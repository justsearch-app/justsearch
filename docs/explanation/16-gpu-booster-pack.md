---
title: NVIDIA GPU Booster Pack (v3)
type: explanation
status: draft
description: "v3 GPU plan (NVML-first), runtime sourcing, self-test, and rollback."
---

# NVIDIA GPU Booster Pack (v3)

This document describes the supported v3 approach for NVIDIA-only GPU acceleration in JustSearch:

- **Hardware-aware plan selection** (NVML-first).
- **Offline GPU Booster Pack** distribution (no arbitrary executable downloads).
- **Shadow install + self-test + rollback** for runtime variants.

This is designed to align with:

- current v1 behavior (`docs/explanation/13-ai-setup-and-verification.md`)
- pack format (`docs/explanation/14-ai-pack-spec.md`)
- enterprise policy (`docs/explanation/15-enterprise-policy.md`)

## 0. Implementation status (what exists today)

This document describes current v3 behavior and is grounded in shipped code paths:

- **Current runtime (v1/v2)**:
  - The app ships a **CPU-only** `llama-server.exe` runtime payload, pinned to `ggml-org/llama.cpp` prebuilt `b8157` (see `modules/ui/build.gradle.kts`).
  - The active server executable can be overridden via `justsearch.server.exe` / `JUSTSEARCH_SERVER_EXE` (BYO runtime).
- **Current hardware detection**:
  - v3 exposes an **NVML-first** GPU capability snapshot via `GET /api/gpu/capabilities`.
    - NVML probing is implemented in the Java backend via the Java FFM API (`java.lang.foreign.*`) and loads `nvml.dll` by absolute path when possible.
    - `nvidia-smi` is used only as a best-effort fallback (lower confidence).
  - `GET /api/inference/status` continues to exist as a higher-level inference status view; v3’s authoritative capability snapshot is `/api/gpu/capabilities`.
- **Current lifecycle behavior**:
  - The runtime manager can attach to an already-running `llama-server` on the configured port to avoid restart loops (`InferenceLifecycleManager.usingExternalLlamaServer`).
    - For safety, adoption is verified via `GET /props` by default (not just `GET /health`); dev escape hatch: `-Djustsearch.inference.external.allow_health_only_adoption=true`.
  - Enterprise policy flags `gpuAccelerationEnabled` and `disallowExternalInferenceServers` are enforced in v3:
    - `gpuAccelerationEnabled=false` blocks GPU runtime activation and clamps GPU layers to 0 when starting `llama-server`.
    - `disallowExternalInferenceServers=true` blocks adopting an already-running external `llama-server` on the configured port.
- **Current pack import**:
  - Offline pack import supports **models packs** (v2) and **runtime packs** (v3) (install-only; activation is separate).
  - Runtime variants are installed under `<AI_HOME>/native-bin/llama-server/variants/<variantId>/` and are activated explicitly via `POST /api/ai/runtime/activate`.
  - Runtime packs may also optionally ship a matching ONNX Runtime native variant under `<AI_HOME>/native-bin/onnxruntime/variants/<variantId>/` (used by the Worker for reranker GPU acceleration when available).

Practical note (allowlisting runtime packs):

- Compute the runtime pack `manifestSha256` using `POST /api/ai/packs/preflight`.
- Ensure it is allowlisted before import:
  - managed machines: admins add it to `%PROGRAMDATA%\\JustSearch\\policy.v1.json` (`allowlists.packManifestSha256`)
  - unmanaged machines: users can add it to `<AI_HOME>/policy.v1.json`:
    - create if missing: `POST /api/policy/user/create`
    - append if already exists (opt-in): `POST /api/policy/user/allowlist/pack-manifest/add`

## 1. Why v3 needs a GPU Booster Pack

- v1 ships a **CPU-only** `llama-server` binary, but Online mode (chat) requires a supported NVIDIA
  GPU regardless: no install path offers a chat/GGUF model without a CUDA-functional GPU (see
  `docs/explanation/05-ai-architecture.md`), so the CPU-only binary alone never yields a working
  chat feature.
- GPU acceleration requires a CUDA-capable runtime plus vendor runtime DLLs (cuBLAS, cuBLASLt, cudart, etc).
- We maintain security posture by avoiding arbitrary binary downloads:
  - GPU support is enabled only by importing an allowlisted offline pack.

