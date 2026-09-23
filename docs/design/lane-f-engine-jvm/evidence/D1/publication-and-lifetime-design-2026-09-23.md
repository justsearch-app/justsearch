# D1 publication and lifetime design

Status: selected design, implementation and production proof outstanding.
Inspected code baseline `22800c842177321e22f430e5be339f13465e0242` (later commits
through09fdbe769 change only documentation/evidence). This record settles the
open D1-4 publication/teardown mechanism and its interaction with D1-8/12/13 and
D2-6. It does not claim these mechanisms already exist or reduce stage acceptance.

## 1. Facts and selected ownership

`SettingsCommitCoordinator.applyOwned` currently holds its mutex through file
preparation/replacement and ConfigStore swap; `ConfigStore.swap` creates its event
after mutation. `OperationAttemptRunnerImpl.applySettingsOwned` owns the accepted
row, full-witness reservation and SQL arming. `HeadAssembly` holds separately
published client and ServiceGraph fields. `DefaultEngineComponentRegistry` has
an apply lock and a distinct observation monitor. None alone supplies a coherent
capture of configuration, service references and component observations.

Source anchors at the inspected revision:

| Owner | Source (relative to repository) |
| --- | --- |
| Existing transaction | `modules/app-services/src/main/java/io/justsearch/app/services/settings/SettingsCommitCoordinator.java:166` |
| Runner authority | `modules/app-observability/src/main/java/io/justsearch/app/observability/operations/OperationAttemptRunnerImpl.java:479` |
| Separate reference reads | `modules/app-services/src/main/java/io/justsearch/app/services/HeadAssembly.java:1456` |
| Config swap/event allocation | `modules/configuration/src/main/java/io/justsearch/configuration/resolved/ConfigStore.java` (`swap`) |
| Existing registry locks | `modules/app-engine/src/main/java/io/justsearch/app/engine/DefaultEngineComponentRegistry.java:25` |
| Shared resources | `modules/app-engine/src/main/java/io/justsearch/app/engine/DefaultEngineProcessResources.java` |
| Releaseable admission freeze | `modules/app-engine/src/main/java/io/justsearch/app/engine/EngineAdmissionController.java:84` |
| Ordered teardown/finally | `modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java:1487` and `:1326` |

Selected mechanism: one process-owned publication read/write lock, allocated by
the composition root with EngineProcessResources and injected into ConfigStore,
component-registry publication and runtime owners. Use the JDK lock rather than
a new general transaction framework. Configuration remains a leaf: inject a JDK
lock, not an app-engine dependency. Both executable roots and the later embedded
composition must receive the same instance; no static second lock or late mutable
rewiring. Process-resource creation may precede ConfigStore initialization.

The write side covers only final validation/prebuilding, the file replacement
boundary and installing prepared references. It never covers candidate startup,
RPC, native drain, user callback, listener delivery or executor joins. File
replacement/ambiguity inspection is the deliberately accepted I/O under this
lock: captures cannot enter the committed-file/unpublished-runtime interval.
Measure this interval in integration; do not claim a latency bound without proof.

The read side covers capture plus resource-lease acquisition, never the entire
request. A request receives a coherent immutable view and owns its resource leases
until its actual synchronous/asynchronous work exits. A record of references is
not a lifetime guarantee. In-place replacement must drain/refuse holders before
destroying A; it cannot keep an old captured view apparently usable after close.

Why this choice: independent atomic/volatile swaps allow mixed pairs; globally
draining every request for every setting would block unrelated work and can
self-deadlock on the reconfigure request. A giant cross-module runtime record
would centralize module-specific objects and duplicate existing owners. The short
shared capture boundary permits typed owner-local views without those problems.
Its cost is mandatory migration of consistency-sensitive readers and lock-order
discipline; partial adoption is not acceptance.

## 2. Transaction protocol and linearization

1. The existing runner accepts exactly one RECONFIGURE row and validates the full
   SettingsWitness (revision AND operation key). Reserve through the fixed settings
   owner, arm the existing SQL marker, acquire the existing registry apply lease.
   No nested applyInternal, second journal or handler-owned terminal writer.
