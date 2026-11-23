# ClinRef — Agent Instructions

## Critical Execution Rules
- **No Android SDK on this machine**: NEVER run Android-specific Gradle tasks (e.g., `./gradlew assembleDebug`, `./gradlew :androidApp:...`, Android instrumented tests, or Android emulator/device tasks).
- **Desktop/JVM Gradle Tasks ARE Permitted**: You MAY run Desktop JVM tasks for `:shared` (e.g., `./gradlew :shared:compileKotlinDesktop`, `./gradlew :shared:desktopTest`) since they compile and run on standard JVM 17 without requiring the Android SDK.
- **Do NOT commit `python_prototype/.env`**: Contains local API keys.
- **Only run `pytest` when modifying real Python code in `python_prototype/`**: Do NOT run `pytest` unconditionally. Specifically, NEVER run `pytest` for Kotlin-only changes, UI/Compose changes, XML resources, Gradle configs, Room DAOs, documentation, or code audits.

## Project Structure & Architecture
- **Shared Multiplatform Library (`:shared`)**: Package `com.clinref.app` / `com.clinref.shared` targeting **Android** and **Desktop (JVM 17)** via Compose Multiplatform (CMP):
  - `commonMain`: Compose M3 Expressive UI & adaptive layouts, Navigation 3 runtime & peer roots, ViewModels (MVI StateFlows), procedural two-stage AI clinical orchestrator (`ClinicalOrchestrator`), optional Gemini Decisions API (`DecisionClient`, `gemini-decision-1` for search/overview reranking and auto-prefetching) & 6 LLM providers (via Koog `PromptExecutor`), domain entities, Room 3 AppDatabase & DAOs, raw SQLite query engine via `BundledSQLiteDriver`, pure KMP `kzstd` decompression, and Koin DI modules (`commonModules`).
  - `desktopMain`: Desktop JVM runtime (`DesktopApp.kt`), AES-256 encrypted secure preferences (`DesktopSecurePreferences`), Swing/AWT `ArticleWebView.desktop.kt` (`SwingPanel` + `JEditorPane`), desktop platform logger, and desktop Koin DI (`desktopAppModules`).
  - `androidMain`: Android-specific implementations, hardware-accelerated Chrome `WebView` (`ArticleWebView.android.kt`, `NestedScrollWebView`, `JsBridge`), Android `BackHandler.android.kt`, and Android platform logger.
- **Android App Launcher (`:androidApp`)**: Package `com.clinref.app` using Compose M3, Koin DI (`androidPlatformModule`), Navigation 3, and Room 3.
- **Desktop App Launcher (`:desktopApp`)**: Package `com.clinref.desktop` targeting Desktop JVM 17. Standalone diagnostic runner and Compose Desktop launcher depending on `:shared`.
- **Python Prototype (`python_prototype/`)**: Python 3.13 (`uv`). LangGraph/LangChain agent, algorithm verification testbed, and offline CDS benchmark harness.
- **SQLite DBs (Project Root)**: 7 SQLite databases (`utdasset.sqlite`, `unidex.en.sqlite`, `fsearch.db`, `fcontentsearch.db`, `utdqf.sqlite`, `utdtoc.db`, `thumbs.db`) read directly by both the Python prototype and the Kotlin SQLite engine.

## Verification & Commands

### Build & Test Commands

```bash
# Desktop tests & compilation check (primary local verification command — 372 tests in ~20-25s)
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
uv run pytest test_clinref.py        # Run unit tests (ONLY when python_prototype/ code was modified)
uv run pytest test_integration.py   # Run integration tests against root SQLite DBs (ONLY when python_prototype/ code was modified)
uv run pytest                       # Full suite: ~400+ tests (unit + integration + e2e + contracts + adversarial)
uv run python run.py                 # Run interactive CLI agent
uv run python eval_prompt.py          # Score prompt conformance on Gemini via local agy bridge (no external API key needed!)
./benchmark_agy.sh                    # Convenient shortcut for live Gemini CDS benchmark (supports --case, --category, --verbose)
uv run python eval_prompt.py --mode agent --provider mistral # Full agentic retrieval over SQLite (needs provider API key)
```

