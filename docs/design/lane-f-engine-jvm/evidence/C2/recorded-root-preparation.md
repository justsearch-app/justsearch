# C2-2 recorded root preparation

September12 decision; source checked at82e0e185d. This refines C2-2 plan
decisions1,3,5 and7. The common preparation seam is implemented in the current cut;
the immutable root plan, strict generation observation and atomic root-state preparation
are implemented. Producer integration, child identity and committed ingestion remain owed below.

## Acceptance must contain the plan

OperationExecutorImpl:362-365 currently accepts a digest of public arguments.
ReindexHandler:41-55 then calls RootLifecycleOps:434-442, which reads the current
watched roots after the parent has started. A crash between those steps leaves
an accepted parent with no recoverable scope. A later checkpoint is unsuitable:
OperationRecordHandle and OperationStore define checkpoints as committed effects.

Use one minimal common preparation seam now; leave keyed ingress, approval
freezing and sealed content-bearing preparation in C2-3. A handler-facing immutable
preparation carrier belongs beside OperationHandler in app-agent-api. Its default
adapts existing handlers without retaining public document/prompt content in the
row. A replayable handler supplies its bounded safe replay payload and execution
of that same prepared value. Descriptor construction stays in app-api/app-services;
app-agent-api must not import app-api and create a dependency cycle.

The descriptor's existing identity_json contains a versioned replay payload beside
the public-argument digest and invoke/undo identity. For this item the allowed
payload is roots/paths, generation, collection and scan policy only. It is a
projection of the typed immutable plan, not a second mutable plan. C2-3 will use
this preparation seam for sealed content-bearing invocations and perform keyed
lookup before preparation; it must not serialize those bodies into this safe slot.

Preparation follows current context/provenance/trust checks and precedes parent
acceptance. It performs no scheduling, enqueue, registration or other effect.
Schema validation can be computed first; invalid input or preparation failure
still accepts the generic digest attempt and terminally refuses without running
children. Existing post-acceptance capability/admission refusal semantics remain.
C2-3 owns the further alignment with frozen approval and keyed replay.

September12 implementation refinement: the safe persistence boundary accepts only
`root-plan.v1`, with exactly `generation` and `roots`. Each root contains exactly
`path`, nullable `collection`, boolean `force` and `singleFile`, `excludePatterns`
and `excludedSubtrees`. Paths are absolute and normalized; excluded subtrees must
be strict descendants. Lists have at most1024 entries, generation128 characters,
collection256, paths32768 and patterns4096; the whole payload remains capped at
200000 characters. Unknown fields/schemas and content-shaped nested objects refuse.
This grammar is the persisted projection of the upcoming typed root plan; the
validator owns no mutable scope or second plan. Content-bearing C2-3 preparation
must use its sealed path, never broaden this safe metadata slot.

Expected no-effect refusals use `OperationPreparationRefused` carrying the original
typed retryable `OperationResult`. The generic accepted row records that bounded
failure code before returning it. Receipt and preparation refusal share the existing
96-character outcome-token grammar via `OperationResult.isDurableOutcomeCode`.
Unexpected runtime exceptions still receive generic failed attempts and propagate.
Fatal preparation Error propagates without accepting or terminalizing a row; fatal
errors after runner acceptance retain the runner's restart-reconciliation contract.

The indexing port prepares a typed immutable root plan and accepts that plan for
execution. The recorded handler must not call the old method that rereads roots.
The same rule covers explicit HTTP/agent directory and single-file ingestion;
single-file units also carry an accepted identity beside the Java request.
Periodic maintenance remains explicitly unrecorded. Explicit reindex/reconcile
must not escape through the old syncDirectory shortcut.

