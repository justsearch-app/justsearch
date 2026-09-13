---
title: "ADR-0048: Extraction isolation and indexing pacing"
type: decision
status: stable
description: "Untrusted parsing runs in a persistent child process pool rather than in the Worker JVM; foreground contention is answered with a duty cycle rather than a pause; Worker health is sampled internally rather than on the request thread; ingestion failures walk a bounded retry ladder to a visible terminal state; and NRT/commit cadence is decided by measurement, which rejected the first candidate."
date: 2026-09-02
probes:
  - adr-0048-extraction-child-pool-present
  - adr-0048-no-user-active-pause
  - adr-0048-no-eval-breath-hold-hatch
  - adr-0048-chaos-witness
  - adr-0048-foreground-gauge-is-worker-local
  - adr-0048-foreground-urgency-is-explicit
  - adr-0048-observer-front-exclusion
  - adr-0048-durable-foreground-held-once
  - adr-0048-foreground-gauge-has-a-live-producer
  - adr-0048-retry-exhausted-terminal
  - adr-0048-nrt-mode-defaults-continuous
last_reviewed: 2026-09-09
---

# ADR-0048: Extraction isolation and indexing pacing

## Status

Accepted (2026-09-02).

Implemented across tempdoc 885 PRs #595 (extraction pool), #598 (duty cycle), #600 (health sampler
and retry ladder) and #602 (cadence). Written after the fact but before the lane closes, because
the live measurements that decided several of these numbers only exist once.

## Context

The Worker does four things that compete: it parses untrusted files, it writes Lucene, it enriches
in the background on the GPU, and it answers foreground search. Before this lane, those four
interacted through mechanisms that were each locally reasonable and collectively wrong.

- **Parsing ran in the Worker JVM.** A parser that looped or exhausted the heap took the Worker
  with it. The existing `process` sandbox mode existed but required an operator to author a command
  line, so nothing used it, and a per-file process spawn was too expensive to default to.
