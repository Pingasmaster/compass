plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.compass.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.compass.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 18
        versionName = "1.0.17"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // WARNING: release APK is signed with the debug keystore for local
            // iteration convenience. Wire a real signingConfig before Play
            // distribution (env-var-backed keystore, or Play App Signing).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)

    implementation(libs.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.material3.adaptive)
    implementation(libs.graphics.shapes)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
