/**
 * Tempdoc 856 C2 — unit tests for merge-links.mjs (the `Session-Id:` commit-message
 * reader) and for the shared link-provenance vocabulary in lib/telemetry-io.mjs.
 *
 * The parser is driven with SYNTHETIC `git log` output rather than a fixture
 * repo: the thing under test is the parse + coverage arithmetic, and a real
 * repo would make the malformed / displaced cases nearly impossible to build.
 *
 * The central case is `githubSquashMessage` — the shape GitHub actually writes
 * on squash-merge, with its own `---------` / `Co-authored-by:` block appended
 * BELOW the PR body. The first implementation used `%(trailers:key=Session-Id)`
 * and would have derived zero links forever against exactly this shape.
 *
 * Run with: `node scripts/agent-analytics/merge-links.test.mjs`
 */

import assert from 'node:assert/strict';
import {
  parseCommitLog,
  readSessionIdLinks,
  summarizeCoverage,
  MIN_COVERAGE_DENOMINATOR,
  isSquashPrSubject,
  isPlausibleSessionId,
  hasSessionIdLine,
  findSessionIdValues,
  SESSION_ID_KEY,
} from './merge-links.mjs';
import {
  MERGE_LINK_SOURCES,
  DEFAULT_MERGE_LINK_SOURCE,
  mergeLinkKind,
  normalizeMergeLinkRow,
  buildMergeLinkRow,
} from './lib/telemetry-io.mjs';
// buildReport is filterMergesToWindow's only exported caller (that predicate is
// module-private at baseline-economics.mjs:459). Importing the real thing keeps
// this test honest: it fails if the window filter's contract changes.
import { buildReport } from './baseline-economics.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const REC = String.fromCharCode(0x00);
const FIELD = String.fromCharCode(0x1f);
const DEFAULT_DATE = '2026-08-01T12:00:00Z';

// The counting tests below pass this as `mechanismLanded` so they exercise HOW
// coverage is counted without depending on WHEN the real mechanism landed. Pinning
// them to the production constant would make a fixture date silently decide whether
// a counting assertion runs at all — which is exactly what happened when the scope
// cutoff was introduced: every fixture predated it and three tests went null.
const EPOCH = '1970-01-01T00:00:00Z';
const SID = '1568032c-aff9-459c-9afd-7adb22e80473';

/** Compose one `git log --format=%x00%H%x1f%s%x1f%cI%x1f%B` record. */
function logRecord(commit, subject, body = '', committedAt = DEFAULT_DATE) {
  return REC + commit + FIELD + subject + FIELD + committedAt + FIELD + `${subject}\n\n${body}\n`;
}

/**
 * The real squash-merge message shape, verified against `b816b98e` on
 * origin/main: PR body, then OUR generated-with line, then GitHub's separator
 * and Co-authored-by paragraph. 262 of 273 squash PR commits since 2026-07-16
 * carry an appended Co-authored-by line; only 3 carry no appended block at all.
 */
function githubSquashMessage(bodyLines) {
  return [
    ...bodyLines,
    '',
    '🤖 Generated with [Claude Code](https://claude.com/claude-code)',
    '',
    '---------',
    '',
    'Co-authored-by: Claude Fable 5 <noreply@anthropic.com>',
  ].join('\n');
}

// --- the refuted-mechanism regression -------------------------------------

run('a Session-Id line SURVIVES GitHub appending its own block below it', () => {
  // This is the case `%(trailers:key=Session-Id)` returned nothing for, because
  // git only parses the LAST paragraph and GitHub's block displaces ours.
  const body = githubSquashMessage(['## Summary', '', 'Did the thing.', '', `${SESSION_ID_KEY}: ${SID}`]);
  const [rec] = parseCommitLog(logRecord('a'.repeat(40), 'feat(856): thing (#520)', body));
  assert.deepEqual(rec.sessionIds, [SID]);
  assert.equal(rec.subject, 'feat(856): thing (#520)');
});

