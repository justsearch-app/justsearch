---
schema: test-intent.v1
task: t-20261018-6108b0
author-role: builder
author-session: 01a181e3-2a58-7732-838e-85a1d29586f9
---

# Test-intent entries

The owner allowed removing duplicated tests after a method-by-method comparison. All nine
VerifierStatusIsolationTest methods retain same-name HTTP counterparts in StatusIsolationHttpTest.
Eight bodies match after helper qualification; the GPU counterpart keeps all assertions and also
checks resampling and freshness through another idle period. The accepted fixture differences
(SERVING versus IDLE and index path) change none of these assertions. No production behavior is
retired.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "well do whatever you think is best",
          "location": "task t-20261018-6108b0 user turn 2026-10-28 (row 2: remove after a method-by-method comparison)"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none; docs/decisions contains no status-isolation or duplicate-test retention decision"
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusAtRestReadTimeHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java"
      ],
      "class": "Adaptation",
      "moved": ["rename"],
      "whatMoved": "Member qualifications change from the removed VerifierStatusIsolationTest subclass to their existing declaring class StatusIsolationFixtureSupport: Fixture, assertWorkerEpoch, assertWorkerTimes and contactLaneAges. Inputs, fixture implementations, preconditions, HTTP observations, assertions, tags and conditions remain unchanged."
    }
  ]
}
```
