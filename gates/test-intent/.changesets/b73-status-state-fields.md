---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b73-status-state-fields
---

The status-change counter advances on state, never on measurement; fresh canonical reads are bounded; bodies without metadata are ordered by request issue. Independent acceptance remains pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/StatusStateProducersTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Every canonical status field and owner publication is classified as state, measurement or time, and the completeness check fails for an unclassified field or producer. Over real HTTP, GPU utilisation, VRAM, history arrays, telemetry throughput and its HEALTHY/UNKNOWN window, counts and clocks never advance the counter, while a state change still does; a failed 422 action after measurement-only changes carries no counter above the displayed reflection. Indexing-job counts and watched-root timestamps are not state.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "classify every canonical status field as state, measurement or time; measurements, gauges, rates, history arrays, clocks and ages never advance the counter",
          "location": "task t-20261005-b6d536 request (b73 builder brief, Fix 1 / S20)"
        }
      ],
      "evidence": "gates/test-intent/evidence/b73-status-state-fields-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#everyReportedStatusFieldIsClassified",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#completenessCheckFailsForAnUnclassifiedFieldOrProducer",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#classificationHasNoEntryForAFieldThatNoLongerExists",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#measurementsAndClocksAreNotComparedButStatesAre",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#everyOwnerPublicationIsAClassifiedProducer",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#idleCounterIgnoresMeasurementChanges",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#failedActionWithMeasurementOnlyDiffCarriesNoNewerCounter",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/StatusStateProducersTest.java#indexingJobCountsWithinTheSameStatesDoNotAdvanceTheCounter",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/StatusStateProducersTest.java#watchedRootTimestampsDoNotAdvanceTheCounter"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/statusStorm.test.ts",
        "modules/ui-web/src/api/statusCounterSignal.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "At most one fresh canonical read is in flight and at most one is started per observed counter value; a held lane cannot turn ordinary requests into fresh reads; refused or failed fresh reads back off, honouring Retry-After, while ordinary polling continues and the display catches up at the next successful read. A body without metadata may replace the display only when it answers a read issued after the displayed body and no newer epoch contradicts it, so the UI converges within the poll interval when the server supplies no metadata. The round-6 verifier cases (hung storm, refused storm, metadata loss, out-of-order, epoch invalidation, idle) are copied with their assertions kept. statusCounterSignal's 'a later request may retry the pending publication' expectation is replaced: the same value is not read again, a later value is.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "at most one fresh read in flight; at most one fresh read per observed counter value (a fresh read that returns without reaching that value does not start another for the same value)",
          "location": "task t-20261005-b6d536 request (b73 builder brief, Fix 3 / S20)"
        },
        {
          "kind": "owner-words",
          "quote": "a body without metadata may replace the display only if it answers a request issued after the displayed body was received and no newer epoch or revision is known to contradict it",
          "location": "task t-20261005-b6d536 request (b73 builder brief, Fix 2 / S20)"
        }
      ],
      "evidence": "gates/test-intent/evidence/b73-status-state-fields-ui.json",
      "newChecks": [
        "modules/ui-web/src/api/statusStorm.test.ts",
        "modules/ui-web/src/api/statusCounterSignal.test.ts"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status7-independent",
  "verdict": "reject",
  "digest": "sha256:a66c7bc5c36424c6619eb5e3a2826a77601abefd19d87ee496e136207f2800ff",
  "note": "Independent verify-status7 on 6f177f241: FAIL S20 / OWN7-BACKOFF. On the built UI against the running candidate head, after 40 s of refused fresh reads (6 refused, back-off works) and a successful ordinary poll, a lock by another client and, separately, the mounted UI's own successful lock action are not shown at the next render: 0 fresh reads, display unlocked while canonical is locked, caught up only 8-15 s later by ordinary polling. statusInvalidation.ts keeps backoffUntil (up to 30 s, Retry-After up to 300 s) until a successful signal-triggered fresh read; successful ordinary polls do not end it and due() gates every action and receipt signal on it. Unit form fails on 6f177f241 and passes on 823c82104. Otherwise: builder Java checks fail on 823c82104 by assertion and pass; classification walk finds 0 unclassified of 48 types; measurement-only changes no longer advance the counter (own Java, live idle 595 s with 0 advances, live chat-load window where every advance carried a state change); one fresh read per counter value; lock/unlock, failed, read-only, replay, approval, chat, GPU, indexing, raw settings, second tab, restart and full-response checks pass. The changed statusCounterSignal expectation is justified by the one-read-per-value rule; a later request still reads a later value and ordinary polls catch up, so a change is delayed by at most one poll, not lost."
}
```
