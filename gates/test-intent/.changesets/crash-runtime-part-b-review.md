---
schema: test-intent.v1
task: t-20261019-91c3ab
author-role: builder
author-session: 01a18715-7ddb-75e1-965c-b02b66cb4d86
---

# Test-intent entries

These entries cover the settled final review repairs. Existing accepted changesets are unchanged. The orchestrator runs the evidence producer after committing and obtains independent acceptance. The same-PID check builds the existing native helper in a private Linux fixture subprocess, retaining real admission without an installed bundle. The rotation check lives in LedgerSurvivorRotationTest and reuses ChildControlHarness; LedgerReconcilerTest is restored byte-for-byte to dabd685ad2.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/shell_commit_transport_tests.rs"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "R4b review repair: snapshot the predecessor instanceId and startedAt before spawn; reject its credentials even if the child reuses its PID, reject publications older than spawn, and rebind a changed instance for the same child. Keep the existing PID-less and failed-spawn assertions."
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no leak across a requested scope",
        "reproduction": "On dabd685ad2, retained_same_pid_manifest_cannot_bind_a_fresh_shell binds the retained predecessor port 42424 and token instead of leaving credentials unavailable."
      },
      "reproduction": "On dabd685ad2, retained_same_pid_manifest_cannot_bind_a_fresh_shell binds the retained predecessor port 42424 and token instead of leaving credentials unavailable.",
      "evidence": "gates/test-intent/evidence/crash-runtime-part-b-review.json",
      "newChecks": [
        "modules/shell/src-tauri/src/shell_commit_transport_tests.rs#shell_actuator::commit_transport_tests::retained_same_pid_manifest_cannot_bind_a_fresh_shell"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestPredecessorRecoveryTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "R4c/R6 review repair: never traverse a final manifest symlink or chmod a shared inode. Quarantine a link as its target text and link marker, and ordinary evidence as bytes in a fresh owner-only regular file, atomically replace the old quarantine, then unlink the source. A quarantine failure logs a named reason and startup continues. Preserve the existing malformed/future/invalid and credential privacy checks."
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no data loss",
        "reproduction": "On dabd685ad2, symlink and hard-link recovery change the outside inode mode from 0755 to 0600; blocked quarantine throws from construction instead of allowing startup."
      },
      "reproduction": "On dabd685ad2, symlink and hard-link recovery change the outside inode mode from 0755 to 0600; blocked quarantine throws from construction instead of allowing startup.",
      "evidence": "gates/test-intent/evidence/crash-runtime-part-b-review.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestPredecessorRecoveryTest.java#symlinkQuarantineNeverChangesOrReadsReferent",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestPredecessorRecoveryTest.java#hardLinkQuarantineNeverChangesSharedInode",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestPredecessorRecoveryTest.java#quarantineFailureIsNamedAndDoesNotPreventStartup"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/LedgerSurvivorRotationTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "R4d review repair: carry each survivor identity receipt unchanged, including conflicting starts, plus at most the four most recent distinct stop intents per child and the latest survivor receipt. Older attempts remain only in retention-managed archives. Keep the survivor policy and 4096-byte record limit."
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no hang",
        "reproduction": "On dabd685ad2, rotationsCarryOnlyFourRecentStopIntentsAndAllIdentityReceipts carries s-0 through s-5 instead of s-2 through s-5 on its first rotation, so repeated failed cleanup adds unbounded active retry history."
      },
      "reproduction": "On dabd685ad2, rotationsCarryOnlyFourRecentStopIntentsAndAllIdentityReceipts carries s-0 through s-5 instead of s-2 through s-5 on its first rotation, so repeated failed cleanup adds unbounded active retry history.",
      "evidence": "gates/test-intent/evidence/crash-runtime-part-b-review.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/LedgerSurvivorRotationTest.java#rotationsCarryOnlyFourRecentStopIntentsAndAllIdentityReceipts"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)",
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Existing explicit ManifestFields test literals default unspecified fields so the new started_at field remains absent in those fixtures. Existing inputs, assertions, fault injection, tags, timeouts and retry bounds are preserved. Direct host-only fixtures do not set a shell spawn boundary. The conformance fake adds its per-process ISO startedAt to match the real manifest input schema.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "R4b admission must check the publication start time against the spawn boundary; retain existing preservation assertions."
        }
      ]
    }
  ]
}
```

## Acceptance records
