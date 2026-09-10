/**
 * Tempdoc 952 P4 — regressions for the worktree/branch lifecycle register.
 *
 * Real Git throughout: every fixture is a repository created with `git init` under a fresh OS
 * temp directory, and every worktree, lock and branch-config write happens inside one of those
 * disposable trees. Nothing here touches the repository this file lives in.
 *
 * Run with: node scripts/agent-analytics/952-worktree-register.test.mjs
 */
import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SOURCE_ROOT = path.resolve(HERE, '..', '..');
const require = createRequire(import.meta.url);
const register = require(path.join(SOURCE_ROOT, 'scripts', 'dev', 'lib', 'worktree-register.cjs'));

const {
  loadPolicy,
  markerFields,
  readMarkers,
  writeMarkers,
  clearMarkers,
  listBranchesWithMarkers,
  parseHold,
  formatHold,
  parseLockReason,
  formatLockReason,
  listWorktrees,
  isUnderSanctionedRoot,
  FINALIZATION_PHASES,
  finalizationDir,
  validateFinalizationRecord,
  buildFinalizationRecord,
  readFinalizations,
  writeFinalization,
  advanceFinalization,
  removeFinalization,
  claimFinalization,
  STATES,
  deriveState,
  deriveOwnerAlive,
  census,
} = register;

let passed = 0;
const failures = [];
async function check(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (err) {
    failures.push(`${label}: ${err.stack || err}`);
  }
}

function run(command, args, { cwd } = {}) {
  const result = spawnSync(command, args, { cwd, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')} exited ${result.status}:\n${result.stdout || ''}${result.stderr || ''}`);
  }
  return result.stdout || '';
}

function git(repo, ...args) {
  return run('git', args, { cwd: repo });
}

const roots = [];
async function makeRepo(label) {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), `wt-register-952-${label}-`));
  roots.push(root);
  const repo = path.join(root, 'main');
  await fsp.mkdir(repo, { recursive: true });
  git(repo, 'init', '-q', '-b', 'main');
  git(repo, 'config', 'user.email', 'fixture@example.invalid');
  git(repo, 'config', 'user.name', 'Fixture');
  await fsp.writeFile(path.join(repo, '.gitignore'), '.claude/\ntmp/\n', 'utf8');
  await fsp.writeFile(path.join(repo, 'tracked.txt'), 'base\n', 'utf8');
  git(repo, 'add', '.gitignore', 'tracked.txt');
  git(repo, 'commit', '-qm', 'fixture');
  return { root, repo };
}

const POLICY = loadPolicy();
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const NOW = Date.parse('2026-09-10T12:00:00.000Z');
const THRESHOLDS = POLICY.thresholds;

