---
schema: test-intent.v1
task: delete-residuals
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Test-intent entries

The transition deadline amendment is the orchestrator's decision under the delegated assignment, not a user or owner quote. During reload/migration/cutover, return recovery and every receipt wait; the D1/D2 clocks start when the surviving runtime accepts writes again. Migration and cutover behavior themselves remain unchanged. Real bind mounts are unverified because they require root here; tuple/receipt comparisons cover different bind subtrees in unit tests. The former large addition to the reload test is extracted into its own Linux drive-return test class. The orchestrator's conditional follow-up says to keep a failure also reproduced on main out of the committed suite or disable it with a named finding. The guarded shared clone at unmodified main b62fc5921 also fails to publish a writable survivor after the existing cutover request and restart, with the same seeded pending receipts. The two migration checks are disabled as REMOUNT-MIGRATION-REPLAY; they retain the refusal/row/data assertions and full reproduction. The row is seeded by the test helper; the existing production writer is unchanged. No migration/replay/promotion behavior is changed.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceSyncDirectoryPhaseTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261021-93a012 user turn 1",
          "delegationScope": "Fix-or-defer decisions on defects found during assigned work; here, the agreed P1 finding that a root whose filesystem is away is not reported through the unavailable-root classifier. Linux only; Windows behavior unchanged.",
          "choice": "Prefix Linux sync errors for a missing root with the existing ROOT_NOT_DIRECTORY classifier, preserving zero additions and deletions. Exercise the worker reply and the HTTP folder-status endpoint consumed by the UI.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Public decisions and existing ROOT_NOT_DIRECTORY classifiers were searched; private orchestrator records were not read under the builder contract. The missing-path literal is replaced only on Linux; zero-count assertions and other scenarios remain."
    },
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxLoopVolume.java"
      ],
      "class": "New",
      "behaviour": "Provision a disposable ext4 image via unprivileged udisks, retain device identities, and always unmount/detach in cleanup. Setup skips with a concrete reason if this facility is unavailable; skipped cases are never evidence of passing behavior. A decoy loop forces a changed device number. The fixture supports real drive-return checks; real bind mounts are explicitly excluded by the orchestrator because this host requires root.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think agents should not ask me for any input if they know better, which in most work related tasks is the case.",
          "delegationLocation": "task t-20261021-93a012 user turn 2",
          "delegationScope": "Work decisions on the owner's assigned JustSearch tasks, including the scenarios and scope of the watched-drive return fix (scenarios v2 with amendments: transition deadlines start when the surviving runtime accepts writes; migration/cutover behavior unchanged; real bind mounts unverified). Strict operation leases and migration/cutover behavior preserved.",
          "choice": "Use existing unavailable-root classification, isolated real-filesystem fixtures, shared production fixture instrumentation, and checks of watcher and receipt recovery. The timing amendment is an orchestrator decision, not an owner quote.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/remount-return.json",
      "newChecks": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxWatchedDriveReturnIntegrationTest.java#realUnmountReturnsWithHealthyWatcherReceiptsAndOfflineChanges"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The existing Fixture becomes package-visible for the extracted LinuxWatchedDriveReturnIntegrationTest. Each Engine temporarily owns user.home and justsearch.data.dir and restores both. A new optional test-only Hook installs the existing migration observation barrier before start; old constructors pass null and retain their configuration. No existing test body, expected outcome, injection, condition, tag or timeout is changed. The large new mount-test addition was moved to its own class; no real-bind fixture remains.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think agents should not ask me for any input if they know better, which in most work related tasks is the case.",
          "delegationLocation": "task t-20261021-93a012 user turn 2",
          "delegationScope": "Work decisions on the owner's assigned JustSearch tasks, including the scenarios and scope of the watched-drive return fix (scenarios v2 with amendments: transition deadlines start when the surviving runtime accepts writes; migration/cutover behavior unchanged; real bind mounts unverified). Strict operation leases and migration/cutover behavior preserved.",
          "choice": "Use existing unavailable-root classification, isolated real-filesystem fixtures, shared production fixture instrumentation, and checks of watcher and receipt recovery. The timing amendment is an orchestrator decision, not an owner quote.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/WorkerExecutorRegistrationsTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "All acquired worker executor registrations close in reverse acquisition order; partial acquisition still closes every already-acquired registration.",
      "whySurvives": "The expected close order keeps every original owner and adds the two return-recovery owners in reverse acquisition order. Existing limit-shape and rollback assertions remain unchanged; no owner or failure case is removed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think agents should not ask me for any input if they know better, which in most work related tasks is the case.",
          "delegationLocation": "task t-20261021-93a012 user turn 2",
          "delegationScope": "Work decisions on the owner's assigned JustSearch tasks, including the scenarios and scope of the watched-drive return fix (scenarios v2 with amendments: transition deadlines start when the surviving runtime accepts writes; migration/cutover behavior unchanged; real bind mounts unverified). Strict operation leases and migration/cutover behavior preserved.",
          "choice": "Use existing unavailable-root classification, isolated real-filesystem fixtures, shared production fixture instrumentation, and checks of watcher and receipt recovery. The timing amendment is an orchestrator decision, not an owner quote.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsWalkSkipPolicyTest.java"
      ],
      "class": "New",
      "behaviour": "On Linux a missing root returns ROOT_NOT_DIRECTORY and deletes nothing, so the existing status/UI unavailable classifiers recognize an absent filesystem.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261021-93a012 user turn 1",
          "delegationScope": "Fix-or-defer decisions on defects found during assigned work; here, the agreed P1 finding that a root whose filesystem is away is not reported through the unavailable-root classifier. Linux only; Windows behavior unchanged.",
          "choice": "Prefix Linux sync errors for a missing root with the existing ROOT_NOT_DIRECTORY classifier, preserving zero additions and deletions. Exercise the worker reply and the HTTP folder-status endpoint consumed by the UI.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/remount-return.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsWalkSkipPolicyTest.java#missingFilesystemUsesTheUnavailableRootReason",
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxWatchedDriveReturnIntegrationTest.java#unmountedRootIsUnavailableInTheStatusApiUsedByTheUi"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The existing producer-transfer fixture supplies real TestEngineExecutors registrations for the two new recovery executor owners. Fixture cleanup closes their registry. Existing test bodies, assertions, conditions and timeouts remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think agents should not ask me for any input if they know better, which in most work related tasks is the case.",
          "delegationLocation": "task t-20261021-93a012 user turn 2",
          "delegationScope": "Work decisions on the owner's assigned JustSearch tasks, including the scenarios and scope of the watched-drive return fix (scenarios v2 with amendments: transition deadlines start when the surviving runtime accepts writes; migration/cutover behavior unchanged; real bind mounts unverified). Strict operation leases and migration/cutover behavior preserved.",
          "choice": "Use existing unavailable-root classification, isolated real-filesystem fixtures, shared production fixture instrumentation, and checks of watcher and receipt recovery. The timing amendment is an orchestrator decision, not an owner quote.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootPresenceGuardTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsWalkSkipPolicyTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The one-argument RootVolumeLedger.forgetUnder(prefix), which only this test still called after production moved to the owner-filtered form, was removed (the unreferenced-code rule flagged it). The test now calls forgetUnder(prefix, ignored -> true), exactly what the one-argument form did. Likewise the four- and five-argument SyncDirectoryOps.execute overloads were removed (production now uses only the six-argument form); SyncDirectoryOpsWalkSkipPolicyTest now passes the null collection and null owner filter the removed overloads passed. Every input, precondition, assertion and scenario is unchanged."
    }
  ]
}
```

## Acceptance records

Pending independent acceptance by the orchestrator or verifier; the builder does not accept its own entries.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-remount-1",
  "verdict": "accept",
  "digest": "sha256:79ab37625e3052e19d7a3c0f66b0eb38f7d144397055a33b741c04c66b777435",
  "note": "Accept unchanged candidate d6e4c1a5e under the orchestrator exclusions: R1/D7 size-only edits with restored mtime are missed by normal startup on both b62fc5921 and candidate (confirmed independently), and are out of scope as a separately tracked pre-existing gap; D7 uses the startup reconciliation rule. R2/P10 legacy nested-root index data loss during parent removal also reproduces on b62fc5921 (confirmed independently), is no worse on candidate, and is out of scope as a separately tracked pre-existing defect; candidate retains child volume proof that baseline loses. Neither excluded behavior is claimed repaired or passing. Retain original A1 migration-replay and A3 real-bind limitations. Review and confirmations: tmp/verify-evidence/verification-report.txt and tmp/verify-evidence/reassessment/. All required candidate module suites passed; generated execution evidence includes assertion fail-before and pass-after."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "codex-auditor-remount-1",
  "verdict": "reject",
  "digest": "sha256:79ab37625e3052e19d7a3c0f66b0eb38f7d144397055a33b741c04c66b777435",
  "note": "Classes match the before-state and candidate changes, and cited evidence records assertion fail-before and pass-after. Reject the required sources for entries 1, 2 and 5: delegated-choice requires the owners written delegation, but these quote orchestrator-P1-amendment or an explicitly labelled orchestrator builder assignment. An implementation assignment also does not state each new expectation. Cite the supplied owner-adopted P1 and D/P scenarios with their specific expectations, or the actual owner delegation and its scope. Optional copies of these citations in entries 3, 4 and 6 should also be corrected or omitted. No Gradle rerun; real bind mounts remain unverified under A3."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-remount-1",
  "verdict": "accept",
  "digest": "sha256:3b4265c2ce7d302187489eee9d8cb045bdae3871d2bb52d850650424cc717d6f",
  "note": "Re-reviewed all six entries under docs/reference/testing/test-intent.md using the exact owner delegation and task-turn locations supplied in the current user turn. Entries 1/5 are the narrow delegated P1 unavailable-classifier defect fix, preserving zero deletions and non-Linux behavior; entry 2 is isolated real-volume fixture/recovery coverage within assigned scenarios; entries 3/6 retain all existing oracles and only add fixture instrumentation; entry 4 retains reverse acquisition cleanup for every owner. Repeated stronger-source conflict search found none. Delegation does not authorize overriding established contracts; strict leases, no deletion on uncertainty, original A1/A2/A3 amendments and explicit R1/R2 exclusions remain. Verified all six before/after item blobs match the prior independent verification and only citation fields changed since HEAD~1. Accept the current digest. Detailed per-entry review: tmp/verify-evidence/citation-review/review.txt. Independent PR 910 audit remains a separate subsequent step."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "codex-auditor-remount-1",
  "verdict": "accept",
  "digest": "sha256:3b4265c2ce7d302187489eee9d8cb045bdae3871d2bb52d850650424cc717d6f",
  "note": "Re-audit of revised sources: the owner quotes and their original context are supplied in the audit request. P1 classification and its Linux-only checks fall within the owners fix-or-defer delegation and agreed P1; real-filesystem fixtures, shared instrumentation, watcher/receipt checks and executor cleanup fall within delegated work decisions and the agreed D/P scenarios with A1-A3. No stronger conflicting source found. Classes, items, choices, test content and execution evidence are unchanged from the prior audit; the missing owner delegation is now supplied. No must-fix findings. Real bind mounts remain unverified under A3; no Gradle rerun."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-remount-1",
  "verdict": "accept",
  "digest": "sha256:7b1c6711ff3b66ed7287c2deb96b827d72eb4959fbd0c4c4919859a90bd40ee4",
  "note": "Accept current digest after reviewing both signature adaptations under docs/reference/testing/test-intent.md. RootPresenceGuardTest expands the removed forgetUnder default with ignored -> true; all seven SyncDirectoryOpsWalkSkipPolicyTest call edits expand the removed execute default with explicit null collection. Every test input, precondition, assertion, parameterization and condition is preserved; the original six entries are unchanged. Regenerated evidence uses the same check IDs, overlay file set and resolved b62fc5921 baseline; producer and current file hashes match, all four cases execute/pass without skips, and every before check fails assertions for the intended watcher/status/classifier defect. Evidence regenerated at 1d8d4efdd and only committed by HEAD 39259d1dd. Original scope exclusions remain; no broader runtime reverification claimed. Detailed review: tmp/verify-evidence/signature-review-v2/review.txt. Separate independent audit for PR 910 follows."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "codex-auditor-remount-1",
  "verdict": "accept",
  "digest": "sha256:7b1c6711ff3b66ed7287c2deb96b827d72eb4959fbd0c4c4919859a90bd40ee4",
  "note": "Reviewed the new entry in documented order: before-state tests and forwarding overloads, agreed scenarios and class table, provisional Adaptation, then candidate diff. The ledger call passes the exact always-true predicate formerly supplied by its overload; all seven sync calls pass the exact null collection formerly supplied by theirs. Inputs, preconditions, assertions, fault injection, tags and conditions are preserved. Remaining production edits only remove these forwarding methods and use existing imports; prior six entries are unchanged. Refreshed evidence retains the same check IDs, overlay set and resolved before revision; all four cases execute/pass without skips and before checks retain intended assertion failures. No must-fix findings. No Gradle rerun; prior A1-A3 limitations remain."
}
```
