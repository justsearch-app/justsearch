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
