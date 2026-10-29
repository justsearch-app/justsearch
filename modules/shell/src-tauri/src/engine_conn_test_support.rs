use crate::{
    binding::ManifestFields,
    engine_host::{EngineHost, PreparedCommand},
};
use std::io::{Read, Write};
use std::net::TcpListener;
use std::sync::Arc;
use std::time::{Duration, Instant};

pub(crate) struct Fixture {
    pub(crate) host: Arc<EngineHost>,
    pub(crate) dir: tempfile::TempDir,
}
impl Fixture {
    pub(crate) fn sleeper(port: u16) -> Self {
        let host = Arc::new(EngineHost::default());
        let mut command = crate::containment::engine_command("sleep").unwrap();
        command.arg("60");
        let pid = host.admit(PreparedCommand { command }).unwrap().pid;
        host.observe_manifest(&ManifestFields {
            incarnation: Some((host.session(), u64::from(host.incarnation_number()))),
            instance_id: Some("fixture-instance".into()),
            pid: Some(pid),
            api_port: Some(port),
            session_token: Some("fixture-token".into()),
            ..Default::default()
        });
        Self {
            host,
            dir: tempfile::tempdir().unwrap(),
        }
    }
    pub(crate) fn actor(&self) -> crate::shell_actuator::ShellActuator {
        crate::shell_actuator::ShellActuator::new(
            self.host.clone(),
            self.dir.path().into(),
            || Err("fixture already started".into()),
            |_| {},
        )
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        self.host.fixture_kill_and_reap();
    }
}

/// This process is an impostor relative to the fixture's separately owned Engine child.
/// It would answer with plausible identity/health/token JSON if sent any request bytes.
pub(crate) fn impostor() -> (u16, std::thread::JoinHandle<Vec<u8>>) {
    let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
    let port = listener.local_addr().unwrap().port();
    listener.set_nonblocking(true).unwrap();
    let reader = std::thread::spawn(move || {
        let deadline = Instant::now() + Duration::from_secs(3);
        let (mut stream, _) = loop {
            match listener.accept() {
                Ok(connection) => break connection,
                Err(error)
                    if error.kind() == std::io::ErrorKind::WouldBlock
                        && Instant::now() < deadline =>
                {
                    std::thread::sleep(Duration::from_millis(2))
                }
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => return Vec::new(),
                Err(error) => panic!("accept: {error}"),
            }
        };
        stream
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let mut bytes = vec![0; 8192];
        let n = stream.read(&mut bytes).unwrap();
        bytes.truncate(n);
        if n > 0 {
            let response = r#"{"instanceId":"fixture-instance","healthy":true,"accepted":true,"schemaVersion":1,"preparationId":"fake","shutdownNonce":"01234567890123456789012345678901","ready":true,"errorCode":"UI_TOKEN_REQUIRED"}"#;
            let _ = write!(
                stream,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{response}",
                response.len()
            );
        }
        bytes
    });
    (port, reader)
}

impl Fixture {
    pub(crate) fn server() -> Self {
        Self {
            host: Arc::new(EngineHost::default()),
            dir: tempfile::tempdir().unwrap(),
        }
    }

    pub(crate) fn start_server(&self, port: u16, instance: &str) -> u16 {
        let port_file = self.dir.path().join(instance);
        let script = r#"
import json,os,pathlib,socket,sys
server=socket.socket()
server.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
server.bind(('127.0.0.1',int(sys.argv[2])))
server.listen()
port_file=pathlib.Path(sys.argv[1])
pending=port_file.with_name(port_file.name+'.tmp')
pending.write_text(str(server.getsockname()[1]))
os.replace(pending,port_file)
while True:
 conn,_=server.accept()
 with conn:
  data=b''
  while b'\r\n\r\n' not in data:
   chunk=conn.recv(4096)
   if not chunk: break
   data+=chunk
  if not data: continue
  headers,body=data.split(b'\r\n\r\n',1)
  length=0
  for line in headers.split(b'\r\n')[1:]:
   key,value=line.split(b':',1)
   if key.lower()==b'content-length': length=int(value)
  while len(body)<length: body+=conn.recv(4096)
  expected=('X-JustSearch-Session: token-'+str(os.getpid())).encode()
  status=b'200 OK' if headers.startswith(b'GET ') or expected in headers else b'401 Unauthorized'
  response=json.dumps({'pid':os.getpid()}).encode()
  conn.sendall(b'HTTP/1.1 '+status+b'\r\nContent-Length: '+str(len(response)).encode()+b'\r\nConnection: close\r\n\r\n'+response)
  if headers.startswith(b'GET '):
   while conn.recv(4096): pass
"#;
        let mut command = crate::containment::engine_command("python3").unwrap();
        command
            .args(["-u", "-c", script])
            .arg(&port_file)
            .arg(port.to_string());
        let pid = self.host.admit(PreparedCommand { command }).unwrap().pid;
        let deadline = Instant::now() + Duration::from_secs(3);
        let port = loop {
            if let Some(port) = std::fs::read_to_string(&port_file)
                .ok()
                .and_then(|port| port.parse::<u16>().ok())
            {
                break port;
            }
            assert!(Instant::now() < deadline, "fixture server failed to start");
            std::thread::sleep(Duration::from_millis(2));
        };
        self.host.observe_manifest(&ManifestFields {
            incarnation: Some((self.host.session(), u64::from(self.host.incarnation_number()))),
            instance_id: Some(instance.into()),
            pid: Some(pid),
            api_port: Some(port),
            session_token: Some(format!("token-{pid}")),
            ..Default::default()
        });
        port
    }
}
