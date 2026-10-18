fn main() {
    // A source-only check/test has no sidecar yet. Release builds must stage the real payload
    // through beforeBuildCommand; never manufacture empty installer resources.
    println!("cargo:rerun-if-changed=resources/headless");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("linux")
        && std::env::var("PROFILE").as_deref() == Ok("debug")
        && !std::path::Path::new("resources/headless/ui-headless.jar").is_file()
    {
        let mut config: serde_json::Value = std::env::var("TAURI_CONFIG")
            .ok()
            .map(|raw| serde_json::from_str(&raw).expect("valid TAURI_CONFIG"))
            .unwrap_or_else(|| serde_json::json!({}));
        config["bundle"]["resources"] = serde_json::json!([]);
        let config = config.to_string();
        std::env::set_var("TAURI_CONFIG", &config);
        println!("cargo:rustc-env=TAURI_CONFIG={config}");
    }
    tauri_build::build()
}
