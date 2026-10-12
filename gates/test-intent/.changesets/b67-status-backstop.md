---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b67-status-backstop
---

Canonical status owns publication completeness; independent acceptance remains pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus5Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusPublicationCompletenessTest.java",
        "modules/ui-web/src/api/statusBoundary.test.ts",
        "modules/ui-web/src/api/statusBoundaryPoll.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The full canonical status structure publishes missed owner changes and stale/recovered transitions, excluding clocks and ages. Failed reads carry publication metadata; cached polls with unreflected changes refresh once; restart and missing metadata cannot restore older current state. Refused fresh reads do not create a loop. Copied verifier assertions are retained.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "metadata on any response counts, successful or not; a body is never shown as current unless it is known to be at least as new as what is displayed",
          "location": "task t-20261005-b6d536 request (b67 builder brief, task item 5 / S20)"
        }
      ],
      "evidence": "gates/test-intent/evidence/b67-status-backstop-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus5Test.java#realEncryptionOwnerPublishesItsCanonicalStateChange",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus5Test.java#samplerFailureAndRecoveryPublishCanonicalFreshnessChanges",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusPublicationCompletenessTest.java#everyStatusBearingFieldAndFutureProducerHasAPublicationPath",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusPublicationCompletenessTest.java#producerWithoutOwnerPublicationStillSignalsCanonicalChange",
        "modules/ui-web/src/api/statusBoundary.test.ts",
        "modules/ui-web/src/api/statusBoundaryPoll.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus3Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus4Test.java"
      ],
      "class": "New",
      "behaviour": "Retain the independent verifier real-HTTP isolation, admission, owner, projection and provenance checks as preservation evidence. Their assertions remain unchanged; the fixture temporary directory uses the test runtime directory.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Preservation: every check added by ea0c311cb, b53's StatusIsolationHttpTest and admission checks, the verifier's 65 own preservation cases where reusable",
          "location": "task t-20261005-b6d536 request (b67 builder brief, Checks)"
        }
      ],
      "evidence": "gates/test-intent/evidence/b67-status-backstop-preservation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus3Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus4Test.java"
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
  "session": "verify-status6-independent",
  "verdict": "reject",
  "digest": "sha256:f8d5d758193c3a5fb4ee3d532dfcb98b8c4a18aa2f59315713b496bb180f7c81",
  "note": "Builder checks fail on 93479e944 by assertion and pass on the candidate, but they do not exercise measurement-only producer changes, missing metadata or refused-read storms. Independent candidate 6377d0030: FAIL. (1) StatusPublicationBackstop counts measurement noise: live idle head advanced the counter 51 times in 10 min (GPU utilisation 0<->1, VRAM, recent* arrays, telemetry window state); the idle mounted UI made 37 fresh reads and a failed 422 action triggered a fresh read and snapshot replacement in 3 of 4 runs. (2) Metadata loss: UI kept 'unlocked' for 12 polls while canonical was locked (live, header-stripping proxy; module case fails, passes on 93479e944). (3) After a real head restart a 1000-request UI burst produced 392 refused fresh reads in 3.6 s, contradicting 'refused fresh reads do not create a loop'. Passing: lock/unlock, raw settings, second tab, approval, agent tool, chat, GPU/index, replay/reattach, restart display, full suites, architecture, regen-all, API contract, lane F. Report: F:/agent-sandbox/design-b6d536/codex/verify-status6/report.md"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status7-independent",
  "verdict": "reject",
  "digest": "sha256:f8d5d758193c3a5fb4ee3d532dfcb98b8c4a18aa2f59315713b496bb180f7c81",
  "note": "Candidate 6f177f241 replaces this record's behaviour (backstop over the full structure) with a state-field classification and changes the refused-read handling, so its evidence no longer describes the running mechanism. Independent verify-status7 on 6f177f241: FAIL S20 / OWN7-BACKOFF. On the built UI against the running candidate head, after 40 s of refused fresh reads (6 refused, back-off works) and a successful ordinary poll, a lock by another client and, separately, the mounted UI's own successful lock action are not shown at the next render: 0 fresh reads, display unlocked while canonical is locked, caught up only 8-15 s later by ordinary polling. statusInvalidation.ts keeps backoffUntil (up to 30 s, Retry-After up to 300 s) until a successful signal-triggered fresh read; successful ordinary polls do not end it and due() gates every action and receipt signal on it. Unit form fails on 6f177f241 and passes on 823c82104."
}
```