## 2. Runtime sourcing (upstream pinned prebuilt)

We already pin an upstream Windows CPU runtime in v1:

- `llama-b8157-bin-win-cpu-x64.zip` (pinned + SHA-256 in `modules/ui/build.gradle.kts`)

The upstream `ggml-org/llama.cpp` `b8157` release also provides Windows CUDA artifacts suitable for GPU packs:

- **CUDA 12.4 (baseline, widest Windows driver compatibility)**:
  - `llama-b8157-bin-win-cuda-12.4-x64.zip` (≈195 MB)
  - `cudart-llama-bin-win-cuda-12.4-x64.zip` (≈373 MB)
- **CUDA 13.1 (optional, newer driver branch)**:
  - `llama-b8157-bin-win-cuda-13.1-x64.zip` (≈88 MB)
  - `cudart-llama-bin-win-cuda-13.1-x64.zip` (≈384 MB)

Recommendation (docs + implementation default):

- Treat **CUDA 12.4** as the first supported GPU Booster Pack variant (widest compatibility).
- Optionally ship **CUDA 13.1** as a second variant once validated on real NVIDIA hardware, and select it only when the detected driver branch supports CUDA 13.x.

Notes:

- The GPU Booster Pack should be assembled by combining the **runtime payload** (exe + ggml backends) with the matching **CUDA redistributables** bundle, and including upstream license files.
- We treat these upstream artifacts as the baseline because v1 already demonstrated “pin known-good upstream builds” is the lowest crash-risk approach.

### 2.1 Exact contents (pinned `b8157` CUDA zips)

This section exists to remove ambiguity when implementing the GPU Booster Pack builder.

#### CUDA runtime zip contents (`llama-b8157-bin-win-cuda-*.zip`)

The upstream CUDA runtime zip contains **top-level** files (no folders). For both CUDA 12.4 and CUDA 13.1, it contains:

- **Executable**
  - `llama-server.exe`
- **Runtime DLLs** (adjacent to the EXE)
  - `llama.dll`
  - `mtmd.dll`
  - `ggml.dll`
  - `ggml-base.dll`
  - `ggml-cuda.dll`
  - `ggml-rpc.dll`
  - `libomp140.x86_64.dll`
  - `ggml-cpu-x64.dll`
  - `ggml-cpu-sse42.dll`
  - `ggml-cpu-haswell.dll`
  - `ggml-cpu-skylakex.dll`
  - `ggml-cpu-icelake.dll`
  - `ggml-cpu-sandybridge.dll`
  - `ggml-cpu-alderlake.dll`
  - `ggml-cpu-sapphirerapids.dll`
  - `ggml-cpu-cannonlake.dll`
  - `ggml-cpu-cascadelake.dll`
  - `ggml-cpu-cooperlake.dll`
  - `ggml-cpu-ivybridge.dll`
  - `ggml-cpu-piledriver.dll`
  - `ggml-cpu-zen4.dll`

Note: `libcurl-x64.dll` and all `LICENSE-*` files were removed from the upstream zip starting at b8157. The `LICENSE-curl` removal corresponds to the libcurl removal; no LICENSE files are bundled in this release.

The upstream zip also includes additional executables (bench/tools). In `b8157` these include:

- `llama-batched-bench.exe`
- `llama-bench.exe`
- `llama-cli.exe`
- `llama-completion.exe`
- `llama-fit-params.exe`
- `llama-gemma3-cli.exe`
- `llama-gguf-split.exe`
- `llama-imatrix.exe`
- `llama-llava-cli.exe`
- `llama-minicpmv-cli.exe`
- `llama-mtmd-cli.exe`
- `llama-perplexity.exe`
- `llama-quantize.exe`
- `llama-qwen2vl-cli.exe`
- `llama-tokenize.exe`
- `llama-tts.exe`
- `rpc-server.exe`

For the GPU Booster Pack we should include **only** `llama-server.exe` + all required adjacent `*.dll` + `LICENSE-*` files (principle of least privilege).

#### CUDA redistributables zip contents (`cudart-llama-bin-win-cuda-*.zip`)

