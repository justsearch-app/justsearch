"""Missing or hidden state must never satisfy a rendered UI promise."""
import json

import pytest

from jseval import ui_proportion_gate as gate


def measure(tmp_path, elements, count=1):
    path = tmp_path / 'state.measure.json'
    path.write_text(json.dumps({'geometry': {'elements': elements, 'scrollableCount': count}}))
    return {'ok': True, 'measure': {'measure_path': str(path)}}


def element(w=300, h=100, visible=True):
    return {'visible': visible, 'rect': {'x': 0, 'y': 0, 'w': w, 'h': h}, 'scrollable': False}


def run(monkeypatch, capture, **checks):
    monkeypatch.setattr(gate, 'load_roles', lambda: {})
    monkeypatch.setattr(gate, 'load_register_steps', lambda: [{'uiShotStep': 'state', **checks}])
    return gate.evaluate(lambda step: capture)


@pytest.mark.parametrize('bad', [element(w=0), element(h=0), element(visible=False), None])
@pytest.mark.parametrize('constraint', ['required', 'ceiling', 'overlap', 'non-scrollable'])
def test_empty_or_hidden_element_cannot_pass(monkeypatch, tmp_path, bad, constraint):
    els = {'.other': {**element(), 'rect': {'x': 400, 'y': 0, 'w': 300, 'h': 100}}}
    if bad is not None:
        els['.promised'] = bad
    checks = {
        'required': {'requiredSelectors': ['.promised']},
        'ceiling': {'elements': [{'selector': '.promised', 'maxHeightPx': 150}]},
        'overlap': {'elements': [{'selector': '.other', 'mustNotOverlapSelector': '.promised'}]},
        'non-scrollable': {'nonScrollableSelectors': ['.promised']},
    }[constraint]
    assert run(monkeypatch, measure(tmp_path, els), **checks)['exit_code'] != 0


def test_absence_needs_mounted_positive_witness(monkeypatch, tmp_path):
    report = run(monkeypatch, measure(tmp_path, {}), absentSelectors=['.obsolete'])
    assert report['exit_code'] == 2
    assert 'witness' in report['rows'][0]['error']
    assert run(monkeypatch, measure(tmp_path, {'.window': element()}),
               absentSelectors=['.obsolete'], requiredSelectors=['.window'])['exit_code'] == 0


def test_promised_scroller_cannot_be_zero(monkeypatch, tmp_path):
    report = run(monkeypatch, measure(tmp_path, {'.window': element()}, count=0),
                 minScrollableRegions=1, maxScrollableRegions=1)
    assert report['exit_code'] == 1
    assert any(row['status'] == 'NO_SCROLLER' for row in report['rows'])


def test_expansion_floor_still_distinguishes_positive_collapsed_card(monkeypatch, tmp_path):
    checks = {'elements': [{'selector': '.activity', 'minHeightPx': 100, 'maxHeightPx': 220}]}
    assert run(monkeypatch, measure(tmp_path, {'.activity': element(h=43)}), **checks)['exit_code'] == 1
    assert run(monkeypatch, measure(tmp_path, {'.activity': element(h=217)}), **checks)['exit_code'] == 0
