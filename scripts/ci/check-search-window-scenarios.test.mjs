#!/usr/bin/env node
/** Durable contract tests for catalog freshness, explicit gaps, and lossless scenario retirement. */
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync, spawnSync } from 'node:child_process';
import { CATALOG, checkSearchWindowScenarios, readBaseCatalog, testTitles } from './check-search-window-scenarios.mjs';
import { parseUiWebGateCommands } from './run-ui-web-gates.mjs';

const ROOT = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const root = mkdtempSync(join(tmpdir(), 'search-window-scenarios-'));
let passed = 0;
const failures = [];
function test(label, fn) {
  try { fn(); passed++; } catch (error) { failures.push(`${label}: ${error.message}`); }
}
const entry = (id = 'SW-SRCH-01') => ({ id, behavior: 'An empty search does nothing.', kind: 'works-today', races: [], observations: [
  { layer: 'view', file: 'search.test.ts', test: 'ignores an empty search' },
  { layer: 'real-ui-fixture', step: 'search-results' },
  { layer: 'live-backend', binding: null, reason: 'No live check is bound.' },
] });
const catalog = () => ({ version: 1, entries: [entry()], retired: [] });
const check = (value = catalog(), baseline = null) => checkSearchWindowScenarios({ root, catalog: value, baseline, stepIndex: { 'search.ts': ['search-results'] } });
const has = (result, pattern) => assert.ok(result.failures.some(message => pattern.test(message)), result.failures.join('\n'));

