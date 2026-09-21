# D1-2 readiness ownership plan

Source audit at `574a88d47` plus initial owner wiring; implementation remains open.
This refines the current component plan without adding lifecycle authority.

## State and projection

One registry-backed Capability adapter serves both existing gate-facing names
(`worker`, `inference`) until D1-15 renames the vocabulary. It reads the registry,
not a copied health field. Its immutable observation includes registry revision,
health, reason, detail, required and name. Required is the component's essential
flag or a state other than ABSENT. Optional intentional absence does not degrade
the aggregate; unknown observations must not imply intentional absence.

| Component | Legacy Capability health |
| --- | --- |
| ABSENT | OFFLINE |
| STARTING | PENDING |
| READY | READY |
| RELOADING | RECOVERING |
| FAILED | DEGRADED |
| UNAVAILABLE | OFFLINE |

Subscribe before reading the initial snapshot; each closeable subscription drops
older revisions. Immutable previous/current observations preserve reason-only
notifications. Consumers retain subscriptions and close them with their owner.
Listener RuntimeExceptions are isolated at the observer boundary; Errors retain
their fatal semantics. The adapter does not retain held reasons independently.

Publishers apply the existing pure ReasonRetention rule before registry mutation.
Concurrent publishers must not race a snapshot/read and overwrite a held cause;
the shared publication seam must serialize the decision and update for the handle.
RecoveryContext stays with the recovery owner as diagnostic evidence; it is not
another lifecycle state. Preserve its attempt/fault/backoff event attributes.

## Owner changes and sequence

1. Introduce the read adapter and common publication semantics. Move generative
   mutations from InferenceCapabilityWiring, RuntimeActivationService and
   InferenceHandlers to the actual handle; remove HeadAssembly's temporary mirror.
2. Move index physical mutations from KnowledgeServerBootstrap,
   KnowledgeServerHealthMonitor and HeadlessApp to the stable index handle.
   KnowledgeServer retains actual start/failure/closed observations. Delete the
   two superseded mutable Capability implementations after consumers move.
3. StatusLifecycleHandler observes the worker sample first, updates contact age,
   publishes the exact existing host-ready conjunction, then captures one registry
   snapshot for the envelope and schema-2 lifecycle projection. READY requires API
   READY, successful contact, indexHealthy, and non-stale sample. A failed
   conjunction may demote previously observed READY to UNAVAILABLE; it must not
   replace physical STARTING, RELOADING, FAILED or ABSENT. Cached HTTP reads still
   detect a wedged sampler by clock age without an extra worker call.
4. Avoid reconciliation feedback: sampler READY/UNAVAILABLE publications cannot
   recursively enqueue another sampler pass. Physical transitions and changes in
   other components still trigger reconciliation. Preserve optional-AI, compatibility
   and throughput diagnostics even when essential index readiness is READY.
5. LifecycleProjection derives both manifest and schema-2 lifecycle from one
   snapshot. Delete computeLifecycleSnapshot's separate derivation. Update health,
   status schemas, frontend consumers and both hosts together. Both hosts read the
   index component state, retaining the existing stability-clock proof.

Primary owners: `StatusLifecycleHandler.buildStatusMap/computeComponent`,
`LifecycleProjection`, `CapabilityGraph`, `CapabilityPhase`,
`RuntimeManifestListenerWiring`, `CapabilityHealthBridge`,
`ReadinessReconciliationTrigger`, and the physical publishers above. Simple gate
consumers take the existing Capability interface; they do not need a new authority.

## Required proof

Six-state adapter and optional-absence tables; stale callback rejection and listener
close; held-reason races; preserved recovery attributes; each index conjunction
input independently false; cached sample becomes stale without an RPC; no sampling
feedback loop; all component combinations produce matching manifest and lifecycle.
Run existing gate/search/status tests, generated schema/UI checks and both adapter
conformance suites. Real boot must prove four registrations with a non-vacuous floor;
separately constructing four test handles cannot prove production wiring.

## Publication seam refinement after owner discovery

All index publishers must share retention semantics, including KnowledgeServer's
physical start/failure/close writes; an app-services-only read/then-transition
helper leaves those writes outside its serialization. Use an atomic conditional
transition on ComponentHandle and one stateless reason-retaining handle decorator
at registration. The decorator retries against the current immutable component;
the registry compares and commits under its existing monitor and notifies only
after releasing it. Pass the same decorated handle to all physical and supervisory
publishers. A separate synchronized wrapper would call registry listeners while
holding its own publication lock and is rejected. No extra lifecycle state,
executor, store or policy copy is needed; reuse ReasonRetention as the sole rule.

The conditional transition also lets the sampler reject an observation overtaken
by a physical failure/reload. Cached responses may demote an expired READY sample
but may not promote a newly STARTING owner from an older cached sample. Successful
fresh sampling can establish initial readiness; failure must preserve physical
STARTING/RELOADING/FAILED/ABSENT as specified above.

