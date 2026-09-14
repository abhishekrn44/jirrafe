plugins {
    `java-library`
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-web")
    implementation("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    implementation("org.springframework:spring-tx")
    implementation("org.springframework.data:spring-data-jpa")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("jakarta.persistence:jakarta.persistence-api")
    implementation("com.example.fixtures:internal-lib:1.0.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
