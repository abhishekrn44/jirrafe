package io.jirrafe.gradle;

import groovy.json.JsonSlurper;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the plugin the way the CLI will: injected into an unmodified project with an init script.
 * Needs {@code jirrafe-fixtures/internal-lib} published to {@code jirrafe-fixtures/local-repo}.
 */
class JirrafePluginTest {
    private static final Path FIXTURE = Paths.get("..", "jirrafe-fixtures", "gradle-multi").toAbsolutePath().normalize();

    @TempDir
    Path tmp;

    @Test
    void writesManifestForGradleMulti() throws IOException {
        Path init = tmp.resolve("jirrafe.init.gradle");
        Files.writeString(init, initScript());
        Path manifest = FIXTURE.resolve(".jirrafe/manifest.json");
        Files.deleteIfExists(manifest);

        BuildResult first = run(init);
        assertEquals(TaskOutcome.SUCCESS, first.task(":jirrafeResolve").getOutcome());

        Map<?, ?> json = (Map<?, ?>) new JsonSlurper().parse(manifest.toFile());
        assertEquals("gradle", json.get("buildTool"));
        List<Map<?, ?>> modules = (List<Map<?, ?>>) json.get("modules");
        assertEquals(List.of(":app", ":kt-module", ":spring-app"), modules.stream().map(m -> m.get("name")).collect(Collectors.toList()));
        assertTrue(((List<?>) modules.get(2).get("resourceDirs")).stream().anyMatch(d -> d.toString().endsWith("resources")));

        Map<?, ?> app = modules.get(0);
        assertEquals("com.example", app.get("group"));
        assertNotNull(app.get("javaHome"));
        assertTrue(((List<?>) app.get("sourceDirs")).stream().anyMatch(d -> d.toString().endsWith("java")));
        assertTrue(((List<?>) app.get("annotationProcessorPath")).stream().anyMatch(d -> d.toString().contains("lombok")), "lombok on the processor path");
        assertEquals(List.of(), app.get("testAnnotationProcessorPath"));

        Map<?, ?> compile = configuration(app, "compileClasspath");
        List<Map<?, ?>> artifacts = (List<Map<?, ?>>) compile.get("artifacts");
        Map<?, ?> internal = artifact(artifacts, "internal-lib");
        assertEquals("internal", internal.get("classification"));
        assertTrue(internal.get("sourcesJar").toString().endsWith("internal-lib-1.0.0-sources.jar"), "sources jar via POM-derived variant");
        Map<?, ?> lang3 = artifact(artifacts, "commons-lang3");
        assertEquals("external", lang3.get("classification"));
        assertEquals("3.14.0", lang3.get("version"));
        assertNull(lang3.get("sourcesJar"), "external sources are never fetched");
        Map<?, ?> kt = artifacts.stream().filter(a -> "project".equals(a.get("classification"))).findFirst().orElseThrow();
        assertEquals(":kt-module", kt.get("module"));

        List<Map<?, ?>> conflicts = (List<Map<?, ?>>) compile.get("conflicts");
        Map<?, ?> conflict = conflicts.stream().filter(c -> "commons-lang3".equals(c.get("name"))).findFirst().orElseThrow();
        assertEquals("3.12.0", conflict.get("requested"));
        assertEquals("3.14.0", conflict.get("selected"));
        assertTrue(conflict.get("requestedBy").toString().contains("commons-text"));
        assertEquals(List.of(), compile.get("unresolved"));

        Map<?, ?> testRuntime = configuration(app, "testRuntimeClasspath");
        assertEquals("external", artifact((List<Map<?, ?>>) testRuntime.get("artifacts"), "junit-jupiter-api").get("classification"));

        BuildResult second = run(init);
        assertTrue(second.getOutput().contains("Reusing configuration cache"), second.getOutput());
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":jirrafeResolve").getOutcome());
    }

    private static BuildResult run(Path init) {
        return GradleRunner.create()
                .withProjectDir(FIXTURE.toFile())
                .withArguments("jirrafeResolve", "--init-script", init.toString(), "--configuration-cache", "--stacktrace")
                .forwardOutput()
                .build();
    }

    private static Map<?, ?> configuration(Map<?, ?> module, String name) {
        return ((List<Map<?, ?>>) module.get("configurations")).stream()
                .filter(c -> name.equals(c.get("name"))).findFirst().orElseThrow();
    }

    private static Map<?, ?> artifact(List<Map<?, ?>> artifacts, String name) {
        return artifacts.stream().filter(a -> name.equals(a.get("name"))).findFirst().orElseThrow();
    }

    /** Same shape the CLI will generate: plugin classes on the init script classpath, applied to the root. */
    private static String initScript() throws IOException {
        Properties p = new Properties();
        try (InputStream in = JirrafePluginTest.class.getClassLoader().getResourceAsStream("plugin-under-test-metadata.properties")) {
            p.load(in);
        }
        String classpath = Arrays.stream(p.getProperty("implementation-classpath").split(File.pathSeparator))
                .map(f -> "'" + f.replace(File.separatorChar, '/') + "'")
                .collect(Collectors.joining(", "));
        return "initscript { dependencies { classpath files(" + classpath + ") } }\n"
                + "rootProject { apply plugin: io.jirrafe.gradle.JirrafePlugin }\n";
    }
}
