---
title: "Stage E host decision inputs (WP4)"
created: 2026-10-04
status: "draft observations for host review"
---

# Stage E host decision inputs (WP4)

Date: 2026-10-04. These are descriptive inputs required by the
[WP4 table in E section 8][runbook], not an additional acceptance gate or a
measurement of an inference-host implementation. Selection, machine, builds,
acquisition drift, owner dispositions and the bounded paired verdict are in
[decision.md](decision.md). Sources are the selected E4 windows, latest E5
records, both E2 workloads per arm, and the named D1 installed feature proof.
Unavailable observations stay unavailable.

## Native encoder faults

| Source | MAIN observation | BRANCH observation | Availability and meaning |
|---|---|---|---|
| E4 owner soak | One confirmed native JVM fault in second-window teardown, Worker PID 37764, `EXCEPTION_ACCESS_VIOLATION (0xc0000005)`, frame `onnxruntime.dll+0x1c473`; no crash-file events in first/tail windows | No confirmed native encoder crash in retained events; each window instead flags llama-server exit 1 at quit, with missing expected-termination classification | Confirmed file-event counts are available; a complete supervisor `native crash` exit count is unavailable. MAIN has no Lane F supervisor exit ledger, and BRANCH's historical child accounting is incomplete. [M4a][M4a], [M4b][M4b], [M4c][M4c], [B4a][B4a], [B4b][B4b], [B4c][B4c], [native log][main-hs] |
| E5 crash experiment | Actual identity-verified Worker forced death, Head remains alive | Actual identity-verified Engine forced death, supervisor replaces it | This is deliberately injected process death, not an observed encoder-native fault. The E5 records do not provide a complete spontaneous native-encoder-exit population; that count is unavailable. [M5][M5], [B5][B5] |

The exact MAIN fatal-error file and its associated Java crash report are retained
at the paths linked here: [hs_err_pid37764.log][main-hs] and
[crash-worker-37764-1790930267909.json][main-crash]. The Java report describes
Lucene `AlreadyClosedException`/`ClosedByInterruptException`; the fatal-error log
separately identifies the ORT native frame. The event occurred during teardown,
not inside the timed foreground window ([M4b][M4b]). This is laboratory split-arm
fault evidence; it does not establish a production encoder fault rate or prove
the same crash frequency in the merged Engine.

The BRANCH llama-server entries are not encoder crash counts. Their
`atMs=-11644473600000`/`timing=before-window` is an invalid exit timestamp;
the ledger diagnoses Windows `TerminateProcess` exit 1 at a clean quit without
`expectedTermination`. Coverage remains incomplete, so “no confirmed encoder
crash in these files” cannot mean zero crashes for every owned child
([B4a][B4a], [B4b][B4b], [B4c][B4c], [ledger, clean-quit diagnosis][ledger]).

## Reconfigure cost and text-only window

| Required observation | Named evidence | Measured value / gap |
|---|---|---|
| Flow B beside: admission → encoder READY | D1 `ordinaryQueryReconfigureProvesBesideAndForcedInPlaceWithoutRestart`, installed report at `30f5ca540`; later delayed-retry rerun at `7ab2aedf5` | **Unavailable** as a timestamped interval in the retained report. It reports beside operation and no API outages/restarts, not the admission and READY timestamps. [D1 installed proof][D1] |
| Flow B in-place: admission → encoder READY | Same D1 report, forced in-place exercise, `candidate_fits_after_source_release` | **Unavailable** as the requested interval. Mode and applied-model proof do not supply elapsed readiness time. [D1][D1] |
| Time in RELOADING while lexical answers | D1 beside/in-place feature proof plus E2 foreground records | **Unavailable** as a continuous text-only duration. D1 reports API survival; E2 records idle/scripted foreground latency, without a reconfigure admission/encoder READY span. [D1][D1], [M2i][M2i], [M2s][M2s], [B2i][B2i], [B2s][B2s] |
| Restoration after refused in-place composition | D1 `refusedInPlaceGapRestoresAThenApprovesAndPromotesB`, closure proof at `51fff0a62` | A restoration time of **23,178 ms** against the **120,000 ms** encoder deadline is recorded. This is a refused-transition restoration measurement, not Flow B admission-to-READY or the whole RELOADING window. [D1][D1] |

