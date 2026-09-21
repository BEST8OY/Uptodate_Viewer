# ClinRef — Agent Instructions

## Critical Execution Rules
- **NEVER run `./gradlew` or Gradle commands**: There is no Android SDK on this machine.
- **Do NOT commit `python_prototype/.env`**: Contains local API keys.
- **Only run `pytest` when modifying real Python code in `python_prototype/`**: Do NOT run `pytest` unconditionally. Specifically, NEVER run `pytest` for Kotlin-only changes, Android UI/Compose changes, XML resources, Gradle configs, Room DAOs, documentation, or code audits.

## Project Structure & Architecture
- **Android App (`:app`)**: Package `com.clinref.app` using Compose M3, Hilt DI, Navigation 3, Room 3 (`androidx.room3`).
- **Python Prototype (`python_prototype/`)**: LangGraph/LangChain agent in Python 3.13 (`uv`). Mirrors Kotlin agent logic 1:1.
- **SQLite DBs (Project Root)**: 6 SQLite databases (`utdasset.sqlite`, `unidex.en.sqlite`, `fsearch.db`, `utdqf.sqlite`, `utdtoc.db`, `thumbs.db`) read directly by the search/agent pipeline.

## Verification & Commands

### Python Prototype (`python_prototype/`)
> **Important**: Run these commands **ONLY** when you have modified `.py` files inside `python_prototype/`.
```bash
uv run pytest test_clinref.py        # Run 90 unit tests (ONLY when python_prototype/ code was modified)
uv run pytest test_integration.py   # Run 49 integration tests against root SQLite DBs (ONLY when python_prototype/ code was modified)
uv run python run.py                 # Run interactive CLI agent
```

### Kotlin Unit Tests (`app/src/test/java/com/clinref/app/`)
- `data/MedicalDatabaseToolsTest.kt`: Speculative outline bundling, link parsing, footnote bracket removal, graphic table markdown.
- `domain/ai/SafetyValidatorTest.kt`: Intent-aware rules, compound slashed units (`5 u/x`, `0.5 mcg/kg/min`), thousand-separator comma normalization (`1,200 mg` vs `1200 mg`).
- `domain/ai/TurnContextAccumulatorTest.kt`: Tool call deduplication, outline title dash stripping (`-Antiplatelet` -> `Antiplatelet`), ref auto-population.

## Architectural Parity & Design Precedence (Kotlin & Python)
> **Design Precedence**: `python_prototype/` serves as an algorithm verification testbed and reference implementation. Algorithmic tool flow, safety regexes, and validation criteria should align, but **architectural parity must NEVER compromise idiomatic Kotlin, Jetpack Compose M3, Room 3, or Hilt architecture**. When superior Android UX or cleaner software engineering warrants divergence (e.g., rich UI models, navigation scoping, native DI), prioritize Kotlin/Android architecture.

1. **Pure 3-Stage Pipeline**: `searchTopics` / `search_topics` returns candidate topics `[{id, title}]`; `getTopicOutline` / `get_topic_outline` retrieves topic outline (sections, table graphics, `topicType`).
2. **Intent-Aware Safety Validation**: Non-clinical greetings bypass tool requirements. Answers containing clinical numbers/units (`CLINICAL_QUANTITY_REGEX`) require tool verification; unverified quantities trigger a hard block for self-correction.
3. **Number & Quantity Normalization**: Range bounds (`5-10 mg`) and thousand separators (`1,200 mg` vs `1200 mg`) are normalized during text verification. Structural numbers (section IDs, years, list steps) are exempted.
4. **HTML & Link Sanitization**: `javascript:appAction(...)` JSON link payloads convert to `[Text](Topic-ID)` / `[Text](Graphic-ID)`. Footnote citation brackets (`[1]`, `[1, 2]`) are stripped.
5. **Reference Navigation Intent**: In `ClinicalReferencesSection`, tapping the parent topic card navigates to the root of the article (`sectionId = null`), while tapping a sub-section chip navigates to that specific section (`sectionId = sec.sectionId`).

## Known Quirks
- **Koog Duplicate Classes**: `utils-android` must remain excluded in `app/build.gradle.kts` (line 130); `utils-jvm` is used instead.
- **ProGuard**: Keep rules in `app/proguard-rules.pro` for Koog, Room3, Hilt, serialization, and Compose must not be trimmed.
- **KMP AGP 9 Setup**: `:shared` uses `com.android.kotlin.multiplatform.library` plugin with `withHostTest { }` inside `kotlin { android { ... } }`. Source sets (`commonMain`, `commonTest`, `androidMain`, `androidHostTest`) use explicit KMP DSL accessors.
- **Navigation 3 Top-Level Peer Roots**: Bottom navigation top-level routes MUST be independent roots in `toDecoratedEntries` (`getTopLevelRoutesInUse() = listOf(topLevelRoute)`). NEVER stack `listOf(startRoute, topLevelRoute)` to implement "Exit through Home" — this corrupts `NavDisplay`'s internal scene state, z-index calculation, and predictive back targeting across multi-tab transitions. Instead, handle "Exit through Home" via an explicit top-level `BackHandler` (`enabled = !isOnOverlayScreen && topLevelRoute != startRoute`).
- **Koog Tool Result Double-Encoding**: In Koog, `eventContext.toolResult` for string-returning tools is a `JSONPrimitive`. Calling `.toString()` outputs double-encoded, escaped JSON strings (`"\"{\\\"key\\\":...}\""`). Deserializing tool results or args in Kotlin must unbox string primitives (e.g., via `extractJsonString` / `parseAsJsonObject`) before attempting `as? JsonObject` to avoid silent `null` deserialization failures.

