#!/usr/bin/env node
/**
 * efficiency-trend.mjs — tempdoc 908 §5.1.
 *
 * WHY THIS EXISTS. Every reader under scripts/agent-analytics/ (context-residency,
 * spawn-economics, cache-efficiency, overhead-taxonomy, context-attribution) emits
 * ONE aggregate for its whole `--since/--until` window — `baseline-economics.mjs`
 * is the sole exception and tempdoc 908 §2 distrusts its numbers least. A snapshot
 * reader answers "where did the money go?" and cannot answer "is this getting
 * better or worse?" — the only question that tells you whether a lever worked.
 * Tempdoc 908 §1 measured the SAME ledger with a time axis added and found
 * subagent cost share rising 36% -> 74% over four weeks while the main loop got
 * cheaper — a fact no existing reader could see because none of them bucket by
 * time. This reader adds that axis, following `context-residency.mjs`'s structure
 * (pure `build*` functions + a `print*` per section + `--json`, guarded `main()`).
 *
 * Productionises the throwaway readers `tmp/tokeff2/{weekly,w2,spawnweek}.mjs`
 * (908 §1.4) — the SECOND time in one week a token-efficiency question needed a
 * throwaway `tmp/` script (886 needed `tmp/tokeff/{deep,deep3,deep4}.mjs` for the
 * same reason); by `structural-defects-no-repeat` that repeat is sufficient
 * justification on its own.
 *
 * SECTIONS (a) and (c) read the neutral ledger (`lib/ledger/index.mjs`) and price
 * every call via `spawn-economics.mjs`'s exported `costOfCall` — REUSED, not
 * reimplemented, so a pricing-table update lands once. SECTION (b) shells out to
 * `git log --first-parent --numstat` because PR delivery is not a ledger concept;
 * ADR-0045 makes one first-parent commit on `--ref` an exact count of one shipped
 * PR, needing no session->merge attribution at all (908 §4.2 argues the existing
 * attribution join is the wrong tool for this exact use). SECTION (d) is always
 * printed, never conditional, because the retention inversion (908 §4.3) is the
 * higher-priority half of this tempdoc — a maintainer must see the rotation floor
 * every run, not opt into it.
 *
 * BUCKETING, three separate but string-compatible mechanisms (908 §5.1 correctness
 * requirement 4): ledger `Call`s (sections a/c) are bucketed by `isoWeekKey`/
 * `dayKey`, computed from the call's own UTC `ts` — a pure reimplementation of
 * ISO-8601 Monday-start weeks. `git log` (section b) is bucketed by git's OWN
 * `--date=format:'%G-W%V'` / `'%Y-%m-%d'` — not re-derived — so a git-authored
 * bucket key is definitionally correct for git's own commits. The two are joined
 * as STRINGS ("2026-W33"), and this was verified empirically against the real
 * corpus (908 §1.2's 45/64/57/49 PR counts and §1.1/§1.3's per-week ledger figures
 * both reproduced exactly for W32-W35) rather than assumed from the spec alone.
 *
 * TWO "median conventions" are named in 908 §5.1/§6.1 — `sorted[floor(p*len)]`
 * for (a) and `sorted[floor(len/2)]` for (c). For any integer `len`, `0.5 * len`
 * is exact in IEEE-754 double (0.5 is a power of two), so `floor(0.5*len)` and
 * `floor(len/2)` are the SAME value for every input — verified, not assumed. One
 * `percentile()` helper is used everywhere in this file; no figure shifts.
 *
 * A SPAWN's bucket is its FIRST call's `ts` (908 §5.1 correctness requirement 2),
 * never each call's own `ts` — `buildBucketedSpawnRows` groups by `sessionId`
 * first, sorts by `ts`, then assigns the WHOLE spawn to one bucket. Section (a)'s
 * per-CALL bucketing (requirement 1) is a DIFFERENT, deliberately looser rule for
 * per-call metrics (ctx/out, cost share) that do not need spawn-level integrity.
 *
 * PRICING FAILS CLOSED (requirement 3): `costOfCall`'s `priced: false` case is
 * counted in `unpricedCalls`, never summed as $0-silent.
 *
 * Flags: `--by week|day` (default `week`), `--since <ISO>` (default trailing 60
 * days), `--until <ISO>`, `--harness claude-code|codex-cli|all` (default `all`),
 * `--no-git` (skip section b), `--ref <gitref>` (default `origin/main`, falls back
 * to `main` and says so), `--long-spawn-calls <n>` (default 120), `--json`,
 * `--snapshot-path <file>` (default: `trend-snapshot.mjs`'s own default path — override
 * mainly for tests, so a test never reads/writes the real machine snapshot store).
 */

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { listCalls } from './lib/ledger/index.mjs';
import { costOfCall } from './spawn-economics.mjs';
import { round } from './lib/transcript-cost.mjs';
import { discoverProjectDirs, DEFAULT_PROJECTS_ROOT } from './lib/transcript-store.mjs';
// Circular by construction (908 §5.2): trend-snapshot.mjs imports this
// module's build*/bucketing exports to compute what it snapshots; this module
// imports back only the READ side (never buildSnapshotRecord/write*, which
// would be a real cycle at call time). Safe in ESM because neither side is
// touched during top-level module evaluation -- only inside `main()`, which
// runs after both modules have finished loading. Verified empirically
// (`node efficiency-trend.mjs` and `node trend-snapshot.mjs` both load clean).
import { readSnapshotFile, resolveDefaultSnapshotPath } from './trend-snapshot.mjs';

