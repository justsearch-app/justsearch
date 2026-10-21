---
title: AI Pack Spec (v2/v3)
type: explanation
status: draft
description: "Offline pack format, allowlist trust model, safe extraction, and rollback semantics."
---

# AI Pack Spec (v2/v3)

This document defines the **offline pack format** used by JustSearch for:

- **v2 AI Pack** (models-only): install pinned GGUF model assets into AI Home in air-gapped / enterprise environments.
- **v3 GPU Booster Pack** (NVIDIA-only): install a CUDA-capable `llama-server` runtime variant (and optionally vision assets) into AI Home.

It is intentionally concrete so implementation can be deterministic and fail-closed.

## 0. Implementation status (what exists today)

This spec covers v2 + v3. The current implementation status is:

- **Implemented (v2)**:
  - Import **models-only** packs from zip or folder.
  - Fail-closed manifest validation, per-file hash/size verification, and “no extra payload files”.
  - Manifest allowlisting (policy/app allowlist) and model hash allowlisting (policy).
  - Downgrade protection (reject older `packVersion` unless explicitly allowed).
  - Helper endpoint: `POST /api/ai/packs/preflight` returns `{ packId, packVersion, manifestSha256 }` without installing.
- **Implemented (v3)**:
  - Import **runtime packs** (`kind: "runtime"`, `variantId`) from zip or folder.
  - Runtime variants are installed under `<AI_HOME>/native-bin/llama-server/variants/<variantId>/`.
  - Runtime activation is a separate step (`POST /api/ai/runtime/activate`) and is gated by policy + self-test.
- **Not implemented yet (future)**:
  - `kind: "vision"` and `kind: "combo"` packs and their non-runtime asset roles.
  - Automatic activation as part of offline pack import. Separately, runtime discovery can prefer CUDA; Install AI and Windows startup can select an installed `cuda12` variant when GPU policy permits (startup also requires positive GPU layers); see [AI Setup + Verification](13-ai-setup-and-verification.md).

## 1. Goals

- Support **offline import** from a **zip** or a **folder**.
- Ensure **integrity and authenticity** without Windows code signing:
  - **Allowlist-only trust** (locked for now): packs are accepted only when the **pack manifest digest** is allowlisted by the app and/or enterprise policy.
- Ensure **safe extraction**:
  - prevent zip-slip/path traversal
  - never write outside AI Home
  - do not allow packs to choose arbitrary destination paths
- Ensure **atomic-ish install + rollback**:
  - stage → verify → install
  - prevent silent downgrades
  - allow explicit rollback for admins/users when needed

## 2. Non-goals (for v2/v3 initial implementation)

- Signed remote manifest / TUF (deferred).
- Accepting arbitrary packs from arbitrary sources without allowlisting.
- Arbitrary remote runtime sources. Outside offline import, Install AI can download registry-pinned, SHA-256-verified Windows runtime binaries and CUDA dependencies.

## 3. Terminology

- **AI Home**: `%APPDATA%\\io.justsearch.shell\\` on Windows (see `docs/explanation/13-ai-setup-and-verification.md`).
- **Pack**: a zip file or folder containing a **manifest** and a **payload**.
- **Manifest digest**: SHA-256 of the **raw manifest file bytes** (as stored in the pack). This is allowlisted (not the zip).
- **Allowlist**: a set of manifest digests (and optional pack ids) that are permitted to install.

Scope note:

- Model packs use the platform AI Home. Offline **runtime packs are Windows-only** (`NativeTargets.supportsRuntimePacks()`); Linux uses an operator-supplied runtime and has no machine-policy file. Concrete executable/DLL examples below describe Windows packs.

## 4. Trust model (allowlist-only)

### 4.1 What we verify

On import, the app must verify:

