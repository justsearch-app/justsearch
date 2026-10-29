use super::*;
use std::io::Read;
use std::net::TcpListener;

#[test]
fn spawn_keeps_predecessor_evidence_even_when_admission_fails() {
    use crate::supervisor::Actuator;
    for fail in [false, true] {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir(dir.path().join("runtime")).unwrap();
        let path = dir.path().join("runtime/manifest.json");
        let evidence = r#"{"schemaVersion":2,"pid":424242,"children":[],"head":{"sessionToken":"private"}}"#;
        std::fs::write(&path, evidence).unwrap();
        let observed = path.clone();
        let mut actuator = ShellActuator::new(
            Arc::new(EngineHost::default()), dir.path().into(),
            move || {
                assert_eq!(std::fs::read_to_string(&observed).ok().as_deref(), Some(evidence),
                    "the successor must receive predecessor evidence at the spawn boundary");
                if fail { Err("fixture admission failure".into()) } else { Ok(std::process::id()) }
            }, |_| {},
        );
        assert_eq!(actuator.spawn_engine().is_err(), fail);
        assert_eq!(std::fs::read_to_string(path).unwrap(), evidence);
    }
}

#[test]
fn pidless_predecessor_never_supplies_successor_port_or_token() {
    if isolated_manifest_check("shell_actuator::commit_transport_tests::pidless_predecessor_never_supplies_successor_port_or_token") {
        return;
    }
    let host = EngineHost::default();
    let mut command = crate::containment::engine_command(std::env::current_exe().unwrap()).unwrap();
    command.arg("--list").stdout(std::process::Stdio::null());
    let child = host.admit(crate::engine_host::PreparedCommand { command }).unwrap();
    let mut predecessor = binding::ManifestFields::default();
    predecessor.instance_id = Some("predecessor".into());
    predecessor.api_port = Some(42424);
    predecessor.session_token = Some("predecessor-secret".into());
    let refused = host.observe_manifest(&predecessor);
    let observed = host.binding_snapshot();
    host.fixture_kill_and_reap();
    assert!(child.pid > 0);
    assert_eq!(refused, None);
    assert_eq!(observed.port, None);
    assert_eq!(observed.token, None);
}

// Each manifest check runs with a private native helper, without an installed UI bundle.
fn isolated_manifest_check(_test_name: &str) -> bool {
    // Admission requires the native Linux helper. Build a private fixture instead of relying on
    // installDist; isolate its process-wide bundle path from the other library tests.
    #[cfg(target_os = "linux")]
    {
        const BUNDLE: &str = "JUSTSEARCH_MANIFEST_TEST_BUNDLE";
        if let Some(bundle) = std::env::var_os(BUNDLE) {
            crate::linux_process::set_bundle_dir(std::path::Path::new(&bundle));
        } else {
            let bundle = tempfile::tempdir().unwrap();
            let helper = bundle.path().join(crate::native_targets::relative_path("bound-exec"));
            std::fs::create_dir_all(helper.parent().unwrap()).unwrap();
            let source = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../../../native/bound-exec/bound-exec.c");
            let compiled = std::process::Command::new("cc")
                .args(["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror"])
                .arg(source).arg("-o").arg(helper).status().unwrap();
            assert!(compiled.success(), "private bound-exec fixture must compile");
            let result = std::process::Command::new(std::env::current_exe().unwrap())
                .args(["--exact", _test_name, "--nocapture"])
                .env(BUNDLE, bundle.path()).output().unwrap();
            assert_eq!(result.status.code(), Some(0), "isolated manifest admission check failed:\n{}\n{}",
                String::from_utf8_lossy(&result.stdout), String::from_utf8_lossy(&result.stderr));
            return true;
        }
    }
    false
}