September12 source follow-through: do not implement pure preparation by calling
RootLifecycleOps.getWatchedRoots. Its availability projection may schedule a
filesystem refresh. Snapshot only root membership and collection labels through
WatchedRootsState, with the existing state owner synchronizing registration/removal
and this snapshot. Register membership and label together; otherwise a concurrent
snapshot can freeze a new labelled root as default. Copy the current exclusion
patterns once. No second registry or persistent revision is needed for this snapshot;
accepted scope is then the immutable plan. The generation observation remains
separate and must be revalidated before effects. Implementation of this root-state
snapshot is implemented below; the common seam and generation read alone did not supply it.

## Nested roots retain their policies

RootLifecycleOps:300-309 permits nested roots. IngestCollectionPolicy:96-114
selects the deepest containing watched root. Unconditionally collapsing an
ancestor/descendant pair would therefore relabel documents.

Normalize paths with the existing platform Path semantics. Remove exact duplicates
with identical effective policy. Collapse an ancestor/descendant pair only when
collection and effective scan/exclude policy agree. Otherwise retain the distinct
planned child, and exclude that child's subtree from its ancestor's enumeration.
Freeze those subtree boundaries with the plan. Each path then belongs to one
planned child, preserving the deepest-root collection without last-writer races.
Caller-explicit collection assignment still has the existing priority. Tests must
cover distinct nested policies, same-policy collapse and prefix-only sibling paths.

Overlapping independently accepted plans serialize through committed completion,
as the existing C2-2 plan requires. This does not forbid nested root configuration
or change the product's collection authority.

## Capture an authoritative generation, not status

WorkerIngestService:170-174 already retains the composed runtime target path and
whether that ingest runtime is also serving. IndexGenerationManager:678-698 has
a strict current-state predicate, isIdleActiveGeneration, that reads state.json
without cached state, recovery writes or missing-state fallback. Reuse that
authority through a narrow Java-only index-half port to obtain the generation
identifier for the immutable plan. Do not use activeGenerationSupplier:247-261
or a status cache; that supplier is explicitly best effort.

Refuse preparation when the target cannot be established as the current serving
generation. At recorded scan admission, check the persisted target against the
actual service/runtime again before each batch admission and at covering
commit/terminalization. Derive the identifier only after the same captured target
passes the strict check; missing generation-manager/runtime dependencies refuse.
Generation mismatch is an
explicit unresolved attempt, never a silent retarget or successful empty walk.
Checkpoints and terminal success must be backed by committed units for the recorded
target. Recovery eligibility remains C2-8; D1 owns transition-wide leases, journal
carry-forward and activation. A fresh observation is not a lease, and this port
must not claim that D1's transition guarantee is already implemented.

## Child identity and proof

Current implementation and its tests are tracked in [recorded ingest child](recorded-ingest-child.md).

The shared runner exposes a narrow find-or-accept child operation. Under the
existing store lock/transaction, find the child relation by parentOperationKey and
its frozen root/generation/collection/policy identity before inserting. Persist
the relation in identity_json; introduce no relation table, context field or
terminal writer. Restart reconstructs children from the accepted parent plan.
The Java scan carrier is the accepted row's operation_key, not the database-local
numeric id. It replaces the worker-minted UUID with an already-owned identity and
avoids aliasing surviving jobs to a reused integer after operations.db recovery.
Numeric row ids remain the local ordering/capability identity. Verify that database
recovery cannot attach an old scan's committed units to a newly accepted child.

September12 child-acceptance mechanism: the runner accepts an ingest child only from
its own live parent handle and a root contained in that parent's persisted plan.
The store transaction reads the parent, derives the generation from its plan and
canonicalizes a one-root child descriptor containing parentOperationKey. It looks
for the complete child identity before inserting under a fresh UUIDv7 key. A repeated
lookup returns the existing row, including a terminal one. A new child requires a
RUNNING parent; an absent/terminal parent or a root outside the plan refuses before
effect. Child audit attribution and survival inherit the stored parent; live execution
continues with the parent's admitted context, retaining work identity separately.
No client-supplied relation or database-local parent id establishes ownership. The
runner remains the only terminal writer. Reuse the store's existing transaction and
insert primitive, rather than nesting transactions or introducing another child store.

