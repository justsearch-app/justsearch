#!/usr/bin/env node

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { spawnSync, execFileSync } from 'node:child_process';

import {
  assertCleanCandidate,
  commandForPlatform,
  executeLocalSubsets,
  buildPreparedPlan,
  executePreparedPlan,
  formatPreparedSummary,
  resolvePrContext,
  resultForCommand,
  assertIgnoredOutput,
  runPreparedCommand,
} from './run-publish-preflight.mjs';
import { classifyPaths } from './classify-changes.mjs';
import { CASES } from '../governance/gates/test-intent/corpus.mjs';
import { runScenario } from '../governance/gates/test-intent/scenario.mjs';
import { loadPublicCiLocalRepro, validatePublicCiLocalRepro } from './lib/public-ci-local-repro.mjs';

const repositoryManifest = loadPublicCiLocalRepro();
const build = repositoryManifest.contexts.filter((entry) => entry.check === 'Build (no model blobs)');
assert.equal(build.length, 1);
assert.ok(build[0].commands.includes('node scripts/dev/java-prepush.mjs'),
  'publication preflight must execute affected module integration tests and PMD through the helper');
assert.ok(!build[0].commands.some((command) => /gradle pmdAll\b/.test(command)),
  'the helper replaces the standalone PMD invocation');
const publicClaims = repositoryManifest.contexts.find((entry) => entry.check === 'Public claims');
assert.ok(publicClaims.commands.includes('node scripts/ci/test-check-runtime-manifest-closure.mjs'),
  'Public claims must reproduce the blocking runtime manifest closure self-test');
assert.ok(!publicClaims.commands.includes('node scripts/ci/check-runtime-manifest-closure.mjs'),
  'the advisory runtime manifest closure guard must not block local publication preflight');
const lifecycle = repositoryManifest.contexts.filter((entry) => entry.check === 'Engine lifecycle tests (model-free)');
assert.equal(lifecycle.length, 1, 'the required lifecycle check needs exactly one reproduction entry');
assert.equal(lifecycle[0].mode, 'local-subset');
assert.deepEqual(lifecycle[0].commands, [
  'node scripts/lib/tool-invoker.cjs gradle :modules:system-tests:lifecycleIntegrationTest -PincludeAiTests=false -PskipWebBuild=true --console=plain',
]);

const signalPolicy = {
  workflows: [{ blocking: true, requiredStatusChecks: ['Local', 'Hosted'] }],
};
const validManifest = {
  kind: 'justsearch-public-ci-local-repro.v1',
  version: 1,
  contexts: [
    { check: 'Local', mode: 'local-subset', commands: ['one', 'two'] },
    { check: 'Hosted', mode: 'hosted-only', reason: 'external state' },
  ],
};

assert.deepEqual(validatePublicCiLocalRepro({ manifest: validManifest, signalPolicy }), []);
assert.match(validatePublicCiLocalRepro({
  manifest: { ...validManifest, contexts: [validManifest.contexts[0]] },
  signalPolicy,
}).join('\n'), /Hosted: required check must appear exactly once/);
assert.match(validatePublicCiLocalRepro({
  manifest: { ...validManifest, contexts: [...validManifest.contexts, validManifest.contexts[0]] },
  signalPolicy,
}).join('\n'), /Local: required check must appear exactly once/);

const calls = [];
const status = executeLocalSubsets(validManifest, {
  checkCandidate() {},
  cwd: 'fixture',
  run(command, options) {
    calls.push({ command, options });
    return { status: command === 'two' ? 7 : 0 };
  },
});
assert.equal(status, 7);
assert.deepEqual(calls.map((call) => call.command), ['one', 'two']);
assert.ok(calls.every((call) => call.options.cwd === 'fixture' && call.options.shell === true));

assert.equal(commandForPlatform('./gradlew.bat checkLicense', 'win32'), '.\\gradlew.bat checkLicense');
assert.equal(commandForPlatform('./gradlew.bat checkLicense', 'linux'), './gradlew.bat checkLicense');
assert.equal(commandForPlatform('node scripts/ci/check.mjs', 'win32'), 'node scripts/ci/check.mjs');

// --run uses this same executor: every local command must reach the shell in manifest order.
const manifestCommands = repositoryManifest.contexts
  .filter((context) => context.mode === 'local-subset')
  .flatMap((context) => context.commands);