#[test]
fn retained_same_pid_manifest_cannot_bind_a_fresh_shell() {
    if isolated_manifest_check("shell_actuator::commit_transport_tests::retained_same_pid_manifest_cannot_bind_a_fresh_shell") {
        return;
    }
    use crate::supervisor::Actuator;
    let dir = tempfile::tempdir().unwrap();
    std::fs::create_dir(dir.path().join("runtime")).unwrap();
    let path = dir.path().join("runtime/manifest.json");
    let host = Arc::new(EngineHost::default());
    let mut command = crate::containment::engine_command(std::env::current_exe().unwrap()).unwrap();
    command.arg("--list").stdout(std::process::Stdio::null());
    let child = host.admit(crate::engine_host::PreparedCommand { command }).unwrap();
    // Install the admitted child's PID in predecessor evidence to deterministically model reuse.
    let publish = |id: &str, started: &str, port: u16| {
        let incarnation = if id.starts_with("successor") {
            json!({"session":host.session(),"n":child.generation})
        } else {
            json!({"session":"predecessor-shell","n":1})
        };
        std::fs::write(&path, json!({"instanceId":id,"pid":child.pid,"startedAt":started,"incarnation":incarnation,
            "head":{"apiPort":port,"sessionToken":format!("secret-{id}")}}).to_string()).unwrap();
    };
    publish("predecessor", "2026-01-01T00:00:00Z", 42424);
    let mut actuator = ShellActuator::new(host.clone(), dir.path().into(),
        move || Ok(child.pid), |_| {});
    actuator.spawn_engine().unwrap();
    actuator.discovery();
    let stale = host.binding_snapshot();
    publish("too-early", "2026-01-01T00:00:01Z", 42425);
    actuator.discovery();
    let early = host.binding_snapshot();
    let now = supervisor::epoch_ms_to_iso(epoch());
    publish("successor", &now, 42426);
    actuator.discovery();
    let successor = host.binding_snapshot();
    publish("successor-republished", &now, 42427);
    actuator.discovery();
    let rebound = host.binding_snapshot();
    host.fixture_kill_and_reap();
    assert_eq!(stale.port, None, "retained same-PID predecessor cannot supply credentials");
    assert_eq!(stale.token, None);
    assert_eq!(early.port, None, "an instance predating spawn cannot supply credentials");
    assert_eq!(successor.port, Some(42426));
    assert_eq!(successor.token.as_deref(), Some("secret-successor"));
    assert_eq!(rebound.port, Some(42427), "same child with a different instance rebinds");
    assert_eq!(rebound.token.as_deref(), Some("secret-successor-republished"));
}

#[test]
fn competing_incarnation_with_reused_pid_never_binds() {
    if isolated_manifest_check("shell_actuator::commit_transport_tests::competing_incarnation_with_reused_pid_never_binds") {
        return;
    }
    use crate::supervisor::Actuator;
    let dir = tempfile::tempdir().unwrap();
    std::fs::create_dir(dir.path().join("runtime")).unwrap();
    let path = dir.path().join("runtime/manifest.json");
    let predecessor = json!({"instanceId":"predecessor","pid":424242,
        "startedAt":"2026-01-01T00:00:00Z","incarnation":{"session":"predecessor-shell","n":1},
        "head":{"apiPort":42424,"sessionToken":"predecessor-secret"}});
    std::fs::write(&path, predecessor.to_string()).unwrap();
    let host = Arc::new(EngineHost::default());
    let spawn_host = host.clone();
    let spawn_path = path.clone();
    let mut actuator = ShellActuator::new(host.clone(), dir.path().into(), move || {
        // This seam is after the old predecessor snapshot. Model the competing writer and PID
        // reuse directly, without relying on the OS to recycle a particular process number.
        assert_eq!(std::fs::read_to_string(&spawn_path).unwrap(), predecessor.to_string());
        let mut command = crate::containment::engine_command(std::env::current_exe().unwrap()).unwrap();
        command.arg("--list").stdout(std::process::Stdio::null());
        let child = spawn_host.admit(crate::engine_host::PreparedCommand { command })?;
        std::fs::write(&spawn_path, json!({"instanceId":"competing-writer","pid":child.pid,
            "startedAt":"9999-01-01T00:00:00Z","incarnation":{"session":"other-shell","n":child.generation},
            "head":{"apiPort":42425,"sessionToken":"competing-secret"}}).to_string()).unwrap();
        Ok(child.pid)
    }, |_| {});
    actuator.spawn_engine().unwrap();
    actuator.discovery();
    let other_session = host.binding_snapshot();
    let mut manifest: Value = serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
    manifest["incarnation"] = json!({"session":host.session(),"n":host.incarnation_number()+1});
    std::fs::write(&path, manifest.to_string()).unwrap();
    actuator.discovery();
    let other_number = host.binding_snapshot();
    manifest["incarnation"] = json!({"session":host.session(),"n":host.incarnation_number()});
    std::fs::write(&path, manifest.to_string()).unwrap();
    actuator.discovery();
    let own = host.binding_snapshot();
    manifest["instanceId"] = json!("later-competing-writer");
    manifest["incarnation"]["session"] = json!("other-shell");
    manifest["head"]["apiPort"] = json!(42426);
    manifest["head"]["sessionToken"] = json!("later-competing-secret");
    std::fs::write(&path, manifest.to_string()).unwrap();
    actuator.discovery();
    let still_own = host.binding_snapshot();
    host.fixture_kill_and_reap();
    assert_eq!(other_session.port, None, "another session with a reused PID cannot supply credentials");
    assert_eq!(other_session.token, None);
    assert_eq!(other_number.port, None, "another incarnation number cannot supply credentials");
    assert_eq!(other_number.token, None);
    assert_eq!(own.port, Some(42425));
    assert_eq!(own.token.as_deref(), Some("competing-secret"));
    assert_eq!(still_own, own, "a competing writer cannot replace an admitted binding");
}

