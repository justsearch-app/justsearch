# C2-9 ingestion authorization recovery contract

## Status and scope

This note records the minimum authorization and composition contract for recovering recorded `INGEST` work. It is design evidence under C2-9. It does not move a stage, implement the recovery owner, or make an accepted operation row an authorization authority.

The source review was performed at revision `128a0c945e60fe1ba3d1afb4d30869d8242ea6d9` in the Windows/PowerShell worktree `F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify`. The tree also contained unrelated in-progress app-api value changes. No build or test was run for this docs-only review.

## Historical investigation at128a0c945 (superseded by the implementation records below)

`RequestEngineContext` copies `X-JustSearch-Grant-Reference` from the request into `EngineContext` (`modules/ui/src/main/java/io/justsearch/ui/api/RequestEngineContext.java:37-39`). `EngineContext` explicitly defines `clientKind` and `grantReference` as attribution rather than authorization and requires a resumed operation to resolve authority again (`modules/core/src/main/java/io/justsearch/core/context/EngineContext.java:14-17`). Source identity is separately checked against the registered transport through `EngineProvenance` (`modules/app-services/src/main/java/io/justsearch/app/services/intent/EngineProvenance.java:61-69`). A caller-provided grant reference therefore cannot authorize admission or recovery.

The dispatcher freezes the request with its original context before gate evaluation and accepts that unchanged request after the gate succeeds (`modules/app-services/src/main/java/io/justsearch/app/services/registry/executor/OperationExecutorImpl.java:380-394`, `modules/app-services/src/main/java/io/justsearch/app/services/registry/executor/OperationExecutorImpl.java:414-439`, `modules/app-services/src/main/java/io/justsearch/app/services/registry/executor/OperationExecutorImpl.java:467-475`). The gate can succeed through the structural evaluator, a durable grant, or a one-time capsule, but it returns no typed record of which basis succeeded (`modules/app-services/src/main/java/io/justsearch/app/services/registry/executor/OperationExecutorImpl.java:847-918`). `DurableGrantStore.isAllowed` returns only a Boolean after testing operation and family grants (`modules/app-services/src/main/java/io/justsearch/app/services/intent/DurableGrantStore.java:185-198`), so it loses the exact selected entry. Capsules use a process-local key and cannot survive restart (`modules/app-services/src/main/java/io/justsearch/app/services/intent/ConsentCapsuleService.java:31-37`). The current accepted row consequently cannot distinguish structural `AUTO`, an operation grant, a family grant, or a consumed ephemeral capsule. Recovery must not infer a permanent grant from that row or from `grantReference`.

The structural decision is already owned by one evaluator. The catalog classifies ingest as medium risk, inline, in the file-operations family (`modules/app-agent/src/main/java/io/justsearch/agent/tools/AgentToolsOperationCatalog.java:334-360`). `CoreTrustEvaluator` can permit trusted medium-risk inline work as `AUTO` (`modules/app-services/src/main/java/io/justsearch/app/services/intent/CoreTrustEvaluator.java:79-98`), while `IntentGateEvaluator` combines the source catalog, trust evaluation, and global hard stop (`modules/app-services/src/main/java/io/justsearch/app/services/intent/IntentGateEvaluator.java:17-36`, `modules/app-services/src/main/java/io/justsearch/app/services/intent/IntentGateEvaluator.java:74-85`). Requiring a durable grant for every resumed ingest would therefore reject work that was legitimately admitted through structural `AUTO`.

The existing indexed-root grant scope is insufficient for recorded ingest recovery. The default preparation check refuses coverage (`modules/app-services/src/main/java/io/justsearch/app/services/intent/DurableGrantScope.java:44-48`); `IndexedRootGrantScope` is built unbound and later supplied a roots view (`modules/app-services/src/main/java/io/justsearch/app/services/intent/IndexedRootGrantScope.java:50-71`), and its current decision examines raw arguments and refuses when roots are unavailable (`modules/app-services/src/main/java/io/justsearch/app/services/intent/IndexedRootGrantScope.java:73-110`). Prepared dispatch calls `coversPreparation` when a preparation nonce is present (`modules/app-services/src/main/java/io/justsearch/app/services/registry/executor/OperationExecutorImpl.java:876-882`). C2-9 therefore needs a strict preparation check over the frozen `RecordedRootPlan` metadata and current watched-root membership, with no raw-argument fallback.

## Selected server-built authorization basis

Successful gate evaluation must produce a typed, server-built authorization basis before the operation is accepted. The operation owner persists that basis atomically with acceptance. The basis is a locator and evidence for later revalidation; it is never authority by itself.

| Basis | Accepted meaning | Recovery rule |
| --- | --- | --- |
| `StructuralAuto` | The shared structural evaluator returned `AUTO` for the registered transport/source and operation at admission. | Re-run the same shared `IntentGateEvaluator` and the same `GlobalHardStop` against the stored registered context, current operation risk, and frozen preparation scope. Resume only if the current result still permits structural execution. |
| `DurableGrantReference` | The gate selected one exact durable entry. The typed reference includes `OPERATION` or `FAMILY`, its exact target, and source tier, plus any operation binding required by the existing store contract. | Resolve that same entry in the sole `DurableGrantStore`; require that it still exists, covers the current risk and registered context, and covers the frozen root plan. Revocation of the selected entry fails recovery even if a different operation or family grant would now match. A newly chosen grant requires a new authorization attempt rather than silent substitution. |
| `EphemeralCapsule` | Admission consumed a one-time capsule issued by the process-local capsule authority. | Refuse recovery after process restart. Do not persist the capsule token or signing key, mint a durable grant, or treat the accepted row as replacement authority. |

Rows that predate the typed basis, contain a malformed basis, or contain contradictory transport/source provenance fail closed. The recovery owner must not guess which branch originally admitted them.

The durable store already defines persistent allow-always scopes for an operation-and-tier or family-and-tier (`modules/app-services/src/main/java/io/justsearch/app/services/intent/DurableGrantStore.java:28-58`). Its recovery-facing API must resolve and return the exact selected reference rather than collapse operation and family checks into one Boolean. Source-tier validation remains the existing `EngineProvenance` check.

The smallest durable representation is the existing nullable `operations.grant_ref` column (`modules/app-observability/src/main/java/io/justsearch/app/observability/operations/OperationSchema.java:11-23`). At acceptance, this field must stop carrying the untrusted request header and instead carry a compact, versioned encoding of exactly one server-built basis: structural `AUTO`, exact operation/family grant key, or ephemeral-capsule marker. The encoding is non-secret, must satisfy `EngineContext`'s identifier grammar and 256-character ceiling (`modules/core/src/main/java/io/justsearch/core/context/EngineContext.java:42-52`), and must fail closed on an unknown version or malformed value. A separate schema column is unnecessary unless a later requirement needs to preserve the discarded caller header as distinct attribution.

Prepared acceptance needs a deliberate one-field transition. The preparation envelope freezes the original unattached context, including the caller-supplied grant reference (`modules/app-services/src/main/java/io/justsearch/app/services/registry/executor/PreparedInvocationCodec.java:61-69`), and a retry restores that frozen context (`modules/app-services/src/main/java/io/justsearch/app/services/registry/executor/OperationExecutorImpl.java:446-459`). After the gate chooses its typed basis, the acceptance request replaces only `grantReference`; `SqliteOperationStore` then inserts that value and copies/deletes the nonce-bound preparation within the same transaction (`modules/app-observability/src/main/java/io/justsearch/app/observability/operations/SqliteOperationStore.java:321-380`). Recovery's envelope/row check is currently exact (`modules/app-services/src/main/java/io/justsearch/app/services/registry/executor/RecordedIngestPlanResolver.java:23-36`); it must be narrowed to require equality of every other `EngineContext` field while requiring the row's differing `grantReference` to decode as a valid server-built basis. It must not broadly ignore context differences or accept the envelope's header as a basis.

