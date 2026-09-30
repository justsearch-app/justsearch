# Live SSE recovery and rendered history overlap

2026-09-13, production revision **e7edaf503**. Live1238 passes17 checks over14
connections. UI1241 passes typecheck and all6520 tests in485 files with the added
rendered regression. [Commands, source hashes and accessible artifacts](sse-live-rendered-verification.json).

The managed distribution was rebuilt with `:modules:ui:installDist` (1235 PASS);
all five relevant installed jars match the built jars before launch. The live harness
uses fresh owned data and persists expected BAD_REQUEST outcomes from empty
core.resolve-path-hash invocations. It observes each completion once through standalone
operation history, standalone action ledger and the multiplexed action-ledger source.
Operation history is not one of the multiplexed sources.

After completing a second row while disconnected, each source reconnects from the first
checkpoint and deliberately disconnects at connected, before consuming replay. Reconnecting
again from that connected token replays the second row exactly once, without the first row
or an unnecessary snapshot/reset. HTTP and MCP return the same recorded outcome.

The harness stops and restarts the owned process with the same data, advances replacement
sequences beyond the old checkpoints, then verifies that all three old-incarnation tokens
receive reset and a durable snapshot containing both original identities exactly once.
A keyed retry after restart leaves one operations row and one ledger event.
Both managed runs report successful shutdown and closed ports; quick_health afterwards
reports ABSENT with no foreign runs or inference orphan.

The HappyDOM regression drives a controlled EventSource through the actual pooled
EnvelopeStream, subscription strategy, ResourceView and jf-table. It first renders three
snapshot rows, replaces a repeated operationKey in place, preserves another key with the
same operationId, and appends an unkeyed observation independently. DOM teardown releases
the source and listeners. Negative1239 disables key propagation and fails at the visible
four-row assertion with five rows. Production was restored byte-for-byte before UI1241.

1237 remains FAILED: an ambiguous jar-name filter stopped the harness before managed
startup;1238 uses an exact module/version pattern. Typecheck1240 remains FAILED because
the new fixture lacked a source existence guard; fixed before1241. The earlier focused
worker run1236 passed but did not establish type safety. Raw logs remain accessible in the
manifest through lane acceptance plus30 days and must be exported before worktree release.

These are separate live protocol and simulated-source DOM witnesses. They do not claim a
single real-browser reconnect campaign, physical power-loss proof or model-quality proof.
Forced snapshot-publication and slow-subscriber races are covered by the focused Java
regressions in [snapshot retirement](snapshot-retirement.md). ui-shot affected mapping
reports no steps for the new test; the UI coverage gate passes. No visual screenshot is
claimed. Coherent full/stress, installed-schema5 and hosted proof remain required.

Independent refute-first source/artifact review is clear after retaining the actual post-stop
health response. All25 manifest entries match their accessible artifacts. The reviewer did
not execute the campaign; execution claims above come from the retained run outputs.
