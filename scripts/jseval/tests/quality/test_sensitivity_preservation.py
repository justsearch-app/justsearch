"""Preservation contracts, runnable unchanged on the pre-sensitivity baseline."""
from dataclasses import replace
import json

import pytest

from jseval.quality import compare, index
from jseval.quality.types import Outcome, RunRecord, Served
from .conftest import pinned_env
from .helpers import config_for, make_collection, make_run


def test_unknown_inherited_settings_and_ort_threads_still_key_preparation(tmp_path):
    models = tmp_path/'models'; models.mkdir(); (models/'model.onnx').write_bytes(b'model')
    cfg = index.IndexConfig(models_dir=models)
    c = make_collection()
    env = pinned_env(cfg)
    key = index.compute_index_key(c, cfg, env)[0]
    for name in ('JUSTSEARCH_FUTURE_UNKNOWN', 'JUSTSEARCH_ORT_INTRA_OP_THREADS'):
        assert index.compute_index_key(c, cfg, {**env, name: '4'})[0] != key


@pytest.mark.parametrize('reason', ['DISABLED', 'DEADLINE_EXCEEDED', 'SEARCH_DEADLINE_EXCEEDED',
                                   'MODEL_LOADING', 'INFERENCE_FAILED', 'ACQUIRE_BUDGET_EXCEEDED', None])
def test_default_reranker_drops_remain_invalid(reason):
    c = make_collection({'en/vague': 4}); record = make_run(c)
    changed = replace(record, results=tuple(replace(q,
        served=Served(q.served.legs, 'skipped', reason)) for q in record.results))
    expected = Outcome.INCONCLUSIVE if reason == 'DISABLED' else Outcome.INVALID
    assert compare.compare(c, config_for(c), record, changed).verdict.outcome == expected


def test_legacy_jsonl_and_baseline_still_load_score_and_compare(tmp_path):
    c = make_collection({'en/vague': 4}); record = make_run(c)
    lines = [json.loads(line) for line in record.dumps().splitlines()]
    lines[0].pop('run_kind', None); lines[0].pop('sensitivity', None)
    for line in lines[1:]: line.pop('hit_signals', None)
    path = tmp_path/'old.jsonl'; path.write_text(''.join(json.dumps(line)+'\n' for line in lines))
    old = RunRecord.read(path)
    compare.write_baseline(path, c, tmp_path/'old.baseline.json')
    baseline, _ = compare.load_baseline(tmp_path/'old.baseline.json')
    assert compare.compare(c, config_for(c), baseline, old).verdict.outcome == Outcome.PASS