The D1 report records a forced in-place test with 1 MiB free device memory, not
a run on a physical minimum-spec machine ([D1][D1]). Its named raw fixture paths
were not present at the report's worktree-relative locations during this draft;
the report is retained as feature evidence, and missing phase timestamps are
not reconstructed from sample counts or test wall clocks.

For context, E2 successful foreground p95 (hybrid / lexical, ms) is:

| Workload | MAIN | BRANCH | Evidence |
|---|---|---|---|
| Agent idle | `259.213400 / 17.027600` | `279.010200 / 23.516200` | [M2i][M2i], [B2i][B2i] |
| Scripted agent | `1551.859500 / 18.479300` | `689.511600 / 26.486300` | [M2s][M2s], [B2s][B2s] |

These measurements neither time encoder reconfigure nor establish text survival
during RELOADING. BRANCH's lexical results exceed the corresponding frozen
ceilings, and successful-request p95 omits failed terminals; the wire failures
remain explicit in [decision.md](decision.md).

## Encoder share of Engine restart

| Same-boot E5 timeline field | MAIN | BRANCH | Evidence |
|---|---|---|---|
| Injected kill (`killMs`) | `1790923329081` | `1791041530241` | [M5][M5], [B5][B5] |
| Confirmed process death (`deathConfirmedMs`) | `1790923329535` | `1791041530617` | [M5][M5], [B5][B5] |
| First successful API observation (`apiMs`) | `1790923329953` | `1791041533954` | [M5][M5], [B5][B5] |
| Index/text-ready observation (`indexMs`) | `1790923345953` | `1791041554480` | [M5][M5], [B5][B5] |
| Index minus API, derived from those timestamps | `16000 ms` | `20526 ms` | [M5][M5], [B5][B5] |
| Encoder READY / first semantic answer timestamp | **Unavailable** | **Unavailable** | Neither record's `crashTimeline` contains this phase; startup capability receipts are not successor-semantic readiness. [M5][M5], [B5][B5] |
| Generative READY / first successful post-death chat | **Unavailable** | **Unavailable** | Not in the same-boot recovery timeline. [M5][M5], [B5][B5] |
| Text/API → encoder/semantic difference | **Unavailable** | **Unavailable** | No paired encoder-ready timestamps; no encoder-load share can be calculated. [M5][M5], [B5][B5] |

The available interval is **API-to-index**, not API-to-encoders. MAIN's Head
survived and its API number is the first successful post-death probe; BRANCH's
number is the successor API. The index interval can include recovery and index
opening; the timeline does not isolate encoder load. It is therefore not an
encoder-load proxy. Session identities at startup/stop establish configured
models, not the missing readiness phase ([M5][M5], [B5][B5], [E WP4 rule][runbook]).

## Request-time encoder latency

The E2 records retain supplemental `encoder-stage-ms.csv` and summary reports
after each fixed foreground window. Each report describes 36 calls. The probe
is not the E2 admission population and is not continuously sampled during the
foreground window; its contention at each individual call is not quantified
([E section 2][runbook], [MAIN idle probe][MprobeI], [MAIN scripted probe][MprobeS],
[BRANCH idle probe][BprobeI], [BRANCH scripted probe][BprobeS]).

| Workload / supplemental probe | MAIN cross-encoder p50 / p95 / max (ms) | BRANCH cross-encoder p50 / p95 / max (ms) | Evidence |
|---|---|---|---|
| Agent idle | `226 / 917 / 34478` | `149 / 1129 / 1349` | [MprobeI][MprobeI], [BprobeI][BprobeI], [M2i][M2i], [B2i][B2i] |
| Scripted agent | `304 / 7003 / 18376` | `145 / 162 / 480` | [MprobeS][MprobeS], [BprobeS][BprobeS], [M2s][M2s], [B2s][B2s] |

