#!/usr/bin/env node
/**
 * World-state query (tempdoc 743 P-J) — one entry point answering the "queryable world state"
 * deficit named in tempdoc 743's second-wave theorization: worktree staleness, live sessions,
 * tempdoc-number allocation, and (read-only, best-effort) dev-stack ownership. A pure function of
 * disk + local git state + best-effort `claude agents --json`; no daemon, no stored state, no
 * network calls (offline by design — no `gh` in v1, keep it fast).
 *
 * Sections:
 *   (a) WORKTREES     — per registered worktree: branch, dirty count, ahead/behind vs
 *                        origin/main, pushed?, last-commit age, VERDICT.
 *   (b) LIVE SESSIONS  — `claude agents --json` (best-effort; degrades to "unavailable").
 *   (c) TEMPDOC NUMBERS — highest claimed + next free (scripts/ci/lib/tempdoc-scan.mjs, the same
 *                        scanner `check-tempdoc-numbers.mjs` uses for the merge gate — "one
 *                        scanner, two consumers") + informational pick-time conflicts.
 *   (d) STACK          — best-effort read of the MAIN checkout's tmp/dev-runner/active.json +
 *                        linked run.json for runId/ports; "not running or unknown" otherwise.
 *                        Authoritative ownership state still comes from quick_health — this is a
 *                        read-only orientation glance, not a replacement.
 *   (e) AGENT SPAWNS   — tempdoc 861 §6.4 `orientation` occasion: registered + observed
 *                        agent-spawned helper processes (ui-shot's Vite, `serve-worktree-fe`, the
 *                        OTel sink) with their §6.3 verdicts. Read-only: this occasion binds to
 *                        `capability: 'advisory'` in the reaper's frozen `OCCASIONS` map, so it
 *                        can never obtain a kill list — a ready-to-run kill line is printed for
 *                        the observed tier, never auto-run.
 *   (f) ADR REVIEW     — tempdoc 884 design decision 4: ADRs whose `last_reviewed` is past the
 *                        declared review window. The `adr-coverage` gate already warns in CI, but
 *                        a CI-only warning is the pile nobody reads — nothing is scheduled to
 *                        re-read decisions, so this section is the schedule. The window and the
 *                        date arithmetic come from the gate's own `review-window.mjs`; this is a
 *                        second consumer of one rule, not a second rule.
 *
 * Every external probe (git, claude CLI, dev-runner state files) is wrapped in try/catch and
 * degrades to an explicit "unavailable"/"unknown" line — this tool must never crash the orienting
 * agent, and must stay well under 10s.
 *
 * Usage: node scripts/agent-analytics/world-state.mjs [--json]
 */

import { execFile, execFileSync, spawnSync } from 'node:child_process';
import { promisify } from 'node:util';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import matter from 'gray-matter';
import { collectClaims, divergentInFlightCollisions, nextFreeNumber } from '../ci/lib/tempdoc-scan.mjs';
import {
  NON_ADR_FILES,
  REVIEW_STALE_DAYS_DEFAULT,
  daysSince,
  loadReviewStaleDays,
  normalizeDate,
} from '../governance/gates/adr-coverage/review-window.mjs';

// Tempdoc 861 §7.5 — the documented cross-format interop: an ESM tool pulls the shared `.cjs`
// dev-stack libs in via `createRequire`, exactly as `otlp-sink-ensure.mjs` already does.
const require = createRequire(import.meta.url);
const { gatherAgentSpawnOrientation, describeEntry, resolveCallerSessionId } = require('../dev/lib/agent-spawn-sweep.cjs');
// Tempdoc 952: the lifecycle census joins the Worktrees table (OWNER/LIFECYCLE columns) instead
// of adding a section, so session-start output does not grow (952 §5.7, A10).
const worktreeRegister = require('../dev/lib/worktree-register.cjs');
const worktreeArchive = require('../dev/lib/worktree-archive.cjs');

const STALE_DAYS_THRESHOLD = 3;

// The tree this script actually lives in — NOT process.cwd() (the CLI is run from anywhere) and
// NOT the main checkout (an ADR you are editing lives in YOUR worktree).
const SELF_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

