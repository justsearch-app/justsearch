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
          "delegationQuote": "Also include the small P1 fix: a root whose filesystem is away shows as unavailable through the same classifier as other unavailable roots, in the status API and the UI, with a check that fails before.",
          "delegationLocation": "task delete-residuals turn orchestrator-P1-amendment",
          "delegationScope": "Normalize the missing-root refusal on Linux; keep Windows behavior unchanged.",
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
          "delegationQuote": "Build the smallest change that meets the Deliver and Preserve scenarios.",
          "delegationLocation": "task delete-residuals request (orchestrator builder assignment)",
          "delegationScope": "Implement the watched-drive return scenarios v2 and the orchestrator amendments; preserve strict operation leases and migration/cutover behavior.",
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
          "delegationQuote": "Build the smallest change that meets the Deliver and Preserve scenarios.",
          "delegationLocation": "task delete-residuals request (orchestrator builder assignment)",
          "delegationScope": "Implement the watched-drive return scenarios v2 and the orchestrator amendments; preserve strict operation leases and migration/cutover behavior.",
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
          "delegationQuote": "Build the smallest change that meets the Deliver and Preserve scenarios.",
          "delegationLocation": "task delete-residuals request (orchestrator builder assignment)",
          "delegationScope": "Implement the watched-drive return scenarios v2 and the orchestrator amendments; preserve strict operation leases and migration/cutover behavior.",
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
          "delegationQuote": "Also include the small P1 fix: a root whose filesystem is away shows as unavailable through the same classifier as other unavailable roots, in the status API and the UI, with a check that fails before.",
          "delegationLocation": "task delete-residuals turn orchestrator-P1-amendment",
          "delegationScope": "Normalize the missing-root refusal on Linux; keep Windows behavior unchanged.",
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
          "delegationQuote": "Build the smallest change that meets the Deliver and Preserve scenarios.",
          "delegationLocation": "task delete-residuals request (orchestrator builder assignment)",
          "delegationScope": "Implement the watched-drive return scenarios v2 and the orchestrator amendments; preserve strict operation leases and migration/cutover behavior.",
          "choice": "Use existing unavailable-root classification, isolated real-filesystem fixtures, shared production fixture instrumentation, and checks of watcher and receipt recovery. The timing amendment is an orchestrator decision, not an owner quote.",
          "label": "agent choice"
        }
      ]
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
