---
title: "Stage E manual paired decision"
created: 2026-10-04
status: "draft for review; owner dispositions recorded"
---

# Stage E manual paired decision

Date: 2026-10-04. Lane: `codex/lane-f-pr1`; drafting checkout revision
`2dc9346f7c652d58de8c1c20fdd1f7cb19223656` (read with `git rev-parse HEAD`).
MAIN is pinned to `ac1c93bf3`. This is a manual assessment of the retained
measurements, authorized by the owner's dated disposition below. The generated
[table](table.md) remains the automated result; this document does not rewrite its
failures or the acquisition identities.

The evidence supports quality within the declared noise allowance, lower peak
summed process commit, and actual supervised recovery on this reference machine.
It does **not** establish a passing joint envelope: measured fixture, response-time,
indexing, soak and hang failures remain, alongside measurement gaps. Owner acceptance
of a particular deviation is identified at its clause, not extended to other clauses.
The applicable rule is [design section 16][design] with [E sections 2, 9 and 10][runbook].

## Evidence selection and bounds

Selection uses `latestRecords(process.cwd())` from
[e-run.mjs](../../../../../scripts/jseval/lane-f/e-run.mjs), loaded with
`node --input-type=module -e "import { latestRecords } from './scripts/jseval/lane-f/e-run.mjs'; const records = latestRecords(process.cwd());"`.
E2/E3 use both `workloadRecords`; E4 uses all corresponding `windowRecords`, not
just its tail record. E1 paired quality uses its pinned reference. Short source
labels in the tables below link to the actual JSON records.

| Evidence | MAIN | BRANCH | BRANCH recorded revision / runtime Head build stamp |
|---|---|---|---|
| E1 | [M1][M1] | [B1][B1] | `234dcc781` / `df92175ac4f00b80` |
| E2/E3 agent idle | [M2i][M2i] | [B2i][B2i] | `dbdb0c7d5` / `df92175ac4f00b80` |
| E2/E3 scripted agent | [M2s][M2s] | [B2s][B2s] | `4afe0b7b9` / `df92175ac4f00b80` |
| E4 first window | [M4a][M4a] | [B4a][B4a] | `4a856d76e` / `ea782678a63bf4de` |
| E4 second window | [M4b][M4b] | [B4b][B4b] | `7e269aed5` / `ea782678a63bf4de` |
| E4 tail | [M4c][M4c] | [B4c][B4c] | `f691dd532` / `ea782678a63bf4de` |
| E5 | [M5][M5] | [B5][B5] | `4986e4667` / `ea782678a63bf4de` |
| E6 | [M6][M6] | [B6][B6] | `bbe065d1a` / `ea782678a63bf4de` |

MAIN's runtime Head stamp is `eb365b7032da7fa6`; its separate Worker stamp is
`55ffe7744cc7e103`, visible in [M5][M5]'s killed-process command. Product stamps,
record revisions and the drafting revision are different identities.

Machine facts: `DESKTOP-FA00PO7`, Windows `win32` release `10.0.26200`, `x64`,
Intel Core i7-12700K, RAM `34028519424` bytes, Node `v24.12.0` ([M1][M1], [B1][B1]).
Both retained GPU receipts identify NVIDIA GeForce RTX 4070, `12282 MiB`, driver
`610.88` ([MAIN GPU receipt][gpu-main], [BRANCH GPU receipt][gpu-branch]); GPU
facts are supplementary receipts, not fields invented in `machine`.

The frozen [values][values] name SciFact with 5,183 documents and 300 queries,
the 91-document workflow fixture and the 100-document recovery workload.
The E2/E3 records actually declare `expectedDocuments=5184`; their rates use
that recorded population, not the nominal values-file count ([M2i][M2i], [M2s][M2s],
[B2i][B2i], [B2s][B2s]). E2/E3 run a 1,200-second foreground window for each
workload, hybrid then lexical, with the same recorded query-pool hash. E4 measures
55 + 55 + 10 minutes per arm, totaling 120 separately bounded minutes, with
indexing, agent traffic and scheduled reconfigure. All six records retain their
start/end/teardown boundaries. BRANCH's first and second windows are separated
by the owner pause; this is not a continuous two-hour soak ([values][values],
[M4a][M4a], [M4b][M4b], [M4c][M4c], [B4a][B4a], [B4b][B4b], [B4c][B4c],
[ledger, pause/resume entries][ledger]). E section 11's two-one-hour wording
disagrees with these frozen values and records; the records win.

### Acquisition drift and its limits

