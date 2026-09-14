package io.jirrafe.core.llm

import io.jirrafe.core.config.Config
import io.jirrafe.core.model.Attrs
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.query.SourceReader
import io.jirrafe.core.store.GraphStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/** One chat completion: prompt in, text out. Providers are plain HTTP so an air-gapped Ollama works like the rest. */
fun interface Summarizer {
    fun summarize(prompt: String, maxTokens: Int): String
}

/**
 * The five providers as request shapes over one `java.net.http` client. Keys come from the
 * environment (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `AZURE_OPENAI_API_KEY`, `AWS_BEARER_TOKEN_BEDROCK`);
 * endpoints from `knowledge.endpoint` in `jirrafe.toml` or their conventional environment variables.
 */
class HttpSummarizer(
    val provider: String,
    val model: String,
    private val endpoint: String?,
    private val env: (String) -> String? = System::getenv,
    private val send: (HttpRequest) -> String = ::sendWithJdk,
) : Summarizer {
    class Shape(val url: String, val headers: Map<String, String>, val body: JsonObject, val text: (JsonObject) -> String)

    fun shape(prompt: String, maxTokens: Int): Shape = when (provider) {
        "anthropic" -> Shape(
            (endpoint ?: env("ANTHROPIC_BASE_URL") ?: "https://api.anthropic.com").trimEnd('/') + "/v1/messages",
            mapOf(
                "x-api-key" to key("ANTHROPIC_API_KEY"), "anthropic-version" to "2023-06-01",
                "anthropic-beta" to "server-side-fallback-2026-07-01",
            ),
            buildJsonObject {
                put("model", model.ifEmpty { "claude-opus-5" }); put("max_tokens", maxTokens); put("fallbacks", "default")
                put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", prompt) }) })
            },
        ) { r ->
            if (r["stop_reason"]?.jsonPrimitive?.content == "refusal") "" else
                r["content"]!!.jsonArray.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }.joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content }
        }
        "openai" -> Shape(
            (endpoint ?: env("OPENAI_BASE_URL") ?: "https://api.openai.com/v1").trimEnd('/') + "/chat/completions",
            mapOf("Authorization" to "Bearer ${key("OPENAI_API_KEY")}"),
            chatBody(model.ifEmpty { "gpt-5" }, prompt, maxTokens, "max_completion_tokens"),
            ::chatText,
        )
        "azure-openai" -> {
            val base = (endpoint ?: env("AZURE_OPENAI_ENDPOINT") ?: error("set AZURE_OPENAI_ENDPOINT or knowledge.endpoint")).trimEnd('/')
            val deployment = model.ifEmpty { error("knowledge.model must name the Azure deployment") }
            Shape(
                "$base/openai/deployments/$deployment/chat/completions?api-version=${env("AZURE_OPENAI_API_VERSION") ?: "2024-10-21"}",
                mapOf("api-key" to key("AZURE_OPENAI_API_KEY")),
                chatBody(deployment, prompt, maxTokens, "max_completion_tokens"),
                ::chatText,
            )
        }
        "bedrock" -> {
            val region = env("AWS_REGION") ?: env("AWS_DEFAULT_REGION") ?: "us-east-1"
            val id = model.ifEmpty { "anthropic.claude-opus-5" }
            Shape(
                (endpoint ?: "https://bedrock-runtime.$region.amazonaws.com").trimEnd('/') + "/model/$id/converse",
                mapOf("Authorization" to "Bearer ${key("AWS_BEARER_TOKEN_BEDROCK")}"),
                buildJsonObject {
                    put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", buildJsonArray { add(buildJsonObject { put("text", prompt) }) }) }) })
                    put("inferenceConfig", buildJsonObject { put("maxTokens", maxTokens) })
                },
            ) { r -> r["output"]!!.jsonObject["message"]!!.jsonObject["content"]!!.jsonArray.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }.joinToString("") }
        }
        "ollama" -> Shape(
            (endpoint ?: env("OLLAMA_HOST")?.let { if (it.startsWith("http")) it else "http://$it" } ?: "http://localhost:11434").trimEnd('/') + "/api/chat",
            emptyMap(),
            buildJsonObject {
                put("model", model.ifEmpty { "llama3.1" }); put("stream", false)
                put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", prompt) }) })
                put("options", buildJsonObject { put("num_predict", maxTokens) })
            },
        ) { r -> r["message"]!!.jsonObject["content"]!!.jsonPrimitive.content }
        else -> error("unknown provider '$provider'; one of ${PROVIDERS.joinToString()}")
    }

    override fun summarize(prompt: String, maxTokens: Int): String {
        val s = shape(prompt, maxTokens)
        val request = HttpRequest.newBuilder(URI.create(s.url)).timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .apply { s.headers.forEach { (k, v) -> header(k, v) } }
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(JsonObject.serializer(), s.body)))
            .build()
        return s.text(json.parseToJsonElement(send(request)).jsonObject).trim()
    }

    private fun key(name: String) = env(name) ?: error("set $name for provider $provider")

    private fun chatBody(model: String, prompt: String, maxTokens: Int, maxField: String) = buildJsonObject {
        put("model", model); put(maxField, maxTokens)
        put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", prompt) }) })
    }

    private fun chatText(r: JsonObject) = r["choices"]!!.jsonArray.first().jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content

    companion object {
        val PROVIDERS = listOf("anthropic", "openai", "azure-openai", "bedrock", "ollama")
        private val json = Json { encodeDefaults = false }

        private fun sendWithJdk(request: HttpRequest): String {
            val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build().send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() / 100 != 2) error("${request.uri()} returned ${response.statusCode()}: ${response.body().take(300)}")
            return response.body()
        }

        fun fromConfig(config: Config, env: (String) -> String? = System::getenv): HttpSummarizer? {
            val provider = config.string("knowledge.provider", "none")
            if (provider == "none") return null
            return HttpSummarizer(provider, config.string("knowledge.model", ""), config.string("knowledge.endpoint", "").ifEmpty { null }, env)
        }
    }
}

