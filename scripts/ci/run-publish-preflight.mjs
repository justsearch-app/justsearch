#!/usr/bin/env node

import { spawn, spawnSync } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import { classifyDiff } from './classify-changes.mjs';
import { requiredInputsToProduce } from '../governance/lib/input-contract.mjs';
import { loadPublicCiLocalRepro } from './lib/public-ci-local-repro.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

function usage() {
  return 'Usage: node scripts/ci/run-publish-preflight.mjs [--check|--list|--run] | --prepare [--list] [--pr N]';
}

export function commandForPlatform(command, platform = process.platform) {
  if (platform !== 'win32') return command;
  return command.replace(/^\.\/gradlew\.bat(?=\s|$)/, '.\\gradlew.bat');
}

export function assertCleanCandidate({ cwd = ROOT, run = spawnSync } = {}) {
  const result = run(
    'git',
    ['status', '--porcelain=v1', '--untracked-files=all'],
    { cwd, encoding: 'utf8', windowsHide: true },
  );
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`git status failed while checking the publication candidate (exit ${result.status ?? 'unknown'})`);
  }
  if (String(result.stdout || '').trim()) {
    throw new Error(
      'publication preflight requires a clean candidate; commit all staged, unstaged, and non-ignored untracked files before --run or --prepare',
    );
  }
}

export function executeLocalSubsets(manifest, {
  checkCandidate = assertCleanCandidate,
  cwd = ROOT,
  platform = process.platform,
  run = spawnSync,
} = {}) {
  checkCandidate({ cwd });
  for (const context of manifest.contexts) {
    if (context.mode !== 'local-subset') continue;
    process.stdout.write(`\n[${context.check}]\n`);
    for (const command of context.commands) {
      const executableCommand = commandForPlatform(command, platform);
      process.stdout.write(`> ${executableCommand}\n`);
      const result = run(executableCommand, { cwd, shell: true, stdio: 'inherit' });
      if (result.error) throw result.error;
      if (result.status !== 0) return result.status ?? 1;
    }
  }
  return 0;
}

function printInventory(manifest) {
  for (const context of manifest.contexts) {
    if (context.mode === 'hosted-only') console.log(`${context.check}: hosted-only — ${context.reason}`);
    else console.log(`${context.check}: local-subset (${context.commands.length} command${context.commands.length === 1 ? '' : 's'})`);
  }
}

async function main() {
  if (process.argv.includes('--prepare')) {
    await preparedMain(process.argv.slice(2));
    return;
  }
  const args = process.argv.slice(2);
  if (args.length > 1 || (args[0] && !['--check', '--list', '--run', '-h', '--help'].includes(args[0]))) {
    console.error(usage());
    process.exitCode = 2;
    return;
  }
  if (args[0] === '-h' || args[0] === '--help') {
    console.log(usage());
    return;
  }
  const manifest = loadPublicCiLocalRepro();
  if (args[0] === '--run') process.exitCode = executeLocalSubsets(manifest);
  else if (args[0] === '--list') printInventory(manifest);
  else console.log(`run-publish-preflight: PASS (${manifest.contexts.length} required checks classified)`);
}

const require = createRequire(import.meta.url);

function gitText(args, cwd = ROOT, run = spawnSync) {
  const result = run('git', args, { cwd, encoding: 'utf8', windowsHide: true });
  if (result.error || result.status !== 0) throw new Error(`git ${args.join(' ')}: ${result.error?.message || result.stderr || result.status}`);
  return result.stdout.trim();
}

export function resolvePrContext({ cwd = ROOT, pr, run = spawnSync } = {}) {
  const candidate = gitText(['rev-parse', 'HEAD'], cwd, run);
  let number = pr;
  let provisional = false;
  let reason;
  if (pr === undefined) {
    provisional = true;
    const result = run('gh', ['api', 'repos/justsearch-app/justsearch/issues?state=all&sort=created&direction=desc&per_page=1', '--jq', '.[0].number'], { cwd, encoding: 'utf8', timeout: 30000 });
    const last = String(result.stdout || '').trim();
    if (result.status === 0 && /^\d+$/.test(last) && Number.isSafeInteger(Number(last) + 1)) number = Number(last) + 1;
    else reason = 'gh PR-number prediction unavailable; audit selection not checked here (supply --pr N)';
  }
  const base = gitText(['merge-base', 'origin/main', candidate], cwd, run);
  return { candidate, base, pr: number, provisional, reason };
}

