//! Shell-owned shortcut preference and pending focus; neither depends on the Engine.
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Mutex,
};
use tauri::{Emitter, Manager};
use tauri_plugin_global_shortcut::{
    Code, GlobalShortcutExt, Modifiers, Shortcut, ShortcutState as KeyState,
};

const PREFERENCE_FILE: &str = "search-shortcut.v1.json";
const SUMMON_EVENT: &str = "justsearch://summon-search";
const STATUS_EVENT: &str = "justsearch://search-shortcut-status";

#[derive(Debug, PartialEq)]
enum Preference {
    Choice(bool),
    Unreadable(String),
}

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct PreferenceFile {
    version: u32,
    enabled: bool,
}

fn read_preference(root: &Path) -> Preference {
    match std::fs::read(root.join(PREFERENCE_FILE)) {
        Ok(bytes) => match serde_json::from_slice::<PreferenceFile>(&bytes) {
            Ok(file) if file.version == 1 => Preference::Choice(file.enabled),
            _ => Preference::Unreadable("The saved shortcut preference cannot be read by this version. Its bytes have been kept; change the switch to replace it.".into()),
        },
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Preference::Choice(true),
        Err(error) => Preference::Unreadable(format!("The saved shortcut preference could not be read: {error}. Its bytes have been kept.")),
    }
}

#[derive(Debug, PartialEq)]
pub(crate) struct LaunchRequest {
    pub(crate) focus_search: bool,
    pub(crate) show_window: bool,
    pub(crate) deep_links: Vec<String>,
}
impl LaunchRequest {
    pub(crate) fn from_args(args: &[String]) -> Self {
        let deep_links: Vec<_> = args
            .iter()
            .filter(|arg| arg.starts_with("justsearch://"))
            .cloned()
            .collect();
        let focus_flag = args.iter().any(|arg| arg == "--focus-search");
        Self {
            focus_search: focus_flag && deep_links.is_empty(),
            show_window: focus_flag || !args.iter().any(|arg| arg == "--minimized"),
            deep_links,
        }
    }
}

