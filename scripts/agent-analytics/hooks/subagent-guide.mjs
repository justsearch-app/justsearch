#!/usr/bin/env node

/**
 * Synchronous SubagentStart hook — injects codebase-specific guidance.
 *
 * Emits additionalContext with project-specific knowledge that subagents
 * wouldn't otherwise have. Generic rules (use Read not cat) are already
 * in the system prompt — this adds value beyond that.
 *
 * - Synchronous (async: false) — blocks until it returns
 * - Timeout: 5s
 * - Always exits 0 — never blocks subagent creation
 */

import { hardInvariants } from '../lib/hard-invariants.mjs';
import { isDirectRun } from '../lib/hook-base.mjs';

/**
 * Self-imposed budget for the brief's `additionalContext` (no harness-enforced
 * cap is documented; ~10K is the house limit tempdoc 935 D2 cites). The brief is
 * deliberately minimal; the constant exists so the budget is asserted by a test
 * rather than only by a comment nobody re-measures after adding a line.
 */
export const GUIDANCE_CHAR_CAP = 10000;

export function buildGuidance(input = {}) {
  const sessionId = typeof input.session_id === 'string' && input.session_id.trim()
    ? input.session_id.trim()
    : null;
  const platformLine = process.platform === 'win32'
    ? 'Windows Git Bash. Use forward slashes and /dev/null, not NUL.'
    : `${process.platform}.`;
  const isCodex = process.env.JUSTSEARCH_AGENT_HARNESS === 'codex-cli';

  // Claude Explore/Plan omit project instructions; the manifest targets those
  // roles. Forks inherit parent history. Client hooks and instruction delivery
  // are distinct; repository subprocess tests do not prove runtime loading.
  // See docs/reference/contributing/agent-workflow.md (checked 2026-09-08).

  const sections = [];

  sections.push(
    isCodex
      ? '## JustSearch — Codex subagent baseline brief (AGENTS.md remains the project authority; read it and the governing design before work)'
      : '## JustSearch — subagent baseline brief (for roles selected by the hook binding; read AGENTS.md and the governing design before work)',
  );

  // Projected LIVE from AGENTS.md's hard invariants (single authority — never
  // hand-copy; a hand-copy silently drifted to 4-of-6 before tempdoc 620 Part V).
  const invariants = hardInvariants();
  if (invariants.length) {
    sections.push(
      '### Hard invariants (do not violate) — projected from AGENTS.md',
      ...invariants.map((t, i) => `${i + 1}. ${t}`),
    );
  } else {
    sections.push('Project invariants could not be loaded. Read AGENTS.md before editing; report missing instructions to the parent.');
  }

  sections.push(
    '### Agent discipline',
    '- Fix root causes, not symptoms. Never comment out failing code, weaken assertions, @Disabled tests, or broaden catches to silence failures.',
    '- If a test fails after your changes, the test is probably right and your code is wrong.',
    '- Explore existing helpers before creating new ones. The most common mistake is reinventing utilities that exist two packages over.',
    '- Do not introduce backwards-compatibility shims, dead-code comments, or speculative abstractions.',
    '- Default to writing no comments. Only add WHY-comments for non-obvious invariants.',
    '- Execute synchronously end-to-end within your turns: use bounded in-turn condition-polls for waits; NEVER stop your turn to "wait for" an external event or monitor — use the available wait/resume tools; do not present an unmonitored stop as continuing work.',
  );

  if (isCodex) {
    sections.push(
      '### Codex subagent risk profile',
      '- Do not assume parent conversation details were inherited; the parent brief and AGENTS.md are the contract.',
      '- Repository hooks are guardrails, not an enforcement boundary. Never use destructive git even if a tool path bypasses a hook.',
      '- Stay in the assigned worktree and within the role sandbox. Do not merge, publish, or take over the shared dev stack.',
    );
  } else {
    sections.push(
      '### Subagent-specific risk profile',
      '- Session-wide Claude tool hooks apply inside subagents; actual coverage depends on client configuration, trust, and matchers. Never assume a safety boundary from prose.',
      '- Do not run destructive git or modify files outside the assigned worktree and scope. The parent owns publication and shared-state decisions.',
      '- Read large files in bounded sections. If repeated reads or correction rounds stop making progress, return the unresolved cause to the parent.',
    );
  }

  sections.push(
    '### Out-of-scope findings protocol (tempdoc 872 — there is no inbox)',
    'If you notice a pre-existing issue outside your task scope: a wrong doc/comment with a verified one-line fix -> fix it in place (ride-along). Anything else -> report it in your final result with `file:line` so the orchestrator routes it (a red/flaky command on main is fixed or quarantined, never remembered; a hook or agent-lessons.md for a platform lesson; the owning tempdoc for a defect). Do not investigate further and do not call note-observation.mjs — it no longer writes.',
  );

  sections.push(
    '### Tooling pointers',
    `- Platform: ${platformLine}`,
    '- Use Grep files_with_matches first to find which files to read; then targeted Read.',
    '- Docs map: docs/llms.txt. Architecture overview: docs/explanation/01-system-overview.md.',
    '- Build: `./gradlew.bat build -x test` (compile only) before declaring done.',
    '- Format: `./gradlew.bat spotlessApply` after Java edits.',
    '- Pipeline profiling: `python -m jseval` (NEVER raw `gradlew runHeadless &` + `sleep` loops).',
    '- Don\'t use bare `sleep` to wait on a backend; use a bounded condition-poll, or jseval for backend lifecycle.',
    // Execution hygiene (tempdoc 935 D2) — each line targets an observed subagent
    // rerun of an expensive command. Keep to three; the brief is deliberately minimal.
    '- A command piped into `tail`/`head`/`grep` reports the PIPE\'s exit status, not the command\'s. Run it bare or `set -o pipefail` the first time — never rerun a long build/test just to recover its exit status.',
    '- Search with `rg` or `git grep` (both gitignore-aware); never `grep -r` or `find` from the repo root — they walk build output, node_modules and model blobs, and that walk is the cost.',
    '- Send an expensive tool\'s output to an explicit absolute path, then read that file and its actual schema before parsing — guessing field names and re-running the tool is the most expensive rerun there is.',
  );

  sections.push(
    '### Reporting',
    'Keep the deliverable stable; return material scope growth or lifecycle/concurrency ambiguity to the parent before continuing. Report acceptance items, tested revision, environment, actual results, and missing proof. CI wiring is not a successful run.',
    'Stop after answering what was asked. Don\'t gold-plate. Return a concise summary; the parent relays it to the user.',
  );

  if (sessionId) {
    sections.push(
      '### Session attribution',
      `If you invoke workflow wrappers/DAGs that take --session-id, pass: ${sessionId}`,
    );
  }

  return sections.join('\n');
}

async function main() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);

  const raw = Buffer.concat(chunks).toString('utf8').trim();
  if (!raw) return;
  let input = {};
  try {
    input = JSON.parse(raw);
  } catch {
    input = {};
  }

  process.stdout.write(JSON.stringify({
    hookSpecificOutput: {
      hookEventName: 'SubagentStart',
      additionalContext: buildGuidance(input),
    },
  }));
}

// Direct-run guard so the test can import `buildGuidance` without main() parking
// on an stdin that the test runner never closes (spawnSync pipes it, never ends it).
if (isDirectRun(import.meta.url)) main().catch(() => process.exit(0));
