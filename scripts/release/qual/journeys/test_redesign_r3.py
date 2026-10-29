"""Review counterexamples: no host installs, desktop, shared service or VM."""
import json
from pathlib import Path
import tempfile
import subprocess
from types import SimpleNamespace as NS
import unittest
from unittest.mock import Mock, MagicMock, patch

import linux
import runner
from test_repair2 import immediate
import test_repair4
import test_redesign_r2
import test_repair6
from test_writes import wrapper


class HostedCrashTests(unittest.TestCase):
    def collect(self, root, entries, *, code=0, stderr=''):
        report = {'journeys': [{'id': 'J1', 'checks': [
            {'name': 'install', 'status': 'passed', 'evidence': []}]}], 'fullSelection': True}
        report.update(runner.summarize(report['journeys']))
        (root / 'summary.json').write_text(json.dumps(report))
        (root / 'app-processes.json').write_text(json.dumps([{'pid':77,'ticks':'123','exe':'/opt/JustSearch'}]))
        collect = getattr(runner, 'collect_hosted_crashes', None)
        self.assertIsNotNone(collect, 'Hosted lane has no crash collection/verdict step')
        with patch.object(runner.subprocess, 'run', side_effect=[
                NS(returncode=code, stdout=json.dumps(entries), stderr=stderr),
                NS(returncode=0, stdout='/opt/JustSearch\n')]) as command:
            result = collect(root, '2026-10-29T00:00:00+00:00')
        self.assertIn('coredumpctl', command.call_args_list[0].args[0])
        self.assertIn('--json=short', command.call_args_list[0].args[0])
        return result, json.loads((root / 'summary.json').read_text())

    def test_hosted_product_crash_fails_an_otherwise_passing_lane(self):
        with tempfile.TemporaryDirectory() as temp:
            code, report = self.collect(Path(temp), [{'exe':'/opt/JustSearch','sig':11}])
            self.assertEqual(code, 1)
            self.assertEqual(report['counts']['failed-product'], 1)
            self.assertFalse(report['passed'])

    def test_hosted_unknown_crash_requires_owner_validation(self):
        with tempfile.TemporaryDirectory() as temp:
            code, report = self.collect(Path(temp), [{'exe':'/usr/bin/other','sig':6}])
            self.assertEqual(code, 2)
            self.assertEqual(report['status'], 'passed-pending-owner-validation')
            self.assertFalse(report['passed'])

    def test_hosted_collection_error_is_infrastructure(self):
        with tempfile.TemporaryDirectory() as temp:
            code, report = self.collect(Path(temp), [], code=1, stderr='Journal access denied')
            self.assertEqual(code, 1)
            self.assertEqual(report['counts']['failed-infrastructure'], 1)

    def test_empty_coredump_journal_is_not_a_collection_error(self):
        with tempfile.TemporaryDirectory() as temp:
            code, report = self.collect(Path(temp), [], code=1, stderr='No coredumps found.\n')
            self.assertEqual(code, 2)
            self.assertEqual(report['status'], 'passed-pending-owner-validation')
            self.assertFalse(report['passed'])

    def test_workflow_provisions_and_collects_crashes_after_hosted_run(self):
        workflow = (runner.HERE.parents[3] / '.github/workflows/qualify-release-linux.yml').read_text()
        self.assertIn('systemd-coredump', workflow)
        self.assertIn('collect_hosted_crashes(out,', workflow)
        self.assertLess(workflow.index('journeys/hosted.py'), workflow.index('collect_hosted_crashes(out,'))
        self.assertLess(workflow.index('[[ $RUNNER_ENVIRONMENT == github-hosted ]]'), workflow.index('sudo'))


