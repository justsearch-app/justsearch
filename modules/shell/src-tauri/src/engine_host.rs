//! Tauri-free ownership core for the Engine child and its host lifetime.

use crate::binding::{apply_manifest_observation, Binding, BindingChange, ManifestFields};
use crate::containment::{self, IncarnationJob};
use crate::supervisor::{self, StateRecord};
use std::path::Path;
use std::process::{Child, ChildStderr, ChildStdout, Command};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

#[derive(Debug, Clone, PartialEq, Eq)]
enum Discovery {
    AwaitingSuccessor,
    AwaitingManifest {
        pid: u32,
    },
    Bound {
        pid: u32,
        instance_id: String,
    },
}

struct OwnedChild {
    child: Child,
    generation: u64,
    /// The incarnation's job (Spec F §2.1). `None` only when the job could not be created and the
    /// Engine was started uncontained.
    job: Option<IncarnationJob>,
}

/// State-file value while the current incarnation runs without a shell job (Spec F §2.1).
pub(crate) const SHELL_CONTAINMENT_UNAVAILABLE: &str = "shell_unavailable";

struct HostInner {
    child: Option<OwnedChild>,
    /// Why the current incarnation could not be put in a job; `None` when it was.
    shell_containment_error: Option<String>,
    predecessor_job: Option<IncarnationJob>,
    predecessor_empty: Option<containment::JobEmpty>,
    closing: bool,
    replacement_held: bool,
    next_generation: u64,
    binding: Binding,
    discovery: Discovery,
    spawn_error: Option<String>,
    latest_record: Option<StateRecord>,
    #[cfg(target_os = "linux")]
    exit_signal: Option<i32>,
    last_tooltip: Option<String>,
}

impl Default for HostInner {
    fn default() -> Self {
        Self {
            child: None,
            shell_containment_error: None,
            predecessor_job: None,
            predecessor_empty: None,
            closing: false,
            replacement_held: false,
            next_generation: 1,
            binding: Binding::default(),
            discovery: Discovery::AwaitingSuccessor,
            spawn_error: None,
            latest_record: None,
            #[cfg(target_os = "linux")]
            exit_signal: None,
            last_tooltip: None,
        }
    }
}

#[derive(Default)]
struct WatcherState {
    started: bool,
    cancelled: bool,
    handle: Option<JoinHandle<()>>,
}

/// A fully prepared launch. `admit` performs only the close check, spawn, pipe extraction and
/// installation, keeping the close/spawn linearization point small and explicit.
pub(crate) struct PreparedCommand {
    pub(crate) command: Command,
}

#[derive(Debug)]
pub(crate) struct AdmittedChild {
    pub(crate) pid: u32,
    pub(crate) generation: u64,
    pub(crate) stdout: Option<ChildStdout>,
    pub(crate) stderr: Option<ChildStderr>,
}

pub(crate) struct EngineHost {
    inner: Mutex<HostInner>,
    watcher: Arc<(Mutex<WatcherState>, Condvar)>,
    supervisor: Mutex<Option<JoinHandle<()>>>,
    control: Mutex<Control>,
    wake: Condvar,
    exit_observed: (Mutex<Option<i32>>, Condvar),
    terminal: (Mutex<Option<supervisor::Outcome>>, Condvar),
    pub(crate) clock: Mutex<supervisor::SuspendAwareClock>,
    origin: Instant,
}

#[derive(Default)]
struct Control {
    manual_restart: bool,
    pending: Option<supervisor::HostQuit>,
    active: Option<String>,
    upgrade: Option<(String, String)>,
    receipt: Option<Result<serde_json::Value, String>>,
}

impl Default for EngineHost {
    fn default() -> Self {
        Self {
            inner: Mutex::new(HostInner::default()),
            watcher: Arc::new((Mutex::new(WatcherState::default()), Condvar::new())),
            supervisor: Mutex::new(None),
            control: Mutex::new(Control::default()),
            wake: Condvar::new(),
            exit_observed: (Mutex::new(None), Condvar::new()),
            terminal: (Mutex::new(None), Condvar::new()),
            clock: Mutex::new(supervisor::SuspendAwareClock::default()),
            origin: Instant::now(),
        }
    }
}

impl EngineHost {
    pub(crate) fn admit(&self, mut prepared: PreparedCommand) -> Result<AdmittedChild, String> {
        self.admit_with(&mut prepared, || {})
    }

    fn admit_with(
        &self,
        prepared: &mut PreparedCommand,
        before_spawn: impl FnOnce(),
    ) -> Result<AdmittedChild, String> {
        self.admit_with_job(prepared, before_spawn, IncarnationJob::create)
    }

    fn admit_with_job(
        &self,
        prepared: &mut PreparedCommand,
        before_spawn: impl FnOnce(),
        create_job: impl FnOnce(u64) -> Result<IncarnationJob, containment::ContainmentError>,
    ) -> Result<AdmittedChild, String> {
        let mut inner = self.inner.lock().expect("engine host mutex poisoned");
        if inner.closing || self.control.lock().unwrap().active.as_deref() == Some("quit") {
            return Err("Engine host is closing; launch refused".into());
        }
        if inner.replacement_held {
            return Err("Engine replacement is held by the updater; launch refused".into());
        }
        if inner.child.is_some() {
            return Err("Engine child is already installed".into());
        }
        before_spawn();
        let generation = inner.next_generation;
        prepared.command.env(
            "JUSTSEARCH_ENGINE_INCARNATION",
            format!("{}/{generation}", self.session()),
        );
        // One job per incarnation, created by the spawn (Spec F §2.1). If Windows will not give us
        // a job, the Engine still starts, uncontained, and the state file says so.
        let (mut job, mut containment_error) = match create_job(generation) {
            Ok(job) => (Some(job), None),
            Err(containment::ContainmentError::Unsupported) => (None, None),
            Err(error) => (None, Some(error.to_string())),
        };
        let spawned = match &job {
            Some(job) => job.spawn_contained(&mut prepared.command),
            None => containment::spawn_uncontained(&mut prepared.command)
                .map_err(containment::ContainmentError::Spawn),
        };
        let mut child = match spawned {
            Ok(child) => child,
            Err(
                error @ (containment::ContainmentError::AssignFailed(_)
                | containment::ContainmentError::ResumeFailed(_)),
            ) => {
                // The suspended candidate was terminated and reaped by spawn_contained.
                // Retry plainly; the manifest sweeps remain the fallback until step 13.
                containment_error = Some(error.to_string());
                drop(job.take());
                containment::spawn_uncontained(&mut prepared.command)
                    .map_err(|e| format!("spawn java failed: {e}"))?
            }
            Err(error) => return Err(format!("spawn java failed: {error}")),
        };
        *self.exit_observed.0.lock().unwrap() = None;
        #[cfg(target_os = "linux")]
        {
            inner.exit_signal = None;
        }
        let pid = child.id();
        let stdout = child.stdout.take();
        let stderr = child.stderr.take();
        inner.next_generation = inner.next_generation.saturating_add(1);
        if let Some(error) = &containment_error {
            eprintln!(
                "[containment] Engine incarnation {generation} runs without a shell job: {error}"
            );
        }
        inner.shell_containment_error = containment_error;
        inner.discovery = Discovery::AwaitingManifest { pid };
        inner.spawn_error = None;
        inner.predecessor_job.take();
        inner.predecessor_empty = None;
        inner.child = Some(OwnedChild {
            child,
            generation,
            job,
        });
        Ok(AdmittedChild {
            pid,
            generation,
            stdout,
            stderr,
        })
    }

    pub(crate) fn reset_for_successor(&self) -> Result<(), String> {
        let mut inner = self.inner.lock().expect("engine host mutex poisoned");
        if inner.child.is_some() {
            return Err("Cannot reset backend state while Engine is still running".into());
        }
        inner.binding.port = None;
        inner.binding.token = None;
        inner.discovery = Discovery::AwaitingSuccessor;
        inner.spawn_error = None;
        Ok(())
    }

    pub(crate) fn observe_manifest(&self, manifest: &ManifestFields) -> Option<BindingChange> {
        let mut inner = self.inner.lock().expect("engine host mutex poisoned");
        if inner.closing {
            return None;
        }
        let Some(id) = manifest.instance_id.as_deref() else {
            return None;
        };
        let pid = match &inner.discovery {
            Discovery::AwaitingManifest { pid } | Discovery::Bound { pid, .. } => *pid,
            _ => return None,
        };
        let Some(owned) = inner.child.as_ref().filter(|owned| owned.child.id() == pid) else {
            return None;
        };
        // PID reuse and wall-clock changes cannot identify the writer. Require the exact
        // session/generation supplied to this child at admission, even after binding.
        if manifest.pid != Some(pid)
            || manifest.incarnation.as_ref().is_none_or(|(session, n)|
                session != &self.session() || *n != owned.generation)
        {
            return None;
        }
        let change = apply_manifest_observation(&mut inner.binding, manifest, Some(pid));
        if inner.binding.instance_id.as_deref() == Some(id) {
            inner.discovery = Discovery::Bound {
                pid,
                instance_id: id.to_string(),
            };
        }
        Some(change)
    }

    pub(crate) fn observe_manifest_with_sinks(
        &self,
        manifest: &ManifestFields,
        restart_event: impl FnOnce(Option<u16>),
        tooltip_sink: impl FnOnce(&str),
    ) -> Option<BindingChange> {
        let change = self.observe_manifest(manifest);
        if change.as_ref().is_some_and(|change| change.restarted) {
            restart_event(manifest.api_port);
        }
        if change.is_some() {
            let tooltip = match manifest.lifecycle.as_deref() {
                Some(lifecycle) => format!("JustSearch · {lifecycle}"),
                None => "JustSearch".to_string(),
            };
            self.update_tooltip_if_changed(tooltip, tooltip_sink);
        }
        change
    }

    fn update_tooltip_if_changed(&self, tooltip: String, sink: impl FnOnce(&str)) -> bool {
        let mut inner = self.inner.lock().expect("engine host mutex poisoned");
        if inner.last_tooltip.as_deref() == Some(tooltip.as_str()) {
            return false;
        }
        inner.last_tooltip = Some(tooltip.clone());
        drop(inner);
        sink(&tooltip);
        true
    }

    pub(crate) fn binding_snapshot(&self) -> Binding {
        self.inner
            .lock()
            .expect("engine host mutex poisoned")
            .binding
            .clone()
    }

