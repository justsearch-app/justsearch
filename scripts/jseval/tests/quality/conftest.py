"""Isolated effective inputs for every quality test.

No test reads or writes the real snapshot store, runs a real installDist, or depends on the
machine's JDK/tesseract: snapshots live under a temporary ROOT, the JDK and OCR resolvers
return small fake runtimes (a real ELF stands in for tesseract so ``ldd`` works), and
snapshot selection returns a real snapshot of a fake checkout. Tests of the real resolvers
and selection use ``REAL``.
"""
from __future__ import annotations

import shutil
import sys
from pathlib import Path

import pytest

from jseval.quality import distribution, index, inputs

REAL = {"resolve_jdk": inputs.resolve_jdk, "resolve_ocr": inputs.resolve_ocr,
        "select_distribution": index._select_distribution, "which": shutil.which}


def write_checkout(root: Path, *, jar: bytes = b"jar bytes") -> Path:
    """A minimal checkout with a complete install, runtime resources and launch closure."""
    install = root / distribution.INSTALL
    for rel, text in {"bin/ui": "#!/bin/sh\nCLASSPATH=$APP_HOME/lib/app.jar\n", "bin/ui.bat": "@echo off\n",
                      "build-stamp.txt": "stamp\n"}.items():
        (install / rel).parent.mkdir(parents=True, exist_ok=True)
        (install / rel).write_text(text)
    (install / "bin/ui").chmod(0o755)
    (install / "lib").mkdir(exist_ok=True)
    (install / "lib/app.jar").write_bytes(jar)
    files = {"SSOT/catalogs/fields.v1.json": "{}", "SSOT/manifests/plugins/pipeline-stage-plugins.v1.json": "{}",
             "modules/ui/src/main/resources/headless-config/application.yaml": "a: 1\n",
             "contracts/native-targets/spec.v1.json": "{}", "governance/exit-ledger.v1.schema.json": "{}",
             "governance/supervision-contract.v1.json": "{}",
             distribution.LAUNCHER: "require('./lib/launch.cjs');\n",
             "scripts/dev/lib/launch.cjs": "if (0) require('./lazy.cjs');\n",
             "scripts/dev/lib/lazy.cjs": "module.exports = 1;\n"}
    for rel, text in files.items():
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        (root / rel).write_text(text)
    return root


def fake_jdk(root: Path) -> Path:
    for rel in ("release", "bin/java", "lib/modules", "lib/server/libjvm.so"):
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        (root / rel).write_bytes(rel.encode())
    return root


@pytest.fixture(autouse=True)
def isolated_inputs(monkeypatch, tmp_path_factory):
    base = tmp_path_factory.mktemp("inputs")
    monkeypatch.setattr(distribution, "ROOT", base / "dists")
    monkeypatch.setenv("JSEVAL_QUALITY_DISTRIBUTIONS", str(base / "dists"))
    monkeypatch.setattr(distribution, "_digests", None)
    monkeypatch.setattr(inputs, "_jdk_cache", {})
    monkeypatch.setattr(inputs, "_ocr_cache", {})
    monkeypatch.setenv("JUSTSEARCH_GPU_ENABLED", "false")
    which = shutil.which
    # CI runners have no machine Gradle slot wrapper; stand in only when it is absent.
    monkeypatch.setattr(shutil, "which", lambda name, *a, **kw: which(name, *a, **kw) or (
        "/bin/false" if name == "js-gradle-slot" else None))
    for name in (*inputs.JVM_OPTION_VARIABLES, *inputs.LOADER_VARIABLES, "JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND",
                 "JUSTSEARCH_TESSERACT_PATH", "JUSTSEARCH_TESSDATA_PATH", "JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH"):
        monkeypatch.delenv(name, raising=False)
    jdk = fake_jdk(base / "jdk")
    tessdata = base / "tessdata"
    tessdata.mkdir()
    (tessdata / "eng.traineddata").write_bytes(b"eng")
    monkeypatch.setattr(inputs, "resolve_jdk", lambda *a: jdk)
    monkeypatch.setattr(inputs, "resolve_ocr", lambda *a: (Path(sys.executable).resolve(), tessdata))
    checkout = write_checkout(base / "checkout")
    selected = {}

    def select(*_args):
        if "digest" not in selected:
            selected["digest"] = distribution.create(checkout, ["fake-commit", None])
        return selected["digest"]

    monkeypatch.setattr(index, "_select_distribution", select)
    return {"base": base, "checkout": checkout, "jdk": jdk, "tessdata": tessdata, "select": select}


def pinned_env(config, **overrides) -> dict:
    """The complete pinned environment for ``config``, with its selected snapshot and every
    location resolved against it, as ``prepare_index`` pins them."""
    env = config.environment()
    env[index.DISTRIBUTION_ENV] = index._select_distribution(config, env, False)
    env.update(overrides)
    index.pin_locations(env)
    return env


def ready_distribution(monkeypatch) -> None:
    """Stand-in for a checkout whose own snapshot and prepared data are present."""
    from jseval import backend
    monkeypatch.setattr(distribution, "current", lambda *a: index._select_distribution())
    monkeypatch.setattr(backend, "require_prepared_data", lambda *a: None)


@pytest.fixture
def real_inputs(monkeypatch):
    """Opt-in real-engine tests: the machine's JDK, OCR runtime, snapshot store and selection."""
    root = Path.home() / ".cache/justsearch-quality/dists"
    monkeypatch.setattr(distribution, "ROOT", root)
    monkeypatch.setenv("JSEVAL_QUALITY_DISTRIBUTIONS", str(root))
    monkeypatch.setattr(inputs, "resolve_jdk", REAL["resolve_jdk"])
    monkeypatch.setattr(inputs, "resolve_ocr", REAL["resolve_ocr"])
    monkeypatch.setattr(index, "_select_distribution", REAL["select_distribution"])