run('a Session-Id line is found in the MIDDLE of the message too (position is irrelevant)', () => {
  const body = githubSquashMessage([`${SESSION_ID_KEY}: ${SID}`, '', '## Summary', '', 'Did the thing.']);
  const [rec] = parseCommitLog(logRecord('a'.repeat(40), 'feat: thing (#520)', body));
  assert.deepEqual(rec.sessionIds, [SID]);
});

// --- line anchoring: what must NOT count ----------------------------------

run('a mid-line MENTION of Session-Id is not a declaration', () => {
  const body = 'The publish skill tells you to write Session-Id: <uuid> in the body.';
  const [rec] = parseCommitLog(logRecord('a'.repeat(40), 'docs: explain it (#1)', body));
  assert.deepEqual(rec.sessionIds, []);
  assert.deepEqual(rec.malformed, []);
});

run('an INDENTED Session-Id line (code block / quoted example) is not a declaration', () => {
  const body = ['Example:', '', `    ${SESSION_ID_KEY}: ${SID}`, ''].join('\n');
  const [rec] = parseCommitLog(logRecord('a'.repeat(40), 'docs: example (#1)', body));
  assert.deepEqual(rec.sessionIds, []);
});

run('the key match is case-insensitive (this repo already writes two casings of Co-authored-by)', () => {
  const [rec] = parseCommitLog(logRecord('a'.repeat(40), 'feat: x (#1)', `session-id: ${SID}`));
  assert.deepEqual(rec.sessionIds, [SID]);
});

run('a missing space after the colon still counts, matching git key:value syntax', () => {
  const [rec] = parseCommitLog(logRecord('a'.repeat(40), 'feat: x (#1)', `${SESSION_ID_KEY}:${SID}`));
  assert.deepEqual(rec.sessionIds, [SID]);
});

run('trailing whitespace on the line is tolerated', () => {
  const [rec] = parseCommitLog(logRecord('a'.repeat(40), 'feat: x (#1)', `${SESSION_ID_KEY}: ${SID}   `));
  assert.deepEqual(rec.sessionIds, [SID]);
});

run('the preview checker and the reader agree on every one of those shapes', () => {
  // preview-squash-message.mjs imports hasSessionIdLine, so a body the preview
  // calls OK is by construction a body this reader will find. Asserted here so
  // the shared-predicate guarantee is tested, not just documented.
  const accepted = [`${SESSION_ID_KEY}: ${SID}`, `session-id: ${SID}`, `${SESSION_ID_KEY}:${SID}`, `${SESSION_ID_KEY}: ${SID}  `];
  for (const line of accepted) {
    assert.equal(hasSessionIdLine(`## Summary\n\nx\n\n${line}`), true, line);
    assert.deepEqual(findSessionIdValues(line), [SID], line);
  }
  const rejected = ['see Session-Id: x inline', `   ${SESSION_ID_KEY}: ${SID}`, 'Session-Idx: y', `${SESSION_ID_KEY}:`, `${SESSION_ID_KEY}:   `];
  for (const line of rejected) assert.equal(hasSessionIdLine(line), false, line);
});

// --- parseCommitLog basics ------------------------------------------------

run('parseCommitLog yields empty arrays for a commit with NO Session-Id line', () => {
  const [rec] = parseCommitLog(logRecord('b'.repeat(40), 'docs: something older (#1)', githubSquashMessage(['Body.'])));
  assert.deepEqual(rec.sessionIds, []);
  assert.deepEqual(rec.malformed, []); // an undeclared commit is not a finding
});

run('parseCommitLog reports a MALFORMED value instead of dropping it', () => {
  const body = [`${SESSION_ID_KEY}: !!!`, `${SESSION_ID_KEY}: ab`].join('\n');
  const [rec] = parseCommitLog(logRecord('c'.repeat(40), 'fix: thing (#2)', body));
  assert.deepEqual(rec.sessionIds, []);
  assert.deepEqual(rec.malformed, ['!!!', 'ab']); // 'ab' is under the 4-char floor
});

