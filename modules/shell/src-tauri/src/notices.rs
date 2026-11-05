//! DE5: desktop notifications and the taskbar badge.
//!
//! The notification plugin's desktop path is fire-and-forget, so a click never reaches the app.
//! Delivery lives here because clicking must bring the app forward on the event's item. Which
//! events leave the app is decided in the frontend's notice policy, not here.

use std::{
    collections::HashMap,
    sync::Mutex,
    time::{Duration, Instant},
};

use tauri::{AppHandle, Emitter, State, WebviewWindow};

const ACTIVATED_EVENT: &str = "justsearch://notice-activated";
/// Every open window sees the same live event at nearly the same moment.
const CLAIM_WINDOW: Duration = Duration::from_secs(60);
/// Must equal OVERLAY_SIZE in the frontend's taskbarBadge.ts.
#[cfg(any(windows, test))]
const OVERLAY_SIZE: u32 = 32;

/// One notification per event id, across every window that reports it.
#[derive(Default)]
pub(crate) struct NoticeLedger(Mutex<HashMap<String, Instant>>);

impl NoticeLedger {
    fn claim(&self, id: &str, now: Instant) -> bool {
        let mut claims = self.0.lock().expect("notice ledger mutex poisoned");
        claims.retain(|_, at| now.duration_since(*at) < CLAIM_WINDOW);
        if claims.contains_key(id) {
            return false;
        }
        claims.insert(id.to_owned(), now);
        true
    }
}

#[cfg(any(windows, target_os = "linux"))]
fn activate(app: &AppHandle, id: &str) {
    crate::search_shortcut::foreground(app);
    let _ = app.emit(ACTIVATED_EVENT, id);
}

#[cfg(windows)]
fn deliver(app: AppHandle, id: String, title: String, body: String, sound: bool) {
    use tauri_winrt_notification::{Sound, Toast};
    // A dev build has no Start menu identity for Windows to attribute the toast to.
    let app_id = if tauri::is_dev() {
        Toast::POWERSHELL_APP_ID.to_owned()
    } else {
        app.config().identifier.clone()
    };
    let _ = Toast::new(&app_id)
        .title(&title)
        .text1(&body)
        .sound(sound.then_some(Sound::Default))
        .on_activated(move |_| {
            activate(&app, &id);
            Ok(())
        })
        .show();
}

#[cfg(target_os = "linux")]
fn deliver(app: AppHandle, id: String, title: String, body: String, sound: bool) {
    use notify_rust::{Hint, Notification};
    let mut notification = Notification::new();
    notification
        .summary(&title)
        .body(&body)
        .auto_icon()
        .action("default", "Open");
    if sound {
        notification.sound_name("message-new-instant");
    } else {
        notification.hint(Hint::SuppressSound(true));
    }
    if let Ok(handle) = notification.show() {
        handle.wait_for_action(|action| {
            if action == "default" {
                activate(&app, &id);
            }
        });
    }
}

/// Unshipped platforms keep the plugin's delivery, which cannot report a click.
#[cfg(not(any(windows, target_os = "linux")))]
fn deliver(app: AppHandle, _id: String, title: String, body: String, sound: bool) {
    use tauri_plugin_notification::NotificationExt;
    let mut builder = app.notification().builder().title(title).body(body);
    if sound {
        builder = builder.sound("default");
    }
    let _ = builder.show();
}

#[tauri::command]
pub(crate) fn show_desktop_notice(
    app: AppHandle,
    ledger: State<'_, NoticeLedger>,
    id: String,
    title: String,
    body: Option<String>,
    sound: bool,
) {
    if ledger.claim(&id, Instant::now()) {
        // Delivery blocks: on Linux for as long as the notification stays open.
        tauri::async_runtime::spawn_blocking(move || {
            deliver(app, id, title, body.unwrap_or_default(), sound);
        });
    }
}

#[cfg(any(not(windows), test))]
fn badge_value(count: u32) -> Option<i64> {
    (count > 0).then_some(i64::from(count))
}

#[cfg(any(windows, test))]
fn overlay_pixels(count: u32, overlay: Option<Vec<u8>>) -> Option<Vec<u8>> {
    let expected = (OVERLAY_SIZE * OVERLAY_SIZE * 4) as usize;
    overlay.filter(|pixels| count > 0 && pixels.len() == expected)
}

/// The needs-you count on the taskbar or dock; 0 clears it. Windows has no badge count, so it
/// shows the frontend's drawing as an overlay icon. A platform with neither ignores this.
#[tauri::command]
pub(crate) fn set_needs_you_badge(window: WebviewWindow, count: u32, overlay: Option<Vec<u8>>) {
    #[cfg(windows)]
    {
        let icon = overlay_pixels(count, overlay)
            .map(|pixels| tauri::image::Image::new_owned(pixels, OVERLAY_SIZE, OVERLAY_SIZE));
        let _ = window.set_overlay_icon(icon);
    }
    #[cfg(not(windows))]
    {
        let _ = overlay;
        let _ = window.set_badge_count(badge_value(count));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_event_reported_by_two_windows_shows_once() {
        let ledger = NoticeLedger::default();
        let now = Instant::now();
        assert!(ledger.claim("authorization:a", now));
        assert!(!ledger.claim("authorization:a", now + Duration::from_millis(5)));
    }

    #[test]
    fn different_events_each_show() {
        let ledger = NoticeLedger::default();
        let now = Instant::now();
        assert!(ledger.claim("authorization:a", now));
        assert!(ledger.claim("authorization:b", now));
    }

    #[test]
    fn a_claim_expires_so_later_work_can_notify_and_the_ledger_stays_small() {
        let ledger = NoticeLedger::default();
        let now = Instant::now();
        assert!(ledger.claim("finished:search", now));
        assert!(ledger.claim("finished:search", now + CLAIM_WINDOW));
        assert_eq!(ledger.0.lock().unwrap().len(), 1);
    }

    #[test]
    fn zero_clears_the_badge() {
        assert_eq!(badge_value(0), None);
        assert_eq!(badge_value(3), Some(3));
    }

    #[test]
    fn the_overlay_clears_at_zero_and_rejects_a_wrong_size() {
        let pixels = vec![0; (OVERLAY_SIZE * OVERLAY_SIZE * 4) as usize];
        assert_eq!(overlay_pixels(2, Some(pixels.clone())), Some(pixels.clone()));
        assert_eq!(overlay_pixels(0, Some(pixels)), None);
        assert_eq!(overlay_pixels(2, Some(vec![0; 4])), None);
        assert_eq!(overlay_pixels(2, None), None);
    }
}
