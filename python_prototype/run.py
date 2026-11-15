"""Main entry point for ClinRef Python prototype.

Usage:
    python run.py                          # Interactive mode with GPT-4o
    python run.py --provider anthropic     # Use Claude
    python run.py --provider ollama        # Use local Ollama
    python run.py --provider agy           # Use local Antigravity CLI (no LLM API key needed)
    python run.py --query "AFib treatment" # Single query mode
    python run.py --test                   # Run test suite
"""

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Optional

from dotenv import load_dotenv

load_dotenv()

from database import ClinRefDatabase
from html_parser import extract_outline_sections
from safety_validator import PatientProfile, SafetyValidator, TurnContext
from system_prompt import build_system_prompt, extract_patient_context
from tools import init_tools

# ---------------------------------------------------------------------------
# Standardized Clinical Constants (Milestone 6 Parity)
# ---------------------------------------------------------------------------
DEFAULT_TEMPERATURE = 0.15
DEFAULT_MAX_OUTPUT_TOKENS_CLOUD = 16384
DEFAULT_MAX_OUTPUT_TOKENS_OLLAMA = 4096


def collect_patient_profile() -> Optional[dict]:
    """Interactively collect an optional patient profile (parity with the Kotlin UI's PatientProfile editor).

    Answers feed both the system prompt context and the evidence corpus, so values the
    clinician supplies are never flagged as invented quantities.
    """
    print("Patient profile (press Enter to skip a field — an empty profile disables patient context):")
    try:
        age = input("  Age: ").strip()
        sex = input("  Sex: ").strip()
        conditions = input("  Conditions (comma-separated): ").strip()
        medications = input("  Medications (comma-separated): ").strip()
        allergies = input("  Allergies (comma-separated): ").strip()
        notes = input("  Notes: ").strip()
    except (EOFError, KeyboardInterrupt):
        print()
        return None

    profile: dict = {}
    if age:
        profile["age"] = age
    if sex:
        profile["sex"] = sex
    for key, raw in (("conditions", conditions), ("medications", medications), ("allergies", allergies)):
        items = [part.strip() for part in raw.split(",") if part.strip()]
        if items:
            profile[key] = items
    if notes:
        profile["notes"] = notes
    return profile or None


def _build_references_section(validation_result: dict) -> str:
    """Build a formatted references section from validation result."""
    if not validation_result:
        return ""

    refs_lines = []

    if validation_result.get("topic_refs"):
        from collections import defaultdict
        by_topic = defaultdict(list)
        seen = set()
        for r in validation_result["topic_refs"]:
            key = (r.get("topic_id", ""), r.get("section_id", ""))
            if key not in seen:
                seen.add(key)
                display_title = r.get("topic_title") or r.get("topic_id", "Unknown")
                by_topic[display_title].append(
                    (r.get("label", ""), r.get("section_id", ""))
                )
        if by_topic:
            refs_lines.append("References:")
            for topic, sections in by_topic.items():
                refs_lines.append(f"  {topic}")
                for label, section_id in sections:
                    refs_lines.append(f"    - {label} (ID: {section_id})")

    if validation_result.get("graphic_refs"):
        if not refs_lines:
            refs_lines.append("References:")
        for g in validation_result["graphic_refs"]:
            refs_lines.append(f"  - Graphic {g.get('graphic_id', '')}: {g.get('label', '')}")

    return "\n\n" + "\n".join(refs_lines) if refs_lines else ""


# Provider -> environment variable mapping
_API_KEY_ENV = {
    "openai": "OPENAI_API_KEY",
    "anthropic": "ANTHROPIC_API_KEY",
    "mistral": "MISTRAL_API_KEY",
    "google": "GOOGLE_API_KEY",
    "openrouter": "OPENROUTER_API_KEY",
}


def resolve_api_key(provider: str, api_key: str = None) -> str | None:
    """Resolve API key: explicit arg > env var (None for key-free local providers)."""
    if api_key:
        return api_key
    if provider in ("agy", "ollama"):
        return None
    env_var = _API_KEY_ENV.get(provider)
    if env_var:
        return os.environ.get(env_var)
    # litellm providers use OPENAI_API_KEY by convention
    return os.environ.get("OPENAI_API_KEY")


