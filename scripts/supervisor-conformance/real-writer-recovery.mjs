import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { DatabaseSync } from 'node:sqlite';

const repo = process.cwd();
const work = process.env.JUSTSEARCH_WRITER_RECOVERY_WORK
  ? path.resolve(process.env.JUSTSEARCH_WRITER_RECOVERY_WORK)
  : path.join(repo, 'tmp', 'lane-f-takeover', `writer-live-${Date.now()}`);
const state = path.join(work, 'state');
const data = path.join(work, 'data');
const indexBase = path.join(work, 'index');
const aiEnabled = process.env.JUSTSEARCH_WRITER_RECOVERY_AI_ENABLED === '1';
fs.mkdirSync(path.join(data, 'runtime'), { recursive: true });
fs.mkdirSync(state, { recursive: true });
const port = await new Promise((resolve, reject) => {
  const server = net.createServer();
  server.once('error', reject);
  server.listen(0, '127.0.0.1', () => {
    const p = server.address().port;
    server.close(() => resolve(p));
  });
});
const env = {
  ...process.env,
  JUSTSEARCH_SUPERVISOR_HARNESS: '1',
  JUSTSEARCH_DEV_RUNNER_STATE_ROOT: state,
  JUSTSEARCH_DEV_RUNNER_FRONTEND_COMMAND: JSON.stringify([
    process.execPath,
    '-e',
    'setInterval(() => {}, 60000)',
  ]),
  JUSTSEARCH_DEV_RUNNER_BACKEND_PORT_TIMEOUT_MS: '30000',
  JUSTSEARCH_DEV_RUNNER_BACKEND_READY_TIMEOUT_MS: '60000',
  JUSTSEARCH_BACKFILL_MAX_DOCS_BEFORE_COMMIT: '1',
  JUSTSEARCH_INDEX_COMMIT_TIMER_INTERVAL_MS: '600000',
  JUSTSEARCH_INDEX_BASE_PATH: indexBase,
  JUSTSEARCH_AI_EMBED_ENABLED: 'false',
  JUSTSEARCH_NER_ENABLED: 'false',
  JUSTSEARCH_SPLADE_ENABLED: 'false',
  JUSTSEARCH_RERANK_ENABLED: 'false',
  JUSTSEARCH_RERANK_CHUNKS_ENABLED: 'false',
  JAVA_OPTS:
    `${process.env.JAVA_OPTS ?? ''} -Djustsearch.eval.mode=true -Djustsearch.data.dir=${data}`,
  AI_OFFLINE: 'true',
  CI: '',
};
delete env.JUSTSEARCH_DEV_RUNNER_ENGINE_COMMAND;
if (aiEnabled) {
  delete env.JUSTSEARCH_AI_EMBED_ENABLED;
  delete env.JUSTSEARCH_NER_ENABLED;
  delete env.JUSTSEARCH_SPLADE_ENABLED;
  delete env.JUSTSEARCH_RERANK_ENABLED;
  delete env.JUSTSEARCH_RERANK_CHUNKS_ENABLED;
  delete env.AI_OFFLINE;
}
const runner = path.join(repo, 'scripts', 'dev', 'dev-runner.cjs');
const child = spawn(process.execPath, [
  runner, 'start', '--json', '--skip-build', '--clean', 'none', '--api-port', '0',
  '--ui-port', String(port), '--data-dir', data, '--session-id', 'writer-recovery-live',
], { cwd: repo, env, stdio: ['ignore', 'pipe', 'pipe'] });
let output = '';
let ownedRunId = null;
child.stdout.on('data', (b) => { output += b; process.stdout.write(b); });
child.stderr.on('data', (b) => { output += b; process.stderr.write(b); });

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const readJson = (file) => { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; } };
async function waitFor(label, timeoutMs, probe) {
  const end = Date.now() + timeoutMs;
  let value;
  while (Date.now() < end) {
    value = await probe();
    if (value) return value;
    await delay(100);
  }
  throw new Error(`timeout waiting for ${label}; last output=${output.slice(-4000)}`);
}
async function waitUntil(label, deadline, probe) {
  return waitFor(label, Math.max(1, deadline - Date.now()), probe);
}
async function request(apiPort, endpoint, options = {}) {
  const response = await fetch(`http://127.0.0.1:${apiPort}${endpoint}`, {
    ...options,
    signal: AbortSignal.timeout(5000),
  });
  const text = await response.text();
  return { status: response.status, text };
}
async function post(apiPort, endpoint, body) {
  return request(apiPort, endpoint, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body),
  });
}
function filesBelow(root, predicate) {
  const found = [];
  if (!fs.existsSync(root)) return found;
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    const full = path.join(root, entry.name);
    if (entry.isDirectory()) found.push(...filesBelow(full, predicate));
    else if (predicate(entry.name)) found.push(full);
  }
  return found;
}
function requireThat(condition, message) {
  if (!condition) throw new Error(message);
}
function jobStateFor(filename) {
  const database = new DatabaseSync(path.join(data, 'jobs.db'), { readOnly: true });
  try {
    return database
      .prepare('SELECT path, state FROM jobs WHERE path LIKE ? ORDER BY last_updated DESC LIMIT 1')
      .get(`%${filename}`);
  } finally {
    database.close();
  }
}
function acceptedCount(response) {
  try { return Number(JSON.parse(response.text).accepted ?? 0); } catch { return 0; }
}
function matchingHit(response, expectedPath, marker) {
  try {
    const expected = path.resolve(expectedPath).toLowerCase();
    return JSON.parse(response.text).results?.find((hit) => {
      const fields = hit?.fields ?? {};
      return String(fields.path ?? '').toLowerCase() === expected
        && String(fields.content_preview ?? '').includes(marker);
    });
  } catch {
    return null;
  }
}
function resolveOwnedRunId() {
  if (ownedRunId) return ownedRunId;
  const fromSupervisor = readJson(path.join(data, 'runtime', 'supervisor.v1.json'))?.runId;
  if (fromSupervisor) return fromSupervisor;
  const runs = path.join(state, 'runs');
  if (!fs.existsSync(runs)) return null;
  const ids = fs.readdirSync(runs, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name);
  return ids.length === 1 ? ids[0] : null;
}

