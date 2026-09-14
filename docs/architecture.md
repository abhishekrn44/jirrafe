# Architecture

jirrafe turns a Gradle or Maven JVM project, including the code inside its internal jars, into a
graph in SQLite and serves it over MCP. The pipeline is `resolve -> index -> knowledge -> serve`.

```
 build plugin            jirrafe-extract                 jirrafe-core                  jirrafe-cli
 (Gradle / Maven)        bytecode tier (ASM)             GraphStore (SQLite)           serve  (MCP, stdio | http)
 manifest.json   ---->   source tier (javac)   ---->     knowledge layer      ---->    install, watch, diff,
 modules, classpaths,    framework plugins (Spring)      queries + explain             pull, bench
 internal jars, sources  incremental by unit             LLM summaries (optional)
```

## Modules

| module | what it holds |
|---|---|
| `jirrafe-core` | graph model, SQLite store with FTS5, manifest model, `jirrafe.toml`, the knowledge layer (Leiden communities, layers, god nodes, flows, findings, report, HTML), queries and `explain`, LLM providers, structural diff, benchmark |
| `jirrafe-extract` | ASM bytecode tier, javac source tier (`JavaCompiler` with annotation processors, so Lombok and MapStruct work), Vineflower lazy decompile, the `Indexer` that runs them per unit |
| `jirrafe-frameworks` | framework plugins discovered through `ServiceLoader`; Spring is the first |
| `jirrafe-gradle` | the Gradle plugin (`io.jirrafe`): `jirrafeResolve` writes `.jirrafe/manifest.json` |
| `jirrafe-maven` | the Maven plugin: `resolve` goal, separate Maven build |
| `jirrafe-cli` | the `jirrafe` command and the MCP server adapter |
| `jirrafe-fixtures` | test projects: Gradle multi-project with a published internal library and a Kotlin module, Spring Boot app, Maven multi-module, benchmark questions |

## Resolve

The build plugin is the only thing that knows the real classpaths. Per module and configuration it
records every artifact, classifies it internal or external by group prefix, fetches sources jars for
internal ones, and records version conflicts (requested versus selected). The CLI injects the Gradle
plugin through an init script, so projects need no build changes; the Maven plugin runs by coordinate.

## Index

Bytecode first, source on top. For every module the classes directory goes through ASM
(insert-or-ignore), then the sources go through javac with the module's own classpath and processor
path (insert-or-replace with identical ids), so parsed declarations win and bytecode fills what
source could not resolve. Internal jars follow the same rule: the jar through ASM, the sources jar
through javac. Node ids are stable and readable (`com.acme.Outer$Inner#place(com.acme.Order)`), so
both tiers and every plugin write the same ids.
An internal jar is then cut to what reaches it: the classes that code outside the jar points at,
plus what those reach within two hops inside it, plus any class carrying a framework annotation
(auto-configuration, entities, resources are wired by annotation, not by a call). A reached member
keeps its whole class. Everything else in the jar is dropped and, if a kept class still refers to
it, comes back as a stub like third-party code. The jar's unit hash includes that usage, so a repo
that starts using more of the jar re-extracts it.
Markdown in the repository is indexed last: one `doc` node per heading section, linked with
`MENTIONS` edges to the classes and members the section names; changelogs are skipped.

Units of work are modules and internal artifacts. Each has a content hash in `meta`; unchanged units
are skipped, changed ones are deleted and re-extracted. Stubs and dispatch edges are dropped before
extraction and made again after it: EXTERNAL stubs for edge targets that were never indexed,
class-hierarchy dispatch edges, framework plugins, orphan pruning, and the search index.

## Knowledge

Everything here is derived and recomputed from scratch, so it is idempotent:

- communities: Leiden on the class projection with the package tree as a prior, three resolutions
  for a hierarchy
- layers: controller, service, repository, client, config, model, util
- god nodes: in-degree and betweenness
- flows: one per entry point (route, consumer, scheduled job, `main`), bounded depth-first walks
  with the artifacts crossed
- findings: dead code, cyclic packages, layer violations, version conflicts, untested god nodes,
  SARIF ingest; plugin findings (proxy self-invocation, undefined config) stay
- outputs: `GRAPH_REPORT.md`, `graph.html`, `graph.json`, and optional LLM summaries cached by
  prompt hash

## Serve

`Queries` answers every question as compact JSON with `file:line` citations inside a token budget;
`McpServer` maps them to MCP tools and resources and is the only file that touches the SDK. `explain`
is search plus graph ranking, no LLM inside the server. See `mcp-tools.md`.

## Resolution levels

Every edge says how it was found: `exact` (javac or bytecode), `cha` (class-hierarchy dispatch with
confidence `1/n`), `spring` (the framework guarantees it), `heuristic` (a guess from names or string
constants). Agents are told to trust `exact` and `spring` over `heuristic`.
