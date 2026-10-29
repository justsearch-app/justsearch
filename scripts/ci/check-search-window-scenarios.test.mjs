#!/usr/bin/env node
/** Durable contract tests for catalog freshness, explicit gaps, and lossless scenario retirement. */
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync, readFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
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
  for (const field of ['file', 'test']) {
    test(`a real UI fixture binding cannot declare ${field}`, () => {
      const value = catalog();
      value.entries[0].observations[1][field] = field === 'file' ? 'search.test.ts' : 'ignores an empty search';
      has(check(value), /real-ui-fixture:.*cannot declare file or test/);
    });
  }
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
  test('several observations in one layer bind each clause independently', () => {
    const value = catalog();
    writeFileSync(join(root, 'second.test.ts'), "it('keeps the accepted draft', () => {});\n");
    value.entries[0].observations.push({ layer: 'view', file: 'second.test.ts', test: 'keeps the accepted draft' });
    const result = check(value);
    assert.deepEqual(result.failures, []);
    assert.deepEqual(result.counts.view, { bound: 2, unbound: 0 });
    value.entries[0].observations[3].test = 'removed clause observation';
    has(check(value), /test title "removed clause observation" is not present/);
  });
  test('invalid catalogs, kinds and races fail', () => {
    has(check({}), /catalog requires/);
    const value = catalog(); value.entries[0].kind = 'verified'; value.entries[0].races = ['unknown'];
    has(check(value), /valid kind/); has(check(value), /race vocabulary/);
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
  test('disabled declarations and tests inside disabled suites cannot bind', () => {
    const disabled = [
      "it.skip('ignores an empty search', () => {});",
      "it.skip.each([1])('ignores an empty search', () => {});",
      "it.each([1]).skip('ignores an empty search', () => {});",
      "test.todo('ignores an empty search');",
      "test.concurrent.skip('ignores an empty search', () => {});",
      "xit('ignores an empty search', () => {});",
      "xtest('ignores an empty search', () => {});",
      "describe.skip('disabled', () => { it('ignores an empty search', () => {}); });",
      "describe.skip.each([1])('disabled %s', () => { describe('nested', () => { it('ignores an empty search', () => {}); }); });",
      "xdescribe('disabled', () => { test('ignores an empty search', () => {}); });",
      "describe.todo('disabled', () => { it('ignores an empty search', () => {}); });",
      "it.skipIf(true)('ignores an empty search', () => {});",
      "describe.runIf(false)('disabled', () => { it('ignores an empty search', () => {}); });",
    ];
    try {
      for (const source of disabled) {
        writeFileSync(join(root, 'search.test.ts'), source);
        has(check(), /test title "ignores an empty search" is not present/);
      }
      assert.deepEqual([...testTitles(`
        describe('enabled', () => { it('before', () => {});
          describe.skip('disabled', () => { it('hidden', () => {}); });
          describe.only('nested', () => { test('after', () => {}); });
        });
        it('outside', () => {});
      `)], ['before', 'after', 'outside']);
    } finally {
      writeFileSync(join(root, 'search.test.ts'), "it('ignores an empty search', () => {});\n");
    }
  });
  test('a title must be the complete literal argument, never a literal prefix', () => {
    try {
      for (const title of [
        '"ignores an empty search" + " extended"',
        '"ignores an empty search".trim()',
        '`ignores an empty search${suffix}`',
        'title',
      ]) {
        writeFileSync(join(root, 'search.test.ts'), `it(${title}, () => {});`);
        has(check(), /test title "ignores an empty search" is not present/);
      }
      assert.deepEqual([...testTitles('it(`static`, () => {}); test("complete", () => {});')], ['static', 'complete']);
    } finally {
      writeFileSync(join(root, 'search.test.ts'), "it('ignores an empty search', () => {});\n");
    }
  });
  // These are separate regressions so each false binding has its own reported outcome.
  for (const [label, source, expected] of [
    ['division before a comment containing a test', "const ratio = 6 / 2; // it('target', () => {});\nconst other = 8 / 4;", false],
    ['division before describe.skip', "const ratio = 6 / 2; describe.skip('disabled', () => { const other = 8 / 4; it('target', () => {}); });", false],
    ['suite.skip', "suite.skip('disabled', () => { it('target', () => {}); });", false],
    ['it.skip', "it.skip('target', () => {});", false],
    ['it.skip.each', "it.skip.each([1])('target', () => {});", false],
    ['it.todo', "it.todo('target');", false],
    ['nested describe.skip', "describe('outer', () => { describe.skip('inner', () => { it('target', () => {}); }); });", false],
    ['describe.skipIf', "describe.skipIf(true)('disabled', () => { it('target', () => {}); });", false],
    ['it.runIf', "it.runIf(true)('target', () => {});", false],
    ['concatenated title', "it('target' + ' extra', () => {});", false],
    ['template title with substitution', 'it(`target${suffix}`, () => {});', false],
    ['title inside a string', 'const source = "it(\'target\', () => {})";', false],
    ['ordinary literal title', "it('target', () => {});", true],
    ['each literal title', "it.each([1])('target', () => {});", true],
    ['it.fails', "it.fails('target', () => {});", false],
    ['suite.fails', "suite.fails('disabled', () => { it('target', () => {}); });", false],
    ['xsuite', "xsuite('disabled', () => { it('target', () => {}); });", false],
    ['computed skip', "describe['skip']('disabled', () => { it('target', () => {}); });", false],
    ['it.extend skip', "it.extend({}).skip('target', () => {});", false],
    ['parenthesized skipped suite', "(describe.skip)('disabled', () => { it('target', () => {}); });", false],
    ['dynamic test modifier', "const only = 'skip'; it[only]('target', () => {});", false],
    ['dynamic suite modifier', "describe[condition ? 'skip' : 'only']('disabled', () => { it('target', () => {}); });", false],
    ['unknown suite factory', "suite.extend({}).skip('disabled', () => { it('target', () => {}); });", false],
    ['unknown test modifier', "it.unknown('target', () => {});", false],
    ['an each factory is not a title declaration', "it.each('target')('actual title', () => {});", false],
    ['static template title', 'test(`target`, () => {});', true],
    ['escaped literal title', "it('tar\\u0067et', () => {});", true],
    ['escaped template substitution is literal', 'it(`target\\${suffix}`, () => {});', false],
  ]) {
    test(`AST binding: ${label}`, () => {
      assert.equal(testTitles(source).has('target'), expected);
      assert.equal(testTitles(source, 'bindings.test.js').has('target'), expected);
    });
  }
  for (const [label, source, expected] of [
    ...['skip', 'todo', 'fails'].flatMap(option => [
      [`test option ${option}`, `it('target', { ${option}: true }, () => {});`, false],
      [`describe option ${option}`, `describe('outer', { ${option}: true }, () => { it('target', () => {}); });`, false],
      [`suite option ${option}`, `suite('outer', { ${option}: true }, () => { describe('inner', () => { test('target', () => {}); }); });`, false],
      [`false test option ${option}`, `it('target', { ${option}: false }, () => {});`, true],
      [`false suite option ${option}`, `suite('outer', { ${option}: false }, () => { it('target', () => {}); });`, true],
    ]),
    ['test without a body', "it('target');", false],
    ['test with options but no body', "test('target', {});", false],
    ['test with undefined body', "test('target', {}, undefined);", false],
    ['test with non-function body', "test('target', {}, 123);", false],
    ['test with a referenced body', "test('target', callback);", false],
    ['test with a conditional body', "test('target', enabled ? () => {} : undefined);", false],
    ['test with dynamic options', "test('target', options, () => {});", false],
    ['suite with dynamic options', "describe('outer', options, () => { it('target', () => {}); });", false],
    ['test with conditional options', "test('target', enabled ? {} : { skip: true }, () => {});", false],
    ['suite with conditional options', "suite('outer', enabled ? {} : { todo: true }, () => { it('target', () => {}); });", false],
    ['dynamic skip value', "it('target', { skip: disabled }, () => {});", false],
    ['dynamic suite todo value', "describe('outer', { todo: disabled }, () => { it('target', () => {}); });", false],
    ['spread options', "it('target', { ...options }, () => {});", false],
    ['spread suite options', "suite('outer', { ...options }, () => { it('target', () => {}); });", false],
    ['shorthand skip option', "it('target', { skip }, () => {});", false],
    ['computed skip option', "it('target', { ['skip']: true }, () => {});", false],
    ['dynamic option name', "it('target', { [option]: true }, () => {});", false],
    ['getter skip option', "it('target', { get skip() { return true; } }, () => {});", false],
    ['inherited skip option', "it('target', { __proto__: { skip: true } }, () => {});", false],
    ['options on an each test', "it.each([1])('target', { skip: true }, () => {});", false],
    ['options on an each suite', "describe.each([1])('outer', { todo: true }, () => { it('target', () => {}); });", false],
    ['legacy trailing options', "it('target', () => {}, { skip: true });", false],
    ['dynamic trailing argument', "it('target', () => {}, options);", false],
    ['literal timeout', "it('target', () => {}, 1000);", true],
    ['ordinary literal options', "it('target', { timeout: 1000, retry: 2, concurrent: true, only: true }, () => {});", true],
    ['function expression body', "test('target', {}, function () {});", true],
    ['async function body', "test('target', {}, async () => {});", true],
    ['expression body', "test('target', {}, () => expect(true).toBe(true));", true],
    ['wrapped options and body', "test('target', ({ skip: false }), (() => {}));", true],
    ['enabled sibling of option-disabled suite', "describe('outer', { skip: true }, () => { it('hidden', () => {}); }); it('target', () => {});", true],
  ]) {
    test(`options and body binding: ${label}`, () => {
      assert.equal(testTitles(source).has('target'), expected);
      assert.equal(testTitles(source, 'bindings.test.js').has('target'), expected);
    });
  }
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
  test('first introduction at a valid merge base passes, while missing or shallow history fails closed', () => {
    const history = join(root, 'history');
    mkdirSync(history);
    const git = (cwd, args) => execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
    const commit = message => git(history, ['-c', 'user.name=Catalog test', '-c', 'user.email=catalog-test@example.invalid', 'commit', '--quiet', '--allow-empty', '-m', message]);
    git(history, ['init', '--quiet']);
    commit('before catalog');
    const firstBase = git(history, ['rev-parse', 'HEAD']);
    mkdirSync(join(history, 'governance'));
    writeFileSync(join(history, CATALOG), JSON.stringify(catalog()));
    git(history, ['add', CATALOG]);
    commit('introduce catalog');
    assert.deepEqual(readBaseCatalog(history, firstBase), { base: firstBase, catalog: null });
    assert.deepEqual(check(catalog(), readBaseCatalog(history, firstBase).catalog).failures, []);
    const sharedBase = git(history, ['rev-parse', 'HEAD']);
    git(history, ['branch', 'base-side']);
    commit('head side');
    const headSide = git(history, ['rev-parse', 'HEAD']);
    git(history, ['checkout', '--quiet', 'base-side']);
    commit('base side');
    assert.deepEqual(readBaseCatalog(history, headSide), { base: sharedBase, catalog: catalog() });
    assert.throws(() => readBaseCatalog(history, 'refs/heads/missing'));

    const shallow = join(root, 'shallow');
    git(root, ['clone', '--quiet', '--depth=1', pathToFileURL(history).href, shallow]);
    git(shallow, ['fetch', '--quiet', '--depth=1', 'origin', headSide]);
    git(shallow, ['update-ref', 'refs/heads/head-side', 'FETCH_HEAD']);
    assert.equal(git(shallow, ['rev-parse', '--is-shallow-repository']), 'true');
    assert.throws(() => readBaseCatalog(shallow, 'head-side'));
    // A shallow repository is sufficient when the merge base itself is available.
    assert.deepEqual(readBaseCatalog(shallow, 'HEAD').catalog, catalog());
    git(shallow, ['fetch', '--quiet', '--unshallow', 'origin']);
    assert.deepEqual(readBaseCatalog(shallow, 'head-side'), { base: sharedBase, catalog: catalog() });
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
