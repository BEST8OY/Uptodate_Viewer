# ClinRef Test Infrastructure & Verification Specification

## 1. Test Philosophy

### 1.1 Opaque-Box & Requirement-Driven Architecture
ClinRef's test infrastructure operates on an **opaque-box, requirement-driven philosophy**. The system is treated as a deterministic clinical reasoning and evidence retrieval pipeline. Tests interface exclusively through documented public contracts:
- Public tool invocations and structured JSON argument/response schemas.
- Agent graph inputs (`HumanMessage`, user query text, patient profile) and outputs (`AIMessage`, auto-populated citations, safety verification envelopes).
- In-graph self-healing remediation loops triggered by verifiable clinical validation failures.

Internal transient implementations (e.g., private regex tokens, internal intermediate AST structures, or temporary buffer pointers) are tested through their observable effects on clinical output fidelity, unit conversion accuracy, and citation integrity.

### 1.2 Verification Against Real UpToDate SQLite Databases
All integration and end-to-end retrieval tests execute against the full production UpToDate SQLite database suite located in the project root:
- `utdasset.sqlite` (2.1 GB): Production compressed topic and graphic assets (`topic_asset`, `graphic_asset`) using zstandard dictionary/payload compression.
- `unidex.en.sqlite` (96 MB): Curated medical keyword index, search query synonyms, topic hit hex blobs.
- `fsearch.db` (13 MB): SQLite FTS4 full-text search index over clinical topic titles and contents.
- `utdtoc.db` (21 MB): Table of contents hierarchy (`TOC`, `TOCMap`).
- `utdqf.sqlite` (16 MB): Quick-find prefix autocomplete database.
- `thumbs.db` (206 MB): Medical graphic and table thumbnail binary blobs.

**Database Invariant**: All root SQLite databases are strictly **read-only** and must never be mutated during test execution.

### 1.3 Zero-External-Dependency & Deterministic Test Execution
Test suites must run hermetically without external API calls (e.g., Gemini, OpenAI, Anthropic) or internet access:
- **Mock & Scripted LLM Drivers**: Multi-turn agent conversations, tool-selection sequences, and clinical answers are orchestrated via deterministic scripted mock engines (`ScriptedMockLLM` in Python, mock executors / state harnesses in Kotlin).
- **Forensic Determinism**: Any dosage extraction, unit conversion, patient contraindication, or remediation test must yield 100% reproducible results on every run.

### 1.4 Strict Cross-Platform Parity
ClinRef maintains strict algorithmic and schema parity across its two platforms:
1. **Kotlin Multiplatform (`:shared`)**: Android & Desktop JVM target using JetBrains Koog autonomous agent graph, Room DB, Bundled SQLite, and Compose Multiplatform.
2. **Python Prototype (`python_prototype/`)**: Python SQLite bindings, LangGraph state machine, Pydantic domain models, and pytest.

Any behavioral rule, metric conversion formula, patient boundary threshold, or error envelope defined in one platform must have an identical counterpart in the other.

---

## 2. Feature Inventory Test Matrix (Features 1–13)

