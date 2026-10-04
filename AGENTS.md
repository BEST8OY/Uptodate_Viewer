# ClinRef — Agent Instructions

## Critical Execution Rules
- **No Android SDK on this machine**: NEVER run Android-specific Gradle tasks (e.g., `./gradlew assembleDebug`, `./gradlew :androidApp:...`, Android instrumented tests, or Android emulator/device tasks).
- **Desktop/JVM Gradle Tasks ARE Permitted**: You MAY run Desktop JVM tasks for `:shared` (e.g., `./gradlew :shared:compileKotlinDesktop`, `./gradlew :shared:desktopTest`) since they compile and run on standard JVM 17 without requiring the Android SDK.
- **Do NOT commit `python_prototype/.env`**: Contains local API keys.
- **Only run `pytest` when modifying real Python code in `python_prototype/`**: Do NOT run `pytest` unconditionally. Specifically, NEVER run `pytest` for Kotlin-only changes, UI/Compose changes, XML resources, Gradle configs, Room DAOs, documentation, or code audits.

## Project Structure & Architecture
- **Shared Multiplatform Library (`:shared`)**: Package `com.clinref.app` / `com.clinref.shared` targeting **Android** and **Desktop (JVM 17)** via Compose Multiplatform (CMP):
  - `commonMain`: Compose M3 Expressive UI & adaptive layouts, Navigation 3 runtime & peer roots, ViewModels (MVI StateFlows), Koog AI ReAct state machine & 7 LLM providers, domain entities, Room 3 AppDatabase & DAOs, raw SQLite query engine via `BundledSQLiteDriver`, pure KMP `kzstd` decompression, and Koin DI modules (`commonModules`).
  - `desktopMain`: Desktop JVM runtime (`DesktopApp.kt`), AES-256 encrypted secure preferences (`DesktopSecurePreferences`), Swing/AWT `ArticleWebView.desktop.kt` (`SwingPanel` + `JEditorPane`), desktop platform logger, and desktop Koin DI (`desktopAppModules`).
  - `androidMain`: Android-specific implementations, hardware-accelerated Chrome `WebView` (`ArticleWebView.android.kt`, `NestedScrollWebView`, `JsBridge`), Android `BackHandler.android.kt`, and Android platform logger.
- **Android App Launcher (`:androidApp`)**: Package `com.clinref.app` using Compose M3, Koin DI (`androidPlatformModule`), Navigation 3, and Room 3.
- **Desktop App Launcher (`:desktopApp`)**: Package `com.clinref.desktop` targeting Desktop JVM 17. Standalone diagnostic runner and Compose Desktop launcher depending on `:shared`.
- **Python Prototype (`python_prototype/`)**: LangGraph/LangChain agent in Python 3.13 (`uv`). Mirrors Kotlin agent logic 1:1.
- **SQLite DBs (Project Root)**: 6 SQLite databases (`utdasset.sqlite`, `unidex.en.sqlite`, `fsearch.db`, `utdqf.sqlite`, `utdtoc.db`, `thumbs.db`) read directly by both the Python prototype and the Kotlin SQLite engine.

## Verification & Commands

### Build & Test Commands

```bash
# Desktop tests & compilation check (primary local verification command — 143 tests in ~4s)
./gradlew :shared:desktopTest --stacktrace

# Run Desktop diagnostic verifier & app (queries root SQLite DBs, all 10 ViewModels, Room 3)
./gradlew :desktopApp:run --console=plain

# Quick desktop app compilation check
./gradlew :desktopApp:compileKotlin --console=plain

# Quick shared desktop compilation check without running tests (fast typecheck)
./gradlew :shared:compileKotlinDesktop --stacktrace

# Generate code coverage report via Jacoco (XML + HTML, when configured)
./gradlew :shared:jacocoDesktopTestReport --stacktrace

# Lint (Android — CI only, requires Android SDK)
./gradlew lint --stacktrace

# Full Android release build (CI only, requires Android SDK)
./gradlew assembleRelease --stacktrace

# Desktop release package (whole-program ProGuard optimization, packaging only)
./gradlew :shared:packageReleaseDistributionForCurrentOS --stacktrace
```

