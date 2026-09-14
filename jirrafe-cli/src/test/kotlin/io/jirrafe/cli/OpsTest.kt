package io.jirrafe.cli

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpsTest {
    @Test
    fun `watcher reports a debounced burst of changes`() {
        val dir = Files.createTempDirectory("jirrafe-watch")
        val seen = ArrayList<Set<java.nio.file.Path>>()
        val latch = CountDownLatch(1)
        val watcher = Watcher(listOf(dir), debounceMillis = 300) { seen += it; latch.countDown() }
        val thread = Thread { watcher.run() }.apply { isDaemon = true; start() }
        Thread.sleep(300)
        Files.writeString(dir.resolve("A.java"), "class A {}")
        Files.writeString(dir.resolve("B.java"), "class B {}")
        assertTrue(latch.await(15, TimeUnit.SECONDS), "watcher fired")
        watcher.close()
        thread.join(2000)
        assertTrue(seen.first().any { it.fileName.toString() == "A.java" }, "$seen")
    }

    @Test
    fun `pull copies a graph from a path, a zip, and a url`() {
        val src = Files.createTempDirectory("jirrafe-src")
        val db = src.resolve("graph.db").also { Files.writeString(it, "not really sqlite") }
        val out = Files.createTempDirectory("jirrafe-out")
        assertEquals(listOf(out.resolve("graph.db")), Pull.pull(db.toString(), out))
        assertEquals("not really sqlite", Files.readString(out.resolve("graph.db")))

        val zip = src.resolve("graph.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry("build/.jirrafe/graph.db")); z.write("zipped".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("build/.jirrafe/GRAPH_REPORT.md")); z.write("# report".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("build/other.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        val written = Pull.pull(zip.toString(), out).map { it.fileName.toString() }.toSet()
        assertEquals(setOf("graph.db", "GRAPH_REPORT.md"), written)
        assertEquals("zipped", Files.readString(out.resolve("graph.db")))

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graph.db") { ex ->
            val body = "from http".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            Pull.pull("http://127.0.0.1:${server.address.port}/graph.db", out)
            assertEquals("from http", Files.readString(out.resolve("graph.db")))
        } finally { server.stop(0) }
    }
}