| Feature # | Feature Name | Tier | Primary Test Files | Core Verification Invariants |
|---|---|---|---|---|
| **F1** | Compound Topic Overview Retrieval | Tier 1, 4 | `test_e2e.py`<br>`CompoundRetrievalE2ETest.kt` | `getTopicOverview` / `get_topic_overview` returns bundled outline + pre-fetched high-yield sections (`Summary & Recommendations`, `Dosing`, `Management`) in 1 round-trip; falls back gracefully for calculators (`topicType = "calc"`). |
| **F2** | Granular Tool Preservation | Tier 1, 4 | `test_integration.py`<br>`MedicalDatabaseToolsTest.kt` | Preserves the strict 6-tool contract (`getTopicOverview` bundle + 5 granular: `searchTopics`, `getTopicSectionsText`, `getGraphicContent`, `searchTopicSections`, `getRelatedTopics`); smart section title matching; non-table graphic rejection. |
| **F3** | Bidirectional Serialization & Schema Parity | Tier 1, 4 | `test_clinref.py`<br>`MedicalDatabaseToolsTest.kt`<br>`CompoundRetrievalE2ETest.kt` | Exact parameter naming and `@JsonNames` aliases (`topic_id` vs `topicId`, `section_ids` vs `sectionIds`); uniform JSON error envelope `{"error": "..."}`; no double-escaping or field dropping. |
| **F4** | 3-Phase State Machine (Koog & LangGraph) | Tier 3, 4 | `test_e2e.py`<br>`KoogAgentStrategyE2ETest.kt` | Structured phases: Evidence Gathering & Tool Retrieval -> Clinical Synthesis -> In-Graph Safety Verification & Remediation. Autonomous transitions without external loop intervention. |
| **F5** | Premature Synthesis Prevention | Tier 3, 4 | `test_e2e.py`<br>`KoogAgentStrategyE2ETest.kt` | Intercepts clinical claims generated before tool retrieval; forces evidence retrieval before synthesis on non-conversational queries (Rule 0). |
| **F6** | Streaming Reasoning & Thinking Token Hygiene | Tier 3, 4 | `test_clinref.py`<br>`AdversarialRemedyTest.kt`<br>`KoogAgentStrategyE2ETest.kt` | Isolates reasoning tokens to dedicated flows; strips XML `<thought>` tags; ensures thinking tokens do not leak into chat text or trigger false-positive number validation failures. |
| **F7** | Deterministic Tier 1 Safety Validation | Tier 1, 4 | `test_clinref.py`<br>`SafetyValidatorTest.kt`<br>`SafetyValidationE2ETest.kt` | Regex/AST dosage extraction; unit conversions (g/mg/mcg, L/mL); numerical range checking; patient profile bounds (eGFR/CrCl < 30 mL/min, documented allergy cross-referencing). |
| **F8** | Targeted Tier 2 Semantic Grounding | Tier 2, 4 | `test_clinref.py`<br>`SafetyValidatorTest.kt`<br>`SafetyValidationE2ETest.kt` | Guardrails for critical contraindications, black-box warnings, and ungrounded clinical assertions against retrieved section markdown. |
| **F9** | 100% Deterministic Citation Auto-Population | Tier 2, 4 | `test_clinref.py`<br>`TurnContextAccumulatorTest.kt`<br>`ClinicalInquiryE2ETest.kt` | `TopicRef`, `GraphicRef`, and `ClinicalSource` automatically extracted from successful tool execution records; parent topics attributed for graphic tables; calculator FULL section filtered. |
| **F10** | Structured Point-of-Care System Prompt | Tier 3, 4 | `test_clinref.py`<br>`ClinicalAgentStrategyTest.kt` | System prompts enforce structured PICO/SOAP problem formulation, explicit patient contraindication cross-referencing, and concise category-based clinical outputs. |
| **F11** | Context-Aware Remediation Prompt Builder | Tier 3, 4 | `test_clinref.py`<br>`ClinicalAgentStrategyTest.kt`<br>`ClinicalInquiryE2ETest.kt` | `buildCorrectionPrompt` produces actionable, context-aware remediation instructions directing the model to retrieve missing sections or rectify unverified quantities. |
| **F12** | Opaque-Box E2E Testing Suite | Tier 4 | `test_e2e.py`<br>`ClinicalInquiryE2ETest.kt`<br>`CompoundRetrievalE2ETest.kt` | Complete end-to-end clinical workflows from query to verified answer across multi-turn tool loops, patient profile checks, and citations. |
| **F13** | Final Integration & Adversarial Hardening | Tier 5 | `test_integration.py`<br>`AdversarialStressTest.kt`<br>`AdversarialRemedyTest.kt` | Extreme inputs, special characters, malformed JSON, SQL injection probes, read-only DB integrity, and rapid multi-turn stress testing. |

---

## 3. Testing Methodology

### 3.1 Category-Partitioning
Input parameters and execution contexts are partitioned into discrete equivalence classes:

1. **Clinical Query Categories**:
   - `C1_SINGLE`: Single-concept query (e.g., `"apixaban dosing"`, `"gout flare"`).
   - `C2_MULTI`: Multi-concept query requiring cross-topic retrieval (e.g., `"atrial fibrillation with severe renal impairment"`).
   - `C3_CALC`: Medical calculator lookup (e.g., `"cha2ds2-vasc score"`, `"curb-65"`).
   - `C4_CONV`: Conversational / greeting query (e.g., `"hello"`, `"who are you"`).
   - `C5_INVALID`: Non-existent medical entity or nonsense string.

2. **Retrieval Tool Execution Paths**:
   - `R1_COMPOUND`: High-yield compound retrieval (`getTopicOverview`) returning outline + sections in 1 round trip.
   - `R2_GRANULAR`: Multi-step granular retrieval (`searchTopics` -> `getTopicOverview` -> `getTopicSectionsText`).
   - `R3_TABLE`: Graphic table retrieval (`getGraphicContent`) for tabular dosing matrices.
   - `R4_RETRY`: Search query auto-retry through unidex suggestion candidates.

3. **Dosage & Quantitative Formats**:
   - `D1_EXACT`: Exact integer/decimal with unit (e.g., `5 mg`, `0.5 mg`, `100 mL`).
   - `D2_RANGE`: Numerical range with unit (e.g., `5 to 10 mg`, `10-20 mg`).
   - `D3_PREFIX`: Metric prefix conversions (`1000 mg` = `1 g`, `500 mcg` = `0.5 mg`, `2500 mL` = `2.5 L`).
   - `D4_INVENTED`: Quantity present in synthesis but absent from retrieved evidence and user question.

