"""Journey control fixtures for the UI at ab9271581f, without an app or VM."""
import copy
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace as NS
import unittest
from unittest.mock import Mock, patch

import gpu
import linux
import runner
import windows
import test_redesign
from test_repair2 import immediate


class EndOfControlProbe(Exception):
    """The control under test was reached; later journey work is out of scope."""


def dom(script, fixture, args=None):
    js = """const fixture=JSON.parse(process.argv[1]);
const segment={textContent:'Library',getBoundingClientRect:()=>({width:fixture.width??100,height:fixture.height??20})};
const card={hidden:false,shadowRoot:{}};
const visible=e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0};
const all=(root,selector)=>{
 if(root===card.shadowRoot)return [];
 if(selector==='jf-shell')return [{activeId:fixture.active===false?'core.search-v3-surface':'core.library-surface'}];
 if(selector==='jf-walkthrough-card')return [card];
 if(selector==='button')return [];
 if(selector==='button.segment[data-surface-id="core.library-surface"][aria-current="page"]')return fixture.current===false?[]:[segment];
 if(selector==='button.area-trigger[aria-label="Library, choose area"]')return fixture.narrow?[segment]:[];
 return [];
};
const value=new Function('all','document','args','visible','return (function(){'+process.argv[2]+'}).apply(null,args)')(all,{},JSON.parse(process.argv[3]),visible);
console.log(JSON.stringify(value));"""
    return json.loads(subprocess.check_output(['node', '-e', js, json.dumps(fixture), script,
                                               json.dumps(args or [])], text=True))


RULE = 'justsearch-journey-skip-1.tmp'


def authored_run():
    state = {'savedFiles': {'files': {'models/embed.onnx': 'sha'}},
             'settings': {'ui': {'theme': 'dark', 'excludePatterns': [RULE],
                                 'indexingPause': {'schedule': dict(linux.READING_HOURS)}}},
             'roots': ['/corpus'], 'search': {'results': [linux.OPEN_FILE]}}
    run = Mock(args=NS(phase='candidate'), facts={'skipRule': RULE, 'readingHours': linux.READING_HOURS,
                                                 'beforeState': state, 'corpus': '/corpus'})
    run.require.side_effect = lambda key: run.facts[key]
    run.current_state = state
    return run


