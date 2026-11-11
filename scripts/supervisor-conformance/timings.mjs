import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const ledger = require('../dev/lib/exit-ledger.cjs');

function lines(file) {
  if (!fs.existsSync(file)) return [];
  return fs.readFileSync(file, 'utf8').split('\n').filter(Boolean);
}
const epoch = value => Number.isFinite(Date.parse(value)) ? Date.parse(value) : null;
const STDERR_TAIL_CHARS = 2000;
// The fake Engine's stderr copy is named by its incarnation reference, made filename-safe.
export const stderrName = ref => ref.replace(/[^A-Za-z0-9._-]/g, '_');
const EXIT_FIELDS = ['code', 'signal', 'signalName', 'codeName', 'exitClass', 'phase', 'forced', 'observedAt'];

// How each Engine incarnation of one work folder ended, as the supervisor recorded it, and the end
// of what that fake Engine wrote to stderr. Evidence joins only by incarnation reference: a PID can
// be reused by a later start (Windows does), so an unreferenced start is its own row, identified by
// PID and entry time, and an exit no supervisor recorded for it stays null.
export function incarnationEnds(workDir, ledgerRows, events) {
  const ends = new Map();
  const row = (incarnation, pid = null, entryEpochMs = null) => {
    const key = incarnation ?? `pid ${pid} at ${entryEpochMs}`;
    if (!ends.has(key)) ends.set(key, { incarnation, pid, entryEpochMs, workDir, exit: null, stderrTail: null });
    const found = ends.get(key);
    found.pid ??= pid;
    found.entryEpochMs ??= entryEpochMs;
    return found;
  };
  const ref = incarnation => `${incarnation.session}/${incarnation.n}`;
  for (const line of ledgerRows) {
    if (line.type === 'engine-start' && line.incarnation) row(ref(line.incarnation), line.pid ?? null);
  }
  for (const line of events) {
    if (line.phase === 'engineEntry' && line.workDir === workDir) row(line.incarnation || null, line.pid, line.atMs);
  }
  for (const line of ledgerRows) {
    if (line.type !== 'engine-exit' || !line.incarnation) continue;
    row(ref(line.incarnation)).exit = Object.fromEntries(
      EXIT_FIELDS.filter(key => line[key] !== undefined).map(key => [key, line[key]]));
  }
  const logs = path.join(workDir, 'data', 'logs');
  const files = fs.existsSync(logs) ? fs.readdirSync(logs).sort() : [];
  for (const name of files) {
    const match = /^fake-engine-(\d+)-(\d+)(?:-(.+))?\.stderr\.log$/.exec(name);
    if (!match) continue;
    const [pid, entryEpochMs] = [Number(match[1]), Number(match[2])];
    const named = match[3] ? [...ends.values()].filter(end => end.incarnation && stderrName(end.incarnation) === match[3]) : [];
    // A reference that names no single known incarnation stays unattributed, still shown.
    let end = named.length === 1 ? named[0] : match[3] ? null : row(null, pid, entryEpochMs);
    if (!end) ends.set(name, end = { incarnation: null, pid, entryEpochMs, workDir, exit: null, stderrTail: null });
    end.pid ??= pid;
    end.entryEpochMs ??= entryEpochMs;
    end.stderrFile = name;
    end.stderrTail = fs.readFileSync(path.join(logs, name), 'utf8').slice(-STDERR_TAIL_CHARS);
  }
  return [...ends.values()];
}

