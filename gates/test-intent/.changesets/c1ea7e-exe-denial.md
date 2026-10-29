---
schema: test-intent.v1
task: t-20261024-c1ea7e
author-role: builder
author-session: 01a18835-7ba7-7641-a904-48058a226bef
---

# Test-intent entries

F1 and the persistent-denial case of F2 pin repaired behavior. Other added checks preserve F2; all original checks remain unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/LinuxChildReapingRaceTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F1: retry a transient exe-link AccessDeniedException within the existing deadline and retain the helper actual exit status. F2: a wrapper that never execs still times out even if its exe link remains unreadable; exit-125 detail, missing-link retries, identity capture and birth registration remain intact. F3: preserve existing child reaping, OwnedHelper and BoundChildProcess checks.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no crash",
        "reproduction": "Inject AccessDeniedException at the exe-link read during a bound child launch. The old spawn aborts launch instead of allowing the child to complete; transientExeAccessDenialKeepsPollingAndPreservesExitStatus asserts successful launch."
      },
      "reproduction": "The seam-only before patch preserves the old exception behavior. Inject three access denials before the real procfs read, or deny every read for a wrapper that never execs. Both checks fail by assertion before the repair.",
      "evidence": "gates/test-intent/evidence/c1ea7e-exec-denial.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/LinuxChildReapingRaceTest.java#transientExeAccessDenialKeepsPollingAndPreservesExitStatus",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/LinuxChildReapingRaceTest.java#unreadableWrapperStillFailsAtTheExecDeadline"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/LinuxChildReapingRaceTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve the existing exec deadline for a visible unchanged wrapper, keep polling a transient missing exe link, and retain exit-125 refusal detail. Existing Linux child reaping tests are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F1: retry a transient exe-link AccessDeniedException within the existing deadline and retain the helper actual exit status. F2: a wrapper that never execs still times out even if its exe link remains unreadable; exit-125 detail, missing-link retries, identity capture and birth registration remain intact. F3: preserve existing child reaping, OwnedHelper and BoundChildProcess checks.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/c1ea7e-exec-preservation.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/LinuxChildReapingRaceTest.java#unchangedWrapperStillFailsAtTheExecDeadline",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/LinuxChildReapingRaceTest.java#missingExeLinkKeepsPolling",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/LinuxChildReapingRaceTest.java#wrapperRefusalStillIncludesExit125AndItsDetail"
      ]
    }
  ]
}
```

## Acceptance records