The gate on a prepared retry must evaluate the frozen plan context, not a fresh retry context. The current call still passes the raw dispatch context (`modules/app-services/src/main/java/io/justsearch/app/services/registry/executor/OperationExecutorImpl.java:380-393`) even though `persistedPlan` has restored the original context. The implementation must use the stable plan request for authorization and then apply the server basis to that same context before `acceptPrepared`. Existing-key receipt lookup compares only the key and canonical operation descriptor (`modules/app-observability/src/main/java/io/justsearch/app/observability/operations/SqliteOperationStore.java:531-567`), so the replacement does not change replay identity. Derived ingest children already copy `grant_ref` and the remaining parent attribution atomically (`modules/app-observability/src/main/java/io/justsearch/app/observability/operations/SqliteOperationStore.java:454-513`); they must inherit the validated typed basis without reparsing caller input.

## Sole authority composition and roots carrier

Composition must create one shared authorization-authorities record before the worker fork. It carries the sole `DurableGrantStore`, `ConsentCapsuleService`, `IndexedRootGrantScope`, `GlobalHardStop`, shared `IntentGateEvaluator`, and sole preloaded watched-roots carrier. The record is passed both to the generation-ready recovery seam and through `OperationSubstrateInit`/`HeadAssembly`; it does not create a second bootstrap owner.

Today the watched-root map, store, and state are fields of `KnowledgeClient` (`modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeClient.java:155-164`). Its constructor creates the store and state (`modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeClient.java:222-227`), and later builds `RootLifecycleOps`, migrates the store, and loads persisted roots (`modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeClient.java:288-295`). The store loader skips roots that no longer exist and otherwise propagates load failure (`modules/app-services/src/main/java/io/justsearch/app/services/worker/WatchedRootsStore.java:119-169`). This state must be extracted into one preloaded carrier created before the worker fork, preserving migration-then-load order and failure behavior. The same carrier is injected into `KnowledgeClient`/`RootLifecycleOps` and bound once to the sole `IndexedRootGrantScope`. No second file reader, roots map, or root-membership authority is permitted. Corruption or inability to preload the carrier prevents recovery polling; an absent or unresolvable root fails containment closed.

The present order starts `KnowledgeServer` before `EngineKnowledgeClient` exists (`modules/app-engine/src/main/java/io/justsearch/app/engine/EngineRoot.java:223-260`). `HeadlessApp` creates the root, starts the asynchronous worker path, and only then builds `HeadAssembly` (`modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java:1064-1142`). The current grant scope is bound even later during agent-tool registration (`modules/app-services/src/main/java/io/justsearch/app/services/HeadAssembly.java:891-906`). The shared record must instead be created beside the operation store before the asynchronous fork at `modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java:1064-1115`, passed into `EngineRoot.forProcess`, and reused by the primary `HeadAssembly`.

`OperationSubstrateInit` currently creates the catalog/evaluator/capsule, hard stop/store, and scope/evaluator in separate local steps (`modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/OperationSubstrateInit.java:159-163`, `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/OperationSubstrateInit.java:191-197`, `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/OperationSubstrateInit.java:269-277`). Those sites must consume the prebuilt record while preserving the existing event-sink and hard-stop revocation wiring (`modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/OperationSubstrateInit.java:220-229`). `OperationExecutorImpl` must receive the same evaluator instance instead of constructing another. The capsule singleton may be composed early for later intent ceremonies, but recovery never converts it into restart authority.

The dependency direction remains acyclic. `worker-services` already depends on `app-api` (`modules/worker-services/build.gradle.kts:12`), while `app-engine` depends on `app-api` and core and implements the composition over app-services, worker-services, and indexer-worker (`modules/app-engine/build.gradle.kts:23-31`). The generation-ready seam should therefore be a narrow app-api callback or typed decision implemented by app-engine. Worker modules must not import app-services, and app-services must not import engine or worker modules.

## Recovery sequencing and revocation boundary

The generation-ready callback first revalidates each interrupted recorded ingest from its server-built basis and frozen `RecordedRootPlan`. Only authorized operation keys enter the runtime permit set. The same permit predicate gates both `PENDING` polling and `PROCESSING` recovery/reaping. No recovered job may be released before generation compatibility, capability checks, authorization, and current root containment have succeeded. Fresh-attempt permission follows the separate continuation rules below; administrative stopped-orphan accounting never grants execution permission.

Authorization is revalidated at each recorded-unit claim boundary, or through an equivalent invalidation path tied to the same sole grant and roots authorities. Revoking the selected durable grant, removing a root that supplies required containment, changing structural AUTO policy, or an applicable hard stop prevents the next unclaimed unit. Fresh AUTO/capsule consent is not derived from watched-root membership; restart containment applies to every basis. A unit that has already passed the check and been claimed has crossed its admission boundary; revocation does not retroactively cancel an effect already in progress. This boundary adds no progress counter or second authorization authority.

`OperationAttemptRunner.reconcile` is an owner-readiness reconciliation entry point (`modules/app-api/src/main/java/io/justsearch/app/api/operations/OperationAttemptRunner.java:105-108`), and its implementation scans the constructor-captured interrupted rows once per explicit call (`modules/app-observability/src/main/java/io/justsearch/app/observability/operations/OperationAttemptRunnerImpl.java:456-480`; snapshot capture at `modules/app-observability/src/main/java/io/justsearch/app/observability/operations/OperationAttemptRunnerImpl.java:44-75`). A generation/capability `Wait` therefore keeps units fenced and requires an explicit later reconciliation pass; it must not be documented as self-waking.

Required failure behavior is:

- Invalid or contradictory registered transport/source context fails the operation and releases no job.
- A missing or revoked exact durable-grant reference fails authority without substituting another matching grant.
- An ephemeral-capsule basis refuses restart recovery.
- A structural basis that is no longer `AUTO`, or is blocked by the hard stop, refuses recovery.
- A corrupt grant store or watched-roots store, or failure to preload either sole authority, prevents polling/startup rather than falling back to empty or permissive state.
- A malformed or unsupported recorded preparation, or any frozen root outside current membership, fails closed with the operation owner's typed failure outcome.
- Generation or capability unavailability returns `Wait`, keeps jobs fenced, and is retried only by an explicit reconciliation pass.

## Notification after the jobs lock

`IndexingJobsChangeStream.drainCommitted` invokes subscribers synchronously (`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue/IndexingJobsChangeStream.java:263-279`). `SqliteJobQueue` currently calls it before releasing the jobs-owner lock (`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue/SqliteJobQueue.java:1243-1265`). The operations checkpoint path must not subscribe directly and perform operation-store I/O under that lock.

Walk mutations instead collect affected operation keys or admission revisions inside the successful jobs transaction. Only a committed transaction promotes that notification. The outermost queue call releases the jobs lock, then notifies the vertical owner. The owner rereads the latest durable jobs receipt and checkpoints the operation row. Coalescing and reordering are safe because the receipt is reread; observer failure cannot roll back the jobs transaction, and existing periodic/shutdown maintenance retries from durable state.

## Required regression proof

The implementation is not ready until focused tests establish all of the following:

