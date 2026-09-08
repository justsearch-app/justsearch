#!/usr/bin/env node

/**
 * Recover session→merge links predating the Session-Id convention, from
 * observation-shard adds (tempdoc 856 §4).
 *
 * Observation shards are named by session UUID and ride into `main` inside the
 * PR carrying that session's work, so the commit that ADDS a shard is a
 * durable session→merge link already sitting in public history — the only
 * historical source that survives transcript rotation.
 *
 *   node scripts/agent-analytics/recover-merge-links.mjs            # DRY RUN
 *   node scripts/agent-analytics/recover-merge-links.mjs --json
 *   node scripts/agent-analytics/recover-merge-links.mjs --apply    # writes
 *
 * THIS IS INFERENCE, NOT FACT, and the design says so in three places at once:
 *
 *  1. Only commits adding EXACTLY ONE shard qualify. 856 §4 measured the
 *     falsifier "a session cannot merge a PR after it ended" against surviving
 *     transcripts: single-shard commits were wrong 4/45 (8.9%), multi-shard
 *     commits 10/18 (55.6%). A multi-shard commit sweeps in other sessions'
 *     shards — one commit adding five shards was claimed by three sessions that
 *     had ended 1, 1 and 7 days earlier. The restriction is part of the design,
 *     not a tuning knob.
 *  2. Only commits whose subject is a squash PR commit `(#N)` qualify.
 *  3. Every emitted row is source:'shard-inference', kind:'inference'. Never
 *     fact tier — 856 §3.1: without that, a recovered row is indistinguishable
 *     from an observed one and the fact tier silently absorbs an inference.
 *
 * Dry-run is the default because this writes into a live measurement file that
 * baseline-economics.mjs reads (the delegate-by-default falsifier was judged and
 * closed 2026-09-07, tempdoc 948; the ledger remains the merge-cost authority). §7: recovery and
 * filtering push the merge count in opposite directions and must be reported as
 * separate labelled components, never netted into one moved number — so the
 * report prints candidates, rejects and skips separately rather than a total.
 */

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  repoRoot, MERGE_LINK_SOURCES, buildMergeLinkRow, normalizeMergeLinkRow, loadNdjsonArray,
} from './lib/telemetry-io.mjs';
import { isSquashPrSubject, isPlausibleSessionId } from './merge-links.mjs';
// The ledger lives in the MAIN checkout, not the worktree you happen to be in.
// resolveDefaultMergesPath resolves it via `git rev-parse --git-common-dir`
// precisely so this works from any worktree. Defaulting to the worktree's own
// (empty) tmp/agent-telemetry would make --apply create a SECOND ledger holding
// rows that already exist in the first — a fork of the derived cache, which is
// the exact failure 856 exists to remove.
import { resolveDefaultMergesPath } from './baseline-economics.mjs';

export const SHARD_PREFIX = 'docs/observations.d/';

/**
 * Measured in 856 §4 against surviving transcripts. Printed on every run: a
 * consumer must not read these rows as clean, and a number that only lives in
 * a tempdoc is a number nobody downstream sees.
 */
export const MEASURED = Object.freeze({
  singleShardErrorPct: 8.9,      // 4 / 45
  multiShardErrorPct: 55.6,      // 10 / 18
  transcriptCoverage: '63 of 158 candidate links had a surviving transcript',
  unverifiablePct: 60,           // ~60% of candidates cannot be checked at all
});

const REC = String.fromCharCode(0x00);
const FIELD = String.fromCharCode(0x1f);
// Composite key separator for (session_id, merge_commit) dedup. `|` matches
// baseline-economics.mjs:180's dedupeMergeRows so the two agree on what "the
// same pair" means; it is unambiguous because neither half can contain it —
// session ids are [A-Za-z0-9._-] (note-observation.mjs's sanitizeId) and
// commit hashes are hex. This comment is the one place that reasoning lives.
const PAIR_SEP = '|';

export const GIT_LOG_ARGS = [
  'log', '--diff-filter=A', '--name-only',
  `--format=%x00%H%x1f%s%x1f%cI`,
  '--', SHARD_PREFIX,
];

