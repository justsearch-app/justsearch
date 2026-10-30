#!/usr/bin/env python3
"""Generate a registry PR from a Linux GPU workflow's SHA256SUMS and SIZES.

This only writes JSON. Final URLs point to the release tag; a draft is private
until the owner publishes it. Never register TEST-ONLY or placeholder artifacts.
"""
import argparse
import copy
import json
from pathlib import Path
import re

FILENAMES = ("llama-b8571-linux-cuda12-x86_64.zip", "ort-cuda12-cudnn9-linux-x86_64.zip")


def entries(text, pattern):
    result = {}
    for line in text.splitlines():
        match = re.fullmatch(pattern + r"  ([a-zA-Z0-9_.-]+)", line)
        if not match or match[2] not in FILENAMES or match[2] in result:
            raise ValueError("Invalid, unexpected or duplicate artifact: " + line)
        result[match[2]] = match[1]
    if set(result) != set(FILENAMES):
        raise ValueError("Both release ZIPs are required")
    return result


def update(registry, tag, checksums, sizes):
    if not re.fullmatch(r"linux-cuda12-[1-9][0-9]*", tag):
        raise ValueError("Tag must be a new linux-cuda12-<positive integer> tag")
    hashes = entries(checksums, r"([a-fA-F0-9]{64})")
    lengths = entries(sizes, r"([1-9][0-9]*)")
    result = copy.deepcopy(registry)
    if result.get("schemaVersion") != 2:
        raise ValueError("Registry schemaVersion 2 required")
    base = next(p for p in result["packages"] if p["id"] == "cuda-runtime")
    package = {key: base[key] for key in ("necessity", "label", "description", "targetDir", "installRoot",
                                         "termsUrl", "license", "tier", "requiresCuda", "minVramBytes", "variants")}
    package.update(id="cuda-runtime-linux", replaces="cuda-runtime", nativeTarget="linux-x86_64",
                   supportingFiles=[dict(filename=name, sha256=hashes[name].lower(), sizeBytes=int(lengths[name]),
                                         downloadUrl="https://github.com/eliasjustus/justsearch-releases/releases/download/"
                                                     + tag + "/" + name, extract=True) for name in FILENAMES])
    result["packages"] = [p for p in result["packages"] if p["id"] != "cuda-runtime-linux"] + [package]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--checksums", type=Path, required=True)
    parser.add_argument("--sizes", type=Path, required=True)
    parser.add_argument("--registry", type=Path, default=Path("modules/configuration/src/main/resources/ai/model-registry.v2.json"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = update(json.loads(args.registry.read_text()), args.tag, args.checksums.read_text(), args.sizes.read_text())
    except (ValueError, KeyError, StopIteration) as failure:
        parser.exit(1, str(failure) + "\n")
    args.output.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
