"""Final review regressions for CPU activation and GPU model readiness."""
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

import gpu
import linux
import test_cpu_reranking_default
from test_windows_cpu import immediate


class ReviewRound3Tests(unittest.TestCase):
    def test_cpu_rejects_cross_encoder_skips_other_than_default_off(self):
        for reason in ('RPC_FAILED', 'SEARCH_DEADLINE_EXCEEDED', 'DISABLED', None):
            with self.subTest(reason=reason):
                result = test_cpu_reranking_default.CPURerankingDefaultTests().retrieval()
                result['searchTrace']['stages'][-1]['reason'] = reason
                run = Mock()
                run.api.return_value = result
                with patch.object(linux, 'wait_for', immediate), self.assertRaises(AssertionError):
                    linux.semantic_search(run)

    def test_gpu_j3_requires_active_reranker_separately_from_cuda_embed(self):
        for active in (False, None, True):
            with self.subTest(rerankerActive=active):
                run = Mock(facts={})
                run.api.return_value = {'onnxFeatures': [
                    {'id': 'embed', 'modelActive': True, 'executionProvider': 'cuda', 'gpuFallback': False},
                    {'id': 'reranker', 'modelActive': active}]}
                with patch.object(gpu, 'start_chat'), patch.object(gpu, 'chat') as chat, \
                     patch.object(linux, 'semantic_search', return_value={}):
                    if active is True:
                        gpu.gpu_installed_checks(run)
                        chat.assert_called_once()
                    else:
                        with self.assertRaisesRegex(gpu.ProductFailure, 'reranker'):
                            gpu.gpu_installed_checks(run)
                        chat.assert_not_called()

    def test_journey_docs_describe_two_required_cpu_models_without_dangling_clause(self):
        text = Path(linux.__file__).with_name('README.md').read_text()
        self.assertFalse('activation, the three\nrequired runtime components' in text)
        self.assertFalse('the three required active runtime components' in text)
        self.assertFalse('need not be active on CPU.\n  and installation fully complete.' in text)


if __name__ == '__main__':
    unittest.main()
