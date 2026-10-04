---
title: "Stage E manual paired decision"
created: 2026-10-04
status: "post-remedy update; owner and delegated dispositions recorded"
---

# Stage E manual paired decision

Date: 2026-10-04. Lane: `codex/lane-f-pr1`; first drafted at revision
`2dc9346f7c652d58de8c1c20fdd1f7cb19223656`, updated after the E section 10
remedies and reruns (lane merge `0edd4eb62`, records selected below).
MAIN is pinned to `ac1c93bf3`. This is a manual assessment of the retained
measurements, authorized by the owner's dated disposition below. The generated
[table](table.md) remains the automated result; this document does not rewrite its
failures or the acquisition identities.

The evidence supports quality within the declared noise allowance, lower peak
summed process commit, faster crash-to-index recovery than MAIN, cooperative and
forced hang recovery inside the policy bound, and an idle-agent window in which
every E2/E3 clause passes. It does **not** establish a passing joint envelope:
the deterministic fixture difference, the scripted-agent GPU-contention timeouts and
rate shortfalls, the shared heap-growth and soak wire failures, and the shared child
classification residuals remain, alongside measurement gaps. Owner acceptance
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
| E2/E3 agent idle | [M2i][M2i] | [B2i][B2i] | `0edd4eb62` / `2612e8feb8034312` |
| E2/E3 scripted agent | [M2s][M2s] | [B2s][B2s] | `999eec818` / `2612e8feb8034312` |
| E4 first window | [M4a][M4a] | [B4a][B4a] | `4a856d76e` / `ea782678a63bf4de` |
| E4 second window | [M4b][M4b] | [B4b][B4b] | `7e269aed5` / `ea782678a63bf4de` |
| E4 tail | [M4c][M4c] | [B4c][B4c] | `f691dd532` / `ea782678a63bf4de` |
| E5 | [M5][M5] (recaptured) | [B5][B5] | `fcdd435ff` / `be1441ebcbc5fb17` |
| E6 | [M6][M6] (recaptured) | [B6][B6] | `0723f911c` / `be1441ebcbc5fb17` |

MAIN's runtime Head stamp is `eb365b7032da7fa6`; its separate Worker stamp is
`55ffe7744cc7e103`, visible in the first MAIN E5 record's killed-process command
([M5pre][M5pre]). Product stamps, record revisions and the drafting revision are
different identities. The post-remedy E2/E3 records share one product build
(stamp `2612e8feb8034312`, product commit `a562887ff`; later revisions add only
evidence commits and the hashed E6 instrument fix `2f63b88f7`). E5 and E6 were
rerun on the final PR product (stamp `be1441ebcbc5fb17`, lane `17efe0305`), which
adds the full-suite fixes `de783affb` and the installed-tier fixes `33c86c131`
(dev-runner exit attribution) and `226174c09` (live start waits for model
initialization). E1 and E4 were not rerun after the remedies; their rows keep the
earlier build, so the remedies are not claimed for them.

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

The E1–E4 pair identities differ, as did the first E5 pair. Comparing retained `pairIdentityInputs` shows
changed hashes for `scripts/dev/lib/process-identity.cjs` and
`scripts/dev/lib/stop-exit-census.cjs`, changed in the evening campaign after
MAIN's captures (P1 `8978ad3c8`, Q14 `e6e7d142d`; [ledger][ledger]). The first E5 pair additionally
lost `models/onnx/gte-multilingual-base/model.onnx.optimized` and its `.opt-meta`
receipt during tempdoc 958's legacy cache cleanup. Comparing the E5 inventories
by path shows those removals, with no added or changed remaining inventory
entries ([M5pre][M5pre], [B5pre][B5pre]). The same cache/receipt removals also occur in each
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
[M5pre][M5pre], [B5pre][B5pre], helper sources above).

The remedies changed one hashed instrument, `scripts/jseval/lane-f/e456-live.mjs`
(evidence scoped to the current launch and phase in `a562887ff`; validated event
timestamps and rotation-safe cursors in `2f63b88f7`). Accepting that change meant
recapturing MAIN for the groups that use it. MAIN E5 and E6 were recaptured on
2026-10-04 and again after the final BRANCH reruns, so each pair is captured
with the same instrument bytes and model inventory ([M5][M5], [M6][M6]; earlier
recaptures [M5rec][M5rec], [M6rec][M6rec]). E4 also reads this instrument and was not rerun,
so its rows keep the pre-change pair.