class CurrentUIControlsTests(unittest.TestCase):
    def test_h3_library_pickers_stage_and_confirm_the_exact_native_path(self):
        # LibrarySurface.ts:2992; FolderAddFlow.ts:54,155. Search submits directly.
        for adapter in (linux, windows):
            for label in ('Add a folder', 'Add folder', 'Add Folder'):
                run = Mock(facts={})
                run.require.side_effect = lambda key: '/corpus' if key == 'corpus' else True
                run.api.return_value = ['/corpus']
                run.native.picker_visible.side_effect = [True, False]
                state = []
                button = {'element': 'library-picker'}
                def picker_label(selector):
                    self.assertIn(f'button[aria-label="{label}"]', selector.split(','))
                def click(selector):
                    if not state:  # Baseline searches globally for the old label.
                        picker_label(selector)
                        state.append('picker')
                    else:
                        self.assertEqual((selector, state), ('button[aria-label="Add"]', ['picker', 'exact path']))
                def click_element(element):
                    self.assertEqual(element, button)
                    state.append('picker')
                def execute(script, args):
                    if 'jf-library-surface' in script:
                        run.nav.assert_called_once_with('Library')
                        self.assertIn('all(library.shadowRoot', script)
                        picker_label(args[0])
                        return button
                    self.assertIn('input[aria-label="Folder path"]', script)
                    self.assertEqual(args, ['/corpus'])
                    self.assertEqual(state, ['picker'])
                    if adapter is windows:
                        run.native.select_folder.assert_called_once_with('/corpus')
                    else:
                        run.native.type.assert_called_once_with('/corpus')
                    state.append('exact path')
                    return True
                run.wd.click.side_effect = click
                run.wd.click_element.side_effect = click_element
                run.wd.execute.side_effect = execute
                with self.subTest(platform=adapter.__name__, label=label), \
                        patch.object(linux, 'wait_for', immediate), patch.object(adapter, 'wait_for', immediate), \
                        patch.object(linux.time, 'sleep'):
                    self.assertEqual(adapter.add_folder(run), ['/corpus'])

    def test_h2_cpu_ai_reaches_install_control_with_dynamic_size_on_both_platforms(self):
        for adapter in (linux, windows):
            run = Mock(facts={}, args=NS(resume=None))
            def click(selector):
                self.assertEqual(selector, 'button[aria-label^="Install AI"]',
                                 'Install AI · 2 GB must match the control prefix')
                raise EndOfControlProbe()
            run.wd.click.side_effect = click
            with self.subTest(platform=adapter.__name__), self.assertRaises(EndOfControlProbe):
                adapter.install_ai(run)

    def test_h3_warm_link_requires_active_shell_and_visible_current_library_segment(self):
        helper = test_redesign.EvidenceTests()
        for fixture, accepts in (({}, True), ({'current': False, 'narrow': True}, True),
                                 ({'active': False}, False), ({'current': False}, False),
                                 ({'height': 0}, False), ({'width': 0}, False)):
            run = helper.warm_run()
            run.wd.execute.side_effect = lambda script, args=None: dom(script, fixture if 'const shell=' in script else {'active': False}, args)
            with self.subTest(fixture=fixture):
                if accepts:
                    self.assertEqual(helper.warm(run)['destination'],
                                     {'route': 'core.library-surface', 'segment': 'Library'})
                else:
                    with self.assertRaisesRegex(AssertionError, 'DOM destination'):
                        helper.warm(run)

    def test_h8_gpu_uses_the_overview_switch_and_waits_for_intent(self):
        # BrainSurface.ts:2044,2192,3525. The developer-only Start answers is absent.
        run = Mock(facts={})
        def click(selector):
            self.assertEqual(selector, 'button[role="switch"][aria-label="Use Answer questions"][aria-checked="false"][aria-disabled="false"]')
        run.wd.click.side_effect = click
        run.api.side_effect = [{'inference': {'chatEnabledSpec': enabled}} for enabled in (False, True)]
        def poll(probe, *_args, **_kwargs):
            self.assertFalse(probe())
            self.assertTrue(probe())
        with patch.object(gpu, 'wait_for', poll), patch.object(gpu, 'wait_chat_ready', return_value={'ready': True}) as ready:
            self.assertEqual(gpu.start_chat(run), {'ready': True})
        self.assertTrue(run.facts['chatStart']['acknowledged'])
        self.assertEqual(run.evidence.call_args.args[1]['control'], 'Use Answer questions')
        ready.assert_called_once_with(run, 'J3')

    def test_h6_empty_legacy_walkthrough_host_never_causes_missing_dismiss_click(self):
        for adapter in (linux, windows, gpu):
            run = Mock(binary='/private/JustSearch', work='/private', facts={})
            run.wd.execute.side_effect = lambda script, args=None: dom(script, {}, args)
            run.wd.click.side_effect = AssertionError('The empty legacy walkthrough has no Dismiss button')
            with self.subTest(platform=adapter.__name__), patch.object(adapter, 'wait_for', immediate):
                if adapter is gpu:
                    self.assertTrue(adapter.first_run(run)['mountedDOM'])
                else:
                    with self.assertRaises(runner.OwnerValidation):
                        adapter.first_run(run)
            run.wd.click.assert_not_called()

    def test_h4_search_mode_uses_the_rendered_control_and_still_submits(self):
        # Sv3Composer.ts:1497,1660,1849,1978; sv3-run.ts:765.
        trigger = 'button[data-testid="sv3-composer-tier"]'
        direct = '[data-testid="sv3-composer-tier"] button[data-tier="search"]'
        for adapter in (linux, windows):
            for layout, clicks in (('search-only', []), ('search-ask', [direct]),
                                   ('menu', [trigger, 'button[data-tier="search"]'])):
                run = Mock()
                run.require.return_value = '/corpus'
                run.wd.find_visible.side_effect = lambda selector: (
                    {'element': 'mode'} if selector == {'search-ask': direct, 'menu': trigger}.get(layout) else None)
                def click(selector):
                    self.assertEqual(selector, clicks[run.wd.click.call_count - 1] if clicks else None)
                run.wd.click.side_effect = click
                run.wd.keys.side_effect = EndOfControlProbe
                with self.subTest(platform=adapter.__name__, layout=layout), patch.object(linux, 'search_api'), \
                        patch.object(windows.Path, 'read_text', return_value=json.dumps({'viewer': 'notepad.exe'})), \
                        patch.object(windows, 'default_viewer', return_value={'executable': 'notepad.exe'}), \
                        patch.object(windows, 'ps_json', return_value=[]):
                    with self.assertRaises(EndOfControlProbe):
                        adapter.search_open(run)
                    self.assertEqual([c.args[0] for c in run.wd.click.call_args_list], clicks)
                    run.wd.keys.assert_called_once_with('textarea[data-testid="sv3-composer-input"]', linux.QUERY + '\ue007')


