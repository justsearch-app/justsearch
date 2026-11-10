---
schema: test-intent.v1
task: t-20261103-forgetflake
author-role: builder
author-session: forget-flake-builder-7lnojjvf
---

# Test-intent entries

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "S55 returned-root replay and the shared native migration scenarios supply their existing positive known-volume precondition with the scoped EngineTestHarness.knownVolume fixture before construction through close/reopen. Extracting nativeMigrationWithKnownVolume only scopes that fixture. Host volume discovery is supplied; native held-directory checks, files, queues, extraction, policy guards, inputs, fault injection, expected outcomes, tags and conditions stay unchanged by this fixture adaptation. The observer and opt-in contention changes are classified separately below.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think agents should not ask me for any input if they know better, which in most work related tasks is the case.",
          "delegationLocation": "task t-20261103-forgetflake request",
          "delegationScope": "fixing a recurring CI flake",
          "choice": "Supply the scenarios' existing positive known-volume precondition with the scoped test fixture, restoring the volume lookup afterward and preserving the scenarios' oracle.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think agents should not ask me for any input if they know better, which in most work related tasks is the case.",
          "delegationLocation": "task t-20261103-forgetflake request",
          "delegationScope": "fixing a recurring CI flake",
          "choice": "Retire the completion observer's old condition that the first exact 'Buffered UPSERT lacks its accepted target projection: ' refusal ends observation and immediately reaches the nonterminal assertions. That stopping condition treats a retryable replay refusal as the end of the observation. Replace it with bounded observation of retries, without changing production behavior or the final assertions. This choice follows the pre-existing replay contract in docs/explanation/11-index-schema-migration.md:542-546 ('Replay retains the buffer if decoding or enqueueing fails.') and :571-574 ('Strict read or mutation failure retains the complete snapshot for retry.'); neither passage makes the first refusal terminal.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Ran rg -n -i 'retry|refus|promot|switching|migration|cutover|forget|readmission' docs/decisions --glob '*.md', then the focused search for 'first (refusal|failure)|stop.{0,40}(refusal|projection)|refusal.{0,40}(terminal|stop)|watcherReplayFailure|Buffered UPSERT|target projection|cutover|SWITCHING'. Read ADR-0030:241-276 (agent-dispatch retry policy) and ADR-0048:63-85 (bounded ingestion retries); neither requires native promotion observation to stop on a projection refusal. Confirmed the cited replay contract exists at origin/main and is unchanged in this PR."
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTest.java"
      ],
      "class": "New",
      "behaviour": "The shared native migration completion observer continues after retryable target-projection refusals until terminal promotion (IDLE with the successor active and no building generation) or the existing 45-second deadline. It retains the last non-null refusal for deadline diagnostics. All existing final receipt, replacement admission, current-byte hash, policy and reopen assertions, including the terminal assertion, remain enabled. The separately opted-in contention scenario arms checked lock barriers and reports the observed refusal to the barrier before retry; ordinary scenarios do not install those barriers. The existing regression check forces a real busy eligibility refusal, requires the ordinary observer to see it, then requires retry and promotion through the complete R9 oracle.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think agents should not ask me for any input if they know better, which in most work related tasks is the case.",
          "delegationLocation": "task t-20261103-forgetflake request",
          "delegationScope": "fixing a recurring CI flake",
          "choice": "Observe retryable native replay refusals until promotion or the existing 45-second observation deadline, retaining refusal diagnostics and all final assertions; pin this with opt-in real lock contention and checked barriers. This is a bounded test-observation choice within fixing the recurring CI flake. Its product basis predates the PR: docs/explanation/11-index-schema-migration.md:542-546 says 'Replay retains the buffer if decoding or enqueueing fails.', :571-574 says 'Strict read or mutation failure retains the complete snapshot for retry.', and :656-658 bounds SWITCHING rather than declaring the first refusal terminal. The test's existing 45-second deadline is retained, not substituted for the product's switching budget.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/forget-flake.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTestHostAssumptionsTest.java#hotSaveObserverWaitsForRetryAfterBusyEligibilityRefusal"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTestHostAssumptionsTest.java"
      ],
      "class": "New",
      "behaviour": "Run the entire existing S55 returned-root replay scenario with the outer volume lookup returning null, proving it supplies and restores its own positive volume precondition. Separately force one actual queue tryLock eligibility refusal for R9\u2019s policy-skipped target, hold the next promotion before runtime ownership until the ordinary observer sees the exact projection refusal, then require retry, promotion and every original replacement receipt/current bytes/unchanged forget receipt/reopen assertion. The test-only before patch adds only opt-in instrumentation wiring; neither scoped volume evidence nor the observer repair is present before.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think agents should not ask me for any input if they know better, which in most work related tasks is the case.",
          "delegationLocation": "task t-20261103-forgetflake request",
          "delegationScope": "fixing a recurring CI flake",
          "choice": "Pin host-independent setup with null external volume evidence and pin observer retry behavior with real lock contention and checked barriers, reusing the complete existing scenarios as the oracle.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/forget-flake.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTestHostAssumptionsTest.java#returnedRootReplaySuppliesVolumeEvidenceWhenHostHasNone",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTestHostAssumptionsTest.java#hotSaveObserverWaitsForRetryAfterBusyEligibilityRefusal"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTestHostAssumptionsTest.java"
      ],
      "class": "New",
      "behaviour": "Run the entire existing R9 HOT public Save/readmission scenario with the outer volume lookup returning null, proving the scenario supplies and restores its own positive volume precondition across construction, promotion and real close/reopen. Every original final receipt, replacement admission, current-byte hash, policy and reopen assertion remains enabled. The failing baseline scenario runs in its own JVM to isolate cleanup from the other deliberately failing baseline scenarios.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think agents should not ask me for any input if they know better, which in most work related tasks is the case.",
          "delegationLocation": "task t-20261103-forgetflake request",
          "delegationScope": "fixing a recurring CI flake",
          "choice": "Pin host-independent setup with null external volume evidence and pin observer retry behavior with real lock contention and checked barriers, reusing the complete existing scenarios as the oracle.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/forget-flake-host-r9.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTestHostAssumptionsTest.java#hotSaveReadmissionSuppliesVolumeEvidenceThroughReopenWhenHostHasNone"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1c537-959b-7392-831c-d17cb3dbcd66",
  "verdict": "accept",
  "digest": "sha256:cf671a132fb8a6918123c94bce993fa4d75531c3dc2ffb74d1f92de051ca21bf"
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "01a1c547-8d82-7401-a006-7fd7f8b596d6",
  "verdict": "reject",
  "digest": "sha256:cf671a132fb8a6918123c94bce993fa4d75531c3dc2ffb74d1f92de051ca21bf"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1c56c-dc57-7423-8f7d-9567c6a070b8",
  "verdict": "accept",
  "digest": "sha256:c0b60fc17ed905b5af431c64149392a568dd200a136fc4c1c76ba4b3a324dd93"
}
```
