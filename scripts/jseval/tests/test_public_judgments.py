from unittest.mock import MagicMock, patch
from types import SimpleNamespace as Row
import pytest
from jseval.corpus_fetch import fetch_miracl_sample
from jseval.corpus_build import build_golden
import ir_measures


def test_public_grades_survive_fetch_and_materialization(tmp_path):
    ds = MagicMock()
    ds.queries_iter.return_value = iter([Row(query_id='upstream-7', text='several positives')])
    ds.qrels_iter.return_value = iter([Row(query_id='upstream-7', doc_id='a', relevance=1), Row(query_id='upstream-7', doc_id='b', relevance=2)])
    ds.docs_iter.return_value = iter([Row(doc_id=d, title='', text=d) for d in ['a', 'b']])
    with patch('ir_datasets.load', return_value=ds):
        fetch_miracl_sample(tmp_path/'source', lang='de', seed=666, n_docs=2)
    build_golden(tmp_path/'source', tmp_path/'dataset')
    qrels = [ir_measures.Qrel('q0001', 'a', 1), ir_measures.Qrel('q0001', 'b', 2)]
    rows = (tmp_path/'dataset/qrels/test.tsv').read_text().splitlines()[1:]
    actual = [ir_measures.Qrel(q, d, int(g)) for q, d, g in (row.split('\t') for row in rows)]
    assert actual == qrels
    ideal = [ir_measures.ScoredDoc('q0001', 'b', 2), ir_measures.ScoredDoc('q0001', 'a', 1)]
    assert ir_measures.calc_aggregate([ir_measures.nDCG@10], actual, ideal)[ir_measures.nDCG@10] == pytest.approx(1.0)


def test_superseded_release_cannot_become_a_regression_floor(tmp_path):
    import json
    from jseval.ratchet_kernel import load_baselines_doc

    (tmp_path / 'release.json').write_text(json.dumps({
        'status': 'superseded', 'measured': {'mixed/miracl-de-2k': {'metrics': {'nDCG@10': 0.857}}},
    }))
    pointer = tmp_path / 'pointer.json'
    pointer.write_text(json.dumps({'current_release': 'release.json', 'fallback_baselines': {'old': {}}}))
    with pytest.raises(ValueError, match='superseded release'):
        load_baselines_doc(pointer, project_release=lambda release, pointer: {'baselines': {'retired': {}}})
