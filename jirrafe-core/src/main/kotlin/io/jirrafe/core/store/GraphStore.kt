package io.jirrafe.core.store

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import io.jirrafe.core.model.Edge
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.GraphSink
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Origin
import io.jirrafe.core.model.Resolution
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * SQLite-backed graph (`.jirrafe/graph.db`). One writer connection, batched inserts inside a
 * transaction; call [flush] to commit. Nodes are insert-or-ignore by default (the first writer of an id
 * wins); a replace overwrites the columns and merges `attrs`.
 */
class GraphStore private constructor(private val conn: Connection) : GraphSink, AutoCloseable {

    companion object {
        private const val BATCH = 1000
        private val json = Json { encodeDefaults = false }
        private val attrsSerializer = MapSerializer(String.serializer(), String.serializer())
        private val NODE_COLUMNS =
            "id, kind, fqn, origin, signature, file, start_line, end_line, doc, sha, module, artifact, attrs"
        private val SCHEMA = """
            CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT);
            CREATE TABLE IF NOT EXISTS nodes(
                id TEXT PRIMARY KEY, kind TEXT NOT NULL, fqn TEXT NOT NULL, origin TEXT NOT NULL,
                signature TEXT, file TEXT, start_line INTEGER, end_line INTEGER, doc TEXT, sha TEXT,
                module TEXT, artifact TEXT, attrs TEXT);
            CREATE INDEX IF NOT EXISTS nodes_kind ON nodes(kind);
            CREATE INDEX IF NOT EXISTS nodes_module ON nodes(module);
            CREATE TABLE IF NOT EXISTS edges(
                src TEXT NOT NULL, dst TEXT NOT NULL, kind TEXT NOT NULL, resolution TEXT NOT NULL,
                confidence REAL NOT NULL, PRIMARY KEY(src, dst, kind)) WITHOUT ROWID;
            CREATE INDEX IF NOT EXISTS edges_dst ON edges(dst, kind);
            CREATE VIRTUAL TABLE IF NOT EXISTS nodes_fts USING fts5(id UNINDEXED, terms, signature, doc);
        """

        fun open(path: Path): GraphStore {
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            return connect("jdbc:sqlite:${path.toAbsolutePath()}")
        }

        fun inMemory(): GraphStore = connect("jdbc:sqlite::memory:")

        private fun connect(url: String): GraphStore {
            val c = DriverManager.getConnection(url)
            c.createStatement().use { s ->
                s.execute("PRAGMA journal_mode=WAL")
                s.execute("PRAGMA synchronous=NORMAL")
                SCHEMA.split(";").filter { it.isNotBlank() }.forEach { s.execute(it) }
            }
            c.autoCommit = false
            return GraphStore(c)
        }

        /** Search terms for an fqn: separators become spaces and camelCase words are split out too. */
        /** A node's annotations as words: the simple name of each and every value, `PreAuthorize hasRole ADMIN`. */
        fun annotationTerms(attrs: String?): String {
            val encoded = runCatching { attrs?.let { kotlinx.serialization.json.Json.parseToJsonElement(it) }?.jsonObject?.get(io.jirrafe.core.model.Attrs.ANNOTATIONS)?.jsonPrimitive?.content }.getOrNull() ?: return ""
            val decoded = runCatching { io.jirrafe.core.model.Attrs.decodeAnnotations(encoded) }.getOrNull() ?: return ""
            return " " + terms(decoded.entries.joinToString(" ") { (fqn, members) -> fqn.substringAfterLast('.') + " " + members.values.joinToString(" ") })
        }

        fun terms(fqn: String): String {
            val words = fqn.replace(Regex("[.#$(),\\[\\]<>]+"), " ").trim()
            val camel = words.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            return if (camel == words) words else "$words $camel"
        }
    }

