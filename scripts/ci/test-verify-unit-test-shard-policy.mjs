#!/usr/bin/env node

import assert from 'node:assert/strict';
import fs from 'node:fs';

import { validateUnitTestShardPolicy } from './verify-unit-test-shard-policy.mjs';

const tasks = [':modules:alpha:test', ':modules:beta:test'];

function lane(overrides = {}) {
  return {
    lane: 'alpha',
    requiredCheck: 'Unit tests (alpha)',
    artifact: 'unit-test-attribution-alpha',
    runnerLabel: 'windows-latest/alpha',
    owner: 'alpha owner',
    platformClass: 'mixed/needs targeted experiment',
    platformRiskNotes: ['risk note'],
    gradleTasks: tasks,
    localCommand: './gradlew.bat :modules:alpha:test :modules:beta:test -PskipWebBuild=true --console=plain',
    advisoryBudgets: {
      maxSummedSuiteSeconds: 300,
      slowSuiteWarnSeconds: 60,
      maxSkipped: 50,
    },
    ...overrides,
  };
}

function policy(lanes = [lane()]) {
  return {
    kind: 'justsearch-unit-test-shard-policy.v1',
    version: 1,
    budgetsAreAdvisory: true,
    lanes,
  };
}

function workflow({ laneName = 'alpha', artifact = 'unit-test-attribution-alpha', runnerLabel = 'windows-latest/alpha', gradleTasks = tasks } = {}) {
  return [
    'name: CI',
    'jobs:',
    '  unit-tests:',
    '    name: Unit tests (${{ matrix.lane }})',
    '    strategy:',
    '      matrix:',
    '        include:',
    `          - lane: ${laneName}`,
    `            artifact: ${artifact}`,
    `            runner_label: ${runnerLabel}`,
    '            gradle_tasks: >-',
    ...gradleTasks.map((task) => `              ${task}`),
    '    steps:',
    '      - name: Test',
    '        run: ./gradlew.bat ${{ matrix.gradle_tasks }}',
    '  other:',
    '    runs-on: ubuntu-latest',
    '',
  ].join('\n');
}

function signalPolicy(checks = ['Unit tests (alpha)']) {
  return {
    workflows: [
      {
        name: 'CI',
        requiredStatusChecks: checks,
      },
    ],
  };
}

function validate({ policyDoc = policy(), workflowText = workflow(), signalDoc = signalPolicy() } = {}) {
  return validateUnitTestShardPolicy({
    policy: policyDoc,
    workflowText,
    workflowSignalPolicy: signalDoc,
  });
}

{
  const result = validate();
  assert.equal(result.ok, true, result.issues.join('\n'));
  assert.deepEqual(result.policyLanes, ['alpha']);
  assert.deepEqual(result.workflowLanes, ['alpha']);
}

{
  const result = validate({ workflowText: workflow({ laneName: 'beta' }) });
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /policy lane missing from workflow matrix: alpha/);
  assert.match(result.issues.join('\n'), /workflow matrix lane missing from policy: beta/);
}

{
  const result = validate({ workflowText: workflow({ artifact: 'wrong-artifact' }) });
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /artifact mismatch/);
}

{
  const result = validate({ workflowText: workflow({ runnerLabel: 'ubuntu-latest/alpha' }) });
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /runner label mismatch/);
}

{
  const result = validate({ workflowText: workflow({ gradleTasks: [':modules:alpha:test'] }) });
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /gradle task list differs/);
}

{
  const result = validate({ signalDoc: signalPolicy(['Other check']) });
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /required check missing/);
}

{
  const result = validate({ policyDoc: policy([lane({ platformClass: 'unknown-platform' })]) });
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /platformClass is not allowed/);
}

{
  const result = validate({ policyDoc: policy([lane({ platformRiskNotes: [] })]) });
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /platformRiskNotes must be a non-empty array/);
}

console.log('test-verify-unit-test-shard-policy: PASS');

