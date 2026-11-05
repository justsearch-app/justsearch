"""Report percentiles and diagnostic hit signals, independent of sensitivity admission."""
from dataclasses import replace
import json
from types import SimpleNamespace

import httpx
import pytest

from jseval.quality import compare, report, run
from jseval.quality.types import Outcome, QueryResult, RunRecord, Served
from .helpers import config_for, make_collection, make_run


def test_timings_include_slow_skips_and_old_runs_are_unavailable():
    c = make_collection({'en/vague': 4}); record = make_run(c)
    assert report.search_timings(record.results) == {'p50_seconds': None, 'p95_seconds': None}
    rows = tuple(replace(q, timings={'elapsed_seconds': t}, served=Served(q.served.legs, 'skipped', 'DEADLINE_EXCEEDED'))
                 for q, t in zip(record.results, [1, 2, 3, 15]))
    assert report.search_timings(rows)['p50_seconds'] == 2.5
    assert report.search_timings(rows)['p95_seconds'] == pytest.approx(13.2)
    cmp = compare.compare(c, config_for(c), record, record)
    assert 'p50 unavailable, p95 unavailable' in report.render(cmp)
    assert 'p50 2.500s' in report.score_line(c, {'nDCG@10': 1}, 0, 4, rows)
    # Pre-change JSONL has neither signals nor arm fields and still compares.
    lines = [json.loads(line) for line in record.dumps().splitlines()]
    lines[0].pop('run_kind'); lines[0].pop('sensitivity')
    for line in lines[1:]: line.pop('hit_signals')
    old = RunRecord.loads(''.join(json.dumps(line)+'\n' for line in lines))
    assert old == record
    assert compare.compare(c, config_for(c), old, old).verdict.outcome == Outcome.PASS


def test_per_hit_trace_stays_aligned_after_chunk_collapse(tmp_path):
    c = make_collection({'en/vague': 1})
    (tmp_path/'materialized.json').write_text(json.dumps(c.documents))
    h = SimpleNamespace(ranked_doc_ids=c.ranked_doc_ids, collection=c, root=tmp_path, documents_dir=tmp_path)
    def hit(doc, rank):
        return {'fields': {'path': c.documents[doc]}, 'trace': [
            {'id': 'sparse-retrieval', 'rank': rank}, {'id': 'dense-retrieval', 'rank': rank+1},
            {'id': 'splade-retrieval', 'rank': rank+2}, {'id': 'fusion', 'score': .2*rank},
            {'id': 'cross-encoder', 'score': -.5*rank}]}
    body = {'results': [hit('d002', 1), hit('d001', 2), hit('d002', 3),
                        {'fields': {'path': c.documents['d003']}}],
        'searchTrace': {'stages': [{'id': stage, 'status': 'executed'} for stage in
            ('sparse-retrieval', 'dense-retrieval', 'cross-encoder')]}}
    with httpx.Client(base_url='http://engine', transport=httpx.MockTransport(lambda _: httpx.Response(200, json=body))) as client:
        result = run.search_query(client, h, 'q000', c.queries['q000'], run.refined_request(), 5)
    assert result.error is None and result.ranked == ('d002', 'd001', 'd003')
    assert result.hit_signals[0] == {'keyword_rank': 1, 'meaning_rank': 2, 'splade_rank': 3,
                                    'fusion_score': .2, 'cross_encoder_score': -.5}
    assert all(value is None for value in result.hit_signals[-1].values())
    assert result.hit_signals[1]['keyword_rank'] == 2
    assert QueryResult.from_dict(result.to_dict()) == result


