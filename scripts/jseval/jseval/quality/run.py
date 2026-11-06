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
import psutil

from .. import backend
from .._paths import REPO_ROOT
from . import collections, compare, distribution, index, inputs, known_answers, report, scoring, sensitivity
from .types import (Outcome, QueryResult, RunHeader, RunRecord, Served,
                    atomic_write_text, canonical_json, sha256_text)

DEFAULT_COLLECTIONS = ('scifact', 'miracl-de', 'enron-email')
FULL_COLLECTIONS = ('scifact', 'miracl-de', 'miracl-fr', 'enron-email')
# Join the default and full checks once no query kind of theirs awaits labels (real-use: when
# its topical labels are frozen); until then they run only when named.
LABELLED_DEFAULTS = ('real-use',)


def default_collections(full_suite: bool = False) -> tuple[str, ...]:
    base = FULL_COLLECTIONS if full_suite else DEFAULT_COLLECTIONS
    labelled = tuple(a for a in LABELLED_DEFAULTS if not collections.load_config(a).pending_kinds)
    # Keep short Enron last so its startup overlaps the preceding collection's scoring.
    return base[:-1] + labelled + base[-1:]
CLEANUP_SECONDS = 45
GPU_MARGIN_MIB = 2048


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
    served = Served()
    ranked = ()
    error = None
    signals = ()
    elapsed = None
    try:
        payload = {**request, 'query': query.text}
        start = time.monotonic()
        try:
            resp = client.post('/api/knowledge/search', json=payload,
                               timeout=max(.001, timeout))
        finally:
            elapsed = time.monotonic() - start
        if resp.status_code == 400 and resp.json().get('errorCode') == compare.SYNTAX_REFUSAL:
            return QueryResult(qid, query.sha256, (), Served((), 'skipped', compare.SYNTAX_REFUSAL),
                               timings={'elapsed_seconds': elapsed, 'http_status': 400})
        resp.raise_for_status()
        body = resp.json()
        if body.get('error'):
            raise ValueError(str(body['error']))
        trace = body.get('searchTrace') or {}
        stages = {s['id']: s for s in trace.get('stages', [])}
        if not all(s in stages for s in ('sparse-retrieval', 'dense-retrieval', 'cross-encoder')):
            raise ValueError('missing retrieval/reranker trace evidence')
        leg_details = {leg: {'status': stages[stage]['status'], 'reason': stages[stage].get('reason')}
                       for stage, leg in (('sparse-retrieval', 'lexical'),
                           ('dense-retrieval', 'dense'), ('splade-retrieval', 'splade'))
                       if stage in stages}
        legs = tuple(leg for leg, detail in leg_details.items() if detail['status'] == 'executed')
        ce = stages['cross-encoder']
        served = Served(legs, ce['status'], ce.get('reason'), leg_details)
        hits = body.get('results')
        if not isinstance(hits, list):
            raise ValueError('missing delivered results')
        locations = [(h.get('fields') or {}).get('path') or h.get('id') for h in hits]
        if any(not isinstance(loc, str) or not loc for loc in locations):
            raise ValueError('delivered hit has no full document identity')
        materialized = replace(handle.collection, version='',
            documents=json.loads((handle.root/'materialized.json').read_text()))
        delivered = [materialized.doc_id_for_location(loc, handle.documents_dir) for loc in locations]
        ranked = tuple(dict.fromkeys(delivered))
        signals = delivered_signals(hits, delivered, ranked)
    except Exception as e:
        # All other HTTP errors, transport failures and malformed traces stay errors.
        error = f'{type(e).__name__}: {e}'
    return QueryResult(qid, query.sha256, ranked, served, error,
                       {'elapsed_seconds': elapsed}, signals)


