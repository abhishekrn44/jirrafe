package io.jirrafe.frameworks.spring

import io.jirrafe.core.model.Attrs
import io.jirrafe.core.model.Edge
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Origin
import io.jirrafe.core.model.Resolution
import io.jirrafe.core.plugin.FrameworkPlugin
import io.jirrafe.core.plugin.IndexContext
import io.jirrafe.core.store.GraphStore
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Spring semantics on top of the code graph: beans and injection, routes, config keys, JPA,
 * messaging, remote calls, scheduled jobs, and the proxy self-invocation finding.
 * Everything here is derived from annotation values, parameters, string constants and edges
 * that the extractors already stored; the plugin never parses source itself.
 */
class SpringPlugin : FrameworkPlugin {
    override val id = "spring"

    override fun contribute(ctx: IndexContext) = Pass(ctx).run()

    private class Bean(
        val id: String, val name: String, val type: String, val provider: Node,
        val primary: Boolean, val qualifier: String?, val profile: String?, val conditional: Boolean,
    )

    private class Pass(private val ctx: IndexContext) {
        private val store: GraphStore = ctx.store
        private val classes: Map<String, Node> = CLASS_KINDS.flatMap { store.nodes(it) }.associateBy { it.id }
        private val beans = ArrayList<Bean>()
        private val config = LinkedHashMap<String, ConfigEntry>()
        private val descendants = HashMap<String, Set<String>>()
        private val knownTopics = HashSet<String>()

        fun run() {
            loadConfig()
            collectBeans()
            injections()
            routes()
            access()
            configBindings()
            jpa()
            listeners()
            producers()
            remoteCalls()
            scheduled()
            selfInvocations()
            store.flush()
        }

        // ---- config keys --------------------------------------------------------------------

        private fun loadConfig() {
            val dirs = ctx.manifest.modules.flatMap { it.resourceDirs }.map(Path::of)
            for (file in ConfigFiles.find(dirs)) for (entry in ConfigFiles.read(file)) {
                config.putIfAbsent(entry.key, entry)
                store.node(
                    Node(
                        "config:${entry.key}", NodeKind.CONFIG_KEY, entry.key, Origin.REPO, file = entry.file.toString(),
                        startLine = entry.line, endLine = entry.line,
                        attrs = buildMap {
                            put("value", entry.value); put("defined", "true")
                            entry.profile?.let { put("profile", it) }
                        },
                    )
                )
            }
        }

        /** `${key}` or `${key:default}`; returns key and whether a default exists. */
        private fun placeholder(text: String): Pair<String, Boolean>? {
            val m = PLACEHOLDER.find(text) ?: return null
            return m.groupValues[1] to (m.groupValues[2].isNotEmpty())
        }

        private fun resolve(text: String): String {
            val (key, _) = placeholder(text) ?: return text
            return config[key]?.value ?: text
        }

        private fun bindConfig(from: String, text: String) {
            val (key, hasDefault) = placeholder(text) ?: return
            val id = "config:$key"
            if (key !in config) {
                store.node(Node(id, NodeKind.CONFIG_KEY, key, Origin.REPO, attrs = mapOf("defined" to "false")))
                if (!hasDefault) {
                    finding(from, "undefined-config-key", key, "warning", "Config key '$key' is used but not defined in any application file")
                }
            }
            store.edge(Edge(from, id, EdgeKind.BINDS_CONFIG, Resolution.EXACT))
        }

        private fun configBindings() {
            for (cls in classes.values) {
                for (member in members(cls)) {
                    annotationsOf(member)[VALUE]?.get("value")?.let { bindConfig(cls.id, it) }
                    for (p in Attrs.params(member)) p.annotations[VALUE]?.get("value")?.let { bindConfig(cls.id, it) }
                }
                val props = annotationsOf(cls)[CONFIGURATION_PROPERTIES] ?: continue
                val prefix = (props["prefix"] ?: props["value"] ?: "").trim()
                for (key in config.keys) if (prefix.isEmpty() || key == prefix || key.startsWith("$prefix.")) {
                    store.edge(Edge(cls.id, "config:$key", EdgeKind.BINDS_CONFIG, Resolution.EXACT))
                }
            }
        }

