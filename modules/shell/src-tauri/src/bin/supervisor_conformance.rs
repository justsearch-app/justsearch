//! The Tauri supervisor's ACTUATOR half, driven by the conformance harness (lane F stage B item
//! B10; the question is `stages/B.md` Q2 and the answer is (a)).
//!
//! **Why a binary and not a test.** The shell crate's CI lane is `cargo test --lib --locked` and
//! there is no `tests/` directory, so `--lib` sees `#[cfg(test)] mod tests` only. Those tests prove
//! the DECISIONS — `supervisor.rs`'s table, read from the register the Node harness reads. What
//! they cannot prove is that the code around the decisions does what the decision said, and the one
//! thing stage B §2 re-cut for this platform — a cooldown floored on the process handle closing
//! rather than on a number — is invisible to any fake spawner. So this binary runs the SAME
//! `run_supervision` loop `lib.rs` runs, against a real child (the harness's fake engine), and
//! `scripts/supervisor-conformance/adapters/tauri.mjs` judges the artifacts it leaves behind.
//!
//! It includes `supervisor.rs` by path rather than through `shell_lib`, so nothing here links the
//! Tauri runtime — which on Windows cannot be linked into a test-shaped binary at all (see
//! `BackendState::tray_id`'s note in `lib.rs`).
//!
//! Usage (the adapter builds this line; nothing else invokes it):
//!   supervisor-conformance --data-dir <dir> --fake-engine <path to fake-engine.mjs>
//!                          --plan <path to plan.json> [--node <node.exe>] [--run-ms 12000]

// `allow(dead_code)`: this binary includes the whole supervisor module by path and uses the part it
// needs. The unused remainder is what `lib.rs` uses, and warning about it here would be warning
// that two consumers of one module do not consume it identically.
#[path = "../supervisor.rs"]
#[allow(dead_code)]
mod supervisor;
#[path = "../engine_probe.rs"]
mod engine_probe;

use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use supervisor::{
    load_policy, run_supervision, Actuator, Outcome, Ready, StateRecord, Supervisor,
};

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

struct FakeEngineActuator {
    node: PathBuf,
    fake_engine: PathBuf,
    plan: PathBuf,
    data_dir: PathBuf,
    child: Option<Child>,
    api_port: Option<u16>,
    started: Instant,
    run_ms: u64,
    /// Stop once an incarnation at least this high has reached `running`; 0 means "run to a
    /// terminal outcome or the run budget".
    stop_after_incarnation: u32,
    /// What the supervisor published last — the stop condition reads it, so a case ends as soon as
    /// its observable has landed rather than after a fixed wall-clock wait.
    last_state: String,
    last_incarnation: u32,
    forced_kill: bool,
    requests_written: Vec<String>,
}

impl FakeEngineActuator {
    // Joined literally, for the reason `ShellActuator` states: this file is scanned by
    // check-runtime-manifest-closure, and a `runtime_dir()` helper would hide the artifact names
    // from it.
    fn manifest_path(&self) -> PathBuf {
        self.data_dir.join("runtime").join("manifest.json")
    }

    fn request_path(&self) -> PathBuf {
        self.data_dir.join("runtime").join("shutdown-request.v1.json")
    }

    fn state_path(&self) -> PathBuf {
        self.data_dir.join("runtime").join("supervisor.v1.json")
    }

    fn read_manifest(&self) -> Option<serde_json::Value> {
        std::fs::read_to_string(self.manifest_path())
            .ok()
            .and_then(|raw| serde_json::from_str::<serde_json::Value>(&raw).ok())
    }

    fn write_atomic(path: &Path, bytes: &str) -> Result<(), String> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let tmp = path.with_extension("tmp");
        std::fs::write(&tmp, bytes).map_err(|e| e.to_string())?;
        std::fs::rename(&tmp, path).map_err(|e| e.to_string())
    }

}

