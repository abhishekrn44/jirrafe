plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":jirrafe-core"))
    testImplementation(project(":jirrafe-extract"))
    // Spring jars for the fixture's classpath; the test reads them from its own classpath
    testRuntimeOnly(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    testRuntimeOnly("org.springframework:spring-context")
    testRuntimeOnly("org.springframework:spring-web")
    testRuntimeOnly("org.springframework:spring-webmvc")
    testRuntimeOnly("jakarta.servlet:jakarta.servlet-api")
    testRuntimeOnly("org.springframework:spring-tx")
    testRuntimeOnly("org.springframework.data:spring-data-jpa")
    testRuntimeOnly("org.springframework.kafka:spring-kafka")
    testRuntimeOnly("jakarta.persistence:jakarta.persistence-api")
    testImplementation(kotlin("test"))
}
