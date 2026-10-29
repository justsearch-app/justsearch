"""Round-two evidence and attribution counterexamples; isolated resources only."""
import contextlib
import io
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
from types import SimpleNamespace as NS
import unittest
from unittest.mock import Mock, MagicMock, patch

import linux
import runner
from test_repair2 import immediate
from test_repair4 import DEFINITION
import test_repair4
import test_repair6
from test_redesign import BINARY, SHELL, WINDOW
import test_redesign
from test_writes import wrapper


class UpdateTests(unittest.TestCase):
    def cancellation(self):
        run = Mock(binary=BINARY, run_id='isolated', facts={})
        run.args.package = 'candidate.deb'
        run.require.return_value = {'candidate.deb': 'abc'}
        run.target.owned.return_value = [SHELL]
        run.target.vm.config.return_value = {'password':'guest'}
        run.target.run.return_value = NS(stdout='[]')
        run.native.screen_text.return_value = 'Authentication password'
        run.target.alive.return_value = []
        return run

    def test_cancel_cannot_pass_from_foreign_password_pixels_without_candidate_elevation(self):
        run = self.cancellation()
        with patch.object(linux, 'installed_version', return_value='old'), \
             patch.object(linux, 'open_update_offer'), patch.object(linux, 'assert_state'), \
             patch.object(linux, 'wait_for', immediate), self.assertRaises(runner.ProductFailure):
            linux.cancel_update(run)
        run.native.key.assert_not_called()
        self.assertNotIn('updateCancelled', run.facts)

    def test_cancel_requires_exact_elevation_identity_to_exit(self):
        run = self.cancellation()
        elevation = {'pid': 98, 'ticks': '456', 'exe': '/usr/bin/pkexec',
                     'argv': ['pkexec','env','DEBIAN_FRONTEND=noninteractive','apt-get','install','-y','-o','DPkg::Lock::Timeout=300','/tmp/candidate.deb'],
                     'sha256': 'abc'}
        run.target.run.return_value.stdout = json.dumps([elevation])
        run.target.alive.return_value = [elevation]
        with patch.object(linux, 'installed_version', return_value='old'), \
             patch.object(linux, 'open_update_offer'), patch.object(linux, 'assert_state'), \
             patch.object(linux, 'wait_for', immediate), self.assertRaisesRegex(AssertionError, 'elevation process exited'):
            linux.cancel_update(run)
        run.native.key.assert_called_once_with('escape')

    def test_cancel_records_elevation_and_unchanged_version(self):
        run = self.cancellation()
        elevation = {'pid': 98, 'ticks': '456', 'exe': '/usr/bin/pkexec',
                     'argv': ['pkexec','env','DEBIAN_FRONTEND=noninteractive','apt-get','install','-y','-o','DPkg::Lock::Timeout=300','/tmp/candidate.deb'],
                     'sha256': 'abc'}
        run.target.run.return_value.stdout = json.dumps([elevation])
        with patch.object(linux, 'installed_version', return_value='old'), \
             patch.object(linux, 'open_update_offer'), patch.object(linux, 'assert_state'), \
             patch.object(linux, 'wait_for', immediate):
            result = linux.cancel_update(run)
        self.assertEqual(result.get('elevation'), [elevation])
        self.assertEqual(result['resumedVersion'], 'old')
        run.target.alive.assert_any_call([elevation])

    def test_cancel_rejects_wrong_executable_ownership_or_candidate_bytes(self):
        import hashlib
        for mismatch in ('exe','owner','artifact'):
            with self.subTest(mismatch=mismatch), tempfile.TemporaryDirectory() as temp:
                root=Path(temp);proc=root/'98';proc.mkdir();artifact=root/'candidate.deb';artifact.write_bytes(b'candidate')
                expected=hashlib.sha256(b'candidate').hexdigest()
                argv=['pkexec','env','DEBIAN_FRONTEND=noninteractive','apt-get','install','-y','-o','DPkg::Lock::Timeout=300',str(artifact)]
                (proc/'cmdline').write_bytes('\0'.join(argv).encode()+b'\0')
                (proc/'environ').write_bytes(b'QUAL_JOURNEY_RUN=other\0' if mismatch=='owner' else b'QUAL_JOURNEY_RUN=isolated\0')
                (proc/'stat').write_text('98 (pkexec) '+' '.join(['S','1']+['0']*17+['456']))
                (proc/'exe').symlink_to('/usr/bin/python3' if mismatch=='exe' else '/usr/bin/pkexec')
                if mismatch=='artifact':artifact.write_bytes(b'wrong')
                run=self.cancellation();run.require.return_value={'candidate.deb':expected}
                def execute(args,**kw):
                    source=args[-2].replace("Path('/proc')",'Path('+repr(temp)+')')
                    output=io.StringIO()
                    with patch.object(sys,'argv',['guest',args[-1]]),contextlib.redirect_stdout(output):exec(source,{})
                    return NS(stdout=output.getvalue())
                run.target.run.side_effect=execute
                with patch.object(linux,'installed_version',return_value='old'), \
                     patch.object(linux,'open_update_offer'),patch.object(linux,'assert_state'), \
                     patch.object(linux,'wait_for',immediate),self.assertRaises(runner.ProductFailure):
                    linux.cancel_update(run)
                run.native.key.assert_not_called()

    def test_success_rejects_retained_old_shell_or_engine(self):
        for old in (SHELL, {'pid': 78, 'ticks': '222', 'exe': '/opt/app/runtime/bin/java'}):
            run = test_repair4.Repair4Tests().update_run()
            old_shell = dict(SHELL, exe=run.binary)
            old_engine = {'pid': 78, 'ticks': '222', 'exe': '/opt/app/runtime/bin/java'}
            run.target.owned.side_effect = [[old_shell, old_engine], [dict(old_shell,pid=88,ticks='333')]]
            run.target.alive.return_value = [dict(old,exe=run.binary) if old == SHELL else old]
            with self.subTest(old=old), patch.object(linux, 'installed_version', return_value='0.3.1'), \
                 patch.object(linux, 'open_update_offer'), patch.object(linux, 'assert_state'), \
                 patch.object(linux, 'candidate_engine', return_value={'candidate':True}), \
                 patch.object(linux, 'update_elevation', return_value=[{'pid':98,'ticks':'456'}]), \
                 patch.object(linux, 'wait_for', immediate), self.assertRaisesRegex(AssertionError, 'Pre-update shell and Engine exited'):
                linux.update_success(run)
            recorded = next((c.args[1] for c in run.evidence.call_args_list if c.args[0] == 'J5-pre-update-processes'), [])
            self.assertEqual(recorded, [old_shell, old_engine])

    def test_success_cannot_skip_recording_old_engine_identity(self):
        run=test_repair4.Repair4Tests().update_run()
        run.target.owned.side_effect=[[dict(SHELL,exe=run.binary)],[dict(SHELL,pid=88,ticks='333',exe=run.binary)]]
        with patch.object(linux,'installed_version',return_value='0.3.1'), \
             patch.object(linux,'open_update_offer'),patch.object(linux,'assert_state'), \
             patch.object(linux,'candidate_engine',return_value={'candidate':True}), \
             patch.object(linux,'wait_for',immediate),self.assertRaisesRegex(AssertionError,'Pre-update Engine identity unavailable'):
            linux.update_success(run)

    def test_repair_banner_stops_offer_polling_as_product(self):
        run = Mock()
        def dom(script, args=None):
            if 'repair:' in script:return {'text': 'Manual repair required', 'repair': True}
            if 'jf-app-update-banner' in script:return 'Manual repair required'
            return False
        run.wd.execute.side_effect = dom
        run.require.return_value = '0.3.1'
        with patch.object(linux, 'wait_for', immediate), self.assertRaisesRegex(AssertionError, 'repair'):
            linux.open_update_offer(run, 'repair')


