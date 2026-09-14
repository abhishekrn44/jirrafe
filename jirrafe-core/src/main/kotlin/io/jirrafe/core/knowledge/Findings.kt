package io.jirrafe.core.knowledge

import io.jirrafe.core.manifest.Manifest
import io.jirrafe.core.model.Attrs
import io.jirrafe.core.model.Edge
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Origin
import io.jirrafe.core.model.Resolution
import io.jirrafe.core.store.GraphStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.walk

/** Structural findings, no LLM. Each is attached to its subject and, for cycles, to every member. */
internal object Findings {
    class Finding(
        val subject: String, val kind: String, val key: String, val severity: String, val message: String,
        val extra: Map<String, String> = emptyMap(), val alsoOn: List<String> = emptyList(),
    ) {
        val id get() = "finding:$kind:$subject->$key"
    }

    /** The kinds this layer owns; framework plugins own the rest. */
    val KINDS = setOf("dead-code", "cyclic-packages", "layer-violation", "version-conflict", "untested-god-node", "sarif")

    /** Test class -> classes it exercises, by reference (exact) or by name (`FooTest` -> `Foo`, heuristic). */
    fun testLinks(g: ClassGraph): List<Edge> {
        val edges = LinkedHashMap<Pair<String, String>, Edge>()
        val tests = g.nodes.values.filter { ClassGraph.isCode(it) && ClassGraph.isTest(it) }
        for (t in tests) {
            val members = listOf(t.id) + g.outgoing[t.id].orEmpty().filter { it.kind == EdgeKind.CONTAINS }.map { it.to }
            for (m in members) for (e in g.outgoing[m].orEmpty()) {
                if (e.kind == EdgeKind.CONTAINS) continue
                val target = ClassGraph.owner(e.to)
                if (target != t.id && target in g.index) edges[t.id to target] = Edge(t.id, target, EdgeKind.TESTS, Resolution.EXACT)
            }
            val name = t.id.substringAfterLast('.')
            val subject = SUFFIXES.firstNotNullOfOrNull { s -> if (name.endsWith(s) && name.length > s.length) name.removeSuffix(s) else null }
            if (subject != null) {
                val id = g.packageOf(t).let { if (it.isEmpty()) subject else "$it.$subject" }
                if (id in g.index) edges.putIfAbsent(t.id to id, Edge(t.id, id, EdgeKind.TESTS, Resolution.HEURISTIC))
            }
        }
        return edges.values.toList()
    }

    private val SUFFIXES = listOf("Test", "Tests", "IT", "Spec", "TestCase")

    fun compute(
        g: ClassGraph, layers: Array<String?>, gods: List<Int>, tested: Set<String>, manifest: Manifest?, root: Path, store: GraphStore,
    ): List<Finding> = deadCode(g) + cyclicPackages(g) + layerViolations(g, layers) + versionConflicts(manifest) +
        untestedGods(g, gods, tested) + sarif(manifest, root, store)

    // ---- dead code -----------------------------------------------------------------------------

    private val CONTRACT_METHODS = setOf(
        "toString", "equals", "hashCode", "compareTo", "run", "call", "apply", "accept", "get", "test", "close", "iterator", "main",
    )

