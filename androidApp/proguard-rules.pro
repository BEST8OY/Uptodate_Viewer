# Add project specific ProGuard/R8 rules here.

# Retrace support: preserve source file and line numbers for stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin Serialization (library consumer rules handle KSerializer implementations)
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod

# Keep app-specific serializable models, serializers, and navigation routes
-keep,includedescriptorclasses class com.clinref.app.**$$serializer { *; }
-keepclassmembers class com.clinref.app.** { *** Companion; }
-keepclasseswithmembers class com.clinref.app.** { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers class com.clinref.app.domain.** {
    <fields>;
    <init>(...);
}
-keepclassmembers class com.clinref.app.ui.navigation.** {
    <fields>;
    <init>(...);
}

# Compose
-dontwarn androidx.compose.**

# Koog AI agents — allow shrinking and obfuscation of unused framework internals
-keep,allowshrinking,allowobfuscation class ai.koog.** { *; }
-dontwarn ai.koog.utils.io.**

# Koog native class-based tools (zero reflection required)
-keep class com.clinref.app.data.tools.** { *; }

# OpenTelemetry (Koog transitive) — allow shrinking and obfuscation of unused metrics/SDK
-keep,allowshrinking,allowobfuscation class io.opentelemetry.** { *; }
-dontwarn com.google.auto.value.AutoValue**
-dontwarn io.opentelemetry.api.incubator.**
-dontwarn io.opentelemetry.sdk.metrics.internal.descriptor.**
-dontwarn io.opentelemetry.sdk.common.**
-dontwarn io.opentelemetry.api.internal.**
-dontwarn org.osgi.**

# Ktor (Koog transitive) — keep engine container, allow shrinking and obfuscation
-keep class io.ktor.client.engine.** implements io.ktor.client.HttpClientEngineContainer { *; }
-keep,allowshrinking,allowobfuscation class io.ktor.** { *; }
-dontwarn java.lang.management.**
-dontwarn io.ktor.**

# Compile-time static analysis annotations (referenced by Google Tink & OpenTelemetry)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**




