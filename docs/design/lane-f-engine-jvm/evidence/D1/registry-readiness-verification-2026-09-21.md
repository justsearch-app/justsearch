# D1 registry sampler and manifest connection

Base: `ad9ac55ba` plus the captured source inventories, Windows, 2026-09-21.
D1 acceptance,
schema2 HTTP/host migration and mutable capability retirement remain open.

## Sampler ownership and proof obligations

EngineRoot decorates the actual index handle with the existing stateless reason
retention policy and supplies that same handle to the status sampler through
HeadlessApp, LocalApiServer.Builder and CoreApiAssembly. Sampling uses structural
client attachment, so initial PENDING does not prevent the RPC that establishes
READY. Registry publication validates the complete pre-observation snapshot.

Fresh success requires API READY, successful contact, indexHealthy and freshness.
It preserves physical ABSENT/RELOADING; FAILED, UNAVAILABLE and STARTING can recover.
Failed conjunctions demote only READY. Ordinary cached reads may demote stale
READY without an RPC, but cannot promote a new physical start from an older cache.
Contact failures retain `worker.lost`; API/health failures use
`worker.unavailable` with evidence identifying the failed input.

Two fresh observations on unchanged READY would otherwise have the same registry
revision, allowing an older failed RPC to finish last. Fresh debug/background
sampling and tap delivery therefore share a sampling lock; cached reads bypass
that lock. Cache acceptance/publication uses a separate short synchronized section.
This uses serialized ownership instead of a new lifecycle epoch or state store.

Independent review proposed suppressing cached demotion while an RPC was active;
root rejected that because a wedged RPC would leave the host's component READY.
The retained design demotes immediately, discards the invalidated RPC, and uses
the existing trigger's one queued follow-up to recover. Review confirmed the
actual trigger clears its pending flag before calling the sampler. The regression
uses the real trigger and handler, blocked RPC, stale read before release, initial
and final FIFO barriers, exactly three RPCs and surfaced callback failures.

Other regressions cover each conjunction input independently, slow successful
contact, preserved physical reasons, API/index transition races, cached restart
protection, and optional compatibility degradation without essential index failure.
The existing sampler/staleness fixtures now model structural client attachment;
their assertions on RPC count, tap delivery and staleness remain intact.

## Manifest ownership

The publisher derives aggregate lifecycle from the same pure snapshot projection.
Its one revision high-water mark advances before no-op detection and fallible I/O.
Older snapshots cannot regress either aggregate or sibling projections; equal
revision retry remains possible after failed disk writes. Actual boot now wires one
registry subscription before its initial snapshot read. Legacy string/capability
aggregate writers are retired; worker, AI, mode and chat projections cannot set the
aggregate. The publisher owns subscription shutdown and rejects late callbacks.
Transient initial write failures retain the subscription for the next revision;
unexpected projection defects release it. STARTING projects pending on the legacy
worker axis instead of retaining a prior ready value.

Root corrected an audit finding after the initial integration run: unrelated
component revisions must not reset worker/AI readyAt or write unchanged projections.
Ready timestamps now come from each component's stateSince, and equal projections
are no-ops. The actual listener test pins epoch equality, unchanged metadata causing
zero writes, API-only change causing one aggregate write, and a new recovery epoch.

## Runs

- 2321: initial sampler/publication overload, 65 cases / seven suites, one fresh
  UI test task, zero failures/errors/skips; affected format/PMD passed in59s.
  Predates fresh-request serialization, additional concurrency/evidence tests and
  the actual manifest listener migration. It is not proof of the final diff.
- 2322:145 cases /18 suites, both tasks fresh.13 EngineRoot/trigger cases pass;
 132 UI cases have one failure: an old JSON-shape fixture relied on the retired
 worker-axis aggregate write. Its setup now supplies a real READY registry
 snapshot and retains the ready-state assertion in the canonical wire vocabulary.
 Full static collection found one unused assignment and seven test-source PMD
 issues; all unrelated static tasks passed. Runtime2m12s.
