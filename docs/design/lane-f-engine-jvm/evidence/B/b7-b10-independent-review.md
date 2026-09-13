# B7-B10 independent review

Reviewed 2026-09-08, range `e692b86ef..1ffd6cc2d`, by a read-only reviewer who
did not implement the range. This is source review, not runtime proof. Findings
remain open until their fix commits and regression evidence are recorded.

| ID | severity | finding and primary source at `1ffd6cc2d` | required correction |
|---|---|---|---|
| R1 | blocker | `lib.rs:105-111` resets binding identity; `binding.rs:77-91` treats the next manifest as first bind; `lib.rs:1306-1308` therefore omits the event that `ui-web/src/main.jsx:140-153` needs to reload port/token | Retain predecessor identity while clearing credentials; test the real reset/rebind/event transition |
| R2 | blocker | `supervisor.rs:629-640` checks cancellation before handle wait/cooldown, then spawns; `lib.rs:111` clears the killed flag on that path | Separate monotonic host close from incarnation reset and serialize final spawn admission with close cleanup |
| R3 | high | `ShutdownRequestWatcher.java:137-140` deletes the request before dispatch; `dev-runner.cjs:2664-2674` and `supervisor.rs:706-712` only sample it; parsers discard the deadline | Implement the accepted-instance marker, retention, exit-time read and original deadline protocol decided in design section 0; test rejected intent and stale markers too |
| R4 | cross-cutting verification gap | `bin/supervisor_conformance.rs:24-26,42-60,117` includes the loop but uses a distinct `FakeEngineActuator`, never the `ShellActuator` in `lib.rs` | Exercise extracted production bindings with injectable OS/Tauri edges and real children where handle behavior matters; correct coverage claims |
| R5 | high | Initial spawn error bypasses supervision (`lib.rs:1693-1713`); replacement spawn error returns an exhausted outcome while publishing starting (`supervisor.rs:636-647`) | Publish a real terminal transition for both initial and replacement spawn failure |
| R6 | high | `ui-web/src/api/supervisorState.ts:86-102` has no production caller; `main.jsx:140-153` installs only backend restart | Install at boot and connect state to actual outage/terminal presentation, with a wiring regression |
| R7 | medium | API responsiveness starts the stability clock (`dev-runner.cjs:2554-2576,2677-2683`; `lib.rs:1105-1118`; `supervisor.rs:582-588,651-660`) before essential index readiness | Separate API-ready running state from continuous essential-component readiness used to reset the budget |
| R8 | medium | `dev-runner.cjs:2350-2382` signals children without disabling supervision; `:2719-2720,2793-2814` can restart while teardown waits for the frontend | Disable supervision before signalling; one teardown owner and a deliberate-shutdown race test |
| R9 | medium | Every spawn starts a watcher (`lib.rs:992-1003`); its loop has no generation or host-close termination (`:1285-1330`) | One watcher per host state or cancel/join each predecessor; test bounded watcher lifetime across requested restarts |

Paths abbreviated in the table: Rust sources are under `modules/shell/src-tauri/src/`;
`dev-runner.cjs` is under `scripts/dev/`; `ui-web/` is under `modules/`;
`ShutdownRequestWatcher.java` is under
`modules/app-engine/src/main/java/io/justsearch/app/engine/`.

The orchestrator independently reread the binding reset/event path, host reset and
cooldown path, absent frontend installation, and request observation/deadline paths.
The proposed retain-only request fix was refuted before implementation: a supervisor
could see a request before the Engine rejects it. The accepted-instance marker
records validation without creating a second artifact or duplicating the lease
authority. The amended protocol is in design section 0.

Verified sound by source review: classifiers agree on requested/transient/non-transient
exits and hang accounting; both policy overrides require the exact harness flag;
dev command overrides also check that flag; the closure scanner now checks both
production writers and both root and nested Rust source files. The harness's existing
override test covers policy overrides, so a command-override negative test is still
required when revisiting that binding.

An initial claim that Rust's Windows rename cannot replace a destination was
falsified against Rust's implementation and withdrawn. It is not a finding and
must not cause a replacement helper to be added. No build, service, or tracked-file
mutation was performed by the reviewer. Packaged runtime behavior still requires
the new binding tests and the stage-B checkpoint proof.

## Production ownership repair evidence (2026-09-08)

R1, R2, R4, R5 and R9 are implemented on `codex/lane-f-host-ownership` for a new
independent review. `modules/shell/src-tauri/src/engine_host.rs` is the Tauri-free production core
called by `lib.rs`; it owns the real `Child`, monotonic close, spawn generation, discovery phase,
watcher handle and latest `StateRecord`. The regression suite uses real child processes for both
close/spawn orderings and successor handoff. Manifest restart callbacks and initial launch-terminal
publication are shared production seams. Replacement launch failure runs through
`run_supervision` and the same host state writer used by `ShellActuator`.

Focused verification uses a test-only Tauri resource override; package configuration is unchanged:
`$env:TAURI_CONFIG='{"bundle":{"resources":[]}}'; cargo test --lib` — **59 passed, 0 failed,
0 ignored** after the shared manifest/event/tooltip admission seam and launch-failure writer proof.
`rustfmt --edition 2021 --check src/engine_host.rs` and `git diff --check` passed. The
repository-wide `cargo fmt --all -- --check` remains red across untouched Rust files, so it is not
claimed as a green acceptance check.

The count reconciles exactly from the inherited **56**: seven `BackendState` tests that bypassed
real child admission were removed, and ten `engine_host` production-core regressions replaced them,
for **59**. The replacements cover the same binding rules through an installed child and add both
close/spawn orderings, monotonic close, stdout generation, terminal publication, watcher ownership,
self-cancel and tooltip admission/cache behavior.

This batch intentionally excludes R3 request acceptance/deadline protocol, R6 UI installation, R7
liveness/readiness semantics, R8 dev-runner teardown, B11/B13 managed-child/updater work and any
updater overhaul. It does not change the shared exit policy or supervision register.

Complete captured output is preserved in `host-ownership-cargo.log`. Mutation checks were run
against the actual shared production seams, then each mutation was restored before the final green
run:

```text
mutation: suppress observe_manifest_with_sinks restart callback
test engine_host::tests::real_reset_rejects_stale_manifests_and_emits_once_for_successor ... FAILED
assertion `left == right` failed
  left: 0
 right: 1
test result: FAILED. 0 passed; 1 failed; 0 ignored; 58 filtered out

mutation: suppress publish_initial_spawn_failure event callback
test engine_host::tests::initial_launch_failure_uses_shared_terminal_disk_event_writer ... FAILED
called `Option::unwrap()` on a `None` value
test result: FAILED. 0 passed; 1 failed; 0 ignored; 58 filtered out

mutation: remove replacement-spawn transition to State::Exhausted in run_supervision
test engine_host::tests::replacement_launch_failure_traverses_shared_loop_and_real_writer ... FAILED
assertion `left == right` failed
  left: "starting"
 right: "exhausted"
test result: FAILED. 0 passed; 1 failed; 0 ignored; 58 filtered out

restored final: cargo test --lib
test result: ok. 59 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out
```

The final green used `TAURI_CONFIG={"bundle":{"resources":[]}}` only in the test process because
the verified clean worktree does not contain the generated `resources/headless/**/*` payload. The
first un-overridden attempt failed solely in `tauri-build` with `glob pattern
resources/headless/**/* path not found`; no Cargo or package configuration was weakened. The unit
suite executes the shared host core and writer but does not instantiate Tauri's `run()` setup; the
`run()` call into `publish_terminal_spawn_failure` remains source-reviewed production wiring.
