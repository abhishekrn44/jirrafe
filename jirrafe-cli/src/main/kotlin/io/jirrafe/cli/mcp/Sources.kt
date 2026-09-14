package io.jirrafe.cli.mcp

import io.jirrafe.core.manifest.Manifest
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Origin
import io.jirrafe.core.query.SourceReader
import io.jirrafe.extract.decompile.Decompiler
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads repo files and extracted sources jars by line range, and decompiles classes from jars
 * lazily into `.jirrafe/decompiled/`. Decompiling is limited to internal artifacts unless
 * [allowPublic] (`--i-understand-licenses`) is set; `decompile = "never"` disables it.
 */
class Sources(
    private val workDir: Path,
    private val manifest: Manifest?,
    private val allowPublic: Boolean = false,
    private val decompile: Boolean = true,
) : SourceReader {
    private val decompiler by lazy { Decompiler(workDir.resolve("decompiled")) }
    private val internal: Set<String> by lazy { manifest?.modules.orEmpty().flatMap { m -> m.configurations.flatMap { it.artifacts } }.filter { it.classification == "internal" }.map { it.coordinate }.toSet() }
    private val libraries: List<Path> by lazy { manifest?.modules.orEmpty().flatMap { m -> m.configurations.flatMap { it.artifacts } }.mapNotNull { it.file?.let(Path::of) }.distinct().filter { Files.isRegularFile(it) } }

    override fun read(node: Node, contextLines: Int): SourceReader.Source? {
        if (node.origin == Origin.EXTERNAL) return null
        val file = node.file ?: return null
        val path = Path.of(file)
        if (file.endsWith(".jar")) return decompiled(node, path, contextLines)
        if (!Files.isRegularFile(path) || file.endsWith(".class")) return null
        val lines = Files.readAllLines(path)
        val start = node.startLine ?: 1
        val end = node.endLine ?: if (node.startLine == null) minOf(lines.size, MAX_LINES) else start
        return slice(file, lines, start, end, contextLines, decompiled = false)
    }

    private fun decompiled(node: Node, jar: Path, contextLines: Int): SourceReader.Source? {
        if (!decompile) return null
        if (!allowPublic && node.artifact !in internal) return null
        val binaryName = node.id.substringBefore('#').substringBefore('$')
        val out = decompiler.decompile(jar, binaryName, libraries) ?: return null
        val lines = Files.readAllLines(out)
        val member = node.id.substringAfter('#', "").substringBefore('(')
        val hit = if (member.isEmpty() || node.kind !in setOf(NodeKind.METHOD, NodeKind.CONSTRUCTOR, NodeKind.FIELD)) -1
        else lines.indexOfFirst { l -> (" $member(" in l || " $member;" in l || " $member =" in l) && !l.trimStart().startsWith("//") }
        return if (hit < 0) slice(out.toString(), lines, 1, minOf(lines.size, MAX_LINES), 0, decompiled = true)
        else slice(out.toString(), lines, hit + 1, minOf(lines.size, hit + 1 + MEMBER_WINDOW), contextLines, decompiled = true)
    }

    private fun slice(file: String, lines: List<String>, start: Int, end: Int, context: Int, decompiled: Boolean): SourceReader.Source {
        val from = (start - context).coerceIn(1, maxOf(1, lines.size))
        val to = (end + context).coerceIn(from, lines.size)
        return SourceReader.Source(file, from, lines.subList(from - 1, to).joinToString("\n"), decompiled)
    }

    private companion object {
        const val MAX_LINES = 400
        const val MEMBER_WINDOW = 60
    }
}
