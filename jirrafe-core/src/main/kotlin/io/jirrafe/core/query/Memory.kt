package io.jirrafe.core.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * What earlier sessions asked and then fetched: one JSON line per query in `.jirrafe/queries.jsonl`. `explain`
 * reads it back so the ids an agent went on to fetch after the same question come first the next time, body
 * included, and the second call is not needed. Local to the checkout; nothing leaves the machine.
 */
class Memory(private val file: Path) {
    fun log(kind: String, arg: String) {
        runCatching {
            Files.createDirectories(file.parent)
            val line = buildJsonObject { put("t", System.currentTimeMillis() / 1000); put("kind", kind); put("arg", arg) }
            Files.writeString(file, line.toString() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    /**
     * Ids fetched with `source` or `node` within two minutes after a question whose terms overlap [terms] by at
     * least half (Jaccard), most fetched first, at most three. [termsOf] is the caller's own tokenisation.
     */
    fun recall(terms: Set<String>, termsOf: (String) -> Set<String>): List<String> {
        if (terms.isEmpty() || !Files.exists(file)) return emptyList()
        val entries = Files.readAllLines(file).takeLast(20_000).mapNotNull { l -> runCatching { Json.parseToJsonElement(l).jsonObject }.getOrNull() }
        val counts = HashMap<String, Int>()
        var until = 0L
        for (e in entries) {
            val t = e["t"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
            val arg = e["arg"]?.jsonPrimitive?.content ?: continue
            when (e["kind"]?.jsonPrimitive?.content) {
                "explain" -> {
                    val past = termsOf(arg)
                    val overlap = (past intersect terms).size.toDouble() / (past union terms).size.coerceAtLeast(1)
                    until = if (overlap >= 0.5) t + 120 else 0L
                }
                "source", "node" -> if (t <= until) counts.merge(arg, 1, Int::plus)
            }
        }
        return counts.entries.sortedByDescending { it.value }.take(3).map { it.key }
    }
}
