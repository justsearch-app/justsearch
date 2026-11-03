#!/usr/bin/env node
/** The argv list is authoritative; malformed data fails before any gate executes. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readUiWebGateCommands } from './run-ui-web-gates.mjs';
import { checkCoverage } from './check-governance-ci-coverage.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const register = JSON.parse(fs.readFileSync(path.join(ROOT, 'governance', 'consult-register.v1.json'), 'utf8'));
const entry = (register.entries ?? register.regions ?? []).find((e) => e.id === 'ui-web-gates');
const fixture = (commands) => ({ regions: [{ id: 'ui-web-gates', commands, recipe: entry.recipe }] });
const workflowText = `jobs:
  ui:
    name: UI gates
    runs-on: ubuntu-latest
    steps:
      - run: node scripts/ci/run-ui-web-gates.mjs
`;
const coverage = (consultRegister, workflow = workflowText) => checkCoverage({
  workflowText: workflow,
  registry: { gates: [{ id: 'ambient-purity' }] },
  requiredChecks: ['UI gates'],
  consultRegister,
});

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ui-web-gates-'));
const runner = path.join(root, 'scripts/ci/run-ui-web-gates.mjs');
fs.mkdirSync(path.dirname(runner), { recursive: true });
fs.mkdirSync(path.join(root, 'governance'));
fs.copyFileSync(path.join(ROOT, 'scripts/ci/run-ui-web-gates.mjs'), runner);
const save = (value) => fs.writeFileSync(path.join(root, 'governance/consult-register.v1.json'), JSON.stringify(value));
const cli = (args = []) => spawnSync(process.execPath, [runner, ...args], { encoding: 'utf8' });
try {
  run('real command list retains retired-guard removal and references existing scripts', () => {
    const commands = readUiWebGateCommands(register);
    assert.ok(!commands.some((c) => c[1] === 'scripts/ci/check-composition-surfaces.mjs'));
    for (const command of commands) assert.ok(fs.existsSync(path.join(ROOT, command[1])), command[1]);
    save(register);
    const result = cli(['--list']);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout, commands.map((c) => c.join(' ') + '\n').join(''));
  });

  run('missing ui-web-gates entry fails closed for runner and coverage', () => {
    save({ regions: [] });
    assert.throws(() => readUiWebGateCommands({ regions: [] }), /no `ui-web-gates` entry/);
    assert.throws(() => coverage({ regions: [] }), /no `ui-web-gates` entry/);
    for (const args of [[], ['--list']]) {
      const result = cli(args);
      assert.equal(result.status, 1);
      assert.match(result.stderr, /no `ui-web-gates` entry/);
    }
  });

  const marker = path.join(root, 'executed');
  const sentinel = ['node', '-e', "require('node:fs').writeFileSync('executed', 'ran')"];
  for (const [label, commands] of [
    ['missing commands', undefined],
    ['null commands', null],
    ['object commands', {}],
    ['string commands', 'node scripts/ci/check-adaptive-closure.mjs'],
    ['empty commands', []],
    ['non-array command', [sentinel, 'node']],
    ['empty command', [sentinel, []]],
    ['non-string argument', [sentinel, ['node', 1]]],
    ['non-node executable', [sentinel, ['npm', 'test']]],
  ]) {
    run(`${label} fails before execution and coverage credit`, () => {
      const invalid = fixture(commands);
      const message = /ui-web-gates\.commands(?:\[1\])? must be a non-empty array/;
      assert.throws(() => readUiWebGateCommands(invalid), message);
      assert.throws(() => coverage(invalid), message);
      save(invalid);
      for (const args of [[], ['--list']]) {
        const result = cli(args);
        assert.equal(result.status, 1, result.stdout + result.stderr);
        assert.match(result.stderr, message);
        assert.equal(fs.existsSync(marker), false, 'no command may run before the entire list is valid');
      }
    });
  }

  run('argv order and argument boundaries are executed exactly', () => {
    const commands = ['first with spaces', 'second'].map((value) => [
      'node', '-e', "require('node:fs').appendFileSync('executed', JSON.stringify(process.argv.slice(1)) + '\\n')", value,
    ]);
    const value = fixture(commands);
    assert.deepEqual(readUiWebGateCommands(value), commands);
    save(value);
    const result = cli();
    assert.equal(result.status, 0, result.stdout + result.stderr);
    const executed = fs.readFileSync(marker, 'utf8');
    assert.equal(executed, '["first with spaces"]\n["second"]\n');
    fs.rmSync(marker);
    value.regions[0].recipe = [];
    save(value);
    const reworded = cli();
    assert.equal(reworded.status, 0, reworded.stdout + reworded.stderr);
    assert.equal(fs.readFileSync(marker, 'utf8'), executed, 'recipe removal cannot change execution');
  });

  run('removing recipe parentheticals changes neither the list nor production coverage', () => {
    const reworded = structuredClone(register);
    const e = (reworded.entries ?? reworded.regions).find((row) => row.id === 'ui-web-gates');
    e.recipe = e.recipe.map((line) => line.replace(/\([^)]*\)/g, ''));
    assert.notDeepEqual(e.recipe, entry.recipe);
    assert.deepEqual(readUiWebGateCommands(reworded), readUiWebGateCommands(register));
    assert.deepEqual(coverage(reworded), coverage(register));
    save(reworded);
    const result = cli(['--list']);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout, readUiWebGateCommands(register).map((c) => c.join(' ') + '\n').join(''));
    e.recipe = [];
    assert.deepEqual(readUiWebGateCommands(reworded), readUiWebGateCommands(register));
  });

  run('production coverage still rejects an absent CI invocation or absent gate command', () => {
    assert.deepEqual(coverage(register).issues, []);
    for (const result of [
      coverage(register, workflowText.replace('node scripts/ci/run-ui-web-gates.mjs', 'echo no gates')),
      coverage(fixture(entry.commands.filter((c) => !c.includes('ambient-purity')))),
    ]) {
      assert.equal(result.rows[0].production, false);
      assert.match(result.issues[0], /ambient-purity: no required hosted production invocation/);
    }
  });

  run('register output argument containing --mode gate cannot claim production enforcement', () => {
    const command = ['node', 'scripts/governance/run.mjs', '--gate', 'ambient-purity', '--out', 'report --mode gate'];
    const result = coverage(fixture([command]));
    assert.equal(result.rows[0].production, false);
    assert.match(result.issues[0], /ambient-purity: no required hosted production invocation/);
  });

  run('register gate-mode arguments retain production enforcement', () => {
    const command = ['node', 'scripts/governance/run.mjs', '--gate', 'ambient-purity', '--mode', 'gate'];
    for (const argv of [command, [...command, '--out', 'report; --mode warn']]) {
      const result = coverage(fixture([argv]));
      assert.equal(result.rows[0].production, true);
      assert.deepEqual(result.issues, []);
    }
  });
} finally {
  fs.rmSync(root, { recursive: true, force: true });
}

if (failures.length) {
  console.error(`run-ui-web-gates.test: ${failures.length} failure(s)\n  ` + failures.join('\n  '));
  process.exit(1);
}
console.log(`run-ui-web-gates.test: ${passed} passed`);
