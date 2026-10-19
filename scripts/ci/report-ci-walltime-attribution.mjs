#!/usr/bin/env node
/**
 * Summarize GitHub Actions per-job / per-step wall-clock for a public CI run.
 *
 * This is attribution only: it reports where a run's wall-clock time goes (which
 * lane is the critical path; how much of each lane is fixed runner tax vs work)
 * without changing any check result. It is the wall-clock sibling of
 * report-unit-test-attribution.mjs — that script reads JUnit XML (test-suite
 * time); this one reads the Actions jobs JSON (job wall-clock), a different and
 * complementary layer (job wall-clock is ~2x summed-suite time; the gap is JVM
 * cold start + Gradle configuration + fork serialization). Tempdoc 668.
 *
 * Two consumption modes over the same pure `buildWalltimeReport` (tempdoc 668,
 * "consumption follows demand"):
 *  - CI path: a prior step fetches `gh api .../runs/<id>/jobs --paginate` and passes
 *    it via --jobs-json <file> (network/auth stays out of the pure logic).
 *  - On-demand path: an agent/dev runs `--run-id <id>` or `--latest`; the tool
 *    fetches the run itself via gh, so investigating "why is CI slow?" runs the
 *    instrument instead of re-deriving `gh api …/jobs | jq` by hand.
 */

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const SIGNAL_POLICY = JSON.parse(fs.readFileSync(new URL('./workflow-signal-policy.v1.json', import.meta.url), 'utf8'));
const REQUIRED_CHECKS = SIGNAL_POLICY.workflows.find((w) => w.name === 'CI').requiredStatusChecks;
const PART_CHECKS = ['Unit tests (app-ui-ui)', 'Unit tests (app-ui-services)', 'Windows-native tests (node)', 'Windows-native tests (contracts)'];

const KIND = 'justsearch-ci-walltime-attribution.v1';

// Steps that are runner overhead rather than the job's actual work. Everything
// else is counted as "work". Kept deliberately small and name-based; the Actions
// API gives no machine classification, so this is a documented heuristic.
const FIXED_TAX_STEP = /^(Set up job|Checkout|Setup |Install |Post |Complete job|Upload |Report )/;

function usage() {
  return [
    'Usage: node scripts/ci/report-ci-walltime-attribution.mjs (--jobs-json <file> | --run-id <id> | --latest) [options]',
    '',
    'Input (one required):',
    '  --jobs-json <path>        Pre-fetched `gh api .../runs/<id>/jobs` JSON (the CI path)',
    '  --run-id <id>             Fetch that run\'s jobs via gh and attribute it (on-demand)',
    '  --latest                  Fetch the most recent CI run on the current branch',
    '',
    'Options:',
    '  --download-artifacts      With --run-id/--latest, also download the unit-test-attribution',
    '                            artifacts to enable the per-unit-lane test-vs-overhead split',
    '  --unit-attribution-dir <path>  Dir of already-downloaded unit-test-attribution artifacts',
    '  --repository <owner/repo> Repository slug for the report header (else gh-resolved)',
    '  --workflow <name>         Workflow name for the report header',
    '  --self-job <name>         Job name to exclude (the attribution job itself)',
    '  --out-json <path>         Write JSON report',
    '  --out-md <path>           Write Markdown report',
    '  --json                    Print JSON to stdout',
    '  --md                      Print Markdown to stdout',
    '  -h, --help',
  ].join('\n');
}

