mod binding;
// Spec F §2: one kill-on-close job per Engine incarnation; the shell itself never joins one.
mod containment;
// Reader/writer enabler; lifecycle callers land in Spec F step 6.
mod engine_host;
mod engine_probe;
#[allow(dead_code)]
mod exit_ledger;
mod platform_paths;
mod native_targets;
#[cfg(target_os = "linux")]
mod linux_process;
// Lane F stage B item B10: the Engine supervisor (design 7.1). The DECISION half is a pure
// function over the register the dev-runner also reads; only the actuator lives in this file.
mod quit;
mod session_window;
mod shell_actuator;
mod supervisor;
mod updater;

use binding::{read_manifest_if_present, Binding, ManifestFields};
use engine_host::{EngineHost, PreparedCommand};

use std::fs::OpenOptions;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

#[cfg(windows)]
use std::os::windows::process::CommandExt;

use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{Emitter, Manager, WindowEvent};
use tauri_plugin_deep_link::DeepLinkExt;
use tokio::sync::Notify;

/// Windows CREATE_NO_WINDOW flag - prevents console window from appearing.
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x08000000;

const RESET_MARKER_FILE: &str = ".justsearch-reset-requested";

/// The webview's signal that a NEW Engine incarnation is answering: everything it cached about the
/// old one — above all the per-boot session token — is dead (tempdoc 805 G.1).
///
/// Lane F stage B item B10 explicitly does NOT touch it. Same emit site (`watch_manifest`, on an
/// instanceId change), same `Option<u16>` payload, same subscriber
/// (`modules/ui-web/src/api/backendRestart.ts`). The supervisor's own state is a SEPARATE event
/// below, because the two answer different questions — "your binding is stale" and "here is what
/// the supervisor is doing" — and folding them would make a webview that only wants the first
/// re-resolve its binding on every cooldown tick. `backend_restart_event_is_untouched` pins it.
pub(crate) const EVENT_BACKEND_RESTART: &str = "justsearch://backend-restart";

/// The supervisor's state, forwarded to the webview (design 7.1's "visible state" row). Carries the
/// contents of `<dataDir>/runtime/supervisor.v1.json`.
pub(crate) const EVENT_SUPERVISOR_STATE: &str = "justsearch://supervisor-state";

/// The `backend-restart` payload, as a function so a test can pin it. It is the new incarnation's
/// port and nothing else: the webview re-resolves everything else from the manifest, and widening
/// this payload would make the event a second discovery channel (tempdoc 501 §6).
pub(crate) fn backend_restart_payload(manifest: &ManifestFields) -> Option<u16> {
    manifest.api_port
}

#[derive(serde::Serialize)]
struct FileMetadata {
    #[serde(rename = "isDir")]
    is_dir: bool,
}

#[derive(serde::Serialize)]
struct JustSearchPaths {
    home: String,
    models_dir: String,
    llama_server_dir: String,
    logs_dir: String,
    llama_log: String,
    engine_log: String,
}

struct BackendState {
    host: Arc<EngineHost>,
    quit: Arc<quit::QuitCoordinator>,
    port_ready: Arc<Notify>,
    session_token_ready: Arc<Notify>,
    /// Single-use token for confirming destructive operations (factory reset).
    delete_token: Mutex<Option<String>>,
    /// Tempdoc 501 Phase 17: the tray icon's registered id. The manifest watcher
    /// uses the AppHandle (set separately in `init_tray_context`) to look up the
    /// live `TrayIcon` via `app.tray_by_id(...)`. Direct caching of `TrayIcon` or
    /// `AppHandle` as a field of `BackendState` pulls the Tauri runtime into the
    /// test binary and fails on Windows with `STATUS_ENTRYPOINT_NOT_FOUND`; the
    /// indirection via the global `TRAY_CONTEXT` keeps the runtime out of test
    /// linkage.
    tray_id: Mutex<Option<String>>,
}

impl Default for BackendState {
    fn default() -> Self {
        let host = Arc::new(EngineHost::default());
        Self {
            quit: Arc::new(quit::QuitCoordinator::new(host.clone())),
            host,
            port_ready: Arc::default(),
            session_token_ready: Arc::default(),
            delete_token: Mutex::default(),
            tray_id: Mutex::default(),
        }
    }
}

/// Tempdoc 501 Phase 17: globally-stored AppHandle for tray-tooltip updates.
/// `OnceLock` lets the production binary set it exactly once at app setup; tests
/// never touch it. Stored separately from BackendState so the type doesn't appear
/// in BackendState's struct layout (which would force the Tauri runtime to link
/// into the test binary on Windows; see `tray_id` docs above).
static TRAY_CONTEXT: std::sync::OnceLock<tauri::AppHandle> = std::sync::OnceLock::new();

impl BackendState {
    /// Clear per-process discovery state before starting a replacement Head in the same shell.
    /// The updater uses this only after `wait_for_child_exit` has observed the old child exit.
    ///
    /// Tempdoc 805 G.1: replacement with an empty record — the same move a new instance makes,
    /// with nothing observed yet.
    fn reset_for_restart(&self) -> Result<(), String> {
        self.host.reset_for_successor()
    }

    fn binding_snapshot(&self) -> Binding {
        self.host.binding_snapshot()
    }

    fn get_port(&self) -> Option<u16> {
        self.host.binding_snapshot().port
    }

    fn get_session_token(&self) -> Option<String> {
        self.host.binding_snapshot().token
    }

    /// Tempdoc 805 G.1: apply a manifest observation under the provenance rule (`binding.rs`),
    /// then fire the notifies for whatever became available. Returns true iff the binding was
    /// replaced because a different instance succeeded a known one — the caller's restart signal.
    fn observe_manifest(&self, manifest: &ManifestFields) -> Option<binding::BindingChange> {
        let payload = backend_restart_payload(manifest);
        let change = self.host.observe_manifest_with_sinks(
            manifest,
            |port| {
                debug_assert_eq!(port, payload);
                if let Some(app) = TRAY_CONTEXT.get() {
                    let _ = app.emit(EVENT_BACKEND_RESTART, payload);
                }
            },
            |tooltip| update_tray_tooltip(self, tooltip),
        );
        if let Some(change) = change {
            if change.port_available {
                self.port_ready.notify_waiters();
            }
            if change.token_available {
                self.session_token_ready.notify_waiters();
            }
            Some(change)
        } else {
            None
        }
    }

    fn has_spawn_error(&self) -> bool {
        self.host.has_spawn_error()
    }

    fn kill_child(&self) {
        let ticket = self.quit.begin(quit::QuitCause::WindowExit);
        let outcome = self.quit.wait(ticket);
        eprintln!("[quit] {outcome:?}");
    }
    fn wait_for_child_exit(&self, timeout: Duration) -> bool {
        self.host.wait_for_exit(timeout)
    }

    fn child_pid(&self) -> Option<u32> {
        self.host.child_pid()
    }
}

impl Drop for BackendState {
    fn drop(&mut self) {
        // Best-effort cleanup on app shutdown.
        let ticket = self.quit.begin(quit::QuitCause::Drop);
        let _ = self.quit.wait(ticket);
    }
}

/// Tempdoc 805 G.1: ask Head to run its own ordered shutdown (`POST /api/lifecycle/shutdown`).
/// Returns true iff Head acknowledged with 2xx; every other outcome falls through to force-kill.
///
/// Hand-rolled loopback HTTP rather than the `reqwest` client the updater uses: this runs on the
/// synchronous quit path (including `Drop`, and `RunEvent::Exit` while the tokio runtime is
/// winding down), where an async client has no runtime to block on.
fn request_head_shutdown(port: u16, token: &str) -> bool {
    if token.contains(['\r', '\n']) {
        return false; // a token carrying CRLF would splice headers — never send it
    }
    let address = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
    let Ok(mut stream) = TcpStream::connect_timeout(&address, Duration::from_secs(2)) else {
        return false;
    };
    let _ = stream.set_write_timeout(Some(Duration::from_secs(2)));
    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
    let request = format!(
        "POST /api/lifecycle/shutdown HTTP/1.1\r\n\
         Host: 127.0.0.1:{port}\r\n\
         X-JustSearch-Session: {token}\r\n\
         Content-Type: application/json\r\n\
         Content-Length: 2\r\n\
         Connection: close\r\n\r\n{{}}"
    );
    if stream.write_all(request.as_bytes()).is_err() {
        return false;
    }
    let _ = stream.flush();
    let mut response = Vec::new();
    let mut chunk = [0u8; 256];
    while response.len() < 64 {
        match stream.read(&mut chunk) {
            Ok(0) | Err(_) => break,
            Ok(n) => response.extend_from_slice(&chunk[..n]),
        }
    }
    let status_line = String::from_utf8_lossy(&response);
    let Some(code) = status_line.split_whitespace().nth(1) else {
        return false;
    };
    code.starts_with('2')
}

/// Recursively copy a directory tree from src to dest.
/// Creates dest if it doesn't exist. Overwrites files if force_update is true,
/// otherwise only copies missing or empty files.
fn copy_dir_recursive(
    src: &std::path::Path,
    dest: &std::path::Path,
    force_update: bool,
) -> std::io::Result<usize> {
    let mut copied = 0usize;
    if !src.is_dir() {
        return Ok(0);
    }
    std::fs::create_dir_all(dest)?;
    for entry in std::fs::read_dir(src)?.flatten() {
        let p = entry.path();
        let fname = match p.file_name() {
            Some(n) => n,
            None => continue,
        };
        let dest_path = dest.join(fname);
        if p.is_dir() {
            // Recurse into subdirectory
            copied += copy_dir_recursive(&p, &dest_path, force_update)?;
        } else if p.is_file() {
            let should_copy = force_update
                || match std::fs::metadata(&dest_path) {
                    Ok(m) => m.len() == 0,
                    Err(_) => true,
                };
            if should_copy {
                std::fs::copy(&p, &dest_path)?;
                copied += 1;
            }
        }
    }
    Ok(copied)
}

/// Validate a user-supplied path before filesystem operations.
/// Blocks dangerous patterns that could be exploited via XSS-driven IPC calls.
fn validate_user_path(path: &str, block_executables: bool) -> Result<(), String> {
    let p = std::path::Path::new(path);

    // Block UNC / network paths (prevents remote code execution via network shares)
    if path.starts_with("\\\\") || path.starts_with("//") {
        return Err("Network paths are not allowed".into());
    }

    // Block path traversal
    for component in p.components() {
        if matches!(component, std::path::Component::ParentDir) {
            return Err("Path traversal (..) is not allowed".into());
        }
    }

    if block_executables {
        validate_document_type(p)?;
    }

    Ok(())
}

// One local-document policy, shared by validation and resolved-target dispatch.
const OPEN_DOCUMENT_EXTENSIONS: &[&str] = &[
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub", "txt",
    "md", "csv", "tsv", "log", "json", "xml", "html", "htm", "eml", "msg", "png", "jpg", "jpeg",
    "gif", "bmp", "webp", "tif", "tiff", "svg", "mp3", "wav", "m4a", "flac", "mp4", "mov", "mkv",
    "webm", "markdown", "mdx", "yaml", "yml", "toml", "ini", "conf", "rst", "tex", "srt", "vtt",
    "ics", "vcf", "mbox", "odg", "dotx", "ppsx", "vsdx", "xps", "mobi", "djvu", "cbz", "heic",
    "avif", "ico", "psd", "raw", "ogg", "opus", "aac", "wma", "avi", "wmv", "m4v", "mpg", "mpeg",
    "zip", "7z",
];

fn validate_document_type(path: &std::path::Path) -> Result<(), String> {
    let ext = path.extension().and_then(|e| e.to_str()).unwrap_or("");
    if OPEN_DOCUMENT_EXTENSIONS
        .iter()
        .any(|allowed| ext.eq_ignore_ascii_case(allowed))
    {
        Ok(())
    } else {
        let kind = if ext.is_empty() {
            "files with no extension".to_string()
        } else {
            format!(".{ext} files")
        };
        Err(format!(
            "Opening {kind} is not allowed. Use Show in folder instead."
        ))
    }
}

fn is_windows_drive_path(path: &str) -> bool {
    let bytes = path.as_bytes();
    bytes.len() >= 2 && bytes[0].is_ascii_alphabetic() && bytes[1] == b':'
}

fn resolve_local_path(path: &str) -> Result<PathBuf, String> {
    if path.is_empty() || path.chars().any(char::is_control) {
        return Err("A nonempty local path without control characters is required".into());
    }
    validate_user_path(path, false)?;
    if !is_windows_drive_path(path) && reqwest::Url::parse(path).is_ok() {
        return Err("URLs cannot be opened as local files".into());
    }
    #[cfg(windows)]
    {
        // Reject network/device forms BEFORE canonicalize adds its own trusted \\?\ prefix.
        let normalized = path.replace('\\', "/");
        if normalized.starts_with("//") {
            return Err("Network and device paths are not allowed".into());
        }
        let rest = if is_windows_drive_path(path) {
            &path[2..]
        } else {
            path
        };
        if rest.contains(':') {
            return Err("Alternate data streams are not allowed".into());
        }
    }
    std::fs::canonicalize(path).map_err(|e| format!("Cannot resolve local path '{path}': {e}"))
}

fn local_open_target(path: &str) -> Result<PathBuf, String> {
    let resolved = resolve_local_path(path)?;
    if !resolved.is_dir() {
        if !resolved.is_file() {
            return Err(
                "Only documents and directories can be opened. Use Show in folder instead.".into(),
            );
        }
        validate_document_type(&resolved)?;
    }
    // Residual: the target can still be replaced between this check and OS dispatch.
    Ok(resolved)
}

fn reveal_target(path: &str) -> Result<(PathBuf, PathBuf), String> {
    let resolved = resolve_local_path(path)?;
    let directory = if resolved.is_dir() {
        resolved.clone()
    } else {
        resolved
            .parent()
            .ok_or("The item has no parent directory")?
            .to_path_buf()
    };
    if !directory.is_dir() {
        return Err("Show in folder requires an existing directory".into());
    }
    Ok((resolved, directory))
}

fn validate_external_url(input: &str) -> Result<reqwest::Url, String> {
    if input.is_empty() || input.chars().any(char::is_control) || input.trim() != input {
        return Err("A nonempty URL without whitespace or control characters is required".into());
    }
    let url = reqwest::Url::parse(input).map_err(|e| format!("Invalid external URL: {e}"))?;
    match url.scheme() {
        "http" | "https" if url.host_str().is_some() => Ok(url),
        "mailto" if !url.path().is_empty() => Ok(url),
        scheme => Err(format!("Opening URL scheme '{scheme}' is not allowed")),
    }
}

