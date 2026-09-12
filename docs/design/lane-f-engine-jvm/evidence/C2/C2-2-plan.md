# C2-2: implementation seams and verification plan

Status: source-grounded at6a4059352 on2026-09-12; batch1 closed at e60e2cb0d; C2-2 implementation and negative proofs in progress.
The root owns all edits/builds. A read-only explorer mapped the seams; decisions
below resolve its returned ambiguities without owner gates.

Latest coherent projection/orphan proof is [checkpoint656](projection-checkpoint-656.md):
full build/PMD9,917 cases, stress659 and clean hosted orphan/queue/snapshot results.
Offline procedure guard cleanup is the next bounded correction; actual completion,
context and index durability remain open as described below.

## Decisions

1. Keep indexing.proto unchanged as C2 requires. KnowledgeClient.scanRoot builds a
   protobuf request with no inbound scan id (KnowledgeClient.java:1506-1530), but
   EngineKnowledgeClient.executeScanRoot/scanRootWork already calls the Java
   WorkerIngestService directly (EngineKnowledgeClient.java:900-967). Carry the
   accepted operation record id explicitly beside the request through these Java
   methods into WorkerIngestService.scanRoot. WorkerScanOps already persists scanId
   on queued units. Do not overload correlationId, add an operation key to
   EngineContext, mutate the wire schema, or use a thread-local. Maintenance calls
   retain explicitly unrecorded scan identity; user/boot walks must supply their
   accepted row. The existing worker-minted id is superseded on recorded walks.
2. OperationAttemptRunner's callable contract belongs in app-api; its implementation
   belongs beside the SQLite store in app-observability. Inject the same runner into
   app-services and app-agent through composition. No app-agent dependency on
   app-services is added. The restricted handler handle and actual-completion
   carrier must live in app-agent-api, because OperationHandler is already there
   and app-api depends on that module, so the reverse edge would cycle.
3. One dispatch retains one parent row. A dispatch that launches several root walks
   accepts child ingest rows for the individual walks through the same runner,
   aggregates their actual completion, and never completes at the immediate
   started response. Each child has its own scan/record id. Ordinary periodic sync
   remains maintenance. Explicit reindex routes through the recorded walk path;
   its current synchronous syncDirectory shortcut cannot bypass that authority.
   Parent acceptance persists the normalized root plan. Each child's identity_json
   includes parentOperationKey and root/generation identity; a locked store
   transaction finds-or-accepts that child, so a restart cannot mint two children
   for one planned root. Parent completion reconstructs from this persisted plan
   and relation. Normalize duplicate/ancestor roots; serialize overlapping recorded
   walks through committed completion, not just enumeration. No second relation
   table or new schema column is required.
4. A synchronous handler is adapted to an already-completed result stage. Async
   handlers return an immediate response plus their actual completion stage through
   an explicit handler overload carrying the restricted handle. The existing
   OperationResult wire record remains a response; a started response is never
   fabricated into a terminal result. The runner alone writes terminal state.
5. The runner accepts before scheduling/enqueue/effect, then owns start, failure,
   completion and reconciliation. Authentication/provenance/trust checks remain
   before exposing any existing outcome. Capability/input/admission failures after
   acceptance terminalize through the runner with no handler execution. A storage
   failure propagates and no unit runs. Handler acceptance of its own dispatch row
   is forbidden; child creation is explicit through the runner.
6. Boot registration declares kind owners before the unowned interactive sweep.
   Claim all current owner kinds synchronously in the process composition before
   the async index fork; attaching/running a ready reconciler is a separate action.
   An owner becoming ready reconciles its own rows. Durable rows and owned-but-not-
   ready rows are never terminalized by a default sweep. Ingest recovery finds the
   open root/generation row and reuses its id; no duplicate boot row is minted.
7. Identity comparison uses canonical inputs before returning an existing outcome.
   Generic argument identity stores a canonical digest rather than document/prompt
   bodies; replayable ingest/reindex descriptors retain only their required roots,
   generation, collection and scan policy. Thus the register's no-document-content
   promise survives write operations with content arguments. Key parsing, the
   client carrier, canonical conflict/expiry responses and full schema policy
   projection remain C2-3, but C2-2 must not create an incompatible acceptance API.
   Stored outcomes are explicitly non-content receipts: safe outcome/error code,
   execution id, unit counts and record/key identity. Never serialize arbitrary
   OperationResult.message, structuredData, diagnostic excerpts or prompts. The
   first invocation can return its rich result; a same-key retry returns the
   durable receipt without rerunning, not the original content-bearing response.
8. Negative568 reproduces an older committed unit incorrectly completing a newer
   claim of the same path (expected queueDepth1, actual0). Existing null scan ids
   already COALESCE-preserve the prior owner, but the unconditional path-only
   terminal UPDATE still loses claim identity. Use an opaque process-local claim
   capability: the one SqliteJobQueue connection/lock issues it at poll, invalidates
   it after a successful replacement, and only the matching claim can terminalize
   its row. Carry the original claim through extraction, pending commit transitions
   and failure paths. The capability need not persist: a restarted Engine cannot
   receive the dead JVM's callbacks. This is simpler than a new persisted revision
   column/schema16 or serializing every watcher behind a global operation lock.
   Verify that production has one queue owner and no serialized claim transport;
   fake/reconstructed claims must not acquire authority by matching path fields.
   Queue replacement batches must be atomic before claim invalidation. A scan must
   validate successful admission before counting units; a short/failed enqueue
   becomes a typed runner failure, never a clean enumeration completion.