function parseArgs(argv) {
  const out = {
    jobsJson: null,
    unitAttributionDir: null,
    runId: null,
    latest: false,
    downloadArtifacts: false,
    repository: null,
    workflow: null,
    selfJob: null,
    outJson: null,
    outMd: null,
    json: false,
    md: false,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--jobs-json' && argv[i + 1]) out.jobsJson = argv[++i];
    else if (arg === '--unit-attribution-dir' && argv[i + 1]) out.unitAttributionDir = argv[++i];
    else if (arg === '--run-id' && argv[i + 1]) out.runId = argv[++i];
    else if (arg === '--latest') out.latest = true;
    else if (arg === '--download-artifacts') out.downloadArtifacts = true;
    else if (arg === '--repository' && argv[i + 1]) out.repository = argv[++i];
    else if (arg === '--workflow' && argv[i + 1]) out.workflow = argv[++i];
    else if (arg === '--self-job' && argv[i + 1]) out.selfJob = argv[++i];
    else if (arg === '--out-json' && argv[i + 1]) out.outJson = argv[++i];
    else if (arg === '--out-md' && argv[i + 1]) out.outMd = argv[++i];
    else if (arg === '--json') out.json = true;
    else if (arg === '--md') out.md = true;
    else if (arg === '--help' || arg === '-h') out.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  // Input is either a pre-fetched --jobs-json (the CI path) or an on-demand run
  // reference (--run-id / --latest) the tool fetches itself via gh (the local /
  // agent investigation path).
  if (!out.help && !out.jobsJson && !out.runId && !out.latest) {
    throw new Error('one of --jobs-json, --run-id <id>, or --latest is required');
  }
  return out;
}

function elapsedSeconds(startedAt, completedAt) {
  if (!startedAt || !completedAt) return null;
  const start = Date.parse(startedAt);
  const end = Date.parse(completedAt);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return null;
  return Math.round((end - start) / 1000);
}

function classifyStep(step) {
  const seconds = elapsedSeconds(step.started_at, step.completed_at) ?? 0;
  const fixed = FIXED_TAX_STEP.test(String(step.name || ''));
  return { name: step.name || '<step>', seconds, fixed };
}

// A unit-test lane job is named "Unit tests (<lane>)"; the capture group is the
// lane key used by the unit-test-attribution artifacts (e.g. "app-ui").
const UNIT_LANE_JOB = /^Unit tests \((.+)\)$/;

export function buildWalltimeReport({ run = {}, jobs = [], selfJob = null, suiteTimes = {}, requiredChecks = REQUIRED_CHECKS }) {
  const jobReports = [];
  for (const job of Array.isArray(jobs) ? jobs : []) {
    if (selfJob && job.name === selfJob) continue;
    const wallSeconds = elapsedSeconds(job.started_at, job.completed_at);
    // In-progress jobs (e.g. the attribution job itself) have no end time yet.
    if (wallSeconds === null) continue;
    const steps = (Array.isArray(job.steps) ? job.steps : []).map(classifyStep);
    const fixedTaxSeconds = steps.filter((s) => s.fixed).reduce((a, s) => a + s.seconds, 0);
    const workSeconds = steps.filter((s) => !s.fixed).reduce((a, s) => a + s.seconds, 0);
    const report = {
      name: job.name || '<job>',
      conclusion: job.conclusion || null,
      wallSeconds,
      fixedTaxSeconds,
      workSeconds,
      startedAt: job.started_at || null,
      completedAt: job.completed_at || null,
      steps,
    };
    // For unit lanes, split the opaque "Unit tests" step (which `workSeconds`
    // captures) into actual test CPU vs framework overhead by correlating the
    // summed suite time from that lane's already-uploaded unit-test-attribution
    // artifact. This is a DERIVED projection over two existing records, never a
    // re-measurement (tempdoc 668 design). `testCpu` is summed suite time — CPU,
    // not wall, when a module runs parallel forks — so overhead is a conservative
    // floor and is clamped at 0 to stay non-negative under that overcount.
    const laneMatch = UNIT_LANE_JOB.exec(report.name);
    const suite = laneMatch ? suiteTimes[laneMatch[1]] : undefined;
    if (Number.isFinite(suite)) {
      report.components = {
        testCpuSeconds: Math.round(suite),
        frameworkOverheadSeconds: Math.max(0, Math.round(workSeconds - suite)),
        fixedTaxSeconds,
      };
    }
    jobReports.push(report);
  }
  jobReports.sort((a, b) => b.wallSeconds - a.wallSeconds || a.name.localeCompare(b.name));

  const critical = jobReports[0] || null;
  const totals = {
    jobs: jobReports.length,
    // Historical field: longest single job, not elapsed required-CI completion.
    criticalPathSeconds: critical ? critical.wallSeconds : 0,
    summedJobSeconds: jobReports.reduce((a, j) => a + j.wallSeconds, 0),
    fixedTaxSeconds: jobReports.reduce((a, j) => a + j.fixedTaxSeconds, 0),
    workSeconds: jobReports.reduce((a, j) => a + j.workSeconds, 0),
  };

  const requiredNames = [...requiredChecks];
  // Preserve reporting for runs from before the split.
  if (jobs.some((j) => PART_CHECKS.includes(j.name))) requiredNames.push(...PART_CHECKS);
  const requiredJobs = jobs.filter((j) => requiredNames.includes(j.name));
  const missing = requiredNames.filter((name) => !requiredJobs.some((j) => j.name === name && j.completed_at));
  const starts = requiredJobs.map((j) => Date.parse(j.started_at)).filter(Number.isFinite);
  const ends = requiredJobs.map((j) => Date.parse(j.completed_at)).filter(Number.isFinite);
  const startedAt = starts.length ? new Date(Math.min(...starts)).toISOString() : null;
  const completedAt = missing.length === 0 && ends.length ? new Date(Math.max(...ends)).toISOString() : null;
  const requiredCompletion = {
    startedAt, completedAt, elapsedSeconds: elapsedSeconds(startedAt, completedAt),
    complete: Boolean(startedAt && completedAt), missingChecks: missing,
  };
  const warnings = missing.map((name) => `required job missing or incomplete: ${name}`);
  for (const job of jobReports) {
    if (UNIT_LANE_JOB.test(job.name) && job.name !== 'Unit tests (app-ui)' && !job.components) {
      warnings.push(`unit attribution missing for ${job.name}`);
    }
  }
  totals.unitTestCpuSeconds = jobReports.reduce((sum, job) => sum + (job.components?.testCpuSeconds || 0), 0);
  totals.runnerMinutes = totals.summedJobSeconds / 60;
  totals.requiredCiElapsedSeconds = requiredCompletion.elapsedSeconds;

  return {
    kind: KIND,
    generatedAt: new Date().toISOString(),
    run: {
      runId: run.runId || null,
      runAttempt: run.runAttempt || null,
      repository: run.repository || null,
      workflow: run.workflow || null,
    },
    // The jobs API reuses the ORIGINAL attempt's step timestamps on unchanged
    // reruns (run_attempt > 1); surface it so consumers can discount stale timings.
    rerunCaveat: Number(run.runAttempt) > 1,
    criticalPath: critical ? { job: critical.name, wallSeconds: critical.wallSeconds } : null,
    requiredCompletion,
    warnings,
    totals,
    jobs: jobReports,
  };
}

function fmtSeconds(n) {
  if (n === null || n === undefined) return 'n/a';
  if (n >= 60) return `${Math.floor(n / 60)}m ${Math.round(n % 60)}s`;
  return `${n}s`;
}

export function renderMarkdown(report) {
  // The pacer's full split (for the directional "next lever" line below).
  const critJob = report.criticalPath
    ? report.jobs.find((j) => j.name === report.criticalPath.job)
    : null;
  const lines = [
    '### CI wall-clock attribution',
    '',
    `Generated: ${report.generatedAt}`,
    '',
    `Run: ${report.run.repository || 'unknown'} #${report.run.runId || 'unknown'} (attempt ${report.run.runAttempt || '1'}), workflow ${report.run.workflow || 'unknown'}`,
    '',
    report.rerunCaveat
      ? '> Rerun (attempt > 1): the Actions jobs API reuses original-attempt step timestamps for unchanged jobs, so durations may be stale.'
      : '',
    report.criticalPath
      ? `Critical path: **${report.criticalPath.job}** at ${fmtSeconds(report.criticalPath.wallSeconds)} (longest single job).`
      : 'Critical path: none (no completed jobs).',
    '',
    `Required CI completion: ${fmtSeconds(report.requiredCompletion.elapsedSeconds)} (${report.requiredCompletion.startedAt || 'unknown'} to ${report.requiredCompletion.completedAt || 'incomplete'}; includes aggregate scheduling).`,
    ...report.warnings.map((warning) => `Warning: ${warning}`),
    '',
    critJob
      ? `Next lever: to shrink the run, shrink **${critJob.name}** — ${fmtSeconds(critJob.workSeconds)} addressable work vs ${fmtSeconds(critJob.fixedTaxSeconds)} fixed runner tax.`
      : '',
    '',
    `Totals: ${report.totals.jobs} jobs, fixed runner tax ${fmtSeconds(report.totals.fixedTaxSeconds)}, work ${fmtSeconds(report.totals.workSeconds)} (summed across jobs).`,
    '',
    '| Job | Wall clock | Fixed tax | Work | Conclusion |',
    '|---|---:|---:|---:|---|',
  ].filter((line) => line !== '');
  for (const job of report.jobs) {
    lines.push(`| ${job.name} | ${fmtSeconds(job.wallSeconds)} | ${fmtSeconds(job.fixedTaxSeconds)} | ${fmtSeconds(job.workSeconds)} | ${job.conclusion || 'unknown'} |`);
  }
  lines.push('');

  const decomposed = report.jobs.filter((job) => job.components);
  if (decomposed.length > 0) {
    lines.push(
      '#### Unit-lane cost breakdown',
      '',
      'Where a unit lane\'s wall-clock actually goes: how much is real test work vs framework overhead (Gradle configuration + JVM startup + fork serialization + inter-module gaps). Derived by correlating each lane\'s summed suite time with its test-step wall-clock — no re-measurement.',
      '',
      '| Lane | Wall clock | Fixed tax | Framework overhead | Test CPU | Overhead % |',
      '|---|---:|---:|---:|---:|---:|',
    );
    for (const job of decomposed) {
      const c = job.components;
      const denom = c.frameworkOverheadSeconds + c.testCpuSeconds;
      const pct = denom > 0 ? Math.round((100 * c.frameworkOverheadSeconds) / denom) : 0;
      lines.push(`| ${job.name} | ${fmtSeconds(job.wallSeconds)} | ${fmtSeconds(c.fixedTaxSeconds)} | ${fmtSeconds(c.frameworkOverheadSeconds)} | ${fmtSeconds(c.testCpuSeconds)} | ${pct}% |`);
    }
    lines.push(
      '',
      '> Test CPU is summed suite time — when a module runs parallel forks it is CPU, not wall-clock, so the reported framework overhead is a conservative floor.',
      '',
    );
  }
  return `${lines.join('\n')}\n`;
}

function writeText(filePath, text) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, text, 'utf8');
}

