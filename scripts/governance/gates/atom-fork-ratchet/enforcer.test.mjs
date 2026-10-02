import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { resolve, join, dirname } from 'node:path';
import test from 'node:test';
import { enforceAtomForkRatchet } from './enforcer.mjs';
import { rebalanceBaseline } from '../../../ci/check-atom-fork-ratchet.mjs';

const baselinePath = 'scripts/ci/atom-fork-ratchet-baseline.v1.json';
const file = 'shell-v0/new-fork.ts';
const gate = { baseline: { path: baselinePath }, changesetsDir: 'gates/atom-fork-ratchet/.changesets' };

function fixture(fn) {
  mkdirSync(resolve('tmp'), { recursive: true });
  const root = mkdtempSync(resolve('tmp/atom-fork-'));
  const write = (path, body) => {
    const dest = join(root, path);
    mkdirSync(dirname(dest), { recursive: true });
    writeFileSync(dest, body);
  };
  const pins = (prior, live) => {
    write('_baseline/' + baselinePath, JSON.stringify(prior));
    write(baselinePath, JSON.stringify(live));
  };
  write('modules/ui-web/src/' + file, '.badge { color: red; }');
  const enforce = () => enforceAtomForkRatchet({
    repoRoot: root, gate, baselineRef: 'HEAD', fixtureMode: true, fixtureRoot: root,
  });
  return Promise.resolve().then(() => fn({ root, write, pins, enforce }))
    .finally(() => rmSync(root, { recursive: true, force: true }));
}

test('simultaneous source growth and a newly added pin require a changeset', () => fixture(async ({ pins, enforce }) => {
  pins({}, { [file]: 1 });
  const result = await enforce();
  assert.equal(result.verdict, 'fail');
  assert.equal(result.findings[0].ruleId, 'atom-fork-ratchet/silent-growth');
  assert.match(result.findings[0].message, /pin increased from 0 to 1/);
}));

test('raising an existing pin also requires a changeset', () => fixture(async ({ pins, enforce }) => {
  pins({ [file]: 1 }, { [file]: 2 });
  assert.equal((await enforce()).verdict, 'fail');
}));

test('a declared and repinned increase passes, but unpinned overflow fails', () => fixture(async ({ pins, write, enforce }) => {
  write(gate.changesetsDir + '/growth.md', '---\nclassification: declared-growth\ntempdoc: 936\njustification: A new intentional fork is reviewed.\n---\n');
  pins({}, { [file]: 1 });
  assert.equal((await enforce()).verdict, 'pass');
  pins({}, {});
  const result = await enforce();
  assert.equal(result.verdict, 'fail');
  assert.equal(result.findings[0].ruleId, 'atom-fork-ratchet/declared-growth-without-repin');
}));

test('unchanged and shrinking pins pass', () => fixture(async ({ pins, enforce }) => {
  for (const prior of [1, 2]) {
    pins({ [file]: prior }, { [file]: 1 });
    assert.equal((await enforce()).verdict, 'pass');
  }
}));

test('rebalance refuses growth without writing and only shrinks existing pins', () => fixture(({ root, pins }) => {
  const options = { srcRoot: join(root, 'modules/ui-web/src/shell-v0'), baselinePath: join(root, baselinePath) };
  pins({}, {});
  const before = readFileSync(options.baselinePath, 'utf8');
  assert.throws(() => rebalanceBaseline(options), /Cannot rebalance.*upward/);
  assert.equal(readFileSync(options.baselinePath, 'utf8'), before);
  pins({}, { [file]: 2, 'shell-v0/removed.ts': 1 });
  rebalanceBaseline(options);
  assert.deepEqual(JSON.parse(readFileSync(options.baselinePath, 'utf8')), { [file]: 1 });
}));
