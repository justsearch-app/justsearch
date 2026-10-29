"""Windows evidence contracts: isolated fixtures and an owned process-handle check.

S3 (Windows part): "Journeys J1-J9 run against the draft's exact bytes on hosted runners, the two clean VMs and the GPU host, and produce one result summary that separates passed, failed, skipped by decision (Windows GPU) and unavailable"
S21 (Windows part): "The MCP bridge's manifest and registry entry carry the app's version, the release URL and checksum, declare Linux and Windows, and the bridge works on both (J8)"
S22: "Journeys run on clean installs without a developer JDK, models or inherited runtime paths; a missing download, skipped or unavailable check is reported as such, never as a pass"
Windows update: "Windows in-app update via descriptor v2 from a VM-local mirror: success relaunches the new version with data kept; a cancelled elevation or failed install resumes the old version and re-offers"
"""
import unittest

from runner import Infrastructure, ProductFailure
import windows


class WindowsEvidenceTests(unittest.TestCase):
    def test_cached_downloads_resume_only_from_positive_acquisition_evidence(self):
        from unittest.mock import Mock, patch
        required = ('embedding', 'splade', 'reranker', 'ner', 'citation-scorer')
        complete = {'state': 'completed', 'phase': 'activation_required', 'repairNeeded': False, 'packages': [
            {'packageId': name, 'state': 'installed', 'error': ''} for name in required]}
        cases = [(complete, True), (dict(complete, state='failed'), False),
                 (dict(complete, repairNeeded=True), False),
                 (dict(complete, repairNeeded=None), False), (dict(complete, phase='unknown'), False),
                 (dict(complete, lastError='failed'), False), (dict(complete, errorCode='failure'), False),
                 (dict(complete, packages=complete['packages'][:-1]), False)]
        for status, cached in cases:
            with self.subTest(status=status):
                run = Mock(facts={});run.args.resume='checkpoint.json';run.api.return_value=status
                with patch.object(windows, 'shared_install_ai', return_value='all readiness checks retained') as shared:
                    self.assertEqual(windows.install_ai(run), 'all readiness checks retained')
                self.assertEqual(run.facts.get('resumedSetup', False), cached)
                shared.assert_called_once_with(run)
        run=Mock(facts={});run.args.resume=None
        with patch.object(windows,'shared_install_ai'):
            windows.install_ai(run)
        run.api.assert_not_called()

    @unittest.skipUnless(windows.os.name == 'nt', 'Windows PowerShell fixture')
    def test_resource_log_tails_are_plain_strings_without_powershell_file_metadata(self):
        import ast
        import json
        import tempfile
        from pathlib import Path
        tree = ast.parse(Path(__file__).with_name('vm-windows.py').read_text())
        budget = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == 'WriteBudget')
        method = next(n for n in budget.body if isinstance(n, ast.FunctionDef) and n.name == 'resources')
        assignment = next(n for n in method.body if isinstance(n, ast.Assign)
                          and any(isinstance(t, ast.Name) and t.id == 'source' for t in n.targets))
        suffix = ast.literal_eval(assignment.value.right)
        with tempfile.TemporaryDirectory() as temp:
            logs = Path(temp) / 'io.justsearch.shell/logs'
            logs.mkdir(parents=True)
            (logs / 'engine.log').write_text('engine fixture message\n', encoding='utf-8')
            # Exercise the production file/log collector with owned fixture data;
            # memory is fixed so the test never inspects a running application.
            source = '$env:APPDATA=' + windows.ps_value(temp) + ';$memory=& { @{fixture=$true}' + suffix
            result = json.loads(windows.ps_json(source))
        self.assertEqual(result['engineLogs'][0]['tail'], ['engine fixture message'])

    @unittest.skipUnless(windows.os.name == 'nt', 'Windows PowerShell fixture')
    def test_update_probe_stops_a_reverted_service_and_records_its_original_state(self):
        import ast
        from pathlib import Path
        tree = ast.parse(Path(__file__).with_name('vm-windows.py').read_text())
        source = next(ast.literal_eval(node.value) for node in tree.body if isinstance(node, ast.Assign)
                      and any(isinstance(target, ast.Name) and target.id == 'UPDATE_ACTIVITY' for target in node.targets))
        # Shadow all guest interfaces: this fixture never controls real services.
        mock = r"""
$since=$null;$script:serviceState='Running'
function Test-Path {param($Path) $false}
function Get-CimInstance {param($ClassName)
 [pscustomobject]@{Name='wuauserv';StartMode='Manual';State=$script:serviceState}
 [pscustomobject]@{Name='UsoSvc';StartMode='Disabled';State='Stopped'}
}
function Stop-Service {param($Name,[switch]$Force,$ErrorAction) $script:serviceState='Stopped'}
function Get-WinEvent {param($ListLog) [pscustomobject]@{LogName='fixture';IsEnabled=$true}}
function Get-ItemProperty {param($Path) [pscustomobject]@{NoAutoUpdate=1;AUOptions=2}}
"""
        import json
        result = json.loads(windows.ps_json(mock + source))
        self.assertEqual(result['services'][0]['State'], 'Stopped')
        exception = result['controlExceptions'][0]
        self.assertEqual(exception['observed']['State'], 'Running')
        self.assertIsNone(exception['controlError'])
        self.assertEqual(result['downloadBytes'], 0)

    def test_driver_must_match_webview_build(self):
        windows.match_driver('155.0.4283.62', 'MSEdgeDriver 155.0.4283.62')
        with self.assertRaisesRegex(Infrastructure, '155.0.4283.50'):
            windows.match_driver('155.0.4283.62', 'MSEdgeDriver 155.0.4283.50')
        with self.assertRaises(Infrastructure):
            windows.match_driver('155.0.4283.62', 'MSEdgeDriver 154.0.4283.62')
        with self.assertRaises(Infrastructure):
            windows.match_driver('', 'MSEdgeDriver 155.0.4283.62')

    def test_installer_exit_code_is_product(self):
        windows.installer_result({'exitCode': 0})
        with self.assertRaises(ProductFailure):
            windows.installer_result({'exitCode': 2})

    def test_smartscreen_is_environment(self):
        with self.assertRaises(Infrastructure):
            windows.installer_result({'blockedBy': 'SmartScreen'})

    def test_missing_installer_result_is_not_pass(self):
        with self.assertRaises(Infrastructure):
            windows.installer_result({})

    def test_identity_requires_image_pid_and_creation_time(self):
        app = {'pid': 50, 'created': '20261029090000.000000+000',
               'exe': r'C:\Users\qual\AppData\Local\JustSearch\JustSearch.exe', 'ppid': 10}
        self.assertTrue(windows.same_process(app, dict(app)))
        for key, changed in [('pid', 51), ('created', 'later'), ('exe', r'C:\other\JustSearch.exe')]:
            self.assertFalse(windows.same_process(app, dict(app, **{key: changed})))
        self.assertFalse(windows.same_process(app, {'pid': 50}))

    def test_product_crash_requires_exact_identity_or_bundled_image(self):
        app = {'pid': 50, 'created': '20261029090000.000000+000',
               'exe': r'C:\app\JustSearch.exe', 'ppid': 10}
        self.assertEqual(windows.crash_attribution(dict(app), [app], r'C:\app'), 'product')
        self.assertEqual(windows.crash_attribution(dict(app, created='later', exe=r'C:\other\app.exe'), [app], r'C:\app'), 'unattributed')
        self.assertEqual(windows.crash_attribution({'exe': r'C:\app\resources\headless\runtime\bin\java.exe'}, [], r'C:\app'), 'product')
        self.assertEqual(windows.crash_attribution({'exe': r'C:\application\java.exe'}, [], r'C:\app'), 'unattributed')

    def test_unrelated_webview_crash_is_not_product(self):
        self.assertEqual(windows.crash_attribution({'exe': r'C:\Edge\msedgewebview2.exe'}, [], r'C:\app'), 'unattributed')

    def test_webview_started_by_app_is_product(self):
        app = {'pid': 50, 'created': '20261029090000.000000+000', 'exe': r'C:\app\JustSearch.exe', 'ppid': 10}
        child = {'pid': 51, 'created': '20261029090001.000000+000', 'exe': r'C:\Edge\msedgewebview2.exe', 'ppid': 50,
                 'parentCreated': app['created']}
        self.assertEqual(windows.crash_attribution(child, [app], r'C:\app'), 'product')
        self.assertEqual(windows.crash_attribution(dict(child, parentCreated='reused'), [app], r'C:\app'), 'unattributed')

    def test_update_requires_relaunch_retention_and_old_process_exit(self):
        valid = {'installedVersion': '0.3.2', 'window': True, 'engineReady': True,
                 'oldShellAlive': False, 'oldEngineAlive': False, 'stateUnchanged': True}
        windows.update_result(valid, '0.3.2')
        for key, value in [('installedVersion', '0.3.1'), ('window', False), ('engineReady', False),
                           ('oldShellAlive', True), ('oldEngineAlive', True), ('stateUnchanged', False)]:
            with self.subTest(key=key), self.assertRaises(ProductFailure):
                windows.update_result(dict(valid, **{key: value}), '0.3.2')

    def test_cancellation_needs_actual_installer_and_reoffer(self):
        valid = {'installerObserved': True, 'installerExited': True, 'versionUnchanged': True,
                 'engineReady': True, 'reoffered': True}
        windows.cancel_result(valid)
        for key in valid:
            with self.subTest(key=key), self.assertRaises(ProductFailure):
                windows.cancel_result(dict(valid, **{key: False}))




