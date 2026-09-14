package io.jirrafe.maven;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Invoked as {@code mvn compile io.jirrafe:jirrafe-maven-plugin:<version>:resolve}. Each module
 * writes {@code target/jirrafe/module.json}; the last project in the reactor merges them into
 * {@code .jirrafe/manifest.json} at the execution root. Same shape as the Gradle plugin's output.
 */
@Mojo(name = "resolve", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public class ResolveMojo extends AbstractMojo {
    // Maven scopes folded into the four Gradle-style classpaths so core reads one shape.
    private static final Map<String, Set<String>> CONFIGURATIONS = new LinkedHashMap<>();

    static {
        CONFIGURATIONS.put("compileClasspath", Set.of("compile", "provided", "system"));
        CONFIGURATIONS.put("runtimeClasspath", Set.of("compile", "runtime"));
        CONFIGURATIONS.put("testCompileClasspath", Set.of("compile", "provided", "system", "test"));
        CONFIGURATIONS.put("testRuntimeClasspath", Set.of("compile", "runtime", "test"));
    }

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    /** Comma-separated group prefixes that count as internal. Default: the reactor's own groupIds. */
    @Parameter(property = "jirrafe.internalGroupPrefixes")
    private String internalGroupPrefixes;

    @Parameter(property = "jirrafe.output", defaultValue = "${session.executionRootDirectory}/.jirrafe/manifest.json")
    private File output;

    @Inject
    private RepositorySystem repoSystem;

    @Override
    public void execute() throws MojoExecutionException {
        List<String> prefixes = prefixes();
        Set<String> reactor = session.getProjects().stream()
                .map(p -> p.getGroupId() + ":" + p.getArtifactId()).collect(Collectors.toSet());
        try {
            if (!"pom".equals(project.getPackaging())) {
                Path moduleFile = moduleFile(project);
                Files.createDirectories(moduleFile.getParent());
                Files.writeString(moduleFile, Json.write(module(prefixes, reactor)));
            }

            List<MavenProject> projects = session.getProjects();
            if (project == projects.get(projects.size() - 1)) {
                merge(projects);
            }
        } catch (IOException | DependencyCollectionException e) {
            throw new MojoExecutionException("jirrafe resolve failed for " + project.getArtifactId(), e);
        }
    }

    private List<String> prefixes() {
        if (internalGroupPrefixes != null && !internalGroupPrefixes.isBlank()) {
            return Arrays.stream(internalGroupPrefixes.split(",")).map(String::trim)
                    .filter(s -> !s.isEmpty()).collect(Collectors.toList());
        }
        return new ArrayList<>(session.getProjects().stream().map(MavenProject::getGroupId)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    private Map<String, Object> module(List<String> prefixes, Set<String> reactor) throws DependencyCollectionException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", project.getArtifactId());
        m.put("dir", project.getBasedir().getAbsolutePath());
        m.put("group", project.getGroupId());
        m.put("version", project.getVersion());
        m.put("javaHome", System.getProperty("java.home"));
        m.put("javaVersion", Runtime.version().feature());
        m.put("sourceDirs", project.getCompileSourceRoots());
        m.put("testSourceDirs", project.getTestCompileSourceRoots());
        m.put("classesDirs", List.of(project.getBuild().getOutputDirectory()));
        m.put("resourceDirs", project.getResources().stream().map(Resource::getDirectory).collect(Collectors.toList()));
        m.put("testResourceDirs", project.getTestResources().stream().map(Resource::getDirectory).collect(Collectors.toList()));
        // Maven puts processors on the compile classpath; javac discovers them from there.
        m.put("annotationProcessorPath", List.of());
        m.put("testAnnotationProcessorPath", List.of());

        List<Map<String, Object>> conflicts = conflicts();
        Map<String, String> sourcesCache = new HashMap<>();
        List<Object> configurations = new ArrayList<>();
        for (Map.Entry<String, Set<String>> c : CONFIGURATIONS.entrySet()) {
            List<Object> artifacts = new ArrayList<>();
            for (Artifact a : project.getArtifacts()) {
                if (!c.getValue().contains(a.getScope())) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("group", a.getGroupId());
                entry.put("name", a.getArtifactId());
                entry.put("version", a.getVersion());
                if (reactor.contains(a.getGroupId() + ":" + a.getArtifactId())) {
                    entry.put("classification", "project");
                    entry.put("module", a.getArtifactId());
                } else if (prefixes.stream().anyMatch(a.getGroupId()::startsWith)) {
                    entry.put("classification", "internal");
                    entry.put("sourcesJar", sourcesCache.computeIfAbsent(a.getId(), k -> sourcesJar(a)));
                } else {
                    entry.put("classification", "external");
                }
                entry.put("scope", a.getScope());
                entry.put("file", a.getFile() == null ? null : a.getFile().getAbsolutePath());
                artifacts.add(entry);
            }
            Map<String, Object> configuration = new LinkedHashMap<>();
            configuration.put("name", c.getKey());
            configuration.put("artifacts", artifacts);
            configuration.put("conflicts", conflicts);
            configuration.put("unresolved", List.of());
            configurations.add(configuration);
        }
        m.put("configurations", configurations);
        return m;
    }

    /** Sources are resolved one at a time; a missing sources jar is not an error. */
    private String sourcesJar(Artifact a) {
        ArtifactRequest request = new ArtifactRequest(
                new DefaultArtifact(a.getGroupId(), a.getArtifactId(), "sources", "jar", a.getVersion()),
                project.getRemoteProjectRepositories(), null);
        try {
            return repoSystem.resolveArtifact(session.getRepositorySession(), request).getArtifact().getFile().getAbsolutePath();
        } catch (ArtifactResolutionException e) {
            getLog().debug("no sources for " + a.getId());
            return null;
        }
    }

    /** Verbose collection keeps the losers of "nearest wins" in the graph; each loser is a conflict. */
    private List<Map<String, Object>> conflicts() throws DependencyCollectionException {
        DefaultRepositorySystemSession verbose = new DefaultRepositorySystemSession(session.getRepositorySession());
        verbose.setConfigProperty(ConflictResolver.CONFIG_PROP_VERBOSE, true);
        CollectRequest request = new CollectRequest();
        request.setRootArtifact(RepositoryUtils.toArtifact(project.getArtifact()));
        request.setDependencies(project.getDependencies().stream()
                .map(d -> RepositoryUtils.toDependency(d, verbose.getArtifactTypeRegistry())).collect(Collectors.toList()));
        if (project.getDependencyManagement() != null) {
            request.setManagedDependencies(project.getDependencyManagement().getDependencies().stream()
                    .map(d -> RepositoryUtils.toDependency(d, verbose.getArtifactTypeRegistry())).collect(Collectors.toList()));
        }
        request.setRepositories(project.getRemoteProjectRepositories());
        List<Map<String, Object>> conflicts = new ArrayList<>();
        walk(repoSystem.collectDependencies(verbose, request).getRoot(), conflicts);
        return conflicts;
    }

    private static void walk(DependencyNode node, List<Map<String, Object>> conflicts) {
        for (DependencyNode child : node.getChildren()) {
            DependencyNode winner = (DependencyNode) child.getData().get(ConflictResolver.NODE_DATA_WINNER);
            if (winner != null && child.getArtifact() != null
                    && !winner.getArtifact().getVersion().equals(child.getArtifact().getVersion())) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("group", child.getArtifact().getGroupId());
                c.put("name", child.getArtifact().getArtifactId());
                c.put("requested", child.getArtifact().getVersion());
                c.put("selected", winner.getArtifact().getVersion());
                c.put("requestedBy", node.getArtifact() == null ? null : node.getArtifact().getGroupId()
                        + ":" + node.getArtifact().getArtifactId() + ":" + node.getArtifact().getVersion());
                conflicts.add(c);
            }
            walk(child, conflicts);
        }
    }

    private void merge(List<MavenProject> projects) throws IOException {
        List<Object> modules = new ArrayList<>();
        for (MavenProject p : projects) {
            Path f = moduleFile(p);
            if (!"pom".equals(p.getPackaging()) && Files.exists(f)) {
                modules.add(Json.RAW.apply(Files.readString(f)));
            }
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", 1);
        manifest.put("buildTool", "maven");
        manifest.put("buildToolVersion", session.getSystemProperties().getProperty("maven.version"));
        manifest.put("root", session.getExecutionRootDirectory());
        manifest.put("modules", modules);
        Files.createDirectories(output.toPath().getParent());
        Files.writeString(output.toPath(), Json.write(manifest));
        getLog().info("jirrafe manifest: " + output);
    }

    private static Path moduleFile(MavenProject p) {
        return Path.of(p.getBuild().getDirectory(), "jirrafe", "module.json");
    }
}