fn classpath_sep() -> &'static str {
    if cfg!(windows) {
        ";"
    } else {
        ":"
    }
}

fn resolve_headless_dir<R: tauri::Runtime>(app: &tauri::AppHandle<R>) -> Result<PathBuf, String> {
    // Tauri bundles resources under a platform-specific resource directory.
    //
    // IMPORTANT: On Windows/NSIS the final resource layout can differ depending on how the
    // resource globs are specified. We have historically used:
    //   bundle.resources = ["resources/headless/**/*"]
    // which may end up as:
    //   <resource_dir>/headless/**
    // OR:
    //   <resource_dir>/resources/headless/**
    //
    // We treat `ui-headless.jar` as the anchor file and locate its parent directory.
    let resource_dir = app
        .path()
        .resource_dir()
        .map_err(|e| format!("resource_dir() failed: {e}"))?;

    // Strip Windows extended-length path prefix (\\?\) that Tauri adds.
    // Java's Files.isDirectory() returns false for \\?\ paths on some versions.
    #[cfg(windows)]
    let resource_dir = {
        let s = resource_dir.to_string_lossy();
        if let Some(stripped) = s.strip_prefix(r"\\?\") {
            std::path::PathBuf::from(stripped.to_string())
        } else {
            resource_dir
        }
    };

    let candidates = [
        resource_dir.join("headless"),
        resource_dir.join("resources").join("headless"),
    ];

    for cand in candidates {
        if cand.join("ui-headless.jar").exists() {
            return Ok(cand);
        }
    }

    // Fallback: best-effort search within the resource dir (bounded depth) for ui-headless.jar.
    fn find_ui_jar_dir(dir: &std::path::Path, depth: usize, max_depth: usize) -> Option<PathBuf> {
        if depth > max_depth {
            return None;
        }
        let entries = std::fs::read_dir(dir).ok()?;
        for entry in entries.flatten() {
            let p = entry.path();
            if p.is_file() {
                if p.file_name().and_then(|s| s.to_str()) == Some("ui-headless.jar") {
                    return p.parent().map(|pp| pp.to_path_buf());
                }
            } else if p.is_dir() {
                if let Some(found) = find_ui_jar_dir(&p, depth + 1, max_depth) {
                    return Some(found);
                }
            }
        }
        None
    }

    if let Some(found) = find_ui_jar_dir(&resource_dir, 0, 4) {
        return Ok(found);
    }

    Err(format!(
        "Unable to locate headless bundle under resource dir {} (expected to find ui-headless.jar)",
        resource_dir.display()
    ))
}

fn resolve_app_data_dir<R: tauri::Runtime>(app: &tauri::AppHandle<R>) -> Result<PathBuf, String> {
    app.path()
        .app_data_dir()
        .map_err(|e| format!("app_data_dir() failed: {e}"))
}

fn maybe_run_factory_reset<R: tauri::Runtime>(app: &tauri::AppHandle<R>) -> Result<(), String> {
    let app_data_dir = resolve_app_data_dir(app)?;
    let marker = app_data_dir.join(RESET_MARKER_FILE);
    if !marker.exists() {
        return Ok(());
    }

    // Locked choice (Option A): preserve BYO AI assets by default.
    // - Keep: {appDataDir}/models/**
    // - Keep: {appDataDir}/native-bin/llama-server/**
    // - Delete: everything else (settings, index, logs, telemetry, etc)
    eprintln!("Factory reset marker detected at: {}", marker.display());

    // Best-effort: delete everything except the AI asset folders.
    let models_dir = app_data_dir.join("models");
    let native_bin_dir = app_data_dir.join("native-bin");
    let native_llama_dir = native_bin_dir.join("llama-server");

    // Ensure the kept dirs exist (so path comparisons are stable).
    let _ = std::fs::create_dir_all(&models_dir);
    let _ = std::fs::create_dir_all(&native_llama_dir);

    // Delete all top-level entries except models/ and native-bin/ (handled specially).
    if let Ok(entries) = std::fs::read_dir(&app_data_dir) {
        for entry in entries.flatten() {
            let p = entry.path();
            if p == marker {
                continue;
            }
            if p == models_dir {
                continue;
            }
            if p == native_bin_dir {
                // Delete everything in native-bin except llama-server/.
                if let Ok(nentries) = std::fs::read_dir(&native_bin_dir) {
                    for nentry in nentries.flatten() {
                        let np = nentry.path();
                        if np == native_llama_dir {
                            continue;
                        }
                        let _ = if np.is_dir() {
                            std::fs::remove_dir_all(&np)
                        } else {
                            std::fs::remove_file(&np)
                        };
                    }
                }
                continue;
            }

            let _ = if p.is_dir() {
                std::fs::remove_dir_all(&p)
            } else {
                std::fs::remove_file(&p)
            };
        }
    }

    // Clear the marker after attempting the reset to avoid boot loops.
    let _ = std::fs::remove_file(&marker);
    eprintln!("Factory reset completed (AI assets preserved).");
    Ok(())
}

