"""Bounded regression checks for cached activation and Wayland dispatch attribution."""
import json
import unittest
from unittest.mock import Mock, patch

import linux
from runner import Infrastructure, classify


def immediate(fn, description, *args, error=AssertionError, **kwargs):
    value = fn()
    if not value:
        raise error(description)
    return value


class Repair2Tests(unittest.TestCase):
    def test_cached_j3_waits_past_installed_fully_transient_then_activates(self):
        run = Mock()
        run.facts = {'resumedSetup': True}
        run.wd.text.return_value = 'Chat is not supported here.'
        run.wd.execute.return_value = []
        inactive = {'onnxFeatures': []}
        runtime = {'onnxFeatures': [{'id': name, 'modelActive': name != 'reranker'}
                   for name in ('embed', 'splade', 'reranker', 'citation_scorer')]}
        transient = {'state': 'idle', 'phase': 'idle', 'installedFully': True}
        staged = dict(transient, phase='activation_required', installedFully=False)
        active = dict(transient, phase='done', readyCapabilities=['retrieval-core'])
        activated = False
        statuses = iter([transient, staged])
        retrieval = {'results': [linux.OPEN_FILE], 'searchTrace': {'effectiveMode': 'VECTOR',
            'stages': [{'id': 'dense-retrieval', 'status': 'executed'},
                       {'id': 'cross-encoder', 'status': 'executed'}]}}
        def activate(_):
            nonlocal activated
            activated = True
        def api(path, *args):
            if path == '/api/ai/install/status':
                return active if activated else next(statuses)
            return {'/api/ai/install/plan-preview': {'downloadProfile': 'CPU', 'components': [
                        {'id': 'chat', 'state': 'unavailable'}, {'id': 'embed', 'state': 'installed'}]},
                    '/api/ai/install/manifest': {}, '/api/ai/runtime/status': runtime if activated else inactive,
                    '/api/knowledge/search': retrieval}[path]
        run.api.side_effect = api
        def bounded(fn, description, **kwargs):
            if description == 'Default CPU AI install':
                first = fn()
                self.assertIsNone(first, 'installedFully transient must not settle cached J3')
                return immediate(fn, description, **kwargs)
            return immediate(fn, description, **kwargs)
        with patch.object(linux, 'wait_for', bounded), patch.object(linux, 'activate_downloaded', activate):
            with self.assertRaises(AssertionError) as failure:
                linux.install_ai(run)
        self.assertTrue(activated)
        self.assertEqual(classify(failure.exception), 'failed-product')
        reason = str(failure.exception)
        for expected in ('reranker.modelActive=false', 'embed.modelActive=true',
                         'splade.modelActive=true', 'citation_scorer.modelActive=true', 'cross-encoder'):
            self.assertIn(expected, reason)
        run.wd.click.assert_not_called()  # No repeated download.
        self.assertNotIn('ai', run.facts)

    def test_cached_j3_unsettled_resume_is_infrastructure_with_deadline(self):
        run = Mock()
        run.facts = {'resumedSetup': True}
        run.api.return_value = {'downloadProfile': 'CPU', 'state': 'idle', 'phase': 'idle',
                                'installedFully': True, 'onnxFeatures': []}
        run.wd.execute.return_value = []
        with patch.object(linux.time, 'monotonic', side_effect=[0, 0, 901, 901, 901, 1802]), \
             patch.object(linux.time, 'sleep'), patch.object(linux, 'activate_downloaded') as activate:
            with self.assertRaisesRegex(Infrastructure, 'Default CPU AI install timed out after 900s'):
                linux.install_ai(run)
        activate.assert_not_called()

    def test_wayland_dispatch_precedes_product_focus_classification(self):
        for dispatch, expected in ((None, 'failed-infrastructure'),
                                   ({'pid': 123, 'time': '2026-10-28T21:00:00Z'}, 'failed-product')):
            with self.subTest(dispatch=dispatch):
                run = Mock(work='/tmp/own-shortcut', binary='/usr/bin/JustSearch', run_id='own-run')
                run.args.lane = 'vm-linux-wayland'
                run.require.return_value = '/tmp/own-corpus'
                run.wd.execute.return_value = '--focus-search'
                run.target.json.return_value = dispatch
                run.target.env = {'WAYLAND_DISPLAY': 'wayland-0', 'XDG_SESSION_TYPE': 'wayland'}
                run.native.focused_window.return_value = {'pid': 42, 'title': 'fixture — Mousepad'}
                run.native.app_window.return_value = None
                with patch.object(linux, 'require_candidate'), patch.object(linux, 'wait_for', immediate):
                    with self.assertRaises(Exception) as failure:
                        linux.shortcut(run)
                self.assertEqual(classify(failure.exception), expected)
                self.assertIn('dispatch' if dispatch is None else 'foregrounded search', str(failure.exception))
                run.native.type.assert_not_called()
                evidence = [call.args[1] for call in run.evidence.call_args_list
                            if call.args[0] == 'J7-shortcut-dispatch']
                self.assertTrue(evidence, 'Dispatch and compositor evidence retained on failure')
                self.assertEqual(evidence[-1]['dispatched'], dispatch is not None)
                self.assertEqual(evidence[-1]['focusedWindow']['pid'], 42)
                self.assertIn('wayland-0', json.dumps(evidence[-1]))


if __name__ == '__main__':
    unittest.main()
