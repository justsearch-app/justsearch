# C2 .3c — production ingestion composition proof

Selected 2026-09-20 after the locally verified REST connection. This completes the
composition proof owed by recorded-handler-connection.md; C2 remains open and merge
placement remains F.

The existing RecordedIngestionCoordinatorTest fixture already owns real SQLite
operations/queue stores, runner, resolver, authority, admission and coordinator.
Its real EngineKnowledgeClient/WorkerIngestService test exercises filesystem
enumeration but synthesizes parent acceptance and covers only INGEST. Extend that
fixture for the actual registered IngestTool and ReindexHandler through
OperationExecutorImpl and their production catalogs. Preserve the existing narrow
component tests. Do not introduce another preparation codec, receipt store,
authority or production test switch. app-engine is the allowed composition root;
add app-agent only to its test classpath for the real handler/catalog. No production
dependency edge changes and no index implementation enters the UI.

## Per-item sequence

1. Actual dispatch and receipt path for file ingest, directory ingest, and directory
   reindex with force false/true. At the producer-entry seam assert parent and child
   rows/preparation are already persisted and the same accepted context/plan reaches
   the Java client. Mutate the root/policy suppliers after acceptance, then retry the
   same key while running and after completion: no fresh preparation, producer or
   queue work, same identity. Changed public input refuses key reuse. Real filesystem
   enumeration must admit only frozen targets. Hold document terminal transitions to
   prove enumeration alone cannot complete the parent, then assert sealed revision,
   acknowledgement and committed unit counts. This seam controls terminal queue
   transitions and does not claim actual Lucene write/flush proof.
2. For both operation families, combine the real accepted preparation with stale
   generation/revoked live authorization and assert typed refusal plus no queue claim
   or index effect. An empty sealed receipt is permitted and is not evidence of an
   effect. Reopen the real operations/queue stores and replace the producer binding;
   prove stored preparation/child identity survives, old work exits before successor
   work, and receipt acknowledgement gates terminal publication. Reuse existing
   coordinator recovery/replacement seams and the winning Resume body; recovery must
   never invoke fresh handler preparation.
3. Reconcile focused and full affected suites, PMD/format, architecture, negative
   controls, dependency projections/locks and hosted results. Retain original red
   evidence. Existing real installed supervisor and live/model C2 acceptance remain
   required for the actual index-write boundary and process death; synthetic queue
   completion cannot substitute for them.

Root owns dependency/composition edits, all builds, live stack and integration.
Bounded test drafting may be delegated within this fixture; newly discovered
ownership/lifecycle ambiguity returns before implementation grows. Commit/push each
item and checkpoint WIP at least hourly. No claim of C2 completion follows merely
from this test slice passing.

## First composed failure (2026-09-20)

Live1909 accepted core.ingest-files with kind=operation, then failed in the real
RecordedIngestPlanResolver with Unsupported recorded ingestion producer. New1912
composition tests reproduce that failure for all four cases (30 cases total, four
failures). The production catalogs had never declared their INGEST/REINDEX kinds;
the dispatcher correctly used their default generic policy. Declare the existing
record kinds at those two catalog entries, where classification belongs. Do not add
operation-id inference to the dispatcher or relax the resolver. This selects the
recorded recovery owner; live1929 later shows survival admission still needs the
separate correction described below. Logs: tmp/1909-stage-live.txt,
tmp/1912-composition-red.txt and copied tmp/1912-xml/app-engine; the first1911 command
compiled but selected a nonexistent test method, so it is not runtime evidence.

1914 passes all30 coordinator cases after those two declarations (copied
tmp/1914-xml/app-engine); its PMD failure found nine redundant qualifiers, corrected
without suppressions. The final test observes the exact parent's completion through
the store subscription, registered before dispatch, instead of manually maintaining
the coordinator in a polling loop. Full1917 executes 299 Engine cases in56 suites,
zero failures/errors/skips, including that final event path; copied results are
tmp/1917-xml/app-engine. The build still fails PMD's unused subscription resource
warning, corrected with the same unnamed try-resource pattern already used by the
adjacent queue subscription. Format1920 passes; restored tests/lint follow.

Independent review caught the dependency projector interpreting JVM test-suite
`implementation` syntax as a production edge. Move the new dependency to explicit
top-level `testImplementation` and regenerate: module-deps.md changes only its test
edge list, with no production graph/fan-in change. Module-deps and operation-surface
governance gates pass in1919. Full dependency resolution1905 had no lock drift;
1921 repeats resolution after the syntax correction.

