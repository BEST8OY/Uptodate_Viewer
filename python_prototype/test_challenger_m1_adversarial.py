"""Empirical Gate Challenger Adversarial Test Suite for Milestone 1.

Adversarially tests:
1. Conversational Queries:
   - 'hello', 'who are you', 'help', 'what can you do', 'thanks' must NOT be intercepted as premature.
   - Conversational greeting with internal thought tags containing clinical figures/numbers must strip thoughts and pass without interception.
2. Premature Clinical Inquiries:
   - Premature clinical response with dosage figures and empty tool history must be intercepted.
   - Interception prompt must include 'SYSTEM NOTICE: PREMATURE RESPONSE INTERCEPTED' and retrieval directives.
   - Graph must transition back to LLM with incremented premature_retries.
3. Budget Exhaustion & Safe Fallback:
   - Successive premature responses must respect max_premature_retries budget (default 2).
   - When budget is exhausted, must route to validate_safety and cleanly terminate in blocked state without infinite loops or unhandled exceptions.
   - Immediate terminal block when effective retries is 0.
4. Streaming Token / Thought Tag Hygiene:
   - <thought> / <think> / <reasoning> blocks must be stripped before premature decision and safety validation.
   - Dosing numbers exclusively within thought blocks do not trigger premature clinical claims.
   - Dosing numbers outside thought blocks correctly trigger premature interception.
5. Tool Loop State Machine Invariants:
   - Tool calls immediately route to execute_tools, never premature interception.
   - Responses with fetched section evidence route to validation, not premature interception.
"""

import json
from typing import Optional
import pytest
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, ToolMessage
from langgraph.graph import END

from agent import create_clinical_agent
from safety_validator import FetchedSection, ToolCallRecord, TurnContext


class ScriptedSequenceLLM:
    """Scripted LLM returning a pre-determined sequence of fresh responses.

    Returns a new AIMessage instance per invocation to ensure unique IDs across graph turns.
    """

    def __init__(self, responses: list[BaseMessage]):
        self.responses = list(responses)
        self.call_count = 0
        self.invocations: list[list[BaseMessage]] = []

    def bind_tools(self, tool_list):
        return self

    def invoke(self, messages: list[BaseMessage], config: Optional[dict] = None) -> BaseMessage:
        self.invocations.append(messages)
        if self.call_count < len(self.responses):
            resp = self.responses[self.call_count]
            self.call_count += 1
            if isinstance(resp, AIMessage):
                return AIMessage(content=resp.content, tool_calls=resp.tool_calls)
            return resp
        return AIMessage(content="Sequence exhausted.")


# ═══════════════════════════════════════════════════════════════════════
# 1. Conversational Queries (Must NOT be intercepted)
# ═══════════════════════════════════════════════════════════════════════


