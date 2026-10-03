# Stage A — measured deletion counts (design 16, "positive benefit")

Recorded 2026-09-08. Base = `git merge-base main HEAD` = `76871d924`. HEAD = the stage-A branch
`worktree-lane-F-A`.

Design 16's positive-benefit row asks stage A to produce the deletion count itself. §7 of
`stages/A.md` is the *forecast*, made at `fe19df0d5` before the code was written; this file is the
*measurement*. The table also lives in `stages/A.md` §6 so a reader of the checklist does not have
to find this file; this is the recorded-evidence copy the row asks for, with the full command list.

## Convention

`git diff --diff-filter=D` and `git diff -M --diff-filter=D` both return 105, so no deletion is
being reclassified as a rename. There are 35 separate renames (24 of them `Grpc*ServiceTest` →
`Worker*ServiceTest`). Under the alternative convention where a rename counts as a deletion plus an
addition, the totals become 140 deleted / 161 added. Everything below uses the rename-aware
convention — 105 deleted / 126 added — which is the smaller of the two.

## Files deleted, by area

| area | files |
|---|---|
| gRPC / proto plumbing | 42 |
| worker process, spawner, supervision | 25 |
| system / chaos / soak / torture tests | 22 |
| MMF / shared-memory bus | 9 |
| config-snapshot tier | 5 |
| logging (the second log tier) | 2 |
| **total** | **105** |

Independent cross-check by source set: main java 37 + main proto 1 + main resources 1 + test 38 +
systemTest 19 + soakTest 1 + integrationTest 3 + docs 1 + scripts 4 = 105. (The proto is the
deleted `infra_diagnostics.proto`, under `src/main/proto/`, not `src/main/java/`.) Every one of the 105 paths landed in
exactly one area bucket; there were no unclassified paths.

## The other quantities

| quantity | measured |
|---|---|
| gRPC RPCs removed | 51 — 49 from `indexing.proto` (`SearchService` 10, `IngestService` 38, `HealthService` 1) plus 2 from the deleted `infra_diagnostics.proto`. `indexing.proto` has 0 `rpc` and 0 `service` at HEAD |
| proto `service` blocks removed | 4 |
| MMF fields removed | 10 named data fields (12 addressable slots, 2 reserved); 18 `public static final` declarations across `MmfWorkerSignalLayoutV1` (12 / 9 `OFFSET_*`) and `MmfWorkerSignalHeaderV1` (6 / 3) |
| argv builders removed | 4 by §7's enumeration; 6 counting `WorkerProcessManager.createProcessBuilder()` (dispatcher) and `ManagedProcess.createProcessBuilder()` (abstract) |
| config ordinal removed | yes — `ORDINAL_WORKER_SNAPSHOT = 450` (`ResolvedConfigBuilder.java:59` at base, absent at HEAD, zero references left in `modules/` or `scripts/`) |
| log file removed | yes — `worker.log`; `headless-backend.log` renamed to `engine.log` |
| test classes deleted vs replaced | 31 outright / 29 replaced, of 60 deleted `*Test.java` |

## Commands

```bash
BASE=$(git merge-base main HEAD)                                   # 76871d924
git diff --diff-filter=D --name-only $BASE..HEAD | wc -l           # 105
git diff -M --diff-filter=D --name-only $BASE..HEAD | wc -l        # 105 (rename-aware, unchanged)
git diff -M --diff-filter=R --name-status $BASE..HEAD | wc -l      # 35 renames
git diff --diff-filter=A --name-only $BASE..HEAD | wc -l           # 126
git diff $BASE..HEAD -- '*/indexing.proto' | grep -cE '^-\s*rpc '   # 49
git diff $BASE..HEAD -- '*/indexing.proto' | grep -E  '^-\s*service ' # 3 blocks
git show $BASE:'*/infra_diagnostics.proto' | grep -cE '^\s*rpc '    # 2
git show $BASE:'*/MmfWorkerSignalLayoutV1.java' | grep -cE '^\s*public static final'  # 12
git show $BASE:'*/MmfWorkerSignalHeaderV1.java' | grep -cE '^\s*public static final'  # 6
git show $BASE:'*/ResolvedConfigBuilder.java'  | grep -nE 'ORDINAL_[A-Z_]+ = '
git diff --diff-filter=D --name-only $BASE..HEAD | grep -c 'Test\.java$'  # 60
git diff --diff-filter=A --name-only $BASE..HEAD | grep -c 'Test\.java$'  # 35
```

## Caveats, so the number is not read as more than it is

1. **Outright vs replaced embeds a judgment.** A deleted test counts as REPLACED only when a
   specific successor class exists *and* a primary source asserts the relationship — the successor's
   javadoc, a stage-A commit body, or a governance probe. Name similarity alone does not qualify.
   Three entries are defensibly borderline (`RemoteKnowledgeClientHealthDeadlineTest`,
   `KnowledgeServerIntegrationTest` at 2 of 6 tests, `ChaosSuiteTest` at 2 of 7 arms); counting
   those as outright instead gives 34 / 26. Both splits are honest; the reader should know the
   choice exists.
2. **Two deletions have a non-JUnit successor** and are counted OUTRIGHT because the rule requires a
   test class: `WorkerSpawnerJvmFlagsTest` → `scripts/dev/test-dev-runner-head-java-opts.mjs`, and
   `GrpcSearchServiceDocumentSliceWireTest` → pre-existing `worker-services` slice tests.
3. **Eight deleted files are test support**, not test classes, and are excluded from the 60:
   `GrpcTestClient`, `HandleLeakDetector`, `MmfTestHarness`, `WorkerProcessManager`,
   `ManagedProcess`, `NmtMemoryTracker`, `SoakTestRunner`, `torture/FileIntruder`.
4. **`GrpcCircuitBreakerTest` is two distinct files** (`app-services` and `ipc-common`), both deleted
   at A10. The 60 treats them as two; a simple-name count gives 59.
5. **A deletion count is not by itself a benefit.** Design 16 pairs it with semantic
   non-regression for exactly this reason: 105 files removed is only good news if the properties
   they asserted either still hold or were consciously given up. The per-class accounting of which
   is which is the A12 mapping table in `stages/A.md` §0.1, and the deliberately-lost list is §10.