for (const platform of ['win32', 'linux']) {
  const executed = [];
  assert.equal(executeLocalSubsets(repositoryManifest, {
    checkCandidate() {},
    cwd: 'fixture',
    platform,
    run(command, options) {
      executed.push(command);
      assert.deepEqual(options, { cwd: 'fixture', shell: true, stdio: 'inherit' });
      return { status: 0 };
    },
  }), 0);
  assert.deepEqual(executed, manifestCommands);
  for (const command of manifestCommands) assert.equal(commandForPlatform(command, platform), command);
}

const gitStatusCalls = [];
assert.throws(
  () => assertCleanCandidate({
    cwd: 'dirty-fixture',
    run(command, args, options) {
      gitStatusCalls.push({ command, args, options });
      return { status: 0, stdout: '?? candidate-secret.txt\n' };
    },
  }),
  /requires a clean candidate/,
);
assert.deepEqual(gitStatusCalls[0].args, ['status', '--porcelain=v1', '--untracked-files=all']);
assert.equal(gitStatusCalls[0].options.cwd, 'dirty-fixture');
assert.doesNotThrow(() => assertCleanCandidate({
  cwd: 'clean-fixture',
  run() { return { status: 0, stdout: '' }; },
}));



const context = { candidate: 'a'.repeat(40), base: 'b'.repeat(40), comparisonBase: 'b'.repeat(40), pr: 100, provisional: false };
const registry = { gates: [
  { id: 'dead-code', config: { inputs: [{ path: 'tmp/report.json', class: 'required', producer: 'produce report' }] } },
  { id: 'test-efficacy', config: { inputs: [{ path: 'tmp/pit.json', class: 'on-demand', producer: 'expensive optional' }] } },
] };
const docsPlan = buildPreparedPlan(repositoryManifest, { context, lanes: classifyPaths(['docs/reference/example.md']), registry });
assert.ok(docsPlan.some(step => step.command === 'node scripts/ci/run-ui-web-gates.mjs'), 'Public claims always selects the UI production runner');
assert.deepEqual(docsPlan.find(step => step.id === 'isolated-tools').outputs, ['tmp/ui-checks/venv', 'tmp/ui-checks/env.sh', 'tmp/ui-checks/scratch'], 'retire the old virtualenv before prepare-ui-checks validates the next session tree');
assert.ok(!docsPlan.some(step => step.check === 'Unit tests (app-ui)'), 'docs do not select UI unit lanes');
assert.ok(!docsPlan.some(step => step.command === 'expensive optional'), 'on-demand input is not required CI evidence');
assert.deepEqual(docsPlan.find(step => step.id === 'input-dead-code-0').outputs, ['tmp/report.json']);
const frontendPlan = buildPreparedPlan(repositoryManifest, { context, lanes: classifyPaths(['modules/ui-web/src/Test.vue']), registry });
assert.ok(frontendPlan.some(step => step.check === 'Unit tests (app-ui)'));
assert.ok(frontendPlan.some(step => step.command?.endsWith(`test-intent/cli.mjs --base ${context.base} --pr 100`)));
assert.ok(frontendPlan.some(step => step.command?.includes(':modules:ui:test')), 'use the existing local reproduction');

