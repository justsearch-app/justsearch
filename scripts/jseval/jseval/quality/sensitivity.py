"""Explicit scoring-only overrides. Inherited inputs always retain their normal index key."""
from __future__ import annotations

from collections import Counter
from datetime import datetime
import json
import math
import os
from pathlib import Path
import re

import httpx
import psutil

from .. import index_identity
from jsharness import readiness
from . import inputs
from .types import canonical_json, sha256_text

# Engine reader evidence (paths relative to modules/, Java line numbers at 59e8bf815f):
# All names: configuration/.../resolved/ResolvedConfigBuilder.java resolves env/sysprops;
# indexer-worker/.../server/EncoderConfigurationProjection.java:263,303-308,346 projects
# these values. HeadlessApp.java:252 mirrors EMBED_GPU_ENABLED for worker resolution.
# Name                              ResolvedConfigBuilder   Runtime consumers
# JUSTSEARCH_RERANK_ENABLED          :1343                   reranker/.../RerankerConfig.java:127-142
# JUSTSEARCH_RERANK_GPU_ENABLED      :1345                   RerankerConfig.java:144; InferenceCompositionRoot.java:1239-1240
# JUSTSEARCH_RERANK_TOP_K            :1349                   RerankerConfig.java:142 (search candidate count)
# JUSTSEARCH_EMBED_GPU_ENABLED       :1270-1278              worker-core/.../embed/EmbeddingConfig.java:106;
#                                                          InferenceCompositionRoot.java:239,943
# JUSTSEARCH_ORT_INTRA_OP_THREADS    :1204                   ort-common/.../RuntimePolicyResolver.java:75;
#                                                          SessionOptionsApplier.java:56-57
# GPU budgets also read the projected switches: InferenceCompositionRoot.java:128-159,
# 211-283,315-330; app-services/.../gpu/GpuProfileResolver.java:51,54 (placement only).
# InferenceCompositionRoot.java:526-534,943,978-1008,1234-1240,1505-1509 selects
# exact generation files, probes EPs and builds sessions, not saved selections.
# QueryRoleSelectionResolver.java:49-55,93-117,123-155,179-205 selects in-memory
# query roles. KnowledgeServer.java:4797-4827 wires generation/query projections.
# Enabled/topK consumers: app-services/.../worker/KnowledgeSearchEngine.java:
# 633-635,771,1174,1191,1413,1640; SearchPipelinePresets.java:48,80 (search only).
# Enabled also: EncoderConfigurationProjection.java:361-369 (saved preference
# precedence); app-services/.../ai/runtime/RuntimeActivationService.java:633-634
# (status only). SettingsServiceImpl.java:73 and SettingsCommitCoordinator.java:
# 438,1032 read it only on an explicit settings operation, which scoring never calls;
# config/ConfigStoreRebuilder.java:295 imports the saved preference into resolution.
# Query reranking has no index writer. Encoder switches/threads also drive ingestion:
# admit ONLY a settled prepared index, never a build. KnowledgeServer.java:1965-1966,2012
# recovers jobs and starts ingestion at boot: require idle with prepared settings and stop
# that Engine under the data lock before starting an Engine with scoring overrides.
# GenerationModelSelection.java:43-46,69-84,99-122,125-139 retains/verifies exact saved
# model files and fingerprints; it has no persistence API. KnowledgeServer.java:1419
# loads it at boot; EncoderConfigurationProjection.java:148-187 keeps selected files.
# KnowledgeServer.java:1401,9615 binds model selections only for recorded build/promotion,
# not an adopted native generation. :1505-1513 compares committed fingerprints before
# opening the writer. No setting here enters the committed model/field fingerprint.
# KnowledgeServer.java:6835-6844 uses GenerationModelSelection.runtimeFingerprintInputs
# for expected commit metadata, not encoder placement/threads; that method's complete
# inputs are GenerationModelSelection.java:81-84. No bindRecordedModels is invoked
# for a native prepared generation. Legacy/unbound native generations use
# KnowledgeServer.java:6815-6828; EmbeddingFingerprint.java:100-131 discovers the
# model via resolveExistingModelFile, independent of the GPU flag. The remaining
# commit assets knobs are SsotCommitMetadataSource.java:115-125 (none allowlisted).
# S4 is relative to the ordinary scoring start:
# IndexWriter open / ingestion start / shutdown marker writes are common to default starts.
ALLOWLIST = {
    'JUSTSEARCH_RERANK_ENABLED': 'justsearch.rerank.enabled',
    'JUSTSEARCH_RERANK_GPU_ENABLED': 'justsearch.rerank.gpu.enabled',
    'JUSTSEARCH_RERANK_TOP_K': 'justsearch.rerank.top_k',
    'JUSTSEARCH_EMBED_GPU_ENABLED': 'justsearch.embed.gpu.enabled',
    'JUSTSEARCH_ORT_INTRA_OP_THREADS': 'justsearch.onnxruntime.intra_op_threads',
}
DEADLINES = frozenset({'DEADLINE_EXCEEDED', 'SEARCH_DEADLINE_EXCEEDED'})
SKIPS = DEADLINES | {'DISABLED'}
DEADLINE_NOTE = ('SEARCH_DEADLINE_EXCEEDED can also cover model loading or saturation by earlier '
                 'native runs; the trace does not separate them.')


