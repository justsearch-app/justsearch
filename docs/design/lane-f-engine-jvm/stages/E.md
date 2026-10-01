---
title: "Lane F stage E — gate run: runbook"
stage: E
created: 2026-09-09
base: be47faa40
status: "runbook, written during stage C planning; instruments and values re-verified at stage start (17.6)"
updated: 2026-09-27
---

# Lane F stage E — gate run: runbook

Stage E runs, it does not build (17.6). **The 2026-09-24 owner re-cut below
supersedes the older all-row run list and fixed-soak/floor/collector directions.**
This document is the runbook: which values are
instantiated before the first run, which instruments exist and which E must add, the procedure
for the seven paired groups of section 16, the bound on every run, and what the record under `evidence/E/`
must contain for stage F's report. Section numbers are `design.md`'s; `design.md` is cited by
section, code and scripts by `file:line` at `be47faa40` (the instrument inventory was taken there
on 2026-09-09 and is re-verified at stage start).

Stage E runs the seven paired groups in §3 against current `main`. G1 is the
default; the owner sets E4's soak duration. D1/D2 own every other §16 row as
one-sided feature acceptance. The signed dead-Engine upgrade and inherited-store
recovery exercise remains E7, with §16's artifact disposition.

---

## 0. Decisions and corrections this runbook records

- **E0.8 — Seven paired groups only (owner, 2026-09-24).** Pair branch against
  current `main` for quality plus workflow fixture, foreground search and agent
  response time during bulk indexing, indexing speed, memory budget with an
  owner-duration no-crash soak, crash recovery with no orphaned children,
  graceful and forced hang detection, and dead-Engine upgrade. Every other §16
  row is one-sided feature acceptance mapped to D1/D2; no condition is waived.
  Installed and real-model proof may be reused where its revision and assertions
  cover the row. Minimum-spec machine, other-OS recovery and representative
  changes run only for their corresponding claim. G1 is the default; a
  collector comparison runs only if a response-time group fails. The owner sets
  soak duration before its run. E0.1, E0.4, E0.5 and E0.6 below remain as
  historical decisions only where they conflict with this correction.
- **E0.1 — Run length is bounded to fifty-five minutes** *(owner constraint, 2026-09-07: no
  benchmark, eval, soak or capture over one hour)*. 17.7's locked soak ("two hours of indexing, a
  scripted agent and a reconfigure every fifteen minutes") is satisfied by **two fifty-five-minute
  runs on separate occasions**, each a self-contained record; the zero-crash and no-heap-growth
  requirements of the memory row hold across both runs together, and the record says so. This is
  a constraint on continuous duration, not a relaxation of the row.
  **2026-09-09 amendment:** preserve both 55-minute continuous windows and add a separate
  10-minute run so the total is 120 measured minutes. The prior three-40-minute edit is withdrawn:
  it shortened the window used to detect heap growth. Evaluate the slope after warmup in each
  55-minute run, retain all interruption boundaries, and never label this a continuous two-hour soak.
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
  affected paired groups and dependent D1/D2 feature proof (2026-09-24). The
  default does not flip on a failed envelope (16).

### 0.1 Corrections found while running (appended per run)