run('parseCommitLog keeps valid ids and reports malformed ones from the same commit', () => {
  const body = [`${SESSION_ID_KEY}: ${SID}`, `${SESSION_ID_KEY}: <unknown>`].join('\n');
  const [rec] = parseCommitLog(logRecord('d'.repeat(40), 'fix: thing (#3)', body));
  assert.deepEqual(rec.sessionIds, [SID]);
  assert.deepEqual(rec.malformed, ['<unknown>']);
});

run('parseCommitLog dedupes a repeated Session-Id and keeps two different ones', () => {
  const dup = parseCommitLog(logRecord('e'.repeat(40), 'fix: x (#4)', `${SESSION_ID_KEY}: sess-1234\n${SESSION_ID_KEY}: sess-1234`));
  assert.deepEqual(dup[0].sessionIds, ['sess-1234']);
  const two = parseCommitLog(logRecord('f'.repeat(40), 'fix: y (#5)', `${SESSION_ID_KEY}: sess-1234\n${SESSION_ID_KEY}: sess-5678`));
  assert.deepEqual(two[0].sessionIds, ['sess-1234', 'sess-5678']);
});

run('parseCommitLog handles multiple commits and ignores empty input', () => {
  const raw = logRecord('1'.repeat(40), 'a (#1)', `${SESSION_ID_KEY}: sess-1111`)
    + logRecord('2'.repeat(40), 'b (#2)', 'no id here')
    + logRecord('3'.repeat(40), 'c', '');
  assert.equal(parseCommitLog(raw).length, 3);
  assert.deepEqual(parseCommitLog(''), []);
  assert.deepEqual(parseCommitLog(null), []);
});

run('a multi-line message body does not break record or field boundaries', () => {
  const raw = logRecord('1'.repeat(40), 'a (#1)', githubSquashMessage(['line 1', '', 'line 2', '', `${SESSION_ID_KEY}: sess-1111`]))
    + logRecord('2'.repeat(40), 'b (#2)', githubSquashMessage(['other']));
  const recs = parseCommitLog(raw);
  assert.equal(recs.length, 2);
  assert.equal(recs[0].subject, 'a (#1)');
  assert.deepEqual(recs[0].sessionIds, ['sess-1111']);
  assert.equal(recs[1].subject, 'b (#2)');
  assert.deepEqual(recs[1].sessionIds, []);
});

// --- isSquashPrSubject / isPlausibleSessionId -----------------------------

run('isSquashPrSubject matches only a trailing (#N) squash PR subject', () => {
  assert.equal(isSquashPrSubject('feat: thing (#520)'), true);
  assert.equal(isSquashPrSubject('feat: thing (#520) '), true);
  assert.equal(isSquashPrSubject('feat: thing'), false);
  assert.equal(isSquashPrSubject("Merge branch 'main' of https://github.com/x/y"), false);
  assert.equal(isSquashPrSubject('fix: mentions (#520) then trails off'), false);
});

run('isPlausibleSessionId admits the published shard-filename alphabet only', () => {
  assert.equal(isPlausibleSessionId(SID), true);
  assert.equal(isPlausibleSessionId('wt-abc123def456'), true);
  assert.equal(isPlausibleSessionId('has space'), false);
  assert.equal(isPlausibleSessionId('a'), false);
  assert.equal(isPlausibleSessionId(''), false);
});

// --- readSessionIdLinks ---------------------------------------------------

