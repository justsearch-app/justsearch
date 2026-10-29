---
schema: test-intent.v1
task: t-20261027-4e4d6b
author-role: builder
author-session: codex-rel-rr-20261029
---

# Test-intent entries

The production change reuses CrossEncoderReranker.isQueryWarm; CPU-only session creation is eager
(ModelSessionPolicyResolver.resolveReranker defers only when a GPU arena is configured).
NativeSessionHandle.isQueryWarm explicitly accepts a healthy CPU session without a prior run.
No new lifecycle state or changes to citation, execution-provider or fallback diagnostics.
The two existing wiring sites both call WorkerSearchService.isRerankerActive.

The new checks run through real initial/query-reload service construction and the health response;
the CPU-before/after check also runs a real tiny ONNX model through the rerank interface.
R3 uses the production deferred CPU backup policy: after GPU release/failure it reports inactive
while no CPU session exists (documented current behaviour), then active after a real rerank creates
that session. R3 is narrowed to "GPU released or failed, once the CPU session exists -> true";
it does not claim activity immediately after GPU handoff or failure.
GPU failure is injected in the handle; release uses its public release API. No GPU is used.
The ort-common testFixtures expose composition/model helpers but no reusable GPU failure-injection
fixture; its handle tests also inject private state. Reflection is retained to avoid accessing GPU
hardware or introducing a new fixture in this status repair. R2 checks only the service wiring with
a mocked CrossEncoderReranker; it is not evidence of real GPU readiness or failure recovery.
R2/R4/R5 are preservation checks: the old CUDA predicate already returns the required values
for these cases. They are checked separately on the baseline rather than claimed as repairs.
Regenerated evidence on e9d569f1b0 confirms R1's two cases and narrowed R3's four cases fail
at the active-status assertions and pass on the candidate. In R3, the before-state passes the
inactive-without-CPU checks and real CPU rerank, then fails at reporting the now-ready CPU session
active. The narrowed R3 is therefore a repair check; its pre-search inactivity is preservation.

Evidence is produced by a temporary copy of the repository runner with
`gates/test-intent/evidence/reranker-cpu-active-runner.patch` applied: Gradle uses `js-gradle-slot ./gradlew`,
and the before-state uses a shared clone under the temporary directory instead of a Git worktree.
No product or check semantics are changed by that adapter.

Baseline preservation result: `gates/test-intent/evidence/reranker-cpu-active-preservation-before.xml`
retains the eight JUnit cases and suite counters; only system-out/system-err logs are omitted.
It comes from the shared clone at e9d569f1b0 with only the candidate test file overlaid, running
`js-gradle-slot ./gradlew :modules:worker-services:test` with method filters for
`warmGpuRerankerIsActive`, `unbuiltRerankerIsInactive` and
`retiredOrFailedCpuRerankerIsInactive` (`-PskipWebBuild=true --console=plain`).

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java"
      ],
      "class": "New",
      "behaviour": "R1/R3/R6: initial and query-reload health report a ready CPU reranker active before its first inference, after a real CPU rerank, and after a real fallback search creates the deferred CPU session following GPU release or failure, using the same readiness helper. Before that session exists, inactivity is preserved.",
      "sources": [
        {
          "kind": "delegated-choice",
          "label": "agent choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27",
          "delegationScope": "release-workflow work and the product defects its journeys found",
          "choice": "R1: CPU only, reranker built and CPU session healthy -> true, both before the first search and after one that reranked."
        },
        {
          "kind": "delegated-choice",
          "label": "agent choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27",
          "delegationScope": "release-workflow work and the product defects its journeys found",
          "choice": "R3: GPU released or failed, once the CPU session exists -> true. Deferred CPU sessions remain inactive until a real search creates them (current behaviour)."
        },
        {
          "kind": "delegated-choice",
          "label": "agent choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27",
          "delegationScope": "release-workflow work and the product defects its journeys found",
          "choice": "R6: The initial and reload wiring use the same supplier (one helper; no duplicate lambdas)."
        }
      ],
      "evidence": "gates/test-intent/evidence/reranker-cpu-active-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#cpuRerankerIsActiveBeforeAndAfterRealRerank",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#cpuRerankerBecomesActiveAfterDeferredFallbackSearch"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve R2/R4/R5 through initial and query-reload health: warm GPU is active; unbuilt, retired and failed rerankers are inactive. R2 is a wiring-level check with a mocked reranker, without real GPU readiness or hardware; CPU retirement/failure use a real native handle. The existing fixture constructor retains empty discovery by default; only the new overload supplies discovery for these health checks.",
      "sources": [
        {
          "kind": "delegated-choice",
          "label": "agent choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27",
          "delegationScope": "release-workflow work and the product defects its journeys found",
          "choice": "R2: GPU with warm GPU session -> true."
        },
        {
          "kind": "delegated-choice",
          "label": "agent choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27",
          "delegationScope": "release-workflow work and the product defects its journeys found",
          "choice": "R4: Reranker not built (no model, disabled) -> false."
        },
        {
          "kind": "delegated-choice",
          "label": "agent choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27",
          "delegationScope": "release-workflow work and the product defects its journeys found",
          "choice": "R5: Reranker closed (reload swap, shutdown) or session failed on every device -> false."
        }
      ],
      "evidence": "gates/test-intent/evidence/reranker-cpu-active-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#warmGpuRerankerIsActive",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#unbuiltRerankerIsInactive",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#retiredOrFailedCpuRerankerIsInactive"
      ]
    }
  ]
}
```

## Acceptance records

The acceptance records below are unchanged stale history for the earlier candidate; they do not
accept this repair. Independent verification of this repair is pending.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-rel-rr-verifier-20261029",
  "verdict": "accept",
  "digest": "sha256:ef81ec88851f742a385689323abe91eb0eebf0e95740548c2a165d1c293ba8ef",
  "note": "Accepted: six CPU readiness cases fail before and pass after; eight preservation cases pass both. Independent full worker suites and CPU dev-stack API/UI/reload checks confirm the intended status correction. The runner patch reproduces the recorded evidence producer digest."
}
```
