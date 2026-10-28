"""Regression counterexamples from the independent W4a verifier (S3/S7/S22)."""
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import linux
from runner import Infrastructure, classify


def once(fn, description, *args, error=AssertionError, **kwargs):
    value = fn()
    if not value:
        raise error(description)
    return value


class RepairTests(unittest.TestCase):
    def test_shortcut_cannot_pass_when_mousepad_receives_query(self):
        run = Mock(work='/tmp/own-run', binary='/usr/bin/JustSearch', run_id='own-run')
        run.args.lane = 'vm-linux-wayland'
        run.require.return_value = '/tmp/own-corpus'
        run.wd.execute.return_value = '--focus-search'
        # The exact false-pass observation: app visible behind Mousepad;
        # fixture and newly typed text are in Mousepad, not the search field.
        run.native.screen_text.return_value = 'Search v3 orchid lighthouse'
        run.native.focused_window.return_value = {'pid': 42, 'title': 'fixture — Mousepad'}
        run.native.app_window.return_value = None
        run.target.json.return_value = {'pid': 123}
        with patch.object(linux, 'require_candidate'), patch.object(linux, 'wait_for', once):
            with self.assertRaisesRegex(AssertionError, 'foregrounded search'):
                linux.shortcut(run)
        run.native.type.assert_not_called()

    def test_warm_link_uses_library_title_even_when_body_ocr_misses_button(self):
        run = Mock(work='/tmp/own-run')
        run.native.screen_text.return_value = 'Search v3 orchid lighthouse'
        run.native.app_text.side_effect = ['Search v3 orchid lighthouse', 'Library']
        run.native.app_window.return_value = {'pid': 1, 'rect': {'x': 0, 'y': 0, 'width': 1024, 'height': 700}}
        with patch.object(linux, 'require_candidate'), patch.object(linux, 'wait_for', once), \
             patch.object(linux, 'interaction_observation'):
            result = linux.deep_links(run)
        self.assertIn('Library', json.dumps(result['warm']))
        self.assertFalse(run.wd.execute.called)

    def test_restart_bookmark_stays_in_normal_restarted_process(self):
        run = Mock()
        run.start_ui.side_effect = AssertionError('WebDriver relaunch invalidates normal restart observation')
        geometry = {'button': {'x': 900, 'y': 20}, 'popup': {'x': 750, 'y': 40, 'width': 240,
                    'height': 60, 'viewportWidth': 1024, 'viewportHeight': 700}}
        run.require.side_effect = lambda key: geometry if key == 'bookmarkGeometry' else True
        run.native.app_window.return_value = {'pid': 1, 'rect': {'x': 100, 'y': 80}}
        events = []
        texts = iter(['Settings INTERFACE & APPEARANCE', 'Search v3', '', 'Library just now'])
        def app_text(name, *args):
            events.append(name)
            return next(texts)
        run.native.app_text.side_effect = app_text
        run.native.key.side_effect = lambda key: events.append(key)
        with patch.object(linux, 'assert_state', return_value={'retained': True}), \
             patch.object(linux, 'interaction_observation'), patch.object(linux, 'wait_for', once):
            self.assertEqual(linux.restart(run), {'retained': True})
        run.start_ui.assert_not_called()
        run.native.click.assert_called_once_with(1000, 100)
        run.wd.click.assert_not_called()
        self.assertEqual(events[:3], ['J4-restored-settings', 'escape', 'J4-settings-closed'])

    def test_apt_closed_proxy_failure_is_infrastructure(self):
        with tempfile.TemporaryDirectory() as temp:
            run = Mock(out=Path(temp))
            run.target.vm = None
            run.target.run.return_value = SimpleNamespace(returncode=100, stdout='',
                stderr='E: Failed to fetch http://archive.ubuntu.com/pool/curl.deb Could not connect to 127.0.0.1:9 (Connection refused)')
            with self.assertRaises(Infrastructure) as raised:
                linux.apt(run, '--download-only', '--reinstall', 'install', 'curl')
            self.assertEqual(classify(raised.exception), 'failed-infrastructure')
            self.assertIn('Failed to fetch', next(Path(temp).glob('apt-*.log')).read_text())

    def test_apt_package_script_defect_stays_product(self):
        with tempfile.TemporaryDirectory() as temp:
            run = Mock(out=Path(temp))
            run.target.vm = None
            run.target.run.return_value = SimpleNamespace(returncode=100, stdout='',
                stderr='just-search post-installation script subprocess returned error exit status 1')
            with self.assertRaises(AssertionError):
                linux.apt(run, 'install', '/tmp/own.deb')

    def test_j3_downloaded_but_inactive_models_cannot_pass(self):
        run = Mock()
        run.facts = {}
        run.wd.text.return_value = 'Chat is not supported here.'
        run.wd.execute.side_effect = lambda script: {'typed': True, 'operationKeys': []} if 'const typed=' in script else []
        run.api.side_effect = lambda path, *args: {
            '/api/ai/install/plan-preview': {'downloadProfile': 'CPU', 'components': [
                {'id': 'chat', 'state': 'unavailable'}, {'id': 'embedding', 'state': 'to-download'}]},
            '/api/ai/install/manifest': {},
            '/api/ai/install/status': {'state': 'completed', 'phase': 'activation_required',
                'installedFully': False, 'readyCapabilities': ['retrieval-core']},
            '/api/ai/runtime/status': {'onnxFeatures': [{'id': 'embed', 'modelActive': False}]},
        }[path]
        with patch.object(linux, 'wait_for', once), patch.object(linux, 'search_api', return_value={}):
            with self.assertRaisesRegex(AssertionError, 'activated'):
                linux.install_ai(run)
        self.assertIn(('button[aria-label="Activate downloaded models"]',), [c.args for c in run.wd.click.call_args_list])
        self.assertNotIn('ai', run.facts)

    def test_vector_query_rejects_keyword_fallback_and_inactive_dense_stage(self):
        run = Mock()
        for trace in [
            {'effectiveMode': 'TEXT'},
            {'effectiveMode': 'VECTOR', 'degradation': {'hybridFallback': True}, 'stages': [{'id': 'dense-retrieval', 'status': 'executed'}]},
            {'effectiveMode': 'VECTOR', 'stages': [{'id': 'dense-retrieval', 'status': 'skipped'}]},
            {'effectiveMode': 'VECTOR', 'stages': [{'id': 'dense-retrieval', 'status': 'executed'}, {'id': 'sparse-retrieval', 'status': 'executed'}]},
        ]:
            run.api.return_value = {'results': [linux.OPEN_FILE], 'searchTrace': trace}
            with patch.object(linux, 'wait_for', once), self.assertRaises(AssertionError):
                linux.semantic_search(run)
        good = {'results': [linux.OPEN_FILE], 'searchTrace': {'effectiveMode': 'VECTOR',
                'stages': [{'id': 'dense-retrieval', 'status': 'executed'}]}}
        run.api.return_value = good
        with patch.object(linux, 'wait_for', once):
            self.assertEqual(linux.semantic_search(run), good)
        self.assertEqual(run.api.call_args.args[1]['mode'], 'vector')

    def test_j3_query_loads_lazy_models_and_keeps_final_active_assertion(self):
        for typed, reranker_active in ((True, True), (False, True), (False, False)):
            run = Mock()
            run.facts = {}
            run.wd.text.return_value = 'Chat is not supported here.'
            run.wd.execute.side_effect = lambda script: {'typed': typed, 'operationKeys': [] if typed else ['accepted-operation']} if 'const typed=' in script else []
            downloaded = {'state': 'completed', 'phase': 'activation_required', 'installedFully': False,
                          'readyCapabilities': ['retrieval-core']}
            active = dict(downloaded, phase='completed', installedFully=True)
            statuses = iter([downloaded, active])
            retrieval = {'results': [linux.OPEN_FILE], 'searchTrace': {'effectiveMode': 'VECTOR',
                         'stages': [{'id': 'dense-retrieval', 'status': 'executed'}]}}
            queried = False
            def api(path, *args):
                nonlocal queried
                if path == '/api/ai/install/status':
                    return next(statuses)
                if path == '/api/knowledge/search':
                    queried = True
                    return retrieval
                return {'/api/ai/install/plan-preview': {'downloadProfile': 'CPU', 'components': [
                    {'id': 'chat', 'state': 'unavailable'}, {'id': 'embedding', 'state': 'to-download'}]},
                    '/api/ai/install/manifest': {}, '/api/ai/runtime/status': {'onnxFeatures': [
                        {'id': name, 'modelActive': (queried and reranker_active) or name != 'reranker'}
                        for name in ('embed', 'splade', 'reranker', 'citation_scorer')]}}[path]
            run.api.side_effect = api
            with patch.object(linux, 'wait_for', once):
                if not reranker_active:
                    with self.assertRaisesRegex(AssertionError, 'All default retrieval models active'):
                        linux.install_ai(run)
                    self.assertTrue(queried, 'Dense retrieval ran before the final active-model failure')
                    continue
                result = linux.install_ai(run)
            self.assertTrue(result['installed']['installedFully'])
            self.assertEqual(result['activeRetrieval'], retrieval)
            if typed:
                run.wd.keys.assert_called_once_with('input[data-testid="authorization-typed-input"]',
                                                    'core.activate-installed-models')
            else:
                run.wd.keys.assert_not_called()

    def test_shortcut_requires_focused_search_control_and_exact_value(self):
        run = Mock()
        run.native.app_window.return_value = {'pid': 1}
        for state in [{'documentFocused': False, 'searchFocused': True, 'value': 'query'},
                      {'documentFocused': True, 'searchFocused': False, 'value': 'query'},
                      {'documentFocused': True, 'searchFocused': True, 'value': 'stale query'}]:
            run.wd.execute.return_value = state
            self.assertIsNone(linux.shortcut_receiver(run, 'query'))
        run.wd.execute.return_value = {'documentFocused': True, 'searchFocused': True, 'value': 'query'}
        self.assertTrue(linux.shortcut_receiver(run, 'query'))

    def test_j3_restored_downloads_are_not_downloaded_again(self):
        run = Mock()
        run.facts = {'resumedSetup': True}
        run.wd.text.return_value = 'Chat is not supported here.'
        run.wd.execute.return_value = []
        installed = {'state': 'completed', 'phase': 'completed', 'installedFully': True,
                     'readyCapabilities': ['retrieval-core']}
        retrieval = {'results': [linux.OPEN_FILE], 'searchTrace': {'effectiveMode': 'VECTOR',
                     'stages': [{'id': 'dense-retrieval', 'status': 'executed'}]}}
        run.api.side_effect = lambda path, *args: {
            '/api/ai/install/plan-preview': {'downloadProfile': 'CPU', 'components': [
                {'id': 'chat', 'state': 'unavailable'}, {'id': 'embedding', 'state': 'installed'}]},
            '/api/ai/install/manifest': {}, '/api/ai/install/status': installed,
            '/api/ai/runtime/status': {'onnxFeatures': [{'id': name, 'modelActive': True}
                for name in ('embed', 'splade', 'reranker', 'citation_scorer')]},
            '/api/knowledge/search': retrieval,
        }[path]
        with patch.object(linux, 'wait_for', once):
            result = linux.install_ai(run)
        self.assertEqual(result['activeRetrieval'], retrieval)
        run.wd.click.assert_not_called()

    def test_j3_restored_idle_staged_models_activate_without_a_new_download(self):
        run = Mock()
        run.facts = {'resumedSetup': True}
        run.wd.text.return_value = 'Chat is not supported here.'
        run.wd.execute.side_effect = lambda script: {'typed': False, 'operationKeys': ['receipt']} if 'const typed=' in script else []
        staged = {'state': 'idle', 'phase': 'activation_required', 'installedFully': False, 'readyCapabilities': []}
        active = {'state': 'idle', 'phase': 'done', 'installedFully': True, 'readyCapabilities': ['retrieval-core']}
        statuses = iter([staged, active])
        retrieval = {'results': [linux.OPEN_FILE], 'searchTrace': {'effectiveMode': 'VECTOR',
                     'stages': [{'id': 'dense-retrieval', 'status': 'executed'}]}}
        def api(path, *args):
            if path == '/api/ai/install/status':
                return next(statuses)
            return {'/api/ai/install/plan-preview': {'downloadProfile': 'CPU', 'components': [
                    {'id': 'chat', 'state': 'unavailable'}, {'id': 'embedding', 'state': 'installed'}]},
                '/api/ai/install/manifest': {},
                '/api/ai/runtime/status': {'onnxFeatures': [{'id': name, 'modelActive': True}
                    for name in ('embed', 'splade', 'reranker', 'citation_scorer')]},
                '/api/knowledge/search': retrieval}[path]
        run.api.side_effect = api
        with patch.object(linux, 'wait_for', once):
            self.assertEqual(linux.install_ai(run)['activeRetrieval'], retrieval)
        run.wd.click.assert_called_once_with('button[aria-label="Activate downloaded models"]')


if __name__ == '__main__':
    unittest.main()
