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
