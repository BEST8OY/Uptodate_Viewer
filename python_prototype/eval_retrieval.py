"""Tool Retrieval Evaluation Benchmark for ClinRef.

Benchmarks Phase 1 LLM tool-calling behavior, schema adherence, query formulation,
and evidence retrieval against root SQLite databases using Gemini via local agy CLI bridge.
"""

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from database import ClinRefDatabase
import tools as tools_module

CASES = [
    {
        "id": "afib-rate-control-sanitization",
        "category": "query_sanitization",
        "question": "What is the recommended initial rate control medication and dose for a 65-year-old male with new onset atrial fibrillation and eGFR 28?",
        "expected_first_tool": "searchTopics",
        "clean_query_terms": ["atrial fibrillation", "afib"],
        "forbidden_query_terms": ["65", "male", "egfr", "28", "initial", "recommended", "medication", "dose"],
        "expected_topic_id": "1022",
        "should_fetch_sections": True,
        "is_calculator": False,
    },
    {
        "id": "curb65-calculator-short-circuit",
        "category": "calculator_discipline",
        "question": "What are the clinical criteria and point values for the CURB-65 severity score in community acquired pneumonia?",
        "expected_first_tool": "searchTopics",
        "clean_query_terms": ["curb-65", "curb 65", "curb"],
        "expected_topic_id": "16316",
        "should_not_call_sections": True,
        "is_calculator": True,
    },
    {
        "id": "paxlovid-simvastatin-multi-drug",
        "category": "parallel_search",
        "question": "Can I co-administer Paxlovid and simvastatin for an outpatient with COVID-19?",
        "expected_first_tool": "searchTopics",
        "must_search_both": [["paxlovid", "nirmatrelvir"], ["simvastatin"]],
        "is_calculator": False,
    },
    {
        "id": "afib-urgent-rate-control-table-graphics",
        "category": "table_graphics",
        "question": "What are the specific IV bolus doses and infusion rates in the urgent rate control medications table for atrial fibrillation?",
        "expected_first_tool": "searchTopics",
        "clean_query_terms": ["atrial fibrillation", "afib"],
        "expected_graphic_id": "141646",
        "is_calculator": False,
    },
    {
        "id": "apixaban-monograph-dosing",
        "category": "dose_provenance",
        "question": "What is the standard oral dosing, frequency, and renal dose adjustment for apixaban in nonvalvular atrial fibrillation?",
        "expected_first_tool": "searchTopics",
        "clean_query_terms": ["apixaban"],
        "expected_topic_type": "drug",
        "expected_topic_id": "85642",
        "should_fetch_sections": True,
        "is_calculator": False,
    },
    {
        "id": "copd-exacerbation-long-article-pinpoint",
        "category": "section_pinpointing",
        "question": "In a hospitalized patient with acute exacerbation of COPD, what are the indications and blood gas thresholds for noninvasive positive pressure ventilation (NIPPV)?",
        "expected_first_tool": "searchTopics",
        "clean_query_terms": ["copd", "copd exacerbation"],
        "expected_topic_id": "1461",
        "allow_search_topic_sections": True,
        "is_calculator": False,
    },
    {
        "id": "gout-colchicine-conversational-sanitization",
        "category": "query_sanitization",
        "question": "I have an 82-year-old female presenting with an acute severe podagra gout flare and stage 4 CKD, what can I prescribe?",
        "expected_first_tool": "searchTopics",
        "clean_query_terms": ["gout", "acute gout", "podagra", "colchicine"],
        "forbidden_query_terms": ["82", "female", "presenting", "ckd", "stage 4", "prescribe", "what can i"],
        "is_calculator": False,
    },
    {
        "id": "typeahead-vocabulary-refinement",
        "category": "typeahead_refinement",
        "question": "What is the diagnostic evaluation and empiric treatment for pneumon in an immunocompetent adult?",
        "expected_first_tool": "searchTopics",
        "initial_query_substring": "pneumon",
        "is_calculator": False,
    },
    {
        "id": "cha2ds2-vasc-calculator-short-circuit",
        "category": "calculator_discipline",
        "question": "What are the clinical criteria, point allocations, and risk stratification thresholds for the CHA2DS2-VASc score in nonvalvular atrial fibrillation?",
        "expected_first_tool": "searchTopics",
        "clean_query_terms": ["cha2ds2-vasc", "cha2ds2 vasc", "cha2ds2"],
        "expected_topic_id": "99815",
        "should_not_call_sections": True,
        "is_calculator": True,
    },
    {
        "id": "methotrexate-boxed-warning-monograph",
        "category": "dose_provenance",
        "question": "What is the initial oral dosing schedule and boxed warning for methotrexate in active rheumatoid arthritis?",
        "expected_first_tool": "searchTopics",
        "clean_query_terms": ["methotrexate"],
        "expected_topic_type": "drug",
        "expected_topic_id": "9630",
        "should_fetch_sections": True,
        "is_calculator": False,
    },
    {
        "id": "enoxaparin-in-topic-section-search",
        "category": "section_pinpointing",
        "question": "In an adult patient with severe renal impairment (CrCl < 30 mL/min), what is the recommended dose adjustment for enoxaparin when treating deep vein thrombosis?",
        "expected_first_tool": "searchTopics",
        "clean_query_terms": ["enoxaparin"],
        "expected_topic_id": "9407",
        "allow_search_topic_sections": True,
        "is_calculator": False,
    },
    {
        "id": "cap-empiric-regimens-table-graphics",
        "category": "table_graphics",
        "question": "According to the inpatient community-acquired pneumonia treatment table, what are the preferred antibiotic regimens for hospitalized adults without risk factors for MRSA or Pseudomonas?",
        "expected_first_tool": "searchTopics",
        "clean_query_terms": ["community acquired pneumonia", "pneumonia"],
        "expected_graphic_id": "150234",
        "is_calculator": False,
    },
]


