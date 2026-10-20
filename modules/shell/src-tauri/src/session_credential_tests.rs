use super::*;
use crate::engine_conn_test_support::Fixture;

#[test]
fn credential_waits_for_both_fields_and_keeps_their_incarnation_together() {
    let fixture = Fixture::server();
    let mut state = BackendState::default();
    state.host = fixture.host.clone();
    let admit = || {
        let mut command = crate::containment::engine_command("sleep").unwrap();
        command.arg("60");
        fixture.host.admit(PreparedCommand { command }).unwrap().pid
    };
    let first_pid = admit();
    assert!(session_credential(&state).is_none());
    state.observe_manifest(&ManifestFields {
        instance_id: Some("first".into()),
        pid: Some(first_pid),
        session_token: Some("first-token".into()),
        ..Default::default()
    });
    assert!(
        session_credential(&state).is_none(),
        "token alone must not be issued"
    );
    state.observe_manifest(&ManifestFields {
        instance_id: Some("first".into()),
        pid: Some(first_pid),
        api_port: Some(43311),
        ..Default::default()
    });
    let credential = session_credential(&state).unwrap();
    assert_eq!(credential.origin, "http://127.0.0.1:43311");
    assert_eq!(credential.token, "first-token");

    fixture.host.fixture_kill_and_reap();
    fixture.host.reset_for_successor().unwrap();
    let next_pid = admit();
    state.observe_manifest(&ManifestFields {
        instance_id: Some("next".into()),
        pid: Some(next_pid),
        api_port: Some(43312),
        ..Default::default()
    });
    assert!(
        session_credential(&state).is_none(),
        "new port must not inherit the predecessor token"
    );
    state.observe_manifest(&ManifestFields {
        instance_id: Some("next".into()),
        pid: Some(next_pid),
        session_token: Some("next-token".into()),
        ..Default::default()
    });
    let credential = session_credential(&state).unwrap();
    assert_eq!(credential.origin, "http://127.0.0.1:43312");
    assert_eq!(credential.token, "next-token");
}
