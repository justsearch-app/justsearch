"""Evidence rules: isolated fake app/DOM/compositor and crash reports; no VM."""
import contextlib
import io
import inspect
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace as NS
import unittest
from unittest.mock import Mock, patch

import linux
import runner
from test_repair2 import immediate
from test_repair4 import DEFINITION, rows
from test_writes import wrapper

BINARY = '/usr/bin/JustSearch'
SHELL = {'pid': 77, 'ticks': '123', 'exe': BINARY}
WINDOW = {'pid': 77, 'title': 'JustSearch', 'focused': True,
          'rect': {'x': 0, 'y': 0, 'width': 800, 'height': 600}}
DESTINATION = {'route': 'core.library-surface', 'heading': 'Library'}


def app_window(native, focused=True):
    # Invoke each candidate's real interface; baseline lacks the mapped-only path.
    if 'binary' not in inspect.signature(native.app_window).parameters:
        return native.app_window()
    return native.app_window(BINARY, focused=focused)


class EvidenceTests(unittest.TestCase):
    def test_pending_owner_never_reports_plain_pass_and_lists_screenshots(self):
        journeys = [{'id': 'J1', 'checks': [{'name': 'first-run', 'status': 'owner-validation',
                     'evidence': ['first-run.png', 'triage.txt']}]}]
        report = runner.summarize(journeys)
        self.assertFalse(report['passed'])
        self.assertEqual(report['status'], 'passed-pending-owner-validation')
        self.assertEqual(report['ownerValidation'], [{'journey': 'J1', 'check': 'first-run',
                                                     'screenshots': ['first-run.png']}])

    def test_pending_does_not_hide_failure_unavailability_or_incomplete_selection(self):
        for status in ('failed-product', 'failed-infrastructure', 'unavailable'):
            report = runner.summarize([{'id':'J7','checks':[
                {'name':'cold','status':'owner-validation'}, {'name':'warm','status':status}]}])
            self.assertFalse(report['passed'])
            self.assertEqual(report['status'], 'failed')
        report = runner.summarize([{'id':'J1','checks':[{'name':'first-run','status':'owner-validation'}]}], False)
        self.assertFalse(report['passed'])
        self.assertEqual(report['status'], 'incomplete')

    def test_combined_owner_screenshot_links_point_into_overlay(self):
        journeys = rows('vm-linux-wayland')
        cold = next(c for j in journeys if j['id']=='J7' for c in j['checks'] if c['name'] in ('deep-link-cold','deep-links'))
        cold.update(status='owner-validation', evidence=['cold.png'])
        with tempfile.TemporaryDirectory() as temp:
            wrapper.combine(NS(session='wayland',mode='fresh',journeys=None), Path(temp), {'candidate':{'journeys':journeys}})
            report = json.loads((Path(temp)/'summary.json').read_text())
        self.assertEqual(report['status'], 'passed-pending-owner-validation')
        self.assertEqual(report['ownerValidation'][0]['screenshots'], ['candidate/run/cold.png'])
        self.assertFalse(report['passed'])

    def first_run(self, text):
        run = Mock(binary=BINARY, work='/tmp/isolated')
        run.wd.execute.side_effect = [True, [], False]
        run.native.screen_text.return_value = text
        return run

    def test_j1_notification_center_replay_cannot_certify_first_run_text(self):
        run = self.first_run('Notification center\nJustSearch\nSearch')
        with patch.object(linux,'wait_for',immediate):
            try:
                linux.first_run(run)
            except Exception as exc:
                self.assertEqual(type(exc).__name__, 'OwnerValidation')
            else:
                self.fail('Foreign notification-center Search text produced a pass')
        self.assertEqual(run.desktop_spawn.call_args.args[0], ['gtk-launch','JustSearch'])
        run.observe_window.assert_called_once()
        run.ready.assert_called_once()

    def test_j1_foreign_overlay_token_never_decides_a_pass(self):
        for text in ('Search overlay-only-token', ''):
            with self.subTest(text=text),patch.object(linux,'wait_for',immediate):
                try:
                    linux.first_run(self.first_run(text))
                except Exception as exc:
                    self.assertEqual(type(exc).__name__, 'OwnerValidation')
                else:self.fail('Screenshot/OCR decided a product pass')

    def test_j1_requires_desktop_window_and_engine_before_owner_validation(self):
        for method in ('observe_window','ready'):
            run=self.first_run('Search')
            getattr(run,method).side_effect=AssertionError('missing '+method)
            with self.subTest(method=method),self.assertRaisesRegex(AssertionError,'missing '+method):
                linux.first_run(run)
            run.start_ui.assert_not_called()

    def test_exact_installed_executable_rejects_owned_basename_impostor(self):
        target=Mock();target.owned.return_value=[dict(SHELL,exe='/tmp/foreign/JustSearch')]
        native=runner.Native(target,Path('/tmp/unused'))
        native.focused_window=Mock(return_value=WINDOW)
        native.windows=Mock(return_value={'focused':WINDOW,'windows':[WINDOW]})
        self.assertIsNone(app_window(native))

    def test_mapped_unfocused_window_is_enough_for_launch_but_not_shortcut(self):
        target=Mock();target.owned.return_value=[SHELL]
        native=runner.Native(target,Path('/tmp/unused'))
        native.focused_window=Mock(return_value={'pid':88})
        native.windows=Mock(return_value={'focused':{'pid':88},'windows':[WINDOW]})
        self.assertEqual(app_window(native,focused=False),WINDOW)
        self.assertIsNone(app_window(native))

    def test_one_gnome_query_observes_mapped_windows_without_modal_or_banner_guards(self):
        target=Mock()
        def guest(source,*args,**kw):
            def evaluate(method,variant,*unused):
                js='''
const app={get_pid:()=>77,get_title:()=>"JustSearch",get_frame_rect:()=>({x:0,y:0,width:800,height:600})};
const Main={modalCount:1,messageTray:{_banner:{visible:true}}};
global.display={focus_window:app};
global.get_window_actors=()=>[{is_mapped:()=>true,meta_window:app},{is_mapped:()=>false,meta_window:app}];
console.log(JSON.stringify([true,JSON.stringify(eval(process.argv[1]))]));
'''
                raw=subprocess.check_output(['node','-e',js,variant[0]],text=True)
                return NS(unpack=lambda:json.loads(raw))
            query=Mock(side_effect=evaluate)
            repository=NS(Gio=NS(DBusProxy=NS(new_for_bus_sync=lambda *a:NS(call_sync=query)),BusType=NS(SESSION=0)),
                          GLib=NS(Variant=lambda kind,value:value))
            output=io.StringIO()
            with patch.dict(sys.modules,{'gi':NS(repository=repository),'gi.repository':repository}), \
                 patch.dict(os.environ,{'WAYLAND_DISPLAY':'wayland-0'},clear=True),contextlib.redirect_stdout(output):
                exec(source,{})
            self.assertEqual(query.call_count,1)
            return json.loads(output.getvalue())
        target.json.side_effect=guest;target.owned.return_value=[SHELL]
        native=runner.Native(target,Path('/tmp/unused'))
        self.assertEqual(app_window(native),WINDOW)
        target.json.assert_called_once()

    def test_shortcut_miss_uses_one_compositor_observation_and_keeps_foreground_evidence(self):
        target=Mock();target.owned.return_value=[SHELL]
        foreign=dict(WINDOW,pid=88,title='Mousepad')
        target.json.side_effect=lambda source,*a,**kw: {'focused':foreign,'windows':[WINDOW,foreign]} if 'global.get_window_actors' in source else foreign
        run=Mock(binary=BINARY,native=runner.Native(target,Path('/tmp/unused')))
        self.assertIsNone(linux.shortcut_receiver(run))
        target.json.assert_called_once()
        self.assertEqual(run.evidence.call_args.args[1]['focusedWindow'],foreign)
        run.wd.execute.assert_not_called()

    def warm_run(self):
        run=Mock(binary=BINARY,work='/tmp/isolated',run_id='isolated')
        run.target.owned.return_value=[SHELL]
        run.native.app_window.return_value=WINDOW
        run.native.matching_text.side_effect=lambda name,*args: None if 'before' in name else 'Library'
        run.wd.execute.side_effect=[True,DESTINATION]
        return run

    def warm(self,run,wait=immediate):
        with patch.object(linux,'require_candidate'),patch.object(linux,'interaction_observation'),patch.object(linux,'wait_for',wait):
            return linux.deep_links(run)

    def test_warm_link_passes_from_session_dom_even_if_ocr_is_empty(self):
        run=self.warm_run();run.native.screen_text.return_value=''
        result=self.warm(run)
        run.start_ui.assert_called_once();run.nav.assert_called_once_with('Search')
        self.assertEqual(result['destination'],DESTINATION)
        run.native.matching_text.assert_not_called();run.native.screen_text.assert_not_called()

    def test_foreign_library_pixels_cannot_pass_when_dom_did_not_navigate(self):
        run=self.warm_run();run.wd.execute.side_effect=[True,None]
        run.native.screen_text.return_value='Library'
        with self.assertRaisesRegex(AssertionError,'DOM destination'):self.warm(run)

    def test_warm_link_rejects_live_forwarder_at_destination_acceptance(self):
        run=self.warm_run()
        run.target.owned.side_effect=[[SHELL],[SHELL,dict(SHELL,pid=88)],[SHELL,dict(SHELL,pid=88)]]
        with patch.object(linux.time,'monotonic',return_value=0),self.assertRaisesRegex(AssertionError,'extra JustSearch'):
            self.warm(run)

    def test_warm_link_allows_forwarder_that_exits_within_grace_before_destination(self):
        run=self.warm_run();run.wd.execute.side_effect=[True,None,DESTINATION]
        run.target.owned.side_effect=[[SHELL],[SHELL,dict(SHELL,pid=88)],[SHELL],[SHELL]]
        def twice(fn,description,**kw):
            if 'DOM destination' in description:
                self.assertIsNone(fn())
            return immediate(fn,description,**kw)
        with patch.object(linux.time,'monotonic',return_value=0):
            self.assertEqual(self.warm(run,twice)['destination'],DESTINATION)

    def test_warm_link_rejects_forwarder_alive_after_grace_before_dom(self):
        run=self.warm_run();run.target.owned.side_effect=[[SHELL],[SHELL,dict(SHELL,pid=88)]]
        with patch.object(linux.time,'monotonic',side_effect=[0,4,4]),self.assertRaisesRegex(AssertionError,'grace period'):
            self.warm(run)

    def test_warm_link_rejects_replaced_receiver_or_window(self):
        for replacement,window in (([dict(SHELL,pid=88)],WINDOW),([SHELL],dict(WINDOW,pid=88))):
            run=self.warm_run();dispatched=False
            def dispatch(args,*unused):
                nonlocal dispatched
                if 'surface' in args[1]:dispatched=True
            run.desktop_spawn.side_effect=dispatch
            run.target.owned.side_effect=lambda: replacement if dispatched else [SHELL]
            run.native.app_window.side_effect=lambda *a,**kw: window if dispatched else WINDOW
            with self.subTest(replacement=replacement,window=window),self.assertRaisesRegex(AssertionError,'receiver PID'):
                self.warm(run)

    def test_cold_link_requires_single_process_and_mapped_window_then_owner_validation(self):
        for shells,window in (([],WINDOW),([SHELL,dict(SHELL,pid=88)],WINDOW),([SHELL],None),([SHELL],WINDOW)):
            run=self.warm_run();run.target.owned.return_value=shells;run.native.app_window.return_value=window
            with self.subTest(shells=shells,window=window),patch.object(linux,'require_candidate'):
                try:linux.CHECKS.get('deep-link-cold',linux.deep_links)(run)
                except Exception as exc:
                    self.assertEqual(type(exc).__name__,'OwnerValidation' if len(shells)==1 and window else 'AssertionError')
                else:self.fail('Cold destination cannot be automatically passed')
                run.start_ui.assert_not_called();run.wd.execute.assert_not_called()


