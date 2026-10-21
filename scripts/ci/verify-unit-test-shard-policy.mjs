#!/usr/bin/env node
/**
 * Verify that the public CI unit-test matrix matches the declared shard policy.
 *
 * This is an ownership guard only. It does not schedule lanes, tune budgets, or
 * change branch protection.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import { workflowJobs, aggregateDependencies, runsOnPrAndPush } from './check-governance-ci-coverage.mjs';

const KIND = 'justsearch-unit-test-shard-policy.v1';
const ALLOWED_PLATFORM_CLASSES = new Set([
  'windows-required',
  'probably-platform-neutral',
  'mixed/needs targeted experiment',
]);
const REQUIRED_LANE_FIELDS = [
  'lane',
  'requiredCheck',
  'artifact',
  'runnerLabel',
  'owner',
  'platformClass',
  'localCommand',
];

function repoRootFromCwd() {
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, 'settings.gradle.kts'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
  }
}

function parseArgs(argv) {
  const out = {
    root: null,
    policy: null,
    workflow: null,
    workflowSignalPolicy: null,
    json: false,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--root' && argv[i + 1]) out.root = argv[++i];
    else if (arg === '--policy' && argv[i + 1]) out.policy = argv[++i];
    else if (arg === '--workflow' && argv[i + 1]) out.workflow = argv[++i];
    else if (arg === '--workflow-signal-policy' && argv[i + 1]) out.workflowSignalPolicy = argv[++i];
    else if (arg === '--json') out.json = true;
    else if (arg === '--help' || arg === '-h') out.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  return out;
}

function usage() {
  return [
    'Usage: node scripts/ci/verify-unit-test-shard-policy.mjs [--json] [--root DIR]',
    '       [--policy FILE] [--workflow FILE] [--workflow-signal-policy FILE]',
    '',
    'Verifies that scripts/ci/unit-test-shard-policy.v1.json matches',
    '.github/workflows/ci.yml and workflow-signal-policy.v1.json.',
  ].join('\n');
}

function stripQuotes(value) {
  const trimmed = String(value ?? '').trim();
  if (
    (trimmed.startsWith('"') && trimmed.endsWith('"')) ||
    (trimmed.startsWith("'") && trimmed.endsWith("'"))
  ) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function leadingSpaces(line) {
  return /^ */.exec(line)?.[0].length ?? 0;
}

function findUnitTestJobBlock(text) {
  const lines = text.split(/\r?\n/);
  let start = -1;
  let startIndent = -1;
  for (let i = 0; i < lines.length; i += 1) {
    if (/^  unit-tests:\s*$/.test(lines[i])) {
      start = i;
      startIndent = leadingSpaces(lines[i]);
      break;
    }
  }
  if (start === -1) return [];
  const block = [];
  for (let i = start + 1; i < lines.length; i += 1) {
    const line = lines[i];
    if (line.trim() && leadingSpaces(line) <= startIndent) break;
    block.push(line);
  }
  return block;
}

export function parseUnitTestMatrix(workflowText) {
  const lines = findUnitTestJobBlock(workflowText);
  const lanes = [];
  let current = null;

  function finishCurrent() {
    if (current) {
      current.gradleTasks = current.gradleTasks ?? [];
      lanes.push(current);
    }
    current = null;
  }

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    const laneMatch = /^\s*-\s+lane:\s*(.+?)\s*$/.exec(line);
    if (laneMatch) {
      finishCurrent();
      current = {
        lane: stripQuotes(laneMatch[1]),
        artifact: null,
        runnerLabel: null,
        gradleTasks: [],
      };
      continue;
    }
    if (!current) continue;

    const scalarMatch = /^\s*(artifact|runner_label):\s*(.+?)\s*$/.exec(line);
    if (scalarMatch) {
      if (scalarMatch[1] === 'artifact') current.artifact = stripQuotes(scalarMatch[2]);
      else current.runnerLabel = stripQuotes(scalarMatch[2]);
      continue;
    }

    const tasksMatch = /^(\s*)gradle_tasks:\s*>-\s*$/.exec(line);
    if (tasksMatch) {
      const indent = tasksMatch[1].length;
      const tasks = [];
      for (let j = i + 1; j < lines.length; j += 1) {
        const taskLine = lines[j];
        if (taskLine.trim() && leadingSpaces(taskLine) <= indent) break;
        if (taskLine.trim()) tasks.push(taskLine.trim());
        i = j;
      }
      current.gradleTasks = tasks;
    }
  }
  finishCurrent();
  // Explicit jobs are used for app-ui so its aggregate waits only on its own parts.
  for (const job of workflowJobs(workflowText)) {
    const match = /^Unit tests \(([^$]+)\)$/.exec(job.name ?? '');
    const tasks = [...job.text.matchAll(/:modules:[\w-]+:test\b/g)].map((m) => m[0]);
    if (!match || !tasks.length) continue;
    lanes.push({
      lane: match[1],
      artifact: /name: (unit-test-attribution-[\w-]+?)(?:-attempt-|\s*$)/m.exec(job.text)?.[1],
      runnerLabel: /--runner-label "([^"$]+)"/.exec(job.text)?.[1],
      gradleTasks: tasks,
    });
  }
  lanes.sort((a, b) => a.lane.localeCompare(b.lane));
  return lanes;
}

