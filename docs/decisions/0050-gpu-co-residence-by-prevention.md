---
title: "ADR-0050: GPU co-residence by prevention, measured profiles and explicit degradation"
type: decision
status: stable
description: "The chat model and the ONNX encoders share one consumer GPU by not overlapping bulk GPU work with chat generation or a foreground game, by fixed per-card profiles built from measured and capped footprints, and by unloading the chat model under external pressure; a search that cannot get semantic results in time returns keyword results with a reason instead of timing out."
date: 2026-10-05
amends:
  - "0004-single-tenant-gpu-policy.md"
  - "0047-context-window-is-a-derived-resource.md"
  - "0048-extraction-isolation-and-indexing-pacing.md"
probes: "none - ADR-specific probes are not registered; the Enforcement section lists candidates, not executed checks"
last_reviewed: 2026-10-20
---

# ADR-0050: GPU co-residence by prevention, measured profiles and explicit degradation

## Status

Accepted by the owner on 2026-10-05 (decisions D1-D10 of the co-residence design).
- It replaces the 2026-10-02 interim policy recorded in ADR-0004's amendment: "match shipped behaviour; chat going Online never yields the encoders". It is the "budget-aware policy" follow-up that amendment names.
- Implemented in #759 (`1d272de4b`); ADR-0004's interim amendment no longer describes the shipped policy. ADR-specific probes are not registered in `governance/adr-probes.v1.json`.

## Context

- **The failure.** In the lane F E2 scripted-agent window, hybrid searches that overlapped chat generation hit native embedding and SPLADE encodes of up to about 194 s. Free VRAM was 172 MiB, and no arena OOM or execution-provider fallback was logged. The searches expired at the 15 s budget as HTTP 504. E3's primary and SPLADE rates failed from the same cause (`docs/design/lane-f-engine-jvm/evidence/E/decision.md`).
- **The platform makes reactive designs unreliable on consumer Windows:**
  - Over-allocation does not fail. Since driver 536.40 the NVIDIA driver spills to system memory, many times slower, by default.
  - There is no GPU priority between two processes: no MPS on Windows, and CUDA stream priority is per process.
  - Windowless background processes lose memory arbitration to the foreground application.
  - A running ONNX Runtime call cannot be cancelled. Closing its session waits for the call, so releasing encoder memory under contention takes as long as the stuck call.
- **The earlier designs failed in two ways.** Five rounds predicted memory from documented constants that measurement contradicted. They also relied on releasing memory quickly, which the platform does not allow.
- **What the merged Engine does differently.** It runs far more bulk GPU work than the split stack did: late chunking, NER and embedding backfill now progress while chat is loaded, where the split stack starved them. That overlap is the leading explanation for the tail. It is to be confirmed by measurement M1 before the profiles ship.

## Decision

1. **Search answers on time.**
   - Query-side semantic work has a sub-budget inside the request deadline. It covers the gate wait, session readiness, tokenisation and the native run.
   - When the budget is missed, the search returns keyword results with `hybrid_fallback` and the reason `semantic_budget_exceeded`. The reason carries a cause (contended, loading or stuck) and is reported per leg.
   - While a native run is stuck, a circuit breaker skips that leg at once.
   - Query sessions are prewarmed at activation, so graph optimisation never lands on a user query.
   - RAG runs its query encodes before generation and falls back with `retrieval_mode_reason`. VECTOR-only requests wait within their deadline and never fall back silently.
   - HTTP 504 is reserved for real failures.
2. **Foreground before background, with aging, on one device-level order.**
   - All encoders on a device share one foreground/background order.
   - Bulk batches are bounded in duration (about 1 s) as well as count, so a foreground wait is bounded.
   - Aging guarantees background progress. This keeps ADR-0048's rule that foreground contention is answered with a duty floor, never a starving pause.
3. **Bulk GPU work yields to chat generation and to a foreground game.**
   - Bulk work means index-time encodes, late chunking, NER, BGE-M3 and VDU.
   - While chat generates, bulk GPU batches run only at ADR-0048's duty floor: one batch of about 1 s per duty window, through the same pacing governor. They are slowed, never paused, so a continuous agent loop cannot starve indexing. Query-understanding and expansion calls don't count as generation.
   - During a game, or with low adapter memory, bulk GPU batches do not start. This amends ADR-0048 for external conditions only: they are not our own continuous foreground loop, and they end on their own.
   - Both states are visible ("Indexing slowed: chat is answering", "Indexing paused: …").
4. **Fixed profiles from measured, capped footprints.** The profile is chosen at activation from the device's total memory, and recomputed when models or launch flags change. Arena caps enforce its numbers.

   | Card | Profile |
   |---|---|
   | 16 GB and up | Everything on the GPU. |
   | 12 GB | 9B chat model; vision projector on CPU except during VDU runs; pinned context rung; capped query encoders. |
   | 8 GB | 4B chat model. |
   | No usable NVIDIA GPU | The CPU tier. |

   - Explicit user settings win over the profile. Where they can't be met, the app says so; it never changes them silently.
   - Chat and encoders are pinned to the same device.
