import importlib.util
import unittest
from pathlib import Path

from jseval.preflight import assert_capabilities

spec = importlib.util.spec_from_file_location('capability_ready', Path(__file__).with_name('capability-ready.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ReadinessTest(unittest.TestCase):
    def run_gate(self, statuses, timeout=5):
        now = [0]
        calls = []

        def check(url, intended, **kwargs):
            calls.append(intended)
            status = statuses[min(len(calls) - 1, len(statuses) - 1)]
            return assert_capabilities(status, intended)

        result = gate.wait_ready('unused', timeout, check=check, clock=lambda: now[0],
                                 pause=lambda seconds: now.__setitem__(0, now[0] + seconds))
        return result, calls, now[0]

    def test_health_alone_and_absent_model_do_not_pass(self):
        result, calls, elapsed = self.run_gate([{'healthy': True}])
        self.assertFalse(result['ok'])
        self.assertEqual(elapsed, 5)
        self.assertTrue(all(call == {'reranker'} for call in calls))

    def test_delayed_model_passes_exact_preflight(self):
        result, calls, elapsed = self.run_gate([{'healthy': True}, {'rerankerModelPath': 'shared/model.onnx'}])
        self.assertTrue(result['ok'])
        self.assertEqual(len(calls), 2)
        self.assertEqual(elapsed, 2)
        self.assertTrue(result['attempts'][0]['refusals'])
        self.assertTrue(result['attempts'][1]['realized']['reranker']['realized'])

    def test_nested_split_worker_and_flat_engine_use_same_predicate(self):
        for status in [{'rerankerModelPath': 'shared/model.onnx'},
                       {'worker': {'gpu': {'rerankerModelPath': 'shared/model.onnx'}}}]:
            result, calls, _ = self.run_gate([status])
            self.assertTrue(result['ok'])
            self.assertEqual(len(calls), 1)


if __name__ == '__main__':
    unittest.main()