class ClassificationTests(unittest.TestCase):
    def test_viewer_exact_fixture_needs_no_ocr_or_second_app_launch(self):
        run = Mock(binary=BINARY, work='/tmp/isolated', facts={})
        run.wd.text.return_value = linux.OPEN_FILE
        run.wd.element.return_value = {'element-6066-11e4-a52e-4f735466cecf':'fixture'}
        run.native.screen_text.return_value = ''
        viewer = {'pid':123,'ticks':'456','exe':'/usr/bin/mousepad'}
        with patch.object(linux,'search_api',return_value={}), \
             patch.object(linux,'receiving_viewer',return_value=viewer), patch.object(linux,'wait_for',immediate):
            linux.search_open(run)
        run.native.screen_text.assert_not_called()
        run.desktop_spawn.assert_not_called()
        run.target.stop.assert_called_once_with([viewer])

    def test_viewer_return_never_spawns_an_unchecked_second_shell(self):
        run=Mock(binary=BINARY,work='/tmp/isolated',facts={})
        run.wd.text.return_value=linux.OPEN_FILE
        run.wd.element.return_value={'element-6066-11e4-a52e-4f735466cecf':'fixture'}
        run.native.screen_text.return_value='File Edit Document'
        with patch.object(linux,'search_api',return_value={}), \
             patch.object(linux,'receiving_viewer',return_value={'pid':123,'ticks':'456','exe':'/usr/bin/mousepad'}), \
             patch.object(linux,'wait_for',immediate):linux.search_open(run)
        run.desktop_spawn.assert_not_called()

    def test_j4_restored_settings_close_failure_is_product(self):
        run=test_redesign.BookmarkTests().restart_run()
        def dom(script,args=None):
            if 'localStorage' in script:return [test_redesign.BookmarkTests.bookmark]
            if 'return !all' in script:return False
            if 'Close Settings' in script:return True
            return True
        run.wd.execute.side_effect=dom
        run.close_settings.side_effect=lambda: runner.Run.close_settings(run)
        with patch.object(linux,'assert_state',return_value={}), \
             patch.object(linux,'wait_for',immediate),patch.object(runner,'wait_for',immediate), \
             self.assertRaises(runner.ProductFailure):linux.restart(run)

    def test_packaged_shell_never_mounts_is_product(self):
        run = Mock(binary=BINARY, work='/tmp/isolated', facts={})
        run.wd.execute.return_value = False
        with patch.object(linux, 'wait_for', immediate), self.assertRaises(AssertionError):
            linux.first_run(run)

    def test_packaged_rail_never_mounts_is_product(self):
        run = Mock(binary=BINARY, work='/tmp/isolated', facts={})
        wd = Mock(); wd.execute.return_value = False
        with patch.object(runner,'WebDriver',return_value=wd), patch.object(runner,'wait_for',immediate), \
             self.assertRaises(AssertionError):
            runner.Run.start_ui(run)

    def test_activation_neither_consent_nor_operation_is_product(self):
        run = Mock(); run.wd.execute.return_value = None
        with patch.object(linux,'wait_for',immediate), self.assertRaises(AssertionError):
            linux.activate_downloaded(run)

    def test_generic_product_timeout_is_not_download_transport(self):
        for message in ('model activation timeout', 'worker readiness timed out', 'model verification timeout'):
            run = test_redesign.PollingTests().ai_run([{'state':'failed','message':message}])
            with self.subTest(message=message), patch.object(linux,'wait_for',immediate), \
                 self.assertRaisesRegex(AssertionError, 'Install AI failed'):
                linux.install_ai(run)

    def test_download_transport_failures_remain_infrastructure(self):
        for message in ('download connection timed out', 'download read timeout', 'HTTP 503'):
            run = test_redesign.PollingTests().ai_run([{'state':'failed','message':message}])
            with self.subTest(message=message), patch.object(linux,'wait_for',immediate):
                try:linux.install_ai(run)
                except Exception as exc:self.assertEqual(runner.classify(exc), 'failed-infrastructure')
                else:self.fail('Failed download passed')

    def test_missing_bookmark_element_without_storage_corroboration_is_infrastructure(self):
        run = Mock()
        run.wd.execute.side_effect = lambda script,*a: [] if 'localStorage' in script else None
        with patch.object(linux,'wait_for',immediate), self.assertRaises(runner.Infrastructure):
            linux.bookmark_entry(run,test_redesign.BookmarkTests.bookmark)

    def test_search_baseline_setup_failure_is_infrastructure(self):
        run = Mock(binary=BINARY, work='/tmp/isolated', facts={})
        run.wd.execute.return_value = False
        with patch.object(linux,'require_candidate'), patch.object(linux,'wait_for',immediate), \
             self.assertRaises(runner.Infrastructure):
            linux.deep_links(run)

    def test_all_rail_navigation_closes_restored_settings_first(self):
        for surface in ('Search','Library','AI Brain','Settings'):
            with self.subTest(surface=surface),tempfile.TemporaryDirectory() as temp:
                run=runner.Run(NS(evidence=temp+'/run',vm=None),DEFINITION)
                run.facts['ui']=True;run.wd=Mock()
                run.wd.execute.side_effect=[True,False]
                run.wd.element.return_value={'element-6066-11e4-a52e-4f735466cecf':'rail'}
                with patch.object(runner,'wait_for',immediate):run.nav(surface)
                calls=run.wd.mock_calls
                self.assertIn(unittest.mock.call.click('button[aria-label="Close Settings"]'),calls)
                self.assertLess(next(i for i,c in enumerate(calls) if c[0]=='click'),
                                next(i for i,c in enumerate(calls) if c[0]=='element'))

    def test_restored_settings_stuck_is_product(self):
        with tempfile.TemporaryDirectory() as temp:
            run=runner.Run(NS(evidence=temp+'/run',vm=None),DEFINITION)
            run.facts['ui']=True;run.wd=Mock();run.wd.execute.return_value=True
            with patch.object(runner,'wait_for',immediate),self.assertRaises(runner.ProductFailure):run.nav('Search')
            run.wd.element.assert_not_called()