- **2026-10-01, E1 main `2026-10-01T06-25-28-289Z-f159494e`:** the unconditional
  fixture capture ignored E0.2 and then refused main's legacy ingest response
  (`accepted=92`, empty `error`, nonempty `scanId`). The pin check now reads
  `evidence/baseline/fixture-pr0b/pins.json` and the captures' recorded provenance.
  This metadata projects PR 0b's published revision and fixture input surfaces;
  effective configuration and sampling remain owned by the capture and fixture spec.
  `git diff --name-only f938c4eb2 ac1c93bf3` over those surfaces identifies six
  changed corpus documents; the installed main worker stamp `55ffe7744cc7e103`
  also differs from the recorded `8a048084ff68bc54`. **Recapture required.**
  The original main record retains its failed outcome and SciFact evidence and now
  includes `fixtureDecision` with the exact command, changed paths, pins, stamps
  and capture hashes. Future E1 records retain the same decision evidence before
  launch. Legacy ingest acceptance requires a positive integer count, empty error
  and nonempty scan ID; operation envelopes keep their existing refusal predicate
  and cannot fall back to legacy fields. Focused proof:
  `node --test scripts/jseval/lane-f/e-run.test.mjs` (23 tests), including both
  E0.2 plan cases, build/pin mismatch and mixed-envelope refusal. Root's live
  E1 main invocation remains required.

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
| soak | owner sets duration before E4 (2026-09-24) | indexing, scripted agent and reconfigure workload; record continuous windows and total measured duration | E0.8 |
| floor machine | conditional on a minimum-spec claim | record the actual machine if claimed; a device-memory cap proves a mode path but not minimum-spec performance | E0.8 |
| corpus | the jseval reference corpus | `scifact` (5,183 documents, 300 queries) for the quality and indexing rows; the 91-document fixture corpus for the workflow fixture; the 100-document lock workload (B17) for the recovery rows | `evidence/baseline/README.md` |
| semantic-availability bound | D1 one-sided feature acceptance | D1 records refusal fraction, duration and bound for any floor-machine claim; not an E comparison | D1 |
| retained-state and admission caps | first cut by the implementer | D2 one-sided aggregate-bound acceptance confirms these values; E4 observes memory | C1/D2 |
| session-gate aging threshold and background batch bound | first cut at D2 | D2 one-sided request-time encoder acceptance confirms these values | D2 |
| allowed-difference classes | exactly three | `new-reason-code`, `equal-score-order`, `generative-text` — already code constants (`workflow_fixture.py:224`); none added after a diff | PR 0b |
| noise-pair gate | three captures per side, `maxNoisyFraction` 0.05, `scoreTieEpsilon` 0.001 | unchanged from PR 0b | `lane-f-workflow-fixture.v1.json` |
| collector, heap, `UseCompactObjectHeaders` | G1 default | confirm heap in E4; compare collectors/headers only after E2 failure or a specific benefit claim | §5 |
| hang poll interval and count | set from E4's observed safepoint pause | interval × count ≥ 3 × the worst observed pause, interval ≥ 10 s; write the register before E6 | §5 |

---

## 2. Instruments: what exists, what E adds

### Root driver (2026-10-01)

`scripts/jseval/lane-f/e-run.mjs` is the dependency-free Node driver. Run it
from the driver worktree after root's `quick_health`/shared-lease preflight.
It uses the already-built distributions in `lane-f-pr1-verify` (BRANCH) and
`lane-f-e-main` (MAIN, pinned `ac1c93bf3`). It does not build or install.
All jseval and lane-F instrument code comes from the driver tree; each arm's
dev-runner owns its stack. Launches use G1, `-Xmx2g`, standard chat profile,
fresh private data directories and `leaseDurationSec=3600`. The fixture uses
MAIN's unchanged documentation corpus and the fixture-pair launch pins;
E0.2's pin/build check selects main's three recorded PR 0b captures when valid,
otherwise three fresh fixture-cycle captures reproduce fixture-pair's capture
sequence without its force-cleanup of an active stack. Branch always captures
fresh; when main reuses the compact PR 0b fixture, branch uses that same fixture
profile. SciFact keeps its standard profile and shared materialization.

```powershell
node scripts/jseval/lane-f/e-run.mjs e1-quality --arm main --dry-run
node scripts/jseval/lane-f/e-run.mjs e1-quality --arm main
node scripts/jseval/lane-f/e-run.mjs e2-e3-load --arm main
node scripts/jseval/lane-f/e-run.mjs e0-values --arm main
node scripts/jseval/lane-f/e-run.mjs e1-quality --arm branch
node scripts/jseval/lane-f/e-run.mjs e2-e3-load --arm branch
# Run each following invocation separately for --arm main and --arm branch:
node scripts/jseval/lane-f/e-run.mjs e4-memory-soak --arm main --window 1
node scripts/jseval/lane-f/e-run.mjs e4-memory-soak --arm main --window 2
node scripts/jseval/lane-f/e-run.mjs e4-memory-soak --arm main --window 3
node scripts/jseval/lane-f/e-run.mjs e5-crash --arm main
node scripts/jseval/lane-f/e-run.mjs e6-hang --arm main
node scripts/jseval/lane-f/e-run.mjs table
```

Every subcommand accepts `--repo-root <path>` to resolve instruments, jseval,
values, corpus cache and output evidence in that checkout while executing the
driver and its `capability-ready.py` helper from their source worktree. This
allows root to exercise unmerged driver changes without copying files. Both
driver sources are included in the paired instrument identity.

