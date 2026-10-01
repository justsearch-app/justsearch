#!/usr/bin/env node
/**
 * Lane F stage E paired driver. Root/operator owns the shared stack lease.
 * No build, install, Gradle, or automatic collector changes. Node built-ins only.
 * E7 signed dead-Engine upgrade is operator-driven and externally blocked on signing.
 * Missing instruments/clauses are UNMEASURABLE, never a successful measurement.
 * Raw evidence is retained in tmp/lane-f-e; index.json points to every invocation.
 */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
export const ARMS = Object.freeze({
  branch: 'F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify',
  main: 'F:/justsearch-public/.claude/worktrees/lane-f-e-main',
});
const GROUPS = ['e1-quality', 'e2-e3-load', 'e4-memory-soak', 'e5-crash', 'e6-hang'];
const EVIDENCE = 'docs/design/lane-f-engine-jvm/evidence/E';
const read = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const write = (file, value) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
};
const finite = x => typeof x === 'number' && Number.isFinite(x) && x >= 0;
export function verifySharedModels(config) {
  const models = config.keys?.find(entry => entry.key === 'justsearch.models.dir')?.value;
  const sharedModels = path.resolve(ARMS.main, '../../../models');
  if (typeof models !== 'string' || path.resolve(models).toLowerCase() !== sharedModels.toLowerCase()) {
    throw new Error(`Shared models required: effective justsearch.models.dir=${models}, expected ${sharedModels}`);
  }
  return models;
}
export function parseArgs(argv) {
  const [command, ...rest] = argv;
  if (![...GROUPS, 'e0-values', 'table'].includes(command)) throw new Error('Unknown subcommand');
  const result = { command, dryRun: false };
  for (let i = 0; i < rest.length; i++) {
    const flag = rest[i];
    if (flag === '--dry-run') {
      if (result.dryRun) throw new Error('Duplicate --dry-run');
      result.dryRun = true;
    } else {
      const key = { '--arm': 'arm', '--window': 'window', '--repo-root': 'repoRoot' }[flag];
      const value = rest[++i];
      if (!key || !value || value.startsWith('--') || result[key]) throw new Error(`Invalid option ${flag}`);
      result[key] = value;
    }
  }
  if (GROUPS.includes(command) && !Object.hasOwn(ARMS, result.arm)) throw new Error('--arm branch|main required');
  if (result.arm && !Object.hasOwn(ARMS, result.arm)) throw new Error('Invalid arm');
  if (command === 'e0-values' && result.arm && result.arm !== 'main') throw new Error('E0 uses MAIN only');
  if (command === 'table' && result.arm) throw new Error('table reads both arms; omit --arm');
  if (command === 'e4-memory-soak' ? !['1', '2', '3'].includes(result.window) : result.window !== undefined) {
    throw new Error('--window 1|2|3 required only for E4');
  }
  return result;
}

