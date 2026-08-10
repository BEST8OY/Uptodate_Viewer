# ClinRef — Agent Instructions

## Critical Execution Rules
- **NEVER run `./gradlew` or Gradle commands**: There is no Android SDK on this machine.
- **Do NOT commit `python_prototype/.env`**: Contains local API keys.
- **Only run `pytest` when modifying `python_prototype/`**: Do NOT run `pytest` for Kotlin-only, Android UI, or Room DAO changes.

## Project Structure & Architecture
- **Android App (`:app`)**: Package `com.clinref.app` using Compose M3, Hilt DI, Navigation 3, Room 3 (`androidx.room3`).
- **Python Prototype (`python_prototype/`)**: LangGraph/LangChain agent in Python 3.13 (`uv`). Mirrors Kotlin agent logic 1:1.
- **SQLite DBs (Project Root)**: 6 SQLite databases (`utdasset.sqlite`, `unidex.en.sqlite`, `fsearch.db`, `utdqf.sqlite`, `utdtoc.db`, `thumbs.db`) read directly by the search/agent pipeline.

## Verification & Commands

### Python Prototype (`python_prototype/`)
```bash
uv run pytest test_clinref.py        # Run 90 unit tests
uv run pytest test_integration.py   # Run 49 integration tests against root SQLite DBs
uv run python run.py                 # Run interactive CLI agent
```

### Kotlin Unit Tests (`app/src/test/java/com/clinref/app/`)
- `data/MedicalDatabaseToolsTest.kt`: Speculative outline bundling, link parsing, footnote bracket removal, graphic table markdown.
- `domain/ai/SafetyValidatorTest.kt`: Intent-aware rules, compound slashed units (`5 u/x`, `0.5 mcg/kg/min`), thousand-separator comma normalization (`1,200 mg` vs `1200 mg`).
- `domain/ai/TurnContextAccumulatorTest.kt`: Tool call deduplication, outline title dash stripping (`-Antiplatelet` -> `Antiplatelet`), ref auto-population.

## Architectural Parity Rules (Kotlin & Python)
1. **Pure 3-Stage Pipeline**: `searchTopics` / `search_topics` returns candidate topics `[{id, title}]`; `getTopicOutline` / `get_topic_outline` retrieves topic outline (sections, table graphics, `topicType`).
2. **Intent-Aware Safety Validation**: Non-clinical greetings bypass tool requirements. Answers containing clinical numbers/units (`CLINICAL_QUANTITY_REGEX`) require tool verification; unverified quantities trigger a hard block for self-correction.
3. **Number & Quantity Normalization**: Range bounds (`5-10 mg`) and thousand separators (`1,200 mg` vs `1200 mg`) are normalized during text verification. Structural numbers (section IDs, years, list steps) are exempted.
4. **HTML & Link Sanitization**: `javascript:appAction(...)` JSON link payloads convert to `[Text](Topic-ID)` / `[Text](Graphic-ID)`. Footnote citation brackets (`[1]`, `[1, 2]`) are stripped.

## Known Quirks
- **Koog Duplicate Classes**: `utils-android` must remain excluded in `app/build.gradle.kts` (line 130); `utils-jvm` is used instead.
- **ProGuard**: Keep rules in `app/proguard-rules.pro` for Koog, Room3, Hilt, serialization, and Compose must not be trimmed.
- **KMP AGP 9 Setup**: `:shared` uses `com.android.kotlin.multiplatform.library` plugin with `withHostTest { }` inside `kotlin { android { ... } }`. Source sets (`commonMain`, `commonTest`, `androidMain`, `androidUnitTest`) use explicit KMP DSL accessors.

