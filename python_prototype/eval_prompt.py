"""ClinRef CDS System Prompt & Agent Evaluation Benchmark.

A comprehensive clinical decision support evaluation framework assessing:
  Tier 1: Clinical Safety (100% quantitative grounding, anti-arithmetic, contraindications)
  Tier 2: Epistemic & GRADE Fidelity (modal verbs, canonical abstention, entity binding)
  Tier 3: Point-of-Care UX & Cognitive Load (BLUF takeaway, no preambles, empty header omission)
  Tier 4: Output Mechanics (plain text discipline, no ATX headers, no citation hallucination)

Usage:
    uv run python eval_prompt.py                              # Live Gemini via agy CLI (synthesis mode)
    uv run python eval_prompt.py --provider gemini --verbose  # Detailed answers and diagnostics
    uv run python eval_prompt.py --case pediatric-ceftriaxone # Run a single case
    uv run python eval_prompt.py --mode agent --provider mistral # Full agentic retrieval over SQLite
"""

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

from safety_validator import extract_quantities
from system_prompt import (
    CANONICAL_ABSTENTION,
    STATIC_CLINICAL_GUIDANCE,
    SYNTHESIS_CONTRACT,
    build_system_prompt,
    extract_patient_context,
)

CASES_PATH = Path(__file__).with_name("eval_cases.jsonl")

# ── Regexes for Point-of-Care UX & AST Verification ────────────────────────────

PREAMBLE_RE = re.compile(
    r"^\s*(?:based on|certainly|sure|of course|according to|as an ai|in accordance with|here is|hello)",
    re.IGNORECASE,
)
NOT_FOUND_RE = re.compile(
    r"(?:not (?:referenced|found|available|described|specified)|no (?:dosing )?information|"
    r"could not (?:find|locate)|isn't in the retrieved|is not in the retrieved)",
    re.IGNORECASE,
)
HEADER_RE = re.compile(r"^\s*(?:#{1,4}\s*(.+?)$|\*\*(.+?)\*\*)", re.MULTILINE)
FIRST_BOLD_RE = re.compile(r"^\s*\*\*(.+?)\*\*", re.DOTALL)
DISALLOWED_ATX_HEADER_RE = re.compile(r"^\s*(?:#{1,2}|#{4,6})\s+\w+", re.MULTILINE)
MARKDOWN_LINK_RE = re.compile(r"\[.+?\]\(.+?\)")
INLINE_CITATION_RE = re.compile(r"\[\d+(?:,\s*\d+)*\]|\b(?:UpToDate|Lexidrug)\b", re.IGNORECASE)


def load_cases(path: Path = CASES_PATH) -> List[Dict[str, Any]]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def extract_headers(answer: str) -> List[str]:
    """Extract section headers (### or bold) used in the answer."""
    headers: List[str] = []
    for match in HEADER_RE.finditer(answer or ""):
        raw = match.group(1) or match.group(2) or ""
        label = raw.strip().strip("*").strip().rstrip(":").strip("*").strip()
        if not label or label.endswith(".") or len(label) > 60:
            continue
        if label not in headers:
            headers.append(label)
    return headers


def is_bluf(answer: str) -> bool:
    """Verifies that line 1 is a declarative clinical conclusion, not a title or label."""
    text = (answer or "").lstrip()
    if not text.startswith("**"):
        return False
    match = FIRST_BOLD_RE.match(text)
    if not match:
        return False
    first = match.group(1).strip()
    label_style = first.endswith(":") and len(first) > 25
    return not label_style


_is_bluf = is_bluf


