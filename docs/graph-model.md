# Graph model

The graph lives in `.jirrafe/graph.db` (SQLite) and is exported to `graph.json` as
`{"nodes": [...], "edges": [...]}`. Ids are public API: agents cite them and tools accept them.

## Node ids

| kind | id | example |
|---|---|---|
| class, interface, enum, record, annotation | binary name | `com.acme.Outer$Inner` |
| method, constructor | `Class#name(erasedArgType,...)`, no return type; constructors are `<init>` | `com.acme.OrderService#place(com.acme.Order)` |
| field | `Class#name` | `com.acme.OrderService#max` |
| package | package name | `com.acme.orders` |
| file | `file:<path relative to the root>` | `file:app/src/main/java/com/acme/Main.java` |
| module | `module:<name>` | `module::spring-app` |
| artifact | `artifact:<group:name:version>` | `artifact:com.acme:shared-lib:2.3.0` |
| bean | `bean:<name>` | `bean:orderService` |
| http_route | `route:<VERB> <path>` | `route:POST /orders` |
| message_topic | `topic:<name>` | `topic:orders-placed` |
| config_key | `config:<key>` | `config:orders.max` |
| scheduled_job | `job:<method id>` | `job:com.acme.OrderService#expire()` |
| community | `community:L<level>-<index>` | `community:L0-3` |
| flow | `flow:<entry method id>` | `flow:com.acme.OrderController#place(com.acme.Order)` |
| finding | `finding:<kind>:<subject>-><key>` | `finding:dead-code:com.acme.Util#old()->method` |
| doc | `doc:<markdown path>#<heading slug>` | `doc:docs/payments.md#capturing-a-payment` |

## Node fields

`id`, `kind`, `fqn`, `origin` (`repo`, `sources_jar`, `bytecode`, `external`), `signature`, `file`,
`startLine`, `endLine`, `doc` (first 4000 characters of the Javadoc), `sha` (of the class file or
source file), `module`, `artifact`, and `attrs`, a string map of kind-specific extras:

| attribute | on | content |
|---|---|---|
| `annotations` | classes, members | JSON `{annotationFqn: {member: value}}` |
| `params` | methods | JSON list of `{name, type, annotations}` |
| `strings` | methods | string constants seen in the body, including concatenation pieces |
| `supertypes` / `genericSignature` | classes | generic supertypes from source / bytecode |
| `test`, `generated` | classes, members | `"true"` for test sources and generated sources |
| `layer`, `community`, `inDegree`, `betweenness`, `god` | classes | from the knowledge layer |
| `verb`, `path`, `handler`, `consumes`, `produces`, `remote`, `url` | routes | |
| `value`, `defined`, `profile` | config keys | |
| `type`, `stereotype`, `provider`, `primary`, `qualifier`, `conditional` | beans | |
| `level`, `size`, `packages`, `layers`, `gods`, `summary`, `parent`, `llmLabel`, `llmSummary` | communities | |
| `entry`, `entryKind`, `steps` (JSON), `stepCount`, `artifacts`, `modules`, `communities`, `external`, `summary`, `llmSummary` | flows | |
| `kind`, `severity`, `subject`, `key` | findings | plus kind-specific extras |

## Edges

`from`, `to`, `kind`, `resolution` (`exact`, `cha`, `spring`, `heuristic`), `confidence` (0..1).
The primary key is `(from, to, kind)`.

| kind | from -> to |
|---|---|
| `CONTAINS` | module/artifact -> package -> class -> member; community -> child community |
| `IMPORTS` | file -> class |
| `EXTENDS`, `IMPLEMENTS`, `OVERRIDES` | class -> class, method -> method |
| `CALLS` | method -> method (lambdas fold into their enclosing method; method references are calls) |
| `DISPATCHES_TO` | call site -> overriding implementation, confidence `1/n` (class-hierarchy analysis) |
| `USES_TYPE`, `READS_FIELD`, `WRITES_FIELD`, `ANNOTATED_WITH` | member or class -> target |
| `INJECTS` | class -> bean |
| `PROVIDES_BEAN` | class or `@Bean` method -> bean |
| `HANDLES_ROUTE` | handler method -> route |
| `CALLS_REMOTE` | method -> remote route |
| `PRODUCES_TO`, `CONSUMES_FROM` | method -> topic |
| `BINDS_CONFIG` | class, field or method -> config key |
| `MAPS_TO_TABLE` | repository -> entity |
| `TESTS` | test class -> class (exact by reference, heuristic by name) |
| `MEMBER_OF_COMMUNITY` | class -> finest community |
| `STEP_OF_FLOW` | method -> flow, confidence `1/(depth+1)` |
| `DEPENDS_ON_ARTIFACT` | module -> artifact or module |
| `HAS_FINDING` | subject -> finding |
| `MENTIONS` | doc section -> class or member it names (heuristic) |

JDK targets (`java.`, `jdk.`, `sun.`, `com.sun.`) are dropped. Any other target that was never
indexed becomes an `external` stub so calls into un-indexed code still land somewhere.

## Search

`nodes_fts` is an FTS5 table over camelCase-split names, signatures and docs; `search` is BM25 with
prefix matching on every word, falling back to id substrings.

## Cypher and other consumers

`graph.json` is the portable form. Node and edge kinds map one to one onto labels and relationship
types, so a loader for Neo4j or Memgraph is a short script.
