'use strict';

/**
 * Owner presence: is the agent session that owns something still running, and has it used the
 * dev stack lately?
 *
 * Two independent facts, both keyed by the owner key from `agent-identity.cjs`:
 *   - liveness, from the harness process itself: the owner's pid present with the same creation
 *     time is `alive`; the pid missing or carrying a different creation time is `ended` (a reused
 *     pid never looks alive); no owner, an override owner without a pid, or no process evidence is
 *     `unknown`. Unknown behaves like an active owner and is never reaped;
 *   - dev-stack use, from a touch record the dev tools write on every call:
 *     `<stateRoot>/owners/<key>.json` `lastDevStackTouchAt`.
 *
 * Grades fed to the unchanged pure verdict (`ownership-verdict.cjs`):
 *   ended                                   -> abandoned (reclaim without asking)
 *   alive, touch older than the idle bound  -> idle-hold (takeover with a warning)
 *   alive, recent touch                     -> active (ask the user)
 *   unknown, or alive with no touch record  -> active-like (ask the user)
 *
 * The supervisor's renewal tick uses `decideSupervisorPresence` (pure): an ended owner's stack is
 * shut down after the grace period (or the declared hold if longer); a running owner's stack after
 * an hour without dev-stack use beyond the declared hold; an unknown owner's stack never.
 */

const fs = require('node:fs');
const path = require('node:path');

const { normalizeCreationTime, coerceProcessTable, DEFAULT_MAX_TABLE_AGE_MS } = require('./process-identity.cjs');
const { isSafeKey, readFixtureTable } = require('./agent-identity.cjs');

const OWNERS_DIRNAME = 'owners';
const TOUCH_SCHEMA = 'owner-touch.v1';

function envMs(env, name, def) {
  const v = Number(env[name]);
  return Number.isFinite(v) && v > 0 ? v : def;
}

/** Thresholds, env-tunable so integration tests can drive them in seconds. */
function presenceThresholds(env = process.env) {
  return Object.freeze({
    idleAfterMs: envMs(env, 'JUSTSEARCH_DEV_IDLE_MS', 15 * 60_000),
    graceMs: envMs(env, 'JUSTSEARCH_DEV_REAPER_GRACE_MS', 5 * 60_000),
    idleShutdownMs: envMs(env, 'JUSTSEARCH_DEV_IDLE_SHUTDOWN_MS', 60 * 60_000),
    touchRetentionMs: envMs(env, 'JUSTSEARCH_DEV_OWNER_RETENTION_MS', 3 * 24 * 60 * 60_000),
  });
}

function ownersDir(stateRoot) {
  return path.join(stateRoot, OWNERS_DIRNAME);
}

function touchPath(stateRoot, key) {
  if (!isSafeKey(key)) return null;
  return path.join(ownersDir(stateRoot), `${key}.json`);
}

function readOwnerRecord(stateRoot, key) {
  const file = touchPath(stateRoot, key);
  if (!file) return null;
  try {
    const doc = JSON.parse(fs.readFileSync(file, 'utf8'));
    return doc && typeof doc === 'object' ? doc : null;
  } catch {
    return null;
  }
}

