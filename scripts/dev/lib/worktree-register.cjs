#!/usr/bin/env node
/**
 * Tempdoc 952 P4 — the worktree/branch lifecycle REGISTER: markers, lock hints, finalization
 * records, state derivation, and the census.
 *
 * This module is the reading half of the lifecycle controller (952 §5.2/§5.4). It answers
 * "what worktrees and branches exist, who owns them, and what state is each in" from Git and
 * the session ledger. It does NOT create, release, archive, or delete anything: the CLI
 * (`scripts/dev/worktree-lifecycle.cjs`, P5) and `remove-worktree.cjs` own every destructive
 * step, and `worktree-archive.cjs` (P3) owns preservation.
 *
 * Four sources, deliberately kept distinct because they carry different authority:
 *
 *  1. **Branch config markers** (`branch.<name>.justsearch-*`) — the repository's OWN ownership
 *     record, written at creation. Survives directory loss (so a leftover branch is still a
 *     visible obligation) and dies with the branch (so a retired branch leaves nothing to prune).
 *     This is the only source that establishes ownership (952 Amendment B).
 *  2. **The worktree lock reason** — a LIVENESS HINT ONLY. Derisk D4 measured it: every kept
 *     worktree from an ended session is unlocked, so lock presence means "a harness session holds
 *     this right now", and a lock's pid is a hint about that session's process. A lock never
 *     establishes ownership, and the reconciler never rewrites or releases a harness lock whose
 *     reason it cannot attribute (a "foreign lock" → QUARANTINED).
 *  3. **The session ledger** (`tmp/dev-runner/sessions/<id>.json` `lastActivityAt`, tempdoc 886)
 *     — read through `ownership-verdict.readSessionActivity`, never re-derived here.
 *  4. **Finalization records** (`tmp/dev-runner/worktrees/<resource>.json`) — the only state this
 *     lifecycle STORES, and only while a resource is mid-archive/mid-removal. A third scope over
 *     tempdoc 861's one shared process-record grammar, beside `foreign/` and `agent-spawns/`.
 *
 * Invariants a reader should not have to infer:
 *
 *  - **Reading never writes** (844 §12.2 / 861 §6.1, inherited). `readMarkers`,
 *    `listBranchesWithMarkers`, `listWorktrees`, `readFinalizations`, `deriveState` and `census`
 *    perform no mutation of any kind — no config writes, no record writes, no pruning. The write
 *    paths are exactly `writeMarkers`, `clearMarkers`, `writeFinalization`, `advanceFinalization`
 *    and `removeFinalization`, and each is an explicit call.
 *  - **Absence of evidence is never ACTIVE and never ORPHANED.** An unverifiable owner is
 *    SUSPECT: SUSPECT reports, ORPHANED authorizes (in phase 2) removal, so the unknown case must
 *    land on the reporting side. Mirrors `classifyActivity`'s `known:false` and `leaseState`'s
 *    `unknown`.
 *  - **This scope carries its own schema version** (861 [A8]). `WORKTREE_RECORD_SCHEMA_VERSION` is
 *    independent of the `foreign/` and `agent-spawns/` constants; all three are `1` by
 *    coincidence, not by coupling.
 *  - **The state root override is honoured** (861 [A9]) through the ONE generic resolver, so an
 *    isolated dev-runner gets an isolated finalization register instead of a confident empty read.
 *
 * Every git invocation goes through an injectable `git(repoRoot, args)` so the whole module is
 * testable against throwaway repositories (and so no caller can be surprised by a hidden spawn).
 */
'use strict';

const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const {
  resolveRegisterDir,
  readRegister,
  writeRecordAtomic,
  pidAlive,
} = require('./process-record.cjs');
const {
  assertSafeRecordId,
  leaseState,
  realpathNearest,
  normalizePathForCompare,
} = require('./agent-spawn-record.cjs');
const {
  normalizeCreationTime,
  readProcessTable,
  coerceProcessTable,
  DEFAULT_MAX_TABLE_AGE_MS,
} = require('./process-identity.cjs');
const { readSessionActivity } = require('./ownership-verdict.cjs');

/* ── Policy (governance/worktree-lifecycle.v1.json is the source of truth) ─────────────────── */

/** Repo root of the checkout this module file lives in — the default policy location. */
const MODULE_REPO_ROOT = path.resolve(__dirname, '..', '..', '..');
const POLICY_RELPOSIX = 'governance/worktree-lifecycle.v1.json';
const MARKER_PREFIX = 'justsearch-';

const policyCache = new Map();

/**
 * Load the lifecycle policy. The governance file owns `sanctionedRoot`, `thresholds` and
 * `branchConfigKeys`; nothing here re-states them, so a policy edit cannot silently disagree with
 * the code that reads it. Cached per resolved path; throws (loudly) when the file is missing or
 * malformed rather than falling back to an invented default.
 *
 * @param {{ repoRoot?: string, policyPath?: string, reload?: boolean }} [args]
 */
