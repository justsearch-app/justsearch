//! Rust implementation of the `contracts/platform-paths/spec.v1.json` contract
//! (tempdoc 501 §3.6).
//!
//! This module is the single Rust source of truth for resolving the JustSearch
//! data directory. Mirrors the precedence + platform-default logic from the
//! reference Java implementation
//! (`modules/configuration/.../PlatformPaths.java`) and the Node twin
//! (`scripts/lib/platform-paths.mjs`).
//!
//! The shell does not run inside a JVM, so sysprop-class sources from the
//! contract are skipped here (same convention as the Node twin). Env-var +
//! platform-default branches are authoritative.

use std::env;
use std::path::PathBuf;

/// Replace literal `${user.home}` placeholders. Returns the input unchanged if
/// no placeholder is present. Mirrors PlatformPaths.expandUserHomePlaceholders.
pub fn expand_user_home_placeholders(raw: &str) -> String {
    if !raw.contains("${user.home}") {
        return raw.to_string();
    }
    if let Some(home) = user_home() {
        return raw.replace("${user.home}", &home);
    }
    raw.to_string()
}

fn user_home() -> Option<String> {
    // dirs-style helper without bringing in the dirs crate. On Windows
    // USERPROFILE / HOMEDRIVE+HOMEPATH; elsewhere HOME.
    if cfg!(target_os = "windows") {
        if let Ok(p) = env::var("USERPROFILE") {
            if !p.is_empty() {
                return Some(p);
            }
        }
        let drive = env::var("HOMEDRIVE").ok();
        let dir = env::var("HOMEPATH").ok();
        if let (Some(d), Some(h)) = (drive, dir) {
            if !d.is_empty() && !h.is_empty() {
                return Some(format!("{d}{h}"));
            }
        }
    }
    env::var("HOME").ok().filter(|s| !s.is_empty())
}

/// Resolve the data directory per the shared contract.
///
/// Precedence:
///   1. `JUSTSEARCH_DATA_DIR` env var (expands `${user.home}` if present)
///   2. Platform default:
///        windows: `%LOCALAPPDATA%/JustSearch` (or `userHome/AppData/Local/JustSearch`)
///        macos:   `userHome/Library/Application Support/JustSearch`
///        linux:   `userHome/.justsearch`
pub fn resolve_data_dir() -> PathBuf {
    explicit_data_dir().unwrap_or_else(platform_default)
}

/// The shell uses only the explicit contract override, preserving its historical Tauri default.
pub fn explicit_data_dir() -> Option<PathBuf> {
    if let Ok(env_val) = env::var("JUSTSEARCH_DATA_DIR") {
        let trimmed = env_val.trim();
        if !trimmed.is_empty() {
            return Some(PathBuf::from(expand_user_home_placeholders(trimmed)));
        }
    }
    None
}

fn platform_default() -> PathBuf {
    if cfg!(target_os = "windows") {
        if let Ok(la) = env::var("LOCALAPPDATA") {
            if !la.is_empty() {
                return PathBuf::from(la).join("JustSearch");
            }
        }
        let home = user_home().unwrap_or_else(|| "C:\\".to_string());
        return PathBuf::from(home)
            .join("AppData")
            .join("Local")
            .join("JustSearch");
    }
    if cfg!(target_os = "macos") {
        let home = user_home().unwrap_or_else(|| "/".to_string());
        return PathBuf::from(home)
            .join("Library")
            .join("Application Support")
            .join("JustSearch");
    }
    let home = user_home().unwrap_or_else(|| "/".to_string());
    PathBuf::from(home).join(".justsearch")
}

