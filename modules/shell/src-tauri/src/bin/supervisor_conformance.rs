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
//! B17 additionally routes child ownership, manifest admission, exit observation, state publication
//! and local handoff validation through the same EngineHost methods as ShellActuator. The fake
//! Engine supplies controlled faults; Tauri AppHandle setup and graphical event delivery are not
//! executed by this binary.
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
#[path = "../binding.rs"]
#[allow(dead_code)]
mod binding;
#[path = "../engine_host.rs"]
#[allow(dead_code)]
mod engine_host;
#[path = "../engine_conn.rs"]
mod engine_conn;
#[path = "../engine_probe.rs"]
mod engine_probe;
#[path = "../supervisor.rs"]
#[allow(dead_code)]
mod supervisor;
// Spec F §2.1: the fake engine starts inside its incarnation job exactly as the real Engine does,
// so the containment cases exercise the production spawn with no Tauri link.
#[path = "../containment.rs"]
#[allow(dead_code)]
mod containment;
#[path = "../exit_ledger.rs"]
#[allow(dead_code)]
mod exit_ledger;

#[cfg(target_os = "linux")]
#[path = "../linux_process.rs"]
mod linux_process;
#[path = "../native_targets.rs"]
#[allow(dead_code)]
mod native_targets;
#[path = "../quit.rs"]
mod quit;
#[path = "../session_window.rs"]
mod session_window;
#[path = "../shell_actuator.rs"]
mod shell_actuator;
// Binary-only updater edge injection; the product coordinator is included unchanged.
#[allow(dead_code)]
mod updater_probe {
    include!("../updater.rs");
    include!("support/updater_probe.inc.rs");
}
// AppHandle paths are never driven by the conformance binary. Fail explicitly if used.
#[cfg_attr(test, derive(Default))]
pub struct BackendState {
    host: Arc<engine_host::EngineHost>,
}
impl BackendState {
    fn child_pid(&self) -> Option<u32> {
        self.host.child_pid()
    }
    fn get_port(&self) -> Option<u16> {
        self.host.binding_snapshot().port
    }
    fn get_session_token(&self) -> Option<String> {
        self.host.binding_snapshot().token
    }
    fn has_spawn_error(&self) -> bool {
        self.host.has_spawn_error()
    }
    fn wait_for_child_exit(&self, timeout: Duration) -> bool {
        self.host.wait_for_exit(timeout)
    }
}
fn resolve_app_data_dir(_: &tauri::AppHandle) -> Result<PathBuf, String> {
    Err("AppHandle is outside supervisor conformance".into())
}
fn reconcile_registered_children(_: &std::path::Path) -> Result<(), String> {
    Err("registered-child reconciliation is outside this probe".into())
}
fn start_supervision(_: &tauri::AppHandle, _: Arc<BackendState>) -> Result<(), String> {
    Err("conformance must keep its original actor".into())
}
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use supervisor::{Actuator, Outcome};
fn arg(name: &str) -> Option<String> {
    let args: Vec<String> = std::env::args().collect();
    args.iter()
        .position(|a| a == name)
        .and_then(|i| args.get(i + 1).cloned())
}
fn main() {
    #[cfg(target_os = "linux")]
    linux_process::set_bundle_dir(
        &std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../ui/build/install/ui"),
    );
    let data_dir = PathBuf::from(arg("--data-dir").expect("data dir"));
    let fake_engine = PathBuf::from(arg("--fake-engine").expect("fake engine"));
    let plan = PathBuf::from(arg("--plan").expect("plan"));
    let node = PathBuf::from(arg("--node").unwrap_or("node".into()));
    let run_ms = arg("--run-ms")
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(25000);
    let stop_after = arg("--stop-after-incarnation")
        .and_then(|v| v.parse::<u32>().ok())
        .unwrap_or(0);
    let request = arg("--request-reason").filter(|s| !s.is_empty());
    let host = Arc::new(engine_host::EngineHost::default());
    let coordinator = Arc::new(quit::QuitCoordinator::new(host.clone()));
    let _session =
        session_window::start(host.clone(), coordinator.clone()).expect("session window");
    let spawn_host = host.clone();
    let spawn_dir = data_dir.clone();
    let checkpoint = Arc::new(Mutex::new(None::<supervisor::StateRecord>));
    let saved = checkpoint.clone();
    let event_host = host.clone();
    let actor_thread = Arc::new(Mutex::new(None::<String>));
    let event_actor = actor_thread.clone();
    let mut actuator = shell_actuator::ShellActuator::new(
        host.clone(),
        data_dir.clone(),
        move || {
            spawn_host.reset_for_successor()?;
            let mut command = containment::engine_command(&node).map_err(|e| e.to_string())?;
            command
                .arg(&fake_engine)
                .env("JUSTSEARCH_DATA_DIR", &spawn_dir)
                .env("JUSTSEARCH_API_PORT", "0")
                .env("JUSTSEARCH_FAKE_ENGINE_PLAN", &plan)
                .stdout(Stdio::null())
                .stderr(Stdio::null());
            spawn_host
                .admit(engine_host::PreparedCommand { command })
                .map(|c| c.pid)
        },
        move |record| {
            *event_actor.lock().unwrap() = Some(format!("{:?}", std::thread::current().id()));
            if stop_after > 0
                && record.state == "running"
                && record.incarnation >= stop_after
                && saved.lock().unwrap().is_none()
            {
                *saved.lock().unwrap() = Some(record.clone());
                let _ = event_host.post_stop(
                    "quit",
                    None,
                    Duration::from_millis(supervisor::load_policy().quit_deadline_ms),
                );
            }
        },
    );
    let control_host = host.clone();
    let control_dir = data_dir.clone();
    let control_actor = actor_thread.clone();
    let trigger_coordinator = coordinator.clone();
    let timer = std::thread::spawn(move || {
        let started = Instant::now();
        let mut request = request;
        loop {
            if control_host.terminal_outcome().is_some() {
                break;
            }
            let state = control_host.latest_record();
            if started.elapsed().as_millis() as u64 >= run_ms {
                let _ = control_host.post_stop(
                    "quit",
                    None,
                    Duration::from_millis(supervisor::load_policy().quit_deadline_ms),
                );
            }
            if state.as_ref().is_some_and(|r| r.state == "running")
                && started.elapsed() >= Duration::from_millis(500)
            {
                if let Some(reason) = request.take() {
                    let budget = if reason == "quit" {
                        supervisor::load_policy().quit_deadline_ms
                    } else {
                        supervisor::load_policy().graceful_stop_deadline_ms
                    };
                    let _ = control_host.post_stop(&reason, None, Duration::from_millis(budget));
                }
            }
            if let Ok(raw) = std::fs::read_to_string(control_dir.join("shell-control.json")) {
                if let Ok(value) = serde_json::from_str::<serde_json::Value>(&raw) {
                    let _ = std::fs::remove_file(control_dir.join("shell-control.json"));
                    match value["action"].as_str() {
                        Some("session-query") => {
                            trigger_coordinator.begin(quit::QuitCause::SessionEnd {
                                close_app: false,
                            });
                        }
                        Some("session-end") => {
                            let ticket = trigger_coordinator.begin(quit::QuitCause::SessionEnd {
                                close_app: false,
                            });
                            let _ = trigger_coordinator.wait(ticket);
                        }
                        Some("triggers") => {
                            trigger_coordinator.begin(quit::QuitCause::TrayQuit);
                            trigger_coordinator.begin(quit::QuitCause::Drop);
                            trigger_coordinator.begin(quit::QuitCause::WindowExit);
                        }
                        Some("hold") => {
                            let started = Instant::now();
                            let result = control_host.acquire_replacement_hold(Duration::ZERO);
                            write_control_result(
                                &control_dir,
                                &value,
                                serde_json::json!({
                                    "ok": result.is_ok(), "error": result.err(),
                                    "elapsedMs": started.elapsed().as_millis(),
                                    "held": control_host.replacement_held(),
                                    "actorRunning": control_host.supervisor_running(),
                                    "continues": control_host.should_continue(),
                                    "actorThread": control_actor.lock().unwrap().clone(),
                                    "pid": control_host.child_pid()
                                }),
                            );
                        }
                        Some("release") => {
                            let result = control_host.release_replacement_hold();
                            write_control_result(
                                &control_dir,
                                &value,
                                serde_json::json!({
                                    "ok": result.is_ok(), "error": result.err(),
                                    "held": control_host.replacement_held(),
                                    "actorRunning": control_host.supervisor_running()
                                }),
                            );
                        }
                        Some("upgrade") => {
                            let result = control_host.stop_owned_engine(Duration::from_secs(2));
                            write_control_result(
                                &control_dir,
                                &value,
                                serde_json::json!({
                                    "ok": result.is_ok(), "error": result.err(),
                                    "jobEmpty": control_host.predecessor_job_empty(),
                                    "pid": control_host.child_pid()
                                }),
                            );
                        }
                        Some("quit-upgrade") => {
                            let quit = control_host.post_stop("quit", None, Duration::from_secs(2));
                            let upgrade = control_host.post_stop("upgrade", None, Duration::ZERO);
                            let commit =
                                control_host.upgrade_commit("prepare", "nonce", Duration::ZERO);
                            write_control_result(
                                &control_dir,
                                &value,
                                serde_json::json!({
                                    "quitAccepted": quit.is_ok(), "upgradeRefused": upgrade.is_err(),
                                    "commitRefused": commit.is_err()
                                }),
                            );
                        }
                        Some("updater-nonempty")
                        | Some("updater-quit")
                        | Some("updater-prepare-failure") => {
                            let mut result = updater_probe::drive(
                                control_host.clone(),
                                &control_dir,
                                value["action"].as_str().unwrap(),
                            );
                            result["actorThread"] =
                                serde_json::json!(control_actor.lock().unwrap().clone());
                            write_control_result(&control_dir, &value, result);
                        }
                        Some("suspend") => control_host.note_power(supervisor::PowerEvent::Suspend),
                        Some("resume") => control_host.note_power(supervisor::PowerEvent::Resume),
                        _ => {
                            let _ = control_host.post_stop(
                                value["reason"].as_str().unwrap_or("quit"),
                                value["detail"].as_str().map(String::from),
                                Duration::from_millis(
                                    value["budgetMs"]
                                        .as_u64()
                                        .unwrap_or(supervisor::load_policy().quit_deadline_ms),
                                ),
                            );
                        }
                    }
                }
            }
            std::thread::sleep(Duration::from_millis(10));
        }
    });
    let (sent, received) = std::sync::mpsc::channel();
    host.start_supervisor(move || {
        let mut supervisor = supervisor::Supervisor::new(supervisor::load_policy());
        let outcome = supervisor::run_supervision(&mut supervisor, &mut actuator);
        sent.send((outcome, supervisor)).unwrap();
    })
    .expect("one actor");
    let (outcome, supervisor) = received.recv().unwrap();
    let _ = timer.join();
    let records = exit_ledger::read_ledger(
        &exit_ledger::ledger_directory(&data_dir),
        exit_ledger::Family::Engine,
    )
    .unwrap()
    .records;
    let checkpoint = checkpoint.lock().unwrap().clone();
    let last_exit = checkpoint
        .as_ref()
        .and_then(|r| r.last_exit.clone())
        .or(supervisor.last_exit.clone());
    let forced = records
        .iter()
        .any(|r| r["type"] == "engine-exit" && r["forced"] == true);
    let summary = serde_json::json!({"ok":true,"outcome":match outcome {
        Outcome::Stopped {reason,exit_code}=>serde_json::json!({"kind":"stopped","reason":reason,"exitCode":exit_code}),
        Outcome::Exhausted {reason,exit_code}=>serde_json::json!({"kind":"exhausted","reason":reason,"exitCode":exit_code}),
        Outcome::NoEngine=>serde_json::json!({"kind":"stopped","reason":"quit"}) },
        "state":supervisor.state.name(),"checkpoint":checkpoint,"incarnation":supervisor.incarnation,
        "restartCount":supervisor.restart_count,"lastExit":last_exit,"forcedKill":forced,
        "requestsWritten":records.iter().filter(|r|r["type"]=="engine-intent" && r["channel"]!="TERMINATE").map(|r|match r["reason"].as_str(){Some("TIMEOUT")=>"hang",Some("ENGINE_RESTART")=>"restart",Some("UPGRADE")=>"upgrade",_=>"quit"}).collect::<Vec<_>>(),
        "policyProfile":if supervisor.policy.harness_active {"harness"} else {"product"}});
    println!("{summary}");
}

fn write_control_result(
    dir: &std::path::Path,
    request: &serde_json::Value,
    result: serde_json::Value,
) {
    let payload = serde_json::json!({"nonce": request["nonce"], "result": result});
    let path = dir.join("shell-control-result.json");
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, serde_json::to_vec(&payload).unwrap()).unwrap();
    std::fs::rename(tmp, path).unwrap();
}

#[cfg(all(test, target_os = "linux"))]
#[path = "../engine_conn_test_support.rs"]
mod engine_conn_test_support;
