import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { load } from 'js-yaml';

const root = path.resolve(import.meta.dirname, '../..');
fs.mkdirSync(path.join(root, 'tmp'), { recursive: true });
const job = load(fs.readFileSync(path.join(root, '.github/workflows/ci.yml'), 'utf8')).jobs['windows-native-contracts'];
const coverage = job.steps.find(step => step.name.startsWith('Windows coverage'));
const check = job.steps.find(step => step.name === 'Require Windows safety class execution');

// Evaluate the workflow's actual outcome condition for both ways coverage can be bypassed,
// and both ways it can run. Reject other expressions so a mutation cannot silently pass.
for (const outcome of ['skipped', 'cancelled', 'success', 'failure']) {
  test(`Windows safety check: coverage ${outcome}`, () => {
    assert.equal(coverage.id, 'windows-coverage');
    assert.equal(check.if, "always() && (steps.windows-coverage.outcome == 'success' || steps.windows-coverage.outcome == 'failure')");
    const condition = check.if.replace('always()', 'true').replaceAll('steps.windows-coverage.outcome', JSON.stringify(outcome));
    const runs = Function(`return ${condition}`)();
    assert.equal(runs, ['success', 'failure'].includes(outcome));
    if (runs) {
      const fixture = fs.mkdtempSync(path.join(root, 'tmp/windows-coverage-missing-'));
      try {
        const result = spawnSync(process.execPath, [path.join(root, 'scripts/ci/report-unit-test-attribution.mjs'),
          '--results-root', fixture, '--require-windows-classes', '--json'], { encoding: 'utf8' });
        assert.equal(result.status, 1, result.stderr);
        assert.match(result.stderr, /missing|no test|did not execute/i);
      } finally { fs.rmSync(fixture, { recursive: true, force: true }); }
    }
  });
}

test('Windows conformance evidence uploads on failure for seven days', () => {
  const upload = job.steps.find(step => step.name === 'Preserve supervisor conformance diagnostics');
  assert.equal(upload.if, 'failure()');
  assert.equal(upload.uses, 'actions/upload-artifact@v7');
  assert.equal(upload.with.path, 'tmp/supervisor-conformance/**');
  assert.equal(upload.with['retention-days'], 7);
  assert.match(upload.with.name, /github.run_attempt/);
});
