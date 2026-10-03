# Stage E GPU yield diagnosis â€” 2026-10-01

Status: diagnosis complete; Java fix and regression tests written, **not built or run**.
Build and paired standard-model proof remain with the lane-F orchestrator. No Gradle,
npm, stack, installed Engine, commit, push or sub-agent was used in this assignment.
Working base: `59ccd739f73fca7e4f1485566f85677da4669de3`, branch
`codex/lane-f-gpu-yield`.

## Evidence locations and revisions

Paths below are accessible local evidence, not hashes standing in for artifacts.
For `.log.gz:N`, N is the line after gzip decompression; no run files were modified.

| Alias | Location |
|---|---|
| B | `F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/lane-f-e/e2-e3-load/branch/2026-10-01T20-01-10-542Z-1fb8ac79/` |
| M | `F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/lane-f-e/e2-e3-load/main/2026-10-01T16-42-58-581Z-b4fbca64/` |
| BL | `F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/lane-f-e/2026-10-01T20-01-10-542Z-1fb8ac79/agent-idle/logs/` |
| ML | `F:/justsearch-public/.claude/worktrees/lane-f-e-main/tmp/lane-f-e/2026-10-01T16-42-58-581Z-b4fbca64/agent-idle/logs/` |
| MC | `F:/justsearch-public/.claude/worktrees/lane-f-e-main/` (split source at `ac1c93bf3`) |

`B/0-revision.stdout:1` records `a4bdca504aa0b3cd61f866e7960c4ceb35261ff2`;
`M/0-revision.stdout:1` records `ac1c93bf32c2bba3e4a22462295acbc618f850bc`.
The branch capture is earlier than this task's base. A read-only content diff from
the captured branch revision to the working base showed no differences in
`InferenceWiring.java`, `GpuSchedulingGauge.java` or `NativeSessionHandle.java`.
The receipts identify the actual run data directories and PIDs
(`B/start-agent-idle-receipt.json:9`, `M/start-agent-idle-receipt.json:9`).

In the following references, **start** means
`encoder-sessions-encoder-sessions-activate-start-agent-idle-start.json`, and
**end** means `encoder-sessions-encoder-sessions-stop-agent-idle-end.json`.

## Phase 1: causal diagnosis

**Verdict: (a), an inherited deliberate exclusivity policy, exposed by the repaired
async GPU broadcast.** It is not a VRAM arithmetic error or a stuck lease grant.
The pre-fix canonical policy explicitly required single tenancy (working-base
`docs/explanation/05-ai-architecture.md:19`); C1 explicitly documented Online GPU
backfill pause and CPU query embedding
(`docs/design/lane-f-engine-jvm/evidence/C1/gpu-scheduling-connect.md:36`).
The design retains one scheduling policy and gauge, rather than one GPU owner
(`docs/design/lane-f-engine-jvm/design.md:1032`).
The new dated amendment supersedes unconditional exclusion in this assignment.

The exact decision chain is:

1. `InferenceWiring.refreshGpuStatus` publishes `manager.isOnline()` directly to
   the gauge; no free/total VRAM, model reservation or budget calculation occurs
   on that path (`modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/InferenceWiring.java:55`).
   At the working base, the gauge returned that boolean unchanged; the new fit
   callback replaces that read (`modules/core/src/main/java/io/justsearch/core/scheduling/GpuSchedulingGauge.java:89`).
2. Every index/query composition supplies `() -> !signalBus.isMainGpuActive()`
   as its GPU arbiter (`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:4312`).
   `NativeSessionHandle.selectSession` returns CPU **before** lazy CUDA creation
   whenever that arbiter refuses GPU
   (`modules/ort-common/src/main/java/io/justsearch/ort/NativeSessionHandle.java:245`).
   Thus a pending SPLADE session never attempts CUDA while the unconditional
   Online signal remains true; a released embedding session cannot reacquire it.
3. The indexing loop handles the rising signal by releasing embedding GPU while
   retaining CPU query embedding
   (`modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/EmbeddingProviderLifecycle.java:169`, `:219`).
   The sentinel releases reranker on the same rising signal
   (`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:5944`;
   `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerSearchService.java:352`).
   GPU backfill is deferred by the same signal, while CPU-only backfill is permitted
   (`modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/LoopPacingPolicy.java:58`).
