package io.jirrafe.frameworks.spring

import io.jirrafe.core.knowledge.Html
import io.jirrafe.core.knowledge.Knowledge
import io.jirrafe.core.knowledge.Report
import io.jirrafe.core.manifest.Manifest
import io.jirrafe.core.manifest.ManifestArtifact
import io.jirrafe.core.manifest.ManifestConfiguration
import io.jirrafe.core.manifest.ManifestModule
import io.jirrafe.core.manifest.VersionConflict
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.store.GraphStore
import io.jirrafe.extract.Indexer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The knowledge layer over the indexed Spring fixture: real flows, layers and findings. */
class KnowledgeFixtureTest {
    private val fixtures: Path = Path.of("..", "jirrafe-fixtures").toAbsolutePath().normalize()
    private val springApp: Path = fixtures.resolve("gradle-multi/spring-app")
    private val libJar: Path = fixtures.resolve("local-repo/com/example/fixtures/internal-lib/1.0.0/internal-lib-1.0.0.jar")
    private val libSources: Path = fixtures.resolve("local-repo/com/example/fixtures/internal-lib/1.0.0/internal-lib-1.0.0-sources.jar")
    private val springJars = System.getProperty("java.class.path").split(File.pathSeparator).filter { it.endsWith(".jar") && !it.contains("jirrafe") }

    private val manifest = Manifest(
        schema = 1, buildTool = "gradle", root = fixtures.resolve("gradle-multi").toString(),
        modules = listOf(
            ManifestModule(
                name = ":spring-app", dir = springApp.toString(), group = "com.example", version = "0.1.0", javaVersion = 17,
                sourceDirs = listOf(springApp.resolve("src/main/java").toString()),
                resourceDirs = listOf(springApp.resolve("src/main/resources").toString()),
                classesDirs = listOf(springApp.resolve("build/classes/java/main").toString()),
                configurations = listOf(
                    ManifestConfiguration(
                        "compileClasspath",
                        artifacts = listOf(
                            ManifestArtifact("com.example.fixtures", "internal-lib", "1.0.0", "internal", sourcesJar = libSources.toString(), file = libJar.toString()),
                        ) + springJars.mapIndexed { i, jar -> ManifestArtifact("ext", "jar$i", "1", "external", file = jar) },
                        conflicts = listOf(VersionConflict("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0", "commons-text")),
                    ),
                ),
            ),
        ),
    )

    private val store = GraphStore.open(Files.createTempDirectory("jirrafe-knowledge").resolve("graph.db")).also { store ->
        check(Files.exists(springApp.resolve("build/classes/java/main"))) { "build jirrafe-fixtures/gradle-multi first" }
        val stats = Indexer(store, Files.createTempDirectory("jirrafe-work"), listOf(SpringPlugin())).index(manifest)
        check(stats.sourceErrors == 0) { "fixture must compile cleanly: $stats" }
    }
    private val result = Knowledge(store, manifest, fixtures.resolve("gradle-multi")).build()

    @Test
    fun `the GET orders flow runs controller to repository`() {
        val flow = store.node("flow:com.example.orders.OrderController#open()")
        assertNotNull(flow)
        assertEquals("GET /orders", flow.fqn)
        val steps = flow.attrs["steps"]!!
        assertContains(steps, "com.example.orders.OrderService#open()")
        assertContains(steps, "com.example.orders.OrderRepository#findByStatus(java.lang.String)")
        assertContains(flow.attrs["summary"]!!, "OrderController.open -> OrderService.open -> OrderRepository.findByStatus")
        assertContains(flow.attrs["communities"]!!, "community:")
        val place = store.node("flow:com.example.orders.OrderController#place(com.example.orders.Order)")!!
        assertContains(place.attrs["steps"]!!, "com.example.orders.EmailNotifier#notify(com.example.orders.Order)")
        assertEquals("com.example.fixtures:internal-lib:1.0.0", place.attrs["artifacts"], "greeter.greet crosses into the internal jar")
        assertEquals(setOf("route", "consumer", "job"), result.let { store.nodes(NodeKind.FLOW).map { it.attrs["entryKind"] }.toSet() })
    }

    @Test
    fun `layers, communities and gods on real code`() {
        assertEquals("controller", store.node("com.example.orders.OrderController")!!.attrs["layer"])
        assertEquals("service", store.node("com.example.orders.OrderService")!!.attrs["layer"])
        assertEquals("repository", store.node("com.example.orders.OrderRepository")!!.attrs["layer"])
        assertEquals("model", store.node("com.example.orders.Order")!!.attrs["layer"])
        assertEquals("client", store.node("com.example.orders.PricingClient")!!.attrs["layer"])
        assertEquals("config", store.node("com.example.orders.AppConfig")!!.attrs["layer"])
        assertTrue(result.gods.isNotEmpty())
        assertContains(store.meta("knowledge.gods")!!, "com.example.orders.Order")
        assertTrue(store.edgesFrom("com.example.orders.OrderService", EdgeKind.MEMBER_OF_COMMUNITY).isNotEmpty())
        assertTrue(store.nodes(NodeKind.COMMUNITY).any { "com.example.lib" in it.attrs["packages"]!! }, "library classes are clustered too")
    }

    @Test
    fun `structural findings join the plugin findings`() {
        val kinds = store.nodes(NodeKind.FINDING).groupBy { it.attrs["kind"]!! }
        assertContains(kinds.keys, "self-invocation")
        assertContains(kinds.keys, "undefined-config-key")
        assertContains(kinds["version-conflict"]!!.single().fqn, "commons-lang3 requested 3.12.0 by commons-text, resolved 3.14.0")
        assertTrue(kinds["untested-god-node"]!!.isNotEmpty(), "the fixture has no tests, so every god node is untested")
        val report = Report.markdown(store, result)
        assertContains(report, "| :spring-app | com.example:0.1.0 |")
        assertContains(report, "com.example.fixtures:internal-lib:1.0.0 | sources jar")
        assertContains(report, "### self-invocation")
        assertContains(Html.render(result), "OrderService")
    }
}
