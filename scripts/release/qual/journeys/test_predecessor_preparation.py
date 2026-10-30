"""Predecessor preparation is visible and cannot stand in for candidate checks."""
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


windows_vm = load('preparation_windows_vm', 'vm-windows.py')
linux_vm = load('preparation_linux_vm', 'vm-run.py')


class PreparationSummaryTests(unittest.TestCase):
    def test_both_lanes_retain_predecessor_failures_and_evidence_for_owner(self):
        for wrapper, session in ((windows_vm, 'vm'), (linux_vm, 'wayland')):
            with self.subTest(session=session), tempfile.TemporaryDirectory() as temp:
                out = Path(temp)
                args = SimpleNamespace(session=session, mode='fresh', journeys=None)
                checks = [{'name': name, 'status': status, 'reason': 'observed ' + status,
                           'evidence': [name + '.json', name + '.png']}
                          for name, status in (('cpu-ai', 'failed-product'), ('first-run', 'passed'),
                                               ('restart', 'unavailable'), ('folder', 'failed-infrastructure'))]
                sources = {'candidate': {'journeys': [{'id': 'J1', 'checks': [dict(checks[1])]}]},
                           'predecessor-setup': {'predecessorVersion': '0.3.0',
                               'journeys': [{'id': 'J3', 'checks': checks}]}}
                with patch.object(wrapper, 'full_selection', return_value=True):
                    wrapper.combine(args, out, sources)
                result = json.loads((out / 'summary.json').read_text())
                preparation = [r for r in result['journeys'] if r.get('scope') == 'predecessor-preparation']
                self.assertEqual(len(preparation), 1, 'predecessor rows disappeared')
                row = preparation[0]
                self.assertNotEqual(row['id'], 'J3', 'preparation must not satisfy candidate selection')
                self.assertEqual(row['predecessorVersion'], '0.3.0')
                self.assertEqual([c['status'] for c in row['checks']],
                                 ['owner-validation', 'passed', 'unavailable', 'failed-infrastructure'])
                failed = row['checks'][0]
                self.assertEqual(failed['originalStatus'], 'failed-product')
                self.assertIn('0.3.0', failed['reason']); self.assertIn('cpu-ai', failed['reason'])
                self.assertTrue(all('predecessor-setup/' in p for p in failed['evidence']))
                pending = next(r for r in result['ownerValidation'] if r['check'] == 'cpu-ai')
                self.assertEqual(pending['evidence'], failed['evidence'])
                self.assertEqual(result['status'], 'failed')  # unavailable/infrastructure preserved

    def test_predecessor_product_failure_requires_owner_even_with_all_candidate_checks(self):
        for wrapper, session in ((windows_vm, 'vm'), (linux_vm, 'wayland')):
            with self.subTest(session=session), tempfile.TemporaryDirectory() as temp:
                args = SimpleNamespace(session=session, mode='fresh', journeys=None)
                def row(status):
                    return {'id': 'J3', 'checks': [{'name': 'cpu-ai', 'status': status,
                            'reason': 'model activation failed', 'evidence': ['activation.json']}]}
                with patch.object(wrapper, 'full_selection', return_value=True):
                    code = wrapper.combine(args, Path(temp), {
                        'candidate': {'journeys': [row('passed')]},
                        'predecessor-setup': {'predecessorVersion': '0.3.0', 'journeys': [row('failed-product')]}})
                report = json.loads((Path(temp) / 'summary.json').read_text())
                self.assertEqual((code, report['status']), (2, 'passed-pending-owner-validation'))
                self.assertEqual(report['counts']['passed'], 1)
