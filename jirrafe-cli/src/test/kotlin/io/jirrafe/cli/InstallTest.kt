package io.jirrafe.cli

import com.github.ajalt.clikt.core.parse
import io.jirrafe.core.config.Config
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallTest {
    private val root: Path = Files.createTempDirectory("jirrafe-install")
    private val home: Path = Files.createTempDirectory("jirrafe-home")

    private fun json(p: Path) = Json.parseToJsonElement(Files.readString(p)).jsonObject

    @Test
    fun `every client gets a merged config and an instructions section`() {
        Files.writeString(root.resolve(".vscode/mcp.json").also { Files.createDirectories(it.parent) }, """{"servers":{"other":{"command":"x"}}}""")
        Files.writeString(root.resolve("CLAUDE.md"), "# My rules\n\nKeep them.\n")
        for (client in Install.CLIENTS) Install.run(client, root, "jirrafe", home)

        val vscode = json(root.resolve(".vscode/mcp.json"))["servers"]!!.jsonObject
        assertTrue(vscode.containsKey("other"), "existing servers survive")
        assertEquals("stdio", vscode["jirrafe"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val args = vscode["jirrafe"]!!.jsonObject["args"]!!.jsonArray.map { it.jsonPrimitive.content }
        if (System.getProperty("os.name").startsWith("Windows")) {
            assertEquals(listOf("/c", "jirrafe", "serve", "--dir"), args.dropLast(1))
            assertEquals(root.toRealPath(), Path.of(args.last()).toRealPath()) // 8.3 form when the path has a space
        } else assertEquals(listOf("serve", "--dir", root.toAbsolutePath().normalize().toString()), args)
        assertTrue(json(root.resolve(".mcp.json"))["mcpServers"]!!.jsonObject.containsKey("jirrafe"))
        assertTrue(Files.readString(root.resolve(".claude/skills/jirrafe/SKILL.md")).contains("jirrafe query explain"))
        assertTrue(json(root.resolve(".cursor/mcp.json"))["mcpServers"]!!.jsonObject.containsKey("jirrafe"))
        assertTrue(json(home.resolve(".codeium/windsurf/mcp_config.json"))["mcpServers"]!!.jsonObject.containsKey("jirrafe"))
        assertEquals("local", json(home.resolve(".copilot/mcp-config.json"))["mcpServers"]!!.jsonObject["jirrafe"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        val claude = Files.readString(root.resolve("CLAUDE.md"))
        assertTrue(claude.startsWith("# My rules"), "existing content kept")
        assertContains(claude, "<!-- jirrafe:begin -->")
        assertContains(claude, "explain(question)")
        val hooks = json(root.resolve(".claude/settings.json"))["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray
        assertEquals("Grep|Glob", hooks.single().jsonObject["matcher"]!!.jsonPrimitive.content)
        assertContains(Files.readString(root.resolve(".github/copilot-instructions.md")), "impact_of_changes")
        val cursor = Files.readString(root.resolve(".cursor/rules/jirrafe.mdc"))
        assertTrue(cursor.startsWith("---\ndescription: Use for any question") && cursor.contains("alwaysApply: false") && cursor.contains("`explain(question)`"), "an on-demand Cursor rule in the MCP form")
        val windsurf = Files.readString(root.resolve(".windsurf/rules/jirrafe.md"))
        assertTrue(windsurf.startsWith("---\ntrigger: model_decision") && windsurf.contains("`get_node(id)`"))
        val agents = Files.readString(root.resolve("AGENTS.md"))
        assertTrue(agents.contains("`jirrafe query explain") && !agents.contains("explain(question)"), "AGENTS.md gets the shell form")
        val skill = Files.readString(root.resolve(".claude/skills/jirrafe/SKILL.md"))
        assertTrue(skill.startsWith("---\nname: jirrafe") && skill.contains("allowed-tools: Bash(jirrafe:*)"), skill.take(200))
        assertTrue(!Files.exists(root.resolve(".cursorrules")) && !Files.exists(root.resolve(".windsurfrules")), "deprecated always-on files are not written")
    }

    @Test
    fun `installing twice does not duplicate sections or hooks`() {
        Install.run("claude", root, "jirrafe", home)
        Install.run("claude", root, "jirrafe", home)
        assertEquals(1, Regex("jirrafe:begin").findAll(Files.readString(root.resolve("CLAUDE.md"))).count())
        assertEquals(1, json(root.resolve(".claude/settings.json"))["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray.size)
    }

    @Test
    fun `config parses the template and init writes it`() {
        val c = Config.parse(Config.template("gradle", "com.acme"))
        assertEquals("gradle", c.string("project.build_tool", "?"))
        assertEquals(listOf("com.acme"), c.list("deps.internal_group_prefixes"))
        assertEquals(3000, c.int("serve.default_token_budget", 0))
        assertEquals(false, c.bool("knowledge.send_code_snippets", true))
        assertEquals("internal-only", c.string("deps.decompile", "?"))

        val project = Files.createTempDirectory("jirrafe-init")
        Files.writeString(project.resolve("build.gradle.kts"), "group = \"org.acme.shop\"\nversion = \"1\"\n")
        Init().parse(listOf("--dir", project.toString()))
        assertContains(Files.readString(project.resolve("jirrafe.toml")), "org.acme.shop")
        assertContains(Files.readString(project.resolve(".gitignore")), ".jirrafe/")
        Init().parse(listOf("--dir", project.toString()))
        assertEquals(1, Files.readAllLines(project.resolve(".gitignore")).count { it == ".jirrafe/" })
    }
}
