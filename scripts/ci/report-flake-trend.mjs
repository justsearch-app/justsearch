#!/usr/bin/env node
/**
 * Flake-trend aggregation across CI runs (829 follow-up to PR #447).
 *
 * PR #447 wired `flakyTests` extraction into `report-unit-test-attribution.mjs`
 * (829 R5): every CI run's three unit lanes upload `unit-test-attribution-<lane>`
 * and the integration lane uploads `integration-test-results` (which contains
 * `build/ci/integration-test-attribution.json`), each carrying a `flakyTests`
 * array. That extraction is per-run and per-lane — nothing aggregates it ACROSS
 * runs, so a per-test flake RATE over time (the one signal a commercial
 * Develocity server would have provided, 829 F5) stays invisible. This script
 * closes that gap.
 *
 * Deliberately NOT wired into CI: it reads across many runs via `gh`, which no
 * single CI lane can do cheaply (a lane only ever sees its own run). Run it as
 * an agent/manual CLI:
 *
 *   node scripts/ci/report-flake-trend.mjs --days 7 --md
 *
 * Collection (network, via `gh`) is kept structurally separate from aggregation
 * (pure, no fs/network) so the aggregation semantics are unit-testable without
 * a live `gh` session — see test-report-flake-trend.mjs and the `--records-json`
 * escape hatch below, which replays a pre-fetched records array offline.
 */

import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import { resolveGhBin } from '../dev/run-gh.mjs';

const KIND = 'justsearch-flake-trend.v1';

// PR #447's merge commit (c3cbaef3) is the moment `flakyTests` extraction first shipped.
// Any CI run that completed before this timestamp has no attribution artifact to read —
// that is "no data", never "zero flakes" (honesty rule: don't conflate the two).
export const WIRING_BOUNDARY_ISO = '2026-08-14T00:43:16Z';

const DEFAULT_DAYS = 7;
const DEFAULT_MAX_RUNS = 30;
const MAX_RUNS_CAP = 100;
const DEFAULT_MIN_OCCURRENCES = 1;
const WARN_THRESHOLD = 3;
const CI_WORKFLOW_FILE = 'ci.yml';
const API_PAGE_SIZE = 100;
const API_MAX_PAGES = 5; // bounds worst-case fetch cost for a large --days window

function repoRootFromCwd() {
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, 'settings.gradle.kts'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
  }
}

// Not exported by scripts/ci/workflow-signal-health.mjs, so reproduced here (credit: same
// origin/URL-parse pattern) rather than reaching across scripts for an unexported helper.
function detectRepoSlug() {
  try {
    const url = spawnSync('git', ['remote', 'get-url', 'origin'], { encoding: 'utf8' }).stdout.trim();
    const match = url.match(/github\.com[:/](.+?)\/(.+?)(?:\.git)?$/i);
    if (match) return `${match[1]}/${match[2]}`;
  } catch {
    // Fall through to the repository default.
  }
  return 'justsearch-app/justsearch';
}

function usage() {
  return [
    'Usage: node scripts/ci/report-flake-trend.mjs [options]',
    '',
    'Aggregates flakyTests attribution across CI runs to surface per-test flake rates',
    '(the cross-run signal a commercial Develocity server would have provided, 829 F5).',
    '',
    'Options:',
    `  --days N               Window in days (default ${DEFAULT_DAYS})`,
    `  --max-runs N           Max runs to scan, capped at ${MAX_RUNS_CAP} (default ${DEFAULT_MAX_RUNS})`,
    '  --branch <name>        Restrict to one branch (default: all branches — PR-lane flakes matter too)',
    `  --min-occurrences N    Filter report rows to occurrences >= N (default ${DEFAULT_MIN_OCCURRENCES})`,
    '  --cache-dir <path>     Per-run cache dir (default tmp/agent-telemetry/flake-trend-cache under repo root)',
    '  --repo <owner/repo>    Override the detected GitHub repo slug',
    '  --records-json <path>  Skip gh collection; aggregate pre-fetched records from this file (offline/testing)',
    '  --out-json <path>      Write JSON report',
    '  --out-md <path>        Write Markdown report',
    '  --json                 Print JSON to stdout',
    '  --md                   Print Markdown to stdout',
    '  -h, --help',
  ].join('\n');
}