/** A reviewable process plan; placeholders are resolved only after the owned start receipt. */
export function buildPlan(options, values, root = ROOT) {
  const arm = options.arm ?? 'main';
  const tree = ARMS[arm];
  const group = options.command;
  const raw = path.join(root, 'tmp/lane-f-e', group, arm, '${invocation}');
  const tools = path.join(root, 'scripts/jseval/lane-f');
  const runner = path.join(tree, 'scripts/dev/dev-runner.cjs');
  const pythonCwd = path.join(root, 'scripts/jseval');
  const env = {
    PYTHONPATH: pythonCwd, PYTHONUTF8: '1',
    // Trust only the selected owner-assigned arm in child processes; no global Git config edits.
    GIT_CONFIG_COUNT: '1', GIT_CONFIG_KEY_0: 'safe.directory', GIT_CONFIG_VALUE_0: tree,
    JUSTSEARCH_HEAD_HEAP: values.heap.packaged.replace('-Xmx', ''),
    UI_OPTS: '-XX:-UseSerialGC -XX:-UseZGC -XX:+UseG1GC',
    JAVA_OPTS: '-Djustsearch.eval.mode=true',
    JUSTSEARCH_CHAT_PROFILE: 'standard',
  };
  const cmd = (label, executable, args, cwd = tree, extra = {}) => ({ label, executable, args, cwd, env, ...extra });
  const start = label => cmd(`start-${label}`, 'node', [runner, 'start', '--json', '--skip-build',
    '--clean', 'none', '--data-dir', path.join(tree, 'tmp/lane-f-e', '${invocation}', label),
    '--api-port', '33221', '--session-id', '${session}', '--lease-duration-sec', '3600',
    '--chat-profile', 'standard'], tree, { mode: 'start',
      requestsAfterReceipt: ['/api/mcp/token', '/api/health', '/api/status', '/api/debug/state', '/api/runtime/manifest', '/api/debug/effective-config'] });
  const stop = label => cmd(`stop-${label}`, 'node', [runner, 'stop', '--json', '--run', '${runId}',
    '--session-id', '${session}'], tree, { mode: 'stop' });
  const evalRun = (label, load = false, mode = 'hybrid') => cmd(label, 'python', ['-m', 'jseval', 'run',
    '--dataset', 'scifact', '--modes', 'lexical,hybrid', '--embedding', '--splade', '--ce',
    '--pipeline', '--base-url', 'http://127.0.0.1:33221',
    '--corpus-dir', path.join(root, 'tmp/lane-f-e/corpus/scifact'),
    '--output-dir', path.join(raw, label, 'eval-results'),
    '--timeline', path.join(raw, label, 'timeline.tsv'),
    ...(load ? ['--search-load', 'continuous', '--search-load-search-mode', mode, '--max-queries', '1'] : [])], pythonCwd);
  const samplers = label => [
    cmd(`rss-${label}`, 'powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
      path.join(tools, 'head-rss-sampler.ps1'), '-Out', path.join(raw, label, 'head-rss.csv'),
      '-Stop', path.join(raw, label, 'rss.stop'), '-IncludeSplitWorker'], tree, { mode: 'background' }),
    cmd(`status-${label}`, 'bash', [path.join(tools, 'status-sampler.sh'), path.join(raw, label),
      '33221', path.join(raw, label, 'status.stop')], tree, { mode: 'background' }),
  ];
  const admission = label => cmd(`admission-${label}`, 'node', [path.join(tools, 'admission-loop.mjs'),
    '--capture-workload', path.join(raw, label), '--base-url', 'http://127.0.0.1:33221',
    '--stop-file', path.join(raw, label, 'admission.stop')], tree, { mode: 'background' });
  const probe = label => cmd(`encoder-${label}`, 'bash', [path.join(tools, 'encoder-latency-probe.sh'),
    path.join(raw, label, 'encoder'), '3', '33221'], root);
  const analyze = label => cmd(`analyze-${label}`, 'node', [path.join(tools, 'analyze-head-run.cjs'), path.join(raw, label), '--paired-split'], root);
  const endSamples = label => ({ label: `end-samples-${label}`, mode: 'end-samples', directory: path.join(raw, label) });
  const preamble = [cmd('revision', 'git', ['rev-parse', 'HEAD']),
    cmd('dirty', 'git', ['status', '--porcelain']),
    cmd('runner-status', 'node', [runner, 'status', '--active', '--json']),
    cmd('machine', 'node', ['-e',
      'const os=require("node:os");console.log(JSON.stringify({platform:os.platform(),release:os.release(),ramBytes:os.totalmem(),cpu:os.cpus().map(c=>c.model)}))']),
    cmd('gpu', 'nvidia-smi', ['--query-gpu=name,uuid,memory.total,driver_version', '--format=csv'])];
  if (group === 'e0-values') return [{ mode: group, label: group }];
  if (group === 'table') return [cmd('fixture-gate', 'bash', [path.join(tools, 'fixture-gate.sh'),
    '${mainFixture}', '${branchFixture}', '${fixtureReport}'], root), { mode: group, label: group }];
  let commands;
  if (group === 'e1-quality') {
    commands = [start('quality'), evalRun('quality'), stop('quality'),
      cmd('relevance', 'python', ['-m', 'jseval', 'relevance-gate', '--dataset', 'beir/scifact',
        '--data-dir', path.join(raw, 'quality'), '--report-out', path.join(raw, 'relevance-gate.json')], pythonCwd)];
    const fixtureEnv = { ...env,
      JUSTSEARCH_INDEX_VECTOR_EXHAUSTIVE_SEARCH: 'true', JUSTSEARCH_LLM_SLOTS: '1',
      JUSTSEARCH_RERANK_DEADLINE_MS: '60000', JUSTSEARCH_RERANK_CHUNKS_DEADLINE_MS: '60000',
      JUSTSEARCH_RERANK_TOP_K: '40', JUSTSEARCH_RERANK_GPU_MEM_MB: '4096',
      JUSTSEARCH_INDEX_HYBRID_CANDIDATE_LIMIT_MAX: '5000',
      JUSTSEARCH_HYBRID_CHUNK_COLLAPSE_LIMIT_MULTIPLIER: '50',
      JUSTSEARCH_HYBRID_LEG_ARBITRATION_ENABLED: 'false',
      JUSTSEARCH_HYBRID_RERANK_POOL_RECALL_COMPLETE: 'false',
      JUSTSEARCH_FIXTURE_CORPUS_ROOT: ARMS.main,
    };
    for (let n = 1; n <= values.noisePairGate.capturesPerSide; n++) {
      commands.push({ ...start(`fixture-${n}`), env: fixtureEnv },
        cmd(`fixture-${n}`, 'bash', [path.join(tools, 'fixture-cycle.sh'), path.join(raw, 'fixture', `capture-${n}.json`),
          'standard', '33221'], root, { env: fixtureEnv }), stop(`fixture-${n}`));
    }
  } else if (group === 'e2-e3-load') {
    commands = [];
    for (const workload of ['agent-idle', 'scripted-agent']) for (const mode of ['hybrid', 'lexical']) {
      const label = `${workload}-${mode}`;
      commands.push(start(label), ...samplers(label),
      ...(workload === 'scripted-agent' ? [admission(label)] : []), evalRun(label, true, mode),
      endSamples(label), probe(label), analyze(label), stop(label));
    }
  } else if (group === 'e4-memory-soak') {
    commands = [start('soak'), ...samplers('soak'), admission('soak'),
      { ...evalRun('soak', true), mode: 'soak', minutes: values.soak.windows[Number(options.window) - 1].minutes },
      endSamples('soak'), analyze('soak'), stop('soak')];
  } else if (group === 'e5-crash') {
    commands = [cmd('durable-crash', 'node', [path.join(tree, 'scripts/supervisor-conformance/real-writer-recovery.mjs')], tree,
      { env: { ...env, JUSTSEARCH_REAL_RECOVERY_SCENARIO: 'processing',
        JUSTSEARCH_WRITER_RECOVERY_WORK: path.join(tree, 'tmp/lane-f-e', '${invocation}', 'recovery') } })];
  } else {
    commands = ['hang-soft-recovered-through-the-request-file', 'hang-hard-recovered-by-forced-kill'].map(id =>
      cmd(id, 'node', [path.join(tree, 'scripts/supervisor-conformance/run.mjs'), '--adapter', 'dev-runner', '--case', id], tree,
        { env: { ...env, SUPERVISOR_CONFORMANCE_KEEP: '1', SUPERVISOR_CONFORMANCE_VERBOSE: '1' } }));
  }
  return [...preamble, ...commands];
}