fn spawn_headless_backend<R: tauri::Runtime>(
    app: &tauri::AppHandle<R>,
    state: Arc<BackendState>,
) -> Result<(), String> {
    // Resolve app data dir and open the headless backend log FIRST so we can record spawn failures.
    let app_data_dir = resolve_app_data_dir(app)?;
    // Ensure app data directory exists (user-writable).
    std::fs::create_dir_all(&app_data_dir).map_err(|e| {
        format!(
            "Failed to create app data dir {}: {e}",
            app_data_dir.display()
        )
    })?;

    let logs_dir = app_data_dir.join("logs");
    std::fs::create_dir_all(&logs_dir)
        .map_err(|e| format!("Failed to create logs dir {}: {e}", logs_dir.display()))?;

    // Persist backend stdout/stderr to a log file for release builds (no console).
    //
    // Lane F stage A item A16: this is the SAME path the Engine's own Logback FILE appender
    // writes (modules/ui/src/main/resources/logback.xml) — the shell opens it before spawning
    // Java so a JVM that dies before Logback configures itself still leaves evidence. It was
    // `headless-backend.log`; A16 renamed it to `engine.log` on both sides, because there is one
    // process and one log now. Rename BOTH or the merged process grows a second log file, which
    // is the exact thing A16 exists to remove.
    let engine_log_path = logs_dir.join("engine.log");

    // Rotate the previous boot's log on every launch so post-mortem evidence
    // survives a single restart (tempdoc 374 sandbox round 4 issue F: install
    // crash lines were unrecoverable after the next boot under the previous
    // size-based rotation policy). Move engine.log → .log.1, then
    // .log.1 → .log.2 for one extra generation before discarding.
    if engine_log_path.exists() {
        let log1 = logs_dir.join("engine.log.1");
        let log2 = logs_dir.join("engine.log.2");
        if log1.exists() {
            let _ = std::fs::remove_file(&log2);
            let _ = std::fs::rename(&log1, &log2);
        }
        let _ = std::fs::rename(&engine_log_path, &log1);
    }

    let engine_log_file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&engine_log_path)
        .map_err(|e| {
            format!(
                "Failed to open engine log {}: {e}",
                engine_log_path.display()
            )
        })?;
    let engine_log = Arc::new(Mutex::new(engine_log_file));

    let headless_dir = match resolve_headless_dir(app) {
        Ok(p) => p,
        Err(err) => {
            if let Ok(mut f) = engine_log.lock() {
                let _ = writeln!(f, "[shell] Failed to resolve headless bundle dir: {err}");
            }
            return Err(err);
        }
    };
    if !headless_dir.is_dir() {
        if let Ok(mut f) = engine_log.lock() {
            let _ = writeln!(
                f,
                "[shell] headless dir not found or not a directory: {}",
                headless_dir.display()
            );
        }
        return Err(format!(
            "headless dir not found: {}",
            headless_dir.display()
        ));
    }

    #[cfg(target_os = "linux")]
    linux_process::set_bundle_dir(&headless_dir);

    // Use javaw.exe on Windows to avoid opening a console window (java.exe creates one).
    let java_bin = headless_dir
        .join("runtime")
        .join("bin")
        .join(native_targets::executable_name("java-gui"));
    let ui_jar = headless_dir.join("ui-headless.jar");
    let lib_glob = headless_dir.join("lib").join("*");

    if !java_bin.exists() {
        return Err(format!("java runtime not found: {}", java_bin.display()));
    }
    if !ui_jar.exists() {
        return Err(format!("ui-headless.jar not found: {}", ui_jar.display()));
    }

    let cp = format!(
        "{}{}{}",
        ui_jar.to_string_lossy(),
        classpath_sep(),
        lib_glob.to_string_lossy()
    );

    // Contract A: AI Home is a well-known, user-writable folder. Use app_data_dir as JUSTSEARCH_HOME.
    // Create the expected BYO folders so the UI can guide users to drop files.
    let models_dir = app_data_dir.join("models");
    let native_bin_dir = app_data_dir.join("native-bin");
    let native_llama_dir = native_bin_dir.join("llama-server");
    let native_tesseract_dir = native_bin_dir.join("tesseract");
    for d in [&models_dir, &native_llama_dir, &native_tesseract_dir] {
        std::fs::create_dir_all(d)
            .map_err(|e| format!("Failed to create directory {}: {e}", d.display()))?;
    }

    // v1 Simple Mode contract: ship llama-server payload with the app and restore it into AI Home.
    //
    // IMPORTANT: On Windows, llama-server.exe may be dynamically linked and require adjacent DLLs
    // (llama.dll / ggml*.dll / mtmd.dll / etc). Restore the *directory payload*, not just the exe.
    //
    // Upgrade safety: If the bundled runtime version differs from what's already in AI Home, overwrite
    // the on-disk payload so users don't stay stuck on a crashing runtime.
    //
    // Best-effort: do not fail app startup if the copy fails (app can still run without AI).
    let bundled_llama_dir = headless_dir.join("native-bin").join("llama-server");
    if bundled_llama_dir.is_dir() {
        let version_file = "runtime-version.txt";
        let bundled_version = std::fs::read_to_string(bundled_llama_dir.join(version_file))
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty());
        let installed_version = std::fs::read_to_string(native_llama_dir.join(version_file))
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty());
        let force_update = bundled_version.is_some() && bundled_version != installed_version;
        if force_update {
            if let Ok(mut f) = engine_log.lock() {
                let _ = writeln!(
                    f,
                    "[shell] llama-server runtime version changed (installed={:?}, bundled={:?}); overwriting AI Home payload",
                    installed_version,
                    bundled_version
                );
            }
        }
        let mut copied = 0usize;
        if let Ok(entries) = std::fs::read_dir(&bundled_llama_dir) {
            for entry in entries.flatten() {
                let p = entry.path();
                let fname = match p.file_name() {
                    Some(n) => n,
                    None => continue,
                };
                let dest = native_llama_dir.join(fname);

                if p.is_dir() {
                    // Recursively copy subdirectories (e.g., variants/cuda12/)
                    match copy_dir_recursive(&p, &dest, force_update) {
                        Ok(n) => copied += n,
                        Err(e) => {
                            if let Ok(mut f) = engine_log.lock() {
                                let _ = writeln!(
                                    f,
                                    "[shell] Failed to restore llama-server subdirectory ({} -> {}): {e}",
                                    p.display(),
                                    dest.display()
                                );
                            }
                        }
                    }
                } else if p.is_file() {
                    // Copy missing/empty files, or overwrite everything when the bundled runtime version changes.
                    let should_copy = force_update
                        || match std::fs::metadata(&dest) {
                            Ok(m) => m.len() == 0,
                            Err(_) => true,
                        };
                    if !should_copy {
                        continue;
                    }
                    if let Err(e) = std::fs::copy(&p, &dest) {
                        if let Ok(mut f) = engine_log.lock() {
                            let _ = writeln!(
                                f,
                                "[shell] Failed to restore llama-server payload file ({} -> {}): {e}",
                                p.display(),
                                dest.display()
                            );
                        }
                    } else {
                        copied += 1;
                    }
                }
            }
        }
        if copied > 0 {
            if let Ok(mut f) = engine_log.lock() {
                let _ = writeln!(
                    f,
                    "[shell] Restored llama-server payload into AI Home (copied {copied} file(s)) to {}",
                    native_llama_dir.display()
                );
            }
        }
    } else {
        if let Ok(mut f) = engine_log.lock() {
            let _ = writeln!(
                f,
                "[shell] Bundled llama-server dir not found at {} (AI runtime restore skipped)",
                bundled_llama_dir.display()
            );
        }
    }

    // OCR baseline: restore an optional bundled Tesseract payload into AI Home.
    //
    // The payload is directory-shaped because tesseract.exe needs adjacent DLLs and tessdata/*.traineddata.
    // Best-effort mirrors llama-server: OCR can degrade honestly if the payload is absent.
    let bundled_tesseract_dir = headless_dir.join("native-bin").join("tesseract");
    if bundled_tesseract_dir.is_dir() {
        let version_file = "runtime-version.txt";
        let bundled_version = std::fs::read_to_string(bundled_tesseract_dir.join(version_file))
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty());
        let installed_version = std::fs::read_to_string(native_tesseract_dir.join(version_file))
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty());
        let force_update = bundled_version.is_some() && bundled_version != installed_version;
        match copy_dir_recursive(&bundled_tesseract_dir, &native_tesseract_dir, force_update) {
            Ok(copied) if copied > 0 => {
                if let Ok(mut f) = engine_log.lock() {
                    let _ = writeln!(
                        f,
                        "[shell] Restored Tesseract OCR payload into AI Home (copied {copied} file(s)) to {}",
                        native_tesseract_dir.display()
                    );
                }
            }
            Ok(_) => {}
            Err(e) => {
                if let Ok(mut f) = engine_log.lock() {
                    let _ = writeln!(
                        f,
                        "[shell] Failed to restore Tesseract OCR payload ({} -> {}): {e}",
                        bundled_tesseract_dir.display(),
                        native_tesseract_dir.display()
                    );
                }
            }
        }
    }

    // Prefer explicit overrides to avoid CWD-based discovery in bundled mode.
    let config_path = headless_dir.join("config").join("application.yaml");
    let ssot_path = headless_dir.join("SSOT");
    let plugins_manifest = headless_dir
        .join("SSOT")
        .join("manifests")
        .join("plugins")
        .join("pipeline-stage-plugins.v1.json");

    let crash_dir = app_data_dir.join("crashes");
    let mut cmd = containment::engine_command(&java_bin).map_err(|e| e.to_string())?;
    // Use AOT cache if present (JEP 514 — built at compile time, bundled in aot/).
    let aot_cache = headless_dir.join("aot").join("head.aot");
    cmd.current_dir(&headless_dir)
        // ==== The packaged Engine flag set (lane F stage A item A13 follow-up) ====
        //
        // This block spawns ONE process that is now both halves of the product. Item A13 deleted
        // the Worker distribution and its spawner but never revisited these flags, so until this
        // change the packaged Engine ran on the Head-only set: a 512 MiB heap sized for "REST API
        // + SSE + static files" while Lucene, the job queue, the indexing loop and the ONNX
        // session cache lived in the same heap, and no -Dfile.encoding, which WorkerSpawner used
        // to set for the index half (WorkerSpawner.java:457 before A11).
        //
        // -Xmx2g: the old Head 512m plus the Worker's 1g, with headroom, because there is one
        // heap now instead of two. The Worker's larger working set was OFF-heap (ORT arenas and
        // Lucene mmap), which -Xmx does not govern, so this is not 512m + 4g. The gate run at
        // stage E re-sizes this against measurement; 2g is the safe interim, not a tuned value.
        //
        // -XX:+UseCompactObjectHeaders: one heap holding the index half's object population wants
        // it, and it was absent here.
        //
        // -Dfile.encoding=UTF-8: carried over from the deleted spawner. Document extraction reads
        // untrusted bytes, and on Windows the platform default is not UTF-8, so losing this
        // changes how text is decoded — silently, and only for non-ASCII content.
        //
        // UseG1GC: the Engine serves search while it indexes in the same heap, so it needs a
        // low-pause collector; design section 8's decision table fixes G1 as the default and
        // stage E compares collectors only if a response-time group fails. Stated explicitly
        // because JVM ergonomics pick SerialGC on a small machine. (SerialGC was the Head-only
        // first cut, right for a heap >= 85 % empty, 917 Derisk 1.) Lane F PR 0:
        // TieredStopAtLevel=1 dropped and MetaspaceSize=128m added, because every full GC in the
        // measured run was a Metaspace or CodeCache threshold (the C1-only 48 MiB code cache),
        // not heap pressure, and C1-only also conflicted with the AOT cache below.
        // -XX:-UsePerfData: skip hsperfdata temp file (avoids Defender scan on Windows).
        //
        // This set is pinned from BOTH sides: scripts/dev/test-dev-runner-head-java-opts.mjs
        // asserts the dev-runner's set exactly (deepEqual, so an addition fails), and reads THIS
        // FILE's source to assert each shared flag appears here exactly once. The dev-runner
        // deliberately carries no default -Xmx (tempdoc 730) — that is the one documented
        // divergence, because a dev machine's JVM default is fine and a packaged one's is not.
        .arg("-Xmx2g")
        .arg("-XX:+UseG1GC")
        .arg("-XX:MetaspaceSize=128m")
        .arg("-XX:MaxDirectMemorySize=256m")
        .arg("-XX:+UseCompactObjectHeaders")
        .arg("-XX:-UsePerfData")
        .arg("-Dfile.encoding=UTF-8")
        .arg("--sun-misc-unsafe-memory-access=warn")
        // FFM downcalls (NVML, the Windows job object, the GPU driver probe); JDK 25 warns without this, a later JDK (JEP 472) refuses.
        .arg("--enable-native-access=ALL-UNNAMED");
    if aot_cache.exists() {
        cmd.arg(format!("-XX:AOTCache={}", aot_cache.to_string_lossy()));
    }
    cmd
        // JVM crash diagnostics — write to <dataDir>/crashes/
        .arg(format!(
            "-XX:ErrorFile={}/hs_err_pid%p.log",
            crash_dir.to_string_lossy()
        ))
        .arg("-XX:+HeapDumpOnOutOfMemoryError")
        .arg(format!("-XX:HeapDumpPath={}/", crash_dir.to_string_lossy()))
        // Lane F stage B item B1. Without this an OutOfMemoryError reaches the default
        // uncaught-exception handler and the JVM exits 1 — the same code as a boot failure, so the
        // supervisor cannot tell a memory death from a bad config. With it the JVM exits 3.
        // (Measured on Temurin 25.0.2: 3 with the flag, 1 without.) EngineExit classifies 3 as
        // TRANSIENT, so it is retried under cooldown rather than going straight to exhausted.
        .arg("-XX:+ExitOnOutOfMemoryError")
        // Lane F stage B item B1. Without this an OutOfMemoryError reaches the default
        // uncaught-exception handler and the JVM exits 1 — the same code as a boot failure, so
        // the supervisor cannot tell a memory death from a bad config. With it the JVM exits 3.
        // (Measured on Temurin 25.0.2: 3 with the flag, 1 without.) EngineExit classifies 3 as
        // TRANSIENT, so it is retried under cooldown rather than going straight to exhausted.
        // The packaged shell is the production trust boundary: Head must mint a per-boot
        // session token and enforce it on every mutating loopback request. Browser development
        // uses the separate dev-stack launch path and does not pass through this command.
        .arg("-Djustsearch.prod=true")
        .arg(format!(
            "-Djustsearch.app.version={}",
            app.package_info().version
        ))
        .arg(format!(
            "-Djustsearch.data.dir={}",
            app_data_dir.to_string_lossy()
        ))
        .arg(format!(
            "-Djustsearch.config={}",
            config_path.to_string_lossy()
        ))
        .arg(format!(
            "-Djustsearch.repo.root={}",
            headless_dir.to_string_lossy()
        ))
        .arg(format!(
            "-Djustsearch.ssot.path={}",
            ssot_path.to_string_lossy()
        ))
        .arg(format!(
            "-Djustsearch.plugins.manifest={}",
            plugins_manifest.to_string_lossy()
        ))
        .arg("-cp")
        .arg(cp)
        .arg("io.justsearch.ui.HeadlessApp")
        .env("JUSTSEARCH_HOME", &app_data_dir);

    // GPU acceleration: if bundled ORT CUDA DLLs are present, set the native path
    // so GpuAutoDetection and OrtCudaHelper find them without conventional-path search.
    let ort_cuda_dir = headless_dir
        .join("native-bin")
        .join("onnxruntime")
        .join("cuda12");
    if ort_cuda_dir.join(native_targets::library_name("ort-cuda")).exists() {
        cmd.env("JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH", &ort_cuda_dir);
        cmd.env("JUSTSEARCH_GPU_ENABLED", "true");
    }

    // OCR baseline: make app-owned Tesseract discoverable to Tika's external-process parser.
    // Tika 3.x carries executable/tessdata paths on the parser instance built from process
    // configuration, so the packaged app must project the restored runtime through env.
    let tesseract_exe = native_tesseract_dir.join(native_targets::executable_name("tesseract"));
    let tessdata_dir = native_tesseract_dir.join("tessdata");
    if tesseract_exe.exists() && tessdata_dir.is_dir() {
        cmd.env("TESSDATA_PREFIX", &tessdata_dir);
        let existing_path = std::env::var_os("PATH").unwrap_or_default();
        let separator = if cfg!(windows) { ";" } else { ":" };
        let mut path_value = native_tesseract_dir.as_os_str().to_os_string();
        if !existing_path.is_empty() {
            path_value.push(separator);
            path_value.push(existing_path);
        }
        cmd.env("PATH", path_value);
    }

    cmd.stdout(Stdio::piped()).stderr(Stdio::piped());

    // The Engine starts inside its incarnation's job, with no console window (belt-and-suspenders
    // with javaw.exe); both creation flags are set in one place, `containment.rs` (Spec F §2.1).
    let admitted = state.host.admit(PreparedCommand { command: cmd })?;
    let generation = admitted.generation;

    // Drain stdout/stderr to avoid pipe backpressure.
    if let Some(stdout) = admitted.stdout {
        let state_clone = state.clone();
        let log_clone = engine_log.clone();
        thread::spawn(move || {
            let reader = BufReader::new(stdout);
            for line in reader.lines().flatten() {
                // Security: do NOT write JUSTSEARCH_SESSION_TOKEN= lines to the
                // log file. The token is a secret that should only be delivered
                // to the UI in-memory.
                //
                // Tempdoc 501 Phase 8: the consumer-side parse is gone —
                // watch_manifest() now reads sessionToken from the manifest's
                // head.sessionToken field. This line stays as a redaction
                // guard so the token never lands on disk even if HeadlessApp
                // keeps emitting it for human observation.
                if line.starts_with("JUSTSEARCH_SESSION_TOKEN=") {
                    continue;
                }

                // Write all other lines to the log file.
                // Tempdoc 501 Phase 8: the JUSTSEARCH_API_PORT= and
                // JUSTSEARCH_SESSION_TOKEN= stdout lines stay in the log for
                // human observation but the consumer-side parse is gone —
                // watch_manifest() is the ONLY discovery path, and tempdoc 805
                // G.1 made the binding it feeds instance-keyed.
                if let Ok(mut f) = log_clone.lock() {
                    let _ = writeln!(f, "{line}");
                }
            }
            // Stdout pipe closed — process exited. If no port was ever set and
            // this isn't a graceful shutdown, signal an error so waiters unblock
            // immediately instead of blocking until the timeout expires.
            if state_clone.host.stdout_closed(generation) {
                state_clone.port_ready.notify_waiters();
                state_clone.session_token_ready.notify_waiters();
            }
        });
    }

    if let Some(stderr) = admitted.stderr {
        let log_clone = engine_log.clone();
        thread::spawn(move || {
            let reader = BufReader::new(stderr);
            for line in reader.lines().flatten() {
                if let Ok(mut f) = log_clone.lock() {
                    let _ = writeln!(f, "[stderr] {line}");
                }
            }
        });
    }

    // Tempdoc 501 Phase 7: manifest-watcher thread reads <dataDir>/runtime/manifest.json — the
    // producer's self-published runtime identity — and feeds the binding.
    //
    // Tempdoc 805 G.1: started AFTER the child is recorded, so the provenance rule can compare the
    // manifest's `pid` against our live child from the very first poll. Started earlier, the first
    // polls ran with an unknown child pid and could adopt a previous boot's residue as the
    // establishing observation.
    let weak_state = Arc::downgrade(&state);
    let manifest_path = app_data_dir.join("runtime").join("manifest.json");
    state.host.ensure_watcher(move || {
        if let Some(state) = weak_state.upgrade() {
            watch_manifest_tick(&state, &manifest_path);
        }
    })?;

    Ok(())
}

/// The **requested-restart leg** (design 7.1, lane F stage B item B10).
///
/// This was the updater's private helper — "restart Head after an updater handoff fails after
/// orderly shutdown", one caller. It is now the one way this process replaces the Engine, shared by
/// that caller and by the supervisor's restart path ([`ShellActuator::spawn_engine`]). The
/// distinction the design draws is between a restart the supervisor ASKED for and a crash it
/// observed; both end up here, and only the crash is charged to the budget.
///
/// Still intentionally crate-visible rather than a Tauri command: only the shell lifecycle
/// coordinator may replace the Engine, never untrusted webview code.
pub(crate) fn restart_headless_backend(
    app: &tauri::AppHandle,
    state: Arc<BackendState>,
) -> Result<(), String> {
    state.reset_for_restart()?;
    if let Err(error) = spawn_headless_backend(app, state.clone()) {
        state.host.record_spawn_error(error.clone());
        state.port_ready.notify_waiters();
        state.session_token_ready.notify_waiters();
        return Err(error);
    }
    Ok(())
}

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
            if !child.identity.as_ref().is_some_and(|id| id.valid(child.pid)) {
                return Err("Incomplete Linux managed child identity".into());
            }
            Ok(child)
        })
        .collect::<Result<_, String>>()?;
    for child in children {
        terminal_kill_if_identity_matches(&child)?;
    }
    Ok(())
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
    let mut process = Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-Command", script])
        .env("JUSTSEARCH_CHILD_PID", child.pid.to_string())
        .env("JUSTSEARCH_CHILD_STARTED", &child.started_at)
        .env("JUSTSEARCH_CHILD_EXE", &child.executable)
        .creation_flags(CREATE_NO_WINDOW)
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
                thread::sleep(Duration::from_millis(20))
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
        child.identity.as_ref().ok_or("Missing Linux managed child identity")?,
        std::path::Path::new(&child.executable),
    )
}

#[cfg(not(any(windows, target_os = "linux")))]
fn terminal_kill_if_identity_matches(_child: &ManagedChildRecord) -> Result<(), String> {
    Err("Registered child termination is unsupported on this installer platform".into())
}

fn publish_terminal_spawn_failure(
    host: &EngineHost,
    predecessor_instance_id: Option<String>,
    data_dir: Option<&std::path::Path>,
    error: &str,
    event: impl FnOnce(&supervisor::StateRecord),
) {
    if let Some(data_dir) = data_dir {
        cleanup_registered_children_for_terminal(data_dir);
    }
    let path = data_dir.map(|d| d.join("runtime").join("supervisor.v1.json"));
    host.publish_initial_spawn_failure(path.as_deref(), predecessor_instance_id, error, event);
}

fn cleanup_after_supervision(outcome: &supervisor::Outcome, data_dir: &std::path::Path) {
    if matches!(outcome, supervisor::Outcome::Exhausted { exit_code, .. } if *exit_code != 2) {
        cleanup_registered_children_for_terminal(data_dir);
    }
}

/// Start supervising the Engine on an OS thread.
///
/// An OS thread, following the split this file already uses for the stdout drain and the manifest
/// watcher: the loop blocks on sockets and on `try_wait`, and it must keep running while the async
/// runtime is busy or winding down.
pub(crate) fn start_supervision(
    app: &tauri::AppHandle,
    state: Arc<BackendState>,
) -> Result<(), String> {
    let Ok(data_dir) = resolve_app_data_dir(app) else {
        return Err("supervisor: no app data dir; the Engine will not be supervised".into());
    };
    let host = state.host.clone();
    let spawn_app = app.clone();
    let event_app = app.clone();
    let mut actuator = shell_actuator::ShellActuator::new(
        host.clone(),
        data_dir,
        move || {
            state.reset_for_restart()?;
            spawn_headless_backend(&spawn_app, state.clone())?;
            Ok(state.child_pid().unwrap_or(0))
        },
        move |record| {
            let _ = event_app.emit(EVENT_SUPERVISOR_STATE, record);
        },
    );
    host.start_supervisor(move || {
        let policy = supervisor::load_policy();
        let mut sup = supervisor::Supervisor::new(policy);
        let outcome = supervisor::run_supervision(&mut sup, &mut actuator);
        cleanup_after_supervision(&outcome, &actuator.data_dir);
        match outcome {
            supervisor::Outcome::Exhausted { reason, .. } => {
                // The current host state already carries the terminal result. B13 uses owned
                // child/binding state under its replacement hold, even before an API exists.
                eprintln!("supervisor: {reason}");
            }
            supervisor::Outcome::Stopped { reason, .. } => {
                eprintln!("supervisor: the Engine stopped ({reason}); not restarting");
            }
            supervisor::Outcome::NoEngine => {}
        }
    })
}

