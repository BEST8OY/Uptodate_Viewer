# ProGuard rules for UpToDate Viewer
-keepattributes SourceFile,LineNumberTable

# Keep aircompressor (pure Java Zstd)
-keep class io.airlift.compress.** { *; }
-dontwarn io.airlift.compress.hadoop.**
-dontwarn org.apache.hadoop.**

# Keep JavascriptInterface methods for WebView
-keepclassmembers class com.uptodate.viewer.** {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep @Serializable data classes (only those not covered by serialization plugin)
-keep class com.uptodate.viewer.data.database.content.models.TopicPayload { *; }
-keep class com.uptodate.viewer.data.database.content.models.GraphicGroup { *; }
-keep class com.uptodate.viewer.data.database.content.models.GraphicEntry { *; }
-keep class com.uptodate.viewer.data.database.content.models.GraphicInfo { *; }
-keep class com.uptodate.viewer.data.database.content.models.ContributorGroup { *; }
-keep class com.uptodate.viewer.data.database.content.models.ContributorPerson { *; }
-keep class com.uptodate.viewer.data.database.toc.models.TocPayload { *; }
-keep class com.uptodate.viewer.data.database.toc.models.TocChildJson { *; }
-keep class com.uptodate.viewer.data.database.assets.models.GraphicPayload { *; }