### Kotlin Unit Tests
- `ui/navigation/NavigationStateTest.kt`: Independent peer roots, multi-backstack state preservation, reselect scroll-to-top, Exit Through Home pattern.
- `ui/search/SearchViewModelTest.kt`: Query debouncing (300ms), audience filtering, SavedStateHandle query restoration.
- `ui/toc/TocViewModelTest.kt`: Tree expansion, root loading, expanded ID persistence, topic resolution.
- `ui/conversations/ConversationListViewModelTest.kt`: Pinning, unread status, search filtering, multi-selection.
- `ui/history/HistoryViewModelTest.kt` & `ui/favorites/FavoritesViewModelTest.kt`: StateFlow WhileSubscribed flow collection and DAO operations.
- `ui/chat/ChatViewModelTest.kt`: Message dispatching, cancel generation, state machine transitions, conversation initialization.
- `ui/chat/ChatScrollControllerTest.kt`: Initial scroll landing on last user message, prepend anchor preservation on pagination, and manual bottom jumps.
- `data/local/AppDatabaseTest.kt`: Room 3 in-memory database, ConversationDao, MessageDao (cascade deletes), HistoryDao, FavoriteDao.
- `data/MedicalDatabaseToolsTest.kt`: Outline section parsing, payload-derived graphic/related-topic inventories, link parsing, footnote bracket removal, parent-child section subsumption, graphic table markdown.
- `domain/ai/ClinicalOrchestratorTest.kt`: Two-stage clinical retrieval while-loop, 1-pass empty evidence retry, parallel coroutines, canonical abstention, small-talk bypass, thinking-token stripping fallback, and token accounting.
- `domain/ai/ChatExecutionManagerTest.kt`: Turn execution lifecycle, reliability timeout management, cancellation, streaming events, retry, and error state machine.
- `domain/ai/DecisionClientTest.kt` & `domain/ai/DecisionGatesTest.kt`: Gemini Decisions API (`gemini-decision-1`), search candidate scoring, overview section/table scoring, auto-prefetching, and fail-open behavior.
- `domain/ai/TurnContextAccumulatorTest.kt`: Tool call deduplication, outline title dash stripping (`-Antiplatelet` -> `Antiplatelet`), ref auto-population, and thread-safe concurrent collection.

See [`docs/ai-system-prompts-and-pipelines.md`](docs/ai-system-prompts-and-pipelines.md) for the operational prompt and tool contract reference and [`docs/architectural-principles-universal-clinical-prompt.md`](docs/architectural-principles-universal-clinical-prompt.md) for the foundational biomedical CDS architecture and empirical verification whitepaper.

## Architectural Parity & Design Precedence (Kotlin & Python)
> **Design Precedence**: `python_prototype/` serves as an algorithm verification testbed and reference implementation. Algorithmic tool flow, safety regexes, and validation criteria should align, but **architectural parity must NEVER compromise idiomatic Kotlin, Jetpack Compose M3 Expressive, Room 3, Navigation 3, or Koin architecture**. When superior multiplatform UX or cleaner software engineering warrants divergence (e.g., rich UI models, navigation scoping, native DI), prioritize Kotlin/CMP architecture.