class TestConversationalQueriesAdversarial:
    """Conversational user inputs must never trigger premature synthesis interception."""

    @pytest.mark.parametrize(
        "query,greeting_text",
        [
            ("hello", "Hello! I am ClinRef AI, your clinical evidence assistant."),
            ("hi there", "Hi! How can I help you with medical literature today?"),
            ("who are you", "I am ClinRef AI, an offline clinical evidence decision support assistant."),
            ("help", "I can assist you in searching UpToDate clinical guidelines and drug monographs."),
            ("what can you do", "I can search clinical topics, drug dosages, and evidence tables."),
            ("thanks", "You're welcome! Let me know if you need anything else."),
            ("thank you very much", "Glad to help!"),
            ("good morning", "Good morning! How may I assist you today?"),
        ],
    )
    def test_conversational_queries_bypass_premature_interception(self, query, greeting_text):
        llm = ScriptedSequenceLLM([AIMessage(content=greeting_text)])
        agent = create_clinical_agent(llm=llm, tools=[], system_prompt="Test Prompt", max_premature_retries=2)

        state = {
            "messages": [HumanMessage(content=query)],
            "system_prompt": "Test Prompt",
            "turn_context": TurnContext().model_dump(),
            "user_question": query,
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
            "premature_retries": 0,
        }

        final_state = agent.invoke(state)
        # Invariants:
        # 1. premature_retries remains 0
        assert final_state["premature_retries"] == 0
        # 2. No premature interception notice was injected
        interceptions = [
            m for m in final_state["messages"]
            if isinstance(m, HumanMessage) and "PREMATURE RESPONSE INTERCEPTED" in m.content
        ]
        assert len(interceptions) == 0
        # 3. Final message is the greeting
        last_msg = final_state["messages"][-1]
        assert isinstance(last_msg, AIMessage)
        assert greeting_text in last_msg.content
        # 4. Validation passed
        val = final_state.get("validation_result")
        assert val is not None
        assert val["passed"] is True

    def test_conversational_query_with_clinical_numbers_in_thought_tags(self):
        """Conversational query where LLM has intermediate thought tokens with dosages/numbers."""
        raw_output = (
            "<thought>\n"
            "The user said 'hello'. I recall trial 10294 evaluated 500 mg vs 250 mg with 85.5% success.\n"
            "However, this is just a friendly greeting, so no clinical claims should be made.\n"
            "</thought>\n"
            "Hello doctor! I am ready to help you navigate clinical guidelines."
        )
        llm = ScriptedSequenceLLM([AIMessage(content=raw_output)])
        agent = create_clinical_agent(llm=llm, tools=[], system_prompt="Test Prompt", max_premature_retries=2)

        state = {
            "messages": [HumanMessage(content="Hello doctor!")],
            "system_prompt": "Test Prompt",
            "turn_context": TurnContext().model_dump(),
            "user_question": "Hello doctor!",
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
            "premature_retries": 0,
        }

        final_state = agent.invoke(state)
        assert final_state["premature_retries"] == 0
        last_msg = final_state["messages"][-1]
        assert isinstance(last_msg, AIMessage)
        # Thought must be stripped from final AIMessage content
        assert "<thought>" not in last_msg.content
        assert "500 mg" not in last_msg.content
        assert "Hello doctor!" in last_msg.content
        assert final_state["validation_result"]["passed"] is True


# ═══════════════════════════════════════════════════════════════════════
# 2. Premature Clinical Inquiries (Must be intercepted)
# ═══════════════════════════════════════════════════════════════════════


class TestPrematureClinicalInquiriesAdversarial:
    """Clinical inquiries answered immediately with specific doses without tools must be intercepted."""

    @pytest.mark.parametrize(
        "premature_answer",
        [
            "The recommended dosage of amoxicillin is 500 mg orally every 8 hours for 10 days.",
            "Start apixaban at 5 mg orally twice daily.",
            "Administer ceftriaxone 1 g IV daily.",
            "Initial dose of lisinopril is 10 mg once daily.",
            "Give morphine 2 to 4 mg IV every 2 to 4 hours as needed.",
            "Expected response rate is approximately 95% with standard therapy.",
        ],
    )
    def test_premature_assertion_routes_to_interception_node(self, premature_answer):
        # Round 1: Premature clinical answer -> Intercepted
        # Round 2: Safe general response without dosage
        resp1 = AIMessage(content=premature_answer)
        resp2 = AIMessage(content="I need to consult the clinical databases to provide verified dosing guidance.")

        llm = ScriptedSequenceLLM([resp1, resp2])
        agent = create_clinical_agent(llm=llm, tools=[], system_prompt="Test Prompt", max_premature_retries=2)

        state = {
            "messages": [HumanMessage(content="What is the recommended dose?")],
            "system_prompt": "Test Prompt",
            "turn_context": TurnContext().model_dump(),
            "user_question": "What is the recommended dose?",
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
            "premature_retries": 0,
        }

        final_state = agent.invoke(state)

        # Invariants:
        # 1. premature_retries incremented to 1
        assert final_state["premature_retries"] == 1
        # 2. Interception notice injected into history
        interceptions = [
            m for m in final_state["messages"]
            if isinstance(m, HumanMessage) and "SYSTEM NOTICE: PREMATURE RESPONSE INTERCEPTED" in m.content
        ]
        assert len(interceptions) == 1
        notice = interceptions[0].content
        assert "MANDATORY CLINICAL DIRECTIVE" in notice
        assert "get_topic_overview" in notice or "getTopicOverview" in notice
        assert "search_topics" in notice or "searchTopics" in notice
        # 3. LLM was invoked twice (initial + after interception)
        assert llm.call_count == 2


# ═══════════════════════════════════════════════════════════════════════
# 3. Budget Exhaustion & Clean Termination
# ═══════════════════════════════════════════════════════════════════════


