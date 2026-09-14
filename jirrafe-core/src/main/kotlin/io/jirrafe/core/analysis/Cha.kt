package io.jirrafe.core.analysis

import io.jirrafe.core.model.Edge
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Resolution
import io.jirrafe.core.store.GraphStore

/**
 * Class-hierarchy analysis: for every CALLS edge to an overridable method, add DISPATCHES_TO
 * edges to each subtype in the indexed universe that declares the same `name(args)`.
 * Confidence is `1 / implementations`. Derived data: recomputed from scratch each run.
 */
object Cha {
    fun compute(store: GraphStore): Int {
        store.deleteEdges(EdgeKind.DISPATCHES_TO)

        val children = HashMap<String, MutableList<String>>()
        for (e in store.edges(EdgeKind.EXTENDS) + store.edges(EdgeKind.IMPLEMENTS)) {
            children.getOrPut(e.to) { ArrayList() } += e.from
        }
        // class -> member signature -> overridable
        val methods = HashMap<String, MutableMap<String, Boolean>>()
        store.forEachSignature(NodeKind.METHOD) { id, signature ->
            val h = id.indexOf('#')
            if (h > 0) methods.getOrPut(id.substring(0, h)) { HashMap() }[id.substring(h + 1)] = overridable(signature)
        }
        val descendants = HashMap<String, List<String>>()
        fun descendantsOf(cls: String): List<String> = descendants.getOrPut(cls) {
            val seen = LinkedHashSet<String>()
            val queue = ArrayDeque(children[cls].orEmpty())
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                if (seen.add(c)) queue += children[c].orEmpty()
            }
            seen.toList()
        }

        var added = 0
        for (call in store.edges(EdgeKind.CALLS)) {
            val h = call.to.indexOf('#')
            if (h < 0) continue
            val owner = call.to.substring(0, h)
            val member = call.to.substring(h + 1)
            if (member.startsWith("<")) continue
            if (methods[owner]?.get(member) == false) continue
            val impls = descendantsOf(owner).filter { methods[it]?.containsKey(member) == true }
            if (impls.isEmpty()) continue
            val confidence = 1.0 / impls.size
            for (impl in impls) {
                store.edge(Edge(call.from, "$impl#$member", EdgeKind.DISPATCHES_TO, Resolution.CHA, confidence))
                added++
            }
        }
        store.flush()
        return added
    }

    private fun overridable(signature: String?): Boolean {
        if (signature == null) return true
        val modifiers = signature.substringBefore('(').substringBeforeLast(' ', "")
        return listOf("static", "private", "final").none { it in modifiers.split(' ') }
    }
}
