//! Tauri-free production actuator. The graphical shell and conformance driver use this same code.
use crate::{binding, engine_host::EngineHost, engine_probe, exit_ledger, supervisor};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;
use supervisor::{Actuator, Decision, HostQuit, Outcome, Ready, StateRecord};

pub(crate) struct ShellActuator {
    pub host: Arc<EngineHost>,
    pub data_dir: PathBuf,
    spawn: Box<dyn FnMut() -> Result<u32, String> + Send>,
    event: Box<dyn FnMut(&StateRecord) + Send>,
    ledger: exit_ledger::LedgerWriter,
    session: String,
    n: u32,
    pid: Option<u32>,
    intents: Vec<Value>,
    forced: bool,
    #[cfg(target_os = "linux")]
    forced_with_intent: bool,
    terminated_at: Option<u64>,
    started_at: u64,
    process: Value,
    suspended_at_start: u64,
    detail: Option<String>,
    old_holder_recent: bool,
    lock_holder: Option<Value>,
    intervals: Vec<(u64, u64)>,
    applied: std::collections::HashSet<(usize, bool)>,
}
impl ShellActuator {
    pub fn new(
        host: Arc<EngineHost>,
        data_dir: PathBuf,
        spawn: impl FnMut() -> Result<u32, String> + Send + 'static,
        event: impl FnMut(&StateRecord) + Send + 'static,
    ) -> Self {
        let dir = exit_ledger::ledger_directory(&data_dir);
        let now = epoch();
        let mut ledger = exit_ledger::LedgerWriter::new(dir.clone(), exit_ledger::Family::Engine,
            json!({"role":"shell", "pid":std::process::id(), "startedAt":supervisor::epoch_ms_to_iso(now)}), true).expect("shell writer identity");
        let mut open = std::collections::BTreeMap::new();
        if let Ok(read) = exit_ledger::read_ledger(&dir, exit_ledger::Family::Engine) {
            for record in read.records {
                let key = record["incarnation"].to_string();
                match record["type"].as_str() {
                    Some("engine-start") => {
                        open.insert(key, (record, false));
                    }
                    Some("engine-intent") => {
                        if let Some((_, intent)) = open.get_mut(&key) {
                            *intent = true;
                        }
                    }
                    Some("engine-exit" | "engine-owner-died") => {
                        open.remove(&key);
                    }
                    _ => {}
                }
            }
        }
        let mut old_holder_recent = false;
        let mut lock_holder = None;
        for (_, (start, intent)) in open {
            let check = identity_check(&start);
            old_holder_recent |= check == "ALIVE_MATCH"
                || now
                    .saturating_sub(exit_ledger::known_timestamp(&start["at"]).unwrap_or(0) as u64)
                    < supervisor::load_policy().data_dir_locked_wait_ms;
            if check == "ALIVE_MATCH" {
                lock_holder = Some(start.clone());
            }
            let fields = json!({"incarnation":start["incarnation"], "pid":start["pid"],
                "basis":if intent {"OPEN_INTENT"} else {"OPEN_START"}, "osCheck":check,
                "exitClass":if check == "GONE" {"OWNER_DIED"} else {"UNKNOWN"}});
            let _ = ledger.append("engine-owner-died", fields.as_object().unwrap().clone());
        }
        ledger.start_session();
        let session = host.session();
        let intervals = if std::env::var(supervisor::HARNESS_FLAG).as_deref() == Ok("1") {
            std::env::var("JUSTSEARCH_SUPERVISOR_HARNESS_SUSPEND_FILE")
                .ok()
                .and_then(|p| std::fs::read_to_string(p).ok())
                .and_then(|raw| serde_json::from_str::<Vec<Value>>(&raw).ok())
                .unwrap_or_default()
                .into_iter()
                .filter_map(|v| Some((v["fromMs"].as_u64()?, v["toMs"].as_u64()?)))
                .collect()
        } else {
            Vec::new()
        };
        Self {
            host,
            data_dir,
            spawn: Box::new(spawn),
            event: Box::new(event),
            ledger,
            session,
            n: 0,
            pid: None,
            intents: Vec::new(),
            forced: false,
            #[cfg(target_os = "linux")]
            forced_with_intent: false,
            terminated_at: None,
            started_at: 0,
            process: Value::Null,
            suspended_at_start: 0,
            detail: None,
            old_holder_recent,
            lock_holder,
            intervals,
            applied: Default::default(),
        }
    }
    fn append(&mut self, kind: &str, fields: Value) {
        match self
            .ledger
            .append(kind, fields.as_object().unwrap().clone())
        {
            Ok(true) => {}
            other => eprintln!("[exit-ledger] {kind}: {other:?}"),
        }
    }
    fn request_path(&self) -> PathBuf {
        self.data_dir
            .join("runtime")
            .join("shutdown-request.v1.json")
    }
    fn manifest(&self) -> Option<Value> {
        serde_json::from_str(
            &std::fs::read_to_string(self.data_dir.join("runtime").join("manifest.json")).ok()?,
        )
        .ok()
    }
    fn refresh_binding(&self) {
        if let Some(manifest) =
            binding::read_manifest_if_present(&self.data_dir.join("runtime").join("manifest.json"))
        {
            self.host.observe_manifest(&manifest);
        }
    }
    fn incarnation(&self) -> Value {
        json!({"session":self.session,"n":self.n})
    }
    fn wire_incarnation(&self) -> String {
        format!("{}/{}", self.session, self.n)
    }
}
impl Actuator for ShellActuator {
    fn discovery(&mut self) -> Option<Ready> {
        self.refresh_binding();
        let binding = self.host.binding_snapshot();
        binding.port.map(|port| Ready {
            pid: self.host.child_pid(),
            api_port: Some(port),
            instance_id: binding.instance_id,
        })
    }
    fn start_initial(&mut self) -> Result<u32, String> {
        self.spawn_engine()
    }
    fn spawn_engine(&mut self) -> Result<u32, String> {
        let _ = std::fs::remove_file(self.request_path());
        let _ = std::fs::remove_file(self.data_dir.join("runtime").join("manifest.json"));
        let pid = (self.spawn)()?;
        self.n = self.host.incarnation_number();
        self.pid = Some(pid);
        self.intents.clear();
        self.forced = false;
        #[cfg(target_os = "linux")]
        { self.forced_with_intent = false; }
        self.detail = None;
        self.terminated_at = None;
        self.started_at = epoch();
        self.suspended_at_start = self.host.clock.lock().unwrap().suspended_ms_since(0);
        self.process = process_record(pid);
        let mut start = self.process.clone();
        start.as_object_mut().unwrap().extend(json!({"incarnation":self.incarnation(),
            "cause":if self.n == 1 {"INITIAL"} else {"RESTART"}, "supervisorKind":"tauri"}).as_object().unwrap().clone());
        self.append("engine-start", start);
        Ok(pid)
    }
    fn await_ready(&mut self, _deadline: u64) -> Result<Ready, String> {
        self.refresh_binding();
        let binding = self
            .host
            .observe_current_binding(|port| {
                engine_probe::responds(port, "/api/health", Duration::from_millis(100))
            })
            .ok_or("not responsive yet")?;
        Ok(Ready {
            pid: self.host.child_pid(),
            api_port: binding.port,
            instance_id: binding.instance_id,
        })
    }
    fn poll_exit(&mut self) -> Option<i32> {
        self.host.try_reap()
    }
    fn probe_health(&mut self) -> bool {
        let now = self.host.now_ms();
        if self.host.clock.lock().unwrap().grace(now) {
            return true;
        }
        self.host
            .observe_current_binding(|port| {
                engine_probe::responds(port, "/api/health", Duration::from_millis(100))
            })
            .is_some()
    }
    fn probe_essential_ready(&mut self) -> Option<engine_probe::ReadyEpoch> {
        self.host
            .observe_current_value(|port| {
                engine_probe::essential_ready(port, Duration::from_millis(100))
            })
            .map(|(_, epoch)| epoch)
    }
    fn observed_shutdown_reason(&mut self, current: &Ready) -> Option<String> {
        self.host
            .observed_shutdown_reason(&self.manifest()?, current)
    }
    fn take_host_quit(&mut self) -> Option<HostQuit> {
        let quit = self.host.take_host_quit()?;
        self.detail = quit.detail.clone();
        Some(quit)
    }
    fn record_intent(&mut self, reason: &str, detail: Option<&str>, channel: &str, deadline: u64) {
        if let Some(detail) = detail {
            self.detail = Some(detail.into());
        }
        let (reason, default_detail, expected) = match reason {
            "quit" => ("QUIT", None, 0),
            "upgrade" => ("UPGRADE", None, 0),
            "restart" => ("ENGINE_RESTART", None, 4),
            _ => ("TIMEOUT", Some("hang"), 0),
        };
        let channel = if channel == "REQUEST_FILE"
            && self.host.binding_snapshot().port.is_some()
            && reason == "QUIT"
        {
            "HTTP_SHUTDOWN"
        } else if channel == "REQUEST_FILE" && reason == "UPGRADE" {
            "UPDATER_COMMIT"
        } else {
            channel
        };
        let fields = json!({"incarnation":self.incarnation(), "intentId":uuid::Uuid::new_v4().to_string(),
            "reason":reason, "detail":detail.or(self.detail.as_deref()).or(default_detail), "mode":if channel == "TERMINATE" {"NOW"} else {"COOPERATIVE"},
            "channel":if channel == "none" {Value::Null} else {json!(channel)}, "requestedBy":"OWNER",
            "requestedAt":supervisor::epoch_ms_to_iso(epoch()), "deadlineAt":supervisor::epoch_ms_to_iso(deadline), "expectedCode":expected});
        self.intents.push(fields.clone());
        self.append("engine-intent", fields);
    }
    fn write_shutdown_request(&mut self, reason: &str, deadline: u64) -> Result<(), String> {
        let body = json!({"schemaVersion":1,"reason":reason,"detail":self.detail,
            "deadlineAtEpochMs":deadline,"deadlineEpochMs":deadline,
            "incarnation":self.wire_incarnation(),"requestNonce":uuid::Uuid::new_v4().to_string(),"issuedBy":"tauri-shell"});
        if reason == "upgrade" {
            if let Some((preparation, nonce)) = self.host.take_upgrade() {
                let receipt = post_before_send(
                    &self.host,
                    "/api/upgrade/commit-shutdown",
                    &json!({"schemaVersion":1,"preparationId":preparation,"shutdownNonce":nonce}),
                    1000,
                    || !self.host.quit_started(),
                );
                let (mut receipt, sent) = receipt;
                if !sent {
                    receipt = Err(UPGRADE_COMMIT_NOT_SENT.into());
                }
                let result = receipt.as_ref().map(|_| ()).map_err(Clone::clone);
                if receipt.is_err() {
                    self.host.reject_upgrade();
                }
                self.host.set_upgrade_receipt(receipt);
                return result;
            }
        }
        if reason == "quit" {
            if post(&self.host, "/api/lifecycle/shutdown", &body, 200).is_ok() {
                return Ok(());
            }
        }
        write_atomic(&self.request_path(), &format!("{body}\n"))
    }
    fn force_kill(&mut self) {
        self.forced = true;
        self.terminated_at = Some(epoch());
        #[cfg(target_os = "linux")]
        { self.forced_with_intent = !self.intents.is_empty(); }
        let sent = self.host.force_stop();
        #[cfg(target_os = "linux")]
        { self.forced_with_intent &= sent.is_ok(); }
        #[cfg(not(target_os = "linux"))]
        let _ = sent;
    }
    fn record_exit(&mut self, code: i32, phase: &str, decision: &Decision) {
        let mut intents = self.intents.clone();
        if let Ok(read) = exit_ledger::read_ledger(
            &exit_ledger::ledger_directory(&self.data_dir),
            exit_ledger::Family::Engine,
        ) {
            for row in read.records {
                if row["type"] == "engine-self-intent"
                    && row["engine"]["pid"].as_u64() == self.pid.map(u64::from)
                    && exit_ledger::known_timestamp(&row["at"])
                        .is_some_and(|at| at >= self.started_at as i64)
                    && same_engine_identity(&row["engine"], &self.process)
                {
                    intents.insert(0, row);
                }
            }
        }
        let matched = intents
            .iter()
            .rev()
            .find(|i| i["expectedCode"].as_i64() == Some(i64::from(code)));
        let latest = intents.last();
        let requested = matched.is_some()
            || (self.forced && code == exit_ledger::JS_ENGINE_STOP as i32 && latest.is_some());
        #[cfg(target_os = "linux")]
        let requested = if self.host.exit_signal().is_some() {
            // Only our successful forced SIGKILL following an owner intent is requested.
            self.host.exit_signal() == Some(9) && self.forced_with_intent
        } else { requested };
        let intent = matched.or(latest);
        let contained = cfg!(windows) && self.host.latest_record().is_some_and(|r| r.containment.as_deref() != Some("shell_unavailable"));
        #[allow(unused_mut)]
        let mut exit = json!({"incarnation":self.incarnation(), "exitClass":if requested {"REQUESTED"} else {"UNEXPECTED"},
            "reason":intent.map(|i|i["reason"].clone()),"detail":intent.map(|i|i["detail"].clone()), "code":code,
            "codeName":supervisor::describe_exit(code,&supervisor::load_policy()), "exitedAt":null,"observedAt":supervisor::epoch_ms_to_iso(epoch()),
            "forced":self.forced, "terminateCalledAt":self.terminated_at.map(supervisor::epoch_ms_to_iso),"suspendedMs":self.host.clock.lock().unwrap().suspended_ms_since(0).saturating_sub(self.suspended_at_start),
            "phase":phase,"codeDistinctive":!self.forced || contained,"containment":if contained {"SHELL_JOB"} else if cfg!(target_os = "linux") {"LINUX_PARENT_DEATH"} else {"NONE"},"jobEmpty":if contained {json!(self.host.predecessor_job_empty())} else {Value::Null},
            "contract":{"action":decision.action.name(),"exitClass":decision.exit_class,"counted":decision.counted}});
        #[cfg(target_os = "linux")]
        if let Some(signal) = self.host.exit_signal() {
            // A signal is not a numeric program exit code or an OS exit timestamp.
            exit["code"] = Value::Null;
            exit["signal"] = json!(signal);
            exit["signalName"] = json!(linux_signal_name(signal));
            exit["codeName"] = json!(linux_signal_name(signal));
            exit["ownerIntentBeforeSignal"] = if self.forced_with_intent || self.intents.is_empty() {
                json!(self.forced_with_intent)
            } else {
                // An unrelated signal's OS delivery time is unknown; observation order alone
                // cannot prove a pending cooperative intent preceded that signal.
                Value::Null
            };
            exit["codeDistinctive"] = json!(false);
        }
        self.append("engine-exit", exit);
        if code != 2 {
            self.lock_holder = self.pid.map(|_| self.process.clone());
        }
        self.host.acknowledge_exit(code);
    }
    fn wait_for_handle_release(&mut self) -> bool {
        self.host.predecessor_job_empty()
    }
    fn sleep(&mut self, ms: u64) {
        self.host.wait_or_wake(ms);
    }
    fn wait_or_wake(&mut self, ms: u64) {
        self.host.wait_or_wake(ms);
    }
    fn monotonic_ms(&mut self) -> u64 {
        let raw = self.host.raw_ms();
        for (index, (from, to)) in self.intervals.iter().enumerate() {
            for (resume, at) in [(false, *from), (true, *to)] {
                if raw >= at && self.applied.insert((index, resume)) {
                    self.host.clock.lock().unwrap().note(
                        if resume {
                            supervisor::PowerEvent::Resume
                        } else {
                            supervisor::PowerEvent::Suspend
                        },
                        at,
                    );
                }
            }
        }
        self.host.now_ms()
    }
    fn publish_state(&mut self, record: &StateRecord) {
        self.host.publish_state(
            Some(&self.data_dir.join("runtime").join("supervisor.v1.json")),
            record,
            |r| (self.event)(r),
        );
    }
    fn replacement_held(&mut self) -> bool {
        self.host.replacement_held()
    }
    fn incarnation_number(&self) -> u32 {
        self.n
    }
    fn lock_holder_gone(&self) -> bool {
        self.lock_holder
            .as_ref()
            .is_some_and(|record| identity_check(record) == "GONE")
    }
    fn prior_owner_recent(&mut self) -> bool {
        self.old_holder_recent
    }
    fn terminal(&mut self, outcome: &Outcome) {
        self.host.publish_terminal(outcome);
    }
    fn resumed(&mut self) -> bool {
        self.host.clock.lock().unwrap().take_resumed()
    }
    fn suspended_ms_since(&self, t0: u64) -> u64 {
        self.host.clock.lock().unwrap().suspended_ms_since(t0)
    }
    fn note_power(&self, event: supervisor::PowerEvent) {
        self.host.note_power(event);
    }
}

