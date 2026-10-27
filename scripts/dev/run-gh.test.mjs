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
import * as runGh from './run-gh.mjs';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawnSync } from 'node:child_process';
import { buildSquashMessagePreview } from '../ci/preview-squash-message.mjs';
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
    loadJson: (_bin, args) => ({ value: args[0] === 'api' ? (args[1].includes('/protection/') ? { contexts: [], checks: [] } : []) : args[1] === 'checks' ? [] : {
      state: 'OPEN', headRefOid: 'a'.repeat(40), baseRefName: 'main', isDraft: false,
      mergeStateStatus: 'QUEUED', autoMergeRequest: {},
    } }),
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

const snapshot = { headRefOid: 'a'.repeat(40), title: 'Validated title', body: 'Body\r\nwith lines', isDraft: false, mergeable: 'MERGEABLE' };
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
  const preview = buildSquashMessagePreview({
    repo: { squash_merge_commit_title: 'PR_TITLE', squash_merge_commit_message: 'PR_BODY' },
    pr: { ...snapshot, number: 933 },
  });
  assert.deepEqual(preview.errors, []);
  assert.equal(preview.proposedCommit.title, 'Validated title (#933)');
  assert.deepEqual(calls.at(-1).args, ['pr', 'merge', '933', '--squash', '--auto', '--match-head-commit', snapshot.headRefOid, '--subject', preview.proposedCommit.title, '--body', preview.proposedCommit.body, '--repo', 'justsearch-app/justsearch']);
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


