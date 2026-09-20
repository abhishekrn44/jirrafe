package io.jirrafe.core.bench

import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.query.Queries
import io.jirrafe.core.store.GraphStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

/**
 * Retrieval benchmark: for each question with expected node ids, what an agent gets from the
 * graph (one `explain` call, plus one `search` when explain missed something) versus a grep
 * baseline (grep each keyword over the repo sources, read the matching files). Measures
 * precision and recall over node ids, tokens handed to the model, and tool calls.
 */
object Benchmark {
    /** An expected entry may list alternatives separated by `|` (overloads, the same class in two modules): any one counts. */
    @Serializable
    class Question(val question: String, val expected: List<String>)

    class Run(val precision: Double, val recall: Double, val tokens: Int, val calls: Int, val found: Set<String>)

    class Row(val question: Question, val graph: Run, val grep: Run)

    class Result(val rows: List<Row>) {
        fun summary(select: (Row) -> Run): Map<String, Double> {
            val runs = rows.map(select)
            return mapOf(
                "precision" to runs.map { it.precision }.average(),
                "recall" to runs.map { it.recall }.average(),
                "tokens" to runs.map { it.tokens.toDouble() }.average(),
                "medianTokens" to runs.map { it.tokens }.sorted().let { it[it.size / 2] }.toDouble(),
                "calls" to runs.map { it.calls.toDouble() }.average(),
                "fullRecall" to runs.count { it.recall >= 0.999 }.toDouble() / runs.size,
            )
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun load(file: Path): List<Question> = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Question.serializer()), Files.readString(file))

    fun run(store: GraphStore, root: Path, sourceDirs: List<Path>, questions: List<Question>, budget: Int = Queries.DEFAULT_BUDGET): Result {
        val q = Queries(store, store.meta("root") ?: root.toString())
        val index = SourceIndex(store, root, sourceDirs)
        return Result(questions.map { question -> Row(question, graph(q, question, budget), grep(index, question)) })
    }

    // ---- graph -----------------------------------------------------------------------------------

    private fun graph(q: Queries, question: Question, budget: Int): Run {
        val found = LinkedHashSet<String>()
        var tokens = 0
        var calls = 0
        fun collect(o: JsonObject) {
            o["flows"]?.jsonArray?.forEach { f -> found += f.jsonObject["id"]!!.jsonPrimitive.content; f.jsonObject["steps"]?.jsonArray?.forEach { found += it.jsonObject["id"]!!.jsonPrimitive.content } }
            o["communities"]?.jsonArray?.forEach { c -> c.jsonObject["central"]?.jsonPrimitive?.content?.split(',')?.forEach { if (it.isNotEmpty()) found += it } }
            o["nodes"]?.jsonArray?.forEach { n ->
                found += n.jsonObject["id"]!!.jsonPrimitive.content
                for (edges in listOf("uses", "usedBy")) n.jsonObject[edges]?.jsonArray?.forEach { found += it.jsonObject["id"]!!.jsonPrimitive.content }
            }
            o["results"]?.jsonArray?.forEach { found += it.jsonObject["id"]!!.jsonPrimitive.content }
            o["pack"]?.jsonArray?.forEach { found += it.jsonObject["id"]!!.jsonPrimitive.content }
        }
        // `stale` describes the checkout, not the graph: a benchmark clone whose build rewrote its headers would pay for it on every question
        val explain = JsonObject(q.explain(question.question, budget).filterKeys { it != "stale" })
        tokens += Queries.tokens(explain); calls++
        collect(explain)
        if (!question.expected.all { e -> e.split('|').any { it in found } }) {
            val search = q.search(question.question, limit = 10, budget = budget)
            tokens += Queries.tokens(search); calls++
            collect(search)
        }
        return score(found, question.expected, tokens, calls)
    }

    // ---- grep baseline -----------------------------------------------------------------------------

