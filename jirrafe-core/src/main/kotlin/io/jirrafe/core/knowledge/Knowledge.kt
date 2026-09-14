package io.jirrafe.core.knowledge

import io.jirrafe.core.manifest.Manifest
import io.jirrafe.core.model.Edge
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Origin
import io.jirrafe.core.model.Resolution
import io.jirrafe.core.store.GraphStore
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Path

/**
 * The knowledge layer: communities, layers, god nodes, flows, structural findings and test links,
 * derived from the indexed graph and written back as nodes, edges and attributes. Everything here
 * is recomputed from scratch on each run, so it is idempotent and needs no incremental bookkeeping.
 */
class Knowledge(
    private val store: GraphStore,
    private val manifest: Manifest?,
    private val root: Path,
    private val log: (String) -> Unit = {},
) {
    class Community(val id: String, val level: Int, val label: String, val members: List<Int>, val parent: String?, val gods: List<Int>) {
        val children = ArrayList<Community>()
    }

    class Result internal constructor(
        internal val g: ClassGraph,
        val communities: List<Community>,
        val levels: Int,
        internal val layers: Array<String?>,
        internal val scores: Gods.Scores,
        val gods: List<Int>,
        internal val flows: List<Flows.Flow>,
        internal val findings: List<Findings.Finding>,
        val tests: List<Edge>,
    ) {
        val classCount get() = g.classes.size
        val flowCount get() = flows.size
        val findingCount get() = findings.size
        fun layerCounts(): Map<String, Int> = layers.filterNotNull().groupingBy { it }.eachCount()
        fun leafCommunities() = communities.filter { it.level == 0 }
        fun summary(): String =
            "$classCount classes in ${leafCommunities().size} communities (hierarchy depth $levels), " +
                "${gods.size} god nodes, ${flows.size} flows, ${findings.size} findings, ${tests.size} test links"
    }

    fun build(): Result {
        log("clearing derived knowledge")
        store.deleteKinds(setOf(NodeKind.COMMUNITY, NodeKind.FLOW))
        store.deleteEdges(EdgeKind.TESTS)
        store.deleteNodes(store.nodes(NodeKind.FINDING).filter { it.attrs["kind"] in Findings.KINDS }.map { it.id })

        val g = ClassGraph(store)
        log("class graph: ${g.classes.size} classes")
        val layers = Layers.classify(g, store)
        val scores = Gods.compute(g)
        val hierarchy = Communities.detect(g)
        val communities = communities(g, hierarchy, scores)
        val gods = g.classes.indices.filter { scores.inDegree[it] >= 2 }.sortedByDescending { scores.score(it) }.take(10)
        val flows = Flows.compute(g)
        val tests = Findings.testLinks(g)
        val tested = tests.map { it.to }.toSet()
        val findings = Findings.compute(g, layers, gods, tested, manifest, root, store)
        log("communities ${communities.size}, flows ${flows.size}, findings ${findings.size}")

        write(g, communities, layers, scores, gods, flows, tests, findings)
        return Result(g, communities, hierarchy.depth, layers, scores, gods, flows, findings, tests)
    }

    private fun communities(g: ClassGraph, h: Communities.Hierarchy, scores: Gods.Scores): List<Community> {
        if (h.depth == 0) return emptyList()
        val out = ArrayList<Community>()
        val byId = HashMap<String, Community>()
        // members of every cluster at every level, walking each class up through its parents
        val members = List(h.depth) { level -> Array(h.clustersAt(level)) { ArrayList<Int>() } }
        for (cls in g.classes.indices) {
            var cluster = h.leafOf(cls)
            for (level in 0 until h.depth) {
                members[level][cluster] += cls
                if (level + 1 < h.depth) cluster = h.parentOf(level, cluster)
            }
        }
        for (level in h.depth - 1 downTo 0) for (cluster in 0 until h.clustersAt(level)) {
            val m = members[level][cluster]
            if (m.isEmpty()) continue
            val parent = if (level + 1 < h.depth) "community:L${level + 1}-${h.parentOf(level, cluster)}" else null
            val gods = m.filter { scores.inDegree[it] >= 1 }.sortedByDescending { scores.score(it) }.take(3)
            val c = Community("community:L$level-$cluster", level, label(g, m), m, parent, gods)
            out += c
            byId[c.id] = c
            parent?.let { byId[it]?.children?.add(c) }
        }
        return out
    }

    private fun label(g: ClassGraph, members: List<Int>): String {
        val packages = members.groupingBy { g.packageOf(g.classes[it]) }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key }))
        val top = packages.first().key.ifEmpty { "(default package)" }
        return if (packages.size == 1) top else "$top (+${packages.size - 1} packages)"
    }

    private fun write(
        g: ClassGraph, communities: List<Community>, layers: Array<String?>, scores: Gods.Scores, gods: List<Int>,
        flows: List<Flows.Flow>, tests: List<Edge>, findings: List<Findings.Finding>,
    ) {
        val attrs = HashMap<String, MutableMap<String, String>>()
        fun attr(i: Int, key: String, value: String) { attrs.getOrPut(g.classes[i].id) { HashMap() }[key] = value }
        for (i in g.classes.indices) {
            layers[i]?.let { attr(i, "layer", it) }
            attr(i, "inDegree", scores.inDegree[i].toString())
            attr(i, "betweenness", "%.1f".format(scores.betweenness[i]))
        }
        for (i in gods) attr(i, "god", "global")

        for (c in communities) {
            val dominant = c.members.groupingBy { g.classes[it].module ?: g.classes[it].artifact ?: "" }.eachCount().maxByOrNull { it.value }?.key
            val layerCounts = c.members.mapNotNull { layers[it] }.groupingBy { it }.eachCount()
            val packages = c.members.map { g.packageOf(g.classes[it]) }.distinct().sorted()
            store.node(
                Node(
                    c.id, NodeKind.COMMUNITY, c.label, Origin.REPO, doc = summary(g, c, layerCounts),
                    module = dominant?.takeUnless { it.startsWith("artifact:") || it.isEmpty() },
                    attrs = buildMap {
                        put("level", c.level.toString()); put("size", c.members.size.toString())
                        put("packages", packages.take(5).joinToString(","))
                        put("layers", Layers.ORDER.filter { it in layerCounts }.joinToString(",") { "$it:${layerCounts[it]}" })
                        put("gods", c.gods.joinToString(",") { g.classes[it].id })
                        put("summary", summary(g, c, layerCounts))
                        c.parent?.let { put("parent", it) }
                    },
                )
            )
            c.parent?.let { store.edge(Edge(it, c.id, EdgeKind.CONTAINS, Resolution.EXACT)) }
            if (c.level == 0) for (i in c.members) {
                attr(i, "community", c.id)
                store.edge(Edge(g.classes[i].id, c.id, EdgeKind.MEMBER_OF_COMMUNITY, Resolution.EXACT))
                if (i in c.gods && i !in gods) attr(i, "god", "community")
            }
        }

        for (f in flows) {
            val id = "flow:${f.entry.id}"
            val classes = f.steps.mapNotNull { g.index[ClassGraph.owner(it.id)] }.distinct()
            store.node(
                Node(
                    id, NodeKind.FLOW, f.label, f.entry.origin, file = f.entry.file, startLine = f.entry.startLine, module = f.entry.module,
                    doc = Flows.summary(g, f),
                    attrs = buildMap {
                        put("entry", f.entry.id); put("entryKind", f.kind)
                        put("steps", Flows.json.encodeToString(ListSerializer(Flows.Step.serializer()), f.steps))
                        put("stepCount", f.steps.size.toString())
                        put("artifacts", f.artifacts.joinToString(",")); put("modules", f.modules.joinToString(","))
                        put("communities", classes.mapNotNull { attrs[g.classes[it].id]?.get("community") }.distinct().joinToString(","))
                        put("external", f.external.joinToString(","))
                        put("summary", Flows.summary(g, f))
                    },
                )
            )
            for (s in f.steps) store.edge(Edge(s.id, id, EdgeKind.STEP_OF_FLOW, Resolution.EXACT, 1.0 / (s.depth + 1)))
        }

        for (e in tests) store.edge(e)

        for (f in findings) {
            store.node(Findings.toNode(f, g.nodes[f.subject] ?: store.node(f.subject)))
            for (s in listOf(f.subject) + f.alsoOn) store.edge(Edge(s, f.id, EdgeKind.HAS_FINDING, Resolution.EXACT))
        }
        store.flush()
        store.setAttrs(attrs)
        store.setMeta("knowledge.gods", gods.joinToString(",") { g.classes[it].id })
        store.setMeta("knowledge.levels", communities.maxOfOrNull { it.level + 1 }?.toString() ?: "0")
        store.rebuildSearchIndex()
    }

    private fun summary(g: ClassGraph, c: Community, layerCounts: Map<String, Int>): String {
        val packages = c.members.groupingBy { g.packageOf(g.classes[it]) }.eachCount().entries.sortedByDescending { it.value }
        val where = if (packages.size == 1) "in ${packages.first().key}" else "in ${packages.size} packages, mostly ${packages.first().key} (${packages.first().value})"
        val layers = Layers.ORDER.filter { it in layerCounts }.joinToString(", ") { "$it ${layerCounts[it]}" }
        val central = c.gods.joinToString(", ") { g.shortName(g.classes[it].id) }
        return "${c.members.size} classes $where." +
            (if (layers.isNotEmpty()) " Layers: $layers." else "") +
            (if (central.isNotEmpty()) " Central: $central." else "")
    }
}
