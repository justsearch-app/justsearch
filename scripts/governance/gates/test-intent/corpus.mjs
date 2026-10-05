/**
 * The test-intent detection corpus — tempdoc 966 D1 "Detection corpus", plus the scenario checks for
 * S1–S10 and S12. Every case is a real scratch git repo (scenario.mjs) analysed end to end.
 *
 * Each case: { name, scenario, expect: 'pass'|'fail', rules?: [ruleIds that must fire as errors],
 *              flagged?: [exact flagged item ids], notFlagged?: [ids that must not be flagged],
 *              check?: (result) => void }
 *
 * The D1 list, in the design's order, is the first block (`d1-*`); each says flagged-and-fails or
 * passes exactly as D1 states.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

import { analyzeTestIntent, formatReport } from './analyze.mjs';
import { appendRecord, parseChangeset, writeSkeleton } from './changeset.mjs';
import { changesetText } from './scenario.mjs';
import { contentDigest, evidenceSelfDigest, EVIDENCE_PRODUCER, EVIDENCE_SCHEMA } from './evidence.mjs';
import { TEST_SUPPORT_PATHS_FILE, renderTestSupportList } from './test-support.mjs';

// ---- the base tree every case starts from --------------------------------------------------------
export const P = {
  calc: 'modules/core/src/main/java/io/x/Calc.java',
  test: 'modules/core/src/test/java/io/x/CalcTest.java',
  golden: 'modules/core/src/test/resources/golden/calc.json',
  contract: 'docs/contracts/calc-contract.md',
  decision: 'docs/decisions/0001-calc-adds.md',
  ownerDoc: 'docs/tempdocs/100-owner-words.md',
  suppression: 'scripts/ci/suppression-ratchet-baseline.v1.json',
  strength: 'gates/test-efficacy/strength-baseline.v1.json',
  seams: 'governance/logic-seams.v1.json',
  archunit: 'modules/app-launcher/archunit_store/stored.rules',
  evidencePolicy: 'scripts/ci/test-evidence-policy.v1.json',
  rustLib: 'modules/shell/src-tauri/src/lib.rs',
  rustHandoff: 'modules/shell/src-tauri/src/updater_handoff_tests.rs',
  rustUpdater: 'modules/shell/src-tauri/src/updater.rs',
  uiTest: 'modules/ui-web/src/views/list.test.ts',
  uiLockdown: 'modules/ui-web/src/views/list-lockdown.test.ts',
  uiMock: 'modules/ui-web/src/mocks/fixtures.mjs',
  uiSetup: 'modules/ui-web/src/__test-setup__/setup.ts',
  uiFixture: 'modules/ui-web/src/api/__fixtures__/search.json',
  uiProd: 'modules/ui-web/src/views/list.ts',
  scriptTest: 'scripts/ci/some-check.test.mjs',
  jvmPlugin: 'build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt',
  coreBuild: 'modules/core/build.gradle.kts',
  viteConfig: 'modules/ui-web/vite.config.js',
  uiPackage: 'modules/ui-web/package.json',
  uiHelper: 'modules/ui-web/src/shell-v0/plugin-api/testHostApi.ts',
  supportList: TEST_SUPPORT_PATHS_FILE,
  oracle: 'modules/system-tests/src/main/java/io/x/judge/AnswerJudge.java',
  junitMain: 'modules/app-util/src/main/resources/junit-platform.properties',
  workflow: '.github/workflows/ci.yml',
  shardPolicy: 'scripts/ci/unit-test-shard-policy.v1.json',
  settings: 'settings.gradle.kts',
  docsWorkflow: '.github/workflows/docs.yml',
  gradleProperties: 'gradle.properties',
};

const JVM_PLUGIN = [
  'package conventions',
  '',
  'class JvmBaseConventionsPlugin : Plugin<Project> {',
  '  override fun apply(project: Project) {',
  '    val isWindowsHost = System.getProperty("os.name").lowercase().contains("windows")',
  '    // Windows-only tests run where they can run; they are excluded on other hosts.',
  '    project.tasks.withType(Test::class.java).configureEach {',
  '      useJUnitPlatform {',
  '        val excluded = mutableListOf<String>()',
  '        if (!isWindowsHost) {',
  '          excluded.add("windows")',
  '        }',
  '        excludeTags(*excluded.toTypedArray())',
  '      }',
  '      maxHeapSize = "384m"',
  '    }',
  '    project.tasks.withType(JavaExec::class.java).configureEach {',
  '      jvmArgs("--enable-native-access=ALL-UNNAMED")',
  '    }',
  '  }',
  '}',
  '',
].join('\n');

const CORE_BUILD = [
  'plugins {',
  '  `java-library`',
  '}',
  '',
  'dependencies {',
  '  implementation("com.google.guava:guava:33.0.0-jre")',
  '  testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")',
  '}',
  '',
  'tasks.named<Test>("test") {',
  '  maxHeapSize = "256m"',
  '}',
  '',
].join('\n');

const VITE_CONFIG = [
  "import { defineConfig } from 'vite';",
  '',
  'export default defineConfig({',
  "  build: { outDir: 'dist' },",
  '  test: {',
  "    environment: 'node',",
  "    include: ['src/**/*.{test,spec}.{js,ts,tsx}'],",
  "    exclude: ['e2e/**', 'node_modules/**', 'src/**/*-lockdown.test.{js,ts,tsx}'],",
  '  },',
  '});',
  '',
].join('\n');

const UI_PACKAGE = JSON.stringify({
  name: 'ui-web',
  scripts: { build: 'vite build', 'test:unit:run': 'vitest run', 'test:unit:lockdown': 'vitest run --config vitest.config.lockdown.ts' },
  devDependencies: { vite: '^7.0.0', vitest: '^3.0.0' },
}, null, 2) + '\n';

/** CI files the selection-surface cases add to the base tree (BASE itself stays as it was). */
const CI_WORKFLOW = [
  'name: CI',
  'on:',
  '  pull_request:',
  '  push:',
  '    branches: [main]',
  'env:',
  "  JAVA_VERSION: '25'",
  'jobs:',
  '  unit-tests:',
  '    name: Unit tests (${{ matrix.lane }})',
  '    runs-on: ubuntu-latest',
  '    strategy:',
  '      fail-fast: false',
  '      matrix:',
  '        include:',
  '          - lane: core',
  '            gradle_tasks: >-',
  '              :modules:core:test',
  '              :modules:app-util:test',
  '    steps:',
  '      - name: Checkout',
  '        uses: actions/checkout@v7',
  '      - name: Cache Gradle',
  '        uses: actions/cache@v4',
  '        with:',
  '          path: ~/.gradle/caches',
  "          key: gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle.kts') }}",
  '      - name: Unit tests',
  '        run: |',
  '          ./gradlew ${{ matrix.gradle_tasks }} -PskipWebBuild=true --console=plain',
  '  frontend:',
  '    runs-on: ubuntu-latest',
  '    steps:',
  '      - uses: actions/checkout@v7',
  '      - name: Frontend unit tests',
  '        working-directory: modules/ui-web',
  '        run: npm ci && npx vitest run',
  '',
].join('\n');

const SHARD_POLICY = JSON.stringify({
  kind: 'justsearch-unit-test-shard-policy.v1',
  version: 1,
  lanes: [{ lane: 'core', gradleTasks: [':modules:core:test', ':modules:app-util:test'] }],
}, null, 2) + '\n';

const SETTINGS = [
  'rootProject.name = "x"',
  '',
  'include(',
  '  ":modules:core",',
  '  ":modules:app-util"',
  ')',
  '',
].join('\n');

