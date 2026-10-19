"""Integration tests for the tempdoc 751 WP3 index-cache wiring.

Mock at the module seams (WP1 index_identity + WP2 index_cache public interfaces,
plus backend.py's Popen/health internals) — never the filesystem or a live
backend. The load-bearing invariants under test: the gate is OFF by default
(byte-identical), adoption is fail-closed (any doubt falls through to the
identical fresh build), and publish happens only after the backend is stopped.
"""

from __future__ import annotations

import inspect
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest
from click.testing import CliRunner

from jseval import backend as backend_mod
from jseval import raw_corpus_manifest as rcm
from jseval.cli import main


# --------------------------------------------------------------------------- #
# start_backend adopt-path tests (backend.py).
# --------------------------------------------------------------------------- #


@pytest.fixture
def boot_mocks(tmp_path, monkeypatch):
    """Patch backend.py's boot/wipe internals; yield the data_dir + mocks.

    Short-circuits the models-dir default (so shared_models_dir isn't probed) and
    stubs distribution staging and returns a MagicMock Popen so no process is spawned.
    """
    monkeypatch.setenv("JUSTSEARCH_MODELS_DIR", str(tmp_path / "models"))
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    with patch("jseval.backend._install_distribution"), \
         patch("jseval.backend.subprocess.Popen") as popen, \
         patch("jseval.backend.run_register.register_backend"), \
         patch("jseval.backend._wait_for_health", return_value=True), \
         patch("jseval.backend._clean_data_dir") as clean, \
         patch("jseval.backend.stop_backend") as stop:
        popen.return_value = MagicMock()
        yield SimpleNamespace(
            data_dir=data_dir, popen=popen, clean=clean, stop=stop,
        )


def _selector(key, reason=None):
    return SimpleNamespace(key=key, components={}, unavailable_reason=reason)


def _entry(name="deadbeefcafef00d"):
    return SimpleNamespace(dir=Path("/store/entries") / name, doc={"selector_key": name})


def _confirm(ok, failures=None, checks=None):
    return SimpleNamespace(ok=ok, failures=failures or [], checks=checks or {})


def test_mode_off_touches_no_cache_seam(boot_mocks):
    """Default (off): today's exact path — no WP1/WP2 seam is called."""
    with patch("jseval.index_identity.compute_selector") as sel, \
         patch("jseval.index_cache.lookup") as lookup, \
         patch("jseval.index_cache.adopt") as adopt, \
         patch("jseval.index_identity.confirm_adoption") as confirm:
        info = backend_mod.start_backend(data_dir=boot_mocks.data_dir, clean=True)

    sel.assert_not_called()
    lookup.assert_not_called()
    adopt.assert_not_called()
    confirm.assert_not_called()
    assert info.cache_outcome is None
    assert boot_mocks.popen.call_count == 1


def test_selector_unavailable_falls_back_to_fresh(boot_mocks):
    """Selector key None -> cache disabled for this run, one fresh boot."""
    with patch("jseval.index_identity.compute_selector",
               return_value=_selector(None, "git_sha: unavailable")), \
         patch("jseval.index_cache.lookup") as lookup:
        info = backend_mod.start_backend(
            data_dir=boot_mocks.data_dir, clean=True, index_cache_mode="on",
            corpus_dir=boot_mocks.data_dir.parent)

    lookup.assert_not_called()
    assert info.cache_outcome["mode"] == "disabled:git_sha: unavailable"
    assert info.cache_outcome["selector_key"] is None
    assert boot_mocks.popen.call_count == 1


def test_no_corpus_axis_disables_cache(boot_mocks):
    """corpus_dir=None + dataset_name=None + mode=on -> disabled (no cross-corpus
    key collisions), selector never computed, one fresh boot, selector_key None."""
    with patch("jseval.index_identity.compute_selector") as sel, \
         patch("jseval.index_cache.lookup") as lookup:
        info = backend_mod.start_backend(
            data_dir=boot_mocks.data_dir, clean=True, index_cache_mode="on")

    sel.assert_not_called()
    lookup.assert_not_called()
    assert info.cache_outcome["mode"] == "disabled:no-corpus-axis"
    assert info.cache_outcome["selector_key"] is None
    assert boot_mocks.popen.call_count == 1


