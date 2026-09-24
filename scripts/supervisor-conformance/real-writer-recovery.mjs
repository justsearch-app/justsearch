import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { DatabaseSync } from 'node:sqlite';
import { exerciseMigrationRestart } from './migration-restart-scenario.mjs';
import { exerciseHostileLocks } from './hostile-lock-scenario.mjs';
import { exerciseProcessingReplay } from './processing-replay-scenario.mjs';
import { exerciseOperationResume } from './operation-resume-scenario.mjs';
import { exerciseOperationFault } from './operation-fault-scenario.mjs';
import {
  exerciseBulkFault, BULK_FAULT_CASES,
  exerciseInstallerActivationFault, INSTALLER_FAULT_CASES, writeRetainedInstallerCandidate,
  exerciseLiveModelAB,
} from './bulk-fault-scenario.mjs';
import { createOperationKey } from '../../modules/ui-web/src/api/operationKey.ts';
import { captureFromLive } from '../codegen/gen-api-client.mjs';
import { exerciseReconfigureRefresh } from './reconfigure-refresh-scenario.mjs';

const repo = process.cwd();
const scenario = process.env.JUSTSEARCH_REAL_RECOVERY_SCENARIO;
const operationFault = new Set(['ingest-before-accept', 'settings-before-accept',
  'ingest-after-accept-before-effect', 'settings-after-accept-before-effect',
  'ingest-after-effect-before-checkpoint', 'settings-after-effect-before-checkpoint',
  'ingest-client-disconnect', 'settings-mid-compose']).has(scenario);
const bulkFault = Object.hasOwn(BULK_FAULT_CASES, scenario ?? '');
const installerFault = Object.hasOwn(INSTALLER_FAULT_CASES, scenario ?? '');
const modelBoot = scenario === 'model-x-y-boot' || scenario === 'model-missing-x-boot';
const modelLiveAB = scenario === 'model-live-a-b';
const distinctModelB = modelLiveAB && process.env.JUSTSEARCH_WRITER_RECOVERY_DISTINCT_B === '1';
const mixedChatInstaller = installerFault
  && process.env.JUSTSEARCH_WRITER_RECOVERY_MIXED_CHAT === '1';
function readActiveGenerationManifest(base) {
  const active = JSON.parse(fs.readFileSync(path.join(base, 'state.json'), 'utf8'));
  return JSON.parse(fs.readFileSync(path.join(base, 'indices', active.active_generation,
    '.justsearch-index-generation.json'), 'utf8'));
}
function findRetainedModelsRoot() {
  let current = repo;
  for (;;) {
    const candidate = path.join(current, 'models');
    if (fs.existsSync(path.join(candidate, 'onnx', 'gte-multilingual-base', 'model_fp16.onnx'))) {
      return candidate;
    }
    const parent = path.dirname(current);
    if (parent === current) throw new Error('retained alternate model Y is unavailable');
    current = parent;
  }
}
const operationKey = operationFault || bulkFault || installerFault || modelLiveAB
  ? createOperationKey() : null;
const work = process.env.JUSTSEARCH_WRITER_RECOVERY_WORK
  ? path.resolve(process.env.JUSTSEARCH_WRITER_RECOVERY_WORK)
  : path.join(repo, 'tmp', 'lane-f-takeover', `writer-live-${Date.now()}`);