1. A forged request `grantReference` cannot authorize either initial admission or recovery.
2. Each successful gate branch stamps exactly one typed server-built basis atomically with operation acceptance; a failed acceptance leaves no usable basis.
3. `StructuralAuto` resumes only while the same shared evaluator still returns `AUTO`; a hard stop or policy/source change blocks it.
4. Operation and family grants persist distinct exact references. Revoking the selected reference blocks recovery even when another grant matches, while the same unrevoked reference survives store reopen.
5. A capsule-authorized accepted operation refuses process-restart recovery; no capsule token/key is persisted and no durable grant row is minted.
6. Prepared coverage parses the frozen `RecordedRootPlan`, accepts only roots currently covered by the sole roots carrier, and rejects raw-argument fallback, argument/plan mismatch, out-of-root paths, symlink escapes, and unresolvable paths.
7. The roots carrier is loaded before the recovery callback and is object-identical to the state later used by `KnowledgeClient`, `RootLifecycleOps`, and `IndexedRootGrantScope`. Root add/remove updates affect the next claim, and corrupt preload prevents polling.
8. No `PENDING` poll, `PROCESSING` recovery, or reaper action occurs before the generation-ready authorization callback completes.
9. Revocation after unit N prevents claim N+1; a separately controlled test pins the documented behavior of a unit already claimed when revocation occurs.
10. A `Wait` result does not wake through the one-shot runner by itself; an explicit later reconcile can admit work only after all conditions pass.
11. No operation-store call occurs while the jobs lock is held. Post-commit notification runs after unlock, failed transactions emit nothing, and observer failure, coalescing, or reordering is repaired by rereading the durable receipt during maintenance.
12. Production wiring supplies one durable store, roots carrier, scope, hard stop, and evaluator instance to both recovery and later request handling; module-boundary tests reject reverse dependencies.

## Missing proof

Shared pre-fork authority and the sole roots carrier are locally verified in C2-9b.1 below. Strict recorded-root scope is locally and hosted verified in C2-9b.2a. The pure typed decision is implemented in C2-9b.2b; its current verification is recorded below. Per-claim fencing and the generation-ready recovery callback remain unimplemented. After-lock queue notification is implemented and verified separately in [walk-notifications-retention.md](walk-notifications-retention.md). The C2-9a.1 basis/selection/binding cut is locally verified below; it does not establish live recovery, reboot permission or stage completion.


## C2-9a acceptance prerequisite (2026-09-14)

Implement the already-selected typed basis and exact grant lookup before activating the real
C2-8d.3 producer. This is a prerequisite ordering within C2, with no stage/merge movement.
Keep `operations.grant_ref` as the durable slot and preserve all other prepared context axes.

The pure app-api basis encoding is `jsa1:auto`, `jsa1:capsule`, or
`jsa1:op:<SOURCE_TIER>:<base64url-UTF8-target>` /
`jsa1:family:<SOURCE_TIER>:<base64url-UTF8-target>`. Reuse SourceTier; do not create a trust
vocabulary. Encoding is unpadded, canonical, strict UTF-8, bounded to 256 characters, and
rejects unknown variants/versions, blank/control-bearing targets and noncanonical forms.
This is a projection of the exact existing grant key, not another authority or grant store.
Operation and family references are distinct and never substitute for each other on replay.
Oversized keys refuse acceptance; truncation or hashing away the exact target is forbidden.

C2-9a supplies the codec, exact existing-entry selection/revalidation and gate-to-acceptance
stamp, with prepared-context one-field validation. Pre-fork shared authorities, roots carrier,
per-claim permits, generation-ready callback and live recovery remain C2-9b and must be
connected before the overall C2-8/9 acceptance is claimed.

The stamping cut applies to every dispatcher acceptance that passes the shared
lattice, including AUTO and capsules, so `grant_ref` has one meaning across its
server-gated rows. Ungated legacy/test constructors clear caller grant references and do not invent a basis; their
rows cannot pass the recorded-ingestion recovery binding validator. A prepared
retry evaluates the frozen context and provenance, while the existing public
argument digest check still precedes the gate. Only the grant reference changes;
all other envelope axes remain exact. This adds no operations column.

### Per-item cut and review correction

C2-9a.1 supplies the typed evidence/selection/binding prerequisite only. Independent
review found an inherited durability defect: grant/revoke currently changes live
sets before the file replacement succeeds. C2-9a.2 must serialize one candidate
snapshot, persist it before publication, and emit success events only after that
commit. The existing grant keys and file remain the sole authority. This is smaller
than a new journal/store or a permanent recovery marker. On a failed write the
mutation throws and the previously committed view remains both in memory and on
disk; no successful revoke is reported. Recovery activation remains blocked until
this correction and failure/reopen regressions pass.

The earlier cross-client prepared-dispatch fixture treated the fresh trusted
retry as sufficient to run a frozen untrusted invocation. That expectation is
superseded by this design's explicit frozen-context gate rule: the retry must
supply bound approval, while attribution and work-handle isolation remain pinned.


### C2-9a.1 local verification (2026-09-14)

Base `7777be3fd135b9119a60ab89f059b0990eb0c2f7` plus the basis/selection/binding
commit diff, Windows x64, root-owned Gradle with no simultaneous source edits.
The codec is strict/canonical; selection preserves the exact operation/family key;
the dispatcher clears every caller reference and stamps successful shared-gate
results at acceptance. Prepared retries use frozen context/provenance. Resolver
validation permits only the stamped field difference. The original preparation
remains byte-equivalent on injected SQLite acceptance failure and no row/effect
appears. No capsule secret is included in the stamp.

| Run | Result |
| --- | --- |
|1627|Compile rejected two redundant record-constructor assignments; corrected without suppressions. Tests did not execute.|
|1628|158 cases, no skips/failures; PMD rejected one redundant qualified test annotation.|
|1629|157 cases, no skips/failures; app-services executed, app-api reused up-to-date; PMD/format passed. Removed the empty-context construction case because it tested the wrong rejection boundary.|
|1630|23 cases, no skips/failures; acceptance-failure regression and prepared dispatch suite executed; PMD/format passed.|
|1631|Full app-api and app-services suites both executed:3,122 cases/476 suites, three existing skips, no failures/errors; PMD/format passed.|
|1632|Negative control removed stamps, exact selected-entry checks, frozen gate context and resolver checks:20 cases,11 expected failures, no skips/errors.|
|1633|All three production files restored byte-for-byte to1631;20 targeted cases executed, no skips/failures/errors; PMD/format passed.|

The negative proves failure for the operation/family/capsule stamps, ungated forged
reference, cross-client trust upgrade, revoked selected-entry substitution, row
context mismatch and missing/malformed evidence. AUTO retains the forged AUTO
value in that mutation and is not claimed as a discriminating negative by itself.
The existing unaffected provenance/executor checks continue to pass.

`tmp/1633-gates.txt` records operation-surface, execution-surface,
register-guard-resolution and store-recoverability passes. Canonical documentation
regeneration/link/config checks pass in `tmp/1633-docs.txt`.
All logs, counts and preserved XML are accessible under
`F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/1627*` through `tmp/1633*`;
retain through final lane reconciliation plus30 days, at least2026-10-14.
Full final-lane/platform/live proof remains required. C2-9a.2 persistence correction
and C2-9b integration remain open; this per-item commit does not activate recovery.

Independent refute review (`/root/walk_closure_refute`) reread the restored production
source and1631/1633 evidence and found no further C2-9a.1 blocker. Its grant-write
failure finding remains explicitly owned by the next C2-9a.2 cut. Hosted7777 proves
only the predecessor queue work, not this diff.


### C2-9a.2 publication decision (2026-09-14)

Use one volatile immutable set of the existing `DurableGrant` keys, replacing the
two mutable per-kind key sets. One mutation lock serializes candidate construction
and strict forced atomic file replacement; only successful replacement publishes
the new set. Readers capture one committed snapshot; lifecycle callbacks run after
publication and outside the lock. This removes the two private key projections
rather than adding a journal, transaction manager or another authority. A failed
mutation throws and leaves the prior committed state; callers must not report a
successful revoke. Strict replacement refuses unsupported atomic rename instead
of falling back to a partial overwrite. This does not claim physical power-loss
parent-directory durability beyond AtomicFileWrites' documented contract.


