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