export function buildPreparedPlan(manifest, { cwd = ROOT, context, lanes, registry } = {}) {
  registry ??= JSON.parse(fs.readFileSync(path.join(cwd, 'governance/registry.v1.json'), 'utf8'));
  const steps = [
    { id: 'isolated-tools', command: 'node scripts/dev/prepare-ui-checks.cjs', network: true, isolated: false, platform: 'linux', outputs: ['tmp/ui-checks/venv', 'tmp/ui-checks/env.sh', 'tmp/ui-checks/scratch'] },
    { id: 'candidate-dist', command: 'node scripts/dev/prepare-worktree.cjs', needs: ['isolated-tools'], gradle: true, network: true },
    { id: 'wire-tools', command: 'npm --prefix scripts/wire-contract install', needs: ['isolated-tools'], network: true },
  ];
  const inputsByGate = new Map();
  for (const gate of registry.gates) {
    // Regenerate even when the previous candidate left an input behind.
    const inputs = requiredInputsToProduce({ gate, repoRoot: cwd, fileExists: () => false });
    inputsByGate.set(gate.id, []);
    for (const [i, input] of inputs.entries()) {
      const id = `input-${gate.id}-${i}`;
      inputsByGate.get(gate.id).push(id);
      steps.push({ id, command: input.producer, needs: ['isolated-tools'], outputs: [input.path],
        advisory: gate.id === 'npm-audit', network: true });
    }
  }
  for (const entry of manifest.contexts) {
    if (entry.lanes && !entry.lanes.some(lane => lanes[lane])) continue;
    const prefix = entry.check.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/-$/, '');
    if (entry.mode === 'hosted-only') {
      steps.push({ id: prefix, check: entry.check, reason: entry.reason });
      continue;
    }
    const setup = [];
    for (const [i, original] of entry.commands.entries()) {
      const id = `${prefix}-${i}`;
      let command = original;
      const needs = ['isolated-tools', ...setup];
      const gates = [...command.matchAll(/--gate ([a-z0-9-]+)/g)].map(match => match[1]);
      for (const gate of gates) needs.push(...(inputsByGate.get(gate) || []));
      if (gates.includes('wire')) needs.push('wire-tools');
      if (/gradle|java-prepush|agent-analytics\/run-all-tests|ui-a11y-gate/.test(command)) needs.push('candidate-dist');
      let reason;
      if (gates.includes('test-intent')) {
        command = `node scripts/governance/gates/test-intent/cli.mjs --base ${context.base} --pr ${context.pr ?? '<unavailable>'}`;
        if (!context.pr) reason = context.reason;
      }
      if (/playwright install --with-deps/.test(command)) reason = 'system browser dependencies require changes to the shared host; isolated Chromium is prepared separately';
      const windows = /^cmd \/c\b|^\.\/gradlew\.bat\b/.test(command);
      const pythonVersion = /python -m (?:pip|pytest)/.test(command) ? '3.13' : undefined;
      steps.push({ id, check: entry.check, command, needs: [...new Set(needs)], reason,
        platform: windows ? 'win32' : undefined, pythonVersion,
        network: /npm (?:ci|.*install)|pip install|playwright install/.test(command) });
      // Only setup failures stop the rest of this context. Checks stay independent.
      if (windows || /npm (?:ci|.*install)|pip install|playwright install|(?:gradle|gradlew).*\b(?:assemble|build|checkLicense)\b/.test(original)) setup.push(id);
    }
  }
  steps.push({ id: 'hosted-parity', reason: 'The inventory reproduces local subsets; display/GPU product checks and hosted toolchain parity remain unverified.' });
  return steps;
}

