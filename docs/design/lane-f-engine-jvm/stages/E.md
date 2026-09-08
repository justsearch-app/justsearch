---
title: "Lane F stage E — gate run: runbook"
stage: E
created: 2026-09-09
base: be47faa40
status: "runbook, written during stage C planning; instruments and values re-verified at stage start (17.6)"
updated: 2026-09-09
---

# Lane F stage E — gate run: runbook

Stage E runs, it does not build (17.6). This document is the runbook: which values are
instantiated before the first run, which instruments exist and which E must add, the procedure
for every row of section 16, the bound on every run, and what the record under `evidence/E/`
must contain for stage F's report. Section numbers are `design.md`'s; `design.md` is cited by
section, code and scripts by `file:line` at `be47faa40` (the instrument inventory was taken there
on 2026-09-09 and is re-verified at stage start).

Stage E in one line (17.3 row E): **no code beyond the fixes the run forces; collector and heap
chosen, and the supervisor's hang poll interval and count set with them from the soak's worst
safepoint pause; the owner's bounds and the three allowed-difference classes instantiated from
the PR 0 baseline before the run; paired against `main` after PR 0 on the same corpus and
machine, plus a run at the supported floor; the signed dead-Engine upgrade and inherited-store
recovery exercise (section 0, 2026-09-08).**

---

## 0. Decisions and corrections this runbook records

- **E0.1 — Run length is bounded to fifty-five minutes** *(owner constraint, 2026-09-07: no
  benchmark, eval, soak or capture over one hour)*. 17.7's locked soak ("two hours of indexing, a
  scripted agent and a reconfigure every fifteen minutes") is measured across **three forty-minute
  runs on separate occasions**, each a self-contained record; the zero-crash and no-heap-growth
  requirements of the memory row hold across all three runs together. This is 120 minutes of
  observed workload, not a continuous two-hour run; the record states the interruption boundaries.
- **E0.2 — The split side is measured at E, not read from the baseline.** 16 says "paired runs on
  the same corpus and machine"; the PR 0 baseline (`evidence/baseline/`, 2026-09-07) was taken on
  the PR 0 branch head, at the split Head's `512m` heap, before PR 0b's pins. For every paired row
  the split side is **`origin/main` at E's start** (which carries PR 0 and PR 0b, so the
  launch-flag correction is on both sides, as 16 requires) run with the same script on the same
  day; the baseline record is the sanity reference and the source of the 17.7 starting values,
  not the comparison arm. The exception is the workflow fixture: `evidence/baseline/fixture-pr0b/`
  is a valid split side because its pins and build are recorded and the gate is byte-exact; it is
  reused unless `main` has since changed a pinned surface, in which case the split side is
  recaptured.
- **E0.3 — Two instruments are wrong at this base and are fixed before any run** (E1 below):
  `analyze-head-run.cjs` filters roles `head`/`worker` (`:67,71,74`) while `head-rss-sampler.ps1`
  emits `engine` (`:18`), so its working-set section prints `-` for every phase of a merged run;
  and `head-flag-run.sh:76` pins `JUSTSEARCH_HEAD_HEAP=512m`, the split Head's heap, which would
  measure the merged Engine under the wrong bound.
- **E0.4 — The floor machine.** 17.7 names it as "the lowest GPU class the lower-memory guidance
  names". If a second machine of that class is not available at E, the floor run is executed on
  the reference machine with device memory capped by the existing per-model `*_GPU_MEM_MB` keys
  (the `fixture-pair.sh` pins are the precedent) to the floor class's VRAM, and the record says
  **"floor simulated by device-memory cap"**; 16's closing paragraph already bounds the conclusion
  to the tested hardware, and the report (F) restates the floor as "not run on a floor machine"
  in that case. The unloaded-encoder in-place path is what the cap must force (7.4).
- **E0.5 — Rows whose harness is another stage's** are run through that stage's harness, not a
  second one: admission and aggregate bound through C1's `admission-loop.mjs`, the forced-kill
  and resume rows through C2's installed-process scenarios, the reconfigure, generation, stuck
  component and semantic-availability rows through D1's harness, the durability and cursor rows
  through D2's tests. E adds only the instruments §2 names as missing.