The E1–E5 pair identities differ. Comparing retained `pairIdentityInputs` shows
changed hashes for `scripts/dev/lib/process-identity.cjs` and
`scripts/dev/lib/stop-exit-census.cjs`, changed in the evening campaign after
MAIN's captures (P1 `8978ad3c8`, Q14 `e6e7d142d`; [ledger][ledger]). E5 additionally
lost `models/onnx/gte-multilingual-base/model.onnx.optimized` and its `.opt-meta`
receipt during tempdoc 958's legacy cache cleanup. Comparing the E5 inventories
by path shows those removals, with no added or changed remaining inventory
entries ([M5][M5], [B5][B5]). The same cache/receipt removals also occur in each
selected E4 window pair ([M4a][M4a], [M4b][M4b], [M4c][M4c], [B4a][B4a],
[B4b][B4b], [B4c][B4c]); the ledger's final drift note singles out E5, but the
records show that E4 shares this inventory drift. Configured source weights
remain recorded. The optimized
graph is a regenerable cache, but its absence can affect model startup; this manual
comparison cannot prove identical cache-warmth or attribute the recovery delta
solely to merging the JVMs.

Reading the helpers narrows the scope of the drift:

- [process-identity.cjs][process-identity] reads the Windows process table,
  canonicalizes birth identity, checks PID/birth/command/freshness and supplies
  bounded asynchronous identity lookup. It does not terminate targets. Its
  verdicts affect ownership verification, including which processes stop/fault
  paths may act on, and how observers bind an exit to a process.
- [stop-exit-census.cjs][stop-census] opens an observer before teardown, binds
  Engine and registered children to OS births, and produces exit codes, times,
  expected-termination classifications and coverage gaps. It recognizes an
  identity-bound extraction-child termination intent; it does not itself kill
  targets and cannot supply historical exits missing from the registry.

The retained instrument hashes show no change to quality scoring/capture,
foreground latency acquisition, RSS/private-byte sampling, GC parsing or crash-file
scanning attributable to this drift. **Owned-exit crash classification and teardown
coverage do depend on the changed census**, so an unqualified assertion that all
crash measurement was unchanged would be incorrect. Process-identity lookup also
supports the fault target checks. These are the documented limits of the owner's
manual comparison, not proof that the helpers are irrelevant ([M1][M1], [B1][B1],
[M5][M5], [B5][B5], helper sources above).

E6's acquisition identities match exactly ([M6][M6], [B6][B6]); both arms were
captured on 2026-10-04. Identity agreement does not cure MAIN's missing fault
observations or BRANCH's failed deadline measurements.

There is a separate aggregate-driver issue: `latestRecords()` marks BRANCH's
merged E2/E3 record `Workload provenance mismatch`, because the two source
revisions differ. Their `valuesHash`, query pool and runtime Head stamp agree
([B2i][B2i], [B2s][B2s]); [mergeLoadRecords][driver] nevertheless requires revision
equality. This manual document exposes that aggregation flag and assesses the
individual workloads. A shared stamp is evidence of the same runtime build,
not a new rule silently applied to the generated table.

## E1 — quality and workflow fixture

Group: **fail**. The quality clauses pass manually; the retained workflow diff
contains regressions. The ledger's earlier description of fixture “gaps” is
superseded by the retained [fixture-gate report][fixture].

| Clause | MAIN value | BRANCH value | Rule | Manual verdict | Evidence |
|---|---|---|---|---|---|
| baseline-quality | `true` | `true` | Historical quality ratchet must hold, separately from paired quality (E section 1). | pass | [M1][M1], [B1][B1] |
| paired-quality | Hybrid SciFact nDCG@10 `0.7588375946421915` (pinned) | `0.7574770785114983` | BRANCH ≥ pinned MAIN − `0.01` (E section 1). | pass | [M1][M1], [B1][B1], [runbook][runbook] |
| SearchTrace-shape | `true` | `true` | Same SearchTrace shape, design section 16. | pass | [M1][M1], [B1][B1] |
| workflow-evidence-citations-cancellation | c01 citation target: coordination passage only; c03 event sequence includes reasoning/tool proposal | c01 adds testing-strategy passage; c03 shorter event sequence | Declared deterministic targets/events must match; cancellation and evidence fields require captured proof. | fail | [M1][M1], [B1][B1], [fixture][fixture] |
| allowed-differences | Baseline for paired fixture | Diff: 2 regressions, 6 allowed, 213 equal, 0 missing, 1 noisy field | Only predeclared new-reason-code, equal-score-order, generative-text classes; no retrospective class additions. | fail | [fixture][fixture], [values][values] |

The fixture reports health `ok=true` and no declared-but-uncaptured fields.
`chatTurns/c01.citationTargets` and `chatTurns/c03.eventNames` differ under
`class=exact`; these are failures, not missing-instrument gaps. Other equal
fields, including cancellation, do not cancel those failures ([fixture][fixture]).

## E2 — foreground and agent response under indexing

Group: **fail**. Latencies are admitted successful-request p95s; the wire clause
separately retains unsuccessful requests. Values below are milliseconds.
The per-workload ceilings in [values][values], rather than the maximum across
workloads, control the foreground comparison.

