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
operation-id inference to the dispatcher or relax the resolver. This also gives the
accepted invocation its designed durable survival/recovery owner. Logs: tmp/1909-stage-live.txt,
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
