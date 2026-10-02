#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseUiWebGateCommands } from './run-ui-web-gates.mjs';

// Only executable run fields count. Comments, fixture tests and warn-mode runs are
// not evidence that a required hosted job enforces a gate on production sources.
export function workflowJobs(text) {
  const jobs = [];
  let job;
  let run;
  let step;
  for (const line of text.split(/\r?\n/)) {
    if (/^  [\w-]+:\s*$/.test(line)) {
      job = { id: line.trim().slice(0, -1), runs: [], text: '' };
      jobs.push(job);
      run = null;
      step = null;
    }
    if (!job || /^\s*#/.test(line)) continue;
    job.text += '\n' + line;
    const field = /^    (name|runs-on|continue-on-error|if|timeout-minutes):\s*(.*)$/.exec(line);
    if (field) job[field[1]] = field[2];
    if (/^      - /.test(line)) step = {};
    const stepField = /^        (if|continue-on-error):\s*(.*)$/.exec(line);
    if (step && stepField) step[stepField[1]] = stepField[2];
    const command = /^(\s*)(?:-\s+)?run:\s*(.*)$/.exec(line);
    if (command) {
      run = { indent: command[1].length, text: /^[|>]/.test(command[2]) ? '' : command[2], step };
      job.runs.push(run);
    } else if (run && line.trim()) {
      if (line.search(/\S/) > run.indent) run.text += '\n' + line.trim();
      else run = null;
    }
  }
  return jobs;
}

export function checkCoverage({ workflowText, registry, consultRegister, requiredChecks, exemptions = [] }) {
  const production = new Set();
  const fixtures = new Set();
  const known = registry.gates.map((g) => g.id);
  for (const job of workflowJobs(workflowText)) {
    const name = job.name ?? '';
    const required = requiredChecks.includes(name)
      || (name.includes('${{ matrix.') && requiredChecks.some((c) => c.startsWith(name.split('${{')[0])));
    if (!required || job['continue-on-error'] === 'true' || job.if
        || !/^(ubuntu|windows|macos)-|\$\{\{ matrix\.os \}\}/.test(job['runs-on'] ?? '')) continue;
    const commands = job.runs.filter((r) => {
      if (r.step?.['continue-on-error'] === 'true') return false;
      const condition = r.step?.if;
      if (!condition || condition === 'always()') return true;
      const matrix = /^matrix\.(\w+) == ['"]([\w-]+)['"]$/.exec(condition);
      return matrix && new RegExp(`${matrix[1]}: ['"]?${matrix[2]}['"]?\\s*$`, 'm').test(job.text);
    }).flatMap((r) => r.text.replace(/\\\s*\n/g, ' ').split('\n').map((c) => c.split('#')[0]));
    if (commands.some((c) => /^node scripts\/ci\/run-ui-web-gates\.mjs\s*$/.test(c.trim()))) {
      commands.push(...parseUiWebGateCommands(consultRegister).map((c) => c.join(' ')));
    }
    for (const command of commands) {
      if (!/^node scripts\/governance\/run\.mjs(?:\s|$)/.test(command.trim())) continue;
      const ids = [...command.matchAll(/--gate\s+([\w,-]+)/g)].flatMap((m) => m[1].split(','));
      if (/--self-test\b/.test(command)) {
        for (const id of ids.length ? ids : known) fixtures.add(id);
      } else if (/--mode\s+gate\b/.test(command) && !/--(?:fixture|repo-root)\b/.test(command)) {
        for (const id of ids.length ? ids : known) production.add(id);
      }
    }
  }
  const issues = [];
  for (const e of exemptions) {
    if (!known.includes(e.id) || !e.reason?.trim()) issues.push(`invalid exemption: ${e.id}`);
  }
  const rows = registry.gates.map((g) => ({
    id: g.id, production: production.has(g.id), fixtures: fixtures.has(g.id) && !!g.selfTestFixturesDir,
  }));
  for (const row of rows) {
    if (!row.production && !exemptions.some((e) => e.id === row.id)) {
      issues.push(`${row.id}: no required hosted production invocation (fixture coverage: ${row.fixtures})`);
    }
  }
  return { issues, rows };
}

function main() {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
  const json = (p) => JSON.parse(fs.readFileSync(path.join(root, p), 'utf8'));
  const result = checkCoverage({
    workflowText: fs.readFileSync(path.join(root, '.github/workflows/ci.yml'), 'utf8'),
    registry: json('governance/registry.v1.json'),
    consultRegister: json('governance/consult-register.v1.json'),
    requiredChecks: json('scripts/ci/workflow-signal-policy.v1.json').workflows.find((w) => w.name === 'CI').requiredStatusChecks,
    exemptions: json('scripts/ci/governance-ci-coverage-policy.v1.json').exemptions,
  });
  if (result.issues.length) {
    console.error(result.issues.join('\n'));
    process.exitCode = 1;
  } else console.log(`check-governance-ci-coverage: OK (${result.rows.filter((r) => r.production).length}/${result.rows.length} production gates; documented exemptions)`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
