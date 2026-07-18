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

# Room database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
