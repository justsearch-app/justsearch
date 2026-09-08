# Stage B takeover verification

Recorded 2026-09-08 at lane head `1ffd6cc2dc00d7ba750d40c3d9d8189423144b7d`.
This is a dated run record, not the stage-B checkpoint. B11-B17 and the review fixes
remain open.

## Long-document forensic control

The inherited failure does not establish an accidental CPU fallback or an idle,
deterministic timeout. `OnnxEmbeddingEncoderLongDocForensicTest` explicitly builds
an FP32 CPU session: its helper passes no GPU config, disables deferred CPU and
GPU retry, and gives the assembler an arbiter that refuses GPU. The 30-second
deadline comes from `JvmBaseConventionsPlugin`'s JUnit default, not an annotation
on this method. The long case runs three encodes of the same 5,141-token input.

Source anchors at the recorded head:

- `modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/onnx/OnnxEmbeddingEncoderLongDocForensicTest.java:129`
  (CPU session helper), and `:215` (long case).
- `build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt:118`
  (default deadline).

The relevant encoder implementation, test, and `ort-common` source have no diff
between `origin/main` at `83b9e5fd523a70f7f460703be8b32c64d03c4cdc` and the lane.
Both fresh controls used the same model asset, SHA-256
`5b9f03fdc40350a78fa064b4cfb6bf9a229a7c40aa87736f537e3ebd00aa2b86`.

Command, run sequentially in a clean main comparison worktree and the lane:

```text
./gradlew.bat :modules:worker-core:test --tests '*OnnxEmbeddingEncoderLongDocForensicTest' --no-build-cache -PtestParallelism=1 --console=plain
```

| run | XML suite timestamp (UTC) | long case seconds | tests | failures | skipped |
|---|---|---:|---:|---:|---:|
| inherited lane failure | 2026-09-08T05:16:31.534Z | 68.404 | 2 | 1 | 0 |
| fresh clean main control | 2026-09-08T05:35:44.792Z | 16.476 | 2 | 0 | 0 |
| fresh lane control | 2026-09-08T05:37:56.846Z | 16.939 | 2 | 0 | 0 |

Both fresh runs passed with cosine agreement of 1, without changing a timeout,
test, model, or provider choice. The inherited failure remains an unreproduced
timing event; these two controls do not establish its cause. There is no provider
log establishing CUDA fallback, and the explicit CPU setup makes that explanation
inapplicable to this test.

Local raw logs and preserved XML are under `tmp/lane-f-takeover/` in the lane and
the `lane-F-main-verification` comparison worktree. The full-suite result must be
captured before another targeted run replaces a module's XML output.

## Inherited test-count discrepancy

A read-only audit of the previous implementers' transcripts reconstructs the
exact discrepancy. B6 recorded 1,514 XML classes, 9,331 tests, 25 skipped and no
failures or errors. B10's full run failed at `worker-core:test`, after which two
targeted reruns each invoked `worker-core:cleanTest`: first two ONNX classes,
then only `OnnxEmbeddingEncoderLongDocForensicTest`. The reported B10 inventory
was taken **after** those reruns, not from the original full run.

The unchanged worker-core tests previously produced 83 XML files with 335 tests
(`evidence/A/a20-suite-and-gates.md:38`). The final targeted rerun left one file
with two tests, removing 82 files and 333 tests from the inventory. B7 added one
`EngineSupervisionPolicyTest` class with three tests. Therefore:

```text
1514 - 82 + 1 = 1433 XML files
9331 - 333 + 3 = 9001 tests
```

The expected complete inventory at the recorded lane head is **1,515 XML files
and 9,334 tests**. The smaller inventory does not establish that downstream tasks
were aborted; it is fully explained by overwriting worker-core's results. The
original full run did fail, and its failure count cannot be replaced by the
last targeted run's one-failure count. No original B6 per-module table survives;
the total and the discrepancy attribution are independently reconstructable.

The two original timeout cases were
`OnnxEmbeddingEncoderBoundedTokenizeTest.groupBoundariesPreserveResults` (10-minute
deadline) and `OnnxEmbeddingEncoderLongDocForensicTest.longDocEmbedWithSpansMatchesBaseEmbed`
(30-second deadline). The bounded-tokenization case passed on the first targeted
rerun; only the long-document case failed again. The original full command failed
after 21m3s. Neither original timeout is omitted from the new full-suite check.

