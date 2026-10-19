//! Linux process birth identity. Unreadable evidence remains unknown; a zombie is ended.
use serde::{Deserialize, Serialize};
use std::io;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Identity {
    kind: String,
    pub(crate) pid: u32,
    pub(crate) boot_id: String,
    pub(crate) start_ticks: String,
}

impl Identity {
    pub(crate) fn valid(&self, pid: u32) -> bool {
        self.kind == "linux-proc-v1"
            && pid > 0
            && pid <= i32::MAX as u32
            && self.pid == pid
            && uuid::Uuid::parse_str(&self.boot_id)
                .is_ok_and(|boot| boot.hyphenated().to_string() == self.boot_id)
            && self
                .start_ticks
                .parse::<u64>()
                .is_ok_and(|ticks| ticks.to_string() == self.start_ticks)
    }
}

fn stat(raw: &str) -> io::Result<(&str, &str)> {
    // comm may contain spaces and ')' characters. Field 3 follows the final ')'.
    let (_, rest) = raw
        .rsplit_once(')')
        .ok_or_else(|| io::Error::other("invalid proc stat"))?;
    let fields: Vec<_> = rest.split_whitespace().collect();
    let state = fields
        .first()
        .copied()
        .ok_or_else(|| io::Error::other("missing proc state"))?;
    let ticks = fields
        .get(19)
        .copied()
        .filter(|s| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit()))
        .ok_or_else(|| io::Error::other("invalid proc start ticks"))?;
    Ok((state, ticks))
}

fn proc_read_error(error: io::Error) -> io::Error {
    // Linux ESRCH (3) can occur when a process is reaped after opening its stat file.
    if (error.kind() == io::ErrorKind::NotFound || error.raw_os_error() == Some(3))
        && Path::new("/proc/self/stat").is_file()
    {
        io::Error::new(io::ErrorKind::NotFound, "process ended")
    } else {
        io::Error::other(format!("process birth identity unreadable: {error}"))
    }
}

fn birth(pid: u32) -> io::Result<Identity> {
    let raw = std::fs::read_to_string(format!("/proc/{pid}/stat")).map_err(proc_read_error)?;
    let (state, ticks) = stat(&raw)?;
    if matches!(state, "Z" | "X" | "x") {
        return Err(io::Error::new(io::ErrorKind::NotFound, "process ended"));
    }
    let boot = std::fs::read_to_string("/proc/sys/kernel/random/boot_id")
        .map_err(|error| io::Error::other(format!("boot identity unreadable: {error}")))?;
    let identity = Identity {
        kind: "linux-proc-v1".into(),
        pid,
        boot_id: boot.trim().into(),
        start_ticks: ticks.into(),
    };
    if !identity.valid(pid) {
        return Err(io::Error::other("invalid Linux identity"));
    }
    Ok(identity)
}

pub(crate) fn capture(pid: u32) -> io::Result<(Identity, PathBuf)> {
    let before = birth(pid)?;
    let executable = match std::fs::read_link(format!("/proc/{pid}/exe")) {
        Ok(executable) => executable,
        Err(error) => {
            // A missing exe link alone is not evidence that the process ended.
            birth(pid)?;
            return Err(io::Error::other(format!(
                "process executable unreadable: {error}"
            )));
        }
    };
    let after = birth(pid)?;
    if before != after || !executable.is_absolute() {
        return Err(io::Error::other("process changed during identity read"));
    }
    Ok((before, executable))
}

pub(crate) fn check(pid: u32, expected: Option<&Identity>) -> &'static str {
    // Missing or malformed saved evidence never authorises an 'absent' verdict.
    let Some(expected) = expected.filter(|id| id.valid(pid)) else {
        return "IDENTITY_UNKNOWN";
    };
    match birth(pid) {
        Ok(actual) if actual == *expected => "ALIVE_MATCH",
        Ok(_) => "GONE",
        Err(e) if e.kind() == io::ErrorKind::NotFound => "GONE",
        Err(_) => "IDENTITY_UNKNOWN",
    }
}

// Resolved by the same bundle-discovery path used for the Engine, never from PATH or a data dir.
static BOUND_EXEC: OnceLock<PathBuf> = OnceLock::new();
pub(crate) fn set_bundle_dir(headless: &Path) {
    let _ = BOUND_EXEC.set(headless.join(crate::native_targets::relative_path("bound-exec")));
}

pub(crate) fn bound_exec() -> io::Result<&'static Path> {
    #[cfg(test)]
    set_bundle_dir(&Path::new(env!("CARGO_MANIFEST_DIR")).join("../../ui/build/install/ui"));
    BOUND_EXEC.get().map(PathBuf::as_path).ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::NotFound,
            "Linux bound-exec bundle path is unavailable",
        )
    })
}

