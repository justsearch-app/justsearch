#!/usr/bin/env node

/**
 * Derive session→merge links from `Session-Id:` lines in commit messages
 * (tempdoc 856 §3).
 *
 * The ledger `tmp/agent-telemetry/session-merges.ndjson` is captured at a
 * moment (worktree teardown) and therefore under-reports by construction:
 * ~50% of squash PRs are linked, swinging 15%–78% week to week depending on
 * whether the moment happened. 856's answer is to put the join key INSIDE the
 * authority — the squash commit message — so the link cannot drift from git,
 * because it is no longer a claim ABOUT git. This module is the reader half:
 * git is the authority, the ndjson demotes to a derived cache.
 *
 * WHY THIS READS `%B` AND NOT `%(trailers:...)`. The first implementation used
 * git's trailer parser and would have derived ZERO links forever. Git parses
 * trailers only from the LAST paragraph of a message, and GitHub appends its
 * own paragraph — `---------` and/or `Co-authored-by:` — after the PR body when
 * it squash-merges. Measured on this repo since 2026-07-16: 262 of 273 squash
 * PR commits carry an appended `Co-authored-by:` line and only 3 carry no
 * appended block at all, so displacement is the DEFAULT path, not an edge case.
 * Reproduced with `git interpret-trailers --parse`: a `Session-Id:` line at the
 * end of the PR body is dropped in all three observed shapes (dashes appended,
 * bare `Co-authored-by:` appended, `Generated with` block between).
 *
 * The design thesis is "the key lives in the authority", not "the key is a git
 * trailer". The line still lands in the commit message permanently and is
 * publicly verifiable; git's trailer PARSER is the only thing GitHub takes
 * away, and this reader does not need it.
 *
 *   node scripts/agent-analytics/merge-links.mjs                 # HEAD history
 *   node scripts/agent-analytics/merge-links.mjs --range origin/main
 *   node scripts/agent-analytics/merge-links.mjs --since 2026-07-16
 *   node scripts/agent-analytics/merge-links.mjs --json
 *
 * The HEADLINE output is Session-Id coverage over squash PR commits, because
 * that number is the retirement condition for `recordMergeLink()` in
 * remove-worktree.cjs (856 §6: >=95% over 30 consecutive days). A retirement
 * condition nobody can measure is a deferral, so it is printed first and on
 * every run.
 *
 * Read-only w.r.t. git and the filesystem. Writes nothing.
 */

import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  repoRoot, MERGE_LINK_SOURCES, buildMergeLinkRow,
} from './lib/telemetry-io.mjs';

export const SESSION_ID_KEY = 'Session-Id';

/**
 * The one definition of "this commit message declares a session id".
 *
 * EXPORTED AND SHARED WITH `scripts/ci/preview-squash-message.mjs` on purpose:
 * the preview's whole job is to promise that what the author wrote will be
 * found later. If the checker and the reader each carried their own pattern,
 * the preview could report success over a line this reader silently ignores —
 * a fork of exactly the kind 856 exists to remove.
 *
 * - Anchored at line start: prose or a code sample that merely MENTIONS
 *   `Session-Id: x` mid-sentence must not count. Leading whitespace is also
 *   rejected, because an indented occurrence is almost always inside a fenced
 *   block or a quoted example rather than a declaration.
 * - Key match is case-insensitive: this repo already writes both
 *   `Co-Authored-By` (the agent attribution convention) and `Co-authored-by` (GitHub's),
 *   so assuming one casing for our own key would be optimistic.
 * - The space after the colon is optional, matching git's own `key:value`
 *   trailer syntax.
 * - Trailing whitespace is tolerated.
 * - The value is captured LOOSELY (anything up to end of line) and validated
 *   separately by isPlausibleSessionId. A strict `\S+` here would make
 *   `Session-Id: some garbage` match nothing at all, silently dropping a
 *   malformed declaration instead of reporting it (856 §7). An EMPTY value
 *   still does not match, so the preview correctly calls that "missing".
 */
