# R8 rules for the release build.
#
# The heavy lifting is done by consumer rules bundled inside the libraries
# themselves: Retrofit, OkHttp/Okio, Moshi, Room, and kotlinx.coroutines all
# ship META-INF proguard/R8 rules. In particular, Moshi's bundled rules keep
# the KSP-generated `*JsonAdapter` classes for every @JsonClass model (the
# adapters are found reflectively by Moshi.adapter()), and the generated
# adapters access properties directly, so the model classes themselves may be
# renamed freely. Manifest components (MainActivity, the IME service, the
# Application class) are kept automatically via the AAPT-generated rules.
#
# Only project-specific rules live here.

# Keep file/line info so release crash stack traces from the IME process are
# actionable; hide the original source paths in the renamed output.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# A keyboard sees everything the user types: make sure chatty log levels are
# stripped from release bytecode entirely, not just filtered at runtime.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
