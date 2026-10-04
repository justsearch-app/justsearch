# PR 0 and PR 0b publication verification (2026-09-08)

This record concerns the split-mode baseline PRs #708 and #717. It does not
certify the later one-Engine Stage B changes or clear their closed-writer failure.
The owner delegated merges to the orchestrator before publication.

## Candidates and review

PR #708 candidate `30e027c6105489d49cf5427f5a80188aee0902bb` incorporates main
`83b9e5fd523a70f7f460703be8b32c64d03c4cdc`. An independent reviewer established
that main's 94 changed paths and the original PR's 59 paths do not overlap. The
manual Codex workflow-fixture projection was added and independently reviewed.
The candidate merged at `0824e365411960597d2af2f87ba55cf17381426f`; a complete
tree diff is empty, and the landed message preserves the approved durable body.

PR #717 was fully tested at `7c8cc01a50f10c684cfe26b47274bed778c855be`. The final
candidate `a6bda3224274dd768a3ded741b28fca7b43c9de4` incorporates the published
PR #708 ancestry. Its entire tree is byte-identical to the tested candidate.
Ten squash-history conflicts were resolved by preserving that tested tree;
an independent reviewer verified both tree equalities, merge parents and the
unchanged 71-path PR 0b diff. GitHub automatically retargeted #717 to main.

