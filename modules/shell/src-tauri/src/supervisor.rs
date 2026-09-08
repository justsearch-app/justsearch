//! The Engine supervisor's decision seam, Rust half (design 7.1, lane F stage B item B10).
//!
//! Two implementations of one contract: this file and `scripts/dev/lib/engine-supervisor.cjs`.
//! Neither copies the numbers or the cases — both READ
//! `governance/supervision-contract.v1.json`'s `engine` row, which is the authority precisely
//! because the supervisor is deliberately not a JVM (7.1's `never` clause) and so has no Java
//! record to be a projection of. Here the register is embedded at compile time with
//! `include_str!`, so the packaged binary carries the same bytes the harness read.
//!
//! **Why the file is shaped like this, and not like `lib.rs`.** The CI lane for this crate is
//! `cargo test --lib --locked` and there is no `tests/` directory, so `--lib` means
//! `#[cfg(test)] mod tests` only. A supervisor written as a method on `BackendState` would be
//! untestable in that lane — it would need a Tauri runtime, which on Windows fails to link into a
//! test binary at all (see `BackendState::tray_id`'s note). So the supervisor is split:
//!
//!   * [`decide`] — a pure function of an observation and a policy. No clock, no filesystem, no
//!     process. Its whole behaviour is the table in the register, and the tests at the bottom of
//!     this file run that table.
//!   * [`Actuator`] — the trait for everything `decide` cannot do: spawn, wait for the handle to
//!     close, sleep a cooldown, write the request file, kill, publish the state. `lib.rs`
//!     implements it over the real child; `src/bin/supervisor_conformance.rs` implements it over
//!     the harness's fake engine, which is how the actuator half is exercised at all.
//!
//! The split is not a testing convenience — it is the reason "one contract, two implementations"
//! is checkable. A fake process spawner inside `#[cfg(test)]` would have proven the decisions and
//! nothing else, and the one thing stage B §2 re-cut for Windows — a cooldown floored on the
//! process handle closing rather than on a number — is exactly what a fake spawner cannot show.

use serde::{Deserialize, Serialize};

/// The supervision register, embedded at compile time. The path is relative to THIS file:
/// `src -> src-tauri -> shell -> modules -> <repo root>`.
const SUPERVISION_REGISTER: &str = include_str!("../../../../governance/supervision-contract.v1.json");

/// The env var that turns the per-run policy overrides on.
///
/// Setting an override WITHOUT this is inert, by construction rather than by convention: a shell
/// launched from an environment that happens to carry a leftover
/// `JUSTSEARCH_SUPERVISOR_STABILITY_WINDOW_MS=1500` must not quietly ship a 1.5 s stability window
/// to a user's machine. `overrides_are_inert_without_the_harness_flag` is the test.
pub const HARNESS_FLAG: &str = "JUSTSEARCH_SUPERVISOR_HARNESS";

/// The supervisor's states (design 7.1). Serialised in kebab-case to match the state file the
/// dev-runner writes — one file shape, two writers.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum State {
    Starting,
    Running,
    Stopping,
    Restarting,
    Exhausted,
}

impl State {
    pub fn name(self) -> &'static str {
        match self {
            State::Starting => "starting",
            State::Running => "running",
            State::Stopping => "stopping",
            State::Restarting => "restarting",
            State::Exhausted => "exhausted",
        }
    }

    /// Read back from a state file or a register case. Used by the decision table and by any
    /// consumer that reads the file the other supervisor wrote.
    #[allow(dead_code)]
    pub fn from_name(name: &str) -> Option<State> {
        match name {
            "starting" => Some(State::Starting),
            "running" => Some(State::Running),
            "stopping" => Some(State::Stopping),
            "restarting" => Some(State::Restarting),
            "exhausted" => Some(State::Exhausted),
            _ => None,
        }
    }
}

/// What the supervisor observed. One variant per edge the register's cases describe.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Event {
    Exit,
    Ready,
    StabilityElapsed,
    HealthMiss,
    StartDeadlineElapsed,
    RequestDeadlineElapsed,
}

impl Event {
    /// Read from the register's case list, which names events as strings.
    #[allow(dead_code)]
    pub fn from_name(name: &str) -> Option<Event> {
        match name {
            "exit" => Some(Event::Exit),
            "ready" => Some(Event::Ready),
            "stability-elapsed" => Some(Event::StabilityElapsed),
            "health-miss" => Some(Event::HealthMiss),
            "start-deadline-elapsed" => Some(Event::StartDeadlineElapsed),
            "request-deadline-elapsed" => Some(Event::RequestDeadlineElapsed),
            _ => None,
        }
    }
}

#[derive(Debug, Clone)]
pub struct Observation {
    pub event: Event,
    pub exit_code: Option<i32>,
    pub requested_reason: Option<String>,
    pub consecutive_misses: u32,
    pub restart_count: u32,
    pub state: State,
}

/// What to do about it. The names are the register's `expect.action` strings, and that is
/// deliberate: the case list is the contract, so the enum answers to it rather than the reverse.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    None,
    Stop,
    Restart,
    Exhausted,
    RequestShutdown,
    ForceKill,
    EnterRunning,
    ResetBudget,
}

