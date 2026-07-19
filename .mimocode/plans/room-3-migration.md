# Room 2.8 → 3.0 Migration Plan

## Status: BLOCKED

**Blocked by**: No KSP version exists for Kotlin 2.4.0. Room 3.0's KSP processor fails with `[MissingType]` when used with KSP 2.3.9 + Kotlin 2.4.0. Will unblock when `com.google.devtools.ksp` releases a version for Kotlin 2.4.0.

## Correct Room 2.8.4 KMP Setup (Current)

Room 2.8.4 supports KMP and requires the `@ConstructedBy` pattern:

```kotlin
@Database(...)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() { ... }

@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
```

Dependencies:
- `androidx.room:room-runtime:2.8.4`
- `androidx.room:room-compiler:2.8.4` (KSP)
- `androidx.sqlite:sqlite-bundled:2.7.0`
- Room Gradle plugin: `androidx.room`

### Files affected

| File | Current (2.x) | Changes needed for 3.0 |
|------|--------------|----------------------|
| `libs.versions.toml` | `room = "2.8.4"` | `room = "3.0.0"`, new artifacts |
| `app/build.gradle.kts` | `room-runtime`, `room-ktx`, `room-compiler` | `room3-runtime`, `room3-compiler` + add `room3-sqlite-android` driver |
| `AppDatabase.kt` | `androidx.room.*` imports | `androidx.room3.*` imports, `@ColumnTypeConverter` |
| `ConversationDao.kt` | `androidx.room.*` imports | `androidx.room3.*` imports |
| `MessageDao.kt` | `androidx.room.*` imports | `androidx.room3.*` imports |
| `Converters.kt` | `@TypeConverter` | `@ColumnTypeConverter` |
| `DatabaseModule.kt` | `Room.databaseBuilder(context, ...)` | Requires `SQLiteDriver`, no Context needed |
| ProGuard rules | Room 2.x rules | Update to Room 3.x |

---

## Step 1: Update Dependencies

### `libs.versions.toml`

```toml
[versions]
room = "3.0.0"

[libraries]
# Old — REMOVE:
# room-runtime, room-ktx, room-compiler

# New — ADD:
room3-runtime = { group = "androidx.room3", name = "room3-runtime", version.ref = "room" }
room3-compiler = { group = "androidx.room3", name = "room3-compiler", version.ref = "room" }
room3-sqlite-android = { group = "androidx.room3", name = "room3-sqlite-android", version.ref = "room" }
```

### `app/build.gradle.kts`

```kotlin
// Old
implementation(libs.room.runtime)
implementation(libs.room.ktx)
ksp(libs.room.compiler)

// New
implementation(libs.room3.runtime)
ksp(libs.room3.compiler)
implementation(libs.room3.sqlite.android)  // Android SQLite driver
```

Remove the `room.schemaLocation` KSP arg — Room 3.0 uses the Gradle plugin for schema export instead.

Add Room Gradle plugin to root `build.gradle.kts`:
```kotlin
plugins {
    id("androidx.room3") version "3.0.0" apply false
}
```

Add to app `build.gradle.kts`:
```kotlin
plugins {
    id("androidx.room3")
}

room3 {
    schemaDirectory("$projectDir/schemas")
}
```

---

## Step 2: Update Imports (All Files)

Every `androidx.room.*` import becomes `androidx.room3.*`:

```kotlin
// Old
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.OnConflictStrategy
import androidx.room.Delete
import androidx.room.Update
import androidx.room.TypeConverter

// New
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.TypeConverters
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.OnConflictStrategy
import androidx.room3.Delete
import androidx.room3.Update
import androidx.room3.TypeConverter
```

---

## Step 3: Rename `@TypeConverter` → `@ColumnTypeConverter`

### `Converters.kt`

