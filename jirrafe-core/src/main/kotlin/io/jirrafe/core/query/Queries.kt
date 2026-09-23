package io.jirrafe.core.query

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Every question the MCP server answers, as compact JSON with `file:line` citations. Pure graph
 * reads, deterministic, no LLM. Each answer honours a token budget (about four characters per
 * token) by shrinking its lists until it fits.
 */
class Queries(
    private val store: GraphStore,
    private val root: String = store.meta("root").orEmpty(),
    private val manifest: Manifest? = null,
    private val sources: SourceReader? = null,
    private val memory: Memory? = null,
) {
    companion object {
        val json = Json { encodeDefaults = false }
        const val DEFAULT_BUDGET = 3000 // a ceiling; the measured answers are well under it, and the bodies shrink last
        private val LIMITS = intArrayOf(Int.MAX_VALUE, 40, 20, 15, 10, 7, 5, 3, 2, 1) // 20 -> 10 halved every section and left half the budget unused
        private val STRUCTURE = setOf(EdgeKind.CONTAINS, EdgeKind.MEMBER_OF_COMMUNITY, EdgeKind.STEP_OF_FLOW, EdgeKind.HAS_FINDING, EdgeKind.IMPORTS, EdgeKind.TESTS)
        private val CODE = setOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.ENUM, NodeKind.RECORD, NodeKind.ANNOTATION)
        private const val DISPATCH_FLOOR = 0.2 // 1/n: a call site with more than five implementations names none of them
        private const val CHAIN_STEPS = 4
        private const val PACK_LEADS = 2 // distinct matches whose chains are packed
        private const val PACK_STEPS = 5 // bodies along the first match's chain: entry, the match, and the hops below it
        private const val PACK_STEPS_MORE = 3 // along a further match's chain
        private const val PACK_MAX = 6 // bodies in one answer
        private const val PACK_LINES = 120 // per body; a longer method is the agent's own call to read
        private const val DATA_CLASSES = 4
        private const val DATA_FIELDS = 20
        private const val CONFIG_KEYS = 8
        /**
         * What a framework declares rather than calls, by concept: the annotations that mark it, the bean types
         * that configure it, the artifacts that ship it. A question naming the concept, or a packed body carrying
         * one of its annotations, brings the whole family into the answer; Spring calls these, the code does not,
         * so no call chain reaches them.
         */
        private class Family(val words: List<String>, val annotations: List<String>, val beanTypes: List<String>, val artifacts: List<String>)
        private val FAMILIES = listOf(
            Family(listOf("secur", "auth", "login", "signin", "token", "jwt", "permission", "role", "admin", "password", "credential"),
                listOf("EnableWebSecurity", "EnableMethodSecurity", "EnableGlobalMethodSecurity", "PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed"),
                // in the order they explain the concept: the chain declares the rules, the filter runs on every
                // request, the details service loads the credentials; the manager and the encoder are details
                listOf("SecurityFilterChain", "OncePerRequestFilter", "UserDetailsService", "AuthenticationProvider", "AuthenticationManager", "PasswordEncoder", "AuthenticationEntryPoint"),
                listOf("security", "jjwt", "oauth")),
            Family(listOf("cach", "ehcache", "redis"), listOf("EnableCaching", "Cacheable", "CacheEvict", "CachePut", "Caching"), listOf("CacheManager"), listOf("cache", "ehcache", "jcache", "redis")),
            Family(listOf("error", "exception", "fail"), listOf("ControllerAdvice", "RestControllerAdvice", "ExceptionHandler", "ResponseStatus"), emptyList(), emptyList()),
            Family(listOf("transaction"), listOf("Transactional", "EnableTransactionManagement"), listOf("PlatformTransactionManager"), emptyList()),
            Family(listOf("schedul", "cron", "job", "async"), listOf("Scheduled", "EnableScheduling", "Async", "EnableAsync"), listOf("TaskScheduler"), emptyList()),
            Family(listOf("valid"), listOf("Valid", "Validated"), emptyList(), listOf("validation")),
            Family(listOf("event", "startup", "preload", "listener", "boot"), listOf("EventListener", "PostConstruct"), listOf("CommandLineRunner", "ApplicationRunner", "ApplicationListener"), emptyList()),
            Family(listOf("cors", "intercept", "mvc"), listOf("CrossOrigin"), listOf("WebMvcConfigurer", "HandlerInterceptor"), emptyList()),
        )
        private const val WIRING_SITES = 12
        private const val WIRING_BODIES = 3 // the framework bodies that answer a question the call chain cannot
        /** What a framework calls on a registered component instead of the code calling it. */
        private val FILTER_METHODS = setOf("doFilterInternal", "doFilter", "preHandle", "loadUserByUsername", "parse", "print", "convert")
        private const val FIELDS_MAX = 12
        private const val WHOLE_FILE_TOKENS = 700L // a file this size costs less whole than the same facts as fragments
        private const val CARD_MEMBERS = 30
        /** A question about a workflow: its second step is not called by its first, it reads what the first wrote. */
        private val WORKFLOW = Regex("\\b(workflow|steps?|process|lifecycle|end[ -]to[ -]end)\\b")
        private const val NEAR_MISS = 0.5 // a second match scoring this fraction of the first gets its own chain; new tokens cost twelve times re-read ones
        /** Where a request goes next, by the knowledge layer's classification of the target's class. */
        private val LAYER_RANK = mapOf("controller" to 0, "service" to 0, "repository" to 0, "client" to 0, "util" to 2, "config" to 2, "model" to 3)
        /** What a lead is worth by its class's layer: a helper or an entity is rarely the answer, an unlabelled class often is. */
        private val LAYER_WEIGHT = mapOf("util" to 0.7, "config" to 0.7, "model" to 0.4)
        /** A usage question, which prose may answer: "how do I", "why", "what is", "example", "documentation". */
        private val NON_DOC = NodeKind.values().toSet() - NodeKind.DOC
        private val USAGE = Regex("""\b(how (do|can|should|would) (i|we|you)|why|what is|what are|where (do|can) (i|we)|example|guide|documentation|readme|tutorial)\b""", RegexOption.IGNORE_CASE)

        fun tokens(element: JsonElement): Int = json.encodeToString(JsonElement.serializer(), element).length / 4
    }

    /** Builds with shrinking list limits until the answer fits [budget] tokens. */
    private fun fit(budget: Int, build: (limit: Int) -> JsonObject): JsonObject {
        var last: JsonObject? = null
        for (limit in LIMITS) {
            val o = build(limit)
            if (System.getenv("JIRRAFE_DEBUG") == "1") System.err.println("debug: fit level=$limit tokens=${tokens(o)} budget=$budget")
            if (tokens(o) <= budget) return o
            last = o
        }
        return last!!
    }

    /** A negative limit is an overflowed "unlimited". */
    private fun <T> List<T>.cap(limit: Int) = if (limit < 0 || size <= limit) this else take(limit)

    // ---- refs ----------------------------------------------------------------------------------

    private val rootNorm = root.replace('\\', '/').trimEnd('/')

    /** Repo-relative, forward slashes; the prefix match ignores slash direction and case so an alias of the root still strips. */
    private fun relative(file: String): String {
        val f = file.replace('\\', '/')
        return if (rootNorm.isNotEmpty() && f.startsWith(rootNorm, ignoreCase = true)) f.substring(rootNorm.length).trimStart('/') else f
    }

    private fun at(n: Node): String? = n.file?.let { f ->
        val rel = relative(f)
        n.startLine?.let { "$rel:$it" } ?: rel
    }

    private fun ref(n: Node): JsonObject = buildJsonObject {
        put("id", n.id); put("kind", n.kind.name.lowercase())
        at(n)?.let { put("at", it) }
        n.attrs["layer"]?.let { put("layer", it) }
        if (n.origin != Origin.REPO) put("origin", n.origin.name.lowercase())
    }

    private fun ref(id: String): JsonObject = store.node(id)?.let { ref(it) } ?: buildJsonObject { put("id", id) }

    private val PACKAGE = Regex("""\b[a-z][a-z0-9_]*\.""")

    /** Member name with simple-name parameters, as the outline prints it: `save(Owner)` for `save(org.x.Owner)`. */
    private fun shortName(m: Node) = m.id.substringAfter('#').replace(PACKAGE, "")

    /** What an id is called: the member, or the class when it has none. Parameter types carry dots of their own,
     *  so the name is taken before the parameter list, never from the end of the string. */
    private fun simpleName(id: String): String {
        val head = id.substringBefore('(')
        return (if ('#' in head) head.substringAfterLast('#') else head.substringAfterLast('.')).substringAfterLast('$')
    }

    /** The node with this exact id, or the member an outline signature denotes (`a.Foo#save(Owner)`); the first overload wins a tie. */
    private fun resolve(id: String): Node? = resolveAll(id).firstOrNull()

    /** Every member an id denotes: `a.Foo#save` is all overloads of save, `a.Foo#save(Owner)` one of them. */
    private fun resolveAll(id: String): List<Node> = store.node(id)?.let { listOf(it) } ?: if ('#' in id) {
        val want = id.substringAfter('#').replace(" ", "")
        store.edgesFrom(owner(id), EdgeKind.CONTAINS).mapNotNull { store.node(it.to) }
            .filter { m -> shortName(m) == want || ('(' !in want && shortName(m).substringBefore('(') == want) }
    } else emptyList()

    /** A bare member name that matches several overloads is not a guess to make on the agent's behalf. */
    private fun ambiguous(id: String, all: List<Node>): JsonObject? = if (all.size > 1 && '(' !in id) buildJsonObject {
        put("error", "'$id' matches ${all.size} members; pick one")
        put("candidates", buildJsonArray { for (n in all) add(buildJsonObject { put("id", n.id); at(n)?.let { put("at", it) }; n.signature?.let { put("signature", it) } }) })
    } else null

    /** `stale` for one node: the graph predates edits to its file, so its lines may be off. */
    private fun staleFor(n: Node): JsonObject? {
        val rel = n.file?.let { relative(it) } ?: return null
        val built = store.meta("commit") ?: return null
        if (io.jirrafe.core.store.Git.changedSources(java.nio.file.Path.of(root), built).none { it.replace('\\', '/') == rel }) return null
        return buildJsonObject { put("builtAt", built.take(12)); put("file", rel); put("note", "the graph predates edits to this file; cited lines may be off; read the current file before quoting, or run `jirrafe index`") }
    }

    /** Outline entry of a class member: the id is the class id + '#' + the signature's `name(params)`, so neither is repeated. */
    private fun memberRef(m: Node): JsonObject = buildJsonObject {
        m.startLine?.let { put("line", it) }
        val sig = m.signature
        if (sig != null) put("signature", sig.replace(PACKAGE, "")) else put("name", shortName(m)) // simple type names: the outline is for reading, ids are for calling; stubs have no signature
        Attrs.annotations(m).keys.filter { it != "java.lang.Override" }.takeIf { it.isNotEmpty() }?.let { a -> put("annotations", buildJsonArray { for (k in a) add(JsonPrimitive(k.substringAfterLast('.'))) }) }
        if (m.attrs["inherited"] == "true") put("inherited", true)
    }

    private fun edgeRef(e: Edge, other: String): JsonObject = buildJsonObject {
        put("id", other); put("kind", e.kind.name.lowercase())
        if (e.resolution != Resolution.EXACT) put("resolution", e.resolution.name.lowercase())
        if (e.confidence < 1.0) put("confidence", "%.2f".format(e.confidence))
    }

    private fun error(message: String) = buildJsonObject { put("error", message) }

    /** Present when the repository moved on since the build: the agent then knows which cited lines may be off. */
    private fun stale(): JsonObject? {
        val built = store.meta("commit") ?: return null
        val dir = java.nio.file.Path.of(root)
        val head = io.jirrafe.core.store.Git.head(dir) ?: return null
        val changed = io.jirrafe.core.store.Git.changedSources(dir, built)
        if (head == built && changed.isEmpty()) return null
        return buildJsonObject {
            put("builtAt", built.take(12)); put("head", head.take(12)); put("changedSources", changed.size)
            put("files", buildJsonArray { for (f in changed.take(5)) add(JsonPrimitive(f)) })
            put("note", "the graph predates edits to these files; their cited lines may be off; rebuild with `jirrafe build`")
        }
    }

    private fun owner(id: String) = id.substringBefore('#')

    // ---- overview ------------------------------------------------------------------------------

    fun overview(budget: Int = DEFAULT_BUDGET): JsonObject = fit(budget) { limit ->
        val modules = store.nodes(NodeKind.MODULE)
        val jars = store.nodes(NodeKind.ARTIFACT).filter { it.origin != Origin.EXTERNAL }
        val communities = store.nodes(NodeKind.COMMUNITY).filter { it.attrs["level"] == "0" }.sortedByDescending { it.attrs["size"]?.toInt() ?: 0 }
        val flows = store.nodes(NodeKind.FLOW).sortedByDescending { it.attrs["stepCount"]?.toInt() ?: 0 }
        val findings = store.nodes(NodeKind.FINDING)
        buildJsonObject {
            put("root", root); store.meta("buildTool")?.let { put("buildTool", it) }
            stale()?.let { put("stale", it) }
            put("nodes", store.count("nodes")); put("edges", store.count("edges"))
            put("modules", buildJsonArray { for (m in modules.cap(limit)) add(buildJsonObject { put("id", m.id); put("coordinates", "${m.attrs["group"]}:${m.attrs["version"]}") }) })
            put("internalJars", buildJsonArray { for (a in jars.cap(limit)) add(buildJsonObject { put("id", a.id); put("source", a.origin.name.lowercase()) }) })
            put("communities", buildJsonArray {
                for (c in communities.cap(minOf(limit, 12))) add(buildJsonObject {
                    put("id", c.id); put("label", c.attrs["llmLabel"] ?: c.fqn); put("size", c.attrs["size"]?.toInt() ?: 0)
                    c.attrs["gods"]?.takeIf { it.isNotEmpty() }?.let { put("central", it) }
                })
            })
            put("flows", buildJsonArray { for (f in flows.cap(minOf(limit, 12))) add(buildJsonObject { put("id", f.id); put("entry", f.fqn); put("steps", f.attrs["stepCount"]?.toInt() ?: 0) }) })
            put("layers", store.meta("knowledge.levels")?.let { layerCounts() } ?: JsonObject(emptyMap()))
            put("findings", buildJsonObject { for ((sev, list) in findings.groupBy { it.attrs["severity"] ?: "info" }) put(sev, list.size) })
            store.meta("knowledge.gods")?.takeIf { it.isNotEmpty() }?.let { put("godNodes", it) }
            put("hint", "Call explain(question) first; it returns the flows, communities and nodes relevant to a question with citations.")
        }
    }

    private fun layerCounts(): JsonObject {
        val counts = HashMap<String, Int>()
        for (kind in CODE) for (n in store.nodes(kind)) n.attrs["layer"]?.let { counts.merge(it, 1, Int::plus) }
        return buildJsonObject { for ((k, v) in counts.entries.sortedByDescending { it.value }) put(k, v) }
    }

    // ---- search and nodes ----------------------------------------------------------------------

    fun search(query: String, kinds: Set<NodeKind>? = null, limit: Int = 20, budget: Int = DEFAULT_BUDGET): JsonObject {
        // bm25 ranks by length, so sixty dead-code findings on a class outrank the class and push it off the list,
        // and `IntegersTest` precedes `Integers`. A finding is excluded in the query unless the query asks for one;
        // the thing named comes first, then its members, a test last; the compiler's `this`/`class` fields are
        // nobody's answer
        val word = query.trim().substringAfterLast('.').substringAfterLast('#').lowercase()
        val asksFindings = kinds?.contains(NodeKind.FINDING) == true || word.startsWith("finding") || word.startsWith("dead")
        val asksTests = kinds?.contains(NodeKind.TEST) == true || word.startsWith("test")
        val within = kinds ?: if (asksFindings) null else NodeKind.values().toSet() - NodeKind.FINDING
        val hits = LinkedHashMap<String, Node>()
        if (kinds == null) for (n in store.search(query, 10, CODE)) hits[n.id] = n // the class named, before its own sixty members push it off the list
        for (n in store.search(query, limit * 3, within)) hits.putIfAbsent(n.id, n)
        if (hits.size < limit) for (n in store.nodesLike(query.trim(), limit * 3 - hits.size, within)) hits.putIfAbsent(n.id, n)
        val ranked = hits.values.filter { n -> !n.id.endsWith("#this") && !n.id.endsWith("#class") && !n.id.contains("#this$") }
            .sortedWith(compareBy<Node> { n -> if (!asksTests && n.attrs["test"] == "true") 1 else 0 }
                .thenBy { n -> if (simpleName(n.id).lowercase() == word) 0 else 1 }
                .thenBy { n -> when (n.kind) { in CODE -> 0; NodeKind.METHOD, NodeKind.CONSTRUCTOR -> 1; NodeKind.FIELD -> 3; NodeKind.FINDING -> 4; else -> 2 } }
                .thenBy { n -> n.id.length })
            .take(limit)
        return fit(budget) { l ->
            buildJsonObject {
                put("query", query)
                put("results", buildJsonArray { for (n in ranked.cap(l)) add(buildJsonObject { ref(n).forEach { (k, v) -> put(k, v) }; n.signature?.let { put("signature", it) } }) })
            }
        }
    }

    fun getNode(id: String, includeSource: Boolean = false, budget: Int = DEFAULT_BUDGET): JsonObject {
        val all = resolveAll(id)
        ambiguous(id, all)?.let { return it }
        val n = all.firstOrNull() ?: return error("no node '$id'; try search")
        val id = n.id
        memory?.log("node", id)
        val out = store.edgesFrom(id).filter { it.kind !in STRUCTURE }
        val inc = store.edgesTo(id).filter { it.kind !in STRUCTURE }
        val members = store.edgesFrom(id, EdgeKind.CONTAINS).map { it.to }.sortedBy { store.node(it)?.startLine ?: Int.MAX_VALUE }
        val flows = store.edgesFrom(id, EdgeKind.STEP_OF_FLOW).map { it.to }
        val findings = store.edgesFrom(id, EdgeKind.HAS_FINDING).mapNotNull { store.node(it.to) }
        val source = if (includeSource) sources?.read(n, 0) else null
        return fit(budget) { l ->
            buildJsonObject {
                ref(n).forEach { (k, v) -> put(k, v) }
                put("fqn", n.fqn)
                n.signature?.let { put("signature", it) }
                n.doc?.let { put("doc", it.take(if (l > 10) 600 else 200)) }
                n.module?.let { put("module", it) }; n.artifact?.let { put("artifact", it) }
                n.attrs["community"]?.let { put("community", it) }
                n.attrs["god"]?.let { put("god", it) }
                for (key in listOf("verb", "path", "value", "defined", "type", "summary", "severity", "kind", "size", "label", "stepCount", "entry")) n.attrs[key]?.let { put(key, it) }
                Attrs.annotations(n).takeIf { it.isNotEmpty() }?.let { a -> put("annotations", buildJsonArray { for (k in a.keys) add(JsonPrimitive(k)) }) }
                if (members.isNotEmpty()) put("members", buildJsonArray { for (m in members.cap(l * 2)) add(store.node(m)?.let { memberRef(it) } ?: JsonPrimitive(m)) })
                put("outgoing", buildJsonArray { for (e in out.cap(l * 2)) add(edgeRef(e, e.to)) })
                put("incoming", buildJsonArray { for (e in inc.cap(l * 2)) add(edgeRef(e, e.from)) })
                if (out.size > l * 2 || inc.size > l * 2) put("edgeCounts", buildJsonObject { put("outgoing", out.size); put("incoming", inc.size) })
                if (flows.isNotEmpty()) put("flows", buildJsonArray { for (f in flows.cap(l)) add(JsonPrimitive(f)) })
                if (findings.isNotEmpty()) put("findings", buildJsonArray { for (f in findings.cap(l)) add(buildJsonObject { put("id", f.id); put("message", f.fqn) }) })
                source?.let { put("source", sourceJson(it)) }
                staleFor(n)?.let { put("stale", it) }
            }
        }
    }

    private fun sourceJson(s: SourceReader.Source) = buildJsonObject {
        put("file", relative(s.file)); put("startLine", s.startLine); put("endLine", s.startLine + s.text.lines().size - 1)
        if (s.decompiled) put("decompiled", true)
        put("text", s.text)
    }

    /** The source of one node; [lines] (file line numbers) continues a body that was cut, without re-reading its start. */
    fun readSource(id: String, contextLines: Int = 0, lines: IntRange? = null): JsonObject {
        val all = resolveAll(id)
        ambiguous(id, all)?.let { return it }
        val n = all.firstOrNull() ?: return error("no node '$id'")
        val id = n.id
        memory?.log("source", id)
        val reader = sources ?: return error("source reading is not available in this server")
        val whole = reader.read(n, contextLines) ?: return error("no readable source for '$id' (${n.origin.name.lowercase()}); decompiling is limited to internal jars")
        val s = lines?.let { r ->
            val ls = whole.text.lines()
            val from = (r.first - whole.startLine).coerceIn(0, ls.size)
            val to = (r.last - whole.startLine + 1).coerceIn(from, ls.size)
            SourceReader.Source(whole.file, whole.startLine + from, ls.subList(from, to).joinToString("\n"), whole.decompiled)
        } ?: whole
        return buildJsonObject { put("id", id); sourceJson(s).forEach { (k, v) -> put(k, v) }; staleFor(n)?.let { put("stale", it) } }
    }

    /**
     * Several bodies in one call, within one budget, so the follow-up to an answer is one turn and not one per id.
     * What did not fit is listed as `pending`, whole ids, so the next call is exact; an error or a candidates list
     * is small and always returned.
     */
    fun readSources(ids: List<String>, budget: Int = DEFAULT_BUDGET): JsonObject {
        var left = budget * 4
        val done = ArrayList<JsonObject>()
        val pending = ArrayList<String>()
        for (id in ids) {
            val r = readSource(id)
            val text = r["text"]?.jsonPrimitive?.content
            when {
                text == null -> done += r
                text.length <= left -> { done += r; left -= text.length }
                left > 400 -> { done += JsonObject(r + mapOf("text" to JsonPrimitive(text.take(left).substringBeforeLast('\n')), "truncated" to JsonPrimitive(true))); left = 0 }
                else -> pending += id
            }
        }
        return buildJsonObject { put("sources", JsonArray(done)); if (pending.isNotEmpty()) put("pending", buildJsonArray { for (p in pending) add(JsonPrimitive(p)) }) }
    }

    // ---- graph walks ---------------------------------------------------------------------------

    fun neighbors(id: String, direction: String = "both", kinds: Set<EdgeKind>? = null, depth: Int = 1, minConfidence: Double = 0.0, budget: Int = DEFAULT_BUDGET): JsonObject {
        store.node(id) ?: return error("no node '$id'")
        val seen = linkedMapOf(id to 0)
        var frontier = listOf(id)
        val edges = ArrayList<Edge>()
        for (d in 1..depth.coerceIn(1, 4)) {
            val next = ArrayList<String>()
            for (from in frontier) {
                val step = ArrayList<Edge>()
                if (direction != "in") step += store.edgesFrom(from)
                if (direction != "out") step += store.edgesTo(from)
                for (e in step) {
                    if (e.kind in STRUCTURE && e.kind != EdgeKind.CONTAINS) continue
                    if (kinds != null && e.kind !in kinds) continue
                    if (e.confidence < minConfidence) continue
                    edges += e
                    val other = if (e.from == from) e.to else e.from
                    if (seen.putIfAbsent(other, d) == null) next += other
                }
            }
            frontier = next
            if (edges.size > 2000) break
        }
        return fit(budget) { l ->
            buildJsonObject {
                put("id", id); put("depth", depth)
                put("nodes", buildJsonArray { for ((n, d) in seen.entries.drop(1).cap(l * 3)) add(buildJsonObject { ref(n).forEach { (k, v) -> put(k, v) }; put("distance", d) }) })
                put("edges", buildJsonArray {
                    for (e in edges.distinct().cap(l * 4)) add(buildJsonObject { put("from", e.from); put("to", e.to); put("kind", e.kind.name.lowercase()); if (e.resolution != Resolution.EXACT) put("resolution", e.resolution.name.lowercase()) })
                })
                if (seen.size - 1 > l * 3) put("nodeCount", seen.size - 1)
            }
        }
    }

    /** Shortest dependency path from one node to another over forward edges, members reachable through their class. */
    fun path(from: String, to: String, maxDepth: Int = 6): JsonObject {
        store.node(from) ?: return error("no node '$from'"); store.node(to) ?: return error("no node '$to'")
        val prev = HashMap<String, Pair<String, Edge>>()
        val queue = ArrayDeque<Pair<String, Int>>().also { it += from to 0 }
        val visited = hashSetOf(from)
        var found = false
        while (queue.isNotEmpty() && !found && visited.size < 50_000) {
            val (cur, d) = queue.removeFirst()
            if (d >= maxDepth) continue
            val steps = store.edgesFrom(cur).filter { it.kind !in STRUCTURE || it.kind == EdgeKind.CONTAINS } +
                store.edgesTo(cur, EdgeKind.CONTAINS) // up to the owning class
            for (e in steps) {
                val next = if (e.from == cur) e.to else e.from
                if (!visited.add(next)) continue
                prev[next] = cur to e
                if (next == to) { found = true; break }
                queue += next to d + 1
            }
        }
        if (!found) return buildJsonObject { put("from", from); put("to", to); put("found", false) }
        val chain = ArrayList<JsonObject>()
        var cur = to
        while (cur != from) {
            val (p, e) = prev.getValue(cur)
            chain += buildJsonObject { put("from", p); put("to", cur); put("kind", e.kind.name.lowercase()); if (e.resolution != Resolution.EXACT) put("resolution", e.resolution.name.lowercase()) }
            cur = p
        }
        return buildJsonObject { put("from", from); put("to", to); put("found", true); put("length", chain.size); put("path", JsonArray(chain.reversed())) }
    }

    /** Transitive callers, dispatch-aware, grouped by community, flow and module, with the tests to run. */
    fun impact(id: String, depth: Int = 3, budget: Int = DEFAULT_BUDGET): JsonObject {
        val n = resolve(id) ?: return error("no node '$id'")
        val seeds = if (n.kind in CODE) listOf(n.id) + store.edgesFrom(n.id, EdgeKind.CONTAINS).map { it.to } else listOf(n.id)
        return impactOf(seeds, depth, budget) { put("id", n.id) }
    }

    /** `impact` of the working tree: the members the changed lines fall in, what they affect, and one command for the covering tests. */
    fun impactOfChanges(depth: Int = 3, budget: Int = DEFAULT_BUDGET): JsonObject {
        val ranges = io.jirrafe.core.store.Git.changedRanges(java.nio.file.Path.of(root))
        if (ranges.isEmpty()) return error("no Java or Kotlin source differs from HEAD")
        return impactOfChanges(ranges, depth, budget)
    }

    fun impactOfChanges(ranges: Map<String, List<IntRange>>, depth: Int = 3, budget: Int = DEFAULT_BUDGET): JsonObject {
        val changed = LinkedHashMap<String, Node>()
        for ((rel, rs) in ranges) {
            val inFile = store.nodesInFileEndingWith(rel.replace('\\', '/'))
            val members = inFile.filter { m -> m.kind !in CODE && m.kind != NodeKind.FILE && m.startLine != null && rs.any { r -> r.first <= (m.endLine ?: m.startLine!!) && m.startLine!! <= r.last } }
            for (m in members.ifEmpty { inFile.filter { it.kind in CODE } }) changed[m.id] = m // a change outside every member (imports, a field) is the class's
        }
        if (changed.isEmpty()) return error("the changed lines fall in no indexed source; run `jirrafe build`")
        return impactOf(changed.keys.toList(), depth, budget) { put("changed", buildJsonArray { for (n in changed.values) add(ref(n)) }) }
    }

    /** One command that runs [tests] with the project's own wrapper, module-qualified where the build has modules. */
    private fun testCommand(tests: Collection<String>): String? {
        if (tests.isEmpty()) return null
        val windows = System.getProperty("os.name").startsWith("Windows")
        val byModule = tests.groupBy { store.node(it)?.module ?: "" }
        return when (store.meta("buildTool")) {
            "gradle" -> (if (windows) "gradlew.bat" else "./gradlew") + byModule.entries.joinToString("") { (m, ts) ->
                " " + (if (m.isEmpty() || m == ":") "test" else "$m:test") + ts.joinToString("") { " --tests $it" }
            }
            "maven" -> (if (windows) "mvnw.cmd" else "./mvnw") + " -q test" +
                (if (store.nodes(NodeKind.MODULE).size > 1) " -pl ${byModule.keys.filter { it.isNotEmpty() }.joinToString(",")} -Dsurefire.failIfNoSpecifiedTests=false" else "") +
                " -Dtest=" + tests.joinToString(",")
            else -> null
        }
    }

    private fun impactOf(seeds: List<String>, depth: Int, budget: Int, header: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject {
        val affected = LinkedHashMap<String, Int>()
        var frontier = seeds
        seeds.forEach { affected[it] = 0 }
        for (d in 1..depth.coerceIn(1, 6)) {
            val next = ArrayList<String>()
            for (target in frontier) {
                val callers = store.edgesTo(target).filter { it.kind == EdgeKind.CALLS || it.kind == EdgeKind.DISPATCHES_TO || it.kind == EdgeKind.INJECTS || it.kind == EdgeKind.USES_TYPE || it.kind == EdgeKind.READS_FIELD || it.kind == EdgeKind.WRITES_FIELD || it.kind == EdgeKind.EXTENDS || it.kind == EdgeKind.IMPLEMENTS || it.kind == EdgeKind.OVERRIDES }
                for (e in callers) if (affected.putIfAbsent(e.from, d) == null) next += e.from
            }
            frontier = next
            if (affected.size > 5000) break
        }
        val callers = affected.filterKeys { it !in seeds }
        val classes = callers.keys.map { owner(it) }.toSet() + seeds.map { owner(it) }
        val tests = LinkedHashSet<String>()
        for (c in classes) for (e in store.edgesTo(c, EdgeKind.TESTS)) tests += e.from
        for (c in callers.keys.map { owner(it) }) if (store.node(c)?.attrs?.get("test") == "true") tests += c
        val flows = LinkedHashMap<String, Int>()
        for (m in callers.keys + seeds) for (e in store.edgesFrom(m, EdgeKind.STEP_OF_FLOW)) flows.merge(e.to, 1, Int::plus)
        val byCommunity = callers.keys.groupBy { store.node(owner(it))?.attrs?.get("community") ?: "none" }
        val byModule = callers.keys.groupBy { store.node(it)?.module ?: store.node(it)?.artifact ?: "external" }
        return fit(budget) { l ->
            buildJsonObject {
                header(); put("depth", depth); put("affected", callers.size)
                put("callers", buildJsonArray { for ((c, d) in callers.entries.sortedBy { it.value }.cap(l * 3)) add(buildJsonObject { ref(c).forEach { (k, v) -> put(k, v) }; put("distance", d) }) })
                put("byCommunity", buildJsonObject { for ((c, list) in byCommunity.entries.sortedByDescending { it.value.size }.cap(l)) put(c, list.size) })
                put("byModule", buildJsonObject { for ((m, list) in byModule.entries.sortedByDescending { it.value.size }.cap(l)) put(m, list.size) })
                put("flows", buildJsonArray { for ((f, c) in flows.entries.sortedByDescending { it.value }.cap(l)) add(buildJsonObject { put("id", f); store.node(f)?.let { put("entry", it.fqn) }; put("affectedSteps", c) }) })
                put("testsToRun", buildJsonArray { for (t in tests.toList().cap(l * 2)) add(JsonPrimitive(t)) })
                testCommand(tests.toList().cap(l * 2))?.let { put("testCommand", it) }
            }
        }
    }

    // ---- knowledge nodes -----------------------------------------------------------------------

    fun community(id: String, budget: Int = DEFAULT_BUDGET): JsonObject {
        val c = store.node(id) ?: return error("no community '$id'; call communities()")
        val members = store.edgesTo(id, EdgeKind.MEMBER_OF_COMMUNITY).mapNotNull { store.node(it.from) }
        val children = store.edgesFrom(id, EdgeKind.CONTAINS).map { it.to }
        return fit(budget) { l ->
            buildJsonObject {
                put("id", id); put("label", c.fqn); put("level", c.attrs["level"]?.toInt() ?: 0); put("size", c.attrs["size"]?.toInt() ?: members.size)
                (c.attrs["llmSummary"] ?: c.attrs["summary"])?.let { put("summary", it) }
                c.attrs["layers"]?.takeIf { it.isNotEmpty() }?.let { put("layers", it) }
                c.attrs["gods"]?.takeIf { it.isNotEmpty() }?.let { put("central", it) }
                c.attrs["parent"]?.let { put("parent", it) }
                if (children.isNotEmpty()) put("children", buildJsonArray { for (ch in children.cap(l * 2)) add(JsonPrimitive(ch)) })
                put("members", buildJsonArray { for (m in members.sortedByDescending { it.attrs["inDegree"]?.toInt() ?: 0 }.cap(l * 3)) add(ref(m)) })
            }
        }
    }

    fun communities(query: String? = null, budget: Int = DEFAULT_BUDGET): JsonObject {
        val q = query?.lowercase()?.trim().orEmpty()
        val all = store.nodes(NodeKind.COMMUNITY).filter { q.isEmpty() || q in it.fqn.lowercase() || q in it.attrs["packages"].orEmpty().lowercase() || q in it.attrs["summary"].orEmpty().lowercase() }
            .sortedWith(compareByDescending<Node> { it.attrs["level"]?.toInt() ?: 0 }.thenByDescending { it.attrs["size"]?.toInt() ?: 0 })
        return fit(budget) { l ->
            buildJsonObject {
                put("count", all.size)
                put("communities", buildJsonArray {
                    for (c in all.cap(l * 2)) add(buildJsonObject {
                        put("id", c.id); put("label", c.attrs["llmLabel"] ?: c.fqn); put("level", c.attrs["level"]?.toInt() ?: 0); put("size", c.attrs["size"]?.toInt() ?: 0)
                        (c.attrs["llmSummary"] ?: c.attrs["summary"])?.let { put("summary", it) }
                    })
                })
            }
        }
    }

    /** By flow id, entry method id, route (`GET /orders` or `/orders`), or topic name. */
    fun flow(key: String, budget: Int = DEFAULT_BUDGET): JsonObject {
        val flows = store.nodes(NodeKind.FLOW)
        val k = key.trim()
        val f = flows.firstOrNull { it.id == k || it.attrs["entry"] == k || it.id == "flow:$k" }
            ?: flows.firstOrNull { it.fqn.equals(k, ignoreCase = true) }
            ?: flows.firstOrNull { it.fqn.substringAfter(' ').equals(k, ignoreCase = true) || it.fqn.endsWith(" $k", ignoreCase = true) }
            ?: flows.firstOrNull { k.lowercase() in it.fqn.lowercase() }
            ?: return error("no flow for '$key'; entries: " + flows.take(20).joinToString { it.fqn })
        val steps = f.attrs["steps"]?.let { json.parseToJsonElement(it).jsonArray } ?: JsonArray(emptyList())
        return fit(budget) { l ->
            buildJsonObject {
                put("id", f.id); put("entry", f.fqn); put("entryKind", f.attrs["entryKind"] ?: ""); put("entryNode", f.attrs["entry"] ?: "")
                (f.attrs["llmSummary"] ?: f.attrs["summary"])?.let { put("summary", it) }
                put("stepCount", steps.size)
                put("steps", buildJsonArray {
                    for (s in steps.toList().cap(l * 4)) {
                        val o = s.jsonObject
                        val id = o["id"]!!.jsonPrimitive.content
                        add(buildJsonObject { put("id", id); put("depth", o["depth"]!!.jsonPrimitive.content.toInt()); o["via"]?.jsonPrimitive?.content?.takeIf { it != "EXACT" }?.let { put("via", it.lowercase()) }; store.node(id)?.let { n -> at(n)?.let { put("at", it) } } })
                    }
                })
                for (key in listOf("artifacts", "modules", "communities", "external")) f.attrs[key]?.takeIf { it.isNotEmpty() }?.let { put(key, it) }
            }
        }
    }

    fun routes(prefix: String? = null, budget: Int = DEFAULT_BUDGET): JsonObject = listing(NodeKind.HTTP_ROUTE, budget, "routes",
        filter = { it.attrs["remote"] != "true" && (prefix.isNullOrEmpty() || it.attrs["path"].orEmpty().startsWith(prefix) || it.fqn.contains(prefix)) }) { r ->
        buildJsonObject {
            put("id", r.id); put("verb", r.attrs["verb"] ?: ""); put("path", r.attrs["path"] ?: "")
            r.attrs["handler"]?.let { put("handler", it) }
            r.attrs["consumes"]?.let { put("consumes", it) }; r.attrs["produces"]?.let { put("produces", it) }
            store.node(r.attrs["handler"].orEmpty())?.let { h -> at(h)?.let { put("at", it) } }
            store.node("flow:${r.attrs["handler"]}")?.let { put("flow", it.id) }
        }
    }

    fun topics(budget: Int = DEFAULT_BUDGET): JsonObject = listing(NodeKind.MESSAGE_TOPIC, budget, "topics") { t ->
        buildJsonObject {
            put("id", t.id); put("name", t.fqn); put("system", t.attrs["system"] ?: "")
            put("consumers", buildJsonArray { for (e in store.edgesTo(t.id, EdgeKind.CONSUMES_FROM)) add(JsonPrimitive(e.from)) })
            put("producers", buildJsonArray { for (e in store.edgesTo(t.id, EdgeKind.PRODUCES_TO)) add(JsonPrimitive(e.from)) })
        }
    }

    fun config(prefix: String? = null, budget: Int = DEFAULT_BUDGET): JsonObject = listing(NodeKind.CONFIG_KEY, budget, "keys",
        filter = { prefix.isNullOrEmpty() || it.fqn.startsWith(prefix) }) { c ->
        buildJsonObject {
            put("key", c.fqn); put("defined", c.attrs["defined"] == "true")
            c.attrs["value"]?.let { put("value", it) }; c.attrs["profile"]?.let { put("profile", it) }
            at(c)?.let { put("at", it) }
            put("boundBy", buildJsonArray { for (e in store.edgesTo(c.id, EdgeKind.BINDS_CONFIG)) add(JsonPrimitive(e.from)) })
        }
    }

    fun beans(type: String? = null, budget: Int = DEFAULT_BUDGET): JsonObject = listing(NodeKind.BEAN, budget, "beans",
        filter = { type.isNullOrEmpty() || it.attrs["type"].orEmpty().contains(type) || it.fqn.contains(type) }) { b ->
        buildJsonObject {
            put("id", b.id); put("name", b.fqn); put("type", b.attrs["type"] ?: ""); put("stereotype", b.attrs["stereotype"] ?: "")
            b.attrs["provider"]?.let { put("provider", it) }
            if (b.attrs["primary"] == "true") put("primary", true); if (b.attrs["conditional"] == "true") put("conditional", true)
            b.attrs["qualifier"]?.let { put("qualifier", it) }; b.attrs["profile"]?.let { put("profile", it) }
            at(b)?.let { put("at", it) }
            put("injectedInto", buildJsonArray { for (e in store.edgesTo(b.id, EdgeKind.INJECTS)) add(edgeRef(e, e.from)) })
        }
    }

    private fun listing(kind: NodeKind, budget: Int, name: String, filter: (Node) -> Boolean = { true }, item: (Node) -> JsonObject): JsonObject {
        val all = store.nodes(kind).filter(filter)
        return fit(budget) { l -> buildJsonObject { put("count", all.size); put(name, buildJsonArray { for (n in all.cap(l * 2)) add(item(n)) }) } }
    }

    fun findings(kind: String? = null, severity: String? = null, node: String? = null, budget: Int = DEFAULT_BUDGET): JsonObject {
        val all = (if (node != null) store.edgesFrom(node, EdgeKind.HAS_FINDING).mapNotNull { store.node(it.to) } else store.nodes(NodeKind.FINDING))
            .filter { (kind == null || it.attrs["kind"] == kind) && (severity == null || it.attrs["severity"] == severity) }
            .sortedWith(compareBy({ listOf("error", "warning", "info").indexOf(it.attrs["severity"]) }, { it.id }))
        return fit(budget) { l ->
            buildJsonObject {
                put("count", all.size)
                put("byKind", buildJsonObject { for ((k, list) in all.groupBy { it.attrs["kind"] ?: "other" }) put(k, list.size) })
                put("findings", buildJsonArray {
                    for (f in all.cap(l * 2)) add(buildJsonObject {
                        put("id", f.id); put("kind", f.attrs["kind"] ?: ""); put("severity", f.attrs["severity"] ?: ""); put("message", f.fqn)
                        put("subject", f.attrs["subject"] ?: ""); at(f)?.let { put("at", it) }
                    })
                })
            }
        }
    }

    fun dependencies(module: String? = null, budget: Int = DEFAULT_BUDGET): JsonObject {
        val modules = store.nodes(NodeKind.MODULE).filter { module == null || it.fqn == module || it.id == module || it.fqn == ":$module" }
        if (modules.isEmpty()) return error("no module '$module'; modules: " + store.nodes(NodeKind.MODULE).joinToString { it.fqn })
        val conflicts = store.nodes(NodeKind.FINDING).filter { it.attrs["kind"] == "version-conflict" }
        return fit(budget) { l ->
            buildJsonObject {
                put("modules", buildJsonArray {
                    for (m in modules) add(buildJsonObject {
                        put("id", m.id); put("coordinates", "${m.attrs["group"]}:${m.attrs["version"]}")
                        val deps = store.edgesFrom(m.id, EdgeKind.DEPENDS_ON_ARTIFACT).mapNotNull { store.node(it.to) }
                        put("internal", buildJsonArray { for (d in deps.filter { it.origin != Origin.EXTERNAL }.cap(l * 3)) add(buildJsonObject { put("id", d.id); put("source", d.origin.name.lowercase()) }) })
                        put("external", buildJsonArray { for (d in deps.filter { it.origin == Origin.EXTERNAL }.cap(l * 3)) add(JsonPrimitive(d.fqn)) })
                        put("externalCount", deps.count { it.origin == Origin.EXTERNAL })
                        put("conflicts", buildJsonArray { for (c in conflicts.filter { it.attrs["subject"] == m.id }.cap(l * 2)) add(JsonPrimitive(c.fqn)) })
                    })
                })
            }
        }
    }

    // ---- explain -------------------------------------------------------------------------------

    /**
     * The first call an agent should make: search, rank the flows and communities the hits belong
     * to, expand the best nodes' neighbours, and pack it all with citations into the budget.
     */
    fun explain(question: String, budget: Int = DEFAULT_BUDGET): JsonObject {
        val words = question.split(Regex("[^\\p{Alnum}_]+")).filter { it.length > 2 && it.lowercase() !in STOP }
        val hits = LinkedHashMap<String, Double>()
        // "how does X work" is answered by central methods and classes, not by fields, constructors or leaf helpers:
        // weight a hit by its kind and by how much depends on its class (inDegree from the knowledge layer)
        val weight = HashMap<String, Double>()
        fun weight(n: Node): Double = weight.getOrPut(n.id) {
            val kind = when (n.kind) { NodeKind.FIELD -> 0.5; NodeKind.CONSTRUCTOR -> 0.6; NodeKind.FILE, NodeKind.PACKAGE -> 0.2; NodeKind.DOC -> 0.7; else -> 1.0 } // prose helps, code answers
            val cls = if (n.kind in CODE) n else store.node(owner(n.id))
            val inDegree = cls?.attrs?.get("inDegree")?.toIntOrNull() ?: 0
            kind * (1.0 + kotlin.math.log10(1.0 + inDegree) / 2)
        }
        fun hit(n: Node, score: Double) { hits.merge(n.id, score * weight(n), Double::plus) }
        // tests crowd the search window on large graphs (hundreds of testGetRootCause* methods), so they are
        // dropped from the candidates unless the question is about tests
        val aboutTests = words.any { it.lowercase().startsWith("test") }
        // prose answers usage ("how do I", "why", "what is"); a mechanism question ("how is X captured") is answered
        // by code, and a doc section that lists event names would otherwise outrank the method
        val wantsDocs = USAGE.containsMatchIn(question)
        // bm25 prefers short entries: for "password" the fields and one-line accessors of four DTOs fill every slot
        // before the twenty-line method that generates one. Code with a body and the types come first, then the
        // accessors, then the fields; within a rank the search order stands
        fun rank(n: Node) = when {
            n.kind in CODE -> 0
            n.kind == NodeKind.METHOD || n.kind == NodeKind.CONSTRUCTOR -> if ((n.endLine ?: 0) - (n.startLine ?: 0) >= 2) 0 else 1
            n.kind == NodeKind.FIELD -> 2
            n.kind == NodeKind.DOC -> 1 // a README sentence after the code it describes: it took the first slot for "admin"
            else -> 0
        }
        fun find(text: String, limit: Int, kinds: Set<NodeKind>? = null): List<Node> {
            // excluded in the query, not after it: a doc section must not take a candidate slot from the code it would displace
            val found = store.search(text, if (aboutTests) limit else limit * 3, kinds ?: if (wantsDocs) null else NON_DOC)
            return (if (aboutTests) found else found.filter { it.attrs["test"] != "true" }).sortedBy { rank(it) }.take(limit)
        }
        find(words.joinToString(" "), 30).forEachIndexed { i, n -> hit(n, 3.0 / (i + 1)) }
        val stems = words.map { stem(it) }
        if (stems != words) find(stems.joinToString(" "), 30).forEachIndexed { i, n -> hit(n, 3.0 / (i + 1)) }
        // every pair of words: "reflection toString" finds reflectionToString when "based" defeats the full phrase
        // up to eight words: "what is the two step workflow for creating a user via admin" is seven, and "user admin"
        // is the pair that names saveRequest and nothing else
        if (stems.size in 3..8) for (i in stems.indices) for (j in i + 1 until stems.size) {
            find(stems[i] + " " + stems[j], 10).forEachIndexed { k, n -> hit(n, 1.5 / (k + 1)) }
        }
        for (w in words) {
            // a word one or two things in the code carry says which thing the question means: "admin" is on one
            // method, "user" is on everything. The flows already score a rare word three times; so does a body
            // rare counted over code: the routes and the README that carry "admin" all point at the same method
            fun rare(found: List<Node>) = if (found.count { it.kind in CODE || it.kind == NodeKind.METHOD || it.kind == NodeKind.CONSTRUCTOR } <= 2) 3.0 else 1.0
            val found = find(w, 15); val r = rare(found)
            found.forEachIndexed { i, n -> hit(n, r / (i + 1)) }
            val stem = stem(w)
            if (stem != w) find(stem, 15).let { f -> val rs = rare(f); f.forEachIndexed { i, n -> hit(n, 0.8 * rs / (i + 1)) } }
        }
        // "which routes ...", "what topics ...": the question names a kind, so list that kind (filtered by the other words)
        val kindHits = ArrayList<Node>() // the kind the question named: these lead the matches whatever a name scored
        for ((word, kind) in KIND_WORDS) if (words.any { stem(it.lowercase()) == word }) {
            val rest = words.filter { stem(it.lowercase()) != word }
            val scoped = if (rest.isEmpty()) emptyList() else
                (find(rest.joinToString(" "), 10, setOf(kind)) + rest.flatMap { find(stem(it), 5, setOf(kind)) }).distinctBy { it.id }
            // a kind the question named is the answer when the question is only about that kind ("which endpoints
            // exist"); when it is the object of a real subject ("what secures the endpoints"), an arbitrary ten of
            // them must not outrank the subject, so an unscoped listing scores below a name match
            // "routes for authentication": no path says "authentication", but the handler's class does (`AuthController`).
            // What the wiring is attached to names it as well as the wiring itself, so the listing is scoped by that
            // before it falls back to an arbitrary ten
            val byAttached = if (scoped.isNotEmpty() || rest.isEmpty()) emptyList() else store.nodes(kind).filter { n ->
                n.attrs["remote"] != "true" && store.edgesTo(n.id).any { e -> val words = e.from.substringBefore('(').split(Regex("""[^\p{Alnum}]+|(?<=[a-z0-9])(?=[A-Z])""")).map { it.lowercase() }.toHashSet()
                    rest.any { r -> val st = stem(r.lowercase()); words.any { w -> w.startsWith(st) || (st.length >= 4 && st.startsWith(w) && w.length >= 3) } } }
            }
            val listed = scoped.ifEmpty { byAttached }.ifEmpty { if (rest.isEmpty()) store.nodes(kind).filter { it.attrs["remote"] != "true" }.take(20) else emptyList() }
            if (listed.isNotEmpty()) { listed.forEachIndexed { i, n -> hit(n, 2.0 / (i + 1)) }; kindHits += listed }
            else store.nodes(kind).filter { it.attrs["remote"] != "true" }.take(10).forEachIndexed { i, n -> hit(n, 0.5 / (i + 1)) }
        }
        // "what routes exist for authentication" is answered by the list; "how does the signin route work" by its body
        val listing = kindHits.isNotEmpty() && !question.trim().lowercase().startsWith("how")
        if (hits.isEmpty()) for (w in words) store.nodesLike(w, 10).filter { wantsDocs || it.kind != NodeKind.DOC }.forEach { hit(it, 0.5) }
        // a hit on wiring (route, topic, config key, bean) is really about the code attached to it
        for ((id, score) in hits.toList()) {
            val n = store.node(id) ?: continue
            val attached = when (n.kind) {
                NodeKind.HTTP_ROUTE -> store.edgesTo(id).filter { it.kind == EdgeKind.HANDLES_ROUTE || it.kind == EdgeKind.CALLS_REMOTE }
                NodeKind.MESSAGE_TOPIC -> store.edgesTo(id).filter { it.kind == EdgeKind.CONSUMES_FROM || it.kind == EdgeKind.PRODUCES_TO }
                NodeKind.CONFIG_KEY -> store.edgesTo(id, EdgeKind.BINDS_CONFIG)
                NodeKind.BEAN -> store.edgesTo(id, EdgeKind.PROVIDES_BEAN)
                NodeKind.SCHEDULED_JOB -> store.edgesFrom(id, EdgeKind.CALLS)
                else -> emptyList()
            }
            for (e in attached) store.node(if (e.to == id) e.from else e.to)?.let { hit(it, score * 0.9) }
            // a hit on a library method is really about the code that calls it: "JWT signing" is `JwtBuilder#signWith`
            // by name and `generateToken` in this repo. The method's own name must say it: "stream" matched
            // `StreamResult#<init>` by its class and led to that constructor's callers, nothing to do with the question.
            // And only a method few places call; one the whole codebase calls (`List#add`) says nothing about which
            if (n.origin == Origin.EXTERNAL && n.kind == NodeKind.METHOD && stems.any { st -> simpleName(id).lowercase().startsWith(st.lowercase()) }) {
                val callers = store.edgesTo(id, EdgeKind.CALLS).mapNotNull { store.node(it.from) }.filter { it.origin == Origin.REPO && it.attrs["test"] != "true" }
                if (callers.size in 1..3) for (c in callers) hit(c, score * 0.9)
            }
        }
        // what earlier sessions fetched after asking the same thing comes first, body included: the second call
        // of last time is the first answer of this time
        memory?.let { mem ->
            val remembered = mem.recall(terms(question), ::terms)
            val top = hits.values.maxOrNull() ?: 1.0
            // just under the best match, never above it: what was read last time is a candidate, not the answer to this question
            remembered.forEachIndexed { i, id -> if (store.node(id) != null) hits.merge(id, top * (0.9 - i * 0.1), Double::plus) }
            mem.log("explain", question)
        }
        val nodes = hits.keys.mapNotNull { store.node(it) }.associateBy { it.id }.toMutableMap()

        val flows = LinkedHashMap<String, Double>()
        val communities = LinkedHashMap<String, Double>()
        for ((id, score) in hits) {
            val n = nodes[id] ?: continue
            when (n.kind) {
                NodeKind.FLOW -> flows.merge(id, score * 2, Double::plus)
                NodeKind.COMMUNITY -> communities.merge(id, score * 2, Double::plus)
                else -> {
                    val members = if (n.kind in CODE) listOf(id) + store.edgesFrom(id, EdgeKind.CONTAINS).map { it.to } else listOf(id)
                    for (m in members) for (e in store.edgesFrom(m, EdgeKind.STEP_OF_FLOW)) flows.merge(e.to, score * e.confidence, Double::plus)
                    (n.attrs["community"] ?: store.node(owner(id))?.attrs?.get("community"))?.let { communities.merge(it, score, Double::plus) }
                    if (n.kind == NodeKind.HTTP_ROUTE) n.attrs["handler"]?.let { h -> store.node("flow:$h")?.let { flows.merge(it.id, score * 3, Double::plus) } }
                }
            }
        }
        // A flow that mentions every question word (label, summary, step names) outranks one that merely
        // scored on hits; routes break ties because most questions are about request handling.
        // words, not substrings: "add" must match addPet, not ModelAndView.addObject in the external list; the
        // label and the step names only, because parameter types in step ids (java.util.List) match everything
        // how many flows each question word appears in: a word in every flow ("user", "request") says nothing about
        // which one the question means, a word in one ("signin") says everything
        val flowTokens = flows.keys.mapNotNull { store.node(it) }.associateWith { f ->
            // the entry's annotations too: @PreAuthorize(hasRole('ADMIN')) is what makes saveRequest the admin's flow
            val entry = f.attrs["entry"]?.let { store.node(it) }
            (f.fqn + " " + f.attrs["summary"].orEmpty().substringBefore("; external") + " " + (entry?.let { e -> Attrs.annotations(e).entries.joinToString(" ") { (k, v) -> k.substringAfterLast('.') + " " + v.values.joinToString(" ") } } ?: ""))
                .split(Regex("""[^\p{Alnum}]+|(?<=[a-z0-9])(?=[A-Z])""")).map { it.lowercase() }.toHashSet()
        }
        fun matches(tokens: Set<String>, stem: String) = tokens.any { it.startsWith(stem) || (stem.length >= 4 && stem.startsWith(it) && it.length >= 3) }
        val stemFlows = stems.associateWith { s -> flowTokens.values.count { matches(it, s.lowercase()) } }
        fun coverage(f: Node): Int {
            val tokens = flowTokens[f] ?: emptySet() // one token set per flow, the entry's annotations included
            // "created" is a POST, "deleted" a DELETE: CRUD vocabulary names the verb, not the handler
            val verb = stems.any { HTTP_VERBS[it.lowercase()]?.let { v -> f.fqn.startsWith(v) } == true }
            // a word half the flows share is not evidence; the rest score, the rarest highest
            val half = maxOf(2, flowTokens.size / 2)
            return stems.sumOf { s ->
                if (!matches(tokens, s.lowercase())) 0
                else when { (stemFlows[s] ?: 0) > half -> 0; (stemFlows[s] ?: 0) <= 2 -> 3; else -> 1 }
            } + (if (verb) 1 else 0)
        }
        val kindOrder = listOf("route", "consumer", "job", "main")
        // two flows covering the question alike ("creating a user": self-registration and the admin's request) are
        // told apart by which one contains the best-scoring match; the tie went to store order before
        val rankedFlows = flows.entries.sortedWith(
            compareByDescending<Map.Entry<String, Double>> { store.node(it.key)?.let { f -> coverage(f) } ?: 0 }
                .thenByDescending { e -> bestFlowStepsOf(store.node(e.key)).maxOfOrNull { hits[it] ?: 0.0 } ?: 0.0 }
                .thenBy { kindOrder.indexOf(store.node(it.key)?.attrs?.get("entryKind")) }
                .thenByDescending { it.value },
        )
        // tests describe behaviour but are rarely the answer; keep them, below production code
        val codeHits = hits.entries.filter { nodes[it.key]?.kind !in setOf(NodeKind.FLOW, NodeKind.COMMUNITY, NodeKind.PACKAGE, NodeKind.FILE) }
            .sortedByDescending { if (nodes[it.key]?.attrs?.get("test") == "true") it.value * 0.3 else it.value }

        // An agent given a map of ids goes and reads the files along it, one turn each, and every turn re-reads a
        // context fifty times the size of this answer. So the answer is the reading itself: the bodies along the
        // chain from the entry point through the best match and on to the boundary, in order, cited. That is what
        // the agent would have assembled over five turns, and what only the graph knows how to order.
        // a one-line body (an empty constructor, a trivial getter) has nothing to explain and never leads
        fun leadable(n: Node) = n.kind in setOf(NodeKind.METHOD, NodeKind.CONSTRUCTOR) && n.origin != Origin.EXTERNAL && n.attrs["test"] != "true" &&
            ((n.endLine ?: 0) - (n.startLine ?: 0) >= 1 || store.node(owner(n.id))?.kind == NodeKind.INTERFACE)
        // a nested class (a Lombok builder, an inner helper) takes its enclosing class's layer
        fun layerRank(id: String) = LAYER_RANK[(store.node(owner(id))?.attrs?.get("layer") ?: store.node(owner(id).substringBefore('$'))?.attrs?.get("layer"))] ?: 1
        // The best few distinct matches, logic layers first: a generated builder or a DTO getter matches the question's
        // words as well as the service does. On a small application the second and third candidates are as often the
        // answer as the first ("how is a user created": the request and the approval both create one), and reading both
        // costs less than one more turn.
        // A class the question names ("the parser", "the filter") is explained by the member that does its work: no
        // member name matches the question, so the busiest one stands in for the class with the class's score. The
        // constructor was standing in, and a constructor sets fields up; BsonParser's was packed with a two-line
        // feature check while nextToken, which is the parser, went unread
        for (e in codeHits.filter { nodes[it.key]?.kind in CODE }.take(3)) {
            store.edgesFrom(e.key, EdgeKind.CONTAINS).map { it.to }.mapNotNull { store.node(it) }
                .filter { leadable(it) && it !in nodes.values }
                .maxWithOrNull(compareBy({ store.edgesFrom(it.id, EdgeKind.CALLS).count() }, { (it.endLine ?: 0) - (it.startLine ?: 0) }))
                ?.let { m -> hits[m.id] = e.value; (nodes as MutableMap)[m.id] = m }
        }
        val leadHits = hits.entries.filter { nodes[it.key]?.kind !in setOf(NodeKind.FLOW, NodeKind.COMMUNITY, NodeKind.PACKAGE, NodeKind.FILE) }
            .sortedByDescending { if (nodes[it.key]?.attrs?.get("test") == "true") it.value * 0.3 else it.value }
        // The layer is a hint, not a gate. Walking the tiers meant any class Spring let us label `service` beat a
        // far better match with no label at all, which is most of a library: mongo-spark's `filterDatabases` came
        // first for "how are filters pushed down" while `MongoScanBuilder#pushFilters`, top of the search, was
        // discarded. So the score decides, and the layer only holds helpers and data back.
        val candidates = leadHits.filter { e -> nodes[e.key]?.let { leadable(it) && it.attrs["generated"] != "true" } == true }
            .sortedByDescending { e -> e.value * (LAYER_WEIGHT[store.node(owner(e.key))?.attrs?.get("layer")] ?: 1.0) }
            .map { it.key }.distinct()
        // A flow is ranked by how much of the question it covers; a body only by its own name. So one stray word
        // beats a whole workflow: "request status changes" name-matched `changePassword`, which led the answer
        // while the flow correctly showed `saveRequest`. When a flow covers two or more of the question's words,
        // the bodies come from that flow, and the best match inside it leads.
        val topFlow = rankedFlows.firstOrNull()?.let { store.node(it.key) }
        // two of the question's words, not a score of two: one rare word scores three, and "password generated"
        // handed the answer to the changePassword flow on "password" alone, over the method that generates one
        val flowCovers = topFlow?.let { f -> val tokens = flowTokens[f] ?: emptySet(); stems.count { s -> matches(tokens, s.lowercase()) && (stemFlows[s] ?: 0) <= maxOf(2, flowTokens.size / 2) } } ?: 0
        val flowSteps = bestFlowStepsOf(topFlow)
        // the flow won on covering the question, so its entry leads even when no word named it: `saveRequest` is
        // what "the user-creation request and approval workflow" means, though the question never says the word
        val flowLead = if (flowCovers >= 2) (candidates.firstOrNull { it in flowSteps }
            ?: flowSteps.firstOrNull { id -> store.node(id)?.let { leadable(it) } == true }?.also { id -> store.node(id)?.let { (nodes as MutableMap)[id] = it } }) else null
        val lead = flowLead ?: candidates.firstOrNull()
        // JIRRAFE_DEBUG=1: the ranking on stderr, for reading why an answer led with what it did
        if (System.getenv("JIRRAFE_DEBUG") == "1") System.err.println("debug: flowCovers=$flowCovers top=${topFlow?.fqn} lead=$lead" + System.lineSeparator() + candidates.take(8).joinToString(System.lineSeparator()) { "  %.2f %s".format(hits[it] ?: 0.0, it) })
        // A library has no route, consumer or job, so nothing precomputes a flow and the agent walks the call chain
        // by reading files. Follow it here instead: the same shape, derived on the spot from the best match.
        val chainSteps = if (rankedFlows.isEmpty()) chain(lead) else emptyList()
        val bestFlowSteps = flowSteps
        // a walk carries logic, not a DTO's getters or a generated builder: their shape is in `data`
        val named = { id: String -> stems.any { s -> simpleName(id).lowercase().contains(s.lowercase()) } }
        fun packable(id: String) = store.node(id)?.let { it.origin != Origin.EXTERNAL && it.file != null && it.attrs["generated"] != "true" && (layerRank(id) < 2 || named(id)) } == true
        fun walk(from: String, steps: Int): List<String> = when (from) {
            // the precomputed flow already resolved dispatch and ordered the walk: the entry, then the flow from the match on
            // (a stable sort by layer, so the service and repository steps come before the helpers the walk met first)
            in bestFlowSteps -> (listOf(bestFlowSteps.first()) + bestFlowSteps.drop(bestFlowSteps.indexOf(from)).sortedBy { layerRank(it) }).distinct()
            else -> chain(from).ifEmpty { listOf(from) }
        }.filter { it == from || packable(it) }.take(steps)
        // each further match adds a chain only where it leads somewhere the first did not
        // with no flow to start from, the entry that reaches the match leads the answer, as a flow's entry would
        val entry = if (rankedFlows.isEmpty() && lead != null) climb(lead).firstOrNull()?.takeIf { owner(it) != owner(lead) } else null
        val spine = ArrayList<String>()
        var walks = 0
        var firstWalk = 0 // where the lead's own chain ends: a workflow's next step goes in there, ahead of a mere runner-up
        for (c in (listOfNotNull(flowLead) + candidates).distinct()) { // the covering flow's entry walks first
            if (listing) break
            if (walks == PACK_LEADS || spine.size >= PACK_MAX || c in spine) continue
            if (walks > 0 && (hits[c] ?: 0.0) < (hits[lead] ?: 0.0) * NEAR_MISS) break // a further chain only for a match that could as well be the answer
            val steps = (if (walks == 0 && entry != null) listOf(entry) else emptyList()) + walk(c, if (walks == 0) PACK_STEPS else PACK_STEPS_MORE).filter { it !in spine }
            if (System.getenv("JIRRAFE_DEBUG") == "1") System.err.println("debug: walk $walks from ${simpleName(c)} (%.2f) entry=${entry?.let { simpleName(it) }} -> ${steps.map { simpleName(it) }}".format(hits[c] ?: 0.0))
            spine += steps
            walks++
            if (walks == 1) firstWalk = spine.size
        }
        // A workflow's second step is not called by its first; it reads what the first wrote. So when the question asks
        // for one, the route handler whose own chain touches a repository the lead's chain touched is the next step,
        // whatever it scored: approveRequest finds by tempPk what saveRequest saved, and "creating a user via admin"
        // never says "approve"
        if (WORKFLOW.containsMatchIn(question.lowercase()) && spine.isNotEmpty()) {
            fun repos(ids: List<String>) = ids.flatMap { id -> store.edgesFrom(id, EdgeKind.CALLS).map { owner(it.to) } }
                .filter { c -> store.node(c)?.let { n -> n.origin == Origin.REPO && (n.attrs["layer"] == "repository" || simpleName(c).endsWith("Repo") || simpleName(c).endsWith("Repository")) } == true }.toSet()
            val leadRepos = repos(spine)
            val handlers = store.edges(EdgeKind.HANDLES_ROUTE).map { it.from }.distinct().filter { h -> h !in spine && store.node(h)?.let { leadable(it) } == true }
            if (System.getenv("JIRRAFE_DEBUG") == "1") System.err.println("debug: workflow leadRepos=${leadRepos.map { simpleName(it) }} handlers=${handlers.size} " +
                handlers.joinToString(" ") { h -> simpleName(h) + ":" + (repos(chain(h)) intersect leadRepos).size })
            val next = if (leadRepos.isEmpty()) null else handlers
                .map { h -> h to (repos(chain(h)) intersect leadRepos).size }.filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { if (owner(it.first) == owner(spine.first())) 0 else 1 }).firstOrNull()?.first
            if (next != null) {
                val steps = walk(next, PACK_STEPS_MORE).filter { it !in spine }
                if (System.getenv("JIRRAFE_DEBUG") == "1") System.err.println("debug: workflow step from ${simpleName(next)} -> ${steps.map { simpleName(it) }}")
                spine.subList(firstWalk, spine.size).clear() // the runner-up's chain yields to the workflow's next step
                spine.removeAll { id -> id != lead && store.node(id)?.let { (it.endLine ?: 0) - (it.startLine ?: 0) <= 1 } == true } // an abstract repository method is no step, and held a slot
                spine += steps
            }
        }
        if (spine.size > PACK_MAX) spine.subList(PACK_MAX, spine.size).clear()
        if (System.getenv("JIRRAFE_DEBUG") == "1") System.err.println("debug: spine=${spine.map { simpleName(it) }}")
        // Whole bodies. A cut at thirty lines was an invitation to fetch the rest, and that turn costs more than the
        // whole method does; only something longer than a screen and a half is the agent's own call to read.
        // the families in play: named by the question, or declared on what the chain packs
        val onSpine = (spine + spine.map { owner(it) }).distinct().mapNotNull { store.node(it) }.flatMap { Attrs.annotations(it).keys }.map { it.substringAfterLast('.') }.toSet()
        val asked = FAMILIES.filter { f -> stems.any { s -> f.words.any { w -> s.lowercase().startsWith(w) } } }
        val families = FAMILIES.filter { f -> f in asked || f.annotations.any { it in onSpine } }
        val spineIds = (spine + spine.map { owner(it) }).toSet()
        val wiring = ArrayList<Pair<Node, String>>() // site, its annotation text
        val wiringBodies = ArrayList<String>() // the bean methods that configure the concept, packed like chain steps
        for (f in families) {
            for (ann in f.annotations) for (a in store.nodesLike(ann, 20).filter { it.id.endsWith(".$ann") }) {
                for (e in store.edgesTo(a.id, EdgeKind.ANNOTATED_WITH)) store.node(e.from)?.takeIf { it.origin == Origin.REPO && it.attrs["test"] != "true" }?.let { site ->
                    // the question named the concept: every site. Only the chain did: the chain's own sites and the @Enable* that switches it on
                    if (f in asked || site.id in spineIds || ann.startsWith("Enable")) wiring += site to annotationText(site, a.id)
                }
            }
            // the family lists its bean types in the order they explain the concept: the filter chain declares the
            // rules, the encoder is a detail. Store order put the detail first and the rules never fitted.
            // a @Component filter's bean type is its own class, not `OncePerRequestFilter`: ask the hierarchy
            fun familyType(b: Node): String? {
                val t = b.attrs["type"] ?: return null
                t.substringAfterLast('.').takeIf { it in f.beanTypes }?.let { return it }
                return store.edgesFrom(t, EdgeKind.EXTENDS).plus(store.edgesFrom(t, EdgeKind.IMPLEMENTS))
                    .map { it.to.substringAfterLast('.') }.firstOrNull { it in f.beanTypes }
            }
            for (b in store.nodes(NodeKind.BEAN).sortedBy { f.beanTypes.indexOf(familyType(it)).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }) {
                val type = familyType(b) ?: continue
                // a @Bean method names itself in `provider`; a @Component is joined to its bean by an edge
                val provider = b.attrs["provider"] ?: store.edgesTo(b.id, EdgeKind.PROVIDES_BEAN).firstOrNull()?.from ?: continue
                if ('#' in provider) wiringBodies += provider // a @Bean method: its body is the configuration
                else {
                    store.node(provider)?.let { c -> wiring += c to (type + " bean") }
                    // a filter's configuration is what it does per request, so its own override is the body to show
                    store.edgesFrom(provider, EdgeKind.CONTAINS).map { it.to }
                        .firstOrNull { it.substringAfter('#').substringBefore('(') in FILTER_METHODS }?.let { wiringBodies += it }
                }
            }
        }
        val dependencies = manifest?.modules.orEmpty().flatMap { m -> m.configurations.flatMap { it.artifacts } }.filter { a -> families.any { f -> f.artifacts.any { w -> (a.name ?: "").contains(w, ignoreCase = true) } } }
            .map { "${it.group}:${it.name}:${it.version}" }.distinct().sortedBy { if ("starter" in it) 0 else 1 }.take(5)
        val wiringSites = wiring.distinctBy { it.first.id }.sortedBy { it.first.file + ":" + it.first.startLine }.take(WIRING_SITES)
        // Where the answer lives decides what to send. Nine questions in ten are answered inside one or two files.
        // A small file read whole is cheaper than the same facts cut into fragments, and it reads in the order it
        // was written; a large one never is. So: the whole file when it is small, its card when it is not, and the
        // chain of bodies only when the answer is genuinely spread.
        val spineFiles = spine.mapNotNull { store.node(it)?.file }.distinct()
        val concentrated = spineFiles.size in 1..2 && wiringBodies.none { it !in spine }
        val fileTokens = spineFiles.associateWith { f -> runCatching { java.nio.file.Files.size(java.nio.file.Path.of(f)) / 4 }.getOrDefault(Long.MAX_VALUE) }
        val whole = if (concentrated) spineFiles.filter { (fileTokens[it] ?: Long.MAX_VALUE) <= WHOLE_FILE_TOKENS } else emptyList()
        val cards = if (concentrated) spineFiles.filter { it !in whole } else emptyList()
        // a one-line delegation adds a header, a citation and a line of code to say what its caller already showed
        val trivial = { id: String -> store.node(id)?.let { (it.endLine ?: 0) - (it.startLine ?: 0) <= 1 && it.id != lead } == true }
        // the framework body the question is about comes first: "token checks on subsequent requests" means the
        // filter, not the encoder, whatever order the family lists its types in
        // what the question asks about: its own words against the member and the class it lives in
        val namedWiring = { id: String -> stems.any { st -> val w = st.lowercase().take(5)
            simpleName(id).lowercase().contains(w) || owner(id).substringAfterLast('.').lowercase().contains(w) } }
        // "JWT signing" is answered by `generateToken`; the filter chain and the user-details service are the same
        // family and not the question. They ride when the question names them, or when nothing else answers it
        val askedFirst = wiringBodies.filter { it !in spine }.distinct().filter { !listing && (spine.isEmpty() || namedWiring(it)) }
        val pack = (spine.filterIndexed { i, id -> i == 0 || !trivial(id) } + askedFirst.take(WIRING_BODIES)).mapNotNull { id -> nodes[id] ?: store.node(id) }
            .filter { n -> n.file == null || (n.file !in whole && n.file !in cards) } // its file is already going, whole or as a card
            .mapNotNull { n ->
            sources?.read(n, 0)?.let { s ->
                // tabs and a method's own indentation are escape sequences in JSON and tokens in a context; neither says anything
                val raw = s.text.lines().dropWhile { it.isBlank() }.map { it.replace("\t", "  ").trimEnd() }
                val indent = raw.filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length } ?: 0
                val lines = raw.map { it.drop(minOf(indent, it.length - it.trimStart().length)) }
                Pack(n.id, s, lines.take(PACK_LINES).joinToString("\n"), lines.size > PACK_LINES)
            }
        }
        val packed = pack.map { it.id }.toHashSet()
        // the file as the agent would have read it: cheaper than fragments below the threshold, and coherent
        val wholeFiles = whole.mapNotNull { f ->
            val text = runCatching { java.nio.file.Files.readString(java.nio.file.Path.of(f)) }.getOrNull() ?: return@mapNotNull null
            Triple(relative(f), text.replace("\t", "  ").lines(), spine.filter { store.node(it)?.file == f })
        }
        // a large file as its shape: what it declares, where each member starts, and only the bodies that matched
        val cardFiles = cards.mapNotNull { f ->
            val cls = spine.firstOrNull { store.node(it)?.file == f }?.let { store.node(owner(it).substringBefore('$')) } ?: return@mapNotNull null
            val members = store.edgesFrom(cls.id, EdgeKind.CONTAINS).mapNotNull { store.node(it.to) }
                .filter { it.kind == NodeKind.METHOD || it.kind == NodeKind.CONSTRUCTOR }
                .sortedBy { it.startLine ?: Int.MAX_VALUE }
                .map { "${it.startLine ?: 0} ${shortName(it)}" }
            Triple(cls, members.take(CARD_MEMBERS), spine.filter { store.node(it)?.file == f })
        }
        // The data the chain moves, as a field list each: on a CRUD service the entities and DTOs are the domain, and
        // "what does it return" is answered by a shape, not a body. Types the packed classes use, model layer only,
        // the ones named in the packed code first.
        val packText = pack.joinToString("\n") { it.text }
        val data = spine.map { owner(it) }.distinct().flatMap { c -> store.edgesFrom(c, EdgeKind.USES_TYPE).map { it.to } }.distinct()
            .mapNotNull { store.node(it) }
            .filter { it.kind in CODE && it.origin == Origin.REPO && it.attrs["layer"] == "model" && it.id.substringAfterLast('.').substringAfterLast('$') in packText }
            .take(DATA_CLASSES)
            .map { c -> c to store.edgesFrom(c.id, EdgeKind.CONTAINS).mapNotNull { store.node(it.to) }.filter { it.kind == NodeKind.FIELD }.map { it.id.substringAfterLast('#') }.filter { !it.startsWith("this") && '$' !in it }.take(DATA_FIELDS) }
        // and the configuration the chain reads, key and value, so a secret's name or an expiry is not a file read away
        val configKeys = (spine + spine.map { owner(it) }).distinct().flatMap { store.edgesFrom(it, EdgeKind.BINDS_CONFIG).map { e -> e.to } }.distinct()
            .mapNotNull { store.node(it) }.take(CONFIG_KEYS)

        // What an agent measurably uses, in order: the first flow's steps and their file:line, the top nodes and their
        // edge lists (28 of 49 follow-up ids came only from edges), then docs, then later flows, then communities
        // (0 of 66 answers cited one). Shrinking follows the reverse order; nothing here duplicates anything else.
        return fit(budget) { l ->
            buildJsonObject {
                put("question", question)
                stale()?.let { put("stale", it) }
                if (hits.isEmpty()) put("note", "nothing matched; try search with a class or method name, or overview")
                put("flows", buildJsonArray {
                    if (chainSteps.isNotEmpty()) add(buildJsonObject {
                        put("id", "chain:" + chainSteps.first()); put("entry", chainSteps.first())
                        put("derived", "call chain from the best match; this project has no route, consumer or job to precompute a flow from")
                        put("stepCount", chainSteps.size)
                        val shown = chainSteps.cap(maxOf(2, l))
                        if (shown.size < chainSteps.size) put("truncated", true)
                        put("steps", buildJsonArray { for (s in shown) add(buildJsonObject { put("id", s); store.node(s)?.let { n -> at(n)?.let { put("at", it) } } }) })
                    })
                    // with bodies in the answer the flows are context, not the answer: the first one's steps beyond the
                    // pack, and the others by name only. Every step id is forty characters an agent re-reads every turn.
                    val packedBodies = pack.isNotEmpty()
                    for ((i, entry) in rankedFlows.cap(if (packedBodies) 1 else maxOf(1, l / 4)).withIndex()) {
                        val f = store.node(entry.key) ?: continue
                        add(buildJsonObject {
                            put("id", entry.key); put("entry", f.fqn); f.attrs["llmSummary"]?.let { put("summary", it) }
                            val steps = f.attrs["steps"]?.let { json.parseToJsonElement(it).jsonArray } ?: JsonArray(emptyList())
                            val shown = when {
                                packedBodies && i > 0 -> emptyList()
                                packedBodies -> steps.toList().filter { s -> s.jsonObject["id"]!!.jsonPrimitive.content.let { it !in packed && packable(it) } }.cap(minOf(6, l)) // the steps worth following, not the DTO builders the walk met
                                else -> steps.toList().cap(if (i == 0) l else maxOf(3, l / 2)) // later flows shrink first
                            }
                            put("stepCount", steps.size)
                            if (shown.size < steps.size) put("truncated", true)
                            if (shown.isNotEmpty()) put("steps", buildJsonArray { for (s in shown) { val sid = s.jsonObject["id"]!!.jsonPrimitive.content; add(buildJsonObject { put("id", sid); store.node(sid)?.let { n -> at(n)?.let { put("at", it) } } }) } })
                            f.attrs["external"]?.takeIf { it.isNotEmpty() && l >= 10 }?.let { put("external", it.split(',').take(5).joinToString(",")) }
                            f.attrs["artifacts"]?.takeIf { it.isNotEmpty() }?.let { put("artifacts", it) }
                        })
                    }
                })
                // communities were cited in none of sixty-six answers; they ride only when there is nothing else to say
                put("communities", buildJsonArray {
                    for ((id, _) in (if (pack.isNotEmpty()) emptyList() else communities.entries.sortedByDescending { it.value }.cap(1))) {
                        val c = store.node(id) ?: continue
                        add(buildJsonObject { put("id", id); put("label", c.attrs["llmLabel"] ?: c.fqn); c.attrs["llmSummary"]?.let { put("summary", it) } })
                    }
                })
                // the bodies come before the id lists and shrink last: they are the answer, the ids are the map. At the
                // budget the answer sits between levels 5 and 9, where two bodies were kept and the flow listing, the
                // wiring sites and the matches were not: a workflow's second step was cut while its map stayed
                val bodies = pack.cap(when { l >= 20 -> PACK_MAX; l >= 3 -> 4; l >= 2 -> 3; l >= 1 -> 1; else -> 0 })
                if (bodies.isNotEmpty()) put("pack", buildJsonArray {
                    for (p in bodies) add(buildJsonObject {
                        put("id", p.id); put("at", "${relative(p.source.file)}:${p.source.startLine}")
                        classHeader(p.id)?.let { put("class", it) } // the declaration the body lives in: its annotations and supertypes
                        val cls = owner(p.id).substringBefore('$')
                        if (l >= 5 && bodies.firstOrNull { owner(it.id).substringBefore('$') == cls } === p) fieldsOf(cls, bodies.filter { owner(it.id).substringBefore('$') == cls }.joinToString("\n") { it.text }).takeIf { it.isNotEmpty() }?.let { fs -> put("fields", buildJsonArray { for (f in fs) add(JsonPrimitive(f)) }) }
                        if (p.source.decompiled) put("decompiled", true)
                        val callers = cleanEdges(p.id, store.edgesTo(p.id)) { it.from }.size
                        if (callers > 0) put("callers", callers)
                        // what the body calls into the project, by id, so a repository query or a helper is named without a read
                        // where the body goes next, by id, and only where it is not already shown: a written token costs
                        // twelve re-read ones, so nothing is said twice
                        val calls = cleanEdges(p.id, store.edgesFrom(p.id).filter { it.kind == EdgeKind.CALLS || it.kind == EdgeKind.DISPATCHES_TO }) { it.to }
                            .filter { e -> e.to !in packed && store.node(e.to)?.origin != Origin.EXTERNAL && layerRank(e.to) < 3 }
                        if (l >= 10 && calls.isNotEmpty()) put("calls", buildJsonArray { for (e in calls.cap(4)) add(JsonPrimitive(e.to)) })
                        put("text", p.text); if (p.truncated) put("truncated", true)
                    })
                })
                if (wholeFiles.isNotEmpty()) put("files", buildJsonArray {
                    for ((path, lines, matched) in wholeFiles) add(buildJsonObject {
                        put("path", path); put("lines", lines.size)
                        put("answers", buildJsonArray { for (m in matched) add(JsonPrimitive(m)) })
                        put("text", lines.joinToString("\n"))
                    })
                })
                if (cardFiles.isNotEmpty()) put("cards", buildJsonArray {
                    for ((cls, members, matched) in cardFiles) add(buildJsonObject {
                        put("id", cls.id); at(cls)?.let { put("at", it) }
                        classHeader(cls.id + "#")?.let { put("class", it) }
                        fieldsOf(cls.id, matched.mapNotNull { id -> nodes[id]?.let { sources?.read(it, 0)?.text } }.joinToString("\n")).takeIf { it.isNotEmpty() }
                            ?.let { fs -> put("fields", buildJsonArray { for (x in fs) add(JsonPrimitive(x)) }) }
                        put("members", buildJsonArray { for (m in members) add(JsonPrimitive(m)) })
                        put("bodies", buildJsonArray { for (m in matched) { val n = nodes[m] ?: store.node(m); val s = n?.let { sources?.read(it, 0) }
                            if (n != null && s != null) add(buildJsonObject { put("id", n.id); put("at", "${relative(s.file)}:${s.startLine}"); put("text", s.text.replace("\t", "  ").lines().take(PACK_LINES).joinToString("\n")) }) } })
                    })
                })
                if (l >= 5 && data.isNotEmpty()) put("data", buildJsonArray {
                    for ((c, fields) in data) add(buildJsonObject { put("id", c.id); at(c)?.let { put("at", it) }; c.attrs["table"]?.let { put("table", it) }; put("fields", fields.joinToString(", ")) })
                })
                if (l >= 5 && configKeys.isNotEmpty()) put("config", buildJsonArray {
                    for (k in configKeys) add(buildJsonObject { put("key", k.fqn); k.attrs["value"]?.let { put("value", it) }; at(k)?.let { put("at", it) } })
                })
                // complete for the annotations named: the graph knows every site, which is what lets an agent stop looking
                if (l >= 5 && wiringSites.isNotEmpty()) put("wiring", buildJsonArray {
                    for ((site, text) in wiringSites.cap(maxOf(4, l))) add(buildJsonObject { put("id", site.id); at(site)?.let { put("at", it) }; put("declares", text) })
                })
                if (l >= 5 && dependencies.isNotEmpty()) put("dependencies", buildJsonArray { for (d in dependencies) add(JsonPrimitive(d)) })
                // a thin answer says which of the code's own words are near the question, so the next ask lands
                if (pack.isEmpty() || hits.size < 3) nearbyVocabulary(words + stems).takeIf { it.isNotEmpty() }?.let { v -> put("vocabulary", buildJsonArray { for (t in v) add(JsonPrimitive(t)) }) }
                // the other matches: with bodies packed, a few names and lines for the agent to choose to follow; the
                // edge lists that were the follow-up ids before the bodies were here are now the bodies' own `calls`
                put("nodes", buildJsonArray {
                    // a kind the question named is listed in full, first: "what routes exist for authentication" is the routes
                    val listedIds = kindHits.map { it.id }
                    // the repo's own code before a library stub or an annotation: `@GeneratedValue` and `lombok.Generated`
                    // outscored the method that generates a password, and an agent cannot open either of them
                    // and a body before a one-line accessor, as the word search orders them: five getters and setters
                    // of `password` filled the list while the method that generates one sat sixth
                    val ranked = (listedIds + codeHits.map { it.key }.sortedWith(compareBy({ if (nodes[it]?.origin == Origin.REPO) 0 else 1 }, { nodes[it]?.let { n -> rank(n) } ?: 0 }))).distinct().filter { it !in packed }
                    // five, not three: "password generated" led with generateToken, and the generator, fourth by score,
                    // was cut from the list an agent would have followed it from
                    // and when the budget binds (level three and under), the map yields to the bodies: two, not five
                    for (id in ranked.cap(listedIds.size + (if (pack.isNotEmpty()) (if (l >= 5) 5 else 2) else maxOf(2, l / 2)))) {
                        val n = nodes[id] ?: continue
                        add(buildJsonObject {
                            ref(n).forEach { (k, v) -> put(k, v) }
                            n.signature?.let { put("signature", it) }
                            if (l >= 10) n.doc?.let { d -> firstSentence(d)?.let { put("doc", it) } }
                            for (key in listOf("verb", "path", "value", "type")) n.attrs[key]?.let { put(key, it) }
                            val out = if (pack.isNotEmpty()) emptyList() else cleanEdges(id, store.edgesFrom(id)) { it.to }
                            val inc = if (pack.isNotEmpty()) emptyList() else cleanEdges(id, store.edgesTo(id)) { it.from }
                            if (out.isNotEmpty()) put("uses", buildJsonArray { for (e in out.cap(maxOf(2, l / 4))) add(edgeRef(e, e.to)) })
                            if (inc.isNotEmpty()) put("usedBy", buildJsonArray { for (e in inc.cap(maxOf(2, l / 4))) add(edgeRef(e, e.from)) })
                            val findings = store.edgesFrom(id, EdgeKind.HAS_FINDING).mapNotNull { store.node(it.to) }
                            if (findings.isNotEmpty()) put("findings", buildJsonArray { for (f in findings.cap(3)) add(JsonPrimitive(f.fqn)) })
                        })
                    }
                })
            }
        }
    }

    /** One body in the reading pack: a step of the chain with its source, cut to a few dozen lines. */
    private class Pack(val id: String, val source: SourceReader.Source, val text: String, val truncated: Boolean)

    /**
     * Edge lists an agent can act on: no self-edges, no test-class targets reached through heuristic dispatch
     * (16-24% of edge characters on libraries), one entry per target keeping the best-resolved edge, exact first.
     */
    private fun cleanEdges(self: String, edges: List<Edge>, other: (Edge) -> String): List<Edge> {
        val selfIsTest = store.node(owner(self))?.attrs?.get("test") == "true"
        return edges.asSequence()
            .filter { it.kind !in STRUCTURE && it.kind != EdgeKind.MENTIONS && other(it) != self } // a doc that names the method is not a caller; `node` still lists it
            .filter { it.kind != EdgeKind.DISPATCHES_TO || it.confidence >= DISPATCH_FLOOR } // one `post` among twenty implementations says nothing; `neighbors` still has them
            .filter { selfIsTest || store.node(owner(other(it)))?.attrs?.get("test") != "true" }
            .sortedWith(compareBy<Edge> { it.resolution.ordinal }.thenByDescending { it.confidence })
            .distinctBy { other(it) }
            .toList()
    }

    /**
     * The call chain out of [start], for a project with no entry point to precompute a flow from. One hop per step,
     * the best-resolved call into indexed non-test code, preferring a target that calls on, so the chain follows the
     * request rather than stopping at the first getter.
     */
    private fun bestFlowStepsOf(flow: Node?): List<String> = flow?.attrs?.get("steps")
        ?.let { s -> json.parseToJsonElement(s).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content } }.orEmpty()

    /**
     * The repo method that reaches [id] from above, and the path down to it. A library has no route, consumer or job
     * to precompute a flow from, so the chain walked only downwards: from `Html#isMatch`, a private helper that
     * matched the question by name, it found sibling helpers while the mechanism starts at `convertToHtml`. Climbing
     * the callers finds that entry, preferring the one nothing else calls.
     */
    private fun climb(id: String): List<String> {
        // breadth first, not a single line up: the first caller of a helper is often an anonymous visitor that nothing
        // calls, a dead end, while the entry sits four hops away through a sibling
        val seen = LinkedHashSet<String>(); var frontier = listOf(id)
        val levels = ArrayList<List<String>>()
        repeat(CHAIN_STEPS) {
            val next = frontier.flatMap { store.edgesTo(it, EdgeKind.CALLS).map { e -> e.from } }
                .filter { c -> c != id && seen.add(c) && store.node(c)?.let { it.origin == Origin.REPO && it.attrs["test"] != "true" && it.file != null } == true }
            if (next.isEmpty()) return@repeat
            levels += next; frontier = next
        }
        // the highest named caller reached: an anonymous visitor is a dead end, and the public wrapper above the
        // entry is usually one line, so the top of a four-hop climb is where the mechanism starts
        for (level in levels.asReversed()) level.firstOrNull { c ->
            '$' !in owner(c) && store.node(c)?.let { (it.endLine ?: 0) - (it.startLine ?: 0) >= 1 } == true
        }?.let { return listOf(it) }
        return emptyList()
    }

    private fun chain(start: String?): List<String> {
        var current = start ?: return emptyList()
        val steps = arrayListOf(current)
        while (steps.size < CHAIN_STEPS) {
            // a call to an interface method is followed into its implementation (dispatch edges, confidence 1/n);
            // the request goes on through services, repositories and clients, not into a DTO's builder
            val next = (store.edgesFrom(current, EdgeKind.CALLS) + store.edgesFrom(current, EdgeKind.DISPATCHES_TO).filter { it.confidence >= DISPATCH_FLOOR })
                .filter { it.to !in steps }
                .mapNotNull { e -> store.node(e.to)?.let { e to it } }
                .filter { (_, n) -> n.origin != Origin.EXTERNAL && n.file != null && n.attrs["test"] != "true" }
                .sortedWith(
                    compareBy<Pair<Edge, Node>> { LAYER_RANK[store.node(owner(it.second.id))?.attrs?.get("layer")] ?: 1 }
                        .thenByDescending { store.edgesFrom(it.second.id, EdgeKind.CALLS).isNotEmpty() }
                        .thenBy { it.first.resolution.ordinal }
                        .thenByDescending { it.first.confidence },
                )
                .firstOrNull()?.second?.id ?: break
            steps += next
            current = next
        }
        return if (steps.size > 1) steps else emptyList()
    }

    /** The graph's own words, once per process: the camelCase pieces of every repo class, member, route and config key. */
    private val vocabulary: Map<String, Int> by lazy {
        val counts = HashMap<String, Int>()
        for (k in listOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.ENUM, NodeKind.RECORD, NodeKind.METHOD, NodeKind.FIELD, NodeKind.CONFIG_KEY, NodeKind.HTTP_ROUTE)) for (n in store.nodes(k)) {
            if (n.origin == Origin.EXTERNAL) continue
            val name = if (k == NodeKind.CONFIG_KEY || k == NodeKind.HTTP_ROUTE) n.fqn else n.id.substringAfterLast('.').substringAfterLast('$').substringAfter('#').substringBefore('(')
            for (t in name.split(Regex("""[^\p{Alnum}]+|(?<=[a-z0-9])(?=[A-Z])""")).map { it.lowercase() }) if (t.length in 3..30) counts.merge(t, 1, Int::plus)
        }
        counts
    }

    /**
     * For a question the code's words did not match ("database connection" against `datasource`, "created" against
     * `saveRequest`): the identifiers nearest the question's words, so the agent can ask again in the code's own
     * vocabulary instead of guessing a synonym. Drawn from the graph only; nothing invented.
     */
    private fun nearbyVocabulary(words: List<String>): List<String> {
        val want = words.map { it.lowercase() }.filter { it.length >= 3 }
        return vocabulary.entries
            .filter { (t, _) -> want.any { w -> t != w && (t.startsWith(w.take(4)) || w.startsWith(t.take(4)) || (w.length >= 5 && t.contains(w.take(5)))) } }
            .sortedByDescending { it.value }.map { it.key }.take(20)
    }

    /** `@PreAuthorize(hasRole('ADMIN'))`: one annotation on a node with its values, as it reads in the source. */
    private fun annotationText(n: Node, annotation: String): String {
        val values = Attrs.annotations(n)[annotation].orEmpty()
        val name = "@" + annotation.substringAfterLast('.')
        return when {
            values.isEmpty() -> name
            values.keys == setOf("value") -> name + "(" + values["value"] + ")"
            else -> name + values.entries.joinToString(", ", "(", ")") { (k, v) -> "$k=$v" }
        }
    }

    /** `@Service class LoginServiceImpl implements LoginService`: the class a member lives in, as declared. */
    private fun classHeader(id: String): String? {
        val c = store.node(owner(id).substringBefore('$')) ?: return null
        val annotations = Attrs.annotations(c).keys.filter { !it.startsWith("java.lang.") && !it.startsWith("lombok.") }.joinToString(" ") { annotationText(c, it) }
        val decl = (c.signature ?: c.id.substringAfterLast('.')).replace(PACKAGE, "")
        return listOf(annotations, decl).filter { it.isNotEmpty() }.joinToString(" ").takeIf { it.isNotEmpty() }
    }

    /**
     * What a class holds, as declared: `@Autowired private UserRepo userRepo`, `private final Argon2PasswordEncoder
     * encoder = new Argon2PasswordEncoder(SALT, HASH_LENGTH, ...)`. A method body uses these by name; the encoder
     * it was built with, the repository it was given, live here and nowhere a body shows. Read from the source
     * when a reader is at hand (the initialiser is the fact), the signature otherwise; constants and loggers left out.
     */
    private fun fieldsOf(classId: String, usedIn: String): List<String> {
        val c = store.node(classId) ?: return emptyList()
        if (c.kind !in CODE || c.origin == Origin.EXTERNAL) return emptyList()
        val fields = store.edgesFrom(classId, EdgeKind.CONTAINS).mapNotNull { store.node(it.to) }
            .filter { it.kind == NodeKind.FIELD && it.attrs["generated"] != "true" }
            .filter { f -> val sig = f.signature.orEmpty(); !("static" in sig && "final" in sig) && !sig.contains("Logger") && !f.id.endsWith("#class") }
            .filter { f -> Regex("\\b" + Regex.escape(f.id.substringAfterLast('#')) + "\\b").containsMatchIn(usedIn) } // only what the shown bodies use
            .sortedBy { it.startLine ?: Int.MAX_VALUE }
        return fields.take(FIELDS_MAX).map { f ->
            val ann = Attrs.annotations(f).keys.filter { !it.startsWith("java.lang.") && !it.startsWith("lombok.") }.joinToString(" ") { annotationText(f, it) }
            val text = sources?.read(f, 0)?.text?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("@") }?.joinToString(" ")?.trimEnd(';')?.take(200)
                ?: f.signature?.replace(PACKAGE, "") ?: f.id.substringAfterLast('#')
            listOf(ann, text).filter { it.isNotEmpty() }.joinToString(" ")
        }
    }

    /** The first sentence of a Javadoc, markup stripped, at most 200 characters and never cut mid-word. */
    private fun firstSentence(doc: String): String? {
        val text = doc.replace(Regex("\\{@(?:code|link|linkplain)\\s+([^}]*)}"), "$1").replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        val end = Regex("[.!?](\\s|$)").find(text)?.range?.first?.plus(1) ?: text.length
        val sentence = text.substring(0, end)
        return if (sentence.length <= 200) sentence else sentence.take(200).substringBeforeLast(' ') + " ..."
    }

    /** `placed` -> `place`, `orders` -> `order`, `listing` -> `list`: prefix search does the rest. */
    /** The stems `explain` searches on, as a set: what two phrasings of one question have in common. */
    private fun terms(question: String): Set<String> =
        question.split(Regex("[^\\p{Alnum}_]+")).filter { it.length > 2 && it.lowercase() !in STOP }.map { stem(it).lowercase() }.toSet()

    private fun stem(w: String): String = when {
        w.length > 5 && w.endsWith("ing") -> undouble(w.dropLast(3))
        w.length > 4 && w.endsWith("ied") -> w.dropLast(3) + "y"
        w.length > 4 && w.endsWith("ed") -> undouble(w.dropLast(2))
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    /** "planned" -> "plann" -> "plan", "mapping" -> "mapp" -> "map"; "called" -> "call" and "passed" -> "pass" keep their double letter. */
    private fun undouble(w: String): String =
        if (w.length > 3 && w[w.length - 1] == w[w.length - 2] && w.last() !in "lsz" && w.last() !in "aeiou") w.dropLast(1) else w

    private val HTTP_VERBS = mapOf(
        "creat" to "POST", "add" to "POST", "regist" to "POST", "submit" to "POST", "post" to "POST",
        "updat" to "PUT", "edit" to "PUT", "chang" to "PUT", "modify" to "PUT", "put" to "PUT",
        "delet" to "DELETE", "remov" to "DELETE",
        "list" to "GET", "fetch" to "GET", "show" to "GET", "read" to "GET", "look" to "GET", "search" to "GET", "find" to "GET",
    )

    private val KIND_WORDS = mapOf(
        "route" to NodeKind.HTTP_ROUTE, "endpoint" to NodeKind.HTTP_ROUTE, "topic" to NodeKind.MESSAGE_TOPIC, "queue" to NodeKind.MESSAGE_TOPIC,
        "config" to NodeKind.CONFIG_KEY, "configur" to NodeKind.CONFIG_KEY, "configuration" to NodeKind.CONFIG_KEY, "property" to NodeKind.CONFIG_KEY, "setting" to NodeKind.CONFIG_KEY, // "configured" stems to "configur"
        "bean" to NodeKind.BEAN, "job" to NodeKind.SCHEDULED_JOB, "schedul" to NodeKind.SCHEDULED_JOB, "cron" to NodeKind.SCHEDULED_JOB,
        "flow" to NodeKind.FLOW, "community" to NodeKind.COMMUNITY, "finding" to NodeKind.FINDING,
        "unused" to NodeKind.FINDING, "dead" to NodeKind.FINDING, "cycle" to NodeKind.FINDING, "cyclic" to NodeKind.FINDING, "conflict" to NodeKind.FINDING, "violation" to NodeKind.FINDING, "smell" to NodeKind.FINDING,
    )

    private val STOP = setOf("the", "and", "how", "does", "what", "where", "which", "with", "for", "this", "that", "are", "when", "from", "into", "work", "works", "code", "mechanism", "used", "use", "uses",
        "exist", "exists", "there", "list", "repo", "project") // "in this repo" is not about `UserRepo`; "which routes exist" is about the routes
}