// Observations only. Missing phases remain absent: an unobserved event is never a zero duration.
export function caseTimings({ adapter, testCase, policy, root }) {
  fs.mkdirSync(root, { recursive: true });
  const file = path.join(root, `${adapter}-${testCase.id}-${Date.now()}-${process.hrtime.bigint()}.timing.json`);
  const record = { schemaVersion: 1, adapter, case: testCase.id, platform: process.platform,
    startedAtMs: Date.now(), workDirs: [], budgetsMs: {
      initialPort: testCase.startTimeoutMs ?? (adapter === 'dev-runner' ? 10000 : 4000),
      initialReady: testCase.startTimeoutMs ?? (adapter === 'dev-runner' ? 10000 : 4000),
      restartStart: policy.startDeadlineMs, gracefulStop: policy.gracefulStopDeadlineMs,
      quit: policy.quitDeadlineMs, quitForceAt: policy.quitDeadlineMs - policy.quitForceMarginMs,
      quitForceTolerance: 250,
      quitAssertion: policy.quitDeadlineMs + (testCase.probe === 'stream' ? policy.quitForceMarginMs : 2000),
    }, events: [], waits: [], measurements: [], incarnations: [] };
  const runs = [];
  const samplers = [];
  let launches = 0;
  const seen = new Set();
  const event = (phase, atMs, fields = {}) => {
    if (!Number.isFinite(atMs)) return;
    // entryEpochMs tells apart two unreferenced starts that reused one PID.
    const key = JSON.stringify([phase, fields.incarnation, fields.pid, fields.entryEpochMs, fields.workDir, fields.launch]);
    if (seen.has(key)) return;
    seen.add(key);
    record.events.push({ phase, atMs, ...fields });
  };
  return {
    file, record,
    addWorkDir(workDir) { record.workDirs.push(workDir); },
    launch(workDir) { event('supervisorSpawnRequested', Date.now(), { workDir, launch: ++launches, source: 'harness' }); },
    attach(run) {
      runs.push(run);
      // Some cases wait only for process exit; sample their intermediate ready publications too.
      const statePath = run.statePath ?? path.join(run.workDir, 'data/runtime/supervisor.v1.json');
      const timer = setInterval(() => {
        let state;
        try {
          state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
          if (state) this.state(state, statePath);
        } catch { /* Atomic replacement may briefly leave no readable live record. */ }
      }, 25);
      timer.unref();
      samplers.push(timer);
    },
    state(state, statePath) {
      const fields = { incarnation: `${state.runId}/${state.incarnation}`, source: 'supervisor state', statePath };
      if (state.state === 'running') event('ready', epoch(state.readyAt) ?? epoch(state.updatedAt) ?? Date.now(), fields);
      if (state.state === 'stopped') event('stoppedPublished', epoch(state.updatedAt) ?? Date.now(), fields);
    },
    quitRequested(state, workDir, atMs = Date.now()) {
      event('quitRequested', atMs, { incarnation: `${state.runId}/${state.incarnation}`,
        workDir, source: 'harness control request' });
    },
    wait(what, timeoutMs) {
      const row = { what, budgetMs: timeoutMs, startedAtMs: Date.now(), endedAtMs: null };
      record.waits.push(row);
      return () => { row.endedAtMs = Date.now(); };
    },
    measure(phase, startedAtMs, budgetMs) {
      record.measurements.push({ phase, startedAtMs, endedAtMs: Date.now(), budgetMs });
    },
    finish(problems) {
      for (const timer of samplers) clearInterval(timer);
      for (const run of runs) {
        fs.writeFileSync(path.join(run.workDir, `supervisor-${run.child.pid}.stdout.log`), run.output.stdout);
        fs.writeFileSync(path.join(run.workDir, `supervisor-${run.child.pid}.stderr.log`), run.output.stderr);
      }
      for (const workDir of record.workDirs) {
        const stateFile = path.join(workDir, 'data/runtime/supervisor.v1.json');
        if (fs.existsSync(stateFile)) this.state(JSON.parse(fs.readFileSync(stateFile, 'utf8')), stateFile);
        for (const line of lines(path.join(workDir, 'spawn-timings.jsonl'))) {
          const row = JSON.parse(line);
          event(row.phase, row.atMs, { incarnation: row.incarnation, source: row.source, workDir });
        }
        const dataDir = path.join(workDir, 'data');
        const rows = ledger.readLedger(ledger.ledgerDirectory(dataDir), 'engine').records.map(row => row.line);
            const logLines = lines(path.join(dataDir, 'logs', 'engine.log'));
        // The first stdout line survives even if fixture initialization fails before its file log.
        for (const run of runs.filter(run => run.workDir === workDir && run.stateRoot)) {
          const runRoot = path.join(run.stateRoot, 'runs');
          if (fs.existsSync(runRoot)) for (const name of fs.readdirSync(runRoot)) {
            logLines.push(...lines(path.join(runRoot, name, 'logs/backend.stdout.log')));
          }
        }
        let current;
        for (const line of logLines) {
          const match = /\[fake-engine\] (\S+) (.*)/.exec(line);
          if (!match) continue;
          const entry = /entryEpochMs=(\d+) pid=(\d+)/.exec(match[2]);
          if (entry) {
            const ref = /incarnationRef=(\S+)/.exec(match[2]);
            // Only the reference names the incarnation: a PID may belong to a reused start.
            current = { pid: Number(entry[2]), entryEpochMs: Number(entry[1]), incarnation: ref?.[1] ?? null };
            event('engineEntry', Number(entry[1]), { ...current, workDir, source: 'fake Engine entry' });
          }
          const port = /JUSTSEARCH_API_PORT=(\d+)/.exec(match[2]);
          if (port) {
            // The preceding entry belongs to this log's current process, even when no ledger start survived.
            event('portEmitted', epoch(match[1]), { incarnation: current?.incarnation, pid: current?.pid,
              entryEpochMs: current?.entryEpochMs, workDir, source: 'fake Engine log' });
          }
        }
        for (const row of rows) {
          if (!row.incarnation) continue;
          const fields = { incarnation: `${row.incarnation.session}/${row.incarnation.n}`, workDir, source: 'exit ledger' };
          if (row.type === 'engine-intent' && row.reason === 'QUIT' && row.channel !== 'TERMINATE') event('quitRequested', epoch(row.requestedAt), fields);
          if (row.type === 'engine-exit') {
            event('forceIssued', epoch(row.terminateCalledAt), fields);
            event('exitObserved', epoch(row.observedAt), fields);
          }
        }
        record.incarnations.push(...incarnationEnds(workDir, rows, record.events));
      }
      record.finishedAtMs = Date.now();
      record.problems = problems;
      fs.writeFileSync(file, JSON.stringify(record, null, 2) + '\n');
      return record;
    },
  };
}
