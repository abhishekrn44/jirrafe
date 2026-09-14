pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Gradle Tooling API is only published here.
        maven("https://repo.gradle.org/gradle/libs-releases")
    }
}

rootProject.name = "jirrafe"

include(
    "jirrafe-core",
    "jirrafe-extract",
    "jirrafe-frameworks",
    "jirrafe-gradle",
    "jirrafe-cli",
)
// jirrafe-maven is a Maven build (see its pom.xml); jirrafe-fixtures are standalone builds.