function requiredCheckNamesFromWorkflowSignalPolicy(policy) {
  const workflows = Array.isArray(policy.workflows) ? policy.workflows : [];
  const ci = workflows.find((entry) => entry.name === 'CI');
  return new Set(Array.isArray(ci?.requiredStatusChecks) ? ci.requiredStatusChecks : []);
}

function missingStringFields(entry, fields) {
  return fields.filter((field) => typeof entry[field] !== 'string' || entry[field].trim() === '');
}

function laneMap(lanes, key, issues, label) {
  const map = new Map();
  for (const lane of lanes) {
    if (!lane[key]) {
      issues.push(`${label} lane missing ${key}`);
      continue;
    }
    if (map.has(lane[key])) issues.push(`duplicate ${label} lane: ${lane[key]}`);
    map.set(lane[key], lane);
  }
  return map;
}

function sameList(a, b) {
  return a.length === b.length && a.every((value, index) => value === b[index]);
}

function validateBudget(entry, issues) {
  const budget = entry.advisoryBudgets;
  if (!budget || typeof budget !== 'object') {
    issues.push(`${entry.lane}: missing advisoryBudgets`);
    return;
  }
  for (const field of ['maxSummedSuiteSeconds', 'slowSuiteWarnSeconds', 'maxSkipped']) {
    if (!Number.isFinite(budget[field]) || budget[field] < 0) {
      issues.push(`${entry.lane}: advisoryBudgets.${field} must be a non-negative number`);
    }
  }
}