4. The reported strings are observations of these decisions, not memory admission
   computations: `OrtCudaStatus.released` supplies â€œyielding VRAM to main inferenceâ€—
   (`modules/ort-common/src/main/java/io/justsearch/ort/OrtCudaStatus.java:126`), while
   `EncoderRuntimeExplainer` supplies the generic â€œGPU not activeâ€— fallback when
   detailed runtime identity is unavailable
   (`modules/app-services/src/main/java/io/justsearch/app/services/observability/EncoderRuntimeExplainer.java:222`).

The branch log proves the sequence: ACTIVE at 22:01:29.298, sentinel release at
22:01:30.019, embedding release at 22:01:30.046
(`BL/engine.2026-10-01.0.log.gz:2300`, `:2316`, `:2319`, `:2320`).
The start capture reports standard chat, 99 GPU layers, NVML, total
12,878,610,432 bytes and free 3,431,297,024 bytes, yet released reranker and CPU
embed/SPLADE (`B/start:28`, `:35`, `:36`, `:37`, `:49`, `:51`, `:71`, `:73`, `:82`, `:84`).
Those free bytes were never consulted by the original yield decision.

**End-capture correction:** the supplied branch end capture actually reports
reranker CUDA, embedding CPU/released and SPLADE CPU/lazy
(`B/end:49`, `:71`, `:73`, `:82`, `:84`). The log shows reranker initialization
finishing after release (`BL/engine.2026-10-01.0.log.gz:2340`). This establishes
overlapping release/initialization, not successful CUDA query execution after yield:
the arbiter still selects CPU even with a resident GPU session
(`NativeSessionHandle.java:245`). The fix avoids that release for a fitting set;
it does not claim to repair the independent native creation/release ordering.

`B/7-_api_debug_state.json:3` is an early boot snapshot (uptime 5,534 ms), preceding
activation at `B/start:3`; it cannot establish post-activation lease or CUDA state.
The effective-config capture shows no device-memory ceiling override
(`B/9-_api_debug_effective-config.json:2036`) and a 2048 MB reranker cap (`:2051`).
`/api/debug/session-policies` serializes the serving policy snapshot, not arbiter
admission or actual execution: controller delegates to the index port
(`modules/ui/src/main/java/io/justsearch/ui/api/SessionPoliciesController.java:90`),
and the owner serializes `snap.models()`
(`modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerIngestService.java:2117`, `:2128`).
No standalone session-policies response was present in these supplied arm captures;
the runtime identity/availability captures and code establish the distinction.

### Why MAIN kept CUDA

MAIN has the same nominal exclusivity rule, **not** a successful sized admission
algorithm. Its normal boot constructs `HeadAssembly` with a null bootstrap
(`MC/modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java:436`), passes that
value into the service phase (`MC/modules/app-services/src/main/java/io/justsearch/app/services/HeadAssembly.java:386`, `:400`),
then invokes the old broadcast wiring
(`MC/modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/ServicePhase.java:186`).
That wiring returns without registering a listener when the bootstrap or bus is
null (`MC/modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/InferenceWiring.java:29`, `:34`).
Later connection does not re-register that listener
(`MC/modules/app-services/src/main/java/io/justsearch/app/services/HeadAssembly.java:1263`).
C1 documents and fixes this exact missing-broadcast path
(`docs/design/lane-f-engine-jvm/evidence/C1/gpu-scheduling-connect.md:22`).
The MAIN startup topology therefore leaves the Worker without the Online yield.

The Worker did initialize reranker, SPLADE and embedding on CUDA
(`ML/worker.log:443`, `:545`, `:1273`); the end capture confirms all three CUDA
alongside standard GPU chat (`M/end:28`, `:37`, `:49`, `:71`, `:82`), with
1,450,782,720 bytes still free (`M/end:36`). This is real coexistence evidence,
but not evidence of a correct MAIN budget policy.

### Why this is not (b) or (c), and what D1 still owns

There is no subtraction, double-counted chat, stale VRAM read or â€œforeign Engine
reservationâ€— in the original unconditional boolean decision
(`InferenceWiring.java:55`, working-base `GpuSchedulingGauge.java:83`).
`RuntimeGpuLease` explicitly mirrors mode, ignores requested size and does not
arbitrate encoder admission
(`modules/app-services/src/main/java/io/justsearch/app/services/runtimestate/RuntimeGpuLease.java:11`, `:38`, `:53`).
`RuntimeReconciler` mirrors mode transitions for status
(`modules/app-services/src/main/java/io/justsearch/app/services/runtimestate/RuntimeReconciler.java:216`).
Changing the holder back to INDEXING would misreport Online chat and would not
change the encoder arbiter.