After each owned start, the driver waits up to five minutes for health and
then up to five minutes for jseval's exact capability preflight:
`derive_intended_engines('lexical,hybrid', cross_encoder=True)` and
`assert_capabilities` from `jseval/preflight.py`. The latter reads and flattens
`/api/status`, requiring `rerankerModelPath`; it deliberately does not require
the lazy dense/SPLADE presence signals or CUDA initialization. Every attempt's
verdict is retained in `<start-label>-capability-ready.json`. This gate runs
before any measurement, including all fresh fixture starts, on either arm.
The captured effective config must resolve `justsearch.models.dir` to the shared
main checkout's `models` directory. The driver records the verified value.
Git trust is limited to the assigned arm in child-process environment; machine
facts use Node's OS API because Windows CIM is unavailable in the sandbox.

**2026-10-01 supervised readiness-fix window:** two of three permitted real
invocations were attempted, both before any measurement. Invocation
`2026-10-01T06-21-13-044Z-82616ed8` failed because sandbox-account Git ownership
rejection produced an empty revision, previously mislabeled as a pin mismatch.
Process-scoped trust and immediate nonzero-command checking fix that diagnosis.
Invocation `2026-10-01T06-22-16-797Z-ada7f994` reached startup but was denied
write access to `F:/justsearch-public/tmp/dev-runner/active.lock.json` (EPERM).
No stack was started; subsequent runner status reported `NO_ACTIVE_RUN`.
No third invocation was spent repeating this filesystem restriction. Root must
run with access to the existing shared runner state; redirecting that state
would bypass shared-stack ownership. Raw outputs and per-clause missing-evidence
records are in `lane-f-pr1-verify` under the paths described below. All four E1
clauses remain unvalidated. The earlier main capture
`2026-10-01T06-16-16-201Z-158dff46/9-_api_debug_effective-config.json` confirms
`F:/justsearch-public/models`; live branch confirmation remains outstanding.
Local regression evidence: `node --test scripts/jseval/lane-f/e-run.test.mjs`
(19 cases) and, with `PYTHONPATH=scripts/jseval`,
`python scripts/jseval/lane-f/capability-ready.test.py` (3 cases), retained in
the driver tree at `tmp/lane-f-e-readiness-tests.txt` and
`tmp/lane-f-e-capability-tests.txt`. No Gradle or commits were performed.

Every subcommand accepts `--dry-run`, which prints the process plan without
launching programs, contacting an API or writing evidence. E0 refuses to
refit after any recorded branch invocation, including a failed launch.
The load instrument runs fresh SciFact ingest under each combination of
idle/scripted agent and lexical/hybrid foreground traffic. Agent traffic
uses admission-loop's sequential workload mode rather than its saturation
oracle. All mutating HTTP clients carry the current per-boot token.

Invocation records and `index.json` live under
`evidence/E/<subcommand>/<arm>/`; raw outputs live under
`tmp/lane-f-e/<subcommand>/<arm>/<invocation>/`. Retain that tree through
stage F review; the driver never prunes it. The invocation deadline is
59 minutes, reserving the final minute for owned cleanup. The frozen
values file currently specifies 55, 55 and 10 minute soak windows; the
driver preserves all three boundaries and never calls them continuous.
`table` runs the existing fixture gate when both captures are present,
then reports every group and clause, including missing or failed evidence.

**Instrument gaps remain explicit.** The paired extensions are specified in
[the accepted E4–E6 instrument design](../evidence/E/e456-instruments-design.md).
`e456-live.mjs` collects scoped process/private-byte samples, every JVM's
GC logs and launch flags, scheduled real settings changes, and continuous
supervisor/Head log evidence. E5 kills the actual owned Engine or Worker
with accepted jobs in flight, sharing verified crash mechanics with the
real-writer fixture while retaining production cooldown. E6 attaches JDWP
only to an owned loopback debug port and suspends request threads or the
application VM; it uses no fake Engine or production code changes.
These implementations require root's live run before any acceptance claim.
MAIN has no Lane F operations/checkpoint ledger or recovering Head supervisor;
the literal unsupported E5/E6 clauses remain `unmeasurable-on-split`, with
native Worker results and the §16 disposition beside them. Missing GC,
checkpoint, active child, or fault-confirmation evidence remains unmeasurable.
The memory bound compares summed arm commit against split and checks each
JVM's documented launch limits; it invents no absolute process ceiling.
Run both E4 arms' three windows, then `e4-hang-values` before E6 to freeze
the observed safepoint-derived interval and misses. E7 remains
operator-driven and externally blocked on signing as specified in §7.

