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
  assert.equal(job.if, "github.event_name != 'merge_group'", 'lifecycle coverage runs outside the merge queue');
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
test('both installed Engine tiers always collect and upload additional child logs', () => {
  for (const id of ['integration-tests', 'lifecycle-integration-tests']) {
    const job = workflowJobs(workflow).find((entry) => entry.id === id);
    const collection = job.runs.find((run) => run.text === 'node scripts/ci/collect-e2e-child-logs.mjs');
    assert.equal(collection?.step?.if, 'always()', id);
    assert.match(job.text, /name: Upload E2E child process logs\n        if: always\(\)/);
    assert.match(job.text, /path: build\/ci\/e2e-child-logs\/\*\*/);
    assert.match(job.text, /retention-days: 3/);
  }
});

test('CI does full required work outside the queue and advisory work only on dispatch', () => {
  const jobs = load(workflow).jobs;
  const skipQueue = "github.event_name != 'merge_group'";
  for (const id of ['jseval-suite', 'license-and-notices', 'app-ui-ui', 'app-ui-services',
    'windows-native-node', 'windows-native-contracts', 'lifecycle-integration-tests']) {
    assert.equal(jobs[id].if, skipQueue, id);
  }
  for (const id of ['app-ui', 'windows-native-tests']) {
    assert.equal(jobs[id].if, `always() && ${skipQueue}`, id);
  }
  for (const id of ['public-claims', 'build', 'secret-scan']) assert.equal(jobs[id].if, undefined, id);
  for (const id of ['measured-axe', 'integration-tests']) {
    assert.equal(jobs[id].if, "github.event_name == 'workflow_dispatch'", id);
  }
  assert.equal(jobs['ci-walltime'].if, "always() && github.event_name == 'workflow_dispatch'");
  assert.ok(jobs['ci-walltime'].needs.includes('integration-tests'));
  const originalConditions = {
    'Report unit-test attribution': 'always()',
    'Report unit-test advisory budget': 'always()',
    'Upload unit-test attribution': 'always()',
    'Linux shell build dependencies': "runner.os == 'Linux'",
    'Stage Linux process-lifetime helper': "runner.os == 'Linux'",
    'Build the supervisor conformance driver': "runner.os == 'Windows'",
    'Supervisor conformance (Tauri adapter)': "runner.os == 'Windows'",
  };
  for (const id of ['unit-tests', 'shell-rust-tests']) {
    assert.equal(jobs[id].if, undefined, `${id}: job must expand its matrix check names on the queue`);
    assert.ok(jobs[id].strategy.matrix);
    for (const step of jobs[id].steps) {
      const original = originalConditions[step.name] ? `${originalConditions[step.name]} && ` : '';
      assert.equal(step.if, original + skipQueue, `${id}/${step.name}: no work on the queue`);
    }
  }
});