// Failure isolation and blocked outputs: nothing dependent is dispatched, but every
// independent failure is observed, including when a child cannot be launched.
const dispatched = [];
const steps = [
  { id: 'first', command: 'bad' },
  { id: 'consumer', command: 'must not run', needs: ['first'], outputs: ['tmp/stale.json'] },
  { id: 'second', command: 'bad too' },
  { id: 'hosted', reason: 'GitHub identity' },
  { id: 'windows', command: 'windows-only', platform: 'win32' },
  { id: 'missing', command: 'missing-tool' },
  { id: 'good', command: 'good' },
];
const invalidated = [];
const results = await executePreparedPlan(steps, {
  platform: 'linux', report() {}, invalidate: output => invalidated.push(output),
  run(step) { dispatched.push(step.id); return { status: step.id === 'good' ? 0 : step.id === 'missing' ? 127 : 7 }; },
});
assert.deepEqual(dispatched, ['first', 'second', 'missing', 'good']);
assert.deepEqual(invalidated, ['tmp/stale.json']);
assert.equal(results.get('first').status, 'failed');
assert.equal(results.get('second').status, 'failed');
assert.equal(results.get('consumer').status, 'blocked');
assert.equal(results.get('hosted').status, 'not checked here');
assert.equal(results.get('windows').status, 'not checked here');
assert.equal(results.get('missing').status, 'not checked here');
assert.equal(results.get('good').status, 'passed');
assert.match(formatPreparedSummary(results, context), /failed: 2 \/ blocked: 1 \/ not checked here: 3 — incomplete/);
assert.match(formatPreparedSummary(new Map([['only', { status: 'passed' }]]), context), /— complete$/);
assert.equal(resultForCommand({ network: true }, { status: 1, stderr: 'HTTP 403' }).status, 'not checked here');
assert.equal(resultForCommand({ network: true }, { status: 1, stderr: 'npm error code E503' }).status, 'not checked here');
assert.equal(resultForCommand({ network: true }, { status: 1, stderr: 'pip: HTTP error 403 while getting package' }).status, 'not checked here');
assert.equal(resultForCommand({ network: true }, { status: 1, stderr: 'invalid lockfile' }).status, 'failed');
assert.equal(resultForCommand({ pythonVersion: '3.13' }, { status: 125 }).status, 'not checked here');
assert.equal(resultForCommand({ network: true }, { status: 22, stderr: 'curl: (22) The requested URL returned error: 403' }).status, 'not checked here');

const prCalls = [];
function mockContext(command, args) {
  prCalls.push([command, args]);
  if (command === 'git') return { status: 0, stdout: args[0] === 'rev-parse' ? context.candidate : context.base };
  return { status: 0, stdout: args.includes('--jq') ? '99\n' : JSON.stringify({ base: { sha: context.base, ref: 'release' } }) };
}
const predicted = resolvePrContext({ cwd: 'fixture', run: mockContext });
assert.equal(predicted.pr, 100);
assert.equal(predicted.provisional, true);
assert.match(formatPreparedSummary(new Map(), predicted), /PR 100 \(provisional\)/);
const explicit = resolvePrContext({ cwd: 'fixture', pr: 105, run: mockContext });
assert.equal(explicit.base, context.base);
assert.equal(explicit.provisional, false);
assert.equal(explicit.pr, 105);
assert.ok(!prCalls.some(([command, args]) => command === 'gh' && args[1].endsWith('/pulls/105')), 'explicit PR uses the local origin/main merge-base without a network lookup');
const unavailable = resolvePrContext({ run(command, args) {
  return command === 'gh' ? { error: { code: 'ENOENT' }, status: null } : mockContext(command, args);
} });
assert.equal(unavailable.pr, undefined);
assert.match(unavailable.reason, /prediction unavailable/);
const missingPrPlan = buildPreparedPlan(repositoryManifest, { context: unavailable, lanes: classifyPaths(['docs/x.md']), registry });
assert.ok(missingPrPlan.find(step => step.command?.includes('test-intent/cli')).reason);

for (const lanes of [7, [], ['unknown']]) {
  const manifest = structuredClone(validManifest);
  manifest.contexts[0].lanes = lanes;
  assert.match(validatePublicCiLocalRepro({ manifest, signalPolicy }).join('\n'), /known classifier lanes/);
}
const contextFixture = { ...validManifest, contexts: [
  { check: 'First', mode: 'local-subset', commands: ['npm ci', 'one'], lanes: ['frontend'] },
  { check: 'Second', mode: 'local-subset', commands: ['two', 'three'] },
  validManifest.contexts[1],
] };
const fixturePlan = buildPreparedPlan(contextFixture, { context, lanes: { frontend: true }, registry: { gates: [] } });
const fixtureResults = await executePreparedPlan(fixturePlan.map(step => step.check || step.reason ? step : { id: step.id, virtual: true }), {
  report() {}, invalidate() {},
  run(step) { return { status: ['npm ci', 'two'].includes(step.command) ? 7 : 0 }; },
});
assert.equal(fixtureResults.get('first-0').status, 'failed');
assert.equal(fixtureResults.get('first-1').status, 'blocked');
assert.equal(fixtureResults.get('second-0').status, 'failed');
assert.equal(fixtureResults.get('second-1').status, 'passed');
assert.equal(fixtureResults.get('hosted').status, 'not checked here');
assert.ok(!buildPreparedPlan(contextFixture, { context, lanes: {}, registry }).some(step => step.check === 'First'));
const windowsPlan = buildPreparedPlan({ contexts: [{ check: 'Native producer', mode: 'local-subset', commands: ['cmd /c produce', 'check notices'] }] }, { context, lanes: {}, registry: { gates: [] } });
const windowsResults = await executePreparedPlan(windowsPlan.map(step => step.id === 'isolated-tools' ? { id: step.id, virtual: true } : step).filter(step => step.check || step.id === 'isolated-tools'), {
  platform: 'linux', report() {}, run() { throw new Error('unavailable producer and its consumer must not run'); },
});
assert.equal(windowsResults.get('native-producer-0').status, 'not checked here');
assert.equal(windowsResults.get('native-producer-1').status, 'blocked');