impl Actuator for FakeEngineActuator {
    fn spawn_engine(&mut self) -> Result<u32, String> {
        // Every incarnation starts from a clean discovery surface: a restarted Engine that inherited
        // the dead one's manifest would be discovered at the dead one's port with the dead one's
        // session token — the residue tempdoc 805 G.1 named on this side of the product.
        let _ = std::fs::remove_file(self.manifest_path());
        let child = Command::new(&self.node)
            .arg(&self.fake_engine)
            .env("JUSTSEARCH_DATA_DIR", &self.data_dir)
            .env("JUSTSEARCH_API_PORT", "0")
            .env("JUSTSEARCH_FAKE_ENGINE_PLAN", &self.plan)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|e| format!("spawn fake engine failed: {e}"))?;
        let pid = child.id();
        self.child = Some(child);
        self.api_port = None;
        Ok(pid)
    }

    fn await_ready(&mut self, deadline_ms: u64) -> Result<Ready, String> {
        loop {
            if let Some(manifest) = self.read_manifest() {
                if let Some(port) = manifest
                    .get("head")
                    .and_then(|h| h.get("apiPort"))
                    .and_then(|p| p.as_u64())
                {
                    let port = port as u16;
                    if engine_probe::responds(port, "/api/health", Duration::from_millis(800)) {
                        self.api_port = Some(port);
                        return Ok(Ready {
                            pid: self.child.as_ref().map(Child::id),
                            api_port: Some(port),
                            instance_id: manifest
                                .get("instanceId")
                                .and_then(|v| v.as_str())
                                .map(|s| s.to_string()),
                        });
                    }
                }
            }
            if now_ms() >= deadline_ms {
                return Err("the incarnation did not publish a port and answer in time".into());
            }
            std::thread::sleep(Duration::from_millis(50));
        }
    }

    fn poll_exit(&mut self) -> Option<i32> {
        let child = self.child.as_mut()?;
        match child.try_wait() {
            Ok(Some(status)) => {
                self.child = None;
                Some(status.code().unwrap_or(1))
            }
            _ => None,
        }
    }

    fn probe_health(&mut self) -> bool {
        match self.api_port {
            // A hang is not a refusal: the socket is accepted and then nothing happens, so the miss
            // is a TIMEOUT and the probe has to have one.
            Some(port) => engine_probe::responds(port, "/api/health", Duration::from_millis(700)),
            None => false,
        }
    }

    fn probe_essential_ready(&mut self) -> bool {
        self.api_port.is_some_and(|port| engine_probe::essential_ready(port, Duration::from_millis(700)))
    }

    fn observed_request_reason(&mut self) -> Option<String> {
        let raw = std::fs::read_to_string(self.request_path()).ok()?;
        let parsed: serde_json::Value = serde_json::from_str(&raw).ok()?;
        let reason = parsed.get("reason")?.as_str()?.to_string();
        // An unknown reason is IGNORED, never guessed at: guessing from a file caught mid-write
        // could stop the product, and the deadline covers the case where ignoring it was wrong.
        matches!(reason.as_str(), "quit" | "restart" | "upgrade" | "hang").then_some(reason)
    }

    fn write_shutdown_request(&mut self, reason: &str, deadline_epoch_ms: u64) -> Result<(), String> {
        self.requests_written.push(reason.to_string());
        let body = serde_json::json!({
            "schemaVersion": 1,
            "reason": reason,
            "deadlineEpochMs": deadline_epoch_ms,
            "issuedBy": "tauri-supervisor-conformance",
        });
        Self::write_atomic(&self.request_path(), &format!("{body}\n"))
    }

    fn force_kill(&mut self) {
        self.forced_kill = true;
        let Some(child) = self.child.as_mut() else { return };
        let pid = child.id();
        #[cfg(windows)]
        {
            // `/T` because the Engine owns children whose handles are the reason the next
            // incarnation's boot can fail; item B12 replaces this with kill-by-identity.
            let _ = Command::new("taskkill")
                .args(["/PID", &pid.to_string(), "/T", "/F"])
                .status();
        }
        #[cfg(not(windows))]
        {
            let _ = pid;
        }
        let _ = child.kill();
    }

    fn wait_for_handle_release(&mut self) -> bool {
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let alive = match self.child.as_mut() {
                None => false,
                Some(child) => matches!(child.try_wait(), Ok(None)),
            };
            let log_free = {
                let log = self.data_dir.join("logs").join("engine.log");
                !log.exists()
                    || std::fs::OpenOptions::new().append(true).open(&log).is_ok()
            };
            if !alive && log_free {
                self.child = None;
                return true;
            }
            if Instant::now() >= deadline {
                return false;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
    }

    fn sleep(&mut self, ms: u64) {
        if ms > 0 {
            std::thread::sleep(Duration::from_millis(ms));
        }
    }

    fn now_ms(&mut self) -> u64 {
        now_ms()
    }

    fn publish_state(&mut self, record: &StateRecord) {
        self.last_state = record.state.clone();
        self.last_incarnation = record.incarnation;
        let json = serde_json::to_string_pretty(record).unwrap_or_default();
        let _ = Self::write_atomic(&self.state_path(), &format!("{json}\n"));
    }

    fn should_continue(&mut self) -> bool {
        // Stop as soon as THIS case's observable has landed, or when the run budget is spent. The
        // condition is passed in rather than assumed: "a second incarnation reached running" is what
        // every RESTART case asserts, but a case whose whole point is the terminal state has to be
        // allowed to reach it — the first cut stopped `budget-exhausted-after-max-attempts` at
        // incarnation 2 and reported `cancelled` with one restart spent.
        if self.stop_after_incarnation > 0
            && self.last_state == "running"
            && self.last_incarnation >= self.stop_after_incarnation
        {
            return false;
        }
        (self.started.elapsed().as_millis() as u64) < self.run_ms
    }
}