class SensitivityRefused(ValueError):
    pass


def settings(pairs) -> dict[str, str]:
    out = {}
    for pair in pairs:
        name, sep, value = pair.partition('=')
        if not sep or name not in ALLOWLIST or not value or '=' in value:
            raise SensitivityRefused(f'--set {pair!r}: refused name or malformed NAME=VALUE')
        if name in out:
            raise SensitivityRefused(f'--set {name}: repeated name')
        if name in ('JUSTSEARCH_RERANK_TOP_K', 'JUSTSEARCH_ORT_INTRA_OP_THREADS'):
            if not re.fullmatch(r'[1-9][0-9]*', value) or int(value) > 2147483647:
                raise SensitivityRefused(f'--set {name}: expected a positive 32-bit integer')
        elif value not in ('true', 'false'):
            raise SensitivityRefused(f'--set {name}: expected true or false')
        out[name] = value
    # Higher-priority JVM properties cannot silently override an explicit arm.
    try:
        inputs.jvm_options(os.environ)
    except inputs.InputRefused as e:
        raise SensitivityRefused(f'--set {", ".join(out) or "settings"}: {e}') from None
    return out


def launch_environment(prepared, overrides):
    overrides = settings([f'{name}={value}' for name, value in overrides.items()])
    env = {**prepared, **overrides}
    # These run AFTER IndexConfig pins, never instead of those pins. #1146 stays fail-closed.
    try:
        inputs.jvm_options(env)
        inputs.native_selection(env)
        inputs.model_selection(env)
    except inputs.InputRefused as e:
        raise SensitivityRefused(str(e)) from None
    return env


def cpus(value: str | None) -> list[int] | None:
    if value is None:
        return None
    chosen = set()
    try:
        for part in value.split(','):
            if not re.fullmatch(r'[0-9]+(?:-[0-9]+)?', part):
                raise ValueError()
            ends = [int(n) for n in part.split('-')]
            lo, hi = ends[0], ends[-1]
            if hi < lo or hi - lo > 65536:
                raise ValueError()
            chosen.update(range(lo, hi+1))
        if not chosen or not chosen <= set(os.sched_getaffinity(0)):
            raise ValueError()
    except (ValueError, AttributeError, OSError):
        raise SensitivityRefused(f'--cpus {value!r}: malformed or outside allowed CPUs') from None
    return sorted(chosen)


def pin_cpus(requested):
    if requested is not None:
        os.sched_setaffinity(0, requested)
        if set(os.sched_getaffinity(0)) != set(requested):
            raise SensitivityRefused('effective worker CPU set differs from --cpus')


def require_cpu_threads(overrides, requested):
    # ORT's default thread pool can assign affinities beyond an inherited process
    # mask. An explicit positive count disables that automatic placement.
    if requested is not None:
        value = overrides.get('JUSTSEARCH_ORT_INTRA_OP_THREADS')
        if value is None or not 1 <= int(value) <= len(requested):
            raise SensitivityRefused('--cpus requires --set JUSTSEARCH_ORT_INTRA_OP_THREADS=N '
                                     f'with 1 <= N <= {len(requested)}')