export function validateUnitTestShardPolicy({ policy, workflowText, workflowSignalPolicy }) {
  const issues = [];
  if (policy.kind !== KIND) issues.push(`policy kind must be ${KIND}, got ${policy.kind}`);
  if (policy.version !== 1) issues.push(`policy version must be 1, got ${policy.version}`);
  if (policy.budgetsAreAdvisory !== true) issues.push('policy budgetsAreAdvisory must be true');
  if (!Array.isArray(policy.lanes)) issues.push('policy lanes must be an array');

  const policyLanes = Array.isArray(policy.lanes) ? policy.lanes : [];
  for (const lane of policyLanes) {
    const missing = missingStringFields(lane, REQUIRED_LANE_FIELDS);
    if (missing.length > 0) issues.push(`${lane.lane || '<unknown>'}: missing fields: ${missing.join(', ')}`);
    if (!Array.isArray(lane.gradleTasks) || lane.gradleTasks.length === 0) {
      issues.push(`${lane.lane || '<unknown>'}: gradleTasks must be a non-empty array`);
    }
    if (!Array.isArray(lane.platformRiskNotes) || lane.platformRiskNotes.length === 0) {
      issues.push(`${lane.lane || '<unknown>'}: platformRiskNotes must be a non-empty array`);
    }
    if (lane.platformClass && !ALLOWED_PLATFORM_CLASSES.has(lane.platformClass)) {
      issues.push(`${lane.lane}: platformClass is not allowed: ${lane.platformClass}`);
    }
    validateBudget(lane, issues);
  }

  const jobs = workflowJobs(workflowText);
  for (const aggregate of policy.aggregates ?? []) {
    const job = jobs.find((j) => j.id === aggregate.jobId);
    const actual = job ? aggregateDependencies(job).sort() : [];
    if (!job || job.name !== aggregate.requiredCheck
        || !sameList(actual, [...aggregate.parts].sort())) {
      issues.push(`${aggregate.jobId}: aggregate must require success from every expected part on PRs and pushes`);
    }
    for (const part of aggregate.parts) {
      const partJob = jobs.find((j) => j.id === part);
      if (!partJob || !runsOnPrAndPush(partJob.if) || partJob['continue-on-error']
          || /^        continue-on-error:/m.test(partJob.text)
          || partJob.runs.some((run) => {
            if (!run.step?.if || run.step.if === 'always()') return false;
            // Coverage attribution must still run after a failed Gradle execution. Its only
            // permitted bypass is when that execution never ran (an earlier failure/cancel).
            const coverage = partJob.runs.find(candidate => candidate.step?.id === 'windows-coverage');
            return !(part === 'windows-native-contracts'
              && run.text.startsWith('node scripts/ci/report-unit-test-attribution.mjs ')
              && run.text.includes('--require-windows-classes')
              && run.step.if === "always() && (steps.windows-coverage.outcome == 'success' || steps.windows-coverage.outcome == 'failure')"
              && coverage && !coverage.step.if && coverage.text.includes('-PwindowsOnly=true'));
          })) {
        issues.push(`${part}: required part is missing, conditional or tolerates job/step failure`);
      }
    }
  }
  for (const part of [...(policy.windowsParts ?? []), ...policyLanes.filter((lane) => lane.commands)]) {
    const job = jobs.find((j) => j.id === part.jobId);
    const commands = job?.runs.map((r) => r.text).join('\n') ?? '';
    const commandLines = commands.split('\n').map((line) => line.trim());
    for (const command of part.commands) {
      if (commandLines.filter((line) => line === command).length !== 1) issues.push(`${part.jobId}: expected command exactly once: ${command}`);
    }
    const actualTasks = [...commands.matchAll(/:modules:[\w-]+:test\b/g)].map((m) => m[0]);
    if (!sameList(actualTasks, part.gradleTasks)) issues.push(`${part.jobId}: Gradle task ownership differs`);
  }

  const workflowLanes = parseUnitTestMatrix(workflowText);
  const byPolicyLane = laneMap(policyLanes, 'lane', issues, 'policy');
  const byWorkflowLane = laneMap(workflowLanes, 'lane', issues, 'workflow');
  const requiredChecks = requiredCheckNamesFromWorkflowSignalPolicy(workflowSignalPolicy);

  for (const [laneName, policyLane] of byPolicyLane) {
    const workflowLane = byWorkflowLane.get(laneName);
    if (!workflowLane) {
      issues.push(`policy lane missing from workflow matrix: ${laneName}`);
      continue;
    }
    if (policyLane.artifact !== workflowLane.artifact) {
      issues.push(`${laneName}: artifact mismatch policy=${policyLane.artifact} workflow=${workflowLane.artifact}`);
    }
    if (policyLane.runnerLabel !== workflowLane.runnerLabel) {
      issues.push(`${laneName}: runner label mismatch policy=${policyLane.runnerLabel} workflow=${workflowLane.runnerLabel}`);
    }
    if (!sameList(policyLane.gradleTasks || [], workflowLane.gradleTasks || [])) {
      issues.push(`${laneName}: gradle task list differs between policy and workflow`);
    }
    if (!requiredChecks.has(policyLane.requiredCheck)) {
      issues.push(`${laneName}: required check missing from workflow-signal-policy: ${policyLane.requiredCheck}`);
    }
  }
  for (const laneName of byWorkflowLane.keys()) {
    if (!byPolicyLane.has(laneName)) issues.push(`workflow matrix lane missing from policy: ${laneName}`);
  }

  return {
    ok: issues.length === 0,
    issues,
    policyLanes: [...byPolicyLane.keys()].sort(),
    workflowLanes: [...byWorkflowLane.keys()].sort(),
    requiredUnitChecks: [...requiredChecks].filter((check) => check.startsWith('Unit tests (')).sort(),
  };
}

function main() {
  try {
    const opts = parseArgs(process.argv.slice(2));
    if (opts.help) {
      console.log(usage());
      return;
    }
    const root = path.resolve(opts.root || repoRootFromCwd());
    const policyPath = path.resolve(opts.policy || path.join(root, 'scripts', 'ci', 'unit-test-shard-policy.v1.json'));
    const workflowPath = path.resolve(opts.workflow || path.join(root, '.github', 'workflows', 'ci.yml'));
    const signalPolicyPath = path.resolve(opts.workflowSignalPolicy || path.join(root, 'scripts', 'ci', 'workflow-signal-policy.v1.json'));
    const result = validateUnitTestShardPolicy({
      policy: JSON.parse(fs.readFileSync(policyPath, 'utf8')),
      workflowText: fs.readFileSync(workflowPath, 'utf8'),
      workflowSignalPolicy: JSON.parse(fs.readFileSync(signalPolicyPath, 'utf8')),
    });
    if (opts.json) console.log(JSON.stringify(result, null, 2));
    else if (result.ok) console.log(`verify-unit-test-shard-policy: OK (${result.policyLanes.length} lanes)`);
    else {
      console.error('verify-unit-test-shard-policy: FAIL');
      for (const issue of result.issues) console.error(`- ${issue}`);
    }
    if (!result.ok) process.exitCode = 1;
  } catch (error) {
    console.error(`verify-unit-test-shard-policy: ${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
