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
import { fileURLToPath } from 'node:url';

import { appendRecord, computeDigest, parseChangeset, skeletonEntry } from './changeset.mjs';
import { contentDigest, evidenceSelfDigest, EVIDENCE_PRODUCER, EVIDENCE_SCHEMA, validateEvidence } from './evidence.mjs';
import {
  classifyFailBefore, gradleTarget, parseJUnitXml, parseLibtest, parseVitestJson, runnerFor, selectJUnitCases,
  selectVitestCases, summarize,
} from './evidence-runners.mjs';
import { RustScanError, extractTestItems, testModuleFiles } from './rust-tests.mjs';
import {
  buildConfigChange, buildConfigKind, changedLineIndices, identifierParts, packageTestProjection, rootScriptResolver, scanBlocks,
  scanWorkflow, workflowLineRunsTests,
} from './build-config.mjs';
import { classifyPath, executionContext, isOutOfScopeTestLike, UI_WEB_VITEST_SELECTIONS } from './scope.mjs';
import { validateSource } from './sources.mjs';
import {
  TEST_SUPPORT_PATHS_FILE, globToRegExp, importSpecifiers, parseTestSupportList, renderTestSupportList,
  scanTestOnlyImports, testOnlyFiles,
} from './test-support.mjs';
import { verdictForAcceptance, verdictForItem } from './truth-table.mjs';
import { ROLLING_DAYS, buildReport, reconsiderRate, renderMarkdown, writeStepSummary } from './weekly-report.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..', '..', '..', '..');

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

// ---- removal sources (P6): only code the PR removes ----------------------------------------------
test('sources: a removal names a deleted production file or a member gone at the head', () => {
  const calc = 'modules/core/src/main/java/io/x/Calc.java';
  const base = { [calc]: 'class Calc {\n  static int add(int a, int b) { return a + b; }\n  static int addAll() { return 0; }\n}\n' };
  const ctx = (now, status) => ({
    readBase: (p) => base[p] ?? null,
    readNow: (p) => (p in now ? now[p] : base[p] ?? null),
    changedPaths: new Set(Object.keys(status)),
    changeStatus: new Map(Object.entries(status)),
  });
  const v = (removed, c) => validateSource({ kind: 'removal', removed }, c, { allowRemoval: true }).map((e) => e.rule);
  assert.deepEqual(v([calc], ctx({ [calc]: null }, { [calc]: 'D' })), []);
  assert.deepEqual(v([calc], ctx({ [calc]: 'class Calc {}\n' }, { [calc]: 'M' })), ['source-unresolved'], 'a modified file is not a removal');
  const addGone = ctx({ [calc]: 'class Calc {\n  static int addAll() { return 0; }\n}\n' }, { [calc]: 'M' });
  assert.deepEqual(v([`${calc}#add`], addGone), [], 'a whole-word match: addAll does not keep add alive');
  assert.deepEqual(v([`${calc}#addAll`], addGone), ['source-unresolved'], 'still present at the head');
  assert.deepEqual(v([`${calc}#sub`], addGone), ['source-unresolved'], 'never present at the base');
  assert.deepEqual(v([`${calc}#a-b`], addGone), ['entry-invalid']);
  assert.deepEqual(v(['modules/core/src/test/java/io/x/CalcTest.java'], addGone), ['entry-invalid']);
  assert.deepEqual(v([`${calc}#add`], { ...addGone, readNow: undefined }), ['source-unresolved'], 'fails closed without the head');
  const listed = { ...addGone, isTestPath: (p) => p === calc };
  assert.deepEqual(v([`${calc}#add`], listed), ['entry-invalid'], 'a listed test-only helper is test code');
});

// ---- execution context of moves -------------------------------------------------------------------
test('scope: system-tests in full, the test-support list watched', () => {
  assert.equal(classifyPath('modules/system-tests/src/main/java/io/x/judge/AnswerJudge.java')?.kind, 'test-code');
  assert.equal(classifyPath('modules/system-tests/build.gradle.kts')?.kind, 'test-code');
  assert.equal(classifyPath('modules/system-tests/src/test/java/io/x/AT.java')?.reason, "JVM test source set 'test'");
  assert.equal(classifyPath(TEST_SUPPORT_PATHS_FILE)?.kind, 'watched-baseline');
});

