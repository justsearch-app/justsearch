import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { load } from 'js-yaml';
import { workflowJobs } from './check-governance-ci-coverage.mjs';

const read = (p) => fs.readFileSync(new URL('../../' + p, import.meta.url), 'utf8');
const workflow = read('.github/workflows/ci.yml');
const build = read('modules/system-tests/build.gradle.kts');
const signal = JSON.parse(read('scripts/ci/workflow-signal-policy.v1.json'));
const walltime = JSON.parse(read('scripts/ci/ci-walltime-policy.v1.json'));

test('model-free lifecycle task has a required hosted job and a matching explicit budget', () => {
  const job = workflowJobs(workflow).find((j) => j.runs.some((r) =>
    /(?:^|\s):modules:system-tests:lifecycleIntegrationTest(?:\s|$)/.test(r.text)));
  assert.ok(job, 'lifecycleIntegrationTest must be scheduled in hosted CI');
  assert.equal(job['runs-on'], 'windows-latest');
  assert.equal(job.if, "needs.changes.outputs.lifecycle != 'false'", 'lifecycle coverage uses fail-safe input selection');
  assert.notEqual(job['continue-on-error'], 'true');
  assert.ok(signal.workflows.find((w) => w.name === 'CI').requiredStatusChecks.includes(job.name));
  const command = job.runs.find((r) => r.text.includes(':modules:system-tests:lifecycleIntegrationTest'));
  assert.match(command.text, /-PincludeAiTests=false(?:\s|$)/);
  assert.equal(command.step?.if, undefined);
  assert.notEqual(command.step?.['continue-on-error'], 'true');
  const policy = walltime.lanes.find((l) => l.job === job.name);
  assert.ok(policy, 'new required job must have a walltime policy');
  assert.equal(policy.hardTimeoutSeconds, Number(job['timeout-minutes']) * 60);
  const lifecycleTask = build.slice(build.indexOf('tasks.register<Test>("lifecycleIntegrationTest")'), build.indexOf('// System test task'));
  const taskMinutes = Number(/timeout.set\(Duration.ofMinutes\((\d+)\)\)/.exec(lifecycleTask)?.[1]);
  assert.ok(taskMinutes > 0 && Number(job['timeout-minutes']) > taskMinutes);
  assert.match(lifecycleTask, /if \(!includeAiTests\) excludeTags\("ai"\)/);
  assert.match(lifecycleTask, /includeTestsMatching\("io.justsearch.systemtests.supervision.EngineLifecycleE2ETest"\)/);
  assert.ok(job.text.includes('build/test-results/lifecycleIntegrationTest/TEST-*.xml'));
});

