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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-91c3ab-b2-claude",
  "verdict": "accept",
  "digest": "sha256:ebd6ea80f3f9034e5e51f152ec05d2dfb3dbf4dc6bc7c6eaf87368a6975de619",
  "note": "Candidate efe584d532. 6 repair checks fail on 5dcc5db048 by assertion (named refusal thrown) and pass on candidate. Real Linux Engine: malformed, second malformed, future-schema (live unrelated PID) and invalid-children manifests start up, one 600 quarantine with original bytes, named WARN, unclean narration on log and health stream, no child seeded, unrelated process untouched, planted token only in the quarantine; ledger mode 000 and directory start with PREDECESSOR_LEDGER_UNREADABLE; harness children/handoff/boot-quit/boot-failure pass; ui 2275 and app-api 470 tests pass. Mutations: refusal, ledger refusal, multi-quarantine, unclean flag, token-in-log caught; owner-only restrict not caught (redundant with runtime-dir repair except hard/symbolic links)."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-91c3ab-b3-claude",
  "verdict": "accept",
  "digest": "sha256:18e5f5dc18167b1cd352d3cc283bf6d35662e9af1f7f507070b9c205d7f6d97b",
  "note": "Claude verifier on fe4172eab2: re-accepted after regenerated evidence; RuntimeManifestPredecessorRecoveryTest 7/7 pass, :modules:ui:test 2280 tests 0 failures; real-Engine malformed and hostile-link manifests are quarantined and start-up continues."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "auditor-1025-codex-2",
  "verdict": "accept",
  "digest": "sha256:18e5f5dc18167b1cd352d3cc283bf6d35662e9af1f7f507070b9c205d7f6d97b",
  "note": "Checked predecessor recovery, future-schema handling and the superseded refusal expectations against the quoted recovery choice. Defect pin is justified: matching evidence records assertion fail-before and successful candidate execution, including quarantine privacy, invalid-ownership refusal and continued startup."
}
```
