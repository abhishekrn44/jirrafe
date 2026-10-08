package io.jirrafe.core.store

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The two git facts the graph needs: the commit it was built at, and which sources moved since. */
object Git {
    private val HUNK = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@""")

    fun head(dir: Path): String? = run(dir, "rev-parse", "HEAD")?.trim()?.takeIf { it.length == 40 }

    /**
     * Java and Kotlin files under [dir] that differ between [commit] and the working tree, committed or not, as paths
     * relative to [dir]. `--relative`: [dir] may be one service of a repository that holds several, and a change to
     * another service is not a reason to call this one's graph stale (72 files in audio-fingerprint-service made every
     * answer about audio-ingestion-service hedge its line numbers).
     */
    fun changedSources(dir: Path, commit: String): List<String> =
        run(dir, "diff", "--name-only", "--relative", commit)?.lines()?.filter { it.endsWith(".java") || it.endsWith(".kt") }.orEmpty()

    /** Changed line ranges per Java or Kotlin file (repo-relative), working tree against `HEAD`; a pure deletion is the line it left behind. */
    fun changedRanges(dir: Path): Map<String, List<IntRange>> {
        val out = LinkedHashMap<String, MutableList<IntRange>>()
        var file: String? = null
        for (line in run(dir, "diff", "-U0", "--relative", "HEAD", "--", "*.java", "*.kt")?.lines().orEmpty()) {
            if (line.startsWith("+++ ")) file = line.removePrefix("+++ ").removePrefix("b/").takeIf { it != "/dev/null" }
            else if (line.startsWith("@@") && file != null) {
                val m = HUNK.find(line) ?: continue
                val from = m.groupValues[1].toInt(); val count = m.groupValues[2].toIntOrNull() ?: 1
                out.getOrPut(file) { ArrayList() } += from..maxOf(from, from + count - 1)
            }
        }
        return out
    }

    private fun run(dir: Path, vararg args: String): String? = try {
        // a background tool: never refresh the index. Two jirrafe processes on two services of one repository both ran
        // `git diff` at once, and the clone's index file was gone afterwards
        val p = ProcessBuilder("git", "--no-optional-locks", "-C", dir.toString(), *args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) out else null
    } catch (e: Exception) {
        null // no git, or not a repository: the graph simply carries no commit
    }
}