```kotlin
// Old
import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(separator = ",")
    @TypeConverter
    fun toStringList(value: String): List<String> = ...
}

// New
import androidx.room3.ColumnTypeConverter

class Converters {
    @ColumnTypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(separator = ",")
    @ColumnTypeConverter
    fun toStringList(value: String): List<String> = ...
}
```

---

## Step 4: Update DatabaseBuilder — Add SQLiteDriver

Room 3.0 requires a `SQLiteDriver`. For Android, use `AndroidSQLiteDriver`.

### `DatabaseModule.kt`

```kotlin
// Old
import androidx.room.Room

fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
    return Room.databaseBuilder(context, AppDatabase::class.java, "clinref_ai.db")
        .fallbackToDestructiveMigration()
        .build()
}

// New
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver

fun provideDatabase(): AppDatabase {
    val dbFile = context.getDatabasePath("clinref_ai.db")
    return Room.databaseBuilder<AppDatabase>(
        name = dbFile.absolutePath,
        driver = AndroidSQLiteDriver()
    )
    .fallbackToDestructiveMigration(dropAllTables = true)
    .build()
}
```

Note: `Room.databaseBuilder` in 3.0 no longer takes `Context` as first arg. The driver handles file I/O.

---

## Step 5: Verify DAO Compatibility

Room 3.0 requires all DAO functions to be either `suspend` or return a reactive type (`Flow`, `PagingSource`, etc.).

**Our DAOs already satisfy this requirement:**
- `ConversationDao`: `suspend` functions + `Flow<List<>>` — no changes needed
- `MessageDao`: `suspend` functions + `Flow<List<>>` — no changes needed

The only change is import paths.

---

## Step 6: Update ProGuard Rules

```proguard
# Old
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# New
-keep class * extends androidx.room3.RoomDatabase
-keep @androidx.room3.Entity class *
```

---

## Step 7: Room Gradle Plugin (Optional but Recommended)

The Room Gradle Plugin ensures schema files are correctly placed for validation and auto-migrations. Without it, `exportSchema = true` may not work as expected in 3.0.

```kotlin
// settings.gradle.kts or root build.gradle.kts
plugins {
    id("androidx.room3") version "3.0.0" apply false
}

// app/build.gradle.kts
plugins {
    id("androidx.room3")
}

room3 {
    schemaDirectory("$projectDir/schemas")
}
```

Remove the `ksp { arg("room.schemaLocation", ...) }` block — the plugin handles it.

---

## Step 8: Reset Schema Version

Since we're dropping support for Room 2.x users (per requirement), reset the schema:

```kotlin
@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,  // Reset to 1
    exportSchema = true
)
```

Delete the old `app/schemas/` directory and let Room 3.0 regenerate from scratch.

---

## Step 9: Verify Build

```bash
./gradlew assembleRelease --stacktrace
```

Expected: clean build, no import errors, schema exported to `app/schemas/`.

---

## Summary of Changes

| What | Room 2.x | Room 3.0 |
|------|----------|---------|
| Package | `androidx.room` | `androidx.room3` |
| Artifacts | `room-runtime`, `room-ktx`, `room-compiler` | `room3-runtime`, `room3-compiler`, `room3-sqlite-android` |
| TypeConverter | `@TypeConverter` | `@ColumnTypeConverter` |
| DatabaseBuilder | `Room.databaseBuilder(context, cls, name)` | `Room.databaseBuilder<cls>(name, driver)` |
| SQLiteDriver | Built-in (SupportSQLite) | Explicit (`AndroidSQLiteDriver`) |
| Schema export | KSP arg | Gradle plugin `room3 { schemaDirectory(...) }` |
| DAO functions | suspend + Flow OK | suspend + Flow OK (same) |
| Coroutines | Optional via `room-ktx` | Required (built-in) |

## Risk Assessment

- **Low risk**: Our DAOs already use `suspend` + `Flow`, no SupportSQLite APIs, no complex migrations
- **Main breaking change**: Import paths + SQLiteDriver requirement
- **No data migration needed**: We're resetting schema version (destructive migration)