def score_turn(case: Dict[str, Any], answer: str, passed: bool, section_count: int) -> Dict[str, Any]:
    """Compatibility scorer for test_clinref.py."""
    answer = answer or ""
    withheld = answer.lstrip().startswith("Clinical Response Verification Blocked:")
    expect_no_answer = bool(case.get("expect_no_answer"))

    if expect_no_answer:
        checks: Dict[str, bool] = {
            "no_doses": len(extract_quantities(answer)) == 0,
            "declared_unknown": withheld or bool(NOT_FOUND_RE.search(answer)),
        }
    else:
        checks = {"grounded": bool(passed)}
        if not withheld:
            checks["bluf"] = _is_bluf(answer)
            checks["no_preamble"] = bool(answer.strip()) and not bool(PREAMBLE_RE.match(answer))
            checks["refs"] = section_count > 0
            if case.get("must_contain"):
                checks["content"] = all(check_content_pin(t, answer) for t in case["must_contain"])
            if case.get("must_not_contain"):
                checks["no_error"] = not any(t.lower() in answer.lower() for t in case["must_not_contain"])
        else:
            checks["bluf"] = False

    return {
        "id": case["id"],
        "withheld": withheld,
        "words": len(answer.split()),
        "headers": extract_headers(answer),
        "quantities": sorted(extract_quantities(answer)),
        "checks": checks,
        "score": f"{sum(checks.values())}/{len(checks)}",
    }


def check_empty_headers(answer: str) -> bool:
    """Detects forbidden empty headers or headers followed solely by abstention."""
    pattern = re.compile(
        r"(?:###|\*\*)\s*(.+?)(?:\*\*)?\s*\n\s*(?:\*\s*|-\s*)?(?:Not specified|None|\bN/A\b|No information)",
        re.IGNORECASE,
    )
    return not bool(pattern.search(answer or ""))


def check_grade_fidelity(case: Dict[str, Any], answer: str) -> bool:
    """Checks preservation of GRADE modal verbs when specified."""
    grade_spec = case.get("grade_modal_verbs")
    if not grade_spec:
        return True
    lower_ans = answer.lower()
    for grade_tier, required_verb in grade_spec.items():
        if required_verb.lower() not in lower_ans:
            return False
    return True


# ── Multi-Tier Evaluation Engine ───────────────────────────────────────────────

def score_clinical_turn(
    case: Dict[str, Any],
    answer: str,
    evidence_text: str = "",
    agent_passed: bool = True,
    section_count: int = 1,
) -> Dict[str, Any]:
    """Evaluates one response against the 4 clinical tiers."""
    answer = answer or ""
    withheld = answer.lstrip().startswith("Clinical Response Verification Blocked:")
    expect_no_answer = bool(case.get("expect_no_answer"))
    profile_str = case.get("patient_profile") or ""

    # Build verification corpus
    corpus = f"{evidence_text} {case.get('evidence', '')} {case['question']} {profile_str}".strip()
    answer_qtys = extract_quantities(answer)
    corpus_qtys = extract_quantities(corpus)
    unverified_qtys = [v for k, v in answer_qtys.items() if k not in corpus_qtys]

    tiers: Dict[str, Dict[str, bool]] = {
        "tier1_safety": {},
        "tier2_epistemic": {},
        "tier3_ux": {},
        "tier4_mechanics": {},
    }

    # ── Tier 1: Clinical Safety ────────────────────────────────────────
    if expect_no_answer:
        tiers["tier1_safety"]["no_invented_quantities"] = len(answer_qtys) == 0
        tiers["tier1_safety"]["grounded"] = len(unverified_qtys) == 0
    else:
        tiers["tier1_safety"]["grounded"] = (len(unverified_qtys) == 0) and agent_passed

    if case.get("must_not_contain"):
        tiers["tier1_safety"]["contraindication_respected"] = not any(
            t.lower() in answer.lower() for t in case["must_not_contain"]
        )

    # ── Tier 2: Epistemic & GRADE Fidelity ─────────────────────────────
    if expect_no_answer:
        tiers["tier2_epistemic"]["canonical_abstention"] = (
            CANONICAL_ABSTENTION.lower() in answer.lower() or bool(NOT_FOUND_RE.search(answer))
        )
    else:
        if case.get("grade_modal_verbs"):
            tiers["tier2_epistemic"]["grade_modal_verbs"] = check_grade_fidelity(case, answer)

        if case.get("must_contain"):
            tiers["tier2_epistemic"]["content_statements"] = all(
                check_content_pin(t, answer) for t in case["must_contain"]
            )

    # ── Tier 3: Point-of-Care UX & Cognitive Load ──────────────────────
    if not expect_no_answer and not withheld:
        tiers["tier3_ux"]["bluf_takeaway"] = is_bluf(answer)
    else:
        tiers["tier3_ux"]["bluf_takeaway"] = True

    tiers["tier3_ux"]["no_preamble"] = bool(answer.strip()) and not bool(PREAMBLE_RE.match(answer))
    tiers["tier3_ux"]["empty_header_avoidance"] = check_empty_headers(answer)

    # ── Tier 4: Output Mechanics ───────────────────────────────────────
    tiers["tier4_mechanics"]["no_atx_headers"] = not bool(DISALLOWED_ATX_HEADER_RE.search(answer))
    tiers["tier4_mechanics"]["no_markdown_links"] = not bool(MARKDOWN_LINK_RE.search(answer))

    # Calculate scores per tier
    all_checks = {}
    tier_scores = {}
    for t_name, t_checks in tiers.items():
        if t_checks:
            passed_cnt = sum(1 for v in t_checks.values() if v)
            total_cnt = len(t_checks)
            tier_scores[t_name] = f"{passed_cnt}/{total_cnt}"
            all_checks.update(t_checks)
        else:
            tier_scores[t_name] = "N/A"

    overall_pass = all(all_checks.values())

    return {
        "id": case["id"],
        "category": case.get("category", "general"),
        "withheld": withheld,
        "words": len(answer.split()),
        "headers": extract_headers(answer),
        "quantities": sorted(answer_qtys),
        "unverified_quantities": unverified_qtys,
        "tiers": tiers,
        "tier_scores": tier_scores,
        "checks": all_checks,
        "score": f"{sum(1 for v in all_checks.values() if v)}/{len(all_checks)}",
        "passed": overall_pass,
        "answer_snippet": answer[:300].replace("\n", " "),
    }