try {
  writeFileSync(join(root, 'search.test.ts'), "it('ignores an empty search', () => {});\n");
  test('valid bindings and explicit gaps report counts without claiming execution', () => {
    const result = check();
    assert.deepEqual(result.failures, []);
    assert.deepEqual(result.counts, {
      controller: { bound: 0, unbound: 0 }, view: { bound: 1, unbound: 0 },
      'real-ui-fixture': { bound: 1, unbound: 0 }, 'live-backend': { bound: 0, unbound: 1 },
    });
  });
  test('duplicate active IDs fail', () => {
    const value = catalog(); value.entries.push(entry());
    has(check(value), /duplicate ID SW-SRCH-01/);
  });
  test('duplicate retired IDs and overlap with active IDs fail', () => {
    for (const retired of [[{ id: 'SW-SRCH-01', reason: 'Retired.' }], [
      { id: 'SW-OLD-01', reason: 'Retired.' }, { id: 'SW-OLD-01', reason: 'Retired again.' },
    ]]) has(check({ ...catalog(), retired }), /duplicate ID/);
  });
  test('a file existing without the exact declared test title fails', () => {
    const value = catalog(); value.entries[0].observations[0].test = 'ignores an empty';
    has(check(value), /test title "ignores an empty" is not present/);
  });
  test('a missing bound file fails', () => {
    const value = catalog(); value.entries[0].observations[0].file = 'missing.test.ts';
    has(check(value), /file missing.test.ts does not exist/);
  });
  test('a missing UI step fails', () => {
    const value = catalog(); value.entries[0].observations[1].step = 'removed-step';
    has(check(value), /step "removed-step" is not in/);
  });
  test('removing a base scenario without retirement fails', () => {
    has(check({ version: 1, entries: [], retired: [] }, catalog()), /SW-SRCH-01: removed without a recorded retirement/);
  });
  test('a recorded retirement with a reason allows removal', () => {
    const value = { version: 1, entries: [], retired: [{ id: 'SW-SRCH-01', reason: 'Replaced by the unified search flow.' }] };
    assert.deepEqual(check(value, catalog()).failures, []);
  });
  test('retirement requires a reason and replacements must resolve', () => {
    has(check({ ...catalog(), retired: [{ id: 'SW-OLD-01', reason: '' }] }), /retirement requires a reason/);
    has(check({ ...catalog(), retired: [{ id: 'SW-OLD-01', reason: 'Replaced.', replacedBy: 'missing' }] }), /replacedBy must name an active scenario/);
    assert.deepEqual(check({ ...catalog(), retired: [{ id: 'SW-OLD-01', reason: 'Replaced.', replacedBy: 'SW-SRCH-01' }] }).failures, []);
  });
  test('retirements themselves cannot disappear silently on a later change', () => {
    has(check(catalog(), { version: 1, entries: [], retired: [{ id: 'SW-OLD-01', reason: 'Retired.' }] }), /SW-OLD-01: removed without a recorded retirement/);
  });
  test('unbound observations require reasons and cannot hide stale bindings', () => {
    const value = catalog(); value.entries[0].observations[2].reason = '';
    has(check(value), /unbound observation requires a reason/);
    value.entries[0].observations[2].file = 'missing.test.ts';
    has(check(value), /unbound observation cannot also declare a binding/);
  });
  test('invalid catalogs, kinds, races and repeated layers fail', () => {
    has(check({}), /catalog requires/);
    const value = catalog(); value.entries[0].kind = 'verified'; value.entries[0].races = ['unknown'];
    value.entries[0].observations.push({ layer: 'view', binding: null, reason: 'No observation.' });
    has(check(value), /valid kind/); has(check(value), /race vocabulary/); has(check(value), /duplicate observation layer/);
  });
  test('test titles are declarations, not comments, descriptions or unrelated strings', () => {
    assert.deepEqual([...testTitles(`
      // it('comment', () => {});
      /* test('block comment', () => {}); */
      describe('suite name', () => {});
      const unrelated = "it('string contents', () => {})";
      const pattern = /['"][^'"]*Thing['"]/g;
      it('single', () => {});
      test.only("double", () => {});
      it.each(['a', 'b'])('parameter %s', () => {});
      it.each([{ callback: () => ')' }])('object $case', () => {});
      test.concurrent.each\`a | b\`('table %s', () => {});
      it('reader\\'s title', () => {});
      it(\`static template\`, () => {});
    `)], ['single', 'double', 'parameter %s', 'object $case', 'table %s', "reader's title", 'static template']);
  });
  test('repository-relative paths cannot escape to unrelated files', () => {
    const value = catalog(); value.entries[0].observations[0].file = '../search.test.ts';
    has(check(value), /file must be inside the repository/);
  });
  test('a specified base resolves through real Git history', () => {
    const base = readBaseCatalog(ROOT, 'HEAD');
    assert.equal(base.base, execFileSync('git', ['rev-parse', 'HEAD'], { cwd: ROOT, encoding: 'utf8' }).trim());
  });
  test('an unavailable base fails closed, including through the CLI', () => {
    const badRef = 'refs/heads/search-window-scenarios-test-missing-base';
    assert.throws(() => readBaseCatalog(ROOT, badRef));
    const result = spawnSync(process.execPath, [join(ROOT, 'scripts/ci/check-search-window-scenarios.mjs'), '--base-ref', badRef], { cwd: ROOT, encoding: 'utf8' });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /search-window-scenarios gate FAILED/);
  });
  test('the always-on UI recipe runs the catalog check and its contract tests', () => {
    const register = JSON.parse(readFileSync(join(ROOT, 'governance/consult-register.v1.json'), 'utf8'));
    const commands = parseUiWebGateCommands(register).map(command => command.join(' '));
    for (const file of ['check-search-window-scenarios.mjs', 'check-search-window-scenarios.test.mjs']) assert.ok(commands.includes(`node scripts/ci/${file}`));
  });
  test('the delivered catalog is well formed and all declared bindings resolve', () => {
    const result = checkSearchWindowScenarios({ root: ROOT,
      catalog: JSON.parse(readFileSync(join(ROOT, CATALOG), 'utf8')),
      stepIndex: JSON.parse(readFileSync(join(ROOT, 'scripts/jseval/jseval/ui_step_index.json'), 'utf8')) });
    assert.deepEqual(result.failures, []);
  });
} finally {
  rmSync(root, { recursive: true, force: true });
}
if (failures.length) {
  console.error(`check-search-window-scenarios.test: ${failures.length} failure(s)\n  ${failures.join('\n  ')}`);
  process.exitCode = 1;
} else console.log(`check-search-window-scenarios.test: ${passed} passed`);
