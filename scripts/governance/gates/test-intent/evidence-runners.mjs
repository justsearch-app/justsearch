/**
 * Runners and result parsers for the execution-evidence script (tempdoc 966 D4).
 *
 * A check id is `<repo path>[#<name>]`:
 *   JVM     modules/<m>/src/<set>/java/<pkg>/<Class>.java[#method]   Gradle, JUnit XML (TEST-*.xml)
 *   vitest  modules/ui-web/src/**\/*.test.ts[#full test name]        vitest JSON reporter
 *   Rust    modules/shell/src-tauri/**\/*.rs#<libtest name>          `cargo test -- --exact`, libtest
 *                                                                     text output (stable Rust has no
 *                                                                     JSON test output; the text lines
 *                                                                     `test <name> ... ok|FAILED|ignored`
 *                                                                     are stable and are what is parsed)
 *
 * Parsers are pure (text in, per-check records out) so the self-tests can pin them without a build.
 */

import { checkFile, checkName } from './evidence.mjs';

export const ASSERTION_TYPES = /(AssertionFailedError|AssertionError|ComparisonFailure|MultipleFailuresError|org\.assertj\.|AssertionFailure|org\.junit\.ComparisonFailure)/;

/** Which runner a check id belongs to. */
export function runnerFor(checkId) {
  const file = checkFile(checkId);
  if (/^modules\/shell\/src-tauri\/.+\.rs$/.test(file)) return 'cargo';
  if (/^modules\/[^/]+\/src\/[^/]+\/java\/.+\.java$/.test(file)) return 'gradle';
  if (/^modules\/ui-web\/.+\.(test|spec)\.(js|jsx|ts|tsx|mjs|cjs|mts|cts)$/.test(file)) return 'vitest';
  return null;
}

/** Gradle coordinates of a JVM check. */
export function gradleTarget(checkId) {
  const file = checkFile(checkId);
  const m = /^modules\/([^/]+)\/src\/([^/]+)\/java\/(.+)\.java$/.exec(file);
  if (!m) return null;
  const [, module, set, classPath] = m;
  if (set === 'main' || set === 'testFixtures') return { error: `${file}: source set '${set}' has no test task` };
  const fqcn = classPath.replaceAll('/', '.');
  return {
    module,
    project: `:modules:${module}`,
    task: set,
    fqcn,
    method: checkName(checkId),
    resultFile: `modules/${module}/build/test-results/${set}/TEST-${fqcn}.xml`,
  };
}

const decodeXml = (s) => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');

/** Parse a Gradle JUnit XML report into testcases. */
export function parseJUnitXml(xml) {
  const cases = [];
  const re = /<testcase\b([^>]*?)(\/>|>([\s\S]*?)<\/testcase>)/g;
  let m;
  while ((m = re.exec(xml)) !== null) {
    const attrs = m[1];
    const body = m[3] ?? '';
    const name = decodeXml(/\bname="([^"]*)"/.exec(attrs)?.[1] ?? '');
    const classname = decodeXml(/\bclassname="([^"]*)"/.exec(attrs)?.[1] ?? '');
    let status = 'passed';
    let failureType = null;
    let message = null;
    if (/<skipped\b/.test(body)) status = 'skipped';
    const fail = /<(failure|error)\b([^>]*)>?/.exec(body);
    if (fail) {
      status = 'failed';
      failureType = decodeXml(/\btype="([^"]*)"/.exec(fail[2])?.[1] ?? fail[1]);
      message = decodeXml(/\bmessage="([^"]*)"/.exec(fail[2])?.[1] ?? '').slice(0, 300);
    }
    cases.push({ name, classname, status, failureType, message });
  }
  return cases;
}

/** Select the JUnit cases a check names (all cases of the class when no method is named). */
export function selectJUnitCases(cases, target) {
  return cases.filter((c) => c.classname === target.fqcn || c.classname.startsWith(`${target.fqcn}$`))
    .filter((c) => !target.method || c.name === target.method || c.name.startsWith(`${target.method}(`));
}

