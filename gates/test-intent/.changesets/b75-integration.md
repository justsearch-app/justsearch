---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b75-integration-builder-20261012
---

Integration of census-repair, e7b, prompt-cache, status-timeout and vdu into b6d536/integration
(merge commits c088e38b4, 9ee870d9c, d063adc56, bb28c015a, 8bb751157). The combination brings two
status-admission designs together. status-timeout (b50) refuses an overlapping status read at the
work pool's bound. The base's b58 fix gives status reads their own bounded observation pool, so a
search-filled work pool never refuses them. The orchestrator decided, under the owner's
delegation, that the observation pool wins. The overlap check keeps its intent, but now against
the observation pool's own bounds: an overlapping read beyond capacity is refused with the typed
capacity reason and Retry-After, never queued or admitted without bound, and an in-flight read
holds its slot until its response is sent. The three fixture fixes follow production signatures
and wiring that the combination changed, and keep each oracle.

Before-state and fail-before (recorded from the builder's own detached worktree of 8bb751157,
not with run-evidence `--before`, because that links ui-web/node_modules into a worktree it then
removes with `--force`):

- Pristine 8bb751157: `:modules:ui:compileTestJava` fails at StatusLifecycleQueryOnCpuTest.java:191
  (buildReadinessEnvelope takes four arguments). KnowledgeServerRecoveryCloseTest fails 5 of 24
  with NullPointerException at KnowledgeServer `svc.searchService().embeddingAvailability()`.
- 8bb751157 plus only the StatusLifecycleQueryOnCpuTest compile overlay: the original overlap
  check fails in StatusIsolationHttpTest, VerifierStatusIsolationTest and
  StatusPublicationCompletenessTest ("expected: <429> but was: <200>"), and
  EngineAdmissionTransportTest#statusHasItsOwnBoundedAdmissionWhileSearchAndChatAreInFlight fails
  ("expected: <429> but was: <200>": the hook never held a status read). StatusLifecycleQueryOnCpuTest
  passes 3 of 3.
- Mutations on the candidate, each reverted afterwards: removing the observation pool's
  aggregate and per-client checks in EngineAdmissionController.reserveAdmission makes all three
  adapted checks fail ("expected: <429> but was: <200>"), and so do the four further classes that
  inherit the method (VerifierStatus2Test to VerifierStatus5Test). Closing the status reservation
  before the response instead of after it (ApiSecurityFilters) makes the three directly named
  classes fail the same way.

Logs: F:/agent-sandbox/design-b6d536/codex/b75-integration/logs/ fb-pristine.log,
fb-ui-original.log, adapted-mutation-nobound.log, adapted-mutation-nobound-2to5.log,
adapted-mutation-nohold.log, adapted-combined.log, run-evidence2.log.

Earlier changesets whose evidence or acceptance the combination invalidates (they need their
evidence re-run on the combined tree and a new acceptance record):
- Evidence invalid and acceptance stale: b48-llama-gpu-off, b51-gpuoff-consistency,
  b56-nogpu-encoders, b67-status-backstop (its VerifierStatusIsolationTest check, edited here),
  b68-prompt-cache, b6d536-bulk-gate-e4, b6d536-bulk-gate-e6, b6d536-integrate.
- Evidence invalid: b6d536-b64-status-counter, b6d536-batch1-repair (StatusLifecycleQueryOnCpuTest,
  edited here), b6d536-no-adoption.
- Acceptance stale only: b50-status-timeout, b52-nogpu-profile, b6d536-batch-1, b6d536-bulk-gate,
  b70-prompt-cache-followups.
- Checks this changeset edits whose earlier evidence was already invalid on a parent:
  b53-status-isolation (StatusIsolationHttpTest), b58-mcp-admission and b6d536-fg-pool
  (EngineAdmissionTransportTest). b56-nogpu-encoders above also holds the
  KnowledgeServerRecoveryCloseTest checks edited here.
- The 22 items the gate reports uncovered only on the combination are uncovered because their
  entries in the changesets above are invalid; none lacks an entry.

These lists come from comparing the test-intent gate on the combined working tree with the gate
on the base and on each of the five branch tips.