// Read every `unit-test-attribution.json` under `dir` (the downloaded
// unit-test-attribution-* artifacts, each in its own subdir) and build a
// { lane: summedSuiteSeconds } map from each report's `lane` + `totals.timeSeconds`.
// Fail-soft: a missing dir or unreadable/foreign file contributes nothing, so the
// lane simply gets no cost breakdown rather than failing the advisory job.
export function readSuiteTimes(dir, jobs = []) {
  const out = {};
  const attempts = {};
  if (!dir || !fs.existsSync(dir)) return out;
  const stack = [dir];
  while (stack.length > 0) {
    const current = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(current, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const abs = path.join(current, entry.name);
      if (entry.isDirectory()) stack.push(abs);
      else if (entry.isFile() && entry.name === 'unit-test-attribution.json') {
        try {
          const data = JSON.parse(fs.readFileSync(abs, 'utf8'));
          if (data && typeof data.lane === 'string' && Number.isFinite(data.totals?.timeSeconds)) {
            const attempt = Number(/-attempt-(\d+)/.exec(abs)?.[1] || 0);
            const job = jobs.find((j) => j.name === `Unit tests (${data.lane})`);
            if (attempt && job?.run_attempt && attempt !== job.run_attempt) continue;
            if (attempt < (attempts[data.lane] ?? -1)) continue;
            attempts[data.lane] = attempt;
            out[data.lane] = data.totals.timeSeconds;
          }
        } catch {
          // Skip unreadable/foreign files.
        }
      }
    }
  }
  return out;
}

