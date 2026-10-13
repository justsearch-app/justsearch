---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b97-proc-java
---

# Test-intent entries

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/SynchronousGpuReleaseTest.java"
      ],
      "class": "New",
      "behaviour": "A synchronous exit continuation sees a running GPU release producer, with recovered release recorded before replacement spawn. A still-pending release timeout refuses spawning with a reason, then permits retry after release.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Retain observed descendant identities after a self-test wrapper exits; refuse chat while a survivor or unreadable enumeration remains; start GPU release polling before synchronous exit callbacks, and refuse a GPU start if its bounded wait ends while the poll is still pending. Completed timeout/unmeasured polls keep their existing recorded outcomes.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/SynchronousGpuReleaseTest.java#synchronousExitContinuationStartsReplacementAfterGpuRelease",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/SynchronousGpuReleaseTest.java#pendingGpuReleaseTimeoutRefusesSpawnAndAllowsRetryAfterRelease"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestProcessOwnershipTest.java"
      ],
      "class": "New",
      "behaviour": "An exited wrapper with a live serving descendant cannot pass its self-test. Closing a failed self-test session with chat desired cannot spawn main beside the descendant; status explains why and retry succeeds after its exit. Unreadable descendant enumeration throws instead of certifying an empty snapshot.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Retain observed descendant identities after a self-test wrapper exits; refuse chat while a survivor or unreadable enumeration remains; start GPU release polling before synchronous exit callbacks, and refuse a GPU start if its bounded wait ends while the poll is still pending. Completed timeout/unmeasured polls keep their existing recorded outcomes.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestProcessOwnershipTest.java#exitedSelfTestWrapperWithLiveDescendantCannotPass",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestProcessOwnershipTest.java#selfTestDescendantSurvivalBlocksMainReload",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestProcessOwnershipTest.java#unreadableDescendantsAreNotAnEmptySuccessfulSnapshot"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/ChildControlHarness.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SlowStartFakeLlamaServer.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ManagedChatSeam.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "signature"
      ],
      "whatMoved": "Fixture cleanup records creation instants at process startup and checks them before terminating; the seam accepts an injected child control so the session and candidate share the same real ownership boundary. Existing test inputs and oracles are retained."
    }
  ]
}
```

## Acceptance records
