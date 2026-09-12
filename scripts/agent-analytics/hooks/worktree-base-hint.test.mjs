/**
 * Tempdoc 727 F-3 / 940 — unit tests for worktree-base-hint's decision logic.
 *
 * Exercises the pure `buildWorktreeBaseNotes(...)` function directly (the I/O wrapper
 * `main()`, which shells out to git, is not invoked on import).
 *
 * Run with: `node scripts/agent-analytics/hooks/worktree-base-hint.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { buildWorktreeBaseNotes } from './worktree-base-hint.mjs';

let passed = 0;
const failures = [];

function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

run('worktree at origin/main, main not ahead, no uncommitted changes → silent', () => {
  const r = buildWorktreeBaseNotes({
    worktreeHead: 'abc123', originMainHead: 'abc123', mainAheadCount: 0, changes: [],
  });
  assert.equal(r, null);
});

run('worktree HEAD differs from origin/main → base-mismatch note naming the fetch remedy', () => {
  const r = buildWorktreeBaseNotes({
    worktreeHead: 'aaa111', originMainHead: 'bbb222', mainAheadCount: 0, changes: [],
  });
  assert.ok(r && /base mismatch/i.test(r) && r.includes('git fetch origin main'));
});

run('local main ahead of origin/main → STOP note with the count (tempdoc 940)', () => {
  const r = buildWorktreeBaseNotes({
    worktreeHead: 'abc123', originMainHead: 'abc123', mainAheadCount: 297, changes: [],
  });
  assert.ok(r && /^STOP:/.test(r) && r.includes('297 commit(s) AHEAD'));
  assert.ok(r.includes('never-commit-on-local-main'), 'expected the rule handle');
  // The worktree itself is fine in this case — no false base-mismatch claim.
  assert.ok(!/base mismatch/i.test(r));
});

run('main ahead AND worktree behind → STOP note first, then the mismatch note', () => {
  const r = buildWorktreeBaseNotes({
    worktreeHead: 'aaa111', originMainHead: 'bbb222', mainAheadCount: 3, changes: [],
  });
  assert.ok(r && r.indexOf('STOP:') === 0 && r.indexOf('STOP:') < r.indexOf('base mismatch'));
});

run('main ahead count unknown (git failed) → no false STOP', () => {
  const r = buildWorktreeBaseNotes({
    worktreeHead: 'abc', originMainHead: 'abc', mainAheadCount: null, changes: [],
  });
  assert.equal(r, null);
});

run('matching HEADs, main has uncommitted changes → FYI note (tempdoc 727 F-3)', () => {
  const r = buildWorktreeBaseNotes({
    worktreeHead: 'abc123',
    originMainHead: 'abc123',
    mainAheadCount: 0,
    changes: ['M docs/tempdocs/654-direction-note.md'],
  });
  assert.ok(r && /uncommitted change/i.test(r) && r.includes('654-direction-note.md'));
  // Neutral FYI framing, not an alarm (branch-safety.md documents shared-main WIP as normal).
  assert.ok(!/error|blocked|failed|STOP/.test(r), 'expected neutral framing, not alarm language');
});

run('both a HEAD mismatch AND uncommitted changes → both notes present', () => {
  const r = buildWorktreeBaseNotes({
    worktreeHead: 'aaa111',
    originMainHead: 'bbb222',
    mainAheadCount: 0,
    changes: ['M some/file.md'],
  });
  assert.ok(r && /base mismatch/i.test(r) && /uncommitted change/i.test(r));
});

run('more than 8 uncommitted changes → truncated with a "+N more" tail', () => {
  const changes = Array.from({ length: 12 }, (_, i) => `M file${i}.md`);
  const r = buildWorktreeBaseNotes({ worktreeHead: 'abc', originMainHead: 'abc', mainAheadCount: 0, changes });
  assert.ok(r && r.includes('+4 more'));
});

run('missing HEAD values (git call failed / no remote) → no false mismatch claim', () => {
  const r = buildWorktreeBaseNotes({ worktreeHead: null, originMainHead: 'abc123', mainAheadCount: 0, changes: [] });
  assert.equal(r, null);
  const r2 = buildWorktreeBaseNotes({ worktreeHead: 'abc123', originMainHead: null, mainAheadCount: null, changes: [] });
  assert.equal(r2, null);
});

run('empty changes array (not just falsy) → silent on that axis', () => {
  const r = buildWorktreeBaseNotes({ worktreeHead: 'abc', originMainHead: 'abc', mainAheadCount: 0, changes: [] });
  assert.equal(r, null);
});

// --- Report ---
if (failures.length > 0) {
  console.error(`worktree-base-hint.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`worktree-base-hint.test: all ${passed} checks passed`);