def engine_cpus(handle, requested, check=None):
    if check is not None:
        check.update(ok=False, threads=[])
    parent = psutil.Process(handle._backend.proc.pid)
    processes = [parent, *parent.children(recursive=True)]
    jvms = [p for p in processes if p.name() in ('java', 'java.exe')]
    if not jvms:
        raise SensitivityRefused('cannot read effective Engine JVM CPU set')
    effective = sorted(jvms[0].cpu_affinity())
    for process in processes:
        if requested is None:
            continue
        for thread in process.threads():
            mask = 'unavailable'
            try:
                status = Path(f'/proc/{process.pid}/task/{thread.id}/status').read_text()
                mask = next(line.split(':', 1)[1].strip() for line in status.splitlines()
                            if line.startswith('Cpus_allowed_list:'))
                if not re.fullmatch(r'[0-9]+(?:-[0-9]+)?(?:,[0-9]+(?:-[0-9]+)?)*', mask):
                    raise ValueError('malformed Cpus_allowed_list')
                allowed = set()
                for part in mask.split(','):
                    ends = [int(n) for n in part.split('-')]
                    lo, hi = ends[0], ends[-1]
                    if hi < lo:
                        raise ValueError('reversed CPU range')
                    allowed.update(range(lo, hi+1))
            except (OSError, ValueError, StopIteration) as e:
                raise SensitivityRefused(f'thread {thread.id} pid {process.pid} mask {mask!r}: '
                                         f'cannot read CPU affinity ({type(e).__name__})') from None
            if check is not None:
                check['threads'].append({'pid': process.pid, 'tid': thread.id, 'mask': mask})
            if not allowed <= set(requested):
                raise SensitivityRefused(f'thread {thread.id} pid {process.pid} mask {mask} '
                                         f'outside --cpus {requested}')
        leader_mask = process.cpu_affinity()
        if set(leader_mask) != set(requested):
            raise SensitivityRefused(f'effective CPU set differs from --cpus for thread {process.pid} '
                                     f'pid {process.pid} mask {leader_mask}')
    if check is not None:
        check['ok'] = True
    return effective


def query_ids(collection, sample, seed):
    ids = sorted(collection.queries)
    if sample is not None:
        if isinstance(sample, bool) or not isinstance(sample, int) or sample <= 0 or len(ids) < sample:
            raise SensitivityRefused(f'--sample {sample}: only {len(ids)} eligible scored queries')
        ids = sorted(sorted(ids, key=lambda q: (sha256_text(canonical_json([seed, q])), q))[:sample])
    return ids


def declaration(c, overrides, requested, sample, seed):
    ids = query_ids(c, sample, seed)
    return {'settings': inputs.recorded(overrides), 'requested_cpus': requested,
            'sample_size': len(ids), 'seed': seed,
            'queries': {qid: c.queries[qid].sha256 for qid in ids}}


def require_idle(status):
    """Missing counts cannot authorize a scoring-only encoder change."""
    s = readiness.flatten_status(status)
    counts = ('pendingJobs', 'processingJobsCount', 'embeddingPendingCount', 'pendingNerCount')
    if s.get('indexState') != 'IDLE' or any(s.get(k) != 0 for k in counts):
        raise SensitivityRefused('sensitivity refuses pending ingestion/enrichment work or unavailable idle evidence')
    if any(s.get(k, 0) != 0 for k in ('pendingJobsCount', 'chunkEmbeddingPendingCount',
                                      'spladePendingCount', 'chunkSpladePendingCount')):
        raise SensitivityRefused('sensitivity refuses pending ingestion/enrichment work')
    for audit in s.get('completeness', []):
        if audit.get('missing', 0) or audit.get('failed', 0):
            raise SensitivityRefused('sensitivity refuses pending enrichment work')


def confirm_identity(handle, entry):
    """index_identity's checks 1-4 and 6, deliberately no search/CE canary (S6).

    Population, full source/extracted content and text probes still run in index._confirm.
    This is local to the labelled arm: preparation and ordinary adoption are unchanged.
    """
    failures, checks = [], {}
    fail = failures.append
    try:
        live = index_identity.compute_live_identity(handle.base_url, handle.env,
                                                    index_key=handle.index_key)
        if live.key != (entry.get('identity') or {}).get('key'):
            fail('identity.key: prepared and live index identity differ')
        checks['identity'] = {'ok': not failures, 'key': live.key}
        with httpx.Client(base_url=handle.base_url, timeout=30) as client:
            status, error = index_identity._get_json(client, '/api/status')
            require_idle(status)
            index_identity._confirm_binding(client, handle.data_dir, fail, checks)
            index_identity._confirm_generation(status, error, entry, fail, checks)
            index_identity._confirm_counts(status, error, entry, fail, checks)
        index_identity._confirm_watched_roots(handle.data_dir, entry, fail, checks)
    except Exception as e:
        fail(f'sensitivity identity confirmation: {type(e).__name__}: {e}')
    return index_identity.ConfirmResult(not failures, failures, checks)