function writeOwnerRecord(stateRoot, key, patch) {
  const file = touchPath(stateRoot, key);
  if (!file) return false;
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const cur = readOwnerRecord(stateRoot, key) || {};
    const tmp = `${file}.${process.pid}.${Math.random().toString(36).slice(2, 8)}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify({ ...cur, ...patch, schema: TOUCH_SCHEMA, key }), 'utf8');
    try {
      fs.renameSync(tmp, file);
    } catch (err) {
      try { fs.rmSync(tmp, { force: true }); } catch { /* best effort */ }
      throw err;
    }
    return true;
  } catch {
    return false;
  }
}

/** Record that `identity` just used the dev stack. Best-effort; no owner key means nothing to do. */
function touchOwner(stateRoot, identity, { now = Date.now() } = {}) {
  const owner = identity?.owner;
  if (!owner?.key) return false;
  return writeOwnerRecord(stateRoot, owner.key, {
    harness: identity.harness,
    pid: owner.pid ?? null,
    creationTime: owner.creationTime ?? null,
    sessionId: identity.sessionId ?? null,
    lastDevStackTouchAt: new Date(now).toISOString(),
  });
}

/** Merge a patch (e.g. `ownedEpoch`) into the owner's record. */
function mergeOwnerRecord(stateRoot, key, patch) {
  return writeOwnerRecord(stateRoot, key, patch);
}

/** The owner block stored on holder / helper / worktree records, from an identity. */
function ownerBlockFor(identity) {
  const o = identity?.owner;
  if (!o?.key) return null;
  return {
    harness: identity.harness,
    pid: o.pid ?? null,
    creationTime: o.creationTime ?? null,
    key: o.key,
  };
}

/**
 * The owner block encoded by a process-derived owner key (`<harness>-<pid>-<creationTime>`), or
 * null for an override key, a legacy value or anything else. Pure.
 */
function parseOwnerKey(key) {
  const m = /^(claude|codex)-(\d+)-(\d+)$/.exec(String(key ?? ''));
  if (!m) return null;
  return { harness: m[1], pid: Number(m[2]), creationTime: m[3], key: m[0] };
}

/**
 * Liveness from a process table. Pure over its inputs.
 * @returns {{ state:'alive'|'ended'|'unknown', reason:string }}
 */
function ownerLiveness(owner, { table, now = Date.now(), maxTableAgeMs = DEFAULT_MAX_TABLE_AGE_MS } = {}) {
  if (!owner || !owner.key) return { state: 'unknown', reason: 'no owner recorded' };
  const pid = Number(owner.pid);
  const ct = normalizeCreationTime(owner.creationTime == null ? null : String(owner.creationTime));
  if (!Number.isInteger(pid) || pid <= 0 || !ct) return { state: 'unknown', reason: 'owner has no process identity (override or legacy)' };
  const coerced = coerceProcessTable(table, { now, maxTableAgeMs });
  if (!coerced.ok) return { state: 'unknown', reason: `no process evidence: ${coerced.reason}` };
  const row = coerced.table.find((r) => Number(r?.ProcessId) === pid);
  if (!row) return { state: 'ended', reason: `owner pid ${pid} is gone` };
  const rowCt = normalizeCreationTime(row.CreationFileTimeUtc);
  if (!rowCt) return { state: 'unknown', reason: `owner pid ${pid} row has no creation time` };
  if (rowCt !== ct) return { state: 'ended', reason: `owner pid ${pid} was reused (creation time differs)` };
  return { state: 'alive', reason: `owner pid ${pid} running` };
}

/**
 * Cheap liveness for a frequent tick: does the pid exist at all? 'present' | 'absent' | 'unknown'.
 * A present pid may be a reused one; callers confirm with `ownerLiveness` before acting.
 */
function ownerPidPresence(owner, { env = process.env, kill = process.kill } = {}) {
  const pid = Number(owner?.pid);
  if (!Number.isInteger(pid) || pid <= 0) return 'unknown';
  const fixture = readFixtureTable(env);
  if (fixture) {
    if (!fixture.ok) return 'unknown';
    return fixture.table.some((r) => Number(r?.ProcessId) === pid) ? 'present' : 'absent';
  }
  try {
    kill(pid, 0);
    return 'present';
  } catch (err) {
    if (err && err.code === 'EPERM') return 'present';
    if (err && err.code === 'ESRCH') return 'absent';
    return 'unknown';
  }
}

/**
 * Map liveness + touch into the activity shape the pure verdict reads (`classifyActivity`).
 * ended -> positive staleness (abandoned); alive -> fresh general activity plus the touch time
 * (no touch -> null, i.e. unknown, active-like); unknown -> null.
 */
function presenceToOwnerActivity(liveness, touchRecord, now = Date.now()) {
  const state = liveness?.state ?? 'unknown';
  if (state === 'ended') return { lastActivityAt: new Date(0).toISOString(), lastDevStackTouchAt: null };
  if (state === 'alive') {
    const touch = touchRecord?.lastDevStackTouchAt ?? null;
    if (!touch) return null;
    return { lastActivityAt: new Date(now).toISOString(), lastDevStackTouchAt: touch };
  }
  return null;
}

/** Declared hold (lease duration) in ms, from an active.json lease. */
function declaredHoldMsOf(active) {
  const sec = Number(active?.lease?.durationSec);
  return Number.isFinite(sec) && sec > 0 ? sec * 1000 : 0;
}

/**
 * Thresholds handed to the verdict: the idle bound is raised by a declared hold (a long hold is a
 * declared intention to keep the stack while not calling the dev tools).
 */
function verdictThresholds({ active, defaults, env = process.env }) {
  const p = presenceThresholds(env);
  return {
    abandonedAfterMs: defaults.abandonedAfterMs,
    idleAfterMs: Math.max(defaults.idleAfterMs ?? p.idleAfterMs, declaredHoldMsOf(active)),
  };
}

/**
 * Project an owner-keyed holder into the label-keyed shape the pure verdict compares, so the
 * verdict's self check (`holder.agentSessionId === callerSessionId`) becomes a key comparison
 * without touching the verdict. A holder with no owner block (every pre-change record) projects
 * to no id at all: an unknown owner, never "self".
 */
function projectActiveForVerdict(active) {
  if (!active?.holder) return active;
  return { ...active, holder: { ...active.holder, agentSessionId: active.holder.owner?.key ?? null } };
}

/** Restore the real holder on a verdict's victim (the projection is internal to the decision). */
function restoreVictim(decision, active) {
  if (decision?.victim) return { ...decision, victim: { ...decision.victim, holder: active?.holder ?? null } };
  return decision;
}

function holderOwner(active) {
  return active?.holder?.owner ?? null;
}

function isSelf(active, callerIdentity) {
  const hk = holderOwner(active)?.key;
  const ck = callerIdentity?.owner?.key;
  return !!(hk && ck && hk === ck);
}

/**
 * Supervisor tick decision. Pure.
 * @param {object} p
 * @param {'alive'|'ended'|'unknown'} p.liveness
 * @param {string|null} p.lastTouchAt  ISO time of the owner's last dev-stack touch
 * @param {number|null} p.endedSinceMs when the owner was first observed ended
 * @param {number} p.declaredHoldMs
 * @param {number} p.now
 * @param {object} p.thresholds presenceThresholds()
 * @returns {{ action:'renew'|'pause'|'reap', reason:string, disposition?:string }}
 */
function decideSupervisorPresence({ liveness, lastTouchAt = null, endedSinceMs = null, declaredHoldMs = 0, now = Date.now(), thresholds = presenceThresholds() }) {
  if (liveness === 'ended') {
    const since = Number.isFinite(endedSinceMs) ? endedSinceMs : now;
    const limit = Math.max(thresholds.graceMs, declaredHoldMs);
    if (now - since > limit) {
      return { action: 'reap', disposition: 'reaped_abandoned', reason: `owner ended ${Math.round((now - since) / 1000)}s ago (limit ${Math.round(limit / 1000)}s)` };
    }
    return { action: 'pause', reason: 'owner ended; renewal paused until the grace period passes' };
  }
  if (liveness === 'alive') {
    const t = lastTouchAt ? new Date(lastTouchAt).getTime() : NaN;
    if (!Number.isFinite(t)) return { action: 'renew', reason: 'owner running; no touch record (unknown use)' };
    const limit = thresholds.idleShutdownMs + declaredHoldMs;
    if (now - t > limit) {
      return { action: 'reap', disposition: 'reaped_idle', reason: `owner running but no dev-stack use for ${Math.round((now - t) / 1000)}s (limit ${Math.round(limit / 1000)}s)` };
    }
    return { action: 'renew', reason: 'owner running' };
  }
  return { action: 'renew', reason: 'owner unknown; never reaped on that basis' };
}

/**
 * Remove touch records of ended owners older than the retention bound. A record whose owner is
 * alive or unknown, or whose key is in `keep` (e.g. a worktree's owner), is never removed.
 * `table` may be a function, called at most once and only when some record is old enough to judge.
 * @returns {string[]} removed keys
 */
function pruneOwnerRecords(stateRoot, { table, now = Date.now(), keep = new Set(), env = process.env } = {}) {
  const removed = [];
  const { touchRetentionMs } = presenceThresholds(env);
  let entries = [];
  try { entries = fs.readdirSync(ownersDir(stateRoot)); } catch { return removed; }
  for (const name of entries) {
    if (!name.endsWith('.json')) continue;
    const key = name.slice(0, -'.json'.length);
    if (!isSafeKey(key) || keep.has(key)) continue;
    const rec = readOwnerRecord(stateRoot, key);
    if (!rec) continue;
    const touched = new Date(rec.lastDevStackTouchAt ?? 0).getTime();
    if (Number.isFinite(touched) && now - touched < touchRetentionMs) continue;
    if (typeof table === 'function') table = table();
    const live = ownerLiveness({ key, pid: rec.pid, creationTime: rec.creationTime }, { table, now });
    if (live.state !== 'ended') continue;
    try {
      fs.rmSync(path.join(ownersDir(stateRoot), name), { force: true });
      removed.push(key);
    } catch { /* best effort */ }
  }
  return removed;
}

/**
 * Does deciding about this holder need process evidence? Only when it has a process identity and
 * the caller is not the holder itself.
 */
function holderNeedsTable(active, callerIdentity) {
  const o = holderOwner(active);
  return !!(o && Number.isInteger(Number(o.pid)) && Number(o.pid) > 0 && o.creationTime && !isSelf(active, callerIdentity));
}

/**
 * The ownership verdict for owner-keyed records: gathers presence and delegates to the unchanged
 * pure `computeOwnershipVerdict`. `table` is a readProcessTable-shaped result (or null = no
 * evidence, which reads as unknown and therefore active-like).
 */
function computeOwnerVerdict({
  active, callerIdentity = null, selfCheck = true, supervisorAlive = false, leaseExpired = true,
  stateRoot, table = null, liveness: givenLiveness = null, opLeases = null, takeover = 'deny',
  confirmInterrupt = null, provenance = null, now = Date.now(), env = process.env,
}) {
  // Required lazily: ownership-verdict reads its env thresholds at load time.
  const { computeOwnershipVerdict, DEFAULT_THRESHOLDS } = require('./ownership-verdict.cjs');
  const owner = holderOwner(active);
  const liveness = givenLiveness
    ?? (owner ? ownerLiveness(owner, { table, now }) : { state: 'unknown', reason: 'no owner recorded' });
  const touch = owner?.key && stateRoot ? readOwnerRecord(stateRoot, owner.key) : null;
  const ownerActivity = presenceToOwnerActivity(liveness, touch, now);
  const decision = computeOwnershipVerdict({
    active: projectActiveForVerdict(active),
    callerSessionId: callerIdentity?.owner?.key ?? null,
    selfCheck,
    supervisorAlive,
    leaseExpired,
    ownerActivity,
    opLeases,
    takeover,
    confirmInterrupt,
    provenance,
    now,
    thresholds: verdictThresholds({ active, defaults: DEFAULT_THRESHOLDS, env }),
  });
  return { decision: restoreVictim(decision, active), liveness, touch };
}

/** "Your stack was taken over" notice for a caller whose recorded ownedEpoch is behind. */
function displacedNoticeFor({ stateRoot, active, callerIdentity }) {
  const { computeDisplacedNotice } = require('./ownership-verdict.cjs');
  const callerKey = callerIdentity?.owner?.key;
  if (!callerKey || !stateRoot || !active) return null;
  const rec = readOwnerRecord(stateRoot, callerKey);
  const holderKey = holderOwner(active)?.key ?? null;
  const holderName = active.holder?.agentSessionId || holderKey || 'an unknown owner';
  // The pure notice compares holder and caller; compare keys, then name the holder readably.
  const notice = computeDisplacedNotice(rec?.ownedEpoch, active.ownershipEpoch, holderKey ?? 'unknown-owner', callerKey);
  return notice ? notice.replace(/now held by .*\.$/, `now held by ${holderName}.`) : null;
}

module.exports = {
  holderNeedsTable,
  computeOwnerVerdict,
  displacedNoticeFor,
  OWNERS_DIRNAME,
  TOUCH_SCHEMA,
  presenceThresholds,
  ownersDir,
  touchPath,
  readOwnerRecord,
  touchOwner,
  mergeOwnerRecord,
  ownerBlockFor,
  parseOwnerKey,
  ownerLiveness,
  ownerPidPresence,
  presenceToOwnerActivity,
  declaredHoldMsOf,
  verdictThresholds,
  projectActiveForVerdict,
  restoreVictim,
  holderOwner,
  isSelf,
  decideSupervisorPresence,
  pruneOwnerRecords,
};