class BookmarkTests(unittest.TestCase):
    bookmark={'id':'saved-library','label':'Library','url':'justsearch://surface/core.library-surface',
              'surfaceId':'core.library-surface','savedAt':123}

    def restart_run(self,stored=True,visible=True):
        run=Mock(binary=BINARY,work='/tmp/isolated',facts={})
        geometry={'button':{'x':10,'y':20},'popup':{}}
        run.require.side_effect=lambda key: self.bookmark if key=='bookmark' else geometry if key=='bookmarkGeometry' else True
        run.native.app_window.return_value=WINDOW
        run.native.app_text.side_effect=lambda name,*a,**kw: 'Settings' if name=='J4-restored-settings' else 'Search'
        run.native.matching_text.side_effect=[None,None,None,'Library']
        def dom(script,args=None):
            if 'localStorage' in script:return [self.bookmark] if stored else []
            if 'const popup=' in script:return {'label':'Library','url':self.bookmark['url']} if visible else None
            return True
        run.wd.execute.side_effect=dom
        return run

    def restart(self,run):
        with patch.object(linux,'assert_state',return_value={'retained':True}), \
             patch.object(linux,'interaction_observation'),patch.object(linux,'wait_for',immediate), \
             patch.object(linux.time,'sleep'):
            return linux.restart(run)

    def test_restart_uses_own_webdriver_dom_and_storage_api(self):
        run=self.restart_run()
        self.assertEqual(self.restart(run),{'retained':True})
        run.start_ui.assert_called_once()
        run.native.matching_text.assert_not_called();run.native.app_text.assert_not_called()
        run.wd.click.assert_any_call('button[aria-label="View bookmarks"]')
        self.assertTrue(any('localStorage.getItem' in c.args[0] for c in run.wd.execute.call_args_list))

    def test_library_pixels_cannot_hide_missing_stored_bookmark(self):
        with self.assertRaisesRegex(AssertionError,'Web Storage API'):
            self.restart(self.restart_run(stored=False))

    def test_library_pixels_cannot_hide_missing_dom_entry_with_stored_bookmark(self):
        with self.assertRaisesRegex(AssertionError,'entry visible in DOM list'):
            self.restart(self.restart_run(visible=False))

    def test_bookmark_dom_requires_exact_visible_list_entry_label_and_url(self):
        run=Mock()
        for label,url,height in (('Library',self.bookmark['url'],20),('Other',self.bookmark['url'],20),
                                 ('Library','justsearch://other',20),('Library',self.bookmark['url'],0)):
            def execute(script,args=None):
                js='''const data=JSON.parse(process.argv[1]);
const row={querySelector:()=>({textContent:data.label}),getAttribute:()=>data.url,getBoundingClientRect:()=>({height:data.height})};
const popup={hasAttribute:()=>true,shadowRoot:{}};
const all=(root,selector)=>selector==='jf-bookmarks-popover'?[popup]:(root===popup.shadowRoot?[row]:[]);
const value=new Function('all','document','args','return (function(){'+process.argv[2]+'}).apply(null,args)')(all,{},JSON.parse(process.argv[3]));
console.log(JSON.stringify(value));'''
                return json.loads(subprocess.check_output(['node','-e',js,json.dumps({'label':label,'url':url,'height':height}),script,json.dumps(args or [])],text=True))
            run.wd.execute.side_effect=execute
            with self.subTest(label=label,url=url,height=height),patch.object(linux,'wait_for',immediate):
                if label=='Library' and url==self.bookmark['url'] and height:
                    self.assertEqual(linux.bookmark_entry(run,self.bookmark),{'label':label,'url':url})
                else:
                    with self.assertRaises(AssertionError):linux.bookmark_entry(run,self.bookmark)

    def test_storage_api_reads_active_profile_persisted_record(self):
        run=Mock()
        state={'version':2,'activeProfileId':'b','profiles':{'a':{'savedViews':[{'id':'wrong'}]},'b':{'savedViews':[self.bookmark]}}}
        def execute(script,args=None):
            js='''const state=process.argv[1];global.localStorage={getItem:()=>state};console.log(JSON.stringify(new Function(process.argv[2])()));'''
            return json.loads(subprocess.check_output(['node','-e',js,json.dumps(state),script],text=True))
        run.wd.execute.side_effect=execute
        self.assertEqual(linux.stored_bookmarks(run),[self.bookmark])