def test_lookup_miss_falls_back_to_fresh(boot_mocks):
    """Selector available, no entry -> outcome miss:selector, one fresh boot."""
    with patch("jseval.index_identity.compute_selector",
               return_value=_selector("selkey123")), \
         patch("jseval.index_cache.lookup", return_value=None), \
         patch("jseval.index_cache.adopt") as adopt:
        info = backend_mod.start_backend(
            data_dir=boot_mocks.data_dir, clean=True, index_cache_mode="on",
            corpus_dir=boot_mocks.data_dir.parent)

    adopt.assert_not_called()
    assert info.cache_outcome["mode"] == "miss:selector"
    assert info.cache_outcome["selector_key"] == "selkey123"
    assert boot_mocks.popen.call_count == 1


def test_hit_confirm_ok_adopts(boot_mocks):
    """Hit + confirm ok: adopt + touch called, single boot, no second wipe."""
    entry = _entry("abc123entrydir00")
    with patch("jseval.index_identity.compute_selector",
               return_value=_selector("selkey123")), \
         patch("jseval.index_cache.lookup", return_value=entry), \
         patch("jseval.index_cache.adopt") as adopt, \
         patch("jseval.index_cache.touch") as touch, \
         patch("jseval.index_identity.confirm_adoption",
               return_value=_confirm(True, checks={"identity": {"ok": True}})):
        info = backend_mod.start_backend(
            data_dir=boot_mocks.data_dir, clean=True, index_cache_mode="on",
            corpus_dir=boot_mocks.data_dir.parent)

    adopt.assert_called_once()
    touch.assert_called_once_with(entry)
    boot_mocks.stop.assert_not_called()
    assert info.cache_outcome["mode"] == "adopted"
    assert info.cache_outcome["entry"] == "abc123entrydir00"
    # Adopted => a single boot, no fresh-build second Popen.
    assert boot_mocks.popen.call_count == 1


def test_hit_confirm_fails_falls_through_to_fresh(boot_mocks):
    """Hit + confirm FAILS: stop the mis-booted backend, wipe, boot fresh."""
    entry = _entry("badentrydir00000")
    failures = ["identity.field_catalog_hash: entry=aa live=bb"]
    with patch("jseval.index_identity.compute_selector",
               return_value=_selector("selkey123")), \
         patch("jseval.index_cache.lookup", return_value=entry), \
         patch("jseval.index_cache.adopt"), \
         patch("jseval.index_cache.touch") as touch, \
         patch("jseval.index_identity.confirm_adoption",
               return_value=_confirm(False, failures=failures,
                                     checks={"identity": {"ok": False, "diffs": failures}})):
        info = backend_mod.start_backend(
            data_dir=boot_mocks.data_dir, clean=True, index_cache_mode="on",
            corpus_dir=boot_mocks.data_dir.parent)

    # The fresh-build path actually ran: stop was called once (mis-boot), and
    # _boot_and_wait ran twice (adopt boot + fresh boot => two Popens).
    boot_mocks.stop.assert_called_once()
    assert boot_mocks.popen.call_count == 2
    touch.assert_not_called()
    assert info.cache_outcome["mode"] == "miss:confirm"
    assert info.cache_outcome["detail"]["failures"] == failures
    # A non-git/dirt component differed -> a scoped pin would NOT have hit.
    assert info.cache_outcome["would_have_hit_scoped_pin"] is False


def test_scoped_pin_would_hit_when_only_git_differs(boot_mocks):
    """miss:confirm where only git_sha/dirt differ -> scoped-pin flag True."""
    entry = _entry()
    diffs = ["identity.git_sha: entry=aa live=bb",
             "identity.dirty_state_hash: entry=cc live=dd"]
    with patch("jseval.index_identity.compute_selector",
               return_value=_selector("selkey123")), \
         patch("jseval.index_cache.lookup", return_value=entry), \
         patch("jseval.index_cache.adopt"), \
         patch("jseval.index_identity.confirm_adoption",
               return_value=_confirm(False, failures=diffs,
                                     checks={"identity": {"ok": False, "diffs": diffs}})):
        info = backend_mod.start_backend(
            data_dir=boot_mocks.data_dir, clean=True, index_cache_mode="on",
            corpus_dir=boot_mocks.data_dir.parent)

    assert info.cache_outcome["would_have_hit_scoped_pin"] is True