// Only the actor's transport creates this fact, before any write was attempted.
pub(crate) const UPGRADE_COMMIT_NOT_SENT: &str = "Upgrade commit request was not sent";
fn epoch() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
pub(crate) fn write_atomic(path: &Path, bytes: &str) -> Result<(), String> {
    crate::exit_ledger::ensure_owner_only_directory(path.parent().ok_or("no parent")?)
        .map_err(|e| e.to_string())?;
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, bytes).map_err(|e| e.to_string())?;
    std::fs::rename(tmp, path).map_err(|e| e.to_string())
}
#[cfg(not(target_os = "linux"))]
fn filetime_ms(birth: &str) -> Option<u64> {
    (birth.parse::<u64>().ok()? / 10000).checked_sub(11644473600000)
}
#[cfg(windows)]
fn process_identity(pid: u32) -> (Option<String>, Option<String>) {
    use windows_sys::Win32::{
        Foundation::{CloseHandle, FILETIME},
        System::Threading::{
            GetProcessTimes, OpenProcess, QueryFullProcessImageNameW,
            PROCESS_QUERY_LIMITED_INFORMATION,
        },
    };
    unsafe {
        let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if handle.is_null() {
            return (None, None);
        }
        let mut times = [std::mem::zeroed::<FILETIME>(); 4];
        let ok = GetProcessTimes(
            handle,
            &mut times[0],
            &mut times[1],
            &mut times[2],
            &mut times[3],
        );
        let birth = (ok != 0).then(|| {
            ((u64::from(times[0].dwHighDateTime) << 32) | u64::from(times[0].dwLowDateTime))
                .to_string()
        });
        let mut buffer = [0u16; 2048];
        let mut len = buffer.len() as u32;
        let executable = (QueryFullProcessImageNameW(handle, 0, buffer.as_mut_ptr(), &mut len)
            != 0)
            .then(|| String::from_utf16_lossy(&buffer[..len as usize]));
        CloseHandle(handle);
        (birth, executable)
    }
}
#[cfg(not(any(windows, target_os = "linux")))]
fn process_identity(_pid: u32) -> (Option<String>, Option<String>) {
    (None, None)
}
#[cfg(not(target_os = "linux"))]
fn legacy_identity_check(pid: u32, birth: Option<&str>) -> &'static str {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::{
            Foundation::{CloseHandle, GetLastError, ERROR_INVALID_PARAMETER},
            System::Threading::{OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION},
        };
        let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if handle.is_null() && GetLastError() == ERROR_INVALID_PARAMETER {
            return "GONE";
        }
        if !handle.is_null() {
            CloseHandle(handle);
        }
    }
    let (seen, _) = process_identity(pid);
    match (seen.as_deref(), birth) {
        (Some(a), Some(b)) if a == b => "ALIVE_MATCH",
        (Some(_), Some(_)) => "GONE",
        _ => "IDENTITY_UNKNOWN",
    }
}

