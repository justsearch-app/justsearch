#!/usr/bin/env node
/**
 * Render a per-run public CI evidence digest.
 *
 * This is an advisory read-model. It composes GitHub run/job/artifact facts,
 * workflow-signal policy, cache usage, and optional unit attribution JSON. It
 * does not decide whether CI passed and does not replace required checks.
 */

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import { selectJobArtifact } from './lib/ci-artifact-attempts.mjs';
import { loadPublicCiLocalRepro, localReproCommandMap } from './lib/public-ci-local-repro.mjs';

const KIND = 'justsearch-ci-evidence-digest.v1';
const RUN_FIELDS = [
  'conclusion',
  'createdAt',
  'databaseId',
  'displayTitle',
  'event',
  'headBranch',
  'headSha',
  'name',
  'number',
  'startedAt',
  'status',
  'updatedAt',
  'url',
  'workflowName',
].join(',');

const SHARD_POLICY = JSON.parse(fs.readFileSync(new URL('./unit-test-shard-policy.v1.json', import.meta.url), 'utf8'));
const UNIT_ARTIFACTS = new Map([
  ...SHARD_POLICY.lanes.map((lane) => [lane.jobName || `Unit tests (${lane.lane})`, lane.artifact]),
  ...Object.entries(SHARD_POLICY.historicalArtifacts || {}).map(([lane, artifact]) => [`Unit tests (${lane})`, artifact]),
]);
const WINDOWS_ARTIFACTS = new Map([
  ['Windows-native tests (contracts)', 'windows-native-contracts'],
]);
const BUILD_ATTRIBUTION_ARTIFACT = 'build-attribution';
const BUILD_ATTRIBUTION_KIND = 'justsearch-build-attribution.v1';

const LOCAL_REPRO_COMMANDS = localReproCommandMap(loadPublicCiLocalRepro());

function repoRootFromCwd() {
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, 'settings.gradle.kts'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
  }
}

function usage() {
  return [
    'Usage: node scripts/ci/report-ci-evidence-digest.mjs [options]',
    '',
    'Online mode:',
    '  --repo <owner/repo>       GitHub repository, default from origin',
    '  --run-id <id>             GitHub Actions run id; otherwise latest completed CI run',
    '  --workflow <name>         Workflow to select when --run-id is omitted (default: CI)',
    '  --limit <n>               Run-list limit for selection (default: 20)',
    '',
    'Fixture mode:',
    '  --run-json <path>         Run JSON object',
    '  --jobs-json <path>        Jobs JSON object with jobs[]',
    '  --artifacts-json <path>   Artifacts JSON object with artifacts[]',
    '  --cache-usage-json <path> Cache usage JSON object',
    '  --unit-attribution-dir <path> Directory containing unit-test-attribution.json files',
    '  --build-attribution <path> Build-attribution JSON file or directory',
    '',
    'Common:',
    '  --policy <path>           Workflow signal policy JSON',
    '  --json                    Print JSON',
    '  --md                      Print Markdown (default)',
    '  -h, --help',
  ].join('\n');
}

function parseArgs(argv) {
  const opts = {
    repo: null,
    runId: null,
    workflow: 'CI',
    limit: 20,
    policy: null,
    runJson: null,
    jobsJson: null,
    artifactsJson: null,
    cacheUsageJson: null,
    unitAttributionDir: null,
    buildAttribution: null,
    json: false,
    md: false,
    help: false,
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--repo' && argv[i + 1]) opts.repo = argv[++i];
    else if (arg === '--run-id' && argv[i + 1]) opts.runId = argv[++i];
    else if (arg === '--workflow' && argv[i + 1]) opts.workflow = argv[++i];
    else if (arg === '--limit' && argv[i + 1]) opts.limit = Number.parseInt(argv[++i], 10);
    else if (arg === '--policy' && argv[i + 1]) opts.policy = argv[++i];
    else if (arg === '--run-json' && argv[i + 1]) opts.runJson = argv[++i];
    else if (arg === '--jobs-json' && argv[i + 1]) opts.jobsJson = argv[++i];
    else if (arg === '--artifacts-json' && argv[i + 1]) opts.artifactsJson = argv[++i];
    else if (arg === '--cache-usage-json' && argv[i + 1]) opts.cacheUsageJson = argv[++i];
    else if (arg === '--unit-attribution-dir' && argv[i + 1]) opts.unitAttributionDir = argv[++i];
    else if (arg === '--build-attribution' && argv[i + 1]) opts.buildAttribution = argv[++i];
    else if (arg === '--json') opts.json = true;
    else if (arg === '--md') opts.md = true;
    else if (arg === '--help' || arg === '-h') opts.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }

  if (!Number.isInteger(opts.limit) || opts.limit <= 0) throw new Error('--limit must be a positive integer');
  return opts;
}

function loadJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function execGh(args) {
  return execFileSync('gh', args, {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });
}

function detectRepoSlug() {
  try {
    const url = execFileSync('git', ['remote', 'get-url', 'origin'], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
      windowsHide: true,
    }).trim();
    const match = url.match(/github\.com[:/](.+?)\/(.+?)(?:\.git)?$/i);
    if (match) return `${match[1]}/${match[2]}`;
  } catch {
    // Fall through to the public repo default.
  }
  return 'justsearch-app/justsearch';
}

function loadRun(opts) {
  if (opts.runJson) return loadJson(opts.runJson);
  if (opts.runId) {
    const args = ['run', 'view', String(opts.runId), '--json', RUN_FIELDS];
    if (opts.repo) args.push('--repo', opts.repo);
    return JSON.parse(execGh(args));
  }

  const args = ['run', 'list', '--workflow', opts.workflow, '--limit', String(opts.limit), '--json', RUN_FIELDS];
  if (opts.repo) args.push('--repo', opts.repo);
  const runs = JSON.parse(execGh(args));
  const run = runs.find((candidate) => candidate.status === 'completed') || runs[0];
  if (!run) throw new Error(`No GitHub Actions runs found for workflow ${opts.workflow}`);
  return run;
}

function loadJobs(run, opts) {
  if (opts.jobsJson) return loadJson(opts.jobsJson);
  if (opts.runJson) throw new Error('Fixture mode requires --jobs-json so the digest stays offline');
  const id = run.databaseId || opts.runId;
  if (!id) throw new Error('Cannot load jobs without a run id');
  const api = JSON.parse(execGh(['api', `repos/${opts.repo}/actions/runs/${id}/jobs?filter=latest&per_page=100`]));
  return { jobs: api.jobs || [] };
}

function loadArtifacts(run, opts) {
  if (opts.artifactsJson) return loadJson(opts.artifactsJson);
  if (opts.runJson) return { artifacts: [] };
  const id = run.databaseId || opts.runId;
  if (!id) return { artifacts: [] };
  return JSON.parse(execGh(['api', `repos/${opts.repo}/actions/runs/${id}/artifacts?per_page=100`]));
}

function loadCacheUsage(opts) {
  if (opts.cacheUsageJson) return loadJson(opts.cacheUsageJson);
  if (opts.runJson) return null;
  return JSON.parse(execGh(['api', `repos/${opts.repo}/actions/cache/usage`]));
}

