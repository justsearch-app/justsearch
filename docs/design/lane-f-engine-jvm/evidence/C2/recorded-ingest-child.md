# C2-2: acceptance of a recorded ingest child

September13, implemented at fc679f92a plus this item; local primitive proof complete.
Governing scope: [recorded-root preparation, child identity and proof](recorded-root-preparation.md#child-identity-and-proof).
This is the child acceptance primitive; production reindex/ingest activation, current
generation guards, scan-key propagation and committed-unit completion remain subsequent
parts of C2-2. Merge placement remains F/PR1.

## Mechanism

OperationAttemptRunner.acceptIngestChild takes a runner-issued parent handle and one
RecordedRootPlan.Root. It refuses a forged handle, another runner's handle, or a handle
whose terminal-persistence observation failed. A failed terminal write can leave the
row RUNNING; that row does not restore the lost live capability. Existing normal terminal
handles may still look up an already-accepted child, but cannot create a new one.

SqliteOperationStore uses its existing lock and transaction to read the parent, decode
its strict persisted plan, and find the exact requested root. It selects the stored
root value, preserving generation, collection, flags, patterns and subtree exclusions
without repartitioning. The child identity has exactly mode=ingest-child,
parentOperationKey and preparedInvocation(schema=root-plan.v1,payload=one-root-plan).
Its kind is INGEST and operation reference is null: it is a non-dispatched scan, not
another invocation of a catalog handler. The Java carrier will be its opaque UUIDv7
operation key; database row ids remain local identities.

Lookup matches kind, null operation reference and the full canonical identity. It
returns an existing row, including a terminal one, after checking inherited context
and audit attribution; ambiguous multiple matches refuse. Only insertion requires a
RUNNING parent. INSERT SELECT copies the raw parent context/audit columns in the same
transaction, under a newly minted Engine key. No table, column, relation writer or
terminal writer is added. Both ordinary and child acceptance use the same insert column
declaration. Storage failures remain STORAGE_FAILED; capability, parent or scope
refusals are CHILD_ACCEPTANCE_REFUSED, including synchronous/asynchronous parent failure
receipts. Recovery ownership still validates operation identity/provenance and grants.

The parent effect owner composes every child's durable completion into its own returned
completion stage. The runner does not gain an implicit child-tracking registry. An
existing child never executes through normal start; the INGEST reconciler resumes open
children captured at runner boot. A child first accepted after runner boot starts normally.
The parent/child readiness order must not cause duplicate work or a lost completion.

## Verification

Grammar782 executes25 cases/3 suites with zero failures/errors/skips, passes app-api
PMD and compiles app-observability production. It covers exact partition preservation,
strict outer identity/UUID/digest validation, duplicate/unknown fields, child cardinality,
and payload size bounded independently of the larger enclosing identity. It precedes
the additional exceptional-parent-capability guard. Store/restart/concurrency fixtures
and independent review are covered by the subsequent runs below.

Focused783 passes93 cases/22 suites;784 passes94 after the architecture review fix;
final786 passes96 cases/22 suites with zero failures/errors/skips and selected PMD.
In786 app-observability and app-launcher tests execute; app-api reuses the unchanged
grammar result. The fixtures cover inherited nonempty attribution and scope, forged
and foreign handles, lost parent capability, parallel duplicate acceptance, storage
refusal, terminal reuse, both recovery orders and a child absent at runner boot.
Parents explicitly compose child completion in the fixtures; the runner provides no
implicit aggregation.

Independent review found two defects, both fixed: the lifecycle ArchUnit guard now
includes acceptIngestChild and an unauthorized-call fixture asserts that exact
violation; all bounded OperationStoreException codes now survive parent failure
receipts. Negative785 executes3 cases/2 suites and fails both intended synchronous
and deferred asynchronous assertions with STORAGE_FAILED expected versus
UNCAUGHT_EXCEPTION actual. Final786 passes those unchanged assertions. Review reread
finds no surviving primitive source defect. Broader787 executes741 cases/134 suites with zero failures/errors/skips across
app-api (225), app-observability (432), and app-launcher (84). All three test tasks
execute; selected PMD passes with unchanged-input reuse. Production scan
admission, generation guards and committed-unit completion remain separate required work.

```text
gradlew.bat :modules:app-api:test --tests *OperationDescriptorPreparationTest
  --tests *RecordedRootPlanTest :modules:app-api:pmdMain :modules:app-api:pmdTest
  :modules:app-observability:compileJava
  -PtestParallelism=1 --max-workers=4 --console=plain
```

Windows/Java25; artifacts at worktree tmp/c2-2-child-grammar-782 with .txt,
-counts.json and -xml/ suffixes. Retain through lane acceptance plus30 days and
export before releasing the worktree.

Final focused command (786;784 lacks the receipt fix,783 also lacks launcher PMD):

```text
gradlew.bat :modules:app-api:test --tests *OperationDescriptorPreparationTest
  --tests *RecordedRootPlanTest :modules:app-observability:test
  --tests *OperationChildAcceptanceTest --tests *OperationAttemptRunnerTest
  --tests *SqliteOperationStoreTest :modules:app-launcher:test
  --tests *OperationStoreArchitectureTest :modules:app-api:pmdMain
  :modules:app-api:pmdTest :modules:app-observability:pmdMain
  :modules:app-observability:pmdTest :modules:app-launcher:pmdTest
  -PtestParallelism=1 --max-workers=4 --console=plain
```

Negative785 uses only `:modules:app-observability:test` and
`--tests *OperationChildAcceptanceTest.childStorageRefusalInParent*` with the same
parallelism flags. Broad787 selects the same three module test tasks without filters,
plus the same PMD tasks and flags. Artifacts use `tmp/c2-2-child-783`, `-784`, `-786`,
`tmp/c2-2-child-negative-785`, and `tmp/c2-2-child-modules-787`, each with `.txt`,
`-counts.json`, and `-xml/` suffixes, under the same retention rule.
