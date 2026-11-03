"""Bounded, exclusively admitted runs of the Search window's refined request.

The public caller plans outside the reservation. admission.py owns the complete
worker tree and enforces the hard deadline, including native inference/cleanup.
Only the caller publishes a verdict, after admission has reaped every child.
"""
from __future__ import annotations

import argparse
import importlib.util
from dataclasses import asdict, replace
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import platform
import subprocess
import sys
import time
import uuid

import httpx

from .. import backend
from .._paths import REPO_ROOT
from . import collections, compare, index, known_answers, report, scoring
from .types import (Outcome, QueryResult, RunHeader, RunRecord, Served,
                    atomic_write_text, canonical_json, sha256_text)

DEFAULT_COLLECTIONS = ('scifact', 'miracl-de', 'miracl-fr', 'enron-email')
CLEANUP_SECONDS = 45


class RunRefused(RuntimeError):
    pass


def utc() -> str:
    return datetime.now(timezone.utc).isoformat()


def refined_request() -> dict:
    # searchState.ts:383-390, FACET_FIELDS:342. The trace is always on.
    return {'querySyntax': 'search', 'limit': 50, 'includeExcerpts': True,
            'facets': {'include': True, 'fields': [{'field': f} for f in
                ('file_kind', 'mime_base', 'language', 'meta_author')]}}


def request_for(mode: str | None) -> dict:
    request = refined_request()
    if mode:
        request['mode'] = mode
    return request


def expected_legs(mode: str | None) -> tuple[str, ...]:
    return {'text': ('lexical',), 'vector': ('dense',)}.get(mode, ('lexical', 'dense'))


def search_query(client: httpx.Client, handle: index.IndexHandle, qid, query,
                 request: dict, timeout: float) -> QueryResult:
    start = time.monotonic()
    served = Served()
    ranked = ()
    error = None
    try:
        resp = client.post('/api/knowledge/search', json={**request, 'query': query.text},
                           timeout=max(.001, timeout))
        resp.raise_for_status()
        body = resp.json()
        if body.get('error'):
            raise ValueError(str(body['error']))
        trace = body.get('searchTrace') or {}
        stages = {s['id']: s for s in trace.get('stages', [])}
        if not all(s in stages for s in ('sparse-retrieval', 'dense-retrieval', 'cross-encoder')):
            raise ValueError('missing retrieval/reranker trace evidence')
        legs = tuple(leg for stage, leg in (('sparse-retrieval', 'lexical'),
                     ('dense-retrieval', 'dense'), ('splade-retrieval', 'splade'))
                     if stages.get(stage, {}).get('status') == 'executed')
        ce = stages['cross-encoder']
        served = Served(legs, ce['status'], ce.get('reason'))
        hits = body.get('results')
        if not isinstance(hits, list):
            raise ValueError('missing delivered results')
        locations = [(h.get('fields') or {}).get('path') or h.get('id') for h in hits]
        if any(not isinstance(loc, str) or not loc for loc in locations):
            raise ValueError('delivered hit has no full document identity')
        ranked = tuple(handle.ranked_doc_ids(locations))
    except Exception as e:
        # Preserve a 400, transport failure or malformed trace as this query's error.
        error = f'{type(e).__name__}: {e}'
    return QueryResult(qid, query.sha256, ranked, served, error,
                       {'elapsed_seconds': time.monotonic() - start})


def require_budget(estimate: float, budget: float) -> None:
    if not math.isfinite(budget) or not CLEANUP_SECONDS < budget <= 600:
        raise RunRefused(f'window must be greater than {CLEANUP_SECONDS} and at most 600 seconds')
    if not math.isfinite(estimate) or estimate > budget:
        raise RunRefused(f'estimate {estimate:.1f}s exceeds budget {budget:g}s; prepare a smaller collection set for this window')


def estimate_seconds(c, request: dict, models: dict, runs: Path) -> float:
    observed = []
    for path in runs.glob('*/**/*.jsonl'):
        try:
            r = RunRecord.read(path)
            h = r.header
            if (h.collection_version != c.version or h.request_config != request
                    or h.model_hashes != models or h.ended is None
                    or compare.validate_run(c, r, {c.version}, 'history')):
                continue
            elapsed = (datetime.fromisoformat(h.ended) - datetime.fromisoformat(h.started)).total_seconds()
            if elapsed > 0:
                observed.append(elapsed)
        except (ValueError, OSError, KeyError, TypeError):
            continue
    # Cold estimate includes model warmup, witnesses and process teardown, never indexing.
    return max(observed[-5:]) * 1.25 + 5 if observed else 45 + .35 * len(c.queries)