        // ---- beans -----------------------------------------------------------------------------

        private fun collectBeans() {
            val autoConfigured = autoConfiguredClasses()
            for (cls in classes.values) {
                val ann = annotationsOf(cls)
                val stereotype = ann.keys.firstOrNull { isStereotype(it) }
                val repository = cls.kind == NodeKind.INTERFACE && isSpringDataRepository(cls.id)
                if (stereotype == null && !repository) continue
                val fromJar = cls.artifact != null
                val conditional = ann.keys.any { isCondition(it) } || (fromJar && cls.id !in autoConfigured)
                val name = ann[stereotype]?.let { it["value"] ?: it["name"] }?.takeIf { it.isNotBlank() } ?: beanName(cls.id)
                val bean = addBean(
                    name, cls.id, cls, ann.containsKey(PRIMARY), ann[QUALIFIER]?.get("value"), ann[PROFILE]?.get("value"),
                    conditional, stereotype?.substringAfterLast('.') ?: "Repository",
                )
                if (stereotype != null && (stereotype in CONFIGURATION_STEREOTYPES || cls.id in autoConfigured)) {
                    for (m in members(cls).filter { it.kind == NodeKind.METHOD }) {
                        val mann = annotationsOf(m)
                        val beanAnn = mann[BEAN] ?: continue
                        val type = m.signature?.substringAfterLast(": ")?.trim() ?: continue
                        val mname = beanAnn["name"] ?: beanAnn["value"] ?: m.id.substringAfter('#').substringBefore('(')
                        addBean(
                            mname.substringBefore(','), type, m, mann.containsKey(PRIMARY), mann[QUALIFIER]?.get("value"),
                            mann[PROFILE]?.get("value") ?: bean.profile, conditional || mann.keys.any { isCondition(it) }, "Bean",
                        )
                    }
                }
            }
        }

        private fun addBean(
            name: String, type: String, provider: Node, primary: Boolean, qualifier: String?, profile: String?,
            conditional: Boolean, stereotype: String,
        ): Bean {
            var id = "bean:$name"
            if (beans.any { it.id == id && it.type != type }) id = "bean:$name@$type"
            val bean = Bean(id, name, type, provider, primary, qualifier, profile, conditional)
            beans += bean
            store.node(
                Node(
                    id, NodeKind.BEAN, name, provider.origin, file = provider.file, startLine = provider.startLine,
                    module = provider.module, artifact = provider.artifact,
                    attrs = buildMap {
                        put("type", type); put("stereotype", stereotype); put("provider", provider.id)
                        if (primary) put("primary", "true")
                        qualifier?.let { put("qualifier", it) }
                        profile?.let { put("profile", it) }
                        if (conditional) put("conditional", "true")
                    },
                )
            )
            store.edge(Edge(provider.id, id, EdgeKind.PROVIDES_BEAN, if (conditional) Resolution.HEURISTIC else Resolution.SPRING))
            return bean
        }

        /** Classes listed in Boot's auto-configuration files inside internal jars. */
        private fun autoConfiguredClasses(): Set<String> {
            val out = HashSet<String>()
            for (artifact in store.nodes(NodeKind.ARTIFACT)) {
                if (artifact.attrs["classification"] != "internal") continue
                val jar = artifact.file?.let(Path::of)?.takeIf { it.toString().endsWith(".jar") && java.nio.file.Files.isRegularFile(it) } ?: continue
                JarFile(jar.toFile()).use { jf ->
                    jf.getJarEntry(AUTOCONFIG_IMPORTS)?.let { e ->
                        jf.getInputStream(e).bufferedReader().readLines().map { it.substringBefore('#').trim() }
                            .filter { it.isNotEmpty() }.forEach { out += it }
                    }
                    jf.getJarEntry("META-INF/spring.factories")?.let { e ->
                        val props = java.util.Properties().apply { load(jf.getInputStream(e)) }
                        props.getProperty(ENABLE_AUTOCONFIG)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { out += it }
                    }
                }
            }
            return out
        }

