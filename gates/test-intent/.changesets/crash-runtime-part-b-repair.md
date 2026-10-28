---
schema: test-intent.v1
task: t-20261019-91c3ab
author-role: builder
author-session: 01a184b0-f3e0-7cf0-b996-a90c15813c76
---

# Test-intent entries

The orchestrator generates execution evidence after committing; the builder does not write evidence or acceptance. The skeleton also listed unrelated changes because origin/main advanced beyond the assigned baseline; this changeset covers only the assigned repairs.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestPredecessorRecoveryTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestPublisherTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestRecoveryTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "R4c repair: quarantine malformed, invalid-child and future-schema predecessor manifests as one private original file, warn with the named reason, classify unclean, seed no children from invalid evidence, and continue startup. An unreadable ledger warns and preserves valid manifest children. Replace the superseded refusal expectations; all other recovery expectations remain."
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no crash",
        "reproduction": "The committed candidate throws named manifest/ledger exceptions from publisher construction, preventing every subsequent Engine startup until evidence is manually removed."
      },
      "reproduction": "Construct the publisher with malformed JSON, invalid children, a future schema, or a directory at the children-ledger path. Each assertDoesNotThrow fails on 5dcc5db048.",
      "evidence": "gates/test-intent/evidence/crash-runtime-part-b-repair.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestPredecessorRecoveryTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestPublisherTest.java#futureManifestSchemaIsQuarantinedBeforePublication",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestRecoveryTest.java#invalidEvidenceIsQuarantinedAndKept"
      ]
    }
  ]
}
```

## Acceptance records
