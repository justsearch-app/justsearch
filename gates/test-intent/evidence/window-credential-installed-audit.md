# Entry 14 installed-Engine execution and entry 16 class review

Candidate: `00974d061c8563b68eadf81968aca0ee855f959b` plus the uncommitted assertion-form change
in `modules/shell/src-tauri/src/shell_connection_tests.rs` (authorized builder 12 continuation).
Before-state: `69869bfe490ffb06e94d1ec079544982aced66c0`.
Builder evidence only; independent acceptance remains outstanding.

## Runner and isolation

The testing reference's "Execution evidence" section documents Rust execution through
`run-evidence.mjs`, but neither that section nor the runner supplies an ignored-test option.
The temporary runner adaptation in `window-credential-installed-runner.patch` adds
`--include-ignored` to the exact libtest commands. It also uses a supplied shared clone after
checking its HEAD against `--before`, instead of registering a worktree in the source repository.
Parsing, check-source hashing, assertion classification and evidence serialization are unchanged.
The generated JSON records the adapted script's digest and each exact command. It is copied
unchanged from the candidate clone; it is not hand-written or edited.

Both trees were created inside the session's `$TMPDIR`:

```sh
git clone --quiet --shared --no-checkout . "$TMPDIR/candidate"
git -C "$TMPDIR/candidate" checkout --quiet --detach 00974d061c
git clone --quiet --shared --no-checkout . "$TMPDIR/before"
git -C "$TMPDIR/before" checkout --quiet --detach 69869bfe49
```

Each owns its distribution and Cargo target. Existing Cargo artifacts seeded each target as
ordinary copies; Cargo rebuilt the checkout-specific crate. Each clone ran
`./gradlew :modules:ui:installDist -PskipWebBuild=true --console=plain` successfully, then compiled
its own native helper exactly as CI's "Stage Linux process-lifetime helper" step does:

```sh
helper="modules/ui/build/install/ui/$(node -p 'require("./scripts/lib/native-targets.cjs").nativeTarget().paths["bound-exec"]')"
mkdir -p "$(dirname "$helper")"
cc -std=c11 -O2 -Wall -Wextra -Werror native/bound-exec/bound-exec.c -o "$helper"
```

The admission CLI caps `--gate-timeout` at 720 seconds. These runs use the same admission module's `admission` context
with `gate_timeout=3600` and its `run_command` helper, preserving inherited admission descriptors.
Both distribution builds and the complete evidence invocation take admission this way.
`.agents/preflight` is absent.

## Entry 16 class

Against gate base `81f1b00072`, the only change within `engine_host.rs`'s test items is in
`manual_restart_after_finished_exhaustion_reaches_ready_with_a_new_generation`: after admitting
its fake child, the fixture supplies that generation's synthetic window credential and returns
the same child's PID. Inputs still describe an authenticated fake Engine. The fault injection,
readiness observation, failed-generation/manual-restart, running-state, reset-budget, binding,
reaping assertions, tags and conditions are retained. This is input re-encoding under the new
credential requirement, so `Adaptation` fits the class table. That class requires no D4 evidence.
No new expected outcome is inferred from production output.

Both entries now quote the owner's actual delegation, with the existing request location and
scope. Readiness semantics are identified as the agent's N5 choice; the lifecycle source names
N5/N11. The orchestrator's readiness decision is not presented as owner words.

## Resources

Checks use fresh temporary app data, owned child processes and loopback listeners. They touch
the shared Gradle cache/daemon and local CPU/disk. No owner application, dev stack, GPU or CI
is used. Only test-owned children are stopped. Raw setup/run logs stay in the session temporary
directory; the assertion output and exact test commands are retained in the generated JSON.

## Assertion-form correction

The prior run failed on the intended real reconciliation HTTP 403, but the runner classified the
custom-message `assert!` as `exception`. The continuation explicitly authorized changing that
check to a plain assertion and regenerating evidence. Its boolean condition is unchanged:
only HTTP 503 before the deadline may be retried. `assert_eq!(condition, true, ...)` retains the
same condition, error diagnostic and failure point, and emits Rust's standard equality assertion
failure. No other product test or production code changed.

## Observed results