    private val insertNode = conn.prepareStatement(
        "INSERT OR IGNORE INTO nodes($NODE_COLUMNS) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)"
    )
    // Replace keeps attrs the new node does not set (json_patch), so source nodes never lose bytecode facts.
    private val replaceNode = conn.prepareStatement(
        "INSERT INTO nodes($NODE_COLUMNS) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET " +
            "kind = excluded.kind, fqn = excluded.fqn, origin = excluded.origin, signature = excluded.signature, " +
            "file = excluded.file, start_line = excluded.start_line, end_line = excluded.end_line, doc = excluded.doc, " +
            "sha = excluded.sha, module = excluded.module, artifact = excluded.artifact, " +
            "attrs = json_patch(coalesce(nodes.attrs, '{}'), coalesce(excluded.attrs, '{}'))"
    )
    private val insertEdge = conn.prepareStatement(
        "INSERT OR IGNORE INTO edges(src, dst, kind, resolution, confidence) VALUES(?,?,?,?,?)"
    )
    private var pending = 0

    override fun node(node: Node, replace: Boolean) {
        val st = if (replace) replaceNode else insertNode
        st.setString(1, node.id)
        st.setString(2, node.kind.name)
        st.setString(3, node.fqn)
        st.setString(4, node.origin.name)
        st.setString(5, node.signature)
        st.setString(6, node.file)
        st.setObject(7, node.startLine)
        st.setObject(8, node.endLine)
        st.setString(9, node.doc)
        st.setString(10, node.sha)
        st.setString(11, node.module)
        st.setString(12, node.artifact)
        st.setString(13, if (node.attrs.isEmpty()) null else json.encodeToString(attrsSerializer, node.attrs))
        st.addBatch()
        batched()
    }

    override fun edge(edge: Edge) {
        insertEdge.setString(1, edge.from)
        insertEdge.setString(2, edge.to)
        insertEdge.setString(3, edge.kind.name)
        insertEdge.setString(4, edge.resolution.name)
        insertEdge.setDouble(5, edge.confidence)
        insertEdge.addBatch()
        batched()
    }

    private fun batched() {
        if (++pending >= BATCH) flush()
    }

    /** Executes pending inserts and commits. */
    fun flush() {
        insertNode.executeBatch()
        replaceNode.executeBatch()
        insertEdge.executeBatch()
        conn.commit()
        pending = 0
    }

    fun node(id: String): Node? =
        query("SELECT $NODE_COLUMNS FROM nodes WHERE id = ?", id).firstOrNull()

    fun nodes(kind: NodeKind? = null): List<Node> =
        if (kind == null) query("SELECT $NODE_COLUMNS FROM nodes ORDER BY id")
        else query("SELECT $NODE_COLUMNS FROM nodes WHERE kind = ? ORDER BY id", kind.name)

    fun edgesFrom(id: String, kind: EdgeKind? = null): List<Edge> =
        edges("src", id, kind)

    fun edgesTo(id: String, kind: EdgeKind? = null): List<Edge> =
        edges("dst", id, kind)

    private fun edges(column: String, id: String, kind: EdgeKind?): List<Edge> {
        val sql = "SELECT src, dst, kind, resolution, confidence FROM edges WHERE $column = ?" +
            (if (kind == null) "" else " AND kind = ?")
        conn.prepareStatement(sql).use { st ->
            st.setString(1, id)
            if (kind != null) st.setString(2, kind.name)
            st.executeQuery().use { rs ->
                val out = ArrayList<Edge>()
                while (rs.next()) out += Edge(
                    rs.getString(1), rs.getString(2), EdgeKind.valueOf(rs.getString(3)),
                    Resolution.valueOf(rs.getString(4)), rs.getDouble(5),
                )
                return out
            }
        }
    }

