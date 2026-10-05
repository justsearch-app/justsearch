#!/usr/bin/env node
/**
 * Tempdoc 861 W2 — the `agent-spawns/` scope: record grammar, lease, and retention.
 *
 * The SECOND scope over W1's one shared grammar (861 §6.1: "one grammar, two scopes"). Everything
 * structural — directory resolution, the bounded symlink-refusing reader, the atomic write, the
 * `live | unreachable | stale` derivation — is reused from `process-record.cjs`'s generic layer,
 * never re-derived. What lives HERE is only what is specific to this scope: its own schema
 * version, its own record shape, its own validator, its lease, and its retention policy.
 *
 * What this scope means: a helper process an AGENT session spawned — a ui-shot auto-serve, a
 * worktree Vite, the OTel sink — as opposed to `foreign/`, which means a JustSearch BACKEND started
 * outside the dev-runner. Two meanings, two directories, two validators. Widening either until it
 * covered the other is the fork this separation exists to prevent.
 *
 * Three invariants a reader should not have to infer:
 *
 *  - **[A8] The schema version is this scope's own.** `AGENT_SPAWN_RECORD_SCHEMA_VERSION` is
 *    independent of `FOREIGN_RECORD_SCHEMA_VERSION`; they are both `1` today by coincidence, not by
 *    coupling. Two scopes sharing one grammar must not share one version number, or every change to
 *    either forces a lockstep bump on the other's working producer.
 *
 *  - **[A9] The scope honours `JUSTSEARCH_DEV_RUNNER_STATE_ROOT`**, through W1's ONE generic
 *    resolver. A scope that hardcoded the default root would read the wrong directory under an
 *    isolated dev-runner and report a confident empty — the third occurrence of that bug shape in
 *    861's own evidence.
 *
 *  - **Reading never deletes; pruning is an explicit call.** `readAgentSpawnRegister` reports a
 *    stale or failed-verify record, it never removes one (844 §12.2, inherited by 861 §6.1).
 *    `pruneAgentSpawnRecords` is the separate maintenance write path, and it is what stops [A5]'s
 *    deliberate retention of failed-verify records from becoming unbounded growth ([A10]).
 *
 * Nothing in this module kills or signals a process. It writes, reads, and deletes RECORD FILES.
 */
'use strict';

const fsp = require('node:fs/promises');
const path = require('node:path');

const {
  resolveRegisterDir,
  readRegister,
  writeRecordAtomic,
} = require('./process-record.cjs');
const { normalizeCreationTime } = require('./process-identity.cjs');

/* ── Scope constants ───────────────────────────────────────────────────────────────────────── */

/**
 * [A8] THIS SCOPE'S OWN version constant. Never `FOREIGN_RECORD_SCHEMA_VERSION`, never a shared
 * one. Bump this when the agent-spawns record shape changes; `foreign/` is unaffected by
 * construction.
 */
const AGENT_SPAWN_RECORD_SCHEMA_VERSION = 1;

/**
 * A record whose `owner` block was written by the one identity rule (agent-identity.cjs) carries
 * `ownerIdentityVersion: 1`. Only such a record's owner is trusted: for "own" (SAME_SESSION) and
 * for reading its owner's activity. A record without it (every pre-change record, whatever label it
 * carries) is an unknown owner's.
 */
const OWNER_IDENTITY_VERSION = 1;
const OWNER_KEY_RE = /^[A-Za-z0-9._-]{4,160}$/;

/** Does this record carry a trusted owner identity? Pure. */
function hasTrustedOwner(record) {
  return record?.ownerIdentityVersion === OWNER_IDENTITY_VERSION
    && typeof record?.owner?.key === 'string'
    && OWNER_KEY_RE.test(record.owner.key);
}

/** Directory name under the dev-runner state root — a SIBLING of `foreign/`, never inside it. */
const AGENT_SPAWNS_REGISTER_DIRNAME = 'agent-spawns';

/**
 * Where the register sits relative to the main checkout, for messages that name a file path.
 *
 * Beside the dev-runner's own state, never inside it — the same placement 844 chose for `foreign/`
 * and for the same reason: `dev-runner.cjs` enumerates only `runs/` and reads its other state by
 * exact name, so a sibling directory cannot be mistaken for one of its runs.
 */
