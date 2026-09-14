package io.jirrafe.cli.mcp

import io.jirrafe.core.knowledge.SyntheticGraph
import io.jirrafe.core.query.Queries
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Contract test: every tool is listed with a schema and answers over a real MCP session. */
class McpServerTest {
    private val store = SyntheticGraph.build()
    private val report = Files.createTempFile("jirrafe", ".md").also { Files.writeString(it, "# Graph report\n\nhello") }
    private val mcp = McpServer(Queries(store, "/repo"), store, report, defaultBudget = 2000)

    private fun <T> session(block: suspend (Client) -> T): T = runBlocking {
        val pair = ChannelTransport.createLinkedPair()
        val client = Client(Implementation("test", "0"))
        mcp.server.createSession(pair.serverTransport)
        client.connect(pair.clientTransport)
        try { block(client) } finally { client.close() }
    }

    private suspend fun Client.call(tool: String, vararg args: Pair<String, Any?>) =
        callTool(tool, args.toMap()).let { r ->
            assertTrue(r.isError != true, "$tool failed: ${(r.content.first() as TextContent).text}")
            Json.parseToJsonElement((r.content.first() as TextContent).text).jsonObject
        }

    @Test
    fun `lists every tool with a schema and instructions`() = session { client ->
        val tools = client.listTools().tools
        assertEquals(McpServer.toolNames().sorted(), tools.map { it.name }.sorted())
        val explain = tools.first { it.name == "explain" }
        assertEquals(listOf("question"), explain.inputSchema.required)
        assertTrue(explain.inputSchema.properties!!.containsKey("token_budget"))
        assertTrue(tools.all { !it.description.isNullOrBlank() })
        assertContains(client.serverInstructions ?: "", "explain")
    }

    @Test
    fun `every tool answers`() = session { client ->
        assertEquals("module:app", client.call("overview")["modules"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content)
        assertContains(client.call("search", "query" to "OrderService")["results"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }, "a.OrderService")
        assertEquals("service", client.call("get_node", "id" to "a.OrderService")["layer"]!!.jsonPrimitive.content)
        assertTrue(client.call("neighbors", "id" to "a.OrderService#open()", "depth" to 1)["nodes"]!!.jsonArray.isNotEmpty())
        assertEquals("true", client.call("path", "from" to "a.OrderController", "to" to "a.OrderRepository#findAll()")["found"]!!.jsonPrimitive.content)
        assertTrue(client.call("impact", "id" to "a.OrderRepository#findAll()")["affected"]!!.jsonPrimitive.content.toInt() > 0)
        val communities = client.call("communities")["communities"]!!.jsonArray
        assertTrue(communities.isNotEmpty())
        assertTrue(client.call("community", "id" to communities.first().jsonObject["id"]!!.jsonPrimitive.content)["members"]!!.jsonArray.isNotEmpty())
        assertEquals("GET /orders", client.call("flow", "entry" to "/orders")["entry"]!!.jsonPrimitive.content)
        assertEquals(1, client.call("routes")["count"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, client.call("topics")["count"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, client.call("config", "key_prefix" to "orders")["count"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, client.call("beans")["count"]!!.jsonPrimitive.content.toInt())
        assertTrue(client.call("findings", "severity" to "warning")["count"]!!.jsonPrimitive.content.toInt() > 0)
        assertTrue(client.call("dependencies", "module" to ":app")["modules"]!!.jsonArray.size == 1)
        val explain = client.call("explain", "question" to "how are orders listed", "token_budget" to 800)
        assertTrue(explain["flows"]!!.jsonArray.isNotEmpty())
        // tool-level errors are reported in the result, not as protocol errors
        val bad = client.callTool("get_node", mapOf("id" to "nope"))
        assertEquals(true, bad.isError)
        // read_source without a reader is an in-result error too
        assertEquals(true, client.callTool("read_source", mapOf("id" to "a.OrderService")).isError)
    }

    @Test
    fun `report and flows are resources`() = session { client ->
        val resources = client.listResources().resources
        assertContains(resources.map { it.uri }, "jirrafe://report")
        assertContains(resources.map { it.uri }, "jirrafe://flow/a.OrderController#list()")
        val text = (client.readResource(ReadResourceRequest(ReadResourceRequestParams("jirrafe://report"))).contents.single() as TextResourceContents).text
        assertContains(text, "# Graph report")
        val flow = (client.readResource(ReadResourceRequest(ReadResourceRequestParams("jirrafe://flow/a.OrderController#list()"))).contents.single() as TextResourceContents).text
        assertContains(flow, "a.OrderRepository#findAll()")
    }
}