class ProcessTests(unittest.TestCase):
    def test_stop_app_includes_deleted_installed_executables_and_excludes_impostor(self):
        run=Mock(binary=BINARY,facts={},wd=None)
        deleted=dict(SHELL,exe=BINARY+' (deleted)')
        foreign=dict(SHELL,pid=88,exe='/tmp/foreign/JustSearch')
        java={'pid':99,'ticks':'123','exe':'/opt/app/runtime/bin/java (deleted)'}
        run.target.owned.return_value=[deleted,foreign,java]
        run.target.run.return_value.stdout=BINARY+'\n/opt/app/runtime/bin/java\n'
        runner.Run.stop_app(run)
        run.target.stop.assert_called_once_with([deleted,java])

    def test_launch_cannot_accept_prelaunch_process_window(self):
        with tempfile.TemporaryDirectory() as temp:
            run=runner.Run(NS(evidence=temp+'/run',vm=None),DEFINITION)
            run.binary=BINARY;run.facts['preLaunchProcesses']=[SHELL]
            run.target.owned=Mock(return_value=[SHELL])
            run.native.windows=Mock(return_value={'focused':WINDOW,'windows':[WINDOW]})
            run.native.shot=Mock()
            with patch.object(runner,'wait_for',immediate),self.assertRaises(AssertionError):run.observe_window('stale')

    def test_stop_does_not_treat_unreadable_identity_as_gone(self):
        target=runner.Target()
        def execute(source,value,**kw):
            with patch.object(sys,'argv',['guest',json.dumps(value)]), \
                 patch.object(Path,'read_text',side_effect=PermissionError('proc stat denied')):
                exec(source,{})
        target.py=execute
        with self.assertRaises(PermissionError):target.stop([SHELL])

    def test_stop_waits_after_kill_until_exact_identity_is_gone(self):
        state={'alive':True,'killed':False,'time':0}
        def stat(path,*a,**kw):
            if not state['alive']:raise FileNotFoundError()
            return '77 (app) '+ ' '.join(['S','1']+['0']*17+['123'])
        def kill(pid,sig):
            if sig==signal.SIGKILL:state['killed']=True
        def sleep(seconds):
            state['time']+=seconds
            if state['killed']:state['alive']=False
        target=runner.Target()
        def execute(source,value,**kw):
            with patch.object(sys,'argv',['guest',json.dumps(value)]),patch.object(Path,'read_text',stat), \
                 patch.object(os,'kill',kill),patch.object(runner.time,'sleep',sleep), \
                 patch.object(runner.time,'monotonic',side_effect=lambda:state['time']):exec(source,{})
        target.py=execute
        target.stop([SHELL])
        self.assertFalse(state['alive'],'stop returned before SIGKILL exit was observed')