Review checked the existing audit fan-in (`OperationSubstrateInit` binds only
`ActionLedgerChangeRegistry.broadcastActionEvent`). No Grant event consumer
reconstructs current authorization from callback delivery order. The immutable
committed set/file remains the sole authority; callbacks may interleave after
concurrent commits. A new event-sequencing queue is therefore not part of this fix.


### C2-9a.2 local verification (2026-09-14)

Implemented at base `43fa7b3ace2f32a8a23715dd073c55f93c477a39` plus this
publication diff, Windows x64, root-owned Gradle. The previously open live-before-disk
defect is corrected; C2-9b integration remains open.

- 1634 executed145 focused cases/7 suites, no failures/errors/skips, PMD and format
pass. Real TempDir filesystem failures preserve committed memory and saved/reopened
file for issuance and operation/family/non-user revocation, emit no success event,
and permit successful restore/retry. Concurrent mixed writers retain all grants
on reopen. Invalid loaded/issued keys fail closed.
- 1635 deliberately published before persistence:8 executed cases, exactly two
expected failures in failed-grant and failed-revoke state assertions.
- 1636 restored the production file byte-for-byte to1634 and executed the full
app-services suite:2,901 cases/430 suites, three existing skips, no failures/errors;
PMD/format passed. Root independently verified byte equality after the run.
- Canonical regeneration/link/config checks and store-recoverability pass in
`tmp/1636-docs.txt` and `tmp/1636-store-gate.txt`.

Independent reviewer reread production, failure/reopen tests and1634/1635 evidence:
no remaining blocker in this correction. A proposed audit sequencing concern was
refuted against actual consumers and did not justify another queue. No hosted
proof for this dirty cut is claimed. Logs/counts/XML remain accessible under
`F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/1634*` through `tmp/1636*`,
retained through final lane reconciliation plus30 days, at least2026-10-14.


## C2-9b implementation sequence (2026-09-14)

1. **C2-9b.1 preloaded shared ownership.** Promote the existing WatchedRootsState
   to the preloaded carrier instead of wrapping its map/store/state in another
   registry. Its factory migrates then loads before any executor/server start,
   using the process's configured data directory. KnowledgeClient and
   RootLifecycleOps keep the exact same map/state; expose only a copied path view
   outside the worker package. Legacy migration copy failure is fatal; failure to
   rename an already-copied legacy source remains harmless. Retain current missing
   path filtering (it only narrows authorization); do not authorize detached roots.
   Compose a bootstrap OperationAuthority containing that carrier plus the one
   store/capsule/hard-stop/scope/evaluator/catalog/trust objects before HeadlessApp's
   async fork. Pass it through EngineRoot and primary HeadAssembly/SubstratePhase;
   OperationSubstrateInit consumes it and only attaches existing audit sinks.
   Explicit test-only boot factories use in-memory state. Do not move the whole
   HeadAssembly before the fork or create another evaluator: the evaluator needs
   only existing trust/source catalogs, not the later capability resolver.
2. **C2-9b.2 frozen scope and recovery decision.** Add strict frozen-plan coverage,
   shared-basis revalidation and typed refusal/wait decisions, with no public-input
   fallback. Keep envelope identity validation in its existing owner. Record the
   exact argument/plan validation split before implementing it.
3. **C2-9b.3 activation fence.** Connect the generation-ready callback and per-claim
   permit/revocation checks before any recorded poll/reaper/processing recovery;
   connect the producer and explicit reconciliation cadence. This is required
   activation work, not a deferral after C2.

C2-9b.1 acceptance requires object identity across bootstrap/client/root lifecycle
and scope, corruption/migration failure before worker startup, root add/remove
visibility, no duplicate load and no changed trust boundaries. Focused tests plus
app-services/app-engine/UI compilation and appropriate integration precede its
per-item checkpoint. Later items still require actual live/restart proof.


### C2-9b.1 local verification (2026-09-14)

Base `27246acd6cd6278bf73c0170c78bc834805b55c1` plus this shared-authority diff,
Windows x64, root-owned Gradle. `OperationAuthority.load(configPhase.dataDir())`
now precedes construction of the process EngineRoot and the asynchronous bootstrap
executor/fork. API assembly receives `engineRoot.authority()`; the substrate
reuses its evaluator, catalogs, capsule service, hard stop, grants and roots scope.
KnowledgeClient receives the same WatchedRootsState/map, including RootLifecycleOps
and SyncOps. Its construction no longer migrates or reloads durable roots.

- 1637 executed120 cases with no failures; PMD rejected a redundant qualified test
  name. 1638 executed126 services cases without failure but Engine test compilation
  rejected a fixture imported from the wrong module. Both defects were corrected.
- 1639 executed143 cases/13 suites with no failures/errors/skips; services and Engine
  tests, UI main/test compilation, services/Engine PMD and formatting passed. Tests
  cover synchronous roots hydration, isolated migration-copy failure/preservation,
  corrupt roots/grants, configured grant path, shared object identities/hard-stop
  effects, and copied membership snapshots reflecting add/remove changes.
- 1640 negative-control compilation failed because the injected reload called the
  wrong method; it provides no behavioral proof. Corrected1641 executed17 cases
  with exactly three expected failures: duplicate capsules violate identity,
  swallowed migration-copy failure violates fatal preload, and client reload reads
  the deliberately corrupted file changed after preload.
- The three production files were restored byte-for-byte from the1639 snapshots
  before integrated1642. The actual Engine client regression uses a mocked index
  server and real roots file/state; it proves reuse and no second read, not physical
  process startup. Pre-fork ordering and failure cleanup also require the recorded
  production call-path review. Full live/restart recovery remains C2-9b.3.

Canonical regeneration/link/config checks and the three operation/execution gates
plus store-recoverability pass in `tmp/1642-docs.txt` and `tmp/1642-gates.txt`.
Evidence logs/counts/XML are accessible under
`F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/1637*` through `tmp/1642*`;
retain through final lane reconciliation plus30 days, at least2026-10-14.


Independent call-path refute confirmed the canonical Headless path: preload at
HeadlessApp:1086, EngineRoot construction at:1087, asynchronous fork at:1117;
buildApi passes the same authority at:455-459. The failure path at:1311-1357 and
:1381-1384 closes operations and the instance lock when preload throws before
processRoot/Head assignment. Legacy EngineRoot process overloads have no current
production caller; the old HeadAssembly constructor is used by the separate
standalone launcher without another EngineRoot. Minimal search-only composition
explicitly uses in-memory authority. These compatibility paths are not proof of
recorded ingestion recovery; activation must use the shared process authority.


Integrated1642 executed the full app-services and app-engine suites:3,145 cases/
480 suites, three existing skips, no failures/errors. UI main/test compilation,
services/Engine PMD and formatting passed (incremental reuse is recorded in the log).
Root independently verified all three negative-control source restores remained
byte-identical to the1639 backups after this run. This closes the C2-9b.1 local
composition item; C2-9b.2/.3 and actual producer activation remain open.

Hosted grant checkpoint27246acd6 failed the unchanged UI context assertion;
12 other CI jobs and CLA passed. [Exact hosted finding and correction](hosted-authorization-context.md).
No successful hosted run for this shared-authority diff is claimed yet.


## C2-9b.2 frozen scope and identity split (2026-09-14)

The accepted envelope/row identity and actual frozen effect scope are separate
checks with existing owners:

1. PreparedInvocationCodec and RecordedIngestPlanResolver reject a descriptor /
   public-input / envelope / accepted-context mismatch. Only the server-selected
   grant-reference transition differs; every other frozen axis remains exact.