export const DAY_MS = 24 * 60 * 60 * 1000;
export const VALID_HARNESS_ARGS = ['claude-code', 'codex-cli', 'all'];
export const VALID_BY = ['week', 'day'];
export const DEFAULT_LONG_SPAWN_CALLS = 120;

// A path classification a maintainer can audit at a glance (908 §5.1 (b)).
const CODE_PATH_RE = /^(modules\/.*\/src\/|scripts\/|gates\/|contracts\/)/;
const DOCS_PATH_RE = /^docs\//;
const NOISE_PATH_RE = /(^|\/)(package-lock\.json)$|\.lock$|(^|\/)node_modules(\/|$)|\.svg$|\.png$|\.min\./;

// --- generic helpers ---------------------------------------------------------

/** Same floor-index percentile method `context-residency.mjs` uses. */
export function percentile(sortedAsc, p) {
  if (!sortedAsc.length) return 0;
  const idx = Math.min(sortedAsc.length - 1, Math.floor(p * sortedAsc.length));
  return sortedAsc[idx];
}

function fmtK(n) { return n == null ? 'n/a' : `${Math.round(n / 1000)}k`; }
function usd(n) { return n == null ? 'n/a' : `$${n.toFixed(0)}`; }
function pctFmt(n) { return n == null ? 'n/a' : `${n}%`; }

// --- bucketing ---------------------------------------------------------------

/** ISO-8601 (Monday-start) week key in UTC, e.g. `2026-W33`. */
export function isoWeekKey(tsMs) {
  const d = new Date(tsMs);
  const utc = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
  const dayNum = (utc.getUTCDay() + 6) % 7; // Mon=0 .. Sun=6
  utc.setUTCDate(utc.getUTCDate() - dayNum + 3); // Thursday of the same ISO week
  const isoYear = utc.getUTCFullYear();
  const jan4 = new Date(Date.UTC(isoYear, 0, 4));
  const jan4DayNum = (jan4.getUTCDay() + 6) % 7;
  const week1Monday = new Date(jan4);
  week1Monday.setUTCDate(jan4.getUTCDate() - jan4DayNum);
  const weekNum = Math.round((utc - week1Monday) / (7 * DAY_MS)) + 1;
  return `${isoYear}-W${String(weekNum).padStart(2, '0')}`;
}

/** UTC calendar-day key, e.g. `2026-08-20`. */
export function dayKey(tsMs) {
  return new Date(tsMs).toISOString().slice(0, 10);
}

export function bucketKey(tsMs, by) {
  return by === 'day' ? dayKey(tsMs) : isoWeekKey(tsMs);
}

/** Start/end ms (UTC, end exclusive) of the ISO week named by `key` (`YYYY-Www`). */
export function weekBounds(key) {
  const [yearStr, wStr] = key.split('-W');
  const year = Number(yearStr);
  const week = Number(wStr);
  const jan4 = new Date(Date.UTC(year, 0, 4));
  const jan4DayNum = (jan4.getUTCDay() + 6) % 7;
  const week1Monday = new Date(jan4);
  week1Monday.setUTCDate(jan4.getUTCDate() - jan4DayNum);
  const startMs = week1Monday.getTime() + (week - 1) * 7 * DAY_MS;
  return { startMs, endMs: startMs + 7 * DAY_MS };
}

/** Start/end ms (UTC, end exclusive) of the calendar day named by `key` (`YYYY-MM-DD`). */
export function dayBounds(key) {
  const startMs = Date.parse(`${key}T00:00:00.000Z`);
  return { startMs, endMs: startMs + DAY_MS };
}

export function bucketBounds(key, by) {
  return by === 'day' ? dayBounds(key) : weekBounds(key);
}

/**
 * True when the bucket named `key` overlaps `[sinceMs, untilMs]` at all — the
 * same coarse, bucket-level overlap test a snapshot row (which has no
 * per-call `ts` left to filter on) needs to respect a caller's `--since/
 * --until`. `null` on either bound means unbounded on that side, matching
 * `lib/ledger/index.mjs`'s own `inTsWindow` convention. A bucket whose END is
 * at-or-before `sinceMs`, or whose START is at-or-after `untilMs`, has zero
 * overlap and is excluded — this is what stops a snapshot record from a
 * bucket entirely outside the requested window from being admitted (908
 * defect: the merge previously filtered only by harness/by, never by window).
 */
export function bucketOverlapsWindow(key, by, sinceMs, untilMs) {
  const bounds = bucketBounds(key, by);
  if (sinceMs != null && bounds.endMs <= sinceMs) return false;
  if (untilMs != null && bounds.startMs >= untilMs) return false;
  return true;
}

function tsMsOf(call) {
  const t = call.ts ? Date.parse(call.ts) : NaN;
  return Number.isNaN(t) ? null : t;
}

