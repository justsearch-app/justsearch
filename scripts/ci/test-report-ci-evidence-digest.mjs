#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildDigest, renderMarkdown } from './report-ci-evidence-digest.mjs';

const scriptPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'report-ci-evidence-digest.mjs');

const policy = {
  workflows: [
    {
      name: 'CI',
      class: 'public-hosted-fact-lanes',
      owner: 'ADR-0044 / public hosted CI fact lanes',
      failureDefault: 'product-regression',
      requiredStatusChecks: [
        'Public claims',
        'License and notices',
        'Build (no model blobs)',
        'Unit tests (app-ui)',
        'Unit tests (search-worker)',
        'Unit tests (platform-contracts)',
        'Secret scan',
      ],
    },
  ],
};

const greenRun = {
  databaseId: 1001,
  number: 77,
  workflowName: 'CI',
  displayTitle: 'green run',
  event: 'pull_request',
  headBranch: 'feature/ci-digest',
  headSha: 'abc123',
  status: 'completed',
  conclusion: 'success',
  createdAt: '2026-06-29T09:00:00Z',
  startedAt: '2026-06-29T09:01:00Z',
  updatedAt: '2026-06-29T09:21:00Z',
  url: 'https://example.test/runs/1001',
};

function step(name, conclusion, startMinute, endMinute) {
  return {
    name,
    status: 'completed',
    conclusion,
    started_at: `2026-06-29T09:${String(startMinute).padStart(2, '0')}:00Z`,
    completed_at: `2026-06-29T09:${String(endMinute).padStart(2, '0')}:00Z`,
  };
}

let nextJobId = 1;

function job(name, conclusion, startMinute, endMinute, steps = [], labels = ['windows-latest']) {
  return {
    id: nextJobId++,
    name,
    status: 'completed',
    conclusion,
    started_at: `2026-06-29T09:${String(startMinute).padStart(2, '0')}:00Z`,
    completed_at: `2026-06-29T09:${String(endMinute).padStart(2, '0')}:00Z`,
    html_url: `https://example.test/jobs/${encodeURIComponent(name)}`,
    check_run_url: `https://api.example.test/checks/${encodeURIComponent(name)}`,
    labels,
    runner_name: `runner-${name}`,
    steps,
  };
}

function unitPayload(label, suites, tests = 12) {
  return {
    artifactName: `unit-test-attribution-${label}`,
    file: `fixture/${label}/build/ci/unit-test-attribution.json`,
    complete: suites > 0,
    runner: {
      runnerLabel: `windows-latest/${label}`,
      runnerOs: 'Windows',
      runnerName: `runner-${label}`,
      imageOs: 'windows-2025-vs2026',
      imageVersion: '20260622.153.1',
    },
    totals: {
      suites,
      tests,
      skipped: 0,
      failures: 0,
      errors: 0,
      timeSeconds: 42,
    },
    modules: [],
    slowestSuites: [],
  };
}

function buildPayload() {
  return {
    kind: 'justsearch-build-attribution.v1',
    command: ['./gradlew.bat', 'assemble', '-PskipWebBuild=false', '--console=plain'],
    commandLine: './gradlew.bat assemble -PskipWebBuild=false --console=plain',
    requestedGradleTasks: ['assemble'],
    gradleProperties: [{ name: 'skipWebBuild', value: 'false' }],
    exitCode: 0,
    success: true,
    durationMillis: 420000,
    taskEvidencePresent: true,
    totals: {
      tasks: 3,
      skipped: 0,
      upToDate: 1,
      fromCache: 0,
      failed: 0,
      durationMillis: 12000,
    },
    phaseGroups: [
      {
        id: 'web-dependency-install',
        name: 'Web dependency install',
        tasks: 1,
        skipped: 0,
        upToDate: 0,
        fromCache: 0,
        failed: 0,
        durationMillis: 10000,
      },
    ],
    slowestTasks: [],
    warnings: [],
  };
}

function artifacts(names) {
  return {
    artifacts: names.map((name, index) => ({
      id: index + 1,
      name,
      size_in_bytes: 1000 + index,
      digest: `sha256:${index}`,
      expired: false,
      archive_download_url: `https://example.test/artifacts/${name}.zip`,
    })),
  };
}

