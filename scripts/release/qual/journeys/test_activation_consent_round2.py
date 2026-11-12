"""Consent progress must belong to this activation; polling failures are infrastructure."""
import itertools
import unittest
from unittest.mock import Mock, patch

import linux
import runner


class ActivationConsentRound2Tests(unittest.TestCase):
    def run_fixture(self, baseline_keys=(), baseline_install=None, keys=(), install=None,
                    operation_id='core.activate-installed-models', typed=True):
        run = Mock()
        baseline_install = baseline_install or {'state': 'completed', 'phase': 'activation_required'}
        install = install or baseline_install
        clicked = False
        observations = itertools.chain([{'typed': typed, 'operationKeys': list(keys)}],
                                       itertools.repeat({'typed': False, 'operationKeys': list(keys)}))

        def click(selector):
            nonlocal clicked
            clicked = True

        def dom(script):
            if not clicked:
                return {'typed': False, 'operationKeys': list(baseline_keys)}
            return dict(next(observations))

        def api(path):
            if path == '/api/ai/install/status':
                return dict(install if clicked else baseline_install)
            if path == '/api/operation-history':
                return {'entries': [{'operationKey': key, 'operationId': operation_id}
                                    for key in keys]}
            self.fail('Unexpected API: ' + path)

        run.wd.click.side_effect = click
        run.wd.execute.side_effect = dom
        run.api.side_effect = api
        return run

    def activate(self, run):
        with patch.object(runner.time, 'monotonic', side_effect=itertools.count()), \
             patch.object(runner.time, 'sleep'):
            linux.activate_downloaded(run)

    def evidence(self, run):
        return [call.args[1] for call in run.evidence.call_args_list
                if call.args[0] == 'J3-activation-consent'][-1]

    def test_unchanged_unrelated_key_is_not_approval(self):
        run = self.run_fixture(baseline_keys=['old-key'], keys=['old-key'],
                               operation_id='core.rebuild-index')
        with self.assertRaises(runner.Infrastructure):
            self.activate(run)
        self.assertNotIn('path', self.evidence(run))

    def test_unchanged_activation_key_is_not_approval(self):
        run = self.run_fixture(baseline_keys=['old-key'], keys=['old-key'])
        with self.assertRaises(runner.Infrastructure):
            self.activate(run)

    def test_new_unrelated_key_is_not_approval(self):
        run = self.run_fixture(keys=['new-key'], operation_id='core.rebuild-index')
        with self.assertRaises(runner.Infrastructure):
            self.activate(run)

    def test_unchanged_downloading_is_not_approval(self):
        run = self.run_fixture(baseline_install={'state': 'running', 'phase': 'downloading'})
        with self.assertRaises(runner.Infrastructure):
            self.activate(run)

    def test_new_activation_key_is_approval(self):
        run = self.run_fixture(baseline_keys=['old-key'], keys=['old-key', 'new-key'])
        self.activate(run)
        evidence = self.evidence(run)
        self.assertEqual(evidence['path'], 'withdrawnAfterPageApproval')
        self.assertEqual(evidence['progress']['operationKeys'], ['new-key'])
        run.api.assert_any_call('/api/operation-history')

    def test_activation_required_to_activating_or_done_is_approval(self):
        for phase, state in [('activating', 'running'), ('done', 'completed')]:
            with self.subTest(phase=phase):
                run = self.run_fixture(install={'state': state, 'phase': phase})
                self.activate(run)
                evidence = self.evidence(run)
                self.assertEqual(evidence['path'], 'withdrawnAfterPageApproval')

    def test_no_prompt_or_key_timeout_is_infrastructure_with_observation(self):
        run = self.run_fixture(typed=False)
        with self.assertRaises(runner.Infrastructure) as failure:
            self.activate(run)
        self.assertIn('"operationKeys": []', str(failure.exception))
        self.assertNotIn('last observation: None', str(failure.exception))

    def test_transient_api_timeout_retains_observation_and_failure(self):
        run = self.run_fixture()
        api = run.api.side_effect

        def unavailable(path):
            if run.wd.click.called:
                raise runner.TransientAPI('Engine HTTP 503 during consent')
            return api(path)

        run.api.side_effect = unavailable
        with self.assertRaises(runner.Infrastructure) as failure:
            self.activate(run)
        self.assertIn('Engine HTTP 503 during consent', str(failure.exception))
        self.assertIn('"operationKeys": []', str(failure.exception))
        self.assertNotIn('last observation: None', str(failure.exception))


if __name__ == '__main__':
    unittest.main()
