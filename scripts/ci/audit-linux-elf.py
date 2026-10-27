#!/usr/bin/env python3
"""Audit an amd64 .deb, extracted tree, or ELF against Ubuntu 24.04's ABI.

Uses only Python's standard library, dpkg-deb, binutils and ldd. The JSON report
includes every ELF (also foreign JAR natives), requirements and loader results.
Dependency resolution uses this machine's system libraries plus package-local
siblings and the bundled JVM; clean-install qualification is a separate check.
"""

import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile


LIMITS = {"GLIBC": "2.39", "GLIBCXX": "3.4.33"}
# Noble ships GCC 14's libstdc++6 (GLIBCXX_3.4.33):
# https://packages.ubuntu.com/noble/libstdc++6
# https://gcc.gnu.org/onlinedocs/libstdc++/manual/abi.html
GPU_LIBRARY = re.compile(
    r"lib(?:cuda|cudart|cublas(?:Lt)?|cudnn(?:_[a-z0-9_]+)?|cufft|curand|"
    r"cusolver|cusparse(?:Lt)?|nvrtc(?:-builtins)?|nvJitLink|nvinfer(?:_[a-z0-9_]+)?|"
    r"nvonnxparser)\.so(?:\.\d+)*$"
)


def run(*args, env=None):
    return subprocess.run(args, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, env=env, timeout=60)


def checked(*args):
    result = run(*args)
    if result.returncode:
        raise RuntimeError(f"{args[0]} exited {result.returncode}: {result.stdout}")
    return result.stdout


def version(value):
    return tuple(map(int, value.split(".")))


def elf_header(file):
    with file.open("rb") as stream:
        header = stream.read(20)
    return header if header[:4] == b"\x7fELF" else None


def audit(label, file, jar_member, runtime_dirs):
    header = elf_header(file)
    if len(header) < 20 or header[5] not in (1, 2):
        raise ValueError(f"Malformed ELF: {label}")
    machine = struct.unpack("<H" if header[5] == 1 else ">H", header[18:20])[0]
    row = {"path": label, "machine": machine, "osabi": header[7], "errors": []}
    reason = None
    if machine != 62 or header[4:6] != b"\x02\x01":
        reason = f"foreign architecture (ELF machine {machine}, class {header[4]})"
    elif header[7] not in (0, 3):
        reason = f"foreign OS ABI {header[7]}"
    elif jar_member and re.search(
        r"(?:^|/)(?:[^/]*(?:android|musl|freebsd|openbsd|dragonflybsd|sunos)[^/]*)(?:/|$)",
        jar_member, re.IGNORECASE,
    ):
        reason = "foreign OS/libc JAR variant"
    if reason:
        if not jar_member:
            row["errors"].append(reason + " outside a JAR")
        row["ignored"] = reason
        return row

    # Read only version NEEDS, not definitions exported by a library.
    versions = checked("readelf", "--version-info", "--wide", str(file))
    needs = versions.split("Version needs section", 1)[-1] if "Version needs section" in versions else ""
    row["required_versions"] = {}
    for family, limit in LIMITS.items():
        found = sorted(set(re.findall(rf"\b{family}_(\d+(?:\.\d+)+)\b", needs)), key=version)
        row["required_versions"][family] = found
        for value in found:
            if version(value) > version(limit):
                row["errors"].append(f"{family}_{value} exceeds Ubuntu 24.04 ceiling {family}_{limit}")

    dynamic = checked("readelf", "--dynamic", "--wide", str(file))
    row["needed"] = re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic)
    row["unresolved"] = []
    row["report_only"] = []
    if row["needed"]:
        # Never inherit developer runtime overrides, preload hooks or GPU-pack paths.
        env = {k: v for k, v in os.environ.items() if not k.startswith("LD_")}
        env["LC_ALL"] = "C"
        env["LD_LIBRARY_PATH"] = ":".join(map(str, [file.parent, *runtime_dirs]))
        result = run("ldd", str(file), env=env)
        row["loader_output"] = result.stdout.strip()
        row["loader_exit"] = result.returncode
        missing = sorted(set(re.findall(r"^\s*(\S+)\s+=>\s+not found", result.stdout, re.M)))
        row["unresolved"] = missing
        for lib in missing:
            if lib == "libonnxruntime.so.1" or GPU_LIBRARY.fullmatch(lib):
                row["report_only"].append(lib)
            else:
                row["errors"].append(f"Unresolved library: {lib}")
        # GPU dependencies remain visible even if the audit host has a GPU pack installed.
        row["gpu_pack_dependencies"] = [lib for lib in row["needed"] if GPU_LIBRARY.fullmatch(lib)]
        if result.returncode or re.search(r"version .* not found", result.stdout):
            row["errors"].append(f"ldd failed (exit {result.returncode}): {result.stdout.strip()}")
    return row


def scan(source, scratch):
    if source.is_file() and source.suffix == ".deb":
        arch = checked("dpkg-deb", "--field", str(source), "Architecture").strip()
        if arch != "amd64":
            raise ValueError(f"Expected amd64 package, got {arch}")
        root = scratch / "package"
        checked("dpkg-deb", "--extract", str(source), str(root))
    else:
        root = source
    files = sorted(root.rglob("*")) if root.is_dir() else [root]
    runtime_dirs = sorted(p for p in files if p.is_dir() and
                          (p.as_posix().endswith("runtime/lib") or p.as_posix().endswith("runtime/lib/server")))
    rows = []
    for file in files:
        if not file.is_file() or file.is_symlink():
            continue
        label = file.relative_to(root).as_posix() if root.is_dir() else file.name
        if elf_header(file):
            rows.append(audit(label, file, None, runtime_dirs))
        elif file.suffix.lower() == ".jar":
            # Each JAR has its own native tree, preserving siblings for loader resolution.
            jar_root = scratch / "jars" / str(len(rows))
            members = []
            with zipfile.ZipFile(file) as archive:
                for member in archive.infolist():
                    if member.is_dir():
                        continue
                    with archive.open(member) as stream:
                        if stream.read(4) != b"\x7fELF":
                            continue
                    name = PurePosixPath(member.filename)
                    if name.is_absolute() or ".." in name.parts or "\\" in member.filename:
                        raise ValueError(f"Unsafe JAR member: {label}!/{member.filename}")
                    target = jar_root / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with archive.open(member) as src, target.open("wb") as dst:
                        shutil.copyfileobj(src, dst)
                    members.append((member.filename, target))
            for member, target in members:
                rows.append(audit(f"{label}!/{member}", target, member, runtime_dirs))
    if not rows:
        raise ValueError("No ELF objects found")
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    report = {"ceilings": LIMITS, "dependency_resolution": "package-local and audit host system libraries", "objects": [], "errors": []}
    try:
        if not args.package.exists():
            raise ValueError(f"Missing input: {args.package}")
        os.environ["LC_ALL"] = "C"
        with tempfile.TemporaryDirectory(prefix="justsearch-elf-") as temp:
            report["objects"] = scan(args.package.resolve(), Path(temp))
        report["errors"] = [f'{row["path"]}: {error}' for row in report["objects"] for error in row["errors"]]
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        report["errors"].append(str(error))
    report["ok"] = not report["errors"]
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    ignored = sum("ignored" in row for row in report["objects"])
    print(f'ELF audit: {"PASS" if report["ok"] else "FAIL"}; {len(report["objects"])} objects, {ignored} ignored; {args.report}')
    for error in report["errors"]:
        print(error)
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