def create_llm(
    provider: str,
    model: Optional[str] = None,
    api_key: Optional[str] = None,
    temperature: float = DEFAULT_TEMPERATURE,
    max_tokens: Optional[int] = None,
):
    """Create a LangChain chat model with standardized clinical defaults.

    Applies temperature=0.15 and max_tokens=16384 (cloud) / 4096 (Ollama).
    Onerous/unsupported top_p and top_k parameters are omitted.
    """
    if max_tokens is None:
        max_tokens = DEFAULT_MAX_OUTPUT_TOKENS_OLLAMA if provider == "ollama" else DEFAULT_MAX_OUTPUT_TOKENS_CLOUD

    if provider == "openai":
        from langchain_openai import ChatOpenAI
        return ChatOpenAI(
            model=model or "gpt-4o",
            api_key=api_key,
            temperature=temperature,
            max_tokens=max_tokens,
        )
    elif provider == "anthropic":
        from langchain_anthropic import ChatAnthropic
        return ChatAnthropic(
            model=model or "claude-sonnet-4-20250514",
            api_key=api_key,
            temperature=temperature,
            max_tokens=max_tokens,
        )
    elif provider == "mistral":
        from langchain_mistralai import ChatMistralAI
        return ChatMistralAI(
            model=model or "mistral-large-latest",
            api_key=api_key,
            temperature=temperature,
            max_tokens=max_tokens,
        )
    elif provider == "openrouter":
        from langchain_openai import ChatOpenAI
        return ChatOpenAI(
            model=model or "nvidia/nemotron-3.5-super-vl:free",
            api_key=api_key or os.environ.get("OPENROUTER_API_KEY"),
            base_url=os.environ.get("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1"),
            temperature=temperature,
            max_tokens=max_tokens,
        )
    elif provider == "agy":
        from agy_chat_model import AgyChatModel
        return AgyChatModel(
            model_name=model,
            temperature=temperature,
        )
    elif provider == "ollama":
        try:
            from langchain_community.chat_models import ChatOllama
            return ChatOllama(
                model=model or "llama3.2",
                base_url="http://localhost:11434",
                temperature=temperature,
                num_predict=max_tokens,
            )
        except ImportError:
            from langchain_openai import ChatOpenAI
            return ChatOpenAI(
                model=model or "llama3.2",
                api_key="ollama",
                base_url=os.environ.get("OLLAMA_BASE_URL", "http://localhost:11434/v1"),
                temperature=temperature,
                max_tokens=max_tokens,
            )
    else:
        # Use litellm if langchain_community is present, otherwise fallback to OpenAI-compatible ChatOpenAI
        try:
            from langchain_community.chat_models import ChatLiteLLM
            model_name = model or f"{provider}/gpt-4o"
            return ChatLiteLLM(
                model=model_name,
                api_key=api_key,
                temperature=temperature,
                max_tokens=max_tokens,
            )
        except ImportError:
            from langchain_openai import ChatOpenAI
            return ChatOpenAI(
                model=model or "gpt-4o",
                api_key=api_key,
                temperature=temperature,
                max_tokens=max_tokens,
            )


def _resolve_decision_runtime(
    decision_mode: str,
    decision_prefetch: bool,
    token_tracker,
    decision_client=None,
):
    """Build optional (DecisionClient, DecisionConfig) for `gemini-decision-1` retrieval reranking."""
    mode = (decision_mode or "off").strip().lower()
    if mode == "off" and decision_client is None:
        return None, None

    from decision_client import DecisionClient
    from decision_gates import DecisionConfig

    dec_cfg = DecisionConfig.from_mode(mode, auto_prefetch=decision_prefetch)
    if dec_cfg is None:
        dec_cfg = DecisionConfig.rerank(auto_prefetch=decision_prefetch)

    client = decision_client or DecisionClient.from_env(
        fail_open=True,
        on_usage=token_tracker.on_decision_end,
    )
    if not client.is_configured():
        print(
            "Warning: --decision-mode enabled, but GOOGLE_API_KEY/GEMINI_API_KEY is missing; "
            "Decision API calls will fail-open cleanly.",
            file=sys.stderr,
        )
    return client, dec_cfg


