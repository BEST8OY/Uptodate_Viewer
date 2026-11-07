"""Self-Correction & Remediation Benchmark for ClinRef AI.

Evaluates the closed-loop recovery and remediation prompts across three suites:
  Suite A: Gate 1 Evidence Gate Pause Recovery (build_evidence_gate_pause_prompt)
  Suite B: Gate 2 Closed-Book Safety Revision (build_revision_prompt)
  Suite C: Loop C Missing Evidence Escalation (build_missing_evidence_prompt)

All test scenarios are grounded 1:1 in real SQLite database content from:
  - utdasset.sqlite (topics 1022, 7027, 8063, 5959, 9630, 85642, 9407, 134695, 3869)
  - unidex.en.sqlite (clinical keyword searches & typeahead vocabulary)

Usage:
    ./benchmark_remediation.sh                          # All 14 scenarios
    ./benchmark_remediation.sh --suite suite_a          # Gate 1 Pause scenarios
    ./benchmark_remediation.sh --suite suite_b          # Gate 2 Safety Revision
    ./benchmark_remediation.sh --suite suite_c          # Loop C Missing Evidence Escalation
    ./benchmark_remediation.sh --case rev-fatal-interval-error --verbose
"""

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from safety_validator import (
    PatientProfile,
    SafetyValidator,
    TurnContext,
    extract_quantities,
)
from system_prompt import (
    CANONICAL_ABSTENTION,
    STATIC_CLINICAL_GUIDANCE,
    SYNTHESIS_CONTRACT,
    build_evidence_gate_pause_prompt,
    build_missing_evidence_prompt,
    build_revision_prompt,
    static_clinical_guidance,
)

# ── Regexes for AST & Format Checking ──────────────────────────────────────────

PREAMBLE_RE = re.compile(
    r"^\s*(?:based on|certainly|sure|of course|according to|as an ai|in accordance with|here is|hello)",
    re.IGNORECASE,
)
HEADER_RE = re.compile(r"^\s*(?:#{1,4}\s*)?\*\*(.+?)\*\*", re.MULTILINE)
FIRST_BOLD_RE = re.compile(r"^\s*\*\*(.+?)\*\*", re.DOTALL)
ATX_HEADER_RE = re.compile(r"^\s*#{1,4}\s+\w+", re.MULTILINE)
MARKDOWN_LINK_RE = re.compile(r"\[.+?\]\(.+?\)")
INLINE_CITATION_RE = re.compile(r"\[\d+(?:,\s*\d+)*\]|\b(?:UpToDate|Lexidrug)\b", re.IGNORECASE)

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

# ── Test Scenarios Grounded in Real SQLite Database Content ─────────────────────

