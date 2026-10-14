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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Every thread or task that delivers Health events has one owner that ends it",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-11 (b65 builder brief)'; the text does not appear in the owner's turns of session 886833dd"
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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Normal shutdown ends every open SSE attachment (Health and every other stream the server holds) promptly and exits 0 with clean=true",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-11 (b65 builder brief)'; the text does not appear in the owner's turns of session 886833dd"
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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "the exit code and clean flag must report a real failure when there is one",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-11 (b65 builder brief)'; the text does not appear in the owner's turns of session 886833dd"
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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "if the candidate retains state across refresh that it does not need, remove it",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-11 (b65 builder brief)'; the text does not appear in the owner's turns of session 886833dd"
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

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch5-20261012",
  "verdict": "reject",
  "digest": "sha256:24618fb96010b747b617d7a1cc093f9643ccc4337ad350a769c1518330a38024",
  "note": "verify-batch5 B5-H1: with the interruptible Health channel, a synchronous publish() whose subscriber is unsubscribed from another thread leaves the publishing (producer) thread interrupted in 400/400 races; its next FileChannel read throws ClosedByInterruptException. 8eb24a870 never interrupts the producer. Production synchronous publishers include CapabilityHealthBridge.emitRecoveryOccurrence. The listed checks pass and fail before, but miss this producer side effect. Report: F:/agent-sandbox/design-b6d536/codex/verify-batch5/report.md"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch6-20261012",
  "verdict": "accept",
  "digest": "sha256:24618fb96010b747b617d7a1cc093f9643ccc4337ad350a769c1518330a38024",
  "note": "verify-batch6 on e331c571b: the B5-H1 regression is repaired: an unsubscribe from another thread during a synchronous Health publish leaves the producer uninterrupted in 400/400 races with FileChannel reads succeeding (b639e82c5: 3 failing cases); previous verifier cases H1-H3, O1-O10, V1-V6, R2-R4, B5-H2, B5-C1, B5-C2 unchanged (H4 fails on both revisions as since verify-batch4, probe design)."
}
```