2. Build the typed desired UiSettings/SettingsV2 candidate. Classify all changed
   dependencies. Refuse generation-bound keys with the explicit reindex pointer;
   restart-required values are desired only until the requested successor boots.
   No-op dependencies do not advance unrelated applied versions. Use the existing
   typed API-port plan, including its environment-override-negative proof.
3. Through a fixed composition-root collaborator, prepare all fallible component
   work, response payload, cleanup ownership and rollback plan before commitment.
   Prepared runtime changes are opaque owner-created values, not arbitrary handler
   callbacks. Component registry remains the observation authority; the composition
   collaborator supplies implementation behavior, not another state registry.
4. Side-by-side candidates are private until publication. In-place candidates first
   publish the affected component as RELOADING/unavailable and stop new acquisitions
   of that affected resource, then drain actual leases. Unaffected text/query paths
   continue according to the existing readiness contract. Timeout refuses before
   destroying a still-held resource. Compose B; on any precommit failure/cancellation
   close candidates, recompose A if it was destroyed, retain refused-close resources,
   and report both causes if A cannot be restored. Settings bytes/revision stay A.
5. Acquire the publication write lock. Revalidate the reservation, captured owner
   identities and shutdown state. Resolve cancellation-versus-commit once through
   the runner's fixed private control: cancellation winning before commit admission
   aborts; commit admission winning makes subsequent cancellation advisory until
   publication and durable receipt handling finish. Do not expose terminal authority.
   Prebuild the ConfigChangedEvent and registry batch snapshot using the latest
   unrelated component observations while writers are excluded. Final validation
   and all ordinary allocations still precede replacement.
6. Replace the settings file with the existing prepared atomic replacement and
   witness. The witness resolves an I/O error reported after a successful move.
   If commitment is proven, install only prepared config/service/component
   references and the prevalidated receipt before unlocking. If commitment is
   uncertain, retain the existing recovery fence and candidates, refuse new serving
   captures for affected resources and request ordered recovery; never guess A or B.
7. Outside all physical publication/owner locks, notify observers. Notifications
   do not compose resources or determine transaction success. The runner persists
   COMPLETE from the committed receipt before clearing its logical settings fence.
   Postcommit outcome-write failure remains committed recovery, never FAILED.
   Restart-required scheduling follows durable row completion, once.
8. Retire old resources outside publication locks. A failed old-resource close
   retains its owner/permit and existing recovery path; it does not undo a committed
   configuration or silently leak a resource. A true process-fatal failure during
   publication is recovered from the durable witness on restart, not by rolling the
   settings file backward or claiming an ordinary precommit failure.

The existing ConfigStore swap must gain an owner-only prepared-swap path so event
construction is precommit. The registry likewise needs a prepared batch install
that does not allocate a snapshot or call observers after the commitment point.
Retain ordinary startup/health publication through the same lock; no shadow path.
The no-fallible-postcommit claim covers application operations, not JVM failure.

## 3. Reader migration and lock order

Inventory every consumer pairing configuration with mutable runtime references.
The implementation acceptance inventory must include:

- HeadAssembly core/worker/inference access and rebuild/connect paths; replace
  separate controller back-reference refreshes with stable capture-capable facades.
- HTTP search/RAG/chat, MCP operations, agent/workflow nested effects, and embedded
  ports; each actual operation captures once at its owning boundary. Nested work
  either retains its parent's view or explicitly starts a new independent operation.
- Worker search legs, enrichment/backfill and generation activation; one operation
  cannot reacquire different encoders halfway through a query or document effect.
- Settings responses, readiness/status/component-map/manifest projections; combine
  desired/applied fields from one protected capture, preserving intentional pending
  desired values rather than asserting desired always equals serving.
- Config-only hot readers may use ConfigStore.get under its injected read lock;
  readers needing resources must capture both under one enclosing read guard.
  Captured operation code must not later fall back to ConfigStore.global().get.

