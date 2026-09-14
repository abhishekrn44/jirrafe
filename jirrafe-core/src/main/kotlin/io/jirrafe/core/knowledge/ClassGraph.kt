package io.jirrafe.core.knowledge

import io.jirrafe.core.model.Edge
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Origin
import io.jirrafe.core.store.GraphStore

/**
 * The graph folded to class level: every member edge becomes a weighted edge between the owning
 * classes. Test classes are kept in [nodes] but excluded from [classes], so they are linked to what
 * they test without being clustered as logic. EXTERNAL stubs are excluded entirely.
 */
internal class ClassGraph(store: GraphStore) {
    val nodes: Map<String, Node> = store.nodes().associateBy { it.id }
    val classes: List<Node> = nodes.values.filter { isCode(it) && it.origin != Origin.EXTERNAL && !isTest(it) }.sortedBy { it.id }
    val index: Map<String, Int> = classes.withIndex().associate { (i, n) -> n.id to i }

    /** Directed, weighted: `deps[i][j]` = how strongly class i depends on class j. */
    val deps: Array<MutableMap<Int, Double>> = Array(classes.size) { HashMap() }
    val dependents: Array<MutableSet<Int>> = Array(classes.size) { HashSet() }

    /** Every outgoing edge per node id, for call-graph walks and member lookup. */
    val outgoing: Map<String, List<Edge>>

    init {
        val out = HashMap<String, MutableList<Edge>>()
        for (kind in EdgeKind.entries) {
            val weight = WEIGHTS[kind]
            for (e in store.edges(kind)) {
                out.getOrPut(e.from) { ArrayList() } += e
                if (weight == null) continue
                val i = index[owner(e.from)] ?: continue
                val j = index[owner(e.to)] ?: continue
                if (i == j) continue
                val w = if (kind == EdgeKind.DISPATCHES_TO) weight * e.confidence else weight
                deps[i].merge(j, w, Double::plus)
                dependents[j] += i
            }
        }
        outgoing = out
    }

    /** Undirected projection, each pair once with summed weight. */
    fun undirected(): Array<MutableMap<Int, Double>> {
        val u = Array<MutableMap<Int, Double>>(classes.size) { HashMap() }
        for (i in deps.indices) for ((j, w) in deps[i]) {
            u[i].merge(j, w, Double::plus)
            u[j].merge(i, w, Double::plus)
        }
        return u
    }

    fun packageOf(cls: Node): String = cls.id.substringBeforeLast('.', "")

    fun shortName(id: String): String = owner(id).substringAfterLast('.').replace('$', '.') +
        (if ('#' in id) "." + id.substringAfter('#').substringBefore('(') else "")

    companion object {
        val WEIGHTS = linkedMapOf(
            EdgeKind.CALLS to 1.0, EdgeKind.DISPATCHES_TO to 1.0, EdgeKind.INJECTS to 2.0,
            EdgeKind.EXTENDS to 3.0, EdgeKind.IMPLEMENTS to 3.0, EdgeKind.USES_TYPE to 0.5,
            EdgeKind.READS_FIELD to 1.0, EdgeKind.WRITES_FIELD to 1.0,
        )
        private val CODE = setOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.ENUM, NodeKind.RECORD, NodeKind.ANNOTATION)

        fun isCode(n: Node) = n.kind in CODE
        fun isTest(n: Node) = n.attrs["test"] == "true"

        /** The class that owns a member id, or the id itself. */
        fun owner(id: String): String = id.substringBefore('#')
    }
}
