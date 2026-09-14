package io.jirrafe.core.knowledge

import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Origin
import io.jirrafe.core.model.Resolution
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One flow per entry point (HTTP route, message consumer, scheduled job, `main`): a depth-first
 * walk of CALLS and DISPATCHES_TO to the leaves, deduplicated, bounded, with the artifacts crossed.
 * This is the precomputed answer to "how does X work end to end".
 */
internal object Flows {
    @Serializable
    data class Step(val id: String, val depth: Int, val via: Resolution)

    class Flow(
        val entry: Node, val kind: String, val label: String, val steps: List<Step>,
        val artifacts: List<String>, val modules: List<String>, val external: List<String>,
    )

    private const val MAX_DEPTH = 10
    private const val MAX_STEPS = 150
    val json = Json { encodeDefaults = false }

    fun compute(g: ClassGraph): List<Flow> {
        val entries = LinkedHashMap<String, Pair<String, String>>() // method id -> (kind, label)
        for (e in g.outgoing.values.flatten()) when (e.kind) {
            EdgeKind.HANDLES_ROUTE -> g.nodes[e.to]?.takeIf { it.attrs["remote"] != "true" }?.let { entries.putIfAbsent(e.from, "route" to it.fqn) }
            EdgeKind.CONSUMES_FROM -> g.nodes[e.to]?.let { entries.putIfAbsent(e.from, "consumer" to "consume ${it.fqn}") }
            EdgeKind.CALLS -> if (e.from.startsWith("job:")) entries.putIfAbsent(e.to, "job" to "scheduled ${g.shortName(e.to)}")
            else -> {}
        }
        for (n in g.nodes.values) {
            if (n.kind == NodeKind.METHOD && n.origin == Origin.REPO && n.id.endsWith("#main(java.lang.String[])") && !ClassGraph.isTest(n)) {
                entries.putIfAbsent(n.id, "main" to "main ${g.shortName(n.id)}")
            }
        }
        return entries.mapNotNull { (id, entry) -> g.nodes[id]?.let { walk(g, it, entry.first, entry.second) } }
            .sortedWith(compareBy({ KIND_ORDER.indexOf(it.kind) }, { it.label }))
    }

    private val KIND_ORDER = listOf("route", "consumer", "job", "main")

    private fun walk(g: ClassGraph, entry: Node, kind: String, label: String): Flow {
        val steps = ArrayList<Step>()
        val seen = HashSet<String>()
        val external = LinkedHashSet<String>()
        fun visit(id: String, depth: Int, via: Resolution) {
            if (!seen.add(id) || steps.size >= MAX_STEPS) return
            val node = g.nodes[id] ?: return
            if (node.origin == Origin.EXTERNAL) { external += id; return }
            if (ClassGraph.isTest(node)) return
            steps += Step(id, depth, via)
            if (depth >= MAX_DEPTH) return
            val calls = g.outgoing[id].orEmpty().filter { it.kind == EdgeKind.CALLS || it.kind == EdgeKind.DISPATCHES_TO }
            for (e in calls.sortedWith(compareBy({ it.kind != EdgeKind.CALLS }, { it.to }))) {
                if (trivial(g, e.to)) continue
                visit(e.to, depth + 1, e.resolution)
            }
        }
        visit(entry.id, 0, Resolution.EXACT)
        val nodes = steps.mapNotNull { g.nodes[it.id] }
        return Flow(
            entry, kind, label, steps,
            artifacts = nodes.mapNotNull { it.artifact }.distinct(),
            modules = nodes.mapNotNull { it.module }.distinct(),
            external = external.take(10),
        )
    }

    /** Constructors and leaf accessors add nothing to a flow's story. */
    private fun trivial(g: ClassGraph, id: String): Boolean {
        val member = id.substringAfter('#', "")
        if (member.startsWith("<")) return true
        val name = member.substringBefore('(')
        val accessor = (name.startsWith("get") || name.startsWith("set") || name.startsWith("is")) && name.length > 3
        return accessor && g.outgoing[id].orEmpty().none { it.kind == EdgeKind.CALLS }
    }

    fun summary(g: ClassGraph, f: Flow): String {
        val path = f.steps.take(6).joinToString(" -> ") { g.shortName(it.id) }
        val more = if (f.steps.size > 6) " ... (${f.steps.size} steps)" else ""
        val crosses = if (f.artifacts.isEmpty()) "" else "; crosses ${f.artifacts.joinToString()}"
        val ext = if (f.external.isEmpty()) "" else "; external: ${f.external.take(3).joinToString { g.shortName(it) }}"
        return "${f.label}: $path$more$crosses$ext"
    }
}
