---
title: Test GPU Inference Locally
type: how-to
status: stable
description: "How to run llama-server with GPU offload for local development and testing."
---

# Test GPU Inference Locally

## Prerequisites

- NVIDIA GPU with CUDA compute capability 7.0+ (e.g., RTX 2060 or newer)
- CUDA variant present at `modules/ui/native-bin/llama-server/variants/cuda12/`

## Preflight Check (Recommended)

Run the repo preflight before GPU testing:

```bash
node scripts/verify-prerequisites.mjs
```

This verifies model files, native runtime layout, and GPU visibility expected by the AI workflows.

## Method 1: Standalone llama-server (quick validation)

Launch the CUDA variant directly to verify GPU offload works:

```bash
modules/ui/native-bin/llama-server/variants/cuda12/llama-server.exe \
  -m models/<your-model>.gguf \
  --jinja -ngl 99 --host 127.0.0.1 --port 8086
```

Check stderr for these lines to confirm GPU offload:
- `ggml_cuda_init: found N CUDA devices` — CUDA backend loaded
- `offloaded N/N layers to GPU` — model layers on GPU

## Method 2: System property override (full app)

Pass the CUDA variant path as a system property when launching the app:

```text
-Djustsearch.server.exe=modules/ui/native-bin/llama-server/variants/cuda12/llama-server.exe
-Djustsearch.gpu.layers=99
```

This overrides `InferenceConfig.findServerExecutable()` without needing the UI activation flow. See `docs/explanation/13-ai-setup-and-verification.md` section 3.3 for how variant selection works.

## Troubleshooting

- **No CUDA devices found:** Verify your NVIDIA driver supports CUDA 12.4+ (`nvidia-smi` shows driver version)
- **Missing DLL errors (exit code 0xC0000135):** The CUDA variant bundles its own runtime DLLs. If they're missing, re-extract or re-download the variant.
- **Layers show as `CPU_Mapped`:** You're running the CPU variant, not the CUDA variant. Check which exe is being used in the logs.
- **ORT session creation fails after NVIDIA driver upgrade:** The Engine stores derived optimized graphs in one per-machine store, keyed by model SHA-256, ORT version, execution provider and optimization level. The key does not include the CUDA driver version. If a driver upgrade triggers a loud `createSession` exception, stop the Engine and delete the affected CUDA entry directory (the graph and `entry.json` together), then restart. The default Windows store is `%LOCALAPPDATA%\JustSearch\cache\ort-optimized`; CUDA entries are under `<ortVersion>/cuda-EXTENDED_OPT/<sha256>/`. If configured, use the root from `JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR` / `-Djustsearch.ort.optimized_cache_dir` (YAML `ort.optimized_cache_dir`) instead. See [optimized graph ownership](../explanation/05-ai-architecture.md) for other platforms and the 16 GiB default cap.

  Optimized entries are safe to delete: they regenerate from the source model on next session creation. Legacy `<model>.cuda.optimized` / `.cuda.opt-meta` siblings are no longer written; session loading attempts to remove the four exact legacy CPU/CUDA sibling names, refusing links and directories.

## Further reading

- GPU Booster Pack architecture: `docs/explanation/16-gpu-booster-pack.md`
- Runtime variant selection: `docs/explanation/13-ai-setup-and-verification.md` section 3.3
- Environment variables: `docs/reference/configuration/environment-variables.md`
