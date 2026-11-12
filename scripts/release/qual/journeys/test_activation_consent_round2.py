"""J3 callers require activation end state after optional/withdrawn consent."""
import itertools
import types
import unittest
from unittest.mock import Mock, patch

import gpu
import linux
import runner
import windows
import test_windows_cpu


class ActivationEndStateTests(unittest.TestCase):
    def cpu_run(self, platform, active, withdrawn=False, operation_state=None):
        run = Mock(platform=platform, facts={}, args=types.SimpleNamespace(resume=False))
        status = test_windows_cpu.WindowsCPUReadinessTests().status()
        status.update(phase='done' if active else 'activation_required',
                      installedFully=platform == 'linux' and active)
        runtime = {'onnxFeatures': [{'id': name, 'modelActive': active}
                                   for name in ('embed', 'citation_scorer')]}
        run.wd.text.return_value = 'Chat is not supported here.'
        observations = iter([{'typed': True, 'blocked': True, 'operationKeys': []},
                             {'typed': False, 'operationKeys': []}])

        def dom(script):
            if 'authorization-typed-input' in script:
                return next(observations, {'typed': False, 'operationKeys': []}) if withdrawn else {
                    'typed': False, 'operationKeys': []}
            return ['activation'] if operation_state else []

        run.wd.execute.side_effect = dom
        responses = {
            '/api/ai/install/plan-preview': {'downloadProfile': 'CPU', 'components': [
                {'id': 'chat', 'state': 'unavailable'}, {'id': 'embedding', 'state': 'installed'}]},
            '/api/ai/install/manifest': {}, '/api/ai/install/status': status,
            '/api/ai/runtime/status': runtime,
            '/api/operation-history/activation': {'state': operation_state},
        }
        def api(path):
            if path == '/api/ai/install/status' and not any(
                    call.args[0] == 'button[aria-label="Activate downloaded models"]'
                    for call in run.wd.click.call_args_list):
                return dict(status, phase='activation_required', installedFully=False)
            return responses[path]

        run.api.side_effect = api
        run.target.json.return_value = {}
        run.memory_sample.return_value = {}
        return run

    def run_cpu(self, run):
        waits = []

        def fast_wait(fn, description, seconds=90, interval=1, error=AssertionError):
            waits.append((description, seconds))
            return runner.wait_for(fn, description, seconds=5, interval=0, error=error)

        with patch.object(linux, 'wait_for', side_effect=fast_wait), \
             patch.object(runner.time, 'monotonic', side_effect=itertools.count()), \
             patch.object(runner.time, 'sleep'), \
             patch.object(linux, 'semantic_search', return_value={}) as search:
            result = (windows if run.platform == 'windows' else linux).install_ai(run)
            self.assertIn(('Downloaded default CPU search encoders activated', 900), waits)
            search.assert_called_once_with(run)
            return result

    def test_click_that_changes_nothing_fails_callers_activation_wait(self):
        for platform in ('linux', 'windows'):
            with self.subTest(platform=platform):
                run = self.cpu_run(platform, active=False)
                with self.assertRaisesRegex(AssertionError, 'Downloaded default CPU search encoders activated.*timed out'):
                    self.run_cpu(run)
                self.assertNotIn('ai', run.facts)
                consent = [c.args[1] for c in run.evidence.call_args_list
                           if c.args[0] == 'J3-activation-consent'][-1]
                self.assertEqual(consent['path'], 'noneObserved')
                run.wd.click.assert_any_call('button[aria-label="Activate downloaded models"]')

    def test_withdrawn_prompt_and_active_end_state_pass(self):
        for platform in ('linux', 'windows'):
            with self.subTest(platform=platform):
                run = self.cpu_run(platform, active=True, withdrawn=True)
                self.assertEqual(self.run_cpu(run)['installed']['phase'], 'done')
                consent = [c.args[1] for c in run.evidence.call_args_list
                           if c.args[0] == 'J3-activation-consent'][-1]
                self.assertEqual(consent['path'], 'withdrawn')
                self.assertIn('ai', run.facts)

    def test_failed_or_cancelled_receipts_fail_even_with_active_models(self):
        for platform in ('linux', 'windows'):
            for state in ('failed', 'cancelled'):
                with self.subTest(platform=platform, state=state):
                    run = self.cpu_run(platform, active=True, withdrawn=True, operation_state=state)
                    with self.assertRaisesRegex(runner.ProductFailure, 'Model activation failed'):
                        self.run_cpu(run)
                    self.assertNotIn('ai', run.facts)

    def test_gpu_no_op_still_fails_its_900_second_activation_wait(self):
        run = self.cpu_run('linux', active=False)
        run.args = types.SimpleNamespace(resume=False)
        run.window_api.return_value = {'success': True}
        waits = []

        def fast_wait(fn, description, seconds=90, interval=1, error=AssertionError):
            waits.append((description, seconds))
            return runner.wait_for(fn, description, seconds=5, interval=0, error=error)

        with patch.object(gpu, 'require_gpu_plan'), \
             patch.object(linux, 'wait_for', side_effect=fast_wait), \
             patch.object(gpu, 'wait_for', side_effect=fast_wait), \
             patch.object(runner.time, 'monotonic', side_effect=itertools.count()), \
             patch.object(runner.time, 'sleep'), \
             self.assertRaisesRegex(AssertionError, 'Default GPU AI activation.*timed out'):
            gpu.gpu_install(run)
        self.assertIn(('Default GPU AI activation', 900), waits)
        self.assertNotIn('gpuInstalled', run.facts)


if __name__ == '__main__':
    unittest.main()
