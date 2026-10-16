use super::*;
use serde_json::json;
use std::sync::atomic::{AtomicBool, AtomicUsize};

struct DeliveryFixture {
    root: tempfile::TempDir,
    queue: Mutex<Option<(&'static str, &'static str)>>,
    exit: AtomicBool,
    launches: AtomicUsize,
    order: &'static str,
}
impl UpgradeHandoff for DeliveryFixture {
    fn hold(&self) -> Result<(), String> {
        Ok(())
    }
    fn is_held(&self) -> bool {
        true
    }
    fn engine_available(&self) -> bool {
        true
    }
    fn child_pid(&self) -> Option<u32> {
        (!self.exit.load(Ordering::SeqCst)).then_some(42)
    }
    fn stop_unrecoverable(&self) -> Result<Option<u32>, String> {
        assert!(
            self.exit.load(Ordering::SeqCst),
            "actor observed the exit before dead-Engine handoff"
        );
        Ok(None)
    }
    async fn prepare(&self) -> Result<PrepareResponse, String> {
        Ok(PrepareResponse {
            schema_version: 1,
            preparation_id: "prep".into(),
            shutdown_nonce: "n".repeat(32),
            ready: true,
        })
    }
    async fn cancel(&self, _: &str, _: &str) -> Result<serde_json::Value, String> {
        Ok(json!({}))
    }
    async fn commit(&self, _: &str, _: &str) -> Result<ShutdownReceipt, String> {
        // Deterministic actor/transport seam: dequeue precedes every observation
        // and transport result. The coordinator must decide from facts, not this queue.
        *self.queue.lock().unwrap() = Some(("prep", "nonce"));
        assert!(self.queue.lock().unwrap().take().is_some());
        if self.order == "before connect" {
            self.exit.store(true, Ordering::SeqCst);
        }
        if self.order == "during connect" {
            self.exit.store(true, Ordering::SeqCst);
        }
        let failed_send = Err(crate::shell_actuator::UPGRADE_COMMIT_NOT_SENT.into());
        if self.order == "after connect failure" {
            self.exit.store(true, Ordering::SeqCst);
        }
        assert!(
            self.queue.lock().unwrap().is_none(),
            "losing order: queue already consumed"
        );
        failed_send
    }
    fn wait_for_exit(&self, _: Duration) -> bool {
        self.exit.load(Ordering::SeqCst)
    }
    fn await_contained_exit(&self, _: Duration) -> Result<crate::containment::JobEmpty, String> {
        assert!(self.exit.load(Ordering::SeqCst));
        Ok(crate::containment::JobEmpty::Empty {
            waited: Duration::ZERO,
        })
    }
    fn terminate_contained(&self) -> Result<(), String> {
        panic!("empty job needs no termination")
    }
    fn resume(&self) -> Result<(), String> {
        panic!("nothing restarts while held")
    }
    fn launch(&self, _: &Path) -> Result<(u32, u128), String> {
        assert!(self.exit.load(Ordering::SeqCst));
        self.launches.fetch_add(1, Ordering::SeqCst);
        Ok((100, now_epoch_ms()))
    }
}

#[tokio::test]
async fn consumed_commit_queue_held_crash_installs_for_every_exit_observation_order() {
    for order in ["before connect", "during connect", "after connect failure"] {
        let fixture = DeliveryFixture {
            root: tempfile::tempdir().unwrap(),
            queue: Mutex::new(None),
            exit: AtomicBool::new(false),
            launches: AtomicUsize::new(0),
            order,
        };
        let path = fixture.root.path().join("staged/probe/installer.exe");
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
        let result = finish_staged_update(
            fixture.root.path(),
            &UpdateCoordinator::default(),
            &fixture,
            &descriptor,
            staged,
            "probe".into(),
            "1.0.0".into(),
        )
        .await;
        assert_eq!(
            fixture.launches.load(Ordering::SeqCst),
            1,
            "consumed queue and no commit sent must install for {order}: {result:?}"
        );
        result.unwrap();
    }
}

#[test]
fn sent_commit_failure_keeps_receipt_rules_and_unsent_without_exit_is_refused() {
    let sent: Result<serde_json::Value, String> =
        resolve_commit_delivery(Err("connection lost after write".into()), || {
            panic!("sent failure must keep receipt rules")
        });
    assert_eq!(sent.unwrap_err(), "connection lost after write");
    let no_exit: Result<serde_json::Value, String> = resolve_commit_delivery(
        Err(crate::shell_actuator::UPGRADE_COMMIT_NOT_SENT.into()),
        || Err("actor exit deadline".into()),
    );
    assert_eq!(no_exit.unwrap_err(), "actor exit deadline");
}

#[test]
fn actor_without_terminal_observation_cannot_authorize_install() {
    let host = crate::engine_host::EngineHost::default();
    host.acquire_replacement_hold(Duration::ZERO).unwrap();
    let supervisor = crate::supervisor::Supervisor::new(crate::supervisor::load_policy());
    host.publish_state(None, &supervisor.record(None, None, None, None, "2026-10-07T00:00:00Z".into()), |_| {});
    assert_eq!(finish_held_actor_exit(&host, Duration::ZERO).unwrap_err(),
        "Actor ended without publishing its exit observation");
}