SUITE_A_CASES = [
    {
        "id": "pause-outline-stall",
        "category": "gate1_pause",
        "suite": "suite_a",
        "question": "What is the recommended initial rate control medication and dose for new onset atrial fibrillation with rapid ventricular response?",
        "evidence_summary": """Retrieved Topics:
- [1022] Atrial fibrillation: Overview of management (type: medical)
  Available sections:
  * H2786058395: INITIAL MANAGEMENT OF NEW-ONSET AF
  * H2720161302: Urgent management
  * H1214778358: Hemodynamically unstable patients
  * H2011098342: Hemodynamically stable patients with rapid ventricular response
  Available graphics: none""",
        "expected_tool": "getTopicSectionsText",
        "expected_topic_id": "1022",
        "expected_section_ids": ["H2786058395", "H2720161302", "H1214778358", "H2011098342"],
        "forbidden_tools": ["searchTopics"],
    },
    {
        "id": "pause-zero-hit-stall",
        "category": "gate1_pause",
        "suite": "suite_a",
        "question": "65yo male presenting with sudden severe tearing chest pain radiating to the back, BP 190/110, what is the initial management and diagnostic imaging?",
        "evidence_summary": "(none: no topics or sections have been retrieved yet)",
        "expected_tool": "searchTopics",
        "clean_query_patterns": [
            r"aortic\s+dissection",
            r"acute\s+aortic",
            r"thoracic\s+aortic",
            r"chest\s+pain",
        ],
        "forbidden_query_terms": ["65", "male", "bp", "190", "110", "tearing", "radiating", "presenting"],
        "forbidden_tools": ["getTopicSectionsText", "getGraphicContent"],
    },
    {
        "id": "pause-table-graphic-stall",
        "category": "gate1_pause",
        "suite": "suite_a",
        "question": "What are the specific IV bolus doses and infusion rates in the urgent rate control medications table for atrial fibrillation?",
        "evidence_summary": """Retrieved Topics:
- [1022] Atrial fibrillation: Overview of management (type: medical)
  Available sections:
  * H2786058395: INITIAL MANAGEMENT OF NEW-ONSET AF
  Available graphics:
  * [141646] Urgent rate control medications (Table: true)""",
        "expected_tool": "getGraphicContent",
        "expected_graphic_id": "141646",
        "forbidden_tools": ["searchTopics"],
    },
    {
        "id": "pause-multi-drug-stall",
        "category": "gate1_pause",
        "suite": "suite_a",
        "question": "Can I co-administer Paxlovid and simvastatin for an outpatient with COVID-19?",
        "evidence_summary": """Retrieved Topics:
- [134695] Nirmatrelvir and ritonavir: Drug information (type: drug)
  Available sections:
  * F14479016: Dosing: Adult
  * F14478950: Warnings/Precautions
  Available graphics: none""",
        "expected_tool": "searchTopics",
        "clean_query_patterns": [r"simvastatin"],
        "forbidden_query_terms": ["paxlovid", "nirmatrelvir", "co-administer", "covid-19"],
        "forbidden_tools": [],
    },
]

