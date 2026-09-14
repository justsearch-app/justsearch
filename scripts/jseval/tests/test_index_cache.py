"""Tests for index_cache.py -- input-addressed eval-index cache store (tempdoc 751, WP2).

Every test opts back into a cache root explicitly (via `JUSTSEARCH_INDEX_CACHE` pointed at
`tmp_path`), overriding this module's own autouse `_disable_index_cache_by_default` fixture
below -- the same pattern `test_dataset_cache.py` uses against `conftest.py`'s equivalent
autouse fixture for `JUSTSEARCH_DATASET_CACHE`. Unlike `dataset_cache`, this cache has no
suite-wide autouse disable in `conftest.py` (WP2 must not edit that shared file), so this
module supplies its own -- otherwise an un-opted-in test could write real cache-entry
directories into the actual main checkout merely by running the unit suite from a worktree.
"""

from __future__ import annotations

import json
import shutil
import time
from pathlib import Path

import pytest

from jseval import index_cache


@pytest.fixture(autouse=True)
def _disable_index_cache_by_default(monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", "0")


def _make_data_dir(base: Path, *, generation: str = "g-x", populate_extra: bool = True) -> Path:
    """Build a minimal fake quiesced eval data dir, including the files that must be
    excluded from a published entry (logs/, telemetry/, app.lock, worker_signal.lock).

    The logs/ fixture file is named engine.log (not worker.log) because that's what a real
    data dir contains post lane-F-stage-A merge; the exclusion below is a whole-directory
    exclusion (index_cache._EXCLUDED_TOP_LEVEL_NAMES), so this doesn't change what's tested,
    only keeps the fixture honest.
    """
    data_dir = base / "data"
    idx_default = data_dir / "index" / "default"
    gen_dir = idx_default / "indices" / generation
    gen_dir.mkdir(parents=True, exist_ok=True)
    (gen_dir / "dummy.bin").write_bytes(b"segment-bytes")
    (idx_default / "state.json").write_text(
        json.dumps({"active_generation": generation}), encoding="utf-8")
    if populate_extra:
        (data_dir / "jobs.db").write_text("job-queue-state", encoding="utf-8")
        (data_dir / "jobs.db-wal").write_text("wal", encoding="utf-8")
        (data_dir / "jobs.db-shm").write_text("shm", encoding="utf-8")
        (data_dir / "watched_roots.json").write_text("{}", encoding="utf-8")
        logs_dir = data_dir / "logs"
        logs_dir.mkdir(parents=True, exist_ok=True)
        (logs_dir / "engine.log").write_text("log line", encoding="utf-8")
        telemetry_dir = data_dir / "telemetry"
        telemetry_dir.mkdir(parents=True, exist_ok=True)
        (telemetry_dir / "trace.ndjson").write_text("{}", encoding="utf-8")
        (data_dir / "app.lock").write_text("lock", encoding="utf-8")
        (data_dir / "worker_signal.lock").write_text("lock", encoding="utf-8")
    return data_dir


def _identity_doc() -> dict:
    return {"key": "abc123def456", "components": {"git_sha": "deadbeef", "git_dirty": False}}


def _attestation(*, build_state: str = "COMPLETE", generation: str = "g-x") -> dict:
    return {
        "counts": {"doc_count": 199, "chunk_vector_count": 4293},
        "canary": {"query": "test query", "required_stages": ["chunk_merge"]},
        "generation_id": generation,
        "build_state": build_state,
        "commit_time": "2026-07-17T08:30:08Z",
        "built_at": time.time(),
        "git_sha": "deadbeef",
        "hardware": "cuda12-dev-machine",
    }


# --- publish / lookup / adopt round trip -----------------------------------------------


def test_publish_lookup_adopt_round_trip(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(tmp_path / "cache"))
    data_dir = _make_data_dir(tmp_path / "build")
    selector_key = "a" * 64
    identity = _identity_doc()
    attestation = _attestation()

    published = index_cache.publish(data_dir, selector_key, identity, attestation)
    assert published is not None

    entry = index_cache.lookup(selector_key)
    assert entry is not None
    assert entry.doc["schema_version"] == "index-cache-entry.v1"
    assert entry.doc["selector_key"] == selector_key
    assert entry.doc["identity"] == identity
    assert entry.doc["attestation"] == attestation
    assert entry.doc["last_adopted_at"] is None
    assert entry.doc["published_at"] > 0

    # Excluded runtime residue must not have been copied.
    assert not (entry.dir / "data" / "logs").exists()
    assert not (entry.dir / "data" / "telemetry").exists()
    assert not (entry.dir / "data" / "app.lock").exists()
    assert not (entry.dir / "data" / "worker_signal.lock").exists()

    # Everything else -- including SQLite -wal/-shm -- copied verbatim.
    assert (entry.dir / "data" / "jobs.db").read_text(encoding="utf-8") == "job-queue-state"
    assert (entry.dir / "data" / "jobs.db-wal").is_file()
    assert (entry.dir / "data" / "jobs.db-shm").is_file()
    assert (entry.dir / "data" / "watched_roots.json").is_file()
    dummy = entry.dir / "data" / "index" / "default" / "indices" / "g-x" / "dummy.bin"
    assert dummy.read_bytes() == b"segment-bytes"

    dest = tmp_path / "dest"
    dest.mkdir()
    index_cache.adopt(entry, dest)

    assert (dest / "jobs.db").read_text(encoding="utf-8") == "job-queue-state"
    assert (dest / "watched_roots.json").is_file()
    assert (dest / "index" / "default" / "indices" / "g-x" / "dummy.bin").read_bytes() == (
        b"segment-bytes"
    )
    assert not (dest / "logs").exists()
    assert not (dest / "telemetry").exists()
    assert not (dest / "app.lock").exists()
    assert not (dest / "worker_signal.lock").exists()


# --- publish refusals --------------------------------------------------------------------


def test_publish_refuses_when_cache_disabled(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", "0")
    data_dir = _make_data_dir(tmp_path / "build")
    result = index_cache.publish(data_dir, "disabled-key", _identity_doc(), _attestation())
    assert result is None


def test_publish_refuses_when_build_state_not_complete(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(tmp_path / "cache"))
    data_dir = _make_data_dir(tmp_path / "build")
    result = index_cache.publish(
        data_dir, "not-complete-key", _identity_doc(), _attestation(build_state="BUILDING"))
    assert result is None
    assert index_cache.lookup("not-complete-key") is None


def test_publish_refuses_when_state_json_missing(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(tmp_path / "cache"))
    data_dir = tmp_path / "build" / "data"
    data_dir.mkdir(parents=True)
    (data_dir / "jobs.db").write_text("x", encoding="utf-8")
    result = index_cache.publish(data_dir, "no-state-key", _identity_doc(), _attestation())
    assert result is None
    assert index_cache.lookup("no-state-key") is None


def test_publish_never_raises_on_unexpected_copy_failure(tmp_path, monkeypatch):
    """Fail-quiet contract: an unexpected exception during copy must log+None, not raise."""
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(tmp_path / "cache"))
    data_dir = _make_data_dir(tmp_path / "build")

    def _boom(*_args, **_kwargs):
        raise OSError("simulated copy failure")

    monkeypatch.setattr(index_cache.shutil, "copytree", _boom)
    result = index_cache.publish(data_dir, "boom-key", _identity_doc(), _attestation())
    assert result is None


# --- lookup misses -------------------------------------------------------------------------


def _entry_dir_for(cache_dir: Path, selector_key: str) -> Path:
    return cache_dir / "entries" / selector_key[:32]


def test_lookup_miss_on_corrupted_entry_json(tmp_path, monkeypatch):
    cache_dir = tmp_path / "cache"
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(cache_dir))
    data_dir = _make_data_dir(tmp_path / "build")
    key = "corrupt-entry-key"
    assert index_cache.publish(data_dir, key, _identity_doc(), _attestation()) is not None

    entry_dir = _entry_dir_for(cache_dir, key)
    (entry_dir / "entry.json").write_text("{not valid json", encoding="utf-8")

    assert index_cache.lookup(key) is None


