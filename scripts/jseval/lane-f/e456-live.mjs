/** Stage E live collectors. Called only by root's owned e-run lifecycle; importing is inert. */
import fs from 'node:fs';
import path from 'node:path';
import { gunzipSync } from 'node:zlib';
import { spawn } from 'node:child_process';
import { createOperationKey } from '../../../modules/ui-web/src/api/operationKey.ts';
import { createRequire } from 'node:module';
import { DatabaseSync } from 'node:sqlite';
import identity from '../../dev/lib/process-identity.cjs';
import { killOwned, verifyOwned } from '../../supervisor-conformance/verified-crash.mjs';
import { attachFault } from '../../supervisor-conformance/jdwp-fault.mjs';
import { ownedProcesses, parseGc, heapTrend, summedHeapTrend, memorySeries, launchBudget,
  splitGap, verdict, hangPolicy, hangVerdict, crashObservation } from './e456-instruments.mjs';

const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const json = file => fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : null;
const save = (file, value) => { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`); };
const recordOf = row => ({ pid: Number(row.ProcessId), creationFileTimeUtc: row.CreationFileTimeUtc, cmdlineFingerprint: row.CommandLine });
const keyOf = row => `${row.ProcessId}/${row.CreationFileTimeUtc}`;
export const executableFrom = command => /^"([^"]+)"|^(\S+)/.exec(command ?? '')?.slice(1).find(Boolean);
const fileTimeMs = value => Number((BigInt(value) - 116444736000000000n) / 10000n);
const require = createRequire(import.meta.url);
const allFiles = dir => fs.existsSync(dir) ? fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory()
  ? allFiles(path.join(dir, e.name)) : [path.join(dir, e.name)]) : [];
async function run(executable, args, timeout = 15000) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '', stderr = '';
    child.stdout.on('data', b => { stdout += b; }); child.stderr.on('data', b => { stderr += b; });
    const timer = setTimeout(() => child.kill(), timeout);
    child.once('error', error => { clearTimeout(timer); reject(error); });
    child.once('close', (code, signal) => { clearTimeout(timer); resolve({ code, signal, stdout, stderr }); });
  });
}
export async function requestLive(context, endpoint, body, timeoutMs = 3000) {
  const url = `http://127.0.0.1:33221${endpoint}`;
  const headers = { Host: '127.0.0.1:33221' };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    if (context.token) headers['X-JustSearch-Session'] = context.token;
  }
  const response = await fetch(url, { method: body === undefined ? 'GET' : 'POST', headers,
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(timeoutMs) });
  const text = await response.text();
  let value; try { value = JSON.parse(text); } catch { value = { text }; }
  if (!response.ok) throw new Error(`${endpoint}: HTTP ${response.status}: ${text.slice(0, 300)}`);
  return value;
}
export function settingsPatch(snapshot, patterns, arm, operationKey = createOperationKey()) {
  if (arm === 'branch' && !snapshot?.witness) throw new Error('Fresh branch settings witness required');
  return { ui: { excludePatterns: patterns }, ...(arm === 'branch' ? { witness: snapshot.witness, operationKey } : {}) };
}
function rows(dbFile, sql, args = []) {
  if (!fs.existsSync(dbFile)) return [];
  const db = new DatabaseSync(dbFile, { readOnly: true });
  try { db.exec('PRAGMA busy_timeout=500'); return db.prepare(sql).all(...args); } finally { db.close(); }
}
export class LiveCollector {
  constructor(context, command) {
    this.context = context; this.arm = context.record.arm; this.directory = command.directory;
    this.data = context.dataDir; this.startMs = Date.now(); this.stopped = false;
    this.snapshots = []; this.errors = []; this.processes = new Map(); this.flags = new Map();
    this.diagnostics = []; this.reconfigures = []; this.nextGcMs = this.startMs + 300000;
    this.nextSettingsMs = this.startMs + 900000; this.initialRoots = new Set(); this.logOffsets = new Map();
    this.scopeFile = path.join(this.directory, 'process-scope.json');
    this.artifact = path.join(this.directory, 'instruments.json');
  }
  async start() {
    fs.mkdirSync(this.directory, { recursive: true });
    this.originalSettings = await requestLive(this.context, '/api/settings/v2');
    this.originalApplied = await requestLive(this.context, '/api/indexing/excludes/apply?dryRun=true', {});
    await this.sample();
    this.task = this.loop();
    return this;
  }
  table() {
    const result = identity.readProcessTable();
    if (!result.ok) throw new Error(`Process identity unavailable: ${result.reason ?? result.error}`);
    return { ...result, table: result.table.map(row => ({ ...row, ExecutablePath: executableFrom(row.CommandLine) })) };
  }
  sample() {
    const next = (this.sampleTail ?? Promise.resolve()).then(() => this.sampleOnce());
    this.sampleTail = next.catch(() => {});
    return next;
  }
  async sampleOnce() {
    this.tailLogs();
    const supervisor = json(path.join(this.data, 'runtime/supervisor.v1.json'));
    const table = this.table(), candidates = table.table.filter(row => /HeadlessApp/.test(row.CommandLine ?? '')
      && (row.CommandLine ?? '').includes(this.data));
    if (!candidates.length) {
      this.snapshots.push({ atMs: Date.now(), processes: [], supervisor }); this.persist(); return;
    }
    if (candidates.length !== 1) throw new Error(`Expected one owned Head/Engine, found ${candidates.length}`);
    const root = recordOf(candidates[0]);
    const manifest = json(path.join(this.data, 'runtime/manifest.json'));
    if (this.arm === 'branch' && (manifest?.pid !== root.pid || !manifest?.instanceId)) throw new Error('Root manifest identity mismatch');
    const retained = [];
    for (const child of manifest?.children ?? []) {
      const row = table.table.find(p => Number(p.ProcessId) === child.pid);
      if (!row || !row.CreationFileTimeUtc || !row.ExecutablePath || !child.startedAt
        || Math.abs(fileTimeMs(row.CreationFileTimeUtc) - Date.parse(child.startedAt)) > 1000
        || path.resolve(row.ExecutablePath).toLowerCase() !== path.resolve(child.executable).toLowerCase()
        || !child.declaredConfigHash || !child.realizedArgvHash) {
        throw new Error(`Managed child identity/configuration unavailable: ${child.id}`);
      }
      retained.push(recordOf(row));
    }
    const owned = ownedProcesses(table.table, root, this.arm, retained);
    const required = this.arm === 'main' ? ['head', 'worker'] : ['engine'];
    if (!required.every(role => owned.some(p => p.role === role))) throw new Error('Arm JVM coverage incomplete');
    const now = Date.now(), records = [];
    for (const row of owned) {
      const key = keyOf(row), prior = this.processes.get(key);
      this.processes.set(key, { ...row, firstObservedMs: prior?.firstObservedMs ?? now, lastObservedMs: now });
      records.push({ ...recordOf(row), role: row.role });
      if (!this.flags.has(key) && row.isJvm) {
        const jcmd = this.jcmd(row), diagnostic = await run(jcmd, [String(row.ProcessId), 'VM.flags', '-all']);
        this.flags.set(key, { ...diagnostic, role: row.role });
        save(path.join(this.directory, `flags-${row.ProcessId}-${row.CreationFileTimeUtc}.json`), diagnostic);
      }
    }
    this.initialRoots.add(keyOf(candidates[0]));
    const snapshot = { atMs: now, processes: records, supervisor, instanceId: manifest?.instanceId };
    this.snapshots.push(snapshot);
    // Atomic scope projection consumed by the existing PowerShell memory sampler.
    const temporary = `${this.scopeFile}.pending`;
    save(temporary, { atMs: now, processes: records }); fs.renameSync(temporary, this.scopeFile);
    this.root = root; this.current = owned; this.manifest = manifest;
    this.tailLogs(); this.persist();
  }
  jcmd(row) {
    const executable = row.ExecutablePath;
    if (!executable || !/^java(?:\.exe)?$/i.test(path.basename(executable))) throw new Error('Verified JVM executable unavailable');
    return path.join(path.dirname(executable), process.platform === 'win32' ? 'jcmd.exe' : 'jcmd');
  }
  tailLogs() {
    const launchLogs = this.context.record.commands.filter(c => c.mode === 'start').flatMap(c => [c.stdoutFile, c.stderrFile]).filter(Boolean);
    for (const file of [...allFiles(path.join(this.data, 'logs')).filter(f => /\.log(?:\.\d+)?(?:\.gz)?$/.test(f)), ...launchLogs]) {
      const raw = fs.readFileSync(file), buffer = file.endsWith('.gz') ? gunzipSync(raw) : raw, offset = this.logOffsets.get(file) ?? 0;
      const text = buffer.subarray(buffer.length < offset ? 0 : offset).toString('utf8');
      fs.appendFileSync(path.join(this.directory, 'head-events.log'), text);
      this.logOffsets.set(file, buffer.length);
    }
  }
  async collectGc() {
    for (const row of this.current.filter(p => p.isJvm)) {
      const record = recordOf(row), check = identity.verifyProcessIdentity({ record, table: this.table() });
      if (!identity.isVerifiedMatch(check)) throw new Error(`GC target identity changed: ${check.reason}`);
      const result = await run(this.jcmd(row), [String(record.pid), 'GC.run']);
      const receipt = { atMs: Date.now(), ...record, ...result };
      this.diagnostics.push(receipt); save(path.join(this.directory, `full-gc-${record.pid}-${receipt.atMs}.json`), receipt);
    }
  }
  async reconfigure(restore = false) {
    const before = await requestLive(this.context, '/api/settings/v2');
    const pattern = `**/__lane_f_never_matches_${this.reconfigures.length % 2}__/**`;
    const patterns = restore ? this.originalSettings.ui?.excludePatterns ?? []
      : [...(this.originalSettings.ui?.excludePatterns ?? []), pattern];
    const atMs = Date.now(), response = await requestLive(this.context, '/api/settings/v2', settingsPatch(before, patterns, this.arm));
    const after = await until(Math.min(this.context.deadline ?? Infinity, Date.now() + 30000), 'Live settings applied', async () => {
      const snapshot = await requestLive(this.context, '/api/settings/v2');
      if (JSON.stringify(snapshot.ui?.excludePatterns) !== JSON.stringify(patterns)) return null;
      if (this.arm === 'branch' && response.operationRecordId != null) {
        const operation = rows(path.join(this.data, 'operations.db'), 'SELECT state FROM operations WHERE id = ?', [response.operationRecordId])[0];
        if (operation?.state !== 'COMPLETE') return null;
      }
      return snapshot;
    });
    const applied = await requestLive(this.context, '/api/indexing/excludes/apply?dryRun=true', {});
    const config = await requestLive(this.context, '/api/debug/effective-config');
    const reported = applied.perPattern?.map(p => p.pattern);
    if (JSON.stringify(after.ui?.excludePatterns) !== JSON.stringify(patterns)
      || JSON.stringify(reported) !== JSON.stringify(patterns)
      || ['matchedFiles', 'deletedByPathJobs', 'deletedById', 'capped'].some(k => applied[k] !== this.originalApplied[k])) {
      throw new Error('Scheduled live settings did not reach the excludes consumer unchanged');
    }
    this.reconfigures.push({ atMs, restore, patterns, response, after, applied, config }); this.persist();
  }
  persist() { save(this.artifact, { startedAtMs: this.startMs, endedAtMs: this.endMs,
    snapshots: this.snapshots, errors: this.errors, diagnostics: this.diagnostics,
    reconfigures: this.reconfigures, processes: [...this.processes.values()], flags: [...this.flags],
    dataDir: this.data, arm: this.arm }); }
  async loop() {
    while (!this.stopped) {
      try {
        await this.sample();
        if (this.context.record.groups.includes('E4') && Date.now() >= this.nextGcMs) {
          this.nextGcMs += 300000; await this.collectGc();
        }
        if (this.context.record.groups.includes('E4') && Date.now() >= this.nextSettingsMs) {
          this.nextSettingsMs += 900000; await this.reconfigure();
        }
      } catch (error) { this.errors.push({ atMs: Date.now(), reason: error.message }); this.persist(); }
      await pause(1000);
    }
  }
  async stop() {
    this.stopped = true; await this.task; this.endMs = Date.now();
    if (this.context.record.groups.includes('E4') && this.reconfigures.some(r => !r.restore)) {
      try { await this.reconfigure(true); } catch (error) { this.errors.push({ atMs: Date.now(), reason: `Settings restore: ${error.message}` }); }
    }
    this.persist();
  }
  projectE4() {
    const { record: r, values } = this.context;
    const file = path.join(this.directory, 'head-rss.csv');
    const memory = fs.existsSync(file) ? memorySeries(fs.readFileSync(file, 'utf8'), this.arm) : null;
    r.metrics.memory = memory;
    if (memory?.complete) {
      r.metrics.peakCommitMB = memory.peakCommitBytes / 1024 ** 2;
      r.metrics.peakWorkingSetMB = memory.peakWorkingSetBytes / 1024 ** 2;
      r.clauses['working-set'] = true; r.clauses['machine-wide-commit-vs-main'] = true;
      r.clauses['window-duration'] = memory.sampledMs >= values.soak.windows[Number(r.window) - 1].minutes * 60000 - 5000;
    }
    const flagChecks = [...this.flags.values()].map(f => f.code === 0 ? launchBudget(f.stdout, f.role) : undefined);
    r.clauses['component-commit-budget'] = verdict(flagChecks) === 'pass' && memory?.complete ? true
      : flagChecks.includes(false) ? false : undefined;
    const longLived = [...this.processes.values()].filter(p => ['head', 'worker', 'engine'].includes(p.role));
    const coverage = this.snapshots.length > 1 && this.errors.length === 0 && this.snapshots.every(s =>
      (this.arm === 'main' ? ['head', 'worker'] : ['engine']).every(role => s.processes.some(p => p.role === role)))
      && this.snapshots.every((s, i) => i === 0 || s.atMs - this.snapshots[i - 1].atMs <= 15000);
    const events = fs.existsSync(path.join(this.directory, 'head-events.log')) ? fs.readFileSync(path.join(this.directory, 'head-events.log'), 'utf8') : '';
    const crash = this.initialRoots.size > 1 || new Set(longLived.filter(p => p.role === 'worker').map(keyOf)).size > 1
      || /worker process died|Attempting worker restart/i.test(events)
      || this.snapshots.some(s => s.processes.length === 0)
      || this.snapshots.some(s => s.supervisor?.lastExit || s.supervisor?.incarnation > 1);
    r.clauses['zero-crashes'] = crash ? false : coverage ? true : undefined;
    const trends = [], parsedAll = [];
    const logs = allFiles(path.join(this.context.raw, 'gc'));
    for (const process of this.processes.values()) {
      if (!process.isJvm) continue;
      const parsed = logs.filter(f => path.basename(f).startsWith(`jvm-${process.ProcessId}-`))
        .map(f => parseGc(fs.readFileSync(f, 'utf8')));
      parsedAll.push(...parsed);
      const points = parsed.flatMap(p => p.points).filter(p => p.timeMs >= fileTimeMs(process.CreationFileTimeUtc)
        && p.timeMs <= process.lastObservedMs + 15000);
      const trend = heapTrend(points, this.startMs, this.endMs);
      trends.push({ role: process.role, identity: keyOf(process), ...trend,
        ordinaryAfterGc: points.filter(p => !p.full) });
    }
    r.metrics.heapTrends = trends;
    const pauses = parsedAll.flatMap(p => p.pausesMs);
    r.metrics.worstPauseMs = pauses.length ? Math.max(...pauses) : undefined;
    // Short-lived extraction JVMs are reported individually; long-window no-growth requires every long-lived arm JVM.
    const requiredTrends = trends.filter(t => ['engine', 'head', 'worker'].includes(t.role));
    const summed = summedHeapTrend(requiredTrends.map(t => t.points), this.startMs, this.endMs);
    r.metrics.summedHeapTrend = summed;
    if (Number(r.window) < 3) requiredTrends.push(summed);
    if (Number(r.window) < 3) r.clauses['live-after-GC-trend'] = requiredTrends.length === longLived.length + 1
      && requiredTrends.length ? verdict(requiredTrends.map(t => t.status === 'pass' ? true : t.status === 'fail' ? false : undefined)) === 'pass'
        ? true : requiredTrends.some(t => t.status === 'fail') ? false : undefined : undefined;
    const due = Math.floor(values.soak.windows[Number(r.window) - 1].minutes / 15);
    const configured = this.reconfigures.filter(r => !r.restore);
    r.clauses['index-agent-reconfigure-workload'] = coverage && configured.length === due
      && configured.every((change, i) => Math.abs(change.atMs - (this.startMs + (i + 1) * 900000)) < 30000) ? true : undefined;
    r.gaps['component-commit-budget'] = 'Observed JVM heap/direct/metaspace launch limits; the total private-bytes envelope is the paired split sum, not a synthetic total ceiling.';
    if (Number(r.window) === 3) r.gaps['live-after-GC-trend'] = 'Ten-minute tail contributes memory/duration/crashes; slopes are required in windows 1 and 2.';
  }
}