| Clause | MAIN value | BRANCH value | Rule | Manual verdict | Evidence |
|---|---|---|---|---|---|
| indexing-window-valid | Both windows `true`, 1,200 s each | Both windows `true`, 1,200 s each | Active bulk/enrichment, valid boundary counters and same query pool on both workloads (E section 2). | pass | [M2i][M2i], [M2s][M2s], [B2i][B2i], [B2s][B2s] |
| foreground-p95, idle hybrid / lexical | `259.213400` / `17.027600` | `279.010200` / `23.516200` | Ceilings `285.134740` / `18.730360` = corresponding MAIN × `1.10`. | fail (lexical); hybrid passes | [M2i][M2i], [B2i][B2i], [values][values] |
| foreground-p95, scripted hybrid / lexical | `1551.859500` / `18.479300` | `689.511600` / `26.486300` | Ceilings `1707.045450` / `20.327230`. | fail (lexical); hybrid passes | [M2s][M2s], [B2s][B2s], [values][values] |
| agent-api-p95 | `5568.798600`; 471/472 admitted/offered | `3756.844400`; 149/160 admitted/offered | Successful admitted API p95 ≤ `6125.678460`; wire failures assessed separately. | pass | [M2s][M2s], [B2s][B2s], [values][values] |
| idle-rejections | 0 wire rejections / 47,553 offered | 0 / 41,843 | Zero rejection ceiling. | pass | [M2i][M2i], [B2i][B2i], [values][values] |
| scripted-rejections | 0 / 42,090 wire requests | 0 / 31,694 | At most `0.01` admission rejection fraction, with legal reason/retry receipts. Failed terminals are not admission rejections. | pass | [M2s][M2s], [B2s][B2s], [values][values] |
| no-timeout-or-5xx | Idle: 47,551 HTTP 200, 2 boundary censored; scripted: 42,087 HTTP 200, 3 boundary censored | Idle: 41,841 HTTP 200, 2 boundary censored; scripted: 31,660 HTTP 200, 24 HTTP 504, 5 LLM_ERROR, 2 missing HTTP status, 3 boundary censored | Candidate must have no non-boundary timeout, 5xx or invalid terminal. MAIN counts are baseline facts (E section 2 amendment). | fail | [M2i][M2i], [M2s][M2s], [B2i][B2i], [B2s][B2s] |

Keeping the GPU-pressure result is an owner disposition to retain and diagnose
the failure, not a timeout waiver. No collector comparison is evidenced after
this response-time failure; the conditional remedy remains unperformed.

## E3 — progress under the same foreground load

Group: **fail across both workloads**; the owner accepted the idle primary-rate
shortfall as a **pass-with-recorded-deviation**. Rates are completed units/s over
the recorded stage-active interval, not full-run average throughput.

| Clause | MAIN value | BRANCH value | Rule | Manual verdict | Evidence |
|---|---|---|---|---|---|
| indexing-window-valid | Both `true` | Both `true` | Same E2 windows and query pool; active intervals must be measurable. | pass | [M2i][M2i], [M2s][M2s], [B2i][B2i], [B2s][B2s] |
| stage rates, idle primary | `18.3109182662` | `15.1631987835` | ≥ `16.4798264396` (MAIN × `0.90`). | pass-with-recorded-deviation | [M2i][M2i], [B2i][B2i], [values][values], [owner decision][ledger] |
| stage rates, idle embed / SPLADE / NER | `0.7349966214` / `3.4458174937` / `0.0008333295` | `1.1391593349` / `3.5591437595` / `0.3549977152` | Each positive MAIN rate × `0.90`, independently. | pass | [M2i][M2i], [B2i][B2i], [values][values] |
| stage rates, scripted primary / embed / SPLADE | `12.2587497580` / `0.1124979330` / `1.2816431177` | `2.7332646480` / `0` / `0.3249918332` | Minima `11.0328747822` / `0.1012481397` / `1.1534788060`. | fail | [M2s][M2s], [B2s][B2s], [values][values] |
| Other stage comparisons | Idle chunk rate `0`; scripted chunk and NER `0` | Idle chunk `0.3333311880`; scripted chunk and NER `0` | Zero MAIN rate supplies no relative comparison. A branch-only gain cannot establish relative pass. | unmeasurable (relative clauses with zero baseline) | [M2i][M2i], [M2s][M2s], [B2i][B2i], [B2s][B2s] |
| chunk-progress-under-foreground-load, idle | 0 completions despite pending chunks | 400 completions, positive rate | Positive candidate chunk progress whenever chunks are pending; MAIN starvation is baseline, not candidate acceptance. | pass | [M2i][M2i], [B2i][B2i] |
| chunk-progress-under-foreground-load, scripted | 0 completions despite pending chunks | 0 completions despite pending chunks | Same absolute anti-starvation condition. | fail | [M2s][M2s], [B2s][B2s] |