The public bodies describe durable behavior. Detailed verification and independent
review are in the managed records for [#708](https://github.com/justsearch-app/justsearch/pull/708#issuecomment-5568819490)
and [#717](https://github.com/justsearch-app/justsearch/pull/717#issuecomment-5575496722).
Both records and squash previews passed before their respective queue requests.

## Fresh local verification

| Check | #708 | #717 |
|---|---|---|
| Full build, tests excluded, serialized | PASS, 55s | PASS, 2m51s |
| Full Java suite, clean test outputs and no build cache | PASS, 17m26s | PASS, 13m9s |
| Java totals | 9,301 tests; 0 failures/errors; 26 skipped | 9,337 tests; 0 failures/errors; 26 skipped |
| Preserved Java XML reports | 1,516 across 33 modules | 1,517 across 33 modules |
| Full jseval Python suite | 3,536 passed, 16 skipped; 634.96s | 3,637 passed, 16 skipped; 512.67s |
| UI typecheck and unit tests | Not separately repeated; hosted checks | PASS; 479 files, 6,445 tests |

Java command: `gradlew.bat cleanTest test --no-build-cache --continue --max-workers=1 -PtestParallelism=1 --console=plain`.
Build command: `gradlew.bat build -x test --max-workers=1 -PtestParallelism=1 --console=plain`.
Python command, from each candidate's `scripts/jseval`: `python -m pytest tests -q -p no:cacheprovider`.
All XML files, module totals, per-file hashes and raw logs were preserved before
any filtered rerun under each candidate's `tmp/lane-f-merge/pr708-full-snapshot`
or `pr717-full-snapshot`. Python logs remain beside those snapshots.

The first #708 parallel build failed the unchanged LambdaMart latency assertion:
22.3458ms against 5ms. Its original failure XML and log remain preserved. The
serialized build reran all 12 app-services integration tests successfully. No
limit or production code was changed. This is consistent with load sensitivity;
the run does not isolate load as the sole cause.

| Module | #708 tests | #717 tests | #708 skipped | #717 skipped |
|---|---:|---:|---:|---:|
| adapters-lucene | 698 | 704 | 0 | 0 |
| ai-backend | 62 | 62 | 0 | 0 |
| api-contract-projection-java | 21 | 21 | 0 | 0 |
| app-agent | 661 | 669 | 0 | 0 |
| app-agent-api | 228 | 228 | 0 | 0 |
| app-api | 195 | 199 | 0 | 0 |
| app-config | 2 | 2 | 0 | 0 |
| app-inference | 298 | 299 | 0 | 0 |
| app-launcher | 59 | 59 | 0 | 0 |
| app-observability | 382 | 382 | 0 | 0 |
| app-services | 2579 | 2588 | 3 | 3 |
| app-util | 19 | 19 | 1 | 1 |
| benchmarks | 104 | 104 | 0 | 0 |
| configuration | 288 | 288 | 0 | 0 |
| core | 72 | 72 | 0 | 0 |
| core-contracts | 38 | 38 | 0 | 0 |
| dead-code-audit | 2 | 2 | 0 | 0 |
| extension-substrate | 7 | 7 | 0 | 0 |
| gpu-bridge | 77 | 77 | 0 | 0 |
| indexer-worker | 379 | 379 | 12 | 12 |
| indexing | 84 | 84 | 0 | 0 |
| infra-core | 3 | 3 | 0 | 0 |
| ipc-common | 19 | 19 | 0 | 0 |
| ort-common | 164 | 167 | 0 | 0 |
| prompt-support | 5 | 5 | 0 | 0 |
| reranker | 68 | 68 | 0 | 0 |
| ssot-tools | 17 | 17 | 0 | 0 |
| system-tests | 99 | 99 | 0 | 0 |
| telemetry | 88 | 88 | 1 | 1 |
| test-support | 13 | 13 | 0 | 0 |
| ui | 994 | 994 | 1 | 1 |
| worker-core | 326 | 326 | 6 | 6 |
| worker-services | 1250 | 1255 | 2 | 2 |


All module rows have zero failures and zero errors. Both candidates passed the
dev-runner tests, script lint, dependency-lock and runtime-manifest closure checks,
documentation validation and generated skill/index checks. #717 also passed all
27 UI gates, the six affected contract gates, locale-invariant analysis, and all
seven generated sets excluding notices. Notices were verified by hosted CI rather
than represented as a local result. Secret scans found no leaks in either final
candidate range. Existing live fixture captures were retained; no new live
capture, benchmark campaign or performance claim was added in this publication.

## Raw-log SHA-256 inventory

These hashes identify the original local log bytes, not a newline-normalized
Git copy. The raw logs and XML snapshots are retained in the owning worktrees.

| Log | SHA-256 |
|---|---|
| #708 initial parallel build (failed) | `cf16b7926e13780fd019a36243fdbd29dd2fbfa987dcc0eb44dba64805faa62d` |
| #708 serialized build | `28807af0476f9a206fc436737eaac640910865fcecb1e9b45d6b58a4d0a3b52a` |
| #708 full Java suite | `3e30c704d5bb8b44eff962dda474359719d6e8dc506d9419176a78a875a38a80` |
| #708 full Python suite | `8715ea4beebefa842ed24359c6bdea2eb9a83215a77b3783cfade04370cefa8e` |
| #717 serialized build | `4ae7aac0965027cca0919aaf9edb6ffd5a7ff5e99ceb3654c60eb284009cba10` |
| #717 full Java suite | `ee3eaee81d7208849b1cc258fbeb280cadfdc71a3add560060f060482b3512df` |
| #717 full Python suite | `fe2ee9a4431531bfc4c79d883d22075e8e4453a63266ce6b22eb78bcd2cf964c` |

## Integration boundary

The ongoing Stage B worktree does not yet contain PR 0b. Its Codex workflow
instructions were compared directly with its own canonical reference and match
the PR 0 behavior. The initially copied PR 0b instruction update was reverted
before pushing; no executable change entered Stage B from either documentation
copy. Import the published PR 0b implementation and matching instructions at the
lane's recorded main-integration checkpoint, then review conflicts and run the
required tests. Do not infer integration from shared branch ancestry or the PRs'
publication alone.

## Hosted publication results

PR #708 passed candidate CI [34211470867](https://github.com/justsearch-app/justsearch/actions/runs/34211470867),
all 11 required contexts, and queue CI [34212322063](https://github.com/justsearch-app/justsearch/actions/runs/34212322063).
It merged on 2026-09-08 at 09:59:37 UTC. Main push CI
[34213036612](https://github.com/justsearch-app/justsearch/actions/runs/34213036612)
passed on the landed SHA. Managed review records cannot be updated after merge;
this section records the post-merge evidence without bypassing that restriction.

PR #717 passed candidate CI [34213356372](https://github.com/justsearch-app/justsearch/actions/runs/34213356372),
all 11 required contexts and the additional integration tier. Queue CI
[34214244891](https://github.com/justsearch-app/justsearch/actions/runs/34214244891)
passed, and the PR merged on 2026-09-08 at 10:21:29 UTC as
`f938c4eb2e9b487bd0965859108d239c30e8601f`. The complete landed tree equals
candidate `a6bda3224274dd768a3ded741b28fca7b43c9de4`; the approved durable
squash body and attribution are preserved. Main push CI
[34215014373](https://github.com/justsearch-app/justsearch/actions/runs/34215014373)
passed on the landed SHA, including the accessibility advisory. Both publications
have completed their candidate, queue and post-merge verification.
