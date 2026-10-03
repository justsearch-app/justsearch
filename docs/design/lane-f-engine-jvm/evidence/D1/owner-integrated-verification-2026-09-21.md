# Captured-owner integrated verification

Run2298 passed on Windows/JDK25 at source revision
`6c95d7989e1e8e154afa61b69ece3b6b0205c5ab`. The later `b4d01c1cc` commit changes
only workflow documentation/skills. Compiled sources stayed unchanged throughout
the30m2s run. This is owner-wiring proof, not D1 stage acceptance or live model-query
proof for D1.

```powershell
.\gradlew.bat :modules:telemetry:test :modules:configuration:test :modules:adapters-lucene:test :modules:reranker:test :modules:worker-core:test :modules:worker-services:test :modules:indexer-worker:test :modules:app-services:test :modules:app-engine:test :modules:ui:test -PtestParallelism=1
```

| Module | Cases | Skips | Execution |
| --- | ---: | ---: | --- |
| configuration | 287 | 0 | UP-TO-DATE |
| adapters-lucene | 737 | 0 | UP-TO-DATE |
| telemetry | 95 | 1 | fresh |
| reranker | 69 | 0 | fresh |
| worker-core | 389 | 7 | fresh |
| worker-services | 1408 | 2 | fresh |
| indexer-worker | 726 | 15 | fresh |
| app-services | 3005 | 3 | fresh |
| app-engine | 352 | 0 | fresh |
| ui | 1302 | 1 | fresh |

Total:8370 represented cases in1326 suites, zero failures/errors,29 skips.
7346 cases belong to freshly executed tasks;1024 are reused unchanged results.
The skipped cases comprise19 asset/opt-in model checks, five filesystem/platform
checks, two external collector/dataset checks, and three existing explicitly
deferred composition guardrails. Skips are not successful proof. This run includes
actual ONNX long-document execution but does not establish standard-model search
quality or a live API/model query. Existing Unsafe/rrd4j/protobuf and LightGBM
warnings remain visible; no warning suppression was added.

Retained in this worktree's `tmp/` through lane acceptance plus30days, export before
worktree deletion:

- `2298-owner-projection-integrated.txt`: full Gradle output.
- `2298-owner-projection-integrated-counts.json`: exact command/revision/task counts.
- `2298-owner-projection-integrated-xml/`: XML copied before subsequent test runs.
- `2298-owner-projection-integrated-skips.json`: individual skipped cases.
- `2298-owner-projection-integrated-sources.json`: empty dirty-source inventory at
  start; the clean source commit above identifies the tested files.
- `2298-worker-core-progress*.txt` and `2298-app-engine-progress.txt`: bounded
  thread diagnostics showing native model tests and concurrent read/write pacing.

Earlier2296 supplies the successful full static checks and focused three-review-fix
regressions;2297's deliberate tracing-cleanup omission failed for the surviving
exporter thread and was restored byte-exactly. The new conditional-publication
changes began only after2298 completed and require their own proof.
