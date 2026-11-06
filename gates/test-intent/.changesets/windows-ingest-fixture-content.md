---
schema: test-intent.v1
task: t-20261102-redesign
author-role: builder
author-session: id01a1b0ce-f709-7bd2-b74b-cce5bfef26c1
---

# Test-intent entries

Fixture scaffolding adaptation; the diagnostics and starvation assertions remain durable checks.
JUnit class-scoped temporary user-content folders replace folders beneath protected application
data. Both batches remain present for the same Engine lifetime. JUnit removes the content after
the existing backend shutdown, including when a test fails. No production admission rules change.

The diagnostics oracle still matches ledger events by size and resolver paths by suffix; exact
submitted-file correlation remains unverified. The starvation oracle still observes global index
growth and drained, stable enrichment counters; exclusion of unrelated indexing from batch-B
attribution remains unverified. Neither oracle is redesigned here.

```json
{
  "entries": [
    {
      "items": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/api/IngestionDiagnosticsContractTest.java",
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/api/IngestStarvationE2ETest.java"
      ],
      "class": "Adaptation",
      "moved": ["input re-encoding"],
      "whatMoved": "Only the user-content parent directory and its cleanup ownership move: BACKEND.dataDir() becomes an independent JUnit @TempDir, cleaned after the existing @AfterAll backend stop. Corpus bytes, unique markers, file names, ingest request shape, chunk-producing batch A, same-Engine second batch, drained/stable observations, privacy exclusions, SHA-256 shape, extraction provenance, resolver assertions, tags, and every existing timeout and final assertion are retained.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "This handover covers the existing Windows CI repair, reconciliation and cleanup of merged E4c, and independent assessment of the already-built narrow P2b+c close.",
          "delegationLocation": "task t-20261102-redesign user turn 1",
          "delegationScope": "Test/CI fixture correction only; preserve diagnostics and starvation oracles and deadlines without product security exceptions or root grants.",
          "choice": "Use class-scoped JUnit @TempDir content parents, with automatic cleanup after the existing backend shutdown, instead of storing corpora under protected backend app data.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

Independent hosted Windows evidence remains pending. In separate, owned baseline and candidate
checkouts with separate build directories, run this exact class-level selection in each:

```powershell
.\gradlew.bat :modules:system-tests:integrationTest --tests io.justsearch.systemtests.api.IngestionDiagnosticsContractTest --tests io.justsearch.systemtests.api.IngestStarvationE2ETest -PskipWebBuild=true
```

Classify the baseline protected-app-data BAD_REQUEST as fixture-defect reproduction, not a new
product assertion fail-before (the current helper raises an exception). Classify candidate passes
as preservation of the original diagnostics and two-batch assertions after valid user-content
admission. Preserve JUnit outcomes, skips/retries and fixture logs; compilation alone proves neither.

The Node fixture regression is separate scaffolding coverage: baseline self-tests reproduced two
stale search-count assertions at `45cb5755de846d6edb634d481dbb69f901b24ca1`. The candidate passed
all 17 hostile-lock self-tests. To reproduce the one-root assertion fail-before, copy the candidate
`scripts/supervisor-conformance/hostile-lock-scenario.test.mjs` into an owned baseline shared clone
and run `node --test --test-name-pattern='^healthy hostile-lock run' scripts/supervisor-conformance/hostile-lock-scenario.test.mjs`;
this failed on the unchanged baseline scenario with "submit all 100 documents under one directory
root". The complete file passed on the candidate. Running-to-complete ordering and final-outcome
negatives are preservation/strengthening
checks; hosted acquisition and recovery evidence is still required for real Windows claims.

## Acceptance records
