# C2-2 recorded root preparation

September12 decision; source checked at82e0e185d. This refines C2-2 plan
decisions1,3,5 and7. The common preparation seam is implemented in the current cut;
typed root plans, child identity and committed ingestion remain owed below.

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
counts are accessible at worktree `tmp/c2-2-preparation-{756,759,761}` and
`tmp/c2-2-preparation-negative-757` prefixes (`.txt`, `-counts.json`, `-xml/`).
Compile-only failures758/760 have logs. Retain through lane acceptance plus30 days,
and export before releasing the worktree. The same suffixes hold for
`tmp/c2-2-preparation-negative-762` and `tmp/c2-2-preparation-modules-763`.
Canonical storage documentation is updated; index regeneration, skill sync and
canonical link checks pass. Full repository/hosted/live validation remains part of
the next integrated producer checkpoint.
