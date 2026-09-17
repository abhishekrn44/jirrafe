package io.jirrafe.core.query

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The answer as an agent reads code: a file header per body, every line numbered, code as code. An agent
 * cites `file:line` from what it has read, and what it has read looks like this; the same bodies as escaped
 * strings inside JSON were fetched again as "the real thing". JSON stays the canonical result for tools,
 * tests and MCP's structured content; this is the text an agent sees.
 */
object Render {
    fun explain(o: JsonObject): String = buildString {
        appendLine("# " + o.str("question"))
        o["note"]?.let { appendLine(); appendLine("note: " + it.jsonPrimitive.content) }
        o["stale"]?.jsonObject?.let { s ->
            appendLine(); appendLine("stale: graph built at ${s.str("builtAt")}, HEAD ${s.str("head")}, ${s.str("changedSources")} changed source files" +
                (s["files"]?.jsonArray?.joinToString(", ", " (", ")") { it.jsonPrimitive.content } ?: "") + "; read the current file before quoting a line from one of them")
        }
        val flows = o["flows"]?.jsonArray.orEmpty()
        if (flows.isNotEmpty()) {
            appendLine(); appendLine("## flows")
            for (f in flows.map { it.jsonObject }) {
                val entry = f.str("entry"); val id = f.str("id")
                append("- $entry (${f.str("stepCount")} steps)"); if (f["truncated"] != null) append(", listed in part")
                if (f["derived"] != null) append("; derived from the best match, this project has no entry point to precompute a flow from")
                if (entry != id) append("  [$id]")
                appendLine()
                for (s in f["steps"]?.jsonArray.orEmpty().map { it.jsonObject }) appendLine("  - " + s.str("id") + (s["at"]?.let { "  @ " + it.jsonPrimitive.content } ?: ""))
            }
        }
        for (f in o["files"]?.jsonArray.orEmpty().map { it.jsonObject }) {
            appendLine(); appendLine("## " + f.str("path") + "  (whole file, " + f.str("lines") + " lines)")
            f["answers"]?.jsonArray?.takeIf { it.isNotEmpty() }?.let { a -> appendLine("answers here: " + a.joinToString(", ") { it.jsonPrimitive.content }) }
            appendLine()
            val width = f.str("lines").length
            for ((i, l) in f.str("text").lines().withIndex()) appendLine((i + 1).toString().padStart(width) + "  " + l)
        }
        for (c in o["cards"]?.jsonArray.orEmpty().map { it.jsonObject }) {
            appendLine(); appendLine("## " + c.str("id") + "  @ " + c.str("at"))
            c["class"]?.let { appendLine("in " + it.jsonPrimitive.content) }
            c["fields"]?.jsonArray?.takeIf { it.isNotEmpty() }?.let { fs -> appendLine("fields: " + fs.joinToString("; ") { it.jsonPrimitive.content }) }
            c["members"]?.jsonArray?.takeIf { it.isNotEmpty() }?.let { m -> appendLine("members (line name): " + m.joinToString(" · ") { it.jsonPrimitive.content }) }
            for (b in c["bodies"]?.jsonArray.orEmpty().map { it.jsonObject }) { appendLine(); append(source(b)) }
        }
        val pack = o["pack"]?.jsonArray.orEmpty()
        if (pack.isNotEmpty()) {
            appendLine(); appendLine("## code")
            for (p in pack.map { it.jsonObject }) {
                appendLine()
                append(source(p))
            }
        }
        section(o["data"], "data") { d -> "- " + d.str("id").substringAfterLast('.') + " { " + d.str("fields") + " }" + (d["table"]?.let { "  table " + it.jsonPrimitive.content } ?: "") + (d["at"]?.let { "  @ " + it.jsonPrimitive.content } ?: "") }
        section(o["config"], "config") { c -> "- " + c.str("key") + (c["value"]?.let { " = " + it.jsonPrimitive.content } ?: "") + (c["at"]?.let { "  @ " + it.jsonPrimitive.content } ?: "") }
        section(o["wiring"], "wiring (framework declarations that apply; complete for the annotations listed)") { w -> "- " + w.str("declares") + "  " + w.str("id") + (w["at"]?.let { "  @ " + it.jsonPrimitive.content } ?: "") }
        o["dependencies"]?.jsonArray?.takeIf { it.isNotEmpty() }?.let { d -> appendLine(); appendLine("dependencies: " + d.joinToString(", ") { it.jsonPrimitive.content }) }
        o["vocabulary"]?.jsonArray?.takeIf { it.isNotEmpty() }?.let { v -> appendLine(); appendLine("vocabulary (the code's own words nearest this question; ask again with the ones that fit): " + v.joinToString(", ") { it.jsonPrimitive.content }) }
        section(o["nodes"], if (pack.isNotEmpty()) "other matches" else "matches") { n ->
            "- " + n.str("id") + (n["at"]?.let { "  @ " + it.jsonPrimitive.content } ?: "") + (n["signature"]?.let { "  " + it.jsonPrimitive.content } ?: "") +
                (n["doc"]?.let { "  ; " + it.jsonPrimitive.content } ?: "") +
                (n["uses"]?.jsonArray?.takeIf { it.isNotEmpty() }?.let { u -> "\n  uses: " + u.joinToString(", ") { it.jsonObject.str("id") } } ?: "") +
                (n["usedBy"]?.jsonArray?.takeIf { it.isNotEmpty() }?.let { u -> "\n  used by: " + u.joinToString(", ") { it.jsonObject.str("id") } } ?: "")
        }
        section(o["communities"], "communities") { c -> "- " + c.str("label") + "  [" + c.str("id") + "]" + (c["summary"]?.let { ": " + it.jsonPrimitive.content } ?: "") }
    }