Records phase B (batch 3) re-ran `b75-integration.json` on b599d4d7e because its check files changed after the first run. The entry has no fail-before. Every check passes, including StatusLifecycleQueryOnCpuTest (3 cases) and KnowledgeServerRecoveryCloseTest (24 cases).

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java"
      ],
      "class": "New",
      "behaviour": "overlappingStatusPreservesAdmissionCapacityRefusal (also inherited by StatusPublicationCompletenessTest and VerifierStatus2Test to VerifierStatus5Test): with this client's work bound full (4 of 4), a real GET /api/status is still admitted and is held in flight. With the held read and three more observation reservations at the per-client bound of 4, an overlapping read gets 429, Retry-After 1, errorCode ADMISSION_CONTEXT_LIMIT and errorClass TRANSIENT. With other clients filling the observation aggregate of 16 while this client has room, it gets 429, Retry-After 1, ADMISSION_ENGINE_LIMIT and TRANSIENT. The held read keeps its slot until its response is sent: once released it answers 200, the live admission count returns to the work items alone, and a later read answers 200. The previous expectation, 429 because three work items and the held read filled the shared pool, is replaced: status no longer shares the work pool. Every other assertion of both classes is unchanged. Build cleanup removes redundant qualification in StatusIsolationHttpTest; removes trailing blank lines in VerifierStatusIsolationTest, retaining original inputs, resource lifetimes and every existing assertion.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 request",
          "quote": "Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06T21:10Z",
          "delegationScope": "integrating the b6d536 branches, where status-timeout's overlap check (an overlapping status read refused at the shared work pool's bound) contradicts b58's separate bounded observation pool for status reads",
          "choice": "The separate bounded observation pool wins. The overlap check's intent, that overlapping status reads beyond capacity are refused with the typed capacity reason and Retry-After rather than queued or admitted without bound, is asserted against the observation pool's own per-client and aggregate bounds, together with status-timeout's lock-free hold of status admission until the response is sent.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-build-green-candidate-b3b4c08bac.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java#overlappingStatusPreservesAdmissionCapacityRefusal",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java#overlappingStatusPreservesAdmissionCapacityRefusal",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusPublicationCompletenessTest.java#overlappingStatusPreservesAdmissionCapacityRefusal",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java#overlappingStatusPreservesAdmissionCapacityRefusal",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus3Test.java#overlappingStatusPreservesAdmissionCapacityRefusal",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus4Test.java#overlappingStatusPreservesAdmissionCapacityRefusal",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus5Test.java#overlappingStatusPreservesAdmissionCapacityRefusal"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/EngineAdmissionTransportTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "The test's beforeMatched hook holds a ?block=true request once it has been admitted. It recognised admission only through RequestEngineWork. Since status-timeout, an admitted GET /api/status carries its lock-free status reservation (context attribute status-admission-reservation) instead, so the hook now also recognises that attribute. Inputs, fixtures (2,8) and (8,2), the saturating search and chat work, the two held status reads, the wait for activeWorkCount() >= 4, and the expected 200 and typed refusals (ADMISSION_CONTEXT_LIMIT or ADMISSION_ENGINE_LIMIT, Retry-After) are unchanged."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleQueryOnCpuTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "status-timeout added a fourth parameter, boolean cached, to StatusLifecycleHandler.buildReadinessEnvelope. The helper passes false, a freshly built envelope, as StatusLifecycleHandlerTest's direct calls do. Inputs, projection, worker contact and every expectation are unchanged."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRecoveryCloseTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "On a model-initialisation failure, KnowledgeServer now publishes FAILED embedding availability through the serving search service (svc.searchService().embeddingAvailability(), base commit a37c6ef33). e7b's fixture gave the mocked DefaultWorkerAppServices no search service, so the failure path threw NullPointerException. The fixture now returns a mocked WorkerSearchService reporting STARTING, the state a fresh server has. Recovery inputs, injected failures, the captured desired plan and every expectation are unchanged. Build cleanup removes redundant qualification in KnowledgeServerRecoveryCloseTest, retaining original inputs, resource lifetimes and every existing assertion."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS2-20261015",
  "verdict": "accept",
  "digest": "sha256:9e0b0f0f34c9948188aced94881adda8e86a49c2df5ccfefcde699f3e379e2f9",
  "note": "Reviewed independent observation capacity/refusal and slot retention against the delegated integration choice. Own candidate checks passed all seven listed overlap methods; the explicit manual-before explanation is retained as a nonblocking proof limit."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS5c-20261016",
  "verdict": "accept",
  "digest": "sha256:e4b8c42068d111867e6f8c8bc5cd86fd9263d5d1da0e37b1c0064fc9720a116b",
  "note": "Re-bound to origin/main 298354254 after reviewing qualification and whitespace cleanup without oracle changes. The seven listed observation-admission checks, edited recovery fixture and both full status-isolation classes pass; the entry retains its explicit historical manual-before explanation and makes no automated fail-before claim."
}
```
