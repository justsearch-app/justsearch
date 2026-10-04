# Cooperative indexing shutdown and retained dev reload, 2026-09-21

Full Engine2203 reproduced a shutdown defect: `IndexingLoop.close` interrupted the
Lucene-owning thread during extraction; the final shutdown commit could invalidate
the native file lock. Focused2204 passed but did not waive that failure.

The fix stops cooperatively, joins for the existing five-second deadline, and
throws while retaining dependent resources if the owner is still alive or the
closing caller is interrupted. No new shutdown state machine or timer is needed.
Independent review found that dev reload consumed its file request before an
incomplete close. Its existing sentinel now retains one in-memory retry bit and
retries the incumbent's close before constructing or starting replacement services.
This is transient intent owned by the reload manager, not another durable marker.
A sentinel retry was chosen over adding another file writer or scheduler.

## Evidence

Tested base: `0f1bec7e324a7aa8fab86bab0452d6167dd2d940` plus the five Java files
identified in `tmp/2215-cooperative-restored-tests-sources.json`. Raw evidence is
local to this worktree, retained through lane acceptance plus30days and exported
before worktree release. This checkpoint does not close C2 or its remaining tiers.

| Run | Command and result |
| --- | --- |
|2208|`:modules:app-engine:test --console=plain --info`:340 cases/59 suites, zero failures/errors/skips. Fresh, including unchanged pacing, soak/reopen and real bulk restart. Predates the dev-reload retry change.|
|2210|`:modules:worker-services:spotlessApply :modules:worker-services:test :modules:worker-services:pmdMain :modules:worker-services:pmdTest --console=plain`:1402 cases/259 suites, zero failures/errors, two existing skips. Fresh. PMD test found four redundant qualifiers, corrected next. Predates timeout regression.|
|2211|Worker `IndexingLoopRestartTest` and indexer `DevReloadManagerTriggerTest`, plus both module formatting and relevant PMD:16 cases/3 suites, zero failures/errors/skips; fresh and all checks green.|
|2213|Same focused classes, temporarily restoring owner interruption/resource-close-on-failed-join and removing retained reload detection:four expected failures. Three shutdown guards and one reload retry guard fail. Both production sources restored byte-for-byte in `finally`.|
|2214|`build -x test -PskipWebBuild=true` plus requested PMD tasks:whole compile/static build green. This command also named test tasks, but `-x test` excluded them; no test execution credited.|
|2215|`:modules:worker-services:test --tests '*IndexingLoopRestartTest' :modules:indexer-worker:test --console=plain`:702 represented cases/105 suites, zero failures/errors,15 existing skips. Full indexer task fresh; restored worker regression results reused from2211 cache, not falsely claimed as a fresh run.|

Logs use the run prefixes `tmp/2208-engine-cooperative-close-full`,
`tmp/2210-worker-cooperative-close`, `tmp/2211-cooperative-reload-focused`,
`tmp/2213-shutdown-negative`, `tmp/2214-cooperative-restored-build` and
`tmp/2215-cooperative-restored-tests`. Each test run has copied XML/counts;
2213 also retains exact assertion failures and the reproducing Python script.
2210 skips a POSIX symlink fixture and optional crashed-index SPLADE corpus;
2215 skips12 unavailable ONNX fixture cases and3 platform/filesystem cases.

Independent review's timeout/reload findings are implemented. The timeout test
holds the actual owner past five seconds; it does not substitute closer interruption.
The final-commit test performs a real loop write seam and explicitly verifies the
shutdown commit, preventing an idle-loop wrong-reason pass. Reload regression proves
consumed external request, retained retry, no early replacement, and ordered successful
close/replacement start. Negative2213 refutes each new guard.

## Hosted checkpoint limitation

[CI35583668414](https://github.com/justsearch-app/justsearch/actions/runs/35583668414)
reports overall success but its required integration job fails all three retries of
installed migration: incarnation1 exits1 rather than requested4. It tests the pushed
connected checkpoint through merge checkout4bf5e34, before this shutdown correction.
Downloaded artifacts and failures are under `tmp/2216-hosted-connected*`; diagnosis
and successful corrected hosted proof remain required. Do not credit the aggregate.

## Persistent workflow changes

The user's requested agent-system improvement is in the existing canonical
`docs/reference/contributing/agent-workflow.md`: retain an owner/evidence map,
checkpoint connected slices before adjacent work, and refresh current status in
place. Existing delegation/review rules were already explicit and were not copied
into new hooks or broader prompts. Both harness review/closeout skills were checked.
The handoff now has a compact current section/map; superseded detail is separate
history and the resumption document routes current status to that single owner.

Docs index and skill regeneration/checks, canonical links, shared instruction
projection and Codex parity all pass. Prompt-surface inventory is retained in
`tmp/2212-prompt-surface-inventory.txt`; these checks prove repository consistency,
not instruction delivery or future compliance. No main-checkout files changed.