// --- (a) leading indicators (denominator-free) --------------------------------

/**
 * One row per bucket: calls, cost, ctx/out, $/M-output, main/sub p50 context,
 * subagent cost share. Bucketed per-CALL by each call's own `ts` (requirement 1)
 * — a call with an unparsable/absent `ts` cannot be bucketed and is excluded
 * (never silently folded into an arbitrary bucket).
 */
export function buildLeadingIndicators(calls, by) {
  const buckets = new Map();
  let excludedUnbucketable = 0;
  for (const c of calls) {
    const tsMs = tsMsOf(c);
    if (tsMs == null) { excludedUnbucketable += 1; continue; }
    const key = bucketKey(tsMs, by);
    let b = buckets.get(key);
    if (!b) {
      b = {
        bucket: key, calls: 0, costUsd: 0, unpricedCalls: 0,
        totalContextTokens: 0, totalOutputTokens: 0,
        mainCtx: [], subCtx: [], mainCostUsd: 0, subCostUsd: 0,
      };
      buckets.set(key, b);
    }
    b.calls += 1;
    b.totalContextTokens += c.contextTokens;
    b.totalOutputTokens += c.tokens.output || 0;
    const { usd: callUsd, priced } = costOfCall(c);
    const isMain = c.lineage.kind === 'main';
    if (priced) {
      b.costUsd += callUsd;
      if (isMain) b.mainCostUsd += callUsd; else b.subCostUsd += callUsd;
    } else {
      b.unpricedCalls += 1;
    }
    if (isMain) b.mainCtx.push(c.contextTokens); else b.subCtx.push(c.contextTokens);
  }
  const rows = [];
  for (const b of buckets.values()) {
    const mainSorted = b.mainCtx.slice().sort((x, y) => x - y);
    const subSorted = b.subCtx.slice().sort((x, y) => x - y);
    const pricedTotal = b.mainCostUsd + b.subCostUsd;
    rows.push({
      bucket: b.bucket,
      calls: b.calls,
      costUsd: round(b.costUsd, 2),
      unpricedCalls: b.unpricedCalls,
      ctxOut: b.totalOutputTokens ? Math.round(b.totalContextTokens / b.totalOutputTokens) : null,
      costPerMOut: b.totalOutputTokens ? round((b.costUsd * 1e6) / b.totalOutputTokens, 0) : null,
      mainP50Ctx: mainSorted.length ? percentile(mainSorted, 0.5) : null,
      subP50Ctx: subSorted.length ? percentile(subSorted, 0.5) : null,
      subCostSharePct: pricedTotal ? round((100 * b.subCostUsd) / pricedTotal, 0) : null,
    });
  }
  rows.sort((x, y) => x.bucket.localeCompare(y.bucket));
  return { rows, excludedUnbucketable };
}

// --- spawn grouping + (c) spawn tail --------------------------------------------

/**
 * Group Claude spawn/fork Calls by `sessionId` (one spawn) and assign the WHOLE
 * spawn to the bucket of its FIRST call's `ts` (requirement 2) — never smearing
 * one spawn's cost across two buckets. `calls` must already be filtered to
 * non-synthetic, non-main-lineage Claude Calls (same contract `spawn-economics.mjs`
 * uses for its own `buildSpawnRows`).
 */
export function buildBucketedSpawnRows(calls, by) {
  const bySession = new Map();
  for (const c of calls) {
    if (!bySession.has(c.sessionId)) bySession.set(c.sessionId, []);
    bySession.get(c.sessionId).push(c);
  }
  const rows = [];
  for (const [sessionId, arr] of bySession) {
    arr.sort((x, y) => (tsMsOf(x) ?? 0) - (tsMsOf(y) ?? 0));
    const firstTsMs = tsMsOf(arr[0]);
    if (firstTsMs == null) continue; // cannot bucket a spawn with no dateable first call
    let costUsd = 0;
    let unpricedCalls = 0;
    let peakContextTokens = 0;
    for (const c of arr) {
      if (c.contextTokens > peakContextTokens) peakContextTokens = c.contextTokens;
      const { usd: callUsd, priced } = costOfCall(c);
      if (priced) costUsd += callUsd; else unpricedCalls += 1;
    }
    rows.push({ sessionId, bucket: bucketKey(firstTsMs, by), calls: arr.length, peakContextTokens, costUsd, unpricedCalls });
  }
  return rows;
}

/** One row per bucket: spawn count, median calls/spawn, median peak ctx, $/spawn, long-spawn tail. */
export function buildSpawnTail(spawnRows, longSpawnCalls = DEFAULT_LONG_SPAWN_CALLS) {
  const byBucket = new Map();
  for (const r of spawnRows) {
    if (!byBucket.has(r.bucket)) byBucket.set(r.bucket, []);
    byBucket.get(r.bucket).push(r);
  }
  const rows = [];
  for (const [bucket, spawns] of byBucket) {
    const callsSorted = spawns.map((s) => s.calls).sort((x, y) => x - y);
    const peaksSorted = spawns.map((s) => s.peakContextTokens).sort((x, y) => x - y);
    const totalCost = spawns.reduce((a, s) => a + s.costUsd, 0);
    const unpricedCalls = spawns.reduce((a, s) => a + s.unpricedCalls, 0);
    const long = spawns.filter((s) => s.calls >= longSpawnCalls);
    const longCost = long.reduce((a, s) => a + s.costUsd, 0);
    rows.push({
      bucket,
      spawns: spawns.length,
      medCalls: percentile(callsSorted, 0.5),
      medPeakCtx: percentile(peaksSorted, 0.5),
      costPerSpawn: spawns.length ? round(totalCost / spawns.length, 1) : null,
      longSpawns: long.length,
      longCostSharePct: totalCost ? round((100 * longCost) / totalCost, 0) : null,
      unpricedCalls,
    });
  }
  rows.sort((x, y) => x.bucket.localeCompare(y.bucket));
  return rows;
}

