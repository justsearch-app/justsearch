"""Round-two counterexamples. Isolated resources; no display, VM or host installs."""
import contextlib
import io
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import urllib.error

import linux
import runner
from test_repair2 import immediate
import test_repair4
from test_repair4 import DEFINITION
from test_writes import wrapper


class Round2Tests(unittest.TestCase):
    def restart_run(self, before='', after='Library'):
        run = test_repair4.Repair4Tests().restart_run(lambda psm: after)
        original = run.native.app_text.side_effect
        def text(name, *args, psm=6, **kw):
            if name.startswith('J4-before-bookmarks'):
                return before.get(psm, '') if isinstance(before, dict) else before
            return original(name, *args, psm=psm, **kw)
        run.native.app_text.side_effect = text
        return run

    def test_01_before_bookmarks_uses_both_segmentations_and_ignores_case(self):
        for before in ({6: 'Ubrary', 11: 'Library'}, {6: 'library'}):
            with self.subTest(before=before):
                run = self.restart_run(before)
                with patch.object(linux, 'wait_for', immediate), \
                     patch.object(linux, 'assert_state', return_value={'savedFiles': {'savedViews': ['Library']}}), \
                     patch.object(linux, 'interaction_observation'), \
                     self.assertRaisesRegex(runner.Infrastructure, 'already contains Library'):
                    linux.restart(run)
                run.native.click.assert_not_called()

    def link_run(self):
        run = Mock(work='/tmp/isolated-run', run_id='w4a-round-two')
        run.wd.execute.return_value = 'Search'
        run.native.app_text.return_value = ''
        run.native.app_window.return_value = {'rect': {'width': 1024, 'height': 700}}
        return run

    def test_02_warm_link_rejects_already_visible_library(self):
        run = self.link_run()
        run.native.matching_text.side_effect = lambda name, *a: None if name == 'J7-deep-link-before' else ('Library' if 'warm' in name else 'unique cold query')
        with patch.object(linux, 'require_candidate'), patch.object(linux, 'interaction_observation'), \
             patch.object(linux, 'wait_for', immediate), self.assertRaisesRegex(runner.Infrastructure, 'already contains Library'):
            linux.deep_links(run)
        self.assertEqual(run.desktop_spawn.call_count, 1)

    def desktop(self, target, out, wait=Mock(return_value=True)):
        with patch.object(wrapper, 'Target', return_value=target), patch.object(wrapper, 'Native'), \
             patch.object(wrapper, 'crash_evidence'), patch.object(wrapper.vm, 'guest_root'), \
             patch.object(wrapper.vm, 'QMP'), patch.object(wrapper, 'wait_for', wait), patch('screen.key'):
            wrapper.prepare_desktop(SimpleNamespace(session='wayland'), out)

    def test_03_banner_setting_is_applied_and_verified(self):
        for stuck in (False, True):
            with self.subTest(stuck=stuck), tempfile.TemporaryDirectory() as temp:
                target = Mock()
                target.run.side_effect = lambda args, **kw: Mock(stdout='true' if stuck and args[:2] == ['gsettings','get'] and args[-1] == 'show-banners' else 'false')
                if stuck:
                    with self.assertRaisesRegex(runner.Infrastructure, 'banners could not be disabled'):
                        self.desktop(target, Path(temp))
                else:
                    self.desktop(target, Path(temp))
                    target.run.assert_any_call(['gsettings', 'set', 'org.gnome.desktop.notifications', 'show-banners', 'false'])
                    self.assertEqual((Path(temp) / 'notification-banners.txt').read_text(), 'false\n')

    def test_03_visible_compositor_banner_cannot_supply_app_text(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); shot = root / 'screen.png'; Image.new('RGB',(400,200)).save(shot)
            target = Mock(env={'WAYLAND_DISPLAY': 'wayland-0'})
            target.json.return_value = True
            native = runner.Native(target, root)
            native.app_window = Mock(return_value={'pid': 1, 'rect': {'x':0,'y':0,'width':400,'height':200}})
            native.shot = Mock(return_value=shot)
            with patch.object(runner.subprocess, 'check_output', return_value='unique notification token'), \
                 self.assertRaisesRegex(runner.Infrastructure, 'notification'):
                native.matching_text('external', 'unique notification token')

    def api_run(self, temp, failure=None, live=None, endpoint_failure=False):
        run = runner.Run(SimpleNamespace(evidence=temp+'/evidence', vm=None), DEFINITION)
        run.data = temp; run.run_id = 'isolated-engine'
        manifest = {'pid': 12345, 'instanceId': 'instance', 'head': {'apiPort':1234,'sessionToken':'private'}}
        opener = Mock()
        responses = [dict(pid=12345, instanceId='instance') if live is None else live, {'ready': True}]
        def opened(*a, **kw):
            if isinstance(failure, (urllib.error.HTTPError, TimeoutError, urllib.error.URLError)) and (not endpoint_failure or len(responses) == 1): raise failure
            return contextlib.closing(io.StringIO(json.dumps(responses.pop(0))))
        opener.open.side_effect = opened
        def execute(source, value, **kw):
            out = io.StringIO()
            with patch('sys.argv', ['guest', json.dumps(value)]), contextlib.redirect_stdout(out), \
                 patch.object(Path, 'read_text', side_effect=failure if isinstance(failure, FileNotFoundError) else None,
                              return_value=json.dumps(manifest)), \
                 patch.object(Path, 'read_bytes', return_value=b'QUAL_JOURNEY_RUN=isolated-engine\0' if failure != 'ownership' else b'other\0'), \
                 patch.object(os, 'kill', side_effect=failure if isinstance(failure, ProcessLookupError) else None), \
                 patch.object(runner.urllib.request, 'build_opener', return_value=opener):
                try:
                    exec(source, {})
                except Exception as exc:
                    raise runner.Infrastructure('Guest python exited 1: '+str(exc)) from exc
            return json.loads(out.getvalue())
        run.target.json = execute
        return run

    def test_04_engine_failures_cross_guest_boundary_as_product(self):
        for failure, live in ((FileNotFoundError('runtime manifest'),None), (ProcessLookupError('dead Engine'),None),
                              ('ownership',None), (None,{'pid':999,'instanceId':'other'}),
                              (urllib.error.HTTPError('local',404,'missing',{},None),None),
                              (urllib.error.HTTPError('local',503,'failed',{},None),None)):
            with self.subTest(failure=failure, live=live), tempfile.TemporaryDirectory() as temp:
                for endpoint_failure in (False, True):
                    run_root = Path(temp)/str(endpoint_failure); run_root.mkdir()
                    run = self.api_run(str(run_root), failure, live, endpoint_failure)
                    with self.assertRaises(AssertionError): run.api('/api/status')

    def test_04_ssh_255_and_timeout_remain_infrastructure(self):
        target = runner.Target()
        target.vm = Mock(); target.name = 'isolated'
        target.vm.ssh.return_value = SimpleNamespace(returncode=255, stdout='', stderr='SSH transport')
        with self.assertRaises(runner.Infrastructure): target.run(['python3'])
        target.vm.ssh.side_effect = subprocess.TimeoutExpired('ssh',1)
        with self.assertRaises(subprocess.TimeoutExpired): target.run(['python3'])

    def test_04_guest_http_timeout_stays_infrastructure(self):
        for failure in (TimeoutError('HTTP timed out'), urllib.error.URLError(TimeoutError('HTTP timed out'))):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temp:
                run = self.api_run(temp, failure)
                with self.assertRaises(runner.Infrastructure): run.api('/api/status')

    def test_04_valid_engine_response_is_unwrapped(self):
        with tempfile.TemporaryDirectory() as temp:
            self.assertEqual(self.api_run(temp).api('/api/status'), {'ready': True})

    def test_startup_wait_retries_product_api_failure_until_engine_publishes_manifest(self):
        run = Mock(facts={})
        run.api.side_effect = [AssertionError('manifest missing'), {'indexAvailable': True},
                               {'components': {'index': {'state': 'READY'}}}]
        with patch.object(runner.time, 'monotonic', side_effect=[0, 0, 1]), patch.object(runner.time, 'sleep'):
            runner.Run.ready(run)
        self.assertTrue(run.facts['ready'])

    def test_startup_wait_persistent_product_api_failure_still_fails_product(self):
        run = Mock(facts={}); run.api.side_effect = AssertionError('manifest missing')
        with patch.object(runner.time, 'monotonic', side_effect=[0, 0, 241]), patch.object(runner.time, 'sleep'), \
             self.assertRaisesRegex(AssertionError, 'manifest missing'):
            runner.Run.ready(run)

    def test_05_existing_unfocused_window_is_infrastructure_in_launch_and_first_run(self):
        for entry in ('launch','first-run'):
            with self.subTest(entry=entry), tempfile.TemporaryDirectory() as temp:
                target = Mock(env={}); target.owned.return_value = [{'pid':77,'exe':'/usr/bin/JustSearch'}]
                target.json.return_value = [{'pid':77,'title':'JustSearch'}]
                native = runner.Native(target, Path(temp)); native.app_window = Mock(return_value=None)
                native.focused_window = Mock(return_value={'pid':88,'title':'Mousepad'})
                run = Mock(native=native, binary='/usr/bin/JustSearch')
                with patch.object(runner, 'wait_for', immediate), patch.object(linux, 'wait_for', immediate), \
                     self.assertRaisesRegex(runner.Infrastructure, 'not focused'):
                    runner.Run.observe_window(run,'probe') if entry == 'launch' else linux.first_run(run)
                record = json.loads(next(Path(temp).glob('*window-miss.json')).read_text())
                self.assertEqual(record['focused']['title'],'Mousepad')
                self.assertEqual(record['ownedPids'], [77])

    def test_05_no_owned_window_remains_product(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Mock(env={}); target.owned.return_value = []; target.json.return_value = []
            native = runner.Native(target, Path(temp)); native.focused_window = Mock(return_value=None)
            run = Mock(native=native)
            native.app_window = Mock(return_value=None)
            with patch.object(runner,'wait_for',immediate), self.assertRaises(AssertionError):
                runner.Run.observe_window(run,'none')

    def test_06_persistent_bookmark_miss_with_stored_bookmark_is_product(self):
        run = self.restart_run(after='Ubrary')
        with patch.object(linux, 'wait_for', immediate), patch.object(linux, 'interaction_observation'), \
             patch.object(linux, 'assert_state', return_value={'savedFiles': {'savedViews': ['Library']}}), \
             self.assertRaises(AssertionError): linux.restart(run)

    def test_06_uncorroborated_ocr_miss_has_observation_tag_in_summary(self):
        with tempfile.TemporaryDirectory() as temp:
            run = runner.Run(SimpleNamespace(evidence=temp+'/run', vm=None, lane='vm-linux-wayland'), DEFINITION)
            run.work = '/tmp/isolated'; run.facts['folder'] = True
            run.native = Mock(); run.native.matching_text.return_value = None
            run.stop_app = Mock(); run.desktop_spawn = Mock(); run.observe_window = Mock(); run.ready = Mock()
            # Baseline also needs its obsolete before-read to succeed.
            run.start_ui = Mock(); run.nav = Mock(); run.wd = Mock(); run.wd.execute.return_value = 'Search'
            journey = {'id':'J7','checks':[]}; run.results.append(journey)
            with patch.object(linux,'require_candidate'), patch.object(linux,'wait_for',immediate):
                run.check(journey,'deep-links',lambda: linux.deep_links(run))
            row = json.loads((run.out/'summary.json').read_text())['journeys'][0]['checks'][0]
            self.assertEqual(row['status'], 'failed-infrastructure')
            self.assertEqual(row.get('reasonTag'), 'observation')

    def test_07_cold_query_uses_distinct_words_derived_from_run_id(self):
        queries = []
        for run_id in ('w4a-123', 'w4a-124'):
            run = self.link_run(); run.run_id = run_id
            run.native.matching_text.side_effect = lambda name, *a: None if 'before' in name else 'query'
            with patch.object(linux,'require_candidate'), patch.object(linux,'interaction_observation'), patch.object(linux,'wait_for',immediate):
                linux.deep_links(run)
            from urllib.parse import parse_qs, urlparse
            query = parse_qs(urlparse(run.desktop_spawn.call_args_list[0].args[0][1]).query)['q'][0]
            self.assertRegex(query, r'^[a-z]+(?: [a-z]+)+$')
            self.assertTrue(set(query).isdisjoint('01bl6ec'))
            queries.append(query)
        self.assertNotEqual(*queries)

    def test_08_cold_query_never_typed_or_launched_with_webdriver_before_link(self):
        run = self.link_run(); events = []
        run.stop_app.side_effect = lambda: events.append('stop')
        run.desktop_spawn.side_effect = lambda *a: events.append('dispatch')
        run.native.matching_text.side_effect = lambda name, *a: None if 'before' in name else 'query'
        with patch.object(linux,'require_candidate'), patch.object(linux,'interaction_observation'), patch.object(linux,'wait_for',immediate):
            linux.deep_links(run)
        run.start_ui.assert_not_called(); run.wd.keys.assert_not_called(); run.native.type.assert_not_called()
        self.assertEqual(events[:2], ['stop','dispatch'])

    def test_09_retry_regression_has_no_tesseract_dependency(self):
        with patch.object(subprocess,'check_output',side_effect=AssertionError('test must stub OCR')):
            test_repair4.Repair4Tests().test_real_predecessor_bookmark_fixture_uses_alternate_ocr_on_miss()

    def test_10_self_hosted_action_is_rejected_before_tools(self):
        for environment in ('self-hosted',''):
            with self.subTest(environment=environment), patch.dict(os.environ,{'GITHUB_ACTIONS':'true','RUNNER_ENVIRONMENT':environment},clear=True), \
                 patch.object(Path,'is_file',return_value=False), self.assertRaises(runner.Infrastructure):
                runner.require_disposable_host()
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'true','RUNNER_ENVIRONMENT':'github-hosted'},clear=True):
            runner.require_disposable_host()

    def crash_ssh(self, root):
        def ssh(_, command, **kw):
            command = command.replace('/var/crash',str(root))
            if command.startswith('find '):
                return SimpleNamespace(stdout=''.join(p.name+'\n' for p in root.glob('*.crash')))
            args = shlex.split(command)[4:]  # sudo -S -p ''
            return subprocess.run(args, stdout=kw['stdout'], stderr=subprocess.PIPE, check=True)
        return ssh

    def test_11_recorded_crashes_are_moved_before_next_overlay(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)/'crash'; root.mkdir(); out=Path(temp)/'evidence'; out.mkdir()
            (root/'app.crash').write_text('ExecutablePath: /opt/just-search/JustSearch\nSignal: 5\n')
            def move(_, command):
                subprocess.run(['sh','-c',command.replace('/var/crash',str(root))],check=True)
            with patch.object(wrapper.vm,'ssh',self.crash_ssh(root)), patch.object(wrapper.vm,'config',return_value={'password':'guest'}), \
                 patch.object(wrapper.vm,'guest_root',move):
                with self.assertRaises(AssertionError): wrapper.crash_evidence(out)
                self.assertFalse((root/'app.crash').exists())
                wrapper.crash_evidence(out)
            self.assertEqual(json.loads((out/'guest-crashes.json').read_text())[0]['fields']['Signal'],'5')
            self.assertTrue((out/'guest-crashes.tar').is_file())

    def test_11_crash_copy_is_capped_before_transfer(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'crash'; root.mkdir(); out=Path(temp)/'evidence'; out.mkdir()
            with (root/'huge.crash').open('wb') as stream: stream.truncate(100_000_001)
            with patch.object(wrapper.vm,'ssh',self.crash_ssh(root)), patch.object(wrapper.vm,'config',return_value={'password':'guest'}), \
                 patch.object(wrapper.vm,'guest_root'), self.assertRaises(subprocess.CalledProcessError):
                wrapper.crash_evidence(out)
            self.assertEqual((out/'guest-crashes.tar').stat().st_size,0)

    def test_12_desktop_preparation_does_not_accept_arbitrary_ocr(self):
        descriptions = []
        def wait(fn, description, **kw):
            descriptions.append(description); return True
        with tempfile.TemporaryDirectory() as temp:
            target=Mock(); target.run.return_value.stdout='false'
            self.desktop(target,Path(temp),wait)
        self.assertNotIn('Logged-in desktop is visible',descriptions)
        self.assertIn('GNOME command dialog',descriptions)


if __name__ == '__main__': unittest.main()