def call_gemini_via_agy(
    prompt: str,
    model: Optional[str] = None,
    timeout: int = 180,
    retries: int = 1,
) -> str:
    """Spawns agy CLI in print mode to query Gemini directly with retry on timeout."""
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
            return "[AGY TIMEOUT: exceeded timeout]"
        except Exception as e:
            return f"[AGY EXCEPTION: {e}]"


def parse_tool_calls_from_response(raw: str) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    """Extract tool calls and preliminary text from raw LLM output."""
    raw = raw.strip()
    # Find JSON blocks inside markdown code fence or raw json
    code_match = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", raw, re.DOTALL)
    json_str = code_match.group(1) if code_match else raw

    # Look for naked JSON object
    if not code_match:
        brace_match = re.search(r"(\{.*\})", raw, re.DOTALL)
        if brace_match:
            json_str = brace_match.group(1)

    try:
        data = json.loads(json_str)
        if isinstance(data, dict):
            calls = data.get("tool_calls") or []
            if not calls and "tool_name" in data:
                calls = [{"name": data["tool_name"], "args": data.get("arguments", {})}]
            text = data.get("text") or data.get("message")
            return calls, text
    except Exception:
        pass

    # If parsing failed, treat raw as text output
    return [], raw


def execute_tool_call(name: str, args: Dict[str, Any], tools_by_name: Dict[str, Any]) -> Dict[str, Any]:
    """Execute a single tool call against ClinRefDatabase."""
    norm_name = name.strip()
    # Support camelCase and snake_case lookup
    tool_fn = tools_by_name.get(norm_name)
    if not tool_fn:
        # Try alternate casing
        for k, v in tools_by_name.items():
            if k.lower() == norm_name.lower():
                tool_fn = v
                break

    if not tool_fn:
        return {"error": f"Tool '{name}' not found. Available: {list(tools_by_name.keys())}"}

    try:
        res = tool_fn.invoke(args)
        if hasattr(res, "model_dump"):
            return res.model_dump()
        elif hasattr(res, "dict"):
            return res.dict()
        return res
    except Exception as e:
        return {"error": f"Tool execution failed: {e}"}


