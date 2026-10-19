//! Resumable Option A reset, before any Engine or WebView can open user stores.
use std::fs;
use std::io::Write;
use std::path::Path;

pub(crate) const MARKER: &str = ".justsearch-reset-requested";
const STAGING: &str = ".justsearch-reset-status.tmp";

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum Phase {
    Idle,
    Requested,
    Incomplete,
    AwaitingStorage,
    Complete,
}

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub(crate) struct Failure {
    pub path: String,
    pub error: String,
}

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub(crate) struct Status {
    pub phase: Phase,
    pub remaining: Vec<Failure>,
}

impl Status {
    fn new(phase: Phase) -> Self {
        Self {
            phase,
            remaining: vec![],
        }
    }
    fn failed(path: &Path, error: impl ToString) -> Self {
        Self {
            phase: Phase::Incomplete,
            remaining: vec![failure(path, error)],
        }
    }
    pub(crate) fn blocks_backend(&self) -> bool {
        !matches!(self.phase, Phase::Idle | Phase::Complete)
    }
}

fn failure(path: &Path, error: impl ToString) -> Failure {
    Failure {
        path: path.to_string_lossy().into_owned(),
        error: error.to_string(),
    }
}

fn is_link(metadata: &fs::Metadata) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        // Includes junctions and other reparse points, not just symbolic links.
        metadata.file_attributes() & 0x400 != 0
    }
    #[cfg(not(windows))]
    {
        metadata.file_type().is_symlink()
    }
}

fn check_root(dir: &Path) -> Result<(), String> {
    let metadata = fs::symlink_metadata(dir).map_err(|e| e.to_string())?;
    if is_link(&metadata) || !metadata.is_dir() {
        return Err("The app data folder must be a real directory, not a link".into());
    }
    Ok(())
}

fn load(dir: &Path) -> Result<Status, String> {
    // Normal launch: one metadata probe, no enumeration, no writes.
    let marker = dir.join(MARKER);
    let metadata = match fs::symlink_metadata(&marker) {
        Ok(value) => value,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Status::new(Phase::Idle)),
        Err(e) => return Err(e.to_string()),
    };
    check_root(dir)?;
    if is_link(&metadata) || !metadata.is_file() {
        return Err(
            "Reset marker is not a regular file; cancel reset to keep the remaining data".into(),
        );
    }
    let bytes = fs::read(marker).map_err(|e| e.to_string())?;
    if bytes == b"reset\n" {
        return Ok(Status::new(Phase::Requested));
    }
    let status: Status =
        serde_json::from_slice(&bytes).map_err(|e| format!("Cannot read reset state: {e}"))?;
    Ok(status)
}

pub(crate) fn read_status(dir: &Path) -> Status {
    load(dir).unwrap_or_else(|e| Status::failed(&dir.join(MARKER), e))
}

fn sync_directory(dir: &Path) -> Result<(), String> {
    #[cfg(unix)]
    {
        fs::File::open(dir)
            .and_then(|f| f.sync_all())
            .map_err(|e| e.to_string())?;
    }
    #[cfg(not(unix))]
    {
        let _ = dir;
    }
    Ok(())
}

fn save(dir: &Path, status: &Status) -> Result<(), String> {
    check_root(dir)?;
    // create_new avoids following a planted staging link. A leftover staging file from
    // interruption is unlinked first; never opened through a link.
    let staging = dir.join(STAGING);
    match fs::remove_file(&staging) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => return Err(e.to_string()),
    }
    let bytes = serde_json::to_vec(status).map_err(|e| e.to_string())?;
    let mut file = fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&staging)
        .map_err(|e| e.to_string())?;
    file.write_all(&bytes)
        .and_then(|()| file.sync_all())
        .map_err(|e| e.to_string())?;
    drop(file);
    replace_marker(&staging, &dir.join(MARKER))?;
    sync_directory(dir)
}

