# Contributing

Thanks for helping. Bug reports, fixtures that break the extractors, and framework plugins are the
most useful contributions.

## Build and test

```
./gradlew build                      # every module, every test
cd jirrafe-maven && ./mvnw -B install   # the Maven plugin (separate build)
./gradlew :jirrafe-cli:installDist   # jirrafe-cli/build/install/jirrafe/bin/jirrafe
```

The fixtures under `jirrafe-fixtures/` must be built once before the extractor tests run:

```
cd jirrafe-fixtures/internal-lib && ./gradlew publish
cd ../gradle-multi && ./gradlew build
cd ../maven-multi && ./mvnw -B package
```

JDK 17 or 21. Kotlin, except the two build plugins, which are Java on purpose.

## Writing a framework plugin

A plugin is a post-pass over the stored graph. It never sees javac or ASM; it reads the nodes, edges
and attributes the extractors left (annotations with their values, parameters, string constants,
supertypes) and writes wiring nodes and edges back.

1. Implement `io.jirrafe.core.plugin.FrameworkPlugin` in `jirrafe-frameworks`:

   ```kotlin
   class QuarkusPlugin : FrameworkPlugin {
       override val id = "quarkus"
       override fun contribute(ctx: IndexContext) {
           val store = ctx.store
           for (cls in store.nodes(NodeKind.CLASS)) {
               val annotations = Attrs.annotations(cls)          // fqn -> member -> value
               val path = annotations["jakarta.ws.rs.Path"]?.get("value") ?: continue
               // ... find the methods, emit HTTP_ROUTE nodes and HANDLES_ROUTE edges
           }
       }
   }
   ```

2. Register it in `jirrafe-frameworks/src/main/resources/META-INF/services/io.jirrafe.core.plugin.FrameworkPlugin`.

3. Use only the kinds a plugin owns: `BEAN`, `HTTP_ROUTE`, `CONFIG_KEY`, `MESSAGE_TOPIC`,
   `SCHEDULED_JOB`, `FINDING`. They are deleted before every plugin run, so a plugin must be
   idempotent and needs no incremental bookkeeping. To annotate an existing node (an entity's
   table, a layer), use `store.setAttrs`.

4. Mark how sure you are: `Resolution.SPRING` (or your framework) for what the framework
   guarantees, `HEURISTIC` for guesses, and lower `confidence` when several candidates exist.
   Agents are told to trust `exact` and `spring` over `heuristic`.

5. Add a fixture module under `jirrafe-fixtures/gradle-multi/` that exercises every rule, and a
   test in `jirrafe-frameworks/src/test` that indexes it (see `SpringPluginTest`). Framework jars go
   on the test runtime classpath; the test hands every jar on its own classpath to the indexer.

Look at `SpringPlugin.kt` for the shape: one small function per rule, findings through one helper.

## Pull requests

- One change per PR, with a test that fails before and passes after.
- Keep the node id scheme and the JSON shapes stable; both are public API for agents.
- Commit messages: a short subject, a body that says why.