// --- merge-wait outcome coverage (all GitHub responses are fakes) ---
const openMergePr = {
  state: 'OPEN', mergedAt: null, mergeCommit: null, headRefOid: 'a'.repeat(40),
  baseRefName: 'main',
  isDraft: false, mergeStateStatus: 'BLOCKED', autoMergeRequest: { enabledAt: '2026-10-27' },
};
const requiredRow = (state = 'SUCCESS', bucket = 'pass', name = 'required') => ({
  name, state, bucket, link: `https://example.test/checks/${name}`,
});
const requiredRules = (...contexts) => [{
  type: 'required_status_checks', parameters: { required_status_checks: contexts.map((context) => ({ context })) },
}];
async function observeMerge(polls, {
  timeoutSec = 1, step = 15_000, repo = null, head = null,
  rules = requiredRules('required'), rulesError = null,
  classic = { contexts: [], checks: [] }, classicError = null,
} = {}) {
  let clock = 0; let poll = 0; let views = 0;
  const calls = []; const pauses = []; let stderr = '';
  const originalWrite = process.stderr.write;
  process.stderr.write = (message) => { stderr += message; return true; };
  try {
    const code = await mergeWait('fake-gh', 7, timeoutSec, {
      repo, head,
      loadJson: (bin, args) => {
        assert.equal(bin, 'fake-gh'); calls.push(args);
        if (args[0] === 'api') {
          const protection = args[1].includes('/protection/');
          const stderr = protection ? classicError : rulesError;
          return stderr ? { error: new Error(stderr), stderr } : { value: protection ? classic : rules };
        }
        const current = polls[Math.min(poll, polls.length - 1)];
        if (args[1] === 'checks') return current.checkError
          ? { error: new Error(current.checkError) } : { value: current.checks ?? [requiredRow('PENDING', 'pending')] };
        assert.equal(args[1], 'view');
        if (current.viewError || (views % 2 && current.afterError)) return { error: new Error(current.viewError || current.afterError) };
        if (Object.hasOwn(current, 'invalidSnapshot')) return { value: current.invalidSnapshot };
        return { value: { ...openMergePr, ...(views++ % 2 ? current.after ?? current.before : current.before) } };
      },
      now: () => clock,
      pause: async (ms) => { pauses.push(ms); clock += step ?? ms; poll += 1; views = 0; },
    });
    return { code, calls, stderr, poll, pauses };
  } finally {
    process.stderr.write = originalWrite;
  }
}
for (const position of ['before', 'after']) await runAsync(`M1 merged in ${position} snapshot wins over failures and auto-merge off`, async () => {
  const merged = { state: 'MERGED', mergedAt: '2026-10-27', mergeCommit: { oid: 'landed' }, autoMergeRequest: null };
  const result = await observeMerge([{ before: { autoMergeRequest: null }, [position]: merged, checks: [requiredRow('FAILURE', 'fail')] }]);
  assert.equal(result.code, 0); assert.match(result.stderr, /merged at landed/);
});
await runAsync('M1 second snapshot merge wins even over a checks query error', async () => {
  const result = await observeMerge([{ after: { state: 'MERGED', mergeCommit: { oid: 'landed' } }, checkError: 'network' }]);
  assert.equal(result.code, 0);
});
for (const failure of [{ rulesError: 'gh: API rate limit exceeded (HTTP 403)' }, { classicError: 'gh: Server Error (HTTP 502)' }]) await runAsync('M1 merged wins before required-set reads can fail', async () => {
  const result = await observeMerge([{ before: { state: 'MERGED', mergeCommit: { oid: 'landed' } } }], failure);
  assert.equal(result.code, 0); assert.match(result.stderr, /merged at landed/);
  assert.equal(result.calls.some((args) => args[0] === 'api'), false);
});
await runAsync('M2 stable closed PR fails without merging', async () => {
  const result = await observeMerge([{ before: { state: 'CLOSED', autoMergeRequest: null } }]);
  assert.equal(result.code, 1); assert.match(result.stderr, /closed without merging/);
});
for (const [state, bucket] of [
  ['FAILURE', 'fail'], ['CANCELLED', 'cancel'], ['STARTUP_FAILURE', 'pending'],
  ['ERROR', 'pending'], ['FAILURE', 'pending'], ['TIMED_OUT', 'pending'], ['ACTION_REQUIRED', 'pending'],
]) await runAsync(`M3 required ${state}/${bucket} fails immediately alongside pending rows`, async () => {
  const result = await observeMerge([{ checks: [requiredRow(state, bucket, 'broken'), requiredRow('PENDING', 'pending', 'waiting'), requiredRow('ERROR', 'fail', 'legacy')] }]);
  assert.equal(result.code, 1); assert.equal(result.poll, 0);
  assert.match(result.stderr, /broken.*https:\/\/example.test\/checks\/broken/);
  assert.match(result.stderr, /legacy.*https:\/\/example.test\/checks\/legacy/);
});
await runAsync('M4 auto-merge off requests enqueue after a fix', async () => {
  const result = await observeMerge([{ before: { autoMergeRequest: null } }]);
  assert.equal(result.code, 1); assert.match(result.stderr, /auto-merge is off.*enqueue again after a fix/);
});
await runAsync('M5 stable new head fails and requests enqueue again', async () => {
  const result = await observeMerge([{}, { before: { headRefOid: 'b'.repeat(40), autoMergeRequest: null } }], { timeoutSec: 60 });
  assert.equal(result.code, 1); assert.match(result.stderr, /new head.*enqueue again/);
});
for (const [update, reason] of [[{ isDraft: true }, /draft/], [{ mergeStateStatus: 'DIRTY' }, /conflicts/]]) {
  await runAsync(`M6 terminal PR condition ${JSON.stringify(update)}`, async () => {
    const result = await observeMerge([{ before: update }]);
    assert.equal(result.code, 1); assert.match(result.stderr, reason);
  });
}
for (const mergeStateStatus of ['BLOCKED', 'UNKNOWN', 'BEHIND', 'UNSTABLE']) {
  await runAsync(`M7 ${mergeStateStatus} alone stays pending`, async () => {
    const result = await observeMerge([{ before: { mergeStateStatus } }]);
    assert.equal(result.code, 3);
  });
  await runAsync(`M7 passing checks stall after ten minutes (${mergeStateStatus})`, async () => {
    const result = await observeMerge([{ before: { mergeStateStatus }, checks: [requiredRow(), requiredRow('SKIPPED', 'skipping'), requiredRow('NEUTRAL', 'pass')] }], { timeoutSec: 900, step: 60_000 });
    assert.equal(result.code, 1); assert.equal(result.poll, 10);
    assert.match(result.stderr, new RegExp(`stall.*${mergeStateStatus}`));
  });
}
await runAsync('M7 pending rerun resets the continuous passing timer', async () => {
  const result = await observeMerge([
    { checks: [requiredRow()] }, { checks: [requiredRow()] }, {}, { checks: [requiredRow()] },
  ], { timeoutSec: 1500, step: 300_000 });
  assert.equal(result.code, 1); assert.equal(result.poll, 5);
});
await runAsync('M8 only required checks are queried, excluding advisory failures', async () => {
  const result = await observeMerge([{ checks: [requiredRow()] }]);
  assert.equal(result.code, 3);
  assert.ok(result.calls.some((args) => args[1] === 'checks'));
  for (const args of result.calls.filter((args) => args[1] === 'checks')) {
    assert.deepEqual(args, ['pr', 'checks', '7', '--required', '--json', 'name,state,bucket,link']);
  }
});
await runAsync('M9 current pending rerun and STALE are pending', async () => {
  const result = await observeMerge([{ checks: [requiredRow('PENDING', 'pending'), requiredRow('STALE', 'pending')] }]);
  assert.equal(result.code, 3); assert.match(result.stderr, /2 reported, 0 passed, 2 pending/);
});
await runAsync('M10 timeout reports counts and never mutates auto-merge', async () => {
  const result = await observeMerge([{ checks: [requiredRow(), requiredRow('QUEUED', 'pending')] }]);
  assert.equal(result.code, 3); assert.match(result.stderr, /TIMEOUT.*2 reported, 1 passed, 1 pending/);
  assert.ok(result.calls.every((args) => args[0] === 'api' || ['view', 'checks'].includes(args[1])));
});
await runAsync('M10 zero checks never passes or starts the stall timer', async () => {
  const result = await observeMerge([{ checks: [] }], { timeoutSec: 900, step: 300_000 });
  assert.equal(result.code, 3); assert.match(result.stderr, /none reported yet/); assert.doesNotMatch(result.stderr, /0 pending/);
});
for (const field of ['checkError', 'viewError']) await runAsync(`M11 ${field} returns unexpected error`, async () => {
  const result = await observeMerge([{ [field]: 'auth/network/rate limit' }]);
  assert.equal(result.code, 2); assert.match(result.stderr, /auth\/network\/rate limit/);
});
for (const [label, result] of [
  ['empty exit 1', { status: 1, stdout: '', stderr: '' }],
  ['auth', { status: 1, stderr: 'authenticate first' }],
  ['non JSON', { status: 0, stdout: 'not json' }],
  ['malformed rows', { status: 0, stdout: '{}' }],
  ['incomplete row', { status: 0, stdout: '[{}]' }],
  ['spawn', { error: new Error('ENOENT') }],
  ['signal', { status: null, signal: 'SIGTERM', stdout: '[]' }],
]) run(`M11 required-check decoding rejects ${label}`, () => {
  assert.ok(runGh.parseRequiredChecksResult(result).error);
});
for (const status of [0, 1, 8, 9]) run(`M11 JSON buckets decide independently of exit ${status}`, () => {
  const row = requiredRow('PENDING', 'pending');
  assert.deepEqual(runGh.parseRequiredChecksResult({ status, stdout: JSON.stringify([row]) }), { value: [row] });
});
run('M11 explicit unregistered message is pending, preserving checks-wait heuristic', () => {
  assert.deepEqual(runGh.parseRequiredChecksResult({ status: 1, stderr: "no required checks reported on the 'branch' branch" }), { value: [] });
});
await runAsync('M12 identical polls emit once and changed check counts emit a transition', async () => {
  const result = await observeMerge([{}, {}, { checks: [requiredRow(), requiredRow('PENDING', 'pending')] }], { timeoutSec: 45 });
  assert.equal(result.code, 3);
  assert.equal(result.stderr.split('\n').filter((line) => line.startsWith('run-gh merge-wait PR')).length, 2);
});
for (const change of [{ headRefOid: 'b'.repeat(40) }, { state: 'CLOSED' }]) {
  await runAsync(`unstable snapshots stay pending rather than trusting stale failure (${JSON.stringify(change)})`, async () => {
    const result = await observeMerge([{ after: change, checks: [requiredRow('FAILURE', 'fail')] }]);
    assert.equal(result.code, 3); assert.match(result.stderr, /changed during poll/);
  });
}
await runAsync('a new head during a poll is rejected on the next stable poll', async () => {
  const change = { headRefOid: 'b'.repeat(40), autoMergeRequest: null };
  const result = await observeMerge([{ after: change }, { before: change }], { timeoutSec: 60 });
  assert.equal(result.code, 1); assert.equal(result.poll, 1); assert.match(result.stderr, /new head/);
});
await runAsync('R1 every PR view and required-check query targets the supplied repo', async () => {
  const result = await observeMerge([{}], { repo: 'owner/repo' });
  assert.equal(result.code, 3);
  const prCalls = result.calls.filter((args) => args[0] === 'pr');
  assert.equal(prCalls.length, 6);
  for (const args of prCalls) assert.deepEqual(args.slice(-2), ['--repo', 'owner/repo']);
  assert.deepEqual(prCalls.slice(0, 3).map((args) => args[1]), ['view', 'checks', 'view']);
  assert.match(result.calls[0][4], /headRefOid/); assert.match(result.calls[0][4], /autoMergeRequest/);
});
for (const repo of [null, 'owner/repo']) run(`E1 enqueue success prints the background observer next step (${repo})`, () => {
  let stderr = '';
  const code = enqueuePullRequest('fake-gh', 7, repo, {
    run: (_bin, args) => args[1] === 'view' ? snapshotResult() : { status: 0 },
    writeError: (message) => { stderr += message; },
  });
  assert.equal(code, 0);
  assert.equal(stderr.trim().split('\n').length, 1);
  assert.ok(stderr.includes(`node scripts/dev/run-gh.mjs merge-wait 7${repo ? ` --repo ${repo}` : ''} --head ${snapshot.headRefOid} --timeout-sec 5400`));
  assert.match(stderr, /background/);
});
run('E1 enqueue failure emits no observer next step and preserves exit status', () => {
  let stderr = '';
  const code = enqueuePullRequest('fake-gh', 7, null, {
    run: (_bin, args) => args[1] === 'view' ? snapshotResult() : { status: 1 },
    writeError: (message) => { stderr += message; },
  });
  assert.equal(code, 1); assert.doesNotMatch(stderr, /merge-wait/);
});