4. **Patient Profile Attributes**:
   - `P1_NORMAL`: Young/adult patient with normal renal function (eGFR > 60 mL/min), no allergies.
   - `P2_RENAL_SEVERE`: Patient with severe renal impairment (eGFR/CrCl < 30 mL/min).
   - `P3_ALLERGIC`: Patient with documented drug allergies (e.g., `"penicillin"`, `"sulfa"`).
   - `P4_POLYPHARMACY`: Patient with concurrent interacting medications.

### 3.2 Boundary Value Analysis (BVA)
Tests probe exact critical thresholds where clinical behavior shifts:

1. **Renal Clearance Boundaries**:
   - `eGFR = 31 mL/min` (Mild/moderate: standard dose permitted).
   - `eGFR = 30 mL/min` (Exact boundary cutoff).
   - `eGFR = 29 mL/min` (Severe: triggers mandatory dose reduction or contraindication flag).

2. **Dosage & Metric Conversions**:
   - Zero dosage: `0 mg`, `0.0 mL`.
   - Small fractions: `0.025 mg`, `0.125 mg`.
   - Magnitude equivalence: `1000 mcg == 1 mg`, `1000 mg == 1 g`, `1000 mL == 1 L`.
   - Non-equivalent near-misses: `50 mg` claimed when evidence states `500 mg` (must be blocked).

3. **Graph Loop Retries**:
   - `retry_count = 0` (First attempt).
   - `retry_count = 1` (First remediation turn).
   - `retry_count = 2` (Max retries reached -> terminal safety block emitted).

4. **Input Boundaries**:
   - Empty string `""` and whitespace-only `"   "`.
   - Giant string (10,000+ characters).
   - Meta-characters and HTML tags (`<script>`, `&quot;`, `'`, `|`, `\n`).

### 3.3 Real-World Point-of-Care Clinical Scenarios

#### Scenario 1: Nonvalvular Atrial Fibrillation Anticoagulation (Apixaban)
- **Profile**: 78-year-old male, Serum Creatinine 2.1 mg/dL, weight 58 kg (meets dose reduction criteria).
- **Flow**: Query -> `get_topic_overview("128998")` -> evidence retrieved with standard (5 mg BID) and reduced (2.5 mg BID) dosing -> model proposes 2.5 mg BID -> Tier 1 verifies quantity and renal appropriateness -> citations auto-populated -> Response emitted.

#### Scenario 2: Gout Flare in Severe Chronic Kidney Disease
- **Profile**: 65-year-old female, eGFR 22 mL/min (severe CKD).
- **Flow**: Query -> model attempts to recommend standard NSAID (e.g., Indomethacin 50 mg TID) -> Tier 1 / Tier 2 flags renal contraindication -> remediation loop triggered via `buildCorrectionPrompt` -> model self-corrects to intra-articular glucocorticoids or dose-adjusted colchicine -> Safety verified.

#### Scenario 3: Acute Bacterial Pharyngitis with Penicillin Allergy
- **Profile**: 24-year-old male, documented allergy: `"penicillin (anaphylaxis)"`.
- **Flow**: Query -> model initially drafts Amoxicillin -> Tier 1 cross-references allergy list -> blocks recommendation -> remediation injects allergy alert -> model self-corrects to Azithromycin or Cephalosporin (if non-anaphylactic) -> Safety passed.

#### Scenario 4: Medical Calculator Full Body Retrieval
- **Topic**: Calculator topic (e.g. `148929` / CHA2DS2-VASc).
- **Flow**: `getTopicOverview` returns `topicType = "calc"` and `sections = [{"id": "FULL"}]` -> `getTopicSectionsText("FULL")` returns complete calculator markdown -> synthesis without invented numbers -> citation auto-populated without invalid sub-sections.

#### Scenario 5: Multi-Turn Self-Correction Remediation Loop
- **Flow**: Model invents ungrounded figure (`"cure rate of 99.4%"`) -> `SafetyValidator` fails Rule 2 (invented numbers) -> graph routes to `buildCorrectionPrompt` -> correction prompt feeds exact violation and prior evidence -> model rectifies response -> validation passes -> final answer output.

---

## 4. Test Suite Architecture & Directory Layout

