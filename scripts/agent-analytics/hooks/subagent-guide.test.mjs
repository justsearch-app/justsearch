/**
 * Tempdoc 935 D2 — unit tests for the subagent baseline brief.
 *
 * Exercises the pure `buildGuidance(...)` directly. The module's `main()` I/O
 * wrapper is behind an `isDirectRun` guard, so importing it here does not park
 * on stdin.
 *
 * The two properties under test are the ones that silently rot:
 *   1. The three execution-hygiene lines (935 D2) are actually IN the brief —
 *      a brief is only a delivery surface for what it still contains.
 *   2. The brief stays under the hook output cap. Every future line pushes on
 *      this, and nobody re-measures a cap that only lives in a comment.
 *
 * Run with: `node scripts/agent-analytics/hooks/subagent-guide.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { buildGuidance, GUIDANCE_CHAR_CAP } from './subagent-guide.mjs';

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

run('brief carries the pipefail / exit-status hygiene line (935 D2)', () => {
  const g = buildGuidance({});
  assert.ok(/set -o pipefail/.test(g), 'expected `set -o pipefail` in the brief');
  // Right reason: it must say WHY (the pipe reports its own status), not merely
  // mention the flag, and it must name the rerun it exists to prevent.
  assert.ok(/pipe/i.test(g) && /exit status/i.test(g), 'expected the masked-exit rationale');
  assert.ok(/never rerun/i.test(g), 'expected the "do not rerun to recover status" instruction');
});

run('brief carries the gitignore-aware search line (935 D2)', () => {
  const g = buildGuidance({});
  assert.ok(/`rg`/.test(g) && /`git grep`/.test(g), 'expected rg / git grep to be named');
  assert.ok(/grep -r/.test(g) && /`find`/.test(g), 'expected the grep -r / find anti-pattern named');
  assert.ok(/gitignore-aware/i.test(g), 'expected the gitignore-aware rationale');
});

run('brief carries the artifact-path / read-the-schema line (935 D2)', () => {
  const g = buildGuidance({});
  assert.ok(/absolute path/i.test(g), 'expected the explicit-absolute-path instruction');
  assert.ok(/schema/i.test(g), 'expected the read-the-actual-schema instruction');
});

run('all three hygiene lines are present together, and only three were added', () => {
  const lines = buildGuidance({}).split('\n');
  const hygiene = lines.filter(
    (l) => /set -o pipefail/.test(l) || /gitignore-aware/.test(l) || /absolute path/i.test(l),
  );
  assert.equal(hygiene.length, 3, `expected exactly 3 hygiene lines, got ${hygiene.length}`);
});

run('brief stays under the hook output cap', () => {
  const g = buildGuidance({ session_id: 'x'.repeat(64) });
  assert.ok(
    g.length < GUIDANCE_CHAR_CAP,
    `brief is ${g.length} chars, cap is ${GUIDANCE_CHAR_CAP}`,
  );
});

run('cap check is not vacuous — the brief is substantial', () => {
  // Guards the wrong-reason pass: a cap assertion over an empty string is green
  // and meaningless. The brief has always been multiple KB.
  const g = buildGuidance({});
  assert.ok(g.length > 2000, `brief collapsed to ${g.length} chars`);
});

run('session id is threaded into the attribution section when present', () => {
  assert.ok(buildGuidance({ session_id: 'sess-abc' }).includes('sess-abc'));
  assert.ok(!/Session attribution/.test(buildGuidance({})));
});

if (failures.length > 0) {
  console.error(`subagent-guide.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`subagent-guide.test: all ${passed} checks passed`);
