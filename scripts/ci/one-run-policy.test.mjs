import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { load } from 'js-yaml';
const read = (path) => readFileSync(new URL('../../' + path, import.meta.url), 'utf8');
const workflow = load(read('.github/workflows/ci.yml'));
const signal = JSON.parse(read('scripts/ci/workflow-signal-policy.v1.json'));
const required = ['cla-assistant', 'Public claims', 'License and notices', 'Build (no model blobs)', 'Unit tests (app-ui)', 'Unit tests (search-worker)', 'Unit tests (platform-contracts)', 'Secret scan', 'Windows-native tests', 'Shell crate tests (Rust)', 'jseval Python suite', 'Engine lifecycle tests (model-free)', 'Measured axe'];
test('all thirteen required names have literal jobs or aggregates on every PR', () => {
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
  for (const id of ['app-ui', 'windows-native-tests', 'unit-tests-search-worker', 'unit-tests-platform-contracts']) {
    const job = workflow.jobs[id];
    assert.equal(job.if, 'always()');
    const step = job.steps[0];
    const variables = Object.keys(step.env);
    for (let index = 0; index < variables.length; index++) {
      for (const result of ['success', 'skipped', 'failure', 'cancelled']) {
        const env = Object.fromEntries(variables.map((variable, i) => [variable, i === index ? result : 'success']));
        const checked = spawnSync('bash', ['-c', step.run], { env: { ...process.env, ...env } });
        assert.equal(checked.status === 0, ['success', 'skipped'].includes(result), `${id}: ${variables[index]}/${result}`);
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
  for (const id of ['integration-tests', 'module-integration-tests']) {
    assert.equal(workflow.jobs[id].if, mainOnly);
    assert.notEqual(workflow.jobs[id]['continue-on-error'], true);
    assert.ok(!required.includes(workflow.jobs[id].name), `${id}: main-only checks must not be required`);
  }
  const axe = workflow.jobs['measured-axe'];
  assert.equal(axe.needs, 'changes');
  assert.equal(axe.if, "needs.changes.outputs.frontend != 'false'");
  assert.equal(axe.name, 'Measured axe');
  assert.equal(axe.strategy, workflow.jobs['jseval-suite'].strategy, 'unselected literal jobs report skipped');
  assert.equal(axe['continue-on-error'], undefined);
  const gate = axe.steps.find(step => step.name === 'Measured axe over the a11y baseline register');
  assert.equal(gate.if, undefined);
  assert.equal(gate['continue-on-error'], undefined);
  assert.match(gate.run, /set -o pipefail/);
  assert.match(gate.run, /python -m jseval ui-a11y-gate --output-dir build\/ci\/ui-a11y/);
  assert.ok(workflow.jobs['ci-walltime'].needs.includes('measured-axe'));
  const walltime = JSON.parse(read('scripts/ci/ci-walltime-policy.v1.json'));
  const budget = walltime.lanes.find(lane => lane.job === axe.name);
  assert.equal(budget?.requiredCheck, axe.name);
  assert.equal(budget.hardTimeoutSeconds, axe['timeout-minutes'] * 60);
  assert.ok(budget.advisoryBudgets.maxWallSeconds > 0);
  assert.ok(budget.advisoryBudgets.maxWallSeconds <= budget.hardTimeoutSeconds * 0.8);
  const node = workflow.jobs['windows-native-node'];
  assert.equal(node.if, "needs.changes.outputs.devrunner != 'false'");
  const conformance = node.steps.find(step => step.name === 'Supervisor conformance (dev-runner adapter)');
  assert.equal(conformance.if, undefined, 'dev-runner conformance follows its containing lane');
  for (const id of ['windows-native-contracts', 'windows-native-contracts-2']) {
    assert.ok(!workflow.jobs[id].steps.some(step => /Supervisor conformance/.test(step.name)));
  }
  const tauri = workflow.jobs['shell-rust-tests'].steps.find(step => step.name === 'Supervisor conformance (Tauri adapter)');
  assert.equal(tauri.if, undefined, 'the selected Rust lane exercises conformance on PRs');
  for (const job of Object.values(workflow.jobs)) for (const step of job.steps) {
    if (step.name?.startsWith('Supervisor conformance repeated')) assert.equal(step.if, `(${mainOnly})`);
  }
  const runner = read('scripts/dev/run-dev-runner-tests.mjs');
  assert.match(runner, /JUSTSEARCH_PR_CI === 'true'.*test-dev-runner-stop-census\.mjs/);
});
test('module integration tier runs all five tasks together and preserves failure reports', () => {
  const job = workflow.jobs['module-integration-tests'];
  assert.ok(job, 'module integration tier must be scheduled');
  assert.equal(job.name, 'Integration tests (module tier)');
  assert.equal(job['runs-on'], 'ubuntu-latest');
  assert.equal(job['timeout-minutes'], 20);
  assert.equal(job.needs, undefined, 'main-only tier must not depend on PR classification');
  const setup = job.steps.filter(step => /^(Checkout|Setup Java|Setup Gradle)$/.test(step.name));
  assert.deepEqual(setup, workflow.jobs.build.steps.filter(step => /^(Checkout|Setup Java|Setup Gradle)$/.test(step.name)));
  const modules = ['app-api', 'app-services', 'indexing', 'reranker', 'ui'];
  const runs = job.steps.filter(step => step.run?.includes(':integrationTest'));
  assert.equal(runs.length, 1);
  const step = runs[0];
  assert.equal(step.if, undefined);
  assert.notEqual(step['continue-on-error'], true);
  assert.equal(step.run.trim().split('\n').at(-1), `./gradlew ${modules.map(module => `:modules:${module}:integrationTest`).join(' ')} --continue -PskipWebBuild=true --console=plain`);
  const upload = job.steps.find(step => step.name === 'Upload module integration-test results');
  assert.equal(upload?.uses, 'actions/upload-artifact@v7');
  assert.equal(upload.if, 'always()');
  assert.deepEqual(upload.with.path.trim().split('\n'), modules.flatMap(module => [
    `modules/${module}/build/test-results/integrationTest/TEST-*.xml`,
    `modules/${module}/build/reports/tests/integrationTest/**`,
  ]));
  assert.ok(workflow.jobs['ci-walltime'].needs.includes('module-integration-tests'));
});
test('the synchronize guard cannot execute PR code and has only its required permissions', () => {
  const guard = load(read('.github/workflows/auto-merge-guard.yml'));
  assert.deepEqual(guard.on, { pull_request_target: { types: ['synchronize'] } });
  assert.deepEqual(guard.permissions, {});
  assert.deepEqual(guard.jobs['disable-auto-merge'].permissions, { 'pull-requests': 'write', contents: 'write' });
  const [checkout, script] = guard.jobs['disable-auto-merge'].steps;
  assert.equal(checkout.uses, 'actions/checkout@v7');
  assert.equal(checkout.with.ref, '${{ github.event.pull_request.base.sha }}');
  assert.equal(checkout.with['persist-credentials'], false);
  assert.match(script.with.script, /require\('\.\/scripts\/ci\/auto-merge-guard\.cjs'\)/);
  assert.match(script.with.script, /await guardAutoMerge\(\{ github, context, core \}\)/);
});

test('the MCP documentation reader stays in always-on Public claims', () => {
  const job = workflow.jobs['public-claims'];
  assert.equal(job.if, 'always()');
  const step = job.steps.find(step => step.run?.includes('node scripts/dev/test-dev-mcp-surface-honesty.mjs'));
  assert.ok(step);
  assert.equal(step.if, undefined);
});
