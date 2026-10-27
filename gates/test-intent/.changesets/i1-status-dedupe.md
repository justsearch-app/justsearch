---
schema: test-intent.v1
task: t-20261018-6108b0
author-role: builder
author-session: 01a17caf-83af-7ad2-aef6-cf23215ede0d
---

# Status test inheritance deduplication

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationFixtureSupport.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "split"
      ],
      "whatMoved": "Split the existing Fixture, per-instance JsonMapper and HttpClient, view(), get(), contactLaneAges(), assertWorkerEpoch(), assertWorkerTimes(), fieldGet(), statusContext() and assertCapacityRefusal() into package-private abstract StatusIsolationFixtureSupport, with no test annotations. Their source is unchanged. VerifierStatusIsolationTest retains the same nine annotated test methods and its private GPU-publication helper. HTTP test references continue to resolve inherited members. Inputs, preconditions, fault injection, observations, expectations and conditions of the retained executions are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i approve",
          "location": "task t-20261018-6108b0 user turn 2026-10-27"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus3Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus4Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus5Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusPublicationCompletenessTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i approve",
          "location": "task t-20261018-6108b0 user turn 2026-10-27"
        }
      ],
      "assessment": {
        "purpose": "Remove exactly 45 duplicate inherited executions: the nine VerifierStatusIsolationTest methods repeated in each of these five classes. Each guarded behaviour continues to run once in VerifierStatusIsolationTest, and all subclass-declared tests remain byte-identical. This is not Obsolete: no guarded product behaviour is retired.",
        "callersAndStateOwners": "JUnit discovers inherited @Test methods in concrete subclasses. Each subclass previously inherited the same per-instance JsonMapper, HttpClient, Fixture and helpers; those now come directly from the abstract support class. Fixture owns its ephemeral Javalin HTTP server, admission controller and StatusFixtureWork; StatusComponentFixture and StatusLifecycleHandler own component state and publications. Each test creates and closes its own fixture. The independent source check in the approved assignment found no subclass changes an inherited fixture, wiring, timing or state and no subclass test depends on inherited tests running first.",
        "indirectBehaviours": "Changing the superclass intentionally changes JUnit discovery: only the five copies of each inherited method disappear. It does not change subclass test bodies, inherited helper implementations, fixture lifetime, HTTP routing, producer timing or assertions. StatusIsolationHttpTest, StatusAtRestReadTimeHttpTest and StatusNonBlockingHttpTest keep their original inherited-member references. The support class has no @Test methods and its name does not match test discovery.",
        "evidence": "Before (main be1f315b50, :modules:ui:test selecting io.justsearch.ui.api.VerifierStatus* and io.justsearch.ui.api.Status*): 237 executions across 26 report files, 0 failures, 0 skips; each of the five classes ran its own tests plus the nine inherited methods. After (this change, :modules:ui:test selecting the six status classes plus StatusIsolationHttpTest, StatusAtRestReadTimeHttpTest and StatusNonBlockingHttpTest, then spotlessCheck and pmdTest, all passing): VerifierStatusIsolationTest 9, VerifierStatus2Test 14, VerifierStatus3Test 1, VerifierStatus4Test 5, VerifierStatus5Test 3, StatusPublicationCompletenessTest 3, StatusIsolationHttpTest 16, StatusAtRestReadTimeHttpTest 1, StatusNonBlockingHttpTest 46; 0 failures, 0 skips. The five subclass counts equal their own declared tests, so exactly 9 x 5 = 45 inherited executions are gone. This evidence can fail: a subclass that still inherited the nine, or lost one of its own tests, would show a different count.",
        "unresolvedRisks": "Losing the sixfold repetition per run gives slightly less chance to hit a rare race in the nine shared tests; the owner accepted that when approving. An independent before/after discovery comparison of every execution is left to the verifier."
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
  "session": "01a17ccd-538b-7741-af1c-a8a78d4d7c50",
  "verdict": "accept",
  "digest": "sha256:72974669b74217cd36ef620649a9828376b3ccde3352c999f05bb3df1e4d7739"
}
```