test('scope: executionContext keeps a move only within one module, source set or vitest selection', () => {
  const cases = {
    'modules/core/src/test/java/io/x/A.java': 'jvm:core:test',
    'modules/core/src/testFixtures/java/io/x/A.java': 'jvm:core:testFixtures',
    'modules/core/src/integrationTest/java/io/x/A.java': 'jvm:core:integrationTest',
    'modules/system-tests/src/main/java/io/x/J.java': 'jvm:system-tests:main',
    'modules/ui-web/src/a/b.test.ts': 'ui-web:vitest-default',
    'modules/ui-web/src/a/b.spec.tsx': 'ui-web:vitest-default',
    'modules/ui-web/src/a/b-lockdown.test.ts': 'ui-web:vitest-lockdown',
    'modules/ui-web/src/a/b-lockdown.test.js': null,
    'modules/ui-web/src/a/b.test.mjs': null,
    'modules/ui-web/e2e/b.test.ts': null,
    'modules/ui-web/src/mocks/m.ts': null,
    'modules/ui-web/src/a/__snapshots__/b.test.ts.snap': null,
    'modules/shell/src-tauri/tests/t.rs': 'rust:modules/shell/src-tauri/tests',
    'modules/core/archunit_store/stored.rules': null,
    'gates/dead-code/baseline.txt': null,
  };
  for (const [p, want] of Object.entries(cases)) assert.equal(executionContext(p), want, p);
});

test('scope: the vitest selections mirror modules/ui-web configs, and executionContext follows them', () => {
  const ui = path.join(REPO, 'modules', 'ui-web');
  const arrayOf = (text, key) => {
    const m = new RegExp(`\\n\\s*${key}:\\s*\\[([^\\]]*)\\]`).exec(text);
    return m ? [...m[1].matchAll(/'([^']+)'/g)].map((x) => x[1]) : [];
  };
  const vite = fs.readFileSync(path.join(ui, 'vite.config.js'), 'utf8');
  const lock = fs.readFileSync(path.join(ui, 'vitest.config.lockdown.ts'), 'utf8');
  assert.deepEqual(arrayOf(vite, 'include'), UI_WEB_VITEST_SELECTIONS.default.include, 'vite.config.js test.include drifted');
  assert.deepEqual(arrayOf(vite, 'exclude'), UI_WEB_VITEST_SELECTIONS.default.exclude, 'vite.config.js test.exclude drifted');
  assert.deepEqual(arrayOf(lock, 'include'), UI_WEB_VITEST_SELECTIONS.lockdown.include, 'vitest.config.lockdown.ts include drifted');
  assert.deepEqual(arrayOf(lock, 'exclude'), UI_WEB_VITEST_SELECTIONS.lockdown.exclude, 'vitest.config.lockdown.ts exclude drifted');
  const selected = ({ include, exclude }, rest) => include.some((g) => globToRegExp(g).test(rest)) && !exclude.some((g) => globToRegExp(g).test(rest));
  for (const rest of ['src/a.test.ts', 'src/x/y/a.spec.js', 'src/a.test.tsx', 'src/a-lockdown.test.ts', 'src/a-lockdown.test.js',
    'src/a-lockdown.test.tsx', 'src/a.test.mjs', 'e2e/a.test.ts', 'src/a.ts', 'node_modules/x/a.test.ts']) {
    const want = selected(UI_WEB_VITEST_SELECTIONS.default, rest) ? 'ui-web:vitest-default'
      : selected(UI_WEB_VITEST_SELECTIONS.lockdown, rest) ? 'ui-web:vitest-lockdown' : null;
    assert.equal(executionContext(`modules/ui-web/${rest}`), want, rest);
  }
});

