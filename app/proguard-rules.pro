# Add project specific ProGuard rules here.

# Kotlin Serialization
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod

-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * implements kotlinx.serialization.KSerializer { *; }
-keep,includedescriptorclasses class com.clinref.app.**$$serializer { *; }
-keepclassmembers class com.clinref.app.** { *** Companion; }
-keepclasseswithmembers class com.clinref.app.** { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers class com.clinref.app.**$$serializer { *; }
-keep class com.clinref.app.ui.navigation.** { *; }
-keep class com.clinref.app.domain.** { *; }

# Hilt
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Compose
-dontwarn androidx.compose.**

# WebView JavaScript Interface
-keepclassmembers class com.clinref.app.ui.content.JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Koog AI agents — broad keep for now; narrow once reflection surface is known
-keep class ai.koog.** { *; }
-keep class ai.koog.utils.io.** { *; }
-keep class ai.koog.utils.concurrency.** { *; }
-keep class ai.koog.utils.time.** { *; }
-keep class ai.koog.utils.system.** { *; }
-dontwarn ai.koog.utils.io.**

# Koog tool discovery: keep MedicalDatabaseTools and its @Tool/@LLMDescription annotations
# so reflection-based ToolSet registration works at runtime
-keep class com.clinref.app.data.MedicalDatabaseTools { *; }
-keepattributes RuntimeVisibleAnnotations

# Room database (3.0)
-keep class * extends androidx.room3.RoomDatabase { <init>(); }
-keep @androidx.room3.Entity class *
-keep @androidx.room3.Database class *

# OpenTelemetry (Koog transitive)
-dontwarn com.google.auto.value.AutoValue**
-dontwarn io.opentelemetry.api.incubator.metrics.**
-dontwarn io.opentelemetry.sdk.metrics.internal.descriptor.**
-dontwarn io.opentelemetry.sdk.common.**
-dontwarn io.opentelemetry.api.internal.**

# Ktor (Koog transitive)
-dontwarn java.lang.management.**
-dontwarn io.ktor.**


