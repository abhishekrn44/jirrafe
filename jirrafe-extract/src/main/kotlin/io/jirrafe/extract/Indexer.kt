package io.jirrafe.extract

import io.jirrafe.core.analysis.Cha
import io.jirrafe.core.manifest.Manifest
import io.jirrafe.core.manifest.ManifestArtifact
import io.jirrafe.core.manifest.ManifestModule
import io.jirrafe.core.model.Edge
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Origin
import io.jirrafe.core.model.Resolution
import io.jirrafe.core.plugin.FrameworkPlugin
import io.jirrafe.core.plugin.IndexContext
import io.jirrafe.core.store.GraphStore
import io.jirrafe.extract.bytecode.BytecodeExtractor
import io.jirrafe.extract.source.SourceExtractor
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

/** Hops into an internal jar from the code that uses it; what lies beyond stays a stub. */
private const val JAR_DEPTH = 2

/**
 * Builds the graph from a manifest. Units of work are modules and internal artifacts; each has a
 * content hash (sources, classpath, jars) stored in `meta`, and unchanged units are skipped.
 * A changed unit is deleted and re-extracted: bytecode first (insert-or-ignore), then source
 * (insert-or-replace) so parsed declarations win and bytecode fills what source could not resolve.
 *
 * @param workDir the `.jirrafe` directory; holds extracted sources jars and generated sources.
 */