// ---- build configuration that selects or runs tests ------------------------------------------------
test('build config: which files are build configuration', () => {
  assert.equal(buildConfigKind('modules/core/build.gradle.kts'), 'gradle');
  assert.equal(buildConfigKind('build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt'), 'gradle');
  assert.equal(buildConfigKind('gradle.properties'), 'properties');
  assert.equal(buildConfigKind('modules/ui-web/vitest.config.lockdown.ts'), 'test-runner');
  assert.equal(buildConfigKind('modules/ui-web/vite.config.js'), 'js-config');
  assert.equal(buildConfigKind('modules/ui-web/package.json'), 'package');
  assert.equal(buildConfigKind('scripts/ci/package.json'), null);
  assert.equal(buildConfigKind('modules/ui-web/node_modules/x/package.json'), null);
  assert.equal(buildConfigKind('modules/core/src/main/java/A.java'), null);
  assert.equal(buildConfigKind('modules/app-util/src/main/resources/junit-platform.properties'), 'junit-platform');
  assert.equal(buildConfigKind('scripts/x/junit-platform.properties'), 'junit-platform', 'anywhere');
  assert.equal(buildConfigKind('modules/a/src/main/resources/META-INF/services/org.junit.platform.launcher.PostDiscoveryFilter'), 'junit-platform');
  assert.equal(buildConfigKind('META-INF/services/org.junit.platform.launcher.LauncherSessionListener'), 'junit-platform', 'anywhere');
  assert.equal(buildConfigKind('modules/a/src/main/resources/META-INF/services/java.nio.file.spi.FileSystemProvider'), null);
  assert.equal(buildConfigKind('.github/workflows/ci.yml'), 'workflow');
  assert.equal(buildConfigKind('.github/workflows/release.yaml'), 'workflow');
  assert.equal(buildConfigKind('.github/dependabot.yml'), null);
  assert.equal(buildConfigKind('settings.gradle.kts'), 'settings');
  assert.equal(buildConfigKind('build-logic/settings.gradle.kts'), 'settings');
  assert.equal(classifyPath('scripts/ci/unit-test-shard-policy.v1.json')?.kind, 'watched-baseline');
});

test('build config: settings includes, junit-platform.properties', () => {
  const flag = (rel, a, b) => buildConfigChange(rel, a, b);
  const kts = 'rootProject.name = "x"\ninclude(\n  ":modules:core",\n  ":modules:app-util"\n)\n';
  assert.ok(flag('settings.gradle.kts', kts, kts.replace(',\n  ":modules:app-util"', '')), 'a project removed');
  assert.ok(flag('settings.gradle.kts', kts, kts.replace('  ":modules:app-util"\n', '  // ":modules:app-util"\n')), 'a project commented out');
  assert.equal(flag('settings.gradle.kts', kts, kts.replace('  ":modules:core",\n  ":modules:app-util"\n', '  ":modules:app-util",\n  ":modules:core"\n')), null, 'reordered');
  assert.equal(flag('settings.gradle.kts', kts, `${kts}include(":modules:new")\n`), null, 'a project added');
  assert.equal(flag('settings.gradle.kts', kts, kts.replace('include(\n  ":modules:core",\n', 'include(":modules:core")\ninclude(\n')), null, 'split into two includes');
  assert.ok(flag('settings.gradle.kts', `${kts}listOf("a").forEach { include(it) }\n`, kts), 'a dynamic include: on doubt');
  const groovy = "include ':modules:core',\n        ':modules:app-util'\n";
  assert.ok(flag('settings.gradle', groovy, "include ':modules:core'\n"), 'Groovy continuation');
  assert.equal(flag('settings.gradle.kts', kts, kts.replace('"x"', '"y"')), null, 'unrelated settings edit');
  assert.ok(flag('modules/a/src/main/resources/junit-platform.properties', null, 'junit.platform.execution.dryRun.enabled=true\n'));
  assert.ok(flag('modules/a/src/main/resources/junit-platform.properties', 'a=1\n', null), 'deleted');
});