    private fun deadCode(g: ClassGraph): List<Finding> {
        val incoming = HashMap<String, MutableSet<EdgeKind>>()
        for (list in g.outgoing.values) for (e in list) if (e.kind != EdgeKind.CONTAINS) incoming.getOrPut(e.to) { HashSet() } += e.kind
        val overrides = g.outgoing.values.flatten().filter { it.kind == EdgeKind.OVERRIDES }
        val overriding = overrides.map { it.from }.toSet()
        val overridden = overrides.map { it.to }.toSet()
        val out = ArrayList<Finding>()
        for (cls in g.classes) {
            if (cls.origin != Origin.REPO || cls.kind == NodeKind.ANNOTATION) continue
            val referenced = incoming[cls.id]?.any { it != EdgeKind.CONTAINS } == true
            val members = g.outgoing[cls.id].orEmpty().filter { it.kind == EdgeKind.CONTAINS }.map { it.to }
            val memberReferenced = members.any { incoming[it]?.any { k -> k == EdgeKind.CALLS || k == EdgeKind.DISPATCHES_TO || k == EdgeKind.READS_FIELD || k == EdgeKind.WRITES_FIELD } == true }
            val annotated = Attrs.annotations(cls).isNotEmpty()
            val hasMain = members.any { it.endsWith("#main(java.lang.String[])") }
            if (!referenced && !memberReferenced && !annotated && !hasMain) {
                out += Finding(cls.id, "dead-code", "class", "info", "${g.shortName(cls.id)} is never referenced")
                continue
            }
            if (cls.kind == NodeKind.INTERFACE) continue
            for (m in members) {
                val node = g.nodes[m] ?: continue
                if (node.kind != NodeKind.METHOD) continue
                val name = m.substringAfter('#').substringBefore('(')
                if (name in CONTRACT_METHODS || name.startsWith("lambda$") || name.startsWith("<")) continue
                if (cls.kind == NodeKind.ENUM && (name == "values" || name == "valueOf")) continue
                if (cls.kind == NodeKind.RECORD && m.endsWith("()")) continue // component accessor
                if (m in overriding || m in overridden || Attrs.annotations(node).isNotEmpty()) continue
                val sig = node.signature.orEmpty()
                if ("abstract" in sig) continue
                if (incoming[m]?.any { it == EdgeKind.CALLS || it == EdgeKind.DISPATCHES_TO } == true) continue
                if (g.outgoing[m].orEmpty().any { it.kind == EdgeKind.HANDLES_ROUTE || it.kind == EdgeKind.CONSUMES_FROM }) continue // entry point
                val visible = "public" in sig || "protected" in sig
                out += Finding(m, "dead-code", "method", if (visible) "info" else "warning", "${g.shortName(m)} has no callers")
            }
        }
        return out
    }

    // ---- cyclic packages -----------------------------------------------------------------------

    private fun cyclicPackages(g: ClassGraph): List<Finding> {
        val packages = g.classes.withIndex().filter { it.value.origin == Origin.REPO }.groupBy({ g.packageOf(it.value) }, { it.index })
        val ids = packages.keys.sorted()
        val index = ids.withIndex().associate { (i, p) -> p to i }
        val adj = Array(ids.size) { HashSet<Int>() }
        for ((pkg, members) in packages) for (i in members) for (j in g.deps[i].keys) {
            val target = g.classes[j].takeIf { it.origin == Origin.REPO }?.let { g.packageOf(it) } ?: continue
            if (target != pkg) index[target]?.let { adj[index.getValue(pkg)] += it }
        }
        return tarjan(adj).filter { it.size > 1 }.map { scc ->
            val names = scc.map { ids[it] }.sorted()
            Finding(
                names.first(), "cyclic-packages", names.joinToString(","), "warning",
                "${names.size} packages depend on each other: ${names.joinToString(" <-> ")}",
                alsoOn = names.drop(1),
            )
        }
    }

    private fun tarjan(adj: Array<HashSet<Int>>): List<List<Int>> {
        val n = adj.size
        val idx = IntArray(n) { -1 }; val low = IntArray(n); val onStack = BooleanArray(n)
        val stack = ArrayDeque<Int>(); val result = ArrayList<List<Int>>(); var counter = 0
        fun strong(v: Int) {
            idx[v] = counter; low[v] = counter; counter++
            stack.addLast(v); onStack[v] = true
            for (w in adj[v]) {
                if (idx[w] < 0) { strong(w); low[v] = minOf(low[v], low[w]) } else if (onStack[w]) low[v] = minOf(low[v], idx[w])
            }
            if (low[v] == idx[v]) {
                val scc = ArrayList<Int>()
                do { val w = stack.removeLast(); onStack[w] = false; scc += w } while (w != v)
                result += scc
            }
        }
        for (v in 0 until n) if (idx[v] < 0) strong(v)
        return result
    }

    // ---- layer violations ----------------------------------------------------------------------

    private fun layerViolations(g: ClassGraph, layers: Array<String?>): List<Finding> {
        val out = ArrayList<Finding>()
        for (i in g.classes.indices) {
            val from = layers[i] ?: continue
            if (g.classes[i].origin != Origin.REPO) continue
            val forbidden = Layers.FORBIDDEN[from] ?: continue
            for (j in g.deps[i].keys) {
                val to = layers[j] ?: continue
                if (to in forbidden) out += Finding(
                    g.classes[i].id, "layer-violation", g.classes[j].id, "warning",
                    "$from ${g.shortName(g.classes[i].id)} depends on $to ${g.shortName(g.classes[j].id)}",
                    extra = mapOf("from" to from, "to" to to),
                )
            }
        }
        return out
    }

    // ---- version conflicts ---------------------------------------------------------------------