1. **Manifest digest allowlist**
   - Compute `manifest_sha256` from the pack’s manifest bytes.
   - Determine the effective allowlist using this deterministic rule:
     - If **machine policy** exists: the pack MUST be in `allowlists.packManifestSha256` (**empty list = deny all packs**).
     - Else, if **user policy** exists and `allowlists.packManifestSha256` is non-empty: the pack MUST be in that list (**power-user enablement**).
     - Else: the pack MUST be in the **app-bundled pack allowlist**.
     - Additionally, when **machine policy exists**, if **user policy** has a non-empty pack allowlist then require membership (restrictive intersection).
   - Rationale:
     - when a machine policy exists, user-writable configuration must not be able to broaden what is allowed
     - on unmanaged installs, user policy provides a per-user opt-in allowlist for power users
     - enterprise admins can pin exactly which packs are allowed on managed machines
2. **Per-file integrity**
   - Every file declared in the manifest must exist in the payload and match declared SHA-256 and size.
3. **No extra files**
   - Reject packs that contain files not declared in the manifest (fail closed).

Allowlist sources:

- **App-bundled allowlist**: `modules/ui/src/main/resources/ai/pack-allowlist.v1.json`
- **Machine policy**: `%PROGRAMDATA%\\JustSearch\\policy.v1.json` (see `docs/explanation/15-enterprise-policy.md`)
- **User policy**: `<AI_HOME>/policy.v1.json` (restrictive only; see `docs/explanation/15-enterprise-policy.md`)

### 4.2 Why allowlist the manifest digest (not the zip)

- Whole-zip digests are fragile (repacking can change compression metadata).
- Manifest digests are stable as long as the manifest bytes are unchanged.
- Digest allowlisting is compatible with enterprise workflows (admins can pin a manifest digest in policy).

Practical note (Windows):

- Some tools may write JSON with a UTF-8 BOM. Because the digest is computed from the raw bytes, **a BOM changes the digest**. Use `POST /api/ai/packs/preflight` to compute the exact digest JustSearch will use.

## 4.3 Practical workflow (unmanaged machines)

On unmanaged machines (no machine policy), the recommended flow is:

1. **Preflight** the pack to compute `manifestSha256` (no install): `POST /api/ai/packs/preflight`.\n
2. **Allowlist** the digest for this user:\n
   - If `<AI_HOME>/policy.v1.json` is missing: use the UI helper “Create user policy (enable this pack)” (or `POST /api/policy/user/create`).\n
   - If the user policy already exists: use the UI helper “Add digest to user policy allowlist” (or `POST /api/policy/user/allowlist/pack-manifest/add`).\n
3. **Import** the pack: `POST /api/ai/packs/import`.\n

On managed machines (machine policy present), allowlisting must be done by administrators in `%PROGRAMDATA%\\JustSearch\\policy.v1.json`.

## 5. Pack structure (zip or folder)

Pack root MUST contain:

- `pack-manifest.v1.json`
- `payload/` directory

All other files MUST be under `payload/` and MUST be declared in the manifest. This keeps validation fail-closed and avoids “special-case” files at pack root.

The payload may contain subdirectories (e.g., `payload/models/`, `payload/runtime/`, `payload/vision/`) but **destination paths are not chosen by the pack**; only the app maps payload files into AI Home.

## 6. Manifest schema (`pack-manifest.v1.json`)

### 6.1 Required fields

- `schemaVersion` (int): must be `1`
- `packId` (string): stable identifier (e.g., `justsearch.ai-pack.v2.models.default`)
- `packVersion` (string): semver-like (e.g., `2.0.0`)
- `kind` (string enum): `models | runtime | vision | combo`
- `createdAt` (string): ISO-8601 timestamp
- `requiresAppMin` (string): minimum compatible JustSearch version
- `requiresAppMax` (string, optional): maximum compatible JustSearch version (or omit for “no upper bound”)
- `files[]`: list of files included in the payload
- `assets[]`: logical mapping of “what this file is used for” (role-based)

Implementation note (v2/v3 today):

  - The importer enforces only a subset:
    - `schemaVersion=1`, `packId`, `packVersion`
    - `kind` must be `models` or `runtime`
    - `files[]` + `assets[]` must be present
    - For `models`: only `model.chat` + `model.embedding` are accepted
    - For `runtime`: `variantId` is required and roles:
      - `runtime.llamaServer` + `runtime.runtimeFile` (llama-server runtime variant)
      - `runtime.onnxruntime` + `runtime.onnxruntimeFile` (optional ONNX Runtime native variant, aligned to the same `variantId`)
  - Fields like `createdAt` / `requiresAppMin` are parsed but not yet enforced as hard requirements.