SUITE_B_CASES = [
    {
        "id": "rev-unit-conversion",
        "category": "gate2_revision",
        "suite": "suite_b",
        "question": "What is the recommended empiric dose of ceftriaxone for adult inpatient community-acquired pneumonia?",
        "patient_profile": None,
        "evidence_snippet": (
            "Topic: Treatment of community-acquired pneumonia in adults who require hospitalization (Topic ID 7027), "
            "Table 150234 (Empiric antibiotic treatment for CAP in hospitalized adults): "
            "Beta lactam without pseudomonal coverage plus coverage for atypical organisms: "
            "Ceftriaxone (1 to 2 g IV once daily) or Cefotaxime (1 to 2 g IV every 8 hours) plus "
            "Azithromycin (500 mg IV or orally once daily) or Doxycycline (100 mg orally twice daily)."
        ),
        "seed_draft": (
            "**Ceftriaxone 1000 mg IV once daily** is the recommended initial dose for hospitalized adults with "
            "community-acquired pneumonia, combined with azithromycin 500 mg daily."
        ),
        "seed_violations": [
            {
                "code": "UNVERIFIED_QTY",
                "detail": "Unverified clinical quantities found in response: 1000 mg. Ensure every quantity or dosage matches the retrieved database section text exactly.",
            }
        ],
        "must_remove_strings": ["1000 mg", "1000mg"],
        "must_contain_strings": ["1 g", "1 to 2 g"],
        "preserved_concepts": ["ceftriaxone", "azithromycin"],
    },
    {
        "id": "rev-synthetic-math",
        "category": "gate2_revision",
        "suite": "suite_b",
        "question": "What is the recommended oral amoxicillin dose for acute otitis media in a 4-year-old child weighing 20 kg?",
        "patient_profile": PatientProfile(age="4 years", conditions=["acute otitis media"], notes="Weight: 20 kg"),
        "evidence_snippet": (
            "Topic: Acute otitis media in children: Treatment (Topic ID 5959), Section H2812503948: "
            "High-dose amoxicillin (80 to 90 mg/kg/day orally divided into two doses) is the first-line treatment of choice "
            "for acute otitis media in pediatric patients."
        ),
        "seed_draft": (
            "**Amoxicillin 1800 mg/day orally divided as 900 mg twice daily** should be prescribed for this 20 kg child "
            "(calculated at 90 mg/kg/day)."
        ),
        "seed_violations": [
            {
                "code": "UNVERIFIED_QTY",
                "detail": "Unverified clinical quantities found in response: 1800 mg, 900 mg. Ensure every quantity or dosage matches the retrieved database section text exactly.",
            }
        ],
        "must_remove_strings": ["1800 mg", "900 mg", "1800mg", "900mg"],
        "must_contain_strings": ["mg/kg"],
        "preserved_concepts": ["amoxicillin", "otitis media"],
    },
    {
        "id": "rev-duration-absence",
        "category": "gate2_revision",
        "suite": "suite_b",
        "question": "What is the treatment dose and duration for uncomplicated cystitis in an adult female?",
        "patient_profile": None,
        "evidence_snippet": (
            "Topic: Acute simple cystitis in female adults (Topic ID 8063), Section H207461713: "
            "First-line antimicrobial options: Nitrofurantoin monohydrate/macrocrystals 100 mg orally twice daily taken with meals "
            "is an appropriate first-line antimicrobial agent."
        ),
        "seed_draft": (
            "**Nitrofurantoin 100 mg orally twice daily for 14 days** is recommended for acute uncomplicated cystitis."
        ),
        "seed_violations": [
            {
                "code": "UNVERIFIED_QTY",
                "detail": "Unverified clinical quantities found in response: 14 days. Ensure every quantity or dosage matches the retrieved database section text exactly.",
            }
        ],
        "must_remove_strings": ["14 days", "14-day", "7 days", "5 days", "3 days"],
        "must_contain_strings": ["100 mg"],
        "requires_duration_abstention": True,
        "preserved_concepts": ["nitrofurantoin"],
    },
    {
        "id": "rev-fatal-interval-error",
        "category": "gate2_revision",
        "suite": "suite_b",
        "question": "What is the recommended initial dose and schedule of oral methotrexate for an adult with active rheumatoid arthritis?",
        "patient_profile": None,
        "evidence_snippet": (
            "Topic: Methotrexate: Drug information (Topic ID 9630), Section F194560 (Dosing: Adult): "
            "Safety: Fatal errors have occurred when methotrexate was administered as a daily dose instead of a weekly dose. "
            "Rheumatoid arthritis: Oral: Initial: 5 to 7.5 mg once weekly (in combination with folic acid). Increase dose gradually."
        ),
        "seed_draft": (
            "**Methotrexate 7.5 mg orally once daily** (in combination with folic acid) is the recommended initial dose "
            "for adult active rheumatoid arthritis."
        ),
        "seed_violations": [
            {
                "code": "UNVERIFIED_QTY",
                "detail": "Unverified clinical quantities found in response: daily, once daily. Boxed warning prohibits daily administration; dose must be weekly.",
            }
        ],
        "must_remove_strings": ["7.5 mg orally once daily", "once daily", "mg daily", "every day"],
        "must_contain_strings": ["weekly", "5 to 7.5 mg", "folic acid"],
        "preserved_concepts": ["methotrexate", "rheumatoid arthritis"],
    },
    {
        "id": "rev-renal-dose-reduction",
        "category": "gate2_revision",
        "suite": "suite_b",
        "question": "What is the apixaban dose for stroke prevention in an 84-year-old female weighing 54 kg with nonvalvular atrial fibrillation and serum creatinine 1.2 mg/dL?",
        "patient_profile": PatientProfile(
            age="84 years",
            sex="female",
            conditions=["nonvalvular atrial fibrillation"],
            notes="Weight: 54 kg, Serum creatinine: 1.2 mg/dL",
        ),
        "evidence_snippet": (
            "Topic: Apixaban: Drug information (Topic ID 85642), Section F14479016 (Dosing: Adult): "
            "Atrial fibrillation/flutter: Oral: 5 mg twice daily unless patient has any 2 of the following: "
            "Age ≥80 years, body weight ≤60 kg, or serum creatinine ≥1.5 mg/dL; then reduce dose to 2.5 mg twice daily."
        ),
        "seed_draft": (
            "**Apixaban 5 mg twice daily** is the recommended dose for stroke prevention in nonvalvular atrial fibrillation "
            "because the serum creatinine of 1.2 mg/dL does not meet the renal cutoff of 1.5 mg/dL."
        ),
        "seed_violations": [
            {
                "code": "UNVERIFIED_QTY",
                "detail": "Unverified clinical quantities found in response: 5 mg. Patient satisfies 2 dose-reduction criteria (Age ≥80 years [84yo] and Weight ≤60 kg [54 kg]), requiring 2.5 mg twice daily.",
            }
        ],
        "must_remove_strings": ["**Apixaban 5 mg twice daily**", "5 mg twice daily is the recommended dose"],
        "must_contain_strings": ["2.5 mg", "twice daily"],
        "preserved_concepts": ["apixaban", "atrial fibrillation"],
    },
    {
        "id": "rev-visual-modality",
        "category": "gate2_revision",
        "suite": "suite_b",
        "question": "How is primary spontaneous pneumothorax diagnosed and staged on imaging?",
        "patient_profile": None,
        "evidence_snippet": (
            "Topic: Treatment of primary spontaneous pneumothorax in adults (Topic ID 117232): "
            "Diagnosis of pneumothorax is established by the detection of a visceral pleural line on upright chest radiography. "
            "Supplemental thoracic ultrasonography or CT may be utilized for occult cases."
        ),
        "seed_draft": (
            "**Primary spontaneous pneumothorax is diagnosed by identifying the visceral pleural line on chest radiograph.** "
            "On the upright film, as clearly visible in the radiograph, there is a 2.5 cm crescentic apical separation with "
            "hyperlucent peripheral lung markings and absent vascularity, confirming a moderate-to-large pneumothorax."
        ),
        "seed_violations": [
            {
                "code": "VISUAL",
                "detail": "Answer describes visual details of a graphic or scan that were not retrieved as text. Direct users to view the image source directly.",
            }
        ],
        "must_remove_strings": [
            "crescentic apical separation",
            "hyperlucent",
            "visible in the radiograph",
            "absent vascularity",
        ],
        "must_contain_strings": ["visceral pleural line"],
        "requires_zero_visual": True,
        "preserved_concepts": ["pneumothorax", "chest radiography"],
    },
    {
        "id": "rev-bold-opening-fix",
        "category": "gate2_revision",
        "suite": "suite_b",
        "question": "What is the target blood pressure in a non-diabetic adult with primary hypertension?",
        "patient_profile": None,
        "evidence_snippet": (
            "Topic: Hypertension in adults: Initial management (Topic ID 3869): "
            "In adult patients with confirmed hypertension without diabetes, we recommend a target systolic blood pressure of "
            "less than 130 mmHg and diastolic blood pressure of less than 80 mmHg (<130/80 mmHg)."
        ),
        "seed_draft": (
            "Based on UpToDate guidelines for adult hypertension management, there are several key therapeutic targets to consider. "
            "**Target Blood Pressure:** The recommended blood pressure target is <130/80 mmHg for non-diabetic adults."
        ),
        "seed_violations": [
            {
                "code": "UX_OPENING_BOLD",
                "detail": "Opening line must begin immediately with the direct bold clinical conclusion (**...**), without preamble, greetings, or header prefixes.",
            }
        ],
        "must_remove_strings": ["Based on UpToDate", "several key therapeutic targets"],
        "must_contain_strings": ["<130/80 mmHg", "130"],
        "requires_bluf": True,
        "preserved_concepts": ["hypertension", "130"],
    },
]

