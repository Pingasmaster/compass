plugins {
    id("com.android.test")
    // The :macrobenchmark module hosts .kt files but does NOT apply
    // `kotlin.compose` (no Compose runtime here), so the Kotlin Android plugin
    // is not auto-applied and `:macrobenchmark:compileDebugKotlin` would fail
    // with "Unresolved reference 'pressHome' / 'startActivityAndWait' / 'device'"
    // because the `MacrobenchmarkScope` receiver on `setupBlock` / `measureBlock`
    // lambdas is never inferred. This is the test-module equivalent of the
    // project's `app/build.gradle.kts` "Known Non-Bug #1": there, the duplicate
    // is fatal because `kotlin.compose` already registered the extension; here
    // no such conflict exists. We omit `version` because the plugin is already
    // on the buildscript classpath via the root `plugins { ... apply false }`.
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.compass.app.macrobenchmark"
    compileSdk = 37

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // Benchmark classes need to launch the app's MainActivity under test.
    implementation(project(":app"))

    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}