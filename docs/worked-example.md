# Worked example: "what is the auth mechanism in this code?"

The project is `jirrafe-fixtures/gradle-multi`, a Spring Boot service with an internal library jar.
The graph was built with `jirrafe build --dir jirrafe-fixtures/gradle-multi`. Everything below is
the real output of the MCP server, unedited apart from whitespace.

## Call 1: `explain`

```json
{"name": "explain", "arguments": {"question": "what is the auth mechanism in this code?", "token_budget": 1200}}
```

```json
{
  "question": "what is the auth mechanism in this code?",
  "flows": [],
  "communities": [
    {
      "id": "community:L0-0",
      "label": "com.example.orders",
      "summary": "12 classes in com.example.orders. Layers: controller 1, service 5, repository 1, client 1, config 2, model 1. Central: OrderService, Order, Notifier.",
      "central": "com.example.orders.OrderService,com.example.orders.Order,com.example.orders.Notifier"
    }
  ],
  "nodes": [
    {
      "id": "com.example.orders.ApiKeyInterceptor#isAuthorized(java.lang.String)",
      "kind": "method",
      "at": "spring-app/src/main/java/com/example/orders/ApiKeyInterceptor.java:27",
      "signature": "isAuthorized(java.lang.String): boolean",
      "uses": [{"id": "com.example.orders.ApiKeyInterceptor#apiKey", "kind": "reads_field"}],
      "usedBy": [{"id": "com.example.orders.ApiKeyInterceptor#preHandle(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,java.lang.Object)", "kind": "calls"}]
    },
    {
      "id": "com.example.orders.ApiKeyInterceptor",
      "kind": "class",
      "at": "spring-app/src/main/java/com/example/orders/ApiKeyInterceptor.java:10",
      "layer": "service",
      "signature": "public class ApiKeyInterceptor implements HandlerInterceptor",
      "doc": "The authentication mechanism: every request must carry the configured API key in the X-Api-Key header.",
      "uses": [
        {"id": "bean:apiKeyInterceptor", "kind": "provides_bean", "resolution": "spring"},
        {"id": "config:orders.api-key", "kind": "binds_config"},
        {"id": "jakarta.servlet.http.HttpServletRequest", "kind": "uses_type"},
        {"id": "jakarta.servlet.http.HttpServletResponse", "kind": "uses_type"},
        {"id": "org.springframework.stereotype.Component", "kind": "annotated_with"},
        {"id": "org.springframework.web.servlet.HandlerInterceptor", "kind": "implements"}
      ],
      "usedBy": [{"id": "com.example.orders.WebConfig", "kind": "uses_type"}]
    }
  ],
  "next": "get_node(id) for details, read_source(id) for code, impact(id) before changing anything; trust exact and spring edges over heuristic ones"
}
```

That is 469 tokens. The answer is already there: a `HandlerInterceptor` named `ApiKeyInterceptor`,
registered by `WebConfig`, bound to the `orders.api-key` configuration key, checking in
`isAuthorized`. No flow is involved because authentication happens before any route handler.

## Call 2: `get_node` with source

```json
{"name": "get_node", "arguments": {"id": "com.example.orders.ApiKeyInterceptor#isAuthorized(java.lang.String)", "include_source": true, "token_budget": 800}}
```

```json
{
  "id": "com.example.orders.ApiKeyInterceptor#isAuthorized(java.lang.String)",
  "kind": "method",
  "at": "spring-app/src/main/java/com/example/orders/ApiKeyInterceptor.java:27",
  "signature": "isAuthorized(java.lang.String): boolean",
  "module": ":spring-app",
  "outgoing": [{"id": "com.example.orders.ApiKeyInterceptor#apiKey", "kind": "reads_field"}],
  "incoming": [{"id": "com.example.orders.ApiKeyInterceptor#preHandle(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,java.lang.Object)", "kind": "calls"}],
  "source": {
    "file": "spring-app/src/main/java/com/example/orders/ApiKeyInterceptor.java",
    "startLine": 27,
    "text": "    boolean isAuthorized(String presented) {\n        return presented != null && presented.equals(apiKey);\n    }"
  }
}
```

## The answer an agent can give

> Authentication is an API key. `ApiKeyInterceptor` (`spring-app/src/main/java/com/example/orders/ApiKeyInterceptor.java:10`)
> implements Spring's `HandlerInterceptor`; `WebConfig` registers it for `/orders/**`. It reads the
> `X-Api-Key` header and compares it with the `orders.api-key` property (`application.yml`) in
> `isAuthorized` (line 27); a mismatch returns 401 before the controller runs. There is no
> Spring Security, no sessions, no roles.

Two tool calls, about 900 tokens, every claim cited. The grep baseline for the same question in the
benchmark (`jirrafe bench`) reads two files for 274 tokens and finds the interceptor but not the
registration; a real agent then usually greps again for the class name, opens `WebConfig`, and
reads `application.yml` to find the key.

## What to try next

- `impact("com.example.orders.ApiKeyInterceptor#isAuthorized(java.lang.String)")` before changing the check.
- `config("orders.api-key")` to see where the key is defined and everything bound to it.
- `routes()` to see which routes the interceptor path pattern covers.
