package io.jirrafe.cli.mcp

import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.query.Queries
import io.jirrafe.core.store.GraphStore
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * The one file that touches the MCP SDK. Maps every [Queries] method to a
 * tool, exposes the report and each flow as a resource, and serves over stdio or Streamable HTTP.
 * Starts from `graph.db` as is; never rebuilds.
 */
class McpServer(
    private val queries: Queries,
    private val store: GraphStore,
    private val reportFile: Path,
    private val defaultBudget: Int = Queries.DEFAULT_BUDGET,
) {
    private class Param(val name: String, val type: String, val description: String, val required: Boolean = false)

    private val budget = Param("token_budget", "integer", "maximum size of the answer in tokens (default $defaultBudget)")
    private val id = Param("id", "string", "node id, e.g. com.acme.OrderService or com.acme.OrderService#place(com.acme.Order)", required = true)

    val server: Server = Server(
        Implementation("jirrafe", version()),
        ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(), resources = ServerCapabilities.Resources())),
        INSTRUCTIONS,
    )

    init {
        tool("explain", "Call this first. Finds the flows, communities and nodes relevant to a question and returns a cited context bundle within the budget. Deterministic, no LLM.",
            listOf(Param("question", "string", "the question in plain words", true), budget)) { a -> queries.explain(a.str("question"), a.budget()) }
        tool("overview", "Modules, internal jars, top communities and flows, layers and findings counts.", listOf(budget)) { a -> queries.overview(a.budget()) }
        tool("search", "Full-text search over names, signatures and docs; prefix words; falls back to id substring.",
            listOf(Param("query", "string", "words or a (partial) name", true), Param("kinds", "string", "comma-separated node kinds to keep, e.g. class,method,http_route"), Param("limit", "integer", "max results (default 20)"), budget)) { a ->
            queries.search(a.str("query"), a.kinds(), a.int("limit", 20), a.budget())
        }
        tool("get_node", "A node with its attributes, doc, community, layer, edges, flows and findings; optionally its source.",
            listOf(id, Param("include_source", "boolean", "include the source text (decompiles internal jars lazily)"), budget)) { a -> queries.getNode(a.str("id"), a.bool("include_source"), a.budget()) }
        tool("read_source", "Exact source lines of a node; decompiled output is marked as such.",
            listOf(id, Param("context_lines", "integer", "lines before and after (default 0)"))) { a -> queries.readSource(a.str("id"), a.int("context_lines", 0)) }
        tool("neighbors", "Nodes and edges around a node.",
            listOf(id, Param("direction", "string", "in | out | both (default both)"), Param("edge_types", "string", "comma-separated edge kinds, e.g. calls,injects"), Param("depth", "integer", "1-4 (default 1)"), Param("resolution_min", "number", "drop edges below this confidence"), budget)) { a ->
            queries.neighbors(a.str("id"), a.str("direction", "both"), a.edgeKinds(), a.int("depth", 1), a.num("resolution_min", 0.0), a.budget())
        }
        tool("path", "Shortest dependency path between two nodes.",
            listOf(Param("from", "string", "start node id", true), Param("to", "string", "end node id", true), Param("max_depth", "integer", "default 6"))) { a -> queries.path(a.str("from"), a.str("to"), a.int("max_depth", 6)) }
        tool("impact", "Transitive callers including dispatch, grouped by community, flow and module, with the tests to run.",
            listOf(id, Param("depth", "integer", "1-6 (default 3)"), budget)) { a -> queries.impact(a.str("id"), a.int("depth", 3), a.budget()) }
        tool("impact_of_changes", "impact of the working tree's changes against HEAD: the members the changed lines fall in, their callers, the tests to run and one command that runs them.",
            listOf(Param("depth", "integer", "1-6 (default 3)"), budget)) { a -> queries.impactOfChanges(a.int("depth", 3), a.budget()) }
        tool("community", "A community: summary, central classes, members, children.", listOf(Param("id", "string", "community id, e.g. community:L0-3", true), budget)) { a -> queries.community(a.str("id"), a.budget()) }
        tool("communities", "List communities, optionally filtered by label, package or summary text.", listOf(Param("query", "string", "filter text"), budget)) { a -> queries.communities(a.strOrNull("query"), a.budget()) }
        tool("flow", "The precomputed end-to-end step list for an entry point, by flow id, handler method id, route (GET /orders) or topic.",
            listOf(Param("entry", "string", "flow id, method id, route or topic", true), budget)) { a -> queries.flow(a.str("entry"), a.budget()) }
        tool("routes", "HTTP routes with handlers and flows.", listOf(Param("prefix", "string", "path prefix filter"), budget)) { a -> queries.routes(a.strOrNull("prefix"), a.budget()) }
        tool("topics", "Message topics with consumers and producers.", listOf(budget)) { a -> queries.topics(a.budget()) }
        tool("config", "Configuration keys, their values, files and the code bound to them.", listOf(Param("key_prefix", "string", "key prefix filter"), budget)) { a -> queries.config(a.strOrNull("key_prefix"), a.budget()) }
        tool("beans", "Spring beans with providers and injection points.", listOf(Param("type", "string", "type or name filter"), budget)) { a -> queries.beans(a.strOrNull("type"), a.budget()) }
        tool("findings", "Findings: dead code, cycles, layer violations, proxy self-invocation, undefined config, conflicts, SARIF.",
            listOf(Param("kind", "string", "finding kind"), Param("severity", "string", "error | warning | info"), Param("node", "string", "only findings on this node"), budget)) { a -> queries.findings(a.strOrNull("kind"), a.strOrNull("severity"), a.strOrNull("node"), a.budget()) }
        tool("dependencies", "Internal and external artifacts per module, with version conflicts.", listOf(Param("module", "string", "module name, e.g. :app"), budget)) { a -> queries.dependencies(a.strOrNull("module"), a.budget()) }

        server.addResource("jirrafe://report", "GRAPH_REPORT.md", "The generated architecture report: modules, jars, communities, layers, flows, findings.", "text/markdown") {
            ReadResourceResult(listOf(TextResourceContents(if (Files.exists(reportFile)) Files.readString(reportFile) else "run `jirrafe knowledge` first", "jirrafe://report", "text/markdown")))
        }
        for (f in store.nodes(NodeKind.FLOW)) {
            val uri = "jirrafe://flow/${f.id.removePrefix("flow:")}"
            server.addResource(uri, f.fqn, "Flow: ${f.attrs["summary"] ?: f.fqn}", "application/json") {
                ReadResourceResult(listOf(TextResourceContents(Queries.json.encodeToString(JsonElement.serializer(), queries.flow(f.id)), uri, "application/json")))
            }
        }
    }

    private fun tool(name: String, description: String, params: List<Param>, handler: (JsonObject) -> JsonObject) {
        val schema = ToolSchema(
            properties = buildJsonObject { for (p in params) put(p.name, buildJsonObject { put("type", p.type); put("description", p.description) }) },
            required = params.filter { it.required }.map { it.name }.ifEmpty { null },
        )
        server.addTool(name, description, schema, toolAnnotations = ToolAnnotations(readOnlyHint = true, idempotentHint = true)) { request ->
            val args = request.params.arguments ?: JsonObject(emptyMap())
            try {
                val result = handler(args)
                CallToolResult(listOf(TextContent(Queries.json.encodeToString(JsonElement.serializer(), result))), isError = result.containsKey("error"), structuredContent = result)
            } catch (e: Exception) {
                CallToolResult(listOf(TextContent("error: ${e.message ?: e.toString()}")), isError = true)
            }
        }
    }

    private fun JsonObject.str(name: String, default: String = ""): String = this[name]?.jsonPrimitive?.content ?: default
    private fun JsonObject.strOrNull(name: String): String? = this[name]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(name: String, default: Int): Int = this[name]?.jsonPrimitive?.content?.toIntOrNull() ?: default
    private fun JsonObject.num(name: String, default: Double): Double = this[name]?.jsonPrimitive?.content?.toDoubleOrNull() ?: default
    private fun JsonObject.bool(name: String): Boolean = this[name]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
    private fun JsonObject.budget(): Int = int("token_budget", defaultBudget).coerceAtLeast(200)
    private fun JsonObject.kinds(): Set<NodeKind>? = strOrNull("kinds")?.split(',')?.mapNotNull { k -> NodeKind.entries.firstOrNull { it.name.equals(k.trim(), ignoreCase = true) } }?.toSet()?.ifEmpty { null }
    private fun JsonObject.edgeKinds(): Set<EdgeKind>? = strOrNull("edge_types")?.split(',')?.mapNotNull { k -> EdgeKind.entries.firstOrNull { it.name.equals(k.trim(), ignoreCase = true) } }?.toSet()?.ifEmpty { null }

    /** Blocks until the client closes stdin. Nothing but JSON-RPC may be written to stdout. */
    suspend fun serveStdio(out: java.io.OutputStream = System.out) {
        val transport = StdioServerTransport(System.`in`.asSource().buffered(), out.asSink().buffered())
        val done = CompletableDeferred<Unit>()
        val session = server.createSession(transport)
        session.onClose { done.complete(Unit) }
        done.await()
    }

    fun serveHttp(port: Int, host: String = "127.0.0.1") {
        embeddedServer(CIO, port = port, host = host) { mcpStreamableHttp { server } }.start(wait = true)
    }

    companion object {
        fun version(): String = McpServer::class.java.`package`?.implementationVersion ?: "dev"

        val INSTRUCTIONS = """
            jirrafe is a precomputed code graph of this JVM project, including the code inside its internal jars.
            Call explain(question) before exploring; it returns the relevant flows (end-to-end step lists), communities and nodes with file:line citations.
            Prefer these tools over grep for structure: callers, dispatch targets, Spring wiring, routes, topics and config keys are exact here.
            Trust edges with resolution exact or spring over heuristic ones. Call impact(id) before changing a method. Cite file:line from the answers.
        """.trimIndent()

        fun toolNames(): List<String> = listOf(
            "explain", "overview", "search", "get_node", "read_source", "neighbors", "path", "impact", "impact_of_changes", "community", "communities",
            "flow", "routes", "topics", "config", "beans", "findings", "dependencies",
        )
    }
}
