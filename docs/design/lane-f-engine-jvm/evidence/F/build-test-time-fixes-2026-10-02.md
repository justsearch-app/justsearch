# Lane F build/test time fixes — 2026-10-02

Implemented as uncommitted edits on `codex/lane-f-buildfix`, base `103946f6f`.
Scope and rationale: [investigation recommendations 1, 2 and 4, and CI coverage gap](build-test-time-investigation-2026-10-02.md).
The owner authorized edits and Node checks only. No Gradle, npm, stack, installed
Engine, sub-agent, commit or push was run. JVM and hosted proof remain pending.

## Implementation and acceptance

1. Native pointer witness: the existing `TwoPhaseBarrier` observer reads the
   generation state under the production reentrant state lock on the migration
   thread, before publishing `afterPointerReached`. It snapshots the exact B
   journal, all seven projection witnesses for each survivor (six stored fields
   plus exact document count), and refreshed deletion counts for all three
   victims. Immutable records/lists are published through `AtomicReference`.
   The test thread surfaces observer failures and checks the same expected
   values before `cancelAfterPointerCommit`. Original `WAIT_MS`, mutation,
   recovery, owner close/reopen, journal replay and retirement checks remain.
   Both barrier timeout branches record their occurrence; the native test checks
   before cancellation and after cutover exit, and the recorded test checks after
   exit. The post-exit checks cover a timeout racing cancellation.
2. PMD cache marker: removed the obsolete comment and opt-out only. Static
   reading found no launcher-specific PMD classpath binding to refactor. Task,
   producer dependencies, rules, classpath and `check`/`pmdAll` wiring remain.
   Serialization/cache compatibility is unproven until strict Gradle execution.
   No dependencies changed, so no lockfile regeneration is required.
3. CI execution: added `:modules:app-engine:test` to platform-contracts in both
   the workflow and policy, including the policy's local command. No task was
   moved, emptied or excluded, and no budget was raised.
4. ArchUnit: `productionImporterSeesTheEncoderOwner` is now a static `@ArchTest`
   receiving `JavaClasses` from the unchanged `@AnalyzeClasses` import. The
   planted dependency still uses its separate fixture import and JUnit test.

## Shard decision

No existing advisory walltime budget fits the Engine task alone, even at the
estimated 9 minutes after the lock-cycle fix: app-ui 450s, search-worker 390s,
platform-contracts 360s. Every shard also has a 300s advisory summed-suite budget.
The September 4 walltime register records maxima of 390s, 330s and 248s
respectively (`scripts/ci/ci-walltime-policy.v1.json`). Platform-contracts is
therefore the lightest recorded lane; 248s plus 540–720s is 788–968s, below the
workflow's 1500s hard timeout. This is a planning estimate, not hosted proof;
runner startup, compilation, model availability and scheduling can differ.

Proposal: collect hosted attribution for this shard, then review its advisory
walltime and summed-suite budgets against the measured distribution. If the
1500s ceiling lacks 20% headroom, propose a dedicated Engine shard with explicit
required-check ownership rather than omit coverage. Budget/check expansion is
outside these edits; the current advisory overrun remains visible.

## Local evidence

All commands below exited 0 against these edits:

```text
node --check scripts/ci/check-workflow-triggers.mjs
node --check scripts/ci/verify-unit-test-shard-policy.mjs
node scripts/ci/check-workflow-triggers.mjs
node scripts/ci/verify-unit-test-shard-policy.mjs
node scripts/ci/test-check-workflow-triggers.mjs
node scripts/ci/test-verify-unit-test-shard-policy.mjs
git diff --check
```

The guard tests retain negative cases for task-list mismatch and workflow trigger
drift. This proves policy consistency and guard behavior, not hosted execution.

## Pending root verification packet (investigation §7 adjusted)

Preserve pre-edit XML/logs before rerunning. Record the candidate diff/revision,
JDK/OS, model state, skips, task identities and raw logs. Run focused commands one
at a time under the owner's grant. The unchanged build commands intentionally
share the graph and attribution path; archive outputs elsewhere between runs.

