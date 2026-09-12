# C2-2: implementation seams and verification plan

Status: source-grounded at6a4059352 on2026-09-12; batch1 closed at e60e2cb0d; C2-2 implementation and negative proofs in progress.
The root owns all edits/builds. A read-only explorer mapped the seams; decisions
below resolve its returned ambiguities without owner gates.

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