    /// One incarnation supplies the port, token and PID. Recheck the owned child after proof:
    /// on Unix try_wait also detects an exited child whose numeric PID could have been reused.
    /// Once proven, this connected socket cannot be transferred to a new listener on the port.
    pub(crate) fn connect_engine(&self, timeout: Duration) -> Result<(std::net::TcpStream, Binding), String> {
        let (generation, pid, binding) = {
            let inner = self.inner.lock().expect("engine host mutex poisoned");
            let child = inner.child.as_ref()
                .ok_or_else(|| crate::engine_conn::not_proven("no owned Engine"))?;
            if !matches!(&inner.discovery, Discovery::Bound { pid, instance_id }
                if *pid == child.child.id() && Some(instance_id) == inner.binding.instance_id.as_ref())
            {
                return Err(crate::engine_conn::not_proven("Engine binding unavailable"));
            }
            (child.generation, child.child.id(), inner.binding.clone())
        };
        let port = binding.port.ok_or_else(|| crate::engine_conn::not_proven("port unavailable"))?;
        if binding.token.is_none() {
            return Err(crate::engine_conn::not_proven("session token unavailable"));
        }
        let stream = crate::engine_conn::connect_proven(port, pid, timeout)?;
        let mut inner = self.inner.lock().expect("engine host mutex poisoned");
        if inner.binding != binding {
            return Err(crate::engine_conn::not_proven("Engine binding changed"));
        }
        let child = inner.child.as_mut()
            .filter(|child| child.generation == generation)
            .ok_or_else(|| crate::engine_conn::not_proven("Engine incarnation changed"))?;
        if !matches!(child.child.try_wait(), Ok(None)) {
            return Err(crate::engine_conn::not_proven("Engine exited or identity unavailable"));
        }
        Ok((stream, binding))
    }

    /// Attribute an HTTP observation only to the same admitted child and binding throughout it.
    pub(crate) fn observe_current_binding(
        &self,
        probe: impl FnOnce(u16) -> bool,
    ) -> Option<Binding> {
        self.observe_current_value(|port| probe(port).then_some(()))
            .map(|(binding, ())| binding)
    }

    pub(crate) fn observe_current_value<T>(
        &self,
        probe: impl FnOnce(u16) -> Option<T>,
    ) -> Option<(Binding, T)> {
        let (generation, binding) = {
            let inner = self.inner.lock().expect("engine host mutex poisoned");
            let child = inner.child.as_ref()?;
            if inner.closing
                || !matches!(&inner.discovery, Discovery::Bound { pid, instance_id }
                if *pid == child.child.id() && Some(instance_id) == inner.binding.instance_id.as_ref())
            {
                return None;
            }
            (child.generation, inner.binding.clone())
        };
        let value = probe(binding.port?)?;
        let inner = self.inner.lock().expect("engine host mutex poisoned");
        (!inner.closing
            && inner.child.as_ref()?.generation == generation
            && inner.binding == binding)
            .then_some((binding, value))
    }
    /// Admit local shutdown intent only from this host's current child and manifest binding.
    pub(crate) fn observed_shutdown_reason(
        &self,
        manifest: &serde_json::Value,
        current: &supervisor::Ready,
    ) -> Option<String> {
        let reason = supervisor::shutdown_handoff_reason(manifest, current)?;
        let binding = self.observe_current_binding(|_| true)?;
        (self.child_pid() == current.pid && binding.instance_id == current.instance_id)
            .then_some(reason)
    }

    pub(crate) fn child_pid(&self) -> Option<u32> {
        self.inner
            .lock()
            .expect("engine host mutex poisoned")
            .child
            .as_ref()
            .map(|c| c.child.id())
    }
    pub(crate) fn has_spawn_error(&self) -> bool {
        self.inner
            .lock()
            .expect("engine host mutex poisoned")
            .spawn_error
            .is_some()
    }
    pub(crate) fn record_spawn_error(&self, error: String) {
        self.inner
            .lock()
            .expect("engine host mutex poisoned")
            .spawn_error = Some(error);
    }
    pub(crate) fn stdout_closed(&self, generation: u64) -> bool {
        let mut inner = self.inner.lock().expect("engine host mutex poisoned");
        if inner.closing
            || inner.binding.port.is_some()
            || inner.child.as_ref().map(|c| c.generation) != Some(generation)
        {
            return false;
        }
        if inner.spawn_error.is_none() {
            inner.spawn_error = Some("Backend process exited before reporting API port".into());
        }
        true
    }
    /// Observe the Engine's exit. An observed exit ends the incarnation's job (Spec F §2.3):
    /// anything still in it after its Engine root exited is an orphan helper by definition, and it
    /// is terminated with `JS_ENGINE_STOP` before this returns.
    pub(crate) fn try_reap(&self) -> Option<i32> {
        let mut inner = self.inner.lock().expect("engine host mutex poisoned");
        let status = inner.child.as_mut()?.child.try_wait().ok()??;
        #[cfg(target_os = "linux")]
        {
            use std::os::unix::process::ExitStatusExt;
            inner.exit_signal = status.signal();
        }
        let owned = inner.child.take().expect("the child was just observed");
        inner.discovery = Discovery::AwaitingSuccessor;
        Self::end_owned(&mut inner, owned);
        // Negative signals are an internal policy observation, never persisted as program codes.
        #[cfg(target_os = "linux")]
        return status.code().or_else(|| {
            use std::os::unix::process::ExitStatusExt;
            status.signal().map(|signal| -signal)
        });
        #[cfg(not(target_os = "linux"))]
        Some(status.code().unwrap_or(1))
    }

    #[cfg(target_os = "linux")]
    pub(crate) fn exit_signal(&self) -> Option<i32> {
        self.inner
            .lock()
            .expect("engine host mutex poisoned")
            .exit_signal
    }

    fn end_owned(inner: &mut HostInner, owned: OwnedChild) -> Child {
        if let Some(job) = owned.job {
            let _ = job.terminate(containment::JS_ENGINE_STOP);
            inner.predecessor_empty =
                Some(job.wait_empty(Duration::from_millis(containment::RESTART_FLOOR_WAIT_MS)));
            // On timeout the unique handle closes now, killing anything terminate left behind.
            // Keep the negative witness so an updater cannot mistake a missing handle for empty.
            inner.predecessor_job = match inner.predecessor_empty {
                Some(containment::JobEmpty::Empty { .. }) => Some(job),
                _ => None,
            };
        }
        owned.child
    }

    /// The updater must positively observe an empty incarnation job before launching.
    pub(crate) fn await_contained_exit(
        &self,
        timeout: Duration,
    ) -> Result<containment::JobEmpty, String> {
        let inner = self.inner.lock().expect("engine host mutex poisoned");
        let job = inner
            .child
            .as_ref()
            .and_then(|owned| owned.job.as_ref())
            .or(inner.predecessor_job.as_ref());
        Ok(match job {
            Some(job) => job.wait_empty(timeout),
            None if matches!(
                inner.predecessor_empty,
                Some(containment::JobEmpty::TimedOut { .. })
            ) =>
            {
                inner
                    .predecessor_empty
                    .expect("matched the negative witness")
            }
            None if inner.child.is_none() => containment::JobEmpty::Empty {
                waited: Duration::ZERO,
            },
            None => return Err("uncontained Engine has not exited".into()),
        })
    }

    pub(crate) fn predecessor_job_empty(&self) -> bool {
        let inner = self.inner.lock().expect("engine host mutex poisoned");
        match inner.predecessor_empty {
            Some(containment::JobEmpty::Empty { .. }) => true,
            Some(containment::JobEmpty::TimedOut { .. }) => false,
            None => inner.child.is_none(),
        }
    }

    /// Force without consuming the exit: poll_exit (and, until step 6, existing waiters) reaps it.
    pub(crate) fn force_stop(&self) -> Result<(), String> {
        let mut inner = self.inner.lock().expect("engine host mutex poisoned");
        if let Some(owned) = inner.child.as_mut() {
            match &owned.job {
                Some(job) => job
                    .terminate(containment::JS_ENGINE_STOP)
                    .map_err(|e| e.to_string()),
                None => {
                    containment::terminate_uncontained(&mut owned.child).map_err(|e| e.to_string())
                }
            }
        } else if let Some(job) = &inner.predecessor_job {
            job.terminate(containment::JS_ENGINE_STOP)
                .map_err(|e| e.to_string())
        } else {
            Ok(())
        }
    }