```powershell
./gradlew.bat :modules:app-engine:spotlessCheck :modules:app-launcher:spotlessCheck -PskipWebBuild=true --max-workers=2 --console=plain
./gradlew.bat :modules:app-engine:test --tests "io.justsearch.app.engine.EngineNativePointerBootMutationTest" --rerun-tasks --no-build-cache -PskipWebBuild=true --max-workers=2 --console=plain
./gradlew.bat :modules:app-engine:test -PskipWebBuild=true --max-workers=2 --console=plain
./gradlew.bat :modules:app-launcher:test --tests "io.justsearch.app.launcher.NativeInferenceContainmentTest" --rerun-tasks --no-build-cache -PskipWebBuild=true --max-workers=2 --console=plain
./gradlew.bat :modules:app-launcher:test -PskipWebBuild=true --max-workers=2 --console=plain
./gradlew.bat :modules:app-launcher:pmdIntegrationTest -PskipWebBuild=true --max-workers=2 --configuration-cache-problems=fail --console=plain
./gradlew.bat :modules:app-launcher:pmdIntegrationTest -PskipWebBuild=true --max-workers=2 --configuration-cache-problems=fail --console=plain
./gradlew.bat build -PskipWebBuild=true --continue --max-workers=2 --configuration-cache-problems=fail --console=plain --profile -PjustsearchBuildAttributionTasksJson=tmp/build-test-investigation-task-timing.json
./gradlew.bat build -PskipWebBuild=true --continue --max-workers=2 --configuration-cache-problems=fail --console=plain --profile -PjustsearchBuildAttributionTasksJson=tmp/build-test-investigation-task-timing.json
./gradlew.bat pmdAll -PskipWebBuild=true --max-workers=2 --configuration-cache-problems=fail --console=plain
```

Before claiming the performance result, repeat focused/warm comparisons at least
three times on an idle machine with worker counts and model state held constant.
Require both native-pointer cases, every original physical/durable witness, and
no recorded timeout. Plant an incorrect pointer/journal snapshot in an isolated
verification checkout and require assertion failure. Require the ArchUnit
presence guard to fail on a graph without EncoderSet, its planted boundary
violation to fire, and all three properties to be discovered across the JUnit
and ArchUnit engines. Confirm the second unchanged strict PMD/build invocation
reuses configuration cache, and changed PMD input invalidates correctly. Compare
the full affected-module case identities/skips with baseline. Hosted execution
and advisory-budget review remain with the root owner; installed Engine tiers
still require separate authorization and are not part of this packet.

## Root verification (2026-10-02, at `9b8412533`)

Run by the lane root on Windows 11 / OpenJDK 25.0.2, one Gradle build at a
time, idle stack. Logs under the worktree's gitignored `tmp/bf/`.

| Check | Result |
| --- | --- |
| `spotlessCheck` (app-engine, app-launcher) | pass |
| `EngineNativePointerBootMutationTest`, `--rerun-tasks --no-build-cache` | 2/2 pass, 0 skipped, suite 28.6 s (was a 180 s timeout green) |
| Planted wrong journal snapshot in the post-pointer witness | fails as required: "post-pointer cancellation must retain the exact B journal snapshot"; file restored |
| `:modules:app-engine:test` (full) | pass, 8 min 20 s |
| `NativeInferenceContainmentTest`, no build cache | 3/3 pass (JUnit and ArchUnit engines) |
| `:modules:app-launcher:test` (full) | pass |
| `pmdIntegrationTest` twice, `--configuration-cache-problems=fail` | pass; second run "Configuration cache entry reused" |
| `check-workflow-triggers`, `verify-unit-test-shard-policy` and its test | pass |
| `build -x test` | pass |

Not run here: the three-run warm performance comparison, the planted
EncoderSet-absence graph for the ArchUnit presence guard, and hosted execution
of the new platform-contracts entry (runs on the next push).