function withTempRoot(fn) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-ci-digest-'));
  try {
    return fn(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

{
  const jobsDoc = {
    jobs: [
      job('Public claims', 'success', 1, 3, [step('Workflow trigger policy guard', 'success', 1, 2)], ['ubuntu-latest']),
      job('License and notices', 'success', 2, 7, [step('NOTICE / THIRD_PARTY_NOTICES are in sync', 'success', 6, 7)]),
      job('Build (no model blobs)', 'success', 2, 10, [step('Assemble without model blobs', 'success', 3, 10)]),
      job('Unit tests (app-ui)', 'success', 3, 17, [step('Unit tests with model-dependent tests self-skipping', 'success', 4, 16)]),
      job('Unit tests (search-worker)', 'success', 3, 15, [step('Unit tests with model-dependent tests self-skipping', 'success', 4, 14)]),
      job('Unit tests (platform-contracts)', 'success', 3, 12, [step('Unit tests with model-dependent tests self-skipping', 'success', 4, 11)]),
      job('Secret scan', 'success', 1, 2, [step('Gitleaks', 'success', 1, 2)], ['ubuntu-latest']),
    ],
  };
  const report = buildDigest({
    policy,
    run: greenRun,
    jobsDoc,
    artifactsDoc: artifacts([
      'build-attribution',
      'unit-test-attribution-app-ui',
      'unit-test-attribution-search-worker',
      'unit-test-attribution-platform-contracts',
    ]),
    cacheUsage: { active_caches_size_in_bytes: 543518112, active_caches_count: 14 },
    unitPayloads: [unitPayload('app-ui', 15), unitPayload('search-worker', 12), unitPayload('platform-contracts', 8)],
  });

  assert.equal(report.kind, 'justsearch-ci-evidence-digest.v1');
  assert.equal(report.run.durationSeconds, 1200);
  assert.equal(report.criticalPathJob.name, 'Unit tests (app-ui)');
  assert.equal(report.primaryFailure, null);
  assert.equal(report.cacheUsage.activeCachesCount, 14);
  assert.equal(report.jobs.find((entry) => entry.name === 'Public claims').factLane.required, true);
  assert.equal(report.unitAttribution.length, 3);
  assert.equal(report.unitAttribution[0].complete, true);
  assert.equal(report.buildEvidence.evidenceSteps[0].name, 'Assemble without model blobs');
  assert.equal(report.buildEvidence.artifactPresent, true);
  assert.equal(report.buildEvidence.payloadPresent, false);
  const md = renderMarkdown(report);
  assert.match(md, /CI Evidence Digest/);
  assert.match(md, /Unit tests \(app-ui\)/);
  assert.match(md, /518\.3 MB across 14 caches/);
  assert.match(md, /build-attribution/);
}

{
  const failedRun = { ...greenRun, databaseId: 1002, conclusion: 'failure', url: 'https://example.test/runs/1002' };
  const report = buildDigest({
    policy,
    run: failedRun,
    jobsDoc: {
      jobs: [
        job('Public claims', 'success', 1, 3, [step('Workflow trigger policy guard', 'success', 1, 2)], ['ubuntu-latest']),
        job('Unit tests (app-ui)', 'failure', 3, 18, [
          step('Unit tests with model-dependent tests self-skipping', 'failure', 4, 17),
          step('Report unit-test attribution', 'success', 17, 18),
        ]),
        job('Experimental advisory lane', 'failure', 3, 4, [step('Try something', 'failure', 3, 4)]),
      ],
    },
    artifactsDoc: artifacts(['unit-test-attribution-app-ui']),
    cacheUsage: { active_caches_size_in_bytes: 1024, active_caches_count: 1 },
    unitPayloads: [unitPayload('app-ui', 0, 0)],
  });

  assert.equal(report.primaryFailure.job, 'Unit tests (app-ui)');
  assert.deepEqual(report.primaryFailure.steps, ['Unit tests with model-dependent tests self-skipping']);
  assert.equal(report.unitAttribution[0].payloadPresent, true);
  assert.equal(report.unitAttribution[0].complete, false);
  assert.match(report.warnings.join('\n'), /zero suites/);
  assert.match(report.warnings.join('\n'), /Experimental advisory lane/);
  assert.equal(report.jobs.find((entry) => entry.name === 'Experimental advisory lane').factLane.required, false);
}

{
  const report = buildDigest({
    policy,
    run: greenRun,
    jobsDoc: {
      jobs: [
        job('Build (no model blobs)', 'success', 2, 10, [step('Assemble without model blobs', 'success', 3, 10)]),
      ],
    },
    artifactsDoc: artifacts(['build-attribution']),
    cacheUsage: null,
    buildPayloads: [{ file: 'fixtures/build-attribution.json', report: buildPayload() }],
  });

  assert.equal(report.buildEvidence.artifactPresent, true);
  assert.equal(report.buildEvidence.payloadPresent, true);
  assert.equal(report.buildEvidence.payload.requestedGradleTasks[0], 'assemble');
  assert.equal(report.buildEvidence.payload.phaseGroups[0].name, 'Web dependency install');
  const md = renderMarkdown(report);
  assert.match(md, /provided locally/);
  assert.match(md, /Web dependency install/);
}

{
  const report = buildDigest({
    policy,
    run: greenRun,
    jobsDoc: { jobs: [job('Unit tests (search-worker)', 'success', 3, 8)] },
    artifactsDoc: artifacts(['unit-test-attribution-search-worker']),
    cacheUsage: null,
    unitPayloads: [],
  });
  assert.equal(report.unitAttribution[0].artifactPresent, true);
  assert.equal(report.unitAttribution[0].payloadPresent, false);
  assert.equal(report.unitAttribution[0].totals, null);
  assert.doesNotMatch(report.warnings.join('\n'), /zero suites/);
}

withTempRoot((root) => {
  const fixture = path.join(root, 'fixtures');
  const unitDir = path.join(fixture, 'unit-test-attribution-app-ui', 'build', 'ci');
  const runJson = path.join(fixture, 'run.json');
  const jobsJson = path.join(fixture, 'jobs.json');
  const artifactsJson = path.join(fixture, 'artifacts.json');
  const cacheJson = path.join(fixture, 'cache.json');
  const policyJson = path.join(fixture, 'policy.json');
  const buildAttributionJson = path.join(fixture, 'build-attribution.json');
  writeJson(runJson, greenRun);
  writeJson(jobsJson, { jobs: [job('Unit tests (app-ui)', 'success', 3, 9)] });
  writeJson(artifactsJson, artifacts(['unit-test-attribution-app-ui']));
  writeJson(cacheJson, { active_caches_size_in_bytes: 2048, active_caches_count: 2 });
  writeJson(policyJson, policy);
  writeJson(buildAttributionJson, buildPayload());
  writeJson(path.join(unitDir, 'unit-test-attribution.json'), {
    kind: 'justsearch-unit-test-attribution.v1',
    runner: {
      runnerLabel: 'windows-latest/app-ui',
      imageOs: 'windows-2025-vs2026',
      imageVersion: '20260622.153.1',
    },
    totals: { suites: 2, tests: 9, skipped: 0, failures: 0, errors: 0, timeSeconds: 3.5 },
    modules: [],
    slowestSuites: [],
  });

  const res = spawnSync(process.execPath, [
    scriptPath,
    '--run-json', runJson,
    '--jobs-json', jobsJson,
    '--artifacts-json', artifactsJson,
    '--cache-usage-json', cacheJson,
    '--unit-attribution-dir', fixture,
    '--build-attribution', buildAttributionJson,
    '--policy', policyJson,
    '--json',
  ], { encoding: 'utf8' });

  assert.equal(res.status, 0, res.stderr);
  const report = JSON.parse(res.stdout);
  assert.equal(report.kind, 'justsearch-ci-evidence-digest.v1');
  assert.equal(report.unitAttribution[0].totals.tests, 9);
  assert.equal(report.unitAttribution[0].runner.imageVersion, '20260622.153.1');
  assert.equal(report.buildEvidence, null);
});

withTempRoot((root) => {
  const runJson = path.join(root, 'run.json');
  const jobsJson = path.join(root, 'jobs.json');
  const policyJson = path.join(root, 'policy.json');
  writeJson(runJson, greenRun);
  writeJson(jobsJson, { jobs: [job('Public claims', 'success', 1, 2)] });
  writeJson(policyJson, policy);

  const res = spawnSync(process.execPath, [
    scriptPath,
    '--run-json', runJson,
    '--jobs-json', jobsJson,
    '--policy', policyJson,
    '--json',
  ], { encoding: 'utf8', env: { ...process.env, GH_TOKEN: '', GITHUB_TOKEN: '' } });

  assert.equal(res.status, 0, res.stderr);
  const report = JSON.parse(res.stdout);
  assert.equal(report.artifacts.length, 0);
  assert.equal(report.cacheUsage, null);
});

withTempRoot((root) => {
  const runJson = path.join(root, 'run.json');
  const jobsJson = path.join(root, 'jobs.json');
  const policyJson = path.join(root, 'policy.json');
  const badBuild = path.join(root, 'build-attribution.json');
  writeJson(runJson, greenRun);
  writeJson(jobsJson, { jobs: [job('Build (no model blobs)', 'success', 1, 2)] });
  writeJson(policyJson, policy);
  writeJson(badBuild, { kind: 'wrong-kind.v1' });

  const res = spawnSync(process.execPath, [
    scriptPath,
    '--run-json', runJson,
    '--jobs-json', jobsJson,
    '--policy', policyJson,
    '--build-attribution', badBuild,
    '--json',
  ], { encoding: 'utf8' });

  assert.equal(res.status, 0, res.stderr);
  const report = JSON.parse(res.stdout);
  assert.match(report.warnings.join('\n'), /ignored build attribution/);
  assert.equal(report.buildEvidence.payloadPresent, false);
});

console.log('test-report-ci-evidence-digest: PASS');

// Independently rerun parts retain their own attempt's evidence; an old attempt
// cannot fill a hole left by a failed or cancelled current attempt.
{
  const ui = { ...job('Unit tests (app-ui-ui)', 'success', 1, 3), run_attempt: 2 };
  const services = { ...job('Unit tests (app-ui-services)', 'success', 1, 4), run_attempt: 1 };
  const aggregate = job('Unit tests (app-ui)', 'success', 5, 6, [step('Require every app-ui part to succeed', 'success', 5, 6)]);
  const windows = { ...job('Windows-native tests (node)', 'failure', 1, 2), run_attempt: 2 };
  const report = buildDigest({ policy, run: greenRun, jobsDoc: { jobs: [ui, services, aggregate, windows] }, artifactsDoc: artifacts([
    'unit-test-attribution-app-ui-ui-attempt-1',
    'unit-test-attribution-app-ui-services-attempt-1',
    'windows-native-node-attempt-1',
  ]) });
  assert.equal(report.unitAttribution.length, 2, 'aggregate owns no JUnit payload');
  assert.equal(report.unitAttribution[0].artifactPresent, false, 'old attempt cannot mask missing UI evidence');
  assert.equal(report.unitAttribution[1].artifactPresent, true, 'unchanged services part retains attempt 1');
  assert.equal(report.partEvidence.find((p) => p.job === windows.name).artifactPresent, false);
  assert.ok(report.warnings.some((w) => w.includes('windows-native-node-attempt-2')));
  assert.ok(report.jobs.find((j) => j.name === ui.name).factLane.required);
}

// Hosted CP5: GitHub gives unchanged parts the current workflow attempt number
// while retaining their original execution timestamps and artifacts.
{
  const unchanged = { ...job('Unit tests (app-ui-services)', 'success', 1, 4), run_attempt: 3 };
  const old = { name: 'unit-test-attribution-app-ui-services-attempt-1', created_at: unchanged.completed_at };
  const report = buildDigest({ policy, run: greenRun, jobsDoc: { jobs: [unchanged] }, artifactsDoc: { artifacts: [old] } });
  assert.equal(report.unitAttribution[0].artifactPresent, true);
  assert.equal(report.partEvidence[0].attempt, 1);
  const rerun = { ...unchanged, started_at: '2026-07-01T01:00:00Z', completed_at: '2026-07-01T01:04:00Z' };
  const missing = buildDigest({ policy, run: greenRun, jobsDoc: { jobs: [rerun] }, artifactsDoc: { artifacts: [old] } });
  assert.equal(missing.unitAttribution[0].artifactPresent, false);
}