export function parseArgs(argv) {
  const out = {
    days: DEFAULT_DAYS,
    maxRuns: DEFAULT_MAX_RUNS,
    branch: null,
    minOccurrences: DEFAULT_MIN_OCCURRENCES,
    cacheDir: null,
    repo: null,
    recordsJson: null,
    outJson: null,
    outMd: null,
    json: false,
    md: false,
    now: null,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--days' && argv[i + 1]) out.days = Number.parseInt(argv[++i], 10);
    else if (arg === '--max-runs' && argv[i + 1]) out.maxRuns = Number.parseInt(argv[++i], 10);
    else if (arg === '--branch' && argv[i + 1]) out.branch = argv[++i];
    else if (arg === '--min-occurrences' && argv[i + 1]) out.minOccurrences = Number.parseInt(argv[++i], 10);
    else if (arg === '--cache-dir' && argv[i + 1]) out.cacheDir = argv[++i];
    else if (arg === '--repo' && argv[i + 1]) out.repo = argv[++i];
    else if (arg === '--records-json' && argv[i + 1]) out.recordsJson = argv[++i];
    else if (arg === '--out-json' && argv[i + 1]) out.outJson = argv[++i];
    else if (arg === '--out-md' && argv[i + 1]) out.outMd = argv[++i];
    else if (arg === '--json') out.json = true;
    else if (arg === '--md') out.md = true;
    else if (arg === '--now' && argv[i + 1]) out.now = argv[++i]; // hidden: deterministic tests only
    else if (arg === '--help' || arg === '-h') out.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  if (!Number.isInteger(out.days) || out.days <= 0) throw new Error('--days must be a positive integer');
  if (!Number.isInteger(out.maxRuns) || out.maxRuns <= 0) throw new Error('--max-runs must be a positive integer');
  if (out.maxRuns > MAX_RUNS_CAP) {
    console.error(`report-flake-trend: --max-runs ${out.maxRuns} exceeds the cap; clamping to ${MAX_RUNS_CAP}`);
    out.maxRuns = MAX_RUNS_CAP;
  }
  if (!Number.isInteger(out.minOccurrences) || out.minOccurrences <= 0) {
    throw new Error('--min-occurrences must be a positive integer');
  }
  return out;
}

// ---------------------------------------------------------------------------
// Collection (impure: gh api / gh run download / fs cache). Kept out of the
// aggregation path below so aggregation can be unit-tested with no network.
// ---------------------------------------------------------------------------

/** Lane -> {artifact, file} map. Unit lanes come from the existing shard policy register
 * (avoids a second, drifting copy of lane/artifact names); integration-tests is wired
 * directly since it is deliberately NOT a unit-test shard (ci.yml comment near the
 * "Report integration-test attribution" step: report-unit-test-budget.mjs throws on an
 * undeclared lane, and integration-tests isn't one). */
export function buildLaneArtifactMap(repoRoot) {
  const policyPath = path.join(repoRoot, 'scripts/ci/unit-test-shard-policy.v1.json');
  const policy = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
  const map = {};
  for (const laneEntry of policy.lanes) {
    map[laneEntry.lane] = { artifact: laneEntry.artifact, file: 'unit-test-attribution.json' };
  }
  for (const [lane, artifact] of Object.entries(policy.historicalArtifacts || {})) {
    map[lane] = { artifact, file: 'unit-test-attribution.json' };
  }
  map['integration-tests'] = { artifact: 'integration-test-results', file: 'integration-test-attribution.json' };
  return map;
}

function findFileRecursive(dir, filename) {
  if (!fs.existsSync(dir)) return null;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const abs = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      const found = findFileRecursive(abs, filename);
      if (found) return found;
    } else if (entry.isFile() && entry.name === filename) {
      return abs;
    }
  }
  return null;
}