for (const invalidSnapshot of [null, [], {}, { ...openMergePr, headRefOid: null }]) {
  await runAsync(`M11 malformed snapshot returns error (${JSON.stringify(invalidSnapshot)})`, async () => {
    const result = await observeMerge([{ invalidSnapshot }]);
    assert.equal(result.code, 2); assert.match(result.stderr, /incomplete PR snapshot/);
  });
}
await runAsync('M7 unstable snapshots reset the passing timer', async () => {
  const result = await observeMerge([
    { checks: [requiredRow()] },
    { checks: [requiredRow()], after: { state: 'CLOSED' } },
    { checks: [requiredRow()] },
  ], { timeoutSec: 1500, step: 300_000 });
  assert.equal(result.code, 1); assert.equal(result.poll, 4);
});
run('required check classifier counts accepted conclusions and leaves unfamiliar states pending', () => {
  assert.deepEqual(runGh.classifyRequiredChecks([
    requiredRow('SKIPPED', 'skipping'), requiredRow('NEUTRAL', 'pass'), requiredRow(),
    requiredRow('STALE', 'pass'), requiredRow('REQUESTED', 'pending'), requiredRow('ERROR', 'fail'),
  ]), { reported: 6, passed: 3, pending: 2, failures: [requiredRow('ERROR', 'fail')] });
});


