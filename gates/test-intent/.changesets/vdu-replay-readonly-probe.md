---
schema: test-intent.v1
task: t-20261018-6108b0
author-role: builder
author-session: d1ac5a04-dc16-4c57-b4ee-9db565185dd8
---

# Observe VDU replay through read-only queue probes

The existing replay oracle is preserved. This author records no acceptance.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineVduMigrationReplayTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Only the live jobs.db observation changes. requireBuffered selects generation, key, op, payload, last_updated, revision and accepted_order and maps them with the same JDBC getters into the canonical seven-field SwitchBufferOp constructor as the product listSwitchBufferOps path. It fails when the key is absent or not unique. bufferedDepth uses SELECT COUNT(*) FROM switch_buffer. Both use DriverManager with Xerial open_mode=1 (SQLITE_OPEN_READONLY) and busy_timeout=5000; no schema initialization or migration is invoked. awaitBufferedDepth keeps its 100 ms polling and original timeout. seedLegacyVduUpdate still runs with the Engine closed and uses SqliteJobQueue.putSwitchBuffer and listSwitchBufferOps. All existing assertions, messages, inputs, preconditions, expected outcomes, timeouts, step order, tags and conditions remain. Source: CI run 40404506498 attempt 1, job \"Unit tests (platform-contracts-1)\", SQLite SQLITE_BUSY at EngineVduMigrationReplayTest.java:262 (openQueue), reached from requireBuffered at line 140 while the real Engine owned the same jobs.db. The observation previously opened a second writing queue and ran schema migrations against the live writer; it now reads the same durable row without creating that competing write transaction.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including red-main and flaky-test repairs",
          "choice": "Inspect the live Engine switch buffer through a read-only JDBC connection instead of opening a second writing SqliteJobQueue; preserve the complete durable-row and replay oracles.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "orchestrator",
  "session": "cc6d91b2-5314-42fd-867b-dbc2d8a68699",
  "verdict": "accept",
  "digest": "sha256:c74d2b485891d60c2bfa65974eb1dd0dea97a021852b17d7fd952830f295d5d2"
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "01a17bbd-c49e-7fe3-a00c-685087755c34",
  "verdict": "accept",
  "digest": "sha256:c74d2b485891d60c2bfa65974eb1dd0dea97a021852b17d7fd952830f295d5d2"
}
```