const AGENT_SPAWNS_REGISTER_RELPOSIX = 'tmp/dev-runner/agent-spawns';

/**
 * The ownership dimension (861 §6.2, [A5]/[A6]).
 *
 *   `session-owned`        — the ordinary case: an agent session started it and owns it.
 *   `ownerless-singleton`  — a daemon whose whole purpose is outliving every session (the OTel
 *                            sink). DECLARED, not exempted: an exemption is invisible and rots,
 *                            a declared mode is legible to every reader, and the §6.3 matrix reads
 *                            it as "never reap, report only".
 */
const OWNERSHIP_MODES = Object.freeze({
  SESSION_OWNED: 'session-owned',
  OWNERLESS_SINGLETON: 'ownerless-singleton',
});
const OWNERSHIP_VALUES = Object.freeze(Object.values(OWNERSHIP_MODES));

/**
 * Probe kinds a record may declare. 861 §6.1: the record declares its own liveness probe rather
 * than the reader hardcoding endpoints. Today only `port` exists, because today every producer has
 * a port; a portless kind is additive when a portless producer actually arrives, and an UNKNOWN
 * kind is reported unreadable rather than guessed at.
 */
const PROBE_KINDS = Object.freeze(['port']);

/** Default retention for [A10]. A record older than this with no live lease is garbage. */
const DEFAULT_MAX_RECORD_AGE_MS = 7 * 24 * 60 * 60 * 1000;

/** Bounds mirror the foreign scope's: readers of this register must stay cheap. */
const AGENT_SPAWNS_MAX_RECORDS = 64;
const AGENT_SPAWNS_MAX_BYTES = 16_000;

/* ── Directory + record-file resolution ────────────────────────────────────────────────────── */

/** [A9] The generic resolver, scoped. The override is honoured here because it is honoured there. */
function resolveAgentSpawnsRegisterDir(mainRepoRoot, env = process.env) {
  return resolveRegisterDir(mainRepoRoot, AGENT_SPAWNS_REGISTER_DIRNAME, env);
}

/**
 * A record id is a file name. Anything that could escape the register directory is refused loudly
 * rather than sanitized quietly — a silently-rewritten id would make a record unfindable by the
 * producer that wrote it.
 */
function assertSafeRecordId(recordId) {
  if (typeof recordId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(recordId) || recordId.includes('..')) {
    throw new Error(`unsafe recordId ${JSON.stringify(recordId)}: expected [A-Za-z0-9][A-Za-z0-9._-]{0,127} with no '..'`);
  }
  return recordId;
}

function agentSpawnRecordPath(dir, recordId) {
  return path.join(dir, `${assertSafeRecordId(recordId)}.json`);
}

/* ── Validation — per scope, injected into the shared reader ([A7]) ────────────────────────── */

function isNonEmptyString(v) {
  return typeof v === 'string' && v.trim().length > 0;
}

function validateLease(lease) {
  if (lease === undefined || lease === null) return { ok: true };
  if (typeof lease !== 'object') return { ok: false, reason: 'lease must be an object' };
  if (!Number.isFinite(lease.durationSec) || lease.durationSec <= 0) {
    return { ok: false, reason: `lease.durationSec must be a positive number (${JSON.stringify(lease.durationSec)})` };
  }
  for (const field of ['renewedAt', 'expiresAt']) {
    if (!isNonEmptyString(lease[field]) || !Number.isFinite(new Date(lease[field]).getTime())) {
      return { ok: false, reason: `lease.${field} must be an ISO timestamp (${JSON.stringify(lease[field])})` };
    }
  }
  return { ok: true };
}

/**
 * [A7]/[A8] — the agent-spawns scope's OWN validator, injected into the shared reader. The generic
 * envelope knows only "is this readable JSON under the size/symlink bounds"; that a record must
 * declare THIS scope's schema version, an identity triple, an ownership mode, and a probe it
 * understands is knowledge that lives here.
 *
 * This is the half of the two-way scope-distinctness proof that faces this direction: a
 * `foreign/`-shaped record dropped in here fails (no identity triple, no ownership mode) and comes
 * back `unreadable`, exactly as an agent-spawn-shaped record dropped into `foreign/` fails
 * `validateForeignRecord`. Neither scope silently accepts the other's records.
 */
