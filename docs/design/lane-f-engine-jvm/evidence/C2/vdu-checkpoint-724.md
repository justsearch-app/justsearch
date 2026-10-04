# C2-2 VDU generation integrated checkpoint

September12, Windows/Temurin Java25. Code and tests at55b8aeb15, containing
b532a56ee's generation correction and the reviewed real Engine migration fixture.
f989c59d1 adds only the following offline-owner design while the build runs; no
production/test inputs changed during verification.

`gradlew.bat build pmdAll -PtestParallelism=1 --max-workers=4 --console=plain`

| Run | Result |
| --- | --- |
| 722 | RED at indexer-worker spotlessJavaSourcesCheck: mixed local line endings in SwitchBufferConcurrentReplayTest. Test tasks already reported2,126 cases/293 suites with zero failures/errors/skips, including214 freshly executed Engine cases. Later tasks were not run; this is not a full pass. |
| 723 | Affected worker-core/worker-services/indexer-worker/app-engine spotlessApply passes. It normalizes local line endings; Git reports no content diff. |
| 724 | PASS full build and PMD,11m.10,034 cases/1,643 suites, zero failures/errors,35 skips.8 test tasks execute,1 FROM-CACHE,29 UP-TO-DATE.366 build tasks:28 execute,1 FROM-CACHE,337 UP-TO-DATE. |

724's executed test tasks are app-launcher, dead-code-audit, indexer-worker,
system-tests, ui unit/integration, worker-core and worker-services. ort-common
reuses cache; app-engine reuses722's complete214-case run. All other reuse is
listed in the retained counts file. This does not claim every test executed anew.

Hosted [CI34712074865](https://github.com/justsearch-app/justsearch/actions/runs/34712074865)
passes55b8aeb15, including the new Engine fixture in its test inputs. The original
production generation checkpoint also has green CI34711067677. Named stress714
passes2 unchanged cases with documented reuse; adding an Engine fixture does not
change those adapter/ORT stress inputs. The broad stress selector's empty-module
limitation remains documented in checkpoint694, without weakening the selector.

Together with [focused/negative proof](vdu-generation-proof.md) and
[real Engine proof](engine-vdu-migration-replay.md), this closes the current VDU
generation prerequisite's local integrated and hosted verification. It does not
close C2-2: actual offline ownership/completion, recorded ingestion, direct HTTP
acceptance and installed/live producer proofs remain. D1 still owns carry-forward
and generation activation/retirement under accepted writes.

## Evidence access

Logs, copied XML and counts are under this worktree's
`tmp/c2-2-vdu-generation-full-{722,724}{.txt,-xml/,-counts.json}`. Formatter log:
`tmp/c2-2-vdu-generation-format-723.txt`. Capture utility:
`tmp/c2-2-capture-generation.py`. Retain through lane acceptance plus30 days;
export before releasing the worktree. Read build logs alongside XML counts,
particularly for722's formatting failure.