fn wayland_session(session: Option<&str>, display: Option<&str>) -> bool {
    session.is_some_and(|value| value.eq_ignore_ascii_case("wayland"))
        || display.is_some_and(|value| !value.is_empty())
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub(crate) enum SearchSummon {
    FocusSearch,
    DeepLink { url: String },
}

#[derive(Default)]
struct PendingSummon {
    pending: Mutex<Option<SearchSummon>>,
    held: AtomicBool,
}
impl PendingSummon {
    fn request(&self) {
        *self.pending.lock().expect("pending summon mutex poisoned") =
            Some(SearchSummon::FocusSearch);
    }
    fn request_deep_link(&self, url: String) {
        *self.pending.lock().expect("pending summon mutex poisoned") =
            Some(SearchSummon::DeepLink { url });
    }
    fn take(&self) -> Option<SearchSummon> {
        self.pending
            .lock()
            .expect("pending summon mutex poisoned")
            .take()
    }
    fn chord(&self, pressed: bool) -> bool {
        if pressed {
            !self.held.swap(true, Ordering::AcqRel)
        } else {
            self.held.store(false, Ordering::Release);
            false
        }
    }
}

#[derive(Clone, Serialize)]
pub(crate) struct ShortcutStatus {
    enabled: bool,
    active: bool,
    reason: Option<String>,
    revision: u64,
}
struct ShortcutState {
    root: Option<PathBuf>,
    unavailable: Option<String>,
    status: ShortcutStatus,
}
impl Default for ShortcutState {
    fn default() -> Self {
        Self {
            root: None,
            unavailable: Some("Shortcut initialization is still in progress.".into()),
            status: ShortcutStatus {
                enabled: true,
                active: false,
                reason: Some("Shortcut initialization is still in progress.".into()),
                revision: 0,
            },
        }
    }
}

#[derive(Default)]
pub(crate) struct SearchShortcut {
    state: Mutex<ShortcutState>,
    pending: PendingSummon,
}

impl SearchShortcut {
    pub(crate) fn has_pending(&self) -> bool {
        self.pending
            .pending
            .lock()
            .expect("pending summon mutex poisoned")
            .is_some()
    }
}

fn chord() -> Shortcut {
    Shortcut::new(Some(Modifiers::ALT | Modifiers::SHIFT), Code::Space)
}

fn register(state: &mut ShortcutState, registration: &mut impl FnMut(bool) -> Result<(), String>) {
    state.status.active = false;
    if let Some(reason) = &state.unavailable {
        state.status.reason = Some(reason.clone());
    } else {
        match registration(true) {
            Ok(()) => {
                state.status.active = true;
                state.status.reason = None;
            }
            Err(_) => {
                state.status.reason = Some("The desktop refused Alt + Shift + Space (another application may be using it, or your desktop may not allow global shortcuts).".into());
            }
        }
    }
}

/// Recovery must finish before reading preferences or registering native shortcuts.
pub(crate) fn after_factory_reset(status: &super::factory_reset::Status, initialize: impl FnOnce()) {
    if !status.blocks_backend() {
        initialize();
    }
}

pub(crate) fn initialize(app: &tauri::AppHandle) {
    let runtime = app.state::<SearchShortcut>();
    let mut state = runtime
        .state
        .lock()
        .expect("search shortcut mutex poisoned");
    state.root = super::resolve_app_data_dir(app).ok();
    let preference = state
        .root
        .as_deref()
        .map(read_preference)
        .unwrap_or_else(|| {
            Preference::Unreadable(
                "The app-data folder could not be resolved. The shortcut is not active.".into(),
            )
        });
    state.unavailable = if cfg!(target_os = "linux")
        && wayland_session(
            std::env::var("XDG_SESSION_TYPE").ok().as_deref(),
            std::env::var("WAYLAND_DISPLAY").ok().as_deref(),
        ) {
        Some("Not available in this desktop session. Bind JustSearch --focus-search in your desktop's keyboard settings.".into())
    } else if !cfg!(any(windows, target_os = "linux")) {
        Some("The global search shortcut is not available on this platform.".into())
    } else {
        let plugin = tauri_plugin_global_shortcut::Builder::new()
            .with_handler(|app, shortcut, event| {
                if shortcut != &chord() {
                    return;
                }
                // No preference mutex in this callback: the plugin may still hold its own map lock.
                if app
                    .state::<SearchShortcut>()
                    .pending
                    .chord(event.state == KeyState::Pressed)
                {
                    // OS registration can be waiting for this callback thread. Window geometry
                    // reads wait for the UI thread, so release the plugin's lock before them.
                    let handle = app.clone();
                    let _ = app.run_on_main_thread(move || summon(&handle));
                }
            })
            .build();
        app.plugin(plugin)
            .err()
            .map(|_| "The desktop shortcut service could not start. Try restarting JustSearch, or use a desktop custom shortcut.".into())
    };
    match preference {
        Preference::Choice(enabled) => {
            state.status.enabled = enabled;
            state.status.reason = None;
            if enabled {
                register(&mut state, &mut |_| {
                    app.global_shortcut()
                        .register(chord())
                        .map_err(|error| error.to_string())
                });
            }
        }
        Preference::Unreadable(reason) => {
            state.status.enabled = true;
            state.status.active = false;
            state.status.reason = Some(reason);
        }
    }
    state.status.revision += 1;
}

/// Keep valid saved geometry; recover only a window wholly outside every connected monitor.
pub(crate) fn foreground(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        if let (Ok(position), Ok(size), Ok(monitors)) = (
            window.outer_position(),
            window.outer_size(),
            window.available_monitors(),
        ) {
            let visible = monitors.iter().any(|monitor| {
                let origin = monitor.position();
                let screen = monitor.size();
                i64::from(position.x) < i64::from(origin.x) + i64::from(screen.width)
                    && i64::from(position.x) + i64::from(size.width) > i64::from(origin.x)
                    && i64::from(position.y) < i64::from(origin.y) + i64::from(screen.height)
                    && i64::from(position.y) + i64::from(size.height) > i64::from(origin.y)
            });
            if !visible {
                if let Some(monitor) = window
                    .primary_monitor()
                    .ok()
                    .flatten()
                    .or_else(|| monitors.first().cloned())
                {
                    let _ = window.set_position(*monitor.position());
                }
            }
        }
        let _ = window.set_focus();
        // Tao may discard set_focus while show/unminimize is still queued. Request
        // activation unconditionally on the UI thread, where native restoration
        // and activation can happen together, including a never-shown window.
        #[cfg(any(target_os = "linux", windows))]
        {
            let window = window.clone();
            let _ = app.run_on_main_thread(move || {
                #[cfg(target_os = "linux")]
                if let Ok(native) = window.gtk_window() {
                    use gtk::prelude::{GtkWindowExt, WidgetExt};
                    native.present_with_time(0);
                    // On the first map GTK's present may only show the widget.
                    // Ask GDK explicitly so X11 receives _NET_ACTIVE_WINDOW too.
                    if let Some(surface) = native.window() {
                        surface.focus(0);
                    }
                }
                #[cfg(windows)]
                if let Ok(hwnd) = window.hwnd() {
                    use windows_sys::Win32::UI::WindowsAndMessaging::{
                        IsIconic, SetForegroundWindow, ShowWindow, SW_RESTORE, SW_SHOW,
                    };
                    // The summon is an explicit user action. Preserve maximized
                    // geometry when restoring a window that is merely hidden.
                    unsafe {
                        if IsIconic(hwnd.0.cast()) != 0 {
                            ShowWindow(hwnd.0.cast(), SW_RESTORE);
                        }
                        ShowWindow(hwnd.0.cast(), SW_SHOW);
                        SetForegroundWindow(hwnd.0.cast());
                    }
                }
            });
        }
    }
}

