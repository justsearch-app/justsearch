//! Real post-staging coordinator and ownership core, with HTTP and installer edges injected.
use super::*;
use crate::engine_host::{EngineHost, PreparedCommand};
use std::sync::atomic::AtomicUsize;

struct HandoffFixture {
    root: tempfile::TempDir,
    host: Arc<EngineHost>,
    available: bool,
    failure: &'static str,
    launches: AtomicUsize,
    resumes: AtomicUsize,
    stopped_pid: Mutex<Option<u32>>,
    registered_child: Mutex<Option<std::process::Child>>,
}

fn sleeper() -> PreparedCommand {
    let mut command = if cfg!(windows) {
        let mut command = std::process::Command::new("ping.exe");
        command.args(["-n", "30", "127.0.0.1"]);
        command
    } else {
        let mut command = std::process::Command::new("sleep");
        command.arg("30");
        command
    };
    command
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());
    PreparedCommand { command }
}

impl HandoffFixture {
    fn new(available: bool, failure: &'static str) -> Self {
        let result = Self {
            root: tempfile::tempdir().unwrap(),
            host: Arc::new(EngineHost::default()),
            available,
            failure,
            launches: AtomicUsize::new(0),
            resumes: AtomicUsize::new(0),
            stopped_pid: Mutex::new(None),
            registered_child: Mutex::new(None),
        };
        result.spawn_supervised();
        result
    }
    fn spawn_supervised(&self) {
        self.host.admit(sleeper()).unwrap();
        let host = self.host.clone();
        self.host
            .start_supervisor(move || {
                while host.should_continue() {
                    std::thread::sleep(Duration::from_millis(2));
                }
            })
            .unwrap();
    }
    async fn run(&self, coordinator: &UpdateCoordinator) -> Result<(), String> {
        let path = self.root.path().join("staged/attempt-1/installer.exe");
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(&path, b"MZfixture").unwrap();
        let staged = StagedArtifact {
            path: path.to_string_lossy().into_owned(),
            sha256: sha256_bytes(b"MZfixture"),
            size: 9,
        };
        if self.failure == "artifact" {
            fs::write(&path, b"tampered").unwrap();
        }
        if self.failure == "intent" {
            fs::create_dir(self.root.path().join("intent.v1.json")).unwrap();
        }
        if self.failure == "witness" {
            fs::create_dir(self.root.path().join("installer-launch-witness.v1.json")).unwrap();
        }
        if self.failure == "malformed_children" {
            fs::create_dir(self.root.path().join("runtime")).unwrap();
            fs::write(self.root.path().join("runtime/manifest.json"),
                r#"{"schemaVersion":2,"children":{}}"#).unwrap();
        }
        // Authentication/staging is upstream. This is the exact downstream production call.
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
            compatibility: vec![ReleaseStoreCompatibility {
                owner_id: "preferences".into(),
                owner: "HEAD".into(),
                role: "AUTHORED".into(),
                format_version: 1,
                readable_source_versions: vec![1],
                reconciliation_strategy: "fixture".into(),
            }],
        };
        finish_staged_update(
            self.root.path(),
            coordinator,
            self,
            &descriptor,
            staged,
            "attempt-1".into(),
            "1.0.0".into(),
        )
        .await
    }
    fn intent(&self) -> UpgradeIntent {
        read_json(&self.root.path().join("intent.v1.json")).unwrap()
    }
}

impl Drop for HandoffFixture {
    fn drop(&mut self) {
        self.host.begin_close();
        self.host.stop_owned_engine(Duration::from_secs(2)).unwrap();
        if let Some(child) = self.registered_child.lock().unwrap().as_mut() {
            let _ = child.kill();
            child.wait().unwrap();
        }
    }
}

