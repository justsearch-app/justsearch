//! Shared-API lifetime witnesses. No candidate actuator is copied into the before-state.
//! This does not substitute for the actor's ledger, quit or upgrade-ticket checks.
#![cfg(windows)]
use super::*;
use crate::engine_host::{EngineHost, PreparedCommand};
use std::sync::atomic::{AtomicBool, AtomicUsize};
use std::time::Instant;

struct Witness {
    root: tempfile::TempDir,
    host: Arc<EngineHost>,
    running: Arc<AtomicBool>,
    alive: Arc<AtomicBool>,
    ticks: Arc<AtomicUsize>,
    done: Mutex<std::sync::mpsc::Receiver<()>>,
    pid: u32,
    creation_time: u64,
    resumes: AtomicUsize,
}

#[cfg(windows)]
fn creation_time(child: &std::process::Child) -> u64 {
    use std::os::windows::io::AsRawHandle;
    use windows_sys::Win32::Foundation::FILETIME;
    use windows_sys::Win32::System::Threading::GetProcessTimes;
    let mut times = [FILETIME {
        dwLowDateTime: 0,
        dwHighDateTime: 0,
    }; 4];
    let ok = unsafe {
        GetProcessTimes(
            child.as_raw_handle() as _,
            &mut times[0],
            &mut times[1],
            &mut times[2],
            &mut times[3],
        )
    };
    assert_ne!(ok, 0, "record the owned child's creation time at admission");
    (u64::from(times[0].dwHighDateTime) << 32) | u64::from(times[0].dwLowDateTime)
}

impl Witness {
    fn new() -> Self {
        let host = Arc::new(EngineHost::default());
        let mut command = std::process::Command::new("ping.exe");
        command
            .args(["-n", "60", "127.0.0.1"])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null());
        let pid = host.admit(PreparedCommand { command }).unwrap().pid;
        let mut birth = None;
        host.with_child_mut(|child| birth = Some(creation_time(child)));
        let creation_time = birth.unwrap();
        eprintln!("witness child started: pid={pid}, creationFileTime={creation_time}");
        let running = Arc::new(AtomicBool::new(true));
        let alive = Arc::new(AtomicBool::new(false));
        let ticks = Arc::new(AtomicUsize::new(0));
        let (entered, ready) = std::sync::mpsc::channel();
        let (ended, done) = std::sync::mpsc::channel();
        let actor_host = host.clone();
        let signal = running.clone();
        let actor_alive = alive.clone();
        let actor_ticks = ticks.clone();
        host.start_supervisor(move || {
            actor_alive.store(true, Ordering::SeqCst);
            entered.send(std::thread::current().id()).unwrap();
            while signal.load(Ordering::SeqCst) && actor_host.should_continue() {
                actor_ticks.fetch_add(1, Ordering::SeqCst);
                std::thread::sleep(Duration::from_millis(5));
            }
            actor_alive.store(false, Ordering::SeqCst);
            let _ = ended.send(());
        })
        .unwrap();
        let actor_id = ready.recv_timeout(Duration::from_secs(2)).unwrap();
        eprintln!("witness original actor entered: {actor_id:?}");
        Self {
            root: tempfile::tempdir().unwrap(),
            host,
            running,
            alive,
            ticks,
            done: Mutex::new(done),
            pid,
            creation_time,
            resumes: AtomicUsize::new(0),
        }
    }

    async fn failed_prepare(&self) -> Result<(), String> {
        let path = self.root.path().join("staged/attempt-1/installer.exe");
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(&path, b"MZfixture").unwrap();
        let staged = StagedArtifact {
            path: path.to_string_lossy().into_owned(),
            sha256: sha256_bytes(b"MZfixture"),
            size: 9,
        };
        let descriptor = ReleaseDescriptor {
            schema_version: 1,
            sequence: 3,
            version: "1.1.0".into(),
            channel: "stable".into(),
            target: "windows-x86_64".into(),
            metadata_key_id: "fixture".into(),
            metadata_root_policy: "OFFLINE_LONG_LIVED_V1".into(),
            artifact: ReleaseArtifact {
                url: "https://example.test/installer.exe".into(),
                sha256: staged.sha256.clone(),
                size: staged.size,
                signature: "fixture".into(),
                public_key: "fixture".into(),
            },
            compatibility: vec![],
        };
        finish_staged_update(
            self.root.path(),
            &UpdateCoordinator::default(),
            self,
            &descriptor,
            staged,
            "attempt-1".into(),
            "1.0.0".into(),
        )
        .await
    }
}