Both fresh `installDist` commands exited 0 (candidate 18 seconds, before-state 7 seconds).
Both CI-style native-helper compiler commands exited 0. Admission was requested separately for
each distribution build and the evidence invocation, each with `gate_timeout=3600`.

The adapted evidence command exited 0. All seven exact candidate commands exited 0 with one
executed/passed check apiece and zero ignored checks. All seven before-state commands exited
101 and are classified `assertion`. The exact commands are recorded in the generated JSON.
Every Cargo invocation uses this prefix, including the explicitly ignored installed-Engine test:

```sh
cargo test --lib --locked --manifest-path modules/shell/src-tauri/Cargo.toml -- --include-ignored --exact <libtest-name>
```

| Exact libtest name | Candidate | 69869bfe49 |
| --- | --- | --- |
| `shell_actuator::connection_tests::window_credential_authorizes_update_preparation` | exit 0, passed | exit 101, assertion |
| `shell_actuator::connection_tests::window_credential_authorizes_reconciliation` | exit 0, passed | exit 101, assertion |
| `shell_actuator::connection_tests::window_credential_authorizes_quit_without_request_file` | exit 0, passed | exit 101, assertion |
| `shell_actuator::connection_tests::supervisor_successors_use_the_new_pid_and_coherent_binding_on_same_and_new_ports` | exit 0, passed | exit 101, assertion |
| `updater::reconciliation_tests::delayed_window_credential_waits_before_reconciliation` | exit 0, passed | exit 101, assertion |
| `updater::reconciliation_tests::shell_readiness_waits_for_current_window_credential` | exit 0, passed | exit 101, assertion |
| `shell_actuator::connection_tests::installed_engine_shutdown_upgrade_and_released_port` | exit 0, passed | exit 101, assertion |

The installed-Engine before-state failure was:

```text
panicked at src/shell_connection_tests.rs:273:13:
assertion `left == right` failed: installed Engine reconciliation failed: Err("Engine request rejected (403)")
  left: false
 right: true
```

The candidate explicitly executed the same installed-Engine test, including real durable-intent
reconciliation and the retained quit, prepare/cancel/commit and released-port checks, and passed.
The evidence JSON was copied byte-for-byte from the candidate clone after the runner exited 0.
It has not been edited by hand.

The evidence invocation from the candidate clone, with the recorded runner patch applied and
`WINDOW_EVIDENCE_BEFORE="$TMPDIR/before"`, was:

```sh
node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/window-credential-shell-lifecycle.json \
  --before 69869bfe49 \
  --overlay modules/shell/src-tauri/src/engine_conn_test_support.rs \
  --check modules/shell/src-tauri/src/shell_connection_tests.rs#shell_actuator::connection_tests::window_credential_authorizes_update_preparation \
  --check modules/shell/src-tauri/src/shell_connection_tests.rs#shell_actuator::connection_tests::window_credential_authorizes_reconciliation \
  --check modules/shell/src-tauri/src/shell_connection_tests.rs#shell_actuator::connection_tests::window_credential_authorizes_quit_without_request_file \
  --check modules/shell/src-tauri/src/shell_connection_tests.rs#shell_actuator::connection_tests::supervisor_successors_use_the_new_pid_and_coherent_binding_on_same_and_new_ports \
  --check modules/shell/src-tauri/src/updater_reconciliation_tests.rs#updater::reconciliation_tests::delayed_window_credential_waits_before_reconciliation \
  --check modules/shell/src-tauri/src/updater_reconciliation_tests.rs#updater::reconciliation_tests::shell_readiness_waits_for_current_window_credential \
  --check modules/shell/src-tauri/src/shell_connection_tests.rs#shell_actuator::connection_tests::installed_engine_shutdown_upgrade_and_released_port
```

`node scripts/governance/gates/test-intent/cli.mjs --base 81f1b00072` exited 1 with exactly one
problem, `test-intent/acceptance-missing`. All ten flagged items are covered; no evidence-invalid
or uncovered problems remain. Current changeset digest:
`sha256:5cb799428126778582e31a8994681807ec098cc3e4bccecc52fd0fa7f6faa8a5`.
`git diff --check` exited 0. `.agents/preflight` is absent. No commit was made.

Independent acceptance of the final candidate remains outstanding.
