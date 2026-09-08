# Stage B scope and sequencing review (2026-09-08)

Reviewed at `3721395ff`, before implementing the shared request-slot protocol.
This is a design review, not execution evidence. The orchestrator re-read design
sections 3, 6 and 17.8 and obtained an independent read-only review.

## Stop-rule disposition

**Stop the unimplemented shared-slot batch and re-cut its ownership.** Section
17.8's checklist-growth bullet literally names stage A, but the same architecture
signal applies here: an ephemeral second shutdown trigger has grown into claim,
acceptance, retention and cross-process outcome recovery. The later-mechanism
bullet is relevant because this starts supplying C2-shaped acceptance and outcome
semantics before C2. Do not quietly move those guarantees into B.

The main-development trigger already fired earlier (design section 0,
"17.8 bullet 1 fired"). Its recorded disposition remains: keep the branch, merge
main at stage boundaries, and reconsider the cut if the lane's blast radius
conflicts. A fresh fetch still found `origin/main` at `83b9e5fd5`; that is no new
trigger, not evidence the earlier trigger never fired. No release request or gate
requiring another process boundary was found in this review. B's temporary global
admission freeze already has a named replacement in C1; no C1/C2 implementation
is newly authorized by this review.

## Alternatives and costs

| cut | benefit | cost / decision |
|---|---|---|
| Supervisor alone writes the out-of-band file; its reason and deadline remain authoritative in memory. Engine-local actions invoke the ordered sequence locally. | Matches B2's existing SHELL ownership; keeps graceful shutdown when the API is wedged; removes cross-language claims and Engine acceptance rewrites. | Preferred direction, pending proof of a bound for a stalled Engine-originated restart while health still responds. A requested exit code supplies classification only after death. |
| One single-writer artifact per direction | Separates publication ownership and can expose Engine-originated intent to the host. | Adds another schema, cleanup and recovery lifecycle. Retaining accepted intent across death recreates much of the protocol being removed. Use only if existing lifecycle evidence cannot meet the bounded-shutdown requirement. |
| Bounded authenticated HTTP, then force kill | Removes the request file. | Rejected: loses the existing graceful-hang case when the API pool is stuck but the dedicated watcher can still close resources. |
| Shared first-claim slot and accepted-instance marker | Preserves exact Engine-originated deadlines and acceptance through death. | Rejected as the next B batch: introduces three-language claim, publication, acknowledgement and cleanup coordination. Its promised behavior must be explicitly narrowed or assigned to its proper stage before replacement code lands. |

The post-response/pre-dispatch crash window must be stated honestly. A flushed
HTTP response is not proof of completed shutdown. The updater already requires
its nonce-bound final receipt before installation; closing all accepted-operation
crash windows durably belongs to C2. Conversely, a responsive API during a stalled
close is a present lifecycle problem: R7 correctly treats HTTP 503 as liveness,
so ordinary hang detection alone cannot bound that case.

There is a narrower candidate than another artifact:
`RuntimeManifestPublisher.publishLifecycle` already writes the canonical lifecycle
(`RuntimeManifestPublisher.java`:611-620), whose vocabulary includes STOPPING.
An admitted current-instance STOPPING observation could start the host's existing
grace period in memory. This is not yet a verified implementation. Later capability
publications can overwrite that lifecycle
(`RuntimeManifestListenerWiring.java`:105-162), and the current ordered sequence
deletes the manifest before stopping the API (`HeadlessApp.java`:1340-1345).
STOPPING must remain monotonic through those later publications, and the manifest
must remain observable through every close step during which health can respond.
The proof must cover both hazards, preserve final child-handoff evidence, and show
that no stale instance can arm or cancel the deadline. Failed publication cannot
justify an unbounded voluntary restart: a completed promotion can remain complete
with a visible restart-required remedy. No new local watchdog is approved here.

## Scope retained and work to remove