| Requested stage | Timing availability | Identity / interpretation |
|---|---|---|
| Query embedding | **Unavailable** as a separate measured latency | Probe CSV's dense-retrieval/query-understanding rows have empty `ms`; configured embed model is `onnx/gte-multilingual-base`. [McsvI][McsvI], [McsvS][McsvS], [BcsvI][BcsvI], [BcsvS][BcsvS], source E2 records |
| Query sparse encoding | **Unavailable** as a separate measured latency | Sparse-retrieval rows have empty `ms`; configured SPLADE is `splade/naver-splade-v3`. The end-to-end query duration is not sparse-encode time. Same CSVs and E2 records above. |
| Rerank | Cross-encoder stage timing available, as tabulated | Configured model directory `onnx/reranker`; this is the reported stage, not a separately proven one-rerank-of-twenty workload. Same probes and E2 records above. |
| Citation scoring | **Unavailable** as a separate measured latency in these search probes | Configured model directory `onnx/citation-scorer` is retained, but configuration/activity is not timing; no citation timing series appears in the probe summary/CSV. Same probes and E2 records above. |

The common model root is `F:/justsearch-public/models`; the E2 receipts bind the
configured directories and source-weight inventories. Chat uses
`Qwen_Qwen3.5-9B-Q4_K_M.gguf`, standard/cuda12, with `mmproj-F16.gguf`.
Session receipts retain lazy/unknown model activity and provider observations:
MAIN idle-start reports lazy embed/SPLADE CPU fallback and a CUDA reranker;
BRANCH idle-start reports unknown providers for unloaded sessions. No common
realized provider is inferred from equal configured selections ([M2i][M2i],
[M2s][M2s], [B2i][B2i], [B2s][B2s], `encoderSessions`).

For the scripted BRANCH acquisition, the owner retained diagnosis E2SD with
60 MiB free VRAM, LLM 31 versus 58 tok/s, SPLADE batches 234–683 seconds, and
HTTP 504s identified as approximately 15-second hybrid-search timeouts in native
ORT encoding ([ledger, 2026-10-03 owner decisions][ledger]). These are diagnosis
receipts, not measured query-stage latency or per-process VRAM accounting. The
foreground record retains 24 HTTP 504 and 5 LLM_ERROR outcomes, zero document
embedding/chunk completions in the window, and degraded primary/SPLADE progress
([B2s][B2s]). No per-process VRAM series is available to partition that pressure
between co-resident clients. Follow-up is the owner's budget-aware co-resident
GPU policy and per-process VRAM capture.

**Post-remedy rerun, 2026-10-04.** The BRANCH records above ([B2i][B2i], [B2s][B2s]) are
the first selection. The scripted window was rerun overnight with no build, other
stack or owner workload ([B2s2][B2s2]). It retains 18 HTTP 504s and one missing
status, and primary and SPLADE rates below 90% of MAIN. Diagnosis REME2B found
native embedding and SPLADE encodes of up to about 194 seconds while free VRAM fell
to 172 MiB during LLM generation, with no logged arena out-of-memory or
execution-provider fallback; MAIN kept embedding below about 12 seconds. The
VRAM-pressure weakness is therefore reproduced without outside load. Why MAIN avoids
the tail is not established, so this is an input to the host review, not evidence
of a measured host-placement benefit ([decision, E2][decisionE2]).

## Host review disposition

[Design section 5][host-rule] retains the in-process stage-1 encoder owner,
and calls for revisiting a host on evidenced encoder faults, a second runtime/OS,
concurrent verification stacks, reconfigure cost, or at the first release after
Lane F. This evidence supplies an exact split-arm ORT teardown fault and a retained
GPU-pressure weakness relevant to that review. It supplies no production fault
rate, complete supervisor-native-exit census, Flow B READY/RELOADING duration,
encoder share of restart, or isolated query embedding/sparse/citation latency.
Those inputs remain unavailable, so the review cannot claim a quantified
out-of-process-host benefit from this packet. No host change is decided here.

