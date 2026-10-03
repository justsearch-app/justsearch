import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
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
  assert.equal(job.if, undefined, 'lifecycle coverage must run on every CI trigger');
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