### 6.2 File entries

Each `files[]` entry has:

- `id` (string): stable file id within the pack (unique)
- `pathInPack` (string): relative path from the **pack root** (no leading slash), and MUST start with `payload/`
- `sha256` (string): hex SHA-256 (case-insensitive; implementations should normalize to lowercase for comparisons/logs)
- `sizeBytes` (number): bytes

### 6.3 Asset entries (role mapping)

Each `assets[]` entry has:

- `role` (string enum): a logical destination role (see mapping table below)
- `fileId` (string): references a `files[].id`
- `variantId` (string, optional): legacy/optional; v3 runtime packs use top-level `variantId`

Runtime variant rules (v3):

- If `kind` is `runtime`, top-level `variantId` is REQUIRED (e.g., `cuda-12.4`).
- Runtime packs must include exactly one `runtime.llamaServer` asset that references `payload/llama-server.exe`.
- Runtime packs MAY also include `runtime.onnxruntime` / `runtime.onnxruntimeFile` assets to ship an ONNX Runtime native payload under the same `variantId` (used by the Engine index half for reranker GPU acceleration).
- Hardware compatibility gating (driver/VRAM) is based on the app’s known mapping for `variantId` (not pack-provided claims).

### 6.4 Example manifest (models-only AI Pack)

```json
{
  "schemaVersion": 1,
  "packId": "justsearch.ai-pack.v2.models.default",
  "packVersion": "2.0.0",
  "kind": "models",
  "createdAt": "2025-12-23T00:00:00Z",
  "requiresAppMin": "1.0.0",
  "files": [
    {
      "id": "chat_gguf",
      "pathInPack": "payload/models/Qwen3VL-8B-Thinking-Q4_K_M.gguf",
      "sha256": "9e54ca764eb00aacd26a94f31f0b5b42bf1a60c8783bbd39b86e37ff3faeb41f",
      "sizeBytes": 5027784800
    },
    {
      "id": "embed_gguf",
      "pathInPack": "payload/models/nomic-embed-text-v1.5.Q4_K_M.gguf",
      "sha256": "d4e388894e09cf3816e8b0896d81d265b55e7a9fff9ab03fe8bf4ef5e11295ac",
      "sizeBytes": 84106624
    }
  ],
  "assets": [
    { "role": "model.chat", "fileId": "chat_gguf" },
    { "role": "model.embedding", "fileId": "embed_gguf" }
  ]
}
```

### 6.5 Example manifest (runtime pack / GPU Booster Pack)

Runtime packs are `kind: "runtime"` and MUST specify a `variantId`. They include `llama-server.exe` plus all required adjacent DLLs and notices.

```json
{
  "schemaVersion": 1,
  "packId": "justsearch.gpu-booster-pack.v3.cuda-12.4",
  "packVersion": "3.0.0",
  "kind": "runtime",
  "variantId": "cuda-12.4",
  "createdAt": "2025-12-27T00:00:00Z",
  "requiresAppMin": "1.0.0",
  "files": [
    {
      "id": "llama_server_exe",
      "pathInPack": "payload/llama-server.exe",
      "sha256": "REPLACE_WITH_SHA256",
      "sizeBytes": 123456789
    },
    {
      "id": "ggml_cuda",
      "pathInPack": "payload/ggml-cuda.dll",
      "sha256": "REPLACE_WITH_SHA256",
      "sizeBytes": 12345678
    },
    {
      "id": "cudart",
      "pathInPack": "payload/cudart64_12.dll",
      "sha256": "REPLACE_WITH_SHA256",
      "sizeBytes": 12345678
    }
  ],
  "assets": [
    { "role": "runtime.llamaServer", "fileId": "llama_server_exe" },
    { "role": "runtime.runtimeFile", "fileId": "ggml_cuda" },
    { "role": "runtime.runtimeFile", "fileId": "cudart" }
  ]
}
```

## 7. Role → destination mapping (fixed)

The app MUST map roles to fixed destinations under AI Home:

