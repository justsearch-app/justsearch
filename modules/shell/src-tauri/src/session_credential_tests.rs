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
    fixture.host.consume_credential_line(1, "JUSTSEARCH_WINDOW_CREDENTIAL=first-window");
    assert!(session_credential(&state).is_none());
    state.observe_manifest(&ManifestFields {
        incarnation: Some((fixture.host.session(), u64::from(fixture.host.incarnation_number()))),
        instance_id: Some("first".into()),
        pid: Some(first_pid),
        session_token: Some("first-token".into()),
        ..Default::default()
    });
    assert!(
        session_credential(&state).is_none(),
        "assertion failed: token alone must not be issued"
    );
    state.observe_manifest(&ManifestFields {
        incarnation: Some((fixture.host.session(), u64::from(fixture.host.incarnation_number()))),
        instance_id: Some("first".into()),
        pid: Some(first_pid),
        api_port: Some(43311),
        ..Default::default()
    });
    let credential = session_credential(&state).unwrap();
    assert_eq!(credential.origin, "http://127.0.0.1:43311");
    assert_eq!(credential.token, "first-token");
    assert_eq!(credential.window_credential, "first-window");

    fixture.host.fixture_kill_and_reap();
    fixture.host.reset_for_successor().unwrap();
    let next_pid = admit();
    // A delayed predecessor drain must not install its secret on the next process.
    fixture.host.consume_credential_line(1, "JUSTSEARCH_WINDOW_CREDENTIAL=stale-window");
    state.observe_manifest(&ManifestFields {
        incarnation: Some((fixture.host.session(), u64::from(fixture.host.incarnation_number()))),
        instance_id: Some("next".into()),
        pid: Some(next_pid),
        api_port: Some(43312),
        ..Default::default()
    });
    assert!(
        session_credential(&state).is_none(),
        "assertion failed: new port must not inherit the predecessor token"
    );
    state.observe_manifest(&ManifestFields {
        incarnation: Some((fixture.host.session(), u64::from(fixture.host.incarnation_number()))),
        instance_id: Some("next".into()),
        pid: Some(next_pid),
        session_token: Some("next-token".into()),
        ..Default::default()
    });
    assert!(session_credential(&state).is_none(), "manifest alone must not issue a window credential");
    fixture.host.consume_credential_line(2, "JUSTSEARCH_WINDOW_CREDENTIAL=next-window");
    let credential = session_credential(&state).unwrap();
    assert_eq!(credential.window_credential, "next-window");
    assert_eq!(credential.origin, "http://127.0.0.1:43312");
    assert_eq!(credential.token, "next-token");
}

#[test]
fn secret_stdout_lines_are_consumed_before_logging_even_without_a_live_child() {
    let host = EngineHost::default();
    let lines = ["ordinary stdout", "JUSTSEARCH_SESSION_TOKEN=manifest-secret",
        "JUSTSEARCH_WINDOW_CREDENTIAL=window-secret", "JUSTSEARCH_API_PORT=33221"];
    let mut log = Vec::new();
    for line in lines {
        if !host.consume_credential_line(1, line) { log.push(line); }
    }
    assert_eq!(log, ["ordinary stdout", "JUSTSEARCH_API_PORT=33221"]);
    assert!(host.window_session_binding().is_none());
    // The production drain uses this filter before the file write.
    let source = include_str!("lib.rs");
    let drain = source.split("if let Some(stdout) = admitted.stdout").nth(1).unwrap()
        .split("if let Some(stderr)").next().unwrap();
    assert!(drain.find("consume_credential_line").unwrap() < drain.find("writeln!").unwrap());
}
