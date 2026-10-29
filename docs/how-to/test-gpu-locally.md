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
- Linux: the shipped registry currently has no Linux GPU packs, so Install AI offers CPU search
  and shows chat unavailable. For an operator-managed runtime, provide a CUDA-enabled
  `llama-server` built from pinned llama.cpp `b8571`, set `JUSTSEARCH_SERVER_EXE`, and make its
  shared libraries available to the loader. To exercise pack installation, use the test recipe below.
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

## Linux GPU pack build and assembly

The app supports Linux runtime packs, but none are registered in the shipped registry yet.
The pinned producer builds llama.cpp **b8571 against CUDA 12.8.2**, with cuDNN **9.8.0**
and ORT **1.24.3**, on **Ubuntu 24.04** (glibc ceiling 2.39). It does not need a GPU.
The source URLs, versions and SHA-256 values live in
[`linux-gpu-inputs.v1.json`](../../scripts/ai/linux-gpu-inputs.v1.json); NVIDIA inputs come only
from NVIDIA's official redistrib channel. A hash-pinned Ubuntu zlib runtime and its license
are also bundled because cuDNN links to `libz.so.1`. CUDA NVRTC and its builtins are
included for cuDNN's runtime-compiled engines, which load NVRTC dynamically. On an Ubuntu 24.04 machine with Python 3.12,
GCC/G++ 13, CMake, Ninja, binutils and patchelf already available:

```bash
python3 scripts/ai/build-linux-gpu-packs.py --work /scratch/linux-gpu-build \
  --output /scratch/linux-gpu-output --jobs 2
```

This is the same command used by **Build Linux GPU Packs** (`build-linux-gpu-packs.yml`).
Reuse the work folder after a failed build: every downloaded archive is checked again;
completed extraction and compilation are reused. Keep each checkout's work folder private.
The output folder must not contain previous ZIPs. `build-io.txt` records kernel block IO,
including waited-for subprocesses, for the local SSD budget. No host package installation
or publishing occurs in this script.

To assemble pre-existing inputs directly:

```bash
python3 scripts/ai/assemble-linux-gpu-packs.py \
  --llama-dir /inputs/llama/bin --cuda-dir /inputs/cuda/lib \
  --cudnn-dir /inputs/cudnn/lib --ort-dir /inputs/ort-linux-x64 \
  --notices /inputs/licenses --input-manifest /inputs/verified-inputs.json \
  --output /output/linux-gpu
```

Inputs must include their redistribution notices. Required tools are Python 3, `patchelf`,
`readelf`, and `ldd`; tests also use `gcc`. `--patchelf /path/to/patchelf` selects an existing
local tool. The pack workflow installs `patchelf` on its Ubuntu runner with
`sudo apt-get update && sudo apt-get install -y patchelf`. For local assembler tests without
a host installation, install the PyPI wheel into a fresh scratch directory and expose its
executable only to the test command:

```bash
python3 -m pip install --target "$TMPDIR/patchelf-wheel" patchelf
PATH="$TMPDIR/patchelf-wheel/bin:$PATH" \
  python3 -m unittest discover -s scripts/ai -p 'test_assemble_linux_gpu_packs.py'
```

The assembler writes each patched input once, hardlinks its audit view and aliases, and streams
ZIP creation. It materializes SONAMEs and ggml backend aliases as
regular ZIP entries, rewrites RUNPATH to `$ORIGIN`, and audits the combined dependency closure
with `readelf`, `ldd`, and the loader with its cache disabled. Driverless build runners
use the pinned CUDA linker's `libcuda.so.1` stub only for that OS driver ABI during auditing;
the stub is never packaged. Actual driver/GPU execution is a separate host check. It rejects dependencies outside
the pack except the explicit OS/driver ABI allowlist, CUDA 13 release inputs, and GLIBC symbols
above **2.39**. Both ZIPs extract into the same `cuda12` directory. ORT natives are audit inputs;
the Engine's GPU ORT JAR continues to supply ORT itself.