```
Uptodate_Viewer/
├── TEST_INFRA.md                          # Test infrastructure specification (this document)
├── TEST_READY.md                          # Test readiness certificate and feature verification summary
├── python_prototype/                      # Python prototype test suite
│   ├── test_clinref.py                    # Unit tests for validator, parser, tools, agent (150 tests)
│   ├── test_integration.py                # Database integration tests against root SQLite DBs (58 tests)
│   ├── test_e2e.py                        # Opaque-box E2E clinical inquiry, compound retrieval & safety tests
│   ├── test_gate2_adversarial.py          # Gate 2 hardening: SQL injection, arg-alias, non-FTS & DB-integrity batteries (156 tests)
│   ├── test_m2_adversarial.py             # Milestone 2 adversarial prompts, profile bounds & remediation tests (63 tests)
│   ├── test_adversarial_m1.py             # Milestone 1 adversarial retrieval & state machine tests (28 tests)
│   ├── test_challenger_m1_iter2.py        # 6-tool preservation, alias & smart-resolution cascade tests
│   ├── test_challenger_m1_adversarial.py  # Cross-platform alias risk probes (21 tests)
│   ├── reasoning_sanitizer.py             # Pure reasoning/thinking token sanitizer (parity with Kotlin)
│   ├── harness_challenger_m1.py           # Standalone SQL-injection challenge harness (gate evidence)
└── shared/src/commonTest/kotlin/          # Kotlin Multiplatform test suite (:shared)
    └── com/clinref/app/
        ├── data/
        │   ├── MedicalDatabaseToolsTest.kt# Granular tool execution & JSON schema tests
        │   ├── AdversarialCascadeTest.kt   # Smart-resolution cascades (incl. bullet prefixes) & partial truncation
        │   └── AssetRepositoryTest.kt      # Asset dao and payload decompression tests
        ├── domain/ai/
        │   ├── ClinicalAgentStrategyTest.kt# Koog autonomous strategy graph node/edge tests
        │   ├── SafetyValidatorTest.kt      # Rules 0-4 quantitative & patient-bound validation tests
        │   ├── TurnContextAccumulatorTest.kt# Tool call tracking, citation auto-population & profile wiring tests
        │   ├── ReasoningTokenSanitizerTest.kt # Thinking-token leak/strip parity tests
        │   └── StreamingManagerTest.kt     # Fragmented tag demux & streaming buffer hygiene tests
        ├── adversarial/
        │   ├── AdversarialCompoundRetrievalTest.kt # Injection/emoji/unicode/IDs fuzz battery
        │   ├── AdversarialPrematureInterceptionTest.kt # Rule 0 interception & greeting bypass
        │   ├── AdversarialRenalBoundaryTest.kt # eGFR/CrCl boundary BVA (29/30/31)
        │   ├── AllergyPromptAdversarialTest.kt # Cross-reactivity & punctuation allergen battery
        │   ├── AdversarialRemedyTest.kt    # State restoration and lifecycle audit tests
        │   └── AdversarialStressTest.kt    # Stress, fuzzing, and boundary condition tests
        └── e2e/                           # Kotlin Multiplatform E2E Test Suite
            ├── ClinicalInquiryE2ETest.kt  # Full inquiry flows, self-correction, citations
            ├── CompoundRetrievalE2ETest.kt# Compound retrieval contracts & section resolution
            ├── SafetyValidationE2ETest.kt # Tier 1 quantitative & Tier 2 semantic safety
            └── KoogAgentStrategyE2ETest.kt# Koog state machine transitions & streaming hygiene
```

---

## 5. Test Runner & Verification Instructions

### 5.1 Python Prototype Test Suite
The Python test suite uses `pytest` managed by `uv`:

```bash
# Run all Python tests (unit, integration, and E2E)
cd python_prototype && uv run pytest -v

# Run specifically the E2E inquiry and compound retrieval suite
cd python_prototype && uv run pytest test_e2e.py -v

# Run database integration tests against root SQLite databases
cd python_prototype && uv run pytest test_integration.py -v
```

### 5.2 Kotlin Multiplatform Desktop JVM Test Suite
The Kotlin Multiplatform test suite runs via Gradle Desktop JVM test tasks (no Android SDK required):

```bash
# Run all shared multiplatform desktop tests with full stacktrace
./gradlew :shared:desktopTest --stacktrace

# Run specific E2E test classes
./gradlew :shared:desktopTest --tests "com.clinref.app.e2e.*" --stacktrace

# Verify clean compilation of desktop and shared targets
./gradlew :desktopApp:compileKotlin :shared:compileKotlinDesktop --console=plain
```

### 5.3 CI/CD Quality Gates & Exit Criteria
All verification runs must meet the following non-negotiable exit criteria:
1. `uv run pytest` exits with code 0 (100% pass rate, zero failures, zero errors).
2. `./gradlew :shared:desktopTest` exits with code 0 (100% pass rate across all suites).
3. Root SQLite database checksums remain completely unmodified after test execution.
4. Zero unhandled API key leaks or hardcoded credentials.