try {
  /* ── 1. Markers round-trip ──────────────────────────────────────────────────────────────── */

  await check('markers round-trip through real git config, including the hold triple', async () => {
    const { repo } = await makeRepo('markers');
    assert.deepEqual(
      markerFields(POLICY),
      ['resource', 'session', 'harness', 'created', 'fork', 'anchor', 'released', 'hold'],
      'marker fields are the governance file branchConfigKeys with the justsearch- prefix stripped',
    );
    assert.equal(readMarkers({ repoRoot: repo, branch: 'main' }), null, 'an unregistered branch reads as null, not as an empty registration');

    const written = writeMarkers({
      repoRoot: repo,
      branch: 'main',
      markers: {
        resource: '952-worktree-lifecycle',
        session: 'sess-abc',
        harness: 'claude',
        created: '2026-09-01T08:00:00.000Z',
        fork: 'a'.repeat(40),
        anchor: 'b'.repeat(40),
      },
    });
    assert.equal(written.length, 6);

    const markers = readMarkers({ repoRoot: repo, branch: 'main' });
    assert.equal(markers.resource, '952-worktree-lifecycle');
    assert.equal(markers.session, 'sess-abc');
    assert.equal(markers.harness, 'claude');
    assert.equal(markers.fork, 'a'.repeat(40));
    assert.equal(markers.released, null, 'every declared field is present, unset ones as null');
    assert.equal(markers.hold, null);

    // A partial update must not erase fork/anchor — they are unrecoverable once lost.
    writeMarkers({ repoRoot: repo, branch: 'main', markers: { released: '2026-09-09T20:00:00.000Z' } });
    const afterRelease = readMarkers({ repoRoot: repo, branch: 'main' });
    assert.equal(afterRelease.released, '2026-09-09T20:00:00.000Z');
    assert.equal(afterRelease.fork, 'a'.repeat(40), 'a partial write leaves the other markers intact');

    // Hold encodes as reason|owner|review-by and decodes losslessly, including a reason with a pipe.
    writeMarkers({
      repoRoot: repo,
      branch: 'main',
      markers: { hold: { reason: 'evidence | still needed', owner: 'elias', reviewBy: '2026-09-20' } },
    });
    const raw = git(repo, 'config', '--get', 'branch.main.justsearch-hold').trim();
    assert.equal(raw, 'evidence | still needed|elias|2026-09-20');
    const held = readMarkers({ repoRoot: repo, branch: 'main' }).hold;
    assert.equal(held.reason, 'evidence | still needed');
    assert.equal(held.owner, 'elias');
    assert.equal(held.reviewBy, '2026-09-20');
    assert.equal(formatHold(held), raw, 'decode then encode is the identity');
    assert.equal(parseHold('bare reason').malformed, true, 'a value that is not a triple is flagged, not guessed at');
    assert.throws(() => formatHold({ reason: 'r', owner: 'a|b', reviewBy: 'x' }), /must not contain/);
    assert.throws(() => writeMarkers({ repoRoot: repo, branch: 'main', markers: { nonsense: 'x' } }), /unknown marker/);

    // A second branch, so the census is not trivially a one-row map.
    git(repo, 'branch', 'feature/a.b');
    writeMarkers({ repoRoot: repo, branch: 'feature/a.b', markers: { resource: 'lane-a', session: 'sess-def' } });
    git(repo, 'branch', 'unregistered');
    git(repo, 'config', 'branch.unregistered.justsearch-session', 'sess-ghi');

    const all = listBranchesWithMarkers({ repoRoot: repo });
    assert.deepEqual([...all.keys()].sort(), ['feature/a.b', 'main'], 'a branch with no justsearch-resource is not a registration');
    assert.equal(all.get('feature/a.b').resource, 'lane-a');
    assert.equal(all.get('main').hold.owner, 'elias');

    const cleared = clearMarkers({ repoRoot: repo, branch: 'feature/a.b' });
    assert.deepEqual(cleared, ['branch.feature/a.b.justsearch-resource', 'branch.feature/a.b.justsearch-session'],
      'clearing an unset key is not an error, and only the keys that existed are reported');
    assert.equal(readMarkers({ repoRoot: repo, branch: 'feature/a.b' }), null);
  });

  /* ── 2. Lock reasons ────────────────────────────────────────────────────────────────────── */

  await check('parseLockReason accepts the three grammars and refuses everything else', async () => {
    const observed = parseLockReason('claude session 952-worktree-lifecycle (pid 1234)');
    assert.deepEqual(observed, { harness: 'claude', session: '952-worktree-lifecycle', pid: 1234, start: null });

    const ours = parseLockReason('codex session sess-abc (pid 4321, start 133712345678901234)');
    assert.deepEqual(ours, { harness: 'codex', session: 'sess-abc', pid: 4321, start: '133712345678901234' });

    for (const prefix of ['session', 'background', 'subagent']) {
      const documented = parseLockReason(`${prefix}-3f2a1b4c-9d8e-4f7a-8b6c-1d2e3f4a5b6c-elias`);
      assert.deepEqual(documented, {
        harness: 'claude',
        session: '3f2a1b4c-9d8e-4f7a-8b6c-1d2e3f4a5b6c',
        pid: null,
        start: null,
      }, `${prefix}- grammar carries no pid`);
    }

    for (const garbage of ['', '   ', 'locked by hand', 'claude session (pid)', 'claude session x (pid abc)', 'session-only', null, undefined, 42]) {
      assert.equal(parseLockReason(garbage), null, `${JSON.stringify(garbage)} matches no grammar`);
    }

    // Our grammar round-trips, and a session name with spaces and parentheses survives it.
    const reason = formatLockReason({ harness: 'claude', session: '952 lane (a)', pid: 77, start: '133712345678901234' });
    assert.equal(reason, 'claude session 952 lane (a) (pid 77, start 133712345678901234)');
    assert.deepEqual(parseLockReason(reason), { harness: 'claude', session: '952 lane (a)', pid: 77, start: '133712345678901234' });
    assert.equal(formatLockReason({ harness: 'claude', session: 's', pid: 5 }), 'claude session s (pid 5)',
      'an unknown start is omitted, never stamped with a placeholder');
    assert.throws(() => formatLockReason({ harness: 'claude', session: 's', pid: 0 }), /positive integer pid/);
  });

  /* ── 3. listWorktrees ───────────────────────────────────────────────────────────────────── */

  await check('listWorktrees parses main plus a linked, locked worktree losslessly', async () => {
    const { root, repo } = await makeRepo('list');
    const linked = path.join(root, 'lane a');
    git(repo, 'worktree', 'add', '-q', linked, '-b', 'worktree-lane-a');
    const reason = 'claude session 952 lane worktree (pid 4321)';
    git(repo, 'worktree', 'lock', '--reason', reason, linked);

    const entries = listWorktrees({ repoRoot: repo });
    assert.equal(entries.length, 2);
    assert.equal(entries[0].isMain, true, 'git lists the main worktree first');
    assert.equal(entries[0].branch, 'main');
    assert.equal(entries[0].locked, null);
    assert.match(entries[0].head, /^[0-9a-f]{40}$/);

    const [, lane] = entries;
    assert.equal(lane.isMain, false);
    assert.equal(lane.branchRef, 'refs/heads/worktree-lane-a');
    assert.equal(lane.branch, 'worktree-lane-a');
    assert.equal(lane.detached, false);
    assert.equal(lane.bare, false);
    assert.equal(lane.locked, reason, 'a lock reason with spaces and parentheses survives the -z parse verbatim');
    assert.equal(lane.prunable, null);
    assert.equal(path.resolve(lane.path), path.resolve(linked));
    assert.equal(parseLockReason(lane.locked).pid, 4321);

    // A detached worktree carries no branch, and a removed directory becomes prunable.
    const detached = path.join(root, 'detached');
    git(repo, 'worktree', 'add', '-q', '--detach', detached, 'HEAD');
    await fsp.rm(detached, { recursive: true, force: true });
    const after = listWorktrees({ repoRoot: repo });
    const gone = after.find((e) => path.resolve(e.path) === path.resolve(detached));
    assert.equal(gone.detached, true);
    assert.equal(gone.branch, null);
    assert.ok(gone.prunable, 'a missing directory is reported prunable rather than silently dropped');
  });

  /* ── 4. deriveState matrix ──────────────────────────────────────────────────────────────── */

  await check('deriveState reaches every state and holds both safety rules', async () => {
    const markers = {
      resource: 'lane-a', session: 'sess-abc', harness: 'claude', created: '2026-09-01T00:00:00.000Z',
      fork: null, anchor: null, released: null, hold: null,
    };
    const lock = 'claude session sess-abc (pid 4321)';
    const base = { markers, sanctioned: true, now: NOW, thresholds: THRESHOLDS };
    const fresh = { lastActivityAt: new Date(NOW - 2 * MINUTE).toISOString() };
    const stale = { lastActivityAt: new Date(NOW - 3 * HOUR).toISOString() };
    const ancient = { lastActivityAt: new Date(NOW - 72 * HOUR).toISOString() };
    const seen = new Set();
    const at = (args) => {
      const result = deriveState({ ...base, ...args });
      seen.add(result.state);
      assert.ok(Array.isArray(result.reasons) && result.reasons.length > 0, 'every verdict carries a reason');
      return result;
    };

    assert.equal(at({ lock, ownerAlive: true, sessionActivity: stale }).state, STATES.ACTIVE);
    assert.equal(at({ lock, ownerAlive: null, sessionActivity: fresh }).state, STATES.ACTIVE,
      'fresh ledger activity is positive evidence even when the process cannot be probed');
    assert.equal(at({ lock, ownerAlive: false, sessionActivity: stale }).state, STATES.SUSPECT);
    assert.equal(at({ lock, ownerAlive: false, sessionActivity: ancient }).state, STATES.ORPHANED);
    assert.equal(at({ lock: null, ownerAlive: null, sessionActivity: null }).state, STATES.RELEASED);
    assert.match(at({ lock: null }).reasons.join(' '), /lock released by harness/);
    assert.equal(at({ lock, markers: { ...markers, released: '2026-09-09T10:00:00.000Z' }, ownerAlive: true }).state, STATES.RELEASED,
      'an explicit release marker outranks a lingering lock');
    assert.equal(
      at({ lock, markers: { ...markers, hold: { reason: 'r', owner: 'o', reviewBy: '2026-10-01' } }, ownerAlive: true }).state,
      STATES.HELD,
      'a hold outranks liveness',
    );
    assert.equal(
      at({ lock, finalization: { phase: 'archived', by: { sessionId: 'sess-zzz' } }, ownerAlive: true }).state,
      STATES.FINALIZING,
      'a finalization in flight outranks every Git-visible signal',
    );

    // Safety rule 1: unknown liveness with no activity is SUSPECT, never ACTIVE and never ORPHANED.
    const unknown = at({ lock, ownerAlive: null, sessionActivity: null });
    assert.equal(unknown.state, STATES.SUSPECT);
    assert.match(unknown.reasons.join(' '), /unverifiable/);
    const undatable = at({ lock, ownerAlive: false, sessionActivity: null });
    assert.equal(undatable.state, STATES.SUSPECT, 'a verified-dead owner with no activity stamp cannot be aged into ORPHANED');

    // Safety rule 2: a lock this module cannot attribute is foreign — quarantined, never released.
    const foreign = at({ lock: 'held by the release engineer', ownerAlive: true, sessionActivity: fresh });
    assert.equal(foreign.state, STATES.QUARANTINED);
    assert.match(foreign.reasons.join(' '), /foreign lock/);
    const otherSession = at({ lock: 'claude session sess-other (pid 4321)', ownerAlive: true, sessionActivity: fresh });
    assert.equal(otherSession.state, STATES.QUARANTINED, 'a lock naming a different session than the markers is foreign too');

    // Safety rule 3: outside the sanctioned root, or unregistered, is protected.
    assert.equal(at({ lock, sanctioned: false, ownerAlive: false, sessionActivity: ancient }).state, STATES.UNMANAGED,
      'an unsanctioned root is protected even when every other signal says orphaned');
    assert.equal(at({ lock, markers: null, ownerAlive: false, sessionActivity: ancient }).state, STATES.UNMANAGED,
      'an unregistered worktree is protected even when every other signal says orphaned');

    assert.deepEqual([...seen].sort(), [...Object.values(STATES)].sort(), 'the matrix reaches every declared state');
  });

  await check('deriveOwnerAlive never upgrades absent evidence, and catches a recycled pid', async () => {
    const alive = (pid) => pid === 4321;
    assert.equal(deriveOwnerAlive({ lock: { pid: null, start: null }, isPidAlive: alive }).alive, null);
    assert.equal(deriveOwnerAlive({ lock: { pid: 9999, start: null }, isPidAlive: alive }).alive, false);
    assert.equal(deriveOwnerAlive({ lock: { pid: 4321, start: null }, isPidAlive: alive }).alive, true);
    assert.equal(
      deriveOwnerAlive({ lock: { pid: 4321, start: '133712345678901234' }, isPidAlive: alive, processTable: null }).alive,
      null,
      'a start time with no usable process table is unverifiable, not alive',
    );
    const table = { ok: true, readAt: NOW, table: [{ ProcessId: 4321, CreationFileTimeUtc: '133712345678901234' }] };
    assert.equal(deriveOwnerAlive({ lock: { pid: 4321, start: '133712345678901234' }, isPidAlive: alive, processTable: table, now: NOW }).alive, true);
    const recycled = deriveOwnerAlive({ lock: { pid: 4321, start: '133700000000000000' }, isPidAlive: alive, processTable: table, now: NOW });
    assert.equal(recycled.alive, false);
    assert.match(recycled.reason, /recycled/);
  });

  /* ── 5. Finalization records ────────────────────────────────────────────────────────────── */

  await check('finalization records round-trip, validate per scope, and advance only forwards', async () => {
    const { root } = await makeRepo('final');
    const stateRoot = path.join(root, 'state');
    const env = { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: stateRoot };
    const dir = finalizationDir(root, env);
    assert.equal(path.resolve(dir), path.resolve(path.join(stateRoot, 'worktrees')), 'the state-root override is honoured');

    const record = buildFinalizationRecord({
      resource: 'lane-a',
      worktreePath: path.join(root, 'main', '.claude', 'worktrees', 'lane-a'),
      branch: 'worktree-lane-a',
      head: 'c'.repeat(40),
      receipt: { verdict: 'LANDED', squash: 'd'.repeat(40) },
      by: { sessionId: 'sess-abc', pid: process.pid, creationFileTimeUtc: '133712345678901234' },
      leaseDurationSec: POLICY.thresholds.finalizationLeaseSec,
      now: NOW,
    });
    assert.equal(record.phase, 'claimed');
    assert.deepEqual(FINALIZATION_PHASES, ['claimed', 'archived', 'verified', 'removed', 'retired', 'done']);
    assert.equal(validateFinalizationRecord(record).ok, true);

    await writeFinalization({ mainRepoRoot: root, record, env });
    const entries = await readFinalizations({ mainRepoRoot: root, env });
    assert.equal(entries.length, 1);
    assert.equal(entries[0].ok, true);
    assert.deepEqual(entries[0].record, record, 'the record survives the atomic write byte-for-byte');

    // Per-scope validation: a record shaped for a sibling scope is reported unreadable, not accepted.
    await fsp.writeFile(path.join(dir, 'intruder.json'), JSON.stringify({
      schemaVersion: 1, recordId: 'intruder', producer: 'ui-shot', pid: 4321,
      creationFileTimeUtc: '133712345678901234', cmdlineFingerprint: 'vite', ownership: 'session-owned',
      probe: { kind: 'port', port: 5173 },
    }), 'utf8');
    const mixed = await readFinalizations({ mainRepoRoot: root, env });
    const intruder = mixed.find((e) => e.recordId === 'intruder');
    assert.equal(intruder.ok, false, 'an agent-spawns record is not silently accepted by this scope');
    assert.match(intruder.reason, /unsafe recordId undefined/, 'it fails on the missing resource id, not on a coincidence');
    await fsp.rm(path.join(dir, 'intruder.json'), { force: true });

    for (const [mutation, pattern] of [
      [{ phase: 'nonsense' }, /unknown phase/],
      [{ head: '' }, /no head/],
      [{ by: { sessionId: 'x', pid: 0, creationFileTimeUtc: null } }, /by\.pid/],
      [{ by: { sessionId: 'x', pid: 5, creationFileTimeUtc: 12345 } }, /creationFileTimeUtc/],
      [{ resource: '../escape' }, /unsafe recordId/],
      [{ schemaVersion: 2 }, /schemaVersion/],
      [{ lease: { durationSec: 900, renewedAt: 'x', expiresAt: 'y' } }, /renewedAt/],
    ]) {
      const verdict = validateFinalizationRecord({ ...record, ...mutation });
      assert.equal(verdict.ok, false, `${JSON.stringify(mutation)} must be refused`);
      assert.match(verdict.reason, pattern);
    }

    const advanced = await advanceFinalization({ mainRepoRoot: root, record, phase: 'archived', env, now: NOW + 5 * MINUTE });
    assert.equal(advanced.phase, 'archived');
    assert.notEqual(advanced.lease.expiresAt, record.lease.expiresAt, 'advancing renews the lease: progress is the liveness signal');
    await assert.rejects(
      () => advanceFinalization({ mainRepoRoot: root, record: advanced, phase: 'claimed', env }),
      /backwards/,
      'rewinding would re-run an already-completed destructive step',
    );

    assert.equal((await removeFinalization({ mainRepoRoot: root, resource: 'lane-a', env })).removed, true);
    assert.equal((await removeFinalization({ mainRepoRoot: root, resource: 'lane-a', env })).removed, false);
    assert.deepEqual(await readFinalizations({ mainRepoRoot: root, env }), []);
  });

  await check('claimFinalization refuses a live lease and grants a lapsed one whose claimant is dead', async () => {
    const holder = { sessionId: 'sess-holder', pid: 4321, creationFileTimeUtc: '133712345678901234' };
    const mine = { sessionId: 'sess-mine', pid: 99, creationFileTimeUtc: null };
    const live = buildFinalizationRecord({
      resource: 'lane-a', worktreePath: 'C:/x', branch: null, head: 'e'.repeat(40),
      by: holder, leaseDurationSec: 900, now: NOW,
    });

    assert.deepEqual(claimFinalization({ existing: null, by: mine, now: NOW }).claim, true);

    const denied = claimFinalization({ existing: live, by: mine, now: NOW + 5 * MINUTE });
    assert.equal(denied.claim, false, 'a live lease is never broken, whatever the pid says');
    assert.match(denied.reason, /live lease/);

    const lapsedAlive = claimFinalization({ existing: live, by: mine, now: NOW + 2 * HOUR, holderAlive: true });
    assert.equal(lapsedAlive.claim, false, 'a lapsed lease on a live holder is a busy worker, not an abandoned one');

    const granted = claimFinalization({ existing: live, by: mine, now: NOW + 2 * HOUR, holderAlive: false });
    assert.equal(granted.claim, true);
    assert.match(granted.reason, /dead/);

    const unverifiable = claimFinalization({ existing: live, by: mine, now: NOW + 2 * HOUR, holderAlive: null });
    assert.equal(unverifiable.claim, true, 'a record nobody could ever break would strand the resource forever');
    assert.match(unverifiable.reason, /unverifiable/);

    const resume = claimFinalization({ existing: live, by: { ...holder }, now: NOW + 5 * MINUTE });
    assert.equal(resume.claim, true, 'the crash-resume path: a session always re-claims its own record');
    assert.throws(() => claimFinalization({ existing: null, by: {} }), /by\.sessionId/);
  });

  /* ── 6. Census ──────────────────────────────────────────────────────────────────────────── */

  await check('census reports active, released, leftover and unmanaged resources without writing anything', async () => {
    const { root, repo } = await makeRepo('census');
    const stateRoot = path.join(root, 'state');
    const env = { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: stateRoot };
    const LIVE_PID = 4321;
    const isPidAlive = (pid) => pid === LIVE_PID;

    // (a) A registered, locked worktree whose owner process is alive.
    const activePath = path.join(repo, '.claude', 'worktrees', 'lane-active');
    git(repo, 'worktree', 'add', '-q', activePath, '-b', 'worktree-lane-active');
    writeMarkers({
      repoRoot: repo,
      branch: 'worktree-lane-active',
      markers: {
        resource: 'lane-active', session: 'sess-active', harness: 'claude',
        created: new Date(NOW - 2 * 24 * HOUR).toISOString(),
      },
    });
    git(repo, 'worktree', 'lock', '--reason', formatLockReason({ harness: 'claude', session: 'sess-active', pid: LIVE_PID }), activePath);

    // (b) A kept worktree whose session ended: the harness dropped the lock, nobody released it.
    const keptPath = path.join(repo, '.claude', 'worktrees', 'lane-kept');
    git(repo, 'worktree', 'add', '-q', keptPath, '-b', 'worktree-lane-kept');
    writeMarkers({
      repoRoot: repo,
      branch: 'worktree-lane-kept',
      markers: { resource: 'lane-kept', session: 'sess-kept', harness: 'claude', created: new Date(NOW - 9 * 24 * HOUR).toISOString() },
    });

    // (c) A leftover branch: markers survive, the directory is long gone.
    git(repo, 'branch', 'worktree-lane-gone');
    writeMarkers({
      repoRoot: repo,
      branch: 'worktree-lane-gone',
      markers: {
        resource: 'lane-gone', session: 'sess-gone', harness: 'codex',
        created: new Date(NOW - 30 * 24 * HOUR).toISOString(),
        hold: { reason: 'evidence pending', owner: 'elias', reviewBy: '2026-09-30' },
      },
    });

    // (d) A registered worktree OUTSIDE the sanctioned root: protected, never acted on.
    const strayPath = path.join(root, 'hand-made', 'stray');
    git(repo, 'worktree', 'add', '-q', strayPath, '-b', 'stray');
    writeMarkers({ repoRoot: repo, branch: 'stray', markers: { resource: 'stray', session: 'sess-stray' } });

    // The session ledger: the active session is fresh, the kept session went quiet days ago.
    const sessionsDir = path.join(stateRoot, 'sessions');
    await fsp.mkdir(sessionsDir, { recursive: true });
    await fsp.writeFile(path.join(sessionsDir, 'sess-active.json'), JSON.stringify({ lastActivityAt: new Date(NOW - MINUTE).toISOString() }), 'utf8');
    await fsp.writeFile(path.join(sessionsDir, 'sess-kept.json'), JSON.stringify({ lastActivityAt: new Date(NOW - 9 * 24 * HOUR).toISOString() }), 'utf8');

    const before = git(repo, 'config', '--null', '--get-regexp', '^branch\\..*\\.justsearch-');
    const beforeWorktrees = git(repo, 'worktree', 'list', '--porcelain');

    const result = await census({
      mainRepoRoot: repo,
      env,
      now: NOW,
      policy: POLICY,
      isPidAlive,
      processTable: null,
    });

    const byResource = new Map(result.worktrees.map((w) => [w.resource, w]));
    assert.deepEqual([...byResource.keys()].sort(), ['lane-active', 'lane-kept'], 'the main worktree is not a managed resource');

    const active = byResource.get('lane-active');
    assert.equal(active.state, STATES.ACTIVE);
    assert.equal(active.ownerAlive, true);
    assert.equal(active.branch, 'worktree-lane-active');
    assert.equal(active.sessionId, 'sess-active');
    assert.equal(active.ageDays, 2);
    assert.match(active.lock, /^claude session sess-active \(pid 4321\)$/);

    const kept = byResource.get('lane-kept');
    assert.equal(kept.state, STATES.RELEASED);
    assert.equal(kept.lock, null);
    assert.equal(kept.ownerAlive, null, 'no lock means no pid to probe');
    assert.match(kept.reasons.join(' '), /lock released by harness/);
    assert.equal(kept.ageDays, 9);

    assert.equal(result.leftoverBranches.length, 1);
    const [leftover] = result.leftoverBranches;
    assert.equal(leftover.branch, 'worktree-lane-gone');
    assert.equal(leftover.state, STATES.HELD);
    assert.equal(leftover.markers.hold.owner, 'elias');
    assert.equal(leftover.ageDays, 30);

    assert.equal(result.unmanaged.length, 1);
    assert.equal(result.unmanaged[0].resource, 'stray');
    assert.equal(result.unmanaged[0].state, STATES.UNMANAGED);
    assert.match(result.unmanaged[0].reasons.join(' '), /outside the sanctioned worktree root/);
    assert.deepEqual(result.finalizing, []);

    // A finalization record moves its resource to FINALIZING on the very next read.
    await writeFinalization({
      mainRepoRoot: repo,
      env,
      record: buildFinalizationRecord({
        resource: 'lane-kept', worktreePath: keptPath, branch: 'worktree-lane-kept',
        head: 'f'.repeat(40), by: { sessionId: 'sess-janitor', pid: LIVE_PID, creationFileTimeUtc: null },
        leaseDurationSec: 900, now: NOW,
      }),
    });
    const second = await census({ mainRepoRoot: repo, env, now: NOW, policy: POLICY, isPidAlive, processTable: null });
    const finalizingRow = second.worktrees.find((w) => w.resource === 'lane-kept');
    assert.equal(finalizingRow.state, STATES.FINALIZING);
    assert.equal(second.finalizing.length, 1);
    assert.equal(second.finalizing[0].phase, 'claimed');
    assert.equal(second.finalizing[0].lease, 'live');

    // Reading never writes: neither census changed a marker, a lock, or the worktree table.
    assert.equal(git(repo, 'config', '--null', '--get-regexp', '^branch\\..*\\.justsearch-'), before, 'the census wrote no branch config');
    assert.equal(git(repo, 'worktree', 'list', '--porcelain'), beforeWorktrees, 'the census changed no worktree registration');

    assert.equal(await isUnderSanctionedRoot({ worktreePath: activePath, sanctionedRoot: POLICY.sanctionedRoot, mainRepoRoot: repo }), true);
    assert.equal(await isUnderSanctionedRoot({ worktreePath: strayPath, sanctionedRoot: POLICY.sanctionedRoot, mainRepoRoot: repo }), false);
  });
} finally {
  for (const root of roots.reverse()) {
    await fsp.rm(root, { recursive: true, force: true }).catch(() => {});
  }
}

if (failures.length) {
  console.error(`952-worktree-register.test: ${failures.length} FAILED / ${passed} passed`);
  for (const failure of failures) console.error(`  x ${failure}`);
  process.exit(1);
}
console.log(`952-worktree-register.test: ${passed} passed`);