function loadPolicy({ repoRoot = MODULE_REPO_ROOT, policyPath, reload = false } = {}) {
  const file = policyPath || path.join(repoRoot, ...POLICY_RELPOSIX.split('/'));
  if (!reload && policyCache.has(file)) return policyCache.get(file);
  let parsed;
  try {
    parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (err) {
    throw new Error(`cannot read the worktree-lifecycle policy at ${file}: ${String(err?.message || err)}`);
  }
  if (!Array.isArray(parsed?.branchConfigKeys) || parsed.branchConfigKeys.length === 0) {
    throw new Error(`${file} declares no branchConfigKeys`);
  }
  if (!parsed?.thresholds || typeof parsed.thresholds !== 'object') {
    throw new Error(`${file} declares no thresholds`);
  }
  policyCache.set(file, Object.freeze(parsed));
  return policyCache.get(file);
}

/**
 * `justsearch-resource` → `resource`. The marker FIELD names are a projection of the governance
 * file's config keys, derived here rather than listed a second time.
 */
function markerFieldFor(configKey) {
  return String(configKey).startsWith(MARKER_PREFIX) ? String(configKey).slice(MARKER_PREFIX.length) : null;
}

/** The marker field names, in the governance file's order. */
function markerFields(policy) {
  return policy.branchConfigKeys.map(markerFieldFor).filter((f) => f !== null);
}

/* ── The injectable git runner ─────────────────────────────────────────────────────────────── */

const GIT_QUERY_ENV = Object.freeze({ GIT_OPTIONAL_LOCKS: '0', GIT_TERMINAL_PROMPT: '0' });

/**
 * Default `git` implementation: `{ status, stdout, stderr }`, never throwing on a non-zero exit —
 * callers decide which non-zero exits are expected (`git config --unset` exits 5 when the key was
 * not set, and that is not an error).
 *
 * @param {string} repoRoot
 * @param {string[]} args
 * @param {{ cwd?: string }} [options]
 */
function defaultGit(repoRoot, args, { cwd = repoRoot } = {}) {
  const result = spawnSync('git', args, {
    cwd,
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    env: { ...process.env, ...GIT_QUERY_ENV },
  });
  return {
    status: result.error ? null : result.status,
    stdout: result.stdout || '',
    stderr: result.stderr || (result.error ? String(result.error.message) : ''),
  };
}

function requireGit(git, repoRoot, args, label) {
  const result = git(repoRoot, args);
  if (result.status !== 0) {
    throw new Error(`${label} failed (git exited ${result.status}): ${String(result.stderr || result.stdout || '').trim()}`);
  }
  return result.stdout || '';
}

function isNonEmptyString(value) {
  return typeof value === 'string' && value.trim().length > 0;
}

/** Escape a branch name for git's POSIX-extended `--get-regexp` pattern. */
function escapeRegexLiteral(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/* ── Markers: per-branch git config ────────────────────────────────────────────────────────── */

/**
 * Decode a `hold` marker (`reason|owner|review-by`).
 *
 * The trailing two fields are taken from the END, so a reason containing `|` round-trips
 * losslessly instead of silently shifting the owner and review date one field to the left.
 * `raw` is always carried so a malformed value is visible rather than interpreted on a guess.
 */
function parseHold(raw) {
  if (!isNonEmptyString(raw)) return null;
  const parts = String(raw).split('|');
  if (parts.length < 3) return { raw, reason: raw, owner: null, reviewBy: null, malformed: true };
  const reviewBy = parts[parts.length - 1];
  const owner = parts[parts.length - 2];
  const reason = parts.slice(0, parts.length - 2).join('|');
  return { raw, reason, owner, reviewBy };
}

/** Encode a hold triple. Refuses a `|` in `owner`/`reviewBy`, which decoding could not undo. */
function formatHold({ reason, owner, reviewBy } = {}) {
  for (const [name, value] of [['reason', reason], ['owner', owner], ['reviewBy', reviewBy]]) {
    if (!isNonEmptyString(value)) throw new Error(`hold.${name} must be a non-empty string`);
  }
  for (const [name, value] of [['owner', owner], ['reviewBy', reviewBy]]) {
    if (String(value).includes('|')) throw new Error(`hold.${name} must not contain '|' (the field separator)`);
  }
  return `${reason}|${owner}|${reviewBy}`;
}

/**
 * Parse `git config --null --get-regexp` output: NUL-terminated records, each `key\nvalue`.
 * Losslessly handles values containing newlines, which the line-oriented form cannot.
 */
function parseConfigNulRecords(raw) {
  const out = [];
  for (const record of String(raw || '').split('\0')) {
    if (record === '') continue;
    const split = record.indexOf('\n');
    if (split === -1) { out.push({ key: record, value: null }); continue; }
    out.push({ key: record.slice(0, split), value: record.slice(split + 1) });
  }
  return out;
}

const CONFIG_KEY_RE = new RegExp(`^branch\\.(.*)\\.(${MARKER_PREFIX}[A-Za-z0-9-]+)$`);

function markersFromConfigRecords(records, policy) {
  const known = new Set(policy.branchConfigKeys);
  const byBranch = new Map();
  for (const { key, value } of records) {
    const match = CONFIG_KEY_RE.exec(key);
    if (!match) continue;
    const branch = match[1];
    const configKey = match[2];
    if (!known.has(configKey)) continue; // an unknown justsearch-* key is not interpreted on a guess
    const field = markerFieldFor(configKey);
    const current = byBranch.get(branch) || {};
    current[field] = field === 'hold' ? parseHold(value) : value;
    byBranch.set(branch, current);
  }
  // Normalize every branch's shape so consumers can read `markers.released` without a guard.
  const fields = markerFields(policy);
  for (const [branch, partial] of byBranch) {
    const full = {};
    for (const field of fields) full[field] = partial[field] ?? null;
    byBranch.set(branch, full);
  }
  return byBranch;
}

/**
 * Read one branch's ownership markers.
 *
 * Returns `null` when the branch carries no `justsearch-resource` — an unregistered branch is
 * reported as unregistered, never as an empty registration.
 *
 * @param {{ repoRoot: string, branch: string, git?: Function, policy?: object }} args
 * @returns {{resource: string, session: string|null, harness: string|null, created: string|null,
 *   fork: string|null, anchor: string|null, released: string|null, hold: object|null}|null}
 */
function readMarkers({ repoRoot, branch, git = defaultGit, policy = loadPolicy() } = {}) {
  if (!isNonEmptyString(branch)) throw new Error('readMarkers requires a branch name');
  const pattern = `^branch\\.${escapeRegexLiteral(branch)}\\.${MARKER_PREFIX}`;
  const result = git(repoRoot, ['config', '--null', '--get-regexp', pattern]);
  // Exit 1 means "no key matched" — an ordinary, expected answer for an unregistered branch.
  if (result.status === 1) return null;
  if (result.status !== 0) {
    throw new Error(`branch marker read failed (git exited ${result.status}): ${String(result.stderr || '').trim()}`);
  }
  const byBranch = markersFromConfigRecords(parseConfigNulRecords(result.stdout), policy);
  const markers = byBranch.get(branch) || null;
  if (!markers || !isNonEmptyString(markers.resource)) return null;
  return markers;
}

/**
 * Write ownership markers. ONLY the provided keys are touched — a partial update (stamping
 * `released` at session close) must not erase `fork`/`anchor`, which are unrecoverable once lost.
 *
 * Values are strings; `hold` additionally accepts `{reason, owner, reviewBy}` and is encoded.
 *
 * @param {{ repoRoot: string, branch: string, markers: object, git?: Function, policy?: object }} args
 * @returns {string[]} the config keys written
 */
function writeMarkers({ repoRoot, branch, markers, git = defaultGit, policy = loadPolicy() } = {}) {
  if (!isNonEmptyString(branch)) throw new Error('writeMarkers requires a branch name');
  if (!markers || typeof markers !== 'object') throw new Error('writeMarkers requires a markers object');
  const fields = new Set(markerFields(policy));
  const written = [];
  for (const [field, value] of Object.entries(markers)) {
    if (value === undefined || value === null) continue;
    if (!fields.has(field)) {
      throw new Error(`unknown marker ${JSON.stringify(field)}; the governance file declares ${[...fields].join(', ')}`);
    }
    const encoded = field === 'hold' && typeof value === 'object' ? formatHold(value) : value;
    if (typeof encoded !== 'string') {
      throw new Error(`marker ${field} must be a string (got ${typeof encoded})`);
    }
    const key = `branch.${branch}.${MARKER_PREFIX}${field}`;
    requireGit(git, repoRoot, ['config', key, encoded], `writing ${key}`);
    written.push(key);
  }
  return written;
}

/**
 * Remove markers (default: all of them). Used when a branch is retired without being deleted, and
 * by `hold --release`. A key that was never set exits 5, which is success for this purpose.
 *
 * @param {{ repoRoot: string, branch: string, fields?: string[], git?: Function, policy?: object }} args
 * @returns {string[]} the config keys actually cleared
 */
function clearMarkers({ repoRoot, branch, fields = null, git = defaultGit, policy = loadPolicy() } = {}) {
  if (!isNonEmptyString(branch)) throw new Error('clearMarkers requires a branch name');
  const known = markerFields(policy);
  const target = fields ? fields : known;
  const cleared = [];
  for (const field of target) {
    if (!known.includes(field)) {
      throw new Error(`unknown marker ${JSON.stringify(field)}; the governance file declares ${known.join(', ')}`);
    }
    const key = `branch.${branch}.${MARKER_PREFIX}${field}`;
    const result = git(repoRoot, ['config', '--unset-all', key]);
    if (result.status === 0) cleared.push(key);
    else if (result.status !== 5) {
      throw new Error(`clearing ${key} failed (git exited ${result.status}): ${String(result.stderr || '').trim()}`);
    }
  }
  return cleared;
}

/**
 * Every branch carrying markers, whether or not a worktree still exists for it. This is the branch
 * census that makes a leftover branch a visible obligation (952 §1: 94 branches whose PR merged a
 * median 22 days earlier, invisible to any directory-driven scan).
 *
 * @returns {Map<string, object>} branch → markers
 */
function listBranchesWithMarkers({ repoRoot, git = defaultGit, policy = loadPolicy() } = {}) {
  const result = git(repoRoot, ['config', '--null', '--get-regexp', `^branch\\..*\\.${MARKER_PREFIX}`]);
  if (result.status === 1) return new Map();
  if (result.status !== 0) {
    throw new Error(`branch marker census failed (git exited ${result.status}): ${String(result.stderr || '').trim()}`);
  }
  const byBranch = markersFromConfigRecords(parseConfigNulRecords(result.stdout), policy);
  for (const [branch, markers] of [...byBranch]) {
    if (!isNonEmptyString(markers.resource)) byBranch.delete(branch);
  }
  return byBranch;
}

/* ── Lock reasons: hints, never ownership ──────────────────────────────────────────────────── */

/**
 * Our grammar: `<harness> session <id> (pid <pid>, start <creationFileTimeUtc>)`. Claude Code's
 * own lock (`claude session <name> (pid N)`) parses as the same shape with `start: null`.
 */
const LOCK_REASON_RE = /^([A-Za-z][A-Za-z0-9_-]*) session (.+?) \(pid (\d+)(?:, start (\d+))?\)$/;

/**
 * The grammar Claude Code's documentation describes: `session-<id>-<user>` and its
 * background/subagent variants. `<id>` is a session UUID (which itself contains hyphens), so the
 * trailing hyphen-free segment is taken as the user and everything between as the id. The split is
 * inherently ambiguous for a username containing a hyphen — which is why this parse yields a HINT
 * and never an ownership claim.
 */
const LOCK_REASON_CLAUDE_DOC_RE = /^(?:session|background|subagent)-(.+)-([^-]+)$/;

/**
 * Parse a worktree lock reason into `{harness, session, pid, start}`, or `null` when no known
 * grammar matches.
 *
 * A LOCK NEVER ESTABLISHES OWNERSHIP (952 Amendment B, derisk D4). What it supports is exactly
 * two inferences: "some harness session holds this worktree right now" (presence) and "here is a
 * pid worth probing for liveness" (the hint). Ownership identity comes from the branch config
 * markers, which the repository writes itself. A reason this function cannot parse is a FOREIGN
 * lock: reported, quarantined, and never released by the reconciler.
 *
 * `pid` is `null` for the documented Claude grammar, which carries no pid; `start` is `null` for
 * every reason that predates the lifecycle command's re-stamp.
 *
 * @param {string} reason
 * @returns {{harness: string, session: string, pid: number|null, start: string|null}|null}
 */
function parseLockReason(reason) {
  if (!isNonEmptyString(reason)) return null;
  const text = String(reason).trim();
  const ours = LOCK_REASON_RE.exec(text);
  if (ours) {
    const pid = Number(ours[3]);
    return {
      harness: ours[1].toLowerCase(),
      session: ours[2],
      pid: Number.isInteger(pid) && pid > 0 ? pid : null,
      start: normalizeCreationTime(ours[4] ?? null),
    };
  }
  const documented = LOCK_REASON_CLAUDE_DOC_RE.exec(text);
  if (documented) {
    return { harness: 'claude', session: documented[1], pid: null, start: null };
  }
  return null;
}

/**
 * Render our grammar. `start` is omitted when unknown rather than stamped with a placeholder — a
 * fabricated creation time would defeat the pid-reuse conjunct it exists to supply (861 §6.2).
 */
function formatLockReason({ harness, session, pid, start = null } = {}) {
  if (!isNonEmptyString(harness)) throw new Error('formatLockReason requires a harness');
  if (!isNonEmptyString(session)) throw new Error('formatLockReason requires a session id');
  if (!Number.isInteger(pid) || pid <= 0) throw new Error(`formatLockReason requires a positive integer pid (got ${JSON.stringify(pid)})`);
  const normalizedStart = normalizeCreationTime(start);
  return normalizedStart === null
    ? `${harness} session ${session} (pid ${pid})`
    : `${harness} session ${session} (pid ${pid}, start ${normalizedStart})`;
}

/* ── The worktree table ────────────────────────────────────────────────────────────────────── */

/**
 * Lossless parser for `git worktree list --porcelain -z` (the field set `remove-worktree.cjs`
 * already parses: path, HEAD, branch, detached, bare, locked+reason, prunable+reason). Unknown
 * fields are carried in `extra` rather than dropped, so a future Git version's addition is
 * visible instead of silently lost. Throws on a malformed stream; it never exits the process,
 * because this module is a library.
 */
function parseWorktreePorcelainZ(raw) {
  const entries = [];
  let entry = null;
  for (const field of String(raw || '').split('\0')) {
    if (field === '') {
      if (entry) entries.push(entry);
      entry = null;
      continue;
    }
    const split = field.indexOf(' ');
    const key = split === -1 ? field : field.slice(0, split);
    const value = split === -1 ? null : field.slice(split + 1);
    if (key === 'worktree') {
      if (entry) entries.push(entry);
      entry = { path: value, head: null, branchRef: null, detached: false, bare: false, locked: null, prunable: null };
      continue;
    }
    if (!entry) throw new Error(`git worktree list returned ${JSON.stringify(key)} before a worktree field`);
    if (key === 'HEAD') entry.head = value;
    else if (key === 'branch') entry.branchRef = value;
    else if (key === 'detached') entry.detached = true;
    else if (key === 'bare') entry.bare = true;
    else if (key === 'locked') entry.locked = value === null ? '(no reason supplied)' : value;
    else if (key === 'prunable') entry.prunable = value === null ? '(no reason supplied)' : value;
    else {
      if (!entry.extra) entry.extra = [];
      entry.extra.push({ key, value });
    }
  }
  if (entry) entries.push(entry);
  return entries;
}

function shortBranch(branchRef) {
  return typeof branchRef === 'string' && branchRef.startsWith('refs/heads/')
    ? branchRef.slice('refs/heads/'.length)
    : null;
}

/**
 * Every registered worktree, main first (git lists the main worktree first, and `isMain` records
 * that rather than leaving each caller to re-infer it). `branch` is the short name; `locked` and
 * `prunable` carry their reasons.
 */
function listWorktrees({ repoRoot, git = defaultGit } = {}) {
  const raw = requireGit(git, repoRoot, ['worktree', 'list', '--porcelain', '-z'], 'worktree membership query');
  return parseWorktreePorcelainZ(raw).map((entry, index) => ({
    ...entry,
    branch: shortBranch(entry.branchRef),
    isMain: index === 0,
  }));
}

/* ── Finalization records (the third process-record scope) ─────────────────────────────────── */

/** [861 A8] THIS scope's own version constant. Never a shared one. */
const WORKTREE_RECORD_SCHEMA_VERSION = 1;

/** Directory name under the dev-runner state root — a SIBLING of `foreign/` and `agent-spawns/`. */
const WORKTREES_REGISTER_DIRNAME = 'worktrees';
const WORKTREES_REGISTER_RELPOSIX = 'tmp/dev-runner/worktrees';

/**
 * The finalization phases, in order. `reconcile` resumes from the recorded phase, so the ORDER is
 * part of the contract, not presentation.
 */
const FINALIZATION_PHASES = Object.freeze(['claimed', 'archived', 'verified', 'removed', 'retired', 'done']);

const WORKTREES_MAX_RECORDS = 64;
const WORKTREES_MAX_BYTES = 16_000;

/**
 * The lease constructor, in the SAME shape `agent-spawn-record.cjs` builds and `leaseState`
 * reads — `{durationSec, renewedAt, expiresAt}`, expiry derived from the injected clock.
 *
 * This is a forced local copy, not a second grammar: `agent-spawn-record.cjs`'s `makeLease` is
 * module-private (its `module.exports` carries `leaseState` but not `makeLease`), and this chunk
 * owns no other file. The READER side is the shared one — `leaseState` is imported, so the two
 * scopes cannot drift on what "live" and "lapsed" mean, which is the half that matters for
 * safety. Delete this and import the original as soon as that module exports it.
 */
function makeLease({ durationSec, now = Date.now() }) {
  if (!Number.isFinite(durationSec) || durationSec <= 0) {
    throw new Error(`lease durationSec must be a positive number (got ${JSON.stringify(durationSec)})`);
  }
  return {
    durationSec,
    renewedAt: new Date(now).toISOString(),
    expiresAt: new Date(now + durationSec * 1000).toISOString(),
  };
}

/** [861 A9] The generic resolver, scoped. Honours `JUSTSEARCH_DEV_RUNNER_STATE_ROOT`. */
function finalizationDir(mainRepoRoot, env = process.env) {
  return resolveRegisterDir(mainRepoRoot, WORKTREES_REGISTER_DIRNAME, env);
}

function finalizationRecordPath(dir, resource) {
  return path.join(dir, `${assertSafeRecordId(resource)}.json`);
}

function isIsoTimestamp(value) {
  return isNonEmptyString(value) && Number.isFinite(new Date(value).getTime());
}

/**
 * [861 A7]/[A8] — this scope's OWN validator, injected into the shared bounded reader. A record
 * shaped for `foreign/` or `agent-spawns/` fails here (no resource, no phase) and comes back
 * `unreadable`, never silently accepted.
 */
function validateFinalizationRecord(record) {
  if (record?.schemaVersion !== WORKTREE_RECORD_SCHEMA_VERSION) {
    return {
      ok: false,
      reason: `unknown worktree-finalization schemaVersion ${JSON.stringify(record?.schemaVersion)} (this reader understands ${WORKTREE_RECORD_SCHEMA_VERSION})`,
    };
  }
  try {
    assertSafeRecordId(record.resource);
  } catch (err) {
    return { ok: false, reason: String(err?.message || err) };
  }
  if (!isNonEmptyString(record.path)) {
    return { ok: false, reason: `record declares no path (${JSON.stringify(record.path)})` };
  }
  if (record.branch !== null && !isNonEmptyString(record.branch)) {
    return { ok: false, reason: `record's branch must be a non-empty string or null (${JSON.stringify(record.branch)})` };
  }
  if (!isNonEmptyString(record.head)) {
    return { ok: false, reason: `record declares no head (${JSON.stringify(record.head)}); without it the archived tip cannot be verified` };
  }
  if (record.receipt !== null && (typeof record.receipt !== 'object' || Array.isArray(record.receipt))) {
    return { ok: false, reason: `record's receipt must be an object or null (${JSON.stringify(record.receipt)})` };
  }
  if (!FINALIZATION_PHASES.includes(record.phase)) {
    return { ok: false, reason: `record declares unknown phase ${JSON.stringify(record.phase)} (expected one of ${FINALIZATION_PHASES.join(', ')})` };
  }
  if (!isIsoTimestamp(record.startedAt)) {
    return { ok: false, reason: `record's startedAt must be an ISO timestamp (${JSON.stringify(record.startedAt)})` };
  }
  const by = record.by;
  if (!by || typeof by !== 'object') return { ok: false, reason: 'record declares no claimant (by)' };
  if (!isNonEmptyString(by.sessionId)) {
    return { ok: false, reason: `by.sessionId must be a non-empty string (${JSON.stringify(by?.sessionId)})` };
  }
  if (!Number.isInteger(by.pid) || by.pid <= 0) {
    return { ok: false, reason: `by.pid must be a positive integer (${JSON.stringify(by?.pid)})` };
  }
  // Tri-state, deliberately: a claimant with no creation time is admissible (Claude's own lock
  // carries none), but it is recorded as an explicit null so a later re-claim can see that pid
  // reuse cannot be ruled out — not silently omitted.
  if (by.creationFileTimeUtc !== null && normalizeCreationTime(by.creationFileTimeUtc) === null) {
    return { ok: false, reason: `by.creationFileTimeUtc must be a decimal FILETIME string or null (${JSON.stringify(by?.creationFileTimeUtc)})` };
  }
  const lease = record.lease;
  if (!lease || typeof lease !== 'object') return { ok: false, reason: 'record declares no lease' };
  if (!Number.isFinite(lease.durationSec) || lease.durationSec <= 0) {
    return { ok: false, reason: `lease.durationSec must be a positive number (${JSON.stringify(lease.durationSec)})` };
  }
  if (!isIsoTimestamp(lease.renewedAt)) {
    return { ok: false, reason: `lease.renewedAt must be an ISO timestamp (${JSON.stringify(lease.renewedAt)})` };
  }
  // Reuses the agent-spawns lease primitive rather than re-deriving expiry parsing: `unknown`
  // here means "no readable expiresAt", which a finalization record must never carry — an
  // unreadable lease is a claim nobody could ever safely break.
  if (leaseState(record, Date.now()) === 'unknown') {
    return { ok: false, reason: `lease.expiresAt must be an ISO timestamp (${JSON.stringify(lease.expiresAt)})` };
  }
  return { ok: true };
}

/**
 * Build a well-formed finalization record, or throw at the write site rather than leaving an
 * invalid record for a reader to report as `unreadable` hours later.
 */
function buildFinalizationRecord({
  resource,
  worktreePath,
  branch = null,
  head,
  receipt = null,
  phase = 'claimed',
  by,
  leaseDurationSec,
  now = Date.now(),
} = {}) {
  const record = {
    schemaVersion: WORKTREE_RECORD_SCHEMA_VERSION,
    resource,
    path: worktreePath,
    branch,
    head,
    receipt,
    phase,
    startedAt: new Date(now).toISOString(),
    by: {
      sessionId: by?.sessionId,
      pid: by?.pid,
      creationFileTimeUtc: normalizeCreationTime(by?.creationFileTimeUtc),
    },
    lease: makeLease({ durationSec: leaseDurationSec, now }),
  };
  const verdict = validateFinalizationRecord(record);
  if (!verdict.ok) throw new Error(`refusing to build an invalid finalization record: ${verdict.reason}`);
  return record;
}

/**
 * Read the finalization register. One entry per file: `{ok:true, recordId, record}` or
 * `{ok:false, recordId, reason}` — a torn record is reported, never hidden and never deleted.
 */
async function readFinalizations({ mainRepoRoot, env = process.env, dir = null } = {}) {
  return readRegister({
    dir: dir || finalizationDir(mainRepoRoot, env),
    maxRecords: WORKTREES_MAX_RECORDS,
    maxBytes: WORKTREES_MAX_BYTES,
    validateRecord: validateFinalizationRecord,
  });
}

/** Write (or replace) one finalization record, validated, atomically. */
async function writeFinalization({ mainRepoRoot, record, env = process.env, dir = null } = {}) {
  const verdict = validateFinalizationRecord(record);
  if (!verdict.ok) throw new Error(`refusing to write an invalid finalization record: ${verdict.reason}`);
  const target = dir || finalizationDir(mainRepoRoot, env);
  const file = finalizationRecordPath(target, record.resource);
  await writeRecordAtomic(file, record);
  return file;
}

/**
 * Move a finalization forward and renew its lease in the same write — progress IS the liveness
 * signal, so a phase that advances without renewing would let an actively-working finalization
 * look abandoned to a competing reconciler.
 *
 * Refuses to move BACKWARDS: `reconcile` resumes from the recorded phase, so rewinding would
 * re-run an already-completed destructive step (re-archiving over a verified archive, or
 * re-entering removal for a tree already gone).
 */
async function advanceFinalization({ mainRepoRoot, record, phase, env = process.env, dir = null, now = Date.now() } = {}) {
  const current = FINALIZATION_PHASES.indexOf(record?.phase);
  const next = FINALIZATION_PHASES.indexOf(phase);
  if (next === -1) {
    throw new Error(`unknown finalization phase ${JSON.stringify(phase)} (expected one of ${FINALIZATION_PHASES.join(', ')})`);
  }
  if (current === -1) throw new Error(`record carries unknown phase ${JSON.stringify(record?.phase)}`);
  if (next < current) {
    throw new Error(`refusing to move finalization for ${record.resource} backwards from ${record.phase} to ${phase}`);
  }
  const advanced = {
    ...record,
    phase,
    lease: makeLease({ durationSec: record.lease.durationSec, now }),
  };
  await writeFinalization({ mainRepoRoot, record: advanced, env, dir });
  return advanced;
}

/**
 * Retire a finalization record — the `done` path, and the only deletion in this module. Deletes a
 * FILE; touches no worktree, no branch, no process. Never deletes through a symlink.
 */
async function removeFinalization({ mainRepoRoot, resource, env = process.env, dir = null } = {}) {
  const file = finalizationRecordPath(dir || finalizationDir(mainRepoRoot, env), resource);
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
 * PURE. May this claimant start (or take over) a finalization for this resource?
 *
 * A finalization is the one part of this lifecycle that DELETES things, so a second claimant
 * entering it concurrently is the worst failure this module can enable. The rules, in order:
 *
 *  1. No record → claim. Nothing is in flight.
 *  2. The record is this session's own → claim. This is the crash-resume path: `reconcile` picks
 *     up its own torn finalization from the recorded phase.
 *  3. Lease live → REFUSE. Someone is working, whatever their pid says.
 *  4. Lease lapsed but the holder is verified ALIVE → REFUSE. A lapsed lease on a live holder is
 *     a busy process that has not renewed, not an abandoned one.
 *  5. Lease lapsed and the holder is dead OR unverifiable → claim.
 *
 * `holderAlive` is the caller's liveness verdict about `existing.by.pid` — tri-state, and `null`
 * (unverifiable) grants the claim by rule 5, because a finalization record that nobody can ever
 * break would strand the resource forever. That direction is safe HERE (unlike ORPHANED) because
 * the lease has already lapsed, which is itself positive evidence of an absent worker.
 *
 * @returns {{claim: boolean, reason: string}}
 */
function claimFinalization({ existing = null, by, now = Date.now(), holderAlive = null } = {}) {
  if (!isNonEmptyString(by?.sessionId)) throw new Error('claimFinalization requires by.sessionId');
  if (!existing) return { claim: true, reason: 'no finalization record for this resource' };
  const holder = existing.by || {};
  if (holder.sessionId === by.sessionId) {
    return { claim: true, reason: `resuming this session's own finalization at phase ${existing.phase}` };
  }
  const lease = leaseState(existing, now);
  if (lease === 'live') {
    return { claim: false, reason: `finalization is held by session ${holder.sessionId} under a live lease until ${existing.lease?.expiresAt}` };
  }
  if (holderAlive === true) {
    return { claim: false, reason: `finalization lease lapsed but the claimant pid ${holder.pid} is alive; it is busy, not abandoned` };
  }
  return {
    claim: true,
    reason: `finalization lease ${lease} and claimant pid ${holder.pid} is ${holderAlive === false ? 'dead' : 'unverifiable'}`,
  };
}

/* ── State derivation ──────────────────────────────────────────────────────────────────────── */

const STATES = Object.freeze({
  ACTIVE: 'ACTIVE',
  RELEASED: 'RELEASED',
  SUSPECT: 'SUSPECT',
  ORPHANED: 'ORPHANED',
  HELD: 'HELD',
  QUARANTINED: 'QUARANTINED',
  FINALIZING: 'FINALIZING',
  UNMANAGED: 'UNMANAGED',
});
const STATE_VALUES = Object.freeze(Object.values(STATES));

const FALLBACK_THRESHOLDS = Object.freeze({ suspectAfterMin: 15, orphanGraceHours: 24 });

/**
 * PURE. The single state derivation (952 §5.4). No IO, no clock of its own, no git.
 *
 * @param {object} args
 * @param {object|null} args.worktree        a `listWorktrees` entry, or null for a leftover branch.
 * @param {object|null} args.markers         `readMarkers` output.
 * @param {string|null} args.lock            the RAW lock reason, or null when unlocked.
 * @param {object|null} args.sessionActivity `readSessionActivity` output.
 * @param {boolean|null} args.ownerAlive     true | false | null (unverifiable) — never coerced.
 * @param {object|null} args.finalization    a finalization record for this resource.
 * @param {boolean} args.sanctioned          is the path under the sanctioned root?
 * @param {number} args.now
 * @param {{suspectAfterMin: number, orphanGraceHours: number}} args.thresholds
 * @returns {{state: string, reasons: string[]}}
 */
function deriveState({
  worktree = null,
  markers = null,
  lock = null,
  sessionActivity = null,
  ownerAlive = null,
  finalization = null,
  sanctioned = true,
  now = Date.now(),
  thresholds = FALLBACK_THRESHOLDS,
} = {}) {
  const reasons = [];
  const suspectAfterMs = Number(thresholds?.suspectAfterMin ?? FALLBACK_THRESHOLDS.suspectAfterMin) * 60_000;
  const orphanGraceMs = Number(thresholds?.orphanGraceHours ?? FALLBACK_THRESHOLDS.orphanGraceHours) * 3_600_000;
  const done = (state) => ({ state, reasons });

  if (worktree?.prunable) reasons.push(`git reports the registration prunable: ${worktree.prunable}`);

  // 1. A finalization in flight outranks everything: the resource is mid-archive/mid-removal and
  //    its Git-visible state is transient by construction.
  if (finalization) {
    reasons.push(`finalization record present at phase ${finalization.phase} (claimed by ${finalization.by?.sessionId})`);
    return done(STATES.FINALIZING);
  }

  // 2. Protected. Not under the sanctioned root, or carrying no registration this repository
  //    wrote: either way this lifecycle did not create it and must not decide its fate. 952 §1
  //    counted 26 hand-made worktrees outside the harness directory — reported, never acted on.
  if (!sanctioned) {
    reasons.push('path is outside the sanctioned worktree root; protected, never acted on');
    return done(STATES.UNMANAGED);
  }
  if (!markers) {
    reasons.push('no branch ownership markers; this lifecycle did not register it, so it is protected');
    return done(STATES.UNMANAGED);
  }

  // 3. A lock this module cannot attribute is a FOREIGN lock (952 Amendment B): quarantined, and
  //    never released by the reconciler. Ordered ahead of hold/released because a foreign lock
  //    means the markers no longer describe who is holding the tree.
  let parsedLock = null;
  if (lock !== null && lock !== undefined) {
    parsedLock = parseLockReason(lock);
    if (!parsedLock) {
      reasons.push(`lock reason ${JSON.stringify(String(lock).slice(0, 120))} matches no known grammar; foreign lock`);
      return done(STATES.QUARANTINED);
    }
    if (isNonEmptyString(markers.session) && parsedLock.session !== markers.session) {
      reasons.push(`lock names session ${parsedLock.session} but the branch markers name ${markers.session}; foreign lock`);
      return done(STATES.QUARANTINED);
    }
    reasons.push(`locked by ${parsedLock.harness} session ${parsedLock.session}${parsedLock.pid === null ? ' (no pid in the reason)' : ` (pid ${parsedLock.pid})`}`);
  }

  // 4. An explicit human decision beats every derived signal.
  if (markers.hold) {
    reasons.push(`hold: ${markers.hold.reason} (owner ${markers.hold.owner}, review by ${markers.hold.reviewBy})`);
    return done(STATES.HELD);
  }
  if (markers.released) {
    reasons.push(`released by the owning session at ${markers.released}`);
    return done(STATES.RELEASED);
  }

  // 5. No lock and no release marker. Derisk D4 measured that every kept worktree from an ENDED
  //    session is unlocked, so the harness dropped the lock on exit: the session is gone even
  //    though it never released explicitly. Reported as RELEASED, not as an unknown.
  if (parsedLock === null) {
    reasons.push('lock released by harness (no lock, no explicit release marker)');
    return done(STATES.RELEASED);
  }

  // 6. Locked and ours. Liveness decides, and only POSITIVE evidence yields ACTIVE.
  const lastActivityMs = sessionActivity?.lastActivityAt ? new Date(sessionActivity.lastActivityAt).getTime() : NaN;
  const activityKnown = Number.isFinite(lastActivityMs);
  const activityAgeMs = activityKnown ? now - lastActivityMs : null;
  const activityFresh = activityKnown && activityAgeMs <= suspectAfterMs;
  if (activityKnown) {
    reasons.push(`session ledger last activity ${Math.round(activityAgeMs / 60_000)} min ago`);
  } else {
    reasons.push('no session-ledger activity stamp for the owning session');
  }

  if (ownerAlive === true) {
    reasons.push('owner process verified alive');
    return done(STATES.ACTIVE);
  }
  if (activityFresh) {
    reasons.push(`session activity is fresher than the ${thresholds?.suspectAfterMin ?? FALLBACK_THRESHOLDS.suspectAfterMin} min suspect threshold`);
    return done(STATES.ACTIVE);
  }

  if (ownerAlive === false) {
    reasons.push('owner process is not running');
    if (!activityKnown) {
      // Verified-dead owner, but nothing dates the staleness. ORPHANED is the state that (in
      // phase 2) authorizes removal, so it is never entered on an unmeasurable age.
      reasons.push('staleness cannot be aged without an activity stamp, so this stays SUSPECT');
      return done(STATES.SUSPECT);
    }
    const staleSinceMs = lastActivityMs + suspectAfterMs;
    if (now - staleSinceMs > orphanGraceMs) {
      reasons.push(`stale for more than the ${thresholds?.orphanGraceHours ?? FALLBACK_THRESHOLDS.orphanGraceHours} h orphan grace period`);
      return done(STATES.ORPHANED);
    }
    return done(STATES.SUSPECT);
  }

  // 7. `ownerAlive === null`: liveness could not be established. Never ACTIVE by absence of
  //    evidence, never ORPHANED without a verified-dead owner.
  reasons.push('owner liveness is unverifiable; reported, not acted on');
  return done(STATES.SUSPECT);
}

/**
 * Liveness for a lock's pid — the ONE place the process table meets a lock hint.
 *
 * `verifyProcessIdentity`'s full three-conjunct check is deliberately NOT used here: the lock
 * grammar carries no `cmdlineFingerprint`, so that function would REFUSE on every input and the
 * refusal would say nothing about the harness process. What IS available is the pid and, once the
 * lifecycle command has re-stamped the lock, the creation time — so this evaluates exactly those
 * two conjuncts and reports honestly which ones it could evaluate.
 *
 * @returns {{alive: boolean|null, reason: string}} `null` = unverifiable, never coerced.
 */
function deriveOwnerAlive({ lock = null, isPidAlive = pidAlive, processTable = null, now = Date.now(), maxTableAgeMs = DEFAULT_MAX_TABLE_AGE_MS } = {}) {
  if (!lock || !Number.isInteger(lock.pid) || lock.pid <= 0) {
    return { alive: null, reason: 'the lock reason carries no pid, so process liveness cannot be probed' };
  }
  if (!isPidAlive(lock.pid)) {
    return { alive: false, reason: `pid ${lock.pid} is not running` };
  }
  if (lock.start === null) {
    // Positive but weak: the pid is alive, and pid reuse cannot be ruled out. Reported as alive
    // because the consequence of this branch is retention (ACTIVE), never removal.
    return { alive: true, reason: `pid ${lock.pid} is alive; the lock records no start time, so pid reuse is not ruled out` };
  }
  const resolved = coerceProcessTable(processTable, { now, maxTableAgeMs, acceptUnstampedTable: false });
  if (!resolved.ok) {
    return { alive: null, reason: `process table unusable: ${resolved.reason}` };
  }
  const row = resolved.table.find((r) => Number(r?.ProcessId) === lock.pid);
  if (!row) return { alive: false, reason: `pid ${lock.pid} is not present in the process table` };
  const liveStart = normalizeCreationTime(row.CreationFileTimeUtc);
  if (liveStart === null) {
    return { alive: null, reason: `pid ${lock.pid} is present but its creation time is unreadable; pid reuse cannot be ruled out` };
  }
  if (liveStart !== lock.start) {
    return { alive: false, reason: `pid ${lock.pid} was recycled: live process created at ${liveStart}, the lock names ${lock.start}` };
  }
  return { alive: true, reason: `pid ${lock.pid} verified alive (pid and creation time both match)` };
}

/* ── The census ────────────────────────────────────────────────────────────────────────────── */

/** Is `worktreePath` at or under the sanctioned root? Resolves both sides through junctions. */
async function isUnderSanctionedRoot({ worktreePath, sanctionedRoot, mainRepoRoot }) {
  if (!isNonEmptyString(worktreePath) || !isNonEmptyString(sanctionedRoot)) return false;
  const rootAbs = path.isAbsolute(sanctionedRoot) ? sanctionedRoot : path.join(mainRepoRoot, ...String(sanctionedRoot).split('/'));
  const root = normalizePathForCompare(await realpathNearest(rootAbs));
  const target = normalizePathForCompare(await realpathNearest(worktreePath));
  if (!root || !target) return false;
  return target === root || target.startsWith(`${root}/`);
}

function ageDaysFrom(created, now) {
  if (!isIsoTimestamp(created)) return null;
  return Math.round(((now - new Date(created).getTime()) / 86_400_000) * 10) / 10;
}

/**
 * The whole picture, in one read: every registered worktree with its derived state, every branch
 * carrying markers but no directory, everything protected, and every finalization in flight.
 *
 * READING NEVER WRITES (861 §6.1). This function runs `git worktree list` and `git config
 * --get-regexp`, reads the session ledger and the finalization register, and mutates nothing —
 * not the config, not a record, not a lock. A census that repaired what it found would make the
 * report itself a destructive action, which is precisely what the reconciler's advisory phase 1
 * exists to avoid.
 *
 * The MAIN worktree is excluded from every bucket: it is the checkout this lifecycle runs from,
 * not a resource it manages, and reporting it as `UNMANAGED` beside genuine strays would bury the
 * signal (952 §1 counted 26 real ones).
 *
 * @param {object} args
 * @param {string} args.mainRepoRoot   the main checkout (where the register and policy live).
 * @param {string} [args.repoRoot]     the checkout to query git in; defaults to `mainRepoRoot`.
 * @param {Function} [args.git]
 * @param {string} args.sessionsDir    `tmp/dev-runner/sessions` (tempdoc 886's ledger).
 * @param {object} [args.processTable] a `readProcessTable` result; read on demand when omitted.
 */
async function census({
  mainRepoRoot,
  repoRoot = mainRepoRoot,
  git = defaultGit,
  env = process.env,
  sessionsDir = null,
  now = Date.now(),
  thresholds = null,
  sanctionedRoot = null,
  policy = null,
  isPidAlive = pidAlive,
  processTable = undefined,
  readTable = readProcessTable,
} = {}) {
  const effectivePolicy = policy || loadPolicy({ repoRoot: mainRepoRoot });
  const effectiveThresholds = thresholds || effectivePolicy.thresholds;
  const effectiveRoot = sanctionedRoot || effectivePolicy.sanctionedRoot;
  const ledgerDir = sessionsDir || resolveRegisterDir(mainRepoRoot, 'sessions', env);

  const worktreeEntries = listWorktrees({ repoRoot, git });
  const branchMarkers = listBranchesWithMarkers({ repoRoot, git, policy: effectivePolicy });
  const registerEntries = await readFinalizations({ mainRepoRoot, env });

  const finalizationByResource = new Map();
  const finalizing = [];
  for (const entry of registerEntries) {
    if (!entry.ok) {
      finalizing.push({
        resource: entry.recordId,
        readable: false,
        reason: entry.reason,
        recordFile: `${WORKTREES_REGISTER_RELPOSIX}/${entry.recordId}.json`,
      });
      continue;
    }
    finalizationByResource.set(entry.record.resource, entry.record);
    finalizing.push({
      resource: entry.record.resource,
      readable: true,
      phase: entry.record.phase,
      path: entry.record.path,
      branch: entry.record.branch,
      startedAt: entry.record.startedAt,
      by: entry.record.by,
      lease: leaseState(entry.record, now),
      recordFile: `${WORKTREES_REGISTER_RELPOSIX}/${entry.record.resource}.json`,
    });
  }

  // The process table is read at most once, and only when a lock actually carries a start time to
  // check it against — a census must stay cheap enough to run on every orientation.
  let table = processTable;
  const needsTable = () => {
    if (table !== undefined) return table;
    table = readTable();
    return table;
  };

  const worktrees = [];
  const unmanaged = [];
  const branchesWithDirectories = new Set();

  for (const entry of worktreeEntries) {
    if (entry.isMain) {
      if (entry.branch) branchesWithDirectories.add(entry.branch);
      continue;
    }
    if (entry.branch) branchesWithDirectories.add(entry.branch);
    const markers = entry.branch ? branchMarkers.get(entry.branch) || null : null;
    const parsedLock = entry.locked === null ? null : parseLockReason(entry.locked);
    const sanctioned = await isUnderSanctionedRoot({ worktreePath: entry.path, sanctionedRoot: effectiveRoot, mainRepoRoot });
    const resource = markers?.resource || path.basename(entry.path);
    const finalization = finalizationByResource.get(resource) || null;

    let ownerAlive = null;
    let ownerReason = null;
    // Liveness is only probed for a registered, sanctioned resource: an UNMANAGED tree is
    // protected regardless of the verdict, and probing it would be work whose answer nothing uses.
    if (sanctioned && markers && parsedLock) {
      const verdict = deriveOwnerAlive({
        lock: parsedLock,
        isPidAlive,
        processTable: parsedLock.start === null ? null : needsTable(),
        now,
      });
      ownerAlive = verdict.alive;
      ownerReason = verdict.reason;
    }

    const sessionId = markers?.session || parsedLock?.session || null;
    const sessionActivity = sessionId ? readSessionActivity(ledgerDir, sessionId) : null;

    const { state, reasons } = deriveState({
      worktree: entry,
      markers,
      lock: entry.locked,
      sessionActivity,
      ownerAlive,
      finalization,
      sanctioned,
      now,
      thresholds: effectiveThresholds,
    });
    if (ownerReason) reasons.push(ownerReason);

    const row = {
      path: entry.path,
      branch: entry.branch,
      head: entry.head,
      lock: entry.locked,
      markers,
      resource,
      sessionId,
      ownerAlive,
      state,
      reasons,
      ageDays: ageDaysFrom(markers?.created, now),
    };
    if (state === STATES.UNMANAGED) unmanaged.push(row);
    else worktrees.push(row);
  }

  const leftoverBranches = [];
  for (const [branch, markers] of branchMarkers) {
    if (branchesWithDirectories.has(branch)) continue;
    // The directory is already gone, so no lock and no liveness question remains: only the human
    // decision (hold) and the release marker can speak. The finalization record is carried on the
    // row for visibility without letting it change the two-state answer this bucket promises.
    const { state, reasons } = deriveState({
      worktree: null,
      markers,
      lock: null,
      sessionActivity: null,
      ownerAlive: null,
      finalization: null,
      sanctioned: true,
      now,
      thresholds: effectiveThresholds,
    });
    leftoverBranches.push({
      branch,
      markers,
      state,
      reasons,
      ageDays: ageDaysFrom(markers?.created, now),
      finalization: finalizationByResource.get(markers.resource) || null,
    });
  }

  return { worktrees, leftoverBranches, unmanaged, finalizing };
}

module.exports = {
  // Policy
  POLICY_RELPOSIX,
  MARKER_PREFIX,
  loadPolicy,
  markerFields,
  // Git plumbing
  defaultGit,
  // Markers
  readMarkers,
  writeMarkers,
  clearMarkers,
  listBranchesWithMarkers,
  parseHold,
  formatHold,
  // Locks (hints only)
  parseLockReason,
  formatLockReason,
  // Worktrees
  listWorktrees,
  parseWorktreePorcelainZ,
  isUnderSanctionedRoot,
  // Finalization records
  WORKTREE_RECORD_SCHEMA_VERSION,
  WORKTREES_REGISTER_DIRNAME,
  WORKTREES_REGISTER_RELPOSIX,
  FINALIZATION_PHASES,
  finalizationDir,
  finalizationRecordPath,
  validateFinalizationRecord,
  buildFinalizationRecord,
  readFinalizations,
  writeFinalization,
  advanceFinalization,
  removeFinalization,
  claimFinalization,
  // State
  STATES,
  STATE_VALUES,
  deriveState,
  deriveOwnerAlive,
  census,
};