async function until(deadline, label, read) {
  let error;
  while (Date.now() < deadline) {
    try { const value = await read(); if (value) return value; } catch (failure) { error = failure; }
    await pause(100);
  }
  throw new Error(`${label}: deadline${error ? ` (${error.message})` : ''}`);
}
function operationRows(data) {
  return rows(path.join(data, 'operations.db'), `SELECT id,operation_key,state,kind,checkpoint_cursor,
    units_completed,units_failed,attempts FROM operations WHERE kind='ingest'`);
}
export function jobRows(data, corpus) {
  const file = path.join(data, 'jobs.db');
  const fields = rows(file, 'PRAGMA table_info(jobs)').map(r => r.name);
  if (!fields.length) return [];
  for (const field of ['path', 'state', 'scan_id', 'last_updated']) {
    if (!fields.includes(field)) throw new Error(`Jobs ledger lacks ${field}`);
  }
  const revision = fields.includes('unit_revision') ? 'unit_revision' : 'NULL AS unit_revision';
  // MAIN has scan identity, but no Lane F per-unit revision. NULL stays an explicit absence.
  return rows(file, `SELECT path,state,scan_id,${revision},last_updated FROM jobs WHERE path LIKE ?`, [`${corpus}%`]);
}
function searchHit(value, marker) { return (value?.results ?? value?.hits ?? []).some(hit => JSON.stringify(hit).includes(marker)); }
export function logEventTime(log, pattern, afterMs) {
  return log.split(/\r?\n/).flatMap(line => {
    let row; try { row = JSON.parse(line); } catch { return []; }
    const time = Date.parse(row['@timestamp']);
    return pattern.test(row.message ?? '') && time >= afterMs ? [time] : [];
  }).at(-1);
}
async function healthyLlama(collector, children) {
  for (const child of children) {
    const current = collector.current.find(p => keyOf(p) === keyOf(child));
    if (!current) return false;
    verifyOwned(recordOf(collector.root), collector.data);
    const check = identity.verifyProcessIdentity({ record: recordOf(child), table: collector.table() });
    if (!identity.isVerifiedMatch(check)) return undefined;
    const command = child.CommandLine ?? '';
    const host = /--host\s+(?:"([^"]+)"|(\S+))/.exec(command)?.slice(1).find(Boolean);
    const port = Number(/--port\s+"?(\d+)/.exec(command)?.[1]);
    if (host !== '127.0.0.1' || !Number.isSafeInteger(port) || port < 1 || port > 65535) return undefined;
    try {
      const response = await fetch(`http://127.0.0.1:${port}/health`, { signal: AbortSignal.timeout(2000) });
      const body = await response.json();
      collector.diagnostics.push({ atMs: Date.now(), child: recordOf(child), health: body, status: response.status });
      if (!response.ok || body.status !== 'ok') return false;
    } catch { return undefined; }
  }
  return children.length ? true : undefined;
}
export async function crashExperiment(context) {
  const collector = context.collector, r = context.record;
  if (r.arm === 'main') {
    r.dispositions ??= {};
    for (const clause of ['actual-death-durable-operation', 'checkpoint-resume', 'visible-restarting']) {
      r.dispositions[clause] = splitGap(clause === 'visible-restarting'
        ? 'MAIN Head logs narrate Worker replacement; the Head has no dev-arm respawn supervisor'
        : 'MAIN exposes durable scan jobs, without operations.db or Lane F per-unit revision/checkpoint identity');
      r.gaps[clause] = r.dispositions[clause].reason;
    }
  }
  const directory = path.join(context.raw, 'crash'); fs.mkdirSync(directory, { recursive: true });
  const corpus = path.join(directory, 'corpus'); fs.mkdirSync(corpus);
  for (let i = 0; i < 100; i++) fs.writeFileSync(path.join(corpus, `recovery-${i}.rtf`),
    `{\\rtf1\\ansi lanefrecoverydoc${i} ${'durable checkpoint capybara '.repeat(2500)}}`);
  const key = createOperationKey(), accepted = await requestLive(context, '/api/knowledge/ingest', { paths: [corpus], idempotencyKey: key }, 30000);
  save(path.join(directory, 'accepted.json'), accepted);
  let cut;
  try {
    cut = await until(Math.min(context.deadline, Date.now() + 120000), 'Accepted durable work must be partially complete and PROCESSING', async () => {
      const jobs = jobRows(collector.data, corpus), processing = jobs.find(j => j.state === 'PROCESSING');
      const completed = jobs.filter(j => j.state === 'DONE');
      if (!processing || !completed.length || jobs.length !== 100) return null;
      const operation = r.arm === 'branch' ? operationRows(collector.data).find(o => o.state === 'RUNNING'
        && o.units_completed > 0 && o.checkpoint_cursor && (o.operation_key === processing.scan_id || o.operation_key === key)) : undefined;
      if (r.arm === 'branch' && (!operation || !jobs.every(j => typeof j.unit_revision === 'string' && j.unit_revision.length))) return null;
      // Query a committed completed marker before injecting; that query defines index-ready after recovery.
      const first = completed[0], marker = `lanefrecoverydoc${/recovery-(\d+)\.rtf$/.exec(first.path)?.[1]}`;
      if (!searchHit(await requestLive(context, '/api/knowledge/search', { query: marker, limit: 5, mode: 'text' }), marker)) return null;
      await collector.sample();
      if (!jobRows(collector.data, corpus).some(j => j.path === processing.path && j.state === 'PROCESSING')) return null;
      const target = collector.current.find(p => p.role === (r.arm === 'branch' ? 'engine' : 'worker'));
      return { ...recordOf(target), instanceId: collector.manifest?.instanceId, operation, processing, completed, jobs, marker, accepted,
        children: collector.current.filter(p => ['llama-server', 'extraction-child'].includes(p.role)),
        registeredChildren: collector.manifest?.children ?? [] };
    });
  } catch (error) {
    r.gaps['actual-death-durable-operation'] = `No valid durable cut; no idle kill substituted: ${error.message}`;
    return;
  }
  save(path.join(directory, 'before.json'), cut);
  const killed = killOwned(cut, collector.data), timeline = { killMs: killed.issuedAtMs };
  save(path.join(directory, 'kill.json'), killed);
  await until(Date.now() + 15000, 'Verified target death', () => {
    const table = collector.table(); return !table.table.some(p => Number(p.ProcessId) === cut.pid && p.CreationFileTimeUtc === cut.creationFileTimeUtc);
  });
  timeline.deathConfirmedMs = Date.now();
  const deadJob = jobRows(collector.data, corpus).find(j => j.path === cut.processing.path);
  const deadOperation = r.arm === 'branch' ? operationRows(collector.data).find(o => o.id === cut.operation.id) : undefined;
  const durableAtDeath = deadJob?.state === 'PROCESSING' && deadJob.scan_id === cut.processing.scan_id
    && (r.arm === 'main' || deadJob.unit_revision === cut.processing.unit_revision
      && deadOperation?.state === 'RUNNING' && deadOperation.units_completed >= cut.operation.units_completed);
  save(path.join(directory, 'after-death.json'), { deadJob, deadOperation, durableAtDeath });
  const deadline = Math.min(context.deadline, timeline.killMs + 180000);
  const restored = await until(deadline, 'API restoration', async () => {
    const manifest = await requestLive(context, '/api/runtime/manifest');
    if (r.arm === 'branch' && manifest.instanceId === cut.instanceId) return null;
    context.token = (await requestLive(context, '/api/mcp/token')).token ?? null;
    return manifest;
  });
  timeline.apiMs = Date.now();
  await until(deadline, 'Text index ready', async () => searchHit(await requestLive(context, '/api/knowledge/search',
    { query: cut.marker, limit: 5, mode: 'text' }), cut.marker));
  timeline.indexMs = Date.now();
  await until(deadline, 'Successor process identity', async () => {
    await collector.sample();
    return collector.current.some(p => p.role === (r.arm === 'branch' ? 'engine' : 'worker')
      && keyOf(p) !== `${cut.pid}/${cut.creationFileTimeUtc}`);
  });
  const target = collector.current.find(p => p.role === (r.arm === 'branch' ? 'engine' : 'worker'));
  let repeatedCompleted = false, regressedCheckpoint = false;
  const checkpointSamples = [];
  const completion = await until(deadline, 'Original accepted jobs finish without resubmission', () => {
    const jobs = jobRows(collector.data, corpus);
    if (cut.completed.some(old => jobs.some(j => j.path === old.path && j.state !== 'DONE'))) repeatedCompleted = true;
    if (r.arm === 'branch') {
      const operation = operationRows(collector.data).find(o => o.id === cut.operation.id);
      if (operation) {
        checkpointSamples.push({ atMs: Date.now(), operation });
        if (operation.units_completed < cut.operation.units_completed) regressedCheckpoint = true;
        if (operation.state !== 'COMPLETE') return null;
      } else return null;
    }
    return jobs.length === 100 && jobs.every(j => j.state === 'DONE') ? jobs : null;
  });
  timeline.inFlightCompletedMs = Date.now();
  const afterOperation = r.arm === 'branch' ? operationRows(collector.data).find(o => o.id === cut.operation.id) : undefined;
  const noDuplicateEffects = !repeatedCompleted && !regressedCheckpoint
    && cut.completed.every(old => completion.some(j => j.path === old.path && j.last_updated === old.last_updated))
    && completion.every(j => cut.jobs.some(old => old.path === j.path
    && old.scan_id === j.scan_id && (r.arm === 'main' || old.unit_revision === j.unit_revision)));
  r.metrics.jobRevisionAvailable = completion.every(j => j.unit_revision !== null);
  const after = { ...recordOf(target), operation: afterOperation, jobs: completion, noDuplicateEffects };
  const observed = crashObservation(cut, after, timeline, r.arm);
  Object.assign(r.metrics, { 'crash-to-api': observed.apiMs, 'crash-to-index': observed.indexMs,
    crashTimeline: timeline, crashBefore: cut, crashAfter: after, apiContinuity: r.arm === 'main' ? 'Head survived; first successful post-death probe bounds the observation' : 'successor API' });
  r.clauses['actual-death-durable-operation'] = durableAtDeath ? observed.identityChanged && noDuplicateEffects : undefined;
  if (!durableAtDeath) r.gaps['actual-death-durable-operation'] = 'PROCESSING/RUNNING cut was not observed after verified death; not substituted by a pre-kill snapshot';
  r.clauses['crash-to-api'] = observed.apiMs <= context.values.crashToApiRestoredMs;
  r.clauses['crash-to-index'] = Number.isFinite(observed.indexMs) ? true : undefined;
  r.clauses['checkpoint-resume'] = durableAtDeath ? observed.checkpointResume : undefined;
  const supervisor = collector.snapshots.filter(s => s.supervisor?.state === 'restarting' && s.atMs >= timeline.killMs);
  r.clauses['visible-restarting'] = r.arm === 'branch' ? supervisor.length > 0 ? true : undefined : undefined;
  const originalChildren = [...collector.processes.values()].filter(p => !['engine', 'head', 'worker'].includes(p.role)
    && p.firstObservedMs < timeline.killMs);
  const table = collector.table().table;
  const orphaned = originalChildren.filter(p => p.role !== 'llama-server'
    && table.some(row => Number(row.ProcessId) === Number(p.ProcessId) && row.CreationFileTimeUtc === p.CreationFileTimeUtc));
  r.clauses['no-orphaned-child'] = orphaned.length ? false
    : originalChildren.some(p => p.role !== 'llama-server') ? true : undefined;
  r.metrics.childRecovery = { originalChildren, orphaned, current: collector.current.filter(p => ['llama-server', 'extraction-child'].includes(p.role)) };
  const llamaBefore = cut.children.filter(p => p.role === 'llama-server');
  let adopted = llamaBefore.length ? llamaBefore.every(before => collector.current.some(after => after.role === 'llama-server'
    && keyOf(before) === keyOf(after))) : undefined;
  if (adopted && r.arm === 'branch') adopted = cut.registeredChildren.filter(c => c.kind === 'LLAMA_SERVER')
    .every(old => collector.manifest.children.some(c => c.pid === old.pid && c.startedAt === old.startedAt
      && c.declaredConfigHash === old.declaredConfigHash && c.realizedArgvHash === old.realizedArgvHash));
  if (adopted) adopted = await healthyLlama(collector, llamaBefore);
  r.metrics.childPaths ??= {};
  r.metrics.childPaths.crash = { childrenBefore: cut.children, childrenAfter: collector.current,
    extractionStopped: r.clauses['no-orphaned-child'], healthyLlamaAdopted: adopted };
  if (r.arm === 'main') {
    r.dispositions ??= {};
    for (const clause of ['checkpoint-resume', 'visible-restarting']) {
      r.dispositions[clause] = splitGap(clause === 'checkpoint-resume' ? 'MAIN has job replay, no Lane F operations checkpoint ledger'
        : 'MAIN narrates Worker restart in Head logs, no supervisor.v1.json');
      r.gaps[clause] = r.dispositions[clause].reason;
    }
    // A jobs ledger is not a Lane F operation acceptance/checkpoint claim.
    r.dispositions['actual-death-durable-operation'] = splitGap('Accepted durable jobs in flight measured; MAIN cannot expose Lane F operation identity');
    delete r.clauses['actual-death-durable-operation'];
  }
  save(path.join(directory, 'recovery.json'), { cut, after, timeline, observed, restored, checkpointSamples, childRecovery: r.metrics.childRecovery });
}