Independent child review identifies a prerequisite before connecting recorded handlers:
At that review, OperationExecutorImpl stamps even prepared parents as generic OPERATION, but
production deliberately owns REINDEX/INGEST and fails unowned interactive operations at
boot. Bring forward the declared catalog recordKind portion of C2-3 for these parents;
do not own every generic operation or infer kind from arbitrary replay JSON. The same
closed vocabulary must serve the policy and durable row, without a duplicate kind enum.
Keyed ingress, sealed content and approval freezing remain C2-3.

The runner validates its private parent capability and owner, not public id/key values.
Child identity has an exact typed grammar embedding the unchanged root-plan.v1 projection;
lookup compares kind, operation reference and canonical identity. Use the exact stored
matching root, preserving existing subtree exclusions without repartitioning. Copy raw
parent context/audit columns inside the transaction and validate them on an existing match;
do not fabricate InvocationProvenance fields the row never stored. Permanent parent/scope
refusal is distinct from storage failure. The parent owner aggregates every child's durable
completion; insertion alone cannot prevent an early parent finish. Existing open children
resume only through C2-8's INGEST reconciler; normal start remains non-reexecuting. Prove
parent-first and child-first recovery ordering, plus new children absent at runner boot.

Required regressions: kill after parent acceptance before child creation; mutate
watched roots after acceptance; refuse storage before any scan; restart without
duplicate child creation; enforce nested-policy partitioning; reject stale or
unreadable generation state; prove explicit reindex avoids syncDirectory; retain
overlap exclusion until covering commits; include single-file ingestion. C2-3
additionally proves terminal keyed retry performs zero preparation and mismatched
public input yields OPERATION_KEY_REUSED before any recorded receipt is returned.

Independent read-only review rejected checkpoint-later and full C2-3 reordering,
and identified the nested-policy and generation-authority gaps resolved above.
Its reread found no fatal contradiction, requiring strict witness derivation and
generation revalidation per batch and covering commit, now explicit here.
## Common seam verification

The common dispatcher seam is a prerequisite only. The root producer, child lookup,
recovery and committed-unit tests above remain owed. Existing handlers use transient
passthrough; a replay-capable handler must implement executePrepared, so the default
cannot silently ignore its frozen scope. The executor passes the same immutable
value, validates that public arguments remain unchanged, and stores only their digest.

Focused756 passes80 cases/8 suites plus affected PMD. Negative757 drops the prepared
descriptor at acceptance: the frozen-root persisted-identity assertion fails; six
conformance cases pass. The original source is restored. Independent review then
finds unenforced safe-payload shape and missing typed refusal; both are corrected as
specified above. Review also catches the receipt-code bound mismatch and rejects
terminalizing fatal preparation errors, now corrected.

758 fails compilation on an ignored Optional return in the new exception guard;
759 passes compilation but two new invalid-input tests used the intentionally
validation-skipped no-argument schema. The fixture now uses a constrained required-path
schema; existing passthrough semantics are preserved. 760 catches the fixture's
incorrect Interface accessor, corrected to result(). Final focused761 passes94 cases/
8 suites, zero failures/errors/skips plus PMD. app-services executes77 cases; app-api's
10 and app-agent-api's7 reuse unchanged successful759 inputs. Independent source
reread finds no remaining substantive ordering, trust or lifetime issue.

Command for focused756/758-761:

```text
gradlew.bat :modules:app-agent-api:test --tests *OperationPreparationTest
  :modules:app-api:test --tests *OperationDescriptorPreparationTest
  :modules:app-services:test --tests *OperationExecutorImplTest
  :modules:app-agent-api:pmdMain :modules:app-agent-api:pmdTest
  :modules:app-api:pmdMain :modules:app-api:pmdTest
  :modules:app-services:pmdMain :modules:app-services:pmdTest
  -PtestParallelism=1 --max-workers=4 --console=plain
```

