/**
 * Unit tests for the test-intent gate's pure parts: scope classification (D1), Rust test-item
 * extraction, changeset parsing and digest, evidence validation and runner parsers (D4), the
 * acceptance truth table (D2, Measurement) and the weekly report.
 *
 * Run with: `node scripts/governance/gates/test-intent/units.test.mjs`
 */

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { appendRecord, computeDigest, parseChangeset, skeletonEntry } from './changeset.mjs';
import { contentDigest, evidenceSelfDigest, EVIDENCE_PRODUCER, EVIDENCE_SCHEMA, validateEvidence } from './evidence.mjs';
import {
  classifyFailBefore, gradleTarget, parseJUnitXml, parseLibtest, parseVitestJson, runnerFor, selectJUnitCases,
  selectVitestCases, summarize,
} from './evidence-runners.mjs';
import { RustScanError, extractTestItems, testModuleFiles } from './rust-tests.mjs';
import { classifyPath, isOutOfScopeTestLike } from './scope.mjs';
import { verdictForAcceptance, verdictForItem } from './truth-table.mjs';
import { ROLLING_DAYS, buildReport, reconsiderRate, renderMarkdown, writeStepSummary } from './weekly-report.mjs';

let passed = 0;
const failures = [];
function test(name, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${name}: ${e.message}`);
  }
}

// ---- scope (D1) ----------------------------------------------------------------------------------
test('scope: in-scope paths', () => {
  const cases = {
    'modules/core/src/test/java/io/x/A.java': 'test-code',
    'modules/core/src/integrationTest/java/io/x/A.java': 'test-code',
    'modules/core/src/testFixtures/java/io/x/A.java': 'test-code',
    'modules/core/src/test/resources/golden/a.json': 'test-code',
    'modules/test-support/src/main/java/io/x/Support.java': 'test-code',
    'modules/app-api-tck/src/main/java/io/x/Tck.java': 'test-code',
    'modules/ui-web/src/a.test.ts': 'test-code',
    'modules/ui-web/src/a.spec.tsx': 'test-code',
    'modules/ui-web/src/a-lockdown.test.ts': 'test-code',
    'modules/ui-web/src/mocks/x.ts': 'test-code',
    'modules/ui-web/src/__test-setup__/x.ts': 'test-code',
    'modules/ui-web/src/api/__fixtures__/x.json': 'test-data',
    'modules/ui-web/src/__snapshots__/a.test.ts.snap': 'test-data',
    'modules/shell/src-tauri/tests/it.rs': 'test-code',
    'modules/app-launcher/archunit_store/stored.rules': 'watched-baseline',
    'modules/core/archunit_store/abc': 'watched-baseline',
    'scripts/ci/suppression-ratchet-baseline.v1.json': 'watched-baseline',
    'gates/test-efficacy/strength-baseline.v1.json': 'watched-baseline',
    'gates/dead-code/baseline.txt': 'watched-baseline',
    'scripts/ci/test-evidence-policy.v1.json': 'watched-baseline',
    'scripts/ci/stress-suite-policy.v1.json': 'watched-baseline',
  };
  for (const [p, kind] of Object.entries(cases)) assert.equal(classifyPath(p)?.kind, kind, p);
});

test('scope: out of scope paths', () => {
  for (const p of [
    'modules/core/src/main/java/io/x/A.java',
    'modules/ui-web/src/views/list.ts',
    'modules/shell/src-tauri/src/lib.rs',
    'scripts/ci/x.test.mjs',
    'scripts/governance/_fixtures/test-intent/positive/scenario.json',
    'docs/contracts/a.md',
    'governance/logic-seams.v1.json',
  ]) assert.equal(classifyPath(p), null, p);
  assert.ok(isOutOfScopeTestLike('scripts/ci/x.test.mjs'));
  assert.ok(isOutOfScopeTestLike('scripts/jseval/run.py'));
  assert.ok(!isOutOfScopeTestLike('modules/ui-web/src/a.test.ts'));
});

// ---- Rust ----------------------------------------------------------------------------------------
test('rust: cfg(test) items are extracted; production is not', () => {
  const src = 'fn a() { let s = "#[cfg(test)] {"; }\n// #[test] in a comment\n#[cfg(test)]\nmod tests {\n    #[test]\n    fn t() { assert!(true); }\n}\nfn b() {}\n';
  const out = extractTestItems(src);
  assert.equal(out.items.length, 1);
  assert.ok(out.text.startsWith('#[cfg(test)]\nmod tests {'));
  assert.ok(out.text.endsWith('}'));
  assert.ok(!out.text.includes('fn b'));
});

test('rust: free #[test] fns, #[tokio::test] and #![cfg(test)] files', () => {
  const free = extractTestItems('fn p() {}\n#[test]\nfn one() {}\n#[tokio::test]\nasync fn two() {}\n');
  assert.equal(free.items.length, 2);
  assert.equal(extractTestItems('#![cfg(test)]\nfn x() {}\n').wholeFile, true);
});

test('rust: unbalanced input throws (the caller flags it)', () => {
  assert.throws(() => extractTestItems('fn a() {\n'), RustScanError);
  assert.throws(() => extractTestItems('fn a() { let s = "open; }\n'));
});

test('rust: test-only module declarations resolve, with and without #[path]', () => {
  const norm = (xs) => xs.map((x) => x.replaceAll('\\', '/'));
  assert.deepEqual(norm(testModuleFiles('modules/shell/src-tauri/src/updater.rs', '#[cfg(test)]\n#[path = "updater_handoff_tests.rs"]\nmod handoff;\n')),
    ['modules/shell/src-tauri/src/updater_handoff_tests.rs']);
  assert.deepEqual(norm(testModuleFiles('modules/shell/src-tauri/src/lib.rs', '#[cfg(test)]\nmod tests;\n')),
    ['modules/shell/src-tauri/src/tests.rs', 'modules/shell/src-tauri/src/tests/mod.rs']);
  assert.deepEqual(testModuleFiles('modules/shell/src-tauri/src/lib.rs', 'mod prod;\n'), []);
});

// ---- changeset -----------------------------------------------------------------------------------
const CHANGESET = [
  '---', 'schema: test-intent.v1', 'task: t-1', 'author-role: builder', 'author-session: b-1', '---', '',
  '```json', '{ "entries": [ { "items": ["a"], "class": "Obsolete" } ] }', '```', '',
  '## Acceptance records', '',
].join('\n');

test('changeset: parses, and records do not enter the digest', () => {
  const p = parseChangeset(CHANGESET);
  assert.ok(p.ok, p.error);
  assert.equal(p.frontmatter['author-session'], 'b-1');
  assert.equal(p.entries.length, 1);
  const withRecord = appendRecord(CHANGESET, { kind: 'acceptance', role: 'verifier', session: 'v-1', verdict: 'accept', digest: 'sha256:x' });
  const q = parseChangeset(withRecord);
  assert.equal(q.records.length, 1);
  assert.equal(q.digestedText, p.digestedText);
  assert.equal(parseChangeset(CHANGESET.replace(/\r?\n/g, '\r\n')).digestedText, p.digestedText, 'CRLF does not change the digest');
});

test('changeset: digest binds items and evidence', () => {
  const base = { changesetPath: 'c.md', digestedText: 'x', items: [{ id: 'a', before: '1', after: '2' }], evidence: [] };
  const d = computeDigest(base);
  assert.notEqual(computeDigest({ ...base, items: [{ id: 'a', before: '1', after: '3' }] }), d);
  assert.notEqual(computeDigest({ ...base, evidence: [{ path: 'e.json', content: '{}' }] }), d);
  assert.notEqual(computeDigest({ ...base, digestedText: 'y' }), d);
  assert.equal(computeDigest({ ...base }), d);
});

test('changeset: unparseable inputs are rejected', () => {
  assert.equal(parseChangeset('no frontmatter').ok, false);
  assert.equal(parseChangeset(CHANGESET.replace('{ "entries"', '{ entries')).ok, false);
  assert.equal(parseChangeset(CHANGESET.replace('```json', '```json\n{}\n```\n```json')).ok, false, 'two entry blocks');
  assert.match(skeletonEntry('a').class, /^TODO/);
});

// ---- evidence (D4) -------------------------------------------------------------------------------
const evidenceFor = (checks, failBefore = null) => {
  const ev = {
    schema: EVIDENCE_SCHEMA, producer: { script: EVIDENCE_PRODUCER }, createdAt: 'now', revision: 'r', tree: 't',
    environment: { node: 'x' }, runs: [{ command: 'gradlew' }], checks, failBefore,
  };
  ev.digest = evidenceSelfDigest(ev);
  return JSON.stringify(ev);
};
const FILE = 'modules/core/src/test/java/io/x/ATest.java';
const readWorking = (p) => (p === FILE ? 'class ATest {}' : null);
const rec = (o = {}) => ({ id: `${FILE}#m`, contentDigest: contentDigest('class ATest {}'), executed: true, skipped: false, outcome: 'passed', ...o });

test('evidence: a valid file passes; each defect is caught', () => {
  const v = (text, opts = {}) => validateEvidence({ evidenceText: text, evidencePath: 'e.json', newChecks: [`${FILE}#m`], requireFailBefore: false, readWorking, ...opts });
  assert.deepEqual(v(evidenceFor([rec()])), []);
  assert.equal(v(null).length, 1);
  assert.ok(v(evidenceFor([rec({ executed: false, skipped: true, outcome: 'skipped' })])).length > 0, 'skipped');
  assert.ok(v(evidenceFor([rec({ contentDigest: contentDigest('other') })])).length > 0, 'content changed');
  assert.ok(v(evidenceFor([rec({ outcome: 'failed' })])).length > 0, 'failed');
  assert.ok(v(evidenceFor([])).length > 0, 'check missing');
  const tampered = JSON.parse(evidenceFor([rec()]));
  tampered.checks[0].outcome = 'passed!';
  assert.ok(v(JSON.stringify(tampered)).some((e) => /self-digest/.test(e.message)), 'hand edit');
  const fb = (classification) => evidenceFor([rec()], { ref: 'main', checks: [{ id: `${FILE}#m`, classification }] });
  assert.deepEqual(v(fb('assertion'), { requireFailBefore: true }), []);
  assert.ok(v(fb('compile'), { requireFailBefore: true }).length > 0);
  assert.ok(v(evidenceFor([rec()]), { requireFailBefore: true }).length > 0, 'fail-before missing');
});

test('runners: routing and Gradle coordinates', () => {
  assert.equal(runnerFor(`${FILE}#m`), 'gradle');
  assert.equal(runnerFor('modules/ui-web/src/a.test.ts#x'), 'vitest');
  assert.equal(runnerFor('modules/shell/src-tauri/src/lib.rs#tests::t'), 'cargo');
  assert.equal(runnerFor('scripts/x.test.mjs#a'), null);
  const t = gradleTarget('modules/core/src/integrationTest/java/io/x/BIT.java#m');
  assert.deepEqual([t.project, t.task, t.fqcn, t.method], [':modules:core', 'integrationTest', 'io.x.BIT', 'm']);
  assert.ok(gradleTarget('modules/core/src/testFixtures/java/io/x/F.java#m').error);
});

test('runners: JUnit XML, vitest JSON and libtest parse into cases', () => {
  const xml = '<testsuite><testcase name="m()" classname="io.x.ATest"/><testcase name="n" classname="io.x.ATest"><failure type="org.opentest4j.AssertionFailedError" message="expected: &lt;3&gt;"/></testcase>'
    + '<testcase name="s" classname="io.x.ATest"><skipped/></testcase><testcase name="m" classname="io.x.Other"/></testsuite>';
  const cases = parseJUnitXml(xml);
  assert.equal(cases.length, 4);
  assert.deepEqual(selectJUnitCases(cases, { fqcn: 'io.x.ATest', method: 'm' }).map((c) => c.status), ['passed']);
  const n = selectJUnitCases(cases, { fqcn: 'io.x.ATest', method: 'n' });
  assert.equal(classifyFailBefore({ runner: 'gradle', cases: n }).classification, 'assertion');
  assert.equal(classifyFailBefore({ runner: 'gradle', cases: [{ ...n[0], failureType: 'java.lang.NullPointerException' }] }).classification, 'exception');
  assert.equal(summarize(selectJUnitCases(cases, { fqcn: 'io.x.ATest', method: 's' })).outcome, 'skipped');
  assert.equal(summarize(selectJUnitCases(cases, { fqcn: 'io.x.ATest' })).outcome, 'partly-skipped');
  assert.equal(summarize([]).outcome, 'not-found');

  const vjson = JSON.stringify({ testResults: [{ name: 'F:\\r\\modules\\ui-web\\src\\a.test.ts', status: 'failed', message: '', assertionResults: [
    { fullName: 'a works', title: 'works', status: 'failed', failureMessages: ['AssertionError: expected 1 to be 2'] },
    { fullName: 'a other', title: 'other', status: 'passed' },
  ] }] });
  const sel = selectVitestCases(parseVitestJson(vjson), 'modules/ui-web/src/a.test.ts#a works');
  assert.equal(sel.cases.length, 1);
  assert.equal(classifyFailBefore({ runner: 'vitest', ...sel }).classification, 'assertion');
  assert.equal(classifyFailBefore({ runner: 'vitest', cases: [], fileStatus: 'failed', fileMessage: 'Transform failed' }).classification, 'compile');

  const lib = parseLibtest("running 2 tests\ntest tests::a ... ok\ntest tests::b ... FAILED\n\nfailures:\n\n---- tests::b stdout ----\nthread 'tests::b' panicked at src/lib.rs:9:9:\nassertion `left == right` failed\n");
  assert.deepEqual(lib.map((c) => c.status), ['passed', 'failed']);
  assert.equal(classifyFailBefore({ runner: 'cargo', cases: lib.filter((c) => c.name === 'tests::b') }).classification, 'assertion');
  assert.equal(classifyFailBefore({ runner: 'cargo', cases: [], buildOutput: 'error[E0425]: cannot find value' }).classification, 'compile');
  assert.equal(classifyFailBefore({ runner: 'cargo', cases: [], buildOutput: '' }).classification, 'environment');
});

// ---- truth table (D1, D2, Measurement) -----------------------------------------------------------
test('truth table: coverage', () => {
  assert.equal(verdictForItem({ id: 'a', fullEntries: 0, refs: 0, referable: false }).ruleId, 'test-intent/uncovered');
  assert.equal(verdictForItem({ id: 'a', fullEntries: 0, refs: 1, referable: false }).ruleId, 'test-intent/reference-not-allowed');
  assert.equal(verdictForItem({ id: 'a', fullEntries: 1, refs: 1, referable: true }).ruleId, 'test-intent/second-entry');
  assert.equal(verdictForItem({ id: 'a', fullEntries: 0, refs: 1, referable: true }).status, 'pass');
});

test('truth table: acceptance, audit and tie-break', () => {
  const base = { changeset: 'c.md', authorSession: 'b', digest: 'd', auditSelected: false, prNumber: 3 };
  const r = (o) => ({ kind: 'acceptance', role: 'verifier', session: 'v', verdict: 'accept', digest: 'd', ...o });
  const v = (records, o = {}) => verdictForAcceptance({ ...base, records, ...o }).ruleId;
  assert.equal(v([r()]), 'test-intent/accepted');
  assert.equal(v([]), 'test-intent/acceptance-missing');
  assert.equal(v([r({ session: 'b' })]), 'test-intent/acceptor-is-author');
  assert.equal(v([r({ role: 'builder' })]), 'test-intent/acceptance-missing');
  assert.equal(v([r({ digest: 'old' })]), 'test-intent/acceptance-stale');
  assert.equal(v([r({ verdict: 'reject' })]), 'test-intent/acceptance-rejected');
  assert.equal(v([r()], { authorSession: null }), 'test-intent/acceptance-missing');
  // A rejection on the current digest blocks; only changed content (a new digest) clears it.
  const rej = r({ session: 'x', verdict: 'reject' });
  assert.equal(v([rej, r({ session: 'x' })]), 'test-intent/acceptance-rejected', 'same reviewer changing its mind');
  assert.equal(v([rej, r({ session: 'y' })]), 'test-intent/tie-break-required', 'another reviewer accepting');
  assert.equal(v([r({ session: 'y' }), rej]), 'test-intent/tie-break-required', 'order does not matter');
  assert.equal(v([rej, r({ session: 'y' }), r({ kind: 'tie-break', session: 'y' })]), 'test-intent/tie-break-required', 'a party cannot break the tie');
  assert.equal(v([rej, r({ session: 'y' }), r({ kind: 'tie-break', session: 'b' })]), 'test-intent/tie-break-required', 'nor the author');
  assert.equal(v([rej, r({ session: 'y' }), r({ kind: 'tie-break', session: 't' })]), 'test-intent/accepted');
  assert.equal(v([rej, r({ session: 'y' }), r({ kind: 'tie-break', session: 't', verdict: 'reject' })]), 'test-intent/acceptance-rejected');
  assert.equal(v([r({ session: 'x', verdict: 'reject', digest: 'old' }), r({ session: 'x' })]), 'test-intent/accepted', 'rejection on old content');
  const audit = { auditSelected: true, prNumber: 10 };
  assert.equal(v([r()], audit), 'test-intent/audit-required');
  assert.equal(v([r(), r({ kind: 'audit', session: 'v' })], audit), 'test-intent/audit-required');
  assert.equal(v([r(), r({ kind: 'audit', session: 'a' })], audit), 'test-intent/accepted');
  assert.equal(v([r(), r({ kind: 'audit', session: 'a', verdict: 'reject' })], audit), 'test-intent/tie-break-required');
  assert.equal(v([r(), r({ kind: 'audit', session: 'a', verdict: 'reject' }), r({ kind: 'tie-break', session: 'a' })], audit), 'test-intent/tie-break-required');
  assert.equal(v([r(), r({ kind: 'audit', session: 'a', verdict: 'reject' }), r({ kind: 'tie-break', session: 't' })], audit), 'test-intent/accepted');
  assert.equal(v([r(), r({ kind: 'audit', session: 'a', verdict: 'reject' }), r({ kind: 'tie-break', session: 't', verdict: 'reject' })], audit), 'test-intent/acceptance-rejected');
});

// ---- weekly report (Measurement) -----------------------------------------------------------------
test('weekly report: counts and the one-in-ten tie-break flag', () => {
  const cs = (entries, records) => ({ path: 'gates/test-intent/.changesets/x.md', text: appendRecordsTo(entries, records) });
  const appendRecordsTo = (entries, records) => records.reduce((t, rec2) => appendRecord(t, rec2),
    CHANGESET.replace('{ "entries": [ { "items": ["a"], "class": "Obsolete" } ] }', JSON.stringify({ entries })));
  const acc = (o = {}) => ({ kind: 'acceptance', role: 'verifier', session: 'v', verdict: 'accept', digest: 'd', ...o });
  const commits = [
    { sha: 'a'.repeat(40), date: 'd', subject: 'x (#10)', pr: 10, changesets: [cs([{ items: ['a'], class: 'New', newChecks: ['a#1', 'a#2'] }], [acc(), acc({ kind: 'audit', session: 'u', verdict: 'reject' }), acc({ kind: 'tie-break', session: 't' })])] },
    { sha: 'b'.repeat(40), date: 'd', subject: 'y (#11)', pr: 11, changesets: [cs([{ items: ['b'], class: 'Obsolete' }, { items: ['c'], ref: 'gates/test-efficacy/.changesets/z.md' }], [acc({ verdict: 'reject' }), acc()])] },
    { sha: 'c'.repeat(40), date: 'd', subject: 'z (#12)', pr: 12, changesets: [] },
  ];
  const rep = buildReport({ commits, ref: 'origin/main', sinceIso: 's', untilIso: 'u' });
  assert.equal(rep.totals.prsMerged, 3);
  assert.equal(rep.totals.prsWithEntries, 2);
  assert.equal(rep.totals.entriesPerClass.New, 1);
  assert.equal(rep.totals.entriesPerClass.Obsolete, 1);
  assert.equal(rep.totals.references, 1);
  assert.equal(rep.totals.rejections, 2);
  assert.equal(rep.totals.audited, 1);
  assert.equal(rep.totals.tieBreaks, 1);
  assert.equal(rep.totals.newChecks, 2);
  assert.ok(rep.findings.some((f) => /third-reviewer tie-breaks were 1 of 1 audited PRs \(100\.0%\), above one in ten/.test(f)));
  assert.match(renderMarkdown(rep), /\| Third-reviewer tie-breaks \| 1 \|/);
});

test('reconsider rate: tie-breaks first, disagreements before any tie-break, strictly above one in ten', () => {
  assert.equal(reconsiderRate({ audited: 10, tieBreaks: 1, auditDisagreements: 5 }).above, false, 'boundary: 1 in 10 is not above');
  assert.equal(reconsiderRate({ audited: 10, tieBreaks: 2, auditDisagreements: 2 }).above, true);
  assert.match(reconsiderRate({ audited: 10, tieBreaks: 2, auditDisagreements: 9 }).basis, /tie-breaks/);
  assert.equal(reconsiderRate({ audited: 10, tieBreaks: 0, auditDisagreements: 2 }).above, true);
  assert.match(reconsiderRate({ audited: 10, tieBreaks: 0, auditDisagreements: 2 }).basis, /disagreements/);
  assert.equal(reconsiderRate({ audited: 10, tieBreaks: 0, auditDisagreements: 1 }).above, false);
  assert.equal(reconsiderRate({ audited: 0, tieBreaks: 0, auditDisagreements: 0 }).above, false);
  assert.equal(reconsiderRate({ audited: 0, tieBreaks: 0, auditDisagreements: 1 }).above, true, 'nothing to divide by');
});

test('weekly report: the gate step writes the rolling four-week counts to the job summary', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'test-intent-summary-'));
  try {
    const g = (...a) => execFileSync('git', a, { cwd: tmp, stdio: 'pipe', env: { ...process.env, GIT_AUTHOR_NAME: 'u', GIT_AUTHOR_EMAIL: 'u@x.invalid', GIT_COMMITTER_NAME: 'u', GIT_COMMITTER_EMAIL: 'u@x.invalid' } });
    g('init', '-q', '-b', 'main');
    fs.mkdirSync(path.join(tmp, 'gates/test-intent/.changesets'), { recursive: true });
    fs.writeFileSync(path.join(tmp, 'gates/test-intent/.changesets/a.md'),
      CHANGESET.replace('{ "entries": [ { "items": ["a"], "class": "Obsolete" } ] }', JSON.stringify({ entries: [{ items: ['a'], class: 'New', newChecks: ['a#x'] }] })));
    g('add', '-A');
    g('commit', '-q', '-m', 'Add a check (#15)');
    assert.equal(writeStepSummary({ repoRoot: tmp, env: {} }), null, 'no summary outside a job');
    const summary = path.join(tmp, 'summary.md');
    const md = writeStepSummary({ repoRoot: tmp, env: { GITHUB_STEP_SUMMARY: summary } });
    assert.equal(fs.readFileSync(summary, 'utf8'), md + '\n');
    assert.match(md, /^# Test-intent counts/);
    assert.match(md, /\| Entries: New \| 1 \|/);
    assert.match(md, /\| Audited PRs \(every 5th by number\) \| 1 \|/);
    assert.equal(ROLLING_DAYS, 28);
    const broken = writeStepSummary({ repoRoot: path.join(tmp, 'missing'), env: { GITHUB_STEP_SUMMARY: summary } });
    assert.match(broken, /Not available on this run/, 'a failure is reported, never thrown');
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

if (failures.length > 0) {
  console.error(`test-intent units: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`test-intent units: all ${passed} passed`);
