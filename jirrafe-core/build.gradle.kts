plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.networkanalysis) {
        // The jar already embeds a minimized fastutil; the declared dep duplicates classes.
        exclude(group = "it.unimi.dsi")
    }
    testImplementation(kotlin("test"))
}
