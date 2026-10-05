/**
 * Tempdoc 861 W4 — the reaper's authority matrix, cell by cell.
 *
 * 861 §7.1 Phase 4's acceptance is "a table-driven test covering EVERY cell of §6.3's matrix", and
 * §7.2's closure is "an independent reviewer walks the diff with the §6.3 matrix in hand". So the
 * matrix is literally a table here, each row carries the stable `cell` id the module assigns, and
 * the file finishes by asserting the union of observed cells EQUALS `CELLS` — a cell that stops
 * being produced turns this file red rather than quietly vanishing from the coverage claim.
 *
 * Three columns, because the matrix has two and [A4] splits one of them:
 *   `sweep`    — abandonment sweep, capability `execute`.
 *   `teardown` — conflict, capability `execute` (what `remove-worktree` gets: it may act up to the
 *                ceiling, and a holder it may not remove blocks the teardown).
 *   `build`    — conflict, capability `advisory` (the before-a-build occasion). [A4]: every `reap`
 *                in this column is downgraded to `report`, and the column mints NOTHING spendable.
 *
 * This file is sited under `scripts/agent-analytics/` on purpose, per 861 §7.6: `scripts/dev/*.test.mjs`
 * runs in CI NOWHERE, and the two most safety-critical tests in this change are the authority
 * matrix and the identity branch. Auto-discovery by `run-all-tests.mjs` is what makes them run.
 *
 * Nothing here touches a process it did not create. The kill-path tests live in the sibling
 * `861-w4-reaper-kill.test.mjs`, and even there every victim is a `node -e` child this test spawned.
 *
 * Run with: `node scripts/agent-analytics/861-w4-reaper-matrix.test.mjs`
 */

import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const HERE = path.dirname(fileURLToPath(import.meta.url));
const REAPER_PATH = path.join(HERE, '..', 'dev', 'lib', 'agent-spawn-reaper.cjs');

const {
  REAP_DISPOSITIONS,
  OCCASION_KINDS,
  OCCASIONS,
  CAPABILITIES,
  CELLS,
  OWNER_STATES,
  resolveOccasion,
  ownerActivityVerdict,
  reapEligible,
  markRefusals,
  executeReap,
} = require('../dev/lib/agent-spawn-reaper.cjs');
const { OWNERSHIP_MODES } = require('../dev/lib/agent-spawn-record.cjs');
const { DEFAULT_THRESHOLDS } = require('../dev/lib/ownership-verdict.cjs');