/**
 * Session id from a shard basename (no `.md`), stripping the writer suffix.
 *
 * Shards are `<sessionId>[.<writer>]` since tempdoc 862 — keyed by the tree that
 * writes them, because one session spawns many worktrees under the delegate model.
 * This strip is LOAD-BEARING, not cosmetic: `isPlausibleSessionId`'s alphabet
 * (`merge-links.mjs:107`, `/^[A-Za-z0-9._-]{4,80}$/`) ADMITS dots, so an unstripped
 * `<uuid>.<writer>` would not be rejected — it would be accepted and written into
 * session-merges.ndjson as a session that never existed. A silently wrong
 * attribution row in a measurement file a falsifier reads is the exact class 856
 * exists to remove (tempdoc 862 §D.4).
 *
 * The writer suffix is the LAST dot-segment; a name with no dot is a whole session
 * id. That is unambiguous BY CONSTRUCTION, not by assumption about id shape:
 * `shardPathFor` composes both halves with a dot-free sanitizer, so a shard name
 * carries at most one dot and it is always the writer separator. (Relying on
 * "session ids are UUIDs, which have no dots" would be unsound — ids come from
 * $CLAUDE_CODE_SESSION_ID, and `sanitizeId` permits dots, so a dotted id would
 * otherwise mint a bare shard that this function truncates to a session that never
 * existed.) Degrades to the pre-862 behaviour on legacy bare-named shards.
 */
export function sessionIdFromShardName(base) {
  const name = String(base ?? '');
  const dot = name.lastIndexOf('.');
  return dot > 0 ? name.slice(0, dot) : name;
}

/**
 * Parse `git log --diff-filter=A --name-only --format=%x00%H%x1f%s%x1f%cI --
 * docs/observations.d/` into one record per commit. Pure; the test seam.
 *
 * Returns [{ commit, subject, committedAt, shards: string[] }] where `shards`
 * are the session ids of the shard files ADDED by that commit.
 */
export function parseShardAddLog(raw) {
  const out = [];
  for (const chunk of String(raw ?? '').split(REC)) {
    if (!chunk.trim()) continue;
    const [head, ...rest] = chunk.split('\n');
    const [commit, subject, committedAt] = head.split(FIELD);
    if (!commit || !commit.trim()) continue;
    const shards = [];
    for (const line of rest) {
      const file = line.trim();
      if (!file.startsWith(SHARD_PREFIX) || !file.endsWith('.md')) continue;
      const sessionId = sessionIdFromShardName(file.slice(SHARD_PREFIX.length, -'.md'.length));
      if (sessionId && !shards.includes(sessionId)) shards.push(sessionId);
    }
    out.push({
      commit: commit.trim(),
      subject: (subject ?? '').split('\n')[0],
      committedAt: (committedAt ?? '').trim() || null,
      shards,
    });
  }
  return out;
}

/**
 * Split shard-add commits into qualifying candidates and labelled rejects.
 * Rejects are RETURNED, not dropped (856 §7) — a filtered commit is a finding
 * about the recovery, and silently discarding it reproduces the same
 * absent-vs-negative confusion §3.2 fixes.
 */
export function classifyShardCommits(commits) {
  const candidates = [];
  const rejected = [];
  for (const c of commits) {
    if (c.shards.length === 0) {
      rejected.push({ ...c, reason: 'no-shard-file-added' });
    } else if (c.shards.length > 1) {
      rejected.push({ ...c, reason: `multi-shard (${c.shards.length}) — measured ${MEASURED.multiShardErrorPct}% false-positive rate` });
    } else if (!isSquashPrSubject(c.subject)) {
      rejected.push({ ...c, reason: 'subject is not a squash PR commit (#N)' });
    } else if (!isPlausibleSessionId(c.shards[0])) {
      rejected.push({ ...c, reason: `shard filename is not a plausible session id: ${JSON.stringify(c.shards[0])}` });
    } else {
      candidates.push({ ...c, sessionId: c.shards[0] });
    }
  }
  return { candidates, rejected };
}

