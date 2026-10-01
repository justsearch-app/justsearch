"""Fixed-window ingestion/load tests: no backend, model or stack required."""
import json
import asyncio
from pathlib import Path
import tempfile
from types import SimpleNamespace
import time
import unittest
from unittest.mock import patch

from jseval import bulk_load
from jseval.search_load import SearchLoadRunner, SearchLoadSpec, search_body
import httpx


class Response:
    status_code = 200
    headers = {}

    def __init__(self, body):
        self.body = body

    def raise_for_status(self):
        pass

    def json(self):
        return self.body

    def read(self):
        return b'{}'


class Client:
    def __init__(self):
        self.headers = {}
        self.submitted = False
        self.posts = []
        self.completed = 0

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def get(self, *args, **kwargs):
        self.completed += 1
        return Response({'workerRpcStale': False, 'indexedDocuments': int(self.submitted),
            'chunkEmbeddingCompletedCount': self.completed, 'chunkEmbeddingPendingCount': 9,
            'pendingJobs': int(self.submitted), 'embeddingPendingCount': 2,
            'spladePendingCount': 2, 'pendingNerCount': 2})

    def post(self, url, json, timeout):
        self.posts.append(json)
        time.sleep(min(.002, timeout))
        return Response({})


class AsyncClient:
    def __init__(self, client):
        self.client = client

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        pass

    async def post(self, url, json):
        self.client.posts.append(json)
        await asyncio.sleep(.002)
        return Response({})


class BulkLoadTest(unittest.TestCase):
    def test_search_journal_retains_504_before_cycle_summary_or_stop(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'search-cycle.jsonl'
            runner = SearchLoadRunner('http://127.0.0.1:33221', ['query'],
                SearchLoadSpec(mode='continuous', outcomes_file=str(file)))
            client = httpx.Client(transport=httpx.MockTransport(
                lambda request: httpx.Response(504, request=request)), base_url='http://127.0.0.1:33221')
            with client:
                runner._issue(client, search_body('query'))
            events = [json.loads(line) for line in file.read_text().splitlines()]
            self.assertEqual([e['event'] for e in events], ['request-start', 'http-response', 'request-outcome'])
            self.assertEqual(events[1]['status'], 504)
            self.assertEqual(events[-1]['status'], 504)
            self.assertEqual(events[-1]['error'], 'REQUEST_FAILURE')
            self.assertEqual(runner._errors, 1)
            self.assertFalse(runner._latencies_ms)

    def test_search_journal_persists_timeout_and_success_separately(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'search-cycle.jsonl'
            runner = SearchLoadRunner('http://127.0.0.1:33221', ['query'],
                SearchLoadSpec(mode='continuous', outcomes_file=str(file)))
            def reply(request):
                if request.read().find(b'timeout') >= 0:
                    raise httpx.ReadTimeout('request expired')
                return httpx.Response(200, request=request)
            with httpx.Client(transport=httpx.MockTransport(reply), base_url='http://127.0.0.1:33221') as client:
                runner._issue(client, search_body('timeout'))
                runner._issue(client, search_body('success'))
            outcomes = [json.loads(line) for line in file.read_text().splitlines()
                if json.loads(line)['event'] == 'request-outcome']
            self.assertEqual(outcomes[0]['error'], 'TIMEOUT')
            self.assertEqual(outcomes[1]['status'], 200)
            self.assertNotIn('error', outcomes[1])
            self.assertEqual([e['requestId'] for e in outcomes], [0, 1])

    def test_submits_without_wait_then_equal_blocks_and_boundary_samples(self):
        client = Client()
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / 'document.txt').write_text('corpus bytes', encoding='utf-8')
            args = SimpleNamespace(output_dir=directory, corpus_dir=directory,
                base_url='http://127.0.0.1:33221', workload='agent-idle',
                block_seconds=.02, admission_script='unused')
            def submit(*args, **kwargs):
                client.submitted = True
            with patch.object(bulk_load, 'open_client', return_value=client), \
                 patch.object(bulk_load.httpx, 'AsyncClient', return_value=AsyncClient(client)), \
                 patch.object(bulk_load, '_ensure_materialized', return_value=5183), \
                 patch.object(bulk_load, '_foreground_queries', return_value=['q1', 'q2']), \
                 patch.object(bulk_load, 'add_watched_root', side_effect=submit) as ingest:
                bulk_load.run(args)
            result = json.loads((Path(directory) / 'bulk-load.json').read_text())
            self.assertEqual(ingest.call_count, 1)
            self.assertEqual(result['modes'], ['hybrid', 'lexical'])
            self.assertEqual(result['corpusManifest'][0][0], 'document.txt')
            self.assertEqual(len(result['corpusManifest'][0][1]), 64)
            self.assertEqual(set(result['encoderSessions']), {'start', 'end'})
            for row in result['requests']:
                if row.get('windowBoundary'):
                    self.assertEqual(row['cancellationCause'], 'mode-block-end' if row['mode'] == 'hybrid' else 'fixed-window-end')
            self.assertEqual(result['durationSeconds'], .04)
            self.assertEqual(result['samples'][0]['offsetSeconds'], 0)
            self.assertEqual(result['samples'][-1]['offsetSeconds'], .04)
            self.assertTrue(all(s['active'] for s in result['samples']))
            self.assertEqual(set(result['searchP95']), {'hybrid', 'lexical'})
            for mode in result['modes']:
                rows = [r for r in result['requests'] if r['mode'] == mode]
                self.assertTrue(rows)
                self.assertEqual(rows[0]['queryIndex'], 0)
            self.assertTrue((Path(directory) / 'phases.json').exists())

    def test_corpus_manifest_binds_bytes_and_names(self):
        with tempfile.TemporaryDirectory() as directory:
            corpus = Path(directory)
            first = corpus / 'first.txt'
            first.write_text('first', encoding='utf-8')
            original = bulk_load.corpus_manifest(corpus)
            first.write_text('second', encoding='utf-8')
            self.assertNotEqual(original, bulk_load.corpus_manifest(corpus))
            first.write_text('first', encoding='utf-8')
            first.rename(corpus / 'renamed.txt')
            self.assertNotEqual(original, bulk_load.corpus_manifest(corpus))

    def test_encoder_session_observations_preserve_lazy_state_and_errors_without_gating(self):
        client = Client()
        client.get = lambda endpoint, **kwargs: Response({'onnxFeatures': [
            {'id': 'embed', 'modelPath': None, 'fallbackReason': 'lazy'}]})
        observed = bulk_load.encoder_sessions(client)
        self.assertIsNone(observed['ai']['onnxFeatures'][0]['modelPath'])
        def unavailable(*args, **kwargs):
            raise ValueError('session endpoint unavailable')
        client.get = unavailable
        self.assertIn('unavailable', bulk_load.encoder_sessions(client)['aiError'])

    def test_stale_or_missing_counts_fail_instead_of_zero_progress(self):
        client = Client()
        client.get = lambda *a, **k: Response({'workerRpcStale': True})
        with self.assertRaisesRegex(ValueError, 'stale'):
            bulk_load.snapshot(client, 5183)
        client.get = lambda *a, **k: Response({})
        with self.assertRaisesRegex(ValueError, 'numeric'):
            bulk_load.snapshot(client, 5183)


if __name__ == '__main__':
    unittest.main()