// ---------------------------------------------------------------------------------------------
// Pure functions (exported for tests — no I/O, no process access beyond their arguments).
// ---------------------------------------------------------------------------------------------

/**
 * Verdict for a single worktree row. Pure — takes already-gathered facts, no git/fs access.
 *
 * - STRANDED-FINISHED: clean, has commits ahead of origin/main, none of them pushed anywhere,
 *   and the last commit is older than the staleness threshold — work that looks finished but was
 *   never sent anywhere.
 * - STALE-CANDIDATE: clean and zero commits ahead of origin/main — nothing unique left to lose.
 * - DIRTY-IDLE: uncommitted changes sitting untouched past the staleness threshold.
 * - ACTIVE: everything else (the default — recent activity, or state we can't yet classify as
 *   one of the above three).
 *
 * `null` for `aheadCount`/`pushed`/`lastCommitAgeDays` means "unknown" (a probe failed) and is
 * treated conservatively — it never *causes* a STRANDED-FINISHED/STALE-CANDIDATE/DIRTY-IDLE
 * verdict, only ACTIVE (the safe default when the evidence is incomplete).
 *
 * @param {{dirty: boolean|null, aheadCount: number|null, pushed: boolean|null, lastCommitAgeDays: number|null}} row
 * @returns {'ACTIVE'|'STRANDED-FINISHED'|'STALE-CANDIDATE'|'DIRTY-IDLE'}
 */
export function computeVerdict({ dirty, aheadCount, pushed, lastCommitAgeDays }) {
  const isStale = typeof lastCommitAgeDays === 'number' && lastCommitAgeDays > STALE_DAYS_THRESHOLD;

  if (dirty === true) {
    return isStale ? 'DIRTY-IDLE' : 'ACTIVE';
  }
  if (dirty === false) {
    if (aheadCount != null && aheadCount > 0 && pushed === false && isStale) {
      return 'STRANDED-FINISHED';
    }
    if (aheadCount === 0) {
      return 'STALE-CANDIDATE';
    }
  }
  return 'ACTIVE';
}

/** Numbers claimed under 2+ distinct basenames anywhere (origin or any worktree) — informational
 * only; broader than `divergentInFlightCollisions` (which ignores origin-present basenames to
 * avoid flagging legitimate on-origin multi-file batches). Surfaces the same pre-existing #720/
 * #729 in-flight collisions plus anything already reused on origin, for situational awareness at
 * pick time — not a pass/fail gate. */
export function pickTimeConflicts(claims) {
  const out = [];
  for (const [number, byName] of claims) {
    if (byName.size >= 2) {
      out.push({ number, basenames: [...byName.keys()].sort() });
    }
  }
  return out.sort((a, b) => Number(a.number) - Number(b.number));
}

function formatAge(days) {
  if (days == null) return '?';
  if (days < 1) return '<1d';
  return `${Math.floor(days)}d`;
}
function formatBool(v) {
  if (v === null) return '?';
  return v ? 'yes' : 'no';
}
function formatCount(v) {
  return v == null ? '?' : String(v);
}

/**
 * Tempdoc 952 §5.7 / A8: the lifecycle detail block, printed only under `--lifecycle` (owner and
 * metrics runs), never on an ordinary orientation. Pure.
 */
export function renderLifecycleDetail(lifecycle) {
  const lines = ['## Worktree lifecycle (952)', ''];
  const m = lifecycle.metrics;
  lines.push(`ownership coverage: ${m.registered}/${m.registered + m.unregisteredUnderRoot} sanctioned worktrees registered (${m.unregisteredUnderRoot} pre-952 or unregistered), ${m.unmanagedOutsideRoot} outside the sanctioned root`);
  lines.push(`termination latency: ${m.releasedOverGrace} released resource(s) older than ${m.graceHours} h still present of ${m.released} released; ${m.finalizing} finalizing, ${m.quarantined} quarantined`);
  lines.push(`preservation: ${lifecycle.archives.length} archive(s), ${(lifecycle.archives.reduce((s, a) => s + (a.bytes || 0), 0) / 1048576).toFixed(1)} MB, oldest ${lifecycle.archives.length ? lifecycle.archives[lifecycle.archives.length - 1].createdAt : '-'}`);
  const obligations = lifecycle.rows.filter((r) => r.state !== 'ACTIVE' && r.state !== 'UNMANAGED');
  if (obligations.length) {
    lines.push('', 'obligations:');
    for (const r of obligations) lines.push(`  - ${r.state} ${r.branch ?? 'detached'} @ ${r.path}${r.reasons?.length ? ` — ${r.reasons[0]}` : ''}`);
  }
  for (const f of lifecycle.finalizing) lines.push(`  - FINALIZING ${f.resource}: ${f.readable ? `${f.phase} (lease ${f.lease})` : `unreadable: ${f.reason}`}`);
  lines.push('');
  return lines;
}

