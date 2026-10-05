'use strict';

/**
 * Agent identity: which agent session is calling, derived from the process tree.
 *
 * The retired session hooks used to write `tmp/agent-telemetry/current-session-id`, one file
 * shared by every session in the checkout. Every resolver that read it handed all sessions the
 * same identity, so two sessions counted as one and a stale ID outlived its session. That file is
 * never read here.
 *
 * Identity now comes from the harness process the caller runs under. Claude Code (`claude.exe`)
 * and Codex (`codex.exe`) are ancestors of every shell command and MCP server they start, and a
 * nested session is a NEARER ancestor than its parent session. So the nearest harness ancestor is
 * the session, and its pid plus creation time is a key no other session can share, including a
 * resumed or relaunched one (a new process, a new key).
 *
 * Resolution order (first hit wins):
 *   1. an explicit override: the CLI `--session-id` / MCP `input.sessionId` (`explicit`), or the
 *      test and CI seam `JUSTSEARCH_AGENT_IDENTITY`. A `claude:` / `codex:` prefix names the
 *      harness. An explicit id equal to the detected session's own label is that session, not a
 *      second owner;
 *   2. a validated hand-off from the dev MCP server to the dev-runner it spawned
 *      (`JUSTSEARCH_AGENT_IDENTITY_HANDOFF`, honoured only by the direct child it was made for);
 *   3. the nearest harness ancestor in the process table. The label is THAT harness's variable:
 *      `CLAUDE_CODE_SESSION_ID` for Claude, `CODEX_THREAD_ID` (or `CODEX_SESSION_ID`) for Codex. A
 *      Codex session started from a Claude shell inherits `CLAUDE_CODE_SESSION_ID`; it is ignored
 *      because the nearest harness is Codex. `CLAUDE_PID` is not trusted on its own (an MCP server
 *      can inherit a stale one);
 *   3b. the broken-chain fallback (`claudePidFallback`). Git Bash tools that fork and then exec
 *      another Git Bash program (`timeout`, `xargs`, `env`, ...) leave a Windows parent pid that
 *      no longer names a process, so the walk cannot reach the harness. Only then, and only when
 *      ALL hold, is `CLAUDE_PID` used: the walk ended at a parent pid that no longer names its
 *      parent (absent, or recycled by a newer process); `CLAUDE_PID` names a live Claude harness
 *      process; that process was created before every ancestor the walk did find; and no Codex
 *      variable (`CODEX_THREAD_ID` / `CODEX_SESSION_ID`) is present (both harnesses' variables
 *      with a broken chain is ambiguous). Its `source` is `claude-pid-fallback`. Codex sets no pid
 *      variable, so a broken Codex chain stays unknown (a documented limitation). The MCP-server
 *      path never reaches this: its chain to the harness is direct, so the walk never breaks;
 *   4. `CI` set: harness `ci`, no owner;
 *   5. otherwise harness `unknown`, no owner. Never a borrowed or stale ID.
 *
 * `JUSTSEARCH_AGENT_SESSION_ID` is not identity: it is only the label the dev-runner hands to the
 * backend so operation leases name their session.
 *
 * Every id is sanitised by one rule (`sanitizeSessionId`) and every key is file-name safe.
 */

const fs = require('node:fs');
const { readProcessTable, readProcessTableAsync, normalizeCreationTime } = require('./process-identity.cjs');

const ENV_OVERRIDE = 'JUSTSEARCH_AGENT_IDENTITY';
const ENV_HANDOFF = 'JUSTSEARCH_AGENT_IDENTITY_HANDOFF';
/**
 * Test seam: a JSON file `{ "selfPid": <pid>, "rows": [Win32_Process-shaped rows] }` (or
 * `{ "ok": false, "reason": "..." }`) that replaces the real process table, for identity AND for
 * liveness. Read fresh on every call, so a test can end an owner by rewriting the file.
 */
const ENV_TABLE_FIXTURE = 'JUSTSEARCH_PROCESS_TABLE_FIXTURE';

