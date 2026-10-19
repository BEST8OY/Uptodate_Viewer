# Project: ClinRef Modernization & Architectural Overhaul

## Architecture
ClinRef provides offline, point-of-care clinical evidence decision support powered by multiplatform UpToDate SQLite databases (2.1GB `utdasset.sqlite`, `unidex.en.sqlite`, `utdtoc.db`, `utdqf.sqlite`, `thumbs.db`).
Per explicit user directive (2026-10-18T14:11:42Z: "no fts search / it has poor quality"), all topic and clinical searches strictly use structured indexes (`unidex.en.sqlite` with stopword sanitization and token weighting) rather than full-text search (`fsearch.db` / FTS5).
The system operates across two symmetric platforms with strict algorithmic and data model parity:
1. **Kotlin Multiplatform (`:shared`)**: Kotlin Compose UI, Room DB, Bundled SQLite, JetBrains Koog autonomous agent state machine, Gemini SDK, and desktop JVM target.
2. **Python Prototype (`python_prototype/`)**: Python SQLite bindings, LangGraph state graph, Gemini / Mock LLM clients, and pytest integration test suites.

### Pipeline Data Flow:
```
User Clinical Query + Patient Profile
             │
             ▼
[Phase 1: Evidence Gathering & Tool Retrieval]
  ├── Compound Retrieval: getTopicOverview (outline hierarchy + calculator full markdown)
  └── Granular Retrieval: searchTopics (unidex), getTopicOverview (compound bundle), getTopicSectionsText, getGraphicContent, getRelatedTopics
             │
             ▼
[Phase 2: Clinical Synthesis]
  ├── Structured Clinical Reasoning Prompt (PICO/SOAP, patient contraindications, category outputs)
  └── Thinking/Reasoning Stream Management (buffer isolation, XML thought tag stripping)
             │
             ▼
[Phase 3: Hybrid Dual-Engine Safety Validation & Remediation]
  ├── Tier 1 (Deterministic Fast): Regex/AST quantities, units (g/mg/mcg, L/mL), patient bounds (eGFR/CrCl, allergies)
  ├── Tier 2 (Targeted Semantic): Contraindication guardrails, black-box warnings, evidence grounding
  └── In-Graph Remediation Loop: context-aware buildCorrectionPrompt, auto-populated citations (TopicRef, GraphicRef)
```

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Compound Topic Overview Retrieval | Bundled outline hierarchy + calculator full markdown in a single round-trip | M1 | R1, R2 |
| 2 | Non-FTS Structured Topic Retrieval | Multi-word topic lookup using `unidex.en.sqlite` and `utdasset.sqlite` without FTS; granular tools preserved | M1 | R2, Directive |
| 3 | Bidirectional Serialization & Schema Parity | Consistent argument names, aliases (`@JsonNames`), error envelopes (`{"error": ...}`), and return schemas across Kotlin and Python | M1 | R2 |
| 4 | 3-Phase State Machine Parity | Segregated operational phases (Evidence Gathering -> Clinical Synthesis -> Safety Verification & Remediation) with explicit premature interception in both Kotlin and Python | M1 | R1 |
| 5 | Streaming Reasoning & Thinking Token Hygiene | Handle reasoning deltas and thinking tokens cleanly via dedicated flows without leaking thoughts into chat text or breaking safety parsers | M1 | R1 |
| 6 | Structured Point-of-Care System Prompt | Enforce structured PICO/SOAP reasoning, explicit patient profile contraindication checking, and category clinical outputs | M2 | R3 |
| 7 | Context-Aware Remediation Prompt Builder | `buildCorrectionPrompt` directing models to call `getTopicSectionsText` (non-FTS) or rectify unverified quantities | M2 | R3 |
| 8 | Deterministic Tier 1 Safety Validation | Regex/AST dosage, unit conversion (g/mg/mcg, L/mL), numerical range verification, and programmatic patient profile bounds (eGFR < 30, documented allergies) | M2 | R4 |
| 9 | Targeted Tier 2 Semantic Grounding | Semantic guardrails for critical contraindications, black-box warnings, and ungrounded assertions | M2 | R4 |
| 10 | 100% Deterministic Citation Auto-Population | Auto-populate `TopicRef`, `GraphicRef`, and `ClinicalSource` from tool execution records without manual LLM bibliographies | M2 | R4 |
| 11 | Opaque-Box E2E Testing Suite | Multi-tier test suite (Tiers 1-4) verifying end-to-end clinical workflows across Kotlin and Python | E2E Track | AC |
| 12 | Final Integration & Adversarial Hardening | Pass 100% of E2E test suite + Tier 5 adversarial testing against live SQLite databases | M3 | AC |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| E2E | E2E Testing Track | Design test infra, runner, Tiers 1-4 test cases; publish TEST_READY.md | none | DONE |
| M1 | R1 & R2 Modernization: Pipeline, State Machine & Retrieval | LangGraph `intercept_premature` node, non-FTS retrieval compliance, compound overview alignment, dead code cleanup, streaming hygiene | none | DONE |
| M2 | R3 & R4 Modernization: Prompts, Remediation & Safety Engine | PICO/SOAP directives, Python patient context parity (allergies/notes/sex), non-FTS `buildCorrectionPrompt`, programmatic renal/allergy safety checks | M1 | DONE |
| M3 | Final Dual-Platform Integration & Gate Verification | 100% test pass (pytest 549/549 + desktopTest 303/303), 5-agent gate certification (2 Reviewers, 2 Challengers, 1 Forensic Auditor) with demonstrated-defect remediation | M1, M2 | DONE (2026-10-18) |

