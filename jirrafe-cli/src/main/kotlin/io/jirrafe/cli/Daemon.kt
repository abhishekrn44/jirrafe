package io.jirrafe.cli

import io.jirrafe.cli.mcp.Sources
import io.jirrafe.core.config.Config
import io.jirrafe.core.manifest.Manifest
import io.jirrafe.core.query.Memory
import io.jirrafe.core.query.Queries
import io.jirrafe.core.query.Render
import io.jirrafe.core.store.GraphStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import kotlin.io.path.exists

/**
 * A query answered from a fresh JVM pays two seconds of start-up before the graph is even open; an agent
 * asks two or three times per question. The first `jirrafe query` in a project answers in-process and leaves
 * a daemon behind holding the graph open; every later query is a socket round-trip on localhost. The daemon
 * reopens the graph when `graph.db` changes and exits after an idle hour. Nothing else changes: same answers,
 * same text, same command line.
 *
 * Wire format: one line of arguments as JSON `{"tool":..,"texts":[..],..}` in, the answer text out, then close.
 * A token in `.jirrafe/daemon.token` must accompany every request; the port is in `.jirrafe/daemon.port`.
 */
object Daemon {
    private const val IDLE_MS = 60L * 60 * 1000

    /** The answer for one query, from whatever process holds the graph. */
    class Request(val tool: String, val texts: List<String>, val budget: Int?, val source: Boolean, val depth: Int?, val diff: Boolean, val lines: IntRange?, val format: String, val licenses: Boolean)

    class Answer(val text: String, val error: String? = null)

    fun answer(root: Path, out: Path, store: GraphStore, r: Request): Answer {
        val config = Config.load(root)
        val manifest = out.resolve("manifest.json").takeIf { it.exists() }?.let { Manifest.read(it) }
        val b = r.budget ?: config.int("serve.default_token_budget", Queries.DEFAULT_BUDGET)
        val sources = Sources(out, manifest, allowPublic = r.licenses, decompile = config.string("deps.decompile", "internal-only") != "never")
        val q = Queries(store, store.meta("root") ?: root.toString(), manifest, sources, Memory(out.resolve("queries.jsonl")))
        val text = r.texts.firstOrNull()
        val needs = setOf("explain", "search", "node", "source", "impact", "flow", "neighbors")
        if (text == null && r.tool in needs && !(r.tool == "impact" && r.diff)) return Answer("", "`${r.tool}` needs an argument")
        fun need(): String = text!!
        val result: JsonObject = when (r.tool) {
            "explain" -> q.explain(need(), b)
            "search" -> q.search(need(), budget = b)
            "node" -> q.getNode(need(), r.source, b)
            "source" -> if (r.texts.size > 1) q.readSources(r.texts, b) else q.readSource(need(), 0, r.lines).let { res -> // the largest live token sink had no budget at all
                val t = res["text"]?.let { it as? JsonPrimitive }?.content
                if (t == null || t.length <= b * 4) res
                else JsonObject(res + mapOf("text" to JsonPrimitive(t.take(b * 4).substringBeforeLast('\n')), "truncated" to JsonPrimitive(true)))
            }
            "impact" -> if (r.diff) q.impactOfChanges(r.depth ?: 3, b) else q.impact(need(), r.depth ?: 3, b)
            "flow" -> q.flow(need(), b)
            "neighbors" -> q.neighbors(need(), "both", null, r.depth ?: 1, 0.0, b)
            "routes" -> q.routes(text, b)
            "topics" -> q.topics(b)
            "beans" -> q.beans(text, b)
            "config" -> q.config(text, b)
            "findings" -> q.findings(text, null, null, b)
            "communities" -> q.communities(text, b)
            "dependencies" -> q.dependencies(text, b)
            "overview" -> q.overview(b)
            else -> return Answer("", "unknown query `${r.tool}`")
        }
        // the answer an agent reads is code with line numbers, not code inside JSON strings; JSON on request
        val rendered = when {
            r.format == "json" -> null
            r.tool == "explain" -> Render.explain(result)
            r.tool == "source" -> Render.sources(result)
            else -> null
        }
        return Answer(rendered ?: Queries.json.encodeToString(JsonObject.serializer(), result))
    }

    // ---- client side ------------------------------------------------------------------------------

