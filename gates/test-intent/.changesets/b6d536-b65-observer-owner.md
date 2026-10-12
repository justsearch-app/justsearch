---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b65-batch-observer-owner
---

# Health observer ownership, SSE shutdown and refresh retention

Independent verifier acceptance remains pending. The private execution adapter changes only process identity guarding, private build paths, dependencies and junction-safe cleanup; repository governance tooling and test assertions are unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeObserverLifetimeTest.java"
      ],
      "class": "New",
      "behaviour": "Unsubscribe, bridge close, channel close and the registered Engine delivery owner shutdown release an interruptible blocked Health observer independently of publication.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2026-10-11 (b65 builder brief)",
          "quote": "Every thread or task that delivers Health events has one owner that ends it"
        }
      ],
      "conflictSearch": "R1-R4 and existing publication, recovery and shutdown checks retain their expectations. Genuine drain failures must remain observable.",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeObserverLifetimeTest.java#eachOwnerBoundaryReleasesBlockedObserver",
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeObserverLifetimeTest.java#synchronousHealthDeliveryAlsoEndsAtItsOwnerBoundary",
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeObserverLifetimeTest.java#initialHealthReplayPrefixAlsoEndsAtItsOwnerBoundary",
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeObserverLifetimeTest.java#retiredRegistrationDoesNotBeginHealthPrefix"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b65-observer-owner.json",
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/SseTransportOwnerShutdownTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/SseAdmissionShutdownTest.java"
      ],
      "class": "New",
      "behaviour": "Normal shutdown closes open Health, snapshot, event-only, multiplexed, run and blocking SSE streams through real HTTP, including blocked snapshot setup. Streams relinquish actual Engine admission before the production ordered work drain; shutdown completes promptly with exit 0 and clean=true.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2026-10-11 (b65 builder brief)",
          "quote": "Normal shutdown ends every open SSE attachment (Health and every other stream the server holds) promptly and exits 0 with clean=true"
        }
      ],
      "conflictSearch": "R1-R4 and existing publication, recovery and shutdown checks retain their expectations. Genuine drain failures must remain observable.",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/SseTransportOwnerShutdownTest.java#normalShutdownEndsOpenStreamsBeforeGracefulDrain",
        "modules/ui/src/test/java/io/justsearch/ui/SseAdmissionShutdownTest.java#streamsRelinquishAdmissionBeforeOrderedWorkDrain"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b65-observer-owner.json",
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/LocalApiServerTransportShutdownTest.java"
      ],
      "class": "New",
      "behaviour": "A real selector or graceful-drain failure propagates to ordered shutdown accounting while later shutdown phases still run.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2026-10-11 (b65 builder brief)",
          "quote": "the exit code and clean flag must report a real failure when there is one"
        }
      ],
      "conflictSearch": "R1-R4 and existing publication, recovery and shutdown checks retain their expectations. Genuine drain failures must remain observable.",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/LocalApiServerTransportShutdownTest.java#selectorTeardownThatNeverReturnsCannotConsumeWholeGrace",
        "modules/ui/src/test/java/io/justsearch/ui/api/LocalApiServerTransportShutdownTest.java#wedgedRequestStillReachesOrderedHeadIndexCloseAndExit"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b65-observer-owner.json",
      "repaired": true
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkAdmissionRefreshRetentionTest.java"
      ],
      "class": "New",
      "behaviour": "Twelve committed refresh cycles over 100000 chunks do not retain another chunk-sized membership heap beyond the settled initial scan.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2026-10-11 (b65 builder brief)",
          "quote": "if the candidate retains state across refresh that it does not need, remove it"
        }
      ],
      "conflictSearch": "R1-R4 and existing publication, recovery and shutdown checks retain their expectations. Genuine drain failures must remain observable.",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkAdmissionRefreshRetentionTest.java#repeatedRefreshDoesNotRetainAnotherChunkSizedHeap"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b65-refresh-retention-qualified.json",
      "repaired": true
    }
  ]
}
```