/** A workflow that runs no tests. */
const DOCS_WORKFLOW = [
  'name: Docs',
  'on:',
  '  pull_request:',
  'env:',
  '  NODE_OPTIONS: --max-old-space-size=2048',
  'jobs:',
  '  docs:',
  '    runs-on: ubuntu-latest',
  '    steps:',
  '      - uses: actions/checkout@v7',
  '      - run: npm run lint:docs',
  '',
].join('\n');

const GRADLE_PROPERTIES = 'org.gradle.jvmargs=-Xmx2g\norg.gradle.caching=true\nkotlinVersion=2.1.0\nversion=0.3.0\n';

const WITH_CI = {
  [P.workflow]: CI_WORKFLOW, [P.shardPolicy]: SHARD_POLICY, [P.settings]: SETTINGS,
  [P.docsWorkflow]: DOCS_WORKFLOW, [P.gradleProperties]: GRADLE_PROPERTIES,
};

const TEST_V1 = [
  'package io.x;',
  'import static org.junit.jupiter.api.Assertions.assertEquals;',
  'import org.junit.jupiter.api.Test;',
  'class CalcTest {',
  '  @Test void adds() { assertEquals(3, Calc.add(1, 2)); }',
  '  @Test void ratio() { assertEquals(0.5, Calc.ratio(1, 2), 0.001); }',
  '}',
  '',
].join('\n');

const edit = (from, to) => TEST_V1.replace(from, to);

export const BASE = {
  [P.calc]: 'package io.x;\nclass Calc {\n  static int add(int a, int b) { return a + b; }\n  static double ratio(int a, int b) { return (double) a / b; }\n}\n',
  [P.test]: TEST_V1,
  [P.golden]: '{ "sum": 3 }\n',
  [P.contract]: '# Calc contract\n\nThe calculator adds two numbers.\n',
  [P.decision]: '# ADR-0001\n\nOwner decision (owner words, 2026-01-02): "Calc.add returns the arithmetic sum."\n',
  [P.ownerDoc]: '# 100\n\nThe owner wrote: "ratios are reported to three decimals" (2026-01-03).\n',
  [P.suppression]: '{ "version": 1, "files": { "modules/core/src/test/java/io/x/CalcTest.java": 0 } }\n',
  [P.strength]: '{ "schema": "pit-strength-baseline.v1", "seams": { "calc": { "minStrength": 0.9 } } }\n',
  [P.seams]: JSON.stringify({ version: 1, seams: [{ id: 'calc', law: 'add is the arithmetic sum', targetTests: ['io.x.CalcTest'], guard: 'test:CalcTest' }] }, null, 2) + '\n',
  [P.archunit]: 'rule-a=store-a\n',
  [P.evidencePolicy]: '{ "version": 1, "lanes": [] }\n',
  [P.rustLib]: 'pub fn add(a: i32, b: i32) -> i32 {\n    a + b\n}\n\n#[cfg(test)]\nmod tests {\n    use super::*;\n    #[test]\n    fn adds() {\n        assert_eq!(add(1, 2), 3);\n    }\n}\n',
  [P.rustUpdater]: 'pub fn ver() -> &\'static str {\n    "1"\n}\n\n#[cfg(test)]\n#[path = "updater_handoff_tests.rs"]\nmod handoff_tests;\n',
  [P.rustHandoff]: 'use super::*;\n\nfn helper() -> &\'static str {\n    ver()\n}\n\n#[test]\nfn version_is_one() {\n    assert_eq!(helper(), "1");\n}\n',
  [P.uiTest]: "import { expect, it } from 'vitest';\nit('lists', () => { expect([1].length).toBe(1); });\n",
  [P.uiLockdown]: "import { expect, it } from 'vitest';\nit('locks', () => { expect(true).toBe(true); });\n",
  [P.uiMock]: 'export const rows = [1, 2];\n',
  [P.uiSetup]: 'export {};\n',
  [P.uiFixture]: '{ "hits": 2 }\n',
  [P.uiProd]: 'export const list = (r) => r;\n',
  [P.scriptTest]: "import assert from 'node:assert';\nassert.equal(1, 1);\n",
  [P.jvmPlugin]: JVM_PLUGIN,
  [P.coreBuild]: CORE_BUILD,
  [P.viteConfig]: VITE_CONFIG,
  [P.uiPackage]: UI_PACKAGE,
  [P.uiHelper]: 'export const testHost = () => ({ ok: true });\n',
  [P.supportList]: renderTestSupportList([P.uiHelper], 'corpus'),
  [P.oracle]: 'package io.x.judge;\nclass AnswerJudge {\n  static final double PASS_SCORE = 0.8;\n}\n',
};

// ---- entry and record helpers --------------------------------------------------------------------
export const SCENARIO_SOURCE = {
  kind: 'owner-adopted-scenario',
  task: 't-corpus-1',
  scenario: 'S1',
  quote: 'Calc.add returns the sum of its two arguments plus one.',
  label: 'agent-drafted, owner-adopted',
};
const obsolete = (items, extra = {}) => ({ items, class: 'Obsolete', sources: [SCENARIO_SOURCE], conflictSearch: 'none', ...extra });
const ACCEPT = [{ role: 'verifier', session: 'verifier-session-1', verdict: 'accept' }];
const changedTest = { write: { [P.test]: edit('assertEquals(3, Calc.add(1, 2))', 'assertEquals(4, Calc.add(1, 2))') } };
const accepted = (extra = {}) => ({
  main: BASE,
  branch: [changedTest],
  changeset: { entries: [obsolete([P.test])] },
  accept: ACCEPT,
  ...extra,
});

/** Evidence exactly as run-evidence.mjs writes it (the corpus plays the script). */
export function evidenceText({ checks, failBefore = null, tamper = false }) {
  const ev = {
    schema: EVIDENCE_SCHEMA,
    producer: { script: EVIDENCE_PRODUCER, scriptDigest: 'sha256:corpus' },
    createdAt: '2026-10-05T00:00:00.000Z',
    revision: '0'.repeat(40),
    tree: '1'.repeat(40),
    environment: { platform: 'corpus', node: process.version },
    runs: [{ command: './gradlew :modules:core:test --tests io.x.CalcTest --rerun', exitCode: 0 }],
    checks: checks.map((c) => ({
      id: c.id,
      file: c.id.split('#')[0],
      runner: 'gradle',
      contentDigest: contentDigest(c.content),
      executed: c.executed ?? true,
      skipped: c.skipped ?? false,
      outcome: c.outcome ?? 'passed',
      cases: [],
    })),
    failBefore,
  };
  ev.digest = evidenceSelfDigest(ev);
  if (tamper) ev.checks[0].outcome = 'passed-by-hand';
  return JSON.stringify(ev, null, 2) + '\n';
}

const NEW_TEST = 'modules/core/src/test/java/io/x/CalcNegTest.java';
const NEW_TEST_TEXT = 'package io.x;\nclass CalcNegTest {\n  @Test void negatives() { assertEquals(-3, Calc.add(-1, -2)); }\n}\n';
const NEW_CHECK = `${NEW_TEST}#negatives`;
const EVIDENCE = 'gates/test-intent/evidence/calc-neg.json';
const newEntry = (extra = {}) => ({
  items: [NEW_TEST],
  class: 'New',
  behaviour: 'Calc.add sums negative numbers.',
  sources: [SCENARIO_SOURCE],
  evidence: EVIDENCE,
  newChecks: [NEW_CHECK],
  ...extra,
});
const newCase = ({ evidence, entry = newEntry(), afterAccept } = {}) => ({
  main: BASE,
  branch: [{ write: { [NEW_TEST]: NEW_TEST_TEXT, [EVIDENCE]: evidence ?? evidenceText({ checks: [{ id: NEW_CHECK, content: NEW_TEST_TEXT }] }) } }],
  changeset: { entries: [entry] },
  accept: ACCEPT,
  ...(afterAccept ? { afterAccept } : {}),
});

