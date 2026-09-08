#!/usr/bin/env node
/**
 * The supervisor conformance harness (design 7.1 "Conformance", lane F stage B item B7).
 *
 *   node scripts/supervisor-conformance/run.mjs --self-test
 *   node scripts/supervisor-conformance/run.mjs --adapter dev-runner
 *   node scripts/supervisor-conformance/run.mjs --adapter tauri
 *
 * `--self-test` needs no supervisor at all: it checks that the register's exit table still agrees
 * with the Java that produces those integers, runs every decision case through the JS decision
 * seam, proves the harness overrides are inert without their flag, and drives the fake engine
 * through each mode to show it really does exit, hang and answer as the modes claim. That is what
 * lets item B7 land GREEN before either implementation exists — the harness fixes the contract, and
 * a contract you cannot run is a document.
 *
 * `--adapter X` runs the actuator cases against one implementation. A case the adapter does not
 * report a result for FAILS the harness. It does not print `skipped` and exit 0, because "one
 * contract, two implementations" stops being true the moment one of them is allowed to answer N/A —
 * and an N/A is exactly what a harness prints on the day the contract quietly forked.
 */

import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import {
  HARNESS_FLAG,
  OVERRIDE_ENV,
  FAKE_ENGINE,
  actuatorCases,
  checkExitTableAgreement,
  decisionCases,
  engineRow,
  loadPolicy,
  repoRoot,
  runDecisionTable,
} from './contract.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));

/**
 * The per-run overrides the harness uses. Product values are minutes; a CI lane that waited 300 s
 * for one stability window would be a lane nobody runs, which is the same as no lane. The register
 * carries the product numbers and this is the only place the test numbers exist.
 */
export const HARNESS_OVERRIDES = Object.freeze({
  [HARNESS_FLAG]: '1',
  [OVERRIDE_ENV.stabilityWindowMs]: '1500',
  [OVERRIDE_ENV.cooldownIncrementMs]: '100',
  [OVERRIDE_ENV.maxCooldownMs]: '400',
  [OVERRIDE_ENV.startDeadlineMs]: '4000',
  [OVERRIDE_ENV.gracefulStopDeadlineMs]: '1500',
  [OVERRIDE_ENV.hangPollIntervalMs]: '200',
  [OVERRIDE_ENV.hangUnhealthyThreshold]: '3',
});

// ---------------------------------------------------------------------------------------------
// IO the adapters share. Passed IN to each adapter rather than imported by it, so an adapter is
// only ever the part that differs: how this implementation is started and observed.
// ---------------------------------------------------------------------------------------------

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitFor(predicate, { timeoutMs = 10_000, intervalMs = 50, what = 'condition' } = {}) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    // eslint-disable-next-line no-await-in-loop
    const value = await predicate();
    if (value) return value;
    if (Date.now() >= deadline) throw new Error(`timed out after ${timeoutMs}ms waiting for ${what}`);
    // eslint-disable-next-line no-await-in-loop
    await sleep(intervalMs);
  }
}

