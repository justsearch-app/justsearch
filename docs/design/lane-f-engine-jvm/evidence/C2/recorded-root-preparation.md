# C2-2 recorded root preparation

September12 decision; source checked at82e0e185d. This refines C2-2 plan
decisions1,3,5 and7. Implementation and all proof below remain owed.

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
No executable acceptance is claimed by this design record.
