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
            Err(error) => {
                state.status.reason = Some(format!("The desktop refused Alt + Shift + Space (another application may be using it): {error}"));
            }
        }
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
            .map(|error| format!("The desktop shortcut service could not start: {error}"))
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
    }
}

pub(crate) fn summon(app: &tauri::AppHandle) {
    app.state::<SearchShortcut>().pending.request();
    foreground(app);
    let _ = app.emit(SUMMON_EVENT, ());
}

/// Tauri's cold-link parser ignores mixed arguments. Retain their explicit destination until ready.
pub(crate) fn summon_deep_link(app: &tauri::AppHandle, url: String) {
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
            app.global_shortcut().unregister(chord())
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
        let _ = shortcuts.unregister(chord());
    }
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
