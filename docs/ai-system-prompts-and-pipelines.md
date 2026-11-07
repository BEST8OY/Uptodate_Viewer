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
                        │ pass → clinical_synthesis (distilled <retrieved_evidence>)
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

### Production Kotlin Multiplatform (`ClinicalOrchestrator`) Execution Model

In the production Kotlin application (`:shared`), this lifecycle is implemented by `ClinicalOrchestrator` via an explicit procedural pipeline directly on `PromptExecutor`:

1. **Stage 0: Deterministic Small-Talk Gating**: Immediate regex check (`QuestionRouter.smallTalk`). Greetings and conversational pleasantries bypass the retrieval pipeline with zero LLM or network calls.
2. **Stage 1: Ephemeral Parallel Tool Retrieval Loop**: Runs up to `MAX_RETRIEVAL_ROUNDS = 10`. When the model suggests multiple tools, they execute concurrently via `coroutineScope { awaitAll() }`. Evidence sections are accumulated in full without artificial truncation (`MAX_EVIDENCE_CHARS` limit removed to guarantee complete clinical tables and multi-page dosing matrices reach synthesis unclipped). Multi-turn conversation history is retained up to `MAX_CHAT_HISTORY_TURNS = 100`.
   - **Evidence Gating & 1-Pass Retry**: If round execution ends with zero retrieved evidence, a single retrieval retry is triggered (`buildEvidenceGatePausePrompt`). If still empty, the orchestrator emits `CANONICAL_ABSTENTION` and halts without synthesis.
3. **Stage 2: Closed-Book Clinical Synthesis**: Tools are completely omitted (`tools = emptyList()`), strictly preventing tool hallucination. Synthesis streams token-by-token into `StreamingManager.onStreamingTextDelta`. Static clinical guidance is cached (`AnthropicCacheControl.Default`), and reasoning tokens are sanitized (`ReasoningTokenSanitizer`).
4. **Gate 2: Deterministic Safety Validation & 1-Pass Revision**: `SafetyValidator.validate` checks the completed draft for unverified quantities (`UNVERIFIED_QTY` / `UNRETRIEVED_DOSES`). A single-pass tool-free revision is issued (`buildRevisionPrompt`). If figures remain unverified after revision, the draft is presented with amber warning badges citing the offending values.

### Python Prototype Reference Model

In `python_prototype/agent.py`, the turn is driven by LangGraph state transitions:
Budgets: `MAX_TOOL_ROUNDS = 24`, `maxSafetyRetries = 2` (Gate 1 retries, Gate 2 revisions per draft, premature interceptions), `MAX_EVIDENCE_ESCALATIONS = 1`, `maxAgentIterations = 128`.

Why escalation exists: a tool-free revision can only drop or re-word a value. If the value was never retrieved, the model tends to restate it and the turn dead-ends after the budget. After one failed revision the violation becomes `UNRETRIEVED_DOSES` (Phase 1), the offending values are named in the prompt, and tools are available again. Escalation is capped at one, and it resets the revision counter so the freshly retrieved draft gets its own repair attempt.

## 2. System prompt

Two system messages, so provider-side prompt caching stays effective:

1. `SystemPrompt.STATIC_CLINICAL_GUIDANCE` — invariant, cache-eligible (Anthropic `cache_control`).
2. `SystemPrompt.patientContextBlock(profile)` — dynamic, omitted when the profile is empty.

The static block (≈2.6 KB, cache-eligible) has four sections:

1. **Retrieve first (Database Schema & Tool Mechanics)** — zero internal memory directive, the deterministic retrieval pipeline
   (`searchTopics` → `getTopicOverview` → `getTopicSectionsText` / `getGraphicContent`), query discipline
   (1–3 plain keywords, no demographics, lab values, or conversational sentences; parallel searches for multi-drug/comparative entities),
   zero-hit recovery via typeahead `refine_with` vocabulary, topic type routing (`medical` clinical reviews vs `drug` Lexidrug monographs vs `calc` scoring calculators with `FULL` section short-circuiting), actionable Dose Provenance rule (retrieving the dedicated `drug` topic for exact milligram dosing and organ cutoffs), section selection and batching (up to 10 section IDs per call, in-article section locator `searchTopicSections(topicId, query)`), tabular data retrieval (`graphic_table`), and the Outlines Are Not Evidence directive (headings contain zero evidence; section text or tables must be in context before answering).
2. **Fidelity (Strict Closed-World Grounding)** — verbatim quantities character-for-character,
   preserved GRADE recommendation strength modal verbs (`recommend` vs `suggest` vs `may`), patient-profile
   cross-check, canonical abstention (`Not specified in retrieved UpToDate sections.`), strict evidence
   boundaries, and no image interpretation.
3. **Answer structure (Let the Question Decide)** — direct bold clinical conclusion always (unlabeled),
   no conversational preambles ("Based on the retrieved evidence..."), followed by details grouped under
   short bold headings that name what the question asks for (causes, workup, dosing, monitoring, prognosis,
   escalation), preferring the retrieved section's own title, completely omitting unneeded outline headings,
   and never forcing an empty heading or heading stating nothing was specified.
