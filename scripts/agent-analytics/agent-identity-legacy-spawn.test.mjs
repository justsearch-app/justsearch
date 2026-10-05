/**
 * Agent identity without hooks: legacy agent-spawn records are never reaped on their label.
 *
 * A record written before the identity change (or lacking the trusted owner fields) carries only a
 * `sessionId` label, and labels used to come from a shared pointer file that named whichever
 * session last started in a checkout. Such a record must:
 *   - never match "a session may always reap its own spawns" (SAME_SESSION) through the label;
 *   - never have its owner's activity read, so a stale-looking label cannot license a reap even
 *     after the lease lapses.
 * A record carrying `ownerIdentityVersion: 1` and an owner key is trusted for both.
 *
 * Calls pass BOTH the pre-change parameter (`callerSessionId`) and the owner key, so the same file
 * runs against the base revision, where the legacy cases are (wrongly) reaped.
 *
 *   node scripts/agent-analytics/agent-identity-legacy-spawn.test.mjs
 */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { reapEligible } = require('../dev/lib/agent-spawn-reaper.cjs');
const { OWNERSHIP_MODES, validateAgentSpawnRecord, buildAgentSpawnRecord } = require('../dev/lib/agent-spawn-record.cjs');
const { DEFAULT_THRESHOLDS } = require('../dev/lib/ownership-verdict.cjs');

let passed = 0;
const failures = [];
async function check(label, fn) {
  try { await fn(); passed += 1; console.log(`  ok   ${label}`); } catch (e) { failures.push(`${label}: ${e.message}`); console.log(`  FAIL ${label}: ${e.message}`); }
}

const NOW = Date.parse('2026-10-05T12:00:00.000Z');
const LABEL = 'session-label-aaaa';
const OWNER_IDENT = 'claude-4242-134356046943905590';
const PID = 41064;
const CTIME = '134320479841300350';
const FINGERPRINT = 'vite --port 5174';
const iso = (ms) => new Date(ms).toISOString();
const LAPSED = { durationSec: 30, renewedAt: iso(NOW - 3_630_000), expiresAt: iso(NOW - 3_600_000) };
const LIVE = { durationSec: 1800, renewedAt: iso(NOW - 60_000), expiresAt: iso(NOW + 1_740_000) };

const REC = (over = {}) => ({
  schemaVersion: 1,
  recordId: 'ui-shot-5174',
  producer: 'ui-shot',
  pid: PID,
  creationFileTimeUtc: CTIME,
  cmdlineFingerprint: FINGERPRINT,
  ownership: OWNERSHIP_MODES.SESSION_OWNED,
  probe: { kind: 'port', port: 5174 },
  startedAt: iso(NOW - 3_700_000),
  lease: LIVE,
  sessionId: LABEL,
  ...over,
});
const TRUSTED = { ownerIdentityVersion: 1, owner: { harness: 'claude', pid: 4242, creationTime: '134356046943905590', key: OWNER_IDENT } };
const TABLE = {
  ok: true,
  readAt: NOW,
  table: [{ ProcessId: PID, ParentProcessId: 9444, Name: 'node.exe', CommandLine: `node vite.js ${FINGERPRINT}`, CreationFileTimeUtc: CTIME }],
};
const STALE_ACTIVITY = { lastActivityAt: iso(NOW - 3_600_000), lastDevStackTouchAt: null };

function sweep(records, { callerSessionId = null, callerOwnerKey = null, activityFor = () => null } = {}) {
  return reapEligible({
    records,
    processTable: TABLE,
    occasion: 'session-start',
    callerSessionId,
    callerOwnerKey,
    now: NOW,
    thresholds: DEFAULT_THRESHOLDS,
    activityFor,
    env: {},
  });
}

await check('a legacy record whose label matches the caller is NOT reaped as same-session', () => {
  const out = sweep([REC()], { callerSessionId: LABEL, callerOwnerKey: OWNER_IDENT });
  assert.equal(out.reap.length, 0, `reaped: ${JSON.stringify(out.reap.map((e) => e.reason))}`);
});

await check('a legacy record with a stale label is NOT reaped through activity, even with its lease lapsed', () => {
  const out = sweep([REC({ lease: LAPSED })], { callerSessionId: 'someone-else-bbbb', callerOwnerKey: 'claude-1-1', activityFor: () => STALE_ACTIVITY });
  assert.equal(out.reap.length, 0, `reaped: ${JSON.stringify(out.reap.map((e) => e.reason))}`);
});

await check('a trusted record owned by the caller IS reaped as same-session (owner key, not label)', () => {
  const out = sweep([REC({ ...TRUSTED, sessionId: 'a-different-label' })], { callerOwnerKey: OWNER_IDENT });
  assert.equal(out.reap.length, 1);
});

await check('a trusted record of an ended owner with a lapsed lease IS reaped (stale owner)', () => {
  const out = sweep([REC({ ...TRUSTED, lease: LAPSED })], { callerOwnerKey: 'claude-1-1', activityFor: () => STALE_ACTIVITY });
  assert.equal(out.reap.length, 1);
});

await check('the trusted owner marker is validated: a version without an owner key is refused', () => {
  assert.equal(validateAgentSpawnRecord(REC(TRUSTED)).ok, true);
  assert.equal(validateAgentSpawnRecord(REC()).ok, true, 'a legacy record still reads (as an unknown owner)');
  assert.equal(validateAgentSpawnRecord(REC({ ownerIdentityVersion: 1 })).ok, false);
  assert.equal(validateAgentSpawnRecord(REC({ ownerIdentityVersion: 2, owner: TRUSTED.owner })).ok, false);
});

await check('new records carry the trusted owner identity', async () => {
  const rec = await buildAgentSpawnRecord({
    recordId: 'serve-worktree-fe-5174-41064', producer: 'serve-worktree-fe', pid: PID, creationFileTimeUtc: CTIME,
    cmdlineFingerprint: FINGERPRINT, port: 5174, leaseDurationSec: 60, sessionId: LABEL, owner: TRUSTED.owner, now: NOW,
  });
  assert.equal(rec.ownerIdentityVersion, 1);
  assert.equal(rec.owner.key, OWNER_IDENT);
});

console.log(`\nagent-identity-legacy-spawn: ${passed} passed, ${failures.length} failed`);
if (failures.length) process.exit(1);
