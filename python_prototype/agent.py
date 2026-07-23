r"""LangGraph clinical retrieval agent — mirrors KoogAgentFactory.kt.

State graph:
    nodeStart -> [LLM Request] <-> [Execute Tools] (tool loop)
                          \-> [Safety Validate] -> [Self-Correction] or [Finish]
"""

import json
from typing import Annotated, Callable, Optional, TypedDict

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.tools import BaseTool
from langgraph.graph import END, StateGraph
from langgraph.graph.message import add_messages

from safety_validator import (
    FetchedSection,
    SafetyValidator,
    ToolCallRecord,
    TurnContext,
    ValidationResult,
)


class AgentState(TypedDict):
    messages: Annotated[list[BaseMessage], add_messages]
    system_prompt: str
    turn_context: dict  # Serialized TurnContext
    user_question: str  # Original user question for number validation
    retry_count: int
    validation_result: Optional[dict]


def _build_tool_map(tools: list[BaseTool]) -> dict[str, Callable]:
    """Build name -> invoke mapping for manual tool execution."""
    return {t.name: t for t in tools}


def create_clinical_agent(
    llm,
    tools: list[BaseTool],
    system_prompt: str,
    callbacks: list = None,
    max_tool_retries: int = 20,
    max_safety_retries: int = 2,
):
    """Create the clinical retrieval agent graph.

    Args:
        llm: LangChain chat model with bound tools
        tools: List of tool functions
        system_prompt: System prompt text
        callbacks: List of callback handlers (e.g., TokenTracker)
        max_tool_retries: Max tool loop iterations (default 25, matching Kotlin)
        max_safety_retries: Max self-correction retries (default 2)
    """
    tool_map = _build_tool_map(tools)

    def should_continue(state: AgentState) -> str:
        """Route: tool calls -> execute_tools, text response -> validate_safety."""
        last_message = state["messages"][-1]
        if isinstance(last_message, AIMessage) and last_message.tool_calls:
            return "execute_tools"
        return "validate_safety"

    def call_llm(state: AgentState) -> dict:
        """Invoke LLM with conversation history."""
        config = {"callbacks": callbacks} if callbacks else {}
        response = llm.invoke(state["messages"], config=config)
        return {"messages": [response]}

    def execute_tools(state: AgentState) -> dict:
        """Execute tool calls manually (mirrors Kotlin ToolRegistry pattern)."""
        last_message = state["messages"][-1]
        tc = TurnContext(**state["turn_context"])
        new_messages: list[BaseMessage] = []

        if not isinstance(last_message, AIMessage) or not last_message.tool_calls:
            return {}

        for tool_call in last_message.tool_calls:
            name = tool_call["name"]
            args = tool_call["args"]
            tool_call_id = tool_call.get("id", "")

            tc.tool_calls.append(
                ToolCallRecord(
                    tool_name=name,
                    arguments=args,
                    result="",
                    success=True,
                )
            )

            # Execute the tool
            tool = tool_map.get(name)
            if tool is None:
                result_content = json.dumps({"error": f"Unknown tool: {name}"})
                tc.tool_calls[-1].success = False
            else:
                try:
                    raw = tool.invoke(args)
                    result_content = raw if isinstance(raw, str) else json.dumps(raw)
                except Exception as e:
                    result_content = json.dumps({"error": str(e)})
                    tc.tool_calls[-1].success = False

            tc.tool_calls[-1].result = result_content
            tc.tool_results.append(result_content)

            # Track fetched sections
            if name == "get_topic_section_text":
                tc.fetched_sections.append(
                    FetchedSection(
                        topic_id=args.get("topic_id", ""),
                        section_id=args.get("section_id", ""),
                        section_title=args.get("section_title", ""),
                    )
                )

            # Track graphic IDs
            if name == "get_graphic_content":
                graphic_id = args.get("graphic_id", "")
                if graphic_id:
                    tc.graphic_ids.add(graphic_id)

            new_messages.append(
                ToolMessage(content=result_content, tool_call_id=tool_call_id)
            )

        return {
            "messages": new_messages,
            "turn_context": tc.model_dump(),
            "user_question": state.get("user_question", ""),
        }

    def validate_safety(state: AgentState) -> dict:
        """Run safety validation on the final answer."""
        last_message = state["messages"][-1]
        answer = last_message.content if hasattr(last_message, "content") else str(last_message)

        tc = TurnContext(**state["turn_context"])
        tc.answer = answer
        tc.user_question = state.get("user_question", "")

        validator = SafetyValidator()
        tc.citations = validator.parse_citations(answer)
        validation = validator.validate(tc)

        if not validation.passed and state["retry_count"] < max_safety_retries:
            correction_msg = HumanMessage(
                content=(
                    "CRITICAL SYSTEM NOTICE: Your answer was blocked by clinical safety verification.\n"
                    f"REASON: {validation.blocked_reason}\n\n"
                    "Re-read the retrieved section text and respond strictly using fetched content with citations. "
                    "Do not invent any numbers or data not present in the tool results."
                )
            )
            return {
                "messages": [correction_msg],
                "retry_count": state["retry_count"] + 1,
                "turn_context": tc.model_dump(),
                "validation_result": validation.model_dump(),
            }

        return {
            "validation_result": validation.model_dump(),
            "turn_context": tc.model_dump(),
        }

    def check_validation_next(state: AgentState) -> str:
        """Route after validation: retry or finish."""
        last = state["messages"][-1]
        if isinstance(last, HumanMessage) and "CRITICAL SYSTEM NOTICE" in last.content:
            return "llm"
        return END

    # ── Build the graph ────────────────────────────────────────────────

    workflow = StateGraph(AgentState)

    workflow.add_node("llm", call_llm)
    workflow.add_node("execute_tools", execute_tools)
    workflow.add_node("validate_safety", validate_safety)

    workflow.set_entry_point("llm")

    workflow.add_conditional_edges("llm", should_continue, {
        "execute_tools": "execute_tools",
        "validate_safety": "validate_safety",
    })
    workflow.add_edge("execute_tools", "llm")
    workflow.add_conditional_edges("validate_safety", check_validation_next, {
        "llm": "llm",
        END: END,
    })

    return workflow.compile()


def create_initial_state(
    user_message: str,
    system_prompt: str,
) -> dict:
    """Create initial agent state for a new turn."""
    return {
        "messages": [
            SystemMessage(content=system_prompt),
            HumanMessage(content=user_message),
        ],
        "system_prompt": system_prompt,
        "turn_context": TurnContext().model_dump(),
        "user_question": user_message,
        "retry_count": 0,
        "validation_result": None,
    }