        private fun injections() {
            for (bean in beans) {
                val cls = bean.provider.takeIf { it.kind in CLASS_KINDS } ?: continue
                val members = members(cls)
                val ctors = members.filter { it.kind == NodeKind.CONSTRUCTOR }
                val ctor = ctors.singleOrNull() ?: ctors.firstOrNull { annotationsOf(it).keys.any { a -> a in INJECT } }
                ctor?.let { c -> for (p in Attrs.params(c)) if (p.annotations.keys.none { it == VALUE }) inject(cls, bean, p.type, qualifierOf(p.annotations)) }
                for (m in members) {
                    val ann = annotationsOf(m)
                    if (ann.keys.none { it in INJECT }) continue
                    when (m.kind) {
                        NodeKind.FIELD -> inject(cls, bean, m.signature?.substringAfterLast(": ") ?: continue, qualifierOf(ann))
                        NodeKind.METHOD -> for (p in Attrs.params(m)) inject(cls, bean, p.type, qualifierOf(p.annotations) ?: qualifierOf(ann))
                        else -> {}
                    }
                }
            }
        }

        private fun qualifierOf(ann: Map<String, Map<String, String>>): String? =
            ann[QUALIFIER]?.get("value") ?: ann[RESOURCE]?.get("name")

        private fun inject(cls: Node, bean: Bean, declaredType: String, qualifier: String?) {
            var type = declaredType.trim()
            var multiple = false
            for (wrapper in WRAPPERS) if (type.startsWith("$wrapper<")) {
                multiple = wrapper != "java.util.Optional" && wrapper != "org.springframework.beans.factory.ObjectProvider"
                type = type.substringAfter('<').substringBeforeLast('>')
                if (wrapper == "java.util.Map") type = type.substringAfter(',').trim()
            }
            type = type.substringBefore('<').trim()
            if (qualifier != null) {
                val named = beans.filter { it.name == qualifier || it.qualifier == qualifier }
                if (named.isNotEmpty()) {
                    named.forEach { link(cls, bean, it, if (it.conditional) Resolution.HEURISTIC else Resolution.SPRING, 1.0) }
                    return
                }
            }
            var candidates = beans.filter { it !== bean && (it.type == type || isSubtype(it.type, type)) }
            if (candidates.isEmpty()) return // framework-provided or outside the indexed universe
            if (candidates.any { it.profile == null }) candidates = candidates.filter { it.profile == null }
            when {
                multiple -> candidates.forEach { link(cls, bean, it, Resolution.SPRING, 1.0) }
                candidates.size == 1 -> link(cls, bean, candidates[0], if (candidates[0].conditional) Resolution.HEURISTIC else Resolution.SPRING, 1.0)
                candidates.any { it.primary } -> candidates.filter { it.primary }.forEach { link(cls, bean, it, Resolution.SPRING, 1.0) }
                else -> candidates.forEach { link(cls, bean, it, Resolution.HEURISTIC, 1.0 / candidates.size) }
            }
        }

        private fun link(cls: Node, from: Bean, to: Bean, resolution: Resolution, confidence: Double) {
            store.edge(Edge(cls.id, to.id, EdgeKind.INJECTS, resolution, confidence))
        }

        // ---- routes ----------------------------------------------------------------------------

