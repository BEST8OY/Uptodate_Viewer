# ClinRef — Compose Multiplatform (CMP) Migration & Architecture Roadmap

This document serves as the authoritative, phased TODO checklist and execution blueprint for migrating the **ClinRef** clinical reference and AI assistant application from an Android-only Jetpack Compose app into a unified **Compose Multiplatform (CMP)** codebase targeting **Android** and **Desktop (JVM: Linux/macOS/Windows)** (with iOS deferred).

---

## 1. Architectural Strategy & Target Topology

```
+-----------------------------------------------------------------------------------+
|                                      :shared                                      |
|                                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  | commonMain (Pure Multiplatform)                                             |  |
|  |                                                                             |  |
|  | - Compose M3 Expressive UI & Adaptive Layouts (Phone, Tablet, Desktop)      |  |
|  | - Navigation 3 NavDisplay & Top-Level Independent Peer Roots                |  |
|  | - ViewModels (MVI StateFlows, UI State, Coroutine Scopes)                   |  |
|  | - Koog AI Agent Pipeline (ClinicalAgentStrategy, Safety, Accumulator)       |  |
|  | - Domain Models & Unified Clinical Citation Hierarchy (ClinicalSource)      |  |
|  | - Repositories (Content, Search, Toc, History, Favorites, Conversation)    |  |
|  | - Room 3 AppDatabase (Conversations, Messages, History, Favorites)          |  |
|  | - Raw SQLite Query Engine (SearchDao, TocDao, ContentDao via SQLiteDriver)  |  |
|  | - Pure KMP Utilities (kzstd Zstandard Decompression, kotlinx.serialization)|  |
|  +-----------------------------------------------------------------------------+  |
|                                                                                   |
|  +--------------------+   +-----------------------+                               |
|  | androidMain        |   | desktopMain (JVM)     |                               |
|  | - Android WebView  |   | - Desktop WebView     |                               |
|  | - EncryptedPrefs   |   | - AES-256 Key File    |                               |
|  | - SAF Tree Picker  |   | - Swing/AWT Article   |                               |
|  | - System Bar Insets|   | - Coroutines Swing    |                               |
|  +--------------------+   +-----------------------+                               |
+-----------------------------------------------------------------------------------+
             ▲                                ▲
             │                                │
  +--------------------+            +--------------------+
  |    :androidApp     |            |    :desktopApp     |
  | (Android Launcher) |            |   (JVM Launcher)   |
  | MainActivity.kt    |            |     Main.kt        |
  +--------------------+            +--------------------+
```

---

## 2. Master Migration Checklist

### Phase 1: Gradle Build System & Dependency Modernization
- [x] **1.1. Version Catalog Alignment (`gradle/libs.versions.toml`)**
  - [x] Added Koin (`4.2.2`) dependencies: `koin-core`, `koin-compose`, `koin-compose-viewmodel`.
  - [x] Added Ksoup (`0.6.0`) multiplatform HTML dependency.
  - [x] Verified `androidx.sqlite:sqlite-bundled` and `androidx.room3` are aligned.
  - [x] Verified `org.meshtastic:kzstd:0.1.2` for pure KMP Zstandard payload decompression.
- [x] **1.2. Shared Module Build Configuration (`shared/build.gradle.kts`)**
  - [x] Added `jvm("desktop")` target with JVM 17 compiler options alongside `android`.
  - [x] Added KMP dependencies in `commonMain` (Koog, Serialization, kzstd, ksoup, sqlite-bundled, koin-core).
  - [x] Added `desktopMain` and `androidMain` dependencies (`koog-http-client-okhttp`, `koog-utils-jvm`).
  - [x] Configured `utils-android` exclusion to prevent duplicate class conflicts.

---

### Phase 2: Cross-Platform Database & SQLite Query Engine
- [x] **2.1. Raw SQLite Driver Migration (`DatabaseManager`)**
  - [x] Implemented `PlatformFileSystem` expect/actual for cross-platform file/directory operations.
  - [x] Implemented multiplatform `DatabaseManager` in `shared` using `androidx.sqlite.driver.bundled.BundledSQLiteDriver`.
  - [x] Created `SqliteExtensions.kt` with `useQuery`, `useQueryFirstOrNull`, and `useExecute` helper methods.
- [x] **2.2. DAO Modernization (Replacing Android `Cursor` with `SQLiteConnection`)**
  - [x] Refactored `SearchDao.kt` to use `SQLiteConnection.useQuery` and `useQueryFirstOrNull` (zero cursor leaks, hex blob parsing via pure Kotlin).
  - [x] Refactored `TocDao.kt` to use `SQLiteConnection.useQuery` (TOC tree and video index lookups).
  - [x] Refactored `ContentDao.kt` to use `SQLiteConnection.useQueryFirstOrNull` (Zstd asset decoding).
  - [x] Refactored `AssetDao.kt` to use `SQLiteConnection.useQueryFirstOrNull` (Graphic JSON decoding).

