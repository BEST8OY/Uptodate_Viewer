# Add project specific ProGuard rules here.

# Kotlin Serialization
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod

-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * implements kotlinx.serialization.KSerializer { *; }
-keep,includedescriptorclasses class com.clinref.app.**$$serializer { *; }
-keepclassmembers class com.clinref.app.** { *** Companion; }
-keepclasseswithmembers class com.clinref.app.** { kotlinx.serialization.KSerializer serializer(...); }
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
-dontwarn ai.koog.utils.io.**

# Koog native class-based tools (zero reflection required)
-keep class com.clinref.app.data.tools.** { *; }

# Room database (3.0)
-keep class * extends androidx.room3.RoomDatabase { <init>(); }
-keep @androidx.room3.Entity class *
-keep @androidx.room3.Database class *

# OpenTelemetry (Koog transitive)
-keep class io.opentelemetry.** { *; }
-dontwarn com.google.auto.value.AutoValue**
-dontwarn io.opentelemetry.api.incubator.**
-dontwarn io.opentelemetry.sdk.metrics.internal.descriptor.**
-dontwarn io.opentelemetry.sdk.common.**
-dontwarn io.opentelemetry.api.internal.**
-dontwarn org.osgi.**

# Ktor (Koog transitive)
-keep class io.ktor.** { *; }
-dontwarn java.lang.management.**
-dontwarn io.ktor.**