#[cfg(not(windows))]
fn replace_marker(staging: &Path, marker: &Path) -> Result<(), String> {
    fs::rename(staging, marker).map_err(|e| e.to_string())
}

#[cfg(windows)]
fn replace_marker(staging: &Path, marker: &Path) -> Result<(), String> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::{
        MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
    };
    let from: Vec<u16> = staging.as_os_str().encode_wide().chain(Some(0)).collect();
    let to: Vec<u16> = marker.as_os_str().encode_wide().chain(Some(0)).collect();
    // Both NUL-terminated buffers live through the call. Same-directory replacement
    // plus WRITE_THROUGH commits the new phase before ordinary work may resume.
    if unsafe {
        MoveFileExW(
            from.as_ptr(),
            to.as_ptr(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    } == 0
    {
        return Err(std::io::Error::last_os_error().to_string());
    }
    Ok(())
}

pub(crate) fn request(dir: &Path) -> Result<(), String> {
    fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    check_root(dir)?;
    if read_status(dir).phase != Phase::Idle {
        return Err("A reset is already pending".into());
    }
    save(dir, &Status::new(Phase::Requested))
}

/// Standard-library recursive removal does not follow directory links. Handle
/// reparse-point directories explicitly on Windows (including junctions).
fn remove_entry(path: &Path, metadata: &fs::Metadata) -> std::io::Result<()> {
    if is_link(metadata) {
        #[cfg(windows)]
        {
            use std::os::windows::fs::MetadataExt;
            if metadata.file_attributes() & 0x10 != 0 {
                return fs::remove_dir(path);
            }
        }
        return fs::remove_file(path);
    }
    if metadata.is_dir() {
        fs::remove_dir_all(path)
    } else {
        fs::remove_file(path)
    }
}

fn visit(dir: &Path, native_bin: bool, remove: bool, failures: &mut Vec<Failure>) {
    let entries = match fs::read_dir(dir) {
        Ok(value) => value,
        Err(e) => {
            failures.push(failure(dir, e));
            return;
        }
    };
    for entry in entries {
        let entry = match entry {
            Ok(value) => value,
            Err(e) => {
                failures.push(failure(dir, e));
                continue;
            }
        };
        let path = entry.path();
        let name = entry.file_name();
        if !native_bin && name == MARKER {
            continue;
        }
        let metadata = match fs::symlink_metadata(&path) {
            Ok(value) => value,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
            Err(e) => {
                failures.push(failure(&path, e));
                continue;
            }
        };
        if metadata.is_dir() && !is_link(&metadata) {
            if (native_bin && name == "llama-server") || (!native_bin && name == "models") {
                continue;
            }
            if !native_bin && name == "native-bin" {
                visit(&path, true, remove, failures);
                if remove {
                    if let Err(e) = sync_directory(&path) {
                        failures.push(failure(&path, e));
                    }
                }
                continue;
            }
        }
        if remove {
            if let Err(e) = remove_entry(&path, &metadata) {
                failures.push(failure(&path, e));
            }
        } else if !failures.iter().any(|f| f.path == path.to_string_lossy()) {
            failures.push(failure(&path, "Still present after deletion"));
        }
    }
}

pub(crate) fn run_pending(dir: &Path, cleanup: impl FnOnce() -> Result<(), String>) -> Status {
    let status = match load(dir) {
        Ok(value) => value,
        Err(e) => return Status::failed(&dir.join(MARKER), e),
    };
    if !matches!(status.phase, Phase::Requested | Phase::Incomplete) {
        return status;
    }
    if let Err(e) = check_root(dir) {
        return Status::failed(dir, e);
    }
    let mut result = Status::new(Phase::AwaitingStorage);
    // Never delete the ownership manifest before its processes have been reconciled.
    if let Err(e) = cleanup() {
        result.remaining.push(failure(&dir.join("runtime"), e));
    } else {
        visit(dir, false, true, &mut result.remaining);
        visit(dir, false, false, &mut result.remaining);
    }
    if !result.remaining.is_empty() {
        result.phase = Phase::Incomplete;
    }
    if let Err(e) = save(dir, &result) {
        result.phase = Phase::Incomplete;
        result.remaining.push(failure(&dir.join(MARKER), e));
    }
    result
}

pub(crate) fn storage_cleared(dir: &Path) -> Result<(), String> {
    if load(dir)?.phase != Phase::AwaitingStorage {
        return Err("Reset is not awaiting window storage cleanup".into());
    }
    save(dir, &Status::new(Phase::Complete))
}

fn clear_marker(dir: &Path) -> Result<(), String> {
    check_root(dir)?;
    let marker = dir.join(MARKER);
    let metadata = fs::symlink_metadata(&marker).map_err(|e| e.to_string())?;
    // Cancellation also works for a malformed marker; unlink it without reading its target.
    if metadata.is_dir() && !is_link(&metadata) {
        return Err("Reset marker is a directory".into());
    }
    remove_entry(&marker, &metadata).map_err(|e| e.to_string())?;
    sync_directory(dir)
}

pub(crate) fn cancel(dir: &Path) -> Result<(), String> {
    if read_status(dir).phase != Phase::Incomplete {
        return Err("Only an incomplete reset can be cancelled".into());
    }
    // Never unlink destructive intent: a power loss must not resurrect it after
    // cancellation has allowed new stores to be written. Keep a durable tombstone.
    save(dir, &Status::new(Phase::Idle))
}

pub(crate) fn dismiss(dir: &Path) -> Result<(), String> {
    if load(dir)?.phase != Phase::Complete {
        return Err("Reset is not complete".into());
    }
    clear_marker(dir)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn temp() -> tempfile::TempDir {
        tempfile::tempdir().unwrap()
    }
    fn seed(dir: &Path, name: &str, bytes: &[u8]) {
        let path = dir.join(name);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, bytes).unwrap();
    }

    #[test]
    fn rs3_wipes_every_authored_store_before_start_and_waits_for_storage() {
        let dir = temp();
        for name in [
            "conversations/a/messages.jsonl",
            "memories/a.json",
            "agent-history/a.md",
            "runs/a/events",
            "index/a",
            "settings.json",
            "watched-folders.json",
            "logs/engine.log",
            "keystore",
            "telemetry/a",
        ] {
            seed(dir.path(), name, b"private");
        }
        request(dir.path()).unwrap();
        let status = run_pending(dir.path(), || Ok(()));
        for name in [
            "conversations",
            "memories",
            "agent-history",
            "runs",
            "index",
            "settings.json",
            "watched-folders.json",
            "logs",
            "keystore",
            "telemetry",
        ] {
            assert!(!dir.path().join(name).exists(), "old store remains: {name}");
        }
        assert_eq!(
            status.phase,
            Phase::AwaitingStorage,
            "do not claim completion before window storage cleanup"
        );
        assert!(status.blocks_backend());
        assert!(
            dir.path().join(MARKER).exists(),
            "intent must survive a crash before storage cleanup"
        );
        assert!(storage_cleared(dir.path()).is_ok());
        assert_eq!(read_status(dir.path()).phase, Phase::Complete);
        assert!(!read_status(dir.path()).blocks_backend());
        dismiss(dir.path()).unwrap();
        assert_eq!(read_status(dir.path()).phase, Phase::Idle);
    }

    #[test]
    fn rs4_child_cleanup_failure_preserves_intent_and_stores_until_retry() {
        let dir = temp();
        seed(dir.path(), "conversations/a", b"private");
        request(dir.path()).unwrap();
        let status = run_pending(dir.path(), || Err("owned Engine still running".into()));
        assert_eq!(
            status.phase,
            Phase::Incomplete,
            "a running predecessor is not a completed wipe"
        );
        assert!(status.blocks_backend());
        assert_eq!(
            fs::read(dir.path().join("conversations/a")).unwrap(),
            b"private"
        );
        assert!(status
            .remaining
            .iter()
            .any(|f| f.error.contains("still running")));
        assert!(dir.path().join(MARKER).exists());
        assert_eq!(
            run_pending(dir.path(), || Ok(())).phase,
            Phase::AwaitingStorage
        );
        assert!(!dir.path().join("conversations").exists());
    }

    #[test]
    fn rs5_legacy_intent_resumes_and_cancellation_keeps_remaining_data() {
        let dir = temp();
        seed(dir.path(), MARKER, b"reset\n");
        seed(dir.path(), "conversations/a", b"private");
        let failed = run_pending(dir.path(), || Err("held file".into()));
        assert_eq!(failed.phase, Phase::Incomplete);
        assert!(
            cancel(dir.path()).is_ok(),
            "cancellation must make remaining stores ordinary data"
        );
        assert_eq!(read_status(dir.path()).phase, Phase::Idle);
        let after = run_pending(dir.path(), || panic!("no reset pending"));
        assert_eq!(after.phase, Phase::Idle);
        assert_eq!(
            fs::read(dir.path().join("conversations/a")).unwrap(),
            b"private"
        );
    }

    #[test]
    fn rs5_cancel_commits_non_destructive_intent_before_new_work() {
        let dir = temp();
        seed(dir.path(), MARKER, b"reset\n");
        run_pending(dir.path(), || Err("held file".into()));
        assert_eq!(
            cancel(dir.path()),
            Ok(()),
            "cancellation must make destructive intent non-destructive"
        );
        assert!(
            dir.path().join(MARKER).is_file(),
            "cancellation must durably replace destructive intent before ordinary work resumes"
        );
        // Crash after cancellation with an uncommitted staging write must not resurrect a wipe.
        seed(dir.path(), ".justsearch-reset-status.tmp", b"reset\n");
        seed(dir.path(), "conversations/new", b"new work after cancel");
        assert_eq!(
            run_pending(dir.path(), || panic!("cancelled reset must never resume")).phase,
            Phase::Idle
        );
        assert_eq!(
            fs::read(dir.path().join("conversations/new")).unwrap(),
            b"new work after cancel"
        );
        request(dir.path()).unwrap();
        assert_eq!(read_status(dir.path()).phase, Phase::Requested);
    }

    #[test]
    fn rs5_interruption_after_disk_wipe_never_erases_successor_data() {
        let dir = temp();
        request(dir.path()).unwrap();
        assert_eq!(
            run_pending(dir.path(), || Ok(())).phase,
            Phase::AwaitingStorage
        );
        seed(dir.path(), "sentinel", b"not a second wipe");
        assert_eq!(
            run_pending(dir.path(), || panic!("must not wipe twice")).phase,
            Phase::AwaitingStorage
        );
        assert!(dir.path().join("sentinel").exists());
        assert!(storage_cleared(dir.path()).is_ok());
        assert_eq!(
            run_pending(dir.path(), || panic!("complete receipt is not intent")).phase,
            Phase::Complete
        );
        assert!(dir.path().join("sentinel").exists());
    }

    #[test]
    fn rn1_models_and_runtime_keep_their_bytes() {
        let dir = temp();
        seed(dir.path(), "models/nested/model.gguf", b"model bytes");
        seed(
            dir.path(),
            "native-bin/llama-server/variants/cpu/runtime",
            b"runtime bytes",
        );
        seed(dir.path(), "native-bin/tesseract/ocr", b"rebuildable");
        request(dir.path()).unwrap();
        run_pending(dir.path(), || Ok(()));
        assert_eq!(
            fs::read(dir.path().join("models/nested/model.gguf")).unwrap(),
            b"model bytes"
        );
        assert_eq!(
            fs::read(
                dir.path()
                    .join("native-bin/llama-server/variants/cpu/runtime")
            )
            .unwrap(),
            b"runtime bytes"
        );
        assert!(!dir.path().join("native-bin/tesseract").exists());
    }

    #[test]
    fn rn3_normal_start_does_not_enumerate_or_modify_stores() {
        let dir = temp();
        seed(dir.path(), "conversations/a", b"keep");
        seed(dir.path(), "logs/a", b"keep too");
        assert_eq!(
            run_pending(dir.path(), || panic!(
                "normal startup must not reconcile for reset"
            ))
            .phase,
            Phase::Idle
        );
        assert_eq!(
            fs::read(dir.path().join("conversations/a")).unwrap(),
            b"keep"
        );
        assert_eq!(fs::read(dir.path().join("logs/a")).unwrap(), b"keep too");
        assert_eq!(fs::read_dir(dir.path()).unwrap().count(), 2);
    }

    #[cfg(unix)]
    #[test]
    fn rs7_native_bin_link_is_removed_without_traversing_its_target() {
        let dir = temp();
        let external = temp();
        seed(external.path(), "other-app/sentinel", b"untouched");
        seed(external.path(), "llama-server/runtime", b"external runtime");
        std::os::unix::fs::symlink(external.path(), dir.path().join("native-bin")).unwrap();
        request(dir.path()).unwrap();
        run_pending(dir.path(), || Ok(()));
        assert_eq!(
            fs::read(external.path().join("other-app/sentinel"))
                .ok()
                .as_deref(),
            Some(b"untouched".as_slice()),
            "native-bin link target must never be traversed"
        );
        assert_eq!(
            fs::read(external.path().join("llama-server/runtime")).unwrap(),
            b"external runtime"
        );
        assert!(fs::symlink_metadata(dir.path().join("native-bin")).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn rs7_nested_links_leave_indexed_files_and_other_applications_untouched() {
        let dir = temp();
        let external = temp();
        seed(external.path(), "original.txt", b"source file");
        seed(dir.path(), "index/ordinary", b"index");
        std::os::unix::fs::symlink(external.path(), dir.path().join("index/source-link")).unwrap();
        std::os::unix::fs::symlink(external.path().join("missing"), dir.path().join("dangling"))
            .unwrap();
        request(dir.path()).unwrap();
        run_pending(dir.path(), || Ok(()));
        assert_eq!(
            fs::read(external.path().join("original.txt")).unwrap(),
            b"source file"
        );
        assert!(!dir.path().join("index").exists());
        assert!(fs::symlink_metadata(dir.path().join("dangling")).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn rs4_actual_delete_error_is_reported_and_retried_after_restart() {
        use std::os::unix::fs::PermissionsExt;
        let dir = temp();
        seed(dir.path(), "blocked/private", b"held data");
        seed(dir.path(), "index/a", b"removed");
        request(dir.path()).unwrap();
        fs::set_permissions(
            dir.path().join("blocked"),
            fs::Permissions::from_mode(0o500),
        )
        .unwrap();
        let first = run_pending(dir.path(), || Ok(()));
        let retained = read_status(dir.path());
        fs::set_permissions(
            dir.path().join("blocked"),
            fs::Permissions::from_mode(0o700),
        )
        .unwrap();
        assert_eq!(
            first.phase,
            Phase::Incomplete,
            "permission-denied deletion must not complete"
        );
        assert_eq!(
            retained.phase,
            Phase::Incomplete,
            "failed intent must survive restart"
        );
        assert!(first
            .remaining
            .iter()
            .any(|f| f.path.contains("blocked") && !f.error.is_empty()));
        assert!(!dir.path().join("index").exists());
        assert!(dir.path().join("blocked/private").exists());
        assert_eq!(
            run_pending(dir.path(), || Ok(())).phase,
            Phase::AwaitingStorage
        );
        assert!(!dir.path().join("blocked").exists());
    }
}