/// Tempdoc 501 Phase 7: poll the runtime manifest and feed port + session
/// token into BackendState as soon as the producer publishes them.
///
/// Polls every 100ms for up to 60s. Exits when:
///   * port and token (if any) are both set on state, OR
///   * the spawned child reports a spawn error, OR
///   * the deadline elapses (the manifest never appeared — nothing else can
///     supply the binding; tempdoc 501 Phase 8 removed the stdout parse).
///
/// We poll rather than use OS file-watch APIs because (a) Tauri is currently
/// tokio-rt-multi-thread without a notify dep, (b) the manifest write is a
/// single atomic rename so any poll catches it on the next tick, and (c) 100ms
/// granularity beats the JVM warmup by ~5 orders of magnitude.
fn watch_manifest_tick(state: &BackendState, manifest_path: &std::path::Path) {
    if let Some(manifest) = read_manifest_if_present(manifest_path) {
        // Tempdoc 805 G.1: one provenance-checked application. A new instanceId replaces the
        // whole binding (port AND token are per-boot facts of one incarnation) and emits the
        // restart event so the webview re-resolves instead of failing silently against a dead
        // port with the previous boot's token.
        if state.observe_manifest(&manifest).is_none() {
            return;
        }
    }
}

/// Tempdoc 501 Phase 17: tooltip update path. Looks up the tray icon by id via the
/// stored app handle; quietly no-ops if the tray hasn't been built yet or has been
/// torn down. Keeping the actual `tauri::tray::TrayIcon` out of `BackendState` (using
/// id-based lookup instead) prevents the tray runtime from being pulled into the test
/// binary, which fails to link on Windows with STATUS_ENTRYPOINT_NOT_FOUND.
fn update_tray_tooltip(state: &BackendState, tooltip: &str) {
    let Some(app) = TRAY_CONTEXT.get() else {
        return; // tray not yet built
    };
    let tray_id = match state.tray_id.lock() {
        Ok(g) => g.clone(),
        Err(_) => return,
    };
    let Some(id) = tray_id else {
        return;
    };
    if let Some(tray) = app.tray_by_id(&id) {
        let _ = tray.set_tooltip(Some(tooltip));
    }
}

#[tauri::command]
fn supervisor_state(state: tauri::State<'_, Arc<BackendState>>) -> Option<supervisor::StateRecord> {
    state.host.latest_record()
}

#[tauri::command]
async fn api_port(state: tauri::State<'_, Arc<BackendState>>) -> Result<Option<u16>, String> {
    if let Some(port) = state.get_port() {
        return Ok(Some(port));
    }
    if state.has_spawn_error() {
        return Ok(None);
    }

    // Wait (bounded) for backend to reveal its port on stdout.
    // Head startup is ~4.5s measured (Mar 2026); 15s gives 3× headroom.
    let notified = state.port_ready.notified();
    let _ = tokio::time::timeout(std::time::Duration::from_secs(15), notified).await;
    Ok(state.get_port())
}

/// Returns the session token for non-GET request authentication (prod mode only).
/// This is delivered to the UI so it can include the token in API requests.
/// Returns None if the token has not been set (dev mode or not yet started).
#[tauri::command]
async fn session_token(
    state: tauri::State<'_, Arc<BackendState>>,
) -> Result<Option<String>, String> {
    if let Some(token) = state.get_session_token() {
        return Ok(Some(token));
    }
    if state.has_spawn_error() {
        return Ok(None);
    }

    // Wait (bounded) for backend to reveal its token on stdout.
    // Use a shorter timeout than port since token may not be present in dev mode.
    let notified = state.session_token_ready.notified();
    let _ = tokio::time::timeout(std::time::Duration::from_secs(10), notified).await;
    Ok(state.get_session_token())
}

#[tauri::command]
async fn smoke_run_id() -> Option<String> {
    match std::env::var("JUSTSEARCH_SMOKE_RUN_ID") {
        Ok(v) => {
            let trimmed = v.trim().to_string();
            if trimmed.is_empty() {
                None
            } else {
                Some(trimmed)
            }
        }
        Err(_) => None,
    }
}

#[tauri::command]
async fn get_file_metadata(path: String) -> Result<FileMetadata, String> {
    validate_user_path(&path, false)?;
    let metadata = std::fs::metadata(&path)
        .map_err(|e| format!("Failed to get metadata for '{path}': {e}"))?;
    Ok(FileMetadata {
        is_dir: metadata.is_dir(),
    })
}

// Shell consumers need a plain path, while validation keeps the canonical target.
#[cfg(windows)]
fn shell_dispatch_path(target: &Path) -> Result<PathBuf, String> {
    let simplified = dunce::simplified(target);
    let mut components = simplified.components();
    match components.next() {
        Some(std::path::Component::Prefix(prefix)) => match prefix.kind() {
            std::path::Prefix::Disk(_) => return Ok(simplified.to_path_buf()),
            std::path::Prefix::VerbatimUNC(server, share) => {
                let mut plain = std::ffi::OsString::from(r"\\");
                plain.push(server);
                plain.push(r"\");
                plain.push(share);
                plain.push(components.as_path());
                let plain = PathBuf::from(plain);
                // Check Win32's plain-path interpretation; Rust may add verbatim semantics itself.
                use std::os::windows::ffi::{OsStrExt, OsStringExt};
                use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};
                use windows_sys::Win32::Foundation::INVALID_HANDLE_VALUE;
                use windows_sys::Win32::Storage::FileSystem::{
                    CreateFileW, GetFinalPathNameByHandleW, FILE_FLAG_BACKUP_SEMANTICS,
                    FILE_READ_ATTRIBUTES, FILE_SHARE_DELETE, FILE_SHARE_READ, FILE_SHARE_WRITE,
                    OPEN_EXISTING,
                };
                let file: Vec<u16> = plain.as_os_str().encode_wide().chain(Some(0)).collect();
                let handle = unsafe {
                    CreateFileW(
                        file.as_ptr(),
                        FILE_READ_ATTRIBUTES,
                        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                        std::ptr::null(),
                        OPEN_EXISTING,
                        FILE_FLAG_BACKUP_SEMANTICS,
                        std::ptr::null_mut(),
                    )
                };
                if handle == INVALID_HANDLE_VALUE {
                    return Err(format!(
                        "Cannot resolve plain UNC target '{}': {}",
                        plain.display(),
                        std::io::Error::last_os_error()
                    ));
                }
                let handle = unsafe { OwnedHandle::from_raw_handle(handle) };
                let size = unsafe {
                    GetFinalPathNameByHandleW(handle.as_raw_handle(), std::ptr::null_mut(), 0, 0)
                };
                if size == 0 {
                    return Err(format!(
                        "Cannot identify plain UNC target: {}",
                        std::io::Error::last_os_error()
                    ));
                }
                let mut name = vec![0u16; size as usize + 1];
                let size = unsafe {
                    GetFinalPathNameByHandleW(
                        handle.as_raw_handle(),
                        name.as_mut_ptr(),
                        name.len() as u32,
                        0,
                    )
                };
                if size == 0 || size as usize >= name.len() {
                    return Err(format!(
                        "Cannot identify plain UNC target: {}",
                        std::io::Error::last_os_error()
                    ));
                }
                let resolved = PathBuf::from(std::ffi::OsString::from_wide(&name[..size as usize]));
                if resolved == target {
                    return Ok(plain);
                }
            }
            _ => {}
        },
        _ => {}
    }
    Err("The resolved target cannot be represented as a non-verbatim Windows shell path".into())
}

#[cfg(windows)]
fn windows_shell_open(target: &std::ffi::OsStr) -> Result<(), String> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Foundation::RPC_E_CHANGED_MODE;
    use windows_sys::Win32::System::Com::{
        CoInitializeEx, CoUninitialize, COINIT_APARTMENTTHREADED, COINIT_DISABLE_OLE1DDE,
    };
    use windows_sys::Win32::UI::Shell::{
        ShellExecuteExW, SEE_MASK_FLAG_NO_UI, SEE_MASK_NOASYNC, SHELLEXECUTEINFOW,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;

    let file: Vec<u16> = target.encode_wide().chain(Some(0)).collect();
    let verb: Vec<u16> = "open".encode_utf16().chain(Some(0)).collect();
    let initialized = unsafe {
        CoInitializeEx(
            std::ptr::null(),
            (COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE) as u32,
        )
    };
    if initialized < 0 && initialized != RPC_E_CHANGED_MODE {
        return Err(format!(
            "Could not initialize Windows shell COM: {initialized:#x}"
        ));
    }
    let mut info = SHELLEXECUTEINFOW {
        cbSize: std::mem::size_of::<SHELLEXECUTEINFOW>() as u32,
        // The async IPC thread has no Windows message loop; finish shell/DDE work before returning.
        fMask: SEE_MASK_NOASYNC | SEE_MASK_FLAG_NO_UI,
        lpVerb: verb.as_ptr(),
        lpFile: file.as_ptr(),
        nShow: SW_SHOWNORMAL,
        ..Default::default()
    };
    // No command parser, PATHEXT lookup, parameters, or environment expansion of the checked target.
    let result = if unsafe { ShellExecuteExW(&mut info) } != 0 {
        Ok(())
    } else {
        Err(format!(
            "Windows could not open '{}': {}",
            target.to_string_lossy(),
            std::io::Error::last_os_error()
        ))
    };
    if initialized >= 0 {
        unsafe {
            CoUninitialize();
        }
    }
    result
}

// Return the exact path handed to the OS so native tests can observe the real dispatch boundary.
fn dispatch_open_target(target: &Path) -> Result<PathBuf, String> {
    #[cfg(windows)]
    {
        let target = shell_dispatch_path(target)?;
        if target.is_dir() {
            Command::new("explorer.exe")
                .arg(&target)
                .status()
                .map_err(|e| format!("Failed to open folder '{}': {e}", target.display()))?;
        } else {
            windows_shell_open(target.as_os_str())?;
        }
        Ok(target)
    }
    #[cfg(not(windows))]
    {
        open::that(target).map_err(|e| format!("Failed to open '{}': {e}", target.display()))?;
        Ok(target.to_path_buf())
    }
}

fn dispatch_reveal_target(_item: &Path, directory: &Path) -> Result<PathBuf, String> {
    #[cfg(windows)]
    {
        let target = if _item.is_file() { _item } else { directory };
        let target = shell_dispatch_path(target)?;
        let argument = if _item.is_file() {
            let mut argument = std::ffi::OsString::from("/select,");
            argument.push(target.as_os_str());
            argument
        } else {
            target.as_os_str().to_os_string()
        };
        Command::new("explorer.exe")
            .arg(argument)
            .status()
            .map_err(|e| format!("Failed to reveal '{}': {e}", target.display()))?;
        Ok(target)
    }
    #[cfg(not(windows))]
    {
        open::that(directory)
            .map_err(|e| format!("Failed to reveal '{}': {e}", directory.display()))?;
        Ok(directory.to_path_buf())
    }
}

#[tauri::command]
async fn open_file(path: String) -> Result<(), String> {
    let target = local_open_target(&path)?;
    dispatch_open_target(&target).map(|_| ())
}

#[tauri::command]
async fn open_external_url(url: String) -> Result<(), String> {
    let target = validate_external_url(&url)?;
    #[cfg(windows)]
    {
        windows_shell_open(std::ffi::OsStr::new(target.as_str()))
    }
    #[cfg(not(windows))]
    {
        open::that(target.as_str()).map_err(|e| format!("Failed to open URL: {e}"))
    }
}

#[tauri::command]
async fn reveal_in_explorer(path: String) -> Result<(), String> {
    let (item, directory) = reveal_target(&path)?;
    dispatch_reveal_target(&item, &directory).map(|_| ())
}

/// Phase 1: Generate a single-use token for confirming factory reset.
/// The frontend calls this before showing the confirmation dialog.
#[tauri::command]
async fn prepare_delete_data(state: tauri::State<'_, Arc<BackendState>>) -> Result<String, String> {
    let token = uuid::Uuid::new_v4().to_string();
    *state
        .delete_token
        .lock()
        .expect("delete_token mutex poisoned") = Some(token.clone());
    Ok(token)
}

/// Phase 2: Execute factory reset only if the caller provides the correct token.
/// The token is consumed on every attempt (valid or not) to prevent replay.
#[tauri::command]
async fn confirm_delete_data(
    token: String,
    app: tauri::AppHandle,
    state: tauri::State<'_, Arc<BackendState>>,
) -> Result<(), String> {
    let stored = state
        .delete_token
        .lock()
        .expect("delete_token mutex poisoned")
        .take(); // consume — single use

    match stored {
        Some(expected) if expected == token => {}
        _ => return Err("Invalid or expired confirmation token".into()),
    }

    let app_data_dir = resolve_app_data_dir(&app)?;
    std::fs::create_dir_all(&app_data_dir).map_err(|e| {
        format!(
            "Failed to create app data dir {}: {e}",
            app_data_dir.display()
        )
    })?;

    let marker = app_data_dir.join(RESET_MARKER_FILE);
    std::fs::write(&marker, b"reset\n")
        .map_err(|e| format!("Failed to write reset marker {}: {e}", marker.display()))?;

    // Close the app so the reset runs on next launch, before the backend starts.
    app.exit(0);
    Ok(())
}

#[tauri::command]
async fn justsearch_paths(app: tauri::AppHandle) -> Result<JustSearchPaths, String> {
    let home = resolve_app_data_dir(&app)?;
    let models_dir = home.join("models");
    let llama_server_dir = home.join("native-bin").join("llama-server");
    let logs_dir = home.join("logs");
    Ok(JustSearchPaths {
        home: home.to_string_lossy().to_string(),
        models_dir: models_dir.to_string_lossy().to_string(),
        llama_server_dir: llama_server_dir.to_string_lossy().to_string(),
        logs_dir: logs_dir.to_string_lossy().to_string(),
        llama_log: logs_dir
            .join("llama-server.log")
            .to_string_lossy()
            .to_string(),
        engine_log: logs_dir.join("engine.log").to_string_lossy().to_string(),
    })
}

// Tempdoc 507 §3.5 / §6 Phase 4 + 508 §12.1 — file-based plugin distribution.
// Scans the plugin directory and returns each plugin's manifest + source so the
// frontend's PluginRegistry can install them at startup. Hot-reload uses the
// FE-side @tauri-apps/plugin-fs watcher; this command only handles startup scan
// + on-demand re-read.