def test_requested_but_unavailable_axis_logs_warning(boot_mocks, caplog):
    """Finding 1: a requested cache disabled because the axis is unresolvable must
    log at WARNING (not the old silent INFO), with the reason verbatim."""
    import logging

    reason = ("corpus axis unresolvable: F:\\d\\corpus-dir has no corpus.jsonl and "
              "neither does its parent F:\\d")
    with caplog.at_level(logging.WARNING, logger="jseval.backend"), \
         patch("jseval.index_identity.compute_selector",
               return_value=_selector(None, reason)), \
         patch("jseval.index_cache.lookup") as lookup:
        info = backend_mod.start_backend(
            data_dir=boot_mocks.data_dir, clean=True, index_cache_mode="on",
            corpus_dir=boot_mocks.data_dir.parent)

    lookup.assert_not_called()
    assert info.cache_outcome["mode"] == f"disabled:{reason}"
    warnings = [r for r in caplog.records if r.levelno == logging.WARNING]
    assert any(reason in r.getMessage() for r in warnings), \
        "expected a WARNING carrying the unavailable-axis reason verbatim"


def test_adopt_error_falls_through_to_fresh(boot_mocks):
    """adopt() raising -> fallthrough to fresh, outcome miss:adopt-error."""
    entry = _entry("raisedentry00000")
    with patch("jseval.index_identity.compute_selector",
               return_value=_selector("selkey123")), \
         patch("jseval.index_cache.lookup", return_value=entry), \
         patch("jseval.index_cache.adopt", side_effect=OSError("copy failed")), \
         patch("jseval.index_identity.confirm_adoption") as confirm:
        info = backend_mod.start_backend(
            data_dir=boot_mocks.data_dir, clean=True, index_cache_mode="on",
            corpus_dir=boot_mocks.data_dir.parent)

    # Never got to boot the adopted dir, so confirm is never reached; one fresh boot.
    confirm.assert_not_called()
    assert info.cache_outcome["mode"] == "miss:adopt-error"
    assert "copy failed" in info.cache_outcome["detail"]["error"]
    assert boot_mocks.popen.call_count == 1


# --------------------------------------------------------------------------- #
# Publish hook ordering (commands/run.py).
# --------------------------------------------------------------------------- #


def _run_iteration_kwargs(**overrides):
    ctx = SimpleNamespace(obj={"json": False})
    base = dict(
        ctx=ctx, dataset="mixed/legal-clerc-200", modes=None, base_url="http://x",
        output_dir=str(Path("out")), top_k=10, embedding=False, splade=False,
        lambdamart=False, cross_encoder=False, allow_errors=False, max_queries=0,
        context_coverage=False, thresholds="0.25,0.5", history_db=None,
        corpus_dir=None, skip_ingest=True, pipeline=False, timeline_path=None,
        start_backend=True, llm=False, clean=True, reset=False,
        allow_degraded=False, index_cache_enabled=True, env_overrides={},
        json_flag=False, is_warmup=False,
    )
    base.update(overrides)
    return base


def test_publish_captured_up_published_after_stop():
    """Identity captured while up; publish only after stop; order recorded."""
    from jseval.commands import run as run_cmd

    order: list[str] = []
    info = backend_mod.BackendInfo(
        proc=MagicMock(), data_dir=Path("data"),
        cache_outcome={"mode": "miss:selector", "selector_key": "selkey123"},
        spawn_env={"JUSTSEARCH_REPO_ROOT": "x"},
    )

    def _capture(*a, **k):
        order.append("capture")
        return ("identity-doc", "attestation")

    def _stop(*a, **k):
        order.append("stop")

    def _publish(*a, **k):
        order.append("publish")

    with patch("jseval.backend.start_backend", return_value=info), \
         patch("jseval.backend.stop_backend", side_effect=_stop), \
         patch("jseval.commands.run.assert_run_capabilities"), \
         patch("jseval.commands.run._do_run"), \
         patch("jseval.commands.run._capture_publish_inputs", side_effect=_capture), \
         patch("jseval.commands.run._publish_after_stop", side_effect=_publish):
        run_cmd._run_iteration(**_run_iteration_kwargs())

    assert order == ["capture", "stop", "publish"]


