/**
 * Tempdoc 952 — unit bite for worktree-register's decision logic (`decideRegistration`).
 * The I/O wrapper `main()` is not invoked on import.
 *
 * Run with: `node scripts/agent-analytics/hooks/worktree-register.test.mjs`
 */

import assert from 'node:assert/strict';
import { decideRegistration } from './worktree-register.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

run('WorktreeCreate with worktree_path → register with the session and claude harness', () => {
  const d = decideRegistration({ hook_event_name: 'WorktreeCreate', session_id: 's1', worktree_path: 'F:/r/.claude/worktrees/x' });
  assert.equal(d.action, 'register');
  assert.equal(d.worktreePath, 'F:/r/.claude/worktrees/x');
  assert.equal(d.sessionId, 's1');
  assert.equal(d.harness, 'claude');
});

run('WorktreeCreate without worktree_path → skip', () => {
  assert.equal(decideRegistration({ hook_event_name: 'WorktreeCreate', session_id: 's1' }).action, 'skip');
});

run('PostToolUse EnterWorktree uses tool_response.worktreePath', () => {
  const d = decideRegistration({ hook_event_name: 'PostToolUse', tool_name: 'EnterWorktree', session_id: 's2', tool_response: { worktreePath: '/r/.claude/worktrees/y' } });
  assert.equal(d.action, 'register');
  assert.equal(d.worktreePath, '/r/.claude/worktrees/y');
});

run('PostToolUse for another tool → skip', () => {
  assert.equal(decideRegistration({ hook_event_name: 'PostToolUse', tool_name: 'Bash', session_id: 's2' }).action, 'skip');
});

run('Codex adapter payload → codex harness', () => {
  const d = decideRegistration({ hook_event_name: 'WorktreeCreate', harness: 'codex-cli', session_id: 'c1', worktree_path: '/r/.claude/worktrees/z' });
  assert.equal(d.harness, 'codex');
});

run('other events and empty input → skip, never throw', () => {
  assert.equal(decideRegistration({ hook_event_name: 'SessionStart' }).action, 'skip');
  assert.equal(decideRegistration(null).action, 'skip');
  assert.equal(decideRegistration('x').action, 'skip');
});

if (failures.length) {
  console.error(`worktree-register.test: ${failures.length} FAILED / ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`worktree-register.test: ${passed} passed`);
