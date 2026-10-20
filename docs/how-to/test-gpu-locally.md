---
title: Test GPU Inference Locally
type: how-to
status: stable
description: "How to run llama-server with GPU offload for local development and testing."
---

# Test GPU Inference Locally

## Prerequisites

- NVIDIA GPU with CUDA compute capability 7.0+ (e.g., RTX 2060 or newer)
- Windows: use **Install AI** on supported NVIDIA hardware to install the CUDA runtime under
  `%APPDATA%\io.justsearch.shell\native-bin\llama-server\variants\cuda12\`.
  A development build staged with the CUDA variant uses
  `modules/ui/build/llama-server/stage/variants/cuda12/` instead; no Gradle task mirrors it
  into `modules/ui/native-bin`.
- Linux: provide a CUDA-enabled `llama-server` built from the pinned llama.cpp version `b8571`,
  set `JUSTSEARCH_SERVER_EXE` to its executable path, and make its shared libraries available
  to the loader. The Windows runtime downloaded by Install AI is not used on Linux.
- Provide a local chat GGUF model. The examples below use the model downloaded by Install AI.

## Preflight Check

The repo preflight command is:

```bash
node scripts/verify-prerequisites.mjs
```

It is intended to check model files, runtime layout, and GPU visibility, but currently refers to
`VramDetector.java` at its retired path and checks only `llama-server.exe`. It therefore reports
failures even with a usable runtime (especially on Linux). Keep the failure visible; this command
is awaiting a separate script fix and is not evidence of GPU readiness. Validate the executable
and actual GPU offload below.

## Method 1: Standalone llama-server (quick validation)

Launch the CUDA variant directly to verify GPU offload works. On Windows (PowerShell):

```powershell
& "$env:APPDATA\io.justsearch.shell\native-bin\llama-server\variants\cuda12\llama-server.exe" `
  -m "$env:APPDATA\io.justsearch.shell\models\Qwen_Qwen3.5-9B-Q4_K_M.gguf" `
  --jinja -ngl 99 --host 127.0.0.1 --port 8086
```

On Linux (Bash), after setting `JUSTSEARCH_SERVER_EXE` to your CUDA build:

```bash
"$JUSTSEARCH_SERVER_EXE" \
  -m "${XDG_DATA_HOME:-$HOME/.local/share}/io.justsearch.shell/models/Qwen_Qwen3.5-9B-Q4_K_M.gguf" \
  --jinja -ngl 99 --host 127.0.0.1 --port 8086
```

For a dev stack, substitute its model path and, on Windows, its staged executable path.

Check stderr for these lines to confirm GPU offload:
- `ggml_cuda_init: found N CUDA devices` — CUDA backend loaded
- `offloaded N/N layers to GPU` — model layers on GPU

## Method 2: System property override (full app)

Pass the CUDA variant path as a system property when launching the app:

```text
-Djustsearch.server.exe=<absolute-path-to-CUDA-llama-server>
-Djustsearch.gpu.layers=99
```

Use `llama-server.exe` on Windows and `llama-server` on Linux. The equivalent executable override
is `JUSTSEARCH_SERVER_EXE`; `JUSTSEARCH_GPU_LAYERS=99` sets the layer count. These configure the managed
server; the app still needs valid model/runtime settings and activation. `justsearch.server.exe`
overrides executable discovery in `InferenceConfig`. See `docs/explanation/13-ai-setup-and-verification.md` section 3.3 for how variant selection works.

## Troubleshooting

- **No CUDA devices found:** Verify your NVIDIA driver supports CUDA 12.4+ (`nvidia-smi` shows driver version)
- **Windows only — missing DLL errors (exit code 0xC0000135):** The CUDA variant bundles its own runtime DLLs. If they're missing, re-extract or re-download the variant.
- **Layers show as `CPU_Mapped`:** You're running the CPU variant, not the CUDA variant. Check which exe is being used in the logs.
- **ORT session creation fails after NVIDIA driver upgrade:** The Engine stores derived optimized graphs in one per-machine store, keyed by model SHA-256, ORT version, execution provider and optimization level. The key does not include the CUDA driver version. If a driver upgrade triggers a loud `createSession` exception, stop the Engine and delete the affected CUDA entry directory (the graph and `entry.json` together), then restart. The default Windows store is `%LOCALAPPDATA%\JustSearch\cache\ort-optimized`; CUDA entries are under `<ortVersion>/cuda-EXTENDED_OPT/<sha256>/`. If configured, use the root from `JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR` / `-Djustsearch.ort.optimized_cache_dir` (YAML `ort.optimized_cache_dir`) instead. See [optimized graph ownership](../explanation/05-ai-architecture.md) for other platforms and the 16 GiB default cap.

  Optimized entries are safe to delete: they regenerate from the source model on next session creation. Legacy `<model>.cuda.optimized` / `.cuda.opt-meta` siblings are no longer written; session loading attempts to remove the four exact legacy CPU/CUDA sibling names, refusing links and directories.

## Further reading

- GPU Booster Pack architecture: `docs/explanation/16-gpu-booster-pack.md`
- Runtime variant selection: `docs/explanation/13-ai-setup-and-verification.md` section 3.3
- Environment variables: `docs/reference/configuration/environment-variables.md`