const HARNESSES = Object.freeze(['claude', 'codex']);
const SESSION_ID_RE = /^[A-Za-z0-9._-]{4,80}$/;
const WINDOWS_RESERVED_RE = /^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$/i;
const MAX_WALK_DEPTH = 64;

/**
 * The one sanitising rule for session ids and override ids. Same character class as the merge
 * ledger has always used (`[A-Za-z0-9._-]`, at most 80 chars), plus what makes the result safe as
 * a file name everywhere: at least 4 characters, no leading or trailing dot, not a Windows device
 * name. Returns null when nothing usable is left.
 */
function sanitizeSessionId(raw) {
  if (raw === null || raw === undefined) return null;
  let s = String(raw).trim().replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 80);
  s = s.replace(/^\.+/, (m) => '_'.repeat(m.length)).replace(/\.+$/, (m) => '_'.repeat(m.length));
  if (WINDOWS_RESERVED_RE.test(s)) s = `_${s}`.slice(0, 80);
  if (!SESSION_ID_RE.test(s)) return null;
  if (!/[A-Za-z0-9]/.test(s)) return null;
  return s;
}

function isSafeKey(key) {
  return typeof key === 'string' && /^[A-Za-z0-9._-]{4,160}$/.test(key) && !/^\./.test(key) && !/\.$/.test(key);
}

function truthyEnv(v) {
  if (v === undefined || v === null) return false;
  const s = String(v).trim().toLowerCase();
  return s !== '' && s !== '0' && s !== 'false' && s !== 'no';
}

/** Parse `claude:<id>` / `codex:<id>` / `<id>` into `{ harness, sessionId }`, or null. */
function parseOverride(raw) {
  if (raw === null || raw === undefined) return null;
  const text = String(raw).trim();
  // 'unknown' is the repository's "no identity" sentinel (remove-worktree --session-id unknown).
  if (!text || text.toLowerCase() === 'unknown') return null;
  const m = /^(claude|codex)[:](.*)$/i.exec(text);
  const harness = m ? m[1].toLowerCase() : 'unknown';
  const sessionId = sanitizeSessionId(m ? m[2] : text);
  if (!sessionId) return null;
  return { harness, sessionId };
}

function overrideIdentity(parsed, source) {
  return Object.freeze({
    harness: parsed.harness,
    owner: Object.freeze({ pid: null, creationTime: null, key: `override-${parsed.sessionId}`, harness: parsed.harness }),
    sessionId: parsed.sessionId,
    source,
  });
}

function anonymousIdentity(env, detail) {
  const ci = truthyEnv(env.CI);
  return Object.freeze({
    harness: ci ? 'ci' : 'unknown',
    owner: null,
    sessionId: null,
    source: ci ? 'ci' : 'none',
    ...(detail ? { detail } : {}),
  });
}

function labelFor(harness, env) {
  if (harness === 'claude') return sanitizeSessionId(env.CLAUDE_CODE_SESSION_ID);
  if (harness === 'codex') return sanitizeSessionId(env.CODEX_THREAD_ID) || sanitizeSessionId(env.CODEX_SESSION_ID);
  return null;
}

/** Which harness a process-table row is, or null. Exported for the tests. */
function classifyHarnessRow(row) {
  const name = String(row?.Name ?? '').trim().toLowerCase();
  if (name === 'claude' || name === 'claude.exe') return 'claude';
  if (name === 'codex' || name === 'codex.exe') return 'codex';
  if (name === 'node' || name === 'node.exe') {
    const cmd = String(row?.CommandLine ?? '');
    if (/@anthropic-ai[\\/]+claude-code/i.test(cmd)) return 'claude';
    if (/@openai[\\/]+codex/i.test(cmd)) return 'codex';
  }
  return null;
}

/**
 * Walk from `selfPid` up the parent chain to the nearest harness process. Pure.
 * Returns `{ ok:true, found:{harness,pid,creationTime}|null }` or `{ ok:false, reason }`.
 * A parent created after its child is a recycled ppid, not the real parent: the walk stops there.
 */
