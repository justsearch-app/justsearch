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

/// Component-owned READY epoch; elapsed time is measured by the host's monotonic clock.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct ReadyEpoch(String);

impl ReadyEpoch {
    pub(crate) fn parse(value: &str) -> Option<Self> {
        // Engine Instant serialization is UTC, with up to nanosecond precision.
        let bytes = value.as_bytes();
        if !(20..=30).contains(&bytes.len()) || !value.is_ascii()
            || bytes[4] != b'-' || bytes[7] != b'-' || bytes[10] != b'T'
            || bytes[13] != b':' || bytes[16] != b':' || bytes.last() != Some(&b'Z') {
            return None;
        }
        if bytes.len() > 20 && (bytes[19] != b'.' || bytes.len() < 22
            || !bytes[20..bytes.len()-1].iter().all(u8::is_ascii_digit)) {
            return None;
        }
        let number = |start: usize, end: usize| -> Option<u32> {
            let text = &value[start..end];
            text.bytes().all(|b| b.is_ascii_digit()).then(|| text.parse().ok()).flatten()
        };
        let year = number(0, 4)?;
        let month = number(5, 7)?;
        let day = number(8, 10)?;
        let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
        let days = match month {
            2 => if leap { 29 } else { 28 },
            4 | 6 | 9 | 11 => 30,
            1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
            _ => return None,
        };
        if !(1..=days).contains(&day) || number(11, 13)? > 23
            || number(14, 16)? > 59 || number(17, 19)? > 59 {
            return None;
        }
        Some(Self(value.into()))
    }
}

pub(crate) fn essential_ready(port: u16, timeout: Duration) -> Option<ReadyEpoch> {
    essential_status(&observe(port, "/api/status", timeout, true)?)
}

fn essential_status(status: &serde_json::Value) -> Option<ReadyEpoch> {
    let index = status.pointer("/readiness/engineComponents/index")?;
    if index.get("state")?.as_str()? != "READY" { return None; }
    ReadyEpoch::parse(index.get("stateSince")?.as_str()?)
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
    fn essential_readiness_requires_the_engine_ready_epoch() {
        let epoch = "2026-09-21T12:00:00.123456789Z";
        let mut status = serde_json::json!({"readiness": {"engineComponents": {
            "index": {"state": "READY", "stateSince": epoch}
        }}});
        assert_eq!(essential_status(&status), ReadyEpoch::parse(epoch));
        for state in ["ABSENT", "STARTING", "RELOADING", "FAILED", "UNAVAILABLE", "UNKNOWN"] {
            status["readiness"]["engineComponents"]["index"]["state"] = serde_json::json!(state);
            assert_eq!(essential_status(&status), None);
        }
        status["readiness"]["engineComponents"]["index"]["state"] = serde_json::json!("READY");
        for invalid in ["", "yesterday", "2026-02-30T12:00:00Z", "2026-09-21T24:00:00Z", "2026-09-21", "2026-09-21T12:00:00.Z"] {
            status["readiness"]["engineComponents"]["index"]["stateSince"] = serde_json::json!(invalid);
            assert_eq!(essential_status(&status), None, "{invalid}");
        }
        assert_eq!(essential_status(&serde_json::json!({})), None);
        assert_ne!(ReadyEpoch::parse(epoch), ReadyEpoch::parse("2026-09-21T12:01:00Z"));
    }
}