pub(crate) fn summon(app: &tauri::AppHandle) {
    if super::reset_blocks_search(app) {
        foreground(app);
        return;
    }
    app.state::<SearchShortcut>().pending.request();
    foreground(app);
    let _ = app.emit(SUMMON_EVENT, ());
}

/// Tauri's cold-link parser ignores mixed arguments. Retain their explicit destination until ready.
pub(crate) fn summon_deep_link(app: &tauri::AppHandle, url: String) {
    if super::reset_blocks_search(app) {
        foreground(app);
        return;
    }
    app.state::<SearchShortcut>().pending.request_deep_link(url);
    foreground(app);
    let _ = app.emit(SUMMON_EVENT, ());
}

fn main_only(window: &tauri::WebviewWindow) -> Result<(), String> {
    if window.label() == "main" {
        Ok(())
    } else {
        Err("Search shortcut commands are available only to the main window.".into())
    }
}

#[tauri::command]
pub(crate) async fn search_shortcut_status(
    window: tauri::WebviewWindow,
    runtime: tauri::State<'_, SearchShortcut>,
) -> Result<ShortcutStatus, String> {
    main_only(&window)?;
    Ok(runtime
        .state
        .lock()
        .map_err(|error| error.to_string())?
        .status
        .clone())
}

#[tauri::command]
pub(crate) fn take_search_summon(
    window: tauri::WebviewWindow,
    runtime: tauri::State<'_, SearchShortcut>,
) -> Result<Option<SearchSummon>, String> {
    main_only(&window)?;
    Ok(runtime.pending.take())
}

#[tauri::command]
pub(crate) async fn set_search_shortcut_enabled(
    app: tauri::AppHandle,
    window: tauri::WebviewWindow,
    runtime: tauri::State<'_, SearchShortcut>,
    enabled: bool,
) -> Result<ShortcutStatus, String> {
    main_only(&window)?;
    // One mutex covers OS ownership and the file replacement, including overlapping IPC callers.
    let mut state = runtime.state.lock().map_err(|error| error.to_string())?;
    let result = apply_choice(&mut state, enabled, |register| {
        if register {
            app.global_shortcut().register(chord())
        } else {
            unregister_chord(app.global_shortcut())
        }
        .map_err(|error| error.to_string())
    });
    if !state.status.active {
        runtime.pending.held.store(false, Ordering::Release);
    }
    let _ = app.emit(STATUS_EVENT, state.status.clone());
    result
}

pub(crate) fn release(app: &tauri::AppHandle) {
    // Exit runs on the UI thread. Never wait on the preference lock: a toggle may be
    // holding it while waiting for its OS registration task on this same thread.
    if let Some(shortcuts) =
        app.try_state::<tauri_plugin_global_shortcut::GlobalShortcut<tauri::Wry>>()
    {
        let _ = unregister_chord(&shortcuts);
    }
}

