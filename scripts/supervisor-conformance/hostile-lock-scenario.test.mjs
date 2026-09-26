import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { exerciseHostileLocks } from './hostile-lock-scenario.mjs';

const OPERATION_KEY = '01996c43-8300-7000-8000-000000000001';
const RUN_ID = 'owned-run';
const INITIAL_INCARNATION = 2;

function fixture(t, options = {}) {
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-hostile-lock-'));
  const previousScenario = process.env.JUSTSEARCH_REAL_RECOVERY_SCENARIO;
  process.env.JUSTSEARCH_REAL_RECOVERY_SCENARIO = 'lock-boot';

  t.after(() => {
    if (previousScenario === undefined) delete process.env.JUSTSEARCH_REAL_RECOVERY_SCENARIO;
    else process.env.JUSTSEARCH_REAL_RECOVERY_SCENARIO = previousScenario;
    const resolved = path.resolve(work);
    assert.equal(path.dirname(resolved), path.resolve(os.tmpdir()));
    assert.ok(path.basename(resolved).startsWith('justsearch-hostile-lock-'));
    fs.rmSync(resolved, { recursive: true, force: true });
  });

  let submitted = false;
  let ingestPosts = 0;
  let searchPosts = 0;
  let operationGets = [];
  let submittedPaths = [];
  let lastSupervisor = null;
  let successorReads = 0;
  let acknowledgedRelease = false;
  let releasedExit = null;
  let operationGetIncarnations = [];
  const initialBinding = {
    state: 'running',
    runId: RUN_ID,
    pid: 123,
    instanceId: 'instance-2',
    incarnation: INITIAL_INCARNATION,
    lastExit: null,
  };
  const qualifyingExit = {
    counted: true,
    incarnation: INITIAL_INCARNATION,
    reason: 'fatal_or_uncaught',
  };
  const staleExit = { counted: true, incarnation: INITIAL_INCARNATION - 1, reason: 'fatal_or_uncaught' };

  const supervisorForRead = () => {
    if (!submitted) return { ...initialBinding };
    if (options.countedExit && !fs.existsSync(path.join(work, 'intruder-stopped'))) {
      return { ...initialBinding, state: 'stopped', lastExit: { ...qualifyingExit } };
    }
    if (options.countedExit) {
      successorReads += 1;
      const incarnation = successorReads === 1
        ? INITIAL_INCARNATION
        : INITIAL_INCARNATION + 1;
      return {
        state: 'running',
        runId: RUN_ID,
        pid: 123 + incarnation,
        instanceId: `instance-${incarnation}`,
        incarnation,
        lastExit: { ...qualifyingExit },
      };
    }
    return {
      ...initialBinding,
      lastExit: options.staleExit ? { ...staleExit } : null,
    };
  };

  const readJson = (file) => {
    if (file.endsWith('supervisor.v1.json')) {
      lastSupervisor = supervisorForRead();
      return lastSupervisor;
    }
    if (file.endsWith('manifest.json')) {
      assert.ok(lastSupervisor, 'the supervisor snapshot must be read before its manifest');
      return {
        pid: lastSupervisor.pid,
        instanceId: lastSupervisor.instanceId,
        head: { apiPort: 12345 },
      };
    }
    throw new Error(`unexpected JSON path: ${file}`);
  };

  const waitFor = async (label, _timeoutMs, probe) => {
    if (label.includes('releases hostile locks after')) {
      assert.ok(fs.existsSync(path.join(work, 'intruder-stop')),
        'hostile locks are released only after the qualifying exit is observed');
      assert.equal(lastSupervisor?.runId, RUN_ID);
      assert.equal(lastSupervisor?.lastExit?.counted, true);
      assert.ok(lastSupervisor.lastExit.incarnation >= INITIAL_INCARNATION);
      releasedExit = { ...lastSupervisor.lastExit };
      fs.writeFileSync(path.join(work, 'intruder-stopped'), 'closed');
      acknowledgedRelease = true;
    }

    if (label.startsWith('the original operation completes')) {
      for (let attempt = 0; attempt < 4; attempt += 1) {
        const result = await probe();
        if (result) return result;
      }
      throw new Error(`simulated wait timeout: ${label}`);
    }

    const result = await probe();
    assert.ok(result, `unsatisfied probe: ${label}`);
    return result;
  };

  const post = async (_port, endpoint, body) => {
    if (endpoint === '/api/knowledge/ingest') {
      ingestPosts += 1;
      assert.equal(ingestPosts, 1, 'an accepted or refused ingest is submitted exactly once');
      assert.equal(body.paths.length, 100, 'the scenario submits the complete 100-file corpus');
      assert.match(body.idempotencyKey,
        /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
      assert.equal(fs.existsSync(path.join(work, 'intruder-stop')), false,
        'the intruder remains active through submission');
      submittedPaths = body.paths;
      submitted = true;

      if (options.lostAcceptance) {
        const error = new Error('acceptance socket closed');
        error.code = 'ECONNRESET';
        throw error;
      }
      if (options.http500) {
        return { status: 500, text: JSON.stringify({ success: false, error: 'refused' }) };
      }
      if (options.operationRefused) {
        return { status: 200, text: JSON.stringify({ success: false, errorClass: 'HANDLER_FAILURE' }) };
      }
      return {
        status: 200,
        text: JSON.stringify({
          success: true,
          structuredData: { operationKey: body.idempotencyKey, operationRecordId: 17 },
        }),
      };
    }

    assert.equal(endpoint, '/api/knowledge/search');
    searchPosts += 1;
    if (options.countedExit) {
      assert.ok(acknowledgedRelease, 'the lock intruder must stop after a counted exit');
      assert.ok(lastSupervisor.incarnation > qualifyingExit.incarnation,
        'search is attempted only on a higher, healthy incarnation');
    } else {
      assert.equal(fs.existsSync(path.join(work, 'intruder-stop')), false,
        'a healthy run must not stop the lock intruder');
    }
    const matchedCount = options.matchedCount ?? 100;
    return {
      status: 200,
      text: JSON.stringify({
        results: submittedPaths.slice(0, matchedCount).map((file) => ({ fields: { path: file } })),
      }),
    };
  };

  const request = async (_port, endpoint) => {
    if (endpoint.startsWith('/api/operation-history/')) {
      operationGets.push(endpoint);
      assert.equal(endpoint, `/api/operation-history/${OPERATION_KEY}`,
        'lost acceptance is resolved only by querying the exact supplied operation key');
      assert.equal(ingestPosts, 1, 'outcome resolution must not resubmit ingest');
      if (options.lostAcceptance) {
        assert.ok(acknowledgedRelease, 'do not resolve a lost response before a qualifying counted exit');
        assert.equal(releasedExit?.counted, true);
        assert.ok(releasedExit.incarnation >= INITIAL_INCARNATION);
      }
      if (options.countedExit) {
        assert.ok(acknowledgedRelease);
        assert.ok(lastSupervisor.incarnation > qualifyingExit.incarnation,
          'the outcome is read only after the successor incarnation exceeds the counted exit');
        operationGetIncarnations.push(lastSupervisor.incarnation);
      }
      return {
        status: 200,
        text: JSON.stringify({
          operationKey: OPERATION_KEY,
          state: options.outcomeState ?? 'complete',
          result: { code: options.outcomeCode ?? 'SUCCESS' },
          unitsFailed: options.unitsFailed ?? 0,
        }),
      };
    }
    if (endpoint === '/api/health') return { status: 200, text: '{}' };
    throw new Error(`unexpected request path: ${endpoint}`);
  };

  return {
    work,
    data: path.join(work, 'data'),
    first: { runId: RUN_ID, incarnation: INITIAL_INCARNATION },
    readJson,
    waitFor,
    request,
    post,
    createOperationKey: () => OPERATION_KEY,
    requireOperationSuccess: (response, label) => {
      assert.equal(response.status, 200, `${label}: expected HTTP 200, got ${response.status}`);
      const body = JSON.parse(response.text);
      assert.equal(body.success, true, `${label}: ${response.text}`);
      return {
        operationKey: body.structuredData.operationKey,
        operationRecordId: body.structuredData.operationRecordId,
      };
    },
    requireThat: (condition, message) => assert.ok(condition, message),
    stats: () => ({
      ingestPosts,
      searchPosts,
      operationGets: [...operationGets],
      successorReads,
      acknowledgedRelease,
      releasedExit: releasedExit && { ...releasedExit },
      operationGetIncarnations: [...operationGetIncarnations],
      intruderStopped: fs.existsSync(path.join(work, 'intruder-stop')),
    }),
  };
}

async function exercise(t, options) {
  const c = fixture(t, options);
  await exerciseHostileLocks(c);
  return c;
}

test('healthy hostile-lock run submits once, indexes all 100, and never stops the intruder', async (t) => {
  const c = await exercise(t, {});
  const stats = c.stats();
  assert.equal(stats.ingestPosts, 1);
  assert.equal(stats.operationGets.length, 1);
  assert.equal(stats.searchPosts, 1);
  assert.equal(stats.intruderStopped, false);
});

test('explicit HTTP 500 refusal is not replayed', async (t) => {
  const c = fixture(t, { http500: true });
  await assert.rejects(exerciseHostileLocks(c), /hostile-lock ingest/);
  const stats = c.stats();
  assert.equal(stats.ingestPosts, 1);
  assert.equal(stats.operationGets.length, 0);
  assert.equal(stats.searchPosts, 0);
  assert.equal(stats.intruderStopped, false);
});

test('lost acceptance waits for counted exit, then reads the exact key without replay', async (t) => {
  const c = fixture(t, { countedExit: true, lostAcceptance: true });
  await exerciseHostileLocks(c);
  const stats = c.stats();
  assert.equal(stats.ingestPosts, 1);
  assert.deepEqual(stats.operationGets, [`/api/operation-history/${OPERATION_KEY}`]);
  assert.equal(stats.acknowledgedRelease, true);
  assert.ok(stats.successorReads >= 2, 'same-incarnation observation must be rejected before polling successor');
  assert.deepEqual(stats.operationGetIncarnations, [INITIAL_INCARNATION + 1]);
  assert.equal(stats.intruderStopped, true);
});

test('HTTP 200 with an operation refusal is not treated as acceptance or replayed', async (t) => {
  const c = fixture(t, { operationRefused: true });
  await assert.rejects(exerciseHostileLocks(c), /hostile-lock ingest/);
  const stats = c.stats();
  assert.equal(stats.ingestPosts, 1);
  assert.equal(stats.operationGets.length, 0);
  assert.equal(stats.searchPosts, 0);
  assert.equal(stats.intruderStopped, false);
});

test('a stale counted exit does not release hostile locks', async (t) => {
  const c = await exercise(t, { staleExit: true });
  const stats = c.stats();
  assert.equal(stats.ingestPosts, 1);
  assert.equal(stats.operationGets.length, 1);
  assert.equal(stats.intruderStopped, false);
});

for (const outcomeState of ['unknown', 'failed']) {
  test(`${outcomeState.toUpperCase()} operation outcome cannot pass`, async (t) => {
    const c = fixture(t, { outcomeState });
    await assert.rejects(
      exerciseHostileLocks(c),
      /original hostile-lock operation must remain recoverable/,
    );
    const stats = c.stats();
    assert.equal(stats.ingestPosts, 1);
    assert.deepEqual(stats.operationGets, [`/api/operation-history/${OPERATION_KEY}`]);
    assert.equal(stats.searchPosts, 1,
      'the scenario may probe search, but must reject this durable operation outcome');
    assert.equal(stats.intruderStopped, false);
  });
}

test('partial corpus cannot pass even with a complete SUCCESS outcome', async (t) => {
  const c = fixture(t, { matchedCount: 99 });
  await assert.rejects(exerciseHostileLocks(c), /simulated wait timeout/);
  const stats = c.stats();
  assert.equal(stats.ingestPosts, 1);
  assert.equal(stats.operationGets.length, 4);
  assert.equal(stats.searchPosts, 4);
  assert.equal(stats.intruderStopped, false);
});

test('counted fatal exit releases locks and requires a higher incarnation to pass', async (t) => {
  const c = await exercise(t, { countedExit: true });
  const stats = c.stats();
  assert.equal(stats.ingestPosts, 1);
  assert.equal(stats.acknowledgedRelease, true);
  assert.ok(stats.successorReads >= 2);
  assert.deepEqual(stats.operationGetIncarnations, [INITIAL_INCARNATION + 1]);
  assert.equal(stats.intruderStopped, true);
});