def delivered_signals(hits, delivered, ranked):
    """Keep the first delivered chunk's trace, exactly like ranked_doc_ids collapse."""
    empty = dict.fromkeys(('keyword_rank', 'meaning_rank', 'splade_rank', 'fusion_score', 'cross_encoder_score'))
    if not any(hit.get('trace') for hit in hits):
        return tuple(dict(empty) for _ in ranked)
    by_doc = {}
    for hit, doc in zip(hits, delivered):
        if doc in by_doc:
            continue
        stages = {stage['id']: stage for stage in hit.get('trace') or []}
        by_doc[doc] = {name: (stages.get(stage) or {}).get(field) for name, stage, field in (
            ('keyword_rank', 'sparse-retrieval', 'rank'),
            ('meaning_rank', 'dense-retrieval', 'rank'),
            ('splade_rank', 'splade-retrieval', 'rank'),
            ('fusion_score', 'fusion', 'score'),
            ('cross_encoder_score', 'cross-encoder', 'score'))}
    return tuple(by_doc.get(doc, empty) for doc in ranked)


def require_budget(estimate: float, budget: float, *, full_suite: bool = False) -> None:
    maximum = 600
    if not math.isfinite(budget) or not CLEANUP_SECONDS < budget <= maximum:
        raise RunRefused(f'window must be greater than {CLEANUP_SECONDS} and at most {maximum} seconds')
    if not math.isfinite(estimate) or estimate > budget:
        raise RunRefused(f'estimate {estimate:.1f}s exceeds budget {budget:g}s; prepare a smaller collection set for this window')


def _history(c, request: dict, models: dict, runs: Path):
    observed = []
    for path in runs.glob('*/**/*.jsonl'):
        try:
            r = RunRecord.read(path)
            h = r.header
            if (h.collection_version != c.version or h.request_config != request
                    or h.run_kind == 'sensitivity'
                    or h.model_hashes != models or h.ended is None
                    or compare.validate_run(c, r, {c.version}, 'history')):
                continue
            elapsed = (datetime.fromisoformat(h.ended) - datetime.fromisoformat(h.started)).total_seconds()
            if elapsed > 0:
                done_path = path.parent/'done.json'
                done = json.loads(done_path.read_text()) if done_path.exists() else {}
                if not isinstance(done, dict) or not isinstance(done.get('phase_seconds', {}), dict):
                    continue
                query_times = [q.timings.get('elapsed_seconds') for q in r.results]
                if all(isinstance(v, (int, float)) and math.isfinite(v) and v >= 0 for v in query_times):
                    done = {**done, 'query_seconds': sum(query_times)}
                observed.append((h.started, elapsed, done))
        except (ValueError, OSError, KeyError, TypeError):
            continue
    return sorted(observed, key=lambda row: row[0])[-5:]


def estimate_seconds(c, request: dict, models: dict, runs: Path) -> float:
    observed = []
    for _, elapsed, done in _history(c, request, models, runs):
        if not done.get('phase_seconds') and 'query_seconds' in done:
            # Legacy overlapped records do not say how long a ready engine sat
            # waiting for its predecessor. Use measured query work plus the cold
            # startup/witness/teardown allowance, rather than summing that wait.
            observed.append(45 + done['query_seconds'])
            continue
        waiting = done.get('phase_seconds', {}).get('waiting', 0)
        if isinstance(waiting, (int, float)) and math.isfinite(waiting) and 0 <= waiting < elapsed:
            observed.append(elapsed-waiting)
    # Cold estimate includes model warmup, witnesses and process teardown, never indexing.
    return max(observed[-5:]) * 1.25 + 5 if observed else 45 + .35 * len(c.queries)


def estimate_startup_seconds(c, request, models, runs) -> float:
    values = [d.get('phase_seconds', {}).get('startup') for _, _, d in _history(c, request, models, runs)]
    values = [v for v in values if isinstance(v, (int, float)) and math.isfinite(v) and v > 0]
    return max(values)*1.25 if values else 45


def estimate_window(totals, startups, overlap: bool) -> float:
    """Startup of i+1 can overlap only the scoring/cleanup of i, never its startup."""
    finished = score_start = 0
    for total, startup in zip(totals, startups):
        startup = min(total, startup)
        ready = (score_start if overlap else finished) + startup
        score_start = max(ready, finished)
        finished = score_start + total - startup
    return finished + CLEANUP_SECONDS