## Ordered implementation within C2-2

- Extend the app-api store port and SQLite implementation with transactional
  acceptance, lookup and guarded transitions; descriptor/outcome contracts live in
  app-api, restricted handler handle/completion carrier in app-agent-api (no reverse
  app-api import). The schema is alreadyv1.
- Add the shared runner implementation and composition wiring. Keep existing
  history/advisory projection until C2-4 replaces its storage, with terminal
  projections emitted by the runner's completion owner rather than a second writer.
- Connect direct ingest, explicit/recovery walks and actual committed-unit
  completion; propagate the accepted id through the Java-only scan seam. Acceptance
  before the first enqueue and failed-acceptance/no-enqueue are required tests.
- Connect catalog dispatch/undo through the same runner, preserving trust,
  validation, lineage, grounding, audit suppression and advisory assertions. Keyed
  transport wiring and recordKind schema declarations complete in C2-3.
- Connect scheduled acceptance before timer submission; start after admission;
  scheduling/admission/refusal/uncaught errors become truthful terminal outcomes.
  BackgroundRunService's current swallowed exception cannot stand in for success.
- Register producers/projections under operation-surfaces with real test guards;
  run owner-scoped boot/default recovery regressions and the actual installed
  acceptance-before-effect fault point before claiming C2-2 complete.

C2-7 through C2-10 finish durable checkpoints, bounded attempts and reindex resume;
D1 supplies generation-specific reconcilers and activation. These are required
stage placements, not waived acceptance. Do not close an earlier item with a claim
that only a later producer can prove; keep each proof boundary explicit.

## Required evidence

Store tests must refute same-key duplicate execution, identity mismatch, failed
acceptance followed by effect, terminal reversal and an interactive default sweep
that touches an owned or durable row. Runner tests must hold an async completion
stage open after a started response and verify the row remains nonterminal.
Ingest tests must use the production boot/recovery entry and persist the same scan
id in jobs, including failure before the first enqueue. Scheduled tests must show
acceptance at scheduling time and failure when fire-time admission refuses.

Run operation-surface and register-guard-resolution gates, focused app-observability,
app-services, app-agent, worker-services, indexer-worker and UI tests, then the full
batch2 suite. Preserve XML/output before narrowed reruns; commit per item and push
immediately, with hourly WIP checkpoints if the item exceeds one hour.


## Independent review and negative evidence

The reviewer returned five issues: recoverable parent/child linkage and overlapping
walks, content-bearing result persistence, pre-readiness boot claims, a conflicting
handle-location bullet and ignored enqueue failure. The root's decisions above
resolve each; no owner question or deferred decision remains. Implementation tests
must demonstrate those decisions, including delayed/failed index startup, repeated
root/ancestor requests and sync/watch interference.

Run568: :modules:indexer-worker:test --tests '*OperationQueueOwnershipTest'
-PtestParallelism=1 --max-workers=4 --console=plain fails the intended assertion in
anOlderCommittedUnitCannotCompleteAReplacementClaim. Retained output/XML:
tmp/c2-2-queue-ownership-negative-568.txt and .xml. Production had not changed:
claimA, replacement enqueue/claimB, then A's pending completion marked B DONE.
This is a concrete prerequisite defect for C2's durable completion, not evidence
that adding a persistent revision column is necessary. The test will carry the
original claim once the production completion API can express it.