function findHarnessAncestor(rows, selfPid) {
  if (!Array.isArray(rows) || rows.length === 0) return { ok: false, reason: 'empty process table' };
  const byPid = new Map();
  for (const row of rows) {
    const pid = Number(row?.ProcessId);
    if (Number.isInteger(pid) && pid > 0) byPid.set(pid, row);
  }
  let cur = byPid.get(Number(selfPid));
  if (!cur) return { ok: false, reason: `own pid ${selfPid} is not in the process table` };
  const visited = new Set([Number(selfPid)]);
  // Every process the walk found (self included): the fallback's creation-time floor.
  const chain = [cur];
  for (let depth = 0; depth < MAX_WALK_DEPTH; depth += 1) {
    const ppid = Number(cur.ParentProcessId);
    if (!Number.isInteger(ppid) || ppid <= 0 || visited.has(ppid)) break;
    visited.add(ppid);
    const parent = byPid.get(ppid);
    if (!parent) return { ok: true, found: null, broken: true, chain };
    const childCt = normalizeCreationTime(cur.CreationFileTimeUtc);
    const parentCt = normalizeCreationTime(parent.CreationFileTimeUtc);
    // A parent created after its child is a recycled ppid: the real parent no longer exists.
    if (childCt && parentCt && BigInt(parentCt) > BigInt(childCt)) return { ok: true, found: null, broken: true, chain };
    const harness = classifyHarnessRow(parent);
    if (harness) {
      if (!parentCt) return { ok: false, reason: `harness ancestor pid ${ppid} has no creation time` };
      return { ok: true, found: { harness, pid: ppid, creationTime: parentCt } };
    }
    chain.push(parent);
    cur = parent;
  }
  return { ok: true, found: null, broken: false, chain };
}

/**
 * The broken-chain fallback (resolution step 3b). Pure. Returns a `found` block or null.
 * `walk` is a `findHarnessAncestor` result with `found: null`.
 */
function claudePidFallback(rows, walk, env) {
  if (!walk || !walk.ok || walk.found || !walk.broken) return null;
  if (String(env.CODEX_THREAD_ID ?? '').trim() || String(env.CODEX_SESSION_ID ?? '').trim()) return null;
  const pid = Number(String(env.CLAUDE_PID ?? '').trim());
  if (!Number.isInteger(pid) || pid <= 0) return null;
  const row = (rows || []).find((r) => Number(r?.ProcessId) === pid);
  if (!row || classifyHarnessRow(row) !== 'claude') return null;
  const ct = normalizeCreationTime(row.CreationFileTimeUtc);
  if (!ct) return null;
  const chain = Array.isArray(walk.chain) ? walk.chain : [];
  if (chain.length === 0) return null;
  for (const ancestor of chain) {
    const act = normalizeCreationTime(ancestor?.CreationFileTimeUtc);
    if (!act || !(BigInt(ct) < BigInt(act))) return null;
  }
  return { harness: 'claude', pid, creationTime: ct };
}

function ownerKey(harness, pid, creationTime) {
  return `${harness}-${pid}-${creationTime}`;
}

function detectedIdentity(found, env, source = 'process-ancestry') {
  return Object.freeze({
    harness: found.harness,
    owner: Object.freeze({
      harness: found.harness,
      pid: found.pid,
      creationTime: found.creationTime,
      key: ownerKey(found.harness, found.pid, found.creationTime),
    }),
    sessionId: labelFor(found.harness, env),
    source,
  });
}