def _write_json(path: Path, value) -> None:
    atomic_write_text(path, canonical_json(value) + '\n')


def _config(raw: dict) -> index.IndexConfig:
    raw = dict(raw)
    for name in ('repo_root', 'models_dir', 'documents_dir', 'cache_root', 'data_dir'):
        if raw[name] is not None:
            raw[name] = Path(raw[name])
    return index.IndexConfig(**raw)


def _serial_config(cfg) -> dict:
    return {k: str(v) if isinstance(v, Path) else v for k, v in asdict(cfg).items()}


def require_exclusive(root: Path) -> None:
    """Use admission's live token check, then require its exclusive mode."""
    spec = importlib.util.spec_from_file_location('quality_admission', root/'scripts/dev/admission.py')
    admission = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(admission)
    if not admission.nested(os.environ):
        raise RunRefused('scoring requires live exclusive machine admission')
    token = json.loads(os.environ['JS_ADMISSION_TOKEN'])
    row = admission.live_record(Path(token['directory'])/token['record'])
    if not row or row.get('mode') != 'exclusive':
        raise RunRefused('scoring requires exclusive machine admission')


def _collection_worker(plan: dict, item: dict, deadline: float) -> None:
    require_exclusive(Path(item['index_config']['repo_root']))
    c = collections.load_collection(Path(item['collection']))
    cfg = replace(_config(item['index_config']), timeout_sec=max(.001, deadline-time.monotonic()))
    path = Path(item['run'])
    header = None
    results = []
    handle = None
    failure = None
    start = time.monotonic()
    try:
        handle = index.prepare_index(c, cfg, prepared_only=True)
        handle.wait(prepared_only=True, deadline=deadline)
        header = RunHeader(
            collection=c.name, collection_version=c.version, index_key=handle.index_key,
            index_population=handle.index_population, request_config=plan['request'],
            commit=plan['commit'], dirty_hash=plan['dirty_hash'], model_hashes=handle.model_hashes,
            started=utc(), ended=None, expected_legs=expected_legs(plan['mode']),
            expected_reranker='skipped' if plan['mode'] in ('text', 'vector') else 'executed',
            index_build=handle.index_build, index_cache_key=handle.index_cache_key,
            index_state={**handle.index_state, 'runtime': {
                k: v for k, v in handle.env.items()
                if k not in index.OPERATIONAL_ENV and k not in
                ('JUSTSEARCH_REPO_ROOT', 'JUSTSEARCH_MODELS_DIR')}}, hardware=plan['hardware'],
            budget={'window_seconds': plan['budget']})
        # started includes this collection's backend start and confirmation for estimation.
        header = replace(header, started=datetime.fromtimestamp(
            time.time() - (time.monotonic()-start), timezone.utc).isoformat())
        RunRecord(header, ()).write(path)
        Path(item['ready']).touch()
        while not Path(item['go']).exists():
            if time.monotonic() >= deadline:
                raise RunRefused('window deadline before scoring')
            time.sleep(.02)
        with httpx.Client(base_url=handle.base_url) as client:
            for qid, query in sorted(c.queries.items()):
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise RunRefused(f'window deadline at query {qid}')
                results.append(search_query(client, handle, qid, query, plan['request'], min(90, remaining)))
                RunRecord(header, tuple(results)).write(path)
        confirmed = index.confirm(handle)
        if not confirmed.ok:
            raise RunRefused('not ready: index changed during scoring: ' + '; '.join(confirmed.failures))
    except BaseException as e:
        failure = f'{type(e).__name__}: {e}'
    finally:
        if handle:
            try:
                handle.close()
            except BaseException as e:
                failure = f'cleanup failed: {e}'
        # A completion marker is written only after backend teardown succeeded.
        if header and failure is None:
            RunRecord(replace(header, ended=utc()), tuple(results)).write(path)
        _write_json(Path(item['done']), {'error': failure, 'elapsed_seconds': time.monotonic()-start})