class NavigationTests(unittest.TestCase):
    key = 'element-6066-11e4-a52e-4f735466cecf'

    def driver(self, *, wide=False, menu=None, areas=True, legacy=False):
        # TopBar.ts:163–185,268–301,327–345. Hidden segments stay mounted.
        wd = runner.WebDriver(Mock(), 4444)
        wd.session = 'owned'
        state = {'menu': menu, 'selected': None}
        def execute(script, args=None):
            if not args:
                self.assertIn('button[data-surface-id]', script)
                return True  # The baseline probe sees the mounted, hidden segments.
            selector = args[0]
            controls = {
                'button[data-surface-id="core.search-v3-surface"]': 'area' if wide and areas else None,
                'button.area-trigger': 'areas' if not wide and areas else None,
                'button.area-trigger:not([aria-expanded="true"])': 'areas' if not wide and areas and state['menu'] != 'areas' else None,
                'button[data-surface-id="core.library-surface"]': 'library' if wide and areas else None,
                'button[data-area-id="core.library-surface"]': 'library' if state['menu'] == 'areas' else None,
                'button[aria-label="Menu"]:not([aria-expanded="true"])': 'more' if state['menu'] != 'more' else None,
                'button[data-surface-id="core.settings-surface"]': 'settings' if legacy or state['menu'] == 'more' else None,
                'button[data-surface-id="core.settings-surface"]:not([role="menuitem"])': 'settings' if legacy else None,
                'button[data-surface-id="core.settings-surface"][role="menuitem"]': 'settings' if state['menu'] == 'more' else None,
            }
            item = next((controls.get(part) for part in selector.split(',') if controls.get(part)), None)
            return {self.key: item} if item else None
        def choose(item):
            if item in ('areas', 'more'):
                state['menu'] = item
            else:
                state.update(selected=item, menu=None)
        wd.execute = Mock(side_effect=execute)
        wd.call = Mock(side_effect=lambda path, body: choose(path.split('/')[-2]))
        wd.click_element = Mock(side_effect=lambda element: choose(element[self.key]))
        return wd, state

    def test_h1_settings_mounts_after_menu_and_legacy_direct_button_still_works(self):
        for legacy in (False, True):
            wd, state = self.driver(legacy=legacy)
            run = Mock(wd=wd)
            with self.subTest(legacy=legacy), patch.object(runner, 'wait_for', immediate):
                runner.Run.nav(run, 'Settings')
                self.assertEqual(state['selected'], 'settings')
                if not legacy:
                    wd.call.assert_any_call('/session/owned/element/more/click', {})

    def test_h2_area_navigation_uses_visible_segments_or_area_menu(self):
        for wide, menu in ((True, None), (False, None), (False, 'areas')):
            wd, state = self.driver(wide=wide, menu=menu)
            with self.subTest(wide=wide, menu=menu), patch.object(runner, 'wait_for', immediate):
                runner.Run.nav(Mock(wd=wd), 'Library')
                self.assertEqual(state['selected'], 'library')

    def test_h2_startup_requires_a_visible_area_control(self):
        for wide, areas in ((True, True), (False, True), (False, False)):
            wd, _ = self.driver(wide=wide, areas=areas)
            wd.start = Mock()
            run = Mock(facts={}, args=NS(driver='driver'), work='/isolated')
            with self.subTest(wide=wide, areas=areas), patch.object(runner, 'WebDriver', return_value=wd), \
                    patch.object(runner, 'wait_for', immediate):
                if areas:
                    runner.Run.start_ui(run)
                    self.assertTrue(run.facts['ui'])
                else:
                    with self.assertRaisesRegex(AssertionError, 'navigation'):
                        runner.Run.start_ui(run)
                    self.assertNotIn('ui', run.facts)


def execute_dom(script, fixture, args, setup):
    program = """
const fixture=JSON.parse(process.argv[1]);
const args=JSON.parse(process.argv[2]);
const box=visible=>({width:visible?220:0,height:visible?20:0,top:0,bottom:visible?20:0,left:0,right:220});
const node=(text,visible=true)=>({innerText:text,textContent:text,getBoundingClientRect:()=>box(visible)});
const document={documentElement:{clientHeight:400}};
""" + setup + """
const visible=e=>!!e&&e.getBoundingClientRect().width>0&&e.getBoundingClientRect().height>0;
const value=new Function('all','document','visible','return (function(){'+process.argv[3]+'}).apply(null,arguments[3])')(
 all,document,visible,args);
console.log(JSON.stringify(value));
"""
    return json.loads(subprocess.check_output(['node', '-e', program, json.dumps(fixture),
                                               json.dumps(args or []), script], text=True))


