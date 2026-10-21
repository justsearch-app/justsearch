//! Prove the server end of this connection before writing any application bytes.
use std::net::{SocketAddr, TcpStream};
use std::time::{Duration, Instant};

pub(crate) fn not_proven(reason: impl std::fmt::Display) -> String {
    format!("Engine connection not proven: {reason}")
}

pub(crate) fn connect_proven(
    port: u16,
    expected_pid: u32,
    timeout: Duration,
) -> Result<TcpStream, String> {
    let deadline = Instant::now() + timeout;
    let server = SocketAddr::from(([127, 0, 0, 1], port));
    let stream = TcpStream::connect_timeout(&server, timeout).map_err(not_proven)?;
    let client = stream.local_addr().map_err(not_proven)?;
    loop {
        if Instant::now() >= deadline {
            return Err(not_proven("ownership proof timed out"));
        }
        if server_owned_by(server, client, expected_pid).map_err(not_proven)? {
            if Instant::now() >= deadline {
                return Err(not_proven("ownership proof timed out"));
            }
            return Ok(stream);
        }
        // Linux does not assign the accepted socket an inode until accept().
        std::thread::sleep(
            Duration::from_millis(2).min(deadline.saturating_duration_since(Instant::now())),
        );
    }
}

#[cfg(target_os = "linux")]
fn server_owned_by(server: SocketAddr, client: SocketAddr, pid: u32) -> Result<bool, String> {
    fn endpoint(raw: &str) -> Option<SocketAddr> {
        let (address, port) = raw.split_once(':')?;
        let address = match address.len() {
            8 => address,
            32 if address.starts_with("0000000000000000FFFF0000") => &address[24..],
            _ => return None,
        };
        let address = u32::from_str_radix(address, 16).ok()?.to_ne_bytes();
        Some(SocketAddr::from((
            address,
            u16::from_str_radix(port, 16).ok()?,
        )))
    }
    let mut inode = None;
    for table in ["tcp", "tcp6"] {
        let raw = match std::fs::read_to_string(format!("/proc/net/{table}")) {
            Ok(raw) => raw,
            Err(e) if table == "tcp6" && e.kind() == std::io::ErrorKind::NotFound => continue,
            Err(e) => return Err(format!("TCP table unavailable: {e}")),
        };
        for row in raw.lines().skip(1) {
            let fields: Vec<_> = row.split_whitespace().collect();
            if fields.len() > 9
                && fields[3] == "01"
                && endpoint(fields[1]) == Some(server)
                && endpoint(fields[2]) == Some(client)
            {
                inode = Some(fields[9].to_string());
            }
        }
    }
    let Some(inode) = inode.filter(|inode| inode != "0") else {
        return Ok(false);
    };
    let fds = std::fs::read_dir(format!("/proc/{pid}/fd"))
        .map_err(|e| format!("process sockets unavailable: {e}"))?;
    let wanted = std::path::PathBuf::from(format!("socket:[{inode}]"));
    for fd in fds {
        let fd = fd.map_err(|e| e.to_string())?;
        if std::fs::read_link(fd.path()).is_ok_and(|target| target == wanted) {
            return Ok(true);
        }
    }
    Err("server socket belongs to another process".into())
}

#[cfg(windows)]
#[path = "engine_conn/windows.rs"]
mod windows;
#[cfg(windows)]
use windows::server_owned_by;

#[cfg(not(any(target_os = "linux", windows)))]
fn server_owned_by(_: SocketAddr, _: SocketAddr, _: u32) -> Result<bool, String> {
    Err("socket ownership proof unavailable on this platform".into())
}

/// The boolean records the first attempted write, including a partial/failed write.
pub(crate) fn post_connected(
    mut stream: TcpStream,
    port: u16,
    token: &str,
    path: &str,
    body: &serde_json::Value,
    timeout: Duration,
    before_send: impl FnOnce() -> bool,
) -> (Result<serde_json::Value, String>, bool) {
    use std::io::{Read, Write};
    let mut sent = false;
    let result = (|| {
        if token.contains(['\r', '\n']) || path.contains(['\r', '\n', ' ']) {
            return Err("Invalid Engine request".into());
        }
        stream
            .set_read_timeout(Some(timeout))
            .map_err(|e| e.to_string())?;
        stream
            .set_write_timeout(Some(timeout))
            .map_err(|e| e.to_string())?;
        let body = body.to_string();
        if !before_send() {
            return Err("Engine exited or quit before upgrade commit request".into());
        }
        sent = true;
        write!(stream,"POST {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nX-JustSearch-Session: {token}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).map_err(|e|e.to_string())?;
        let mut response = Vec::new();
        stream
            .take(1024 * 1024 + 1)
            .read_to_end(&mut response)
            .map_err(|e| e.to_string())?;
        if response.len() > 1024 * 1024 {
            return Err("Engine response too large".into());
        }
        let split = response
            .windows(4)
            .position(|s| s == b"\r\n\r\n")
            .ok_or("Malformed HTTP response")?;
        let headers = std::str::from_utf8(&response[..split]).map_err(|e| e.to_string())?;
        let status = headers
            .lines()
            .next()
            .and_then(|line| line.split_whitespace().nth(1))
            .and_then(|code| code.parse::<u16>().ok())
            .ok_or("Malformed HTTP status")?;
        if !(200..300).contains(&status) {
            return Err(format!("Engine request rejected ({status})"));
        }
        let body = &response[split + 4..];
        let chunked = headers.lines().skip(1).any(|line| {
            line.split_once(':').is_some_and(|(name, value)| {
                name.eq_ignore_ascii_case("transfer-encoding")
                    && value.trim().eq_ignore_ascii_case("chunked")
            })
        });
        let decoded;
        let body = if chunked {
            decoded = decode_chunks(body)?;
            &decoded
        } else {
            body
        };
        serde_json::from_slice(body).map_err(|e| format!("Invalid Engine JSON response: {e}"))
    })();
    (result, sent)
}

fn decode_chunks(mut body: &[u8]) -> Result<Vec<u8>, String> {
    let mut decoded = Vec::new();
    loop {
        let end = body
            .windows(2)
            .position(|s| s == b"\r\n")
            .ok_or("Malformed HTTP chunk")?;
        let size = std::str::from_utf8(&body[..end])
            .map_err(|e| e.to_string())?
            .split(';')
            .next()
            .ok_or("Missing HTTP chunk size")?;
        let size = usize::from_str_radix(size, 16).map_err(|e| e.to_string())?;
        body = &body[end + 2..];
        if size == 0 {
            return Ok(decoded);
        }
        if size > body.len().saturating_sub(2) || &body[size..size + 2] != b"\r\n" {
            return Err("Truncated HTTP chunk".into());
        }
        decoded.extend_from_slice(&body[..size]);
        body = &body[size + 2..];
    }
}

#[cfg(all(test, target_os = "linux"))]
#[path = "engine_conn_tests.rs"]
mod tests;
