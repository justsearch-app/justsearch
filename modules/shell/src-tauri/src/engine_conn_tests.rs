use super::*;
use std::io::{Read, Write};
use std::net::TcpListener;

#[test]
fn exact_connection_is_proven_again_after_port_takeover() {
    let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
    let port = listener.local_addr().unwrap().port();
    let reader = std::thread::spawn(move || {
        for _ in 0..2 {
            let (mut stream, _) = listener.accept().unwrap();
            let mut buf = [0; 1];
            assert_eq!(stream.read(&mut buf).unwrap(), 0);
        }
    });
    drop(connect_proven(port, std::process::id(), Duration::from_secs(1)).unwrap());
    let error = connect_proven(port, u32::MAX, Duration::from_secs(1)).unwrap_err();
    assert!(error.contains("not proven"));
    reader.join().unwrap();
}

#[test]
fn accepted_socket_must_belong_to_expected_pid_and_timeout_writes_nothing() {
    let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
    let port = listener.local_addr().unwrap().port();
    let error = connect_proven(port, std::process::id(), Duration::from_millis(20)).unwrap_err();
    assert!(error.contains("not proven") && error.contains("timed out"));
    let (mut stream, _) = listener.accept().unwrap();
    assert_eq!(stream.read(&mut [0; 1]).unwrap(), 0);
}

#[test]
fn chunked_engine_json_is_decoded_and_truncated_chunks_fail() {
    assert_eq!(decode_chunks(b"2\r\n{}\r\n0\r\n\r\n").unwrap(), b"{}");
    assert!(decode_chunks(b"3\r\n{}").is_err());
}

#[test]
fn proven_sender_accepts_content_length_and_chunked_responses() {
    for body in [
        "Content-Length: 2\r\n\r\n{}",
        "Transfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n",
    ] {
        let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let reader = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut request = Vec::new();
            while !request.ends_with(b"\r\n\r\n{}") {
                let mut chunk = [0; 1024];
                let n = stream.read(&mut chunk).unwrap();
                assert!(n > 0);
                request.extend_from_slice(&chunk[..n]);
            }
            write!(stream, "HTTP/1.1 200 OK\r\n{body}").unwrap();
        });
        let stream = connect_proven(port, std::process::id(), Duration::from_secs(1)).unwrap();
        let (value, sent) = post_connected(
            stream,
            port,
            "fixture-token",
            "/prepare",
            &serde_json::json!({}),
            Duration::from_secs(1),
            || true,
        );
        assert_eq!(value.unwrap(), serde_json::json!({}));
        assert!(sent);
        reader.join().unwrap();
    }
}
