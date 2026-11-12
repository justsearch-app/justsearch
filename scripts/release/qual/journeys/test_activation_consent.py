"""Activation consent can be withdrawn when the page approves the same request."""
import itertools
import unittest
from unittest.mock import Mock, patch

import linux
import runner


class ActivationConsentTests(unittest.TestCase):
    def run_with_observations(self, observations, install=None):
        run = Mock()
        remaining = iter(observations)
        run.wd.execute.side_effect = lambda script: (dict(next(remaining, observations[-1]))
            if run.wd.click.called else {'typed': False, 'operationKeys': []})
        run.api.side_effect = lambda path: ({'entries': [
            {'operationKey': 'activation-operation', 'operationId': 'core.activate-installed-models'}]}
            if path == '/api/operation-history' else
            install or {'state': 'completed', 'phase': 'activation_required'})
        return run

    def activate(self, run):
        # Exercise the real polling/deadline logic without spending a minute on
        # each negative case. No app, shared stack or external resources used.
        with patch.object(runner.time, 'monotonic', side_effect=itertools.count()), \
             patch.object(runner.time, 'sleep'):
            linux.activate_downloaded(run)

    def consent(self, run):
        return [call.args[1] for call in run.evidence.call_args_list
                if call.args[0] == 'J3-activation-consent'][-1]

    def test_seen_then_withdrawn_with_operation_is_page_approval(self):
        run = self.run_with_observations([
            {'typed': True, 'operationKeys': []},
            {'typed': False, 'operationKeys': ['activation-operation']},
        ])
        self.activate(run)
        evidence = self.consent(run)
        self.assertEqual(evidence['path'], 'withdrawnAfterPageApproval')
        self.assertEqual(evidence['progress']['operationKeys'], ['activation-operation'])
        run.wd.keys.assert_not_called()

    def test_withdrawn_waits_for_install_progress(self):
        run = self.run_with_observations([
            {'typed': True, 'operationKeys': []},
            {'typed': False, 'operationKeys': []},
        ])
        pending = {'state': 'completed', 'phase': 'activation_required'}
        progressed = {'state': 'completed', 'phase': 'done'}
        statuses = iter([pending, progressed])
        run.api.side_effect = lambda path: next(statuses, progressed)
        self.activate(run)
        evidence = self.consent(run)
        self.assertEqual(evidence['path'], 'withdrawnAfterPageApproval')
        self.assertEqual(evidence['progress']['install'], progressed)
        run.api.assert_called_with('/api/ai/install/status')
        run.wd.keys.assert_not_called()

    def test_withdrawn_without_progress_fails_with_last_observation(self):
        run = self.run_with_observations([
            {'typed': True, 'operationKeys': []},
            {'typed': False, 'operationKeys': []},
        ])
        with self.assertRaises(runner.Infrastructure) as failure:
            self.activate(run)
        self.assertIn('activation_required', str(failure.exception))
        self.assertNotIn('last observation: None', str(failure.exception))
        evidence = self.consent(run)
        self.assertEqual(evidence['install']['phase'], 'activation_required')
        self.assertNotIn('path', evidence)
        run.wd.keys.assert_not_called()

    def test_stable_input_keeps_typed_approval(self):
        run = self.run_with_observations([{'typed': True, 'operationKeys': []}])
        self.activate(run)
        self.assertEqual(self.consent(run)['path'], 'typedApproved')
        run.wd.keys.assert_called_once_with('input[data-testid="authorization-typed-input"]',
                                           'core.activate-installed-models')
        run.wd.click.assert_any_call('button[aria-label="Approve"]')

    def test_immediate_operation_keeps_immediate_path(self):
        run = self.run_with_observations([{'typed': False, 'operationKeys': ['activation-operation']}])
        self.activate(run)
        self.assertEqual(self.consent(run)['path'], 'immediate')
        run.wd.keys.assert_not_called()

    def test_disappearance_during_typing_rechecks_progress(self):
        run = self.run_with_observations([
            {'typed': True, 'operationKeys': []},
            {'typed': True, 'operationKeys': []},
            {'typed': False, 'operationKeys': ['activation-operation']},
        ])
        run.wd.keys.side_effect = runner.Infrastructure('WebDriver stale element reference')
        self.activate(run)
        self.assertEqual(self.consent(run)['path'], 'withdrawnAfterPageApproval')
        self.assertIn('stale element', self.consent(run)['interactionError'])
        run.wd.click.assert_called_once_with('button[aria-label="Activate downloaded models"]')

    def test_visible_but_disabled_input_is_not_withdrawn(self):
        run = self.run_with_observations([
            {'typed': True, 'blocked': True, 'operationKeys': ['activation-operation']},
        ], install={'state': 'completed', 'phase': 'done'})
        with self.assertRaises(runner.Infrastructure):
            self.activate(run)
        run.wd.keys.assert_not_called()


if __name__ == '__main__':
    unittest.main()
