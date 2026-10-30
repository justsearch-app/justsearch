use super::*;

fn fixture() -> tempfile::TempDir {
    fixture_with_baseline(false)
}

fn fixture_with_baseline(published: bool) -> tempfile::TempDir {
    let root = tempfile::tempdir().unwrap();
    let script = std::env::var_os("JUSTSEARCH_TEST_FIXTURE_SCRIPT")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            Path::new(env!("CARGO_MANIFEST_DIR"))
                .ancestors()
                .nth(3)
                .unwrap()
                .join("scripts/release/updater-v2-fixture.mjs")
        });
    let result = std::process::Command::new("node")
        .arg(script)
        .arg(root.path())
        .args(if published { vec!["published"] } else { vec![] })
        .output()
        .unwrap();
    assert!(
        result.status.success(),
        "producer fixture failed: {}",
        String::from_utf8_lossy(&result.stderr)
    );
    root
}

#[test]
fn published_baseline_producer_bytes_cover_full_current_register() {
    let root = fixture_with_baseline(true);
    let bytes = fs::read(root.path().join("release.v2.json")).unwrap();
    for target in ["windows-x86_64", "linux-x86_64"] {
        let descriptor = parse_descriptor(&bytes, target).unwrap();
        let result = validate_store_compatibility(&descriptor);
        assert!(result.is_ok(), "assertion failed: {target}: {result:?}");
    }
}

#[test]
fn published_baseline_producer_bytes_cover_earlier_current_subset() {
    let root = fixture_with_baseline(true);
    let bytes = fs::read(root.path().join("release.v2.json")).unwrap();
    for target in ["windows-x86_64", "linux-x86_64"] {
        let mut local: LocalStoreRegister = serde_json::from_str(LOCAL_STORE_REGISTER).unwrap();
        // A synthetic earlier register still owns a store added after the published baseline.
        local
            .durable_stores
            .retain(|store| store.id == "data-version-marker");
        assert_eq!(local.durable_stores.len(), 1);
        let descriptor = parse_descriptor(&bytes, target).unwrap();
        let result = validate_store_compatibility_against(local, &descriptor);
        assert!(result.is_ok(), "assertion failed: {target}: {result:?}");
    }
}

#[test]
fn published_baseline_producer_bytes_cover_published_register_including_retirement() {
    let root = fixture_with_baseline(true);
    let bytes = fs::read(root.path().join("release.v2.json")).unwrap();
    for target in ["windows-x86_64", "linux-x86_64"] {
        let local: LocalStoreRegister =
            serde_json::from_slice(&fs::read(root.path().join("predecessor.json")).unwrap())
                .unwrap();
        assert_eq!(local.durable_stores.len(), 42);
        assert!(local
            .durable_stores
            .iter()
            .any(|store| store.id == "worker-config-snapshot"));
        let descriptor = parse_descriptor(&bytes, target).unwrap();
        validate_store_compatibility_against(local, &descriptor).unwrap();
    }
}

#[test]
fn published_baseline_producer_bytes_refuse_unreadable_predecessor_format() {
    let root = fixture_with_baseline(true);
    let bytes = fs::read(root.path().join("release.v2.json")).unwrap();
    for target in ["windows-x86_64", "linux-x86_64"] {
        let mut local: LocalStoreRegister =
            serde_json::from_slice(&fs::read(root.path().join("predecessor.json")).unwrap())
                .unwrap();
        let descriptor = parse_descriptor(&bytes, target).unwrap();
        let store = &mut local.durable_stores[0];
        let row = descriptor
            .compatibility
            .iter()
            .find(|row| row.owner_id == store.id)
            .unwrap();
        store.current_version = row.readable_source_versions.iter().max().unwrap() + 1;
        let error = validate_store_compatibility_against(local, &descriptor).unwrap_err();
        assert!(error.contains("cannot read durable store"), "{error}");
    }
}

#[test]
fn producer_bytes_select_both_targets_and_cover_every_installed_store() {
    let root = fixture();
    let bytes = fs::read(root.path().join("release.v2.json")).unwrap();
    for target in ["windows-x86_64", "linux-x86_64"] {
        let parsed = parse_descriptor(&bytes, target);
        assert!(
            parsed.is_ok(),
            "v2 parser must accept the producer's two-target bytes: {parsed:?}"
        );
        let descriptor = parsed.unwrap();
        assert_eq!(descriptor.target, target);
        assert!(validate_store_compatibility(&descriptor).is_ok());
        let mut missing_store = descriptor.clone();
        missing_store.compatibility.pop();
        assert!(validate_store_compatibility(&missing_store)
            .unwrap_err()
            .contains("does not declare compatibility"));
    }
    let mut value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    value["targets"]
        .as_object_mut()
        .unwrap()
        .remove(running_target().unwrap());
    assert!(parse_descriptor(
        &serde_json::to_vec(&value).unwrap(),
        running_target().unwrap()
    )
    .unwrap_err()
    .contains("no target"));
}

