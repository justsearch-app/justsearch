"""Final review regressions for CPU activation and GPU model readiness."""
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

import gpu
import linux
import test_cpu_reranking_default
from test_windows_cpu import immediate


class ReviewRound3Tests(unittest.TestCase):
    def check_resumed_activation(self, *, late=False):
        run = Mock(platform='linux', facts={'resumedSetup': True})
        run.wd.text.return_value = 'Chat is not supported here.'
        run.wd.execute.return_value = []
        active = {'state': 'idle', 'phase': 'done', 'installedFully': True}
        ambiguous = dict(active, phase='idle')
        pending = dict(active, phase='activation_required')
        # Even a stale positive completeness flag cannot override pending activation.
        statuses = iter(([active] if late else [ambiguous]) + [pending, pending, active])
        runtime = {'onnxFeatures': [{'id': name, 'modelActive': True}
                                   for name in ('embed', 'citation_scorer')]}
        def api(path, *args):
            if path == '/api/ai/install/status':
                return next(statuses)
            return {'/api/ai/install/plan-preview': {'downloadProfile': 'CPU', 'components': [
                        {'id': 'chat', 'state': 'unavailable'}, {'id': 'embedding', 'state': 'installed'}]},
                    '/api/ai/install/manifest': {}, '/api/ai/runtime/status': runtime}[path]
        run.api.side_effect = api
        def bounded(fn, description, **kwargs):
            if description == 'Default CPU AI install' and not late:
                self.assertIsNone(fn(), 'Active embed must not settle ambiguous cached install')
            for _ in range(3):
                value = fn()
                if value:
                    return value
            raise AssertionError(description)
        with patch.object(linux, 'wait_for', bounded), \
             patch.object(linux, 'semantic_search', return_value={}), \
             patch.object(linux, 'activate_downloaded') as activate:
            result = linux.install_ai(run)
        activate.assert_called_once_with(run)
        self.assertEqual(result['installed'], active)
        run.wd.click.assert_not_called()

    def test_active_embed_waits_for_pending_candidate_then_activates_once(self):
        self.check_resumed_activation()

    def test_activation_poll_handles_late_pending_candidate_once(self):
        self.check_resumed_activation(late=True)

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
