package io.jirrafe.core.diff

import io.jirrafe.core.knowledge.Knowledge
import io.jirrafe.core.knowledge.SyntheticGraph
import io.jirrafe.core.model.Edge
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Origin
import io.jirrafe.core.model.Resolution
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffTest {
    private val base = SyntheticGraph.build()
    private val head = SyntheticGraph.build(knowledge = false).also { store ->
        // a new method, a removed one, a changed body and a changed call
        store.node(Node("a.OrderService#cancel(long)", NodeKind.METHOD, "a.OrderService#cancel(long)", Origin.REPO, signature = "public void cancel(long)", module = "app", file = "src/a/OrderService.java", startLine = 30))
        store.edge(Edge("a.OrderService", "a.OrderService#cancel(long)", EdgeKind.CONTAINS, Resolution.EXACT))
        store.deleteNodes(listOf("a.OrderService#unused()"))
        store.node(Node("a.OrderRepository#findAll()", NodeKind.METHOD, "a.OrderRepository#findAll()", Origin.REPO, signature = "public abstract java.util.List findAll()", sha = "changed", module = "app", file = "src/a/OrderRepository.java", startLine = 10), replace = true)
        store.edge(Edge("a.OrderController#list()", "a.OrderService#cancel(long)", EdgeKind.CALLS, Resolution.EXACT))
        store.flush()
        Knowledge(store, null, Path.of(".")).build()
    }
    // the base node has no sha, so give it one that differs
    init {
        base.node(Node("a.OrderRepository#findAll()", NodeKind.METHOD, "a.OrderRepository#findAll()", Origin.REPO, signature = "public abstract java.util.List findAll()", sha = "old", module = "app", file = "src/a/OrderRepository.java", startLine = 10), replace = true)
        base.flush()
    }

    @Test
    fun `added, removed and changed members with affected callers and tests`() {
        val r = Diff.compare(base, head)
        assertEquals(listOf("a.OrderService#cancel(long)"), r.added.map { it.id })
        assertEquals(listOf("a.OrderService#unused()"), r.removed.map { it.id })
        assertEquals(mapOf("a.OrderController#list()" to "calls changed", "a.OrderRepository#findAll()" to "body changed"), r.changed.associate { it.id to it.what })
        assertContains(r.affected.keys, "a.OrderService#open()")
        assertEquals(1, r.affected["a.OrderService#open()"])
        assertContains(r.tests, "a.OrderServiceTest")
        assertTrue(r.byFlow.keys.any { "GET /orders" in it })
        val md = Diff.markdown(r, "main", "feature")
        assertContains(md, "| count | 1 | 1 | 2 |")
        assertContains(md, "`a.OrderService#unused()` method: removed")
        assertContains(md, "### Tests to run")
        assertContains(md, "src/a/OrderRepository.java:10")
    }

    @Test
    fun `identical graphs produce no changes`() {
        val r = Diff.compare(base, base)
        assertTrue(r.isEmpty)
        assertContains(Diff.markdown(r), "No structural changes")
    }
}