---

### Phase 3: AI Domain Layer & Koog Multiplatform Pipeline
- [x] **3.1. Migrated Domain Suite to `shared` (`commonMain`)**
  - [x] Pure domain models: `Audience`, `Topic`, `SearchResult`, `TocItem`, `GraphicData`, `HistoryEntry`, `FavoriteEntry`.
  - [x] AI domain models: `ClinicalSource`, `AiConfiguration`, `PatientProfile`, `AiProvider`, `ProviderSettings`, `SystemPrompt`, `AiJsonUtils`.
  - [x] All 7 AI providers: `OpenAIProvider`, `AnthropicProvider`, `GoogleProvider`, `MistralAIProvider`, `OllamaProvider`, `OpenRouterProvider`, `AiProviderFactory`.
  - [x] Autonomous ReAct state machine: `ClinicalAgentStrategy`, `SafetyValidator`, `TurnContextAccumulator`, `StreamingManager`, `ReliabilityManager`.
- [x] **3.2. Pure Kotlin HTML Table to Markdown Transformation**
  - [x] Eliminated JVM-only `org.jsoup:jsoup` dependency in `MedicalDatabaseTools.kt`.
  - [x] Implemented pure Kotlin regex-based table parser and markdown pipe generator matching Python prototype test expectations.
- [x] **3.3. Cross-Platform Logging & Tracing**
  - [x] Implemented `PlatformLogger` expect/actual (Android Logcat and Desktop console/stderr).
  - [x] Decoupled `SecureLogger` and `PlatformTraceLogWriter` from Android `Log`.
  - [x] Decoupled `KoogAgentFactory` from Android `Context` and Hilt.

---

### Phase 4: Repositories & Room 3 Multiplatform
- [x] **4.1. Repositories Migration to `shared`**
  - [x] `SearchRepository`, `TocRepository`, `ContentRepository`, `AssetRepository`.
  - [x] `ConversationRepository`, `HistoryRepository`, `FavoriteRepository`.
  - [x] Removed all `@Inject` and `@Singleton` annotations in favor of constructor injection.
- [x] **4.2. Room Data Layer**
  - [x] Room 3 entities: `ConversationEntity`, `MessageEntity`, `HistoryEntity`, `FavoriteEntity`.
  - [x] Room 3 DAOs: `ConversationDao`, `MessageDao`, `HistoryDao`, `FavoriteDao`.
  - [x] `RoomChatHistoryProvider` in `commonMain` using `MessageDao` and `KoogClock`.

---

### Phase 5: Dependency Injection & Secure Storage
- [x] **5.1. Multiplatform Secure Preferences**
  - [x] Defined `SecurePreferences` interface in `shared/src/commonMain/kotlin/com/clinref/shared/secure/`.
  - [x] Implemented `DesktopSecurePreferences` in `desktopMain` with AES-256-GCM encryption in `~/.clinref/`.
  - [x] Updated Android `SecurePreferences` to implement `SecurePreferences` interface.
- [x] **5.2. Koin Dependency Injection Modules**
  - [x] Defined `commonDatabaseModule`, `commonRepositoryModule`, and `commonAiModule` in `KoinModules.kt`.
  - [x] Defined `desktopPlatformModule` and `desktopAppModules` in `DesktopModules.kt`.
- [x] **5.3. Desktop Entrypoint & Diagnostics**
  - [x] Created `DesktopApp.kt` runner in `desktopMain` to execute standalone diagnostics against root SQLite DBs.

---

### Phase 6: UI Layer, Navigation 3 & Adaptive Design (Next Steps)
- [x] **6.1. Move ViewModels to Multiplatform**
  - [x] Update ViewModels to use Koin injection and standard `androidx.lifecycle.ViewModel`.
  - [x] Decouple `ChatViewModel` from Android `ClipboardManager` and `Toast` (use Compose `LocalClipboardManager` and `SnackbarHostState`).
  - [x] All 10 ViewModels ported to `shared/src/commonMain/kotlin/com/clinref/app/ui/` with zero Android dependencies.
  - [x] All UI supporting models (`OutlineSection`, `GraphicUiState`, `ThemeColors`, `CssBuilder`) ported to `shared`.
  - [x] Registered `commonViewModelModule` in Koin and switched all screens from `hiltViewModel()` to `koinViewModel()`.
