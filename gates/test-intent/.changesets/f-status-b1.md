---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: f-status-b1-builder
---

# Follow-up B1: the status fallback says the Worker's counts are unknown

While the Engine has no reading from the Worker (not connected yet, its first answer pending, or
the status RPC failed), `/api/status` publishes `WorkerOperationalView.fallback`. Its core used to
carry `indexedDocuments` and `searchableDocuments` 0 with `indexedDocumentsUnknown` false, so a
worker outage read as a known empty index. `CoreIndexView.fallback` now sets
`indexedDocumentsUnknown` true, the wire's existing unknown form; the shape of the JSON does not
change.

`WorkerOperationalFallbackTest` gains one check that the fallback's status JSON says the counts are
unknown. It fails by assertion on `origin/main` (`expected: <true> but was: <false>`) and passes
on the candidate (`gates/test-intent/evidence/f-status-b1.json`).

`StatusRecordSchemaTest.sampleStatusResponse()` generates the shared fixture
`modules/ui-web/src/api/__fixtures__/status-response-live.json`, a live status whose components are
READY. It took its Worker block from `fallback("SERVING")`, so with this change the fixture would
have turned into an outage status for every UI test that reads it. It now builds the same Worker
block with an explicit counted core, field for field the old fallback core with
`indexedDocumentsUnknown` false. The fixture file is unchanged and the drift check
("StatusResponse fixture matches current Java serialization") compares against the same bytes.

The new handler test `modules/ui/.../StatusWorkerCountUnknownTest.java` (worker absent at start,
slow first answer, RPC failure mid-session and back, counted empty index) is an isolated new file
and needs no entry.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/status/WorkerOperationalFallbackTest.java"
      ],
      "class": "New",
      "behaviour": "FB1: the Worker fallback view, published while the Engine has no Worker reading, serializes core.indexedDocumentsUnknown as true, so its placeholder zero document counts are not claimed as known.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (top bar, Settings, status pill, counts): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "Follow-up B1 FB1 with the orchestrator decision of 2026-11-12: when the worker's numbers are not known, every status the Engine publishes says so (unknown), never 0 as known. When the worker client is absent, at initial status and on a worker RPC failure, the published status marks indexedDocuments and searchableDocuments unknown (indexedDocumentsUnknown true, the wire's existing unknown form), not 0 as known.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-status-b1.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/status/WorkerOperationalFallbackTest.java#fallbackDocumentCountsAreUnknownInStatusJson"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/status/StatusRecordSchemaTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "sampleStatusResponse() builds the live sample's Worker block as WorkerOperationalViewBuilder.from(WorkerOperationalView.fallback(\"SERVING\")).withCore(new CoreIndexView(false, 0, 0, \"SERVING\", 0, 0, 0L, 0L, 0L, 0L, new long[0], new double[0], 0L, 0L, 0L, 0L, 0L, false, null)) instead of WorkerOperationalView.fallback(\"SERVING\"). The core is field for field the core the fallback produced at the base (it now sets indexedDocumentsUnknown true); every other sub-view is still the fallback's. The serialized sample, the shared fixture status-response-live.json it is compared with, every assertion and every other input are unchanged."
    }
  ]
}
```

## Acceptance records