run('readSessionIdLinks emits commit-message/fact rows and collects malformed values', () => {
  const raw = logRecord('a'.repeat(40), 'feat: x (#10)', githubSquashMessage([`${SESSION_ID_KEY}: ${SID}`]))
    + logRecord('b'.repeat(40), 'feat: y (#11)', `${SESSION_ID_KEY}: bogus value`)
    + logRecord('c'.repeat(40), 'feat: z (#12)', 'nothing');
  const { links, malformed, commits, undated } = readSessionIdLinks({ raw });
  assert.equal(commits.length, 3);
  assert.equal(links.length, 1);
  assert.equal(links[0].source, MERGE_LINK_SOURCES.COMMIT_MESSAGE);
  assert.equal(links[0].source, 'commit-message');
  assert.equal(links[0].kind, 'fact'); // the commit says so — permanently verifiable
  assert.equal(links[0].session_id, SID);
  assert.equal(links[0].merge_commit, 'a'.repeat(40));
  assert.equal(links[0].ts, DEFAULT_DATE);
  assert.deepEqual(undated, []);
  assert.equal(malformed.length, 1);
  assert.equal(malformed[0].commit, 'b'.repeat(40));
  // Captured loosely, then rejected by isPlausibleSessionId — a strict \S+
  // would have matched nothing and dropped this finding silently.
  assert.equal(malformed[0].value, 'bogus value');
});

// --- commit date -> ts (the window-filter collision) ----------------------

run('parseCommitLog threads the committer date through as committedAt', () => {
  const [rec] = parseCommitLog(logRecord('a'.repeat(40), 'feat: x (#10)', `${SESSION_ID_KEY}: sess-aaaa`, '2026-07-04T09:30:00+02:00'));
  assert.equal(rec.committedAt, '2026-07-04T09:30:00+02:00');
  assert.equal(rec.subject, 'feat: x (#10)'); // the extra field did not shift the others
  assert.deepEqual(rec.sessionIds, ['sess-aaaa']);
});

run('parseCommitLog tolerates an EMPTY date field and still keeps the link', () => {
  const [rec] = parseCommitLog(logRecord('a'.repeat(40), 'feat: x (#10)', `${SESSION_ID_KEY}: sess-aaaa`, ''));
  assert.equal(rec.committedAt, null);
  assert.deepEqual(rec.sessionIds, ['sess-aaaa']); // date unknown, link intact
});

run('parseCommitLog treats an UNPARSEABLE date as absent, not as a real timestamp', () => {
  const [rec] = parseCommitLog(logRecord('a'.repeat(40), 'feat: x (#10)', `${SESSION_ID_KEY}: sess-aaaa`, 'not-a-date'));
  assert.equal(rec.committedAt, null);
  assert.deepEqual(rec.sessionIds, ['sess-aaaa']);
});

run('readSessionIdLinks REPORTS an undated link instead of silently emitting ts:null', () => {
  const raw = logRecord('a'.repeat(40), 'feat: dated (#10)', `${SESSION_ID_KEY}: sess-aaaa`, '2026-07-04T00:00:00Z')
    + logRecord('b'.repeat(40), 'feat: undated (#11)', `${SESSION_ID_KEY}: sess-bbbb`, 'garbage');
  const { links, undated } = readSessionIdLinks({ raw });
  assert.equal(links.length, 2);
  assert.equal(links[1].ts, null);
  assert.equal(undated.length, 1);
  assert.equal(undated[0].session_id, 'sess-bbbb');
  assert.equal(undated[0].commit, 'b'.repeat(40));
});

const windowSession = () => [{
  session_id: 'sess-0001', project_dir: 'p', start_ts: '2026-07-04T00:00:00.000Z',
  total_cost_usd: 8, orchestrator_tokens_total: 100, worker_tokens_total: 50,
  subagents: { count: 0 }, model_mix: {}, unknown_model_tokens: {},
}];