#[cfg(target_os = "linux")]
fn linux_signal_name(signal: i32) -> String {
    match signal {
        1 => "SIGHUP", 2 => "SIGINT", 3 => "SIGQUIT", 4 => "SIGILL", 6 => "SIGABRT",
        8 => "SIGFPE", 9 => "SIGKILL", 11 => "SIGSEGV", 13 => "SIGPIPE", 14 => "SIGALRM",
        15 => "SIGTERM", _ => return format!("signal {signal}"),
    }.to_string()
}

fn process_record(pid: u32) -> Value {
    #[cfg(target_os = "linux")]
    {
        return match crate::linux_process::capture(pid) {
            Ok((identity, executable)) => json!({"pid":pid, "processIdentity":identity,
                "processStartedAt":null, "executable":executable.to_string_lossy()}),
            Err(_) => json!({"pid":pid, "processIdentity":null, "processStartedAt":null, "executable":null}),
        };
    }
    #[cfg(not(target_os = "linux"))]
    {
        let (birth, executable) = process_identity(pid);
        json!({"pid":pid, "creationFileTime":birth,
            "processStartedAt":birth.as_deref().and_then(filetime_ms).map(supervisor::epoch_ms_to_iso),
            "executable":executable.map(|s|s.chars().take(1024).collect::<String>())})
    }
}