- **E0.6 — Collector and heap are chosen by a short protocol, then every gate row is run once
  under the chosen pair.** No row is run twice under two collectors; the protocol (§5) is three
  bounded probe runs, not the gate.
- **E0.7 — A failed row is answered by section 2's rule** (collector and heap, pacing, a missing
  executor or admission bound, an operation contract that lost its bound, only then a process
  boundary), a fix re-runs the rows it can affect, and a fix to a shared mechanism re-runs the
  whole table (17.6). The default does not flip on a failed envelope (16).

### 0.1 Corrections found while running (appended per run)

*(empty — the runner appends one bullet per run whose procedure or value did not survive contact.)*

---

## 1. Values instantiated before the first run (17.7)

Every value below is written into `evidence/E/values.json` **before** the first gate run, with
its source, and the file's hash is in the run records. A value that changes after a diff is seen
is a new run, not an amendment.

| value | 17.7 rule | instantiation | source |
|---|---|---|---|
| foreground search p95 ceiling | split p95 plus ten percent | split side measured at E (E0.2) times 1.10, per mode (`hybrid`, `lexical` pinned); baseline sanity: hybrid p95 898 ms, lexical p95 160 ms after enrichment | `evidence/pr0/after/search-load-after-enrich.csv` via `analyze-head-run.cjs` |
| agent-loop API p95 ceiling | same | split side at E times 1.10, measured by C1's `admission-loop.mjs` driver (one loop of search-and-chat calls per context) — the baseline has only one total (`agent_turn_ms=10750`) | `evidence/pr0/after/agent-turn.txt` |
| admission rejection ceiling | zero with the agent idle; one percent under the scripted agent | as stated; counted by the harness's oracle on the wire, never inferred | 17.7 |
| indexing-progress fraction | ninety percent of split | split side's docs/s and chunks/s at E under the same foreground load; baseline sanity: primary indexing 127.4 docs/s, embedding complete at 209.7 s on scifact | `evidence/baseline/scifact/summary.json` `ingest.pipeline_summary` |
| warm-start budget | baseline index-ready plus five seconds | 7.6 s (`worker_ready_ms=7631` on warm restart) plus 5 s = **12.6 s** from the first cooldown's end to `index` ready | `evidence/pr0/after/restart-startup.txt` |
| crash-to-API-restored | first cooldown plus the warm-start budget | 1 s (`cooldownIncrementMs`) plus 12.6 s = **13.6 s** to `api`; `index` within the same budget | `supervision-contract.v1.json:164` |
| soak | two hours (17.7) → three forty-minute runs (E0.1) | indexing of the reference corpus, the scripted agent from `admission-loop.mjs` at a fixed rate, a reconfigure every fifteen minutes (two per run), under the chosen collector | E0.1 |
| floor machine | lowest GPU class the lower-memory guidance names | the class named in `docs/` lower-memory guidance at E's start; simulated by device-memory cap if no machine (E0.4) | E0.4 |
| corpus | the jseval reference corpus | `scifact` (5,183 documents, 300 queries) for the quality and indexing rows; the 91-document fixture corpus for the workflow fixture; the 100-document lock workload (B17) for the recovery rows | `evidence/baseline/README.md` |
| semantic-availability bound | paired first, absolute second | the split's semantic outage for the same reindex on the same machine, measured at E; an absolute ceiling fixed by the orchestrator from the split reference before the candidate gate run, with its value and rationale recorded in `values.json`; both bounds are required | 17.7 |
| retained-state and admission caps | first cut by the implementer, confirmed at E | the C1 values from `governance/retained-state.v1.json` and the admission config, confirmed or changed by the aggregate-bound and memory rows | C1 |
| session-gate aging threshold and background batch bound | first cut at D2, final at E | the D2 values, confirmed or changed by the request-time-encoders row | D2 |
| allowed-difference classes | exactly three | `new-reason-code`, `equal-score-order`, `generative-text` — already code constants (`workflow_fixture.py:224`); none added after a diff | PR 0b |
| noise-pair gate | three captures per side, `maxNoisyFraction` 0.05, `scoreTieEpsilon` 0.001 | unchanged from PR 0b | `lane-f-workflow-fixture.v1.json` |
| collector, heap, `UseCompactObjectHeaders` | gate run | chosen by §5's protocol, then written to both spawn sites and the exact-set pin before the gate rows run | §5 |
| hang poll interval and count | set with the collector from the soak's worst safepoint pause | interval × count ≥ 3 × the worst `Total time for which application threads were stopped` seen across all three soak runs, with interval ≥ 10 s; written to `governance/supervision-contract.v1.json` `:170-171` (both supervisors read it; no code change) | §5 |

