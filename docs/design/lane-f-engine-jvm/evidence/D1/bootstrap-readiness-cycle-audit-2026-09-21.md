# D1-2 bootstrap/readiness cycle audit


## Implementation decision (2026-09-21)

The root and independent audit rejected adding epoch counters. Reuse `initLock`
to serialize each start attempt, physical health observation, initialization and
ordered close. Replace the old generation counter with `physicalHealthy` and
`healthyInitializationComplete`, private to that lock. Neither is an exported
readiness state. False physical health and close reset completion; a successful
initialization sets it only after required setup calls return. Setup failures
remain retryable. A partial failure can repeat already-issued catch-up work, so
the guarantee is one successful initialization per healthy period, not exactly-once
side effects. Help ingestion keeps its existing best-effort marker semantics and
is retried after a later physical recovery. API/readiness changes never reset it.

Both first and recovered healthy periods use the same catch-up sequence. The old
monitor already retried help on recovery; the generation counter only duplicated
that distinction. Locking start/health/close avoids obsolete-client work without
another counter or shared authority. Shutdown may wait for the current synchronous
bootstrap action; root walks are queued asynchronously and existing client close
owns their drain. Temporary mutable-capability READY publication remains until the
registry sampler migration lands; it no longer triggers initialization or clears
the fatal-index latch. The observations below describe the pre-change source.

Audit target: source revision `93e206213c8e3f47fae0e3b0f6489cd3edd9a677` plus the
uncommitted D1 projection work present on 2026-09-21. This is a read-only design audit; no
runtime migration, build or test command was run. The governing contracts are
[`readiness-plan-2026-09-21.md`](readiness-plan-2026-09-21.md) and D1-2 in
[`../../stages/D1.md`](../../stages/D1.md).

## Finding

The sampled index readiness result cannot also be a prerequisite for obtaining its sample.
`StatusLifecycleHandler.observeWorker` currently refuses the Worker RPC while
`workerCapability.available()` is false (`StatusLifecycleHandler.java:470-483`). After D1-2,
the registry-backed capability projects index `STARTING` as non-ready until that RPC proves the
four-input conjunction. The first sample therefore cannot promote the index. Use the existing
structural binding predicate, `KnowledgeServerBootstrap.hasClient()`
(`KnowledgeServerBootstrap.java:460-468`), to decide whether an RPC can be attempted. A missing
bootstrap/client yields the existing failed fallback; the sampled result, API state, index-health
bit and age decide registry readiness after the RPC.

Two auxiliary paths have the same wrong dependency even though they are not readiness
authorities:

| Current decision | Why it is unsafe after D1-2 | Exact replacement |
|---|---|---|
| `HeadAssembly.connectKnowledgeServer` waits for `localCap.available()` before resolving the memoized agent-tool registration and otherwise installs a capability listener (`HeadAssembly.java:1514-1536`). | The tools are part of connection-time composition. Waiting for the sampler result can leave them absent indefinitely when the sampler is itself waiting for a bound status client. | After assigning the client and rebuilding the service graph (`HeadAssembly.java:1473-1513`), resolve the existing `Memoized<Boolean>` from this connection path when `ks.hasClient()` and the live client/indexing/document services exist. Retain the memo's one-successful-resolution semantics; remove the readiness listener as an initialization trigger. |
| `AgentToolHandlers.registerLateBound` repeats `!workerCapability.available()` as a prerequisite (`AgentToolHandlers.java:147-174`). | Removing only the outer gate still caches a premature `false`; the inner gate therefore completes the same cycle. | Replace the capability argument/gate with structural inputs already owned by the call: non-null `KnowledgeClient`, non-null `KnowledgeServerBootstrap` with `hasClient()`, non-null data directory, and the freshly rebuilt services. Handler invocation remains protected by the normal request/operation capability gates. |
| `KnowledgeServerHealthMonitor.tick` detects `non-READY -> READY` around `checkHealth()` and only then runs deferred initialization (`KnowledgeServerHealthMonitor.java:290-307`). | D1-2 removes `checkHealth()` as a registry-READY writer. The transition disappears, so a client that becomes healthy after the boot retry budget never runs its auxiliary initialization. Conversely, sampler readiness can change because API state or sample age changed and is not a physical recovery edge. | Drive initialization from the direct health return and a bootstrap-owned physical health edge, described below. Never use registry/capability READY as the side-effect trigger. |