# Operational fixtures exercise app evidence and its counterexamples. All files,
# processes and HTTP/PowerShell observations are isolated or mocked.
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
from unittest.mock import Mock, patch, PropertyMock
import zipfile

import linux
from runner import Run as SharedRun, OwnerValidation, Unavailable, summarize, report_exit_code
from writes import difference


@unittest.skipUnless(os.name == 'nt', 'Windows held process handles')
class WindowsLifecycleTests(unittest.TestCase):
    def test_exited_handles_and_stops_keep_exact_lifetime_checks(self):
        import sys
        with tempfile.TemporaryDirectory() as temp:
            target=windows.Target();target.env=dict(os.environ)
            try:
                for operation in ('owned','stop'):
                    with self.subTest(operation=operation):
                        row=target.spawn([sys.executable,'-c','import time;time.sleep(1)'],str(Path(temp)/'child.log'))
                        target.handles[row['pid']].wait(timeout=10)
                        if operation=='owned':self.assertEqual(target.owned(),[])
                        else:target.stop([row])
                row=target.spawn([sys.executable,'-c','import time;time.sleep(300)'],str(Path(temp)/'child.log'))
                target.stop([dict(row,created='1900-01-01T00:00:00.0000000Z')])
                self.assertIsNone(target.handles[row['pid']].poll(), 'A mismatched process lifetime must not be killed')
                target.stop([row])
                self.assertIsNotNone(target.handles[row['pid']].poll())
            finally:
                for handle in target.handles.values():
                    if handle.poll() is None:handle.terminate();handle.wait(timeout=20)


def once(fn, description, *args, error=AssertionError, **kwargs):
    value = fn()
    if not value:
        raise error(description)
    return value


def process(pid=50, image=r'C:\app\JustSearch.exe', created='2026-10-29T09:00:00.0000000Z', parent=10):
    return {'pid': pid, 'created': created, 'exe': image, 'ppid': parent}


