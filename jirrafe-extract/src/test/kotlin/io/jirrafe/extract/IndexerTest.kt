package io.jirrafe.extract

import io.jirrafe.core.manifest.Manifest
import io.jirrafe.core.manifest.ManifestArtifact
import io.jirrafe.core.manifest.ManifestConfiguration
import io.jirrafe.core.manifest.ManifestModule
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Origin
import io.jirrafe.core.store.GraphStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IndexerTest {
    private fun manifest(extraClasspath: List<String> = emptyList()) = Manifest(
        schema = 1, buildTool = "gradle", root = Fixtures.gradleMulti.toString(),
        modules = listOf(
            ManifestModule(
                name = ":app", dir = Fixtures.gradleMulti.resolve("app").toString(), group = "com.example", version = "0.1.0",
                javaVersion = 17,
                sourceDirs = listOf(Fixtures.appSources.toString()),
                classesDirs = listOf(Fixtures.appClasses.toString()),
                annotationProcessorPath = listOf(Fixtures.lombokJar.toString()),
                configurations = listOf(
                    ManifestConfiguration(
                        "compileClasspath", artifacts = listOf(
                            ManifestArtifact("com.example.fixtures", "internal-lib", "1.0.0", "internal",
                                sourcesJar = Fixtures.internalLibSources.toString(), file = Fixtures.internalLibJar.toString()),
                            ManifestArtifact(classification = "project", module = ":kt-module", file = Fixtures.ktClasses.toString()),
                            ManifestArtifact("org.projectlombok", "lombok", "1.18.38", "external", file = Fixtures.lombokJar.toString()),
                            ManifestArtifact("org.apache.commons", "commons-lang3", "3.14.0", "external", file = "commons-lang3-3.14.0.jar"),
                        ) + extraClasspath.map { ManifestArtifact("x", "extra", "1", "external", file = it) }
                    ),
                ),
            ),
            ManifestModule(
                name = ":kt-module", dir = Fixtures.gradleMulti.resolve("kt-module").toString(), group = "com.example", version = "0.1.0",
                javaVersion = 17, classesDirs = listOf(Fixtures.ktClasses.toString()),
            ),
        ),
    )

    @Test
    fun indexesSourcesJarsBytecodeStubsAndDispatch() {
        // relative, as the CLI passes it: extracted sources must still land inside it
        val work = Path.of("build", "jirrafe-test-${System.nanoTime()}").also { Files.createDirectories(it) }
        val db = work.resolve("graph.db")
        GraphStore.open(db).use { store ->
            val stats = Indexer(store, work).index(manifest())
            assertEquals(2, stats.modules)
            assertEquals(1, stats.internalJars)
            assertEquals(0, stats.skippedUnits)
            assertEquals(0, stats.sourceErrors)
            assertTrue(stats.jarSourceErrors > 0, "LibAutoConfiguration needs Spring Boot, which this classpath lacks: $stats")
            // ...and the unresolved source did not wipe the bytecode facts: the auto-configuration annotation survives
            assertTrue(store.node("com.example.lib.LibAutoConfiguration")!!.attrs["annotations"]!!.contains("AutoConfiguration"))
            assertTrue(stats.sourceFiles >= 6, "3 app files + 3 sources-jar files: $stats")

            // source tier won over bytecode for the repo module and for the sources jar
            val banner = assertNotNull(store.node("com.example.app.Main#banner(java.lang.String)"))
            assertEquals(Origin.REPO, banner.origin)
            assertNotNull(banner.doc)
            val greet = assertNotNull(store.node("com.example.lib.DefaultGreeter#greet(java.lang.String)"))
            assertEquals(Origin.SOURCES_JAR, greet.origin)
            assertTrue(greet.file!!.contains("sources"), greet.file!!)
            assertEquals(Origin.BYTECODE, store.node("com.example.kt.Formatter#shout(java.lang.String)")?.origin)
            assertEquals("The only implementation shipped in the jar.", store.node("com.example.lib.DefaultGreeter")?.doc)

            // class-hierarchy dispatch through the interface, inside the module and into the jar
            val total = store.edgesFrom("com.example.app.Features#total(com.example.app.Features\$Shape[])", EdgeKind.DISPATCHES_TO)
            assertEquals(
                setOf("com.example.app.Features\$Circle#area()", "com.example.app.Features\$Square#area()"),
                total.map { it.to }.toSet(),
            )
            assertEquals(0.5, total.first().confidence)
            assertEquals(
                listOf("com.example.lib.DefaultGreeter#greet(java.lang.String)"),
                store.edgesFrom(banner.id, EdgeKind.DISPATCHES_TO).map { it.to },
            )
            assertTrue(stats.dispatches >= 3)

            // artifacts, modules, stubs
            assertEquals(Origin.SOURCES_JAR, store.node("artifact:com.example.fixtures:internal-lib:1.0.0")?.origin)
            assertEquals(Origin.EXTERNAL, store.node("artifact:org.apache.commons:commons-lang3:3.14.0")?.origin)
            assertTrue("module::kt-module" in store.edgesFrom("module::app", EdgeKind.DEPENDS_ON_ARTIFACT).map { it.to })
            assertEquals(Origin.EXTERNAL, store.node("kotlin.jvm.internal.Intrinsics#checkNotNullParameter(java.lang.Object,java.lang.String)")?.origin)
            assertEquals(NodeKind.PACKAGE, store.node("com.example.app")?.kind)
            assertEquals(listOf("com.example.app.Main#banner(java.lang.String)"), store.search("banner").map { it.id })

            // second run: nothing changed, everything skipped, graph identical
            val again = Indexer(store, work).index(manifest())
            assertEquals(3, again.skippedUnits, "2 modules + 1 internal artifact")
            assertEquals(stats.nodes, again.nodes)
            assertEquals(stats.edges, again.edges)

            // classpath change re-indexes :app and the internal jar whose sources resolve against that classpath
            val changed = Indexer(store, work).index(manifest(extraClasspath = listOf("extra-1.jar")))
            assertEquals(1, changed.skippedUnits, ":kt-module is untouched")
            assertEquals(stats.nodes + 1, changed.nodes, "one new artifact node")
            assertEquals(Origin.REPO, store.node("com.example.app.Main#banner(java.lang.String)")?.origin)

            val json = work.resolve("graph.json")
            store.exportJson(json)
            val parsed = Json.parseToJsonElement(Files.readString(json)).jsonObject
            assertEquals(changed.nodes.toInt(), parsed["nodes"]!!.jsonArray.size)
            assertEquals(changed.edges.toInt(), parsed["edges"]!!.jsonArray.size)
        }
    }
}