> **Important**: Android builds cannot be run locally — there is no Android SDK on this system. Android APKs are built exclusively via GitHub Actions CI. Only desktop targets can be run locally: use `:shared:desktopTest` for rapid verification and test passes; `:shared:compileKotlinDesktop` for fast syntax and typechecking without running tests; and `:desktopApp:run` for local desktop execution against SQLite databases. No separate typecheck or formatter commands — compilation is the typecheck.


### Python Prototype (`python_prototype/`)
> **Important**: Run these commands **ONLY** when you have modified `.py` files inside `python_prototype/`.
```bash
uv run pytest test_clinref.py        # Run 90 unit tests (ONLY when python_prototype/ code was modified)
uv run pytest test_integration.py   # Run 49 integration tests against root SQLite DBs (ONLY when python_prototype/ code was modified)
uv run python run.py                 # Run interactive CLI agent
```

### Kotlin Unit Tests
- `ui/navigation/NavigationStateTest.kt`: Independent peer roots, multi-backstack state preservation, reselect scroll-to-top, Exit Through Home pattern.
- `ui/search/SearchViewModelTest.kt`: Query debouncing (300ms), audience filtering, SavedStateHandle query restoration.
- `ui/toc/TocViewModelTest.kt`: Tree expansion, root loading, expanded ID persistence, topic resolution.
- `ui/conversations/ConversationListViewModelTest.kt`: Pinning, unread status, search filtering, multi-selection.
- `ui/history/HistoryViewModelTest.kt` & `ui/favorites/FavoritesViewModelTest.kt`: StateFlow WhileSubscribed flow collection and DAO operations.
- `data/local/AppDatabaseTest.kt`: Room 3 in-memory database, ConversationDao, MessageDao (cascade deletes), HistoryDao, FavoriteDao.
- `data/MedicalDatabaseToolsTest.kt`: Speculative outline bundling, link parsing, footnote bracket removal, graphic table markdown.
- `domain/ai/SafetyValidatorTest.kt`: Intent-aware rules, compound slashed units (`5 u/x`, `0.5 mcg/kg/min`), thousand-separator comma normalization (`1,200 mg` vs `1200 mg`).
- `domain/ai/TurnContextAccumulatorTest.kt`: Tool call deduplication, outline title dash stripping (`-Antiplatelet` -> `Antiplatelet`), ref auto-population.

See [`docs/testing.md`](docs/testing.md) for the complete testing strategy, test inventory, fakes architecture, and roadmap.

## Architectural Parity & Design Precedence (Kotlin & Python)
> **Design Precedence**: `python_prototype/` serves as an algorithm verification testbed and reference implementation. Algorithmic tool flow, safety regexes, and validation criteria should align, but **architectural parity must NEVER compromise idiomatic Kotlin, Jetpack Compose M3 Expressive, Room 3, Navigation 3, or Koin architecture**. When superior multiplatform UX or cleaner software engineering warrants divergence (e.g., rich UI models, navigation scoping, native DI), prioritize Kotlin/CMP architecture.

1. **Pure 3-Stage Pipeline**: `searchTopics` / `search_topics` returns candidate topics `[{id, title}]`; `getTopicOutline` / `get_topic_outline` retrieves topic outline (sections, table graphics, `topicType`).
2. **Intent-Aware Safety Validation**: Non-clinical greetings bypass tool requirements. Answers containing clinical numbers/units (`CLINICAL_QUANTITY_REGEX`) require tool verification; unverified quantities trigger a hard block for self-correction.
3. **Number & Quantity Normalization**: Range bounds (`5-10 mg`) and thousand separators (`1,200 mg` vs `1200 mg`) are normalized during text verification. Structural numbers (section IDs, years, list steps) are exempted.
4. **HTML & Link Sanitization**: `javascript:appAction(...)` JSON link payloads convert to `[Text](Topic-ID)` / `[Text](Graphic-ID)`. Footnote citation brackets (`[1]`, `[1, 2]`) are stripped.
5. **Reference Navigation Intent**: In `ClinicalReferencesSection`, tapping the parent topic card navigates to the root of the article (`sectionId = null`), while tapping a sub-section chip navigates to that specific section (`sectionId = sec.sectionId`).
6. **Canonical Tool Response Identity**: Retrieval tool responses (`TopicSectionsResponse` / `get_topic_sections_text`) must explicitly include `topicId` alongside `topicTitle` to prevent fragile title-matching heuristics or stale parent associations.
7. **Synthetic Section Normalization**: Full-article and calculator queries use `"FULL"` as an internal section sentinel. `"FULL"` must always be normalized to `""` in reference models (`TopicRef`, `ClinicalSource`) so reader navigation directs to the root article rather than looking for a nonexistent `#FULL` DOM anchor.

