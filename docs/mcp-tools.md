# MCP tool reference

Every tool below is also a shell command, `jirrafe query <tool> [argument] [--token-budget N]`,
printing the same JSON; that is what the Claude Code skill (`install --client claude-skill`) uses.

`jirrafe serve --dir <project>` serves `.jirrafe/graph.db` over stdio (default) or Streamable HTTP
(`--transport http --port 8765`, bound to `127.0.0.1`). It starts in well under a second and never
rebuilds; run `jirrafe build` to refresh the graph.

Every answer is compact JSON. Code nodes carry `at` (`file:line` relative to the project root), edges
carry `resolution` when it is not `exact` and `confidence` when it is below 1. Every tool takes
`token_budget` (default 5000, from `[serve] default_token_budget`); answers shrink their lists until
they fit. Tool errors (unknown id, no such flow) come back inside the result with `isError`, so the
agent can correct itself.

## Start here

| tool | arguments | answer |
|---|---|---|
| `explain` | `question`, `token_budget` | flows relevant to the question (steps with citations, `stepCount`, `truncated` when cut), one or two communities (id, label), `pack`, whole method bodies along the chains from the best matches (the entry point, the match and the hops below it, a second chain when another match scores nearly as well; up to six bodies of up to 120 lines, each cited, with its caller count and the ids it `calls`), `data`, the entities and DTOs those bodies move as field lists, `config`, the keys they read with values, then the other matching nodes by name. The bodies are the answer and shrink last; with bodies present the flow steps and node edges collapse to what the bodies do not cover. Deterministic: search, graph ranking, budget packing; no LLM. |
| `overview` | `token_budget` | modules, internal jars, largest communities, longest flows, layer and finding counts, god nodes |

Both carry `stale` when the repository moved on since the build: the commit the graph was built at,
`HEAD`, the count of Java and Kotlin files that differ from that commit (committed or not) and up to
five of their paths. Cited lines in those files may be off; the skill text tells the agent to read
the current file before quoting one, and to rebuild when the question is about them. Absent when the
root is not a git repository.

Markdown in the repository (README, `docs/`, ADRs, a `documents/` folder) is in the graph too, one
`doc` node per heading section (`doc:<path>#<slug>`), cited by file and line and linked with
`mentions` edges to the classes and members the section names; changelogs and release notes are
skipped. `explain` offers doc sections only for usage questions ("how do I", "why", "what is",
"example"); a mechanism question ("how is X captured") is answered by code, and a section that
lists event names would otherwise outrank the method. `search` and `get_node` always show them.

`explain` also remembers. Every `explain`, `get_node` and `read_source` call is appended to
`.jirrafe/queries.jsonl` (local to the checkout, never sent anywhere). When a question shares at least
half its stems with an earlier one, the ids that were fetched within two minutes after that earlier
question come first, at most three, the leading method's body included, so the follow-up call of
last time is not needed this time.

## Nodes

| tool | arguments | answer |
|---|---|---|
| `search` | `query`, `kinds` (comma-separated), `limit` | BM25 over names, signatures and docs with prefix matching; id-substring fallback |
| `get_node` | `id`, `include_source` | attributes, doc, module, community, layer, annotations, members as an outline (line, signature, annotations; a member's id is the class id + `#` + the signature's `name(params)`, simple type names accepted), outgoing and incoming edges, flows it is a step of, findings; source when asked (decompiled lazily for internal jars) |
| `read_source` | `id`, `context_lines` | the exact lines of a node; `decompiled: true` marks Vineflower output |

## Structure

| tool | arguments | answer |
|---|---|---|
| `neighbors` | `id`, `direction` (`in`, `out`, `both`), `edge_types`, `depth` (1-4), `resolution_min` | nodes with their distance and the edges between them |
| `path` | `from`, `to`, `max_depth` | shortest dependency path, members reachable through their class |
| `impact` | `id`, `depth` (1-6) | transitive callers including dispatch and injection, grouped by community, flow and module, the tests to run and `testCommand`, one wrapper command that runs them |
| `impact_of_changes` | `depth` | the same for the working tree against `HEAD`: the members the changed lines fall in (`changed`), their callers, tests and `testCommand`; CLI: `jirrafe query impact --diff` |
| `community` | `id` | summary, layers, central classes, members, children and parent |
| `communities` | `query` | all communities, filterable by label, package or summary text |
| `flow` | `entry` (flow id, handler method id, `GET /orders`, `/orders`, or a topic name) | the precomputed step list with depth and resolution per step, artifacts crossed, modules, communities, external boundary |

## Wiring

| tool | arguments | answer |
|---|---|---|
| `routes` | `prefix` | verb, path, handler, consumes, produces, flow |
| `topics` | | topics with consumers and producers |
| `config` | `key_prefix` | keys with values, files, profiles and the code bound to them; `defined: false` for keys used but never defined |
| `beans` | `type` | beans with type, stereotype, provider, primary, qualifier, conditional, and who injects them |
| `findings` | `kind`, `severity`, `node` | dead-code, cyclic-packages, layer-violation, self-invocation, undefined-config-key, version-conflict, untested-god-node, sarif |
| `dependencies` | `module` | internal and external artifacts per module with version conflicts |

## Resources

| uri | content |
|---|---|
| `jirrafe://report` | `GRAPH_REPORT.md` |
| `jirrafe://flow/<entry method id>` | one flow as JSON |

## Instructions the server sends

The server's `instructions` tell the client to call `explain` first, prefer graph tools over grep
for structure, trust `exact` and `spring` edges over `heuristic` ones, call `impact` before changing
a method, and cite `file:line`. `jirrafe install --client <name>` writes the same guidance into the
client's own file and registers the server where the client has one: `CLAUDE.md` and
`.github/copilot-instructions.md` (always on), `.cursor/rules/jirrafe.mdc` and
`.windsurf/rules/jirrafe.md` (loaded on demand by their description, like the skill), `AGENTS.md`
for Codex, Amp, Jules and any agent with a shell (the CLI form, no server). One procedure, two
spellings: `jirrafe query explain "..."` in a shell, `explain(question)` over MCP.