Use existing EngineContext for attribution and admitted lifetime. Do not serialize
runtime objects/leases into its wire representation. Explicit internal request
scope/view parameters or bound service facades carry the captured resources across
asynchronous boundaries. Thread-local storage alone is insufficient.

Lock order: existing settings/apply serialization and owner lifecycle locks first;
publication lock second; short local reference/lease/registry monitors last.
Generation promotion's existing STATE_CONTROL guard is an owner lock: acquire
it after runtimeSwapLock and before publication, as specified in the generation
decision. Re-entering it from its already-owning strict promotion is allowed;
acquiring it for the first time under publication is not.
Capture under publication read must NOT acquire settings mutexes, lifecycle locks,
runtimeSwapLock, run callbacks or wait for close. Lease retain/release uses short
reference-count monitors; capture failure unwinds acquired leases. No lock upgrade
from publication read to write. Notifications and deferred cleanup happen after
unlock. All registry mutation paths must follow this order, rather than taking
their monitor and then trying to acquire the publication lock.

Keep an immutable owner-local serving view for the actual related pointers.
Several assignments can occur inside the publication write lock because all paired
captures use its read lock. Single-field volatile getters are not exemptions.
Tests pause between each assignment and attempt a real consumer capture.

## 4. Generative candidate integration

D1-5's strict candidate context, logical/physical identities, rollback context and
cleanup rules remain binding. Its currently self-publishing apply entry cannot
be called as a fallible D1-4 prepare step: that would expose B before the outer
settings transaction commits. Split preparation from installation for the fixed
coordinator path. The existing manager owns an opaque prepared candidate and its
rollback; only coordinator publication installs its desired/applied references.

Lifecycle recovery and standalone boot still belong to the manager. During an
outer apply, the existing lifecycle serialization/owner token must prevent a late
recovery from publishing over the staged candidate. No manager lock is acquired
inside publication. Health failure after a committed healthy observation is a new
runtime event, not retrospective failure of settings persistence. No new executor.
Legacy settings listeners, reload-inference and install-time apply paths must join
the selected owner route or be retired with their tests and registrations.

## 5. Shutdown authority and dependency closure

Add a monotonic process-closing state to the existing EngineAdmissionController.
It is independent of the releaseable upgrade-preparation id, has no reopen method,
and refuses new ordinary work even when allowWhileFrozen is true. Only explicit
existing shutdown/control operations may bypass it; cooperative headers cannot.
Existing work may retain references to finish cleanup but cannot mint a new admitted
effect. Resource restart inside a live process does not enter process-closing.

Set closing under publication write plus the admission owner's short monitor.
This orders shutdown against final commit admission: a commit admitted first
finishes; otherwise the candidate aborts. Release publication before cancellation
callbacks or any drain. Releasing an old upgrade freeze cannot reopen admission.

Quiesce settings before destroying Head/inference dependencies. Request interactive
cancellation, stop producers through their actual owners, and wait for active apply
bodies/cleanup/receipt handling to finish. The reconfigure thread must not synchronously
join its own shutdown: recovery requests enqueue through the existing supervision
path. A shutdown control request is excluded only by its trusted exact handle,
never by client bucket or arbitrary operation name.

Durable row survival is not a live-work completion signal. Preserve durable rows
for restart while requiring all in-memory users of the operation store to finish
or checkpoint and relinquish access. Use the existing admitted-work reference
accounting and runner active-body ownership; do not treat Future.cancel, interrupt,
executor shutdown or a terminal SQL row alone as proof of actual body exit.

Keep EngineShutdownSequence's attempt-independent-cleanup behavior, but guard each
dependent destructive step with actual completion facts. Operations store requires
settings quiescence, runner/store-user quiescence and index closure. Process
resources require apply release plus dependent Head/index/native closure. Instance
lock release requires all stateful owners that could still touch the data directory
to have closed, not indexClosed alone. Apply identical guards in the startup/finally
cleanup path. Replace the current index-only lock helper; do not add a parallel
shutdown orchestrator or allow a finally block to bypass a refused ordered close.

