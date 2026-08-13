import org.gradle.api.GradleException

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
    val baseVersionCode = 43
    val baseVersionName = "1.0.42"

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

    signingConfigs {
        create("release") {
            val keystoreFile = file("../release-keystore.jks")
            val passwordFile = rootProject.file(".password-signing-keys")
            val requireReleaseSigning = providers
                .gradleProperty("compass.requireReleaseSigning")
                .map { it.equals("true", ignoreCase = true) }
                .orElse(false)
                .get()

            if (keystoreFile.exists() && passwordFile.exists()) {
                storeFile = keystoreFile
                storePassword = passwordFile.readText().trim()
                keyAlias = "compass"
                keyPassword = storePassword
            } else if (requireReleaseSigning) {
                throw GradleException(
                    "release-keystore.jks or .password-signing-keys missing, but " +
                        "compass.requireReleaseSigning=true (set by ./build.sh " +
                        "release path). Refusing to sign release artifacts with " +
                        "the debug key. Place both files at the repo root, or use " +
                        "./build.sh --debug for a local unsigned-of-production build.",
                )
            } else {
                val fallbackMessage = "release-keystore.jks or .password-signing-keys missing - " +
                    "falling back to AGP debug signing for the release variant " +
                    "(ok for --debug / local; production builds must set " +
                    "compass.requireReleaseSigning=true)."
                rootProject.logger.warn(fallbackMessage)
                val debug = signingConfigs.getByName("debug")
                val debugStoreFile = debug.storeFile
                if (debugStoreFile != null && !debugStoreFile.exists()) {
                    debugStoreFile.parentFile.mkdirs()
                    val process = ProcessBuilder(
                        System.getProperty("java.home") + "/bin/keytool",
                        "-genkey", "-noprompt",
                        "-keystore", debugStoreFile.absolutePath,
                        "-alias",
                        requireNotNull(debug.keyAlias) {
                            "debug signingConfig.keyAlias is null"
                        },
                        "-keyalg", "RSA", "-keysize", "2048",
                        "-validity", "10000",
                        "-dname", "CN=Android Debug,O=Android,C=US",
                        "-storepass",
                        requireNotNull(debug.storePassword) {
                            "debug signingConfig.storePassword is null"
                        },
                        "-keypass",
                        requireNotNull(debug.keyPassword) {
                            "debug signingConfig.keyPassword is null"
                        },
                    ).redirectErrorStream(true).start()
                    val output = process.inputStream.bufferedReader().readText()
                    val exitCode = process.waitFor()
                    if (exitCode != 0) {
                        throw GradleException(
                            "Failed to materialize debug keystore at " +
                                "${debugStoreFile.absolutePath}: keytool exited $exitCode\n$output",
                        )
                    }
                }
                storeFile = debug.storeFile
                storePassword = debug.storePassword
                keyAlias = debug.keyAlias
                keyPassword = debug.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            // AGP default debug signing. Never assign the release keystore.
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
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

// Opt-in skippability evidence: run any compile task with -PcomposeReports to
// get <variant>-composables.txt / -classes.txt under app/build/compose_reports
// (catches regressions like unskippable or frozen derived state in review).
// Gated on a property so normal builds stay configuration-cache friendly.
composeCompiler {
    if (project.hasProperty("composeReports")) {
        reportsDestination = layout.buildDirectory.dir("compose_reports")
        metricsDestination = layout.buildDirectory.dir("compose_metrics")
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