def _window(plan: dict) -> None:
    require_exclusive(Path(plan.get('repo_root', REPO_ROOT)))
    # Leave cleanup time inside admission's single hard deadline.
    deadline = time.monotonic() + plan['budget'] - CLEANUP_SECONDS
    def start(item):
        child = subprocess.Popen([sys.executable, '-m', 'jseval.quality.run',
                                  'collection', plan['path'], str(item['number']), str(deadline)])
        return child
    items = plan['items']
    child = start(items[0])
    for n, item in enumerate(items):
        while not Path(item['ready']).exists() and child.poll() is None:
            if time.monotonic() >= deadline:
                raise RunRefused('window deadline during backend startup')
            time.sleep(.02)
        if not Path(item['ready']).exists():
            raise RunRefused(f"{item['name']}: backend did not become ready")
        # The next model load/confirmation overlaps current scoring, as measured in feasibility.
        following = start(items[n+1]) if n+1 < len(items) else None
        Path(item['go']).touch()
        while child.poll() is None:
            if time.monotonic() >= deadline:
                raise RunRefused('window deadline during scoring')
            time.sleep(.02)
        done = json.loads(Path(item['done']).read_text())
        if done['error']:
            raise RunRefused(done['error'])
        child = following
    # admission still owns and reaps all descendants before returning to the caller.


