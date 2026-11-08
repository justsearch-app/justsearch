"""C5 H4: policy skips survive artifacts and compare as ordinary deterministic skips."""
from dataclasses import replace
import json

from jseval.ce_coverage import ce_coverage_verdict, state_from_per_query_record, state_from_response
from jseval.quality import compare as qcmp
from jseval.quality.types import Outcome, Served, RunRecord
from .helpers import config_for, make_collection, make_run, with_results


def test_policy_skip_is_deterministic_in_raw_and_reloaded_trace():
    raw = {"results": [{"id": "one", "judgeSignals": {"ce_score": None}}],
           "searchTrace": {"stages": [{"id": "cross-encoder", "status": "skipped", "reason": "QUERY_TOO_LONG"}]}}
    persisted = {"crossEncoderStatus": "skipped", "crossEncoderReason": "QUERY_TOO_LONG",
                 "judgeSignals": [{"docId": "one", "ce_score": None}]}
    states = [state_from_response(raw), state_from_per_query_record(json.loads(json.dumps(persisted)))]
    result = ce_coverage_verdict(states, ce_requested=True)
    assert result.verdict == "ok"
    assert result.silent_drops == 0
    assert result.applied == 0


def test_identical_policy_skips_compare_normally_after_record_reload(tmp_path):
    collection = make_collection()
    base = make_run(collection)
    base = with_results(base, [replace(r, served=Served(("lexical", "dense"), "skipped", "QUERY_TOO_LONG"))
                               for r in base.results])
    candidate = replace(base, header=replace(base.header, commit="b" * 40))
    base_path = tmp_path / "base"
    candidate_path = tmp_path / "candidate"
    base.write(base_path)
    candidate.write(candidate_path)
    comparison = qcmp.compare(collection, config_for(collection), RunRecord.read(base_path), RunRecord.read(candidate_path))
    assert comparison.verdict.outcome is Outcome.PASS
