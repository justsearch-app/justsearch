---
schema: test-intent.v1
task: t-20261111-recoveryrefusal
author-role: builder
author-session: recoveryrefusal-builder-1
---

# Test-intent entries

Agent choices under the quoted delegation, against the settled scenarios of the task. Execution evidence separates the assertion fail-before repair (R1) from candidate-only checks. Independent acceptance is pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootIndexRecoveryTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/PolledObservationRecoveryCloseTest.java"
      ],
      "class": "New",
      "behaviour": "Repaired, asserted on 9868861448 and passing on the candidate: heldStatusReservationDoesNotRefuseRecoveryCloseAndOpenFailureIsTheRecordedCause. With a status reservation held across EngineRoot.closeForRecovery inside recoverIndex, the close proceeds, the attempt reaches the open, and the recorded FAILED evidence is the open's lock failure (/index/write.lock), not 'Live Engine work retains the index owner'; one counted attempt; the incumbent's closeForRecovery runs. heldPolledReadDoesNotRefuseTheRecoveryClose (GET /api/runtime/live, /api/diagnostics/traces, /api/inference/transitions, /api/ai/packs/status, /api/conversations/encryption): the real handler behind the real ApiSecurityFilters and the Engine's admission is held in its JSON response serialization while EngineRoot.closeForRecovery runs against a mocked physical index owner; the close does not throw, the owner's closeForRecovery runs, and the response completes with 200. On 9868861448 the close throws 'Live Engine work retains the index owner'.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with investigating Windows start and restart failures. i dont want you to spend your tokens too much on implementation, but save that for opus models.\n4. The third defect: run its challenger passes and settle cases and a design before anything is built.",
          "delegationLocation": "task t-20261111-recoveryrefusal request",
          "delegationScope": "The third defect: an Engine index recovery attempt refused by a concurrent status read; settle cases and a design, then build.",
          "choice": "Settled scenarios, quoted verbatim: R1 (deliver, re-scoped): A status request, or another request polled routinely while the Engine runs (GET /api/runtime/live and the other requests the shell, dev-runner or UI poll on a timer, each shown not to reach the index), in progress at the moment a recovery attempt closes the index does not make that close refuse: the attempt goes on to open the index, and the recorded failure, if any, is the real cause (in the exhaustion case, the index lock). Checked by holding such a response across the close; fails before the change for this reason. Other requests stay counted as work. Settled design: closeIndex's live-work check stops counting plain /api/status reservations and counts everything else as today, from one snapshot of the admission record. Polled reads given the status reservation, each traced to published in-process state only: GET /api/runtime/live, /api/diagnostics/traces, /api/inference/transitions, /api/ai/packs/status, /api/conversations/encryption.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/recovery-status-reservation-repair.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootIndexRecoveryTest.java#heldStatusReservationDoesNotRefuseRecoveryCloseAndOpenFailureIsTheRecordedCause",
        "modules/ui/src/test/java/io/justsearch/ui/api/PolledObservationRecoveryCloseTest.java#heldPolledReadDoesNotRefuseTheRecoveryClose"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootIndexRecoveryTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineAdmissionControllerTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/PolledObservationRecoveryCloseTest.java"
      ],
      "class": "New",
      "behaviour": "New checks of preserved and new-accounting behaviour, run on the candidate only; not repaired behaviour. workThatUsesTheIndexStillRefusesRecoveryCloseAndIsCounted (admitted, retained, internal-attach as the client's status-sampler call slot, admitted alongside status reservations): the recovery close still refuses with evidence 'Physical recovery failed: Live Engine work retains the index owner', one counted attempt, no open, incumbent not closed. shutdownDrainWaitsForAHeldStatusReservationThatTheIndexCloseDoesNotCount: an admitted observation is counted by the index close, a plain status reservation is not, the count survives freeze and closing publication, and the shutdown drain stays incomplete until the reservation is released. concurrentStatusChurnNeverMasksOrUnderflowsTheIndexRetainingCount: bounded-loop stress test; concurrent status reservations and ordinary admissions never make the index close's count drop below a held work slot, exceed real work, or go negative. The admission-controller checks call indexRetainingWorkCount, which does not exist on 9868861448, so they have no before run; the R3 cases also pass on 9868861448 (builder's earlier transplant run, not part of this evidence). heldOrdinaryRequestStillRefusesTheRecoveryClose: an ordinary GET observation outside the polled set, held the same way, still makes the recovery close refuse with 'Live Engine work retains the index owner' and leaves the owner unclosed (\"Other requests stay counted as work\").",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with investigating Windows start and restart failures. i dont want you to spend your tokens too much on implementation, but save that for opus models.\n4. The third defect: run its challenger passes and settle cases and a design before anything is built.",
          "delegationLocation": "task t-20261111-recoveryrefusal request",
          "delegationScope": "The third defect: an Engine index recovery attempt refused by a concurrent status read; settle cases and a design, then build.",
          "choice": "Settled scenarios, quoted verbatim: R1 (deliver, re-scoped): A status request, or another request polled routinely while the Engine runs (GET /api/runtime/live and the other requests the shell, dev-runner or UI poll on a timer, each shown not to reach the index), in progress at the moment a recovery attempt closes the index does not make that close refuse: the attempt goes on to open the index, and the recorded failure, if any, is the real cause (in the exhaustion case, the index lock). Checked by holding such a response across the close; fails before the change for this reason. Other requests stay counted as work. | R3 (must not break): Work that really uses the index (a client lease, a serving view, a retained Engine work handle, an issued call past its deadline) still keeps the recovery from closing the dependencies it uses; such a refusal is reported truthfully and counted as today. | P4 (must not break): Status admission keeps its guarantees: observation limits, release through serialization, failure and disconnect, independence from search capacity; ordinary and fresh status responses, serializer failure included. | P5 (must not break): Process shutdown still drains all admitted work, status reservations included: admission stays closed and drain completes only after their release. | P6 (must not break): Recovery accounting stays truthful: count 0 at start, one increment per admitted attempt, a concurrent manual recovery refused as busy, one counted escalation after the budget is used. Settled design: closeIndex's live-work check stops counting plain /api/status reservations and counts everything else as today, from one snapshot of the admission record. Two separate reads or separate atomics are not acceptable: they can mask real work.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/recovery-status-reservation-new.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootIndexRecoveryTest.java#workThatUsesTheIndexStillRefusesRecoveryCloseAndIsCounted",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineAdmissionControllerTest.java#shutdownDrainWaitsForAHeldStatusReservationThatTheIndexCloseDoesNotCount",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineAdmissionControllerTest.java#concurrentStatusChurnNeverMasksOrUnderflowsTheIndexRetainingCount",
        "modules/ui/src/test/java/io/justsearch/ui/api/PolledObservationRecoveryCloseTest.java#heldOrdinaryRequestStillRefusesTheRecoveryClose"
      ],
      "repaired": false
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "kpy2yxwx-verifier",
  "verdict": "accept",
  "digest": "sha256:650a592f0dda7ba077eea3901202bce79f7b814621249d5e908755de4cec8fbf",
  "note": "Expectations match settled R1, R3 and P4-P6; evidence digests and assertion fail-before are valid. Test-intent acceptance does not certify unverified scenarios."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "01a1c901-4465-7e10-9305-0f5779c1f093",
  "verdict": "accept",
  "digest": "sha256:650a592f0dda7ba077eea3901202bce79f7b814621249d5e908755de4cec8fbf",
  "note": "Re-scoped R1 and delegated choices support the expectations; evidence and test-source digests match; repair runs fail by assertion for the intended refusal. Audit covers test intent, not Windows or physical index recovery."
}
```
