import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { exerciseHostileLocks } from './hostile-lock-scenario.mjs';

for (const { name, fatalExit, staleExit, operationSuccess } of [
  { name: 'healthy run stays attacked', fatalExit: false, operationSuccess: true },
  { name: 'release after submission incarnation exits', fatalExit: true, operationSuccess: true },
  { name: 'old counted exit does not release the new attack', staleExit: true, operationSuccess: true },
  { name: 'operation refusal fails', fatalExit: false, operationSuccess: false },
]) {
  test(`hostile locks: ${name}`, async (t) => {
    const work = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-lock-phase-'));
    const prior = process.env.JUSTSEARCH_REAL_RECOVERY_SCENARIO;
    process.env.JUSTSEARCH_REAL_RECOVERY_SCENARIO = 'lock-boot';
    t.after(() => {
      if (prior === undefined) delete process.env.JUSTSEARCH_REAL_RECOVERY_SCENARIO;
      else process.env.JUSTSEARCH_REAL_RECOVERY_SCENARIO = prior;
      const resolved = path.resolve(work);
      assert.equal(path.dirname(resolved), path.resolve(os.tmpdir()));
      assert.ok(path.basename(resolved).startsWith('justsearch-lock-phase-'));
      fs.rmSync(resolved, { recursive: true, force: true });
    });
    let submitted = false;
    let acknowledgedRelease = false;
    let searchCount = 0;
    let submittedPaths;
    const run = exerciseHostileLocks({
      work, data: path.join(work, 'data'), first: { runId: 'owned', incarnation: 2 },
      readJson: (file) => file.endsWith('supervisor.v1.json')
        ? { state: 'running', runId: 'owned', pid: 123, instanceId: 'incarnation', incarnation: 2,
          lastExit: submitted && fatalExit
            ? { counted: true, incarnation: 2, reason: 'fatal_or_uncaught' }
            : staleExit ? { counted: true, incarnation: 1, reason: 'fatal_or_uncaught' } : null }
        : { pid: 123, instanceId: 'incarnation', head: { apiPort: 12345 } },
      waitFor: async (label, timeout, probe) => {
        if (label.includes('releases hostile locks after')) {
          assert.ok(submitted, 'do not remove the attack before accepted indexing work');
          assert.ok(fs.existsSync(path.join(work, 'intruder-stop')));
          acknowledgedRelease = true;
          fs.writeFileSync(path.join(work, 'intruder-stopped'), 'closed');
        }
        const result = await probe();
        assert.ok(result, `unsatisfied probe: ${label}`);
        return result;
      },
      post: async (port, endpoint, body) => {
        if (endpoint.endsWith('/ingest')) {
          assert.equal(body.paths.length, 100);
          assert.match(body.idempotencyKey,
            /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
          assert.equal(fs.existsSync(path.join(work, 'intruder-stop')), false);
          submittedPaths = body.paths;
          submitted = true;
          return { status: 200, text: JSON.stringify({
            success: operationSuccess,
            structuredData: operationSuccess
              ? { operationKey: body.idempotencyKey, operationRecordId: 17 }
              : {},
            errorClass: operationSuccess ? undefined : 'HANDLER_FAILURE',
          }) };
        }
        searchCount += 1;
        assert.equal(acknowledgedRelease, Boolean(fatalExit),
          'a fatal injection ends before probing successor recovery; a healthy run stays attacked');
        return { status: 200, text: JSON.stringify({ results: [
          { fields: { path: submittedPaths[0] } },
        ] }) };
      },
      request: async () => ({ status: 200 }),
      createOperationKey: () => '01996c43-8300-7000-8000-000000000001',
      requireOperationSuccess: (response, label) => {
        const body = JSON.parse(response.text);
        assert.equal(body.success, true, `${label}: ${response.text}`);
        return { operationKey: body.structuredData.operationKey,
          operationRecordId: body.structuredData.operationRecordId };
      },
      requireThat: (condition, message) => assert.ok(condition, message),
    });
    if (!operationSuccess) {
      await assert.rejects(run, /hostile-lock ingest/);
      assert.equal(searchCount, 0);
      assert.equal(fs.existsSync(path.join(work, 'intruder-stop')), false);
    } else {
      await run;
      assert.equal(searchCount, 1);
      assert.equal(fs.existsSync(path.join(work, 'intruder-stop')), Boolean(fatalExit));
    }
  });
}
