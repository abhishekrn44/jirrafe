package io.jirrafe.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import io.jirrafe.cli.mcp.McpServer
import io.jirrafe.cli.mcp.Sources
import io.jirrafe.core.bench.Benchmark
import io.jirrafe.core.config.Config
import io.jirrafe.core.diff.Diff
import io.jirrafe.core.llm.HttpSummarizer
import io.jirrafe.core.llm.Summaries
import io.jirrafe.core.knowledge.Html
import io.jirrafe.core.knowledge.Report
import io.jirrafe.core.manifest.Manifest
import io.jirrafe.core.plugin.FrameworkPlugin
import io.jirrafe.core.query.Memory
import io.jirrafe.core.query.Queries
import io.jirrafe.core.store.GraphStore
import io.jirrafe.extract.Indexer
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.io.path.exists
import kotlin.io.path.name

class Jirrafe : CoreCliktCommand(name = "jirrafe") {
    init {
        context { // clikt-core neither exits with the error's status nor writes errors to stderr unless told to
            exitProcess = { kotlin.system.exitProcess(it) }
            echoMessage = { _, message, newline, err -> val w = if (err) System.err else System.out; if (newline) w.println(message) else w.print(message) }
        }
    }
    override fun run() {}
}

/** The steps behind the commands, so `build` can chain them. */
object Pipeline {
    fun version(): String = McpServer.version().takeIf { it != "dev" } ?: "0.1.0-SNAPSHOT"

    fun buildTool(dir: Path): String? = when {
        listOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle").any { dir.resolve(it).exists() } -> "gradle"
        dir.resolve("pom.xml").exists() -> "maven"
        else -> null
    }

    /** Runs the build plugin so it writes `.jirrafe/manifest.json`. */
    fun resolve(root: Path, pluginJar: Path?, echo: (String) -> Unit) {
        val dir = root.toAbsolutePath().normalize()
        val out = dir.resolve(".jirrafe").also { Files.createDirectories(it) }
        val windows = System.getProperty("os.name").startsWith("Windows")
        val command = when (buildTool(dir)) {
            "gradle" -> {
                val jar = pluginJar ?: bundledPluginJar(out) ?: throw CliktError("Gradle plugin jar not found; pass --plugin-jar or use the distribution")
                val init = out.resolve("init.gradle")
                Files.writeString(init, "initscript { dependencies { classpath files('${jar.toAbsolutePath().toString().replace('\\', '/')}') } }\n" +
                    "rootProject { apply plugin: io.jirrafe.gradle.JirrafePlugin }\n")
                val wrapper = dir.resolve(if (windows) "gradlew.bat" else "gradlew")
                listOf(if (wrapper.exists()) wrapper.toString() else "gradle", "-q", "jirrafeResolve", "--init-script", init.toString())
            }
            "maven" -> {
                val wrapper = dir.resolve(if (windows) "mvnw.cmd" else "mvnw")
                listOf(if (wrapper.exists()) wrapper.toString() else mavenFallback(windows) ?: "mvn", "-q", "-B", "compile", "io.jirrafe:jirrafe-maven-plugin:${version()}:resolve")
            }
            else -> throw CliktError("no Gradle or Maven build found in $dir")
        }
        fun run(cmd: List<String>): Int {
            echo("resolve: ${cmd.joinToString(" ")}")
            val process = ProcessBuilder(cmd).directory(dir.toFile()).inheritIO()
            process.environment().putIfAbsent("JAVA_HOME", System.getProperty("java.home")) // mvnw refuses to start without it
            return try { process.start().waitFor() } catch (e: java.io.IOException) {
                throw CliktError("cannot run '${cmd[0]}' (${e.message}); add a Maven or Gradle wrapper to the project or put mvn/gradle on PATH")
            }
        }
        var code = run(command)
        // The project's Maven wrapper is the project's, not ours: mvnw.cmd 3.3 cannot start from a home path with a
        // space, and a wrapper pinned to Maven 3.5 cannot run a plugin that needs 3.9. When it fails, the same
        // command runs again on a Maven this machine already has: on PATH, or one an earlier wrapper downloaded.
        if (code != 0 && command[0].endsWith(if (windows) "mvnw.cmd" else "mvnw")) mavenFallback(windows)?.let { mvn ->
            echo("the wrapper failed (exit $code); retrying with $mvn")
            code = run(listOf(mvn) + command.drop(1))
        }
        if (code != 0) throw CliktError("resolve failed with exit code $code")
        if (!out.resolve("manifest.json").exists()) throw CliktError("resolve produced no manifest at ${out.resolve("manifest.json")}")
        val patterns = Config.load(dir).list("deps.internal_jar_patterns")
        if (patterns.isNotEmpty()) {
            val manifest = out.resolve("manifest.json")
            Manifest.write(Manifest.reclassify(Manifest.read(manifest), patterns), manifest)
        }
    }

