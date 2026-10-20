use super::*;
use crate::engine_conn_test_support::{impostor, Fixture};

async fn rejects_impostor(path: &str) {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let (port, reader) = impostor();
    let fixture = Fixture::sleeper(port);
    let mut backend = BackendState::default();
    backend.host = fixture.host.clone();
    let result: Result<serde_json::Value, _> =
        post_head(&backend, path, Some(serde_json::json!({}))).await;
    assert!(
        result.is_err(),
        "assertion failed: {path} must reject an unproven server"
    );
    assert!(result.unwrap_err().contains("not proven"), "{path}");
    assert!(
        reader.join().unwrap().is_empty(),
        "{path} disclosed request bytes"
    );
}

#[tokio::test]
async fn prepare_rejects_impostor() {
    rejects_impostor("/api/upgrade/prepare").await;
}
#[tokio::test]
async fn cancel_rejects_impostor() {
    rejects_impostor("/api/upgrade/cancel").await;
}
#[tokio::test]
async fn commit_rejects_impostor() {
    rejects_impostor("/api/upgrade/commit-shutdown").await;
}
#[tokio::test]
async fn reconcile_rejects_impostor() {
    rejects_impostor("/api/upgrade/reconcile").await;
}
