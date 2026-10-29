//! Tempdoc 805 G.1 — the shell's backend binding and the provenance rule that governs it.
//!
//! Pure decision logic: no locks, no Tauri, no process handles. `BackendState` (lib.rs) owns the
//! `Mutex<Binding>` and the notify plumbing and calls into here. Keeping the rule pure is what
//! makes it unit-testable without linking the Tauri runtime into the test binary (the same
//! constraint that keeps `TrayIcon` out of `BackendState`).

use std::fs;
use std::path::Path;

/// The shell's backend binding — ONE record, replaced atomically.
///
/// Port and token are per-boot facts of ONE Head incarnation, identified by its `instanceId`
/// (a fresh UUID minted on every Head start). Keeping them as independent first-write-wins fields
/// let a restart's port land beside the previous boot's token (R11-F2: every mutating call 401s
/// forever, with no recovery). They live or die together.
#[derive(Default, Clone, Debug, PartialEq, Eq)]
pub(crate) struct Binding {
    pub(crate) instance_id: Option<String>,
    pub(crate) port: Option<u16>,
    pub(crate) token: Option<String>,
}

/// What an accepted observation changed, so the caller can fire the right notifies.
#[derive(Default, Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct BindingChange {
    /// A DIFFERENT instance succeeded a known one — the caller's restart signal. Establishing the
    /// first instance is not a restart.
    pub(crate) restarted: bool,
    pub(crate) port_available: bool,
    pub(crate) token_available: bool,
}

#[derive(Default)]
pub(crate) struct ManifestFields {
    pub(crate) api_port: Option<u16>,
    pub(crate) session_token: Option<String>,
    /// Tempdoc 501 Phase 17: top-level `lifecycle` field used to drive the tray tooltip.
    pub(crate) lifecycle: Option<String>,
    /// Tempdoc 637 #1: top-level per-boot `instanceId` — its change signals a backend restart.
    pub(crate) instance_id: Option<String>,
    /// Tempdoc 805 G.1: top-level `pid` of the process that published this manifest. When the
    /// shell spawned the child, a manifest naming a different pid is residue from another boot.
    pub(crate) pid: Option<u32>,
    /// Publication identity timestamp, checked against the shell spawn boundary.
    pub(crate) started_at: Option<u64>,
}

/// Apply a manifest observation to `binding` under the provenance rule.
///
/// A manifest is the producer's self-published identity, but the file outlives the process that
/// wrote it, so it can be residue from a previous boot. It is trusted only when
///   * it carries an `instanceId` (every v0.1.0+ manifest does; absence is residue), AND
///   * when this shell spawned the child, its `pid` matches that child (a manifest written by any
///     other process is not our backend's), AND
///   * its `instanceId` matches the current binding OR announces a new instance.
///
/// Same instance -> fill gaps only (the correct half of first-write-wins). New instance -> replace
/// the WHOLE record, so a new port can never be merged with a dead boot's token.
pub(crate) fn apply_manifest_observation(
    binding: &mut Binding,
    manifest: &ManifestFields,
    child_pid: Option<u32>,
) -> BindingChange {
    let Some(new_id) = manifest.instance_id.as_deref() else {
        return BindingChange::default();
    };
    if let (Some(child), Some(manifest_pid)) = (child_pid, manifest.pid) {
        if child != manifest_pid {
            return BindingChange::default();
        }
    }
    let token = manifest
        .session_token
        .as_deref()
        .filter(|t| !t.is_empty())
        .map(str::to_string);

    if binding.instance_id.as_deref() == Some(new_id) || binding.instance_id.is_none() {
        let had_port = binding.port.is_some();
        let had_token = binding.token.is_some();
        binding.instance_id = Some(new_id.to_string());
        if binding.port.is_none() {
            binding.port = manifest.api_port;
        }
        if binding.token.is_none() {
            binding.token = token;
        }
        return BindingChange {
            restarted: false,
            port_available: !had_port && binding.port.is_some(),
            token_available: !had_token && binding.token.is_some(),
        };
    }

    *binding = Binding {
        instance_id: Some(new_id.to_string()),
        port: manifest.api_port,
        token,
    };
    BindingChange {
        restarted: true,
        port_available: binding.port.is_some(),
        token_available: binding.token.is_some(),
    }
}

pub(crate) fn read_manifest_if_present(path: &Path) -> Option<ManifestFields> {
    let content = fs::read_to_string(path).ok()?;
    let v: serde_json::Value = serde_json::from_str(&content).ok()?;
    let head = v.get("head")?;
    let mut out = ManifestFields::default();
    if let Some(port) = head.get("apiPort").and_then(|p| p.as_u64()) {
        if port > 0 && port <= u16::MAX as u64 {
            out.api_port = Some(port as u16);
        }
    }
    if let Some(t) = head.get("sessionToken").and_then(|s| s.as_str()) {
        out.session_token = Some(t.to_string());
    }
    if let Some(l) = v.get("lifecycle").and_then(|s| s.as_str()) {
        out.lifecycle = Some(l.to_string());
    }
    // Tempdoc 637 #1: top-level per-boot instanceId (sibling of `lifecycle`, not under `head`).
    if let Some(id) = v.get("instanceId").and_then(|s| s.as_str()) {
        if !id.is_empty() {
            out.instance_id = Some(id.to_string());
        }
    }
    // Tempdoc 805 G.1: top-level `pid` (RuntimeManifestPublisher writes it beside `instanceId`).
    if let Some(pid) = v.get("pid").and_then(|p| p.as_u64()) {
        if pid > 0 && pid <= u32::MAX as u64 {
            out.pid = Some(pid as u32);
        }
    }
    out.started_at = v.get("startedAt").and_then(crate::exit_ledger::known_timestamp)
        .and_then(|ms| u64::try_from(ms).ok());
    Some(out)
}