def gpu_memory(pid: int | None) -> tuple[float | None, float | None]:
    """Read-only GPU evidence. Unknown/multi-GPU placement refuses overlap."""
    try:
        def query(kind, fields):
            return subprocess.check_output(['nvidia-smi', f'--query-{kind}={fields}',
                '--format=csv,noheader,nounits'], text=True, timeout=2).strip().splitlines()
        devices = query('gpu', 'uuid,memory.free')
        if len(devices) != 1:
            return None, None
        device, free = [v.strip() for v in devices[0].split(',')]
        footprint = None
        if pid is not None:
            owned = {pid, *(p.pid for p in psutil.Process(pid).children(recursive=True))}
            sizes = [float(size) for row in query('compute-apps', 'gpu_uuid,pid,used_gpu_memory')
                     for gpu, proc, size in [[v.strip() for v in row.split(',')]]
                     if gpu == device and int(proc) in owned]
            footprint = sum(sizes) if sizes else None
        free = float(free)
        if not math.isfinite(free) or free < 0 or (footprint is not None and
                (not math.isfinite(footprint) or footprint <= 0)):
            return None, None
        return free, footprint
    except (OSError, ValueError, subprocess.SubprocessError, psutil.Error):
        return None, None


def can_overlap(enabled: bool, pid: int | None, footprint: float | None = None) -> bool:
    if not enabled:
        return False
    free, measured = gpu_memory(pid)
    required = max(measured or 0, footprint or 0)
    # Before any engine starts, reserve space for both. With an engine running,
    # free memory already excludes its allocations; reserve one more plus margin.
    return free is not None and required > 0 and free >= required*(2 if pid is None else 1) + GPU_MARGIN_MIB


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
    ready = scoring_start = scoring_end = None
    gpu_peak = None
    gpu_enabled = str(plan['hardware'].get('gpu_enabled', '')).lower() == 'true'
    arm = item.get('sensitivity')
    cpu_checks = {}
    try:
        if arm is not None:
            sensitivity.require_cpu_threads(arm['settings'], arm['requested_cpus'])
            sensitivity.pin_cpus(arm['requested_cpus'])
            arm = {**arm, 'cpu_affinity_checks': cpu_checks}
        handle = index.prepare_index(c, cfg, prepared_only=True,
            **({'sensitivity': arm['settings']} if arm is not None else {}))
        handle.wait(prepared_only=True, deadline=deadline)
        header = RunHeader(
            collection=c.name, collection_version=c.version, index_key=handle.index_key,
            index_population=handle.index_population, request_config=plan['request'],
            commit=plan['commit'], dirty_hash=plan['dirty_hash'], model_hashes=handle.model_hashes,
            started=utc(), ended=None, expected_legs=expected_legs(plan['mode']),
            expected_reranker='skipped' if plan['mode'] in ('text', 'vector') else 'executed',
            index_build=handle.index_build, index_cache_key=handle.index_cache_key,
            # Location-independent: resource settings appear as the digest of their content and
            # every other path-like value as its own digest.
            index_state={**handle.index_state, 'runtime': inputs.recorded(handle.components['runtime'])},
            identity=handle.identity, hardware=plan['hardware'],
            budget={'window_seconds': plan['budget']},
            run_kind='sensitivity' if arm is not None else 'standard', sensitivity=arm or {})
        # started includes this collection's backend start and confirmation for estimation.
        header = replace(header, started=datetime.fromtimestamp(
            time.time() - (time.monotonic()-start), timezone.utc).isoformat())
        RunRecord(header, ()).write(path)
        if arm is not None:
            arm = sensitivity.history_key(arm,
                sensitivity.effective_settings(handle.base_url, arm['settings']),
                sensitivity.engine_cpus(handle, arm['requested_cpus'],
                                        cpu_checks.setdefault('before_scoring', {})))
            sensitivity.check_profile(arm, item.get('sensitivity_expected'))
            header = replace(header, sensitivity=arm)
            RunRecord(header, ()).write(path)
        ready = time.monotonic()
        Path(item['ready']).touch()
        while not Path(item['go']).exists():
            if time.monotonic() >= deadline:
                raise RunRefused('window deadline before scoring')
            time.sleep(.02)
        scoring_start = time.monotonic()
        with httpx.Client(base_url=handle.base_url) as client:
            chosen = arm['queries'] if arm is not None else c.queries
            for qid in sorted(chosen):
                query = c.queries[qid]
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise RunRefused(f'window deadline at query {qid}')
                results.append(search_query(client, handle, qid, query, plan['request'], min(90, remaining)))
                RunRecord(header, tuple(results)).write(path)
                if gpu_enabled and len(results) in (1, len(c.queries)):
                    _, measured = gpu_memory(os.getpid())
                    gpu_peak = max(gpu_peak or 0, measured or 0) or None
        confirmed = index.confirm(handle)
        if not confirmed.ok:
            raise RunRefused('not ready: index changed during scoring: ' + '; '.join(confirmed.failures))
        if arm is not None:
            # Lazy sessions can acquire their execution provider on the first query.
            header = replace(header, sensitivity=sensitivity.history_key(arm,
                sensitivity.effective_settings(handle.base_url, arm['settings']),
                sensitivity.engine_cpus(handle, arm['requested_cpus'],
                                        cpu_checks.setdefault('after_scoring', {}))))
            sensitivity.check_profile(header.sensitivity, item.get('sensitivity_expected'))
        scoring_end = time.monotonic()
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
        elif header and arm is not None:
            RunRecord(header, tuple(results)).write(path)
        ended = time.monotonic()
        phases = ({'startup': ready-start, 'waiting': scoring_start-ready,
                   'scoring': scoring_end-scoring_start, 'cleanup': ended-scoring_end}
                  if None not in (ready, scoring_start, scoring_end) else {})
        _write_json(Path(item['done']), {'error': failure, 'elapsed_seconds': ended-start,
                    'phase_seconds': phases, 'gpu_engine_mib': gpu_peak})


