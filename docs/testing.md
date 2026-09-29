# ClinRef — Testing Strategy & Architecture Report

## 1. Current Testing Setup Analysis

| Category | Status & Implementation Details |
|---|---|
| **Architecture / Multiplatform Target** | **Compose Multiplatform (CMP)**: `:shared` (library targeting JVM 17 Desktop & Android SDK 35+), `:desktopApp` (standalone Desktop JVM runner), and `:app` (Android launcher). |
| **Dependency Injection** | **Koin 4.2.2**: Multiplatform DI (`commonModules`, `desktopAppModules`, `androidPlatformModule`). All ViewModels, DAOs, and repositories run natively via Koin. |
| **Unit Testing Framework** | **JUnit 4 (`junit:4.13.2`)**: Executed via `:shared:desktopTest` on standard OpenJDK 27 / JVM 17 without requiring Android SDK. |
| **Coroutines & AI Testing** | `kotlinx-coroutines-test:1.10.1` (`StandardTestDispatcher`, `runTest`, `backgroundScope`), `ai.koog:agents-test:1.2.0`. |
| **Mocking Framework** | **MockK (`io.mockk:mockk:1.13.13`)**: Type-safe Kotlin mocking in `commonTest`. |
| **Test Fakes** | In-memory test fakes (`FakeSecurePreferences`) for hermetic preference testing without disk or Android keystore. |
| **Robolectric** | **Not needed for desktop tests**: Unit tests, ViewModel state flows, Navigation 3 backstacks, and in-memory Room 3 SQLite tests run natively on Desktop JVM without Android emulation overhead. |
| **Code Coverage** | **JaCoCo (`0.8.14`)**: Configured via `:shared:jacocoDesktopTestReport`. Produces comprehensive HTML and XML coverage reports under `shared/build/reports/jacoco/`. |
| **UI Composition** | **100% Jetpack Compose / Compose Multiplatform M3 Expressive**: Multiplatform `BackHandler`, adaptive navigation (bottom bar <600dp, vertical rail >=600dp), and platform-abstracted `ArticleWebView`. |
| **UI Behavior & Screenshot Testing** | Candidate for Roborazzi / Compose Multiplatform UI test runner on JVM. |
| **Smoke & End-to-End Testing** | `:desktopApp:run` verifies live queries against the 6 root SQLite databases, Room 3 initialization, and all 10 ViewModels. |
| **Python Prototype Verification** | `pytest` in `python_prototype/` (90 unit tests, 49 integration tests against root SQLite DBs). |

---

## 2. Test Inventory (143 Passing Tests in ~4.2s)

