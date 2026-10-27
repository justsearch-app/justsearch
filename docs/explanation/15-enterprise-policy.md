---
title: Enterprise Policy (v2+)
type: explanation
status: draft
description: "Current config-file policy enforcement, restrictive merges, and platform limits."
---

# Enterprise Policy (v2+)

This document describes current **enterprise policy enforcement** using **config files**. Machine policy is Windows-only; Linux uses the user policy under AI Home.

## 1. Goals

- Allow enterprise admins to enforce:
  - **downloads disabled** for Install AI model/runtime downloads (not app updates or dependency-internal traffic),
  - **Online AI disabled** (AI features off),
  - **GPU acceleration disabled** (force CPU-only even if GPU packs are present),
  - allowlists for **pack-import files** (by SHA-256; not Install AI downloads or later model use),
  - allowlists for **offline packs** (by pack manifest digest),
  - (optional) disallow adopting external inference servers.
- Provide a deterministic **precedence order**:
  - machine policy → user policy → UI settings → defaults.
- Keep the v1 security posture:
  - reject offline-pack hash/digest mismatches and packs that are not allowlisted;
  - a present but unloadable Windows machine policy disables app updates and AI toggles;
  - Install AI downloads and GPU use fail closed when a configured policy service gives no snapshot; the Online-mode switch does not.

## 1.1 Implementation status (what is enforced today)

Policy is implemented and exposed via `GET /api/policy/effective`. Enforcement status:

- **App updates (first enforcing release to be named in its release notes)**:
  - `appUpdatesEnabled=false` forbids automatic and manual app-update checks and installer downloads.
    Rust enforces it without the Engine, including recovery and qualification autorun. The field is
    optional in schemaVersion 1: absent or null means allowed. Older releases ignore it; deploy an
    enforcing version before relying on the restriction. It is independent of `downloadsEnabled`
    and `JUSTSEARCH_AI_DISABLED` (both affect AI, not app updates).

- **Enforced today (v2)**:
  - `downloadsEnabled=false` blocks “Install AI” downloads. A configured policy service that throws or returns no snapshot also blocks start and repair with `403 DOWNLOADS_DISABLED` before acquisition; the background admission recheck fails closed too. Missing policy files retain permissive defaults, and an absent policy service remains permissive for compatibility.
  - `onlineAiEnabled` blocks switching into Online inference mode.
  - Offline pack import allowlisting by manifest digest and file SHA-256 (`allowlists.modelSha256`). The hash allowlist applies on pack import only; Install AI downloads verify registry-pinned hashes but do not enforce this policy allowlist.

- **Enforced in v3 (runtime + inference)**:
  - `gpuAccelerationEnabled=false`:
    - blocks GPU runtime activation (`POST /api/ai/runtime/activate` returns 403),
    - clamps `-ngl` to 0 when starting llama-server even if UI settings request GPU layers.
  - `disallowExternalInferenceServers=true`:
    - prevents “adopting” an already-running llama-server on the configured port (fail closed with a clear error).

HTTP error codes (v3):

- `POLICY_ONLINE_AI_DISABLED`:
  - returned when Online AI is disabled by policy (e.g., switching to Online mode, or activating a GPU runtime).
- `POLICY_GPU_DISABLED`:
  - returned when GPU acceleration is disabled by policy and a GPU runtime activation is attempted.
- `POLICY_EXTERNAL_SERVER_DISALLOWED`:
  - returned when policy disallows adopting an already-running external `llama-server` on the configured port.

## 2. Policy file locations

Machine policy is supported only on Windows. `resolveMachinePolicyPath()` returns `null` on
Linux and other non-Windows platforms; those platforms have only the user-writable policy.

### 2.1 Machine policy (admin-managed, Windows only)

- **Path**: `%PROGRAMDATA%\\JustSearch\\policy.v1.json`
- **Security expectation**:
  - file is ACL-protected (admin-writable; standard users read-only).
  - machine policy is authoritative.

Installer note (known limitation):

- The Windows installer currently runs in **current-user** mode. In some environments (including Sandbox), it may not have permission to create `%PROGRAMDATA%\\JustSearch\\` automatically.
- If you need machine policy, create the folder and file as admin:

