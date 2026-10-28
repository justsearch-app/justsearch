"""Harness checks; no app, display, network, shared VM or installed packages."""
import importlib.util
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch


class RunnerTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('journey_runner', Path(__file__).with_name('runner.py'))
        self.r = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.r)

    def test_assertion_is_product_failure(self):
        self.assertEqual(self.r.classify(AssertionError('wrong search result')), 'failed-product')

    def test_driver_failure_is_infrastructure(self):
        self.assertEqual(self.r.classify(self.r.Infrastructure('driver mismatch')), 'failed-infrastructure')

    def test_download_outage_is_infrastructure(self):
        self.assertEqual(self.r.classify(self.r.Infrastructure('download unavailable')), 'failed-infrastructure')

    def test_unknown_exception_is_not_blame_on_product(self):
        self.assertEqual(self.r.classify(ValueError('harness bug')), 'failed-infrastructure')

    def test_blocked_prerequisite_is_unavailable(self):
        self.assertEqual(self.r.classify(self.r.Unavailable('AI download did not complete')), 'unavailable')

    def test_unavailable_is_not_pass(self):
        report = self.r.summarize([{'id': 'J1', 'checks': [{'status': 'unavailable', 'reason': 'no driver'}]}])
        self.assertFalse(report['passed'])
        self.assertEqual(report['counts']['passed'], 0)
        self.assertEqual(report['counts']['unavailable'], 1)

    def test_decision_skip_is_not_pass(self):
        report = self.r.summarize([{'id': 'J5', 'checks': [{'status': 'skipped-by-decision', 'reason': 'VM only'}]}])
        self.assertFalse(report['passed'])
        self.assertEqual(report['counts']['passed'], 0)

    def test_mixed_failure_families_are_preserved(self):
        report = self.r.summarize([{'id': 'J2', 'checks': [{'status': 'passed'},
            {'status': 'failed-product'}, {'status': 'failed-infrastructure'}]}])
        self.assertEqual(report['counts']['failed-product'], 1)
        self.assertEqual(report['counts']['failed-infrastructure'], 1)
        self.assertFalse(report['passed'])

    def test_empty_report_cannot_pass(self):
        self.assertFalse(self.r.summarize([])['passed'])

    def test_all_executed_checks_must_pass(self):
        self.assertTrue(self.r.summarize([{'id': 'J1', 'checks': [{'status': 'passed'}]}])['passed'])

    def test_invalid_status_is_rejected(self):
        with self.assertRaises(ValueError):
            self.r.summarize([{'id': 'J1', 'checks': [{'status': 'skip'}]}])

    def test_tampered_release_asset_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'candidate.deb').write_bytes(b'altered')
            (root / 'SHA256SUMS').write_text('0' * 64 + '  candidate.deb\n')
            with self.assertRaises(self.r.Infrastructure):
                self.r.verify_assets(root, ['candidate.deb'])

    def test_checksums_cannot_escape_asset_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'SHA256SUMS').write_text('0' * 64 + '  ../candidate.deb\n')
            with self.assertRaises(self.r.Infrastructure):
                self.r.verify_assets(root, ['../candidate.deb'])

    def test_duplicate_checksum_names_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'SHA256SUMS').write_text(('0' * 64 + '  candidate.deb\n') * 2)
            with self.assertRaises(self.r.Infrastructure):
                self.r.verify_assets(root, ['candidate.deb'])

    def test_missing_checksum_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'candidate.deb').write_bytes(b'package')
            (root / 'SHA256SUMS').write_text('')
            with self.assertRaises(self.r.Infrastructure):
                self.r.verify_assets(root, ['candidate.deb'])

    def test_steps_keep_both_failure_families_and_write_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = types.SimpleNamespace(evidence=str(Path(tmp) / 'evidence'), vm=None, lane='hosted-linux-x11')
            run = self.r.Run(args, {'scenarios': {}})
            row = {'id': 'J2', 'checks': []}
            run.results.append(row)
            def fail(exc):
                raise exc
            with patch.object(run.native, 'shot', side_effect=OSError('no desktop')):
                run.check(row, 'search', lambda: fail(AssertionError('fixture absent')))
                run.check(row, 'viewer', lambda: fail(self.r.Infrastructure('focus failed')))
                run.check(row, 'models', lambda: fail(self.r.Unavailable('download not completed')))
            summary = json.loads((run.out / 'summary.json').read_text())
            self.assertEqual([c['status'] for c in summary['journeys'][0]['checks']],
                             ['failed-product', 'failed-infrastructure', 'unavailable'])
            self.assertFalse(summary['passed'])
            self.assertEqual(len(list(run.out.glob('*.error.txt'))), 3)

    def test_settled_scenario_quotes_are_preserved(self):
        expected = {
            'S3': "Journeys J1-J9 run against the draft's exact bytes on hosted runners, the two clean VMs and the GPU host, and produce one result summary that separates passed, failed, skipped by decision (Windows GPU) and unavailable",
            'S7': 'Clean Ubuntu 24.04 (Wayland and X11): .deb installs, launches, adds a folder via the system picker, indexes, searches, opens a result, Install AI gives search without chat on CPU; X11 shortcut registers, Wayland shows the --focus-search alternative; uninstall/reinstall keeps data',
            'S8': 'Linux in-app update via descriptor v2 and pkexec apt-get: success relaunches the new version; cancelled prompt, lock timeout or failure resumes the old Engine and re-offers the update',
            'S21': "The MCP bridge's manifest and registry entry carry the app's version, the release URL and checksum, declare Linux and Windows, and the bridge works on both (J8)",
            'S22': 'Journeys run on clean installs without a developer JDK, models or inherited runtime paths; a missing download, skipped or unavailable check is reported as such, never as a pass',
        }
        definition = json.loads(Path(__file__).with_name('journeys.json').read_text())
        self.assertEqual(definition['scenarios'], expected)

    def test_preparation_failure_retains_unavailable_checks(self):
        report = self.r.preparation_failure('hosted-linux-x11', 'Download outage')
        self.assertFalse(report['passed'])
        self.assertEqual(report['counts']['failed-infrastructure'], 1)
        self.assertGreater(report['counts']['unavailable'], 0)
        self.assertGreater(report['counts']['skipped-by-decision'], 0)
        self.assertEqual(report['counts']['passed'], 0)

    def test_mcp_missing_fixture_is_unavailable_before_client_launch(self):
        import linux
        def require(key):
            if key == 'folder':
                raise self.r.Unavailable('picker did not complete')
            return True
        with self.assertRaises(self.r.Unavailable):
            linux.mcp(types.SimpleNamespace(require=require))

    def test_ai_missing_fixture_is_unavailable_before_downloading(self):
        import linux
        def require(key):
            if key == 'folder':
                raise self.r.Unavailable('picker did not complete')
            return True
        with self.assertRaises(self.r.Unavailable):
            linux.install_ai(types.SimpleNamespace(require=require))

    def test_predecessor_cannot_certify_candidate_desktop_integration(self):
        import linux
        run = types.SimpleNamespace(require=lambda key: '0.3.1')
        with patch.object(linux, 'installed_version', return_value='0.3.0'):
            with self.assertRaises(linux.Unavailable):
                linux.require_candidate(run)


if __name__ == '__main__':
    unittest.main()