#[derive(serde::Serialize)]
struct DiscoveredPlugin {
    id: String,
    path: String,
    #[serde(rename = "manifestJson")]
    manifest_json: String,
    #[serde(rename = "sourceText")]
    source_text: String,
    /// §13 critical-analysis A4: true when manifest.json or plugin.js
    /// exceeded the per-file size cap. FE skips installation; manifest/
    /// source are empty strings in that case.
    #[serde(rename = "tooLarge")]
    too_large: bool,
}

// §13 critical-analysis A4: bounded reads for plugin manifest + source.
// Manifest is structured JSON (KB-scale). Plugin source is JS; 1 MB
// covers complex plugins with docs and bundled deps, well under what
// a malicious file would need to exhaust memory.
const PLUGIN_MANIFEST_MAX_BYTES: u64 = 64 * 1024;
const PLUGIN_SOURCE_MAX_BYTES: u64 = 1024 * 1024;

/// Read a file as a UTF-8 string but cap by metadata length first.
/// Returns `Ok(None)` when the file exceeds the cap; caller treats
/// that as "skip with tooLarge flag." Returns `Err` on real I/O
/// errors.
fn read_capped(path: &std::path::Path, max_bytes: u64) -> Result<Option<String>, String> {
    let meta =
        std::fs::metadata(path).map_err(|e| format!("Failed to stat {}: {e}", path.display()))?;
    if meta.len() > max_bytes {
        return Ok(None);
    }
    std::fs::read_to_string(path)
        .map(Some)
        .map_err(|e| format!("Failed to read {}: {e}", path.display()))
}

fn plugin_dir(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let home = resolve_app_data_dir(&app)?;
    Ok(home.join("plugins"))
}

#[tauri::command]
async fn get_plugin_dir(app: tauri::AppHandle) -> Result<String, String> {
    let dir = plugin_dir(&app)?;
    // Create the directory if it doesn't exist — file-based distribution
    // assumes the user can drop plugins into it without first creating it.
    if !dir.exists() {
        std::fs::create_dir_all(&dir)
            .map_err(|e| format!("Failed to create plugin dir {}: {e}", dir.display()))?;
    }
    Ok(dir.to_string_lossy().to_string())
}

#[tauri::command]
async fn scan_plugins(app: tauri::AppHandle) -> Result<Vec<DiscoveredPlugin>, String> {
    let dir = plugin_dir(&app)?;
    if !dir.exists() {
        // No plugins directory means no plugins — not an error.
        return Ok(Vec::new());
    }
    let entries = std::fs::read_dir(&dir)
        .map_err(|e| format!("Failed to read plugin dir {}: {e}", dir.display()))?;
    let mut out = Vec::new();
    for entry in entries.flatten() {
        let entry_path = entry.path();
        if !entry_path.is_dir() {
            continue;
        }
        let manifest_path = entry_path.join("manifest.json");
        let source_path = entry_path.join("plugin.js");
        if !manifest_path.is_file() || !source_path.is_file() {
            // Skip directories that aren't valid plugin layouts. A plugin
            // requires both manifest.json and plugin.js at the top level.
            continue;
        }
        // §13 critical-analysis A4: cap reads at PLUGIN_MANIFEST_MAX_BYTES
        // and PLUGIN_SOURCE_MAX_BYTES. Oversize files return a
        // tooLarge entry so the FE can skip + log without consuming
        // arbitrary memory.
        let manifest_opt = read_capped(&manifest_path, PLUGIN_MANIFEST_MAX_BYTES)?;
        let source_opt = read_capped(&source_path, PLUGIN_SOURCE_MAX_BYTES)?;
        let too_large = manifest_opt.is_none() || source_opt.is_none();
        let manifest_json = manifest_opt.unwrap_or_default();
        let source_text = source_opt.unwrap_or_default();
        // Extract id from the directory name. We could parse manifest_json for
        // the id field, but the directory name is the canonical install id and
        // mismatches are a user error caught later by PluginRegistry.install.
        let id = entry_path
            .file_name()
            .and_then(|n| n.to_str())
            .map(String::from)
            .unwrap_or_default();
        out.push(DiscoveredPlugin {
            id,
            path: entry_path.to_string_lossy().to_string(),
            manifest_json,
            source_text,
            too_large,
        });
    }
    Ok(out)
}

