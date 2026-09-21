# D1 component model: current implementation plan

Baseline `492af9b89`; production proof is at its unchanged parent `ba1440624`.
C2's remaining hosted documentation gate does not block batch1 (D1 checklist §12).
This plan implements D1-1 through D1-3; no acceptance is claimed before wiring and proof.

## Ownership and alternatives

Keep the already-designed process-owned registry in core/app-engine. Extending the
two existing app-services Capability implementations alone cannot represent the API,
encoder owner, apply exclusion, or per-component versions and would violate core's
dependency direction. Four independent registries would duplicate authority. One
registry with four stable registrations is the smallest design satisfying D1-1/2.
Actual resource construction, close and recovery stay in their current owners; the
registry closes its observations/subscriptions, not Lucene, API or model resources.
No persistent store, executor, timer, or autonomous recovery loop is added in D1-1.

EngineRoot constructs the registry beside executors, using its RetainedStateBudget.
HeadlessApp passes it through the real front composition and final process cleanup;
index restarts retain the same component registrations. The index constructor gets
its existing physical owner's handle. EncoderSet replacement remains D1-12: the
current inference surface reports encoder state until that work lands.

## Contract and concurrency

`core.component.EngineComponentRegistry` registers a validated ComponentSpec and
returns a ComponentHandle; snapshots are immutable and sorted by name. A spec has
name, essential, immutable dependencyKeys, compose capability (BESIDE, IN_PLACE,
CHOOSES_PER_APPLY), startDeadline (zero means no deadline for api), recoveryBudget.
The existing checklist states and fields are retained, including wall/monotonic
stateSince, applied/desired versions, last compose evidence and recovery count.
State and associated reason/evidence update atomically. A no-op publication must
not reset stateSince; changing state does. Values are observations of the owner,
not a second physical lifecycle. Diagnostic evidence must not copy config secrets,
document contents, accepted preparations or authentication values.

Registry listeners run after releasing its monitor. They receive immutable snapshots;
projection consumers subscribe then read current snapshot and use a revision to
discard stale callbacks. Listener failure cannot undo committed owner state or
prevent other observers; isolate RuntimeException at that explicit observer boundary
and report it. Errors retain their existing fatal owner. Subscriptions are closeable.

`tryApply` returns a closeable same-thread lease or a typed busy/closed refusal.
Use the prescribed ReentrantLock.tryLock plus the existing attempted-configurations
permit, with explicit same-thread reentry refusal. Exactly one permit is held for
the lease lifetime; a wrong-thread close fails without releasing it. Registry close
refuses while an apply lease is outstanding, preserving that lease for its owner.
After successful close registrations and mutations refuse; lease close is idempotent.
Only this retained producer is activated; generation and encoder accounting remain
unimplemented until their owning items. The policy reader must distinguish a
declared connected producer from a future producer without inventing live zeroes
before actual owner construction.

Applied-value hashing belongs above core, using the existing configuration authority
and canonical JSON machinery. Core receives only digests. D1-3 must cover EnvRegistry
and YAML-only ConfigKey; settle their actual identifier/value projection before
classifying the register. An accepted C2 revision/key is never an applied-value hash.
The key universe is the unique union of both declaration enums. Apply scope and
component dependency are different axes: a generation-bound encoder key remains
an encoder dependency, and one shared key can affect multiple owners. Therefore
dependency sets come from each owner's typed applied-value projection, validated
against the declarations/register, rather than filtering only component-scoped rows.
`InferenceConfig.vduMode` is a procedure mode without an operator key; retain it in
runtime-mode evidence, not under an invented or unrelated config key. Config hashes
do not certify procedure mode or native resource identity.

Encoder composition observes roles at their existing selection sites. Disabled
optional roles do not fail readiness; a requested-but-missing role does. A selected
BGE-M3 failure stays missing even when the existing SPLADE fallback can serve.
No requested roles means intentionally UNAVAILABLE encoders with lexical operation
preserved. READY requires every requested role composed and service wiring finished;
the always-released model latch is insufficient. The composition's supplied config
must feed the typed role factories and their digest, without a second global read.
Component reason vocabulary/projections and live observation remain D1-2/D1-15 work;
foundation unit tests do not close this connected acceptance.

## Sequence and proof

1. Implement bounded core contract and app-engine registry; test immutable snapshots,
   listener lifecycle, coherent concurrent publication, busy/reentrant/wrong-thread
   exclusion, retained count and close behavior. Root alone runs Gradle.
2. Wire all four actual owners and process lifetime; derive versions from declared
   applied values. Prove real registration coverage (floor4 plus explicit exceptions),
   changes for a declared dependency and stability for an unrelated key. Reconcile
   retained register and policy tests; keep every existing retention check.
3. D1-2 replaces the two Capability authorities with registry projections, preserving
   held-cause semantics, listeners and consumer gates. Derive envelope/health/manifest
   from the same snapshot; schema2 and host/readiness consumers change together.
   Delete superseded lifecycle derivation in this batch. Preserve actual index READY
   conjunction and stability-clock evidence.
4. D1-3 supplies the complete config-apply projection/register, generation-dependent
   aggregate hash, and config-surface fourth scalar. Negative controls prove missing
   and unknown keys fail. Regenerate schemas and connected UI consumers.
5. Run the checklist's batch1 module/frontend/governance checks and both supervisor
   adapters; independent review refutes disconnected registrations, stale readiness,
   secret leakage and wrong-reason passes. Record tested revisions and artifacts,
   checkpoint and continue D1-4 through D1-17.

The general principle is one resource owner publishing multiple diagnostic projections.
It earns its keep when envelope, manifest and host readiness agree under fault/recovery;
retire an adapter or abstraction when it adds an independent authority or has no real
consumer. This batch does not generalize the registry to arbitrary plugins or jobs.
