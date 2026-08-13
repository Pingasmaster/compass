plugins {
    id("com.android.test")
    // AGP 9.0+ has built-in Kotlin support, so the `org.jetbrains.kotlin.android`
    // plugin is no longer required. Adding it (or the `kotlin.compose` plugin)
    // fails with "extension already registered with that name" / "no longer
    // required". The benchmark classes use a trailing-lambda
    // `rule.measureRepeated(...) { ... }` so the `MacrobenchmarkScope` receiver
    // is inferred without needing the standalone plugin.
}

android {
    namespace = "com.compass.app.macrobenchmark"
    compileSdk = 37

    targetProjectPath = ":app"

    experimentalProperties["android.experimental.self-instrumenting"] = true

    // Required by leakcanary-android-core (>=3.0-alpha-9), which ships an
    // adaptive-icon launcher resource. Adaptive icons need API 26+. The
    // harness exercises the future flavor (minSdk 37) on GMD API 37.
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_26
        targetCompatibility = JavaVersion.VERSION_26
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26)
        }
    }

    testOptions.managedDevices.localDevices {
        // Same GMD as :baselineprofile / dustvalve_next.
        register("pixel7aApi37") {
            device = "Pixel 7a"
            apiLevel = 37
            systemImageSource = "google"
            pageAlignment = com.android.build.api.dsl.ManagedVirtualDevice.PageAlignment.FORCE_16KB_PAGES
            testedAbi = "arm64-v8a"
        }
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}

tasks.matching { it.name.startsWith("checkTestedAppObfuscation") }.configureEach {
    enabled = false
}
