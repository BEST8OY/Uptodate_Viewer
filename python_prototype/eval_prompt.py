"""Prompt conformance evaluation for ClinRef.

Scoring is deterministic: no LLM judge, no network, no opinion. Each check maps to one line
of the system prompt, so a failed check names the rule that did not hold.

Answer *structure* is deliberately not scored: the prompt names no fixed shapes, so there is no
taxonomy to match. The report prints the bold headers each answer used, for human review.

Usage:
    uv run python eval_prompt.py                       # live run, default provider/model
    uv run python eval_prompt.py --provider mistral --model ministral-14b-latest
    uv run python eval_prompt.py --case cold-adult-management --verbose

Cases live in eval_cases.jsonl:
    id, question
    must_contain / must_not_contain   clinician pins for fluent claims no numeric rule can reject
    expect_no_answer                  the corpus cannot answer this: withhold, or say so in one line
"""

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Optional

from safety_validator import extract_quantities

CASES_PATH = Path(__file__).with_name("eval_cases.jsonl")

PREAMBLE_RE = re.compile(r"^\s*(?:based on|certainly|sure|of course|according to|as an ai)", re.IGNORECASE)
NOT_FOUND_RE = re.compile(
    r"(?:not (?:referenced|found|available|described)|no (?:dosing )?information|"
    r"could not (?:find|locate)|isn't in the retrieved)",
    re.IGNORECASE,
)
HEADER_RE = re.compile(r"^\s*(?:#{1,4}\s*)?\*\*(.+?)\*\*", re.MULTILINE)
FIRST_BOLD_RE = re.compile(r"^\s*\*\*(.+?)\*\*", re.DOTALL)


def load_cases(path: Path = CASES_PATH) -> list[dict]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def extract_headers(answer: str) -> list[str]:
    """Bold labels the answer used, in order — reported for human review, never scored."""
    headers: list[str] = []
    for match in HEADER_RE.finditer(answer or ""):
        label = match.group(1).strip().rstrip(":").strip()
        # A bolded BLUF sentence is not a header: headers are short and do not end in a period.
        if not label or label.endswith(".") or len(label) > 60:
            continue
        if label not in headers:
            headers.append(label)
    return headers


def _is_bluf(answer: str) -> bool:
    """A BLUF is a sentence, not a title.

    `**Management of the common cold:**` opens with bold text but tells the reader nothing, so
    a long bold label ending in a colon is not accepted as a bottom line.
    """
    text = (answer or "").lstrip()
    if not text.startswith("**"):
        return False
    match = FIRST_BOLD_RE.match(text)
    if not match:
        return False
    first = match.group(1).strip()
    label_style = first.endswith(":") and len(first) > 25
    return not label_style


def score_turn(case: dict, answer: str, passed: bool, section_count: int) -> dict:
    """Score one turn against the prompt contract. Pure function; safe to unit-test.

    A case may demand a *withheld* turn (`expect_no_answer`): a question the corpus cannot
    answer must end withheld, or say that the value is not in the retrieved sections — never
    with a drafted regimen.
    """
    answer = answer or ""
    withheld = answer.lstrip().startswith("Clinical Response Verification Blocked:")
    expect_no_answer = bool(case.get("expect_no_answer"))

    if expect_no_answer:
        checks: dict[str, bool] = {
            "no_doses": len(extract_quantities(answer)) == 0,
            "declared_unknown": withheld or bool(NOT_FOUND_RE.search(answer)),
        }
    else:
        checks = {"grounded": bool(passed)}
        if not withheld:
            checks["bluf"] = _is_bluf(answer)
            checks["no_preamble"] = bool(answer.strip()) and not PREAMBLE_RE.match(answer)
            checks["refs"] = section_count > 0
            # Clinician-encoded content pins: the cheapest way to catch a fluent answer that
            # no numeric rule can reject (a shifted boundary, a number on the wrong claim).
            if case.get("must_contain"):
                checks["content"] = all(t.lower() in answer.lower() for t in case["must_contain"])
            if case.get("must_not_contain"):
                checks["no_error"] = not any(
                    t.lower() in answer.lower() for t in case["must_not_contain"]
                )
        else:
            # The turn was withheld but this case has an answer in the corpus.
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


CHECK_ORDER = ["grounded", "no_doses", "declared_unknown", "bluf", "no_preamble", "refs", "content", "no_error"]

CHECK_LABELS = {
    "grounded": "grounding (every value traceable)",
    "no_doses": "no doses invented for an unanswerable question",
    "declared_unknown": "said the value is not in the retrieved sections",
    "bluf": "BLUF first",
    "no_preamble": "no preamble",
    "refs": "references attached",
    "content": "required clinical statements present",
    "no_error": "no known-wrong statement (clinician pin)",
}