def effective_settings(base_url, overrides):
    with httpx.Client(base_url=base_url, timeout=30) as client:
        def get(path):
            response = client.get(path)
            response.raise_for_status()
            return response.json()
        config = get('/api/debug/effective-config')
        policies = get('/api/debug/session-policies')
        encoders = get('/api/inference/encoders')
    resolved = {r['key']: r.get('value') for r in config.get('resolvedConfig', [])}
    for name, value in overrides.items():
        actual = resolved.get(ALLOWLIST[name])
        if str(actual).lower() != value:
            raise SensitivityRefused(f'{name}: engine reports {actual!r}, requested {value!r}')
    unreported = lambda reason: {'value': 'unreported', 'reason': reason}
    def reported(value, reason):
        return value if value is not None else unreported(reason)
    models = policies.get('models') or {}
    reranker = models.get('RERANKER') or {}
    variant = reranker.get('variant') or {}
    def provider(role):
        # EncoderRole.java:23-34: consumerName(), not packageId().
        row = (encoders.get('encoders') or {}).get(role) or {}
        return reported(row.get('currentAccelerator'), 'no live encoder runtime observation for '+role)
    return {'reranker_enabled': reported(resolved.get('justsearch.rerank.enabled'),
                                          'effective config has no resolved reranker enable flag'),
            'reranker_execution_provider': provider('reranker'),
            'query_embedder_execution_provider': provider('embed'),
            'ort_intra_op_threads': reported((policies.get('runtime') or {}).get('session', {}).get('intraOpThreads'),
                                             'ORT default thread count is not exposed'),
            'reranker_file': inputs.recorded({'file': variant.get('modelFile')})['file']
                if variant.get('modelFile') else unreported('no selected reranker policy variant'),
            'reranker_precision': reported(variant.get('precision'), 'no selected reranker policy variant')}


def counts(c, results, group_keys=()):
    from .collections import query_group
    def tally(rows):
        reasons = Counter('executed' if r.served.reranker == 'executed' else
                          r.served.reason or 'unknown' for r in rows)
        longest = current = 0
        for r in rows:
            current = current+1 if r.served.reranker == 'skipped' and r.served.reason in DEADLINES else 0
            longest = max(longest, current)
        return {'queries': len(rows), 'reranker': dict(sorted(reasons.items())),
                'longest_consecutive_deadline_skips': longest}
    groups = {query_group(c, qid, group_keys): [] for qid in c.queries}
    for r in results:
        groups.setdefault(query_group(c, r.qid, group_keys), []).append(r)
    return {'all': tally(results), **{g: tally(rows) for g, rows in sorted(groups.items())}}


def history_key(declaration, effective, cpuset):
    return {**declaration, 'effective_settings': effective, 'effective_cpus': cpuset}


def estimate(c, request, models, runs, declaration):
    """Reuse one observed profile only, and require its readback again in the worker."""
    from .compare import validate_run
    from .types import RunRecord
    observed = []
    allowed = declaration['requested_cpus'] or sorted(os.sched_getaffinity(0))
    for path in runs.glob('*/**/*.jsonl'):
        try:
            record = RunRecord.read(path)
            h, arm = record.header, record.header.sensitivity
            if (h.run_kind != 'sensitivity' or h.collection_version != c.version
                    or h.request_config != request or h.model_hashes != models
                    or any(arm.get(k) != v for k, v in declaration.items())
                    or arm.get('effective_cpus') != allowed
                    or not arm.get('effective_settings')
                    or validate_run(c, record, {c.version}, 'history', sensitivity=True)):
                continue
            done = json.loads((path.parent/'done.json').read_text())
            if done.get('error') is not None:
                continue
            elapsed = (datetime.fromisoformat(h.ended) - datetime.fromisoformat(h.started)).total_seconds()
            phases = done.get('phase_seconds', {})
            total = elapsed - phases.get('waiting', 0)
            startup = phases.get('startup', 45)
            if not all(math.isfinite(v) and v > 0 for v in (total, startup)):
                continue
            expected = {k: arm[k] for k in ('effective_settings', 'effective_cpus')}
            observed.append((h.started, total, startup, expected))
        except (ValueError, OSError, KeyError, TypeError):
            continue
    if observed:
        observed.sort(key=lambda row: row[0])
        expected = observed[-1][3]
        matching = [row for row in observed if row[3] == expected][-5:]
        return max(row[1] for row in matching)*1.25+5, max(row[2] for row in matching)*1.25, expected
    return cold_estimate(declaration['sample_size']), 45, None


def check_profile(arm, expected):
    if expected is not None and any(arm.get(k) != v for k, v in expected.items()):
        raise SensitivityRefused('effective settings/CPU set differ from the sensitivity history used for the window estimate')


def cold_estimate(sample_size):
    # Two full content/identity confirmations replace the two CE canaries. Include
    # startup, both witnesses and teardown; the window adds its existing 45s cleanup.
    return 90 + 15 * sample_size
