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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // Benchmark classes need to launch the app's MainActivity under test.
    implementation(project(":app"))

    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}