// --- (b) delivery (git) --------------------------------------------------------

export function classifyPath(p) {
  if (NOISE_PATH_RE.test(p)) return 'noise';
  if (CODE_PATH_RE.test(p)) return 'code';
  if (DOCS_PATH_RE.test(p)) return 'docs';
  return 'other';
}

const COMMIT_MARK = '@@efficiency-trend-commit@@';

/**
 * Resolve a usable git ref: `ref` if it verifies, else `main` (reported via
 * `fallback: true`), else `null` (git/ref unavailable — never a fabricated
 * denominator, per 908 §5.1 (b)).
 */
export function resolveGitRef({ cwd, ref = 'origin/main' } = {}) {
  try {
    execFileSync('git', ['rev-parse', '--verify', `${ref}^{commit}`], { cwd, stdio: 'ignore' });
    return { ref, fallback: false };
  } catch { /* fall through to main */ }
  if (ref !== 'main') {
    try {
      execFileSync('git', ['rev-parse', '--verify', 'main^{commit}'], { cwd, stdio: 'ignore' });
      return { ref: 'main', fallback: true };
    } catch { /* fall through to unavailable */ }
  }
  return null;
}

/**
 * Shell out to `git log --first-parent --numstat`, bucketing by GIT'S OWN
 * `--date=format` (never re-derived) — see module header for why this is safer
 * than joining on a re-implemented date computation. Returns one row per raw
 * commit: `{bucket, files: [{added, deleted, path}]}`. A binary file's `-/-`
 * counts as 0 (never NaN).
 */
export function gitDeliveryRawCommits({ cwd, ref, sinceMs, untilMs, by }) {
  const dateFmt = by === 'day' ? '%Y-%m-%d' : '%G-W%V';
  const args = [
    'log', ref, '--first-parent', '--numstat',
    `--pretty=format:${COMMIT_MARK}%H|%cd`, `--date=format:${dateFmt}`,
  ];
  if (sinceMs != null) args.push(`--since=${new Date(sinceMs).toISOString()}`);
  if (untilMs != null) args.push(`--until=${new Date(untilMs).toISOString()}`);
  const out = execFileSync('git', args, { cwd, encoding: 'utf8', maxBuffer: 1024 * 1024 * 128 });
  const commits = [];
  let cur = null;
  for (const line of out.split('\n')) {
    if (line.startsWith(COMMIT_MARK)) {
      if (cur) commits.push(cur);
      const rest = line.slice(COMMIT_MARK.length);
      const sep = rest.indexOf('|');
      cur = { hash: rest.slice(0, sep), bucket: rest.slice(sep + 1), files: [] };
    } else if (line.trim() && cur) {
      const parts = line.split('\t');
      if (parts.length === 3) {
        const [a, d, f] = parts;
        cur.files.push({ added: a === '-' ? 0 : Number(a), deleted: d === '-' ? 0 : Number(d), path: f });
      }
    }
  }
  if (cur) commits.push(cur);
  return commits;
}

/**
 * Pure aggregation over raw commits (testable with crafted fixtures, no git
 * needed). `costByBucket` is a `bucket -> costUsd` map, normally the same
 * bucket's total from `buildLeadingIndicators` — 908 §1.2b's whole argument is
 * that BOTH denominators (per-PR, per-code-line) must print side by side, so
 * both are always computed together here, never one without the other.
 */
export function buildDeliveryRows(commits, costByBucket = new Map()) {
  const byBucket = new Map();
  for (const c of commits) {
    let b = byBucket.get(c.bucket);
    if (!b) { b = { bucket: c.bucket, prCount: 0, churnPerPR: [], filesPerPR: [], codeChurn: 0 }; byBucket.set(c.bucket, b); }
    b.prCount += 1;
    let churn = 0;
    for (const f of c.files) {
      churn += f.added + f.deleted;
      if (classifyPath(f.path) === 'code') b.codeChurn += f.added + f.deleted;
    }
    b.churnPerPR.push(churn);
    b.filesPerPR.push(c.files.length);
  }
  const rows = [];
  for (const b of byBucket.values()) {
    const churnSorted = b.churnPerPR.slice().sort((x, y) => x - y);
    const filesSorted = b.filesPerPR.slice().sort((x, y) => x - y);
    const costUsd = costByBucket.has(b.bucket) ? costByBucket.get(b.bucket) : null;
    rows.push({
      bucket: b.bucket,
      prCount: b.prCount,
      medChurnPerPR: percentile(churnSorted, 0.5),
      medFilesPerPR: percentile(filesSorted, 0.5),
      codeChurn: b.codeChurn,
      costUsd,
      costPerLandedPR: costUsd != null ? round(costUsd / b.prCount, 0) : null,
      costPerKCodeLines: (costUsd != null && b.codeChurn > 0) ? round((costUsd * 1000) / b.codeChurn, 1) : null,
    });
  }
  rows.sort((x, y) => x.bucket.localeCompare(y.bucket));
  return rows;
}