class UpdateReviewTests(unittest.TestCase):
    def test_half_installed_candidate_version_does_not_count_as_installed(self):
        for state in ('iU ', 'iF ', 'iH ', 'rc ', 'iiR'):
            with self.subTest(state=state):
                run = Mock()
                def query(args, **kw):
                    fmt = args[args.index('-W') + 1]
                    return NS(returncode=0, stdout=state+'0.3.1' if 'Status-Abbrev' in fmt else '0.3.1')
                run.target.run.side_effect = query
                self.assertNotEqual(linux.installed_version(run), '0.3.1')
        state = 'ii '
        self.assertEqual(linux.installed_version(run), '0.3.1')

    def test_update_success_waits_for_fully_configured_candidate(self):
        run = test_repair4.Repair4Tests().update_run()
        run.target.json.return_value = {'pid':77,'executableMatches':True,'jarMatches':True}
        def query(args, **kw):
            if '-L' in args:
                return NS(returncode=0, stdout='/opt/runtime/bin/java\n/opt/ui-headless.jar\n')
            if args[0] == 'dpkg-query':
                return NS(returncode=0, stdout='iU 0.3.1' if 'Status-Abbrev' in args[2] else '0.3.1')
            return NS(returncode=0, stdout='')
        run.target.run.side_effect = query
        with patch.object(linux,'open_update_offer'),patch.object(linux,'assert_state'), \
             patch.object(linux,'update_elevation',return_value=[{'pid':98,'ticks':'456'}]), \
             patch.object(linux,'wait_for',immediate), \
             self.assertRaisesRegex(AssertionError,'Candidate installed by updater'):
            linux.update_success(run)
        run.ready.assert_not_called()

    def test_candidate_engine_rejects_half_installed_package(self):
        run = Mock()
        run.require.return_value = '0.3.1'
        run.api.return_value = {'pid':77}
        run.target.json.return_value = {'executableMatches':True,'jarMatches':True}
        def query(args, **kw):
            if '-L' in args:
                return NS(returncode=0, stdout='/opt/runtime/bin/java\n/opt/ui-headless.jar\n')
            return NS(returncode=0, stdout='iF 0.3.1' if 'Status-Abbrev' in args[2] else '0.3.1')
        run.target.run.side_effect = query
        with self.assertRaisesRegex(AssertionError, 'candidate package version'):
            linux.candidate_engine(run)

    def test_cancel_missing_owned_pkexec_is_product_before_ocr(self):
        run = test_redesign_r2.UpdateTests().cancellation()
        run.native.screen_text.return_value = ''
        with patch.object(linux, 'installed_version', return_value='old'), \
             patch.object(linux, 'wait_for', immediate):
            try: linux.cancel_update(run)
            except Exception as exc:
                self.assertEqual(runner.classify(exc), 'failed-product')
            else: self.fail('Missing owned elevation passed')
        run.native.screen_text.assert_not_called()
        run.native.key.assert_not_called()

    def test_cancel_wrong_installer_or_hash_is_product(self):
        for mismatch in ('argv', 'sha256'):
            with self.subTest(mismatch=mismatch):
                run = test_redesign_r2.UpdateTests().cancellation()
                row = {'pid':98,'ticks':'456','exe':'/usr/bin/pkexec',
                       'argv':['pkexec','env','DEBIAN_FRONTEND=noninteractive','apt-get','install','-y','-o','DPkg::Lock::Timeout=300','/tmp/candidate.deb'],
                       'sha256':'abc'}
                row[mismatch] = ['pkexec','sh'] if mismatch == 'argv' else 'wrong'
                run.target.run.return_value = NS(stdout=json.dumps([row]))
                with patch.object(linux,'installed_version',return_value='old'), \
                     patch.object(linux,'open_update_offer'), patch.object(linux,'assert_state'), \
                     patch.object(linux,'wait_for',immediate), self.assertRaises(runner.ProductFailure):
                    linux.cancel_update(run)
                run.native.key.assert_not_called()

    def test_success_never_types_password_without_owned_elevation(self):
        run = test_repair4.Repair4Tests().update_run()
        with patch.object(linux,'installed_version',return_value='0.3.1'), \
             patch.object(linux,'open_update_offer'), patch.object(linux,'assert_state'), \
             patch.object(linux,'update_elevation',return_value=[]), \
             patch.object(linux,'wait_for',immediate):
            try: linux.update_success(run)
            except Exception: pass
        run.native.type.assert_not_called()
        run.native.key.assert_not_called()

    def test_lock_never_types_password_without_owned_elevation(self):
        run = Mock(work='/tmp/isolated', facts={})
        run.target.vm.config.return_value = {'password':'guest'}
        run.target.json.return_value = {'pid':98,'ticks':'456'}
        run.target.py.return_value = 'False'
        run.native.screen_text.return_value = 'Authentication password'
        with patch.object(linux,'installed_version',return_value='old'), \
             patch.object(linux,'open_update_offer'), patch.object(linux,'update_elevation',return_value=[]), \
             patch.object(linux,'wait_for',immediate), patch.object(linux.time,'monotonic',side_effect=[0,300]):
            try: linux.update_lock(run)
            except Exception: pass
        run.native.type.assert_not_called()
        run.native.key.assert_not_called()


