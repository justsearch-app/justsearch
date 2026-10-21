#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildReport, renderMarkdown } from './report-unit-test-attribution.mjs';

const scriptPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'report-unit-test-attribution.mjs');

function write(filePath, text) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, text, 'utf8');
}

function withTempRoot(fn) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-unit-attribution-'));
  try {
    return fn(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

function suiteXml(attrs) {
  const pairs = Object.entries(attrs)
    .map(([key, value]) => `${key}="${String(value)}"`)
    .join(' ');
  return `<?xml version="1.0" encoding="UTF-8"?><testsuite ${pairs}><testcase classname="${attrs.name}" name="case"/></testsuite>\n`;
}

withTempRoot((root) => {
  write(
    path.join(root, 'modules/alpha/build/test-results/test/TEST-example.AlphaTest.xml'),
    suiteXml({ name: 'example.AlphaTest', tests: 4, skipped: 1, failures: 0, errors: 0, time: 2.5 }),
  );
  write(
    path.join(root, 'modules/beta/build/test-results/test/TEST-example.BetaTest.xml'),
    suiteXml({ name: 'example.BetaTest', tests: 2, skipped: 0, failures: 1, errors: 0, time: 5.25 }),
  );
  write(
    path.join(root, 'modules/alpha/build/test-results/test/TEST-example.FastTest.xml'),
    suiteXml({ name: 'example.FastTest', tests: 1, skipped: 0, failures: 0, errors: 0, time: 0.125 }),
  );

  const report = buildReport({
    root,
    lane: 'alpha-lane',
    top: 2,
    runner: {
      runnerLabel: 'windows-latest',
      runnerOs: 'Windows',
      runnerArch: 'X64',
      runnerName: 'GitHub Actions 1',
      imageOs: 'windows-2025-vs2026',
      imageVersion: '20260622.153.1',
    },
  });

  assert.equal(report.kind, 'justsearch-unit-test-attribution.v1');
  assert.equal(report.lane, 'alpha-lane');
  assert.deepEqual(report.totals, {
    suites: 3,
    tests: 7,
    skipped: 1,
    failures: 1,
    errors: 0,
    timeSeconds: 7.875,
  });
  assert.equal(report.modules[0].module, 'modules/beta');
  assert.equal(report.modules[1].module, 'modules/alpha');
  assert.equal(report.modules[1].tests, 5);
  assert.equal(report.slowestSuites[0].name, 'example.BetaTest');
  assert.equal(report.slowestSuites.length, 2);

  const md = renderMarkdown(report);
  assert.match(md, /windows-latest/);
  assert.match(md, /Lane: alpha-lane/);
  assert.match(md, /example\.BetaTest/);
});

withTempRoot((root) => {
  const outJson = path.join(root, 'out/report.json');
  const outMd = path.join(root, 'out/report.md');
  const summary = path.join(root, 'summary.md');
  write(
    path.join(root, 'modules/gamma/build/test-results/test/TEST-example.GammaTest.xml'),
    suiteXml({ name: 'example.GammaTest', tests: 3, skipped: 2, failures: 0, errors: 1, time: 1.75 }),
  );
  const res = spawnSync(process.execPath, [
    scriptPath,
    '--results-root', root,
    '--lane', 'gamma-lane',
    '--runner-label', 'windows-latest',
    '--out-json', outJson,
    '--out-md', outMd,
    '--json',
  ], {
    encoding: 'utf8',
    env: { ...process.env, GITHUB_STEP_SUMMARY: summary, RUNNER_OS: 'Windows', ImageVersion: 'v-test' },
  });
  assert.equal(res.status, 0, res.stderr);
  assert.equal(JSON.parse(fs.readFileSync(outJson, 'utf8')).totals.errors, 1);
  assert.match(fs.readFileSync(outMd, 'utf8'), /GammaTest/);
  assert.match(fs.readFileSync(summary, 'utf8'), /Test attribution \(gamma-lane\)/);
  assert.equal(JSON.parse(res.stdout).lane, 'gamma-lane');
  assert.equal(JSON.parse(res.stdout).runner.imageVersion, 'v-test');
});

withTempRoot((root) => {
  const fail = spawnSync(process.execPath, [scriptPath, '--results-root', root], { encoding: 'utf8' });
  assert.notEqual(fail.status, 0);
  assert.match(fail.stderr, /no JUnit XML files found/);

  const pass = spawnSync(process.execPath, [scriptPath, '--results-root', root, '--allow-empty', '--json'], { encoding: 'utf8' });
  assert.equal(pass.status, 0, pass.stderr);
  assert.equal(JSON.parse(pass.stdout).totals.suites, 0);
});

withTempRoot((root) => {
  write(
    path.join(root, 'modules/real/build/test-results/test/TEST-example.RealTest.xml'),
    suiteXml({ name: 'example.RealTest', tests: 1, skipped: 0, failures: 0, errors: 0, time: 1 }),
  );
  write(
    path.join(root, 'tmp/gha-unit-copy/modules/copied/build/test-results/test/TEST-example.CopiedTest.xml'),
    suiteXml({ name: 'example.CopiedTest', tests: 99, skipped: 0, failures: 0, errors: 0, time: 99 }),
  );
  const report = buildReport({ root, top: 5 });
  assert.equal(report.totals.tests, 1);
  assert.equal(report.modules[0].module, 'modules/real');
});

// Tempdoc 829 R5 — flaky-test extraction. Fixture modeled on the real
// IngestionDiagnosticsContractTest shape from run 31742266298: a Develocity retry
// re-executes a class whose @BeforeAll stalled, so the synthetic "initializationError"
// testcase appears twice in the same <testsuite> — once with a nested <failure>, once
// clean/self-closing. The suite's own tests= attribute (3) counts both attempts plus the
// one real passing test.
withTempRoot((root) => {
  const longMessage = `java.util.concurrent.TimeoutException: ${'boot stalled past the fixture budget. '.repeat(6)}`;
  const suiteXmlRaw = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<testsuite name="io.justsearch.systemtests.api.IngestionDiagnosticsContractTest"',
    ' tests="3" skipped="0" failures="0" errors="0" time="37.3">',
    '<testcase classname="io.justsearch.systemtests.api.IngestionDiagnosticsContractTest"',
    ` name="initializationError" time="30.1"><failure message="${longMessage}"`,
    ' type="java.util.concurrent.TimeoutException">stack trace omitted</failure></testcase>',
    '<testcase classname="io.justsearch.systemtests.api.IngestionDiagnosticsContractTest"',
    ' name="initializationError" time="6.1"/>',
    '<testcase classname="io.justsearch.systemtests.api.IngestionDiagnosticsContractTest"',
    ' name="testDiagnosticsEndpointReturnsShape" time="1.1"/>',
    '</testsuite>',
    '',
  ].join('\n');
  write(
    path.join(root, 'modules/system-tests/build/test-results/test/TEST-io.justsearch.systemtests.api.IngestionDiagnosticsContractTest.xml'),
    suiteXmlRaw,
  );

  const report = buildReport({ root, top: 20 });
  assert.equal(report.totals.tests, 3, 'both retry attempts still count toward the (inflated) totals');
  assert.equal(report.flakyTests.length, 1);
  const [flaky] = report.flakyTests;
  assert.equal(flaky.classname, 'io.justsearch.systemtests.api.IngestionDiagnosticsContractTest');
  assert.equal(flaky.name, 'initializationError');
  assert.equal(flaky.module, 'modules/system-tests');
  assert.equal(flaky.attempts, 2);
  assert.ok(flaky.firstFailureMessage.length <= 200, 'truncated to ~200 chars');
  assert.ok(flaky.firstFailureMessage.startsWith('java.util.concurrent.TimeoutException'));
  assert.ok(longMessage.length > 200, 'fixture message must actually exceed the truncation length to prove truncation fired');

  const md = renderMarkdown(report);
  assert.match(md, /Flaky tests \(self-recovered on Develocity retry\): 1\./);
  assert.match(md, /Caution: retried executions inflate the timing\/test-count totals/);
  assert.match(md, /initializationError/);
});

// Negative case — a test failing on every attempt (no clean retry) is a genuine failure,
// not a flake, and must NOT appear in flakyTests even though it also produces repeated
// <testcase> entries with the same classname+name.
withTempRoot((root) => {
  const suiteXmlRaw = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<testsuite name="io.justsearch.systemtests.api.GenuinelyBrokenTest"',
    ' tests="2" skipped="0" failures="2" errors="0" time="4.0">',
    '<testcase classname="io.justsearch.systemtests.api.GenuinelyBrokenTest"',
    ' name="testAlwaysFails" time="2.0"><failure message="expected true but was false"',
    ' type="org.opentest4j.AssertionFailedError">stack1</failure></testcase>',
    '<testcase classname="io.justsearch.systemtests.api.GenuinelyBrokenTest"',
    ' name="testAlwaysFails" time="2.0"><failure message="expected true but was false"',
    ' type="org.opentest4j.AssertionFailedError">stack2</failure></testcase>',
    '</testsuite>',
    '',
  ].join('\n');
  write(
    path.join(root, 'modules/system-tests/build/test-results/test/TEST-io.justsearch.systemtests.api.GenuinelyBrokenTest.xml'),
    suiteXmlRaw,
  );

  const report = buildReport({ root, top: 20 });
  assert.equal(report.totals.failures, 2);
  assert.deepEqual(report.flakyTests, [], 'two failed attempts with no clean pass is not a flake');

  const md = renderMarkdown(report);
  assert.match(md, /Flaky tests \(self-recovered on Develocity retry\): 0\./);
  assert.doesNotMatch(md, /Caution: retried executions inflate/);
});

