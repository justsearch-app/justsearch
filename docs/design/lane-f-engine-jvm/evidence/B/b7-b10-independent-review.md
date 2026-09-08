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
