use super::*;
use std::os::unix::process::ExitStatusExt;

#[test]
fn linux_install_exit_classification_preserves_old_engine_only_when_safe() {
    for code in [0, 100, 126, 127, 1] {
        for changed in [false, true] {
            let result = classify_install(
                Ok(ExitStatus::from_raw(code << 8)),
                || Ok(!changed),
                Path::new("/staged/update.deb"),
            );
            if code == 0 {
                assert!(result.is_ok());
            } else {
                assert_eq!(
                    result.unwrap_err().repair_required,
                    changed && ![126, 127].contains(&code)
                );
            }
        }
    }
    for changed in [false, true] {
        for status in [
            Ok(ExitStatus::from_raw(9)),
            Err(std::io::Error::from(std::io::ErrorKind::NotFound)),
        ] {
            let error = classify_install(status, || Ok(!changed), Path::new("/staged/update.deb"))
                .unwrap_err();
            assert_eq!(error.repair_required, changed);
            if changed {
                assert!(error.message.contains("sudo apt install ./update.deb"));
            }
        }
    }
    assert!(
        classify_install(
            Ok(ExitStatus::from_raw(100 << 8)),
            || Err("unreadable".into()),
            Path::new("/staged/update.deb")
        )
        .unwrap_err()
        .repair_required
    );
}

#[test]
fn deb_format_requires_complete_version_member() {
    let deb = super::super::fixture_installer_bytes();
    assert!(validate_deb(deb).is_ok());
    for invalid in [
        b"MZfixture".as_slice(),
        b"!<arch>\n".as_slice(),
        &deb[..deb.len() - 1],
    ] {
        assert!(validate_deb(invalid).is_err());
    }
    let mut wrong_member = deb.to_vec();
    wrong_member[8..24].copy_from_slice(b"debian-binary x ");
    assert!(validate_deb(&wrong_member).is_err());
    let mut wrong = deb.to_vec();
    *wrong.last_mut().unwrap() = b'9';
    assert!(validate_deb(&wrong).is_err());
}

pub(in super::super) fn fake_launch(
    path: &Path,
    directory: &Path,
    outcome: &str,
) -> Result<(u32, u128), InstallerError> {
    use std::os::unix::fs::PermissionsExt;
    let fake_dir = directory.join("fake-bin");
    fs::create_dir_all(&fake_dir).unwrap();
    let code = outcome.split('-').nth(1).unwrap();
    let end = match code {
        "signal" => "kill -TERM $$".to_string(),
        "spawn" => String::new(),
        code => format!("exit {code}"),
    };
    if code != "spawn" {
        let fake = fake_dir.join("pkexec");
        fs::write(
            &fake,
            format!("#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$JUSTSEARCH_FAKE_ARGS\"\n{end}\n"),
        )
        .unwrap();
        fs::set_permissions(&fake, fs::Permissions::from_mode(0o700)).unwrap();
    }
    let changed = outcome.ends_with("changed");
    let mut calls = 0;
    launch_with(
        path,
        || {
            calls += 1;
            if outcome.ends_with("unknown") && calls > 1 {
                return Err("injected package query failure".into());
            }
            Ok(InstalledState {
                package: if changed && calls > 1 {
                    b"installed\n2\n".to_vec()
                } else {
                    b"installed\n1\n".to_vec()
                },
                files: BTreeMap::from([(
                    "/usr/bin/JustSearch".into(),
                    if (changed || outcome.ends_with("files")) && calls > 1 {
                        "new"
                    } else {
                        "old"
                    }
                    .into(),
                )]),
            })
        },
        |command| {
            // This child sees only the private fake directory: no failure can invoke host pkexec.
            command
                .env("PATH", &fake_dir)
                .env("JUSTSEARCH_FAKE_ARGS", directory.join("pkexec.args"));
        },
    )
}
