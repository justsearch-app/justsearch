import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test, { before, after } from 'node:test';
import { spawnSync, execFileSync } from 'node:child_process';
import { casesForAdapter, engineRow } from './contract.mjs';

const source = path.resolve(import.meta.dirname, '../..');
const tmp = path.join(source, 'tmp');
fs.mkdirSync(tmp, { recursive: true });
let fixture;
let loader;
const count = adapter => casesForAdapter(['start', 'discovery', 'quit', 'kill'], engineRow(), adapter).length;

before(() => {
  fixture = fs.mkdtempSync(path.join(tmp, 'conformance-reporting-'));
  execFileSync('git', ['clone', '--quiet', '--shared', '--no-checkout', source, fixture]);
  execFileSync('git', ['-C', fixture, 'checkout', '--quiet', '--detach', 'HEAD']);
  for (const file of ['run.mjs', 'timings.mjs', 'timings-preload.cjs', 'lifecycle.mjs', 'updater-hold.mjs',
    'adapters/dev-runner.mjs', 'adapters/tauri.mjs']) {
    fs.copyFileSync(path.join(source, 'scripts/supervisor-conformance', file), path.join(fixture, 'scripts/supervisor-conformance', file));
  }
  fs.copyFileSync(path.join(source, '.github/workflows/ci.yml'), path.join(fixture, '.github/workflows/ci.yml'));
  fs.copyFileSync(path.join(source, 'scripts/ci/test-windows-contract-diagnostics.mjs'), path.join(fixture, 'scripts/ci/test-windows-contract-diagnostics.mjs'));
  fs.symlinkSync(path.join(source, 'node_modules'), path.join(fixture, 'node_modules'), 'junction');
  loader = path.join(fixture, 'fault-loader.mjs');
  fs.writeFileSync(loader, `
    import fs from 'node:fs';
    import path from 'node:path';
    import { registerHooks } from 'node:module';
    let denied = false;
    const rm = fs.rmSync;
    fs.rmSync = function(file, ...args) {
      if (process.env.TEST_FAULT === 'delete' && !denied && /fixture-/.test(String(file))) {
        denied = true; throw Object.assign(new Error('injected delete failure'), { code: 'EACCES' });
      }
      return rm.call(this, file, ...args);
    };
    const mkdir = fs.mkdirSync;
    fs.mkdirSync = function(file, ...args) {
      if (process.env.TEST_FAULT === 'init' && path.basename(String(file)) === 'records') throw new Error('injected record initialization failure');
      return mkdir.call(this, file, ...args);
    };
    const write = fs.writeFileSync;
    fs.writeFileSync = function(file, ...args) {
      if (process.env.TEST_FAULT === 'write' && String(file).endsWith('.timing.json')) throw new Error('injected record write failure');
      return write.call(this, file, ...args);
    };
    registerHooks({ load(url, context, next) {
      if (/supervisor-conformance\\/adapters\\/(tauri|dev-runner)\\.mjs$/.test(url)) return { format: 'module', shortCircuit: true, source: \`
        import fs from 'node:fs';
        export const phases = ['start','discovery','quit','kill'];
        export const available = () => ({ ok: true });
        let calls = 0;
        export async function runCase({ testCase, io }) {
          const dir = io.makeWorkDir('fixture-' + testCase.id);
          fs.writeFileSync(dir + '/fixture.log', 'case ran');
          if (process.env.TEST_FAULT === 'finish') fs.writeFileSync(dir + '/spawn-timings.jsonl', 'invalid record');
          const cleanup = () => {
            if (process.env.TEST_CLEANUP === 'dev-runner') fs.rmSync(dir, { recursive: true, force: true });
            else { try { fs.rmSync(dir, { recursive: true, force: true }); }
              catch (err) { if (process.env.TEST_CLEANUP === 'updater') process.stderr.write('fixture cleanup retained ' + dir + ': ' + err.code + '\\\\n'); }
            }
          };
          if (io.deferCleanup) io.deferCleanup(dir, cleanup);
          else if (!io.timings && process.env.SUPERVISOR_CONFORMANCE_KEEP !== '1') cleanup();
          return { problems: ++calls === 1 && process.env.TEST_CASE_FAIL === '1' ? ['fixture scenario failure'] : [] };
        }
      \` };
      return next(url, context);
    } });
  `);
});
after(() => { if (fixture) fs.rmSync(fixture, { recursive: true, force: true }); });

