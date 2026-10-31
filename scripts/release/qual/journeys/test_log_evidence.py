"""Failure-time app logs: temporary files and real adapter Python subprocesses only."""
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import runner
import windows


class LogEvidenceContract:
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        args = SimpleNamespace(evidence=str(self.root / 'evidence'), vm=None,
                               lane=self.lane)
        definition = json.loads(Path(runner.__file__).with_name('journeys.json').read_text())
        self.run = self.run_type(args, definition)
        self.run.data = str(self.root / 'data')
        self.run.work = str(self.root / 'work')
        Path(self.run.work).mkdir()
        self.logs = Path(self.run.data) / 'logs'
        self.logs.mkdir(parents=True)
        (self.logs / 'engine.log').write_text('before restart\n', encoding='utf-8')
        (self.logs / 'engine.log.1').write_text('rotated\n', encoding='utf-8')
        (self.logs / 'workers').mkdir()
        (self.logs / 'workers/embed.log').write_bytes('模型 worker '.encode() + b'\xff')
        self.run.native.shot = Mock()
        self.row = {'id': 'J3', 'checks': []}
        self.run.results.append(self.row)

    def fail(self, exc=None, name='cpu-ai'):
        def failure():
            raise exc if exc is not None else runner.ProductFailure('activation timed out')
        self.run.check(self.row, name, failure)
        return self.row['checks'][-1]

    def finish(self):
        # No app or crash collector is started by these log-only fixtures.
        with patch.object(self.run, 'stop_app'), \
                patch.object(self.run.target, 'owned', return_value=[]), \
                patch.object(self.run.target, 'stop'), \
                patch.object(self.run, 'jvm_crashes', return_value=None), \
                patch.object(self.run, 'collect_crashes', return_value=None, create=True), \
                patch.object(self.run.target, 'end_observation', create=True):
            self.run.finish()

    def test_failure_keeps_all_logs_before_later_restart(self):
        check = self.fail()
        snapshot = self.run.out / 'J3-cpu-ai-logs'
        self.assertTrue(snapshot.is_dir(), 'failure-time logs were not collected')
        self.assertIn(snapshot.name, check['evidence'])
        # A later journey restarts the app and replaces the current Engine log.
        (self.logs / 'engine.log').write_text('after restart\n', encoding='utf-8')
        self.run.check(self.row, 'later-journey', lambda: None)
        self.finish()
        self.assertEqual((snapshot / 'engine.log').read_text(), 'before restart\n')
        self.assertEqual((snapshot / 'engine.log.1').read_text(), 'rotated\n')
        self.assertEqual((snapshot / 'workers/embed.log').read_text(encoding='utf-8'),
                         '模型 worker \ufffd')
        self.assertEqual((self.run.out / 'app-logs/engine.log').read_text(), 'after restart\n')
        self.assertEqual((self.run.out / 'app-logs/engine.log.1').read_text(), 'rotated\n')
        self.assertEqual((self.run.out / 'app-logs/workers/embed.log').read_text(encoding='utf-8'),
                         '模型 worker \ufffd')
        self.assertEqual((self.run.out / 'engine.log').read_text().strip(), 'after restart')

    def test_every_raised_nonpass_status_keeps_logs(self):
        for exc, status in ((runner.ProductFailure('product'), 'failed-product'),
                            (runner.Infrastructure('transport'), 'failed-infrastructure'),
                            (runner.Unavailable('prerequisite'), 'unavailable'),
                            (runner.OwnerValidation('visual'), 'owner-validation'),
                            (runner.SkippedByDecision('conditional'), 'skipped-by-decision')):
            with self.subTest(status=status):
                check = self.fail(exc, status)
                self.assertEqual(check['status'], status)
                self.assertTrue((self.run.out / f'J3-{status}-logs/engine.log').is_file())

    def test_snapshots_and_finish_cap_and_redact_each_file(self):
        suffix = b'\n{"sessionToken" : "secret-credential"}\n'
        content = b'old prefix' + b'x' * 4_000_000 + suffix
        for name in ('engine.log', 'engine.log.1', 'workers/mcp.log'):
            (self.logs / name).write_bytes(content)
        self.fail()
        self.finish()
        expected = (content[-4_000_000:].decode()
                    .replace('secret-credential', '<redacted>'))
        for folder in ('J3-cpu-ai-logs', 'app-logs'):
            for name in ('engine.log', 'engine.log.1', 'workers/mcp.log'):
                with self.subTest(folder=folder, name=name):
                    saved = self.run.out / folder / name
                    self.assertTrue(saved.is_file(), 'capped/redacted app log missing')
                    self.assertEqual(saved.read_text(), expected)
                    self.assertLessEqual(saved.stat().st_size, 4_000_000)
        self.assertNotIn('secret-credential', (self.run.out / 'engine.log').read_text())

    def test_collection_transport_error_preserves_status_and_reason(self):
        with patch.object(self.run.target, 'py', side_effect=runner.Infrastructure('collector offline')):
            check = self.fail()
        self.assertEqual(check['status'], 'failed-product')
        self.assertEqual(check['reason'], 'activation timed out')
        folder = self.run.out / 'J3-cpu-ai-logs'
        self.assertTrue(folder.is_dir(), 'collection error folder missing')
        self.assertIn('collector offline', (folder / 'collection-error.txt').read_text())
        summary = json.loads((self.run.out / 'summary.json').read_text())
        self.assertEqual(summary['journeys'][0]['checks'][0]['status'], 'failed-product')

    def test_collection_scan_error_is_recorded_without_changing_status(self):
        self.run.data = str(self.root / 'broken-data')
        Path(self.run.data).mkdir()
        (Path(self.run.data) / 'logs').write_text('not a directory')
        check = self.fail(runner.Unavailable('not installed'))
        self.assertEqual(check['status'], 'unavailable')
        self.assertEqual(check['reason'], 'not installed')
        error = self.run.out / 'J3-cpu-ai-logs/collection-error.txt'
        self.assertTrue(error.is_file(), 'scan error was not recorded')
        self.assertIn('logs', error.read_text())

    def test_finish_keeps_all_logs_without_a_failed_check(self):
        self.finish()
        for name in ('engine.log', 'engine.log.1', 'workers/embed.log'):
            self.assertTrue((self.run.out / 'app-logs' / name).is_file(), name)
        self.assertTrue((self.run.out / 'engine.log').is_file())


class LinuxLogEvidenceTests(LogEvidenceContract, unittest.TestCase):
    run_type = runner.Run
    lane = 'hosted-linux-x11'


class WindowsLogEvidenceTests(LogEvidenceContract, unittest.TestCase):
    run_type = windows.Run
    lane = 'vm-windows'
