# C1-7 bounded scan progress proof

Date: 2026-09-09. Windows, Temurin 25.0.2, lane-F-A. Tested working changes over
`bf7c624c0`; the item commit carries the implementation and this record. C1 remains open.

One replay ring per scan replaces unbounded history, per-subscriber snapshots and queues.
The process policy supplies 64 mapped scans, 64 events per ring and 48 open subscriptions at
current defaults. Old completed mapped entries make room first. A live subscription can keep
an evicted completed ring until its terminal event is staged; that reference is then severed.
Thus mapped plus subscription-owned rings are bounded by 112, with at most one staged event
per open subscription. Capacity releases at terminal delivery, explicit close, interruption or
idle expiry, never just terminal staging. Delivered DTOs belong to their consumers.

A late subscriber sees the retained suffix. An overtaken subscriber receives
UNKNOWN_SCAN_OR_RETENTION_EXPIRED without cancelling the underlying scan. The canonical API
contract replaces its former unbounded full-history promise. The HTTP handler always closes
its subscription and acquires capacity before starting SSE. Registration failure reaches the
Engine port as the exact executor refusal and is not rewritten as generic delivery failure.

## Executed checks

- Run66: original 12 scan tests plus six always-on module guardrails passed; Engine/UI compile passed.
- Run69: those tests, seven EngineWorkCancellation tests and affected PMD passed, including actual
  bounded delivery refusal propagation and admission/pacing release.
- Run70: real registry subscription saturation produced HTTP429/Retry-After before SSE setup;
  one controller test plus two UI guardrails and UI PMD passed.
- Run73: final 15 scan tests plus six guardrails, seven Engine cancellation tests, one controller
  test plus two UI guardrails passed (31 total). App-services PMD main/test passed. Includes suffix
  replay/lag expiry, all-active buffer refusal, completed-entry reuse, subscriber saturation,
  terminal staging/delivery, retained released objects, interrupt/close wakeup and idle expiry.
- Run76 restored the final production source after mutations; all 21 app-services tests passed
  from the valid run73 cache. No source differs from the run73 scan candidate.
- Documentation index, skill embeddings and canonical links passed; both index/skills generators
  ran after the API contract edit. Corresponding Claude/Codex search-quality skills only link the
  unrelated search-contract section and contain no scan replay behavior to update.

## Refute-first checks

Independent reviewer c1_scan_review found two real ownership defects in the initial dirty diff:
released cursors retained rings, then terminal staging freed capacity before delivery. Root fixed
both and added regressions. The review also identified missing idle proof; a package-private timing
seam keeps the production sixty-second window and lets the actual wait/expiry path execute in tests.
Root retained ownership after three correction follow-ups; no issue was waived.

Compiled mutations failed for the intended assertions:

- 67: remove ring eviction => lagging subscriber incorrectly receives normal progress.
- 68: omit capacity release => replacement subscription refuses.
- 74: omit ring severance on release => retained closed object still references its old ring.
- 75: release capacity at terminal staging => saturated subscribe is unexpectedly accepted.

Scripts restore source in finally. Logs and XML: `tmp/c1-batch4-scan-progress-*66*`, `*69*`,
`*70*`, `*73*`, `*76*`; mutation logs/XML `tmp/c1-batch4-scan-progress-mutant-{67,68,74,75}`.
Green XML snapshots are in the corresponding `tmp/c1-batch4-scan-progress-green-*` directories.
Local worktree artifacts remain accessible through lane completion plus 30 days. These focused
checks do not establish final full-suite, installed live-model, hosted or platform acceptance.
