import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dependency.analysis)
    alias(libs.plugins.androidx.baselineprofile) apply false
    // Provides JavaToolchainService for the JDK 21 ktlint JavaExec tasks below.
    id("jvm-toolchains")
}

// ktlint on JDK 21: its embedded kotlin-compiler-embeddable still calls
// sun.misc.Unsafe::objectFieldOffset. On the JDK 25 daemon that emits a
// terminal-deprecation WARNING even with --sun-misc-unsafe-memory-access=allow
// (JEP 498). Running the CLI via a JDK 21 toolchain keeps builds clean without
// suppressing the warning or lowering the app's JVM 25 target.
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
    "!**/build/**",
)

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Check Kotlin sources with ktlint on JDK 21"
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootDir
    args(ktlintInputPatterns + "--relative")
    // build.sh exports JDK 25-only flags via JAVA_TOOL_OPTIONS; JDK 21 rejects them.
    environment("JAVA_TOOL_OPTIONS", "")
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Format Kotlin sources with ktlint on JDK 21"
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootDir
    args(listOf("-F") + ktlintInputPatterns + "--relative")
    environment("JAVA_TOOL_OPTIONS", "")
}