- [x] **6.2. Navigation 3 & Adaptive Design**
  - [x] Implemented multiplatform `BackHandler` (`expect`/`actual`) across Android and Desktop.
  - [x] Refactored all screens (`TocScreen`, `ContentScreen`, `HistoryScreen`, `FavoritesScreen`, `ConversationListScreen`, `SetupScreen`, `NavGraph`) to use common `BackHandler`.
  - [x] Refactored `ContributorsDialog` to pure Compose `LazyColumn` eliminating Android WebView dependency.
  - [x] Decoupled `ChatScreen` from `LocalContext` and Android `Toast`, migrating to Compose `SnackbarHostState`.
  - [x] Implemented adaptive layout in `NavGraph.kt` using `BoxWithConstraints`:
    - Compact screens (< 600dp): Material 3 bottom `NavigationBar` with system bar insets.
    - Expanded / Desktop screens (>= 600dp): Material 3 vertical `NavigationRail` maximizing vertical clinical reading area.
  - [x] Preserved Navigation 3 Independent Peer Roots pattern (`toDecoratedEntries`, `listOf(topLevelRoute)`).
- [x] **6.3. Article WebView Multiplatform Abstraction**
  - [x] Defined platform-agnostic `ArticleWebViewController` with callback binding for section jumping, in-page search, and action interception.
  - [x] Defined pure Kotlin `scrollToSectionJs(sectionId)` utility.
  - [x] Defined `expect @Composable fun HtmlContentWebView(...)` in `shared` `commonMain`.
  - [x] Implemented Android `actual` with hardware-accelerated Chrome `WebView`, `NestedScrollWebView`, and `JsBridge` in `shared` `androidMain`.
  - [x] Implemented Desktop `actual` with `SwingPanel` + `JEditorPane` + `JScrollPane` + hyperlink routing + text search highlighter in `shared` `desktopMain`.
  - [x] Cleaned up duplicate WebView files in `:app` with zero symbol collisions.
  - [x] Verified all 10 ViewModels and `ArticleWebViewController` in `DesktopApp.kt`.

---

### Phase 7: Dedicated Desktop Launcher Module & Production KMP Topology
- [x] **7.1. JetBrains Official KMP Multi-Module Structure**
  - [x] Created dedicated `:desktopApp` launcher module depending on `:shared` as recommended by official JetBrains documentation.
  - [x] Configured `settings.gradle.kts` to include `:app`, `:shared`, and `:desktopApp`.
  - [x] Added `application` plugin and configured JVM 17 release compatibility for OpenJDK 27 runtime.
  - [x] Integrated `kotlinx-coroutines-swing` providing `Dispatchers.Main` on Desktop JVM (AWT Event Dispatch Thread).
- [x] **7.2. Room 3 KMP with KSP Code Generation**
  - [x] Configured `@ConstructedBy(AppDatabaseConstructor::class)` and `expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase>`.
  - [x] Configured KSP processor (`kspDesktop(libs.room3.compiler)`) generating `AppDatabase_Impl` and constructor for desktop target.
  - [x] Verified Room database creation with `BundledSQLiteDriver` and automatic home directory fallback.
- [x] **7.3. Multiplatform Navigation 3 State Serialization**
  - [x] Configured `SavedStateConfiguration` with `SerializersModule` handling `NavKey` open polymorphism (`subclass(...)`).
  - [x] Converted `topLevelRoute` state saving to type-safe Compose `rememberSaveable` with route-level `Saver`.
- [x] **7.4. Verification & Diagnostics**
  - [x] Migrated unit test suite to `shared/src/commonTest/` (101/101 tests passing in ~3.4s via `./gradlew :shared:desktopTest`).
  - [x] Ran `./gradlew :desktopApp:run` against all 6 root SQLite databases:
    - Search: 202 results for 'asthma'
    - TOC: 31 root items
    - ViewModels: 10/10 resolved successfully via Koin DI
    - ArticleWebViewController: JavaScript generation and action dispatcher validated
- [x] **7.5. Full DI Unification & Stable Module Dependencies**
  - [x] Maintained standard, rock-solid Gradle `project(":shared")` dependencies across `:androidApp` and `:desktopApp` (zero incubating warnings).
  - [x] Retired Dagger Hilt entirely from `:androidApp` (removed plugin, compiler, and runtime dependencies).
  - [x] Migrated `MainActivity.kt` and `ClinRefApp.kt` to 100% Koin injection (`by inject()`).
  - [x] Eliminated all duplicate `domain`, `repository`, `data` (DAOs), and `util` classes from `:androidApp` in favor of `:shared`.

---

