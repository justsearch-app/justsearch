"""Native code comes only from what the key hashes; the GPU key covers the driver.

One check per review finding: native packs and other native fallback locations the engine
would discover refuse before every launch and at adoption, in CPU mode too; the DJL tokenizer
library is never reused from an earlier extraction; per-model GPU switches stay in the key as
values and make a launch GPU mode; in GPU mode the NVIDIA driver version and the bytes of the
libcuda.so.1 the system loader resolves are keyed, and GPU mode refuses when either is unreadable.
Names are spelled out so the checks stand on their own.
"""
from __future__ import annotations

from dataclasses import replace
import os
from pathlib import Path
import platform
import subprocess

import pytest

from jseval.quality import index as ix
from .conftest import pinned_env
from .helpers import make_collection
from .test_index import collection, config  # noqa: F401 (fixtures)

# Every native location the engine discovers under its data folder (see inputs.DATA_NATIVE_FOLDER).
DATA_FALLBACKS = ("native-bin/llama-server/variants/cuda12/libonnxruntime.so",
                  "native-bin/onnxruntime/variants/cuda12/libonnxruntime.so",
                  "native-bin/llama-server/llama-server",
                  "native-bin/tesseract/tesseract")
MODEL_GPU_SWITCHES = ("JUSTSEARCH_EMBED_GPU_ENABLED", "JUSTSEARCH_SPLADE_GPU_ENABLED",
                      "JUSTSEARCH_NER_GPU_ENABLED", "JUSTSEARCH_BGE_M3_GPU_ENABLED",
                      "JUSTSEARCH_RERANK_GPU_ENABLED", "JUSTSEARCH_RERANK_CHUNKS_GPU_ENABLED")
LDCONFIG_ARCH = {"x86_64": "x86-64", "aarch64": "AArch64"}


@pytest.fixture(autouse=True)
def no_inherited_switches(monkeypatch):
    for name in (*MODEL_GPU_SWITCHES, "RUST_LIBRARY_PATH", "RUST_FLAVOR", "ENGINE_CACHE_DIR", "DJL_CACHE_DIR"):
        monkeypatch.delenv(name, raising=False)


@pytest.fixture
def models(tmp_path):
    root = tmp_path / "models"
    (root / "onnx/embed").mkdir(parents=True)
    (root / "onnx/embed/model.onnx").write_bytes(b"graph")
    return root


def launch(models, tmp_path, **env):
    cfg = ix.IndexConfig(models_dir=models)
    return cfg, pinned_env(cfg, **env), tmp_path / "entry/data"


# --------------------------------------------------------------------------- native fallbacks


@pytest.mark.parametrize("rel", DATA_FALLBACKS)
def test_a_native_pack_in_the_data_folder_refuses_the_launch_in_cpu_mode(models, tmp_path, rel):
    cfg, env, data = launch(models, tmp_path)
    assert env["JUSTSEARCH_GPU_ENABLED"] == "false"
    (data / rel).parent.mkdir(parents=True)
    (data / rel).write_bytes(b"\x7fELF native")
    with pytest.raises(ix.InputUnavailable, match="native-bin"):
        ix._launch_env(cfg, data, env)


def test_even_an_empty_native_folder_in_the_data_folder_refuses(models, tmp_path):
    cfg, env, data = launch(models, tmp_path)
    (data / "native-bin").mkdir(parents=True)
    with pytest.raises(ix.InputUnavailable, match="native-bin"):
        ix._launch_env(cfg, data, env)


def test_adoption_refuses_a_native_pack_added_to_a_prepared_entry(collection, config, tmp_path, monkeypatch):
    c, source = collection
    env = pinned_env(config)
    monkeypatch.setattr(ix.IndexConfig, "environment", lambda _: dict(env))
    monkeypatch.setattr(ix, "_under_cache", lambda p: p)
    real_popen = subprocess.Popen

    class FakeWorker:
        def poll(self):
            return 0
    monkeypatch.setattr(ix.subprocess, "Popen", lambda command, **kw:
                        FakeWorker() if "pausable" in command else real_popen(command, **kw))
    cfg = replace(config, cache_root=tmp_path / "cache", documents_dir=source)
    with ix.prepare_index(c, cfg) as handle:
        root, key, data = handle.root, handle.index_key, handle.data_dir
    ix._write(root / "entry.json", {"owner": ix._population_owner(c, key)})
    pack = data / "native-bin/llama-server/variants/cuda12"
    pack.mkdir(parents=True)
    (pack / "libonnxruntime.so").write_bytes(b"\x7fELF replaced core")
    launched = []
    monkeypatch.setattr(ix.subprocess, "Popen", lambda command, **kw:
                        launched.append(command) if "pausable" in command else real_popen(command, **kw))
    with pytest.raises(ix.InputUnavailable, match="native-bin"):
        ix.prepare_index(c, cfg)
    assert not launched
    # A refused input is never evidence against the entry.
    assert (root / "entry.json").is_file() and (pack / "libonnxruntime.so").is_file()


def test_the_tokenizer_library_is_never_reused_from_an_earlier_extraction(models, tmp_path, monkeypatch):
    monkeypatch.setenv("ENGINE_CACHE_DIR", str(tmp_path / "shared-djl"))
    monkeypatch.setenv("DJL_CACHE_DIR", str(tmp_path / "shared-djl"))
    cfg, env, data = launch(models, tmp_path)
    planted = data / "engine-cache/tokenizers/0.21.0-0.36.0-cpu-linux-x86_64/libtokenizers.so"
    planted.parent.mkdir(parents=True)
    planted.write_bytes(b"\x7fELF replaced tokenizer")
    first = ix._launch_env(cfg, data, env)
    # DJL reads ENGINE_CACHE_DIR before DJL_CACHE_DIR; the launch's value replaces the caller's.
    assert first.get("ENGINE_CACHE_DIR") == str(data / "engine-cache")
    assert Path(first["ENGINE_CACHE_DIR"]).is_dir() and not any(Path(first["ENGINE_CACHE_DIR"]).iterdir())
    planted.parent.mkdir(parents=True)
    planted.write_bytes(b"\x7fELF left by the previous launch")
    ix._launch_env(cfg, data, env)
    assert not planted.exists()