run('a commit-message row SURVIVES baseline-economics filterMergesToWindow for its commit week', () => {
  // Regression guard: filterMergesToWindow (baseline-economics.mjs:459) drops
  // any row without a parseable `ts`, so a ts:null row would vanish from
  // cost-per-merge with no diagnostic. buildReport is that predicate's only
  // exported caller, so this exercises the REAL filter.
  const raw = logRecord('a'.repeat(40), 'feat(856): row (#520)', `${SESSION_ID_KEY}: sess-0001`, '2026-07-04T12:00:00Z');
  const { links } = readSessionIdLinks({ raw });
  const report = buildReport({ sessions: windowSession(), merges: links, since: '2026-07-01', until: null, excludedCount: 0 });
  assert.equal(report.totals.merge_rows_in_window, 1);
  assert.equal(report.totals.merges_attributed, 1);
  assert.equal(report.totals.cost_per_merge_attributed, 8);
});

run('the same row with ts:null is dropped by the real window filter — the defect this fixes', () => {
  // Falsifier for the test above: it must be the DATE doing the work, not the
  // row merely existing, or the assertion could pass for the wrong reason.
  const raw = logRecord('a'.repeat(40), 'feat(856): row (#520)', `${SESSION_ID_KEY}: sess-0001`, 'unparseable');
  const { links } = readSessionIdLinks({ raw });
  assert.equal(links[0].ts, null);
  const report = buildReport({ sessions: windowSession(), merges: links, since: '2026-07-01', until: null, excludedCount: 0 });
  assert.equal(report.totals.merge_rows_in_window, 0);
  assert.equal(report.totals.merges_attributed, 0);
  assert.equal(report.totals.sessions_with_zero_merges, 1);
});

run('a row outside the window is excluded on its commit date, not its read time', () => {
  const raw = logRecord('a'.repeat(40), 'feat: old (#1)', `${SESSION_ID_KEY}: sess-0001`, '2026-06-01T12:00:00Z');
  const { links } = readSessionIdLinks({ raw });
  const report = buildReport({ sessions: windowSession(), merges: links, since: '2026-07-01', until: null, excludedCount: 0 });
  assert.equal(report.totals.merge_rows_in_window, 0); // back-dating buckets correctly
});

// --- summarizeCoverage (the §6 retirement condition) ----------------------

run('summarizeCoverage counts coverage over squash PR commits only', () => {
  const raw = logRecord('a'.repeat(40), 'feat: x (#10)', `${SESSION_ID_KEY}: sess-aaaa`)
    + logRecord('b'.repeat(40), 'feat: y (#11)', 'nothing')
    + logRecord('c'.repeat(40), 'feat: z (#12)', 'nothing')
    + logRecord('d'.repeat(40), 'Merge branch main', `${SESSION_ID_KEY}: sess-bbbb`); // not a PR commit
  const cov = summarizeCoverage(parseCommitLog(raw), { mechanismLanded: EPOCH });
  assert.equal(cov.commitsScanned, 4);
  assert.equal(cov.squashPrCommits, 3);
  assert.equal(cov.squashPrWithSessionId, 1);
  assert.equal(cov.squashPrWithoutSessionId, 2);
  assert.equal(cov.coveragePct, 33.3);
  assert.equal(cov.distinctSessions, 2);
  assert.equal(cov.nonPrCommitsWithSessionId, 1);
});

run('summarizeCoverage reports null (not 0%) coverage when there are no PR commits', () => {
  const cov = summarizeCoverage(parseCommitLog(logRecord('a'.repeat(40), 'local commit', 'x')), { mechanismLanded: EPOCH });
  assert.equal(cov.squashPrCommits, 0);
  assert.equal(cov.coveragePct, null); // unknown, never a manufactured zero
});

run('summarizeCoverage reaches 100% when every PR commit declares a session', () => {
  const raw = logRecord('a'.repeat(40), 'feat: x (#10)', `${SESSION_ID_KEY}: sess-aaaa`)
    + logRecord('b'.repeat(40), 'feat: y (#11)', `${SESSION_ID_KEY}: sess-bbbb`);
  assert.equal(summarizeCoverage(parseCommitLog(raw), { mechanismLanded: EPOCH }).coveragePct, 100);
});