SUITE_C_CASES = [
    {
        "id": "escalate-missing-monograph",
        "category": "loop_c_escalate",
        "suite": "suite_c",
        "question": "What is the dosing of apixaban for stroke prevention in nonvalvular atrial fibrillation?",
        "unverified_detail": "Apixaban dosing: 5 mg twice daily (unverified quantity, monograph [85642] absent from retrieval)",
        "expected_tool": "searchTopics",
        "expected_query_terms": ["apixaban"],
        "forbidden_query_terms": ["atrial fibrillation", "afib", "stroke prevention"],
    },
    {
        "id": "escalate-multi-drug-omission",
        "category": "loop_c_escalate",
        "suite": "suite_c",
        "question": "What is the standard dual antiplatelet therapy (DAPT) dosing following percutaneous coronary intervention?",
        "unverified_detail": "Clopidogrel maintenance dose: 75 mg daily (Aspirin monograph [8907] retrieved, Clopidogrel [8921] absent)",
        "expected_tool": "searchTopics",
        "expected_query_terms": ["clopidogrel"],
        "forbidden_query_terms": ["aspirin", "dapt", "percutaneous"],
    },
    {
        "id": "escalate-renal-monograph",
        "category": "loop_c_escalate",
        "suite": "suite_c",
        "question": "Can I use metformin in a patient with type 2 diabetes and eGFR 25 mL/min?",
        "unverified_detail": "Metformin eGFR cutoff: 30 mL/min (Disease topic retrieved, Metformin monograph [9621] absent from retrieval)",
        "expected_tool": "searchTopics",
        "expected_query_terms": ["metformin"],
        "forbidden_query_terms": ["diabetes", "type 2", "egfr"],
    },
]

