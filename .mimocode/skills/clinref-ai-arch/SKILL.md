---
name: clinref-ai-arch
description: ClinRef AI agent architecture — safety rules, citation parsing, tool set, validation pipeline. Use when modifying SafetyValidator, TurnContextAccumulator, MedicalDatabaseTools, KoogAgentFactory, SystemPrompt, or Python prototype.
---

# ClinRef AI Architecture Reference

## Tool Set (7 tools)

| Tool | Purpose | Key args |
|------|---------|----------|
| `searchTopics` | FTS search (unidex primary, fcontentsearch, fsearch fallback) | `query` (core term only) |
| `getTopicOutline` | Sections, graphics, related topics | `topicId` |
| `getTopicSectionText` | Full section markdown (singular) | `topicId`, `sectionId`, `sectionTitle` |
| `getTopicSectionsText` | Batch section retrieval (plural) | `topicId`, `sectionIds: List<String>` |
| `followRelatedTopic` | Passthrough to getTopicOutline | `topicId` |
| `getGraphicContent` | Table markdown (single asset lookup) | `graphicId` |
| `submitClinicalAnswer` | **Terminal tool** — structured citations | `answerText`, `citations: List<CitationPayload>`, `noDataFound` |

**Search flow:** unidex.en.sqlite → fcontentsearch.db → fsearch.db
**Suggestion flow:** unidex.en.sqlite (primary) → utdqf.sqlite (fallback)

## Terminal Tool Pattern

`submitClinicalAnswer` is the **required** final tool call. It forces the LLM to emit structured citations as JSON instead of relying on fragile regex parsing.

**Kotlin strategy routing** (KoogAgentFactory.kt):
```
nodeExecuteTool → nodeTerminalCheck → (empty → nodeFinish | non-empty → nodeSendToolResult → LLM)
```
Custom node checks `accumulator.getToolCalls().lastOrNull()?.toolName == "submitClinicalAnswer"`. If terminal tool was called, skip LLM and go to finish.

**Python routing** (agent.py):
```python
def after_tools(state):
    tc = TurnContext(**state["turn_context"])
    if tc.tool_calls and tc.tool_calls[-1].tool_name == "submit_clinical_answer":
        return "validate_safety"
    return "llm"
```

## Deduplication

Both versions prevent duplicate tool+args execution:
- **Python**: `execute_tools()` checks `tc.tool_calls` for existing match, returns cached `ToolMessage`
- **Kotlin**: `TurnContextAccumulator.onToolCallCompleted()` checks `executedToolCalls` list, silently skips

## Validation Pipeline (7 rules, intent-aware, short-circuit)

0. **Intent-aware tool call check** — no tool calls → allow if `isNonClinicalResponse()` (no clinical terms in answer), else block
1b. **Section content required** — `getTopicSectionText`, `getTopicSectionsText`, or `getGraphicContent` must succeed
2. **Citation consistency** — each cited section must match a fetched section (by ID or bidirectional substring title match)
3. **No invented numbers** — boundary-aware matching; user question values allowed; markdown formatting stripped
4. **Citations required** — at least one citation
5. **No graphic interpretation** — visual language blocked UNLESS present in tool results (quoting)

Rule 0 is new: prevents conversational soft-lock when LLM responds to greetings without tools.

## Intent Detection

```python
CLINICAL_INTENT_TERMS = {
    "mg", "dose", "dosing", "treatment", "contraindication", "therapy",
    "diagnosis", "patient", "table", "drug", "medication", "prescribe",
    "symptom", "prognosis", "biopsy", "surgery", "procedure", "infusion",
    ...
}
```
If answer contains ANY clinical term → clinical response (requires tools).
If answer contains NO clinical terms → conversational (allow without tools).

## Number Validation

- Regex: `\d+[\.,]?\d*\s*(?:mg|%|mL|mmol|mcg|units?|mEq|L|kg|cm|mmHg)?`
- Strips: citation lines, markdown headers, list markers, bold/italic, dividers
- Boundary-aware: `(?<![\d])12(?!\d])` prevents "12" matching "123"
- Numeric-only check: "60 mL" matches "60" in tool text
- Question check: "42" matches "42 mL" from user question

## Graphic Interpretation

**Patterns (3):**
1. `the [imaging type] [action verb]` — "the CT reveals"
2. `[imaging type] [noun]` — "CT findings"
3. `the [figure type] [action verb]` — "the figure shows"

**Logic (binary):**
- If visual language in tool results → ALLOW (quoting)
- If visual language NOT in tool results → BLOCK

## Citation Format

Regex (handles markdown bullets, bold, case-insensitive):
`^\s*(?:[-*]|\d+\.)?\s*(?:\*\*)?Topic:(?:\*\*)?\s*(.+?),\s*(?:\*\*)?Section:(?:\*\*)?\s*(.+?)(?:\s*\(ID:\s*([a-zA-Z0-9_-]+)\))?(?:\*\*)?\s*(?=\s*(?:$|\n))`

Structured citations from `submitClinicalAnswer` bypass regex entirely.

## Linking Format

- Topics: `[text](Topic-topicId)`
- Graphics: `[text](Graphic-graphicId)`

## Self-Correction Pattern

When validation fails, inject previously fetched evidence into correction prompt:
- Build evidence summary from `accumulator.fetchedSections`
- Include in correction prompt with remedial instructions
- LLM re-evaluates using only the retrieved evidence

**Python** (agent.py validate_safety): builds HumanMessage with evidence, routes back to LLM
**Kotlin** (ChatViewModel.runCorrectionTurn): creates new agent with existingAccumulator, sends correction prompt

## Batch Retrieval

Always prefer `getTopicSectionsText` (batch) over individual `getTopicSectionText` calls.
System prompt instructs: "Do NOT call getTopicSectionText individually — use the batch tool instead."

**Section tracking**: `TurnContextAccumulator.parseBatchSectionResult()` parses `sectionIds` JSON array and adds each as `FetchedSection`.

## Kotlin ↔ Python Parity

Both versions must have:
- Same 7 tools
- Same 7 validation rules (intent-aware)
- Same number validation (boundary-aware, question check, markdown stripping)
- Same graphic interpretation logic (binary allow/block based on tool results)
- Same system prompt (search rules, section selection, terminal tool required, linking)
- Same deduplication logic
- Same terminal tool routing
- Same evidence injection in correction

## Key Files

| Kotlin | Python | Purpose |
|--------|--------|---------|
| `SafetyValidator.kt` | `safety_validator.py` | 7 validation rules (intent-aware) |
| `TurnContextAccumulator.kt` | `agent.py` | Tool call tracking, citation parsing, dedup |
| `MedicalDatabaseTools.kt` | `tools.py` | 7 tools including terminal + batch |
| `SearchDao.kt` | `database.py` | FTS search, suggestion lookup |
| `SystemPrompt.kt` | `system_prompt.py` | LLM instructions (terminal tool required) |
| `KoogAgentFactory.kt` | `agent.py` | Agent graph, terminal routing, event handlers |
| `ChatViewModel.kt` | `run.py` | Conversation loop, answer extraction |

## Database Architecture

| Database | Purpose | Records |
|----------|---------|---------|
| `unidex.en.sqlite` | Query suggestions (primary) | 261K |
| `utdqf.sqlite` | Query frequency (fallback) | 106K |
| `fsearch.db` | FTS topic search | 62K |
| `fcontentsearch.db` | FTS content search | 62K |
| `utdtoc.db` | Table of contents | 201K |
| `utdasset.sqlite` | Compressed topic assets | 22K topics, 40K graphics |
