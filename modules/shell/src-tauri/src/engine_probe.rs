//! Bounded observations of the already-admitted Engine binding.

use std::time::Duration;

const MAX_STATUS_BYTES: usize = 1024 * 1024;

fn observe(
    port: u16,
    path: &str,
    timeout: Duration,
    read_status: bool,
) -> Option<serde_json::Value> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .ok()?;
    runtime.block_on(async {
        // reqwest requires an explicit provider even for HTTP in this crate's no-provider build.
        // Configure this client only; probes never follow redirects or leave loopback HTTP.
        let tls = rustls::ClientConfig::builder_with_provider(std::sync::Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_safe_default_protocol_versions()
        .ok()?
        .with_root_certificates(rustls::RootCertStore::empty())
        .with_no_client_auth();
        let client = reqwest::Client::builder()
            .tls_backend_preconfigured(tls)
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(timeout)
            .build()
            .ok()?;
        let mut response = client
            .get(format!("http://127.0.0.1:{port}{path}"))
            .send()
            .await
            .ok()?;
        if !read_status {
            // A valid HTTP response, including 503, proves responsiveness. No body is needed.
            return Some(serde_json::Value::Null);
        }
        if response.status() != reqwest::StatusCode::OK {
            return None;
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await.ok()? {
            if bytes.len() + chunk.len() > MAX_STATUS_BYTES {
                return None;
            }
            bytes.extend_from_slice(&chunk);
        }
        serde_json::from_slice(&bytes).ok()
    })
}

pub(crate) fn responds(port: u16, path: &str, timeout: Duration) -> bool {
    observe(port, path, timeout, false).is_some()
}

pub(crate) fn essential_ready(port: u16, timeout: Duration) -> bool {
    observe(port, "/api/status", timeout, true).is_some_and(|status| essential_status(&status))
}

fn essential_status(status: &serde_json::Value) -> bool {
    status
        .pointer("/components/head/state")
        .and_then(|v| v.as_str())
        == Some("LIFECYCLE_STATE_READY")
        && status.get("indexAvailable").and_then(|v| v.as_bool()) == Some(true)
        && status
            .pointer("/worker/core/indexHealthy")
            .and_then(|v| v.as_bool())
            == Some(true)
        && status
            .pointer("/readiness/components/indexServing/stale")
            .and_then(|v| v.as_bool())
            == Some(false)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::thread;
    use std::time::Instant;

    fn endpoint(response: &'static [u8], delay: Duration) -> (u16, thread::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(1)))
                .unwrap();
            let mut request = [0; 1024];
            let _ = socket.read(&mut request);
            thread::sleep(delay);
            let _ = socket.write_all(response);
        });
        (port, handle)
    }

    #[test]
    fn valid_503_is_live_but_silence_and_non_http_are_not() {
        let (port, server) = endpoint(
            b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n",
            Duration::ZERO,
        );
        assert!(responds(port, "/api/health", Duration::from_secs(1)));
        server.join().unwrap();
        let (port, server) = endpoint(b"not HTTP\r\n\r\n", Duration::ZERO);
        assert!(!responds(port, "/api/health", Duration::from_secs(1)));
        server.join().unwrap();
        let (port, server) = endpoint(b"", Duration::from_millis(250));
        let started = Instant::now();
        assert!(!responds(port, "/api/health", Duration::from_millis(50)));
        assert!(started.elapsed() < Duration::from_millis(200));
        server.join().unwrap();
    }

    #[test]
    fn essential_readiness_ignores_optional_ai_but_requires_live_index_and_api() {
        let mut status = serde_json::json!({
            "components": {"head": {"state": "LIFECYCLE_STATE_READY"}},
            "indexAvailable": true, "worker": {"core": {"indexHealthy": true}},
            "readiness": {"components": {
                "indexServing": {"state": "DEGRADED", "stale": false},
                "ai": {"state": "NOT_READY"}
            }}
        });
        assert!(essential_status(&status));
        for path in ["/indexAvailable", "/worker/core/indexHealthy"] {
            *status.pointer_mut(path).unwrap() = serde_json::json!(false);
            assert!(!essential_status(&status));
            *status.pointer_mut(path).unwrap() = serde_json::json!(true);
        }
        status["readiness"]["components"]["indexServing"]["stale"] = serde_json::json!(true);
        assert!(!essential_status(&status));
        status["readiness"]["components"]["indexServing"]["stale"] = serde_json::json!(false);
        status["components"]["head"]["state"] = serde_json::json!("LIFECYCLE_STATE_STOPPING");
        assert!(!essential_status(&status));
        assert!(!essential_status(&serde_json::json!({})));
    }
}