The ledger rounds idle primary to 83% and MAIN idle NER to zero. The records
show a small positive MAIN NER rate. “Other stages exceed MAIN; no starvation”
describes the idle window; it does not describe [B2s][B2s]'s GPU-pressure window.
The owner's E3 acceptance is recorded below in its original substance. No
additional waiver of the scripted anti-starvation/rate clauses is recorded.

## E4 — memory and owner-duration soak

Group: **fail**, with shared heap-growth failure and candidate wire failures;
component budget, complete crash coverage and some workload coverage are
**unmeasurable**. Window order below is first / second / tail.

| Clause | MAIN value | BRANCH value | Rule | Manual verdict | Evidence |
|---|---|---|---|---|---|
| component-commit-budget | Consumer private-byte attribution unavailable | Same gap, including host ORT | Every component must fit the section 8 consumer budget; flags or process totals are not substitutes (E section 2). | unmeasurable | [M4a][M4a], [M4b][M4b], [M4c][M4c], [B4a][B4a], [B4b][B4b], [B4c][B4c] |
| launch-flag-compliance | `true` in each window | `true` in each window | Recorded per-JVM launch flags comply; G1 and packaged heap pins retained. | pass | [M4a][M4a], [M4b][M4b], [M4c][M4c], [B4a][B4a], [B4b][B4b], [B4c][B4c] |
| machine-wide-commit-vs-main | Peak summed arm private MB: `28727.66015625` / `21965.8125` / `23839.99609375` | `26568.87890625` / `26590.0859375` / `23795.00390625` | Maximum over all windows ≤ MAIN maximum; sum includes owned JVMs/native children. This is the arm process sum, not whole-desktop commit. | pass | [M4a][M4a], [M4b][M4b], [M4c][M4c], [B4a][B4a], [B4b][B4b], [B4c][B4c] |
| working-set | Peak MB: `17741.37890625` / `15902.24609375` / `18431.15234375` | `16981.890625` / `20354.6640625` / `18362.953125` | Report beside commit, not a separate “lower RSS” gate. | pass (reported) | [M4a][M4a], [M4b][M4b], [M4c][M4c], [B4a][B4a], [B4b][B4b], [B4c][B4c] |
| live-after-GC-trend | Summed post-warmup slopes: `2420924.4586` / `2244711.2749` bytes/min | `97231.5626` / `211596.6351` bytes/min | No live-after-GC growth in either long window; tail contributes duration/memory/crashes, not slope acceptance. | shared-baseline-fail | [M4a][M4a], [M4b][M4b], [B4a][B4a], [B4b][B4b] |
| zero-crashes | One confirmed native JVM crash in second-window teardown; complete exit coverage unavailable in all windows | No confirmed JVM crash in retained events; each window flags llama-server exit 1, missing expected termination, incomplete accounting | Candidate zero crashes through teardown for every owned JVM/native child. MAIN crash is baseline; missing coverage cannot pass. | unmeasurable | [M4a][M4a], [M4b][M4b], [M4c][M4c], [B4a][B4a], [B4b][B4b], [B4c][B4c] |
| no-timeout-or-5xx | HTTP 504: `12 / 13 / 1`; timeout errors `2 / 2 / 0`; terminal/request failures also retained | HTTP 504: `3 / 4 / 0`; timeout errors `5 / 4 / 0`; terminal/request failures also retained | Candidate validity, MAIN baseline counts; a shared failure does not waive BRANCH acceptance. | fail | [M4a][M4a], [M4b][M4b], [M4c][M4c], [B4a][B4a], [B4b][B4b], [B4c][B4c] |
| owner-duration | `55 / 55 / 10` measured minutes | `55 / 55 / 10`; shared runtime stamp across slots | All owner windows and total measured; frozen values and same build. | pass | All E4 records above, [values][values], [same-build amendment][runbook] |
| index-agent-reconfigure-workload | Clause `true` in every window | `true` only in second window; absent in first/tail; cycle coverage `completed=0` in all slots | Indexing, agent and reconfigure overlap must be demonstrated, independently of wire validity. | unmeasurable | [M4a][M4a], [M4b][M4b], [M4c][M4c], [B4a][B4a], [B4b][B4b], [B4c][B4c] |

The ledger calls the first BRANCH stop clean: ordered shutdown `clean=true`,
Engine exit 0, llama-server PID 25184 exit 1 from Windows `TerminateProcess`,
without `expectedTermination` ([ledger, 18:32][ledger]). The records flag the same
pattern at subsequent quits, but their exit `atMs=-11644473600000` yields the
impossible `timing=before-window`. That timestamp is not a genuine pre-window
crash time. The census lacks intended-child-stop classification and historical
descendant receipts; the records contain 425 / 455 / 84 exit-accounting gaps
([B4a][B4a], [B4b][B4b], [B4c][B4c]). These are classification/coverage gaps,
not evidence of three product crashes and not a crash-free pass.