def test_publish_skipped_when_adopted():
    """outcome adopted -> no capture, no publish (only a fresh build publishes)."""
    from jseval.commands import run as run_cmd

    info = backend_mod.BackendInfo(
        proc=MagicMock(), data_dir=Path("data"),
        cache_outcome={"mode": "adopted", "selector_key": "selkey123"},
        spawn_env={},
    )
    with patch("jseval.backend.start_backend", return_value=info), \
         patch("jseval.backend.stop_backend"), \
         patch("jseval.commands.run.assert_run_capabilities"), \
         patch("jseval.commands.run._do_run"), \
         patch("jseval.commands.run._capture_publish_inputs") as capture, \
         patch("jseval.commands.run._publish_after_stop") as publish:
        run_cmd._run_iteration(**_run_iteration_kwargs())

    capture.assert_not_called()
    publish.assert_not_called()


# --------------------------------------------------------------------------- #
# _do_run ingest-skip on adoption (tempdoc 751 sec Q sub-bug b).
#
# An adopted cache entry is a complete, confirmed index; re-ingesting the same
# corpus is redundant and (with the additive readiness floor) wedged the chain
# (the 1001+1001=2002 wall). _do_run must ingest EXACTLY ONCE on a fresh build
# (miss/disabled) and ZERO times on adoption -- eval still runs against the
# adopted index. warm drives ONE _run_iteration -> _do_run, so this is the
# "warm flow issues exactly one ingest pass" guarantee: a miss warms with a
# single ingest; an adopt-hit warm reuses the index without a second pass.
# --------------------------------------------------------------------------- #


def _do_run_kwargs(index_cache):
    ctx = SimpleNamespace(obj={"json": False})
    return dict(
        ctx=ctx, dataset="mixed/legal-clerc-200", modes=None, base_url="http://x",
        output_dir=str(Path("out")), top_k=10, embedding=False, splade=False,
        lambdamart=False, cross_encoder=False, allow_errors=False, max_queries=0,
        context_coverage=False, thresholds="0.25,0.5", history_db=None,
        corpus_dir=None, skip_ingest=False, ingest_config=MagicMock(),
        env_overrides={}, suppress_stdout=True, index_cache=index_cache,
    )


def test_do_run_ingests_once_on_fresh_build():
    """miss:selector -> exactly one prepare_corpus pass (the build warm publishes)."""
    from jseval.commands import run as run_cmd

    with patch("jseval.ingest.prepare_corpus") as prep, \
         patch("jseval.run.execute_run") as ex:
        prep.return_value = {"readiness_passed": True, "pipeline_summary": None}
        ex.return_value = {"dataset": "x"}
        run_cmd._do_run(**_do_run_kwargs({"mode": "miss:selector"}))

    prep.assert_called_once()
    ex.assert_called_once()


def test_do_run_skips_ingest_on_adopt():
    """adopted -> zero prepare_corpus passes (no redundant second ingest), but
    eval still runs against the adopted index."""
    from jseval.commands import run as run_cmd

    with patch("jseval.ingest.prepare_corpus") as prep, \
         patch("jseval.run.execute_run") as ex:
        ex.return_value = {"dataset": "x"}
        run_cmd._do_run(**_do_run_kwargs({"mode": "adopted", "entry": "abc123"}))

    prep.assert_not_called()
    ex.assert_called_once()


def test_raw_adopt_skips_ingest_but_threads_same_context_to_run(tmp_path):
    from jseval.commands import run as run_cmd

    corpus = tmp_path / "corpus-dir"
    corpus.mkdir()
    (corpus / "one.pdf").write_bytes(b"one")
    context = rcm.RawCorpusContext(
        "mixed/raw", corpus.resolve(), rcm.build_raw_manifest(corpus),
    )
    kwargs = _do_run_kwargs({"mode": "adopted", "entry": "abc123"})
    kwargs.update(dataset="mixed/raw", raw_context=context)

    with patch("jseval.ingest.prepare_corpus") as prep, \
            patch("jseval.run.execute_run", return_value={"dataset": "mixed/raw"}) as execute:
        run_cmd._do_run(**kwargs)

    prep.assert_not_called()
    assert execute.call_args.kwargs["raw_context"] is context


