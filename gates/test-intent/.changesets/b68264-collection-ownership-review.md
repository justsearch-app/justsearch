---
schema: test-intent.v1
task: t-20261024-b68264
author-role: builder
author-session: 01a18819-40da-7781-a431-5bfa7518612d
---

# Test-intent entries

The audit correction separates the retired deletion-without-removal-evidence expectation,
its replacement scenario and harness instrumentation. The Engine file is absent at merge base
940edec03639; the replaced fixture is in commit 873a11ad97 at
modules/app-engine/src/test/java/io/justsearch/app/engine/EngineHintlessCollectionMigrationTest.java:113-172
(direct declaration erasure at lines 125-127). The candidate replacement is at lines 178-268.
These snapshots describe test changes, not sources of intent: the delegated R1 settlement below
supplies the warrant. Existing execution evidence still hashes the unchanged candidate tests,
so it is reused. Prior acceptance records remain below and become stale after the entry edits.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineHintlessCollectionMigrationTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "R1 settlement: retire the earlier removeDeclaration=true scenario that erased all declarations directly and expected the legacy NULL admission to disappear after restart/cutover without a recorded-plan owner, pending removal or covering scoped deletion. Preserve unlabelled admissions outside every declared or frozen-plan root; convert them to deletion only with a later generation-eligible covering scoped deletion. Missing ownership alone is not deletion authority."
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in docs/decisions/ for NULL-labelled recovery or removed-declaration deletion authority; the supplied R1 delegated settlement supersedes the earlier C7 deletion-without-removal-evidence expectation."
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineHintlessCollectionMigrationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Replace removeDeclaration=true with a different deletion-authority scenario: removedDeclarationRecovery (lines 178-268) loads durable root authority, starts and pauses a real Engine migration, and plants an old-version NULL job and UPSERT receipt before removal. Production Remove removes the parent then child, verifies empty declarations and a DELETE_PREFIX covering the file, and is followed by collection C deletion. After restart/resume, the legacy NULL UPSERT must resolve to ownership or removal before ordered replay; cutover must publish a different generation; parent and chunks must be absent after cutover and another restart. NULL seeding, migration phase, removal evidence and the covering prefix assertion are substantive new preconditions and observations, not Adaptation. removeDeclaration=false and other prior checks keep their scenarios. The cited whole-class evidence executes and passes [2] true, and records an assertion fail-before for [2] true at the legacy NULL receipt resolution assertion on fda69956df. The out-of-root preservation counterpart has its own Defect pin below.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "R1/C7 settlement: represent removed declarations through production Remove and its scoped deletion evidence. A pre-fix NULL admission is converted to deletion only with a later generation-eligible covering DELETE_PREFIX; recovery/replay, live cutover and another restart must not resurrect its parent or chunks. Without declared/frozen-plan ownership or covering removal evidence, preserve NULL admissions."
        }
      ],
      "evidence": "gates/test-intent/evidence/b68264-collection-ownership.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineHintlessCollectionMigrationTest.java"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineTestHarness.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Harness instrumentation only: add startWithPersistedRoots and load the durable OperationAuthority at each boot for callers selecting that entry, exposing the real Remove path to the replacement Engine scenario. Existing hooked start entries keep their in-memory authority; their inputs, preconditions, callbacks, barriers, assertions, timeouts and retry bounds are unchanged. Durable authority and removal/deletion preconditions in the opting-in Engine scenario are classified separately as New above, not claimed as oracle-preserving input re-encoding.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "R1 settlement: exercise production Remove using the same durable declarations as the Worker; keep existing harness start entries and their oracles unchanged. The replacement removal-authority scenario is separately warranted."
        }
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineHintlessCollectionMigrationTest.java"
      ],
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no data loss",
        "reproduction": "0172c39092 rewrites an acknowledged NULL-labelled out-of-root UPSERT into DELETE on boot, dropping its job and replayed document after a migration restart."
      },
      "reproduction": "0172c39092 rewrites an acknowledged NULL-labelled out-of-root UPSERT into DELETE on boot, dropping its job and replayed document after a migration restart.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "R1/C3: a valid admission outside every declared or frozen-plan folder must retain its NULL-labelled receipt, job, parent and chunks through a real migration, restart, replay, live cutover and another restart. Unknown ownership alone never authorizes deletion."
        }
      ],
      "evidence": "gates/test-intent/evidence/b68264-collection-ownership-review.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineHintlessCollectionMigrationTest.java#outsideAdmissionKeepsNullJobReceiptAndDocumentAcrossMigrationRestart"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/DeclaredCollectionBatchSnapshotTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/DeclaredCollectionScanSnapshotTest.java"
      ],
      "class": "New",
      "behaviour": "R2: for 32 files, ordinary and candidate scans and hintless normal/migration batches read declarations once per scan or batch, with every file sharing that snapshot. A changed registry is observed by the next scan/batch and changes its reconciliation/admission result. Each operation keeps one snapshot; the composed service now reuses parsed declarations between operations until the registry file identity changes (F2/K3-K5 below). Scan snapshots load only at the first collection comparison, retaining the old absence of lookup for deletion-only, forced, or reserved-only scans. The unchanged WatcherReloadRetryTest preserves the real deletion-only reload path; it passed on 0172c39092 and caught the intermediate eager-load regression.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "R2: avoid reading and parsing watched-root declarations for each file; read once per scan/batch, before the queue lock where ordering permits, and refresh next time. Between operations, parse the registry at most once per change: reuse the parsed declarations while the file's key, size and modification time are unchanged. Preserve frozen-plan, explicit and reserved collection authority."
        }
      ],
      "evidence": "gates/test-intent/evidence/b68264-collection-ownership-review.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/DeclaredCollectionBatchSnapshotTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/DeclaredCollectionScanSnapshotTest.java"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-b68264-codex-r3",
  "verdict": "accept",
  "digest": "sha256:7a2c0e5384682d8d75087ef3b2bd8d9fe3c98b7371dd411050f514c9cbe013aa",
  "note": "R1 restart/cutover, deletion boundaries, snapshot refresh, C1-C7 and merge checks pass; all 96 full-suite failures match baseline."
}
```

## Final bounded repair: F1 and F2 (K1–K5)

Source: the builder assignment, user turn of 2026-10-29, “close two review findings on the
collection-label fix (b68264), third and last repair round”. Its settled design says:
“live hintless admission uses the same authority order as replay” and “the declaration supplier
parses the registry at most once per registry change”. K1–K5 below reproduce those supplied
scenarios. These two additional test classes are isolated new sources, exempt under the
isolated-new-test rule; their fail-before logs and JUnit XML remain for the independent verifier.
The R2 entry retains the operation snapshots introduced in the preceding repair and now states
the parsed-registry cache policy too. Its prior acceptance must be refreshed by the verifier.

- `EngineFrozenCollectionAdmissionTest#unseenLiveFileUsesFrozenDefaultUntilCutoverThenScanRelabels`
  (K1): prepare and dispatch an actual durable bulk operation whose accepted root plan freezes
  default. Pause its recorded migration before publishing Green, replace the persisted declaration
  with C, then submit a previously unseen hintless file through the live Engine. Compare its actual
  indexed label with the independently asserted default label of an equivalent legacy NULL receipt
  repaired using that same accepted plan. Delete C, assert the parent and chunks survive, resume
  and publish Green, scan, and await C. The first fixture used the native start RPC and was rejected
  as evidence for a recorded scenario; final evidence uses the durable operation path.
