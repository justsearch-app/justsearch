# Native names and the Linux runtime

`spec.v1.json` supplies executable, library and ORT resource names for each supported
OS/architecture. Java packages these exact bytes; Node reads the repository file;
the desktop shell embeds the same file. A registry package with `nativeTarget`
contains artifacts for that descriptor target. An absent target means portable
model data. Windows runtime archives are excluded from Linux install plans.

## Linux x86_64 CUDA llama-server

Use a Linux x86_64 CUDA build of llama.cpp's `llama-server`. Build it from a
llama.cpp source checkout with the CUDA toolkit and a C/C++ build toolchain:

```sh
cmake -S /path/to/llama.cpp -B /path/to/llama.cpp/build -DGGML_CUDA=ON
cmake --build /path/to/llama.cpp/build --config Release --target llama-server -j
```

Alternatively, unpack a compatible Linux CUDA distribution outside JustSearch's
managed Windows runtime directories. Keep its shared libraries beside the
executable as provided by that distribution. Mark only the executable runnable,
then check its dependencies and startup:

```sh
chmod u+x /absolute/path/to/llama-server
ldd /absolute/path/to/llama-server
/absolute/path/to/llama-server --version
export JUSTSEARCH_SERVER_EXE=/absolute/path/to/llama-server
export JUSTSEARCH_GPU_ENABLED=true
```

Resolve every `not found` dependency before launching JustSearch. The llama.cpp
CUDA build must match the installed NVIDIA driver/toolkit libraries. ORT has its
own CUDA dependencies: expose the compatible CUDA 12/cuDNN 9 libraries to the
Engine's dynamic loader (for example through `LD_LIBRARY_PATH`), and set
`JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH` when using an external ORT native directory.
The installed application includes the ORT Linux native resources.

Start JustSearch with that environment and the downloaded/imported models. Activate
variant `default` with chat profile `compact` or `standard` through the AI runtime
API or its UI. An omitted/default selection uses the exact configured executable;
`cuda12` remains a Windows pack selection and is refused on Linux. A missing,
directory or non-executable override is refused rather than replaced with another
runtime. Development inference must stay on the GPU.

Deactivation disables chat when no CPU baseline exists. It preserves the runtime
path and GPU settings, so activate `default` again to resume. The saved profile and
settings survive an Engine restart; keep the operator environment override set
on every launch. To replace the runtime, deactivate and stop the Engine, install
the replacement with its matching libraries, validate it as above, update
`JUSTSEARCH_SERVER_EXE` if its path changed, then restart and activate `default`.
Windows activation, deactivation and runtime-pack layouts retain their existing
behavior.