def _format_tool_log_lines(content: str, elapsed_s: float) -> list[str]:
    """Render concise, informative log lines for a ToolMessage payload."""
    try:
        data = json.loads(content)
    except Exception:
        data = None

    if not isinstance(data, dict):
        preview = content[:140].replace("\n", " ")
        if len(content) > 140:
            preview += "..."
        return [f"  [Tool | {elapsed_s:.2f}s] {preview}"]

    if data.get("error"):
        return [f"  [Tool ERROR | {elapsed_s:.2f}s] {data['error']}"]

    lines: list[str] = []
    rerank = data.get("decisionRerank") if isinstance(data.get("decisionRerank"), dict) else None

    if "results" in data and isinstance(data.get("results"), list):
        results = data["results"]
        top_str = ", ".join(f"[{r.get('id')}] {r.get('title')}" for r in results[:3])
        query = data.get("query", "")
        lines.append(
            f"  [Tool | {elapsed_s:.2f}s] searchTopics({query!r}) -> {len(results)} hits"
            + (f" (top: {top_str})" if top_str else "")
        )
        if rerank:
            hint = str(rerank.get("hint") or "")
            if len(hint) > 160:
                hint = hint[:157] + "..."
            lines.append(
                f"    [Decision Rerank] needsDrugMonograph={rerank.get('requiresDrugMonograph')} "
                f"needsTableData={rerank.get('requiresTableData')}"
                + (f" | {hint}" if hint else "")
            )
        return lines

    if "sections" in data and isinstance(data.get("sections"), list):
        tid = data.get("topicId", "?")
        title = data.get("title", "")
        t_type = data.get("topicType", "?")
        sec_list = data["sections"]
        sec_titles = {str(s.get("id")): str(s.get("title", "")).lstrip("-–— ") for s in sec_list}
        graphics = data.get("graphics") or []
        tables_cnt = sum(1 for g in graphics if g.get("isTable", True))
        lines.append(
            f"  [Tool | {elapsed_s:.2f}s] getTopicOverview({tid} {title!r}, type={t_type}) -> "
            f"{len(sec_list)} sections, {tables_cnt} tables"
        )
        if rerank:
            rec_secs = rerank.get("recommendedSectionIds") or []
            rec_gfxs = rerank.get("recommendedGraphicIds") or []
            picked = ", ".join(
                f"{sid} ({sec_titles[sid]})" if sid in sec_titles else str(sid)
                for sid in rec_secs[:6]
            )
            if len(rec_secs) > 6:
                picked += f", +{len(rec_secs) - 6} more"
            lines.append(
                f"    [Decision Rerank] picked {len(rec_secs)} sections"
                + (f": {picked}" if picked else "")
                + (f" | {len(rec_gfxs)} tables: {', '.join(rec_gfxs)}" if rec_gfxs else "")
            )
            if rerank.get("autoPrefetched"):
                sec_md_len = len(str(rerank.get("prefetchedSectionsMarkdown") or ""))
                tbl_md_len = len(str(rerank.get("prefetchedTablesMarkdown") or ""))
                lines.append(
                    f"    [Auto-Prefetch] loaded {len(rec_secs)} sections ({sec_md_len:,} chars)"
                    + (f" + {len(rec_gfxs)} tables ({tbl_md_len:,} chars)" if tbl_md_len else "")
                )
        return lines

    if "sectionTitles" in data and isinstance(data.get("sectionTitles"), dict):
        tid = data.get("topicId", "?")
        sec_titles = data["sectionTitles"]
        md_len = len(str(data.get("markdown") or ""))
        listed = ", ".join(f"{sid} ({lbl})" for sid, lbl in list(sec_titles.items())[:5])
        if len(sec_titles) > 5:
            listed += f", +{len(sec_titles) - 5} more"
        lines.append(
            f"  [Tool | {elapsed_s:.2f}s] getTopicSectionsText(topic={tid}) -> "
            f"{len(sec_titles)} sections ({md_len:,} chars)"
            + (f": {listed}" if listed else "")
        )
        if data.get("invalidSections"):
            lines.append(f"    [Notice] invalidSections={data['invalidSections']}")
        return lines

    if "graphicId" in data or ("markdown" in data and "title" in data):
        gid = data.get("graphicId") or data.get("id") or "?"
        g_title = data.get("title", "")
        md_len = len(str(data.get("markdown") or ""))
        lines.append(f"  [Tool | {elapsed_s:.2f}s] getGraphicContent({gid} {g_title!r}) -> {md_len:,} chars")
        return lines

    preview = content[:140].replace("\n", " ")
    if len(content) > 140:
        preview += "..."
    return [f"  [Tool | {elapsed_s:.2f}s] {preview}"]


