"""UI command exit and artifact contracts."""
import json
from click.testing import CliRunner
from jsharness.cli import main

def _ui_check_result(*, ok: bool) -> dict:
    return {
        "schema": "ui-check.v1",
        "ok": ok,
        "elapsed_ms": 12.0,
        "output_dir": "captures",
        "total_shots": 1,
        "total_passed": 1 if ok else 0,
        "shots": [{
            "name": "settings",
            "ok": ok,
            "required": True,
            "elapsed_ms": 12.0,
            **({} if ok else {"error": "required setup failed"}),
        }],
    }


def test_ui_check_required_failure_preserves_output_and_exits_nonzero(monkeypatch, tmp_path):
    from jsharness import ui_check

    monkeypatch.setattr(ui_check, "execute_ui_check", lambda **_kwargs: _ui_check_result(ok=False))
    result = CliRunner().invoke(main, ["ui-check", "--output-dir", str(tmp_path)])

    assert result.exit_code == 1
    assert "UI Check: FAIL (0/1 screenshots)" in result.output
    artifact = json.loads((tmp_path / "ui-check.json").read_text(encoding="utf-8"))
    assert artifact["ok"] is False
    assert artifact["shots"][0]["required"] is True


def test_ui_check_success_preserves_zero_exit(monkeypatch, tmp_path):
    from jsharness import ui_check

    monkeypatch.setattr(ui_check, "execute_ui_check", lambda **_kwargs: _ui_check_result(ok=True))
    result = CliRunner().invoke(main, ["ui-check", "--output-dir", str(tmp_path)])

    assert result.exit_code == 0
    assert "UI Check: PASS (1/1 screenshots)" in result.output
    assert json.loads((tmp_path / "ui-check.json").read_text(encoding="utf-8"))["ok"] is True
