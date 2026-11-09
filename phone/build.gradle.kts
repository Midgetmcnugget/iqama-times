import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Phone home-screen widget showing the same Masjidbox iqamah times as the TV plugin.
plugins {
    alias(libs.plugins.android.application)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "uk.iqama.widget"
    compileSdk = libs.versions.compileSdk.get().toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "uk.iqama.widget"
        minSdk = 26
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    // Same fixed key as the TV app, so each new build installs over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../iqama/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation("androidx.work:work-runtime:2.10.0")
}