class WindowsOperationalTests(unittest.TestCase):
    def test_owned_parent_chain_retains_exact_creation_times_and_excludes_reuse(self):
        target = windows.Target()
        shell = process()
        java = process(51, r'C:\app\runtime\bin\java.exe', '2026-10-29T09:00:01.0000000Z', 50)
        webview = process(52, r'C:\Edge\msedgewebview2.exe', '2026-10-29T09:00:02.0000000Z', 50)
        reused = process(50, created='2026-10-29T10:00:00.0000000Z')
        target.roots = [shell]
        target.app_binary = shell['exe']
        with patch.object(windows, 'ps_json', return_value=[shell, java, webview]):
            owned = target.owned()
        self.assertEqual({p['pid'] for p in owned}, {50, 51, 52})
        self.assertEqual(next(p for p in owned if p['pid']==52)['parentCreated'], shell['created'])
        self.assertEqual(len(target.app_processes), 3)
        with patch.object(windows, 'ps_json', return_value=[reused]):
            self.assertEqual(target.owned(), [])

    def test_uia_window_must_belong_to_exact_installed_image_and_owned_lifetime(self):
        target = Mock(app_binary=r'C:\app\JustSearch.exe')
        target.owned.return_value = [process()]
        native = windows.Native(target, Path('unused'))
        visible = {'pid': 50, 'handle': 200, 'focused': True}
        with patch.object(native, 'windows', return_value=[visible]):
            self.assertEqual(native.app_window(target.app_binary)['handle'], 200)
            self.assertIsNone(native.app_window(r'C:\other\JustSearch.exe'))
            self.assertIsNone(native.app_window(target.app_binary, exclude=[process()]))
        with patch.object(native, 'windows', return_value=[dict(visible, pid=99)]):
            self.assertIsNone(native.app_window(target.app_binary))

    def test_native_keyboard_refuses_foreign_foreground_before_sending(self):
        native = windows.Native(Mock(app_binary=r'C:\app\JustSearch.exe'), Path('unused'))
        with patch.object(native, 'app_window', return_value=None), patch.object(native, 'send') as send:
            with self.assertRaises(Infrastructure):
                native.key('enter')
            send.assert_not_called()
        owned = {'process': process(), 'pid': 50, 'handle': 200, 'focused': True}
        with patch.object(native, 'app_window', return_value=owned), patch.object(native, 'send') as send:
            native.key('escape')
            send.assert_called_once_with(owned, '{ESC}')

    def test_native_com_picker_uses_exact_value_and_refuses_unowned_or_unaccepted_input(self):
        with tempfile.TemporaryDirectory() as temp:
            target=Mock(app_binary=r'C:\app\JustSearch.exe')
            target.owned.return_value=[process()];target.alive.return_value=[process()]
            native=windows.Native(target,Path(temp))
            window={'pid':50,'handle':200,'class':'#32770'}
            client=Mock();module=Mock()
            client.ElementFromHandle.return_value=Mock(CurrentProcessId=50,CurrentNativeWindowHandle=200,CurrentClassName='#32770')
            edit=Mock(CurrentName='',CurrentAutomationId='1152',CurrentClassName='Edit',CurrentControlType=50033,
                      CurrentNativeWindowHandle=201,CurrentProcessId=50,CurrentIsEnabled=True)
            button=Mock(CurrentName='Select Folder',CurrentAutomationId='1',CurrentClassName='Button',CurrentControlType=50033,
                        CurrentNativeWindowHandle=202,CurrentProcessId=50,CurrentIsEnabled=True)
            folder=r'C:\folder+name{one}'
            value=edit.GetCurrentPattern.return_value.QueryInterface.return_value;value.CurrentValue=folder
            invoke=button.GetCurrentPattern.return_value.QueryInterface.return_value
            closed=[False]
            def edit_handle():
                if closed[0]:raise RuntimeError('Picker control retired after submission')
                return 201
            invoke.Invoke.side_effect=lambda:closed.__setitem__(0,True)
            with patch.object(native,'windows',return_value=[dict(window,pid=99),window]), \
                 patch.object(windows,'wait_for',once),patch.object(native,'automation',return_value=(client,module)), \
                 patch.object(native,'descendants',return_value=[edit,button]),patch.object(native,'shot'), \
                 patch.object(type(edit),'CurrentNativeWindowHandle',create=True,new_callable=PropertyMock) as handle:
                handle.side_effect=edit_handle
                result=native.select_folder(folder)
                self.assertTrue(result['invoked']);self.assertEqual(result['value'],folder)
                value.SetValue.assert_called_once_with(folder);invoke.Invoke.assert_called_once()
                self.assertGreaterEqual(client.ElementFromHandle.call_count,2)
                invoke.Invoke.reset_mock();value.CurrentValue='wrong path';closed[0]=False
                with self.assertRaisesRegex(Infrastructure,'did not accept'):native.select_folder(folder)
                invoke.Invoke.assert_not_called()
            with patch.object(native,'windows',return_value=[dict(window,pid=99)]), \
                 patch.object(windows,'wait_for',once),patch.object(native,'automation') as automation:
                with self.assertRaises(ProductFailure):native.select_folder(folder)
            automation.assert_not_called()

    def test_native_com_element_refuses_stale_process_and_reused_window(self):
        native=windows.Native(Mock(),Path('unused'))
        window={'pid':50,'handle':200,'class':'#32770','process':process()}
        with patch.object(native,'automation') as automation:
            native.target.alive.return_value=[]
            with self.assertRaisesRegex(Infrastructure,'process identity'):native.element(window)
            automation.assert_not_called()
            native.target.alive.return_value=[process()]
            client=Mock();automation.return_value=(client,Mock())
            client.ElementFromHandle.return_value=Mock(CurrentProcessId=99,CurrentNativeWindowHandle=200,CurrentClassName='#32770')
            with self.assertRaisesRegex(Infrastructure,'window identity'):native.element(window)

    def test_native_buttons_ignore_disabled_controls_and_reject_foreign_owners(self):
        native=windows.Native(Mock(),Path('unused'))
        window={'pid':50,'handle':200,'class':'#32770','process':process()}
        button=Mock(CurrentAutomationId='1',CurrentClassName='Button',CurrentIsEnabled=True,
                    CurrentIsOffscreen=False,CurrentProcessId=50)
        with patch.object(native,'descendants',return_value=[button]):
            self.assertEqual(native.buttons(window,1),[button])
            button.CurrentIsEnabled=False
            self.assertEqual(native.buttons(window,1),[])
            button.CurrentIsEnabled=True;button.CurrentProcessId=99
            with self.assertRaisesRegex(Infrastructure,'process identity'):native.buttons(window,1)

    def test_search_accepts_windows_canonical_case_and_preserves_linux_case(self):
        import linux
        from corpus import OPEN_FILE
        for platform,name,accepted in [('windows',OPEN_FILE.lower(),True),('linux',OPEN_FILE,True),
                                       ('linux',OPEN_FILE.lower(),False),('windows','other.txt',False)]:
            result={'results':[{'id':name,'fields':{'filename':name}}]}
            run=Mock(platform=platform);run.api.return_value=result
            with patch.object(linux,'wait_for',once):
                if accepted:self.assertEqual(linux.search_api(run),result)
                else:
                    with self.assertRaises(AssertionError):linux.search_api(run)

    def test_open_uses_the_exact_fixture_dom_id_instead_of_the_first_ranked_row(self):
        import linux
        from corpus import OPEN_FILE
        corpus=r'C:\corpus';image=r'C:\Qual\viewer\notepad++.exe'
        fixture=str(Path(corpus)/OPEN_FILE)
        receiver=dict(process(60,image),argv=[image,fixture])
        run=Mock(facts={});run.require.side_effect=lambda key:corpus if key=='corpus' else True
        run.target.roots=[];run.native.windows.return_value=[{'pid':60,'handle':300,'automationId':'GraphicalPowerShellMainWindow'}]
        first={'element-6066-11e4-a52e-4f735466cecf':'unrelated-first'}
        exact={'element-6066-11e4-a52e-4f735466cecf':'fixture-second'}
        action={'element-6066-11e4-a52e-4f735466cecf':'fixture-actions'}
        run.wd.element.return_value=first;run.wd.execute.side_effect=[exact,action];run.wd.text.return_value=OPEN_FILE
        tools=Mock();tools.read_text.return_value=json.dumps({'viewer':image})
        path=lambda p:tools if str(p)=='C:/Qual/tools.json' else Path(p)
        with patch.object(windows,'Path',side_effect=path),patch.object(linux,'search_api',return_value={'results':[OPEN_FILE.lower()]}), \
             patch.object(windows,'default_viewer',return_value={'executable':image}), \
             patch.object(windows,'ps_json',side_effect=[[],[receiver]]),patch.object(windows,'wait_for',once):
            windows.search_open(run)
        run.wd.element.assert_not_called()
        self.assertIn('data-addressable-id',run.wd.execute.call_args_list[0].args[0])
        self.assertEqual(run.wd.execute.call_args_list[0].args[1],[fixture])
        self.assertIn('row-actions',run.wd.execute.call_args_list[1].args[0])
        self.assertEqual(run.wd.execute.call_args_list[1].args[1],[exact])
        run.wd.click_element.assert_called_once_with(action)
        self.assertTrue(run.facts['search'])

    def test_default_viewer_needs_exact_argv_path_image_new_lifetime_and_window(self):
        image = r'C:\Qual\viewer\notepad++.exe'
        fixture = r'C:\corpus\JustSearch-journey-orchid-open.txt'
        viewer = dict(process(60, image), argv=[image, '-multiInst', fixture])
        window = {'pid': 60, 'handle': 300, 'automationId':'GraphicalPowerShellMainWindow'}
        self.assertEqual(windows.viewer_identity([viewer], [window], fixture, image, [])['window'], window)
        for rows, win, before in [([viewer], [], []), ([viewer], [dict(window,automationId='')], []), ([viewer], [window], [viewer]),
            ([dict(viewer, argv=[image, fixture+'.other'])], [window], []),
            ([dict(viewer, exe=r'C:\other\notepad++.exe')], [window], [])]:
            self.assertIsNone(windows.viewer_identity(rows, win, fixture, image, before))

    def test_mcpb_extraction_rejects_windows_drive_and_backslash_escapes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in ('../bad', '/bad', r'..\bad', 'C:bad'):
                bundle = root / 'bad.mcpb'
                with zipfile.ZipFile(bundle, 'w') as z:
                    z.writestr(name, 'malicious')
                with self.assertRaises(Infrastructure):
                    windows.extract_mcpb(bundle, root / 'out')
            bundle = root / 'good.mcpb'
            with zipfile.ZipFile(bundle, 'w') as z:
                z.writestr('server/index.js', 'bridge')
            windows.extract_mcpb(bundle, root / 'out')
            self.assertEqual((root/'out/server/index.js').read_text(), 'bridge')

    def test_picker_confirms_app_dom_then_real_api_never_uia_text_as_pass(self):
        run = Mock(facts={})
        run.require.return_value = r'C:\corpus'
        run.native.select_folder.return_value = {'pid': 50, 'invoked': True}
        run.wd.execute.return_value = True
        run.api.return_value = {'roots': [{'path': r'C:\corpus'}]}
        with patch.object(windows, 'wait_for', once):
            self.assertEqual(windows.add_folder(run), run.api.return_value)
        self.assertIn('folder', run.facts)
        run.facts.clear()
        run.api.return_value = {'roots': []}
        with patch.object(windows, 'wait_for', once), self.assertRaises(ProductFailure):
            windows.add_folder(run)
        self.assertNotIn('folder', run.facts)

    def test_install_exit_code_does_not_hide_missing_or_wrong_installed_version(self):
        run = Mock()
        run.installed_version.return_value = {'version': '0.3.0', 'location': 'C:/app'}
        with self.assertRaisesRegex(ProductFailure, 'expected per-user'):
            windows.verify_install(run, '0.3.2')
        run.installed_version.return_value = None
        with self.assertRaises(ProductFailure):
            windows.verify_install(run, '0.3.2')

    def test_install_runs_exact_selected_bytes_and_keeps_package_identity(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); package = root/'test-setup.exe';package.write_bytes(b'MZcandidate')
            run = Mock(work=str(root), facts={})
            run.target.owned.return_value = []
            run.target.alive.return_value = []
            identity = process(60, str(package))
            run.target.spawn.return_value = identity
            handle = Mock(returncode=0)
            handle.poll.return_value = 0
            run.target.handles = {60: handle}
            with patch.object(windows, 'wait_for', once):
                result = windows.run_installer(run, package)
            self.assertEqual(result['sha256'], windows.digest(package))
            self.assertEqual(run.facts['installerProcesses'], [identity])
            run.target.spawn.assert_called_once_with([str(package), '/S'], str(root)+'/nsis.log')
            handle.returncode = 2
            with patch.object(windows, 'wait_for', once), self.assertRaises(ProductFailure):
                windows.run_installer(run, package)

    def test_windows_state_reads_bookmarks_from_own_web_storage_and_model_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp);(root/'models').mkdir();(root/'models/model.onnx').write_bytes(b'actual model')
            run = Mock(data=str(root))
            bookmark = {'id': 'authored', 'label': 'Library'}
            run.wd.execute.return_value = [bookmark]
            run.api.return_value = {'results': ['JustSearch-journey-orchid-open.txt']}
            with patch.object(linux, 'search_api', return_value={'results': ['fixture']}):
                state = windows.Run.state_snapshot(run)
            self.assertEqual(state['savedFiles']['savedViews'], [bookmark])
            self.assertEqual(len(state['savedFiles']['files']), 1)
            self.assertIn('localStorage.getItem', run.wd.execute.call_args[0][0])

    def test_windows_restart_uses_shared_state_contract_and_storage_dom(self):
        run = Mock(platform='windows', facts={'windowShownAt': 1})
        bookmark = {'id': 'saved', 'label': 'Library', 'surfaceId': 'core.library-surface', 'url': 'justsearch://library'}
        before = {'savedFiles': {'files': {'model': 'sha'}, 'savedViews': [bookmark]}, 'settings': {'ui': {'theme': 'dark'}},
                  'roots': {'path': r'C:\corpus'}, 'search': {'results': ['JustSearch-journey-orchid-open.txt']}}
        run.require.side_effect = lambda k: {'beforeState': before, 'bookmark': bookmark, 'corpus': r'C:\corpus'}[k]
        run.state_snapshot.return_value = before
        with patch.object(linux, 'stored_bookmarks', return_value=[bookmark]), patch.object(linux, 'bookmark_entry', return_value={'label': 'Library'}), patch.object(linux, 'interaction_observation'):
            self.assertEqual(linux.restart(run), before)
        run.start_ui.assert_called_once()
        run.state_snapshot.return_value = dict(before, savedFiles={'files': {'model': 'changed'}, 'savedViews': [bookmark]})
        with self.assertRaises(AssertionError):
            linux.assert_state(run)

    def test_mcp_metadata_proves_version_release_url_checksum_and_both_platforms(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);bundle=root/'justsearch-mcp.mcpb'
            manifest={'version':'0.3.2','compatibility':{'platforms':['linux','win32']}}
            with zipfile.ZipFile(bundle,'w') as z:z.writestr('manifest.json',json.dumps(manifest))
            registry={'version':'0.3.2','packages':[{'identifier':'https://github.com/justsearch-app/justsearch/releases/download/v0.3.2/justsearch-mcp.mcpb','fileSha256':windows.digest(bundle)}]}
            registry_path=root/'server.json';registry_path.write_text(json.dumps(registry))
            run=Mock(args=SimpleNamespace(assets=root,mcpb=bundle.name,registry=registry_path));run.require.return_value='0.3.2'
            self.assertEqual(windows.mcp_metadata(run)['manifest'],manifest)
            registry['packages'][0]['fileSha256']='wrong';registry_path.write_text(json.dumps(registry))
            with self.assertRaises(AssertionError):windows.mcp_metadata(run)
            run.args.registry=None
            with self.assertRaises(Unavailable):windows.mcp_metadata(run)

    def test_reinstall_requires_current_candidate_and_observes_state_after_real_installer(self):
        run=Mock(facts={},args=SimpleNamespace(assets=Path('assets'),package='candidate.exe'))
        run.require.side_effect=lambda k:{'initialVersion':'0.3.2','beforeState':{'saved':'state'}}[k]
        run.installed_version.return_value={'version':'0.3.2'}
        with patch.object(windows,'run_installer',return_value={'exitCode':0}) as install, patch.object(windows,'verify_install'), patch.object(linux,'assert_state',return_value={'retained':True}) as retained:
            self.assertEqual(windows.reinstall(run)['state'],{'retained':True})
            install.assert_called_once_with(run,Path('assets/candidate.exe'))
            retained.assert_called_once_with(run)
        run.installed_version.return_value={'version':'0.3.1'}
        with patch.object(windows,'run_installer') as install,self.assertRaises(Unavailable):windows.reinstall(run)
        install.assert_not_called()

    def test_uninstall_retention_uses_real_model_bytes_not_seeded_files(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'models').mkdir();file=root/'models/model.bin';file.write_bytes(b'production download')
            before=windows.retained_files(root)
            self.assertEqual(before,{'models/model.bin':windows.digest(file)} if os.name!='nt' else {'models\\model.bin':windows.digest(file)})
            file.write_bytes(b'corrupted')
            self.assertNotEqual(before,windows.retained_files(root))

    def test_windows_disk_bytes_remain_measured_when_swap_unavailable(self):
        before={'devices':{'0 C:':100},'writtenBytes':100,'swapWrittenBytes':None}
        after={'devices':{'0 C:':170},'writtenBytes':170,'swapWrittenBytes':None,'swapMeasurement':'unavailable'}
        result=difference(before,after)
        self.assertEqual(result['writtenBytes'],70)
        self.assertIsNone(result['swapWrittenBytes'])

    def test_hosted_unknown_crashes_cannot_yield_pass_or_require_fake_screenshot(self):
        rows=[{'id':'J1','checks':[{'name':'install','status':'passed','evidence':['nsis.json']} ]},
              {'id':'windows-crashes','checks':[{'name':'hosted crash collection unproven','status':'owner-validation','evidence':['windows-crashes.json']}]}]
        report=summarize(rows)
        self.assertEqual(report_exit_code(report),2)
        self.assertEqual(report['ownerValidation'][0]['evidence'],['windows-crashes.json'])
        rows[1]['checks'][0]['status']='failed-product'
        self.assertEqual(report_exit_code(summarize(rows)),1)

    def test_every_windows_applicable_check_is_implemented_and_linux_only_stays_excluded(self):
        definition=json.loads(Path(windows.__file__).with_name('journeys.json').read_text())
        checks={c['id'] for j in definition['journeys'] for c in j['checks'] if 'vm-windows' in c['lanes'] or 'hosted-windows' in c['lanes']}
        self.assertEqual(checks,set(windows.CHECKS))
        for j in definition['journeys']:
            if j['id']=='J7':
                self.assertTrue(all(not any(l.endswith('windows') for l in c['lanes']) for c in j['checks']))
        self.assertIn('Windows GPU',definition['scenarios']['S3'])
        self.assertEqual(definition['scenarios']['S-Windows-update'],'Windows in-app update via descriptor v2 from a VM-local mirror: success relaunches the new version with data kept; a cancelled elevation or failed install resumes the old version and re-offers')


