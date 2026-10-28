---
schema: test-intent.v1
task: e74253
author-role: builder
author-session: 2b15433d-65b2-4413-a249-3dfdcf55562d
---

# Test-intent entries

The two new test classes are isolated new sources; only the shared volume seam is gated. Execution
evidence for the S checks, fail-before on 9e492f703f, is
`gates/test-intent/evidence/e74253-native-sync-root.json`. Independent acceptance is intentionally absent.

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
        "evidence": "gates/test-intent/evidence/e74253-native-sync-root.json: unavailableRootIsDeferredAndReconciledWhenItsVolumeReturns fails before (9e492f703f with this file overlaid) because boot does not publish within 30 s and B's receipts do not settle, and passes after, with the child logging 'Committed boot deferred 1 root(s) not on the volume first seen at their path' and then retiring the predecessor once the volume returns. The deferral only happens when this lookup reports a different volume, so the assertion on previousKept/previousRetired would fail if the seam did nothing. The full :modules:indexer-worker:test run passes with the change, covering every existing user of the extension.",
        "unresolvedRisks": "The volume string for the away folder is synthesised (':740' in place of ':739'); a real removable volume is not exercised. Independent acceptance is intentionally absent."
      }
    }
  ]
}
```

## Acceptance records