RecoveryContext currently relies on synchronous WorkerCapability callbacks. The
monitor must emit/pass attempt/fault/backoff evidence at its recovery decision,
not read a parked context from a coalescing adapter callback. The unused production
generation/isFirstConnect counters need no replacement. Listener owners must close
their subscriptions; CoreApiAssembly/CapabilityGraph test fallbacks must cease
constructing independent mutable capabilities when the migration is complete.

The production-registration regression now passes in2296 through HeadlessApp.buildApi
with the real shared EngineRoot registry and real API bind. It enforces floor4 and
an empty exception set without calling register itself. AI is disabled and the
index is not started in that fixture, so it does not prove model/index readiness.

## Aggregate projection decision

The four component states and the six existing overall lifecycle states are
different vocabularies. Keep the overall wire enum; publish the component enum
directly in schema2's four slots. Do not map ABSENT to an overall STOPPED slot or
collapse FAILED and UNAVAILABLE in the component vector.

The one aggregate function uses this ordered table, extending the existing index
policy to both essential components (`api`, `index`):

| First matching condition | Overall lifecycle |
| --- | --- |
| An essential component is FAILED or RELOADING | ERROR |
| An essential component is STARTING | STARTING |
| An essential component is ABSENT or UNAVAILABLE | DEGRADED |
| An optional component is neither READY nor intentionally ABSENT | DEGRADED |
| Otherwise | READY |

Choose the diagnostic component from the winning row in stable name order, and
project its reason/evidence into both health and manifest. Missing required
registrations are a composition error, not intentional absence. Optional absence
does not prevent text search; optional failure changes aggregate diagnostics but
not the host's essential readiness, which is the index component's READY state.
Enumerate all1296 four-component combinations against an independent expected
table and require identical manifest/health aggregate results. STOPPING/STOPPED
remain in the established overall wire vocabulary but are not inferred from
ABSENT; shutdown is represented by physical owners' component observations.

Conditional transition compares the complete immutable component observation
under the existing registry monitor. A matching no-op succeeds without a new
revision or listener callback; a mismatch returns false without side effects.
Unrelated components do not invalidate this single-component form. Reason-retaining
writers retry a mismatch. The sampler instead uses the explicit full-registry
conditional form: API readiness is a related precondition, and an API transition
between observation and publication must invalidate the sample atomically. A
sampler must discard its obsolete result rather than retry
with a newly read physical state. State transitions update the existing monotonic
state clock, so a physical phase change and return cannot accept an old sample.

The independent review also identified a bootstrap cycle in the old sampler's
`workerCapability.available()` guard. After the registry migration STARTING
projects PENDING, so that guard would prevent the first successful sample forever.
Sample eligibility must use structural client availability instead; READY remains
the sampled result. Bootstrap/monitor success performs its existing auxiliary
initialization and requests reconciliation without blindly publishing READY.
Preserve the existing monitor tick trigger. Explicitly suppress sampler-owned
index publications from scheduling another sample while still responding to
physical, API and optional-component changes. Prove exact RPC/run counts and
STARTING-to-READY boot/recovery, not just eventual readiness or burst coalescing.

## Schema and consumer decisions

The [schema consumer audit](schema2-consumer-plan-2026-09-21.md) and
[host audit](host-readiness-plan-2026-09-21.md) own the current paths and required
commands. Emit `LifecycleSnapshotV2` and a v2 lifecycle schema at the existing
routes; retire the unused v1 Java/schema artifacts when all references migrate.
The envelope retains camelCase `stateSince`; lifecycle slots retain snake_case
`state_since`. Both project the same registry Instant. Hosts treat `stateSince`
as an epoch token and measure continuous readiness with their existing local
monotonic clock, never by subtracting JVM wall time from a host clock.

StatusDeck preserves its existing meaning: live transport first, then both API
and index READY. Optional model state remains in the existing diagnostic notices.
Unknown/missing component state cannot satisfy a READY gate. No second frontend
aggregate policy replaces the existing diagnostic composites.

Manifest aggregate publication uses one registry subscription, installed before
reading the initial snapshot. The two old capability listeners miss API/encoder
changes. Worker/AI/mode/chat axis callbacks may remain projections but cannot pass
or overwrite the overall lifecycle. Under the existing publisher monitor, reject
registry revisions below the highest observed, advancing that high-water mark
before both no-op detection and fallible I/O. An equal-revision retry may retry a
failed write; a successful duplicate remains a lifecycle-equality no-op. Preserve
the existing write-before-current ordering and failure reporting. Own and close
the registry subscription before registry/publisher teardown. Tests must cover
API-only/encoder-only updates, delayed old callbacks after a newer no-op, failed
write plus same-revision retry, and closed subscriptions.

Core's ComponentState remains the runtime authority and gains no protobuf
dependency. The distinct proto component enum is a wire projection, with a
bijection test against core values. It must serialize plain component names
such as READY, not the overall LIFECYCLE_STATE_READY vocabulary. Reserve the
old StatusResponse components field number4 and allocate a new field number
for the new component message while preserving JSON name `components`. Never
reuse old head/worker/inference tags for different meanings. Retire unreferenced
old messages after a reference sweep. Keep unknown numeric proto values permitted
for forward compatibility; readiness decisions accept only known READY.