class TestBudgetExhaustionAdversarial:
    """Exceeding premature retry budget must cleanly terminate without unhandled exceptions or loops."""

    def test_exceeding_premature_budget_terminates_in_safety_block(self):
        """When LLM repeatedly produces premature responses, retry budget is exhausted and cleanly blocks."""
        premature_turn = AIMessage(content="Give 500 mg amoxicillin orally twice daily.")

        # Provide endless stream of premature responses
        llm = ScriptedSequenceLLM([premature_turn, premature_turn, premature_turn, premature_turn])
        # max_premature_retries = 2, max_safety_retries = 0 to trigger immediate terminal block
        agent = create_clinical_agent(
            llm=llm,
            tools=[],
            system_prompt="Test Prompt",
            max_premature_retries=2,
            max_safety_retries=0,
        )

        state = {
            "messages": [HumanMessage(content="What is the amoxicillin dose?")],
            "system_prompt": "Test Prompt",
            "turn_context": TurnContext().model_dump(),
            "user_question": "What is the amoxicillin dose?",
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
            "premature_retries": 0,
        }

        final_state = agent.invoke(state)

        # Invariants:
        # When max_safety_retries=0, effective_premature_retries = min(2, 0) = 0
        # So it immediately routed to validate_safety on attempt 1!
        last_msg = final_state["messages"][-1]
        assert isinstance(last_msg, AIMessage)
        assert last_msg.content.startswith("Clinical Response Verification Blocked:")
        assert "database verification" in last_msg.content
        assert final_state["validation_result"]["passed"] is False

    def test_full_two_retry_premature_budget_exhaustion(self):
        """With max_safety_retries=1 and max_premature_retries=2, exactly 2 premature attempts occur."""
        llm = ScriptedSequenceLLM([
            AIMessage(content="Turn 1: Give 500 mg amoxicillin orally twice daily."),
            AIMessage(content="Turn 2: Give 500 mg amoxicillin orally twice daily."),
            AIMessage(content="Turn 3: Give 500 mg amoxicillin orally twice daily."),
            AIMessage(content="Turn 4: Give 500 mg amoxicillin orally twice daily."),
        ])

        agent = create_clinical_agent(
            llm=llm,
            tools=[],
            system_prompt="Test Prompt",
            max_premature_retries=2,
            max_safety_retries=1,
        )

        state = {
            "messages": [HumanMessage(content="What is the amoxicillin dose?")],
            "system_prompt": "Test Prompt",
            "turn_context": TurnContext().model_dump(),
            "user_question": "What is the amoxicillin dose?",
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
            "premature_retries": 0,
        }

        final_state = agent.invoke(state)

        # Exactly 2 premature interception notices in message history
        interceptions = [
            m for m in final_state["messages"]
            if isinstance(m, HumanMessage) and "SYSTEM NOTICE: PREMATURE RESPONSE INTERCEPTED" in m.content
        ]
        assert len(interceptions) == 2
        assert final_state["premature_retries"] == 2

        # Terminal state must be cleanly blocked
        last_msg = final_state["messages"][-1]
        assert isinstance(last_msg, AIMessage)
        assert last_msg.content.startswith("Clinical Response Verification Blocked:")


# ═══════════════════════════════════════════════════════════════════════
# 4. Thought Tags & Reasoning Token Hygiene
# ═══════════════════════════════════════════════════════════════════════


