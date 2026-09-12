# C2-2 offline procedure: result and shutdown details

September12 at55b8aeb15. This refines the already-decided shared coordinator owner;
implementation candidates are not yet applied or verified. No owner input is pending.

## One result projection

Add one immutable app-api OfflineProcessingOutcome for the captured pass, reused
for progress and the final result. The existing EnrichmentProgressView describes
global coverage and cannot represent a single attempt. The new projection carries
selected, processed and failed counts, a bounded block reason and embedding-handoff
status; remaining and blocked counts derive from those values. It contains no paths,
document text or workId. The handler projects it to OperationRecordHandle checkpoints;
the existing runner remains the sole terminal writer.

Only acknowledged document outcomes count: usable text is processed; committed
no-text, abstention or failed-document results count as failed. A false/throwing
index acknowledgement is not a completed unit. Preserve prior checkpoints and fail
the attempt without recursively trying to turn a control failure into a FAILED
document. markVduProcessing's negative scalar cannot distinguish retry exhaustion
from missing parent: stop with PROCESSING_REFUSED and leave that unit unacknowledged.
No new protobuf acknowledgement type is needed for this conservative result.

Capture the existing default selection of at most100 IDs once. Capability or pacing
blocks report the captured remainder. If the Engine cannot reach selection at all,
report zero selected plus the explicit blocking reason; do not invent selected IDs
from a global count. A completed pass is not a promise to drain future/global work.
Embedding completion here means the mode handoff, not autonomous backfill completion.

## Actual completion and cancellation

The coordinator receives the shared executor registry and admission authority.
Manual and automatic submissions use the same single-flight guard, claimed before
submission and released only on actual exit. All procedure/model/index calls use
the context returned by admission.attach; for already-admitted manual work this
retains its exact identity, and automatic work receives one newly admitted identity.
The sampler keeps only its pacing timer and supplies explicit internal attribution.

EngineFutures owns task submission and cancellation. Its FutureTask cancellation
result can precede body cleanup and discard a later cleanup failure. Keep a separate
actual body result and an actual-exit completion stage, composing them for the public
result. This is per-submission future composition, not another lifecycle registry.
Register cancellation before submission using a future bridge to the task handle;
check cancellation at body entry as well. A queued cancellation executes no procedure.
Submission refusal invokes the same once-only actual-exit callback synchronously;
attach any captured cleanup failure to that refusal because no stage is returned.

Actual-exit cleanup attempts cancellation detachment and work-reference release,
then releases single-flight. Success linearizes at the cancellation-reason snapshot
after that cleanup. A cancellation already known there prevents success; a later
request cannot rewrite it. Preserve actual body/fatal failures and suppressed cleanup
failures rather than losing them to an early cancelled FutureTask result. Normalize
completion wrappers so the existing runner observes CancellationException or Error
directly; an interrupted wait is cancellation with its original cause retained.

## Shutdown barrier

Registration.close shuts down and waits for a bounded interval but does not throw
when the executor remains alive (DefaultEngineExecutorRegistry:143-155,296-305).
The coordinator must explicitly check its retained ExecutorService.isTerminated.
An unfinished owner refuses clean close and retains its registered instance.

HeadAssembly runs that repeatable barrier before claiming its closed CAS. A timeout
must leave a later close able to finish dependency cleanup after the body exits.
Do not put it in OrchestrationHandles' aggregate-and-continue teardown. The Headless
ordered sequence needs a successful HeadAssembly close before index/store/telemetry
closure; its existing failed-step/unclean receipt is the reporting authority. Its
fallback cleanup must preserve the same dependency condition. LauncherEnvironment
must propagate a failed HeadAssembly close before closing the operations store or
restoring process configuration. No second receipt or persistent shutdown marker.

## Required proof

Preserve existing mode/VRAM/abstention and coordinator guard-cleanup intent. Use real
batch calls, not copied test implementations. Prove exact context; queued/running
cancellation; blocked cleanup retaining admission and row; one manual/automatic
owner; submission/cleanup failure; partial acknowledged checkpoints; late mode-exit
failure; retained dependencies and an unclean shutdown receipt on nontermination.
Use the real store/runner/admission in an integration fixture. Negative checks restore
early result publication, detached manual work and swallowed write/cleanup failures.
Focused checks, full/PMD, live actual-model/API and installed-path proof remain owed.

Independent read-only design review confirmed the future composition and identified
the refusal-cleanup and closed-CAS hazards above. This is a design review, not an
implementation or test pass.