The child registry, reconciliation, dead-Engine updater and versioned manifest
were already in the stage-B row and checklist. They remain required. The runtime
contract bump follows a breaking manifest change; it is not a new feature. Their
specific handoff callback and retention mechanics still need proof against that
requirement, rather than being justified by the size of the earlier amendment.
The updater's exclusion of replacement while applying an update is ownership of
an existing child; prefer the host's existing authority to a second transaction
record. The recovery UI must expose the existing terminal host state through its
real boot path, not create another recovery authority.

The local upgrade preparation reservation and response-failure rollback address
proven concurrency bugs. Keep those protections. If the single-writer cut passes,
remove the Java upgrade request writer and bridge verifier, the watcher's
controller-phase coupling, supervisor scans of Engine-written requests, and the
unimplemented accepted marker, shared claim/staging rules and corresponding race
fixtures. Update B2/B3/B6/B15, section 7.3, conformance and recoverability ownership
together. Retain strict stale boot cleanup, watcher lifetime, the shell-owned
request's schema, the final receipt and named requested-exit classification.
Reviewed code already committed is not evidence that its transport must survive.

## Required proof before the replacement batch

1. A production controller/bridge test blocks response flush: no local dispatch
   before release, exactly one after it, none on flush failure, and the preparation
   remains retryable/cancellable after failure. Moving dispatch before flush must
   make the test fail.
2. Both real supervisor bindings handle a requested exit without an Engine-written
   request file and restart without charging the crash budget. Counting that exit
   must make the test fail. Preserve graceful soft-hang and forced whole-JVM cases.
   Exercise production lifecycle methods; a fake actuator does not prove their
   binding. Tauri setup/AppHandle call sites remain source-reviewed unless executed.
3. Establish the owner and deadline for an Engine-originated shutdown that stalls
   while health still responds. Exercise that production path; do not infer it
   from an eventual exit or the updater's separate timeout.
4. Exercise the updater's host-owned stop intent and exclusion of replacement
   through commit failure, competing quit/hang and timeout. A missing final receipt
   must still prevent the normal install path. Which host intent wins an upgrade
   versus quit/hang race remains part of this proof, not an implemented contract.

The general lesson is narrow: put each cross-process artifact under one writer
and keep decisions in the process that owns the child. It earns its place if
these proofs pass while claim/acceptance/cleanup machinery disappears. Reconsider
it if a current requirement needs independently recoverable intent after the host
itself dies; do not build a general single-writer framework in this lane.

## B15 precursor proof after B11–B14 integration (2026-09-08)

The existing `shutdownHandoff` is a narrower candidate than changing aggregate
lifecycle into another stop authority. `RuntimeManifestPublisherTest.
pendingShutdownSurvivesLateReadinessWritesButNotANewIncarnation` exercises pending
restart, three late readiness publications, finally close, and a successor seed.
It passes on production B11 code. Temporarily dropping the handoff in
`publishLifecycle` fails the exact handoff equality assertion; restored publisher
tests (26) and UI test PMD pass. No production code changed in this proof.
Independent read-only review signed off its scope and discriminating assertions.

Independent host/design review identified the required exit priority: a terminal
writer fault runs the ordered RESTART close and therefore publishes a restart
handoff too. Copying that reason into the current supervisor requested-reason slot
would make the fatal exit free. Observe local handoff separately from host intent;
a local timeout and fatal exit are charged, while a dedicated clean requested-restart
exit covers death before the host's first manifest observation. Move publication
before blocking close steps, keep upgrade dispatch after successful response flush,
and bind any deadline to the admitted PID/instance without refreshing it on polls.
Those production bindings and the four proofs above remain unimplemented here.

| Ignored artifact | SHA-256 |
| --- | --- |
| `tmp/b15-handoff-lifetime-proof.txt` | `63f7f9d6d00cca4e166a74ac662d3f35b53563e1b34178e191f570366720b70b` |
| `tmp/b15-handoff-lifetime-negative.txt` | `e514d84846e75135db99e5ca5a2820996dfebadcaab508bf369b65b7caf5b5bd` |
| `tmp/b15-handoff-lifetime-restored.txt` | `a823ac4e3e102da3b4fecd6082199ebe02342fe465cbe0561c4a45035e421b65` |