1915 full app-services runs2,941 cases: three failures, three existing skips. Two
assertions still required the superseded string-only collection schema and one still
required generic ingestion policy. Update those exact schema/policy projections to
the already approved nullable collection and INGEST contracts; retain exact equality
checks and all unrelated declarations. Original XML is in tmp/1915-xml/app-services.
Hosted CI35536949678 at30792f547 likewise fails the two schema projections and its
system-integration job (111 cases,33 failures,42 skips); CLA35536948211 passes.
Failure classification/logs are tmp/1918-workflow-health.txt and
tmp/1918-hosted-failures.txt. Independent artifact triage (tmp/1918-hosted-audit)
reduces the33 integration failures to11 tests retried three times, all rejected by
the same missing ingestion kind: four diagnostics methods, one starvation case,
five supervisor scenarios and one operation-resume case. The writer scenario's
outer error differs, but its Engine log records the same resolver failure before
the producer. No hosted reindex trace exists; the four-case local composition proof
supplies direct reindex coverage. The hosted source is the expected synthetic merge
4b999f44128400cb1564052560a6c0a0a7b3f4e5 of PR30792f547, not stale distribution output.
Corrected full local/system and hosted proof remain required. Full1921 dependency
resolution succeeds with no lockfile drift after the explicit test-edge correction.

Live standard-model activation is available: the variant mutation in1907 was refused
by the configured executable lock, but the supported inference mode intent in1908
completed and quick_health confirmed the standard Qwen3.5-9B model online. This is
activation evidence only, not a model query. The old owned run was stopped cleanly
with data preserved before installing the catalog correction; the failed row remains
failed and must not be rewritten into a successful outcome.

## .3c.1 local verification checkpoint

Restored1922 passes: Engine coordinator30 and full app-services2,941 cases execute;
unchanged app-agent692 and UI1,251 cases are reused from the matching Gradle task
inputs. Across the four represented modules there are4,914 cases, six existing skips
and zero failures/errors. All eight selected main/test PMD tasks and three module
Java-format checks pass. Logs, copied XML and counts are tmp/1922-restored-full.txt,
tmp/1922-xml and tmp/1922-counts.json. The preceding1917 full Engine suite supplies
the remaining Engine cases; it is not mislabeled an aggregate green build.
1923 architecture passes37 cases, no skips/failures/errors, including layering,
index-writer ownership and unreferenced methods; tmp/1923-architecture.txt and
tmp/1923-xml/app-launcher. Independent source/evidence review found no remaining
code defect after the test-only dependency correction; its evidence wording request
is resolved above by distinguishing full-test success from aggregate PMD failure.

This closes the bounded local .3c.1 implementation/proof checkpoint. Actual live
indexing, installed process recovery, corrected hosted runs and .3c.2 remain open.
Preflight1926 via this worktree's configured MCP client passes all checks. The
desktop's main-checkout MCP still expects the retired Worker distribution; its
negative preflight is a stale client implementation, not a reason to recreate that
distribution. Use the active worktree client and normal owned dev runner.

## .3c.2 execution split

Keep the existing fixture's stores and owners stable for the first refusal cut
(.3c.2a): use actual registered ingest/reindex preparation, stop and replace its
physical attachment, then change the serving generation or engage the real hard
stop. Assert typed refusal, no successor producer/claim and zero committed units,
with unchanged parent/child identities and accepted preparation. This uses the
existing replacement lifetime and avoids making every fixture field mutable just
to add a refusal matrix.

The following .3c.2b owns reopening both SQLite stores and constructing a new
runner/coordinator, consuming the winning Resume bodies and preserving the real
producer-exit barrier. Establish the old admission/work lifetime explicitly before
choosing which process-owned authorities a store-reopen test retains; attachment
replacement deliberately preserves the pending parent and is not process shutdown.
Keep real process-death and actual index-write proof in the installed/live harness.

## Live indexing and remaining survival defect

Component refusal cut .3c.2a is implemented. Initial1933 runs34 cases with two
failures: trusted SYSTEM_INTERNAL/StructuralAuto is intentionally immune to hard
stop, so that fixture was not revocable. Correct it to real MCP/UNTRUSTED authority:
ingest uses the shared durable operation grant, reindex uses the LOW-risk structural
auto branch. Both prove an Authorized verdict before revocation. Restored1935
passes all34 cases and PMD; copied results are tmp/1935-xml/app-engine. Source/evidence
review finds no substantive defect. Full1936 passes304 cases/57 suites with no skips,
failures or errors and both PMD tasks. Negative1938 disables only the authority's
generation check and still passes because the coordinator independently refuses the
same mismatch. Negative1939 disables both generation-refusal branches: both stale
cases fail awaiting the missing terminal refusal; both hard-stop cases still pass.
Both production files are restored byte-for-byte in a finally block. Restored1940
passes all34 coordinator cases and both PMD tasks. Logs/copied XML/counts are under
tmp/1936-*, tmp/1938-*, tmp/1939-* and tmp/1940-*; the mutation harness is
tmp/1938-negative-refusal.py. This is proof of refusal rather than a claim that the
mutant produced an index effect. None of these directly constructed durable contexts prove HTTP
classification; that missing production boundary remains separately recorded below.