// --- provenance vocabulary (856 §3.1) -------------------------------------

run('mergeLinkKind: known observed sources are fact, shard-inference is inference', () => {
  assert.equal(mergeLinkKind(MERGE_LINK_SOURCES.TEARDOWN), 'fact');
  assert.equal(mergeLinkKind(MERGE_LINK_SOURCES.PUBLISH), 'fact');
  assert.equal(mergeLinkKind(MERGE_LINK_SOURCES.COMMIT_MESSAGE), 'fact');
  assert.equal(mergeLinkKind(MERGE_LINK_SOURCES.SHARD_INFERENCE), 'inference');
});

run('mergeLinkKind: an UNRECOGNISED source is unknown, never laundered into fact', () => {
  // The failure this guards: `!== 'shard-inference'` therefore fact, which
  // would let any foreign or misspelled writer mint fact-tier rows.
  assert.equal(mergeLinkKind('some-future-writer'), 'unknown');
  assert.equal(mergeLinkKind('git-trailer'), 'unknown'); // the retired name is not grandfathered
  assert.equal(mergeLinkKind(''), 'unknown');
  assert.equal(mergeLinkKind(undefined), 'unknown');
});

run('normalizeMergeLinkRow backfills a LEGACY row that has neither source nor kind', () => {
  const legacy = { session_id: 's1', merge_commit: 'c1', subject: 'x (#1)', ts: '2026-07-01T00:00:00.000Z' };
  const row = normalizeMergeLinkRow(legacy);
  assert.equal(row.source, DEFAULT_MERGE_LINK_SOURCE);
  assert.equal(row.source, 'teardown');
  assert.equal(row.kind, 'fact');
  assert.equal(row.kind_conflict, undefined);
  assert.equal(legacy.source, undefined); // input is not mutated
});

run('normalizeMergeLinkRow resolves a source/kind disagreement to the WEAKER tier', () => {
  // Direction 1 — the row over-claims: fact tier on an inference source. The
  // source wins, because believing `kind` would let the fact tier absorb a
  // derived row (856 §3.1).
  const overclaim = normalizeMergeLinkRow({ session_id: 's1', merge_commit: 'c1', source: 'shard-inference', kind: 'fact' });
  assert.equal(overclaim.kind, 'inference');
  assert.equal(overclaim.kind_conflict, 'fact'); // the discarded claim is surfaced, not erased

  // Direction 2 — the row under-claims: inference tier on an observed source.
  // The ROW wins here. Deriving strictly from source would UPGRADE it to fact,
  // manufacturing confidence the writer explicitly disclaimed.
  const underclaim = normalizeMergeLinkRow({ session_id: 's1', merge_commit: 'c1', source: 'publish', kind: 'inference' });
  assert.equal(underclaim.kind, 'inference');
  assert.equal(underclaim.kind_conflict, undefined); // nothing was discarded

  // The invariant behind both: normalization can only ever weaken a claim.
  for (const source of ['teardown', 'publish', 'commit-message', 'shard-inference', 'mystery']) {
    for (const kind of ['fact', 'inference', 'unknown', undefined, 'nonsense']) {
      const row = normalizeMergeLinkRow({ session_id: 's1', merge_commit: 'c1', source, kind });
      const rank = { unknown: 0, inference: 1, fact: 2 };
      assert.ok(rank[row.kind] <= rank[mergeLinkKind(source)], `${source}/${kind} must not upgrade`);
      if (rank[kind] != null) assert.ok(rank[row.kind] <= rank[kind], `${source}/${kind} must not upgrade`);
    }
  }
});

run('normalizeMergeLinkRow ignores an unrecognised kind value and falls back to the source', () => {
  const row = normalizeMergeLinkRow({ session_id: 's1', merge_commit: 'c1', source: 'publish', kind: 'bogus-tier' });
  assert.equal(row.kind, 'fact');
  assert.equal(row.kind_conflict, 'bogus-tier');
});