/// Filesystem path the producer writes its manifest to.
pub fn resolve_manifest_path() -> PathBuf {
    resolve_data_dir().join("runtime").join("manifest.json")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use std::fs;
    use std::path::PathBuf;
    use std::sync::Mutex;

    // Tempdoc 501 §3.6: contract-spec fixtures mutate process-global env vars
    // hermetically. Cargo runs tests in parallel by default; serialize all
    // env-touching tests through one mutex so they don't observe each other's
    // overrides. The lock is the only state shared across these tests.
    static ENV_LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn expand_user_home_returns_input_when_no_placeholder() {
        let _g = ENV_LOCK.lock().unwrap();
        assert_eq!(expand_user_home_placeholders("/var/lib"), "/var/lib");
    }

    /// Tempdoc 501 §3.6: drive the cross-language contract spec
    /// (`contracts/platform-paths/spec.v1.json`) through the Rust resolver.
    /// Fixtures marked `runners: [...]` that omit "rust" are skipped, mirroring
    /// the Java + Node twin tests.
    #[test]
    fn resolve_data_dir_matches_contract_spec() {
        let _g = ENV_LOCK.lock().unwrap();
        let spec_path = find_spec_path().expect("spec.v1.json not found");
        let spec_text = fs::read_to_string(&spec_path).expect("read spec");
        let spec: serde_json::Value = serde_json::from_str(&spec_text).expect("parse spec");
        let fixtures = spec
            .get("fixtures")
            .and_then(|f| f.as_array())
            .expect("fixtures array");

        let mut ran = 0;
        let mut failures: Vec<String> = Vec::new();
        for fixture in fixtures {
            let name = fixture
                .get("name")
                .and_then(|v| v.as_str())
                .unwrap_or("<unnamed>");

            // Filter by runners marker; rust-only/missing-marker fixtures run.
            if let Some(runners) = fixture.get("runners").and_then(|r| r.as_array()) {
                if !runners.iter().any(|r| r.as_str() == Some("rust")) {
                    continue;
                }
            }

            // Skip fixtures targeting platforms other than the current host —
            // resolve_data_dir branches on cfg!(target_os), which can't be
            // overridden at runtime. Linux-fixtures run on Linux hosts; etc.
            let platform = fixture.get("platform").and_then(|v| v.as_str()).unwrap_or("");
            if !matches_current_platform(platform) {
                continue;
            }

            // Hermetic env override under the global mutex.
            let env = fixture
                .get("env")
                .and_then(|v| v.as_object())
                .map(|m| {
                    m.iter()
                        .map(|(k, v)| (k.clone(), v.as_str().unwrap_or("").to_string()))
                        .collect::<HashMap<_, _>>()
                })
                .unwrap_or_default();
            let user_home = fixture
                .get("userHome")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let expected = fixture
                .get("expected")
                .and_then(|v| v.as_str())
                .unwrap_or("");

            let actual = with_env_overrides(&env, user_home, || resolve_data_dir());
            let actual_norm = actual.to_string_lossy().replace('\\', "/");
            let expected_norm = expected.replace('\\', "/");

            if actual_norm != expected_norm {
                failures.push(format!(
                    "{name}: expected {expected_norm}, got {actual_norm}"
                ));
            }
            ran += 1;
        }

        assert!(
            failures.is_empty(),
            "{} of {} rust fixtures failed:\n{}",
            failures.len(),
            ran,
            failures.join("\n"),
        );
        assert!(ran > 0, "no rust-targeted fixtures executed — harness regression");
    }

    #[test]
    fn explicit_override_expands_home_and_preserves_spaces_and_unicode() {
        let _g = ENV_LOCK.lock().unwrap();
        let overrides = HashMap::from([("JUSTSEARCH_DATA_DIR".into(), "  ${user.home}/scratch space/検索  ".into())]);
        let actual = with_env_overrides(&overrides, "/test-home", explicit_data_dir);
        assert_eq!(actual, Some(PathBuf::from("/test-home/scratch space/検索")));
        let empty = HashMap::from([("JUSTSEARCH_DATA_DIR".into(), "  ".into())]);
        assert_eq!(with_env_overrides(&empty, "/test-home", explicit_data_dir), None);
        let absent = HashMap::from([("JUSTSEARCH_DATA_DIR".into(), String::new())]);
        assert_eq!(with_env_overrides(&absent, "/test-home", explicit_data_dir), None);
    }

    fn matches_current_platform(p: &str) -> bool {
        match p {
            "linux" => cfg!(target_os = "linux"),
            "macos" => cfg!(target_os = "macos"),
            "windows" => cfg!(target_os = "windows"),
            "" => true,
            _ => false,
        }
    }

    /// Save+set+restore the affected env vars and HOME under a closure.
    /// Always runs under `ENV_LOCK` so cargo's parallel test runner can't
    /// race two fixtures' env-touching scopes.
    fn with_env_overrides<F, R>(
        env: &HashMap<String, String>,
        user_home: &str,
        f: F,
    ) -> R
    where
        F: FnOnce() -> R,
    {
        let touched_keys: Vec<&str> = env
            .keys()
            .map(|s| s.as_str())
            .chain(std::iter::once("HOME"))
            .chain(std::iter::once("USERPROFILE"))
            .chain(std::iter::once("LOCALAPPDATA"))
            .collect::<std::collections::BTreeSet<_>>()
            .into_iter()
            .collect();

        let saved: HashMap<&str, Option<String>> = touched_keys
            .iter()
            .map(|k| (*k, std::env::var(*k).ok()))
            .collect();

        for k in &touched_keys {
            std::env::remove_var(*k);
        }
        if !user_home.is_empty() {
            std::env::set_var("HOME", user_home);
            // Windows-style fallback chain too — resolve_data_dir checks
            // USERPROFILE first.
            std::env::set_var("USERPROFILE", user_home);
        }
        for (k, v) in env {
            std::env::set_var(k, v);
        }

        let result = f();

        // Restore.
        for k in &touched_keys {
            match saved.get(*k).and_then(|v| v.clone()) {
                Some(v) => std::env::set_var(*k, v),
                None => std::env::remove_var(*k),
            }
        }
        result
    }

    fn find_spec_path() -> Option<PathBuf> {
        let mut cur = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        for _ in 0..8 {
            let candidate = cur.join("contracts/platform-paths/spec.v1.json");
            if candidate.is_file() {
                return Some(candidate);
            }
            let parent = cur.parent()?.to_path_buf();
            cur = parent;
        }
        None
    }
}