await runAsync('M1 first-snapshot merge wins over subsequent checks and snapshot errors', async () => {
  const result = await observeMerge([{
    before: { state: 'MERGED', mergeCommit: { oid: 'landed' } },
    checkError: 'network', afterError: 'network',
  }]);
  assert.equal(result.code, 0); assert.match(result.stderr, /merged at landed/);
  assert.deepEqual(result.calls.filter((args) => args[0] === 'pr').map((args) => args[1]), ['view']);
});
await runAsync('M11 second PR snapshot error exits 2', async () => {
  const result = await observeMerge([{ afterError: 'network' }]);
  assert.equal(result.code, 2); assert.match(result.stderr, /network/);
});

await runAsync('missing required context never starts a stall and is named on timeout', async () => {
  const result = await observeMerge([{ checks: [requiredRow()] }], {
    timeoutSec: 900, step: 60_000, rules: requiredRules('required', 'late context'),
  });
  assert.equal(result.code, 3);
  assert.match(result.stderr, /TIMEOUT.*not reported yet: late context/);
  assert.doesNotMatch(result.stderr, /stalled/);
});
await runAsync('stall starts only after the last required context reports a pass', async () => {
  const result = await observeMerge([
    { checks: [requiredRow()] },
    { checks: [requiredRow()] },
    { checks: [requiredRow(), requiredRow('SKIPPED', 'skipping', 'late')] },
  ], { timeoutSec: 1800, step: 300_000, rules: requiredRules('required', 'late') });
  assert.equal(result.code, 1); assert.equal(result.poll, 4);
  assert.match(result.stderr, /stalled/);
});
await runAsync('a missing context resets an already running stall timer', async () => {
  const result = await observeMerge([
    { checks: [requiredRow(), requiredRow('NEUTRAL', 'pass', 'late')] },
    { checks: [requiredRow()] },
    { checks: [requiredRow(), requiredRow('NEUTRAL', 'pass', 'late')] },
  ], { timeoutSec: 1800, step: 300_000, rules: requiredRules('required', 'late') });
  assert.equal(result.code, 1); assert.equal(result.poll, 4);
});
await runAsync('required contexts are the union across rules, including duplicate contexts', async () => {
  const result = await observeMerge([{ checks: [requiredRow()] }], {
    timeoutSec: 900, step: 60_000,
    rules: [...requiredRules('required'), { type: 'pull_request' }, ...requiredRules('late', 'required')],
  });
  assert.equal(result.code, 3);
  assert.match(result.stderr, /not reported yet: late/);
});
for (const [label, options] of [
  ['no rules', { rules: [] }],
  ['no required contexts', { rules: [{ type: 'pull_request' }] }],
  ['empty required set', { rules: requiredRules() }],
]) await runAsync(`unavailable required contexts disable only stall detection (${label})`, async () => {
  const result = await observeMerge([{ checks: [requiredRow()] }], { timeoutSec: 900, step: 60_000, ...options });
  assert.equal(result.code, 3);
  assert.equal(result.stderr.split('\n').filter((line) => /stall detection disabled/.test(line)).length, 1);
  assert.match(result.stderr, /TIMEOUT/);
  assert.equal(result.calls.filter((args) => args[0] === 'api').length, 2);
});
for (const repo of [null, 'owner/repo']) await runAsync(`rules are read once for the initial base (${repo})`, async () => {
  const result = await observeMerge([{ before: { baseRefName: 'release/v1' } }], { repo });
  assert.deepEqual(result.calls.filter((args) => args[0] === 'api'), [
    ['api', `repos/${repo ?? '{owner}/{repo}'}/branches/release%2Fv1/protection/required_status_checks`],
    ['api', `repos/${repo ?? '{owner}/{repo}'}/rules/branches/release%2Fv1`, '--paginate'],
  ]);
  for (const args of result.calls.filter((args) => args[1] === 'view')) assert.match(args[4], /baseRefName/);
});
await runAsync('enqueue head mismatch is rejected on the first stable poll', async () => {
  const result = await observeMerge([{ before: { headRefOid: 'b'.repeat(40) } }], { head: 'a'.repeat(40) });
  assert.equal(result.code, 1); assert.equal(result.poll, 0); assert.match(result.stderr, /new head.*enqueue again/);
});
await runAsync('matching enqueue head continues observing', async () => {
  const result = await observeMerge([{}], { head: openMergePr.headRefOid });
  assert.equal(result.code, 3); assert.doesNotMatch(result.stderr, /new head/);
});
await runAsync('merge-wait pauses 60 seconds and keeps view/checks/view per poll', async () => {
  const result = await observeMerge([{}], { timeoutSec: 120, step: null });
  assert.equal(result.code, 3); assert.deepEqual(result.pauses, [60_000, 60_000]);
  assert.deepEqual(result.calls.filter((args) => args[0] === 'pr').map((args) => args[1]),
    ['view', 'checks', 'view', 'view', 'checks', 'view', 'view', 'checks', 'view']);
});
await runAsync('run-wait-sha retains its 15 second poll interval', async () => {
  const pauses = []; let clock = 0;
  const code = await runWaitSha('fake-gh', 'abc', 15, {}, {
    loadJson: () => ({ value: [] }), now: () => clock,
    pause: async (ms) => { pauses.push(ms); clock += ms; }, emit: (_prefix, value) => value.key,
  });
  assert.equal(code, 3); assert.deepEqual(pauses, [15_000]);
});

