use super::*;
use crate::engine_conn_test_support::{impostor, Fixture};

#[test]
fn shutdown_impostor_gets_zero_bytes_and_quit_can_force_kill() {
    let (port, reader) = impostor();
    let fixture = Fixture::sleeper(port);
    let mut actor = fixture.actor();
    actor
        .write_shutdown_request("quit", epoch() + 1000)
        .unwrap();
    assert!(
        reader.join().unwrap().is_empty(),
        "assertion failed: shutdown disclosed bytes to an impostor"
    );
    assert!(fixture
        .dir
        .path()
        .join("runtime/shutdown-request.v1.json")
        .exists());
    actor.force_kill();
    let deadline = std::time::Instant::now() + Duration::from_secs(2);
    while actor.poll_exit().is_none() {
        assert!(
            std::time::Instant::now() < deadline,
            "force-killed child survived"
        );
        std::thread::sleep(Duration::from_millis(5));
    }
}

#[test]
fn commit_impostor_is_not_marked_sent_or_allowed_to_run_send_hook() {
    let (port, reader) = impostor();
    let fixture = Fixture::sleeper(port);
    let hook_called = std::cell::Cell::new(false);
    let (result, sent) = post_before_send(
        &fixture.host,
        "/api/upgrade/commit-shutdown",
        &json!({}),
        1000,
        || {
            hook_called.set(true);
            true
        },
    );
    assert!(
        !hook_called.get(),
        "assertion failed: unproven connection reached commit send boundary"
    );
    assert!(
        result.is_err(),
        "assertion failed: commit must reject an unproven server"
    );
    assert!(result.unwrap_err().contains("not proven"));
    assert!(!sent);
    assert!(reader.join().unwrap().is_empty());
}

#[test]
fn every_reconnect_requires_fresh_proof() {
    let (first, reader) = impostor();
    let fixture = Fixture::sleeper(first);
    let result = post(&fixture.host, "/api/upgrade/prepare", &json!({}), 1000);
    assert!(
        result.is_err(),
        "assertion failed: upgrade must reject an unproven server"
    );
    assert!(result.unwrap_err().contains("not proven"));
    assert!(reader.join().unwrap().is_empty());
    let (second, reader) = impostor();
    let predecessor = fixture.host.child_pid();
    fixture.host.fixture_kill_and_reap();
    fixture.host.reset_for_successor().unwrap();
    let mut command = crate::containment::engine_command("sleep").unwrap();
    command.arg("60");
    fixture
        .host
        .admit(crate::engine_host::PreparedCommand { command })
        .unwrap();
    assert_ne!(fixture.host.child_pid(), predecessor);
    fixture.host.observe_manifest(&binding::ManifestFields {
        instance_id: Some("successor".into()),
        pid: fixture.host.child_pid(),
        api_port: Some(second),
        session_token: Some("successor-token".into()),
        ..Default::default()
    });
    assert_eq!(fixture.host.binding_snapshot().port, Some(second));
    assert_eq!(
        fixture.host.binding_snapshot().token.as_deref(),
        Some("successor-token")
    );
    let result = post(&fixture.host, "/api/upgrade/prepare", &json!({}), 1000);
    assert!(
        result.is_err(),
        "assertion failed: upgrade must reject an unproven server"
    );
    assert!(result.unwrap_err().contains("not proven"));
    assert!(reader.join().unwrap().is_empty());
}

#[test]
fn supervisor_successors_use_the_new_pid_and_coherent_binding_on_same_and_new_ports() {
    let fixture = Fixture::server();
    let original_port = fixture.start_server(0, "initial");
    let mut previous = None;
    for (instance, port) in [
        ("initial", original_port),
        ("same-port", original_port),
        ("new-port", 0),
    ] {
        if instance != "initial" {
            fixture.host.fixture_kill_and_reap();
            fixture.host.reset_for_successor().unwrap();
            fixture.start_server(port, instance);
        }
        let pid = fixture.host.child_pid().unwrap();
        assert_ne!(Some(pid), previous);
        let response = post(&fixture.host, "/api/upgrade/prepare", &json!({}), 1000).unwrap();
        assert_eq!(response["pid"], pid);
        previous = Some(pid);
        if port == 0 {
            assert_ne!(fixture.host.binding_snapshot().port, Some(original_port));
        }
    }
}

#[test]
fn takeover_after_a_successful_health_probe_is_rejected_on_the_actual_send_connection() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let fixture = Fixture::server();
    let port = fixture.start_server(0, "probe");
    assert!(engine_probe::responds(
        port,
        "/health",
        Duration::from_secs(1)
    ));
    fixture.host.with_child_mut(|child| {
        child.kill().unwrap();
        child.wait().unwrap();
    });
    let listener = std::net::TcpListener::bind(("127.0.0.1", port)).unwrap();
    let reader = std::thread::spawn(move || {
        use std::io::Read;
        let (mut stream, _) = listener.accept().unwrap();
        let mut bytes = Vec::new();
        stream.read_to_end(&mut bytes).unwrap();
        bytes
    });
    let result = post(&fixture.host, "/api/upgrade/prepare", &json!({}), 1000);
    assert!(
        result.is_err(),
        "assertion failed: upgrade must reject an unproven server"
    );
    assert!(result.unwrap_err().contains("not proven"));
    assert!(reader.join().unwrap().is_empty());
}