```
Gradle Test Run :shared:desktopTest[desktop]
├── com.clinref.app.ui.navigation.NavigationStateTest (10 tests)
│   ├── initial state starts at startRoute with isolated peer root stacks [PASSED]
│   ├── switching top-level tabs changes topLevelRoute without altering other stacks [PASSED]
│   ├── navigating to sub-route pushes onto current active top-level stack [PASSED]
│   ├── navigating to identical sub-route is idempotent and does not duplicate stack entry [PASSED]
│   ├── reselecting active top-level route with sub-stack pops back to root [PASSED]
│   ├── reselecting active top-level route when already at root emits reselect event [PASSED]
│   ├── goBack pops current sub-route when in sub-stack [PASSED]
│   ├── goBack on non-start top-level root exits through home (navigates to startRoute) [PASSED]
│   ├── goBack at startRoute root does not pop or crash [PASSED]
│   └── switching tabs preserves individual tab sub-stacks [PASSED]
├── com.clinref.app.ui.search.SearchViewModelTest (7 tests)
│   ├── onQueryChanged with query length <= 2 clears suggestions [PASSED]
│   ├── onQueryChanged with query length > 2 debounces and fetches suggestions [PASSED]
│   ├── search with blank query is ignored [PASSED]
│   ├── search with valid query populates results and clears suggestions and loading [PASSED]
│   ├── search failure records error message and turns off loading [PASSED]
│   ├── onAudienceChanged updates selected audience and re-runs search if query is non-empty [PASSED]
│   └── restoring saved query from SavedStateHandle auto-initiates search on creation [PASSED]
├── com.clinref.app.ui.toc.TocViewModelTest (6 tests)
│   ├── init loads root TOC items successfully [PASSED]
│   ├── init failure records error and clears loading state [PASSED]
│   ├── loadChildren fetches and attaches child items to correct parent item [PASSED]
│   ├── toggleExpanded alternates presence in expandedIds set [PASSED]
│   ├── resolveTopicId delegates directly to repository [PASSED]
│   └── retry reloads root TOC items [PASSED]
├── com.clinref.app.ui.conversations.ConversationListViewModelTest (7 tests)
│   ├── initialization maps entities to UI models and applies ALL filter by default [PASSED]
│   ├── filtering by PINNED shows only pinned conversations [PASSED]
│   ├── filtering by UNREAD shows only unread conversations [PASSED]
│   ├── search query filters conversations by preview or title [PASSED]
│   ├── toggleItemSelection updates selectedIds and activates selection mode [PASSED]
│   ├── togglePin delegates to conversation repository [PASSED]
│   └── deleteConversation delegates to conversation repository [PASSED]
├── com.clinref.app.ui.history.HistoryViewModelTest (4 tests)
│   ├── history state flow exposes emitted entries from repository [PASSED]
│   ├── addHistory delegates to repository addOrPromote [PASSED]
│   ├── removeHistory delegates to repository remove [PASSED]
│   └── clearHistory delegates to repository clear [PASSED]
├── com.clinref.app.ui.favorites.FavoritesViewModelTest (4 tests)
│   ├── favorites state flow exposes emitted entries from repository [PASSED]
│   ├── addFavorite delegates to repository add [PASSED]
│   ├── removeFavorite delegates to repository remove [PASSED]
│   └── clearFavorites delegates to repository clearAll [PASSED]
├── com.clinref.app.ui.content.GraphicViewModelTest (4 tests)
│   ├── loadGraphic emits Error on exception instead of crashing [PASSED]
│   ├── loadGraphic emits Error on null result [PASSED]
│   ├── loadGraphic emits Success on decoded graphic [PASSED]
│   └── retry reloads last requested graphic [PASSED]
├── com.clinref.app.data.MedicalDatabaseToolsTest (10 tests)
│   ├── asToolList returns 5 class-based tools with expected names [PASSED]
│   ├── class-based tools execute properly and return structured content [PASSED]
│   ├── getGraphicContent blocks non-table graphic types [PASSED]
│   ├── getGraphicContent renders markdown tables for graphic_table type [PASSED]
│   ├── getTopicOutline handles calculator topic with empty outlineHtml [PASSED]
│   ├── getTopicOutline parses outline html into sections and table graphics [PASSED]
│   ├── getTopicSectionsText renders full bodyHtml for calculator or FULL [PASSED]
│   ├── getTopicSectionsText renders markdown and converts appAction links [PASSED]
│   ├── searchTopics auto-retries when first query has no results [PASSED]
│   └── searchTopics returns pure candidate topics without embedded outlines [PASSED]
├── com.clinref.app.data.AssetRepositoryTest (7 tests)
│   ├── caches decoded graphic per id [PASSED]
│   ├── distinct ids return distinct instances [PASSED]
│   ├── evicts eldest entry beyond capacity and refetches [PASSED]
│   ├── getGraphicTitle delegates to dao when graphic is not cached [PASSED]
│   ├── getGraphicTitle returns cached title when graphic is already loaded [PASSED]
│   ├── missing payload returns null and is not cached as success [PASSED]
│   └── most recent entries survive beyond capacity [PASSED]
├── com.clinref.app.data.local.AppDatabaseTest (4 tests)
│   ├── testConversationCrudAndQueries (CRUD, rename, pin, tokens, tokenLimit, read state) [PASSED]
│   ├── testMessageCascadeOnConversationDelete (ForeignKey CASCADE verification) [PASSED]
│   ├── testHistoryDaoOperations (insert, count Flow, timestamp DESC, delete) [PASSED]
│   └── testFavoriteDaoOperations (insert, isFavorite Flow, timestamp DESC, delete) [PASSED]
├── com.clinref.app.domain.ai.ClinicalAgentStrategyTest (1 test)
│   └── strategy creates autonomous graph with correct name [PASSED]
├── com.clinref.app.domain.ai.SafetyValidatorTest (60+ parameter variations)
│   ├── intent-aware rules for greetings vs clinical queries [PASSED]
│   ├── compound slashed units normalization (5 u/x, 0.5 mcg/kg/min) [PASSED]
│   ├── thousand-separator comma normalization (1,200 mg vs 1200 mg) [PASSED]
│   └── unverified quantity hard-block triggers [PASSED]
├── com.clinref.app.domain.ai.TurnContextAccumulatorTest (12 tests)
│   ├── tool call deduplication [PASSED]
│   ├── outline title dash stripping (-Antiplatelet -> Antiplatelet) [PASSED]
│   └── reference auto-population [PASSED]
├── com.clinref.app.domain.GraphicDataTest (4 tests)
│   └── graphic payload parsing and entity mapping [PASSED]
└── com.clinref.app.util.HtmlNormalizerTest (3 tests)
    └── link normalization and citation tag stripping [PASSED]
```

