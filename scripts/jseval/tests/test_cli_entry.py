"""Tempdoc 716 Tax 2: the cross-checkout refusal at the CLI entry point.

An editable install / stale PYTHONPATH pins `import jseval` to one checkout;
invoking from a different worktree used to silently execute the other
checkout's code. `main()` now refuses with the PYTHONPATH remedy inline
(escape hatch: JUSTSEARCH_ALLOW_CROSS_CHECKOUT_JSEVAL=1).
"""

from __future__ import annotations

import os
from pathlib import Path

from click.testing import CliRunner

import jseval._paths as _paths
import jseval.cli as cli_mod
from jseval.cli import main


def _fake_checkout(tmp_path: Path, name: str) -> Path:
    root = tmp_path / name
    (root / ".git").mkdir(parents=True)
    (root / "scripts" / "jseval").mkdir(parents=True)
    return root


class TestCrossCheckoutRefusal:
    def test_mismatch_refuses_with_remedy(self, tmp_path, monkeypatch):
        other = _fake_checkout(tmp_path, "other-checkout")
        invoking = _fake_checkout(tmp_path, "invoking-checkout")
        monkeypatch.setattr(_paths, "module_checkout_root", lambda: other)
        monkeypatch.setattr(_paths, "cwd_checkout_root", lambda: invoking)
        monkeypatch.delenv("JUSTSEARCH_ALLOW_CROSS_CHECKOUT_JSEVAL", raising=False)

        result = CliRunner().invoke(main, ["modes"])

        assert result.exit_code != 0
        assert str(other) in result.output
        assert str(invoking) in result.output
        # The remedy must be inline and exact — the paper-cut's manual fix,
        # delivered at the moment of failure instead of via tribal knowledge.
        both = os.pathsep.join(str(invoking / "scripts" / name) for name in ("jseval", "jsharness"))
        assert f"PYTHONPATH={both} python -m jseval" in result.output
        assert "JUSTSEARCH_ALLOW_CROSS_CHECKOUT_JSEVAL" in result.output

    def test_escape_hatch_env_allows_cross_checkout(self, tmp_path, monkeypatch):
        other = _fake_checkout(tmp_path, "other-checkout")
        invoking = _fake_checkout(tmp_path, "invoking-checkout")
        monkeypatch.setattr(_paths, "module_checkout_root", lambda: other)
        monkeypatch.setattr(_paths, "cwd_checkout_root", lambda: invoking)
        monkeypatch.setenv("JUSTSEARCH_ALLOW_CROSS_CHECKOUT_JSEVAL", "1")

        result = CliRunner().invoke(main, ["modes"])
        assert result.exit_code == 0, result.output

    def test_matching_checkout_proceeds(self, tmp_path, monkeypatch):
        # The common case must not false-positive — same root on both sides.
        root = _fake_checkout(tmp_path, "one-checkout")
        monkeypatch.setattr(_paths, "module_checkout_root", lambda: root)
        monkeypatch.setattr(_paths, "cwd_checkout_root", lambda: root)
        monkeypatch.delenv("JUSTSEARCH_ALLOW_CROSS_CHECKOUT_JSEVAL", raising=False)

        result = CliRunner().invoke(main, ["modes"])
        assert result.exit_code == 0, result.output

    def test_outside_any_checkout_proceeds(self, monkeypatch):
        # Invoking from a non-JustSearch CWD (e.g. a scratch dir): nothing to
        # cross-check; jseval's tempdoc-351 REPO_ROOT fallback handles paths.
        monkeypatch.setattr(_paths, "cwd_checkout_root", lambda: None)
        monkeypatch.delenv("JUSTSEARCH_ALLOW_CROSS_CHECKOUT_JSEVAL", raising=False)

        result = CliRunner().invoke(main, ["modes"])
        assert result.exit_code == 0, result.output

    def test_real_environment_is_self_consistent(self):
        # Live sanity: the suite itself runs with PYTHONPATH pointing at this
        # checkout, so the real helpers must agree (guards the helpers from
        # drifting apart in a way the mocked tests above would not catch).
        cli_mod._assert_matching_checkout()