let fixtureFailure;
try {
  const supervisorFile = path.join(data, 'runtime', 'supervisor.v1.json');
  const first = await waitFor('first running incarnation', 90000, async () => {
    const s = readJson(supervisorFile);
    return s?.state === 'running' ? s : null;
  });
  ownedRunId = first.runId;
  const manifest = readJson(path.join(data, 'runtime', 'manifest.json'));
  const apiPort = manifest.head.apiPort;
  const healthBefore = await waitFor('initial healthy Engine', 60000, async () => {
    try {
      const response = await request(apiPort, '/api/health');
      return response.status === 200 ? response : null;
    } catch { return null; }
  });
  const firstDoc = path.join(work, 'first.txt');
  fs.writeFileSync(firstDoc, 'firstdurablemarker quokka');
  const firstIngest = await waitFor('first ingest acceptance', 90000, async () => {
    try {
      const response = await post(apiPort, '/api/knowledge/ingest', { paths: [firstDoc] });
      return response.status >= 200 && response.status < 300 ? response : null;
    } catch { return null; }
  });
  requireThat(acceptedCount(firstIngest) > 0, `first ingest accepted no work: ${firstIngest.text}`);
  console.log('FIRST_INGEST', firstIngest);
  const firstDone = await waitFor('first job committed DONE', 60000, async () => {
    const row = jobStateFor('first.txt');
    return row?.state === 'DONE' ? row : null;
  });
  const existing = await waitFor('compound segment after committed first job', 60000, async () => {
    const files = filesBelow(indexBase, (name) => /^_[0-9a-z]+\.cfs$/.test(name));
    return files.length ? files : null;
  });
  const parsed = existing.map((file) => ({ file, n: Number.parseInt(path.basename(file).slice(1, -4), 36) }));
  parsed.sort((a, b) => b.n - a.n);
  const collision = path.join(path.dirname(parsed[0].file), `_${(parsed[0].n + 1).toString(36)}.cfs`);
  fs.writeFileSync(collision, 'collision', { flag: 'wx' });
  console.log('COLLISION', collision);
  const secondDoc = path.join(work, 'second.txt');
  fs.writeFileSync(secondDoc, 'secondreplayedmarker wombat');
  const secondIngest = await post(apiPort, '/api/knowledge/ingest', { paths: [secondDoc] });
  requireThat(secondIngest.status === 200, `second ingest was not accepted: ${JSON.stringify(secondIngest)}`);
  requireThat(acceptedCount(secondIngest) > 0, `second ingest accepted no work: ${secondIngest.text}`);
  const recoveryDeadline = Date.now() + 180000;
  const queuedBeforeDeath = await waitUntil('second job queue state before death', recoveryDeadline, async () => {
    const row = jobStateFor('second.txt');
    const current = readJson(supervisorFile);
    return row && ['PENDING', 'PROCESSING'].includes(row.state)
      && current?.incarnation === first.incarnation ? row : null;
  });
  console.log('SECOND_INGEST', secondIngest, 'QUEUE_BEFORE_DEATH', queuedBeforeDeath);
  const restarted = await waitUntil('supervised restart', recoveryDeadline, async () => {
    const s = readJson(supervisorFile);
    return s?.state === 'running' && s.incarnation > first.incarnation ? s : null;
  });
  const restartedManifest = await waitUntil('new manifest', recoveryDeadline, async () => {
    const m = readJson(path.join(data, 'runtime', 'manifest.json'));
    return m?.instanceId && m.instanceId !== manifest.instanceId ? m : null;
  });
  requireThat(restarted.runId === first.runId, 'supervisor changed run identity across restart');
  requireThat(restarted.restartCount === 1, `restartCount=${restarted.restartCount}, expected 1`);
  requireThat(restarted.lastExit?.code === 1, `exit=${JSON.stringify(restarted.lastExit)}, expected code 1`);
  requireThat(restarted.lastExit?.reason === 'fatal_or_uncaught', 'writer exit was misclassified');
  requireThat(restarted.lastExit?.requestedReason == null, 'writer exit was replaced by a requested shutdown');
  requireThat(restartedManifest.instanceId !== manifest.instanceId, 'Engine incarnation did not change');
  const firstIncarnationLog = await waitUntil('first incarnation diagnostic log', recoveryDeadline, async () => {
    const file = path.join(
      state, 'runs', first.runId, 'incarnations', String(first.incarnation), 'logs', 'engine.log',
    );
    return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
  });
  requireThat(
    firstIncarnationLog.includes('FileAlreadyExistsException')
      && firstIncarnationLog.includes(path.basename(collision)),
    'the first incarnation did not record the seeded segment collision as the writer tragedy',
  );
  const healthAfter = await waitUntil('restarted health', recoveryDeadline, async () => {
    try {
      const response = await request(restartedManifest.head.apiPort, '/api/health');
      return response.status === 200 ? response : null;
    } catch { return null; }
  });
  const hit = await waitUntil('replayed document searchability', recoveryDeadline, async () => {
    try {
      const response = await post(restartedManifest.head.apiPort, '/api/knowledge/search', {
        query: 'secondreplayedmarker', limit: 5, mode: 'text',
      });
      return response.status === 200 && matchingHit(response, secondDoc, 'secondreplayedmarker')
        ? response : null;
    } catch { return null; }
  });
  const durableHit = await waitUntil('pre-fault durable document searchability', recoveryDeadline, async () => {
    try {
      const response = await post(restartedManifest.head.apiPort, '/api/knowledge/search', {
        query: 'firstdurablemarker', limit: 5, mode: 'text',
      });
      return response.status === 200 && matchingHit(response, firstDoc, 'firstdurablemarker')
        ? response : null;
    } catch { return null; }
  });
  const finalState = readJson(supervisorFile);
  requireThat(
    finalState?.incarnation === 2 && finalState?.restartCount === 1,
    `unexpected additional Engine incarnation: ${JSON.stringify(finalState)}`,
  );
  console.log('PASS', JSON.stringify({ first, healthBefore, firstDone, queuedBeforeDeath,
    restarted, healthAfter, hit, durableHit, work }));
} catch (error) {
  fixtureFailure = error;
}
try {
  const cleanupRunId = resolveOwnedRunId();
  requireThat(cleanupRunId, `could not resolve owned run identity under ${state}`);
  const stopArgs = [runner, 'stop', '--json', '--session-id', 'writer-recovery-live',
    '--run', cleanupRunId];
  const stopped = spawnSync(process.execPath, stopArgs, {
    cwd: repo, env, encoding: 'utf8', timeout: 30000,
  });
  console.log('STOP', stopped.status, stopped.stdout, stopped.stderr);
  if (stopped.status !== 0 || !stopped.stdout.includes('"portsClosed":true')) {
    throw new Error(`identity-checked dev-runner cleanup failed: ${stopped.stdout} ${stopped.stderr}`);
  }
} catch (cleanupFailure) {
  if (fixtureFailure) {
    throw new AggregateError([fixtureFailure, cleanupFailure], 'writer recovery and cleanup failed');
  }
  throw cleanupFailure;
}
if (fixtureFailure) throw fixtureFailure;