[runbook]: ../../stages/E.md#host-decision-inputs-wp4
[host-rule]: ../../design.md#5-the-inference-seam-transitional-stage-then-the-target
[ledger]: F:/agent-sandbox/js-lane-f/ledger.md
[D1]: ../D1/installed-round-2026-10-01.md
[M2i]: e2-e3-load/main/2026-10-01T16-42-58-581Z-b4fbca64.json
[M2s]: e2-e3-load/main/2026-10-01T17-05-11-968Z-6b4848ac.json
[B2i]: e2-e3-load/branch/2026-10-03T12-38-06-994Z-b80cb657.json
[B2s]: e2-e3-load/branch/2026-10-03T12-59-26-654Z-2f0996a4.json
[B2s2]: e2-e3-load/branch/2026-10-04T01-58-19-999Z-f9e67f1f.json
[decisionE2]: decision.md#e2--foreground-and-agent-response-under-indexing
[M4a]: e4-memory-soak/main/2026-10-02T06-46-11-669Z-02344ce3.json
[M4b]: e4-memory-soak/main/2026-10-02T07-42-03-648Z-7e783211.json
[M4c]: e4-memory-soak/main/2026-10-02T08-37-53-575Z-8c6f6165.json
[B4a]: e4-memory-soak/branch/2026-10-03T15-34-29-779Z-f4832677.json
[B4b]: e4-memory-soak/branch/2026-10-03T22-59-25-894Z-f018e8c4.json
[B4c]: e4-memory-soak/branch/2026-10-03T23-55-01-600Z-0208ce21.json
[M5]: e5-crash/main/2026-10-02T06-41-36-093Z-0ef8ea83.json
[B5]: e5-crash/branch/2026-10-03T15-31-41-882Z-3f940bff.json
[main-hs]: F:/justsearch-public/.claude/worktrees/lane-f-e-main/tmp/lane-f-e/2026-10-02T07-42-03-648Z-7e783211/soak/crashes/hs_err_pid37764.log
[main-crash]: F:/justsearch-public/.claude/worktrees/lane-f-e-main/tmp/lane-f-e/2026-10-02T07-42-03-648Z-7e783211/soak/crashes/crash-worker-37764-1790930267909.json
[MprobeI]: ../../../../../tmp/lane-f-e/e2-e3-load/main/2026-10-01T16-42-58-581Z-b4fbca64/agent-idle/encoder/encoder-stage-summary.md
[MprobeS]: ../../../../../tmp/lane-f-e/e2-e3-load/main/2026-10-01T17-05-11-968Z-6b4848ac/scripted-agent/encoder/encoder-stage-summary.md
[BprobeI]: ../../../../../tmp/lane-f-e/e2-e3-load/branch/2026-10-03T12-38-06-994Z-b80cb657/agent-idle/encoder/encoder-stage-summary.md
[BprobeS]: ../../../../../tmp/lane-f-e/e2-e3-load/branch/2026-10-03T12-59-26-654Z-2f0996a4/scripted-agent/encoder/encoder-stage-summary.md
[McsvI]: ../../../../../tmp/lane-f-e/e2-e3-load/main/2026-10-01T16-42-58-581Z-b4fbca64/agent-idle/encoder/encoder-stage-ms.csv
[McsvS]: ../../../../../tmp/lane-f-e/e2-e3-load/main/2026-10-01T17-05-11-968Z-6b4848ac/scripted-agent/encoder/encoder-stage-ms.csv
[BcsvI]: ../../../../../tmp/lane-f-e/e2-e3-load/branch/2026-10-03T12-38-06-994Z-b80cb657/agent-idle/encoder/encoder-stage-ms.csv
[BcsvS]: ../../../../../tmp/lane-f-e/e2-e3-load/branch/2026-10-03T12-59-26-654Z-2f0996a4/scripted-agent/encoder/encoder-stage-ms.csv