This zip contains only CUDA DLLs:

- CUDA 12.4:
  - `cudart64_12.dll`
  - `cublas64_12.dll`
  - `cublasLt64_12.dll`
- CUDA 13.1:
  - `cudart64_13.dll`
  - `cublas64_13.dll`
  - `cublasLt64_13.dll`

#### GPU Booster Pack assembly rule

For a given `variantId` (`cuda-12.4` or `cuda-13.1`), the GPU Booster Pack should install a single “adjacent-files” directory under:

- `<AI_HOME>/native-bin/llama-server/variants/<variantId>/`

and include:

- all files listed above (server + DLLs), plus
- a `runtime-version.txt` written by the pack builder (e.g., `llama.cpp b8157 win-cuda-12.4-x64`).

Dependency note (Windows):

- The upstream runtime zip is the authoritative list of files we start from, but Windows runtime dependencies can vary by build toolchain.
- If the resulting `llama-server.exe` requires additional runtime DLLs not present in the upstream zip (e.g., MSVC runtime DLLs), treat this as a **self-test failure** and fix it by adjusting the pack recipe (or switching to a different pinned upstream artifact). Do not “paper over” missing DLLs with heuristic PATH hacks.

## 3. Compatibility matrix (CUDA variants)

### 3.1 Driver requirements (Windows)

We gate GPU plan recommendation/activation by NVIDIA driver version.

CUDA 12.4 (specific Windows minimums):

- CUDA 12.4 GA: **>= 551.61** (Windows)
- CUDA 12.4 Update 1: **>= 551.78** (Windows)

CUDA 13.x (driver branch floor):

- CUDA 13.x: **>= 580** (Windows driver branch; minor-version compatibility floor)

Notes:

- Starting with CUDA 13.1, the Windows display driver is **not bundled** with the CUDA Toolkit package; users install the driver separately. This does not change our pack approach (we never ship the driver).
- When in doubt, default to the CUDA 12.4 variant because it has a lower Windows driver floor and aligns with our pinned-upstream strategy.