class PollingTests(unittest.TestCase):
    def test_reported_product_failure_cannot_recover_into_pass(self):
        fn=Mock(side_effect=[AssertionError('reported product failed'),True])
        with patch.object(runner.time,'sleep'),self.assertRaisesRegex(AssertionError,'reported product failed'):
            runner.wait_for(fn,'state',seconds=.1)
        fn.assert_called_once()

    def test_product_failure_is_not_overwritten_by_later_transport_error(self):
        fn=Mock(side_effect=[AssertionError('reported failed'),runner.Infrastructure('last SSH')])
        with patch.object(runner.time,'monotonic',side_effect=[0,0,.5,2]),patch.object(runner.time,'sleep'), \
             self.assertRaisesRegex(AssertionError,'reported failed'):
            runner.wait_for(fn,'state',seconds=1)
        fn.assert_called_once()

    def test_transient_api_failure_retains_product_attribution_at_deadline(self):
        transient=getattr(runner,'TransientAPI',AssertionError)
        fn=Mock(side_effect=[transient('Engine unavailable'),runner.Infrastructure('last SSH')])
        with patch.object(runner.time,'monotonic',side_effect=[0,0,.5,2]),patch.object(runner.time,'sleep'), \
             self.assertRaisesRegex(AssertionError,'Engine unavailable'):
            runner.wait_for(fn,'state',seconds=1)

    def test_ready_reported_start_error_cannot_recover(self):
        run=Mock(facts={});run.api.side_effect=[{'knowledgeServerStartError':'start failed'}, {},
                                               {'indexAvailable':True},{'components':{'index':{'state':'READY'}}}]
        with patch.object(runner.time,'sleep'),self.assertRaisesRegex(AssertionError,'start failed'):
            runner.Run.ready(run)
        self.assertNotIn('ready',run.facts)
        self.assertEqual(run.api.call_count,1)

    def ai_run(self,states,operations=None):
        run=Mock(facts={})
        run.wd.text.return_value='Chat is not supported here.'
        run.wd.execute.return_value=['activation'] if operations else []
        statuses=iter(states)
        outcomes=iter([operations])
        def api(path,*args):
            if path=='/api/ai/install/status':return next(statuses,states[-1])
            if path=='/api/operation-history/activation':return next(outcomes,{'state':'succeeded'})
            return {'/api/ai/install/plan-preview':{'downloadProfile':'CPU','components':[
                {'id':'chat','state':'unavailable'},{'id':'embed','state':'available'}]},
                '/api/ai/install/manifest':{},'/api/ai/runtime/status':{'onnxFeatures':[
                    {'id':name,'modelActive':True} for name in ('embed','splade','reranker','citation_scorer')]},
                '/api/operation-history/activation':operations}[path]
        run.api.side_effect=api
        return run

    def test_install_reported_failed_then_completed_stays_failed(self):
        run=self.ai_run([{'state':'failed','error':'bad model'}, {'installedFully':True,'readyCapabilities':['retrieval-core']}])
        with patch.object(linux,'activate_downloaded'),patch.object(linux,'semantic_search',return_value={}),patch.object(linux.time,'sleep'), \
             self.assertRaisesRegex(AssertionError,'Install AI failed'):
            linux.install_ai(run)
        self.assertNotIn('ai',run.facts)

    def test_install_failure_during_activation_cannot_recover(self):
        active={'installedFully':True,'readyCapabilities':['retrieval-core']}
        run=self.ai_run([active,{'state':'failed'},active])
        with patch.object(linux,'activate_downloaded'),patch.object(linux,'semantic_search',return_value={}), \
             patch.object(linux.time,'sleep'),self.assertRaisesRegex(AssertionError,'Install AI failed during activation'):
            linux.install_ai(run)
        self.assertNotIn('ai',run.facts)

    def test_activation_reported_failed_or_cancelled_then_active_stays_failed(self):
        for state in ('failed','cancelled'):
            active={'installedFully':True,'readyCapabilities':['retrieval-core']}
            run=self.ai_run([active,active],{'state':state})
            with self.subTest(state=state),patch.object(linux,'activate_downloaded'),patch.object(linux,'semantic_search',return_value={}), \
                 patch.object(linux.time,'sleep'),self.assertRaisesRegex(AssertionError,'Model activation failed'):
                linux.install_ai(run)
            self.assertNotIn('ai',run.facts)