impl Drop for Witness {
    fn drop(&mut self) {
        self.running.store(false, Ordering::SeqCst);
        self.done
            .lock()
            .unwrap()
            .recv_timeout(Duration::from_secs(2))
            .unwrap();
        // Check the handle against the identity captured immediately after admission.
        self.host.with_child_mut(|child| {
            assert_eq!(
                (child.id(), creation_time(child)),
                (self.pid, self.creation_time)
            );
        });
        eprintln!(
            "witness child cleanup: pid={}, creationFileTime={}",
            self.pid, self.creation_time
        );
        self.host.force_stop().unwrap();
        let until = Instant::now() + Duration::from_secs(2);
        while self.host.child_pid().is_some() && self.host.try_reap().is_none() {
            assert!(Instant::now() < until, "owned child cleanup failed");
            std::thread::sleep(Duration::from_millis(5));
        }
    }
}

impl UpgradeHandoff for Witness {
    fn hold(&self) -> Result<(), String> {
        self.host
            .acquire_replacement_hold(Duration::from_millis(300))
    }
    fn is_held(&self) -> bool {
        self.host.replacement_held()
    }
    fn engine_available(&self) -> bool {
        self.host.child_pid().is_some()
    }
    fn child_pid(&self) -> Option<u32> {
        self.host.child_pid()
    }
    fn stop_unrecoverable(&self) -> Result<Option<u32>, String> {
        panic!("prepare must fail first")
    }
    async fn prepare(&self) -> Result<PrepareResponse, String> {
        assert!(self.is_held());
        assert_eq!(
            self.child_pid(),
            Some(self.pid),
            "prepare runs against the admitted live child"
        );
        Err("injected failed prepare".into())
    }
    async fn cancel(&self, _: &str, _: &str) -> Result<serde_json::Value, String> {
        panic!("no preparation receipt")
    }
    async fn commit(&self, _: &str, _: &str) -> Result<ShutdownReceipt, String> {
        panic!("prepare failed")
    }
    fn wait_for_exit(&self, _: Duration) -> bool {
        panic!("prepare failed")
    }
    fn await_contained_exit(&self, _: Duration) -> Result<crate::containment::JobEmpty, String> {
        panic!("prepare failed")
    }
    fn terminate_contained(&self) -> Result<(), String> {
        panic!("prepare failed")
    }
    fn resume(&self) -> Result<(), String> {
        self.resumes.fetch_add(1, Ordering::SeqCst);
        self.host.release_replacement_hold()
    }
    fn launch(&self, _: &Path) -> Result<(u32, u128), String> {
        panic!("failed prepare must not launch")
    }
}

#[test]
fn hold_returns_at_once_and_engine_stays_supervised() {
    let witness = Witness::new();
    let started = Instant::now();
    let result = witness.hold();
    let elapsed = started.elapsed();
    let continues = witness.host.should_continue();
    let actor = witness.alive.load(Ordering::SeqCst);
    let live = witness.host.child_pid() == Some(witness.pid) && witness.host.try_reap().is_none();
    eprintln!("hold result={result:?}, elapsed={elapsed:?}, continues={continues}, actor={actor}, live={live}");
    assert_eq!(
        result.is_ok() && elapsed < Duration::from_millis(100) && continues && actor && live,
        true,
        "hold must return at once and preserve supervision of the live Engine"
    );
}

#[tokio::test]
async fn release_after_failed_prepare_resumes_the_same_actor() {
    let witness = Witness::new();
    let result = witness.failed_prepare().await;
    assert!(
        result
            .as_ref()
            .is_err_and(|e| e.contains("injected failed prepare")),
        "both versions must reach failed prepare: {result:?}"
    );
    let ticks = witness.ticks.load(Ordering::SeqCst);
    std::thread::sleep(Duration::from_millis(30));
    let same_actor =
        witness.alive.load(Ordering::SeqCst) && witness.ticks.load(Ordering::SeqCst) > ticks;
    let released = !witness.host.replacement_held();
    let live = witness.host.child_pid() == Some(witness.pid) && witness.host.try_reap().is_none();
    eprintln!("failed prepare={result:?}, released={released}, same_actor={same_actor}, live={live}, resumes={}",
        witness.resumes.load(Ordering::SeqCst));
    assert_eq!(
        same_actor, true,
        "failed prepare must resume the original actor; hold must not end it"
    );
    assert!(
        released && live && witness.resumes.load(Ordering::SeqCst) == 1,
        "failed prepare must release admission exactly once with the same live Engine"
    );
}