test('fullTestSuite includes the separately owned lifecycle tier', () => {
  const aggregate = build.slice(build.indexOf('tasks.register("fullTestSuite")'));
  assert.match(aggregate, /dependsOn\([^\n]*tasks.named\("lifecycleIntegrationTest"\)/);
});

const uploadPolicy = JSON.parse(read('scripts/ci/e2e-upload-policy.v1.json'));
for (const expected of uploadPolicy.uploads) {
  test(`${expected.job}: preserve main upload paths and conditions`, () => {
    const jobs = load(workflow).jobs;
    const matches = jobs[expected.job].steps.filter((step) => step.name === expected.step);
    assert.equal(matches.length, 1, `${expected.job}: expected exactly one ${expected.step}`);
    const step = matches[0];
    assert.deepEqual({ uses: step.uses, condition: step.if,
      paths: step.with.path.trim().split('\n') },
    { uses: expected.uses, condition: expected.condition, paths: expected.paths },
    `${expected.job}: preserve uploads pinned from ${uploadPolicy.sourceRef}:${uploadPolicy.sourcePath}`);
  });
}

// Bounded child logs supplement the existing complete fixture artifacts.
// Evaluate a retention value for one job status. Only a literal day count or the
// `job.status == '<status>' && <days> || <days>` form is accepted, so another expression fails here.
function retentionFor(value, status) {
  if (Number.isInteger(value)) return value;
  const match = /^\$\{\{\s*job\.status\s*==\s*'(success|failure|cancelled)'\s*&&\s*(\d+)\s*\|\|\s*(\d+)\s*\}\}$/.exec(String(value));
  assert.ok(match, `unsupported retention expression: ${value}`);
  return status === match[1] ? Number(match[2]) : Number(match[3]);
}

test('both installed Engine tiers always collect and upload child logs, kept longer when the job failed', () => {
  const jobs = load(workflow).jobs;
  for (const id of ['integration-tests', 'lifecycle-integration-tests']) {
    const job = workflowJobs(workflow).find((entry) => entry.id === id);
    const collection = job.runs.find((run) => run.text === 'node scripts/ci/collect-e2e-child-logs.mjs');
    assert.equal(collection?.step?.if, 'always()', id);
    const steps = jobs[id].steps;
    const artifact = `${id}-child-logs-attempt-\${{ github.run_attempt }}`;
    const uploads = steps.filter((step) => step.uses?.startsWith('actions/upload-artifact@'));
    const matching = uploads.filter((step) => step.with?.name === artifact);
    assert.equal(matching.length, 1, `${id}: exactly one upload of ${artifact}`);
    const [upload] = matching;
    assert.equal(upload.if, 'always()', `${id}: child logs upload on every outcome`);
    assert.equal(upload.with.path, 'build/ci/e2e-child-logs/**', id);
    assert.equal(upload.with['if-no-files-found'], 'error', id);
    // Retention reads the job status when the step runs, so every other upload that can fail runs first.
    assert.equal(uploads.at(-1), upload, `${id}: child logs upload comes after the job's other uploads`);
    assert.equal(steps.at(-1), upload, `${id}: child logs upload is the job's final step, so no later step can fail after retention is chosen`);
    const collect = steps.findIndex((step) => step.run === 'node scripts/ci/collect-e2e-child-logs.mjs');
    assert.ok(collect >= 0 && steps.indexOf(upload) > collect, `${id}: upload follows collection`);
    for (const [status, days] of [['failure', 30], ['success', 3], ['cancelled', 3]]) {
      assert.equal(retentionFor(upload.with['retention-days'], status), days, `${id}: retention when ${status}`);
    }
  }
});

test('PRs scope fixed jobs and main runs every slow tier', () => {
  const jobs = load(workflow).jobs;
  const mainOnly = "github.event_name == 'workflow_dispatch' || (github.event_name == 'push' && github.ref == 'refs/heads/main')";
  for (const id of ['jseval-suite', 'license-and-notices', 'build', 'app-ui-ui', 'app-ui-ui-2', 'app-ui-services', 'app-ui-services-2', 'app-ui-services-3',
    'unit-tests-search-worker-1', 'unit-tests-search-worker-2', 'unit-tests-platform-contracts-1', 'unit-tests-platform-contracts-2', 'shell-rust-tests', 'shell-rust-tests-linux',
    'windows-native-node', 'windows-native-contracts', 'windows-native-contracts-2', 'lifecycle-integration-tests']) {
    assert.equal(jobs[id].needs, 'changes', id);
    assert.match(jobs[id].if, /^needs\.changes\.outputs\.[a-z]+ != 'false'$/, id);
    assert.ok(!jobs[id].strategy, `${id}: literal check names must report even when skipped`);
  }
  for (const id of ['app-ui', 'unit-tests-search-worker', 'unit-tests-platform-contracts', 'windows-native-tests', 'secret-scan', 'public-claims']) assert.equal(jobs[id].if, 'always()', id);
  for (const id of ['integration-tests', 'module-integration-tests']) assert.equal(jobs[id].if, mainOnly, id);
  assert.equal(jobs['measured-axe'].needs, 'changes');
  assert.equal(jobs['measured-axe'].if, "needs.changes.outputs.frontend != 'false'");
  assert.equal(jobs['measured-axe'].name, 'Measured axe');
  assert.notEqual(jobs['integration-tests']['continue-on-error'], true);
  assert.equal(jobs['ci-walltime'].if, `always() && (${mainOnly})`);
  assert.ok(jobs['ci-walltime'].needs.includes('integration-tests'));
  assert.ok(jobs['ci-walltime'].needs.includes('module-integration-tests'));
});