export function resultForCommand(step, result, { cwd = ROOT, platform = process.platform } = {}) {
  const output = `${result.stdout || ''}\n${result.stderr || ''}`;
  if (step.advisory && result.status === 0) {
    try {
      const report = JSON.parse(fs.readFileSync(path.join(cwd, step.outputs[0]), 'utf8'));
      if (!report.targets?.length || report.targets.some(target => !target.available)) {
        return { status: 'not checked here', reason: `GitHub advisory API unavailable: ${report.targets?.filter(target => !target.available).map(target => target.error).join('; ') || 'no targets'}` };
      }
    } catch (error) { return { status: 'failed', reason: `advisory evidence unreadable: ${error.message}` }; }
  }
  if (result.status === 0 && !result.error) {
    const missing = (step.outputs || []).filter(output => !fs.existsSync(path.join(cwd, output)));
    return missing.length ? { status: 'failed', reason: `producer did not write ${missing.join(', ')}` } : { status: 'passed' };
  }
  if (step.pythonVersion && result.status === 125) return { status: 'not checked here', reason: `requires CI Python ${step.pythonVersion}; the isolated interpreter has a different version` };
  const unavailable = /ENOTCACHED|ENETUNREACH|ENOTFOUND|EAI_AGAIN|ECONNREFUSED|ECONNRESET|ETIMEDOUT|ERR_SOCKET_TIMEOUT|HTTP(?: error)?[ :]+(?:403|5\d\d)|\bE(?:403|5\d\d)\b|returned error: (?:403|5\d\d)|curl: \((?:5|6|7|28|35|60)\)|fetch failed|network is unreachable|could not resolve host|proxy.*(?:refused|resolve)|CERT_HAS_EXPIRED|unable to get local issuer certificate/i;
  if (step.network && unavailable.test(output + (result.error?.message || ''))) {
    return { status: 'not checked here', reason: `${step.advisory ? 'GitHub advisory API' : 'network'} unavailable: ${output.trim().slice(-700)}` };
  }
  if (result.error?.code === 'ENOENT' || result.status === 127 || /spawn(?:Sync)? \S+ ENOENT/.test(output)) return { status: 'not checked here', reason: `required executable unavailable on ${platform}: ${result.error?.message || output.trim()}` };
  return { status: 'failed', reason: result.error?.message || `exit ${result.status ?? result.signal ?? 'unknown'}` };
}

// Check ignored destinations before deleting stale inputs or opening logs. A tracked
// input or symlink must never turn regeneration into a write outside this checkout.
export function assertIgnoredOutput(output, cwd = ROOT) {
  const absolute = path.resolve(cwd, output);
  const relative = path.relative(cwd, absolute);
  if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) throw new Error(`output outside checkout: ${output}`);
  let current = cwd;
  for (const part of relative.split(path.sep)) {
    current = path.join(current, part);
    if (fs.lstatSync(current, { throwIfNoEntry: false })?.isSymbolicLink()) throw new Error(`output is a symlink: ${current}`);
  }
  if (gitText(['ls-files', '--', relative], cwd)) throw new Error(`producer output is tracked: ${output}`);
  const ignored = spawnSync('git', ['check-ignore', '--quiet', '--', relative], { cwd });
  if (ignored.status !== 0) throw new Error(`producer output must be ignored: ${output}`);
}

