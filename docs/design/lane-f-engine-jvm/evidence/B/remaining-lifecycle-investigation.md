# B14/B15 investigation — 2026-09-08

Read-only investigation at `1ffd6cc2d` through `d23e94bdd`; these are unresolved
design questions, not completed work or amendments to the locked acceptance.
The orchestrator must decide them in design section 0 before implementation.

## B14: a stale state file is not a current supervision owner

The draft says a subsequent Engine reads exhausted state and transitions a live
capability. In production, a predecessor's file can be read during a race before
the new supervisor publishes its state: Tauri spawns first (`lib.rs:1693-1712`) and
the Rust loop publishes starting later (`supervisor.rs:578-586`). Node also spawns
before supervisor initialization (`dev-runner.cjs:2042,2483-2513`), and its cleanup
does not remove the supervisor record (`:2098-2105`). This is reachable, but it
cannot establish a reliable current-owner terminal veto on a deliberate fresh boot.

The Rust record carries supervisor kind, state, incarnation and Engine discovery
identity, but no live supervisor owner identity (`supervisor.rs:445-477`). Node
does not forward an expected supervisor launch identity to the child
(`dev-runner.cjs:1968-2024`). Mere presence of a starting/running file cannot prove
that a supervisor is alive and responsible for this Engine.

`KnowledgeServerBootstrap.supervisionActive()` is currently false (`:450-462`).
Changing it to file-exists/state-equals would activate the local recovery veto in
`BootRecoveryDecision.java:188-195`. That monitor currently has no transfer action:
it stands down and rechecks later (`KnowledgeServerHealthMonitor.java:303-321`),
while the manual path returns a supervision veto (`:594-645`). Process supervision
uses cheap API liveness; an API that answers while its index failed boot does not
by itself trigger process recovery. A live-owner check alone therefore does not
resolve the missing recovery owner after the veto.

Decide whether the obsolete whole-Worker supervision veto should be retired in
favor of bounded local index recovery while the Engine is alive, or whether an
explicit, budgeted Engine escalation path must take over. A requested restart is
uncounted (`supervisor.rs:312-331`); using it for persistent boot failures would
create unlimited retries. Non-transient failure already has a terminal classifier
(`:365-383`). Preserve fatal index/schema vetoes and their remedies.

Also decide the real terminal producer: the host/UI can report Engine restart
exhaustion while the Engine is dead; importing that predecessor fact as a current
Engine capability requires an explicit handoff and must not block a fresh boot
because of stale residue. Any owner/launch contract must be written before spawn,
matched by the child, and checked for live ownership. The B14 acceptance must prove
the selected production path rather than only a simulated stale file.

## B15: promotion responses lose restart information

`MigrationOps.java:32-55,91-109` collapses start/rollback to accepted, while the
worker response echoes the request's restart flag (`MigrationControlOps.java:37-63,
160-185`). Cutover lacks a restart-required field (`indexing.proto:1189-1193`,
`MigrationControlOps.java:74-92`). REST responses are generic 202 results
(`IndexingController.java:430-483`). The flag must follow the actual need to reopen
the promoted generation and reach a concrete restart consumer.

The existing three restart-as-reload refusals are named in
`RestartRequiredException.java:8-13`; HTTP still returns 409
(`InferenceHandlers.java:628-683`). The only current Engine request writer is
upgrade-specific (`HeadlessApp.java:1219`). Lifecycle response-before-shutdown
ordering is an existing precedent (`LifecycleApiModule.java:44-65`). B15 must
connect supervised restart to the promotion and install/import paths while keeping
an honest restart-required remedy when no trusted restart owner exists.