class PassageTests(unittest.TestCase):
    def test_h5_fixture_has_one_query_hit_below_the_first_screen(self):
        with tempfile.TemporaryDirectory() as temp:
            corpus = Path(temp) / 'corpus'
            linux.generate(corpus)
            text = (corpus / linux.OPEN_FILE).read_text().lower()
        self.assertEqual(text.count(linux.QUERY), 1)
        self.assertGreaterEqual(text[:text.index(linux.QUERY)].count('\n'), 100)
        # The pane check requires the whole match line in view; that equals the word in view only
        # while the line cannot wrap at the pane's narrowest width.
        line = next(l for l in text.splitlines() if linux.QUERY in l)
        self.assertLessEqual(len(line), 48)

    def test_h5_both_adapters_open_the_clicked_passage_and_keep_the_external_viewer(self):
        # ResultsCard.ts:625,636; sv3-search.ts:448; Sv3Pane.ts:236,246;
        # documentPane/DocumentPane.ts:716,1427. Ordinary query-marked text isn't an anchored passage.
        from contextlib import ExitStack
        for adapter in (linux, windows):
            for case in ('match', 'top-of-file', 'wrong-document', 'no-anchor', 'wrong-term', 'hidden', 'offscreen', 'clipped'):
                with self.subTest(platform=adapter.__name__, case=case), ExitStack() as stack:
                    hit = {NavigationTests.key: 'exact-row'}
                    action = {NavigationTests.key: 'exact-actions'}
                    corpus = '/corpus'
                    path = corpus + '/' + linux.OPEN_FILE
                    run = Mock(facts={}, args=NS(phase='candidate'), platform=adapter.__name__)
                    run.require.side_effect = lambda key: corpus if key == 'corpus' else True
                    run.wd.find_visible.return_value = None
                    run.target.roots = []
                    opened = False
                    def click_row(element):
                        nonlocal opened
                        self.assertEqual(element, hit)
                        opened = True
                    run.wd.click_element.side_effect = click_row
                    def execute(script, args=None):
                        if 'sv3-pane-document' in script:
                            self.assertTrue(opened)
                            return execute_dom(script, {'case': case, 'path': path}, args, """
const line=node('Orchid '+(fixture.case==='wrong-term'?'wrong':'lighthouse')+' release qualification.');
line.getAttribute=name=>name==='data-line'?(fixture.case==='top-of-file'?'0':'120'):null;
if (fixture.case==='offscreen') line.getBoundingClientRect=()=>({...box(true),top:100,bottom:120});
if (fixture.case==='clipped') line.getBoundingClientRect=()=>({...box(true),top:10,bottom:30});
const region={...node(''),scrollTop:fixture.case==='top-of-file'?0:2400};
const root={querySelector:selector=>selector==='.scroll-region'?region:null};
const pane={...node('',fixture.case!=='hidden'),docPath:fixture.path+(fixture.case==='wrong-document'?'.other':''),
  shadowRoot:root};
const all=(rootOrDoc,selector)=>rootOrDoc===root
 ? (selector.includes('hl-')&&fixture.case!=='no-anchor'?[line]:[])
 : (selector==='jf-document-pane[data-testid="sv3-pane-document"]'?[pane]:[]);
""")
                        if 'window.__qualRowActionClick' in script:
                            return {'trusted': True, 'button': 0, 'row': path}
                        return action if 'row-actions' in script else hit
                    run.wd.execute.side_effect = execute
                    stack.enter_context(patch.object(linux, 'search_api'))
                    stack.enter_context(patch.object(linux, 'receiving_viewer', return_value={'pid': 123}))
                    stack.enter_context(patch.object(linux, 'wait_for', immediate))
                    stack.enter_context(patch.object(windows, 'wait_for', immediate))
                    stack.enter_context(patch.object(windows.Path, 'read_text', return_value='{"viewer":"notepad.exe"}'))
                    stack.enter_context(patch.object(windows, 'default_viewer', return_value={'executable': 'notepad.exe'}))
                    stack.enter_context(patch.object(windows, 'ps_json', return_value=[]))
                    stack.enter_context(patch.object(windows, 'viewer_identity', return_value={'pid': 123}))
                    if case == 'match':
                        self.assertEqual(adapter.search_open(run)['receivingViewerProcess'], {'pid': 123})
                        run.wd.click.assert_any_call('button,[role="menuitem"]', 'Open file', error=runner.ProductFailure)
                        self.assertTrue(opened, 'ordinary row click never opened the in-app passage')
                        run.native.shot.assert_any_call('J2-in-app-passage')
                    else:
                        with self.assertRaisesRegex(AssertionError, 'passage'):
                            adapter.search_open(run)
                        self.assertNotIn('search', run.facts)


