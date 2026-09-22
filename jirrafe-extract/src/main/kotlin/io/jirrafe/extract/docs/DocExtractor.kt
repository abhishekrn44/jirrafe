package io.jirrafe.extract.docs

import io.jirrafe.core.model.Edge
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Origin
import io.jirrafe.core.model.Resolution
import io.jirrafe.core.store.GraphStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import io.jirrafe.core.config.Config
import kotlin.io.path.walk

/**
 * Markdown in the repository as graph nodes, one per heading section (`doc:<path>#<slug>`), searchable by
 * heading and text, cited by file and line, and linked with MENTIONS to the classes and members the
 * section names. Prose answers "why" and "how do I use" questions that no method body holds.
 */
class DocExtractor(private val store: GraphStore) {
    private companion object {
        // .claude holds the skill jirrafe installs, .idea and .vscode an editor's notes: none of them is the project's
        // documentation. `docs.skip_dirs` in jirrafe.toml adds to the list
        val SKIP = setOf(".git", ".jirrafe", ".claude", ".idea", ".vscode", "build", "target", "node_modules", "out")
        val NOISE = Regex("""^(changelog|changes|history|release[-_ ]?notes|releases)\b""", RegexOption.IGNORE_CASE) // every class name ever touched, no explanation
        val IDENT = Regex("""\b([A-Z][A-Za-z0-9]+)(?:\.([a-z][A-Za-z0-9]*)\()?""")
        const val MAX_FILES = 500
        const val MAX_TEXT = 400
    }

    fun extract(root: Path): Int {
        val classes = HashMap<String, MutableList<String>>() // simple name -> class ids
        for (k in listOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.ENUM, NodeKind.RECORD)) for (c in store.nodes(k)) {
            if (c.origin == Origin.REPO) classes.getOrPut(c.id.substringAfterLast('.').substringAfterLast('$')) { ArrayList() } += c.id
        }
        var sections = 0
        val skip = SKIP + Config.load(root).list("docs.skip_dirs")
        val files = root.walk().filter { p -> p.isRegularFile() && p.extension.equals("md", true) && !NOISE.containsMatchIn(p.name) && p.relativeTo(root).none { it.name in skip } }.take(MAX_FILES)
        for (file in files) {
            val rel = file.relativeTo(root).toString().replace('\\', '/')
            val lines = runCatching { Files.readAllLines(file) }.getOrNull() ?: continue
            var start = 0
            var heading = file.name
            fun flush(end: Int) {
                val body = lines.subList(start, end)
                val text = body.filterNot { it.startsWith("#") }.joinToString(" ") { it.trim() }.trim()
                if (text.isEmpty()) return
                val id = "doc:$rel#" + heading.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                store.node(Node(id, NodeKind.DOC, heading, Origin.REPO, file = file.toString(), startLine = start + 1, endLine = end, doc = text.take(MAX_TEXT)))
                sections++
                val seen = HashSet<String>()
                for (m in IDENT.findAll(body.joinToString("\n"))) {
                    val ids = classes[m.groupValues[1]] ?: continue
                    val member = m.groupValues[2]
                    for (cls in ids) {
                        val target = if (member.isNotEmpty()) store.edgesFrom(cls, EdgeKind.CONTAINS).map { it.to }.firstOrNull { it.substringAfter('#').substringBefore('(') == member } ?: cls else cls
                        if (seen.add(target)) store.edge(Edge(id, target, EdgeKind.MENTIONS, Resolution.HEURISTIC, 0.8))
                    }
                }
            }
            for ((i, line) in lines.withIndex()) {
                if (line.startsWith("#")) {
                    flush(i)
                    start = i; heading = line.trimStart('#').trim().ifEmpty { file.name }
                }
            }
            flush(lines.size)
        }
        store.flush()
        return sections
    }
}
