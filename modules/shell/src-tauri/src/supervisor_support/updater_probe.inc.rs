// Only included in supervisor-conformance. Inject external edges into the unchanged coordinator.
struct ProbeEdge {
    host: Arc<crate::engine_host::EngineHost>,
    action: String,
    helper: Mutex<std::process::Child>,
    launches: std::sync::atomic::AtomicUsize,
    empty_checks: std::sync::atomic::AtomicUsize,
    terminates: std::sync::atomic::AtomicUsize,
    resumes: std::sync::atomic::AtomicUsize,
}
impl UpgradeHandoff for ProbeEdge {
    fn hold(&self) -> Result<(), String> {
        self.host.acquire_replacement_hold(Duration::ZERO)
    }
    fn quitting(&self) -> bool {
        self.host.quit_started()
    }
    fn is_held(&self) -> bool {
        self.host.replacement_held()
    }
    fn engine_available(&self) -> bool {
        self.action != "updater-nonempty" && self.host.child_pid().is_some()
    }
    fn child_pid(&self) -> Option<u32> {
        self.host.child_pid()
    }
    fn stop_unrecoverable(&self) -> Result<Option<u32>, String> {
        self.host.stop_owned_engine(Duration::from_secs(2))
    }
    async fn prepare(&self) -> Result<PrepareResponse, String> {
        if self.action == "updater-quit" {
            self.host.post_stop("quit", None, Duration::from_secs(2))?;
            self.host.wait_terminal(self.host.now_ms() + 3000);
        }
        if self.action == "updater-prepare-failure" {
            return Err("injected prepare failure".into());
        }
        Ok(PrepareResponse {
            schema_version: 1,
            preparation_id: "probe".into(),
            shutdown_nonce: "n".repeat(32),
            ready: true,
        })
    }
    async fn cancel(&self, _: &str, _: &str) -> Result<serde_json::Value, String> {
        Ok(serde_json::json!({}))
    }
    async fn commit(&self, _: &str, _: &str) -> Result<ShutdownReceipt, String> {
        Err("nonempty probe uses the unbound stop path".into())
    }
    fn wait_for_exit(&self, timeout: Duration) -> bool {
        self.host.wait_for_exit(timeout)
    }
    fn await_contained_exit(&self, _: Duration) -> Result<crate::containment::JobEmpty, String> {
        self.empty_checks.fetch_add(1, Ordering::SeqCst);
        if self
            .helper
            .lock()
            .unwrap()
            .try_wait()
            .map_err(|e| e.to_string())?
            .is_none()
        {
            Ok(crate::containment::JobEmpty::TimedOut { active: 1 })
        } else {
            Ok(crate::containment::JobEmpty::Empty {
                waited: Duration::ZERO,
            })
        }
    }
    fn terminate_contained(&self) -> Result<(), String> {
        // Fault edge: the helper cannot be confirmed terminated. No production stop is changed.
        self.terminates.fetch_add(1, Ordering::SeqCst);
        Ok(())
    }
    fn resume(&self) -> Result<(), String> {
        self.resumes.fetch_add(1, Ordering::SeqCst);
        self.host.release_replacement_hold()
    }
    fn launch(&self, _: &Path) -> Result<(u32, u128), InstallerError> {
        self.launches.fetch_add(1, Ordering::SeqCst);
        Ok((42, now_epoch_ms()))
    }
}
pub(super) fn drive(
    host: Arc<crate::engine_host::EngineHost>,
    data_dir: &Path,
    action: &str,
) -> serde_json::Value {
    let root = data_dir.join("updater-probe");
    let path = root.join("staged/probe/installer.exe");
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    fs::write(&path, probe_installer_bytes()).unwrap();
    let staged = StagedArtifact {
        path: path.to_string_lossy().into_owned(),
        sha256: sha256_bytes(probe_installer_bytes()),
        size: probe_installer_bytes().len() as u64,
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
            size: probe_installer_bytes().len() as u64,
            signature: "fixture".into(),
            public_key: "fixture".into(),
            key_id: "fixture-artifact".into(),
        },
        compatibility: vec![],
    };
    let mut command = if cfg!(windows) {
        let mut command = std::process::Command::new("ping.exe");
        command.args(["-n", "60", "127.0.0.1"]);
        command
    } else {
        let mut command = crate::containment::engine_command("sleep").unwrap();
        command.arg("60");
        command
    };
    command.stdout(std::process::Stdio::null());
    let edge = ProbeEdge {
        host,
        action: action.into(),
        helper: Mutex::new(command.spawn().unwrap()),
        launches: 0.into(),
        empty_checks: 0.into(),
        terminates: 0.into(),
        resumes: 0.into(),
    };
    let helper_pid = edge.helper.lock().unwrap().id();
    let coordinator = UpdateCoordinator::default();
    let result = tokio::runtime::Runtime::new()
        .unwrap()
        .block_on(finish_staged_update(
            &root,
            &coordinator,
            &edge,
            &descriptor,
            staged,
            "probe".into(),
            "1.0.0".into(),
        ));
    let intent = read_json::<UpgradeIntent>(&root.join("intent.v1.json")).ok();
    let mut helper = edge.helper.lock().unwrap();
    let helper_alive = helper.try_wait().unwrap().is_none();
    let payload = serde_json::json!({"error": result.err(),
        "launches": edge.launches.load(Ordering::SeqCst),
        "emptyChecks": edge.empty_checks.load(Ordering::SeqCst),
        "terminates": edge.terminates.load(Ordering::SeqCst),
        "resumes": edge.resumes.load(Ordering::SeqCst),
        "helperPid": helper_pid, "helperAlive": helper_alive,
        "state": coordinator.snapshot().state, "intent": intent,
        "stagedExists": path.exists(), "held": edge.host.replacement_held(),
        "actorRunning": edge.host.supervisor_running(),
        "upgradeRefused": if action == "updater-quit" { edge.host.post_stop("upgrade", None, Duration::ZERO).is_err() } else { false },
        "commitRefused": if action == "updater-quit" { edge.host.upgrade_commit("probe", "nonce", Duration::ZERO).is_err() } else { false }
    });
    let _ = helper.kill();
    let _ = helper.wait();
    payload
}

// The probe builds outside cfg(test), so it carries its own copy of the fixture bytes.
fn probe_installer_bytes() -> &'static [u8] {
    if cfg!(target_os = "linux") {
        b"!<arch>\ndebian-binary   0           0     0     100644  4         `\n2.0\n"
    } else {
        b"MZfixture"
    }
}
