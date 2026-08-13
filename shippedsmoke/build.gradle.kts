plugins {
    id("com.android.test")
}

/*
 * Shipped-config smoke: drives the release APK EXACTLY as users receive it.
 * Self-instrumenting so R8 keeps are not loosened by androidTest keep rules.
 */
android {
    namespace = "com.compass.app.shippedsmoke"
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
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.errorprone.annotations)
}
