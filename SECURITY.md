# Security

## What jirrafe touches

- It reads your build (through the Gradle or Maven plugin), your sources, and the jars on your
  classpath. It writes only under `.jirrafe/` in the project directory.
- No telemetry. Network access happens only to your project's own artifact repositories (through
  your build tool), to the LLM provider you enable in `jirrafe.toml`, and to the URL you give
  `jirrafe pull`.
- Nothing is sent to an LLM unless `[knowledge] provider` is set. Even then only names, signatures,
  Javadoc and edges are sent; code snippets require `send_code_snippets = true`. Run
  `jirrafe knowledge --dry-run` to print exactly what would go out.
- The MCP server serves the local `graph.db`. Over stdio it is only reachable by the client that
  started it. Over HTTP it binds to `127.0.0.1`; do not expose it beyond localhost without an
  authenticating proxy.
- Decompiling is limited to jars the manifest classifies as internal. Public jars need
  `--i-understand-licenses`, and `deps.decompile = "never"` turns it off.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting: the **Security** tab of the repository, "Report a
vulnerability". Please do not open a public issue for security problems. You will get an acknowledgement within a week and a fix
or a mitigation plan as soon as the problem is understood.