let passed = 0;
const failures = [];
/** Temp dirs (mutant modules, register fixtures) removed at the end. */
const mutantDirs = [];
async function check(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

/* ── fixtures ─────────────────────────────────────────────────────────────────────────────── */

const NOW = Date.parse('2026-08-25T12:00:00.000Z');
const OWNER = 'owner-session-aaaaaaaa';
const CALLER = 'caller-session-bbbbbbbb';
const PID = 41064;
const CTIME = '134320479841300350';
const FINGERPRINT = 'vite --port 5174';
const iso = (ms) => new Date(ms).toISOString();

/** An agent-spawns record. The default is the ordinary case: another session, lease live. */
const REC = (over = {}) => ({
  schemaVersion: 1,
  recordId: 'ui-shot-5174',
  producer: 'ui-shot',
  pid: PID,
  creationFileTimeUtc: CTIME,
  cmdlineFingerprint: FINGERPRINT,
  ownership: OWNERSHIP_MODES.SESSION_OWNED,
  probe: { kind: 'port', port: 5174 },
  startedAt: iso(NOW - 3_600_000),
  lease: { durationSec: 1800, renewedAt: iso(NOW - 60_000), expiresAt: iso(NOW + 1_740_000) },
  sessionId: OWNER,
  ...over,
  // Ownership is the trusted owner KEY (agent-identity.cjs), never the label. The matrix keys each
  // record's owner by its session string, so "same session" keeps meaning what the rows say.
  ...trustedOwnerFor(over.sessionId === undefined ? OWNER : over.sessionId, over),
});

function trustedOwnerFor(key, over) {
  if ('owner' in over || 'ownerIdentityVersion' in over || !key) return {};
  return { ownerIdentityVersion: 1, owner: { harness: 'claude', pid: null, creationTime: null, key } };
}

/** A lease that expired an hour ago, declaring `durationSec` as the owner's hold. */
const LAPSED = (durationSec = 30) => ({
  durationSec,
  renewedAt: iso(NOW - 3_600_000 - durationSec * 1000),
  expiresAt: iso(NOW - 3_600_000),
});

const ROW = (over = {}) => ({
  ProcessId: PID,
  ParentProcessId: 9444,
  Name: 'node.exe',
  CommandLine: `"C:\\Program Files\\nodejs\\node.exe" node_modules\\vite\\bin\\vite.js ${FINGERPRINT}`,
  CreationFileTimeUtc: CTIME,
  ...over,
});

/** A `readProcessTable()`-shaped result, stamped `ageMs` in the past. */
const TABLE = (rows = [ROW()], ageMs = 0) => ({ ok: true, table: rows, readAt: NOW - ageMs });

const ACTIVITY = {
  /** A tool call 10s ago: the owner is present. */
  fresh: { lastActivityAt: iso(NOW - 10_000), lastDevStackTouchAt: iso(NOW - 10_000) },
  /** Silent for an hour: past any threshold a 30s lease can justify. */
  longSilent: { lastActivityAt: iso(NOW - 3_600_000), lastDevStackTouchAt: null },
  /**
   * Silent 100 minutes, under a record declaring a 2-hour hold. `classifyActivity` calls this
   * `generalStale` (past 5 minutes) — and the declared hold says it is intent, not abandonment.
   * This is the exact shape of the 2026-07-14 defect (`dev-runner.cjs:2105-2113`).
   */
  quietButWorking: { lastActivityAt: iso(NOW - 6_000_000), lastDevStackTouchAt: null },
  /**
   * [F5] Silent 7 minutes with NO declared hold. Past `classifyActivity`'s 5-minute general
   * threshold, inside the 10-minute floor (5m abandoned + 5m grace) every record gets. The
   * 5-to-10-minute window the earlier fixtures skipped entirely — and the window whose verdict
   * was previously explained as "the declared lease duration is intent" for a 30-second lease.
   */
  silentSevenMinutes: { lastActivityAt: iso(NOW - 420_000), lastDevStackTouchAt: null },
};

/**
 * [F2] The three columns are now named OCCASIONS, not hand-assembled `{occasion, capability}`
 * pairs — the module's `OCCASIONS` map is the one authority, and this test derives from it rather
 * than restating it. When the earlier revision kept its own COLUMNS table, the test's pairing and
 * the module's rule were two places that could disagree; now a wrong pairing here cannot even be
 * written.
 */
const COLUMNS = {
  sweep: 'session-start',
  teardown: 'worktree-teardown',
  build: 'before-a-build',
};

await check('[F2] the test columns ARE occasions from the module map — one authority, not a restatement', () => {
  assert.deepEqual(resolveOccasion(COLUMNS.sweep), { kind: OCCASION_KINDS.SWEEP, capability: CAPABILITIES.EXECUTE });
  assert.deepEqual(resolveOccasion(COLUMNS.teardown), { kind: OCCASION_KINDS.CONFLICT, capability: CAPABILITIES.EXECUTE });
  assert.deepEqual(resolveOccasion(COLUMNS.build), { kind: OCCASION_KINDS.CONFLICT, capability: CAPABILITIES.ADVISORY });
});

const R = REAP_DISPOSITIONS;

/* ── THE MATRIX ───────────────────────────────────────────────────────────────────────────────
 *
 * Every row is one cell of §6.3 as corrected by [A1] and [A5]. `expect` is
 * {sweep, teardown, build}; `blocks` is the `blocksProceed` flag the teardown column must set.
 */
const MATRIX = [
  {
    id: 'registered, same session',
    row: 'Registered, **same session**',
    record: REC({ sessionId: CALLER, lease: LAPSED() }),
    activity: null,
    cell: CELLS.SAME_SESSION,
    expect: { sweep: R.REAP, teardown: R.REAP, build: R.REPORT },
    blocks: false,
  },
  {
    id: 'registered, other session, lease live',
    row: 'Registered, other session, **lease live**',
    record: REC(),
    activity: ACTIVITY.fresh,
    cell: CELLS.LEASE_LIVE,
    expect: { sweep: R.CONTENTION, teardown: R.CONTENTION, build: R.CONTENTION },
    blocks: true,
  },
  {
    id: 'registered, other session, lease unreadable (unknown is not lapsed)',
    row: 'Registered, other session, lease unknown — W2 leaseState tri-state',
    record: REC({ lease: { durationSec: 30, renewedAt: iso(NOW), expiresAt: 'not-a-timestamp' } }),
    activity: ACTIVITY.longSilent,
    cell: CELLS.LEASE_UNKNOWN,
    expect: { sweep: R.CONTENTION, teardown: R.CONTENTION, build: R.CONTENTION },
    blocks: true,
  },
  {
    // [A1], the cell that gated this whole phase.
    id: '[A1] lease lapsed BUT owner activity fresh -> contention, not garbage',
    row: 'Registered, other session, lease lapsed but owner activity fresh',
    record: REC({ lease: LAPSED() }),
    activity: ACTIVITY.fresh,
    cell: CELLS.LAPSED_OWNER_ACTIVE,
    expect: { sweep: R.CONTENTION, teardown: R.CONTENTION, build: R.CONTENTION },
    blocks: true,
    ownerState: OWNER_STATES.ACTIVE,
  },
  {
    // [A1]'s other arm: classifyActivity's `known:false` is LEAVE, never stale.
    id: '[A1] lease lapsed, owner activity UNKNOWN -> leave (absent signal is not a permissive one)',
    row: 'Registered, other session, lease lapsed, no activity stamp',
    record: REC({ lease: LAPSED() }),
    activity: null,
    cell: CELLS.LAPSED_OWNER_UNKNOWN,
    expect: { sweep: R.CONTENTION, teardown: R.CONTENTION, build: R.CONTENTION },
    blocks: true,
    ownerState: OWNER_STATES.UNKNOWN,
  },
  {
    // [F5] The floor window, distinct from a hold anyone declared.
    id: '[F5] lease lapsed, owner silent 7min with NO declared hold -> contention via the grace window',
    row: 'Registered, other session, lease lapsed, inside the abandonment floor',
    record: REC({ lease: LAPSED(30) }),
    activity: ACTIVITY.silentSevenMinutes,
    cell: CELLS.LAPSED_GRACE_WINDOW,
    expect: { sweep: R.CONTENTION, teardown: R.CONTENTION, build: R.CONTENTION },
    blocks: true,
    ownerState: OWNER_STATES.GRACE_WINDOW,
  },
  {
    // [A1]'s declared-hold arm, ported from dev-runner.cjs:2109-2113.
    id: '[A1] lease lapsed, owner generally stale BUT within its declared hold -> contention',
    row: 'Registered, other session, lease lapsed, within a declared hold',
    record: REC({ lease: LAPSED(7200) }),
    activity: ACTIVITY.quietButWorking,
    cell: CELLS.LAPSED_DECLARED_HOLD,
    expect: { sweep: R.CONTENTION, teardown: R.CONTENTION, build: R.CONTENTION },
    blocks: true,
    ownerState: OWNER_STATES.DECLARED_HOLD,
  },
  {
    id: 'lease lapsed AND owner activity stale -> reap (the ONLY other-session reap)',
    row: 'Registered, other session, **lease lapsed AND owner activity stale**',
    record: REC({ lease: LAPSED() }),
    activity: ACTIVITY.longSilent,
    cell: CELLS.LAPSED_OWNER_STALE,
    expect: { sweep: R.REAP, teardown: R.REAP, build: R.REPORT },
    blocks: false,
    ownerState: OWNER_STATES.STALE,
  },
  {
    // [A5]: a dimension, not a footnote — and "any owner state" means exactly that.
    id: '[A5] ownerless-singleton with a LAPSED lease and a long-silent owner -> never reap',
    row: 'Registered, `ownerless-singleton` (any owner state)',
    record: REC({ ownership: OWNERSHIP_MODES.OWNERLESS_SINGLETON, producer: 'otlp-sink', lease: LAPSED(), sessionId: OWNER }),
    activity: ACTIVITY.longSilent,
    cell: CELLS.OWNERLESS_SINGLETON,
    expect: { sweep: R.REPORT, teardown: R.REPORT, build: R.REPORT },
    // Never reaped, but still an unreapable HOLDER: a teardown must refuse rather than proceed
    // into the half-deleted worktree shell (861 §6.4 / §2-bis (c)).
    blocks: true,
  },
  {
    id: '[A5] ownerless-singleton owned by the CALLING session -> still never reap',
    row: 'Registered, `ownerless-singleton` — dominates the same-session reap rule',
    record: REC({ ownership: OWNERSHIP_MODES.OWNERLESS_SINGLETON, producer: 'otlp-sink', sessionId: CALLER, lease: LAPSED() }),
    activity: null,
    cell: CELLS.OWNERLESS_SINGLETON,
    expect: { sweep: R.REPORT, teardown: R.REPORT, build: R.REPORT },
    blocks: true,
  },
  {
    // [A5]'s never-reap row. The pid belongs to the dev-runner's own active run.
    id: "[A5] the dev-runner's own active run -> never reap, report only",
    row: "Registered, the **dev-runner's own active run**",
    record: REC({ sessionId: CALLER, lease: LAPSED(), producer: 'dev-runner' }),
    activity: ACTIVITY.longSilent,
    devRunnerActive: { runId: '846e7f29-24ba-45ea-ac68-dc398ba99159', pids: [PID] },
    cell: CELLS.DEV_RUNNER_OWN_RUN,
    expect: { sweep: R.REPORT, teardown: R.REPORT, build: R.REPORT },
    blocks: true,
  },
  {
    // [A5]'s identity cell, arm (ii): the creation time cannot be read.
    id: '[A5] identity REFUSE — creation time unreadable on the live row',
    row: 'Registered, **identity verification fails or is unavailable**',
    record: REC({ sessionId: CALLER, lease: LAPSED() }),
    table: TABLE([ROW({ CreationFileTimeUtc: null })]),
    activity: ACTIVITY.longSilent,
    cell: CELLS.IDENTITY_REFUSE,
    expect: { sweep: R.REFUSE, teardown: R.REFUSE, build: R.REFUSE },
    blocks: true,
  },
  {
    // arm (i): the recycled pid.
    id: '[A5] identity MISMATCH — pid recycled (creation times differ)',
    row: 'Registered, identity verification fails — recycled pid',
    record: REC({ sessionId: CALLER, lease: LAPSED() }),
    table: TABLE([ROW({ CreationFileTimeUtc: '134320479841399999' })]),
    activity: ACTIVITY.longSilent,
    cell: CELLS.IDENTITY_MISMATCH,
    expect: { sweep: R.REFUSE, teardown: R.REFUSE, build: R.REFUSE },
    // [F3] Refuses to KILL (the pid now belongs to someone else), but does not block a teardown:
    // the process this record describes is positively gone, so it holds nothing. Whatever inherited
    // the pid is the observed tier's business, not this record's.
    blocks: false,
  },
  {
    // arm (iii): the enumeration itself failed. An empty table is NO evidence, not exculpatory.
    id: '[A5] identity REFUSE — the process table is unavailable',
    row: 'Registered, identity verification is unavailable',
    record: REC({ sessionId: CALLER, lease: LAPSED() }),
    table: { ok: false, reason: 'process-table query exited 1' },
    activity: ACTIVITY.longSilent,
    cell: CELLS.IDENTITY_REFUSE,
    expect: { sweep: R.REFUSE, teardown: R.REFUSE, build: R.REFUSE },
    blocks: true,
  },
  {
    id: '[A5] identity REFUSE — the table snapshot has aged past its freshness bound',
    row: 'Registered, identity verification is unavailable (stale snapshot)',
    record: REC({ sessionId: CALLER, lease: LAPSED() }),
    table: TABLE([ROW()], 60_000),
    activity: ACTIVITY.longSilent,
    cell: CELLS.IDENTITY_REFUSE,
    expect: { sweep: R.REFUSE, teardown: R.REFUSE, build: R.REFUSE },
    blocks: true,
  },
  {
    id: '[A5] identity REFUSE — a bare unstamped array cannot be age-bounded',
    row: 'Registered, identity verification is unavailable (unstamped table)',
    record: REC({ sessionId: CALLER, lease: LAPSED() }),
    table: [ROW()],
    activity: ACTIVITY.longSilent,
    cell: CELLS.IDENTITY_REFUSE,
    expect: { sweep: R.REFUSE, teardown: R.REFUSE, build: R.REFUSE },
    blocks: true,
  },
  {
    id: 'a record that failed scope validation is no evidence at all',
    row: 'Registered, record unreadable',
    entry: { ok: false, recordId: 'ui-shot-9001', reason: 'unknown agent-spawn schemaVersion 99' },
    activity: null,
    cell: CELLS.RECORD_UNREADABLE,
    expect: { sweep: R.REFUSE, teardown: R.REFUSE, build: R.REFUSE },
    blocks: true,
  },
  {
    id: 'observed only (not registered) -> report with a ready-to-run kill line',
    row: '**Observed** only (not registered)',
    observed: { ProcessId: 55501, Name: 'node.exe', CommandLine: 'node vite.js --port 5199' },
    cell: CELLS.OBSERVED_ONLY,
    expect: { sweep: R.REPORT, teardown: R.REPORT, build: R.REPORT },
    blocks: false,
  },
];

/** Every cell the matrix produced, for the coverage assertion at the end. */
const seenCells = new Set();

function runCell(entryRow, columnName) {
  const records = entryRow.observed
    ? []
    : [entryRow.entry ? entryRow.entry : { ok: true, recordId: entryRow.record.recordId, record: entryRow.record }];
  return reapEligible({
    records,
    observed: entryRow.observed ? [entryRow.observed] : [],
    processTable: entryRow.table === undefined ? TABLE() : entryRow.table,
    occasion: COLUMNS[columnName],
    callerOwnerKey: CALLER,
    now: NOW,
    thresholds: DEFAULT_THRESHOLDS,
    devRunnerActive: entryRow.devRunnerActive ?? null,
    activityFor: () => entryRow.activity ?? null,
    env: {},
  });
}

for (const m of MATRIX) {
  for (const columnName of Object.keys(COLUMNS)) {
    await check(`MATRIX [${columnName}] ${m.id}`, () => {
      const out = runCell(m, columnName);
      assert.equal(out.all.length, 1, 'exactly one entry per matrix cell');
      const e = out.all[0];
      seenCells.add(e.cell);
      assert.equal(e.cell, m.cell, `cell id (row: ${m.row})`);
      assert.equal(e.disposition, m.expect[columnName], `disposition (row: ${m.row}) — reason: ${e.reason}`);
      assert.ok(out[m.expect[columnName]].includes(e), 'the entry must land in the bucket its disposition names');
      if (m.ownerState) assert.equal(e.owner?.state, m.ownerState, 'owner activity state');
      // blocksProceed is a teardown-column-only signal.
      assert.equal(e.blocksProceed, columnName === 'teardown' ? m.blocks : false, 'blocksProceed');
      assert.equal(out.blocksProceed, columnName === 'teardown' ? m.blocks : false, 'aggregate blocksProceed');
    });
  }
}

/* ── the coverage assertion: every cell, or this file goes red ────────────────────────────── */

await check('COVERAGE: the matrix exercised every declared cell of the corrected §6.3 matrix', () => {
  const declared = new Set(Object.values(CELLS));
  const missing = [...declared].filter((c) => !seenCells.has(c));
  const extra = [...seenCells].filter((c) => !declared.has(c));
  assert.deepEqual(missing, [], `cells declared but never exercised: ${missing.join(', ')}`);
  assert.deepEqual(extra, [], `cells produced but not declared: ${extra.join(', ')}`);
  assert.equal(seenCells.size, declared.size);
});

/* ── [A4]: the advisory column mints nothing spendable ────────────────────────────────────── */

await check('[A4] the advisory (before-a-build) column produces ZERO reap entries across the whole matrix', () => {
  let reaps = 0;
  let downgraded = 0;
  for (const m of MATRIX) {
    const out = runCell(m, 'build');
    reaps += out.reap.length;
    downgraded += out.all.filter((e) => e.downgraded).length;
  }
  assert.equal(reaps, 0, 'a PreToolUse-tier occasion must never hold a reap entry');
  assert.ok(downgraded >= 2, `at least the two reap rows must be visibly downgraded, saw ${downgraded}`);
});

await check('[A4] a downgraded entry still names its ceiling, so an advisory surface can say "this WOULD be reapable"', () => {
  const out = runCell(MATRIX[0], 'build');
  const e = out.all[0];
  assert.equal(e.disposition, R.REPORT);
  assert.equal(e.ceiling, R.REAP);
  assert.equal(e.downgraded, true);
});

await check('[A4] executeReap refuses a downgraded entry — the advisory occasion holds nothing spendable', async () => {
  const out = runCell(MATRIX[0], 'build');
  let execCalls = 0;
  const res = await executeReap(out.all[0], {
    dir: null,
    readTable: () => TABLE(),
    exec: () => { execCalls += 1; return { status: 0 }; },
    now: () => NOW,
  });
  assert.equal(res.refused, true);
  assert.equal(res.killed, false);
  assert.equal(execCalls, 0, 'no kill may be attempted for a non-reap entry');
  assert.match(res.reason, /advisory-downgraded/);
});

/* ── [F2] the unwritable-spelling probe ───────────────────────────────────────────────────── */

const reapable = () => {
  const rec = REC({ sessionId: CALLER, lease: LAPSED() });
  return [{ ok: true, recordId: rec.recordId, record: rec }];
};

await check('[F2] PROBE: {occasion: CONFLICT, capability: EXECUTE} is now UNWRITABLE — it throws', () => {
  // The exact spelling the earlier revision accepted, and the one [A4] forbids for the
  // before-a-build surface. `OCCASION_KINDS.CONFLICT` is a column, not an occasion.
  assert.throws(
    () => reapEligible({
      records: reapable(),
      processTable: TABLE(),
      occasion: OCCASION_KINDS.CONFLICT,
      capability: CAPABILITIES.EXECUTE,
      callerOwnerKey: CALLER,
      now: NOW,
      activityFor: () => null,
      env: {},
    }),
    /unknown reap occasion "conflict".*Capability is bound to the occasion/s,
  );
});

await check('[F2] PROBE: a stray `capability` alongside a REAL occasion cannot upgrade it either', () => {
  const out = reapEligible({
    records: reapable(),
    processTable: TABLE(),
    occasion: 'before-a-build',
    // Ignored: capability is not an input. If this ever took effect, a PreToolUse hook would hold
    // a kill list — which is precisely [A4]'s prohibition.
    capability: CAPABILITIES.EXECUTE,
    callerOwnerKey: CALLER,
    now: NOW,
    activityFor: () => null,
    env: {},
  });
  assert.equal(out.reap.length, 0, 'before-a-build must never hold a reap entry, whatever else is passed');
  assert.equal(out.all[0].capability, CAPABILITIES.ADVISORY, 'capability comes from the occasion');
  assert.equal(out.all[0].downgraded, true);
});

await check('[F2] PROBE: an omitted or unknown occasion THROWS — no silent safe-default fallback', () => {
  assert.throws(() => reapEligible({ records: reapable(), processTable: TABLE() }), /unknown reap occasion/);
  assert.throws(() => reapEligible({ records: [], processTable: TABLE(), occasion: 'sweep' }), /unknown reap occasion/);
  assert.throws(() => resolveOccasion('constructor'), /unknown reap occasion/, 'prototype keys are not occasions');
});

await check('[F2] all six §6.4 occasions are declared, frozen, and only the two advisory ones are advisory', () => {
  assert.deepEqual(Object.keys(OCCASIONS).sort(), [
    'before-a-build', 'orientation', 'session-closeout', 'session-end', 'session-start', 'worktree-teardown',
  ]);
  assert.ok(Object.isFrozen(OCCASIONS));
  const advisory = Object.entries(OCCASIONS).filter(([, v]) => v.capability === CAPABILITIES.ADVISORY).map(([k]) => k).sort();
  assert.deepEqual(advisory, ['before-a-build', 'orientation'], '§6.4: before-a-build never kills; world-state never kills');
});

await check('[F2] every EXECUTE occasion can actually mint a reap, and neither advisory one can', () => {
  for (const [name, spec] of Object.entries(OCCASIONS)) {
    const out = reapEligible({
      records: reapable(), processTable: TABLE(), occasion: name,
      callerOwnerKey: CALLER, now: NOW, activityFor: () => null, env: {},
    });
    const expected = spec.capability === CAPABILITIES.EXECUTE ? 1 : 0;
    assert.equal(out.reap.length, expected, `occasion ${name} (${spec.capability})`);
  }
});

/* ── §6.4: a teardown refuses while an UNREAPABLE holder remains ──────────────────────────── */

await check('§6.4 blocksProceed: a never-reap holder still blocks a teardown, and a reapable one does not', () => {
  const singleton = MATRIX.find((m) => m.id.startsWith('[A5] ownerless-singleton with a LAPSED'));
  const reapable = MATRIX.find((m) => m.id.startsWith('registered, same session'));
  assert.equal(runCell(singleton, 'teardown').blocksProceed, true, 'an unreapable holder blocks');
  assert.equal(runCell(reapable, 'teardown').blocksProceed, false, 'a holder the teardown can clear does not');
  assert.equal(runCell(singleton, 'sweep').blocksProceed, false, 'a sweep has nothing to proceed with');
  assert.equal(runCell(singleton, 'build').blocksProceed, false, '[A4]: an advisory occasion never blocks');
});

await check('§6.4 blocksProceed: the observed tier never blocks — that judgement belongs to the caller', () => {
  assert.equal(runCell(MATRIX.at(-1), 'teardown').blocksProceed, false);
});

/* ── [F3] a DEAD never-reap holder is a phantom, and must not block a teardown ─────────────── */

const SINGLETON = REC({
  ownership: OWNERSHIP_MODES.OWNERLESS_SINGLETON,
  producer: 'otlp-sink',
  recordId: 'otlp-sink',
  lease: LAPSED(),
});

function singletonTeardown(table) {
  return reapEligible({
    records: [{ ok: true, recordId: SINGLETON.recordId, record: SINGLETON }],
    processTable: table,
    occasion: 'worktree-teardown',
    callerOwnerKey: CALLER,
    now: NOW,
    activityFor: () => ACTIVITY.longSilent,
    env: {},
  });
}

await check('[F3] ARM 1 — a LIVE ownerless-singleton blocks the teardown (it really is holding the tree)', () => {
  const out = singletonTeardown(TABLE());
  const e = out.all[0];
  assert.equal(e.cell, CELLS.OWNERLESS_SINGLETON);
  assert.equal(e.disposition, R.REPORT, 'never reaped');
  assert.equal(e.identity.verdict, 'match');
  assert.equal(e.blocksProceed, true, 'an unreapable holder that exists must stop the teardown');
  assert.equal(out.blocksProceed, true);
});

await check('[F3] ARM 2 — a DEAD ownerless-singleton does NOT block: identity MISMATCH means positively gone', () => {
  // The pid is absent from a table that WAS read. Nothing holds the tree, but the never-reap policy
  // keeps the record in `report` and the record itself survives until the 7-day prune — so without
  // the MISMATCH term a teardown would refuse for a week over a process that exited.
  const out = singletonTeardown(TABLE([ROW({ ProcessId: 999999 })]));
  const e = out.all[0];
  assert.equal(e.cell, CELLS.OWNERLESS_SINGLETON, 'still never-reap, still reported');
  assert.equal(e.disposition, R.REPORT);
  assert.equal(e.identity.verdict, 'mismatch');
  assert.match(e.identity.reason, /not present in the process table/);
  assert.equal(e.blocksProceed, false, 'a phantom holder must not block a teardown');
  assert.equal(out.blocksProceed, false);
});

await check('[F3] ARM 3 — an UNREADABLE verdict still blocks: unknown is not exculpatory, only a read negative unblocks', () => {
  const unreadable = singletonTeardown(TABLE([ROW({ CreationFileTimeUtc: null })]));
  assert.equal(unreadable.all[0].identity.verdict, 'refuse');
  assert.equal(unreadable.all[0].blocksProceed, true);
  const unavailable = singletonTeardown({ ok: false, reason: 'process-table query exited 1' });
  assert.equal(unavailable.all[0].identity.verdict, 'refuse');
  assert.equal(unavailable.all[0].blocksProceed, true);
});

await check('[F3] the same three arms hold for an ordinary registered holder, not just the singleton', () => {
  const rec = REC({ sessionId: 'someone-else', recordId: 'ui-shot-5176' });
  const run = (table) => reapEligible({
    records: [{ ok: true, recordId: rec.recordId, record: rec }],
    processTable: table,
    occasion: 'worktree-teardown',
    callerOwnerKey: CALLER,
    now: NOW,
    activityFor: () => ACTIVITY.fresh,
    env: {},
  });
  assert.equal(run(TABLE()).blocksProceed, true, 'live + contended blocks');
  assert.equal(run(TABLE([ROW({ ProcessId: 999999 })])).blocksProceed, false, 'gone does not block');
  assert.equal(run({ ok: false, reason: 'query failed' }).blocksProceed, true, 'unknown still blocks');
});

/* ── [F7] the sweep-side marking obligation ───────────────────────────────────────────────── */

await check('[F7] a projection refusal carries markPending, and the bucket collects them', () => {
  const rec = REC({ sessionId: CALLER, lease: LAPSED() });
  const out = reapEligible({
    records: [{ ok: true, recordId: rec.recordId, record: rec }],
    processTable: TABLE([ROW({ CreationFileTimeUtc: null })]),
    occasion: 'session-start',
    callerOwnerKey: CALLER,
    now: NOW,
    activityFor: () => null,
    env: {},
  });
  assert.equal(out.refuse.length, 1);
  assert.equal(out.refuse[0].markPending, true, 'the §6.3 cell says "mark failed-verify" in the SWEEP column too');
  assert.deepEqual(out.markPending, out.refuse, 'the bucket is the obligation, made hard to skip');
});

await check('[F7] a non-refusal never carries markPending, and an unattributable refusal cannot be marked', () => {
  assert.equal(runCell(MATRIX[0], 'sweep').all[0].markPending, false, 'a reap has nothing to mark');
  assert.equal(runCell(MATRIX.at(-1), 'sweep').all[0].markPending, false, 'the observed tier has no record to mark');
  // A record that failed scope validation has no identity verdict to record.
  const unreadable = MATRIX.find((m) => m.entry);
  assert.equal(runCell(unreadable, 'sweep').all[0].markPending, false);
});

await check('[F7] markRefusals discharges the obligation, retains every record, and reports failures', async () => {
  const dir = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w4-mark-'));
  mutantDirs.push(dir);
  const rec = REC({ sessionId: CALLER, lease: LAPSED() });
  const file = path.join(dir, `${rec.recordId}.json`);
  await fsp.writeFile(file, JSON.stringify(rec, null, 2), 'utf8');

  const out = reapEligible({
    records: [{ ok: true, recordId: rec.recordId, record: rec }, { ok: false, recordId: 'ghost', reason: 'bad schema' }],
    processTable: TABLE([ROW({ CreationFileTimeUtc: null })]),
    occasion: 'session-start',
    callerOwnerKey: CALLER,
    now: NOW,
    activityFor: () => null,
    env: {},
  });
  const res = await markRefusals(out.markPending, { dir, now: NOW });
  assert.deepEqual(res.marked, [rec.recordId]);
  assert.deepEqual(res.failed, []);

  const after = JSON.parse(await fsp.readFile(file, 'utf8'));
  assert.equal(after.identityVerify.verdict, 'refuse');
  assert.equal(after.pid, rec.pid, 'RETAINED, never deleted — the trail survives the refusal');

  // A record that vanished between projection and marking is reported, not thrown.
  await fsp.rm(file, { force: true });
  const second = await markRefusals(out.markPending, { dir, now: NOW });
  assert.deepEqual(second.marked, []);
  assert.equal(second.failed.length, 1);
  assert.match(second.failed[0].reason, /no such record/);
});

/* ── the observed tier carries a ready-to-run kill line ───────────────────────────────────── */

await check('the observed tier is reported with a ready-to-run kill line, never killed', () => {
  const out = runCell(MATRIX.at(-1), 'sweep');
  assert.equal(out.reap.length, 0);
  assert.equal(out.report[0].killLine, 'taskkill /PID 55501 /F');
});

/* ── the [A1] join, unit-level ────────────────────────────────────────────────────────────── */

await check('[A1] ownerActivityVerdict: known:false is UNKNOWN, never STALE', () => {
  const v = ownerActivityVerdict(null, REC({ lease: LAPSED() }), NOW, DEFAULT_THRESHOLDS, {});
  assert.equal(v.state, OWNER_STATES.UNKNOWN);
});

await check('[A1] ownerActivityVerdict: the declared hold widens the threshold exactly as dev-runner.cjs:2110-2113 does', () => {
  const grace = 5 * 60_000;
  const short = ownerActivityVerdict(ACTIVITY.longSilent, REC({ lease: LAPSED(30) }), NOW, DEFAULT_THRESHOLDS, {});
  assert.equal(short.thresholdMs, DEFAULT_THRESHOLDS.abandonedAfterMs + grace, 'a 30s lease cannot beat the default');
  const long = ownerActivityVerdict(ACTIVITY.longSilent, REC({ lease: LAPSED(7200) }), NOW, DEFAULT_THRESHOLDS, {});
  assert.equal(long.thresholdMs, 7200 * 1000 + grace, 'a declared 2h hold IS the threshold');
  assert.equal(long.state, OWNER_STATES.DECLARED_HOLD, 'one hour of silence is inside a two-hour declared hold');
  assert.equal(short.state, OWNER_STATES.STALE, 'one hour of silence is far outside a 30s hold plus grace');
});

/* ── MUTATION PROOF: the [A1] activity join is load-bearing ───────────────────────────────── */

/**
 * The join is the amendment. A test that merely asserts "lapsed + fresh owner -> contention" is
 * satisfied by any implementation that happens to return contention, including one that never
 * consults activity at all — so this compiles a MUTANT of the module with the join replaced by a
 * constant `stale`, which is exactly rev 1's "lapsed -> reap", and asserts the reap-while-working
 * cell flips. If the join is ever dropped, this goes red by name.
 */
async function loadMutant(replace, replaceWith) {
  const src = await fsp.readFile(REAPER_PATH, 'utf8');
  assert.ok(src.includes(replace), `mutation target not found in the reaper source: ${replace}`);
  const libDir = path.dirname(REAPER_PATH).replace(/\\/g, '/');
  const mutated = src
    .replace(replace, replaceWith)
    .replace(/require\('\.\//g, `require('${libDir}/`);
  const file = path.join(await fsp.mkdtemp(path.join(os.tmpdir(), '861-w4-mutant-')), 'mutant.cjs');
  await fsp.writeFile(file, mutated, 'utf8');
  return { mod: require(file), dir: path.dirname(file) };
}

await check('MUTATION [A1]: dropping the activity join turns the reap-while-working cell into a reap', async () => {
  const working = MATRIX.find((m) => m.id.startsWith('[A1] lease lapsed BUT owner activity fresh'));
  assert.ok(working, 'the reap-while-working row must exist');

  // The real module: contention.
  const real = runCell(working, 'sweep');
  assert.equal(real.all[0].disposition, R.CONTENTION);
  assert.equal(real.all[0].cell, CELLS.LAPSED_OWNER_ACTIVE);

  // The mutant: the join is replaced by a constant "stale", i.e. rev 1's lapsed-alone rule.
  const { mod, dir } = await loadMutant(
    'ownerActivityVerdict(activity, record, now, thresholds, env)',
    "{ state: 'stale', reason: 'MUTANT: activity join dropped', abandonedMs: null, thresholdMs: 0 }",
  );
  mutantDirs.push(dir);
  const mutated = mod.reapEligible({
    records: [{ ok: true, recordId: working.record.recordId, record: working.record }],
    processTable: TABLE(),
    occasion: COLUMNS.sweep,
    callerOwnerKey: CALLER,
    now: NOW,
    activityFor: () => working.activity,
    env: {},
  });
  assert.equal(
    mutated.all[0].disposition,
    R.REAP,
    'the mutant must reap — otherwise this test proves nothing about the join being load-bearing',
  );
  assert.equal(mutated.all[0].cell, CELLS.LAPSED_OWNER_STALE);
  assert.notEqual(real.all[0].disposition, mutated.all[0].disposition, 'real and mutant must disagree');
});

await check('MUTATION [A1]: the same mutant also breaks the declared-hold cell', async () => {
  const hold = MATRIX.find((m) => m.id.includes('within its declared hold'));
  const { mod, dir } = await loadMutant(
    'ownerActivityVerdict(activity, record, now, thresholds, env)',
    "{ state: 'stale', reason: 'MUTANT: activity join dropped', abandonedMs: null, thresholdMs: 0 }",
  );
  mutantDirs.push(dir);
  const mutated = mod.reapEligible({
    records: [{ ok: true, recordId: hold.record.recordId, record: hold.record }],
    processTable: TABLE(),
    occasion: COLUMNS.sweep,
    callerOwnerKey: CALLER,
    now: NOW,
    activityFor: () => hold.activity,
    env: {},
  });
  assert.equal(runCell(hold, 'sweep').all[0].disposition, R.CONTENTION);
  assert.equal(mutated.all[0].disposition, R.REAP);
});

/* ── scope: the reaper never reads the `foreign/` register ────────────────────────────────── */

await check('SCOPE: the reaper requires exactly the agent-spawns-scope modules, plus one generic helper', async () => {
  const src = await fsp.readFile(REAPER_PATH, 'utf8');
  const specs = [...src.matchAll(/require\('([^']+)'\)/g)].map((m) => m[1]).sort();
  assert.deepEqual(specs, [
    './agent-spawn-record.cjs',
    './owner-presence.cjs',
    './ownership-verdict.cjs',
    './process-identity.cjs',
    './process-record.cjs',
    'node:child_process',
    'node:fs/promises',
    'node:path',
  ], 'an unexpected require is a scope widening');
});

await check('SCOPE: the ONLY symbol taken from the shared-grammar module is the generic atomic writer', async () => {
  const src = await fsp.readFile(REAPER_PATH, 'utf8');
  const m = src.match(/const \{([^}]*)\} = require\('\.\/process-record\.cjs'\)/);
  assert.ok(m, 'the process-record.cjs import must be a destructuring one, so this test can read it');
  const names = m[1].split(',').map((s) => s.trim()).filter(Boolean).sort();
  assert.deepEqual(names, ['writeRecordAtomic'], 'importing anything else from that file reaches into the foreign scope');
});

await check('SCOPE: no foreign-scope identifier appears anywhere in the reaper', async () => {
  const src = await fsp.readFile(REAPER_PATH, 'utf8');
  const forbidden = /\bFOREIGN_[A-Z_]+\b|\breadForeignRegister\b|\bresolveForeignRegisterDir\b|\bvalidateForeignRecord\b|\bprobeForeignRuns\b|\bFOREIGN_REGISTER_DIRNAME\b/;
  assert.equal(forbidden.test(src), false, 'the reaper must not name a foreign-scope symbol');
});

await check('SCOPE: reapEligible is synchronous — a function that read a register directory could not be', () => {
  assert.equal(reapEligible.constructor.name, 'Function', 'reapEligible must not be async: it is a pure projection');
  assert.equal(executeReap.constructor.name, 'AsyncFunction', 'executeReap is the effectful arm');
});

/* ── cleanup + report ─────────────────────────────────────────────────────────────────────── */

for (const dir of mutantDirs) await fsp.rm(dir, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`861-w4-reaper-matrix.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  \u2717 ${f}`);
  process.exit(1);
}
console.log(`861-w4-reaper-matrix.test: all ${passed} checks passed (${seenCells.size} matrix cells covered)`);