def test_raw_adopt_refuses_mismatched_context_dataset(tmp_path):
    from jseval.commands import run as run_cmd

    corpus = tmp_path / "corpus-dir"
    corpus.mkdir()
    (corpus / "one.pdf").write_bytes(b"one")
    context = rcm.RawCorpusContext(
        "mixed/different", corpus.resolve(), rcm.build_raw_manifest(corpus),
    )
    kwargs = _do_run_kwargs({"mode": "adopted", "entry": "abc123"})
    kwargs.update(dataset="mixed/raw", raw_context=context)

    with patch("jseval.ingest.prepare_corpus") as prep, \
            patch("jseval.run.execute_run") as execute, \
            pytest.raises(rcm.RawCorpusManifestError, match="dataset"):
        run_cmd._do_run(**kwargs)

    prep.assert_not_called()
    execute.assert_not_called()


def test_raw_cache_refuses_historical_selector_pin(boot_mocks, tmp_path):
    corpus = tmp_path / "corpus-dir"
    corpus.mkdir()
    (corpus / "one.pdf").write_bytes(b"one")
    context = rcm.RawCorpusContext(
        "mixed/raw", corpus.resolve(), rcm.build_raw_manifest(corpus),
    )

    with pytest.raises(ValueError, match="cannot bypass strict raw corpus identity"):
        backend_mod.start_backend(
            data_dir=boot_mocks.data_dir,
            clean=True,
            index_cache_mode="on",
            dataset_name="mixed/raw",
            raw_context=context,
            pin_selector_key="historical-key",
        )

    assert boot_mocks.popen.call_count == 0


def test_raw_publication_revalidates_after_backend_stop(tmp_path):
    from jseval.commands import run as run_cmd

    corpus = tmp_path / "corpus-dir"
    corpus.mkdir()
    document = corpus / "one.pdf"
    document.write_bytes(b"one")
    context = rcm.RawCorpusContext(
        "mixed/raw", corpus.resolve(), rcm.build_raw_manifest(corpus),
    )
    document.write_bytes(b"changed after capture")

    with patch("jseval.index_cache.publish") as publish, \
            pytest.raises(rcm.RawCorpusManifestError):
        run_cmd._publish_after_stop(
            tmp_path / "data", "selector", ("identity", "attestation"),
            raw_context=context, env_overrides={},
        )
    publish.assert_not_called()


# --------------------------------------------------------------------------- #
# Provenance block + manifest isolation (run.py).
# --------------------------------------------------------------------------- #


def test_summary_index_cache_block_and_manifest_untouched():
    """The provenance block lands in summary["index_cache"], not in the manifest."""
    from jseval import run as run_module
    from jseval import manifest as manifest_mod

    outcome = {
        "mode": "miss:selector", "entry": None, "detail": {},
        "selector_key": "internal-selkey", "would_have_hit_scoped_pin": None,
    }
    # The no-modes (ingest-only) path is the light branch that still threads the
    # block; base_url is unreachable so snapshots degrade to None gracefully.
    summary = run_module.execute_run(
        dataset_name="scifact", base_url="http://127.0.0.1:1", modes=[],
        index_cache=outcome,
    )
    assert "index_cache" in summary
    assert summary["index_cache"]["mode"] == "miss:selector"
    # selector_key is internal and must NOT leak into provenance.
    assert "selector_key" not in summary["index_cache"]
    # The block lives outside the manifest: compute_manifest has no such param.
    assert "index_cache" not in inspect.signature(manifest_mod.compute_manifest).parameters


def test_index_cache_block_returns_none_when_disengaged():
    from jseval.run import _index_cache_block

    assert _index_cache_block(None) is None
    assert _index_cache_block({}) is None


