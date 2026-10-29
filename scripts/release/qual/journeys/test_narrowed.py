"""The six final repairs, using isolated files and mocked guest/desktop boundaries."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace as NS
import unittest
from unittest.mock import MagicMock, Mock, patch

import linux
import runner
from test_repair2 import immediate
from test_writes import wrapper


class NarrowedTests(unittest.TestCase):
    def hosted(self, out, entries):
        report = {'journeys': [{'id': 'J1', 'checks': [
            {'name': 'install', 'status': 'passed', 'evidence': []}]}], 'fullSelection': True}
        (out / 'summary.json').write_text(json.dumps(report))
        with patch.object(runner.subprocess, 'run', side_effect=[
                NS(returncode=0, stdout=json.dumps(entries), stderr=''),
                NS(returncode=0, stdout='/opt/JustSearch\n')]):
            code = runner.collect_hosted_crashes(out, '2026-10-29T00:00:00+00:00')
        return code, json.loads((out / 'summary.json').read_text())

    def test_n1_hosted_collection_never_certifies_the_lane(self):
        for entries, code in (([], 2), ([{'exe': '/opt/JustSearch', 'sig': 11}], 1)):
            with self.subTest(entries=entries), tempfile.TemporaryDirectory() as temp:
                observed, report = self.hosted(Path(temp), entries)
                self.assertEqual(observed, code)
                self.assertFalse(report['passed'])
                pending = [c for j in report['journeys'] for c in j['checks']
                           if c['status'] == 'owner-validation']
                row = next((c for c in pending if c['name'] == 'hosted crash collection unproven'), None)
                self.assertIsNotNone(row, 'Hosted collection omitted its unproven-collection row')
                self.assertIn('coredumps.json', row['evidence'])
                self.assertIn('hosted-crashes.json', row['evidence'])

    def test_n2_crash_owner_validation_uses_records_without_a_desktop(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            crash = {'attribution': 'unattributed', 'fields': {'ExecutablePath': '/usr/bin/helper'}}
            (out / 'guest-crashes.json').write_text(json.dumps([crash]))
            report = {'journeys': [], 'fullSelection': True}
            with patch.object(wrapper, 'Target'), patch.object(wrapper, 'Native') as native:
                native.return_value.shot.side_effect = runner.Infrastructure('No desktop remains')
                try:
                    wrapper.record_unattributed_crashes(report, out)
                except runner.Infrastructure as exc:
                    self.fail('Crash owner validation still requires a screenshot: ' + str(exc))
            native.assert_not_called()
            row = report['journeys'][0]['checks'][0]
            self.assertIn('../guest-crashes.json', row['evidence'])
            self.assertFalse(any(p.endswith('.png') for p in row['evidence']))
            self.assertIn('../guest-crashes.json', report['ownerValidation'][0]['evidence'])
            _, hosted = self.hosted(out, [{'exe': '/usr/bin/helper', 'sig': 6}])
            pending = next(p for p in hosted['ownerValidation'] if p['check'] == 'unattributed')
            self.assertIn('hosted-crashes.json', pending['evidence'])
            visible = runner.summarize([{'id': 'J1', 'checks': [{
                'name': 'first-run', 'status': 'owner-validation', 'evidence': ['first-run.png']}]}])
            self.assertEqual(visible['ownerValidation'][0]['screenshots'], ['first-run.png'])

    def test_n3_setup_sweep_only_exempts_an_absent_package(self):
        for status, absent in (('installed', False), ('unpacked', False), ('', True), ('config-files', True)):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as temp:
                out = Path(temp)
                target = Mock()
                def command(args, **kw):
                    if args[0] == 'dpkg-query':
                        return NS(returncode=1 if not status else 0, stdout=status, stderr='')
                    return NS(returncode=0, stdout='false', stderr='')
                target.run.side_effect = command
                with patch.object(wrapper, 'Target', return_value=target), \
                     patch.object(wrapper, 'crash_evidence') as crashes, \
                     patch.object(wrapper.vm, 'QMP', MagicMock()), patch('screen.key'):
                    wrapper.prepare_desktop(NS(session='x11'), out)
                crashes.assert_called_once_with(out / 'before-install' if absent else out,
                                                before_install=absent)
                self.assertTrue(any(c.args[0][0] == 'dpkg-query' for c in target.run.call_args_list))

    def test_n4_update_offer_requires_the_exact_version_token(self):
        for offered in ('0.3.10', '0.3.1-rc.1', '0.3.1+other', '10.3.1', '0.3.1'):
            with self.subTest(offered=offered):
                run = Mock()
                run.require.return_value = '0.3.1'
                with patch.object(linux, 'wait_for', immediate), \
                     patch.object(linux, 'update_banner', return_value=f'JustSearch {offered} is available.'):
                    if offered == '0.3.1':
                        linux.open_update_offer(run, 'offer')
                        run.wd.click.assert_called()
                    else:
                        with self.assertRaisesRegex(AssertionError, 'candidate version'):
                            linux.open_update_offer(run, 'offer')
                        run.wd.click.assert_not_called()

    def test_n5_missing_desktop_entry_does_not_depend_on_english(self):
        run = Mock()
        def command(args, **kw):
            if args[0] == 'test':
                return NS(returncode=1, stdout='', stderr='')
            return NS(returncode=1, stdout='', stderr='Datei oder Verzeichnis nicht gefunden')
        run.target.run.side_effect = command
        with patch.object(linux, 'require_candidate'):
            try:
                linux.desktop_entry(run)
            except Exception as exc:
                self.assertIsInstance(exc, runner.ProductFailure)
            else:
                self.fail('A missing desktop entry passed')
        self.assertTrue(any(c.args[0][:2] == ['test', '-e'] for c in run.target.run.call_args_list))

    def test_n6_new_jvm_logs_fail_vm_and_hosted_but_old_logs_do_not(self):
        for lane, vm_name in (('hosted-linux-x11', None), ('vm-linux-wayland', 'ubuntu')):
            for fresh in (False, True):
                with self.subTest(lane=lane, fresh=fresh), tempfile.TemporaryDirectory() as temp:
                    root = Path(temp)
                    home, tmp, engine, data = (root / p for p in ('home', 'tmp', 'engine', 'home/app'))
                    for folder in (home, tmp, engine, data / 'crashes'):
                        folder.mkdir(parents=True, exist_ok=True)
                    since = time.time_ns()
                    for i, folder in enumerate((home, tmp, engine, data / 'crashes')):
                        log = folder / f'hs_err_pid{100+i}.log'
                        log.write_text('# A fatal error has been detected by the Java Runtime Environment\n# SIGSEGV\n')
                        when = since + 1_000_000 if fresh else since - 10_000_000_000
                        os.utime(log, ns=(when, when))
                    args = NS(evidence=str(root / 'evidence'), vm=vm_name, lane=lane)
                    with patch.object(runner, 'Target'):
                        run = runner.Run(args, {'journeys': [], 'scenarios': {}})
                    run.work, run.data = str(root / 'work'), str(data)
                    run.crash_since_ns = since
                    run.stop_app = Mock()
                    target = run.target
                    target.py.return_value = 'not created'
                    def guest(source, value=None, **kw):
                        output = io.StringIO()
                        source = source.replace("Path('/tmp')", f'Path({str(tmp)!r})')
                        with patch.object(sys, 'argv', ['guest', json.dumps(value)]), \
                             patch.object(Path, 'home', return_value=home), \
                             patch.object(Path, 'cwd', return_value=root), \
                             patch.object(subprocess, 'run', return_value=NS(returncode=0, stdout=str(engine / 'ui-headless.jar')+'\n')), \
                             contextlib.redirect_stdout(output):
                            exec(source, {})
                        return json.loads(output.getvalue())
                    target.json.side_effect = guest
                    run.finish()
                    checks = [c for j in run.results for c in j['checks']]
                    product = [c for c in checks if c['status'] == 'failed-product']
                    self.assertEqual(bool(product), fresh)
                    self.assertTrue(any(c['name'] == 'bundled-jvm-crashes' for c in checks))
                    record = json.loads((run.out / 'jvm-crashes.json').read_text())
                    self.assertEqual(len(record), 4 if fresh else 0)
                    if fresh:
                        self.assertIn('SIGSEGV', json.dumps(record))
                        self.assertIn('jvm-crashes.json', product[0]['evidence'])
                        self.assertEqual(runner.report_exit_code(run.save()), 1)


if __name__ == '__main__':
    unittest.main()