PROMPT_BASELINE = """You are ClinRef AI, an evidence-grounded clinical reference for clinicians. Every clinical fact in your answer must come from UpToDate sections retrieved in this turn.

### 1. RETRIEVE FIRST — Database Schema & Tool Mechanics
Follow the deterministic retrieval sequence: `searchTopics(query)` → `getTopicOverview(topicId)` → `getTopicSectionsText(topicId, sectionIds)` or `getGraphicContent(graphicId)`.
- Query Formulation: Search using 1–3 plain clinical keywords naming a single condition or drug (e.g., "atrial fibrillation", "apixaban"). Never pass full questions, patient demographics ("65yo male"), lab values ("eGFR 28"), or sentences to search. For multi-drug or comparative queries, issue parallel searches for each entity.
- Handling Empty Hits: If `results` is empty, immediately retry using the suggested terms in `refine_with` from the typeahead vocabulary.
- Navigating Topic Types:
  - `medical` (Disease & Clinical Topics): Comprehensive clinical reviews covering etiology, clinical presentation, physical examination, diagnostic evaluation, differential diagnosis, acute and long-term management, GRADE recommendations, complications, and prognosis.
  - `drug` (Lexidrug Monographs): Mandatory for pharmacotherapy. Provides granular dosing, administration, organ adjustments, adverse reactions, and contraindications.
  - `calc` (Clinical Calculators): Returns full scoring algorithms and formulas directly in `getTopicOverview` under section `FULL`. Do not call section retrieval for calculators.
- Dose Provenance Rule: Before stating any milligram dose, infusion rate, or interval, ensure it is verified in the retrieved section text, attached table (`graphic_table`), or the dedicated `drug` monograph topic that owns it.
- Section Selection & In-Article Search: Copy section IDs verbatim from the overview; never invent or renumber them. For lengthy articles with dozens of sections, call `searchTopicSections(topicId, keywords)` to pinpoint relevant section IDs and snippets.
- Tabular Data: Clinical tables (diagnostic criteria, risk scores, treatment algorithms, dosing matrices, drug interactions) are indexed under `graphics` (`isTable: true`). Fetch them using `getGraphicContent(graphicId)`. Never request non-table graphics."""

PROMPT_REWRITE = """You are ClinRef AI, an evidence-grounded clinical reference for clinicians. You possess zero internal medical memory—every clinical assertion, dose, and recommendation must be retrieved from UpToDate database tools in this turn. Never emit clinical text until relevant section text or tables are in context.

### 1. RETRIEVE FIRST — Database Schema & Tool Mechanics
Retrieval Pipeline: `searchTopics(query)` → `getTopicOverview(topicId)` → `getTopicSectionsText(topicId, sectionIds)` or `getGraphicContent(graphicId)`.
- Query Discipline: Search 1–3 plain clinical keywords naming a single condition or drug (e.g., "atrial fibrillation", "apixaban"). Never include patient demographics ("65yo male"), lab values ("eGFR 28"), questions, or conversational sentences. For multi-drug or comparative queries, issue parallel searches for each individual entity.
- Zero-Hit Recovery: If results is empty, immediately retry using terms from refine_with or a broader single clinical noun.
- Topic Type Routing:
  - `medical` (Disease Topics): Comprehensive clinical reviews and GRADE recommendations.
  - `drug` (Lexidrug Monographs): Authoritative source for pharmacotherapy. Mandatory for exact milligram dosing, titration, organ impairment adjustments, and contraindications.
  - `calc` (Calculators): `getTopicOverview` already delivers the complete algorithm, point scoring, and risk stratification under section `FULL`. When a calculator topic is retrieved for a scoring query, stop retrieval immediately—do not fetch disease articles or call `getTopicSectionsText`.
- Actionable Dose Provenance: Disease topics often recommend an agent without giving exact doses or renal cutoffs. When specific dosing, titration, or renal/hepatic adjustments are required, retrieve the dedicated drug monograph (`topicType: drug`).
- Section Selection & Batching: Copy section IDs verbatim from `getTopicOverview` or `searchTopicSections`; never invent or renumber them. Batch up to 10 relevant section IDs in a single `getTopicSectionsText` call. For long articles (>15 sections), call `searchTopicSections(topicId, query)` to pinpoint exact section IDs.
- Tabular Data: Clinical tables (risk scores, staging criteria, dosing matrices) are indexed in `graphics` (`isTable: true`). Retrieve them using `getGraphicContent(graphicId)`. Never request non-table graphics.
- Outlines Are Not Evidence: Topic overviews contain only section headings, not clinical text. You must fetch section text via `getTopicSectionsText` or tables via `getGraphicContent` before answering."""

