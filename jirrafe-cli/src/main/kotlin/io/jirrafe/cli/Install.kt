package io.jirrafe.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes the MCP registration and an instructions file for one client. JSON configs are merged,
 * never overwritten; instruction sections are added once, between markers.
 */
object Install {
    val CLIENTS = listOf("copilot-vscode", "copilot-jetbrains", "copilot-cli", "claude", "claude-skill", "cursor", "windsurf", "agents-md")
    private const val BEGIN = "<!-- jirrafe:begin -->"
    private const val END = "<!-- jirrafe:end -->"
    private val json = Json { prettyPrint = true }

    fun run(client: String, root: Path, command: String = "jirrafe", home: Path = Path.of(System.getProperty("user.home"))): List<Path> {
        val dir = root.toAbsolutePath().normalize()
        val args = listOf("serve", "--dir", dir.toString())
        val written = ArrayList<Path>()
        // Windows clients spawn stdio servers through `cmd /c`, which mangles a quoted path with spaces followed
        // by more arguments, so every path is written in its 8.3 short form
        val windows = System.getProperty("os.name").startsWith("Windows")
        val argv = if (windows) listOf("/c", shortPath(command)) + args.map { shortPath(it) } else args
        fun server(extra: Map<String, JsonElement> = emptyMap()) = buildJsonObject {
            for ((k, v) in extra) put(k, v)
            put("command", if (windows) "cmd" else command)
            put("args", buildJsonArray { for (a in argv) add(kotlinx.serialization.json.JsonPrimitive(a)) })
        }
        when (client) {
            "copilot-vscode" -> {
                written.add(mergeJson(dir.resolve(".vscode/mcp.json"), listOf("servers", "jirrafe"), server(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("stdio")))))
                written.add(section(dir.resolve(".github/copilot-instructions.md"), instructions("Copilot")))
            }
            "copilot-jetbrains" -> {
                val cfg = if (System.getProperty("os.name").startsWith("Windows")) Path.of(System.getenv("LOCALAPPDATA") ?: home.resolve("AppData/Local").toString()).resolve("github-copilot/intellij/mcp.json")
                else home.resolve(".config/github-copilot/intellij/mcp.json")
                written.add(mergeJson(cfg, listOf("servers", "jirrafe"), server()))
                written.add(section(dir.resolve(".github/copilot-instructions.md"), instructions("Copilot")))
            }
            "copilot-cli" -> {
                written.add(mergeJson(home.resolve(".copilot/mcp-config.json"), listOf("mcpServers", "jirrafe"), server(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("local"), "tools" to buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("*")) }))))
                written.add(section(dir.resolve(".github/copilot-instructions.md"), instructions("Copilot")))
            }
            "claude" -> {
                written.add(mergeJson(dir.resolve(".mcp.json"), listOf("mcpServers", "jirrafe"), server()))
                written.add(section(dir.resolve("CLAUDE.md"), instructions("Claude Code")))
                written.add(claudeHook(dir.resolve(".claude/settings.json")))
            }
            // a skill instead of an MCP server: no tool schemas in every turn, no extra tool-search round trip,
            // and `/jirrafe` makes the graph the first step rather than a hope
            "claude-skill" -> {
                val file = dir.resolve(".claude/skills/jirrafe/SKILL.md")
                Files.createDirectories(file.parent)
                Files.writeString(file, skill(command))
                written.add(file)
            }
            // rules directories with a description load on demand, like the skill; the single always-on files are deprecated
            "cursor" -> {
                written.add(mergeJson(dir.resolve(".cursor/mcp.json"), listOf("mcpServers", "jirrafe"), server()))
                written.add(write(dir.resolve(".cursor/rules/jirrafe.mdc"), cursorRule()))
            }
            "windsurf" -> {
                written.add(mergeJson(home.resolve(".codeium/windsurf/mcp_config.json"), listOf("mcpServers", "jirrafe"), server()))
                written.add(write(dir.resolve(".windsurf/rules/jirrafe.md"), windsurfRule()))
            }
            // Codex, Amp, Jules and any agent that reads AGENTS.md and has a shell: the CLI form, no server
            "agents-md" -> written.add(section(dir.resolve("AGENTS.md"), instructions("the agent", command)))
            else -> throw IllegalArgumentException("unknown client '$client'; one of ${CLIENTS.joinToString()}")
        }
        return written
    }

    /** The trigger every client sees: when to reach for the graph. */
    const val DESCRIPTION = "Use for any question about this Java (or Kotlin) project's code before grepping or reading files - how something works, is done, verified, created, sent or configured; where it happens; what calls it; what breaks if it changes. Answers come from a precomputed code graph (repo plus internal jars, README and docs) with file:line citations in one or two calls, including request flows, Spring wiring, configuration keys, \"how do I use X\" from the project's own docs, and the impact of a change."

    /** The instructions section for a client that calls the MCP tools; `agents-md` gets the CLI form. */
    /** Always-on text for a client without on-demand skills: short, an order first, then the triggers; the procedure lives in the skill. */
    fun instructions(client: String, cli: String? = null): String {
        fun c(shell: String, mcp: String) = if (cli != null) shell else mcp
        val explain = c("`$cli query explain \"<the question>\"`", "`explain(question)`")
        val source = c("`$cli query source <id> [<id> ...]`", "`read_source(id)` (several ids comma-separated)")
        val impact = c("`$cli query impact <id>`", "`impact(id)`")
        val diff = c("`$cli query impact --diff`", "`impact_of_changes`")
        val index = c("`$cli index`", "`jirrafe index` in a shell")
        return """
        |## jirrafe code graph
        |
        |For any question about how this Java or Kotlin project's code works - how something is done, verified,
        |created, sent or configured; where it happens; what calls it; what breaks if it changes - your first
        |action is $explain. It returns the method bodies along the chain from the entry point with every
        |line numbered, the entities and config keys they use, and the framework wiring that applies (security,
        |caching, transactions, error handling), each cited `file:line`. Most questions end there.
        |
        |Triggers: "how is/does ...", "where is ...", "what calls ...", "which class/endpoint/key ...", "how do I ...",
        |"add/modify/fix <something>", anything that depends on how classes relate.
        |
        |Then, only when needed: $source for a body the answer names but does not show; $impact before changing a
        |method or for "what calls X" (the caller list is complete; do not grep to confirm it); $diff after editing,
        |for the tests to run. If the answer has no code but lists `vocabulary`, ask again with those words.
        |
        |Answer from what the commands returned and cite its numbered lines; do not fetch a body already shown;
        |where the answer is silent, say so rather than fill it in. Read source files only for something the graph
        |did not return. When an answer says `stale`, run $index first. The graph is not rebuilt by $client.
        |""".trimMargin()
    }

    fun skill(command: String): String =
        "---\nname: jirrafe\ndescription: $DESCRIPTION\nallowed-tools: Bash($command:*)\n---\n\n" + body(command)

    /** Cursor rule, loaded on demand by its description; goes in `.cursor/rules/`. */
    fun cursorRule(): String = "---\ndescription: $DESCRIPTION\nglobs:\nalwaysApply: false\n---\n\n" + body(null)

    /** Windsurf rule, activation by model decision; goes in `.windsurf/rules/`. */
    fun windsurfRule(): String = "---\ntrigger: model_decision\ndescription: $DESCRIPTION\n---\n\n" + body(null)

    /**
     * The measured procedure, once. With [cli] set the steps run `jirrafe query ...` in a shell (Claude Code skill,
     * AGENTS.md); without it they call the MCP tools of the same names.
     */
    fun body(cli: String?): String {
        fun c(shell: String, mcp: String) = if (cli != null) shell else mcp
        val explain = c("`$cli query explain \"<the question in plain words>\"`", "`explain(question)`")
        val node = c("`$cli query node <id>`", "`get_node(id)`")
        val source = c("`$cli query source <id>`", "`read_source(id)`")
        val sources = c("`$cli query source <id> <id> <id>`", "`read_source` with the ids comma-separated")
        val more = c("`--lines A-B`", "`lines: A-B`")
        val impact = c("`$cli query impact <id>`", "`impact(id)`")
        val diff = c("`$cli query impact --diff`", "`impact_of_changes`")
        val build = c("`$cli build`", "`jirrafe build` in a shell")
        val index = c("`$cli index`", "`jirrafe index` in a shell")
        val call = c("`$cli query`", "graph tool")
        val root = c(" Run the commands from the project root.", "")
        val others = c("`routes`, `beans`, `config <prefix>`, `topics`, `findings`, `flow <route or id>`, `neighbors <id>`, `search <name>`, `overview`",
            "`routes`, `beans`, `config`, `topics`, `findings`, `flow`, `neighbors`, `search`, `overview`")
        return """
        |This project has a jirrafe code graph in `.jirrafe/` covering the repository and its internal jars.
        |Every answer is text with `file:line` citations (`--format json` for JSON).$root
        |
        |1. The graph is `.jirrafe/graph.db` and is already built; do not check for it. If an answer says it is missing, run $build (about a minute).
        |   An answer carrying `stale` predates edits to the files it lists: their cited lines may be off, so read
        |   the current file before quoting a line from one of them. When `stale` lists more than a few files, or
        |   the question is about one of them, run $index first (incremental, seconds) and ask again.
        |2. Start with the question itself: $explain
        |   The answer is the reading itself: under `## code`, the method bodies along the chain from the entry
        |   point, each headed `### file:start-end  id` with every line numbered, followed by the ids it calls;
        |   then `## data` (the entities and DTOs those bodies move, as field lists), `## config` (the keys they
        |   read, with values), `## wiring` (the framework declarations that apply: security rules and filters,
        |   cache, transaction and exception-handling configuration, with their values, locations and the
        |   dependency that ships them; complete for the annotations listed), `## flows` and `## other matches`.
        |   Each body says which class it lives in, how that class is annotated, and its `fields` (the injected
        |   repositories, the encoder or client it was built with). Explain the mechanism end to end: entry
        |   point, service logic, data access, and the wiring that enforces it. Cite `file:line`
        |   straight from the numbered lines. The code shown is the source; do not fetch a body that is already
        |   under `## code`. If there is no `## code` and a `vocabulary` line, the question's words are not the
        |   code's: ask again with the listed words that fit. Answer only from what the commands returned; where
        |   they are silent, say so rather than fill it in.
        |3. Fetch more only in three cases, and in one call: a body that ends with `... cut at line N` continues
        |   with $more; ids listed as `pending`; an id the answer names but does not show. $sources returns
        |   several bodies at once. $node gives one node with its callers and callees. Classes inside internal
        |   jars are decompiled on demand. Read a method (`Class#method(...)`), not its whole class. Ids are
        |   pasted verbatim from an answer; an id you have not seen in an answer is found with `search <name>`
        |   first, never guessed. A bare `Class#method` matching several overloads returns candidates, pick one.
        |   When the question names a method or class whose id you already know, go straight to $source,
        |   $node or $impact; the question itself is for finding what you do not know.
        |4. Before recommending a change, and for "what calls X" or "who uses X": $impact
        |   lists every caller with its `file:line`, grouped by community and flow, and the tests to run.
        |   That list is complete; do not grep to confirm it. Only a reflective or config-driven use
        |   (a class named in a config value) is missing from it, so say so when the caller list looks short.
        |   After editing, before committing: $diff maps the working tree's changes to
        |   the members they touch, lists what those affect, and ends with `testCommand`, the one command that
        |   runs the covering tests; run that instead of the whole suite.
        |5. Other views when the question asks for them: $others.
        |
        |Trust edges marked `exact` or `spring` over `heuristic`. Cite `file:line` from the answers. Skip the
        |graph when you already know the file and line; one $call call replaces a grep session,
        |not a fact you already have.
        |""".trimMargin()
    }

    /** Adds or replaces the marked section in a Markdown or rules file. */
    private fun section(file: Path, text: String): Path {
        val block = "$BEGIN\n$text$END\n"
        val existing = if (Files.exists(file)) Files.readString(file) else ""
        val updated = if (BEGIN in existing && END in existing) {
            existing.substring(0, existing.indexOf(BEGIN)) + block + existing.substring(existing.indexOf(END) + END.length).trimStart('\n')
        } else if (existing.isBlank()) block else existing.trimEnd() + "\n\n" + block
        write(file, updated)
        return file
    }

    /** Sets `path` inside the JSON object at [file], keeping everything else. */
    private fun mergeJson(file: Path, path: List<String>, value: JsonElement): Path {
        val existing = if (Files.exists(file)) runCatching { json.parseToJsonElement(Files.readString(file)).jsonObject }.getOrNull() ?: JsonObject(emptyMap()) else JsonObject(emptyMap())
        write(file, json.encodeToString(JsonElement.serializer(), set(existing, path, value)))
        return file
    }

    private fun set(obj: JsonObject, path: List<String>, value: JsonElement): JsonObject {
        val key = path.first()
        val child = if (path.size == 1) value else set(obj[key]?.let { it as? JsonObject } ?: JsonObject(emptyMap()), path.drop(1), value)
        return JsonObject(obj + (key to child))
    }

    /** A PreToolUse hook on Grep and Glob that reminds Claude Code to use the graph first. */
    /** `C:\Users\Abhishek Rana\x` -> `C:\Users\ABHISH~1\x`; unchanged when it has no space or is not an existing path. */
    fun shortPath(p: String): String {
        if (' ' !in p || !Files.exists(Path.of(p))) return p
        return runCatching {
            val proc = ProcessBuilder("cmd", "/c", "for %I in (\"$p\") do @echo %~sI").redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().readText().trim().also { proc.waitFor() }.takeIf { it.isNotEmpty() && ' ' !in it }
        }.getOrNull() ?: p
    }

    private fun claudeHook(file: Path): Path {
        val existing = if (Files.exists(file)) runCatching { json.parseToJsonElement(Files.readString(file)).jsonObject }.getOrNull() ?: JsonObject(emptyMap()) else JsonObject(emptyMap())
        val hooks = existing["hooks"] as? JsonObject ?: JsonObject(emptyMap())
        val pre = (hooks["PreToolUse"] as? kotlinx.serialization.json.JsonArray)?.toList().orEmpty()
        if (pre.any { "jirrafe" in it.toString() }) return file
        val reminder = "jirrafe: for code structure prefer the jirrafe MCP tools (explain, search, impact, flow) over Grep/Glob."
        val entry = buildJsonObject {
            put("matcher", "Grep|Glob")
            put("hooks", buildJsonArray {
                add(buildJsonObject {
                    put("type", "command")
                    put("command", "echo '{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"additionalContext\":\"$reminder\"}}'")
                })
            })
        }
        val updated = JsonObject(existing + ("hooks" to JsonObject(hooks + ("PreToolUse" to kotlinx.serialization.json.JsonArray(pre + entry)))))
        write(file, json.encodeToString(JsonElement.serializer(), updated))
        return file
    }

    private fun write(file: Path, text: String): Path {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(file, if (text.endsWith("\n")) text else text + "\n")
        return file
    }
}
