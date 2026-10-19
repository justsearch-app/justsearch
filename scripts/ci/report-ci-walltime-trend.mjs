#!/usr/bin/env node
/**
 * Wall-clock TREND analyzer over the last N public CI runs on `main`.
 *
 * The per-run budget (report-ci-walltime-budget.mjs) answers "did THIS run exceed
 * its ceiling"; per-run wall-clock is noisy (~6-10% CV), so a single over-budget
 * run is weak signal. This analyzer answers the durable question — "is a lane's
 * wall-clock drifting up / are we at the floor" — by taking the MEDIAN over N runs
 * and flagging only SUSTAINED drift (median above the advisory ceiling). Warn-only:
 * it never fails anything; its workflow may open a single de-duped issue on drift.
 * Tempdoc 668 (post-hoc analyzer + actions:read + issues:write + var gate pattern).
 *
 * Input: --runs-json <file> = array of per-run summaries
 *   [{ runId, createdAt, jobs: [{ name, wallSeconds }] }, ...]
 * If omitted, main() self-fetches the last N successful `main` CI runs via `gh`.
 */

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import { mergePaginatedJobs } from './report-ci-walltime-attribution.mjs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const KIND = 'justsearch-ci-walltime-trend.v1';

function repoRootFromCwd() {
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, 'settings.gradle.kts'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
  }
}

function usage() {
  return [
    'Usage: node scripts/ci/report-ci-walltime-trend.mjs [options]',
    '',
    'Options:',
    '  --runs-json <path>        Pre-assembled array of per-run summaries (else fetch via gh)',
    '  --limit <n>               Runs to fetch when self-fetching (default: 20)',
    '  --workflow <file>         Workflow file to fetch (default: ci.yml)',
    '  --policy <file>           CI wall-clock policy JSON',
    '  --out-json <path>         Write JSON report',
    '  --out-md <path>           Write Markdown report',
    '  --json                    Print JSON to stdout',
    '  --md                      Print Markdown to stdout',
    '  -h, --help',
  ].join('\n');
}

function parseArgs(argv) {
  const out = {
    runsJson: null,
    limit: 20,
    workflow: 'ci.yml',
    policy: null,
    outJson: null,
    outMd: null,
    json: false,
    md: false,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--runs-json' && argv[i + 1]) out.runsJson = argv[++i];
    else if (arg === '--limit' && argv[i + 1]) out.limit = Number.parseInt(argv[++i], 10);
    else if (arg === '--workflow' && argv[i + 1]) out.workflow = argv[++i];
    else if (arg === '--policy' && argv[i + 1]) out.policy = argv[++i];
    else if (arg === '--out-json' && argv[i + 1]) out.outJson = argv[++i];
    else if (arg === '--out-md' && argv[i + 1]) out.outMd = argv[++i];
    else if (arg === '--json') out.json = true;
    else if (arg === '--md') out.md = true;
    else if (arg === '--help' || arg === '-h') out.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  if (!Number.isInteger(out.limit) || out.limit <= 0) throw new Error('--limit must be a positive integer');
  return out;
}

function median(values) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = sorted.length >> 1;
  return sorted.length % 2 ? sorted[mid] : Math.round((sorted[mid - 1] + sorted[mid]) / 2);
}