def test_lookup_miss_on_schema_version_mismatch(tmp_path, monkeypatch):
    cache_dir = tmp_path / "cache"
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(cache_dir))
    data_dir = _make_data_dir(tmp_path / "build")
    key = "schema-mismatch-key"
    assert index_cache.publish(data_dir, key, _identity_doc(), _attestation()) is not None

    entry_dir = _entry_dir_for(cache_dir, key)
    doc = json.loads((entry_dir / "entry.json").read_text(encoding="utf-8"))
    doc["schema_version"] = "index-cache-entry.v0"
    (entry_dir / "entry.json").write_text(json.dumps(doc), encoding="utf-8")

    assert index_cache.lookup(key) is None


def test_lookup_miss_on_selector_key_mismatch(tmp_path, monkeypatch):
    cache_dir = tmp_path / "cache"
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(cache_dir))
    data_dir = _make_data_dir(tmp_path / "build")
    key = "selector-mismatch-key"
    assert index_cache.publish(data_dir, key, _identity_doc(), _attestation()) is not None

    entry_dir = _entry_dir_for(cache_dir, key)
    doc = json.loads((entry_dir / "entry.json").read_text(encoding="utf-8"))
    doc["selector_key"] = "a-completely-different-key"
    (entry_dir / "entry.json").write_text(json.dumps(doc), encoding="utf-8")

    assert index_cache.lookup(key) is None


