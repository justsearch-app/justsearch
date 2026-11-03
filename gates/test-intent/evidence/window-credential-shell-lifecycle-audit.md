# N5/N11 shell lifecycle credential audit

Builder candidate based on `69869bfe490ffb06e94d1ec079544982aced66c0`; uncommitted.
This is implementation/check evidence, not independent acceptance.

## HTTP call inventory

| Caller | Engine request | Transport |
| --- | --- | --- |
| `updater.rs::prepare_head` | POST `/api/upgrade/prepare` | `post_head` → `shell_actuator::post` |
| `updater.rs::cancel_head` | POST `/api/upgrade/cancel` | same |
| `updater.rs::commit_head` (existing unused helper) | POST `/api/upgrade/commit-shutdown` | same |
| `UpdateCoordinator` target reconciliation | POST `/api/upgrade/reconcile` | same |
| `ShellActuator::write_shutdown_request`, upgrade | POST `/api/upgrade/commit-shutdown` | `post_before_send` |
| `ShellActuator::write_shutdown_request`, quit | POST `/api/lifecycle/shutdown` | `post` → `post_before_send` |
| `ShellActuator` health/liveness | GET `/api/health` | `engine_probe::responds`; no secrets |
| `ShellActuator` essential readiness/not-ready reason | GET `/api/status` | `engine_probe`; no secrets |

All authenticated paths reach `EngineHost::connect_engine` and `engine_conn::post_connected`.
The host snapshots the admitted child's window credential with its generation, PID and binding,
then retains the existing socket ownership and post-proof generation/binding/liveness checks.
The sender rejects empty or CR/LF-containing window credentials before invoking the send hook.
`Binding`, manifests and persisted upgrade intent do not acquire the secret.
No route was added to the token-only allowlist.

Search: `rg -n 'X-JustSearch-Session|\.header\(|reqwest|TcpStream|http://127.0.0.1' modules/shell/src-tauri/src`.
`updater.rs`'s other reqwest requests fetch release metadata/artifacts from HTTPS release URLs;
they do not address the Engine or carry its credentials. `lib.rs` constructs a loopback origin
for the webview credential command but makes no HTTP request there. The supervisor-conformance
Rust adapter supplies a fake actuator, not another authenticated Engine HTTP transport.

The installed API-port proof's two settings mutations already use `requestThroughWindow`.
Cleanup now uses that same helper for POST `/api/lifecycle/shutdown`; its obsolete token-only
POST wrapper was deleted. The remaining direct requests are reads.

## Readiness repair

All production shell Engine launches enable `-Djustsearch.windowCredential=true`.
`EngineHost::authenticated_pid` now checks the current admitted child, matching manifest binding,
port, token and window credential under one lock. Both `ShellActuator::await_ready` (through the
host startup predicate) and updater readiness require it. A delayed predecessor stdout record
cannot satisfy current readiness. Existing connection-time identity and liveness proof remains.

The updater retains its 60-second readiness bound. If it expires before authenticated readiness,
it leaves the durable intent in RECONCILING and reports a transient readiness error in the existing
`reconciling` status; it does not submit reconciliation or apply a repair-required transition.
Explicit startup failure and an actual reconciliation refusal/invalid attestation retain their
existing error handling. The timeout branch is established by inspection, not an end-to-end timed
installation run.

The manual-restart unit fixture and the supervisor-conformance fake-Engine adapter now install
an explicit synthetic credential on their admitted fake incarnation. They discard fake stdout
and do not run the production JVM authority implementation. Their existing readiness, restart,
shutdown and budget expectations remain intact. Real Engine launches still consume secret stdout.

## Checks observed (continuation)

- `.agents/preflight`: absent; the directory is empty.
- `cargo test --locked --manifest-path modules/shell/src-tauri/Cargo.toml`: exit 101,
  **278 passed, 2 failed, 1 ignored**. Only
  `search_shortcut::tests::sc1_sc9_x11_summon_requests_activation_from_never_shown_and_hidden`
  and `search_shortcut::tests::sc8_x11_off_releases_all_lock_variants_to_another_client` fail.
  Their private Xvfb cannot bind its Unix listener in the sandbox. The untouched baseline shared
  clone, with its own built distribution/target, reports **273 passed, the same 2 failed,
  1 ignored**, exit 101. These are not regressions.
- An initial candidate run found one additional failed fixture: manual restart remained
  `starting` because its fake Engine supplied no window credential. Supplying that fixture's
  synthetic credential restored its unchanged `running` and restart assertions in the final run.
