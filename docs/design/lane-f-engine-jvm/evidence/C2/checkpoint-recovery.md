# C2-4 frontend checkpoint recovery

2026-09-13, based on1fea587c8. A reducer failure no longer acknowledges its frame.
EnvelopeStream keeps the prior payload and observed sequence, clears the checkpoint,
marks the connection disconnected and detaches before notifying listeners. The existing
reconnect/backoff owner requests fresh state without that token. MultiplexedStream clears
only the failed logical stream's token and lets the physical owner reconnect; unaffected
entries retain their payloads and tokens in the bundle. No new timer owner or state flag.

Negative1216 fails three intended assertions against prior production; two selected cases
pass and40 cases are selector-skipped. Initial focused1217 and full1219 pass, but independent
review finds a reentrant start during failure notification: when its replacement also fails,
the pending-timer early return leaves it attached. Negative1220 reproduces that exact defect
(one intended failure,25 selector skips). The correction always detaches the failed source
before deduplicating the reconnect timer. Late frames from the replacement are ignored,
and the existing timer opens fresh state. Intentional stop remains respected.

Final focused1221 passes46 tests; full1222 passes6,519 tests across484 files and typecheck
passes. Only source comments and canonical wording change afterward. Documentation
generation/checks and canonical links pass in1223; affected-step discovery reports no
visual steps. Full Vitest retains its fixture connection-refused/Happy DOM diagnostics.
Independent review rechecks the reproduced defect, source and artifact hashes and finds
no remaining substantive defect. No failed run is relabelled as a pass.

[Exact commands, results, source hashes and accessible logs](checkpoint-recovery-verification.json)
are retained through lane acceptance plus30 days; export before worktree release.
This is local frontend proof. Backend atomic snapshot attachment, incarnation validation
and safe lifecycle tokens remain next, followed by real SSE/restart/rendered overlap and
the installed/hosted tiers in the [SSE plan](C2-4-sse-plan.md). No C2-4 closure is claimed.
