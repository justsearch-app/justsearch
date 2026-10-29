#!/usr/bin/env python3
"""Build S13's pinned Linux CUDA12 packs; same entry point locally and on ubuntu-24.04.

Requires Python >=3.12, GCC/G++ 13, CMake, Ninja, patchelf and binutils. No sudo,
package installs, GPU access, or publishing. Keep --work across retries: verified
archives, extracted inputs and the incremental build are reused. Each checkout
must have its own work directory. Outputs must not already exist.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent
PINS = HERE / "linux-gpu-inputs.v1.json"
ARCHITECTURES = "70-virtual;75-real;80-real;86-real;89-real;90-real;100-real;120a-real"


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def fetch(item, cache):
    """Never consume unverified bytes, including from a previous invocation."""
    dest = cache / (item["id"] + "-" + item["sha256"][:16])
    if not dest.exists():
        partial = dest.with_suffix(".part")
        print("Fetch " + item["url"], flush=True)
        with urllib.request.urlopen(item["url"], timeout=120) as source, partial.open("wb") as sink:
            shutil.copyfileobj(source, sink, 1024 * 1024)
        if digest(partial) != item["sha256"]:
            partial.unlink()
            raise ValueError("SHA-256 mismatch: " + item["id"])
        partial.rename(dest)
    if digest(dest) != item["sha256"]:
        raise ValueError("Cached SHA-256 mismatch: " + item["id"])
    return dest


def unpack(archive, dest, item):
    marker = dest / ".sha256"
    if marker.exists() and marker.read_text() == item["sha256"]:
        return
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    with tarfile.open(archive, "r|*") as source:
        # NVIDIA ships large static archives too. Only cudart/nvcc use those to
        # compile; other static libraries never enter a runtime pack or this build.
        for member in source:
            if item["id"] not in ("cuda_nvcc", "cuda_cudart", "llama") and member.name.endswith(".a"):
                continue
            source.extract(member, dest, filter="data")
    marker.write_text(item["sha256"])


def link_tree(source, dest):
    """Merge compiler components with links; no second toolkit copy."""
    for path in sorted(source.rglob("*")):
        relative = path.relative_to(source)
        target = dest / relative
        if path.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        elif not target.is_symlink() and not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            target.symlink_to(path.resolve())
        elif target.resolve() != path.resolve() and digest(target) != digest(path):
            raise ValueError("Conflicting toolkit file: " + str(relative))


def command(*args, env=None):
    print("+ " + " ".join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), check=True, env=env)


def build(args):
    if platform.machine() != "x86_64" or platform.system() != "Linux":
        raise ValueError("Linux x86_64 build environment required")
    # A newer host cannot establish the supported glibc ceiling by compiling here.
    if tuple(map(int, platform.libc_ver()[1].split("."))) > (2, 39):
        raise ValueError("Build on Ubuntu 24.04 (glibc <=2.39), e.g. the qualification VM")
    pins = json.loads(PINS.read_text())
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    cache = work / "downloads"
    cache.mkdir(exist_ok=True)
    directories = {}
    notices = work / "notices"
    notices.mkdir(exist_ok=True)
    for item in pins["inputs"]:
        archive = fetch(item, cache)
        if item["id"] == "ort":
            dest = work / "ort"
            dest.mkdir(exist_ok=True)
            marker = dest / ".sha256"
            if not marker.exists() or marker.read_text() != item["sha256"]:
                with zipfile.ZipFile(archive) as jar:
                    for name in jar.namelist():
                        if name.startswith("ai/onnxruntime/native/linux-x64/") and name.endswith(".so"):
                            with jar.open(name) as source, (dest / Path(name).name).open("wb") as sink:
                                shutil.copyfileobj(source, sink, 1024 * 1024)
                        if Path(name).name.lower() in ("license", "license.txt", "thirdpartynotices.txt"):
                            (notices / ("ort-" + Path(name).name)).write_bytes(jar.read(name))
                marker.write_text(item["sha256"])
            directories["ort"] = dest
            continue
        if item["id"] == "zlib":
            dest = work / "inputs" / "zlib"
            marker = dest / ".sha256"
            if not marker.exists() or marker.read_text() != item["sha256"]:
                command("dpkg-deb", "--extract", archive, dest)
                marker.write_text(item["sha256"])
            directories["zlib"] = dest / "usr"
            shutil.copyfile(dest / "usr/share/doc/zlib1g/copyright", notices / "zlib-copyright")
            continue
        dest = work / "inputs" / item["id"]
        unpack(archive, dest, item)
        roots = [p for p in dest.iterdir() if p.is_dir()]
        if len(roots) != 1:
            raise ValueError("Expected one archive root: " + item["id"])
        directories[item["id"]] = roots[0]
        licenses = [p for p in roots[0].rglob("*") if p.is_file()
                    and p.name.lower().startswith(("license", "eula", "copyright", "notice"))]
        if not licenses:
            raise ValueError("Missing redistribution license: " + item["id"])
        for index, path in enumerate(licenses):
            shutil.copyfile(path, notices / (item["id"] + "-" + str(index) + "-" + path.name))
    toolkit = work / "toolkit"
    toolkit.mkdir(exist_ok=True)
    for name in ("cuda_nvcc", "cuda_cudart", "cuda_cccl", "libcublas"):
        link_tree(directories[name], toolkit)
    # NVIDIA's archives use lib/; CMake/nvcc also look for lib64/.
    if not (toolkit / "lib64").exists():
        (toolkit / "lib64").symlink_to("lib", target_is_directory=True)
    env = {k: v for k, v in os.environ.items() if not k.startswith("LD_")
           and k not in ("CUDA_HOME", "CUDA_PATH", "LIBRARY_PATH", "CPATH", "CPLUS_INCLUDE_PATH")}
    env.update(CUDACXX=str(toolkit / "bin/nvcc"), CUDA_HOME=str(toolkit),
               SOURCE_DATE_EPOCH="1760000000", LC_ALL="C", TZ="UTC")
    build_dir = work / "llama-build"
    llama = next(item for item in pins["inputs"] if item["id"] == "llama")
    configure = ["cmake", "-S", str(directories["llama"]), "-B", str(build_dir), "-G", "Ninja",
                 "-DCMAKE_BUILD_TYPE=Release", "-DGGML_CUDA=ON", "-DGGML_NATIVE=OFF",
                 "-DLLAMA_BUILD_NUMBER=" + llama["version"].removeprefix("b"),
                 "-DLLAMA_BUILD_COMMIT=" + llama["commit"],
                 "-DGGML_CCACHE=OFF", "-DGGML_OPENMP=OFF", "-DLLAMA_CURL=OFF", "-DLLAMA_OPENSSL=OFF",
                 "-DLLAMA_BUILD_TESTS=OFF", "-DLLAMA_BUILD_EXAMPLES=OFF", "-DLLAMA_BUILD_TOOLS=ON",
                 "-DGGML_CUDA_CUB_3DOT2=OFF", "-DFETCHCONTENT_FULLY_DISCONNECTED=ON",
                 "-DCMAKE_C_COMPILER=gcc-13", "-DCMAKE_CXX_COMPILER=g++-13",
                 "-DCMAKE_CUDA_HOST_COMPILER=g++-13", "-DCUDAToolkit_ROOT=" + str(toolkit),
                 "-DCMAKE_CUDA_ARCHITECTURES=" + ARCHITECTURES,
                 "-DCMAKE_BUILD_RPATH_USE_ORIGIN=ON", "-DCMAKE_INSTALL_RPATH=$ORIGIN",
                 "-DCMAKE_C_FLAGS=-ffile-prefix-map=" + str(work) + "=/build",
                 "-DCMAKE_CXX_FLAGS=-ffile-prefix-map=" + str(work) + "=/build",
                 "-DCMAKE_CUDA_FLAGS=-Xcompiler=-ffile-prefix-map=" + str(work) + "=/build"]
    command(*configure, env=env)
    command("cmake", "--build", build_dir, "--target", "llama-server", "--parallel", args.jobs, env=env)
    provenance = dict(pins, build={"configure": [a.replace(str(work), "/build") for a in configure],
                                 "cudaArchitectures": ARCHITECTURES,
                                 "tools": {name: subprocess.check_output([name, "--version"], text=True).splitlines()[0]
                                           for name in ("gcc-13", "g++-13", "cmake", "ninja", "patchelf")}})
    manifest = work / "verified-inputs.json"
    manifest.write_text(json.dumps(provenance, indent=2) + "\n")
    # Avoid copying redist libraries into another aggregate directory just for assembly.
    cuda = work / "cuda-runtime"
    cuda.mkdir(exist_ok=True)
    for name, directory in directories.items():
        if name not in ("llama", "ort", "cudnn", "cuda_nvcc", "cuda_cccl"):
            link_tree(directory / "lib", cuda / name)
    command("python3", HERE / "assemble-linux-gpu-packs.py", "--llama-dir", build_dir / "bin",
            "--cuda-dir", cuda, "--cudnn-dir", directories["cudnn"] / "lib",
            "--ort-dir", directories["ort"], "--notices", notices, "--input-manifest", manifest,
            "--driver-stub", directories["cuda_cudart"] / "lib/stubs/libcuda.so",
            "--output", args.output, env=env)
    result = json.loads((args.output / "registry-artifacts.json").read_text())
    (args.output / "SHA256SUMS").write_text("".join(
        item["sha256"] + "  " + item["filename"] + "\n" for item in result["artifacts"]))
    (args.output / "SIZES").write_text("".join(
        str(item["sizeBytes"]) + "  " + item["filename"] + "\n" for item in result["artifacts"]))
    shutil.copyfile(manifest, args.output / "verified-inputs.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--jobs", type=int, default=2)
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    try:
        build(args)
    finally:
        # Linux accounts this process and waited-for children. Evidence only: it is
        # block IO, not logical file sizes, and includes failed invocations as well.
        if args.work.exists():
            io = Path("/proc/self/io").read_text()
            (args.work / "build-io.txt").write_text(io)
            print(io, flush=True)


if __name__ == "__main__":
    main()
