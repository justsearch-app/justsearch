"""The registered hero-recent capture must reject overlap and a lost last row."""
import json

import unittest
from tempfile import TemporaryDirectory
from pathlib import Path
from unittest.mock import patch

from jsharness import ui_proportion_gate

RECENTS = '[data-testid="sv3-recents"]'
LAST = RECENTS + ' li:last-child button'
FIELD = '[data-testid="sv3-composer-shell"]'
HEADLINE = '[data-testid="sv3-composer-headline"]'


class RecentProportionRepairs(unittest.TestCase):
    def test_registered_hero_recents_reject_broken_layout(self):
        for failure in ['field-overlap', 'text-overlap', 'last-missing', 'last-clipped']:
            with self.subTest(failure=failure), TemporaryDirectory() as temporary:
                self.check_layout(Path(temporary), failure)

    def check_layout(self, tmp_path, failure):
        steps = ui_proportion_gate.load_register_steps()
        step = next(s for s in steps if s['uiShotStep'] == 'sv3-recents-hero-normal-dark')
        with patch.object(ui_proportion_gate, 'load_register_steps', return_value=[step]), \
             patch.object(ui_proportion_gate, 'load_roles', return_value={}):
            self.check_gate(tmp_path, failure)

    def check_gate(self, tmp_path, failure):
        def rect(y, h):
            return {'visible': True, 'scrollable': False, 'rect': {'x': 100, 'y': y, 'w': 500, 'h': h}}

        elements = {FIELD: rect(150, 60), HEADLINE: rect(70, 40), RECENTS: rect(240, 300),
                    LAST: rect(500, 35), 'jf-top-bar': rect(0, 44),
                    '[data-testid="sv3-recent-clear"]': rect(240, 30)}
        measure = tmp_path / 'recents.measure.json'

        def capture(_step):
            measure.write_text(json.dumps({'geometry': {'elements': elements}}))
            return {'ok': True, 'measure': {'measure_path': str(measure)}}

        good = ui_proportion_gate.evaluate(capture)
        self.assertEqual(good['exit_code'], 0, good)
        if failure == 'field-overlap':
            elements[RECENTS] = rect(160, 300)
        elif failure == 'text-overlap':
            elements[RECENTS] = rect(80, 300)
        elif failure == 'last-missing':
            del elements[LAST]
        else:
            elements[LAST] = rect(790, 35)
        report = ui_proportion_gate.evaluate(capture)
        self.assertNotEqual(report['exit_code'], 0, report)
        self.assertTrue(any(row['status'] in ['OVERLAPS', 'CLIPPED', 'MISSING_REQUIRED'] for row in report['rows']), report)