# --------------------------------------------------------------------------- #
# CLI smoke (commands/index_cache_cmd.py + run flag plumbing).
# --------------------------------------------------------------------------- #


@pytest.fixture(autouse=True)
def _allow_cross_checkout(monkeypatch):
    # The CliRunner invokes main(), which runs the cross-checkout guard; keep the
    # smoke tests robust to the pip-editable install location.
    monkeypatch.setenv("JUSTSEARCH_ALLOW_CROSS_CHECKOUT_JSEVAL", "1")


def test_cli_index_cache_list_empty():
    with patch("jseval.index_cache.list_entries", return_value=[]):
        result = CliRunner().invoke(main, ["index-cache", "list"])
    assert result.exit_code == 0
    assert "no index-cache entries" in result.output


def test_cli_index_cache_prune():
    with patch("jseval.index_cache.prune", return_value=[Path("/store/e1")]) as prune:
        result = CliRunner().invoke(
            main, ["index-cache", "prune", "--max-entries", "3"])
    assert result.exit_code == 0
    prune.assert_called_once_with(max_entries=3, max_bytes=None)
    assert "pruned e1" in result.output


def test_cli_index_identity_smoke():
    identity = SimpleNamespace(to_doc=lambda: {"key": "abc", "components": {}})
    with patch("jseval.index_identity.compute_live_identity", return_value=identity):
        result = CliRunner().invoke(main, ["index-identity", "--base-url", "http://x"])
    assert result.exit_code == 0
    assert '"key": "abc"' in result.output


def test_run_index_cache_flag_reaches_start_backend_kwarg():
    """`run --index-cache` plumbs index_cache_mode='on' into start_backend."""
    info = backend_mod.BackendInfo(
        proc=MagicMock(), data_dir=Path("data"),
        cache_outcome={"mode": "adopted", "selector_key": "k"}, spawn_env={},
    )
    with patch("jseval.backend.start_backend", return_value=info) as start, \
         patch("jseval.backend.stop_backend"), \
         patch("jseval.commands.run.assert_run_capabilities"), \
         patch("jseval.commands.run._do_run"):
        result = CliRunner().invoke(main, [
            "run", "--dataset", "scifact", "--max-queries", "0",
            "--start-backend", "--clean", "--index-cache",
        ])
    assert result.exit_code == 0, result.output
    assert start.call_args.kwargs["index_cache_mode"] == "on"


def test_run_fresh_index_flag_keeps_cache_off():
    """`run --fresh-index` keeps index_cache_mode='off' even with env=1."""
    info = backend_mod.BackendInfo(proc=MagicMock(), data_dir=Path("data"))
    with patch("jseval.backend.start_backend", return_value=info) as start, \
         patch("jseval.backend.stop_backend"), \
         patch("jseval.commands.run.assert_run_capabilities"), \
         patch("jseval.commands.run._do_run"), \
         patch.dict("os.environ", {"JUSTSEARCH_INDEX_CACHE_ADOPT": "1"}):
        result = CliRunner().invoke(main, [
            "run", "--dataset", "scifact", "--max-queries", "0",
            "--start-backend", "--clean", "--fresh-index",
        ])
    assert result.exit_code == 0, result.output
    assert start.call_args.kwargs["index_cache_mode"] == "off"


def test_run_dataset_reaches_start_backend_kwarg():
    """`run --dataset X` threads dataset_name into start_backend (751 P.5)."""
    info = backend_mod.BackendInfo(
        proc=MagicMock(), data_dir=Path("data"),
        cache_outcome={"mode": "miss:selector", "selector_key": "k"}, spawn_env={},
    )
    with patch("jseval.backend.start_backend", return_value=info) as start, \
         patch("jseval.backend.stop_backend"), \
         patch("jseval.commands.run.assert_run_capabilities"), \
         patch("jseval.commands.run._do_run"):
        result = CliRunner().invoke(main, [
            "run", "--dataset", "golden/desktop-v1", "--max-queries", "0",
            "--start-backend", "--clean", "--index-cache",
        ])
    assert result.exit_code == 0, result.output
    assert start.call_args.kwargs["dataset_name"] == "golden/desktop-v1"


