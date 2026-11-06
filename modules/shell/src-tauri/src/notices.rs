//! DE5: desktop notifications and the taskbar badge.
//!
//! The notification plugin's desktop path is fire-and-forget, so a click never reaches the app.
//! Delivery lives here because clicking must bring the app forward on the event's item, using the
//! activation token the desktop gives with the click where it gives one. Which events leave the app
//! is decided in the frontend's notice policy, not here.

use std::{collections::VecDeque, sync::Mutex};

use tauri::{AppHandle, Emitter, State, WebviewWindow};
#[cfg(target_os = "linux")]
use tauri::{Manager, UserAttentionType};

const ACTIVATED_EVENT: &str = "justsearch://notice-activated";
/// The freedesktop notification service: its bus name and interface are the same string.
#[cfg(target_os = "linux")]
const NOTIFICATIONS: &str = "org.freedesktop.Notifications";
#[cfg(target_os = "linux")]
const NOTIFICATIONS_PATH: &str = "/org/freedesktop/Notifications";
/// Event ids remembered. Ids are unique per event, so forgetting the oldest risks nothing unless a
/// window reports an event this many events late.
const CLAIM_LIMIT: usize = 500;
/// Must equal OVERLAY_SIZE in the frontend's taskbarBadge.ts.
#[cfg(any(windows, test))]
const OVERLAY_SIZE: u32 = 32;

/// One notification per event id, across every window that reports it.
#[derive(Default)]
pub(crate) struct NoticeLedger(Mutex<VecDeque<String>>);

impl NoticeLedger {
    fn claim(&self, id: &str) -> bool {
        let mut claims = self.0.lock().expect("notice ledger mutex poisoned");
        if claims.iter().any(|claimed| claimed == id) {
            return false;
        }
        if claims.len() == CLAIM_LIMIT {
            claims.pop_front();
        }
        claims.push_back(id.to_owned());
        true
    }
}

#[cfg(windows)]
fn activate(app: &AppHandle, id: &str) {
    crate::search_shortcut::foreground(app);
    let _ = app.emit(ACTIVATED_EVENT, id);
}

/// A desktop grants focus only to a request carrying the token of the person's click. Without one
/// the window may stay behind what they were using, so it asks for attention as well.
#[cfg(target_os = "linux")]
fn activate(app: &AppHandle, id: &str, activation_token: Option<String>) {
    let attention = activation_token.is_none();
    crate::search_shortcut::foreground_with_token(app, activation_token);
    if attention {
        if let Some(window) = app.get_webview_window("main") {
            let _ = window.request_user_attention(Some(UserAttentionType::Informational));
        }
    }
    let _ = app.emit(ACTIVATED_EVENT, id);
}

