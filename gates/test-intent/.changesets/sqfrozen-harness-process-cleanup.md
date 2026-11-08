---
schema: test-intent.v1
task: t-20261106-frozen-index
author-role: builder
author-session: 01a1b8c1-ef5c-7552-bdbf-8a9e34966de1
---

# Test-intent entries

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineTestHarness.java"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Keep the embedded Engine fixture isolated by retiring its own process resources after successful index teardown, including across explicit harness restarts.",
        "callersAndStateOwners": "Each EngineTestHarness open constructs a new EngineRoot with its own process resources. EngineRoot.close remains an index-only, restartable operation; the whole test harness owns the enclosing process lifetime.",
        "indirectBehaviours": "Only helper teardown changes. Existing inputs, assertions, fault injection, tags and timeouts stay unchanged. Index close runs first; its failure prevents process teardown. The existing finally still clears fixture fields and restores process properties after either close fails. Restart closes the old fixture before constructing its replacement.",
        "evidence": "EngineTestHarnessProcessCleanupTest executes work on the actual fixture-owned executor, then checks executor shutdown, closed registry admission and closed component admission after harness close. The ecbdb296 before-state reaches the executor-shutdown assertion and fails because the executor remains live. The candidate combined batch passes all 14 tests with no skips: this new check plus the existing harness isolation, frozen composition, migration lifecycle, startup cleanup and restartable Engine executor lifetime classes.",
        "unresolvedRisks": "This is model-free test-fixture ownership evidence. It does not add production runtime, model-quality or scoring evidence. Independent assessment and digest-bound acceptance are required for this helper change."
      }
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "reviewer",
  "session": "01a1b879-70a2-73f0-9a5e-3231c1e53dae",
  "verdict": "accept",
  "digest": "sha256:f4fc3ee2cf1e9820e96f37494f97ddfa656f91a93bba6c62bf322cee814237f0",
  "note": "Exact Incidental proposal accepted by independent reviewer 01a1b879-70a2-73f0-9a5e-3231c1e53dae on 754754ae. CLI transcription by orchestrator 01a1b0c3-481a-7d22-87d4-0ea0ad710b2c. Source and intent only; no execution or product PASS inferred."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "01a1b879-70a2-73f0-9a5e-3231c1e53dae",
  "verdict": "accept",
  "digest": "sha256:f4fc3ee2cf1e9820e96f37494f97ddfa656f91a93bba6c62bf322cee814237f0",
  "note": "Independent source/intent audit: fixture-owned process teardown follows successful index close, preserves existing oracles and restart semantics, and has a meaningful executor shutdown regression check. Execution counts were not independently verified here; no runtime or product PASS inferred."
}
```
