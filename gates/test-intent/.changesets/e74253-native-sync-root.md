---
schema: test-intent.v1
task: e74253
author-role: builder
author-session: 7eeab586-99dd-4baf-982b-f4054e53cd21
---

# Test-intent entries

The two test classes are isolated new sources; only the shared volume seam is gated. Execution
evidence: `gates/test-intent/evidence/e74253-native-sync-root.json` records the S1-S8 checks (S1, S3,
S5 and S8 against an uninterrupted migration followed by an ordinary restart, S5b and S8 also with a
PROMOTED crash and once the root or permissions return; S2 with its unit supersession check;
S4a/S4b/S4c; S6; S7a with the delete-after-sync S7b order; P6 and P7), the F1 checks (a DELETE a
later sync restores, beside a projection, in the S7 restart and in the mixed unit settlement) and
the all-or-nothing acknowledgement checks (partialAcknowledgementLeavesEverySelectedReceiptForTheRetry
and partialMixedAcknowledgementLeavesEverySelectedReceiptForTheRetry, and their file-receipt-only
variants partialFileAcknowledgementLeavesEverySelectedReceiptForTheRetry and
partialMixedFileAcknowledgementLeavesEverySelectedReceiptForTheRetry) failing before and passing
after, and the S2 policy control allowedSourceUnderALaterRootSyncSettlesWithoutCertification. On
the before state the SYNC_ROOT checks fail at their retry, because origin/main refuses a SYNC_ROOT
before it acknowledges anything; the two variants hold no SYNC_ROOT, so origin/main settles them
and its count-checked removal loses the receipt it did remove (the "stays too" assertion). The
exclusion check ExcludedReceiptUnderRootSyncTest#excludedSourceUnderALaterRootSyncNeedsTheFinalOwner
has its own evidence, `gates/test-intent/evidence/e74253-native-sync-root-policy.json`, before
78042b4117: there the file-only route settled an excluded receipt a later SYNC_ROOT covers without
the final committed-acknowledgement owner (the "missing final owner" assertion); the mixed route
already refused it and passes on both. Since the merge of origin/main (#998, Forget), the before
state is origin/main b744db8744 with the volume seam overlaid: 9e492f703f lacks the
KnowledgeServer policy constructor the merged S5 child uses. S5 also holds a forgotten (FILE
literal) and an excluded (glob) file under the retained SYNC_ROOT's root, with the Engine's
native policy owner composed; the recovered boot must publish and neither index nor requeue them. The S4c and both all-or-nothing checks also fail
on 2d80a943ea with only the two test classes applied (a builder run, not recorded by the script):
the restored file is missing from search after boot, and a refused DELETE removal leaves the DELETE
without the SYNC_ROOT that supersedes it. The F1 control
mixedSnapshotStillRefusesADeleteNoLaterRootSyncCovers is not recorded: it refuses on both sides.
Independent acceptance is intentionally absent.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/NoVolumeIdentityExtension.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "e74253",
          "scenario": "S5",
          "quote": "At restart the SYNC_ROOT's root is (a) on an unavailable volume, (b) deleted from disk, (c) no longer declared in configuration. In each case the app starts within normal startup, other roots serve, and the root's indexed files follow the ordinary-restart rule for that case (control: an uninterrupted migration then the same change and a normal restart).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "assessment": {
        "purpose": "Add one static installer, installVolumeLookupWithFolderAway, so a raw child-process driver can report one folder on another volume until a supplier says it returned. SyncRootPromotionCrashRecoveryTest uses it for S5a. Nothing existing changed.",
        "callersAndStateOwners": "Only SyncRootPromotionCrashRecoveryTest's child JVM (scenario 'roots', restart phase) calls it, before installVolumeLookupIfAbsent, which then leaves it in place. RootIdentity.VOLUME_IDENTITY_LOOKUP and VOLUME_IDENTITY_UNAVAILABLE are the state it sets; that child JVM exits by halt, so no JUnit JVM state is touched. beforeEach, afterEach, installVolumeLookupIfAbsent, withVolumeIdentity and volumeIdentityAvailable are unchanged, as is the worker-services copy of this extension.",
        "indirectBehaviours": "No existing method, constant or default changed; tests that use the extension through @ExtendWith or installVolumeLookupIfAbsent see identical lookups. The new method keeps the PRESENT_VOLUME value for every path outside the folder, and for the folder once the supplier says it returned.",
        "evidence": "gates/test-intent/evidence/e74253-native-sync-root.json (before origin/main b744db8744 with this file overlaid): retainedRootSyncsRestartAsAnUninterruptedMigrationDoes fails before (boot does not publish) and passes after. After, the crash restart and the control's ordinary restart both run with the away folder on another volume, and the away root's files and the file added to it while away are compared token by token with the control (S5a). The full :modules:indexer-worker:test run covers every existing user of the extension.",
        "unresolvedRisks": "The volume string for the away folder is synthesised (':740' in place of ':739'); a real removable volume is not exercised. The supplier is always true here, so the away folder's return is not exercised. Independent acceptance is intentionally absent."
      }
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a18385-61d7-7212-8910-b1e2424d6f51",
  "verdict": "accept",
  "digest": "sha256:65a39a4c048d3438ac65ff0f5bb5b83011e8bd4705139f14954ed3276284d292"
}
```