function validateAgentSpawnRecord(record) {
  if (record?.schemaVersion !== AGENT_SPAWN_RECORD_SCHEMA_VERSION) {
    return {
      ok: false,
      reason: `unknown agent-spawn schemaVersion ${JSON.stringify(record?.schemaVersion)} (this reader understands ${AGENT_SPAWN_RECORD_SCHEMA_VERSION})`,
    };
  }
  if (!isNonEmptyString(record.producer)) {
    return { ok: false, reason: `record declares no producer (${JSON.stringify(record.producer)})` };
  }
  if (!Number.isInteger(record.pid) || record.pid <= 0) {
    return { ok: false, reason: `record declares no usable pid (${JSON.stringify(record.pid)})` };
  }
  // The identity triple's other two terms. A record missing either can never be verified, so a
  // kill path could never act on it — accepting it would put a permanently unactionable record in
  // a register whose product is authority.
  if (normalizeCreationTime(record.creationFileTimeUtc) === null) {
    return {
      ok: false,
      reason: `record declares no readable creationFileTimeUtc (${JSON.stringify(record.creationFileTimeUtc)}); pid alone cannot survive pid reuse`,
    };
  }
  if (!isNonEmptyString(record.cmdlineFingerprint)) {
    return { ok: false, reason: `record declares no cmdlineFingerprint (${JSON.stringify(record.cmdlineFingerprint)})` };
  }
  if (!OWNERSHIP_VALUES.includes(record.ownership)) {
    return { ok: false, reason: `record declares unknown ownership ${JSON.stringify(record.ownership)} (expected one of ${OWNERSHIP_VALUES.join(', ')})` };
  }
  const probe = record.probe;
  if (!probe || !PROBE_KINDS.includes(probe.kind)) {
    return { ok: false, reason: `record declares unknown probe kind ${JSON.stringify(probe?.kind)} (this reader understands ${PROBE_KINDS.join(', ')})` };
  }
  if (probe.kind === 'port' && (!Number.isInteger(probe.port) || probe.port <= 0 || probe.port > 65535)) {
    return { ok: false, reason: `port probe declares no usable port (${JSON.stringify(probe?.port)}); expected an integer in 1..65535` };
  }
  // The owner identity, when declared, must be complete: a version without a usable owner key (or
  // the reverse) is a half-written claim, and a half-written ownership claim must not be trusted.
  if (record.ownerIdentityVersion !== undefined || record.owner !== undefined) {
    if (record.ownerIdentityVersion !== OWNER_IDENTITY_VERSION) {
      return { ok: false, reason: `unknown ownerIdentityVersion ${JSON.stringify(record.ownerIdentityVersion)} (this reader understands ${OWNER_IDENTITY_VERSION})` };
    }
    if (!hasTrustedOwner(record)) {
      return { ok: false, reason: `ownerIdentityVersion ${OWNER_IDENTITY_VERSION} declares no usable owner key (${JSON.stringify(record.owner?.key)})` };
    }
  }
  const lease = validateLease(record.lease);
  if (!lease.ok) return lease;
  return { ok: true };
}

/** This scope's reader: the shared bounded reader, with this scope's validator injected. */
async function readAgentSpawnRegister({
  dir,
  maxRecords = AGENT_SPAWNS_MAX_RECORDS,
  maxBytes = AGENT_SPAWNS_MAX_BYTES,
  readdir = undefined,
} = {}) {
  return readRegister({
    dir,
    maxRecords,
    maxBytes,
    ...(readdir ? { readdir } : {}),
    validateRecord: validateAgentSpawnRecord,
  });
}

/* ── Building, writing, and the lease ──────────────────────────────────────────────────────── */

function makeLease({ durationSec, now = Date.now() }) {
  const renewedAt = new Date(now).toISOString();
  return {
    durationSec,
    renewedAt,
    expiresAt: new Date(now + durationSec * 1000).toISOString(),
  };
}