    /** The daemon's answer if one is listening for this project, else null (and the caller answers in-process). */
    fun ask(out: Path, r: Request): Answer? {
        val port = out.resolve("daemon.port").takeIf { it.exists() }?.let { Files.readString(it).trim().toIntOrNull() } ?: return null
        val token = out.resolve("daemon.token").takeIf { it.exists() }?.let { Files.readString(it).trim() } ?: return null
        return try {
            Socket().use { s ->
                s.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300)
                s.soTimeout = 60_000
                val w = PrintWriter(s.getOutputStream().writer(Charsets.UTF_8), true)
                w.println(token)
                w.println(encode(r))
                s.shutdownOutput()
                val body = s.getInputStream().reader(Charsets.UTF_8).readText()
                if (body.startsWith("ERROR ")) Answer("", body.removePrefix("ERROR ").trim()) else Answer(body.removeSuffix("\n"))
            }
        } catch (e: Exception) {
            null // no daemon, a stale port file, or a daemon mid-restart: answer in-process
        }
    }

    /** Leaves a daemon behind for the next query; silent if one is already up or the launcher cannot be found. */
    fun spawn(root: Path, launcher: List<String>?) {
        val out = root.resolve(".jirrafe")
        if (out.resolve("daemon.port").exists() && ask(out, Request("overview", emptyList(), 200, false, null, false, null, "json", false)) != null) return
        val cmd = launcher ?: return
        try {
            // argv straight to the process: a .bat launches through the shell on its own, and the arguments are quoted right
            val pb = ProcessBuilder(cmd + listOf("daemon", "--dir", root.toString()))
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out.resolve("daemon.log").toFile()))
            pb.redirectInput(ProcessBuilder.Redirect.from(java.io.File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")))
            pb.start()
        } catch (e: Exception) {
            runCatching { Files.writeString(out.resolve("daemon.log"), "could not start the daemon with $cmd: $e" + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND) }
        }
    }

    // ---- server side ------------------------------------------------------------------------------

    /** Holds the graph open and answers on localhost until idle for an hour or the graph file is replaced by a rebuild. */
    fun serve(root: Path) {
        val out = root.resolve(".jirrafe")
        val db = out.resolve("graph.db")
        if (!db.exists()) return
        val token = java.util.UUID.randomUUID().toString()
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        Files.writeString(out.resolve("daemon.token"), token)
        Files.writeString(out.resolve("daemon.port"), server.localPort.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        var store = GraphStore.open(db)
        var stamp = Files.getLastModifiedTime(db).toMillis()
        val pool = Executors.newFixedThreadPool(4)
        var last = System.currentTimeMillis()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { Files.deleteIfExists(out.resolve("daemon.port")); Files.deleteIfExists(out.resolve("daemon.token")) } })
        server.soTimeout = 5_000
        while (true) {
            val client = try { server.accept() } catch (e: java.net.SocketTimeoutException) {
                if (System.currentTimeMillis() - last > IDLE_MS) break
                continue
            }
            last = System.currentTimeMillis()
            // a rebuild replaced the file: reopen, so no answer ever comes from a graph that is gone
            val now = runCatching { Files.getLastModifiedTime(db).toMillis() }.getOrDefault(0L)
            if (now != stamp) { runCatching { store.close() }; store = GraphStore.open(db); stamp = now }
            val current = store
            pool.submit { handle(client, root, out, current, token) }
        }
        runCatching { store.close() }
        pool.shutdown()
        server.close()
    }

    private fun handle(client: Socket, root: Path, out: Path, store: GraphStore, token: String) = client.use { s ->
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val w = PrintWriter(s.getOutputStream().writer(Charsets.UTF_8), true)
        val presented = reader.readLine()
        if (presented != token) { w.print("ERROR wrong token"); w.flush(); return@use }
        val r = try { decode(reader.readLine() ?: "") } catch (e: Exception) { w.print("ERROR bad request"); w.flush(); return@use }
        val a = try { answer(root, out, store, r) } catch (e: Exception) { Answer("", e.message ?: e.toString()) }
        if (a.error != null) w.print("ERROR " + a.error) else w.print(a.text)
        w.flush()
    }

    // ---- wire ---------------------------------------------------------------------------------------

    private fun encode(r: Request): String = Queries.json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
        "tool" to JsonPrimitive(r.tool), "texts" to kotlinx.serialization.json.JsonArray(r.texts.map { JsonPrimitive(it) }),
        "budget" to JsonPrimitive(r.budget ?: -1), "source" to JsonPrimitive(r.source), "depth" to JsonPrimitive(r.depth ?: -1),
        "diff" to JsonPrimitive(r.diff), "lines" to JsonPrimitive(r.lines?.let { "${it.first}-${it.last}" } ?: ""), "format" to JsonPrimitive(r.format), "licenses" to JsonPrimitive(r.licenses),
    )))

    private fun decode(line: String): Request {
        val o = Queries.json.parseToJsonElement(line) as JsonObject
        fun str(k: String) = (o[k] as JsonPrimitive).content
        fun int(k: String) = str(k).toInt().takeIf { it >= 0 }
        return Request(str("tool"), (o["texts"] as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content }, int("budget"), str("source").toBoolean(), int("depth"), str("diff").toBoolean(),
            str("lines").takeIf { it.isNotEmpty() }?.let { it.substringBefore('-').toInt()..it.substringAfter('-').toInt() }, str("format"), str("licenses").toBoolean())
    }
}
