package io.jirrafe.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.DocsType;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applied to the root project (the CLI injects it with an init script). Registers
 * {@code jirrafeResolveModule} on every JVM project and {@code jirrafeResolve} on the root,
 * which merges the per-module results into {@code .jirrafe/manifest.json}.
 *
 * <p>Internal artifacts are those whose group starts with one of the prefixes in
 * {@code -Pjirrafe.internalGroupPrefixes=a,b}; the default is the set of groups of all
 * projects in the build.
 */
public class JirrafePlugin implements Plugin<Project> {
    public static final String MODULE_TASK = "jirrafeResolveModule";
    public static final String TASK = "jirrafeResolve";
    private static final List<String> CONFIGURATIONS =
            List.of("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath");

    @Override
    public void apply(Project project) {
        Project root = project.getRootProject();
        if (project != root) {
            root.getPluginManager().apply(JirrafePlugin.class);
            return;
        }
        Provider<String> outputPath = root.getProviders().gradleProperty("jirrafe.output");
        TaskProvider<ResolveTask> aggregate = root.getTasks().register(TASK, ResolveTask.class, t -> {
            t.setGroup("jirrafe");
            t.setDescription("Writes .jirrafe/manifest.json: modules, classpaths, internal jars, conflicts");
            t.getRootDir().set(root.getProjectDir().getAbsolutePath());
            t.getGradleVersion().set(root.getGradle().getGradleVersion());
            t.getOutputFile().fileProvider(outputPath.map(File::new)
                    .orElse(root.getLayout().getProjectDirectory().file(".jirrafe/manifest.json").getAsFile()));
        });
        // Registered after evaluation so every project's group is known for the default prefixes.
        root.getGradle().projectsEvaluated(g -> {
            List<String> prefixes = internalPrefixes(root);
            for (Project p : root.getAllprojects()) {
                if (!p.getPluginManager().hasPlugin("java-base")) {
                    continue;
                }
                TaskProvider<ResolveModuleTask> moduleTask =
                        p.getTasks().register(MODULE_TASK, ResolveModuleTask.class, t -> configure(p, t, prefixes));
                aggregate.configure(t -> t.getModuleFiles().from(moduleTask.flatMap(ResolveModuleTask::getOutputFile)));
            }
        });
    }

    private static List<String> internalPrefixes(Project root) {
        String explicit = (String) root.findProperty("jirrafe.internalGroupPrefixes");
        if (explicit != null && !explicit.isBlank()) {
            return Arrays.stream(explicit.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        }
        Set<String> groups = new LinkedHashSet<>();
        for (Project p : root.getAllprojects()) {
            String group = String.valueOf(p.getGroup());
            if (!group.isEmpty()) {
                groups.add(group);
            }
        }
        return new ArrayList<>(groups);
    }

    private static void configure(Project p, ResolveModuleTask t, List<String> prefixes) {
        t.setGroup("jirrafe");
        t.getModulePath().set(p.getPath());
        t.getModuleDir().set(p.getProjectDir().getAbsolutePath());
        t.getModuleGroup().set(String.valueOf(p.getGroup()));
        t.getModuleVersion().set(String.valueOf(p.getVersion()));
        t.getInternalGroupPrefixes().set(prefixes);
        t.getOutputFile().set(p.getLayout().getBuildDirectory().file("jirrafe/module.json"));

        JavaPluginExtension java = p.getExtensions().getByType(JavaPluginExtension.class);
        SourceSet main = java.getSourceSets().findByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet test = java.getSourceSets().findByName(SourceSet.TEST_SOURCE_SET_NAME);
        t.getSourceDirs().set(p.provider(() -> paths(main == null ? null : main.getAllSource().getSrcDirs())));
        t.getTestSourceDirs().set(p.provider(() -> paths(test == null ? null : test.getAllSource().getSrcDirs())));
        t.getClassesDirs().set(p.provider(() -> paths(main == null ? null : main.getOutput().getClassesDirs().getFiles())));
        if (main != null) {
            t.dependsOn(main.getClassesTaskName()); // fresh checkouts have no bytecode otherwise
        }
        t.getResourceDirs().set(p.provider(() -> paths(main == null ? null : main.getResources().getSrcDirs())));
        t.getTestResourceDirs().set(p.provider(() -> paths(test == null ? null : test.getResources().getSrcDirs())));

        t.getAnnotationProcessorPath().set(p.provider(() -> processorPath(p, "annotationProcessor")));
        t.getTestAnnotationProcessorPath().set(p.provider(() -> processorPath(p, "testAnnotationProcessor")));

        Provider<JavaLauncher> launcher = p.getExtensions().getByType(JavaToolchainService.class).launcherFor(java.getToolchain());
        t.getJavaHome().set(launcher.map(l -> l.getMetadata().getInstallationPath().getAsFile().getAbsolutePath()));
        t.getJavaVersion().set(launcher.map(l -> l.getMetadata().getLanguageVersion().asInt()));

        ObjectFactory objects = p.getObjects();
        for (String name : CONFIGURATIONS) {
            Configuration c = p.getConfigurations().findByName(name);
            if (c == null) {
                continue;
            }
            ResolvableDependencies incoming = c.getIncoming();
            ResolveModuleTask.ConfigurationInput in = new ResolveModuleTask.ConfigurationInput();
            in.name = name;
            in.root = incoming.getResolutionResult().getRootComponent();
            in.artifacts = incoming.getArtifacts().getResolvedArtifacts();
            in.sources = incoming.artifactView(v -> {
                v.withVariantReselection();
                v.setLenient(true);
                v.componentFilter(new InternalModuleFilter(prefixes));
                v.attributes(a -> {
                    a.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.DOCUMENTATION));
                    a.attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.class, DocsType.SOURCES));
                });
            }).getArtifacts().getResolvedArtifacts();
            t.getConfigurations().add(in);
            t.getResolvedFiles().from(c);
        }
    }

    private static List<String> processorPath(Project p, String configuration) {
        Configuration c = p.getConfigurations().findByName(configuration);
        return c == null ? List.of() : paths(c.getFiles());
    }

    private static List<String> paths(Set<File> files) {
        if (files == null) {
            return List.of();
        }
        return files.stream().map(File::getAbsolutePath).sorted().collect(Collectors.toList());
    }

    static boolean isInternal(String group, List<String> prefixes) {
        return prefixes.stream().anyMatch(group::startsWith);
    }

    /** Only internal module components get their sources jar fetched. */
    static final class InternalModuleFilter implements Spec<ComponentIdentifier>, Serializable {
        private final List<String> prefixes;

        InternalModuleFilter(List<String> prefixes) {
            this.prefixes = new ArrayList<>(prefixes);
        }

        @Override
        public boolean isSatisfiedBy(ComponentIdentifier id) {
            return id instanceof ModuleComponentIdentifier && isInternal(((ModuleComponentIdentifier) id).getGroup(), prefixes);
        }
    }
}