## Structural boot and recovery sequence

`KnowledgeServerBootstrap.awaitHealthyAndComplete` already polls the actual client
(`KnowledgeServerBootstrap.java:341-359`). Keep the direct result as the startup fact, but remove
its capability/registry READY write. A healthy result invokes the same initialization owner; an
unhealthy result leaves a bound client for the monitor (`KnowledgeServerBootstrap.java:360-370`).
`KnowledgeServerBootstrap.checkHealth` likewise already returns the actual RPC result
(`KnowledgeServerBootstrap.java:712-735`); retain that return and move READY/UNAVAILABLE
publication to the status sampler.

The initialization owner needs a small, private edge witness rather than lifecycle state:

1. In `KnowledgeServerBootstrap`, record the physical client health epoch (`false -> true`) and
   the last successfully initialized epoch. Reset both with the client during close, beside the
   existing reset at `KnowledgeServerBootstrap.java:756-763,797-808`.
2. Both the startup poll and every monitor health tick submit their direct health result to that
   owner. On a new healthy epoch, run `completeReadyInitialization`; mark the epoch initialized
   only after the work succeeds, so a skipped/failed attempt remains retryable. A steady healthy
   tick is a no-op. A physical unhealthy observation arms the next healthy edge.
3. Preserve the existing first-connect versus recovery work:
   `reindexPersistedRoots`, help ingest and periodic sync on the first initialized epoch, then
   reindex plus periodic sync on later healthy epochs (`KnowledgeServerBootstrap.java:470-503`).
   The registry snapshot is not consulted. This is projection bookkeeping for an existing side
   effect, not another lifecycle authority.

This keeps the monitor's two arms structural. `!bootstrap.hasClient()` selects boot recovery
(`KnowledgeServerHealthMonitor.java:290-295,352-370`); a bound client selects direct health
sampling. A successful recovery attempt hands the bootstrap over whenever `hasClient()` becomes
true (`KnowledgeServerHealthMonitor.java:479-497`), and the callback invokes the same composition
root binding (`KnowledgeServerHealthMonitor.java:503-518`; `HeadlessApp.java:655-668`). Do not
make either handover wait for registry READY. The recovery owner must continue to emit its
attempt/fault/backoff evidence directly; a later coalesced registry READY callback is not a
lossless recovery event source, as the readiness plan already records.

`HeadlessApp` already uses the right predicate for the initial handover:
`knowledgeServer != null && knowledgeServer.hasClient()` precedes `connectAndBind`
(`HeadlessApp.java:572-584`). `connectAndBind` deliberately updates `HeadAssembly` first and the
API controllers second (`HeadlessApp.java:635-648`). Preserve this order and request one readiness
reconciliation after both bindings complete. `StatusLifecycleHandler.setKnowledgeServer` only
assigns the late-bound reference today (`StatusLifecycleHandler.java:239-243`), while the monitor's
first scheduled tick is delayed by the poll interval (`KnowledgeServerHealthMonitor.java:204-218`).
An explicit post-bind request makes initial and recovered clients sampleable immediately without
inventing another sampler or publishing READY blindly.

The other READY checks in these owners do not control composition:

- `KnowledgeServerBootstrap` clears its fatal-index recovery veto from a capability READY listener
  (`KnowledgeServerBootstrap.java:227-236`). Clear that diagnostic latch when the direct physical
  health observation succeeds. Waiting for sampled registry READY unnecessarily keeps a proven
  stale veto live, while clearing it from API/sample-age readiness would assign the side effect to
  the wrong owner.
- The monitor's exception path currently classifies a tick failure as `worker.lost` only when the
  capability was READY (`KnowledgeServerHealthMonitor.java:308-318`). Preserve the distinction by
  consulting the bootstrap's last direct physical-health/initialized-epoch witness, not registry
  readiness; API state and sample staleness cannot tell whether the client was previously serving.