TOOL_DECLARATIONS = """AVAILABLE TOOLS:
1. searchTopics(query: string)
   Search the UpToDate index by a single medical condition, syndrome, or generic drug name (1–3 words max).
   Returns candidate {id, title} pairs and refine_with keywords.

2. getTopicOverview(topicId: string)
   Retrieve section outline hierarchy (sectionIds and titles), available table graphics (graphicIds), related topics, and topicType ('medical', 'drug', 'calc').
   Note: For 'calc' topics, full formula markdown is returned directly under section 'FULL'. Standard topics return outline titles only.

3. getTopicSectionsText(topicId: string, sectionIds: string[])
   Retrieve full unredacted clinical text for up to 10 sectionIds within a single topicId.

4. searchTopicSections(topicId: string, query: string)
   Targeted 1-2 keyword search inside a single topicId to locate specific section IDs and snippets.

5. getGraphicContent(graphicId: string)
   Retrieve structured Markdown table content for items listed under graphics where isTable is true."""


def run_single_benchmark_case(
    case: Dict[str, Any],
    system_prompt: str,
    tools_by_name: Dict[str, Any],
    max_rounds: int = 4,
    model: Optional[str] = None,
    verbose: bool = False,
) -> Dict[str, Any]:
    """Execute multi-round retrieval agent loop for one case and evaluate."""
    history: List[Dict[str, Any]] = []
    executed_tool_calls: List[Dict[str, Any]] = []
    premature_texts: List[str] = []
    completed = False

    turn_instructions = (
        f"{system_prompt}\n\n{TOOL_DECLARATIONS}\n\n"
        "RESPONSE FORMAT INSTRUCTIONS:\n"
        "To execute tool calls, respond ONLY with a JSON object:\n"
        '```json\n{"tool_calls": [{"name": "toolName", "args": {"arg1": "val1"}}]}\n```\n'
        "If you have retrieved all necessary evidence sections or tables and are ready to stop retrieval, respond ONLY with:\n"
        '```json\n{"done": true, "reason": "All required sections/tables retrieved"}\n```\n'
        "NEVER output clinical answers during this retrieval phase."
    )

    for round_idx in range(1, max_rounds + 1):
        # Assemble conversation context
        messages_block = f"CLINICAL INQUIRY:\n{case['question']}\n\n"
        if history:
            messages_block += "PRIOR RETRIEVAL ROUNDS & RESULTS:\n"
            for step in history:
                messages_block += f"[Round {step['round']}] Model emitted tool calls: {json.dumps(step['calls'])}\n"
                messages_block += f"Tool execution result:\n{step['result_summary']}\n\n"

        done_hint = '{"done": true}'
        prompt = f"{turn_instructions}\n\n{messages_block}\nRespond with your next tool calls or {done_hint}:"

        raw_resp = call_gemini_via_agy(prompt, model=model, timeout=180)
        tool_calls, preliminary_text = parse_tool_calls_from_response(raw_resp)

        if verbose:
            print(f"\n  --- Round {round_idx} Raw Response ---")
            print(f"  {raw_resp[:300]}...")

        # Check for done marker
        if not tool_calls and "done" in raw_resp.lower() and "true" in raw_resp.lower():
            completed = True
            break

        # Check for premature clinical answer text
        if preliminary_text and len(preliminary_text.split()) > 15:
            # Check if this contains clinical conclusions
            if not raw_resp.strip().startswith("{") and not "tool_calls" in raw_resp:
                premature_texts.append(preliminary_text)

        if not tool_calls:
            # No tool calls and not done
            break

        # Execute tool calls
        round_results = []
        for tc in tool_calls:
            name = tc.get("name", "")
            args = tc.get("args") or tc.get("arguments") or {}
            executed_tool_calls.append({"round": round_idx, "name": name, "args": args})

            res = execute_tool_call(name, args, tools_by_name)

            # Summarize result for next turn
            summary = ""
            if name.lower() == "searchtopics":
                hits = res.get("results") or []
                summary = f"searchTopics returned {len(hits)} hits: " + json.dumps([{"id": h["id"], "title": h["title"]} for h in hits[:5]])
                if res.get("refine_with"):
                    summary += f" | refine_with: {res['refine_with'][:5]}"
            elif name.lower() == "gettopicoverview":
                secs = res.get("sections") or []
                graphs = res.get("graphics") or []
                summary = f"getTopicOverview for [{res.get('topicId')}] (type: {res.get('topicType')}, title: {res.get('title')}): {len(secs)} sections, {len(graphs)} graphics."
                if res.get("topicType") == "calc":
                    summary += f" [CALCULATOR: Full markdown delivered under FULL ({len(res.get('markdown', ''))} chars)]"
                else:
                    sample_secs = [{"id": s.get("id"), "title": s.get("title")} for s in secs[:8]]
                    summary += f"\nSection Outline sample: {json.dumps(sample_secs)}"
                    if graphs:
                        summary += f"\nGraphics: {json.dumps([{'id': g.get('id'), 'title': g.get('title'), 'isTable': g.get('isTable')} for g in graphs])}"
            elif name.lower() == "gettopicsectionstext":
                titles = res.get("sectionTitles") or {}
                summary = f"getTopicSectionsText fetched sections {list(titles.keys())} ({len(res.get('markdown', ''))} chars text)."
            elif name.lower() == "searchtopicsections":
                matches = res.get("matches") or []
                summary = f"searchTopicSections matched {len(matches)} sections: " + json.dumps([{"id": m.get("sectionId"), "title": m.get("sectionTitle")} for m in matches[:5]])
            elif name.lower() == "getgraphiccontent":
                summary = f"getGraphicContent fetched table [{res.get('graphicId')}] ({len(res.get('markdown', ''))} chars table markdown)."
            else:
                summary = json.dumps(res)[:300]

            round_results.append(summary)

        history.append({
            "round": round_idx,
            "calls": tool_calls,
            "result_summary": "\n".join(round_results),
        })

        # If calculator case and overview fetched, model has full info
        if case.get("is_calculator") and any(tc.get("name", "").lower() == "gettopicoverview" for tc in tool_calls):
            # Continue one more round to verify if model stops or makes redundant calls
            pass

    # Evaluate the execution trace against case criteria
    score_res = evaluate_retrieval_trace(case, executed_tool_calls, premature_texts, completed)
    return score_res


