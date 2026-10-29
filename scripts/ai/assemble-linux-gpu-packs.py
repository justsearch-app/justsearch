#!/usr/bin/env python3
"""Assemble Linux x86_64 GPU packs without modifying inputs or installing host packages.

Inputs: llama.cpp b8571 (CUDA 12, built on Ubuntu 24.04), CUDA >=12.8 and cuDNN 9
redistribution directories, the four Linux natives extracted from ORT 1.24.3's GPU
JAR, and their notices. Requires Python 3, patchelf, readelf, ldd. No downloads.

Example:
  python3 scripts/ai/assemble-linux-gpu-packs.py --llama-dir build/bin \
    --cuda-dir cuda/lib --cudnn-dir cudnn/lib --ort-dir ort-linux-x64 \
    --notices licenses --output dist/linux-gpu

--test-only permits host glibc/CUDA builds for a host-only journey. Such ZIPs are
named TEST-ONLY and carry a marker; they are never release inputs. Output JSON
records sizeBytes and sha256 for the registry. No symlinks are written to ZIPs.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

SYSTEM_LIBS = re.compile(r"^(?:ld-linux-x86-64\.so\.2|lib(?:c|m|dl|rt|pthread|resolv|util)\.so\.[0-9]+|libstdc\+\+\.so\.6|libgcc_s\.so\.1|libcuda\.so\.1|libnvidia-[\w-]+\.so\.[\d.]+)$")
ORT_NAMES = ("libonnxruntime.so", "libonnxruntime4j_jni.so", "libonnxruntime_providers_shared.so", "libonnxruntime_providers_cuda.so")
CUDA_NAMES = ("libcudart.so.12", "libcublas.so.12", "libcublasLt.so.12", "libcufft.so.11",
              "libnvJitLink.so.12")
CUDNN_NAMES = tuple("libcudnn" + suffix + ".so.9" for suffix in
                    ("", "_graph", "_heuristic", "_ops", "_cnn", "_adv", "_engines_runtime_compiled", "_engines_precompiled"))


def run(*args, env=None):
    result = subprocess.run(args, env=env, text=True, capture_output=True, check=True)
    return result.stdout


def elf(path):
    with path.open("rb") as source:
        return source.read(4) == b"\x7fELF"


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def copy_elf(source, dest, patchelf, identities=None, only_names=None):
    """Flatten ELF inputs, materializing SONAME aliases as regular files."""
    dest.mkdir(parents=True, exist_ok=True)
    if identities is None:
        identities = {}
    for path in sorted(source.rglob("*")):
        if only_names is not None and path.name not in only_names:
            continue
        if "stubs" in path.relative_to(source).parts:
            continue
        if not path.is_file() or not (path.name == "llama-server" or ".so" in path.name) or not elf(path):
            continue
        if path.name.startswith(("libcuda.so", "libnvidia-")):
            raise ValueError(f"Driver libraries must come from the OS, never the pack: {path.name}")
        header = run("readelf", "-h", str(path))
        if "Advanced Micro Devices X86-64" not in header:
            raise ValueError(f"Not an x86_64 ELF: {path}")
        dynamic = run("readelf", "-d", str(path))
        match = re.search(r"\(SONAME\).*\[(.*?)\]", dynamic)
        names = {match[1] if match else path.name}
        # ggml discovers backends by their unversioned dlopen name.
        if path.name.startswith("libggml-") and ".so" in path.name:
            names.add(path.name.split(".so", 1)[0] + ".so")
        for name in names:
            if Path(name).name != name:
                raise ValueError(f"Unsafe SONAME: {name}")
            target = dest / name
            identity = digest(path)
            if target.exists():
                if identities.get(name) != identity:
                    raise ValueError(f"Conflicting SONAME input: {name}")
                continue
            identities[name] = identity
            shutil.copyfile(path, target)
            target.chmod(0o755)
            run(patchelf, "--set-rpath", "$ORIGIN", str(target))


def audit(directory, glibc_floor=(2, 39)):
    """Reject globally supplied CUDA libraries, even if this host's ldd finds them.

    DT_NEEDED must resolve inside the flattened pack or to an explicit OS/driver
    ABI. ldd's cache can expose /opt dependencies; the path check rejects these.
    A second loader pass disables the cache entirely. Absolute NEEDED paths and
    inherited RPATHs are forbidden. All dlopen cuDNN components are checked too.
    """
    directory = directory.resolve()
    env = {key: value for key, value in os.environ.items()
           if not key.startswith("LD_") and key not in ("LIBRARY_PATH", "CUDA_PATH", "CUDA_HOME")}
    env["LC_ALL"] = "C"
    env["LD_LIBRARY_PATH"] = str(directory)
    checked = []
    for path in sorted(directory.iterdir()):
        if not path.is_file() or not elf(path):
            continue
        dynamic = run("readelf", "-d", str(path))
        needed = re.findall(r"\(NEEDED\).*\[(.*?)\]", dynamic)
        for name in needed:
            if "/" in name or (not (directory / name).is_file() and not SYSTEM_LIBS.fullmatch(name)):
                raise ValueError(f"Dependency outside pack: {path.name} needs {name}")
        paths = re.findall(r"\((?:RUNPATH|RPATH)\).*\[(.*?)\]", dynamic)
        if paths != ["$ORIGIN"] or "(RPATH)" in dynamic:
            raise ValueError(f"Non-relocatable RUNPATH: {path.name}: {paths}")
        versions = [tuple(map(int, version.split("."))) for version in
                    re.findall(r"\bGLIBC_(\d+\.\d+(?:\.\d+)?)", run("readelf", "--version-info", str(path)))]
        if versions and glibc_floor is not None and max(versions) > glibc_floor:
            raise ValueError(f"glibc floor exceeded: {path.name}: {max(versions)} > {glibc_floor}")
        output = run("ldd", str(path), env=env)
        if "not found" in output:
            raise ValueError(f"Incomplete closure for {path.name}:\n{output}")
        for name, location in re.findall(r"^\s*(\S+)\s+=>\s+(\S+)", output, re.M):
            resolved = Path(location).resolve()
            if resolved.parent != directory:
                if not SYSTEM_LIBS.fullmatch(name) or not str(resolved).startswith(("/usr/lib/", "/lib/", "/lib64/", "/usr/lib64/")):
                    raise ValueError(f"ldd escaped pack: {path.name}: {name} => {location}")
        # Locate the system loader from ldd, without consulting /etc/ld.so.conf.d.
        loader = next((p for p in ("/lib64/ld-linux-x86-64.so.2", "/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2")
                       if Path(p).is_file()), None)
        if loader is None:
            raise ValueError("No x86_64 system loader")
        run(loader, "--inhibit-cache", "--library-path", str(directory), "--list", str(path), env=env)
        checked.append(path.name)
    if not checked:
        raise ValueError("No ELF files to audit")
    return checked


def archive(directory, output):
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as target:
        for path in sorted(directory.rglob("*")):
            if path.is_file():
                if path.is_symlink():
                    raise ValueError(f"Symlink in output: {path}")
                info = zipfile.ZipInfo(path.relative_to(directory).as_posix(), (2026, 1, 1, 0, 0, 0))
                info.external_attr = (0o100755 if elf(path) else 0o100644) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                with path.open("rb") as source, target.open(info, "w", force_zip64=True) as sink:
                    shutil.copyfileobj(source, sink, 1024 * 1024)
    return {"filename": output.name, "sizeBytes": output.stat().st_size, "sha256": digest(output)}


def assemble(args):
    args.output.mkdir(parents=True, exist_ok=True)
    if not args.notices.is_dir() or not any(args.notices.rglob("*")):
        raise ValueError("Notices directory must contain the redistribution licenses")
    with tempfile.TemporaryDirectory(prefix="linux-gpu-", dir=args.output) as work:
        work = Path(work)
        llama, cuda, combined = (work / name for name in ("llama", "cuda", "audit"))
        copy_elf(args.llama_dir, llama, args.patchelf)
        identities = {}
        copy_elf(args.cuda_dir, cuda, args.patchelf, identities)
        copy_elf(args.cudnn_dir, cuda, args.patchelf, identities)
        required = [llama / "llama-server", *(cuda / name for name in (*CUDA_NAMES, *CUDNN_NAMES))]
        if not any(llama.glob("libggml-cuda.so*")):
            raise ValueError("Missing llama.cpp CUDA backend")
        for path in required:
            if not path.is_file():
                raise ValueError(f"Missing required payload: {path.name}")
        for source in (llama, cuda):
            for path in source.iterdir():
                target = combined / path.name
                combined.mkdir(exist_ok=True)
                if target.exists() and digest(target) != digest(path):
                    raise ValueError(f"Conflicting native filename: {path.name}")
                shutil.copyfile(path, target)
                target.chmod(0o755)
        for name in ORT_NAMES:
            if not (args.ort_dir / name).is_file():
                raise ValueError(f"Missing ORT 1.24.3 audit input: {name}")
        copy_elf(args.ort_dir, combined, args.patchelf, only_names=ORT_NAMES)
        if not args.test_only:
            for path in combined.iterdir():
                if re.search(r"\(NEEDED\).*\[lib(?:cudart|cublas|cublasLt)\.so\.13\]", run("readelf", "-d", str(path))):
                    raise ValueError(f"Release input uses CUDA 13: {path.name}")
        checked = audit(combined, None if args.test_only else (2, 39))
        # Query the exact copied runtime, never a host toolkit directory. This API does not
        # initialize a device. A subprocess keeps the assembler free of native-library state.
        cuda_version = int(run(sys.executable, "-c",
            "import ctypes,sys; lib=ctypes.CDLL(sys.argv[1]); v=ctypes.c_int(); "
            "rc=lib.cudaRuntimeGetVersion(ctypes.byref(v)); "
            "sys.exit(rc) if rc else print(v.value)", str(combined / "libcudart.so.12")).strip())
        if not 12080 <= cuda_version < 13000:
            raise ValueError(f"CUDA runtime must be 12.8+: {cuda_version}")
        # llama dlopens its adjacent backends. Every ELF's RUNPATH is $ORIGIN;
        # dependencies ZIP extracts into this same directory (as on Windows).
        (llama / "runtime-version.txt").write_text("b8571\n")
        (cuda / "linux-gpu-pack.json").write_text(json.dumps({
            "testOnly": args.test_only, "llamaVersion": "b8571", "ortVersion": "1.24.3",
            "cudaRuntimeVersion": cuda_version, "cudnn": "9", "minimumNvidiaDriver": "570",
            "maximumGlibc": None if args.test_only else "2.39", "auditedElfFiles": checked,
        }, indent=2) + "\n")
        shutil.copytree(args.notices, llama / "notices", symlinks=False)
        prefix = "TEST-ONLY-" if args.test_only else ""
        names = ((llama, "llama-b8571-linux-cuda12-x86_64.zip"),
                 (cuda, "ort-cuda12-cudnn9-linux-x86_64.zip"))
        artifacts = [archive(source, args.output / (prefix + name)) for source, name in names]
        result = {"testOnly": args.test_only, "artifacts": artifacts}
        (args.output / (prefix + "registry-artifacts.json")).write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for name in ("llama-dir", "cuda-dir", "cudnn-dir", "ort-dir", "notices", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--patchelf", default="patchelf")
    parser.add_argument("--test-only", action="store_true")
    args = parser.parse_args()
    try:
        assemble(args)
    except (ValueError, OSError, subprocess.CalledProcessError) as failure:
        parser.exit(1, f"Linux GPU pack assembly failed: {failure}\n")


if __name__ == "__main__":
    main()
