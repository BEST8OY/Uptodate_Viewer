"""Main entry point for ClinRef Python prototype.

Usage:
    python run.py                          # Interactive mode with GPT-4o
    python run.py --provider anthropic     # Use Claude
    python run.py --provider ollama        # Use local Ollama
    python run.py --query "AFib treatment" # Single query mode
    python run.py --test                   # Run test suite
"""

import argparse
import json
import os
import sys
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

from database import ClinRefDatabase
from html_parser import extract_outline_sections
from safety_validator import CONVERSATIONAL_USER_REGEX, SafetyValidator, TurnContext


def is_conversational_query(query: str) -> bool:
    """Zero-LLM upstream intent classifier for basic greetings and help requests."""
    clean = query.strip().lower()
    return bool(CONVERSATIONAL_USER_REGEX.match(clean)) and len(clean) < 40
from system_prompt import build_system_prompt, extract_patient_context
from tools import init_tools


def _extract_answer_from_tool_result(content: str) -> str:
    """Extract answer text from a terminal tool's SUBMITTED JSON result."""
    try:
        data = json.loads(content)
        if data.get("status") == "SUBMITTED" and "answer" in data:
            return data["answer"]
    except (json.JSONDecodeError, TypeError):
        pass
    return ""


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
    """Resolve API key: explicit arg > env var."""
    if api_key:
        return api_key
    env_var = _API_KEY_ENV.get(provider)
    if env_var:
        return os.environ.get(env_var)
    # litellm providers use OPENAI_API_KEY by convention
    return os.environ.get("OPENAI_API_KEY")


def create_llm(provider: str, model: str = None, api_key: str = None):
    """Create a LangChain chat model for the specified provider."""
    if provider == "openai":
        from langchain_openai import ChatOpenAI
        return ChatOpenAI(model=model or "gpt-4o", api_key=api_key)
    elif provider == "anthropic":
        from langchain_anthropic import ChatAnthropic
        return ChatAnthropic(model=model or "claude-sonnet-4-20250514", api_key=api_key)
    elif provider == "mistral":
        from langchain_mistralai import ChatMistralAI
        return ChatMistralAI(model=model or "mistral-large-latest", api_key=api_key)
    elif provider == "ollama":
        from langchain_community.chat_models import ChatOllama
        return ChatOllama(model=model or "llama3.2", base_url="http://localhost:11434")
    else:
        # Use litellm for other providers (google, openrouter, etc.)
        from langchain_community.chat_models import ChatLiteLLM
        model_name = model or f"{provider}/gpt-4o"
        return ChatLiteLLM(model=model_name, api_key=api_key)


def run_interactive(provider: str, model: str, db_path: str):
    """Run interactive chat loop."""
    db = ClinRefDatabase(db_dir=Path(db_path))
    tools = init_tools(db)
    system_prompt = build_system_prompt(provider=provider)
    api_key = resolve_api_key(provider)
    llm = create_llm(provider, model, api_key)
    llm_with_tools = llm.bind_tools(tools)

    from agent import create_clinical_agent, create_initial_state
    from token_tracker import TokenTracker
    token_tracker = TokenTracker()
    agent = create_clinical_agent(llm_with_tools, tools, system_prompt, callbacks=[token_tracker])

    print(f"ClinRef Prototype — Provider: {provider}, Model: {model or 'default'}")
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

        # Build messages with history
        messages = conversation_history + [{"role": "user", "content": user_input}]

        from langchain_core.messages import HumanMessage, SystemMessage
        initial_state = {
            "messages": [
                SystemMessage(content=system_prompt),
                HumanMessage(content=user_input),
            ],
            "system_prompt": system_prompt,
            "turn_context": TurnContext().model_dump(),
            "user_question": user_input,
            "retry_count": 0,
            "validation_result": None,
        }

        # Run agent with streaming output
        print()
        answer = ""
        validation_result = None
        config = {"callbacks": [token_tracker]}

        for event in agent.stream(initial_state, config=config, stream_mode="updates"):
            for node_name, node_output in event.items():
                messages = node_output.get("messages", [])

                if node_name == "llm":
                    for msg in messages:
                        if hasattr(msg, "tool_calls") and msg.tool_calls:
                            for tc in msg.tool_calls:
                                print(f"  LLM -> tool_call: {tc['name']}({json.dumps(tc['args'], ensure_ascii=False)})")
                        elif hasattr(msg, "content") and msg.content:
                            # Final text response — print below tool flow
                            answer = msg.content

                elif node_name == "execute_tools":
                    for msg in messages:
                        if hasattr(msg, "content"):
                            content = msg.content if isinstance(msg.content, str) else json.dumps(msg.content)
                            # Extract answer from terminal tool result
                            if not answer:
                                answer = _extract_answer_from_tool_result(content)
                            # Show truncated result
                            preview = content[:120].replace("\n", " ")
                            if len(content) > 120:
                                preview += "..."
                            print(f"  Tool -> {preview}")

                elif node_name == "validate_safety":
                    val = node_output.get("validation_result")
                    if val:
                        validation_result = val
                        status = "PASSED" if val.get("passed") else "BLOCKED"
                        print(f"  Validation: {status}")
                        if val.get("blocked_reason"):
                            print(f"    Blocked: {val['blocked_reason']}")
                        if val.get("warnings"):
                            for w in val["warnings"]:
                                print(f"    Warning: {w}")

        # Append references section to answer
        answer += _build_references_section(validation_result)

        # Show final answer
        print(f"\nClinRef: {answer}\n")

        # Show token usage
        token_tracker.print_summary()

        # Update conversation history
        from langchain_core.messages import HumanMessage, AIMessage
        conversation_history.append({"role": "user", "content": user_input})
        conversation_history.append({"role": "assistant", "content": answer})

        # Keep history manageable (last 10 exchanges)
        if len(conversation_history) > 20:
            conversation_history = conversation_history[-20:]

    db.close()