/** Parse a vitest JSON report into per-file assertion results. */
export function parseVitestJson(text) {
  const report = JSON.parse(text);
  const files = [];
  for (const tr of report.testResults ?? []) {
    files.push({
      file: String(tr.name ?? '').replaceAll('\\', '/'),
      status: tr.status,
      message: tr.message ?? '',
      cases: (tr.assertionResults ?? []).map((a) => ({
        name: a.fullName ?? a.title,
        title: a.title,
        status: a.status === 'passed' ? 'passed' : a.status === 'failed' ? 'failed' : 'skipped',
        message: (a.failureMessages ?? []).join('\n').slice(0, 300),
      })),
    });
  }
  return files;
}

export function selectVitestCases(files, checkId) {
  const file = checkFile(checkId);
  const name = checkName(checkId);
  const hit = files.filter((f) => f.file.endsWith(file) || f.file.endsWith(file.replace(/^modules\/ui-web\//, '')));
  return {
    fileStatus: hit[0]?.status ?? null,
    fileMessage: hit[0]?.message ?? '',
    cases: hit.flatMap((f) => f.cases).filter((c) => !name || c.name === name || c.title === name),
  };
}

/** Parse libtest text output (`cargo test`). */
export function parseLibtest(text) {
  const cases = [];
  for (const line of text.split(/\r?\n/)) {
    const m = /^test (\S+) \.\.\. (ok|FAILED|ignored)/.exec(line.trim());
    if (m) cases.push({ name: m[1], status: m[2] === 'ok' ? 'passed' : m[2] === 'FAILED' ? 'failed' : 'skipped', message: null });
  }
  for (const c of cases.filter((x) => x.status === 'failed')) {
    const start = text.indexOf(`---- ${c.name} stdout ----`);
    if (start !== -1) c.message = text.slice(start, start + 600);
  }
  return cases;
}

/** Summarise selected cases into the per-check record the evidence file carries. */
export function summarize(cases) {
  if (cases.length === 0) return { executed: false, skipped: false, outcome: 'not-found' };
  const run = cases.filter((c) => c.status !== 'skipped');
  if (run.length === 0) return { executed: false, skipped: true, outcome: 'skipped' };
  // A check whose cases are only partly executed is not "executed": a skipped case hides an expectation.
  if (run.length < cases.length) return { executed: false, skipped: true, outcome: 'partly-skipped' };
  return { executed: true, skipped: false, outcome: run.some((c) => c.status === 'failed') ? 'failed' : 'passed' };
}

/**
 * Classify a fail-before result: only an ASSERTION failure inside the named check shows that the
 * old behaviour violates the expectation; compile and environment failures show nothing.
 */
export function classifyFailBefore({ runner, cases, buildOutput = '', fileStatus = null, fileMessage = '' }) {
  if (cases.length === 0) {
    if (runner === 'vitest' && fileStatus === 'failed') return { classification: 'compile', detail: fileMessage.slice(0, 300) };
    if (/Compilation failed|compileTestJava|compileIntegrationTestJava|error\[E\d+\]|could not compile|error: cannot find symbol|SyntaxError|Transform failed|Failed to load url/i.test(buildOutput)) {
      return { classification: 'compile', detail: 'the check did not compile against the before-state' };
    }
    return { classification: 'environment', detail: 'the check produced no result on the before-state' };
  }
  const failed = cases.filter((c) => c.status === 'failed');
  if (failed.length === 0) {
    return cases.every((c) => c.status === 'skipped')
      ? { classification: 'skipped', detail: 'the check was skipped on the before-state' }
      : { classification: 'passed', detail: 'the check passes on the before-state, so it does not show a repair' };
  }
  const isAssertion = (c) => {
    if (runner === 'gradle') return ASSERTION_TYPES.test(c.failureType ?? '');
    if (runner === 'vitest') return /AssertionError|expected .+ to |Expected:|Received:|toBe|toEqual/i.test(c.message ?? '');
    return /assertion( `left (==|!=) right`)? failed|assertion failed:/i.test(c.message ?? '');
  };
  return failed.every(isAssertion)
    ? { classification: 'assertion', detail: failed.map((c) => `${c.name}: ${c.failureType ?? ''} ${c.message ?? ''}`.trim()).join(' | ').slice(0, 500) }
    : { classification: 'exception', detail: 'the check failed with an exception or panic other than an assertion' };
}