export async function executePreparedPlan(steps, {
  cwd = ROOT, platform = process.platform, run, report = line => console.log(line), invalidate = output => {
    assertIgnoredOutput(output, cwd);
    fs.rmSync(path.join(cwd, output), { force: true, recursive: true });
  },
} = {}) {
  const results = new Map();
  const pending = [...steps];
  while (pending.length) {
    const index = pending.findIndex(step => (step.needs || []).every(id => results.has(id)));
    if (index < 0) throw new Error(`unresolved prerequisites: ${pending.map(step => step.id).join(', ')}`);
    const [step] = pending.splice(index, 1);
    const blocked = (step.needs || []).filter(id => results.get(id).status !== 'passed');
    // Invalidate even a blocked producer: a failed run cannot leave A's evidence looking current.
    for (const output of step.outputs || []) invalidate(output);
    let result;
    if (step.reason) result = { status: 'not checked here', reason: step.reason };
    else if (step.platform && step.platform !== platform) result = { status: 'not checked here', reason: `requires ${step.platform}; running on ${platform}` };
    else if (blocked.length) {
      const unavailable = blocked.filter(id => results.get(id).status === 'not checked here' && (id.startsWith('input-') || id === 'wire-tools'));
      result = unavailable.length
        ? { status: 'not checked here', reason: unavailable.map(id => `${id}: ${results.get(id).reason}`).join('; ') }
        : { status: 'blocked', reason: `prerequisites: ${blocked.join(', ')}` };
    }
    else if (step.virtual) result = { status: 'passed' };
    else {
      report(`\n[${step.id}] ${step.command}`);
      try { result = resultForCommand(step, await run(step), { cwd, platform }); }
      catch (error) { result = { status: 'failed', reason: error.message }; }
    }
    results.set(step.id, { ...result, check: step.check, label: step.label || step.command });
    report(`${step.id}: ${result.status}${result.reason ? ` — ${result.reason}` : ''}`);
  }
  return results;
}

export function formatPreparedSummary(results, context) {
  const counts = Object.fromEntries(['passed', 'failed', 'blocked', 'not checked here'].map(status => [status, [...results.values()].filter(r => r.status === status).length]));
  const complete = !counts.failed && !counts.blocked && !counts['not checked here'];
  return `Candidate ${context.candidate}; PR ${context.pr ?? 'unknown'}${context.provisional ? ' (provisional)' : ''}; base ${context.base}\n` +
    [...results].map(([id, result]) => `${result.status}: ${id}${result.label ? ` (${result.label.replace(/\s+/g, ' ')})` : ''}${result.reason ? ` — ${result.reason}` : ''}`).join('\n') +
    `\n${Object.entries(counts).map(([status, count]) => `${status}: ${count}`).join(' / ')} — ${complete ? 'complete' : 'incomplete'}`;
}

export function runPreparedCommand(step, { cwd, env, logDir, slot }) {
  let command = step.command;
  if (step.pythonVersion) {
    const version = step.pythonVersion.split('.').map(Number);
    command = `python -c 'import sys; sys.exit(0 if sys.version_info[:2] == (${version.join(', ')}) else 125)' && ${command}`;
  }
  // java-prepush owns its own slot; all other Gradle callers share the machine slot.
  if (step.gradle || /tool-invoker.cjs gradle/.test(command)) {
    if (!slot) return Promise.resolve({ status: 127, stderr: 'js-gradle-slot is required for Gradle on this shared machine' });
    command = `js-gradle-slot ${command}`;
  }
  if (step.isolated !== false) command = `source tmp/ui-checks/env.sh\n${command}`;
  assertIgnoredOutput(path.relative(cwd, path.join(logDir, `${step.id}.log`)), cwd);
  const log = fs.createWriteStream(path.join(logDir, `${step.id}.log`));
  return new Promise(resolve => {
    const child = spawn('bash', ['-e', '-o', 'pipefail', '-c', command], { cwd, env, stdio: ['ignore', 'pipe', 'pipe'] });
    // Only a diagnostic tail is retained in memory; the full output is in an ignored log.
    let stdout = '', stderr = '';
    child.stdout.on('data', data => { process.stdout.write(data); log.write(data); stdout = (stdout + data).slice(-32000); });
    child.stderr.on('data', data => { process.stderr.write(data); log.write(data); stderr = (stderr + data).slice(-32000); });
    let error;
    child.on('error', value => { error = value; });
    child.on('close', (status, signal) => { log.end(() => resolve({ status, signal, stdout, stderr, error })); });
  });
}

