# jirrafe

A code graph of any Gradle or Maven Java or Kotlin project, **including the code inside its
internal jars**, that an AI coding agent queries instead of grepping. It ships as a Claude Code
skill and as an MCP server for GitHub Copilot, Cursor, Windsurf and any other MCP client. An agent
asks "how does X work", "what calls Y" or "what breaks if I change Z" and gets the flows, the
classes and methods, their callers and their source, every one cited as `file:line`, in a few
calls instead of a grep session.

**Half the tool calls, a quarter fewer tokens, better answers** against the same model with grep
and file reads, measured live on eleven repositories the model had never seen: 2.7 calls per
question against 5.8, 27% fewer tokens, and the right code reached more often (90% against 87%).
The saving grows with the question: where grep needs ten turns to follow a mechanism, the graph
needs three. A small model with the graph reaches the facts a large one reaches by reading
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
| `jirrafe install --client <name>` | register the skill or the MCP server for a client and write its instructions |
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
jirrafe has read that jar's bytecode. Measured on code the model had not seen: every question
answered against seven in eight, half the tool calls, about three quarters fewer tokens read.

**Against a general-purpose knowledge graph (graphify and similar).** Those tools extract entities
and relations from any text with an LLM, which makes them work on anything but leaves the edges
guessed: they know `PaymentService` is related to `Gateway`, not that `capture` calls
`gateway.capture` at line 77 through an interface. Their answer is a list of nodes; the agent still
opens files to read the code. jirrafe's edges are exact, the answer carries `file:line` and the
source, and building the graph needs no LLM. Measured on the same repository and questions: the
same recall at a third of the calls and a third of the tokens.

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

Every number here comes from live A/B runs: the same questions put to Claude Code with the jirrafe
skill and to a clean clone with only Read, Grep and Glob, the same model on both sides, on
repositories the model could not describe from memory (open-source libraries and small enterprise
Spring services), scored on whether the answer reached the classes and methods a correct answer
must name. Five questions per repository, so treat every figure as a direction, not a decimal.

- **Answers.** With the graph the agent reached the right code on every repository at least as
  often as without it, and on a third of them more often. Where grep missed, it was usually
  configuration: a key in `application.properties`, a `@Configuration` class, a filter the code
  never calls.
- **Turns.** Tool calls per question fell by half or more on every repository (roughly two to
  three instead of four to six), and about half of all questions were answered in a single call.
  Turns are what an agent's session costs in time, and what some assistants bill.
- **Tokens and calls, live.** Eleven repositories the model had never seen (CLI tools, a chess
  engine, a BSON codec, a MongoDB server, a Liquibase extension, a fingerprint matcher), the
  questions taken blind from each project's own README, the same model with the skill against a
  clean clone with grep and file reads: **53% fewer tool calls (2.7 against 5.8) and 27% fewer
  tokens per question**, reaching the right code more often (90% against 87%), at lower cost.
  Where grep had to follow a mechanism across files it took eight to twelve turns; the graph took
  two to four. Offline, against a mechanical grep baseline on eight repositories: 55% fewer tokens
  and 83% fewer calls (1.3 against 7.8), 76% against 48% recall.
- **A cheaper model finds what an expensive one finds.** Six repositories, twenty questions, each
  side run the same day: a small or mid-size model with the graph against a larger model with grep
  (Haiku against Sonnet, and on the hardest repository Sonnet against Opus). **78% recall at $0.030
  a question against 85% at $0.103** - a third of the cost, 2.6 tool calls against 6.2, 43% fewer
  tokens, the same wall clock. Four of the six tied on recall; on Spring Petclinic the small model
  with the graph answered every question in 1.8 calls at $0.020, against 4.4 calls at $0.054.
  The graph changes what a model finds, not how well it writes it up: judged blind on the prose
  itself, the larger model still explains it better, so read this as retrieval parity, not answer
  parity.
- **Unprompted.** The skill loaded on its own for every question shape tested ("how is", "where
  is", "find the code that", "which class", "what calls").
- **Docs.** For "how do I use X" questions on a project with real usage documentation, the graph
  answered seven in ten; grep over the source found none.
- **Internal jars.** On a project carrying two mostly unused libraries, cutting them to what the
  code reaches shrank the graph by a third and raised recall.
- **Against a general-purpose knowledge graph** (graphify) on the same repository and questions:
  the same recall at a third of the calls and a third of the tokens.

Two honest limits of the measurement: recall means the answer named the right code with the right
citations, not that its explanation was good; and the cost of a session depends on prompt caching,
so compare configurations on the same day, as these were.

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