#[test]
fn producer_bytes_enforce_metadata_signature_root_identity_and_rollback() {
    let root = fixture();
    let mut bytes = fs::read(root.path().join("release.v2.json")).unwrap();
    let signature = fs::read_to_string(root.path().join("release.v2.json.sig")).unwrap();
    let key = fs::read_to_string(root.path().join("metadata-public.base64")).unwrap();
    assert!(verify_metadata_signature_with_key(&bytes, signature.trim(), &key).is_ok());
    let descriptor = parse_descriptor(&bytes, running_target().unwrap()).unwrap();
    assert!(validate_descriptor_with_key_id(&descriptor, "test-metadata").is_ok());
    assert!(validate_descriptor_with_key_id(&descriptor, "wrong-key").is_err());
    assert!(validate_sequence(descriptor.sequence, 43)
        .unwrap_err()
        .contains("older than accepted"));
    assert!(validate_sequence(descriptor.sequence, 42).is_ok());
    let wrong = base64::engine::general_purpose::STANDARD.encode([7; 32]);
    assert!(verify_metadata_signature_with_key(&bytes, signature.trim(), &wrong).is_err());
    bytes.push(b' ');
    assert!(verify_metadata_signature_with_key(&bytes, signature.trim(), &key).is_err());
}

// Drive the actual Tauri signature verifier on producer-generated artifacts, through a local
// download. Only this mock app permits HTTP; the production endpoint policy stays unchanged.
// Linux only, like the crate's other mock-app tests: the Tauri runtime in the Windows test binary
// fails to load with STATUS_ENTRYPOINT_NOT_FOUND (tempdoc 501).
#[cfg(target_os = "linux")]
#[tokio::test]
async fn tauri_download_verifies_producer_deb_and_rejects_mismatched_signature() {
    use std::io::{Read, Write};
    let _ = rustls::crypto::ring::default_provider().install_default();
    let root = fixture();
    let descriptor = parse_descriptor(
        &fs::read(root.path().join("release.v2.json")).unwrap(),
        "linux-x86_64",
    )
    .unwrap();
    for mismatch in [false, true] {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let deb = fs::read(root.path().join("update.deb")).unwrap();
        let expected = deb.clone();
        let signature = if mismatch {
            fs::read_to_string(root.path().join("setup.exe.sig")).unwrap()
        } else {
            descriptor.artifact.signature.clone()
        };
        let latest = serde_json::to_vec(&serde_json::json!({"version":"1.2.3", "platforms":{
            "linux-x86_64": {"url":format!("{base}/update.deb"),"signature":signature}
        }}))
        .unwrap();
        let server = std::thread::spawn(move || {
            for body in [latest, deb] {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(Duration::from_secs(5)))
                    .unwrap();
                let mut request = [0; 4096];
                socket.read(&mut request).unwrap();
                write!(
                    socket,
                    "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    body.len()
                )
                .unwrap();
                socket.write_all(&body).unwrap();
            }
        });
        let mut context = tauri::test::mock_context(tauri::test::noop_assets());
        context.config_mut().plugins.0.insert(
            "updater".into(),
            serde_json::json!({
                "pubkey":descriptor.artifact.public_key,"dangerousInsecureTransportProtocol":true
            }),
        );
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_updater::Builder::new().build())
            .build(context)
            .unwrap();
        let update = app
            .updater_builder()
            .target("linux-x86_64")
            .endpoints(vec![format!("{base}/latest.json").parse().unwrap()])
            .unwrap()
            .build()
            .unwrap()
            .check()
            .await
            .unwrap()
            .unwrap();
        let downloaded = update.download(|_, _| {}, || {}).await;
        server.join().unwrap();
        if mismatch {
            assert!(
                downloaded.is_err(),
                "Tauri must reject a signature for the other target"
            );
        } else {
            assert_eq!(downloaded.unwrap(), expected);
        }
    }
}

#[test]
fn authenticated_deb_bytes_pass_download_validation() {
    let root = fixture();
    let mut descriptor = super::tests::test_descriptor(vec![]);
    let bytes = fs::read(root.path().join("update.deb")).unwrap();
    descriptor.artifact.sha256 = sha256_bytes(&bytes);
    descriptor.artifact.size = bytes.len() as u64;
    let result = checked_installer_bytes(&UpdateCoordinator::default(), &descriptor, Ok(bytes));
    if cfg!(target_os = "linux") {
        assert!(
            result.is_ok(),
            "authenticated Debian package must pass platform format validation: {result:?}"
        );
    } else {
        assert!(result.is_err(), "Windows must still require MZ");
    }
}