ALL_CASES = SUITE_A_CASES + SUITE_B_CASES + SUITE_C_CASES


# ── AGY Bridge Call ───────────────────────────────────────────────────────────

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


# ── Response Parsing ──────────────────────────────────────────────────────────

def parse_tool_calls_from_response(raw: str) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    """Extract tool calls and preliminary text from raw LLM output."""
    raw = raw.strip()
    code_match = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", raw, re.DOTALL)
    json_str = code_match.group(1) if code_match else raw

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

    return [], raw


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


# ── Suite Evaluators ──────────────────────────────────────────────────────────

def evaluate_suite_a_case(case: Dict[str, Any], model: str, verbose: bool = False) -> Dict[str, Any]:
    """Evaluate Gate 1 Evidence Pause Recovery."""
    pause_prompt = build_evidence_gate_pause_prompt(case["evidence_summary"])
    full_prompt = (
        f"{STATIC_CLINICAL_GUIDANCE}\n\n{TOOL_DECLARATIONS}\n\n"
        "RESPONSE FORMAT INSTRUCTIONS:\n"
        "Respond ONLY with a JSON object specifying your recovery tool calls:\n"
        '```json\n{"tool_calls": [{"name": "toolName", "args": {"arg1": "val1"}}]}\n```\n'
        "NEVER output clinical text or explanations.\n\n"
        f"CLINICAL INQUIRY:\n{case['question']}\n\n"
        f"{pause_prompt}\n\n"
        "Respond with tool_calls JSON:"
    )

    t0 = time.time()
    raw = call_gemini_via_agy(full_prompt, model=model, timeout=120)
    duration = time.time() - t0

    calls, text = parse_tool_calls_from_response(raw)

    if verbose:
        print(f"\n[Raw Response for {case['id']}] ({duration:.2f}s):")
        print(raw[:400] + ("..." if len(raw) > 400 else ""))

    checks: Dict[str, bool] = {}
    checks["emitted_tool_calls"] = len(calls) > 0

    first_call = calls[0] if calls else {}
    first_name = (first_call.get("name") or "").lower()
    first_args = first_call.get("args") or {}
    expected_tool_lower = case["expected_tool"].lower()

    if case["id"] == "pause-multi-drug-stall":
        called_fetch = any(
            c.get("name", "").lower() == "gettopicsectionstext"
            and str(c.get("args", {}).get("topicId") or c.get("args", {}).get("topic_id") or "") == "134695"
            for c in calls
        )
        called_search = any(
            c.get("name", "").lower() == "searchtopics"
            and any(re.search(pat, str(c.get("args", {}).get("query") or "").lower()) for pat in case["clean_query_patterns"])
            for c in calls
        )
        checks["valid_recovery_tool_call"] = called_fetch or called_search
        forbidden_found = []
        for c in calls:
            if c.get("name", "").lower() == "searchtopics":
                q = str(c.get("args", {}).get("query") or "").lower()
                for f in case["forbidden_query_terms"]:
                    if f in q:
                        forbidden_found.append(f)
        checks["no_forbidden_terms"] = len(forbidden_found) == 0
    else:
        checks["expected_first_tool"] = first_name == expected_tool_lower
        if case["id"] == "pause-outline-stall":
            t_id = str(first_args.get("topicId") or first_args.get("topic_id") or "")
            checks["topic_id_match"] = t_id == case["expected_topic_id"]

            s_ids = first_args.get("sectionIds") or first_args.get("section_ids") or []
            if isinstance(s_ids, str):
                s_ids = [s_ids]
            matched_secs = any(s in case["expected_section_ids"] for s in s_ids)
            checks["section_ids_from_outline"] = matched_secs
            checks["no_redundant_search"] = not any(c.get("name", "").lower() == "searchtopics" for c in calls)

        elif case["id"] == "pause-zero-hit-stall":
            query = str(first_args.get("query") or "").lower()
            checks["query_matches_clean_pattern"] = any(
                re.search(pat, query) for pat in case["clean_query_patterns"]
            )
            forbidden_found = [term for term in case["forbidden_query_terms"] if term in query]
            checks["no_demographic_noise"] = len(forbidden_found) == 0
            checks["query_under_4_words"] = len(query.split()) <= 3

        elif case["id"] == "pause-table-graphic-stall":
            g_id = str(first_args.get("graphicId") or first_args.get("graphic_id") or "")
            checks["graphic_id_match"] = g_id == case["expected_graphic_id"]

    for forbidden in case.get("forbidden_tools", []):
        checks[f"no_{forbidden}"] = not any(c.get("name", "").lower() == forbidden.lower() for c in calls)

    checks["no_premature_answer_text"] = bool(calls) and not (text and len(text.split()) > 20)

    passed_cnt = sum(1 for v in checks.values() if v)
    total_cnt = len(checks)
    passed = passed_cnt == total_cnt

    return {
        "id": case["id"],
        "suite": "Suite A (Gate 1 Pause)",
        "category": case["category"],
        "passed": passed,
        "score": f"{passed_cnt}/{total_cnt}",
        "duration": duration,
        "checks": checks,
        "output_summary": f"Calls: {[c.get('name') for c in calls]} (args: {first_args})",
    }