def test_lookup_miss_on_missing_generation_dir(tmp_path, monkeypatch):
    cache_dir = tmp_path / "cache"
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(cache_dir))
    data_dir = _make_data_dir(tmp_path / "build")
    key = "missing-generation-key"
    assert index_cache.publish(data_dir, key, _identity_doc(), _attestation()) is not None

    entry_dir = _entry_dir_for(cache_dir, key)
    shutil.rmtree(entry_dir / "data" / "index" / "default" / "indices" / "g-x")

    assert index_cache.lookup(key) is None


def test_lookup_miss_when_disabled(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", "0")
    assert index_cache.lookup("anything") is None


# --- atomic publish ------------------------------------------------------------------------


def test_publish_twice_stale_slot_replaced_second_wins(tmp_path, monkeypatch):
    cache_dir = tmp_path / "cache"
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(cache_dir))
    key = "republish-key"

    data_dir_1 = _make_data_dir(tmp_path / "build1", generation="g-1")
    index_cache.publish(data_dir_1, key, _identity_doc(), _attestation(generation="g-1"))
    entry1 = index_cache.lookup(key)
    assert entry1 is not None
    assert (entry1.dir / "data" / "index" / "default" / "indices" / "g-1").is_dir()

    data_dir_2 = _make_data_dir(tmp_path / "build2", generation="g-2")
    index_cache.publish(data_dir_2, key, _identity_doc(), _attestation(generation="g-2"))
    entry2 = index_cache.lookup(key)
    assert entry2 is not None
    assert (entry2.dir / "data" / "index" / "default" / "indices" / "g-2").is_dir()
    assert not (entry2.dir / "data" / "index" / "default" / "indices" / "g-1").exists()
    assert entry2.doc["attestation"]["generation_id"] == "g-2"

    entries_root = cache_dir / "entries"
    leftovers = [p.name for p in entries_root.iterdir() if p.name.startswith(".")]
    assert leftovers == []


# --- adopt validation ----------------------------------------------------------------------


def test_adopt_raises_on_nonempty_dest(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(tmp_path / "cache"))
    data_dir = _make_data_dir(tmp_path / "build")
    key = "adopt-nonempty-key"
    index_cache.publish(data_dir, key, _identity_doc(), _attestation())
    entry = index_cache.lookup(key)
    assert entry is not None

    dest = tmp_path / "dest"
    dest.mkdir()
    (dest / "leftover.txt").write_text("x", encoding="utf-8")

    with pytest.raises(ValueError):
        index_cache.adopt(entry, dest)


