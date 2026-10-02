# Add project specific ProGuard/R8 rules here.

# Retrace support: preserve source file and line numbers for stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Annotation retention for runtime reflection & serialization
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod

# Keep fields and constructors for serializable models and Navigation 3 route arguments
-keepclassmembers @kotlinx.serialization.Serializable class com.clinref.app.** {
    <fields>;
    <init>(...);
}

# Compose
-dontwarn androidx.compose.**

# Koog AI agents (zero reflection with native SimpleTool)
-dontwarn ai.koog.utils.io.**

# Koog tool parameter descriptions for LLM JSON schema generation
-keep @interface ai.koog.agents.core.tools.annotations.LLMDescription

# Koog native class-based tools (zero reflection required)
-keep class com.clinref.app.data.tools.** { *; }

# Ktor (engine discovery handled by Ktor consumer rules)
-dontwarn java.lang.management.**
-dontwarn io.ktor.**

# OpenTelemetry optional & incubator packages (Koog transitive)
-dontwarn com.google.auto.value.AutoValue**
-dontwarn io.opentelemetry.api.incubator.**
-dontwarn io.opentelemetry.sdk.metrics.internal.descriptor.**
-dontwarn io.opentelemetry.sdk.common.**
-dontwarn io.opentelemetry.api.internal.**
-dontwarn org.osgi.**

# Compile-time static analysis annotations (referenced by Google Tink & OpenTelemetry)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**





