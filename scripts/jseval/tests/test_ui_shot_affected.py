"""CLI-to-affected-to-shot option propagation without a browser, server, or backend."""

from __future__ import annotations

import json
from types import SimpleNamespace
from unittest.mock import Mock, call

import pytest
from click.testing import CliRunner

from jseval import ui_shot
from jseval.cli import main


@pytest.fixture
def capture(monkeypatch):
    # Exercise the real affected owner, including separator normalization and deduplication.
    monkeypatch.setattr(ui_shot, "FILE_TO_STEPS", {
        "shell-v0/views/HealthSurface.ts": ["health", "health-light"],
        "views/HealthSurface.ts": ["health"],
    })
    monkeypatch.setattr(ui_shot, "_resolve_ui_url", lambda url: url)
    shot = Mock(side_effect=lambda step_name, **kwargs: {
        "name": step_name, "ok": True, "path": None, "elapsed_ms": 0.0,
    })
    monkeypatch.setattr(ui_shot, "execute_ui_shot", shot)
    return shot


@pytest.mark.parametrize("flags,measure,fixtures,trace,record", [
    ([], True, False, False, False),
    (["--fixtures"], True, True, False, False),
    (["--no-measure"], False, False, False, False),
    (["--trace"], True, False, True, False),
    (["--record"], True, False, False, True),
    (["--fixtures", "--no-measure", "--trace", "--record"], False, True, True, True),
])
def test_affected_cli_preserves_each_capture_option(
    capture, flags, measure, fixtures, trace, record,
):
    result = CliRunner().invoke(main, [
        "--json", "ui-shot", "--affected",
        r"modules\ui-web\src\shell-v0\views\HealthSurface.ts", *flags,
    ])

    assert result.exit_code == 0, result.output
    options = {
        "ui_url": "http://localhost:5173", "output_dir": None, "demo": True,
        "cooldown_ms": 250, "timeout_ms": 30_000,
        "measure": measure, "fixtures": fixtures, "trace": trace, "record": record,
    }
    assert capture.call_args_list == [call("health", **options), call("health-light", **options)]
    assert [row["name"] for row in json.loads(result.output)] == ["health", "health-light"]


def test_affected_cli_keeps_existing_custom_capture_settings(capture, tmp_path):
    output_dir = str(tmp_path / "captures")
    result = CliRunner().invoke(main, [
        "--json", "ui-shot", "--affected", "shell-v0/views/HealthSurface.ts",
        "--fixtures", "--no-demo", "--ui-url", "http://127.0.0.1:5180",
        "--output-dir", output_dir, "--cooldown-ms", "17", "--timeout-ms", "1234",
    ])

    assert result.exit_code == 0, result.output
    options = {
        "ui_url": "http://127.0.0.1:5180", "output_dir": output_dir, "demo": False,
        "cooldown_ms": 17, "timeout_ms": 1234,
        "measure": True, "fixtures": True, "trace": False, "record": False,
    }
    assert capture.call_args_list == [call("health", **options), call("health-light", **options)]


def test_affected_helper_defaults_match_single_shot_defaults(capture):
    ui_shot.execute_ui_shot_affected("shell-v0/views/HealthSurface.ts")
    options = {
        "ui_url": "http://localhost:5173", "output_dir": None, "demo": True,
        "cooldown_ms": 250, "timeout_ms": 30_000,
        "measure": True, "fixtures": False, "trace": False, "record": False,
    }
    assert capture.call_args_list == [call("health", **options), call("health-light", **options)]


def test_named_fixture_variant_fails_before_server_start(monkeypatch):
    monkeypatch.setattr(ui_shot.ui_check, "_build_steps", lambda *_args: [
        SimpleNamespace(name="chat-chip-yield", fixtures_variant="degraded"),
    ])
    monkeypatch.setattr(ui_shot, "_resolve_ui_url", Mock(
        side_effect=AssertionError("fixture validation must precede server startup"),
    ))

    result = CliRunner().invoke(main, ["ui-shot", "chat-chip-yield"])

    assert result.exit_code == 1
    assert "chat-chip-yield" in result.output
    assert "requires --fixtures" in result.output
    ui_shot._resolve_ui_url.assert_not_called()


def test_affected_fixture_variant_fails_before_server_start(monkeypatch):
    monkeypatch.setattr(ui_shot, "FILE_TO_STEPS", {
        "ChatSurface.ts": ["chat-chip-yield"],
    })
    monkeypatch.setattr(ui_shot.ui_check, "_build_steps", lambda *_args: [
        SimpleNamespace(name="chat-chip-yield", fixtures_variant="degraded"),
    ])
    monkeypatch.setattr(ui_shot, "_resolve_ui_url", Mock(
        side_effect=AssertionError("fixture validation must precede server startup"),
    ))

    result = CliRunner().invoke(main, [
        "ui-shot", "--affected", "shell-v0/views/ChatSurface.ts",
    ])

    assert result.exit_code == 1
    assert "chat-chip-yield" in result.output
    assert "requires --fixtures" in result.output
    ui_shot._resolve_ui_url.assert_not_called()


def test_single_failed_shot_prints_diagnostic_and_exits_nonzero(monkeypatch):
    monkeypatch.setattr(ui_shot, "execute_ui_shot", lambda *_args, **_kwargs: {
        "name": "health", "ok": False, "path": None, "elapsed_ms": 0.0,
        "error": "surface did not mount",
    })

    result = CliRunner().invoke(main, ["ui-shot", "health"])

    assert result.exit_code == 1
    assert "FAIL: health -- surface did not mount" in result.output


def test_mixed_affected_batch_prints_each_result_and_exits_nonzero(monkeypatch):
    monkeypatch.setattr(ui_shot, "execute_ui_shot_affected", lambda *_args, **_kwargs: [
        {"name": "settings", "ok": True, "path": "settings.png", "elapsed_ms": 1.0},
        {"name": "settings-light", "ok": False, "path": None, "elapsed_ms": 2.0,
         "error": "selector timed out"},
    ])

    result = CliRunner().invoke(main, ["ui-shot", "--affected", "SettingsSurface.ts"])

    assert result.exit_code == 1
    assert "settings.png" in result.output
    assert "FAIL: settings-light -- selector timed out" in result.output


def test_successful_affected_batch_keeps_zero_exit(monkeypatch):
    monkeypatch.setattr(ui_shot, "execute_ui_shot_affected", lambda *_args, **_kwargs: [
        {"name": "settings", "ok": True, "path": "settings.png", "elapsed_ms": 1.0},
    ])

    result = CliRunner().invoke(main, ["ui-shot", "--affected", "SettingsSurface.ts"])

    assert result.exit_code == 0
    assert "settings.png" in result.output


def test_empty_affected_batch_is_documented_success(monkeypatch):
    monkeypatch.setattr(ui_shot, "execute_ui_shot_affected", lambda *_args, **_kwargs: [])

    result = CliRunner().invoke(main, ["ui-shot", "--affected", "unmapped.ts"])

    assert result.exit_code == 0
    assert result.output.strip() == "No steps affected by this file."