/** Existing (session_id, merge_commit) pairs, normalized so legacy rows count. */
export function loadLedgerPairs({ ledgerPath = resolveDefaultMergesPath() } = {}) {
  const file = ledgerPath;
  const rows = loadNdjsonArray(file).map(normalizeMergeLinkRow);
  const pairs = new Set();
  for (const r of rows) {
    if (r.session_id && r.merge_commit) pairs.add(`${r.session_id}${PAIR_SEP}${r.merge_commit}`);
  }
  return { file, rows, pairs };
}

/**
 * Build the rows this recovery WOULD write. `ts` is the commit's committer
 * date, not "now": the recovery has no observation moment of its own, and
 * back-dating keeps a recovered link in the week it actually happened. Lag
 * analyses (856 §1) must filter on kind==='fact' anyway.
 */
export function planRecovery({ ledgerPath = resolveDefaultMergesPath(), gitRoot = repoRoot, raw = null } = {}) {
  // The ledger path and the git checkout are separate inputs: history comes
  // from wherever this script runs, the ledger from the main checkout. Keeping
  // them separate is also what lets the tests point the write path at a temp
  // dir while still scanning real history.
  const commits = parseShardAddLog(raw ?? gitLog({ root: gitRoot }));
  const { candidates, rejected } = classifyShardCommits(commits);
  const { file, pairs } = loadLedgerPairs({ ledgerPath });

  const toWrite = [];
  const skippedAlreadyLinked = [];
  const seen = new Set(pairs);
  for (const c of candidates) {
    const key = `${c.sessionId}${PAIR_SEP}${c.commit}`;
    if (seen.has(key)) { skippedAlreadyLinked.push(c); continue; }
    seen.add(key);
    toWrite.push(buildMergeLinkRow({
      sessionId: c.sessionId,
      mergeCommit: c.commit,
      subject: c.subject,
      source: MERGE_LINK_SOURCES.SHARD_INFERENCE,
      ts: c.committedAt,
    }));
  }
  const sessions = new Set(toWrite.map((r) => r.session_id));
  return {
    ledgerFile: file,
    commitsScanned: commits.length,
    candidates,
    rejected,
    skippedAlreadyLinked,
    toWrite,
    distinctSessions: sessions.size,
    // Same hazard as merge-links: baseline-economics' filterMergesToWindow
    // drops a row whose `ts` will not parse, so an undated row is written and
    // then invisible. Surfaced, never back-filled with "now".
    undated: toWrite.filter((r) => !r.ts || Number.isNaN(new Date(r.ts).getTime())),
  };
}

function gitLog({ root = repoRoot } = {}) {
  return execFileSync('git', GIT_LOG_ARGS, {
    cwd: root, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, windowsHide: true,
  });
}

/** Append the planned rows. Only ever reached behind an explicit --apply. */
export function applyRecovery(plan) {
  if (plan.toWrite.length === 0) return 0;
  fs.mkdirSync(path.dirname(plan.ledgerFile), { recursive: true });
  fs.appendFileSync(plan.ledgerFile, plan.toWrite.map((r) => JSON.stringify(r)).join('\n') + '\n', 'utf8');
  return plan.toWrite.length;
}

