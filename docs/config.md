# Configuration reference

`jirrafe init` writes `jirrafe.toml` in the project root and adds `.jirrafe/` to `.gitignore`.
Every key is optional; the defaults below apply when the file or the key is absent.

```toml
[project]
build_tool = "gradle"                 # gradle | maven; detected from the build files

[deps]
internal_group_prefixes = ["com.acme"]   # artifacts under these groups are indexed as internal (code inside the jars)
internal_jar_patterns = ["lib/acme-*.jar"]  # jars matching a glob (repo-relative or absolute) are internal too: a legacy build's own jars in lib/
decompile = "internal-only"           # internal-only | never; public jars also need `serve --i-understand-licenses`

[frameworks]
enabled = ["spring"]

[knowledge]
provider = "none"                     # none | anthropic | openai | azure-openai | bedrock | ollama
model = ""                            # provider default when empty (Anthropic: claude-opus-5; Azure: the deployment name, required)
endpoint = ""                         # override the provider URL (Azure resource, Ollama host, Bedrock endpoint)
max_tokens_per_summary = 300
send_code_snippets = false            # when true, the entry method's source and god-node sources are included in prompts

[serve]
transport = "stdio"                   # stdio | http
default_token_budget = 3000
```

## Where the values come from

| key | used by | notes |
|---|---|---|
| `deps.internal_jar_patterns` | `build` (resolve) | applied to the manifest after the build plugin wrote it; a file dependency without coordinates becomes `file:<jar name>:local` |
| `deps.internal_group_prefixes` | the build plugins | Gradle also accepts `-Pjirrafe.internalGroupPrefixes=a,b`; the default is every group of the project's own modules |
| `deps.decompile` | `serve`, `knowledge` | `never` disables Vineflower entirely |
| `knowledge.*` | `knowledge` | `--provider` on the command line overrides `provider`; `--dry-run` prints the prompts and sends nothing |
| `serve.*` | `serve` | `--transport`, `--port`, `--token-budget` override |

## Credentials and endpoints

Credentials are never written to `jirrafe.toml`; they come from the environment:

| provider | environment |
|---|---|
| anthropic | `ANTHROPIC_API_KEY`, optional `ANTHROPIC_BASE_URL` |
| openai | `OPENAI_API_KEY`, optional `OPENAI_BASE_URL` |
| azure-openai | `AZURE_OPENAI_API_KEY`, `AZURE_OPENAI_ENDPOINT` (or `knowledge.endpoint`), optional `AZURE_OPENAI_API_VERSION` |
| bedrock | `AWS_BEARER_TOKEN_BEDROCK` (a Bedrock API key), `AWS_REGION` |
| ollama | optional `OLLAMA_HOST` (default `http://localhost:11434`) |

## Output directory

`.jirrafe/` holds `manifest.json` (from resolve), `graph.db` and `graph.json` (from index),
`GRAPH_REPORT.md`, `graph.html` and `summaries.json` (from knowledge), extracted `sources/`,
generated sources from annotation processors under `generated/`, and lazily decompiled classes
under `decompiled/`. It is safe to delete; `jirrafe build --full` recreates everything.

## JVM options

None are required. The source tier uses only exported `jdk.compiler` APIs, so no `--add-opens`
or `--add-exports`, and the default heap has been enough for repositories of several thousand
files. If `jirrafe build` runs out of memory on a large monorepo, the launcher honours
`JIRRAFE_OPTS` (and `JAVA_OPTS`):

```
JIRRAFE_OPTS=-Xmx4g jirrafe build
```

## Command-line summary

```
jirrafe init      [--dir]
jirrafe resolve   [--dir] [--plugin-jar]
jirrafe index     [--dir] [--full]
jirrafe knowledge [--dir] [--provider] [--dry-run] [--i-understand-licenses]
jirrafe build     [--dir] [--full] [--plugin-jar]
jirrafe serve     [--dir] [--transport stdio|http] [--port] [--token-budget] [--i-understand-licenses]
jirrafe install   --client claude-skill|claude|copilot-vscode|copilot-jetbrains|copilot-cli|cursor|windsurf|agents-md [--dir] [--command]
jirrafe watch     [--dir] [--knowledge-after N]
jirrafe pull      --from <url|path|zip> [--dir] [--token]
jirrafe diff      [--dir] [--base <ref>] [--head <ref>] [--base-db] [--head-db] [--output]
jirrafe bench     --questions <json> [--dir] [--token-budget] [--output]
jirrafe query     <tool> [argument] [--dir] [--token-budget] [--i-understand-licenses]
```