### M3 Gate Outcome (2026-10-18)
- **Forensic Auditor: CERTIFIED** — test counts verified (549 Python collected, 303 Kotlin JUnit), root-DB MD5s unchanged across integration runs, zero secret leaks in diff/untracked files, non-FTS directive compliance proven (chmod-000 `fsearch.db` probe + checksum pin).
- **Gate findings — all remediated and re-verified:**
  - (B1) Patient profile never wired into `TurnContext` → `SafetyValidator` Rule 4 dead in production on BOTH platforms. Fixed: `KoogAgentFactory` → `accumulator.setPatientProfile()`, Python `create_initial_state(patient_profile=…)` + interactive collector in `run.py`. Wired-through integration tests added on both platforms.
  - (V1) Python `search_topics`/`search_topic_sections` silently dropped `q`/`keyword` aliases (empty results). Fixed + alias battery extended.
  - (V2) Python `get_graphic_content` missing `graphic_`/`graphic_table_`/`Graphic-` prefix normalization + raw-ID fallback (Kotlin-only). Fixed to mirror Kotlin.
  - (V4) Python overview used `isTable` while parser emits `is_table` → non-table figures leaked into compound retrieval. Fixed; `isTable` hybrid key emitted for parity.
  - (V5) Python `GraphicRef` order was hash-seed nondeterministic (`set` iteration). Fixed with insertion-ordered list (cf. Kotlin `CopyOnWriteArraySet`) + insertion-order test.
  - (V6) Python tracking ignored singular `section_id`/`sectionId` argument → citations silently dropped vs Kotlin. Fixed.
  - (M1) Four adversarial "tests" were assertion-free (always green) and one conditional assertion; converted to real assertions — which immediately exposed a genuine Kotlin bug: section title resolution did not strip `•`/`●` bullet prefixes (Python parity already did). Fixed in `MedicalDatabaseTools.kt` against `LEADING_BULLET_OR_DASH_REGEX`.
  - (M2) Remediation routing coupled to human-readable `blockedReason` substrings. Fixed: routing keyed on evidence state (`fetchedSections`/`graphicIds` empty), strings remain only in prompts (Python-parity).
  - Hardening: decimal-comma uncomma guard (`1,5 mg` can no longer false-verify against `15 mg` — 10x escape on both platforms), `known` added to allergy noise tokens, Python conversational regex parity, Kotlin `formatDouble` no longer emits scientific notation (`5.0E-4`), `getTopicSectionsText` not-found envelope now includes the topic ID.

### Known Divergences & Follow-Ups (non-blocking, documented)
- Kotlin wire emits camelCase primary keys (`@JsonNames` aliases are decode-only, snake_case CSV keys are not emitted on the Kotlin wire); Python emits dual camel+snake keys. Input robustness identical; output parity asymmetry accepted.
- Python `search_topic_sections` returns `matches`/`message` for a missing topic (recorded as tool success) where Kotlin returns `{"error": "Topic not found: X"}` (tool failure). Converged terminal outcomes; envelope divergence documented here.
- Premature-interception on "tools ran but failed to produce evidence": Kotlin intercepts as premature; Python routes via Rule 1 missing-sections correction. Same terminal block, different counters/route — deliberate.
- Python `get_topic_overview` section entries omit `parentId`; Python payload carries duplicate camel+snake keys; both are additive and harmless.
- `RemediationTarget` enum currently only labels the log line (both targets loop through `nodeRemediate` → `nodeSendInput`); type it into routing if remediation paths ever diverge.
- `StreamingManager` reasoning-buffer StringBuilders live on the agent handler thread with Main-thread `reset()` — pre-existing pattern; harden before exposing `reasoningText`/`isReasoning` in Compose UI. Orphaned `liveDiscoveredSources` machinery has no consumers post-Ch- refactor — cleanup candidate.