```powershell
New-Item -ItemType Directory -Force "C:\ProgramData\JustSearch" | Out-Null
notepad "C:\ProgramData\JustSearch\policy.v1.json"
```

### 2.2 User policy (user-managed / restrictive-only)

- **Path**: `<AI_HOME>/policy.v1.json`
- **Security expectation**:
  - user policy is user-writable:
    - if **machine policy exists**, treat user policy as **restrictive-only** (intersection behavior; it must not broaden machine allowlists).
    - if **machine policy does not exist**, user policy may act as the **primary allowlist source** for offline pack import (power-user opt-in).

UI helper note (v2+):

- The desktop UI may **create** `<AI_HOME>/policy.v1.json` **only if it is missing** to reduce friction when enabling AI Pack import on unmanaged machines.
- The desktop UI may also **append** an additional digest into `allowlists.packManifestSha256` in an **existing** user policy **only via an explicit opt-in click**, and only when **no machine policy is present** (unmanaged machines).
- It must **never edit machine policy**, and must preserve all other user policy fields (only update `allowlists.packManifestSha256` plus `updatedAt`).

Notes:

- `<AI_HOME>` is the OS app-data directory selected by the shell (Windows: `%APPDATA%\\io.justsearch.shell\\`; Linux: `${XDG_DATA_HOME:-~/.local/share}/io.justsearch.shell`).
- For developer/test runs, AI Home may be overridden via `JUSTSEARCH_HOME` / `-Djustsearch.home`.

## 3. Precedence (locked)

1. **Machine policy** (if present)
2. **User policy** (if present)
3. **UI settings**
4. **Defaults**

Policy constrains settings and defaults. User policy may only tighten machine boolean restrictions.

### 3.1 Merge semantics (important)

The policy service merges values as follows:

- **Enabled booleans** (`appUpdatesEnabled`, `downloadsEnabled`, `onlineAiEnabled`, `gpuAccelerationEnabled`):
  - defaults are permissive; machine policy may disable them, and user policy may only further
    restrict them (logical AND). User `true` cannot override machine `false`.
  - A present but unloadable Windows machine policy disables app updates and AI toggles and disallows adoption.
- **`disallowExternalInferenceServers`**:
  - restrictions combine by logical OR; user `false` cannot override machine `true`.
- **Allowlists**:
  - if machine policy exists, it is treated as the **authoritative allowlist** source for offline pack import (**empty list = deny all**).
  - when machine policy is absent, user policy `allowlists.packManifestSha256` may be used as the **primary allowlist** (power-user opt-in).
  - when machine policy is present, user policy allowlists are **restrictive only** (intersection behavior).

See allowlist evaluation rules in `docs/explanation/14-ai-pack-spec.md`.

## 4. Policy schema (`policy.v1.json`)

### 4.1 Example

```json
{
  "schemaVersion": 1,
  "updatedAt": "2025-12-23T00:00:00Z",
  "appUpdatesEnabled": false,
  "downloadsEnabled": false,
  "onlineAiEnabled": false,
  "gpuAccelerationEnabled": false,
  "disallowExternalInferenceServers": true,
  "allowlists": {
    "modelSha256": [
      "67d1659bfe71b89d50b45a4ad1a9e5b997e5bb16ce5da66a6a6167abd569e9e2",
      "d4e388894e09cf3816e8b0896d81d265b55e7a9fff9ab03fe8bf4ef5e11295ac"
    ],
    "packManifestSha256": [
      "REPLACE_WITH_ALLOWLISTED_PACK_MANIFEST_SHA256"
    ]
  }
}
```

### 4.2 Field meanings

