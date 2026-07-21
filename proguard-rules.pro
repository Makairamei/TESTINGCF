# =========================
# CLOUDSTREAM PLUGIN PROGUARD RULES
# =========================

# Keep CloudStream API classes (runtime dependencies, not obfuscated)
-keep class com.lagradost.cloudstream3.** { *; }
-keep class com.lagradost.cloudstream3.extractor.** { *; }
-keep class com.lagradost.cloudstream3.plugins.** { *; }

# Keep plugin entry points annotated with @CloudstreamPlugin
-keep @com.lagradost.cloudstream3.plugins.CloudstreamPlugin class * { *; }

# Keep all classes that implement Plugin interface
-keep class * implements com.lagradost.cloudstream3.plugins.Plugin {
    *();
}
-keep class * extends com.lagradost.cloudstream3.plugins.CloudstreamPlugin {
    *();
}

# Keep data classes used in serialization (Jackson/Gson)
-keepclassmembers @com.fasterxml.jackson.annotation.JsonIgnoreProperties class * {
    *;
}
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.JsonProperty <fields>;
}
-keep @kotlinx.serialization.Serializable class * { *; }

# Keep OkHttp (networking)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Keep Jsoup (HTML parser)
-keep class org.jsoup.** { *; }

# Obfuscate and optimize aggressively
-repackageclasses ''
-allowaccessmodification
-overloadaggressively
-mergeinterfacesaggressively

# Remove debug logging in release
-assumenosideeffects class android.util.Log {
    public static *** w(...);
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