const contractEdit = { write: { [P.contract]: '# Calc contract\n\nThe calculator adds two integers.\n' } };

/**
 * Merged history on main for the reconsider rule: `audited` squash commits for PRs #5, #10, ...
 * (every fifth number, so all audit-selected), each adding a changeset with an entry and records;
 * the first `tieBreaks` of them carry an acceptor/auditor disagreement settled by a tie-break, the
 * next `disagreements` a disagreement without one.
 */
function mergedHistory({ audited, tieBreaks = 0, disagreements = 0 }) {
  const rec = (o) => ({ kind: 'acceptance', role: 'verifier', session: 'v', verdict: 'accept', digest: 'sha256:merged', ...o });
  return Array.from({ length: audited }, (_, i) => {
    const pr = (i + 1) * 5;
    const records = [rec(), rec({ kind: 'audit', session: 'a', verdict: i < tieBreaks + disagreements ? 'reject' : 'accept' })];
    if (i < tieBreaks) records.push(rec({ kind: 'tie-break', session: 't' }));
    const text = records.reduce((t, r) => appendRecord(t, r), changesetText({ entries: [{ items: [`m${pr}`], class: 'Obsolete' }] }));
    return { write: { [`gates/test-intent/.changesets/merged-${pr}.md`]: text }, message: `Merged change (#${pr})` };
  });
}

/**
 * P6: the test is deleted with production code; the Obsolete entry's only source is the removal.
 * `calc: null` deletes Calc.java, otherwise it is rewritten to the given text.
 */
function removalCase({ calc, removed, extra = {} }) {
  const step = calc === null
    ? { delete: [P.test, P.calc, ...(extra.delete ?? [])] }
    : { delete: [P.test, ...(extra.delete ?? [])], ...(calc === BASE[P.calc] ? {} : { write: { [P.calc]: calc } }) };
  return {
    main: BASE,
    branch: [step],
    changeset: { entries: [{ items: [P.test], class: 'Obsolete', sources: [{ kind: 'removal', removed }], conflictSearch: 'none' }] },
    accept: ACCEPT,
  };
}

const hasError = (r, rule) => r.findings.some((f) => f.level === 'error' && f.ruleId === `test-intent/${rule}`);