2. IndexedRootGrantScope.coversPreparation accepts only a governed operation,
   METADATA, exact RecordedRootPlan schema, strict payload and a nonempty root plan.
   It snapshots current watched roots once, resolves every frozen path with exact
   toRealPath, and requires each to fall inside a currently resolvable watched root.
   Missing/unreadable paths and symlink/junction escapes fail closed. It never uses
   public-input coverage, closest-existing-ancestor fallback, or relative resolution.
   A raw in-root argument cannot excuse an out-of-root frozen effect. Ungoverned
   prepared operations retain the existing default refusal.
3. The trusted producer owns historical public-input-to-plan mapping: relative path
   resolution, exclusions, collection, file/directory classification and partitioning.
   Tests belong at that producer. Legitimate partitioning collapses duplicates and
   nested roots with equal policy, so a one-to-one raw-path comparison is wrong.

An unchanged frozen plan survives unrelated watched-root additions/reordering;
removing its covering root prevents the next permission check. Initial public-input
scope behavior is unchanged. Generation readiness and grant selection are outside
this scope component and belong to the recovery owner.

Independent review initially proposed a second argument digest in RecordedRootPlan,
then retracted it after checking the actual callers. Only the trusted registered
handler creates preparation, the dispatcher preserves original arguments exactly,
and the existing codec binds the whole envelope. A duplicate digest cannot catch
an incorrect trusted mapping or a coordinated local metadata edit. The existing
metadata store contract excludes hostile file-level rollback; this change adds no
digest, new schema version or parallel authority.

The scope cut is C2-9b.2a; typed shared-authority revalidation is C2-9b.2b. Both are
prerequisites to .3 activation, not completion of recovery on their own.


### C2-9b.2b typed authority decision

OperationAuthority owns a nested sealed recovery verdict: Authorized(RecordedRootPlan),
Wait, or Refused(OperationReceipt). Reconciliation remains the runner's final
lifecycle command: a pure authorization result cannot manufacture its Resume body.
The evaluator takes the accepted row/preparation, an optional currently serving
generation, and the current required-capability resolver. It has no runner/jobs
reference, writes no store, installs no permit, and never starts an effect.

Precompose the existing CoreOperationCatalog and AgentToolsOperationCatalog with
the authority and make SubstratePhase install those same base catalogs. Current
risk, family and required capabilities come from those canonical Operations;
there is no recovery policy map/catalog. The existing RecordedIngestPlanResolver
validates supported ingest/reindex identity and returns the frozen plan. Require
explicit registered transport membership in the shared source catalog as well as
EngineProvenance's source-tier validation (its unregistered UNTRUSTED fallback is
insufficient for restart). The same strict scope governs INGEST_FILES and REINDEX.
Containment applies to every resumed basis, including StructuralAuto. An initially
approved out-of-root effect can consequently be valid to start yet non-resumable.

A current DENY verdict refuses every basis, including a durable entry issued while
the hard stop is engaged. StructuralAuto additionally requires current AUTO.
Operation/family bases revalidate their exact existing DurableGrant key with the
store's risk/source/family caveats; another matching grant or current AUTO never
substitutes for a revoked selected key. EphemeralCapsule always refuses restart.
No token or fresh confirmation is synthesized.

Permanent binding/provenance, scope and authority refusals precede readiness: they
cannot be hidden behind a simultaneous unavailable capability. Absent serving
generation returns Wait; a different serving generation is a permanent mismatch.
A false/null/unavailable required capability returns Wait. Only then does the pure
check return Authorized. Compact refusal codes are RECOVERY_BINDING_INVALID,
RECOVERY_SCOPE_REFUSED, RECOVERY_AUTHORIZATION_REFUSED and
RECOVERY_GENERATION_MISMATCH; these use the existing bounded receipt code slot.

C2-9b.3 alone maps Wait/Refused to the runner's matching lifecycle commands, or
Authorized plus the real index-owner body to Resume. Install the permit inside
the winning Resume body after the runner's started CAS, never while merely
computing a verdict. Do not use the later EngineKnowledgeClient or Head
WorkerCapability for readiness inside generation-ready startup: the former does
not exist yet and the latter is not READY yet. Use the initialized index runtime
at that callback. An explicit later reconciliation is required to revisit Wait.


### C2-9b.2a verification (2026-09-14)

Base9a3aa8bb0781c95159488c92f95743c28b5c881c plus strict scope and the separately
uncommitted base-catalog reuse draft, Windows x64. Root retained production/test
ownership after one worker correction round and repaired two fixture deficiencies.

- 1645 compilation rejected the worker's wrong RecordedRootPlan import. 1646
  executed41 focused scope/resolver cases/7 suites, no failures/errors/skips;
  services PMD and formatting passed.
- 1647 deliberately allowed public-argument fallback and ancestor resolution:
  14 cases, two expected failures (out-of-root frozen effect behind valid in-root
  public arguments, and ungoverned prepared fallback). The ancestor change was NOT
  detected: the original missing-path fixture had already emptied the watched roots
  and tested an outside path. 1648 restored matching production and reused the
  passing1646 cache; it is not a fresh execution.
- Root corrected PUBLIC_ARGUMENTS to the actual paths key and added a separate
  absent frozen path inside an existing watched root. Isolated1649 ancestor fallback
  executed15 cases with exactly one expected failure in that new regression.
- 1650 restored production byte-for-byte to1646 and executed full app-services:
  2,919 cases/432 suites, three existing skips, no failures/errors. PMD and formatting
  passed. Root independently verified restoration bytes after the run. The Windows
  junction escape case executed, with no skip in the focused scope tests.

The two compiled catalog-reuse lines and authority getters are owned by C2-9b.2b;
they do not implement recovery eligibility and are excluded from this scope cut's
completion claim. The strict scope remains a prerequisite, not producer activation.
Logs/counts/XML: `tmp/1645*` through `tmp/1650*` in the active worktree, retained
through final lane reconciliation plus30 days, at least2026-10-14.