const state = path.join(work, 'state');
const data = path.join(work, 'data');
if (modelLiveAB) {
  // This owned installed fixture previously exercised a different crash cut.
  for (const marker of ['operation-fault-reached.json', 'operation-fault-release']) {
    fs.rmSync(path.join(data, 'runtime', marker), { force: true });
  }
}
const lockScenario = process.env.JUSTSEARCH_REAL_RECOVERY_SCENARIO?.startsWith('lock-');
const indexBase = path.join(lockScenario ? data : work, 'index');
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
  JUSTSEARCH_BACKFILL_MAX_DOCS_BEFORE_COMMIT: lockScenario ? '50' : '1',
  JUSTSEARCH_INDEX_COMMIT_TIMER_INTERVAL_MS: lockScenario ? '1000' : '600000',
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
delete env.JUSTSEARCH_OPERATION_FAULT_KEY;
delete env.JUSTSEARCH_OPERATION_FAULT_KIND;
delete env.JUSTSEARCH_OPERATION_FAULT_POINT;
delete env.JUSTSEARCH_OPERATION_FAULT_SELF_EXIT;
if (lockScenario) env.JUSTSEARCH_BACKFILL_COMMIT_INTERVAL_MS = '1000';
if (['writer', 'processing', 'operation'].includes(scenario) || scenario === undefined
    || operationFault || lockScenario) {
  // Recovery revalidates persisted scope. Keep the successful replay corpus under a
  // real watched root; fresh out-of-root authority intentionally cannot survive restart.
  const corpus = path.join(work, lockScenario ? 'contention-corpus' : 'corpus');
  fs.mkdirSync(corpus, { recursive: true });
  fs.writeFileSync(path.join(data, 'watched_roots.json'), JSON.stringify({
    schemaVersion: 1, roots: [{ path: corpus }],
  }));
}
if (['processing', 'operation'].includes(scenario) || operationFault || bulkFault || installerFault) {
  // Observe durable state after actual Engine death and before its successor claims it.
  env.JUSTSEARCH_SUPERVISOR_COOLDOWN_INCREMENT_MS = '10000';
  env.JUSTSEARCH_SUPERVISOR_MAX_COOLDOWN_MS = '10000';
}
if (operationFault) {
  env.JUSTSEARCH_OPERATION_FAULT_KEY = operationKey;
  env.JUSTSEARCH_OPERATION_FAULT_KIND = scenario.startsWith('settings-') ? 'reconfigure' : 'ingest';
  env.JUSTSEARCH_OPERATION_FAULT_POINT = scenario.endsWith('before-accept') ? 'before-accept'
    : scenario.endsWith('before-checkpoint') ? 'after-effect'
      : scenario === 'settings-mid-compose' ? 'settings-mid-compose' : 'after-accept';
}
if (bulkFault) {
  env.JUSTSEARCH_OPERATION_FAULT_KEY = operationKey;
  env.JUSTSEARCH_OPERATION_FAULT_KIND = 'reindex';
  env.JUSTSEARCH_OPERATION_FAULT_POINT = BULK_FAULT_CASES[scenario].phase;
  const roots = ['bulk-root-a', 'bulk-root-b'].map(name => ({ path: path.join(work, name) }));
  for (const root of roots) fs.mkdirSync(root.path, { recursive: true });
  fs.writeFileSync(path.join(data, 'watched_roots.json'), JSON.stringify({ schemaVersion: 1, roots }));
  delete env.JUSTSEARCH_AI_EMBED_ENABLED;
  delete env.AI_OFFLINE;
}
if (installerFault) {
  if (mixedChatInstaller) env.JUSTSEARCH_GPU_ENABLED = 'true';
  env.JUSTSEARCH_EMBED_GPU_ENABLED = mixedChatInstaller ? 'true' : 'false';
  env.JUSTSEARCH_NER_GPU_ENABLED = mixedChatInstaller ? 'true' : 'false';
  env.JUSTSEARCH_SPLADE_GPU_ENABLED = mixedChatInstaller ? 'true' : 'false';
  env.JUSTSEARCH_OPERATION_FAULT_KEY = operationKey;
  env.JUSTSEARCH_OPERATION_FAULT_KIND = 'reindex';
  env.JUSTSEARCH_OPERATION_FAULT_POINT = INSTALLER_FAULT_CASES[scenario].phase;
  if (scenario === 'installer-before-receipt') env.JUSTSEARCH_OPERATION_FAULT_SELF_EXIT = '1';
  const roots = ['installer-root-a', 'installer-root-b'].map(name => ({ path: path.join(work, name) }));
  for (const root of roots) fs.mkdirSync(root.path, { recursive: true });
  fs.writeFileSync(path.join(data, 'watched_roots.json'), JSON.stringify({ schemaVersion: 1, roots }));
  delete env.JUSTSEARCH_AI_EMBED_ENABLED;
  delete env.JUSTSEARCH_NER_ENABLED;
  delete env.JUSTSEARCH_SPLADE_ENABLED;
  delete env.JUSTSEARCH_NER_MODEL_PATH;
  delete env.JUSTSEARCH_SPLADE_MODEL_PATH;
  delete env.AI_OFFLINE;
  if (mixedChatInstaller) env.JUSTSEARCH_WRITER_RECOVERY_MIXED_CHAT = '1';
  else delete env.JUSTSEARCH_WRITER_RECOVERY_MIXED_CHAT;
}
if (modelLiveAB) {
  env.JUSTSEARCH_OPERATION_FAULT_KEY = operationKey;
  env.JUSTSEARCH_OPERATION_FAULT_KIND = 'reindex';
  env.JUSTSEARCH_OPERATION_FAULT_POINT = 'installer-before-marker';
  if (distinctModelB) {
    env.JUSTSEARCH_GPU_ENABLED = 'true';
    env.JUSTSEARCH_EMBED_GPU_ENABLED = 'true';
    env.JUSTSEARCH_NER_GPU_ENABLED = 'true';
    env.JUSTSEARCH_SPLADE_GPU_ENABLED = 'true';
  }
}
const installerCandidate = installerFault
  ? writeRetainedInstallerCandidate({ data, requireThat, mixedChat: mixedChatInstaller }) : null;
