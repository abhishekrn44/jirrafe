package io.jirrafe.core.knowledge

import io.jirrafe.core.model.Edge
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.store.GraphStore
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A synthetic graph: package `a` is a web slice (controller -> service -> repository), package `b`
 * a dense unrelated cluster, plus a test class, a dead method, a package cycle and a layer violation.
 */
class KnowledgeTest {
    private val store = SyntheticGraph.build(knowledge = false)

    private val result = Knowledge(store, null, Path.of(".")).build()

    @Test
    fun `communities separate the two packages and form a hierarchy`() {
        val leaves = result.leafCommunities()
        assertTrue(leaves.size >= 2, "expected at least two communities, got ${leaves.map { it.label }}")
        val ofA = store.node("a.OrderService")!!.attrs["community"]
        val ofB = store.node("b.Util1")!!.attrs["community"]
        assertNotNull(ofA); assertNotNull(ofB)
        assertTrue(ofA != ofB, "a and b should not share a community")
        assertEquals(ofA, store.node("a.OrderController")!!.attrs["community"])
        assertEquals(ofB, store.node("b.Util3")!!.attrs["community"])
        assertTrue(store.edgesFrom("a.OrderService", EdgeKind.MEMBER_OF_COMMUNITY).single().to == ofA)
        val community = store.node(ofA!!)!!
        assertContains(community.attrs["summary"]!!, "classes")
    }

    @Test
    fun `layers come from annotations, supertypes, names and edges`() {
        assertEquals("controller", store.node("a.OrderController")!!.attrs["layer"])
        assertEquals("service", store.node("a.OrderService")!!.attrs["layer"])
        assertEquals("repository", store.node("a.OrderRepository")!!.attrs["layer"])
        assertEquals("model", store.node("a.Order")!!.attrs["layer"])
        assertEquals("service", store.node("b.ReportService")!!.attrs["layer"])
    }

    @Test
    fun `flows walk from every entry point`() {
        val route = store.node("flow:a.OrderController#list()")
        assertNotNull(route)
        assertEquals("GET /orders", route.fqn)
        val steps = route.attrs["steps"]!!
        assertContains(steps, "a.OrderService#open()")
        assertContains(steps, "a.OrderRepository#findAll()")
        assertTrue("a.Order#getId()" !in steps, "leaf accessors are not steps")
        assertContains(route.attrs["summary"]!!, "OrderController.list -> OrderService.open -> OrderRepository.findAll")
        assertEquals(setOf("flow:a.OrderController#list()", "flow:a.Main#main(java.lang.String[])"), store.edgesFrom("a.OrderService#open()", EdgeKind.STEP_OF_FLOW).map { it.to }.toSet())
        assertEquals("main", store.node("flow:a.Main#main(java.lang.String[])")!!.attrs["entryKind"])
    }

    @Test
    fun `tests are linked, gods scored, findings raised`() {
        assertEquals(setOf("a.OrderService"), store.edgesFrom("a.OrderServiceTest", EdgeKind.TESTS).map { it.to }.toSet())
        assertEquals(2, store.node("a.OrderService")!!.attrs["inDegree"]!!.toInt(), "controller and main; the test class is linked, not counted")
        val findings = store.nodes(NodeKind.FINDING).groupBy { it.attrs["kind"] }
        assertEquals("a.OrderService#unused()", findings["dead-code"]!!.single { it.attrs["severity"] == "warning" }.attrs["subject"])
        assertTrue(findings["dead-code"]!!.none { it.attrs["subject"] == "a.JdbcConn#rollback()" }, "a public method of a class implementing an interface outside the graph is a contract, not dead code")
        assertEquals(1, findings["cyclic-packages"]!!.size)
        assertContains(findings["cyclic-packages"]!!.single().fqn, "a <-> b")
        assertTrue(store.edgesFrom("b", EdgeKind.HAS_FINDING).isNotEmpty(), "cycle attached to every package")
        assertEquals("a.OrderRepository", findings["layer-violation"]!!.single().attrs["subject"])
        assertTrue(findings["untested-god-node"].orEmpty().none { it.attrs["subject"] == "a.OrderService" }, "OrderService has a test")
    }

    @Test
    fun `report and html are produced and a rerun is idempotent`() {
        val report = Report.markdown(store, result)
        for (section in listOf("## Overview", "## Communities", "## Flows", "## Findings", "GET /orders", "### dead-code")) assertContains(report, section)
        val html = Html.render(result)
        assertContains(html, "\"mode\":\"classes\"")
        assertContains(html, "a.OrderService")
        val before = store.count("nodes") to store.count("edges")
        Knowledge(store, null, Path.of(".")).build()
        assertEquals(before, store.count("nodes") to store.count("edges"))
    }
}