fn same_engine_identity(engine: &Value, owned: &Value) -> bool {
    #[cfg(target_os = "linux")]
    return !owned["processIdentity"].is_null() && engine["processIdentity"] == owned["processIdentity"];
    #[cfg(not(target_os = "linux"))]
    return engine["creationFileTime"].is_null() || engine["creationFileTime"] == owned["creationFileTime"];
}

fn identity_check(record: &Value) -> &'static str {
    let pid = record["pid"].as_u64().and_then(|p| u32::try_from(p).ok()).unwrap_or(0);
    #[cfg(target_os = "linux")]
    {
        let identity: Option<crate::linux_process::Identity> = serde_json::from_value(record["processIdentity"].clone()).ok();
        let state = crate::linux_process::check(pid, identity.as_ref());
        if state != "ALIVE_MATCH" { return state; }
        let Some(executable) = record["executable"].as_str().filter(|exe| Path::new(exe).is_absolute())
            else { return "IDENTITY_UNKNOWN"; };
        match crate::linux_process::capture(pid) {
            Ok((seen, exe)) if Some(&seen) == identity.as_ref() => {
                if exe != Path::new(executable) { eprintln!("Managed child {pid} executable changed: {executable:?} -> {exe:?}; birth identity still matches"); }
                "ALIVE_MATCH"
            },
            Ok(_) => "GONE",
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => "GONE",
            Err(_) => "IDENTITY_UNKNOWN",
        }
    }
    #[cfg(not(target_os = "linux"))]
    legacy_identity_check(pid, record["creationFileTime"].as_str())
}

