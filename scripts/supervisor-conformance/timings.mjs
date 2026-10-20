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
    }, events: [], waits: [], measurements: [] };
  const runs = [];
  const samplers = [];
  let launches = 0;
  const seen = new Set();
  const event = (phase, atMs, fields = {}) => {
    if (!Number.isFinite(atMs)) return;
    const key = JSON.stringify([phase, fields.incarnation, fields.pid, fields.workDir, fields.launch]);
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
        try { state = JSON.parse(fs.readFileSync(statePath, 'utf8')); }
        catch { return; } // Atomic replacement may briefly leave no readable live record.
        this.state(state, statePath);
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
        const starts = rows.filter(row => row.type === 'engine-start');
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
            const start = starts.find(row => row.pid === Number(entry[2]));
            const ref = /incarnationRef=(\S+)/.exec(match[2]);
            current = { pid: Number(entry[2]), incarnation: ref?.[1]
              ?? (start ? `${start.incarnation.session}/${start.incarnation.n}` : null) };
            event('engineEntry', Number(entry[1]), { ...current, workDir, source: 'fake Engine entry' });
          }
          const port = /JUSTSEARCH_API_PORT=(\d+)/.exec(match[2]);
          if (port) {
            // The preceding entry belongs to this log's current process, even when no ledger start survived.
            event('portEmitted', epoch(match[1]), { incarnation: current?.incarnation, pid: current?.pid, workDir, source: 'fake Engine log' });
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
      }
      record.finishedAtMs = Date.now();
      record.problems = problems;
      fs.writeFileSync(file, JSON.stringify(record, null, 2) + '\n');
      return record;
    },
  };
}