impl UpgradeHandoff for HandoffFixture {
    fn hold(&self) -> Result<(), String> {
        self.host.acquire_replacement_hold(Duration::from_secs(2))
    }
    fn is_held(&self) -> bool {
        self.host.replacement_held()
    }
    fn engine_available(&self) -> bool {
        self.available
    }
    fn child_pid(&self) -> Option<u32> {
        self.host.child_pid()
    }
    fn stop_unrecoverable(&self) -> Result<Option<u32>, String> {
        assert!(self.is_held());
        assert!(
            !self.available,
            "prepared failures must never fall into the dead path"
        );
        let pid = self.host.stop_owned_engine(Duration::from_secs(2))?;
        *self.stopped_pid.lock().unwrap() = pid;
        if self.failure == "cleanup" {
            return Err("injected child cleanup failure".into());
        }
        crate::reconcile_registered_children(self.root.path())?;
        Ok(pid)
    }
    async fn prepare(&self) -> Result<PrepareResponse, String> {
        assert!(self.is_held());
        if self.failure == "prepare" {
            return Err("injected prepare timeout".into());
        }
        Ok(PrepareResponse {
            schema_version: 1,
            preparation_id: "prep-1".into(),
            shutdown_nonce: "n".repeat(32),
            ready: true,
        })
    }
    async fn cancel(&self, _: &str, _: &str) -> Result<serde_json::Value, String> {
        Ok(serde_json::json!({}))
    }
    async fn commit(&self, _: &str, _: &str) -> Result<ShutdownReceipt, String> {
        if self.failure == "commit" {
            return Err("injected commit timeout".into());
        }
        let pid = self
            .host
            .stop_owned_engine(Duration::from_secs(2))?
            .unwrap();
        let final_receipt = HeadShutdownReceipt {
            schema_version: 1,
            preparation_id: "prep-1".into(),
            shutdown_nonce: "n".repeat(32),
            head_pid: u64::from(pid),
            clean: self.failure != "final",
            worker_outcome: "GRACEFUL".into(),
            errors: vec![],
            completed_at: "2026-09-08T00:00:00Z".into(),
        };
        write_json_atomic(
            &self.root.path().join("head-shutdown-receipt.v1.json"),
            &final_receipt,
        )?;
        Ok(ShutdownReceipt {
            schema_version: 1,
            preparation_id: "prep-1".into(),
            shutdown_nonce: if self.failure == "receipt" {
                "wrong".into()
            } else {
                "n".repeat(32)
            },
            shutdown_accepted: true,
            admission_frozen: true,
            active_lease_count: 0,
            issued_at_epoch_ms: 10,
        })
    }
    fn wait_for_exit(&self, timeout: Duration) -> bool {
        self.host.wait_for_exit(timeout)
    }
    fn resume(&self) -> Result<(), String> {
        self.host.release_replacement_hold()?;
        self.spawn_supervised();
        self.resumes.fetch_add(1, Ordering::SeqCst);
        Ok(())
    }
    fn launch(&self, _: &Path) -> Result<(u32, u128), String> {
        assert!(self.is_held());
        assert_eq!(self.host.child_pid(), None);
        if let Some(child) = self.registered_child.lock().unwrap().as_mut() {
            assert!(child.try_wait().unwrap().is_some(), "registered child survived until launch");
        }
        assert!(self.host.admit(sleeper()).is_err());
        assert_eq!(self.intent().phase, UpgradePhase::InstallLaunching);
        self.launches.fetch_add(1, Ordering::SeqCst);
        if self.failure == "launch" {
            Err("injected installer launch failure".into())
        } else {
            Ok((42, now_epoch_ms()))
        }
    }
}

#[tokio::test]
async fn unbound_owned_engine_is_reaped_before_launch_and_witness_survives_reconciliation() {
    let fixture = HandoffFixture::new(false, "");
    let old_pid = fixture.host.child_pid().unwrap();
    fixture.run(&UpdateCoordinator::default()).await.unwrap();
    let intent = fixture.intent();
    assert_eq!(fixture.launches.load(Ordering::SeqCst), 1);
    assert_eq!(*fixture.stopped_pid.lock().unwrap(), Some(old_pid));
    assert_eq!(intent.phase, UpgradePhase::InstallLaunched);
    assert!(
        matches!(intent.stop_evidence, StopEvidence::EngineUnrecoverable { engine_pid: Some(pid), .. } if pid == old_pid)
    );
    let json = serde_json::to_value(&intent).unwrap();
    assert!(json.get("shutdownNonce").is_none());
    assert!(json.get("preparationId").is_none());
    let reconciled = reconcile_intent(
        &fixture.root.path().join("intent.v1.json"),
        &fixture.root.path().join("installer-launch-witness.v1.json"),
        &fixture.root.path().join("staged"),
        "1.1.0",
        intent,
    )
    .unwrap();
    assert_eq!(reconciled.phase, UpgradePhase::Reconciling);
    assert_eq!(reconciled.stop_evidence.kind(), "ENGINE_UNRECOVERABLE");
}

