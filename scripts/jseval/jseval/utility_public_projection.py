"""Recompute v5 question-level accuracy intervals beside the immutable v4 publication.

Run with ``python -m jseval.utility_public_projection [--check]``. This is a
statistical projection of the published observations, not a new campaign or verdict.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from ._paths import REPO_ROOT
from .utility_evidence import read_evidence
from .utility_recompose import _intention_to_treat_estimand


def _require_sha256(name: str, data: bytes, expected: str) -> None:
    # A real error, not ``assert``: ``python -O`` strips asserts and would project tampered bytes.
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected:
        raise ValueError(f'{name} sha256 mismatch: expected {expected}, got {actual}')


def projection(root: Path = REPO_ROOT) -> dict:
    public = root / 'scripts/jseval/public-agent-utility'
    pointer = json.loads((public / 'current.v1.json').read_text())
    manifest_path = public / pointer['current']['path']
    manifest_bytes = manifest_path.read_bytes()
    _require_sha256('manifest', manifest_bytes, pointer['current']['manifest_sha256'])
    manifest = json.loads(manifest_bytes)
    paths = {name: manifest_path.parent / manifest[name]['path'] for name in ('record', 'observations')}
    for name, path in paths.items():
        _require_sha256(name, path.read_bytes(), manifest[name]['sha256'])
    record = json.loads(paths['record'].read_text())
    policy_path = root / 'scripts/jseval/utility-claim-policy.v5.json'
    policy = json.loads(policy_path.read_text())
    thresholds = policy['thresholds']
    recomputed = _intention_to_treat_estimand(
        read_evidence(paths['observations']), question_level_primary=True,
        statistical_alpha=thresholds['significance_alpha'],
        permutation_draws=thresholds['minimum_permutation_draws'],
        bootstrap_draws=thresholds['minimum_cluster_bootstrap_draws'],
    )
    historical = {cell['stratum_id']: cell for cell in record['estimands']['intention_to_treat']['strata']}
    strata = []
    for cell in recomputed['intention_to_treat']['strata']:
        old = historical.pop(cell['stratum_id'])
        # All published point estimates and historical intervals must survive
        # unchanged; the only addition is the coarser resampling calculation.
        if json.loads(json.dumps({k: v for k, v in cell.items() if k != 'question_level'})) != old:
            raise ValueError(f"recomputation changes historical estimates: {cell['stratum_id']}")
        strata.append({'stratum_id': cell['stratum_id'], 'question_level': cell['question_level']})
    if historical:
        raise ValueError('recomputation omitted historical strata')
    return {
        'schema': 'agent-utility-question-level-projection.v1',
        'publication_id': manifest['publication_id'],
        'record_sha256': manifest['record']['sha256'],
        'observations_sha256': manifest['observations']['sha256'],
        'policy_id': policy['policy_id'],
        'policy_sha256': hashlib.sha256(policy_path.read_bytes()).hexdigest(),
        'outcome': record['claim_verdict']['outcome'],
        'status': 'recomputed from historical observations; original verdict retained',
        'strata': strata,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    target = REPO_ROOT / 'scripts/jseval/public-agent-utility/question-level.v1.json'
    content = json.dumps(projection(), indent=2, ensure_ascii=False) + '\n'
    if args.check:
        if target.read_text() != content:
            raise SystemExit('question-level projection is stale; rerun python -m jseval.utility_public_projection')
    else:
        target.write_text(content)
    print(f'question-level projection: {"verified" if args.check else "written"}')


if __name__ == '__main__':
    main()
