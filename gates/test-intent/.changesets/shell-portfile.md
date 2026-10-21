---
schema: test-intent.v1
task: t-20261019-5ec1a1
author-role: builder
author-session: builder-shell-portfile-20261021
---

# Atomic shell fixture publication

Only test instrumentation changes. The Python connection fixture now publishes its complete
bound port with a sibling write and atomic replacement; its Rust reader retries unreadable or
unparseable contents under the existing three-second deadline and startup-failure message.
The containment PID and X11 activation-count fixtures use the same publication protocol.
The gate flags the two Rust items below. The Python helper is not flagged by this gate;
the independent verifier must check it too.
No product behavior, oracle, input, fault injection, assertion, tag, ignore or test selection changes.
The containment reader retains its five-second startup bound and waits for a parsed PID.

The source below distinguishes the owner's delegation from the orchestrator's repair choice.
The repair choice is agent wording, not owner words.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/engine_conn_test_support.rs",
        "modules/shell/src-tauri/src/containment.rs#cfg(test)"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "The fixture-to-reader handoff moves from an in-place file write to a complete temporary sibling followed by atomic rename/replacement. Port and PID polling treats only parsed contents as ready, keeping the original polling intervals and deadlines. Existing assertions, connection proof boundaries, successor ports, child-owner death checks, activation messages, tags, ignores and selection stay unchanged; no product code changes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 user turn containing the quoted autonomy delegation (source supplied in the shell port-file builder assignment)",
          "delegationScope": "Remaining work in task t-20261019-5ec1a1, including repairing shell test-fixture reliability while preserving established product contracts and test expectations.",
          "choice": "The orchestrator chose to repair the flaky Linux CI port-file publication by publishing complete fixture files atomically and retrying unparseable readiness values, preserving the existing three-second port deadline and message, and to repair the same publication pattern in other shell fixtures. This choice is agent wording.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Validation

Baseline: `2c7501faf609bcd52c3712b04af81e44f9213117`. All Rust commands ran in
`modules/shell/src-tauri`, using its default `target` directory without cleaning or overriding it.

- The temporary probe extracts the actual Python setup and Rust readiness loop, pauses the writer
  after truncation or a flushed numeric prefix, and starts the reader during that pause. Before:
  empty contents panic with `ParseIntError { kind: Empty }`; a prefix returns `34` for actual port
  `34701`. After: both modes return the complete bound port. The old reader with the atomic writer
  also passes both modes, showing that publication prevents the valid-prefix race.
- The candidate reader waits on empty, invalid and out-of-range contents, then accepts the complete
  port. Persistent empty contents fail at 3.001 seconds with `fixture server failed to start`.
- Analogous temporary PID and counter probes show the old fixture exposing PID `12` during a
  `12345` write and count `1` during a `9` to `10` update. The candidate returns PID `12345` and
  preserves count `9` until publishing the complete `10`.
- `cargo test --lib shell_actuator::connection_tests`, 200 iterations before: 192 successful runs,
  8 failing runs with the empty-port panic. After: 200 successful runs. Each successful run executes
  five tests; the existing installed-Engine check remains explicitly ignored.
- Before preservation: the containment owner-death check passes (1/1); the shortcut module passes
  (11/11), including native X11 probes.
- `cargo test --lib`, once after: 244 passed, 0 failed, 1 ignored (the existing installed-Engine check).
  This includes the repaired containment and native X11 fixtures.
- `git diff --check` passes. `rustfmt --check` reports the same pre-existing formatting difference
  at `containment.rs:785` on the baseline and candidate; it is outside the changed lines.

Temporary probes, source hashes and complete logs are in `tmp/builder/portfile-evidence` under the
builder's TMPDIR; they are uncommitted and excluded from repository content. The user staged the
checkout-local process helper with CI's recipe before the resumed run. The builder staged no other
prerequisites. Checks use the default Cargo target/cache and their own child processes and private
Xvfb fixtures; they do not borrow another checkout's target or a running application/service.

The test-intent gate's historical, nonblocking reconsider-rate warning reports 1/9 audited PRs
(11.1%). Independent acceptance is intentionally left to the verifier.

## Acceptance records
