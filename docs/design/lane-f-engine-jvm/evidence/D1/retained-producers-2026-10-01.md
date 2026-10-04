# D1 retained producers ? 2026-10-01

Status: implemented locally on `codex/lane-f-retained`, base `56df784ad`; all requested local
Gradle proof passed under the orchestrator grant. No commit, push, dev stack or Engine startup.

Scope: D1-9, D1-12 and D1-17's remaining retained-state producer connections.
The referenced closure-review-2026-10-01.md is absent from this checkout; C1-12, D1 and
current owners establish the contract. D2 cursor and pinned-reader rows are unchanged.

## Ownership decision

Project existing physical owner state into the existing RetainedStateBudget rather than
adding a second allocation counter, persistent marker, or lifecycle state machine.
Disk measurement and build admission share the directory predicate, including incomplete
retirement directories and excluding diagnostic corruption backups. Unknown disk I/O is
an error, never a measured zero. The register's cap must match the manager's supported two slots.

Native accounting is the maximum of distinct unclosed EncoderSet and QueryRoleSet owners:
the index/query partitions have independent lifetimes, and each domain is capped at two.
Identity deduplication prevents multiple serving leases/views from inflating the count.
The existing runtimeSwapLock serializes admission and native composition. Exact unpublished
CandidateModels and PreparedQueryRoleSettings references retain ownership through preparation
and failed close; successful publication is deduplicated against serving/recovery references.
This closes a previously unowned failed candidate cleanup path without a second resource
registry. Ordered shutdown retries these same owners; native quiescence includes them.
Unknown boot initialization stays unknown. A third role-domain set is refused before composition
with `Retained-state co-resident-encoders cap=2` and the actual index/query counts.
Device-footprint admission remains an additional constraint.

The budget invokes projected producers outside its own monitor to avoid reversing owner/budget
lock order. Permit producers such as attempted-configurations retain their existing behavior.
Projected resources cannot also acquire synthetic budget permits.

## Acceptance and verification

| Requirement | Implementation / regression | Proof status |
|---|---|---|
| Real physical count, two during migration/promotion and one after predecessor deletion | IndexGenerationManager.retainedGenerationCount; IndexGenerationManagerRestartTest | Passed focused regressions |
| Disk bound/refusal includes abandoned and incomplete deletion payload; no cap clamping | Shared isRetainedRepresentation predicate; new disk regressions; existing NativeGenerationPromotionTest refusals | Passed focused regressions |
| Real native count: BESIDE two, one after abort or publication/lease drain | KnowledgeServer.retainedEncoderCount; KnowledgeServerQuerySettingsOwnerTest | Passed focused regressions |
| Third native set refused before composer; held retired index sets included | KnowledgeServerQuerySettingsOwnerTest; KnowledgeServerDeferredRetirementTest | Passed focused regressions |
| Failed native retirement still counted and owned for retry | Existing native-close-error regression extended with count assertion; shutdown/quiescence ownership | Passed focused regressions |
| Register producers/caps and root wiring | EngineResourcePolicyTest (C1-12 register gate); EngineRootAuthorityTest; RetainedStateBudgetTest | Passed focused regressions |
| Packaged register byte-equals source | EngineResourcePolicyTest | Passed app-engine gate (source/package equality) |

No separate retained-state Node/discipline gate exists: C1 explicitly makes
EngineResourcePolicyTest the register coverage gate. Run it with the app-engine test task.
The owner granted the focused builds, PMD for every touched module, and both final whole
worker suites. Static checks accompany the Java proof.

## Static checks completed on the uncommitted implementation

- `git -c core.safecrlf=false diff --check`: pass.
- Source register comparison against HEAD: both D2 rows and attempted-configurations are
  byte-equivalent as parsed rows; neither D1 awaitingProducer marker remains.
- `node scripts/docs/llmstxt-generate.mjs`: regenerated (no index content change).
- `node scripts/docs/llmstxt-generate.mjs --check`: pass (116 indexed docs).
- `node scripts/docs/skills-sync.mjs --check`: pass (5 generated skills, 9 sources).
- `node scripts/docs/verify-canonical-doc-links.mjs`: pass (157 files).
- Both harness skills reference the inference explainer; neither embeds the changed ownership
  paragraph, so no skill regeneration or behavioral edit is required.

## Approved Java verification

All commands ran sequentially against the uncommitted implementation above. A subsequent
indexer-worker formatting refinement only parenthesized three new precedence-warning expressions;
its focused suite recompiled the final Java sources before PMD and the whole suites.

| Check | Tests / checks | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| Focused core (including automatic architecture guards) | 7 | 0 | 0 | 0 |
| Focused worker-core | 28 | 0 | 0 | 0 |
| Focused indexer-worker (including automatic guardrails) | 92 | 0 | 0 | 0 |
| App-engine register gate and root wiring | 7 | 0 | 0 | 0 |
| PMD main/test across all four modules | 8 reports, 0 violations | 0 | 0 | 0 |
| Whole worker-core suite | 430 | 0 | 0 | 7 |
| Whole indexer-worker suite | 1061 | 0 | 0 | 15 |