impl Action {
    /// The register's `expect.action` spelling, which is what the decision table compares against.
    #[allow(dead_code)]
    pub fn name(self) -> &'static str {
        match self {
            Action::None => "none",
            Action::Stop => "stop",
            Action::Restart => "restart",
            Action::Exhausted => "exhausted",
            Action::RequestShutdown => "request-shutdown",
            Action::ForceKill => "force-kill",
            Action::EnterRunning => "enter-running",
            Action::ResetBudget => "reset-budget",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Decision {
    pub action: Action,
    pub reason: Option<String>,
    /// The class the BUDGET used, which is not always the class the integer implies: an Engine
    /// asked to stop for a hang exits with the code of a clean shutdown.
    pub exit_class: Option<String>,
    pub cooldown_ms: Option<u64>,
    pub counted: bool,
}

impl Decision {
    fn plain(action: Action) -> Decision {
        Decision { action, reason: None, exit_class: None, cooldown_ms: None, counted: false }
    }

    fn with_reason(action: Action, reason: &str) -> Decision {
        Decision {
            action,
            reason: Some(reason.to_string()),
            exit_class: None,
            cooldown_ms: None,
            counted: false,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct ExitCodeRow {
    pub code: i32,
    pub name: String,
    pub class: String,
}

/// The Engine supervisor's budget, as the register declares it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Policy {
    pub max_restart_attempts: u32,
    pub cooldown_increment_ms: u64,
    pub max_cooldown_ms: u64,
    pub stability_window_ms: u64,
    pub stability_window_counted_from: String,
    pub start_deadline_ms: u64,
    pub graceful_stop_deadline_ms: u64,
    pub hang_poll_interval_ms: u64,
    pub hang_unhealthy_threshold: u32,
    pub exit_codes: Vec<ExitCodeRow>,
    pub unknown_exit_class: String,
    /// Whether the per-run overrides were in force. Recorded in the state file rather than
    /// inferred: `exhausted` after a 1.5 s stability window is a different fact from `exhausted`
    /// after 300 s, and the file is the only place a later reader can tell which it is reading.
    pub harness_active: bool,
    pub overridden: Vec<String>,
}

fn engine_row() -> serde_json::Value {
    let register: serde_json::Value = serde_json::from_str(SUPERVISION_REGISTER)
        .expect("the embedded supervision register must be valid JSON");
    register
        .get("processes")
        .and_then(|p| p.as_array())
        .and_then(|rows| {
            rows.iter()
                .find(|r| r.get("id").and_then(|v| v.as_str()) == Some("engine"))
                .cloned()
        })
        .expect("the supervision register must carry an `engine` process row")
}

fn env_override(var: &str) -> Option<u64> {
    std::env::var(var).ok().and_then(|raw| raw.trim().parse::<u64>().ok())
}

/// The overridable parameters and their env vars, in the register's `harnessOverrides` order.
/// The restart BUDGET is deliberately absent: a harness that could widen it could hide a
/// supervisor that never reaches `exhausted`.
const OVERRIDES: &[(&str, &str)] = &[
    ("stabilityWindowMs", "JUSTSEARCH_SUPERVISOR_STABILITY_WINDOW_MS"),
    ("maxCooldownMs", "JUSTSEARCH_SUPERVISOR_MAX_COOLDOWN_MS"),
    ("cooldownIncrementMs", "JUSTSEARCH_SUPERVISOR_COOLDOWN_INCREMENT_MS"),
    ("startDeadlineMs", "JUSTSEARCH_SUPERVISOR_START_DEADLINE_MS"),
    ("gracefulStopDeadlineMs", "JUSTSEARCH_SUPERVISOR_GRACEFUL_STOP_DEADLINE_MS"),
    ("hangPollIntervalMs", "JUSTSEARCH_SUPERVISOR_HANG_POLL_INTERVAL_MS"),
    ("hangUnhealthyThreshold", "JUSTSEARCH_SUPERVISOR_HANG_THRESHOLD"),
];

/// Load the policy: the register's numbers, plus the per-run overrides IF AND ONLY IF the harness
/// flag is set in the same environment.
pub fn load_policy() -> Policy {
    let row = engine_row();
    let declared = row.get("policy").expect("engine row must declare a policy block");
    let num = |key: &str| -> u64 {
        declared
            .get(key)
            .and_then(|v| v.as_u64())
            .unwrap_or_else(|| panic!("engine policy is missing `{key}`"))
    };
    let mut policy = Policy {
        max_restart_attempts: num("maxRestartAttempts") as u32,
        cooldown_increment_ms: num("cooldownIncrementMs"),
        max_cooldown_ms: num("maxCooldownMs"),
        stability_window_ms: num("stabilityWindowMs"),
        stability_window_counted_from: declared
            .get("stabilityWindowCountedFrom")
            .and_then(|v| v.as_str())
            .unwrap_or("ready")
            .to_string(),
        start_deadline_ms: num("startDeadlineMs"),
        graceful_stop_deadline_ms: num("gracefulStopDeadlineMs"),
        hang_poll_interval_ms: num("hangPollIntervalMs"),
        hang_unhealthy_threshold: num("hangUnhealthyThreshold") as u32,
        exit_codes: serde_json::from_value(row.get("exitCodes").cloned().unwrap_or_default())
            .expect("engine row must declare an exitCodes table"),
        unknown_exit_class: row
            .get("unknownExitClass")
            .and_then(|v| v.as_str())
            .unwrap_or("TRANSIENT")
            .to_string(),
        harness_active: false,
        overridden: Vec::new(),
    };
    if std::env::var(HARNESS_FLAG).ok().as_deref() != Some("1") {
        return policy;
    }
    policy.harness_active = true;
    for (key, var) in OVERRIDES {
        let Some(value) = env_override(var) else { continue };
        match *key {
            "stabilityWindowMs" => policy.stability_window_ms = value,
            "maxCooldownMs" => policy.max_cooldown_ms = value,
            "cooldownIncrementMs" => policy.cooldown_increment_ms = value,
            "startDeadlineMs" => policy.start_deadline_ms = value,
            "gracefulStopDeadlineMs" => policy.graceful_stop_deadline_ms = value,
            "hangPollIntervalMs" => policy.hang_poll_interval_ms = value,
            "hangUnhealthyThreshold" => policy.hang_unhealthy_threshold = value as u32,
            _ => continue,
        }
        policy.overridden.push((*key).to_string());
    }
    policy
}

/// The class the register declares for `code`, falling back to `unknownExitClass`.
pub fn classify_exit(code: i32, policy: &Policy) -> String {
    policy
        .exit_codes
        .iter()
        .find(|row| row.code == code)
        .map(|row| row.class.clone())
        .unwrap_or_else(|| policy.unknown_exit_class.clone())
}

/// The short reason label — the same rendering `EngineExit.describe` produces, so a state file
/// written by either supervisor names a death the same way. Unknown codes keep their number.
pub fn describe_exit(code: i32, policy: &Policy) -> String {
    policy
        .exit_codes
        .iter()
        .find(|row| row.code == code)
        .map(|row| row.name.to_lowercase())
        .unwrap_or_else(|| format!("unknown({code})"))
}

fn decide_on_exit(observation: &Observation, policy: &Policy) -> Decision {
    let requested = observation.requested_reason.as_deref();
    let code = observation.exit_code.unwrap_or(0);

    // A shutdown the supervisor asked for is classified by the ASK, not by the integer. This is
    // 627 U3's unresolved caveat closed by construction: one budget, one classifier, and the
    // requested path cannot double-spend it because it does not spend it at all.
    match requested {
        Some("restart") => {
            // No cooldown ramp: nothing crashed. The actuator still waits for the process handle
            // to close, which is the floor under EVERY restart and is not a number this function
            // can express.
            return Decision {
                action: Action::Restart,
                reason: Some("restart".to_string()),
                exit_class: Some("REQUESTED".to_string()),
                cooldown_ms: Some(0),
                counted: false,
            };
        }
        Some("upgrade") => {
            return Decision {
                exit_class: Some("REQUESTED".to_string()),
                ..Decision::with_reason(Action::Stop, "upgrade")
            }
        }
        Some("quit") => {
            return Decision {
                exit_class: Some("REQUESTED".to_string()),
                ..Decision::with_reason(Action::Stop, "quit")
            }
        }
        _ => {}
    }

    // `hang` is the one requested reason that IS counted: the request was the recovery, and the
    // thing being recovered from was a fault. Treating it as requested-and-free would give an
    // Engine that hangs every thirty seconds an unbounded number of restarts.
    let hang = requested == Some("hang");
    let exit_class = if hang { "TRANSIENT".to_string() } else { classify_exit(code, policy) };
    let reason = if hang { "hang".to_string() } else { describe_exit(code, policy) };

    if exit_class == "REQUESTED" {
        // Exit 0 with nothing outstanding: the ordered shutdown ran because something else asked
        // for it (the HTTP trigger, a signal). Restarting here would fight the user.
        return Decision {
            action: Action::Stop,
            reason: Some(reason),
            exit_class: Some(exit_class),
            cooldown_ms: None,
            counted: false,
        };
    }
    if exit_class == "NON_TRANSIENT" {
        return Decision {
            action: Action::Exhausted,
            reason: Some(reason),
            exit_class: Some(exit_class),
            cooldown_ms: None,
            counted: false,
        };
    }

    let attempt = observation.restart_count + 1;
    if attempt > policy.max_restart_attempts {
        return Decision {
            action: Action::Exhausted,
            reason: Some(reason),
            exit_class: Some(exit_class),
            cooldown_ms: None,
            counted: false,
        };
    }
    Decision {
        action: Action::Restart,
        reason: Some(reason),
        exit_class: Some(exit_class),
        cooldown_ms: Some(std::cmp::min(
            policy.cooldown_increment_ms.saturating_mul(u64::from(attempt)),
            policy.max_cooldown_ms,
        )),
        counted: true,
    }
}

/// The whole decision. Pure: same inputs, same answer, no clock and no filesystem.
pub fn decide(observation: &Observation, policy: &Policy) -> Decision {
    match observation.event {
        Event::Exit => decide_on_exit(observation, policy),

        // The `starting` -> `running` edge. It is also what arms hang detection and what starts the
        // stability window, which is why it is a decision and not bookkeeping: design 7.1 counts
        // the window from `ready` precisely because a window from spawn is eaten by the boot.
        Event::Ready => {
            if observation.state == State::Starting {
                Decision::plain(Action::EnterRunning)
            } else {
                Decision::plain(Action::None)
            }
        }

        Event::StabilityElapsed => Decision::plain(Action::ResetBudget),

        // Suspended outside `running`: a booting Engine answers nothing for seconds and one running
        // its ordered shutdown stops answering by design. Reading either as a hang would restart a
        // healthy Engine or race a shutdown with a kill.
        Event::HealthMiss => {
            if observation.state != State::Running
                || observation.consecutive_misses < policy.hang_unhealthy_threshold
            {
                Decision::plain(Action::None)
            } else {
                Decision::with_reason(Action::RequestShutdown, "hang")
            }
        }

        Event::StartDeadlineElapsed => {
            if observation.state == State::Starting {
                Decision::with_reason(Action::RequestShutdown, "hang")
            } else {
                Decision::plain(Action::None)
            }
        }

        // The admission the request file makes: it is a request, never a guarantee. A JVM wedged at
        // a safepoint never reads it, and only this ends that.
        Event::RequestDeadlineElapsed => Decision::with_reason(
            Action::ForceKill,
            observation.requested_reason.as_deref().unwrap_or("hang"),
        ),
    }
}

/// The supervisor state file's shape: `<dataDir>/runtime/supervisor.v1.json`.
///
/// One file, two writers (the dev-runner writes the same fields), because the readers — the
/// updater's dead-Engine path, `quick_health`, jseval, the dev MCP — must not have to know which
/// supervisor produced it. Design 7.1 puts it beside the port manifest rather than behind an API
/// for the reason the whole file exists: the Engine is down when it matters.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StateRecord {
    #[serde(rename = "schemaVersion")]
    pub schema_version: u32,
    pub kind: String,
    pub supervisor: String,
    pub state: String,
    pub incarnation: u32,
    pub pid: Option<u32>,
    #[serde(rename = "apiPort")]
    pub api_port: Option<u16>,
    #[serde(rename = "instanceId")]
    pub instance_id: Option<String>,
    #[serde(rename = "restartCount")]
    pub restart_count: u32,
    #[serde(rename = "maxRestartAttempts")]
    pub max_restart_attempts: u32,
    #[serde(rename = "policyProfile")]
    pub policy_profile: String,
    #[serde(rename = "lastExit", skip_serializing_if = "Option::is_none")]
    pub last_exit: Option<LastExit>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    #[serde(rename = "requestedReason", skip_serializing_if = "Option::is_none")]
    pub requested_reason: Option<String>,
    #[serde(rename = "updatedAt")]
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LastExit {
    pub code: i32,
    pub reason: String,
    pub class: String,
    pub counted: bool,
    pub incarnation: u32,
}

/// What an incarnation published once it came up.
#[derive(Debug, Clone, Default)]
pub struct Ready {
    pub pid: Option<u32>,
    pub api_port: Option<u16>,
    pub instance_id: Option<String>,
}

/// How a supervision run ended.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    /// The Engine stopped and nothing should restart it (a clean exit, a requested upgrade).
    Stopped { reason: String, exit_code: i32 },
    /// design 7.1's terminal state.
    Exhausted { reason: String, exit_code: i32 },
    /// The actuator asked to stop supervising (the shell is quitting).
    Cancelled,
}

/// Everything [`decide`] cannot do. `lib.rs` implements it over the real child; the
/// `supervisor-conformance` binary implements it over the harness's fake engine.
///
/// The trait is what makes ONE loop serve both. A conformance binary with a loop of its own would
/// prove a supervisor production does not run, which is the `static-green != live-working` trap
/// wearing a Rust hat.
pub trait Actuator {
    /// Start the next incarnation. Returns its pid.
    fn spawn_engine(&mut self) -> Result<u32, String>;
    /// Wait until this incarnation publishes a port and answers, or the deadline elapses.
    fn await_ready(&mut self, deadline_ms: u64) -> Result<Ready, String>;
    /// The child's exit code, or `None` while it is alive.
    fn poll_exit(&mut self) -> Option<i32>;
    /// A cheap liveness probe (design 7.1: the hang path reads only this). `false` is one miss.
    fn probe_health(&mut self) -> bool;
    /// A request file written by someone who is NOT this supervisor — the Engine escalating for
    /// itself (7.6), or `commit-shutdown` (item B6). Charging those deaths to the crash budget is
    /// the double-accounting 627 U3 left open, arriving from the other side.
    fn observed_request_reason(&mut self) -> Option<String>;
    /// Item B2's out-of-band channel: the Engine's watcher reads this and runs the ordered shutdown.
    fn write_shutdown_request(&mut self, reason: &str, deadline_epoch_ms: u64) -> Result<(), String>;
    /// The deadline expired, so the request was not enough.
    fn force_kill(&mut self);
    /// The cooldown FLOOR: wait until the dead incarnation has let go of its handles. Windows keeps
    /// file handles until the process is gone, and the Engine holds the data-directory lock — a
    /// restart that beats its release exits NON_TRANSIENT for a reason unrelated to the crash.
    fn wait_for_handle_release(&mut self) -> bool;
    /// The linear step above the floor.
    fn sleep(&mut self, ms: u64);
    fn now_ms(&mut self) -> u64;
    /// Publish a transition where a reader outside this process can see it.
    fn publish_state(&mut self, record: &StateRecord);
    /// False when the host is shutting down; the loop returns [`Outcome::Cancelled`].
    fn should_continue(&mut self) -> bool {
        true
    }
}

/// The supervision loop: one implementation, driven by two actuators.
///
/// Deliberately synchronous and deliberately not on the UI thread. `lib.rs` runs it on an OS thread,
/// following the split that file already uses for its drain and manifest-watch threads.
pub fn run_supervision<A: Actuator>(supervisor: &mut Supervisor, actuator: &mut A) -> Outcome {
    const TICK_MS: u64 = 50;

    let mut ready_at: Option<u64> = None;
    let mut request_deadline: Option<u64> = None;
    let mut next_health_poll: u64 = 0;
    let mut current = Ready::default();
    // An exit observed while WAITING for an incarnation to come up. `poll_exit` takes the code, so
    // it has to be carried to the loop rather than re-read there. Without this the start deadline
    // fires on an incarnation that is already dead, and the resulting `hang` request rewrites a
    // NON_TRANSIENT death as a counted transient one — the failure that showed up as
    // `non-transient-2-exhausts-at-once` spending the whole budget.
    let mut pending_exit: Option<i32> = None;

    macro_rules! publish {
        ($reason:expr) => {{
            let now = actuator.now_ms();
            let record = supervisor.record(
                current.pid,
                current.api_port,
                current.instance_id.clone(),
                $reason,
                epoch_ms_to_iso(now),
            );
            actuator.publish_state(&record);
        }};
    }

    // The first incarnation: `lib.rs` has already spawned it (the shell spawns the Engine at setup),
    // so `await_ready` is the entry point rather than `spawn_engine`.
    publish!(None);
    let start_deadline = actuator.now_ms() + supervisor.policy.start_deadline_ms;
    match actuator.await_ready(start_deadline) {
        Ok(ready) => {
            current = ready;
            supervisor.observe(Event::Ready, None);
            ready_at = Some(actuator.now_ms());
            next_health_poll = actuator.now_ms() + supervisor.policy.hang_poll_interval_ms;
            publish!(None);
        }
        Err(_) => {
            pending_exit = actuator.poll_exit();
            if pending_exit.is_none() {
                let decision = supervisor.observe(Event::StartDeadlineElapsed, None);
                if decision.action == Action::RequestShutdown {
                    let reason = decision.reason.clone().unwrap_or_else(|| "hang".into());
                    let deadline = actuator.now_ms() + supervisor.policy.graceful_stop_deadline_ms;
                    let _ = actuator.write_shutdown_request(&reason, deadline);
                    request_deadline = Some(deadline);
                    publish!(Some(reason));
                }
            }
        }
    }

    loop {
        if !actuator.should_continue() {
            return Outcome::Cancelled;
        }

        if let Some(code) = pending_exit.take().or_else(|| actuator.poll_exit()) {
            request_deadline = None;
            let decision = supervisor.observe(Event::Exit, Some(code));
            let reason = decision.reason.clone().unwrap_or_default();
            match decision.action {
                Action::Stop => {
                    publish!(Some(reason.clone()));
                    return Outcome::Stopped { reason, exit_code: code };
                }
                Action::Exhausted => {
                    let terminal = format!("ENGINE_RESTART_EXHAUSTED:{reason}");
                    publish!(Some(terminal.clone()));
                    return Outcome::Exhausted { reason: terminal, exit_code: code };
                }
                Action::Restart => {
                    publish!(Some(reason));
                    // Floor first, then the linear step. Both, in that order, every time.
                    actuator.wait_for_handle_release();
                    actuator.sleep(decision.cooldown_ms.unwrap_or(0));
                    supervisor.entered_starting();
                    current = Ready::default();
                    ready_at = None;
                    publish!(None);
                    match actuator.spawn_engine() {
                        Ok(pid) => current.pid = Some(pid),
                        Err(error) => {
                            publish!(Some(format!("restart_failed:{error}")));
                            return Outcome::Exhausted {
                                reason: format!("restart_failed:{error}"),
                                exit_code: 1,
                            };
                        }
                    }
                    let deadline = actuator.now_ms() + supervisor.policy.start_deadline_ms;
                    match actuator.await_ready(deadline) {
                        Ok(ready) => {
                            let pid = current.pid;
                            current = ready;
                            if current.pid.is_none() {
                                current.pid = pid;
                            }
                            supervisor.observe(Event::Ready, None);
                            ready_at = Some(actuator.now_ms());
                            next_health_poll =
                                actuator.now_ms() + supervisor.policy.hang_poll_interval_ms;
                            publish!(None);
                        }
                        Err(_) => {
                            // `starting` has no hang detection, so this deadline is the only thing
                            // that ends the state — and it ends it the way a hang does, because the
                            // two are one problem seen at different times: up, and not serving.
                            // Unless it is not up at all: an incarnation that DIED while starting is
                            // a death, and reading it as a hang would rewrite its exit class.
                            pending_exit = actuator.poll_exit();
                            let d = if pending_exit.is_some() {
                                Decision::plain(Action::None)
                            } else {
                                supervisor.observe(Event::StartDeadlineElapsed, None)
                            };
                            if d.action == Action::RequestShutdown {
                                let r = d.reason.clone().unwrap_or_else(|| "hang".into());
                                let dl =
                                    actuator.now_ms() + supervisor.policy.graceful_stop_deadline_ms;
                                let _ = actuator.write_shutdown_request(&r, dl);
                                request_deadline = Some(dl);
                                publish!(Some(r));
                            }
                        }
                    }
                }
                _ => {}
            }
            continue;
        }

        let now = actuator.now_ms();

        if let Some(deadline) = request_deadline {
            if now >= deadline {
                request_deadline = None;
                let decision = supervisor.observe(Event::RequestDeadlineElapsed, None);
                if decision.action == Action::ForceKill {
                    actuator.force_kill();
                }
                actuator.sleep(TICK_MS);
                continue;
            }
        }

        if let Some(reason) = actuator.observed_request_reason() {
            if supervisor.requested_reason.as_deref() != Some(reason.as_str()) {
                supervisor.requested_reason = Some(reason.clone());
                supervisor.state = State::Stopping;
                publish!(Some(reason));
            }
        }

        if supervisor.state == State::Running {
            if let Some(at) = ready_at {
                if now.saturating_sub(at) >= supervisor.policy.stability_window_ms
                    && supervisor.restart_count > 0
                {
                    supervisor.observe(Event::StabilityElapsed, None);
                    ready_at = Some(now);
                    publish!(None);
                }
            }
            if now >= next_health_poll {
                next_health_poll = now + supervisor.policy.hang_poll_interval_ms;
                if actuator.probe_health() {
                    supervisor.consecutive_misses = 0;
                } else {
                    supervisor.consecutive_misses += 1;
                    let decision = supervisor.observe(Event::HealthMiss, None);
                    if decision.action == Action::RequestShutdown {
                        let reason = decision.reason.clone().unwrap_or_else(|| "hang".into());
                        let deadline =
                            actuator.now_ms() + supervisor.policy.graceful_stop_deadline_ms;
                        let _ = actuator.write_shutdown_request(&reason, deadline);
                        request_deadline = Some(deadline);
                        publish!(Some(reason));
                    }
                }
            }
        }

        actuator.sleep(TICK_MS);
    }
}

/// Epoch milliseconds as an ISO-8601 UTC instant, so both supervisors' state files carry the same
/// shape of timestamp without pulling a date crate into this crate's dependency set.
pub fn epoch_ms_to_iso(epoch_ms: u64) -> String {
    let secs = (epoch_ms / 1000) as i64;
    let millis = epoch_ms % 1000;
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    let (year, month, day) = civil_from_days(days);
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}.{millis:03}Z",
        rem / 3600,
        (rem % 3600) / 60,
        rem % 60
    )
}

/// Howard Hinnant's `civil_from_days`, the standard days-since-epoch to y/m/d conversion.
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

/// The supervisor's bookkeeping: the state, the budget, and the incarnation counter.
///
/// Deliberately NOT a loop. The loop belongs to whoever owns the child — an OS thread in `lib.rs`,
/// a driver in the conformance binary — and folding one in here would make the type untestable in
/// the `--lib` lane for the second time in the same file.
pub struct Supervisor {
    pub policy: Policy,
    pub state: State,
    pub incarnation: u32,
    pub restart_count: u32,
    pub requested_reason: Option<String>,
    pub consecutive_misses: u32,
    pub last_exit: Option<LastExit>,
}

impl Supervisor {
    pub fn new(policy: Policy) -> Self {
        Supervisor {
            policy,
            state: State::Starting,
            incarnation: 1,
            restart_count: 0,
            requested_reason: None,
            consecutive_misses: 0,
            last_exit: None,
        }
    }

