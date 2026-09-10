/**
 * Tempdoc 952 — unit bite for worktree-release's decision logic (`decideRelease`).
 * The I/O wrapper `main()` is not invoked on import.
 *
 * Run with: `node scripts/agent-analytics/hooks/worktree-release.test.mjs`
 */

import assert from 'node:assert/strict';
import { decideRelease } from './worktree-release.mjs';

const ROOT = 'F:/repo';
const CTX = { sanctionedRoot: 'F:/repo/.claude/worktrees', mainRoot: ROOT };
const base = { hook_event_name: 'SessionEnd', session_id: 'sess-1', cwd: 'F:\\repo\\.claude\\worktrees\\lane' };

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

run('Claude logout inside a managed worktree → record-only release, own, no fetch', () => {
  const d = decideRelease({ ...base, end_reason: 'logout' }, CTX);
  assert.equal(d.action, 'release');
  assert.equal(d.harness, 'claude');
  assert.deepEqual(d.args, ['release', base.cwd, '--own', '--session-id', 'sess-1', '--no-fetch', '--record-only']);
});

run('Claude prompt_input_exit releases; clear, resume, other and missing do not', () => {
  assert.equal(decideRelease({ ...base, end_reason: 'prompt_input_exit' }, CTX).action, 'release');
  for (const r of ['clear', 'resume', 'other', undefined]) {
    const d = decideRelease({ ...base, end_reason: r }, CTX);
    assert.equal(d.action, 'skip', `end_reason ${r}`);
  }
});

run('Codex session end → full if-clean release (harness has no exit path of its own)', () => {
  const d = decideRelease({ ...base, harness: 'codex-cli' }, CTX);
  assert.equal(d.action, 'release');
  assert.equal(d.harness, 'codex');
  assert.ok(d.args.includes('--if-clean') && !d.args.includes('--record-only'));
});

run('session ended in the main checkout → skip', () => {
  assert.equal(decideRelease({ ...base, end_reason: 'logout', cwd: ROOT }, CTX).action, 'skip');
});

run('cwd outside the sanctioned root → skip (unmanaged trees are never touched)', () => {
  assert.equal(decideRelease({ ...base, end_reason: 'logout', cwd: 'F:/justsearch-worktrees/919-splade' }, CTX).action, 'skip');
  assert.equal(decideRelease({ ...base, end_reason: 'logout', cwd: 'F:/repo/.claude/worktrees-evil/x' }, CTX).action, 'skip');
});

run('missing session id or cwd, or a different event → skip, never throw', () => {
  assert.equal(decideRelease({ ...base, end_reason: 'logout', session_id: '' }, CTX).action, 'skip');
  assert.equal(decideRelease({ ...base, end_reason: 'logout', cwd: '' }, CTX).action, 'skip');
  assert.equal(decideRelease({ ...base, hook_event_name: 'Stop', end_reason: 'logout' }, CTX).action, 'skip');
  assert.equal(decideRelease(null, CTX).action, 'skip');
});

if (failures.length) {
  console.error(`worktree-release.test: ${failures.length} FAILED / ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`worktree-release.test: ${passed} passed`);