export const SESSION_ID_LINE_RE = new RegExp(`^${SESSION_ID_KEY}:[ \\t]*(\\S.*?)[ \\t]*$`, 'im');

/** All Session-Id declarations in a commit message / PR body, in order. */
export function findSessionIdValues(text) {
  const out = [];
  const re = new RegExp(SESSION_ID_LINE_RE.source, 'gim');
  for (const m of String(text ?? '').matchAll(re)) out.push(m[1]);
  return out;
}

/** True when the text declares at least one Session-Id line. */
export function hasSessionIdLine(text) {
  return SESSION_ID_LINE_RE.test(String(text ?? ''));
}

/**
 * A squash-merged PR commit, per ADR-0045: GitHub appends ` (#N)` to the PR
 * title. Same shape 856 §1 counts its 463-commit denominator with.
 */
const SQUASH_PR_SUBJECT = /\(#\d+\)\s*$/;

/**
 * Session ids are UUIDs in practice, but note-observation.mjs's sanitizeId
 * admits [A-Za-z0-9._-] and the published shard filenames prove that is the
 * real alphabet. Anything outside it (or absurdly short/long) is a MALFORMED
 * declaration, reported rather than silently dropped — 856 §7: a rejected row
 * is a finding about the source, not noise.
 */
const SESSION_ID_RE = /^[A-Za-z0-9._-]{4,80}$/;

// git log --format record/field separators (%x00 / %x1f). Records are
// NUL-delimited because %B is a full multi-line commit message.
const REC = String.fromCharCode(0x00);
const FIELD = String.fromCharCode(0x1f);

// %cI (committer date, strict ISO-8601) is NOT decoration: baseline-economics'
// filterMergesToWindow drops any ledger row without a parseable `ts`, so a link
// lacking one would vanish from every cost-per-merge window with no diagnostic
// — this design's own failure mode, silent absence, reappearing inside the fix
// for it. The commit date is the real observation time: the commit is the
// moment the link became true.
export const GIT_LOG_FORMAT = '--format=%x00%H%x1f%s%x1f%cI%x1f%B';

export function isSquashPrSubject(subject) {
  return SQUASH_PR_SUBJECT.test(String(subject ?? ''));
}

/** Shared with recover-merge-links.mjs so both writers admit the same ids. */
export function isPlausibleSessionId(value) {
  return SESSION_ID_RE.test(String(value ?? ''));
}

/**
 * Parse the raw output of `git log GIT_LOG_FORMAT <range>` into one record per
 * commit. Pure — the unit tests drive it directly with synthetic output rather
 * than fabricating a git repo.
 *
 * Returns [{ commit, subject, committedAt, sessionIds: string[],
 * malformed: string[] }]. A commit with no Session-Id line yields empty arrays
 * for both. That is NOT a rejection: an undeclared commit is the ordinary
 * pre-856 case, and the coverage summary is where it shows up.
 *
 * `committedAt` is null when the date field is absent or unparseable (an older
 * git, or hand-fed history). Null is reported by readSessionIdLinks rather than
 * papered over with `new Date()` — a fabricated observation time would put the
 * link in the wrong measurement window, which is worse than a legible gap.
 */
export function parseCommitLog(raw) {
  const out = [];
  for (const chunk of String(raw ?? '').split(REC)) {
    if (!chunk.trim()) continue;
    const firstSep = chunk.indexOf(FIELD);
    if (firstSep === -1) continue; // no field separator at all — unparseable
    const commit = chunk.slice(0, firstSep).trim();
    if (!commit) continue;
    const secondSep = chunk.indexOf(FIELD, firstSep + 1);
    const subject = (secondSep === -1 ? chunk.slice(firstSep + 1) : chunk.slice(firstSep + 1, secondSep))
      .split('\n')[0];
    const thirdSep = secondSep === -1 ? -1 : chunk.indexOf(FIELD, secondSep + 1);
    const dateField = secondSep === -1 ? ''
      : (thirdSep === -1 ? chunk.slice(secondSep + 1) : chunk.slice(secondSep + 1, thirdSep)).split('\n')[0].trim();
    const committedAt = dateField && !Number.isNaN(new Date(dateField).getTime()) ? dateField : null;
    // Everything after the third separator is the full message body (%B),
    // including whatever GitHub appended below it.
    const body = thirdSep === -1 ? '' : chunk.slice(thirdSep + 1);
    const sessionIds = [];
    const malformed = [];
    for (const value of findSessionIdValues(body)) {
      if (isPlausibleSessionId(value)) {
        if (!sessionIds.includes(value)) sessionIds.push(value);
      } else {
        malformed.push(value);
      }
    }
    out.push({ commit, subject, committedAt, sessionIds, malformed });
  }
  return out;
}

function gitLog({ range = 'HEAD', since = null, root = repoRoot } = {}) {
  const args = ['log', GIT_LOG_FORMAT];
  if (since) args.push(`--since=${since}`);
  args.push(range);
  return execFileSync('git', args, {
    cwd: root, encoding: 'utf8', maxBuffer: 512 * 1024 * 1024, windowsHide: true,
  });
}

/**
 * Scan git history and return the derived links plus every commit record, so
 * the caller can compute coverage without a second git pass.
 *
 * `raw` is the test seam: it short-circuits the git call.
 */
export function readSessionIdLinks({ range = 'HEAD', since = null, root = repoRoot, raw = null } = {}) {
  const commits = parseCommitLog(raw ?? gitLog({ range, since, root }));
  const links = [];
  const malformed = [];
  const undated = [];
  for (const c of commits) {
    for (const sessionId of c.sessionIds) {
      if (c.committedAt === null) {
        undated.push({ commit: c.commit, subject: c.subject, session_id: sessionId });
      }
      links.push(buildMergeLinkRow({
        sessionId,
        mergeCommit: c.commit,
        subject: c.subject,
        source: MERGE_LINK_SOURCES.COMMIT_MESSAGE,
        ts: c.committedAt,
      }));
    }
    for (const value of c.malformed) {
      malformed.push({ commit: c.commit, subject: c.subject, value });
    }
  }
  return { commits, links, malformed, undated };
}

/**
 * The commit that introduced the `Session-Id:` line and the /publish instruction
 * to write it (tempdoc 856, merged 2026-08-19 as c2645ef1). A PR that merged
 * before this could not have declared an id, so it is not evidence about
 * adoption — see summarizeCoverage.
 */
export const MECHANISM_LANDED = '2026-08-19T20:30:51Z';

/**
 * Smallest in-scope denominator at which the ≥95% retirement condition means
 * anything. Derived, not chosen: 95% is only *expressible* with one tolerated
 * failure from 20 PRs up (19/20 = 95.0%; 18/19 = 94.7% fails, and 19/19 = 100%
 * tolerates none). Below 20 the ratio is either a perfect score that one future
 * miss destroys, or an unreachable bar — neither is evidence about adoption.
 *
 * This floor exists because the metric shipped without one and immediately read
 * **1/1 = 100%**, i.e. already "satisfying" a condition that gates deleting a
 * fallback writer. The same effort added `insufficient` floors to
 * context-attribution, and the instrument it retired carried a derived
 * 44-pair floor — the floor was the one thing not ported. Reporting a conclusion
 * from n=1 is the failure this lane keeps finding elsewhere.
 */
export const MIN_COVERAGE_DENOMINATOR = 20;

/**
 * Session-Id coverage over squash PR commits — the §6 retirement condition.
 *
 * TWO exclusions from the denominator, for the same reason: a commit that was
 * never in scope for the mechanism cannot be evidence about whether the
 * mechanism is adopted.
 *
 *  1. Non-PR commits (direct pushes, local merge commits). The id is declared
 *     in a PR body, so a commit that never went through a PR was never in scope.
 *  2. **PRs that merged before MECHANISM_LANDED.** This was the original defect
 *     (856 §6, fixed 2026-08-20): a rolling window over all history counts PRs
 *     that predate the mechanism, so the ratio reads near-zero no matter how
 *     complete adoption becomes. Measured the morning after 856 merged: 1/110
 *     over 7 days, of which 109 predated the mechanism — a number that looks
 *     like failure and means nothing. With the retirement condition set at >=95%,
 *     the uncorrected metric could never fire, so the tombstone it gates could
 *     never be collected.
 *
 * Pre-mechanism PRs are REPORTED, not silently dropped — a narrowed denominator
 * that hides its own narrowing is the failure this lane keeps finding elsewhere.
 */
export function summarizeCoverage(commits, {
  mechanismLanded = MECHANISM_LANDED,
  minDenominator = MIN_COVERAGE_DENOMINATOR,
} = {}) {
  const allPrCommits = commits.filter((c) => isSquashPrSubject(c.subject));
  // Compared as full instants, not dates: 856 landed at 20:30Z, and PRs merged earlier the
  // SAME day were equally out of scope. Date granularity put 35 of them in the denominator.
  const landedMs = Date.parse(mechanismLanded);
  const inScope = (c) => {
    if (!c.committedAt) return true; // undated: cannot rule it out, so leave it countable
    const t = Date.parse(c.committedAt);
    return Number.isNaN(t) ? true : t >= landedMs;
  };
  const prCommits = allPrCommits.filter(inScope);
  const preMechanism = allPrCommits.length - prCommits.length;
  const declared = prCommits.filter((c) => c.sessionIds.length > 0);
  const sessions = new Set();
  for (const c of commits) for (const s of c.sessionIds) sessions.add(s);
  return {
    commitsScanned: commits.length,
    mechanismLanded,
    squashPrCommits: prCommits.length,
    squashPrPreMechanism: preMechanism,
    squashPrWithSessionId: declared.length,
    squashPrWithoutSessionId: prCommits.length - declared.length,
    coveragePct: prCommits.length === 0 ? null
      : Math.round((declared.length / prCommits.length) * 1000) / 10,
    minDenominator,
    // The percentage is still reported when insufficient — the numbers stay
    // visible, only the CONCLUSION is withheld. Same shape as
    // context-attribution: refusing to draw a verdict is not refusing to show data.
    insufficient: prCommits.length < minDenominator,
    distinctSessions: sessions.size,
    nonPrCommitsWithSessionId: commits.filter((c) => !isSquashPrSubject(c.subject) && c.sessionIds.length > 0).length,
  };
}

function parseArgs(argv) {
  const opts = { range: 'HEAD', since: null, json: false, limit: 20, help: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--range' && argv[i + 1]) opts.range = argv[++i];
    else if (arg === '--since' && argv[i + 1]) opts.since = argv[++i];
    else if (arg === '--limit') {
      const raw = argv[++i];
      const n = Number(raw);
      // A silently-NaN limit yields slice(0, NaN) — an empty listing that looks
      // like "no links found". Refuse instead of printing a lie.
      if (raw === undefined || !Number.isInteger(n) || n < 0) throw new Error(`--limit needs a non-negative integer, got ${JSON.stringify(raw)}`);
      opts.limit = n;
    } else if (arg === '--json') opts.json = true;
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
    console.error(`merge-links: ${e.message}`);
    process.exit(2);
  }
  if (opts.help) {
    console.log([
      'Usage: node scripts/agent-analytics/merge-links.mjs [--range REV] [--since DATE] [--json] [--limit N]',
      '',
      `Derives session->merge links from ${SESSION_ID_KEY}: lines in commit messages,`,
      'and reports coverage over squash PR commits (tempdoc 856 §6 retirement',
      'condition for remove-worktree.cjs recordMergeLink).',
    ].join('\n'));
    return;
  }

  const { commits, links, malformed, undated } = readSessionIdLinks({ range: opts.range, since: opts.since });
  const coverage = summarizeCoverage(commits);

  if (opts.json) {
    console.log(JSON.stringify({
      kind: 'justsearch-merge-links.v1',
      range: opts.range, since: opts.since, coverage, links, malformed, undated,
    }, null, 2));
    return;
  }

  const pct = coverage.coveragePct == null ? 'n/a' : `${coverage.coveragePct}%`;
  console.log(`merge-links: ${SESSION_ID_KEY} coverage ${coverage.squashPrWithSessionId}/${coverage.squashPrCommits} squash PR commits (${pct})`);
  if (coverage.insufficient) {
    console.log(`  ⚠️ INSUFFICIENT: ${coverage.squashPrCommits} in-scope PR(s) < ${coverage.minDenominator} needed to read a >=95% rate.`);
    console.log('    The percentage above is shown, but the retirement condition is NOT met and');
    console.log('    cannot be assessed yet — 95% is only expressible from 20 PRs up (19/20).');
  }
  console.log('  retirement condition (856 §6): >=95% over 30 consecutive days AND a denominator of');
  console.log(`    at least ${coverage.minDenominator} in-scope PRs -> then delete recordMergeLink() in scripts/dev/remove-worktree.cjs`);
  console.log(`  scanned ${coverage.commitsScanned} commits on ${opts.range}${opts.since ? ` since ${opts.since}` : ''}`);
  if (coverage.squashPrPreMechanism > 0) {
    console.log(`  ${coverage.squashPrPreMechanism} squash PR commit(s) merged before the mechanism landed (${coverage.mechanismLanded}) and are`);
    console.log('    OUTSIDE the denominator — they could not have declared an id, so they are not');
    console.log('    evidence about adoption. Counting them is what made this metric unable to fire.');
  }
  console.log(`  links derived: ${links.length} across ${coverage.distinctSessions} distinct sessions`);
  if (coverage.nonPrCommitsWithSessionId > 0) {
    console.log(`  ${coverage.nonPrCommitsWithSessionId} declaring commit(s) are not squash PR commits (outside the coverage denominator)`);
  }
  if (undated.length > 0) {
    console.log(`  ${undated.length} link(s) have NO commit date (ts:null) — baseline-economics'`);
    console.log('    filterMergesToWindow drops a row without a parseable ts, so these are');
    console.log('    invisible to cost-per-merge. Reported here rather than silently dated "now":');
    for (const u of undated.slice(0, opts.limit)) {
      console.log(`    ${u.commit.slice(0, 8)} ${u.session_id.slice(0, 8)}`);
    }
  }
  if (malformed.length > 0) {
    console.log(`  ${malformed.length} MALFORMED ${SESSION_ID_KEY} value(s) (reported, not dropped):`);
    for (const m of malformed.slice(0, opts.limit)) {
      console.log(`    ${m.commit.slice(0, 8)} ${JSON.stringify(m.value)}`);
    }
  }
  if (links.length === 0) {
    console.log(`  (no ${SESSION_ID_KEY} lines in range yet — expected until a squash message carrying one has merged)`);
    return;
  }
  console.log('');
  for (const l of links.slice(0, opts.limit)) {
    console.log(`  ${l.session_id.slice(0, 8)} -> ${l.merge_commit.slice(0, 8)} ${l.ts === null ? 'NO-DATE   ' : String(l.ts).slice(0, 10)} [${l.source}/${l.kind}] ${l.subject.slice(0, 60)}`);
  }
  if (links.length > opts.limit) console.log(`  … ${links.length - opts.limit} more (use --json for all)`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  main();
}