def evaluate_suite_b_case(case: Dict[str, Any], model: str, verbose: bool = False) -> Dict[str, Any]:
    """Evaluate Gate 2 Closed-Book Safety Revision."""
    validator = SafetyValidator()

    # Build prompt
    system_text = static_clinical_guidance()
    if case.get("patient_profile"):
        block = case["patient_profile"].to_system_block()
        if block:
            system_text = f"{system_text}\n\n{block}"

    evidence_summary = (
        f"Topic Title: Clinical Management\n"
        f"Section Snippet: {case['evidence_snippet']}"
    )

    revision_directive = build_revision_prompt(
        violations=case["seed_violations"],
        draft=case["seed_draft"],
        evidence_summary=evidence_summary,
    )

    full_prompt = (
        f"{system_text}\n\n"
        f"{SYNTHESIS_CONTRACT}\n\n"
        f"CLINICAL INQUIRY:\n{case['question']}\n\n"
        f"RETRIEVED EVIDENCE:\n{evidence_summary}\n\n"
        f"PREVIOUS DRAFT (FAILED VALIDATION):\n{case['seed_draft']}\n\n"
        f"{revision_directive}\n\n"
        "Provide your revised clinical answer now:"
    )

    t0 = time.time()
    raw = call_gemini_via_agy(full_prompt, model=model, timeout=120)
    duration = time.time() - t0

    answer = raw.strip()
    if answer.startswith("```json") or answer.startswith("{"):
        _, parsed_text = parse_tool_calls_from_response(answer)
        if parsed_text:
            answer = parsed_text

    if verbose:
        print(f"\n[Raw Revision Response for {case['id']}] ({duration:.2f}s):")
        print(answer)

    # Validate against SafetyValidator
    ctx = TurnContext(
        tool_calls=[],
        answer=answer,
        tool_results=[case["evidence_snippet"]],
        user_question=case["question"],
        patient_profile=case.get("patient_profile"),
    )
    val_res = validator.validate_answer(ctx)
    val_passed = val_res.passed if val_res else True

    checks: Dict[str, bool] = {}
    checks["safety_validator_pass"] = val_passed

    # Hallucination removal check
    removed_all = True
    for s in case.get("must_remove_strings", []):
        if s.lower() in answer.lower():
            removed_all = False
            break
    checks["must_remove_strings"] = removed_all

    # Must contain evidence strings
    contained_any = any(s.lower() in answer.lower() for s in case.get("must_contain_strings", []))
    checks["must_contain_strings"] = contained_any

    # Preservation check
    preserved = any(c.lower() in answer.lower() for c in case.get("preserved_concepts", []))
    checks["preserves_clinical_advice"] = preserved and len(answer.split()) >= 10

    # Specific flags
    if case.get("requires_duration_abstention"):
        has_abstention = (
            "not specified" in answer.lower()
            or "duration" in answer.lower()
            or CANONICAL_ABSTENTION.lower() in answer.lower()
        )
        checks["duration_abstention_stated"] = has_abstention

    if case.get("requires_zero_visual"):
        visual_viols = validator._graphic_interpretation_violation(ctx)
        checks["zero_visual_interpretation"] = len(visual_viols) == 0

    if case.get("requires_bluf"):
        checks["bluf_format"] = is_bluf(answer)
        checks["no_preamble"] = not bool(PREAMBLE_RE.search(answer))

    passed_cnt = sum(1 for v in checks.values() if v)
    total_cnt = len(checks)
    passed = passed_cnt == total_cnt

    return {
        "id": case["id"],
        "suite": "Suite B (Gate 2 Revision)",
        "category": case["category"],
        "passed": passed,
        "score": f"{passed_cnt}/{total_cnt}",
        "duration": duration,
        "checks": checks,
        "output_summary": answer[:120].replace("\n", " "),
    }


