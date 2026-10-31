# ClinRef AI — Prompts, Validators & Retrieval Contracts

> Implementation-accurate reference for the ClinRef AI clinical retrieval agent, generated from the
> code on 2026-10-26. It documents the prompts, the deterministic validators, the tool contracts, and
> the database facts those contracts depend on — so a reviewer can audit behaviour without re-reading
> the source. Both stacks (Kotlin `:shared`, Python `python_prototype/`) implement the same rules;
> `test_cross_stack_scenarios.py` and `CrossStackScenarioParityTest.kt` pin the shared behaviour.
>
> For the comprehensive biomedical informatics whitepaper detailing the theoretical foundations,
> prefix-caching benchmarks, GRADE preservation, and deterministic evaluation matrices, see:
> [Architectural Principles for a Universal Clinical Decision Support System Prompt Over an Offline UpToDate Corpus](architectural-principles-universal-clinical-prompt.md).

## 1. Turn architecture

```
route_intent  ──small talk──▶ canned reply (no LLM, no tools) ──▶ finish
      │
      └──clinical──▶ evidence_gathering (tools bound)
                        │ loop: tool calls ⇄ tool results
                        │ premature text + no evidence → interception prompt (max 2)
                        │ tool-round budget: MAX_TOOL_ROUNDS = 24
                        ▼
                     validate_evidence        ← Gate 1 (NO_TOOLS / NO_SECTIONS)
                        │ fail → evidence-gate pause prompt → evidence_gathering
                        │ pass → (compress history if near token limit) → synthesis
                        ▼
                     clinical_synthesis       ← closed book, tools = emptyList()
                        ▼
                     validate_safety          ← Gate 2 (quantities, visual interpretation)
                        │ pass → finish
                        │ fail (UNVERIFIED_QTY / VISUAL / BLANK_WITH_EVIDENCE)
                        │        → safety-revision prompt → clinical_synthesis
                        │ fail again on quantities
                        │        → UNRETRIEVED_DOSES: tools handed back (once)
                        │          → evidence_gathering → synthesis, revision budget reset
                        └ budget exhausted → blocked message (reason + what to do) → finish
```

Budgets: `MAX_TOOL_ROUNDS = 24`, `maxSafetyRetries = 2` (Gate 1 retries, Gate 2 revisions per
draft, premature interceptions), `MAX_EVIDENCE_ESCALATIONS = 1`, `maxAgentIterations = 128`,
history compression at 80 % of `historyCompressionThreshold` (128 000 default).

Why escalation exists: a tool-free revision can only drop or re-word a value. If the value was
never retrieved, the model tends to restate it and the turn dead-ends after the budget. After one
failed revision the violation becomes `UNRETRIEVED_DOSES` (Phase 1), the offending values are named
in the prompt, and tools are available again. Escalation is capped at one, and it resets the
revision counter so the freshly retrieved draft gets its own repair attempt.

## 2. System prompt

Two system messages, so provider-side prompt caching stays effective:

1. `SystemPrompt.STATIC_CLINICAL_GUIDANCE` — invariant, cache-eligible (Anthropic `cache_control`).
2. `SystemPrompt.patientContextBlock(profile)` — dynamic, omitted when the profile is empty.

The static block (≈2.6 KB, cache-eligible) has four sections:

1. **Retrieve first** — the pipeline in one line, which section titles answer which class of
   question, and the rule that a disease topic's recommendations rarely contain doses (retrieve the
   drug topic that owns one).
2. **Answer shape** — BLUF always, then the detail grouped under short bold headings that name
   what the question asks for (causes, workup, dosing, monitoring, prognosis, escalation), preferring
   the retrieved section's own title and never forcing a heading the question does not ask for.
   There is no fixed set of shapes: a closed taxonomy produced nonsense such as "Preferred
   Etiologies" on a causes question, because no listed shape fit, so the model bolted the topic onto
   a therapy label. Deriving the heading from the evidence removes that failure class.
3. **Fidelity** — verbatim quantities, preserved recommendation strength (`recommend` vs `suggest`
   vs `may`), canonical abstention, patient-profile cross-check, no image interpretation.
4. **Output mechanics** — plain text, bold for BLUF/headers only, no links or reference list.

Two deliberate properties: the retrieval *order* is restated only for planning (the graph enforces
it anyway), and the format rules exist in exactly one place — the synthesis directive carries a
one-line contract (`SYNTHESIS_CONTRACT`) instead of repeating the header list.

Canonical abstention string, rendered from a single constant in both stacks:

```
Not specified in retrieved UpToDate sections.
```

The synthesis turn does **not** repeat the patient profile — it is a persistent system message that
survives the Phase-1 session rewrite. It carries only the evidence digest plus the output contract.

## 3. Remediation prompts