D1-4/12/14 concerns replacement generations and coherent reconfiguration;
D1-14 intentionally uses candidate CUDA arena caps plus 10%, measured free memory,
and evidence of source release, with a device ceiling for the low-memory harness
(`docs/design/lane-f-engine-jvm/stages/D1.md:212`, `:2437`, `:2445`, `:2512`).
Its production decision remains the candidate path in
`KnowledgeServer.java:3618` and `modules/core/src/main/java/io/justsearch/core/component/DeviceMemoryLine.java:37`.
The D1-16 harness consumes those low-memory lifecycle paths (`stages/D1.md:3142`).
None of these replacement decisions caused the Online encoder yield.

## Phase 2: accepted static design and implementation

The owner reassessed the design after the second substantive correction round. The
accepted ADR-0050 design is static and activation-time. The rejected dynamic
implementation is not part of this worktree's contract: there is no cached free-memory
sampler, hysteresis policy, per-acquisition recheck, runtime pressure reclamation walk,
EngineRoot D1 cache rewiring, or partial non-BFCArena OOM path.

At chat Online activation, and after a chat profile/context or encoder-set change, the
existing gauge evaluator computes:

```text
fits = total VRAM >= model bytes + optional mmproj bytes
                  + KV(context rung, slots, KV type)
                  + 512 MiB chat compute buffer
                  + every GPU-eligible encoder role peak
                  + 1.5 GB desktop margin
```

`InferenceConfig` supplies the model and projector paths and configured context;
`ContextWindowPolicy` and `LlamaServerOps` supply the actual launch rung, slots, and KV
type through `OnlineAiRuntimeIntrospection.ContextWindow`. The KV estimate is derived
from the register's measured 4,352 MiB q8_0 reservation at 262,144 tokens and two slots.
The encoder owner publishes the active CUDA roles to the gauge from its existing
`PolicySnapshot`. The named peaks are 1.5 GB embedding, 2.6 GB BGE-M3, 450 MB SPLADE,
300 MB NER, and 600 MB reranker, with citation excluded because composition keeps it
CPU-only.

Total VRAM is read once at evaluation time through the existing capability snapshot.
Unknown capacity, model files, context, KV type, or encoder roles refuse sharing and
preserve ADR-0004 behavior. The gauge stores the immutable result; session acquisition
reads only that result. The result, inputs, encoder roles, and reason are logged once per
evaluation and projected through `/api/debug/state` as `gpu_co_residency`.

The review findings are answered by scope: resident roles are always charged their
complete documented execution peak; chat KV/context and compute buffer are explicit;
there is no runtime reclaim/drain protocol to overclaim; there is no freshness or
hysteresis guarantee because there is no dynamic sampler; existing BFCArena and runtime
OOM behavior remains unchanged. Another process taking VRAM mid-session remains a known
limitation, matching the split stack, and is a follow-up under ADR-0050.

Existing D1 admission keeps its original `GpuCapabilitiesService.snapshot().effective()`
supplier and its measured free-memory semantics. The co-residency evaluator is a separate
activation seam and does not alter generation replacement admission.

Regression tests now cover the measured 12 GB standard profile, 8 GB refusal, unknown
VRAM refusal, fitting/non-fitting gauge composition, and encoder-profile reevaluation:

- `modules/core/src/test/java/io/justsearch/core/scheduling/GpuCoResidencyDecisionTest.java`
- `modules/core/src/test/java/io/justsearch/core/scheduling/GpuSchedulingGaugeTest.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/EncoderGpuCoResidencyTest.java`

Low-memory D1 behavior remains pinned by `DeviceMemoryLineTest`,
`KnowledgeServerDeviceMemoryLineTest`, `IndexingLoopUnloadTelemetryEmitTest`, and
`LoopPacingPolicyTest`. The root live proof must run the paired 12 GB standard chat/index
load, an 8 GB/non-fitting profile, diagnostics capture, profile/context/encoder-set
re-evaluation, unchanged D1 replacement admission, and llama survival.

The owner forbids builds without the explicit Gradle grant, so no Java compilation,
formatter, test, stack, or installed Engine result is claimed here. The requested build
handoff is recorded in the final response.

The broader reach is deliberately limited: a resource-sharing exception needs a complete
admission contract at the boundary where the resource is claimed. This lane earns that
principle with one pure budget and one existing gauge seam. A general runtime allocator
requires a new cross-process reservation and lifecycle design.