The recaptured E5 and E6 pairs have identical pair identities, with no differing
`pairIdentityInputs` ([M5][M5], [B5][B5], [M6][M6], [B6][B6]): the helpers and
the instrument are the same bytes, and both arms saw the same model inventory.
That inventory differs from MAIN's original 2026-10-01 one: tempdoc 958's cleanup
removed the beside-model optimized caches, and the branch's later runs regenerated
them. The E2/E3 pairs differ from MAIN's 2026-10-01 captures in that way, by
removed caches, changed modification times and a different reranker cache size, so
the E2/E3 rows rely on the owner's manual-verdict disposition for drift.
Identity agreement does not cure MAIN's missing fault observations.

There is a separate aggregate-driver issue: `latestRecords()` marks BRANCH's
merged E2/E3 record `Workload provenance mismatch`, because the two source
revisions differ: `999eec818` and `0edd4eb62` differ by evidence commits and by
the E6 instrument change `2f63b88f7` (`e456-live.mjs` and its tests), which E2/E3
do not use. Their `valuesHash`, query pool and runtime Head stamp agree
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

Diagnosis after the run (REME1, recorded in [remedies](remedies-2026-10-04.md)):
c03's shorter event sequence follows the first-wins cancellation of
[design section 3.4][design34]: `cfa4a78b8` suppresses reasoning after cancellation
and aborts before a tool proposal, so BRANCH cancels after 34 ms while MAIN emits
its proposal and cancels after about 3 seconds. All six captures keep the
USER/CANCELLED outcome. In c01 the retrieved evidence matches, but BRANCH's
generated opening sentence differs and the citation scorer maps it to the Testing
Strategy passage (score `0.84512788`). That difference is unattributed: proving its
cause needs a controlled replay of the citation scorer on a fixed answer. Neither diagnosis changes the verdict. E section 1 forbids adding
difference classes after the fact, so the group stays **fail**, with c03 recorded
as a design-intended change and c01 as an open item.

## E2 — foreground and agent response under indexing

Group: **fail**, on one clause: the scripted-agent window's search timeouts. Every
other E2 clause passes after the remedies. Latencies are admitted successful-request
p95s; the wire clause separately retains unsuccessful requests. Values below are
milliseconds. The per-workload ceilings in [values][values], rather than the maximum
across workloads, control the foreground comparison.

| Clause | MAIN value | BRANCH value | Rule | Manual verdict | Evidence |
|---|---|---|---|---|---|
| indexing-window-valid | Both windows `true`, 1,200 s each | Both windows `true`, 1,200 s each | Active bulk/enrichment, valid boundary counters and same query pool on both workloads (E section 2). | pass | [M2i][M2i], [M2s][M2s], [B2i][B2i], [B2s][B2s] |
| foreground-p95, idle hybrid / lexical | `259.213400` / `17.027600` | `261.545000` / `14.659300` | Ceilings `285.134740` / `18.730360` = corresponding MAIN × `1.10`. | pass | [M2i][M2i], [B2i][B2i], [values][values] |
| foreground-p95, scripted hybrid / lexical | `1551.859500` / `18.479300` | `701.118300` / `15.388800` | Ceilings `1707.045450` / `20.327230`. | pass | [M2s][M2s], [B2s][B2s], [values][values] |
| agent-api-p95 | `5568.798600`; 471/472 admitted/offered | `3206.249700`; 405/406 admitted/offered | Successful admitted API p95 ≤ `6125.678460`; wire failures assessed separately. | pass | [M2s][M2s], [B2s][B2s], [values][values] |
| idle-rejections | 0 wire rejections / 47,553 offered | 0 / 52,872 | Zero rejection ceiling. | pass | [M2i][M2i], [B2i][B2i], [values][values] |
| scripted-rejections | 0 / 42,090 wire requests | 0 / 47,644 | At most `0.01` admission rejection fraction, with legal reason/retry receipts. Failed terminals are not admission rejections. | pass | [M2s][M2s], [B2s][B2s], [values][values] |
| no-timeout-or-5xx | Idle: 47,551 HTTP 200, 2 boundary censored; scripted: 42,087 HTTP 200, 3 boundary censored | Idle: 52,870 HTTP 200, 2 boundary censored; scripted: 47,622 HTTP 200, 18 HTTP 504, 1 missing HTTP status, 3 boundary censored | Candidate must have no non-boundary timeout, 5xx or invalid terminal. MAIN counts are baseline facts (E section 2 amendment). | pass (idle); **fail (scripted)** | [M2i][M2i], [M2s][M2s], [B2i][B2i], [B2s][B2s] |

