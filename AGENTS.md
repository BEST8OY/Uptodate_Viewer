# AGENTS.md — ClinRef (Uptodate Viewer)

## Build

Single-module Android app (`:app`). No multi-module complexity.

**No Android SDK on this dev machine** — Gradle builds are not available locally. CI verifies via `./gradlew assembleRelease --stacktrace`.

**No tests exist.** No lint config, no ktlint/detekt.

## Toolchain (non-obvious versions)

- AGP 9.2.1, Kotlin 2.4.0, Gradle 9.6.1
- `compileSdk = 37`, `minSdk = 31`, JVM target 17
- CI uses JDK 21 (Temurin)
- Hilt annotation processing uses **KSP** (not kapt)
- `libs.versions.toml` is the single source of truth for dependency versions

## Architecture

```
com.clinref.app/
├── ClinRefApp.kt          # @HiltAndroidApp Application
├── MainActivity.kt        # @AndroidEntryPoint, edge-to-edge, single Activity
├── di/AppModule.kt        # Hilt @Module — provides DAOs
├── data/                  # DatabaseManager + DAOs (raw SQLite, NOT Room)
├── domain/                # Data classes (serializable models)
├── repository/            # Repository layer (uses DAOs)
├── ui/                    # Compose screens + ViewModels
│   ├── navigation/        # Navigation 3 (NavDisplay, NavKey routes)
│   ├── content/           # WebView content viewer + JsBridge
│   ├── toc/               # Table of contents
│   ├── search/            # Search
│   ├── favorites/         # Favorites
│   ├── history/           # Reading history
│   ├── setup/             # First-run DB directory picker
│   └── theme/             # ClinRefTheme
└── util/                  # GzipUtil, HtmlNormalizer, TimeUtils
```

## Key conventions

### Navigation
Uses **Navigation 3** (`androidx.navigation3`), NOT the older Navigation Compose. Routes are `@Serializable data object/data class` implementing `NavKey`. See `ui/navigation/NavGraph.kt`.

### Data layer
**Raw SQLite** via `SQLiteDatabase.openDatabase()` — NOT Room. `DatabaseManager` opens external `.sqlite`/`.db` files selected by the user at first run. DAOs are plain classes (not Room DAOs). The app expects 6 specific DB files in the user-selected directory.

### WebView content
Content is rendered in WebView. `JsBridge` exposes a `@JavascriptInterface` method (`appAction`) for JS-to-native communication. ProGuard keep rules for JsBridge are in `proguard-rules.pro` — keep them if modifying JsBridge.

### Compose
- Material3 alpha (`1.5.0-alpha23`) — experimental APIs are common here
- `ExperimentalMaterial3ExpressiveApi` is used in several composables
- Compose BOM `2026.06.01`
- Edge-to-edge is enabled in MainActivity

### Serialization
`kotlinx.serialization` for route definitions and domain models. ProGuard rules keep `$$serializer` classes and `Companion` members for `com.clinref.app.**`.

## Gotchas

- **No bundled databases.** The app is useless without user-provided SQLite files. `SetupScreen` handles first-run directory selection.
- **CI only builds release APK** — no test step, no lint step. The only CI verification is `assembleRelease`.
- **`resolutionStrategy.force`** pins `compose-group-mapping:2.4.0` in `build.gradle.kts` — this is a Compose/Kotlin compatibility fix, don't remove without understanding the version matrix.
- **`useLegacyPackaging = true`** for JNI libs — needed for native library loading from assets.
- **`@SuppressLint("SetJavaScriptEnabled")`** is only justified in `HtmlContentWebView` (has JsBridge). Don't add it to other WebViews — `GraphicSheet` explicitly disables JS, and `ContributorsDialog` loads static HTML.