// PR #447 review fix — the integration-tests lane (`:modules:system-tests:integrationTest`)
// writes JUnit XML under build/test-results/integrationTest/, not build/test-results/test/.
// Before the isJUnitResultPath / modulePathFor generalization, this layout was invisible to
// walk() (never discovered) and, if discovered by some other path, would report module
// `<unknown>` instead of `modules/system-tests`. Assert both: the file is picked up at all,
// and its module resolves correctly.
withTempRoot((root) => {
  write(
    path.join(root, 'modules/system-tests/build/test-results/integrationTest/TEST-example.IntegrationOnlyTest.xml'),
    suiteXml({ name: 'example.IntegrationOnlyTest', tests: 1, skipped: 0, failures: 0, errors: 0, time: 3.2 }),
  );

  const report = buildReport({ root, lane: 'integration-tests', top: 20 });
  assert.equal(report.totals.suites, 1, 'integrationTest-layout XML must be discovered by walk()');
  assert.equal(report.totals.tests, 1);
  assert.equal(report.modules.length, 1);
  assert.equal(report.modules[0].module, 'modules/system-tests', 'module must resolve, not fall back to <unknown>');
});

// The blocking Windows check uses every parsed suite, independent of the slow-suite limit.
withTempRoot((root) => {
  const policy = JSON.parse(fs.readFileSync(new URL('./unit-test-shard-policy.v1.json', import.meta.url), 'utf8'));
  const requiredClasses = policy.windowsParts.flatMap(part => part.requiredClasses ?? []);
  const resultPath = (name) => path.join(root, `modules/example/build/test-results/test/TEST-${name}.xml`);
  const testcase = (name, body = '') => `<testcase classname="${requiredClasses.at(-1).name}" name="${name}">${body}</testcase>`;
  for (const entry of requiredClasses) {
    write(resultPath(entry.name), suiteXml({ name: entry.name, tests: 1, skipped: 0 }));
  }
  const report = buildReport({ root, top: 1, requiredClasses });
  assert.equal(report.slowestSuites.length, 1);
  assert.equal(report.requiredClassCoverage.length, 15);
  assert.ok(report.requiredClassCoverage.every((entry) => entry.executed === 1 && !entry.issues.length));
  const run = () => spawnSync(process.execPath, [scriptPath, '--results-root', root,
    '--require-windows-classes', '--json'], { encoding: 'utf8' });
  assert.equal(run().status, 0);
  for (const name of [
    'io.justsearch.app.services.ai.install.AiInstallServiceCudaPolicyTest',
    'io.justsearch.ui.HeadlessAppCudaPolicyTest',
  ]) {
    assert.ok(requiredClasses.some((entry) => entry.name === name), `${name} must be required`);
    fs.rmSync(resultPath(name));
    const missingCuda = run();
    assert.equal(missingCuda.status, 1);
    assert.ok(missingCuda.stderr.includes(`${name}: no tests executed`));
    write(resultPath(name), suiteXml({ name, tests: 1, skipped: 0 }));
    assert.equal(run().status, 0);
  }
  for (const part of policy.windowsParts.filter(part => part.requiredClasses?.length)) {
    const laneRun = () => spawnSync(process.execPath, [scriptPath, '--results-root', root,
      '--lane', part.jobId, '--require-windows-classes', '--json'], { encoding: 'utf8' });
    const entry = part.requiredClasses[0];
    fs.rmSync(resultPath(entry.name));
    assert.match(laneRun().stderr, new RegExp(`${entry.name}: no tests executed`));
    for (const other of policy.windowsParts.filter(other => other.requiredClasses?.length && other !== part)) {
      const result = spawnSync(process.execPath, [scriptPath, '--results-root', root,
        '--lane', other.jobId, '--require-windows-classes', '--json'], { encoding: 'utf8' });
      assert.equal(result.status, 0, result.stderr);
      assert.equal(JSON.parse(result.stdout).requiredClassCoverage.length, other.requiredClasses.length);
    }
    write(resultPath(entry.name), suiteXml({ name: entry.name, tests: 1, skipped: 0 }));
    assert.equal(laneRun().status, 0);
  }
  const atomic = requiredClasses[0];
  const atomicCases = Array.from({ length: 12 }, (_, i) =>
    `<testcase classname="${atomic.name}" name="case${i}">`
    + (i ? '<skipped message="unlisted skip"/>' : '') + '</testcase>').join('');
  write(resultPath(atomic.name), `<testsuite name="${atomic.name}" tests="12" skipped="11">`
    + atomicCases + '</testsuite>');
  const unlisted = run();
  assert.equal(unlisted.status, 1, 'one executed case must not excuse eleven unlisted skips');
  const atomicCoverage = JSON.parse(unlisted.stdout).requiredClassCoverage.find((entry) => entry.name === atomic.name);
  assert.equal(atomicCoverage.executed, 1);
  assert.equal(atomicCoverage.issues.length, 11);
  assert.match(unlisted.stderr, /AtomicFileWritesTest: unexpected skip: case1: unlisted skip/);
  write(resultPath(atomic.name), suiteXml({ name: atomic.name, tests: 1, skipped: 0 }));

  // The newest hosted Windows XML has an empty <skipped/> for this Linux-only case.
  const contended = requiredClasses.find((entry) => entry.name.endsWith('.ContendedFileReadsTest'));
  const rotation = contended.allowedSkips[0];
  write(resultPath(contended.name), `<testsuite name="${contended.name}" tests="2" skipped="1">`
    + `<testcase classname="${contended.name}" name="portableCase()"/>`
    + `<testcase classname="${contended.name}" name="${rotation.name}"><skipped/></testcase></testsuite>`);
  assert.equal(run().status, 0, 'preserve the named @EnabledOnOs(OS.LINUX) skip');
  const prune = requiredClasses.at(-1);
  const allowed = prune.allowedSkips[0];
  const skip = (name, message) => testcase(name, `<skipped message="${message}"/>`);
  write(resultPath(prune.name), `<testsuite name="${prune.name}" tests="2" skipped="1">`
    + testcase('theSameVolumeAfterARestartStillPrunesAGenuineDelete()')
    + skip(allowed.name, 'Assumption failed: needs a second volume besides C:/temp') + '</testsuite>');
  const allowedReport = buildReport({ root, requiredClasses });
  assert.equal(run().status, 0);
  assert.match(renderMarkdown(allowedReport), /Skipped .*aDifferentVolumeAtTheSameLetterAfterARestartPrunesNothing/);
  assert.match(renderMarkdown(allowedReport), /needs a second volume/);
  for (const [name, message] of [
    ['theSameVolumeAfterARestartStillPrunesAGenuineDelete()', 'needs a second volume besides C:/temp'],
    [allowed.name, 'SUBST drive letters and volume identity exist on Windows only'],
  ]) {
    write(resultPath(prune.name), `<testsuite name="${prune.name}">`
      + testcase('anotherCase()') + skip(name, message) + '</testsuite>');
    const result = run();
    assert.equal(result.status, 1);
    assert.match(result.stderr, /unexpected skip/);
  }
  // Even permitted prerequisite skips cannot make an entirely skipped class pass.
  write(resultPath(prune.name), `<testsuite name="${prune.name}">`
    + skip(allowed.name, 'needs a second volume besides C:/temp') + '</testsuite>');
  assert.match(run().stderr, /no tests executed/);
  fs.rmSync(resultPath(requiredClasses[0].name));
  const missing = run();
  assert.equal(missing.status, 1);
  assert.match(missing.stderr, /AtomicFileWritesTest: no tests executed/);
});

console.log('test-report-unit-test-attribution: PASS');