class CrashTests(unittest.TestCase):
    def test_attribution_is_completed_before_reports_move(self):
        from test_repair5 import Round2Tests
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'crash';root.mkdir();out=Path(temp)/'out';out.mkdir()
            (root/'app.crash').write_text('ExecutablePath: /opt/just-search/JustSearch\nSignal: 6\n')
            original=Round2Tests().crash_ssh(root)
            queried=[]
            def ssh(_,command,**kw):
                if command=='dpkg-query -L just-search':
                    self.assertTrue((root/'app.crash').exists());queried.append(True)
                return original(_,command,**kw)
            def move(_,command):
                self.assertTrue(queried,'Attribution must precede moving reports')
                data=json.loads((out/'guest-crashes.json').read_text())
                self.assertEqual(data[0]['attribution'],'product')
            with patch.object(wrapper.vm,'ssh',ssh),patch.object(wrapper.vm,'guest_root',move), \
                 patch.object(wrapper.vm,'config',return_value={'password':'guest'}), \
                 self.assertRaisesRegex(AssertionError,'Product crash'):
                wrapper.crash_evidence(out)

    def test_failed_path_lookup_keeps_crashes_in_place_for_retry(self):
        from test_repair5 import Round2Tests
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'crash';root.mkdir();out=Path(temp)/'out';out.mkdir()
            (root/'app.crash').write_text('ExecutablePath: /tmp/unknown\n')
            original=Round2Tests().crash_ssh(root)
            def ssh(_,command,**kw):
                return NS(returncode=2,stdout='') if command=='dpkg-query -L just-search' else original(_,command,**kw)
            with patch.object(wrapper.vm,'ssh',ssh),patch.object(wrapper.vm,'guest_root') as move, \
                 patch.object(wrapper.vm,'config',return_value={'password':'guest'}),self.assertRaises(runner.Infrastructure):
                wrapper.crash_evidence(out)
            move.assert_not_called()

    def test_unattributed_crash_is_listed_in_combined_summary_without_product_failure(self):
        crash={'file':'/var/crash/helper.crash','attribution':'unattributed','fields':{'ExecutablePath':'/tmp/helper'}}
        with tempfile.TemporaryDirectory() as temp:
            wrapper.combine(NS(session='wayland',mode='rerun',journeys=['J7']),Path(temp),
                            {'rerun':{'journeys':rows('vm-linux-wayland',{'J7'}),'guestCrashes':[crash]}})
            report=json.loads((Path(temp)/'summary.json').read_text())
        self.assertEqual(report['guestCrashes'],[dict(crash,overlay='rerun')])
        self.assertEqual(report['counts']['failed-product'],0)


if __name__=='__main__':unittest.main()
