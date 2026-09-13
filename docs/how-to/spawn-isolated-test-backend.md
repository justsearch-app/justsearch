---
title: "Spawn an Isolated Test Backend"
type: how-to
status: stable
description: "Lite mode env flag (JUSTSEARCH_LITE_MODE) and the per-class IsolatedBackendFixture pattern for integration tests that need a clean backend per test class without paying the 3-8s cost of loading the AI stack."
audience: contributor
related: tempdoc 419 T6.1, T6.2-T6.4; ADR-0028
---

# Spawn an Isolated Test Backend

This guide covers the **lite mode** flag (`JUSTSEARCH_LITE_MODE`) and
the per-class isolated backend pattern (`IsolatedBackendFixture`,
shipped in T6.2). The intended use case is integration tests that need
a clean backend per test class without the multi-second cost of
loading the AI stack.

## When to use lite mode

Use `JUSTSEARCH_LITE_MODE=true` when your test:

- Exercises ingestion, indexing, search, or diagnostic endpoints.
- Does NOT need llama-server, agents, or LLM-generated responses.

Saves roughly **3-8s of startup** depending on hardware (avoids
llama-server probe + model-file checks).

Do NOT use lite mode if your test exercises:

- LLM completion or chat endpoints (`/api/chat/ask`, `/api/chat/agent/*`).
- Online inference state transitions.
- AI-pack import or installation flows.

## How lite mode works

`JUSTSEARCH_LITE_MODE=true` (env or `-Djustsearch.lite.mode=true`)
causes `HeadAssembly.createInferenceManager()` to return `null`,
which cascades through the existing degradation pattern at
`HeadAssembly`: `OnlineAiService.unavailable()` is
substituted, all 20+ AI consumers see the unavailable stub, and
calls to `.aiService()` return safely without crashing.

Equivalent in effect to `JUSTSEARCH_AI_DISABLED=true`. The two flags
are kept distinct because:

- `AI_DISABLED` is granular (just the AI stack).
- `LITE_MODE` is the canonical test-harness flag and can grow to
  cover additional skips (embedding model load, SPLADE init, etc.)
  if future tests need them.

For now, the two flags are functionally identical for the Head process.

## What lite mode does NOT skip

- **Worker initialization.** The index half (composed in-process by
  `EngineRoot` since lane F stage A — there is no separate Worker
  subprocess) still loads embedding/SPLADE models. For ingestion-only
  tests this is acceptable (~3-5s additional startup).
- **Lucene index opening.** Required for indexing endpoints to work.
- **Diagnostic endpoints.** Privacy-safe ledger reads and the scoped
  resolver (ADR-0028) are always available.

If a future test scenario needs an embeddingless Worker, that's a
separate flag (e.g., `JUSTSEARCH_WORKER_LITE_MODE`) — not in scope
of T6.1.

## Using `IsolatedBackendFixture` in tests

Tempdoc 419 T6.2 ships `IsolatedBackendFixture` (in
`modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/harness/`).
It spawns a fresh backend per test class with a tempdir data directory,
OS-assigned port, and lite mode enabled.

```java
class MyIntegrationTest {
  static final IsolatedBackendFixture backend = new IsolatedBackendFixture();

  @BeforeAll
  static void setup() throws Exception {
    backend.start();
  }

  @AfterAll
  static void teardown() {
    backend.stop();  // also nukes the tempdir
  }

  @Test
  void myTest() throws Exception {
    int port = backend.port();
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/health"))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(200, resp.statusCode());
  }
}
```

`backend.dataDir()` returns the tempdir used as `JUSTSEARCH_DATA_DIR` —
tests can place corpora under it so the same cleanup tear-down nukes
them in one shot.

`start()` blocks until `components.worker.state=READY` in
`/api/health`. Plain `200` means Javalin bound but the index half
may still be initializing; the fixture's stricter gate avoids that race.

For tests that ingest a corpus and then need to know it landed, prefer
polling `/api/diagnostics/ingestion/recent` for a `SUCCESS_FULL` event
with the matching `sourceSizeBytes` over polling
`/api/knowledge/search`. The first search after backend boot loads the
embedding ONNX session, and that load reliably exceeds the search
call's deadline (`KnowledgeClient.RpcDeadlineCategory`) — lane F stage A
deleted the gRPC channel and its circuit breaker (`GrpcCircuitBreaker`
no longer exists), but a slow first call still fails the call. The
ledger read does not depend on the model being warm.

Pass `-DisolatedBackend.preserveLogs=true` to keep `backend.log` and the
`app.log` / `engine.log` plus any crash reports under
`%TEMP%/isolated-backend-*` after the fixture stops; without the flag,
logs are dumped to stderr only on startup failure.

## Manual smoke test

To verify lite mode works without spawning a fixture:

```bash
JUSTSEARCH_LITE_MODE=true ./gradlew.bat :modules:ui:runHeadless
```

Expected: backend starts within ~10s (vs. 15-25s with full AI init).
The `/api/health` endpoint reports inference component as
`DEGRADED` / `inference.offline`, which is the normal lite-mode signal.
The `/api/diagnostics/ingestion/recent`, `/api/library/resolve-hash`,
and `/api/knowledge/search` endpoints all work normally.

## References

- Plan: `C:\Users\<user>\.claude\plans\snazzy-soaring-sonnet.md` §"Slice T6.1"
- Tempdoc 419 (unused user-agent capability discovery)
- ADR-0028 (related — the resolver works in lite mode): `docs/decisions/0028-scoped-reverse-path-lookup.md`
- EnvRegistry entry: `modules/configuration/src/main/java/io/justsearch/configuration/EnvRegistry.java`
  (`LITE_MODE` constant)
- Bootstrap entry point: `modules/app-services/src/main/java/io/justsearch/app/services/HeadAssembly.java`
  `createInferenceManager()` method