test('build config: workflow lines that run or select tests', () => {
  const lineRuns = (code, o) => workflowLineRunsTests(code, o);
  for (const code of [
    'run: ./gradlew test', 'run: ./gradlew.bat build --console=plain', './gradlew :modules:core:check', ':modules:core:integrationTest',
    '-x :modules:core:test \\', 'run: ./gradlew assemble --exclude-task test', './gradlew ${{ matrix.tasks }}', './gradlew $TASKS',
    './gradlew :a:compileJava --tests io.x.A', 'npx vitest run', 'npm --prefix modules/ui-web test', 'npm test', 'pnpm run test:unit',
    'npx playwright test', 'cargo test --lib', 'node --test modules/x/a.test.mjs',
  ]) assert.ok(lineRuns(code), code);
  for (const code of [
    'run: ./gradlew assemble -PskipWebBuild=false', './gradlew pmdAll', 'run: ./gradlew.bat checkLicense', 'set -x',
    'node --test scripts/ci/a.test.mjs', 'python -m playwright install chromium', 'key: ms-playwright-${{ runner.os }}',
    "GW='./gradlew'; [ \"$RUNNER_OS\" = \"Windows\" ] && GW='./gradlew.bat'", 'chmod +x ./gradlew',
  ]) assert.ok(!lineRuns(code), code);
  const governance = { rootScriptRunsProductTests: (n) => n === 'test:ui' };
  assert.ok(!lineRuns('npm run test:lint-ps1', governance), 'a root governance script');
  assert.ok(lineRuns('npm run test:ui', governance), 'a root script that runs product tests');
  assert.ok(lineRuns('npm --prefix modules/ui-web run test:lint', governance), 'not the root');
  const root = rootScriptResolver([JSON.stringify({ scripts: { 'test:g': 'node --test scripts/a.test.mjs', 'test:ui': 'npm --prefix modules/ui-web test' } }), null]);
  assert.deepEqual(['test:g', 'test:ui', 'test:gone'].map(root), [false, true, true]);
  assert.equal(rootScriptResolver(['{']), undefined, 'unparseable: every run counts');

  const wf = [
    'on:', '  pull_request:', 'jobs:', '  unit:', '    runs-on: ${{ matrix.os }}', '    strategy:', '      matrix:', '        include:',
    '          - os: ubuntu-latest', '            depth: 1', '            tasks: >-', '              :modules:core:test', '    steps:',
    '      - uses: actions/checkout@v7', '        with:', '          fetch-depth: ${{ matrix.depth }}', '      - name: Tests',
    '        run: |', '          # a shell comment naming vitest', '          "$GW" ${{ matrix.tasks }} --console=plain',
    '  lint:', '    runs-on: ubuntu-latest', '    steps:', '      - name: Lint', '        run: npm run lint:scripts', '',
  ].join('\n');
  const pkg = JSON.stringify({ scripts: { 'lint:scripts': 'eslint scripts', 'test:g': 'node --test scripts/a.test.mjs' } });
  const wflag = (a, b) => buildConfigChange('.github/workflows/ci.yml', a, b, { rootPackageJson: [pkg, pkg] });
  assert.ok(wflag(wf, wf.replace('--console=plain', '--console=plain || true')), 'a step that runs a task list value');
  assert.ok(wflag(wf, wf.replace('ubuntu-latest\n            depth', 'windows-latest\n            depth')), 'a matrix value runs-on reads');
  assert.ok(wflag(wf, wf.replace('        include:', '        exclude:\n          - os: ubuntu-latest\n        include:')), 'a matrix exclusion');
  assert.ok(wflag(wf, wf.replace('  pull_request:', '  pull_request:\n    paths-ignore: [modules/**]')), 'a trigger of a workflow that runs tests');
  assert.ok(wflag(wf, wf.replace('    runs-on: ${{', '    if: false\n    runs-on: ${{')), 'a job-level condition');
  assert.equal(wflag(wf, wf.replace('depth: 1', 'depth: 0')), null, 'a matrix value only checkout reads');
  assert.equal(wflag(wf, wf.replace('naming vitest', 'naming jest')), null, 'a comment');
  assert.equal(wflag(wf, wf.replace('name: Tests', 'name: Unit tests')), null, 'a step name');
  assert.equal(wflag(wf, wf.replace('run: npm run lint:scripts', 'run: npm run lint:scripts && npm run test:g')), null, 'a root governance test script');
  assert.equal(wflag(wf.replace('    runs-on: ubuntu-latest\n    steps:\n      - name: Lint', '    runs-on: windows-latest\n    steps:\n      - name: Lint'), wf), null, 'runs-on of a job without tests');
  const scan = scanWorkflow(wf);
  assert.equal(scan.lines[19].step, scan.lines[16].step, 'block scalar lines belong to their step');
  assert.equal(scan.lines[19].key, 'run');
});