- `appUpdatesEnabled` (optional, default `true`):
  - `false` forbids all new app-update metadata requests and installer downloads. User policy is in
    the shell's app-data directory, passed to the Engine as `JUSTSEARCH_HOME`; Windows machine policy
    is `%PROGRAMDATA%\JustSearch\policy.v1.json`. Machine policy is not read on Linux.
  - Rust and `GET /api/policy/effective` use the same merge rules. A missing file permits defaults;
    malformed, unsupported-version, unreadable or non-regular machine policy fails closed, including
    dangling symlinks and attribute lookup failures. Broken user policy is diagnosed but ignored.
  - Policy is read at each discovery and installation, and again before artifact download. A
    discovery in flight may finish its descriptor, signature and latest-metadata requests. No
    in-flight request is cancelled. Upgrade intents, accepted sequences and local Engine
    reconciliation remain intact.
  - Launch checks are on by default about 30 seconds after chrome mount. Set
    `JUSTSEARCH_AUTO_UPDATE_CHECK=false` before starting a fresh desktop process to suppress only
    the automatic check; manual Settings/recovery checks still work when policy allows them.
  - Discovery requests `release.v2.json`, `release.v2.json.sig` and `latest.json` from the configured
    GitHub release feed, following download/CDN redirects. Installation rechecks `latest.json` and
    downloads the signed installer only on request. The contacted hosts see request IP and time;
    these requests send no document content or queries. Builds without a configured feed make no
    updater requests; debug builds may configure a runtime feed. Other egress has separate controls.
- `downloadsEnabled`:
  - `false` ⇒ Install AI blocks model/runtime downloads. A configured policy service that throws or returns no snapshot also blocks downloads as described above. This flag does not disable app-update checks/downloads or dependency-internal network activity. Allowlisted offline pack import remains available.
- `onlineAiEnabled`:
  - `false` ⇒ Online AI mode must be disabled (CPU or GPU). App should remain usable for search/indexing.
- `gpuAccelerationEnabled` (optional, default `true`):
  - `false` ⇒ GPU runtime variants must not be activated and GPU Booster Pack flows should be disabled/hidden in Simple Mode.
- `disallowExternalInferenceServers`:
  - `true` ⇒ disallow “adopt external llama-server” behavior; require JustSearch-owned loopback-only runtime.

v3 note:

- GPU Booster Pack selection/activation (and its hardware gates) are described in `docs/explanation/16-gpu-booster-pack.md` and rely on NVML-first detection with safe fallbacks.
- `allowlists.modelSha256`:
  - offline model-pack import enforces this allowlist: when non-empty, each imported pack file must match the active machine and user hash lists. Install AI downloads verify the SHA-256 pinned in the model registry; the model allowlist is not a second download gate and does not govern later model use.
- `allowlists.packManifestSha256`:
  - allowlisted offline packs by **manifest digest** (see `docs/explanation/14-ai-pack-spec.md`).

Hash normalization note:

- All SHA-256 values should be treated as **hex, case-insensitive**. Implementations should normalize to lowercase for comparisons/logging.

### 4.3 Current enforcement points

- **Install AI (v1 downloads)**:
  - if `downloadsEnabled=false`, or a configured policy service throws or returns no snapshot, refuse start and repair with `403 DOWNLOADS_DISABLED` before acquisition. The background admission recheck also fails closed. Missing policy files retain permissive defaults; an absent policy service remains permissive for compatibility.
  - if `gpuAccelerationEnabled=false`, or its policy read fails or returns no snapshot, plan CPU variants without CUDA runtime packages or GPU-only chat packages. Policy-caused exclusions say “GPU acceleration is disabled by administrator policy”. Retain installed CUDA files and existing download progress. These admission and planning checks do not cancel transfers already running.
- **Offline import (v2/v3 AI Pack import)**:
  - enforce `allowlists.packManifestSha256` allowlisting rules:
    - if machine policy exists: it is authoritative (empty list = deny all packs)
    - else, if user policy allowlist is non-empty: it is primary (power-user opt-in)
    - else: fall back to app-bundled allowlist
    - additionally, when machine policy exists and user allowlist is non-empty: apply user intersection (restrictive only)
  - enforce `allowlists.modelSha256` when packs contain models.
- **Online AI runtime**:
  - if `onlineAiEnabled=false`, block switching to Online mode (CPU or GPU).
  - if `gpuAccelerationEnabled=false`, reject explicit runtime activation (CPU and GPU variants alike); a configured activation policy service that throws or returns no snapshot also denies activation. Prevent Install AI and boot from automatically selecting CUDA executables or publishing CUDA native paths. A boot policy-load failure also prevents these automatic selections. Keep saved executable and GPU-layer preferences; owned launches apply zero GPU layers and hide CUDA devices while policy forbids GPU acceleration.
