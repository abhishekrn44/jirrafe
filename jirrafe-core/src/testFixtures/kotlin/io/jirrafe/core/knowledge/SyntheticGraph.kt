package io.jirrafe.core.knowledge

import io.jirrafe.core.model.Attrs
import io.jirrafe.core.model.Edge
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Origin
import io.jirrafe.core.model.Resolution
import io.jirrafe.core.store.GraphStore
import java.nio.file.Path

/**
 * A small hand-built graph shared by tests: package `a` is a web slice (controller -> service ->
 * repository), package `b` a dense unrelated cluster, plus a test class, a dead method, a package
 * cycle and a layer violation. [build] also runs the knowledge layer.
 */
object SyntheticGraph {
    fun build(store: GraphStore = GraphStore.inMemory(), knowledge: Boolean = true): GraphStore {
        fun cls(id: String, kind: NodeKind = NodeKind.CLASS, annotations: Map<String, Map<String, String>> = emptyMap(), test: Boolean = false, doc: String? = null) {
            val attrs = buildMap {
                Attrs.encodeAnnotations(annotations)?.let { put(Attrs.ANNOTATIONS, it) }
                if (test) put("test", "true")
            }
            store.node(Node(id, kind, id, Origin.REPO, module = "app", file = "src/${id.replace('.', '/')}.java", startLine = 1, endLine = 100, doc = doc, attrs = attrs))
            store.edge(Edge(id.substringBeforeLast('.'), id, EdgeKind.CONTAINS, Resolution.EXACT))
        }
        fun method(id: String, signature: String = "public void ${id.substringAfter('#')}", annotations: Map<String, Map<String, String>> = emptyMap(), doc: String? = null) {
            val attrs = buildMap { Attrs.encodeAnnotations(annotations)?.let { put(Attrs.ANNOTATIONS, it) } }
            store.node(Node(id, NodeKind.METHOD, id, Origin.REPO, signature = signature, module = "app", file = "src/${id.substringBefore('#').replace('.', '/')}.java", startLine = 10, endLine = 20, doc = doc, attrs = attrs))
            store.edge(Edge(id.substringBefore('#'), id, EdgeKind.CONTAINS, Resolution.EXACT))
        }
        fun calls(from: String, to: String) = store.edge(Edge(from, to, EdgeKind.CALLS, Resolution.EXACT))

        store.node(Node("a", NodeKind.PACKAGE, "a", Origin.REPO))
        store.node(Node("b", NodeKind.PACKAGE, "b", Origin.REPO))
        cls("a.OrderController", annotations = mapOf("org.springframework.web.bind.annotation.RestController" to emptyMap()), doc = "REST endpoints for orders")
        cls("a.OrderService", annotations = mapOf("org.springframework.stereotype.Service" to emptyMap()), doc = "Business rules for placing and listing orders")
        cls("a.OrderRepository", NodeKind.INTERFACE)
        store.edge(Edge("a.OrderRepository", "org.springframework.data.jpa.repository.JpaRepository", EdgeKind.EXTENDS, Resolution.EXACT))
        cls("a.Order")
        cls("a.OrderServiceTest", test = true)
        cls("a.Main")
        method("a.OrderController#list()"); method("a.OrderService#open()", doc = "Open orders"); method("a.OrderService#unused()", "private void unused()")
        // notify() writes a map entry under a literal key nothing else in the graph mentions
        store.node(Node("a.OrderService#notify()", NodeKind.METHOD, "a.OrderService#notify()", Origin.REPO, signature = "private void notify", module = "app", file = "src/a/OrderService.java", startLine = 22, endLine = 24,
            attrs = mapOf(Attrs.STRINGS to Attrs.encodeStrings(listOf("channel", "Opening orders"))!!)))
        store.edge(Edge("a.OrderService", "a.OrderService#notify()", EdgeKind.CONTAINS, Resolution.EXACT))
        store.node(Node("java.util.Map#put(java.lang.Object,java.lang.Object)", NodeKind.METHOD, "put", Origin.EXTERNAL))
        calls("a.OrderService#notify()", "java.util.Map#put(java.lang.Object,java.lang.Object)")
        calls("a.OrderService#open()", "a.OrderService#notify()")
        // implements an interface outside the graph: its public methods are a contract the framework calls, so `rollback()` has no caller and is not dead
        store.node(Node("a.JdbcConn", NodeKind.CLASS, "a.JdbcConn", Origin.REPO, signature = "public class JdbcConn implements Connection", module = "app", file = "src/a/JdbcConn.java", startLine = 1, endLine = 50))
        store.edge(Edge("a", "a.JdbcConn", EdgeKind.CONTAINS, Resolution.EXACT))
        store.edge(Edge("a.OrderService", "a.JdbcConn", EdgeKind.USES_TYPE, Resolution.EXACT))
        method("a.JdbcConn#rollback()")
        method("a.OrderRepository#findAll()", "public abstract java.util.List findAll()"); method("a.Order#getId()"); method("a.Order#setId(long)")
        method("a.OrderServiceTest#opens()", annotations = mapOf("org.junit.jupiter.api.Test" to emptyMap()))
        method("a.Main#main(java.lang.String[])", "public static void main(java.lang.String[])")
        calls("a.OrderController#list()", "a.OrderService#open()")
        calls("a.OrderService#open()", "a.OrderRepository#findAll()")
        calls("a.OrderService#open()", "a.Order#getId()")
        calls("a.OrderServiceTest#opens()", "a.OrderService#open()")
        calls("a.Main#main(java.lang.String[])", "a.OrderService#open()")
        store.edge(Edge("a.OrderController", "a.OrderService", EdgeKind.INJECTS, Resolution.SPRING))
        store.edge(Edge("a.OrderService", "a.OrderRepository", EdgeKind.INJECTS, Resolution.SPRING))
        store.node(Node("route:GET /orders", NodeKind.HTTP_ROUTE, "GET /orders", Origin.REPO, attrs = mapOf("verb" to "GET", "path" to "/orders", "handler" to "a.OrderController#list()")))
        store.edge(Edge("a.OrderController#list()", "route:GET /orders", EdgeKind.HANDLES_ROUTE, Resolution.SPRING))
        store.node(Node("config:orders.max", NodeKind.CONFIG_KEY, "orders.max", Origin.REPO, file = "src/application.yml", startLine = 3, attrs = mapOf("value" to "10", "defined" to "true")))
        store.edge(Edge("a.OrderService", "config:orders.max", EdgeKind.BINDS_CONFIG, Resolution.SPRING))
        store.node(Node("bean:orderService", NodeKind.BEAN, "orderService", Origin.REPO, attrs = mapOf("type" to "a.OrderService", "stereotype" to "Service")))
        store.edge(Edge("a.OrderService", "bean:orderService", EdgeKind.PROVIDES_BEAN, Resolution.SPRING))
        // layer violation and package cycle: the repository calls back into a service in b, which uses a.Order
        cls("b.ReportService"); cls("b.Util1"); cls("b.Util2"); cls("b.Util3")
        method("b.ReportService#run()"); method("b.Util1#f()"); method("b.Util2#f()"); method("b.Util3#f()")
        calls("a.OrderRepository#findAll()", "b.ReportService#run()")
        store.edge(Edge("b.ReportService", "a.Order", EdgeKind.USES_TYPE, Resolution.EXACT))
        for (x in listOf("Util1", "Util2", "Util3")) { calls("b.ReportService#run()", "b.$x#f()"); for (y in listOf("Util1", "Util2", "Util3")) if (x != y) calls("b.$x#f()", "b.$y#f()") }
        // dead code of each shape the rule knows: a class nothing but its test references, a class referenced only by that
        // one, a constant nothing reads, and an implementation of a repo interface that nothing calls through either
        store.node(Node("c", NodeKind.PACKAGE, "c", Origin.REPO))
        cls("c.LegacyExport"); cls("c.LegacyRow"); cls("c.LegacyExportTest", test = true)
        method("c.LegacyExport#run()"); method("c.LegacyExportTest#runs()", annotations = mapOf("org.junit.jupiter.api.Test" to emptyMap()))
        store.edge(Edge("c.LegacyExport", "c.LegacyRow", EdgeKind.USES_TYPE, Resolution.EXACT))
        calls("c.LegacyExportTest#runs()", "c.LegacyExport#run()")
        store.node(Node("a.OrderService#MAX_OPEN", NodeKind.FIELD, "a.OrderService#MAX_OPEN", Origin.REPO, signature = "private static final MAX_OPEN: int", module = "app", file = "src/a/OrderService.java", startLine = 5, endLine = 5))
        store.edge(Edge("a.OrderService", "a.OrderService#MAX_OPEN", EdgeKind.CONTAINS, Resolution.EXACT))
        cls("a.ReportApi", NodeKind.INTERFACE); method("a.ReportApi#export()", "public abstract void export()"); method("a.OrderService#export()")
        store.edge(Edge("a.OrderService", "a.ReportApi", EdgeKind.IMPLEMENTS, Resolution.EXACT))
        store.edge(Edge("a.OrderService#export()", "a.ReportApi#export()", EdgeKind.OVERRIDES, Resolution.EXACT))
        // and what the rule must leave alone: a Lombok setter on the class line, a logger, a repository nothing injects is a bean
        store.node(Node("a.Order#setName(java.lang.String)", NodeKind.METHOD, "a.Order#setName(java.lang.String)", Origin.REPO, signature = "public void setName(java.lang.String)", module = "app", file = "src/a/Order.java", startLine = 1, endLine = 1))
        store.edge(Edge("a.Order", "a.Order#setName(java.lang.String)", EdgeKind.CONTAINS, Resolution.EXACT))
        store.node(Node("a.OrderService#log", NodeKind.FIELD, "a.OrderService#log", Origin.REPO, signature = "private static final log: org.slf4j.Logger", module = "app", file = "src/a/OrderService.java", startLine = 4, endLine = 4))
        store.edge(Edge("a.OrderService", "a.OrderService#log", EdgeKind.CONTAINS, Resolution.EXACT))
        store.node(Node("a.Order#id", NodeKind.FIELD, "a.Order#id", Origin.REPO, signature = "private id: long", module = "app", file = "src/a/Order.java", startLine = 3, endLine = 3))
        store.edge(Edge("a.Order", "a.Order#id", EdgeKind.CONTAINS, Resolution.EXACT))
        cls("a.AuditRepository", NodeKind.INTERFACE)
        store.edge(Edge("a.AuditRepository", "org.springframework.data.jpa.repository.JpaRepository", EdgeKind.EXTENDS, Resolution.EXACT))
        store.node(Node("bean:auditRepository", NodeKind.BEAN, "auditRepository", Origin.REPO, attrs = mapOf("type" to "a.AuditRepository", "stereotype" to "Repository")))
        store.edge(Edge("a.AuditRepository", "bean:auditRepository", EdgeKind.PROVIDES_BEAN, Resolution.SPRING))
        store.node(Node("org.springframework.data.jpa.repository.JpaRepository", NodeKind.INTERFACE, "JpaRepository", Origin.EXTERNAL))
        store.node(Node("module:app", NodeKind.MODULE, ":app", Origin.REPO, module = "app", attrs = mapOf("group" to "com.example", "version" to "1.0")))
        store.flush()
        store.setMeta("root", "/repo"); store.setMeta("buildTool", "gradle")
        if (knowledge) Knowledge(store, null, Path.of(".")).build()
        return store
    }
}