| Role | Destination |
| --- | --- |
| `model.chat` | `<AI_HOME>/models/<filename>` |
| `model.embedding` | `<AI_HOME>/models/<filename>` |
| `vision.mmproj` | `<AI_HOME>/models/<filename>` (or `<AI_HOME>/models/vision/<filename>` if we later split) |
| `runtime.llamaServer` (with `variantId`) | `<AI_HOME>/native-bin/llama-server/variants/<variantId>/<filename>` |
| `runtime.runtimeFile` (with `variantId`) | `<AI_HOME>/native-bin/llama-server/variants/<variantId>/<filename>` |
| `runtime.onnxruntime` (with `variantId`) | `<AI_HOME>/native-bin/onnxruntime/variants/<variantId>/<filename>` |
| `runtime.onnxruntimeFile` (with `variantId`) | `<AI_HOME>/native-bin/onnxruntime/variants/<variantId>/<filename>` |

Notes:

- The manifest does **not** provide destination paths.
- `<filename>` is taken from the pack file name (last path segment of `pathInPack`).
- For runtime variants, include `runtime-version.txt` and any `LICENSE-*` files in the pack payload so they are installed adjacent to `llama-server.exe` (matching v1 runtime restore expectations).
  - If the pack includes ONNX Runtime payloads, include ORT notices/licenses adjacent to the installed ORT DLLs under the ONNX runtime variant directory.

v3 note (runtime packs):

- v3 supports runtime packs. Runtime pack files are installed under `<AI_HOME>/native-bin/llama-server/variants/<variantId>/` (kept out of the v1 CPU baseline discovery path).
- Pack builder rule (principle of least privilege): when sourcing from upstream `llama.cpp` release zips, exclude auxiliary tools (bench/cli/quantize/etc) and include only `llama-server.exe` + required adjacent `*.dll` + `LICENSE-*` + `NOTICE-*` files.
- v3 implementation detail: the importer remains fail-closed and requires every file be referenced by an asset; runtime packs use:
  - `runtime.runtimeFile` for non-exe llama-server adjacent payload files
  - `runtime.onnxruntime` / `runtime.onnxruntimeFile` for ONNX Runtime native payload files (installed under `<AI_HOME>/native-bin/onnxruntime/variants/<variantId>/`).

Practical builder note:

- Use the repo’s builder tool to produce packs with deterministic manifests and Windows-safe zip entry names (forward slashes only): `tools/build-gpu-booster-pack.ps1`.

## 8. Safe extraction rules (zip-slip prevention)

When importing from a zip:

- Reject any `ZipEntry` that is:
  - absolute (starts with `/` or drive letter), or
  - contains `..` segments, or
  - normalizes outside the staging directory.
- Extract only into a staging dir under AI Home, e.g.:
  - `<AI_HOME>/tmp/pack-import/<packId>/<timestamp>/`
- Prefer rejecting packs with duplicate file names/paths.

### 8.1 Additional extraction safeguards (beyond zip-slip)

These rules are required so the importer is safe and deterministic even for hostile input.

- **Reject duplicate paths**:
  - In the manifest: `files[].pathInPack` MUST be unique.
  - In zip containers: `ZipEntry.name` MUST be unique.
  - On Windows, treat paths as **case-insensitive** when checking uniqueness (reject `payload/Foo.bin` + `payload/foo.bin`).
- **Reject backslashes**:
  - `pathInPack` MUST use `/` separators only.
  - Zip entries that contain `\\` MUST be rejected (avoid ambiguous normalization on Windows).
- **Reject suspicious entry types**:
  - The importer MUST extract only regular files.
  - Directory entries are allowed (ignored), but are never considered “payload files”.
  - Any attempt to encode symlinks / special file types MUST be rejected if the chosen extraction library exposes that metadata.
- **Zip bomb limits (fail closed)**:
  - Require manifest sizes and enforce them while extracting:
    - For each declared file: stream bytes to disk and abort if written bytes exceed `sizeBytes`.
    - Reject if extracted bytes are less than `sizeBytes` (truncated).
  - Additionally enforce global caps (values are implementation constants; examples):
    - `MAX_PACK_FILES` (e.g., 512)
    - `MAX_PACK_TOTAL_BYTES` (e.g., 50 GiB)
    - `MAX_SINGLE_FILE_BYTES` (e.g., 20 GiB)
  - Reject packs that exceed any cap even if hashes would otherwise validate.
