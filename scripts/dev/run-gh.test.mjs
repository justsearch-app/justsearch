/**
 * Tempdoc 743 second wave, P-K — unit tests for run-gh.mjs's pure logic.
 *
 * Covers: the cli/cli#7866 bitwise exit decode, the cli/cli#7401 "not registered yet"
 * detection, and gh-binary resolution. Live smoke tests (real `gh pr checks` against a
 * merged/nonexistent PR) are run manually and reported separately — a unit test cannot
 * safely depend on live GitHub state or wall-clock polling.
 *
 * Run with: `node scripts/dev/run-gh.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {
  decodeChecksExit,
  isUnregistered,
  resolveGhBin,
  buildChecksArgs,
  classifyMergeSnapshot,
  classifyWorkflowRun,
  enqueuePullRequest,
  mergeWait,
  parseEnqueueArgs,
  parseValueFlag,
  parseRequiredOnly,
  parseTimeoutSec,
  selectWorkflowRun,
  runWaitSha,
} from './run-gh.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

async function runAsync(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

// --- decodeChecksExit: the cli/cli#7866 bitwise contract ---
run('exit 0 decodes to pass', () => {
  assert.equal(decodeChecksExit(0), 'pass');
});
run('exit 1 (fail bit only) decodes to fail', () => {
  assert.equal(decodeChecksExit(1), 'fail');
});
run('exit 8 (pending bit only) decodes to pending', () => {
  assert.equal(decodeChecksExit(8), 'pending');
});
run('exit 9 (fail + pending bits) decodes to fail — fail bit is terminal', () => {
  assert.equal(decodeChecksExit(9), 'fail');
});
run('exit 2 (neither bit) decodes to unknown', () => {
  assert.equal(decodeChecksExit(2), 'unknown');
});
run('null status decodes to unknown', () => {
  assert.equal(decodeChecksExit(null), 'unknown');
});

// --- isUnregistered: the cli/cli#7401 pre-registration ambiguity ---
run('exit 1 with "no checks reported" text is unregistered, not a failure', () => {
  assert.equal(
    isUnregistered({ status: 1, stdout: '', stderr: "no checks reported on the 'main' branch" }),
    true,
  );
});
run('exit 1 with a real failing-check table is NOT unregistered', () => {
  assert.equal(
    isUnregistered({ status: 1, stdout: 'X  build   fail   2m30s\n', stderr: '' }),
    false,
  );
});
run('exit 0 with a passing-check table is NOT unregistered', () => {
  assert.equal(isUnregistered({ status: 0, stdout: 'checks  pass  1m\n', stderr: '' }), false);
});
run('exit 8 with a pending-check table is NOT unregistered', () => {
  assert.equal(isUnregistered({ status: 8, stdout: 'checks  pending  10s\n', stderr: '' }), false);
});
run('completely empty non-zero result reads as unregistered', () => {
  assert.equal(isUnregistered({ status: 1, stdout: '', stderr: '' }), true);
});
run('exit 1 with "no required checks reported" text is unregistered (gh 2.90.0 --required variant)', () => {
  assert.equal(
    isUnregistered({
      status: 1,
      stdout: '',
      stderr: "no required checks reported on the 'x' branch",
    }),
    true,
  );
});
run('exit 1 with the plain "no checks reported" text is still unregistered', () => {
  assert.equal(
    isUnregistered({ status: 1, stdout: '', stderr: "no checks reported on the 'x' branch" }),
    true,
  );
});

// --- resolveGhBin ---
run('JUSTSEARCH_GH_BIN override wins unconditionally', () => {
  assert.equal(
    resolveGhBin({ JUSTSEARCH_GH_BIN: 'C:\\custom\\gh.exe' }),
    'C:\\custom\\gh.exe',
  );
});
run('falls back to plain "gh" when no scoop install and no override', () => {
  const bogusRoot = path.join(os.tmpdir(), 'no-such-scoop-root-743');
  assert.equal(resolveGhBin({ SCOOP: bogusRoot }), 'gh');
});
if (process.platform === 'win32' && fs.existsSync('F:\\scoop\\apps\\gh')) {
  run('resolves the live scoop-installed gh binary on this box', () => {
    const bin = resolveGhBin({});
    assert.ok(bin.endsWith('gh.exe'), `expected a resolved gh.exe path, got: ${bin}`);
    assert.ok(fs.existsSync(bin), `resolved path does not exist: ${bin}`);
  });
}

// --- buildChecksArgs / parseRequiredOnly: 829 R1 required-only gating ---
run('buildChecksArgs without requiredOnly omits --required (backward-compat)', () => {
  assert.deepEqual(buildChecksArgs(443, false), ['pr', 'checks', '443']);
});
run('buildChecksArgs without requiredOnly arg (undefined) also omits --required', () => {
  assert.deepEqual(buildChecksArgs(443), ['pr', 'checks', '443']);
});
run('buildChecksArgs with requiredOnly appends --required', () => {
  assert.deepEqual(buildChecksArgs(443, true), ['pr', 'checks', '443', '--required']);
});
run('parseRequiredOnly detects --required-only and strips it from rest', () => {
  const { requiredOnly, rest } = parseRequiredOnly(['443', '--required-only', '--timeout-sec', '60']);
  assert.equal(requiredOnly, true);
  assert.deepEqual(rest, ['443', '--timeout-sec', '60']);
});
run('parseRequiredOnly is false and rest is unchanged when flag absent', () => {
  const { requiredOnly, rest } = parseRequiredOnly(['443', '--timeout-sec', '60']);
  assert.equal(requiredOnly, false);
  assert.deepEqual(rest, ['443', '--timeout-sec', '60']);
});

// --- parseTimeoutSec: --timeout-sec with a missing/non-numeric value must not swallow the
// following flag (review fix, PR #445) ---
run('parseTimeoutSec with --timeout-sec immediately followed by another flag keeps default and does not eat the next token', () => {
  const { timeoutSec, rest } = parseTimeoutSec(['445', '--timeout-sec', '--required-only']);
  assert.equal(timeoutSec, 1800);
  assert.deepEqual(rest, ['445', '--required-only']);
});
run('full checks-wait argv with a valueless --timeout-sec still yields requiredOnly=true and default timeout', () => {
  const { timeoutSec, rest: afterTimeout } = parseTimeoutSec([
    '445',
    '--timeout-sec',
    '--required-only',
  ]);
  const { requiredOnly, rest } = parseRequiredOnly(afterTimeout);
  assert.equal(timeoutSec, 1800);
  assert.equal(requiredOnly, true);
  assert.deepEqual(rest, ['445']);
});
run('parseTimeoutSec with a valid numeric value still parses normally', () => {
  const { timeoutSec, rest } = parseTimeoutSec(['445', '--timeout-sec', '60', '--required-only']);
  assert.equal(timeoutSec, 60);
  assert.deepEqual(rest, ['445', '--required-only']);
});

// --- publication transition waits ---
run('merged PR snapshot is terminal success and carries the landed SHA', () => {
  assert.deepEqual(classifyMergeSnapshot({ state: 'MERGED', mergeCommit: { oid: 'abc123' } }), {
    verdict: 'pass', key: 'MERGED:abc123', message: 'merged at abc123',
  });
});
run('closed unmerged PR snapshot is terminal failure', () => {
  assert.equal(classifyMergeSnapshot({ state: 'CLOSED' }).verdict, 'fail');
});
run('open PR snapshot remains pending and exposes queue-state transitions', () => {
  assert.deepEqual(classifyMergeSnapshot({ state: 'OPEN', mergeStateStatus: 'QUEUED' }), {
    verdict: 'pending', key: 'OPEN:QUEUED', message: 'OPEN / QUEUED',
  });
});
run('exact-SHA run selection rejects other events and chooses the newest match', () => {
  const selected = selectWorkflowRun([
    { databaseId: 1, headSha: 'abc', event: 'push', createdAt: '2026-01-01T00:00:00Z' },
    { databaseId: 2, headSha: 'abc', event: 'pull_request', createdAt: '2026-01-03T00:00:00Z' },
    { databaseId: 3, headSha: 'abc', event: 'push', createdAt: '2026-01-02T00:00:00Z' },
    { databaseId: 4, headSha: 'def', event: 'push', createdAt: '2026-01-04T00:00:00Z' },
  ], 'abc', 'push');
  assert.equal(selected.databaseId, 3);
});
run('workflow run classification distinguishes registration, progress, pass, and failure', () => {
  assert.equal(classifyWorkflowRun(null).key, 'UNREGISTERED');
  assert.equal(classifyWorkflowRun({ databaseId: 5, status: 'in_progress' }).verdict, 'pending');
  assert.equal(classifyWorkflowRun({ databaseId: 5, status: 'completed', conclusion: 'success' }).verdict, 'pass');
  assert.equal(classifyWorkflowRun({ databaseId: 5, status: 'completed', conclusion: 'cancelled' }).verdict, 'fail');
});
run('parseValueFlag extracts a named value without swallowing a following flag', () => {
  assert.deepEqual(parseValueFlag(['abc', '--workflow', 'CI', '--event', 'push'], '--workflow', 'default'), {
    value: 'CI', rest: ['abc', '--event', 'push'],
  });
  assert.deepEqual(parseValueFlag(['abc', '--workflow', '--event', 'push'], '--workflow', 'default'), {
    value: 'default', rest: ['abc', '--event', 'push'],
  });
});
await runAsync('merge-wait times out deterministically while a PR stays pending', async () => {
  let clock = 0;
  const code = await mergeWait('gh', 7, 1, {
    loadJson: () => ({ value: { state: 'OPEN', mergeStateStatus: 'QUEUED' } }),
    now: () => clock,
    pause: async () => { clock = 1000; },
    emit: (_prefix, classified) => classified.key,
  });
  assert.equal(code, 3);
});
await runAsync('run-wait-sha handles registration followed by exact-SHA success', async () => {
  let call = 0;
  const code = await runWaitSha('gh', 'abc', 1, { workflow: 'CI', event: 'push' }, {
    loadJson: () => ({ value: call++ === 0 ? [] : [{ databaseId: 9, headSha: 'abc', event: 'push', status: 'completed', conclusion: 'success' }] }),
    now: () => 0,
    pause: async () => {},
    emit: (_prefix, classified) => classified.key,
  });
  assert.equal(code, 0);
  assert.equal(call, 2);
});

// --- validated publication enqueue gateway ---
run('parseEnqueueArgs accepts only a PR number and optional repository slug', () => {
  assert.deepEqual(parseEnqueueArgs(['933']), { prNumber: 933, repo: null });
  assert.deepEqual(parseEnqueueArgs(['933', '--repo', 'justsearch-app/justsearch']), {
    prNumber: 933,
    repo: 'justsearch-app/justsearch',
  });
  assert.throws(() => parseEnqueueArgs(['0']), /positive/);
  for (const ambiguous of ['1e3', '0x10', '+7', '7.0', '01']) {
    assert.throws(() => parseEnqueueArgs([ambiguous]), /positive/, ambiguous);
  }
  assert.throws(() => parseEnqueueArgs(['999999999999999999999']), /safe-integer/);
  assert.throws(() => parseEnqueueArgs(['933', '--squash']), /accepts only/);
  assert.throws(() => parseEnqueueArgs(['933', '--repo', 'invalid']), /owner\/repo/);
});

const snapshot = { headRefOid: 'a'.repeat(40), title: 'Validated title', body: 'Body\nwith lines', isDraft: false, mergeable: 'MERGEABLE' };
const snapshotResult = (value = snapshot) => ({ status: 0, stdout: JSON.stringify(value) });
run('enqueue binds squash auto-merge to the validated head, title and body', () => {
  const calls = [];
  const code = enqueuePullRequest('gh-bin', 933, 'justsearch-app/justsearch', {
    nodeBin: 'node-bin', previewScript: 'preview-script', reviewRecordScript: 'review-script',
    run: (command, args) => {
      calls.push({ command, args });
      return args[0] === 'pr' && args[1] === 'view' ? snapshotResult() : { status: 0 };
    }, writeError: () => {},
  });
  assert.equal(code, 0);
  assert.deepEqual(calls.map((c) => c.args[1]), ['view', '--pr', 'check', 'view', 'merge']);
  assert.deepEqual(calls.at(-1).args, ['pr', 'merge', '933', '--squash', '--auto', '--match-head-commit', snapshot.headRefOid, '--subject', snapshot.title, '--body', snapshot.body, '--repo', 'justsearch-app/justsearch']);
});
for (const update of [{ headRefOid: 'b'.repeat(40) }, { title: 'edited' }, { body: 'edited' }, { isDraft: true }, { mergeable: 'CONFLICTING' }, { mergeable: 'UNKNOWN' }]) {
  run(`enqueue refuses changed snapshot ${JSON.stringify(update)}`, () => {
    let reads = 0; const calls = [];
    const code = enqueuePullRequest('gh', 1, null, {
      run: (_command, args) => {
        calls.push(args);
        return args[1] === 'view' ? snapshotResult(reads++ ? { ...snapshot, ...update } : snapshot) : { status: 0 };
      }, writeError: () => {},
    });
    assert.equal(code, 2);
    assert.ok(!calls.some((args) => args[1] === 'merge'));
  });
}
for (const update of [{ isDraft: true }, { mergeable: 'CONFLICTING' }]) run('enqueue refuses draft/conflict before validation', () => {
  const calls = [];
  assert.equal(enqueuePullRequest('gh', 1, null, { run: (_command, args) => { calls.push(args); return snapshotResult({ ...snapshot, ...update }); }, writeError: () => {} }), 2);
  assert.equal(calls.length, 1);
});
await runAsync('run-wait-sha follows the newer main run that contains a superseded commit', async () => {
  const messages = [];
  const code = await runWaitSha('gh', 'abc', 1, {}, {
    loadJson: (_bin, args) => args[0] === 'api'
      ? (assert.match(args[1], /abc\.\.\.def$/), { value: { status: 'ahead' } })
      : { value: [
        { databaseId: 1, headSha: 'abc', event: 'push', status: 'completed', conclusion: 'cancelled', createdAt: '2026-01-01' },
        { databaseId: 2, headSha: 'def', event: 'push', status: 'completed', conclusion: 'success', createdAt: '2026-01-02' },
      ] },
    emit: (_prefix, value) => { messages.push(value.message); return value.key; },
  });
  assert.equal(code, 0);
  assert.match(messages[0], /following covering run 2/);
});
for (const conclusion of ['success', 'failure']) {
  await runAsync(`run-wait-sha follows a covering run when the original is absent (${conclusion})`, async () => {
    const messages = [];
    const code = await runWaitSha('gh', 'abc', 1, {}, {
      loadJson: (_bin, args) => args[0] === 'api'
        ? { value: { status: args[1].endsWith('...def') ? 'ahead' : 'diverged' } }
        : { value: [
          { databaseId: 3, headSha: 'unrelated', event: 'push', status: 'completed', conclusion: 'success', createdAt: '2026-01-03' },
          { databaseId: 2, headSha: 'def', event: 'push', status: 'completed', conclusion, createdAt: '2026-01-02' },
        ] },
      emit: (_prefix, value) => { messages.push(value.message); return value.key; },
    });
    assert.equal(code, conclusion === 'success' ? 0 : 1);
    assert.match(messages[0], /following covering run 2/);
  });
}

for (const [label, results, expected] of [
  ['preview refusal', [{ status: 1 }], 1],
  ['preview spawn error', [{ error: new Error('missing') }], 2],
  ['preview signal', [{ status: null, signal: 'SIGTERM' }], 2],
  ['preview missing status', [{}], 2],
  ['review refusal', [{ status: 0 }, { status: 1 }], 1],
]) {
  run(`enqueue fails closed on ${label} without requesting a merge`, () => {
    const calls = [];
    const queue = [...results];
    const code = enqueuePullRequest('gh-bin', 933, null, {
      nodeBin: 'node-bin',
      previewScript: 'preview-script',
      reviewRecordScript: 'review-script',
      run: (command, args) => {
        calls.push({ command, args });
        return args[1] === 'view' ? snapshotResult() : queue.shift() ?? { status: 0 };
      },
      writeError: () => {},
    });
    assert.equal(code, expected);
    assert(!calls.some((call) => call.args[1] === 'merge'), JSON.stringify(calls));
  });
}

// --- Report ---
if (failures.length > 0) {
  console.error(`run-gh.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`run-gh.test: all ${passed} checks passed`);
