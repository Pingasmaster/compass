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

    // Required by leakcanary-android-core (>=3.0-alpha-9), which ships an
    // adaptive-icon launcher resource. Adaptive icons need API 26+. The
    // benchmark module's default minSdk (1) rejects it at resource link.
    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
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
    // Benchmark classes need to launch the app's MainActivity under test.
    implementation(project(":app"))

    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}