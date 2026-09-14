package io.jirrafe.extract

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.walk

/** Paths into `jirrafe-fixtures`; the fixtures must be built first (see its README). */
object Fixtures {
    val root: Path = Path.of("..", "jirrafe-fixtures").toAbsolutePath().normalize()
    val gradleMulti: Path = root.resolve("gradle-multi")
    val internalLibJar: Path = root.resolve("local-repo/com/example/fixtures/internal-lib/1.0.0/internal-lib-1.0.0.jar")
    val internalLibSources: Path = root.resolve("local-repo/com/example/fixtures/internal-lib/1.0.0/internal-lib-1.0.0-sources.jar")
    val appSources: Path = gradleMulti.resolve("app/src/main/java")
    val appClasses: Path = gradleMulti.resolve("app/build/classes/java/main")
    val ktClasses: Path = gradleMulti.resolve("kt-module/build/classes/kotlin/main")

    /** Lombok, from this test's own runtime classpath. */
    val lombokJar: Path = System.getProperty("java.class.path").split(File.pathSeparator)
        .first { it.contains("lombok") }.let(Path::of)

    fun appJavaFiles(): List<Path> = appSources.walk().filter { it.extension == "java" }.sorted().toList()

    init {
        for (p in listOf(internalLibJar, internalLibSources, appClasses, ktClasses, appSources)) {
            check(Files.exists(p)) { "fixture missing, build jirrafe-fixtures first: $p" }
        }
    }
}