    /** One body, or a batch of them (`sources` with `pending`). Errors and candidate lists stay readable too. */
    fun sources(o: JsonObject): String = buildString {
        o["sources"]?.jsonArray?.let { list ->
            for (s in list.map { it.jsonObject }) { append(source(s)); appendLine() }
            o["pending"]?.jsonArray?.takeIf { it.isNotEmpty() }?.let { p -> appendLine("pending (did not fit; ask for them next): " + p.joinToString(", ") { it.jsonPrimitive.content }) }
        } ?: append(source(o))
    }

    /** `### file:start-end  id  callers N`, numbered lines, then `calls:`; an error or candidates list as is. */
    fun source(p: JsonObject): String = buildString {
        p["error"]?.let { e ->
            appendLine("error: " + e.jsonPrimitive.content)
            p["candidates"]?.jsonArray?.let { c -> for (n in c.map { it.jsonObject }) appendLine("- " + n.str("id") + (n["at"]?.let { "  @ " + it.jsonPrimitive.content } ?: "") + (n["signature"]?.let { "  " + it.jsonPrimitive.content } ?: "")) }
            return@buildString
        }
        val text = p.str("text")
        val lines = text.lines()
        val (file, start) = p["at"]?.let { a -> val s = a.jsonPrimitive.content; s.substringBeforeLast(':') to (s.substringAfterLast(':').toIntOrNull() ?: 1) }
            ?: (p.str("file") to (p["startLine"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1))
        val end = start + lines.size - 1
        append("### $file:$start-$end  ").append(p.str("id"))
        p["callers"]?.let { append("  callers ").append(it.jsonPrimitive.content) }
        if (p["decompiled"] != null) append("  (decompiled)")
        appendLine()
        p["class"]?.let { appendLine("in " + it.jsonPrimitive.content) }
        p["fields"]?.jsonArray?.takeIf { it.isNotEmpty() }?.let { fs -> appendLine("fields: " + fs.joinToString("; ") { it.jsonPrimitive.content }) }
        val width = end.toString().length
        for ((i, l) in lines.withIndex()) appendLine((start + i).toString().padStart(width) + "  " + l)
        if (p["truncated"] != null) appendLine("... cut at line $end; `source ${p.str("id")} --lines ${end + 1}-${end + 120}` continues it")
        p["calls"]?.jsonArray?.takeIf { it.isNotEmpty() }?.let { c -> appendLine("calls: " + c.joinToString(", ") { it.jsonPrimitive.content }) }
        p["stale"]?.jsonObject?.let { appendLine("stale: " + it.str("note")) }
    }

    private fun StringBuilder.section(v: kotlinx.serialization.json.JsonElement?, title: String, line: (JsonObject) -> String) {
        val items = (v as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return
        appendLine(); appendLine("## $title")
        for (i in items) appendLine(line(i.jsonObject))
    }

    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.content ?: ""
}