MAIN's second-window PID 37764 does have `hs_err` evidence: an access violation
in `onnxruntime.dll`, after the measured window during teardown. Its accompanying
Java crash report names `AlreadyClosedException` in the Lucene reopen thread.
Do not mistake the Java report for the native frame or omit teardown from the
baseline ([M4b][M4b], [native error log][main-hs], [Java crash report][main-crash]).

## E5 — actual crash, checkpoint and children

Group: **shared-baseline-fail** for index-restoration and child classification;
literal paired checkpoint/supervisor clauses remain **unmeasurable** on MAIN.
Candidate API recovery and recorded operation resume are observed.

| Clause | MAIN value | BRANCH value | Rule | Manual verdict | Evidence |
|---|---|---|---|---|---|
| actual-death-durable-operation | Identity-verified Worker death with PROCESSING job; no operation revision ledger | Identity-verified Engine death with RUNNING ingest and PROCESSING revision-bearing unit | Actual owned death with durable work in flight (design section 16). MAIN native job evidence is available; literal Lane F operation identity is unavailable. | pass for death/in-flight work; unmeasurable for paired operation identity | [M5][M5], [B5][B5] |
| crash-to-api | `872 ms`; Head survived, first post-death successful probe | `3713 ms`; successor API | ≤ `13600 ms`, first cooldown + frozen warm-start budget. | pass | [M5][M5], [B5][B5], [values][values] |
| crash-to-index | `16872 ms` | `24239 ms` | Same `13600 ms` bound, E section 2 amendment. | shared-baseline-fail | [M5][M5], [B5][B5], [values][values] |
| checkpoint-resume | Native job replay, no Lane F checkpoint ledger | RUNNING → COMPLETE; cursor `ingest-progress:1:19` → `ingest-receipt:1:104:75166b0522de068fc63fee628f60d5cf6ea35a49b058ea3ebccb41d9289fac56`; attempts `1 → 2`, units completed `16 → 16`, no duplicate effects | Resume durable operation from checkpoint, not from start. BRANCH clause `true`; MAIN literal comparator missing. | unmeasurable (paired); candidate observed pass | [M5][M5], [B5][B5] |
| visible-restarting | Head logs narrate Worker restart; no `supervisor.v1.json` | Clause `true` | Restart visible in supervisor state, with split-native disposition retained. | unmeasurable (paired); candidate observed pass | [M5][M5], [B5][B5] |
| no-orphaned-child | `conhost.exe` PID 36248, child of adopted llama-server 33304, classified orphan | `conhost.exe` PID 21476, child of adopted llama-server 15884, classified orphan | No orphan after restart; healthy llama-server adoption is allowed. | shared-baseline-fail (classification) | [M5][M5], [B5][B5] |
| restart-quit-upgrade-child-policy | Crash/restart `healthyLlamaAdopted=true`; restart survivor llama-server 32000; quit/upgrade survivors empty; crash `extractionStopped=false` | Same booleans; restart survivor llama-server 9872; quit/upgrade survivors empty; crash `extractionStopped=false` | Adopt healthy llama-server on crash/restart; quit/upgrade must leave none. | shared-baseline-fail for full recorded clause; adoption and quit/upgrade subclauses pass | [M5][M5], [B5][B5], [ledger][ledger] |

Restart-path llama survival is adoption by design, not a native crash. The
aggregate child-policy boolean is false on both arms despite passing adoption
and quit/upgrade subclauses; `extractionStopped=false` on the crash experiment
and the conhost classification prevent a full acceptance claim. BRANCH's stop
regression was rerun and quit survivors are empty; that does not erase these
shared residuals. The latest recovery values above supersede the earlier
`3738 / 23479 ms` BRANCH result in the ledger ([earlier E5][B5old], [B5][B5]).

## E6 — graceful and forced hang

Group: **fail**, with missing MAIN measurements. The matching identities mean
this group has no E1–E5 drift exception. BRANCH invocation exit 0 means the
capture finished; it does not mean the acceptance deadlines passed.

| Clause | MAIN value | BRANCH value | Rule | Manual verdict | Evidence |
|---|---|---|---|---|---|
| runnable-watcher-api-pool-wedge | Request-channel deadline; no validated observation | Injected, preHealthy/postUnresponsive true, request observed; forced=true, graceful=false | Confirm request-thread wedge with runnable watcher, then cooperative recovery. | unmeasurable (paired injection); fail for cooperative recovery | [M6][M6], [B6][B6] |
| whole-JVM-wedge | Not reached; no observation | Injected, preHealthy/postUnresponsive true, forced=true | Whole-JVM wedge ignores channel and is killed. | unmeasurable (paired); candidate fault/kill observed pass | [M6][M6], [B6][B6] |
| graceful-deadline | Unavailable | Request-to-index restoration interval `36723–38591 ms`; forced stop | Recover cooperatively within the policy request deadline plus first cooldown and frozen warm-start budget; retained deadline clause is false. | fail | [M6][M6], [B6][B6], [values][values], [supervision policy][policy] |
| forced-deadline | Unavailable | Request-to-index restoration interval `62175–65691 ms` | Same recovery bound; mandatory forced path. | fail | [M6][M6], [B6][B6], [values][values], [policy][policy] |
| E4-derived-hang-settings | No validated clause measurement in failed MAIN invocation | Clause `true` | Frozen interval `10000 ms`, miss count `3`, worst E4 pause `520.782 ms`; interval × count ≥ three times pause and interval ≥ `10000 ms`. | unmeasurable (paired); candidate setting proof passes | [M6][M6], [B6][B6], [values][values] |

