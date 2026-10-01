/** Hashed acquisition owner: deadlines, subprocesses, soak, background and cleanup. */
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { sourceDirt, sharedModelStore } from './e-acquisition-eligibility.mjs';
import { startOwned, activateChat } from './e-start-ready.mjs';
import { finalizeInputs, captureEncoderSessions } from './e-measured-inputs.mjs';
import { crashEvidence, projectCrashes } from './e-crash-evidence.mjs';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const write = (file, value) => { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n'); };
const filesUnder = dir => !fs.existsSync(dir) ? [] : fs.statSync(dir).isFile() ? [dir]
  : fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? filesUnder(path.join(dir, e.name)) : [path.join(dir, e.name)]);
export const invocationDeadline = group => Date.now() + (group === 'e2-e3-load' ? 3000000 : 3540000);
export async function waitForScope(file, deadline, runtime = {}) {
  const now = runtime.now ?? Date.now, exists = runtime.exists ?? fs.existsSync;
  const pause = runtime.pause ?? (ms => new Promise(resolve => setTimeout(resolve, ms)));
  const end = Math.min(deadline, now() + 30000);
  while (!exists(file)) {
    if (now() >= end) throw new Error(`Owned process scope not written within 30 s: ${file}`);
    await pause(Math.min(100, end - now()));
  }
}
export function markTeardown(record, now = Date.now()) {
  const window = record.metrics.soakWindow ??= {};
  window.teardownStartMs ??= now;
  if (Number.isFinite(window.endMs)) {
    window.plannedEndMs ??= window.endMs;
    window.endMs = Math.min(window.endMs, now);
  }
}
export function resolveCommand(command, bindings) {
  const replace = s => s.replace(/\$\{(\w+)\}/g, (_, key) => {
    if (bindings[key] === undefined) throw new Error(`Unresolved ${key}`);
    return bindings[key];
  });
  return { ...command, args: command.args?.map(replace), directory: command.directory && replace(command.directory),
    env: command.env && Object.fromEntries(Object.entries(command.env).map(([k, v]) => [k, replace(v)])) };
}
export function execute(command, context) {
  const log = path.join(context.raw, `${context.sequence++}-${command.label}`);
  const out = fs.openSync(`${log}.stdout`, 'w');
  const err = fs.openSync(`${log}.stderr`, 'w');
  const child = spawn(command.executable, command.args, { cwd: command.cwd,
    env: { ...process.env, ...command.env, JUSTSEARCH_SESSION_TOKEN: context.token ?? '' },
    stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  let stdout = '';
  child.stdout.on('data', b => { fs.writeSync(out, b); stdout += b; });
  child.stderr.on('data', b => fs.writeSync(err, b));
  const receipt = { ...command, startedAt: new Date().toISOString(), stdoutFile: `${log}.stdout`, stderrFile: `${log}.stderr` };
  context.record.commands.push(receipt);
  const complete = new Promise(resolve => {
    child.once('error', error => { receipt.error = error.message; });
    child.once('close', (code, signal) => {
      fs.closeSync(out); fs.closeSync(err);
      Object.assign(receipt, { code, signal, endedAt: new Date().toISOString() });
      resolve({ code, stdout, receipt });
    });
  });
  let expired = false;
  const timer = setTimeout(() => {
    expired = true;
    // The registered supervisor is stopped by its owned lifecycle, never by a PID kill.
    if (command.mode !== 'start') child.kill();
  }, Math.max(1, Math.min(context.deadline - Date.now(), (command.budgetSeconds ?? 3540) * 1000)));
  complete.finally(() => clearTimeout(timer));
  return { child, complete, stdout: () => stdout, expired: () => expired };
}

export async function runAcquisition(plan, context, bindings, services) {
  const { LiveCollector, crashExperiment, hangExperiment, childPathExperiment, projectChildPolicies, collect, CLAUSES, finalize } = services;
  const { record, raw, options } = context, groups = record.groups;
  try {
    fs.mkdirSync(path.join(raw, 'gc'), { recursive: true });
    for (const template of plan) {
      if (Date.now() >= context.deadline) throw new Error('Invocation deadline');
      const command = resolveCommand(template, bindings);
      if (command.mode === 'ai-activate') { await activateChat(command, context); continue; }
      if (command.mode === 'encoder-sessions') { await captureEncoderSessions(context, command.label); continue; }
      if (command.mode === 'fixture-reuse') {
        for (const capture of command.captures) {
          if (hash(fs.readFileSync(capture.file)) !== capture.sha256) throw new Error('PR 0b capture changed after pin check');
          const target = path.join(raw, 'fixture', path.basename(capture.file));
          fs.mkdirSync(path.dirname(target), { recursive: true });
          fs.copyFileSync(capture.file, target);
        }
        record.commands.push({ ...command, copiedAt: new Date().toISOString() });
        continue;
      }
      if (command.mode === 'instruments-start') {
        context.collector = await new LiveCollector(context, command).start(); continue;
      }
      if (command.mode === 'instruments-stop') {
        await context.collector.stop();
        if (groups.includes('E4')) { context.collector.projectE4(); context.instrumentedE4 = true; }
        context.collector = null; continue;
      }
      if (command.mode === 'crash-experiment') { await crashExperiment(context); continue; }
      if (command.mode === 'hang-experiment') { await hangExperiment(context, command); continue; }
      if (command.mode === 'child-path') { await childPathExperiment(context, command.reason); continue; }
      if (command.mode === 'end-samples') {
        fs.writeFileSync(path.join(command.directory, 'rss.stop'), 'stop');
        fs.writeFileSync(path.join(command.directory, 'status.stop'), 'stop');
        fs.writeFileSync(path.join(command.directory, 'admission.stop'), 'stop');
        for (const proc of context.background.splice(0)) {
          const result = await proc.complete;
          if (result.code !== 0) throw new Error(`Instrument failed: ${result.receipt.label}`);
        }
        continue;
      }
      for (const arg of command.args.filter(a => /\.(csv|tsv|json)$/.test(a) && a.startsWith(raw))) fs.mkdirSync(path.dirname(arg), { recursive: true });
      if (command.mode === 'start') {
        await startOwned(command, context, bindings, execute, sharedModelStore());
        record.dataDirs ??= []; record.dataDirs.push(context.dataDir);
        continue;
      }
      if (command.mode === 'background') {
        const scopeIndex = command.args.indexOf('-Scope');
        if (scopeIndex >= 0) await waitForScope(command.args[scopeIndex + 1], context.deadline);
        context.background.push(execute(command, context)); continue;
      }
      if (command.mode === 'soak') {
        await captureEncoderSessions(context, 'soak-window-start');
        const end = Date.now() + command.minutes * 60000;
        record.metrics.soakWindow = { startMs: end - command.minutes * 60000, endMs: end };
        if (end + 60000 > context.deadline) throw new Error('Insufficient one-hour budget for complete soak window');
        let cycle = 0;
        while (Date.now() < end) {
          const iteration = { ...command, label: `soak-cycle-${++cycle}`, outcomesFile: path.join(raw, 'soak', `search-cycle-${cycle}.jsonl`),
            args: [...command.args, '--reset', '--search-load-outcomes', path.join(raw, 'soak', `search-cycle-${cycle}.jsonl`)] };
          const cycleContext = { ...context, deadline: Math.min(context.deadline, end) };
          const execution = execute(iteration, cycleContext);
          context.sequence = cycleContext.sequence;
          const result = await execution.complete;
          if (execution.expired() && Date.now() >= end) {
            result.receipt.cancellationCause = 'fixed-window-end'; break;
          }
          if (result.code !== 0) throw new Error(`Soak cycle exited ${result.code}`);
        }
        record.metrics.measuredMinutes = command.minutes;
        await captureEncoderSessions(context, 'soak-window-end');
        continue;
      }
      if (command.mode === 'stop' && groups.includes('E4')) markTeardown(record);
      const result = await execute(command, context).complete;
      if (result.code !== 0 && command.label.startsWith('encoder-')) {
        // The request-time encoder probe is informational (no verdict clause). Its failure is recorded
        // evidence about the arm (main's searches timed out after the scripted window, 2026-10-01).
        record.gaps['encoder-probe'] = `${command.label} exited ${result.code}; see ${result.receipt.stderrFile}`;
        continue;
      }
      if (result.code !== 0) throw new Error(`${command.label} exited ${result.code}; see ${result.receipt.stderrFile}`);
      if (command.label === 'runner-status') {
        const status = JSON.parse(result.stdout);
        if (status.runId) throw new Error(`Shared stack occupied by ${status.runId}; no takeover`);
      } else if (command.label === 'dirty') {
        if (sourceDirt(result.stdout).length) throw new Error('Arm source tree is dirty; built revision is ambiguous');
      } else if (command.label === 'revision') {
        record.revision = result.stdout.trim();
        if (options.arm === 'main' && !record.revision.startsWith('ac1c93bf3')) throw new Error('MAIN revision differs from owner pin');
      }
      if (command.mode === 'stop') { context.owned = null; delete bindings.runId; context.token = null; }
    }
    collect(context);
    if (groups.includes('E5')) projectChildPolicies(record);
  } catch (error) {
    record.failure = error.message;
    process.exitCode = 1;
  } finally {
    if (context.collector) {
      try {
        await context.collector.stop();
        if (groups.includes('E4')) { context.collector.projectE4(); context.instrumentedE4 = true; }
      } catch (error) { record.failure = `${record.failure ?? ''}; collector cleanup: ${error.message}`; }
    }
    for (const proc of context.background) proc.child.kill();
    if (context.owned) {
      if (groups.includes('E4')) markTeardown(record);
      const command = resolveCommand(plan.find(c => c.mode === 'stop'), bindings);
      context.deadline = Math.min(context.deadline + 30000, Date.parse(record.startedAt) + 3590000);
      const stopped = await execute(command, context).complete;
      if (stopped.code !== 0) record.failure = `${record.failure ?? ''}; owned stop failed`;
    }
    // Retain diagnostics even when an analyzer/start/load step failed before normal projection.
    if (record.failure) {
      try { collect(context); } catch (error) { record.failure += `; evidence projection: ${error.message}`; }
    }
    record.endedAt = new Date().toISOString();
    if (groups.includes('E4')) {
      const stopped = record.commands.filter(c => c.mode === 'stop').at(-1);
      let stop = {}; try { stop = JSON.parse(fs.readFileSync(stopped?.stdoutFile, 'utf8')); } catch { /* no validated stop receipt */ }
      const censusFile = path.join(raw, 'soak/instruments.json');
      const census = fs.existsSync(censusFile) ? { ...JSON.parse(fs.readFileSync(censusFile)), source: censusFile } : undefined;
      record.metrics.crashEvidence = crashEvidence([...new Set([...(record.dataDirs ?? []), context.dataDir].filter(Boolean))], record.metrics.soakWindow, stop, stop.processExits ?? [], census);
      projectCrashes(record);
      record.additionalArtifacts.push(...record.metrics.crashEvidence.sources);
      record.additionalArtifacts.push(...(stop.processExits ?? []).map(e => e.source).filter(f => f && fs.existsSync(f)));
      if (stop.exitCensusFile && fs.existsSync(stop.exitCensusFile)) record.additionalArtifacts.push(stop.exitCensusFile);
      write(path.join(raw, 'crash-evidence.json'), record.metrics.crashEvidence);
    }
    record.rawFiles = [raw, ...record.additionalArtifacts].flatMap(filesUnder);
    for (const group of groups) for (const clause of CLAUSES[group]) {
      if (record.clauses[clause] === undefined && !record.gaps[clause]) record.gaps[clause] =
        'Named instrument did not produce a validated measurement for this clause';
    }
    try { finalizeInputs(context); } catch (e) { record.failure = `${record.failure ?? ''}; acquisition input identity: ${e.message}`; record.pairIdentity = null; process.exitCode = 1; }
    await finalize(context);
    write(record.recordFile, record);
    console.log(JSON.stringify({ id: record.id, recordFile: record.recordFile, failure: record.failure, gaps: record.gaps }, null, 2));
  }
}