    /** BM25 search over names, signatures and docs; every word must match as a prefix. */
    fun search(text: String, limit: Int = 20, kinds: Set<NodeKind>? = null): List<Node> {
        val words = text.split(Regex("[^\\p{Alnum}_]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val match = words.joinToString(" ") { "\"$it\"*" }
        val kindFilter = if (kinds.isNullOrEmpty()) "" else
            " AND n.kind IN (${kinds.joinToString(",") { "'${it.name}'" }})"
        val sql = "SELECT ${NODE_COLUMNS.split(", ").joinToString(", ") { "n.$it" }} FROM nodes_fts f " +
            "JOIN nodes n ON n.id = f.id WHERE nodes_fts MATCH ?$kindFilter ORDER BY bm25(nodes_fts) LIMIT $limit"
        return query(sql, match)
    }

    /** Rebuilds the search table from `nodes`. Call once after indexing. */
    fun rebuildSearchIndex() {
        conn.createStatement().use { it.execute("DELETE FROM nodes_fts") }
        conn.prepareStatement("INSERT INTO nodes_fts(id, terms, signature, doc) VALUES(?,?,?,?)").use { st ->
            conn.createStatement().use { s ->
                s.executeQuery("SELECT id, fqn, signature, doc, attrs FROM nodes").use { rs ->
                    var n = 0
                    while (rs.next()) {
                        st.setString(1, rs.getString(1))
                        // what an annotation says is searchable: "admin" is nowhere in a name, and everywhere in
                        // @PreAuthorize("hasRole('ADMIN')") on the one method the question means
                        st.setString(2, terms(rs.getString(2)) + annotationTerms(rs.getString(5)))
                        st.setString(3, rs.getString(3))
                        st.setString(4, rs.getString(4))
                        st.addBatch()
                        if (++n % BATCH == 0) st.executeBatch()
                    }
                    st.executeBatch()
                }
            }
        }
        conn.commit()
    }

    /**
     * Creates EXTERNAL stub nodes for every edge target that has no node, so calls into
     * un-indexed code still land somewhere. Member stubs get a stub class and a CONTAINS edge.
     */
    fun createStubs(): Int {
        flush()
        val missing = ArrayList<String>()
        val annotations = HashSet<String>()
        conn.createStatement().use { s ->
            s.executeQuery("SELECT DISTINCT dst FROM edges e WHERE NOT EXISTS (SELECT 1 FROM nodes n WHERE n.id = e.dst)")
                .use { rs -> while (rs.next()) missing += rs.getString(1) }
            s.executeQuery("SELECT DISTINCT dst FROM edges WHERE kind = 'ANNOTATED_WITH'")
                .use { rs -> while (rs.next()) annotations += rs.getString(1) }
        }
        for (id in missing) {
            val member = id.contains('#')
            val kind = when {
                id.contains("#<init>(") -> NodeKind.CONSTRUCTOR
                id.contains('(') -> NodeKind.METHOD
                member -> NodeKind.FIELD
                id in annotations -> NodeKind.ANNOTATION
                else -> NodeKind.CLASS
            }
            // an inherited member of an indexed type (OwnerRepository#save from JpaRepository) belongs to that type,
            // not to the outside world, so flows and findings keep walking through it
            val owner = if (member) node(id.substringBefore('#')) else null
            node(Node(id, kind, id, owner?.origin ?: Origin.EXTERNAL, module = owner?.module, artifact = owner?.artifact, attrs = if (owner != null) mapOf("inherited" to "true") else emptyMap()))
            if (member) {
                val ownerId = id.substringBefore('#')
                if (owner == null) node(Node(ownerId, NodeKind.CLASS, ownerId, Origin.EXTERNAL))
                edge(Edge(ownerId, id, EdgeKind.CONTAINS, Resolution.EXACT))
            }
        }
        flush()
        return missing.size
    }

    /** Removes everything one module or one artifact produced: its nodes and the edges leaving them. */
    fun deleteUnit(module: String? = null, artifact: String? = null) {
        flush()
        val column = if (module != null) "module" else "artifact"
        val value = module ?: requireNotNull(artifact)
        conn.prepareStatement("DELETE FROM edges WHERE src IN (SELECT id FROM nodes WHERE $column = ?)").use {
            it.setString(1, value); it.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM nodes WHERE $column = ?").use { it.setString(1, value); it.executeUpdate() }
        conn.commit()
    }

    /**
     * Keeps only the classes of [artifact] that code outside it reaches within [depth] hops over code edges; a reached
     * member keeps its whole class, so outlines stay complete. Returns the number of classes dropped. What a kept class
     * references beyond the depth becomes a stub again, like any third-party code.
     */
    fun pruneUnreachable(artifact: String, depth: Int): Int {
        flush()
        val classOf = HashMap<String, String>()
        conn.prepareStatement("SELECT id FROM nodes WHERE artifact = ?").use { st ->
            st.setString(1, artifact)
            st.executeQuery().use { rs -> while (rs.next()) { val id = rs.getString(1); classOf[id] = id.substringBefore('#') } }
        }
        if (classOf.isEmpty()) return 0
        val out = HashMap<String, MutableList<String>>()
        val seeds = HashSet<String>()
        conn.prepareStatement(
            "SELECT e.src, e.dst FROM edges e WHERE e.kind NOT IN ('CONTAINS','IMPORTS','TESTS','MEMBER_OF_COMMUNITY','STEP_OF_FLOW','HAS_FINDING') " +
                "AND e.dst IN (SELECT id FROM nodes WHERE artifact = ?)"
        ).use { st ->
            st.setString(1, artifact)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val src = rs.getString(1); val dst = rs.getString(2)
                    if (src in classOf) out.getOrPut(src) { ArrayList() } += dst else seeds += dst
                }
            }
        }
        // framework-wired classes (auto-configuration, entities, resources) are reached by an annotation, not by a call
        conn.prepareStatement("SELECT id, json_extract(attrs, '$.annotations') FROM nodes WHERE artifact = ? AND json_extract(attrs, '$.annotations') IS NOT NULL").use { st ->
            st.setString(1, artifact)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val names = kotlinx.serialization.json.Json.parseToJsonElement(rs.getString(2)).let { (it as? kotlinx.serialization.json.JsonObject)?.keys.orEmpty() }
                    if (names.any { !it.startsWith("java.lang.") && !it.startsWith("kotlin.") }) seeds += rs.getString(1)
                }
            }
        }
        val reached = HashSet<String>()
        var frontier: Set<String> = seeds
        repeat(depth + 1) {
            val next = HashSet<String>()
            for (n in frontier) {
                if (!reached.add(n)) continue
                classOf[n]?.let { c -> if (reached.add(c)) next += c } // the class node carries EXTENDS and IMPLEMENTS
                out[n]?.let { next += it }
            }
            frontier = next - reached
        }
        val kept = reached.mapNotNullTo(HashSet()) { classOf[it] }
        val dropped = classOf.values.toHashSet() - kept
        if (dropped.isEmpty()) return 0
        conn.createStatement().use { it.executeUpdate("CREATE TEMP TABLE IF NOT EXISTS drop_ids(id TEXT PRIMARY KEY)"); it.executeUpdate("DELETE FROM drop_ids") }
        conn.prepareStatement("INSERT OR IGNORE INTO drop_ids(id) VALUES(?)").use { st ->
            for ((id, cls) in classOf) if (cls in dropped) { st.setString(1, id); st.addBatch() }
            st.executeBatch()
        }
        conn.createStatement().use {
            it.executeUpdate("DELETE FROM edges WHERE src IN (SELECT id FROM drop_ids) OR dst IN (SELECT id FROM drop_ids)")
            it.executeUpdate("DELETE FROM nodes WHERE id IN (SELECT id FROM drop_ids)")
            it.executeUpdate("DELETE FROM drop_ids")
        }
        conn.commit()
        return dropped.size
    }

    /** Ids outside [artifact] that edges from outside it point at inside [packages]: the usage a pruned jar was cut to. */
    fun usageOf(artifact: String, packages: Collection<String>): List<String> {
        flush()
        if (packages.isEmpty()) return emptyList()
        val prefixes = packages.map { "$it." }
        val ids = ArrayList<String>()
        conn.prepareStatement("SELECT DISTINCT e.dst FROM edges e JOIN nodes n ON n.id = e.src WHERE n.artifact IS NOT ? AND n.origin != 'EXTERNAL'").use { st ->
            st.setString(1, artifact)
            st.executeQuery().use { rs -> while (rs.next()) { val d = rs.getString(1); if (prefixes.any { d.startsWith(it) }) ids += d } }
        }
        return ids.sorted()
    }

    /** Packages of the classes [artifact] currently holds. */
    fun packagesOf(artifact: String): List<String> {
        flush()
        val out = HashSet<String>()
        conn.prepareStatement("SELECT id FROM nodes WHERE artifact = ? AND kind IN ('CLASS','INTERFACE','ENUM','RECORD','ANNOTATION')").use { st ->
            st.setString(1, artifact)
            st.executeQuery().use { rs -> while (rs.next()) out += rs.getString(1).substringBefore('$').substringBeforeLast('.', "") }
        }
        return out.filter { it.isNotEmpty() }.sorted()
    }

    /** Drops every stub; [createStubs] makes them again at the end of a build for whatever is still missing. */
    fun deleteStubs(): Int {
        flush()
        val n = conn.createStatement().use {
            it.executeUpdate("DELETE FROM edges WHERE src IN (SELECT id FROM nodes WHERE origin = 'EXTERNAL')") // the CONTAINS edges between stubs, else they resurrect each other
            it.executeUpdate("DELETE FROM nodes WHERE origin = 'EXTERNAL'")
        }
        conn.commit()
        return n
    }

    /** Drops stubs nobody references any more, empty packages, and edges with a missing end. */
    fun pruneOrphans(): Int {
        flush()
        var total = 0
        conn.createStatement().use { s ->
            repeat(3) {
                total += s.executeUpdate(
                    "DELETE FROM nodes WHERE origin = 'EXTERNAL' " +
                        "AND NOT EXISTS (SELECT 1 FROM edges e WHERE e.dst = nodes.id) " +
                        "AND NOT EXISTS (SELECT 1 FROM edges e WHERE e.src = nodes.id)"
                )
                total += s.executeUpdate(
                    "DELETE FROM nodes WHERE kind = 'PACKAGE' " +
                        "AND NOT EXISTS (SELECT 1 FROM edges e WHERE e.src = nodes.id AND e.kind = 'CONTAINS')"
                )
                s.executeUpdate(
                    "DELETE FROM edges WHERE NOT EXISTS (SELECT 1 FROM nodes n WHERE n.id = edges.src) " +
                        "OR NOT EXISTS (SELECT 1 FROM nodes n WHERE n.id = edges.dst)"
                )
            }
        }
        conn.commit()
        return total
    }

    /** Removes every node of these kinds and the edges touching them (derived data before a rebuild). */
    fun deleteKinds(kinds: Set<NodeKind>) {
        flush()
        val list = kinds.joinToString(",") { "'${it.name}'" }
        conn.createStatement().use { s ->
            s.executeUpdate("DELETE FROM edges WHERE src IN (SELECT id FROM nodes WHERE kind IN ($list)) OR dst IN (SELECT id FROM nodes WHERE kind IN ($list))")
            s.executeUpdate("DELETE FROM nodes WHERE kind IN ($list)")
        }
        conn.commit()
    }

    /** Removes these nodes and every edge touching them. */
    fun deleteNodes(ids: Collection<String>) {
        if (ids.isEmpty()) return
        flush()
        conn.prepareStatement("DELETE FROM edges WHERE src = ? OR dst = ?").use { st ->
            for (id in ids) { st.setString(1, id); st.setString(2, id); st.addBatch() }
            st.executeBatch()
        }
        conn.prepareStatement("DELETE FROM nodes WHERE id = ?").use { st ->
            for (id in ids) { st.setString(1, id); st.addBatch() }
            st.executeBatch()
        }
        conn.commit()
    }

    /** Merges attributes into existing nodes (missing ids are ignored). Derived facts such as layer and community. */
    fun setAttrs(attrs: Map<String, Map<String, String>>) {
        if (attrs.isEmpty()) return
        flush()
        conn.prepareStatement("UPDATE nodes SET attrs = json_patch(coalesce(attrs, '{}'), ?) WHERE id = ?").use { st ->
            var n = 0
            for ((id, a) in attrs) {
                st.setString(1, json.encodeToString(attrsSerializer, a))
                st.setString(2, id)
                st.addBatch()
                if (++n % BATCH == 0) st.executeBatch()
            }
            st.executeBatch()
        }
        conn.commit()
    }

    /** Case-insensitive substring match on ids, for fuzzy lookups when full-text search finds nothing. */
    fun nodesLike(fragment: String, limit: Int = 20, kinds: Set<NodeKind>? = null): List<Node> {
        val kindFilter = if (kinds.isNullOrEmpty()) "" else " AND kind IN (${kinds.joinToString(",") { "'${it.name}'" }})"
        return query("SELECT $NODE_COLUMNS FROM nodes WHERE lower(id) LIKE ? ESCAPE '!'$kindFilter ORDER BY length(id) LIMIT $limit",
            "%" + fragment.lowercase().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%")
    }

    /** Nodes of the file whose path ends with a repo-relative [suffix] (forward slashes), whatever root and separators the graph was built with. */
    fun nodesInFileEndingWith(suffix: String): List<Node> =
        query("SELECT $NODE_COLUMNS FROM nodes WHERE replace(file, '\\', '/') = ? OR replace(file, '\\', '/') LIKE ? ORDER BY start_line", suffix, "%/$suffix")

    fun nodesInFile(file: String): List<Node> =
        query("SELECT $NODE_COLUMNS FROM nodes WHERE file = ? ORDER BY start_line", file)

    fun edges(kind: EdgeKind): List<Edge> = conn.prepareStatement(
        "SELECT src, dst, kind, resolution, confidence FROM edges WHERE kind = ?"
    ).use { st ->
        st.setString(1, kind.name)
        st.executeQuery().use { rs ->
            val out = ArrayList<Edge>()
            while (rs.next()) out += Edge(
                rs.getString(1), rs.getString(2), EdgeKind.valueOf(rs.getString(3)),
                Resolution.valueOf(rs.getString(4)), rs.getDouble(5),
            )
            out
        }
    }

    fun deleteEdges(kind: EdgeKind) {
        flush()
        conn.prepareStatement("DELETE FROM edges WHERE kind = ?").use { it.setString(1, kind.name); it.executeUpdate() }
        conn.commit()
    }

    /** Streams id and signature of every node of [kind] without materialising full nodes. */
    fun forEachSignature(kind: NodeKind, fn: (id: String, signature: String?) -> Unit) {
        conn.prepareStatement("SELECT id, signature FROM nodes WHERE kind = ?").use { st ->
            st.setString(1, kind.name)
            st.executeQuery().use { rs -> while (rs.next()) fn(rs.getString(1), rs.getString(2)) }
        }
    }

    fun count(table: String): Long = conn.createStatement().use { s ->
        s.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getLong(1) }
    }

    fun setMeta(key: String, value: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO meta(key, value) VALUES(?,?)").use {
            it.setString(1, key); it.setString(2, value); it.executeUpdate()
        }
        conn.commit()
    }

    fun meta(key: String): String? = conn.prepareStatement("SELECT value FROM meta WHERE key = ?").use { st ->
        st.setString(1, key)
        st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }

    /** `graph.json`: `{"nodes":[...],"edges":[...]}`, streamed row by row. */
    fun exportJson(path: Path) {
        flush()
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.newBufferedWriter(path).use { w ->
            w.write("{\"nodes\":[")
            conn.createStatement().use { s ->
                s.executeQuery("SELECT $NODE_COLUMNS FROM nodes ORDER BY id").use { rs ->
                    var first = true
                    while (rs.next()) {
                        if (!first) w.write(",")
                        first = false
                        w.write(json.encodeToString(Node.serializer(), readNode(rs)))
                    }
                }
            }
            w.write("],\"edges\":[")
            conn.createStatement().use { s ->
                s.executeQuery("SELECT src, dst, kind, resolution, confidence FROM edges ORDER BY src, dst, kind").use { rs ->
                    var first = true
                    while (rs.next()) {
                        if (!first) w.write(",")
                        first = false
                        val e = Edge(
                            rs.getString(1), rs.getString(2), EdgeKind.valueOf(rs.getString(3)),
                            Resolution.valueOf(rs.getString(4)), rs.getDouble(5),
                        )
                        w.write(json.encodeToString(Edge.serializer(), e))
                    }
                }
            }
            w.write("]}")
        }
    }

    private fun query(sql: String, vararg args: String): List<Node> =
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setString(i + 1, a) }
            st.executeQuery().use { rs ->
                val out = ArrayList<Node>()
                while (rs.next()) out += readNode(rs)
                out
            }
        }

    private fun readNode(rs: ResultSet): Node = Node(
        id = rs.getString(1),
        kind = NodeKind.valueOf(rs.getString(2)),
        fqn = rs.getString(3),
        origin = Origin.valueOf(rs.getString(4)),
        signature = rs.getString(5),
        file = rs.getString(6),
        startLine = rs.getObject(7)?.let { (it as Number).toInt() },
        endLine = rs.getObject(8)?.let { (it as Number).toInt() },
        doc = rs.getString(9),
        sha = rs.getString(10),
        module = rs.getString(11),
        artifact = rs.getString(12),
        attrs = rs.getString(13)?.let { json.decodeFromString(attrsSerializer, it) } ?: emptyMap(),
    )

    override fun close() {
        flush()
        listOf<PreparedStatement>(insertNode, replaceNode, insertEdge).forEach { it.close() }
        conn.close()
    }
}