/** Download one named artifact for one run and read `filename` out of it, or null when the
 * artifact doesn't exist for this run (pre-#447 runs, or a lane that didn't execute). */
function downloadAndReadJson(ghBin, repo, runId, artifactName, filename, scratchDir) {
  fs.mkdirSync(scratchDir, { recursive: true });
  const res = spawnSync(ghBin, ['run', 'download', String(runId), '-n', artifactName, '-D', scratchDir, '--repo', repo], {
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  if (res.status !== 0) return null;
  const found = findFileRecursive(scratchDir, filename);
  if (!found) return null;
  try {
    return JSON.parse(fs.readFileSync(found, 'utf8'));
  } catch {
    return null;
  }
}

/** Build (or read from cache) the compact per-run record: which lanes had attribution data,
 * and their flakyTests. Cached per run-id so repeat invocations skip re-downloading. */
export function ensureRunRecord(ghBin, repo, run, laneMap, cacheDir) {
  const cacheFile = path.join(cacheDir, `${run.id}-attempt-${run.run_attempt || 1}.json`);
  if (fs.existsSync(cacheFile)) {
    try {
      return JSON.parse(fs.readFileSync(cacheFile, 'utf8'));
    } catch {
      // Corrupt cache entry — fall through and refetch.
    }
  }

  // Artifact names include attempts for independently rerunnable parts. Select
  // the latest available attempt for each part; unchanged parts retain their evidence.
  const listing = spawnSync(ghBin, ['api', `repos/${repo}/actions/runs/${run.id}/artifacts?per_page=100`], { encoding: 'utf8' });
  let artifacts = [];
  if (listing.status === 0) {
    try { artifacts = JSON.parse(listing.stdout).artifacts || []; } catch { /* missing evidence remains no data */ }
  }
  const jobListing = spawnSync(ghBin, ['api', `repos/${repo}/actions/runs/${run.id}/jobs?filter=latest&per_page=100`], { encoding: 'utf8' });
  let jobs = [];
  if (jobListing.status === 0) {
    try { jobs = JSON.parse(jobListing.stdout).jobs || []; } catch { /* no data */ }
  }
  const lanes = {};
  for (const [laneName, { artifact, file }] of Object.entries(laneMap)) {
    const scratchDir = path.join(cacheDir, '_scratch', String(run.id), laneName);
    const job = jobs.find((j) => j.name === `Unit tests (${laneName})`);
    const candidates = artifacts.filter((a) => !a.expired && (a.name === artifact
      || (a.name.startsWith(`${artifact}-attempt-`) && (!job?.run_attempt || a.name === `${artifact}-attempt-${job.run_attempt}`))));
    candidates.sort((a, b) => Number(b.name.split('-attempt-')[1] || 0) - Number(a.name.split('-attempt-')[1] || 0));
    const selected = candidates[0]?.name || artifact;
    const json = downloadAndReadJson(ghBin, repo, run.id, selected, file, scratchDir);
    fs.rmSync(scratchDir, { recursive: true, force: true });
    if (json && Array.isArray(json.flakyTests)) {
      lanes[laneName] = { hasData: true, flakyTests: json.flakyTests };
    } else {
      lanes[laneName] = { hasData: false, flakyTests: null };
    }
  }

  const record = {
    runId: run.id,
    createdAt: run.created_at,
    branch: run.head_branch,
    url: run.html_url,
    lanes,
  };
  fs.mkdirSync(cacheDir, { recursive: true });
  fs.writeFileSync(cacheFile, `${JSON.stringify(record, null, 2)}\n`, 'utf8');
  return record;
}

/** Page through completed CI-workflow runs newest-first, stopping once a page's oldest run
 * predates the window (further pages can only be older) or a hard page cap is hit. Pure
 * date-window filtering/capping happens separately in filterRunsInWindow. */
function fetchCiRunsInWindow(ghBin, repo, { days, branch, now }) {
  const cutoffMs = now.getTime() - days * 24 * 60 * 60 * 1000;
  const collected = [];
  for (let page = 1; page <= API_MAX_PAGES; page += 1) {
    const qs = new URLSearchParams({ per_page: String(API_PAGE_SIZE), page: String(page), status: 'completed' });
    if (branch) qs.set('branch', branch);
    // -q projects down to just the fields this tool uses: the full run object (each includes
    // ~40 GitHub-hosted-URL fields, actor/committer blobs, etc.) blew past spawnSync's default
    // maxBuffer at 100 runs/page (measured ENOBUFS at ~1MB of stdout for a single page) — the
    // maxBuffer override below is defense in depth, the field projection is the actual fix.
    const args = [
      'api',
      `repos/${repo}/actions/workflows/${CI_WORKFLOW_FILE}/runs?${qs.toString()}`,
      '-q',
      '.workflow_runs[] | {id, created_at, head_branch, html_url, status}',
    ];
    const res = spawnSync(ghBin, args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
    if (res.status !== 0) {
      throw new Error(`gh api workflow runs (page ${page}) failed: ${(res.stderr || res.stdout || '').trim()}`);
    }
    const lines = res.stdout.split('\n').map((line) => line.trim()).filter(Boolean);
    if (lines.length === 0) break;
    const pageRuns = lines.map((line) => JSON.parse(line));
    collected.push(...pageRuns);
    const oldest = pageRuns[pageRuns.length - 1];
    if (new Date(oldest.created_at).getTime() < cutoffMs) break;
    if (lines.length < API_PAGE_SIZE) break;
  }
  return collected;
}

// ---------------------------------------------------------------------------
// Pure filtering + aggregation (no fs/network — unit-tested directly).
// ---------------------------------------------------------------------------

/** Filter raw `gh api` run objects down to the requested window/branch/cap, newest-first. */
export function filterRunsInWindow(rawRuns, { days, maxRuns, branch, now }) {
  const cutoffMs = now.getTime() - days * 24 * 60 * 60 * 1000;
  return rawRuns
    .filter((run) => run.status === 'completed')
    .filter((run) => !branch || run.head_branch === branch)
    .filter((run) => new Date(run.created_at).getTime() >= cutoffMs)
    .sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime())
    .slice(0, maxRuns);
}

function round3(n) {
  return Math.round(n * 1000) / 1000;
}

/** The suggested (not auto-written) remedy for a test that crossed the WARN threshold. Tempdoc 930
 * retired the expected-state pin: remembering a flaky test made red-on-main cheaper to live with
 * than to fix, so the remedy is the fix (or a quarantine that keeps the signal). Printed for a
 * human/agent; never written here. */
export function buildSuggestedObservationText(test, days) {
  const rate = test.runsWithData > 0 ? round3(test.occurrences / test.runsWithData) : 0;
  const description = `Flaky test ${test.classname}.${test.name} (lane ${test.lane}) flaked ${test.occurrences}/${test.runsWithData} runs (rate ${rate}) in the last ${days}d window`;
  return `fix the flake — ${description}. If it cannot be fixed now, quarantine it in its own runner (so the suite stays a signal) and track the fix in the owning tempdoc — source: scripts/ci/report-flake-trend.mjs`;
}

/**
 * Aggregate per-run attribution records into per-(lane, classname, name) flake trends.
 *
 * `records`: [{ runId, createdAt, branch, url, lanes: { <lane>: { hasData, flakyTests|null } } }]
 *
 * Honesty rules encoded here: a lane with hasData:false never contributes to that test's
 * runs-with-data denominator (absent artifact = no data, not zero flakes); occurrences only
 * ever count runs where hasData was true. Revert-probe target: if the `!laneData.hasData`
 * early-continue below were removed (i.e. a no-data lane were treated as a zero-flake lane),
 * runsWithData would include no-data runs, silently deflating every rate — the fixture in
 * test-report-flake-trend.mjs asserting `runsWithData === 2` (not 3) for a 3-run window with
 * one no-data run is the one that catches this exact break.
 */
export function aggregateFlakeTrend(records, opts = {}) {
  const minOccurrences = opts.minOccurrences ?? DEFAULT_MIN_OCCURRENCES;
  const days = opts.days ?? DEFAULT_DAYS;
  const wiringBoundaryIso = opts.wiringBoundaryIso ?? WIRING_BOUNDARY_ISO;
  const wiringBoundaryMs = new Date(wiringBoundaryIso).getTime();

  const totalRuns = records.length;
  let runsWithAnyData = 0;
  let preWiringRuns = 0;

  const laneRunsWithData = {};
  for (const record of records) {
    if (new Date(record.createdAt).getTime() < wiringBoundaryMs) preWiringRuns += 1;

    let recordHasAnyData = false;
    for (const [lane, laneData] of Object.entries(record.lanes || {})) {
      if (!laneData || !laneData.hasData) continue;
      recordHasAnyData = true;
      laneRunsWithData[lane] = (laneRunsWithData[lane] || 0) + 1;
    }
    if (recordHasAnyData) runsWithAnyData += 1;
  }

  const testMap = new Map();
  for (const record of records) {
    for (const [lane, laneData] of Object.entries(record.lanes || {})) {
      if (!laneData || !laneData.hasData) continue;
      for (const flaky of laneData.flakyTests || []) {
        const key = `${lane}::${flaky.classname}::${flaky.name}`;
        let entry = testMap.get(key);
        if (!entry) {
          entry = {
            classname: flaky.classname,
            name: flaky.name,
            lane,
            module: flaky.module || null,
            occurrences: 0,
            runsWithData: laneRunsWithData[lane] || 0,
            firstSeen: record.createdAt,
            lastSeen: record.createdAt,
            branches: new Set(),
            maxAttempts: 0,
            sampleMessage: flaky.firstFailureMessage || null,
          };
          testMap.set(key, entry);
        }
        entry.occurrences += 1;
        entry.maxAttempts = Math.max(entry.maxAttempts, flaky.attempts || 0);
        entry.branches.add(record.branch || 'unknown');
        if (new Date(record.createdAt).getTime() < new Date(entry.firstSeen).getTime()) entry.firstSeen = record.createdAt;
        if (new Date(record.createdAt).getTime() > new Date(entry.lastSeen).getTime()) entry.lastSeen = record.createdAt;
      }
    }
  }

  const allTests = [...testMap.values()]
    .map((entry) => ({
      ...entry,
      branches: [...entry.branches].sort(),
      rate: entry.runsWithData > 0 ? round3(entry.occurrences / entry.runsWithData) : 0,
    }))
    .sort((a, b) => b.occurrences - a.occurrences || a.classname.localeCompare(b.classname) || a.name.localeCompare(b.name));

  const tests = allTests.filter((test) => test.occurrences >= minOccurrences);
  const warnings = allTests.filter((test) => test.occurrences >= WARN_THRESHOLD).map((test) => buildSuggestedObservationText(test, days));

  return {
    kind: KIND,
    generatedAt: new Date().toISOString(),
    window: {
      days,
      branch: opts.branch || null,
      minOccurrences,
      totalRuns,
      runsWithData: runsWithAnyData,
      preWiringRuns,
      wiringBoundaryIso,
      spansWiringBoundary: preWiringRuns > 0 && preWiringRuns < totalRuns,
    },
    allTestCount: allTests.length,
    tests,
    warnings,
  };
}

// ---------------------------------------------------------------------------
// Rendering.
// ---------------------------------------------------------------------------

function fmtDate(iso) {
  return iso ? iso.slice(0, 10) : 'unknown';
}

export function renderMarkdown(report) {
  const w = report.window;
  const branchLabel = w.branch ? ` (branch: ${w.branch})` : ' (all branches)';
  const lines = [
    `### Flake trend — last ${w.days}d${branchLabel}`,
    '',
    `Generated: ${report.generatedAt}`,
    '',
    `Summary: ${report.allTestCount} flaky tests across ${w.runsWithData} runs with data, ${w.preWiringRuns} runs pre-dated the wiring.`,
    '',
  ];

  if (w.spansWiringBoundary) {
    lines.push(
      `Note: this window spans the ${fmtDate(w.wiringBoundaryIso)} flake-trend wiring boundary (PR #447). ` +
        'Runs before that date have no attribution artifact to read at all — they count as ' +
        '"no data", not "zero flakes", and are excluded from every rate below.',
      '',
    );
  }

  if (w.totalRuns === 0) {
    lines.push('No completed CI runs found in this window.', '');
  } else if (w.runsWithData === 0) {
    lines.push(`All ${w.totalRuns} runs in this window pre-date the wiring boundary — no attribution data available yet.`, '');
  }

  if (w.minOccurrences > 1) {
    lines.push(`Filtered to occurrences >= ${w.minOccurrences} (${report.allTestCount} distinct flaky tests observed before filtering).`, '');
  }

  if (report.tests.length > 0) {
    lines.push(
      '| Test | Lane | Occurrences | Runs w/ data | Rate | First seen | Last seen | Max attempts | Branches | Sample message |',
      '|---|---|---:|---:|---:|---|---|---:|---|---|',
    );
    for (const test of report.tests) {
      const message = (test.sampleMessage || '').replaceAll('|', '\\|').replaceAll('\n', ' ');
      lines.push(
        `| ${test.classname}.${test.name} | ${test.lane} | ${test.occurrences} | ${test.runsWithData} | ${test.rate} | ${fmtDate(test.firstSeen)} | ${fmtDate(test.lastSeen)} | ${test.maxAttempts} | ${test.branches.join(', ')} | ${message} |`,
      );
    }
    lines.push('');
  }

  if (report.warnings.length > 0) {
    lines.push(`WARN: ${report.warnings.length} test(s) crossed the >= ${WARN_THRESHOLD}-occurrence threshold this window. Suggested inbox notes (not written automatically):`, '');
    for (const warning of report.warnings) lines.push(`- \`${warning}\``);
    lines.push('');
  }

  return `${lines.join('\n')}\n`;
}

// ---------------------------------------------------------------------------
// CLI entry point.
// ---------------------------------------------------------------------------

function writeText(filePath, text) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, text, 'utf8');
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    console.log(usage());
    return;
  }

  const repoRoot = repoRootFromCwd();
  const cacheDir = path.resolve(opts.cacheDir || path.join(repoRoot, 'tmp/agent-telemetry/flake-trend-cache'));
  const now = opts.now ? new Date(opts.now) : new Date();

  let records;
  if (opts.recordsJson) {
    records = JSON.parse(fs.readFileSync(path.resolve(opts.recordsJson), 'utf8'));
  } else {
    const ghBin = resolveGhBin();
    const repo = opts.repo || detectRepoSlug();
    const laneMap = buildLaneArtifactMap(repoRoot);
    const rawRuns = fetchCiRunsInWindow(ghBin, repo, { days: opts.days, branch: opts.branch, now });
    const windowRuns = filterRunsInWindow(rawRuns, { days: opts.days, maxRuns: opts.maxRuns, branch: opts.branch, now });
    fs.mkdirSync(cacheDir, { recursive: true });
    records = windowRuns.map((run) => ensureRunRecord(ghBin, repo, run, laneMap, cacheDir));
  }

  const report = aggregateFlakeTrend(records, {
    minOccurrences: opts.minOccurrences,
    days: opts.days,
    branch: opts.branch,
  });
  const json = `${JSON.stringify(report, null, 2)}\n`;
  const md = renderMarkdown(report);

  if (opts.outJson) writeText(path.resolve(opts.outJson), json);
  if (opts.outMd) writeText(path.resolve(opts.outMd), md);
  for (const warning of report.warnings) {
    console.error(`WARN: ${warning}`);
  }
  if (opts.json) process.stdout.write(json);
  else if (opts.md || (!opts.outJson && !opts.outMd)) process.stdout.write(md);
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));
if (isMain) {
  try {
    main();
  } catch (error) {
    console.error(`report-flake-trend: ${error.message}`);
    process.exitCode = 1;
  }
}