#[tauri::command]
async fn read_plugin_source(path: String) -> Result<String, String> {
    // Accept either a full plugin directory path or a direct file path. If a
    // directory is passed, read plugin.js from within it.
    let p = PathBuf::from(&path);
    let target = if p.is_dir() { p.join("plugin.js") } else { p };
    // §13 critical-analysis A4: same 1 MB source cap as scan_plugins.
    // Hot-reload reads through this function too; an oversized file
    // returns a structured error so the caller doesn't OOM.
    match read_capped(&target, PLUGIN_SOURCE_MAX_BYTES)? {
        Some(content) => Ok(content),
        None => Err(format!(
            "Plugin source at {} exceeds size cap of {} bytes",
            target.display(),
            PLUGIN_SOURCE_MAX_BYTES
        )),
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // First, before any window or Engine exists: at Windows shutdown and logoff the shell is ended
    // before other applications, so its incarnation job closes and takes the Engine and its helpers
    // with it (Spec F §0.1 SHELL_SHUTDOWN_LEVEL).
    containment::set_shell_shutdown_priority();
    let state = Arc::new(BackendState::default());
    let _session_window = session_window::start(state.host.clone(), state.quit.clone())
        .expect("Windows session listener");
    let state_for_close = state.clone(); // Clone before setup() moves state
    let update_coordinator = Arc::new(updater::UpdateCoordinator::default());

    tauri::Builder::default()
        .manage(state.clone())
        .manage(update_coordinator.clone())
        .invoke_handler(tauri::generate_handler![
            supervisor_state,
            api_port,
            session_token,
            smoke_run_id,
            get_file_metadata,
            open_file,
            open_external_url,
            reveal_in_explorer,
            prepare_delete_data,
            confirm_delete_data,
            justsearch_paths,
            get_plugin_dir,
            scan_plugins,
            read_plugin_source,
            updater::app_update_status,
            updater::check_for_app_update,
            updater::install_app_update
        ])
        .setup(move |app| {
            let session_app = app.handle().clone();
            state.quit.set_session_exit(move || session_app.exit(0));
            update_coordinator.initialize(app.handle());
            let launch_data_dir = resolve_app_data_dir(app.handle()).ok();
            // Best-effort: spawn the bundled headless backend.
            // If this fails in dev, the UI can still be pointed at an external backend via ?api_port=...
            if let Err(err) = maybe_run_factory_reset(app.handle()) {
                eprintln!("Factory reset failed: {err}");
            }
            if let Err(err) = start_supervision(app.handle(), state.clone()) {
                {
                    state.host.record_spawn_error(err.clone());
                }
                // Notify waiters so they don't block forever on spawn error
                state.port_ready.notify_waiters();
                state.session_token_ready.notify_waiters();
                publish_terminal_spawn_failure(
                    &state.host,
                    state.binding_snapshot().instance_id,
                    launch_data_dir.as_deref(),
                    &err,
                    |record| {
                        let _ = app.emit(EVENT_SUPERVISOR_STATE, record);
                    },
                );
                eprintln!("Failed to spawn headless backend: {err}");
            }
            let reconciliation_app = app.handle().clone();
            let reconciliation_state = state.clone();
            let reconciliation_coordinator = update_coordinator.clone();
            tauri::async_runtime::spawn(async move {
                reconciliation_coordinator
                    .reconcile_after_backend_ready(
                        reconciliation_app.clone(),
                        reconciliation_state.clone(),
                    )
                    .await;
                // Sandbox qualification lane only; inert unless compiled with the test gate AND
                // explicitly opted in at runtime. Sequenced after reconciliation, never
                // concurrently: the PASS verdict IS the reconciled phase.
                updater::maybe_autorun_qualification(
                    reconciliation_app,
                    reconciliation_state,
                    reconciliation_coordinator.clone(),
                )
                .await;
            });

            // System tray: icon with Show/Quit menu, left-click to show window.
            let show_i = MenuItem::with_id(app, "show", "Show JustSearch", true, None::<&str>)?;
            let quit_i = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show_i, &quit_i])?;

            // Tempdoc 501 Phase 17: capture the built TrayIcon into BackendState so the
            // manifest watcher can drive `set_tooltip` on lifecycle transitions.
            // Tempdoc 501 Phase 17: register the tray with an explicit id so the
            // manifest watcher can later look it up via `app.tray_by_id(...)` without
            // holding a direct `TrayIcon` reference (which would force the tray runtime
            // into the test binary; see BackendState.tray_id docs).
            const TRAY_ID: &str = "justsearch-main-tray";
            let _tray_handle = TrayIconBuilder::with_id(TRAY_ID)
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("JustSearch")
                .menu(&menu)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show" => {
                        if let Some(w) = app.get_webview_window("main") {
                            let _ = w.show();
                            let _ = w.unminimize();
                            let _ = w.set_focus();
                        }
                    }
                    "quit" => {
                        for (_, window) in app.webview_windows() {
                            let _ = window.hide();
                        }
                        if let Some(state) = app.try_state::<Arc<BackendState>>() {
                            state.quit.begin(quit::QuitCause::TrayQuit);
                        }
                        app.exit(0);
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        if let Some(w) = tray.app_handle().get_webview_window("main") {
                            let _ = w.show();
                            let _ = w.unminimize();
                            let _ = w.set_focus();
                        }
                    }
                })
                .build(app)?;
            {
                let mut guard = state.tray_id.lock().expect("tray_id mutex poisoned");
                *guard = Some(TRAY_ID.to_string());
            }
            let _ = TRAY_CONTEXT.set(app.handle().clone());

            // Show the main window unless launched with --minimized (autostart).
            // Window-state plugin restores position/size but not visibility (VISIBLE
            // flag is excluded), so we control show/hide here.
            let start_minimized = std::env::args().any(|a| a == "--minimized");
            if !start_minimized {
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.show();
                    let _ = w.set_focus();
                }
            }

            // Slice 489 §15 — bridge OS deep-link arrivals (warm-launch path:
            // running instance receives a URL after setup). Cold-launch path
            // (first invocation arg) is handled in the single-instance
            // callback above; on macOS the plugin also calls on_open_url for
            // cold launches because Info.plist routes the URL through the
            // app's existing event loop.
            let app_handle = app.handle().clone();
            app.deep_link().on_open_url(move |event| {
                for url in event.urls() {
                    let url_str = url.to_string();
                    if url_str.starts_with("justsearch://") {
                        let _ = app_handle.emit("justsearch://deep-link", url_str);
                    }
                }
            });

            Ok(())
        })
        .plugin(tauri_plugin_single_instance::init(|app, argv, _cwd| {
            // Second launch should deterministically focus the existing instance and exit.
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
                let _ = window.unminimize();
                let _ = window.set_focus();
            } else if let Some((_label, window)) = app.webview_windows().into_iter().next() {
                // Fallback: focus the first available window.
                let _ = window.show();
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
            // Slice 489 §15 — handoff deep-link argv to the running instance.
            // On Windows/Linux, deep-link URLs arrive as the new process's
            // command-line args; tauri-plugin-single-instance forwards argv
            // through this callback. Forward any justsearch:// URLs to the FE
            // via the `deep-link` Tauri event (subscribed in tauriBridge.ts).
            for arg in argv.iter().skip(1) {
                if arg.starts_with("justsearch://") {
                    let _ = app.emit("justsearch://deep-link", arg.clone());
                }
            }
        }))
        // Slice 489 §15 — OS-level deep-link plugin (tauri-plugin-deep-link).
        // Pair with single-instance above so all incoming URLs land in the
        // running instance, not a fresh process. The plugin is initialized
        // before single-instance per the plugin's docs (Windows/Linux
        // registration runs here; macOS uses Info.plist registered at build
        // time, which the plugin's build script handles automatically when
        // `plugins.deep-link.desktop.schemes` is declared in tauri.conf.json).
        .plugin(tauri_plugin_deep_link::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(
            tauri_plugin_window_state::Builder::new()
                // Restore position/size but NOT visibility — we control show/hide
                // in setup() based on whether --minimized (autostart) is present.
                .with_state_flags(
                    tauri_plugin_window_state::StateFlags::all()
                        & !tauri_plugin_window_state::StateFlags::VISIBLE,
                )
                .build(),
        )
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec!["--minimized"]),
        ))
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        // Close-to-tray: hide the main window instead of exiting so the backend keeps running.
        .on_window_event(move |window, event| {
            // Only respond to main window close, not dialogs or devtools
            if window.label() != "main" {
                return;
            }
            if let WindowEvent::CloseRequested { api, .. } = event {
                // Hide to tray instead of exiting — backend keeps running.
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        // Explicit cleanup on app exit — kill backend to prevent orphan processes.
        // This fires when app.exit() is called (e.g., from tray menu "Quit").
        .run(move |app, event| {
            if let tauri::RunEvent::Exit = event {
                state_for_close.kill_child();
                if let Ok(data_dir) = resolve_app_data_dir(app) {
                    cleanup_registered_children_for_terminal(&data_dir);
                }
            }
        });
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::tempdir;

    #[cfg(windows)]
    pub(super) fn owned_sleep_child() -> (std::process::Child, ManagedChildRecord) {
        let child = Command::new("powershell.exe")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Start-Sleep -Seconds 30",
            ])
            .creation_flags(CREATE_NO_WINDOW)
            .spawn()
            .unwrap();
        let query = format!(
            "$p=Get-Process -Id {}; [pscustomobject]@{{started=$p.StartTime.ToUniversalTime().ToString('o'); executable=$p.Path}} | ConvertTo-Json -Compress",
            child.id()
        );
        let output = Command::new("powershell.exe")
            .args(["-NoProfile", "-NonInteractive", "-Command", &query])
            .creation_flags(CREATE_NO_WINDOW)
            .output()
            .unwrap();
        let identity: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
        let record = ManagedChildRecord {
            pid: child.id(),
            started_at: identity["started"].as_str().unwrap().to_string(),
            executable: identity["executable"].as_str().unwrap().to_string(),
        };
        (child, record)
    }

    #[cfg(windows)]
    #[test]
    fn exhaustion_cleans_registered_children() {
        let dir = tempdir().unwrap();
        fs::create_dir(dir.path().join("runtime")).unwrap();
        let (mut child, record) = owned_sleep_child();
        let manifest = serde_json::json!({ "schemaVersion": 2, "children": [{
            "pid": record.pid, "startedAt": record.started_at, "executable": record.executable
        }] });
        fs::write(dir.path().join("runtime/manifest.json"), manifest.to_string()).unwrap();
        cleanup_after_supervision(&supervisor::Outcome::NoEngine, dir.path());
        assert!(child.try_wait().unwrap().is_none());
        // Q15-I1: this manifest may belong to the incumbent holding the data directory.
        cleanup_after_supervision(&supervisor::Outcome::Exhausted {
            reason: "DATA_DIR_LOCKED".into(), exit_code: 2,
        }, dir.path());
        assert!(child.try_wait().unwrap().is_none());
        cleanup_after_supervision(&supervisor::Outcome::Exhausted {
            reason: "restart budget exhausted".into(), exit_code: 1,
        }, dir.path());
        assert!(child.try_wait().unwrap().is_some());
    }

    #[cfg(windows)]
    #[test]
    fn initial_spawn_failure_cleans_predecessor_children_before_terminal_event() {
        let dir = tempdir().unwrap();
        fs::create_dir(dir.path().join("runtime")).unwrap();
        let (mut matched, record) = owned_sleep_child();
        let (mut unrelated, mut mismatch) = owned_sleep_child();
        mismatch.executable.push_str(".unrelated");
        let manifest = serde_json::json!({ "schemaVersion": 2, "children": [
            { "pid": record.pid, "startedAt": record.started_at, "executable": record.executable },
            { "pid": mismatch.pid, "startedAt": mismatch.started_at, "executable": mismatch.executable }
        ] });
        fs::write(dir.path().join("runtime/manifest.json"), manifest.to_string()).unwrap();
        let host = EngineHost::default();
        let error = host
            .admit(PreparedCommand {
                command: Command::new("a-command-that-does-not-exist-justsearch"),
            })
            .unwrap_err();
        publish_terminal_spawn_failure(
            &host,
            Some("predecessor".into()),
            Some(dir.path()),
            &error,
            |record| {
                assert_eq!(record.state, "exhausted");
                assert_eq!(record.instance_id.as_deref(), Some("predecessor"));
                assert!(matched.try_wait().unwrap().is_some());
                assert!(unrelated.try_wait().unwrap().is_none());
            },
        );
        let disk: serde_json::Value = serde_json::from_str(
            &fs::read_to_string(dir.path().join("runtime/supervisor.v1.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(disk["state"], "exhausted");
        unrelated.kill().unwrap();
        let _ = unrelated.wait();
    }

    #[cfg(windows)]
    #[test]
    fn terminal_child_cleanup_kills_only_matching_identity() {
        let (mut matched, record) = owned_sleep_child();
        terminal_kill_if_identity_matches(&record).unwrap();
        assert!(matched.try_wait().unwrap().is_some());

        let (mut unrelated, mut mismatch) = owned_sleep_child();
        mismatch.executable.push_str(".unrelated");
        terminal_kill_if_identity_matches(&mismatch).unwrap();
        assert!(unrelated.try_wait().unwrap().is_none());
        unrelated.kill().unwrap();
        let _ = unrelated.wait();
    }

    #[test]
    fn terminal_cleanup_refuses_malformed_or_future_ownership_before_any_action() {
        let dir = tempdir().unwrap();
        fs::create_dir(dir.path().join("runtime")).unwrap();
        let path = dir.path().join("runtime/manifest.json");
        for bad in ["null", "{}", r#"{"schemaVersion":3,"children":[]}"#,
            r#"{"schemaVersion":2}"#, r#"{"schemaVersion":2,"children":null}"#,
            r#"{"schemaVersion":2,"children":{}}"#, r#"{"schemaVersion":2,"children":[{}]}"#] {
            fs::write(&path, bad).unwrap();
            assert!(reconcile_registered_children(dir.path()).is_err(), "{bad}");
        }
        for good in [r#"{"schemaVersion":1}"#, r#"{"schemaVersion":2,"children":[]}"#] {
            fs::write(&path, good).unwrap();
            reconcile_registered_children(dir.path()).unwrap();
        }
    }

    /// Lane F stage B item B10 adds a SECOND shell event and this pins the first one against it.
    ///
    /// `justsearch://backend-restart` is a contract with the webview (tempdoc 805 G.1): it fires on
    /// a new instanceId, carries the new port, and its subscriber drops the dead session token. The
    /// hazard the supervisor introduces is not that someone breaks it deliberately — it is that a
    /// later edit FOLDS the two events together, reasoning that "the supervisor already knows about
    /// restarts". That would make every cooldown tick look like a stale binding to the webview and
    /// every real rebind indistinguishable from a state update.
    ///
    /// Three things are pinned, because the event is three things: the NAME, the PAYLOAD, and the
    /// SUBSCRIBER. The subscriber is checked by reading the TypeScript as text — the same move
    /// `EngineExitTest` makes against `HeadlessApp.java` — since nothing else in this crate can see
    /// across that boundary.
    #[test]
    fn backend_restart_event_is_untouched_by_the_supervisor() {
        assert_eq!(EVENT_BACKEND_RESTART, "justsearch://backend-restart");
        assert_eq!(EVENT_SUPERVISOR_STATE, "justsearch://supervisor-state");
        assert_ne!(
            EVENT_BACKEND_RESTART, EVENT_SUPERVISOR_STATE,
            "the two events must stay distinct: one says the binding is stale, the other says what \
             the supervisor is doing"
        );

        // The payload is the new incarnation's port and nothing else. Widening it would make the
        // event a second discovery channel, which tempdoc 501 §6 closed.
        let manifest = ManifestFields {
            api_port: Some(40404),
            session_token: Some("t".into()),
            lifecycle: Some("READY".into()),
            instance_id: Some("abc".into()),
            pid: Some(7),
        };
        let payload: Option<u16> = backend_restart_payload(&manifest);
        assert_eq!(payload, Some(40404));
        assert_eq!(backend_restart_payload(&ManifestFields::default()), None);

        let consumer = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../ui-web/src/api/backendRestart.ts");
        let source = fs::read_to_string(&consumer)
            .unwrap_or_else(|e| panic!("cannot read {}: {e}", consumer.display()));
        assert!(
            source.contains(EVENT_BACKEND_RESTART),
            "the webview subscriber no longer names {EVENT_BACKEND_RESTART}"
        );
        assert!(
            source.contains("invalidateSessionToken"),
            "the subscriber must still drop the dead per-boot token; that is what the event is FOR"
        );
    }

    #[test]
    fn test_validate_path_rejects_traversal() {
        let input = if cfg!(windows) {
            "C:\\Users\\..\\etc\\passwd"
        } else {
            "/home/../etc/passwd"
        };
        let result = validate_user_path(input, false);
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("traversal"));
    }

    #[test]
    fn test_validate_path_rejects_unc() {
        let result = validate_user_path("\\\\server\\share\\file.txt", false);
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("Network"));

        let result2 = validate_user_path("//server/share/file.txt", false);
        assert!(result2.is_err());
    }

    #[test]
    fn test_validate_path_rejects_executables() {
        for ext in &[
            "exe", "bat", "cmd", "ps1", "vbs", "msi", "scr", "com", "lnk", "pif",
        ] {
            let path = format!("C:\\Users\\malware.{ext}");
            let result = validate_user_path(&path, true);
            assert!(result.is_err(), "should block .{ext}");
        }
        // Case-insensitive
        assert!(validate_user_path("C:\\file.EXE", true).is_err());
        assert!(validate_user_path("C:\\file.Bat", true).is_err());
    }

    #[test]
    fn test_validate_path_allows_normal_files() {
        assert!(validate_user_path("C:\\Users\\Docs\\report.pdf", false).is_ok());
        assert!(validate_user_path("C:\\Users\\Docs\\report.pdf", true).is_ok());
        assert!(validate_user_path("/home/user/docs/file.txt", false).is_ok());
    }

    #[test]
    fn test_validate_path_allows_exe_when_not_blocked() {
        assert!(validate_user_path("C:\\Program Files\\app.exe", false).is_ok());
    }

    #[test]
    fn test_read_manifest_parses_top_level_instance_id() {
        // Tempdoc 637 #1: instanceId is a TOP-LEVEL field (sibling of `lifecycle`), not under `head`.
        let dir = tempdir().unwrap();
        let path = dir.path().join("manifest.json");
        fs::write(
            &path,
            r#"{"instanceId":"abc-123","lifecycle":"READY","head":{"apiPort":40404,"sessionToken":"t"}}"#,
        )
        .unwrap();
        let m = read_manifest_if_present(&path).expect("manifest parses");
        assert_eq!(m.instance_id.as_deref(), Some("abc-123"));
        assert_eq!(m.api_port, Some(40404));
    }

    #[test]
    fn test_read_manifest_parses_top_level_pid() {
        // Tempdoc 805 G.1: the pid the provenance rule keys on is a TOP-LEVEL manifest field.
        let dir = tempdir().unwrap();
        let path = dir.path().join("manifest.json");
        fs::write(
            &path,
            r#"{"instanceId":"abc-123","pid":4242,"lifecycle":"READY","head":{"apiPort":40404,"sessionToken":"t"}}"#,
        )
        .unwrap();
        let m = read_manifest_if_present(&path).expect("manifest parses");
        assert_eq!(m.pid, Some(4242));
    }

    #[test]
    fn test_copy_dir_recursive_copies_subdirectories() {
        let src = tempdir().unwrap();
        let dest = tempdir().unwrap();

        // Create source structure: file.txt, subdir/nested.txt
        fs::write(src.path().join("file.txt"), "content").unwrap();
        fs::create_dir(src.path().join("subdir")).unwrap();
        fs::write(src.path().join("subdir/nested.txt"), "nested").unwrap();

        // Copy with force_update=true
        let copied = copy_dir_recursive(src.path(), dest.path(), true).unwrap();

        // Verify
        assert!(dest.path().join("file.txt").exists());
        assert!(dest.path().join("subdir").is_dir());
        assert!(dest.path().join("subdir/nested.txt").exists());
        assert_eq!(copied, 2); // Two files copied
    }

    #[test]
    fn test_copy_dir_recursive_skips_existing_when_not_forced() {
        let src = tempdir().unwrap();
        let dest = tempdir().unwrap();

        // Create source and dest with same file
        fs::write(src.path().join("file.txt"), "new content").unwrap();
        fs::write(dest.path().join("file.txt"), "old content").unwrap();

        // Copy with force_update=false
        let copied = copy_dir_recursive(src.path(), dest.path(), false).unwrap();

        // Should skip existing non-empty file
        assert_eq!(copied, 0);
        assert_eq!(
            fs::read_to_string(dest.path().join("file.txt")).unwrap(),
            "old content"
        );
    }

    #[test]
    fn test_copy_dir_recursive_copies_empty_files_when_not_forced() {
        let src = tempdir().unwrap();
        let dest = tempdir().unwrap();

        // Create source with content, dest with empty file
        fs::write(src.path().join("file.txt"), "content").unwrap();
        fs::write(dest.path().join("file.txt"), "").unwrap();

        // Copy with force_update=false (should copy because dest is empty)
        let copied = copy_dir_recursive(src.path(), dest.path(), false).unwrap();

        assert_eq!(copied, 1);
        assert_eq!(
            fs::read_to_string(dest.path().join("file.txt")).unwrap(),
            "content"
        );
    }

    #[test]
    fn test_delete_token_roundtrip() {
        let state = BackendState::default();
        let token = uuid::Uuid::new_v4().to_string();
        *state.delete_token.lock().unwrap() = Some(token.clone());

        // Take the token (simulates confirm_delete_data consuming it)
        let stored = state.delete_token.lock().unwrap().take();
        assert_eq!(stored, Some(token));
    }

    #[test]
    fn test_delete_token_rejects_wrong() {
        let state = BackendState::default();
        let token = uuid::Uuid::new_v4().to_string();
        *state.delete_token.lock().unwrap() = Some(token);

        let stored = state.delete_token.lock().unwrap().take();
        assert_ne!(stored.as_deref(), Some("wrong-token"));
    }

    #[test]
    fn test_delete_token_single_use() {
        let state = BackendState::default();
        let token = uuid::Uuid::new_v4().to_string();
        *state.delete_token.lock().unwrap() = Some(token.clone());

        // First take consumes the token
        let first = state.delete_token.lock().unwrap().take();
        assert_eq!(first, Some(token));

        // Second take returns None — token is consumed
        let second = state.delete_token.lock().unwrap().take();
        assert_eq!(second, None);
    }

    // Tempdoc 521 §16.6 — direct verification of the §13 A4 file-size
    // cap. Earlier follow-up reasoning ("no Tauri env to test") was
    // wrong: read_capped is plain Rust with no Tauri surface, so a
    // cargo unit test against this lib crate exercises the cap.

    #[test]
    fn test_read_capped_returns_some_for_under_cap_file() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("small.txt");
        fs::write(&path, "hello").unwrap();
        let res = read_capped(&path, 1024).unwrap();
        assert_eq!(res, Some("hello".to_string()));
    }

    #[test]
    fn test_read_capped_returns_none_for_oversized_file() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("oversized.txt");
        // 10 KB body, 1 KB cap.
        let body = vec![b'a'; 10 * 1024];
        fs::write(&path, &body).unwrap();
        let res = read_capped(&path, 1024).unwrap();
        assert!(
            res.is_none(),
            "read_capped should return None when meta.len() > cap"
        );
    }

    #[test]
    fn test_read_capped_honors_exact_boundary() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("exact.txt");
        // Exactly at the cap is allowed (the check is strict greater-than).
        let body = vec![b'b'; 1024];
        fs::write(&path, &body).unwrap();
        let res = read_capped(&path, 1024).unwrap();
        assert!(res.is_some(), "file exactly at cap must be allowed");
    }

    #[test]
    fn test_read_capped_errors_on_missing_file() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("missing.txt");
        let res = read_capped(&path, 1024);
        assert!(res.is_err());
    }
}

#[cfg(test)]
mod open_guard_tests {
    use super::*;
    use std::fs;
    use tempfile::tempdir;

    fn fixture(dir: &std::path::Path, name: &str) -> String {
        let path = dir.join(name);
        fs::write(&path, "fixture").unwrap();
        path.to_str().unwrap().to_owned()
    }