- `HeadlessApp.connectWorker` uses capability readiness only for log wording after binding
  (`HeadlessApp.java:585-591`), and `tryStartKnowledgeServer` does the same after startup
  (`HeadlessApp.java:1610-1618`). Log the structural connection and direct health result instead;
  neither site should become a readiness gate.
- `HeadAssembly.connectKnowledgeServer` classifies rebuild-history evidence as ready/degraded from
  capability health (`HeadAssembly.java:1538-1558`). Record that the connection/rebuild completed
  and keep sampled readiness as separate evidence; this branch must not control initialization.
- `CapabilityHealthBridge` clears/asserts diagnostics and derives recovery occurrences from READY
  (`CapabilityHealthBridge.java:107-147,150-187`). It is a projection, not a binding decision.
  D1-2's registry/recovery-owner migration must replace those observations without moving any
  initialization into the bridge.

## Gates that must remain readiness gates

Structural presence only permits composition and observation. It must not admit user work.
Retain registry-backed `Capability.available()` checks for:

- HTTP route admission in `ApiSecurityFilters` (`ApiSecurityFilters.java:550-590`);
- search and indexing request controllers (`KnowledgeSearchController.java:111-118` and
  `IndexingController.java:60-67`);
- agent operation capability resolution (`HeadAssembly.java:1941-1950`);
- the upgrade safety predicate, which requires both capability readiness and a live client
  (`LocalApiServer.java:222-243`).

These consumers ask whether work may run. Replacing them with `hasClient()` would admit requests
while the four-input index conjunction is false.

## Earliest safe sampler attachment

All four component handles exist before `CoreApiAssembly` attaches the sampler:

- `index` and `encoders` are registered as `EngineRoot` fields
  (`EngineRoot.java:64-76`);
- `generative` is registered while constructing `HeadAssembly`
  (`HeadAssembly.java:480-502`);
- the API builder registers `api` before entering the `LocalApiServer` constructor
  (`LocalApiServer.java:1304-1314`).

`LocalApiServer` then constructs `CoreApiAssembly` before binding the HTTP server
(`LocalApiServer.java:263-299,325-338`). `CoreApiAssembly` publishes the handler taps and attaches
`statusLifecycleHandler::sampleAndBuildStatusSnapshot` at
`CoreApiAssembly.java:446-468`. That is the earliest safe single attachment point after the four
registrations. Keep `ReadinessReconciliationTrigger.attach`'s self-seed
(`ReadinessReconciliationTrigger.java:100-113`): before API bind or Worker handover it should
honestly produce non-ready. Subscribe the trigger to registry changes before the initial snapshot,
so the API transition to READY (`LocalApiServer.java:357-365`) cannot be missed, and add the
post-`connectAndBind` request above because binding the client is structural state rather than a
component transition.

## Regression obligations

1. A real handler starts with index `STARTING`, API `READY`, a structurally bound client, and a
   healthy `WorkerOperationalView`; the first explicit post-bind sample performs exactly one RPC
   and promotes index readiness. Leaving the old `available()` guard must fail this test.
2. A bound client whose startup health budget expires later returns healthy from the monitor:
   auxiliary initialization runs exactly once for that healthy epoch, the reconciliation callback
   still runs, and a steady healthy tick does not repeat initialization. A false/true physical
   flap runs the existing recovery catch-up once. API-only or stale/fresh registry transitions do
   not run auxiliary initialization.
3. Boot recovery still calls the structural handover with a bound client before sampled READY,
   rebinds both `HeadAssembly` and API controllers, requests a sample, and preserves the recovery
   callback/evidence.
4. Agent tools resolve after the rebuilt services are installed even while the registry index is
   still `STARTING`; their actual request handlers remain unavailable until the registry-backed
   capability is READY.
5. The production composition test asserts the trigger is attached once after all four real
   registrations, then observes the API READY transition and Worker post-bind request. Constructing
   four independent test handles is not sufficient production-wiring proof.

Only `git diff --check` was executed for this documentation-only audit. Required implementation
proof belongs to the D1-2 app-services/UI focused suites plus the production four-registration
boot test and the full batch-1 commands named in `stages/D1.md`.
