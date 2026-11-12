/**
 * Follow-up B1, FB6 — the doctor prints the Worker's state, never `worker=undefined`.
 *
 * Runs the real doctor against a stand-in Engine: a local HTTP server on port 0 answering
 * /api/status, found through an isolated run register (JUSTSEARCH_DEV_RUNNER_STATE_ROOT). The
 * Worker is the Engine's `index` component in the status's lifecycle subset.
 *
 * Run with: `node scripts/dev/doctor.test.mjs`
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const doctor = path.join(path.dirname(fileURLToPath(import.meta.url)), 'doctor.mjs');

const component = (state) => ({ state, reason_code: null, state_since: '2026-11-12T12:00:00Z' });
const statusWith = (components) => ({
  schema_version: 2,
  lifecycle: { state: 'LIFECYCLE_STATE_READY', reason_code: null, message: null },
  components,
  worker: { core: { indexedDocuments: 5, indexedDocumentsUnknown: false } },
  aiReady: false,
});

/** The doctor's text output and exit code against an Engine answering `status`. */
async function runDoctor(status) {
  const server = http.createServer((req, res) => {
    if (req.url === '/api/status') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(status));
    } else {
      res.writeHead(404).end();
    }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const stateRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'doctor-test-'));
  try {
    const { port } = server.address();
    fs.mkdirSync(path.join(stateRoot, 'runs', 'r1'), { recursive: true });
    fs.writeFileSync(path.join(stateRoot, 'active.json'), JSON.stringify({ runId: 'r1' }));
    fs.writeFileSync(path.join(stateRoot, 'runs', 'r1', 'run.json'), JSON.stringify({ apiBaseUrl: `http://127.0.0.1:${port}` }));
    const child = spawn(process.execPath, [doctor], {
      env: { ...process.env, JUSTSEARCH_DEV_RUNNER_STATE_ROOT: stateRoot },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let out = '';
    child.stdout.on('data', (chunk) => { out += chunk; });
    child.stderr.on('data', (chunk) => { out += chunk; });
    const code = await new Promise((resolve) => child.on('close', resolve));
    return { out, code, line: out.split('\n').find((l) => l.startsWith('Live stack')) ?? '' };
  } finally {
    server.close();
    fs.rmSync(stateRoot, { recursive: true, force: true });
  }
}

const allReady = { api: component('READY'), index: component('READY'), encoders: component('READY'), generative: component('ABSENT') };

{
  const { line, code, out } = await runDoctor(statusWith(allReady));
  assert.match(line, /worker=READY, indexed=5/, out);
  assert.doesNotMatch(out, /undefined/);
  assert.equal(code, 0);
}
{
  const { line, code, out } = await runDoctor(statusWith({ ...allReady, index: component('STARTING') }));
  assert.match(line, /worker=STARTING/, out);
  assert.match(out, /worker not yet READY; this is informational/);
  assert.equal(code, 0);
}
{
  const { line, code, out } = await runDoctor(statusWith({ ...allReady, index: component('FAILED') }));
  assert.match(line, /worker=FAILED/, out);
  assert.equal(code, 2, 'a running stack whose Worker failed is broken');
}
{
  // The Engine reports no Worker state: the field is left out rather than printed as undefined.
  const { line, out } = await runDoctor(statusWith({ api: component('READY') }));
  assert.match(line, /^Live stack \([^)]*\): indexed=5/, out);
  assert.doesNotMatch(out, /worker=|undefined/);
}
console.log('doctor.test.mjs: 4 cases passed');