The lexical failures of the first selection ([B2ipre][B2ipre] `23.516200`,
[B2spre][B2spre] `26.486300`) coincided with an identified hotspot: the search path ran
a synchronous facet probe on every query while the facet cache was empty (REME2:
5-8 ms per query; lexical p95 fell to MAIN's level once the cache filled). REME2 did
not attribute the whole delta to it. Fix `268debefd` caches an empty facet result
for the normal refresh interval and shares one probe among concurrent misses; the
rerun's lexical p95 then passed in both windows.

The scripted window was rerun overnight with no Gradle build, other stack or
owner workload running. Its 504s are hybrid searches that overlapped the agent's LLM generation
and expired at the 15-second search budget: native embedding and SPLADE encodes
took up to about 194 seconds while free VRAM fell to 172 MiB, with no logged
arena out-of-memory or execution-provider fallback (REME2B). MAIN, with the same
co-resident GPU policy, kept embedding below about 12 seconds; why its separate
process avoids the tail is not established. This reproduces the weakness the owner
kept on 2026-10-03, now without outside load. It is recorded as a **failure**, not
waived; the follow-up is a VRAM-budget-aware co-residence policy with a semantic
time budget that degrades a hybrid query to lexical with a reason code (design
sections 4 and 8). No collector comparison is evidenced after this response-time
failure; the conditional remedy remains unperformed.

## E3 — progress under the same foreground load

Group: **fail**, on the scripted-agent window's primary and SPLADE rates. The
agent-idle window passes every clause, so the owner's recorded idle-primary
deviation is no longer needed. Rates are completed units/s over the recorded
stage-active interval, not full-run average throughput.

| Clause | MAIN value | BRANCH value | Rule | Manual verdict | Evidence |
|---|---|---|---|---|---|
| indexing-window-valid | Both `true` | Both `true` | Same E2 windows and query pool; active intervals must be measurable. | pass | [M2i][M2i], [M2s][M2s], [B2i][B2i], [B2s][B2s] |
| stage rates, idle primary | `18.3109182662` | `17.5538141611` | ≥ `16.4798264396` (MAIN × `0.90`). | pass | [M2i][M2i], [B2i][B2i], [values][values] |
| stage rates, idle embed / SPLADE / NER | `0.7349966214` / `3.4458174937` / `0.0008333295` | `2.7141556183` / `4.5495197728` / `0.8949963568` | Each positive MAIN rate × `0.90`, independently. | pass | [M2i][M2i], [B2i][B2i], [values][values] |
| stage rates, scripted primary / embed / SPLADE | `12.2587497580` / `0.1124979330` / `1.2816431177` | `7.3374099975` / `0.2527252879` / `0.8172005197` | Minima `11.0328747822` / `0.1012481397` / `1.1534788060`. | **fail** (primary, SPLADE); embed passes | [M2s][M2s], [B2s][B2s], [values][values] |
| Other stage comparisons | Idle chunk rate `0`; scripted chunk and NER `0` | Idle chunk `1.0383291067`; scripted chunk `0.1022539816`, NER `0` | Zero MAIN rate supplies no relative comparison. A branch-only gain cannot establish relative pass. | not compared (E section 3: zero or missing MAIN stages are not compared) | [M2i][M2i], [M2s][M2s], [B2i][B2i], [B2s][B2s] |
| chunk-progress-under-foreground-load, idle | 0 completions despite pending chunks | 1,246 completions, positive rate | Positive candidate chunk progress whenever chunks are pending; MAIN starvation is baseline, not candidate acceptance. | pass | [M2i][M2i], [B2i][B2i] |
| chunk-progress-under-foreground-load, scripted | 0 completions despite pending chunks | 123 completions, positive rate | Same absolute anti-starvation condition. | pass | [M2s][M2s], [B2s][B2s] |

The facet-probe fix also restored the idle primary rate: the first selection's
`15.1631987835` ([B2ipre][B2ipre], accepted by the owner as a recorded deviation on
2026-10-03) became `17.5538141611`, 96% of MAIN. In the scripted window,
synchronous SPLADE stalled primary indexing for about 192 and 128 seconds at 192
and 1,024 documents while the GPU was contended (REME2B); this is the same
co-residence weakness as the E2 timeouts and shares its follow-up. No waiver of
the scripted rate clauses is recorded.

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

Group: **shared-baseline-fail** for child classification only; literal paired
checkpoint/supervisor clauses remain **unmeasurable** on MAIN. After the remedy,
BRANCH restores the index inside the bound while MAIN does not. Candidate API
recovery and recorded operation resume are observed.

| Clause | MAIN value | BRANCH value | Rule | Manual verdict | Evidence |
|---|---|---|---|---|---|
| actual-death-durable-operation | Identity-verified Worker death with PROCESSING job; no operation revision ledger | Identity-verified Engine death with RUNNING ingest and PROCESSING revision-bearing unit | Actual owned death with durable work in flight (design section 16). MAIN native job evidence is available; literal Lane F operation identity is unavailable. | pass for death/in-flight work; unmeasurable for paired operation identity | [M5][M5], [B5][B5] |
| crash-to-api | `917 ms`; Head survived, first post-death successful probe | `4634 ms`; successor API | ≤ `13600 ms`, first cooldown + frozen warm-start budget. | pass | [M5][M5], [B5][B5], [values][values] |
| crash-to-index | `23723 ms` | `5790 ms` | Same `13600 ms` bound, E section 2 amendment. | pass (MAIN baseline fails) | [M5][M5], [B5][B5], [values][values] |
| checkpoint-resume | Native job replay, no Lane F checkpoint ledger | RUNNING → COMPLETE from cursor `ingest-progress:1:19`, units completed `16`, no duplicate effects; clause `true` | Resume durable operation from checkpoint, not from start. MAIN literal comparator missing. | unmeasurable (paired); candidate observed pass | [M5][M5], [B5][B5] |
| visible-restarting | Head logs narrate Worker restart; no `supervisor.v1.json` | Clause `true` | Restart visible in supervisor state, with split-native disposition retained. | unmeasurable (paired); candidate observed pass | [M5][M5], [B5][B5] |
| no-orphaned-child | `conhost.exe` PID 27304 (`other-child`) classified orphan | `conhost.exe` PID 2892 (`other-child`) classified orphan | No orphan after restart; healthy llama-server adoption is allowed. | shared-baseline-fail (classification) | [M5][M5], [B5][B5] |
| restart-quit-upgrade-child-policy | Crash/restart `healthyLlamaAdopted=true`; restart survivor llama-server 1408; quit/upgrade survivors empty; crash `extractionStopped=false` | Same booleans; restart survivor llama-server 3628; quit/upgrade survivors empty; crash `extractionStopped=false` | Adopt healthy llama-server on crash/restart; quit/upgrade must leave none. | shared-baseline-fail for full recorded clause; adoption and quit/upgrade subclauses pass | [M5][M5], [B5][B5] |

On the measured remedy build the values were `3634 ms` / `4076 ms` ([B5rem][B5rem]).
One final-product E5 run ([B5abort][B5abort]) recovered the same way (`4429 ms` /
`6499 ms`) but aborted with a request timeout in its upgrade child-policy step; the
repeat on the same build ([B5][B5]) completed every step, and earlier E5 runs never
showed it. It is recorded as a non-reproduced instrument abort, not a verdict.

The first selection's BRANCH crash-to-index was `24239 ms` ([B5pre][B5pre]): text
search waited on the model-ready latch for the encoders to load. Fix `a562887ff`
lets requests that need no model (lexical, no rerank or semantic stage) answer
immediately while model-dependent requests keep the latch; [design section 17.7][design177]
sets the floor through a fault as “API and text search within cooldown plus warm start”. MAIN keeps the same latch, so its recaptured
`23723 ms` (earlier captures `16872 ms` [M5pre][M5pre] and `19096 ms`
[M5rec][M5rec]) still exceeds the bound.

Restart-path llama survival is adoption by design, not a native crash. The
aggregate child-policy boolean is false on both arms despite passing adoption
and quit/upgrade subclauses; `extractionStopped=false` on the crash experiment
and the conhost classification prevent a full acceptance claim. These residuals
are shared with MAIN.

## E6 — graceful and forced hang

Group: **pass on the candidate by manual verdict**, with MAIN unmeasurable. The
detection and settings clauses pass in the record. The two deadline clauses have
no automated verdict and are judged manually from timestamped evidence below.
The recovery bound is the policy graceful-stop deadline `15000 ms` plus first
cooldown `1000 ms` plus warm-start budget `12600 ms` = `28600 ms`
([policy][policy], [values][values]). The detection bound is interval × misses +
probe timeout + 1,500 ms = `32500 ms`.

| Clause | MAIN value | BRANCH value | Rule | Manual verdict | Evidence |
|---|---|---|---|---|---|
| runnable-watcher-api-pool-wedge | Request-channel deadline; no validated observation | Injected, preHealthy/postUnresponsive true, request observed; detection `15436 ms`; clause `true` | Confirm request-thread wedge with runnable watcher, then cooperative recovery. | unmeasurable (paired); candidate pass | [M6][M6], [B6][B6] |
| whole-JVM-wedge | Not reached; no observation | Injected, preHealthy/postUnresponsive true, request observed; detection `29566 ms`; clause `true` | Whole-JVM wedge ignores channel and is killed. | unmeasurable (paired); candidate pass | [M6][M6], [B6][B6] |
| graceful-deadline | Unavailable | Request at `07:00:11.266Z`; ordered shutdown `clean=true` at `07:00:20.743Z`; death `07:00:21.712Z`, before the `07:00:26.266Z` deadline; Engine exited `0`; index restored `17012 ms` after the request | Exit by itself before the request deadline; recover within `28600 ms`. | pass (manual) | [B6][B6], [soft engine log][B6soft], [soft actuator log][B6softhead], [policy][policy] |
| forced-deadline | Unavailable | Request at `07:01:46.712Z`; `FORCED KILL` after `15000 ms`, exit `1`; death `15592 ms` after the request; index restored `21330 ms` after the request | Kill after the request deadline; recover within `28600 ms`. | pass (manual) | [B6][B6], [hard actuator log][B6hardhead], [policy][policy] |
| E4-derived-hang-settings | No validated clause measurement in failed MAIN invocation | Clause `true` | Frozen interval `10000 ms`, miss count `3`, worst E4 pause `520.782 ms`; interval × count ≥ three times pause and interval ≥ `10000 ms`. | unmeasurable (paired); candidate setting proof passes | [M6][M6], [B6][B6], [values][values] |

Why the deadline verdicts are manual: the instrument now accepts only evidence
lines that carry validated timestamps (`2f63b88f7`, after review RREM found that a
rotated log replayed the soft phase's narration into the hard phase). The
dev-runner's “exited 0” and “FORCED KILL” lines carry no timestamp, so the record
records `graceful` and `forced` as `false`. The earlier remedy-build run
([B6rem][B6rem]) also carried `injectionErrors: ["JDWP connection closed"]`,
which `hangVerdict` refuses; in [jdwp-fault.mjs][jdwp-fault] that error is raised
when the socket closes and rejects suspend commands still pending for threads
started after the wedge, which happens when the Engine exits at the end of its
ordered shutdown. The final-product run has no injection errors. Its soft and hard
recovery times (`17012 ms`, `21330 ms`) match the remedy build's (`17108 ms`,
`19396 ms`). The times above come from the record's
validated fields (request, last-alive, death, restored) and the Engine's own
timestamped log; the untimestamped actuator line is used only to classify the
exit, and the exit code agrees with the log's clean ordered shutdown.

The first selection failed both deadlines ([B6pre][B6pre]: `36723–38591 ms` and
`62175–65691 ms`, soft path forced). Two defects explained it (REME6): Jetty's stop
waited on the wedged API-pool request threads, so ordered shutdown never reached
Head and index close; and the old instrument read earlier launches' logs into the
hard phase. Fix `a562887ff` bounds the HTTP transport stop to half the supervision
grace, so the soft hang now completes ordered shutdown about 9.5-12.3 s after the request; the follow-up
`de783affb` runs that bounded stop on its own thread so a closed executor cannot
refuse it.

Review RREM also found a limit that remains: a request that was **admitted** and
then wedged still holds work ownership, and the drain guards keep refusing Head and
index close for it, so that case ends in the forced kill (inside the same bound).
The guards are kept; graceful recovery from an admitted-request wedge is a
recorded follow-up.

MAIN's recaptured E6 again never logged “worker unresponsive” inside the window;
its retained failure is `Hang request channel: deadline` ([M6][M6], earlier
captures [M6pre][M6pre] and [M6rec][M6rec]). One MAIN attempt between them failed
earlier, at AI activation, persisting its UI settings, and measured nothing
([M6act][M6act]). No MAIN recovery latency can be inferred. The driver labels
soft clauses `unmeasurable-on-split`; that disposition is a gap, not proof MAIN
hung or recovered.

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
- **2026-10-04, about 02:45:** the owner delegated further decisions (“continue
  working fully autonomously … make owner-level decisions”). The decisions below
  were taken under that delegation and are recorded with their reasons in the
  [ledger][ledger] and [remedies](remedies-2026-10-04.md):
  - Run the E section 10 remedies for E1 (diagnose), E2 lexical, E5 index restore
    and E6 recovery; keep the scripted GPU-pressure failure as a failure.
  - Change the hashed E6 instrument and accept recapturing MAIN E5 and E6.
  - Keep the drain and dependency guards; record graceful recovery from an
    admitted-request wedge as a follow-up instead of bypassing the guards (RREM I1).
  - Record c03 as a design-intended change and c01 as unattributed; E1 stays
    failed under E section 1.
  - Record the quiet-machine scripted-agent result (REME2B) as a real failure
    with a high-priority follow-up, without adding a degradation path to this PR.

## Remedies and reruns — E section 10

These are campaign observations from the [orchestrator ledger][ledger], with
retained records where available. Local test results cited here are ledger
receipts, not checks executed during this drafting assignment.

| Failure / root cause | Remedy and review | Rerun / disposition |
|---|---|---|
| Stale installed distribution: branch stamp `0df23ef29b8098e2`, pre-campaign jars; `build -x test` did not refresh installDist. | Start gate changed to assemble plus installDist, refusing jars older than last product commit; fresh stamp `bae23e144d2317a6`. | Stale E1 `2026-10-03T04-41-09-481Z-5c492474`, E2 idle `2026-10-03T05-08-22-668Z-7e0ff456`, scripted `2026-10-03T05-31-05-684Z-1edb1211`, E5 `2026-10-03T05-53-49-362Z-1c88aa59` superseded; fresh q-branch2 E1 [577dad40][B1fresh], idle [322331c0][B2fresh], scripted [85de9392][B2freshs], then final records selected above. |
| Fresh-dist primary rate remained low; S5 PreparedExtractionInput temp-directory/copy/delete cost suspected. | Bisect: base `409b1926b` 124 docs/s, pre-S5 `b09a94364` 117, S5 merge `b74f2aa83` 59, integration `1f463c197` 60. Snapshot fix `e623ebdf9`, merged `c623a4778`, probe 116 docs/s. Concurrent builder load contaminated earlier q-s5check; quiet final campaign required. | q-s5check E1 [b629bf63][B1s5] and idle [57097f7b][B2s5] retained as intermediate evidence, not final selection. The first final idle record [B2ipre][B2ipre] reproduced the primary shortfall, so it was not dismissed as noise; the post-remedy idle record [B2i][B2i] passes. |
| Snapshot fix review found spilled PDF/image reload and Office MIME-admission bypasses; repeated corrections required redesign. | `60eae54a4` follow-up; RS5Pb rejection → v2 lazy materialization, file-backed consumers and metadata-driven text fast path. `1d2e048ec` → RS5V2 MIME-policy rejection; `43b872b58` supplements declared Office type without replacing detected MIME, memoizes detection; RS5V2b rejection → `97784d6de`; RS5V2c approve-with-fixes, test precision `b4419f16b`; accepted merge `234dcc781`. | Final q-branch3 E1 [B1][B1], E2/E3 [B2i][B2i] / [B2s][B2s]. Owner primary deviation and retained GPU-pressure failure, rather than further noisy micro-probes. |
| q-branch3 E4 windows two/three failed at AI activation ([fe224128][B4failed2], [72d2c4c2][B4failed3]): first-window stop left llama-server 22004 holding GPU memory. Failed native drain skipped inference closure; dev-runner fallback PID binding read failure as death and discarded outcomes. | STOPF `078b1148f`; RSTOPF rejection for lock contention/discovery gaps → `db6c06816`; RSTOPFb regression corrected by root; accepted under stopping rule, merged build at `4986e4667`. Discovery-phase lifecycle gaps parked in process-ownership follow-up. | All E4 slots and E5 rerun, not only failed slots: [B4a][B4a], [B4b][B4b], [B4c][B4c], [B5pre][B5pre]. First rerun stop left no Java/llama, per ledger; quits now have no E5 survivors. E1–E3 retained because change was quit-path-only. |
| Evidence commits moved BRANCH revision between E4 slots, incorrectly blocking same-build owner-duration and downstream E6. | Scoring fix `1e13b66c0`: same revision or shared observed Head build stamp; recorded local tests `12 + 49` green. | q-branch6 froze hang values and acquired [M6pre][M6pre] / [B6pre][B6pre]. Current `latestRecords()` owner-duration is true on both E4 arms, even though stale gap text remains in records/table. |
| E5 evidence commit blocked by gitleaks generic-api-key on operation UUID fields. | Rule-targeted, path-anchored operation-key allowlist `.gitleaks.toml`, commit `8297a6c6f`, gitleaks clean in ledger. | Evidence retained; no numeric gate relaxed. Initial [B5old][B5old] superseded by post-stop-fix [B5pre][B5pre]. |
| E2 lexical p95 over its ceiling in both windows; idle primary rate at 83% of MAIN (REME2). | `268debefd`: empty facet results cached for the refresh interval; concurrent misses share one probe (`WorkerStatusCacheFacetTest`). | Idle rerun [B2i][B2i]: every clause passes. |
| E5 crash-to-index over the bound; E6 soft hang forced (REME6). | `a562887ff`: bounded HTTP transport stop; lexical requests bypass the model-ready latch; E6 instrument scoped to the current launch and phase. Review RREM rejected I1 (admitted-request wedge, kept as follow-up) and I2 (rotation replay, fixed in `2f63b88f7`). The full suite then found four regressions (closed executor, failed-bind drain, two tests that assumed lexical search waits); fixed in `de783affb`. | [B5][B5] and [B6][B6] pass their bounds; MAIN E5 and E6 recaptured ([M5][M5], [M6][M6]). |
| An agent-idle rerun stopped with “Model file size/mtime changed during acquisition”. | Root cause: this pre-958 build recreated the beside-model ORT cache files that tempdoc 958's verification had removed. No product change; the files are stable once recreated. | Run `2026-10-04T01-36-51-955Z-c003bc7f` is invalid; repeated as [B2i][B2i]. |
| Scripted-agent timeouts and rate shortfalls reproduced on a quiet machine (REME2B). | None in this PR. Recorded as a failure with a follow-up for a VRAM-budget-aware co-residence policy and a semantic time budget with lexical fallback. | [B2s][B2s] retained as the failing record. |

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
private bytes, actual Engine crash/checkpoint recovery with the index back in
about 6 s (MAIN about 24 s), and cooperative and forced hang recovery inside the
policy bound. Its agent-idle window passes every E2 and E3 clause. Those
observations coexist with the deterministic fixture difference (one
design-intended, one unattributed), search timeouts and indexing shortfalls when
the agent's LLM and the encoders contend for the GPU, positive heap slopes and
soak wire failures shared with MAIN, and incomplete child accounting.

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
[design34]: ../../design.md#34-engine-context-identity-and-provenance
[design177]: ../../design.md#177-owner-set-parameters
[runbook]: ../../stages/E.md
[values]: values.json
[table]: table.md
[ledger]: F:/agent-sandbox/js-lane-f/ledger.md
[driver]: ../../../../../scripts/jseval/lane-f/e-run.mjs
[process-identity]: ../../../../../scripts/dev/lib/process-identity.cjs
[stop-census]: ../../../../../scripts/dev/lib/stop-exit-census.cjs
[jdwp-fault]: ../../../../../scripts/supervisor-conformance/jdwp-fault.mjs
[policy]: ../../../../../governance/supervision-contract.v1.json
[D1]: ../D1/installed-round-2026-10-01.md
[D1stage]: ../../stages/D1.md
[D2stage]: ../../stages/D2.md
[M1]: e1-quality/main/2026-10-01T16-27-42-378Z-0c6510b3.json
[B1]: e1-quality/branch/2026-10-03T12-19-55-978Z-767be14f.json
[M2i]: e2-e3-load/main/2026-10-01T16-42-58-581Z-b4fbca64.json
[M2s]: e2-e3-load/main/2026-10-01T17-05-11-968Z-6b4848ac.json
[B2i]: e2-e3-load/branch/2026-10-04T02-26-10-743Z-15bab137.json
[B2ipre]: e2-e3-load/branch/2026-10-03T12-38-06-994Z-b80cb657.json
[B2s]: e2-e3-load/branch/2026-10-04T01-58-19-999Z-f9e67f1f.json
[B2spre]: e2-e3-load/branch/2026-10-03T12-59-26-654Z-2f0996a4.json
[M4a]: e4-memory-soak/main/2026-10-02T06-46-11-669Z-02344ce3.json
[M4b]: e4-memory-soak/main/2026-10-02T07-42-03-648Z-7e783211.json
[M4c]: e4-memory-soak/main/2026-10-02T08-37-53-575Z-8c6f6165.json
[B4a]: e4-memory-soak/branch/2026-10-03T15-34-29-779Z-f4832677.json
[B4b]: e4-memory-soak/branch/2026-10-03T22-59-25-894Z-f018e8c4.json
[B4c]: e4-memory-soak/branch/2026-10-03T23-55-01-600Z-0208ce21.json
[B4failed2]: e4-memory-soak/branch/2026-10-03T14-20-07-596Z-fe224128.json
[B4failed3]: e4-memory-soak/branch/2026-10-03T14-20-34-937Z-72d2c4c2.json
[M5]: e5-crash/main/2026-10-04T09-06-37-080Z-e93e7de1.json
[M5rec]: e5-crash/main/2026-10-04T02-19-51-086Z-b9aad33b.json
[M5pre]: e5-crash/main/2026-10-02T06-41-36-093Z-0ef8ea83.json
[B5]: e5-crash/branch/2026-10-04T07-02-58-327Z-b531304f.json
[B5abort]: e5-crash/branch/2026-10-04T06-55-41-323Z-63c4fe1b.json
[B5rem]: e5-crash/branch/2026-10-04T02-23-11-671Z-01d399df.json
[B5pre]: e5-crash/branch/2026-10-03T15-31-41-882Z-3f940bff.json
[B5old]: e5-crash/branch/2026-10-03T13-20-40-071Z-08d8f088.json
[M6]: e6-hang/main/2026-10-04T09-11-51-430Z-959a8506.json
[M6rec]: e6-hang/main/2026-10-04T02-47-42-664Z-e76291a5.json
[M6act]: e6-hang/main/2026-10-04T09-10-22-968Z-060d420f.json
[M6pre]: e6-hang/main/2026-10-04T00-07-41-528Z-bbaeeecc.json
[B6]: e6-hang/branch/2026-10-04T06-59-13-643Z-5c160db0.json
[B6rem]: e6-hang/branch/2026-10-04T02-52-05-298Z-67bf95d8.json
[B6pre]: e6-hang/branch/2026-10-04T00-12-03-784Z-d675432f.json
[B6soft]: ../../../../../tmp/lane-f-e/2026-10-04T06-59-13-643Z-5c160db0/hang-soft/logs/engine.log
[B6softhead]: ../../../../../tmp/lane-f-e/e6-hang/branch/2026-10-04T06-59-13-643Z-5c160db0/hang-soft/head-events.log
[B6hardhead]: ../../../../../tmp/lane-f-e/e6-hang/branch/2026-10-04T06-59-13-643Z-5c160db0/hang-hard/head-events.log
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