function readJsonIfPresent(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

function httpGetStatus(url, timeoutMs = 1000) {
  return new Promise((resolve) => {
    const req = http.get(url, { timeout: timeoutMs }, (res) => {
      res.resume();
      resolve(res.statusCode ?? 0);
    });
    req.on('timeout', () => {
      req.destroy();
      resolve(0);
    });
    req.on('error', () => resolve(0));
  });
}

function writeShutdownRequest(dataDir, { reason, deadlineEpochMs, issuedBy = 'supervisor-conformance', nonce }) {
  const target = path.join(dataDir, 'runtime', 'shutdown-request.v1.json');
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const tmp = `${target}.tmp`;
  fs.writeFileSync(
    tmp,
    `${JSON.stringify({ schemaVersion: 1, reason, deadlineEpochMs, issuedBy, ...(nonce ? { nonce } : {}) }, null, 2)}\n`,
    'utf8',
  );
  fs.renameSync(tmp, target);
  return target;
}

/**
 * Under the repo's gitignored `tmp/`, not under `os.tmpdir()`.
 *
 * Not a preference: `dev-runner.cjs`'s `resolveDataDir` REFUSES a `--data-dir` outside the repo root
 * (a path-traversal guard), so a work dir in the system temp directory makes every dev-runner case
 * fail with an error the adapter cannot even see — the refusal is reported as JSON on stdout, and an
 * adapter watching stderr reads it as silence. Found exactly that way.
 */
function makeWorkDir(label) {
  const root = path.join(repoRoot, 'tmp', 'supervisor-conformance');
  fs.mkdirSync(root, { recursive: true });
  return fs.mkdtempSync(path.join(root, `${label}-`));
}

/** Write a per-incarnation behaviour plan for the fake engine, and return its path. */
function writeEnginePlan(workDir, incarnations) {
  const target = path.join(workDir, 'fake-engine.plan.json');
  fs.writeFileSync(target, `${JSON.stringify({ incarnations }, null, 2)}\n`, 'utf8');
  return target;
}

export const io = {
  sleep,
  waitFor,
  readJsonIfPresent,
  httpGetStatus,
  writeShutdownRequest,
  makeWorkDir,
  writeEnginePlan,
  repoRoot,
  fakeEngine: FAKE_ENGINE,
  harnessOverrides: HARNESS_OVERRIDES,
};

// ---------------------------------------------------------------------------------------------
// Reporting
// ---------------------------------------------------------------------------------------------

const results = [];

function record(section, name, problems) {
  const list = problems ?? [];
  results.push({ section, name, problems: list });
  const mark = list.length === 0 ? 'PASS' : 'FAIL';
  process.stdout.write(`${mark}  ${section} :: ${name}\n`);
  for (const problem of list) process.stdout.write(`        ${problem}\n`);
}

function finish() {
  const failed = results.filter((r) => r.problems.length > 0);
  process.stdout.write(
    `\nsupervisor-conformance: ${results.length - failed.length}/${results.length} checks passed\n`,
  );
  if (failed.length > 0) {
    process.stdout.write(`failed: ${failed.map((f) => f.name).join(', ')}\n`);
    process.exit(1);
  }
  if (results.length === 0) {
    process.stdout.write('supervisor-conformance: ran 0 checks — that is a bug, not a pass\n');
    process.exit(1);
  }
}

// ---------------------------------------------------------------------------------------------
// --self-test: the fake engine and the decision seam, with no supervisor under test
// ---------------------------------------------------------------------------------------------

function spawnFakeEngine({ workDir, args = [], env = {} }) {
  const dataDir = path.join(workDir, 'data');
  fs.mkdirSync(path.join(dataDir, 'runtime'), { recursive: true });
  const child = spawn(process.execPath, [FAKE_ENGINE, ...args], {
    env: { ...process.env, JUSTSEARCH_DATA_DIR: dataDir, JUSTSEARCH_API_PORT: '0', ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const output = { stdout: '', stderr: '' };
  child.stdout.on('data', (b) => {
    output.stdout += b.toString();
  });
  child.stderr.on('data', (b) => {
    output.stderr += b.toString();
  });
  const exited = new Promise((resolve) => child.on('exit', (code, signal) => resolve({ code, signal })));
  return { child, dataDir, output, exited };
}

async function selfTestFakeEngineExits() {
  const table = [
    { mode: 'clean-exit', expect: 0, args: ['--mode', 'clean-exit', '--exit-after-ms', '300'] },
    { mode: 'crash', expect: 1, args: ['--mode', 'crash', '--exit-after-ms', '300'] },
    { mode: 'oom', expect: 3, args: ['--mode', 'oom', '--exit-after-ms', '300'] },
    { mode: 'boot-fail', expect: 2, args: ['--mode', 'boot-fail'] },
  ];
  const declared = new Map((engineRow().exitCodes ?? []).map((e) => [e.name, e.code]));
  for (const row of table) {
    const workDir = makeWorkDir(`exit-${row.mode}`);
    try {
      const run = spawnFakeEngine({ workDir, args: row.args });
      const { code } = await run.exited;
      const problems = [];
      if (code !== row.expect) problems.push(`exited ${code}, expected ${row.expect}`);
      if (row.mode === 'boot-fail') {
        if (fs.existsSync(path.join(run.dataDir, 'runtime', 'manifest.json'))) {
          problems.push('boot-fail published a manifest — that is a crash-after-boot, a different row');
        }
        if (code !== declared.get('DATA_DIR_LOCKED')) {
          problems.push('boot-fail must exit with the declared non-transient code');
        }
      }
      if (row.mode === 'oom' && code !== declared.get('OUT_OF_MEMORY')) {
        problems.push('oom must exit with EngineExit.OUT_OF_MEMORY as the register declares it');
      }
      record('fake-engine', `mode ${row.mode} exits ${row.expect}`, problems);
    } finally {
      fs.rmSync(workDir, { recursive: true, force: true });
    }
  }
}

async function selfTestFakeEngineAnswersAndPublishes() {
  const workDir = makeWorkDir('answers');
  const run = spawnFakeEngine({ workDir, args: ['--mode', 'honour'] });
  try {
    const manifest = await waitFor(
      () => readJsonIfPresent(path.join(run.dataDir, 'runtime', 'manifest.json')),
      { what: 'the fake engine manifest' },
    );
    const problems = [];
    const port = manifest?.head?.apiPort;
    if (!Number.isInteger(port) || port <= 0) {
      problems.push(`manifest carried no usable head.apiPort: ${JSON.stringify(manifest?.head)}`);
    } else {
      const health = await httpGetStatus(`http://127.0.0.1:${port}/api/health`);
      if (health !== 200) problems.push(`/api/health answered ${health}, expected 200`);
      const status = await httpGetStatus(`http://127.0.0.1:${port}/api/status`);
      if (status !== 200) problems.push(`/api/status answered ${status}, expected 200`);
    }
    if (!manifest?.instanceId) problems.push('manifest carried no instanceId');
    record('fake-engine', 'honour publishes a real manifest and answers health', problems);

    // ... and honours the request file, which is what makes `hang-soft` recoverable at all.
    writeShutdownRequest(run.dataDir, { reason: 'quit', deadlineEpochMs: Date.now() + 5000 });
    const { code } = await Promise.race([
      run.exited,
      sleep(5000).then(() => ({ code: 'TIMEOUT' })),
    ]);
    record('fake-engine', 'honour exits 0 on a shutdown request', code === 0 ? [] : [`exited ${code}`]);
  } finally {
    run.child.kill();
    fs.rmSync(workDir, { recursive: true, force: true });
  }
}

async function selfTestFakeEngineHangs() {
  for (const mode of ['hang-soft', 'hang-hard']) {
    const workDir = makeWorkDir(mode);
    const run = spawnFakeEngine({ workDir, args: ['--mode', mode, '--hang-after-ms', '400'] });
    try {
      const manifest = await waitFor(
        () => readJsonIfPresent(path.join(run.dataDir, 'runtime', 'manifest.json')),
        { what: `${mode} manifest` },
      );
      const port = manifest.head.apiPort;
      const problems = [];
      if ((await httpGetStatus(`http://127.0.0.1:${port}/api/health`)) !== 200) {
        problems.push('health did not answer BEFORE the hang — the case would prove nothing');
      }
      await sleep(700);
      if ((await httpGetStatus(`http://127.0.0.1:${port}/api/health`, 400)) !== 0) {
        problems.push('health still answered after the hang window');
      }

      writeShutdownRequest(run.dataDir, { reason: 'quit', deadlineEpochMs: Date.now() + 5000 });
      const outcome = await Promise.race([run.exited, sleep(1200).then(() => 'ALIVE')]);
      if (mode === 'hang-soft' && outcome === 'ALIVE') {
        problems.push('hang-soft ignored the request file; the graceful arm has nothing to recover through');
      }
      if (mode === 'hang-hard' && outcome !== 'ALIVE') {
        problems.push('hang-hard honoured the request file; the forced-kill arm would never be exercised');
      }
      record('fake-engine', `${mode} stops answering and ${mode === 'hang-soft' ? 'honours' : 'ignores'} the request file`, problems);
    } finally {
      run.child.kill();
      fs.rmSync(workDir, { recursive: true, force: true });
    }
  }
}

async function selfTestSlowStart() {
  const workDir = makeWorkDir('slow-start');
  const run = spawnFakeEngine({ workDir, args: ['--mode', 'slow-start', '--slow-start-ms', '30000'] });
  try {
    await sleep(900);
    const published = fs.existsSync(path.join(run.dataDir, 'runtime', 'manifest.json'));
    record(
      'fake-engine',
      'slow-start withholds the manifest past a short start deadline',
      published ? ['a manifest appeared; the start-deadline case cannot fire'] : [],
    );
  } finally {
    run.child.kill();
    fs.rmSync(workDir, { recursive: true, force: true });
  }
}

function selfTestDecisionTable() {
  const policy = loadPolicy({ env: {} });
  for (const { id, problems } of runDecisionTable({ policy })) {
    record('decision', id, problems);
  }
  const declared = decisionCases().length;
  record(
    'decision',
    'the register declares decision cases at all',
    declared > 0 ? [] : ['no case declares `decision` in its drives list'],
  );
}

function selfTestOverridesAreInert() {
  const hostile = {
    [OVERRIDE_ENV.stabilityWindowMs]: '7',
    [OVERRIDE_ENV.maxCooldownMs]: '7',
    [OVERRIDE_ENV.cooldownIncrementMs]: '7',
    [OVERRIDE_ENV.hangUnhealthyThreshold]: '1',
  };
  const product = loadPolicy({ env: {} });
  const withoutFlag = loadPolicy({ env: { ...hostile } });
  const withFlag = loadPolicy({ env: { ...hostile, [HARNESS_FLAG]: '1' } });

  const problems = [];
  for (const key of ['stabilityWindowMs', 'maxCooldownMs', 'cooldownIncrementMs', 'hangUnhealthyThreshold']) {
    if (withoutFlag[key] !== product[key]) {
      problems.push(`${key} was overridden WITHOUT ${HARNESS_FLAG}=1 (${withoutFlag[key]} vs ${product[key]})`);
    }
    if (withFlag[key] !== 7 && !(key === 'hangUnhealthyThreshold' && withFlag[key] === 1)) {
      problems.push(`${key} was NOT overridden with ${HARNESS_FLAG}=1 — the flag would be untested`);
    }
  }
  if (withoutFlag.harnessActive !== false) problems.push('harnessActive true without the flag');
  if (withFlag.harnessActive !== true) problems.push('harnessActive false with the flag');
  // The budget is deliberately NOT overridable: a harness that could widen it could hide a
  // supervisor that never reaches `exhausted`.
  if (withFlag.maxRestartAttempts !== product.maxRestartAttempts) {
    problems.push('maxRestartAttempts changed under the harness flag; the budget must not be overridable');
  }
  record('policy', 'per-run overrides are inert without the harness flag', problems);
}

function selfTestExitTable() {
  record('contract', 'the register exit table agrees with EngineExit.java', checkExitTableAgreement());
}

function selfTestCaseListShape() {
  const problems = [];
  const seen = new Set();
  for (const testCase of [...decisionCases(), ...actuatorCases()]) {
    if (!testCase.id) problems.push('a case has no id');
    if (!testCase.why) problems.push(`${testCase.id}: no \`why\` — a case nobody can read is a case nobody maintains`);
    if (!testCase.faultMode) problems.push(`${testCase.id}: no faultMode`);
    seen.add(testCase.id);
  }
  const modes = new Set(actuatorCases().map((c) => c.faultMode));
  for (const required of ['clean-exit', 'hang', 'oom']) {
    if (!modes.has(required)) {
      problems.push(`no ACTUATOR case covers fault mode \`${required}\` — a decision-only proof of it is not a proof`);
    }
  }
  record('contract', 'every declared case is well-formed and the live fault modes are driven', problems);
}

async function selfTest() {
  selfTestExitTable();
  selfTestCaseListShape();
  selfTestDecisionTable();
  selfTestOverridesAreInert();
  await selfTestFakeEngineExits();
  await selfTestFakeEngineAnswersAndPublishes();
  await selfTestFakeEngineHangs();
  await selfTestSlowStart();
}

// ---------------------------------------------------------------------------------------------
// --adapter: the actuator half, against one implementation
// ---------------------------------------------------------------------------------------------

async function runAdapter(name) {
  const modulePath = path.join(HERE, 'adapters', `${name}.mjs`);
  if (!fs.existsSync(modulePath)) {
    process.stderr.write(`supervisor-conformance: no adapter \`${name}\` at ${modulePath}\n`);
    process.exit(2);
  }
  const adapter = await import(`file://${modulePath}`);
  const policy = loadPolicy({ env: { ...process.env, ...HARNESS_OVERRIDES } });
  // `--case <id>` narrows a run while DEBUGGING one implementation. It is not a skip mechanism: the
  // filter is applied here and a filtered run reports how many cases it left out, so a green from a
  // narrowed run cannot be mistaken for a green from the contract.
  const only = process.argv.includes('--case') ? process.argv[process.argv.indexOf('--case') + 1] : null;
  const declared = actuatorCases();
  const cases = only ? declared.filter((c) => c.id === only) : declared;
  if (only) {
    process.stdout.write(
      `supervisor-conformance: NARROWED to \`${only}\` — ${declared.length - cases.length} case(s) not run.\n`,
    );
    if (cases.length === 0) {
      process.stderr.write(`no actuator case with id \`${only}\`\n`);
      process.exit(2);
    }
  }

  const available = adapter.available ? await adapter.available({ io }) : { ok: true };
  if (!available.ok) {
    // NOT a skip. An adapter that cannot run is an implementation that is not passing the contract,
    // and the harness says so with a non-zero exit rather than a reassuring line of output.
    record('adapter', `${name} is runnable`, [available.reason ?? 'adapter reported itself unavailable']);
    finish();
    return;
  }

  for (const testCase of cases) {
    let problems;
    try {
      // eslint-disable-next-line no-await-in-loop
      const outcome = await adapter.runCase({ testCase, policy, io });
      if (!outcome || !Array.isArray(outcome.problems)) {
        problems = ['adapter returned no result for this case — a skipped case fails the harness'];
      } else {
        problems = outcome.problems;
      }
    } catch (err) {
      problems = [`adapter threw: ${err?.stack ?? err}`];
    }
    record(`adapter:${name}`, testCase.id, problems);
  }
}

// ---------------------------------------------------------------------------------------------

async function main() {
  const argv = process.argv.slice(2);
  const adapterIndex = argv.indexOf('--adapter');
  if (argv.includes('--self-test')) {
    await selfTest();
  } else if (adapterIndex >= 0) {
    await runAdapter(argv[adapterIndex + 1]);
  } else {
    process.stderr.write(
      'usage: run.mjs --self-test | --adapter <dev-runner|tauri>\n',
    );
    process.exit(2);
  }
  finish();
}

await main();