def _print_report(rows: list[dict]) -> None:
    columns = [k for k in CHECK_ORDER if any(k in r["checks"] for r in rows)]
    print(f"\n{'case':30s} " + " ".join(f"{k[:4]:>4s}" for k in columns) + "   score")
    for r in rows:
        marks = " ".join(
            ("  - " if k not in r["checks"] else " ok " if r["checks"][k] else " X  ") for k in columns
        )
        print(f"{r['id'][:30]:30s} {marks}   {r['score']}")

    totals = {k: sum(1 for r in rows if k in r["checks"] and not r["checks"][k]) for k in columns}
    failed = {k: v for k, v in totals.items() if v}
    print("\nfailures by rule:" if failed else "\nall checks passed")
    for k, v in sorted(failed.items(), key=lambda kv: -kv[1]):
        print(f"  {v:2d}  {CHECK_LABELS[k]}")

    headers = sorted({h for r in rows for h in r["headers"]})
    if headers:
        print("\nheaders used (informational, not scored):")
        print("  " + " · ".join(headers))


def run_live(provider: str, model: Optional[str], cases: list[dict], verbose: bool) -> list[dict]:
    from agent import create_clinical_agent, create_initial_state
    from database import ClinRefDatabase
    from run import create_llm, resolve_api_key
    from system_prompt import build_system_prompt, extract_patient_context
    from token_tracker import TokenTracker
    import tools as tools_module

    api_key = resolve_api_key(provider)
    if api_key is None and provider != "ollama":
        print(f"No API key found for provider '{provider}'.", file=sys.stderr)
        return []

    db = ClinRefDatabase()
    tool_list = tools_module.init_tools(db)
    llm = create_llm(provider, model, api_key)
    system_prompt = build_system_prompt(patient_context=extract_patient_context(None))
    agent = create_clinical_agent(
        llm=llm,
        tools=tool_list,
        system_prompt=system_prompt,
    )

    rows = []
    for case in cases:
        tracker = TokenTracker()
        print(f"\n▶ {case['id']}: {case['question']}")
        state = create_initial_state(
            user_message=case["question"],
            system_prompt=system_prompt,
        )

        final = agent.invoke(state, config={"callbacks": [tracker]})
        answer = ""
        for msg in reversed(final.get("messages", [])):
            if getattr(msg, "content", "") and not getattr(msg, "tool_calls", None):
                answer = msg.content if isinstance(msg.content, str) else str(msg.content)
                break

        validation = final.get("validation_result") or {}
        turn_context = final.get("turn_context") or {}
        row = score_turn(
            case,
            answer,
            bool(validation.get("passed")),
            len(turn_context.get("fetched_sections") or []),
        )
        row["usage"] = tracker.get_summary()
        row["answer"] = answer[:1200]
        row["trace"] = {
            "tool_calls": sum(len(getattr(m, "tool_calls", None) or []) for m in final.get("messages", [])),
            "gate1_retries": final.get("gate1_retries", 0),
            "gate2_revisions": final.get("gate2_revisions", 0),
            "gate2_escalations": final.get("gate2_escalations", 0),
            "sections": len(turn_context.get("fetched_sections") or []),
        }
        rows.append(row)

        t = row["trace"]
        print(f"  score {row['score']}  words={row['words']}"
              f"{'  WITHHELD' if row['withheld'] else ''}  headers={row['headers'][:4]}")
        print(f"  tools={t['tool_calls']}  sections={t['sections']}  "
              f"g1={t['gate1_retries']}  g2={t['gate2_revisions']}  esc={t['gate2_escalations']}  "
              f"calls={row['usage']['llm_calls']}  tokens={row['usage']['total_tokens']:,}")
        if verbose:
            print("  " + answer.replace("\n", "\n  ")[:1200])
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description="Score ClinRef prompt conformance.")
    parser.add_argument("--provider", default="mistral")
    parser.add_argument("--model", default=None)
    parser.add_argument("--case", default=None, help="Only run one case id")
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument("--out", default="eval_report.json")
    args = parser.parse_args()

    cases = load_cases()
    if args.case:
        cases = [c for c in cases if c["id"] == args.case]
        if not cases:
            print(f"Unknown case id: {args.case}", file=sys.stderr)
            return 2

    rows = run_live(args.provider, args.model, cases, args.verbose)
    if not rows:
        return 1

    _print_report(rows)
    Path(args.out).write_text(json.dumps(rows, indent=2))
    print(f"\nwrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