/** Render the full report as markdown. Pure — takes the already-gathered `data` object. */
export function renderMarkdown(data) {
  const lines = [];
  lines.push('# World state', '');
  lines.push(`_generated ${data.generatedAt}_`, '');

  lines.push('## Worktrees', '');
  lines.push('| WORKTREE | BRANCH | DIRTY | AHEAD | BEHIND | PUSHED | AGE | VERDICT | OWNER | LIFECYCLE |');
  lines.push('|---|---|---|---|---|---|---|---|---|---|');
  for (const w of data.worktrees) {
    lines.push(
      `| ${w.name}${w.isMain ? ' [main]' : ''} | ${w.branch} | ${formatCount(w.dirtyCount)} | ${formatCount(w.aheadCount)} | ${formatCount(w.behindCount)} | ${formatBool(w.pushed)} | ${formatAge(w.lastCommitAgeDays)} | ${w.verdict} | ${w.owner ?? '-'} | ${w.lifecycle ?? '-'} |`,
    );
  }
  lines.push('');
  if (data.lifecycle?.available && data.lifecycle.leftoverBranches.length) {
    lines.push(`lifecycle: ${data.lifecycle.leftoverBranches.length} branch(es) with ownership markers but no worktree: ${data.lifecycle.leftoverBranches.map((b) => `${b.branch} (${b.state})`).join(', ')}`, '');
  }
  if (data.lifecycle?.detail) {
    lines.push(...renderLifecycleDetail(data.lifecycle));
  }

  lines.push('## Live sessions', '');
  if (data.sessions.available) {
    if (data.sessions.rows.length === 0) {
      lines.push('_no live interactive sessions_');
    } else {
      lines.push('| PID | NAME | STATUS | CWD |');
      lines.push('|---|---|---|---|');
      for (const s of data.sessions.rows) {
        lines.push(`| ${s.pid} | ${s.name || '?'} | ${s.status || '?'} | ${s.cwd} |`);
      }
    }
  } else {
    lines.push(`unavailable — ${data.sessions.reason}`);
  }
  lines.push('');

  lines.push('## Tempdoc numbers', '');
  lines.push(`highest claimed: **#${data.tempdocNumbers.highestClaimed}**  |  next free: **#${data.tempdocNumbers.nextFree}**  |  ${data.tempdocNumbers.distinctNumbers} distinct numbers across ${data.tempdocNumbers.worktreeCount} worktree(s) + origin/${data.tempdocNumbers.defaultBranch}`);
  if (data.tempdocNumbers.mergeGateCollisions.length > 0) {
    lines.push('', `merge-gate collisions (check-tempdoc-numbers would fail): ${data.tempdocNumbers.mergeGateCollisions.length}`);
    for (const c of data.tempdocNumbers.mergeGateCollisions) lines.push(`  - #${c.number}: ${c.detail}`);
  }
  if (data.tempdocNumbers.pickTimeConflicts.length > 0) {
    lines.push('', `pick-time conflicts (informational — 2+ distinct basenames anywhere): ${data.tempdocNumbers.pickTimeConflicts.length}`);
    for (const c of data.tempdocNumbers.pickTimeConflicts) lines.push(`  - #${c.number}: ${c.basenames.join(', ')}`);
  }
  lines.push('');

  lines.push('## Stack', '');
  lines.push(data.stack.available ? data.stack.summary : `not running or unknown — use quick_health (${data.stack.reason})`);
  lines.push('');

  lines.push('## ADR review', '');
  if (data.adrReview.available) {
    const { staleCount, scanned, thresholdDays, rows } = data.adrReview;
    lines.push(`${staleCount} of ${scanned} ADR(s) past the ${thresholdDays}-day review window`);
    for (const r of rows) {
      lines.push(`  - ${r.adr}: last_reviewed ${r.lastReviewed ?? 'missing'}${r.ageDays == null ? '' : ` (${r.ageDays}d ago)`}`);
    }
    if (staleCount > 0) {
      lines.push('', 're-examine per docs/decisions/README.md § How to re-examine an ADR (amendments are append-only), then update `last_reviewed` and `probes:`.');
    }
  } else {
    lines.push(`unavailable — ${data.adrReview.reason}`);
  }
  lines.push('');

  lines.push('## Agent spawns', '');
  if (data.agentSpawns.available) {
    const { registered, observed } = data.agentSpawns;
    if (registered.length === 0 && observed.length === 0) {
      lines.push('_no registered or observed agent-spawned processes_');
    } else {
      for (const e of registered) lines.push(`- REGISTERED ${describeEntry(e).replace(/\n\s*/g, ' ')}`);
      for (const e of observed) lines.push(`- OBSERVED ${describeEntry(e).replace(/\n\s*/g, ' ')}`);
    }
  } else {
    lines.push(`unavailable — ${data.agentSpawns.reason}`);
  }
  lines.push('');

  return lines.join('\n');
}

