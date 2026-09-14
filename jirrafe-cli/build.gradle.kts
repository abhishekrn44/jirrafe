plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.graalvm.native)
}

val gradlePlugin: Configuration by configurations.creating

dependencies {
    gradlePlugin(project(":jirrafe-gradle"))
    implementation(project(":jirrafe-core"))
    implementation(project(":jirrafe-extract"))
    runtimeOnly(project(":jirrafe-frameworks"))
    implementation(libs.clikt.core)
    // MCP: all SDK use is confined to io.jirrafe.cli.mcp.McpServer
    implementation(libs.mcp.server)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.io.core)
    runtimeOnly(libs.slf4j.nop)
    testImplementation(testFixtures(project(":jirrafe-core")))
    testImplementation(libs.mcp.client)
    testImplementation(libs.mcp.testing)
    testImplementation(kotlin("test"))
}

tasks.jar {
    manifest { attributes("Implementation-Title" to "jirrafe", "Implementation-Version" to project.version) }
}

application {
    applicationName = "jirrafe"
    mainClass.set("io.jirrafe.cli.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("jirrafe")
    archiveClassifier.set("all")
    mergeServiceFiles() // framework plugins and the SQLite driver register through META-INF/services
    // the Gradle plugin jar rides inside the fat jar too, so `jirrafe resolve` works from `java -jar`
    from(gradlePlugin) { into("plugins"); rename { "jirrafe-gradle.jar" } }
}

graalvmNative {
    binaries.named("main") {
        imageName.set("jirrafe")
        mainClass.set("io.jirrafe.cli.MainKt")
        buildArgs.addAll("--no-fallback", "-H:+ReportExceptionStackTraces", "--enable-url-protocols=http,https")
        resources.autodetect()
    }
    toolchainDetection.set(false)
}

// The Gradle plugin ships beside the CLI so `jirrafe resolve` can inject it through an init script.
distributions {
    main {
        contents {
            from(gradlePlugin) { into("plugins") }
        }
    }
}