On bounded-drain refusal retain the dependencies and OS lock and report unclean
close. Preserve the existing exit-code/receipt policy, but distinguish native
quiescence from other cleanup errors when selecting termination. F-016 records
ORT's own JVM shutdown hook: System.exit while an issued native lease is still
running can race that hook with the native call. Route an unquiesced native owner
to the already-used controlled Runtime.halt primitive (HeadlessApp's local-restart
fallback), through the same exit authority, after bounded receipt/diagnostic work.
Use ordinary JVM exit only when native quiescence is confirmed. This is an explicit
D1-13 amendment to the termination choice, not another supervisor or exit code.
Make the shutdown result carry actual native-quiescence disposition and inject
normal-exit/hard-stop strategies into the existing exit-authority path; a cleanup
exception string is not sufficient to select the strategy. The current System::exit
injection alone is not an implementation of this decision.
Native quiescence includes outstanding native creation/close work as well as
issued inference leases; a zero lease count while an owner is inside a native
constructor is not confirmation. Use the handle owner's in-progress state.
An unrelated telemetry error alone does not require hard termination. Test this
selection in an isolated child process; never infer it from mocked exit callbacks.
This guarantee applies when the Engine exit authority selects termination before
JVM shutdown begins. JVM shutdown hooks start concurrently and have no ordering
guarantee. An external SIGTERM/Ctrl+C, third-party System.exit, or last-thread
termination can start ORT's hook before HeadlessApp's hook finishes native drain.
The HeadlessApp hook may halt after detecting unquiesced work to shorten that
window, but cannot prove race freedom or graceful close. Treat such termination
as uncontrolled process death; its acceptance is durable recovery and eventual
OS reclamation. Keep product-owned fatal exits on the controlled exit authority,
or hard-stop immediately after crash reporting when ordered cleanup cannot safely
run. Run boot contract validation before any native-capable asynchronous startup.
OS reclamation at actual process death is not a successful graceful close.
Resource close APIs remain retryable;
the memoized process-exit sequence need not become a new resumable state machine.

## 6. Required proof and superseded paths

In addition to the full D1-4 stage acceptance, prove:

| Schedule/fault | Required result |
| --- | --- |
| Pause between config/graph/registry installations | Every real paired reader captures entirely A or entirely B |
| Capture A then commit B | A remains usable until actual lease release, or precommit in-place drain refuses |
| Second component preparation fails | First candidate closed; A restored; file/witness/revision unchanged |
| Cancellation before/after commit admission | Exactly one precommit refusal or committed receipt; no persisted B reported FAILED |
| File replacement reports error after move | Existing witness chooses committed B; no reverse write |
| Observer throws after publication | Serving B and committed outcome preserved; no composition in observer |
| Old upgrade owner releases while shutdown waits | Ordinary admission remains closed |
| Durable body ignores interruption | Store/executors/instance lock retained until actual exit or process death |
| Apply requests recovery from its own thread | No self-join; ownership retained through queued recovery |
| Head/process-resource close refuses but index closes | Finally path cannot close operation dependencies or release instance lock |
| External JVM shutdown starts a competing hook during native drain | The competing hook may run before HeadlessApp can halt; report uncontrolled death and prove restart recovery, not native race freedom |

Retire independent publication/back-reference rebuild paths, postcommit composing
listeners, nested settings operations, scalar-only witness sketches and index-only
lock-release logic. Preserve existing C2 witness/recovery and D1-5 negative controls.
Focused tests precede full integrated/platform and installed real-model proof.

The broader principle is atomic capture plus explicit lifetime ownership, with
durable commitment separate from notification. It applies to generation cutover
and cursor pinning, not arbitrary unrelated state. It earns its keep only when
mixed-view and use-after-close controls fail without the mechanism. If an existing
single immutable owner later supplies the same atomic capture/retention contract,
remove the redundant cross-owner lock rather than retaining two authorities.