@pytest.mark.parametrize("name,value", [("RUST_LIBRARY_PATH", "/opt/libtokenizers.so"), ("RUST_FLAVOR", "cu122")])
def test_a_tokenizer_library_override_refuses(models, monkeypatch, name, value):
    monkeypatch.setenv(name, value)
    with pytest.raises(ix.InputUnavailable, match=name):
        ix.IndexConfig(models_dir=models).environment()


# --------------------------------------------------------------------------- per-model GPU switches


@pytest.mark.parametrize("name", MODEL_GPU_SWITCHES)
def test_per_model_gpu_switches_stay_in_the_key_as_values(models, tmp_path, name):
    cfg = ix.IndexConfig(models_dir=models)
    c = make_collection()
    off = ix.compute_index_key(c, cfg, pinned_env(cfg, **{name: "false"}))
    unset = ix.compute_index_key(c, cfg, pinned_env(cfg))
    assert off[1]["runtime"][name] == "false"
    assert off[0] != unset[0]


@pytest.mark.parametrize("name", MODEL_GPU_SWITCHES)
def test_a_per_model_gpu_switch_is_gpu_mode(models, tmp_path, name):
    cfg = ix.IndexConfig(models_dir=models)
    with pytest.raises(ix.InputUnavailable, match="JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH"):
        ix.compute_index_key(make_collection(), cfg, pinned_env(cfg, **{name: "true"}))


# --------------------------------------------------------------------------- GPU driver


@pytest.fixture
def driver(tmp_path, monkeypatch):
    """Stand-in nvidia-smi and ldconfig first on PATH, reporting what the test writes."""
    bin_dir, state = tmp_path / "bin", tmp_path / "driver"
    bin_dir.mkdir()
    state.mkdir()
    (state / "version").write_text("580.95.05\n")
    (state / "smi-status").write_text("0")
    lib = state / "libcuda.so.1"
    lib.write_bytes(b"\x7fELF driver one")
    arch = LDCONFIG_ARCH.get(platform.machine(), "x86-64")
    (state / "ldconfig").write_text(f"2 libs found in cache\n"
                                    f"\tlibcuda.so.1 (libc6) => /nonexistent/i386/libcuda.so.1\n"
                                    f"\tlibcuda.so.1 (libc6,{arch}) => {lib}\n")
    (bin_dir / "nvidia-smi").write_text(f"#!/bin/sh\ncat {state}/version\nexit $(cat {state}/smi-status)\n")
    (bin_dir / "ldconfig").write_text(f"#!/bin/sh\ncat {state}/ldconfig\n")
    for tool in ("nvidia-smi", "ldconfig"):
        (bin_dir / tool).chmod(0o755)
    monkeypatch.setenv("PATH", f"{bin_dir}{os.pathsep}{os.environ['PATH']}")
    natives = tmp_path / "ort-natives"
    natives.mkdir()
    (natives / "libonnxruntime_providers_cuda.so").write_bytes(b"provider")
    return {"state": state, "lib": lib, "natives": natives}


def gpu_key(models, driver, **env):
    cfg = ix.IndexConfig(models_dir=models)
    return ix.compute_index_key(make_collection(), cfg, pinned_env(
        cfg, JUSTSEARCH_GPU_ENABLED="true", JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH=str(driver["natives"]), **env))


def test_a_changed_driver_version_changes_the_gpu_key(models, driver):
    before = gpu_key(models, driver)[0]
    assert gpu_key(models, driver)[0] == before
    (driver["state"] / "version").write_text("580.105.08\n")
    assert gpu_key(models, driver)[0] != before


def test_a_changed_cuda_driver_library_changes_the_gpu_key(models, driver):
    before = gpu_key(models, driver)[0]
    driver["lib"].write_bytes(b"\x7fELF driver two")
    assert gpu_key(models, driver)[0] != before


def test_a_per_model_switch_keys_the_driver_too(models, driver):
    cfg = ix.IndexConfig(models_dir=models)
    env = dict(JUSTSEARCH_EMBED_GPU_ENABLED="true", JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH=str(driver["natives"]))
    before = ix.compute_index_key(make_collection(), cfg, pinned_env(cfg, **env))[0]
    driver["lib"].write_bytes(b"\x7fELF driver two")
    assert ix.compute_index_key(make_collection(), cfg, pinned_env(cfg, **env))[0] != before


@pytest.mark.parametrize("break_it", ["smi fails", "no version", "no libcuda"])
def test_unreadable_driver_information_refuses_gpu_mode(models, driver, break_it):
    if break_it == "smi fails":
        (driver["state"] / "smi-status").write_text("9")
    elif break_it == "no version":
        (driver["state"] / "version").write_text("\n")
    else:
        (driver["state"] / "ldconfig").write_text("0 libs found in cache\n")
    with pytest.raises(ix.InputUnavailable):
        gpu_key(models, driver)


def test_cpu_mode_never_reads_the_driver(models, driver):
    (driver["state"] / "smi-status").write_text("9")
    (driver["state"] / "ldconfig").write_text("")
    cfg = ix.IndexConfig(models_dir=models)
    key, components = ix.compute_index_key(make_collection(), cfg, pinned_env(cfg))
    assert key and components["runtime"]["JUSTSEARCH_GPU_ENABLED"] == "false"