/** Validate a stored or handed-off identity shape; returns a frozen copy or null. */
function coerceIdentity(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const harness = ['claude', 'codex', 'ci', 'unknown'].includes(raw.harness) ? raw.harness : null;
  if (!harness) return null;
  const sessionId = raw.sessionId == null ? null : sanitizeSessionId(raw.sessionId);
  let owner = null;
  if (raw.owner && typeof raw.owner === 'object') {
    const key = raw.owner.key;
    if (!isSafeKey(key)) return null;
    const pid = raw.owner.pid == null ? null : Number(raw.owner.pid);
    const creationTime = raw.owner.creationTime == null ? null : normalizeCreationTime(String(raw.owner.creationTime));
    if (pid !== null && !(Number.isInteger(pid) && pid > 0)) return null;
    owner = Object.freeze({ harness: raw.owner.harness ?? harness, pid, creationTime, key });
  }
  return Object.freeze({ harness, owner, sessionId, source: typeof raw.source === 'string' ? raw.source : 'stored' });
}

/** The hand-off value the MCP server sets for the dev-runner child it is about to spawn. */
function buildHandoffEnv(identity, parentPid = process.pid) {
  const value = JSON.stringify({ v: 1, parentPid, identity });
  return { [ENV_HANDOFF]: value };
}

function readHandoff(env, ppid) {
  const raw = env[ENV_HANDOFF];
  if (!raw) return null;
  try {
    const doc = JSON.parse(raw);
    if (doc?.v !== 1 || Number(doc.parentPid) !== Number(ppid)) return null;
    const id = coerceIdentity(doc.identity);
    return id ? Object.freeze({ ...id, source: `handoff:${id.source}` }) : null;
  } catch {
    return null;
  }
}

/** Read the fixture table if the seam is set; returns a readProcessTable-shaped result or null. */
function readFixtureTable(env = process.env, now = Date.now) {
  const file = env[ENV_TABLE_FIXTURE];
  if (!file) return null;
  try {
    const doc = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (doc && doc.ok === false) return { ok: false, reason: doc.reason || 'fixture: table unavailable' };
    const rows = Array.isArray(doc) ? doc : doc?.rows;
    if (!Array.isArray(rows) || rows.length === 0) return { ok: false, reason: 'fixture: no rows' };
    return { ok: true, table: rows, readAt: now(), selfPid: Number.isInteger(doc?.selfPid) ? doc.selfPid : null };
  } catch (err) {
    return { ok: false, reason: `fixture unreadable: ${String(err?.message || err).slice(0, 200)}` };
  }
}

/** Synchronous table read honouring the fixture seam. */
function readTableSync({ env = process.env, platform = process.platform } = {}) {
  return readFixtureTable(env) || readProcessTable({ platform });
}

async function readTableAsync({ env = process.env, platform = process.platform } = {}) {
  return readFixtureTable(env) || readProcessTableAsync({ platform });
}

/**
 * Identity from a base (the non-explicit resolution) plus an optional explicit id. Pure.
 * An explicit id equal to the base's own label is the base session; anything else is an override.
 */
function applyExplicit(base, explicit) {
  const parsed = parseOverride(explicit);
  if (!parsed) return base;
  if (base && base.owner && base.sessionId && base.sessionId === parsed.sessionId
      && (parsed.harness === 'unknown' || parsed.harness === base.harness)) {
    return base;
  }
  return overrideIdentity(parsed, 'override-explicit');
}

/**
 * The non-explicit resolution, given already-gathered inputs. Pure.
 * @param {{ env:object, ppid:number, selfPid:number, table:object|null }} inputs
 */
function resolveBaseIdentity({ env = process.env, ppid = process.ppid, selfPid = process.pid, table = null } = {}) {
  const envOverride = parseOverride(env[ENV_OVERRIDE]);
  if (envOverride) return overrideIdentity(envOverride, 'override-env');
  const handoff = readHandoff(env, ppid);
  if (handoff) return handoff;
  if (table && table.ok) {
    const startPid = Number.isInteger(table.selfPid) ? table.selfPid : selfPid;
    const walk = findHarnessAncestor(table.table, startPid);
    if (walk.ok && walk.found) return detectedIdentity(walk.found, env);
    const fallback = claudePidFallback(table.table, walk, env);
    if (fallback) return detectedIdentity(fallback, env, 'claude-pid-fallback');
    return anonymousIdentity(env, walk.ok ? (walk.broken ? 'process chain broken; no verifiable harness' : null) : walk.reason);
  }
  return anonymousIdentity(env, table ? table.reason : null);
}