class Indexer(
    private val store: GraphStore,
    workDir: Path,
    private val plugins: List<FrameworkPlugin> = emptyList(),
    private val log: (String) -> Unit = {},
) {
    /** Kinds that framework plugins own; cleared before every plugin run. */
    private val pluginKinds = setOf(
        NodeKind.BEAN, NodeKind.HTTP_ROUTE, NodeKind.CONFIG_KEY, NodeKind.MESSAGE_TOPIC, NodeKind.SCHEDULED_JOB, NodeKind.FINDING,
    )

    private val workDir: Path = workDir.toAbsolutePath().normalize()

    data class Stats(
        val modules: Int, val internalJars: Int, val skippedUnits: Int, val classes: Int,
        val sourceFiles: Int,
        /** Compile errors in the repo's own sources. */
        val sourceErrors: Int,
        /** Compile errors in sources jars, expected when a jar's compile-only dependencies are absent. */
        val jarSourceErrors: Int,
        val stubs: Int, val dispatches: Int, val nodes: Long, val edges: Long,
    )

    fun index(manifest: Manifest): Stats {
        val bytecode = BytecodeExtractor(store)
        val source = SourceExtractor(store)
        val root = Path.of(manifest.root)
        store.deleteStubs() // derived from dangling edges and recreated below; a jar that turned internal must not find its stubs in the way
        store.deleteEdges(EdgeKind.DISPATCHES_TO) // recomputed at the end; old ones would resurrect stubs for implementations that left
        var skipped = 0
        var classes = 0
        var sourceFiles = 0
        var sourceErrors = 0
        var jarSourceErrors = 0

        for (m in manifest.modules) {
            val moduleId = "module:${m.name}"
            val mainSources = javaFiles(m.sourceDirs)
            val testSources = javaFiles(m.testSourceDirs)
            val classpath = classpath(m, "compileClasspath")
            val testClasspath = classpath(m, "testCompileClasspath")
            val classesDirs = m.classesDirs.map(Path::of).filter { Files.isDirectory(it) }
            val sha = sha(
                (mainSources + testSources).map { it.toString() + ":" + fileSha(it) } +
                    (classpath + testClasspath + m.annotationProcessorPath + m.testAnnotationProcessorPath).map { it.toString() } +
                    classesDirs.map { it.toString() } + listOf(m.javaVersion.toString())
            )
            if (store.meta("unit:$moduleId") == sha) {
                skipped++
                continue
            }
            log("module ${m.name}")
            store.deleteUnit(module = m.name)
            store.node(
                Node(
                    moduleId, NodeKind.MODULE, m.name, Origin.REPO, file = m.dir, module = m.name,
                    attrs = buildMap {
                        put("group", m.group); put("version", m.version)
                        m.javaHome?.let { put("javaHome", it) }
                        m.javaVersion?.let { put("javaVersion", it.toString()) }
                    },
                )
            )
            for (dir in classesDirs) {
                classes += bytecode.extractDir(dir, BytecodeExtractor.Context(Origin.BYTECODE, m.name, null, moduleId))
            }
            for ((files, cp, processors, test) in listOf(
                Quad(mainSources, classpath, m.annotationProcessorPath, false),
                Quad(testSources, testClasspath, m.testAnnotationProcessorPath, true),
            )) {
                if (files.isEmpty()) continue
                fun options(processorPath: List<Path>) = SourceExtractor.Options(
                    root = root, classpath = classesDirs + cp, processorPath = processorPath, // own classes first: a released version of this very library may sit on the classpath
                    generatedDir = workDir.resolve("generated").resolve(safe(m.name)).resolve(if (test) "test" else "main"),
                    release = m.javaVersion, origin = Origin.REPO, module = m.name, owner = moduleId, test = test,
                )
                // a processor built for a newer JDK than the one running jirrafe (Error Prone on Spring Framework) kills
                // javac in-process; retry without processors, and never let one module's source tier abort the index
                val r = try {
                    source.extract(files, options(processors.map(Path::of)))
                } catch (e: Throwable) {
                    log("  source tier of ${m.name} failed with ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.first()}; retrying without annotation processors")
                    try {
                        source.extract(files, options(emptyList()))
                    } catch (e2: Throwable) {
                        log("  source tier of ${m.name} skipped: ${e2.message?.lineSequence()?.first()}; bytecode only")
                        SourceExtractor.Result(0, 0, listOf(e2.toString()))
                    }
                }
                sourceFiles += r.files
                sourceErrors += r.errors.size
                if (r.errors.isNotEmpty()) {
                    log("  ${r.errors.size} compile errors in ${m.name}; bytecode covers those calls")
                    r.errors.take(5).forEach { log("    $it") }
                }
            }
            for (c in m.configurations) for (a in c.artifacts) {
                val target = if (a.classification == "project") a.module?.let { "module:$it" } else "artifact:${a.coordinate}"
                target?.let { store.edge(Edge(moduleId, it, EdgeKind.DEPENDS_ON_ARTIFACT, Resolution.EXACT)) }
            }
            store.flush()
            store.setMeta("unit:$moduleId", sha)
        }

        // Artifacts once each, with the classpath of the first module that uses them (a superset of their own).
        val seen = HashSet<String>()
        var internalJars = 0
        for (m in manifest.modules) for (c in m.configurations) for (a in c.artifacts) {
            if (a.classification == "project" || !seen.add(a.coordinate)) continue
            val artifactId = "artifact:${a.coordinate}"
            val internal = a.classification == "internal"
            val jar = a.file?.let(Path::of)?.takeIf { it.isRegularFile() && it.extension == "jar" }
            val sourcesJar = a.sourcesJar?.let(Path::of)?.takeIf { it.isRegularFile() }
            // an internal jar is cut to what reaches it, so its unit also changes when the usage does
            fun jarSha(packages: List<String>) = sha(listOfNotNull(jar?.let(::fileSha), sourcesJar?.let(::fileSha)) + classpath(m, c.name).map { it.toString() } + store.usageOf(a.coordinate, packages))
            val sha = if (internal) jarSha(store.meta("packages:$artifactId")?.lines().orEmpty()) else a.coordinate
            if (store.meta("unit:$artifactId") == sha && store.node(artifactId) != null) {
                if (internal) skipped++
                continue
            }
            store.deleteUnit(artifact = a.coordinate)
            store.node(artifactNode(artifactId, a, internal))
            if (internal && jar != null) {
                log("artifact ${a.coordinate}")
                internalJars++
                classes += bytecode.extractJar(jar, BytecodeExtractor.Context(Origin.BYTECODE, null, a.coordinate, artifactId))
                if (sourcesJar != null) {
                    val dir = workDir.resolve("sources").resolve(safe(a.coordinate))
                    unzip(sourcesJar, dir)
                    val r = source.extract(
                        javaFiles(listOf(dir.toString())), SourceExtractor.Options(
                            root = root, classpath = listOf(jar) + classpath(m, c.name),
                            generatedDir = workDir.resolve("generated").resolve(safe(a.coordinate)), release = m.javaVersion,
                            origin = Origin.SOURCES_JAR, artifact = a.coordinate, owner = artifactId,
                        )
                    )
                    sourceFiles += r.files
                    jarSourceErrors += r.errors.size
                    if (r.errors.isNotEmpty()) {
                        log("  ${r.errors.size} compile errors in ${a.coordinate} sources; bytecode covers those calls")
                        r.errors.take(5).forEach { log("    $it") }
                    }
                }
                val packages = store.packagesOf(a.coordinate) // before the cut: usage inside a dropped package must still count
                val dropped = store.pruneUnreachable(a.coordinate, JAR_DEPTH)
                if (dropped > 0) log("  kept the classes reached from outside the jar; dropped $dropped")
                store.setMeta("packages:$artifactId", packages.joinToString("\n"))
                store.setMeta("unit:$artifactId", jarSha(packages))
                continue
            }
            store.flush()
            store.setMeta("unit:$artifactId", sha)
        }

        store.deleteKinds(setOf(NodeKind.DOC)) // prose is re-read every build: cheap, and it links to whatever the units just produced
        val docs = io.jirrafe.extract.docs.DocExtractor(store).extract(root)
        if (docs > 0) log("docs: $docs sections")
        val stubs = store.createStubs()
        store.pruneOrphans()
        store.deleteKinds(pluginKinds)
        val ctx = object : IndexContext {
            override val manifest = manifest
            override val store = this@Indexer.store
            override fun log(message: String) = this@Indexer.log(message)
        }
        for (plugin in plugins) {
            log("plugin ${plugin.id}")
            plugin.contribute(ctx)
            store.flush()
        }
        val dispatches = Cha.compute(store)
        store.pruneOrphans() // plugins and dispatch may have retired the last edge of a stub
        store.rebuildSearchIndex()
        store.setMeta("schema", "1")
        store.setMeta("buildTool", manifest.buildTool)
        store.setMeta("root", manifest.root)
        io.jirrafe.core.store.Git.head(Path.of(manifest.root))?.let { store.setMeta("commit", it) } // lets queries say when the graph is behind
        return Stats(
            manifest.modules.size, internalJars, skipped, classes, sourceFiles, sourceErrors, jarSourceErrors, stubs, dispatches,
            store.count("nodes"), store.count("edges"),
        )
    }

    private data class Quad(val files: List<Path>, val classpath: List<Path>, val processors: List<String>, val test: Boolean)

    private fun artifactNode(id: String, a: ManifestArtifact, internal: Boolean) = Node(
        id, NodeKind.ARTIFACT, a.coordinate,
        origin = when {
            !internal -> Origin.EXTERNAL
            a.sourcesJar != null -> Origin.SOURCES_JAR
            else -> Origin.BYTECODE
        },
        file = a.file, artifact = a.coordinate,
        attrs = buildMap {
            put("group", a.group ?: ""); put("name", a.name ?: ""); put("version", a.version ?: "")
            put("classification", a.classification)
            a.sourcesJar?.let { put("sourcesJar", it) }
        },
    )

    private fun classpath(m: ManifestModule, configuration: String): List<Path> =
        m.configurations.firstOrNull { it.name == configuration }?.artifacts?.mapNotNull { it.file?.let(Path::of) } ?: emptyList()

    private fun javaFiles(dirs: List<String>): List<Path> = dirs.map(Path::of).filter { Files.isDirectory(it) }
        .flatMap { dir -> dir.walk().filter { it.extension == "java" && it.fileName.toString() != "module-info.java" }.toList() }
        .sorted()

    private fun unzip(zip: Path, dir: Path) {
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        ZipInputStream(Files.newInputStream(zip)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val target = dir.resolve(e.name).normalize()
                if (!target.startsWith(dir) || e.isDirectory) continue
                Files.createDirectories(target.parent)
                Files.copy(zin, target)
            }
        }
    }

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')

    private fun sha(parts: List<String>): String = sha1(parts.joinToString("\n").toByteArray())

    private fun fileSha(p: Path): String {
        val md = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(p).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}
