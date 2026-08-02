plugins {
    id("com.android.test")
}

android {
    namespace = "com.compass.app.baselineprofile"
    compileSdk = 37

    targetProjectPath = ":app"

    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        minSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // :app has an api flavor dimension; always exercise the future APK.
        missingDimensionStrategy("api", "future")
    }

    flavorDimensions += "api"
    productFlavors {
        create("future") {
            dimension = "api"
        }
    }

    buildTypes {
        create("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testOptions.managedDevices.localDevices {
        // Match dustvalve_next / calc / STT: Pixel 7a / API 37 Google APIs /
        // 16 KB pages, testing the arm64 APK via NDK translation.
        register("pixel7aApi37") {
            device = "Pixel 7a"
            apiLevel = 37
            systemImageSource = "google"
            pageAlignment = com.android.build.api.dsl.ManagedVirtualDevice.PageAlignment.FORCE_16KB_PAGES
            testedAbi = "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
