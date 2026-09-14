plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":jirrafe-core"))
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(variantOf(libs.vineflower) { classifier("slim") })
    testImplementation(kotlin("test"))
    // Only so the tests can find a Lombok jar for the annotation processor path
    testRuntimeOnly("org.projectlombok:lombok:1.18.38")
}
