package io.jirrafe.frameworks.spring

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** A key defined in an `application*.properties|yml` file. */
data class ConfigEntry(val key: String, val value: String, val file: Path, val line: Int, val profile: String?)

/**
 * Reads Spring config files into flat keys. Properties files are read line by line; YAML through a
 * minimal indentation walker.
 */
object ConfigFiles {
    private val NAMES = Regex("^(application|bootstrap)(-([A-Za-z0-9_]+))?\\.(properties|ya?ml)$")

    fun find(dirs: List<Path>): List<Path> = dirs.filter { Files.isDirectory(it) }
        .flatMap { dir -> Files.list(dir).use { it.filter { f -> NAMES.matches(f.name) }.toList() } }
        .sortedBy { it.name }

    fun read(file: Path): List<ConfigEntry> {
        val profile = NAMES.find(file.name)?.groupValues?.get(3)?.ifEmpty { null }
        val lines = Files.readAllLines(file)
        return if (file.name.endsWith(".properties")) properties(lines, file, profile) else yaml(lines, file, profile)
    }

    private fun properties(lines: List<String>, file: Path, profile: String?): List<ConfigEntry> {
        val out = ArrayList<ConfigEntry>()
        lines.forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEachIndexed
            val sep = line.indexOfFirst { it == '=' || it == ':' }
            if (sep <= 0) return@forEachIndexed
            out += ConfigEntry(line.substring(0, sep).trim(), line.substring(sep + 1).trim(), file, i + 1, profile)
        }
        return out
    }

    private fun yaml(lines: List<String>, file: Path, profile: String?): List<ConfigEntry> {
        val out = ArrayList<ConfigEntry>()
        val stack = ArrayList<Pair<Int, String>>() // indent -> key
        lines.forEachIndexed { i, raw ->
            val line = raw.substringBefore(" #").trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) return@forEachIndexed
            if (line.trim() == "---") {
                stack.clear()
                return@forEachIndexed
            }
            val indent = line.length - line.trimStart().length
            val body = line.trim()
            if (body.startsWith("- ")) return@forEachIndexed
            val colon = body.indexOf(':')
            if (colon <= 0) return@forEachIndexed
            val key = body.substring(0, colon).trim().trim('"', '\'')
            val value = body.substring(colon + 1).trim()
            while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeLast()
            val full = (stack.map { it.second } + key).joinToString(".")
            if (value.isEmpty() || value == "|" || value == ">") {
                stack += indent to key
            } else {
                out += ConfigEntry(full, value.trim('"', '\''), file, i + 1, profile)
            }
        }
        return out
    }
}
