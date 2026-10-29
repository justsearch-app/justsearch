"""Round-three counterexamples; isolated files/processes, no VM or desktop.

The compositor query's JavaScript runs under Node with a fake Shell, while the
actual guest Python adapter runs with a fake D-Bus proxy.
"""
import contextlib
import http.client
import io
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tarfile
import tempfile
from types import SimpleNamespace as NS
import unittest
from unittest.mock import Mock, patch

import runner
import test_repair5
from test_writes import wrapper


def guest(source, value=None, **kwargs):
    output = io.StringIO()
    with patch.object(sys, 'argv', ['guest', json.dumps(value)]), contextlib.redirect_stdout(output):
        try:
            exec(source, {})
        except Exception as exc:
            raise runner.Infrastructure('Guest python failed: ' + str(exc)) from exc
    return json.loads(output.getvalue())


class Round3Tests(unittest.TestCase):


    def test_dropped_engine_response_is_product_at_live_and_endpoint(self):
        for failure in (http.client.RemoteDisconnected('closed'), ConnectionResetError('reset'),
                        http.client.IncompleteRead(b'{',99)):
            for endpoint in (False,True):
                with self.subTest(failure=failure,endpoint=endpoint),tempfile.TemporaryDirectory() as temp:
                    run=test_repair5.Round2Tests().api_run(temp)
                    def execute(source,value,**kw):
                        # Patch the actual guest HTTP opener; the live or endpoint
                        # response fails before any JSON body can be decoded.
                        opener=Mock();calls=[]
                        def open_response(*a,**kw):
                            calls.append(1)
                            if not endpoint or len(calls)>1:
                                response=Mock();response.read.side_effect=failure
                                return contextlib.closing(response)
                            return contextlib.closing(io.StringIO(json.dumps({'pid':12345,'instanceId':'instance'})))
                        opener.open.side_effect=open_response
                        # api_run has its own opener; use a guest executor directly.
                        with patch.object(Path,'read_text',return_value=json.dumps({'pid':12345,'instanceId':'instance','head':{'apiPort':1234,'sessionToken':'private'}})), \
                             patch.object(Path,'read_bytes',return_value=b'QUAL_JOURNEY_RUN=isolated-engine\0'), \
                             patch.object(os,'kill'),patch.object(runner.urllib.request,'build_opener',return_value=opener):
                            return guest(source,value)
                    run.target.json=execute
                    with self.assertRaises(AssertionError):run.api('/api/status')

    def test_poll_retries_transient_product_failure(self):
        fn=Mock(side_effect=[runner.TransientAPI('not published'),{'ready':True}])
        with patch.object(runner.time,'monotonic',side_effect=[0,0,.5]),patch.object(runner.time,'sleep'):
            self.assertEqual(runner.wait_for(fn,'poll',seconds=1),{'ready':True})
        self.assertEqual(fn.call_count,2)


    def test_hidden_x11_window_is_not_a_visible_app(self):
        with tempfile.TemporaryDirectory() as temp,patch.dict(os.environ,{},clear=True):
            target=Mock(json=guest);target.owned.return_value=[{'pid':77,'exe':'/usr/bin/JustSearch'}]
            native=runner.Native(target,Path(temp));native.focused_window=Mock(return_value=None)
            def windows(args,**kw):
                return NS(returncode=1 if '--onlyvisible' in args else 0,stdout='' if '--onlyvisible' in args else '123\n')
            with patch.object(subprocess,'check_output',return_value='999'),patch.object(subprocess,'run',side_effect=windows),self.assertRaisesRegex(AssertionError,'no owned, mapped window'):
                native.window_failure('hidden', '/usr/bin/JustSearch')

    def crash(self, root, out, paths):
        def ssh(_,command,**kw):
            command=command.replace('/var/crash',str(root))
            if command.startswith('find '):return NS(stdout=''.join(p.name+'\n' for p in root.glob('*.crash')))
            if command=='dpkg-query -L just-search':return NS(returncode=0,stdout='\n'.join(paths))
            return subprocess.run(shlex.split(command)[4:],stdout=kw['stdout'],stderr=subprocess.PIPE,check=True)
        def move(_,command):subprocess.run(['sh','-c',command.replace('/var/crash',str(root))],check=True)
        with patch.object(wrapper.vm,'ssh',ssh),patch.object(wrapper.vm,'config',return_value={'password':'guest'}), \
             patch.object(wrapper.vm,'guest_root',move):wrapper.crash_evidence(out)

    def test_large_product_core_keeps_product_verdict_and_moves_report(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'crashes';root.mkdir();out=Path(temp)/'out';out.mkdir()
            report=root/'huge.crash'
            with report.open('wb') as stream:
                stream.write(b'ExecutablePath: /usr/lib/JustSearch/runtime/bin/java\nSignal: 6\nCoreDump: base64\n')
                stream.truncate(100_000_001)
            with self.assertRaisesRegex(AssertionError,'Product crash'):
                self.crash(root,out,['/usr/lib/JustSearch/runtime/bin/java'])
            self.assertFalse(report.exists())
            self.assertLess((out/'guest-crashes.tar').stat().st_size,100_000)
            with tarfile.open(out/'guest-crashes.tar') as archive:
                self.assertNotIn(b'CoreDump:',archive.extractfile('huge.crash').read())
            self.assertEqual(json.loads((out/'guest-crashes.json').read_text())[0]['fields']['Signal'],'6')

    def test_crash_attribution_uses_package_or_installed_executable(self):
        for fields,product in (({'ExecutablePath':'/usr/bin/python3','ProcCmdline':'python3 /tmp/justsearch-probe.py'},False),
            ({'ExecutablePath':'/tmp/justsearch-helper'},False),
            ({'ExecutablePath':'/usr/lib/JustSearch/runtime/bin/java'},True),
            ({'ExecutablePath':'/tmp/deleted','Package':'just-search 0.3.1'},True),
            ({'ExecutablePath':'/tmp/deleted','Package':'just-search-helper 1'},False)):
            with self.subTest(fields=fields),tempfile.TemporaryDirectory() as temp:
                root=Path(temp)/'crashes';root.mkdir();out=Path(temp)/'out';out.mkdir()
                (root/'app.crash').write_text(''.join(k+': '+v+'\n' for k,v in fields.items()))
                if product:
                    with self.assertRaises(AssertionError):self.crash(root,out,['/usr/lib/JustSearch/runtime/bin/java'])
                else:self.crash(root,out,['/usr/lib/JustSearch/runtime/bin/java'])
                self.assertFalse((root/'app.crash').exists())


if __name__=='__main__':unittest.main()