delete env.JUSTSEARCH_DEV_RUNNER_ENGINE_COMMAND;
if (aiEnabled) {
  delete env.JUSTSEARCH_AI_EMBED_ENABLED;
  delete env.JUSTSEARCH_NER_ENABLED;
  delete env.JUSTSEARCH_SPLADE_ENABLED;
  delete env.JUSTSEARCH_RERANK_ENABLED;
  delete env.JUSTSEARCH_RERANK_CHUNKS_ENABLED;
  delete env.AI_OFFLINE;
}
if (process.env.JUSTSEARCH_REAL_RECOVERY_SCENARIO === 'migration') {
  const sources = path.join(work, 'migration-sources');
  fs.mkdirSync(sources, { recursive: true });
  fs.writeFileSync(path.join(data, 'watched_roots.json'), JSON.stringify({
    schemaVersion: 1, roots: [{ path: sources }],
  }));
  // A locally resolvable embedding model makes its fingerprint a cutover precondition.
  // Exercise that model instead of disabling embeddings and bypassing verification.
  delete env.JUSTSEARCH_AI_EMBED_ENABLED;
  delete env.AI_OFFLINE;
}
if (modelBoot) {
  delete env.JUSTSEARCH_AI_EMBED_ENABLED;
  delete env.JUSTSEARCH_NER_ENABLED;
  delete env.JUSTSEARCH_SPLADE_ENABLED;
  delete env.AI_OFFLINE;
  env.JUSTSEARCH_EMBED_GPU_ENABLED = 'false';
  env.JUSTSEARCH_NER_GPU_ENABLED = 'false';
  env.JUSTSEARCH_SPLADE_GPU_ENABLED = 'false';
  const settingsPath = path.join(data, 'ui', 'settings.json');
  const saved = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
  const active = readActiveGenerationManifest(indexBase);
  const modelX = active?.models?.embedding?.id;
  if (!modelX || !path.resolve(modelX).startsWith(path.resolve(work, 'installer-models'))) {
    throw new Error('model boot fixture does not have the active generation bound to X');
  }
  if (scenario === 'model-x-y-boot') {
    const modelYDir = path.join(work, 'desired-model-y');
    fs.mkdirSync(modelYDir, { recursive: true });
    const source = path.join(findRetainedModelsRoot(), 'onnx', 'gte-multilingual-base');
    if (!fs.existsSync(path.join(modelYDir, 'model.onnx'))) {
      fs.linkSync(path.join(source, 'model_fp16.onnx'), path.join(modelYDir, 'model.onnx'));
    }
    if (!fs.existsSync(path.join(modelYDir, 'tokenizer.json'))) {
      fs.linkSync(path.join(source, 'tokenizer.json'), path.join(modelYDir, 'tokenizer.json'));
    }
    fs.writeFileSync(path.join(modelYDir, 'model_manifest.json'),
      '{"cpu":"model.onnx","capabilities":{"cpu_precision":"fp16"}}\n');
    saved.settings.embedOnnxModelPath = modelYDir;
    fs.writeFileSync(settingsPath, `${JSON.stringify(saved, null, 2)}\n`);
  } else {
    const missing = `${modelX}.held-absent`;
    if (!fs.existsSync(modelX) && !fs.existsSync(missing)) {
      throw new Error('model boot fixture cannot move X to a private absent path');
    }
    if (fs.existsSync(modelX) && !fs.existsSync(missing)) fs.renameSync(modelX, missing);
  }
}
const runner = path.join(repo, 'scripts', 'dev', 'dev-runner.cjs');
const child = spawn(process.execPath, [
  runner, 'start', '--json', '--skip-build', '--clean', 'none', '--api-port', '0',
  '--ui-port', String(port), '--data-dir', data, '--session-id', 'writer-recovery-live',
  '--lease-duration-sec', '600',
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
async function request(apiPort, endpoint, options = {}, timeoutMs = 5000) {
  const response = await fetch(`http://127.0.0.1:${apiPort}${endpoint}`, {
    ...options,
    signal: AbortSignal.timeout(timeoutMs),
  });
  const text = await response.text();
  return { status: response.status, text };
}
async function post(apiPort, endpoint, body, timeoutMs = 5000) {
  return request(apiPort, endpoint, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body),
  }, timeoutMs);
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
      .prepare('SELECT path, state, last_updated, scan_id, unit_revision FROM jobs WHERE path LIKE ? ORDER BY last_updated DESC LIMIT 1')
      .get(`%${filename}`);
  } finally {
    database.close();
  }
}
function requireOperationSuccess(response, label, expectedStatus = 200) {
  let body;
  try { body = JSON.parse(response.text); }
  catch { throw new Error(`${label} returned invalid JSON: ${response.text}`); }
  requireThat(response.status === expectedStatus && body.success === true,
    `${label} failed: HTTP ${response.status} ${response.text}`);
  const metadata = body.structuredData;
  requireThat(typeof metadata?.operationKey === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(metadata.operationKey)
    && Number.isInteger(metadata.operationRecordId),
  `${label} omitted canonical operation metadata: ${response.text}`);
  return { body, operationKey: metadata.operationKey,
    operationRecordId: metadata.operationRecordId };
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
    .filter((entry) => entry.isDirectory() && fs.existsSync(path.join(runs, entry.name, 'run.json')))
    .map((entry) => entry.name);
  return ids.length === 1 ? ids[0] : null;
}

