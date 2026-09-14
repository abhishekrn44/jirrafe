package io.jirrafe.core.store

import io.jirrafe.core.model.Edge
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Origin
import io.jirrafe.core.model.Resolution
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphStoreTest {
    private val greeter = Node("com.example.lib.DefaultGreeter", NodeKind.CLASS, "com.example.lib.DefaultGreeter", Origin.BYTECODE,
        signature = "public class DefaultGreeter", attrs = mapOf("sourceFile" to "DefaultGreeter.java"))
    private val greet = Node("com.example.lib.DefaultGreeter#greet(java.lang.String)", NodeKind.METHOD,
        "com.example.lib.DefaultGreeter#greet(java.lang.String)", Origin.BYTECODE, startLine = 10, endLine = 12)

    @Test
    fun roundTripsNodesAndEdges() {
        GraphStore.inMemory().use { store ->
            store.node(greeter)
            store.node(greet)
            store.node(greeter.copy(signature = "second writer loses"))
            store.edge(Edge(greeter.id, greet.id, EdgeKind.CONTAINS, Resolution.EXACT))
            store.flush()

            assertEquals(greeter, store.node(greeter.id))
            assertEquals(greet, store.node(greet.id))
            assertEquals(listOf(greet.id), store.edgesFrom(greeter.id, EdgeKind.CONTAINS).map { it.to })
            assertEquals(listOf(greeter.id), store.edgesTo(greet.id).map { it.from })
        }
    }

    @Test
    fun stubsExternalTargets() {
        GraphStore.inMemory().use { store ->
            store.node(greet)
            store.edge(Edge(greet.id, "org.slf4j.Logger#info(java.lang.String)", EdgeKind.CALLS, Resolution.EXACT))
            assertEquals(1, store.createStubs())

            val stub = assertNotNull(store.node("org.slf4j.Logger#info(java.lang.String)"))
            assertEquals(NodeKind.METHOD, stub.kind)
            assertEquals(Origin.EXTERNAL, stub.origin)
            assertEquals(NodeKind.CLASS, store.node("org.slf4j.Logger")?.kind)
            assertEquals(listOf(stub.id), store.edgesFrom("org.slf4j.Logger", EdgeKind.CONTAINS).map { it.to })
        }
    }

    @Test
    fun inheritedMemberOfIndexedTypeKeepsItsOrigin() {
        GraphStore.inMemory().use { store ->
            store.node(greeter)
            store.node(greet)
            store.edge(Edge(greet.id, "com.example.lib.DefaultGreeter#save(java.lang.Object)", EdgeKind.CALLS, Resolution.EXACT))
            store.createStubs()

            val stub = assertNotNull(store.node("com.example.lib.DefaultGreeter#save(java.lang.Object)"))
            assertEquals(Origin.BYTECODE, stub.origin)
            assertEquals("true", stub.attrs["inherited"])
        }
    }

    @Test
    fun searchesByPrefixAndCamelCaseWord() {
        GraphStore.inMemory().use { store ->
            store.node(greeter)
            store.node(greet)
            store.flush()
            store.rebuildSearchIndex()

            assertEquals(listOf(greeter.id, greet.id).toSet(), store.search("greeter").map { it.id }.toSet())
            assertEquals(listOf(greet.id), store.search("greet string", kinds = setOf(NodeKind.METHOD)).map { it.id })
            assertTrue(store.search("nothing_here").isEmpty())
        }
    }

    @Test
    fun exportsJson() {
        val file = Files.createTempFile("graph", ".json")
        GraphStore.inMemory().use { store ->
            store.node(greeter)
            store.node(greet)
            store.edge(Edge(greeter.id, greet.id, EdgeKind.CONTAINS, Resolution.EXACT))
            store.exportJson(file)
        }
        val json = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals(2, json["nodes"]!!.jsonArray.size)
        assertEquals(1, json["edges"]!!.jsonArray.size)
        assertEquals("DefaultGreeter.java", json["nodes"]!!.jsonArray[0].jsonObject["attrs"]!!.jsonObject["sourceFile"]!!.toString().trim('"'))
    }

    @Test
    fun pruneUnreachableKeepsTheClassesTheRepoReachesWithinTheDepth() {
        GraphStore.inMemory().use { store ->
            fun n(id: String, artifact: String?) = store.node(Node(id, if ('#' in id) NodeKind.METHOD else NodeKind.CLASS, id, if (artifact == null) Origin.REPO else Origin.BYTECODE, artifact = artifact))
            fun call(a: String, b: String) = store.edge(Edge(a, b, EdgeKind.CALLS, Resolution.EXACT))
            n("app.Main#run()", null)
            for (c in listOf("lib.A", "lib.B", "lib.C", "lib.D", "lib.Unused")) { n(c, "j"); n("$c#m()", "j"); store.edge(Edge(c, "$c#m()", EdgeKind.CONTAINS, Resolution.EXACT)) }
            store.node(Node("lib.Wired", NodeKind.CLASS, "lib.Wired", Origin.BYTECODE, artifact = "j", attrs = mapOf("annotations" to """{"org.springframework.boot.autoconfigure.AutoConfiguration":{}}""")))
            store.node(Node("lib.Old", NodeKind.CLASS, "lib.Old", Origin.BYTECODE, artifact = "j", attrs = mapOf("annotations" to """{"java.lang.Deprecated":{}}""")))
            call("app.Main#run()", "lib.A#m()"); call("lib.A#m()", "lib.B#m()"); call("lib.B#m()", "lib.C#m()"); call("lib.C#m()", "lib.D#m()")
            assertEquals(3, store.pruneUnreachable("j", 2), "D is three hops away, Unused and Old are never reached")
            assertNotNull(store.node("lib.C#m()")); assertNotNull(store.node("lib.C"))
            assertNotNull(store.node("lib.Wired"), "a framework annotation counts as a reference")
            assertEquals(null, store.node("lib.D")); assertEquals(null, store.node("lib.Unused#m()")); assertEquals(null, store.node("lib.Old"))
            assertEquals(listOf("lib.A#m()"), store.usageOf("j", listOf("lib")), "what the repo points at inside the jar")
            assertEquals(listOf("lib"), store.packagesOf("j"))
        }
    }
}