class CrashTests(unittest.TestCase):
    def test_webkit_children_fail_product_even_without_package_attribution(self):
        for name in ('WebKitWebProcess','WebKitNetworkProcess'):
            with self.subTest(name=name),tempfile.TemporaryDirectory() as temp:
                root=Path(temp)/'crashes';root.mkdir();out=Path(temp)/'out';out.mkdir()
                (root/'child.crash').write_text('ExecutablePath: /usr/lib/webkit/'+name+'\nSignal: 11\n')
                with self.assertRaisesRegex(AssertionError,'Product crash'):
                    test_repair6.Round3Tests().crash(root,out,[])

    def test_product_crash_fields_after_core_are_still_attributed(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'crashes';root.mkdir();out=Path(temp)/'out';out.mkdir()
            (root/'child.crash').write_text('ProblemType: Crash\nCoreDump: base64\n ignored-core-payload\nExecutablePath: /usr/lib/webkit/WebKitWebProcess\nSignal: 11\n')
            with self.assertRaisesRegex(AssertionError,'Product crash'):
                test_repair6.Round3Tests().crash(root,out,[])

    def test_owner_only_lane_has_distinct_exit_code(self):
        rows=[{'id':'J7','checks':[{'name':'deep-link-cold','status':'owner-validation','evidence':['cold.png']}]}]
        with tempfile.TemporaryDirectory() as temp,patch.object(wrapper,'full_selection',return_value=True):
            code=wrapper.combine(NS(session='wayland',mode='fresh',journeys=None),Path(temp),{'candidate':{'journeys':rows}})
        self.assertEqual(code,2)

    def test_observed_app_descendant_crash_is_product(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'crashes';root.mkdir();out=Path(temp)/'out';out.mkdir();(out/'run').mkdir()
            (out/'run/app-processes.json').write_text(json.dumps([SHELL]))
            (root/'child.crash').write_text('ExecutablePath: /usr/bin/helper\nProcStatus:\n Pid:\t78\n PPid:\t77\nSignal: 6\n')
            with self.assertRaisesRegex(AssertionError,'Product crash'):
                test_repair6.Round3Tests().crash(root,out,[])

    def test_crash_reports_preserve_transitive_app_ancestry_in_any_order(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'crashes';root.mkdir();out=Path(temp)/'out';out.mkdir();(out/'run').mkdir()
            (out/'run/app-processes.json').write_text(json.dumps([SHELL]))
            for name,pid,ppid in (('grandchild',79,78),('child',78,77)):
                (root/(name+'.crash')).write_text(f'ExecutablePath: /usr/bin/{name}\nProcStatus:\n Pid:\t{pid}\n PPid:\t{ppid}\nSignal: 6\n')
            with self.assertRaisesRegex(AssertionError,'Product crash'):
                test_repair6.Round3Tests().crash(root,out,[])
            reports=json.loads((out/'guest-crashes.json').read_text())
            self.assertEqual([r['attribution'] for r in reports],['product','product'])

    def test_unattributed_crash_changes_final_lane_to_pending_owner(self):
        with tempfile.TemporaryDirectory() as temp:
            out=Path(temp)/'overlay';disk=Path(temp)/'disk';disk.touch()
            started={'pid':os.getpid(),'disk':str(disk)}
            args=NS(session='wayland',memory_mib=12288,memory_reason=None,mirror=None,mode='rerun')
            def crashes(out):
                (out/'guest-crashes.json').write_text(json.dumps([{'file':'/var/crash/other.crash',
                    'attribution':'unattributed','fields':{'ExecutablePath':'/usr/bin/other','Signal':'6'}}]))
            def execute(args,out,*a):
                report={'journeys':[{'id':'J7','checks':[{'name':'desktop-entry','status':'passed','evidence':[]}]}], 'fullSelection':True}
                report.update(runner.summarize(report['journeys']))
                (out/'summary.json').write_text(json.dumps(report));return 0
            qmp=MagicMock();qmp.return_value.__enter__.return_value.command.return_value=[]
            with patch.object(wrapper,'pins'),patch.object(wrapper.vm,'wait_free'), \
                 patch.object(wrapper,'ports_available',return_value=True),patch.object(wrapper.vm,'start'), \
                 patch.object(wrapper.vm,'require_owner',return_value=started),patch.object(wrapper.vm,'wait_ssh'), \
                 patch.object(wrapper.vm,'guest_root'),patch.object(wrapper,'provision'), \
                 patch.object(wrapper,'prepare_desktop'),patch.object(wrapper,'execute',side_effect=execute), \
                 patch.object(wrapper,'crash_evidence',side_effect=crashes), \
                 patch.object(wrapper.vm,'read_state',return_value=started),patch.object(wrapper.vm,'stop',return_value=True), \
                 patch.object(wrapper.vm,'QMP',qmp),patch.object(wrapper,'Target') as target, \
                 patch.object(wrapper,'Native') as native:
                target.return_value.json.return_value={'writtenBytes':123}
                wrapper.run_overlay(args,out,'candidate',['J7'])
            report=json.loads((out/'summary.json').read_text())
            self.assertEqual(report['status'],'passed-pending-owner-validation')
            self.assertFalse(report['passed'])
            row=next(c for j in report['journeys'] for c in j['checks'] if c['status']=='owner-validation')
            self.assertIn('/usr/bin/other',row['reason'])
            self.assertIn('../guest-crashes.json', row['evidence'])
            self.assertFalse(any(p.endswith('.png') for p in row['evidence']))
            native.assert_not_called()


if __name__=='__main__':unittest.main()
