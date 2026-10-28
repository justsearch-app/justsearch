use super::*;
use crate::binding::ManifestFields;
use crate::engine_conn_test_support::Fixture;
use crate::engine_host::PreparedCommand;

fn reconciliation_server(mode: &str) -> (Fixture, BackendState, ReconciliationRequest) {
    let fixture = Fixture::server();
    let port_file = fixture.dir.path().join("port");
    let script = r#"
import json,os,pathlib,socket,sys
server=socket.socket()
server.bind(('127.0.0.1',0))
server.listen()
pathlib.Path(sys.argv[1]).write_text(str(server.getsockname()[1]))
mode=sys.argv[2]
count=0
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
  assert headers.startswith(b'POST /api/upgrade/reconcile HTTP/1.1')
  assert ('X-JustSearch-Session: token-'+str(os.getpid())).encode() in headers
  request=json.loads(body)
  count+=1
  pathlib.Path(sys.argv[1]+'.requests').write_text(str(count))
  warming=mode=='never-ready' or (mode in ('warming','warming-invalid') and count<=2)
  status='503 Service Unavailable' if warming else '409 Conflict' if mode=='refused' else '200 OK'
  response={k:request[k] for k in ('schemaVersion','attemptId','stopEvidenceKind','shutdownNonce','targetVersion','headPid','owners')}
  response.update(ready=not warming,headReady=True,workerReady=not warming)
  response['owners']=[dict(o,healthy=not warming) for o in request['owners']]
  if warming: response['error']='Worker is not ready'
  if mode=='refused': response.update(ready=False,error='Request identity does not match the durable reconciling intent')
  if mode=='warming-invalid' and not warming: response['attemptId']='another-attempt'
  response=json.dumps(response).encode()
  conn.sendall(('HTTP/1.1 '+status+'\r\nContent-Length: '+str(len(response))+'\r\nConnection: close\r\n\r\n').encode()+response)
"#;
    let mut command = crate::containment::engine_command("python3").unwrap();
    command.args(["-u", "-c", script]).arg(&port_file).arg(mode);
    let pid = fixture.host.admit(PreparedCommand { command }).unwrap().pid;
    let deadline = Instant::now() + Duration::from_secs(5);
    let port = loop {
        if let Some(port) = fs::read_to_string(&port_file)
            .ok()
            .and_then(|port| port.parse::<u16>().ok())
        {
            break port;
        }
        assert!(
            Instant::now() < deadline,
            "reconciliation server startup timed out"
        );
        std::thread::sleep(Duration::from_millis(2));
    };
    fixture.host.observe_manifest(&ManifestFields {
        instance_id: Some("reconciliation-fixture".into()),
        pid: Some(pid),
        api_port: Some(port),
        session_token: Some(format!("token-{pid}")),
        ..Default::default()
    });
    let mut backend = BackendState::default();
    backend.host = fixture.host.clone();
    let request = ReconciliationRequest {
        schema_version: 1,
        attempt_id: "attempt-1".into(),
        stop_evidence_kind: "PREPARED".into(),
        shutdown_nonce: "n".repeat(32),
        source_version: "0.9.0".into(),
        target_version: "0.9.1".into(),
        release_sequence: 3,
        head_pid: u64::from(pid),
        owners: vec![OwnerExpectation {
            owner_id: "preferences".into(),
            format_version: 1,
        }],
    };
    (fixture, backend, request)
}

async fn reconcile_fixture(mode: &str) -> (UpgradeIntent, AppUpdateStatus, usize) {
    let (fixture, backend, request) = reconciliation_server(mode);
    let mut intent: UpgradeIntent = serde_json::from_value(serde_json::json!({
        "schemaVersion":1,"phase":"RECONCILING","attemptId":request.attempt_id,
        "preparationId":"prep-1","shutdownNonce":request.shutdown_nonce,
        "stagedArtifact":{"path":"unused","sha256":"unused","size":1},
        "ownerExpectations":request.owners,"sourceVersion":request.source_version,
        "targetVersion":request.target_version,"releaseSequence":request.release_sequence,
        "updatedAtEpochMs":1
    }))
    .unwrap();
    let response = post_head::<ReconciliationResponse>(
        &backend,
        "/api/upgrade/reconcile",
        Some(serde_json::to_value(&request).unwrap()),
    )
    .await;
    let result =
        response.and_then(|response| validate_reconciliation_response(&request, &response));
    apply_reconciliation_outcome(&mut intent, result).unwrap();
    let coordinator = UpdateCoordinator::default();
    persist_intent_at(fixture.dir.path(), &coordinator, &intent).unwrap();
    let persisted = read_intent(&fixture.dir.path().join("intent.v1.json"))
        .unwrap()
        .unwrap();
    let count = fs::read_to_string(fixture.dir.path().join("port.requests"))
        .unwrap()
        .parse()
        .unwrap();
    (persisted, coordinator.snapshot(), count)
}

#[tokio::test]
async fn reconciliation_retries_not_ready_then_commits_verified_response() {
    let (intent, status, count) = reconcile_fixture("warming").await;
    assert_eq!(
        intent.phase,
        UpgradePhase::Committed,
        "503 startup must wait for the valid attestation: {:?}",
        intent.error
    );
    assert_eq!(status.state, "committed");
    assert_eq!(count, 3);
}

#[tokio::test]
async fn reconciliation_refusal_requires_repair_without_retry() {
    let (intent, status, count) = reconcile_fixture("refused").await;
    assert_eq!(intent.phase, UpgradePhase::RepairRequired);
    assert_eq!(status.state, "repair_required");
    assert!(intent.error.unwrap().contains("409"));
    assert_eq!(count, 1, "a real refusal must be terminal");
}

#[tokio::test]
async fn reconciliation_retry_still_rejects_invalid_attestation() {
    let (intent, status, count) = reconcile_fixture("warming-invalid").await;
    assert_eq!(intent.phase, UpgradePhase::RepairRequired);
    assert_eq!(status.state, "repair_required");
    assert!(intent.error.unwrap().contains("attestation is invalid"));
    assert_eq!(count, 3);
}

#[tokio::test]
async fn reconciliation_never_ready_times_out_into_repair() {
    let started = Instant::now();
    let (intent, status, count) = reconcile_fixture("never-ready").await;
    assert_eq!(intent.phase, UpgradePhase::RepairRequired);
    assert_eq!(status.state, "repair_required");
    assert!(intent
        .error
        .unwrap()
        .contains("did not become ready before timeout"));
    assert!(count > 1, "503 must be retried rather than accepted");
    assert!(
        started.elapsed() < BACKEND_READY_TIMEOUT + Duration::from_secs(5),
        "readiness wait must be bounded"
    );
}
