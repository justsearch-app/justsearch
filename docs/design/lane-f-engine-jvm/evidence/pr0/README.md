# PR 0 evidence: Head launch flags, before and after

Design 17.2: drop `-XX:TieredStopAtLevel=1`, add `-XX:MetaspaceSize=128m` at both Head spawn
sites, keep `-XX:+UseSerialGC`, measure before and after on the 917 Derisk 1 procedure. The
procedure is now `scripts/jseval/lane-f/head-flag-run.sh` (analysed by `analyze-head-run.cjs`
beside it); each subdirectory here is one run's artifacts plus `analysis.md`.

## Runs

| | before | after |
|---|---|---|
| date, machine | 2026-09-07 09:56 to 10:04, dev machine (NVIDIA GeForce RTX 4070, 12 GB; Windows 11) | 2026-09-07 10:00 to 10:08, same |
| code | lane branch at `02ec8c0b8` (= `main` `76871d924` + design docs), dev-runner as committed there | same tree with the PR 0 flag edits in `dev-runner.cjs` |
| effective Head flags (`jcmd VM.flags`) | `MaxHeapSize=512M UseSerialGC TieredStopAtLevel=1 -UsePerfData` | `MaxHeapSize=512M UseSerialGC MetaspaceSize=128M -UsePerfData` |
| launch | dev-runner, `--clean soft --skip-build`, `JUSTSEARCH_HEAD_HEAP=512m`, `-Xlog:gc*,safepoint` | same |
| corpus | `docs/explanation` + `docs/reference`, 91 documents, 1276 chunks | same, 1278 chunks (a reference doc was being edited by the fixture work between runs; one document differs) |
| phases | 60 s idle, ingest, 60 searches during enrichment, wait for every stage, 60 searches after, 30 lexical-pinned searches, compact-model activation and one agent turn, warm restart, stop | same |

## Result

| measure | before | after |
|---|---|---|
| full GCs, first launch (142 to 146 s of uptime) | **5** (2 Metaspace threshold, 3 CodeCache threshold), 170 ms stopped | **0** |
| full GCs, warm restart (first 17 s) | **3** (all Metaspace threshold), 79 ms stopped | **0** |
| young GCs, first launch | 1, 3 ms | 5, 66 ms total (p50 13 ms, max 21 ms) |
| live heap after GC | 13 to 63 MB of 491 MB committed | 13 to 64 MB of 491 MB |
| Head working set, idle (60 s) | p50 330 MB | p50 361 MB |
| Head working set, ingest + enrichment | p50 364 MB, max 369 | p50 416 MB, max 426 |
| Head working set, search + agent turn | p50 386 MB, max 394 | p50 439 MB, max 460 |
| Worker working set, idle / loaded | p50 4.04 / 4.25 GB | p50 4.04 / 4.27 GB (unchanged, as expected) |
| startup, warm (`--skip-build`) | HTTP 2.56 s, worker ready 7.48 s | HTTP 2.56 s, worker ready 7.52 s |
| restart, warm | HTTP 2.62 s, worker ready 7.06 s | HTTP 2.63 s, worker ready 7.63 s |
| search p50 / p95, hybrid mode, during enrichment (n=51 / 52) | 408 / 885 ms | 433 / 948 ms |
| search p50 / p95, hybrid mode, after enrichment (n=51 / 50) | 389 / 880 ms | 423 / 898 ms |
| search p50 / p95, lexical pipeline pinned, after enrichment (n=30) | 87 / 140 ms | 94 / 160 ms |
| agent turn (compact model, 3 iterations) | 8.4 s, 159 KB of SSE | 10.8 s, 213 KB of SSE (generation length differs; not comparable) |

## Reading

- **The flag change does what 917 Derisk 1 predicted.** Every full GC in the before-run was a
  Metaspace or CodeCache threshold (the C1-only 48 MiB code cache and the default Metaspace
  high-water mark), none was heap pressure, and the after-run has no full GC at all on either
  launch. Total stop-the-world time on the first launch fell from about 173 ms to 66 ms.
- **The cost is working set**: about +30 MB idle and +50 MB under load, which is the tiered
  compiler's threads and the larger code cache the default tiered policy reserves (240 MiB
  reserved, committed as used). `MetaspaceSize` is a GC-trigger threshold, not a commit, so it
  contributes nothing to that number. This is the Head half of the memory line the Engine's
  budget (design 8) will be sized from; the baseline capture after PR 0 carries the after
  numbers.
- **Search latency is not separable from noise at this sample size.** The hybrid p50 moved
  +6 to +9 percent and the lexical-pinned p50 +8 percent (87 to 94 ms) on one sequential run each,
  with C2 compiling during the first minutes of the after-run and the Worker's GPU embedding
  running under both. No regression is claimed and none is excluded; the gate run at stage E
  measures p95 with the declared margin (17.7) on the after flags for both sides, so the flag
  change can never be credited to or charged against the merge (16, credit rule).
- **Search mode is recorded per query** because the first attempt at this pairing was
  confounded by it: the mode the planner picks (text versus hybrid) depends on where enrichment
  stands when the query arrives, and the two modes differ by 4x in latency. The lexical-pinned
  set is the like-for-like Head CPU-path comparison.
- **Startup is unchanged** within 0.1 s on both launches.

## What is not here

The production Tauri path with the AOT cache (917 Derisk 1 named it as still to be measured):
the dev machine has no dev AOT cache, so both runs are the no-cache path, and `lib.rs` carries
the same edit unmeasured. The baseline capture (17.2, next step after PR 0 merges) and the
stage E gate run are where the packaged path is measured.