#[test]
fn own_incarnation_binds_after_wall_clock_steps_back() {
    if isolated_manifest_check("shell_actuator::commit_transport_tests::own_incarnation_binds_after_wall_clock_steps_back") {
        return;
    }
    use crate::supervisor::Actuator;
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("runtime/manifest.json");
    let host = Arc::new(EngineHost::default());
    let spawn_host = host.clone();
    let spawn_path = path.clone();
    let mut actuator = ShellActuator::new(host.clone(), dir.path().into(), move || {
        let mut command = crate::containment::engine_command(std::env::current_exe().unwrap()).unwrap();
        command.arg("--list").stdout(std::process::Stdio::null());
        let child = spawn_host.admit(crate::engine_host::PreparedCommand { command })?;
        // Fault injection: the publisher's immutable startedAt is before the shell's spawn time.
        std::fs::write(&spawn_path, json!({"instanceId":"own-engine","pid":child.pid,
            "startedAt":"2000-01-01T00:00:00Z","incarnation":{"session":spawn_host.session(),"n":child.generation},
            "head":{"apiPort":42426,"sessionToken":"own-secret"}}).to_string()).unwrap();
        Ok(child.pid)
    }, |_| {});
    actuator.spawn_engine().unwrap();
    actuator.discovery();
    let own = host.binding_snapshot();
    host.fixture_kill_and_reap();
    assert_eq!(own.port, Some(42426), "wall-clock rollback cannot reject the owned incarnation");
    assert_eq!(own.token.as_deref(), Some("own-secret"));
}

#[test]
fn manifest_without_incarnation_never_binds() {
    if isolated_manifest_check("shell_actuator::commit_transport_tests::manifest_without_incarnation_never_binds") {
        return;
    }
    use crate::supervisor::Actuator;
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("runtime/manifest.json");
    let host = Arc::new(EngineHost::default());
    let spawn_host = host.clone();
    let mut actuator = ShellActuator::new(host.clone(), dir.path().into(), move || {
        let mut command = crate::containment::engine_command(std::env::current_exe().unwrap()).unwrap();
        command.arg("--list").stdout(std::process::Stdio::null());
        spawn_host.admit(crate::engine_host::PreparedCommand { command }).map(|child| child.pid)
    }, |_| {});
    actuator.spawn_engine().unwrap();
    let mut observed = Vec::new();
    for incarnation in [Value::Null, json!({"session":host.session()}), json!({"n":1}),
        json!({"session":host.session(),"n":"1"}), json!({"session":host.session(),"n":0})] {
        std::fs::write(&path, json!({"instanceId":"unsupervised","pid":host.child_pid(),
            "startedAt":"9999-01-01T00:00:00Z","incarnation":incarnation,
            "head":{"apiPort":42427,"sessionToken":"unsupervised-secret"}}).to_string()).unwrap();
        actuator.discovery();
        observed.push(host.binding_snapshot());
    }
    host.fixture_kill_and_reap();
    for binding in observed {
        assert_eq!(binding.port, None, "an absent or malformed incarnation cannot supply credentials");
        assert_eq!(binding.token, None);
    }
}

// Exercise the same proven connection and write boundary with a listener owned by this fixture.
fn post_before_send(
    port: u16, token: &str, path: &str, body: &Value, timeout_ms: u64,
    before_send: impl FnOnce() -> bool,
) -> (Result<Value, String>, bool) {
    let timeout = Duration::from_millis(timeout_ms);
    match crate::engine_conn::connect_proven(port, std::process::id(), timeout) {
        Ok(stream) => crate::engine_conn::post_connected(stream, port, token, path, body, timeout, before_send),
        Err(error) => (Err(error), false),
    }
}

#[test]
fn connect_failure_and_observed_exit_send_no_commit_bytes() {
    let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener);
    let (result, sent) = post_before_send(port, "token", "/commit", &json!({}), 1000, || {
        panic!("failed connect must not reach the send boundary")
    });
    assert!(result.is_err());
    assert!(!sent);

    let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
    let port = listener.local_addr().unwrap().port();
    let reader = std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let mut bytes = Vec::new();
        stream.read_to_end(&mut bytes).unwrap();
        bytes
    });
    let (result, sent) = post_before_send(port, "token", "/commit", &json!({}), 1000, || false);
    assert!(result.is_err());
    assert!(!sent);
    assert!(reader.join().unwrap().is_empty(), "exit observed before send leaves no bytes");
}

#[test]
fn request_written_then_missing_receipt_is_ambiguous_delivery() {
    let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
    let port = listener.local_addr().unwrap().port();
    let reader = std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let mut bytes = [0u8; 1024];
        let n = stream.read(&mut bytes).unwrap();
        assert!(n > 0, "request reaches the real socket before response loss");
        // Close without returning any receipt.
    });
    let (result, sent) = post_before_send(port, "token", "/commit", &json!({}), 1000, || true);
    reader.join().unwrap();
    assert!(result.is_err());
    assert!(sent, "a read/receipt failure after writing must keep receipt rules");
}