function isoTime(value) {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

function durationSeconds(start, end) {
  if (!start || !end) return null;
  const startMs = new Date(start).getTime();
  const endMs = new Date(end).getTime();
  if (!Number.isFinite(startMs) || !Number.isFinite(endMs) || endMs < startMs) return null;
  return Math.round((endMs - startMs) / 1000);
}

function durationFromJob(job) {
  return durationSeconds(job.startedAt || job.started_at || job.started_at, job.completedAt || job.completed_at || job.completed_at);
}

function durationFromRun(run) {
  return durationSeconds(run.startedAt || run.createdAt, run.updatedAt);
}

function fmtSeconds(seconds) {
  if (seconds == null) return 'unknown';
  if (seconds >= 3600) {
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    return `${hours}h ${minutes}m`;
  }
  if (seconds >= 60) return `${Math.floor(seconds / 60)}m ${Math.round(seconds % 60)}s`;
  return `${seconds}s`;
}

function fmtBytes(bytes) {
  if (!Number.isFinite(bytes)) return 'unknown';
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${Math.round(value * 10) / 10} ${units[unit]}`;
}

function normalizeStep(step) {
  const conclusion = step.conclusion || step.status || null;
  return {
    name: step.name || String(step.number || 'unknown step'),
    status: step.status || null,
    conclusion,
    startedAt: isoTime(step.startedAt || step.started_at),
    completedAt: isoTime(step.completedAt || step.completed_at),
    durationSeconds: durationSeconds(step.startedAt || step.started_at, step.completedAt || step.completed_at),
  };
}

function normalizeJob(job, policyEntry, artifacts, splitRun) {
  const steps = (job.steps || []).map(normalizeStep);
  const failedSteps = steps.filter((step) => {
    const conclusion = String(step.conclusion || '').toLowerCase();
    const status = String(step.status || '').toLowerCase();
    return conclusion === 'failure' || conclusion === 'cancelled' || status === 'failure';
  });
  const name = job.name || 'unknown job';
  const expected = new Set(policyEntry?.requiredStatusChecks || []);
  const baseArtifact = name === 'Unit tests (app-ui)' && (splitRun || steps.some((s) => /Require every app-ui part/.test(s.name)))
    ? null : UNIT_ARTIFACTS.get(name) || null;
  const localCommand = SHARD_POLICY.lanes.find((lane) => lane.jobName === name)?.localCommand;
  const part = /^(?:Unit tests \(app-ui-(?:ui|services)\)|Windows-native tests \((?:node|contracts)\))$/.test(name);
  const partBase = baseArtifact || WINDOWS_ARTIFACTS.get(name);
  const selected = partBase ? selectJobArtifact(artifacts, partBase, job) : null;
  const partArtifact = part && partBase ? selected?.name || `${partBase}-attempt-${job.run_attempt || 1}` : null;
  const artifactName = baseArtifact && part ? partArtifact : baseArtifact;
  return {
    id: job.id || job.databaseId || null,
    name,
    status: job.status || null,
    conclusion: job.conclusion || null,
    startedAt: isoTime(job.startedAt || job.started_at),
    completedAt: isoTime(job.completedAt || job.completed_at),
    durationSeconds: durationFromJob(job),
    url: job.html_url || job.url || job.checkRunUrl || job.check_run_url || null,
    checkRunUrl: job.check_run_url || job.checkRunUrl || null,
    labels: Array.isArray(job.labels) ? job.labels.map(String) : [],
    runnerName: job.runner_name || job.runnerName || null,
    runnerGroupName: job.runner_group_name || job.runnerGroupName || null,
    factLane: (expected.has(name) || (part && expected.has(name.startsWith('Unit tests') ? 'Unit tests (app-ui)' : 'Windows-native tests'))) ? {
      workflow: policyEntry.name,
      class: policyEntry.class,
      owner: policyEntry.owner,
      failureDefault: policyEntry.failureDefault || null,
      required: true,
    } : {
      workflow: policyEntry?.name || null,
      class: 'unregistered-job',
      owner: null,
      failureDefault: 'advisory-unknown',
      required: false,
    },
    localReproduction: LOCAL_REPRO_COMMANDS.get(name) || (localCommand ? [localCommand] : []),
    unitAttributionArtifact: artifactName,
    partArtifact,
    runAttempt: selected ? Number(selected.name.split('-attempt-')[1] || job.run_attempt || 1) : null,
    workflowAttempt: job.run_attempt || null,
    failedSteps,
    steps,
  };
}

function criticalPathJob(jobs) {
  return jobs
    .filter((job) => job.durationSeconds != null)
    .sort((a, b) => b.durationSeconds - a.durationSeconds || a.name.localeCompare(b.name))[0] || null;
}

function primaryFailure(jobs) {
  const failed = jobs.find((job) => ['failure', 'cancelled', 'timed_out'].includes(String(job.conclusion).toLowerCase()));
  if (!failed) return null;
  return {
    job: failed.name,
    conclusion: failed.conclusion,
    steps: failed.failedSteps.map((step) => step.name),
    localReproduction: failed.localReproduction,
  };
}

function artifactList(artifactsDoc) {
  return (artifactsDoc?.artifacts || []).map((artifact) => ({
    id: artifact.id || null,
    name: artifact.name || 'unknown artifact',
    sizeBytes: artifact.size_in_bytes ?? artifact.sizeBytes ?? null,
    digest: artifact.digest || null,
    expired: Boolean(artifact.expired),
    createdAt: artifact.created_at || artifact.createdAt || null,
    expiresAt: artifact.expires_at || artifact.expiresAt || null,
    url: artifact.html_url || artifact.url || null,
    downloadUrl: artifact.archive_download_url || artifact.archiveDownloadUrl || null,
  }));
}

function walkJsonFiles(dir, out = []) {
  if (!dir || !fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const abs = path.join(dir, entry.name);
    if (entry.isDirectory()) walkJsonFiles(abs, out);
    else if (entry.isFile() && entry.name.endsWith('.json')) out.push(abs);
  }
  return out;
}

function inferArtifactName(filePath, report) {
  for (const part of filePath.split(path.sep)) {
    if (part.startsWith('unit-test-attribution-')) return part;
  }
  const label = report?.runner?.runnerLabel || '';
  const lane = String(label).split('/').pop();
  if (lane && lane !== label) return `unit-test-attribution-${lane}`;
  return null;
}

function loadUnitAttributionPayloads(root) {
  const entries = [];
  for (const filePath of walkJsonFiles(root)) {
    let report;
    try {
      report = loadJson(filePath);
    } catch {
      continue;
    }
    if (report?.kind !== 'justsearch-unit-test-attribution.v1') continue;
    const artifactName = inferArtifactName(filePath, report);
    const suites = Number(report?.totals?.suites || 0);
    entries.push({
      artifactName,
      file: filePath,
      complete: suites > 0,
      runner: report.runner || null,
      totals: report.totals || null,
      modules: Array.isArray(report.modules) ? report.modules.slice(0, 10) : [],
      slowestSuites: Array.isArray(report.slowestSuites) ? report.slowestSuites.slice(0, 10) : [],
    });
  }
  return entries;
}

function loadBuildAttributionPayloads(inputPath) {
  const warnings = [];
  if (!inputPath) return { payloads: [], warnings };
  const abs = path.resolve(inputPath);
  if (!fs.existsSync(abs)) {
    return { payloads: [], warnings: [`build attribution path not found: ${abs}`] };
  }
  const stat = fs.statSync(abs);
  const files = stat.isDirectory() ? walkJsonFiles(abs) : [abs];
  const payloads = [];
  for (const filePath of files) {
    let report;
    try {
      report = loadJson(filePath);
    } catch (error) {
      warnings.push(`could not read build attribution ${filePath}: ${error.message}`);
      continue;
    }
    if (report?.kind !== BUILD_ATTRIBUTION_KIND) {
      warnings.push(`ignored build attribution ${filePath}: kind ${report?.kind || '<missing>'}`);
      continue;
    }
    payloads.push({ file: filePath, report });
  }
  return { payloads, warnings };
}

function summarizeUnitAttribution(jobs, artifacts, unitPayloads) {
  const artifactByName = new Map(artifacts.map((artifact) => [artifact.name, artifact]));
  const payloadsByArtifact = new Map();
  for (const payload of unitPayloads) {
    if (payload.artifactName) payloadsByArtifact.set(payload.artifactName, payload);
  }

  return jobs
    .filter((job) => job.unitAttributionArtifact)
    .map((job) => {
      const artifact = artifactByName.get(job.unitAttributionArtifact) || null;
      const payload = payloadsByArtifact.get(job.unitAttributionArtifact) || null;
      return {
        job: job.name,
        artifactName: job.unitAttributionArtifact,
        artifactPresent: Boolean(artifact),
        artifact,
        payloadPresent: Boolean(payload),
        complete: Boolean(payload?.complete),
        totals: payload?.totals || null,
        runner: payload?.runner || null,
        warning: payload && !payload.complete
          ? 'unit attribution payload has zero suites; use failed step/log context as primary evidence'
          : null,
      };
    });
}

function summarizeBuildEvidence(jobs, artifacts, buildPayloads) {
  const build = jobs.find((job) => job.name === 'Build (no model blobs)') || null;
  if (!build) return null;
  const artifactByName = new Map(artifacts.map((artifact) => [artifact.name, artifact]));
  const artifact = artifactByName.get(BUILD_ATTRIBUTION_ARTIFACT) || null;
  const payload = buildPayloads[0]?.report || null;
  const evidenceSteps = build.steps.filter((step) => /assemble|web|vite|build/i.test(step.name));
  return {
    job: build.name,
    conclusion: build.conclusion,
    durationSeconds: build.durationSeconds,
    runnerLabels: build.labels,
    runnerName: build.runnerName,
    artifactName: BUILD_ATTRIBUTION_ARTIFACT,
    artifactPresent: Boolean(artifact),
    artifact,
    payloadPresent: Boolean(payload),
    payload: payload ? {
      file: buildPayloads[0].file,
      exitCode: payload.exitCode,
      success: payload.success,
      durationMillis: payload.durationMillis,
      taskEvidencePresent: payload.taskEvidencePresent,
      requestedGradleTasks: payload.requestedGradleTasks || [],
      totals: payload.totals || null,
      phaseGroups: Array.isArray(payload.phaseGroups) ? payload.phaseGroups : [],
      slowestTasks: Array.isArray(payload.slowestTasks) ? payload.slowestTasks.slice(0, 10) : [],
      warnings: Array.isArray(payload.warnings) ? payload.warnings : [],
    } : null,
    evidenceSteps: evidenceSteps.map((step) => ({
      name: step.name,
      conclusion: step.conclusion,
      durationSeconds: step.durationSeconds,
    })),
    limitation: payload
      ? 'build-attribution payload is advisory; Gradle assemble remains the pass/fail authority'
      : 'v1 uses job/step timing only unless a local build-attribution payload is supplied',
  };
}

function normalizeCacheUsage(cacheUsage) {
  if (!cacheUsage) return null;
  return {
    activeCachesSizeBytes: cacheUsage.active_caches_size_in_bytes ?? cacheUsage.activeCachesSizeBytes ?? null,
    activeCachesCount: cacheUsage.active_caches_count ?? cacheUsage.activeCachesCount ?? null,
  };
}

function warningsFor(jobs, unitAttribution, buildEvidence, payloadWarnings = []) {
  const warnings = [...payloadWarnings];
  for (const job of jobs) {
    if (!job.factLane.required) warnings.push(`job "${job.name}" is not registered as a required public CI fact lane`);
  }
  for (const unit of unitAttribution) {
    if (!unit.artifactPresent) warnings.push(`${unit.job} has no ${unit.artifactName} artifact listed`);
    if (unit.warning) warnings.push(`${unit.job}: ${unit.warning}`);
  }
  if (buildEvidence?.payload?.warnings?.length) {
    for (const warning of buildEvidence.payload.warnings) warnings.push(`${buildEvidence.job}: ${warning}`);
  }
  return warnings;
}

export function buildDigest({
  policy,
  run,
  jobsDoc,
  artifactsDoc = { artifacts: [] },
  cacheUsage = null,
  unitPayloads = [],
  buildPayloads = [],
  payloadWarnings = [],
}) {
  const ciPolicy = (policy.workflows || []).find((workflow) => workflow.name === 'CI') || null;
  const artifacts = artifactList(artifactsDoc);
  const splitRun = (jobsDoc?.jobs || []).some((job) => job.name === 'Unit tests (app-ui-ui)' || job.name === 'Unit tests (app-ui-services)');
  const jobs = (jobsDoc?.jobs || []).map((job) => normalizeJob(job, ciPolicy, artifacts, splitRun));
  const unitAttribution = summarizeUnitAttribution(jobs, artifacts, unitPayloads);
  const buildEvidence = summarizeBuildEvidence(jobs, artifacts, buildPayloads);
  const partEvidence = jobs.filter((job) => job.partArtifact).map((job) => ({
    job: job.name, attempt: job.runAttempt, artifactName: job.partArtifact,
    artifactPresent: artifacts.some((a) => a.name === job.partArtifact && !a.expired),
  }));
  const critical = criticalPathJob(jobs);
  const cache = normalizeCacheUsage(cacheUsage);
  return {
    kind: KIND,
    generatedAt: new Date().toISOString(),
    run: {
      databaseId: run.databaseId || null,
      number: run.number || null,
      workflowName: run.workflowName || run.name || null,
      title: run.displayTitle || null,
      event: run.event || null,
      branch: run.headBranch || null,
      sha: run.headSha || null,
      status: run.status || null,
      conclusion: run.conclusion || null,
      createdAt: isoTime(run.createdAt),
      startedAt: isoTime(run.startedAt),
      updatedAt: isoTime(run.updatedAt),
      durationSeconds: durationFromRun(run),
      url: run.url || null,
    },
    policy: ciPolicy ? {
      workflow: ciPolicy.name,
      class: ciPolicy.class,
      owner: ciPolicy.owner,
      requiredStatusChecks: ciPolicy.requiredStatusChecks || [],
    } : null,
    primaryFailure: primaryFailure(jobs),
    criticalPathJob: critical ? {
      name: critical.name,
      conclusion: critical.conclusion,
      durationSeconds: critical.durationSeconds,
      url: critical.url,
    } : null,
    jobs,
    artifacts,
    unitAttribution,
    partEvidence,
    buildEvidence,
    cacheUsage: cache,
    warnings: [
      ...warningsFor(jobs, unitAttribution, buildEvidence, payloadWarnings),
      ...partEvidence.filter((p) => !p.artifactPresent).map((p) => `${p.job} has no ${p.artifactName} artifact listed`),
    ],
  };
}

function mdLink(label, url) {
  return url ? `[${label}](${url})` : label;
}

function oneLine(value) {
  return String(value || '').replaceAll('|', '\\|').replace(/\s+/g, ' ').trim();
}

function commandSummary(commands) {
  if (!commands || commands.length === 0) return 'see workflow step';
  if (commands.length === 1) return `\`${commands[0]}\``;
  return `${commands.length} commands`;
}

export function renderMarkdown(report) {
  const run = report.run;
  const cache = report.cacheUsage;
  const lines = [
    '# CI Evidence Digest',
    '',
    `Generated: ${report.generatedAt}`,
    '',
    `Run: ${mdLink(String(run.databaseId || run.number || 'unknown'), run.url)} - ${run.conclusion || run.status || 'unknown'} (${run.event || 'unknown'} on ${run.branch || 'unknown branch'})`,
    `Commit: ${run.sha || 'unknown'}`,
    `Duration: ${fmtSeconds(run.durationSeconds)}`,
    `Critical path: ${report.criticalPathJob ? `${report.criticalPathJob.name} (${fmtSeconds(report.criticalPathJob.durationSeconds)})` : 'unknown'}`,
    `Primary failure: ${report.primaryFailure ? `${report.primaryFailure.job} (${report.primaryFailure.steps.join(', ') || report.primaryFailure.conclusion})` : 'none'}`,
    `Cache footprint: ${cache ? `${fmtBytes(cache.activeCachesSizeBytes)} across ${cache.activeCachesCount ?? 'unknown'} caches` : 'not available'}`,
    '',
    '## Fact Lanes',
    '',
    '| Job | Result | Duration | Policy | Runner | Failed Steps | Reproduce |',
    '|---|---|---:|---|---|---|---|',
  ];

  for (const job of report.jobs) {
    const runner = [...job.labels, job.runnerName].filter(Boolean).join(', ') || 'unknown';
    const failed = job.failedSteps.map((step) => step.name).join('<br>') || '';
    lines.push(
      `| ${mdLink(oneLine(job.name), job.url)} | ${job.conclusion || job.status || 'unknown'} | ${fmtSeconds(job.durationSeconds)} | ${job.factLane.required ? oneLine(job.factLane.owner) : 'advisory/unknown'} | ${oneLine(runner)} | ${oneLine(failed)} | ${oneLine(commandSummary(job.localReproduction))} |`
    );
  }

  lines.push('', '## Unit Attribution', '');
  if (report.unitAttribution.length === 0) {
    lines.push('No unit attribution lanes were found in the job list.');
  } else {
    lines.push('| Job | Artifact | Payload | Totals | Runner Image |', '|---|---|---|---|---|');
    for (const unit of report.unitAttribution) {
      const artifact = unit.artifactPresent ? mdLink(unit.artifactName, unit.artifact?.downloadUrl || unit.artifact?.url) : `${unit.artifactName} missing`;
      const totals = unit.totals
        ? `${unit.totals.tests || 0} tests, ${unit.totals.suites || 0} suites, ${unit.totals.failures || 0} failures, ${unit.totals.errors || 0} errors`
        : 'not downloaded';
      const runner = unit.runner
        ? `${unit.runner.runnerLabel || 'unknown'} / ${unit.runner.imageOs || 'unknown image'} ${unit.runner.imageVersion || ''}`.trim()
        : 'not available';
      lines.push(`| ${unit.job} | ${artifact} | ${unit.payloadPresent ? (unit.complete ? 'complete' : 'zero-suite') : 'not provided'} | ${totals} | ${runner} |`);
    }
  }

  if (report.partEvidence.length) {
    lines.push('', '## Part Evidence', '', '| Job | Attempt | Artifact |', '|---|---:|---|');
    for (const part of report.partEvidence) {
      lines.push(`| ${part.job} | ${part.attempt ?? 'unknown'} | ${part.artifactName}${part.artifactPresent ? '' : ' missing'} |`);
    }
  }
  lines.push('', '## Build Evidence', '');
  if (report.buildEvidence) {
    lines.push(`Build job: ${report.buildEvidence.conclusion || 'unknown'}, ${fmtSeconds(report.buildEvidence.durationSeconds)}.`);
    lines.push(`Artifact: ${report.buildEvidence.artifactPresent ? mdLink(report.buildEvidence.artifactName, report.buildEvidence.artifact?.downloadUrl || report.buildEvidence.artifact?.url) : 'not listed'}.`);
    lines.push(`Payload: ${report.buildEvidence.payloadPresent ? 'provided locally' : 'not downloaded/provided'}.`);
    lines.push('');
    lines.push('| Step | Result | Duration |', '|---|---|---:|');
    for (const step of report.buildEvidence.evidenceSteps) {
      lines.push(`| ${oneLine(step.name)} | ${step.conclusion || 'unknown'} | ${fmtSeconds(step.durationSeconds)} |`);
    }
    if (report.buildEvidence.payload?.phaseGroups?.length) {
      lines.push('', '| Build Phase | Tasks | Failed | Task Time |', '|---|---:|---:|---:|');
      for (const group of report.buildEvidence.payload.phaseGroups) {
        lines.push(`| ${oneLine(group.name)} | ${group.tasks} | ${group.failed} | ${fmtSeconds(Math.round((group.durationMillis || 0) / 1000))} |`);
      }
    }
    lines.push('', `Limit: ${report.buildEvidence.limitation}`);
  } else {
    lines.push('Build job not found.');
  }

  lines.push('', '## Local Reproduction', '');
  for (const job of report.jobs.filter((entry) => entry.localReproduction.length > 0)) {
    lines.push(`### ${job.name}`, '');
    for (const command of job.localReproduction) lines.push(`- \`${command}\``);
    lines.push('');
  }

  if (report.warnings.length > 0) {
    lines.push('## Warnings', '');
    for (const warning of report.warnings) lines.push(`- ${warning}`);
    lines.push('');
  }

  return `${lines.join('\n')}\n`;
}

function main() {
  try {
    const opts = parseArgs(process.argv.slice(2));
    if (opts.help) {
      console.log(usage());
      return;
    }
    const repoRoot = repoRootFromCwd();
    opts.repo ??= detectRepoSlug();
    opts.policy ??= path.join(repoRoot, 'scripts', 'ci', 'workflow-signal-policy.v1.json');

    const policy = loadJson(opts.policy);
    const run = loadRun(opts);
    const jobsDoc = loadJobs(run, opts);
    const artifactsDoc = loadArtifacts(run, opts);
    const cacheUsage = loadCacheUsage(opts);
    const unitPayloads = opts.unitAttributionDir
      ? loadUnitAttributionPayloads(path.resolve(opts.unitAttributionDir))
      : [];
    const buildAttribution = loadBuildAttributionPayloads(opts.buildAttribution);
    const report = buildDigest({
      policy,
      run,
      jobsDoc,
      artifactsDoc,
      cacheUsage,
      unitPayloads,
      buildPayloads: buildAttribution.payloads,
      payloadWarnings: buildAttribution.warnings,
    });

    if (opts.json) process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
    else process.stdout.write(renderMarkdown(report));
  } catch (error) {
    console.error(`report-ci-evidence-digest: ${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