    pub(crate) fn wait_for_exit(&self, timeout: Duration) -> bool {
        // Admission and reaping are different: an empty slot can still await actor accounting.
        // Read inner before exit_observed, matching admission's lock order.
        let never_admitted = self.inner.lock().unwrap().next_generation == 1;
        let guard = self.exit_observed.0.lock().unwrap();
        if guard.is_some() || never_admitted {
            return true;
        }
        let (guard, _) = self
            .exit_observed
            .1
            .wait_timeout_while(guard, timeout, |exit| exit.is_none())
            .unwrap();
        guard.is_some()
    }
    /// Published only after the loop has persisted its exit decision.
    pub(crate) fn acknowledge_exit(&self, code: i32) {
        *self.exit_observed.0.lock().unwrap() = Some(code);
        self.exit_observed.1.notify_all();
    }
    pub(crate) fn raw_ms(&self) -> u64 {
        self.origin.elapsed().as_millis() as u64
    }
    pub(crate) fn now_ms(&self) -> u64 {
        self.clock.lock().unwrap().now_ms(self.raw_ms())
    }
    pub(crate) fn note_power(&self, event: supervisor::PowerEvent) {
        self.clock.lock().unwrap().note(event, self.raw_ms());
        self.wake.notify_all();
    }
    pub(crate) fn incarnation_number(&self) -> u32 {
        self.inner
            .lock()
            .unwrap()
            .child
            .as_ref()
            .map_or(1, |c| c.generation as u32)
    }
    pub(crate) fn session(&self) -> String {
        // Unique per shell process lifetime, shared by the environment and ledger.
        format!("shell-{}-{:p}", std::process::id(), self)
    }
    pub(crate) fn post_stop(
        &self,
        reason: &str,
        detail: Option<String>,
        budget: Duration,
    ) -> Result<(), String> {
        let now = self.now_ms();
        // Serialize the stop intent with admission: no process can spawn after quit wins.
        let _admission = self.inner.lock().unwrap();
        let mut control = self.control.lock().unwrap();
        if control.active.as_deref() == Some("quit") {
            return if reason == "quit" {
                Ok(())
            } else {
                Err("Quit already in flight; upgrade refused".into())
            };
        }
        if control.active.as_deref() == Some(reason) {
            return Ok(());
        }
        control.active = Some(reason.into());
        control.pending = Some(supervisor::HostQuit {
            reason: reason.into(),
            detail,
            deadline_ms: now + budget.as_millis() as u64,
            deadline_epoch_ms: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64
                + budget.as_millis() as u64,
        });
        self.wake.notify_all();
        Ok(())
    }
    /// Return true when the existing actor owns the request, false when a new actor is needed.
    pub(crate) fn request_manual_restart(&self) -> Result<bool, String> {
        let actor = self.supervisor.lock().unwrap();
        let inner = self.inner.lock().unwrap();
        let mut control = self.control.lock().unwrap();
        if inner.closing || matches!(control.active.as_deref(), Some("quit" | "upgrade")) {
            return Err("Quit or upgrade is in progress; restart refused".into());
        }
        if inner.replacement_held {
            return Err("An update holds Engine replacement; restart refused".into());
        }
        let record = inner
            .latest_record
            .as_ref()
            .ok_or("Engine has no terminal state; restart refused")?;
        if !matches!(record.state.as_str(), "exhausted" | "stopped") {
            return Err("The Engine is already starting or running; restart refused".into());
        }
        if matches!(record.reason.as_deref(), Some("quit" | "upgrade")) {
            return Err("Engine was stopped for quit or upgrade; restart refused".into());
        }
        if actor.as_ref().is_some_and(|actor| !actor.is_finished()) {
            if record.state != "exhausted" || record.retry_at.is_none() {
                return Err("The previous supervision generation is finishing; try again".into());
            }
            control.manual_restart = true;
            self.wake.notify_all();
            Ok(true)
        } else {
            Ok(false)
        }
    }
    pub(crate) fn take_manual_restart(&self) -> bool {
        std::mem::take(&mut self.control.lock().unwrap().manual_restart)
    }
    pub(crate) fn take_host_quit(&self) -> Option<supervisor::HostQuit> {
        self.control.lock().unwrap().pending.take()
    }
    pub(crate) fn wait_or_wake(&self, ms: u64) {
        let control = self.control.lock().unwrap();
        if control.pending.is_none() && !control.manual_restart {
            let _ = self
                .wake
                .wait_timeout(control, Duration::from_millis(ms))
                .unwrap();
        }
    }
    pub(crate) fn publish_terminal(&self, outcome: &supervisor::Outcome) {
        *self.terminal.0.lock().unwrap() = Some(outcome.clone());
        self.terminal.1.notify_all();
    }
    pub(crate) fn terminal_outcome(&self) -> Option<supervisor::Outcome> {
        self.terminal.0.lock().unwrap().clone()
    }
    pub(crate) fn wait_terminal(&self, deadline: u64) -> Option<supervisor::Outcome> {
        loop {
            let guard = self.terminal.0.lock().unwrap();
            if guard.is_some() {
                return guard.clone();
            }
            if self.now_ms() >= deadline {
                return None;
            }
            drop(
                self.terminal
                    .1
                    .wait_timeout(guard, Duration::from_millis(50))
                    .unwrap(),
            );
        }
    }
    pub(crate) fn upgrade_commit(
        &self,
        preparation: &str,
        nonce: &str,
        timeout: Duration,
    ) -> Result<serde_json::Value, String> {
        {
            let mut control = self.control.lock().unwrap();
            if control.active.as_deref() == Some("quit") {
                return Err("Quit already in flight; update left staged".into());
            }
            control.upgrade = Some((preparation.into(), nonce.into()));
            control.receipt = None;
        }
        self.post_stop("upgrade", None, timeout)?;
        let deadline = Instant::now() + timeout;
        loop {
            // Admission and stop intents lock inner before control. Never invert that order
            // while waiting for an upgrade receipt.
            let child_gone = self.child_pid().is_none();
            let mut control = self.control.lock().unwrap();
            if let Some(receipt) = control.receipt.take() {
                return receipt;
            }
            if control.active.as_deref() == Some("quit") {
                return Err("Quit won; update left staged".into());
            }
            // Only a still-pending request can take the pre-commit dead-Engine path.
            // take_upgrade consumes it before HTTP; a crash after that still needs receipts.
            if control.upgrade.is_some() && child_gone {
                control.upgrade = None;
                return Err("Engine exited before upgrade commit request".into());
            }
            if Instant::now() >= deadline {
                return Err("Upgrade commit receipt timed out".into());
            }
            drop(
                self.wake
                    .wait_timeout(control, Duration::from_millis(50))
                    .unwrap(),
            );
        }
    }
    pub(crate) fn quit_started(&self) -> bool {
        let quitting = self.control.lock().unwrap().active.as_deref() == Some("quit");
        quitting || self.inner.lock().unwrap().closing
    }
    pub(crate) fn reject_upgrade(&self) {
        let mut control = self.control.lock().unwrap();
        if control.active.as_deref() == Some("upgrade") {
            control.active = None;
        }
    }
    pub(crate) fn take_upgrade(&self) -> Option<(String, String)> {
        self.control.lock().unwrap().upgrade.take()
    }
    pub(crate) fn set_upgrade_receipt(&self, receipt: Result<serde_json::Value, String>) {
        self.control.lock().unwrap().receipt = Some(receipt);
        self.wake.notify_all();
    }
    pub(crate) fn supervisor_running(&self) -> bool {
        self.supervisor
            .lock()
            .unwrap()
            .as_ref()
            .is_some_and(|h| !h.is_finished())
    }
    pub(crate) fn with_child_mut(&self, f: impl FnOnce(&mut Child)) {
        if let Some(child) = self
            .inner
            .lock()
            .expect("engine host mutex poisoned")
            .child
            .as_mut()
        {
            f(&mut child.child);
        }
    }
    pub(crate) fn begin_close(&self) -> bool {
        let mut inner = self.inner.lock().expect("engine host mutex poisoned");
        if inner.closing {
            return false;
        }
        inner.closing = true;
        drop(inner);
        self.stop_watcher();
        true
    }
    pub(crate) fn should_continue(&self) -> bool {
        let inner = self.inner.lock().expect("engine host mutex poisoned");
        !matches!(
            inner.latest_record.as_ref().map(|r| r.state.as_str()),
            Some("stopped" | "exhausted")
        )
    }

    /// Production startup wait. Cancellation leaves shutdown ownership with the host/updater.
    pub(crate) fn await_ready(
        &self,
        deadline_ms: u64,
        mut monotonic_ms: impl FnMut() -> u64,
        mut responds: impl FnMut(u16) -> bool,
    ) -> Result<supervisor::Ready, String> {
        let _ = (deadline_ms, monotonic_ms());
        if let Some(binding) = self.observe_current_binding(&mut responds) {
            return Ok(supervisor::Ready {
                pid: self.child_pid(),
                api_port: binding.port,
                instance_id: binding.instance_id,
            });
        }
        Err("not responsive yet".into())
    }

    /// Own exactly one supervision loop. Replacement waits for this loop to end before proceeding.
    pub(crate) fn start_supervisor(
        &self,
        run: impl FnOnce() + Send + 'static,
    ) -> Result<(), String> {
        self.install_supervisor(run, || {})
    }

    fn install_supervisor(
        &self,
        run: impl FnOnce() + Send + 'static,
        before_spawn: impl FnOnce(),
    ) -> Result<(), String> {
        let mut supervisor = self.supervisor.lock().expect("supervisor mutex poisoned");
        let mut control = {
            let inner = self.inner.lock().unwrap();
            if inner.closing || (inner.replacement_held && inner.child.is_none()) {
                return Err(
                    "Engine host is closed or held by an update; supervision refused".into(),
                );
            }
            let control = self.control.lock().unwrap();
            if control.active.as_deref() == Some("quit") {
                return Err("Quit is in progress; supervision refused".into());
            }
            control
        };
        if supervisor.as_ref().is_some_and(|h| !h.is_finished()) {
            return Err("A supervision generation is already installed".into());
        }
        if let Some(old) = supervisor.take() {
            let _ = old.join();
        }
        *self.terminal.0.lock().unwrap() = None;
        control.active = None;
        control.manual_restart = false;
        before_spawn();
        *supervisor = Some(thread::spawn(run));
        Ok(())
    }

    /// Cancellation plus bounded join: on failure the hold remains explicit and spawns stay blocked.
    pub(crate) fn acquire_replacement_hold(&self, timeout: Duration) -> Result<(), String> {
        {
            // Same order as installation and release: no new generation can pass eligibility
            // between the hold's ownership check and publication.
            let _supervisor = self.supervisor.lock().expect("supervisor mutex poisoned");
            let mut inner = self.inner.lock().expect("engine host mutex poisoned");
            if inner.closing || inner.replacement_held {
                return Err("Engine host is already closed or held".into());
            }
            inner.replacement_held = true;
        }
        let _ = timeout;
        self.wake.notify_all();
        Ok(())
    }

    /// Used only after the updater has established that resuming the current installation is safe.
    pub(crate) fn release_replacement_hold(&self) -> Result<(), String> {
        let mut inner = self.inner.lock().expect("engine host mutex poisoned");
        if inner.closing || !inner.replacement_held {
            return Err("Cannot resume a closed, unheld or still-supervised Engine host".into());
        }
        inner.replacement_held = false;
        self.wake.notify_all();
        Ok(())
    }
    /// Give up ownership of the Engine. Its incarnation job is ended first, so the returned handle
    /// is to a process that is stopping or stopped, with nothing of its own left behind.
    pub(crate) fn replacement_held(&self) -> bool {
        self.inner
            .lock()
            .expect("Engine host mutex poisoned")
            .replacement_held
    }

    pub(crate) fn stop_owned_engine(&self, timeout: Duration) -> Result<Option<u32>, String> {
        let pid = self.child_pid();
        if pid.is_none() {
            return Ok(None);
        }
        self.post_stop("upgrade", None, timeout)?;
        // The stop deadline still bounds the cooperative/forced stop. The actor then
        // reaps the child, waits for the incarnation job in end_owned, and persists its
        // exit before publishing terminal. Give that work its own declared allowance.
        let deadline = self.now_ms()
            + timeout.as_millis() as u64
            + containment::RESTART_FLOOR_WAIT_MS
            + containment::JOB_TERMINATE_CONFIRM_MS;
        match self.wait_terminal(deadline) {
            Some(supervisor::Outcome::Stopped { reason, .. }) if reason == "upgrade" => Ok(pid),
            Some(supervisor::Outcome::NoEngine)
                if self.control.lock().unwrap().active.as_deref() == Some("upgrade")
                    && self.replacement_held()
                    && self.child_pid().is_none()
                    && self.wait_for_exit(Duration::ZERO) =>
            {
                Ok(pid)
            }
            _ => Err("Engine stop did not finish as upgrade; installer refused".into()),
        }
    }

    pub(crate) fn ensure_watcher(
        self: &Arc<Self>,
        tick: impl Fn() + Send + Sync + 'static,
    ) -> Result<(), String> {
        let (lock, _cv) = &*self.watcher;
        let mut watcher = lock.lock().expect("watcher mutex poisoned");
        if self.inner.lock().unwrap().closing {
            return Err("Engine host is closing; watcher refused".into());
        }
        if watcher.started {
            return Ok(());
        }
        watcher.started = true;
        let control = self.watcher.clone();
        let tick = Arc::new(tick);
        watcher.handle = Some(thread::spawn(move || loop {
            tick();
            let (lock, cv) = &*control;
            let guard = lock.lock().expect("watcher mutex poisoned");
            let (guard, _) = cv
                .wait_timeout_while(guard, Duration::from_millis(100), |s| !s.cancelled)
                .expect("watcher condvar poisoned");
            if guard.cancelled {
                break;
            }
        }));
        Ok(())
    }

