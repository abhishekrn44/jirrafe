---
name: Bug report
about: Something is extracted wrong, missing, or crashes
labels: bug
---

**What happened**

**What you expected**

**How to reproduce**

A minimal project or a snippet of the code that is indexed wrongly is the fastest way to a fix.
If the issue is in the graph, the node id (`com.acme.Foo#bar(java.lang.String)`) and the output of
`get_node` or `explain` help.

**Environment**

- jirrafe version (release tag or commit):
- Build tool and version (Gradle / Maven):
- JDK:
- OS:
- Frameworks in the project (Spring Boot version, Lombok, MapStruct, ...):

**Logs**

Output of `jirrafe build --dir .` (it reports the compile error count per module).
