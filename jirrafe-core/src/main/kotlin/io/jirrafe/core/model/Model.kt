package io.jirrafe.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class NodeKind {
    MODULE, ARTIFACT, PACKAGE, FILE,
    CLASS, INTERFACE, ENUM, RECORD, ANNOTATION,
    METHOD, CONSTRUCTOR, FIELD,
    CONFIG_KEY, HTTP_ROUTE, MESSAGE_TOPIC, SCHEDULED_JOB, BEAN, TEST,
    COMMUNITY, FLOW, FINDING,
    /** A section of a Markdown file in the repository (README, docs, ADRs), by heading. */
    DOC,
}

@Serializable
enum class EdgeKind {
    CONTAINS, IMPORTS, EXTENDS, IMPLEMENTS, OVERRIDES,
    CALLS, DISPATCHES_TO, USES_TYPE, READS_FIELD, WRITES_FIELD, ANNOTATED_WITH,
    INJECTS, PROVIDES_BEAN, HANDLES_ROUTE, PRODUCES_TO, CONSUMES_FROM, CALLS_REMOTE,
    BINDS_CONFIG, MAPS_TO_TABLE, TESTS,
    MEMBER_OF_COMMUNITY, STEP_OF_FLOW, DEPENDS_ON_ARTIFACT, HAS_FINDING,
    /** A doc section names a class or member. */
    MENTIONS,
}

/** How an edge's target was determined. Agents should trust EXACT and SPRING over HEURISTIC. */
@Serializable
enum class Resolution { EXACT, CHA, SPRING, HEURISTIC }

/** Where a node's code came from. EXTERNAL nodes are never expanded. */
@Serializable
enum class Origin { REPO, SOURCES_JAR, BYTECODE, EXTERNAL }

/**
 * Node ids are readable and stable: a class is its binary name (`com.acme.Outer$Inner`), a member
 * is `class#name` for fields and `class#name(argType,argType)` for methods, a package is its name,
 * a module is `module:<name>`, an artifact is `artifact:<group:name:version>`.
 */
@Serializable
data class Node(
    val id: String,
    val kind: NodeKind,
    val fqn: String,
    val origin: Origin,
    val signature: String? = null,
    val file: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val doc: String? = null,
    val sha: String? = null,
    val module: String? = null,
    val artifact: String? = null,
    /** Kind-specific extras (annotation values, string constants, route verb and path...). */
    val attrs: Map<String, String> = emptyMap(),
)

@Serializable
data class Edge(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val resolution: Resolution,
    val confidence: Double = 1.0,
)

/** Where extractors send what they find. Nodes are insert-or-ignore unless [replace] is set. */
interface GraphSink {
    fun node(node: Node, replace: Boolean = false)
    fun edge(edge: Edge)
}
