package io.jirrafe.gradle;

import groovy.json.JsonOutput;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Writes one project's classpaths, internal jars with their sources jars, and version conflicts. */
@CacheableTask
public abstract class ResolveModuleTask extends DefaultTask {

    /** Resolution results captured at configuration time; all providers are configuration-cache safe. */
    public static final class ConfigurationInput {
        String name;
        Provider<ResolvedComponentResult> root;
        Provider<Set<ResolvedArtifactResult>> artifacts;
        Provider<Set<ResolvedArtifactResult>> sources;
    }

    @Input public abstract Property<String> getModulePath();
    @Input public abstract Property<String> getModuleDir();
    @Input public abstract Property<String> getModuleGroup();
    @Input public abstract Property<String> getModuleVersion();
    @Input public abstract ListProperty<String> getInternalGroupPrefixes();
    @Input public abstract ListProperty<String> getSourceDirs();
    @Input public abstract ListProperty<String> getTestSourceDirs();
    @Input public abstract ListProperty<String> getClassesDirs();
    @Input public abstract ListProperty<String> getResourceDirs();
    @Input public abstract ListProperty<String> getTestResourceDirs();
    @Input public abstract ListProperty<String> getAnnotationProcessorPath();
    @Input public abstract ListProperty<String> getTestAnnotationProcessorPath();
    @Input @Optional public abstract Property<String> getJavaHome();
    @Input @Optional public abstract Property<Integer> getJavaVersion();
    @Internal public abstract ListProperty<ConfigurationInput> getConfigurations();
    /** The resolved classpaths, so the task is up to date until a dependency changes. */
    @Classpath public abstract ConfigurableFileCollection getResolvedFiles();
    @OutputFile public abstract RegularFileProperty getOutputFile();

    @TaskAction
    void run() throws IOException {
        Map<String, Object> module = new LinkedHashMap<>();
        module.put("name", getModulePath().get());
        module.put("dir", getModuleDir().get());
        module.put("group", getModuleGroup().get());
        module.put("version", getModuleVersion().get());
        module.put("javaHome", getJavaHome().getOrNull());
        module.put("javaVersion", getJavaVersion().getOrNull());
        module.put("sourceDirs", getSourceDirs().get());
        module.put("testSourceDirs", getTestSourceDirs().get());
        module.put("classesDirs", getClassesDirs().get());
        module.put("resourceDirs", getResourceDirs().get());
        module.put("testResourceDirs", getTestResourceDirs().get());
        module.put("annotationProcessorPath", getAnnotationProcessorPath().get());
        module.put("testAnnotationProcessorPath", getTestAnnotationProcessorPath().get());
        List<Object> configurations = new ArrayList<>();
        for (ConfigurationInput in : getConfigurations().get()) {
            configurations.add(configuration(in));
        }
        module.put("configurations", configurations);

        Path out = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(out.getParent());
        Files.writeString(out, JsonOutput.prettyPrint(JsonOutput.toJson(module)));
    }

    private Map<String, Object> configuration(ConfigurationInput in) {
        List<String> prefixes = getInternalGroupPrefixes().get();
        Map<String, String> sourcesByCoordinate = new LinkedHashMap<>();
        for (ResolvedArtifactResult a : in.sources.get()) {
            if (a.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier) {
                ModuleComponentIdentifier m = (ModuleComponentIdentifier) a.getId().getComponentIdentifier();
                sourcesByCoordinate.put(coordinate(m), a.getFile().getAbsolutePath());
            }
        }
        List<Object> artifacts = new ArrayList<>();
        for (ResolvedArtifactResult a : in.artifacts.get()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            ComponentIdentifier id = a.getId().getComponentIdentifier();
            if (id instanceof ProjectComponentIdentifier) {
                entry.put("classification", "project");
                entry.put("module", ((ProjectComponentIdentifier) id).getProjectPath());
            } else if (id instanceof ModuleComponentIdentifier) {
                ModuleComponentIdentifier m = (ModuleComponentIdentifier) id;
                entry.put("group", m.getGroup());
                entry.put("name", m.getModule());
                entry.put("version", m.getVersion());
                entry.put("classification", JirrafePlugin.isInternal(m.getGroup(), prefixes) ? "internal" : "external");
                entry.put("sourcesJar", sourcesByCoordinate.get(coordinate(m)));
            } else {
                entry.put("classification", "external");
                entry.put("name", id.getDisplayName());
            }
            entry.put("file", a.getFile().getAbsolutePath());
            artifacts.add(entry);
        }
        List<Object> conflicts = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        walk(in.root.get(), conflicts, unresolved, new HashSet<>());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", in.name);
        out.put("artifacts", artifacts);
        out.put("conflicts", conflicts);
        out.put("unresolved", unresolved);
        return out;
    }

    private static void walk(ResolvedComponentResult component, List<Object> conflicts, List<String> unresolved,
                             Set<ComponentIdentifier> seen) {
        if (!seen.add(component.getId())) {
            return;
        }
        for (DependencyResult d : component.getDependencies()) {
            if (d instanceof UnresolvedDependencyResult) {
                unresolved.add(d.getRequested().getDisplayName());
                continue;
            }
            ResolvedDependencyResult r = (ResolvedDependencyResult) d;
            if (!r.isConstraint() && r.getRequested() instanceof ModuleComponentSelector
                    && r.getSelected().getModuleVersion() != null) {
                ModuleComponentSelector requested = (ModuleComponentSelector) r.getRequested();
                String selectedVersion = r.getSelected().getModuleVersion().getVersion();
                if (!requested.getVersion().isEmpty() && !requested.getVersion().equals(selectedVersion)
                        && r.getSelected().getSelectionReason().isConflictResolution()) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("group", requested.getGroup());
                    c.put("name", requested.getModule());
                    c.put("requested", requested.getVersion());
                    c.put("selected", selectedVersion);
                    c.put("requestedBy", component.getId().getDisplayName());
                    conflicts.add(c);
                }
            }
            walk(r.getSelected(), conflicts, unresolved, seen);
        }
    }

    private static String coordinate(ModuleComponentIdentifier m) {
        return m.getGroup() + ":" + m.getModule() + ":" + m.getVersion();
    }
}