---

## 3. Recommended Testing Strategy & Roadmap

### Step A: ViewModels & Navigation Unit Testing (Completed)
Unit tests in `shared/src/commonTest/`:
1. [x] **`NavigationStateTest`**: Verified Independent Peer Roots behavior, debounced tab reselection, sub-stack popping, and Exit Through Home pattern.
2. [x] **`SearchViewModelTest`**: Verified query debouncing (300ms), audience filtering (`Audience.ALL`, `ADULT`, `PATIENT`), and search state transitions.
3. [x] **`TocViewModelTest`**: Verified root item loading, child item tree expansion, and leaf navigation.
4. [x] **`ConversationListViewModelTest`**: Verified filtering (All/Pinned/Unread), query searching, and multi-selection mode.
5. [x] **`HistoryViewModelTest` & `FavoritesViewModelTest`**: Verified StateFlow observation with `WhileSubscribed(5000)` and repository operations.
6. [ ] **`ChatViewModelTest`**: Test conversation loading, user message submission, and Koog AI streaming integration.

### Step B: In-Memory Room 3 SQLite Test Suite (Completed)
Tested Room 3 DAOs (`ConversationDao`, `MessageDao`, `HistoryDao`, `FavoriteDao`) using `Room.inMemoryDatabaseBuilder<AppDatabase>()` on Desktop JVM with `BundledSQLiteDriver`.

### Step C: In-Memory Fakes Architecture (In Progress)
- [x] `FakeSecurePreferences` (In-memory `SecurePreferences` implementation for AI and preference testing)
- [ ] `FakeSearchRepository`
- [ ] `FakeTocRepository`
- [ ] `FakeContentRepository`

### Step D: Code Coverage Reporting (Completed)
Jacoco plugin configured in `:shared` via `jacocoDesktopTestReport` task. HTML and XML coverage reports generated at:
`shared/build/reports/jacoco/jacocoDesktopTestReport/html/index.html`

---

## 4. Execution Commands

```bash
# Primary local test command (143 tests in ~4.2s)
./gradlew :shared:desktopTest --stacktrace

# Generate Jacoco code coverage report (HTML + XML)
./gradlew :shared:jacocoDesktopTestReport --stacktrace

# Live diagnostic smoke test against root SQLite DBs
./gradlew :desktopApp:run --console=plain

# Fast compilation / syntax check without running tests
./gradlew :shared:compileKotlinDesktop --stacktrace
```