export function parseArgs(argv) {
  const opts = { apply: false, json: false, limit: 15, ledgerPath: null, help: false };
  const valueFor = (flag, raw) => {
    // A flag whose value is missing (or is the next flag) must ERROR, not fall
    // back to the default — a silent fallback turns a typo into a run against
    // the wrong ledger.
    if (raw === undefined || String(raw).startsWith('--')) throw new Error(`${flag} needs a value`);
    return raw;
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--apply') opts.apply = true;
    else if (arg === '--json') opts.json = true;
    else if (arg === '--limit') {
      const raw = valueFor('--limit', argv[++i]);
      const n = Number(raw);
      // A non-numeric limit yields slice(0, NaN) — an empty listing that reads
      // as "no links found". Refuse instead of printing a lie.
      if (!Number.isInteger(n) || n < 0) throw new Error(`--limit needs a non-negative integer, got ${JSON.stringify(raw)}`);
      opts.limit = n;
    } else if (arg === '--ledger') opts.ledgerPath = path.resolve(valueFor('--ledger', argv[++i]));
    else if (arg === '--help' || arg === '-h') opts.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  return opts;
}

function main() {
  let opts;
  try {
    opts = parseArgs(process.argv.slice(2));
  } catch (e) {
    console.error(`recover-merge-links: ${e.message}`);
    process.exit(2);
  }
  if (opts.help) {
    console.log([
      'Usage: node scripts/agent-analytics/recover-merge-links.mjs [--apply] [--json] [--ledger FILE] [--limit N]',
      '',
      'Recovers session->merge links predating the Session-Id convention from',
      'observation-shard adds (tempdoc 856 §4). DRY RUN by default; --apply',
      'appends. Every emitted row is source:shard-inference, kind:inference.',
      '',
      '  --ledger FILE  session-merges.ndjson to read/write',
      '                 (default: the MAIN checkout\'s, resolved from git-common-dir)',
    ].join('\n'));
    return;
  }

  const plan = planRecovery(opts.ledgerPath ? { ledgerPath: opts.ledgerPath } : {});

  if (opts.json) {
    // Apply BEFORE serializing, so `applied` reports what actually happened
    // rather than what the flag asked for.
    const appended = opts.apply ? applyRecovery(plan) : 0;
    console.log(JSON.stringify({
      kind: 'justsearch-recover-merge-links.v1',
      applied: opts.apply, appended, measured: MEASURED, ...plan,
    }, null, 2));
    return;
  }

  const multi = plan.rejected.filter((r) => r.reason.startsWith('multi-shard')).length;
  const notPr = plan.rejected.filter((r) => r.reason.startsWith('subject is not')).length;
  const other = plan.rejected.length - multi - notPr;

  console.log(`recover-merge-links: ${opts.apply ? 'APPLY' : 'DRY RUN (nothing written — pass --apply to write)'}`);
  console.log(`  ledger: ${plan.ledgerFile}`);
  console.log(`  scanned ${plan.commitsScanned} shard-add commits`);
  console.log(`  qualifying candidates: ${plan.candidates.length} (single-shard AND squash PR subject)`);
  console.log(`  rejected: ${plan.rejected.length} — ${multi} multi-shard, ${notPr} non-PR subject, ${other} other`);
  console.log(`  already in ledger, skipped: ${plan.skippedAlreadyLinked.length}`);
  console.log(`  WOULD WRITE: ${plan.toWrite.length} links across ${plan.distinctSessions} distinct sessions`);
  if (plan.undated.length > 0) {
    console.log(`  WARNING: ${plan.undated.length} of those have no parseable commit date, so`);
    console.log("    baseline-economics' filterMergesToWindow will drop them from every window.");
  }
  console.log('');
  console.log('  THESE ROWS ARE INFERENCE, NOT FACT (tempdoc 856 §4):');
  console.log(`    - measured false-positive rate for single-shard commits: ${MEASURED.singleShardErrorPct}%`);
  console.log(`    - multi-shard commits (${MEASURED.multiShardErrorPct}% error) are excluded by construction`);
  console.log(`    - ${MEASURED.transcriptCoverage}; ~${MEASURED.unverifiablePct}% of candidate links have no`);
  console.log('      surviving transcript and are therefore UNVERIFIABLE — the error rate above is');
  console.log('      unmeasured for the majority of them.');
  console.log(`    - every row is source:'shard-inference', kind:'inference'; a fact-tier reader must exclude them.`);

  if (plan.toWrite.length > 0) {
    console.log('');
    for (const r of plan.toWrite.slice(0, opts.limit)) {
      console.log(`  ${r.session_id.slice(0, 8)} -> ${r.merge_commit.slice(0, 8)} ${String(r.ts).slice(0, 10)} ${r.subject.slice(0, 60)}`);
    }
    if (plan.toWrite.length > opts.limit) console.log(`  … ${plan.toWrite.length - opts.limit} more (use --json for all)`);
  }

  if (opts.apply) {
    const n = applyRecovery(plan);
    console.log('');
    console.log(`recover-merge-links: appended ${n} inference rows to ${plan.ledgerFile}`);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  main();
}
