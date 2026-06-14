# Add project specific ProGuard rules here.

# Kotlin Serialization
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod

-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * implements kotlinx.serialization.KSerializer { *; }
-keep,includedescriptorclasses class com.uptodate.viewer.**$$serializer { *; }
-keepclassmembers class com.uptodate.viewer.** { *** Companion; }
-keepclasseswithmembers class com.uptodate.viewer.** { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers class com.uptodate.viewer.**$$serializer { *; }
-keep class com.uptodate.viewer.ui.navigation.** { *; }
-keep class com.uptodate.viewer.domain.** { *; }

# Hilt
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Compose
-dontwarn androidx.compose.**

# WebView JavaScript Interface
-keepclassmembers class com.uptodate.viewer.ui.content.JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
