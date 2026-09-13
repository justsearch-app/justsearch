# C2-4 durable recent history and invocation identity

Implemented 2026-09-13 from7b5248442. OperationHistoryStore now reads the latest200
visible terminal operations through a narrow metadata SELECT, ordered by completion
time then row id and returned oldest first. It no longer owns a deque. SQL/live
committed entries share one projection, and optional operationKey (proto field11)
distinguishes invocations of the same operation. The ledger uses operation:<key>;
legacy and uncommitted observations retain the prior fallback identity. Resource
retention is30 days, separate from the display limit and five-minute resume window.
Both production bootstrap paths pass the same operations owner. Private input,
prepared payloads and signed intent tokens are excluded from history projection.

## Proof and corrections

- Wire negative1131 detects the missing operationKey proto field. Registration
  negative1135 detects the undeclared new projection. Schema1136 detects only the
  additive operationKey property;1137 captures that baseline after the expected
  missing-baseline refusal. The optional wire field needs no version bump.
- Initial1134 fails fixture compilation and PMD: observability has no Mockito
  dependency, proto descriptor optionality uses getProto3Optional(), and inherited
  constants need no qualifier. Correct fixtures/source without weakening checks.
  Its57 passing cases do not constitute a passing build.1136 represents213 cases
  with only the expected schema failure; the118 services cases pass there.
- Negative1138 restores id-only ordering and legacy ledger identity: both intended
  regressions fail (three represented cases, two failures). Exact saved source
  bytes are restored. Final1139 passes213 represented cases across33 suites with
  zero failures/errors/skips:95 execute,118 services reuse unchanged successful
  inputs from1136. PMD, formatting, UI integration compilation, schema sync and
  installDist pass.1140 passes wire/operation-surface/register guards, zero findings.
- Live1141 uses the lane-owned official dev MCP server and current installDist.
  An invalid argument to audited core.resolve-path-hash is accepted and recorded
  as BAD_REQUEST before its effect. The first probe incorrectly expected HTTP400;
  the existing invocation contract returns200/success:false/HANDLER_FAILURE with
  errorCode BAD_REQUEST. Preserve that failed probe, correct its expectation from
  source, and rerun. The final campaign passes nine captured HTTP exchanges: served
  schema, accepted failure, recent history and keyed outcome survive a controlled
  restart; retry leaves exactly one entry for that key. Both controlled stops close
  the owned ports. This dev-mode run is not production mutation-token enforcement,
  crash-gap replay, or installed recovery proof.
- Root's separate skeptical reread checks both bootstrap paths, bounded SQL with
  no private payload SELECT, first-acceptance metadata, same-timestamp identities,
  late older completion, and R1 live STORAGE_FAILED with a still-RUNNING SQL row.
  No remaining defect was found in this reader cut. No independent model or
  completed batch review is claimed.

[Commands, revision, counts and source/artifact hashes](history-reader-verification.json)
identify retained raw logs, XML and live responses under the lane worktree tmp.
Retain until lane acceptance plus30 days and export before worktree release.
Docs generation/link/skill/module/config checks pass in1143.

## Remaining work

Dispatcher-only live publication remains until the next completion-source cut.
Durable source acknowledgement, retry/catch-up before pruning, memory/note ledger
fan-in and atomic SSE snapshot/replay are still required. This reader does not
close C2-4, batch2, stage C or the lane. Full1059 predates these C2-4 changes; a new
coherent full run and installed-v4 recovery remain required. Latest observed
head7b5248442 CI34753477130 is pending and CLA34753476241 queued, awaiting external
runner allocation. No hosted success is inferred; merge placement remains F/PR1.
