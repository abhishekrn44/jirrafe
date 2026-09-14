plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
}

// Maven Central coordinates: io.jirrafe needs the jirrafe.io domain verified with Central; io.github.<owner> is the fallback.
val publishedLibraries = setOf("jirrafe-core", "jirrafe-extract", "jirrafe-frameworks")

allprojects {
    group = "io.jirrafe"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    if (name in publishedLibraries) {
        apply(plugin = "com.vanniktech.maven.publish")
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral()
            if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
            coordinates(project.group.toString(), project.name, project.version.toString())
            pom {
                name.set(project.name)
                description.set("jirrafe: code graph and knowledge graph of JVM projects, including internal jars, served over MCP")
                url.set("https://github.com/abhishekrn44/jirrafe")
                licenses { license { name.set("Apache-2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } }
                developers { developer { id.set("jirrafe"); name.set("jirrafe maintainers") } }
                scm { url.set("https://github.com/abhishekrn44/jirrafe"); connection.set("scm:git:https://github.com/abhishekrn44/jirrafe.git") }
            }
        }
    }
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }
}