def _window(plan: dict) -> None:
    require_exclusive(Path(plan.get('repo_root', REPO_ROOT)))
    # Leave cleanup time inside admission's single hard deadline.
    deadline = time.monotonic() + plan['budget'] - CLEANUP_SECONDS
    def start(item):
        child = subprocess.Popen([sys.executable, '-m', 'jseval.quality.run',
                                  'collection', plan['path'], str(item['number']), str(deadline)])
        return child
    items = plan['items']
    if not items:
        raise RunRefused('zero collections')
    child = start(items[0])
    for n, item in enumerate(items):
        while not Path(item['ready']).exists() and child.poll() is None:
            if time.monotonic() >= deadline:
                raise RunRefused('window deadline during backend startup')
            time.sleep(.02)
        if not Path(item['ready']).exists():
            raise RunRefused(f"{item['name']}: backend did not become ready")
        # Recheck immediately before loading another engine: another GPU user
        # may have consumed memory since planning. Unknown evidence is sequential.
        overlap = (plan.get('overlap', False) and can_overlap(
            True, child.pid, plan.get('gpu_engine_mib')))
        following = start(items[n+1]) if n+1 < len(items) and overlap else None
        Path(item['go']).touch()
        while child.poll() is None:
            if time.monotonic() >= deadline:
                raise RunRefused('window deadline during scoring')
            time.sleep(.02)
        done = json.loads(Path(item['done']).read_text())
        if done['error']:
            raise RunRefused(done['error'])
        child = following or (start(items[n+1]) if n+1 < len(items) else None)
    # admission still owns and reaps all descendants before returning to the caller.