/**
 * 908 §1.2b's "size control" retraction, made permanent: a bare cost-per-PR
 * trend is confounded by PR size, so this warning names the observed
 * week-to-week swing in the SIZE-CONTROLLED metric and refuses to let a
 * maintainer read a short slope as signal. Excludes any bucket in
 * `excludeBuckets` (TRUNCATED/PARTIAL — requirement 5) from the swing itself.
 */
export function deliveryPowerWarning(rows, excludeBuckets = new Set()) {
  const eligible = rows.filter((r) => !excludeBuckets.has(r.bucket) && r.costPerKCodeLines != null);
  if (eligible.length < 2) {
    return 'power warning: fewer than 2 complete buckets with a costed $/1k-code-lines figure — no swing computable.';
  }
  const vals = eligible.map((r) => r.costPerKCodeLines);
  const swing = Math.max(...vals) / Math.min(...vals);
  return `power warning: $/1k-code-lines swings ${swing.toFixed(1)}x week-to-week across ${eligible.length} complete buckets — `
    + `at this bucket count a slope is not statistical signal (tempdoc 908 §7).`;
}

// --- (d) corpus honesty ---------------------------------------------------------

/** Oldest surviving Claude Code transcript mtime across every discovered project dir. */
export function findOldestTranscriptMtimeMs({ projectsRoot = DEFAULT_PROJECTS_ROOT, projectFilter } = {}) {
  let oldest = null;
  const dirs = projectFilter ? discoverProjectDirs(projectsRoot, projectFilter) : discoverProjectDirs(projectsRoot);
  for (const dir of dirs) {
    let files;
    try { files = fs.readdirSync(dir.path, { withFileTypes: true }); } catch { continue; }
    for (const f of files) {
      if (!f.isFile() || !f.name.endsWith('.jsonl')) continue;
      let stat;
      try { stat = fs.statSync(path.join(dir.path, f.name)); } catch { continue; }
      if (oldest == null || stat.mtimeMs < oldest) oldest = stat.mtimeMs;
    }
  }
  return oldest;
}

/**
 * `truncated`: the bucket starts before `floorMs + 1 day` (the rotation floor
 * itself is fuzzy by up to a day of file-write jitter, so a 1-day margin avoids
 * flagging a bucket that is actually complete). `partial`: the bucket extends
 * past `nowMs`. `floorMs == null` (no transcripts discovered at all) never
 * fabricates `truncated: true` — it reports `truncated: false` and the caller
 * is expected to notice `floorMs` itself is null.
 */
export function classifyBucket(bounds, floorMs, nowMs) {
  const truncated = floorMs != null && bounds.startMs < floorMs + DAY_MS;
  const partial = bounds.endMs > nowMs;
  return { truncated, partial };
}

// --- snapshot merge (908 §5.2 hook) ---------------------------------------------

/**
 * Merge live-computed rows with snapshot rows for buckets absent from `liveRows`
 * (908 §5.2 — the snapshot survives buckets the 30-day transcript rotation has
 * already deleted). Live always wins on a shared key; every row is labelled
 * `source: 'live'|'snapshot'`.
 */
export function mergeLiveAndSnapshot(liveRows, snapshotRows, keyOf = (r) => r.bucket) {
  const liveKeys = new Set(liveRows.map(keyOf));
  const merged = liveRows.map((r) => ({ ...r, source: 'live' }));
  for (const s of snapshotRows) {
    if (!liveKeys.has(keyOf(s))) merged.push({ ...s, source: 'snapshot' });
  }
  merged.sort((a, b) => keyOf(a).localeCompare(keyOf(b)));
  return merged;
}

// --- CLI -----------------------------------------------------------------------

function parseArgs(argv) {
  const opts = {
    by: 'week', since: null, until: null, harness: 'all', noGit: false,
    ref: 'origin/main', longSpawnCalls: DEFAULT_LONG_SPAWN_CALLS, json: false, snapshotPath: null,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--by') opts.by = argv[++i];
    else if (a === '--since') opts.since = argv[++i];
    else if (a === '--until') opts.until = argv[++i];
    else if (a === '--harness') opts.harness = argv[++i];
    else if (a === '--no-git') opts.noGit = true;
    else if (a === '--ref') opts.ref = argv[++i];
    else if (a === '--long-spawn-calls') opts.longSpawnCalls = Number(argv[++i]);
    else if (a === '--json') opts.json = true;
    else if (a === '--snapshot-path') opts.snapshotPath = argv[++i];
  }
  return opts;
}

function attachFlags(rows, flagsByBucket) {
  return rows.map((r) => ({ ...r, ...(flagsByBucket.get(r.bucket) ?? { truncated: false, partial: false }) }));
}