test('build config: helpers split identifiers, strip comments and diff lines', () => {
  assert.deepEqual(identifierParts('excludeTestsMatching(useJUnitPlatform) max_parallel_forks'),
    ['exclude', 'tests', 'matching', 'use', 'j', 'unit', 'platform', 'max', 'parallel', 'forks']);
  const s = scanBlocks('tasks.named("test") { // comment with {\n  x = "}"\n}\n');
  assert.ok(s.balanced, 'braces inside comments and strings do not count');
  assert.deepEqual(changedLineIndices(['a', 'b', 'c'], ['a', 'x', 'c']), { removed: [1], added: [1] });
});

test('build config: flags test selection and execution, passes ordinary edits', () => {
  const gradle = 'dependencies {\n  implementation("g:a:1")\n  testImplementation("g:t:1")\n}\n\ntasks.named<Test>("test") {\n  maxHeapSize = "256m"\n}\n';
  const flag = (rel, a, b) => buildConfigChange(rel, a, b);
  assert.equal(flag('modules/core/build.gradle.kts', gradle, gradle.replace('g:a:1', 'g:a:2').replace('g:t:1', 'g:t:2')), null, 'dependency bumps');
  assert.ok(flag('modules/core/build.gradle.kts', gradle, gradle.replace('256m', '512m')), 'a line inside a Test block');
  assert.ok(flag('modules/core/build.gradle.kts', gradle, gradle.replace('  maxHeapSize', '  filter { excludeTestsMatching("X") }\n  maxHeapSize')));
  assert.ok(flag('modules/core/build.gradle.kts', gradle, `${gradle}tasks.withType<Test>().configureEach { enabled = false }\n`));
  assert.ok(flag('modules/core/build.gradle.kts', gradle, gradle.replace('}\n\ntasks', '\ntasks')), 'unbalanced: flagged on doubt');
  assert.equal(flag('modules/core/build.gradle.kts', gradle, gradle.replace('tasks.named', '// a note\ntasks.named')), null, 'a comment line');
  const tagged = 'val win = isWindows()\nconfigure {\n  useJUnitPlatform {\n    if (!win) {\n      excludeTags("windows")\n    }\n  }\n}\n';
  assert.ok(flag('build-logic/src/main/kotlin/P.kt', tagged, tagged.replace('    if (!win) {\n      excludeTags("windows")\n    }\n', '    excludeTags("windows")\n')));
  assert.ok(flag('build-logic/src/main/kotlin/P.kt', tagged, tagged.replace('isWindows()', 'false')), 'a value read in test context');
  const vite = "export default {\n  build: { outDir: 'dist' },\n  test: {\n    include: ['src/**/*.test.ts'],\n  },\n};\n";
  assert.equal(flag('modules/ui-web/vite.config.js', vite, vite.replace("'dist'", "'out'")), null);
  assert.ok(flag('modules/ui-web/vite.config.js', vite, vite.replace("'src/**/*.test.ts'", "'src/a/**/*.test.ts'")));
  assert.ok(flag('modules/ui-web/vitest.config.lockdown.ts', 'export default {};\n', 'export default { };\n'), 'a test runner config: every change');
  const pkg = (o) => JSON.stringify({ name: 'ui-web', scripts: { build: 'vite build', 'test:unit:run': 'vitest run', ...o }, devDependencies: { vite: '^7' } });
  assert.equal(flag('modules/ui-web/package.json', pkg({}), pkg({ build: 'vite build --mode prod' })), null);
  assert.ok(flag('modules/ui-web/package.json', pkg({}), pkg({ 'test:unit:run': 'vitest run src/api' })));
  assert.equal(flag('modules/w/package.json', null, JSON.stringify({ name: 'w', scripts: { build: 'vite build' } })), null, 'a new package without test scripts');
  assert.ok(flag('modules/w/package.json', null, pkg({})), 'a new package with test scripts');
  assert.ok(packageTestProjection(pkg({}), { productModule: true }).includes('vitest run'));
  assert.equal(flag('gradle.properties', 'org.gradle.jvmargs=-Xmx2g\n', 'org.gradle.jvmargs=-Xmx3g\n'), null);
  assert.ok(flag('gradle.properties', 'a=1\n', 'a=1\ntest.retries=2\n'));
});