    private fun versionConflicts(manifest: Manifest?): List<Finding> {
        val out = LinkedHashMap<String, Finding>()
        for (m in manifest?.modules.orEmpty()) for (c in m.configurations) for (v in c.conflicts) {
            val key = "${v.group}:${v.name}"
            out.putIfAbsent(
                "module:${m.name}->$key",
                Finding(
                    "module:${m.name}", "version-conflict", key, "info",
                    "$key requested ${v.requested}${v.requestedBy?.let { " by $it" } ?: ""}, resolved ${v.selected} (${c.name})",
                    extra = mapOf("requested" to v.requested, "selected" to v.selected),
                ),
            )
        }
        return out.values.toList()
    }

    // ---- untested god nodes --------------------------------------------------------------------

    private fun untestedGods(g: ClassGraph, gods: List<Int>, tested: Set<String>): List<Finding> =
        gods.map { g.classes[it] }.filter { it.origin == Origin.REPO && it.id !in tested }.map {
            Finding(it.id, "untested-god-node", "tests", "warning", "${g.shortName(it.id)} is a god node with no test referencing it")
        }

    // ---- SARIF ---------------------------------------------------------------------------------

    /** Ingests every `*.sarif` under the modules (SpotBugs, Error Prone, CodeQL, Sonar...). */
    private fun sarif(manifest: Manifest?, root: Path, store: GraphStore): List<Finding> {
        val out = ArrayList<Finding>()
        val files = manifest?.modules.orEmpty().map { Path.of(it.dir) }.filter { Files.isDirectory(it) }.flatMap { dir ->
            dir.walk().filter { it.isRegularFile() && it.name.endsWith(".sarif") && ".jirrafe" !in it.toString() && "node_modules" !in it.toString() }.toList()
        }.distinct()
        for (file in files) {
            val runs = runCatching { Json.parseToJsonElement(Files.readString(file)).jsonObject["runs"]?.jsonArray }.getOrNull() ?: continue
            for (run in runs) {
                val tool = run.jsonObject["tool"]?.jsonObject?.get("driver")?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "sarif"
                for (r in run.jsonObject["results"]?.jsonArray.orEmpty()) {
                    val o = r.jsonObject
                    val rule = o["ruleId"]?.jsonPrimitive?.content ?: continue
                    val level = o["level"]?.jsonPrimitive?.content ?: "warning"
                    val text = o["message"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: rule
                    val loc = o["locations"]?.jsonArray?.firstOrNull()?.jsonObject?.get("physicalLocation")?.jsonObject
                    val uri = loc?.get("artifactLocation")?.jsonObject?.get("uri")?.jsonPrimitive?.content
                    val line = loc?.get("region")?.jsonObject?.get("startLine")?.jsonPrimitive?.content?.toIntOrNull()
                    val rel = uri?.removePrefix("file://")?.let { runCatching { root.relativize(Path.of(it).toAbsolutePath().normalize()).toString() }.getOrNull() ?: it }
                        ?.replace('\\', '/')
                    val subject = rel?.let { subjectAt(store, it, line) } ?: "module:${manifest?.modules?.firstOrNull()?.name}"
                    val severity = when (level) { "error" -> "error"; "note" -> "info"; else -> "warning" }
                    out += Finding(
                        subject, "sarif", "$rule@${line ?: 0}", severity, "$tool $rule: $text",
                        extra = mapOf("tool" to tool, "rule" to rule) + (line?.let { mapOf("line" to it.toString()) } ?: emptyMap()),
                    )
                }
            }
        }
        return out
    }

    /** The innermost method, else class, else file node covering this line. */
    private fun subjectAt(store: GraphStore, file: String, line: Int?): String? {
        val nodes = store.nodesInFile(file).ifEmpty { return null }
        if (line == null) return nodes.firstOrNull { it.kind == NodeKind.FILE }?.id ?: nodes.first().id
        val covering = nodes.filter { it.startLine != null && it.startLine <= line && (it.endLine ?: it.startLine) >= line }
        return (covering.firstOrNull { it.kind == NodeKind.METHOD || it.kind == NodeKind.CONSTRUCTOR }
            ?: covering.lastOrNull { ClassGraph.isCode(it) }
            ?: nodes.firstOrNull { it.kind == NodeKind.FILE } ?: nodes.first()).id
    }

    fun toNode(f: Finding, at: Node?): Node = Node(
        f.id, NodeKind.FINDING, f.message, at?.origin ?: Origin.REPO, file = at?.file, startLine = at?.startLine, module = at?.module,
        attrs = mapOf("kind" to f.kind, "severity" to f.severity, "subject" to f.subject, "key" to f.key) + f.extra,
    )
}