export function buildTrendReport({ runs = [], policy = {} }) {
  const lanes = Array.isArray(policy.lanes) ? policy.lanes : [];
  const laneReports = [];
  const drifted = [];

  for (const lane of lanes) {
    const ceiling = lane?.advisoryBudgets?.maxWallSeconds;
    const samples = [];
    for (const run of runs) {
      const job = (run.jobs || []).find((j) => j.name === lane.job);
      if (job && Number.isFinite(job.wallSeconds)) samples.push(job.wallSeconds);
    }
    const med = median(samples);
    const overBudget = Number.isFinite(ceiling) && med !== null && med > ceiling;
    const report = {
      missingRuns: runs.length - samples.length,
      job: lane.job,
      runs: samples.length,
      medianSeconds: med,
      minSeconds: samples.length ? Math.min(...samples) : null,
      maxSeconds: samples.length ? Math.max(...samples) : null,
      ceilingSeconds: Number.isFinite(ceiling) ? ceiling : null,
      overBudget,
    };
    laneReports.push(report);
    if (overBudget) drifted.push(report);
  }

  return {
    kind: KIND,
    generatedAt: new Date().toISOString(),
    advisory: true,
    runCount: runs.length,
    lanes: laneReports,
    driftedLanes: drifted,
    warnings: laneReports.filter((lane) => lane.missingRuns > 0).map((lane) => `${lane.job}: missing from ${lane.missingRuns}/${runs.length} runs`),
    // True only on SUSTAINED drift (median over N above ceiling) — the workflow
    // opens an issue only when this is set, so a single slow run never notifies.
    sustainedDrift: drifted.length > 0,
  };
}

function fmtSeconds(n) {
  if (n === null || n === undefined) return 'n/a';
  if (n >= 60) return `${Math.floor(n / 60)}m ${Math.round(n % 60)}s`;
  return `${n}s`;
}

export function renderMarkdown(report) {
  const lines = [
    '### CI wall-clock trend',
    '',
    `Generated: ${report.generatedAt}`,
    '',
    `Window: last ${report.runCount} run(s). Status: ${report.sustainedDrift ? `⚠️ sustained drift in ${report.driftedLanes.length} lane(s)` : 'within advisory ceilings'} (warn-only).`,
    '',
    '| Job | Median | Min | Max | Advisory ceiling | Drift |',
    '|---|---:|---:|---:|---:|:--:|',
  ];
  for (const lane of report.lanes) {
    lines.push(`| ${lane.job} | ${fmtSeconds(lane.medianSeconds)} | ${fmtSeconds(lane.minSeconds)} | ${fmtSeconds(lane.maxSeconds)} | ${lane.ceilingSeconds === null ? 'n/a' : fmtSeconds(lane.ceilingSeconds)} | ${lane.overBudget ? '⚠️' : ''} |`);
  }
  lines.push('', ...report.warnings.map((warning) => `Warning: ${warning}`));
  return `${lines.join('\n')}\n`;
}

function writeText(filePath, text) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, text, 'utf8');
}

function gh(args) {
  return execFileSync('gh', args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
}

function elapsedSeconds(startedAt, completedAt) {
  if (!startedAt || !completedAt) return null;
  const start = Date.parse(startedAt);
  const end = Date.parse(completedAt);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return null;
  return Math.round((end - start) / 1000);
}

function fetchRuns({ limit, workflow }) {
  const list = JSON.parse(gh(['run', 'list', '--workflow', workflow, '--branch', 'main', '--status', 'success', '--limit', String(limit), '--json', 'databaseId,createdAt']));
  const runs = [];
  for (const entry of list) {
    const data = mergePaginatedJobs(gh(['api', `repos/{owner}/{repo}/actions/runs/${entry.databaseId}/jobs`, '--paginate']));
    const jobs = (data.jobs || []).map((j) => ({ name: j.name, wallSeconds: elapsedSeconds(j.started_at, j.completed_at) })).filter((j) => j.wallSeconds !== null);
    runs.push({ runId: entry.databaseId, createdAt: entry.createdAt, jobs });
  }
  return runs;
}

function main() {
  try {
    const opts = parseArgs(process.argv.slice(2));
    if (opts.help) {
      console.log(usage());
      return;
    }
    const root = repoRootFromCwd();
    const policyPath = path.resolve(opts.policy || path.join(root, 'scripts', 'ci', 'ci-walltime-policy.v1.json'));
    const policy = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
    const runs = opts.runsJson
      ? JSON.parse(fs.readFileSync(path.resolve(opts.runsJson), 'utf8'))
      : fetchRuns({ limit: opts.limit, workflow: opts.workflow });
    const report = buildTrendReport({ runs, policy });
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
    console.error(`report-ci-walltime-trend: ${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