5. **The chat model unloads under external pressure.**
   - **Trigger:** a detected game, or sustained low adapter free memory while our own footprint is unchanged, with chat idle.
   - **Human chat** reloads it and shows "loading".
   - **Agent callers** receive a typed, retryable refusal (`CHAT_UNAVAILABLE` with a reason) and never force a load. Agent callers are the chat-run API, the OpenAI-compatible proxy and the agent API; MCP tools only retrieve.
   - **Reload limits:** a minimum dwell of 120 s after a pressure unload. A token bucket is added only if measurement shows reload storms.
   - **Who counts as human:** only a chat run started from the app's own window or a paired device that explicitly asks to load (`allowLoad`). Every other caller gets agent behaviour. This is a cooperative policy, not an authority check: a local caller can claim it.
   - **Pressure is external use:** adapter memory in use minus our own processes' use. An unknown reading triggers nothing.
   - **Idle unload:** none in this version.
   - **Encoder sessions:** never closed or recreated by these switches.
6. **One decider owns the chat server** (configuration × profile × VDU × activation × residency).
   - It replaces the runtime reconciler as the single writer of the runtime state's engine axis (tempdoc 737). It lives in app-services under that guarded role; the runtime status is a projection of its one snapshot.
   - Every exchange with the chat server holds a generation lease, including the OpenAI-compatible proxy. The lease count is the "chat is generating" signal.
   - It runs the activation self-test, stopping the main server first, so a second model is never loaded beside a running one.
   - The legacy path that stops chat so indexing gets the GPU (`Mode.INDEXING`) retires.
7. **Observation is diagnostic first.**
   - Per-process and per-adapter GPU memory is sampled from the Windows GPU performance counters.
   - It is published as status, including a spill-suspected flag.
   - It becomes a control input only after a real-game measurement (M7) shows it is timely.
8. **Degradation is explicit.** Every reduced capability is a named state with a reason, in one status surface read by the UI, MCP, dev tools and evaluation scripts. The reduced capabilities are: semantic fallback, expansion skipped, indexing paused, chat unloaded, chat loading and chat refused.

## Consequences

- **E2-style timeouts become keyword-with-reason answers.** The tail itself is removed if M1 confirms that the overlap causes it.
- **Background indexing is slower while chat generates and stops during games.** The owner accepted this (D4, D10). E3 counts progress under load, not parity with the split stack's rates.
- **Chat may be unloaded while a game runs.** The first chat afterwards waits for a reload (median about 4 s, p90 about 9 s on the 12 GB development card).
- **The constant-based estimates retire.** These are `DeviceMemoryLine`'s footprint constants, the fixed arena cap set (17.4 GB on a 12 GB card) and the dormant `mainGpuActive` consumers. Replacement admission keeps its rules over measured numbers.
- **ADR-0047 interaction:**
  - The profile chooses the top rung that ADR-0047's ladder starts from.
  - The ladder, `-fit off` and window-fraction budgets are unchanged.
  - Free VRAM still never chooses a rung.
- **ADR-0004's amendment** is superseded by this decision.

## Alternatives considered

- **A dynamic multi-level residency manager driven by observed spill.** Deferred, not rejected. Its actuation is slow exactly when needed, it oscillates under agent demand, and its signal is unproven for the game case. It returns if M1 shows the tail persists with bulk work paused.
- **Predictive budget from documented constants.** Rejected after five review rounds: unmeasured peaks.
- **All encoders on CPU while chat is loaded (ADR-0004's historical policy).** Rejected: hybrid p95 rose from 0.26 s to 9.8 s.
- **Encoders in a separate inference process now.** Rejected for this purpose. It creates no GPU priority, which the platform lacks between processes. The inference lane's own triggers stand.
- **Short idle unload (Ollama-style).** Rejected: reload storms are the most common complaint about that pattern.
- **Driver-profile "prefer no sysmem fallback".** Rejected for now. It needs admin rights and an undocumented setting ID, and it turns spill into OOM crashes on every path.

## Reassessment triggers

- M1 shows the tail persists with bulk GPU work paused.
- M7 shows the OS evicts our memory under a game faster than the chat unload responds.
- A supported card class exceeds its profile's measured footprint in the field.
- The encoders move out of process.

## Enforcement (unregistered probe candidates)

- No `isUserActive`-style starving pause. ADR-0048's `adr-0048-no-user-active-pause` stays.
- Indexing keeps a minimum rate under a continuous agent-search loop with chat generating (an effect-at-the-far-end probe, per ADR-0048's 2026-09-07 amendment).
- `semantic_budget_exceeded` is present in the reason-code contract and its UI mirror.
- There is a single llama-server start site, inside the chat-server decider.
- Arena caps derive from the profile, with no constant cap table.

## Evidence

- `docs/design/lane-f-engine-jvm/evidence/E/decision.md` (E2, E3).
- ADR-0004's 2026-10-02 amendment (12 GB measurements).
- The co-residence design and its research, spike and reviews: the task record of this decision. Its contents are copied into the implementing changes' evidence.