def execute(action: str, refs: tuple[str, ...], *, baseline_dir: Path,
            runs_dir: Path, window_seconds: float = 600, mode: str | None = None,
            models_dir: Path | None = None, documents_dir: Path | None = None,
            cache_root: Path = index.CACHE_ROOT, repo_root: Path = REPO_ROOT,
            full_suite: bool = False, sets: tuple[str, ...] = (),
            cpus: str | None = None, sample: int | None = None, seed: int = 0) -> tuple[Outcome, str]:
    """Plan -> reserve -> run -> reap -> validate -> publish; no successful partial verdict."""
    overrides = sensitivity.settings(sets) if action == 'sensitivity' else None
    requested_cpus = sensitivity.cpus(cpus) if action == 'sensitivity' else None
    if overrides is not None:
        sensitivity.require_cpu_threads(overrides, requested_cpus)
    request = request_for(mode)
    if action == 'ablate' and mode is None:
        raise RunRefused('ablation must name an explicit mode')
    if action != 'ablate' and mode is not None:
        raise RunRefused('explicit modes belong to quality ablate')
    commit, dirty = backend.source_identity(repo_root)
    if dirty:
        raise RunRefused(f'candidate tree is dirty ({dirty[:12]}); no trusted quality verdict')
    require_budget(CLEANUP_SECONDS, window_seconds, full_suite=full_suite)
    # Scoring measures the checkout: its own installed snapshot, before any engine starts.
    try:
        distribution.current(repo_root, [commit, dirty])
    except distribution.DistributionRefused as e:
        raise RunRefused(str(e)) from None
    arms, expected_profiles = {}, {}
    loaded = []
    totals, startups, footprints = [], [], []
    pending = {}
    for ref in refs or default_collections(full_suite):
        c, qc = collections.resolve_collection(ref)
        if action == 'check' and not full_suite and c.name == 'miracl-fr':
            raise RunRefused('MIRACL fr belongs to quality full-suite with an explicit window')
        # Refuses a collection without scored queries (K2); pending ones are counted, not run.
        pending[c.name] = known_answers.check(c, qc).pending
        c = collections.scored_collection(c, qc)
        if not c.queries:
            raise RunRefused(f'{c.name}: no scored queries')
        cfg = index.IndexConfig(repo_root=repo_root, models_dir=models_dir,
                                documents_dir=documents_dir, cache_root=cache_root)
        # Read-only prepared check happens before taking the gate; no worker/build fallback.
        if overrides is not None:
            # Revalidate core pins first, then the explicit scoring-only overlay.
            sensitivity.launch_environment(cfg.environment(), overrides)
            arms[c.name] = sensitivity.declaration(c, overrides, requested_cpus, sample, seed)
        with index.prepare_index(c, cfg, prepared_only=True,
                **({'sensitivity': overrides} if overrides is not None else {})) as handle:
            models = dict(handle.model_hashes)
        baseline = baseline_dir / (sha256_text(c.name) + '.baseline.json')
        if action == 'check':
            compare.load_baseline(baseline)  # refuse an absent/corrupt baseline before scoring
        if overrides is not None:
            total, startup, expected_profiles[c.name] = sensitivity.estimate(
                c, request, models, runs_dir, arms[c.name])
        else:
            total, startup = (estimate_seconds(c, request, models, runs_dir),
                              estimate_startup_seconds(c, request, models, runs_dir))
        totals.append(total)
        startups.append(startup)
        for _, _, done in _history(c, request, models, runs_dir):
            footprint = done.get('gpu_engine_mib')
            if isinstance(footprint, (int, float)) and math.isfinite(footprint) and footprint > 0:
                footprints.append(footprint)
        loaded.append((c, qc, cfg, baseline, models))
    if len({c.name for c, *_ in loaded}) != len(loaded):
        raise RunRefused('duplicate collection names')
    gpu_enabled = str(loaded[0][2].environment().get('JUSTSEARCH_GPU_ENABLED', '')).lower() == 'true'
    footprint = max(footprints) if footprints else None
    overlap = overrides is None and bool(footprint and can_overlap(gpu_enabled, None, footprint))
    estimate = estimate_window(totals, startups, overlap)
    require_budget(estimate, window_seconds, full_suite=full_suite)
    out = runs_dir.resolve() / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S') + '-' + uuid.uuid4().hex[:8])
    out.mkdir(parents=True)
    plan = {'path': str(out/'plan.json'), 'repo_root': str(repo_root), 'action': action, 'budget': window_seconds,
            'estimate_seconds': estimate, 'request': request, 'mode': mode,
            'overlap': overlap, 'gpu_engine_mib': footprint,
            'commit': commit, 'dirty_hash': dirty,
            'hardware': {'system': platform.system(), 'machine': platform.machine(),
                         'cpu': platform.processor(), 'cpu_count': os.cpu_count(),
                         'gpu_enabled': loaded[0][2].environment().get('JUSTSEARCH_GPU_ENABLED')},
            'items': []}
    for n, (c, qc, cfg, baseline, models) in enumerate(loaded):
        folder = out / str(n)
        collections.write_collection(c, folder/'collection')
        plan['items'].append({'number': n, 'name': c.name, 'collection': str(folder/'collection'),
                              'index_config': _serial_config(cfg), 'run': str(folder/'run.jsonl'),
                              'ready': str(folder/'ready'), 'go': str(folder/'go'), 'done': str(folder/'done.json'),
                              **({'sensitivity': arms[c.name],
                                  'sensitivity_expected': expected_profiles[c.name]}
                                 if overrides is not None else {})})
    _write_json(out/'plan.json', plan)
    command = [sys.executable, str(repo_root/'scripts/dev/admission.py'), 'exclusive',
               '--purpose', 'search quality ' + action, '--gate-timeout', '1200',
               '--drain-timeout', '600', '--timeout', str(window_seconds), '--',
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
    comparisons, times, records, refusals, measurements = [], {}, [], {}, {}
    for item, (c, qc, cfg, baseline, models) in zip(plan['items'], loaded):
        record, errors = compare.load_run_or_reason(item['run'], c.name)
        # The reservation/startup failure explains missing output. Lead with it
        # so the command names the holders/deadline instead of an absent file.
        errors = problems + errors
        if record:
            refusals[c.name] = [r.qid for r in record.results if compare.syntax_refused(r)]
            errors += compare.validate_run(c, record, {c.version}, c.name,
                                           sensitivity=overrides is not None)
            if overrides is not None and (record.header.run_kind != 'sensitivity' or any(
                    record.header.sensitivity.get(k) != v for k, v in arms[c.name].items())):
                errors.append(f'{c.name}: sensitivity declaration differs from the planned arm')
            if not errors:
                measurements[c.name] = report.run_measurements(c, record, qc.groups)
            pinned = cfg.environment()
            if dict(record.header.model_hashes) != models or index.model_hashes(
                    Path(pinned['JUSTSEARCH_MODELS_DIR']), llm_off=inputs.llm_off(pinned)) != models:
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
        outcome = Outcome.SENSITIVITY if overrides is not None else Outcome.PASS
        details = ('scoring-only settings on prepared index' if overrides is not None else
                   'baseline stored' if action == 'baseline' else f'explicit-mode ablation {mode} (sensitivity information)')
        if action == 'baseline':
            for item, c, baseline in records:
                compare.write_baseline(item['run'], c, baseline)
    surface = 'Search window refined request' if mode is None else f'explicit-mode ablation {mode}'
    summary = {'outcome': outcome.value, 'surface': surface, 'request': request,
               'estimate_seconds': estimate, 'collection_seconds': times, 'details': details,
               'overlap_planned': overlap, 'syntax_refusals': refusals,
               'pending_labels': pending, 'measurements': measurements}
    _write_json(out/'summary.json', summary)
    atomic_write_text(out/'summary.md',
        f"# {outcome.value}: {surface}\n\n{details}\n\n"
        f"Candidate: {commit}. Window: {window_seconds:g}s. Estimate: {estimate:.1f}s.\n\n"
        + "Request (engine mode/pipeline defaults apply when omitted):\n\n```json\n"
        + json.dumps(request, indent=2) + "\n```\n\n"
        + "\n".join(f"- {name}: {elapsed:.1f}s" for name, elapsed in times.items())
        + "\n\n" + "\n".join(f"{name} syntax refusals ({len(ids)}): " + (', '.join(ids) or 'none')
                                  for name, ids in refusals.items())
        + "".join(f"\n\n{name}: {n} queries pending labels, excluded from scoring"
                  for name, n in pending.items() if n)
        + "\n\n" + "\n\n".join(report.measurement_text(name, row, overrides is not None)
                                            for name, row in measurements.items())
        + "\n\nPer-query rankings, served legs, reranker status/reason and errors: `*/run.jsonl`.\n"
        + "Comparison reports: `*/run.md`. Effective runtime and model hashes are in each run header.\n")
    waiting = ''.join(f'; {name}: {n} queries pending labels, not scored' for name, n in pending.items() if n)
    timing_line = '; '.join(f'{name} {report.timing_text(row)}' for name, row in measurements.items())
    details += ('; ' + timing_line) if timing_line else ''
    line = f'{outcome.value} {surface}: {report.compact(details, 3000)}; ' + ', '.join(f'{c} {s:.1f}s' for c,s in times.items()) + waiting + f'; report {out}'
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