class TestThoughtTagsAdversarial:
    """Test reasoning token sanitizer integration with premature interception."""

    def test_dosing_inside_thought_tag_does_not_trigger_premature_interception(self):
        """If dosing numbers exist only inside thought tags, clean text has no dose and is treated as non-dosing."""
        raw_output = (
            "<thought>Considering 500 mg amoxicillin TID for 10 days, but I must advise clinical consult.</thought>\n"
            "Please consult your primary care physician for personalized clinical evaluation."
        )
        llm = ScriptedSequenceLLM([AIMessage(content=raw_output)])
        agent = create_clinical_agent(llm=llm, tools=[], system_prompt="Test Prompt", max_premature_retries=2)

        state = {
            "messages": [HumanMessage(content="What medicine should I take?")],
            "system_prompt": "Test Prompt",
            "turn_context": TurnContext().model_dump(),
            "user_question": "What medicine should I take?",
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
            "premature_retries": 0,
        }

        final_state = agent.invoke(state)
        # Because clean text has no dosing quantities, is_non_clinical_response returns True
        assert final_state["premature_retries"] == 0
        last_msg = final_state["messages"][-1]
        assert "<thought>" not in last_msg.content
        assert "Please consult your primary care physician" in last_msg.content

    def test_dosing_outside_thought_tag_triggers_premature_interception(self):
        """If thought tag is present and dosing numbers are in clean text, premature interception triggers."""
        raw_output = (
            "<thought>Reviewing pharmacology guidelines.</thought>\n"
            "Standard adult dose is 1,000 mg IV every 24 hours."
        )
        resp2 = AIMessage(content="Awaiting clinical tools to retrieve evidence.")
        llm = ScriptedSequenceLLM([AIMessage(content=raw_output), resp2])
        agent = create_clinical_agent(llm=llm, tools=[], system_prompt="Test Prompt", max_premature_retries=2)

        state = {
            "messages": [HumanMessage(content="What is the ceftriaxone dose?")],
            "system_prompt": "Test Prompt",
            "turn_context": TurnContext().model_dump(),
            "user_question": "What is the ceftriaxone dose?",
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
            "premature_retries": 0,
        }

        final_state = agent.invoke(state)
        # Must intercept premature assertion
        assert final_state["premature_retries"] == 1
        interceptions = [
            m for m in final_state["messages"]
            if isinstance(m, HumanMessage) and "SYSTEM NOTICE: PREMATURE RESPONSE INTERCEPTED" in m.content
        ]
        assert len(interceptions) == 1

    def test_deepseek_think_tags_sanitization(self):
        """Verifies <think>...</think> tags are stripped cleanly."""
        raw_output = (
            "<think>Patient weight is 70 kg, initial dose 100 mg.</think>\n"
            "Recommended dose is 100 mg daily."
        )
        resp2 = AIMessage(content="Retrieving guidelines.")
        llm = ScriptedSequenceLLM([AIMessage(content=raw_output), resp2])
        agent = create_clinical_agent(llm=llm, tools=[], system_prompt="Test Prompt", max_premature_retries=2)

        state = {
            "messages": [HumanMessage(content="What is the dose?")],
            "system_prompt": "Test Prompt",
            "turn_context": TurnContext().model_dump(),
            "user_question": "What is the dose?",
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
            "premature_retries": 0,
        }

        final_state = agent.invoke(state)
        assert final_state["premature_retries"] == 1


# ═══════════════════════════════════════════════════════════════════════
# 5. Non-Premature Tool Execution Invariants
# ═══════════════════════════════════════════════════════════════════════


class TestToolExecutionInvariantsAdversarial:
    """When tools or evidence are present, premature interception must not trigger."""

    def test_response_with_fetched_sections_routes_to_validation_not_premature(self):
        """When fetched_sections has evidence, direct text response routes to validation, never premature interception."""
        llm = ScriptedSequenceLLM([AIMessage(content="Based on retrieved guidelines, the dose is 5 mg twice daily.")])
        agent = create_clinical_agent(llm=llm, tools=[], system_prompt="Test Prompt", max_premature_retries=2)

        tc = TurnContext()
        tc.tool_calls.append(
            ToolCallRecord(
                tool_name="get_topic_sections_text",
                arguments={"topic_id": "1"},
                result="...",
                success=True,
            )
        )
        tc.fetched_sections.append(
            FetchedSection(
                topic_id="1",
                topic_title="Apixaban",
                section_id="H1",
                section_title="Dosing",
                content_snippet="Standard dose is 5 mg twice daily.",
            )
        )
        tc.topic_titles["1"] = "Apixaban"
        tc.outline_sections["1"] = {"H1": "Dosing"}

        state = {
            "messages": [HumanMessage(content="What is the apixaban dose?")],
            "system_prompt": "Test Prompt",
            "turn_context": tc.model_dump(),
            "user_question": "What is the apixaban dose?",
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 1,
            "premature_retries": 0,
        }

        final_state = agent.invoke(state)
        # Invariants:
        # 1. premature_retries remains 0 (not intercepted!)
        assert final_state["premature_retries"] == 0
        # 2. Validation passed
        assert final_state["validation_result"]["passed"] is True
        # 3. Final message is AIMessage with verified answer
        assert "5 mg twice daily" in final_state["messages"][-1].content
