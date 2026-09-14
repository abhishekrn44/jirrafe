package io.jirrafe.gradle;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Merges every module's {@code module.json} into the manifest the CLI and core read. */
@CacheableTask
public abstract class ResolveTask extends DefaultTask {
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) public abstract ConfigurableFileCollection getModuleFiles();
    @Input public abstract Property<String> getRootDir();
    @Input public abstract Property<String> getGradleVersion();
    @OutputFile public abstract RegularFileProperty getOutputFile();

    @TaskAction
    void run() throws IOException {
        List<Map<?, ?>> modules = new ArrayList<>();
        for (File f : getModuleFiles().getFiles()) {
            modules.add((Map<?, ?>) new JsonSlurper().parse(f));
        }
        modules.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", 1);
        manifest.put("buildTool", "gradle");
        manifest.put("buildToolVersion", getGradleVersion().get());
        manifest.put("root", getRootDir().get());
        manifest.put("modules", modules);

        Path out = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(out.getParent());
        Files.writeString(out, JsonOutput.prettyPrint(JsonOutput.toJson(manifest)));
    }
}