export async function childPathExperiment(context, reason) {
  const collector = context.collector, r = context.record;
  // Make real extraction children exist before evaluating their cleanup policy.
  const corpus = path.join(collector.directory, 'child-policy-corpus'); fs.mkdirSync(corpus);
  fs.writeFileSync(path.join(corpus, 'children.rtf'), '{\\rtf1\\ansi childpolicycapybara durable parser children}');
  await requestLive(context, '/api/knowledge/ingest', { paths: [corpus], idempotencyKey: createOperationKey() }, 30000);
  const deadline = Math.min(context.deadline, Date.now() + 180000);
  await until(deadline, 'Child-policy corpus ready', async () => searchHit(await requestLive(context,
    '/api/knowledge/search', { query: 'childpolicycapybara', limit: 5, mode: 'text' }), 'childpolicycapybara'));
  await collector.sample();
  const before = collector.current.filter(p => ['llama-server', 'extraction-child'].includes(p.role));
  const initialRoot = collector.root, initialInstance = collector.manifest?.instanceId;
  const initialWorker = collector.current.find(p => p.role === 'worker');
  const registered = collector.manifest?.children ?? [];
  const result = { reason, before, registered, issuedAtMs: Date.now() };
  if (reason === 'restart') {
    if (r.arm === 'branch') {
      verifyOwned(initialRoot, collector.data);
      const runner = require(path.join(context.tree, 'scripts/dev/dev-runner.cjs')).__test;
      await runner.writeShutdownRequestFile(collector.data, { reason: 'restart', deadlineEpochMs: Date.now() + 15000 });
    } else {
      await requestLive(context, '/api/operations/core.restart-worker/invoke', { args: {}, idempotencyKey: createOperationKey() }, 30000);
    }
    await until(deadline, 'Requested restart readiness', async () => {
      const manifest = await requestLive(context, '/api/runtime/manifest');
      if (r.arm === 'branch' && manifest.instanceId === initialInstance) return null;
      await collector.sample();
      if (r.arm === 'main' && !collector.current.some(p => p.role === 'worker' && keyOf(p) !== keyOf(initialWorker))) return null;
      context.token = (await requestLive(context, '/api/mcp/token')).token ?? null;
      await requestLive(context, '/api/health'); return true;
    });
    await collector.sample();
    result.after = collector.current;
    result.healthyLlamaAdopted = before.some(p => p.role === 'llama-server') ? before.filter(p => p.role === 'llama-server')
      .every(old => collector.current.some(p => p.role === 'llama-server' && keyOf(p) === keyOf(old))) : undefined;
    if (r.arm === 'branch' && result.healthyLlamaAdopted) result.healthyLlamaAdopted = registered.filter(c => c.kind === 'LLAMA_SERVER')
      .every(old => collector.manifest.children.some(c => c.pid === old.pid && c.startedAt === old.startedAt
        && c.declaredConfigHash === old.declaredConfigHash && c.realizedArgvHash === old.realizedArgvHash));
    if (result.healthyLlamaAdopted) result.healthyLlamaAdopted = await healthyLlama(collector, before.filter(p => p.role === 'llama-server'));
  } else if (reason === 'quit') {
    result.response = await requestLive(context, '/api/lifecycle/shutdown', {});
  } else {
    const prepared = await requestLive(context, '/api/upgrade/prepare', {});
    result.prepared = prepared;
    result.response = await until(deadline, 'Upgrade shutdown accepted', async () => {
      const response = await requestLive(context, '/api/upgrade/commit-shutdown', {
        preparationId: prepared.preparationId, shutdownNonce: prepared.shutdownNonce });
      return response.shutdownAccepted === true ? response : null;
    });
  }
  if (reason !== 'restart') await until(deadline, 'Owned Head/Engine terminal exit', () => !collector.table().table.some(row =>
    Number(row.ProcessId) === initialRoot.pid && row.CreationFileTimeUtc === initialRoot.creationFileTimeUtc));
  const table = collector.table().table;
  const survivors = before.filter(old => table.some(row => keyOf(row) === keyOf(old)));
  result.survivors = survivors;
  result.extractionStopped = before.some(p => p.role === 'extraction-child')
    ? survivors.every(p => p.role !== 'extraction-child') : undefined;
  result.llamaStopped = before.some(p => p.role === 'llama-server') ? survivors.every(p => p.role !== 'llama-server') : undefined;
  result.finishedAtMs = Date.now(); r.metrics.childPaths ??= {}; r.metrics.childPaths[reason] = result;
  save(path.join(collector.directory, 'child-path.json'), result);
}
export function projectChildPolicies(record) {
  const paths = record.metrics.childPaths ?? {}, checks = [];
  for (const reason of ['crash', 'restart', 'quit', 'upgrade']) {
    checks.push(paths[reason]?.extractionStopped);
    checks.push(paths[reason]?.[reason === 'crash' || reason === 'restart' ? 'healthyLlamaAdopted' : 'llamaStopped']);
  }
  const result = verdict(checks);
  record.clauses['restart-quit-upgrade-child-policy'] = result === 'pass' ? true : result === 'fail' ? false : undefined;
  if (result === 'unmeasurable') record.gaps['restart-quit-upgrade-child-policy'] = 'Every crash/restart/quit/upgrade-shutdown path needs actual live extraction and healthy generative child evidence.';
}