def test_adopt_raises_when_dest_missing(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(tmp_path / "cache"))
    data_dir = _make_data_dir(tmp_path / "build")
    key = "adopt-missing-dest-key"
    index_cache.publish(data_dir, key, _identity_doc(), _attestation())
    entry = index_cache.lookup(key)
    assert entry is not None

    dest = tmp_path / "does-not-exist"
    with pytest.raises(ValueError):
        index_cache.adopt(entry, dest)


def test_adopt_raises_on_copy_failure(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(tmp_path / "cache"))
    data_dir = _make_data_dir(tmp_path / "build")
    key = "adopt-copy-fail-key"
    index_cache.publish(data_dir, key, _identity_doc(), _attestation())
    entry = index_cache.lookup(key)
    assert entry is not None

    dest = tmp_path / "dest"
    dest.mkdir()

    def _boom(*_args, **_kwargs):
        raise OSError("simulated adopt copy failure")

    monkeypatch.setattr(index_cache.shutil, "copytree", _boom)
    with pytest.raises(OSError):
        index_cache.adopt(entry, dest)


# --- touch -----------------------------------------------------------------------------


def test_touch_stamps_last_adopted_at(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(tmp_path / "cache"))
    data_dir = _make_data_dir(tmp_path / "build")
    key = "touch-key"
    index_cache.publish(data_dir, key, _identity_doc(), _attestation())
    entry = index_cache.lookup(key)
    assert entry is not None
    assert entry.doc["last_adopted_at"] is None

    index_cache.touch(entry)

    refreshed = index_cache.lookup(key)
    assert refreshed is not None
    assert refreshed.doc["last_adopted_at"] is not None
    assert refreshed.doc["last_adopted_at"] > 0


def test_touch_is_best_effort_on_unreadable_entry_json(tmp_path, monkeypatch):
    cache_dir = tmp_path / "cache"
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(cache_dir))
    data_dir = _make_data_dir(tmp_path / "build")
    key = "touch-broken-key"
    index_cache.publish(data_dir, key, _identity_doc(), _attestation())
    entry = index_cache.lookup(key)
    assert entry is not None

    (entry.dir / "entry.json").write_text("{not valid json", encoding="utf-8")
    # Must not raise.
    index_cache.touch(entry)


# --- list_entries ------------------------------------------------------------------------


def test_list_entries_empty_when_disabled(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", "0")
    assert index_cache.list_entries() == []


def test_list_entries_returns_published_entries(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(tmp_path / "cache"))
    data_dir_1 = _make_data_dir(tmp_path / "build1", generation="g-1")
    data_dir_2 = _make_data_dir(tmp_path / "build2", generation="g-2")
    index_cache.publish(data_dir_1, "list-key-1", _identity_doc(), _attestation(generation="g-1"))
    index_cache.publish(data_dir_2, "list-key-2", _identity_doc(), _attestation(generation="g-2"))

    entries = index_cache.list_entries()
    keys = {e.doc["selector_key"] for e in entries}
    assert keys == {"list-key-1", "list-key-2"}


# --- prune -----------------------------------------------------------------------------


def _publish_with_stamps(
    tmp_path: Path,
    cache_dir: Path,
    key: str,
    *,
    published_at: float,
    last_adopted_at: float | None = None,
) -> Path:
    data_dir = _make_data_dir(tmp_path / f"build-{key}", populate_extra=False)
    published = index_cache.publish(data_dir, key, _identity_doc(), _attestation())
    assert published is not None
    entry_dir = _entry_dir_for(cache_dir, key)
    doc = json.loads((entry_dir / "entry.json").read_text(encoding="utf-8"))
    doc["published_at"] = published_at
    doc["last_adopted_at"] = last_adopted_at
    (entry_dir / "entry.json").write_text(json.dumps(doc), encoding="utf-8")
    return entry_dir


def test_prune_max_entries_removes_exactly_the_lru_one(tmp_path, monkeypatch):
    cache_dir = tmp_path / "cache"
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(cache_dir))
    now = time.time()

    oldest = _publish_with_stamps(tmp_path, cache_dir, "k-oldest", published_at=now - 100000)
    middle = _publish_with_stamps(tmp_path, cache_dir, "k-middle", published_at=now - 90000)
    newest = _publish_with_stamps(tmp_path, cache_dir, "k-newest", published_at=now - 80000)

    removed = index_cache.prune(max_entries=2)

    assert removed == [oldest]
    assert not oldest.exists()
    assert middle.exists()
    assert newest.exists()
    remaining_dirs = {e.dir for e in index_cache.list_entries()}
    assert remaining_dirs == {middle, newest}


