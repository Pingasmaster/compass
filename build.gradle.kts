import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dependency.analysis)
    alias(libs.plugins.androidx.baselineprofile) apply false
    // Provides JavaToolchainService for the JDK 25 ktlint JavaExec tasks below.
    id("jvm-toolchains")
}

// ktlint CLI via JavaExec on JDK 25. build.sh + ensure-jdk25-home.sh inject
// JEP 498 / native-access opt-in flags so kotlin-compiler-embeddable stays quiet.
val ktlintCli = configurations.create("ktlintCli") {
    // ktlint-cli publishes both external and shadowed variants; JavaExec needs
    // the shadowed fat jar (contains com.pinterest.ktlint.Main + deps).
    attributes {
        attribute(
            org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE,
            objects.named(org.gradle.api.attributes.Bundling.SHADOWED),
        )
    }
}

dependencies {
    ktlintCli(libs.ktlint.cli)
}

val javaToolchains = extensions.getByType(JavaToolchainService::class.java)

val ktlintInputPatterns = listOf(
    "**/src/**/*.kt",
    "**/src/**/*.kts",
    "*.kts",
    "build.gradle.kts",
    "settings.gradle.kts",
    "app/*.kts",
    "macrobenchmark/*.kts",
    "baselineprofile/*.kts",
    "shippedsmoke/*.kts",
    "!**/build/**",
)

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Check Kotlin sources with ktlint on JDK 25"
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        },
    )
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootDir
    args(ktlintInputPatterns + "--relative")
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Format Kotlin sources with ktlint on JDK 25"
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        },
    )
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootDir
    args(listOf("-F") + ktlintInputPatterns + "--relative")
}
