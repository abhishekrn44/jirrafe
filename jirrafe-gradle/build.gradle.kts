plugins {
    `java-gradle-plugin`
    alias(libs.plugins.plugin.publish)
}

gradlePlugin {
    website.set("https://github.com/abhishekrn44/jirrafe")
    vcsUrl.set("https://github.com/abhishekrn44/jirrafe.git")
    plugins {
        create("jirrafe") {
            id = "io.jirrafe"
            implementationClass = "io.jirrafe.gradle.JirrafePlugin"
            displayName = "jirrafe resolve"
            description = "Writes .jirrafe/manifest.json (modules, classpaths, internal jars with sources, version conflicts) for the jirrafe code graph"
            tags.set(listOf("mcp", "code-graph", "architecture", "dependencies"))
        }
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