- **Foreground contention was a pause.** `isUserActive()` reported "a query happened within the
  last 2000 ms" and the indexing loop stopped while it was true. Under a continuous agent-style
  query loop the window never expired, so indexing reached **zero** — measured, not inferred
  (tempdoc 885's chunk-1 baseline: 699 documents indexed in 22 minutes, then nothing). The pause
  was also unobservable: it logged at TRACE while the Worker pins that package to INFO, so no field
  run could count one.
- **Health was read on the request thread.** Every `/api/status` performed a Worker `IndexStatus`
  unary, so a slow Worker became a slow Head.
- **Ingestion failures had no terminal state.** Transient I/O failures counted against the same
  attempt cap as permanent parse failures, and a file that exhausted its attempts simply stopped
  being retried, invisibly.
- **NRT and commit cadence were never measured.** The reopen thread ran on hardcoded constants at
  index open and on configured values after the first backfill — two different cadences in one
  process, silently.

## Decision

**Crash isolation is a persistent extraction child pool.** Untrusted parsing runs in child
processes, not in the Worker JVM. The pool defaults to one child with one request in flight;
the child command is built in-process from `java.home` + `java.class.path` so no operator
authoring is required; requests and responses are length-prefixed frames on stdin/stdout. Routing
is per family (`auto`): `process` for PDF, Office, archives and any OCR route, `in_process` for
plain text, markdown, code and CSV/JSON. A timeout kills the child and marks the file
`FAILED/TIMEOUT`; a crash reports the exit code; a child OOM is a permanent parse failure. The
child polls the Worker PID so it cannot outlive its parent, and long classpaths are passed by
argfile because a dist classpath is short but a test classpath is not.

**Foreground contention is answered with a duty cycle, never a pause.** A Worker-local
`ForegroundLoad` gauge counts in-flight search-family RPCs. While it is non-zero the indexing loop
runs at a configured minimum share of wall time (`justsearch.indexing.foreground_duty_pct`,
default 20) instead of stopping. `isUserActive`, `signalUserActivity` and the eval hatch are
deleted, not deprecated. The gauge is a `worker-services` type rather than a gRPC concept, so it
survives a future Head/Worker merge.

**Worker health is sampled internally.** One scheduled `IndexStatus` unary on the existing health
monitor's tick feeds the taps; the status handler reads the last snapshot and its age and never
calls the Worker. `?fresh=true` forces a synchronous sample for debug tooling.

**Ingestion failures walk a bounded ladder to a visible terminal state.** Transient outcomes stop
counting against the attempt cap; the backoff ladder extends to a 7-day bound and then reports
`RETRY_EXHAUSTED`, which a rescan or a file change resets; the failure reason carries the
exception's own message rather than a literal.

**NRT and commit cadence are decided by measurement, and the first candidate was rejected.**
`index.nrt.mode` selects `continuous` (the default, today's behaviour) or `on_demand` (background
reopens slowed, foreground reads refreshing before acquiring). It ships **opt-in and off**, because
the measured arm rejected the implementation rather than endorsing the idea. The commit half of the
candidate was deleted outright: a `index.commit.idle_ms` knob cannot move commit count while
`CommitOps`' 10 s safety timer is a hardcoded constant and enrichment-backfill commits dominate the
population.

## Consequences

- A parser that hangs, crashes or OOMs costs one file and one child respawn, not the Worker. The
  isolation costs about 11 ms per file on the process families.
- Indexing under a continuous query loop went from **0%** to a floor of 20% duty, and the pacing is
  now countable (`worker.indexing.paced_intervals_total`, `duty_pct`) instead of invisible.
- `/api/status` no longer depends on Worker latency, and reports the age of the sample it served.
- A permanently failing file becomes visible instead of silently abandoned.
- Two config keys' worth of cadence surface exists but is unused by default. It is **not** free:
  it is carried on the explicit condition that a clean re-measure either earns it or deletes it.
- The measurement substrate (`index.runtime.reopen_count`, `segments_since_reopen`, jseval's
  `cadence` block and first-search probe) is retained regardless, because it is what made two
  implementation defects visible at all.

## Alternatives considered

- **Per-file process spawn** for extraction. Rejected on cost; the persistent pool keeps the
  isolation and amortises the spawn.
- **`WindowsJobObject` for grandchild lifetime (original decision, superseded 2026-09-09).**
  Originally rejected as an extra module dependency thought unnecessary beside the PID watchdog.
  Forced parser recycling disproved that assumption: Java-owned native handles vanish when the
  parser JVM dies. The watchdog only terminates that JVM.
- **Keeping the pause and shortening its window.** Rejected: any pause length is a full stop under
  a continuous loop, and the failure mode is starvation rather than slowness.
- **A streaming health RPC.** Rejected as work that a Head/Worker merge would throw away; the
  minimal sampler collapses to a direct call.
- **Shipping reopen-on-demand on the strength of its design.** Rejected by its own measurement:
  reopens rose 2.9x and indexing throughput fell 15%. Two implementation defects explain it, both
  fixed, and the arm has still never run as documented.

## Reassessment triggers

- **Lane F merges Head and Worker into one process.** The sampler collapses to a direct call, the
  gRPC interceptor feeding `ForegroundLoad` becomes redundant (the gauge itself survives), and the
  deferred `FetchDocuments` proto change becomes moot. The extraction pool becomes *more* valuable,
  not less, because a crash in one JVM is now a crash of everything.
- **A clean re-measure of `index.nrt.mode=on_demand`**, with a search-load arm. If it does not earn
  its keep, the three `index.nrt.*` keys are deleted.
- ~~**`CommitOps.COMMIT_TIMER_INTERVAL_MS` becoming configurable**~~ — **done**: it is
  `index.commit.timer_interval_ms` (default `10000`, the constant it replaced), resolved onto
  `ResolvedConfig.Index` and forwarded to the Worker through the ordinal-450 snapshot. That was only
  the FIRST of the two preconditions for commit-cadence work; the second is still open — the
  enrichment backfill's own commit sites (61 of 114 commits in the control arm) are governed by no
  key, so an arm that moves only this timer still cannot reach most of the population.
- **A mixed text+binary corpus existing on disk**, which would let the in-process families' sandbox
  round-trip be measured against the 10 ms/file bar that currently has no evidence either way.

## Evidence

Tempdoc 885: baseline and arm tables under "Baseline (chunk 1)" and "Consolidated live window
(2026-09-02)"; chaos tier at §SC-chaos; per-family extraction latency at §SB.4; item 6 numbers under
"Item 6 live"; the cadence rejection, its two defects and the commit-attribution histogram under
"Item 19 live — resolution". Live figures were taken at the branch's pre-merge HEAD; the
post-window merge added 166 files of this lane's own reviewed work.

## Amendment 2026-09-07: the gauge survived the merge as a type and stopped being fed as a mechanism

The first reassessment trigger above fired. Lane F stage A merged the Head and the Worker into one
JVM (items A6-A9, tempdoc 936), which is the event this ADR named. Re-examined per
`README.md § How to re-examine an ADR`; the outcome is **narrowed**.

**The premise, as written.** "The gauge is a `worker-services` type rather than a gRPC concept, so
it survives a future Head/Worker merge."

**What actually happened.** The type survived exactly as predicted — `ForegroundLoad` is untouched,
and the interceptor that fed it was thrown away at A9 as this ADR forecast. But the *duty cycle*
did not survive, because the replacement producer never matched. `ForegroundLoadInterceptor` sat on
the transport and saw gRPC method names (`Search`, `FetchDocuments`); its replacement,
`ForegroundLoadGate`, sits on the executor seam, where `SearchRpcOps` passes lower-camel operation
labels (`search`, `fetchDocuments`). The comparison never matched once. From item A6 until
2026-09-07 `ForegroundLoad` read zero on every call, so `IndexingPacing` never applied the 20%
foreground duty share and indexing never yielded to a waiting search. The behaviour this ADR's
second decision exists to produce was absent for the whole of stage A.

**Why the existing probe did not catch it, and what the narrowing is.** Both suites were green
throughout, because both fed the gate the vocabulary the gate itself declared —
`ForegroundLoadGateTest` calls it directly with its own operation names, and the item's own
acceptance test constructed a second gate and did the same. The probe was retargeted at A9 onto
`ForegroundLoadGateTest`, which pins the gauge's *balance* (every increment has a decrement across
ok, exception, cancellation and `Error`). That is a real property and it stays pinned. It is simply
not the property that broke: an always-balanced counter that is never incremented is perfectly
balanced.

