plugins {
    `java-library`
    `maven-publish`
}

group = "com.example.fixtures"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // compileOnly: the published POM stays dependency-free, like many platform jars
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:3.5.16")
    compileOnly("org.springframework:spring-context:6.2.16")
}

java {
    withSourcesJar()
}

// Published POM-only, like a Maven-built enterprise jar: no Gradle module metadata.
tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
    repositories {
        maven { url = uri("../local-repo") }
    }
}
