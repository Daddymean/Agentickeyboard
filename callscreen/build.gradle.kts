plugins { alias(libs.plugins.android.application) }

android {
    namespace = "io.github.daddymean.callscreen"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.daddymean.callscreen"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    lint { abortOnError = true }
}

dependencies { testImplementation(libs.junit) }