class WindowsUpdateAndCrashTests(unittest.TestCase):
    def test_log_collection_preserves_utf8_and_invalid_byte_diagnostics_on_windows(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);work=root/'work';work.mkdir()
            (work/'normal.log').write_bytes('模型 log '.encode()+b'\xff')
            run=Mock(out=root,work=str(work),data=str(root/'data'),results=[],args=SimpleNamespace(lane='vm-windows'))
            read,write=Path.read_text,Path.write_text
            def windows_read(path,*args,**kwargs):
                kwargs.setdefault('encoding','cp1252');return read(path,*args,**kwargs)
            def windows_write(path,text,*args,**kwargs):
                kwargs.setdefault('encoding','cp1252');return write(path,text,*args,**kwargs)
            with patch.object(Path,'read_text',windows_read),patch.object(Path,'write_text',windows_write):
                windows.Run.finish(run)
            self.assertEqual((root/'normal.log').read_text(encoding='utf-8'),'模型 log \ufffd')
            self.assertFalse(run.results[-1]['checks'])

    def test_update_witness_requires_real_pid_lifetime_staged_path_and_exact_bytes(self):
        import datetime, threading
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);stage=root/'upgrade/staged/attempt/setup.exe';stage.parent.mkdir(parents=True);stage.write_bytes(b'MZexact bytes')
            witness={'stagedPath':str(stage),'stagedSha256':windows.digest(stage),'processId':60,
                     'launchedAtEpochMs':int(datetime.datetime(2026,10,29,9,tzinfo=datetime.timezone.utc).timestamp()*1000),'attemptId':'attempt'}
            path=root/'upgrade/installer-launch-witness.v1.json';path.write_text(json.dumps(witness))
            expected_sha=windows.digest(stage)
            run=Mock(data=str(root));run.require.side_effect=lambda k:{'assets':{'candidate.exe':expected_sha},'updateClickAtEpochMs':witness['launchedAtEpochMs']-1000}[k];run.args.package='candidate.exe'
            run.target.guard=threading.RLock();run.target.roots=[]
            row=process(60,str(stage));run.native.windows.return_value=[{'pid':60,'class':'#32770','handle':200}]
            with patch.object(windows,'ps_json',return_value=[row]):
                result=windows.installer_witness(run)
            self.assertEqual(result['process'],row)
            self.assertEqual(run.target.roots,[row])
            with patch.object(windows,'ps_json',return_value=[dict(row,created='2026-10-29T10:00:00.0000000Z')]),self.assertRaises(Infrastructure):
                windows.installer_witness(run)
            stage.write_bytes(b'MZtampered')
            with self.assertRaises(ProductFailure):windows.installer_witness(run)

    def test_update_offer_refuses_missing_mirror_and_wrong_overlay(self):
        run=Mock(args=SimpleNamespace(predecessor=None,mirror=None,phase='candidate'))
        with self.assertRaises(Unavailable):windows.update_prepare(run)
        run.args.predecessor=Path('parent.exe');run.args.mirror=Path('mirror')
        with self.assertRaises(Infrastructure):windows.update_prepare(run)

    def test_declining_nsis_requires_exit_old_engine_and_reoffer_without_harness_restart(self):
        with tempfile.TemporaryDirectory() as temp:
            run=Mock(data=temp,facts={'ready':True},args=SimpleNamespace())
            run.installed_version.return_value={'version':'0.3.1'}
            run.target.alive.return_value=[]
            installation={'process':process(60,'C:/stage/setup.exe'),'window':{'handle':200},'witness':{'attemptId':'fresh'}}
            with patch.object(windows,'installer_witness',return_value=installation),patch.object(windows,'invoke_nsis',return_value={'invoked':True}) as action,patch.object(windows,'wait_for',once),patch.object(linux,'open_update_offer') as reoffer,patch.object(linux,'assert_state',return_value={'retained':True}):
                result=windows.cancel_update(run)
            self.assertTrue(result['observation']['installerExited'])
            action.assert_called_once_with(run,installation,2)
            reoffer.assert_called_once()
            run.start_ui.assert_not_called()
            run.normal_launch.assert_not_called()
            run.ready.side_effect=ProductFailure('Old Engine never resumed')
            with patch.object(windows,'installer_witness',return_value=installation),patch.object(windows,'invoke_nsis'),patch.object(windows,'wait_for',once),self.assertRaisesRegex(ProductFailure,'never resumed'):
                windows.cancel_update(run)

    def test_success_observes_automatic_relaunch_before_web_storage_restart(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'upgrade').mkdir();(root/'upgrade/installer-launch-witness.v1.json').write_text(json.dumps({'attemptId':'cancelled'}))
            shell=process();java=process(51,r'C:\app\runtime\bin\java.exe');new=process(61,java['exe'],'2026-10-29T09:01:00.0000000Z')
            facts={'updateCancelled':True,'initialVersion':'0.3.2','bundledJava':java['exe'],'ready':True}
            run=Mock(data=temp,facts=facts,binary=shell['exe']);run.require.side_effect=lambda k:facts[k]
            run.installed_version.return_value={'version':'0.3.2'}
            run.target.owned.side_effect=[[shell,java],[new]];run.target.alive.return_value=[];run.api.return_value={'pid':61}
            installation={'process':process(70,'C:/stage/setup.exe'),'witness':{'attemptId':'success'}}
            order=[]
            run.observe_window.side_effect=lambda name:order.append('automatic-window')
            run.start_ui.side_effect=lambda:order.append('storage-restart')
            with patch.object(linux,'open_update_offer'),patch.object(windows,'installer_witness',return_value=installation),patch.object(windows,'complete_nsis',return_value=[{'invoked':True}]),patch.object(windows,'adopt_relaunch'),patch.object(windows,'verify_install'),patch.object(linux,'assert_state',return_value={'retained':True}),patch.object(windows,'wait_for',once):
                result=windows.update_success(run)
            self.assertEqual(order,['automatic-window','storage-restart'])
            self.assertEqual(result['engine'],new)
            self.assertFalse(result['observation']['oldShellAlive'])
            self.assertFalse(result['observation']['oldEngineAlive'])
            self.assertTrue(facts['updated'])

    def test_nsis_navigation_requires_identified_allowed_actions(self):
        run=Mock(facts={});run.target.alive.side_effect=[[process(60)],[]]
        run.native.windows.return_value=[{'pid':60,'class':'#32770','handle':200}]
        installation={'process':process(60)}
        def twice(fn,*a,**k):
            self.assertFalse(fn());return fn()
        run.native.buttons.return_value=[Mock(CurrentName='&Install')]
        with patch.object(windows,'invoke_nsis',return_value={'invoked':True}) as action,patch.object(windows,'wait_for',twice):
            self.assertEqual(windows.complete_nsis(run,installation),[{'invoked':True}])
        action.assert_called_once()
        run.target.alive.side_effect=None;run.target.alive.return_value=[process(60)]
        run.native.buttons.return_value=[Mock(CurrentName='Unexpected action')]
        with patch.object(windows,'wait_for',once),self.assertRaises(Infrastructure):
            windows.complete_nsis(run,installation)

    def test_crash_collection_preserves_product_unknown_before_and_jvm_outcomes(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);logs=root/'crashes';logs.mkdir()
            run=Mock(out=root,data=str(root/'data'),work=str(root/'work'),facts={},binary=str(root/'app/JustSearch.exe'),crash_since_ns=1)
            run.crash_directories.return_value=[logs];run.jvm_directories.return_value=[logs]
            run.target.app_processes={};run.crash_records.return_value=[]
            self.assertEqual(windows.Run.collect_crashes(run)['eventRecords'],0)
            run.crash_records.return_value=[{'exe':str(root/'app/runtime/bin/java.exe')}]
            with self.assertRaises(ProductFailure):windows.Run.collect_crashes(run)
            run.crash_records.return_value=[{'exe':'C:/unrelated/crashed.exe'}]
            with self.assertRaises(OwnerValidation):windows.Run.collect_crashes(run)
            run.crash_records.return_value=[]
            fatal=logs/'hs_err_pid42.log';fatal.write_text('A fatal error has been detected by the Java Runtime Environment')
            with self.assertRaises(ProductFailure):windows.Run.collect_crashes(run)
            run.crash_since_ns=fatal.stat().st_mtime_ns+1
            self.assertEqual(windows.Run.collect_crashes(run)['jvmLogs'],0)

    def test_retained_data_reinstall_calls_uninstaller_and_checks_unmodified_downloads(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);app=root/'app';app.mkdir();uninstaller=app/'uninstall.exe';uninstaller.write_bytes(b'MZuninstall')
            run=Mock(data=str(root/'data'),facts={},args=SimpleNamespace(assets=root,package='candidate.exe'))
            run.require.side_effect=lambda k:{'initialVersion':'0.3.2','beforeState':{'saved':'state'}}[k]
            run.installed_version.side_effect=[{'version':'0.3.2','location':str(app),'uninstaller':'"'+str(uninstaller)+'"'},None]
            with patch.object(windows,'retained_files',return_value={'model':'sha'}),patch.object(windows,'run_installer',return_value={'exitCode':0}) as install,patch.object(windows,'verify_install'),patch.object(linux,'assert_state',return_value={'retained':True}),patch.object(windows,'wait_for',once):
                self.assertEqual(windows.remove_reinstall(run)['state'],{'retained':True})
            self.assertEqual(install.call_count,2)
            self.assertEqual(install.call_args_list[0].args[1],uninstaller)


