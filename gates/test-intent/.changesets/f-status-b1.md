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
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.own-files.test.ts"
      ],
      "class": "New",
      "behaviour": "FB5: with 12 searchable documents of which 7 are the person's own (ownSearchableDocuments 7), Health's overview Files and its Diagnostics Files card show 7, the same count aiStateStore's index.ownDocumentCount (Library, AI Brain) and the composer's corpus projection read. When the Engine reports no own count, both show 'Unknown', not the whole-index 12 and not 0.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (top bar, Settings, status pill, counts): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "Follow-up B1 FB5 (orchestrator amendment, 2026-11-12): Health's overview Files count follows the batch A rule FA4: the person's own files (ownSearchableDocuments), bundled Help excluded, unknown said as unknown; it agrees with Library, the composer and AI Brain, which already read aiStateStore's index.ownDocumentCount. Health reads that same own count; a count the Engine could not take reads Checking…/Unknown, never the whole-index count or 0.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-status-b1-fb5.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.own-files.test.ts#Files leaves the bundled Help out and agrees with the composer, Library and AI Brain",
        "modules/ui-web/src/shell-v0/views/HealthSurface.own-files.test.ts#Files says unknown when the Engine does not report the person's own count, never the whole index or 0"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts"
      ],
      "class": "New",
      "behaviour": "FB5: when the whole-index (active) count is fresh at 12 but the searchable population the own count belongs to could not be counted (countObservations.searchable unknown), the Files fact says 'Unknown' instead of the whole-index 12. The case's other assertions (documentCount known 12, searchableDocumentCount unknown, no last settled index) are unchanged.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (top bar, Settings, status pill, counts): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "Follow-up B1 FB5 (orchestrator amendment, 2026-11-12): Health's overview Files count follows the batch A rule FA4: the person's own files (ownSearchableDocuments), bundled Help excluded, unknown said as unknown; it agrees with Library, the composer and AI Brain, which already read aiStateStore's index.ownDocumentCount. Health reads that same own count; a count the Engine could not take reads Checking…/Unknown, never the whole-index count or 0.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-status-b1-fb5.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts#distinguishes a failed filtered count from the fresh whole-index count"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (top bar, Settings, status pill, counts): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "Follow-up B1 FB5 (orchestrator amendment, 2026-11-12): Health's overview Files count follows the batch A rule FA4: the person's own files (ownSearchableDocuments), bundled Help excluded, unknown said as unknown; it agrees with Library, the composer and AI Brain, which already read aiStateStore's index.ownDocumentCount. Health reads that same own count; a count the Engine could not take reads Checking…/Unknown, never the whole-index count or 0.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions has no record on which population Health's Files count reads; the aiStateStore comment that called the whole-index documentCount 'the right number for Health's Files' is production code, updated in this change)"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "'a counted index is unchanged': the two fed cores gain ownSearchableDocuments equal to their indexedDocuments (0 and 12), because the Files fact now reads the own count; expected '0' and '12' unchanged. 'keeps fallback counts visibly last known': the core gains ownSearchableDocuments 12 and searchableDocuments moves 10 → 12 (an own count cannot exceed the searchable population), and countObservations gains searchable stale with the same observedAtMs beside active stale, because the fact's last-known provenance now follows the searchable population; every assertion and expected value is unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/display/facts.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The two core.files cases feed the count as index.ownDocumentCount (known(605), known(1)) instead of index.documentCount, the field the Files fact now reads. Expected value '605', presence, and the no-confidence/no-provenance assertions are unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Five fed cores gain ownSearchableDocuments equal to their indexedDocuments/searchableDocuments (37, 11, 37, 1234, 1234) so the Files card, which now reads the own count, has its input. Every expected value (37, '1,234', 'Last known', '…' for Size) and every other input is unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.outage.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The stand-in Engine's status core gains ownSearchableDocuments 5 beside indexedDocuments 5 and searchableDocuments 5, so Health's Files metric, which now reads the own count, has its input. Expected '5' and the last-known/unconfirmed assertions are unchanged."
    }
  ]
}
```

Follow-up B1 amendments FB2a, FB5 and FB6 (2026-11-12):

FB5 moves Health's Files count (the `core.files` fact, its overview metric and its Diagnostics card)
from the whole-index `documentCount` onto aiStateStore's `index.ownDocumentCount`, the count Library,
AI Brain and (through the last settled index) the composer read. `HealthSurface.own-files.test.ts`
is an isolated new file; it has an entry anyway so its fail-before evidence is recorded with the
change. Four existing frontend tests fed only the whole-index count and are re-encoded with the own
count; one expectation in `uncountedIndex.test.ts` changes (entries above).

FB2a (`scripts/jsharness`, `scripts/jseval`) and FB6 (`scripts/dev/doctor.mjs`) are covered by
checks outside this gate's scope (script self-tests and the jseval suite), so they have no entries
and run-evidence has no runner for them. They were recorded by running the candidate's check files
against the candidate and against c95be3048f (a shared clone at that revision with the candidate
check files copied in): `scripts/jsharness/tests/test_flatten_unknown_count.py` and
`scripts/jseval/tests/test_unknown_document_count.py` (pytest, 8 of 10 fail by assertion before,
10 pass after; the two counted-zero cases pass on both), `scripts/dev/doctor.test.mjs` (fails before
on `worker=undefined`, passes after).

## Acceptance records