    pub fn observe(&mut self, event: Event, exit_code: Option<i32>) -> Decision {
        let observation = Observation {
            event,
            exit_code,
            requested_reason: self.requested_reason.clone(),
            consecutive_misses: self.consecutive_misses,
            restart_count: self.restart_count,
            state: self.state,
        };
        let decision = decide(&observation, &self.policy);
        if event == Event::Exit {
            let code = exit_code.unwrap_or(0);
            // The record carries the class the BUDGET used, not the class the integer alone
            // implies: an Engine asked to stop for a hang exits with the code of a clean shutdown.
            self.last_exit = Some(LastExit {
                code,
                reason: decision.reason.clone().unwrap_or_else(|| describe_exit(code, &self.policy)),
                class: decision
                    .exit_class
                    .clone()
                    .unwrap_or_else(|| classify_exit(code, &self.policy)),
                counted: decision.counted,
                incarnation: self.incarnation,
            });
            if decision.counted {
                self.restart_count += 1;
            }
        }
        match decision.action {
            Action::EnterRunning => {
                self.state = State::Running;
                self.consecutive_misses = 0;
            }
            Action::ResetBudget => self.restart_count = 0,
            Action::RequestShutdown => {
                self.state = State::Stopping;
                self.requested_reason = decision.reason.clone();
                self.consecutive_misses = 0;
            }
            Action::Restart => {
                self.state = State::Restarting;
                self.requested_reason = None;
            }
            Action::Exhausted => self.state = State::Exhausted,
            Action::Stop => self.state = State::Stopping,
            Action::None | Action::ForceKill => {}
        }
        decision
    }