class WindowsInstallationTests(unittest.TestCase):
    def test_checkpoint_before_ai_still_requires_production_download(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            tools=json.dumps({'driverVersion':'2.0.4','driverSha256':'fixture'})
            original_read=Path.read_text
            def read(path,*args,**kwargs):
                return tools if str(path)=='C:/Qual/tools.json' else original_read(path,*args,**kwargs)
            for ai in (None, {'installedFully':True}):
                work=root/('before-ai' if ai is None else 'after-ai')
                work.mkdir()
                facts={'assets':{'candidate.exe':'sha'}}
                if ai: facts['ai']=ai
                state={'assets':facts['assets'],'lane':'vm-windows','phase':'candidate',
                       'work':str(work),'data':str(root/'data'),'binary':str(root/'app.exe'),'facts':facts}
                checkpoint=root/'checkpoint.json';checkpoint.write_text(json.dumps(state))
                run=Mock(out=work,facts={'assets':facts['assets']},args=SimpleNamespace(
                    resume=checkpoint,lane='vm-windows',phase='candidate'))
                run.target.env={'PATH':''}
                run.crash_directories.return_value=[]
                with patch.object(Path,'read_text',read),patch.dict(os.environ,{'APPDATA':str(root),'LOCALAPPDATA':str(root)}), \
                     patch.object(windows,'powershell',return_value='session'),patch.object(windows,'ps_json',return_value={
                         'folder':r'C:\Qual\crash-dumps','type':1,'count':1024}):
                    windows.Run.setup(run)
                self.assertEqual(run.facts['resumedSetup'], ai is not None)
                run.start_ui.assert_called_once()

    def test_verified_install_uses_unquoted_registry_root_real_shell_jvm_and_jar(self):
        with tempfile.TemporaryDirectory() as temp:
            local=Path(temp);app=local/'JustSearch';java=app/'resources/headless/runtime/bin/javaw.exe';java.parent.mkdir(parents=True)
            java.write_bytes(b'JVM');shell=app/'JustSearch.exe';shell.write_bytes(b'MZshell');jar=app/'resources/headless/ui-headless.jar';jar.write_bytes(b'Engine')
            # The console JVM is also bundled; it cannot identify the GUI Engine.
            java.with_name('java.exe').write_bytes(b'console JVM')
            run=Mock(facts={});run.installed_version.return_value={'version':'0.3.2','location':str(app)}
            with patch.dict(os.environ,{'LOCALAPPDATA':str(local)}),patch.object(windows,'installer_version',return_value='0.3.2'):
                result=windows.verify_install(run,'0.3.2')
                self.assertEqual(result['binarySha256'],windows.digest(shell))
                self.assertEqual(run.facts['bundledJava'],str(java))
                jar.unlink()
                with self.assertRaisesRegex(ProductFailure,'unique bundled JVM and Engine'):windows.verify_install(run,'0.3.2')

    def test_fresh_install_selects_candidate_or_parent_without_seeding_models(self):
        from corpus import generate
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);assets=root/'assets';assets.mkdir();candidate=assets/'candidate.exe';candidate.write_bytes(b'MZcandidate');parent=root/'parent.exe';parent.write_bytes(b'MZparent')
            bundle=assets/'justsearch-mcp.mcpb'
            with zipfile.ZipFile(bundle,'w') as z:z.writestr('manifest.json','{}')
            for phase,expected in [('candidate',candidate),('predecessor',parent)]:
                work=root/phase;work.mkdir()
                run=Mock(work=str(work),facts={},args=SimpleNamespace(assets=assets,package=candidate.name,mcpb=bundle.name,predecessor=parent,phase=phase))
                run.installed_version.return_value=None
                def corpus(path):
                    path.mkdir();(path/'fixture.txt').write_text('original qualification text');return {'fixture.txt':'hash'}
                with patch.object(windows,'installer_version',side_effect=lambda p:'0.3.1' if p==parent else '0.3.2'),patch.object(windows,'run_installer',return_value={'exitCode':0}) as install,patch.object(windows,'verify_install',return_value={'verified':True}),patch('corpus.generate',side_effect=corpus):
                    result=windows.package_install(run)
                install.assert_called_once_with(run,expected)
                self.assertEqual(result['package']['sha256'],windows.digest(expected))
                self.assertTrue(run.facts['installed'])
                self.assertFalse((work/'models').exists())

    def test_first_run_retains_normal_launch_screenshot_as_owner_validation(self):
        run=Mock(facts={})
        run.wd.execute.side_effect=[True,['Get started'],True]
        with patch.object(windows,'wait_for',once),self.assertRaises(OwnerValidation):windows.first_run(run)
        run.normal_launch.assert_called_once()
        run.start_ui.assert_called_once()
        run.native.shot.assert_any_call('J1-desktop-first-run')
        run.wd.click.assert_any_call('button','Get started')

    def test_start_ui_matches_the_app_webview_build_before_launching_driver(self):
        view=dict(process(55,r'C:\EdgeWebView\143.0.12.1\msedgewebview2.exe'),command=r'msedgewebview2.exe --user-data-dir="C:\Users\qual\AppData\Local\io.justsearch.shell\EBWebView"')
        run=Mock(facts={'driverIdentity':{'version':'2.0.4','sha256':'tool-sha'}},binary=r'C:\app\JustSearch.exe',work='C:/work',args=SimpleNamespace(driver='tauri-driver.exe',native_driver='msedgedriver.exe'))
        run.target.owned.return_value=[view]
        run.target.run.return_value=SimpleNamespace(stdout='MSEdgeDriver 143.0.12.1')
        driver=Mock();driver.execute.return_value=True
        with patch.dict(windows.os.environ,LOCALAPPDATA=r'C:\Users\qual\AppData\Local'),patch('socket.socket'),patch.object(windows,'digest',return_value='tool-sha'),patch.object(windows,'WebDriver',return_value=driver),patch.object(windows,'wait_for',once):
            windows.Run.start_ui(run)
        self.assertTrue(run.facts['ui'])
        driver.start.assert_called_once_with(run.binary, webview_options={'userDataFolder':r'C:\Users\qual\AppData\Local\io.justsearch.shell'})
        run.target.owned.return_value=[dict(view,command=r'msedgewebview2.exe --user-data-dir=C:\Temp\scoped_dir\EBWebView')]
        with patch.dict(windows.os.environ,LOCALAPPDATA=r'C:\Users\qual\AppData\Local'),patch('socket.socket'),patch.object(windows,'digest',return_value='tool-sha'),patch.object(windows,'WebDriver',return_value=driver),patch.object(windows,'wait_for',once),self.assertRaisesRegex(Infrastructure,'normal installed app profile'):
            windows.Run.start_ui(run)
        run.target.owned.return_value=[view]
        run.target.run.return_value=SimpleNamespace(stdout='MSEdgeDriver 143.0.12.2')
        run.desktop_spawn.reset_mock()
        with patch('socket.socket'),patch.object(windows,'digest',return_value='tool-sha'),self.assertRaises(Infrastructure):windows.Run.start_ui(run)
        run.desktop_spawn.assert_not_called()
        run.evidence.assert_called_with('webdriver-versions', {'webview2':'143.0.12.1','msedgedriver':'MSEdgeDriver 143.0.12.2'})

    def test_short_parent_lifetime_binds_only_children_born_before_its_recorded_exit(self):
        parent=process(50,r'C:\app\uninstall.exe');ended=dict(parent,exited='2026-10-29T09:00:02.0000000Z')
        child=process(51,r'C:\Temp\uninstall.exe','2026-10-29T09:00:01.0000000Z',50)
        late=process(52,r'C:\other\not-ours.exe','2026-10-29T09:00:03.0000000Z',50)
        target=windows.Target();target.roots=[parent];handle=Mock();handle.poll.return_value=0;target.handles={50:handle}
        with patch.object(windows,'process_times',return_value=ended),patch.object(windows,'ps_json',return_value=[child,late]):
            rows=target.owned()
        self.assertEqual([r['pid'] for r in rows],[51])
        self.assertEqual(rows[0]['parentCreated'],parent['created'])