4. **Output mechanics** — plain text, bold for opening clinical conclusion and headers only, simple bullets,
   no links or reference list.

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
| 3 | `getTopicSectionsText` | `topicId`, `sectionIds[]` | Verbatim section text, ≤ 10 sections per call (extras reported in `omittedSections`); applies parent-child section subsumption in outline order to eliminate redundant descendant text |
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
model was given. `python_prototype/eval_prompt.py` runs a stratified case file (`eval_cases.jsonl`, 20 clinical scenarios across 10 categories) and scores each turn across a **4-Tier Clinical Decision Support Evaluation Engine**:

| Evaluation Tier | Scored Properties & Contract |
|---|---|
| **Tier 1: Clinical Safety (Blockers)** | 100% quantitative grounding (`SafetyValidator` Gate 2 set difference); anti-arithmetic (no calculated $mg/kg$ absolute doses); patient profile contraindication adherence; modality boundaries (zero visual interpretation of unparsed images/scans). |
| **Tier 2: Epistemic & GRADE Fidelity** | Exact GRADE modal verb preservation (`recommend` vs `suggest` vs `may`); canonical abstention (`Not specified in retrieved UpToDate sections.`); quantitative entity binding (preventing threshold shifting). |
| **Tier 3: Point-of-Care UX & Cognitive Load** | Direct Line 1 bold declarative clinical conclusion (`is_bluf()`, forbidding colon labels like `**Management:**`); zero conversational preambles (`PREAMBLE_RE`); empty heading prohibition (omitting unneeded or empty subheadings). |
| **Tier 4: Output Mechanics** | Plain text formatting discipline; strict prohibition of Markdown ATX headings (`#`/`##`/`###`), markdown links, and hallucinated inline citation brackets (`[1]`). |

### Fast Synthesis, Retrieval & Remediation Benchmark Modes

The evaluation framework supports three comprehensive benchmark harnesses:

1. **Fast Synthesis Mode (`eval_prompt.py` / `./benchmark_agy.sh`)**: Rapidly benchmarks prompt variants directly against pre-accumulated evidence digests without tool overhead. Runs against real Gemini via the local pre-authenticated `agy` CLI bridge:
   ```bash
   ./benchmark_agy.sh                      # Full 20-case CDS evaluation suite
   ./benchmark_agy.sh --verbose            # Full answers and diagnostic telemetry
   ./benchmark_agy.sh --case <case-id>     # Run single case (e.g. pediatric-ceftriaxone)
   ./benchmark_agy.sh --category <name>    # Run by category (e.g. patient_profile)
   ```
2. **Multi-Turn Tool Retrieval Benchmark (`eval_retrieval.py`)**: Tests Phase 1 query discipline, calculator short-circuiting on section `FULL`, Lexidrug monograph dosing provenance, and parallel retrieval against SQLite DBs:
   ```bash
   cd python_prototype && uv run python eval_retrieval.py --prompt rewrite
   ```
3. **Self-Correction & Remediation Loop Benchmark (`eval_remediation.py` / `./benchmark_remediation.sh`)**: Tests the closed-loop recovery immune system across three suites:
   - **Suite A (Gate 1 Evidence Gate Pause)**: Verifies recovery when a turn ends without section text (`buildEvidenceGatePausePrompt`).
   - **Suite B (Gate 2 Closed-Book Safety Revision)**: Injects clinical hallucinations (`UNVERIFIED_QTY`, synthetic math, unit conversions, visual interpretations) and validates 1-pass correction (`buildRevisionPrompt`).
   - **Suite C (Loop C Missing Evidence Escalation)**: Verifies escalation back to tool-calling when an unretrieved drug dose was stated (`buildMissingEvidencePrompt`).
   ```bash
   ./benchmark_remediation.sh              # Run all 14 remediation scenarios across Suites A, B, C
   ./benchmark_remediation.sh --suite suite_b # Test Gate 2 Safety Revision only
   ./benchmark_remediation.sh --case rev-unit-conversion --verbose
   ```
4. **Full Agentic Mode (`--mode agent`)**: Runs the multi-step retrieval graph over root SQLite databases:
   ```bash
   cd python_prototype
   uv run python eval_prompt.py --mode agent --provider mistral --model ministral-14b-latest --verbose
   ```

A prompt change is an improvement only if this scorecard improves without regressing
`grounded`/`abstain`. That is the rule for further prompt edits: measure, do not add adjectives.

## 10. Verification

```bash
./gradlew :shared:desktopTest          # 337 tests
cd python_prototype && uv run pytest   # 421 tests
```

Known flake, unrelated to the AI layer: `ReadingPositionRepositoryTest."save is visible immediately
before it is flushed"` races Room's IO executor under full-suite load (passes standalone).