fn arg(name: &str) -> Option<String> {
    let args: Vec<String> = std::env::args().collect();
    args.iter().position(|a| a == name).and_then(|i| args.get(i + 1).cloned())
}

fn main() {
    let data_dir = PathBuf::from(arg("--data-dir").expect("--data-dir is required"));
    let fake_engine = PathBuf::from(arg("--fake-engine").expect("--fake-engine is required"));
    let plan = PathBuf::from(arg("--plan").expect("--plan is required"));
    let node = PathBuf::from(arg("--node").unwrap_or_else(|| "node".to_string()));
    let run_ms = arg("--run-ms").and_then(|v| v.parse::<u64>().ok()).unwrap_or(15_000);
    let stop_after_incarnation = arg("--stop-after-incarnation")
        .and_then(|v| v.parse::<u32>().ok())
        .unwrap_or(0);

    std::fs::create_dir_all(data_dir.join("runtime")).expect("data dir");

    let policy = load_policy();
    let mut sup = Supervisor::new(policy);
    let mut actuator = FakeEngineActuator {
        node,
        fake_engine,
        plan,
        data_dir,
        child: None,
        api_port: None,
        started: Instant::now(),
        run_ms,
        stop_after_incarnation,
        last_state: String::new(),
        last_incarnation: 0,
        forced_kill: false,
        requests_written: Vec::new(),
    };

    // The shell spawns the Engine at setup and the loop picks it up from `await_ready`, so the
    // driver's first incarnation is spawned here for the same reason.
    if let Err(error) = actuator.spawn_engine() {
        println!("{}", serde_json::json!({ "ok": false, "error": error }));
        std::process::exit(1);
    }

    let outcome = run_supervision(&mut sup, &mut actuator);

    let summary = serde_json::json!({
        "ok": true,
        "outcome": match &outcome {
            Outcome::Stopped { reason, exit_code } =>
                serde_json::json!({ "kind": "stopped", "reason": reason, "exitCode": exit_code }),
            Outcome::Exhausted { reason, exit_code } =>
                serde_json::json!({ "kind": "exhausted", "reason": reason, "exitCode": exit_code }),
            Outcome::Cancelled => serde_json::json!({ "kind": "cancelled" }),
        },
        "state": sup.state.name(),
        "incarnation": sup.incarnation,
        "restartCount": sup.restart_count,
        "lastExit": sup.last_exit,
        "forcedKill": actuator.forced_kill,
        "requestsWritten": actuator.requests_written,
        "policyProfile": if sup.policy.harness_active { "harness" } else { "product" },
    });
    println!("{summary}");

    // Never leave a child behind: the harness runs ten of these back to back.
    if actuator.child.is_some() {
        actuator.force_kill();
    }
}