| Trigger | Template | Purpose |
|---|---|---|
| `NO_TOOLS`, `NO_SECTIONS`, `BLANK_NO_EVIDENCE` | `buildEvidenceGatePausePrompt` | Demand tool calls; list what is already retrieved |
| `UNVERIFIED_QTY`, `VISUAL`, `BLANK_WITH_EVIDENCE` | `buildRevisionPrompt` | Rewrite the draft against the evidence; carries `<violations>`, `<previous_draft>`, `<retrieved_evidence>` |
| Phase-1 text before any evidence | `buildPrematureInterceptionPrompt` | Refuse text, demand the search → overview → sections sequence |
| `UNRETRIEVED_DOSES` | `buildMissingEvidencePrompt` | Name the unverified values, hand tools back, demand the drug topics that own those doses |

`buildCorrectionPrompt` dispatches on the violation codes: `UNRETRIEVED_DOSES` first, then
`ViolationCodes.PHASE_1_TEMPLATE_CODES`, then the revision template.

The static prompt also carries a **Dose Provenance** rule (disease topics rarely contain numeric
doses — retrieve the drug topic before stating one) and an **Empty Header Rule** (never fill the
dosing header with an unretrieved value; say so in one line instead). Both exist because a mandatory
dosing header otherwise pushes weak models to answer doses from memory.

The terminal message keeps the stable `Clinical Response Verification Blocked: ` marker, then states
that the draft is withheld and points at the attached reference cards, so a blocked turn is
actionable rather than a dead end.

## 4. Retrieval tools (5)

| # | Tool | Args | Contract |
|---|---|---|---|
| 1 | `searchTopics` | `query` | Candidate `{id, title}` pairs; auto-refines through the typeahead vocabulary when the direct hit is empty |
| 2 | `getTopicOverview` | `topicId` | Section outline, `graphic_table` ids, related topics, `topicType`; calculator topics return full content |
| 3 | `getTopicSectionsText` | `topicId`, `sectionIds[]` | Verbatim section text, ≤ 10 sections per call; extras reported in `omittedSections` |
| 4 | `searchTopicSections` | `topicId`, `query` | Section ids + snippets for one narrow topic inside a long article |
| 5 | `getGraphicContent` | `graphicId` | Markdown table for `graphic_table` assets; non-tables return an error envelope |

`getRelatedTopics` was removed: related topics are already part of the overview bundle, so a second
round trip only added latency and tool-selection ambiguity.

## 5. Database facts the contracts rely on

Dumps verified against the shipped SQLite files.

| Database | Table | Facts used |
|---|---|---|
| `utdasset.sqlite` | `topic_asset` | 22 363 zstd payloads: `topicInfo.type` / `subtype`, `outlineHtml`, `bodyHtml`, `relatedGraphics`, `relatedTopics` |
| `utdasset.sqlite` | `graphic_asset` | 39 921 payloads: `graphicInfo.subtype` (`graphic_table`, `graphic_picture`, `graphic_figure`, …) + `imageHtml` |
| `utdasset.sqlite` | `other_asset` | 1 378 `table_of_contents_*` resources |
| `unidex.en.sqlite` | `query` / `query_topic` / `topic` | 254 999 queries, 22 k titles; `topic_hits` blobs are the curated topic search |
| `utdqf.sqlite` | `qf` | Typeahead fallback vocabulary |
| `utdtoc.db` | `TOC` / `TOCMap` | Navigation tree only (202 023 leaves); unrelated to retrieval |
| `fsearch.db` | `Search` (FTS4) | Visual-index catalogue; retrieval tools must not depend on it |

Rules that follow from the dumps:

- **`topicType` is declared, not inferred.** `topicInfo.type` is one of `drug`, `medical`, `calc`,
  `narrative`; it replaces the `outlineHtml.isBlank()` guess.
- **`outlineHtml` hrefs carry no `subtype`.** An outline-derived graphic list therefore cannot tell a
  table from a photograph, and the previous parser produced an empty list for every topic. The table
  inventory now comes from `relatedGraphics[].graphics[].graphicInfo.subtype == "graphic_table"`, and
  was verified to contain exactly the same ids as the outline (205 ids across 40 sampled topics, zero
  difference either way).
- **Two tables are boilerplate.** `57469` ("UpToDate Lexidrug clinical abbreviations") and `116421`
  ("Country abbreviations") are attached to ~87 % of drug topics and are the only tables those topics
  have. They are filtered so the model is not invited to fetch a 1 000-row abbreviation list.
- **Units in the corpus.** Doses are dominated by `mg`, `mcg`, `%`/`percent`, `mL`, `g`, `kg`,
  `units`, `mEq`, `mmol`, `mmHg`, `mg/day`, `mg/kg`, `mcg/mL`, `mg/mL`. Ranges (`5 to 100 mg`) appear
  ~380 times per 120 sampled topics; thousand separators are rare (~15 per 6.4 M characters) but do
  occur, so notation equivalence is required in both directions.