function needsTable(env, ppid) {
  return !parseOverride(env[ENV_OVERRIDE]) && !readHandoff(env, ppid);
}

/**
 * An explicit id can only be "the detected session restating its own id" when some harness label
 * (or the hand-off's label) equals it. When none does, the override needs no process walk.
 */
function explicitShortcut(explicit, env, ppid) {
  const parsed = parseOverride(explicit);
  if (!parsed) return null;
  const candidates = [
    sanitizeSessionId(env.CLAUDE_CODE_SESSION_ID),
    sanitizeSessionId(env.CODEX_THREAD_ID),
    sanitizeSessionId(env.CODEX_SESSION_ID),
    readHandoff(env, ppid)?.sessionId ?? null,
  ];
  if (candidates.includes(parsed.sessionId)) return null;
  return overrideIdentity(parsed, 'override-explicit');
}

let cachedBase = null;
let cachedBasePromise = null;

/**
 * Resolve the caller's identity (synchronous; reads the process table at most once per process).
 * Options are for tests: `env`, `readTable`, `selfPid`, `ppid`, `noCache`.
 */
function resolveAgentIdentity({ explicit = null, env = process.env, readTable = null, selfPid = process.pid, ppid = process.ppid, noCache = false } = {}) {
  const shortcut = explicitShortcut(explicit, env, ppid);
  if (shortcut) return shortcut;
  const injected = noCache || readTable !== null || env !== process.env;
  let base = injected ? null : cachedBase;
  if (!base) {
    const table = needsTable(env, ppid) ? (readTable ? readTable() : readTableSync({ env })) : null;
    base = resolveBaseIdentity({ env, ppid, selfPid, table });
    if (!injected) cachedBase = base;
  }
  return applyExplicit(base, explicit);
}

/** Async twin for long-lived servers: never blocks the event loop on the table read. */
async function resolveAgentIdentityAsync({ explicit = null, env = process.env, readTable = null, selfPid = process.pid, ppid = process.ppid, noCache = false } = {}) {
  const shortcut = explicitShortcut(explicit, env, ppid);
  if (shortcut) return shortcut;
  const injected = noCache || readTable !== null || env !== process.env;
  if (injected) {
    const table = needsTable(env, ppid) ? await (readTable ? readTable() : readTableAsync({ env })) : null;
    return applyExplicit(resolveBaseIdentity({ env, ppid, selfPid, table }), explicit);
  }
  if (!cachedBase) {
    if (!cachedBasePromise) {
      cachedBasePromise = (async () => {
        const table = needsTable(env, ppid) ? await readTableAsync({ env }) : null;
        cachedBase = resolveBaseIdentity({ env, ppid, selfPid, table });
        return cachedBase;
      })();
    }
    await cachedBasePromise;
  }
  return applyExplicit(cachedBase, explicit);
}

function resetIdentityCacheForTests() {
  cachedBase = null;
  cachedBasePromise = null;
}

/** Human-readable owner name for messages: the label when there is one, else the key. */
function describeIdentity(identity) {
  if (!identity) return 'unknown';
  const label = identity.sessionId || identity.owner?.key || null;
  return label ? `${label} (${identity.harness})` : identity.harness;
}

module.exports = {
  ENV_OVERRIDE,
  ENV_HANDOFF,
  ENV_TABLE_FIXTURE,
  HARNESSES,
  SESSION_ID_RE,
  sanitizeSessionId,
  isSafeKey,
  parseOverride,
  classifyHarnessRow,
  findHarnessAncestor,
  claudePidFallback,
  ownerKey,
  coerceIdentity,
  buildHandoffEnv,
  readFixtureTable,
  readTableSync,
  readTableAsync,
  applyExplicit,
  resolveBaseIdentity,
  resolveAgentIdentity,
  resolveAgentIdentityAsync,
  resetIdentityCacheForTests,
  describeIdentity,
};
