// SPDX-License-Identifier: Apache-2.0
//! SW8: the in-page window controls gain exactly the four window permissions they need, and only
//! the main window holds them. Every capability file and the configuration are read as shipped.

use serde_json::Value;
use std::collections::BTreeSet;
use std::path::Path;

const WINDOW_CONTROLS: [&str; 4] = [
    "core:window:allow-close",
    "core:window:allow-minimize",
    "core:window:allow-start-dragging",
    "core:window:allow-toggle-maximize",
];

/// Window permissions the main capability held before the window layer existed.
const EARLIER_WINDOW: [&str; 4] = [
    "core:window:allow-set-focus",
    "core:window:allow-set-visible-on-all-workspaces",
    "core:window:allow-show",
    "core:window:default",
];

fn identifiers(capability: &Value) -> Vec<String> {
    capability["permissions"]
        .as_array()
        .expect("capability permissions are a list")
        .iter()
        .map(|permission| {
            permission
                .as_str()
                .or_else(|| permission["identifier"].as_str())
                .expect("permission has an identifier")
                .to_string()
        })
        .collect()
}

#[test]
fn sw8_window_controls_are_granted_to_the_main_window_only() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let config: Value =
        serde_json::from_str(&std::fs::read_to_string(root.join("tauri.conf.json")).unwrap())
            .unwrap();
    // Inline capabilities in the configuration would bypass the files checked below.
    assert!(config["app"]["security"]["capabilities"].is_null());

    let mut holders = Vec::new();
    let mut files = 0;
    for entry in std::fs::read_dir(root.join("capabilities")).unwrap() {
        let path = entry.unwrap().path();
        files += 1;
        assert_eq!(
            path.extension().and_then(|e| e.to_str()),
            Some("json"),
            "{path:?}"
        );
        let capability: Value =
            serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        let granted = identifiers(&capability);
        let window: BTreeSet<&str> = granted
            .iter()
            .map(String::as_str)
            .filter(|id| id.starts_with("core:window:"))
            .collect();
        if window.iter().any(|id| WINDOW_CONTROLS.contains(id)) {
            holders.push(capability["identifier"].as_str().unwrap().to_string());
            assert_eq!(capability["windows"], serde_json::json!(["main"]), "{path:?}");
            for scope in ["webviews", "remote", "platforms"] {
                assert!(capability[scope].is_null(), "{path:?} widens {scope}");
            }
            let expected: BTreeSet<&str> =
                WINDOW_CONTROLS.iter().chain(EARLIER_WINDOW.iter()).copied().collect();
            assert_eq!(window, expected, "{path:?}");
        }
    }
    assert_eq!(files, 1, "a new capability file must be reviewed here");
    assert_eq!(holders, ["default"]);
}