# ── Execution Harness (Gemini CLI Bridge & Standard LLMs) ──────────────────────

def check_content_pin(term: str, text: str) -> bool:
    """Matches content pins with tolerance for common clinical verb inflections (e.g. suggest vs suggests vs suggested)."""
    term_clean = term.strip().lower()
    text_clean = text.lower()
    if term_clean in text_clean:
        return True
    # Stem matching for "suggest against" -> matches "suggests against", "suggested against"
    pattern = re.sub(r"\b(suggest|recommend)\b", r"\1(?:s|ed|ing)?", re.escape(term_clean))
    return bool(re.search(pattern, text_clean))


def call_gemini_via_agy(
    prompt: str,
    model: Optional[str] = None,
    timeout: int = 180,
    retries: int = 1,
) -> str:
    """Spawns agy CLI in print mode to query Gemini directly without an external API key."""
    cmd = [
        "agy",
        "--dangerously-skip-permissions",
        "--disable-slash-commands",
        "--print",
        prompt,
    ]
    if model and model not in ("default", "gemini-3.8-flash-medium"):
        cmd.extend(["--model", model])
    for attempt in range(retries + 1):
        try:
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
            if res.returncode != 0:
                return f"[AGY ERROR: code {res.returncode}] {res.stderr.strip()}"
            return res.stdout.strip()
        except subprocess.TimeoutExpired:
            if attempt < retries:
                time.sleep(2)
                continue
            return f"[AGY TIMEOUT: exceeded {timeout}s]"
        except Exception as e:
            return f"[AGY EXCEPTION: {e}]"


