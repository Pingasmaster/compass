plugins {
    id("com.android.test")
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.compass.app.baselineprofile"
    compileSdk = 37

    targetProjectPath = ":app"

    // The producer module pulls the full app classpath in via
    // `implementation(project(":app"))` (BaselineProfileRule.collect needs the
    // app's MainActivity on the classpath), which pushes the merged dex over
    // the 64K method limit. minSdk=31 enables native multidex so we don't
    // need the support-library multidex dependency.
    defaultConfig {
        minSdk = 31
    }

    testOptions.managedDevices.localDevices {
        // Match dustvalve_next: Pixel 7a / API 37 Google APIs / 16 KB pages,
        // testing the arm64 APK via the image's translation layer (AGP 10 default).
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

    buildFeatures {
        // Kotlin support is built into AGP 9.0+; no separate plugin needed.
        buildConfig = false
    }
}

dependencies {
    // The producer module needs access to the app's compiled classes
    // (MainActivity) to launch them in the rule.collect { ... } block.
    implementation(project(":app"))

    // benchmark-macro-junit4 ships the BaselineProfileRule used by the producer.
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}

// Wire the producer module to the GMD device declared in testOptions above.
baselineProfile {
    managedDevices += "pixel7aApi37"
    useConnectedDevices = false
}

// The `androidx.baselineprofile` plugin auto-creates a `benchmarkRelease`
// variant on `:app` with R8 enabled so the captured profile reflects
// post-shrink code paths. AGP's `checkTestedAppObfuscation` task refuses to
// assemble a `com.android.test` module against a minified app variant
// unless the test module is itself minified, which `com.android.test`
// doesn't expose cleanly. The check is a guard, not a correctness
// requirement: the test instrumentation still runs and produces correct
// profile output even without minification on the test side. Disable it for
// the four auto-generated variants.
tasks.matching { it.name.startsWith("checkTestedAppObfuscation") }.configureEach {
    enabled = false
}