MAIN's first/latest E6 attempt never logged “worker unresponsive” inside the
window; its retained failure is `Hang request channel: deadline` ([ledger][ledger],
[M6][M6]). No MAIN soft-recovery latency can be inferred, and the whole-JVM
experiment and its deadline were not measured. The driver labels soft clauses
`unmeasurable-on-split`; that disposition is a gap, not proof MAIN hung or
recovered. BRANCH's hard-request lower bound precedes injection and produces a
negative detection bound; do not interpret it as negative physical detection
latency. The observed recovery intervals and failed deadline clauses remain
recorded, while request-file timing is an actuator-log interval ([B6][B6]).

## E7 — signed dead-Engine upgrade

Group: **unmeasurable**, with the allowed signing disposition.

| Clause | MAIN value | BRANCH value | Rule | Manual verdict | Evidence |
|---|---|---|---|---|---|
| signed-dead-Engine-upgrade | No signed round | No signed round | Final signed repair must reconcile children and reopen inherited stores/search; if signing blocks it, carry the named gap to first eligible installer. | unmeasurable; deferred to next signed release | [generated table][table], [design section 16][design], [E section 7][runbook], [owner disposition][ledger] |

## Owner dispositions

The following preserves the ledger's decisions in substance and date. These
approve a disposition of evidence; they do not turn missing measurements into
passes or supply a general default-flip waiver.

- **2026-10-03, approximately 15:35:** “E3 ACCEPT with recorded deviation:
  primary 83% of MAIN under load (<0.90 per-stage bar) while every other stage
  beats MAIN and no starvation; record in E table/decision.md and report-back;
  follow-up: rebalance background shares if wanted.” The paired records bound
  that description to agent-idle ([ledger][ledger], [M2i][M2i], [B2i][B2i]).
- **2026-10-03, approximately 15:35:** “E2 scripted-agent: KEEP the GPU-pressure
  result (2026-10-03T12-59-26-654Z-2f0996a4) as-is with diagnosis E2SD attached
  (free VRAM 60 MiB; LLM 31 vs 58 tok/s; SPLADE batches 234-683 s; 504s = hybrid
  search ~15 s timeouts in native ORT encode); follow-up: co-resident GPU policy
  degrades under VRAM pressure (budget-aware policy, per-process VRAM capture).”
  This retains the E2/E3 pressure result and diagnosis, including its failed
  progress/wire clauses. Diagnostic numbers here come from the ledger, not an
  invented per-process VRAM series ([ledger][ledger], [B2s][B2s]). The ledger
  separately records concurrent edit-only sessions/owner apps as a quiet-run
  deviation; causal attribution is bounded accordingly.
- **2026-10-02, approximately 19:50:** “E7 (signed upgrade proof): DEFER with
  recorded gap, destination = next signed release (design 16 disposition).”
  This predates the subsequent campaign and is retained in the ledger's owner
  decisions, not redated to the manual review ([ledger][ledger]).
- **2026-10-04, 02:54 campaign report:** “OWNER DECISION: manual per-clause
  verdict with documented drift (no MAIN recapture).” Scope is the helper/cache
  drift described above ([ledger][ledger]).

## Remedies and reruns — E section 10

These are campaign observations from the [orchestrator ledger][ledger], with
retained records where available. Local test results cited here are ledger
receipts, not checks executed during this drafting assignment.