let fixtureFailure;
try {
  const supervisorFile = path.join(data, 'runtime', 'supervisor.v1.json');
  const initial = await waitFor('first running incarnation with matching manifest', 90000, async () => {
    if (child.exitCode !== null || child.signalCode !== null) {
      throw new Error(`startup runner exited before initial discovery: exit=${child.exitCode} signal=${child.signalCode}; ${output}`);
    }
    const s = readJson(supervisorFile);
    const m = readJson(path.join(data, 'runtime', 'manifest.json'));
    return s?.state === 'running' && m?.pid === s.pid && m?.instanceId === s.instanceId
      && m?.head?.apiPort === s.apiPort ? { supervisor: s, manifest: m } : null;
  });
  const first = initial.supervisor;
  ownedRunId = first.runId;
  const manifest = initial.manifest;
  const apiPort = manifest.head.apiPort;
  const healthBefore = await waitFor('initial healthy Engine', 60000, async () => {
    try {
      const response = await request(apiPort, '/api/health');
      return response.status === 200 ? response : null;
    } catch { return null; }
  });
  if (scenario === 'route-capture') {
    const captured = await captureFromLive(`http://127.0.0.1:${apiPort}`);
    requireThat(captured.routes.every((route) => ![
      '/api/inference/reload', '/api/admin/inference/reload',
    ].includes(route.path)), 'retired inference reload route remained registered');
    console.log('PASS route-capture', JSON.stringify({ routeCount: captured.count, work }));
  } else if (scenario === 'reconfigure-refresh') {
    await exerciseReconfigureRefresh({ apiPort, manifest, request, post, waitFor,
      requireThat, createOperationKey });
  } else if (bulkFault) {
    await exerciseBulkFault({ work, data, indexBase, first, manifest, apiPort, readJson, waitFor,
      request, post, requireThat, requireOperationSuccess, createOperationKey, matchingHit,
      scenario, operationKey, output: () => output });
  } else if (installerFault) {
    await exerciseInstallerActivationFault({ work, data, indexBase, first, manifest, apiPort,
      candidate: installerCandidate,
      readJson, waitFor, request, post, requireThat, requireOperationSuccess, matchingHit,
      scenario, operationKey, output: () => output });
  } else if (modelLiveAB) {
    await exerciseLiveModelAB({ work, data, indexBase, manifest, apiPort, operationKey,
      readJson, waitFor, request, post, requireThat, matchingHit, distinctModelB });
  } else if (modelBoot) {
    const initialStatus = await waitFor('model binding boot status', 60000, async () => {
      try {
        const response = await request(apiPort, '/api/status', {}, 15000);
        return response.status === 200 ? JSON.parse(response.text) : null;
      } catch { return null; }
    });
    console.log('MODEL_BOOT_STATUS', JSON.stringify({ scenario,
      compatibility: initialStatus.worker.compatibility,
      encoders: initialStatus.readiness.engineComponents.encoders }));
    const file = path.join(work, 'installer-root-a', 'installer-0.txt');
    const marker = fs.readFileSync(file, 'utf8').split(/\s+/)[0];
    const search = await waitFor('model binding text search', 60000, async () => {
      try {
        const response = await post(apiPort, '/api/knowledge/search',
          { query: marker, limit: 10, mode: 'text' }, 15000);
        return response.status === 200 && matchingHit(response, file, marker) ? response : null;
      } catch { return null; }
    });
    const status = await waitFor('model binding settled status', 60000, async () => {
      try {
        const response = await request(apiPort, '/api/status', {}, 15000);
        if (response.status !== 200) return null;
        const value = JSON.parse(response.text);
        return value.components.encoders.state === 'STARTING' ? null : value;
      } catch { return null; }
    });
    const active = readActiveGenerationManifest(indexBase);
    if (scenario === 'model-x-y-boot') {
      requireThat(status.worker.compatibility.embeddingCompatState === 'COMPATIBLE',
        'serving X was marked incompatible with desired Y');
      requireThat(status.worker.compatibility.embeddingFingerprintCurrent
        === active.models.embedding.sha256, 'serving X lost its exact model fingerprint');
      requireThat(status.worker.compatibility.indexSchemaFpCurrent
        === status.worker.compatibility.indexSchemaFpStored,
      'serving X status reported the desired Y schema');
      requireThat(status.components.encoders.state === 'READY',
        'serving X encoders did not become READY');
      requireThat(status.readiness.engineComponents.encoders.appliedVersion
        !== status.readiness.engineComponents.encoders.desiredVersion,
      'desired Y was not reported as pending');
      const vector = await post(apiPort, '/api/knowledge/search',
        { query: marker, limit: 10, mode: 'vector' }, 30000);
      requireThat(vector.status === 200 && Array.isArray(JSON.parse(vector.text).results),
        `serving X could not answer a real vector query while Y is desired: ${vector.text}`);
      console.log('MODEL_BINDING_VECTOR_QUERY', JSON.stringify({
        status: vector.status, results: JSON.parse(vector.text).results.length,
      }));
    } else {
      requireThat(status.worker.compatibility.embeddingCompatState === 'UNAVAILABLE',
        'missing X did not make embedding compatibility unavailable');
      requireThat(status.readiness.engineComponents.encoders.evidence === 'INDEX_MODEL_NOT_INSTALLED',
        'missing X did not publish the required model-unavailable evidence');
    }
    console.log('MODEL_BINDING_BOOT_PASS', JSON.stringify({ scenario, marker,
      compatibility: status.worker.compatibility,
      encoders: status.readiness.engineComponents.encoders,
      search: JSON.parse(search.text).results.length }));
  } else if (operationFault) {
    await exerciseOperationFault({ work, data, first, manifest, apiPort, readJson, waitFor,
      request, post, requireThat, requireOperationSuccess, createOperationKey, matchingHit, jobStateFor,
      scenario, operationKey });
  } else if (process.env.JUSTSEARCH_REAL_RECOVERY_SCENARIO === 'operation') {
    await exerciseOperationResume({ work, data, first, manifest, apiPort, readJson, waitFor,
      request, post, requireThat, requireOperationSuccess, createOperationKey, matchingHit, jobStateFor });
  } else if (process.env.JUSTSEARCH_REAL_RECOVERY_SCENARIO === 'processing') {
    await exerciseProcessingReplay({ work, data, first, manifest, apiPort, readJson, waitFor,
      request, post, requireThat, requireOperationSuccess, createOperationKey, matchingHit, jobStateFor });
  } else if (lockScenario) {
    await exerciseHostileLocks({ work, data, first, readJson, waitFor, request, post,
      requireThat, requireOperationSuccess, createOperationKey });
  } else if (process.env.JUSTSEARCH_REAL_RECOVERY_SCENARIO === 'migration') {
    await exerciseMigrationRestart({ work, data, indexBase, first, manifest, apiPort,
      readJson, waitFor, request, post, requireThat, requireOperationSuccess, createOperationKey, matchingHit,
      output: () => output });
  } else {
  const firstDoc = path.join(work, 'corpus', 'first.txt');
  fs.writeFileSync(firstDoc, 'firstdurablemarker quokka');
  const firstOperationKey = createOperationKey();
  const firstIngest = await waitFor('first ingest acceptance', 90000, async () => {
    try {
      const response = await post(apiPort, '/api/knowledge/ingest', {
        paths: [firstDoc], idempotencyKey: firstOperationKey,
      });
      return response.status >= 200 && response.status < 300 ? response : null;
    } catch { return null; }
  });
  const firstReceipt = requireOperationSuccess(firstIngest, 'first ingest');
  requireThat(firstReceipt.operationKey === firstOperationKey,
    `first ingest changed its supplied operation key: ${firstIngest.text}`);
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
  const secondDoc = path.join(work, 'corpus', 'second.txt');
  fs.writeFileSync(secondDoc, 'secondreplayedmarker wombat');
  const secondOperationKey = createOperationKey();
  const secondIngest = await post(apiPort, '/api/knowledge/ingest', {
    paths: [secondDoc], idempotencyKey: secondOperationKey,
  });
  const secondReceipt = requireOperationSuccess(secondIngest, 'second ingest');
  requireThat(secondReceipt.operationKey === secondOperationKey,
    `second ingest changed its supplied operation key: ${secondIngest.text}`);
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
  }
} catch (error) {
  fixtureFailure = error;
}
try {
  if (lockScenario) {
    fs.writeFileSync(path.join(work, 'intruder-stop'), 'stop');
    await waitFor('JUnit releases hostile locks before owned cleanup', 10000,
      () => fs.existsSync(path.join(work, 'intruder-stopped')));
  }
  const cleanupRunId = resolveOwnedRunId();
  if (!cleanupRunId) {
    const observedExit = output.split(/\r?\n/).filter(line => line.startsWith('{'))
      .map(line => { try { return JSON.parse(line); } catch { return null; } })
      .find(value => value?.error?.code === 'ENGINE_EXITED_DURING_DISCOVERY')?.error?.details;
    requireThat(fixtureFailure && child.exitCode === 1 && observedExit?.initialDiscovery === true
      && observedExit.childReplaced === false
      && (Number.isInteger(observedExit.exitCode) || typeof observedExit.signalCode === 'string'),
    `could not resolve registered run or prove initial Engine exit under ${state}`);
    console.log('INITIAL_ENGINE_EXIT_OBSERVED', JSON.stringify(observedExit));
  } else {
  const stopArgs = [runner, 'stop', '--json', '--session-id', 'writer-recovery-live',
    '--run', cleanupRunId];
  const stopped = spawnSync(process.execPath, stopArgs, {
    cwd: repo, env, encoding: 'utf8', timeout: 30000,
  });
  console.log('STOP', stopped.status, stopped.stdout, stopped.stderr);
  if (stopped.status !== 0 || !stopped.stdout.includes('"portsClosed":true')) {
    throw new Error(`identity-checked dev-runner cleanup failed: ${stopped.stdout} ${stopped.stderr}`);
  }
  }
} catch (cleanupFailure) {
  if (fixtureFailure) {
    throw new AggregateError([fixtureFailure, cleanupFailure], 'writer recovery and cleanup failed');
  }
  throw cleanupFailure;
}
if (fixtureFailure) throw fixtureFailure;