        private fun routes() {
            for (cls in classes.values) {
                val ann = annotationsOf(cls)
                val feign = ann[FEIGN_CLIENT]
                if (!ann.keys.any { isController(it) } && feign == null) continue
                // Spring honours mappings declared on an implemented interface or superclass (OpenAPI-generated
                // `OwnersApi` interfaces are the common case), so a method without one inherits its supertype's
                val supers = supertypesOf(cls.id)
                val prefixes = (ann[REQUEST_MAPPING] ?: supers.firstNotNullOfOrNull { classes[it]?.let { c -> annotationsOf(c)[REQUEST_MAPPING] } })
                    ?.let { paths(it) } ?: listOf("")
                for (m in members(cls).filter { it.kind == NodeKind.METHOD }) {
                    val own = annotationsOf(m).filter { (a, v) -> verbs(a, v) != null }
                    val mappings = own.ifEmpty {
                        supers.firstNotNullOfOrNull { s -> store.node("$s#${m.id.substringAfter('#')}")?.let { annotationsOf(it).filter { (a, v) -> verbs(a, v) != null } }?.takeIf { it.isNotEmpty() } }
                            .orEmpty()
                    }
                    for ((annotation, values) in mappings) {
                        val verbs = verbs(annotation, values) ?: continue
                        for (prefix in prefixes) for (path in paths(values)) for (verb in verbs) {
                            val full = join(prefix, path)
                            if (feign != null) {
                                val base = (feign["url"]?.takeIf { it.isNotBlank() } ?: feign["name"] ?: feign["value"] ?: "feign")
                                remote(m.id, verb, resolve(base).trimEnd('/') + full, Resolution.EXACT)
                            } else {
                                val id = "route:$verb $full"
                                store.node(
                                    Node(
                                        id, NodeKind.HTTP_ROUTE, "$verb $full", m.origin, file = m.file, startLine = m.startLine,
                                        module = m.module, artifact = m.artifact,
                                        attrs = buildMap {
                                            put("verb", verb); put("path", full); put("handler", m.id)
                                            values["consumes"]?.let { put("consumes", it) }
                                            values["produces"]?.let { put("produces", it) }
                                        },
                                    )
                                )
                                store.edge(Edge(m.id, id, EdgeKind.HANDLES_ROUTE, Resolution.SPRING))
                            }
                        }
                    }
                }
            }
            // WebFlux / WebMvc.fn router functions: verbs from the predicates called, paths from string constants
            for (m in store.nodes(NodeKind.METHOD)) {
                val predicates = store.edgesFrom(m.id, EdgeKind.CALLS).map { it.to }
                    .filter { it.contains(".function.server.RequestPredicates#") }
                    .map { it.substringAfter('#').substringBefore('(') }.filter { it.uppercase() == it }.toSet()
                if (predicates.isEmpty()) continue
                val verb = predicates.singleOrNull() ?: "ANY"
                for (path in Attrs.strings(m).filter { it.startsWith("/") }.toSet()) {
                    val id = "route:$verb $path"
                    store.node(Node(id, NodeKind.HTTP_ROUTE, "$verb $path", m.origin, file = m.file, startLine = m.startLine, module = m.module,
                        attrs = mapOf("verb" to verb, "path" to path, "handler" to m.id)))
                    store.edge(Edge(m.id, id, EdgeKind.HANDLES_ROUTE, Resolution.HEURISTIC))
                }
            }
        }

        // ---- access rules -----------------------------------------------------------------------

        /** The filter chain's rule for each route, as the route's signature: `hasRole(ADMIN)` on `POST /v1/user/approveRequest/{tempFk}`. */
        private fun access() {
            val rules = ArrayList<Pair<String, String>>()
            for (m in store.nodes(NodeKind.METHOD)) {
                if (m.origin != Origin.REPO || m.signature?.contains("SecurityFilterChain") != true) continue
                val text = runCatching { java.nio.file.Files.readAllLines(java.nio.file.Path.of(m.file!!)).subList((m.startLine ?: 1) - 1, m.endLine ?: 0).joinToString(" ") }.getOrNull() ?: continue
                rules += RouteAccess.rules(text)
            }
            if (rules.isEmpty()) return
            store.flush() // the routes are batched inserts until then, and a query would see none of them
            for (r in store.nodes(NodeKind.HTTP_ROUTE)) {
                val path = r.attrs["path"] ?: continue
                val rule = rules.firstOrNull { (pattern, _) -> RouteAccess.matches(pattern, path) }?.second ?: continue
                store.node(r.copy(signature = rule), replace = true)
            }
        }