/**
 * Build a well-formed record, or throw. Producers fail fast at the write site rather than leaving
 * an invalid record for a reader to report as `unreadable` hours later.
 *
 * @param {object} args
 * @param {string} args.recordId              file-name-safe id, one per process.
 * @param {string} args.producer              which helper wrote this (`ui-shot`, `serve-worktree-fe`, …).
 * @param {number} args.pid                   the pid of the process that actually holds the resources.
 * @param {string} args.creationFileTimeUtc   `.ToFileTimeUtc()` as a decimal string (see process-identity.cjs).
 * @param {string} args.cmdlineFingerprint    a substring of the process's command line; safe ONLY inside the identity conjunction.
 * @param {number} args.port                  the port the process listens on (the only probe kind today).
 * @param {number} args.leaseDurationSec      lease-on-use TTL; refreshed on start AND on reuse.
 * @param {string} [args.ownership]           defaults to `session-owned`.
 * @param {string} [args.sessionId]         the owning session's readable label (agent-identity.cjs).
 * @param {object} [args.owner]             `{ harness, pid, creationTime, key }` - the owning
 *   session's harness process; ownership and liveness are judged by it. A record without one is an
 *   unknown owner's (never "own", never reaped for its owner's absence).
 * @param {string} [args.repoRoot]
 * @param {object} [args.resourceRoots]       `{ worktreeRoot, nodeModulesRealPath }` — resolved
 *   through junctions HERE, so a producer that hands over the junction side still gets a record
 *   that matches. ASYNC for that reason.
 * @param {number} [args.now]
 */
async function buildAgentSpawnRecord({
  recordId,
  producer,
  pid,
  creationFileTimeUtc,
  cmdlineFingerprint,
  port,
  leaseDurationSec,
  ownership = OWNERSHIP_MODES.SESSION_OWNED,
  sessionId = null,
  owner = null,
  repoRoot = null,
  resourceRoots = null,
  now = Date.now(),
} = {}) {
  assertSafeRecordId(recordId);
  const record = {
    schemaVersion: AGENT_SPAWN_RECORD_SCHEMA_VERSION,
    recordId,
    producer,
    pid,
    // Normalized at construction so the register never holds two spellings of the same instant.
    creationFileTimeUtc: normalizeCreationTime(creationFileTimeUtc),
    cmdlineFingerprint,
    ownership,
    probe: { kind: 'port', port },
    startedAt: new Date(now).toISOString(),
    lease: makeLease({ durationSec: leaseDurationSec, now }),
    ...(sessionId ? { sessionId } : {}),
    ...(owner?.key ? { ownerIdentityVersion: OWNER_IDENTITY_VERSION, owner } : {}),
    ...(repoRoot ? { repoRoot } : {}),
    ...(resourceRoots ? { resourceRoots: await normalizeResourceRoots(resourceRoots) } : {}),
  };
  const verdict = validateAgentSpawnRecord(record);
  if (!verdict.ok) throw new Error(`refusing to build an invalid agent-spawn record: ${verdict.reason}`);
  return record;
}

/** Write (or replace) one record, validated, atomically. */
async function writeAgentSpawnRecord({ dir, record }) {
  const verdict = validateAgentSpawnRecord(record);
  if (!verdict.ok) throw new Error(`refusing to write an invalid agent-spawn record: ${verdict.reason}`);
  const file = agentSpawnRecordPath(dir, record.recordId);
  await writeRecordAtomic(file, record);
  return file;
}

/** Retire one record — a producer's clean-exit path. Deletes a FILE; kills nothing. */
async function removeAgentSpawnRecord({ dir, recordId }) {
  const file = agentSpawnRecordPath(dir, recordId);
  try {
    const st = await fsp.lstat(file);
    if (st.isSymbolicLink()) return { removed: false, reason: 'record is a symlink; refusing to delete through it' };
  } catch (err) {
    if (err?.code === 'ENOENT') return { removed: false, reason: 'no such record' };
    throw err;
  }
  await fsp.rm(file, { force: true });
  return { removed: true, file };
}

/**
 * Lease-on-use: refresh an existing record's lease without rewriting anything else. Called by a
 * producer both when it STARTS a process and when it REUSES one — an actively used server keeps
 * extending its own claim, an abandoned one lapses, and no supervisor process is involved.
 */
