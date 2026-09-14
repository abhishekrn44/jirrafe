package io.jirrafe.core.manifest

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** `.jirrafe/manifest.json` as written by the Gradle and Maven plugins (schema 1). */
@Serializable
data class Manifest(
    val schema: Int,
    val buildTool: String,
    val buildToolVersion: String? = null,
    val root: String,
    val modules: List<ManifestModule>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun read(path: Path): Manifest = json.decodeFromString(serializer(), Files.readString(path))

        /**
         * Jars matched by a glob in `deps.internal_jar_patterns` (against the path relative to the root, or the
         * absolute path) become internal: a legacy build's own jars in `lib/` reach the manifest without a group
         * and would otherwise be stubs. A file dependency gets `file:<stem>:local` as its coordinate.
         */
        fun reclassify(m: Manifest, patterns: List<String>): Manifest {
            if (patterns.isEmpty()) return m
            val fs = java.nio.file.FileSystems.getDefault()
            val matchers = patterns.map { fs.getPathMatcher("glob:$it") }
            val root = Path.of(m.root).toAbsolutePath().normalize()
            fun internal(a: ManifestArtifact): Boolean {
                val file = a.file?.let { Path.of(it).toAbsolutePath().normalize() } ?: return false
                val rel = runCatching { root.relativize(file) }.getOrNull()
                return matchers.any { it.matches(file) || (rel != null && it.matches(rel)) }
            }
            return m.copy(modules = m.modules.map { mod ->
                mod.copy(configurations = mod.configurations.map { c ->
                    c.copy(artifacts = c.artifacts.map { a ->
                        if (a.classification != "external" || !internal(a)) a
                        else if (a.group != null) a.copy(classification = "internal")
                        else a.copy(classification = "internal", group = "file", name = Path.of(a.file!!).fileName.toString().removeSuffix(".jar"), version = "local")
                    })
                })
            })
        }

        fun write(m: Manifest, path: Path) { Files.writeString(path, json.encodeToString(serializer(), m)) }
    }
}

@Serializable
data class ManifestModule(
    val name: String,
    val dir: String,
    val group: String,
    val version: String,
    val javaHome: String? = null,
    val javaVersion: Int? = null,
    val sourceDirs: List<String> = emptyList(),
    val testSourceDirs: List<String> = emptyList(),
    val classesDirs: List<String> = emptyList(),
    val resourceDirs: List<String> = emptyList(),
    val testResourceDirs: List<String> = emptyList(),
    val annotationProcessorPath: List<String> = emptyList(),
    val testAnnotationProcessorPath: List<String> = emptyList(),
    val configurations: List<ManifestConfiguration> = emptyList(),
)

@Serializable
data class ManifestConfiguration(
    val name: String,
    val artifacts: List<ManifestArtifact> = emptyList(),
    val conflicts: List<VersionConflict> = emptyList(),
    val unresolved: List<String> = emptyList(),
)

@Serializable
data class ManifestArtifact(
    val group: String? = null,
    val name: String? = null,
    val version: String? = null,
    /** `internal`, `external`, or `project` (a module of the same build, see [module]). */
    val classification: String,
    val module: String? = null,
    val sourcesJar: String? = null,
    val file: String? = null,
) {
    val coordinate: String get() = "$group:$name:$version"
}

@Serializable
data class VersionConflict(
    val group: String,
    val name: String,
    val requested: String,
    val selected: String,
    val requestedBy: String? = null,
)