    /** `mvn` on PATH, else the newest Maven a wrapper has already downloaded under `~/.m2/wrapper/dists`. */
    fun mavenFallback(windows: Boolean): String? {
        val exe = if (windows) "mvn.cmd" else "mvn"
        System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).map { Path.of(it.ifBlank { "." }).resolve(exe) }.firstOrNull { it.exists() }?.let { return it.toString() }
        val dists = Path.of(System.getProperty("user.home"), ".m2", "wrapper", "dists").takeIf { Files.isDirectory(it) } ?: return null
        val version = { p: Path -> Regex("""\d+(\.\d+)+""").find(p.fileName.toString())?.value?.split('.')?.map { it.toInt() } ?: emptyList() }
        return Files.walk(dists, 4).use { s -> s.filter { it.fileName.toString() == exe && it.parent.fileName.toString() == "bin" }.toList() }
            .maxWithOrNull(compareBy<Path>({ version(it.parent.parent.parent).getOrElse(0) { 0 } }, { version(it.parent.parent.parent).getOrElse(1) { 0 } }, { version(it.parent.parent.parent).getOrElse(2) { 0 } }))
            ?.toString()
    }

    /** `<distribution>/plugins/jirrafe-gradle-*.jar` next to this jar, or the copy inside the fat jar, extracted under `.jirrafe/`. */
    fun bundledPluginJar(out: Path): Path? {
        val self = runCatching { Path.of(Pipeline::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
        val plugins = self?.parent?.parent?.resolve("plugins")?.takeIf { Files.isDirectory(it) }
        if (plugins != null) Files.list(plugins).use { s -> s.filter { it.name.startsWith("jirrafe-gradle") && it.name.endsWith(".jar") }.findFirst().orElse(null) }?.let { return it }
        val embedded = Pipeline::class.java.getResourceAsStream("/plugins/jirrafe-gradle.jar") ?: return null
        val target = out.resolve("jirrafe-gradle.jar")
        embedded.use { Files.copy(it, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        return target
    }

    fun index(dir: Path, full: Boolean, echo: (String) -> Unit): Indexer.Stats {
        val out = dir.resolve(".jirrafe")
        val manifest = Manifest.read(out.resolve("manifest.json"))
        if (full) {
            listOf("graph.db", "graph.db-wal", "graph.db-shm").forEach { Files.deleteIfExists(out.resolve(it)) }
            listOf("generated", "sources").forEach { out.resolve(it).toFile().deleteRecursively() }
        }
        return GraphStore.open(out.resolve("graph.db")).use { store ->
            val plugins = ServiceLoader.load(FrameworkPlugin::class.java).toList()
            val stats = Indexer(store, out, plugins, echo).index(manifest)
            store.exportJson(out.resolve("graph.json"))
            stats
        }
    }

    /** Knowledge layer, then LLM summaries when a provider is configured (or [provider] overrides it). */
    fun knowledge(dir: Path, echo: (String) -> Unit, provider: String? = null, dryRun: Boolean = false, licenses: Boolean = false): String {
        val root = dir.toAbsolutePath().normalize()
        val out = root.resolve(".jirrafe")
        val manifest = out.resolve("manifest.json").takeIf { it.exists() }?.let { Manifest.read(it) }
        val config = Config.load(root)
        return GraphStore.open(out.resolve("graph.db")).use { store ->
            val result = io.jirrafe.core.knowledge.Knowledge(store, manifest, root, echo).build()
            val summarizer = if (provider != null && provider != "none") HttpSummarizer(provider, config.string("knowledge.model", ""), config.string("knowledge.endpoint", "").ifEmpty { null })
            else if (provider == null) HttpSummarizer.fromConfig(config) else null
            var summaryLine = ""
            if (summarizer != null) {
                val sendCode = config.bool("knowledge.send_code_snippets", false)
                val sources = Sources(out, manifest, allowPublic = licenses, decompile = config.string("deps.decompile", "internal-only") != "never")
                val stats = Summaries(
                    store, out.resolve("summaries.json"), summarizer, config.int("knowledge.max_tokens_per_summary", 300), sendCode, sources,
                    cacheKeyPrefix = "${summarizer.provider}/${summarizer.model}/", log = echo,
                ).run(dryRun)
                summaryLine = if (dryRun) "; dry run: ${stats.prompts} prompts would go to ${summarizer.provider} (${stats.cached} already cached)"
                else "; summaries: ${stats.calls} from ${summarizer.provider}, ${stats.cached} cached, ${stats.failed} failed"
            }
            Files.writeString(out.resolve("GRAPH_REPORT.md"), Report.markdown(store, result))
            Files.writeString(out.resolve("graph.html"), Html.render(result))
            store.exportJson(out.resolve("graph.json"))
            result.summary() + summaryLine
        }
    }
}

private fun CoreCliktCommand.dirOption() = option("--dir", help = "project root (default: current directory)").path().default(Path.of("."))

/** Detects the build tool, writes `jirrafe.toml`, ignores `.jirrafe/`. */
class Init : CoreCliktCommand(name = "init") {
    override fun help(context: Context) = "Detects the build tool, writes `jirrafe.toml`, ignores `.jirrafe/`."
    private val dir: Path by dirOption()

    override fun run() {
        val tool = Pipeline.buildTool(dir) ?: throw CliktError("no Gradle or Maven build found in $dir")
        val group = detectGroup(dir, tool) ?: "com.example"
        val toml = dir.resolve(Config.FILE)
        if (toml.exists()) echo("$toml exists, left as is") else {
            Files.writeString(toml, Config.template(tool, group))
            echo("wrote $toml (build tool $tool, internal group $group)")
        }
        val ignore = dir.resolve(".gitignore")
        val lines = if (ignore.exists()) Files.readAllLines(ignore) else emptyList()
        if (lines.none { it.trim().trimEnd('/') == ".jirrafe" }) {
            Files.writeString(ignore, (if (lines.isEmpty()) "" else lines.joinToString("\n").trimEnd() + "\n") + ".jirrafe/\n")
            echo("added .jirrafe/ to $ignore")
        }
    }

    private fun detectGroup(dir: Path, tool: String): String? = when (tool) {
        "gradle" -> listOf("build.gradle.kts", "build.gradle").map { dir.resolve(it) }.firstOrNull { it.exists() }?.let { f ->
            Regex("""group\s*=\s*["']([^"']+)["']""").find(Files.readString(f))?.groupValues?.get(1)
        }
        else -> dir.resolve("pom.xml").takeIf { it.exists() }?.let { f ->
            Regex("""<groupId>([^<]+)</groupId>""").find(Files.readString(f))?.groupValues?.get(1)?.trim()
        }
    }
}

/** Runs the build plugin to write `.jirrafe/manifest.json`. */
class Resolve : CoreCliktCommand(name = "resolve") {
    override fun help(context: Context) = "Runs the build plugin to write `.jirrafe/manifest.json`."
    private val dir: Path by dirOption()
    private val pluginJar: Path? by option("--plugin-jar", help = "Gradle plugin jar (default: the one bundled with the distribution)").path()

    override fun run() = Pipeline.resolve(dir, pluginJar) { echo(it) }
}

/** Reads `.jirrafe/manifest.json` (from the build plugins) and updates `graph.db` and `graph.json`. */
class Index : CoreCliktCommand(name = "index") {
    override fun help(context: Context) = "Reads `.jirrafe/manifest.json` (from the build plugins) and updates `graph.db` and `graph.json`."
    private val dir: Path by dirOption()
    private val full: Boolean by option("--full", help = "discard the existing graph and rebuild everything").flag()

    override fun run() = echo(stats(Pipeline.index(dir, full) { echo(it) }, dir))

    companion object {
        fun stats(s: Indexer.Stats, dir: Path) =
            "indexed ${s.modules} modules and ${s.internalJars} internal jars (${s.skippedUnits} unchanged units skipped): " +
                "${s.classes} classes from bytecode, ${s.sourceFiles} source files (${s.sourceErrors} compile errors in the repo, ${s.jarSourceErrors} in sources jars) " +
                "-> ${s.nodes} nodes, ${s.edges} edges, ${s.dispatches} dispatch edges, ${s.stubs} external stubs in ${dir.resolve(".jirrafe/graph.db")}"
    }
}

/** Derives communities, layers, god nodes, flows and findings from `graph.db`; writes `GRAPH_REPORT.md`, `graph.html`, `graph.json`. */
class Knowledge : CoreCliktCommand(name = "knowledge") {
    override fun help(context: Context) = "Derives communities, layers, god nodes, flows and findings from `graph.db`; writes `GRAPH_REPORT.md`, `graph.html`, `graph.json`."
    private val dir: Path by dirOption()
    private val provider: String? by option("--provider", help = "LLM summaries: none | ${HttpSummarizer.PROVIDERS.joinToString(" | ")} (default from jirrafe.toml)")
    private val dryRun: Boolean by option("--dry-run", help = "print exactly what would be sent to the LLM provider and send nothing").flag()
    private val licenses: Boolean by option("--i-understand-licenses", help = "allow code snippets from public jars in prompts").flag()

    override fun run() = echo("knowledge: ${Pipeline.knowledge(dir, { echo(it) }, provider, dryRun, licenses)} -> ${dir.resolve(".jirrafe/GRAPH_REPORT.md")}")
}

/** Re-indexes on file changes; re-runs knowledge after N changed bursts or when `k` is typed. */
class Watch : CoreCliktCommand(name = "watch") {
    override fun help(context: Context) = "Re-indexes on file changes; re-runs knowledge after N changed bursts or when `k` is typed."
    private val dir: Path by dirOption()
    private val knowledgeAfter: Int by option("--knowledge-after", help = "re-run knowledge after this many change bursts (default 10)").int().default(10)

    override fun run() {
        val root = dir.toAbsolutePath().normalize()
        val manifest = Manifest.read(root.resolve(".jirrafe/manifest.json"))
        val roots = manifest.modules.flatMap { it.sourceDirs + it.testSourceDirs + it.resourceDirs }.map(Path::of).filter { it.exists() }
        var bursts = 0
        echo("watching ${roots.size} directories; type k + Enter to run knowledge, q + Enter to quit")
        val watcher = Watcher(roots) { changed ->
            echo("${changed.size} files changed (${changed.first().fileName}${if (changed.size > 1) ", ..." else ""})")
            runCatching { echo(Index.stats(Pipeline.index(root, false) { }, root)) }.onFailure { echo("index failed: ${it.message}") }
            if (++bursts >= knowledgeAfter) { bursts = 0; runCatching { echo("knowledge: " + Pipeline.knowledge(root, {})) }.onFailure { echo("knowledge failed: ${it.message}") } }
        }
        Thread {
            while (true) {
                val line = readlnOrNull() ?: break
                when (line.trim()) {
                    "k" -> runCatching { echo("knowledge: " + Pipeline.knowledge(root, {})) }.onFailure { echo("knowledge failed: ${it.message}") }
                    "q" -> { watcher.close(); break }
                }
            }
        }.apply { isDaemon = true }.start()
        watcher.run()
    }
}

/** Fetches a CI-built graph so nobody indexes hundreds of jars locally. */
class PullCommand : CoreCliktCommand(name = "pull") {
    override fun help(context: Context) = "Fetches a CI-built graph so nobody indexes hundreds of jars locally."
    private val dir: Path by dirOption()
    private val from: String by option("--from", help = "URL or path of graph.db, or a zip containing it (an artifact download)").required()
    private val token: String? by option("--token", help = "bearer token for the download (default: GITHUB_TOKEN)")

    override fun run() {
        val written = Pull.pull(from, dir.resolve(".jirrafe"), token ?: System.getenv("GITHUB_TOKEN"))
        for (f in written) echo("wrote $f")
    }
}

/** Structural diff between two refs (each built in a temporary worktree) or two graph files. */
class DiffCommand : CoreCliktCommand(name = "diff") {
    override fun help(context: Context) = "Structural diff between two refs (each built in a temporary worktree) or two graph files."
    private val dir: Path by dirOption()
    private val base: String? by option("--base", help = "git ref of the base (built in a temporary worktree)")
    private val head: String? by option("--head", help = "git ref of the head (default: the current working tree's graph)")
    private val baseDb: Path? by option("--base-db", help = "compare an existing graph.db instead of building --base").path()
    private val headDb: Path? by option("--head-db", help = "compare an existing graph.db instead of building --head").path()
    private val output: Path? by option("--output", help = "write the Markdown here instead of stdout").path()
    private val pluginJar: Path? by option("--plugin-jar").path()

    override fun run() {
        val root = dir.toAbsolutePath().normalize()
        val temps = ArrayList<Path>()
        fun graphOf(ref: String?, db: Path?, name: String): Path {
            if (db != null) return db
            if (ref == null) return root.resolve(".jirrafe/graph.db").also { if (!it.exists()) throw CliktError("no graph at $it; run `jirrafe build` or pass --$name") }
            val wt = Files.createTempDirectory("jirrafe-$name-")
            Files.delete(wt)
            Git.worktree(root, ref, wt)
            temps.add(wt)
            System.err.println("building $name graph for $ref in $wt")
            Pipeline.resolve(wt, pluginJar) { System.err.println(it) }
            Pipeline.index(wt, true) { }
            Pipeline.knowledge(wt, {}, provider = "none")
            return wt.resolve(".jirrafe/graph.db")
        }
        try {
            val baseGraph = graphOf(base, baseDb, "base")
            val headGraph = graphOf(head, headDb, "head")
            val markdown = GraphStore.open(baseGraph).use { b -> GraphStore.open(headGraph).use { h -> Diff.markdown(Diff.compare(b, h), base ?: baseDb?.toString() ?: "base", head ?: headDb?.toString() ?: "working tree") } }
            if (output != null) { Files.writeString(output!!, markdown); echo("wrote $output") } else echo(markdown)
        } finally {
            temps.forEach { Git.removeWorktree(root, it) }
        }
    }
}

/** resolve, index, knowledge. */
class Build : CoreCliktCommand(name = "build") {
    override fun help(context: Context) = "resolve, index, knowledge."
    private val dir: Path by dirOption()
    private val full: Boolean by option("--full", help = "discard the existing graph and rebuild everything").flag()
    private val pluginJar: Path? by option("--plugin-jar", help = "Gradle plugin jar (default: bundled)").path()

    override fun run() {
        Pipeline.resolve(dir, pluginJar) { echo(it) }
        echo(Index.stats(Pipeline.index(dir, full) { echo(it) }, dir))
        echo("knowledge: ${Pipeline.knowledge(dir, { echo(it) })}")
    }
}

/** Serves `graph.db` over MCP. Starts as is; never rebuilds. */
class Serve : CoreCliktCommand(name = "serve") {
    override fun help(context: Context) = "Serves `graph.db` over MCP. Starts as is; never rebuilds."
    private val dir: Path by dirOption()
    private val transport: String? by option("--transport", help = "stdio | http (default from jirrafe.toml, else stdio)")
    private val port: Int by option("--port", help = "port for http (default 8765)").int().default(8765)
    private val budget: Int? by option("--token-budget", help = "default answer size in tokens").int()
    private val licenses: Boolean by option("--i-understand-licenses", help = "allow decompiling public (non-internal) jars for read_source").flag()

    override fun run() {
        // stdout is the MCP protocol stream; anything else that prints (kotlin-logging's banner on first use of
        // the SDK, a stray println in a plugin) would close the connection, so the real stream is captured first
        val protocolOut = System.out
        System.setOut(System.err)
        val root = dir.toAbsolutePath().normalize()
        val out = root.resolve(".jirrafe")
        val db = out.resolve("graph.db")
        if (!db.exists()) throw CliktError("no graph at $db; run `jirrafe build` first")
        val config = Config.load(root)
        val manifest = out.resolve("manifest.json").takeIf { it.exists() }?.let { Manifest.read(it) }
        val store = GraphStore.open(db)
        val sources = Sources(out, manifest, allowPublic = licenses, decompile = config.string("deps.decompile", "internal-only") != "never")
        val queries = Queries(store, store.meta("root") ?: root.toString(), manifest, sources, Memory(out.resolve("queries.jsonl"))) // the graph's own root, so an alias of it (8.3, symlink) still yields relative paths
        val server = McpServer(queries, store, out.resolve("GRAPH_REPORT.md"), budget ?: config.int("serve.default_token_budget", Queries.DEFAULT_BUDGET))
        when (val t = transport ?: config.string("serve.transport", "stdio")) {
            "stdio" -> runBlocking { server.serveStdio(protocolOut) }
            "http" -> {
                System.err.println("jirrafe MCP server on http://127.0.0.1:$port/mcp")
                server.serveHttp(port)
            }
            else -> throw CliktError("unknown transport '$t'")
        }
    }
}

/** Registers the MCP server with a coding assistant and writes its instructions file. */
class InstallCommand : CoreCliktCommand(name = "install") {
    override fun help(context: Context) = "Registers the MCP server with a coding assistant and writes its instructions file."
    private val dir: Path by dirOption()
    private val client: String by option("--client", help = Install.CLIENTS.joinToString(" | ")).required()
    private val command: String by option("--command", help = "how the client should start jirrafe (default: jirrafe on PATH)").default("jirrafe")

    override fun run() {
        for (f in Install.run(client, dir, command)) echo("wrote $f")
    }
}

/** Retrieval benchmark against a grep baseline; the numbers in the README come from here. */
/** The MCP tools from a shell, for skills and scripts: `jirrafe query explain "how are orders placed"`. */
class Query : CoreCliktCommand(name = "query") {
    override fun help(context: Context) = """
        |Ask the graph from the command line; prints the same JSON the MCP tools return.
        |
        |  jirrafe query explain "how is an order placed"     flows, communities and nodes with file:line
        |  jirrafe query search OrderService                   nodes by name, signature or Javadoc
        |  jirrafe query node <id> [--source]                  one node with edges (and its source)
        |  jirrafe query source <id> [<id>...] [--lines A-B]  the source of one node, or several in one call; --lines continues a cut body
        |  jirrafe query impact <id> [--depth N]               callers affected by a change, and their tests
        |  jirrafe query impact --diff                         the same for the working tree's changes, with the test command
        |  jirrafe query flow <id-or-route>                    one end-to-end flow
        |  jirrafe query neighbors <id> [--depth N]            direct edges
        |  jirrafe query routes|topics|beans|config|findings|communities|dependencies [filter]
        |  jirrafe query overview
        """.trimMargin()

    private val dir by dirOption()
    private val tool by argument(help = "explain | search | node | source | impact | flow | neighbors | routes | topics | beans | config | findings | communities | dependencies | overview")
    private val texts by argument(help = "question, id or filter; source takes several ids").multiple()
    private val text get() = texts.firstOrNull()
    private val lines by option("--lines", help = "source: file lines A-B of the node, to continue a body that was cut").convert { it.substringBefore('-').trim().toInt()..it.substringAfter('-').trim().toInt() }
    private val budget by option("--token-budget", help = "answer size in tokens (default from jirrafe.toml or 3000)").int()
    private val source by option("--source", help = "node: include the source text").flag()
    private val depth by option("--depth", help = "impact and neighbors depth").int()
    private val diff by option("--diff", help = "impact: the working tree's changes against HEAD instead of an id, ending in the test command").flag()
    private val licenses by option("--i-understand-licenses", help = "allow decompiling public jars").flag()
    private val format by option("--format", help = "explain and source: text (numbered code an agent cites from, the default) or json").default("text")

    private val noDaemon by option("--no-daemon", help = "answer in this process and leave no daemon behind").flag()

    override fun run() {
        val root = dir.toAbsolutePath().normalize()
        val out = root.resolve(".jirrafe")
        val db = out.resolve("graph.db")
        if (!db.exists()) throw CliktError("no graph at $db; run `jirrafe build` first")
        System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8")) // Javadoc is not cp1252
        val request = Daemon.Request(tool, texts, budget, source, depth, diff, lines, format, licenses)
        // a daemon holding the graph open answers in milliseconds; a fresh JVM takes two seconds before the graph is even open
        val answer = (if (noDaemon) null else Daemon.ask(out, request))
            ?: GraphStore.open(db).use { store -> Daemon.answer(root, out, store, request) }.also { if (!noDaemon) Daemon.spawn(root, launcher()) }
        if (answer.error != null) throw CliktError(answer.error)
        echo(answer.text)
    }

    /** The command that started this process, for the daemon to be started the same way: the launcher script, else `java -jar`. */
    private fun launcher(): List<String>? {
        System.getenv("JIRRAFE_LAUNCHER")?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
        val jar = runCatching { java.nio.file.Path.of(Query::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull() ?: return null
        val windows = System.getProperty("os.name").startsWith("Windows")
        // an installDist layout: lib/jirrafe-cli.jar beside bin/jirrafe(.bat); otherwise the fat jar with the running JVM
        val script = jar.parent?.takeIf { it.fileName.toString() == "lib" }?.parent?.resolve("bin")?.resolve(if (windows) "jirrafe.bat" else "jirrafe")
        if (script != null && java.nio.file.Files.exists(script)) return listOf(script.toString())
        val java = java.nio.file.Path.of(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java")
        return if (jar.toString().endsWith(".jar")) listOf(java.toString(), "-jar", jar.toString()) else null
    }
}

class DaemonCommand : CoreCliktCommand(name = "daemon") {
    override fun help(context: Context) = "Holds the graph open and answers `jirrafe query` over localhost; started by the first query, exits after an idle hour. Hidden."
    override val hiddenFromHelp = true
    private val dir by dirOption()
    override fun run() = Daemon.serve(dir.toAbsolutePath().normalize())
}

class Bench : CoreCliktCommand(name = "bench") {
    override fun help(context: Context) = "Runs the retrieval benchmark (questions with expected node ids) against a grep baseline and prints Markdown."
    private val dir: Path by dirOption()
    private val questions: Path by option("--questions", help = "JSON list of {question, expected: [node ids]}").path().required()
    private val output: Path? by option("--output", help = "write the Markdown here as well").path()
    private val budget: Int by option("--token-budget", help = "answer budget passed to explain and search (default 3000)").int().default(Queries.DEFAULT_BUDGET)

    override fun run() {
        val root = dir.toAbsolutePath().normalize()
        val manifest = Manifest.read(root.resolve(".jirrafe/manifest.json"))
        val sourceDirs = manifest.modules.flatMap { it.sourceDirs + it.resourceDirs }.map(Path::of)
        val md = GraphStore.open(root.resolve(".jirrafe/graph.db")).use { store ->
            val config = Config.load(root)
            val sources = Sources(root.resolve(".jirrafe"), manifest, allowPublic = false, decompile = config.string("deps.decompile", "internal-only") != "never")
            Benchmark.markdown("${root.fileName} (budget $budget)", Benchmark.run(store, root, sourceDirs, Benchmark.load(questions), budget, sources, manifest))
        }
        echo(md)
        output?.let { Files.writeString(it, md); echo("wrote $it") }
    }
}

fun main(args: Array<String>) = Jirrafe().subcommands(Init(), Resolve(), Index(), Knowledge(), Build(), Serve(), Query(), DaemonCommand(), InstallCommand(), Watch(), PullCommand(), DiffCommand(), Bench()).main(args)