Each ZIP includes NVIDIA's redistribution notices and `linux-gpu-pack.json`, recording
every input's URL and SHA-256, compiler versions, CUDA architectures and audit results.
The workflow uploads both ZIPs, `SHA256SUMS`, `SIZES`, provenance and generated registry
JSON to a **draft** release of `eliasjustus/justsearch-releases`. The owner publishes the
exact draft after validation; agents do not promote it automatically. See
[the release procedure](cut-a-release.md#linux-gpu-pack-procedure).

Generate the registry PR from a run's downloaded output:

```bash
python3 scripts/ai/update-linux-gpu-registry.py --tag linux-cuda12-1 \
  --checksums /output/linux-gpu/SHA256SUMS --sizes /output/linux-gpu/SIZES \
  --output modules/configuration/src/main/resources/ai/model-registry.v2.json
```

The helper validates both exact filenames, hashes, positive sizes and tag syntax. It preserves
Windows entries and creates the distinct `cuda-runtime-linux` package with
`replaces: cuda-runtime` and `nativeTarget: linux-x86_64`. Its final release URLs become
publicly downloadable only when the draft is published. Do not merge the registry update
before that publication. The loader rewrites dependencies to the current target's package
ID before planning; unsupported targets apply no replacements and exclude native packages.
Linux GPU selection requires NVIDIA driver **570 or newer**.

## Host-only Install AI check with a registry override

Use existing input directories and a disposable application distribution and state directory.
Host-built binaries needing a newer glibc or extra CUDA-major libraries are suitable only for
this check. Supply every such dependency among the inputs; the closure audit still applies.
Add `--test-only` to the assembly command. It emits `TEST-ONLY-` ZIPs and
`TEST-ONLY-registry-artifacts.json`, and labels the pack metadata. Never publish these outputs.

Install AI reads `AiInstallService.getManifest()` → `ModelRegistryLoader.loadFromClasspath()` →
**`ai/model-registry.v2.json` using the configuration class's own classloader**. There is no
registry environment variable or filesystem override. For this check, override that resource
inside a **disposable copy** of the configuration JAR:

1. Copy a prepared Linux distribution (for example `modules/ui/build/install/ui`) into a temporary
   directory. Keep the source distribution unchanged. Do not start the dev stack manually;
   dev-stack runs require the justsearch-dev MCP lease workflow.
2. Copy the shipped registry to `<work>/override/ai/model-registry.v2.json`. Append the Linux
   package described above, using the two TEST-ONLY artifact hashes and sizes. Set each URL to
   `http://127.0.0.1:8769/<filename>`. Keep the existing model entries so search and chat can be
   installed normally. The small fixtures under configuration/app-services `src/test/resources/ai/`
   demonstrate the `replaces` relationship; their example URLs and tiny model bytes are not live inputs.
3. Serve only the output directory: `python3 -m http.server 8769 --bind 127.0.0.1 --directory /output/linux-gpu`.
   Record its PID and stop that process after the check. Use an unused port if necessary and match
   the registry URLs to it.
4. Locate the configuration JAR in the **copied** distribution's `lib/` (its `jar --list --file`
   output includes `ai/model-registry.v2.json`). Replace only that copy's resource:

   ```bash
   jar --update --file /work/distribution/lib/configuration-VERSION.jar \
     -C /work/override ai/model-registry.v2.json
   ```