/**
 * Three-state completeness for a leading/spawn-tail row (908 defect 3). A
 * `source: 'live'` row is always judged against TODAY's floor/clock — the
 * live scan genuinely cannot see past it, so `classifyBucket` is correct
 * as-is. A `source: 'snapshot'` row must NOT be re-judged against today's
 * floor: it carries its OWN `truncatedAtCapture`/`partialAtCapture`,
 * recorded by `trend-snapshot.mjs` at write time against the floor/clock
 * THEN, precisely so a bucket rescued while complete does not become
 * permanently TRUNCATED once the floor moves past it. A snapshot row
 * missing either field (a legacy record written before this field existed)
 * is a named residual, `unknown: true` — never guessed `false`, the same
 * "we don't know" precedent as the ledger's `in-ttl-undetermined` /
 * `unfilterableTs`.
 */
export function resolveRowFlags(row, floorMs, nowMs, by) {
  if (row.source === 'snapshot') {
    if (typeof row.truncatedAtCapture === 'boolean' && typeof row.partialAtCapture === 'boolean') {
      return { truncated: row.truncatedAtCapture, partial: row.partialAtCapture, unknown: false };
    }
    return { truncated: false, partial: false, unknown: true };
  }
  const { truncated, partial } = classifyBucket(bucketBounds(row.bucket, by), floorMs, nowMs);
  return { truncated, partial, unknown: false };
}

function attachRowFlags(rows, floorMs, nowMs, by) {
  return rows.map((r) => ({ ...r, ...resolveRowFlags(r, floorMs, nowMs, by) }));
}

/** UNKNOWN takes precedence in the printed label -- a legacy record is not "complete", it is unjudged. */
function flagLabels(r) {
  if (r.unknown) return 'UNKNOWN';
  return [r.truncated ? 'TRUNCATED' : null, r.partial ? 'PARTIAL' : null].filter(Boolean).join(',');
}

function printLeading(rows) {
  console.log('\n=== (a) leading indicators (denominator-free) ===');
  console.log('bucket        calls    cost  ctx/out  $/M-out  mainP50  subP50  subShare  source    flags');
  for (const r of rows) {
    const flags = flagLabels(r);
    console.log(`${r.bucket.padEnd(12)} ${String(r.calls).padStart(7)} ${usd(r.costUsd).padStart(7)} `
      + `${String(r.ctxOut ?? 'n/a').padStart(8)} ${usd(r.costPerMOut).padStart(8)} `
      + `${fmtK(r.mainP50Ctx).padStart(8)} ${fmtK(r.subP50Ctx).padStart(7)} ${pctFmt(r.subCostSharePct).padStart(9)}  `
      + `${(r.source ?? 'live').padEnd(8)}  ${flags}`);
    if (r.unpricedCalls) console.log(`  (${r.unpricedCalls} unpriced calls excluded from cost)`);
  }
}

function printDelivery(delivery) {
  console.log('\n=== (b) delivery (both denominators, side by side — 908 §1.2b) ===');
  if (!delivery.available) {
    console.log(`  unavailable: ${delivery.reason}`);
    return;
  }
  if (delivery.refFallback) console.log(`  (ref "${delivery.requestedRef}" did not resolve; fell back to "${delivery.ref}")`);
  console.log('bucket          PRs  medChurn/PR  medFiles/PR  codeChurn  $/1kCodeLines   $/PR  flags');
  for (const r of delivery.rows) {
    const flags = [r.truncated ? 'TRUNCATED' : null, r.partial ? 'PARTIAL' : null].filter(Boolean).join(',');
    console.log(`${r.bucket.padEnd(14)} ${String(r.prCount).padStart(4)} ${String(r.medChurnPerPR).padStart(12)} `
      + `${String(r.medFilesPerPR).padStart(12)} ${String(r.codeChurn).padStart(10)} `
      + `${(r.costPerKCodeLines == null ? 'n/a' : `$${r.costPerKCodeLines.toFixed(1)}`).padStart(14)} `
      + `${usd(r.costPerLandedPR).padStart(6)}  ${flags}`);
  }
  console.log(`  ${delivery.powerWarning}`);
}

function printSpawnTail(rows, longSpawnCalls) {
  console.log(`\n=== (c) spawn tail (>= ${longSpawnCalls} calls is "long") ===`);
  console.log('bucket        spawns  medCalls  medPeakCtx  $/spawn  long  longCostShare  source    flags');
  for (const r of rows) {
    const flags = flagLabels(r);
    console.log(`${r.bucket.padEnd(12)} ${String(r.spawns).padStart(7)} ${String(r.medCalls).padStart(9)} `
      + `${fmtK(r.medPeakCtx).padStart(11)} ${(r.costPerSpawn == null ? 'n/a' : `$${r.costPerSpawn.toFixed(1)}`).padStart(8)} ${String(r.longSpawns).padStart(5)} `
      + `${pctFmt(r.longCostSharePct).padStart(14)}  ${(r.source ?? 'live').padEnd(8)}  ${flags}`);
  }
}