Local transcript evidence in the previous orchestrator session's `subagents/`
directory: `agent-ab1797fa06e2e0b3f.jsonl:9708-9715` (B6 command and tally),
`agent-a4e1b3c52b1c2267e.jsonl:1138` (B10 full command), `:1289-1300`
(worker-core failure, two timeout cases), `:1309-1335` (first targeted clean/rerun),
`:1338-1360` (second targeted clean/rerun), and `:1364` (the smaller tally).


## Fresh full suite: passed

Command at unchanged code head `1ffd6cc2d` (only this evidence and design notes
were edited during the run):

```text
./gradlew.bat cleanTest test --no-build-cache --continue -PtestParallelism=1 --console=plain
BUILD SUCCESSFUL in 16m 25s
229 actionable tasks: 68 executed, 161 up-to-date
```

All 34 module test tasks with results executed. The 1,515 XML suite timestamps
span 2026-09-08T05:41:49.231Z to 2026-09-08T05:58:04.576Z. Total: **9,334 tests,
0 failures, 0 errors, 25 skipped**. This exactly matches the reconstructed
inventory; worker-core contributes all 83 classes / 335 tests again.

The bounded-tokenization multi-group case passed in **108.991 seconds**, and the
long-document case passed in **18.880 seconds**. Neither was skipped. No deadline,
test input or provider selection changed. Serialization was requested explicitly;
these results do not establish why the previous run took longer.

| module | XML files | tests | skipped | failures | errors |
|---|---:|---:|---:|---:|---:|
| adapters-lucene | 97 | 698 | 0 | 0 | 0 |
| ai-backend | 13 | 62 | 0 | 0 | 0 |
| api-contract-projection-java | 3 | 21 | 0 | 0 | 0 |
| app-agent | 49 | 661 | 0 | 0 | 0 |
| app-agent-api | 38 | 228 | 0 | 0 | 0 |
| app-api | 40 | 195 | 0 | 0 | 0 |
| app-config | 1 | 2 | 0 | 0 | 0 |
| app-engine | 27 | 119 | 0 | 0 | 0 |
| app-inference | 27 | 298 | 0 | 0 | 0 |
| app-launcher | 19 | 60 | 0 | 0 | 0 |
| app-observability | 60 | 379 | 0 | 0 | 0 |
| app-services | 396 | 2516 | 3 | 0 | 0 |
| app-util | 2 | 16 | 0 | 0 | 0 |
| benchmarks | 13 | 104 | 0 | 0 | 0 |
| configuration | 32 | 267 | 0 | 0 | 0 |
| core | 12 | 77 | 0 | 0 | 0 |
| core-contracts | 6 | 38 | 0 | 0 | 0 |
| dead-code-audit | 2 | 2 | 0 | 0 | 0 |
| extension-substrate | 1 | 7 | 0 | 0 | 0 |
| gpu-bridge | 5 | 77 | 0 | 0 | 0 |
| indexer-worker | 65 | 365 | 12 | 0 | 0 |
| indexing | 19 | 84 | 0 | 0 | 0 |
| infra-core | 1 | 3 | 0 | 0 | 0 |
| ipc-common | 2 | 4 | 0 | 0 | 0 |
| ort-common | 34 | 164 | 0 | 0 | 0 |
| prompt-support | 1 | 5 | 0 | 0 | 0 |
| reranker | 7 | 68 | 0 | 0 | 0 |
| ssot-tools | 4 | 17 | 0 | 0 | 0 |
| system-tests | 29 | 96 | 0 | 0 | 0 |
| telemetry | 25 | 88 | 1 | 0 | 0 |
| test-support | 6 | 13 | 0 | 0 | 0 |
| ui | 151 | 1019 | 1 | 0 | 0 |
| worker-core | 83 | 335 | 6 | 0 | 0 |
| worker-services | 245 | 1246 | 2 | 0 | 0 |
| **total** | **1515** | **9334** | **25** | **0** | **0** |

The complete XML tree was copied to
`tmp/lane-f-takeover/full-suite-snapshot/` before releasing the implementer.
Its `summary.json` records each suite's timestamp and SHA-256, as well as this
module tally. The raw command log remains `tmp/lane-f-takeover/full-suite.log`
(SHA-256 `f453bea932439cebb9b538547fa1fdad482902d9cfcafb8663177376d36c8b80`).
This closes the takeover's two verification questions, not the open review
findings or the stage-B checkpoint.
