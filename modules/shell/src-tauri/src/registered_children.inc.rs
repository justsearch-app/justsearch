#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct ManagedChildRecord {
    pid: u32,
    #[serde(default)]
    started_at: String,
    executable: String,
    #[cfg(target_os = "linux")]
    #[serde(rename = "processIdentity")]
    identity: Option<linux_process::Identity>,
}

/// Terminal fallback reports incomplete ownership; recoverable supervision never calls it.
fn cleanup_registered_children_for_terminal(data_dir: &std::path::Path) {
    if let Err(error) = reconcile_registered_children(data_dir) {
        eprintln!("Registered child cleanup incomplete: {error}");
    }
}

/// Shared terminal/update edge. Read and validate the entire ownership projection before killing
/// anything. The Engine remains the manifest writer; a host never edits this child register.
fn reconcile_registered_children(data_dir: &std::path::Path) -> Result<(), String> {
    let path = data_dir.join("runtime").join("manifest.json");
    let raw = match std::fs::read(&path) {
        Ok(raw) => raw,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(format!("Cannot read child ownership: {error}")),
    };
    let root: serde_json::Value = serde_json::from_slice(&raw)
        .map_err(|error| format!("Malformed child ownership manifest: {error}"))?;
    let version = root.get("schemaVersion").and_then(|value| value.as_u64());
    if !matches!(version, Some(1 | 2)) {
        return Err("Unknown child ownership manifest schema".into());
    }
    let values = match root.get("children") {
        Some(serde_json::Value::Array(children)) => children,
        None if version == Some(1) => return Ok(()),
        _ => return Err("Malformed or missing managed children array".into()),
    };
    let children: Vec<ManagedChildRecord> = values
        .iter()
        .map(|value| {
            let child: ManagedChildRecord = serde_json::from_value(value.clone())
                .map_err(|error| format!("Malformed managed child identity: {error}"))?;
            if child.pid == 0
                || (cfg!(windows) && child.started_at.trim().is_empty())
                || !std::path::Path::new(&child.executable).is_absolute()
            {
                return Err("Incomplete managed child identity".into());
            }
            #[cfg(target_os = "linux")]
            if !child
                .identity
                .as_ref()
                .is_some_and(|id| id.valid(child.pid))
            {
                return Err("Incomplete Linux managed child identity".into());
            }
            Ok(child)
        })
        .collect::<Result<_, String>>()?;
    if !children.is_empty() {
        let owner = root.get("pid").and_then(|value| value.as_u64())
            .and_then(|pid| u32::try_from(pid).ok()).filter(|pid| *pid > 0)
            .ok_or("Missing Engine owner; refusing terminal child cleanup")?;
        // A losing shell has no authority over an incumbent's helpers. Only positive evidence
        // that the recorded Engine PID is gone permits this fallback; unknown is a refusal.
        if !terminal_owner_gone(owner)? {
            return Err("Engine owner is still alive; refusing terminal child cleanup".into());
        }
    }
    for child in children {
        terminal_kill_if_identity_matches(&child)?;
    }
    Ok(())
}

#[cfg(target_os = "linux")]
fn terminal_owner_gone(pid: u32) -> Result<bool, String> {
    match linux_process::capture(pid) {
        Ok(_) => Ok(false),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(true),
        Err(error) => Err(format!("Engine owner identity unavailable: {error}")),
    }
}

#[cfg(windows)]
fn terminal_owner_gone(pid: u32) -> Result<bool, String> {
    use windows_sys::Win32::{
        Foundation::{CloseHandle, GetLastError, ERROR_INVALID_PARAMETER, STILL_ACTIVE},
        System::Threading::{GetExitCodeProcess, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION},
    };
    unsafe {
        let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if handle.is_null() {
            return if GetLastError() == ERROR_INVALID_PARAMETER { Ok(true) }
                else { Err("Engine owner identity unavailable".into()) };
        }
        let mut code = 0;
        let ok = GetExitCodeProcess(handle, &mut code);
        CloseHandle(handle);
        if ok == 0 { return Err("Engine owner exit status unavailable".into()); }
        Ok(code != STILL_ACTIVE as u32)
    }
}

#[cfg(not(any(windows, target_os = "linux")))]
fn terminal_owner_gone(_pid: u32) -> Result<bool, String> {
    Err("Engine owner identity check is unsupported on this platform".into())
}

#[cfg(windows)]
fn terminal_kill_if_identity_matches(child: &ManagedChildRecord) -> Result<(), String> {
    // Capture the OS process handle before checking identity, and terminate through that object.
    // A PID reused between validation and termination cannot redirect the destructive operation.
    // Values cross the process boundary as environment variables, never interpolated command text.
    let script = r#"
$ErrorActionPreference='Stop'
try { $p=[Diagnostics.Process]::GetProcessById([int]$env:JUSTSEARCH_CHILD_PID) }
catch [ArgumentException] { exit 0 }
try {
  $null=$p.Handle
  $expected=[DateTimeOffset]::Parse($env:JUSTSEARCH_CHILD_STARTED).ToUnixTimeMilliseconds()
  $actual=([DateTimeOffset]$p.StartTime).ToUnixTimeMilliseconds()
  if ($actual -ne $expected) { exit 10 }
  $want=[IO.Path]::GetFullPath($env:JUSTSEARCH_CHILD_EXE).TrimEnd('\').ToLowerInvariant()
  $got=[IO.Path]::GetFullPath($p.Path).TrimEnd('\').ToLowerInvariant()
  if ($want -ne $got) { exit 11 }
  if (!$p.HasExited) { $p.Kill() }
  if (!$p.WaitForExit(5000)) { exit 12 }
  exit 0
} catch { exit 20 } finally { $p.Dispose() }
"#;
    use std::os::windows::process::CommandExt;
    use std::time::Duration;
    let mut process = std::process::Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-Command", script])
        .env("JUSTSEARCH_CHILD_PID", child.pid.to_string())
        .env("JUSTSEARCH_CHILD_STARTED", &child.started_at)
        .env("JUSTSEARCH_CHILD_EXE", &child.executable)
        .creation_flags(0x08000000)
        .spawn()
        .map_err(|error| format!("Child identity check could not start: {error}"))?;
    let deadline = std::time::Instant::now() + Duration::from_secs(15);
    loop {
        match process.try_wait() {
            Ok(Some(status)) => {
                return match status.code() {
                    // Already dead, confirmed terminated, or positively a different process.
                    Some(0 | 10 | 11) => Ok(()),
                    _ => Err(format!(
                        "Managed child {} could not be safely reconciled ({status})",
                        child.pid
                    )),
                };
            }
            Ok(None) if std::time::Instant::now() < deadline => {
                std::thread::sleep(Duration::from_millis(20))
            }
            _ => {
                let _ = process.kill();
                let _ = process.wait();
                return Err(format!(
                    "Managed child {} identity/termination deadline elapsed",
                    child.pid
                ));
            }
        }
    }
}

#[cfg(target_os = "linux")]
fn terminal_kill_if_identity_matches(child: &ManagedChildRecord) -> Result<(), String> {
    linux_process::terminate(
        child
            .identity
            .as_ref()
            .ok_or("Missing Linux managed child identity")?,
        std::path::Path::new(&child.executable),
    )
}

#[cfg(not(any(windows, target_os = "linux")))]
fn terminal_kill_if_identity_matches(_child: &ManagedChildRecord) -> Result<(), String> {
    Err("Registered child termination is unsupported on this installer platform".into())
}
