# Fixtures

Standalone projects that stand in for a user's code. They are not part of the root
Gradle build; each has its own wrapper. Tests in the main modules point at these.

| Fixture | Build | What it exercises |
|---|---|---|
| `internal-lib` | Gradle | an "internal jar": publishes jar + sources jar to `../local-repo` |
| `gradle-multi` | Gradle | multi-project app consuming `internal-lib` by coordinate, a Kotlin module, and `spring-app` (Boot 3.5 controller, service, JPA, Kafka, config) |
| `maven-multi` | Maven | multi-module app consuming `internal-lib` by coordinate |

Build order matters: publish the library first.

```
cd internal-lib && ./gradlew publish
cd ../gradle-multi && ./gradlew build
cd ../maven-multi && ./mvnw -B -ntp package
```

`internal-lib` is published POM-only (no Gradle module metadata), like a Maven-built enterprise jar,
and ships a Boot auto-configuration whose compile-only dependencies are absent from its POM.
`gradle-multi/app` and `maven-multi/app` both declare `commons-lang3:3.14.0` next to
`commons-text:1.10.0` (which wants 3.12.0) so a version conflict is always present.

To produce a manifest by hand:

```
cd gradle-multi && ./gradlew --init-script <init> jirrafeResolve      # see JirrafePluginTest for the init script
cd maven-multi  && ./mvnw compile io.jirrafe:jirrafe-maven-plugin:0.1.0-SNAPSHOT:resolve
```

`benchmark/` holds the question sets `jirrafe bench` reads: `gradle-multi.json` and
`maven-multi.json` run in CI against the fixtures above, and `TEMPLATE.json` is the starting point
for measuring the graph on your own project.

`local-repo/` and `.jirrafe/` are generated and git-ignored.