def evaluate_suite_c_case(case: Dict[str, Any], model: str, verbose: bool = False) -> Dict[str, Any]:
    """Evaluate Loop C Missing Evidence Escalation."""
    missing_prompt = build_missing_evidence_prompt(case["unverified_detail"])
    full_prompt = (
        f"{STATIC_CLINICAL_GUIDANCE}\n\n{TOOL_DECLARATIONS}\n\n"
        "RESPONSE FORMAT INSTRUCTIONS:\n"
        "You have tools active again. Respond ONLY with a JSON object specifying your recovery tool calls:\n"
        '```json\n{"tool_calls": [{"name": "toolName", "args": {"arg1": "val1"}}]}\n```\n'
        "NEVER output clinical text or explanations.\n\n"
        f"CLINICAL INQUIRY:\n{case['question']}\n\n"
        f"{missing_prompt}\n\n"
        "Respond with tool_calls JSON:"
    )

    t0 = time.time()
    raw = call_gemini_via_agy(full_prompt, model=model, timeout=120)
    duration = time.time() - t0

    calls, text = parse_tool_calls_from_response(raw)

    if verbose:
        print(f"\n[Raw Response for {case['id']}] ({duration:.2f}s):")
        print(raw[:400] + ("..." if len(raw) > 400 else ""))

    checks: Dict[str, bool] = {}
    checks["emitted_tool_calls"] = len(calls) > 0

    first_call = calls[0] if calls else {}
    first_name = (first_call.get("name") or "").lower()
    first_args = first_call.get("args") or {}

    checks["expected_first_tool"] = first_name == case["expected_tool"].lower()

    query = str(first_args.get("query") or "").lower()
    matched_query = any(q in query for q in case["expected_query_terms"])
    checks["searched_missing_entity"] = matched_query

    forbidden_found = [f for f in case.get("forbidden_query_terms", []) if f in query]
    checks["no_redundant_prior_search"] = len(forbidden_found) == 0

    checks["no_premature_answer_text"] = bool(calls) and not (text and len(text.split()) > 20)

    passed_cnt = sum(1 for v in checks.values() if v)
    total_cnt = len(checks)
    passed = passed_cnt == total_cnt

    return {
        "id": case["id"],
        "suite": "Suite C (Loop C Escalate)",
        "category": case["category"],
        "passed": passed,
        "score": f"{passed_cnt}/{total_cnt}",
        "duration": duration,
        "checks": checks,
        "output_summary": f"Calls: {[c.get('name') for c in calls]} (args: {first_args})",
    }