def test_prune_protects_entries_newer_than_10_minutes(tmp_path, monkeypatch):
    cache_dir = tmp_path / "cache"
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(cache_dir))
    now = time.time()

    old = _publish_with_stamps(tmp_path, cache_dir, "k-old", published_at=now - 100000)
    recent = _publish_with_stamps(tmp_path, cache_dir, "k-recent", published_at=now - 30)

    # max_entries=0 would evict everything if not for the 10-minute publish-protection
    # window -- this isolates the protection rule from ordinary LRU-by-recency behavior.
    removed = index_cache.prune(max_entries=0)

    assert removed == [old]
    assert not old.exists()
    assert recent.exists()


def test_prune_max_bytes_evicts_oldest_survivors_first(tmp_path, monkeypatch):
    cache_dir = tmp_path / "cache"
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(cache_dir))
    now = time.time()

    oldest = _publish_with_stamps(tmp_path, cache_dir, "k-a", published_at=now - 100000)
    newest = _publish_with_stamps(tmp_path, cache_dir, "k-b", published_at=now - 90000)

    total_before = sum(
        sum(f.stat().st_size for f in e.dir.rglob("*") if f.is_file())
        for e in index_cache.list_entries()
    )
    one_entry_size = sum(f.stat().st_size for f in oldest.rglob("*") if f.is_file())
    # Budget large enough for one entry, too small for both.
    budget = total_before - one_entry_size

    removed = index_cache.prune(max_entries=8, max_bytes=budget)

    assert removed == [oldest]
    assert not oldest.exists()
    assert newest.exists()


def test_prune_skips_locked_entry_and_continues(tmp_path, monkeypatch):
    cache_dir = tmp_path / "cache"
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(cache_dir))
    now = time.time()

    oldest = _publish_with_stamps(tmp_path, cache_dir, "k-locked", published_at=now - 100000)
    newest = _publish_with_stamps(tmp_path, cache_dir, "k-free", published_at=now - 90000)

    real_rmtree = index_cache.shutil.rmtree

    def _rmtree_that_fails_for_oldest(path, *args, **kwargs):
        if Path(path) == oldest:
            raise OSError("simulated locked file")
        return real_rmtree(path, *args, **kwargs)

    monkeypatch.setattr(index_cache.shutil, "rmtree", _rmtree_that_fails_for_oldest)

    # Should not raise despite the locked entry, and must not report it as removed.
    removed = index_cache.prune(max_entries=1)

    assert removed == []
    assert oldest.exists()
    assert newest.exists()


def test_prune_empty_store_returns_empty_list(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(tmp_path / "cache"))
    assert index_cache.prune() == []


# --- cache_root --------------------------------------------------------------------------