async function renewAgentSpawnLease({ dir, recordId, durationSec, now = Date.now() }) {
  const file = agentSpawnRecordPath(dir, recordId);
  let record;
  try {
    record = JSON.parse(await fsp.readFile(file, 'utf8'));
  } catch (err) {
    if (err?.code === 'ENOENT') return { renewed: false, reason: 'no such record' };
    return { renewed: false, reason: String(err?.message || err).slice(0, 200) };
  }
  const effective = Number.isFinite(durationSec) && durationSec > 0 ? durationSec : record?.lease?.durationSec;
  if (!Number.isFinite(effective) || effective <= 0) {
    return { renewed: false, reason: 'no usable lease duration on the record or in the call' };
  }
  const next = { ...record, lease: makeLease({ durationSec: effective, now }) };
  try {
    await writeAgentSpawnRecord({ dir, record: next });
  } catch (err) {
    // One failure shape for the whole function. Without this, a record that is present but invalid
    // THROWS while a record that is absent RETURNS — so a caller written against the returned shape
    // would crash on precisely the malformed record it most needs to report.
    return { renewed: false, reason: String(err?.message || err).slice(0, 200) };
  }
  return { renewed: true, record: next };
}

/**
 * [A5] The failed-verify marker. When identity verification REFUSES, the §6.3 matrix says: refuse
 * to act, and RETAIN the record with a marker, so the diagnostic trail survives the refusal. The
 * record is NOT deleted — only age removes it, via `pruneAgentSpawnRecords` ([A10]), which is what
 * keeps retention from becoming unbounded growth.
 */
async function markAgentSpawnRecordFailedVerify({ dir, recordId, verdict, reason, now = Date.now() }) {
  const file = agentSpawnRecordPath(dir, recordId);
  let record;
  try {
    record = JSON.parse(await fsp.readFile(file, 'utf8'));
  } catch (err) {
    if (err?.code === 'ENOENT') return { marked: false, reason: 'no such record' };
    return { marked: false, reason: String(err?.message || err).slice(0, 200) };
  }
  const next = {
    ...record,
    identityVerify: { verdict, reason, at: new Date(now).toISOString() },
  };
  // Deliberately the raw atomic write, not `writeAgentSpawnRecord`: a record whose shape is ALREADY
  // odd is exactly the record most worth marking, and re-validating here would throw the marker
  // away for the case it exists to document.
  await writeRecordAtomic(file, next);
  return { marked: true, record: next };
}

/* ── Lease state and the resource-root lookup ──────────────────────────────────────────────── */

/**
 * Tri-state, deliberately: `unknown` (no parseable lease) is NOT `lapsed`. A record whose lease
 * cannot be read is a record about which nothing is known, and the §6.3 matrix's lapsed row is a
 * reap row — so collapsing unknown into lapsed would license a kill on absent evidence, which is
 * the same defect `classifyActivity`'s `known:false` exists to prevent.
 */
function leaseState(record, now = Date.now()) {
  const expiresAt = record?.lease?.expiresAt;
  if (!isNonEmptyString(expiresAt)) return 'unknown';
  const t = new Date(expiresAt).getTime();
  if (!Number.isFinite(t)) return 'unknown';
  return t > now ? 'live' : 'lapsed';
}

function normalizePathForCompare(p) {
  if (!isNonEmptyString(p)) return null;
  const resolved = path.resolve(p).replace(/\\/g, '/').replace(/\/+$/, '');
  // Windows paths are case-insensitive; a holder lookup that missed on case would be a lookup that
  // silently found nothing, which is the failure mode this field exists to remove.
  return process.platform === 'win32' ? resolved.toLowerCase() : resolved;
}

/**
 * Resolve `p` through junctions/symlinks, tolerating a path that does not exist yet.
 *
 * A lexical comparison is not enough for this field's whole purpose: the paths a build error names
 * are usually the JUNCTION side (a worktree's `node_modules/...`), while the record stores the
 * junction's target, so a lexical match returns false for the very file whose lock is being
 * diagnosed. And the query path frequently does NOT exist — "what will hold this path once the
 * build writes it" is a legitimate question — so this walks up to the deepest existing ancestor,
 * resolves THAT, and re-appends the remainder. A path with no existing ancestor at all falls back
 * to its lexical form, which is the most that can be said about it.
 */