// All prepared check commands come from the inventory, with only the test-intent CLI adapter.
for (const step of frontendPlan.filter(step => step.check && step.command)) {
  const entry = repositoryManifest.contexts.find(entry => entry.check === step.check);
  assert.ok(entry.commands.includes(step.command) || step.command.includes('test-intent/cli.mjs'));
}
const prelude = docsPlan.filter(step => !step.check && step.command);
assert.ok(prelude.some(step => step.command === 'npm --prefix scripts/wire-contract install'));
assert.ok(prelude.some(step => step.command === 'produce report'));
assert.ok(prelude.every(step => step.id === 'isolated-tools' || step.needs.includes('isolated-tools')));
const unavailableInputs = await executePreparedPlan([
  { id: 'input-advisory', command: 'fetch', network: true },
  { id: 'advisory-gate', command: 'check', needs: ['input-advisory'] },
], { report() {}, run: () => ({ status: 1, stderr: 'HTTP 503' }) });
assert.equal(unavailableInputs.get('advisory-gate').status, 'not checked here');
assert.match(unavailableInputs.get('advisory-gate').reason, /HTTP 503/);

// Use an accepted changeset without its audit, then dispatch the runner's actual
// adapted command into the real test-intent CLI against the fixture repository.
const auditScenario = CASES.find(entry => entry.name === 's12 an audit-selected PR (#40) needs a second record');
const auditFixture = runScenario(auditScenario.scenario);
try {
  const base = execFileSync('git', ['rev-parse', 'main'], { cwd: auditFixture.root, encoding: 'utf8' }).trim();
  execFileSync('git', ['update-ref', 'refs/remotes/origin/main', base], { cwd: auditFixture.root });
  for (const pr of [40, 41]) {
    const resolved = resolvePrContext({ cwd: auditFixture.root, pr });
    const plan = buildPreparedPlan(repositoryManifest, { context: resolved, lanes: {}, registry });
    const command = plan.find(step => step.command?.includes('test-intent/cli.mjs')).command;
    const result = spawnSync('bash', ['-c', `${command} --repo "$PREFLIGHT_FIXTURE_REPO"`], {
      encoding: 'utf8', env: { ...process.env, PREFLIGHT_FIXTURE_REPO: auditFixture.root },
    });
    assert.equal(result.status, pr === 40 ? 1 : 0, result.stdout + result.stderr);
    assert.equal(/audit-required/.test(result.stdout), pr === 40);
  }
} finally { auditFixture.cleanup(); }