    #[test]
    fn documents_allow_exact_d1_formats() {
        let dir = tempdir().unwrap();
        let expected = [
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub",
            "txt", "md", "csv", "tsv", "log", "json", "xml", "html", "htm", "eml", "msg", "png",
            "jpg", "jpeg", "gif", "bmp", "webp", "tif", "tiff", "svg", "mp3", "wav", "m4a", "flac",
            "mp4", "mov", "mkv", "webm", "markdown", "mdx", "yaml", "yml", "toml", "ini", "conf",
            "rst", "tex", "srt", "vtt", "ics", "vcf", "mbox", "odg", "dotx", "ppsx", "vsdx", "xps",
            "mobi", "djvu", "cbz", "heic", "avif", "ico", "psd", "raw", "ogg", "opus", "aac",
            "wma", "avi", "wmv", "m4v", "mpg", "mpeg", "zip", "7z",
        ];
        assert_eq!(OPEN_DOCUMENT_EXTENSIONS, expected);
        for ext in expected {
            for case in [ext.to_string(), ext.to_ascii_uppercase()] {
                let path = fixture(dir.path(), &format!("document.{case}"));
                assert!(local_open_target(&path).is_ok(), "document .{case}");
            }
        }
    }

    #[test]
    fn refuses_unlisted_types_and_names_each_type() {
        let dir = tempdir().unwrap();
        let mut failures = Vec::new();
        for ext in [
            "hta",
            "js",
            "jse",
            "vbs",
            "vbe",
            "wsf",
            "wsh",
            "wsc",
            "cpl",
            "msc",
            "msi",
            "msp",
            "mst",
            "scr",
            "pif",
            "com",
            "exe",
            "bat",
            "cmd",
            "ps1",
            "reg",
            "inf",
            "lnk",
            "url",
            "scf",
            "chm",
            "jar",
            "appref-ms",
            "application",
            "settingcontent-ms",
            "library-ms",
            "appx",
            "msix",
            "desktop",
            "kdelnk",
            "sh",
            "run",
            "AppImage",
            "docm",
            "xlsm",
            "pptm",
            "mht",
            "mhtml",
            "py",
            "rs",
            "unknown",
        ] {
            for case in [ext.to_string(), ext.to_ascii_uppercase()] {
                let path = fixture(dir.path(), &format!("launcher.{case}"));
                let result = local_open_target(&path);
                if !matches!(&result, Err(message) if message.to_ascii_lowercase().contains(&format!(".{}", case.to_ascii_lowercase())))
                {
                    failures.push(format!(".{case}: {result:?}"));
                }
            }
        }
        assert_eq!(failures, Vec::<String>::new(), "unrefused or unnamed types");
    }

    #[test]
    fn refuses_extensionless_files() {
        let dir = tempdir().unwrap();
        let path = fixture(dir.path(), "launcher");
        assert!(matches!(local_open_target(&path), Err(e) if e.contains("no extension")));
    }

    #[test]
    fn refuses_missing_paths() {
        let dir = tempdir().unwrap();
        assert!(
            matches!(local_open_target(dir.path().join("missing.pdf").to_str().unwrap()), Err(e) if e.contains("resolve"))
        );
    }

    #[test]
    fn urls_accept_only_web_and_mail() {
        for url in [
            "http://example.com/a?q=1#fragment",
            "HTTPS://example.com/?q=1#f",
            "MAILTO:user@example.com?subject=Hi#f",
        ] {
            assert!(validate_external_url(url).is_ok(), "{url}");
        }
        // A drive letter is never classified as a URL scheme, on either build host.
        assert!(is_windows_drive_path("C:\\docs\\report.pdf"));
        assert!(is_windows_drive_path("c:/docs/report.pdf"));
        assert!(!is_windows_drive_path("https://example.com"));
    }

    #[test]
    fn refuses_other_url_schemes_in_every_case() {
        let mut failures = Vec::new();
        for scheme in [
            "file",
            "javascript",
            "vbscript",
            "data",
            "ms-settings",
            "ms-msdt",
            "search-ms",
            "shell",
            "tel",
            "ftp",
            "custom",
        ] {
            for case in [scheme.to_string(), scheme.to_ascii_uppercase()] {
                let url = format!("{case}:payload");
                if validate_external_url(&url).is_ok() {
                    failures.push(url);
                }
            }
        }
        assert_eq!(failures, Vec::<String>::new(), "accepted schemes");
    }

    #[test]
    fn refuses_malformed_and_control_urls() {
        let mut failures = Vec::new();
        for input in [
            "",
            "not a URL",
            "https://",
            "http://[",
            "mailto:",
            " https://example.com",
            "https://example.com\n",
            "https://exam\tple.com",
            "https://example.com/\0x",
            "mailto:u@example.com\r?subject=x",
        ] {
            if validate_external_url(input).is_ok() {
                failures.push(input);
            }
        }
        assert_eq!(
            failures,
            Vec::<&str>::new(),
            "accepted malformed/control URLs"
        );
    }

    #[test]
    fn refuses_urls_on_local_path() {
        let mut failures = Vec::new();
        for input in [
            "http://example.com/a.pdf",
            "https://example.com/a.pdf",
            "mailto:a.pdf",
            "file:///tmp/a.pdf",
            "javascript:a.pdf",
            "vbscript:a.pdf",
            "ms-msdt:a.pdf",
            "search-ms:a.pdf",
            "shell:a.pdf",
            "TEL:a.pdf",
            "ms-settings:a.pdf",
            "data:a.pdf",
        ] {
            if local_open_target(input).is_ok() {
                failures.push(input);
            }
        }
        assert_eq!(failures, Vec::<&str>::new(), "local path accepted URLs");
    }

    #[cfg(unix)]
    #[test]
    fn symlinks_use_resolved_type_and_target() {
        use std::os::unix::fs::symlink;
        let dir = tempdir().unwrap();
        let document = fixture(dir.path(), "document.odt");
        let alias = dir.path().join("alias.pdf");
        symlink(&document, &alias).unwrap();
        assert_eq!(
            local_open_target(alias.to_str().unwrap()).unwrap(),
            fs::canonicalize(&document).unwrap()
        );
    }

    #[cfg(unix)]
    #[test]
    fn refuses_allowed_name_symlink_to_launcher() {
        use std::os::unix::fs::symlink;
        let dir = tempdir().unwrap();
        let launcher = fixture(dir.path(), "launcher.desktop");
        let alias = dir.path().join("document.pdf");
        symlink(&launcher, &alias).unwrap();
        assert!(
            matches!(local_open_target(alias.to_str().unwrap()), Err(e) if e.contains(".desktop"))
        );
    }