// The required aggregate must enforce both expected parts, including non-success
// conclusions, and a producer cannot opt out of enforcement.
{
  const p = policy();
  p.aggregates = [{ jobId: 'aggregate', requiredCheck: 'Unit tests (app-ui)', parts: ['one', 'two'] }];
  const text = workflow() + `
  one:
    name: One
    runs-on: ubuntu-latest
    steps:
      - run: true
  two:
    name: Two
    runs-on: ubuntu-latest
    steps:
      - run: true
  aggregate:
    name: Unit tests (app-ui)
    needs: [one, two]
    if: always()
    runs-on: ubuntu-latest
    steps:
      - name: Require both
        env:
          ONE_RESULT: \${{ needs.one.result }}
          TWO_RESULT: \${{ needs.two.result }}
        run: test "$ONE_RESULT" = success && test "$TWO_RESULT" = success
`;
  const check = (workflowText) => validate({ policyDoc: p, workflowText });
  assert.equal(check(text).ok, true, check(text).issues.join('\n'));
  const scoped = text.replace('    if: always()\n', "    if: always()\n")
    .replace('  one:\n', "  one:\n    if: needs.changes.outputs.windows != 'false'\n")
    .replace('  two:\n', "  two:\n    if: needs.changes.outputs.windows != 'false'\n");
  assert.equal(check(scoped).ok, true, check(scoped).issues.join('\n'));
  for (const condition of ["github.event_name == 'workflow_dispatch'", "github.event_name != 'push'", "needs.changes.outputs.windows != 'false' && false"]) {
    assert.equal(check(scoped.replaceAll("needs.changes.outputs.windows != 'false'", condition)).ok, false, condition);
  }
  for (const altered of [
    text.replace('needs: [one, two]', 'needs: [one]'),
    text.replace('    if: always()\n', ''),
    text.replace(' = success && test "$TWO_RESULT" = success', ' = success || true'),
    text.replace('  two:\n', '  two:\n    continue-on-error: true\n'),
    text.replace('  two:\n', '  two:\n    if: false\n'),
    text.replace('      - run: true', '      - run: true\n        continue-on-error: true'),
    text.replace('      - run: true', '      - run: true\n        if: false'),
  ]) assert.equal(check(altered).ok, false, altered);
}

// Every Windows command and task remains owned exactly once after the split.
{
  const read = (file) => fs.readFileSync(new URL('../../' + file, import.meta.url), 'utf8');
  const policyDoc = JSON.parse(read('scripts/ci/unit-test-shard-policy.v1.json'));
  const signalDoc = JSON.parse(read('scripts/ci/workflow-signal-policy.v1.json'));
  const original = read('.github/workflows/ci.yml');
  const check = (workflowText) => validate({ policyDoc, signalDoc, workflowText });
  assert.equal(check(original).ok, true, check(original).issues.join('\n'));
  for (const part of [...policyDoc.windowsParts, ...policyDoc.lanes.filter((lane) => lane.commands)]) {
    for (const command of part.commands) {
      const start = original.indexOf(`  ${part.jobId}:\n`);
      const altered = original.slice(0, start) + original.slice(start).replace(command, '# omitted by counterexample');
      assert.notEqual(altered, original, command);
      assert.equal(check(altered).ok, false, command);
    }
  }
  for (const altered of [
    original.replace(" || steps.windows-coverage.outcome == 'failure'", ''),
    original.replace('id: windows-coverage', 'id: unrelated-step'),
    original.replace('id: windows-coverage', 'id: windows-coverage\n        if: false'),
  ]) assert.equal(check(altered).ok, false, 'coverage guard must reference an unconditional Gradle execution and include failures');
  const windowsTask = original.indexOf('      - name: Windows coverage');
  const omitted = original.slice(0, windowsTask) + original.slice(windowsTask).replace(':modules:app-api:test ', '');
  assert.equal(check(omitted).ok, false, 'omitting a Windows module must fail ownership');
}

