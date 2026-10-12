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