    fn stop_watcher(&self) {
        let handle = {
            let (lock, cv) = &*self.watcher;
            let mut watcher = lock.lock().expect("watcher mutex poisoned");
            watcher.cancelled = true;
            cv.notify_all();
            watcher.handle.take()
        };
        if let Some(handle) = handle {
            if handle.thread().id() != thread::current().id() {
                let _ = handle.join();
            }
        }
    }

    pub(crate) fn publish_state(
        &self,
        path: Option<&Path>,
        record: &StateRecord,
        event: impl FnOnce(&StateRecord),
    ) {
        let record = &{
            let mut inner = self.inner.lock().expect("engine host mutex poisoned");
            let mut record = record.clone();
            if inner.shell_containment_error.is_some() {
                record.containment = Some(SHELL_CONTAINMENT_UNAVAILABLE.to_string());
            }
            inner.latest_record = Some(record.clone());
            record
        };
        if let Some(path) = path {
            if let Some(parent) = path.parent() {
                if crate::exit_ledger::ensure_owner_only_directory(parent).is_err() {
                    event(record);
                    return;
                }
            }
            let tmp = path.with_extension("tmp");
            if let Ok(json) = serde_json::to_string_pretty(record) {
                if std::fs::write(&tmp, format!("{json}\n")).is_ok() {
                    let _ = std::fs::rename(tmp, path);
                }
            }
        }
        event(record);
    }

