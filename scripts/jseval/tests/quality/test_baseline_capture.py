"""A real atomic replacement cannot split baseline bytes from semantic authority."""
import json
from pathlib import Path
from jseval.quality import compare
from jseval.quality.types import sha256_text
from .helpers import make_collection, make_run


def replacement_after_capture(monkeypatch, target, replacement):
    original = compare.read_regular_text
    captured = []
    def read(path, *args, **kwargs):
        text = original(path, *args, **kwargs)
        if Path(path) == target and not captured:
            captured.append(text)
            pending = target.with_suffix('.replacement')
            pending.write_text(replacement.dumps())
            pending.replace(target)
        return text
    monkeypatch.setattr(compare, 'read_regular_text', read)
    return captured


def test_load_baseline_returns_the_record_whose_captured_bytes_match_the_digest(tmp_path, monkeypatch):
    c = make_collection()
    path = tmp_path/'original.jsonl'
    a = make_run(c); a.write(path)
    pointer = compare.write_baseline(path, c)
    b = make_run(c, commit='b'*40)
    captured = replacement_after_capture(monkeypatch, path, b)
    loaded, metadata = compare.load_baseline(pointer)
    assert captured == [a.dumps()], 'run capture and replacement did not occur during load'
    assert metadata['run_sha256'] == sha256_text(captured[0])
    assert loaded.header.commit == a.header.commit, 'parsed replacement B under captured A digest'
    assert path.read_text() == b.dumps(), 'real atomic replacement was not exercised'


def test_write_baseline_scores_the_same_captured_payload_it_hashes(tmp_path, monkeypatch):
    c = make_collection()
    path = tmp_path/'original.jsonl'
    a = make_run(c); a.write(path)
    b = make_run(c, quality='bad', commit='b'*40)
    captured = replacement_after_capture(monkeypatch, path, b)
    pointer = compare.write_baseline(path, c)
    assert captured == [a.dumps()], 'run capture and replacement did not occur during write'
    assert path.read_text() == b.dumps(), 'real atomic replacement was not exercised'
    metadata = json.loads(pointer.read_text())
    expected = compare.scoring.score_results(c.judgments, a.results)
    assert expected != compare.scoring.score_results(c.judgments, b.results)
    assert metadata['run_sha256'] == sha256_text(captured[0])
    assert metadata['per_query'] == expected, 'scored replacement B but hashed captured A'
    assert metadata['aggregate'] == compare.scoring.aggregate(expected)
    path.write_text(a.dumps())
    loaded, metadata = compare.load_baseline(pointer)
    assert loaded == a
    actual = compare.scoring.score_results(c.judgments, loaded.results)
    assert not compare._check_stored_metrics(metadata, actual, c), 'scored replacement B but hashed captured A'
