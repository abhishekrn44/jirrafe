package io.jirrafe.core.config

import java.nio.file.Files
import java.nio.file.Path

/**
 * `jirrafe.toml`, read with a deliberately small parser: `[section]` headers, `key = value` with
 * strings, numbers, booleans and flat string arrays. Comments start with `#`.
 */
class Config private constructor(private val values: Map<String, String>, private val lists: Map<String, List<String>>) {
    fun string(key: String, default: String): String = values[key] ?: default
    fun int(key: String, default: Int): Int = values[key]?.toIntOrNull() ?: default
    fun bool(key: String, default: Boolean): Boolean = values[key]?.toBooleanStrictOrNull() ?: default
    fun list(key: String): List<String> = lists[key] ?: values[key]?.let { listOf(it) } ?: emptyList()

    companion object {
        const val FILE = "jirrafe.toml"
        val EMPTY = Config(emptyMap(), emptyMap())

        fun load(root: Path): Config = root.resolve(FILE).takeIf { Files.exists(it) }?.let { parse(Files.readString(it)) } ?: EMPTY

        fun parse(text: String): Config {
            val values = HashMap<String, String>()
            val lists = HashMap<String, List<String>>()
            var section = ""
            for (raw in text.lines()) {
                val line = stripComment(raw).trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[") && line.endsWith("]")) { section = line.substring(1, line.length - 1).trim(); continue }
                val eq = line.indexOf('=')
                if (eq < 0) continue
                val key = (if (section.isEmpty()) "" else "$section.") + line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                if (value.startsWith("[")) {
                    lists[key] = value.removePrefix("[").removeSuffix("]").split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }
                } else values[key] = unquote(value)
            }
            return Config(values, lists)
        }

        private fun stripComment(line: String): String {
            var inString = false
            for ((i, c) in line.withIndex()) {
                if (c == '"') inString = !inString
                if (c == '#' && !inString) return line.substring(0, i)
            }
            return line
        }

        private fun unquote(v: String) = if (v.length >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) v.substring(1, v.length - 1) else v

        /** The template `jirrafe init` writes. */
        fun template(buildTool: String, group: String): String = """
            |[project]
            |build_tool = "$buildTool"                 # gradle | maven
            |
            |[deps]
            |internal_group_prefixes = ["$group"]      # artifacts under these groups are indexed as internal
            |decompile = "internal-only"           # internal-only | never; public groups need --i-understand-licenses
            |
            |[frameworks]
            |enabled = ["spring"]
            |
            |[knowledge]
            |provider = "none"                     # none | anthropic | openai | azure-openai | bedrock | ollama
            |model = ""
            |max_tokens_per_summary = 300
            |send_code_snippets = false            # if false, only names, signatures and Javadoc are sent
            |
            |[serve]
            |transport = "stdio"                   # stdio | http
            |default_token_budget = 5000
            |""".trimMargin()
    }
}
