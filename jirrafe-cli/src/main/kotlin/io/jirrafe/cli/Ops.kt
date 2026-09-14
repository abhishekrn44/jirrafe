package io.jirrafe.cli

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** Recursive file watcher with a debounce; fires [onChange] with the changed paths of one burst. */
class Watcher(private val roots: List<Path>, private val debounceMillis: Long = 500, private val onChange: (Set<Path>) -> Unit) : AutoCloseable {
    private val service = FileSystems.getDefault().newWatchService()
    private val keys = HashMap<WatchKey, Path>()
    @Volatile private var running = true

    init {
        for (root in roots) if (root.isDirectory()) Files.walk(root).use { s -> s.filter { it.isDirectory() && !skip(it) }.forEach { register(it) } }
    }

    private fun skip(p: Path) = p.name.let { it == ".jirrafe" || it == "build" || it == "target" || it == ".gradle" || it == ".git" || it == "node_modules" }

    private fun register(dir: Path) {
        keys[dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE)] = dir
    }

    /** Blocks until [close]; runs [onChange] on the calling thread. */
    fun run() {
        while (running) {
            val first = service.poll(250, TimeUnit.MILLISECONDS) ?: continue
            val changed = HashSet<Path>()
            var key: WatchKey? = first
            val deadline = System.currentTimeMillis() + debounceMillis
            while (key != null) {
                val dir = keys[key]
                for (event in key.pollEvents()) {
                    val rel = event.context() as? Path ?: continue
                    val p = dir?.resolve(rel) ?: continue
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && p.isDirectory() && !skip(p)) runCatching { register(p) }
                    if (!p.isDirectory()) changed.add(p)
                }
                key.reset()
                val remaining = deadline - System.currentTimeMillis()
                key = if (remaining > 0) service.poll(remaining, TimeUnit.MILLISECONDS) else service.poll()
            }
            if (changed.isNotEmpty() && running) onChange(changed)
        }
    }

    override fun close() { running = false; service.close() }
}

/** `jirrafe pull`: fetch a CI-built graph (a `graph.db`, or a zip holding one) from a URL or path. */
object Pull {
    fun pull(from: String, out: Path, token: String? = null): List<Path> {
        Files.createDirectories(out)
        val path = runCatching { Path.of(from) }.getOrNull()?.takeIf { Files.isRegularFile(it) }
        val written = ArrayList<Path>()
        if (path != null) {
            if (path.name.endsWith(".zip")) Files.newInputStream(path).use { written += unzip(it, out) }
            else { Files.copy(path, out.resolve("graph.db"), StandardCopyOption.REPLACE_EXISTING); written.add(out.resolve("graph.db")) }
            return written
        }
        val request = HttpRequest.newBuilder(URI.create(from)).timeout(Duration.ofMinutes(10)).GET()
            .apply { if (token != null) header("Authorization", "Bearer $token") }.build()
        val response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() / 100 != 2) error("$from returned ${response.statusCode()}")
        val zip = from.substringBefore('?').endsWith(".zip") || response.headers().firstValue("content-type").orElse("").contains("zip")
        response.body().use { body ->
            if (zip) written += unzip(body, out)
            else { Files.copy(body, out.resolve("graph.db"), StandardCopyOption.REPLACE_EXISTING); written.add(out.resolve("graph.db")) }
        }
        listOf("graph.db-wal", "graph.db-shm").forEach { Files.deleteIfExists(out.resolve(it)) }
        return written
    }

    private val WANTED = setOf("graph.db", "graph.json", "GRAPH_REPORT.md", "graph.html", "manifest.json", "summaries.json")

    private fun unzip(input: InputStream, out: Path): List<Path> {
        val written = ArrayList<Path>()
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val name = e.name.substringAfterLast('/')
                if (!e.isDirectory && name in WANTED) {
                    val target = out.resolve(name)
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                    written.add(target)
                }
                e = zip.nextEntry
            }
        }
        return written
    }
}

/** Git helpers for `jirrafe diff`: a temporary worktree per ref, removed afterwards. */
object Git {
    fun worktree(repo: Path, ref: String, into: Path) {
        run(repo, "git", "worktree", "add", "--detach", into.toString(), ref)
    }

    fun removeWorktree(repo: Path, dir: Path) {
        runCatching { run(repo, "git", "worktree", "remove", "--force", dir.toString()) }
        runCatching { dir.toFile().deleteRecursively() }
    }

    fun run(dir: Path, vararg command: String): String {
        val p = ProcessBuilder(*command).directory(dir.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) error("${command.joinToString(" ")} failed: ${out.trim().take(500)}")
        return out
    }
}