def execute(action: str, refs: tuple[str, ...], *, baseline_dir: Path,
            runs_dir: Path, window_seconds: float = 600, mode: str | None = None,
            models_dir: Path | None = None, documents_dir: Path | None = None,
            cache_root: Path = index.CACHE_ROOT, repo_root: Path = REPO_ROOT) -> tuple[Outcome, str]:
    """Plan -> reserve -> run -> reap -> validate -> publish; no successful partial verdict."""
    request = request_for(mode)
    if action == 'ablate' and mode is None:
        raise RunRefused('ablation must name an explicit mode')
    if action != 'ablate' and mode is not None:
        raise RunRefused('explicit modes belong to quality ablate')
    commit, dirty = backend.source_identity(repo_root)
    if dirty:
        raise RunRefused(f'candidate tree is dirty ({dirty[:12]}); no trusted quality verdict')
    require_budget(CLEANUP_SECONDS, window_seconds)
    backend.require_prepared(repo_root)
    loaded = []
    estimate = CLEANUP_SECONDS
    for ref in refs or DEFAULT_COLLECTIONS:
        c, qc = collections.resolve_collection(ref)
        known_answers.check(c, qc)
        if not c.queries:
            raise RunRefused(f'{c.name}: zero queries')
        cfg = index.IndexConfig(repo_root=repo_root, models_dir=models_dir,
                                documents_dir=documents_dir, cache_root=cache_root)
        # Read-only prepared check happens before taking the gate; no worker/build fallback.
        with index.prepare_index(c, cfg, prepared_only=True) as handle:
            models = dict(handle.model_hashes)
        baseline = baseline_dir / (sha256_text(c.name) + '.baseline.json')
        if action == 'check':
            compare.load_baseline(baseline)  # refuse an absent/corrupt baseline before scoring
        estimate += estimate_seconds(c, request, models, runs_dir)
        loaded.append((c, qc, cfg, baseline, models))
    if len({c.name for c, *_ in loaded}) != len(loaded):
        raise RunRefused('duplicate collection names')
    require_budget(estimate, window_seconds)
    out = runs_dir.resolve() / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S') + '-' + uuid.uuid4().hex[:8])
    out.mkdir(parents=True)
    plan = {'path': str(out/'plan.json'), 'repo_root': str(repo_root), 'action': action, 'budget': window_seconds,
            'estimate_seconds': estimate, 'request': request, 'mode': mode,
            'commit': commit, 'dirty_hash': dirty,
            'hardware': {'system': platform.system(), 'machine': platform.machine(),
                         'cpu': platform.processor(), 'cpu_count': os.cpu_count(), 'gpu_enabled': False},
            'items': []}
    for n, (c, qc, cfg, baseline, models) in enumerate(loaded):
        folder = out / str(n)
        collections.write_collection(c, folder/'collection')
        plan['items'].append({'number': n, 'name': c.name, 'collection': str(folder/'collection'),
                              'index_config': _serial_config(cfg), 'run': str(folder/'run.jsonl'),
                              'ready': str(folder/'ready'), 'go': str(folder/'go'), 'done': str(folder/'done.json')})
    _write_json(out/'plan.json', plan)
    command = [sys.executable, str(repo_root/'scripts/dev/admission.py'), 'exclusive',
               '--purpose', 'search quality ' + action, '--gate-timeout', '120',
               '--drain-timeout', '120', '--timeout', str(window_seconds), '--',
               sys.executable, '-m', 'jseval.quality.run', 'window', plan['path']]
    # Capture logs to disk: stdout has exactly one outcome, only AFTER safe cleanup.
    with (out/'window.log').open('w') as log:
        proc = subprocess.Popen(backend.bind_command(repo_root, command), stdout=log, stderr=subprocess.STDOUT)
        try:
            code = proc.wait()
        except BaseException:
            proc.terminate()
            proc.wait()  # admission waits for its guardian to reap the whole command tree
            raise
    problems = []
    if code != 0:
        problems.append(f'exclusive window exited {code}: {(out/"window.log").read_text()[-2000:]}')
    if backend.source_identity(repo_root) != (commit, dirty):
        problems.append('candidate changed during scoring')
    comparisons, times, records = [], {}, []
    for item, (c, qc, cfg, baseline, models) in zip(plan['items'], loaded):
        record, errors = compare.load_run_or_reason(item['run'], c.name)
        errors += problems
        if record:
            errors += compare.validate_run(c, record, {c.version}, c.name)
            if dict(record.header.model_hashes) != models or index.model_hashes(Path(cfg.environment()['JUSTSEARCH_MODELS_DIR'])) != models:
                errors.append(f'{c.name}: result is from another model set')
            if record.header.commit != commit or record.header.request_config != request:
                errors.append(f'{c.name}: result is from another candidate or request')
        if Path(item['done']).exists():
            done = json.loads(Path(item['done']).read_text())
            times[c.name] = done['elapsed_seconds']
            if done['error']:
                errors.append(f"{c.name}: {done['error']}")
        else:
            errors.append(f'{c.name}: interrupted output (no cleanup completion)')
        if action == 'check':
            b, metrics = compare.load_baseline(baseline)
            result = compare.compare(c, qc, b, record, baseline_metrics=metrics,
                                     intended=('commit', 'index_key'), expect_commit=commit,
                                     expect_models=models, invalid=errors)
            comparisons.append(result)
            atomic_write_text(Path(item['run']).with_suffix('.md'), report.render(result))
        elif errors:
            problems.extend(errors)
        elif record:
            # Rescore before storing anything; non-finite metrics cannot serialize.
            scoring.aggregate(scoring.score_results(c.judgments, record.results))
            records.append((item, c, baseline))
    if action == 'check':
        outcomes = [r.verdict.outcome for r in comparisons]
        outcome = next((o for o in (Outcome.INVALID, Outcome.REGRESSION, Outcome.INCONCLUSIVE) if o in outcomes), Outcome.PASS)
        details = '; '.join(report.outcome_line(r) for r in comparisons)
    elif problems:
        outcome, details = Outcome.INVALID, '; '.join(dict.fromkeys(problems))
    else:
        outcome = Outcome.PASS
        details = 'baseline stored' if action == 'baseline' else f'explicit-mode ablation {mode} (sensitivity information)'
        if action == 'baseline':
            for item, c, baseline in records:
                compare.write_baseline(item['run'], c, baseline)
    surface = 'Search window refined request' if mode is None else f'explicit-mode ablation {mode}'
    summary = {'outcome': outcome.value, 'surface': surface, 'request': request,
               'estimate_seconds': estimate, 'collection_seconds': times, 'details': details}
    _write_json(out/'summary.json', summary)
    atomic_write_text(out/'summary.md',
        f"# {outcome.value}: {surface}\n\n{details}\n\n"
        f"Candidate: {commit}. Window: {window_seconds:g}s. Estimate: {estimate:.1f}s.\n\n"
        + "Request (engine mode/pipeline defaults apply when omitted):\n\n```json\n"
        + json.dumps(request, indent=2) + "\n```\n\n"
        + "\n".join(f"- {name}: {elapsed:.1f}s" for name, elapsed in times.items())
        + "\n\nPer-query rankings, served legs, reranker status/reason and errors: `*/run.jsonl`.\n"
        + "Comparison reports: `*/run.md`. Effective runtime and model hashes are in each run header.\n")
    line = f'{outcome.value} {surface}: {details}; ' + ', '.join(f'{c} {s:.1f}s' for c,s in times.items()) + f'; report {out}'
    return outcome, ' '.join(line.split())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('window', 'collection'))
    parser.add_argument('plan', type=Path)
    parser.add_argument('number', nargs='?', type=int)
    parser.add_argument('deadline', nargs='?', type=float)
    args = parser.parse_args()
    plan = json.loads(args.plan.read_text())
    if args.action == 'window':
        _window(plan)
    else:
        _collection_worker(plan, plan['items'][args.number], args.deadline)


if __name__ == '__main__':
    main()