/// Hand a click's token to the toolkit, for the present that follows it to use. Main thread only.
#[cfg(target_os = "linux")]
pub(crate) fn use_activation_token(window: &gtk::ApplicationWindow, token: &str) {
    use gtk::{
        glib::{object::ObjectExt, translate::ToGlibPtr},
        prelude::{GtkWindowExt, WidgetExt},
    };
    let display = WidgetExt::display(window);
    if display.type_().name() != "GdkWaylandDisplay" {
        // On X11 the token is a startup id, which also carries the click's timestamp.
        window.set_startup_id(token);
    } else if let Ok(token) = std::ffi::CString::new(token) {
        let display: *mut gtk::gdk::ffi::GdkDisplay = display.to_glib_none().0;
        // SAFETY: the display is a live GdkWaylandDisplay, checked above, and GDK copies the id.
        unsafe {
            gdk_wayland_sys::gdk_wayland_display_set_startup_notification_id(
                display.cast(),
                token.as_ptr(),
            );
        }
    }
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

/// A click on a notification, with the desktop's permission to take focus when it gave one.
#[cfg(target_os = "linux")]
#[derive(Debug, PartialEq)]
struct Click {
    activation_token: Option<String>,
}

/// Show one notification and wait until it is clicked or closed. notify-rust keeps its connection
/// private and listens for no activation token, and servers may address that signal only to the
/// connection that asked for the notification, so this speaks the protocol itself.
#[cfg(target_os = "linux")]
fn show_until_answered(
    connection: &zbus::blocking::Connection,
    title: &str,
    body: &str,
    sound: bool,
) -> zbus::Result<Option<Click>> {
    use std::collections::HashMap;
    use zbus::{message::Type, zvariant::Value, MatchRule};

    // Subscribed first: a server may answer as soon as the notification is up.
    let rule = MatchRule::builder()
        .msg_type(Type::Signal)
        .interface(NOTIFICATIONS)?
        .build();
    let signals = zbus::blocking::MessageIterator::for_match_rule(rule, connection, None)?;

    let app_name = std::env::current_exe()
        .ok()
        .and_then(|path| Some(path.file_name()?.to_str()?.to_owned()))
        .unwrap_or_default();
    let hints: HashMap<&str, Value<'_>> = if sound {
        HashMap::from([("sound-name", Value::from("message-new-instant"))])
    } else {
        HashMap::from([("suppress-sound", Value::from(true))])
    };
    let id: u32 = connection
        .call_method(
            Some(NOTIFICATIONS),
            NOTIFICATIONS_PATH,
            Some(NOTIFICATIONS),
            "Notify",
            &(
                &app_name,
                0u32,
                &app_name,
                title,
                body,
                vec!["default", "Open"],
                hints,
                -1i32,
            ),
        )?
        .body()
        .deserialize()?;

    let mut activation_token = None;
    for signal in signals {
        let signal = signal?;
        let header = signal.header();
        let body = signal.body();
        match header.member().map(|member| member.as_str()) {
            // The specification sends the token before the action it belongs to.
            Some("ActivationToken") => {
                if let Ok((notification, token)) = body.deserialize::<(u32, String)>() {
                    if notification == id {
                        activation_token = Some(token);
                    }
                }
            }
            Some("ActionInvoked") => {
                if let Ok((notification, action)) = body.deserialize::<(u32, String)>() {
                    if notification == id {
                        return Ok((action == "default").then_some(Click { activation_token }));
                    }
                }
            }
            Some("NotificationClosed") => {
                if matches!(body.deserialize::<(u32, u32)>(), Ok((notification, _)) if notification == id)
                {
                    return Ok(None);
                }
            }
            _ => {}
        }
    }
    Ok(None)
}