const suiteDir = () => path.join(fixture, 'tmp/supervisor-conformance');
function run({ adapter = 'tauri', fault = '', fail = false, ci = '', keep = '', cleanup = adapter } = {}) {
  return spawnSync(process.execPath, ['--import', loader, path.join(fixture, 'scripts/supervisor-conformance/run.mjs'), '--adapter', adapter], {
    cwd: fixture, encoding: 'utf8', env: { ...process.env, CI: ci, SUPERVISOR_CONFORMANCE_KEEP: keep,
      TEST_FAULT: fault, TEST_CASE_FAIL: fail ? '1' : '', TEST_CLEANUP: cleanup },
  });
}
function reset() { fs.rmSync(suiteDir(), { recursive: true, force: true }); }
function complete(result, adapter, failures = 0) {
  assert.equal(result.status, failures ? 1 : 0, result.stderr);
  assert.equal((result.stdout.match(/^(PASS|FAIL)  adapter:/gm) ?? []).length, count(adapter), result.stdout + result.stderr);
  assert.match(result.stdout, new RegExp('supervisor-conformance: ' + (count(adapter) - failures) + '/' + count(adapter) + ' checks passed'));
}

for (const cleanup of ['tauri', 'updater', 'dev-runner']) {
  test(`${cleanup}: failed deletion preserves base handling and runs every following case`, () => {
    reset();
    const adapter = cleanup === 'dev-runner' ? 'dev-runner' : 'tauri';
    complete(run({ adapter, fault: 'delete', cleanup }), adapter, cleanup === 'dev-runner' ? 1 : 0);
  });
}
test('CI passing-case deletion failure preserves the base dev-runner verdict', () => {
  reset();
  const result = run({ adapter: 'dev-runner', ci: 'true', fault: 'delete' });
  complete(result, 'dev-runner', 1);
  assert.match(result.stdout, /injected delete failure/);
});
test('KEEP=1 retains every passing case folder and prints every keep message', () => {
  reset();
  const result = run({ adapter: 'dev-runner', keep: '1' });
  complete(result, 'dev-runner');
  const folders = fs.readdirSync(suiteDir()).filter(name => name !== 'records');
  assert.equal(folders.length, count('dev-runner'));
  assert.equal((result.stderr.match(/^\[keep\] /gm) ?? []).length, count('dev-runner'));
});
for (const fail of [false, true]) {
  test(`record write failure warns without changing a ${fail ? 'failing' : 'passing'} scenario`, () => {
    reset();
    const result = run({ fault: 'write', fail });
    complete(result, 'tauri', fail ? 1 : 0);
    assert.match(result.stderr, /timing.*warning.*injected record write failure/i);
  });
}
for (const fault of ['init', 'finish']) {
  test(`record ${fault} failure warns and leaves all scenarios passing`, () => {
    reset();
    const result = run({ fault });
    complete(result, 'tauri');
    assert.match(result.stderr, /timing.*warning/i);
  });
}
for (const options of [{}, { fail: true }, { keep: '1' }, { fail: true, keep: '1' }, { ci: 'true' }, { ci: 'true', fail: true }]) {
  test(`retention ${JSON.stringify(options)}`, () => {
    reset();
    const result = run(options);
    complete(result, 'tauri', options.fail ? 1 : 0);
    const folders = fs.readdirSync(suiteDir()).filter(name => name !== 'records');
    const retained = options.keep ? count('tauri') : options.ci && options.fail ? 1 : 0;
    assert.equal(folders.length, retained);
    assert.equal(fs.readdirSync(path.join(suiteDir(), 'records')).length, count('tauri'));
    if (options.keep && retained) assert.match(result.stderr, /\[keep\]/);
  });
}
test('local invocation replaces old records; both CI invocations remain complete', () => {
  reset();
  const records = path.join(suiteDir(), 'records'); fs.mkdirSync(records, { recursive: true });
  fs.writeFileSync(path.join(records, 'old.timing.json'), '{}');
  complete(run(), 'tauri');
  assert.equal(fs.existsSync(path.join(records, 'old.timing.json')), false);
  assert.equal(fs.readdirSync(records).length, count('tauri'));
  reset();
  complete(run({ ci: 'true' }), 'tauri');
  complete(run({ ci: 'true' }), 'tauri');
  assert.equal(fs.readdirSync(records).length, count('tauri') * 2);
});
test('Windows diagnostics test creates tmp in a fresh checkout', () => {
  fs.rmSync(path.join(fixture, 'tmp'), { recursive: true, force: true });
  const env = { ...process.env }; delete env.NODE_TEST_CONTEXT;
  const result = spawnSync(process.execPath, ['--test', 'scripts/ci/test-windows-contract-diagnostics.mjs'], { cwd: fixture, encoding: 'utf8', env });
  assert.equal(result.status, 0, result.stdout + result.stderr);
});
test('both CI regressions have explicit hosted commands beside their sibling tests', () => {
  const workflow = fs.readFileSync(path.join(source, '.github/workflows/ci.yml'), 'utf8');
  const lane = workflow.slice(workflow.indexOf('      - name: Governance production coverage regression tests'), workflow.indexOf('      - name: Test workflow npm audit policy'));
  for (const file of ['test-windows-contract-diagnostics.mjs', 'test-verify-unit-test-shard-policy.mjs']) assert.ok(lane.includes('node --test scripts/ci/' + file), file);
});
