plugins {
    id("com.android.test")
}

android {
    namespace = "com.compass.app.macrobenchmark"
    compileSdk = 37

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        // Kotlin support is built into AGP 9.0+; no separate plugin needed.
        buildConfig = false
    }
}

dependencies {
    // Benchmark classes need to launch the app's MainActivity under test.
    implementation(project(":app"))

    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}