// Exercise the CLI parser with every child gh call replaced in the subprocess.
const cliGhStub = (apiResults) => `
  import childProcess from 'node:child_process';
  import { syncBuiltinESMExports } from 'node:module';
  let clock = 0;
  Date.now = () => clock++ * 1000;
  const apiResults = ${JSON.stringify(apiResults)};
  childProcess.spawnSync = (_bin, args) => {
    if (args[0] === 'api' && apiResults) return apiResults[args[1].includes('/protection/') ? 'classic' : 'rules'];
    return { status: 0, stdout: JSON.stringify(
    args[0] === 'api' ? (args[1].includes('/protection/') ? { contexts: [], checks: [] } : ${JSON.stringify(requiredRules('required'))}) :
    args[1] === 'checks' ? [] : ${JSON.stringify({ ...openMergePr, headRefOid: 'b'.repeat(40) })}
  ) };
  };
  syncBuiltinESMExports();
`;
function mergeWaitCli(args, apiResults = null) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'run-gh-cli-'));
  const stderrPath = path.join(directory, 'stderr');
  const stderrFd = fs.openSync(stderrPath, 'w');
  try {
    const result = spawnSync(process.execPath, [
      '--import', `data:text/javascript,${encodeURIComponent(cliGhStub(apiResults))}`,
      path.join(import.meta.dirname, 'run-gh.mjs'), 'merge-wait', '7', '--timeout-sec', '0.000001', ...args,
    ], { stdio: ['ignore', 'ignore', stderrFd], timeout: 2000 });
    assert.ifError(result.error);
    return { status: result.status, stderr: fs.readFileSync(stderrPath, 'utf8') };
  } finally {
    fs.closeSync(stderrFd);
    fs.rmSync(directory, { recursive: true, force: true });
  }
}
for (const args of [['--head'], ['--head', '--repo', 'owner/repo'], ['--head', 'xyz'],
  ['--head', 'a'.repeat(39)], ['--head', 'a'.repeat(41)], ['--head', 'g'.repeat(40)],
  ['--head', 'a'.repeat(40), '--head', 'b'.repeat(40)]]) {
  run(`CLI rejects malformed head ${JSON.stringify(args)}`, () => {
    const result = mergeWaitCli(args);
    assert.equal(result.status, 2); assert.match(result.stderr, /--head.*40.*hex/i);
  });
}
for (const head of ['a'.repeat(40), 'ABCDEF'.repeat(6) + '1234']) run(`CLI watches supplied hex head ${head}`, () => {
  const result = mergeWaitCli(['--head', head]);
  assert.equal(result.status, 1); assert.match(result.stderr, /new head/);
});
run('CLI normalizes uppercase hex for the matching watched head', () => {
  const result = mergeWaitCli(['--head', 'B'.repeat(40)]);
  assert.equal(result.status, 3); assert.doesNotMatch(result.stderr, /new head/);
});

