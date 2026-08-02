// Compass - settings.gradle.kts
//
// Modernized for Gradle 9.6.0:
//   - foojay-resolver-convention: JDK toolchain auto-provisioning (daemon JVM 25)
//   - enableFeaturePreview("NO_IMPLICIT_LOOKUP_IN_PARENT_PROJECTS"): adopt Gradle 10 behavior
//     (eliminates implicit project.properties() / findProperty() / hasProperty() lookups in
//     parent projects - see https://docs.gradle.org/9.6.0/userguide/upgrading_version_9.html)
//   - enableFeaturePreview("STABLE_CONFIGURATION_CACHE"): recommended complementary preview
//   - FAIL_ON_PROJECT_REPOS: force all repositories declared in dependencyResolutionManagement
//   - version-catalog "libs" is automatic when gradle/libs.versions.toml exists
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // JDK toolchain auto-provisioning for gradle-daemon-jvm.properties (JDK 25).
    // Stable since 0.9.0 (2024); 1.0.0 ships with Gradle 9.6.0.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Adopt Gradle 10 forward-compatible lookups. With this preview, findProperty / hasProperty /
// project.properties() must explicitly use providers.gradleProperty() / providers.systemProperty().
// This catches implicit cross-project coupling that breaks Configuration Cache and Isolated Projects.
enableFeaturePreview("NO_IMPLICIT_LOOKUP_IN_PARENT_PROJECTS")
enableFeaturePreview("STABLE_CONFIGURATION_CACHE")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Compass"
include(":app")
include(":baselineprofile")
include(":macrobenchmark")
include(":shippedsmoke")