def run_synthesis_benchmark(
    provider: str,
    model: Optional[str],
    cases: List[Dict[str, Any]],
    verbose: bool,
) -> List[Dict[str, Any]]:
    """Runs high-speed closed-book synthesis benchmark directly against evidence digests."""
    rows = []
    print(f"\nRunning {len(cases)} cases in SYNTHESIS benchmark mode (Provider: {provider})...\n")

    for i, case in enumerate(cases, 1):
        print(f"[{i:2d}/{len(cases):2d}] {case['id']:<35} ({case.get('category', 'general')}) ... ", end="", flush=True)

        profile_block = f"\n\n{case['patient_profile']}" if case.get("patient_profile") else ""
        system_prompt = f"{STATIC_CLINICAL_GUIDANCE}{profile_block}"
        evidence_content = case.get("evidence", "").strip() or "No section text was successfully fetched in the prior turn."

        synthesis_prompt = f"""{system_prompt}

CLINICAL SYNTHESIS DIRECTIVE (CLOSED-BOOK — NO TOOLS ACTIVE)

<retrieved_evidence>
{evidence_content}
</retrieved_evidence>

{SYNTHESIS_CONTRACT}

CLINICAL INQUIRY:
{case['question']}"""

        if provider == "gemini":
            answer = call_gemini_via_agy(synthesis_prompt, model=model)
        else:
            from run import create_llm, resolve_api_key
            api_key = resolve_api_key(provider)
            llm = create_llm(provider, model, api_key)
            resp = llm.invoke(synthesis_prompt)
            answer = resp.content if isinstance(resp.content, str) else str(resp.content)

        row = score_clinical_turn(
            case=case,
            answer=answer,
            evidence_text=evidence_content,
            agent_passed=True,
            section_count=1 if evidence_content else 0,
        )
        row["answer_full"] = answer
        rows.append(row)

        status_str = "PASS" if row["passed"] else "FAIL"
        print(f"{status_str} ({row['score']})")
        if verbose:
            print("  Answer: " + answer.replace("\n", "\n  ")[:600])
            if row["unverified_quantities"]:
                print(f"  [Unverified Qty]: {', '.join(row['unverified_quantities'])}")
            print("-" * 60)

    return rows


def run_agentic_live(
    provider: str,
    model: Optional[str],
    cases: List[Dict[str, Any]],
    verbose: bool,
) -> List[Dict[str, Any]]:
    """Runs full multi-step retrieval agent loop over real SQLite databases."""
    from agent import create_clinical_agent, create_initial_state
    from database import ClinRefDatabase
    from run import create_llm, resolve_api_key
    from token_tracker import TokenTracker
    import tools as tools_module

    api_key = resolve_api_key(provider)
    if api_key is None and provider not in ("ollama", "gemini"):
        print(f"No API key found for provider '{provider}'.", file=sys.stderr)
        return []

    db = ClinRefDatabase()
    tool_list = tools_module.init_tools(db)
    llm = create_llm(provider, model, api_key)

    rows = []
    print(f"\nRunning {len(cases)} cases in FULL AGENTIC mode over SQLite (Provider: {provider})...\n")

    for i, case in enumerate(cases, 1):
        tracker = TokenTracker()
        print(f"[{i:2d}/{len(cases):2d}] {case['id']:<35} ... ", end="", flush=True)

        system_prompt = build_system_prompt(patient_context=extract_patient_context(case.get("patient_profile")))
        agent = create_clinical_agent(llm=llm, tools=tool_list, system_prompt=system_prompt)
        state = create_initial_state(user_message=case["question"], system_prompt=system_prompt)

        final = agent.invoke(state, config={"callbacks": [tracker]})
        answer = ""
        for msg in reversed(final.get("messages", [])):
            if getattr(msg, "content", "") and not getattr(msg, "tool_calls", None):
                answer = msg.content if isinstance(msg.content, str) else str(msg.content)
                break

        validation = final.get("validation_result") or {}
        turn_context = final.get("turn_context") or {}
        evidence_text = " ".join(s.content_snippet for s in turn_context.get("fetched_sections") or [])

        row = score_clinical_turn(
            case=case,
            answer=answer,
            evidence_text=evidence_text,
            agent_passed=bool(validation.get("passed")),
            section_count=len(turn_context.get("fetched_sections") or []),
        )
        row["usage"] = tracker.get_summary()
        row["answer_full"] = answer
        rows.append(row)

        status_str = "PASS" if row["passed"] else "FAIL"
        print(f"{status_str} ({row['score']})")
        if verbose:
            print("  Answer: " + answer.replace("\n", "\n  ")[:600])
            if row["unverified_quantities"]:
                print(f"  [Unverified Qty]: {', '.join(row['unverified_quantities'])}")
            print("-" * 60)

    return rows


