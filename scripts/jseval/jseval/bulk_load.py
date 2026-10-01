"""Bounded Lane F load during ingestion; never waits for enrichment readiness.

Run as python -m jseval.bulk_load. Uses the canonical corpus projection,
search body/client, and status flattener; the root driver owns the stack.
"""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
import subprocess
import threading
import time

import httpx

from .commands.run import _foreground_queries
from .ingest import _ensure_materialized, add_watched_root
from .readiness import flatten_status
from .search_load import open_client, search_body, percentile


def snapshot(client, expected):
    response = client.get('/api/status', timeout=5)
    response.raise_for_status()
    raw = response.json()
    if raw.get('workerRpcStale') is True:
        raise ValueError('stale status')
    s = flatten_status(dict(raw))
    keys = ['indexedDocuments', 'chunkEmbeddingCompletedCount',
            'chunkEmbeddingPendingCount', 'pendingJobs', 'embeddingPendingCount',
            'spladePendingCount', 'pendingNerCount']
    if any(type(s.get(k)) not in (int, float) or s[k] < 0 for k in keys):
        raise ValueError('missing numeric indexing counters')
    active = s['indexedDocuments'] < expected or any(s[k] > 0 for k in keys[2:])
    return {'observedAtMs': time.time() * 1000, 'active': active,
            **{k: s[k] for k in keys}, 'raw': raw}


def corpus_manifest(corpus):
    return [[str(p.relative_to(corpus)).replace('\\', '/'), hashlib.sha256(p.read_bytes()).hexdigest()]
            for p in sorted(corpus.rglob('*')) if p.is_file()]


def encoder_sessions(client):
    result = {}
    for name, endpoint in [('ai', '/api/ai/runtime/status'), ('manifest', '/api/runtime/manifest')]:
        try:
            response = client.get(endpoint, timeout=5)
            response.raise_for_status()
            result[name] = response.json()
        except Exception as error:
            result[name + 'Error'] = str(error)
    return result


def run(args):
    out = Path(args.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    corpus = Path(args.corpus_dir)
    count = _ensure_materialized('scifact', corpus, None)
    queries = _foreground_queries('scifact', 'bulk-load')
    result = {'kind': 'lane-f-bulk-load.v1', 'workload': args.workload, 'expectedDocuments': count,
              'corpusManifest': corpus_manifest(corpus),
              'blockSeconds': args.block_seconds, 'modes': ['hybrid', 'lexical'],
              'concurrency': 1, 'queryPoolHash': hashlib.sha256(
                  json.dumps(queries, ensure_ascii=False).encode()).hexdigest(),
              'samples': [], 'requests': []}
    stop = threading.Event()
    admission = None
    with open_client(args.base_url) as client:
        initial = snapshot(client, count)
        if initial['indexedDocuments'] != 0:
            raise ValueError('bulk load requires a fresh empty index')
        add_watched_root(args.base_url, corpus, timeout_sec=30,
                         session_token=os.environ.get('JUSTSEARCH_SESSION_TOKEN'))
        # Bound watcher startup, not enrichment completion.
        by = time.monotonic() + 60
        while True:
            first = snapshot(client, count)
            if first['pendingJobs'] > 0 or first['indexedDocuments'] > 0:
                break
            if time.monotonic() >= by:
                raise ValueError('watcher did not begin indexing within 60 seconds')
            time.sleep(1)
        start = time.monotonic()
        end = start + 2 * args.block_seconds
        result['startedAtMs'] = time.time() * 1000
        result['durationSeconds'] = 2 * args.block_seconds
        first['offsetSeconds'] = 0
        result['samples'].append(first)
        result['encoderSessions'] = {'start': encoder_sessions(client)}

        def sample():
            with open_client(args.base_url) as status_client:
                while not stop.wait(min(5, max(0, end - time.monotonic()))):
                    if time.monotonic() >= end:
                        break
                    try:
                        row = snapshot(status_client, count)
                        row['offsetSeconds'] = time.monotonic() - start
                        result['samples'].append(row)
                    except Exception as error:
                        result['samples'].append({'offsetSeconds': time.monotonic() - start,
                                                  'error': str(error)})

        sampler = threading.Thread(target=sample)
        sampler.start()
        try:
            if args.workload == 'scripted-agent':
                admission = subprocess.Popen(['node', args.admission_script,
                    '--capture-workload', str(out), '--base-url', args.base_url,
                    '--stop-file', str(out / 'admission.stop'),
                    '--end-at-ms', str(int(result['startedAtMs'] + result['durationSeconds'] * 1000))])
            async def foreground():
                async with httpx.AsyncClient(base_url=args.base_url,
                        headers=client.headers, timeout=30) as search_client:
                    for index, mode in enumerate(result['modes']):
                        block_end = start + (index + 1) * args.block_seconds
                        query_index = 0
                        while time.monotonic() < block_end:
                            remaining = block_end - time.monotonic()
                            began = time.monotonic()
                            row = {'mode': mode, 'queryIndex': query_index % len(queries),
                                   'offsetSeconds': began - start}
                            try:
                                response = await asyncio.wait_for(search_client.post(
                                    '/api/knowledge/search', json=search_body(
                                        queries[row['queryIndex']], search_mode=mode)), timeout=remaining)
                                row.update(status=response.status_code,
                                           retryAfter=response.headers.get('Retry-After'))
                                if response.status_code == 429:
                                    body = response.json()
                                    row.update(code=body.get('errorCode') or body.get('code'),
                                               retrySafe=body.get('retrySafe'))
                            except asyncio.TimeoutError:
                                # Explicit block-boundary cancellation; httpx's
                                # independent 30-second request timeout still fails.
                                row.update(error='WINDOW_BOUNDARY_CANCELLED', windowBoundary=True,
                                           cancellationCause='mode-block-end' if index == 0 else 'fixed-window-end')
                            except Exception as error:
                                row['error'] = str(error)
                            row['durationMs'] = (time.monotonic() - began) * 1000
                            result['requests'].append(row)
                            query_index += 1
            asyncio.run(foreground())
            result['endedAtMs'] = time.time() * 1000
        finally:
            stop.set()
            sampler.join(timeout=6)
            final = snapshot(client, count)
            final['offsetSeconds'] = result['durationSeconds']
            result['samples'].append(final)
            result['encoderSessions']['end'] = encoder_sessions(client)
            (out / 'phases.json').write_text(json.dumps({'foreground-load': [
                time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(result['startedAtMs'] / 1000)),
                time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(result.get('endedAtMs', time.time() * 1000) / 1000))]}))
            (out / 'admission.stop').write_text('stop')
            if admission is not None:
                admission.wait(timeout=10)
                result['admissionExitCode'] = admission.returncode
            for mode in result['modes']:
                durations = sorted(r['durationMs'] for r in result['requests']
                    if r['mode'] == mode and not r.get('error') and 200 <= r.get('status', 0) < 300)
                result.setdefault('searchP95', {})[mode] = percentile(durations, 95) if durations else None
            (out / 'bulk-load.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--corpus-dir', required=True)
    parser.add_argument('--output-dir', required=True)
    parser.add_argument('--workload', choices=['agent-idle', 'scripted-agent'], required=True)
    parser.add_argument('--admission-script', required=True)
    parser.add_argument('--block-seconds', type=int, default=600)
    options = parser.parse_args()
    if options.block_seconds <= 0:
        parser.error('--block-seconds must be positive')
    run(options)