        private fun verbs(annotation: String, values: Map<String, String>): List<String>? = when (annotation) {
            REQUEST_MAPPING -> values["method"]?.split(',')?.map { it.trim().substringAfterLast('.') }?.filter { it.isNotEmpty() }?.ifEmpty { null } ?: listOf("ANY")
            "$WEB_BIND.GetMapping" -> listOf("GET")
            "$WEB_BIND.PostMapping" -> listOf("POST")
            "$WEB_BIND.PutMapping" -> listOf("PUT")
            "$WEB_BIND.DeleteMapping" -> listOf("DELETE")
            "$WEB_BIND.PatchMapping" -> listOf("PATCH")
            else -> if (isMetaAnnotated(annotation, REQUEST_MAPPING)) listOf("ANY") else null
        }

        private fun paths(values: Map<String, String>): List<String> =
            (values["value"] ?: values["path"] ?: "").split(',').map { it.trim() }

        private fun join(prefix: String, path: String): String {
            val p = if (prefix.isEmpty() || prefix.startsWith("/")) prefix else "/$prefix"
            val s = if (path.isEmpty() || path.startsWith("/")) path else "/$path"
            return (p + s).replace("//", "/").ifEmpty { "/" }
        }

        private fun supertypesOf(cls: String): List<String> {
            val seen = LinkedHashSet<String>()
            val queue = ArrayDeque(listOf(cls))
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                for (e in store.edgesFrom(c, EdgeKind.EXTENDS) + store.edgesFrom(c, EdgeKind.IMPLEMENTS)) if (seen.add(e.to)) queue += e.to
            }
            return seen.toList()
        }

        // ---- JPA -------------------------------------------------------------------------------

        private fun jpa() {
            for (cls in classes.values) {
                val ann = annotationsOf(cls)
                val entity = ann.keys.firstOrNull { it.endsWith(".persistence.Entity") } ?: continue
                val table = ann.keys.firstOrNull { it.endsWith(".persistence.Table") }?.let { ann[it]?.get("name") }
                    ?: cls.id.substringAfterLast('.').substringAfterLast('$')
                store.node(cls.copy(attrs = cls.attrs + ("table" to table)), replace = true)
            }
            for (cls in classes.values) {
                if (cls.kind != NodeKind.INTERFACE || !isSpringDataRepository(cls.id)) continue
                val entity = entityOf(cls) ?: continue
                store.edge(Edge(cls.id, entity, EdgeKind.MAPS_TO_TABLE, Resolution.SPRING))
            }
        }

        private fun entityOf(repository: Node): String? {
            repository.attrs[Attrs.SUPERTYPES]?.split(';')?.forEach { st ->
                if (st.substringBefore('<').let { it.startsWith("org.springframework.data.") && it.endsWith("Repository") }) {
                    return st.substringAfter('<').substringBefore(',').substringBefore('>').trim()
                }
            }
            repository.attrs["genericSignature"]?.let { sig ->
                GENERIC_REPOSITORY.find(sig)?.let { return it.groupValues[1].replace('/', '.') }
            }
            return null
        }

        private fun isSpringDataRepository(cls: String, depth: Int = 0): Boolean {
            if (depth > 5) return false
            return (store.edgesFrom(cls, EdgeKind.EXTENDS) + store.edgesFrom(cls, EdgeKind.IMPLEMENTS)).any { e ->
                (e.to.startsWith("org.springframework.data.") && e.to.endsWith("Repository")) || isSpringDataRepository(e.to, depth + 1)
            }
        }