So the claim is narrowed to what type-locality actually buys. **A `worker-services` type survives a
process merge; its producer does not — the producer is transport-shaped by construction, and a
re-homed producer is a new integration, not a move.** The corollary is a second probe, added here:
`adr-0048-foreground-gauge-has-a-live-producer` asserts that a real search driven through
`EngineRoot` moves `ForegroundLoad.startedTotal`. That assertion is the one that failed
(`expected: <1> but was: <0>`) and it is the only kind that can fail for this defect, because it is
the only one that crosses the seam. A gauge probe that does not enter through the seam's real
caller tests the gauge against itself.

**Generalisation, since this is a recurring shape rather than one typo.** When a decision predicts
that a concern "survives" a refactor because its *type* is in the right module, the probe must
assert the concern's *effect* at the far end of its real producer, not the type's internal
consistency. The decision here was right; the confidence that it was self-executing was not.

No numbers change. The duty cycle, its 20% default, the extraction pool, the health sampler, the
retry ladder and the cadence decision are untouched — the fix restored the producer, it did not
re-decide anything.


## Amendment 2026-09-09: explicit urgency and work lifetime replace operation-name selection

Re-examination after the foreground-balance premise probe failed found a retired test method,
not evidence that balance was optional. The Engine producer now takes an EngineWorkHandle;
ForegroundLoadGate.callOwned selects on EngineContext.urgency rather than an RPC/operation label.
Interactive foreground work balances on actual completion, including its retained child work.
Durable foreground work holds one increment for the work identity across calls and releases it
when the client detaches or the last owner completes. Survival and urgency are independent axes;
background work never starts a foreground increment. The duty share and indexing pacing consumer
are unchanged.

The current source and tests support the narrower balance premise: ForegroundLoadGateTest's
normalExceptionCancellationAndErrorAlwaysBalanceInteractiveWork preserves all four terminal
branches. The probe is retargeted to that method after this re-examination. Two separate probes
pin urgency across both survival axes and durable held ownership. EngineForegroundPacingTest's
real Engine producer witness remains required; a balanced but unfed gauge is still insufficient.
The older operation-name and gRPC descriptions above record the accepted historical design.

## 2026-09-09 amendment: Windows native-descendant containment

`WindowsParserContainment` now installs a mandatory kill-on-close Job Object inside each Windows
parser before serving requests. Native processes inherit the job at creation; the sole job handle
is non-inheritable and remains open until parser death. Windows then terminates the native tree
even on forced parser recycling. Engine death causes the existing watchdog to halt the parser,
triggering the same guarantee. Setup failure aborts parsing, and custom parser commands must call
the production bootstrap. Other platforms retain the watchdog only; the product supports Windows.

The FFM binding lives with the extraction owner in `worker-services`, so no `app-util` dependency
is introduced. A parent-side descendant snapshot cannot cover late spawns; per-document Java
process sets still provide orderly cleanup but cannot survive forced JVM death. See
[Windows Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)
for inherited membership and kill-on-close semantics.