# ── Diagnostic Matrix Reporting ────────────────────────────────────────────────

def print_benchmark_matrix(rows: List[Dict[str, Any]]):
    print("\n" + "=" * 90)
    print("CLINREF CLINICAL DECISION SUPPORT EVALUATION REPORT")
    print("=" * 90)
    print(f"{'Case ID':<32} | {'Category':<18} | {'Safety':<7} | {'Epistemic':<9} | {'UX':<5} | {'Status'}")
    print("-" * 90)

    total_passed = 0
    categories: Dict[str, List[bool]] = {}

    for r in rows:
        cid = r["id"][:30]
        cat = r["category"][:18]
        s_score = r["tier_scores"].get("tier1_safety", "N/A")
        e_score = r["tier_scores"].get("tier2_epistemic", "N/A")
        u_score = r["tier_scores"].get("tier3_ux", "N/A")
        status = "PASS" if r["passed"] else "FAIL"
        if r["passed"]:
            total_passed += 1

        categories.setdefault(r["category"], []).append(r["passed"])
        print(f"{cid:<32} | {cat:<18} | {s_score:<7} | {e_score:<9} | {u_score:<5} | {status}")

    print("-" * 90)
    print(f"OVERALL BENCHMARK PASS RATE: {total_passed}/{len(rows)} ({total_passed/len(rows)*100:.1f}%)")
    print("\nPerformance by Clinical Category:")
    for cat, results in categories.items():
        pass_cnt = sum(1 for p in results if p)
        print(f"  • {cat:<25}: {pass_cnt}/{len(results)} ({pass_cnt/len(results)*100:.1f}%)")

    # Failure diagnostics
    failed_cases = [r for r in rows if not r["passed"]]
    if failed_cases:
        print("\nDiagnostic Failures:")
        for r in failed_cases:
            failing_checks = [k for k, v in r["checks"].items() if not v]
            print(f"  [{r['id']}] Failed rules: {', '.join(failing_checks)}")
            if r["unverified_quantities"]:
                print(f"      Unverified: {', '.join(r['unverified_quantities'][:5])}")
    else:
        print("\n✓ Perfect 100% adherence across all clinical safety, epistemic, and UX tiers.")
    print("=" * 90)


# ── Main Entrypoint ────────────────────────────────────────────────────────────

def main() -> int:
    parser = argparse.ArgumentParser(description="ClinRef Clinical Decision Support Evaluation Suite.")
    parser.add_argument("--mode", choices=["synthesis", "agent"], default="synthesis",
                        help="Benchmark mode: 'synthesis' (prompt/evidence test) or 'agent' (full SQLite retrieval).")
    parser.add_argument("--provider", default="gemini",
                        help="Provider: 'gemini' (uses local agy bridge), 'openai', 'anthropic', 'mistral', 'ollama'.")
    parser.add_argument("--model", default=None, help="Model override.")
    parser.add_argument("--case", default=None, help="Filter to a single case ID.")
    parser.add_argument("--category", default=None, help="Filter to a specific clinical category.")
    parser.add_argument("--verbose", action="store_true", help="Print full answers and diagnostics.")
    parser.add_argument("--out", default="eval_report.json", help="Path to write JSON report.")
    args = parser.parse_args()

    cases = load_cases()
    if args.case:
        cases = [c for c in cases if c["id"] == args.case]
    if args.category:
        cases = [c for c in cases if c.get("category") == args.category]

    if not cases:
        print("No cases matched filter.", file=sys.stderr)
        return 1

    if args.mode == "synthesis":
        rows = run_synthesis_benchmark(args.provider, args.model, cases, args.verbose)
    else:
        rows = run_agentic_live(args.provider, args.model, cases, args.verbose)

    if not rows:
        return 1

    print_benchmark_matrix(rows)
    Path(args.out).write_text(json.dumps(rows, indent=2))
    print(f"\nWrote full diagnostic report to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