Hosted preloaded-authority checkpoint9a3aa8bb0 passed all13 jobs in
[CI34842534126](https://github.com/justsearch-app/justsearch/actions/runs/34842534126)
and CLA34842532052. Exact metadata is `tmp/1650-hosted-run.json`; this also verifies
the288113a2b UI assertion correction (its own CI was cancelled by the next push).
No hosted proof for the later dirty frozen-scope cut is claimed yet.


Independent refute found no production scope defect and requested a mixed-root
regression: a covered first root must not excuse a later uncovered root. Root added
that case. Negative1651 deliberately returned on the first covered root and executed
seven cases with exactly one expected failure in the new mixed-plan assertion.
Production was again restored byte-for-byte to1646 before final focused1652.

Final1652 executed 43 focused cases/7 suites, no failures/errors/skips; PMD/format
passed. Root verified restored production bytes after the run. The1650 full-suite
result remains applicable to unchanged production;1652 also executes the added
mixed-root test. Three governance gates, store recovery and canonical docs checks
pass in tmp/1650-gates.txt and tmp/1652-docs.txt. Retain1651/1652 with the same
artifacts/retention described above. C2-9b.2a is locally verified; typed authority
revalidation, producer activation and live restart proof remain required.


### C2-9b.2b verification (2026-09-14)

Base48b38b4ead9b6f97ae5f1de21c9d6ac0c08fb02b, Windows x64. The pure decision and
canonical base-catalog reuse implement the selected contract above. No lifecycle
command, runtime permit, queue mutation or effect is introduced by this cut.

- 1653 main-only services/UI compilation and services PMD passed while the test
  worker completed its separate test file; that run neither compiled nor ran tests.
- Root reviewed the test diff and corrected the public paths field, the reindex
  input, and two potential wrong-reason passes. The unregistered source case uses
  LOW reindex with AUTO so registration is decisive; the refusal/readiness case
  uses a capsule with required WorkerOnline so reordering is observable.
- 1654 executed32 focused cases/11 suites across services, Engine and UI, with no
  failures/errors/skips. Services PMD and formatting passed.
- 1655 disabled registration, scope, DENY, capsule prohibition, exact grant identity,
  no-AUTO-substitution and generation guards. It executed20 cases with nine expected
  failures, including both alternate-grant directions and a grant issued after the
  hard stop. This is a grouped control, not separate execution of each mutation.
- 1656 restored those guards and moved readiness before permanent checks. It
  executed20 cases with exactly one expected failure in refusal-before-readiness.
  Root restored OperationAuthority byte-for-byte from the positive1654 version
  before the final integrated run.

Logs, counts and copied XML are tmp/1653* through tmp/1657* in the active worktree;
retention is through final lane reconciliation plus30 days, at least2026-10-14.

The scope checkpoint48b38b4ea passed CI34844688686 and CLA34844684700; exact CI
metadata is tmp/1656-hosted-run.json. This is hosted proof for .2a, not the later
pure decision. The actual queue fence, producer and live restart proof remain .3.

Final1657 executed the full services suite:2,934 cases/433 suites, three existing
skips, no failures/errors. Engine(1 case) and UI(5 cases) were UP-TO-DATE from1654;
the combined represented result is2,940 cases/437 suites, not2,940 new executions.
PMD/format passed, and root independently verified restored authority bytes.
The first governance pass correctly rejected the undeclared authority reader;
it is now explicitly a consumer of operation-record-type, guarded by the real
recovery decision tests. No alternate lifecycle representation was registered.

Final governance1658 passes all three gates with zero findings; store recovery and
canonical documentation checks passed in1657. The declared reader and its guard
resolve without a baseline exception or suppressed finding.

Independent read-only refute by /root/seal_test_completion found no material pure
verdict defect or wrong-reason pass after reading the restored source, tests and
1654-1657 evidence. It explicitly does not establish .3 lifecycle/claim/startup
behavior. The separate production review by /root/walk_closure_refute was clear
before final test execution; root retained implementation and Gradle ownership.


Checkpoint449f56a06 is pushed and passed all13 jobs in
[CI34846902769](https://github.com/justsearch-app/justsearch/actions/runs/34846902769)
plus CLA34846899389. Exact metadata is tmp/1660-hosted-run.json. This closes hosted
proof for the pure decision; the queue/startup/producer cuts below remain separate.
PR727's managed review record was updated and exact-read-back verified through
449f56a06 before this hosted run completed (tmp/1659-review-applied.json).

## C2-9b.3 queue fence and startup sequence (2026-09-14)

Selected after source review at48b38b4ea and independent refute; the following is
not yet runtime proof. Split implementation into .3a queue-only permission checks,
.3b startup/reconciliation/lifecycle attachment and .3c the C2-8d.3 producer barrier.

Use an indexer-local required KnowledgeServer startup collaborator implemented by
EngineRoot's stable coordinator. This supersedes the earlier suggested app-api
callback: app-engine already depends on indexer-worker and worker-core; exporting
JobQueue through app-api would require another type/adapter and reverse dependency.
The collaborator provides a total mayClaimRecorded(key) and generation-ready hook
returning a server-scoped close handle. Give SqliteJobQueue its predicate at
construction; all existing no-owner overloads default to denying recorded work.
There is no late mutable setter or persistent permission table.

For .3a, only nonnull walk_seen_epoch denotes recorded membership. PENDING polling
checks the nonblank scan_id and predicate under its existing queue transaction lock,
after active-claim exclusion and before counting toward the batch limit. The query
already has no SQL LIMIT; denied oldest rows must not starve eligible work. Extend
PROCESSING recovery's projection with scan_id and epoch and apply the same check
before requeue. A closed FAILED/CANCELLED orphan instead receives the existing
administrative skip without needing permission to execute; active issued owners
remain excluded from both branches. Both unconditional startup and aged
reaper entry points share this method. Legacy null-epoch jobs retain their existing
behavior even when scan_id is nonnull. False or a runtime failure in the callback
denies; fatal errors propagate. Callback is a pure bounded authority check with no
SQL, runner, queue or cross-store callback. Existing issued claims may finish or
return after revocation; new claims may not pass. No schema/state/ledger is added.

For .3b, move recovery/reaper from their current pre-generation position to after
app-services construction, embedding compatibility and the synchronous generation-
ready callback, immediately before indexing starts. Callback failure aborts startup.
Use the same idle-active-serving observation as WorkerIngestService generation
capture: layout.activeGenerationId alone is invalid while Blue serves and Green
receives writes. Readiness must use the initialized index runtime because the
EngineKnowledgeClient and Head WorkerCapability are not yet available there.

Only the winning Resume body can install recovery permission after runner started
CAS and successful C1 admission. Fresh permission is minted only inside the winning
private child start body as specified below. The under-lock predicate is the unit
authorization boundary and revalidates current authority appropriate to that origin
from immutable accepted row/preparation. A later
non-Authorized verdict invalidates the permission and leaves cross-store lifecycle
handling to after-unlock reconciliation. Every initial or later explicit
reconciliation pass must immediately run unconditional recoverStuckJobs after
winning bodies install permissions. An aged reaper alone cannot recover formerly
fenced PROCESSING jobs: heartbeatProcessing refreshes all their timestamps.

Refused operations get no permission. After their durable terminal, the existing
notification/maintenance owner stops and seals their walk and repairs crashes
between the stores. Wait keeps the operation nonterminal and fenced and requires
explicit later reconciliation. Generation/server-scoped permissions survive drain
and final receipt flush, then close before jobs; failed startup closes them too.

Required .3a regressions: no-owner denial for PENDING and both PROCESSING recovery
entry points; false/throwing predicate denial; old denied rows cannot consume the
limit; nonnull legacy scan key remains eligible; exact key delivery; revocation
blocks the next claim while an issued claim can finish; later authorization plus
unconditional recovery revives a heartbeated row; reopen defaults to denied. Existing
recorded terminal/seal tests explicitly supply allowed test authority, retaining all
existing lifecycle assertions.

Required .3b/.3c proofs: no recovery/reaper/poll before callback, callback failure
aborts; migration never grants idle-serving permission; losing CAS installs no
permission; refusal terminal-to-seal crash repair; single-file and directory
membership; missed notification/final drain flush; exact child acknowledgement
barrier; startup failure/restart cannot carry permission into a new generation.
The reviewed source paths and earlier observations are retained in
tmp/C2-9b3-discovery.md and tmp/C2-8d3-review-decisions.md, but this owning record
contains the decisions needed to continue without those local notes.


Independent .3a review corrected the initial predicate-before-all-mutation plan:
a claim can be issued, its enumeration close FAILED/CANCELLED while the owner is
still live, then the process can crash. On reopen that operation cannot earn a
new execution permit; gating its administrative skip would leave PROCESSING
membership permanently unsealable. Recovery therefore excludes live owners first,
then accounts stopped orphans as SKIPPED, and checks permission only before a
PENDING transition. This is closure accounting, not a replay authorization bypass.
Regressions must cover both stopped outcomes and both recovery entry points with
a denied/throwing authority, and prove the resulting receipt seals.


### Fresh continuation, restart admission and child binding (2026-09-14)

Implementation cuts within .3b are .3b.1 the pure fresh-continuation policy,
.3b.2 the indexer-local startup/drain attachment, and .3b.3 the stable activation,
admission and reconciliation coordinator. Each is committed/pushed separately;
none alone completes activation. .3c remains the actual producer/receipt barrier.

The .3b.1 signature is allowsFreshRecordedIngest(parent, boundPlan). It reads the
accepted parent row's stamped basis and canonical supported operation policy, while
boundPlan is the exact already-validated child effect. Envelope validation and exact
parent-child membership remain with the resolver/runner, outside the jobs lock.
A private short-lived binding projection may share decoding with restart evaluation;
it is not an authority token, durable record or public validated wrapper. This avoids
duplicating the finite operation/provenance/basis checks without moving store access
under the queue lock. Null/malformed binding or plan denies. An ACCEPTED snapshot
is not rejected solely by its state: the private runner body proves actual start.
Fresh durable scope applies to that active child plan; removing another future root
does not cancel this child, but prevents that future child when selected. Restart
continues to validate the entire frozen parent plan. The boolean itself proves only
current policy, never fresh origin, generation, admission or permission.

Keep evaluateRecordedIngest restart-only. The stable coordinator retains only
process-local permission keyed by the exact child operation key and server/generation
activation, with fresh-start versus restart origin. Fresh origin cannot be inferred
from a persisted row or fabricated OperationRecordHandle: the registered prepared
producer passes its live parent handle through runner.acceptIngestChild, which
validates the private runner Control and exact parent preparation. Fresh permission
is created only in that child's winning attempts.start body, after durable start.
There is no public installFresh(handle,key) method or new persisted flag/token.

A named boolean fresh-continuation check on the same OperationAuthority owns the
current gate/grant rule. Binding is decoded and validated outside the jobs lock.
Registered provenance and current non-DENY are required for every fresh basis.
StructuralAuto still requires current AUTO but does not require watched containment.
An exact operation/family basis still requires its selected grant and strict current
frozen containment; another key or current AUTO cannot substitute. EphemeralCapsule
uses only the same live fresh activation and current non-DENY; never reconsume,
recreate or persist a capsule. Initially approved out-of-root AUTO/capsule work may
thus run, while the same AUTO refuses out-of-root restart and any capsule refuses
restart. Root cancellation still stops its walk; unrelated root-list changes do
not erase consent whose authority never came from that list.

Fresh producers retain executePrepared's exact attached context/workId already held
by the dispatcher through async completion; do not admit a second parent work item.
A boot parent obtains admission.attach(row.context()) only in its winning Resume
body, before permission; the resulting handle/context lives through the asynchronous
parent acknowledgement barrier. Boot child activation attaches that admitted parent's
context, rather than minting an independent workId from its inherited durable row.
A child waits if the parent has not obtained admission. Client bind uses that same
workId; no allowWhileFrozen or direct unbounded Worker call is allowed.

Admission can lose a freeze/capacity race after the runner CAS. Return a pending
asynchronous execution rather than throwing a transient refusal into terminal FAILED.
Install no permission until admission succeeds. Retry from client-bind and the
existing maintenance/reconciliation owner, then immediately run unconditional jobs
recovery. On shutdown, release runtime resources without fabricating a terminal
outcome for an unstarted pending effect. Deferred scans use an EngineExecutorRegistry
BACKGROUND registration with its existing thread/queue bounds, not a raw scheduler.

The activation map itself is bounded by the running admission owner's aggregateLimit,
read from EngineAdmissionService.limits rather than a copied default. Full capacity
returns Reconciliation.Wait before allocating another Resume body. Serialize the
coordinator's provisional activation/CAS handoff; a losing CAS discards the reservation
without permission. No second quota registry, semaphore or durable marker is needed.
Parents drive roots sequentially through child acknowledgement before activating the
next root, so fresh child state is bounded by admitted parent work. The existing
bounded frozen root plan supplies the remaining roots without duplicating it per child.

Child recovery parses RecordedIngestChild.from from its actual accepted preparation,
loads the named real parent and validates that parent's actual core.ingest-files or
core.reindex envelope with canonical authority. Scope covers the WHOLE parent frozen
plan; child generation and its sole root must match an exact member. Require inherited
context, executor, initiator, correlation and provenance timestamp plus historyMode
NONE. An open child below a terminal parent is invalid. COMPLETE_WITH_GAPS is not
permission to replay: await the separate D1 decision. Parent must be RUNNING with
admission before child Resume; reconcile REINDEX parents before INGEST children and
retain acceptance order for INGEST parents. Terminal child receipt repair needs no
permission. The open parent anchors all child acknowledgement catch-up.

Accepted binding metadata is immutable; no new atomic multirow store API is selected.
The coordinator must fence child permissions before initiating parent/child terminal
completion and obey the acknowledgement barrier. Prove that ordering; separate row
reads alone do not establish lifecycle consistency. Failed startup/generation replacement
closes all permissions and deferred work; a fresh origin never survives it.

Required proofs add fresh out-of-root AUTO/capsule versus restart, exact inherited
child binding, fake/lost handle and CAS refusal, durable revoke/root removal with
alternate authority present, current AUTO/DENY changes, one shared workId/no leaks,
capacity-full Wait without retained body, transient admission retry, no pre-bind
blocking, bounded scheduling, shutdown without false terminal failure and exact
parent acknowledgement ordering. These are selected mechanisms and required checks,
not completed .3b/.3c implementation. Root verified the child/admission source paths;
independent read-only reviews were performed at449f56a06 without runtime tests.


### C2-9b.3b.1 fresh policy implementation and proof (2026-09-14)

Base1927164b6, Windows x64/Temurin25.0.2+10. OperationAuthority now owns
allowsFreshRecordedIngest(parent, boundPlan) with exactly one bound root. A private
short-lived IngestPolicyBinding shares the existing canonical operation, registered
transport, provenance and basis decoding with restart evaluation. The fresh policy
performs no store/runner/permission/generation action; restart semantics remain
whole-plan scope, permanent refusal before readiness, and capsule refusal.
Independent production refute found no surviving defect in this cut.

Root reviewed the worker's isolated test draft before installing it. Its original
current-AUTO claim actually used medium-risk ingest; a separate low-risk reindex
case now proves that revoked selected authority cannot fall back to current AUTO.
Root removal now changes the same authority's roots supplier. Hard-stop durable
cases reissue the grant after stop so the denial assertion tests DENY rather than
incidentally passing from grant revocation. Two existing restart tests now prove
fresh policy success and restart refusal for the exact same row/frozen invocation.
This is policy evidence only; private winning-child activation remains .3b.3/.3c.

- 1669 main compilation and PMD pass. Three unchanged HeadAssembly advisories remain:
  ignored indexing-bridge Future, locale-less health lowercase, and unnecessary
  capability-resolver lambda. This cut introduces none of those warnings.
- 1670 executes28 cases/6 suites with zero skips/failures/errors; test PMD and whole
  formatting pass, including the unchanged restart decision suite.
- 1671 bypasses only fresh basis eligibility after current DENY;14 cases produce
  exactly five expected failures (current AUTO, both exact keys, alternate grant and
  root containment checks). This is a grouped guard mutation, not five separate runs.
- 1672 separately bypasses the single-root guard;14 cases produce exactly one expected
  failure. Root restored production byte-for-byte to1670 from
  tmp/1671-authority-original.bin before full services1673.
- Full services1673 executes2,942 cases/434 suites, three existing skips, zero
  failures/errors. Main PMD reuses its restored positive cache; test PMD and Spotless
  are up-to-date from1670. Hosted proof is pending after commit.

Independent test review found no material wrong-reason pass. Root independently
re-read XML and corrected its evidence summary:1670 is8 fresh cases +14 recovery
cases +6 automatic guardrails, not14 fresh cases. The1671 five failures are structural
AUTO, selected family, selected operation, revoked grant/current AUTO, and scope;
DENY is retained in that mutation and its hard-stop test passes. The independent
review is source/evidence review, not an additional executed run.

Logs/counts/copied XML are tmp/1670 through tmp/1673 with .txt, -counts.json and
-xml suffixes in F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify. Retain
through final lane reconciliation plus30 days, at least2026-10-14. .3b.2 startup,
.3b.3 activation/admission and .3c producer barrier remain required; no call-site,
actual recovery, live-model or process-restart claim follows from these pure tests.

All three governance gates, store recovery and canonical links pass1673.


### C2-9b.3b.2 startup and close attachment (2026-09-14)

Root source investigation and independent read-only review at2bfd30b08 select an
indexer-local RecordedIngestionLifecycle with constructor-bound mayClaimRecorded
and synchronous attach(queue, checkedServingGeneration, workerOnline). It returns
a closeable server-scoped attachment. The Worker readiness input is a BooleanSupplier;
EngineRoot maps its own RequiredCapability vocabulary rather than teaching the indexer
about agent policy. InferenceOnline is not embedding readiness and remains unavailable
through this index-only seam. No current finite-ingest operation requires it.

The checked generation source dynamically reads the authoritative state for captured
activeIndexPath, then requires the same RunningRuntime for ingest/search and no
exhausted rebuild brake. This matches the actual typed WorkerIngestService constructor;
a DeferredRuntime is readable but not yet a recorded write target. Invalid/unreadable
state aborts startup, while a valid empty observation keeps recorded work fenced.
No deep health request is called inside a queue predicate: that request itself reads
SQLite/Lucene. Per-claim generation failures deny; no cached active-generation fallback.

Attach must clean provisional state itself on any failure before returning, including
Error. The server publishes the returned handle immediately; subsequent startup failures
clean it through normal ordered close, preserving the original failure and any cleanup
failure. EngineRoot previously lost a failed-start owner unconditionally; this cut fixes
that structural defect by clearing it only after awaitClosed(0) proves completion.
Otherwise later start refuses and close retries the same physical owner. Fatal errors
remain fatal and are rethrown unchanged after cleanup is attempted.

The handle closes only after app-services/index-loop drain and final receipt flush,
then revokes permissions before jobs close. A failed close retains it and the queue;
a successful close clears it immediately. No public permit installer, queue export
through app-api, or new timer/state table. The two-minute Worker orphan reaper stays
aged recovery only. The existing Head 30-second operations maintenance owner remains
the .3b.3/.3c home for later reconciliation and immediate unconditional queue recovery.

The constructor's no-owner overloads explicitly deny. Actual stable coordinator binding
remains .3b.3. Required tests exercise real boot ordering, callback failure/fatal cleanup,
close retry, restart exclusion, same-queue corruption reopen, strict current generation
versus migration/brake/deferred state, and no callback into jobs from readiness.


.3b.2 implementation now supplies that constructor-bound lifecycle and ordered
attachment/close seam. Independent production review found no surviving defect
after root added volatile runtime publication and a single snapshot after the
appServices acquire read. The checked observation validates state even when the
runtime is currently fenced. This does not add a generation lease.

Initial1675 compiled indexer/Engine main code and passed both main PMD tasks.
Focused1676 executed9 Engine cases/3 suites, zero skips/failures/errors, plus Engine
test PMD and formatting. Those tests cover failed-start retained ownership, close
retry, confirmed-clean retry, fatal identity, interruption and existing terminal-writer
ownership. The later runtime-visibility refinement still requires fresh compilation
and real indexer boot proof. Worker authored the root test as an isolated draft;
root corrected its missing checked-exception declaration before installation.
The initial startup/generation/close draft was still pending at1676; later proof is
recorded below.
Three governance gates, store recovery and generated canonical docs checks pass1676.
Logs are tmp/1675.txt and tmp/1676.txt; counts/copied XML are tmp/1676-counts.json and
tmp/1676-xml. Same final-reconciliation-plus30-day retention, at least2026-10-14.


Fresh-policy checkpoint2bfd30b08 passes all13 CI34853428167 jobs and
CLA34853425540; metadata tmp/1677-hosted-run.json. This hosted result excludes the
later dirty .3b.2 startup seam. Root negative1677 restored unconditional failed-start
owner discard and executed4 cases with exactly3 expected failures; the confirmed-clean
retry control still passes. Root restored EngineRoot byte-for-byte from
 tmp/1677-root-original.bin before proceeding. Final startup proof remains pending.


Root corrected the installed draft after1678 exposed unchecked Jackson parse failure
through the declared checked generation source, and a redundant test assignment.
The initial correction wrapped JacksonException in the strict IndexGenerationManager
read;1685 showed that this changed its existing exception contract. The final correction
below moves translation to the new checked lifecycle boundary instead.1679 then exposed the draft's invalid
sealed LuceneRuntime mock; root replaced it with the existing concrete RunningRuntime
fixture. Both failed logs/XML are retained.1680 executes14 cases without skips or
failures, plus test PMD and formatting. No failed assertion was weakened.

Independent review identified missing publication/identity and startup-preflight proof.
Root added valid-IDLE/no-services and distinct writable runtime fences, their positive
control, and real boot corruption immediately before attach. That boot must refuse
before invoking the collaborator, preserve malformed state, and finish cleanup.
Readiness explicitly verifies zero JobQueue interactions.1683 executes19 cases/3 suites
(9 real/helper indexer cases,6 automatic guardrails,4 Engine ownership cases), zero
skips/failures/errors. Both test PMD tasks and formatting pass. Actual boot cases suppress
only deferred model initialization; real SQLite, Lucene, services and drain execute.
This is no substitute for the later live producer/model/restart proof.

Negative controls:1681 moves recovery before attachment (7 cases, exactly1 expected
ordering failure);1682 discards the attachment before its close returns (8 cases,
exactly2 expected retry failures);1684 removes startup preflight and the publication/
runtime-identity guards together (8 cases, exactly2 expected failures).1684 is one
grouped mutation, not independent proof of each removed condition. All controls retain
6 automatic guardrails. Root restored KnowledgeServer byte-for-byte from
 tmp/1681-ks-original.bin after each control and before full1685.1677 independently
covers EngineRoot's failed-start ownership discard (4 cases,3 expected failures).

Logs/counts/copied XML for1678-1684 remain under tmp in the active worktree with
.txt, -counts.json and -xml suffixes. Same retention through final lane reconciliation
plus30 days, at least2026-10-14. Full1685 and final review remain pending at this entry.


Full1685 executes1,189 cases/231 suites with21 skips and5 failures: indexer607/15
skips/zero failures; Engine240/no skips/two failures; Worker-core342/six skips/three
failures. Engine pacing ended its observation at exactly10 samples before requiring
more than10; concurrent-read indexing missed its180-second drain deadline. The unchanged
multi-group CPU embedding test took627.920 seconds against its10-minute limit. These
three failures remain open for separate verification; parallel module load is a hypothesis,
not an established cause. Two generation-reader failures directly refuted root's overly
broad exception translation: its existing tests explicitly require JacksonException.
Root restored IndexGenerationManager byte-for-byte from HEAD and translated only at
KnowledgeServer's new checked lifecycle source, preserving both contracts and all tests.

1686 executes27 cases/4 suites, zero skips/failures/errors:16 indexer (10 lifecycle
plus6 guardrails),7 existing VDU generation eligibility and4 Engine startup ownership
cases. Indexer main/test PMD and formatting pass. It also adds real
boot corruption of SQLite page1's schema b-tree with a valid backup: boot must actually
quarantine/restore, attach before recovery, consult the original constructor callback,
and leave the restored recorded PROCESSING member fenced. No corruption-test seam or
error-classifier bypass is used. Raw logs/XML/counts for1685 and1686 are retained under
tmp with the prior suffixes and retention. Thread captures1685-29980-threads.txt and
1685-28596-threads.txt show active CPU inference and real Engine workload, not completion.
The per-item implementation is a WIP checkpoint until the remaining integrated failures
are resolved. .3b.3/.3c remain required; no coordinator activation is claimed.


Lifecycle WIP1ced178d9 is pushed. Focused1687 separately corrects the inherited pacing
fixture's early observation exit and passes its unchanged assertion set; see
[pacing evidence](pacing-observation-exit.md). Full sequential integrated verification,
including the prior concurrent-read drain and multi-group embedding failures, remains
required. No new deadline or input reduction was selected.
