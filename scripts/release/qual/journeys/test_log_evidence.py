"""Failure-time app logs: temporary files and real adapter Python subprocesses only."""
import gzip
import json
import os
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

    def fail_check(self, exc=None, name='cpu-ai'):
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
        check = self.fail_check()
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
                check = self.fail_check(exc, status)
                self.assertEqual(check['status'], status)
                self.assertTrue((self.run.out / f'J3-{status}-logs/engine.log').is_file())

    def test_snapshots_and_finish_cap_and_redact_each_file(self):
        suffix = b'\n{"sessionToken" : "secret-credential"}\n'
        content = b'old prefix' + b'x' * 4_000_000 + suffix
        for name in ('engine.log', 'engine.log.1', 'workers/mcp.log'):
            (self.logs / name).write_bytes(content)
        self.fail_check()
        self.finish()
        expected = (content.decode().replace('secret-credential', '<redacted>')
                    .encode()[-4_000_000:].decode())
        for folder in ('J3-cpu-ai-logs', 'app-logs'):
            for name in ('engine.log', 'engine.log.1', 'workers/mcp.log'):
                with self.subTest(folder=folder, name=name):
                    saved = self.run.out / folder / name
                    self.assertTrue(saved.is_file(), 'capped/redacted app log missing')
                    self.assertTrue(saved.read_text() == expected, 'saved text differs from the redacted tail')
                    self.assertLessEqual(saved.stat().st_size, 4_000_000)
        self.assertNotIn('secret-credential', (self.run.out / 'engine.log').read_text())

    def test_collection_transport_error_preserves_status_and_reason(self):
        with patch.object(self.run.target, 'py', side_effect=runner.Infrastructure('collector offline')):
            check = self.fail_check()
        self.assertEqual(check['status'], 'failed-product')
        self.assertEqual(check['reason'], 'activation timed out')
        folder = self.run.out / 'J3-cpu-ai-logs'
        self.assertTrue(folder.is_dir(), 'collection error folder missing')
        self.assertIn('collector offline', (folder / 'collection-error.txt').read_text())
        summary = json.loads((self.run.out / 'summary.json').read_text())
        self.assertEqual(summary['journeys'][0]['checks'][0]['status'], 'failed-product')

    def test_gzip_archive_is_readable_capped_and_redacted(self):
        archive = self.logs / 'engine.2026-10-31.0.log.gz'
        content = b'old prefix' + b'x' * 4_000_000 + b'\n{"sessionToken":"archive-secret"}\n'
        with gzip.open(archive, 'wb') as stream:
            stream.write(content)
        self.fail_check()
        self.finish()
        for folder in ('J3-cpu-ai-logs', 'app-logs'):
            saved = self.run.out / folder / (archive.name + '.txt')
            self.assertTrue(saved.is_file(), 'gzip archive was not saved as readable text')
            text = saved.read_text(encoding='utf-8')
            self.assertTrue(text.endswith('\n{"sessionToken":"<redacted>"}\n'))
            self.assertNotIn('archive-secret', text)
            self.assertLessEqual(saved.stat().st_size, 4_000_000)
            self.assertFalse((saved.parent / archive.name).exists())

    def test_saved_byte_cap_after_invalid_utf8_and_redaction(self):
        (self.logs / 'invalid.log').write_bytes(
            b'\xff' * 4_000_000 + b'\n{"sessionToken":"x"}\n')
        self.fail_check()
        saved = self.run.out / 'J3-cpu-ai-logs/invalid.log'
        self.assertLessEqual(saved.stat().st_size, 4_000_000,
                             'decoded/redacted bytes exceeded the saved-file cap')
        text = saved.read_text(encoding='utf-8', errors='strict')
        self.assertTrue(text.endswith('\n{"sessionToken":"<redacted>"}\n'))

    def test_tail_never_exposes_a_token_that_started_before_the_cap(self):
        (self.logs / 'long-token.log').write_bytes(
            b'{"sessionToken":"' + b'secret-' * 700_000 + b'"}\nend\n')
        self.fail_check()
        text = (self.run.out / 'J3-cpu-ai-logs/long-token.log').read_text()
        self.assertFalse('secret-' in text, 'tail exposed a truncated session credential')
        self.assertTrue(text.endswith('"}\nend\n'))

    def test_saved_byte_cap_never_splits_the_redaction_marker(self):
        (self.logs / 'marker.log').write_bytes(
            b'{"sessionToken":"x"}' + b'y' * 3_999_990)
        self.fail_check()
        saved = self.run.out / 'J3-cpu-ai-logs/marker.log'
        text = saved.read_text(encoding='utf-8', errors='strict')
        self.assertTrue(text.startswith('"}'), 'saved tail starts inside a redacted credential')
        self.assertLessEqual(saved.stat().st_size, 4_000_000)

    def test_file_limit_selects_newest_and_reports_omissions(self):
        for index in range(70):
            path = self.logs / f'number-{index:03}.log'
            path.write_text(str(index))
            os.utime(path, ns=(2_000_000_000_000_000_000 + index,) * 2)
        check = self.fail_check()
        folder = self.run.out / 'J3-cpu-ai-logs'
        saved = list(folder.glob('number-*.log'))
        self.assertEqual(len(saved), 64, 'collection did not enforce its file limit')
        self.assertEqual({p.name for p in saved},
                         {f'number-{i:03}.log' for i in range(6, 70)})
        self.assertIn('64', (folder / 'collection-error.txt').read_text())
        self.assertEqual(check['status'], 'failed-product')

    def test_collection_byte_budget_and_separate_transfers(self):
        for index in range(10):
            path = self.logs / f'large-{index:02}.log'
            path.write_bytes(bytes([65 + index]) * 4_000_000)
            os.utime(path, ns=(2_000_000_000_000_000_000 + index,) * 2)
        rejected = self.logs / 'large-08.log'
        with rejected.open('r+b') as stream:
            stream.write(b'reject-this-transfer')
        os.utime(rejected, ns=(2_000_000_000_000_000_008,) * 2)
        original = self.run.target.py
        sizes = []
        def bounded_transfer(*args, **kwargs):
            response = original(*args, **kwargs)
            sizes.append(len(response.encode('utf-8')))
            if sizes[-1] > 25_000_000:
                raise runner.Infrastructure('oversized transfer rejected')
            if 'reject-this-transfer' in response:
                raise runner.Infrastructure('individual transfer rejected')
            return response
        with patch.object(self.run.target, 'py', side_effect=bounded_transfer):
            self.fail_check()
        folder = self.run.out / 'J3-cpu-ai-logs'
        self.assertTrue((folder / 'large-09.log').is_file(),
                        'oversized collection lost the newest log')
        saved = sorted(folder.glob('large-*.log'))
        self.assertGreater(len(saved), 1)
        self.assertFalse((folder / rejected.name).exists())
        self.assertTrue((folder / 'large-07.log').is_file(), 'one failed transfer stopped collection')
        self.assertLessEqual(sum(p.stat().st_size for p in folder.rglob('*') if p.is_file()),
                             32_000_000)
        self.assertIn('32', (folder / 'collection-error.txt').read_text())
        self.assertIn('individual transfer rejected', (folder / 'collection-error.txt').read_text())
        self.assertGreater(len(sizes), 1, 'logs were transferred as one aggregate payload')

    def test_repeated_snapshots_share_one_run_budget_within_extractor_limits(self):
        for index in range(10):
            path = self.logs / f'large-{index:02}.log'
            path.write_bytes(bytes([65 + index]) * 4_000_000)
        for index in range(6):
            self.fail_check(name=f'failing-{index}')
        self.finish()
        saved = [p for p in self.run.out.rglob('*') if p.is_file()]
        # vm-run.extract_evidence refuses archives above 100 MB or 1000 members.
        self.assertLess(sum(p.stat().st_size for p in saved), 60_000_000)
        self.assertLess(len(saved), 400)
        self.assertIn('Run log budget', (self.run.out / 'J3-failing-5-logs/collection-error.txt').read_text())

    def test_traversal_is_bounded_and_reports_omissions(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'private.log').write_text('must not be collected')
        (self.logs / 'linked-directory').symlink_to(outside, target_is_directory=True)
        (self.logs / 'linked-file.log').symlink_to(outside / 'private.log')
        # Empty directories consume traversal work even though they collect no bytes.
        for index in range(4100):
            (self.logs / f'empty-{index:04}').mkdir()
        self.fail_check()
        error = self.run.out / 'J3-cpu-ai-logs/collection-error.txt'
        self.assertTrue(error.is_file(), 'bounded traversal did not report omitted entries')
        self.assertIn('4096', error.read_text())
        folder = error.parent
        self.assertFalse((folder / 'linked-directory').exists())
        self.assertFalse((folder / 'linked-file.log').exists())

    def test_collection_scan_error_is_recorded_without_changing_status(self):
        self.run.data = str(self.root / 'broken-data')
        Path(self.run.data).mkdir()
        (Path(self.run.data) / 'logs').write_text('not a directory')
        check = self.fail_check(runner.Unavailable('not installed'))
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