// ---- the cases -----------------------------------------------------------------------------------
export const CASES = [
  // D1 detection corpus, in the design's order.
  {
    name: 'd1-01 one-line src/main change plus the matching assertEquals value',
    scenario: { main: BASE, branch: [{ write: { [P.calc]: BASE[P.calc].replace('a + b;', 'a + b + 1;'), ...changedTest.write } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.test],
  },
  { name: 'd1-02 a deleted test', scenario: { main: BASE, branch: [{ delete: [P.test] }] }, expect: 'fail', rules: ['uncovered'], flagged: [P.test] },
  {
    name: 'd1-03 a renamed-and-edited test flags both sides',
    scenario: {
      main: BASE,
      branch: [
        { move: [[P.test, 'modules/core/src/test/java/io/x/CalcAddTest.java']] },
        { write: { 'modules/core/src/test/java/io/x/CalcAddTest.java': edit('class CalcTest', 'class CalcAddTest').replace('0.001', '0.01') } },
      ],
    },
    expect: 'fail', rules: ['uncovered'], flagged: ['modules/core/src/test/java/io/x/CalcAddTest.java', P.test],
  },
  {
    name: 'd1-04 assertEquals -> assertNotNull',
    scenario: { main: BASE, branch: [{ write: { [P.test]: edit('assertEquals(3, Calc.add(1, 2))', 'assertNotNull(Calc.add(1, 2))') } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.test],
  },
  {
    name: 'd1-05 an existing excluded tag added',
    scenario: { main: BASE, branch: [{ write: { [P.test]: edit('class CalcTest {', '@Tag("benchmark")\nclass CalcTest {') } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.test],
  },
  {
    name: 'd1-06 assumeTrue added',
    scenario: { main: BASE, branch: [{ write: { [P.test]: edit('@Test void adds() {', '@Test void adds() { assumeTrue(false);') } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.test],
  },
  {
    name: 'd1-07 a golden-file value edited',
    scenario: { main: BASE, branch: [{ write: { [P.golden]: '{ "sum": 4 }\n' } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.golden],
  },
  {
    name: 'd1-08 a baseline raised (suppression ratchet)',
    scenario: { main: BASE, branch: [{ write: { [P.suppression]: BASE[P.suppression].replace(': 0', ': 2') } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.suppression],
  },
  {
    name: 'd1-09 a byte-identical move passes',
    scenario: { main: BASE, branch: [{ move: [[P.test, 'modules/core/src/test/java/io/x/calc/CalcTest.java']] }] },
    expect: 'pass', flagged: [],
    check: (r) => assert.equal(r.moves.length, 1),
  },
  {
    name: 'd1-10 a citation to a document added in the PR fails',
    scenario: accepted({
      branch: [changedTest, { write: { 'docs/tempdocs/200-new.md': 'The owner said: "add returns sum plus one".\n' } }],
      changeset: {
        entries: [obsolete([P.test], {
          sources: [{ kind: 'owner-words', quote: 'add returns sum plus one', location: 'docs/tempdocs/200-new.md:1' }],
        })],
      },
    }),
    expect: 'fail', rules: ['source-written-in-pr'],
  },
  {
    name: 'd1-11 an acceptance record made stale by editing a flagged item fails',
    scenario: accepted({ afterAccept: [{ write: { [P.test]: edit('assertEquals(3, Calc.add(1, 2))', 'assertEquals(5, Calc.add(1, 2))') } }] }),
    expect: 'fail', rules: ['acceptance-stale'],
  },
  {
    name: 'd1-12 an accepted PR built as a merge group after main moved passes',
    scenario: accepted({
      mainMoves: [{ write: { [P.calc]: BASE[P.calc].replace('ratio', 'quotient'), [P.contract]: '# Calc contract\n\nMoved.\n' } }],
      event: { name: 'merge_group', pr: 21 },
    }),
    expect: 'pass', flagged: [P.test],
  },
  {
    name: 'd1-13 a contract document edited with no test change passes',
    scenario: { main: BASE, branch: [{ write: { [P.contract]: '# Calc contract\n\nThe calculator adds two integers.\n' } }] },
    expect: 'pass', flagged: [],
  },

  // S1 scope beyond Java.
  {
    name: 's1 frontend tests, lockdown tests, mocks, setup and fixtures are flagged; production is not',
    scenario: {
      main: BASE,
      branch: [{ write: {
        [P.uiTest]: BASE[P.uiTest].replace('toBe(1)', 'toBeGreaterThan(0)'),
        [P.uiLockdown]: BASE[P.uiLockdown].replace('true).toBe(true)', 'true).toBeTruthy()'),
        [P.uiMock]: 'export const rows = [1, 2, 3];\n',
        [P.uiSetup]: 'export const x = 1;\n',
        [P.uiFixture]: '{ "hits": 3 }\n',
        [P.uiProd]: 'export const list = (r) => r.slice();\n',
      } }],
    },
    expect: 'fail', flagged: [P.uiFixture, P.uiMock, P.uiSetup, P.uiLockdown, P.uiTest].sort(),
  },
  {
    name: 's1 rust: a production-only edit is not flagged',
    scenario: { main: BASE, branch: [{ write: { [P.rustLib]: BASE[P.rustLib].replace('a + b\n', 'b + a\n') } }] },
    expect: 'pass', flagged: [],
  },
  {
    name: 's1 rust: an edit inside #[cfg(test)] is flagged; a test-module file is flagged whole',
    scenario: {
      main: BASE,
      branch: [{ write: {
        [P.rustLib]: BASE[P.rustLib].replace('assert_eq!(add(1, 2), 3);', 'assert!(add(1, 2) > 0);'),
        [P.rustHandoff]: BASE[P.rustHandoff].replace('ver()', '"1"'),
      } }],
    },
    expect: 'fail', flagged: [`${P.rustLib}#cfg(test)`, P.rustHandoff].sort(),
  },
  {
    name: 's1 watched baselines: ArchUnit store, test-evidence policy, logic-seam law are flagged; a seam guard edit is not',
    scenario: {
      main: BASE,
      branch: [{ write: {
        [P.archunit]: 'rule-a=store-b\n',
        [P.evidencePolicy]: '{ "version": 1, "lanes": ["x"] }\n',
        [P.seams]: BASE[P.seams].replace('add is the arithmetic sum', 'add is a sum'),
      } }],
    },
    expect: 'fail', flagged: [P.archunit, `${P.seams}#law-targetTests`, P.evidencePolicy].sort(),
  },
  {
    name: 's1 a logic-seam edit outside law/targetTests is not flagged',
    scenario: { main: BASE, branch: [{ write: { [P.seams]: BASE[P.seams].replace('test:CalcTest', 'test:CalcTest + gate:x') } }] },
    expect: 'pass', flagged: [],
  },
  {
    name: 's1 script self-tests are out of scope and the output says so',
    scenario: { main: BASE, branch: [{ write: { [P.scriptTest]: "import assert from 'node:assert';\nassert.equal(2, 2);\n" } }] },
    expect: 'pass', flagged: [],
    check: (r) => assert.ok(r.findings.some((f) => f.ruleId === 'test-intent/out-of-scope' && /scripts\//.test(f.message) && /1 file/.test(f.message))),
  },

  // S2 weakenings that never touch an assertion line.
  {
    name: 's2 a conditional disable is flagged',
    scenario: { main: BASE, branch: [{ write: { [P.test]: edit('@Test void ratio()', '@DisabledOnOs(OS.LINUX) @Test void ratio()') } }] },
    expect: 'fail', flagged: [P.test],
  },
  {
    name: 's2 class-level configuration is flagged',
    scenario: { main: BASE, branch: [{ write: { [P.test]: edit('class CalcTest {', '@Timeout(1)\nclass CalcTest {') } }] },
    expect: 'fail', flagged: [P.test],
  },
  {
    name: 's2 an expectation change that never turns red (tolerance loosened) is flagged',
    scenario: { main: BASE, branch: [{ write: { [P.test]: edit('0.001', '0.5') } }] },
    expect: 'fail', flagged: [P.test],
  },

  // S3 skeleton and parse failures.
  {
    name: 's3 parse failures flag: an unscannable Rust file and an unparseable logic-seam register',
    scenario: {
      main: BASE,
      branch: [{ write: { [P.rustLib]: BASE[P.rustLib] + '\nfn broken() {\n', [P.seams]: '{ not json' } }],
    },
    expect: 'fail', flagged: [`${P.rustLib}#cfg(test)`, `${P.seams}#law-targetTests`].sort(),
  },
  {
    name: 's3 an unparseable changeset counts for nothing',
    scenario: { main: BASE, branch: [changedTest, { write: { 'gates/test-intent/.changesets/bad.md': '---\nschema: test-intent.v1\n---\nno json here\n' } }] },
    expect: 'fail', rules: ['changeset-unparseable', 'uncovered'],
  },

  {
    name: 's3 the gate writes a skeleton changeset listing every flagged item',
    scenario: { main: BASE, branch: [{ delete: [P.test], write: { [P.golden]: '{ "sum": 5 }\n', [P.uiMock]: 'export const rows = [];\n' } }] },
    expect: 'fail', flagged: [P.golden, P.test, P.uiMock].sort(),
    check: (r, root) => {
      const rel = writeSkeleton({ repoRoot: root, name: 'skeleton', items: r.uncovered });
      const parsed = parseChangeset(fs.readFileSync(path.join(root, rel), 'utf8'));
      assert.ok(parsed.ok, parsed.error);
      assert.deepEqual(parsed.entries.flatMap((e) => e.items).sort(), r.flagged.map((f) => f.id).sort());
      const again = analyzeTestIntent({ repoRoot: root, env: {} });
      assert.equal(again.verdict, 'fail', 'an unfilled skeleton covers nothing');
      assert.equal(again.uncovered.length, r.flagged.length);
    },
  },

  // S4 sources.
  {
    name: 's4 a source that does not exist fails',
    scenario: accepted({ changeset: { entries: [obsolete([P.test], { sources: [{ kind: 'decision-record', location: 'docs/decisions/0099-missing.md', quote: 'x' }] })] } }),
    expect: 'fail', rules: ['source-unresolved'],
  },
  {
    name: 's4 a quote that is not in the cited document fails',
    scenario: accepted({ changeset: { entries: [obsolete([P.test], { sources: [{ kind: 'decision-record', location: P.decision, quote: 'Calc.add returns the sum plus one.' }] })] } }),
    expect: 'fail', rules: ['source-unresolved'],
  },
  {
    name: 's4 a verbatim owner quote in a predating document passes',
    scenario: accepted({ changeset: { entries: [obsolete([P.test], { sources: [{ kind: 'owner-words', location: `${P.ownerDoc}:3`, quote: 'ratios are reported to three decimals' }] })] } }),
    expect: 'pass',
  },
  {
    name: 's4 a scenario citation without a quote or the owner-adopted label fails',
    scenario: accepted({ changeset: { entries: [obsolete([P.test], { sources: [{ kind: 'owner-adopted-scenario', task: 't-corpus-1', scenario: 'S1' }] })] } }),
    expect: 'fail', rules: ['entry-invalid'],
  },

  // S5 acceptance.
  { name: 's5 a covered and accepted PR passes locally', scenario: accepted(), expect: 'pass', flagged: [P.test] },
  {
    name: 's5 entries without an acceptance record fail',
    scenario: { ...accepted(), accept: [] },
    expect: 'fail', rules: ['acceptance-missing'],
  },
  {
    name: 's5 an acceptance record written by the author fails',
    scenario: accepted({ accept: [{ role: 'verifier', session: 'builder-session-1', verdict: 'accept' }] }),
    expect: 'fail', rules: ['acceptor-is-author'],
  },
  {
    name: 's5 rejection: reject alone fails',
    scenario: accepted({ accept: [{ role: 'verifier', session: 'verifier-a', verdict: 'reject' }] }),
    expect: 'fail', rules: ['acceptance-rejected'],
  },
  {
    name: 's5 rejection: reject then accept by another reviewer on the same content fails (disagreement)',
    scenario: accepted({ accept: [{ role: 'verifier', session: 'verifier-a', verdict: 'reject' }, { role: 'orchestrator', session: 'orchestrator-b', verdict: 'accept' }] }),
    expect: 'fail', rules: ['tie-break-required'],
  },
  {
    name: 's5 rejection: reject then accept by the same reviewer on the same content still fails',
    scenario: accepted({ accept: [{ role: 'verifier', session: 'verifier-a', verdict: 'reject' }, { role: 'verifier', session: 'verifier-a', verdict: 'accept' }] }),
    expect: 'fail', rules: ['acceptance-rejected'],
  },
  {
    name: 's5 rejection: reject then accept then a third reviewer accepts passes',
    scenario: accepted({
      accept: [
        { role: 'verifier', session: 'verifier-a', verdict: 'reject' },
        { role: 'orchestrator', session: 'orchestrator-b', verdict: 'accept' },
        { kind: 'tie-break', role: 'verifier', session: 'third-c', verdict: 'accept' },
      ],
    }),
    expect: 'pass',
  },
  {
    name: 's5 rejection: a tie-break by one of the disagreeing reviewers does not count',
    scenario: accepted({
      accept: [
        { role: 'verifier', session: 'verifier-a', verdict: 'reject' },
        { role: 'orchestrator', session: 'orchestrator-b', verdict: 'accept' },
        { kind: 'tie-break', role: 'orchestrator', session: 'orchestrator-b', verdict: 'accept' },
      ],
    }),
    expect: 'fail', rules: ['tie-break-required'],
  },
  {
    name: 's5 rejection: reject then content edit then accept passes',
    scenario: accepted({
      accept: [{ role: 'verifier', session: 'verifier-a', verdict: 'reject' }],
      afterAccept: [{ write: { [P.test]: edit('assertEquals(3, Calc.add(1, 2))', 'assertEquals(4, Calc.add(1, 2)); assertEquals(1, Calc.add(0, 0))') } }],
      acceptAgain: [{ role: 'verifier', session: 'verifier-a', verdict: 'accept' }],
    }),
    expect: 'pass', flagged: [P.test],
  },
  {
    name: 's5 a record with a wrong digest is stale',
    scenario: accepted({ accept: [{ role: 'verifier', session: 'verifier-session-1', verdict: 'accept', digestOverride: 'sha256:00' }] }),
    expect: 'fail', rules: ['acceptance-stale'],
  },
  {
    name: 's5 editing an entry after acceptance makes the record stale',
    scenario: accepted({
      afterAccept: [{ replaceIn: { 'gates/test-intent/.changesets/corpus.md': ['"conflictSearch": "none"', '"conflictSearch": "none found"'] } }],
    }),
    expect: 'fail', rules: ['acceptance-stale'],
  },
  {
    name: 's5 a pull_request run after main moved (rebase-equivalent) passes',
    scenario: accepted({ mainMoves: [{ write: { [P.calc]: BASE[P.calc].replace('ratio', 'quotient') } }], event: { name: 'pull_request', pr: 22 } }),
    expect: 'pass', flagged: [P.test],
  },
  {
    name: 's5 the squash push to main passes',
    scenario: accepted({ event: { name: 'push', pr: 23 } }),
    expect: 'pass', flagged: [P.test],
  },

  // S6/S7 what the gate can check mechanically.
  {
    name: 's6 Obsolete citing only task scope is rejected',
    scenario: accepted({ changeset: { entries: [obsolete([P.test], { sources: [{ kind: 'task-scope', quote: 'S11 says to change UnreferencedCodeTest' }] })] } }),
    expect: 'fail', rules: ['source-not-a-citation'],
  },
  {
    name: 's6 Obsolete without the decision-record conflict search fails',
    scenario: accepted({ changeset: { entries: [obsolete([P.test], { conflictSearch: '' })] } }),
    expect: 'fail', rules: ['entry-invalid'],
  },
  {
    name: 's7 a Defect pin whose only source is the defect source fails (the fix needs its own source)',
    scenario: {
      ...newCase({
        entry: {
          items: [NEW_TEST], class: 'Defect pin', reproduction: 'add(-1,-2) returned 3',
          defectSource: { kind: 'defect-source', safeguard: 'no crash', reproduction: 'NPE on negative input' },
          sources: [{ kind: 'defect-source', safeguard: 'no crash', reproduction: 'x' }],
          evidence: EVIDENCE, newChecks: [NEW_CHECK],
        },
        evidence: evidenceText({ checks: [{ id: NEW_CHECK, content: NEW_TEST_TEXT }], failBefore: { ref: 'main', checks: [{ id: NEW_CHECK, classification: 'assertion' }] } }),
      }),
    },
    expect: 'fail', rules: ['entry-invalid'],
  },
  {
    name: 's7 a delegated choice must quote the delegation and label the choice',
    scenario: accepted({
      changeset: { entries: [obsolete([P.test], { sources: [{ kind: 'delegated-choice', delegationQuote: 'Calc.add returns the arithmetic sum.', delegationLocation: P.decision, delegationScope: 'Calc', choice: 'sum plus one' }] })] },
    }),
    expect: 'fail', rules: ['entry-invalid'],
  },
  {
    name: 's7 a decision record must be under docs/decisions',
    scenario: accepted({ changeset: { entries: [obsolete([P.test], { sources: [{ kind: 'decision-record', location: P.ownerDoc, quote: 'ratios are reported to three decimals' }] })] } }),
    expect: 'fail', rules: ['source-unresolved'],
  },

  // S8 execution evidence.
  { name: 's8 a New entry with script evidence passes', scenario: newCase(), expect: 'pass', flagged: [NEW_TEST] },
  {
    name: 's8 a New entry without evidence fails',
    scenario: newCase({ entry: newEntry({ evidence: undefined }) }),
    expect: 'fail', rules: ['evidence-invalid'],
  },
  {
    name: 's8 a skipped new check is not a pass',
    scenario: newCase({ evidence: evidenceText({ checks: [{ id: NEW_CHECK, content: NEW_TEST_TEXT, executed: false, skipped: true, outcome: 'skipped' }] }) }),
    expect: 'fail', rules: ['evidence-invalid'],
  },
  {
    name: 's8 a check edited after the evidence run fails',
    scenario: newCase({ evidence: evidenceText({ checks: [{ id: NEW_CHECK, content: NEW_TEST_TEXT.replace('-3', '-4') }] }) }),
    expect: 'fail', rules: ['evidence-invalid'],
  },
  {
    name: 's8 a hand-edited evidence file fails its self-digest',
    scenario: newCase({ evidence: evidenceText({ checks: [{ id: NEW_CHECK, content: NEW_TEST_TEXT }], tamper: true }) }),
    expect: 'fail', rules: ['evidence-invalid'],
  },
  {
    name: 's8 repaired behaviour needs an assertion fail-before, not a compile failure',
    scenario: newCase({
      entry: newEntry({ repaired: true }),
      evidence: evidenceText({ checks: [{ id: NEW_CHECK, content: NEW_TEST_TEXT }], failBefore: { ref: 'main', checks: [{ id: NEW_CHECK, classification: 'compile' }] } }),
    }),
    expect: 'fail', rules: ['evidence-invalid'],
  },
  {
    name: 's8 a Defect pin with its own expectation source and an assertion fail-before passes',
    scenario: newCase({
      entry: {
        items: [NEW_TEST], class: 'Defect pin', reproduction: 'add(-1,-2) threw before the fix',
        defectSource: { kind: 'defect-source', safeguard: 'no crash', reproduction: 'add(-1,-2) threw' },
        sources: [SCENARIO_SOURCE], evidence: EVIDENCE, newChecks: [NEW_CHECK],
      },
      evidence: evidenceText({ checks: [{ id: NEW_CHECK, content: NEW_TEST_TEXT }], failBefore: { ref: 'main', checks: [{ id: NEW_CHECK, classification: 'assertion' }] } }),
    }),
    expect: 'pass',
  },

  // S9 mechanics.
  {
    name: 's9 pull_request: a test changed on main after the branch point is not this PR\'s item',
    scenario: accepted({ mainMoves: [{ write: { [P.golden]: '{ "sum": 33 }\n' } }], event: { name: 'pull_request', pr: 31 } }),
    expect: 'pass', flagged: [P.test], notFlagged: [P.golden],
  },
  {
    name: 's9 merge_group: same base discipline',
    scenario: accepted({ mainMoves: [{ write: { [P.golden]: '{ "sum": 33 }\n' } }], event: { name: 'merge_group', pr: 32 } }),
    expect: 'pass', flagged: [P.test], notFlagged: [P.golden],
  },
  {
    name: 's9 a changeset committed early in a multi-commit branch counts',
    scenario: accepted({ afterAccept: [{ write: { [P.contract]: '# Calc contract\n\nLater commit.\n' } }, { write: { 'docs/notes.md': 'n\n' } }] }),
    expect: 'pass',
  },
  { name: 's9 a shallow clone fails closed', scenario: { ...accepted(), shallow: true }, expect: 'fail', rules: ['base-unresolved'] },
  {
    name: 's9 a fork PR fails with the maintainer-completes message',
    scenario: { main: BASE, branch: [changedTest], event: { name: 'pull_request', pr: 33, fork: true } },
    expect: 'fail', rules: ['fork-pr', 'uncovered'],
    check: (r) => assert.ok(r.findings.some((f) => /maintainer agent completes the test-intent changeset/.test(f.message))),
  },
  {
    name: 's9 local: uncommitted work in the working tree is flagged',
    scenario: { main: BASE, branch: [], uncommitted: { write: { [P.golden]: '{ "sum": 9 }\n' } } },
    expect: 'fail', flagged: [P.golden],
  },

  // S10 existing records.
  {
    name: 's10 a strength-baseline change covered by a test-efficacy changeset needs only a reference',
    scenario: {
      main: BASE,
      branch: [{ write: {
        [P.strength]: BASE[P.strength].replace('0.9', '0.8'),
        'gates/test-efficacy/.changesets/lower.md': '---\nclassification: strength-regression\ntempdoc: 966\n---\nSeam simplified.\n',
      } }],
      changeset: { entries: [{ items: [P.strength], ref: 'gates/test-efficacy/.changesets/lower.md' }] },
    },
    expect: 'pass', flagged: [P.strength],
  },
  {
    name: 's10 a reference plus a second entry fails',
    scenario: {
      main: BASE,
      branch: [{ write: {
        [P.strength]: BASE[P.strength].replace('0.9', '0.8'),
        'gates/test-efficacy/.changesets/lower.md': '---\nclassification: strength-regression\ntempdoc: 966\n---\nSeam simplified.\n',
      } }],
      changeset: { entries: [{ items: [P.strength], ref: 'gates/test-efficacy/.changesets/lower.md' }, obsolete([P.strength])] },
      accept: ACCEPT,
    },
    expect: 'fail', rules: ['second-entry'],
  },
  {
    name: 's10 an ArchUnit store change cannot ride on a test-efficacy reference',
    scenario: {
      main: BASE,
      branch: [{ write: {
        [P.archunit]: 'rule-a=store-b\n',
        'gates/test-efficacy/.changesets/lower.md': '---\nclassification: strength-regression\ntempdoc: 966\n---\nx\n',
      } }],
      changeset: { entries: [{ items: [P.archunit], ref: 'gates/test-efficacy/.changesets/lower.md' }] },
    },
    expect: 'fail', rules: ['reference-not-allowed'],
  },
  {
    name: 's10 a suppression-ratchet raise with a full accepted entry passes',
    scenario: {
      main: BASE,
      branch: [{ write: { [P.suppression]: BASE[P.suppression].replace(': 0', ': 1') } }],
      changeset: { entries: [{ items: [P.suppression], class: 'Structural rule kept', rule: 'suppressions are ratcheted', whySurvives: 'one justified @Disabled for a flake under the recovery protocol' }] },
      accept: ACCEPT,
    },
    expect: 'pass', flagged: [P.suppression],
  },

  // S12 audit selection.
  {
    name: 's12 an audit-selected PR (#40) needs a second record',
    scenario: accepted({ event: { name: 'pull_request', pr: 40 } }),
    expect: 'fail', rules: ['audit-required'],
  },
  {
    name: 's12 an audit-selected PR with an agreeing audit passes',
    scenario: accepted({ accept: [...ACCEPT, { kind: 'audit', role: 'verifier', session: 'auditor-1', verdict: 'accept' }], event: { name: 'pull_request', pr: 40 } }),
    expect: 'pass',
  },
  {
    name: 's12 a disagreement without a tie-break fails',
    scenario: accepted({ accept: [...ACCEPT, { kind: 'audit', role: 'verifier', session: 'auditor-1', verdict: 'reject' }], event: { name: 'pull_request', pr: 40 } }),
    expect: 'fail', rules: ['tie-break-required'],
  },
  {
    name: 's12 the third reviewer decides a disagreement',
    scenario: accepted({
      accept: [...ACCEPT, { kind: 'audit', role: 'verifier', session: 'auditor-1', verdict: 'reject' }, { kind: 'tie-break', role: 'verifier', session: 'third-1', verdict: 'accept' }],
      event: { name: 'pull_request', pr: 40 },
    }),
    expect: 'pass',
  },
  {
    name: 's12 an auditor who is the acceptor does not count',
    scenario: accepted({ accept: [...ACCEPT, { kind: 'audit', role: 'verifier', session: 'verifier-session-1', verdict: 'accept' }], event: { name: 'pull_request', pr: 45 } }),
    expect: 'fail', rules: ['audit-required'],
  },

  // Measurement: the reconsider warning over the rolling four-week counts (never fails the gate).
  {
    name: 'measurement: tie-breaks at one in ten audited PRs (boundary, not above) give no warning',
    scenario: { main: BASE, branch: [contractEdit], mainMoves: mergedHistory({ audited: 10, tieBreaks: 1 }) },
    expect: 'pass', flagged: [],
    check: (r) => {
      assert.equal(r.reconsider?.audited, 10);
      assert.equal(r.reconsider?.count, 1);
      assert.equal(r.reconsider?.above, false);
      assert.ok(!r.findings.some((f) => f.ruleId === 'test-intent/reconsider-rate' && f.level === 'warning'));
      assert.ok(!/WARNING/.test(formatReport(r)));
    },
  },
  {
    name: 'measurement: tie-breaks above one in ten audited PRs print a marked warning and still pass',
    scenario: { main: BASE, branch: [contractEdit], mainMoves: mergedHistory({ audited: 10, tieBreaks: 2 }) },
    expect: 'pass', flagged: [],
    check: (r) => {
      const w = r.findings.find((f) => f.ruleId === 'test-intent/reconsider-rate' && f.level === 'warning');
      assert.ok(w, 'warning finding');
      assert.match(w.message, /third-reviewer tie-breaks were 2 of 10 audited PRs \(20\.0%\)/);
      assert.match(w.message, /orchestrator must report this as a finding in its next delivery/);
      assert.match(formatReport(r), /WARNING test-intent\/reconsider-rate \(does not fail the gate\)/);
    },
  },
  {
    name: 'measurement: before any tie-break, disagreements above one in ten audited PRs warn',
    scenario: { main: BASE, branch: [changedTest], mainMoves: mergedHistory({ audited: 10, disagreements: 2 }) },
    expect: 'fail', rules: ['uncovered'],
    check: (r) => {
      const w = r.findings.find((f) => f.ruleId === 'test-intent/reconsider-rate' && f.level === 'warning');
      assert.ok(w, 'warning finding');
      assert.match(w.message, /two-reviewer disagreements \(no tie-break yet\) were 2 of 10 audited PRs/);
      assert.ok(r.findings.filter((f) => f.level === 'error').every((f) => f.ruleId !== 'test-intent/reconsider-rate'));
    },
  },

  // P5 / P6.
  {
    name: 'p6 removing test-only production code with its test needs only the removal as source (removed members)',
    scenario: removalCase({ calc: 'package io.x;\nclass Calc {}\n', removed: [`${P.calc}#add`, `${P.calc}#ratio`] }),
    expect: 'pass', flagged: [P.test],
  },
  { name: 'p5 a PR touching nothing in scope sees no failure', scenario: { main: BASE, branch: [{ write: { [P.calc]: BASE[P.calc].replace('a + b', 'b + a') } }] }, expect: 'pass', flagged: [] },

  // Hole 1: a removal source names code the PR removes, not code it merely modifies.
  {
    name: 'removal: a production file that is only modified is not a removal ("change the code, edit the test to match")',
    scenario: {
      main: BASE,
      branch: [{ write: { [P.calc]: BASE[P.calc].replace('a + b;', 'a + b + 1;'), ...changedTest.write } }],
      changeset: { entries: [{ items: [P.test], class: 'Obsolete', sources: [{ kind: 'removal', removed: [P.calc] }], conflictSearch: 'none' }] },
      accept: ACCEPT,
    },
    expect: 'fail', rules: ['source-unresolved'], flagged: [P.test],
    check: (r) => assert.ok(r.findings.some((f) => /is not deleted in this PR\. A modified file is not a removal/.test(f.message))),
  },
  {
    name: 'removal: a production file deleted with its test passes',
    scenario: { ...removalCase({ calc: null, removed: [P.calc] }) },
    expect: 'pass', flagged: [P.test],
  },
  {
    name: 'removal: a removed member (present at the base, gone at the head) passes',
    scenario: removalCase({ calc: BASE[P.calc].replace('  static double ratio(int a, int b) { return (double) a / b; }\n', ''), removed: [`${P.calc}#ratio`] }),
    expect: 'pass', flagged: [P.test],
  },
  {
    name: 'removal: a member that still occurs at the head fails',
    scenario: removalCase({ calc: BASE[P.calc].replace('a + b;', 'a + b + 1;'), removed: [`${P.calc}#add`] }),
    expect: 'fail', rules: ['source-unresolved'],
    check: (r) => assert.ok(r.findings.some((f) => /'add' still occurs in/.test(f.message))),
  },
  {
    name: 'removal: a member that never occurred at the base fails',
    scenario: removalCase({ calc: 'package io.x;\nclass Calc {}\n', removed: [`${P.calc}#subtract`] }),
    expect: 'fail', rules: ['source-unresolved'],
  },
  {
    name: 'removal: a listed test-only helper is test code, not a removed production source',
    scenario: removalCase({ calc: BASE[P.calc], removed: [P.uiHelper], extra: { delete: [P.uiHelper] } }),
    expect: 'fail', rules: ['entry-invalid'],
  },

  // Hole 2: a byte-identical move passes only within one execution context.
  {
    name: 'move: src/test -> src/testFixtures (another source set) is flagged on both sides',
    scenario: { main: BASE, branch: [{ move: [[P.test, 'modules/core/src/testFixtures/java/io/x/CalcTest.java']] }] },
    expect: 'fail', rules: ['uncovered'], flagged: ['modules/core/src/testFixtures/java/io/x/CalcTest.java', P.test],
    check: (r) => {
      assert.equal(r.moves.length, 0);
      assert.ok(r.findings.some((f) => f.ruleId === 'test-intent/move-changes-execution' && /jvm:core:test -> jvm:core:testFixtures/.test(f.message)));
    },
  },
  {
    name: 'move: a vitest file moved into e2e/ (outside the default selection) is flagged',
    scenario: { main: BASE, branch: [{ move: [[P.uiTest, 'modules/ui-web/e2e/list.test.ts']] }] },
    expect: 'fail', rules: ['uncovered'], flagged: ['modules/ui-web/e2e/list.test.ts', P.uiTest],
  },
  {
    name: 'move: a rename to *-lockdown.test.* (another vitest selection) is flagged',
    scenario: { main: BASE, branch: [{ move: [[P.uiTest, 'modules/ui-web/src/views/list2-lockdown.test.ts']] }] },
    expect: 'fail', rules: ['uncovered'], flagged: ['modules/ui-web/src/views/list2-lockdown.test.ts', P.uiTest],
  },
  {
    name: 'move: a JVM test moved into another module is flagged',
    scenario: { main: BASE, branch: [{ move: [[P.test, 'modules/app/src/test/java/io/x/CalcTest.java']] }] },
    expect: 'fail', rules: ['uncovered'], flagged: ['modules/app/src/test/java/io/x/CalcTest.java', P.test],
  },
  {
    name: 'move: a vitest file moved within the default selection passes',
    scenario: { main: BASE, branch: [{ move: [[P.uiTest, 'modules/ui-web/src/views/sub/list.test.ts']] }] },
    expect: 'pass', flagged: [],
    check: (r) => assert.deepEqual(r.moves, [{ from: P.uiTest, to: 'modules/ui-web/src/views/sub/list.test.ts' }]),
  },

  // Hole 3: weakening through build configuration.
  {
    name: 'build: the windows tag excluded unconditionally in the JVM convention plugin is flagged',
    scenario: { main: BASE, branch: [{ write: { [P.jvmPlugin]: JVM_PLUGIN.replace('        if (!isWindowsHost) {\n          excluded.add("windows")\n        }\n', '        excluded.add("windows")\n') } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.jvmPlugin],
  },
  {
    name: 'build: a Gradle excludeTestsMatching filter is flagged',
    scenario: { main: BASE, branch: [{ write: { [P.coreBuild]: CORE_BUILD.replace('  maxHeapSize = "256m"\n', '  maxHeapSize = "256m"\n  filter { excludeTestsMatching("io.x.Calc*") }\n') } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.coreBuild],
  },
  {
    name: 'build: a vitest exclude glob is flagged',
    scenario: { main: BASE, branch: [{ write: { [P.viteConfig]: VITE_CONFIG.replace("'e2e/**', ", "'e2e/**', 'src/views/**', ") } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.viteConfig],
  },
  {
    name: 'build: a package.json test script change is flagged',
    scenario: { main: BASE, branch: [{ write: { [P.uiPackage]: UI_PACKAGE.replace('"vitest run"', '"vitest run src/api"') } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.uiPackage],
  },
  {
    name: 'build: an unrelated dependency bump in a build script passes',
    scenario: { main: BASE, branch: [{ write: { [P.coreBuild]: CORE_BUILD.replace('33.0.0-jre', '33.1.0-jre').replace('5.10.0', '5.11.0') } }] },
    expect: 'pass', flagged: [],
  },
  {
    name: 'build: a vite build option and a package.json dependency bump pass',
    scenario: {
      main: BASE,
      branch: [{ write: { [P.viteConfig]: VITE_CONFIG.replace("outDir: 'dist'", "outDir: 'out'"), [P.uiPackage]: UI_PACKAGE.replace('^7.0.0', '^7.1.0') } }],
    },
    expect: 'pass', flagged: [],
  },

  // Hole 3, continued: selection surfaces outside the build scripts.
  {
    name: 'build: a junit-platform.properties added under src/main that makes Gradle dry-run every test is flagged',
    scenario: { main: { ...BASE, ...WITH_CI }, branch: [{ write: { [P.junitMain]: 'junit.platform.execution.dryRun.enabled=true\n' } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.junitMain],
  },
  {
    name: 'build: any edit to an existing junit-platform.properties is flagged',
    scenario: {
      main: { ...BASE, ...WITH_CI, [P.junitMain]: 'junit.jupiter.execution.parallel.enabled=false\n' },
      branch: [{ write: { [P.junitMain]: 'junit.jupiter.execution.parallel.enabled=true\n' } }],
    },
    expect: 'fail', rules: ['uncovered'], flagged: [P.junitMain],
  },
  {
    name: 'build: a JUnit Platform post-discovery filter registered under src/main META-INF/services is flagged',
    scenario: {
      main: { ...BASE, ...WITH_CI },
      branch: [{ write: { 'modules/app-util/src/main/resources/META-INF/services/org.junit.platform.launcher.PostDiscoveryFilter': 'io.x.SkipEverythingFilter\n' } }],
    },
    expect: 'fail', rules: ['uncovered'], flagged: ['modules/app-util/src/main/resources/META-INF/services/org.junit.platform.launcher.PostDiscoveryFilter'],
  },
  {
    name: 'build: a workflow -x exclusion on the Gradle test command is flagged',
    scenario: {
      main: { ...BASE, ...WITH_CI },
      branch: [{ write: { [P.workflow]: CI_WORKFLOW.replace('-PskipWebBuild=true --console', '-x :modules:core:test -PskipWebBuild=true --console') } }],
    },
    expect: 'fail', rules: ['uncovered'], flagged: [P.workflow],
  },
  {
    name: 'build: a task dropped from a workflow lane task list is flagged',
    scenario: { main: { ...BASE, ...WITH_CI }, branch: [{ write: { [P.workflow]: CI_WORKFLOW.replace('              :modules:app-util:test\n', '') } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.workflow],
  },
  {
    name: 'build: a narrowed workflow vitest command is flagged',
    scenario: { main: { ...BASE, ...WITH_CI }, branch: [{ write: { [P.workflow]: CI_WORKFLOW.replace('npx vitest run', 'npx vitest run src/api') } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.workflow],
  },
  {
    name: 'build: a workflow condition that skips the unit-test step is flagged',
    scenario: {
      main: { ...BASE, ...WITH_CI },
      branch: [{ write: { [P.workflow]: CI_WORKFLOW.replace('      - name: Unit tests\n', "      - name: Unit tests\n        if: github.event_name == 'push'\n") } }],
    },
    expect: 'fail', rules: ['uncovered'], flagged: [P.workflow],
  },
  {
    name: 'build: an unrelated workflow edit (cache key, step names) passes',
    scenario: {
      main: { ...BASE, ...WITH_CI },
      branch: [{
        write: {
          [P.workflow]: CI_WORKFLOW.replace('key: gradle-', 'key: gradle-v2-').replace('- name: Unit tests\n', '- name: Run the unit tests\n')
            .replace('- name: Cache Gradle', '- name: Cache the Gradle caches'),
        },
      }],
    },
    expect: 'pass', flagged: [],
  },
  {
    name: 'build: z01 workflow-level env JAVA_TOOL_OPTIONS dry-running JUnit in a workflow that runs tests is flagged',
    scenario: {
      main: { ...BASE, ...WITH_CI },
      branch: [{ write: { [P.workflow]: CI_WORKFLOW.replace("  JAVA_VERSION: '25'\n", "  JAVA_VERSION: '25'\n  JAVA_TOOL_OPTIONS: -Djunit.platform.execution.dryRun.enabled=true\n") } }],
    },
    expect: 'fail', rules: ['uncovered'], flagged: [P.workflow],
  },
  {
    name: 'build: z02 workflow-level env ORG_GRADLE_PROJECT_windowsOnly in a workflow that runs tests is flagged',
    scenario: {
      main: { ...BASE, ...WITH_CI },
      branch: [{ write: { [P.workflow]: CI_WORKFLOW.replace("  JAVA_VERSION: '25'\n", "  JAVA_VERSION: '25'\n  ORG_GRADLE_PROJECT_windowsOnly: \"true\"\n") } }],
    },
    expect: 'fail', rules: ['uncovered'], flagged: [P.workflow],
  },
  {
    name: 'build: z03 a non-test step of a test job writing to $GITHUB_ENV is flagged',
    scenario: {
      main: { ...BASE, ...WITH_CI },
      branch: [{
        write: {
          [P.workflow]: CI_WORKFLOW.replace('      - name: Unit tests\n',
            '      - name: Tune the JVM\n        run: echo "JAVA_TOOL_OPTIONS=-Xss4m -Dfoo=bar" >> "$GITHUB_ENV"\n      - name: Unit tests\n'),
        },
      }],
    },
    expect: 'fail', rules: ['uncovered'], flagged: [P.workflow],
  },
  {
    name: 'build: z08 windowsOnly=true added to the root gradle.properties is flagged',
    scenario: { main: { ...BASE, ...WITH_CI }, branch: [{ write: { [P.gradleProperties]: `${GRADLE_PROPERTIES}windowsOnly=true\n` } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.gradleProperties],
  },
  {
    name: 'build: a workflow-level env change in a workflow that runs no tests passes',
    scenario: { main: { ...BASE, ...WITH_CI }, branch: [{ write: { [P.docsWorkflow]: DOCS_WORKFLOW.replace('2048', '4096') } }] },
    expect: 'pass', flagged: [],
  },
  {
    name: 'build: a version-only gradle.properties bump passes',
    scenario: { main: { ...BASE, ...WITH_CI }, branch: [{ write: { [P.gradleProperties]: GRADLE_PROPERTIES.replace('2.1.0', '2.1.20').replace('0.3.0', '0.4.0') } }] },
    expect: 'pass', flagged: [],
  },
  {
    name: 'build: an edit to the unit-test shard policy is flagged as a watched baseline',
    scenario: { main: { ...BASE, ...WITH_CI }, branch: [{ write: { [P.shardPolicy]: SHARD_POLICY.replace(',\n        ":modules:app-util:test"', '') } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.shardPolicy],
    check: (r) => assert.equal(r.flagged[0].kind, 'watched-baseline'),
  },
  {
    name: 'build: a module removed from settings.gradle.kts include is flagged',
    scenario: { main: { ...BASE, ...WITH_CI }, branch: [{ write: { [P.settings]: SETTINGS.replace(',\n  ":modules:app-util"', '') } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.settings],
  },
  {
    name: 'build: a module added to settings.gradle.kts include passes',
    scenario: { main: { ...BASE, ...WITH_CI }, branch: [{ write: { [P.settings]: SETTINGS.replace('  ":modules:app-util"\n', '  ":modules:app-util",\n  ":modules:extra"\n') } }] },
    expect: 'pass', flagged: [],
  },

  // Hole 4: test-only helpers outside the scoped paths.
  {
    name: 'test support: an edit to a listed frontend helper only tests import is flagged',
    scenario: { main: BASE, branch: [{ write: { [P.uiHelper]: 'export const testHost = () => ({ ok: false });\n' } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.uiHelper],
  },
  {
    name: 'test support: an edit to the list is flagged as a watched baseline',
    scenario: { main: BASE, branch: [{ write: { [P.supportList]: renderTestSupportList([], 'corpus') } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.supportList],
  },
  {
    name: 'test support: dropping a helper from the list in the PR that edits it still flags the helper',
    scenario: {
      main: BASE,
      branch: [{ write: { [P.supportList]: renderTestSupportList([], 'corpus'), [P.uiHelper]: 'export const testHost = () => ({ ok: false });\n' } }],
    },
    expect: 'fail', rules: ['uncovered'], flagged: [P.uiHelper, P.supportList].sort(),
  },
  {
    name: 'test support: an edit to a system-tests oracle under src/main is flagged',
    scenario: { main: BASE, branch: [{ write: { [P.oracle]: BASE[P.oracle].replace('0.8', '0.6') } }] },
    expect: 'fail', rules: ['uncovered'], flagged: [P.oracle],
  },
];

/** Assert one case's outcome; throws with a readable message. */
export function assertCase(c, result, root) {
  const errors = result.findings.filter((f) => f.level === 'error').map((f) => `${f.ruleId}: ${f.message}`);
  assert.equal(result.verdict, c.expect, `verdict ${result.verdict}, expected ${c.expect}\n  ${errors.join('\n  ')}`);
  for (const rule of c.rules ?? []) {
    assert.ok(hasError(result, rule), `expected rule test-intent/${rule}; got:\n  ${errors.join('\n  ')}`);
  }
  if (c.flagged) assert.deepEqual(result.flagged.map((f) => f.id).sort(), [...c.flagged].sort(), 'flagged items');
  for (const id of c.notFlagged ?? []) assert.ok(!result.flagged.some((f) => f.id === id), `${id} must not be flagged`);
  if (c.check) c.check(result, root);
}
