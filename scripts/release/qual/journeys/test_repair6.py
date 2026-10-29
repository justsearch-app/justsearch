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
import re
import shlex
import subprocess
import sys
import tarfile
import tempfile
from types import SimpleNamespace as NS
import unittest
from unittest.mock import Mock, patch

import linux
import runner
from test_repair2 import immediate
import test_repair4
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
    @contextlib.contextmanager
    def shell(self, main):
        def evaluate(method, variant, *args):
            script = variant[0]
            js = '''
const Main=JSON.parse(process.argv[1]);
if(Main.messageTray && Main.messageTray._banner) {
 Main.messageTray._banner.get_paint_visibility=()=>true;
}
global.display={focus_window:{get_pid:()=>77,get_title:()=>"JustSearch",
 get_frame_rect:()=>({x:0,y:0,width:400,height:200})}};
try { console.log(JSON.stringify([true, JSON.stringify(eval(process.argv[2]))])); }
catch(e) { console.log(JSON.stringify([false,String(e)])); }
'''
            result = subprocess.check_output(['node', '-e', js, json.dumps(main), script], text=True)
            return NS(unpack=lambda: json.loads(result))
        proxy = NS(call_sync=evaluate)
        repository = NS(Gio=NS(DBusProxy=NS(new_for_bus_sync=lambda *a: proxy), BusType=NS(SESSION=0)),
                        GLib=NS(Variant=lambda kind, value: value))
        with patch.dict(sys.modules, {'gi': NS(repository=repository), 'gi.repository': repository}), \
             patch.dict(os.environ, {'WAYLAND_DISPLAY':'wayland-0'}, clear=True):
            yield

    def test_c1_shell_modals_reject_capture_even_while_app_has_focus(self):
        from PIL import Image
        check_output = subprocess.check_output
        def output(args, **kwargs):
            return 'external notification text' if args[0] == 'tesseract' else check_output(args, **kwargs)
        for modal in ('notification-center', 'calendar', 'overview', 'run-dialog'):
            with self.subTest(modal=modal), tempfile.TemporaryDirectory() as temp, \
                 self.shell({'modalCount':1, 'messageTray':{'_banner':None}}), \
                 patch.object(subprocess, 'check_output', side_effect=output):
                target = Mock(env={'WAYLAND_DISPLAY':'wayland-0'}, json=guest)
                target.owned.return_value = [{'pid':77,'exe':'/usr/bin/JustSearch'}]
                shot = Path(temp)/'screen.png'; Image.new('RGB',(400,200)).save(shot)
                native = runner.Native(target, Path(temp)); native.shot = Mock(return_value=shot)
                with self.assertRaisesRegex(runner.Infrastructure, 'modal'):
                    native.app_text(modal)
                native.shot.assert_not_called()

    def test_c1_unavailable_modal_field_is_infrastructure(self):
        for main in ({}, {'modalCount':None}, {'modalCount':'0'}):
            with self.subTest(main=main), self.shell(main):
                native = runner.Native(Mock(json=guest), Path('/tmp/unused'))
                with self.assertRaisesRegex(runner.Infrastructure, 'modal observation unavailable'):
                    native.focused_window()

    def test_c1_zero_modal_count_keeps_owned_focus_observable(self):
        with self.shell({'modalCount':0}):
            native = runner.Native(Mock(json=guest), Path('/tmp/unused'))
            self.assertEqual(native.focused_window()['pid'], 77)

    def test_c2_missing_banner_field_fails_closed(self):
        with self.shell({'modalCount':0,'messageTray':{}}):
            native = runner.Native(Mock(env={'WAYLAND_DISPLAY':'wayland-0'},json=guest),Path('/tmp/unused'))
            with self.assertRaises(runner.Infrastructure): native.reject_notification('missing')

    def test_c2_null_banner_is_valid_and_visible_banner_is_infrastructure(self):
        for banner in (None, {'visible':True}):
            with self.subTest(banner=banner), tempfile.TemporaryDirectory() as temp, \
                 self.shell({'modalCount':0,'messageTray':{'_banner':banner}}):
                native=runner.Native(Mock(env={'WAYLAND_DISPLAY':'wayland-0'},json=guest),Path(temp))
                if banner:
                    with self.assertRaises(runner.Infrastructure): native.reject_notification('banner')
                else: native.reject_notification('banner')

    def link_run(self, cold_count=1, warm='same'):
        run=Mock(work='/tmp/isolated-run',run_id='round-three',binary='/usr/bin/JustSearch')
        state={'warm':False}
        shell=lambda pid: {'pid':pid,'exe':run.binary,'ticks':str(pid)}
        def owned():
            if state['warm'] and warm=='grow':return [shell(77),shell(88)]
            if state['warm'] and warm=='replace':return [shell(88)]
            return [shell(77+i) for i in range(cold_count)]
        run.target.owned.side_effect=owned
        run.native.app_window.side_effect=lambda: {'pid':88 if state['warm'] and warm=='replace' else 77,
            'rect':{'x':0,'y':0,'width':1024,'height':700}}
        run.native.matching_text.side_effect=lambda name,*a: None if 'before' in name else ('Library' if 'warm' in name else linux.cold_link_query(run.run_id))
        def dispatch(args,*a):
            if 'surface' in args[1]:state['warm']=True
        run.desktop_spawn.side_effect=dispatch
        return run

    def links(self, run):
        with patch.object(linux,'require_candidate'),patch.object(linux,'interaction_observation'), \
             patch.object(linux,'wait_for',immediate):
            return linux.deep_links(run)

    def test_c3_second_instance_cannot_pass_from_original_foreground(self):
        with self.assertRaisesRegex(AssertionError,'second JustSearch'):
            self.links(self.link_run(warm='grow'))

    def test_c3_replacement_receiver_cannot_pass_from_library_pixels(self):
        with self.assertRaisesRegex(AssertionError,'receiver PID'):
            self.links(self.link_run(warm='replace'))

    def test_c3_instance_growth_during_capture_is_product(self):
        run=self.link_run()
        original=[{'pid':77,'exe':run.binary}]
        run.target.owned.side_effect=[original,original,original,original+[{'pid':88,'exe':run.binary}]]
        with self.assertRaisesRegex(AssertionError,'second JustSearch.*during capture'):
            self.links(run)

    def test_c3_observed_second_instance_cannot_expire_into_a_pass(self):
        run=self.link_run()
        original=[{'pid':77,'exe':run.binary}]
        run.target.owned.side_effect=[original,original,original+[{'pid':88,'exe':run.binary}],original]
        def wait(fn,description,**kwargs):
            if description.startswith('Cold'):
                return immediate(fn,description,**kwargs)
            return runner.wait_for(fn,description,seconds=1)
        with patch.object(linux,'require_candidate'),patch.object(linux,'interaction_observation'), \
             patch.object(linux,'wait_for',side_effect=wait),patch.object(runner.time,'sleep'), \
             patch.object(runner.time,'monotonic',side_effect=[0,0,.5,2]), \
             self.assertRaisesRegex(AssertionError,'second JustSearch'):
            linux.deep_links(run)

    def test_c3_original_receiver_passes_and_before_pid_is_recorded(self):
        run=self.link_run();self.links(run)
        records=[c.args[1] for c in run.evidence.call_args_list if c.args[0]=='J7-deep-link-warm-before']
        self.assertTrue(records,'Before observation must record the focused receiver PID')
        record=records[0]
        self.assertEqual(record['window']['pid'],77)
        self.assertEqual(len(record['uiProcesses']),1)

    def test_c4_cold_dispatch_requires_exactly_one_ui_process(self):
        for count in (0,2):
            with self.subTest(count=count),self.assertRaisesRegex(AssertionError,'exactly one'):
                self.links(self.link_run(cold_count=count))

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
        fn=Mock(side_effect=[AssertionError('not published'),{'ready':True}])
        with patch.object(runner.time,'monotonic',side_effect=[0,0,.5]),patch.object(runner.time,'sleep'):
            self.assertEqual(runner.wait_for(fn,'poll',seconds=1),{'ready':True})
        self.assertEqual(fn.call_count,2)

    def test_poll_reports_last_result_at_deadline(self):
        for results,error,reason in (([AssertionError('first'),AssertionError('latest')],AssertionError,'latest'),
            ([runner.Infrastructure('SSH'),AssertionError('latest')],AssertionError,'latest'),
            ([AssertionError('product'),runner.Infrastructure('latest SSH')],runner.Infrastructure,'latest SSH'),
            ([AssertionError('old'),None],runner.Observation,'last observation: None'),
            ([AssertionError('old'),False],runner.Observation,'last observation: False')):
            with self.subTest(results=results),patch.object(runner.time,'monotonic',side_effect=[0,0,.5,2]), \
                 patch.object(runner.time,'sleep'),self.assertRaisesRegex(error,reason):
                runner.wait_for(Mock(side_effect=results),'poll',seconds=1,error=runner.Observation)

    def test_hidden_x11_window_is_not_a_visible_app(self):
        with tempfile.TemporaryDirectory() as temp,patch.dict(os.environ,{},clear=True):
            target=Mock(json=guest);target.owned.return_value=[{'pid':77,'exe':'/usr/bin/JustSearch'}]
            native=runner.Native(target,Path(temp));native.focused_window=Mock(return_value=None)
            def windows(args,**kw):
                return NS(returncode=1 if '--onlyvisible' in args else 0,stdout='' if '--onlyvisible' in args else '123\n')
            with patch.object(subprocess,'run',side_effect=windows),self.assertRaisesRegex(AssertionError,'no window'):
                native.window_failure('hidden')

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

    def test_bookmark_before_samples_reject_delayed_library_before_click(self):
        run=test_repair4.Repair4Tests().restart_run(lambda psm:'Library')
        matching=run.native.matching_text
        calls=[]
        def text(name,*args):
            if name.startswith('J4-before-bookmarks'):
                calls.append(name);return None if len(calls)==1 else 'Library'
            return matching(name,*args)
        run.native.matching_text=Mock(side_effect=text)
        with patch.object(linux,'assert_state',return_value={}),patch.object(linux,'wait_for',immediate), \
             patch.object(linux,'interaction_observation'),patch.object(linux.time,'sleep'), \
             self.assertRaisesRegex(runner.Infrastructure,'already contains Library'):
            linux.restart(run)
        run.native.click.assert_not_called()

    def test_cold_token_avoids_m_w_and_matches_wrapped_whitespace(self):
        queries=[linux.cold_link_query(str(i)) for i in range(32)]
        self.assertTrue(all(set(q).isdisjoint('mw') for q in queries))
        run=self.link_run();self.links(run)
        cold=run.native.matching_text.call_args_list[0]
        self.assertTrue(re.search(cold.args[1],linux.cold_link_query(run.run_id).replace(' ','\n  ')))


if __name__=='__main__':unittest.main()