UUIDv7 generation/validation will use the field layout and test vector in
[RFC9562 section5.7](https://www.rfc-editor.org/rfc/rfc9562.html#section-5.7) and
[appendixA.6](https://www.rfc-editor.org/rfc/rfc9562.html#appendix-A.6): a48-bit epoch
millisecond timestamp, version7/variant2 and74 random bits. The database id remains
the ordering authority, so no persistent generator counter is needed.

### Queue prerequisite progress, September12

The root carried the actual IndexJob claim through extraction, ExtractedJob (path,
collection and provenance project from that claim), stale/failure/defer writes and
pending Lucene transitions, including journal per-unit fallback. SQLite claim
checks and state transitions share its existing lock. Replacement batches commit
atomically before invalidation. Duplicate callbacks cannot append a second ledger
event. Close/reset/deletion invalidates the corresponding capability.

Independent review found that Java record equality in journal removeAll could
remove a current claim's failed retry along with a value-equal stale transition.
Negative573 reproduced that exact loss (expected one pending transition, actual
zero); identity-based removal replaces the value-equality cleanup. The regression
also retries the surviving current transition successfully. Negative572 proved a
refused queue admission previously returned clean completion. Scan counters now
advance only after complete batch admission; refusal throws the typed UNAVAILABLE
QUEUE_ADMISSION_FAILED error instead of producing a clean terminal frame.

Evidence: tmp/c2-2-scan-admission-negative-572.txt/.xml and
 tmp/c2-2-journal-identity-negative-573.txt/.xml. Focused571 passed after updating the
IndexingLoopTest reflective helper to the new claim signature (569 and570 each
failed nine old-signature calls; 569 XML retained under
 tmp/c2-2-worker-services-569-xml). Integrated574 completed with eleven failures, all the other old reflective helper
in AdversarialCorpusIngestionTest; the rest passed. Its output/XML are preserved
in tmp/c2-2-queue-integrated-574.txt and tmp/c2-2-suite-574-xml. The helper is
migrated with unchanged behavioral assertions. Run575 passes (4m29s): full
worker-core/indexer-worker checks and the affected worker-services suites/checks.
The full worker-services results from574 remain evidence for unchanged tests,
not a claim that575 reran them. Output/XML/counts: tmp/c2-2-queue-verified-575.txt,
tmp/c2-2-suite-575-xml, tmp/c2-2-suite-575-counts.json.
These are prerequisites only; no runner, boot acceptance or durable completion
producer is claimed complete by them.

The existing IndexingJobsChangeStream flush runs inside SQLite's commit hook,
which precedes the completed COMMIT and must not execute SQL on its connection
([SQLite commit-hook contract](https://www.sqlite.org/c3ref/commit_hook.html)).
It is not C2's committed-unit authority. C2-2 will deliver operation completion
only after the queue transaction returns successfully, and will reconcile against
committed scan rows. The bounded UI/audit stream cannot substitute for this proof.

### Shared core implementation checkpoint, September12

Queue prerequisite b77b68e2f is pushed. The next diff implements the store port's
accept/read/start/checkpoint/terminal guards, canonical UUIDv7 keys, digest-only
invocation descriptors and typed receipts. CanonicalOperationArguments extracts
the existing ConsentCapsuleService sort/hash behavior into app-api for reuse;
consent semantics (including malformed-input fallback) remain unchanged. This
retires the former private capsule implementation rather than creating two
argument-binding authorities.

The runner accepts an opaque prepared capability before scheduling, starts only
a newly accepted body after admission, and keeps the immediate OperationResult
separate from actual CompletionStage completion. Duplicate calls receive the
receipt/progress snapshot and never steal execution. A refused scheduling/admission
attempt can fail only while ACCEPTED. Failed completion persistence leaves RUNNING
and fails the completion observation. Synchronous exceptions are recorded and
rethrown; fatal errors remain observable and require restart reconciliation.

Kind declarations precede the constructor's unowned interactive sweep. Reconciliation
uses only the boot-interrupted set, excluding newly accepted work. Ready owners
return wait/complete/fail/resume verdicts; only the runner writes terminal state.
The C2-8/D1 owners still supply their four-condition eligibility/identity proofs.

578 passes acceptance/key/consent and all six original Runtime.halt recovery
points. 576's six child failures were missing Jackson in the explicit child
classpath; 577 then caught the wrong JsonFactory package in that fixture, fixed
using the existing JsonParser type's code source. These were not weakened crash
assertions. Retained logs and XML: tmp/c2-2-operation-acceptance-576.txt through
-578.txt, tmp/c2-2-operation-576-xml and tmp/c2-2-operation-578-xml.
579 passes the runner/store/key tests and owner PMD checks (24s), with XML retained
under tmp/c2-2-runner-579-xml and log tmp/c2-2-operation-runner-579.txt.

Gate negative580 detects both new unregistered record writers. After adding their
source/port/producer entries, operation-surface581 and register-guard-resolution582
pass. Negative SARIF/log are retained as tmp/c2-2-operation-surface-negative-580.*;
positive logs are tmp/c2-2-operation-surface-581.txt and tmp/c2-2-register-guards-582.txt.
Producer wiring, installed acceptance-before-effect, keyed transport and all later
C2 work remain required. This core checkpoint is not C2-2 acceptance or batch closure.

Independent core review found no substantive defects after inspecting the dirty
snapshot on b77b68e2f and the retained evidence: 578 has49 tests and579 has35,
all without failures/errors/skips. Run583 passes OperationStoreArchitectureTest,
including a negative fixture proving a producer cannot write terminal state.
The bytecode rule also restricts acceptance/start/resume/refusal to the runner,
including calls through the concrete implementation. Output/XML:
tmp/c2-2-operation-architecture-583.txt and tmp/c2-2-architecture-583-xml.
Producer wiring remains the next change within C2-2.

### Dispatch/composition wiring checkpoint, September12

Core530da0914 is pushed. Composition now requires the same runner in EngineRoot,
HeadAssembly and the operation substrate, constructed before the async index fork.
Both executable roots predeclare the six owned kinds; generic interactive operations
retain the default interrupted failure. Dispatch and supported undo accept after
context/provenance/trust checks, before capability/input checks and handler effects.
Handlers receive the accepted handle. History/advisories follow the durable actual
completion stage. Unkeyed AuditPolicy.NONE retains its existing suppression.

584 found four constructor-fixture compile errors, corrected by supplying the
required runner. 585 caught a production regression: an unsupported undo began
eliciting confirmation. The original immediate refusal is restored; unavailable
undo has no effect or stored outcome to expose, so it precedes trust/acceptance.
Its regression test is unchanged. 586 passes93 tests in12 suites without failures,
errors or skips, including real-store async completion, acceptance failure/no body,
and separate undo identity. XML/log/counts: tmp/c2-2-dispatch-586-xml,
tmp/c2-2-dispatch-wiring-586.txt, tmp/c2-2-dispatch-586-counts.json.
Surface negative587 detects the new dispatch writer; registered projection588 passes.
Retained negative SARIF: tmp/c2-2-dispatch-surfaces-587.sarif.
Register guard589 passes. Owner checks590 caught12 unnecessary fully-qualified
names introduced by fixture/constructor migration; after removing those qualifiers,
591 passes the architecture test and app-services/app-engine PMD main/test checks.
Output: tmp/c2-2-dispatch-owner-checks-590.txt and -591.txt.

This wiring is still WIP: catalog recordKind/keyed transport, actual async handler
overrides, ingestion, scheduled producers and installed proofs remain required.
No installed acceptance claim is made from synchronous handler adaptation.

Independent review of8482492c0 found two defects. Negative592 proves synchronous
terminal-write failure returned success (expected OperationStoreException, nothing
thrown) using a real SQLite trigger refusing COMPLETE. The runner now propagates an
already-observed completion persistence failure before returning the immediate
response; later async failures remain exposed by the completion stage. The row
stays RUNNING and no terminal history is published. Two systemTest EngineRoot
constructors also needed the required shared runner; both now construct it over
their existing store. 593 passes the executor/runner regression suites and
:modules:system-tests:compileSystemTestJava. Negative log/XML:
tmp/c2-2-dispatch-storage-negative-592.*; positive log/XML/counts:
tmp/c2-2-scheduled-and-review-593.txt, tmp/c2-2-scheduled-593-xml and
tmp/c2-2-scheduled-593-counts.json. 593 also includes the next scheduled-producer
dirty diff; that producer is a separate C2-2 checkpoint, not part of this correction.

### Async completion decisions

Source-only owner inspection (retained here rather than requiring agent transcripts)
finds these semantic groups. RuntimeActivationService.startActivate/startDeactivate,
AiInstallService.startInstall/repair and AiPackImportService.startImport launch
owner threads and return snapshots; their recorded handler overloads must complete
from those same threads after terminal status and cleanup, with no parallel status
map or polling projection. Their current already-running failures remain failures.
Helper documentation claiming idempotence must be corrected with that wiring.

CancelAiInstallHandler requests cancellation synchronously; its operation completes
after recording/waking that request, while the install operation remains open until
its own thread finishes. SetChatEnabledHandler and SwitchInferenceModeHandler write
durable runtime intent; that write is their effect, and convergence belongs to D1's
reconfigure owner. ReloadInferenceHandler already applies overrides synchronously.
TriggerOfflineProcessingHandler must stop treating the coordinator's already-running
or unavailable branches as completed work; use an explicit owner result and actual
completion. These are implementation decisions, not owner-gated alternatives.

### Scheduled producer checkpoint, September12

BackgroundRunService now accepts through the required shared runner before timer
submission and starts only after fire-time admission. Rejected submission/admission
records its typed reason; shutdown terminalizes pending no-effect timers with
ENGINE_SHUTDOWN. The bounded pending set holds only runner capabilities alongside the
registered scheduler, not another durable authority. Exceptions are recorded and
propagated on direct calls; scheduled calls also log failures instead of swallowing
them as success.

The actual agent run remains authoritative: capture its session id as the checkpoint,
then after runAgent returns inspect the durable snapshot. DONE completes, ERROR
fails, CANCELLED cancels, and missing/nonterminal outcome never claims completion.
At boot the owner reads that same checkpoint's durable run before defaulting an
unrecoverable interactive row to interrupted_by_restart. Durable resume eligibility
remains a required C2-8 item. The generic reconciliation port now includes Cancelled.

596 passes35 tests in7 suites without failures/errors/skips and app-agent/ui/
app-observability owner PMD checks. Cases include acceptance failure/no timer, timer
acceptance/shutdown, fire-time refusal/no agent effect, durable error versus return,
three boot terminal states without rerunning, and C1's existing scheduler limits and
actual work-lifetime tests. Retained evidence: tmp/c2-2-scheduled-verification-596.txt,
tmp/c2-2-scheduled-596-xml, tmp/c2-2-scheduled-596-counts.json. 594's five fixture
qualification violations were corrected without rule changes. Surface negative595
detects the new producer; registered597 and guard-resolution598 pass. C2-2 remains
open for ingestion, async handler owners,955's sealed non-dispatched fixture and
installed acceptance-before-effect proof.

### Non-dispatched sealed-mutation consumer, September12

The app-api runner's existing accept/start/OperationExecution contract exposes
accept/complete/fail without catalog dispatch. Its Javadoc now explicitly describes
this use. No second terminal writer or arbitrary-row completion API is needed.
NonDispatchedMutationTest's producer holds only that port and the existing StoreCipher;
a separate SQLite effect fixture uses AEAD-sealed values. Five mutation labels stand
for955's admission granularity, not implementations of those product mutations.

A second operations connection sees committed RUNNING before sealing/writing. Each
successful effect produces exactly its one completed row; a keyed retry leaves one
effect. Injected acceptance INSERT failure proves zero entries into the sealed store.
Injected effect INSERT failure produces FAILED and a failed retry receipt without
re-execution; a locked cipher produces no plaintext fallback or effect. Generic
OPERATION kind is intentional until C2-3 adds memory/note declarations.

602 passes11 tests in3 suites (new consumer4, existing runner6 and the automatically
included diagnostic architecture guard1), no failures,
errors or skips, and app-api main/app-observability test PMD. Command:

```powershell
./gradlew.bat :modules:app-observability:test --tests '*NonDispatchedMutationTest' --tests '*OperationAttemptRunnerTest' :modules:app-api:pmdMain :modules:app-observability:pmdTest -PtestParallelism=1 --max-workers=4 --console=plain
```

Evidence: tmp/c2-2-nondispatched-602.txt, tmp/c2-2-nondispatched-602-xml,
tmp/c2-2-nondispatched-602-counts.json. Root independently inspected the XML and
failure injections. This is local port proof;955's actual MemoryAdmission/store and
C2-3 prepared replay remain their owning checklist items. C2-2 still owes ingestion,
async handler owners and installed acceptance-before-effect crash proof.

### Runtime activation completion mechanism

C2-2 uses the existing runtime owner thread and status, with one returned completion
stage per accepted activation/deactivation. No status polling, global future lookup
or durable payload is introduced. Snapshot terminal status before releasing the
single-flight guard; complete the stage only after owner lease cleanup. A second
attempt cannot overwrite the first outcome. Registration/start refusal must release
the guard as well as any acquired lease. The service adapter carries the immediate
status map alongside the stage; recorded handlers project the latter into receipts.
A completed self-test with failed/inconclusive result did not activate the variant
and must fail the operation rather than claim its requested effect completed.

Independent review found phantom running status after start refusal and loss of the
owner stage if the adapter's broader status read failed after starting. Root fixes
both here: start refusal writes failed status and frees the guard; read the broader
status before starting, then project only the owner's frozen started scalar DTO.
This initial snapshot is a projection of existing status, not another lifecycle
record. Exceptional owner exit also sets failed status before releasing its lease.
The stage is observational and cannot cancel the owner; C2 does not invent a runtime
cancel operation here. Existing operation-lease/Engine shutdown authority remains.

Verification:607 passes79 tests in13 suites, no failures/errors/skips, plus affected
app-services/API PMD. Includes existing activation/profile/baseline/executor tests,
validator coverage and HTTP chat-profile compatibility. New tests use the real
runtime owner, service adapter, recorded handler and SQLite runner; hold lease
cleanup to prove the row stays RUNNING and another attempt is refused. Start lease
refusal no longer leaves phantom running status; a broad status-read failure starts
no effect. Failed/inconclusive self-test yields a failed receipt.609 additionally
proves an unexpected owner exception releases its lease, sets failed status, allows
a later attempt and cannot mutate the frozen started snapshot (all targeted tests
and test PMD pass; exact counts in retained JSON).

Negative606 temporarily restored the old finished-started adapter and fails the
pending-completion assertion during held cleanup. The implementation was restored
before607.603's test constructor access mistake was fixed using the store's public
constructor;604's78-test pass predates the independent review corrections.
Evidence: tmp/c2-2-runtime-completion-{603,604,607}.txt,
tmp/c2-2-runtime-{604,607,609}-xml and corresponding -counts.json;
tmp/c2-2-runtime-negative-606.txt/.xml; tmp/c2-2-runtime-failures-609.txt.
These are local owner/transport proofs; integrated stress, installed crash and live
model inclusion remain required at the coherent C2 boundary. No C2-2 closure.

Runtime completion owner/adapter/handler projections are explicitly registered
(import-invisible domain status paths). Operation-surface610 and guard-resolution611
pass; llmstxt/skills-sync checks pass.609 totals11 tests in5 suites including the
repository's automatically included guards. These successful checks do not replace
the remaining C2 installed/live and integrated obligations.

### Install/repair owner completion

The next bounded producer uses the existing install status snapshot and owner thread,
not status polling or an attempt registry. Return a frozen started snapshot and a
stage completed after pause cleanup, resumable-byte refresh and lease cleanup; only
then snapshot the terminal status and release the running guard. Repair uses the
same owner. Cancellation remains a synchronous request; its install row cancels only
when the owner actually exits. A completed install run may include its existing
hardware/optional-file limitations; completion means that plan attempt terminated,
not that every model is installed. The existing installedFully/package status remains
the authority for that product distinction.

The age-only reapIfStale at5f2285f63 clears running even if its owner thread remains
alive. It could admit a second installer over the same partial files and let the
first completion read the second attempt's status. The simpler correction uses the
existing running ownership bit: only stale unowned status can be reaped. No thread
registry, timeout-to-success, or new persistent liveness marker is needed. Section0
records this supersession of the575 mechanism; the old unowned-status test remains.

Reaper prerequisite: negative612 observes failed instead of running while the owner
guard remains claimed. The correction preserves that guard and the duplicate-start
refusal; the original stale-unowned and fresh-status tests pass unchanged.613 passes
10 tests in6 suites, no failures/errors/skips, plus app-services PMD main/test.
Evidence: tmp/c2-2-install-reaper-negative-612.txt/.xml,
tmp/c2-2-install-reaper-613.txt, tmp/c2-2-install-reaper-613-xml and -counts.json.
This prerequisite is committed separately before install completion wiring.

The install completion adapter now reuses AiInstallService.Attempt directly across
BrainInstallService; unlike broad runtime status it needs no second map/stage carrier.
The existing AiInstallStatus.snapshot() is the deep-copy authority for both initial
and terminal values. The handler projects that frozen DTO without reading mutable
status after starting. isInstallRunning now reads the same running guard used for
admission, so it stays true during cleanup even when terminal domain status is set.

615 passes123 tests in26 suites, no failures/errors/skips, plus app-services/API PMD
and UI test compilation.614 exposed the stale status-based isInstallRunning query
and one unnecessary qualification; both corrected. Negative616 restores the old
finished-started adapter and fails the pending-completion assertion at held cleanup;
restored immediately afterward. Evidence: tmp/c2-2-install-completion-{614,615}.txt,
tmp/c2-2-install-{614,615}-xml, tmp/c2-2-install-615-counts.json,
tmp/c2-2-install-negative-616.txt/.xml. Live download/model success is not claimed by
these deterministic owner/runner fixtures; the existing install truth/limitation,
smoke-cancel, duplicate-start and status suites are included.

Additional required producer seam found at this checkpoint: AiRoutes.java:110-112
and131 still mount direct AiInstallController start/repair and AiRuntimeController
activation, bypassing catalog dispatch. The current checkpoint fixes catalog handler
completion only; C2-2 remains open for acceptance on those direct HTTP producers,
including runtime chatProfile compatibility. C2-3 owns their explicit client key
carrier. The same row/runner must cover each path without nesting duplicate parent
rows; choose the shared service/transport boundary before adapting them. This is
required remaining C2 work, not an exempt legacy path or owner-gated decision.

Independent install review found no surviving source defect and confirmed that the
new owner snapshots cannot alias. Its proof audit requested explicit cleanup-error
and actual-cancellation coverage. Root adds both: an early cancellation request is
honoured before directory creation; the real owner/SQLite row remains RUNNING while
lease cleanup is held, then cancels. Lease-release failure remains exceptional and
cannot report success. This uses the existing cancellation flag and registered
upgrade callback, not another cancellation registry.

Combined local618 passes163 tests in17 suites (dispatch/undo, shared runner,
non-dispatched sealed mutation, scheduled agent, runtime/install owner, MCP identity
and HTTP admission). Evidence: tmp/c2-2-producers-integrated-618.txt,
tmp/c2-2-producers-618-xml and -counts.json.617/619 surface and guard gates pass.
The initial broad stress620 command fails because app-agent has no *Stress* tests;
this is an invocation mismatch, not a failed stress case. Its log and available XML
are retained (the XML directory includes earlier module results; not a fresh suite
claim). Only adapters-lucene and ort-common contain named Stress test classes; select
those actual subjects for the corrected invocation without changing test validation.

621 passes the added real-owner cancellation and cleanup-error cases, existing
smoke-cancel/reaper coverage, PMD and the actual named stress subjects. The Lucene
stress result is reused unchanged from620; ORT stress executes in621. Module counts
and skips are preserved in tmp/c2-2-install-stress-621-counts.json; raw evidence is
tmp/c2-2-install-and-stress-621.txt and tmp/c2-2-install-stress-621-xml. These are
unit/controlled concurrency proofs, not a live model download or installed crash.
Root independently read the XML after the review; no required proof is waived.

621 exact counts: app-services15 tests/7 suites, adapters-lucene1/1, ort-common1/1;
all zero failures/errors/skips. Code revision is the install completion commit carrying
this evidence; the earlier615/618 inputs differ only by the subsequently tested early
cancel branch and its tests. Independent source review covered the intended handler
before the negative injection, which is restored. No install item is marked closed.


### C1 admission lifetime at asynchronous catalog dispatch

Independent source review ofd86d5d40c finds ApiSecurityFilters closes the only Engine
work reference in the HTTP after-filter. The new install/runtime stages retain an
upgrade lease but that is not C1 quota ownership. OperationExecutorImpl must require
EngineAdmissionService through both HeadAssembly composition paths. After durable
acceptance and preflight, attach the caller's exact work (or admit library work),
retain before invoking the handler, pass the admitted context and release that retained
reference from actual OperationExecution completion. The request scope can then close.
Synchronous exceptions including Error release it; asynchronous fatal completion releases
quota without making a successful row. Existing attempts do not execute or retain.

The runner is deliberately not made a second admission owner: scheduled rows are accepted
before fire-time admission, and recovered context has no live work identity. The existing
producer admission contracts remain. C2-3 retries must look up their row before new effect
admission; C2-2 direct HTTP producers still need their own shared runner boundary.
No additional work state machine, persisted liveness marker or registry is introduced.

622 compiles production wiring and passes existing dispatcher/composition tests but the
new UI fixture did not compile because Javalin is not AutoCloseable. Corrected to explicit
stop in finally. Negative623 removes only the retained reference: all four HTTP outcome
arms fail while checking that work survives the response (expected1, actual0), before
completion is supplied. The intended retained-reference implementation is restored.
Logs and XML: tmp/c2-2-dispatch-admission-622.txt,
tmp/c2-2-dispatch-admission-622-services-xml,
tmp/c2-2-dispatch-admission-negative-623.txt and -623-xml.


Restored625 passes116 tests in13 suites: app-services87/8 (unchanged results reused
from622), UI29/5 executed, zero failures/errors/skips, plus PMD.624's four async HTTP
arms passed; its separate refusal fixture accidentally used the validator's explicit
unconstrained-schema sentinel. Replaced the fixture with a constrained schema so its
array input exercises the intended preflight refusal; no validator or existing test
expectation changed. Negative623 remains a valid counterexample to ownership.
Evidence: tmp/c2-2-dispatch-admission-{624,625}.txt, -624-ui-xml,
-625-xml and -625-counts.json. Operation-surface626 and guard-resolution627 pass.

Independent read-only review of the restored diff found no remaining defect, including
existing-attempt bypass, synchronous attach/retain/body failures, cancellation, fatal
completion, newly admitted library context and both composition paths. Root re-read
the negative/positive XML. The quota reference closes on actual effect completion;
terminal-row persistence follows and has its separate C2 failure semantics. HTTP quota
proof does not claim live install/download success; hosted inclusion remains at C2.


### Pack import actual owner completion

The existing pack owner is the next C2-2 producer. Reuse AiPackImportService.Attempt
(started DTO plus actual completion stage) across PackImportService; the dispatcher
adapter never rereads mutable status after starting. The sole running guard stays
held through lease cleanup, terminal snapshot capture and error handling. Registration
or thread-start refusal clears it with PACK_IMPORT_START_FAILED. Unexpected runtime
owner/cleanup failure completes exceptionally with PACK_IMPORT_OWNER_FAILED status;
fatal Error remains exceptional, not a successful operation receipt. A domain failure
that returns normally releases the lease with FAILURE, not the old unconditional
SUCCESS-on-return. Completed pack install means files/settings were installed; existing
restart/activation behavior is unchanged and must not be advertised as convergence.

The same575 age-only reaper defect exists here: reading an old timestamp clears a live
writer's guard. Section0 adopts the existing guard authority; only unowned stale status
may be reclaimed. No additional registry, timeout-to-success, durable progress authority
or cancellation mechanism is added. Row acceptance precedes handler effects, dispatcher
retains C1 work through the returned completion, and direct pack HTTP/keyed recovery
remain required C2 work. Boot resets this progress projection; installed-packs/staging
and operations rows retain their existing separate recovery responsibilities.


Pack628 and restored631 each pass84 tests in8 suites, zero failures/errors/skips,
with app-api/services PMD and UI test compilation. Actual ZIP installation success
is included in the existing pack fixture; the returned owner completion says completed
while the initial snapshot remains running. New controlled tests hold lease cleanup
while the row remains RUNNING and a duplicate is refused, then prove domain failure,
registration refusal, cleanup failure, and stale-owned versus stale-unowned status.
Negative630 restores finished(started) and fails the pending-completion assertion;
the intended handler is restored before631. The temp path in631 is explicit and the
fixture's descriptor matches its handler arguments.

Evidence: tmp/c2-2-pack-completion-{628,631}.txt, tmp/c2-2-pack-{628,631}-xml and
-counts.json; tmp/c2-2-pack-negative-630.txt and -630-xml. Surface629 and guard632 pass.
Independent read-only review found no remaining source defect and independently read
628 and the negative evidence. Root read631 and all counts. Thread.start refusal is
source-reviewed, not deterministically injected; actual lease-registration refusal and
cleanup exceptions are executed. The opposing initial running/terminal failed values
prove the returned snapshots cannot alias; mutations also prove neither aliases owner
status. No live installed distribution/crash or hosted proof is claimed by these tests.

Hosted checks exposed a missed schema integration fixture constructor; separate
commit3e6569a0d corrects it and633 passes integration compilation/PMD. See
[hosted evidence](hosted-ci.md). The failure is not waived and fresh hosted success
remains required. C2-2 and later stage obligations remain open.


### Integrated producer checkpoint 634

At pushed code revision 70e9e577c, `gradlew.bat build pmdAll -PtestParallelism=1
--max-workers=4 --console=plain` passes in 19m30s: 366 tasks (78 executed, 3 from
cache, 285 up-to-date). Captured JUnit results for the 38 test tasks selected by the
actual Gradle output total 9,901 cases/1,624 suites, zero failures/errors, 35 skips.
Unchanged result reuse is explicit per task in the manifest; omitted/no-source tasks
are not counted from stale XML. The source tree stayed clean at 70e9e577c throughout.

Evidence: tmp/c2-2-producers-full-634.txt, -634-xml/ and -634-counts.json; the capture
script is tmp/c2-2-capture-full-634.py. The diagnostic thread dump in
 tmp/c2-2-full-634-test-threads.txt shows the real read-while-write test pacing/indexing
while searches execute; no test was killed, bypassed or weakened. Hosted evidence at
the same revision is [audited separately](hosted-ci.md), including its orphan retry.
This is a coherent producer checkpoint, not C2-2 or stage C2 completion.

### Next work decisions after the producer checkpoint

1. Resolve the newly observed hosted orphan-test identity failure with a nonvacuous
   process-instance witness and prove it, before describing the latest orphan run as
   clean. Preserve the raw failed attempt and successful retry.
2. Move jobs change-feed materialization out of the SQLite commit hook. The official
   [commit-hook contract](https://www.sqlite.org/c3ref/commit_hook.html) forbids even
   SELECT/prepare on that connection until sqlite3_step returns. Preserve current
   statement/chunk transaction boundaries, finish claim bookkeeping before delivery,
   serialize reentrant subscriber delivery and serialize snapshot SQL with the queue
   connection owner. Use the existing queue owner and change stream; do not create a
   second durable completion authority. The change feed remains a projection, not
   proof of final Lucene commit. Recorded ingestion needs its separate committed-unit
   port and scan ownership after this prerequisite.
3. For core.trigger-offline-processing, record the actual bounded coordinator procedure
   (VDU pass and embedding-mode handoff), not immediate virtual-thread dispatch or
   claimed global semantic drain. The current catalog already declares a trigger, while
   canonical UI wording overstates drain. Return explicit processed/pending/blocked
   outcomes after procedure cleanup; unavailability or skipped required processing is
   not success. Carry the caller's admitted EngineContext through the service,
   coordinator and VDU processor; the autonomous sampler supplies its own explicit
   internal context. Background embedding convergence remains its existing pipeline
   responsibility and must be described separately. Use current owners rather than
   inventing a new unbounded queue-drain state machine.

These are autonomous implementation decisions inside C2; no owner input is pending,
and no remaining required acceptance item is waived or transferred to an unspecified lane.


### Queue post-commit projection prerequisite

September12: the existing SqliteJobQueue transaction owner confirms each commit after
JDBC returns. IndexingJobsChangeStream native update/rollback callbacks only collect or
discard provisional row identities; onCommit executes no SQL or subscriber. After commit,
materialize immutable deltas before another committed chunk or reentrant callback can
replace/delete their rows. Delivery waits for the outermost queue exit, after active-claim
bookkeeping. Existing explicit transactions remain atomic; formerly autocommit statements
get one explicit transaction each, preserving the 499-row batch chunk boundaries and
keeping VACUUM outside transactions. This reuses the queue connection/lock and stream;
a second job store, asynchronous dispatcher or completion authority is unnecessary.

Snapshot reads and subscription registration now use the queue connection lock. Internal
committed sequences filter changes already included in reentrant snapshots. The delivery
guard drains nested subscriber writes after the entire earlier batch. A clear-all regression
also exposed SQLite truncate optimization bypassing update hooks; DELETE with WHERE 1
preserves real per-row notifications. The stream is still a projection of queue state;
C2 committed-unit ingestion and D1/D2 durable effect acknowledgements remain separate.

Focused636 passed153 tests/13 suites with zero failures/errors/skips plus main/test PMD.
Initial635 exposed missing clear-all deletes (151/153 passed) and a PMD guard-shape issue;
both fixed without suppressions or weakened assertions. Negative637 put delivery back
inside the native hook but the independent-reader assertion still passed: visibility alone
does not establish the callback boundary. Negative638 had a new-test generic-overload
compile error. Negative639 detected the callback frame inside JNI, whose swallowed test
assertion made the later observed-state assertion fail. The final regression records the
callback frame and asserts outside JNI so the failure states the exact boundary violation.
Evidence is tmp/c2-2-queue-projection-{635,636,640}.txt and corresponding -xml directories,
plus tmp/c2-2-queue-projection-negative-{637,638,639,641}.txt and available -xml captures.

Independent read-only review found no remaining source defect in the queue transaction,
rollback, snapshot, reentrant delivery or claim lifetime diff. It found a separate caller
race in WorkerIngestService.subscribeIndexingJobs: a delta may reach the sink after atomic
subscription returns but before the snapshot frame is sent. Fix that next with a bounded,
fail-closed initial handoff and forced-interleaving tests; never hold the emitter lock while
acquiring the queue lock. This is required work, not a waiver. Hosted/integrated proof for
the new projection changes remains due at the next coherent boundary.


Final negative641 fails explicitly: expected callback-inside-native-hook [false,false],
observed [true,true]. Restored642 passes153 tests/13 suites, zero failures/errors/skips,
plus main/test PMD (main unchanged-input reuse); artifacts are -642.txt, -642-xml and
-642-counts.json. Surface643, docs index/skill-sync checks, canonical links and diff
whitespace pass. No weakening of validation or callback exception suppression was added.
Integrated and named stress checks remain required at the next coherent boundary.


### Snapshot-before-delta handoff correction

September12: WorkerIngestService subscribes atomically to queue state but previously sent
the snapshot after releasing that owner lock, allowing a writer's newer delta to arrive
first and then be overwritten by the stale snapshot. Retain the existing emitter monitor
and buffer at most256 deltas only until the snapshot and accumulated deltas are delivered.
This bounded initial handoff is an intentional projection buffer, distinct from the port's
steady-state BoundedHandoff. Blocking while holding the queue lock is rejected; taking
the emitter lock before subscribeWithSnapshot would invert lock order. Overflow closes
the subscription (including a handle returned after overflow) and fails with UNAVAILABLE
so the existing bridge obtains a fresh snapshot. It never drops and continues a partial
stream. Reentrant sink writes join the pending queue until that queue is drained.
Cancellation and snapshot failure close the subscription and stop buffered delivery.

Forced-interleaving fixture invokes post-snapshot deltas before subscribeWithSnapshot
returns. Initial644 passes19 tests/5 suites plus worker-services main/test PMD. Original
service negative645 fails the first-frame-is-snapshot assertion (1 test/1 failure).
Evidence: tmp/c2-2-snapshot-order-{644,646}.txt, corresponding -xml and -counts.json;
tmp/c2-2-snapshot-order-negative-645.txt and -645-xml. The intended service is restored
before646; two additional cases cover cancellation before the late handle returns and
from the snapshot sink. Hosted/integrated and named stress proof remains required at
the next coherent boundary. The bounded buffer is not a durable operation completion
source and does not change the C2-2 recorded-ingestion acceptance obligation.


Restored646 passes21 tests/5 suites, zero failures/errors/skips plus PMD. Independent
review found no material source defect and requested a buffered-delta sink-failure
regression. Final648 adds that case and overflow during snapshot emission:23 tests/5
suites pass, zero failures/errors/skips, with main/test PMD (main unchanged-input reuse).
Evidence: tmp/c2-2-snapshot-order-648.txt, -648-xml and -648-counts.json. Surface647 and
diff whitespace pass. Root independently inspected the failure and final result XML.


Followup649 adds the review's cross-thread witness: a latch-backed writer callback must
finish between snapshot capture and subscribe return; taking the emitter monitor across
subscribe would deadlock and fail the bounded assertion. The10 snapshot-order tests pass
with zero failures/errors/skips and worker-services test PMD. The same command compiles
and PMD-checks the in-progress orphan fixture separately; it is not an orphan runtime proof.
Evidence: tmp/c2-2-orphan-compile-snapshot-649.txt, tmp/c2-2-snapshot-thread-649-xml and
-counts.json. This test-only followup is committed separately from the orphan changes.


### Orphan proof correction

The inherited A12 Windows proof now prewarms and reuses an exact production parser
instance and binds both parser/native witnesses to creator-published PID/start/executable.
Final local653 passes actual kill/reap, negative654 refutes the old freshness rule and
exercises bounded failure cleanup, and655 restores identical-source cached proof.
Independent review passes after identity-publication and cleanup findings were fixed.
The [hosted record](hosted-ci.md#orphan-witness-correction-during-c2) owns detailed evidence,
retention and the still-required fresh hosted first-attempt/integrated/stress proof.
