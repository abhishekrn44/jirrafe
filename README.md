# jirrafe

A code graph of any Gradle or Maven Java or Kotlin project, **including the code inside its
internal jars**, that an AI coding agent queries instead of grepping. It ships as a Claude Code
skill and as an MCP server for GitHub Copilot, Cursor, Windsurf and any other MCP client. An agent
asks "how does X work", "what calls Y" or "what breaks if I change Z" and gets the flows, the
classes and methods, their callers and their source, every one cited as `file:line`, in a few
calls instead of a grep session.

**The same answers in a third of the calls, for less money**, against the same model with grep and
file reads, measured live on Spring services the model had not seen and scored against the facts a
correct answer must state: Opus with the graph matched Opus with grep on seven questions of nine
and came within eight points on the other two, in two to four calls against four to eleven, cheaper
on eight of the nine. Sonnet with the graph beat Sonnet with grep on four questions, tied on three
and lost two, every one of them in one or two calls. Neither model with the graph stated a
framework default as a fact of the code, which grep answers did twice
([What to expect](#what-to-expect)).

Requirements: JDK 17 or 21; the project builds with Gradle 7.6+ or Maven 3.9. Kotlin modules are
indexed from bytecode.

## Why

In enterprise Java most real logic lives in internal jars pulled from a private repository. Every
source-based assistant goes blind at that boundary and guesses. jirrafe reads those jars with ASM
and their sources jars with javac, resolves calls with real Java semantics, models Spring wiring,
precomputes one flow per entry point, and hands an agent a cited map instead of a search box. The
compiler is what makes the answers exact, and it needs your build, which you already have.

## Install

Until the first release is published, build from source. macOS and Linux:

```
git clone https://github.com/abhishekrn44/jirrafe.git && cd jirrafe
./gradlew :jirrafe-cli:installDist
export PATH="$PWD/jirrafe-cli/build/install/jirrafe/bin:$PATH"   # add to ~/.zshrc or ~/.bashrc to keep it
jirrafe --help
```

Windows (PowerShell):

```powershell
git clone https://github.com/abhishekrn44/jirrafe.git; cd jirrafe
.\gradlew.bat :jirrafe-cli:installDist
$env:PATH = "$PWD\jirrafe-cli\build\install\jirrafe\bin;$env:PATH"
jirrafe --help
```

That PATH lasts for the session; to keep it, add the `bin` directory under System Properties ->
Environment Variables, or run `setx PATH "<that directory>;$env:PATH"` once.

On a machine without open internet: the build needs a JDK 17 or 21 already installed and on
`JAVA_HOME` (it downloads none) and the Gradle cache warmed once, either by running the build on a
connected machine and copying `~/.gradle/caches` across, or by pointing `GRADLE_USER_HOME` at a
copy. Then build with `./gradlew --offline :jirrafe-cli:installDist`. If it stops at
`compileKotlin` with no message, run it with `--info`: a line about provisioning a toolchain means
a JDK download, a line about the Kotlin daemon means a blocked loopback socket; the build is
configured to need neither.

From the first release on, the fat jar runs anywhere Java does
(`java -jar jirrafe-<version>-all.jar <command>`), and the manifests under `packaging/` cover
Homebrew, Scoop, SDKMAN and jbang. Release assets are built by `.github/workflows/release.yml`.

## Quick start

```
jirrafe init                            # jirrafe.toml, .jirrafe/ in .gitignore
jirrafe build                           # resolve (build plugin) -> index -> knowledge, about a minute
jirrafe install --client claude-skill   # Claude Code as a skill (recommended); or claude (MCP), cursor, windsurf,
                                        # copilot-vscode | copilot-jetbrains | copilot-cli, agents-md (Codex, Amp, Jules)
```

Then ask your assistant a question. In Claude Code the skill loads on its own for questions about
the code ("how is a payment captured", "where is the handshake performed", "what calls the post
helper", "how do I pause a subscription"); `/jirrafe <question>` forces it. Open
`.jirrafe/GRAPH_REPORT.md` for the map yourself, or `.jirrafe/graph.html` to look at the graph.

## How an agent uses it

1. `jirrafe query explain "<the question in plain words>"`: the relevant flows (end-to-end step
   lists from an entry point), the best matching classes and methods with their callers and
   callees, and the body of the leading method, all cited `file:line`. Most questions end here.
2. `jirrafe query source <id>` or `jirrafe query node <id>` when the answer needs one more body or
   one node's full edge lists. Ids are pasted verbatim from the answer, including classes inside
   internal jars, which are decompiled on demand.
3. `jirrafe query impact <id>` for "what calls X" and before changing a method: every caller with
   `file:line`, grouped by community and flow, the tests that cover them, and one command that
   runs those tests.
4. `jirrafe query impact --diff` after editing, before committing: the working tree's changed lines
   mapped to the members they fall in, what those affect, and the test command for exactly that.

Every answer honours a token budget (default 3000) and shrinks its lists to fit. An answer carries
`stale` when sources moved since the build, naming the files whose cited lines may be off. The ids
an agent fetched after a question are remembered locally, so the next time the same question is
asked they come first, body included.

## Commands

| command | what it does |
|---|---|
| `jirrafe init` | writes `jirrafe.toml` and ignores `.jirrafe/` |
| `jirrafe build [--full]` | resolve the classpaths through the build plugin, index sources, bytecode and internal jars, compute the knowledge layer |
| `jirrafe watch` | re-index incrementally on source changes |
| `jirrafe install --client <name> [--git-hooks]` | register the skill or the MCP server for a client and write its instructions; `--git-hooks` adds post-commit, post-checkout and post-merge hooks that re-index in the background, so an answer after a commit never starts from a stale graph (`--remove-git-hooks` strips them) |
| `jirrafe serve [--transport stdio\|http]` | the MCP server |
| `jirrafe query explain "<question>"` | flows, communities and nodes with citations, the leading method's body |
| `jirrafe query search <name>` | nodes by name, signature, Javadoc or doc text |
| `jirrafe query node <id> [--source]` | one node with its edges, a class as an outline of its members |
| `jirrafe query source <id> [--token-budget N]` | the source of a node, decompiled when it lives in an internal jar |
| `jirrafe query impact <id> \| --diff [--depth N]` | callers, tests and the test command for an id or for the working tree's changes |
| `jirrafe query flow <route or id>` | one end-to-end flow |
| `jirrafe query neighbors\|path\|routes\|topics\|beans\|config\|findings\|communities\|dependencies\|overview` | the other views |
| `jirrafe diff` | structural diff of the graph between two commits, posted as a pull request comment by the GitHub Action |
| `jirrafe pull <url>` | fetch a graph built in CI instead of building locally |
| `jirrafe knowledge [--provider ...]` | optional LLM summaries of communities and flows, cached by content hash |
| `jirrafe bench --questions <file>` | the retrieval benchmark on your own project and questions |

Every `query` prints the same JSON the MCP tools return; `docs/mcp-tools.md` lists every tool and
field.

## What you get

- **Bytecode and source tiers.** Exact classes, members, annotations with values, call sites,
  string constants and dispatch edges from ASM; parsed declarations, Javadoc, generics and generated
  sources (Lombok, MapStruct) from javac. Vineflower decompiles internal-jar classes lazily when an
  agent asks for source.
- **Internal jars, cut to what you use.** Jars under your own group prefixes, or matching
  `deps.internal_jar_patterns` (a legacy `lib/` folder), are indexed like your own code: the
  classes your code reaches, what those reach within two hops, and anything wired by annotation.
  The rest of the jar stays out, so a two-thousand-class library you touch three members of costs
  three members.
- **Spring wiring.** Beans and injection resolved by qualifier and `@Primary`, routes with verb and
  path, config keys with their files and lines, JPA tables, Kafka/JMS/Rabbit topics, RestTemplate,
  WebClient and Feign remote calls, scheduled jobs, and the `@Transactional` self-invocation trap.
- **Docs in the graph.** README, `docs/`, ADRs and any Markdown in the repository, one node per
  heading, linked to the classes it names, offered for usage questions ("how do I", "why") and never
  in the way of code questions.
- **Knowledge layer.** Leiden communities with the package tree as a prior, layer classification,
  god nodes, one precomputed flow per route, consumer, job and `main`, structural findings (dead
  code, cyclic packages, layer violations, version conflicts, untested god nodes, SARIF), and
  optional LLM summaries.
- **Honest answers.** Edges carry their resolution (`exact`, `spring`, `cha`, `heuristic`);
  decompiled text is marked as such; an answer says when the graph is behind the working tree.
- **Workflow.** `watch` for incremental re-indexing, `diff` for structural pull request comments
  (with a GitHub Action), `pull` for CI-built graphs, `bench` to measure it on your own code.

## How it compares

**Against an agent with only grep and file reads.** Grep matches text, so `capture(` returns every
overload, comment and test that mentions it, and the agent opens file after file to work out which
one matters. jirrafe already knows the real callers (resolved by the compiler, dispatch included),
the end-to-end flow from the entry point, and includes the method body in the first answer, so most
questions take one call instead of a dozen. Grep also stops at an `import` into an internal jar;
jirrafe has read that jar's bytecode. Measured on Spring services the model had not seen, the same
model on both sides: the same facts reached on seven questions of nine, at a third of the calls.

**Against a general-purpose knowledge graph (graphify and similar).** Those tools extract entities
and relations from any text with an LLM, which makes them work on anything but leaves the edges
guessed: they know `PaymentService` is related to `Gateway`, not that `capture` calls
`gateway.capture` at line 77 through an interface. Their answer is a list of nodes; the agent still
opens files to read the code. jirrafe's edges are exact, the answer carries `file:line` and the
source, and building the graph needs no LLM. Measured on one repository, three questions, Opus on
both sides: the same facts at a third to a seventh of the calls and under half the cost.

**Against symbol indexers for agents (LSP or tree-sitter based).** They answer "where is X defined
or referenced", quickly and across many languages, without a build. jirrafe adds what those cannot
see: the code inside internal jars, Spring wiring (beans, routes, topics, config keys bound to
code), one precomputed flow per entry point, and `impact` with the tests to run. jirrafe has not
been measured head to head against them; the harness in `jirrafe bench` will run that comparison
on your own project.

In short: on Java and Kotlin code the model has not memorised, especially behind an internal-jar
boundary, jirrafe gives correct answers in fewer calls. On a small, well-named repository the
answers are the same and only the calls and time are saved.

## What to expect

Every number here comes from live A/B runs made on 8 and 9 October 2026: the same question put to
Claude Code with the jirrafe skill and to a clean clone with only Read, Grep and Glob, the same
model on both sides, with no proxy or output compression between the agent and the API. Four
Spring repositories: one the model knows (a user-management service) and three it had not seen
(a multi-service job board on Kafka and MongoDB, an audio-ingestion service on MinIO and Kafka, a
metadata service with a Redis cache). Nine questions, each with a list of the facts a correct answer
must state, written from the source before any run; an answer scores a point per fact, half for a
partial one, and a wrong claim (one that contradicts the code, cites a line that does not support
it, or states a framework default as a fact of this code) is counted separately. Nine questions is
a direction, not a decimal.

- **Same model, Opus.** With the graph, the same score as with grep on seven questions of nine
  (five of them full marks on both sides), seven and eight points behind on the other two. Two to
  four calls a question against four to eleven; cheaper on eight of the nine, by $0.02 to $0.15,
  and $0.02 dearer on the one where the whole file is shorter than the graph's answer.
- **Same model, Sonnet.** Ahead of grep on four questions (by 7, 17, 17 and 29 points), level on
  three, behind on two (by 6 and 7). One call on eight questions and two on the ninth, against two
  to ten; cheaper on seven, equal on one, dearer by a cent on one.
- **A cheaper model against a dearer one.** Sonnet with the graph against Opus with grep: level
  on three questions, 5 to 11 points behind on six, at a quarter to a sixth of the cost ($0.04 to
  $0.11 a question against $0.21 to $0.28). The graph closes most of the distance between the two
  models; it does not close it. Where it falls short the line was in the answer and the smaller
  model summarised past it.
- **What the graph carries that grep has to read for.** The facts grep found only by opening
  whole files, and the graph now states as lines: what nothing reads or calls (an unused cache
  repository, a constant never read, a service method no controller reaches), a topic's every
  producer and consumer across modules with their consumer groups, a bean the chain injects or a
  class builds its own instance of, a key written under a literal nothing else reads, and where a
  method too long to show exits.
- **Against a general-purpose knowledge graph** (graphify), one repository, three questions, Opus
  on both sides: the same facts, at 12 to 21 calls against 3 to 9 and $0.51 to $0.74 a question
  against $0.23 to $0.33.
- **Internal jars.** On a project carrying two mostly unused libraries, cutting them to what the
  code reaches shrank the graph by a third.

Two honest limits of the measurement: the scorer was the same agent that built the tool, reading
both answers against the source, not a blind judge; and the cost of a session depends on prompt
caching, so compare configurations run on the same day, as these were, and treat differences under
three cents as noise.

Run it on your own project: copy `jirrafe-fixtures/benchmark/TEMPLATE.json`, replace each
`question` with one of yours and each `expected` entry with the node ids a correct answer must
reach (a class, a `Class#method(param.Types)`, `route:GET /path`, `topic:name`, `config:key`,
`bean:name` or `flow:<handler id>`; `a|b` counts if either is found), then run
`jirrafe bench --questions <file>` for the offline retrieval check.

See `docs/worked-example.md` for a real, unedited answer to "what is the auth mechanism in this
code?" in two calls.

## Documentation

- `docs/architecture.md`: pipeline, modules, tiers, resolution levels
- `docs/graph-model.md`: node ids, attributes, edge kinds
- `docs/mcp-tools.md`: every tool and resource
- `docs/config.md`: `jirrafe.toml`, credentials, command line
- `docs/ci-graph.md`: build the graph in CI, `pull` it locally, post diffs on pull requests
- `docs/worked-example.md`: the auth question end to end
- `CONTRIBUTING.md`: writing a framework plugin

## Privacy and licensing

No telemetry. Network access goes only to your own artifact repositories through your build tool,
to the LLM provider you enable, and to the URL you give `pull`. Nothing is sent to an LLM unless
`[knowledge] provider` is set; even then only names, signatures, Javadoc and edges are sent unless
`send_code_snippets = true`, and `jirrafe knowledge --dry-run` prints exactly what would go out.
The query memory in `.jirrafe/queries.jsonl` stays on your machine.

**Decompilation.** jirrafe decompiles only jars the manifest classifies as internal (your own group
prefixes or patterns) and only when an agent asks to read a class without sources. Decompiling
third-party jars may violate their licence; it is off unless you pass `--i-understand-licenses`,
and `deps.decompile = "never"` disables it entirely. Decompiled output is marked as such and never
presented as original source. See `SECURITY.md`.

## Licence

Apache-2.0. Third-party notices in `NOTICE`.