function printCorpusHonesty(floorMs, truncatedBuckets, partialBuckets, sourceCounts, unknownCounts) {
  console.log('\n=== (d) corpus honesty ===');
  console.log(`  oldest surviving transcript mtime (rotation floor): ${floorMs == null ? 'n/a — no transcripts discovered' : new Date(floorMs).toISOString()}`);
  console.log(`  TRUNCATED buckets (start before floor+1day, LIVE rows only): ${truncatedBuckets.length ? truncatedBuckets.join(', ') : 'none'}`);
  console.log(`  PARTIAL buckets (extend past now, LIVE rows only): ${partialBuckets.length ? partialBuckets.join(', ') : 'none'}`);
  console.log(`  row sources: leading ${sourceCounts.leading.live} live / ${sourceCounts.leading.snapshot} snapshot; `
    + `spawn tail ${sourceCounts.spawnTail.live} live / ${sourceCounts.spawnTail.snapshot} snapshot`);
  console.log(`  UNKNOWN completeness (legacy snapshot record, captured before truncatedAtCapture/partialAtCapture existed): `
    + `leading ${unknownCounts.leading}, spawn tail ${unknownCounts.spawnTail}`);
  console.log('  No trend arithmetic in this report crosses a TRUNCATED, PARTIAL, or UNKNOWN row.');
}

export function countBySource(rows) {
  return {
    live: rows.filter((r) => (r.source ?? 'live') === 'live').length,
    snapshot: rows.filter((r) => r.source === 'snapshot').length,
  };
}