- 2322b:132 UI cases /13 suites, fresh, zero failures/errors/skips after fixture
 and readiness-epoch corrections. One remaining fully qualified List type failed
 test PMD; root corrected it.2322c passes all required UI static checks in7s.
- 2323 negative: bypassing sampler full-snapshot CAS and manifest revision rejection
 produces four intended failures: API/index races overwrite STARTING/FAILED, old
 manifest lifecycle regresses ERROR to READY, and sibling delivery gains a stale
 READY entry. Seven cases /four suites, four failures, no errors/skips.
- 2324 negative: removing fresh sampling ownership fails the actual monitor-owner
 contention assertion. Four cases /three suites, one intended failure.
 Both negative runs restore both production files byte-exactly in finally.
 The driver's first Windows launch used an invalid forward-slash batch command;
 it ran no Gradle tests, its capture assertion rejected that non-build output,
 and finally restored sources. The corrected driver uses the absolute batch path;
 the launch failure is retained separately and is not negative-test evidence.
- 2325 restored:132 UI cases /13 suites, zero failures/errors/skips, FROM-CACHE
 matching the restored source/compiled inputs; not a fresh test run. UI static
 checks pass. The unchanged13 EngineRoot/trigger cases retain2322 fresh proof.
- Runtime-manifest closure passes3691 scanned files/zero violations; readiness
 reason-code gate passes58 emittable codes/zero exemptions. Documentation validation
 passes after handoff archival (commands below).

Independent review found no remaining concrete sampler defect after the stale-read
trigger and initial/final FIFO proof corrections. Root inspected each retained
negative XML failure, not only the Gradle failure exit. This is focused connection
proof, not fresh whole-repository tests, live HTTP/model proof or D1 acceptance.

Commands:

```powershell
# 2322
.\gradlew.bat :modules:app-engine:test --tests '*EngineRoot*Test' --tests '*ComponentReadinessTriggerTest' :modules:ui:test --tests '*IndexReadinessPublicationTest' --tests '*WorkerStatusSamplerTest' --tests '*StatusReadinessStalenessTest' --tests '*HeadlessAppComponentRegistryCoverageTest' --tests '*RuntimeManifest*Test' --tests '*StatusLifecycle*Test' spotlessCheck pmdAll -PtestParallelism=1 --continue
# 2322b and restored2325
.\gradlew.bat :modules:ui:test --tests '*IndexReadinessPublicationTest' --tests '*WorkerStatusSamplerTest' --tests '*StatusReadinessStalenessTest' --tests '*HeadlessAppComponentRegistryCoverageTest' --tests '*RuntimeManifest*Test' --tests '*StatusLifecycle*Test' :modules:ui:spotlessCheck :modules:ui:pmdMain :modules:ui:pmdTest -PtestParallelism=1 --continue
# 2323/2324
python -X utf8 tmp/2323-2324-sampler-negatives.py
node scripts/ci/check-runtime-manifest-closure.mjs
node scripts/ci/check-readiness-reason-codes.mjs
node scripts/docs/docs-validate.mjs
```

Artifacts: worktree `tmp/2321-sampler-manifest*`, `tmp/2322-registry-connected*`,
`tmp/2322b-registry-corrected*`, `tmp/2322c-static-restored.txt`,
`tmp/2323-publication-negatives*`, `tmp/2323-launch-failure.txt`,
`tmp/2324-sampler-ownership-negative*`, `tmp/2325-registry-restored*` (logs,
source inventories, copied XML, counts and skips). Retain through lane acceptance plus30days and export
before deleting the worktree. Existing compiler advisories are preserved, not
suppressed. D1 live API/real-standard-model and required platform proof remain open.

## Agent context correction

The active handoff had grown to1850 lines, including obsolete headings labeled
current and superseded approval/next-work text. It is now109 lines of current
authorization, owners, evidence and next actions; its full historical content is
preserved beside it in `handoff-history-through-2026-09-21.md` with an explicit
historical banner. Documentation validation passes after correcting the archive's
duplicate H1. No extra always-loaded rule was added. Existing path-discovery and
bounded-read rules still require better execution; rewriting guidance does not
count as compliance.