Allowlist-negative762 removes the projection validator call: six intended failures
in20 cases prove both shape rejection and refusal to persist content through actual
dispatch. Original source bytes are restored. Final independent reread finds no
remaining substantive source defect. Broad affected-module763 passes3491 cases/
557 suites, zero failures/errors and3 skips; all four test tasks execute (236
app-agent-api,209 app-api,420 app-observability,2626 app-services). Affected PMD passes.
It uses the focused command without test filters and additionally
`:modules:app-observability:test`. This is not a full repository or live producer proof.

Base a0cf80c0b plus this preparation diff, Windows/Java25. Logs, XML and task-aware
counts are accessible at worktree `tmp/c2-2-preparation-{756,759,761}*` and
`tmp/c2-2-preparation-negative-757*` prefixes (`.txt`, `-counts.json`, `-xml/`).
Compile-only failures758/760 have logs. Retain through lane acceptance plus30 days,
and export before releasing the worktree. The same suffixes hold for
`tmp/c2-2-preparation-negative-762*` and `tmp/c2-2-preparation-modules-763*`.
Canonical storage documentation is updated; index regeneration, skill sync and
canonical link checks pass. Full repository/hosted/live validation remains part of
the next integrated producer checkpoint.

## Immutable root plan verification

September12: RecordedRootPlan is the single typed immutable scope; its root-plan.v1
JSON is the existing persistence projection. Serialization and decoding share the
strict guard. Input paths normalize before planning; persisted paths must already be
absolute and normalized. Partitioning bounds input at1024 before quadratic ancestor
work. Exact duplicates collapse; conflicting policy or exact-path file/directory kind
refuses. A same-policy file under a directory still collapses. Nearest retained ancestors
preserve A(X)/B(Y)/C(X), with immediate retained children excluded from their parent.

Parent review corrected cubic child lookup, a redundant parsed-plan representation,
serialization-bound mismatch, conflicting duplicate kinds and malformed test fixtures.
Independent final review found no substantive DTO defect. It also verified that the
actual Jackson3.1.0 mapper rejects trailing tokens; a repository regression now pins it.
Focused771 at154742d4a plus this DTO diff executes22 cases/3 suites, zero failures,
errors or skips and passing app-api PMD. This includes12 root-plan,7 descriptor and3
architecture cases. Negative772 chooses the outermost ancestor instead of the nearest:
the alternating-policy regression fails, with one intended failure in15 cases/2 suites.
Exact production bytes are restored. The separately documented generation768 proof
and prior preparation763 proof remain scoped to their tested inputs.

```text
gradlew.bat :modules:app-api:test --tests *RecordedRootPlanTest
  --tests *OperationDescriptorPreparationTest :modules:app-api:pmdMain
  :modules:app-api:pmdTest -PtestParallelism=1 --max-workers=4 --console=plain
```

Negative772 uses only RecordedRootPlanTest and omits PMD. Artifacts are worktree
tmp/c2-2-root-plan-771* and tmp/c2-2-root-plan-negative-772* with .txt, -counts.json,
-xml/ suffixes. The reused capture helper labels the earlier c4fbcb53f base; actual
HEAD is154742d4a plus the unchanged DTO scope and added trailing-token test. Retain
through lane acceptance plus30 days, exporting before worktree release. This proves
the value and safe projection, not recorded producer execution or restart replay.

## Atomic root-state preparation verification

September12: IndexingService.prepareReindexPlan reaches strict generation capture,
then RootLifecycleOps snapshots membership/labels through WatchedRootsState. It copies
exclusion policy once and resolves null/blank collection to default before partitioning.
It does not call the availability projection or schedule scan/watch/delete/persist work.
Watched roots retain directory intent without a filesystem probe; explicit file ingest
is separate, and admission must reject a vanished or changed-kind target later.

