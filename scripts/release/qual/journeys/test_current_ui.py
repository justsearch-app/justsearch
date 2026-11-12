"""Hosted redesign regressions H1–H6; isolated UI/Engine fixtures, no app or VM."""
import copy
import json
import subprocess
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
const all=(root,selector)=>{
 if(root===card.shadowRoot)return [];
 if(selector==='jf-shell')return [{activeId:fixture.active===false?'core.search-v3-surface':'core.library-surface'}];
 if(selector==='jf-walkthrough-card')return [card];
 if(selector==='button')return [];
 if(selector==='button.segment[data-surface-id="core.library-surface"][aria-current="page"]')return fixture.current===false?[]:[segment];
 return [];
};
const value=new Function('all','document','args','return (function(){'+process.argv[2]+'}).apply(null,args)')(all,{},JSON.parse(process.argv[3]));
console.log(JSON.stringify(value));"""
    return json.loads(subprocess.check_output(['node', '-e', js, json.dumps(fixture), script,
                                               json.dumps(args or [])], text=True))


RULE = '**/justsearch-journey-skip-*.tmp'


def authored_run():
    state = {'savedFiles': {'files': {'models/embed.onnx': 'sha'}},
             'settings': {'ui': {'theme': 'dark', 'excludePatterns': [RULE]}},
             'roots': ['/corpus'], 'search': {'results': [linux.OPEN_FILE]}}
    run = Mock(args=NS(phase='candidate'), facts={'skipRule': RULE, 'beforeState': state, 'corpus': '/corpus'})
    run.require.side_effect = lambda key: run.facts[key]
    run.current_state = state
    return run


class CurrentUIControlsTests(unittest.TestCase):
    def test_h1_both_folder_pickers_accept_current_label_and_confirm_returned_path(self):
        for adapter in (linux, windows):
            run = Mock(facts={})
            run.require.side_effect = lambda key: '/corpus' if key == 'corpus' else True
            run.api.return_value = ['/corpus']
            run.wd.execute.return_value = True
            run.native.picker_visible.side_effect = [True, False]
            with self.subTest(platform=adapter.__name__), patch.object(adapter, 'wait_for', immediate), patch.object(linux.time, 'sleep'):
                self.assertEqual(adapter.add_folder(run), ['/corpus'])
            selector = run.wd.click.call_args_list[0].args[0]
            self.assertIn('button[aria-label="Add folder"]', selector.split(','))
            # The predecessor still spells Folder with a capital F.
            self.assertIn('button[aria-label="Add Folder"]', selector.split(','))
            run.wd.click.assert_any_call('button[aria-label="Add"]')
            self.assertIn('Folder path', run.wd.execute.call_args.args[0])
            if adapter is windows:
                run.native.select_folder.assert_called_once_with('/corpus')
            else:
                run.native.type.assert_called_once_with('/corpus')

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
        for fixture, accepts in (({}, True), ({'active': False}, False), ({'current': False}, False),
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

    def test_h4_start_answers_waits_for_chat_intent_acknowledgment(self):
        run = Mock(facts={})
        run.api.return_value = {'inference': {'chatEnabledSpec': True}}
        with patch.object(gpu, 'wait_chat_ready', return_value={'ready': True}) as ready:
            self.assertEqual(gpu.start_chat(run), {'ready': True})
        run.wd.click.assert_called_once_with('button[aria-label="Start answers"]')
        self.assertTrue(run.facts['chatStart']['acknowledged'])
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

    def test_h6_search_mode_trigger_is_independent_of_selected_mode(self):
        for adapter in (linux, windows):
            run = Mock()
            run.require.return_value = '/corpus'
            def click(selector):
                self.assertEqual(selector, 'button[data-testid="sv3-composer-tier"]',
                                 'Mode can initially be Ask or Agent as well as Search')
                raise EndOfControlProbe()
            run.wd.click.side_effect = click
            with self.subTest(platform=adapter.__name__), patch.object(linux, 'search_api'), \
                    patch.object(windows.Path, 'read_text', return_value=json.dumps({'viewer': 'notepad.exe'})), \
                    patch.object(windows, 'default_viewer', return_value={'executable': 'notepad.exe'}), \
                    patch.object(windows, 'ps_json', return_value=[]), self.assertRaises(EndOfControlProbe):
                adapter.search_open(run)


class AuthoredStateTests(unittest.TestCase):
    def test_h5_candidate_authors_rule_then_dark_theme_and_predecessor_only_authors_theme(self):
        for adapter in (linux, windows, gpu):
            for phase in ('candidate', 'predecessor') if adapter is not gpu else ('candidate',):
                run = Mock(args=NS(phase=phase, resume=None), facts={})
                settings = {'ui': {'theme': 'dark', 'excludePatterns': [RULE]}}
                run.api.return_value = settings
                run.wd.click.side_effect = lambda selector, *args: self.assertNotIn('bookmark', selector.lower(), 'The redesigned UI has no bookmark controls')
                run.wd.execute.return_value = {'pattern': RULE, 'label': 'Skip rule 2'}
                state = {'settings': settings, 'savedFiles': {'files': {'embedding.onnx': 'sha'}}}
                with self.subTest(platform=adapter.__name__, phase=phase), patch.object(linux, 'wait_for', immediate), patch.object(linux, 'snapshot', return_value=state):
                    self.assertEqual(adapter.author_state(run), state)
                run.wd.click.assert_any_call('button[role="radio"]', 'Dark')
                labels = [c.args[0] for c in run.wd.click.call_args_list]
                if phase == 'candidate':
                    self.assertEqual(labels[:3], ['button[role="tab"][data-tab-id="folders"]',
                        'button[aria-label="Add a rule"]:not(:disabled)',
                        'button[aria-label="Save patterns"]:not(:disabled)'])
                    run.wd.keys.assert_called_once_with('.skip-rule:last-of-type input[aria-label^="Skip rule "]', RULE)
                    self.assertEqual(run.facts['skipRule'], RULE)
                else:
                    run.wd.keys.assert_not_called()
                    self.assertNotIn('skipRule', run.facts)
                self.assertNotIn('bookmark', run.facts)
                self.assertTrue(run.facts['authored'])

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
