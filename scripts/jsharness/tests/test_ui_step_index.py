"""Validate that every step name in ui_step_index.json exists in the step registry."""

from __future__ import annotations

import json
from pathlib import Path

import pytest


def _load_index() -> dict[str, list[str]]:
    idx = Path(__file__).parent.parent / "jsharness" / "ui_step_index.json"
    return json.loads(idx.read_text(encoding="utf-8"))


def _registry_step_names() -> set[str]:
    from jsharness.ui_check import _build_steps

    steps = _build_steps("http://localhost:5173", cooldown_ms=250, timeout_ms=30_000)
    return {s.name for s in steps}


class TestStepIndex:
    def test_all_index_steps_exist_in_registry(self):
        index = _load_index()
        registry = _registry_step_names()
        missing = set()
        for steps in index.values():
            for s in steps:
                if s not in registry:
                    missing.add(s)
        assert not missing, f"Steps in index but not in registry: {missing}"

    def test_index_is_not_empty(self):
        index = _load_index()
        assert len(index) > 0

    def test_no_empty_step_lists(self):
        index = _load_index()
        empty = [f for f, steps in index.items() if len(steps) == 0]
        assert not empty, f"Files with empty step lists: {empty}"

    def test_no_duplicate_steps_per_file(self):
        index = _load_index()
        for file_path, steps in index.items():
            assert len(steps) == len(set(steps)), (
                f"Duplicate steps for {file_path}: {steps}"
            )


_REPO = Path(__file__).resolve().parents[3]


@pytest.mark.parametrize("register,rows_key", [
    ("governance/ui-proportion-baseline.v1.json", "steps"),
    ("governance/ui-a11y-baseline.v1.json", "surfaces"),
])
def test_every_baseline_row_names_a_registered_step(register, rows_key):
    """A renamed or retired capture must take its baseline rows with it: a gate row whose step the
    registry no longer builds is a capture error at gate time, not a check."""
    rows = json.loads((_REPO / register).read_text(encoding="utf-8")).get(rows_key)
    assert rows, f"{register} has no {rows_key}"
    registry = _registry_step_names()
    unknown = sorted({row["uiShotStep"] for row in rows if row.get("uiShotStep") not in registry})
    assert not unknown, f"{register} names steps the registry does not build: {unknown}"