    /// Called by the driver after a successful respawn.
    pub fn entered_starting(&mut self) {
        self.incarnation += 1;
        self.state = State::Starting;
        self.requested_reason = None;
        self.consecutive_misses = 0;
    }

    pub fn record(&self, pid: Option<u32>, api_port: Option<u16>, instance_id: Option<String>, reason: Option<String>, updated_at: String) -> StateRecord {
        StateRecord {
            schema_version: 1,
            kind: "engine-supervisor-state.v1".to_string(),
            supervisor: "tauri".to_string(),
            state: self.state.name().to_string(),
            incarnation: self.incarnation,
            pid,
            api_port,
            instance_id,
            restart_count: self.restart_count,
            max_restart_attempts: self.policy.max_restart_attempts,
            policy_profile: if self.policy.harness_active { "harness" } else { "product" }.to_string(),
            last_exit: self.last_exit.clone(),
            reason,
            requested_reason: self.requested_reason.clone(),
            updated_at,
        }
    }
}

/// The conformance case list, read from the same register the harness reads.
#[allow(dead_code)]
pub fn conformance_cases() -> Vec<serde_json::Value> {
    engine_row()
        .get("conformanceCases")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The decision half of the contract, driven by the register's OWN case list.
    ///
    /// Not a hand-written table: `scripts/supervisor-conformance/contract.mjs` reads exactly these
    /// rows for the JS half, so "one contract, two implementations" is a fact about one file rather
    /// than about two tables that agree on the day they were written. A case added to the register
    /// runs here with no edit to this file — and a case this implementation gets wrong fails the
    /// build in the lane that already exists.
    #[test]
    fn decision_table_matches_the_register() {
        let base = load_policy();
        let cases = conformance_cases();
        assert!(
            cases.len() >= 10,
            "the register declares {} conformance cases — an empty table is a green lie",
            cases.len()
        );

        let mut failures: Vec<String> = Vec::new();
        let mut ran = 0usize;
        for case in &cases {
            let id = case.get("id").and_then(|v| v.as_str()).unwrap_or("<no id>");
            let drives: Vec<&str> = case
                .get("drives")
                .and_then(|v| v.as_array())
                .map(|a| a.iter().filter_map(|v| v.as_str()).collect())
                .unwrap_or_default();
            if !drives.contains(&"decision") {
                continue;
            }
            ran += 1;

            let mut policy = base.clone();
            if let Some(over) = case.get("policyOverride").and_then(|v| v.as_object()) {
                if let Some(v) = over.get("maxRestartAttempts").and_then(|v| v.as_u64()) {
                    policy.max_restart_attempts = v as u32;
                }
            }

            let obs = case.get("observation").expect("a decision case declares an observation");
            let event = Event::from_name(obs.get("event").and_then(|v| v.as_str()).unwrap_or(""))
                .unwrap_or_else(|| panic!("{id}: unknown event"));
            let observation = Observation {
                event,
                exit_code: obs.get("exitCode").and_then(|v| v.as_i64()).map(|v| v as i32),
                requested_reason: obs
                    .get("requestedReason")
                    .and_then(|v| v.as_str())
                    .map(|s| s.to_string()),
                consecutive_misses: obs
                    .get("consecutiveMisses")
                    .and_then(|v| v.as_u64())
                    .unwrap_or(0) as u32,
                restart_count: obs.get("restartCount").and_then(|v| v.as_u64()).unwrap_or(0) as u32,
                state: obs
                    .get("state")
                    .and_then(|v| v.as_str())
                    .and_then(State::from_name)
                    .unwrap_or(State::Running),
            };

            let decision = decide(&observation, &policy);
            let expect = case.get("expect").expect("a case declares what it expects");

            // Only the keys the case DECLARES are compared. A case that says nothing about
            // `cooldownMs` makes no claim about it, and inventing one here would turn the
            // register's silence into an assertion its author never wrote.
            if let Some(want) = expect.get("action").and_then(|v| v.as_str()) {
                if decision.action.name() != want {
                    failures.push(format!("{id}: action {} != {want}", decision.action.name()));
                }
            }
            if let Some(want) = expect.get("reason").and_then(|v| v.as_str()) {
                if decision.reason.as_deref() != Some(want) {
                    failures.push(format!("{id}: reason {:?} != {want}", decision.reason));
                }
            }
            if let Some(want) = expect.get("cooldownMs").and_then(|v| v.as_u64()) {
                if decision.cooldown_ms != Some(want) {
                    failures.push(format!("{id}: cooldownMs {:?} != {want}", decision.cooldown_ms));
                }
            }
            if let Some(want) = expect.get("counted").and_then(|v| v.as_bool()) {
                if decision.counted != want {
                    failures.push(format!("{id}: counted {} != {want}", decision.counted));
                }
            }
        }
        assert!(ran >= 10, "only {ran} decision cases ran; the filter is broken, not the table");
        assert!(failures.is_empty(), "decision-table failures:\n  {}", failures.join("\n  "));
    }

    /// The register's numbers reach this implementation unaltered. This is the Rust side of the
    /// same drift protection `EngineSupervisionPolicyTest` gives the Java mirror: without it, the
    /// `include_str!` could be reading a field that no longer exists and silently defaulting.
    #[test]
    fn policy_comes_from_the_register() {
        let policy = load_policy();
        let declared = engine_row();
        let block = declared.get("policy").unwrap();
        assert_eq!(policy.max_restart_attempts as u64, block["maxRestartAttempts"].as_u64().unwrap());
        assert_eq!(policy.cooldown_increment_ms, block["cooldownIncrementMs"].as_u64().unwrap());
        assert_eq!(policy.max_cooldown_ms, block["maxCooldownMs"].as_u64().unwrap());
        assert_eq!(policy.stability_window_ms, block["stabilityWindowMs"].as_u64().unwrap());
        assert_eq!(policy.stability_window_counted_from, "ready");
        assert!(!policy.exit_codes.is_empty(), "the exit table must be read, not defaulted");
        assert_eq!(policy.unknown_exit_class, "TRANSIENT");
    }

    /// The adverse precondition for the harness overrides (`green-masked-destructive`): an override
    /// present WITHOUT the flag must be inert. A test that only checked the flag-on direction would
    /// pass just as happily if the flag were ignored entirely, which is the failure that ships a
    /// 1.5 s stability window to a user.
    ///
    /// Serialised with the other env-touching test by `#[cfg(test)]` convention: both mutate the
    /// process environment, so they share one lock rather than racing under the test harness's
    /// thread pool.
    #[test]
    fn overrides_are_inert_without_the_harness_flag() {
        let _guard = ENV_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let declared = load_policy().stability_window_ms;
        // The env lock above serialises every test in this module that touches the process
        // environment; nothing else in this crate reads these variables during tests.
        std::env::remove_var(HARNESS_FLAG);
        std::env::set_var("JUSTSEARCH_SUPERVISOR_STABILITY_WINDOW_MS", "7");
        std::env::set_var("JUSTSEARCH_SUPERVISOR_MAX_COOLDOWN_MS", "7");
        let without = load_policy();
        assert_eq!(
            without.stability_window_ms, declared,
            "an override took effect WITHOUT {HARNESS_FLAG}=1"
        );
        assert!(!without.harness_active);
        assert!(without.overridden.is_empty());

        std::env::set_var(HARNESS_FLAG, "1");
        let with = load_policy();
        assert_eq!(with.stability_window_ms, 7, "the flag did not enable the override");
        assert_eq!(with.max_cooldown_ms, 7);
        assert!(with.harness_active);
        // The budget is never overridable: a harness that could widen it could hide a supervisor
        // that never reaches `exhausted`.
        assert_eq!(with.max_restart_attempts, without.max_restart_attempts);

        std::env::remove_var(HARNESS_FLAG);
        std::env::remove_var("JUSTSEARCH_SUPERVISOR_STABILITY_WINDOW_MS");
        std::env::remove_var("JUSTSEARCH_SUPERVISOR_MAX_COOLDOWN_MS");
    }

    static ENV_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    /// The budget is spent, then the terminal state is reached — and the count is what stops the
    /// loop, not the cooldown (§2). Driven through `Supervisor` rather than `decide` so the
    /// bookkeeping (which is where an off-by-one would live) is exercised too.
    #[test]
    fn the_budget_is_spent_exactly_once_per_counted_death() {
        let policy = load_policy();
        let max = policy.max_restart_attempts;
        let mut supervisor = Supervisor::new(policy);
        supervisor.observe(Event::Ready, None);
        assert_eq!(supervisor.state, State::Running);

        for attempt in 1..=max {
            let decision = supervisor.observe(Event::Exit, Some(1));
            assert_eq!(decision.action, Action::Restart, "attempt {attempt} should restart");
            assert!(decision.counted);
            assert_eq!(supervisor.restart_count, attempt);
            supervisor.entered_starting();
            supervisor.observe(Event::Ready, None);
        }
        let final_decision = supervisor.observe(Event::Exit, Some(1));
        assert_eq!(final_decision.action, Action::Exhausted);
        assert_eq!(supervisor.state, State::Exhausted);
        assert_eq!(
            supervisor.restart_count, max,
            "the terminal death must not also be charged — it is the one the budget refused"
        );
    }

    /// A requested restart does not spend the budget, and the state record says so. The direction
    /// that makes this non-vacuous is the second half: an unrequested exit with the same code DOES
    /// spend it.
    #[test]
    fn a_requested_restart_is_free_and_an_unrequested_one_is_not() {
        let mut supervisor = Supervisor::new(load_policy());
        supervisor.observe(Event::Ready, None);
        supervisor.requested_reason = Some("restart".to_string());
        let requested = supervisor.observe(Event::Exit, Some(0));
        assert_eq!(requested.action, Action::Restart);
        assert!(!requested.counted);
        assert_eq!(supervisor.restart_count, 0);

        supervisor.entered_starting();
        supervisor.observe(Event::Ready, None);
        let crashed = supervisor.observe(Event::Exit, Some(1));
        assert_eq!(crashed.action, Action::Restart);
        assert!(crashed.counted);
        assert_eq!(supervisor.restart_count, 1);
    }

    /// A hang recovery exits 0 — the code of a clean shutdown — and must still be charged. The
    /// state record has to say TRANSIENT here, not REQUESTED, or a reader is told the death was
    /// free when it was not.
    #[test]
    fn a_hang_recovery_exits_zero_and_is_still_charged() {
        let mut supervisor = Supervisor::new(load_policy());
        supervisor.observe(Event::Ready, None);
        supervisor.requested_reason = Some("hang".to_string());
        let decision = supervisor.observe(Event::Exit, Some(0));
        assert_eq!(decision.action, Action::Restart);
        assert!(decision.counted);
        assert_eq!(decision.exit_class.as_deref(), Some("TRANSIENT"));
        let last = supervisor.last_exit.clone().unwrap();
        assert_eq!(last.code, 0);
        assert_eq!(last.class, "TRANSIENT");
        assert_eq!(last.reason, "hang");
        assert!(last.counted);
    }

    /// The state file's shape is one contract with two writers, so the field names are pinned here
    /// rather than left to serde's defaults. A rename would make the dev-runner's file and this
    /// one two formats sharing a name.
    #[test]
    fn the_state_record_serialises_with_the_shared_field_names() {
        let mut supervisor = Supervisor::new(load_policy());
        supervisor.observe(Event::Ready, None);
        let record = supervisor.record(Some(42), Some(1234), Some("abc".into()), None, "T".into());
        let json = serde_json::to_value(&record).unwrap();
        assert_eq!(json["schemaVersion"], 1);
        assert_eq!(json["kind"], "engine-supervisor-state.v1");
        assert_eq!(json["supervisor"], "tauri");
        assert_eq!(json["state"], "running");
        assert_eq!(json["apiPort"], 1234);
        assert_eq!(json["instanceId"], "abc");
        assert_eq!(json["restartCount"], 0);
        assert_eq!(json["maxRestartAttempts"], 3);
        assert_eq!(json["policyProfile"], "product");
        assert_eq!(json["updatedAt"], "T");
    }
}