Registration, load, membership mutation, removal, clear and persist share the existing
state monitor. Registration publishes membership and collection together. Completion
checks membership and updates state under that same monitor so removal cannot land
between the check and timestamp write. Independent source review found no production
defect or inverse store/state lock order. Review's missing atomic-label and generation-port
proof is then supplied by the final fixtures; parent takes the test diff after two worker
correction rounds.

773 executes40 cases/11 suites plus affected PMD, zero failures/errors/skips.
The final atomic-label fixture waits for a blocked or completed snapshot at a paused
membership insertion; the removal fixture pauses after observing membership true.
Negative774 restores the old registration synchronization and removes the completion
monitor. Three failures in13 cases show actual null collection, two duplicate walks,
and removed-root resurrection. Original production bytes are restored. Final775 passes
40 cases/11 suites with PMD:28 app-services cases execute,12 app-engine cases reuse the
unchanged773 input. It also verifies KnowledgeClient preparation propagates all strict
generation refusals and captures generation once for a successful empty-root plan.

```text
gradlew.bat :modules:app-services:test --tests *RecordedRootPreparationTest
  --tests *RootCompletionMembershipTest --tests *WatchedRootsStateTest
  --tests *RootLifecycleOpsIdempotencyTest --tests *WatchedRootScanCollectionTest
  :modules:app-engine:test --tests *EngineGenerationCaptureTest
  :modules:app-services:pmdMain :modules:app-services:pmdTest
  :modules:app-api:pmdMain :modules:app-engine:pmdTest
  -PtestParallelism=1 --max-workers=4 --console=plain
```

Negative774 selects only the two new app-services fixtures and omits PMD. Base04716d41e
plus snapshot diff, Windows/Java25. Artifacts: worktree tmp/c2-2-root-snapshot-{773,775}*
and tmp/c2-2-root-snapshot-negative-774*, each with .txt, -counts.json, -xml/ suffixes.
Retain through lane acceptance plus30 days, exporting before worktree release.
Hosted run34720523685 passes04716d41e, including the prior preparation/generation/DTO
and Windows-native fixture. It precedes this snapshot; broader snapshot/producer proof
remains required. C2-2 is open.

Broader776 at c6556fa02 passes5189 cases/906 suites, zero failures/errors and11 skips.
All six selected test tasks execute: app-api221, app-services2633, app-engine226,
app-observability420, worker-core342 and worker-services1347. The11 inherited skips
are composition-root guardrails3, BgeM3 vocabulary1, late-chunking encoder5,
adversarial corpus1 and SPLADE tokenizer crash harness1. Affected PMD passes, with
unchanged inputs reused where Gradle reports UP-TO-DATE. Hosted CI34721364763 passes
the same c6556fa02 revision, including Windows-native checks. This closes the broader
snapshot check, not recorded ingestion or C2-2.

776 command: `gradlew.bat :modules:app-api:test :modules:app-services:test
:modules:app-engine:test :modules:app-observability:test :modules:worker-core:test
:modules:worker-services:test`, plus pmdMain/pmdTest for app-api, app-services,
app-engine, worker-core and worker-services, with `-PtestParallelism=1 --max-workers=4
--console=plain`. Retained log, task-aware counts and XML are at worktree
`tmp/c2-2-root-preparation-modules-776*` with `.txt`, `-counts.json`, `-xml/` suffixes,
under the same retention requirement above. An untracked app-agent-api policy test
draft was added while776 ran; that test/source set was not an input to776. No production
source or selected test source changed during the build.

## Declared kind prerequisite

September13: move the existing OperationKind contract from app-api into the
app-agent-api registry package. OperationPolicy declares recordKind with OPERATION
as its compatibility default; both ordinary and prepared dispatcher descriptors use
that declaration, including preparation refusals and undo. The store and policy
share the same enum and persisted wire spelling, extended with MEMORY and NOTE.
Generate the Operation schema from Java and synchronize its packaged copy; no
separate hand-maintained kind list is introduced. This prerequisite does not activate
catalog entries whose recorded owners are still missing.
The full declaration schema is operation.v1; the live UIOperationView/operation-wire
projection remains unchanged. JsonValue/JsonCreator use the persisted lowercase
spelling. The old app-api enum is deleted, so external Java consumers must update
imports rather than relying on a second compatibility enum.