async function realpathNearest(p) {
  const abs = path.resolve(p);
  let head = abs;
  const tail = [];
  for (;;) {
    try {
      const real = await fsp.realpath(head);
      return tail.length > 0 ? path.join(real, ...tail) : real;
    } catch {
      const parent = path.dirname(head);
      if (parent === head) return abs;
      tail.unshift(path.basename(head));
      head = parent;
    }
  }
}

/**
 * Resolve the roots a producer declares, at normalize time. A producer that passes the junction
 * side rather than its target is corrected HERE rather than writing a record that silently never
 * matches anything — the mirror defect of the lexical lookup, and just as invisible.
 */
async function normalizeResourceRoots(roots) {
  const out = {};
  for (const key of ['worktreeRoot', 'nodeModulesRealPath']) {
    if (isNonEmptyString(roots?.[key])) out[key] = await realpathNearest(roots[key]);
  }
  return out;
}

/**
 * The junction-lock lookup, and the reason `resourceRoots` is recorded at all (861 §6.2).
 *
 * A Vite serving a WORKTREE holds the MAIN checkout's `node_modules`, because the worktree's copy
 * is a junction into it. Recording the RESOLVED target turns "what is locking
 * `lightningcss-win32-x64-msvc/*.node`" from a process-table hunt into a lookup — and it is the
 * only way a path-based holder scan can find a main-checkout holder spawned by a worktree session.
 *
 * ASYNC, and deliberately so: BOTH sides are resolved through junctions before comparison. The
 * query side because callers hand it whatever path a build error printed (usually the junction);
 * the record side because a record may predate `normalizeResourceRoots` resolving its roots, and a
 * lookup that answers "no" for a stale record is the same silent miss in a different place.
 *
 * True when `absPath` is at or under any root this record declares.
 */
async function recordHoldsPath(record, absPath) {
  if (!isNonEmptyString(absPath)) return false;
  const target = normalizePathForCompare(await realpathNearest(absPath));
  if (!target) return false;
  const roots = record?.resourceRoots || {};
  for (const key of ['worktreeRoot', 'nodeModulesRealPath']) {
    if (!isNonEmptyString(roots[key])) continue;
    const root = normalizePathForCompare(await realpathNearest(roots[key]));
    if (!root) continue;
    if (target === root || target.startsWith(`${root}/`)) return true;
  }
  return false;
}

/**
 * Resolve `<root>/node_modules` through any junction, for a producer to record. Returns `null`
 * when it does not exist — an absent directory is reported as absent, not guessed at.
 */
async function resolveNodeModulesRealPath(root) {
  if (!isNonEmptyString(root)) return null;
  try {
    return await fsp.realpath(path.join(root, 'node_modules'));
  } catch {
    return null;
  }
}

/* ── [A10] Retention ───────────────────────────────────────────────────────────────────────── */

/**
 * [A10] The explicit maintenance write path. READING NEVER DELETES; this is the separate call that
 * does, and it exists because `foreign/` has no GC at all and [A5]'s failed-verify retention
 * deliberately adds records nothing else removes. Templates: `pruneAgentEvidence`
 * (`scripts/dev/justsearch-dev-mcp/files.mjs:147-200`) and `pruneHistoricRuns`
 * (`scripts/dev/dev-runner.cjs:378-424`).
 *
 * A record is deleted only when BOTH conditions hold:
 *   1. it is older than `maxAgeMs` (by `startedAt`, falling back to the file's mtime), and
 *   2. its lease is not `live`.
 *
 * The lease condition is not decoration: an `ownerless-singleton` (the OTel sink) legitimately
 * outlives any age threshold while renewing its claim, and deleting its record would silently
 * demote it to the observed tier, where the sweep prints a ready-to-run kill line beside a daemon
 * that is supposed to be there. A `lease: 'unknown'` record is prunable by age — it carries no
 * claim to honour. A failed-verify record is likewise pruned by AGE ALONE (its lease lapses like
 * any other's); the marker buys diagnostic time, not immortality.
 *
 * Also sweeps orphaned `*.tmp` files past the same age. `writeRecordAtomic` leaves one behind only
 * when a producer dies mid-write, and nothing else in this scope ever looks at them — an unswept
 * temp file is invisible to every reader here, which makes it exactly the kind of residue that
 * accumulates unnoticed.
 *
 * Never deletes through a symlink, never touches any other file, never signals a process.
 */