1. **Lean Retrieval Pipeline (4 tools)**: `searchTopics` returns candidate topics `[{id, title}]`; `getTopicOverview` retrieves the compound bundle (section outline, `graphic_table` inventory, related topics, `topicType`, full markdown for calculators); `getTopicSectionsText` returns section text; `getGraphicContent` returns table markdown. Separate `getRelatedTopics` and `searchTopicSections` tools are deliberately NOT exposed — related topics and complete outline hierarchies already arrive inside the overview bundle, eliminating redundant round trips, 150-char evidence stalls, and tool selection ambiguity.
2. **Intent-Aware Clinical Safety & Anti-Arithmetic Grounding**: Non-clinical greetings bypass tool requirements via Stage 0 small talk gating. In Kotlin production, quantitative grounding is enforced architecturally through closed-book Stage 2 prompt isolation (`SYNTHESIS_SYSTEM_PROMPT` conditioned strictly on `<retrieved_evidence>`), Stage 1 evidence gating with canonical abstention (`CANONICAL_ABSTENTION`), and strict anti-arithmetic verbatim dosing directives (weight-based doses like "100 mg/kg/day" must be copied verbatim; synthetic multiplication into absolute doses is strictly forbidden). Post-hoc regex gating (`SafetyValidator`) was decommissioned in Kotlin to allow unblocked real-time token streaming, while retained in `python_prototype/` for offline benchmark scoring. GRADE modal verbs ("recommend" vs "suggest" vs "may") and canonical abstention ("Not specified in retrieved UpToDate sections.") are enforced across both stacks. Single-fact queries omit redundant subheadings. Empty headings and headings stating nothing was specified are strictly prohibited.
3. **Number & Quantity Normalization (Evaluation Harness)**: Quantities are extracted as canonical `<number> <unit>` tokens from both the answer and the evidence corpus, then compared by set membership in the `python_prototype/` evaluation harness. Normalizations: both range bounds (`5-10 mg` ≡ `5 to 10 mg`), thousand separators (`1,200 mg` ≡ `1200 mg`), trailing zeros (`2.0 mg` ≡ `2 mg`), `%` ≡ `percent`, `µg`/`μg`/`ug` ≡ `mcg`, singular/plural durations. Units outside the clinical vocabulary are not quantities. The evidence corpus also includes the user question and the active patient profile, so restating a patient-stated value is not flagged.
4. **DB-Grounded Metadata**: `topicType` comes from the payload's `topicInfo.type` (`drug` / `medical` / `calc` / `narrative`), never from content-shape guesses. Table graphics come from the payload's structured `relatedGraphics` inventory (`subtype == "graphic_table"`), never from `outlineHtml` — outline hrefs carry no subtype, so an outline-derived inventory cannot tell a table from a photograph. The two Lexidrug appendix tables (`57469`, `116421`) are filtered as boilerplate.
5. **HTML & Link Sanitization**: For LLM evidence text (`getTopicSectionsText`), anchor tags and `javascript:appAction(...)` link wrappers are converted directly into clean plain text (raw anchor text without synthetic `[Text](Topic-ID)` or `[Text](Graphic-ID)` markdown links) to eliminate context bloat and link hallucinations. Footnote citation brackets (`[1]`, `[1, 2]`) are stripped.
6. **Reference Navigation Intent**: In `ClinicalReferencesSection`, tapping the parent topic card navigates to the root of the article (`sectionId = null`), while tapping a sub-section chip navigates to that specific section (`sectionId = sec.sectionId`).
7. **Canonical Tool Response Identity**: Retrieval tool responses (`TopicSectionsResponse` / `get_topic_sections_text`) must explicitly include `topicId` alongside `topicTitle` to prevent fragile title-matching heuristics or stale parent associations.
8. **Synthetic Section Normalization**: Full-article and calculator queries use `"FULL"` as an internal section sentinel. `"FULL"` must always be normalized to `""` in reference models (`TopicRef`, `ClinicalSource`) so reader navigation directs to the root article rather than looking for a nonexistent `#FULL` DOM anchor.
9. **Procedural Two-Stage Clinical Pipeline (`ClinicalOrchestrator`)**: Kotlin implements a deterministic two-stage pipeline: Stage 0 small-talk regex gating (zero LLM calls); Stage 1 ephemeral parallel tool retrieval while-loop (tools bound, parallel coroutine execution via `coroutineScope { awaitAll() }`, max 10 rounds, full untruncated evidence, up to `MAX_CHAT_HISTORY_TURNS = 100` turns) with 1-pass evidence retry and canonical abstention; Stage 2 closed-book synthesis with live word-by-word streaming (`executor.executeStreaming`), tools stripped (`tools = emptyList()`), prompt caching (`AnthropicCacheControl.Default`), thinking-token stripping, and automatic fallback to non-streaming execution on incomplete stream termination. Completed responses are saved directly to Room with zero post-hoc buffering stalls.
10. **Parent-Child Section Hierarchy Subsumption**: When `getTopicSectionsText` receives section IDs where child subsections are nested under an ancestor section already requested (or both H1 and H2/H3s are requested), sections are processed in document outline order and child subsections are subsumed by their ancestor. Ancestor sections extract child text in full; subsumed child sections emit `(Content included in section [<ancestor>] above)` instead of duplicating clinical text. All requested section IDs are retained in `sectionTitles` for UI citation chips and navigation anchors, eliminating multi-thousand-token context bloat while preserving 100% evidence verification and navigation fidelity.