5. Launch the copied distribution with fresh `JUSTSEARCH_HOME` and `JUSTSEARCH_DATA_DIR` pointing
   to the same private state directory, private XDG roots, an unused `JUSTSEARCH_API_PORT`, and
   `JUSTSEARCH_MODE=full-desktop`. Scrub inherited `JUSTSEARCH_SERVER_EXE`,
   `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH`, `JUSTSEARCH_NATIVE_PATH`, `LD_LIBRARY_PATH`,
   `LD_PRELOAD`, and Java option executable/native-path overrides. Leave GPU acceleration enabled.
   See [headless launch](headless-runtime.md) and the
   [local API contract](../reference/api-contract-map.md#ai-install-apiaiinstall) for launch/auth details.
6. Confirm `GET /api/ai/install/manifest` contains `cuda-runtime-linux` with the local URLs.
   The plan preview should be `GPU_FULL` and include chat and that runtime. Run **Install AI**
   (`POST /api/ai/install/start`, `{"acceptTerms":true}`), wait for completion, and use **Start AI**
   to activate. Check that the executable selected is
   `<state>/native-bin/llama-server/variants/cuda12/llama-server`.
7. Index a small source document and ask a question it answers. Require an answer with citations,
   successful activation, and llama logs confirming layers offloaded to GPU. Inspect
   `/proc/<engine-pid>/maps` and `/proc/<llama-pid>/maps`: every CUDA/cuDNN library must resolve
   under the installed pack directory, never this host's globally configured `/opt` paths.
   Restart the copied Engine against the same state and repeat; delete only its downloaded
   executable, run **Repair**, and check activation again. Stop only the Engine/server processes
   you started, using their recorded PIDs.

For ORT's CPU-before-CUDA case, extract **both** test ZIPs into one fresh directory and run this
normal JUnit test alone (the UI module uses the Engine's GPU ORT dependency):

```bash
JUSTSEARCH_LINUX_GPU_PACK_DIR=/absolute/path/to/extracted-pack \
  js-gradle-slot ./gradlew :modules:ui:test --tests '*LinuxOrtAfterCpuTest' -PskipWebBuild=true
```

It executes a real CPU session, then preloads the pack dependencies without changing ORT's
one-shot native-path property, executes a CUDA kernel in the same JVM, checks the profiler's
execution provider, and verifies CUDA/cuDNN mappings are pack-owned. This path does **not**
restart the Engine. The test is skipped when the pack variable is absent; a skip is not GPU
verification. The ordinary `OrtCudaHelperTest` also checks that Linux preloads nvJitLink before
sparse/solver libraries. Check assembler fixtures with
`python3 -m unittest discover -s scripts/ai -p 'test_assemble_linux_gpu_packs.py'` (use `PATCHELF`
to select an existing tool).

## Troubleshooting

- **No CUDA devices found:** Verify your NVIDIA driver is 570 or newer on Linux and supports CUDA 12.4+ on Windows (`nvidia-smi` shows driver version)
- **Windows only — missing DLL errors (exit code 0xC0000135):** The CUDA variant bundles its own runtime DLLs. If they're missing, re-extract or re-download the variant.
- **Layers show as `CPU_Mapped`:** You're running the CPU variant, not the CUDA variant. Check which exe is being used in the logs.
- **ORT session creation fails after NVIDIA driver upgrade:** The Engine stores derived optimized graphs in one per-machine store, keyed by model SHA-256, ORT version, execution provider and optimization level. The key does not include the CUDA driver version. If a driver upgrade triggers a loud `createSession` exception, stop the Engine and delete the affected CUDA entry directory (the graph and `entry.json` together), then restart. The default Windows store is `%LOCALAPPDATA%\JustSearch\cache\ort-optimized`; CUDA entries are under `<ortVersion>/cuda-EXTENDED_OPT/<sha256>/`. If configured, use the root from `JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR` / `-Djustsearch.ort.optimized_cache_dir` (YAML `ort.optimized_cache_dir`) instead. See [optimized graph ownership](../explanation/05-ai-architecture.md) for other platforms and the 16 GiB default cap.

  Optimized entries are safe to delete: they regenerate from the source model on next session creation. Legacy `<model>.cuda.optimized` / `.cuda.opt-meta` siblings are no longer written; session loading attempts to remove the four exact legacy CPU/CUDA sibling names, refusing links and directories.

## Further reading

- GPU Booster Pack architecture: `docs/explanation/16-gpu-booster-pack.md`
- Runtime variant selection: `docs/explanation/13-ai-setup-and-verification.md` section 3.3
- Environment variables: `docs/reference/configuration/environment-variables.md`
