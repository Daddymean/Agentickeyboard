// :core — the shared contract of the context platform: Room schema (Event,
// Episode), the Snapshot model, the IContextService AIDL, and ContextClient,
// the binder SDK the keyboard and collectors use to reach :context-app.
plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.androidx.room)
}

android {
  namespace = "dev.context.core"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    minSdk = 34
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    consumerProguardFiles("consumer-rules.pro")
  }

  buildFeatures { aidl = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Same reasoning as :app — the Room plugin owns the schema directory so
// per-variant KSP runs never race on one file.
room { schemaDirectory("$projectDir/schemas") }

dependencies {
  api(libs.androidx.room.runtime)
  api(libs.kotlinx.coroutines.core)
  api(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)
  "ksp"(libs.androidx.room.compiler)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
}
