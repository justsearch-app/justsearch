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

import runner
from test_repair2 import immediate
from test_repair4 import DEFINITION
from test_writes import wrapper


class Round2Tests(unittest.TestCase):


    def desktop(self, target, out, wait=Mock(return_value=True)):
        with patch.object(wrapper, 'Target', return_value=target), patch.object(wrapper, 'Native'), \
             patch.object(wrapper, 'crash_evidence'), patch.object(wrapper.vm, 'guest_root'), \
             patch.object(wrapper.vm, 'QMP'), patch.object(wrapper, 'wait_for', wait), patch('screen.key'):
            wrapper.prepare_desktop(SimpleNamespace(session='wayland'), out)


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
        run.api.side_effect = [runner.TransientAPI('manifest missing'), {'indexAvailable': True},
                               {'components': {'index': {'state': 'READY'}}}]
        with patch.object(runner.time, 'monotonic', side_effect=[0, 0, 1]), patch.object(runner.time, 'sleep'):
            runner.Run.ready(run)
        self.assertTrue(run.facts['ready'])

    def test_startup_wait_persistent_product_api_failure_still_fails_product(self):
        run = Mock(facts={}); run.api.side_effect = runner.TransientAPI('manifest missing')
        with patch.object(runner.time, 'monotonic', side_effect=[0, 0, 241]), patch.object(runner.time, 'sleep'), \
             self.assertRaisesRegex(AssertionError, 'manifest missing'):
            runner.Run.ready(run)


    def test_05_no_owned_window_remains_product(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Mock(env={}); target.owned.return_value = []; target.json.return_value = {'focused': None, 'windows': []}
            native = runner.Native(target, Path(temp)); native.focused_window = Mock(return_value=None)
            run = Mock(native=native)
            native.app_window = Mock(return_value=None)
            with patch.object(runner,'wait_for',immediate), self.assertRaises(AssertionError):
                runner.Run.observe_window(run,'none')


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
            if command == 'dpkg-query -L just-search':
                return SimpleNamespace(returncode=0, stdout='/opt/just-search/JustSearch\n')
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

    def test_11_crash_copy_contains_bounded_headers_even_for_large_core(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'crash'; root.mkdir(); out=Path(temp)/'evidence'; out.mkdir()
            with (root/'huge.crash').open('wb') as stream:
                stream.write(b'ExecutablePath: /usr/bin/desktop-helper\nCoreDump: base64\n')
                stream.truncate(100_000_001)
            with patch.object(wrapper.vm,'ssh',self.crash_ssh(root)), patch.object(wrapper.vm,'config',return_value={'password':'guest'}), \
                 patch.object(wrapper.vm,'guest_root') as move:
                wrapper.crash_evidence(out)
            move.assert_called_once()
            self.assertLess((out/'guest-crashes.tar').stat().st_size,100_000)
            self.assertEqual(json.loads((out/'guest-crashes.json').read_text())[0]['fields'],
                             {'ExecutablePath':'/usr/bin/desktop-helper'})

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
