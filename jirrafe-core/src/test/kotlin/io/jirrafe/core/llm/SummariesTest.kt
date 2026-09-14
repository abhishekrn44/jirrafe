package io.jirrafe.core.llm

import io.jirrafe.core.knowledge.SyntheticGraph
import io.jirrafe.core.model.NodeKind
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummariesTest {
    private val store = SyntheticGraph.build()
    private val cache = Files.createTempDirectory("jirrafe-summaries").resolve("summaries.json")

    @Test
    fun `summaries are applied, cached by prompt hash, and dry run sends nothing`() {
        val sent = ArrayList<String>()
        val fake = Summarizer { prompt, _ -> sent += prompt; "Order handling\nPlaces and lists orders through the service and repository." }
        val first = Summaries(store, cache, fake, log = {}).run()
        assertTrue(first.prompts >= 3, "communities, flows and gods: ${first.prompts}")
        assertEquals(first.prompts, first.calls)
        assertEquals(0, first.cached)
        val community = store.nodes(NodeKind.COMMUNITY).first()
        assertEquals("Order handling", community.attrs["llmLabel"])
        assertContains(community.attrs["llmSummary"]!!, "repository")
        assertTrue(sent.any { "OrderController" in it && "Layers:" in it }, "community prompts carry classes and layers")
        assertTrue(sent.any { "Entry: GET /orders" in it && "OrderRepository#findAll" in it }, "flow prompts carry the steps")
        assertTrue(sent.none { "source" in it.lowercase() && "{" in it }, "no code without send_code_snippets")
        assertTrue(Files.exists(cache))

        val second = Summaries(store, cache, { _, _ -> error("must not be called") }).run()
        assertEquals(second.prompts, second.cached)
        assertEquals(0, second.calls)

        val dry = Summaries(store, Files.createTempFile("empty", ".json").also { Files.delete(it) }, { _, _ -> error("must not be called") }, log = {}).run(dryRun = true)
        assertEquals(0, dry.calls)
        assertEquals(dry.prompts, first.prompts)
    }

    @Test
    fun `provider request shapes`() {
        val env = mapOf("ANTHROPIC_API_KEY" to "a", "OPENAI_API_KEY" to "o", "AZURE_OPENAI_API_KEY" to "z", "AZURE_OPENAI_ENDPOINT" to "https://x.openai.azure.com", "AWS_BEARER_TOKEN_BEDROCK" to "b")::get
        val anthropic = HttpSummarizer("anthropic", "", null, env).shape("hi", 100)
        assertEquals("https://api.anthropic.com/v1/messages", anthropic.url)
        assertEquals("2023-06-01", anthropic.headers["anthropic-version"])
        assertEquals("claude-opus-5", anthropic.body["model"]!!.jsonPrimitive.content)
        assertEquals("default", anthropic.body["fallbacks"]!!.jsonPrimitive.content)
        assertEquals("hi", anthropic.body["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content)
        val openai = HttpSummarizer("openai", "gpt-5-mini", null, env).shape("hi", 100)
        assertEquals("https://api.openai.com/v1/chat/completions", openai.url)
        assertEquals("Bearer o", openai.headers["Authorization"])
        assertEquals(100, openai.body["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
        val azure = HttpSummarizer("azure-openai", "my-deployment", null, env).shape("hi", 100)
        assertContains(azure.url, "/openai/deployments/my-deployment/chat/completions?api-version=")
        val bedrock = HttpSummarizer("bedrock", "", null, env).shape("hi", 100)
        assertEquals("https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-opus-5/converse", bedrock.url)
        val ollama = HttpSummarizer("ollama", "", null, env).shape("hi", 100)
        assertEquals("http://localhost:11434/api/chat", ollama.url)
        assertEquals("false", ollama.body["stream"]!!.jsonPrimitive.content)

        // responses parse through one fake transport
        val s = HttpSummarizer("anthropic", "", null, env) { _ -> """{"stop_reason":"end_turn","content":[{"type":"text","text":"Label\nBody"}]}""" }
        assertEquals("Label\nBody", s.summarize("hi", 10))
        val refused = HttpSummarizer("anthropic", "", null, env) { _ -> """{"stop_reason":"refusal","content":[]}""" }
        assertEquals("", refused.summarize("hi", 10))
    }
}