Pushed3898195ab runs in owned dev instance44158f69-0242-4bdd-9a10-6319316df599,
distribution7e8f708999bf517f, standard Qwen3.5-9B model online. Preflight1926 and
health1928 verify fresh artifacts and no foreign stack. Actual reference consumer
1924 indexes five files and finds the cinnamon canary. Read-only SQLite capture1929
shows parent4/child5 COMPLETE, five committed units each, no failures, and walk
receipt revision9 sealed and acknowledged. jseval tier2-eval1925 retrieves five
anchored chunks and the served standard model answers Captain Mortimer Flux exactly,
with no query/anchor errors. This is one real-model smoke query, not a quality suite.
Commands/results: tmp/1924-stage-live.*, tmp/1925-live-query.json,
tmp/1925-live-rag.txt, tmp/1925-live-rag/tier2-eval.json, tmp/1929-live-rows.json and
tmp/1929-live-walks.json. Retained outcome/matching retry/changed-input409 pass1931;
1930 used the wrong GET route and is retained as a harness error, not product proof.

The live rows also expose survival=INTERACTIVE for both parent and child. The kind
correction alone therefore does not complete the declared-kind prerequisite:
RequestEngineContext admits HTTP work as interactive before resolving the operation,
and the dispatcher attaches to that existing owner. The design already requires
durable ingestion/reindex admission, including nested agent calls. Resolve that
classification before the actual producer work is admitted; do not relabel only the
row or attached context, because cancellation reads the owner's original survival.
The .3c.1 internal DURABLE fixture proves its selected composition but cannot prove
the HTTP boundary. Root is investigating this prerequisite before process recovery
proof. The live stack was stopped cleanly with evidence/data preserved; old failed
rows remain failed. No C2 completion or crash-survival claim follows from live1924.

### .3c.2b process-epoch boundary selected on resumption

The two-store reopen test will stop and await the old producer, close its physical
attachment (including subscriptions), then close queue and operations stores in that
order. Only then may a separate fixture load the same authority directory and both
SQLite paths with a new admission controller, runner and coordinator. Recovery must
consume the runner's winning Resume bodies and retain stored preparation/parent/child
identity; no handler preparation is invoked in the successor. Exercise INGEST and
REINDEX and hold document settlement so enumeration alone cannot finish the parent.

This simulates a new process epoch inside one JVM. Do not assert the abandoned old
admission controller has zero active work: physical replacement deliberately retains
its pending durable parent. Actual process death discards that volatile authority.
Adding a production close API merely for the fixture would change this ownership
contract and is unnecessary. The actual shutdown order is visible in EngineRoot.close
and KnowledgeServer's attachment-before-queue close; RecordedIngestionCoordinator's
Attached.close preserves unfinished enumeration when its uncancelled producer exits
CANCELLED. The installed harness separately owes verified OS death and index effects.

### Both-store reopen proof (2026-09-21)

At base935fc723f plus the new coordinator test, focused2017 executes both INGEST
and forced REINDEX cases; integrated coordinator2018 executes all38 cases with
zero failures/errors/skips. Both runs pass app-engine pmdTest and spotlessJavaCheck.
Commands, output and copied XML/counts are tmp/2017* and tmp/2018* (retained through
lane acceptance plus30 days; export before releasing the worktree).

`bothStoresReopenUnderNewOwnersAndResumeTheOriginalPreparedOperation` dispatches
through actual registered IngestTool/ReindexHandler and OperationExecutorImpl.
It persists a queued file under a held producer, requests and observes that
producer's completed exit, then closes attachment, jobs.db and operations.db.
It opens both paths with new store objects, reloads the authority directory and
creates a new runner, coordinator and admission controller. Before attachment,
the persisted queue grants no claim permission. Recovery consumes the winning
Resume bodies once, increments original parent/child attempts, retains their IDs
and preparation, admits a new durable owner and reuses the frozen forced flag.
Enumeration remains nonterminal while the unit is unsettled. A controlled queue
completion then seals/acknowledges the receipt, completes both rows and releases
the new owner. No handler preparation runs in the successor.

This is same-JVM process-epoch simulation with controlled queue settlement, not
an installed Engine death, actual Lucene write or forced client-disconnect proof.
Those separate C2 obligations remain required. No production API or additional
persistent ownership mechanism was introduced for the fixture.

Independent review requested exact child preparation/context preservation in
addition to the parent. Both are now captured before close and compared after
reopen;2021 reruns all38 coordinator cases and PMD/format successfully. Root
independently reread the correction. This closes the bounded same-JVM test item.

Installed baseline2020 is RED (one executed case, no skips), not credited as
recovery proof. It correctly observes Engine death but times out on successor
replay. Raw output/data: tmp/lane-f-takeover/writer-junit-b619aa3c-a946-4547-ae36-c17725534078/.
The successor is running with restartCount1; parent2/child3 remain RUNNING with
attempts1, and the unit remains PROCESSING.2020-walk.json shows enumeration already
COMPLETE, revision3, unsealed. The harness has no watched_roots.json, so its
StructuralAuto out-of-root effect is correctly refused on restart (the canonical
policy regression explicitly distinguishes fresh continuation from recovery).
The refusal path strands the already-enumerated unsettled child because it only
retires pending members while closing an open enumeration. Fix this refusal gap
and seed the successful replay harness with an actual persistent recovery scope;
do not grant arbitrary out-of-root restart authority or weaken the search/retry
assertions. The harness's owned stop succeeds with portsClosed:true.