async function pruneAgentSpawnRecords({
  dir,
  maxAgeMs = DEFAULT_MAX_RECORD_AGE_MS,
  now = Date.now(),
  dryRun = false,
} = {}) {
  const empty = { found: 0, deleted: 0, retained: 0, deletedIds: [], deletedTmp: 0 };
  if (!dir) return empty;
  let names;
  let tmpNames;
  try {
    const entries = await fsp.readdir(dir, { withFileTypes: true });
    const files = entries.filter((e) => e.isFile());
    names = files.filter((e) => e.name.endsWith('.json')).map((e) => e.name).sort();
    tmpNames = files.filter((e) => e.name.endsWith('.tmp')).map((e) => e.name).sort();
  } catch (err) {
    if (err?.code === 'ENOENT' || err?.code === 'ENOTDIR') return empty;
    throw err;
  }

  const warnings = [];
  const deletedIds = [];
  let retained = 0;

  for (const name of names) {
    const recordId = name.replace(/\.json$/, '');
    const abs = path.join(dir, name);
    try {
      const st = await fsp.lstat(abs);
      if (st.isSymbolicLink()) {
        retained += 1;
        warnings.push(`${recordId}: record is a symlink; refusing to delete through it`);
        continue;
      }
      let record = null;
      try {
        record = JSON.parse(await fsp.readFile(abs, 'utf8'));
      } catch {
        // An unreadable record has no lease to honour, so age alone decides. Reported either way.
        record = null;
      }
      const startedAtMs = record?.startedAt ? new Date(record.startedAt).getTime() : NaN;
      const ageBasis = Number.isFinite(startedAtMs) ? startedAtMs : st.mtimeMs;
      const tooOld = now - ageBasis > maxAgeMs;
      const claimLive = record ? leaseState(record, now) === 'live' : false;
      if (!tooOld || claimLive) {
        retained += 1;
        continue;
      }
      if (!dryRun) await fsp.rm(abs, { force: true });
      deletedIds.push(recordId);
    } catch (err) {
      retained += 1;
      warnings.push(`${recordId}: ${String(err?.message || err).slice(0, 200)}`);
    }
  }

  let deletedTmp = 0;
  for (const name of tmpNames) {
    const abs = path.join(dir, name);
    try {
      const st = await fsp.lstat(abs);
      if (st.isSymbolicLink()) {
        warnings.push(`${name}: temp file is a symlink; refusing to delete through it`);
        continue;
      }
      if (now - st.mtimeMs <= maxAgeMs) continue;
      if (!dryRun) await fsp.rm(abs, { force: true });
      deletedTmp += 1;
    } catch (err) {
      warnings.push(`${name}: ${String(err?.message || err).slice(0, 200)}`);
    }
  }

  return {
    found: names.length,
    deleted: deletedIds.length,
    retained,
    deletedIds,
    deletedTmp,
    ...(dryRun ? { dryRun: true } : {}),
    ...(warnings.length > 0 ? { warnings } : {}),
  };
}

module.exports = {
  AGENT_SPAWN_RECORD_SCHEMA_VERSION,
  OWNER_IDENTITY_VERSION,
  hasTrustedOwner,
  AGENT_SPAWNS_REGISTER_DIRNAME,
  AGENT_SPAWNS_REGISTER_RELPOSIX,
  AGENT_SPAWNS_MAX_RECORDS,
  AGENT_SPAWNS_MAX_BYTES,
  OWNERSHIP_MODES,
  PROBE_KINDS,
  DEFAULT_MAX_RECORD_AGE_MS,
  resolveAgentSpawnsRegisterDir,
  agentSpawnRecordPath,
  assertSafeRecordId,
  validateAgentSpawnRecord,
  readAgentSpawnRegister,
  buildAgentSpawnRecord,
  writeAgentSpawnRecord,
  removeAgentSpawnRecord,
  renewAgentSpawnLease,
  markAgentSpawnRecordFailedVerify,
  leaseState,
  realpathNearest,
  normalizePathForCompare,
  normalizeResourceRoots,
  recordHoldsPath,
  resolveNodeModulesRealPath,
  pruneAgentSpawnRecords,
};