#[cfg(windows)]
#[tokio::test]
async fn coordinator_reconciles_real_registered_child_before_installer_launch() {
    let fixture = HandoffFixture::new(false, "");
    let (child, identity) = crate::tests::owned_sleep_child();
    *fixture.registered_child.lock().unwrap() = Some(child);
    fs::create_dir(fixture.root.path().join("runtime")).unwrap();
    let manifest = serde_json::json!({"schemaVersion": 2, "children": [{
        "pid": identity.pid, "startedAt": identity.started_at, "executable": identity.executable
    }]});
    fs::write(fixture.root.path().join("runtime/manifest.json"), serde_json::to_vec(&manifest).unwrap()).unwrap();
    fixture.run(&UpdateCoordinator::default()).await.unwrap();
    assert_eq!(fixture.launches.load(Ordering::SeqCst), 1);
    assert_eq!(fixture.intent().phase, UpgradePhase::InstallLaunched);
}

#[tokio::test]
async fn launch_failure_resumes_one_child_and_one_supervisor_on_each_stop_path() {
    for available in [false, true] {
        let fixture = HandoffFixture::new(available, "launch");
        assert!(fixture
            .run(&UpdateCoordinator::default())
            .await
            .unwrap_err()
            .contains("supervision was resumed"));
        assert_eq!(fixture.resumes.load(Ordering::SeqCst), 1);
        assert_eq!(fixture.intent().phase, UpgradePhase::Cancelled);
        assert!(fixture.host.child_pid().is_some());
        assert!(!fixture.is_held());
        assert!(fixture
            .host
            .start_supervisor(|| panic!("duplicate supervisor"))
            .is_err());
    }
}

#[tokio::test]
async fn handoff_failures_keep_hold_explicit_and_never_invent_a_dead_engine_fallback() {
    for (available, failure) in [
        (false, "cleanup"),
        (false, "malformed_children"),
        (false, "intent"),
        (false, "witness"),
        (true, "prepare"),
        (true, "commit"),
        (true, "receipt"),
        (true, "final"),
    ] {
        let fixture = HandoffFixture::new(available, failure);
        let coordinator = UpdateCoordinator::default();
        let error = fixture.run(&coordinator).await.unwrap_err();
        assert!(
            error.contains("replacement remains held"),
            "{failure}: {error}"
        );
        assert!(fixture.is_held());
        assert_eq!(coordinator.snapshot().state, "repair_required");
        assert_eq!(fixture.resumes.load(Ordering::SeqCst), 0);
        assert_eq!(
            fixture.launches.load(Ordering::SeqCst),
            usize::from(failure == "witness")
        );
        if available {
            assert_eq!(*fixture.stopped_pid.lock().unwrap(), None);
        }
    }
}

#[tokio::test]
async fn tampered_staging_fails_before_taking_hold() {
    let fixture = HandoffFixture::new(false, "artifact");
    assert!(fixture.run(&UpdateCoordinator::default()).await.is_err());
    assert!(!fixture.is_held());
    assert!(fixture.host.child_pid().is_some());
    assert_eq!(fixture.launches.load(Ordering::SeqCst), 0);
}

#[tokio::test]
async fn dead_stop_witness_refuses_mixed_receipts_and_wrong_attempt() {
    let fixture = HandoffFixture::new(false, "");
    fixture.run(&UpdateCoordinator::default()).await.unwrap();
    for field in ["preparationId", "shutdownNonce", "headPid"] {
        let mut json = serde_json::to_value(fixture.intent()).unwrap();
        json[field] = if field == "headPid" {
            serde_json::json!(42)
        } else {
            serde_json::json!("mixed")
        };
        let mixed: UpgradeIntent = serde_json::from_value(json).unwrap();
        assert!(
            validate_intent_evidence(
                &mixed,
                &fixture.root.path().join("installer-launch-witness.v1.json"),
                &fixture.root.path().join("staged")
            )
            .is_err(),
            "{field}"
        );
    }
    let mut intent = fixture.intent();
    intent.attempt_id = "another-attempt".into();
    assert!(validate_intent_evidence(
        &intent,
        &fixture.root.path().join("installer-launch-witness.v1.json"),
        &fixture.root.path().join("staged")
    )
    .is_err());
}
