package io.jirrafe.extract.decompile

import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences
import org.jetbrains.java.decompiler.main.extern.IResultSaver
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.jar.Manifest

/**
 * Lazy Vineflower decompilation of one class (with its nested classes) from a jar, cached under
 * `<cacheDir>/<jar sha>/<package path>/<Class>.java`. Callers decide whether the jar is allowed.
 */
class Decompiler(private val cacheDir: Path) {
    private val jarShas = HashMap<Path, String>()

    /** Returns the decompiled file, or null if the class is not in the jar. [libraries] resolve overloads. */
    fun decompile(jar: Path, binaryName: String, libraries: List<Path> = emptyList()): Path? {
        val internal = binaryName.replace('.', '/')
        val out = cacheDir.resolve(jarSha(jar)).resolve("$internal.java")
        if (Files.exists(out)) return out

        val tmp = Files.createTempDirectory("jirrafe-decompile")
        try {
            var found = false
            JarFile(jar.toFile()).use { jf ->
                for (e in jf.entries()) {
                    val n = e.name
                    if (n != "$internal.class" && !(n.startsWith("$internal\$") && n.endsWith(".class"))) continue
                    val target = tmp.resolve(n)
                    Files.createDirectories(target.parent)
                    jf.getInputStream(e).use { Files.copy(it, target) }
                    found = true
                }
            }
            if (!found) return null

            val saver = object : IResultSaver {
                override fun saveClassFile(path: String, qualifiedName: String, entryName: String, content: String?, mapping: IntArray?) {
                    if (qualifiedName == internal && content != null) {
                        Files.createDirectories(out.parent)
                        Files.writeString(out, content)
                    }
                }
                override fun saveFolder(path: String) {}
                override fun copyFile(source: String, path: String, entryName: String) {}
                override fun createArchive(path: String, archiveName: String, manifest: Manifest?) {}
                override fun saveDirEntry(path: String, archiveName: String, entryName: String) {}
                override fun copyEntry(source: String, path: String, archiveName: String, entry: String) {}
                override fun saveClassEntry(path: String, archiveName: String, qualifiedName: String, entryName: String, content: String?) {}
                override fun closeArchive(path: String, archiveName: String) {}
            }
            val quiet = object : IFernflowerLogger() {
                override fun writeMessage(message: String, severity: Severity) {}
                override fun writeMessage(message: String, severity: Severity, t: Throwable?) {}
            }
            val options = mapOf<String, Any>(
                IFernflowerPreferences.INDENT_STRING to "    ",
                IFernflowerPreferences.BANNER to "// Decompiled by Vineflower from ${jar.fileName}. Not the original source.\n",
            )
            val decompiler = BaseDecompiler(saver, options, quiet)
            decompiler.addSource(tmp.toFile())
            decompiler.addLibrary(jar.toFile())
            libraries.forEach { decompiler.addLibrary(it.toFile()) }
            decompiler.decompileContext()
            return out.takeIf { Files.exists(it) }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private fun jarSha(jar: Path): String = jarShas.getOrPut(jar) {
        val md = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(jar).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }.substring(0, 16)
    }
}
