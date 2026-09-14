package io.jirrafe.core.diff

import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Origin
import io.jirrafe.core.store.GraphStore

/**
 * Structural diff between two graphs of the same project: added, removed and changed classes,
 * members and wiring nodes, with the callers the head graph says are affected, grouped by
 * community and flow, and the tests to run. Markdown output is sized for a PR comment.
 */
object Diff {
    class Change(val id: String, val kind: NodeKind, val what: String, val at: String?)

    class Result(
        val added: List<Change>, val removed: List<Change>, val changed: List<Change>,
        val affected: Map<String, Int>, val byCommunity: Map<String, Int>, val byFlow: Map<String, Int>,
        val tests: List<String>, val findings: Pair<Int, Int>,
    ) {
        val isEmpty get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
    }

    private val CODE = setOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.ENUM, NodeKind.RECORD, NodeKind.ANNOTATION, NodeKind.METHOD, NodeKind.CONSTRUCTOR, NodeKind.FIELD)
    private val WIRING = setOf(NodeKind.HTTP_ROUTE, NodeKind.MESSAGE_TOPIC, NodeKind.CONFIG_KEY, NodeKind.BEAN, NodeKind.SCHEDULED_JOB)

    fun compare(base: GraphStore, head: GraphStore, depth: Int = 3): Result {
        val root = head.meta("root").orEmpty()
        fun at(n: Node) = n.file?.let { f -> f.removePrefix(root).trimStart('/', '\\').replace('\\', '/') + (n.startLine?.let { ":$it" } ?: "") }
        fun repo(store: GraphStore) = (CODE + WIRING).flatMap { store.nodes(it) }.filter { it.origin == Origin.REPO && it.attrs["test"] != "true" }.associateBy { it.id }
        val before = repo(base)
        val after = repo(head)

        val added = after.values.filter { it.id !in before }.map { Change(it.id, it.kind, "added", at(it)) }
        val removed = before.values.filter { it.id !in after }.map { Change(it.id, it.kind, "removed", at(it)) }
        val changed = after.values.mapNotNull { n ->
            val old = before[n.id] ?: return@mapNotNull null
            val what = when {
                n.kind in WIRING -> if (n.attrs.filterKeys { it != "handler" } != old.attrs.filterKeys { it != "handler" }) "attributes changed" else null
                n.sha != null && old.sha != null -> if (n.sha != old.sha) "body changed" else null
                n.signature != old.signature -> "signature changed"
                else -> if (callees(head, n.id) != callees(base, n.id)) "calls changed" else null
            }
            what?.let { Change(n.id, n.kind, it, at(n)) }
        }

        // Callers in the head graph of everything that changed or disappeared (removed ids still have callers in base; use head for the rest).
        val seeds = (changed.map { it.id } + removed.map { it.id }).toSet()
        val affected = LinkedHashMap<String, Int>()
        var frontier = seeds.toList()
        for (d in 1..depth) {
            val next = ArrayList<String>()
            for (t in frontier) {
                val store = if (t in after) head else base
                for (e in store.edgesTo(t)) {
                    if (e.kind != EdgeKind.CALLS && e.kind != EdgeKind.DISPATCHES_TO && e.kind != EdgeKind.INJECTS && e.kind != EdgeKind.USES_TYPE && e.kind != EdgeKind.OVERRIDES) continue
                    if (e.from in seeds) continue
                    if (affected.putIfAbsent(e.from, d) == null) next += e.from
                }
            }
            frontier = next
            if (affected.size > 3000) break
        }
        val byCommunity = affected.keys.groupingBy { head.node(it.substringBefore('#'))?.attrs?.get("community") ?: "none" }.eachCount()
        val byFlow = LinkedHashMap<String, Int>()
        for (id in seeds + affected.keys) for (e in head.edgesFrom(id, EdgeKind.STEP_OF_FLOW)) byFlow.merge(head.node(e.to)?.fqn ?: e.to, 1, Int::plus)
        val tests = LinkedHashSet<String>()
        for (id in (seeds + affected.keys).map { it.substringBefore('#') }.toSet()) for (e in head.edgesTo(id, EdgeKind.TESTS)) tests += e.from
        for (id in affected.keys) head.node(id.substringBefore('#'))?.takeIf { it.attrs["test"] == "true" }?.let { tests += it.id }
        return Result(
            added.sortedBy { it.id }, removed.sortedBy { it.id }, changed.sortedBy { it.id }, affected,
            byCommunity.entries.sortedByDescending { it.value }.associate { it.key to it.value },
            byFlow.entries.sortedByDescending { it.value }.associate { it.key to it.value },
            tests.toList(), head.nodes(NodeKind.FINDING).size to base.nodes(NodeKind.FINDING).size,
        )
    }

    private fun callees(store: GraphStore, id: String): Set<String> = store.edgesFrom(id, EdgeKind.CALLS).map { it.to }.toSet()

    fun markdown(r: Result, baseName: String = "base", headName: String = "head", maxRows: Int = 40): String = buildString {
        appendLine("## jirrafe structural diff: `$baseName` -> `$headName`")
        appendLine()
        if (r.isEmpty) { appendLine("No structural changes."); return@buildString }
        appendLine("| | added | removed | changed | affected callers | tests to run |")
        appendLine("|---|---|---|---|---|---|")
        appendLine("| count | ${r.added.size} | ${r.removed.size} | ${r.changed.size} | ${r.affected.size} | ${r.tests.size} |")
        appendLine()
        fun section(title: String, list: List<Change>) {
            if (list.isEmpty()) return
            appendLine("### $title (${list.size})")
            appendLine()
            for (c in list.take(maxRows)) appendLine("- `${c.id}` ${c.kind.name.lowercase()}: ${c.what}" + (c.at?.let { " (`$it`)" } ?: ""))
            if (list.size > maxRows) appendLine("- ... and ${list.size - maxRows} more")
            appendLine()
        }
        section("Removed", r.removed)
        section("Changed", r.changed)
        section("Added", r.added)
        if (r.affected.isNotEmpty()) {
            appendLine("### Affected callers (${r.affected.size}, up to depth ${r.affected.values.max()})")
            appendLine()
            if (r.byCommunity.isNotEmpty()) appendLine("By community: " + r.byCommunity.entries.take(8).joinToString { "`${it.key}` ${it.value}" })
            if (r.byFlow.isNotEmpty()) appendLine("Flows touched: " + r.byFlow.entries.take(8).joinToString { "${it.key} (${it.value})" })
            appendLine()
            for ((id, d) in r.affected.entries.take(maxRows)) appendLine("- `$id` (distance $d)")
            if (r.affected.size > maxRows) appendLine("- ... and ${r.affected.size - maxRows} more")
            appendLine()
        }
        if (r.tests.isNotEmpty()) {
            appendLine("### Tests to run")
            appendLine()
            for (t in r.tests.take(maxRows)) appendLine("- `$t`")
            appendLine()
        }
        appendLine("Findings: ${r.findings.second} -> ${r.findings.first}")
    }
}