// Classic protection and rulesets both contribute to the complete required set.
for (const classic of [
  { contexts: ['required'], checks: [] },
  { contexts: [], checks: [{ context: 'required', app_id: 42 }] },
]) await runAsync('classic protection alone enables the ten-minute stall timer', async () => {
  const result = await observeMerge([{ checks: [requiredRow()] }], {
    classic, rules: [], timeoutSec: 900, step: 60_000,
  });
  assert.equal(result.code, 1); assert.equal(result.poll, 10);
  assert.match(result.stderr, /stalled/); assert.doesNotMatch(result.stderr, /disabled/);
});
await runAsync('union includes contexts, checks and rulesets and deduplicates missing names', async () => {
  const result = await observeMerge([{ checks: [requiredRow()] }], {
    classic: { contexts: ['required', 'classic'], checks: [{ context: 'app' }, { context: 'classic' }] },
    rules: requiredRules('ruleset', 'classic', 'required'), timeoutSec: 900, step: 60_000,
  });
  assert.equal(result.code, 3);
  assert.match(result.stderr, /not reported yet: classic, app, ruleset\n/);
  assert.doesNotMatch(result.stderr, /stalled|disabled/);
});
await runAsync('HTTP 404 Branch not protected permits ruleset stall detection', async () => {
  const result = await observeMerge([{ checks: [requiredRow()] }], {
    classicError: 'gh: Branch not protected (HTTP 404)', timeoutSec: 900, step: 60_000,
  });
  assert.equal(result.code, 1); assert.match(result.stderr, /stalled/);
  assert.doesNotMatch(result.stderr, /disabled/);
});
for (const classicError of [
  'gh: Resource not accessible by integration (HTTP 403)',
  'gh: Not Found (HTTP 404)',
]) await runAsync(`unknown classic protection disables stalls once: ${classicError}`, async () => {
  const result = await observeMerge([{ checks: [requiredRow()] }], {
    classicError, timeoutSec: 900, step: 60_000,
  });
  assert.equal(result.code, 3); assert.match(result.stderr, /TIMEOUT/);
  assert.doesNotMatch(result.stderr, /stalled/);
  assert.equal(result.stderr.split('stall detection disabled').length - 1, 1);
  assert.equal(result.calls.filter((args) => args[0] === 'api').length, 2);
});
for (const options of [
  ...['network unavailable', 'gh: Bad credentials (HTTP 401)', 'gh: API rate limit exceeded (HTTP 403)',
    'gh: You have exceeded a secondary rate limit (HTTP 403)', 'gh: Too Many Requests (HTTP 429)',
    'gh: Internal Server Error (HTTP 500)', 'HTTP 4040'].flatMap((message) => [
      { classicError: message }, { rulesError: message },
    ]),
  { rulesError: 'gh: Not Found (HTTP 404)' },
  { rulesError: 'gh: Resource not accessible by integration (HTTP 403)' },
  { classicError: 'gh: Not Found (HTTP 404)', rulesError: 'network unavailable' },
  ...[null, {}, [], { contexts: [] }, { checks: [] }, { contexts: [''], checks: [] },
    { contexts: [3], checks: [] }, { contexts: [], checks: [{}] },
    { contexts: [], checks: [{ context: '' }] }].map((classic) => ({ classic })),
  ...[{}, null, [null], [{}], [{ type: 'required_status_checks', parameters: {} }],
    requiredRules('')].map((rules) => ({ rules })),
]) await runAsync(`required-set read fails closed: ${JSON.stringify(options)}`, async () => {
  const result = await observeMerge([{ checks: [requiredRow()] }], options);
  assert.equal(result.code, 2); assert.doesNotMatch(result.stderr, /disabled|TIMEOUT/);
});