class SettledResultTests(unittest.TestCase):
    def test_h11_windows_clicks_only_the_visible_refined_row_for_the_submitted_query(self):
        corpus = r'C:\corpus'
        fixture = str(Path(corpus) / linux.OPEN_FILE)
        run = Mock()
        run.require.side_effect = lambda key: corpus if key == 'corpus' else True
        states = ('quick', 'refining', 'searching', 'different-query', 'no-pass', 'hidden', 'settled')
        observations = []

        def execute(script, args):
            state = states[len(observations)]
            observations.append(state)
            return execute_dom(script, {'state': state, 'path': fixture, 'query': linux.QUERY}, args, """
const {state,path,query}=fixture;
const row={getBoundingClientRect:()=>box(state!=='hidden'),getAttribute:()=>state==='settled'?path.toLowerCase():path,
 'element-6066-11e4-a52e-4f735466cecf':state};
const root={};
const card={...node(''),shadowRoot:root,snapshot:{query:state==='different-query'?'other':query,
 isRefining:state==='refining',isSearching:state==='searching',
 passStage:state==='no-pass'?null:state==='quick'?'quick':'refined'}};
const all=(rootOrDoc,selector)=>selector==='[data-testid="search-result-row"]'?[row]:
 selector==='jf-results-card'&&rootOrDoc===document?[card]:[];
""")
        run.wd.execute.side_effect = execute

        def poll(fn, description, **kwargs):
            for _ in states:
                if row := fn():
                    return row
            self.fail(description)

        with patch.object(linux, 'search_api'), patch.object(windows, 'wait_for', poll), \
             patch.object(windows.Path, 'read_text', return_value='{"viewer":"notepad.exe"}'), \
             patch.object(windows, 'default_viewer', return_value={'executable': 'notepad.exe'}), \
             patch.object(windows, 'ps_json', return_value=[]), \
             patch.object(linux, 'open_result_passage', side_effect=EndOfControlProbe) as opened:
            with self.assertRaises(EndOfControlProbe):
                windows.search_open(run)
        opened.assert_called_once_with(run, {NavigationTests.key: 'settled'}, fixture)
        self.assertEqual(observations, list(states))


