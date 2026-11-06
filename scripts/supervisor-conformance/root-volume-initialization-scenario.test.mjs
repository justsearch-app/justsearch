import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { DatabaseSync } from 'node:sqlite';
import { prepareRootVolumeInitialization, exerciseRootVolumeInitialization } from './root-volume-initialization-scenario.mjs';

// Tests only scenario orchestration. The Windows JUnit check owns the real OS-lock proof.
function fixture(t, options = {}) {
  const platform = Object.getOwnPropertyDescriptor(process, 'platform');
  if (options.windows) {
    Object.defineProperty(process, 'platform', { value: 'win32' });
    t.after(() => Object.defineProperty(process, 'platform', platform));
  }
  const keyFor = file => process.platform === 'win32' ? path.resolve(file).toLowerCase() : path.resolve(file);
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'Root-Volume-Scenario-'));
  t.after(() => fs.rmSync(work, { recursive: true, force: true }));
  const data = path.join(work, 'data');
  const indexBase = path.join(work, 'index');
  fs.mkdirSync(path.join(data, 'logs'), { recursive: true });
  fs.mkdirSync(indexBase);
  prepareRootVolumeInitialization({ work, data });
  if (!options.noLock) fs.writeFileSync(path.join(work, 'root-volume-lock-held'), 'fixture lock witness');
  fs.writeFileSync(path.join(data, 'logs', 'engine.log'), options.noFailure ? ''
    : 'Upgrade seeding of root volumes could not start; roots stay unconfirmed (The process cannot access the file because another process has locked a portion of the file)');
  const first = { runId: 'owned-run', pid: 123, instanceId: 'owned-instance', incarnation: 1 };
  const key = '01996c43-8300-7000-8000-000000000001';
  let submissions = 0;
  let paths = [];
  let refusedPaths = [];
  const gets = [];
  const waitFor = async (label, _bound, probe) => {
    if (label.startsWith('JUnit releases')) {
      assert.ok(fs.existsSync(path.join(work, 'root-volume-lock-release')));
      fs.writeFileSync(path.join(work, 'root-volume-lock-released'), 'fixture acknowledgment');
    }
    const result = await probe();
    assert.ok(result, `unsatisfied scenario probe: ${label}`);
    if (label.startsWith('the accepted original job')) refusedPaths = result.map(row => row.path);
    return result;
  };
  const c = { work, data, indexBase, first, apiPort: 12345, waitFor,
    createOperationKey: () => key,
    requireThat: (condition, message) => assert.ok(condition, message),
    requireOperationSuccess: response => {
      assert.equal(response.status, 200);
      return { operationKey: key };
    },
    readJson: file => {
      const changed = options.restarted && fs.existsSync(path.join(work, 'root-volume-lock-released'));
      return file.endsWith('manifest.json')
        ? { pid: first.pid, instanceId: first.instanceId }
        : { ...first, incarnation: changed ? 2 : 1 };
    },
    request: async (_port, endpoint) => {
      if (endpoint === '/api/health') return { status: 200 };
      assert.equal(endpoint, `/api/operation-history/${key}`);
      gets.push(endpoint);
      // OperationOutcomeView correlates through the requested endpoint, not an operationKey field.
      return { status: 200, text: JSON.stringify({ state: options.state ?? 'complete',
        historySince: 0, acceptedAt: 1, completedAt: 2, unitsCompleted: 2,
        result: { code: options.result ?? 'SUCCESS' }, unitsFailed: options.unitsFailed ?? 0 }) };
    },
    post: async (_port, endpoint, body) => {
      if (endpoint.endsWith('/ingest')) {
        submissions++;
        assert.equal(body.idempotencyKey, key);
        paths = body.paths;
        const database = new DatabaseSync(path.join(data, 'jobs.db'));
        try {
          database.exec('CREATE TABLE jobs (path TEXT, state TEXT, last_reason_code TEXT)');
          // driveParent cannot admit the second root while the first child is deferred.
          for (const file of paths.slice(0, options.bothQueued ? 2 : 1)) database.prepare('INSERT INTO jobs VALUES (?, ?, ?)')
            .run(keyFor(options.wrongPath ? `${file}.foreign` : file), options.jobState ?? 'PENDING',
              options.noRefusal ? 'OTHER' : 'ROOT_UNAVAILABLE');
        } finally { database.close(); }
        return { status: 200 };
      }
      assert.equal(endpoint, '/api/knowledge/search');
      return { status: 200, text: JSON.stringify({ results: paths.slice(options.partial ? 1 : 0)
        .map(file => ({ fields: { path: options.foreign ? `${file}.foreign`
          : options.wrongCase ? file.toUpperCase() : keyFor(file) } })) }) };
    },
  };
  return { c, stats: () => ({ submissions, gets, paths, keys: paths.map(keyFor), refusedPaths,
    released: fs.existsSync(path.join(work, 'root-volume-lock-release')) }) };
}