def evaluate_retrieval_trace(
    case: Dict[str, Any],
    calls: List[Dict[str, Any]],
    premature_texts: List[str],
    completed: bool,
) -> Dict[str, Any]:
    """Score the trace against specific clinical retrieval criteria."""
    checks = {}

    # Check 1: Tool Sequence & First Tool
    first_call = calls[0] if calls else {}
    first_tool_name = first_call.get("name", "")
    checks["first_tool_searchTopics"] = first_tool_name.lower() == "searchtopics"

    # Check 2: Query Sanitization (No forbidden demographic/question noise)
    search_queries = [
        c["args"].get("query") or c["args"].get("q") or c["args"].get("search_query") or ""
        for c in calls if c.get("name", "").lower() == "searchtopics"
    ]
    all_queries_str = " ".join(search_queries).lower()

    if case.get("forbidden_query_terms"):
        forbidden_hits = [w for w in case["forbidden_query_terms"] if w in all_queries_str]
        checks["query_sanitized_no_noise"] = len(forbidden_hits) == 0
    else:
        checks["query_sanitized_no_noise"] = True

    # Word count of queries: ideally <= 3 words
    clean_length = all(len(q.split()) <= 4 for q in search_queries if q)
    checks["query_word_count_le_4"] = clean_length

    # Check 3: Multi-drug / Parallel Search
    if case.get("must_search_both"):
        searched_entities = []
        for entity_options in case["must_search_both"]:
            hit = any(any(opt in q.lower() for opt in entity_options) for q in search_queries)
            searched_entities.append(hit)
        checks["parallel_multi_entity_searched"] = all(searched_entities)

    # Check 4: Calculator Discipline (No redundant section calls if calc)
    if case.get("should_not_call_sections"):
        called_sections = any(c.get("name", "").lower() == "gettopicsectionstext" for c in calls)
        checks["calculator_no_redundant_sections"] = not called_sections

    # Check 5: Table Graphics Retrieval
    if case.get("expected_graphic_id"):
        called_graphic = any(
            c.get("name", "").lower() == "getgraphiccontent" and
            str(c["args"].get("graphic_id") or c["args"].get("graphicId")) == case["expected_graphic_id"]
            for c in calls
        )
        checks["table_graphic_retrieved"] = called_graphic

    # Check 6: Premature Text Suppression
    checks["no_premature_clinical_text"] = len(premature_texts) == 0

    # Check 7: Dose Provenance / Topic Navigation
    if case.get("expected_topic_id"):
        called_overview = any(
            c.get("name", "").lower() == "gettopicoverview" and
            str(c["args"].get("topic_id") or c["args"].get("topicId")) == case["expected_topic_id"]
            for c in calls
        )
        checks["targeted_topic_overview_fetched"] = called_overview

    # Check 8: In-article pinpointing
    if case.get("allow_search_topic_sections"):
        called_section_search = any(c.get("name", "").lower() == "searchtopicsections" for c in calls)
        called_sections = any(c.get("name", "").lower() == "gettopicsectionstext" for c in calls)
        checks["pinpointed_via_searchTopicSections_or_sections"] = called_section_search or called_sections

    passed_cnt = sum(1 for v in checks.values() if v)
    total_cnt = len(checks)
    overall_pass = passed_cnt == total_cnt

    return {
        "id": case["id"],
        "category": case["category"],
        "checks": checks,
        "score": f"{passed_cnt}/{total_cnt}",
        "passed": overall_pass,
        "calls_count": len(calls),
        "executed_calls": [{"name": c["name"], "args": c["args"]} for c in calls],
        "premature_text_count": len(premature_texts),
    }