## Interface Contracts
### Compound Retrieval (`getTopicOverview` / `get_topic_overview`)
- Input: `topic_id: String` / `topicId: String` (both accepted on both platforms)
- Output (Python emits dual camel+snake keys; Kotlin emits camelCase primary keys, snake_case accepted on input via `@JsonNames`):
  ```json
  {
    "topicId": "...", "title": "...", "topicType": "article|calc",
    "sections": [
      {"id": "...", "title": "...", "level": 1, "parentId": "...|nulled"}
    ],
    "graphics": [
      {"id": "...", "title": "...", "isTable": true}
    ],
    "relatedTopics": [{"id": "...", "title": "..."}],
    "markdown": "<calculator full markdown when topicType=calc, else empty>"
  }
  ```
  - `graphics` contains **table graphics only** (`graphic_table`/`table` subtype; figures/algorithms/photographs excluded).
  - Calculator topics (`topicType = "calc"`) return `sections = [{"id": "FULL", ...}]` plus the full calculator markdown in `markdown`.
- Error: `{"error": "Topic not found: <id>"}`

### Patient Profile & Safety Evaluation
- Schema:
  - `age: String`
  - `sex: String` (standardized across Kotlin & Python)
  - `conditions: List<String>`
  - `medications: List<String>`
  - `allergies: List<String>`
  - `notes: String`
- Tier 1 Bounds:
  - Renal: If eGFR or CrCl < 30 mL/min, flags contraindicated dosage/medication thresholds.
  - Allergies: Direct cross-reference of documented patient allergies against recommended drug classes.
- Tier 2 Guardrails:
  - Black-box warnings and critical contraindications checked against retrieved section text.

### State Machine Operational Phase Contracts
- **Phase 1: Evidence Gathering**: Model has access to all clinical database tools. Cannot transition to Phase 2 until `turnContext.fetchedSections.isNotEmpty()` (unless purely conversational/greeting).
- **Phase 2: Clinical Synthesis**: Model generates synthesized clinical response adhering to PICO/SOAP and category layout. Thinking tokens isolated to `reasoningText`.
- **Phase 3: Safety Verification & Remediation**:
  - Deterministic Tier 1 + Semantic Tier 2.
  - If PASSED: auto-populate `TopicRef`/`GraphicRef`/`ClinicalSource`, emit final clinical message.
  - If FAILED and `retries < max_retries`: inject context-aware correction prompt, route back to Phase 1 (if missing sections) or Phase 2 (if unverified figures).
  - If FAILED and `retries >= max_retries`: emit safe blocked clinical response.

## Code Layout
- Kotlin Multiplatform:
  - Tools: `shared/src/commonMain/kotlin/com/clinref/app/data/tools/ClinicalTools.kt`, `MedicalDatabaseTools.kt`
  - Koog Strategy: `shared/src/commonMain/kotlin/com/clinref/app/domain/ai/ClinicalAgentStrategy.kt`, `KoogAgentFactory.kt`
  - Streaming: `shared/src/commonMain/kotlin/com/clinref/app/domain/ai/StreamingManager.kt`
  - Safety: `shared/src/commonMain/kotlin/com/clinref/app/domain/ai/SafetyValidator.kt`, `PatientProfile.kt`
  - System Prompts: `shared/src/commonMain/kotlin/com/clinref/app/domain/ai/SystemPrompt.kt`
  - Citations: `shared/src/commonMain/kotlin/com/clinref/app/domain/ai/ClinicalSource.kt`, `TurnContextAccumulator.kt`
  - Tests: `shared/src/commonTest/kotlin/...`
- Python Prototype:
  - Tools: `python_prototype/tools.py`, `database.py`
  - LangGraph: `python_prototype/agent.py`, `run.py`
  - Safety: `python_prototype/safety_validator.py`
  - System Prompts: `python_prototype/system_prompt.py`
  - Citations: `python_prototype/clinical_source.py`
  - Tests: `python_prototype/test_clinref.py`, `test_integration.py`