Formatting and all four modules' `compileTestJava` passed. Worker-core skips are six model
asset cases (one vocabulary assumption and five embedding-with-spans cases), and one symlink
privilege assumption. Indexer-worker skips are 12 embedding integration cases whose shared
model-discovery setup aborts without assets, and three filesystem-fixture assumptions
(inaccessible-root enforcement and two symlink privileges). No retained-state regression skipped.
Whole worker-core ran once in 1m27s (423 passed); whole indexer-worker ran once in 9m06s
(1046 passed). Both Gradle tasks exited zero. Counts come from the JUnit XML, including
skipped cases in each total, rather than counting console lines.

The sandbox's default Gradle home could not download the wrapper distribution; Java ZIP
filesystem access also rejected the external read-only distribution/dependency cache. The
cached distribution and dependency cache were copied into ignored `.gradle/agent-distribution`
and `.gradle/agent-user-home`. No external cache or repository build configuration was changed.
The first focused worker run had 24 temp-directory cleanup failures under the external Windows
TEMP location; evidence remains in `.gradle/retained-evidence/worker-core-temp-failure`. The rerun
used writable `.gradle/agent-tmp` and all 28 focused tests passed. Existing compiler warnings,
Gradle deprecations, dependency Unsafe warnings and sandbox WMI access warnings remain advisory.

Every successful command used this invocation prefix (PowerShell), then the task line below:

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=' + (Join-Path (Get-Location) '.gradle/agent-tmp')
& '.\.gradle\agent-distribution\gradle-9.6.1\bin\gradle.bat' --offline --no-daemon --gradle-user-home .gradle/agent-user-home <tasks>
```

Exact task lines, each a separate sequential invocation:

```text
:modules:core:spotlessApply :modules:worker-core:spotlessApply :modules:indexer-worker:spotlessApply :modules:app-engine:spotlessApply
:modules:core:compileTestJava :modules:worker-core:compileTestJava :modules:indexer-worker:compileTestJava :modules:app-engine:compileTestJava
:modules:core:test --tests '*RetainedStateBudgetTest'
:modules:worker-core:test --tests '*IndexGenerationManagerRestartTest' --tests '*NativeGenerationPromotionTest' --tests '*IndexGenerationRetirementTest'
:modules:indexer-worker:spotlessApply
:modules:indexer-worker:test --tests '*KnowledgeServerQuerySettingsOwnerTest' --tests '*KnowledgeServerDeferredRetirementTest' --tests '*KnowledgeServerQueryPreparationDeviceTest' --tests '*KnowledgeServerQueryPreparationTransactionTest' --tests '*KnowledgeServerCloseCompletionTest' --tests '*KnowledgeServerRecoveryCloseTest' --tests '*EncoderSetTest'
:modules:app-engine:test --tests '*EngineRootAuthorityTest' --tests '*EngineResourcePolicyTest'
:modules:core:pmdMain :modules:core:pmdTest :modules:worker-core:pmdMain :modules:worker-core:pmdTest :modules:indexer-worker:pmdMain :modules:indexer-worker:pmdTest :modules:app-engine:pmdMain :modules:app-engine:pmdTest --continue
:modules:worker-core:test
:modules:indexer-worker:test
```

Accessible local artifacts (kept separately so whole suites do not overwrite focused proof):

- `.gradle/retained-evidence/focused/{core,worker-core,indexer-worker,app-engine}`:
  JUnit XML and `counts.json`; 134 tests total, zero failures/errors/skips.
- `.gradle/retained-evidence/pmd/{core,worker-core,indexer-worker,app-engine}`:
  all eight PMD XML reports and aggregate `counts.json`.
- `.gradle/retained-evidence/full/{worker-core,indexer-worker}`: whole-suite XML and
  `counts.json` (both final suites complete).
- `.gradle/retained-{spotless,spotless-refinement,compile,core-focused,worker-core-focused,indexer-worker-focused,app-engine-focused,pmd,worker-core-full,indexer-worker-full}.log`:
  full sequential command output, UTF-16 from PowerShell Tee-Object.

These ignored artifacts remain accessible in this worktree for the orchestrator handoff;
retain them through review/merge, then remove them with the worktree. No hosted or installed
Engine verification was requested or run. This proof closes only these retained-state producer
items, not the broader D1 stage or its separate integration obligations.

## Closeout

The final documentation index, generated skills, canonical link checks and diff whitespace
check passed again after verification. The implementation and its logs remain in this assigned
worktree for the lane orchestrator; commit/push/publication are explicitly withheld by the owner.
No task-specific Java verification remains. Missing real-model assets and filesystem privileges
are named suite limitations above, not retained-state passes or D2 completion.

`world-state.mjs` observed this worktree as ACTIVE with 15 dirty paths; the lifecycle register
still carries an older RELEASED marker. No ownership or publication marker was changed during
this assigned task. The session-closeout process sweep found three pre-existing registrations:
two ui-shot helpers were retained because process identity could not be verified in the sandbox,
and one ownerless OTLP singleton was reported and retained. Nothing was reaped; no helper was
started by this task.