class WindowsEnvironmentTests(unittest.TestCase):
    def test_driver_receipt_pins_supplied_binary_without_a_nonexistent_version_switch(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'tauri-driver.exe';path.write_bytes(b'qualification tooling')
            receipt={'version':'2.0.4','sha256':windows.digest(path)}
            windows.verify_driver_receipt(path,receipt)
            for invalid in (dict(receipt,version='2.0.3'),dict(receipt,sha256='different bytes')):
                with self.assertRaises(Infrastructure):windows.verify_driver_receipt(path,invalid)

    def test_clean_child_has_only_standard_paths_and_owned_marker_without_ci_secrets(self):
        environment={'Path':r'C:\Windows\System32;C:\Java\bin;C:\Qual\node','SystemRoot':r'C:\Windows',
            'APPDATA':r'C:\Users\qual\AppData\Roaming','JAVA_HOME':'developer JDK','JRE_HOME':'inherited runtime',
            'JUSTSEARCH_MODEL_DIR':'seeded models','CUDA_VISIBLE_DEVICES':'0','TAURI_CONFIG':'override',
            'ACTIONS_RUNTIME_TOKEN':'CI credential','GH_TOKEN':'CI credential','WEBVIEW2_BROWSER_EXECUTABLE_FOLDER':'foreign browser'}
        result=windows.clean_environment(environment,'this-run')
        self.assertEqual(result['PATH'],r'C:\Windows\System32;C:\Qual\node')
        self.assertEqual(result['QUAL_JOURNEY_RUN'],'this-run')
        self.assertEqual(result['OMP_NUM_THREADS'],'8')
        self.assertEqual(set(result),{'PATH','SYSTEMROOT','APPDATA','QUAL_JOURNEY_RUN','OMP_NUM_THREADS','OPENBLAS_NUM_THREADS','RAYON_NUM_THREADS'})


class InstallerBrokerTests(unittest.TestCase):
    def test_broker_needs_nonce_checksum_exit_and_exact_lifetime_before_adopting_process(self):
        import threading
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);exe=root/'candidate.exe';exe.write_bytes(b'MZapproved')
            nonce='a'*32
            reply={'nonce':nonce,'sha256':windows.digest(exe),'exitCode':0,
                   'process':dict(process(60,str(exe)),exited='2026-10-29T09:01:00.0000000Z')}
            run=Mock(facts={});run.target.guard=threading.RLock();run.target.roots=[];run.target.owned.return_value=[]
            response=root/('installer-result-'+nonce+'.json')
            with patch.object(windows,'INSTALL_BUS',root),patch('uuid.uuid4',return_value=SimpleNamespace(hex=nonce)),patch.object(windows,'wait_for',once):
                response.write_text(json.dumps(reply))
                self.assertEqual(windows.broker_installer(run,exe,('/S',))['exitCode'],0)
                self.assertEqual(run.target.roots,[reply['process']])
                (root/'installer-request.json').unlink()
                response.write_text(json.dumps(dict(reply,exitCode=2)))
                with self.assertRaises(ProductFailure):windows.broker_installer(run,exe,('/S',))
                (root/'installer-request.json').unlink()
                response.write_text(json.dumps(dict(reply,sha256='wrong')))
                with self.assertRaises(Infrastructure):windows.broker_installer(run,exe,('/S',))


if __name__ == '__main__':
    unittest.main()