def test_cache_root_env_override(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", str(tmp_path))
    assert index_cache.cache_root() == tmp_path


def test_cache_root_empty_string_disables(monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", "")
    assert index_cache.cache_root() is None


def test_cache_root_zero_disables(monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_INDEX_CACHE", "0")
    assert index_cache.cache_root() is None


def test_cache_root_default_resolution(tmp_path, monkeypatch):
    monkeypatch.delenv("JUSTSEARCH_INDEX_CACHE", raising=False)
    monkeypatch.setattr(index_cache, "main_repo_root", lambda: tmp_path)
    assert index_cache.cache_root() == tmp_path / "tmp" / "index-cache"


def test_cache_root_main_checkout_unresolvable_returns_none(monkeypatch):
    """When JUSTSEARCH_INDEX_CACHE is unset and main-checkout resolution itself fails,
    cache_root() must return None, never raise -- mirrors dataset_cache's fail-open-on-
    resolution-failure contract even though this store's adoption decisions are fail-closed:
    resolution failure just means "cache unavailable", handled the same way as "disabled"."""
    monkeypatch.delenv("JUSTSEARCH_INDEX_CACHE", raising=False)

    def _boom():
        raise OSError("simulated unresolvable main checkout")

    monkeypatch.setattr(index_cache, "main_repo_root", _boom)
    assert index_cache.cache_root() is None


# --- tempdoc 768 item 5: replay-tooling pinned adoption ---------------------

def test_run_with_cache_pinned_selector_key_bypasses_compute_selector(tmp_path, monkeypatch):
    """A `pin_selector_key` looks the pinned entry up DIRECTLY and never calls
    compute_selector (which would resolve the CURRENT key) — the 763 §F replay
    need. It also bypasses the no-corpus-axis fail-closed that the same
    (corpus_dir=None, dataset_name=None) args would otherwise trigger."""
    from jseval import backend, index_cache as ic, index_identity

    calls = {"compute": 0, "lookup": []}

    def _no_compute(*a, **k):
        calls["compute"] += 1
        raise AssertionError("compute_selector must not run when a pin is set")

    def _lookup(key):
        calls["lookup"].append(key)
        return None  # miss -> fall through to a (mocked) fresh build

    monkeypatch.setattr(index_identity, "compute_selector", _no_compute)
    monkeypatch.setattr(ic, "lookup", _lookup)
    sentinel_proc = object()
    monkeypatch.setattr(backend, "_boot_and_wait", lambda **k: sentinel_proc)

    proc, outcome = backend._run_with_cache(
        resolved_root=tmp_path, resolved_data=tmp_path / "data",
        gradlew=tmp_path / "gradlew", env={}, port=33333, llm=False,
        health_timeout_sec=1.0, corpus_dir=None, dataset_name=None,
        pin_selector_key="PINNED-KEY-abc123",
    )
    assert calls["compute"] == 0
    assert calls["lookup"] == ["PINNED-KEY-abc123"]
    assert outcome["selector_key"] == "PINNED-KEY-abc123"
    assert outcome["mode"] == "miss:selector"
    assert proc is sentinel_proc


def test_run_with_cache_without_pin_still_fails_closed_on_no_axis(tmp_path, monkeypatch):
    """Contrast: the SAME no-axis args WITHOUT a pin take the fail-closed
    no-corpus-axis path (compute_selector is never reached either, but the
    outcome is the disabled fresh build, not a lookup)."""
    from jseval import backend, index_cache as ic, index_identity

    monkeypatch.setattr(index_identity, "compute_selector",
                        lambda *a, **k: (_ for _ in ()).throw(AssertionError("unreached")))
    monkeypatch.setattr(ic, "lookup",
                        lambda key: (_ for _ in ()).throw(AssertionError("unreached")))
    monkeypatch.setattr(backend, "_boot_and_wait", lambda **k: object())

    _proc, outcome = backend._run_with_cache(
        resolved_root=tmp_path, resolved_data=tmp_path / "data",
        gradlew=tmp_path / "gradlew", env={}, port=33333, llm=False,
        health_timeout_sec=1.0, corpus_dir=None, dataset_name=None,
    )
    assert outcome["mode"] == "disabled:no-corpus-axis"
    assert outcome["selector_key"] is None