        // ---- messaging -------------------------------------------------------------------------

        private fun listeners() {
            for (m in store.nodes(NodeKind.METHOD)) {
                val ann = annotationsOf(m)
                for ((annotation, values) in ann) {
                    val (system, attr) = LISTENERS[annotation] ?: continue
                    val names = (values[attr] ?: values["value"] ?: continue).split(',').map { resolve(it.trim()) }.filter { it.isNotEmpty() }
                    (values[attr] ?: values["value"])?.let { bindConfig(m.id.substringBefore('#'), it) }
                    for (name in names) {
                        knownTopics += name
                        store.edge(Edge(m.id, topic(name, system, m), EdgeKind.CONSUMES_FROM, Resolution.SPRING))
                    }
                }
            }
        }

        private fun producers() {
            for (m in store.nodes(NodeKind.METHOD)) {
                val templates = store.edgesFrom(m.id, EdgeKind.CALLS).map { it.to }.mapNotNull { target ->
                    PRODUCERS.entries.firstOrNull { (prefix, _) -> target.startsWith(prefix) }?.value
                }
                if (templates.isEmpty()) continue
                val candidates = Attrs.strings(m).filter { TOPIC_NAME.matches(it) }.toSet()
                val chosen = candidates.filter { it in knownTopics }.ifEmpty { candidates }
                for (name in chosen) {
                    store.edge(Edge(m.id, topic(name, templates.first(), m), EdgeKind.PRODUCES_TO, Resolution.HEURISTIC, 1.0 / chosen.size))
                }
            }
        }

        private fun topic(name: String, system: String, at: Node): String {
            val id = "topic:$name"
            store.node(Node(id, NodeKind.MESSAGE_TOPIC, name, at.origin, module = at.module, attrs = mapOf("system" to system)))
            return id
        }

        // ---- remote calls ----------------------------------------------------------------------

        private fun remoteCalls() {
            for (m in store.nodes(NodeKind.METHOD)) {
                val http = store.edgesFrom(m.id, EdgeKind.CALLS).any { e -> HTTP_CLIENTS.any { e.to.startsWith(it) } }
                if (!http) continue
                val urls = Attrs.strings(m).filter { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("/") }.toSet()
                for (url in urls) remote(m.id, "ANY", url, Resolution.HEURISTIC)
            }
        }

        private fun remote(from: String, verb: String, url: String, resolution: Resolution) {
            val id = "remote:$url"
            store.node(Node(id, NodeKind.HTTP_ROUTE, url, Origin.EXTERNAL, attrs = mapOf("remote" to "true", "verb" to verb, "url" to url)))
            store.edge(Edge(from, id, EdgeKind.CALLS_REMOTE, resolution))
        }

        // ---- scheduled jobs and proxies --------------------------------------------------------

        private fun scheduled() {
            for (m in store.nodes(NodeKind.METHOD)) {
                val values = annotationsOf(m)[SCHEDULED] ?: continue
                val id = "job:${m.id}"
                store.node(Node(id, NodeKind.SCHEDULED_JOB, m.id, m.origin, file = m.file, startLine = m.startLine, module = m.module,
                    attrs = values.mapValues { resolve(it.value) } + ("handler" to m.id)))
                store.edge(Edge(id, m.id, EdgeKind.CALLS, Resolution.SPRING))
            }
        }