def _format_gate1_lines(node_output: dict, elapsed_s: float) -> list[str]:
    """Render deterministic Gate 1 status."""
    val = node_output.get("validation_result") or {}
    tc = node_output.get("turn_context") or {}

    sec_cnt = len(tc.get("fetched_sections") or [])
    gfx_cnt = len(tc.get("graphic_ids") or [])
    if val.get("passed"):
        return [f"  [Gate 1 | {elapsed_s:.2f}s] PASSED ({sec_cnt} sections, {gfx_cnt} tables)"]
    reason = val.get("blocked_reason") or "Evidence required"
    return [f"  [Gate 1 | {elapsed_s:.2f}s] BLOCKED: {reason}"]


def _format_gate2_lines(val: dict, elapsed_s: float) -> list[str]:
    """Render deterministic Gate 2 validation status."""
    status = "PASSED" if val.get("passed") else "BLOCKED"
    t_refs = len(val.get("topic_refs") or [])
    g_refs = len(val.get("graphic_refs") or [])
    lines = [
        f"  [Gate 2 | {elapsed_s:.2f}s] Validation: {status} (refs: {t_refs} sections, {g_refs} tables)"
    ]
    if val.get("blocked_reason"):
        lines.append(f"    Blocked: {val['blocked_reason']}")
    if val.get("warnings"):
        for w in val["warnings"]:
            lines.append(f"    Warning: {w}")
    return lines


def _stream_agent_turn(agent, initial_state: dict, token_tracker) -> tuple[str, Optional[dict], float]:
    """Run one agent turn with structured status logs and return (answer, validation_result, elapsed_s)."""
    from langchain_core.messages import AIMessage

    answer = ""
    validation_result = None
    config = {"callbacks": [token_tracker]}
    turn_t0 = time.perf_counter()
    step_t0 = turn_t0
    phase1_round = 0
    phase2_round = 0
    is_small_talk = False

    for event in agent.stream(initial_state, config=config, stream_mode="updates"):
        now = time.perf_counter()
        dt = now - step_t0
        step_t0 = now

        for node_name, node_output in event.items():
            if not node_output or not isinstance(node_output, dict):
                continue
            messages = node_output.get("messages", [])

            if node_name == "small_talk_reply":
                is_small_talk = True
                for msg in messages:
                    if hasattr(msg, "content") and msg.content:
                        answer = msg.content

            elif node_name == "node_evidence_gathering":
                phase1_round += 1
                for msg in messages:
                    if hasattr(msg, "tool_calls") and msg.tool_calls:
                        for tc in msg.tool_calls:
                            print(
                                f"  [Phase 1 #{phase1_round} | {dt:.2f}s] LLM -> tool_call: "
                                f"{tc['name']}({json.dumps(tc['args'], ensure_ascii=False)})"
                            )
                    elif hasattr(msg, "content") and msg.content:
                        answer = msg.content
                        print(f"  [Phase 1 #{phase1_round} | {dt:.2f}s] LLM finished evidence gathering -> Gate 1")

            elif node_name == "intercept_premature":
                print(
                    f"  [Intercept | {dt:.2f}s] Premature clinical answer without evidence "
                    f"(retry {node_output.get('premature_retries', '?')})"
                )

            elif node_name == "execute_tools":
                for msg in messages:
                    if hasattr(msg, "content"):
                        raw = msg.content if isinstance(msg.content, str) else json.dumps(msg.content)
                        for line in _format_tool_log_lines(raw, dt):
                            print(line)

            elif node_name == "validate_evidence":
                if node_output.get("validation_result"):
                    for line in _format_gate1_lines(node_output, dt):
                        print(line)

            elif node_name == "node_clinical_synthesis":
                phase2_round += 1
                for msg in messages:
                    if hasattr(msg, "content") and msg.content:
                        answer = msg.content
                        words = len(str(msg.content).split())
                        print(f"  [Phase 2 #{phase2_round} | {dt:.2f}s] LLM synthesized draft ({len(str(msg.content)):,} chars, {words} words)")

            elif node_name in ("validate_safety", "safety_block"):
                for msg in messages:
                    if isinstance(msg, AIMessage) and msg.content and not msg.tool_calls:
                        answer = msg.content
                val = node_output.get("validation_result")
                if val:
                    validation_result = val
                    for line in _format_gate2_lines(val, dt):
                        print(line)

    elapsed = time.perf_counter() - turn_t0
    if not is_small_talk:
        print(f"  [Turn Elapsed] {elapsed:.2f}s")

    if validation_result and not validation_result.get("passed") and validation_result.get("blocked_reason"):
        blocked_reason = validation_result["blocked_reason"]
        if not answer.startswith("Clinical Response Verification Blocked:"):
            answer = f"Clinical Response Verification Blocked: {blocked_reason}"
    else:
        answer += _build_references_section(validation_result)

    return answer, validation_result, elapsed