// ---- frontend helpers only tests import --------------------------------------------------------------
test('test support: import forms and the reachability from production roots', () => {
  const specs = importSpecifiers([
    "import a from './a.js';", "export { b } from './b';", "import type { C } from '@/c';", "// import x from './commented';",
    "const d = await import('./d');", "vi.mock('./e', () => ({}));", "const f = new URL('./f.wasm', import.meta.url);",
    "const g = import.meta.glob('./g/*.ts');", "const s = 'import y from \"./in-string\"';",
  ].join('\n')).map((s) => s.spec);
  assert.deepEqual(specs.filter((s) => s !== './in-string').sort(), ['./a.js', './b', './d', './e', './f.wasm', './g/*.ts', '@/c'].sort());
  assert.ok(!specs.includes('./commented'));
  const m = 'modules/w';
  const files = new Map(Object.entries({
    [`${m}/index.html`]: '<script type="module" src="/src/main.ts"></script>',
    [`${m}/vite.config.js`]: "import { cfg } from './src/config';",
    [`${m}/src/config.ts`]: 'export const cfg = 1;',
    [`${m}/src/main.ts`]: "import { app } from './app';",
    [`${m}/src/app.ts`]: "export const app = 1;",
    [`${m}/src/app.test.ts`]: "import { app } from './app';\nimport { host } from './testHost';\nimport { main } from './main';",
    [`${m}/src/testHost.ts`]: "import { deep } from './deep';\nexport const host = 1;",
    [`${m}/src/deep.ts`]: "import { host } from './testHost';\nexport const deep = 1;",
    [`${m}/src/orphan.ts`]: 'export const o = 1;',
    [`${m}/src/style.css`]: null,
  }));
  assert.deepEqual(testOnlyFiles(files, m), [`${m}/src/deep.ts`, `${m}/src/testHost.ts`]);
  assert.deepEqual([...parseTestSupportList(renderTestSupportList(['b', 'a'], 'd'))], ['a', 'b']);
  assert.throws(() => parseTestSupportList('{"schema":"other","paths":[]}'));
});

test('test support: every frontend file only tests import is in the committed list', () => {
  const listed = parseTestSupportList(fs.readFileSync(path.join(REPO, TEST_SUPPORT_PATHS_FILE), 'utf8'));
  const missing = scanTestOnlyImports(REPO).filter((p) => !listed.has(p));
  assert.deepEqual(missing, [], `only tests import these files; add them to ${TEST_SUPPORT_PATHS_FILE}`);
});

// ---- fail-before: "used to throw, now does not" (D4) ------------------------------------------------
test('runners: real Gradle JUnit XML; assertDoesNotThrow fails as an assertion, a bare throw as an exception', () => {
  // Produced by Gradle 9 / JUnit 5.14.4 from (hostname redacted):
  //   static void parseHeader(String s) { if (s.isEmpty()) throw new IllegalStateException("empty header"); }
  //   @Test void wrapped() { assertDoesNotThrow(() -> parseHeader("")); }
  //   @Test void bare() { parseHeader(""); }
  const xml = fs.readFileSync(path.join(HERE, '..', '..', '_fixtures', 'test-intent', 'junit', 'TEST-io.x.ParserTest.xml'), 'utf8');
  const cases = parseJUnitXml(xml);
  const wrapped = selectJUnitCases(cases, { fqcn: 'io.x.ParserTest', method: 'wrapped' });
  const bare = selectJUnitCases(cases, { fqcn: 'io.x.ParserTest', method: 'bare' });
  assert.deepEqual([wrapped.length, bare.length], [1, 1]);
  assert.equal(wrapped[0].failureType, 'org.opentest4j.AssertionFailedError');
  assert.equal(bare[0].failureType, 'java.lang.IllegalStateException');
  assert.equal(classifyFailBefore({ runner: 'gradle', cases: wrapped }).classification, 'assertion');
  assert.equal(classifyFailBefore({ runner: 'gradle', cases: bare }).classification, 'exception', 'strict: an exception is not an assertion');
});

if (failures.length > 0) {
  console.error(`test-intent units: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`test-intent units: all ${passed} passed`);