    pub(crate) fn publish_initial_spawn_failure(
        &self,
        path: Option<&Path>,
        predecessor_instance_id: Option<String>,
        error: &str,
        event: impl FnOnce(&StateRecord),
    ) {
        let mut sup = supervisor::Supervisor::new(supervisor::load_policy());
        sup.state = supervisor::State::Exhausted;
        let record = sup.record(
            None,
            None,
            predecessor_instance_id,
            Some(format!("ENGINE_RESTART_EXHAUSTED:spawn_failed:{error}")),
            supervisor::epoch_ms_to_iso(
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis() as u64)
                    .unwrap_or(0),
            ),
        );
        self.publish_state(path, &record, event);
    }
    pub(crate) fn latest_record(&self) -> Option<StateRecord> {
        self.inner.lock().unwrap().latest_record.clone()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    impl EngineHost {
        pub(crate) fn fixture_take_child(&self) -> Option<Child> {
            let mut inner = self.inner.lock().expect("engine host mutex poisoned");
            let owned = inner.child.take()?;
            inner.discovery = Discovery::AwaitingSuccessor;
            Some(Self::end_owned(&mut inner, owned))
        }

        pub(crate) fn fixture_kill_and_reap(&self) {
            let _ = self.force_stop();
            if let Some(mut child) = self.fixture_take_child() {
                let _ = child.wait();
            }
        }
    }

    fn sleeper() -> PreparedCommand {
        let mut command = if cfg!(windows) {
            let mut c = Command::new("ping.exe");
            c.args(["-n", "30", "127.0.0.1"]);
            c
        } else {
            let mut c = crate::containment::engine_command("sleep").unwrap();
            c.arg("30");
            c
        };
        command
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped());
        PreparedCommand { command }
    }

    #[test]
    fn manual_restart_after_finished_exhaustion_reaches_ready_with_a_new_generation() {
        let dir = tempfile::tempdir().unwrap();
        let host = Arc::new(EngineHost::default());
        let failed_host = host.clone();
        let failed_dir = dir.path().to_path_buf();
        let spent = Arc::new(AtomicUsize::new(0));
        let observed_spent = spent.clone();
        let crash_host = host.clone();
        host.start_supervisor(move || {
            let mut attempts = 0;
            let mut actuator = crate::shell_actuator::ShellActuator::new(
                failed_host,
                failed_dir,
                move || {
                    attempts += 1;
                    if attempts == 5 {
                        return Err("replacement Java unavailable".into());
                    }
                    crash_host.reset_for_successor()?;
                    let mut command =
                        crate::containment::engine_command("node").map_err(|e| e.to_string())?;
                    command
                        .args(["-e", "process.exit(1)"])
                        .stdout(std::process::Stdio::null())
                        .stderr(std::process::Stdio::null());
                    crash_host.admit(PreparedCommand { command }).map(|c| c.pid)
                },
                move |record| {
                    if record.state == "exhausted" && record.restart_count == 3 {
                        observed_spent.fetch_add(1, Ordering::SeqCst);
                    }
                },
            );
            let mut policy = supervisor::load_policy();
            policy.cooldown_increment_ms = 0;
            policy.max_cooldown_ms = 0;
            policy.exhausted_retry_delay_ms = 1;
            let mut supervisor = supervisor::Supervisor::new(policy);
            supervisor::run_supervision(&mut supervisor, &mut actuator);
        })
        .unwrap();
        let until = Instant::now() + Duration::from_secs(10);
        while host.supervisor_running() {
            assert!(Instant::now() < until, "failed generation did not finish");
            thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(spent.load(Ordering::SeqCst), 1);
        assert!(host
            .latest_record()
            .unwrap()
            .reason
            .unwrap()
            .contains("replacement Java unavailable"));
        assert!(matches!(
            host.terminal_outcome(),
            Some(supervisor::Outcome::Exhausted { .. })
        ));
        assert_eq!(
            host.request_manual_restart(),
            Ok(false),
            "a new actor must own this restart"
        );

        let spawn_host = host.clone();
        let successor_host = host.clone();
        let data_dir = dir.path().to_path_buf();
        let spawn_dir = data_dir.clone();
        let fake_engine = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../scripts/supervisor-conformance/fake-engine.mjs");
        host.start_supervisor(move || {
            let mut actuator = crate::shell_actuator::ShellActuator::new(
                successor_host,
                data_dir,
                move || {
                    spawn_host.reset_for_successor()?;
                    let mut command =
                        crate::containment::engine_command("node").map_err(|e| e.to_string())?;
                    command
                        .arg(&fake_engine)
                        .env("JUSTSEARCH_DATA_DIR", &spawn_dir)
                        .env("JUSTSEARCH_API_PORT", "0")
                        .env("JUSTSEARCH_FAKE_ENGINE_FAULT_GATE", "")
                        .env("JUSTSEARCH_FAKE_ENGINE_PLAN", "")
                        .stdout(std::process::Stdio::null())
                        .stderr(std::process::Stdio::null());
                    spawn_host.admit(PreparedCommand { command }).map(|c| c.pid)
                },
                |_| {},
            );
            let mut supervisor = supervisor::Supervisor::new(supervisor::load_policy());
            supervisor::run_supervision(&mut supervisor, &mut actuator);
        })
        .unwrap();
        assert!(host.start_supervisor(|| panic!("duplicate owner")).is_err());
        let until = Instant::now() + Duration::from_secs(10);
        while host
            .latest_record()
            .is_none_or(|state| state.state != "running")
            && Instant::now() < until
        {
            thread::sleep(Duration::from_millis(10));
        }
        let restarted = host.latest_record().unwrap();
        let binding = host.binding_snapshot();
        host.post_stop("quit", None, Duration::from_secs(2))
            .unwrap();
        host.supervisor
            .lock()
            .unwrap()
            .take()
            .unwrap()
            .join()
            .unwrap();
        assert_eq!(restarted.state, "running");
        assert_eq!(restarted.restart_count, 0);
        assert!(binding.port.is_some());
        assert!(binding.token.is_some());
        assert!(binding.instance_id.is_some());
        assert!(host.child_pid().is_none());
    }

    #[test]
    fn manual_restart_refuses_quit_and_update_holds_without_admitting_a_child() {
        let host = EngineHost::default();
        host.publish_state(None, &terminal_record("native crash"), |_| {});
        assert_eq!(host.request_manual_restart(), Ok(false));
        host.acquire_replacement_hold(Duration::ZERO).unwrap();
        assert!(host
            .request_manual_restart()
            .unwrap_err()
            .contains("update"));
        host.release_replacement_hold().unwrap();
        host.post_stop("quit", None, Duration::ZERO).unwrap();
        assert!(host.request_manual_restart().unwrap_err().contains("Quit"));
        assert!(host.admit(sleeper()).unwrap_err().contains("closing"));
        assert!(host.child_pid().is_none());
    }

    #[test]
    fn hold_linearizes_with_supervisor_installation_and_joins_that_generation() {
        let host = Arc::new(EngineHost::default());
        let (entered_tx, entered_rx) = std::sync::mpsc::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel();
        let installing = host.clone();
        let running = host.clone();
        let install = thread::spawn(move || {
            installing.install_supervisor(
                move || {
                    while running.should_continue() {
                        thread::sleep(Duration::from_millis(2));
                    }
                },
                move || {
                    entered_tx.send(()).unwrap();
                    release_rx.recv().unwrap();
                },
            )
        });
        entered_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        let holding = host.clone();
        let (hold_started_tx, hold_started_rx) = std::sync::mpsc::channel();
        let hold = thread::spawn(move || {
            hold_started_tx.send(()).unwrap();
            holding.acquire_replacement_hold(Duration::from_secs(2))
        });
        hold_started_rx
            .recv_timeout(Duration::from_secs(2))
            .unwrap();
        thread::sleep(Duration::from_millis(50));
        let held_before_install_finished = host.replacement_held();
        release_tx.send(()).unwrap();
        install.join().unwrap().unwrap();
        hold.join().unwrap().unwrap();
        assert!(
            !held_before_install_finished,
            "hold publication overtook supervisor installation"
        );
        assert!(host.replacement_held());
        assert!(host.supervisor.lock().unwrap().is_some());
        host.publish_state(None, &terminal_record("test cleanup"), |_| {});
    }

    #[test]
    fn stop_witness_keeps_pid_of_an_already_exited_owned_child() {
        let host = EngineHost::default();
        let pid = host.admit(sleeper()).unwrap().pid;
        host.with_child_mut(|child| {
            child.kill().unwrap();
        });
        let deadline = Instant::now() + Duration::from_secs(2);
        while host.try_reap().is_none() {
            assert!(Instant::now() < deadline);
            thread::sleep(Duration::from_millis(5));
        }
        host.acknowledge_exit(1);
        host.acquire_replacement_hold(Duration::from_secs(2))
            .unwrap();
        assert!(pid > 0);
        assert_eq!(
            host.stop_owned_engine(Duration::from_secs(2)).unwrap(),
            None
        );
        assert_eq!(host.child_pid(), None);
    }

    fn manifest(host: &EngineHost, id: &str, pid: u32, port: u16) -> ManifestFields {
        ManifestFields {
            api_port: Some(port),
            session_token: Some(format!("token-{id}")),
            lifecycle: Some("READY".into()),
            instance_id: Some(id.into()),
            pid: Some(pid),
            incarnation: Some((host.session(), u64::from(host.incarnation_number()))),
            ..Default::default()
        }
    }

    fn terminal_record(reason: &str) -> StateRecord {
        StateRecord {
            retry_at: None,
            not_ready: None,
            recent_exits: None,
            crash_file: None,
            schema_version: 1,
            kind: "engine-supervisor-state.v1".into(),
            supervisor: "tauri".into(),
            state: "exhausted".into(),
            incarnation: 1,
            pid: None,
            api_port: None,
            instance_id: None,
            restart_count: 0,
            max_restart_attempts: 3,
            policy_profile: "product".into(),
            last_exit: None,
            reason: Some(reason.into()),
            requested_reason: None,
            updated_at: "2026-09-08T00:00:00.000Z".into(),
            containment: None,
            phase: None,
        }
    }

    #[test]
    fn close_before_admission_refuses_launch() {
        let host = Arc::new(EngineHost::default());
        let closed = Arc::new(std::sync::Barrier::new(2));
        let close_host = host.clone();
        let close_barrier = closed.clone();
        let close = thread::spawn(move || {
            assert!(close_host.begin_close());
            close_barrier.wait();
        });
        closed.wait();
        assert!(host.admit(sleeper()).is_err());
        assert_eq!(host.child_pid(), None);
        close.join().unwrap();
    }

    #[test]
    fn probes_require_an_admitted_binding_and_reject_close_during_response() {
        let host = EngineHost::default();
        assert!(host
            .observe_current_binding(|_| panic!("no child to probe"))
            .is_none());
        let child = host.admit(sleeper()).unwrap();
        assert!(host
            .observe_current_binding(|_| panic!("no admitted manifest"))
            .is_none());
        host.observe_manifest(&manifest(&host, "current", child.pid, 40404));
        assert!(host.observe_current_binding(|port| port == 40404).is_some());
        assert!(host
            .observe_current_binding(|_| {
                host.begin_close();
                true
            })
            .is_none());
        if let Some(mut child) = host.fixture_take_child() {
            child.kill().unwrap();
            child.wait().unwrap();
        }
    }

    #[test]
    fn current_value_retains_probe_result_for_the_unchanged_admitted_binding() {
        let host = EngineHost::default();
        let child = host.admit(sleeper()).unwrap();
        host.observe_manifest(&manifest(&host, "current", child.pid, 40404));

        let observed = host
            .observe_current_value(|port| (port == 40404).then_some("ready-epoch-a"))
            .expect("an unchanged admitted binding retains the probed value");

        assert_eq!(observed.0, host.binding_snapshot());
        assert_eq!(observed.1, "ready-epoch-a");
        host.fixture_kill_and_reap();
    }

    #[test]
    fn current_value_discards_probe_result_when_the_binding_changes_during_the_probe() {
        let host = EngineHost::default();
        let child = host.admit(sleeper()).unwrap();
        let mut incomplete = manifest(&host, "current", child.pid, 40404);
        incomplete.session_token = None;
        host.observe_manifest(&incomplete);
        assert_eq!(host.binding_snapshot().token, None);

        let observed = host.observe_current_value(|port| {
            assert_eq!(port, 40404);
            let change = host
                .observe_manifest(&manifest(&host, "current", child.pid, 40404))
                .expect("the current instance may fill a missing token");
            assert!(
                change.token_available,
                "the probe must actually mutate the binding"
            );
            Some("stale-ready-epoch")
        });

        assert!(observed.is_none());
        assert_eq!(
            host.binding_snapshot().token.as_deref(),
            Some("token-current")
        );
        host.fixture_kill_and_reap();
    }

    #[test]
    fn current_value_discards_probe_result_when_the_child_incarnation_changes() {
        let host = EngineHost::default();
        let first = host.admit(sleeper()).unwrap();
        host.observe_manifest(&manifest(&host, "first", first.pid, 40404));

        let observed = host.observe_current_value(|port| {
            assert_eq!(port, 40404);
            let mut child = host.fixture_take_child().unwrap();
            child.kill().unwrap();
            child.wait().unwrap();
            host.reset_for_successor().unwrap();
            let second = host.admit(sleeper()).unwrap();
            host.observe_manifest(&manifest(&host, "second", second.pid, 50505));
            Some("stale-ready-epoch")
        });

        assert!(observed.is_none());
        assert_eq!(
            host.binding_snapshot().instance_id.as_deref(),
            Some("second")
        );
        host.fixture_kill_and_reap();
    }

    #[test]
    fn incarnation_reset_never_reopens_a_closed_host() {
        let host = EngineHost::default();
        host.begin_close();
        host.reset_for_successor().unwrap();
        assert!(
            host.should_continue(),
            "closing admission does not cancel the actor"
        );
        assert!(host.admit(sleeper()).is_err());
    }

    #[test]
    fn replacement_hold_joins_old_supervisor_blocks_spawn_and_resumes_once() {
        let host = Arc::new(EngineHost::default());
        host.admit(sleeper()).unwrap(); // no manifest/API port: still an owned Engine
        let loops = Arc::new(AtomicUsize::new(0));
        let run_host = host.clone();
        let run_loops = loops.clone();
        host.start_supervisor(move || {
            run_loops.fetch_add(1, Ordering::SeqCst);
            while run_host.should_continue() {
                thread::sleep(Duration::from_millis(5));
            }
        })
        .unwrap();
        assert!(host
            .start_supervisor(|| panic!("duplicate supervisor"))
            .is_err());
        host.acquire_replacement_hold(Duration::from_secs(1))
            .unwrap();
        while loops.load(Ordering::SeqCst) == 0 {
            thread::yield_now();
        }
        assert_eq!(loops.load(Ordering::SeqCst), 1);
        host.fixture_kill_and_reap();
        assert!(host.admit(sleeper()).unwrap_err().contains("held"));
        host.release_replacement_hold().unwrap();
        assert!(host.release_replacement_hold().is_err());
        host.reset_for_successor().unwrap();
        host.admit(sleeper()).unwrap();
        host.acquire_replacement_hold(Duration::from_secs(1))
            .unwrap();
        assert_eq!(loops.load(Ordering::SeqCst), 1);
        host.fixture_kill_and_reap();
        host.begin_close();
        host.publish_state(None, &terminal_record("test cleanup"), |_| {});
        assert!(
            host.release_replacement_hold().is_err(),
            "quit remains permanent"
        );
    }

    #[test]
    fn failed_supervisor_join_keeps_replacement_held() {
        let host = EngineHost::default();
        let (release, wait) = std::sync::mpsc::channel();
        host.start_supervisor(move || {
            wait.recv().unwrap();
        })
        .unwrap();
        assert!(host
            .acquire_replacement_hold(Duration::from_millis(25))
            .is_ok());
        assert!(host.supervisor_running());
        assert!(host.admit(sleeper()).unwrap_err().contains("held"));
        release.send(()).unwrap();
        // Keep the failed hold until explicit recovery; dropping the host does not spawn anything.
        host.begin_close();
    }

    struct StartupWaitActuator {
        host: Arc<EngineHost>,
        clock_origin: Instant,
        entered: Option<std::sync::mpsc::Sender<()>>,
        restart_first: bool,
        shutdown_actions: Arc<AtomicUsize>,
    }

    impl supervisor::Actuator for StartupWaitActuator {
        fn spawn_engine(&mut self) -> Result<u32, String> {
            self.host.reset_for_successor()?;
            self.host.admit(sleeper()).map(|child| child.pid)
        }
        fn await_ready(&mut self, deadline_ms: u64) -> Result<supervisor::Ready, String> {
            self.host.await_ready(
                deadline_ms,
                || {
                    if let Some(entered) = self.entered.take() {
                        entered.send(()).unwrap();
                    }
                    self.clock_origin.elapsed().as_millis() as u64
                },
                |_| panic!("an unbound Engine has no port to probe"),
            )
        }
        fn poll_exit(&mut self) -> Option<i32> {
            if self.restart_first {
                self.restart_first = false;
                self.host.fixture_kill_and_reap();
                Some(1)
            } else {
                self.host.try_reap()
            }
        }
        fn probe_health(&mut self) -> bool {
            panic!("still starting")
        }
        fn probe_essential_ready(&mut self) -> Option<crate::engine_probe::ReadyEpoch> {
            panic!("still starting")
        }
        fn observed_shutdown_reason(&mut self, _current: &supervisor::Ready) -> Option<String> {
            None
        }
        fn write_shutdown_request(&mut self, _reason: &str, _deadline: u64) -> Result<(), String> {
            self.shutdown_actions.fetch_add(1, Ordering::SeqCst);
            Ok(())
        }
        fn force_kill(&mut self) {
            self.shutdown_actions.fetch_add(1, Ordering::SeqCst);
            let _ = self.host.force_stop();
        }
        fn take_host_quit(&mut self) -> Option<supervisor::HostQuit> {
            self.host.take_host_quit()
        }
        fn wait_for_handle_release(&mut self) -> bool {
            true
        }
        fn sleep(&mut self, ms: u64) {
            thread::sleep(Duration::from_millis(ms));
        }
        fn monotonic_ms(&mut self) -> u64 {
            self.clock_origin.elapsed().as_millis() as u64
        }
        fn publish_state(&mut self, _record: &StateRecord) {}
        fn should_continue(&mut self) -> bool {
            self.host.should_continue()
        }
    }

    #[test]
    fn replacement_hold_cancels_production_startup_wait_without_shutdown_work() {
        for restart_first in [false, true] {
            let host = Arc::new(EngineHost::default());
            host.admit(sleeper()).unwrap();
            if restart_first {
                host.record_spawn_error("initial incarnation failed".into());
            }
            let (entered_tx, entered_rx) = std::sync::mpsc::channel();
            let (outcome_tx, outcome_rx) = std::sync::mpsc::channel();
            let shutdown_actions = Arc::new(AtomicUsize::new(0));
            let mut actuator = StartupWaitActuator {
                host: host.clone(),
                clock_origin: Instant::now(),
                entered: Some(entered_tx),
                restart_first,
                shutdown_actions: shutdown_actions.clone(),
            };
            host.start_supervisor(move || {
                let mut policy = supervisor::load_policy();
                policy.cooldown_increment_ms = 0;
                policy.max_cooldown_ms = 0;
                let mut supervisor = supervisor::Supervisor::new(policy);
                outcome_tx
                    .send(supervisor::run_supervision(&mut supervisor, &mut actuator))
                    .unwrap();
            })
            .unwrap();
            entered_rx.recv_timeout(Duration::from_secs(2)).unwrap();
            // Use a bound far below the product's 120-second startup deadline.
            let held = host.acquire_replacement_hold(Duration::from_secs(2));
            let outcome = outcome_rx.recv_timeout(Duration::from_millis(100));
            let child_alive = host.child_pid().is_some() && host.try_reap().is_none();
            // Cleanup even when the hold fails, so the negative control leaves no live child.
            assert!(host
                .post_stop("quit", None, Duration::from_millis(350))
                .is_ok());
            assert!(held.is_ok(), "{held:?}");
            assert!(outcome.is_err(), "hold keeps the actor alive");
            assert!(
                child_alive,
                "only the updater may stop the child after cancellation"
            );
            assert_eq!(shutdown_actions.load(Ordering::SeqCst), 0);
            host.release_replacement_hold().unwrap();
            host.acquire_replacement_hold(Duration::from_secs(2))
                .unwrap();
            assert!(matches!(
                outcome_rx.recv_timeout(Duration::from_secs(2)).unwrap(),
                supervisor::Outcome::Stopped { .. }
            ));
        }
    }

    #[test]
    fn admitted_child_is_visible_to_close_and_reaped() {
        let host = Arc::new(EngineHost::default());
        let at_spawn = Arc::new(std::sync::Barrier::new(2));
        let release_spawn = Arc::new(std::sync::Barrier::new(2));
        let admission_host = host.clone();
        let admission_at_spawn = at_spawn.clone();
        let admission_release = release_spawn.clone();
        let admission = thread::spawn(move || {
            let mut prepared = sleeper();
            admission_host.admit_with(&mut prepared, || {
                admission_at_spawn.wait();
                admission_release.wait();
            })
        });
        at_spawn.wait();
        let close_host = host.clone();
        let close = thread::spawn(move || {
            assert!(close_host.begin_close());
            close_host.fixture_kill_and_reap();
        });
        release_spawn.wait();
        let admitted = admission.join().unwrap().unwrap();
        assert!(admitted.pid > 0);
        close.join().unwrap();
        assert_eq!(host.child_pid(), None);
    }

    #[test]
    fn delayed_old_stdout_eof_cannot_poison_successor() {
        let host = EngineHost::default();
        let first = host.admit(sleeper()).unwrap();
        host.fixture_kill_and_reap();
        host.reset_for_successor().unwrap();
        let second = host.admit(sleeper()).unwrap();
        assert!(!host.stdout_closed(first.generation));
        assert!(!host.has_spawn_error());
        host.fixture_kill_and_reap();
        assert_ne!(first.generation, second.generation);
    }

    #[test]
    fn real_reset_rejects_stale_manifests_and_emits_once_for_successor() {
        let host = EngineHost::default();
        let first = host.admit(sleeper()).unwrap();
        assert!(
            !host
                .observe_manifest(&manifest(&host, "old", first.pid, 1111))
                .unwrap()
                .restarted
        );
        let mut child = host.fixture_take_child().unwrap();
        let _ = child.kill();
        let _ = child.wait();
        assert_eq!(
            host.observe_manifest(&manifest(&host, "ghost", first.pid, 9999)),
            None,
            "a reaped child cannot continue admitting manifests"
        );
        host.reset_for_successor().unwrap();
        assert_eq!(
            host.binding_snapshot(),
            Binding {
                instance_id: Some("old".into()),
                port: None,
                token: None
            }
        );
        assert_eq!(
            host.observe_manifest(&manifest(&host, "residue", first.pid, 9999)),
            None,
            "no successor is installed, so no manifest is admissible"
        );

        let second = host.admit(sleeper()).unwrap();
        let mut events = 0;
        host.observe_manifest_with_sinks(
            &manifest(&host, "old", second.pid, 1111),
            |_| events += 1,
            |_| {},
        );
        host.observe_manifest_with_sinks(
            &manifest(&host, "new", first.pid, 2222),
            |_| events += 1,
            |_| {},
        );
        host.observe_manifest_with_sinks(
            &manifest(&host, "new", second.pid, 2222),
            |_| events += 1,
            |_| {},
        );
        host.observe_manifest_with_sinks(
            &manifest(&host, "new", second.pid, 2222),
            |_| events += 1,
            |_| {},
        );
        assert_eq!(events, 1);
        assert_eq!(host.binding_snapshot().instance_id.as_deref(), Some("new"));
        let mut child = host.fixture_take_child().unwrap();
        let _ = child.kill();
        let _ = child.wait();
    }

    #[test]
    fn initial_launch_failure_uses_shared_terminal_disk_event_writer() {
        let host = EngineHost::default();
        let command = Command::new("a-command-that-does-not-exist-justsearch");
        let error = host.admit(PreparedCommand { command }).unwrap_err();
        host.record_spawn_error(error.clone());
        assert!(host.has_spawn_error());
        assert_eq!(host.child_pid(), None);

        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("runtime").join("supervisor.v1.json");
        let observed = Mutex::new(None);
        host.publish_initial_spawn_failure(Some(&path), None, &error, |event| {
            *observed.lock().unwrap() = Some(event.clone());
        });
        let disk: StateRecord =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        assert!(disk
            .reason
            .as_deref()
            .unwrap()
            .starts_with("ENGINE_RESTART_EXHAUSTED:spawn_failed:"));
        assert_eq!(observed.into_inner().unwrap().unwrap().reason, disk.reason);
        assert_eq!(host.latest_record().unwrap().reason, disk.reason);

        let memory_only = terminal_record("ENGINE_RESTART_EXHAUSTED:data_dir_resolution_failed");
        let memory_event = Mutex::new(None);
        host.publish_state(None, &memory_only, |event| {
            *memory_event.lock().unwrap() = Some(event.clone());
        });
        assert_eq!(
            memory_event.into_inner().unwrap().unwrap().reason,
            memory_only.reason
        );
        assert_eq!(host.latest_record().unwrap().reason, memory_only.reason);
    }

    struct ReplacementFailureActuator {
        host: Arc<EngineHost>,
        state_path: std::path::PathBuf,
        events: Vec<StateRecord>,
        exit_pending: bool,
        now: u64,
    }

    impl supervisor::Actuator for ReplacementFailureActuator {
        fn spawn_engine(&mut self) -> Result<u32, String> {
            self.host
                .admit(PreparedCommand {
                    command: Command::new("replacement-command-that-does-not-exist-justsearch"),
                })
                .map(|child| child.pid)
        }
        fn await_ready(&mut self, _deadline_ms: u64) -> Result<supervisor::Ready, String> {
            Ok(supervisor::Ready {
                pid: Some(4242),
                api_port: Some(40404),
                instance_id: Some("predecessor".into()),
            })
        }
        fn poll_exit(&mut self) -> Option<i32> {
            self.exit_pending.then(|| {
                self.exit_pending = false;
                1
            })
        }
        fn probe_health(&mut self) -> bool {
            true
        }
        fn probe_essential_ready(&mut self) -> Option<crate::engine_probe::ReadyEpoch> {
            crate::engine_probe::ReadyEpoch::parse("2026-09-21T12:00:00Z")
        }
        fn observed_shutdown_reason(&mut self, _current: &supervisor::Ready) -> Option<String> {
            None
        }
        fn write_shutdown_request(&mut self, _reason: &str, _deadline: u64) -> Result<(), String> {
            Ok(())
        }
        fn force_kill(&mut self) {}
        fn wait_for_handle_release(&mut self) -> bool {
            true
        }
        fn sleep(&mut self, ms: u64) {
            self.now += ms;
        }
        fn monotonic_ms(&mut self) -> u64 {
            self.now
        }
        fn publish_state(&mut self, record: &StateRecord) {
            let events = &mut self.events;
            self.host
                .publish_state(Some(&self.state_path), record, |record| {
                    events.push(record.clone())
                });
        }
    }

    struct EpochContinuityActuator {
        now: u64,
        ready_calls: usize,
        exit_pending: bool,
        epoch_probes: usize,
        counted_restart_seen: bool,
        reset_at: Option<u64>,
    }

    impl supervisor::Actuator for EpochContinuityActuator {
        fn spawn_engine(&mut self) -> Result<u32, String> {
            Ok(43)
        }

        fn await_ready(&mut self, _deadline_ms: u64) -> Result<supervisor::Ready, String> {
            self.ready_calls += 1;
            Ok(supervisor::Ready {
                pid: Some(if self.ready_calls == 1 { 42 } else { 43 }),
                api_port: Some(40404),
                instance_id: Some(format!("incarnation-{}", self.ready_calls)),
            })
        }

        fn poll_exit(&mut self) -> Option<i32> {
            if self.reset_at.is_some() {
                return Some(0);
            }
            self.exit_pending.then(|| {
                self.exit_pending = false;
                1
            })
        }

        fn probe_health(&mut self) -> bool {
            true
        }

        fn probe_essential_ready(&mut self) -> Option<crate::engine_probe::ReadyEpoch> {
            let epoch = if self.epoch_probes == 0 {
                "2026-09-21T12:00:00Z"
            } else {
                "2026-09-21T12:01:00Z"
            };
            self.epoch_probes += 1;
            crate::engine_probe::ReadyEpoch::parse(epoch)
        }

        fn observed_shutdown_reason(&mut self, _current: &supervisor::Ready) -> Option<String> {
            None
        }

        fn write_shutdown_request(&mut self, _reason: &str, _deadline: u64) -> Result<(), String> {
            Ok(())
        }

        fn force_kill(&mut self) {}

        fn wait_for_handle_release(&mut self) -> bool {
            true
        }

        fn sleep(&mut self, ms: u64) {
            self.now += ms;
        }

        fn monotonic_ms(&mut self) -> u64 {
            self.now
        }

        fn publish_state(&mut self, record: &StateRecord) {
            if record.restart_count == 1 {
                self.counted_restart_seen = true;
            } else if self.counted_restart_seen
                && record.restart_count == 0
                && self.reset_at.is_none()
            {
                self.reset_at = Some(self.now);
            }
        }

        fn should_continue(&mut self) -> bool {
            self.reset_at.is_none() && self.now <= 500
        }
    }

    #[test]
    fn a_new_ready_epoch_must_span_the_full_stability_window_before_budget_reset() {
        let mut policy = supervisor::load_policy();
        policy.cooldown_increment_ms = 0;
        policy.max_cooldown_ms = 0;
        policy.hang_poll_interval_ms = 50;
        policy.stability_window_ms = 100;
        let mut supervisor = supervisor::Supervisor::new(policy);
        let mut actuator = EpochContinuityActuator {
            now: 0,
            ready_calls: 0,
            exit_pending: true,
            epoch_probes: 0,
            counted_restart_seen: false,
            reset_at: None,
        };

        let outcome = supervisor::run_supervision(&mut supervisor, &mut actuator);

        assert!(matches!(outcome, supervisor::Outcome::Stopped { .. }));
        assert!(
            actuator.counted_restart_seen,
            "the loop must first spend one restart"
        );
        assert_eq!(
            actuator.epoch_probes, 4,
            "A, B, B, B are the required observations"
        );
        assert_eq!(
            actuator.reset_at,
            Some(200),
            "epoch B starts a fresh monotonic stability window instead of inheriting A's time"
        );
        assert_eq!(supervisor.restart_count, 0);
    }

    #[test]
    fn replacement_launch_failure_traverses_shared_loop_and_real_writer() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("runtime").join("supervisor.v1.json");
        let host = Arc::new(EngineHost::default());
        let mut actuator = ReplacementFailureActuator {
            host: host.clone(),
            state_path: path.clone(),
            events: Vec::new(),
            exit_pending: true,
            now: 1_000,
        };
        let mut sup = supervisor::Supervisor::new(supervisor::load_policy());
        let outcome = supervisor::run_supervision(&mut sup, &mut actuator);
        assert!(matches!(outcome, supervisor::Outcome::Exhausted { .. }));
        let disk: StateRecord =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        assert_eq!(disk.state, "exhausted");
        assert!(disk
            .reason
            .as_deref()
            .unwrap()
            .starts_with("ENGINE_RESTART_EXHAUSTED:restart_failed:"));
        assert_eq!(actuator.events.last().unwrap().reason, disk.reason);
        assert_eq!(host.latest_record().unwrap().reason, disk.reason);
    }

    #[test]
    fn watcher_starts_once_joins_and_refuses_after_close() {
        let host = Arc::new(EngineHost::default());
        let ticks = Arc::new(AtomicUsize::new(0));
        let t = ticks.clone();
        host.ensure_watcher(move || {
            t.fetch_add(1, Ordering::SeqCst);
        })
        .unwrap();
        host.ensure_watcher(|| panic!("second watcher started"))
            .unwrap();
        host.begin_close();
        let after = ticks.load(Ordering::SeqCst);
        thread::sleep(Duration::from_millis(150));
        assert_eq!(ticks.load(Ordering::SeqCst), after);
        assert!(host.ensure_watcher(|| {}).is_err());
    }

    #[test]
    fn watcher_can_cancel_from_its_own_thread_without_self_join() {
        let host = Arc::new(EngineHost::default());
        let weak = Arc::downgrade(&host);
        let (sent, received) = std::sync::mpsc::channel();
        let sent = Mutex::new(Some(sent));
        host.ensure_watcher(move || {
            if let Some(host) = weak.upgrade() {
                host.begin_close();
                if let Some(sent) = sent.lock().unwrap().take() {
                    let _ = sent.send(());
                }
            }
        })
        .unwrap();
        received.recv_timeout(Duration::from_secs(2)).unwrap();
        assert!(
            host.should_continue(),
            "watcher cancellation leaves actor ownership intact"
        );
    }

    #[test]
    fn repeated_identical_manifest_tooltip_reaches_native_sink_once() {
        let host = EngineHost::default();
        let admitted = host.admit(sleeper()).unwrap();
        let calls = AtomicUsize::new(0);
        let mut residue = manifest(&host, "residue", admitted.pid.saturating_add(1), 9999);
        residue.lifecycle = Some("ERROR".into());
        assert!(host
            .observe_manifest_with_sinks(
                &residue,
                |_| {},
                |_| {
                    calls.fetch_add(1, Ordering::SeqCst);
                }
            )
            .is_none());
        let current = manifest(&host, "current", admitted.pid, 40404);
        assert!(host
            .observe_manifest_with_sinks(
                &current,
                |_| {},
                |_| {
                    calls.fetch_add(1, Ordering::SeqCst);
                }
            )
            .is_some());
        assert!(host
            .observe_manifest_with_sinks(
                &current,
                |_| {},
                |_| {
                    calls.fetch_add(1, Ordering::SeqCst);
                }
            )
            .is_some());
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        host.fixture_kill_and_reap();
    }

    /// Spec F §2.1 failure policy: an incarnation started without its shell job says so in the
    /// supervisor state file, and a contained one carries no such field.
    #[test]
    fn uncontained_incarnation_is_published_as_shell_unavailable() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("runtime").join("supervisor.v1.json");
        let host = EngineHost::default();
        let admitted = host
            .admit_with_job(
                &mut sleeper(),
                || {},
                |_| Err(containment::ContainmentError::Create(5)),
            )
            .unwrap();
        assert!(admitted.pid > 0);
        assert!(host.try_reap().is_none(), "the fallback child is running");
        host.publish_state(Some(&path), &terminal_record("x"), |event| {
            assert_eq!(
                event.containment.as_deref(),
                Some(SHELL_CONTAINMENT_UNAVAILABLE)
            );
        });
        let disk: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        assert_eq!(disk["containment"], "shell_unavailable");

        host.fixture_kill_and_reap();
        let admitted = host.admit(sleeper()).unwrap();
        host.publish_state(Some(&path), &terminal_record("x"), |_| {});
        let disk: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        assert!(
            disk.get("containment").is_none(),
            "a contained incarnation carries no flag: {disk}"
        );
        assert!(admitted.pid > 0);
        host.fixture_kill_and_reap();
    }

    /// The pids of the processes `parent` started, by Toolhelp's recorded parent id.
    #[cfg(windows)]
    fn children_of(parent: u32) -> Vec<u32> {
        use windows_sys::Win32::Foundation::{CloseHandle, INVALID_HANDLE_VALUE};
        use windows_sys::Win32::System::Diagnostics::ToolHelp::{
            CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W,
            TH32CS_SNAPPROCESS,
        };
        let snapshot = unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) };
        assert_ne!(snapshot, INVALID_HANDLE_VALUE);
        let mut entry: PROCESSENTRY32W = unsafe { std::mem::zeroed() };
        entry.dwSize = std::mem::size_of::<PROCESSENTRY32W>() as u32;
        let mut found = Vec::new();
        let mut more = unsafe { Process32FirstW(snapshot, &mut entry) } != 0;
        while more {
            if entry.th32ParentProcessID == parent {
                found.push(entry.th32ProcessID);
            }
            more = unsafe { Process32NextW(snapshot, &mut entry) } != 0;
        }
        unsafe { CloseHandle(snapshot) };
        found
    }

    #[cfg(windows)]
    fn alive(pid: u32) -> bool {
        use windows_sys::Win32::Foundation::{CloseHandle, STILL_ACTIVE};
        use windows_sys::Win32::System::Threading::{
            GetExitCodeProcess, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION,
        };
        let handle = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
        if handle.is_null() {
            return false;
        }
        let mut code = 0u32;
        let ok = unsafe { GetExitCodeProcess(handle, &mut code) };
        unsafe { CloseHandle(handle) };
        ok != 0 && code == STILL_ACTIVE as u32
    }

    /// S14 (shell side, Spec F §2.3): the supervisor's observation of an Engine exit ends what that
    /// Engine left running, before the observation returns.
    #[cfg(windows)]
    #[test]
    fn observed_engine_exit_ends_the_helpers_it_left_running() {
        let host = EngineHost::default();
        let mut command = Command::new("cmd.exe");
        // The "Engine" starts a helper and exits at once, leaving the helper running.
        command
            .args(["/c", "start /b ping -n 30 127.0.0.1 >NUL"])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null());
        let engine = host.admit(PreparedCommand { command }).unwrap();
        let deadline = Instant::now() + Duration::from_secs(10);
        let helpers = loop {
            let helpers: Vec<u32> = children_of(engine.pid)
                .into_iter()
                .filter(|pid| alive(*pid))
                .collect();
            if !helpers.is_empty() {
                break helpers;
            }
            assert!(
                Instant::now() < deadline,
                "the Engine never started its helper"
            );
            thread::sleep(Duration::from_millis(20));
        };
        let code = loop {
            if let Some(code) = host.try_reap() {
                break code;
            }
            assert!(Instant::now() < deadline, "the Engine never exited");
            thread::sleep(Duration::from_millis(20));
        };
        assert_eq!(code, 0);
        // The helper is gone when the observation returns. The console host is listed too: it was
        // created with the Engine, before the Engine joined its job, and ends with its last client,
        // the helper. S14's bound is one second for both.
        let bound = Instant::now() + Duration::from_secs(1);
        let mut survivors: Vec<u32> = helpers.clone();
        while !survivors.is_empty() && Instant::now() < bound {
            survivors.retain(|pid| alive(*pid));
            thread::sleep(Duration::from_millis(20));
        }
        survivors.retain(|pid| alive(*pid));
        assert!(
            survivors.is_empty(),
            "helpers outlived the observed Engine exit by 1 s: {survivors:?}"
        );
    }
    #[cfg(windows)]
    #[test]
    fn forced_shell_stop_observes_supervisor_forced() {
        let host = EngineHost::default();
        host.admit(sleeper()).unwrap();
        host.force_stop().unwrap();
        let until = Instant::now() + Duration::from_secs(2);
        let code = loop {
            if let Some(code) = host.try_reap() {
                break code;
            }
            assert!(Instant::now() < until, "force stop did not end the Engine");
            thread::sleep(Duration::from_millis(10));
        };
        assert_eq!(code, containment::JS_ENGINE_STOP as i32);
        assert_eq!(
            supervisor::describe_exit(code, &supervisor::load_policy()),
            "supervisor_forced"
        );
    }

    #[test]
    fn a_dropped_timed_out_job_is_not_an_empty_updater_witness() {
        let host = EngineHost::default();
        host.inner.lock().unwrap().predecessor_empty =
            Some(containment::JobEmpty::TimedOut { active: 2 });
        assert_eq!(
            host.await_contained_exit(Duration::ZERO).unwrap(),
            containment::JobEmpty::TimedOut { active: 2 }
        );
        assert!(!host.predecessor_job_empty());
        host.force_stop().unwrap();
        assert_eq!(
            host.await_contained_exit(Duration::ZERO).unwrap(),
            containment::JobEmpty::TimedOut { active: 2 }
        );
    }

    fn actor_fixture() -> (Arc<EngineHost>, tempfile::TempDir) {
        let dir = tempfile::tempdir().unwrap();
        let host = Arc::new(EngineHost::default());
        let spawn_host = host.clone();
        let mut actuator = crate::shell_actuator::ShellActuator::new(
            host.clone(),
            dir.path().to_path_buf(),
            move || {
                spawn_host.reset_for_successor()?;
                spawn_host.admit(sleeper()).map(|c| c.pid)
            },
            |_| {},
        );
        host.start_supervisor(move || {
            let mut policy = supervisor::load_policy();
            policy.cooldown_increment_ms = 100;
            policy.max_cooldown_ms = 100;
            let mut sup = supervisor::Supervisor::new(policy);
            supervisor::run_supervision(&mut sup, &mut actuator);
        })
        .unwrap();
        let until = Instant::now() + Duration::from_secs(2);
        while host.child_pid().is_none() {
            assert!(Instant::now() < until);
            thread::sleep(Duration::from_millis(5));
        }
        (host, dir)
    }
    #[test]
    fn prepared_upgrade_waits_for_actor_exit_accounting() {
        let host = Arc::new(EngineHost::default());
        assert!(
            host.wait_for_exit(Duration::ZERO),
            "never admitted needs no receipt"
        );
        let pid = host.admit(sleeper()).unwrap().pid;
        let dir = tempfile::tempdir().unwrap();
        let mut actuator = crate::shell_actuator::ShellActuator::new(
            host.clone(),
            dir.path().to_path_buf(),
            move || Ok(pid),
            |_| {},
        );
        use crate::supervisor::Actuator;
        actuator.spawn_engine().unwrap(); // records PID and native birth before the held-handle stop
        host.force_stop().unwrap();
        let until = Instant::now() + Duration::from_secs(5);
        let code = loop {
            if let Some(code) = host.try_reap() {
                break code;
            }
            assert!(Instant::now() < until);
            thread::sleep(Duration::from_millis(10));
        };
        assert_eq!(
            host.wait_for_exit(Duration::from_millis(20)),
            false,
            "reaped is not the actor's persisted exit acknowledgement"
        );
        host.acknowledge_exit(code);
        assert!(host.wait_for_exit(Duration::ZERO));
    }

    #[test]
    fn upgrade_stop_waits_for_actor_accounting_after_the_job_cleanup_floor() {
        let dir = tempfile::tempdir().unwrap();
        let host = Arc::new(EngineHost::default());
        let pid = host.admit(sleeper()).unwrap().pid;
        host.acquire_replacement_hold(Duration::ZERO).unwrap();
        let (accounted_tx, accounted_rx) = std::sync::mpsc::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel();
        let mut actuator = crate::shell_actuator::ShellActuator::new(
            host.clone(),
            dir.path().to_path_buf(),
            move || Ok(pid),
            move |record| {
                if record.state == "stopped" {
                    // The actor has reaped the real child and persisted its exit, but its
                    // terminal publication is still pending, as after slow job cleanup/I/O.
                    accounted_tx.send(()).unwrap();
                    release_rx.recv().unwrap();
                }
            },
        );
        host.start_supervisor(move || {
            let mut sup = supervisor::Supervisor::new(supervisor::load_policy());
            supervisor::run_supervision(&mut sup, &mut actuator);
        })
        .unwrap();
        let (result_tx, result_rx) = std::sync::mpsc::channel();
        let stop_host = host.clone();
        let waiter = thread::spawn(move || {
            result_tx
                .send(stop_host.stop_owned_engine(Duration::ZERO))
                .unwrap();
        });
        accounted_rx.recv_timeout(Duration::from_secs(10)).unwrap();
        assert!(host.wait_for_exit(Duration::ZERO));
        // Hold the actor beyond the old two-second allowance, but within the job's
        // declared five-second cleanup floor. A timeout must not stand in for its result.
        let early = result_rx.recv_timeout(Duration::from_millis(
            containment::JOB_TERMINATE_CONFIRM_MS + 250,
        ));
        release_tx.send(()).unwrap();
        let result =
            early.unwrap_or_else(|_| result_rx.recv_timeout(Duration::from_secs(10)).unwrap());
        waiter.join().unwrap();
        host.supervisor
            .lock()
            .unwrap()
            .take()
            .unwrap()
            .join()
            .unwrap();
        assert_eq!(
            result,
            Ok(Some(pid)),
            "the accounted upgrade must permit handoff"
        );
        assert!(host.child_pid().is_none());
        assert!(host.predecessor_job_empty());
        assert_eq!(
            actor_records(dir.path())
                .iter()
                .filter(|r| r["type"] == "engine-exit")
                .count(),
            1
        );
    }

    #[test]
    fn upgrade_stop_accepts_crash_before_command_consumption() {
        let dir = tempfile::tempdir().unwrap();
        let host = Arc::new(EngineHost::default());
        let pid = host.admit(sleeper()).unwrap().pid;
        let (started_tx, started_rx) = std::sync::mpsc::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel();
        let mut gate = Some((started_tx, release_rx));
        let mut actuator = crate::shell_actuator::ShellActuator::new(
            host.clone(),
            dir.path().to_path_buf(),
            move || Ok(pid),
            move |_| {
                if let Some((started, release)) = gate.take() {
                    started.send(()).unwrap();
                    release.recv().unwrap();
                }
            },
        );
        host.acquire_replacement_hold(Duration::ZERO).unwrap();
        let actor = thread::spawn(move || {
            let mut sup = supervisor::Supervisor::new(supervisor::load_policy());
            supervisor::run_supervision(&mut sup, &mut actuator);
        });
        started_rx.recv_timeout(Duration::from_secs(5)).unwrap();
        let waiter_host = host.clone();
        let waiter = thread::spawn(move || waiter_host.stop_owned_engine(Duration::from_secs(2)));
        let until = Instant::now() + Duration::from_secs(5);
        while host.control.lock().unwrap().pending.is_none() {
            assert!(Instant::now() < until);
            thread::sleep(Duration::from_millis(1));
        }
        host.with_child_mut(|child| {
            child.kill().unwrap();
            child.wait().unwrap();
        });
        // The loop's first poll observes the crash before consuming the posted upgrade.
        release_tx.send(()).unwrap();
        actor.join().unwrap();
        let result = waiter.join().unwrap();
        assert_eq!(
            result.is_ok(),
            true,
            "accounted crash under replacement hold must permit handoff: {result:?}"
        );
        assert_eq!(result.unwrap(), Some(pid));
        assert!(host.predecessor_job_empty());
        let records = actor_records(dir.path());
        let exits: Vec<_> = records
            .iter()
            .filter(|r| r["type"] == "engine-exit")
            .collect();
        assert_eq!(exits.len(), 1);
        assert_eq!(exits[0]["exitClass"], "UNEXPECTED");
        assert!(host.child_pid().is_none());
    }
    fn actor_records(dir: &Path) -> Vec<serde_json::Value> {
        crate::exit_ledger::read_ledger(
            &crate::exit_ledger::ledger_directory(dir),
            crate::exit_ledger::Family::Engine,
        )
        .unwrap()
        .records
    }
    fn quit_actor(host: &EngineHost) {
        assert_eq!(
            host.post_stop("quit", None, Duration::from_millis(400))
                .is_ok(),
            true,
            "quit must be posted to the actor"
        );
        assert!(host.wait_terminal(host.now_ms() + 2500).is_some());
        assert!(host.child_pid().is_none());
    }
    #[test]
    fn hold_returns_at_once_and_engine_stays_supervised() {
        let (host, _dir) = actor_fixture();
        let now = Instant::now();
        host.acquire_replacement_hold(Duration::from_secs(1))
            .unwrap();
        assert!(now.elapsed() < Duration::from_millis(100));
        assert!(host.supervisor_running());
        assert!(host.should_continue());
        quit_actor(&host);
    }
    #[test]
    fn actor_upgrade_records_intent_and_empty_job_before_handoff() {
        let (host, dir) = actor_fixture();
        host.acquire_replacement_hold(Duration::from_secs(1))
            .unwrap();
        let result = host.stop_owned_engine(Duration::from_millis(400));
        assert!(result.is_ok());
        let pid = result.unwrap();
        assert!(pid.is_some());
        assert!(host.predecessor_job_empty());
        let records = actor_records(dir.path());
        let observed = records.iter().find(|r| r["type"] == "engine-exit");
        assert_eq!(observed.is_some(), true, "the actor must record the exit");
        let exit = observed.unwrap();
        assert_eq!(exit["reason"], "UPGRADE");
        assert_eq!(exit["exitClass"], "REQUESTED");
        assert_eq!(exit["contract"]["action"], "stop");
        assert!(
            matches!(host.terminal_outcome(),Some(supervisor::Outcome::Stopped {reason,..}) if reason=="upgrade")
        );
    }
    #[test]
    fn exit_while_held_is_unexpected_and_not_restarted() {
        let (host, dir) = actor_fixture();
        host.acquire_replacement_hold(Duration::from_secs(1))
            .unwrap();
        host.with_child_mut(|child| {
            child.kill().unwrap();
        });
        assert!(host.wait_for_exit(Duration::from_secs(2)));
        thread::sleep(Duration::from_millis(200));
        let records = actor_records(dir.path());
        assert_eq!(
            records
                .iter()
                .filter(|r| r["type"] == "engine-start")
                .count(),
            1
        );
        let observed = records.iter().find(|r| r["type"] == "engine-exit");
        assert!(observed.is_some(), "held exit must be recorded");
        assert_eq!(observed.unwrap()["exitClass"], "UNEXPECTED");
        assert!(host.supervisor_running());
        quit_actor(&host);
    }
    #[test]
    fn quit_while_held_wins_and_refuses_upgrade() {
        let (host, dir) = actor_fixture();
        host.acquire_replacement_hold(Duration::from_secs(1))
            .unwrap();
        quit_actor(&host);
        assert!(host.post_stop("upgrade", None, Duration::ZERO).is_err());
        assert!(host
            .upgrade_commit("preparation", "nonce", Duration::from_millis(400))
            .is_err());
        assert_eq!(
            actor_records(dir.path())
                .iter()
                .filter(|r| r["type"] == "engine-exit")
                .count(),
            1
        );
        assert!(host.predecessor_job_empty());
    }
    #[test]
    fn release_after_failed_prepare_resumes_the_same_actor() {
        let (host, dir) = actor_fixture();
        host.acquire_replacement_hold(Duration::from_secs(1))
            .unwrap();
        host.with_child_mut(|child| {
            child.kill().unwrap();
        });
        assert!(host.wait_for_exit(Duration::from_secs(2)));
        host.release_replacement_hold().unwrap();
        let until = Instant::now() + Duration::from_secs(2);
        while host.child_pid().is_none() {
            assert!(Instant::now() < until);
            thread::sleep(Duration::from_millis(5));
        }
        assert!(host.supervisor_running());
        while actor_records(dir.path())
            .iter()
            .filter(|r| r["type"] == "engine-start")
            .count()
            != 2
        {
            assert!(Instant::now() < until);
            thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(
            actor_records(dir.path())
                .iter()
                .filter(|r| r["type"] == "engine-start")
                .count(),
            2
        );
        quit_actor(&host);
    }
}

