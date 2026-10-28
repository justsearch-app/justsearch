---
schema: test-intent.v1
task: e74253
author-role: builder
author-session: 7eeab586-99dd-4baf-982b-f4054e53cd21
---

# Test-intent entries

The two test classes are isolated new sources; only the shared volume seam is gated. Execution
evidence: `gates/test-intent/evidence/e74253-native-sync-root.json` records the rows first fixed in
round 1 (S1, S2, S4, S5, S6, S7) failing before on 9e492f703f; `gates/test-intent/evidence/e74253-native-sync-root-round2.json`
records the checks new in round 2 (configured collection root for force=true and force=false in S1/S3 and
S2/S3, retained SYNC_ROOT until its root converged in S4 and S5) failing before on 4a9a22afff;
`gates/test-intent/evidence/e74253-native-sync-root-round3.json` records the checks new in round 3 (a
configured collection root removed at restart in S5; in S6 the receipt's agent/MCP provenance on the
queued offline file and no watcher for the recovered collection root) failing before on 5a77e377f7;
`gates/test-intent/evidence/e74253-native-sync-root-round4.json` records the S1, S2 and S4 checks
changed when S3 was narrowed (the configured collection root's edit now changes its mtime; force=true
finds the new content, force=false keeps what an uninterrupted replay of its receipt keeps) failing
before on 9e492f703f with the volume seam overlaid, and `gates/test-intent/evidence/e74253-native-sync-root-round4-since-4a9a22afff.json`
records the same checks failing before on 4a9a22afff.
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
          "quote": "S5: the SYNC_ROOT's root is unavailable at restart. Other roots serve, the root's files are kept, the predecessor is retained, and boot does not wait out the deadline. When the volume returns, reconciliation completes without a restart.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "assessment": {
        "purpose": "Add one static installer, installVolumeLookupWithFolderAway, so a raw child-process driver can report one folder on another volume until a marker file appears. SyncRootPromotionCrashRecoveryTest uses it for S5. Nothing existing changed.",
        "callersAndStateOwners": "Only SyncRootPromotionCrashRecoveryTest's child JVM (scenario 'unavailable', restart phases) calls it, before installVolumeLookupIfAbsent, which then leaves it in place. RootIdentity.VOLUME_IDENTITY_LOOKUP and VOLUME_IDENTITY_UNAVAILABLE are the state it sets; that child JVM exits by halt, so no JUnit JVM state is touched. beforeEach, afterEach, installVolumeLookupIfAbsent, withVolumeIdentity and volumeIdentityAvailable are unchanged, as is the worker-services copy of this extension.",
        "indirectBehaviours": "No existing method, constant or default changed; tests that use the extension through @ExtendWith or installVolumeLookupIfAbsent see identical lookups. The new method keeps the PRESENT_VOLUME value for every path outside the folder, and for the folder once the supplier says it returned.",
        "evidence": "gates/test-intent/evidence/e74253-native-sync-root.json (before 9e492f703f with this file overlaid) and gates/test-intent/evidence/e74253-native-sync-root-round2.json (before 4a9a22afff): unavailableRootIsDeferredAndReconciledWhenItsVolumeReturns fails before and passes after. After, the child logs 'Committed boot deferred 1 root(s)', publishes within 30 s with the configured collection root's sync settled and only the away root's SYNC_ROOT retained, keeps the predecessor, and once the volume returns settles that receipt and retires the predecessor without a restart. The deferral only happens when this lookup reports a different volume, so the retained receipt and previousKept assertions would fail if the seam did nothing. The full :modules:indexer-worker:test run passes with the change, covering every existing user of the extension.",
        "unresolvedRisks": "The volume string for the away folder is synthesised (':740' in place of ':739'); a real removable volume is not exercised. Independent acceptance is intentionally absent."
      }
    }
  ]
}
```

## Acceptance records