# --------------------------------------------------------------------------- #
# index-cache warm CLI (751 P.5 WP-2) -- helper mocked.
# --------------------------------------------------------------------------- #


def test_warm_published_line():
    with patch("jseval.commands.run.warm_index_cache",
               return_value={"status": "published", "entry": "abc123def4567890", "reason": None}):
        result = CliRunner().invoke(main, ["index-cache", "warm", "--dataset", "golden/x"])
    assert result.exit_code == 0, result.output
    assert result.output.strip() == "published abc123def4567890"


def test_warm_already_cached_line():
    with patch("jseval.commands.run.warm_index_cache",
               return_value={"status": "already-cached", "entry": "deadbeefcafef00d", "reason": None}):
        result = CliRunner().invoke(
            main, ["index-cache", "warm", "--corpus-dir", "datasets/cell/corpus-dir"])
    assert result.exit_code == 0, result.output
    assert result.output.strip() == "already-cached deadbeefcafef00d"


def test_warm_disabled_exits_2():
    """Unresolvable axis -> disabled -> LOUD exit 2 (warm's whole job is to cache)."""
    with patch("jseval.commands.run.warm_index_cache",
               return_value={"status": "disabled", "entry": None,
                             "reason": "corpus axis unresolvable: no corpus.jsonl"}):
        result = CliRunner().invoke(main, ["index-cache", "warm", "--dataset", "scifact"])
    assert result.exit_code == 2
    assert "disabled: corpus axis unresolvable" in result.output


def test_warm_helper_axis_unresolvable_disabled():
    """warm_index_cache resolves the axis FIRST; unresolvable -> disabled, no boot."""
    from jseval.commands import run as run_cmd
    from jseval.index_identity import CorpusAxis

    with patch("jseval.index_identity.resolve_corpus_axis",
               return_value=CorpusAxis(None, None, "corpus axis unresolvable: bad")), \
         patch("jseval.commands.run._run_iteration") as iter_mock:
        out = run_cmd.warm_index_cache("scifact", None, 33221)
    iter_mock.assert_not_called()
    assert out == {"status": "disabled", "entry": None,
                   "reason": "corpus axis unresolvable: bad"}


def test_warm_helper_published():
    from jseval.commands import run as run_cmd
    from jseval.index_identity import CorpusAxis

    axis = CorpusAxis(Path("w"), Path("s"), None)
    with patch("jseval.index_identity.resolve_corpus_axis", return_value=axis), \
         patch("jseval.commands.run._run_iteration",
               return_value={"cache_outcome": {"mode": "miss:selector"},
                             "published_entry": "0123456789abcdefXXXX"}):
        out = run_cmd.warm_index_cache(None, "datasets/cell/corpus-dir", 33221)
    assert out["status"] == "published"
    assert out["entry"] == "0123456789abcdef"  # truncated to 16


def test_warm_helper_already_cached():
    from jseval.commands import run as run_cmd
    from jseval.index_identity import CorpusAxis

    axis = CorpusAxis(Path("w"), Path("s"), None)
    with patch("jseval.index_identity.resolve_corpus_axis", return_value=axis), \
         patch("jseval.commands.run._run_iteration",
               return_value={"cache_outcome": {"mode": "adopted", "entry": "cafebabecafebabeZZ"},
                             "published_entry": None}):
        out = run_cmd.warm_index_cache(None, "datasets/cell/corpus-dir", 33221)
    assert out["status"] == "already-cached"
    assert out["entry"] == "cafebabecafebabe"


def test_warm_requires_exactly_one_of_dataset_or_corpus_dir():
    # Neither -> usage error.
    r_neither = CliRunner().invoke(main, ["index-cache", "warm"])
    assert r_neither.exit_code != 0
    assert "exactly one" in r_neither.output
    # Both -> usage error (helper never invoked).
    with patch("jseval.commands.run.warm_index_cache") as helper:
        r_both = CliRunner().invoke(
            main, ["index-cache", "warm", "--dataset", "golden/x",
                   "--corpus-dir", "datasets/cell/corpus-dir"])
    assert r_both.exit_code != 0
    assert "exactly one" in r_both.output
    helper.assert_not_called()