def _create_runtime_session(
    provider: str,
    model: Optional[str],
    db_path: str,
    system_prompt: str,
    decision_mode: str,
    decision_prefetch: bool,
    decision_client=None,
):
    """Initialize DB tools, LLM, Decision API client, and compiled agent graph."""
    db = ClinRefDatabase(db_dir=Path(db_path))
    tools = init_tools(db)
    api_key = resolve_api_key(provider)
    llm = create_llm(provider, model, api_key, temperature=DEFAULT_TEMPERATURE)
    llm_with_tools = llm.bind_tools(tools)

    from agent import create_clinical_agent
    from token_tracker import TokenTracker

    token_tracker = TokenTracker()
    dec_client, dec_cfg = _resolve_decision_runtime(
        decision_mode, decision_prefetch, token_tracker, decision_client=decision_client
    )
    agent = create_clinical_agent(
        llm=llm,
        tools=tools,
        system_prompt=system_prompt,
        callbacks=[token_tracker],
        llm_with_tools=llm_with_tools,
        llm_tool_free=llm,
        decision_client=dec_client,
        decision_config=dec_cfg,
    )
    return db, agent, token_tracker, dec_cfg


def run_interactive(
    provider: str,
    model: str,
    db_path: str,
    decision_mode: str = "off",
    decision_prefetch: bool = False,
    decision_client=None,
):
    """Run interactive chat loop."""
    patient_profile = collect_patient_profile()
    system_prompt = build_system_prompt(
        patient_context=extract_patient_context(patient_profile),
    )
    db, agent, token_tracker, dec_cfg = _create_runtime_session(
        provider,
        model,
        db_path,
        system_prompt,
        decision_mode,
        decision_prefetch,
        decision_client=decision_client,
    )

    print(f"ClinRef Prototype — Provider: {provider}, Model: {model or 'default'}")
    if dec_cfg is not None:
        print(f"Decision API: mode={decision_mode}, prefetch={decision_prefetch}")
    print("Type your clinical question, or 'quit' to exit.\n")

    conversation_history = []

    while True:
        try:
            user_input = input("You: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nGoodbye.")
            break

        if not user_input or user_input.lower() in ("quit", "exit", "q"):
            print("Goodbye.")
            break

        # Build initial state with conversation history
        from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
        turn_context = TurnContext(
            patient_profile=PatientProfile(**patient_profile) if patient_profile else None
        ).model_dump()
        initial_state = {
            "messages": (
                [SystemMessage(content=system_prompt)]
                + list(conversation_history)
                + [HumanMessage(content=user_input)]
            ),
            "system_prompt": system_prompt,
            "turn_context": turn_context,
            "user_question": user_input,
            "gate1_retries": 0,
            "gate2_revisions": 0,
            "gate2_escalations": 0,
            "validation_result": None,
            "tool_rounds": 0,
            "premature_retries": 0,
            "decision_telemetry": {},
        }

        print()
        answer, _, elapsed = _stream_agent_turn(agent, initial_state, token_tracker)

        # Show final answer
        print(f"\nClinRef: {answer}\n")

        # Show token usage
        token_tracker.print_summary(elapsed_seconds=elapsed)

        # Update conversation history
        conversation_history.append(HumanMessage(content=user_input))
        conversation_history.append(AIMessage(content=answer))

    db.close()


def run_single_query(
    query: str,
    provider: str,
    model: str,
    db_path: str,
    patient_profile: Optional[dict] = None,
    decision_mode: str = "off",
    decision_prefetch: bool = False,
    decision_client=None,
):
    """Run a single query and print the result with streaming flow."""
    if patient_profile is None and sys.stdin.isatty():
        patient_profile = collect_patient_profile()
    system_prompt = build_system_prompt(
        patient_context=extract_patient_context(patient_profile),
    )
    db, agent, token_tracker, dec_cfg = _create_runtime_session(
        provider,
        model,
        db_path,
        system_prompt,
        decision_mode,
        decision_prefetch,
        decision_client=decision_client,
    )

    from agent import create_initial_state
    initial_state = create_initial_state(query, system_prompt, patient_profile=patient_profile)

    print(f"Query: {query}")
    print(
        f"Config: provider={provider} | model={model or 'default'} | "
        f"decision={decision_mode if dec_cfg is not None else 'off'} | prefetch={bool(dec_cfg and dec_cfg.auto_prefetch)}\n"
    )
    answer, _, elapsed = _stream_agent_turn(agent, initial_state, token_tracker)

    print(f"\n{answer}")

    token_tracker.print_summary(elapsed_seconds=elapsed)
    db.close()


