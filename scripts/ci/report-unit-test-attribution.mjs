#!/usr/bin/env node
/**
 * Summarize Gradle/JUnit unit-test XML for the public CI Unit tests lane.
 *
 * Reports timing, skips, failures, and runner identity. The opt-in Windows
 * coverage check additionally fails if policy classes did not execute.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

// KIND is deliberately NOT renamed to be lane-generic even though this script now also
// covers the integration-tests lane: it's a consumer-facing schema identifier (JSON
// structure/field-shape contract), not a lane label, and renaming it would break any
// existing consumer keyed on this string for no structural reason. Lane identity lives in
// the `lane` field and (for humans) the Markdown header below.
const KIND = 'justsearch-unit-test-attribution.v1';

function repoRootFromCwd() {
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, 'settings.gradle.kts'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
  }
}

function usage() {
  return [
    'Usage: node scripts/ci/report-unit-test-attribution.mjs [options]',
    '',
    'Options:',
    '  --results-root <path>     Root to scan (default: repo root)',
    '  --lane <lane>             Unit-test shard lane name',
    '  --runner-label <label>    Workflow runner label, e.g. windows-latest',
    '  --top <n>                 Number of slow suites to include (default: 20)',
    '  --out-json <path>         Write JSON report',
    '  --out-md <path>           Write Markdown report',
    '  --json                    Print JSON to stdout',
    '  --md                      Print Markdown to stdout',
    '  --allow-empty             Exit 0 when no JUnit XML files are found',
    '  --require-windows-classes Check execution of the Windows policy classes',
    '  -h, --help',
  ].join('\n');
}

function parseArgs(argv) {
  const out = {
    resultsRoot: null,
    lane: null,
    runnerLabel: null,
    top: 20,
    outJson: null,
    outMd: null,
    json: false,
    md: false,
    allowEmpty: false,
    requireWindowsClasses: false,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--results-root' && argv[i + 1]) out.resultsRoot = argv[++i];
    else if (arg === '--lane' && argv[i + 1]) out.lane = argv[++i];
    else if (arg === '--runner-label' && argv[i + 1]) out.runnerLabel = argv[++i];
    else if (arg === '--top' && argv[i + 1]) out.top = Number.parseInt(argv[++i], 10);
    else if (arg === '--out-json' && argv[i + 1]) out.outJson = argv[++i];
    else if (arg === '--out-md' && argv[i + 1]) out.outMd = argv[++i];
    else if (arg === '--json') out.json = true;
    else if (arg === '--md') out.md = true;
    else if (arg === '--allow-empty') out.allowEmpty = true;
    else if (arg === '--require-windows-classes') out.requireWindowsClasses = true;
    else if (arg === '--help' || arg === '-h') out.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  if (!Number.isInteger(out.top) || out.top <= 0) {
    throw new Error('--top must be a positive integer');
  }
  return out;
}

function normalizeRel(root, absPath) {
  return path.relative(root, absPath).split(path.sep).join('/');
}

function decodeXmlAttr(value) {
  return String(value)
    .replaceAll('&quot;', '"')
    .replaceAll('&apos;', "'")
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&amp;', '&');
}

function attrsFrom(tagText) {
  const attrs = {};
  const re = /([A-Za-z_:][\w:.-]*)\s*=\s*"([^"]*)"/g;
  for (const match of tagText.matchAll(re)) attrs[match[1]] = decodeXmlAttr(match[2]);
  return attrs;
}

function asNumber(value, fallback = 0) {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

function asInt(value) {
  return Math.trunc(asNumber(value, 0));
}

// Gradle names the results directory after the Test TASK, not a fixed "test" literal —
// build/test-results/test/ for the default `test` task, build/test-results/integrationTest/
// for `:modules:system-tests:integrationTest`, etc. Any task-name segment must be accepted
// here, or a whole tier's XML is silently invisible to attribution (tempdoc 829 follow-up).
const TEST_RESULTS_TASK_DIR = '[^/]+';

function isJUnitResultPath(root, absPath) {
  const rel = normalizeRel(root, absPath);
  const rootBase = path.basename(path.resolve(root));
  if (rel.startsWith('modules/'))
    return new RegExp(`^modules/[^/]+/build/test-results/${TEST_RESULTS_TASK_DIR}/TEST-.+\\.xml$`, 'i').test(rel);
  if (rootBase === 'modules')
    return new RegExp(`^[^/]+/build/test-results/${TEST_RESULTS_TASK_DIR}/TEST-.+\\.xml$`, 'i').test(rel);
  return new RegExp(`^build/test-results/${TEST_RESULTS_TASK_DIR}/TEST-.+\\.xml$`, 'i').test(rel);
}

function walk(dir, root = dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const abs = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === '.git' || entry.name === 'node_modules') continue;
      walk(abs, root, out);
    } else if (entry.isFile() && /^TEST-.+\.xml$/i.test(entry.name)) {
      if (isJUnitResultPath(root, abs)) out.push(abs);
    }
  }
  return out;
}

function modulePathFor(root, filePath) {
  const rel = normalizeRel(root, filePath);
  const match = new RegExp(`^(modules/[^/]+)/build/test-results/${TEST_RESULTS_TASK_DIR}/`).exec(rel);
  if (match) return match[1];
  const buildIndexMatch = new RegExp(`/build/test-results/${TEST_RESULTS_TASK_DIR}/`).exec(rel);
  return buildIndexMatch ? rel.slice(0, buildIndexMatch.index) : '<unknown>';
}

// Develocity's test-retry extension re-executes a flaky test in place: the retried
// attempt is written as ANOTHER <testcase> entry with the same classname+name inside the
// SAME <testsuite> (the failed attempt carries a nested <failure>/<error>, the passing
// retry is bare or self-closing). The suite's own `tests=` attribute counts every attempt,
// so retries silently inflate totals/module/slowestSuites below unless the duplicate
// testcase entries are parsed and grouped explicitly (tempdoc 829 F5/R5).
function parseTestcases(text) {
  const testcases = [];
  const re = /<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g;
  for (const match of text.matchAll(re)) {
    const attrs = attrsFrom(match[1]);
    const body = match[2] || '';
    const failureTagMatch = /<(failure|error)\b([^>]*)>/.exec(body);
    const skipTagMatch = /<skipped\b([^>]*)/.exec(body);
    let failureMessage = null;
    if (failureTagMatch) {
      const tagAttrs = attrsFrom(failureTagMatch[2]);
      failureMessage = tagAttrs.message || null;
    }
    testcases.push({
      classname: attrs.classname || '',
      name: attrs.name || '',
      failed: failureTagMatch !== null,
      failureMessage,
      skipped: skipTagMatch !== null,
      skipMessage: skipTagMatch ? attrsFrom(skipTagMatch[1]).message || '' : null,
    });
  }
  return testcases;
}

function parseSuite(root, filePath) {
  const text = fs.readFileSync(filePath, 'utf8');
  const suiteMatch = /<testsuite\b([^>]*)>/s.exec(text);
  if (!suiteMatch) return null;
  const attrs = attrsFrom(suiteMatch[1]);
  return {
    module: modulePathFor(root, filePath),
    name: attrs.name || path.basename(filePath, '.xml').replace(/^TEST-/, ''),
    file: normalizeRel(root, filePath),
    tests: asInt(attrs.tests),
    skipped: asInt(attrs.skipped),
    failures: asInt(attrs.failures),
    errors: asInt(attrs.errors),
    timeSeconds: asNumber(attrs.time),
    testcases: parseTestcases(text),
  };
}

function truncate(text, maxLength) {
  if (!text) return null;
  return text.length > maxLength ? `${text.slice(0, maxLength - 1)}…` : text;
}

// Group testcase entries by (file, classname, name) — scoped to one suite file, since a
// retry re-executes within the same JUnit XML run. N>1 entries with at least one
// failed+one clean = flaky (self-recovered). N>1 entries that are ALL failed is a
// genuinely failing test, not a flake, and is excluded.
function detectFlakyTests(rawSuites) {
  const groups = new Map();
  for (const suite of rawSuites) {
    for (const testcase of suite.testcases) {
      const key = `${suite.file}::${testcase.classname}::${testcase.name}`;
      const entries = groups.get(key) || [];
      entries.push({ ...testcase, module: suite.module });
      groups.set(key, entries);
    }
  }
  const flakyTests = [];
  for (const entries of groups.values()) {
    if (entries.length < 2) continue;
    const hasFailure = entries.some((entry) => entry.failed);
    const hasClean = entries.some((entry) => !entry.failed);
    if (!hasFailure || !hasClean) continue;
    const firstFailure = entries.find((entry) => entry.failed);
    flakyTests.push({
      classname: entries[0].classname,
      name: entries[0].name,
      module: entries[0].module,
      attempts: entries.length,
      firstFailureMessage: truncate(firstFailure?.failureMessage, 200),
    });
  }
  flakyTests.sort((a, b) => a.classname.localeCompare(b.classname) || a.name.localeCompare(b.name));
  return flakyTests;
}

function runnerInfo(opts, env = process.env) {
  return {
    runnerLabel: opts.runnerLabel || null,
    runnerOs: env.RUNNER_OS || null,
    runnerArch: env.RUNNER_ARCH || null,
    runnerName: env.RUNNER_NAME || null,
    imageOs: env.ImageOS || null,
    imageVersion: env.ImageVersion || null,
  };
}

function round3(n) {
  return Math.round(n * 1000) / 1000;
}

export function buildReport({ root, lane = null, top = 20, runner = runnerInfo({}), requiredClasses = [] }) {
  const rawSuites = walk(root)
    .map((filePath) => parseSuite(root, filePath))
    .filter(Boolean);
  const flakyTests = detectFlakyTests(rawSuites);
  const suites = rawSuites.map(({ testcases: _testcases, ...suite }) => suite);
  const modules = new Map();
  const totals = {
    suites: suites.length,
    tests: 0,
    skipped: 0,
    failures: 0,
    errors: 0,
    timeSeconds: 0,
  };

  for (const suite of suites) {
    totals.tests += suite.tests;
    totals.skipped += suite.skipped;
    totals.failures += suite.failures;
    totals.errors += suite.errors;
    totals.timeSeconds += suite.timeSeconds;
    const current = modules.get(suite.module) || {
      module: suite.module,
      suites: 0,
      tests: 0,
      skipped: 0,
      failures: 0,
      errors: 0,
      timeSeconds: 0,
    };
    current.suites += 1;
    current.tests += suite.tests;
    current.skipped += suite.skipped;
    current.failures += suite.failures;
    current.errors += suite.errors;
    current.timeSeconds += suite.timeSeconds;
    modules.set(suite.module, current);
  }

  const rounded = (entry) => ({ ...entry, timeSeconds: round3(entry.timeSeconds) });
  return {
    kind: KIND,
    generatedAt: new Date().toISOString(),
    lane,
    runner,
    totals: rounded(totals),
    modules: [...modules.values()]
      .map(rounded)
      .sort((a, b) => b.timeSeconds - a.timeSeconds || a.module.localeCompare(b.module)),
    slowestSuites: suites
      .map(rounded)
      .sort((a, b) => b.timeSeconds - a.timeSeconds || a.name.localeCompare(b.name))
      .slice(0, top),
    flakyTests,
    ...(requiredClasses.length ? {
      requiredClassCoverage: requiredClasses.map((required) => {
        const cases = rawSuites.flatMap((suite) => suite.testcases)
          .filter((testcase) => testcase.classname === required.name);
        const executed = cases.filter((testcase) => !testcase.skipped).length;
        const skipped = cases.filter((testcase) => testcase.skipped)
          .map((testcase) => ({ name: testcase.name, message: testcase.skipMessage }));
        const issues = executed ? [] : ['no tests executed'];
        for (const skip of skipped) {
          if (!(required.allowedSkips ?? []).some((allowed) =>
            allowed.name === skip.name && skip.message.includes(allowed.messageIncludes))) {
            issues.push(`unexpected skip: ${skip.name}: ${skip.message}`);
          }
        }
        return { name: required.name, executed, skipped, issues };
      }),
    } : {}),
  };
}

function fmtSeconds(n) {
  if (n >= 60) return `${Math.floor(n / 60)}m ${Math.round(n % 60)}s`;
  return `${round3(n)}s`;
}

export function renderMarkdown(report) {
  // Lane-aware heading — this script now runs for both the unit-tests matrix and the
  // integration-tests lane, so a hardcoded "Unit test attribution" title misdescribes the
  // latter's report even though the underlying JSON `KIND` stays a stable schema id (see
  // the KIND comment above).
  const laneLabel = report.lane || 'unknown lane';
  const lines = [
    `### Test attribution (${laneLabel})`,
    '',
    `Generated: ${report.generatedAt}`,
    '',
    `Lane: ${report.lane || 'unknown'}`,
    '',
    `Runner: ${report.runner.runnerLabel || 'unknown label'} / ${report.runner.runnerOs || 'unknown OS'} / image ${report.runner.imageOs || 'unknown'} ${report.runner.imageVersion || ''}`.trim(),
    '',
    `Total: ${report.totals.tests} tests, ${report.totals.skipped} skipped, ${report.totals.failures} failures, ${report.totals.errors} errors, ${report.totals.suites} suites, ${fmtSeconds(report.totals.timeSeconds)} summed suite time.`,
    '',
    '| Module | Suites | Tests | Skipped | Failures | Errors | Suite time |',
    '|---|---:|---:|---:|---:|---:|---:|',
  ];
  for (const module of report.modules) {
    lines.push(`| ${module.module} | ${module.suites} | ${module.tests} | ${module.skipped} | ${module.failures} | ${module.errors} | ${fmtSeconds(module.timeSeconds)} |`);
  }
  lines.push('', '| Slow suite | Module | Tests | Skipped | Failures | Errors | Time |', '|---|---|---:|---:|---:|---:|---:|');
  for (const suite of report.slowestSuites) {
    lines.push(`| ${suite.name} | ${suite.module} | ${suite.tests} | ${suite.skipped} | ${suite.failures} | ${suite.errors} | ${fmtSeconds(suite.timeSeconds)} |`);
  }

  const flakyTests = report.flakyTests || [];
  lines.push('', `Flaky tests (self-recovered on Develocity retry): ${flakyTests.length}.`);
  if (flakyTests.length > 0) {
    lines.push(
      '',
      // Tempdoc 829 F5/R5: each flaky test below re-executed via the Develocity retry
      // extension, and every attempt is counted in the `Total`/module/slow-suite figures
      // above — those sums are inflated by the retried attempts, not just the flake count.
      'Caution: retried executions inflate the timing/test-count totals above — every retry ' +
        'attempt is a separate `<testcase>` entry that the totals sum over.',
      '',
      '| Test | Module | Attempts | First failure |',
      '|---|---|---:|---|',
    );
    for (const flaky of flakyTests) {
      const message = (flaky.firstFailureMessage || '').replaceAll('|', '\\|').replaceAll('\n', ' ');
      lines.push(`| ${flaky.classname}.${flaky.name} | ${flaky.module} | ${flaky.attempts} | ${message} |`);
    }
  }
  lines.push('');
  if (report.requiredClassCoverage) {
    lines.push('Windows required class execution:', '', '| Class | Executed | Skipped | Result |',
      '|---|---:|---:|---|');
    for (const entry of report.requiredClassCoverage) {
      lines.push(`| ${entry.name} | ${entry.executed} | ${entry.skipped.length} | ${entry.issues.join('; ') || 'pass'} |`);
    }
    lines.push('');
    for (const entry of report.requiredClassCoverage) {
      for (const skip of entry.skipped) lines.push(`Skipped ${entry.name}.${skip.name}: ${skip.message}`, '');
    }
  }
  return `${lines.join('\n')}\n`;
}

function writeText(filePath, text) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, text, 'utf8');
}

function main() {
  try {
    const opts = parseArgs(process.argv.slice(2));
    if (opts.help) {
      console.log(usage());
      return;
    }
    const root = path.resolve(opts.resultsRoot || repoRootFromCwd());
    let requiredClasses = [];
    if (opts.requireWindowsClasses) {
      const policy = JSON.parse(fs.readFileSync(new URL('./unit-test-shard-policy.v1.json', import.meta.url), 'utf8'));
      requiredClasses = policy.windowsParts.find((part) => part.jobId === 'windows-native-contracts')?.requiredClasses;
      if (!Array.isArray(requiredClasses) || !requiredClasses.length) throw new Error('Windows requiredClasses policy is empty');
    }
    const report = buildReport({ root, lane: opts.lane, top: opts.top, runner: runnerInfo(opts), requiredClasses });
    const json = `${JSON.stringify(report, null, 2)}\n`;
    const md = renderMarkdown(report);

    if (report.totals.suites === 0 && !opts.allowEmpty) {
      console.error(`report-unit-test-attribution: no JUnit XML files found under ${root}`);
      process.exitCode = 1;
    }
    for (const entry of report.requiredClassCoverage || []) {
      for (const issue of entry.issues) {
        console.error(`report-unit-test-attribution: ${entry.name}: ${issue}`);
        process.exitCode = 1;
      }
    }
    if (opts.outJson) writeText(path.resolve(opts.outJson), json);
    if (opts.outMd) writeText(path.resolve(opts.outMd), md);
    if (process.env.GITHUB_STEP_SUMMARY) {
      try {
        fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, md, 'utf8');
      } catch {
        // Best effort only.
      }
    }
    if (opts.json) process.stdout.write(json);
    else if (opts.md || (!opts.outJson && !opts.outMd)) process.stdout.write(md);
  } catch (error) {
    console.error(`report-unit-test-attribution: ${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
