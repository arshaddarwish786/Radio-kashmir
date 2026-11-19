plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Each GitHub build gets a higher version number, so a new APK installs over the old one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()

android {
    namespace = "com.radiokashmir.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.radiokashmir.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 10 + buildNumber
        versionName = "2.0.$buildNumber"
    }

    signingConfigs {
        create("release") {
            // A fixed key so every build can be installed as an update of the last one.
            storeFile = file("radiokashmir.jks")
            storePassword = "wadi-radio-2026"
            keyAlias = "radiokashmir"
            keyPassword = "wadi-radio-2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")
    implementation("com.google.guava:guava:33.3.1-android")
}