        private fun selfInvocations() {
            for (cls in classes.values) {
                val classProxies = annotationsOf(cls).keys.filter { it in PROXIED }
                val members = members(cls)
                val proxied = members.filter { it.kind == NodeKind.METHOD }.associate { m ->
                    val own = annotationsOf(m).keys.filter { it in PROXIED }
                    val effective = own.ifEmpty { if (m.signature?.startsWith("public ") == true) classProxies else emptyList() }
                    m.id to effective
                }.filterValues { it.isNotEmpty() }
                if (proxied.isEmpty()) continue
                for (m in members) {
                    for (call in store.edgesFrom(m.id, EdgeKind.CALLS)) {
                        val target = proxied[call.to] ?: continue
                        if (call.to == m.id) continue
                        val names = target.joinToString(", ") { "@" + it.substringAfterLast('.') }
                        finding(
                            m.id, "self-invocation", call.to, "warning",
                            "${m.id.substringAfter('#')} calls ${call.to.substringAfter('#')} on this; the $names proxy is bypassed",
                        )
                    }
                }
            }
        }

        private fun finding(subject: String, kind: String, key: String, severity: String, message: String) {
            val id = "finding:$kind:$subject->$key"
            val at = store.node(subject)
            store.node(Node(id, NodeKind.FINDING, message, at?.origin ?: Origin.REPO, file = at?.file, startLine = at?.startLine,
                module = at?.module, attrs = mapOf("kind" to kind, "severity" to severity, "subject" to subject, "key" to key)))
            store.edge(Edge(subject, id, EdgeKind.HAS_FINDING, Resolution.EXACT))
        }

        // ---- helpers ---------------------------------------------------------------------------

        private fun members(cls: Node): List<Node> = store.edgesFrom(cls.id, EdgeKind.CONTAINS).mapNotNull { store.node(it.to) }
            .filter { it.kind == NodeKind.METHOD || it.kind == NodeKind.CONSTRUCTOR || it.kind == NodeKind.FIELD }

        private fun annotationsOf(node: Node) = Attrs.annotations(node)

        private fun isMetaAnnotated(annotation: String, target: String, depth: Int = 0): Boolean {
            if (annotation == target) return true
            if (depth > 3) return false
            val node = classes[annotation]?.takeIf { it.kind == NodeKind.ANNOTATION } ?: return false
            return annotationsOf(node).keys.any { isMetaAnnotated(it, target, depth + 1) }
        }

        private fun isStereotype(annotation: String) = STEREOTYPES.any { isMetaAnnotated(annotation, it) }
        private fun isController(annotation: String) = CONTROLLERS.any { isMetaAnnotated(annotation, it) }
        private fun isCondition(annotation: String) =
            annotation.startsWith("org.springframework.boot.autoconfigure.condition.") || annotation == "org.springframework.context.annotation.Conditional"