### Phase 8: Screen Elevation to `commonMain` & Desktop GUI Launcher (Future TODO)

**Objective**: Elevate all UI composables from `:app/src/main/java/com/clinref/app/ui` into `:shared/src/commonMain/kotlin/com/clinref/app/ui`, establish a unified root composable `ClinRefApp()`, and launch a native Compose Desktop window in `:desktopApp`.

- [ ] **8.1. Platform Directory & Storage Access Abstraction (`SetupScreen.kt`)**
  - [ ] Define cross-platform `DirectoryPicker` expect/actual or Koin platform service:
    - `androidMain`: Wraps `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree())` with Android SAF and `MANAGE_EXTERNAL_STORAGE` permission check.
    - `desktopMain`: Uses Swing `JFileChooser` (with `DIRECTORIES_ONLY`) or native desktop file dialog.
  - [ ] Port `SetupScreen.kt` to `shared/src/commonMain/kotlin/com/clinref/app/ui/setup/`.

- [ ] **8.2. Multiplatform Graphic Sheet & Zoom (`GraphicSheet.kt`)**
  - [ ] Replace direct Android `android.webkit.WebView` usage in `GraphicSheet.kt` with the shared multiplatform `HtmlContentWebView` abstraction (or Compose Multiplatform canvas/image viewer for graphic tables and SVG/PNG assets).
  - [ ] Port `GraphicSheet.kt` and `components/` to `shared/src/commonMain/kotlin/com/clinref/app/ui/content/`.

- [ ] **8.3. Multiplatform Dynamic Theming & Accessibility Abstractions**
  - [ ] Abstract dynamic color logic in `Theme.kt`:
    - `androidMain`: Queries Android 12+ `dynamicDarkColorScheme` / `dynamicLightColorScheme`.
    - `desktopMain` / `commonMain`: Uses default Material 3 color schemes (`darkColorScheme`, `lightColorScheme`) without requiring `LocalContext`.
  - [ ] Abstract spoken accessibility announcements in `ContentScreen.kt`:
    - Move `AccessibilityManager` announcement logic behind expect/actual `announceForAccessibility(message: String)`.
  - [ ] Port `Theme.kt`, `Color.kt`, and `Type.kt` into `shared/src/commonMain/kotlin/com/clinref/app/ui/theme/`.

- [ ] **8.4. Screen & Navigation Graph Elevation to `commonMain`**
  - [ ] Move all remaining screen composables to `shared/src/commonMain/kotlin/com/clinref/app/ui/`:
    - `NavGraph.kt` (preserves Navigation 3 Independent Peer Roots and adaptive `NavigationRail` / `NavigationBar` layout).
    - `ChatScreen.kt` and `chat/components/` (`MessageItem.kt`, `ModelSelectorSheet.kt`, `ClinicalReferencesSection.kt`).
    - `ContentScreen.kt` and `ContributorsDialog.kt`.
    - `TocScreen.kt`.
    - `HistoryScreen.kt`.
    - `FavoritesScreen.kt`.
    - `ConversationListScreen.kt`.
    - `AiSettingsScreen.kt`.
    - `search/SearchResultsContent.kt`.
  - [ ] Define unified root composable in `commonMain`:
    ```kotlin
    @Composable
    fun ClinRefApp(databaseManager: DatabaseManager = koinInject()) {
        ClinRefTheme {
            NavGraph(databaseManager = databaseManager)
        }
    }
    ```

- [ ] **8.5. Desktop GUI Window Launcher (`:desktopApp`)**
  - [ ] Configure Compose Multiplatform desktop window in `desktopApp/src/main/kotlin/com/clinref/desktop/Main.kt`:
    ```kotlin
    fun main(args: Array<String>) {
        if (args.contains("--cli") || args.contains("--diag")) {
            DesktopApp.main(args)
        } else {
            application {
                Window(
                    onCloseRequest = ::exitApplication,
                    title = "ClinRef — Clinical Reference & AI Assistant"
                ) {
                    ClinRefApp()
                }
            }
        }
    }
    ```
  - [ ] Update `:androidApp/src/main/java/com/clinref/app/MainActivity.kt` to become a minimal launcher:
    ```kotlin
    class MainActivity : ComponentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            enableEdgeToEdge()
            setContent { ClinRefApp() }
        }
    }
    ```

- [x] **8.6. Final Symmetry Naming Convention**
  - [x] Renamed `:app` module directory to `:androidApp` in `settings.gradle.kts`, `androidApp/build.gradle.kts`, and CI workflows, fully mirroring the official JetBrains Quickstart layout (`:androidApp`, `:desktopApp`, `:shared`).