/**
 * LLM labels and summaries for communities, flows and god nodes, cached by the hash of what was
 * sent (`.jirrafe/summaries.json`), so incremental rebuilds only re-summarize what changed.
 * Inputs are names, signatures, Javadoc and top edges; code only when `send_code_snippets` is on.
 */
class Summaries(
    private val store: GraphStore,
    private val cacheFile: Path,
    private val summarizer: Summarizer,
    private val maxTokens: Int = 300,
    private val sendCode: Boolean = false,
    private val sources: SourceReader? = null,
    private val cacheKeyPrefix: String = "",
    private val log: (String) -> Unit = {},
) {
    class Stats(val prompts: Int, val calls: Int, val cached: Int, val failed: Int)

    private val json = Json { prettyPrint = true }
    private val cache: MutableMap<String, String> =
        if (Files.exists(cacheFile)) json.parseToJsonElement(Files.readString(cacheFile)).jsonObject.mapValues { it.value.jsonPrimitive.content }.toMutableMap() else HashMap()

    /** With [dryRun], prints every prompt that would be sent and sends nothing. */
    fun run(dryRun: Boolean = false): Stats {
        val jobs = communities() + flows() + gods()
        var calls = 0; var cached = 0; var failed = 0
        val attrs = HashMap<String, Map<String, String>>()
        for ((node, prompt) in jobs) {
            val key = sha(cacheKeyPrefix + prompt)
            val answer = cache[key]
            if (answer != null) { cached++; attrs[node.id] = parse(answer); continue }
            if (dryRun) { log("---- ${node.id} (${prompt.length} chars)\n$prompt"); continue }
            val text = try { summarizer.summarize(prompt, maxTokens) } catch (e: Exception) { failed++; log("summary failed for ${node.id}: ${e.message}"); continue }
            calls++
            if (text.isBlank()) continue
            cache[key] = text
            attrs[node.id] = parse(text)
        }
        if (!dryRun) {
            store.setAttrs(attrs)
            store.flush()
            cacheFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.writeString(cacheFile, json.encodeToString(JsonObject.serializer(), JsonObject(cache.mapValues { JsonPrimitive(it.value) })))
        }
        return Stats(jobs.size, calls, cached, failed)
    }

    /** First line is the label, the rest the summary. */
    private fun parse(text: String): Map<String, String> {
        val lines = text.trim().lines().map { it.trim().removePrefix("Label:").removePrefix("label:").trim() }.filter { it.isNotEmpty() }
        val label = lines.firstOrNull()?.trim('*', '#', ' ', '"')?.take(80) ?: return emptyMap()
        val summary = lines.drop(1).joinToString(" ").removePrefix("Summary:").trim().ifEmpty { label }
        return mapOf("llmLabel" to label, "llmSummary" to summary)
    }

    private fun communities(): List<Pair<Node, String>> = store.nodes(NodeKind.COMMUNITY).map { c ->
        val members = store.edgesTo(c.id, EdgeKind.MEMBER_OF_COMMUNITY).mapNotNull { store.node(it.from) }.sortedByDescending { it.attrs["inDegree"]?.toInt() ?: 0 }
        val prompt = buildString {
            appendLine("You are documenting a Java codebase. Below is one cluster of related classes found by community detection.")
            appendLine("Reply with a short label on the first line (3-6 words), then 2-3 plain sentences saying what this part of the system does and how its classes work together. No markdown.")
            appendLine()
            appendLine("Packages: ${c.attrs["packages"]}")
            c.attrs["layers"]?.takeIf { it.isNotEmpty() }?.let { appendLine("Layers: $it") }
            appendLine("Classes:")
            for (m in members.take(25)) appendLine(describe(m))
            if (members.size > 25) appendLine("... and ${members.size - 25} more")
            edges(members.take(25)).forEach { appendLine(it) }
        }
        c to prompt
    }

    private fun flows(): List<Pair<Node, String>> = store.nodes(NodeKind.FLOW).map { f ->
        val steps = f.attrs["steps"]?.let { Json.parseToJsonElement(it).jsonArray } ?: JsonArray(emptyList())
        val prompt = buildString {
            appendLine("You are documenting a Java codebase. Below is one end-to-end flow from an entry point through the methods it calls, in call order.")
            appendLine("Reply with a short label on the first line (3-6 words), then 2-3 plain sentences describing what happens end to end, naming the important classes. No markdown.")
            appendLine()
            appendLine("Entry: ${f.fqn} (${f.attrs["entryKind"]})")
            f.attrs["artifacts"]?.takeIf { it.isNotEmpty() }?.let { appendLine("Crosses internal jars: $it") }
            appendLine("Steps:")
            for (s in steps.take(40)) {
                val id = s.jsonObject["id"]!!.jsonPrimitive.content
                val depth = s.jsonObject["depth"]!!.jsonPrimitive.content.toInt()
                appendLine("  ".repeat(depth) + (store.node(id)?.let { describe(it) } ?: id))
            }
            if (sendCode) store.node(f.attrs["entry"].orEmpty())?.let { entry -> sources?.read(entry, 0)?.let { appendLine(); appendLine("Entry method source:"); appendLine(it.text.lines().take(40).joinToString("\n")) } }
        }
        f to prompt
    }

    private fun gods(): List<Pair<Node, String>> = store.meta("knowledge.gods").orEmpty().split(',').filter { it.isNotEmpty() }.mapNotNull { store.node(it) }.map { g ->
        val members = store.edgesFrom(g.id, EdgeKind.CONTAINS).mapNotNull { store.node(it.to) }.filter { it.kind == NodeKind.METHOD }
        val prompt = buildString {
            appendLine("You are documenting a Java codebase. Below is a central class that many others depend on.")
            appendLine("Reply with the class's role in 3-6 words on the first line, then one sentence on what it does. No markdown.")
            appendLine()
            appendLine(describe(g))
            appendLine("Used by ${g.attrs["inDegree"]} classes. Methods:")
            for (m in members.take(20)) appendLine("  " + (m.signature ?: m.id.substringAfter('#')) + (m.doc?.let { " -- " + it.lineSequence().first().take(120) } ?: ""))
            if (sendCode) sources?.read(g, 0)?.let { appendLine(); appendLine("Source:"); appendLine(it.text.lines().take(60).joinToString("\n")) }
        }
        g to prompt
    }

    private fun describe(n: Node): String {
        val kind = n.kind.name.lowercase()
        val layer = n.attrs["layer"]?.let { " [$it]" } ?: ""
        val annotations = Attrs.annotations(n).keys.map { "@" + it.substringAfterLast('.') }.take(4).joinToString(" ")
        val doc = n.doc?.lineSequence()?.firstOrNull()?.take(140)?.let { " -- $it" } ?: ""
        return "- $kind ${n.fqn}$layer ${annotations}$doc".replace("  ", " ")
    }

    private fun edges(members: List<Node>): List<String> {
        val ids = members.map { it.id }.toSet()
        val out = ArrayList<String>()
        for (m in members) {
            val targets = (listOf(m.id) + store.edgesFrom(m.id, EdgeKind.CONTAINS).map { it.to })
                .flatMap { store.edgesFrom(it) }
                .filter { it.kind == EdgeKind.CALLS || it.kind == EdgeKind.INJECTS || it.kind == EdgeKind.EXTENDS || it.kind == EdgeKind.IMPLEMENTS }
                .map { it.to.substringBefore('#') }.filter { it in ids && it != m.id }.distinct().take(5)
            if (targets.isNotEmpty()) out += "- ${m.fqn.substringAfterLast('.')} uses ${targets.joinToString { it.substringAfterLast('.') }}"
        }
        return out.take(30)
    }

    private fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}