        private fun descendantsOf(cls: String): Set<String> = descendants.getOrPut(cls) {
            val seen = LinkedHashSet<String>()
            val queue = ArrayDeque(listOf(cls))
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                for (e in store.edgesTo(c, EdgeKind.EXTENDS) + store.edgesTo(c, EdgeKind.IMPLEMENTS)) if (seen.add(e.from)) queue += e.from
            }
            seen
        }

        private fun isSubtype(type: String, of: String) = type in descendantsOf(of)

        private fun beanName(cls: String): String {
            val simple = cls.substringAfterLast('.').substringAfterLast('$')
            return if (simple.length > 1 && simple[0].isUpperCase() && simple[1].isUpperCase()) simple
            else simple.replaceFirstChar { it.lowercase() }
        }
    }

    private companion object {
        val CLASS_KINDS = setOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.ENUM, NodeKind.RECORD, NodeKind.ANNOTATION)
        const val STEREOTYPE = "org.springframework.stereotype"
        const val WEB_BIND = "org.springframework.web.bind.annotation"
        const val REQUEST_MAPPING = "$WEB_BIND.RequestMapping"
        const val BEAN = "org.springframework.context.annotation.Bean"
        const val PRIMARY = "org.springframework.context.annotation.Primary"
        const val PROFILE = "org.springframework.context.annotation.Profile"
        const val QUALIFIER = "org.springframework.beans.factory.annotation.Qualifier"
        const val VALUE = "org.springframework.beans.factory.annotation.Value"
        const val RESOURCE = "jakarta.annotation.Resource"
        const val SCHEDULED = "org.springframework.scheduling.annotation.Scheduled"
        const val FEIGN_CLIENT = "org.springframework.cloud.openfeign.FeignClient"
        const val CONFIGURATION_PROPERTIES = "org.springframework.boot.context.properties.ConfigurationProperties"
        const val AUTOCONFIG_IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
        const val ENABLE_AUTOCONFIG = "org.springframework.boot.autoconfigure.EnableAutoConfiguration"
        val CONFIGURATION_STEREOTYPES = setOf(
            "org.springframework.context.annotation.Configuration", "org.springframework.boot.autoconfigure.AutoConfiguration",
            "org.springframework.boot.SpringBootConfiguration",
        )
        val CONTROLLERS = setOf("$STEREOTYPE.Controller", "$WEB_BIND.RestController")
        // Spring's own annotations are stubs in the graph, so meta-annotation chains through them are spelled out here;
        // custom stereotypes in indexed code are followed through their ANNOTATED_WITH edges.
        val STEREOTYPES = setOf(
            "$STEREOTYPE.Component", "$STEREOTYPE.Service", "$STEREOTYPE.Repository", "$STEREOTYPE.Controller",
            "$WEB_BIND.RestController", "$WEB_BIND.ControllerAdvice", "$WEB_BIND.RestControllerAdvice",
            "org.springframework.boot.autoconfigure.SpringBootApplication", FEIGN_CLIENT,
        ) + CONFIGURATION_STEREOTYPES
        val INJECT = setOf("org.springframework.beans.factory.annotation.Autowired", "jakarta.inject.Inject", "javax.inject.Inject", RESOURCE)
        val WRAPPERS = listOf(
            "java.util.List", "java.util.Set", "java.util.Collection", "java.util.Map", "java.util.Optional",
            "org.springframework.beans.factory.ObjectProvider",
        )
        val LISTENERS = mapOf(
            "org.springframework.kafka.annotation.KafkaListener" to ("kafka" to "topics"),
            "org.springframework.jms.annotation.JmsListener" to ("jms" to "destination"),
            "org.springframework.amqp.rabbit.annotation.RabbitListener" to ("rabbit" to "queues"),
            "org.springframework.cloud.stream.annotation.StreamListener" to ("stream" to "value"),
        )
        val PRODUCERS = mapOf(
            "org.springframework.kafka.core.KafkaTemplate#" to "kafka",
            "org.springframework.jms.core.JmsTemplate#" to "jms",
            "org.springframework.amqp.rabbit.core.RabbitTemplate#" to "rabbit",
            "org.springframework.cloud.stream.function.StreamBridge#" to "stream",
        )
        val HTTP_CLIENTS = listOf(
            "org.springframework.web.client.RestTemplate#", "org.springframework.web.client.RestClient",
            "org.springframework.web.reactive.function.client.WebClient", "org.springframework.web.client.RestOperations#",
        )
        val PROXIED = setOf(
            "org.springframework.transaction.annotation.Transactional", "jakarta.transaction.Transactional", "javax.transaction.Transactional",
            "org.springframework.scheduling.annotation.Async", "org.springframework.cache.annotation.Cacheable",
            "org.springframework.cache.annotation.CacheEvict", "org.springframework.cache.annotation.CachePut",
            "org.springframework.cache.annotation.Caching", "org.springframework.retry.annotation.Retryable",
            "org.springframework.security.access.prepost.PreAuthorize", "org.springframework.security.access.annotation.Secured",
        )
        val PLACEHOLDER = Regex("\\$\\{([^}:]+)(:[^}]*)?}")
        val TOPIC_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{2,}$")
        val GENERIC_REPOSITORY = Regex("Repository<L([^;<]+);")
    }
}
