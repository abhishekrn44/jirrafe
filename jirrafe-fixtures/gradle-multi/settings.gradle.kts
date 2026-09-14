pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("../local-repo") }
    }
}

rootProject.name = "gradle-multi"

include("app", "kt-module", "spring-app")
