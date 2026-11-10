---
schema: test-intent.v1
task: t-20261103-forgetflake
author-role: builder
author-session: forget-flake-builder-7lnojjvf
---

# Test-intent entries

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "S55 returned-root replay and the shared native migration scenarios scope the existing EngineTestHarness.knownVolume fixture before construction through close/reopen. Only host volume discovery is supplied; native held-directory checks, files, queues, extraction, policy guards and every existing expected outcome remain real and unchanged. The completion observer retains refusal details for deadline diagnostics and waits through retryable projection refusals until terminal promotion or its existing deadline. A separately opted-in regression scenario installs checked lock barriers; ordinary scenarios do not install them. All final receipt, replacement admission, current-byte hash, policy and reopen assertions remain enabled.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think agents should not ask me for any input if they know better, which in most work related tasks is the case.",
          "delegationLocation": "task t-20261103-forgetflake request",
          "delegationScope": "fixing a recurring CI flake",
          "choice": "Supply the scenarios' existing known-volume precondition with the scoped test fixture and observe retryable migration refusals until the existing deadline, preserving all final expectations and production guards.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTestHostAssumptionsTest.java"
      ],
      "class": "New",
      "behaviour": "Run the entire existing S55 returned-root replay scenario with the outer volume lookup returning null, proving it supplies and restores its own positive volume precondition. Separately force one actual queue tryLock eligibility refusal for R9\u2019s policy-skipped target, hold the next promotion before runtime ownership until the ordinary observer sees the exact projection refusal, then require retry, promotion and every original replacement receipt/current bytes/unchanged forget receipt/reopen assertion. The test-only before patch adds only opt-in instrumentation wiring; neither scoped volume evidence nor the observer repair is present before.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think agents should not ask me for any input if they know better, which in most work related tasks is the case.",
          "delegationLocation": "task t-20261103-forgetflake request",
          "delegationScope": "fixing a recurring CI flake",
          "choice": "Pin host-independent setup with null external volume evidence and pin observer retry behavior with real lock contention and checked barriers, reusing the complete existing scenarios as the oracle.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/forget-flake.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTestHostAssumptionsTest.java#returnedRootReplaySuppliesVolumeEvidenceWhenHostHasNone",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTestHostAssumptionsTest.java#hotSaveObserverWaitsForRetryAfterBusyEligibilityRefusal"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTestHostAssumptionsTest.java"
      ],
      "class": "New",
      "behaviour": "Run the entire existing R9 HOT public Save/readmission scenario with the outer volume lookup returning null, proving the scenario supplies and restores its own positive volume precondition across construction, promotion and real close/reopen. Every original final receipt, replacement admission, current-byte hash, policy and reopen assertion remains enabled. The failing baseline scenario runs in its own JVM to isolate cleanup from the other deliberately failing baseline scenarios.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think agents should not ask me for any input if they know better, which in most work related tasks is the case.",
          "delegationLocation": "task t-20261103-forgetflake request",
          "delegationScope": "fixing a recurring CI flake",
          "choice": "Pin host-independent setup with null external volume evidence and pin observer retry behavior with real lock contention and checked barriers, reusing the complete existing scenarios as the oracle.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/forget-flake-host-r9.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTestHostAssumptionsTest.java#hotSaveReadmissionSuppliesVolumeEvidenceThroughReopenWhenHostHasNone"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
