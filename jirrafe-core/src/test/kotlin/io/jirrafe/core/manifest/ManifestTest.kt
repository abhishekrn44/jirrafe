package io.jirrafe.core.manifest

import kotlin.test.Test
import kotlin.test.assertEquals

class ManifestTest {
    private fun manifest(vararg artifacts: ManifestArtifact) = Manifest(
        schema = 1, buildTool = "gradle", root = "/repo",
        modules = listOf(ManifestModule(name = ":app", dir = "/repo", group = "com.acme", version = "1", configurations = listOf(ManifestConfiguration("compileClasspath", artifacts.toList())))),
    )

    private fun artifacts(m: Manifest) = m.modules.single().configurations.single().artifacts

    @Test
    fun `jars matching a pattern become internal, file dependencies get a coordinate`() {
        val m = manifest(
            ManifestArtifact(name = "acme-core.jar", classification = "external", file = "/repo/lib/acme-core.jar"),
            ManifestArtifact(group = "org.acme", name = "legacy", version = "2", classification = "external", file = "/home/u/.m2/org/acme/legacy-2.jar"),
            ManifestArtifact(group = "com.squareup.okhttp3", name = "okhttp", version = "4", classification = "external", file = "/home/u/.m2/okhttp-4.jar"),
        )
        val out = artifacts(Manifest.reclassify(m, listOf("lib/*.jar", "**/legacy-*.jar")))
        assertEquals("internal", out[0].classification)
        assertEquals("file:acme-core:local", out[0].coordinate, "a file dependency is named after its jar")
        assertEquals("internal", out[1].classification)
        assertEquals("org.acme:legacy:2", out[1].coordinate, "a jar with coordinates keeps them")
        assertEquals("external", out[2].classification)
        assertEquals(m, Manifest.reclassify(m, emptyList()), "no patterns, no change")
    }
}
