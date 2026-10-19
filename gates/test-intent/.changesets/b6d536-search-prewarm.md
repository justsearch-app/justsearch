---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, item 5a third slice: Spec D rev 2 step 7. The query sessions are prewarmed
before the app reports the models ready. A search leg with a keyword fallback never creates a
session on the query thread: it runs on a warm GPU session or on the existing CPU session, and it
reports LOADING when neither exists.

ADR-0050 records this decision, but it is added on this branch (not at the gate base), so it is not
cited as a source. The entries cite the owner-adopted scenarios, and the owner's delegation for the
agent's specific choices.

Evidence files (all written by run-evidence.mjs):

- `b6d536-search-prewarm-repaired.json`: repaired behaviour, with an assertion fail-before on
  88cbf13c3 (the steps 5-6 commit this slice builds on). The shared test fixture
  `QueryPrewarmFixture.java` is overlaid into the before-worktree with the check file.
- `b6d536-search-prewarm-preservation.json`: preservation checks that pass before (88cbf13c3) and
  after. That run exits 1 by design, because none of them shows a repair.
- `b6d536-search-prewarm-new.json`: the executor registration check, whose new expectation uses
  API added in this change, so it has no before-state.
- `b6d536-search-prewarm-readiness.json`: the bounded, one-way embedding readiness. Its behaviour
  is new in this slice (88cbf13c3 never gated readiness on warmth), and the checks read
  `query_encoders_cold`, which exists only with the change, so they have no before-state. Each
  was shown to fail by assertion on a local, uncommitted edit that removes the mechanism it pins:
  with the readiness bound removed, "a prewarm that keeps failing held the embedding readiness
  past its bound ==> expected: <true> but was: <false>"; with the one-way latch removed, "the
  embedding readiness flipped back to not ready while its session was re-created ==> expected:
  <0> but was: <243>"; with the Head's cold-encoder branch removed, "expected: <DEGRADED> but
  was: <READY>".

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQueryPrewarmTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/QueryPrewarmFixture.java",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleQueryLegCreationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "S21 through the production deferred model initialization (KnowledgeServer.initDeferredModels with the production embedding service and health service; only the native session owner is a test double whose session takes 1.5 s to create, which never creates a session for a leg that may not create one, and which is warm once a foreground run that was allowed to create it completed on it). (a) While models initialize, health never reports embeddingReady with a cold query session; when models report ready the query session is warm, and the first hybrid search after that runs its dense leg without a LOADING refusal, without a keyword fallback and without creating a session. (b) After a GPU release the prewarm warms the session again without a search, health is ready again, and the next search does not fall back or create a session. (c) A prewarm that stalls past its model-release bound (1.5 s) releases the models at that bound, and a prewarm whose session creation fails releases them at once; in both the embedding is not ready yet when the models are released, because its query session is cold and its readiness bound has not passed. Before the change nothing prewarmed: health reported the embedding ready with a cold session. Through the production NativeSessionHandle (only the native sessions are mocks): (d) a foreground query leg that may not create sessions, before the GPU session exists or after it was released, runs on the existing CPU session and does not attempt GPU creation; with no usable session it throws SessionAcquireDeadlineExceededException with phase LOADING and creates neither session. Before the change the leg created the GPU session on the query thread.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S21",
          "quote": "Search models are warm before the app reports ready; the first search after activation does not fall back.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S1",
          "quote": "During the scripted agent on a quiet 12 GB machine, no search returns a 504 or times out; every search answers within its deadline.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "A query session is warm when a GPU session exists, is not being released and a foreground run that was allowed to create it has completed on it; a CPU session needs no prior run. A query leg with a keyword fallback never creates or retries a session: it uses a warm GPU session, else the existing CPU session, else reports LOADING. One prewarm runner runs one foreground encode through each configured query encoder (embedding, SPLADE unless the IDF query path is used, or BGE-M3) before the models are reported ready, again after any session of those encoders is created or released, and checks every second. Model initialization waits for its first pass at most the query warm-up budget (30 s). Until a query encoder is first warm (or its readiness bound passes), health reports embeddingReady false, so the EMBEDDING readiness component carries its existing reason encoders.health.embedding_not_ready; no new reason code.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-prewarm-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQueryPrewarmTest.java#queryEncodersAreWarmBeforeReadyAndTheFirstSearchDoesNotFallBack",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQueryPrewarmTest.java#aReleasedQuerySessionIsWarmedAgainWithoutASearch",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQueryPrewarmTest.java#aStalledPrewarmReleasesModelsAtItsBoundBeforeTheEmbeddingIsReady",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQueryPrewarmTest.java#aFailedPrewarmReleasesModelsBeforeTheEmbeddingIsReady",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleQueryLegCreationTest.java#aQueryLegBeforeTheGpuSessionExistsRunsOnTheCpuSessionWithoutCreatingIt",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleQueryLegCreationTest.java#aQueryLegAfterAGpuReleaseDoesNotRecreateTheGpuSession",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleQueryLegCreationTest.java#aQueryLegWithNoUsableSessionReportsLoadingAndCreatesNothing"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQueryPrewarmTest.java",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleQueryLegCreationTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation, passing on 88cbf13c3 and on the change. Through the production deferred model initialization: a CPU query session that is already usable is not encoded during initialization and not waited on; health reports the embedding ready, and a hybrid search runs its dense leg without a fallback. With semantic search disabled, model initialization completes without waiting on any prewarm (the bound is set to 60 s, and initialization ends in under 20 s). Through the production NativeSessionHandle: a CPU-only handle, and a handle whose GPU session creation failed, give a query leg that may not create sessions the CPU session.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P2",
          "quote": "Without a usable NVIDIA GPU, keyword and CPU semantic search keep working.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "Readiness waits only on query encoders that are not warm: a CPU-only boot, whose CPU session exists, and a boot with semantic search disabled, which has no query encoder, do not wait on prewarm.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-prewarm-preservation.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQueryPrewarmTest.java#aWarmCpuQuerySessionIsNotWaitedOn",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQueryPrewarmTest.java#semanticSearchDisabledDoesNotWaitOnPrewarm",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleQueryLegCreationTest.java#aCpuOnlyQueryLegRunsOnTheCpuSession",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleQueryLegCreationTest.java#aQueryLegAfterAFailedGpuRunsOnTheCpuSession"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/WorkerExecutorRegistrationsTest.java"
      ],
      "class": "New",
      "behaviour": "The worker's stable executor registrations gain index.query-prewarm: background, scheduled, one thread, the background queue limit, one live instance. The first check now also asserts that spec, and its expected close order (reverse acquisition order) starts with index.query-prewarm, which is acquired last. Every earlier spec assertion, the closed-state checks and both partial-rollback checks are unchanged; rollback still closes only the registrations acquired before the refused one.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S21",
          "quote": "Search models are warm before the app reports ready; the first search after activation does not fall back.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "The prewarm runs on its own Engine-registered executor, index.query-prewarm (background, scheduled, one thread, one instance), acquired last and closed first, rather than on a raw thread or on the deferred-model-init executor, which ends with initialization.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-prewarm-new.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/WorkerExecutorRegistrationsTest.java#projectsPolicyLimitsIntoStableWorkerOwnerShapes"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQueryReadinessTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperTest.java"
      ],
      "class": "New",
      "behaviour": "The embedding readiness that the query prewarm gates is bounded and one-way. Through the production deferred model initialization, health service and search service (only the native session owner is a test double): (a) when GPU session creation always fails, health reports embeddingReady true no earlier than the readiness bound (stubbed to 4 s) and within 10 s after it, with query_encoders_cold true; the prewarm tried to create the session, and a hybrid search falls back with cause loading. (b) After readiness was reached, a GPU release and the prewarm's re-creation never return embeddingReady to false in health samples taken every 5 ms; query_encoders_cold is true during re-creation and false once the new session is warm. Through the Head's readiness envelope: (c) embeddingReady true with query_encoders_cold true makes the embedding component DEGRADED with the existing reason encoders.reloading; the aiFeatures composite is not NOT_READY and names encoders.reloading, and the indexServing component and the retrieval composite are unchanged from the warm case. (d) The Head's worker status mapping carries query_encoders_cold apart from embeddingReady, false when the Worker does not set it. The two pre-existing test classes only gain one check each; no existing check changed.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S21",
          "quote": "Search models are warm before the app reports ready; the first search after activation does not fall back.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P2",
          "quote": "Without a usable NVIDIA GPU, keyword and CPU semantic search keep working.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "The readiness bound is KnowledgeServer.QUERY_READINESS_BOUND_MS = 45 s from the start of the prewarm: the measured activation times (design b6d536 measure M3: 8.8-16.2 s per re-activation, 29.6 s for the slowest first activation, queued behind startup) plus half again as margin. Past it, a prewarm that keeps failing no longer holds the embedding readiness; the prewarm keeps retrying, and searches fall back with cause loading or run on CPU as before. Readiness is one-way per process (QueryEncoderReadiness, shared by every service set that replaces the first): once embeddingReady was reported, a GPU release or session re-creation changes only the component state, carried as a new additive Worker health field query_encoders_cold. The Head maps it to an embedding component DEGRADED with the existing reason encoders.reloading (worded 'Semantic search models are reloading'), rather than a new reason code, so the readiness vocabulary and its wording rows are unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-prewarm-readiness.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQueryReadinessTest.java#aPrewarmThatAlwaysFailsReachesReadyWithinTheBoundWithTheReason",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQueryReadinessTest.java#aGpuReleaseAfterReadyChangesOnlyTheComponentStateNotTheReadiness",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java#coldQueryEncodersAfterReadinessDegradeOnlyTheEmbeddingComponent",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperTest.java#toUiStatusMapCarriesColdQueryEncodersApartFromEmbeddingReadiness"
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
  "session": "68d3797d-dcdf-4a36-aa86-f4641178057c",
  "verdict": "accept",
  "digest": "sha256:cedb74374fe8455950bad7a3cc9ebd9cb687a2a373e6d458a58fef525ea7298b",
  "note": "verify-d57: fail-before re-run on 1392fdfbc (assertion, reason under test), mutation run on 370d82b33 (cause plumbing, readiness one-way/bound, cold->DEGRADED, daemon), behaviour claims match test code"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS1-20261015",
  "verdict": "accept",
  "digest": "sha256:bd883f617adbed76ec6d78017cbf98bb6609cc631567b731f29f77cf644c80df",
  "note": "Re-reviewed query-leg acquisition, readiness and the concrete candidate-only reasons; candidate and declared before checks reproduce the recorded repair and preservation outcomes."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS5b-20261016",
  "verdict": "accept",
  "digest": "sha256:d91fe6c3028f25a05eff79aba964ae22f2034ab5efb9f998c001a04c3fc5d760",
  "note": "Re-bound to origin/main 298354254 after reviewing the readiness descriptor/reflection adaptations and regenerated evidence; their inputs and expected readiness outcomes are preserved and all 16 candidate selectors passed. The seven repaired and four preservation before checks retain matching current test content and independently corroborated proof."
}
```