// Run real producers twice against ignored files. A's existing report must not
// suppress B's producer; even a blocked producer must retire its stale report.
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'preflight-inputs-'));
try {
  execFileSync('git', ['init', '-q', scratch]);
  fs.writeFileSync(path.join(scratch, '.gitignore'), '/tmp/\n');
  execFileSync('git', ['add', '.gitignore'], { cwd: scratch });
  fs.mkdirSync(path.join(scratch, 'tmp'));
  const output = 'tmp/report.json';
  const producer = { id: 'produce', command: 'node -e "require(\'fs\').writeFileSync(\'tmp/report.json\', require(\'fs\').readFileSync(\'input\'))"', outputs: [output] };
  const run = step => spawnSync(step.command, { cwd: scratch, shell: true, encoding: 'utf8' });
  for (const candidate of ['A', 'B']) {
    fs.writeFileSync(path.join(scratch, 'input'), candidate);
    execFileSync('git', ['add', '.'], { cwd: scratch });
    execFileSync('git', ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', candidate], { cwd: scratch });
    assertCleanCandidate({ cwd: scratch });
    const result = await executePreparedPlan([producer], { cwd: scratch, run, report() {} });
    assert.equal(result.get('produce').status, 'passed');
    assert.equal(fs.readFileSync(path.join(scratch, output), 'utf8'), candidate);
  }
  fs.writeFileSync(path.join(scratch, output), JSON.stringify({ targets: [{ available: false, error: 'HTTP 403' }] }));
  assert.equal(resultForCommand({ advisory: true, outputs: [output] }, { status: 0 }, { cwd: scratch }).status, 'not checked here', 'advisory producer exits zero even when the API is unavailable');
  const blockedProducer = await executePreparedPlan([{ id: 'setup', reason: 'unavailable' }, { ...producer, needs: ['setup'] }], { cwd: scratch, run, report() {} });
  assert.equal(blockedProducer.get('produce').status, 'blocked');
  assert.equal(fs.existsSync(path.join(scratch, output)), false, 'blocked producer retires stale evidence too');
  const shellResults = await executePreparedPlan([
    { id: 'multiline', isolated: false, command: 'node -e "process.exit(9)"\nnode -e "process.exit(0)"' },
    { id: 'independent', isolated: false, command: 'node -e "process.exit(7)"' },
    { id: 'blocked', command: 'must not run', needs: ['multiline'] },
  ], { cwd: scratch, report() {}, run: step => runPreparedCommand(step, { cwd: scratch, env: process.env, logDir: path.join(scratch, 'tmp'), slot: false }) });
  assert.equal(shellResults.get('multiline').status, 'failed', 'a later successful command cannot hide an earlier failure in its CI step');
  assert.equal(shellResults.get('multiline').reason, 'exit 9');
  assert.equal(shellResults.get('independent').reason, 'exit 7');
  assert.equal(shellResults.get('blocked').status, 'blocked');
  assert.throws(() => assertIgnoredOutput('.gitignore', scratch), /tracked/);
  assert.throws(() => assertIgnoredOutput('../outside', scratch), /outside/);
  fs.symlinkSync(os.tmpdir(), path.join(scratch, 'tmp/link'));
  assert.throws(() => assertIgnoredOutput('tmp/link/output', scratch), /symlink/);
} finally { fs.rmSync(scratch, { recursive: true, force: true }); }
console.log('test-run-publish-preflight: PASS (legacy modes, selection, failure isolation, prerequisites, unavailable evidence, PR context, regeneration)');

// Ordinary worktree preparation keeps its two npm installs and optional dist;
// only the explicit flag transfers control to the long-running preflight.
const preparationSource = fs.readFileSync(new URL('../dev/prepare-worktree.cjs', import.meta.url), 'utf8');
function prepareCalls(args) {
  const calls = [];
  const stopped = new Error('fixture exit');
  try {
    vm.runInNewContext(preparationSource, {
      __dirname: '/fixture/scripts/dev',
      process: { argv: ['node', 'prepare-worktree.cjs', ...args], env: {}, execPath: 'node', exit(status) { calls.push(['exit', status]); throw stopped; } },
      console: { error() {} },
      require(name) {
        if (name === 'fs') return { existsSync: () => true };
        if (name === 'path') return path;
        if (name.endsWith('resolve-jdk.cjs')) return { resolveJdkHome: () => '/jdk' };
        if (name.endsWith('tool-invoker.cjs')) return { runToolSync(...params) { calls.push(params.slice(0, 2)); return { status: 0 }; } };
        if (name === 'node:child_process') return { spawnSync(...params) { calls.push(params.slice(0, 2)); return { status: 0 }; } };
        throw new Error(`unexpected require: ${name}`);
      },
    });
  } catch (error) { if (error !== stopped) throw error; }
  return calls;
}
assert.deepEqual(prepareCalls(['--no-dist']).map(call => call[0]), ['npm', 'npm']);
assert.deepEqual(prepareCalls([]).map(call => call[0]), ['npm', 'npm', 'gradle']);
const forwarded = prepareCalls(['--preflight', '--pr', '105', '--list']);
assert.deepEqual([...forwarded[0][1]].slice(1), ['--prepare', '--pr', '105', '--list']);
assert.deepEqual(prepareCalls(['--preflight', '--no-dist']), [['exit', 2]]);
console.log('prepare-worktree compatibility: PASS');
