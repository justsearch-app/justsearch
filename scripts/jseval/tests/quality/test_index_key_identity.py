"""Compatibility of opt-in content-key adoption with the existing commit callers."""
from jseval import index_identity as ii


def test_live_key_replaces_commit_and_dirty_only_when_requested(monkeypatch, tmp_path):
    meta = {k: "value" for k in ii._LIVE_KEY_META_FIELDS}
    monkeypatch.setattr(ii, "_fetch_commit_metadata", lambda *_: meta)
    monkeypatch.setattr(ii, "_ner_model_hash", lambda *_: "ner")
    monkeypatch.setattr(ii, "_git_sha_full", lambda *_: "commit-a")
    monkeypatch.setattr(ii, "_dirty_state_hash", lambda *_: "dirty-a")
    env = {"JUSTSEARCH_REPO_ROOT": str(tmp_path)}
    old = ii.compute_live_identity("http://engine", env)
    first = ii.compute_live_identity("http://engine", env, index_key="shape-key")
    monkeypatch.setattr(ii, "_git_sha_full", lambda *_: "commit-b")
    monkeypatch.setattr(ii, "_dirty_state_hash", lambda *_: "dirty-b")
    assert old.key != ii.compute_live_identity("http://engine", env).key
    assert first == ii.compute_live_identity("http://engine", env, index_key="shape-key")
    assert "git_sha" not in first.components and "dirty_state_hash" not in first.components
    assert first.key != ii.compute_live_identity("http://engine", env, index_key="changed").key


def test_selector_uses_same_opt_in_identity_axis(monkeypatch, tmp_path):
    monkeypatch.setattr(ii, "_read_sha256_sidecar", lambda *_: "embed")
    monkeypatch.setattr(ii, "_selector_splade_sidecar", lambda *_: "sparse")
    monkeypatch.setattr(ii, "_git_sha_full", lambda *_: "commit-a")
    monkeypatch.setattr(ii, "_dirty_state_hash", lambda *_: "dirty-a")
    first = ii.compute_selector(tmp_path, None, {}, index_key="shape-key")
    assert first.key and first.components["index_key"] == "shape-key"
    assert "git_sha" not in first.components and "dirty_state_hash" not in first.components
    monkeypatch.setattr(ii, "_git_sha_full", lambda *_: None)
    monkeypatch.setattr(ii, "_dirty_state_hash", lambda *_: "dirty-b")
    assert first == ii.compute_selector(tmp_path, None, {}, index_key="shape-key")
    assert ii.compute_selector(tmp_path, None, {}).key is None


def test_confirmation_uses_key_and_still_refuses_live_identity_mismatch(monkeypatch, tmp_path):
    seen = []
    def live(url, env, *, timeout, index_key):
        seen.append(index_key)
        return ii.IndexIdentity("live-key", {"index_key": index_key})
    monkeypatch.setattr(ii, "compute_live_identity", live)
    for helper in ("_confirm_binding", "_confirm_generation", "_confirm_counts", "_confirm_canary",
                   "_confirm_watched_roots"):
        monkeypatch.setattr(ii, helper, lambda *args: None)
    monkeypatch.setattr(ii, "_get_json", lambda *args: ({}, None))
    entry = {"identity": {"key": "live-key", "components": {"index_key": "shape-key"}}}
    assert ii.confirm_adoption("http://engine", entry, tmp_path, {}, index_key="shape-key").ok
    entry["identity"]["key"] = "foreign-key"
    result = ii.confirm_adoption("http://engine", entry, tmp_path, {}, index_key="shape-key")
    assert not result.ok and "identity.key" in result.failures[0]
    assert seen == ["shape-key", "shape-key"]