- **Cost.** Every payload is a 100–500 KB JSON document; each `topic_asset` read costs one zstd
  decompression. Callers read it once (Kotlin `contentRepository.getTopicContent`, Python
  `db.get_topic_asset`) and derive title, type, sections, graphics, and related topics from that one
  read.

## 6. Deterministic validators

`SafetyValidator` exposes two gates plus a composite verdict. Evidence failures own the composite
verdict, because their remediation is the retrieval prompt (which never renders answer findings);
inside a gate every rule collects.

**Gate 1 — `validateEvidence`**

- `NO_TOOLS` when the turn made no tool calls.
- `NO_SECTIONS` when no section text or table content was captured. A topic outline is explicitly not
  evidence; a successful `getTopicSectionsText` / `getGraphicContent` call, or accumulated section /
  graphic content, is.

**Gate 2 — `validateAnswer`**

- `UNVERIFIED_QTY` when the answer contains a clinical quantity absent from the evidence corpus.
- `VISUAL` when the answer describes image findings that the retrieved text does not contain.

**Quantity comparison** (identical in both stacks). Quantities are extracted from the answer and from
the corpus as canonical `<number> <unit>` tokens and compared by set membership:

| Written | Canonical |
|---|---|
| `5-10 mg`, `5 to 10 mg` | `5 mg` **and** `10 mg` (both bounds verified) |
| `1,200 mg` | `1200 mg` |
| `2.0 mg` | `2 mg` |
| `5 %`, `5 percent` | `5 %` |
| `25 µg`, `25 μg`, `25 ug` | `25 mcg` |
| `3 day`, `3 days` | `3 days` |
| `1 year`, `1-year`, `1 year of age` | `1 years` (age cutoffs) |

The corpus is tool output + fetched section text and titles + graphic labels + topic titles + the user
question + the active patient profile. Including the last two means restating a patient-stated value
is never treated as invention.

Violations are the only remediation input: `[CODE] detail` lines, one per rule, with codes stable
enough to be string-shared across stacks.

## 7. Empty-result handling

- `searchTopics` → `results = []`, `refine_with` = typeahead vocabulary, and an auto-refined retry
  when a suggestion resolves. The vocabulary is queried once per empty search, never on the hit path.
- `getTopicOverview` → `error` envelope (`Topic not found: …`).
- `getTopicSectionsText` → `invalidSections` when ids resolve to nothing, `omittedSections` past the
  ten-section cap, `error` when the topic is absent.
- `getGraphicContent` → `error` when the id is missing, empty, or not a `graphic_table`.

## 8. Parity constraints

1. Prompt text, templates, and constants are byte-identical across stacks (pinned by the parity tests).
2. Tool names are camelCase in both; Python exposes snake_case aliases for its own registry.
3. Every tool result carries `topicId` alongside `topicTitle` so title-matching heuristics are never
   needed.
4. `FULL` is the calculator / whole-article section sentinel and is normalised to `""` in reference
   models so reader navigation targets the article root.

## 9. Evaluating the prompt

Prompt quality here is not a matter of taste; it is the fraction of turns that satisfy rules the
model was given. `python_prototype/eval_prompt.py` runs a case file (`eval_cases.jsonl`) through the
real graph and scores each turn deterministically — no LLM judge, no network:

| check | prompt rule it measures |
|---|---|
| `grounded` | every clinical quantity in the answer appears in the retrieved text (Gate 2) |
| `bluf` | answer opens with a bolded bottom line |
| `no_preamble` | no "Based on the retrieved evidence…" opener |
| `refs` | at least one retrieved section backs the answer |
| `no_doses` | an unanswerable question produced no dosing values |
| `declared_unknown` | an unanswerable question was withheld or said the value is not in the sections |

There is no length rule: the prompt no longer sets a word budget, so the report prints `words`
as an observation rather than a pass/fail check. Clinician-encoded `must_contain` /
`must_not_contain` pins catch fluent answers that no numeric rule can reject.

`expect_no_answer: true` marks a question the corpus cannot answer — where either a withheld turn
or a one-line "not in the retrieved sections" passes. Structure is not scored: the prompt sets no
fixed shapes, so the report prints the bold headings each answer used for human review.

```bash
cd python_prototype
uv run python eval_prompt.py --provider mistral --model ministral-14b-latest --verbose
```

A prompt change is an improvement only if this scorecard improves without regressing
`grounded`/`abstain`. That is the rule for further prompt edits: measure, do not add adjectives.

## 10. Verification

```bash
./gradlew :shared:desktopTest          # 304 tests
cd python_prototype && uv run pytest   # 412 tests
```

Known flake, unrelated to the AI layer: `ReadingPositionRepositoryTest."save is visible immediately
before it is flushed"` races Room's IO executor under full-suite load (passes standalone).