// Distinct tasks own complete class partitions; removing or duplicating one cannot
// silently reduce the source-set coverage when both workflow and policy are edited.
{
  const read = file => fs.readFileSync(new URL('../../' + file, import.meta.url), 'utf8');
  const original = JSON.parse(read('scripts/ci/unit-test-shard-policy.v1.json'));
  const classShards = JSON.parse(read('scripts/ci/unit-test-class-shards.v1.json'));
  const options = { workflowText: read('.github/workflows/ci.yml'),
    workflowSignalPolicy: JSON.parse(read('scripts/ci/workflow-signal-policy.v1.json')), classShards };
  const check = policy => validateUnitTestShardPolicy({ ...options, policy });
  assert.equal(check(original).ok, true);
  const duplicate = structuredClone(original);
  duplicate.lanes[1].gradleTasks.push(duplicate.lanes[0].gradleTasks[0]);
  assert.match(check(duplicate).issues.join('\n'), /exactly one shard owner/);
  const missing = structuredClone(original);
  missing.lanes[0].gradleTasks.shift();
  assert.match(check(missing).issues.join('\n'), /unit test task inventory differs/);
  for (const [module, partition] of Object.entries(classShards.modules)) {
    const tasks = original.lanes.flatMap(lane => lane.gradleTasks).filter(task => task.startsWith(`:modules:${module}:`));
    assert.deepEqual(tasks.sort(), Array.from({ length: partition.count }, (_, i) => `:modules:${module}:ciUnitTest${i + 1}`).sort());
    assert.ok(Number.isInteger(partition.count) && partition.count >= 2);
    for (const [name, bucket] of Object.entries(partition.classes)) {
      assert.match(name, /^io\.justsearch\./);
      assert.ok(Number.isInteger(bucket) && bucket >= 0 && bucket < partition.count, `${module}/${name}`);
    }
  }
}

// The Windows split retains exactly the twelve complete pre-split Test tasks.
// Mutating workflow and owners together cannot erase or duplicate their coverage.
{
  const read = file => fs.readFileSync(new URL('../../' + file, import.meta.url), 'utf8');
  const original = JSON.parse(read('scripts/ci/unit-test-shard-policy.v1.json'));
  const workflowText = read('.github/workflows/ci.yml');
  const workflowSignalPolicy = JSON.parse(read('scripts/ci/workflow-signal-policy.v1.json'));
  const parts = original.windowsParts.filter(part => part.gradleTasks.length);
  assert.equal(parts.length, 2, 'Windows coverage must have two independent jobs');
  const modules = ['app-api', 'app-util', 'app-agent', 'app-services', 'app-observability', 'ui',
    'adapters-lucene', 'app-inference', 'worker-services', 'ort-common', 'configuration', 'indexer-worker'];
  const tasks = parts.flatMap(part => part.gradleTasks);
  assert.deepEqual([...tasks].sort(), modules.map(module => `:modules:${module}:test`).sort());
  const check = policy => validateUnitTestShardPolicy({ policy, workflowText, workflowSignalPolicy });
  for (const part of parts) {
    const job = workflowText.slice(workflowText.indexOf(`  ${part.jobId}:\n`)).split(/\n  [\w-]+:\n/)[0];
    assert.match(job, /cache-read-only: \$\{\{ github.ref != 'refs\/heads\/main' \}\}/);
    assert.match(job, /-PwindowsOnly=true -PskipWebBuild=true --build-cache --profile --continue/);
    for (const entry of part.requiredClasses) {
      const candidates = modules.filter(module => fs.existsSync(new URL(
        `../../modules/${module}/src/test/java/${entry.name.replaceAll('.', '/')}.java`, import.meta.url)));
      assert.equal(candidates.length, 1, entry.name);
      assert.ok(part.gradleTasks.includes(`:modules:${candidates[0]}:test`), `${entry.name}: safety evidence belongs to the task's part`);
    }
    for (const task of part.gradleTasks) {
      const omitted = structuredClone(original);
      omitted.windowsParts.find(entry => entry.jobId === part.jobId).gradleTasks = part.gradleTasks.filter(entry => entry !== task);
      assert.match(check(omitted).issues.join('\n'), /Windows test task inventory differs/);
      const duplicate = structuredClone(original);
      duplicate.windowsParts.find(entry => entry.jobId !== part.jobId).gradleTasks.push(task);
      assert.match(check(duplicate).issues.join('\n'), /Windows test task inventory differs/);
      // Scope the mutation to Windows: the same task may also have a Linux owner.
      const start = workflowText.indexOf(`  ${part.jobId}:\n`);
      const missingInPart = workflowText.slice(0, start) + workflowText.slice(start).replace(`${task} `, '');
      assert.equal(validateUnitTestShardPolicy({ policy: original, workflowText: missingInPart, workflowSignalPolicy }).ok, false);
    }
  }
  const classes = parts.flatMap(part => part.requiredClasses.map(entry => entry.name));
  assert.equal(classes.length, 15);
  assert.equal(new Set(classes).size, 15, 'each safety class has one owner');
}
