# C2-9 ingestion authorization recovery contract

## Status and scope

This note records the minimum authorization and composition contract for recovering recorded `INGEST` work. It is design evidence under C2-9. It does not move a stage, implement the recovery owner, or make an accepted operation row an authorization authority.

The source review was performed at revision `128a0c945e60fe1ba3d1afb4d30869d8242ea6d9` in the Windows/PowerShell worktree `F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify`. The tree also contained unrelated in-progress app-api value changes. No build or test was run for this docs-only review.

## Verified current behavior and gaps

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

The generation-ready callback first revalidates each interrupted recorded ingest from its server-built basis and frozen `RecordedRootPlan`. Only authorized operation keys enter the runtime permit set. The same permit predicate gates both `PENDING` polling and `PROCESSING` recovery/reaping. No job may be released before generation compatibility, capability checks, authorization, and root containment have succeeded.

Authorization is revalidated at each recorded-unit claim boundary, or through an equivalent invalidation path tied to the same sole grant and roots authorities. Revoking the selected durable grant, removing a watched root, changing structural policy, or engaging the hard stop prevents the next unclaimed unit. A unit that has already passed the check and been claimed has crossed its admission boundary; revocation does not retroactively cancel an effect already in progress. This boundary adds no progress counter or second authorization authority.

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

Shared pre-fork authority and the sole roots carrier are locally verified in C2-9b.1 below. Strict recorded-root scope, per-claim invalidation and the generation-ready recovery callback remain unimplemented. After-lock queue notification is implemented and verified separately in [walk-notifications-retention.md](walk-notifications-retention.md). The C2-9a.1 basis/selection/binding cut is locally verified below; it does not establish live recovery, reboot permission or stage completion.


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