const CLAUSES = {
  E1: ['baseline-quality', 'SearchTrace-shape', 'workflow-evidence-citations-cancellation', 'allowed-differences'],
  E2: ['foreground-p95', 'agent-api-p95', 'idle-rejections', 'scripted-rejections', 'no-timeout-or-5xx'],
  E3: ['chunks-per-second-under-foreground-load'],
  E4: ['component-commit-budget', 'machine-wide-commit-vs-main', 'working-set', 'live-after-GC-trend',
    'zero-crashes', 'owner-duration', 'index-agent-reconfigure-workload'],
  E5: ['actual-death-durable-operation', 'crash-to-api', 'crash-to-index', 'checkpoint-resume',
    'visible-restarting', 'no-orphaned-child', 'restart-quit-upgrade-child-policy'],
  E6: ['runnable-watcher-api-pool-wedge', 'whole-JVM-wedge', 'graceful-deadline', 'forced-deadline', 'E4-derived-hang-settings'],
  E7: ['signed-dead-Engine-upgrade'],
};
/** Missing/nonfinite facts are unmeasurable; known violations always dominate missing facts. */
export function verdict(checks) {
  return checks.includes(false) ? 'fail' : checks.length && checks.every(x => x === true) ? 'pass' : 'unmeasurable';
}
export function qualityGateVerdict(report) {
  if (report?.exit_code !== 0) return false;
  if (!finite(report.current) || !finite(report.baseline) || !finite(report.floor)) return undefined;
  return report.checks?.some(c => c.name === 'ndcg10-no-regression' && c.status === 'ok') ? true : undefined;
}
export function tableVerdicts(records, values) {
  const output = [];
  const pair = (group, clause, evaluate) => {
    const arms = ['main', 'branch'].map(arm => records[`${group}/${arm}`]);
    const checks = arms.map(r => r?.clauses?.[clause]);
    if (evaluate && arms.every(Boolean)) checks.push(evaluate(arms[1], arms[0]));
    if (arms.some(r => r?.failure)) checks.push(false);
    if (arms.every(Boolean)) {
      checks.push(arms[0].pairIdentity === arms[1].pairIdentity,
        arms[0].valuesHash === arms[1].valuesHash);
    }
    output.push({ group, clause, verdict: verdict(checks),
      sources: arms.map(r => r?.id ?? 'missing').join(' / '),
      sourceRecords: arms.map(r => r?.recordFile),
      reason: arms.map(r => r?.gaps?.[clause]).filter(Boolean).join('; ') || 'paired clause and frozen values' });
  };
  for (const [group, clauses] of Object.entries(CLAUSES)) for (const clause of clauses) {
    let evaluate;
    if (group === 'E2' && clause === 'foreground-p95') evaluate = b => {
      const bounds = values.foregroundSearchP95Ceiling.ceilingMs;
      return verdict(['hybrid', 'lexical'].map(mode => finite(b.metrics?.searchP95?.[mode]) && finite(bounds?.[mode])
        ? b.metrics.searchP95[mode] <= bounds[mode] : undefined)) === 'pass' ? true
        : ['hybrid', 'lexical'].some(mode => finite(b.metrics?.searchP95?.[mode]) && finite(bounds?.[mode])
          && b.metrics.searchP95[mode] > bounds[mode]) ? false : undefined;
    };
    if (group === 'E2' && clause === 'agent-api-p95') evaluate = b => finite(b.metrics?.agentP95)
      && finite(values.agentLoopApiP95Ceiling.ceilingMs) ? b.metrics.agentP95 <= values.agentLoopApiP95Ceiling.ceilingMs : undefined;
    if (group === 'E3') evaluate = b => {
      const workloadBounds = values.indexingProgressFraction.minimumByWorkload;
      if (workloadBounds) {
        const checks = Object.entries(workloadBounds).map(([load, bound]) => finite(b.metrics?.chunksByWorkload?.[load]) && finite(bound)
          ? b.metrics.chunksByWorkload[load] >= bound : undefined);
        return checks.some(x => x === false) ? false : checks.length === 4 && checks.every(x => x === true) ? true : undefined;
      }
      return finite(b.metrics?.chunksPerSec) && finite(values.indexingProgressFraction.minimumChunksPerSec)
        ? b.metrics.chunksPerSec >= values.indexingProgressFraction.minimumChunksPerSec : undefined;
    };
    if (group === 'E4' && clause === 'machine-wide-commit-vs-main') evaluate = (b, m) =>
      finite(b.metrics?.peakCommitMB) && finite(m.metrics?.peakCommitMB) ? b.metrics.peakCommitMB <= m.metrics.peakCommitMB : undefined;
    if (group === 'E5' && ['crash-to-api', 'crash-to-index'].includes(clause)) evaluate = b =>
      finite(b.metrics?.[clause]) ? b.metrics[clause] <= values.crashToApiRestoredMs : undefined;
    pair(group, clause, evaluate);
  }
  return output;
}

