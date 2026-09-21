# AI Domain Layer — Architectural Modernization & Breaking Improvements

This document tracks the prioritized execution of architectural improvements across the ClinRef AI pipeline (Kotlin `:app` and `python_prototype/`), eliminating legacy workarounds, boundary duality, repetitive regex parsing, and fragile terminal tool requirements.

---

## Todo List & Execution Plan

- [x] **Task 1: Standardized Boundary Normalization (Canonical Tool & Argument Normalizer)**
  - Implemented `AiJsonUtils.normalizeToolName(rawName: String): String` converting snake_case and camelCase tool names (`get_topic_outline` -> `getTopicOutline`) at the boundary.
  - Implemented `AiJsonUtils.normalizeArgs(rawArgs: Map<String, String>): Map<String, String>` mapping `topic_id` -> `topicId`, `section_ids` -> `sectionIds`, `graphic_id` -> `graphicId`.
  - Streamlined downstream dispatch in `TurnContextAccumulator` and `StreamingManager` to use canonical names and keys.

- [x] **Task 2: Fast In-Memory Section Title & Outline Caching (O(1) Title Resolution)**
  - Added thread-safe `ConcurrentHashMap` section title cache in `ContentRepository`.
  - Single-pass outline parsing into in-memory maps eliminates redundant regex scraping and DB queries on repeat section lookups.

- [x] **Task 3: Complete Elimination of `submitClinicalAnswer` & Natural LLM Generation (Eliminated Escaped JSON Fragility)**
  - Completely eliminated `submitClinicalAnswer` and `submit_clinical_answer` across both Kotlin (`:app`) and Python prototype (`python_prototype/`).
  - Streamlined tool suite to 5 pure database retrieval tools (`searchTopics`, `getTopicOutline`, `getRelatedTopics`, `getTopicSectionsText`, `getGraphicContent`).
  - LLMs now respond directly in pure, natural Markdown. Citations and reference cards are automatically populated from accumulated tool evidence.
  - Eliminated escaped JSON parsing bugs, double-encoding issues in Koog, and unnecessary prompt token overhead.

- [x] **Task 4: Unified Clinical Citation Hierarchy (`ClinicalSource`)**
  - Defined unified domain hierarchy in `ClinicalSource.kt` (`ClinicalSource.Article` and `ClinicalSource.Table`) with bidirectional conversions to `TopicRef`/`GraphicRef` and `ResolvedTopicRef`/`ResolvedGraphicRef`.
  - Added Python counterpart in `clinical_source.py` (`ClinicalArticle`, `ClinicalTable`, grouping functions).

- [x] **Task 5: Remediation State Machine Hardening**
  - Integrated `accumulator.prepareForCorrection()` into `ClinicalAgentStrategy` and state reset into `agent.py`, isolating failed turns while preserving accumulated evidence.

- [x] **Task 6: Verification & Test Suite Execution**
  - All 101 Python unit tests in `test_clinref.py` passed.
  - All 51 Python integration tests in `test_integration.py` against root SQLite DBs passed.
  - Added and verified Kotlin unit tests in `TurnContextAccumulatorTest.kt` for canonical boundary normalization, argument normalization, and `ClinicalSource` conversions.