run('normalizeMergeLinkRow gives an unknown-source row the unknown tier, not fact', () => {
  const row = normalizeMergeLinkRow({ session_id: 's1', merge_commit: 'c1', source: 'mystery-writer', kind: 'fact' });
  assert.equal(row.kind, 'unknown');
  assert.equal(row.kind_conflict, 'fact');
});

run('normalizeMergeLinkRow leaves an already-consistent row untouched', () => {
  const row = normalizeMergeLinkRow({ session_id: 's1', merge_commit: 'c1', source: 'publish', kind: 'fact' });
  assert.equal(row.source, 'publish');
  assert.equal(row.kind, 'fact');
  assert.equal(row.kind_conflict, undefined);
});

run('buildMergeLinkRow defaults to teardown/fact and rejects an unknown source', () => {
  const row = buildMergeLinkRow({ sessionId: 's1', mergeCommit: 'c1', subject: 'x (#1)', ts: 'T' });
  assert.deepEqual(row, { session_id: 's1', merge_commit: 'c1', subject: 'x (#1)', ts: 'T', source: 'teardown', kind: 'fact' });
  assert.throws(
    () => buildMergeLinkRow({ sessionId: 's1', mergeCommit: 'c1', subject: 'x', source: 'made-up' }),
    /unknown source 'made-up'/,
  );
});

run('SESSION_ID_KEY is the exact key the PR body carries and preview-squash-message checks', () => {
  assert.equal(SESSION_ID_KEY, 'Session-Id');
});

// --- scope cutoff: PRs that predate the mechanism (856 §6 correction) --------

run('summarizeCoverage EXCLUDES squash PRs that merged before the mechanism landed', () => {
  const raw = logRecord('a'.repeat(40), 'feat: old (#10)', 'nothing', '2026-08-19T10:00:00Z')
    + logRecord('b'.repeat(40), 'feat: new (#11)', `${SESSION_ID_KEY}: sess-aaaa`, '2026-08-20T10:00:00Z');
  const cov = summarizeCoverage(parseCommitLog(raw), { mechanismLanded: '2026-08-19T20:30:51Z' });
  // The pre-mechanism PR could not have declared an id, so counting it would
  // report 50% adoption for a period when adoption was impossible.
  assert.equal(cov.squashPrCommits, 1, 'only the in-scope PR is in the denominator');
  assert.equal(cov.squashPrPreMechanism, 1, 'the excluded one is COUNTED, not silently dropped');
  assert.equal(cov.coveragePct, 100);
});

run('the excluded count is reported, so a narrowed denominator cannot hide its narrowing', () => {
  const raw = logRecord('a'.repeat(40), 'feat: a (#10)', 'nothing', '2026-07-01T10:00:00Z')
    + logRecord('b'.repeat(40), 'feat: b (#11)', 'nothing', '2026-07-02T10:00:00Z')
    + logRecord('c'.repeat(40), 'feat: c (#12)', 'nothing', '2026-08-20T10:00:00Z');
  const cov = summarizeCoverage(parseCommitLog(raw), { mechanismLanded: '2026-08-19T20:30:51Z' });
  assert.equal(cov.squashPrPreMechanism, 2);
  assert.equal(cov.squashPrCommits, 1);
  assert.equal(cov.coveragePct, 0, 'an in-scope PR with no id is a real zero, unlike a pre-scope one');
});

run('the cutoff compares INSTANTS, not dates — same-day-but-earlier is out of scope', () => {
  // 856 landed at 20:30Z. A PR merged at 10:00 the same day was equally unable to
  // declare an id; date-granularity had left 35 such commits in the denominator.
  const raw = logRecord('a'.repeat(40), 'feat: earlier same day (#10)', 'nothing', '2026-08-19T10:00:00Z');
  const cov = summarizeCoverage(parseCommitLog(raw), { mechanismLanded: '2026-08-19T20:30:51Z' });
  assert.equal(cov.squashPrPreMechanism, 1);
  assert.equal(cov.squashPrCommits, 0);
  assert.equal(cov.coveragePct, null, 'no in-scope PRs is unknown, never a manufactured zero');
});