async function preparedMain(args) {
  let pr;
  for (let i = 0; i < args.length; i++) {
    if (['--prepare', '--list'].includes(args[i])) continue;
    if (args[i] === '--pr' && /^[1-9]\d*$/.test(args[i + 1] ?? '') && Number.isSafeInteger(Number(args[i + 1]))) pr = Number(args[++i]);
    else throw new Error(usage());
  }
  const manifest = loadPublicCiLocalRepro();
  // --list is offline and read-only, including no gh API request or output files.
  const listing = args.includes('--list');
  if (!listing) assertCleanCandidate();
  const context = listing ? {
    candidate: gitText(['rev-parse', 'HEAD']), base: gitText(['merge-base', 'origin/main', 'HEAD']),
    pr: pr ?? '{predicted PR}', provisional: pr === undefined,
  } : resolvePrContext({ pr });
  const lanes = classifyDiff({ eventName: 'pull_request', base: context.base, head: context.candidate });
  const steps = buildPreparedPlan(manifest, { context, lanes });
  if (listing) {
    console.log(`Candidate ${context.candidate}; PR ${context.pr}${context.provisional ? ' (provisional)' : ''}; base ${context.base}`);
    console.log('Execution: bash with errexit and pipefail; source tmp/ui-checks/env.sh after preparation; Gradle requires js-gradle-slot.');
    if (!pr) console.log("PR prediction: gh api 'repos/justsearch-app/justsearch/issues?state=all&sort=created&direction=desc&per_page=1' --jq '.[0].number' (+ 1, provisional)");
    for (const step of steps) console.log(`${step.id} [requires: ${(step.needs || []).join(', ') || 'none'}]${step.platform ? ` [${step.platform}]` : ''}${step.pythonVersion ? ` [requires Python ${step.pythonVersion}]` : ''}: ${step.command || `not checked here — ${step.reason}`}${step.reason && step.command ? ` [not checked here: ${step.reason}]` : ''}${step.outputs ? ` [regenerate: ${step.outputs.join(', ')}]` : ''}`);
    return;
  }
  assertIgnoredOutput('tmp/preflight');
  const logDir = path.join(ROOT, 'tmp/preflight');
  fs.mkdirSync(logDir, { recursive: true });
  assertIgnoredOutput('tmp/preflight/event.json');
  assertIgnoredOutput('tmp/preflight/summary.json');
  const eventFile = path.join(logDir, 'event.json');
  fs.writeFileSync(eventFile, JSON.stringify({ number: context.pr, pull_request: { number: context.pr, base: { sha: context.base, ref: 'main' } } }));
  const env = { ...process.env, GITHUB_EVENT_NAME: 'pull_request', GITHUB_BASE_REF: 'main',
    PR_BASE_SHA: context.base, GITHUB_EVENT_PATH: eventFile, JUSTSEARCH_PR_CI: 'true',
    PLAYWRIGHT_SKIP_BROWSER_GC: '1', JS_GRADLE_SLOTS: '1', CARGO_HOME: path.join(ROOT, 'tmp/ui-checks/cargo-home') };
  delete env.GITHUB_OUTPUT;
  delete env.GITHUB_STEP_SUMMARY;
  const { resolveJdkHome } = require('../dev/lib/resolve-jdk.cjs');
  try { env.JAVA_HOME = resolveJdkHome(); } catch (error) { console.error(error.message); }
  const slot = spawnSync('which', ['js-gradle-slot'], { encoding: 'utf8' }).status === 0;
  const results = await executePreparedPlan(steps, { run: step => runPreparedCommand(step, { cwd: ROOT, env, logDir, slot }) });
  try { assertCleanCandidate(); }
  catch (error) { results.set('candidate-preservation', { status: 'failed', reason: error.message }); }
  console.log('\n' + formatPreparedSummary(results, context));
  fs.writeFileSync(path.join(logDir, 'summary.json'), JSON.stringify({ context, results: Object.fromEntries(results) }, null, 2) + '\n');
  process.exitCode = [...results.values()].some(result => result.status !== 'passed') ? 1 : 0;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) main().catch(error => { console.error(error.message); process.exitCode = 2; });
