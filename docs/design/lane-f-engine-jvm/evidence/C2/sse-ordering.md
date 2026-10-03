# C2-4 ordered SSE publication

2026-09-13, source based on f3c07654b. The channel now allocates sequence numbers,
appends retained UPDATEs and enqueues each listener under one short publication
boundary. The existing handoff queue becomes the permanent bounded delivery owner;
one thread drains each listener outside that lock. Other publishers enqueue behind
a blocked socket and can drain healthy listeners. No executor or durable state is added.
Overflow or callback failure retires that listener and clears queued references.

Negative1204 forces the old allocation race and blocked-socket fan-out: the old source
delivers sequences [2,1] and leaves the healthy listener's first frame behind the
blocked socket. Both regressions fail for those intended reasons. The correction also
tests reentrant publication and repeated identical Error instances without preventing
healthy fan-out. Independent read-only review found the obsolete test that required
attachment to block behind socket delivery; its replacement keeps the exact replay
assertions and requires attachment to complete while the existing socket is blocked.

The numeric run contract is preserved separately from the forthcoming strong cursor:
cursor zero still attaches after actual history eviction and replays the retained tail.
The requested capacity-two/three-publish regression passes. Existing slow replay,
concurrent overflow, queue clearing, failure cleanup and ordered handoff tests pass.

Focused1206 executes 70 cases; final1210 represents 71 (56 execute,15 reuse successful
serialization inputs), with zero failures/errors/skips and PMD/format/integration
compilation passing. Compatibility1213 executes 572 cases across the full observability
module and existing SSE/run writers, with zero failures/errors/skips. Independent review
has no surviving implementation defect; the requested eviction proof is now executed.

[Commands, counts, tested-source hashes and retained XML/logs](sse-ordering-verification.json)
keep failed and successful runs separate. Retain until lane acceptance plus 30 days;
export raw artifacts before worktree release. Full1202 predates this cut. Atomic durable
snapshot attachment, incarnation tokens, safe lifecycle checkpoints and live reconnect
proof remain the next part of [C2-4](C2-4-sse-plan.md); no stage closure is claimed.
