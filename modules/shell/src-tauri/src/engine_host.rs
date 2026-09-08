//! Tauri-free ownership core for the Engine child and its host lifetime.

use crate::binding::{apply_manifest_observation, Binding, BindingChange, ManifestFields};
use crate::supervisor::{self, StateRecord};
use std::path::Path;
use std::process::{Child, ChildStderr, ChildStdout, Command};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

#[derive(Debug, Clone, PartialEq, Eq)]
enum Discovery {
    AwaitingSuccessor {
        predecessor: Option<String>,
    },
    AwaitingManifest {
        pid: u32,
        predecessor: Option<String>,
    },
    Bound {
        pid: u32,
        instance_id: String,
    },
}

struct OwnedChild {
    child: Child,
    generation: u64,
}

struct HostInner {
    child: Option<OwnedChild>,
    closing: bool,
    replacement_held: bool,
    next_generation: u64,
    binding: Binding,
    discovery: Discovery,
    spawn_error: Option<String>,
    latest_record: Option<StateRecord>,
    last_tooltip: Option<String>,
}

impl Default for HostInner {
    fn default() -> Self {
        Self {
            child: None,
            closing: false,
            replacement_held: false,
            next_generation: 1,
            binding: Binding::default(),
            discovery: Discovery::AwaitingSuccessor { predecessor: None },
            spawn_error: None,
            latest_record: None,
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
}

impl Default for EngineHost {
    fn default() -> Self {
        Self {
            inner: Mutex::new(HostInner::default()),
            watcher: Arc::new((Mutex::new(WatcherState::default()), Condvar::new())),
            supervisor: Mutex::new(None),
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
        let mut inner = self.inner.lock().expect("engine host mutex poisoned");
        if inner.closing {
            return Err("Engine host is closing; launch refused".into());
        }
        if inner.replacement_held {
            return Err("Engine replacement is held by the updater; launch refused".into());
        }
        if inner.child.is_some() {
            return Err("Engine child is already installed".into());
        }
        before_spawn();
        let mut child = prepared
            .command
            .spawn()
            .map_err(|e| format!("spawn java failed: {e}"))?;
        let pid = child.id();
        let stdout = child.stdout.take();
        let stderr = child.stderr.take();
        let generation = inner.next_generation;
        inner.next_generation = inner.next_generation.saturating_add(1);
        let predecessor = match &inner.discovery {
            Discovery::AwaitingSuccessor { predecessor } => predecessor.clone(),
            _ => inner.binding.instance_id.clone(),
        };
        inner.discovery = Discovery::AwaitingManifest { pid, predecessor };
        inner.spawn_error = None;
        inner.child = Some(OwnedChild { child, generation });
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
        let predecessor = inner.binding.instance_id.clone();
        inner.binding.port = None;
        inner.binding.token = None;
        inner.discovery = Discovery::AwaitingSuccessor { predecessor };
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
        let (pid, predecessor) = match &inner.discovery {
            Discovery::AwaitingManifest { pid, predecessor } => (*pid, predecessor.clone()),
            Discovery::Bound { pid, instance_id } if instance_id == id => (*pid, None),
            _ => return None,
        };
        if inner.child.as_ref().map(|owned| owned.child.id()) != Some(pid) {
            return None;
        }
        if manifest.pid != Some(pid) || predecessor.as_deref() == Some(id) {
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

    /// Attribute an HTTP observation only to the same admitted child and binding throughout it.
    pub(crate) fn observe_current_binding(&self, probe: impl FnOnce(u16) -> bool) -> Option<Binding> {
        let (generation, binding) = {
            let inner = self.inner.lock().expect("engine host mutex poisoned");
            let child = inner.child.as_ref()?;
            if inner.closing || !matches!(&inner.discovery, Discovery::Bound { pid, instance_id }
                if *pid == child.child.id() && Some(instance_id) == inner.binding.instance_id.as_ref()) {
                return None;
            }
            (child.generation, inner.binding.clone())
        };
        if !probe(binding.port?) {
            return None;
        }
        let inner = self.inner.lock().expect("engine host mutex poisoned");
        (!inner.closing && inner.child.as_ref()?.generation == generation && inner.binding == binding)
            .then_some(binding)
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
    pub(crate) fn try_reap(&self) -> Option<i32> {
        let mut inner = self.inner.lock().expect("engine host mutex poisoned");
        let owned = inner.child.as_mut()?;
        match owned.child.try_wait() {
            Ok(Some(status)) => {
                inner.child.take();
                let predecessor = inner.binding.instance_id.clone();
                inner.discovery = Discovery::AwaitingSuccessor { predecessor };
                Some(status.code().unwrap_or(1))
            }
            _ => None,
        }
    }
    pub(crate) fn wait_for_exit(&self, timeout: Duration) -> bool {
        let deadline = Instant::now() + timeout;
        loop {
            if self.try_reap().is_some() || self.child_pid().is_none() {
                return true;
            }
            if Instant::now() >= deadline {
                return false;
            }
            thread::sleep(Duration::from_millis(100));
        }
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
        !inner.closing && !inner.replacement_held
    }

    /// Own exactly one supervision loop. Replacement waits for this loop to end before proceeding.
    pub(crate) fn start_supervisor(&self, run: impl FnOnce() + Send + 'static) -> Result<(), String> {
        self.install_supervisor(run, || {})
    }

    fn install_supervisor(&self, run: impl FnOnce() + Send + 'static, before_spawn: impl FnOnce()) -> Result<(), String> {
        let mut supervisor = self.supervisor.lock().expect("supervisor mutex poisoned");
        if !self.should_continue() {
            return Err("Engine host is closed or held; supervision refused".into());
        }
        if supervisor.is_some() {
            return Err("A supervision generation is already installed".into());
        }
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
        let deadline = Instant::now() + timeout;
        loop {
            {
                let mut supervisor = self.supervisor.lock().expect("supervisor mutex poisoned");
                if supervisor.as_ref().is_none_or(JoinHandle::is_finished) {
                    if let Some(handle) = supervisor.take() {
                        handle.join().map_err(|_| "Supervision generation panicked; Engine remains held")?;
                    }
                    return Ok(());
                }
            }
            if Instant::now() >= deadline {
                return Err("Supervision did not stop in time; Engine remains held".into());
            }
            thread::sleep(Duration::from_millis(20));
        }
    }

    /// Used only after the updater has established that resuming the current installation is safe.
    pub(crate) fn release_replacement_hold(&self) -> Result<(), String> {
        let supervisor = self.supervisor.lock().expect("supervisor mutex poisoned");
        let mut inner = self.inner.lock().expect("engine host mutex poisoned");
        if inner.closing || !inner.replacement_held || supervisor.is_some() {
            return Err("Cannot resume a closed, unheld or still-supervised Engine host".into());
        }
        inner.replacement_held = false;
        Ok(())
    }
    pub(crate) fn take_child(&self) -> Option<Child> {
        let mut inner = self.inner.lock().expect("engine host mutex poisoned");
        let child = inner.child.take().map(|c| c.child);
        if child.is_some() {
            let predecessor = inner.binding.instance_id.clone();
            inner.discovery = Discovery::AwaitingSuccessor { predecessor };
        }
        child
    }

    pub(crate) fn kill_and_reap(&self) {
        if let Some(mut child) = self.take_child() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }

    pub(crate) fn replacement_held(&self) -> bool {
        self.inner.lock().expect("Engine host mutex poisoned").replacement_held
    }

    /// Installer handoff retains the exact child handle when bounded termination cannot finish.
    pub(crate) fn stop_owned_engine(&self, timeout: Duration) -> Result<Option<u32>, String> {
        let pid = self.child_pid();
        self.try_reap();
        if self.child_pid().is_none() { return Ok(pid); }
        let mut kill_error = None;
        self.with_child_mut(|child| {
            if let Err(error) = child.kill() { kill_error = Some(error.to_string()); }
        });
        if self.wait_for_exit(timeout) {
            Ok(pid)
        } else {
            Err(format!("Owned Engine did not stop; replacement remains held: {}",
                kill_error.unwrap_or_else(|| "termination deadline elapsed".into())))
        }
    }

    pub(crate) fn ensure_watcher(
        self: &Arc<Self>,
        tick: impl Fn() + Send + Sync + 'static,
    ) -> Result<(), String> {
        let (lock, _cv) = &*self.watcher;
        let mut watcher = lock.lock().expect("watcher mutex poisoned");
        if !self.should_continue() {
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
        self.inner
            .lock()
            .expect("engine host mutex poisoned")
            .latest_record = Some(record.clone());
        if let Some(path) = path {
            if let Some(parent) = path.parent() {
                let _ = std::fs::create_dir_all(parent);
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

    fn sleeper() -> PreparedCommand {
        let mut command = if cfg!(windows) {
            let mut c = Command::new("ping.exe");
            c.args(["-n", "30", "127.0.0.1"]);
            c
        } else {
            let mut c = Command::new("sleep");
            c.arg("30");
            c
        };
        command
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped());
        PreparedCommand { command }
    }

    #[test]
    fn hold_linearizes_with_supervisor_installation_and_joins_that_generation() {
        let host = Arc::new(EngineHost::default());
        let (entered_tx, entered_rx) = std::sync::mpsc::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel();
        let installing = host.clone();
        let running = host.clone();
        let install = thread::spawn(move || installing.install_supervisor(
            move || while running.should_continue() { thread::sleep(Duration::from_millis(2)); },
            move || { entered_tx.send(()).unwrap(); release_rx.recv().unwrap(); },
        ));
        entered_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        let holding = host.clone();
        let (hold_started_tx, hold_started_rx) = std::sync::mpsc::channel();
        let hold = thread::spawn(move || {
            hold_started_tx.send(()).unwrap();
            holding.acquire_replacement_hold(Duration::from_secs(2))
        });
        hold_started_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        thread::sleep(Duration::from_millis(50));
        let held_before_install_finished = host.replacement_held();
        release_tx.send(()).unwrap();
        install.join().unwrap().unwrap();
        hold.join().unwrap().unwrap();
        assert!(!held_before_install_finished, "hold publication overtook supervisor installation");
        assert!(host.replacement_held());
        assert!(host.supervisor.lock().unwrap().is_none());
    }

    #[test]
    fn stop_witness_keeps_pid_of_an_already_exited_owned_child() {
        let host = EngineHost::default();
        let pid = host.admit(sleeper()).unwrap().pid;
        // Leave the completed Child owned so the updater performs the actual reap.
        host.with_child_mut(|child| { child.kill().unwrap(); child.wait().unwrap(); });
        host.acquire_replacement_hold(Duration::from_secs(2)).unwrap();
        assert_eq!(host.stop_owned_engine(Duration::from_secs(2)).unwrap(), Some(pid));
        assert_eq!(host.child_pid(), None);
        assert_eq!(host.stop_owned_engine(Duration::from_secs(2)).unwrap(), None);
    }

    fn manifest(id: &str, pid: u32, port: u16) -> ManifestFields {
        ManifestFields {
            api_port: Some(port),
            session_token: Some(format!("token-{id}")),
            lifecycle: Some("READY".into()),
            instance_id: Some(id.into()),
            pid: Some(pid),
        }
    }

    fn terminal_record(reason: &str) -> StateRecord {
        StateRecord {
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
        assert!(host.observe_current_binding(|_| panic!("no child to probe")).is_none());
        let child = host.admit(sleeper()).unwrap();
        assert!(host.observe_current_binding(|_| panic!("no admitted manifest")).is_none());
        host.observe_manifest(&manifest("current", child.pid, 40404));
        assert!(host.observe_current_binding(|port| port == 40404).is_some());
        assert!(host.observe_current_binding(|_| {
            host.begin_close();
            true
        }).is_none());
        if let Some(mut child) = host.take_child() {
            child.kill().unwrap();
            child.wait().unwrap();
        }
    }

    #[test]
    fn incarnation_reset_never_reopens_a_closed_host() {
        let host = EngineHost::default();
        host.begin_close();
        host.reset_for_successor().unwrap();
        assert!(!host.should_continue());
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
            while run_host.should_continue() { thread::sleep(Duration::from_millis(5)); }
        }).unwrap();
        assert!(host.start_supervisor(|| panic!("duplicate supervisor")).is_err());
        host.acquire_replacement_hold(Duration::from_secs(1)).unwrap();
        assert_eq!(loops.load(Ordering::SeqCst), 1);
        host.kill_and_reap();
        assert!(host.admit(sleeper()).unwrap_err().contains("held"));
        host.release_replacement_hold().unwrap();
        assert!(host.release_replacement_hold().is_err());
        host.reset_for_successor().unwrap();
        host.admit(sleeper()).unwrap();
        let run_host = host.clone();
        let run_loops = loops.clone();
        host.start_supervisor(move || {
            run_loops.fetch_add(1, Ordering::SeqCst);
            while run_host.should_continue() { thread::sleep(Duration::from_millis(5)); }
        }).unwrap();
        host.acquire_replacement_hold(Duration::from_secs(1)).unwrap();
        assert_eq!(loops.load(Ordering::SeqCst), 2);
        host.kill_and_reap();
        host.begin_close();
        assert!(host.release_replacement_hold().is_err(), "quit remains permanent");
    }

    #[test]
    fn failed_supervisor_join_keeps_replacement_held() {
        let host = EngineHost::default();
        let (release, wait) = std::sync::mpsc::channel();
        host.start_supervisor(move || { wait.recv().unwrap(); }).unwrap();
        assert!(host.acquire_replacement_hold(Duration::from_millis(25)).is_err());
        assert!(host.release_replacement_hold().is_err());
        assert!(host.admit(sleeper()).unwrap_err().contains("held"));
        release.send(()).unwrap();
        // Keep the failed hold until explicit recovery; dropping the host does not spawn anything.
        host.begin_close();
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
            close_host.kill_and_reap();
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
        host.kill_and_reap();
        host.reset_for_successor().unwrap();
        let second = host.admit(sleeper()).unwrap();
        assert!(!host.stdout_closed(first.generation));
        assert!(!host.has_spawn_error());
        host.kill_and_reap();
        assert_ne!(first.generation, second.generation);
    }

    #[test]
    fn real_reset_rejects_stale_manifests_and_emits_once_for_successor() {
        let host = EngineHost::default();
        let first = host.admit(sleeper()).unwrap();
        assert!(
            !host
                .observe_manifest(&manifest("old", first.pid, 1111))
                .unwrap()
                .restarted
        );
        let mut child = host.take_child().unwrap();
        let _ = child.kill();
        let _ = child.wait();
        assert_eq!(
            host.observe_manifest(&manifest("ghost", first.pid, 9999)),
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
            host.observe_manifest(&manifest("residue", first.pid, 9999)),
            None,
            "no successor is installed, so no manifest is admissible"
        );

        let second = host.admit(sleeper()).unwrap();
        let mut events = 0;
        host.observe_manifest_with_sinks(
            &manifest("old", second.pid, 1111),
            |_| events += 1,
            |_| {},
        );
        host.observe_manifest_with_sinks(
            &manifest("new", first.pid, 2222),
            |_| events += 1,
            |_| {},
        );
        host.observe_manifest_with_sinks(
            &manifest("new", second.pid, 2222),
            |_| events += 1,
            |_| {},
        );
        host.observe_manifest_with_sinks(
            &manifest("new", second.pid, 2222),
            |_| events += 1,
            |_| {},
        );
        assert_eq!(events, 1);
        assert_eq!(host.binding_snapshot().instance_id.as_deref(), Some("new"));
        let mut child = host.take_child().unwrap();
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
        fn probe_essential_ready(&mut self) -> bool {
            true
        }
        fn observed_request_reason(&mut self) -> Option<String> {
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
        fn now_ms(&mut self) -> u64 {
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
        assert!(!host.should_continue());
    }

    #[test]
    fn repeated_identical_manifest_tooltip_reaches_native_sink_once() {
        let host = EngineHost::default();
        let admitted = host.admit(sleeper()).unwrap();
        let calls = AtomicUsize::new(0);
        let mut residue = manifest("residue", admitted.pid.saturating_add(1), 9999);
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
        let current = manifest("current", admitted.pid, 40404);
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
        host.kill_and_reap();
    }
}