class AIAndUpdateTests(unittest.TestCase):
    def test_h6_waits_for_enabled_consent_and_classifies_readiness_timeout(self):
        # BrainSurface.ts:4094–4106; components/Button.ts:198 reflects native disabled.
        for resolves in (True, False):
            run = Mock(facts={})
            wd = runner.WebDriver(Mock(), 4444)
            wd.session = 'owned'
            run.wd = wd
            samples = iter((False, resolves))
            def execute(script, args):
                selector = args[0]
                if 'Install AI' in selector:
                    return {'button': 'install'}
                enabled = next(samples)
                return {'button': 'consent', 'enabled': enabled} if enabled or ':not(:disabled)' not in selector else None
            wd.execute = Mock(side_effect=execute)
            def clicked(element):
                if element['button'] == 'consent':
                    self.assertTrue(element['enabled'], 'clicked Accept and download while disabled')
                    raise EndOfControlProbe()
            wd.click_element = Mock(side_effect=clicked)
            def poll(probe, description, *_args, error=AssertionError, **_kwargs):
                for _ in range(2):
                    if result := probe():
                        return result
                raise error(description)
            with self.subTest(resolves=resolves), patch.object(runner, 'wait_for', poll):
                if resolves:
                    with self.assertRaises(EndOfControlProbe):
                        linux.install_ai(run)
                else:
                    with self.assertRaisesRegex(runner.Infrastructure, 'Accept and download'):
                        linux.install_ai(run)
            self.assertFalse(any('/api/ai/install/status' in call.args for call in run.api.call_args_list))

    def test_h7_cpu_requires_the_visible_unavailable_answer_step(self):
        # BrainSurface.ts:1991–2003,2044,2307–2320. The planner reason differs.
        for reason, reach, lit, visible in (
                ('Not available on Linux yet', 'na', 'false', True),
                ('Needs an NVIDIA graphics card', 'na', 'false', True),
                ('', 'na', 'false', True), ('Available', 'ok', 'true', True),
                ('Needs an NVIDIA graphics card', 'ok', 'true', True),
                ('Needs an NVIDIA graphics card', 'na', 'false', False)):
            with self.subTest(reason=reason, reach=reach, lit=lit, visible=visible):
                run = Mock(facts={}, args=NS(phase='candidate'))
                run.wd.text.return_value = f'Answer questions\n{reason}'
                run.wd.execute.side_effect = lambda script, args=None: execute_dom(script,
                    dict(reason=reason, reach=reach, lit=lit, visible=visible), args, """
const name=node('Answer questions', fixture.visible);
const reason=node(fixture.reason, fixture.visible);
const step={...node('',fixture.visible),dataset:{reach:fixture.reach,lit:fixture.lit},
  querySelector:s=>s==='.step-name'?name:s==='.step-reason'?reason:null};
const all=(root,selector)=>selector.includes('[data-feature="chat"]')?[step]:[];
""")
                run.api.side_effect = {
                    '/api/ai/install/plan-preview': {'downloadProfile': 'CPU', 'components': [
                        {'id': 'chat', 'state': 'unavailable', 'unavailableReason': 'Engine GPU detail'},
                        {'id': 'embedding', 'state': 'installed'}]},
                    '/api/ai/install/manifest': {},
                    '/api/ai/install/status': {'installedFully': True, 'readyCapabilities': ['retrieval-core']},
                    '/api/ai/runtime/status': {'onnxFeatures': [
                        {'id': model, 'modelActive': True} for model in ('embed', 'citation_scorer')]},
                }.get
                with patch.object(linux, 'wait_for', immediate), patch.object(linux, 'activate_downloaded'), \
                        patch.object(linux, 'semantic_search', return_value={}):
                    if reason.startswith(('Needs', 'Not available')) and reach == 'na' and visible:
                        self.assertEqual(linux.install_ai(run)['uiText'], run.wd.text.return_value)
                    else:
                        with self.assertRaisesRegex(AssertionError, 'CPU chat unavailable explanation'):
                            linux.install_ai(run)

    def test_h9_screenshot_records_the_version_row_in_the_open_status_panel(self):
        # StatusPill.ts:465,473; StatusPanel.ts:378–392. Rows mount in the closed panel too.
        for panel_opens, has_action in ((True, True), (True, False), (False, False)):
            with self.subTest(panel_opens=panel_opens, has_action=has_action), patch.object(linux, 'wait_for', immediate):
                opened = False
                run = Mock()
                run.require.return_value = '0.3.5'
                run.wd.find_visible.return_value = None
                def click(selector, *args):
                    nonlocal opened
                    if selector.startswith('button.trigger'):
                        opened = panel_opens
                    elif selector == 'button[aria-label="Close status panel"]':
                        opened = False
                run.wd.click.side_effect = click
                def execute(script, args=None):
                    if 'const rows=' in script:
                        return execute_dom(script, {'open': opened}, args, """
const title={...node('JustSearch 0.3.5 is available',fixture.open),scrollIntoView:()=>{}};
const row={...title,querySelector:selector=>selector==='.title .text'?title:null};
const all=(root,selector)=>selector==='article[data-fact="app-update"]'?[row]:[];
""")
                    return opened and has_action
                run.wd.execute.side_effect = execute
                def shot(name):
                    self.assertEqual(name, 'offer-banner')
                    self.assertTrue(opened, 'update row is mounted but hidden in the screenshot')
                run.native.shot.side_effect = shot
                if not panel_opens:
                    with self.assertRaisesRegex(runner.Infrastructure, 'visible'):
                        linux.open_update_offer(run, 'offer')
                    run.native.shot.assert_not_called()
                    continue
                linux.open_update_offer(run, 'offer')
                if has_action:
                    run.wd.click.assert_any_call('article[data-fact="app-update"] button', 'Review and install')
                else:
                    run.nav.assert_called_once_with('Settings')
                    self.assertFalse(opened, 'dismiss the panel before navigating to Settings')


