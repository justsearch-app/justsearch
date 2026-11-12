"""Consent observations never stand in for the caller's activation proof."""
import itertools
import unittest
from unittest.mock import Mock, patch

import linux
import runner


class ActivationConsentTests(unittest.TestCase):
    def run_with_observations(self, observations):
        run = Mock()
        remaining = iter(observations)
        run.wd.execute.side_effect = lambda script: (dict(next(remaining, observations[-1]))
            if run.wd.click.called else {'typed': False, 'operationKeys': []})
        run.api.return_value = {'state': 'completed', 'phase': 'activation_required'}
        return run

    def activate(self, run):
        # Exercise real deadlines without an app, shared resources or wall time.
        with patch.object(runner.time, 'monotonic', side_effect=itertools.count()), \
             patch.object(runner.time, 'sleep'):
            linux.activate_downloaded(run)

    def consent(self, run):
        return [call.args[1] for call in run.evidence.call_args_list
                if call.args[0] == 'J3-activation-consent'][-1]

    def test_stable_input_is_typed_and_approved(self):
        run = self.run_with_observations([{'typed': True, 'operationKeys': []}])
        self.activate(run)
        evidence = self.consent(run)
        self.assertEqual(evidence['path'], 'typedApproved')
        self.assertEqual(evidence['observations'], [{'typed': True, 'operationKeys': []}])
        run.wd.keys.assert_called_once_with('input[data-testid="authorization-typed-input"]',
                                           'core.activate-installed-models')
        run.wd.click.assert_any_call('button[aria-label="Approve"]')
        run.api.assert_not_called()

    def test_seen_then_withdrawn_does_not_require_progress(self):
        observations = [{'typed': True, 'blocked': True, 'operationKeys': []},
                        {'typed': False, 'operationKeys': []}]
        run = self.run_with_observations(observations)
        self.activate(run)
        evidence = self.consent(run)
        self.assertEqual(evidence['path'], 'withdrawn')
        self.assertEqual(evidence['observations'], observations)
        run.wd.keys.assert_not_called()
        run.api.assert_not_called()

    def test_withdrawal_before_first_poll_does_not_fail(self):
        for keys, path in [(['activation-operation'], 'immediate'), ([], 'noneObserved')]:
            with self.subTest(path=path):
                run = self.run_with_observations([{'typed': False, 'operationKeys': keys}])
                self.activate(run)
                evidence = self.consent(run)
                self.assertEqual(evidence['path'], path)
                self.assertTrue(evidence['observations'])
                run.wd.keys.assert_not_called()
                run.api.assert_not_called()

    def test_operation_key_does_not_hide_a_later_prompt(self):
        run = self.run_with_observations([
            {'typed': False, 'operationKeys': ['operation']},
            {'typed': True, 'operationKeys': ['operation']}])
        self.activate(run)
        self.assertEqual(self.consent(run)['path'], 'typedApproved')
        run.wd.keys.assert_called_once()

    def test_withdrawal_during_typing_does_not_fail(self):
        run = self.run_with_observations([
            {'typed': True, 'operationKeys': []},
            {'typed': False, 'operationKeys': []}])
        run.wd.keys.side_effect = runner.Infrastructure('WebDriver stale element reference')
        self.activate(run)
        evidence = self.consent(run)
        self.assertEqual(evidence['path'], 'withdrawn')
        self.assertIn({'interactionError': 'WebDriver stale element reference'}, evidence['observations'])
        run.wd.click.assert_called_once_with('button[aria-label="Activate downloaded models"]')

    def test_withdrawal_during_typing_after_poll_deadline_does_not_fail(self):
        run = self.run_with_observations([
            {'typed': True, 'operationKeys': []},
            {'typed': False, 'operationKeys': []}])
        elapsed = [0]
        ticks = itertools.count()

        def withdrawn(*args):
            elapsed[0] = 31
            raise runner.Infrastructure('WebDriver prompt disappeared during input wait')

        run.wd.keys.side_effect = withdrawn
        with patch.object(runner.time, 'monotonic', side_effect=lambda: elapsed[0] + next(ticks)), \
             patch.object(runner.time, 'sleep'):
            linux.activate_downloaded(run)
        self.assertEqual(self.consent(run)['path'], 'withdrawn')

    def test_disabled_input_is_not_typed(self):
        run = self.run_with_observations([{'typed': True, 'blocked': True, 'operationKeys': []}])
        self.activate(run)
        self.assertEqual(self.consent(run)['path'], 'noneObserved')
        run.wd.keys.assert_not_called()

    def test_remaining_interaction_timeout_retains_observation_and_cause(self):
        run = self.run_with_observations([{'typed': True, 'operationKeys': []}])
        run.wd.keys.side_effect = runner.Infrastructure('WebDriver input unavailable')
        with self.assertRaises(runner.Infrastructure) as failure:
            self.activate(run)
        self.assertIn('"typed": true', str(failure.exception))
        self.assertIn('last failure cause: WebDriver input unavailable', str(failure.exception))

    def test_dom_timeout_retains_last_observation_and_cause(self):
        run = self.run_with_observations([{'typed': False, 'operationKeys': []}])
        run.wd.execute.side_effect = [{'typed': False, 'operationKeys': []}] + [
            runner.Infrastructure('WebDriver disconnected')] * 30
        with self.assertRaises(runner.Infrastructure) as failure:
            self.activate(run)
        self.assertIn('"operationKeys": []', str(failure.exception))
        self.assertIn('last failure cause: WebDriver disconnected', str(failure.exception))


if __name__ == '__main__':
    unittest.main()
