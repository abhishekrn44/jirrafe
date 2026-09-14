# Build the graph in CI, pull it locally

Indexing a large service means resolving and reading every internal jar. Do it once in CI, publish
`graph.db`, and let developers and agents pull it instead of indexing two hundred jars on a laptop.

## GitHub Actions

```yaml
name: code graph
on:
  push:
    branches: [main]
jobs:
  graph:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "17" }
      - name: Build the graph
        run: |
          curl -sSL -o jirrafe.jar https://github.com/abhishekrn44/jirrafe/releases/latest/download/jirrafe-all.jar
          java -jar jirrafe.jar build --dir .
      - uses: actions/upload-artifact@v4
        with:
          name: jirrafe-graph
          path: |
            .jirrafe/graph.db
            .jirrafe/manifest.json
            .jirrafe/GRAPH_REPORT.md
            .jirrafe/graph.html
            .jirrafe/summaries.json
```

The Gradle and Maven tasks are cacheable, so a warm build cache makes `resolve` cheap. Add
`[knowledge] provider` and the provider's key as a secret if you want LLM summaries generated once,
centrally, and shipped with the graph.

## Pull

```
jirrafe pull --from https://github.com/<owner>/<repo>/actions/artifacts/<id>/zip --token $GITHUB_TOKEN
jirrafe pull --from /shared/graphs/service/graph.db
jirrafe pull --from https://artifacts.example.com/service/graph.zip
```

`pull` accepts a `graph.db`, or a zip holding one (an artifact download), from a URL or a path, and
writes it under `.jirrafe/`. Then `jirrafe serve` or `jirrafe install --client ...` works as if the
graph had been built locally; `read_source` reads the working tree for repo files and decompiles
internal jars on demand, so sources jars are not needed on the developer machine.

## Pull request comments

Use the composite action in this repository to post a structural diff on every pull request:

```yaml
name: jirrafe diff
on: [pull_request]
permissions:
  contents: read
  pull-requests: write
jobs:
  diff:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: abhishekrn44/jirrafe@v0.1.0
```

It builds the base and head graphs, runs `jirrafe diff`, and upserts one comment listing removed,
changed and added nodes, the affected callers by community and flow, and the tests to run.