---

## 2. Instruments: what exists, what E adds

Inventory at `be47faa40` (`scripts/jseval/lane-f/`, seven files; `scripts/supervisor-conformance/`;
jseval; the dev MCP). Per row of 16, the instrument and its gap:

| 16 row | instrument today | gap E must close (E1) |
|---|---|---|
| foreground search p95 | `head-flag-run.sh` search phases + `analyze-head-run.cjs` p50/p95/max per `effectiveMode` | heap pin and role filter (E0.3) |
| agent-loop API p95, admission rejections | none; one total in `agent-turn.txt` | C1's `admission-loop.mjs` (its oracle counts rejections by reason code); E adds a p95 over its calls to the analyzer |
| indexing progress | `python -m jseval run --pipeline` `pipeline_timing`; foreground load via `--search-load continuous` | a chunks/s series: E adds a sampler over `/api/knowledge/status` enrichment counters every 5 s |
| memory | `head-rss-sampler.ps1` (Engine only) | machine-wide sum: E extends the sampler to `llama-server.exe` and the extraction child (`--parent-pid` match), and to commit charge (`privateMB` is already there); heap growth from the GC log's live-after-GC series |
| recovery, process | conformance harness (25 cases, both adapters); installed-process scenarios | crash-to-`api`/`index` wall clock: E adds timestamps from `supervisor.v1.json` (`lastExit`, `state` transitions) and the readiness envelope's per-component ready instants (D1) |
| recovery, workflow | none | per-component time-to-ready from D1's readiness envelope; the four clients' re-entry from the generic MCP-client harness (D2/7.6) plus the webview, MCPB bridge and CLI runs; work-loss units from C2's operations table (turns interrupted from the agent loop's own count) |
| stuck component, generation transition, semantic availability, combined rows, reconfigure | none | D1's harness (its checklist §7) |
| aggregate bound | none | C1's `admission-loop.mjs` aggregate arm; cursor eviction from D2's tests |
| durability | none in scripts | D2's in-lane consumer test |
| request-time encoders | `encoder-latency-probe.sh` (`query-understanding` and `dense-retrieval` carry no `ms` at this base) | D2 adds the two stage timings; E runs the probe idle and during bulk embedding |
| semantic non-regression | both layers exist: jseval scifact + `relevance-gate.json`; `fixture-pair.sh` × 2 sides → `fixture-gate.sh` | none |
| resume conditions | `processing-replay-scenario.mjs` (PROCESSING only) | C2's `OperationResumeE2ETest` |
| dead-Engine upgrade | `sandbox-coverage.v1.json:65` `upgrade-dead-engine-recovery`; `sandbox-launch.py --upgrade-from`; `start-in-app-update-test.ps1 -Autorun` | the signed candidate (§7) |
| positive benefit | `evidence/A/a-deletion-counts.md`; `flag-diff-stat.txt` | verification-profile boot time (D2); the three representative changes (§6) |
| hang, graceful / forced | conformance cases `hang-soft-recovered-through-the-request-file`, `hang-hard-recovered-by-forced-kill` | run under the E-set hang parameters, not the harness overrides |

