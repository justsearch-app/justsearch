---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, Spec B (item 6) step 4: the extraction pool (`PersistentExtractionSandbox`)
starts and stops every child through `ChildControl`; the stderr termination marker, the pool's own
`destroyForcibly` kill and its registration-rollback seam are gone (the rollback is the control's
`start`). The Engine's control reaches the pool through `EngineRoot`, `KnowledgeServer`,
`DefaultWorkerAppServices` and `ExtractionSandboxFactory`; compositions that pass only a manifest
registry get an unrecorded control.

Evidence: `gates/test-intent/evidence/b6d536-extraction-pool.json` runs all three checks on the
candidate. `gates/test-intent/evidence/b6d536-extraction-pool-before.json` is the fail-before of
`PersistentExtractionSandboxStopCodeTest` on 27f19c3ac: every forced extraction child there exits 1
instead of 0x4A530001 (assertion failures). `PersistentExtractionSandboxChildControlTest` does not
compile on 27f19c3ac, because the pool there cannot be given a `ChildControl`; no assertion
fail-before is claimed for it.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxChildControlTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ChildControlFixtures.java"
      ],
      "class": "New",
      "behaviour": "Spec B T19, against real stub child JVMs and the real OS terminator, with the control's records captured in order. A request timeout is one stop-intent asked by extraction-pool with reason TIMEOUT and mode NOW, then a child-exit REQUESTED(TIMEOUT), forced, with the control's code. An interrupted caller is REQUESTED(CANCELLED). A malformed response is REQUESTED(RECYCLE) with detail protocol. The request budget is REQUESTED(RECYCLE) with detail request_budget. Pool close is a cooperative QUIT: REQUESTED(QUIT), code 0, not forced. A child that exits 3 mid-request is not stopped (no stop-intent) and is UNEXPECTED(3) with no note; a child that dies of heap exhaustion is UNEXPECTED(1) followed by one child-note oom.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for items 5 and 6",
          "choice": "The pool's reasons map to stop requests asked by extraction-pool: a request timeout is TIMEOUT, an interrupted caller CANCELLED, a protocol failure RECYCLE with detail protocol, the request budget RECYCLE with detail request_budget, all NOW; pool close is one cooperative QUIT for all children (close stdin, one shared 2 s grace, then terminate); JVM shutdown without close is QUIT NOW. A child that ends by itself is not stopped: its own recorded exit is read, and the pool adds the note oom when the child's stderr shows OutOfMemoryError. One still running 2 s after it broke the protocol by itself is stopped as RECYCLE with detail protocol.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-extraction-pool.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxChildControlTest.java"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxStopCodeTest.java"
      ],
      "class": "New",
      "behaviour": "Every extraction child the pool ends (deadline, protocol recycle, request-budget recycle, close forcing a child that ignores EOF) exits with the child control's terminate code: JS_CHILD_STOP (0x4A530001) on Windows, the platform's code elsewhere. Observed on the real OS exit code of the pool's first child, through the pool's existing registry constructor.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for items 5 and 6",
          "choice": "Extraction children are ended only by the child control's terminate, never by the JDK's destroyForcibly, so on Windows a forced extraction child always exits 0x4A530001, a code no child exits with by itself.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-extraction-pool-before.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxStopCodeTest.java"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation", "signature", "rename"],
      "whatMoved": "Four tests pinned observations that step 4 retires (the stderr marker, destroyForcibly with exit 1, the pool's rollback seam); each keeps the behaviour it protects and changes only how it observes it. (1) gracefulCloseForceKillsChildIgnoringEofOnlyAfterGracePeriod, instrumentation. Oracle kept: a child that does not close within its grace is forced, only after the whole 2 s grace, within the 15 s host budget, and its real non-zero OS exit code is kept beside the termination intent. Observed through the control's child-exit record (REQUESTED, reason QUIT, forced, terminateCalledAt at least 2 s after close began, cooperativeAt before it) and the new exit code (the control's code, JS_CHILD_STOP on Windows), instead of the stderr marker, a mocked destroyForcibly and exit 1. (2) survivingRetirementRetainsItsSlotUntilKillActuallyCompletes, instrumentation. Oracle kept: a timed-out child whose kill does not take keeps its slot and its exact live process, a second request fails without spawning a replacement, and once the kill can complete the next request is served by a new child. The kill is refused by an injected ProcessTerminator (terminate fails, the wait returns at once) instead of a mocked destroyForcibly and waitFor. (3) failedRegistrationRollbackRetainsHandleForCloseRetry, signature and instrumentation. Oracle kept: when a failed registration's rollback does not end the child, its handle is retained and the close retry ends it (2 then 3 terminate attempts, formerly 1 then 2 destroy calls). The rollback seam moved from the pool to ChildControl.start, so the test drives it through the pool's spawn with a refusing manifest registry, a real child and a refusing terminator, and the retry is the control's close. (4) survivingRollbackMakesCloseFailAndKeepsHandleForFinalRetry, renamed survivingRollbackIsReportedAndKeepsHandleForFinalRetry, with the same seam move. Oracle kept: a rolled-back child that survives the retry too is reported and its handle is kept for a final retry that ends it. The report is the control's child-survived record (one per failed attempt), the ROLLBACK intent and the SURVIVED handle, instead of the pool's close throwing; the pool no longer holds that child. The fake RetryKillProcess helper is deleted with the seam.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S13",
          "quote": "If the app's shell crashes or is killed, the Engine and every helper end within seconds; the next launch starts normally.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S16",
          "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P5",
          "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for items 5 and 6",
          "choice": "A child whose manifest registration fails is rolled back by the child control that started it, which also keeps a survivor's handle and retries it on its close; the termination intent of every pool stop is the control's ledger record, not a stderr line.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesSandboxProbeTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "DefaultWorkerAppServices.buildContentExtractor now takes the ChildControl that starts the extraction children instead of a manifest registry, so the three calls pass DefaultChildControl.unrecorded(ManagedChildRegistry.noop()) where they passed ManagedChildRegistry.noop(). Inputs, assertions and oracles are unchanged."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "9ab35ce5-d0fe-4b77-bc6b-47bb55c9efa6",
  "verdict": "accept",
  "digest": "sha256:33c3fb1918a49e9f2bafa181b5548d3fc41594231fda080bc10bbb9f491ed932",
  "note": "StopCodeTest fail-before was re-run by the verifier on b76f89ba2 (a port without the ProcessTerminator import): 4 of 4 assertion failures, expected 1246953473 but was 1, the reason under test. The claimed before commit 27f19c3ac is not an ancestor of HEAD; this replaces it. ChildControlTest is compile-only before, as claimed. The four PersistentExtractionSandboxTest adaptations keep their oracles. survivingRollback moves its report from the pool's close throwing to the control's child-survived record and SURVIVED handle, consistent with Spec B 2.2 step 6; accepted with that note. The SandboxProbe signature change is mechanical. All pass on 9b88b9e03 (worker-services 1641 tests, 0 failures)."
}
```