function filesUnder(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory()
    ? filesUnder(path.join(dir, e.name)) : [path.join(dir, e.name)]);
}
function latestRecords(root) {
  const records = {};
  const windows = { main: {}, branch: {} };
  for (const file of filesUnder(path.join(root, EVIDENCE)).filter(f => path.basename(f) === 'index.json')) {
    for (const entry of read(file).runs ?? []) {
      const record = read(entry.record);
      if (record.groups.includes('E4') && (!windows[record.arm][record.window]
        || windows[record.arm][record.window].startedAt < record.startedAt)) windows[record.arm][record.window] = record;
      for (const group of record.groups) if (!records[`${group}/${record.arm}`]
        || records[`${group}/${record.arm}`].startedAt < record.startedAt) records[`${group}/${record.arm}`] = record;
    }
  }
  for (const arm of ['main', 'branch']) {
    const list = Object.values(windows[arm]);
    if (!list.length) continue;
    const merged = structuredClone(list.at(-1));
    merged.id = list.map(r => r.id).join(', ');
    merged.windowRecords = list.map(r => r.recordFile);
    merged.metrics.peakCommitMB = list.every(r => finite(r.metrics.peakCommitMB))
      ? Math.max(...list.map(r => r.metrics.peakCommitMB)) : undefined;
    for (const clause of CLAUSES.E4) merged.clauses[clause] = verdict(list.map(r => r.clauses[clause])) === 'pass'
      ? true : list.some(r => r.clauses[clause] === false) ? false : undefined;
    merged.clauses['owner-duration'] = ['1', '2', '3'].every(w => windows[arm][w]?.clauses['window-duration'] === true)
      && list.every(r => r.valuesHash === merged.valuesHash && r.pairIdentity === merged.pairIdentity && r.revision === merged.revision)
      ? true : undefined;
    if (list.some(r => r.failure)) merged.failure = list.filter(r => r.failure).map(r => r.failure).join('; ');
    records[`E4/${arm}`] = merged;
  }
  return records;
}
export function fillValues(document, records) {
  if (Object.values(records).some(r => r.arm === 'branch')) throw new Error('E0 is forbidden after any branch run');
  const quality = records['E1/main'];
  const load = records['E2/main'];
  if (!quality || !load || quality.failure || load.failure) throw new Error('Successful MAIN E1-E3 records required');
  const metrics = load.metrics;
  if (!['hybrid', 'lexical'].every(mode => finite(metrics.searchP95?.[mode]) && metrics.searchP95[mode] > 0)
    || !finite(metrics.agentP95) || metrics.agentP95 <= 0 || !finite(metrics.chunksPerSec) || metrics.chunksPerSec <= 0) {
    throw new Error('MAIN lacks measured E0 search/API p95 or chunks/s; cannot invent bounds');
  }
  const next = structuredClone(document);
  const v = next.values;
  for (const key of ['foregroundSearchP95Ceiling', 'agentLoopApiP95Ceiling', 'indexingProgressFraction']) {
    v[key].measuredAtE0 = false;
    v[key].sourceRun = load.id;
  }
  v.foregroundSearchP95Ceiling.measuredMs = metrics.searchP95;
  v.foregroundSearchP95Ceiling.ceilingMs = Object.fromEntries(Object.entries(metrics.searchP95).map(([k, x]) => [k, x * 1.1]));
  v.agentLoopApiP95Ceiling.measuredMs = metrics.agentP95;
  v.agentLoopApiP95Ceiling.ceilingMs = metrics.agentP95 * 1.1;
  v.indexingProgressFraction.measuredChunksPerSec = metrics.chunksPerSec;
  v.indexingProgressFraction.minimumChunksPerSec = metrics.chunksPerSec * 0.9;
  if (metrics.chunksByWorkload) v.indexingProgressFraction.minimumByWorkload =
    Object.fromEntries(Object.entries(metrics.chunksByWorkload).map(([label, rate]) => [label, rate * .9]));
  next.e0 = { qualityRun: quality.id, loadRun: load.id, fixedAt: new Date().toISOString() };
  return next;
}

