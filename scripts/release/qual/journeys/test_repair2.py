"""Bounded regression checks for Wayland dispatch attribution."""
import json
import unittest
from unittest.mock import Mock, patch

import linux
from runner import classify


def immediate(fn, description, *args, error=AssertionError, **kwargs):
    value = fn()
    if not value:
        raise error(description)
    return value


class Repair2Tests(unittest.TestCase):
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
                run.native.windows.return_value = {'focused': {'pid': 42, 'title': 'fixture — Mousepad'}, 'windows': []}
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
