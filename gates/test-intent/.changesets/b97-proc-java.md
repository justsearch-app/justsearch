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
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatJobAccountingTest.java"
      ],
      "class": "New",
      "behaviour": "The real Windows control with an idle chat-kind child adds no more than 100 ms of JVM CPU over ten seconds compared with a non-chat child. A later admission retries a transient descendant-listing failure and clears the refusal once the descendants are verified gone, without restarting the control. A wrapper that exits at once after spawning its server leaves that actual serving descendant in the chat scope in all twenty trials. Self-test cleanup rechecks the retained scope after the earlier snapshot drains and rejects a later live member.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting), including round-2 process accounting repairs.",
          "choice": "Use the existing Windows Engine FFM layer to assign a suspended chat root to its own nested job before any instruction can spawn descendants. Query membership only on demand; idle chat adds at most one percent of a core. Retry transient verification failures on later admission or cleanup, retain unresolved ownership, and distinguish confirmed root exit with unverified descendants from actual survival. Recheck the retained scope after an earlier descendant snapshot drains, before declaring self-test success. Preserve launch pipes, arguments, environment, working directory, and rejection of NUL arguments.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java-round2.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatJobAccountingTest.java#idleChatControlAddsLessThanOnePercentOfACore",
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatJobAccountingTest.java#laterAdmissionRetriesAListingFailureAndClearsTheBlock",
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatJobAccountingTest.java#immediateWrapperExitRetainsTheActualSurvivorEveryTime",
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatJobAccountingTest.java#selfTestChecksMembershipAfterObservedDescendantsDrain"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaStopVerificationTest.java"
      ],
      "class": "New",
      "behaviour": "Confirmed root exit with unreadable descendants reports exited, descendants unverified, retains the child for retry, and clears ownership after a later stop verifies the descendants gone.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting), including round-2 process accounting repairs.",
          "choice": "Use the existing Windows Engine FFM layer to assign a suspended chat root to its own nested job before any instruction can spawn descendants. Query membership only on demand; idle chat adds at most one percent of a core. Retry transient verification failures on later admission or cleanup, retain unresolved ownership, and distinguish confirmed root exit with unverified descendants from actual survival. Recheck the retained scope after an earlier descendant snapshot drains, before declaring self-test success. Preserve launch pipes, arguments, environment, working directory, and rejection of NUL arguments.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java-round2.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaStopVerificationTest.java#exitedRootWithUnreadableDescendantsIsUnverifiedAndLaterStopRecovers"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatLaunchCompatibilityTest.java"
      ],
      "class": "New",
      "behaviour": "Replacing JDK launch with suspended native launch preserves UTF-8 pipe bytes, Unicode and quoted/trailing-backslash arguments, overridden environment, working directory, separate stderr, and NUL-argument refusal.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting), including round-2 process accounting repairs.",
          "choice": "Use the existing Windows Engine FFM layer to assign a suspended chat root to its own nested job before any instruction can spawn descendants. Query membership only on demand; idle chat adds at most one percent of a core. Retry transient verification failures on later admission or cleanup, retain unresolved ownership, and distinguish confirmed root exit with unverified descendants from actual survival. Recheck the retained scope after an earlier descendant snapshot drains, before declaring self-test success. Preserve launch pipes, arguments, environment, working directory, and rejection of NUL arguments.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatLaunchCompatibilityTest.java#chatLaunchPreservesPipesArgumentsEnvironmentAndWorkingDirectory"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerCandidateContextTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "A Windows chat launch now starts through WindowsEngineJob.createChat (suspended, inside its own job) instead of ProcessBuilder.start(). oneShotRecoveryHealthFailureCannotRelaunchFallbackLadders hands back the same pre-started sleeping child at that seam as well: a static stub returns a ChatProcess mock that forwards every Process method to that child. The ProcessBuilder construction stub and its one-construction assertion, the inputs, the killing registry and every expected outcome are unchanged; on a platform or revision without the contained launch the stub is never reached."
    }
  ]
}
```

## Acceptance records
