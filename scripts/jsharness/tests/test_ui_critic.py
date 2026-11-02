"""Tests for the REASON design-critic (tempdoc 615 §11 REASON).

Pin the prompt assembly (facts + reference are present) and the tolerant critique
parse (model JSON extracted; non-JSON never crashes) — both without a model/stack.
"""
from __future__ import annotations

from jsharness import ui_critic


def _measure():
    return {
        "a11y_landmarks": [
            {"tag": "h1", "role": None, "label": None, "text": "Search"},
            {"tag": "div", "role": "main", "label": "Main content", "text": ""},
        ],
        "axe": {"violations": [{"id": "aria-valid-attr-value", "impact": "critical"}]},
        "geometry": {"elements": {"h1": {"rect": {"x": 52, "y": 40, "w": 100, "h": 28}}},
                     "document": {"overflowX": False, "overflowY": False}},
    }


class TestCompactFacts:
    def test_extracts_landmarks_axe_overflow(self):
        f = ui_critic.compact_facts(_measure())
        assert {"role": "main", "tag": "div", "label": "Main content", "text": ""} in f["landmarks"]
        assert f["axe"] == [{"id": "aria-valid-attr-value", "impact": "critical"}]
        assert f["overflow"] == {"x": False, "y": False}


class TestAssemblePrompt:
    def test_prompt_carries_reference_and_facts(self):
        ref = ui_critic.load_reference()
        prompt = ui_critic.assemble_prompt(_measure(), ref, "search")
        assert "rubric" in prompt.lower()
        assert "Search" in prompt  # the h1 fact
        assert "aria-valid-attr-value" in prompt  # the axe fact
        assert "JSON object" in prompt  # the output contract

    def test_reference_loads_with_foundation_and_rubric(self):
        ref = ui_critic.load_reference()
        assert ref["foundation"]["spacingGridPx"] == 4
        assert any(r["id"] == "landmark-completeness" for r in ref["rubric"])


class TestParseCritique:
    def test_extracts_json_issues(self):
        reply = 'Here is my critique:\n{"conforms": false, "issues": [{"rubricId": "heading-order", "severity": "high", "evidence": "h1 then h3"}]}\nDone.'
        out = ui_critic.parse_critique(reply)
        assert out["conforms"] is False
        assert out["issues"][0]["rubricId"] == "heading-order"

    def test_conforms_defaults_from_empty_issues(self):
        out = ui_critic.parse_critique('{"issues": []}')
        assert out["conforms"] is True

    def test_non_json_reply_is_wrapped_not_crashed(self):
        out = ui_critic.parse_critique("I cannot produce JSON.")
        assert out["parseError"] is True
        assert out["issues"] == []


class TestCritiqueLoop:
    def test_critique_with_injected_model(self, tmp_path):
        import json
        mp = tmp_path / "home.measure.json"
        mp.write_text(json.dumps(_measure()), encoding="utf-8")
        cap = lambda step: {"ok": True, "measure": {"measure_path": str(mp)}}
        model = lambda prompt: '{"conforms": true, "issues": []}'
        out = ui_critic.critique("home", "search", cap, model)
        assert out["conforms"] is True
        assert out["surface"] == "search" and out["step"] == "home"
