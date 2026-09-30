#!/usr/bin/env python3
"""Self-tests for sandbox-launch.py's dual-harness entry-point staging
(tempdoc 939).

The graders (check_coverage.py & co.) are harness-agnostic -- they only read
evidence files -- but the in-sandbox agent's ENTRY POINTS are not: Claude Code
reads CLAUDE.md + .claude/, Codex reads AGENTS.md + a trusted .codex/config.toml
+ .agents/skills/. Before 939 the share staged only the Claude half, so a Codex
session opened on the mapped folder had no charter, no permissions setup and no
`$start`. These tests pin the Codex half and the one fail-closed check in it:
Codex silently stops reading AGENTS.md at `project_doc_max_bytes`, so the value
written to the staged config must exceed the staged charter's size.

Loaded via importlib from the hyphenated filename, mirroring the sibling
test_sandbox_launch_*.py suites. Never calls main().

Run: python scripts/sandbox/test_sandbox_launch_codex.py
"""

from __future__ import annotations

import importlib.util
import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent

_spec = importlib.util.spec_from_file_location(
    "sandbox_launch_codex_under_test", SCRIPT_DIR / "sandbox-launch.py"
)
sandbox_launch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sandbox_launch)  # type: ignore[union-attr]

stage_charter_entry_points = sandbox_launch.stage_charter_entry_points
stage_codex_settings = sandbox_launch.stage_codex_settings
render_codex_config = sandbox_launch.render_codex_config
CODEX_PROJECT_DOC_MAX_BYTES = sandbox_launch.CODEX_PROJECT_DOC_MAX_BYTES
CHARTER_ENTRY_POINT_FILENAMES = sandbox_launch.CHARTER_ENTRY_POINT_FILENAMES


def _stage_all(share_dir: Path) -> None:
    with redirect_stdout(io.StringIO()):
        stage_charter_entry_points(share_dir)
        stage_codex_settings(share_dir)


class CharterEntryPointTests(unittest.TestCase):
    def test_charter_staged_under_both_harness_names_with_identical_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            share = Path(tmp)
            _stage_all(share)
            self.assertEqual(CHARTER_ENTRY_POINT_FILENAMES, ("CLAUDE.md", "AGENTS.md"))
            contents = {name: (share / name).read_bytes() for name in CHARTER_ENTRY_POINT_FILENAMES}
            self.assertEqual(
                contents["CLAUDE.md"], contents["AGENTS.md"],
                "CLAUDE.md and AGENTS.md must be the same bytes -- one charter, two entry-point names",
            )
            self.assertEqual(
                contents["CLAUDE.md"], (SCRIPT_DIR / "sandbox-CLAUDE.md").read_bytes(),
                "staged charter must be the sandbox charter verbatim",
            )

    def test_real_charter_fits_under_the_cap_the_staged_config_declares(self):
        # The load-bearing assertion: the constant written into .codex/config.toml
        # must exceed the real charter, or Codex truncates it in the guest.
        size = (SCRIPT_DIR / "sandbox-CLAUDE.md").stat().st_size
        self.assertLess(
            size, CODEX_PROJECT_DOC_MAX_BYTES,
            f"sandbox-CLAUDE.md ({size} B) outgrew CODEX_PROJECT_DOC_MAX_BYTES "
            f"({CODEX_PROJECT_DOC_MAX_BYTES}); raise the constant or split the charter",
        )
        # And it is genuinely over Codex's default, i.e. the raised cap is needed,
        # not decorative -- if this ever flips, the cap line can go.
        self.assertGreater(size, 32 * 1024)


class CodexSettingsTests(unittest.TestCase):
    def test_stages_project_config_and_start_skill(self):
        with tempfile.TemporaryDirectory() as tmp:
            share = Path(tmp)
            _stage_all(share)
            cfg = share / ".codex" / "config.toml"
            skill = share / ".agents" / "skills" / "start" / "SKILL.md"
            self.assertTrue(cfg.is_file())
            self.assertTrue(skill.is_file())
            self.assertEqual(
                skill.read_bytes(), (SCRIPT_DIR / "sandbox-start-SKILL.md").read_bytes(),
                "Codex gets the same sandbox-aware start skill Claude gets ($start == /start)",
            )

    def test_config_is_credential_free_and_self_contained(self):
        text = render_codex_config()
        # Same philosophy as stage_claude_settings stripping hooks: nothing in
        # the staged config may point at repo tooling that is not in the share.
        self.assertNotIn("mcp_servers", text)
        self.assertNotIn("scripts/dev", text)
        self.assertNotIn("required = true", text)
        for secret_key in ("api_key", "OPENAI_API_KEY", "auth"):
            self.assertNotIn(secret_key, text)
        # The bypassPermissions counterpart.
        self.assertIn('approval_policy = "never"', text)
        self.assertIn('sandbox_mode = "danger-full-access"', text)
        # The cap written is the constant asserted against.
        self.assertIn(f"project_doc_max_bytes = {CODEX_PROJECT_DOC_MAX_BYTES}", text)
        self.assertIn('model = "gpt-6.1-sol"', text)
        self.assertIn("[computer_use.windows]", text)
        self.assertIn('"JustSearch.exe"', text)

    def test_oversized_agents_md_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            share = Path(tmp)
            (share / "AGENTS.md").write_bytes(b"x" * (CODEX_PROJECT_DOC_MAX_BYTES + 1))
            with redirect_stdout(io.StringIO()) as out, self.assertRaises(SystemExit) as ctx:
                stage_codex_settings(share)
            self.assertEqual(ctx.exception.code, 1)
            self.assertIn("silently truncate", out.getvalue())

    def test_repo_codex_config_is_not_what_gets_staged(self):
        # Guard against a future "simplification" that copies .codex/config.toml
        # from the checkout: that file declares a required MCP server that does
        # not exist in the sandbox and would fail every Codex session start.
        repo_cfg = SCRIPT_DIR.parent.parent / ".codex" / "config.toml"
        if not repo_cfg.is_file():
            self.skipTest("repo .codex/config.toml absent")
        self.assertIn("mcp_servers", repo_cfg.read_text(encoding="utf-8"))
        self.assertNotEqual(render_codex_config(), repo_cfg.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
