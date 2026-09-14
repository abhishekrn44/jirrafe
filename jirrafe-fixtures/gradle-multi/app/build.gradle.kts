plugins {
    application
}

dependencies {
    implementation("com.example.fixtures:internal-lib:1.0.0")
    implementation(project(":kt-module"))
    // commons-text 1.10.0 pulls commons-lang3 3.12.0; the direct 3.14.0 wins -> a version conflict to report
    implementation("org.apache.commons:commons-text:1.10.0")
    implementation("org.apache.commons:commons-lang3:3.14.0")
    // Lombok goes through the annotation processor path, as Gradle 5+ requires
    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("com.example.app.Main")
}
