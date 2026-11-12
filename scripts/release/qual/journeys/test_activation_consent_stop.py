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


if __name__ == '__main__':
    unittest.main()
