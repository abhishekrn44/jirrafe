package io.jirrafe.core.query

import io.jirrafe.core.knowledge.SyntheticGraph
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueriesTest {
    private val store = SyntheticGraph.build()
    private val reader = object : SourceReader {
        override fun read(node: Node, contextLines: Int) = SourceReader.Source(node.file!!, node.startLine ?: 1, "// source of ${node.id}", decompiled = false)
    }
    private val q = Queries(store, "/repo", null, reader)
    private val char34 = '"'.toString()

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.ids(k: String) = this[k]!!.jsonArray.map { it.jsonObject.str("id") }

    @Test
    fun `overview lists modules, communities, flows and findings`() {
        val o = q.overview()
        assertEquals("module:app", o["modules"]!!.jsonArray.single().jsonObject.str("id"))
        assertTrue(o["communities"]!!.jsonArray.size >= 2)
        assertContains(o.ids("flows"), "flow:a.OrderController#list()")
        assertTrue(o["findings"]!!.jsonObject.containsKey("warning"))
    }

    @Test
    fun `search uses full text then id substrings`() {
        assertContains(q.search("order service").ids("results"), "a.OrderService")
        assertContains(q.search("Util2").ids("results"), "b.Util2")
        assertTrue(q.search("list", kinds = setOf(NodeKind.METHOD)).ids("results").all { '#' in it }, "kind filter keeps methods only")
    }

    @Test
    fun `get_node carries edges, community, layer, flows, findings and source`() {
        val n = q.getNode("a.OrderService#open()", includeSource = true)
        assertEquals("method", n.str("kind"))
        assertEquals("src/a/OrderService.java:10", n.str("at"))
        assertContains(n.ids("outgoing"), "a.OrderRepository#findAll()")
        assertContains(n.ids("incoming"), "a.OrderController#list()")
        assertContains(n["flows"]!!.jsonArray.map { it.jsonPrimitive.content }, "flow:a.OrderController#list()")
        assertEquals("// source of a.OrderService#open()", n["source"]!!.jsonObject.str("text"))
        val cls = q.getNode("a.OrderService")
        assertEquals("service", cls.str("layer"))
        assertTrue(cls.str("community").startsWith("community:L0-"))
        val open = cls["members"]!!.jsonArray.map { it.jsonObject }.single { it.str("signature").contains("open()") }
        assertEquals(10, open["line"]!!.jsonPrimitive.content.toInt())
        assertEquals("a.Main#main(java.lang.String[])", q.getNode("a.Main#main(String[])").str("id"), "outline names resolve to the exact member")
        assertContains(q.getNode("nope").str("error"), "no node")
    }

    @Test
    fun `source reads one body, a line range of it, or several bodies in one call`() {
        val one = q.readSource("a.OrderService#open")
        assertEquals("a.OrderService#open()", one.str("id"), "a bare member name resolves when it is not ambiguous")
        assertEquals(10, one["startLine"]!!.jsonPrimitive.int)
        assertEquals(10, one["endLine"]!!.jsonPrimitive.int)
        val cut = q.readSource("a.OrderService#open()", lines = 11..20)
        assertEquals("", cut.str("text"), "a range past the body's end is empty, not the whole body again")
        val batch = q.readSources(listOf("a.OrderService#open()", "b.Util1#f()", "a.Nope#x()"))
        assertEquals(listOf("a.OrderService#open()", "b.Util1#f()"), batch["sources"]!!.jsonArray.filter { it.jsonObject.containsKey("text") }.map { it.jsonObject.str("id") })
        assertTrue(batch["sources"]!!.jsonArray.any { it.jsonObject.containsKey("error") }, "an unknown id is reported in place")
        val tight = q.readSources(listOf("a.OrderService#open()", "b.Util1#f()"), budget = 10)
        assertEquals(1, tight["sources"]!!.jsonArray.size)
        assertEquals(listOf("b.Util1#f()"), tight["pending"]!!.jsonArray.map { it.jsonPrimitive.content }, "what did not fit is named, so the next call is exact")
    }

    @Test
    fun `the rendered answer is numbered code under a file header, and a batch keeps its pending list`() {
        val text = Render.explain(q.explain("how are orders listed?"))
        assertTrue(text.startsWith("# how are orders listed?"))
        assertTrue(text.contains("## code"), "bodies come as code")
        assertTrue(Regex("### src/a/\\S+\\.java:\\d+-\\d+  a\\.").containsMatchIn(text), "each body is headed file:start-end and id")
        assertTrue(Regex("(?m)^ *10  // source of a\\.").containsMatchIn(text), "lines are numbered from the body's start line")
        assertTrue(!text.contains(char34 + "text" + char34), "no JSON in the rendered answer")
        val batch = Render.sources(q.readSources(listOf("a.OrderService#open()", "b.Util1#f()"), budget = 10))
        assertTrue(batch.contains("### src/a/OrderService.java:10-10  a.OrderService#open()"))
        assertTrue(batch.contains("pending (did not fit; ask for them next): b.Util1#f()"))
    }

    @Test
    fun `a question in the wrong words gets the code's nearest words back`() {
        val e = q.explain("what happens on openings of things?") // "openings" is not the code's word; "open" is
        val vocab = e["vocabulary"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        assertTrue((e["pack"]?.jsonArray?.isNotEmpty() == true) || "open" in vocab, "either the code was found or its nearest word is offered: $vocab")
        assertTrue(vocab.all { it.length >= 3 && it == it.lowercase() }, "vocabulary holds only real identifier pieces")
    }

    @Test
    fun `a packed body carries its class's fields once`() {
        val e = q.explain("how are orders listed?")
        val pack = e["pack"]!!.jsonArray.map { it.jsonObject }
        val withFields = pack.filter { it.containsKey("fields") }
        assertTrue(withFields.all { p -> pack.first { owner(it.str("id")) == owner(p.str("id")) } === p }, "fields ride on the first body of each class only")
    }

    private fun owner(id: String) = id.substringBefore('#').substringBefore('$')

    @Test
    fun `neighbors, path and impact walk the graph`() {
        val n = q.neighbors("a.OrderService#open()", depth = 2)
        assertContains(n.ids("nodes"), "b.ReportService#run()")
        val p = q.path("a.OrderController", "b.Util1#f()")
        assertEquals(true, p["found"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("a.OrderController", p["path"]!!.jsonArray.first().jsonObject.str("from"))
        val i = q.impact("a.OrderRepository#findAll()")
        assertContains(i.ids("callers"), "a.OrderController#list()")
        assertContains(i["testsToRun"]!!.jsonArray.map { it.jsonPrimitive.content }, "a.OrderServiceTest")
        assertContains(i.ids("flows"), "flow:a.OrderController#list()")
        val ci = q.impact("a.OrderService")
        assertContains(ci.ids("callers"), "a.Main#main(java.lang.String[])")
    }

    @Test
    fun `impact of changes maps changed lines to members and ends with the test command`() {
        store.setMeta("buildTool", "maven")
        val i = q.impactOfChanges(mapOf("src/a/OrderService.java" to listOf(12..12)))
        assertContains(i.ids("changed"), "a.OrderService#open()", "line 12 is inside open() (10-20)")
        assertTrue(i.ids("changed").none { it == "a.OrderService" }, "members, not the class, when the line falls inside one")
        assertContains(i.ids("callers"), "a.OrderController#list()")
        assertContains(i["testsToRun"]!!.jsonArray.map { it.jsonPrimitive.content }, "a.OrderServiceTest")
        assertTrue(i["testCommand"]?.jsonPrimitive?.content?.endsWith("-Dtest=a.OrderServiceTest") == true, i.toString())
        assertContains(q.impactOfChanges(mapOf("src/a/OrderService.java" to listOf(1..3))).ids("changed"), "a.OrderService", "a change outside every member is the class's")
        assertContains(q.impactOfChanges(mapOf("src/zzz/Nope.java" to listOf(1..1))).str("error"), "no indexed source")
        store.setMeta("buildTool", "gradle")
        assertTrue(q.impactOfChanges(mapOf("src/a/OrderService.java" to listOf(12..12))).str("testCommand").contains("test --tests a.OrderServiceTest"))
    }

    @Test
    fun `flow resolves by id, handler, route and path`() {
        for (key in listOf("flow:a.OrderController#list()", "a.OrderController#list()", "GET /orders", "/orders")) {
            val f = q.flow(key)
            assertEquals("GET /orders", f.str("entry"), key)
            assertContains(f.ids("steps"), "a.OrderRepository#findAll()")
        }
        assertContains(q.flow("DELETE /nothing").str("error"), "no flow")
    }

    @Test
    fun `listings and findings`() {
        assertEquals("/orders", q.routes()["routes"]!!.jsonArray.single().jsonObject.str("path"))
        assertEquals("flow:a.OrderController#list()", q.routes()["routes"]!!.jsonArray.single().jsonObject.str("flow"))
        assertEquals("10", q.config("orders")["keys"]!!.jsonArray.single().jsonObject.str("value"))
        assertEquals("a.OrderService", q.beans("Order")["beans"]!!.jsonArray.single().jsonObject.str("type"))
        assertEquals(0, q.topics()["count"]!!.jsonPrimitive.content.toInt())
        assertTrue(q.findings(kind = "layer-violation")["findings"]!!.jsonArray.size == 1)
        assertTrue(q.findings(node = "a.OrderService#unused()")["findings"]!!.jsonArray.size == 1)
        assertEquals("module:app", q.dependencies()["modules"]!!.jsonArray.single().jsonObject.str("id"))
        val community = q.communities("a")["communities"]!!.jsonArray.first().jsonObject.str("id")
        assertContains(q.community(community).ids("members").map { it }, "a.OrderService")
    }

    @Test
    fun `explain and overview say when sources moved since the build`() {
        val repo = kotlin.io.path.createTempDirectory("jirrafe-git")
        fun git(vararg a: String) = ProcessBuilder("git", "-C", repo.toString(), *a).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor()
        git("init", "-q"); git("config", "user.email", "t@t"); git("config", "user.name", "t")
        val file = repo.resolve("A.java"); java.nio.file.Files.writeString(file, "class A {}\n")
        git("add", "."); git("commit", "-q", "-m", "one")
        val head = io.jirrafe.core.store.Git.head(repo)!!
        store.setMeta("commit", head)
        val fresh = Queries(store, repo.toString(), null, reader)
        assertTrue(!fresh.explain("how are orders listed?").containsKey("stale") && !fresh.overview().containsKey("stale"), "at the built commit nothing is stale")
        java.nio.file.Files.writeString(file, "class A { int x; }\n")
        val stale = fresh.explain("how are orders listed?")["stale"]!!.jsonObject
        assertEquals(1, stale["changedSources"]!!.jsonPrimitive.content.toInt())
        assertEquals("A.java", stale["files"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(mapOf("A.java" to listOf(1..1)), io.jirrafe.core.store.Git.changedRanges(repo), "the hunk names the changed line")
        assertTrue(!q.explain("how are orders listed?").containsKey("stale"), "a root that is not a repository stays silent")
    }

    @Test
    fun `explain puts first what an earlier session fetched after the same question`() {
        val log = kotlin.io.path.createTempDirectory("jirrafe-mem").resolve("queries.jsonl")
        val remembering = Queries(store, "/repo", null, reader, Memory(log))
        val cold = remembering.explain("how are orders listed?")
        assertTrue(cold["nodes"]!!.jsonArray.first().jsonObject.str("id") != "b.Util1#f()", "not the answer on its own")
        remembering.readSource("b.Util1#f()") // what the agent went on to read last time
        val warm = remembering.explain("how is the order list produced?")
        assertTrue(warm["pack"]!!.jsonArray.any { it.jsonObject.str("id") == "b.Util1#f()" }, "a rephrasing sharing most stems recalls it, body included")
        assertTrue(remembering.explain("how are pets vaccinated?")["nodes"]!!.jsonArray.none { it.jsonObject.str("id") == "b.Util1#f()" }, "a different question does not")
    }

    @Test
    fun `explain returns flows, communities and cited nodes within budget`() {
        val e = q.explain("how are orders listed?")
        assertContains(e.ids("flows"), "flow:a.OrderController#list()")
        assertTrue(e["communities"]!!.jsonArray.isNotEmpty())
        val nodes = e["nodes"]!!.jsonArray.map { it.jsonObject }
        assertTrue(nodes.any { it.str("id").startsWith("a.Order") })
        assertTrue(nodes.filter { it.str("kind") in setOf("class", "method") }.all { it.containsKey("at") }, "every code node is cited")
        assertTrue(!e.containsKey("next"), "no dead hint")
        assertTrue(e["flows"]!!.jsonArray.first().jsonObject.containsKey("stepCount"))
        assertTrue(e["pack"]!!.jsonArray.first().jsonObject.str("text").startsWith("// source of a."), "the bodies along the chain ride along")
        assertTrue((q.explain("how are orders listed?", budget = 200)["pack"]?.jsonArray?.size ?: 0) <= 1, "and shrink to one when the budget is tight")
        assertTrue(nodes.flatMap { n -> listOf("uses", "usedBy").flatMap { k -> n[k]?.jsonArray?.map { it.jsonObject.str("id") }.orEmpty() } }.none { it.contains("Test") }, "no test-class edge targets")
        val small = q.explain("how are orders listed?", budget = 300)
        assertTrue(Queries.tokens(small) <= 300 || small["nodes"]!!.jsonArray.size <= 2, "shrinks to the budget: ${Queries.tokens(small)} tokens")
        assertTrue(Queries.tokens(small) < Queries.tokens(e))
        assertContains(q.explain("zzz qqq").str("note"), "nothing matched")
    }
}