class AuthoredStateTests(unittest.TestCase):
    def test_h5_candidate_authors_rule_then_dark_theme_and_predecessor_only_authors_theme(self):
        for adapter in (linux, windows, gpu):
            for phase in ('candidate', 'predecessor') if adapter is not gpu else ('candidate',):
                run = Mock(args=NS(phase=phase, resume=None), facts={})
                settings = {'ui': {'theme': 'dark', 'excludePatterns': [RULE],
                                   'indexingPause': {'schedule': dict(linux.READING_HOURS)}}}
                run.api.return_value = settings
                def click(selector, *args, settings=settings):
                    self.assertNotIn('bookmark', selector.lower(), 'The redesigned UI has no bookmark controls')
                    # Reading hours save on change: Only between, then Any time (kept, turned off).
                    if selector == 'input#indexing-hours':
                        settings['ui']['indexingPause'] = {'schedule': {**linux.READING_HOURS, 'enabled': True}}
                    if selector == 'input[name="indexing-hours"]:not(#indexing-hours)':
                        settings['ui']['indexingPause'] = {'schedule': dict(linux.READING_HOURS)}
                run.wd.click.side_effect = click
                run.wd.execute.return_value = {'pattern': RULE, 'label': 'Skip rule 2'}
                state = {'settings': settings, 'savedFiles': {'files': {'embedding.onnx': 'sha'}}}
                with self.subTest(platform=adapter.__name__, phase=phase), patch.object(linux, 'wait_for', immediate), patch.object(linux, 'snapshot', return_value=state):
                    self.assertEqual(adapter.author_state(run), state)
                run.wd.click.assert_any_call('button[role="radio"]', 'Dark')
                labels = [c.args[0] for c in run.wd.click.call_args_list]
                if phase == 'candidate':
                    # Slice 6b-2: no Save patterns; leaving the field (Tab) saves, then the in-place ask is answered.
                    self.assertEqual(labels[:3], ['button[role="tab"][data-tab-id="folders"]',
                        'button[aria-label="Add a rule"]:not(:disabled)', 'button'])
                    run.wd.click.assert_any_call('button', 'Keep them')
                    run.wd.keys.assert_called_once_with('.skip-rule:last-of-type input[aria-label^="Skip rule "]', RULE + '\ue004')
                    self.assertFalse(any('Save patterns' in label for label in labels))
                    self.assertEqual(run.facts['skipRule'], RULE)
                else:
                    run.wd.keys.assert_not_called()
                    self.assertNotIn('skipRule', run.facts)
                self.assertNotIn('bookmark', run.facts)
                self.assertTrue(run.facts['authored'])

    def test_j4_waits_for_the_left_field_to_persist_before_answering_the_ask(self):
        # The rule must be saved by leaving the field before Keep them is pressed; nothing is removed.
        run = Mock(args=NS(phase='candidate', resume=None), facts={})
        order = []
        saved = {'ui': {'theme': 'dark', 'excludePatterns': []}}
        def keys(selector, text):
            order.append(('keys', text))
            saved['ui']['excludePatterns'] = [RULE]
        def click(selector, *args):
            order.append(('click', selector, *args))
            if args == ('Keep them',):
                self.assertEqual(saved['ui']['excludePatterns'], [RULE], 'the ask is answered only after the rule persisted')
            if selector == 'input#indexing-hours':
                saved['ui']['indexingPause'] = {'schedule': {**linux.READING_HOURS, 'enabled': True}}
            if selector == 'input[name="indexing-hours"]:not(#indexing-hours)':
                saved['ui']['indexingPause'] = {'schedule': dict(linux.READING_HOURS)}
        run.wd.keys.side_effect = keys
        run.wd.click.side_effect = click
        run.api.side_effect = lambda path, *a: saved
        run.wd.execute.return_value = {'pattern': RULE, 'label': 'Skip rule 2'}
        with patch.object(linux, 'wait_for', lambda probe, *a, **k: probe()), patch.object(linux, 'snapshot', return_value={}):
            linux.author_state(run)
        self.assertIn(('click', 'button', 'Keep them'), order)
        self.assertLess(order.index(('keys', RULE + '\ue004')), order.index(('click', 'button', 'Keep them')))
        self.assertNotIn('Remove from search', json.dumps(order))

    def test_j4_authors_reading_hours_in_library_and_turns_them_off_keeping_them(self):
        # Only between saves the default hours on change; Any time turns them off and keeps days and times.
        run = Mock(args=NS(phase='candidate', resume=None), facts={})
        saved = {'ui': {'theme': 'dark', 'excludePatterns': [RULE]}}
        clicks = []
        def click(selector, *args):
            clicks.append(selector)
            if selector == 'input#indexing-hours':
                saved['ui']['indexingPause'] = {'schedule': {**linux.READING_HOURS, 'enabled': True}}
            if selector == 'input[name="indexing-hours"]:not(#indexing-hours)':
                self.assertTrue(saved['ui']['indexingPause']['schedule']['enabled'], 'Only between persisted first')
                saved['ui']['indexingPause'] = {'schedule': dict(linux.READING_HOURS)}
        run.wd.click.side_effect = click
        run.api.side_effect = lambda path, *a: saved
        run.wd.execute.return_value = {'pattern': RULE, 'label': 'Skip rule 2'}
        with patch.object(linux, 'wait_for', immediate), patch.object(linux, 'snapshot', return_value={}):
            linux.author_state(run)
        self.assertIn('input#indexing-hours', clicks, 'Only between is chosen in Library')
        self.assertIn('input[name="indexing-hours"]:not(#indexing-hours)', clicks, 'then Any time')
        self.assertLess(clicks.index('input#indexing-hours'), clicks.index('input[name="indexing-hours"]:not(#indexing-hours)'))
        self.assertEqual(run.facts['readingHours'], {'enabled': False, 'days': [1, 2, 3, 4, 5, 6, 7],
                                                     'startMinute': 540, 'endMinute': 1020})
        # Reading hours that never persisted fail the journey.
        lost = Mock(args=NS(phase='candidate', resume=None), facts={})
        lost.api.return_value = {'ui': {'theme': 'dark', 'excludePatterns': [RULE]}}
        lost.wd.execute.return_value = {'pattern': RULE, 'label': 'Skip rule 2'}
        with patch.object(linux, 'wait_for', immediate), patch.object(linux, 'snapshot', return_value={}), \
                self.assertRaises(AssertionError):
            linux.author_state(lost)

    def test_h5_assert_state_requires_rule_and_dark_even_if_before_snapshot_matches(self):
        for phase, patterns, theme, passes in (('candidate', [RULE], 'dark', True),
                ('candidate', [], 'dark', False), ('candidate', [RULE], 'light', False),
                ('predecessor', [], 'dark', True), ('predecessor', [], 'system', False)):
            run = authored_run()
            run.args.phase = phase
            state = copy.deepcopy(run.current_state)
            state['settings']['ui'].update(excludePatterns=patterns, theme=theme)
            run.facts['beforeState'] = state
            with self.subTest(phase=phase, patterns=patterns, theme=theme), patch.object(linux, 'snapshot', return_value=state):
                if passes:
                    self.assertEqual(linux.assert_state(run), state)
                else:
                    with self.assertRaises(AssertionError):
                        linux.assert_state(run)

    def test_h5_predecessor_restart_needs_no_candidate_rule_editor(self):
        run = authored_run()
        run.args.phase = 'predecessor'
        run.current_state['settings']['ui'].pop('excludePatterns')
        run.facts['beforeState'] = run.current_state
        run.facts.pop('skipRule')
        with patch.object(linux, 'snapshot', return_value=run.current_state):
            self.assertEqual(linux.restart(run), run.current_state)
        run.wd.click.assert_not_called()
        run.wd.execute.assert_not_called()

    def test_h5_predecessor_models_and_dark_theme_suffice_without_bookmarks(self):
        state = {'settings': {'ui': {'theme': 'dark'}},
                 'savedFiles': {'files': {'embedding.onnx': 'model-sha'}}}
        for changed in ('none', 'theme', 'models'):
            run = Mock(facts={'ai': True, 'authored': True, 'beforeState': state})
            current = copy.deepcopy(state)
            if changed == 'theme':
                current['settings']['ui']['theme'] = 'light'
            if changed == 'models':
                current['savedFiles']['files'].clear()
            with self.subTest(changed=changed), patch.object(linux, 'snapshot', return_value=current):
                if changed == 'none':
                    self.assertEqual(linux.require_predecessor_state(run), current['savedFiles'])
                else:
                    with self.assertRaises(runner.Unavailable):
                        linux.require_predecessor_state(run)


if __name__ == '__main__':
    unittest.main()


class SkipRuleTypingTest(unittest.TestCase):
    def test_skip_rule_has_no_shifted_characters(self):
        # Hosted X11 typing turned '*' into '8' (run 43198318384); keep the rule to unshifted keys.
        from linux import SKIP_RULE
        self.assertRegex(SKIP_RULE, r'^[a-z0-9./-]+$')