def main():
    parser = argparse.ArgumentParser(description="ClinRef Tool Retrieval Evaluation Benchmark.")
    parser.add_argument("--prompt", choices=["baseline", "rewrite", "both"], default="both",
                        help="Prompt candidate to test: 'baseline', 'rewrite', or 'both'.")
    parser.add_argument("--case", default=None, help="Filter to a single case ID.")
    parser.add_argument("--model", default=None, help="Gemini model override (omit to use user active model).")
    parser.add_argument("--verbose", action="store_true", help="Print verbose round execution details.")
    args = parser.parse_args()

    db = ClinRefDatabase()
    tools_list = tools_module.init_tools(db)
    tools_by_name = {t.name: t for t in tools_list}
    # Also add snake_case mappings
    for t in tools_list:
        tools_by_name[t.name] = t
        tools_by_name[t.name.lower()] = t

    cases_to_run = CASES
    if args.case:
        cases_to_run = [c for c in CASES if c["id"] == args.case]

    prompts_to_test = []
    if args.prompt in ("baseline", "both"):
        prompts_to_test.append(("BASELINE (Prompt A)", PROMPT_BASELINE))
    if args.prompt in ("rewrite", "both"):
        prompts_to_test.append(("REWRITE (Prompt B)", PROMPT_REWRITE))

    for prompt_label, prompt_text in prompts_to_test:
        print("\n" + "=" * 90)
        print(f"EVALUATING RETRIEVAL BENCHMARK: {prompt_label}")
        print("=" * 90)
        print(f"{'Case ID':<35} | {'Category':<22} | {'Score':<7} | {'Calls':<5} | {'Status'}")
        print("-" * 90)

        results = []
        for case in cases_to_run:
            print(f"[{case['id']:<35}] ... ", end="", flush=True)
            res = run_single_benchmark_case(
                case=case,
                system_prompt=prompt_text,
                tools_by_name=tools_by_name,
                model=args.model,
                verbose=args.verbose,
            )
            results.append(res)
            status = "PASS" if res["passed"] else "FAIL"
            print(f"{res['category']:<22} | {res['score']:<7} | {res['calls_count']:<5} | {status}")

        print("-" * 90)
        passed_total = sum(1 for r in results if r["passed"])
        print(f"OVERALL {prompt_label} PASS RATE: {passed_total}/{len(results)} ({passed_total/len(results)*100:.1f}%)")

        # Detailed breakdown of failures
        failures = [r for r in results if not r["passed"]]
        if failures:
            print(f"\nFailures in {prompt_label}:")
            for f in failures:
                failed_checks = [k for k, v in f["checks"].items() if not v]
                print(f"  • [{f['id']}] Failed: {', '.join(failed_checks)}")
                print(f"    Executed calls: {json.dumps(f['executed_calls'])}")


if __name__ == "__main__":
    main()
