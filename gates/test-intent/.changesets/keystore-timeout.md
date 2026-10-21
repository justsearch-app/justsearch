---
schema: test-intent.v1
task: t-20261021-95d6d2
author-role: builder
author-session: 01a15f52-feff-7ff2-bef5-0e51407e54cc
---

# Keystore process-kill timing on Windows

The delegation below is the owner's authorization. The concrete timeout and diagnostic design are agent choices, not owner-words.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreDurabilityTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261021-95d6d2 request",
          "delegationScope": "Remaining JustSearch repair work, including issue #870: distinguish slow Windows child JVMs from a hang before changing the execution limit, preserve both actions at all four publication steps, and produce timing evidence.",
          "choice": "Give only the eight-child process-kill test a 120-second JUnit timeout, retain the 30-second child publication bound and 10-second termination bound, and report the action, step, start-to-pause duration or incomplete elapsed duration and failed-child output. Forward the parent temporary-directory setting to child JVMs.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "Keep the real-filesystem process-kill durability check executable on Windows while retaining a bounded failure for a child that never reaches its publication pause. The machine-wide 30-second default is unchanged.",
        "callersAndStateOwners": "JUnit runs this cross-platform test; it owns eight Process objects and an isolated TempDir keystore. DataKeyManager and EncryptionKeystore retain ownership of persisted keys and replicas. No production code, stored-data format, credentials, test tags or selection changes.",
        "indirectBehaviours": "The full two-action/four-step matrix, forced filesystem writes, injected publication pauses, owned-process termination, restart, old/new credential unlock, sealed-text assertion and credential reset all remain. Only the aggregate execution envelope and diagnostics change; the per-child deadline now also includes ProcessBuilder.start. Logging survives interruption of the polling loop, and completed-child timings remain in the JUnit system-out report. Children inherit java.io.tmpdir explicitly.",
        "evidence": "Repository evidence: gates/test-intent/evidence/keystore-timeout/investigation.md explains cumulative Windows slowness and the conservative 120-second budget; gates/test-intent/evidence/keystore-timeout/windows-excerpts.txt retains verbatim method results, failure payloads, progress logs, source artifact paths and XML digests from five Windows runs. gates/test-intent/evidence/keystore-timeout/local-checks.txt records the combined 20/20 candidate Gradle run and zero PMD violations. gates/test-intent/evidence/keystore-timeout/durability-probes.json and durability-{before-slow,after-slow,after-hung-child,after-whole-timeout}.txt record baseline failure at 30 s, candidate delayed-child success in 46.302 s, retained child-bound failure and actual 120-second JUnit timeout. gates/test-intent/evidence/keystore-timeout/durability-clean-suite.json records the additional normal 12/12 standalone run. Probe producer source and the actual JUnit launcher are gates/test-intent/evidence/keystore-timeout/probes.py and TimeoutProbe.java. No cited evidence is confined to tmp/.",
        "unresolvedRisks": "The candidate has not executed on Windows; hosted confirmation remains outstanding. Existing Windows reports do not expose exact individual child start-to-pause times, so the new diagnostics will provide those on the next Windows execution. Passing near-limit runs and a timeout in parent-side reset support cumulative slowness; a particular interrupted polling sample alone cannot exclude a stalled child. The retained 30-second child bound prevents the aggregate timeout from hiding that failure."
      }
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreReplicaRepairTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261021-95d6d2 request",
          "delegationScope": "Remaining JustSearch repair work, including issue #870 and the sibling replica-repair timeout in PR #873 run 39632460020: distinguish slow Windows execution from a hang, preserve the complete proof, and inspect neighbouring child-JVM tests.",
          "choice": "Give only the four-child replica-repair kill test a 90-second JUnit timeout, retaining the 30-second child publication bound and 10-second termination bound; report per-child action, step, start-to-pause or incomplete elapsed duration and failed-child output. Forward the parent temporary-directory setting. Leave other surveyed tests unchanged because Windows evidence does not show a near-limit execution or timeout.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "Keep both actions at the two interrupted-publication steps executable on Windows while failing a stalled child within 30 s. The global 30-second default remains unchanged.",
        "callersAndStateOwners": "JUnit owns four Process objects and an isolated TempDir. The subprocess uses the existing KeystoreDurabilityTest.main publication-pause fixture; DataKeyManager and EncryptionKeystore own persisted keys/replicas. No production, format, selection, tag or ownership changes.",
        "indirectBehaviours": "The password/recovery x steps 2/3 matrix is unchanged, including old recovery generation on each iteration, real forced writes and filesystem moves, injected pause, forced termination of the owned Process, restart, current primary/replica equality, correct password unlock, DEK equality, rejection of revoked credentials through BOTH copies, and resetting the password for the next iteration. No assertion is weakened or removed. The child deadline also includes process startup; timings and failed-child output remain in JUnit system-out on interruption.",
        "evidence": "Repository evidence: gates/test-intent/evidence/keystore-timeout/windows-excerpts.txt shows run 39632460020 failing at the aggregate 30 s while the PARENT regenerates recovery credentials (not waiting on a child); the same run retries successfully in 22.561 s. Other Windows passes are 18.558-22.389 s, including main at 19.685 s. Two completed password publication repairs in the failed execution are 13.564 s apart. Four such conservative child-plus-parent intervals cost about 55 s; the 90 s method budget adds about 35 s headroom. gates/test-intent/evidence/keystore-timeout/local-checks.txt records all 20 tests passing and PMD zero violations. gates/test-intent/evidence/keystore-timeout/replica-probes.json and replica-{before-slow,after-slow,after-hung-child,after-whole-timeout}.txt provide before/after and bound checks; producer source is gates/test-intent/evidence/keystore-timeout/replica-probes.py and ReplicaTimeoutProbe.java. gates/test-intent/evidence/keystore-timeout/child-jvm-survey.txt records the neighbouring-test search and reused-report limitations.",
        "unresolvedRisks": "The candidate has not executed on Windows. Historical XML lacks isolated child start-to-pause timing; interval measurements include parent credential/verification/reset work. The failed replica run is proven to have timed out in parent-side preparation, and all near-limit samples retain the child publication bound. New diagnostics make any future stalled child distinguishable. Untagged child-JVM tests absent from the Windows-native selection have no Windows evidence and are not called passes or changed."
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
  "session": "01a15f65-f2fa-71d0-aa82-20f16f656cc0",
  "verdict": "accept",
  "digest": "sha256:4c7d4ea8dbd3feef285d603b509e3942d26de6fc02c03d15094e34c88f26e446",
  "note": "Accept both Incidental delegated-choice entries: independent before/after slowdown, early-exit and first/last-child hang checks preserve the oracle and 30-second child bound, with native Windows confirmation still outstanding."
}
```