def run_single_query(query: str, provider: str, model: str, db_path: str):
    """Run a single query and print the result with streaming flow."""
    # Zero-LLM Fast Path for basic conversational inputs
    if is_conversational_query(query):
        print(f"Query: {query}\n")
        print("ClinRef: Hello! I am your clinical reference assistant. How can I help you with medical topics or drug dosing today?\n")
        return

    db = ClinRefDatabase(db_dir=Path(db_path))
    tools = init_tools(db)
    system_prompt = build_system_prompt(provider=provider)
    api_key = resolve_api_key(provider)
    llm = create_llm(provider, model, api_key)
    llm_with_tools = llm.bind_tools(tools)

    from agent import create_clinical_agent, create_initial_state
    from token_tracker import TokenTracker
    token_tracker = TokenTracker()
    agent = create_clinical_agent(llm_with_tools, tools, system_prompt, callbacks=[token_tracker])

    initial_state = create_initial_state(query, system_prompt)

    print(f"Query: {query}\n")
    answer = ""
    validation_result = None
    config = {"callbacks": [token_tracker]}

    for event in agent.stream(initial_state, config=config, stream_mode="updates"):
        for node_name, node_output in event.items():
            messages = node_output.get("messages", [])

            if node_name == "llm":
                for msg in messages:
                    if hasattr(msg, "tool_calls") and msg.tool_calls:
                        for tc in msg.tool_calls:
                            print(f"  LLM -> tool_call: {tc['name']}({json.dumps(tc['args'], ensure_ascii=False)})")
                    elif hasattr(msg, "content") and msg.content:
                        answer = msg.content

            elif node_name == "execute_tools":
                for msg in messages:
                    if hasattr(msg, "content"):
                        content = msg.content if isinstance(msg.content, str) else json.dumps(msg.content)
                        # Extract answer from terminal tool result
                        if not answer:
                            answer = _extract_answer_from_tool_result(content)
                        preview = content[:120].replace("\n", " ")
                        if len(content) > 120:
                            preview += "..."
                        print(f"  Tool -> {preview}")

            elif node_name == "validate_safety":
                val = node_output.get("validation_result")
                if val:
                    validation_result = val
                    status = "PASSED" if val.get("passed") else "BLOCKED"
                    print(f"  Validation: {status}")

    # Append references section to answer
    answer += _build_references_section(validation_result)

    print(f"\n{answer}")

    # Show token usage
    token_tracker.print_summary()

    db.close()


def run_tests(db_path: str):
    """Run basic tests against the database."""
    print("Running database tests...\n")
    db = ClinRefDatabase(db_dir=Path(db_path))

    # Test 1: Search
    print("Test 1: FTS search for 'atrial fibrillation'")
    results = db.search_topics("atrial fibrillation")
    print(f"  Found {len(results)} results")
    for r in results[:3]:
        print(f"  - [{r['id']}] {r['title']}")
    assert len(results) > 0, "Search returned no results"
    print("  PASS\n")

    # Test 2: Content search
    print("Test 2: Content search for 'anticoagulation'")
    results = db.search_content("anticoagulation")
    print(f"  Found {len(results)} results")
    for r in results[:3]:
        print(f"  - [{r['id']}] {r['title']}")
    assert len(results) > 0, "Content search returned no results"
    print("  PASS\n")

    # Test 3: Topic asset
    if results:
        topic_id = results[0]["id"]
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

    # Test no-tool-calls passes (conversational response)
    ctx = TurnContext(answer="Hello!", user_question="hello")
    result = validator.validate(ctx)
    assert result.passed
    print("  No-tool-calls passes (conversational): PASS")

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
    assert "section content" in result.blocked_reason.lower()
    print("  No-section block: PASS")

    print("\nAll tests passed!")

    db.close()


def main():
    parser = argparse.ArgumentParser(description="ClinRef Python Prototype")
    parser.add_argument("--provider", default="openai", help="LLM provider (openai, anthropic, mistral, ollama)")
    parser.add_argument("--model", default=None, help="Model name override")
    parser.add_argument("--query", default=None, help="Single query mode")
    parser.add_argument("--test", action="store_true", help="Run test suite")
    parser.add_argument("--db-path", default=str(Path(__file__).parent.parent), help="Path to database directory")
    args = parser.parse_args()

    if args.test:
        run_tests(args.db_path)
    elif args.query:
        run_single_query(args.query, args.provider, args.model, args.db_path)
    else:
        run_interactive(args.provider, args.model, args.db_path)


if __name__ == "__main__":
    main()