#[cfg(target_os = "linux")]
fn deliver(app: AppHandle, id: String, title: String, body: String, sound: bool) {
    let answer = zbus::blocking::Connection::session()
        .and_then(|connection| show_until_answered(&connection, &title, &body, sound));
    if let Ok(Some(click)) = answer {
        activate(&app, &id, click.activation_token);
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
    if ledger.claim(&id) {
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
        assert!(ledger.claim("authorization:a"));
        assert!(!ledger.claim("authorization:a"));
    }

    #[test]
    fn different_events_each_show() {
        let ledger = NoticeLedger::default();
        assert!(ledger.claim("authorization:a"));
        assert!(ledger.claim("authorization:b"));
    }

    #[test]
    fn two_failures_of_the_same_kind_are_two_events() {
        let ledger = NoticeLedger::default();
        assert!(ledger.claim("AI install failed@1000"));
        assert!(ledger.claim("AI install failed@2000"));
    }

    #[test]
    fn an_event_never_shows_twice_while_the_ledger_remembers_it() {
        let ledger = NoticeLedger::default();
        assert!(ledger.claim("operation:a"));
        for other in 1..CLAIM_LIMIT {
            assert!(ledger.claim(&format!("operation:{other}")));
            assert!(!ledger.claim("operation:a"));
        }
    }

    #[test]
    fn the_ledger_stays_small_by_forgetting_the_oldest_event() {
        let ledger = NoticeLedger::default();
        for event in 0..=CLAIM_LIMIT {
            assert!(ledger.claim(&format!("operation:{event}")));
        }
        assert_eq!(ledger.0.lock().unwrap().len(), CLAIM_LIMIT);
        assert!(ledger.claim("operation:0"));
    }

    /// Runs `show_until_answered` against a notification server on a private connection, which
    /// answers Notify with id 7 and then sends `signals`. Returns the answer and the hints it saw.
    #[cfg(target_os = "linux")]
    fn answered_by(
        signals: Vec<(&'static str, Signal)>,
        sound: bool,
    ) -> (Option<Click>, Vec<String>) {
        use std::{collections::HashMap, os::unix::net::UnixStream, thread};
        use zbus::{
            blocking::{connection::Builder, MessageIterator},
            zvariant::OwnedValue,
            Guid,
        };

        let (client, server) = UnixStream::pair().unwrap();
        let guid = Guid::generate();
        let server = thread::spawn(move || {
            let connection = Builder::unix_stream(server)
                .server(guid)
                .unwrap()
                .p2p()
                .build()
                .unwrap();
            let call = MessageIterator::from(&connection)
                .find_map(|message| {
                    let message = message.unwrap();
                    let is_notify = message.header().member().is_some_and(|m| m == "Notify");
                    is_notify.then_some(message)
                })
                .unwrap();
            type Notify = (
                String,
                u32,
                String,
                String,
                String,
                Vec<String>,
                HashMap<String, OwnedValue>,
                i32,
            );
            let hints = call.body().deserialize::<Notify>().unwrap().6;
            connection.reply(&call.header(), &(7u32,)).unwrap();
            for (name, signal) in signals {
                let path = NOTIFICATIONS_PATH;
                match signal {
                    Signal::Text(id, text) => connection
                        .emit_signal(None::<()>, path, NOTIFICATIONS, name, &(id, text))
                        .unwrap(),
                    Signal::Closed(id) => connection
                        .emit_signal(None::<()>, path, NOTIFICATIONS, name, &(id, 2u32))
                        .unwrap(),
                }
            }
            // Held until the client has its answer, so every signal reaches it.
            (connection, hints.into_keys().collect::<Vec<_>>())
        });
        let client = Builder::unix_stream(client).p2p().build().unwrap();
        let answer = show_until_answered(&client, "Title", "Body", sound).unwrap();
        (answer, server.join().unwrap().1)
    }

    #[cfg(target_os = "linux")]
    enum Signal {
        Text(u32, &'static str),
        Closed(u32),
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn a_click_carries_the_activation_token_sent_before_it() {
        let (click, _) = answered_by(
            vec![
                ("ActivationToken", Signal::Text(7, "token-from-the-server")),
                ("ActionInvoked", Signal::Text(7, "default")),
            ],
            false,
        );
        assert_eq!(
            click,
            Some(Click {
                activation_token: Some("token-from-the-server".to_owned())
            })
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn a_click_from_a_server_without_tokens_has_none_so_the_window_asks_for_attention() {
        let (click, _) = answered_by(vec![("ActionInvoked", Signal::Text(7, "default"))], false);
        assert_eq!(
            click,
            Some(Click {
                activation_token: None
            })
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn signals_about_another_notification_are_not_this_click() {
        let (click, _) = answered_by(
            vec![
                ("ActivationToken", Signal::Text(8, "someone-elses-token")),
                ("ActionInvoked", Signal::Text(8, "default")),
                ("NotificationClosed", Signal::Closed(8)),
                ("ActionInvoked", Signal::Text(7, "default")),
            ],
            false,
        );
        assert_eq!(
            click,
            Some(Click {
                activation_token: None
            })
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn closing_or_another_action_is_not_a_click() {
        let closed = vec![("NotificationClosed", Signal::Closed(7))];
        assert_eq!(answered_by(closed, false).0, None);
        let other = vec![("ActionInvoked", Signal::Text(7, "snooze"))];
        assert_eq!(answered_by(other, false).0, None);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn the_notification_asks_for_the_platform_sound_only_with_sound_on() {
        let closed = || vec![("NotificationClosed", Signal::Closed(7))];
        assert_eq!(answered_by(closed(), true).1, ["sound-name"]);
        assert_eq!(answered_by(closed(), false).1, ["suppress-sound"]);
    }

    #[test]
    fn zero_clears_the_badge() {
        assert_eq!(badge_value(0), None);
        assert_eq!(badge_value(3), Some(3));
    }

    #[test]
    fn the_overlay_clears_at_zero_and_rejects_a_wrong_size() {
        let pixels = vec![0; (OVERLAY_SIZE * OVERLAY_SIZE * 4) as usize];
        assert_eq!(
            overlay_pixels(2, Some(pixels.clone())),
            Some(pixels.clone())
        );
        assert_eq!(overlay_pixels(0, Some(pixels)), None);
        assert_eq!(overlay_pixels(2, Some(vec![0; 4])), None);
        assert_eq!(overlay_pixels(2, None), None);
    }
}