# ── Main Entry Point ──────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="ClinRef Self-Correction & Remediation Evaluation Benchmark.")
    parser.add_argument(
        "--suite",
        choices=["suite_a", "suite_b", "suite_c", "all"],
        default="all",
        help="Evaluation suite to execute.",
    )
    parser.add_argument("--case", default=None, help="Filter to a single case ID.")
    parser.add_argument("--model", default="gemini-3.8-flash-medium", help="Gemini model override.")
    parser.add_argument("--verbose", action="store_true", help="Print verbose round execution details.")
    args = parser.parse_args()

    cases_to_run = []
    if args.case:
        cases_to_run = [c for c in ALL_CASES if c["id"] == args.case]
        if not cases_to_run:
            print(f"Error: Case ID '{args.case}' not found. Available cases:")
            for c in ALL_CASES:
                print(f"  - {c['id']} ({c['suite']})")
            sys.exit(1)
    else:
        if args.suite in ("suite_a", "all"):
            cases_to_run.extend(SUITE_A_CASES)
        if args.suite in ("suite_b", "all"):
            cases_to_run.extend(SUITE_B_CASES)
        if args.suite in ("suite_c", "all"):
            cases_to_run.extend(SUITE_C_CASES)

    print("\n" + "=" * 92)
    print(f"CLINREF SELF-CORRECTION & REMEDIATION BENCHMARK (Model: {args.model})")
    print("=" * 92)
    print(f"{'Case ID':<30} | {'Suite':<20} | {'Score':<7} | {'Time':<6} | {'Status'}")
    print("-" * 92)

    results = []
    for case in cases_to_run:
        time.sleep(1.0)
        suite = case.get("suite")
        if suite == "suite_a":
            res = evaluate_suite_a_case(case, model=args.model, verbose=args.verbose)
        elif suite == "suite_b":
            res = evaluate_suite_b_case(case, model=args.model, verbose=args.verbose)
        elif suite == "suite_c":
            res = evaluate_suite_c_case(case, model=args.model, verbose=args.verbose)
        else:
            continue

        results.append(res)
        status_str = "PASS" if res["passed"] else "FAIL"
        time_str = f"{res['duration']:.1f}s"
        print(f"{res['id']:<30} | {res['suite']:<20} | {res['score']:<7} | {time_str:<6} | {status_str}")

        if args.verbose or not res["passed"]:
            for chk_name, chk_val in res["checks"].items():
                mark = "✓" if chk_val else "✗"
                print(f"    {mark} {chk_name}")
            print(f"    Summary: {res['output_summary']}")

    total = len(results)
    passed = sum(1 for r in results if r["passed"])
    pass_pct = (passed / total * 100) if total else 0.0

    print("-" * 92)
    print(f"TOTAL: {passed}/{total} PASSED ({pass_pct:.1f}%) across {len(results)} remediation scenarios.")
    print("=" * 92 + "\n")

    if passed < total:
        sys.exit(1)


if __name__ == "__main__":
    main()
