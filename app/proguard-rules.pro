# Project-specific release hardening rules.

# Keep Moshi models and generated adapters stable for Retrofit parsing.
-keep class com.example.network.**JsonAdapter { *; }
-keep class com.example.network.GenerateContentRequest { *; }
-keep class com.example.network.GenerateContentResponse { *; }
-keep class com.example.network.Content { *; }
-keep class com.example.network.Part { *; }
-keep class com.example.network.Candidate { *; }
-keep class com.example.network.GenerationConfig { *; }
-keep class com.example.network.GrammarCorrectionResponse { *; }
-keep class com.example.network.SuggestionsResponse { *; }
-keep class com.example.network.ToneAnalysisResponse { *; }

# Retrofit interfaces are invoked reflectively by Retrofit.
-keep interface com.example.network.GeminiApiService { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# Keep Room entities clear for migrations and schema validation.
-keep class com.example.db.** { *; }

# Preserve source line numbers for crash diagnosis while still allowing shrinking.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