## Known Quirks
- **Koog Duplicate Classes**: `utils-android` must remain excluded in Gradle configurations; `utils-jvm` is used instead.
- **ProGuard**: Keep rules in `androidApp/proguard-rules.pro` for Koog, Room3, Koin, serialization, and Compose must not be trimmed.
- **KMP AGP 9 Setup**: `:shared` uses `com.android.kotlin.multiplatform.library` plugin with `withHostTest { }` inside `kotlin { android { ... } }`. Source sets (`commonMain`, `commonTest`, `androidMain`, `androidHostTest`) use explicit KMP DSL accessors.
- **Navigation 3 Top-Level Peer Roots**: Bottom navigation and navigation rail top-level routes MUST be independent roots in `toDecoratedEntries` (`getTopLevelRoutesInUse() = listOf(topLevelRoute)`). NEVER stack `listOf(startRoute, topLevelRoute)` to implement "Exit through Home" — this corrupts `NavDisplay`'s internal scene state, z-index calculation, and predictive back targeting across multi-tab transitions. Instead, handle "Exit through Home" via an explicit top-level `BackHandler` (`enabled = !isOnSubRoute && topLevelRoute != startRoute`).
- **Koog Tool Result Double-Encoding**: In Koog, `eventContext.toolResult` for string-returning tools is a `JSONPrimitive`. Calling `.toString()` outputs double-encoded, escaped JSON strings (`"\"{\\\"key\\\":...}\""`). Deserializing tool results or args in Kotlin must unbox string primitives (e.g., via `extractJsonString` / `parseAsJsonObject`) before attempting `as? JsonObject` to avoid silent `null` deserialization failures.
- **Koog Tool Argument Deserialization Resilience**: In Koog `SimpleTool<Args>`, LLMs frequently generate `snake_case` JSON keys (e.g., `topic_id`, `section_ids`, `graphic_id`). All `Args` data classes must annotate properties with `@OptIn(ExperimentalSerializationApi::class)` and `@JsonNames(...)` aliases to prevent runtime `MissingFieldException` failures.
- **Procedural Orchestration Over Graph DSL**: Koog's Graph DSL previously suffered from subtle edge-trigger bugs where an `onTextMessage` lambda returned an `Option.of("")` that satisfied the edge unconditionally. To eliminate fragile edge routing and hidden state mutations, the AI pipeline uses `ClinicalOrchestrator`—a clean, procedural Kotlin coroutine orchestrator executing Stage 0 small talk, Stage 1 parallel tool retrieval, and Stage 2 closed-book streaming synthesis directly on `PromptExecutor` without Graph DSL overhead.
- **Streaming Buffer Hygiene**: When an agent emits preliminary thoughts or tokens before tool calls, streaming managers must clear text buffers on `onToolCallStarting` so that intermediate thought tokens do not bleed into the final streamed clinical response.
- **Article Scroll & Reading Position Preservation**: Articles in `HtmlContentWebView` use content-anchored positions (`data-b` block indexes + fractional offsets in in-page JS `ArticleScrollJs.kt` for Android WebView, and model offsets in Desktop JEditorPane). Restorations must temporarily suppress `scroll-behavior: smooth` (defined in `CssBuilder.kt`) using `behavior: 'instant'` / `auto` to prevent animated scrolling from top. `ContentScreen` must invoke `viewModel.open(topicId, sectionId)` (idempotent) rather than observing raw route changes to prevent in-article link navigation history from being wiped on screen re-entry.
- **Chat Scroll & Reading Position Preservation & In-Chat Dialog/Sheet Overlays**: Following clinical topic/section citations or graphic previews from AI Chat renders `ContentScreen` inside a local full-screen Compose `Dialog` overlay and `GraphicSheet` directly inside a Compose bottom sheet overlay on top of `ChatScreen`, leaving `ChatScreen` continuously mounted in the composition hierarchy. This guarantees `LazyColumn` reading positions, text field input state, and active message streaming coroutines are never unmounted, reset, or clamped on overlay dismissal or back gesture, while preventing nested `ModalBottomSheet` issues (double scrims, focus traps, and 640dp max width capping on large displays). In-dialog reader sessions use an ephemeral `ViewModelStoreOwner` disposed upon dismissal to prevent state leakage and ensure subsequent article opens instantiate a fresh `ContentViewModel`. Standard library entry points (Table of Contents, Global Search, History, Favorites) continue to navigate to the full-screen reader (`ContentRoute`) via Navigation 3's `NavDisplay`.
  - **Scroll Landing & Zero Follow-Streaming**: On initial conversation open, the list positions on the last user message (via `hasCompletedInitialScroll`), allowing the clinician to immediately read the prompt while response synthesis streams below. There is **zero auto-following scroll during response streaming**; clinicians maintain full reading freedom without programmatic jumping. Auto-scroll to bottom only triggers on user-sent messages (`ChatScrollEvent.ScrollToBottom`) or manual tap of `ScrollToBottomFAB`.
  - **FAB Visibility & Pagination Anchoring**: The `ScrollToBottomFAB` is strictly hidden while `isGenerating` is true and only reappears after completion if the list is not at the bottom. In `loadOlderMessages`, older message pages from Room (`timestamp DESC`) MUST be `.reversed()` prior to prepending, and `pendingPrependAnchor` preserves the clinician's exact item offset across list state updates.
  - NEVER attempt to solve LazyColumn reading position retention across destructive route changes using post-remount `LaunchedEffect(scrollToItem)` or pre-navigation snapshot listeners — these inevitably fail due to Frame 0 item count clamping and teardown state clobbering. Always preserve complex scroll state by maintaining the parent composable in the composition hierarchy.
- **Context Menu Selection Flicker Workaround (`b/519816995`)**: When dismissing a text selection in Compose Foundation 1.9+ (`isNewContextMenuEnabled = true`), a race condition in `SelectionManager` clears selection state a frame before `ActionMode.finish()`, causing Android to briefly flash a `"Select all"`-only floating toolbar. To prevent this without disabling the new context menu (which would drop system `PROCESS_TEXT` actions like Translate/Web Search), `ChatScreen` wraps the message list in `suppressTransientSelectAllContextMenu()` using `.appendTextContextMenuComponents` (which runs first on every pass to reset a per-pass flag) and `.filterTextContextMenuComponents` (which drops `SelectAllKey` whenever `CopyKey` is absent on the dismiss frame).