export async function hangExperiment(context, command) {
  const collector = context.collector, r = context.record, kind = command.kind;
  const directory = path.join(context.raw, `hang-${kind}`); fs.mkdirSync(directory, { recursive: true });
  const policy = hangPolicy(context.values, command.worstPauseMs);
  if (r.arm === 'main') {
    r.dispositions ??= {};
    for (const clause of [kind === 'soft' ? 'runnable-watcher-api-pool-wedge' : 'whole-JVM-wedge',
      kind === 'soft' ? 'graceful-deadline' : 'forced-deadline']) {
      r.dispositions[clause] = splitGap('Native Worker gRPC/VM fault; MAIN Head HTTP has no autonomous dev-arm recovery');
      r.gaps[clause] = r.dispositions[clause].reason;
    }
  }
  const declaredPolicy = json(path.join(context.tree, 'governance/supervision-contract.v1.json'))?.processes.find(p => p.id === 'engine')?.policy;
  if (r.arm === 'branch' && !declaredPolicy) throw new Error('Owned arm supervision contract missing');
  await collector.sample();
  const target = collector.current.find(p => p.role === (r.arm === 'main' ? 'worker' : 'engine'));
  const record = recordOf(target), port = Number(command.port), initialInstance = collector.manifest?.instanceId,
    evidence = { kind, injected: false, preHealthy: false };
  const verify = async () => {
    verifyOwned(record, collector.data);
    const address = new RegExp(`address=127\\.0\\.0\\.1:${port}(?:\\s|$)`);
    if (!address.test(record.cmdlineFingerprint)) throw new Error('JDWP port not declared by the owned target');
  };
  await requestLive(context, '/api/health'); evidence.preHealthy = true;
  let fault;
  try {
    fault = await attachFault({ port, kind, threadPattern: r.arm === 'main' ? '^grpc-default-executor' : '^(qtp|Jetty|jetty)',
      verify, record: event => fs.appendFileSync(path.join(directory, 'jdwp.jsonl'), `${JSON.stringify(event)}\n`) });
  } catch (error) {
    if (!/No live request threads matched/.test(error.message)) throw error;
    r.gaps[kind === 'soft' ? 'runnable-watcher-api-pool-wedge' : 'whole-JVM-wedge'] = error.message;
    save(path.join(directory, 'result.json'), { evidence, gap: error.message }); return;
  }
  evidence.injected = true; evidence.injectionAtMs = Date.now();
  try {
    if (r.arm === 'branch') {
      try { await requestLive(context, '/api/health', undefined, 2000); evidence.postUnresponsive = false; }
      catch (error) { evidence.postUnresponsive = /abort|timeout/i.test(error.name + error.message); }
      if (!evidence.postUnresponsive) {
        r.gaps[kind === 'soft' ? 'runnable-watcher-api-pool-wedge' : 'whole-JVM-wedge'] = 'Injected suspension did not produce a confirmed API timeout';
        save(path.join(directory, 'result.json'), { evidence }); return;
      }
    } else {
      // Head HTTP liveness is expected to survive. The Head's native gRPC detector supplies the wedge observation.
      evidence.apiSurvived = await requestLive(context, '/api/runtime/manifest');
    }
    const deadline = Math.min(context.deadline, Date.now() + policy.intervalMs * (policy.missCount + 1) + 180000);
    let lastAbsentMs = evidence.injectionAtMs;
    await until(deadline, 'Hang request channel', () => {
      collector.tailLogs();
      if (r.arm === 'main') {
        const log = fs.readFileSync(path.join(collector.directory, 'head-events.log'), 'utf8');
        if (!/worker unresponsive/.test(log)) { lastAbsentMs = Date.now(); return null; }
        evidence.postUnresponsive = true; evidence.requestObserved = true;
        const producerAt = logEventTime(log, /worker unresponsive/, evidence.injectionAtMs);
        evidence.requestAtMs = producerAt ?? Date.now(); evidence.requestLowerMs = producerAt ?? lastAbsentMs - 1500;
        evidence.requestTimeSource = producerAt ? "Head producer UTC log timestamp" : "Head log observed between successive reads (1.5s margin)"; return true;
      }
      const request = json(path.join(collector.data, 'runtime/shutdown-request.v1.json'));
      const supervisor = json(path.join(collector.data, 'runtime/supervisor.v1.json'));
      const log = fs.readFileSync(path.join(collector.directory, 'head-events.log'), 'utf8');
      // The watcher removes a consumed request; the supervisor's narration survives consumption.
      if (!(request?.reason === 'hang' || /wrote a shutdown request: reason=hang/.test(log))) { lastAbsentMs = Date.now(); return null; }
      if (supervisor?.runId !== context.owned.runId) return null;
      evidence.requestObserved = true;
      evidence.requestAtMs = request?.deadlineEpochMs ? request.deadlineEpochMs - declaredPolicy.gracefulStopDeadlineMs : Date.now();
      evidence.requestLowerMs = request?.deadlineEpochMs ? evidence.requestAtMs : lastAbsentMs - 1500;
      evidence.requestTimeSource = request?.deadlineEpochMs ? "shutdown request deadline minus declared grace" : "actuator log observed between reads (1.5s margin)";
      evidence.request = request; return true;
    });
    evidence.lastAliveAtMs = evidence.injectionAtMs;
    await until(deadline, 'Hang target termination', () => {
      const alive = collector.table().table.some(p => Number(p.ProcessId) === record.pid
        && p.CreationFileTimeUtc === record.creationFileTimeUtc);
      if (alive) evidence.lastAliveAtMs = Date.now();
      return !alive;
    });
    evidence.deathAtMs = Date.now();
    collector.tailLogs();
    const log = fs.readFileSync(path.join(collector.directory, 'head-events.log'), 'utf8');
    const supervisor = json(path.join(collector.data, 'runtime/supervisor.v1.json'));
    evidence.exit = supervisor?.lastExit;
    evidence.forced = r.arm === 'main' ? /did not terminate gracefully.*forcing/.test(log)
      : supervisor?.lastExit?.class === 'transient' && supervisor?.lastExit?.reason === 'hang';
    // Forced identity is derived from actuator evidence, not merely elapsed time or debugger death.
    if (r.arm === 'branch') evidence.forced = /FORCED KILL: the Engine ignored the hang request/.test(log);
    evidence.graceful = evidence.forced ? false : r.arm === 'main' ? /Worker restarted on port/.test(log)
      : supervisor?.lastExit?.code === 5 || supervisor?.lastExit?.exitCode === 5;
    await until(deadline, 'Recovered health and text query', async () => {
      const manifest = await requestLive(context, '/api/runtime/manifest');
      if (r.arm === 'branch' && manifest.instanceId === initialInstance) return null;
      context.token = (await requestLive(context, '/api/mcp/token')).token ?? null;
      await requestLive(context, '/api/health');
      evidence.apiRestoredAtMs ??= Date.now();
      await requestLive(context, '/api/knowledge/search', { query: 'capybara', limit: 1, mode: 'text' }); return manifest;
    });
    evidence.restoredAtMs = Date.now();
    evidence.indexReadyAtMs = evidence.restoredAtMs;
    evidence.detectionMs = { lower: evidence.requestLowerMs - evidence.injectionAtMs, upper: evidence.requestAtMs - evidence.injectionAtMs };
    evidence.recoveryMs = { lower: evidence.restoredAtMs - evidence.requestAtMs, upper: evidence.restoredAtMs - evidence.requestLowerMs };
    evidence.injectionErrors = fault.errors();
    const native = r.arm === 'main' ? { gracefulStopDeadlineMs: 5000, cooldownIncrementMs: 1000 } : declaredPolicy;
    native.intervalMs = r.arm === 'main' ? 10000 : policy.intervalMs;
    native.missCount = r.arm === 'main' ? 3 : policy.missCount;
    native.probeTimeoutMs = r.arm === 'main' ? undefined : 1000;
    if (r.arm === 'main') {
      collector.tailLogs();
      const recoveredLog = fs.readFileSync(path.join(collector.directory, 'head-events.log'), 'utf8');
      evidence.graceful = !evidence.forced && /Worker restarted on port/.test(recoveredLog);
    }
    const result = hangVerdict(evidence, kind, native, context.values.warmStartBudgetMs);
    r.metrics[`hang-${kind}`] = evidence;
    if (r.arm === 'branch') {
      r.clauses[kind === 'soft' ? 'runnable-watcher-api-pool-wedge' : 'whole-JVM-wedge'] = evidence.postUnresponsive;
      r.clauses[kind === 'soft' ? 'graceful-deadline' : 'forced-deadline'] = result;
      r.clauses['E4-derived-hang-settings'] = r.clauses['E4-derived-hang-settings'] !== false && collector.snapshots.some(s => s.supervisor?.policyProfile === 'harness'
        && ['hangPollIntervalMs', 'hangUnhealthyThreshold'].every(k => s.supervisor.policyOverrides?.includes(k)));
    } else {
      r.metrics[`split-native-${kind}-verdict`] = result;
      r.dispositions ??= {};
      for (const clause of [kind === 'soft' ? 'runnable-watcher-api-pool-wedge' : 'whole-JVM-wedge',
        kind === 'soft' ? 'graceful-deadline' : 'forced-deadline']) {
        r.dispositions[clause] = splitGap('Measured Worker gRPC/VM suspension; MAIN Head API has no recovering dev-arm supervisor');
        r.gaps[clause] = r.dispositions[clause].reason;
      }
      r.clauses['E4-derived-hang-settings'] = policy.splitCompatible ? true : undefined;
      if (!policy.splitCompatible) r.dispositions['E4-derived-hang-settings'] = splitGap('MAIN is fixed at 10 seconds / 3 misses');
    }
    save(path.join(directory, 'result.json'), { evidence, result, native, policy });
  } finally { await fault.close(); }
}
