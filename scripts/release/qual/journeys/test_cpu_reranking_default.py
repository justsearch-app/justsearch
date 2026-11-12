"""Default CPU reranking is off; GPU qualification still requires it."""
from copy import deepcopy
import unittest
from unittest.mock import Mock, patch

import gpu
import linux
from test_windows_cpu import immediate


class CPURerankingDefaultTests(unittest.TestCase):
    def retrieval(self, cross_status='skipped'):
        return {'results': [linux.OPEN_FILE], 'searchTrace': {
            'effectiveMode': 'VECTOR', 'stages': [
                {'id': 'dense-retrieval', 'status': 'executed'},
                {'id': 'cross-encoder', 'status': cross_status,
                 'reason': 'PIPELINE_NOT_ELIGIBLE' if cross_status == 'skipped' else None}]}}

    def test_default_cpu_vector_query_accepts_skipped_cross_encoder(self):
        for platform in ('linux', 'windows'):
            with self.subTest(platform=platform):
                result = self.retrieval()
                run = Mock(platform=platform)
                run.api.return_value = result
                with patch.object(linux, 'wait_for', immediate):
                    self.assertEqual(linux.semantic_search(run), result)
                self.assertEqual(run.api.call_args.args[1]['mode'], 'vector')

    def test_cpu_default_still_rejects_fallback_blocked_or_inactive_dense_query(self):
        cases = []
        for flag in ('hybridFallback', 'vectorBlocked'):
            result = self.retrieval()
            result['searchTrace']['degradation'] = {flag: True}
            cases.append(result)
        for status in ('skipped', 'failed'):
            result = self.retrieval()
            result['searchTrace']['stages'][0]['status'] = status
            cases.append(result)
        result = self.retrieval()
        result['searchTrace']['effectiveMode'] = 'TEXT'
        cases.append(result)
        result = self.retrieval()
        result['results'] = []
        cases.append(result)
        for result in cases:
            with self.subTest(result=result):
                run = Mock()
                run.api.return_value = result
                with patch.object(linux, 'wait_for', immediate), self.assertRaises(AssertionError):
                    linux.semantic_search(run)

    def test_cpu_default_rejects_executed_or_unobserved_cross_encoder(self):
        cases = [self.retrieval('executed'), self.retrieval('failed')]
        absent = self.retrieval()
        absent['searchTrace']['stages'].pop()
        cases.append(absent)
        duplicate = deepcopy(self.retrieval())
        duplicate['searchTrace']['stages'].append({'id': 'cross-encoder', 'status': 'executed'})
        cases.append(duplicate)
        for result in cases:
            with self.subTest(result=result):
                run = Mock()
                run.api.return_value = result
                with patch.object(linux, 'wait_for', immediate), self.assertRaises(AssertionError):
                    linux.semantic_search(run)

    def test_gpu_query_keeps_cross_encoder_execution_required(self):
        for status in ('skipped', 'executed'):
            run = Mock()
            result = self.retrieval(status)
            run.api.return_value = result
            with patch.object(linux, 'wait_for', immediate):
                if status == 'skipped':
                    with self.assertRaises(AssertionError):
                        linux.semantic_search(run, reranking=True)
                else:
                    self.assertEqual(linux.semantic_search(run, reranking=True), result)

    def test_gpu_journey_requests_reranking(self):
        run = Mock(facts={})
        with patch.object(gpu, 'start_chat'), patch.object(gpu, 'require_gpu_encoders'), \
             patch.object(gpu, 'chat'), patch.object(linux, 'semantic_search') as semantic:
            gpu.gpu_installed_checks(run)
        semantic.assert_called_once_with(run, reranking=True)


if __name__ == '__main__':
    unittest.main()