- **External server adoption**:
  - if `disallowExternalInferenceServers=true`, disallow “adopt an already-running llama-server” modes.

## 5. UX requirements

- The UI must explain policy enforcement clearly:
  - “Downloads disabled by administrator policy.”
  - “Online AI disabled by administrator policy.”
  - “GPU acceleration disabled by administrator policy.”
  - “This AI Pack is not allowlisted by policy.”
- The UI should expose “effective policy” in diagnostics:
  - which policy file(s) were loaded
  - their timestamps/hashes
  - the merged effective values

## 5.1 User policy allowlist helper endpoints (desktop)

These endpoints exist to reduce friction when using offline pack import on unmanaged machines.
They are **guardrailed** and must never edit machine policy.

- `POST /api/policy/user/create` (create-only):
  - Creates `<AI_HOME>/policy.v1.json` only if missing, seeding `allowlists.packManifestSha256` with the requested digest (and preserving app allowlist digests when the app allowlist was previously authoritative).
  - Refuses if machine policy is present.
- `POST /api/policy/user/allowlist/pack-manifest/add` (append):
  - Adds a digest into an **existing** `<AI_HOME>/policy.v1.json` allowlist (schemaVersion=1) with an opt-in click.
  - Guardrails:
    - refuses if machine policy is present (`MACHINE_POLICY_PRESENT`)
    - refuses if user policy is missing (`USER_POLICY_MISSING`)
    - refuses if user policy JSON is invalid (`USER_POLICY_INVALID_JSON`)
    - refuses if schemaVersion is unsupported (`USER_POLICY_UNSUPPORTED_SCHEMA`)
  - On success returns `{ success, path, changed, allowlistedCount }`.

## 6. Verification & troubleshooting (practical)

### 6.1 Quick verify: is policy loaded?

Use the local API:

- `GET /api/policy/effective`

Look for:

- `machine.present=true` and `machine.loaded=true` when `%PROGRAMDATA%\\JustSearch\\policy.v1.json` exists and is valid JSON.
- `user.present=true` and `user.loaded=true` when `<AI_HOME>\\policy.v1.json` exists and is valid JSON.

Additional fields (v2+):

- `packAllowlistSource`: `machine | user | app | none`
- `packAllowlistConfigured`: `true|false`

If the policy file exists but is not detected:

- Ensure it is not accidentally named `policy.v1.json.txt` (Windows Explorer hides extensions by default).

### 6.2 PACK_NOT_ALLOWLISTED (offline pack import)

Meaning:

- The imported pack’s `pack-manifest.v1.json` digest is not allowlisted by the authoritative allowlist source.
- When no allowlist is configured (machine policy absent, user allowlist empty, app allowlist empty), this is expected: **deny-by-default**.

Practical steps:

1. Check `GET /api/policy/effective`:
   - if `machine.present=true`, machine policy is authoritative for offline pack import (empty allowlist blocks all packs). Ensure `%PROGRAMDATA%\\JustSearch\\policy.v1.json` contains `allowlists.packManifestSha256`.
   - if `machine.present=false`, create/update `<AI_HOME>\\policy.v1.json` with `allowlists.packManifestSha256` (power-user allowlist).
2. Retry the import. (Note: `/api/ai/packs/status` reflects the most recent import attempt. A prior failure remains until you trigger a new import.)

### 6.3 Getting the allowlist key (manifest SHA-256)

- Use `POST /api/ai/packs/preflight` with a `path` to a zip or folder to compute the digest JustSearch will use, without installing anything.

## 6. Relationship to existing env vars

v1 already supports hard disables via env/sysprops:

- `JUSTSEARCH_AI_DISABLED` / `-Djustsearch.ai.disabled=true`
- `JUSTSEARCH_LLM_ENABLED` / `-Djustsearch.llm.enabled=false`

Enterprise policy should be the preferred v2+ mechanism, but these overrides remain useful for testing and emergency disablement.

Safety rule:

- env/sysprop overrides may **further restrict** behavior (e.g., disable AI), but must not be allowed to **weaken** machine policy enforcement.