    #[cfg(unix)]
    #[test]
    fn refuses_dangling_and_looping_links() {
        use std::os::unix::fs::symlink;
        let dir = tempdir().unwrap();
        let dangling = dir.path().join("dangling.pdf");
        let looping = dir.path().join("loop.pdf");
        symlink(dir.path().join("absent.pdf"), &dangling).unwrap();
        symlink(&looping, &looping).unwrap();
        let outcomes = [
            local_open_target(dangling.to_str().unwrap()),
            local_open_target(looping.to_str().unwrap()),
        ];
        assert_eq!(
            outcomes.iter().filter(|r| r.is_ok()).count(),
            0,
            "{outcomes:?}"
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_colons_are_kept_and_trailing_suffixes_refused() {
        let dir = tempdir().unwrap();
        let path = fixture(dir.path(), "report:2026.pdf");
        assert_eq!(
            local_open_target(&path).unwrap(),
            fs::canonicalize(path).unwrap()
        );
        let outcomes: Vec<_> = [
            "launcher.exe.",
            "launcher.EXE ",
            "launcher.desktop..",
            "launcher.sh ",
        ]
        .iter()
        .map(|name| local_open_target(&fixture(dir.path(), name)))
        .collect();
        assert_eq!(
            outcomes.iter().filter(|r| r.is_ok()).count(),
            0,
            "{outcomes:?}"
        );
    }

    #[test]
    fn reveal_returns_existing_directory_for_files_and_folders() {
        let dir = tempdir().unwrap();
        let path = fixture(dir.path(), "launcher.desktop");
        let (item, folder) = reveal_target(&path).unwrap();
        assert_eq!(item, fs::canonicalize(path).unwrap());
        assert_eq!(folder, fs::canonicalize(dir.path()).unwrap());
        let (item, folder) = reveal_target(dir.path().to_str().unwrap()).unwrap();
        assert_eq!(item, folder);
        assert!(folder.is_dir());
    }

    #[test]
    fn refuses_reveal_under_file_or_missing_item() {
        let dir = tempdir().unwrap();
        let path = fixture(dir.path(), "launcher.desktop");
        let outcomes = [
            reveal_target(&format!("{path}/child")),
            reveal_target(dir.path().join("absent.pdf").to_str().unwrap()),
        ];
        assert_eq!(
            outcomes.iter().filter(|r| r.is_ok()).count(),
            0,
            "{outcomes:?}"
        );
    }

    // Each subprocess has its own PATH and marker; parallel tests cannot affect the OS opener.
    #[cfg(target_os = "linux")]
    fn run_dispatch_case(case: &str) {
        if std::env::var_os("OPEN_GUARD_CASE").is_some() {
            linux_dispatch_worker();
            return;
        }
        let name = match case {
            "refuse" => "open_guard_tests::linux_commands_refuse_launchers_without_dispatch",
            "preserve" => {
                "open_guard_tests::linux_commands_dispatch_resolved_pdf_and_reveal_directory"
            }
            "plugin" => "open_guard_tests::direct_plugin_ipc_refuses_paths_reveal_and_tel",
            "folders" => "open_guard_tests::linux_commands_open_folders_of_any_name",
            _ => unreachable!(),
        };
        use std::os::unix::fs::PermissionsExt;
        let dir = tempdir().unwrap();
        let opener = dir.path().join("xdg-open");
        fs::write(
            &opener,
            "#!/bin/sh\nprintf '%s\\n' \"$1\" >> \"$OPEN_GUARD_MARKER\"\n",
        )
        .unwrap();
        fs::set_permissions(&opener, fs::Permissions::from_mode(0o755)).unwrap();
        let result = Command::new(std::env::current_exe().unwrap())
            .args(["--exact", name, "--nocapture"])
            .env("OPEN_GUARD_CASE", case)
            .env(
                "DBUS_SESSION_BUS_ADDRESS",
                "unix:path=/nonexistent-open-guard-session-bus",
            )
            .env("OPEN_GUARD_DIR", dir.path())
            .env("OPEN_GUARD_MARKER", dir.path().join("marker"))
            .env("PATH", dir.path())
            .output()
            .unwrap();
        assert_eq!(
            result.status.code(),
            Some(0),
            "actual command dispatch case {case}:\n{}\n{}",
            String::from_utf8_lossy(&result.stdout),
            String::from_utf8_lossy(&result.stderr)
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_commands_refuse_launchers_without_dispatch() {
        run_dispatch_case("refuse");
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_commands_dispatch_resolved_pdf_and_reveal_directory() {
        run_dispatch_case("preserve");
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn direct_plugin_ipc_refuses_paths_reveal_and_tel() {
        run_dispatch_case("plugin");
    }

    #[cfg(target_os = "linux")]
    fn linux_dispatch_worker() {
        let case = std::env::var("OPEN_GUARD_CASE").unwrap();
        let dir = PathBuf::from(std::env::var("OPEN_GUARD_DIR").unwrap());
        let marker = dir.join("marker");
        let runtime = tokio::runtime::Runtime::new().unwrap();
        match case.as_str() {
            "refuse" => {
                let mut results = Vec::new();
                for name in ["crafted.desktop", "crafted.sh", "crafted.pdf.exe"] {
                    let path = fixture(&dir, name);
                    results.push(runtime.block_on(open_file(path)));
                }
                for url in [
                    "javascript:alert(1)",
                    "FILE:///tmp/launcher.desktop",
                    "tel:123",
                ] {
                    results.push(runtime.block_on(open_external_url(url.into())));
                }
                let launcher = fixture(&dir, "parent.desktop");
                results.push(runtime.block_on(reveal_in_explorer(format!("{launcher}/child"))));
                assert!(
                    results.iter().all(Result::is_err) && !marker.exists(),
                    "refusals: {results:?}; dispatched: {:?}",
                    fs::read_to_string(&marker)
                );
            }
            "preserve" => {
                use std::os::unix::fs::symlink;
                let document = fixture(&dir, "real.pdf");
                let alias = dir.join("alias.txt");
                symlink(&document, &alias).unwrap();
                assert!(runtime
                    .block_on(open_file(alias.to_string_lossy().into_owned()))
                    .is_ok());
                assert!(runtime
                    .block_on(reveal_in_explorer(document.clone()))
                    .is_ok());
                assert!(runtime
                    .block_on(reveal_in_explorer(dir.to_string_lossy().into_owned()))
                    .is_ok());
                for url in [
                    "http://example.com/?q=1#f",
                    "https://example.com/?q=1#f",
                    "mailto:user@example.com?subject=Hi",
                ] {
                    assert!(runtime.block_on(open_external_url(url.into())).is_ok());
                }
                let expected = format!("{document}\n{}\n{}\nhttp://example.com/?q=1#f\nhttps://example.com/?q=1#f\nmailto:user@example.com?subject=Hi\n", dir.display(), dir.display());
                assert_eq!(fs::read_to_string(marker).unwrap(), expected);
            }
            "folders" => {
                let mut expected = String::new();
                let mut results = Vec::new();
                for name in ["folder", "folder.exe", "folder.desktop"] {
                    let folder = dir.join(name);
                    fs::create_dir(&folder).unwrap();
                    results
                        .push(runtime.block_on(open_file(folder.to_string_lossy().into_owned())));
                    results.push(
                        runtime.block_on(reveal_in_explorer(folder.to_string_lossy().into_owned())),
                    );
                    expected.push_str(&format!("{}\n{}\n", folder.display(), folder.display()));
                }
                assert_eq!(results, vec![Ok(()); 6]);
                assert_eq!(fs::read_to_string(marker).unwrap(), expected);
            }
            "plugin" => {
                let app = tauri::test::mock_builder()
                    .plugin(tauri_plugin_opener::init())
                    .build(tauri::generate_context!())
                    .unwrap();
                let webview = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
                    .build()
                    .unwrap();
                let launcher = fixture(&dir, "crafted.desktop");
                let mut failures = Vec::new();
                for (cmd, body) in [
                    ("open_path", serde_json::json!({"path": launcher})),
                    (
                        "reveal_item_in_dir",
                        serde_json::json!({"paths": [launcher]}),
                    ),
                    ("open_url", serde_json::json!({"url": "tel:123"})),
                    ("open_url", serde_json::json!({"url": "file:///tmp/a.pdf"})),
                    (
                        "open_url",
                        serde_json::json!({"url": "https://example.com", "with": "/bin/sh"}),
                    ),
                ] {
                    let result = tauri::test::get_ipc_response(
                        &webview,
                        tauri::webview::InvokeRequest {
                            cmd: format!("plugin:opener|{cmd}"),
                            callback: tauri::ipc::CallbackFn(0),
                            error: tauri::ipc::CallbackFn(1),
                            url: "tauri://localhost".parse().unwrap(),
                            body: tauri::ipc::InvokeBody::Json(body),
                            headers: Default::default(),
                            invoke_key: tauri::test::INVOKE_KEY.into(),
                        },
                    );
                    let message = result
                        .as_ref()
                        .err()
                        .and_then(|error| error.as_str())
                        .unwrap_or("");
                    let expected = if cmd == "open_url" {
                        "Not allowed to open url ".to_string()
                    } else {
                        format!("opener.{cmd} not allowed")
                    };
                    if !message.starts_with(&expected) {
                        failures.push(format!("{cmd}: {result:?}"));
                    }
                }
                assert!(
                    failures.is_empty() && !marker.exists(),
                    "plugin accepted {failures:?}; dispatch: {:?}",
                    fs::read_to_string(&marker)
                );
                // The injected link handler still has a usable command and URL scope.
                for url in [
                    "http://example.com/?q=1#f",
                    "https://example.com",
                    "mailto:user@example.com",
                ] {
                    let result = tauri::test::get_ipc_response(
                        &webview,
                        tauri::webview::InvokeRequest {
                            cmd: "plugin:opener|open_url".into(),
                            callback: tauri::ipc::CallbackFn(0),
                            error: tauri::ipc::CallbackFn(1),
                            url: "tauri://localhost".parse().unwrap(),
                            body: tauri::ipc::InvokeBody::Json(serde_json::json!({"url": url})),
                            headers: Default::default(),
                            invoke_key: tauri::test::INVOKE_KEY.into(),
                        },
                    );
                    assert!(result.is_ok(), "allowed plugin link {url}: {result:?}");
                }
                let deadline = std::time::Instant::now() + Duration::from_secs(2);
                loop {
                    let contents = fs::read_to_string(&marker).unwrap_or_default();
                    if contents.lines().count() == 3 {
                        break;
                    }
                    assert!(
                        std::time::Instant::now() < deadline,
                        "plugin dispatches: {contents:?}"
                    );
                    std::thread::sleep(Duration::from_millis(10));
                }
            }
            other => panic!("unknown dispatch case: {other}"),
        }
    }

    #[cfg(windows)]
    #[test]
    fn windows_ntfs_short_names_trailing_dots_ads_and_junctions() {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::Storage::FileSystem::{GetShortPathNameW, GetVolumeInformationW};
        let dir = tempdir().unwrap();
        let root: Vec<u16> = format!(
            "{}\\",
            dir.path()
                .components()
                .next()
                .unwrap()
                .as_os_str()
                .to_string_lossy()
        )
        .encode_utf16()
        .chain(Some(0))
        .collect();
        let mut filesystem = [0u16; 32];
        assert_ne!(
            unsafe {
                GetVolumeInformationW(
                    root.as_ptr(),
                    std::ptr::null_mut(),
                    0,
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    filesystem.as_mut_ptr(),
                    filesystem.len() as u32,
                )
            },
            0,
            "real filesystem query"
        );
        assert_eq!(
            String::from_utf16_lossy(&filesystem).trim_end_matches('\0'),
            "NTFS",
            "requires real NTFS"
        );
        let launcher = fixture(dir.path(), "long-launcher-name.application");
        let wide: Vec<u16> = std::ffi::OsStr::new(&launcher)
            .encode_wide()
            .chain(Some(0))
            .collect();
        let mut short = vec![0u16; 32768];
        let len =
            unsafe { GetShortPathNameW(wide.as_ptr(), short.as_mut_ptr(), short.len() as u32) };
        assert!(len > 0 && (len as usize) < short.len(), "GetShortPathNameW");
        let short = String::from_utf16(&short[..len as usize]).unwrap();
        assert!(
            short.contains('~'),
            "8.3 generation must be enabled for this check: {short}"
        );
        let mut results = vec![local_open_target(&short)];
        for suffix in [".", " ", ". "] {
            results.push(local_open_target(&format!("{launcher}{suffix}")));
        }
        let pdf = fixture(dir.path(), "report.pdf");
        let stream = format!("{pdf}:payload.exe");
        fs::write(&stream, "stream fixture").unwrap();
        results.push(local_open_target(&stream));
        let target_dir = dir.path().join("target");
        fs::create_dir(&target_dir).unwrap();
        fixture(&target_dir, "launcher.hta");
        let junction = dir.path().join("junction");
        let output = Command::new("cmd")
            .args(["/C", "mklink", "/J"])
            .arg(&junction)
            .arg(&target_dir)
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stdout)
        );
        results.push(local_open_target(
            junction.join("launcher.hta").to_str().unwrap(),
        ));
        assert_eq!(
            results.iter().filter(|r| r.is_ok()).count(),
            0,
            "{results:?}"
        );
        assert_eq!(
            local_open_target(&pdf).unwrap(),
            fs::canonicalize(pdf).unwrap()
        );
    }

    #[cfg(windows)]
    #[test]
    fn windows_refuses_mixed_network_device_and_ads_inputs() {
        let outcomes: Vec<_> = [
            "/\\server/share/file.pdf",
            "\\/server/share/file.pdf",
            "\\\\?\\C:\\file.pdf",
            "\\\\.\\C:\\file.pdf",
            r"\\?\GLOBALROOT\Device\HarddiskVolume1\file.pdf",
            r"\\?\other-verbatim\file.pdf",
            "C:\\file.pdf:stream",
        ]
        .iter()
        .map(|path| local_open_target(path))
        .collect();
        assert_eq!(
            outcomes.iter().filter(|r| r.is_ok()).count(),
            0,
            "{outcomes:?}"
        );
    }
    #[cfg(windows)]
    #[test]
    fn windows_symlinks_resolve_document_and_refuse_disguised_launcher() {
        use std::os::windows::fs::symlink_file;
        let dir = tempdir().unwrap();
        let document = fixture(dir.path(), "document.odt");
        let document_alias = dir.path().join("document-alias.pdf");
        symlink_file(&document, &document_alias).unwrap();
        assert_eq!(
            local_open_target(document_alias.to_str().unwrap()).unwrap(),
            fs::canonicalize(document).unwrap()
        );
        let launcher = fixture(dir.path(), "launcher.exe");
        let disguised = dir.path().join("disguised.pdf");
        symlink_file(&launcher, &disguised).unwrap();
        assert!(
            matches!(local_open_target(disguised.to_str().unwrap()), Err(e) if e.contains(".exe"))
        );
    }
    #[test]
    fn refuses_empty_and_control_local_paths() {
        let outcomes: Vec<_> = [
            "",
            "\0",
            "/tmp/document.pdf\n",
            "/tmp/doc\tument.pdf",
            "/tmp/document.pdf\r",
        ]
        .iter()
        .map(|input| local_open_target(input))
        .collect();
        assert_eq!(
            outcomes.iter().filter(|r| r.is_ok()).count(),
            0,
            "{outcomes:?}"
        );
    }
    #[test]
    fn directories_open_regardless_of_name() {
        let dir = tempdir().unwrap();
        for name in ["folder", "folder.exe", "folder.desktop", "folder.unknown"] {
            let folder = dir.path().join(name);
            fs::create_dir(&folder).unwrap();
            assert_eq!(
                local_open_target(folder.to_str().unwrap()),
                Ok(fs::canonicalize(folder).unwrap())
            );
        }
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_commands_open_folders_of_any_name() {
        run_dispatch_case("folders");
    }

    #[cfg(windows)]
    fn assert_plain_dispatched_path(result: Result<PathBuf, String>, expected: &Path) {
        assert_eq!(
            result.as_ref().map(|_| ()),
            Ok(()),
            "real dispatch failed: {result:?}"
        );
        let dispatched = result.unwrap();
        assert!(!dispatched
            .as_os_str()
            .to_string_lossy()
            .starts_with(r"\\?\"));
        assert_eq!(
            fs::canonicalize(&dispatched).unwrap(),
            fs::canonicalize(expected).unwrap()
        );
    }

    #[cfg(windows)]
    #[test]
    fn windows_real_open_uses_plain_dispatch_path() {
        let dir = tempdir().unwrap();
        let path = fixture(dir.path(), "real-document.txt");
        let target = local_open_target(&path).unwrap();
        assert_plain_dispatched_path(dispatch_open_target(&target), Path::new(&path));
    }

    #[cfg(windows)]
    #[test]
    fn windows_real_reveal_uses_plain_dispatch_path_for_file_and_folder() {
        let dir = tempdir().unwrap();
        let path = fixture(dir.path(), "real-document.txt");
        let (item, parent) = reveal_target(&path).unwrap();
        assert_plain_dispatched_path(dispatch_reveal_target(&item, &parent), Path::new(&path));
        let folder = dir.path().to_str().unwrap();
        let (item, directory) = reveal_target(folder).unwrap();
        assert_plain_dispatched_path(dispatch_reveal_target(&item, &directory), Path::new(folder));
    }

    #[cfg(windows)]
    #[test]
    fn windows_real_dispatch_handles_brackets_and_question_like_names() {
        let dir = tempdir().unwrap();
        let folder = dir.path().join("folder [draft] ？");
        fs::create_dir(&folder).unwrap();
        let path = fixture(&folder, "notes[1]？.txt");
        let target = local_open_target(&path).unwrap();
        assert_plain_dispatched_path(dispatch_open_target(&target), Path::new(&path));
        let (item, directory) = reveal_target(&path).unwrap();
        assert_plain_dispatched_path(dispatch_reveal_target(&item, &directory), Path::new(&path));
    }

    #[cfg(windows)]
    #[test]
    fn windows_refuses_verbatim_only_dispatch() {
        let dir = tempdir().unwrap();
        // A real NTFS name whose trailing dot requires verbatim path semantics.
        let folder = fs::canonicalize(dir.path()).unwrap().join("verbatim-only.");
        fs::create_dir(&folder).unwrap();
        let path = fixture(&folder, "document.txt");
        let target = fs::canonicalize(path).unwrap();
        let parent = target.parent().unwrap();
        let results = [
            dispatch_open_target(&target),
            dispatch_reveal_target(&target, parent),
        ];
        assert_eq!(results.iter().filter(|result| matches!(result, Err(message) if message.contains("non-verbatim Windows shell path"))).count(), 2, "{results:?}");
    }
    #[cfg(windows)]
    fn windows_open_with_neighbor_cmd(name: &str, directory: bool) {
        let dir = tempdir().unwrap();
        let target = dir.path().join(name);
        if directory {
            fs::create_dir(&target).unwrap();
        } else {
            fs::write(&target, "real document").unwrap();
        }
        let marker = dir.path().join("neighbor-ran.txt");
        fs::write(
            dir.path().join(format!("{name}.cmd")),
            format!("@echo neighbor-ran>\"{}\"\r\n", marker.display()),
        )
        .unwrap();
        let checked = local_open_target(target.to_str().unwrap()).unwrap();
        let result = dispatch_open_target(&checked);
        assert_eq!(
            result.as_ref().map(|_| ()),
            Ok(()),
            "real dispatch: {result:?}"
        );
        let deadline = std::time::Instant::now() + Duration::from_secs(4);
        while std::time::Instant::now() < deadline && !marker.exists() {
            std::thread::sleep(Duration::from_millis(50));
        }
        assert_eq!(
            marker.exists(),
            false,
            "neighbor .cmd executed for {}",
            target.display()
        );
        assert_plain_dispatched_path(result, &target);
    }

    #[cfg(windows)]
    #[test]
    fn windows_folder_does_not_execute_neighbor_cmd() {
        windows_open_with_neighbor_cmd("Invoice", true);
    }

    #[cfg(windows)]
    #[test]
    fn windows_document_does_not_execute_neighbor_cmd() {
        windows_open_with_neighbor_cmd("report.txt", false);
    }

    #[cfg(windows)]
    #[test]
    fn windows_bracket_folder_does_not_execute_neighbor_cmd() {
        windows_open_with_neighbor_cmd("Q3 [draft]", true);
    }

    #[cfg(windows)]
    #[test]
    fn windows_mapped_unc_drive_opens_document_and_refuses_launcher() {
        use windows_sys::Win32::Storage::FileSystem::GetLogicalDrives;
        let drives = unsafe { GetLogicalDrives() };
        if drives == 0 {
            panic!(
                "Cannot enumerate drives: {}",
                std::io::Error::last_os_error()
            );
        }
        let letter = (b'P'..=b'Z')
            .rev()
            .find(|letter| drives & (1 << (letter - b'A')) == 0)
            .expect("a free drive letter is required for the mapping check")
            as char;
        let drive = format!("{letter}:");
        let mapped = Command::new("net.exe")
            .args(["use", &drive, r"\\localhost\C$", "/persistent:no"])
            .output()
            .unwrap();
        if !mapped.status.success() {
            panic!("The UNC mapping check requires \\\\localhost\\C$; no subst fallback can prove VerbatimUNC behavior: {} {}",
                String::from_utf8_lossy(&mapped.stdout), String::from_utf8_lossy(&mapped.stderr));
        }
        // Always remove only this test's successful mapping, including on assertion failure.
        let result = std::panic::catch_unwind(|| {
            let document = format!(r"{drive}\Windows\win.ini");
            let target = local_open_target(&document).unwrap();
            assert!(
                matches!(target.components().next(), Some(std::path::Component::Prefix(prefix))
                if matches!(prefix.kind(), std::path::Prefix::VerbatimUNC(_, _)))
            );
            let dispatched = dispatch_open_target(&target);
            assert_plain_dispatched_path(dispatched, Path::new(&document));
            let launcher = format!(r"{drive}\Windows\System32\cmd.exe");
            assert!(
                matches!(local_open_target(&launcher), Err(message) if message.contains(".exe"))
            );
            let folder = format!(r"{drive}\Windows");
            let target = local_open_target(&folder).unwrap();
            assert_plain_dispatched_path(dispatch_open_target(&target), Path::new(&folder));
        });
        let cleanup = Command::new("net.exe")
            .args(["use", &drive, "/delete", "/y"])
            .output()
            .unwrap();
        if let Err(failure) = result {
            if !cleanup.status.success() {
                eprintln!(
                    "Mapping cleanup failed for {drive}: {}",
                    String::from_utf8_lossy(&cleanup.stderr)
                );
            }
            std::panic::resume_unwind(failure);
        }
        assert_eq!(
            cleanup.status.code(),
            Some(0),
            "could not remove test mapping {drive}"
        );
    }
}
