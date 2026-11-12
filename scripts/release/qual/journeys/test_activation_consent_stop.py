"""A supervisor stop during the activation consent step is never swallowed."""
import types
import unittest
from unittest.mock import Mock

import linux
import runner


class ConsentStopTests(unittest.TestCase):
    def run_with(self, execute=None, keys=None):
        run = Mock(platform='linux', facts={}, args=types.SimpleNamespace(resume=False))
        run.wd.execute.side_effect = execute or (lambda script: {'typed': False, 'operationKeys': []})
        if keys:
            run.wd.keys.side_effect = keys
        return run

    def test_stop_while_observing_propagates(self):
        def execute(script):
            raise runner.RunStopped('GPU lane supervisor interrupted the session')
        with self.assertRaises(runner.RunStopped):
            linux.activate_downloaded(self.run_with(execute=execute))

    def test_stop_while_typing_propagates(self):
        def keys(selector, text):
            raise runner.RunStopped('GPU lane supervisor interrupted the session')
        run = self.run_with(execute=lambda script: {'typed': True, 'blocked': False, 'operationKeys': []}, keys=keys)
        with self.assertRaises(runner.RunStopped):
            linux.activate_downloaded(run)

    def disappearing(self):
        seq = iter([{'typed': True, 'blocked': False, 'operationKeys': []}])
        return lambda script: next(seq, {'typed': False, 'operationKeys': []})

    def test_stop_while_typing_then_prompt_gone_propagates(self):
        def keys(selector, text):
            raise runner.RunStopped('GPU lane supervisor interrupted the session')
        run = self.run_with(execute=self.disappearing(), keys=keys)
        with self.assertRaises(runner.RunStopped):
            linux.activate_downloaded(run)

    def test_stop_while_approving_then_prompt_gone_propagates(self):
        run = self.run_with(execute=self.disappearing())
        def click(selector):
            if 'Approve' in selector:
                raise runner.RunStopped('GPU lane supervisor interrupted the session')
        run.wd.click.side_effect = click
        with self.assertRaises(runner.RunStopped):
            linux.activate_downloaded(run)


if __name__ == '__main__':
    unittest.main()