    /** Every code node by file, so a read window can be mapped back to the ids it exposes. */
    private class SourceIndex(store: GraphStore, val root: Path, val sourceDirs: List<Path>) {
        val files: List<Path> = sourceDirs.filter { Files.isDirectory(it) }.flatMap { d -> d.walk().filter { it.isRegularFile() && it.extension in setOf("java", "kt", "yml", "yaml", "properties") }.toList() }
        val nodesByFile: Map<String, List<Triple<String, Int, Int>>> = (listOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.ENUM, NodeKind.RECORD, NodeKind.METHOD, NodeKind.CONFIG_KEY)
            .flatMap { store.nodes(it) })
            .filter { it.file != null && it.startLine != null }
            .groupBy({ Path.of(it.file!!).toAbsolutePath().normalize().toString() }, { Triple(it.id, it.startLine!!, it.endLine ?: it.startLine!!) })
        val lines = HashMap<Path, List<String>>()
        fun lines(p: Path) = lines.getOrPut(p) { String(Files.readAllBytes(p), Charsets.UTF_8).lines() } // lenient: repos carry Latin-1 files
    }

    private val STOP = setOf("the", "and", "how", "does", "what", "where", "which", "with", "for", "this", "that", "are", "when", "from", "into", "work", "works", "code", "mechanism", "used", "use", "uses", "there", "any", "all", "can", "you", "get", "is", "in", "of", "to", "a", "an", "do", "we", "our")

    /** Grep each keyword (up to three, most specific first), read the matching files around the hits like an agent would. */
    private fun grep(index: SourceIndex, question: Question): Run {
        val words = question.question.split(Regex("[^\\p{Alnum}_]+")).map { it.lowercase() }.filter { it.length > 2 && it !in STOP }.distinct()
            .sortedByDescending { it.length }.take(3)
        val found = LinkedHashSet<String>()
        var tokens = 0
        var calls = 0
        val read = HashSet<Pair<Path, Int>>() // file and window start already read
        for (w in words) {
            calls++ // one grep
            val stem = w.removeSuffix("ing").removeSuffix("ed").removeSuffix("es").removeSuffix("s")
            val hits = index.files.mapNotNull { f -> val l = index.lines(f); val i = l.indexOfFirst { it.lowercase().contains(stem) }; if (i < 0) null else f to i }
            tokens += hits.size * 20 // the grep output lines themselves
            for ((file, line) in hits.take(5)) { // an agent opens the first few matching files
                val start = maxOf(0, line - 30)
                if (!read.add(file to start / 60)) continue
                calls++ // one file read
                val lines = index.lines(file)
                val end = minOf(lines.size, start + 120)
                tokens += lines.subList(start, end).sumOf { it.length + 1 } / 4
                for ((id, s, e) in index.nodesByFile[file.toAbsolutePath().normalize().toString()].orEmpty()) if (s <= end && e >= start + 1) found += id
            }
        }
        return score(found, question.expected, tokens, calls)
    }

    private fun score(found: Set<String>, expected: List<String>, tokens: Int, calls: Int): Run {
        // "a|b": any overload or duplicate of a name counts. A class counts when a member of it was found:
        // the question asked about the class, and naming its method is naming it - otherwise an answer that
        // cites `Foo#bar()` scores zero against a key that says `Foo`, which is a fault of the key.
        fun matched(e: String) = e in found || (('#' !in e) && found.any { it.startsWith("$e#") })
        val hit = expected.count { e -> e.split('|').any { matched(it) } }
        val precision = if (found.isEmpty()) 0.0 else hit.toDouble() / found.size
        val recall = if (expected.isEmpty()) 1.0 else hit.toDouble() / expected.size
        return Run(precision, recall, tokens, calls, found)
    }

    fun markdown(name: String, r: Result): String = buildString {
        appendLine("### $name (${r.rows.size} questions)")
        if (r.rows.isEmpty()) return@buildString
        appendLine()
        appendLine("| approach | recall | full recall | precision | tokens (mean) | tokens (median) | tool calls (mean) |")
        appendLine("|---|---|---|---|---|---|---|")
        for ((label, sel) in listOf("jirrafe explain (+search)" to { row: Row -> row.graph }, "grep + read files" to { row: Row -> row.grep })) {
            val s = r.summary(sel)
            appendLine("| $label | ${pct(s["recall"]!!)} | ${pct(s["fullRecall"]!!)} | ${pct(s["precision"]!!)} | ${s["tokens"]!!.toInt()} | ${s["medianTokens"]!!.toInt()} | ${"%.1f".format(s["calls"])} |")
        }
        appendLine()
        appendLine("<details><summary>per question</summary>")
        appendLine()
        appendLine("| question | graph recall | graph tokens | grep recall | grep tokens |")
        appendLine("|---|---|---|---|---|")
        for (row in r.rows) appendLine("| ${row.question.question} | ${pct(row.graph.recall)} | ${row.graph.tokens} | ${pct(row.grep.recall)} | ${row.grep.tokens} |")
        appendLine()
        appendLine("</details>")
    }

    private fun pct(d: Double) = "${(d * 100).toInt()}%"
}
