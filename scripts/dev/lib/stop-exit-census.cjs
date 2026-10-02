/* Identity-bound, read-only exit observer. It never terminates a target process. */
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const identity = require('./process-identity.cjs');
const TERMINATION_INTENT_PREFIX = 'JUSTSEARCH_MANAGED_CHILD_TERMINATION ';

function fileTimeFromInstant(instant) {
  const match = /^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z$/.exec(instant ?? '');
  if (!match || !Number.isFinite(Date.parse(instant))) return null;
  const fraction = (match[2] ?? '').padEnd(9, '0');
  if (fraction.slice(7) !== '00') return null; // Windows process starts have 100 ns precision.
  return (BigInt(Date.parse(instant)) * 10000n + 116444736000000000n + BigInt(fraction.slice(3, 7))).toString();
}
function engineIdentity(pid, dataDir, table = identity.readProcessTable()) {
  if (!table.ok || !dataDir) return null;
  const row = table.table.find(p => Number(p.ProcessId) === Number(pid));
  const command = row?.CommandLine ?? '';
  if (!row?.CreationFileTimeUtc || !command.includes('HeadlessApp')
    || !command.replaceAll('\\', '/').toLowerCase().includes(dataDir.replaceAll('\\', '/').toLowerCase())) return null;
  const executable = /^"([^"]+)"|^(\S+)/.exec(command)?.slice(1).find(Boolean);
  return executable ? { pid: Number(pid), creationFileTimeUtc: row.CreationFileTimeUtc, executable,
    cmdlineFingerprint: command, role: 'engine', creationTimeSource: 'CIM' } : null;
}
async function engineIdentityAsync(pid, dataDir) {
  return engineIdentity(pid, dataDir, await identity.readProcessTableAsync());
}
function exitTargets(run, manifest, observed = []) {
  const targets = [], gaps = [];
  const engine = run.exitCensusEngine;
  if (engine && engine.pid === Number(run.pids?.backendRootPid) && manifest?.pid === engine.pid) targets.push(engine);
  else gaps.push({ role: 'engine', pid: run.pids?.backendRootPid, reason: 'missing spawn-bound Engine identity or manifest PID mismatch' });
  if (!Array.isArray(manifest?.children)) gaps.push({ reason: 'B11 current child registry unavailable' });
  for (const child of manifest?.children ?? []) {
    const creationFileTimeUtc = fileTimeFromInstant(child.startedAt);
    if (!Number.isSafeInteger(child.pid) || !creationFileTimeUtc || !path.isAbsolute(child.executable ?? '')
      || !['LLAMA_SERVER', 'EXTRACTION'].includes(child.kind)) {
      gaps.push({ pid: child.pid, id: child.id, reason: 'B11 child lacks exact PID/start/executable identity' }); continue;
    }
    // Java startInstant can have millisecond precision. Bind to the collector's exact
    // owned CIM identity at that same represented millisecond; never add a tolerance.
    const owned = observed.filter(p => {
      const exe = p.executable ?? p.ExecutablePath;
      const birth = p.creationFileTimeUtc ?? p.CreationFileTimeUtc;
      return Number(p.pid ?? p.ProcessId) === child.pid && typeof birth === 'string' && /^\d+$/.test(birth)
        && BigInt(birth) / 10000n === BigInt(creationFileTimeUtc) / 10000n
        && exe && path.resolve(exe).toLowerCase() === path.resolve(child.executable).toLowerCase();
    });
    const births = [...new Set(owned.map(p => p.creationFileTimeUtc ?? p.CreationFileTimeUtc))];
    if (births.length > 1) { gaps.push({ pid: child.pid, reason: 'multiple owned births in B11 startInstant precision; cannot attribute child' }); continue; }
    targets.push({ ...child, creationFileTimeUtc: births[0] ?? creationFileTimeUtc,
      creationTimeSource: births.length ? 'CIM' : 'B11.startInstant',
      role: child.kind === 'LLAMA_SERVER' ? 'llama-server' : 'extraction-child' });
  }
  const unique = [...new Map(targets.map(t => [`${t.pid}/${t.creationFileTimeUtc}`, t])).values()];
  for (const p of observed) {
    const pid = Number(p.pid ?? p.ProcessId), birth = p.creationFileTimeUtc ?? p.CreationFileTimeUtc;
    if (!unique.some(t => t.pid === pid && t.creationFileTimeUtc === birth)) gaps.push({ pid, creationFileTimeUtc: birth,
      role: p.role, reason: 'observed owned process absent from current B11 registry/spawn identity; registry removes completed children and contains no historical exit codes' });
  }
  return { targets: unique, gaps };
}
function readEngineTerminationIntents(file, offset = 0) {
  let fd;
  try {
    fd = fs.openSync(file, 'r');
    const length = fs.fstatSync(fd).size - offset;
    if (length <= 0 || length > 16 * 1024 * 1024) return []; // Missing/oversize evidence grants no exemption.
    const bytes = Buffer.alloc(length);
    const size = fs.readSync(fd, bytes, 0, length, offset);
    return bytes.subarray(0, size).toString('utf8').split(/\r?\n/).flatMap((line, index, lines) => {
      if (index === lines.length - 1 || !line.startsWith(TERMINATION_INTENT_PREFIX)) return [];
      try { return [{ ...JSON.parse(line.slice(TERMINATION_INTENT_PREFIX.length)), source: file }]; }
      catch { return []; }
    });
  } catch { return []; }
  finally { if (fd !== undefined) fs.closeSync(fd); }
}
function matchingEngineTerminationIntent(input, target, exit, intents = []) {
  const engine = input.targets.find(t => t.role === 'engine');
  if (!engine || target.kind !== 'EXTRACTION' || !Array.isArray(intents)) return null;
  return intents.find(intent => {
    if (!intent || typeof intent !== 'object') return false;
    const child = intent.child, ownerBirth = fileTimeFromInstant(intent.engineStartedAt);
    return intent.reason === 'graceful-close-fallback' && intent.enginePid === engine.pid
      && ownerBirth && typeof engine.creationFileTimeUtc === 'string' && /^\d+$/.test(engine.creationFileTimeUtc)
      && BigInt(ownerBirth) / 10000n === BigInt(engine.creationFileTimeUtc) / 10000n
      && child && typeof child.id === 'string' && child.id.trim() && child.id === target.id && child.kind === 'EXTRACTION' && child.pid === target.pid
      && fileTimeFromInstant(child.startedAt) && fileTimeFromInstant(child.startedAt) === fileTimeFromInstant(target.startedAt)
      && path.isAbsolute(child.executable ?? '')
      && path.resolve(child.executable).toLowerCase() === path.resolve(target.executable).toLowerCase()
      && Number.isSafeInteger(intent.requestedAtMs)
      && intent.requestedAtMs >= Math.max(Date.parse(child.startedAt), Date.parse(intent.engineStartedAt), input.observedAfterMs ?? 0)
      && intent.requestedAtMs <= exit.atMs;
  }) ?? null;
}
function exitResult(input, output, source) {
  const gaps = [...input.gaps, ...(output.gaps ?? [])], processExits = [];
  for (const target of input.targets) {
    const exit = output.processExits?.find(e => e.pid === target.pid && e.creationFileTimeUtc === target.creationFileTimeUtc
      && path.resolve(e.executable ?? '').toLowerCase() === path.resolve(target.executable).toLowerCase());
    if (!exit || !Number.isInteger(exit.exitCode) || !Number.isFinite(exit.atMs)) {
      gaps.push({ pid: target.pid, role: target.role, reason: 'identity-bound OS exit code/time unavailable through teardown' }); continue;
    }
    const intent = matchingEngineTerminationIntent(input, target, exit, output.engineTerminationIntents);
    // Only the documented forced-stop code is expected; a native exception remains abnormal.
    processExits.push({ ...exit, role: target.role, source,
      ...(intent ? { terminationIntent: intent } : {}),
      expectedTermination: exit.exitCode === 0 || exit.exitCode === 1
        && (output.forcedPids?.includes(exit.pid) === true || intent !== null) });
  }
  return { processExits, exitAccountingComplete: input.targets.length > 0 && !gaps.length,
    exitAccountingGaps: gaps, exitCensusFile: source,
    exitAccountingScope: 'spawn-bound Engine plus B11 registered children and supplied owned-process census' };
}
async function startStopExitCensus(run, dataDir, runDir, scopeFile, runtime = {}) {
  const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
  let manifest, observed = [], scopeError;
  try { manifest = read(path.join(dataDir, 'runtime/manifest.json')); } catch { /* named below */ }
  if (scopeFile) {
    try {
      const scope = read(scopeFile); observed = scope.processes ?? scope.snapshots?.flatMap(s => s.processes ?? []) ?? [];
    } catch (error) { scopeError = { reason: `owned-process census unavailable: ${error.message}` }; }
  }
  const input = exitTargets(run, manifest, observed);
  input.observedAfterMs = (runtime.now ?? Date.now)();
  const terminationLog = path.join(runDir, 'logs', 'backend.stderr.log');
  let terminationLogOffset = 0;
  try { terminationLogOffset = fs.statSync(terminationLog).size; } catch { /* no prior log */ }
  if (scopeError) input.gaps.push(scopeError);
  if (!scopeFile) input.gaps.push({ reason: 'no historical owned-process census supplied; B11 is a current registry, not an exit history' });
  const outputFile = path.join(runDir, 'process-exits.json');
  if (process.platform !== 'win32' && !runtime.launch) return { finish: async () => exitResult(input,
    { gaps: [{ reason: 'OS process-handle exit census requires Windows' }] }, outputFile) };
  const inputFile = path.join(runDir, 'exit-targets.json'), readyFile = path.join(runDir, 'exit-census-ready.json');
  const finishFile = path.join(runDir, 'exit-census-finish.json');
  for (const file of [readyFile, finishFile, outputFile]) fs.rmSync(file, { force: true });
  fs.writeFileSync(inputFile, JSON.stringify(input));
  const launch = runtime.launch ?? ((args) => spawn('powershell.exe', args, { windowsHide: true, stdio: 'ignore' }));
  const proc = launch(['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
    path.join(__dirname, 'stop-exit-census.ps1'), '-InputFile', inputFile, '-OutputFile', outputFile,
    '-ReadyFile', readyFile, '-FinishFile', finishFile]);
  let launchError;
  proc.once('error', error => { launchError = error.message; });
  const now = runtime.now ?? Date.now, pause = runtime.pause ?? (ms => new Promise(resolve => setTimeout(resolve, ms)));
  const by = now() + 15000;
  while (!fs.existsSync(readyFile) && !launchError && proc.exitCode == null && now() < by) await pause(50);
  if (!fs.existsSync(readyFile)) {
    proc.kill(); input.gaps.push({ reason: `exit observer failed before shutdown: ${launchError ?? 'ready deadline/early exit'}` });
  }
  return { finish: async forcedPids => {
    fs.writeFileSync(finishFile, JSON.stringify({ forcedPids }));
    const end = now() + 5000;
    while (!fs.existsSync(outputFile) && !launchError && proc.exitCode == null && now() < end) await pause(50);
    let output;
    try { output = read(outputFile); } catch { output = { gaps: [{ reason: 'exit observer did not finish through teardown' }] }; proc.kill(); }
    return exitResult(input, { ...output,
      engineTerminationIntents: readEngineTerminationIntents(terminationLog, terminationLogOffset) }, outputFile);
  } };
}
module.exports = { TERMINATION_INTENT_PREFIX, readEngineTerminationIntents, fileTimeFromInstant, engineIdentity, engineIdentityAsync, exitTargets, exitResult, startStopExitCensus };