class ClassificationReviewTests(unittest.TestCase):
    def test_hosted_vm_crashes_read_the_actual_unpacked_process_record(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / 'crashes'; root.mkdir()
            out = Path(temp) / 'out'; (out / 'evidence').mkdir(parents=True)
            (out / 'evidence/app-processes.json').write_text(json.dumps([{'pid':77,'ticks':'123'}]))
            (root / 'child.crash').write_text('ExecutablePath: /usr/bin/helper\nProcStatus:\n Pid: 78\n PPid: 77\nSignal: 6\n')
            with self.assertRaisesRegex(AssertionError,'Product crash'):
                test_repair6.Round3Tests().crash(root,out,[])

    def test_pre_install_crashes_have_separate_evidence(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            target = Mock()
            target.run.return_value.stdout = 'false'
            target.run.return_value.returncode = 1
            with patch.object(wrapper,'Target',return_value=target), \
                 patch.object(wrapper,'crash_evidence') as crashes, \
                 patch.object(wrapper.vm,'QMP',MagicMock()), patch('screen.key'):
                wrapper.prepare_desktop(NS(session='x11'),out)
            crashes.assert_called_once_with(out / 'before-install', before_install=True)

    def test_crash_pid_reuse_cannot_blame_an_observed_app(self):
        for report_pid, parent, ticks in ((77, 1, 999), (78, 77, 100)):
            with self.subTest(pid=report_pid, ticks=ticks):
                stat = str(report_pid) + ' (helper) ' + ' '.join(['S',str(parent)]+['0']*17+[str(ticks)])
                reports = [{'fields': {'ExecutablePath':'/usr/bin/helper',
                    'ProcStatus':f'Pid: {report_pid}\nPPid: {parent}', 'ProcStat':stat}}]
                wrapper.attribute_crashes(reports, set(), [{'pid':77,'ticks':'123'}])
                self.assertEqual(reports[0]['attribution'],'unattributed')
        reports = [{'fields': {'ExecutablePath':'/usr/bin/helper',
            'ProcStatus':'Pid: 77\nPPid: 1', 'ProcStat':'77 (helper) '+' '.join(['S','1']+['0']*17+['123'])}}]
        wrapper.attribute_crashes(reports, set(), [{'pid':77,'ticks':'123'}])
        self.assertEqual(reports[0]['attribution'],'product')

    def test_missing_installed_desktop_file_is_product(self):
        run = Mock()
        def cat(args, **kw):
            message = 'cat: /usr/share/applications/JustSearch.desktop: No such file or directory'
            if kw.get('check', True):
                raise subprocess.CalledProcessError(1, args, output='', stderr=message)
            return NS(returncode=1, stdout='', stderr=message)
        run.target.run.side_effect = cat
        with patch.object(linux,'require_candidate'):
            try: linux.desktop_entry(run)
            except Exception as exc:
                self.assertEqual(runner.classify(exc),'failed-product')
            else: self.fail('Missing desktop entry passed')

    def test_delivered_close_button_action_without_close_is_product(self):
        run = Mock()
        run.wd.execute.return_value = True
        with patch.object(runner,'wait_for',immediate):
            try: runner.Run.close_settings(run)
            except Exception as exc:
                self.assertEqual(runner.classify(exc),'failed-product')
            else: self.fail('Settings stayed open but passed')
        run.wd.click.assert_called_once()

    def test_context_menu_missing_open_file_is_product_after_secondary_click(self):
        run = Mock(facts={})
        wd = runner.WebDriver(Mock(), 4444); wd.session = 'isolated'
        run.wd = wd
        wd.keys = Mock(); wd.element = Mock(return_value={'element-6066-11e4-a52e-4f735466cecf':'hit'})
        wd.call = Mock()
        # Other setup clicks succeed; use real click/element for Open file.
        def click(selector, text=None, **kw):
            if text == 'Open file':
                with patch.object(wd,'element',side_effect=lambda *a,**k: runner.WebDriver.element(wd,*a,**k)), \
                     patch.object(wd,'execute',return_value=None), patch.object(runner,'wait_for',immediate):
                    runner.WebDriver.click(wd,selector,text,**kw)
        wd.click = Mock(side_effect=click)
        with patch.object(linux,'search_api',return_value={}),patch.object(linux,'wait_for',return_value=True):
            try: linux.search_open(run)
            except Exception as exc:
                self.assertEqual(runner.classify(exc),'failed-product')
            else: self.fail('Missing Open file action passed')
        wd.call.assert_called_once()


if __name__ == '__main__': unittest.main()
