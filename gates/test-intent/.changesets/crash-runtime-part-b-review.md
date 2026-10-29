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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-91c3ab-b3-claude",
  "verdict": "accept",
  "digest": "sha256:f0affabdf247428ac128d3cbe39ab1a7732189239e24e19544e5125f7d1d36c7",
  "note": "Claude verifier on fe4172eab2: the 4 review checks fail on dabd685ad2 by assertion (port 42424 bound; referent 0755->0600; UncheckedIOException at construction; s-0..s-5 carried) and pass on candidate; mutations of snapshot, not-before, rebind, no-follow, continue-on-failure and intent bound are each caught (identity-only removal is not); real-Engine probes show no referent change, 0600 regular quarantine, no token in logs, start-up continues."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-91c3ab-b4-claude",
  "verdict": "accept",
  "digest": "sha256:0dcd3c8e66d788757b4f85bcb98d7181ba9ac4bd65c7ae667374c2c99a9def7e",
  "note": "Re-accepted at 753deb42cb: only the evidence moved (fixture changes in shell_commit_transport_tests.rs; revision, timing and re-captured output). retained_same_pid_manifest_cannot_bind_a_fresh_shell, spawn_keeps_predecessor_evidence and losing_shell_cleanup_refuses_a_live_owners_helpers pass on the candidate; retained-same-PID passes on 11bcf0561c too; full shell crate 269 pass."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-91c3ab-b5-claude",
  "verdict": "accept",
  "digest": "sha256:b295b2c3c364b5c6e35d723d422497f1c279d090f8e1714c3c716ff7526f4b76",
  "note": "Re-accepted at 5f6bb672c9 after the engine_host test repair and evidence regeneration changed flagged items. retained_same_pid_manifest_cannot_bind_a_fresh_shell passes and catches a mutant that drops the session comparison (binds port 42424). Full shell lib suite 269/269 and tauri conformance 62/62 on the candidate; no change to this changeset's evidence since its prior acceptance."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "auditor-1025-codex-2",
  "verdict": "accept",
  "digest": "sha256:b295b2c3c364b5c6e35d723d422497f1c279d090f8e1714c3c716ff7526f4b76",
  "note": "Checked same-PID predecessor rejection, symlink and hard-link quarantine, quarantine failure handling, bounded retry retention and fixture adaptations. Defect pin and Adaptation classifications are supported; evidence matches current test content and exposes the asserted defects before the repairs."
}
```