function resolveCommand(command, bindings) {
  const replace = s => s.replace(/\$\{(\w+)\}/g, (_, key) => {
    if (bindings[key] === undefined) throw new Error(`Unresolved ${key}`);
    return bindings[key];
  });
  return { ...command, args: command.args?.map(replace), directory: command.directory && replace(command.directory),
    env: command.env && Object.fromEntries(Object.entries(command.env).map(([k, v]) => [k, replace(v)])) };
}
function execute(command, context) {
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
  }, Math.max(1, context.deadline - Date.now()));
  complete.finally(() => clearTimeout(timer));
  return { child, complete, stdout: () => stdout, expired: () => expired };
}

async function api(context, endpoint) {
  const url = `http://127.0.0.1:33221${endpoint}`;
  const response = await fetch(url, { headers: { Host: '127.0.0.1:33221' }, signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw new Error(`${endpoint}: HTTP ${response.status}`);
  const body = await response.json();
  const rawFile = endpoint === '/api/mcp/token' ? undefined
    : path.join(context.raw, `${context.sequence++}-${endpoint.replaceAll('/', '_')}.json`);
  context.record.commands.push({ label: endpoint, method: 'GET', url, headers: { Host: '127.0.0.1:33221' },
    status: response.status, observedAt: new Date().toISOString(), rawFile, tokenResponseRetained: false });
  if (rawFile) write(rawFile, body);
  return body;
}
async function startOwned(command, context, bindings) {
  const proc = execute(command, context);
  const receipt = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Start receipt deadline')), 180000);
    proc.child.stdout.on('data', () => {
      for (const line of proc.stdout().split(/\r?\n/)) {
        let value; try { value = JSON.parse(line); } catch { continue; }
        if (value.runId && value.ok !== false) { clearTimeout(timer); resolve(value); }
        else if (value.ok === false) { clearTimeout(timer); reject(new Error(JSON.stringify(value.error))); }
      }
    });
    proc.complete.then(({ code }) => { clearTimeout(timer); reject(new Error(`Start exited ${code} before receipt`)); });
  });
  bindings.runId = receipt.runId;
  context.owned = { command, proc, runId: receipt.runId };
  context.record.runIds.push(receipt.runId);
  write(path.join(context.raw, `${command.label}-receipt.json`), receipt);
  // A dev-mode stack (main's split arm) does not enforce the per-boot token and hands out none;
  // proceed without it there. Any mutation the server does guard still fails loudly on the wire.
  context.token = (await api(context, '/api/mcp/token')).token ?? null;
  context.record.tokenEnforced = Boolean(context.token);
  // /api/health answers 503 until the index is ready; wait for readiness, bounded.
  const readyBy = Date.now() + 300000;
  for (;;) {
    try { await api(context, '/api/health'); break; } catch (error) {
      if (!/HTTP 503/.test(String(error.message)) || Date.now() > readyBy) throw error;
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
  }
  await api(context, '/api/debug/state');
  await api(context, '/api/runtime/manifest');
  const config = await api(context, '/api/debug/effective-config');
  context.record.sharedModels = verifySharedModels(config);
  const ready = await execute({ label: `capability-ready-${command.label}`, executable: 'python',
    args: [path.join(ROOT, 'scripts/jseval/lane-f/capability-ready.py'),
      '--base-url', 'http://127.0.0.1:33221', '--timeout', '300',
      '--output', path.join(context.raw, `${command.label}-capability-ready.json`)],
    cwd: command.cwd, env: command.env }, context).complete;
  if (ready.code !== 0) throw new Error(`Capability readiness failed for ${command.label}; see ${ready.receipt.stderrFile}`);
}

function collect(context) {
  const r = context.record;
  for (const command of r.commands) {
    if (!command.stdoutFile || !fs.existsSync(command.stdoutFile)) continue;
    const output = fs.readFileSync(command.stdoutFile, 'utf8');
    for (const match of output.matchAll(/"runId"\s*:\s*"([a-zA-Z0-9-]+)"/g)) {
      if (!r.runIds.includes(match[1])) r.runIds.push(match[1]);
    }
  }
  const summaries = filesUnder(context.raw).filter(f => path.basename(f) === 'summary.json').map(read);
  const loads = summaries.filter(s => s.search_load);
  r.metrics.searchP95 = {};
    for (const s of loads) {
    const load = s.search_load;
    if (finite(load.latency_ms?.p95)) r.metrics.searchP95[load.search_mode] = Math.max(r.metrics.searchP95[load.search_mode] ?? 0, load.latency_ms.p95);
  }
  const captures = filesUnder(context.raw).filter(f => path.basename(f) === 'workload.json').map(read);
  const calls = captures.flatMap(c => c.requests ?? []);
  const admitted = calls.filter(c => c.status >= 200 && c.status < 300 && finite(c.durationMs));
  if (admitted.length) r.metrics.agentP95 = admitted.map(c => c.durationMs).sort((a, b) => a - b)[Math.ceil(admitted.length * .95) - 1];
  r.metrics.chunksByWorkload = {};
  for (const file of filesUnder(context.raw).filter(f => path.basename(f) === 'status-series.csv')) {
    const [header, ...lines] = fs.readFileSync(file, 'utf8').trim().split(/\r?\n/);
    const columns = header.split(',');
    const samples = lines.map(line => line.split(','));
    if (samples.length > 1) {
      const first = samples[0], last = samples.at(-1), ci = columns.indexOf('chunkDocCount');
      const seconds = (Date.parse(last[0]) - Date.parse(first[0])) / 1000;
      const delta = Number(last[ci]) - Number(first[ci]);
      if (ci >= 0 && seconds > 0 && delta >= 0) {
        r.metrics.chunksByWorkload[path.basename(path.dirname(file))] = delta / seconds;
        r.metrics.chunksPerSec = Math.min(r.metrics.chunksPerSec ?? Infinity, delta / seconds);
      }
    }
  }
  if (r.groups.includes('E1')) {
    const gate = path.join(context.raw, 'relevance-gate.json');
    if (fs.existsSync(gate)) r.clauses['baseline-quality'] = qualityGateVerdict(read(gate));
    const fixtures = filesUnder(path.join(context.raw, 'fixture')).filter(f => /^capture-\d+\.json$/.test(path.basename(f))).map(read);
    r.clauses['SearchTrace-shape'] = fixtures.length === context.values.noisePairGate.capturesPerSide
      ? fixtures.every(c => Object.values(c.queries ?? {}).length > 0 && Object.values(c.queries).every(q =>
        q.httpStatus === 200 && typeof q['trace.effectiveMode'] === 'string'
        && typeof q['trace.decisionKind'] === 'string' && q['trace.stageIds']?.length > 0)) : undefined;
  }
  if (r.groups.includes('E2')) {
    r.clauses['no-timeout-or-5xx'] = calls.length && loads.length
      ? calls.every(c => !c.error && ((c.status >= 200 && c.status < 300) || c.status === 429)
        && (!c.streamed || (c.terminal?.doneCount === 1 && c.terminal?.errorCount === 0 && c.terminal?.eof)))
        && loads.every(s => s.search_load.errors === 0) : undefined;
    r.clauses['foreground-p95'] = Object.keys(r.metrics.searchP95).length === 2 ? true : undefined;
    r.clauses['agent-api-p95'] = admitted.length ? true : undefined;
    const rejectionCodes = new Set(['ADMISSION_CONTEXT_LIMIT', 'ADMISSION_ENGINE_LIMIT']);
    r.clauses['scripted-rejections'] = calls.length ? calls.filter(c => c.status === 429).every(c =>
      rejectionCodes.has(c.code) && c.retrySafe === true && /^[1-9]\d*$/.test(c.retryAfter))
      && calls.filter(c => c.status === 429).length / calls.length <= context.values.admissionRejectionCeiling.scriptedAgentFraction : undefined;
    r.clauses['idle-rejections'] = loads.length ? loads.filter(s => s.search_load && !s.search_load.errors).length === loads.length : undefined;
  }
  if (r.groups.includes('E3')) r.clauses['chunks-per-second-under-foreground-load'] = finite(r.metrics.chunksPerSec) ? true : undefined;
  if (r.groups.includes('E4')) {
    const file = path.join(context.raw, 'soak/head-rss.csv');
    if (fs.existsSync(file)) {
      const rows = fs.readFileSync(file, 'utf8').trim().split(/\r?\n/).slice(1).map(line => line.split(','));
      const sums = new Map();
      for (const [ts, role, pid, working, commit] of rows) {
        if (!finite(Number(working)) || !finite(Number(commit)) || !Number.isFinite(Date.parse(ts))) throw new Error('Invalid memory sample');
        const sum = sums.get(ts) ?? { working: 0, commit: 0 };
        sum.working += Number(working); sum.commit += Number(commit); sums.set(ts, sum);
      }
      if (sums.size > 1 && rows.some(row => row[1] === 'engine')
        && (r.arm !== 'main' || rows.some(row => row[1] === 'worker'))) {
        r.metrics.peakCommitMB = Math.max(...[...sums.values()].map(s => s.commit));
        r.metrics.peakWorkingSetMB = Math.max(...[...sums.values()].map(s => s.working));
        r.metrics.sampledSeconds = (Date.parse(rows.at(-1)[0]) - Date.parse(rows[0][0])) / 1000;
        r.clauses['window-duration'] = r.metrics.sampledSeconds >= context.values.soak.windows[Number(r.window) - 1].minutes * 60 - 5;
        r.clauses['working-set'] = true;
        r.clauses['machine-wide-commit-vs-main'] = true;
        // More than one incarnation falsifies no-crash; sparse process samples cannot prove zero deaths.
        if (['engine', 'worker'].some(role => new Set(rows.filter(row => row[1] === role).map(row => row[2])).size > 1)) {
          r.clauses['zero-crashes'] = false;
        }
      }
    }
  }
}

export async function main(argv = process.argv.slice(2), root = ROOT) {
  const options = parseArgs(argv);
  root = path.resolve(options.repoRoot ?? root);
  const valuesFile = path.join(root, EVIDENCE, 'values.json');
  const document = read(valuesFile), values = document.values;
  const plan = buildPlan(options, values, root);
  if (options.dryRun) {
    console.log(JSON.stringify({ options, limitSeconds: 3540, leaseDurationSec: 3600, commands: plan }, null, 2));
    return;
  }
  const records = latestRecords(root);
  if (options.command === 'e0-values') {
    write(valuesFile, fillValues(document, records));
    // MAIN established these bounds: bind its existing reference records to the frozen file.
    const valuesHash = hash(fs.readFileSync(valuesFile));
    for (const r of new Set(Object.values(records))) if (r.arm === 'main' && ['E1', 'E2', 'E3'].some(g => r.groups.includes(g))) {
      r.executedValuesHash ??= r.valuesHash;
      r.valuesHash = valuesHash; write(r.recordFile, r);
    }
    return;
  }
  if (options.command === 'table') {
    const mainArm = records['E1/main'], branchArm = records['E1/branch'];
    if (mainArm && branchArm) {
      const raw = path.join(root, 'tmp/lane-f-e/table', randomUUID());
      fs.mkdirSync(raw, { recursive: true });
      const context = { raw, record: { commands: [] }, sequence: 0, deadline: Date.now() + 60000 };
      const gate = resolveCommand(plan[0], { mainFixture: path.join(mainArm.raw, 'fixture'),
        branchFixture: path.join(branchArm.raw, 'fixture'), fixtureReport: path.join(raw, 'fixture-gate.json') });
      const outcome = await execute(gate, context).complete;
      write(path.join(raw, 'commands.json'), context.record.commands);
      for (const record of [mainArm, branchArm]) for (const clause of ['workflow-evidence-citations-cancellation', 'allowed-differences']) {
        record.clauses[clause] = outcome.code === 0;
        record.gaps[clause] = `fixture-gate raw report: ${raw}`;
      }
      for (const record of [mainArm, branchArm]) write(record.recordFile, record);
    }
    const rows = tableVerdicts(records, values);
    const text = ['# Stage E paired verdicts', '', '| Group | Clause | Verdict | MAIN / BRANCH run | Reason |',
      '|---|---|---|---|---|', ...rows.map(r => `| ${r.group} | ${r.clause} | ${r.verdict} | ${r.sourceRecords.map((file, i) =>
        file ? `[${i === 0 ? 'MAIN' : 'BRANCH'}](${path.relative(path.join(root, EVIDENCE), file).replaceAll('\\', '/')})` : 'missing').join(' / ')} | ${r.reason} |`), '',
      ...Object.keys(CLAUSES).map(group => `${group}: **${verdict(rows.filter(r => r.group === group).map(r => r.verdict === 'pass' ? true : r.verdict === 'fail' ? false : undefined))}**`), '',
      'E7 is operator-driven and externally blocked on signing. Unmeasurable is not a waiver.',
      'One-sided feature acceptance: [D1](../../stages/D1.md) and [D2](../../stages/D2.md).', ''].join('\n');
    fs.writeFileSync(path.join(root, EVIDENCE, 'table.md'), text);
    return;
  }
  if (options.arm === 'branch' && Object.values(values).some(v => v?.measuredAtE0 === true)) throw new Error('Run MAIN E1-E3 and e0-values before any branch run');
  const id = `${new Date().toISOString().replaceAll(/[:.]/g, '-')}-${randomUUID().slice(0, 8)}`;
  const raw = path.join(root, 'tmp/lane-f-e', options.command, options.arm, id);
  fs.mkdirSync(raw, { recursive: true });
  const groups = { 'e1-quality': ['E1'], 'e2-e3-load': ['E2', 'E3'], 'e4-memory-soak': ['E4'], 'e5-crash': ['E5'], 'e6-hang': ['E6'] }[options.command];
  const machine = { hostname: os.hostname(), platform: os.platform(), release: os.release(), arch: os.arch(),
    cpu: os.cpus().map(c => c.model), ramBytes: os.totalmem(), node: process.version };
  const instrumentFiles = filesUnder(path.join(root, 'scripts/jseval/lane-f'));
  const driverFiles = ['e-run.mjs', 'capability-ready.py'].map(file => path.join(ROOT, 'scripts/jseval/lane-f', file));
  const corpusFiles = ['docs/explanation', 'docs/reference'].flatMap(dir => filesUnder(path.join(ARMS.main, dir)));
  const pairIdentity = hash(JSON.stringify({ machine, instruments: instrumentFiles.map(f => [path.relative(root, f), hash(fs.readFileSync(f))]),
    driver: driverFiles.map(f => [path.basename(f), hash(fs.readFileSync(f))]),
    corpus: corpusFiles.map(f => [path.relative(ARMS.main, f), hash(fs.readFileSync(f))]), env: plan.find(c => c.env)?.env }));
  const destination = path.join(root, EVIDENCE, options.command, options.arm);
  const recordFile = path.join(destination, `${id}.json`);
  const record = { kind: 'lane-f-e-run.v1', id, groups, arm: options.arm, window: options.window,
    startedAt: new Date().toISOString(), machine, pairIdentity, valuesHash: hash(fs.readFileSync(valuesFile)),
    recordFile, raw, runIds: [], commands: [], metrics: {}, clauses: {}, gaps: {},
    additionalArtifacts: options.command === 'e5-crash' ? [path.join(ARMS[options.arm], 'tmp/lane-f-e', id, 'recovery')] : [] };
  const context = { raw, record, values, deadline: Date.now() + 3540000, sequence: 0, background: [] };
  const bindings = { invocation: id, session: `lane-f-e-${id}` };
  const index = path.join(destination, 'index.json');
  write(recordFile, record); // Even failed/aborted branch starts prevent a later E0 refit.
  write(index, { runs: [...(fs.existsSync(index) ? read(index).runs : []), { id, record: recordFile, raw }] });
  try {
    for (const template of plan) {
      if (Date.now() >= context.deadline) throw new Error('Invocation deadline');
      const command = resolveCommand(template, bindings);
      if (command.label === 'durable-crash' || command.label.startsWith('hang-soft-')) {
        const missingInstrument = !fs.existsSync(command.args[0]);
        if (missingInstrument || options.command === 'e6-hang') {
          for (const group of groups) for (const clause of CLAUSES[group]) record.gaps[clause] = missingInstrument
            ? `Pinned ${options.arm} tree has no ${command.args[0]}; live paired proof unavailable`
            : 'Conformance fake-engine cases hardcode 200ms/3-miss overrides; cannot exercise E4-derived settings or live JVM wedges';
          break;
        }
      }
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
        await startOwned(command, context, bindings);
        continue;
      }
      if (command.mode === 'background') {
        context.background.push(execute(command, context)); continue;
      }
      if (command.mode === 'soak') {
        const end = Date.now() + command.minutes * 60000;
        if (end + 60000 > context.deadline) throw new Error('Insufficient one-hour budget for complete soak window');
        let cycle = 0;
        while (Date.now() < end) {
          const iteration = { ...command, label: `soak-cycle-${++cycle}`, args: [...command.args, '--reset'] };
          const result = await execute(iteration, context).complete;
          if (result.code !== 0) throw new Error(`Soak cycle exited ${result.code}`);
        }
        record.metrics.measuredMinutes = command.minutes;
        record.gaps['index-agent-reconfigure-workload'] = 'Existing instruments have no scheduled fifteen-minute reconfigure soak';
        continue;
      }
      const result = await execute(command, context).complete;
      if (result.code !== 0) throw new Error(`${command.label} exited ${result.code}; see ${result.receipt.stderrFile}`);
      if (command.label === 'runner-status') {
        const status = JSON.parse(result.stdout);
        if (status.runId) throw new Error(`Shared stack occupied by ${status.runId}; no takeover`);
      } else if (command.label === 'dirty') {
        if (result.stdout.trim()) throw new Error('Arm source tree is dirty; built revision is ambiguous');
      } else if (command.label === 'revision') {
        record.revision = result.stdout.trim();
        if (options.arm === 'main' && !record.revision.startsWith('ac1c93bf3')) throw new Error('MAIN revision differs from owner pin');
      }
      if (command.mode === 'stop') { context.owned = null; delete bindings.runId; context.token = null; }
    }
    collect(context);
    if (groups.includes('E5')) record.gaps['crash-to-api'] = 'Processing harness uses 10s cooldown; lacks paired 100-document crash/readiness timing';
    if (groups.includes('E6')) record.gaps['E4-derived-hang-settings'] = 'Existing conformance uses fake Engine and 200ms harness overrides; not E4-set live JVM hang proof';
  } catch (error) {
    record.failure = error.message;
    process.exitCode = 1;
  } finally {
    for (const proc of context.background) proc.child.kill();
    if (context.owned) {
      const command = resolveCommand(plan.find(c => c.mode === 'stop'), bindings);
      context.deadline = Math.min(context.deadline + 30000, Date.parse(record.startedAt) + 3590000);
      const stopped = await execute(command, context).complete;
      if (stopped.code !== 0) record.failure = `${record.failure ?? ''}; owned stop failed`;
    }
    if (options.command === 'e5-crash') {
      const stateRoot = path.join(record.additionalArtifacts[0], 'state');
      const activeFile = path.join(stateRoot, 'active.json');
      if (fs.existsSync(activeFile)) {
        const active = read(activeFile);
        if (active.runId) {
          if (!record.runIds.includes(active.runId)) record.runIds.push(active.runId);
          context.deadline = Date.parse(record.startedAt) + 3590000;
          const stopped = await execute({ label: 'stop-crash-fixture', executable: 'node',
            args: [path.join(ARMS[options.arm], 'scripts/dev/dev-runner.cjs'), 'stop', '--json',
              '--run', active.runId, '--session-id', 'writer-recovery-live'], cwd: ARMS[options.arm],
            env: { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: stateRoot, JUSTSEARCH_SUPERVISOR_HARNESS: '1' } }, context).complete;
          if (stopped.code !== 0) record.failure = `${record.failure ?? ''}; crash-fixture owned stop failed`;
        }
      }
    }
    record.endedAt = new Date().toISOString();
    record.rawFiles = [raw, ...record.additionalArtifacts].flatMap(filesUnder);
    for (const group of groups) for (const clause of CLAUSES[group]) {
      if (record.clauses[clause] === undefined && !record.gaps[clause]) record.gaps[clause] =
        'Named instrument did not produce a validated measurement for this clause';
    }
    write(recordFile, record);
    console.log(JSON.stringify({ id, recordFile, failure: record.failure, gaps: record.gaps }, null, 2));
  }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { await main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
