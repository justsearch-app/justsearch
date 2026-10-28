use super::{sha256_bytes, InstallerError};
use std::collections::BTreeMap;
use std::fs;
use std::os::unix::fs::MetadataExt;
use std::path::Path;
use std::process::{Command, ExitStatus};

/// Match the Debian ar container and the mandatory version member before elevation.
pub(super) fn validate_deb(bytes: &[u8]) -> Result<(), String> {
    if !bytes.starts_with(b"!<arch>\n") {
        return Err("Authenticated updater artifact is not a Debian package".into());
    }
    let mut offset = 8usize;
    while let Some(header) = bytes.get(offset..offset.saturating_add(60)) {
        if &header[58..60] != b"`\n" {
            break;
        }
        let size = std::str::from_utf8(&header[48..58])
            .ok()
            .and_then(|value| value.trim().parse::<usize>().ok())
            .ok_or("Debian package has an invalid ar member size")?;
        let start = offset
            .checked_add(60)
            .ok_or("Debian package member overflow")?;
        let end = start
            .checked_add(size)
            .ok_or("Debian package member overflow")?;
        let content = bytes
            .get(start..end)
            .ok_or("Debian package member is truncated")?;
        let name = std::str::from_utf8(&header[..16]).unwrap_or("").trim();
        if matches!(name, "debian-binary" | "debian-binary/") {
            return if content == b"2.0\n" {
                Ok(())
            } else {
                Err("Debian package has an unsupported debian-binary version".into())
            };
        }
        offset = end
            .checked_add(size % 2)
            .ok_or("Debian package member overflow")?;
    }
    Err("Debian package has no debian-binary member".into())
}

// Include the package database state and actual bytes. Version equality alone cannot establish
// that a failed apt left an installation safe to resume (unpack may have partially completed).
#[derive(Debug, PartialEq, Eq)]
struct InstalledState {
    package: Vec<u8>,
    files: BTreeMap<String, String>,
}

fn installed_state() -> Result<InstalledState, String> {
    let query = Command::new("dpkg-query")
        .args([
            "--show",
            "--showformat=${db:Status-Status}\n${Version}\n${Conffiles}\n",
            "just-search",
        ])
        .output()
        .map_err(|error| format!("Cannot inspect installed package: {error}"))?;
    if !query.status.success() {
        return Err(format!(
            "Cannot inspect installed just-search package: {}",
            String::from_utf8_lossy(&query.stderr)
        ));
    }
    if !query.stdout.starts_with(b"installed\n") {
        return Err("Installed just-search package is not fully configured".into());
    }
    let listing = Command::new("dpkg-query")
        .args(["--listfiles", "just-search"])
        .output()
        .map_err(|error| format!("Cannot inspect installed files: {error}"))?;
    if !listing.status.success() {
        return Err("Cannot list installed package files".into());
    }
    let listing = String::from_utf8(listing.stdout).map_err(|error| error.to_string())?;
    let mut files = BTreeMap::new();
    for name in listing.lines() {
        let path = Path::new(name);
        if !path.is_absolute() {
            return Err("Package file list contains a relative path".into());
        }
        let state = match fs::symlink_metadata(path) {
            Ok(metadata) => {
                let contents = if metadata.is_file() {
                    sha256_bytes(
                        &fs::read(path)
                            .map_err(|error| format!("Cannot inspect {name}: {error}"))?,
                    )
                } else if metadata.file_type().is_symlink() {
                    format!(
                        "link:{:?}",
                        fs::read_link(path).map_err(|error| error.to_string())?
                    )
                } else if metadata.is_dir() {
                    "directory".into()
                } else {
                    return Err(format!("Unsupported installed package file type: {name}"));
                };
                format!(
                    "{}:{}:{}:{contents}",
                    metadata.mode(),
                    metadata.uid(),
                    metadata.gid()
                )
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => "missing".into(),
            Err(error) => return Err(format!("Cannot inspect {name}: {error}")),
        };
        files.insert(name.to_owned(), state);
    }
    if files.is_empty() {
        return Err("Installed package file list is empty".into());
    }
    Ok(InstalledState {
        package: query.stdout,
        files,
    })
}

fn install_command(path: &Path) -> Command {
    let mut command = Command::new("pkexec");
    command
        .arg("env")
        .arg("DEBIAN_FRONTEND=noninteractive")
        .arg("apt-get")
        .arg("install")
        .arg("-y")
        .arg("-o")
        .arg("DPkg::Lock::Timeout=300")
        .arg(path);
    command
}

fn classify_install(
    status: Result<ExitStatus, std::io::Error>,
    unchanged: impl FnOnce() -> Result<bool, String>,
    path: &Path,
) -> Result<(), InstallerError> {
    match status {
        Ok(status) if status.success() => return Ok(()),
        Ok(status) if matches!(status.code(), Some(126 | 127)) => {
            return Err(
                "Installation authorisation was cancelled or refused; try the update again".into(),
            );
        }
        other => {
            let failure = match other {
                Ok(status) => format!("Installer exited with {status}"),
                Err(error) => format!("Installer could not run: {error}"),
            };
            match unchanged() {
                Ok(true) => Err(failure.into()),
                state => Err(InstallerError {
                    message: format!("{failure}; installed package changed or could not be checked ({state:?}). Reinstall manually: sudo apt install '{}'",
                        path.to_string_lossy().replace('\'', "'\\''")),
                    repair_required: true,
                }),
            }
        }
    }
}

pub(super) fn launch(path: &Path) -> Result<(u32, u128), InstallerError> {
    launch_with(path, installed_state, |_| {})
}

fn launch_with(
    path: &Path,
    mut snapshot: impl FnMut() -> Result<InstalledState, String>,
    configure: impl FnOnce(&mut Command),
) -> Result<(u32, u128), InstallerError> {
    let path = fs::canonicalize(path).map_err(|error| error.to_string())?;
    validate_deb(&fs::read(&path).map_err(|error| error.to_string())?)?;
    let before = snapshot()?;
    let launched_at = super::now_epoch_ms();
    let mut command = install_command(&path);
    configure(&mut command);
    let mut child = match command.spawn() {
        Ok(child) => child,
        Err(error) => {
            return classify_install(
                Err(error),
                || snapshot().map(|after| after == before),
                &path,
            )
            .map(|()| unreachable!());
        }
    };
    let pid = child.id();
    classify_install(
        child.wait(),
        || snapshot().map(|after| after == before),
        &path,
    )?;
    Ok((pid, launched_at))
}

#[cfg(test)]
#[path = "updater_linux_tests.rs"]
pub(super) mod tests;
