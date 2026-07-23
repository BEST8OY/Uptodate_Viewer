---
name: clinref-ai-arch
description: ClinRef AI agent architecture — safety rules, citation parsing, tool set, validation pipeline. Use when modifying SafetyValidator, TurnContextAccumulator, MedicalDatabaseTools, KoogAgentFactory, SystemPrompt, or Python prototype.
---

# ClinRef AI Architecture Reference

## Tool Set (5 tools)

| Tool | Purpose | Key args |
|------|---------|----------|
| `searchTopics` | FTS search (unidex primary, fcontentsearch, fsearch fallback) | `query` (core term only) |
| `getTopicOutline` | Sections, graphics, related topics | `topicId` |
| `getTopicSectionText` | Full section markdown | `topicId`, `sectionId`, `sectionTitle` |
| `followRelatedTopic` | Passthrough to getTopicOutline | `topicId` |
| `getGraphicContent` | Table markdown (single asset lookup) | `graphicId` |

**Search flow:** unidex.en.sqlite → fcontentsearch.db → fsearch.db
**Suggestion flow:** unidex.en.sqlite (primary) → utdqf.sqlite (fallback)

## Validation Pipeline (6 rules, short-circuit)

1. **Tool calls required** — at least one tool call must exist
2. **Section content required** — `getTopicSectionText` must succeed
3. **Citation consistency** — each cited section must match a fetched section (by ID or bidirectional substring title match)
4. **No invented numbers** — boundary-aware matching; user question values allowed; markdown formatting stripped
5. **Citations required** — at least one citation
6. **No graphic interpretation** — visual language blocked UNLESS present in tool results (quoting)

Rule order matters: rule 3 runs before rule 4.

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

## Linking Format

- Topics: `[text](Topic-topicId)`
- Graphics: `[text](Graphic-graphicId)`

## Kotlin ↔ Python Parity

Both versions must have:
- Same 5 tools
- Same 6 validation rules
- Same number validation (boundary-aware, question check, markdown stripping)
- Same graphic interpretation logic (binary allow/block based on tool results)
- Same system prompt (search rules, section selection, linking)

**Rules spec:** `python_prototype/RULES_SPEC.md`

## Key Files

| Kotlin | Python | Purpose |
|--------|--------|---------|
| `SafetyValidator.kt` | `safety_validator.py` | 6 validation rules |
| `TurnContextAccumulator.kt` | `agent.py` | Tool call tracking, citation parsing |
| `MedicalDatabaseTools.kt` | `tools.py` | 5 tools, search with suggestions |
| `SearchDao.kt` | `database.py` | FTS search, suggestion lookup |
| `SystemPrompt.kt` | `system_prompt.py` | LLM instructions |
| `KoogAgentFactory.kt` | `agent.py` | Agent graph, event handlers |

## Database Architecture

| Database | Purpose | Records |
|----------|---------|---------|
| `unidex.en.sqlite` | Query suggestions (primary) | 261K |
| `utdqf.sqlite` | Query frequency (fallback) | 106K |
| `fsearch.db` | FTS topic search | 62K |
| `fcontentsearch.db` | FTS content search | 62K |
| `utdtoc.db` | Table of contents | 201K |
| `utdasset.sqlite` | Compressed topic assets | 22K topics, 40K graphics |