fn unregister_chord(
    shortcuts: &tauri_plugin_global_shortcut::GlobalShortcut<tauri::Wry>,
) -> Result<(), tauri_plugin_global_shortcut::Error> {
    // global-hotkey 0.7's X11 ungrab requests are buffered. Its second
    // unregister performs a keyboard-mapping round trip on that SAME connection,
    // flushing the first ungrabs before returning. Repeating an X11 ungrab is
    // harmless; on Windows unregister once (UnregisterHotKey is not idempotent).
    #[cfg(target_os = "linux")]
    return shortcuts.unregister_multiple([chord(), chord()]);
    #[cfg(not(target_os = "linux"))]
    shortcuts.unregister(chord())
}

fn apply_choice(
    state: &mut ShortcutState,
    enabled: bool,
    mut registration: impl FnMut(bool) -> Result<(), String>,
) -> Result<ShortcutStatus, String> {
    let root = state.root.clone().ok_or(
        "The shortcut preference cannot be saved because the app-data folder is unavailable.",
    )?;
    let was_active = state.status.active;
    if !enabled && was_active {
        registration(false)
            .map_err(|error| format!("The shortcut could not be released: {error}"))?;
        state.status.active = false;
    }
    let bytes = serde_json::to_vec(&PreferenceFile {
        version: 1,
        enabled,
    })
    .map_err(|error| error.to_string())?;
    if let Err(error) = super::updater::write_bytes_atomic(&root.join(PREFERENCE_FILE), &bytes) {
        if was_active && !state.status.active {
            register(state, &mut registration);
        }
        state.status.revision += 1;
        return Err(format!("The shortcut preference was not saved: {error}"));
    }
    state.status.enabled = enabled;
    state.status.reason = None;
    if enabled && !state.status.active {
        register(state, &mut registration);
    }
    state.status.revision += 1;
    Ok(state.status.clone())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn sc7_registration_reason_contains_no_internal_error_text() {
        let mut state = ShortcutState {
            unavailable: None,
            ..ShortcutState::default()
        };
        register(&mut state, &mut |_| {
            Err("HotKey { mods: Modifiers(ALT | SHIFT), key: Space, id: 123 }".into())
        });
        assert!(!state.status.active);
        let reason = state.status.reason.unwrap();
        assert!(reason.contains("another application"));
        assert!(!reason.contains("HotKey"));
        assert!(!reason.contains("Modifiers"));
    }

    #[cfg(target_os = "linux")]
    fn native_probe(test: &str, check: impl FnOnce(&mut tauri::App)) {
        let test = format!("search_shortcut::tests::{test}");
        if std::env::var("JUSTSEARCH_SHORTCUT_PROBE").as_deref() != Ok(&test) {
            let output = std::process::Command::new("python3")
                .arg(concat!(
                    env!("CARGO_MANIFEST_DIR"),
                    "/tests/shortcut_x11_probe.py"
                ))
                .arg(&test)
                .arg(std::env::current_exe().unwrap())
                .env_remove("DISPLAY")
                .env_remove("WAYLAND_DISPLAY")
                .env("CUDA_VISIBLE_DEVICES", "-1")
                .output()
                .expect("Python 3 and Xvfb are required for the native shortcut probes");
            let stderr = String::from_utf8_lossy(&output.stderr);
            // Surface a child's actual assertion before its libtest transcript.
            // Infrastructure failures keep their original non-assertion text.
            let failure = stderr
                .lines()
                .find(|line| line.starts_with("assertion failed:"))
                .unwrap_or("native probe failed");
            assert!(
                output.status.success(),
                "{failure}\n{}\n{stderr}",
                String::from_utf8_lossy(&output.stdout),
            );
            return;
        }
        let mut context = tauri::generate_context!();
        context.config_mut().app.windows.clear();
        let mut app = tauri::Builder::default()
            .any_thread()
            .manage(SearchShortcut::default())
            .build(context)
            .unwrap();
        tauri::WebviewWindowBuilder::new(
            &app,
            "main",
            tauri::WebviewUrl::External("about:blank".parse().unwrap()),
        )
        .visible(false)
        .focused(false)
        .build()
        .unwrap();
        pump(&mut app);
        check(&mut app);
        app.cleanup_before_exit();
    }

    #[cfg(target_os = "linux")]
    #[allow(deprecated)] // Tauri's bounded event-loop pump is for this native harness only.
    fn pump(app: &mut tauri::App) {
        for _ in 0..30 {
            app.run_iteration(|_, _| {});
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn sc8_x11_off_releases_all_lock_variants_to_another_client() {
        native_probe(
            "sc8_x11_off_releases_all_lock_variants_to_another_client",
            |app| {
                initialize(app.handle());
                let runtime = app.state::<SearchShortcut>();
                assert!(runtime.state.lock().unwrap().status.active);
                let probe = || {
                    std::process::Command::new("python3")
                        .args([
                            concat!(env!("CARGO_MANIFEST_DIR"), "/tests/shortcut_x11_probe.py"),
                            "grab",
                        ])
                        .output()
                        .unwrap()
                };
                assert!(!probe().status.success(), "active chord must be owned");
                let off = tauri::async_runtime::block_on(set_search_shortcut_enabled(
                    app.handle().clone(),
                    app.get_webview_window("main").unwrap(),
                    runtime,
                    false,
                ))
                .unwrap();
                assert!(!off.enabled && !off.active);
                assert!(probe().status.success());
                let on = tauri::async_runtime::block_on(set_search_shortcut_enabled(
                    app.handle().clone(),
                    app.get_webview_window("main").unwrap(),
                    app.state::<SearchShortcut>(),
                    true,
                ))
                .unwrap();
                assert!(on.enabled && on.active);
                assert!(
                    !probe().status.success(),
                    "turning on must own the chord again"
                );
                release(app.handle());
                assert!(
                    probe().status.success(),
                    "normal exit must release the chord too"
                );
            },
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn sc1_sc9_x11_summon_requests_activation_from_never_shown_and_hidden() {
        native_probe(
            "sc1_sc9_x11_summon_requests_activation_from_never_shown_and_hidden",
            |app| {
                let activations =
                    PathBuf::from(std::env::var_os("JUSTSEARCH_SHORTCUT_PROBE_DIR").unwrap())
                        .join("activations");
                let count = || {
                    std::fs::read_to_string(&activations)
                        .unwrap_or_default()
                        .parse::<u32>()
                        .unwrap_or(0)
                };
                for request in 0..2 {
                    let before = count();
                    if request == 0 {
                        summon(app.handle());
                    } else {
                        summon_deep_link(
                            app.handle(),
                            "justsearch://surface/core.help-surface".into(),
                        );
                    }
                    pump(app);
                    assert!(app
                        .get_webview_window("main")
                        .unwrap()
                        .is_visible()
                        .unwrap());
                    assert!(count() > before);
                    app.get_webview_window("main").unwrap().hide().unwrap();
                    pump(app);
                }
            },
        );
    }

    #[test]
    fn sc8_reads_off_before_registration_and_preserves_future_or_corrupt_bytes() {
        let root = tempdir().unwrap();
        assert_eq!(read_preference(root.path()), Preference::Choice(true));
        let path = root.path().join("search-shortcut.v1.json");
        std::fs::write(&path, br#"{"version":1,"enabled":false}"#).unwrap();
        assert_eq!(read_preference(root.path()), Preference::Choice(false));
        for bytes in [
            br#"{"version":99,"enabled":false}"#.as_slice(),
            b"broken json",
        ] {
            std::fs::write(&path, bytes).unwrap();
            assert!(matches!(
                read_preference(root.path()),
                Preference::Unreadable(_)
            ));
            assert_eq!(std::fs::read(&path).unwrap(), bytes);
        }
        std::fs::remove_file(&path).unwrap();
        std::fs::create_dir(&path).unwrap();
        assert!(matches!(
            read_preference(root.path()),
            Preference::Unreadable(_)
        ));
    }

    #[test]
    fn sc9_focus_flag_beats_minimized_and_deep_links_keep_their_destination() {
        let args = |values: &[&str]| values.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        let focus = LaunchRequest::from_args(&args(&["app", "--minimized", "--focus-search"]));
        assert!(focus.show_window && focus.focus_search);
        let link = LaunchRequest::from_args(&args(&[
            "app",
            "--minimized",
            "--focus-search",
            "justsearch://surface/core.help-surface",
        ]));
        assert!(link.show_window);
        assert!(!link.focus_search);
        assert_eq!(link.deep_links, ["justsearch://surface/core.help-surface"]);
    }

    #[test]
    fn sn1_sn3_plain_launch_and_minimized_arguments_keep_their_behavior() {
        let plain = LaunchRequest::from_args(&["app".into()]);
        assert!(plain.show_window);
        assert!(!plain.focus_search);
        let minimized = LaunchRequest::from_args(&["app".into(), "--minimized".into()]);
        assert!(!minimized.show_window);
        assert!(!minimized.focus_search);
    }

    #[test]
    fn sc5_sc6_pending_requests_coalesce_and_a_consumed_request_cannot_replay() {
        let pending = PendingSummon::default();
        assert_eq!(pending.take(), None);
        pending.request();
        pending.request();
        assert_eq!(pending.take(), Some(SearchSummon::FocusSearch));
        assert_eq!(pending.take(), None);
        assert!(pending.chord(true));
        assert!(!pending.chord(true));
        assert!(!pending.chord(false));
        assert!(pending.chord(true));
    }

    #[test]
    fn sc10_wayland_detection_fails_closed_even_with_xwayland_display() {
        assert!(wayland_session(Some("wayland"), Some("wayland-0")));
        assert!(wayland_session(Some("WAYLAND"), None));
        assert!(wayland_session(None, Some("wayland-1")));
        assert!(!wayland_session(Some("x11"), None));
        assert!(!wayland_session(None, Some("")));
    }

    #[test]
    fn sc7_sc8_registration_conflict_and_off_switch_report_real_state() {
        let root = tempdir().unwrap();
        let mut state = ShortcutState {
            root: Some(root.path().to_owned()),
            unavailable: None,
            ..ShortcutState::default()
        };
        state.status.enabled = false;
        state.status.reason = None;
        let conflict = apply_choice(&mut state, true, |_| {
            Err("already registered by another app".into())
        })
        .unwrap();
        assert!(conflict.enabled && !conflict.active);
        assert!(conflict.reason.as_deref().unwrap().contains("another app"));
        assert_eq!(read_preference(root.path()), Preference::Choice(true));
        let mut calls = Vec::new();
        let active = apply_choice(&mut state, true, |enabled| {
            calls.push(enabled);
            Ok(())
        })
        .unwrap();
        assert!(active.enabled && active.active);
        let off = apply_choice(&mut state, false, |enabled| {
            calls.push(enabled);
            Ok(())
        })
        .unwrap();
        assert!(!off.enabled && !off.active);
        assert_eq!(calls, [true, false]);
        assert_eq!(read_preference(root.path()), Preference::Choice(false));
    }

    #[test]
    fn sc8_failed_save_restores_the_choice_and_registration() {
        let root = tempdir().unwrap();
        let mut state = ShortcutState {
            root: Some(root.path().to_owned()),
            unavailable: None,
            ..ShortcutState::default()
        };
        state.status.enabled = true;
        state.status.active = true;
        state.status.reason = None;
        std::fs::create_dir(root.path().join(PREFERENCE_FILE)).unwrap();
        let mut calls = Vec::new();
        assert!(apply_choice(&mut state, false, |enabled| {
            calls.push(enabled);
            Ok(())
        })
        .is_err());
        assert!(state.status.enabled && state.status.active);
        assert_eq!(calls, [false, true]);
        assert!(root.path().join(PREFERENCE_FILE).is_dir());
    }

    #[test]
    fn sc8_unavailable_sessions_persist_the_choice_without_attempting_registration() {
        let root = tempdir().unwrap();
        let mut state = ShortcutState {
            root: Some(root.path().to_owned()),
            unavailable: Some("Not available in this desktop session.".into()),
            ..ShortcutState::default()
        };
        let status =
            apply_choice(&mut state, true, |_| panic!("must not register on Wayland")).unwrap();
        assert!(status.enabled && !status.active);
        assert!(root.path().join(PREFERENCE_FILE).is_file());
        assert_eq!(read_preference(root.path()), Preference::Choice(true));
    }
}