- `DeclaredCollectionRegistryCacheTest#candidateReconciliationParsesRegistryOnceWithRealQueue`
  (K3): reconcile 64 new files on a default subscription using the composed ingest service,
  generation manager and real SQLite queue/journal. Count actual `WatchedRootsFormat.declaredCollections`
  calls with a scoped static counting wrapper which calls the real parser; require exactly one.
- `DeclaredCollectionRegistryCacheTest#watcherAndSingleFileAdmissionsReuseParseAndSeeRegistryChanges`
  (K4): independently exercise 64 watcher admissions and 64 single-file batch requests. Require one
  actual parse per unchanged registry; atomically replace A with equal-length B at the same mtime,
  then remove and re-add the declaration. Each next admission must use the new authority and each
  revision adds one parse. The counting wrapper is closed at test exit.
- `DeclaredCollectionRegistryCacheTest#reappearedFileFetchesDeclarationSnapshotOutsideQueueLock`
  (K5): wrap the real service declaration supplier, inspect the actual queue's ReentrantLock on
  every supplier invocation, and assert it is not held by this thread before calling through.
  This observes even the cache's metadata read, rather than only counting parser calls.
- K2 and preservation: existing `DeclaredCollectionAdmissionTest`, all earlier collection,
  snapshot and recovery checks, plus the three complete module suites remain unchanged.

Execution evidence is in `tmp/round3-*-results/` and matching `tmp/round3-*.log`; the final delivery
names the authoritative before/after runs and their counts. The before checkout is a shared clone
of 3241ebf1a3, with only the new test sources overlaid, and its own build directories. There is no
`.agents/preflight` in this checkout.

Registry identity: `WatchedRootsFormat` is the reader. `WatchedRootsStore.persist` writes through
`AtomicFileWrites.replace`, which creates a temporary file and renames it over the target (atomic
move where supported, replacement fallback otherwise). On filesystems exposing fileKey, an
adjacent replacement has a new identity even with equal size and mtime. The cache probes fileKey,
size and full FileTime together. An external in-place rewrite preserving all three can be missed;
a filesystem without fileKey, or inode reuse over multiple unseen replacements with matching size
and timestamp, can also collide. A change racing a probe is observed on the next call. Failed
parses do not replace the previous snapshot, and are retried rather than served as a cache hit.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-b68264-codex-r5",
  "verdict": "accept",
  "digest": "sha256:50010fb501cda03d68f2acae99ab153186e39b53aa52dfac3d94592fc6d48bd1",
  "note": "Verified merge retains both trees without overlapping file edits; 67 targeted cases pass without skips; baseline reproduces K1 and four cache/lock failures; repeated malformed-registry refusal and repair pass before and after; F2 is attributed as a delegated agent choice."
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "auditor-1045-codex-2",
  "verdict": "accept",
  "digest": "sha256:a2668efed1575163c172308a1d3c2a07ac3907152262b571c9c90e019844bd1c",
  "note": "I compared the 873a11ad97 fixture and merge-base harness with the candidate, checked the cited warrants and repeated the decision-record conflict search. The retirement/replacement classifications, harness Adaptation, out-of-root Defect pin and snapshot New entries are supported by matching committed evidence, including assertion fail-before and passing candidate runs."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-b68264-codex-r7",
  "verdict": "accept",
  "digest": "sha256:4483431a7217ac4b7132330cc01144230eece2f19c385ec5717c73925c8dc500",
  "note": "Correctly separates retirement, replacement deletion scenario and harness instrumentation. Evidence hashes match current tests and pass the official validator. (Recorded by the orchestrator: the verifier sandbox refuses the gate's git subprocess, spawnSync git EPERM.)"
}
```