pub(crate) fn terminate(identity: &Identity, executable: &Path) -> Result<(), String> {
    if !identity.valid(identity.pid) || !executable.is_absolute() {
        return Err("Incomplete Linux managed child identity".into());
    }
    match capture(identity.pid) {
        Ok((seen, _)) if seen != *identity => return Ok(()),
        Ok((_, exe)) if exe != executable => eprintln!(
            "Managed child {} executable changed: {:?} -> {:?}; birth identity still matches",
            identity.pid, executable, exe
        ),
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(format!("Managed child identity unknown: {e}")),
        _ => {}
    }
    let helper = bound_exec().map_err(|e| e.to_string())?;
    // The helper opens a pidfd BEFORE rechecking the birth tuple and signals that object.
    let status = std::process::Command::new(helper)
        .args([
            "signal",
            &identity.pid.to_string(),
            &identity.boot_id,
            &identity.start_ticks,
            "SIGKILL",
        ])
        .status()
        .map_err(|e| format!("Linux identity-safe termination failed: {e}"))?;
    match status.code() {
        Some(0 | 3) => {}
        _ => return Err(format!("Linux identity-safe termination failed: {status}")),
    }
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    loop {
        match check(identity.pid, Some(identity)) {
            "GONE" => return Ok(()),
            "ALIVE_MATCH" if std::time::Instant::now() < deadline => {
                std::thread::sleep(std::time::Duration::from_millis(20))
            }
            state => {
                return Err(format!(
                    "Managed child {} termination unconfirmed: {state}",
                    identity.pid
                ))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stat_handles_spaces_and_parentheses_in_comm() {
        let raw =
            "123 (some name ) odd)) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 987654321 20";
        assert_eq!(stat(raw).unwrap(), ("S", "987654321"));
        assert!(stat("broken").is_err());
        assert!(stat("1 (ok) S 1").is_err());
    }
    #[test]
    fn identity_matches_only_the_same_birth_and_boot() {
        let pid = std::process::id();
        let (identity, exe) = capture(pid).unwrap();
        assert!(exe.is_absolute());
        assert_eq!(check(pid, Some(&identity)), "ALIVE_MATCH");
        let mut changed = identity.clone();
        changed.start_ticks.push('0');
        assert_eq!(check(pid, Some(&changed)), "GONE");
        changed = identity.clone();
        changed.boot_id = uuid::Uuid::new_v4().to_string();
        assert_eq!(check(pid, Some(&changed)), "GONE");
        changed.pid += 1;
        assert_eq!(check(pid, Some(&changed)), "IDENTITY_UNKNOWN");
        assert_eq!(check(pid, None), "IDENTITY_UNKNOWN");
        changed = identity.clone();
        changed.start_ticks = format!("0{}", identity.start_ticks);
        assert_eq!(check(pid, Some(&changed)), "IDENTITY_UNKNOWN");
        changed = identity.clone();
        changed.start_ticks = "999999999999999999999999999999999999".into();
        assert_eq!(check(pid, Some(&changed)), "IDENTITY_UNKNOWN");
        changed = identity.clone();
        changed.boot_id = uuid::Uuid::parse_str(&identity.boot_id)
            .unwrap()
            .simple()
            .to_string();
        assert_eq!(check(pid, Some(&changed)), "IDENTITY_UNKNOWN");
    }
    #[test]
    fn canonical_identity_encoding_agrees_with_the_other_readers() {
        let (identity, _) = capture(std::process::id()).unwrap();
        for ticks in ["0", "18446744073709551615"] {
            let mut candidate = identity.clone();
            candidate.start_ticks = ticks.into();
            assert!(candidate.valid(candidate.pid));
        }
        for ticks in ["00", "-1", "18446744073709551616"] {
            let mut candidate = identity.clone();
            candidate.start_ticks = ticks.into();
            assert!(!candidate.valid(candidate.pid));
        }
        let mut candidate = identity;
        candidate.pid = u32::MAX;
        assert!(!candidate.valid(candidate.pid));
    }

    #[test]
    fn reaped_process_stat_read_is_ended() {
        use std::io::Read;

        let mut child = std::process::Command::new("sleep")
            .arg("30")
            .spawn()
            .unwrap();
        let file = std::fs::File::open(format!("/proc/{}/stat", child.id()));
        child.kill().unwrap();
        child.wait().unwrap();

        let error = file
            .unwrap()
            .read_to_string(&mut String::new())
            .unwrap_err();
        assert_eq!(error.raw_os_error(), Some(3), "expected Linux ESRCH");
        let error = proc_read_error(error);
        assert_eq!(error.kind(), io::ErrorKind::NotFound);
        assert_eq!(error.to_string(), "process ended");
    }

    #[test]
    fn zombie_is_ended_without_requiring_executable_readability() {
        let mut child = std::process::Command::new("sleep")
            .arg("30")
            .spawn()
            .unwrap();
        let (identity, _) = capture(child.id()).unwrap();
        child.kill().unwrap();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        while check(child.id(), Some(&identity)) == "ALIVE_MATCH"
            && std::time::Instant::now() < deadline
        {
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
        let observed = check(child.id(), Some(&identity));
        child.wait().unwrap();
        assert_eq!(observed, "GONE");
    }
}