Survival activation remains coupled to the recorded producer integration: the HTTP
filter currently admits INTERACTIVE work before resolving the operation, and
EngineAdmissionController cancels and reports using the admitted work's initial
survival. Replacing only the row context or a dispatcher context view would therefore
lie about actual cancellation. Resolve the declared survival before the producer's
admission (including nested agent dispatch), preserve urgency and attribution, and
prove cancellation/detachment against the real admission owner before activating
reindex/ingest declarations. No mutable survival transition or duplicate work owner
is justified by this classification prerequisite.

The recovery owner must also validate operation identity and attribution before
resuming effects. A contributed catalog can declare a kind; that classification alone
does not authorize the built-in owner to replay an arbitrary operation's payload.
Keep this check with the C2-8 dependency/authority gate when activating producers.

Verification at c6556fa02 plus this prerequisite, Windows/Java25: schema generation777
passes. Focused778 executes120 cases/13 suites without failures/errors/skips and compiles
all moved-type consumers, but fails PMD on18 redundant test qualifiers after imports were
added. Those qualifiers are removed without changing assertions or compiled behavior.
779 passes affected PMD and reuses all120 unchanged tests. Negative780 restores both
hardcoded OPERATION projections: allfour new dispatch cases fail (two persist operation
instead of note; two run zero declared-kind recovery callbacks instead of one). The10-case,
5-suite negative has exactly four failures, no errors/skips. Exact source bytes are restored.
Final781 passes120 cases/13 suites and affected PMD; app-services tests reuse the identical
passing compiled input from cache, the other three tasks are UP-TO-DATE from778.

```text
gradlew.bat :modules:app-agent-api:test --tests *OperationPolicyTest
  :modules:app-api:test --tests *ValueClassWireFormatTest --tests *SubstrateSchemaGenTest
  --tests *OperationDescriptorPreparationTest
  :modules:app-observability:test --tests *NonDispatchedMutationTest --tests *OperationAttemptRunnerTest
  :modules:app-services:test --tests *OperationExecutorImplTest
  :modules:app-agent-api:pmdMain :modules:app-agent-api:pmdTest
  :modules:app-api:pmdMain :modules:app-api:pmdTest
  :modules:app-observability:pmdMain :modules:app-observability:pmdTest
  :modules:app-services:pmdMain :modules:app-services:pmdTest
  -PtestParallelism=1 --max-workers=4 --console=plain
```

778 additionally compiles app-agent/ui/app-launcher test Java and system-tests systemTest
Java. 777 uses `:modules:app-api:updateSchemas`;780 selects only
`:modules:app-services:test --tests *OperationExecutorImplTest.declaredKind*`, with the same
parallelism flags. Both operation.v1 copies match; all eight `regen-all --check` sets pass,
as do canonical-doc link, doc-index, skill-sync and module-dependency checks after regeneration.
The storage doc is not embedded into a shared skill; existing links remain valid.
Logs/counts/XML are `tmp/c2-2-kind-{778,779,781}*` and `tmp/c2-2-kind-negative-780*` with
`.txt`, `-counts.json`, `-xml/` suffixes; schema log is `tmp/c2-2-kind-schema-777.txt`.
Retention is through lane acceptance plus30 days, exporting before worktree release.
This proves classification and its store/recovery connection, not production reindex
activation, per-batch generation guards, committed-unit completion or C2-8 eligibility.
Independent final source review finds no blocking defect in this bounded prerequisite.
It confirms restored policy projection and identifies the operation-identity/attribution
activation guard above. The final constructor-Javadoc correction changes no behavior.