| Failure / root cause | Remedy and review | Rerun / disposition |
|---|---|---|
| Stale installed distribution: branch stamp `0df23ef29b8098e2`, pre-campaign jars; `build -x test` did not refresh installDist. | Start gate changed to assemble plus installDist, refusing jars older than last product commit; fresh stamp `bae23e144d2317a6`. | Stale E1 `2026-10-03T04-41-09-481Z-5c492474`, E2 idle `2026-10-03T05-08-22-668Z-7e0ff456`, scripted `2026-10-03T05-31-05-684Z-1edb1211`, E5 `2026-10-03T05-53-49-362Z-1c88aa59` superseded; fresh q-branch2 E1 [577dad40][B1fresh], idle [322331c0][B2fresh], scripted [85de9392][B2freshs], then final records selected above. |
| Fresh-dist primary rate remained low; S5 PreparedExtractionInput temp-directory/copy/delete cost suspected. | Bisect: base `409b1926b` 124 docs/s, pre-S5 `b09a94364` 117, S5 merge `b74f2aa83` 59, integration `1f463c197` 60. Snapshot fix `e623ebdf9`, merged `c623a4778`, probe 116 docs/s. Concurrent builder load contaminated earlier q-s5check; quiet final campaign required. | q-s5check E1 [b629bf63][B1s5] and idle [57097f7b][B2s5] retained as intermediate evidence, not final selection. Final idle [B2i][B2i] reproduces the primary shortfall, so it is not dismissed as noise. |
| Snapshot fix review found spilled PDF/image reload and Office MIME-admission bypasses; repeated corrections required redesign. | `60eae54a4` follow-up; RS5Pb rejection → v2 lazy materialization, file-backed consumers and metadata-driven text fast path. `1d2e048ec` → RS5V2 MIME-policy rejection; `43b872b58` supplements declared Office type without replacing detected MIME, memoizes detection; RS5V2b rejection → `97784d6de`; RS5V2c approve-with-fixes, test precision `b4419f16b`; accepted merge `234dcc781`. | Final q-branch3 E1 [B1][B1], E2/E3 [B2i][B2i] / [B2s][B2s]. Owner primary deviation and retained GPU-pressure failure, rather than further noisy micro-probes. |
| q-branch3 E4 windows two/three failed at AI activation ([fe224128][B4failed2], [72d2c4c2][B4failed3]): first-window stop left llama-server 22004 holding GPU memory. Failed native drain skipped inference closure; dev-runner fallback PID binding read failure as death and discarded outcomes. | STOPF `078b1148f`; RSTOPF rejection for lock contention/discovery gaps → `db6c06816`; RSTOPFb regression corrected by root; accepted under stopping rule, merged build at `4986e4667`. Discovery-phase lifecycle gaps parked in process-ownership follow-up. | All E4 slots and E5 rerun, not only failed slots: [B4a][B4a], [B4b][B4b], [B4c][B4c], [B5][B5]. First rerun stop left no Java/llama, per ledger; quits now have no E5 survivors. E1–E3 retained because change was quit-path-only. |
| Evidence commits moved BRANCH revision between E4 slots, incorrectly blocking same-build owner-duration and downstream E6. | Scoring fix `1e13b66c0`: same revision or shared observed Head build stamp; recorded local tests `12 + 49` green. | q-branch6 froze hang values and acquired [M6][M6] / [B6][B6]. Current `latestRecords()` owner-duration is true on both E4 arms, even though stale gap text remains in records/table. |
| E5 evidence commit blocked by gitleaks generic-api-key on operation UUID fields. | Rule-targeted, path-anchored operation-key allowlist `.gitleaks.toml`, commit `8297a6c6f`, gitleaks clean in ledger. | Evidence retained; no numeric gate relaxed. Initial [B5old][B5old] superseded by post-stop-fix [B5][B5]. |

The ledger also records hosted Windows conformance readiness/timing flakes and
environmental local status-test timeouts, with reruns/follow-ups. They are not
new E acceptance measurements and are not used to override the retained failures.
No process-boundary remedy was implemented in this drafting package. The ledger
does not establish a fresh dependent D1/D2 rerun for every later shared-mechanism
change; that coverage cannot be silently inferred from older [D1 proof][D1] or
from a green invocation exit. D2's post-merge disposition is recorded in
[values][values].

## Bounded conclusion and host revisit

On the recorded Windows reference machine, shared standard models, SciFact and
fixture/recovery corpora, the branch retains paired quality within noise,
successful admitted agent latency below its ceiling, lower maximum summed
private bytes, and actual Engine crash/checkpoint recovery. Its idle workload
advances chunks and other enrichment while accepting the owner-recorded primary
tradeoff. Those observations coexist with deterministic fixture regressions,
lexical latency failures, GPU-pressure starvation/timeouts, positive heap slopes,
slow index recovery, incomplete child accounting and failed hang deadlines.

Under **E section 9**, every group must pass all applicable clauses or carry
the design's explicit installer/signing disposition. The retained evidence
therefore does **not authorize a default flip under that rule**. Shared baseline
failures are context, not a passing candidate envelope; E7's allowed disposition
does not cover other failures. This is a reviewable evidence decision, not an
independent verification verdict.

The [WP4 inputs](host-decision-inputs.md) identify an observed native ORT baseline
fault and VRAM-pressure weakness; they do not claim a measured host-performance
benefit. Apply [design section 5][host-rule]: retain the stage-1 owner and revisit
host placement when an encoder-fault/reconfigure/runtime/concurrent-stack trigger
is evidenced, or at the first release after Lane F. The observed native fault is
relevant to that review; its teardown occurrence and split provenance must remain
attached. Missing encoder readiness/latency observations cannot establish the
size of a host benefit.