def run_tests(db_path: str):
    """Run basic tests against the database."""
    print("Running database tests...\n")
    db = ClinRefDatabase(db_dir=Path(db_path))

    # Test 1: Search
    print("Test 1: Search for 'atrial fibrillation'")
    search_results = db.search_topics("atrial fibrillation")
    print(f"  Found {len(search_results)} results")
    for r in search_results[:3]:
        print(f"  - [{r['id']}] {r['title']}")
    assert len(search_results) > 0, "Search returned no results"
    print("  PASS\n")

    # Test 2: Suggestions
    print("Test 2: Suggestions for 'anticoagulation'")
    results = db.get_suggestions("anticoagulation")
    print(f"  Found {len(results)} suggestions")
    for r in results[:3]:
        print(f"  - {r}")
    assert len(results) > 0, "Suggestions returned no results"
    print("  PASS\n")

    # Test 3: Topic asset
    if search_results:
        topic_id = search_results[0]["id"]
        print(f"Test 3: Load topic asset for {topic_id}")
        asset = db.get_topic_asset(topic_id)
        if asset:
            title = db.get_topic_title(topic_id)
            print(f"  Title: {title}")
            outline = db.get_topic_outline(topic_id)
            if outline:
                sections = extract_outline_sections(outline)
                print(f"  Sections: {len(sections)}")
                for s in sections[:3]:
                    print(f"    - [{s['id']}] {s['title']}")
            print("  PASS\n")
        else:
            print(f"  SKIP — asset not found for {topic_id}\n")

    # Test 4: Safety validator
    print("Test 4: Safety validator")
    validator = SafetyValidator()

    # Test no-tool-calls block (clinical response)
    ctx = TurnContext(answer="The dose is 5 mg.")
    result = validator.validate(ctx)
    assert not result.passed
    assert "Clinical recommendations require database verification" in result.blocked_reason
    print("  No-tool-calls block (clinical): PASS")

    # Test no-tool-calls blocks greetings too (Rule 0 never bypasses; Change 1 moves
    # greetings to the entry router, so the validator has no answer-side bypass left)
    ctx = TurnContext(answer="Hello!", user_question="hello")
    result = validator.validate(ctx)
    assert not result.passed
    assert "No database tools were executed" in result.blocked_reason
    print("  No-tool-calls block (greeting): PASS")

    # Test no-section block
    from safety_validator import ToolCallRecord
    ctx = TurnContext(
        tool_calls=[ToolCallRecord(
            tool_name="search_topics",
            arguments={"query": "test"},
            result="[]",
            success=True,
        )]
    )
    result = validator.validate(ctx)
    assert not result.passed
    assert "section or table content" in result.blocked_reason.lower()
    print("  No-section block: PASS")

    print("\nAll tests passed!")
    db.close()


def main():
    parser = argparse.ArgumentParser(description="ClinRef Python Prototype")
    parser.add_argument("--provider", default="openai", help="LLM provider (openai, anthropic, mistral, ollama, openrouter, agy)")
    parser.add_argument("--model", default=None, help="Model name override")
    parser.add_argument("--query", default=None, help="Single query mode")
    parser.add_argument("--test", action="store_true", help="Run test suite")
    parser.add_argument("--db-path", default=str(Path(__file__).parent.parent), help="Path to database directory")
    parser.add_argument(
        "--decision-mode",
        choices=["off", "rerank"],
        default="off",
        help="Optional gemini-decision-1 mode for search result and topic outline reranking: off or rerank",
    )
    parser.add_argument(
        "--decision-prefetch",
        action="store_true",
        help="When --decision-mode rerank is active, auto-prefetch top ranked sections/tables",
    )
    args = parser.parse_args()

    if args.test:
        run_tests(args.db_path)
    elif args.query:
        run_single_query(
            args.query,
            args.provider,
            args.model,
            args.db_path,
            decision_mode=args.decision_mode,
            decision_prefetch=args.decision_prefetch,
        )
    else:
        run_interactive(
            args.provider,
            args.model,
            args.db_path,
            decision_mode=args.decision_mode,
            decision_prefetch=args.decision_prefetch,
        )


if __name__ == "__main__":
    main()