- `npm_config_cache="$TMPDIR/npm-cache-before" ./gradlew :modules:ui:installDist` in the baseline
  shared clone: exit 0, BUILD SUCCESSFUL (46 seconds). The candidate's existing independently
  built distribution was reused; no Java source changed.
- The adapted evidence runner: exit 0. Its six exact test commands and complete assertion failures
  on `69869bfe49` are in `window-credential-shell-lifecycle.json`; all six pass on the candidate.
  The original four checks cover 403 preparation/reconciliation, HTTP quit without the fallback
  file, and every lifecycle/upgrade route with successor credentials on same/new ports.
  The two new checks cover delayed credential readiness and the shared shell readiness predicate.
- `cargo test --locked --manifest-path "$TMPDIR/race-before/modules/shell/src-tauri/Cargo.toml" window_credential -- --nocapture`:
  exit 101, **3 passed, 2 failed**. This second shared clone has the exact continuation-start
  repair applied to `69869bfe49`, with only the new test source overlaid. Thus the original three
  credential checks pass while the two readiness tests expose the remaining race:

  ```text
  assertion failed: port/token must not release reconciliation before window authority arrives
  assertion failed: responsive port/token alone must remain not-ready
  ```

  The candidate `window_credential` filter: exit 0, **5 passed**. The delayed test installs the
  manifest first, verifies that bounded polling stays pending and emits no reconciliation request,
  rejects a predecessor record, then delivers the current credential and validates the real
  socket fixture's reconciliation response. The shell predicate does not probe before authority.
- `cargo test --locked --manifest-path modules/shell/src-tauri/Cargo.toml installed_engine_shutdown_upgrade_and_released_port -- --ignored --nocapture`:
  candidate exit 0, **1 passed**. A fresh real Engine with window authority and attach disabled
  reconciles the durable intent, completes HTTP quit with exit 0, and completes prepare/cancel/
  prepare/commit with exit 0. Released-port impostors receive zero bytes. The baseline transport
  with the same test/credential fixture overlaid exits 101 on real reconciliation HTTP 403.
- `node --test scripts/supervisor-conformance/*.test.mjs`: exit 0,
  **170 passed, 5 skipped, 175 total** (108 seconds). Skips require Windows.
- `node --check scripts/supervisor-conformance/installed-api-port-restart.mjs`: exit 0.
- `cargo build --locked --manifest-path modules/shell/src-tauri/Cargo.toml --features supervisor-conformance --bin supervisor-conformance`:
  exit 0. Running that binary with an owned `honour` fake Engine, a fresh temporary data directory,
  `--stop-after-incarnation 1 --run-ms 5000`: exit 0, running checkpoint incarnation 1,
  `shutdownRequestAtCheckpoint:false`, then requested quit exit 0, `forcedKill:false`.
- No Java files changed; `node scripts/dev/java-prepush.mjs` is not applicable.
- `node scripts/governance/gates/test-intent/cli.mjs --base 81f1b00072`: exit 1,
  **only `test-intent/acceptance-missing`**. Current changeset digest:
  `sha256:5b1c6c4d9165c1f043b9713b931a8c325ba6d7b0654adee7e3e11dac15cc9195`.
- `git diff --check`: exit 0. No commit was made.

The evidence runner normally creates a Git worktree. To honor the builder's shared-clone rule,
a temporary copy uses the supplied baseline clone (verifies its HEAD) and retains it for live
checks. Check execution, parsing, classification, hashing and evidence serialization are unchanged.
Its exact adaptation is `window-credential-shell-lifecycle-runner.patch`; the evidence records its
script digest. The baseline and continuation-start trees were both created with
`git clone --quiet --shared --no-checkout` and detached at the requested baseline. Each tree owns
its target and native/distribution builds. Candidate artifacts seeded the temporary Cargo caches
as ordinary copies; Cargo rebuilt each checkout's crate. One attempt to seed a cache raced Cargo's
intermediate-file cleanup; the copy was repeated after compilation before running those tests.
Raw command logs are under `$TMPDIR/lifecycle-checks` (ephemeral).

Checks touched the shared Gradle cache/daemon, CPU/disk, temporary npm caches and owned loopback
listeners. They started their own children with temporary app data and stopped only those children.
They used no owner apps, dev stack, GPU or CI.

## Remaining acceptance

The complete installed Windows restart proof cannot run on this Linux host. Its cleanup change
has syntax and available suite coverage; a successful full Windows exit remains unverified.
Independent acceptance of the final test-intent digest remains for the orchestrator/verifier.