**E1 — instrument fixes, landed before the first run** (the one commit E makes before measuring;
`fix(936): E1 — …`): (a) `analyze-head-run.cjs` reads the role set from the CSV (`engine`,
`llama-server`, `extraction-child`) and fails loudly on an empty phase instead of printing `-`;
(b) `head-flag-run.sh` takes the heap from an argument defaulting to the packaged `-Xmx2g`, and a
`--collector` argument that maps to the flag set under test, so the paired arms are launched
identically except for the tree; (c) `head-rss-sampler.ps1` samples `llama-server.exe` and the
extraction child and emits their roles; (d) a `status-sampler.sh` writes the enrichment counters
every 5 s to `status-series.csv`; (e) `analyze-head-run.cjs` gains the admission p95 and the
chunks/s series. Acceptance: each fix has a falsifying self-test (a CSV with the old role names
must make the analyzer fail, not print `-`); `node scripts/dev/test-dev-runner-head-java-opts.mjs`
still green; `npm run lint:scripts` green.

---

## 3. The runs, each bounded

Every run is one script invocation with its own wall-clock cap inside the script, its own output
directory under `tmp/e/<run>/`, and a record under `evidence/E/<run>/` holding the summary, the
analyzer output, `values.json`'s hash and the SHA-256 of every raw file left in `tmp/`. Raw logs
stay in `tmp/` (stage B's rule); the record carries hashes. Runs are sequential; the dev stack is
held under a declared lease of the run's length. Order:

| run | rows of 16 | procedure | bound |
|---|---|---|---|
| **R0 collector protocol** | (§5) | three probe runs per candidate pair | 3 × 15 min per pair, at most two pairs per day |
| **R1 paired latency and memory** | search p95, memory (steady state), indexing progress | `head-flag-run.sh` on `main` then on the lane, same day, same heap and collector, the status sampler running; `analyze-head-run.cjs` on both; the paired comparison written by a new `compare-head-runs.cjs` (p95 ratio per mode, RSS per phase, docs/s) | 2 × 25 min |
| **R2 fixture gate** | semantic non-regression, layer 2 | `fixture-pair.sh <side> compact 33221 3` on the lane (three cycles), `fixture-gate.sh evidence/baseline/fixture-pr0b/side-a <lane-side> report.json`; the split side recaptured only per E0.2 | one side per run, about 45 min |
| **R3 quality baselines** | semantic non-regression, layer 1 | `python -m jseval run --dataset scifact --modes lexical,hybrid --pipeline --start-backend --clean --settle-index` on each tree; `jseval compare <main-run> <lane-run> --fail-on-regression`; `relevance-gate` against the stored baseline | 2 × 12 min |
| **R4a, R4b, R4c soak** | memory (zero crashes, no heap growth), indexing progress under load, admission rejections, hang parameters' input | `soak-run.sh`: start with GC and safepoint logging, ingest the reference corpus, the scripted agent at a fixed rate, a reconfigure every fifteen minutes, stop at 40 min; the analyzer reports crashes (`supervisor.v1.json` `restartCount` must be 0), live-after-GC trend (slope over the run, must be ≤ 0 after warm-up), worst safepoint pause | 3 × 40 min, separate occasions |
| **R5 recovery** | recovery process, recovery workflow, hang graceful, hang forced, resume conditions | `run.mjs --adapter dev-runner` and `--adapter tauri` under the E hang parameters; `:modules:system-tests:integrationTest` for B17's five and C2's six scenarios; the client re-entry runs (webview via ui-shot, MCPB bridge, CLI, the generic MCP harness) each scripted to: search, kill, wait for `restarting`→`running`, re-bootstrap, search again; work-loss units read from the operations table | about 40 min |
| **R6 lifecycle rows** | reconfigure, stuck component, generation transition, semantic availability, combined low-memory reindex, combined delayed retry | D1's harness on the reference machine, then on the floor (E0.4) | 2 × 45 min |
| **R7 request-time encoders and aggregate bound** | request-time encoders, aggregate bound, durability | `encoder-latency-probe.sh` idle then during bulk embedding; `admission-loop.mjs` both arms; D2's cursor and durability tests | 30 min |
| **R8 dead-Engine upgrade** | dead-Engine upgrade | §7 | one sandbox round, about 40 min of operator time |
| **R9 positive benefit** | positive benefit | §6 | three changes × two modes, each under one hour, on separate occasions |

A run whose script exceeds its cap exits with `TIMED_OUT` and is recorded as such; it is re-cut,
not re-run longer. No run is backgrounded past the harness's sixty-minute task kill.

---

## 4. Row-by-row procedure and pass criterion

For each row of 16, in the table's order: the run, the exact observation, the pass criterion in
the instantiated values, and what stays unmeasurable at E with the reason.

1. **foreground search p95 and agent-loop API p95** — R1 and R4: p95 over admitted requests per
   mode from `search-load-*.csv` and the admission loop's calls; pass if lane p95 ≤ split p95 ×
   1.10 in every mode and the rejection count on the wire is 0 (idle) and ≤ 1 % (scripted agent),
   every rejection carrying the admission reason code; a timeout or a 5xx is a failed row, not a
   rejection.
2. **indexing progress** — R1 and R4: chunks/s from the status series while the foreground load
   runs; pass if lane ≥ 0.90 × split under the same load.
3. **memory** — R1 (steady state) and R4a+R4b (soak): commit charge within the section 8 budget
   document (C1-13, `evidence/C1/memory-budget.md`) through the run; machine-wide sum (Engine +
   llama-server + children) ≤ split's sum for the same phase; live-after-GC slope ≤ 0 after the
   first fifteen minutes; `restartCount` 0 across all three soak runs. Working set reported beside,
   never as the metric.
4. **recovery, process** — R5: the conformance cases for the three exit classes and the budget
   under the E hang values; the installed writer scenario's crash-to-`api` and crash-to-`index`
   from `supervisor.v1.json` timestamps and the readiness envelope, pass if ≤ 13.6 s each;
   `restarting` observed in the file; the PROCESSING scenario proves resume-from-checkpoint (C2)
   not restart; no orphan child in the process table after restart; a healthy llama-server
   adopted (the `external-adoption` fault mode and B12's tests) on the crash and `restart` paths,
   absent after `quit` and `upgrade`.
5. **recovery, workflow** — R5: per-component time to `api`, `index`, `encoders`, `generative`
   from D1's envelope after a forced kill; each of the four clients completes search → kill →
   re-bootstrap → search without operator action, result stated per client; C2's six kill-point
   scenarios (unknown / accepted / running-or-failed with the right unit count; same-key retry
   moves nothing); work loss in user units (turns interrupted, operations resumed vs restarted,
   writes needing a query) reported and compared with 17.7's tolerance (one turn, zero restarts,
   only in-flight writes).
6. **stuck component** — R6: D1's harness blocks the index open past its deadline, asserts
   `failed` with reason, `api` alive, recovery by reconfigure when released, no supervisor
   restart; then the unreleasable lock: local budget exhausted, one supervised restart counted,
   `index` ready after; an optional component in the same state never escalates.
7. **generation transition** — R6: D1's harness on the reference machine and the floor: edit,
   removal and new document during the rebuild reflected at activation; one gap refuses and names
   the document; H1→H2 replay reports `superseded` and does not block; a cursor opened before
   activation pages to expiry or fails `cursor expired` (D2); on the floor the in-place path with
   `reloading` semantic legs, text search answering, a write accepted in the window text-searchable
   at once with `semantic at activation`, semantically searchable after; the refusal branch on the
   floor recomposes encoder A within the reconfigure budget with the deferred writes backfilled.
8. **semantic availability during maintenance** — R6 on the floor: the refused fraction and the
   wall-clock window of a full transition; pass if ≤ the split's semantic outage for the same
   reindex on the same machine (measured in R6's split arm), and ≤ the owner's absolute ceiling
   once named.
9. **combined: low-memory reindex, changing inputs, interruption** — R6 on the floor, in-place
   mode: edits under captured units, forced kill after the `state.json` swap and before the
   completion row → on restart B active, encoders match B, row `complete`, edited documents current;
   the same with the kill before the swap → replay resumes from the journal with A active.
10. **combined: delayed retry after a later change** — R6: A→B (response dropped), B→C, retry of
    the first under its key answers `complete` with C applied and one apply of C in the component
    map; the same under a new key refused `version conflict`; a completed write retried after its
    document was removed answers `complete` and the document stays removed; an aged key answers
    `expired`; after a restore from backup `history since` is the restore point.
11. **aggregate bound** — R7: `admission-loop.mjs` both arms (N contexts at their limit above an
    executor's cap → aggregate code, queue never above its bound; one context with the same total
    load rejects the same number); D2's cursor eviction with `cursor expired` at the per-context
    cap, pinned-reader count in the component map ≤ its cap, memory row holding.
12. **durability** — R7: D2's consumer test (a `durable` index-and-return write searchable after an
    immediate forced kill; an NRT write searchable before the next commit).
13. **request-time encoders** — R7: one query NER, one embedding, one rerank of twenty, idle then
    during bulk embedding (`encoder-latency-probe.sh`, with D2's two stage timings); pass if the
    contended latency ≤ idle + the running batch + one aged batch (the batch times from the run's
    encoder profiles, `debug_state_snapshot.worker.enrichment.encoderProfiles`), the phase stated;
    under the scripted agent reported against admitted concurrency; the `passed`-rule unit tests
    (D2) green in the same record.
14. **semantic non-regression** — R2 and R3: `relevance-gate.json` `ok` and `jseval compare` with
    no significant regression on nDCG@10 per mode and the same `SearchTrace` shape per query;
    `fixture-gate.sh` PASS with 0 regressions, the counts reported beside the verdict.
15. **resume conditions** — R5: C2's `OperationResumeE2ETest` and the four condition tests; the
    lifetime condition as the D2 cursor test.
16. **dead-Engine upgrade** — R8 (§7).
17. **positive benefit** — R9 (§6) plus the deletion counts and the verification-profile boot
    time (D2's test, seconds).
18. **hang, graceful / hang, forced** — R5: the two conformance cases under the E hang values on
    both adapters; recovery within request deadline plus the warm-start budget.
19. **reconfigure** — R6: encoder config change with zero connection drops (the admission loop
    running throughout counts drops), one applied version per component (D1's component map
    sampled every second during the apply), an undeclared-dependency component unchanged; beside
    mode with a rejected compose leaving the incumbent; in-place mode (forced by the cap) with
    `reloading`, a rejected compose leaving `unavailable` then recomposing A, the double-failure
    variant handing over to the stuck-component path; a native-crash compose recovered by the
    supervisor booting from A with the attempted-version record naming B, llama-server adopted
    only on matching identity.

**Unmeasurable at E, named:** Linux whole-Engine recovery (no Linux machine in the run; recorded
as the gap section 16 already names); the floor machine if simulated (E0.4); the Tauri AppHandle setup and events (source-reviewed,
not executed, as B recorded).

---

## 5. The collector protocol (R0)

Candidates: the current pair (`SerialGC`, `-Xmx2g`, `UseCompactObjectHeaders` on) and `G1`
at the same heap; `ZGC` only if `G1`'s young-pause p95 exceeds the split's. For each candidate,
`head-flag-run.sh` with `--collector` and the soak's first fifteen minutes (`soak-run.sh --minutes 15`),
GC and safepoint logging on. Read from `analyze-head-run.cjs`: young-pause p50/p95/max, full
GCs (must be zero), live-after-GC max vs committed, worst safepoint pause, RSS p95 in the
ingest phase, search p95 in the `after-enrich` phase. Choose by: search p95 first (within the
row's ceiling), then RSS, then pause max. `UseCompactObjectHeaders` is toggled on the chosen
collector in one more probe pair and kept only if RSS improves with search p95 unchanged. The
chosen flags are written to both spawn sites and `SHARED_FLAGS`/`PACKAGED_ONLY_FLAGS` in one
commit before R1 (`fix(936): E — the chosen collector and heap`), with `node
scripts/dev/test-dev-runner-head-java-opts.mjs` green. The hang parameters follow from R4's worst
safepoint pause (§1) and are written to the register after R4b.

---

## 6. The three representative changes (R9, positive benefit)

Named here, before the flip, so they cannot be chosen after the fact: **retrieval** — add one
field to the search hit (source tier) through the port and the HTTP shape; **orchestration** —
give the agent loop one new tool that fetches a document slice; **lifecycle** — add one optional
component (a no-op encoder) with readiness and a reconfigure hook. Each is implemented on both
trees (`main` at E's start, the lane) by an agent under the same brief, bounded to one hour per
change per tree, and measured on: modules touched, cross-subsystem facts the agent had to
establish (counted from the brief's questions and the agent's own reads), verification time, and
**failures and correction effort** (review findings, red tests, missed invariants such as an
unpreserved applied-version or acceptance-order rule, with the turns spent correcting them). The
record is `evidence/E/r9/<change>-<tree>.md` with the diff stat and the counts. Credit is
separated as 16 requires: capability work is not counted as merge benefit.

---

## 7. The dead-Engine upgrade round (R8)

The registered procedure `upgrade-dead-engine-recovery` (`governance/sandbox-coverage.v1.json:65`)
against the **final candidate**: a signed installer from `build-installer.yml` (dispatch-only;
the `release-signing` environment admits `main` and `v*`, and a branch candidate needs the
temporary deployment-policy step `docs/how-to/cut-a-release.md:32-37` describes). Steps:
`python scripts/sandbox/sandbox-launch.py --installer <candidate.exe> --upgrade-from <0.3.0.exe> --charter <charter>`;
in the sandbox install 0.3.0, populate a data directory (index, one memory, one note); damage the
Engine non-transiently (a config the resolver rejects, so the classifier's non-transient class is
what fires); wait for `exhausted` in `supervisor.v1.json`; use the pre-API recovery update action
(B13); verify registered matching children exit before the installer launches and an unrelated
PID survives; install the repair; open every inherited store at the broken release's schema;
assert the index count and a search from the inherited index; record source and candidate
versions and the stop evidence. If main-only signing still prevents the candidate at E, the run
moves to the first eligible signed installer after the final merge and F carries the named gap
(section 0, 2026-09-08). The round is the one E run that is operator-driven; it stays inside one
hour of sandbox time.

---

## 8. The record (`evidence/E/`)

- `values.json` (§1) and its hash in every run record.
- One directory per run (`r0/` … `r9/`) with: the command line, the tree and commit of each arm,
  the machine facts (GPU, driver, JDK, OS build), the analyzer output, the verdict per row, and a
  SHA-256 inventory of the raw files left under `tmp/e/`.
- `table.md`: the 16 table row by row with pass / fail / unmeasurable, the number behind each,
  and the run it came from; the collector and heap chosen with R0's numbers; the hang parameters
  and the safepoint pause they derive from.
- `decision.md`: for a failed row, the section 2 remedy applied and the re-run; for a passing
  envelope, the bounded conclusion 16's last paragraph requires (machine, corpus, workload,
  duration, failure scenarios covered).

Stage F's report reads only this directory and the stage records.

---

## 9. What may be red or unmeasurable after E, and nothing else

1. Linux whole-Engine recovery (named gap; no Linux machine).
2. The floor machine, if simulated (E0.4), named as such.
3. The signed dead-Engine round, if signing prevents the candidate (carried by F).
4. The semantic-availability ceiling must be fixed before the candidate gate run and is not an allowed proof gap.

Not allowed: any gate row failing without a section 2 remedy and re-run recorded in
`decision.md`; a row marked passed on a subset of its clauses; a run over the bound.

---

## 10. Stop rule (17.8)

If the envelope fails a row whose remedy is a process boundary (section 2's last resort), stop
and re-read 2 and 14 before any code; record the row, the remedy tried and the number in
`decision.md` and in design section 0. A failed row answered by a shared-mechanism fix re-runs
the whole table (17.6), which means at least R1, R4a, R4b and R6 again: budget the re-run before
the fix.

---

## 11. Open questions

- **Q1 — the floor machine.** Whether a machine of the lower-memory guidance's GPU class is
  available at E. If not, E0.4 applies and the report says so. (No decision needed now.)
- **Q2 — the semantic-availability absolute ceiling.** Under delegated authority, the orchestrator
  fixes the absolute ceiling from the measured split reference before the candidate gate run,
  records the value and rationale in `values.json`, and requires both bounds. No owner decision
  or null-valued ceiling is left pending. A missing split measurement is work for E to perform.

---

*End of the stage-E runbook. Instruments and values are re-verified at stage start; corrections
found while running go to §0.1.*