## Known Quirks
- **Koog Duplicate Classes**: `utils-android` must remain excluded in Gradle configurations; `utils-jvm` is used instead.
- **ProGuard**: Keep rules in `androidApp/proguard-rules.pro` for Koog, Room3, Koin, serialization, and Compose must not be trimmed.
- **KMP AGP 9 Setup**: `:shared` uses `com.android.kotlin.multiplatform.library` plugin with `withHostTest { }` inside `kotlin { android { ... } }`. Source sets (`commonMain`, `commonTest`, `androidMain`, `androidHostTest`) use explicit KMP DSL accessors.
- **Navigation 3 Top-Level Peer Roots**: Bottom navigation and navigation rail top-level routes MUST be independent roots in `toDecoratedEntries` (`getTopLevelRoutesInUse() = listOf(topLevelRoute)`). NEVER stack `listOf(startRoute, topLevelRoute)` to implement "Exit through Home" — this corrupts `NavDisplay`'s internal scene state, z-index calculation, and predictive back targeting across multi-tab transitions. Instead, handle "Exit through Home" via an explicit top-level `BackHandler` (`enabled = !isOnOverlayScreen && topLevelRoute != startRoute`).
- **Koog Tool Result Double-Encoding**: In Koog, `eventContext.toolResult` for string-returning tools is a `JSONPrimitive`. Calling `.toString()` outputs double-encoded, escaped JSON strings (`"\"{\\\"key\\\":...}\""`). Deserializing tool results or args in Kotlin must unbox string primitives (e.g., via `extractJsonString` / `parseAsJsonObject`) before attempting `as? JsonObject` to avoid silent `null` deserialization failures.
- **Koog Tool Argument Deserialization Resilience**: In Koog `SimpleTool<Args>`, LLMs frequently generate `snake_case` JSON keys (e.g., `topic_id`, `section_ids`, `graphic_id`). All `Args` data classes must annotate properties with `@OptIn(ExperimentalSerializationApi::class)` and `@JsonNames(...)` aliases to prevent runtime `MissingFieldException` failures.
- **Streaming Buffer Hygiene**: When an agent emits preliminary thoughts or tokens before tool calls, streaming managers must clear text buffers on `onToolCallStarting` so that intermediate thought tokens do not bleed into the final streamed clinical response.
- **Article Scroll & Reading Position Preservation**: Articles in `HtmlContentWebView` use content-anchored positions (`data-b` block indexes + fractional offsets in in-page JS `ArticleScrollJs.kt` for Android WebView, and model offsets in Desktop JEditorPane). Restorations must temporarily suppress `scroll-behavior: smooth` (defined in `CssBuilder.kt`) using `behavior: 'instant'` / `auto` to prevent animated scrolling from top. `ContentScreen` must invoke `viewModel.open(topicId, sectionId)` (idempotent) rather than observing raw route changes to prevent in-article link navigation history from being wiped on screen re-entry.
- **Chat Scroll & Reading Position Preservation**: Chat conversation positions are preserved in `ChatScrollStateCache` across in-session navigation (e.g., following clinical topic/section citations into `ContentScreen` and returning). `ChatViewModel.loadConversation` is idempotent to prevent message state or pagination history from wiping on re-entry. Auto-scroll to bottom only triggers on user-sent messages (`ChatScrollEvent.ScrollToBottom`) or during AI token streaming when the user is already at the bottom (`isAtBottom = true`); it MUST NOT trigger when loading older messages or when the clinician has scrolled up to inspect previous turns. In `loadOlderMessages`, older message pages from Room (`timestamp DESC`) MUST be `.reversed()` prior to prepending to maintain strict chronological order.