Source: [CUDA Toolkit Release Notes](https://docs.nvidia.com/cuda/cuda-toolkit-release-notes/index.html).

### 3.2 Compute capability + VRAM

We should document a conservative baseline:

- **Compute capability**: prefer `>= 6.0` (Pascal+) as the “supported” floor (older may work but is not guaranteed/tested).
- **VRAM**:
  - never block CPU
  - only recommend GPU plan when detected VRAM and driver meet requirements.

## 4. Hardware detection (NVML-first)

### 4.1 Why NVML (not `nvidia-smi`)

- Windows Sandbox often lacks `nvidia-smi`.
- v3 uses NVML-first detection for driver + VRAM (`/api/gpu/capabilities`), with `nvidia-smi` as a best-effort fallback.
- `nvidia-smi` is useful but insufficient as a primary signal because:
  - it does not provide an authoritative **driver version** gate for CUDA variants
  - it is brittle in minimal environments and can be missing even when an NVIDIA driver is installed
- NVML is driver-provided (`nvml.dll`) and can be called directly from the app via a binding.

### 4.2 Implementation boundary + safe loading (Windows)

NVML access lives in the **Java backend** (the same layer that owns inference lifecycle and policy gates).

Current implementation:

- Uses the Java FFM API (`java.lang.foreign.*`) to bind a minimal NVML surface.
- Safe load strategy (Windows):
  1. Try `%SystemRoot%\\System32\\nvml.dll` via `System.load(...)`
  2. Else fall back to `System.loadLibrary("nvml")` (best-effort; relies on the DLL search path)
  3. If loading/binding fails: treat NVML as unavailable and proceed with `nvidia-smi` fallback (low confidence) or CPU-only.

### 4.3 Required detection outputs

`GET /api/gpu/capabilities` returns a merged capability snapshot:

- `nvml` (best-effort): `{ available, attemptedPath, loadedPath, error, driverVersion, driverVersionMajor, driverVersionMinor, deviceCount, totalVramBytes, freeVramBytes, usedVramBytes }`
- `nvidiaSmi` (best-effort fallback): `{ available, error, driverVersion, driverVersionMajor, driverVersionMinor, totalVramBytes, freeVramBytes, vramDescription }`
- `effective` (what v3 uses for gating/UX): `{ cudaAvailable, source, confidence, driverVersion, driverVersionMajor, driverVersionMinor, deviceCount, totalVramBytes, freeVramBytes, usedVramBytes }`

Notes:

- Current v3 reports **aggregate / device-0-oriented** VRAM signals (plus `deviceCount`), not a full per-GPU inventory.
- `effective.source` is `nvml | nvidia-smi | none` and `effective.confidence` is `HIGH | LOW | UNKNOWN`.

Minimum NVML API surface (v3):

- `nvmlInit_v2()` / `nvmlShutdown()`
- `nvmlSystemGetDriverVersion()` (authoritative for driver gating)
- `nvmlDeviceGetCount_v2()`
- `nvmlDeviceGetHandleByIndex_v2(i)`
- `nvmlDeviceGetMemoryInfo(handle)` (total/free/used bytes)
- `nvmlErrorString(rc)` (best-effort error messages in diagnostics)

Fallback:

- If NVML cannot be loaded, v3 falls back to `nvidia-smi` parsing (best-effort) and treats the result as low confidence.

### 4.4 GPU device selection scope (v3 initial cut)

Important: NVML GPU indices are not guaranteed to match `llama-server` device identifiers.

Upstream `llama-server` device selection uses:

- `--list-devices` to print device **names**
- `--device <dev1,dev2,..>` where `dev*` must match `ggml_backend_dev_by_name(...)` (names, not indices)

Observed `--list-devices` output format (from llama.cpp upstream `common/arg.cpp`):

- Header: `Available devices:`
- One line per non-CPU device:
  - `<name>: <description> (<totalMiB> MiB, <freeMiB> MiB free)`

Empirical note (b8157 CUDA runtime on Windows):

- On an NVIDIA machine, device names are of the form `CUDA0`, `CUDA1`, … and appear exactly as `ggml_backend_dev_name(dev)` prints them.
- Example output observed (RTX 4070, driver `591.59`) after extracting the CUDA runtime + cudart DLLs adjacent to `llama-server.exe`:

```text
Available devices:
  CUDA0: NVIDIA GeForce RTX 4070 (12281 MiB, 11090 MiB free)
```

Therefore, v3 initial scope should be:

- **Single-GPU only**: do not attempt multi-GPU splits.
- Do not pass `--device`/`--main-gpu` flags unless we explicitly implement a mapping strategy.
- Rely on `llama-server` defaults for device selection and use NVML primarily for:
  - driver/VRAM gating (plan recommendation), and
  - self-test confirmation (VRAM delta).

### 4.5 Deterministic pack recommendation (driver-version aware)

Given a detected `nvidia.driverVersion` (from NVML), recommend the newest compatible CUDA variant:

- If `driverVersion >= 580.*`: recommend `cuda-13.1` (if we ship it and it is installed/allowlisted).
- Else if `driverVersion >= 551.61`: recommend `cuda-12.4` (baseline).
- Else: recommend CPU-only (driver too old).

VRAM guardrail:

- Never block CPU.
- Only auto-recommend a GPU plan when **total VRAM is known** and `>= 8 GB` (VDU/vision baseline).
- If driver/VRAM is unknown, recommend CPU by default; allow advanced users to import packs, but require a self-test before activation.

### 4.6 Driver version parsing + comparisons

NVML returns driver identifiers as **alphanumeric strings** (not guaranteed to be purely numeric). Therefore, v3 must define a deterministic parse/compare rule.

Recommended rule:

- Extract the first substring matching a dotted numeric prefix, e.g. `551.61`, `551.78`, `580.95.05`.
- Parse into `(major, minor, patch)` where missing components default to `0`.
- Compare lexicographically.
- If no numeric prefix can be extracted: treat driver version as **unknown**.

Empirical note:

- `nvidia-smi --query-gpu=driver_version --format=csv,noheader` commonly returns a 2-component dotted version (example observed: `591.59`). The parse rule must accept 2 or 3 components.

This supports gates like:

- `cuda-12.4` requires `>= 551.61`
- `cuda-13.1` requires `>= 580.0.0` (driver branch floor)

## 5. Runtime variants and deterministic selection

### 5.1 Problem (today)

`InferenceConfig` currently scans `native-bin/llama-server` subdirectories and uses `findFirst()` (ordering is not guaranteed). With runtime variants, this becomes nondeterministic.

### 5.2 Target behavior (v3)

- Runtime variants should live under fixed directories, e.g.:
  - `<AI_HOME>/native-bin/llama-server/llama-server.exe` (CPU baseline shipped with the app)
  - `<AI_HOME>/native-bin/llama-server/variants/cuda-12.4/llama-server.exe` (GPU pack)
- Active runtime selection must be explicit and deterministic:
  - Use the existing **server executable override** (`UiSettings.serverExecutablePath` → `-Djustsearch.server.exe`) as the pointer for the active runtime variant.
  - Simple Mode should set/clear this automatically when switching between CPU and GPU plans.

### 5.3 External llama-server adoption (current behavior vs v3 needs)

Current behavior (today):

- If a server is already responding on the configured port (`GET /health` returns 200), the runtime manager will probe `GET /props` to verify it looks like `llama-server` before adopting.
  - If `/props` is missing/unparseable or doesn't look like `llama-server`, adoption is refused by default (fail-closed to avoid adopting unrelated HTTP services).
  - Dev escape hatch: `-Djustsearch.inference.external.allow_health_only_adoption=true` allows health-only adoption.
- In this mode there is **no process handle / PID** owned by JustSearch, so:
  - the app cannot stop/restart the server reliably
  - inference refresh through accepted `core.reconfigure` is rejected because restart requires a process handle
  - `POST /api/inference/detach` can be used to switch to a managed server on a new free port (leaves the external server untouched)
  - GPU self-test attribution by PID is not possible
  - adopted servers are still health-monitored; if the external server becomes unhealthy mid-session, inference switches to Offline

v3 implication:

- Full v3 (auto-activation + self-test + rollback + policy enforcement) strongly prefers/assumes the app **owns** the `llama-server` process.

Decision options (to be finalized for v3):

- **Option A (recommended for full v3)**: disallow external-server adoption whenever activating a runtime variant (GPU plan). If an external server is detected on the port, fail activation with an explicit message (“stop external llama-server first”).
- **Option B**: keep external adoption for CPU-only/legacy flows, but when `disallowExternalInferenceServers=true`, treat external servers as a hard block (do not attach).
- **Option C**: always disallow external adoption globally (simplest policy posture, but may impact developer workflows).

## 6. GPU Booster Pack import workflow

### 6.1 Trust posture (allowlist-only)

Because the pack contains executables:

- The pack must be **allowlisted by manifest digest** (app allowlist and/or policy allowlist; machine policy is authoritative when present).
- Installation must be fail-closed and must reject extra files.

See: `docs/explanation/14-ai-pack-spec.md` and `docs/explanation/15-enterprise-policy.md`.

### 6.2 Install vs activate (separate concerns)

- **Install**: import a pack and lay down files under a new runtime variant directory (never mutate the CPU baseline runtime).
- **Activate**: switch the explicit runtime pointer (e.g., `UiSettings.serverExecutablePath`) to the new variant and restart Online AI.

Rules:

- A pack can be **installed** even if hardware is incompatible/unknown (useful for staging), but it must not be auto-activated.
- Auto-activation requires: policy allows Online AI, hardware gate passes (driver + VRAM), and GPU self-test passes.

### 6.3 Shadow install + self-test

Workflow:

1. Import pack → validate allowlist + hashes.
2. Install runtime into a **new variant directory** (do not modify CPU runtime).
3. Run a bounded **GPU self-test**:
   - CLI contract (llama.cpp `b8157`, upstream):
     - `-ngl` / `--gpu-layers` / `--n-gpu-layers`: GPU offload (0 = CPU)
     - `-ctk` / `--cache-type-k` and `-ctv` / `--cache-type-v`: KV cache types (for 8GB-class cards we currently recommend `q4_0` / `q4_0`)
     - `--list-devices`: prints names like `CUDA0`
     - `-dev` / `--device <name>`: selects a device by name as printed by `--list-devices`
   - start `llama-server` with `-ngl 1` (or small nonzero), load a small model, complete a tiny prompt
   - confirm the server is usable:
     - `GET /health` returns 200
     - a tiny `/v1/chat/completions` request succeeds
   - confirm GPU offload (Windows/WDDM reality: per-process memory attribution is often unavailable):
     - **Primary**: total VRAM “delta”
       - sample `used` VRAM several times before model load (e.g., 5 samples over ~5s) and take the median
       - sample `used` VRAM several times after model load / first request and take the median
       - require median(after) - median(before) >= `64 MiB`
       - if baseline is highly noisy (e.g., swings > `64 MiB` during baseline), treat the result as **inconclusive** and fail auto-activation (user can retry with fewer GPU workloads running)
         - empirical baseline noise example (Windows desktop, RTX 4070): ~`42 MiB` range over ~5s
     - **Best-effort (optional)**: per-process attribution
       - obtain the started server PID
       - query `nvmlDeviceGetComputeRunningProcesses_v2` (or `nvidia-smi --query-compute-apps=pid,used_gpu_memory`) and look for the PID
       - if `usedGpuMemory` is available and nonzero, require it is above a small floor (recommend `>= 16 MiB`)
        - if the API returns “unknown” / `N/A` / not supported, treat as inconclusive and fall back to VRAM delta
          - empirical note: on a consumer Windows desktop (WDDM), `nvidia-smi --query-compute-apps=pid,used_gpu_memory` can return `[N/A]` for `used_gpu_memory`
   - (Optional best-effort) confirm via logs. Do not hard-require specific log strings because they can change between upstream releases.
4. If self-test passes: set active runtime pointer to the new variant.
5. If self-test fails: keep CPU active and record diagnostics.

### 6.4 Rollback semantics

- Prevent silent downgrades, but allow explicit rollback when needed.
- Keep a last-known-good runtime variant for recovery.

## 7. Verification reality (CI and testing)

### 7.1 User-facing verification (UI + logs)

- **GPU capability snapshot**: `GET /api/gpu/capabilities` should reflect the machine’s NVIDIA driver + VRAM when available (NVML-first).
- **Runtime packs**: import a runtime pack via `/api/ai/packs/*` and confirm it appears in `GET /api/ai/packs/installed`.
- **Activation**: activate a variant via `POST /api/ai/runtime/activate` and confirm `GET /api/ai/runtime/status`.
- **Log proof of GPU offload** (authoritative):
  - Open `<AI_HOME>/logs/llama-server.log` and find the most recent model load.
  - Confirm the CUDA backend loads and offload is reported, e.g.:
    - `load_backend: loaded CUDA backend from ...\\ggml-cuda.dll`
    - `load_tensors: offloaded X/Y layers to GPU`
  - This is also the easiest way to confirm that `gpuLayers`/`-ngl` is being applied.

- Windows Sandbox is not a reliable CUDA oracle.
- A real NVIDIA test machine (self-hosted CI runner or dedicated validation machine) is required to certify a GPU Booster Pack release.

## 8. Legal notes (CUDA redistributables)

CUDA Toolkit EULA Attachment A lists redistributable components, including:

- CUDA Runtime (`cudart*`)
- CUDA BLAS (`cublas*`, `cublasLt*`)

Any GPU Booster Pack that includes these DLLs must comply with NVIDIA’s EULA (including “Distribution Requirements”; i.e., not distributing the SDK as a stand-alone product).

JustSearch policy (to make audits practical):

- GPU Booster Packs that include CUDA DLLs MUST ship `NOTICE-NVIDIA-CUDA.txt` adjacent to the installed DLLs in the runtime variant directory, containing:
  - EULA + Attachment A URLs
  - the exact redistributed DLL list
  - the JustSearch variant id (e.g., `cuda-12.4`) and minimum driver gate used by the app

See also: `docs/reference/legal/ai-runtime-and-model-redistribution.md`.


## 9. Hardening notes & long-tail risks

Long-term considerations include:

- **NVML variability**: GPU detection may vary across driver versions
- **Multi-GPU**: Currently assumes single-GPU; multi-GPU arbitration is not implemented
- **Log growth**: llama-server logs should be rotated in production
- **Lifecycle edge cases**: Crash recovery relies on process-exit detection; zombie processes may require manual cleanup

