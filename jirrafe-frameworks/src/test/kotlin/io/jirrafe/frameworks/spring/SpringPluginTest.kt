package io.jirrafe.frameworks.spring

import io.jirrafe.core.manifest.Manifest
import io.jirrafe.core.manifest.ManifestArtifact
import io.jirrafe.core.manifest.ManifestConfiguration
import io.jirrafe.core.manifest.ManifestModule
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Resolution
import io.jirrafe.core.store.GraphStore
import io.jirrafe.extract.Indexer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpringPluginTest {
    private val fixtures: Path = Path.of("..", "jirrafe-fixtures").toAbsolutePath().normalize()
    private val springApp: Path = fixtures.resolve("gradle-multi/spring-app")
    private val libJar: Path = fixtures.resolve("local-repo/com/example/fixtures/internal-lib/1.0.0/internal-lib-1.0.0.jar")
    private val libSources: Path = fixtures.resolve("local-repo/com/example/fixtures/internal-lib/1.0.0/internal-lib-1.0.0-sources.jar")

    /** Every jar on this test's own classpath stands in for the module's resolved classpath (a superset is fine). */
    private val springJars = System.getProperty("java.class.path").split(File.pathSeparator)
        .filter { it.endsWith(".jar") && !it.contains("jirrafe") }

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
                    ),
                ),
            ),
        ),
    )

    private val store = GraphStore.open(Files.createTempDirectory("jirrafe-spring").resolve("graph.db")).also { store ->
        check(Files.exists(springApp.resolve("build/classes/java/main"))) { "build jirrafe-fixtures/gradle-multi first" }
        val log = ArrayList<String>()
        val stats = Indexer(store, Files.createTempDirectory("jirrafe-work"), listOf(SpringPlugin())) { log += it }.index(manifest)
        check(stats.sourceErrors == 0) { "fixture must compile cleanly: $stats " + log.joinToString(" | ") }
    }

    private fun out(id: String, kind: EdgeKind) = store.edgesFrom(id, kind)
    private fun targets(id: String, kind: EdgeKind) = out(id, kind).map { it.to }.toSet()

    @Test
    fun beansAndInjection() {
        val service = assertNotNull(store.node("bean:orderService"))
        assertEquals(NodeKind.BEAN, service.kind)
        assertEquals("com.example.orders.OrderService", service.attrs["type"])
        assertEquals("Service", service.attrs["stereotype"])
        assertEquals(listOf("bean:orderService"), targets("com.example.orders.OrderService", EdgeKind.PROVIDES_BEAN).toList())
        assertEquals("true", store.node("bean:emailNotifier")?.attrs?.get("primary"))
        assertEquals("com.example.orders.SmsNotifier", store.node("bean:sms")?.attrs?.get("type"))
        assertEquals("Repository", store.node("bean:orderRepository")?.attrs?.get("stereotype"))
        // @Bean method
        assertEquals("org.springframework.web.client.RestTemplate", store.node("bean:restTemplate")?.attrs?.get("type"))
        assertEquals(Resolution.SPRING, out("com.example.orders.AppConfig#restTemplate()", EdgeKind.PROVIDES_BEAN).single().resolution)
        // auto-configured bean from the internal jar, conditional
        val greeter = assertNotNull(store.node("bean:greeter"))
        assertEquals("true", greeter.attrs["conditional"])
        assertEquals(Resolution.HEURISTIC, out("com.example.lib.LibAutoConfiguration#greeter()", EdgeKind.PROVIDES_BEAN).single().resolution)

        val injects = out("com.example.orders.OrderService", EdgeKind.INJECTS).associate { it.to to it.resolution }
        assertEquals(Resolution.SPRING, injects["bean:orderRepository"], injects.toString())
        assertEquals(Resolution.SPRING, injects["bean:emailNotifier"], "primary wins: $injects")
        assertEquals(Resolution.SPRING, injects["bean:sms"], "qualifier: $injects")
        assertEquals(Resolution.SPRING, injects["bean:pricingClient"], injects.toString())
        assertEquals(Resolution.HEURISTIC, injects["bean:greeter"], "conditional auto-config bean: $injects")
        assertEquals(5, injects.size, "KafkaTemplate is framework-provided and not linked: $injects")
        assertEquals(setOf("bean:orderService"), targets("com.example.orders.OrderController", EdgeKind.INJECTS))
    }

    @Test
    fun routes() {
        val get = assertNotNull(store.node("route:GET /orders"))
        assertEquals("com.example.orders.OrderController#open()", get.attrs["handler"])
        assertNotNull(store.node("route:GET /orders/{id}/quote"))
        assertEquals(setOf("route:GET /health"), targets("com.example.orders.HealthController#health()", EdgeKind.HANDLES_ROUTE))
        val post = assertNotNull(store.node("route:POST /orders"))
        assertEquals("application/json", post.attrs["consumes"])
        assertEquals("application/json", post.attrs["produces"])
        assertEquals(setOf("route:POST /orders"), targets("com.example.orders.OrderController#place(com.example.orders.Order)", EdgeKind.HANDLES_ROUTE))
        assertEquals(4, store.nodes(NodeKind.HTTP_ROUTE).count { it.attrs["remote"] == null })
    }

    @Test
    fun configKeysAndUndefinedFinding() {
        val max = assertNotNull(store.node("config:orders.max"))
        assertEquals("10", max.attrs["value"])
        assertTrue(max.file!!.endsWith("application.yml"))
        assertEquals(2, max.startLine)
        assertEquals("orders", store.node("config:spring.application.name")?.attrs?.get("value"))

        val binds = targets("com.example.orders.OrderService", EdgeKind.BINDS_CONFIG)
        assertTrue("config:orders.max" in binds && "config:orders.undefined-key" in binds, binds.toString())
        assertEquals(setOf("config:orders.pricing-url"), targets("com.example.orders.PricingClient", EdgeKind.BINDS_CONFIG), "constructor parameter @Value")
        assertEquals("false", store.node("config:orders.undefined-key")?.attrs?.get("defined"))
        val finding = store.edgesFrom("com.example.orders.OrderService", EdgeKind.HAS_FINDING).map { store.node(it.to)!! }
            .single { it.attrs["kind"] == "undefined-config-key" }
        assertEquals("orders.undefined-key", finding.attrs["key"])
    }

    @Test
    fun jpaMessagingRemoteAndJobs() {
        assertEquals("orders", store.node("com.example.orders.Order")?.attrs?.get("table"))
        assertEquals(setOf("com.example.orders.Order"), targets("com.example.orders.OrderRepository", EdgeKind.MAPS_TO_TABLE))

        val topic = assertNotNull(store.node("topic:orders-placed"))
        assertEquals("kafka", topic.attrs["system"])
        assertEquals(setOf("topic:orders-placed"), targets("com.example.orders.OrderEvents#onPlaced(java.lang.String)", EdgeKind.CONSUMES_FROM), "resolved through \${orders.topic}")
        val produces = out("com.example.orders.OrderService#place(com.example.orders.Order)", EdgeKind.PRODUCES_TO).single()
        assertEquals("topic:orders-placed", produces.to)
        assertEquals(1.0, produces.confidence, "matched a known topic, so OPEN was not guessed")

        val remote = out("com.example.orders.PricingClient#price(long)", EdgeKind.CALLS_REMOTE).single()
        assertEquals("remote:http://pricing.internal/api/price/{id}", remote.to)
        assertEquals(Resolution.HEURISTIC, remote.resolution)

        val job = assertNotNull(store.node("job:com.example.orders.OrderService#expire()"))
        assertEquals("0 0 * * * *", job.attrs["cron"])
        assertEquals(setOf("com.example.orders.OrderService#expire()"), targets(job.id, EdgeKind.CALLS))
    }

    @Test
    fun selfInvocationFinding() {
        val findings = store.edgesFrom("com.example.orders.OrderService#placeAll(java.util.List)", EdgeKind.HAS_FINDING).map { store.node(it.to)!! }
        val f = findings.single()
        assertEquals("self-invocation", f.attrs["kind"])
        assertEquals("com.example.orders.OrderService#place(com.example.orders.Order)", f.attrs["key"])
        assertTrue(f.fqn.contains("@Transactional"), f.fqn)
        assertEquals(1, store.nodes(NodeKind.FINDING).count { it.attrs["kind"] == "self-invocation" })
    }

    @Test
    fun rerunIsIdempotent() {
        val before = store.count("nodes") to store.count("edges")
        Indexer(store, Files.createTempDirectory("jirrafe-work2"), listOf(SpringPlugin())).index(manifest)
        assertEquals(before, store.count("nodes") to store.count("edges"))
    }
}