// Go through capturedJson and the CLI; every child gh call is replaced before import.
const apiSuccess = (value) => ({ status: 0, stdout: JSON.stringify(value), stderr: '' });
const emptyClassic = apiSuccess({ contexts: [], checks: [] });
const trickyContext = 'quoted "context" with ][ and \\ slash';
for (const separator of ['', '\n']) run(`CLI decodes concatenated ruleset pages (${JSON.stringify(separator)})`, () => {
  const result = mergeWaitCli([], {
    classic: apiSuccess({ contexts: ['classic'], checks: [{ context: 'app', app_id: null }] }),
    rules: { status: 0, stdout: [requiredRules('first'), [], requiredRules(trickyContext)].map(JSON.stringify).join(separator), stderr: '' },
  });
  assert.equal(result.status, 3);
  assert.ok(result.stderr.includes(`not reported yet: classic, app, first, ${trickyContext}`));
  assert.doesNotMatch(result.stderr, /disabled/);
});
for (const stdout of ['', '[', '[]junk', '[]\n{}', '[]\n[', '[{}]']) run(`CLI rejects invalid paginated rules JSON ${JSON.stringify(stdout)}`, () => {
  const result = mergeWaitCli([], { classic: emptyClassic, rules: { status: 0, stdout, stderr: '' } });
  assert.equal(result.status, 2); assert.doesNotMatch(result.stderr, /disabled/);
});
run('CLI rejects malformed classic JSON', () => {
  const result = mergeWaitCli([], { classic: { status: 0, stdout: '{', stderr: '' }, rules: apiSuccess([]) });
  assert.equal(result.status, 2); assert.match(result.stderr, /invalid gh JSON/);
});
for (const [stderr, expected] of [
  ['gh: Branch not protected (HTTP 404)', /no required contexts/],
  ['gh: Not Found (HTTP 404)', /classic.*unknown/i],
  ['gh: Resource not accessible by integration (HTTP 403)', /classic.*unknown/i],
]) run(`CLI recognizes gh stderr format: ${stderr}`, () => {
  const result = mergeWaitCli([], { classic: { status: 1, stdout: '{}', stderr }, rules: apiSuccess([]) });
  assert.equal(result.status, 3); assert.match(result.stderr, expected);
  assert.equal(result.stderr.split('stall detection disabled').length - 1, 1);
});
run('CLI does not treat HTTP text in stdout as a classic permission error', () => {
  const result = mergeWaitCli([], {
    classic: { status: 1, stdout: 'gh: Not Found (HTTP 404)', stderr: 'connection failed' }, rules: apiSuccess([]),
  });
  assert.equal(result.status, 2); assert.match(result.stderr, /connection failed/);
});

// --- Report ---
if (failures.length > 0) {
  console.error(`run-gh.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`run-gh.test: all ${passed} checks passed`);