// Split concatenated top-level JSON documents. `gh api --paginate` emits one
// document per page (`{total_count, jobs:[...]}`), so a multi-page result is
// several objects back-to-back; a single page is one document. Brace/bracket
// depth aware and string aware so a `}{` inside a string value is not a boundary.
function splitJsonDocuments(text) {
  const docs = [];
  let depth = 0;
  let inStr = false;
  let esc = false;
  let start = 0;
  for (let i = 0; i < text.length; i += 1) {
    const c = text[i];
    if (inStr) {
      if (esc) esc = false;
      else if (c === '\\') esc = true;
      else if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') inStr = true;
    else if (c === '{' || c === '[') {
      if (depth === 0) start = i;
      depth += 1;
    } else if (c === '}' || c === ']') {
      depth -= 1;
      if (depth === 0) docs.push(text.slice(start, i + 1));
    }
  }
  return docs;
}

// Merge `gh api .../jobs --paginate` output into a single { jobs: [...] }. Pure +
// exported so the paging logic is unit-tested while the gh shell-out stays thin,
// untested I/O (mirrors workflow-signal-health.mjs).
export function mergePaginatedJobs(text) {
  const jobs = [];
  for (const doc of splitJsonDocuments(String(text || ''))) {
    let parsed;
    try {
      parsed = JSON.parse(doc);
    } catch {
      continue; // skip an unparseable fragment rather than fail the whole report
    }
    if (Array.isArray(parsed)) jobs.push(...parsed);
    else if (Array.isArray(parsed.jobs)) jobs.push(...parsed.jobs);
  }
  return { jobs };
}

// ---- On-demand fetch (thin gh/git shell-outs; untested I/O) ----
// gh resolves {owner}/{repo} from the current checkout, so no repo arg is needed.
function gh(args) {
  return execFileSync('gh', args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
}

function currentBranch() {
  try {
    return execFileSync('git', ['rev-parse', '--abbrev-ref', 'HEAD'], { encoding: 'utf8' }).trim();
  } catch {
    return null;
  }
}

// Resolve the most recent CI run id on the current branch (for --latest).
function resolveLatestRunId() {
  const branch = currentBranch();
  const args = ['run', 'list', '--workflow', 'ci.yml', '--limit', '1', '--json', 'databaseId'];
  if (branch) args.push('--branch', branch);
  const rows = JSON.parse(gh(args));
  const id = Array.isArray(rows) && rows[0] ? rows[0].databaseId : null;
  if (!id) throw new Error(`No CI run found${branch ? ` on branch ${branch}` : ''}`);
  return String(id);
}

// Fetch a run's jobs (all pages) plus best-effort header facts.
function fetchRun(runId) {
  const { jobs } = mergePaginatedJobs(gh(['api', `repos/{owner}/{repo}/actions/runs/${runId}/jobs`, '--paginate']));
  let meta = {};
  try {
    const run = JSON.parse(gh(['api', `repos/{owner}/{repo}/actions/runs/${runId}`]));
    meta = {
      repository: run.repository?.full_name ?? null,
      workflow: run.name ?? null,
      runAttempt: run.run_attempt ?? null,
    };
  } catch {
    // Header facts are best-effort; the attribution stands without them.
  }
  return { jobs, meta };
}

// Best-effort download of the unit-test-attribution artifacts (for the deeper
// per-unit-lane split). Returns a temp dir for readSuiteTimes, or null on any
// failure (artifacts expired / none / gh error) so the report degrades to job-level.
function downloadUnitArtifacts(runId) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ci-walltime-artifacts-'));
  try {
    gh(['run', 'download', String(runId), '--pattern', 'unit-test-attribution-*', '--dir', dir]);
    return dir;
  } catch {
    return null;
  }
}

function main() {
  try {
    const opts = parseArgs(process.argv.slice(2));
    if (opts.help) {
      console.log(usage());
      return;
    }

    let jobs;
    let runId = opts.runId;
    let repository = opts.repository;
    let workflow = opts.workflow;
    let runAttempt = null;
    let unitDir = opts.unitAttributionDir;

    if (opts.jobsJson) {
      // CI path: attribute a pre-fetched jobs JSON.
      const parsed = mergePaginatedJobs(fs.readFileSync(path.resolve(opts.jobsJson), 'utf8'));
      jobs = Array.isArray(parsed) ? parsed : parsed.jobs || [];
      runAttempt = runId ? Math.max(...jobs.map((j) => j.run_attempt || 1), 1) : Math.max(...jobs.map((j) => j.run_attempt || 1), 1);
    } else {
      // On-demand path: fetch the run ourselves (--run-id / --latest).
      if (opts.latest && !runId) runId = resolveLatestRunId();
      const { jobs: fetched, meta } = fetchRun(runId);
      jobs = fetched;
      repository = repository ?? meta.repository;
      workflow = workflow ?? meta.workflow;
      runAttempt = meta.runAttempt ?? Math.max(...jobs.map((j) => j.run_attempt || 1), 1);
      if (opts.downloadArtifacts && !unitDir) unitDir = downloadUnitArtifacts(runId);
    }

    const report = buildWalltimeReport({
      run: { runId, runAttempt, repository, workflow },
      jobs,
      selfJob: opts.selfJob,
      suiteTimes: readSuiteTimes(unitDir && path.resolve(unitDir), jobs),
    });
    const json = `${JSON.stringify(report, null, 2)}\n`;
    const md = renderMarkdown(report);
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
    console.error(`report-ci-walltime-attribution: ${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