/// Explicit live check: builds no GUI and uses only a fresh data directory under TMPDIR.
#[test]
#[ignore = "requires :modules:ui:installDist; run explicitly with --ignored --nocapture"]
fn installed_engine_shutdown_upgrade_and_released_port() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../..")
        .canonicalize()
        .unwrap();
    let installed = root.join("modules/ui/build/install/ui/bin/ui");
    assert!(installed.is_file(), "installed Engine is missing");
    for upgrade in [false, true] {
        let fixture = Fixture::server();
        let mut command = crate::containment::engine_command(&installed).unwrap();
        command
            .current_dir(&root)
            .env("JUSTSEARCH_DATA_DIR", fixture.dir.path())
            .env("JUSTSEARCH_API_PORT", "0")
            .env(
                "JAVA_OPTS",
                format!(
                    "-Djustsearch.prod=true -Djava.io.tmpdir={} -XX:-UsePerfData",
                    fixture.dir.path().display()
                ),
            )
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null());
        let old_pid = fixture
            .host
            .admit(crate::engine_host::PreparedCommand { command })
            .unwrap()
            .pid;
        let deadline = std::time::Instant::now() + Duration::from_secs(90);
        let port = loop {
            assert!(
                fixture.host.try_reap().is_none(),
                "installed Engine exited during startup"
            );
            if let Some(manifest) =
                binding::read_manifest_if_present(&fixture.dir.path().join("runtime/manifest.json"))
            {
                fixture.host.observe_manifest(&manifest);
            }
            if let Some(port) = fixture.host.binding_snapshot().port {
                if fixture.host.binding_snapshot().token.is_some()
                    && engine_probe::essential_ready(port, Duration::from_millis(500)).is_some()
                {
                    break port;
                }
            }
            assert!(
                std::time::Instant::now() < deadline,
                "installed Engine did not become ready"
            );
            std::thread::sleep(Duration::from_millis(50));
        };
        if upgrade {
            let first = post(&fixture.host, "/api/upgrade/prepare", &json!({}), 15000).unwrap();
            assert_eq!(first["ready"], true);
            let capability = json!({"schemaVersion":1, "preparationId":first["preparationId"], "shutdownNonce":first["shutdownNonce"]});
            post(&fixture.host, "/api/upgrade/cancel", &capability, 15000).unwrap();
            let prepared = post(&fixture.host, "/api/upgrade/prepare", &json!({}), 15000).unwrap();
            assert_eq!(prepared["ready"], true);
            let capability = json!({"schemaVersion":1, "preparationId":prepared["preparationId"], "shutdownNonce":prepared["shutdownNonce"]});
            let (receipt, sent) = post_before_send(
                &fixture.host,
                "/api/upgrade/commit-shutdown",
                &capability,
                15000,
                || true,
            );
            assert!(sent);
            assert_eq!(receipt.unwrap()["schemaVersion"], 1);
        } else {
            let mut actor = fixture.actor();
            actor
                .write_shutdown_request("quit", epoch() + 10000)
                .unwrap();
            assert!(
                !fixture
                    .dir
                    .path()
                    .join("runtime/shutdown-request.v1.json")
                    .exists(),
                "HTTP shutdown fell back to the request file"
            );
        }
        let deadline = std::time::Instant::now() + Duration::from_secs(30);
        loop {
            if let Some(code) = fixture.host.try_reap() {
                assert_eq!(code, 0);
                break;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "ordered shutdown did not complete"
            );
            std::thread::sleep(Duration::from_millis(10));
        }
        // A real new listener now owns the released port. It must receive no application bytes.
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_io()
            .build()
            .unwrap();
        let _entered = runtime.enter();
        let listener = tokio::net::TcpSocket::new_v4().unwrap();
        listener.set_reuseaddr(true).unwrap();
        listener
            .bind(std::net::SocketAddr::from(([127, 0, 0, 1], port)))
            .unwrap();
        let listener = listener.listen(1).unwrap().into_std().unwrap();
        listener.set_nonblocking(false).unwrap();
        let reader = std::thread::spawn(move || {
            use std::io::Read;
            let (mut stream, _) = listener.accept().unwrap();
            let mut bytes = Vec::new();
            stream.read_to_end(&mut bytes).unwrap();
            bytes
        });
        assert!(
            crate::engine_conn::connect_proven(port, old_pid, Duration::from_secs(1))
                .unwrap_err()
                .contains("not proven")
        );
        assert!(reader.join().unwrap().is_empty());
        assert!(
            post(&fixture.host, "/api/lifecycle/shutdown", &json!({}), 200)
                .unwrap_err()
                .contains("not proven")
        );
        println!("installed Engine: {} completed with exit 0; released-port impostor received zero bytes", if upgrade {"prepare/cancel/prepare/commit"} else {"ordered HTTP quit"});
    }
}
