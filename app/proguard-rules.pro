# Add project specific ProGuard rules here.

# Kotlin Serialization
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod

-keep,includedescriptorclasses class com.uptodate.viewer.**$$serializer { *; }
-keepclassmembers class com.uptodate.viewer.** { *** Companion; }
-keepclasseswithmembers class com.uptodate.viewer.** { kotlinx.serialization.KSerializer serializer(...); }

# Hilt
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Compose
-dontwarn androidx.compose.**

# WebView JavaScript Interface
-keepclassmembers class com.uptodate.viewer.ui.content.JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