run('an UNDATED commit stays countable rather than being ruled out of scope', () => {
  // committedAt null means we cannot place it; excluding it would silently shrink
  // the denominator on missing data, which is the opposite of reporting the gap.
  const raw = logRecord('a'.repeat(40), 'feat: undated (#10)', `${SESSION_ID_KEY}: sess-aaaa`, '');
  const cov = summarizeCoverage(parseCommitLog(raw), { mechanismLanded: '2026-08-19T20:30:51Z' });
  assert.equal(cov.squashPrCommits, 1);
  assert.equal(cov.squashPrPreMechanism, 0);
});


// --- the denominator floor (856 §6, review finding F4) ----------------------

run('coverage below the floor is marked insufficient, and the pct is STILL shown', () => {
  const raw = logRecord('a'.repeat(40), 'feat: x (#10)', `${SESSION_ID_KEY}: sess-aaaa`, '2026-08-20T10:00:00Z');
  const cov = summarizeCoverage(parseCommitLog(raw), { mechanismLanded: '2026-08-19T20:30:51Z' });
  assert.equal(cov.coveragePct, 100, 'the number stays visible — only the conclusion is withheld');
  assert.equal(cov.insufficient, true, 'n=1 cannot support a >=95% verdict');
  assert.equal(cov.minDenominator, MIN_COVERAGE_DENOMINATOR);
});

run('at the floor exactly, the verdict becomes assessable', () => {
  let raw = '';
  for (let i = 0; i < MIN_COVERAGE_DENOMINATOR; i += 1) {
    raw += logRecord(String(i).padStart(40, '0'), `feat: x (#${i + 100})`,
      `${SESSION_ID_KEY}: sess-${i}`, '2026-08-20T10:00:00Z');
  }
  const cov = summarizeCoverage(parseCommitLog(raw), { mechanismLanded: '2026-08-19T20:30:51Z' });
  assert.equal(cov.squashPrCommits, MIN_COVERAGE_DENOMINATOR);
  assert.equal(cov.insufficient, false, 'the boundary is inclusive — 20 is enough, not 21');
  assert.equal(cov.coveragePct, 100);
});

run('one miss below the floor does not read as a real failure either', () => {
  // The floor is symmetric: it withholds the verdict in BOTH directions, so a
  // small sample cannot manufacture a pass or a fail.
  const raw = logRecord('a'.repeat(40), 'feat: x (#10)', 'no id', '2026-08-20T10:00:00Z');
  const cov = summarizeCoverage(parseCommitLog(raw), { mechanismLanded: '2026-08-19T20:30:51Z' });
  assert.equal(cov.coveragePct, 0);
  assert.equal(cov.insufficient, true, '0% from one PR is as unassessable as 100% from one');
});

run('19 of 20 is exactly 95% — the derivation the floor rests on', () => {
  let raw = '';
  for (let i = 0; i < 20; i += 1) {
    const body = i === 0 ? 'no id' : `${SESSION_ID_KEY}: sess-${i}`;
    raw += logRecord(String(i).padStart(40, '0'), `feat: x (#${i + 100})`, body, '2026-08-20T10:00:00Z');
  }
  const cov = summarizeCoverage(parseCommitLog(raw), { mechanismLanded: '2026-08-19T20:30:51Z' });
  assert.equal(cov.coveragePct, 95, 'one tolerated failure at n=20 is why 20 is the floor');
  assert.equal(cov.insufficient, false);
});

if (failures.length) {
  console.error(`merge-links.test: ${failures.length} FAILED / ${passed} passed`);
  for (const f of failures) console.error('  x ' + f);
  process.exit(1);
}
console.log(`merge-links.test: ${passed} passed`);