/// Bounded loopback HTTP from the actor, including during runtime teardown.
pub(crate) fn post(
    host: &EngineHost,
    path: &str,
    body: &Value,
    timeout_ms: u64,
) -> Result<Value, String> {
    post_before_send(host, path, body, timeout_ms, || true).0
}

/// The boolean means a write was attempted, including a partial/failed write.
/// A connect failure cannot deliver a commit; every error after this boundary can.
fn post_before_send(
    host: &EngineHost,
    path: &str,
    body: &Value,
    timeout_ms: u64,
    before_send: impl FnOnce() -> bool,
) -> (Result<Value, String>, bool) {
    let timeout = Duration::from_millis(timeout_ms);
    let (stream, binding) = match host.connect_engine(timeout) {
        Ok(endpoint) => endpoint,
        Err(error) => return (Err(error), false),
    };
    crate::engine_conn::post_connected(
        stream,
        binding.port.unwrap(),
        binding.token.as_deref().unwrap(),
        path,
        body,
        timeout,
        before_send,
    )
}

#[cfg(test)]
#[path = "shell_commit_transport_tests.rs"]
mod commit_transport_tests;

#[cfg(all(test, target_os = "linux"))]
mod linux_identity_tests {
    use super::*;
    fn signalled_exit(with_intent: bool) -> Value {
        let dir = tempfile::tempdir().unwrap();
        let host = Arc::new(EngineHost::default());
        let spawn_host = host.clone();
        let mut actuator = ShellActuator::new(host.clone(), dir.path().into(), move || {
            let mut command = crate::containment::engine_command("sleep").unwrap();
            command.arg("30");
            spawn_host.admit(crate::engine_host::PreparedCommand { command }).map(|c| c.pid)
        }, |_| {});
        actuator.spawn_engine().unwrap();
        if with_intent {
            actuator.record_intent("quit", None, "TERMINATE", epoch() + 1000);
        }
        if with_intent { actuator.force_kill(); }
        else { host.force_stop().unwrap(); }
        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        let code = loop {
            if let Some(code) = actuator.poll_exit() { break code; }
            assert!(std::time::Instant::now() < deadline, "owned fixture did not exit");
            std::thread::sleep(Duration::from_millis(5));
        };
        let decision = Decision { action: supervisor::Action::Stop, reason: None,
            exit_class: None, cooldown_ms: None, counted: false };
        actuator.record_exit(code, "running", &decision);
        exit_ledger::read_ledger(&exit_ledger::ledger_directory(dir.path()), exit_ledger::Family::Engine)
            .unwrap().records.into_iter().find(|r| r["type"] == "engine-exit").unwrap()
    }

