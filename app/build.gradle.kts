plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.compass.app"
    compileSdk = 37
    // Compile against the 37.1 minor SDK release (API additions only; minor
    // SDKs carry no behavior changes and cannot be targeted - targetSdk
    // stays at the 37 major). Matches calc / Brisky / dustvalve / STT.
    compileSdkMinor = 1

    // Shared by defaultConfig + future flavor offset. build.sh bumps this
    // via sed; future re-reads it on the next Gradle configure.
    val baseVersionCode = 41
    val baseVersionName = "1.0.40"

    defaultConfig {
        applicationId = "com.compass.app"
        targetSdk = 37
        versionCode = baseVersionCode
        versionName = baseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Single codebase, two APKs: compat (Android 8+) and future (Android 17+).
    flavorDimensions += "api"
    productFlavors {
        create("compat") {
            dimension = "api"
            minSdk = 26
            versionNameSuffix = "-legacy"
            // Uses defaultConfig.versionCode so an Android 17 user who
            // somehow installed compat can still upgrade to future.
        }
        create("future") {
            dimension = "api"
            minSdk = 37
            // Higher than compat so sideload/Play prefer this on API 37+.
            versionCode = 1_000_000 + baseVersionCode
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // WARNING: release APK is signed with the debug keystore for local
            // iteration convenience. Wire a real signingConfig before Play
            // distribution (env-var-backed keystore, or Play App Signing).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
        // Required for compat (minSdk 26 + JVM 25). Harmless no-op on future
        // for APIs already present on Android 17; R8 strips unused bits.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
        }
    }

    // Strong skipping is enabled by default since Compose Compiler 2.0 / Kotlin 2.0
    // (ComposeFeatureFlag.StrongSkipping). No composeCompiler {} override needed.
    // See: https://kotlinlang.org/docs/compose-compiler-options.html

    buildFeatures {
        compose = true
        buildConfig = false
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        checkReleaseBuilds = true
        explainIssues = true
        showAll = true
        lintConfig = rootProject.file("config/lint/lint.xml")
    }

    testOptions {
        managedDevices {
            localDevices {
                register("pixel7aApi37") {
                    device = "Pixel 7a"
                    apiLevel = 37
                    systemImageSource = "google"
                    pageAlignment =
                        com.android.build.api.dsl.ManagedVirtualDevice.PageAlignment.FORCE_16KB_PAGES
                    testedAbi = "arm64-v8a"
                }
            }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/detekt-baseline.xml")
    parallel = true
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

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
    implementation(libs.material3.adaptive)
    implementation(libs.graphics.shapes)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)

    // Profile installer is what ships the baseline + startup profiles baked into the
    // release AAB. At install / first launch it copies the profiles from the APK
    // into the profile-cache dir that the OS reads when compiling DEX.
    implementation(libs.androidx.profileinstaller)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)

    // LeakCanary is debug-only; R8 strips it from release builds. Auto-installs via
    // AppStartup so no manual wiring needed.
    debugImplementation(libs.leakcanary.android)

    detektPlugins(libs.detekt.compose)
    lintChecks(libs.lint.slack.checks)
    lintChecks(libs.lint.slack.compose)
}
