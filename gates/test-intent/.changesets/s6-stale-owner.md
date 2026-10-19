---
schema: test-intent.v1
task: s6-stale-owner
author-role: builder
author-session: codex-s6-stale-owner-builder
---

# S6: ended Linux dev-stack owners

New durable script checks: `scripts/dev/test-dev-runner-owner-presence-linux.mjs`.
The runner discovers this file as part of the full dev-runner suite. No existing test is
rewritten, removed or relaxed. Script tests are outside the product-test gate and its D4
runner, per `docs/reference/testing/test-intent.md`; the empty product-test entry list below
does not claim D4 evidence or acceptance. Execution logs stay in `tmp/s6/` as requested.

Source: the owner's builder assignment, user turn headed "Builder brief: an ended owner's dev
stack is reclaimable on Linux (S6)". Its expectations, quoted verbatim:

- O1: "Linux owner whose process has exited → stack verdict abandoned/reclaimable (the same
  verdict a Windows ended owner gets), on every path that decides it (owner-presence, MCP server,
  dev-runner)."
- O2: "Linux owner pid now reused by an unrelated process (same pid, different start ticks) or a
  different boot id → treated as ended, never as the owner."
- O3: "Linux owner alive with matching identity → contention, takeover still needs the user."
- O4: "Linux owner whose identity cannot be read (e.g. /proc unreadable) → not reclaimable without
  the user (conservative)."
- O5: "Windows creationTime owners behave exactly as before (live, ended, reused pid)."
- O6: "the dev-runner and MCP dev-tool suites pass."

Each case exercises the evidence-collection gate plus owner verdict, the dev-runner verdict,
both MCP projection modes, MCP mutation authorization, and the supervisor presence monitor.
A live supervisor and an unexpired lease prevent the dead-stack shortcut. A recent owner touch
prevents an alive owner from looking idle. Ended-owner tests also require explicit warn takeover
to proceed; live/unknown owners retain CONTENTION and no self-authorized recommendation.
The supervisor retains the existing periodic full identity check and abandonment grace period.
Windows advisory PID reuse retains its existing conservative CONTENTION result.

| Scenario | Checks in the new file | Before | After | Lifetime |
| --- | --- | --- | --- | --- |
| O1 | `O1 Linux exited:*` (6 checks) | 6 assertion failures: unknown/CONTENTION/renew instead of ended/abandoned/pause | 6 pass | durable |
| O2 | `O2 Linux pid reused:*`, `O2 Linux previous boot:*` (12 checks) | 12 assertion failures for the same missing Linux identity gate | 12 pass | durable |
| O3 | `O3 Linux alive:*` and self/legacy evidence guard (7 checks) | 7 pass | 7 pass | durable |
| O4 | `O4 Linux unreadable birth:*`, `unavailable table:*`, `malformed identity:*` (18 checks) | 18 pass | 18 pass | durable |
| O5 | Windows live, exited, reused, unreadable birth and unavailable table (30 checks) | 30 pass | 30 pass | durable |

Before: detached worktree of `abe1aff3af934b4cd2de096bd2ff235f35361599`, with only the new
test file copied into it. Command: `node scripts/dev/test-dev-runner-owner-presence-linux.mjs`.
`tmp/s6/focused-before.log`: exit 1, 55 pass, 18 assertion failures, 0 skipped.
Candidate: same command; `tmp/s6/focused-after.log`: exit 0, 73 pass, 0 failures, 0 skipped.
O6 candidate suite execution: `node scripts/dev/run-dev-runner-tests.mjs --verbose` passes 53/53
files (`tmp/s6/dev-runner-suite.log`); the original before suite passes 52/52 files
(`tmp/s6/dev-runner-before.log`); all six `test-dev-mcp-*.mjs` scripts pass
(`tmp/s6/mcp-suite.log`). The before MCP suite passes 5/6 files: its existing projection-live
test misidentifies the nested temporary checkout as a named worktree, then fails its two
`distFrom` name-resolution assertions. See `tmp/s6/mcp-before.log`; no test or unrelated
worktree-resolution behavior is changed to make it pass.

Live scaffolding: `tmp/s6/live-owner-check.mjs`, run with
`flock /tmp/js-linux-port/devstack.lock node tmp/s6/live-owner-check.mjs`. On an isolated real
dev-runner with the conformance fake Engine and a real Linux owner process, MCP stdio
`quick_health` reports CONTENTION while the owner runs and the real CLI refuses another
caller's stop. Closing the owner's stdin ends it normally. MCP then reports
TAKEOVER_ABANDONED and the same caller's ordinary stop succeeds without force or CLI kills;
the isolated active record is removed. `tmp/s6/live-owner-check.log`: exit 0. This is temporary
scaffolding; the script test above is durable. No shared lease is changed.

```json
{ "entries": [] }
```

## Acceptance records
