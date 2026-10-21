import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { load } from 'js-yaml';
const read = (path) => readFileSync(new URL('../../' + path, import.meta.url), 'utf8');
const workflow = load(read('.github/workflows/ci.yml'));
const signal = JSON.parse(read('scripts/ci/workflow-signal-policy.v1.json'));
const required = ['cla-assistant', 'Public claims', 'License and notices', 'Build (no model blobs)', 'Unit tests (app-ui)', 'Unit tests (search-worker)', 'Unit tests (platform-contracts)', 'Secret scan', 'Windows-native tests', 'Shell crate tests (Rust)', 'jseval Python suite', 'Engine lifecycle tests (model-free)'];
test('all twelve required names have literal jobs or aggregates on every PR', () => {
  const declared = signal.workflows.flatMap((row) => row.requiredStatusChecks ?? []);
  assert.deepEqual([...declared].sort(), [...required].sort());
  const cla = load(read('.github/workflows/cla.yml'));
  const jobs = [...Object.entries(workflow.jobs), ...Object.entries(cla.jobs)];
  for (const name of required) {
    const matches = jobs.filter(([id, job]) => (job.name ?? id) === name);
    assert.equal(matches.length, 1, name);
    assert.equal(matches[0][1].strategy, undefined, name);
  }
  for (const [id, job] of jobs) assert.ok(!JSON.stringify(job).includes('merge_group'), id);
  assert.deepEqual(Object.keys(workflow.on).sort(), ['pull_request', 'push', 'workflow_dispatch']);
  assert.equal(signal.branchProtection.requireBranchesUpToDateBeforeMerging, false);
});
test('aggregates accept success/intentional skip and reject failures and cancellations', () => {
  for (const id of ['app-ui', 'windows-native-tests']) {
    const job = workflow.jobs[id];
    assert.equal(job.if, 'always()');
    const step = job.steps[0];
    const variables = Object.keys(step.env);
    for (const left of ['success', 'skipped', 'failure', 'cancelled']) {
      for (const right of ['success', 'skipped', 'failure', 'cancelled']) {
        const result = spawnSync('bash', ['-c', step.run], { env: { ...process.env, [variables[0]]: left, [variables[1]]: right } });
        assert.equal(result.status === 0, ['success', 'skipped'].includes(left) && ['success', 'skipped'].includes(right), `${id}: ${left}/${right}`);
      }
    }
  }
});
test('classifier failure blocks Secret scan; missing outputs cannot deselect lanes', () => {
  const scan = workflow.jobs['secret-scan'];
  assert.equal(scan.needs, 'changes');
  assert.equal(scan.if, 'always()');
  for (const value of ['success', 'failure', 'cancelled', 'skipped', '']) {
    const check = spawnSync('bash', ['-c', scan.steps[0].run], { env: { ...process.env, CHANGES_RESULT: value } });
    assert.equal(check.status === 0, value === 'success');
  }
  for (const [id, job] of Object.entries(workflow.jobs).filter(([, job]) => job.needs === 'changes' && job.if !== 'always()')) {
    assert.match(job.if, /^needs\.changes\.outputs\.[a-z]+ != 'false'$/, id);
  }
});
test('main keeps the running run and runs all slow tiers without masking failures', () => {
  assert.match(workflow.concurrency.group, /github.event_name == 'push'.*refs\/heads\/main.*'main'/);
  assert.equal(workflow.concurrency['cancel-in-progress'], "${{ !(github.event_name == 'push' && github.ref == 'refs/heads/main') }}");
  const mainOnly = "github.event_name == 'workflow_dispatch' || (github.event_name == 'push' && github.ref == 'refs/heads/main')";
  for (const id of ['integration-tests', 'measured-axe']) {
    assert.equal(workflow.jobs[id].if, mainOnly);
    assert.notEqual(workflow.jobs[id]['continue-on-error'], true);
  }
  for (const job of Object.values(workflow.jobs)) {
    for (const step of job.steps) {
      if (step.name?.startsWith('Supervisor conformance (')) assert.ok(step.if.includes(mainOnly), step.name);
    }
  }
  const runner = read('scripts/dev/run-dev-runner-tests.mjs');
  assert.match(runner, /JUSTSEARCH_PR_CI === 'true'.*test-dev-runner-stop-census\.mjs/);
});
test('the synchronize guard cannot execute PR code and has only its required permissions', () => {
  const guard = load(read('.github/workflows/auto-merge-guard.yml'));
  assert.deepEqual(guard.on, { pull_request_target: { types: ['synchronize'] } });
  assert.deepEqual(guard.permissions, { 'pull-requests': 'write', contents: 'read' });
  assert.ok(!read('.github/workflows/auto-merge-guard.yml').includes('actions/checkout'));
  assert.match(guard.jobs['disable-auto-merge'].steps[0].with.script, /disablePullRequestAutoMerge/);
});