test('first child alone can prove refusal before both original documents recover', async t => {
  const { c, stats } = fixture(t);
  await exerciseRootVolumeInitialization(c);
  assert.equal(stats().submissions, 1);
  assert.equal(stats().paths.length, 2);
  assert.deepEqual(stats().refusedPaths, [stats().keys[0]]);
  assert.deepEqual(stats().gets, ['/api/operation-history/01996c43-8300-7000-8000-000000000001']);
  assert.equal(stats().released, true);
});
test('fixture also accepts both original paths already queued and refused', async t => {
  const { c, stats } = fixture(t, { bothQueued: true });
  await exerciseRootVolumeInitialization(c);
  assert.deepEqual(stats().refusedPaths, stats().keys);
  assert.equal(stats().submissions, 1);
});
for (const [name, options, expected] of [
  ['missing OS lock witness', { noLock: true }, /real pending-ledger lock/],
  ['foreign refused path', { wrongPath: true }, /ROOT_UNAVAILABLE/],
  ['wrong refused state', { jobState: 'PROCESSING' }, /ROOT_UNAVAILABLE/],
  ['missing boot fault', { noFailure: true }, /actual boot initialization write failure/],
  ['job without root refusal', { noRefusal: true }, /ROOT_UNAVAILABLE/],
  ['Engine restart', { restarted: true }, /same supervised Engine incarnation/],
  ['failed original operation', { state: 'failed' }, /failed/],
  ['unknown original operation', { state: 'unknown' }, /unknown/],
  ['expired original operation', { state: 'expired' }, /expired/],
  ['incomplete original operation', { state: 'running' }, /every document searches/],
  ['unsuccessful original operation', { result: 'ERROR' }, /ERROR/],
  ['foreign search paths', { foreign: true }, /every document searches/],
  ['nonzero failed units', { unitsFailed: 1 }, /unitsFailed/],
  ['partial search results', { partial: true }, /every document searches/],
]) test(`scenario cannot pass with ${name}`, async t => {
  const { c, stats } = fixture(t, options);
  await assert.rejects(exerciseRootVolumeInitialization(c), expected);
  assert.ok(stats().submissions <= 1);
  if (options.noLock || options.noFailure || options.noRefusal || options.wrongPath || options.jobState) assert.equal(stats().released, false);
});

for (const bothQueued of [false, true]) test(`mixed-case Windows inputs match canonical queue keys (${bothQueued ? 'both children' : 'first child'})`, async t => {
  const { c, stats } = fixture(t, { windows: true, bothQueued });
  await exerciseRootVolumeInitialization(c);
  assert.notEqual(stats().paths[0], stats().keys[0]);
  assert.deepEqual(stats().refusedPaths, stats().keys.slice(0, bothQueued ? 2 : 1));
  assert.equal(stats().submissions, 1);
});

test('non-Windows search identity remains case sensitive', async t => {
  if (process.platform === 'win32') return t.skip('requires case-sensitive non-Windows semantics');
  const { c } = fixture(t, { wrongCase: true });
  await assert.rejects(exerciseRootVolumeInitialization(c), /every document searches/);
});