export function countUnknown(rows) {
  return rows.filter((r) => r.unknown === true).length;
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (!VALID_HARNESS_ARGS.includes(opts.harness)) {
    console.error(`efficiency-trend: unknown --harness "${opts.harness}" (expected one of: ${VALID_HARNESS_ARGS.join(', ')})`);
    process.exit(2);
  }
  if (!VALID_BY.includes(opts.by)) {
    console.error(`efficiency-trend: unknown --by "${opts.by}" (expected one of: ${VALID_BY.join(', ')})`);
    process.exit(2);
  }
  const harnesses = opts.harness === 'all' ? ['claude-code', 'codex-cli'] : [opts.harness];
  const nowMs = Date.now();
  const sinceMs = opts.since ? Date.parse(opts.since) : nowMs - 60 * DAY_MS;
  const untilMs = opts.until ? Date.parse(opts.until) : null;

  const { calls } = listCalls({ harnesses, sinceMs, untilMs });
  const nonSynthetic = calls.filter((c) => !c.synthetic);

  const { rows: liveLeadingRows, excludedUnbucketable } = buildLeadingIndicators(nonSynthetic, opts.by);

  const spawnCalls = nonSynthetic.filter((c) => c.harness === 'claude-code' && c.lineage.kind !== 'main');
  const spawnRows = buildBucketedSpawnRows(spawnCalls, opts.by);
  const liveSpawnTailRows = buildSpawnTail(spawnRows, opts.longSpawnCalls);

  // --- 908 §5.2: fill buckets the 30-day transcript rotation has already
  // deleted from a snapshot taken before they rotated away. Only meaningful
  // for a SINGLE requested harness (a snapshot record is per-harness; combining
  // harnesses into one row, as `--harness all` does live, has no per-harness
  // snapshot equivalent to merge against). Live data always wins on a shared
  // bucket key (`mergeLiveAndSnapshot`); this only ever FILLS a gap. A
  // snapshot record must ALSO overlap the requested `--since/--until` window
  // (`bucketOverlapsWindow`) — the snapshot file accumulates buckets across
  // every past run, so without this a narrow window would silently admit
  // rows from weeks the caller never asked for (908 defect 1).
  let leadingRows = liveLeadingRows;
  let spawnTailRows = liveSpawnTailRows;
  if (harnesses.length === 1) {
    const snapshotRecords = readSnapshotFile(opts.snapshotPath ?? resolveDefaultSnapshotPath())
      .filter((r) => r.harness === harnesses[0] && r.by === opts.by && bucketOverlapsWindow(r.bucket, opts.by, sinceMs, untilMs));
    // Carry the CAPTURE-TIME completeness flags along with the aggregate
    // fields (908 defect 3) — they live at the top level of the snapshot
    // record, not inside `.leading`/`.spawnTail`, so they must be spread in
    // explicitly or a merged row silently loses them.
    const snapshotLeadingRows = snapshotRecords.filter((r) => r.leading)
      .map((r) => ({ bucket: r.bucket, truncatedAtCapture: r.truncatedAtCapture, partialAtCapture: r.partialAtCapture, ...r.leading }));
    const snapshotSpawnTailRows = snapshotRecords.filter((r) => r.spawnTail)
      .map((r) => ({ bucket: r.bucket, truncatedAtCapture: r.truncatedAtCapture, partialAtCapture: r.partialAtCapture, ...r.spawnTail }));
    leadingRows = mergeLiveAndSnapshot(liveLeadingRows, snapshotLeadingRows);
    spawnTailRows = mergeLiveAndSnapshot(liveSpawnTailRows, snapshotSpawnTailRows);
  } else {
    leadingRows = liveLeadingRows.map((r) => ({ ...r, source: 'live' }));
    spawnTailRows = liveSpawnTailRows.map((r) => ({ ...r, source: 'live' }));
  }

  let delivery;
  if (opts.noGit) {
    delivery = { available: false, reason: '--no-git' };
  } else {
    const resolved = resolveGitRef({ cwd: path.dirname(fileURLToPath(import.meta.url)), ref: opts.ref });
    if (!resolved) {
      delivery = { available: false, reason: `neither "${opts.ref}" nor "main" resolved to a commit` };
    } else {
      try {
        const commits = gitDeliveryRawCommits({
          cwd: path.dirname(fileURLToPath(import.meta.url)), ref: resolved.ref, sinceMs, untilMs, by: opts.by,
        });
        const costByBucket = new Map(leadingRows.map((r) => [r.bucket, r.costUsd]));
        const deliveryRows = buildDeliveryRows(commits, costByBucket);
        delivery = {
          available: true, ref: resolved.ref, refFallback: resolved.fallback, requestedRef: opts.ref,
          rows: deliveryRows,
        };
      } catch (e) {
        delivery = { available: false, reason: `git log failed: ${e.message}` };
      }
    }
  }

  // --- (d) corpus honesty + flag every bucket in every section --------------
  const floorMs = harnesses.includes('claude-code') ? findOldestTranscriptMtimeMs() : null;
  const allBucketKeys = new Set([
    ...leadingRows.map((r) => r.bucket),
    ...spawnTailRows.map((r) => r.bucket),
    ...(delivery.available ? delivery.rows.map((r) => r.bucket) : []),
  ]);
  const flagsByBucket = new Map();
  for (const key of allBucketKeys) {
    flagsByBucket.set(key, classifyBucket(bucketBounds(key, opts.by), floorMs, nowMs));
  }

  // 908 defect 4: truncatedBuckets/partialBuckets are printed "LIVE rows
  // only" and feed excludeFromPower -- they must be derived from buckets that
  // actually HAVE a `source: 'live'` row, not from every bucket key any
  // section happens to mention. Deriving from `allBucketKeys` named a bucket
  // whose ONLY row is `source: 'snapshot'` (e.g. UNKNOWN) as TRUNCATED,
  // contradicting that row's own flag -- two different, and one false.
  const liveBucketKeys = new Set([
    ...leadingRows.filter((r) => r.source === 'live').map((r) => r.bucket),
    ...spawnTailRows.filter((r) => r.source === 'live').map((r) => r.bucket),
  ]);
  const truncatedBuckets = [];
  const partialBuckets = [];
  for (const key of liveBucketKeys) {
    const flags = flagsByBucket.get(key);
    if (flags.truncated) truncatedBuckets.push(key);
    if (flags.partial) partialBuckets.push(key);
  }
  truncatedBuckets.sort();
  partialBuckets.sort();
  const excludeFromPower = new Set([...truncatedBuckets, ...partialBuckets]);

  // Leading/spawn-tail rows resolve flags PER ROW (908 defect 3) — a
  // snapshot-sourced row uses its own captured completeness, never today's
  // floor. Delivery has no snapshot equivalent (git history never rotates),
  // so it stays on the uniform bucket-floor `flagsByBucket` derivation.
  const leadingFlagged = attachRowFlags(leadingRows, floorMs, nowMs, opts.by);
  const spawnTailFlagged = attachRowFlags(spawnTailRows, floorMs, nowMs, opts.by);
  if (delivery.available) {
    delivery.rows = attachFlags(delivery.rows, flagsByBucket);
    delivery.powerWarning = deliveryPowerWarning(delivery.rows, excludeFromPower);
  }
  const sourceCounts = { leading: countBySource(leadingFlagged), spawnTail: countBySource(spawnTailFlagged) };
  const unknownCounts = { leading: countUnknown(leadingFlagged), spawnTail: countUnknown(spawnTailFlagged) };

  if (opts.json) {
    console.log(JSON.stringify({
      by: opts.by, harnesses, since: new Date(sinceMs).toISOString(), until: untilMs ? new Date(untilMs).toISOString() : null,
      longSpawnCalls: opts.longSpawnCalls,
      leading: leadingFlagged, excludedUnbucketable,
      spawnTail: spawnTailFlagged,
      delivery,
      corpusHonesty: {
        rotationFloorMs: floorMs, rotationFloorIso: floorMs == null ? null : new Date(floorMs).toISOString(),
        truncatedBuckets, partialBuckets, sourceCounts, unknownCounts,
      },
    }, null, 2));
    return;
  }

  console.log(`efficiency-trend [${harnesses.join(',')}] --by ${opts.by} — since ${new Date(sinceMs).toISOString()}${untilMs ? ` until ${new Date(untilMs).toISOString()}` : ''}`);
  if (excludedUnbucketable) console.log(`  (${excludedUnbucketable} calls excluded: unparsable/absent ts, cannot be bucketed)`);
  printLeading(leadingFlagged);
  printDelivery(delivery);
  printSpawnTail(spawnTailFlagged, opts.longSpawnCalls);
  printCorpusHonesty(floorMs, truncatedBuckets, partialBuckets, sourceCounts, unknownCounts);
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) main();