    #[test]
    fn linux_signal_has_no_fabricated_exit_code_and_requires_prior_owner_intent() {
        for with_intent in [false, true] {
            let exit = signalled_exit(with_intent);
            assert!(exit["code"].is_null(), "{exit}");
            assert_eq!(exit["signal"], 9);
            assert_eq!(exit["signalName"], "SIGKILL");
            assert_eq!(exit["forced"], with_intent);
            assert_eq!(exit["ownerIntentBeforeSignal"], with_intent);
            assert_eq!(exit["exitClass"], if with_intent { "REQUESTED" } else { "UNEXPECTED" });
            assert!(exit["exitedAt"].is_null());
            assert_eq!(exit["containment"], "LINUX_PARENT_DEATH");
        }
    }

    #[test]
    fn records_use_linux_birth_identity_without_windows_timestamps() {
        let process = process_record(std::process::id());
        assert_eq!(process["processIdentity"]["kind"], "linux-proc-v1");
        assert!(process.get("creationFileTime").is_none());
        assert!(process["processStartedAt"].is_null());
        assert_eq!(identity_check(&process), "ALIVE_MATCH");
        let mut missing_executable = process.clone();
        missing_executable["executable"] = Value::Null;
        assert_eq!(identity_check(&missing_executable), "IDENTITY_UNKNOWN");
        assert!(same_engine_identity(&process, &process));
        let mut changed = process.clone();
        changed["processIdentity"]["startTicks"] = json!("0");
        assert_eq!(identity_check(&changed), "GONE");
        assert!(!same_engine_identity(&changed, &process));
        let legacy = json!({"pid":std::process::id(),"creationFileTime":"123"});
        assert_eq!(identity_check(&legacy), "IDENTITY_UNKNOWN");
        assert!(!same_engine_identity(&legacy, &legacy));
    }
    #[test]
    fn deleted_executable_is_still_the_same_live_process_and_is_terminated() {
        let dir = tempfile::tempdir().unwrap();
        let executable = dir.path().join("replaceable-sleep");
        // Wait for the image to finish startup before unlinking it. A multicall sleep
        // can reject a deleted /proc/self/exe during startup and exit without our signal.
        std::fs::copy("/bin/sh", &executable).unwrap();
        let mut child = std::process::Command::new(&executable)
            .args(["-c", "printf ready; read -r hold"])
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .spawn().unwrap();
        let mut ready = [0; 5];
        let readiness = std::io::Read::read_exact(child.stdout.as_mut().unwrap(), &mut ready);
        if readiness.is_err() {
            let _ = child.kill();
            let _ = child.wait();
        }
        assert!(readiness.is_ok(), "owned shell did not acknowledge startup: {readiness:?}");
        let record = process_record(child.id());
        let identity = serde_json::from_value(record["processIdentity"].clone()).unwrap();
        std::fs::remove_file(&executable).unwrap();
        let observed = crate::linux_process::capture(child.id()).unwrap().1;
        assert!(observed.to_string_lossy().ends_with(" (deleted)"));
        // Always reap our original child, including when a before-change assertion fails.
        let verdict = identity_check(&record);
        let termination = crate::linux_process::terminate(&identity, &executable);
        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        let ended = loop {
            if child.try_wait().unwrap().is_some() { break true; }
            if std::time::Instant::now() >= deadline { break false; }
            std::thread::sleep(Duration::from_millis(10));
        };
        if !ended { child.kill().unwrap(); }
        child.wait().unwrap();
        assert_eq!(verdict, "ALIVE_MATCH", "a deleted image does not end its birth identity");
        termination.unwrap();
        assert!(ended, "identity-matched deleted image must be signalled, not skipped");
    }

}

#[cfg(all(test, target_os = "linux"))]
#[path = "shell_connection_tests.rs"]
mod connection_tests;