Static verification: `node --check scripts/jseval/lane-f/e-run.mjs`,
`node --test scripts/jseval/lane-f/e-run.test.mjs`, and
`npm run lint:scripts`. Runtime proof belongs to root's lease; writing
and testing the driver does not establish any E-group pass.

Authoring verification (2026-10-01, no live stack): Node syntax checks and
18 driver/wire-fixture tests passed; the admission oracle's 63 self-test
cases and 26 Python search-load tests passed. `git diff --check` and the
PowerShell comment gate passed. `npm ci --offline` failed with
`ENOTCACHED` for `debug`; `npm run lint:scripts` consequently could not
find ESLint. Lint remains unperformed until dependencies are available.

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

## 3. Seven paired run groups (owner re-cut 2026-09-24)

Each comparison uses the branch and current main on the same machine, corpus,
workload and instrument. Record source revisions, build identity, machine facts,
commands, raw results and both arms' values. Fix the owner's numeric bounds and
the soak duration before the run. No single run exceeds the existing one-hour
execution limit; split a longer owner-set soak into separately recorded windows
without calling them continuous. Reuse a recorded split fixture only if its
pinned surfaces still match main.

| run | paired section 16 group | procedure and required result |
|---|---|---|
| E1 | search quality and workflow fixture | Run jseval quality comparison and the pinned fixture on both arms. Require baseline gate and SearchTrace shape, deterministic evidence/citation/cancellation equality, and only the three predeclared difference classes. |
| E2 | search and agent response times during bulk indexing | Run idle-agent and scripted-agent loads on both arms while bulk indexing. Compare admitted search/API p95; count reason-coded rejections under the owner ceiling, with timeout/5xx as failure. |
| E3 | indexing speed | Measure chunks/s under the same foreground loads on both arms; require the owner-set fraction of split while queries continue. |
| E4 | memory budget and no-crash soak | Sum Engine, llama-server and children commit charge against section 8 and split, report working set, measure live-after-GC trend and zero crashes across the owner-duration indexing/agent/reconfigure soak. |
| E5 | crash recovery and children | Force an actual Engine death with a durable operation in flight. Compare crash-to-API restoration and checkpoint resume; require visible restarting, no orphaned child, correct adopt/stop behavior for each restart/quit/upgrade path. |
| E6 | hang detection, graceful and forced | Exercise runnable-watcher/API-pool wedge and whole-JVM wedge on both arms under fixed hang settings; require request-channel and forced-kill recovery within the respective deadline plus budget. |
| E7 | dead-Engine upgrade | Run the registered sandbox repair over an exhausted Engine on both applicable releases; reconcile children first, open every inherited store at the broken release schema and answer search. Apply section 16's existing signed-artifact disposition if unavailable. |

The minimum-spec-machine run, other-OS recovery and representative-change
exercise occur only when making those claims. They are not extra paired gate
groups. One-sided section 16 feature acceptance stays with the D1/D2 mapping tables.

---

## 4. Row procedure and verdict

For each of E1-E7, freeze the corpus, launch flags, workload and bound before
either arm. Run the split arm on current main and the branch arm with the same
instrument, or verify the workflow fixture's recorded split arm still matches
its pins. Preserve raw evidence and a per-clause verdict; a group passes only
when every clause in its section 16 row passes. Investigate a consistent loss
even inside a noise margin. A shared-mechanism fix re-runs every affected paired
group and its dependent one-sided D1/D2 feature proof.

The remaining section 16 rows are not E rows. D1 owns recovery workflow component
readiness, stuck component, generation transition, semantic availability,
combined low-memory reindex, delayed retry, resume/reconfigure behavior and
their installed real-model cuts. D2 owns aggregate admission/cursor bounds,
durability, request-time encoders, client re-entry, verification boot and
conditional positive-benefit evidence. Joint clauses name both owners in the
D1/D2 maps. Record a passed result only for the revision, environment and
assertions actually run; list unproved clauses in those stage records.

---

## 5. Collector and hang values

Use G1 and the current Engine heap for E1-E7. Confirm the heap against the
memory row and measure GC/safepoint pauses during E4 to set the hang interval
and miss count before E6. If E2's response-time group fails, run the bounded
collector/heap probe with the same corpus and workload; choose the best pair
within the response and memory bounds, pin it in both spawn sites, rerun the
affected groups, and record the change. A G1-versus-ZGC bake-off and compact
object header probe are otherwise conditional on a claimed benefit.

---

## 6. Representative changes (conditional positive-benefit claim)

Run this exercise only if claiming lower coordination cost. Named here, before the flip, so they cannot be chosen after the fact: **retrieval** — add one
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

