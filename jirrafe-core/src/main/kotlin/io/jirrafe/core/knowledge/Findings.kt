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
    ): List<Finding> = deadCode(g, store) + cyclicPackages(g) + layerViolations(g, layers) + versionConflicts(manifest) +
        untestedGods(g, gods, tested) + sarif(manifest, root, store)

    // ---- dead code -----------------------------------------------------------------------------

    private val CONTRACT_METHODS = setOf(
        "toString", "equals", "hashCode", "compareTo", "run", "call", "apply", "accept", "get", "test", "close", "iterator", "main",
    )

    /** How many types `class X extends A implements B, C<D, E>` names as supertypes: three. */
    private fun namedSupertypes(signature: String): Int {
        var s = signature
        while ('<' in s) s = s.replace(Regex("<[^<>]*>"), "")
        val at = listOf(s.indexOf(" extends "), s.indexOf(" implements ")).filter { it >= 0 }.minOrNull() ?: return 0
        return s.substring(at).replace(" extends ", ",").replace(" implements ", ",").split(',').count { it.isNotBlank() }
    }

    /** Annotations that make a method a framework entry: the container calls it, so indexed code need not. */
    private val ENTRY_ANNOTATIONS = setOf(
        "Scheduled", "Schedules", "PostConstruct", "PreDestroy", "EventListener", "TransactionalEventListener", "ApplicationModuleListener",
        "KafkaListener", "KafkaHandler", "RabbitListener", "RabbitHandler", "JmsListener", "SqsListener", "StreamListener", "ServiceActivator",
        "Bean", "ExceptionHandler", "InitBinder", "ModelAttribute", "MessageMapping", "SubscribeMapping", "MessageExceptionHandler",
        "Before", "After", "Around", "AfterReturning", "AfterThrowing", "Pointcut",
        "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "BeforeEach", "AfterEach", "BeforeAll", "AfterAll", "DynamicPropertySource",
        "JsonCreator", "JsonValue", "JsonAnySetter", "JsonAnyGetter", "PrePersist", "PreUpdate", "PostLoad", "PreRemove", "PostPersist", "PostUpdate", "PostRemove",
        "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "QueryMapping", "MutationMapping", "SchemaMapping",
    )

    /** A class the container instantiates and drives by itself: nothing in indexed code has to name it. */
    private val LIVE_CLASS_ANNOTATIONS = setOf("Configuration", "AutoConfiguration", "SpringBootApplication", "ControllerAdvice", "RestControllerAdvice", "Aspect", "WebFilter", "ServerEndpoint")
    private val STEREOTYPES = setOf("Component", "Service", "Repository", "Controller", "RestController")

    /** An edge that makes its target used: one of these from live code is what "referenced" means below. */
    private val REFERENCE = setOf(
        EdgeKind.USES_TYPE, EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS, EdgeKind.INJECTS, EdgeKind.MAPS_TO_TABLE,
        EdgeKind.CALLS, EdgeKind.DISPATCHES_TO, EdgeKind.READS_FIELD, EdgeKind.WRITES_FIELD,
    )

    /**
     * What is declared and used by nothing: a class no live code references (or only its tests, or only another dead
     * class), a method no live code calls (nor calls through the interface it implements), a constant nothing reads.
     * "Live" leaves out tests and generated code; "indexed" is the caveat for reflection, SpEL and templates, which the
     * graph does not see. Before, any annotation on a class or a method counted as life, a file importing a class
     * counted as a reference, overriding anything was a pass, and fields were not judged: on user-management the
     * uncalled `fetchRoles` passed because it overrides its interface, and on audio-metadata-service the unused
     * `TrackCacheRepository` passed because its test names it. A module indexed from bytecode may lack the edges
     * that reference what is judged here, so a repository with one such module is not judged at all.
     */
    private fun deadCode(g: ClassGraph, store: GraphStore): List<Finding> {
        if (g.nodes.values.any { it.kind == NodeKind.MODULE && it.module?.let { m -> store.meta("health:$m") }?.let { h -> h != "source" } == true }) return emptyList()
        val top = { id: String -> id.substringBefore('#').substringBefore('$') }
        val isTest = { id: String -> g.nodes[top(id)]?.attrs?.get("test") == "true" || g.nodes[id]?.attrs?.get("test") == "true" }
        // Lombok's members carry `lombok.Generated` only when lombok.config asks for it; without it a setter sits on
        // the `@Setter` line above the class keyword and a getter on its field's line (the field's own node starts
        // on its annotation, `@Version` above `private int version`), with no range of its own, which no written
        // method has. So: a method of one line, on a line a field spans or above the first member of the class
        val generatedIds = HashSet<String>()
        for (cls in g.nodes.values) {
            if (!ClassGraph.isCode(cls)) continue
            val members = g.outgoing[cls.id].orEmpty().filter { it.kind == EdgeKind.CONTAINS }.mapNotNull { g.nodes[it.to] }
            val fieldSpans = members.filter { it.kind == NodeKind.FIELD && it.startLine != null }.map { (it.startLine ?: 0)..(it.endLine ?: it.startLine ?: 0) }
            val firstMember = members.mapNotNull { m -> m.startLine?.takeIf { m.kind == NodeKind.FIELD || (m.endLine ?: 0) > it } }.minOrNull() ?: Int.MAX_VALUE
            for (m in members) {
                if (m.kind != NodeKind.METHOD && m.kind != NodeKind.CONSTRUCTOR) continue
                val line = m.startLine ?: continue
                if (line == m.endLine && (line < firstMember || fieldSpans.any { line in it })) generatedIds += m.id
            }
        }
        val generated = { id: String -> id in generatedIds || "lombok.Generated" in (g.nodes[id]?.attrs?.get(Attrs.ANNOTATIONS) ?: "") }
        val beanType = g.nodes.values.filter { it.kind == NodeKind.BEAN }.associate { it.id to it.attrs["type"] }
        val scheduled = g.nodes.values.filter { it.kind == NodeKind.SCHEDULED_JOB }.map { it.fqn }.toSet()
        // who references what: the referring top-level class per class (a class's own members and nested types aside),
        // the callers per method, the readers per field; tests kept apart, generated code left out
        val refs = HashMap<String, MutableSet<String>>(); val testRefs = HashMap<String, MutableSet<String>>()
        val callers = HashMap<String, MutableSet<String>>(); val testCallers = HashMap<String, MutableSet<String>>()
        val readers = HashMap<String, MutableSet<String>>()
        for (list in g.outgoing.values) for (e in list) {
            if (e.kind !in REFERENCE || generated(e.from)) continue
            val target = if (e.kind == EdgeKind.INJECTS) (beanType[e.to] ?: e.to) else e.to
            val test = isTest(e.from)
            if (e.kind == EdgeKind.CALLS || e.kind == EdgeKind.DISPATCHES_TO) (if (test) testCallers else callers).getOrPut(target) { HashSet() } += e.from
            if (e.kind == EdgeKind.READS_FIELD && !test) readers.getOrPut(target) { HashSet() } += e.from
            val t = top(target); val f = top(e.from)
            if (f == t) continue
            (if (test) testRefs else refs).getOrPut(t) { HashSet() } += f
        }
        fun names(id: String) = g.nodes[id]?.let { n -> Attrs.annotations(n).keys.map { it.substringAfterLast('.') } }.orEmpty()
        fun entry(m: String) = m.substringAfter('#').startsWith("main(") || m in scheduled || names(m).any { it in ENTRY_ANNOTATIONS } ||
            g.outgoing[m].orEmpty().any { it.kind == EdgeKind.HANDLES_ROUTE || it.kind == EdgeKind.CONSUMES_FROM || it.kind == EdgeKind.PROVIDES_BEAN }
        fun members(cls: Node) = g.outgoing[cls.id].orEmpty().filter { it.kind == EdgeKind.CONTAINS }.map { it.to }
        // A supertype whose methods the graph cannot see (java.sql.Connection, a framework base class) declares a
        // contract the framework calls: `HibernateConnection#commit()` has no caller in the repo and is not dead.
        // The signature names every supertype; an edge exists only to an indexed one, and a dependency's type
        // is a stub holding only the members the repo happens to call. Either way a public method may be that
        // contract and is not judged, and the class may be instantiated by type and is not judged either.
        fun externalContract(cls: Node): Boolean {
            val supertypes = g.outgoing[cls.id].orEmpty().filter { it.kind == EdgeKind.EXTENDS || it.kind == EdgeKind.IMPLEMENTS }
            return namedSupertypes(cls.signature.orEmpty()) > supertypes.size || supertypes.any { e -> g.nodes[e.to]?.origin == Origin.EXTERNAL }
        }
        // a Spring Data repository extends a framework interface, and the framework consumes nothing by that type:
        // only injection does, so the external supertype spares a class, not a bean interface
        fun providesBean(cls: Node) = g.outgoing[cls.id].orEmpty().any { it.kind == EdgeKind.PROVIDES_BEAN }
        val judged = g.classes.filter { cls ->
            cls.origin == Origin.REPO && cls.kind != NodeKind.ANNOTATION && '$' !in cls.id &&
                names(cls.id).none { it in LIVE_CLASS_ANNOTATIONS || it.startsWith("Enable") } &&
                ((cls.kind == NodeKind.INTERFACE && providesBean(cls)) || !externalContract(cls)) && members(cls).none { entry(it) }
        }
        // dead when nothing references it, or when everything that does is itself dead: TrackCache is used only by
        // TrackCacheRepository, which nothing injects
        val dead = HashMap<String, String?>() // class -> the dead class it hangs from, null when nothing refers to it at all
        var changed = true
        while (changed) {
            changed = false
            for (cls in judged) {
                if (cls.id in dead) continue
                val rs = refs[cls.id].orEmpty()
                if (rs.isEmpty() || rs.all { it in dead }) { dead[cls.id] = rs.firstOrNull(); changed = true }
            }
        }
        val out = ArrayList<Finding>()
        for ((id, via) in dead) {
            val name = g.shortName(id)
            val msg = when {
                via != null -> "$name is referenced only by ${g.shortName(via)}, which nothing references"
                names(id).any { it in STEREOTYPES } || g.nodes[id]?.let { providesBean(it) } == true -> "$name is a bean no indexed class injects"
                testRefs[id].orEmpty().isNotEmpty() -> "$name is referenced by no indexed code outside its tests"
                else -> "$name is never referenced"
            }
            out += Finding(id, "dead-code", "class", "info", msg)
        }
        val overridden = g.outgoing.values.flatten().filter { it.kind == EdgeKind.OVERRIDES }.map { it.to }.toSet()
        for (cls in g.classes) {
            if (cls.origin != Origin.REPO || top(cls.id) in dead || cls.kind == NodeKind.INTERFACE || cls.kind == NodeKind.ANNOTATION) continue
            val contract = externalContract(cls)
            for (m in members(cls)) {
                val node = g.nodes[m] ?: continue
                val sig = node.signature.orEmpty()
                if (node.kind == NodeKind.FIELD) {
                    val name = m.substringAfter('#')
                    val type = sig.substringAfter(": ", "")
                    val logger = type.endsWith("Logger") || type.endsWith(".Log") || name.lowercase() in setOf("log", "logger")
                    if ("static" in sig && "final" in sig && name != "serialVersionUID" && !logger && !generated(m) && readers[m].isNullOrEmpty())
                        out += Finding(m, "dead-code", "field", "info", "${g.shortName(m)} is never read")
                    continue
                }
                if (node.kind != NodeKind.METHOD) continue
                val name = m.substringAfter('#').substringBefore('(')
                if (name in CONTRACT_METHODS || name.startsWith("lambda$") || name.startsWith("<") || generated(m) || entry(m)) continue
                // an accessor of a field the class declares is what Jackson, JPA and Mongo call by reflection
                val property = name.removePrefix("get").removePrefix("set").removePrefix("is").replaceFirstChar { it.lowercase() }
                if (name != property && property.isNotEmpty() && members(cls).any { it == "${cls.id}#$property" }) continue
                if (cls.kind == NodeKind.ENUM && (name == "values" || name == "valueOf")) continue
                if (cls.kind == NodeKind.RECORD && m.endsWith("()")) continue // component accessor
                if (m in overridden || "abstract" in sig || !callers[m].isNullOrEmpty()) continue
                val visible = "public" in sig || "protected" in sig
                if (visible && contract) continue
                // an implementation is dead only when nothing calls through what it implements either, and an override
                // of something outside the graph is the framework's to call
                val overrides = g.outgoing[m].orEmpty().filter { it.kind == EdgeKind.OVERRIDES }.map { it.to }
                if (overrides.any { g.nodes[it]?.origin != Origin.REPO || !callers[it].isNullOrEmpty() }) continue
                val msg = "${g.shortName(m)} has no callers" + (if (testCallers[m].isNullOrEmpty()) "" else " outside its tests") +
                    (overrides.firstOrNull()?.let { ", nor does ${g.shortName(it)} which it implements" } ?: "")
                out += Finding(m, "dead-code", "method", if (visible) "info" else "warning", msg)
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
