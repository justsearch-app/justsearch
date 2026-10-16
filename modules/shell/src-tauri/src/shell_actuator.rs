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
    terminated_at: Option<u64>,
    started_at: u64,
    creation_time: Option<String>,
    suspended_at_start: u64,
    detail: Option<String>,
    old_holder_recent: bool,
    lock_holder: Option<(u32, Option<String>)>,
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
            let check = identity_check(
                start["pid"].as_u64().unwrap_or(0) as u32,
                start["creationFileTime"].as_str(),
            );
            old_holder_recent |= check == "ALIVE_MATCH"
                || now
                    .saturating_sub(exit_ledger::known_timestamp(&start["at"]).unwrap_or(0) as u64)
                    < supervisor::load_policy().data_dir_locked_wait_ms;
            if check == "ALIVE_MATCH" {
                lock_holder = Some((
                    start["pid"].as_u64().unwrap_or(0) as u32,
                    start["creationFileTime"].as_str().map(String::from),
                ));
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
            terminated_at: None,
            started_at: 0,
            creation_time: None,
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
        self.detail = None;
        self.terminated_at = None;
        self.started_at = epoch();
        self.suspended_at_start = self.host.clock.lock().unwrap().suspended_ms_since(0);
        let (birth, executable) = process_identity(pid);
        self.creation_time = birth.clone();
        self.append("engine-start", json!({"incarnation":self.incarnation(), "pid":pid,
            "creationFileTime":birth, "processStartedAt":birth.as_deref().and_then(filetime_ms).map(supervisor::epoch_ms_to_iso),
            "executable":executable.map(|s|s.chars().take(1024).collect::<String>()), "cause":if self.n == 1 {"INITIAL"} else {"RESTART"}, "supervisorKind":"tauri"}));
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
                let binding = self.host.binding_snapshot();
                let receipt = match (binding.port, binding.token) {
                    (Some(port), Some(token)) => post_before_send(
                        port,
                        &token,
                        "/api/upgrade/commit-shutdown",
                        &json!({"schemaVersion":1,"preparationId":preparation,"shutdownNonce":nonce}),
                        1000,
                        || self.host.child_pid().is_some() && !self.host.quit_started(),
                    ),
                    _ => (Err("Upgrade binding unavailable".into()), false),
                };
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
            let binding = self.host.binding_snapshot();
            if let (Some(port), Some(token)) = (binding.port, binding.token) {
                if post(port, &token, "/api/lifecycle/shutdown", &body, 200).is_ok() {
                    return Ok(());
                }
            }
        }
        write_atomic(&self.request_path(), &format!("{body}\n"))
    }
    fn force_kill(&mut self) {
        self.forced = true;
        self.terminated_at = Some(epoch());
        let _ = self.host.force_stop();
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
                    && (row["engine"]["creationFileTime"].is_null()
                        || row["engine"]["creationFileTime"].as_str()
                            == self.creation_time.as_deref())
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
        let intent = matched.or(latest);
        let contained = self.host.latest_record().is_some_and(|r| r.containment.as_deref() != Some("shell_unavailable"));
        self.append("engine-exit", json!({"incarnation":self.incarnation(), "exitClass":if requested {"REQUESTED"} else {"UNEXPECTED"},
            "reason":intent.map(|i|i["reason"].clone()),"detail":intent.map(|i|i["detail"].clone()), "code":code,
            "codeName":supervisor::describe_exit(code,&supervisor::load_policy()), "exitedAt":null,"observedAt":supervisor::epoch_ms_to_iso(epoch()),
            "forced":self.forced, "terminateCalledAt":self.terminated_at.map(supervisor::epoch_ms_to_iso),"suspendedMs":self.host.clock.lock().unwrap().suspended_ms_since(0).saturating_sub(self.suspended_at_start),
            "phase":phase,"codeDistinctive":!self.forced || contained,"containment":if contained {"SHELL_JOB"} else {"NONE"},"jobEmpty":if contained {json!(self.host.predecessor_job_empty())} else {Value::Null},
            "contract":{"action":decision.action.name(),"exitClass":decision.exit_class,"counted":decision.counted}}));
        if code != 2 {
            self.lock_holder = self.pid.map(|pid| (pid, self.creation_time.clone()));
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
            .is_some_and(|(pid, birth)| identity_check(*pid, birth.as_deref()) == "GONE")
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
    std::fs::create_dir_all(path.parent().ok_or("no parent")?).map_err(|e| e.to_string())?;
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, bytes).map_err(|e| e.to_string())?;
    std::fs::rename(tmp, path).map_err(|e| e.to_string())
}
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
#[cfg(not(windows))]
fn process_identity(_pid: u32) -> (Option<String>, Option<String>) {
    (None, None)
}
fn identity_check(pid: u32, birth: Option<&str>) -> &'static str {
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
/// Bounded loopback HTTP from the actor, including during runtime teardown.
fn post(
    port: u16,
    token: &str,
    path: &str,
    body: &Value,
    timeout_ms: u64,
) -> Result<Value, String> {
    post_before_send(port, token, path, body, timeout_ms, || true).0
}

/// The boolean means a write was attempted, including a partial/failed write.
/// A connect failure cannot deliver a commit; every error after this boundary can.
fn post_before_send(
    port: u16,
    token: &str,
    path: &str,
    body: &Value,
    timeout_ms: u64,
    before_send: impl FnOnce() -> bool,
) -> (Result<Value, String>, bool) {
    use std::io::{Read, Write};
    let mut sent = false;
    let result = (|| {
        if token.contains(['\r', '\n']) {
            return Err("Invalid session token".into());
        }
        let timeout = Duration::from_millis(timeout_ms);
        let mut stream = std::net::TcpStream::connect_timeout(
            &std::net::SocketAddr::from(([127, 0, 0, 1], port)),
            timeout,
        )
        .map_err(|e| e.to_string())?;
        stream
            .set_read_timeout(Some(timeout))
            .map_err(|e| e.to_string())?;
        stream
            .set_write_timeout(Some(timeout))
            .map_err(|e| e.to_string())?;
        let body = body.to_string();
        if !before_send() {
            return Err("Engine exited or quit before upgrade commit request".into());
        }
        sent = true;
        write!(stream,"POST {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nX-JustSearch-Session: {token}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).map_err(|e|e.to_string())?;
        let mut response = String::new();
        stream
            .read_to_string(&mut response)
            .map_err(|e| e.to_string())?;
        let (headers, body) = response
            .split_once("\r\n\r\n")
            .ok_or("Malformed HTTP response")?;
        if !headers
            .lines()
            .next()
            .is_some_and(|line| line.contains(" 2"))
        {
            return Err("Shutdown rejected".into());
        }
        serde_json::from_str(body).or_else(|_| Ok(json!({"accepted":true})))
    })();
    (result, sent)
}

#[cfg(test)]
#[path = "shell_commit_transport_tests.rs"]
mod commit_transport_tests;