#[cfg(test)]
mod hold_api_tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, Ordering};
    #[test]
    fn hold_api_preserves_live_engine_supervision() {
        let host = Arc::new(EngineHost::default());
        let mut command = if cfg!(windows) {
            let mut command = Command::new("ping.exe");
            command.args(["-n", "60", "127.0.0.1"]);
            command
        } else {
            let mut command = crate::containment::engine_command("sleep").unwrap();
            command.arg("60");
            command
        };
        command.stdout(std::process::Stdio::null());
        host.admit(PreparedCommand { command }).unwrap();
        let running = Arc::new(AtomicBool::new(true));
        let actor_host = host.clone();
        let signal = running.clone();
        host.start_supervisor(move || {
            while signal.load(Ordering::SeqCst) && actor_host.should_continue() {
                std::thread::sleep(Duration::from_millis(5));
            }
        })
        .unwrap();
        std::thread::sleep(Duration::from_millis(20));
        let started = Instant::now();
        let result = host.acquire_replacement_hold(Duration::from_millis(300));
        let continues = host.should_continue();
        let actor = host
            .supervisor
            .lock()
            .unwrap()
            .as_ref()
            .is_some_and(|h| !h.is_finished());
        let live = host.child_pid().is_some() && host.try_reap().is_none();
        eprintln!("hold result={result:?}, elapsed={:?}, continues={continues}, actor={actor}, live={live}",started.elapsed());
        running.store(false, Ordering::SeqCst);
        let _ = host.force_stop();
        while host.try_reap().is_none() {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(
            result.is_ok(),
            "hold returns without joining/cancelling the actor"
        );
        assert_eq!(
            continues && actor && live,
            true,
            "hold must preserve supervision of the live Engine"
        );
    }
}