No physical floor-machine, other-OS recovery, representative-change comparison
or collector bake-off is evidenced by these selected records. `floorMachine.claimed`
is false and G1 is pinned in [values][values]. D1's forced in-place device-memory
exercise is feature evidence, not floor performance ([D1][D1]). The collector
comparison condition was triggered by E2 response-time failure but no such
comparison is retained here; it remains unperformed. Signed inherited-store
recovery is deferred. Neither broader hardware/OS performance nor a rare-crash
rate is claimed; the stated soak duration and fault scenarios are the entire
acceptance population. D1/D2 feature acceptance and named gaps remain in their
own stage records ([D1 stage][D1stage], [D2 stage][D2stage]).

[design]: ../../design.md#16-what-must-be-measured-and-the-gate-for-flipping-the-default
[host-rule]: ../../design.md#5-the-inference-seam-transitional-stage-then-the-target
[runbook]: ../../stages/E.md
[values]: values.json
[table]: table.md
[ledger]: F:/agent-sandbox/js-lane-f/ledger.md
[driver]: ../../../../../scripts/jseval/lane-f/e-run.mjs
[process-identity]: ../../../../../scripts/dev/lib/process-identity.cjs
[stop-census]: ../../../../../scripts/dev/lib/stop-exit-census.cjs
[policy]: ../../../../../governance/supervision-contract.v1.json
[D1]: ../D1/installed-round-2026-10-01.md
[D1stage]: ../../stages/D1.md
[D2stage]: ../../stages/D2.md
[M1]: e1-quality/main/2026-10-01T16-27-42-378Z-0c6510b3.json
[B1]: e1-quality/branch/2026-10-03T12-19-55-978Z-767be14f.json
[M2i]: e2-e3-load/main/2026-10-01T16-42-58-581Z-b4fbca64.json
[M2s]: e2-e3-load/main/2026-10-01T17-05-11-968Z-6b4848ac.json
[B2i]: e2-e3-load/branch/2026-10-03T12-38-06-994Z-b80cb657.json
[B2s]: e2-e3-load/branch/2026-10-03T12-59-26-654Z-2f0996a4.json
[M4a]: e4-memory-soak/main/2026-10-02T06-46-11-669Z-02344ce3.json
[M4b]: e4-memory-soak/main/2026-10-02T07-42-03-648Z-7e783211.json
[M4c]: e4-memory-soak/main/2026-10-02T08-37-53-575Z-8c6f6165.json
[B4a]: e4-memory-soak/branch/2026-10-03T15-34-29-779Z-f4832677.json
[B4b]: e4-memory-soak/branch/2026-10-03T22-59-25-894Z-f018e8c4.json
[B4c]: e4-memory-soak/branch/2026-10-03T23-55-01-600Z-0208ce21.json
[B4failed2]: e4-memory-soak/branch/2026-10-03T14-20-07-596Z-fe224128.json
[B4failed3]: e4-memory-soak/branch/2026-10-03T14-20-34-937Z-72d2c4c2.json
[M5]: e5-crash/main/2026-10-02T06-41-36-093Z-0ef8ea83.json
[B5]: e5-crash/branch/2026-10-03T15-31-41-882Z-3f940bff.json
[B5old]: e5-crash/branch/2026-10-03T13-20-40-071Z-08d8f088.json
[M6]: e6-hang/main/2026-10-04T00-07-41-528Z-bbaeeecc.json
[B6]: e6-hang/branch/2026-10-04T00-12-03-784Z-d675432f.json
[B1fresh]: e1-quality/branch/2026-10-03T05-58-38-380Z-577dad40.json
[B2fresh]: e2-e3-load/branch/2026-10-03T06-19-27-207Z-322331c0.json
[B2freshs]: e2-e3-load/branch/2026-10-03T06-41-12-888Z-85de9392.json
[B1s5]: e1-quality/branch/2026-10-03T07-20-49-966Z-b629bf63.json
[B2s5]: e2-e3-load/branch/2026-10-03T07-39-49-801Z-57097f7b.json
[fixture]: ../../../../../tmp/lane-f-e/table/17a4b2c7-2d5d-4fdd-9e3d-1c19240d74bc/fixture-gate.json
[gpu-main]: ../../../../../tmp/lane-f-e/e1-quality/main/2026-10-01T16-27-42-378Z-0c6510b3/4-gpu.stdout
[gpu-branch]: ../../../../../tmp/lane-f-e/e1-quality/branch/2026-10-03T12-19-55-978Z-767be14f/4-gpu.stdout
[main-hs]: F:/justsearch-public/.claude/worktrees/lane-f-e-main/tmp/lane-f-e/2026-10-02T07-42-03-648Z-7e783211/soak/crashes/hs_err_pid37764.log
[main-crash]: F:/justsearch-public/.claude/worktrees/lane-f-e-main/tmp/lane-f-e/2026-10-02T07-42-03-648Z-7e783211/soak/crashes/crash-worker-37764-1790930267909.json