// ---------------------------------------------------------------------------------------------
// I/O-touching gatherers. Every git/process/fs call is individually try/catch-guarded so one
// failing probe degrades that one field to null/"unavailable" rather than crashing the report.
// ---------------------------------------------------------------------------------------------

function gitOrNull(args, opts = {}) {
  try {
    return execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 5000, ...opts }).trim();
  } catch {
    return null;
  }
}

const execFileAsync = promisify(execFile);

/** Async twin of {@link gitOrNull} — same degrade-to-null contract, non-blocking so probes overlap. */
async function gitOrNullAsync(args) {
  try {
    const { stdout } = await execFileAsync('git', args, {
      encoding: 'utf8',
      timeout: 5000,
      maxBuffer: 16 * 1024 * 1024,
    });
    return stdout.trim();
  } catch {
    return null;
  }
}

/** Run `fn` over `items` with at most `limit` in flight, preserving input order in the result. */
async function mapLimit(items, limit, fn) {
  const out = new Array(items.length);
  let next = 0;
  const worker = async () => {
    for (let i = next++; i < items.length; i = next++) {
      out[i] = await fn(items[i]);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return out;
}

function listWorktreePaths() {
  const out = gitOrNull(['worktree', 'list', '--porcelain']);
  if (!out) return [process.cwd()];
  const paths = out
    .split('\n')
    .filter((l) => l.startsWith('worktree '))
    .map((l) => l.slice('worktree '.length).trim())
    .filter(Boolean);
  return paths.length ? paths : [process.cwd()];
}

/** Main checkout root, derived from the shared `.git` common dir — robust regardless of worktree
 * listing order (tempdoc 743 P-J: don't assume "first worktree list entry is main"). */
function mainCheckoutRoot() {
  const commonDir = gitOrNull(['rev-parse', '--git-common-dir']);
  if (!commonDir) return null;
  const abs = path.resolve(commonDir);
  return abs.replace(/[\\/]\.git[\\/]?$/, '');
}

async function gatherWorktreeRow(wtPath, mainRoot) {
  const name = wtPath.replace(/\\/g, '/').split('/').pop();
  const isMain = mainRoot != null && path.resolve(wtPath) === path.resolve(mainRoot);
  const branch =
    (await gitOrNullAsync(['-C', wtPath, 'branch', '--show-current'])) ||
    (await gitOrNullAsync(['-C', wtPath, 'rev-parse', '--short', 'HEAD'])) ||
    null;

  // The remaining four probes are independent of each other; only `pushed` needed the branch.
  const [statusOut, leftRight, pushedRef, ts] = await Promise.all([
    gitOrNullAsync(['-C', wtPath, 'status', '--porcelain']),
    gitOrNullAsync(['-C', wtPath, 'rev-list', '--left-right', '--count', 'origin/main...HEAD']),
    branch
      ? gitOrNullAsync(['-C', wtPath, 'rev-parse', '--verify', '--quiet', `refs/remotes/origin/${branch}`])
      : Promise.resolve(null),
    gitOrNullAsync(['-C', wtPath, 'log', '-1', '--format=%ct']),
  ]);

  const dirty = statusOut === null ? null : statusOut.length > 0;
  const dirtyCount = statusOut === null ? null : statusOut.length === 0 ? 0 : statusOut.split('\n').filter(Boolean).length;

  let aheadCount = null;
  let behindCount = null;
  if (leftRight) {
    const [left, right] = leftRight.split(/\s+/);
    if (left != null && right != null && left !== '' && right !== '') {
      behindCount = Number(left);
      aheadCount = Number(right);
    }
  }

  const pushed = branch ? pushedRef !== null : null;

  let lastCommitAgeDays = null;
  if (ts) {
    const seconds = Number(ts);
    if (Number.isFinite(seconds)) lastCommitAgeDays = (Date.now() / 1000 - seconds) / 86400;
  }

  const row = { name, path: wtPath, isMain, branch: branch ?? 'unknown', dirty, dirtyCount, aheadCount, behindCount, pushed, lastCommitAgeDays };
  return { ...row, verdict: computeVerdict(row) };
}

/**
 * Probe every registered worktree. Tempdoc 930: this was serial — five blocking `git` spawns per
 * worktree, one worktree at a time — which on a checkout carrying dozens of worktrees dominated the
 * whole run (measured 31.5s over 61 worktrees, 19.0s of it `git status --porcelain`) and pushed the
 * CLI past its 10s budget under ordinary parallel-agent load. Each worktree has its own git index,
 * so the probes do not contend; running them with bounded concurrency is the fix. The bound keeps a
 * 60-worktree checkout from spawning 60 gits at once.
 */
const WORKTREE_PROBE_CONCURRENCY = 8;

async function gatherWorktrees() {
  const mainRoot = mainCheckoutRoot();
  return mapLimit(listWorktreePaths(), WORKTREE_PROBE_CONCURRENCY, (p) => gatherWorktreeRow(p, mainRoot));
}

function gatherSessions() {
  try {
    const res = spawnSync('claude', ['agents', '--json'], { encoding: 'utf8', timeout: 5000 });
    if (res.error) return { available: false, reason: res.error.message, rows: [] };
    if (res.status !== 0) return { available: false, reason: `exit ${res.status}`, rows: [] };
    const parsed = JSON.parse(res.stdout);
    if (!Array.isArray(parsed)) return { available: false, reason: 'unexpected output shape', rows: [] };
    return { available: true, rows: parsed };
  } catch (e) {
    return { available: false, reason: e.message, rows: [] };
  }
}

function gatherTempdocNumbers() {
  const { claims, worktreeCount, defaultBranch } = collectClaims({ cwd: process.cwd() });
  const mergeGateCollisions = divergentInFlightCollisions(claims);
  const conflicts = pickTimeConflicts(claims);
  const nextFree = nextFreeNumber(claims);
  return {
    distinctNumbers: claims.size,
    worktreeCount,
    defaultBranch,
    highestClaimed: nextFree - 1,
    nextFree,
    mergeGateCollisions,
    pickTimeConflicts: conflicts,
  };
}

function gatherStack() {
  const mainRoot = mainCheckoutRoot();
  if (!mainRoot) return { available: false, reason: 'could not resolve main checkout root' };
  try {
    const activePath = path.join(mainRoot, 'tmp', 'dev-runner', 'active.json');
    if (!fs.existsSync(activePath)) return { available: false, reason: 'no active.json' };
    const active = JSON.parse(fs.readFileSync(activePath, 'utf8'));
    if (!active?.runId) return { available: false, reason: 'active.json has no runId' };
    const runPath = path.join(mainRoot, 'tmp', 'dev-runner', 'runs', active.runId, 'run.json');
    let ports = 'ports unknown';
    if (fs.existsSync(runPath)) {
      const run = JSON.parse(fs.readFileSync(runPath, 'utf8'));
      ports = `apiPort=${run.apiPortActual ?? '?'} uiPort=${run.uiPortActual ?? '?'}`;
    }
    const holder = active.holder?.agentSessionId || active.holder?.source || 'unknown';
    return { available: true, summary: `runId=${active.runId} ${ports} holder=${holder}` };
  } catch (e) {
    return { available: false, reason: e.message };
  }
}

/**
 * ADRs whose `last_reviewed` is past the review window (tempdoc 884 design decision 4).
 *
 * Deterministic given its arguments: it reads `adrDir` and nothing else, and takes `now` so a
 * test can pin a synthetic date instead of asserting against whatever today happens to make
 * stale. A missing/unparseable `last_reviewed` counts as stale — an ADR that declares no review
 * date is exactly the kind that goes unexamined (the enforcer's `verdictForReviewStale` makes
 * the same call).
 *
 * @param {{adrDir: string, now?: number, thresholdDays?: number}} options
 * @returns {{available: boolean, reason?: string, scanned: number, staleCount: number,
 *            thresholdDays: number, rows: Array<{adr: string, lastReviewed: string|null, ageDays: number|null}>}}
 */
export function gatherAdrReview({ adrDir, now = Date.now(), thresholdDays = REVIEW_STALE_DAYS_DEFAULT }) {
  const base = { scanned: 0, staleCount: 0, thresholdDays, rows: [] };
  let entries;
  try {
    entries = fs.readdirSync(adrDir).filter((n) => n.endsWith('.md') && !NON_ADR_FILES.has(n));
  } catch (e) {
    return { available: false, reason: e.message, ...base };
  }
  const rows = [];
  let scanned = 0;
  for (const name of entries.sort()) {
    let content;
    try {
      content = fs.readFileSync(path.join(adrDir, name), 'utf8');
    } catch {
      continue; // one unreadable ADR degrades that row, not the section
    }
    scanned += 1;
    let data = {};
    if (content.startsWith('---')) {
      try {
        data = matter(content).data ?? {};
      } catch {
        data = {}; // unparseable frontmatter has no review date — the gate fails it separately
      }
    }
    const lastReviewed = normalizeDate(data.last_reviewed);
    const ageDays = lastReviewed ? daysSince(lastReviewed, now) : null;
    if (ageDays === null || ageDays > thresholdDays) {
      rows.push({ adr: name, lastReviewed, ageDays });
    }
  }
  return { available: true, scanned, staleCount: rows.length, thresholdDays, rows };
}

/** Tempdoc 861 §6.4 `orientation` occasion — read-only, never kills (enforced in the reaper's
 * frozen `OCCASIONS` map, not here: `capability: 'advisory'` mints no `reap` entry regardless of
 * what this gatherer passes in). `recordId === null` is how `reapEligible` marks an observed-tier
 * entry (no record behind it) — the same distinction `probeForeignRuns` makes with `source`. */
async function gatherAgentSpawns() {
  const mainRoot = mainCheckoutRoot();
  if (!mainRoot) return { available: false, reason: 'could not resolve main checkout root' };
  // D2 (closing-window findings): resolve THIS session's id via the standard chain so a session's
  // own live spawn attributes as `same-session`, not `other-session/lease-live` (861 W5 review
  // F-2/F-3's chain, the same one `remove-worktree.cjs` gained). `repoRoot` is `process.cwd()`,
  // the CURRENT tree — not `mainRoot` — because the SessionStart pointer-file fallback is written
  // wherever the session actually started, per `resolveCallerSessionId`'s own doc comment.
  const callerSessionId = resolveCallerSessionId({ env: process.env, repoRoot: process.cwd() });
  const result = await gatherAgentSpawnOrientation({ mainRepoRoot: mainRoot, callerSessionId });
  if (!result.available) return result;
  const all = result.buckets.all;
  return {
    available: true,
    registered: all.filter((e) => e.recordId !== null),
    observed: all.filter((e) => e.recordId === null),
  };
}

// ---------------------------------------------------------------------------------------------
// Report assembly + CLI.
// ---------------------------------------------------------------------------------------------

/**
 * Tempdoc 952: the lifecycle census (worktree-register.cjs). Read-only. Policy is read from
 * SELF_ROOT (the tree this script lives in); the census runs against the shared repository. Any
 * failure degrades to `{available:false, reason}` like every other gatherer.
 */
async function gatherLifecycle({ detail = false } = {}) {
  try {
    const mainRoot = mainCheckoutRoot();
    if (!mainRoot) return { available: false, reason: 'main checkout root unknown' };
    const policy = worktreeRegister.loadPolicy({ repoRoot: SELF_ROOT });
    const census = await worktreeRegister.census({ mainRepoRoot: mainRoot, repoRoot: SELF_ROOT, policy });
    const rows = [...census.worktrees, ...census.unmanaged];
    const byPath = new Map(rows.map((r) => [path.resolve(r.path).toLowerCase(), r]));
    const graceHours = policy.thresholds?.orphanGraceHours ?? 24;
    const now = Date.now();
    const releasedRows = census.worktrees.filter((r) => r.state === 'RELEASED');
    const metrics = {
      registered: census.worktrees.length,
      unregisteredUnderRoot: census.unmanaged.filter((r) => (r.reasons || []).some((x) => /no branch ownership markers/.test(x))).length,
      unmanagedOutsideRoot: census.unmanaged.filter((r) => (r.reasons || []).some((x) => /outside the sanctioned/.test(x))).length,
      released: releasedRows.length,
      releasedOverGrace: releasedRows.filter((r) => r.markers?.released && now - Date.parse(r.markers.released) > graceHours * 3600 * 1000).length,
      graceHours,
      finalizing: census.finalizing.length,
      quarantined: census.worktrees.filter((r) => r.state === 'QUARANTINED').length,
    };
    let archives = [];
    if (detail) {
      try { archives = worktreeArchive.listArchives({ mainRepoRoot: mainRoot, policy: worktreeArchive.loadPolicy(SELF_ROOT) }); } catch { archives = []; }
    }
    return { available: true, detail, byPath, rows, leftoverBranches: census.leftoverBranches, finalizing: census.finalizing, metrics, archives };
  } catch (err) {
    return { available: false, reason: String(err?.message || err).slice(0, 200) };
  }
}

function ownerLabel(row) {
  if (!row?.markers) return null;
  const session = row.markers.session ? String(row.markers.session).slice(0, 8) : '?';
  return `${row.markers.harness || '?'}:${session}`;
}

export async function buildReport({ lifecycleDetail = false } = {}) {
  const [worktrees, lifecycle] = await Promise.all([gatherWorktrees(), gatherLifecycle({ detail: lifecycleDetail })]);
  if (lifecycle.available) {
    for (const w of worktrees) {
      const row = lifecycle.byPath.get(path.resolve(w.path).toLowerCase());
      if (!row) continue;
      w.owner = ownerLabel(row);
      w.lifecycle = row.state;
    }
  }
  return {
    generatedAt: new Date().toISOString(),
    worktrees,
    lifecycle: lifecycle.available ? { ...lifecycle, byPath: undefined } : lifecycle,
    sessions: gatherSessions(),
    tempdocNumbers: gatherTempdocNumbers(),
    stack: gatherStack(),
    adrReview: gatherAdrReview({
      adrDir: path.join(SELF_ROOT, 'docs', 'decisions'),
      thresholdDays: loadReviewStaleDays(SELF_ROOT),
    }),
    agentSpawns: await gatherAgentSpawns(),
  };
}

async function main() {
  const jsonMode = process.argv.includes('--json');
  const data = await buildReport({ lifecycleDetail: process.argv.includes('--lifecycle') });
  if (jsonMode) {
    console.log(JSON.stringify(data, null, 2));
  } else {
    console.log(renderMarkdown(data));
  }
}

const isMainModule = process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (isMainModule) {
  main().catch((err) => {
    console.error(`world-state: ERROR: ${err && err.message ? err.message : err}`);
    process.exit(1);
  });
}
