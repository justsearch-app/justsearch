use super::*;
use std::io::Read;
use std::net::TcpListener;

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