## 7. The dead-Engine upgrade round (E7)

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

## 8. The record (evidence/E/)

- `development-cost.md` reports five descriptive paired measures on `main` and
  the branch. They do not add a merge gate or trigger the conditional
  representative-change exercise. Use the same machine and frozen corpus:

  | measure | command or owned instrument |
  |---|---|
  | Full-suite wall clock | `Measure-Command { ./gradlew.bat test --max-workers=1 }`, with revision, cache state and result |
  | Stack start to first successful search | `python -m jseval run --dataset scifact --modes lexical --pipeline --start-backend --clean --max-queries 1 --timeline tmp/start-first-search.tsv` from `scripts/jseval/`; record launcher and first successful query timestamps in jseval's result. Add that field to jseval if absent before reporting a number. |
  | Verification-profile boot | D2-2's `dev-runner --profile verification` boot receipt, timed to the first ready API response; D2-2 must land the flag and receipt before this row runs. |
  | Whole-Engine hot reload | `justsearch-dev reload` on an owned stack after a small class change in each Engine half; record whether one reload covers both, its revision and result. |
  | Production Java lines by ring | `rg --files modules -g '*.java' -g '!**/test/**'`; classify the tracked production paths through the stage F subsystem map, then count physical lines with `Measure-Object -Line` for API front, core and edges separately. Keep generated sources separate. |

- values.json fixes the seven groups' bounds, corpus, G1/heap, soak duration
  supplied by the owner and hang values before their runs.
- One directory for each E1-E7 group holds commands, branch and main revisions,
  machine facts, raw files or accessible retained-artifact links, analyzer
  output, both arms' values and each clause's verdict.
- table.md lists the seven paired groups with pass/fail/unmeasurable, source
  run and reason. It links D1/D2's separate one-sided feature-acceptance table
  without presenting those rows as E passes.
- decision.md records remedies and reruns for failed groups, plus the bounded
  conclusion: machine, corpus, workload, soak duration and fault scenarios.
  Conditional floor/OS/representative/collector exercises are named when run,
  and claims are bounded when they are not.

### Host decision inputs (WP4)

Record these observations in `evidence/E/host-decision-inputs.md` from the named runs; they
inform the first post-Lane-F host review without adding another E gate:

| Input | Source row and observation |
|---|---|
| Native encoder faults | E4 soak and E5 crash recovery: count supervisor `native crash` exits and retain exact exit evidence. |
| Encoder reconfigure cost and text-only window | D1 Flow B beside and in-place feature acceptance plus E2 foreground search: wall clock from reconfigure admission to encoder READY, and time in `RELOADING` while lexical search answers. |
| Encoder share of Engine restart | E5 recovery: difference between API/text readiness and encoder/semantic readiness on the same boot, with the actual component timestamps. |
| Request-time encoder latency | E2 idle and contended search: query embedding, query sparse encoding, rerank and citation timings where active, with model identity, workload and contention recorded. |

State unavailable measurements as such; a restart interval is a proxy for encoder load only when
the run's timeline identifies that phase. Apply design §5's trigger/revisit rule in the E decision.

Stage F reads this directory and the D1/D2 stage records.

---

## 9. Required verdict at E

Every E1-E7 group must pass all applicable clauses, or have an explicit
installer/signing disposition already allowed by design section 16. Linux or
another OS and a minimum-spec machine are tested only if their coverage is
claimed; an unrun shape is named as unclaimed, not marked passed. The one-sided
D1/D2 rows must have completed feature acceptance or a named gap in their
own stage records. An unmeasured owner-duration soak cannot pass E4.

---

## 10. Stop rule (17.8)

A failed paired group receives the section 2 remedy and a rerun. A fix to a
shared mechanism reruns every affected E group and dependent D1/D2 proof.
A process-boundary remedy triggers the design re-read at sections 2 and 14
before code changes. Record the failed number, cause and rerun in decision.md.

---

## 11. Open questions

- **Owner-duration soak:** decided 2026-09-30: two hours as two separately recorded
  one-hour windows, not continuous ([values.json](../evidence/E/values.json)).
- **Conditional claims:** D1/D2 and F must state whether minimum-spec hardware,
  other-OS recovery or lower coordination cost is claimed. Run the matching
  conditional exercise only for a claim; otherwise bound the report explicitly.

---

*End of the stage-E runbook. Instruments and values are re-verified at stage start; corrections
found while running go to §0.1.*