- **“No extra files” is about regular files**:
  - Reject any regular file under `payload/` that is not declared in the manifest.
  - Directories are allowed only as containers for declared files.

When importing from a folder:

- Treat the folder as the extracted staging dir.
- Apply the same validation rules to the paths referenced in the manifest.
- **Reject symlinks / junctions / reparse points**:
  - Any declared file path that is a symlink (or resolves through a symlinked segment) MUST be rejected.
  - Only accept regular files (`isRegularFile(..., NOFOLLOW_LINKS)`).
  - Rationale: prevents folder-import packs from referencing files outside the import root.

## 9. Install algorithm (staged + verified)

1. **Read manifest bytes** and compute `manifest_sha256`.
2. **Check allowlist** (fail closed if not allowlisted).
3. **Extract (zip only)** into staging.
4. **Validate**
   - all declared files exist
   - sha256 + size match
   - no extra payload files beyond manifest
   - enforce caps (file count / total bytes / single file bytes)
5. **Install**
   - move files into fixed AI Home destinations.
   - use atomic-ish moves (`ATOMIC_MOVE` when possible, fallback to replace).
6. **Record state**
   - write installed pack info (packId, packVersion, manifest_sha256, installedAt) and installed file hashes for diagnostics.

### 9.1 Installed packs record (`installed-packs.v1.json`)

Write a single record file under AI Home:

- **Path**: `<AI_HOME>/installed-packs.v1.json`

Suggested schema (v1):

```json
{
  "schemaVersion": 1,
  "updatedAt": "2025-12-23T00:00:00Z",
  "packs": [
    {
      "packId": "justsearch.gpu-pack.v3.cuda-12.4",
      "packVersion": "3.0.0",
      "kind": "runtime",
      "manifestSha256": "REPLACE_WITH_SHA256",
      "installedAt": "2025-12-23T00:00:00Z",
      "files": [
        {
          "role": "runtime.llamaServer",
          "variantId": "cuda-12.4",
          "destPath": "native-bin/llama-server/variants/cuda-12.4/llama-server.exe",
          "sha256": "REPLACE_WITH_SHA256",
          "sizeBytes": 123456
        }
      ]
    }
  ]
}
```

Notes:

- `destPath` SHOULD be stored as a path relative to `<AI_HOME>` for portability.
- This record is used for:
  - diagnostics (“what is installed?”),
  - rollback decisions (“what was last-known-good?”), and
  - uninstall/cleanup (“what files belong to this pack?”).

## 10. Rollback / downgrade semantics

- **Prevent silent downgrade**:
  - if a different pack version is already installed for the same `packId`, refuse installing an older version unless explicitly requested (“Admin override / rollback”).
- **Keep last-known-good**:
  - keep one previous version for rollback when feasible (disk permitting).
- **Shadow install for runtime variants**:
  - install runtime variants into a new variant directory
  - only mark variant “active” after a bounded self-test succeeds
  - on failure: leave CPU runtime active

## 11. Relationship to v1 "Install AI"

v1 downloads models from allowlisted URLs in a bundled registry (`ai/model-registry.v2.json`), verifies SHA-256, and installs atomically. Offline packs should reuse the same safety posture (hash pinned + atomic install), but add explicit pack allowlisting for authenticity.

## 12. Forward Compatibility

The pack manifest parser uses Jackson's `FAIL_ON_UNKNOWN_PROPERTIES=false` to support forward compatibility:

- **Future-proof parsing:** Future pack versions may include additional manifest fields (e.g., new metadata, capabilities declarations).
- **Graceful degradation:** Current app versions can still parse newer packs by ignoring unknown fields.
- **Safety maintained:** Strict validation is enforced for all *known* fields (schema version, file hashes, roles).
- **Schema versioning:** The `schemaVersion` field ensures semantic compatibility; breaking changes require a version bump.

This design allows pack creators to add optional metadata without breaking older app versions, while the app's validation logic remains fail-closed for security-critical fields.
