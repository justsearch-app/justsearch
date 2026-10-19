/**
 * Agents find the desktop app's Engine: the production MCP server's discover() and the MCPB
 * bridge (packed and extracted outside the checkout, as a host runs it) must connect with no
 * setup to the Engine whose runtime manifest sits in the desktop app's data folder, validate
 * every candidate manifest, honour explicit settings, and name every folder tried on failure.
 *
 * Every Engine here is a loopback stub that answers like the real one (GET /api/runtime/live
 * with pid + instanceId, /api/health, /api/status, /api/mcp/token, POST /mcp). Children run with
 * a temporary HOME / XDG_DATA_HOME / APPDATA and a temporary cwd, so no real data folder, dev
 * checkout or running Engine on this machine is consulted.
 *
 * Run: node --test scripts/lib/engine-discovery.test.mjs
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { packMcpb } from '../ci/pack-mcpb.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '..', '..');
const require = createRequire(import.meta.url);
const DISCOVERY_MJS = path.join(repoRoot, 'scripts', 'prod', 'justsearch-mcp', 'discovery.mjs');
const PROD_MCP_ENTRY = path.join(repoRoot, 'scripts', 'prod', 'justsearch-mcp.mjs');
const ENGINE_DISCOVERY = path.join(repoRoot, 'packaging', 'mcpb', 'server', 'engine-discovery.cjs');
const IS_WINDOWS = process.platform === 'win32';
const IS_LINUX = process.platform === 'linux';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

/** Fail with what was being waited for when promise has not settled within ms. */
function within(promise, ms, what) {
  let timer;
  const expiry = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${what}: nothing within ${ms} ms`)), ms);
  });
  return Promise.race([promise, expiry]).finally(() => clearTimeout(timer));
}

const STOP_MS = 5000;

/**
 * Every child process a test starts, until it has exited. stop says how it is meant to end:
 * 'self' (it exits on its own; still running at cleanup is reported), 'stdin' (it exits when its
 * stdin closes; a kill is reported) or 'kill' (a helper server stopped by a signal).
 */
const liveChildren = new Set();

function spawnNode(args, options, stop) {
  const child = spawn(process.execPath, args, options);
  const entry = { child, stop, what: path.basename(String(args[0])).slice(0, 40), exited: new Promise((resolve) => child.once('exit', resolve)) };
  liveChildren.add(entry);
  entry.exited.then(() => liveChildren.delete(entry));
  return child;
}

/**
 * Stop one child and wait for it to exit: its own way first, then SIGTERM, then SIGKILL.
 * Returns { problem, alive }. A child alive after SIGKILL is let go (unref'd, pipes closed) so
 * the runner can still exit; the caller must not remove its folders.
 */
async function stopChild({ child, stop, what, exited }) {
  const name = `child pid ${child.pid} (${what})`;
  const gone = () => within(exited, STOP_MS, '').then(() => true, () => false);
  const notes = [];
  if (stop === 'stdin') {
    child.stdin.end();
    if (await gone()) return { problem: null, alive: false };
    notes.push(`did not exit within ${STOP_MS} ms of its stdin closing`);
  } else if (stop === 'self') {
    notes.push('was still running at cleanup');
  }
  child.kill('SIGTERM');
  if (await gone()) {
    return { problem: notes.length ? `${name} ${notes.join(', ')}; terminated` : null, alive: false };
  }
  notes.push(`ignored SIGTERM for ${STOP_MS} ms`);
  child.kill('SIGKILL');
  if (await gone()) return { problem: `${name} ${notes.join(', ')}; force-killed`, alive: false };
  child.unref();
  for (const stream of [child.stdin, child.stdout, child.stderr]) stream?.destroy();
  return { problem: `${name} ${notes.join(', ')}; STILL ALIVE ${STOP_MS} ms after SIGKILL`, alive: true };
}

/**
 * The test's resources, released by one after hook in a fixed order: every child stops and has
 * exited, then the services close, then the temp folders are removed (Windows refuses to remove
 * a folder that is a live process's cwd). A failing step does not skip the later ones; all
 * problems fail the test together.
 */
const resources = new WeakMap();

function cleanup(t) {
  let held = resources.get(t);
  if (!held) {
    held = { services: [], roots: [] };
    resources.set(t, held);
    t.after(async () => {
      const problems = [];
      let survivors = 0;
      for (const entry of [...liveChildren]) {
        const { problem, alive } = await stopChild(entry);
        if (problem) problems.push(problem);
        if (alive) {
          survivors++;
          liveChildren.delete(entry); // reported here; later tests do not wait on it again
        }
      }
      for (const close of held.services) await close();
      for (const root of held.roots) {
        if (survivors) {
          problems.push(`left ${root} in place: a child is still alive`);
          continue;
        }
        try {
          fs.rmSync(root, { recursive: true, force: true });
        } catch (e) {
          problems.push(`could not remove ${root}: ${e.code || e.message}`);
        }
      }
      if (problems.length) {
        for (const problem of problems) t.diagnostic(`cleanup: ${problem}`); // shown even when the test already failed
        throw new Error(`cleanup:\n${problems.join('\n')}`);
      }
    });
  }
  return held;
}

function tempRoot(t) {
  const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'js-engine-discovery-')));
  cleanup(t).roots.push(root);
  return root;
}

/** A hermetic machine: home, cwd and (Windows) known folders under root. */
function machine(root, { xdg } = {}) {
  const home = path.join(root, 'home');
  const cwd = path.join(root, 'cwd');
  fs.mkdirSync(home, { recursive: true });
  fs.mkdirSync(cwd, { recursive: true });
  const env = {};
  for (const [k, v] of Object.entries(process.env)) {
    if (!/^(JUSTSEARCH_|XDG_DATA_HOME$|APPDATA$|LOCALAPPDATA$|HOME$|USERPROFILE$)/i.test(k)) env[k] = v;
  }
  env.HOME = home;
  let desktop;
  let userData;
  if (IS_WINDOWS) {
    env.USERPROFILE = home;
    env.APPDATA = path.join(home, 'AppData', 'Roaming');
    env.LOCALAPPDATA = path.join(home, 'AppData', 'Local');
    desktop = path.join(env.APPDATA, 'io.justsearch.shell');
    userData = path.join(env.LOCALAPPDATA, 'JustSearch');
  } else if (process.platform === 'darwin') {
    desktop = path.join(home, 'Library', 'Application Support', 'io.justsearch.shell');
    userData = path.join(home, 'Library', 'Application Support', 'JustSearch');
  } else {
    if (xdg !== undefined) env.XDG_DATA_HOME = xdg;
    const base = xdg && path.isAbsolute(xdg) ? xdg : path.join(home, '.local', 'share');
    desktop = path.join(base, 'io.justsearch.shell');
    userData = path.join(home, '.justsearch');
  }
  return { home, cwd, env, desktop, userData };
}

function writeManifest(dataDir, manifest) {
  fs.mkdirSync(path.join(dataDir, 'runtime'), { recursive: true });
  const text = typeof manifest === 'string' ? manifest : JSON.stringify(manifest);
  fs.writeFileSync(path.join(dataDir, 'runtime', 'manifest.json'), text);
}

function manifestFor(engine, overrides = {}) {
  return {
    schemaVersion: 2,
    instanceId: engine.instanceId,
    pid: engine.pid,
    lifecycle: 'LIFECYCLE_STATE_READY',
    head: { apiPort: engine.port, apiBaseUrl: `http://127.0.0.1:${engine.port}` },
    ...overrides,
  };
}

let instanceCounter = 0;

/**
 * A loopback service. kind 'engine' answers like a JustSearch Engine with the given identity;
 * kind 'foreign' answers health/status checks but is not a JustSearch Engine.
 */
async function startService(t, { kind = 'engine', pid = process.pid, instanceId, token = 'token-abc', port = 0, healthStatus = 200, holdUrl = null } = {}) {
  const id = instanceId || `instance-${process.pid}-${++instanceCounter}`;
  const requests = [];
  // holdUrl: a request to it is answered only when release(body) is called (a slow Engine call).
  let held = null;
  // lastAnswers(): from then on every answer closes its connection, as a stopping Engine's does,
  // so the client keeps no idle connection to reuse once the Engine is gone.
  let closing = false;
  let heldArrived;
  const arrived = new Promise((resolve) => (heldArrived = resolve));
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      requests.push({ method: req.method, url: req.url, headers: req.headers, body, health: healthStatus });
      const json = (status, obj) => {
        res.writeHead(status, { 'content-type': 'application/json', ...(closing ? { connection: 'close' } : {}) });
        res.end(JSON.stringify(obj));
      };
      if (holdUrl && req.url === holdUrl && !held) {
        // Answered only on release(); the listener is gone by then, so the connection closes with
        // this answer, as a stopping Engine's does: the client's next request needs a new
        // connection to the port. release(fn) answers fn(request body).
        held = (answer) => {
          res.writeHead(200, { 'content-type': 'application/json', connection: 'close' });
          res.end(JSON.stringify(typeof answer === 'function' ? answer(body) : answer));
        };
        heldArrived();
        return undefined;
      }
      if (req.url === '/api/health') return json(healthStatus, { ok: healthStatus === 200 });
      if (req.url === '/api/status') return json(200, { status: 'ok' });
      if (kind === 'foreign') return json(404, { error: 'not found' });
      if (req.url === '/api/runtime/live') return json(200, { alive: true, pid, instanceId: id });
      if (req.url === '/api/mcp/token') return json(200, { token });
      if (req.url === '/mcp' && req.method === 'POST') {
        const msg = JSON.parse(body);
        if (msg.id === undefined) return res.writeHead(202).end();
        if (msg.method === 'initialize') {
          return json(200, {
            jsonrpc: '2.0',
            id: msg.id,
            result: { protocolVersion: '2025-06-18', capabilities: {}, serverInfo: { name: `engine ${id}`, version: '0' } },
          });
        }
        return json(200, {
          jsonrpc: '2.0',
          id: msg.id,
          result: { content: [{ type: 'text', text: `${msg.method} by ${id}` }], session: req.headers['x-justsearch-session'] || null },
        });
      }
      if (req.url === '/mcp') return json(405, {});
      if (req.url === '/api/knowledge/status') return json(200, { state: 'READY', ready: true, docCount: 1, healthy: true });
      if (req.url.startsWith('/api/operation-history/')) return json(200, { state: 'completed', by: id });
      if (req.url === '/api/knowledge/retrieve-context') return json(200, { context: `context by ${id}`, total_found: 0, chunks: [], quality: {} });
      if (req.url === '/api/knowledge/ingest' && req.method === 'POST') {
        return json(200, { success: true, operationKey: `op-${id}`, message: `ingest by ${id}` });
      }
      return json(404, {});
    });
  });
  const stats = { connections: 0 };
  server.on('connection', () => stats.connections++);
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', resolve);
  });
  let closed = null;
  /** Stop listening and drop every open connection (the Engine went away). */
  const close = () => {
    if (!closed) {
      closed = new Promise((resolve) => server.close(resolve));
      server.closeAllConnections();
    }
    return closed;
  };
  cleanup(t).services.push(close);
  const setHealth = (status) => {
    healthStatus = status;
  };
  /** Stop listening but keep open connections (a held response can still be sent). */
  const stopListening = () => new Promise((resolve) => server.close(resolve) && resolve());
  const release = (obj) => held(obj);
  const lastAnswers = () => {
    closing = true;
  };
  return { kind, pid, instanceId: id, port: server.address().port, requests, token, close, setHealth, arrived, release, stopListening, lastAnswers, stats };
}

/** Resolve once the service has received the client's notifications/initialized (its handshake is done). */
async function handshakeDone(service, { stream = false, timeoutMs = 10000 } = {}) {
  const done = () =>
    service.requests.some((r) => r.url === '/mcp' && r.body.includes('notifications/initialized')) &&
    (!stream || service.requests.some((r) => r.url === '/mcp' && r.method === 'GET'));
  const deadline = Date.now() + timeoutMs;
  while (!done()) {
    if (Date.now() >= deadline) throw new Error(`handshake: not done within ${timeoutMs} ms`);
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

/** A live process that serves nothing (it only sleeps): an unrelated live pid. */
async function unrelatedLivePid(t) {
  const child = spawnNode(['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' }, 'kill');
  await once(child, 'spawn');
  return child.pid;
}

const IMPOSTOR_SOURCE = `
const http = require('http');
const { port, pid, instanceId, token } = JSON.parse(process.argv[1]);
const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    process.stdout.write('REQ ' + JSON.stringify({ method: req.method, url: req.url, session: req.headers['x-justsearch-session'] || null }) + '\\n');
    const json = (status, obj) => { res.writeHead(status, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)); };
    if (req.url === '/api/runtime/live') return json(200, { alive: true, pid, instanceId });
    if (req.url === '/api/health') return json(200, { ok: true });
    if (req.url === '/api/status') return json(200, { status: 'ok' });
    if (req.url === '/api/mcp/token') return json(200, { token });
    if (req.url === '/mcp' && req.method === 'POST') {
      const msg = JSON.parse(body);
      if (msg.id === undefined) { res.writeHead(202); return res.end(); }
      return json(200, { jsonrpc: '2.0', id: msg.id, result: { protocolVersion: '2025-06-18', capabilities: {}, serverInfo: { name: 'impostor', version: '0' }, content: [{ type: 'text', text: 'impostor' }] } });
    }
    if (req.url === '/api/knowledge/ingest') return json(200, { success: true, operationKey: 'impostor', message: 'impostor' });
    if (req.url === '/api/knowledge/status') return json(200, { state: 'READY', ready: true, docCount: 999, healthy: true });
    if (req.url.startsWith('/api/operation-history/')) return json(200, { state: 'completed', by: 'impostor' });
    if (req.url === '/api/knowledge/search') return json(200, { results: [], facets: { meta_source: { impostor: 1 } } });
    if (req.url === '/api/knowledge/match-citations') return json(200, { matches: ['impostor'], sentences_total: 1, sentences_matched: 1 });
    json(404, {});
  });
});
server.on('connection', () => process.stdout.write('CONN\\n'));
server.listen(port, '127.0.0.1', () => process.stdout.write('LISTENING ' + server.address().port + '\\n'));
`;

/**
 * A foreign HTTP server in its own process that copies an Engine's identity: it answers
 * /api/runtime/live with the given pid and instanceId, /api/health 200, a token and /mcp.
 * Every connection it accepts and every request it receives is reported on its stdout;
 * settled() resolves to { connections, requests } once everything it got before the call is in.
 */
async function startImpostor(t, { port = 0, pid, instanceId, token = 'impostor-token' }) {
  const child = spawnNode(['-e', IMPOSTOR_SOURCE, JSON.stringify({ port, pid, instanceId, token })], {
    stdio: ['ignore', 'pipe', 'inherit'],
  }, 'kill');
  const requests = [];
  let connections = 0;
  let synced = null;
  let out = '';
  const listening = new Promise((resolve, reject) => {
    child.once('exit', (code) => reject(new Error(`impostor exited ${code}`)));
    child.stdout.on('data', (d) => {
      out += d;
      let nl;
      while ((nl = out.indexOf('\n')) !== -1) {
        const line = out.slice(0, nl);
        out = out.slice(nl + 1);
        if (line.startsWith('LISTENING ')) resolve(Number(line.slice(10)));
        if (line === 'CONN') connections++;
        if (line.startsWith('REQ ')) {
          const req = JSON.parse(line.slice(4));
          if (req.url === '/__settled') synced?.();
          else requests.push(req);
        }
      }
    });
  });
  const boundPort = await within(listening, 10000, 'impostor listening');
  /**
   * The test's own request goes through the same accept queue after everything the clients
   * sent; once the impostor reports it, every earlier connection and request is recorded.
   */
  const settled = async () => {
    const seen = new Promise((resolve) => (synced = resolve));
    const req = http.get({ host: '127.0.0.1', port: boundPort, path: '/__settled', agent: false });
    const answered = new Promise((resolve, reject) => {
      req.on('response', (res) => res.resume().on('end', resolve).on('error', reject));
      req.on('error', reject);
    });
    try {
      // One deadline for the whole exchange: connect, the impostor's report, its response.
      await within(Promise.all([answered, seen]), 10000, 'impostor report');
    } finally {
      req.destroy();
    }
    return { connections: connections - 1, requests: [...requests] };
  };
  return { port: boundPort, pid: child.pid, instanceId, requests, settled };
}

/** A pid that existed and has exited. */
async function deadPid() {
  const child = spawnNode(['-e', ''], { stdio: 'ignore' }, 'self');
  await new Promise((resolve) => child.on('exit', resolve));
  return child.pid;
}

/** A port nothing listens on (bound, then released). */
async function closedPort() {
  const server = http.createServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  await new Promise((resolve) => server.close(resolve));
  return port;
}

function run(args, { env, cwd, input, timeoutMs = 20000 }) {
  return new Promise((resolve) => {
    const child = spawnNode(args, { env, cwd, stdio: ['pipe', 'pipe', 'pipe'] }, 'self');
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => (stdout += d));
    child.stderr.on('data', (d) => (stderr += d));
    const timer = setTimeout(() => child.kill(), timeoutMs);
    child.on('exit', (code) => {
      clearTimeout(timer);
      resolve({ code, stdout, stderr });
    });
    child.stdin.on('error', () => {}); // the child may exit before reading
    if (input) child.stdin.write(input);
    child.stdin.end();
  });
}

/** discover() of the production MCP server, in a child with the given machine. */
async function prodDiscover(m, { port, envExtra = {}, timeoutMs } = {}) {
  const script =
    `import(${JSON.stringify(pathToFileURL(DISCOVERY_MJS).href)})` +
    `.then((d) => d.discover(${port ? `{ port: ${port} }` : ''}))` +
    '.then((r) => { process.stdout.write(JSON.stringify(r)); })' +
    '.catch((e) => { process.stderr.write(e.message); process.exit(3); });';
  const res = await run(['--input-type=module', '-e', script], { env: { ...m.env, ...envExtra }, cwd: m.cwd, timeoutMs });
  return { ...res, result: res.code === 0 ? JSON.parse(res.stdout) : null };
}

/** Pack the MCPB from source and extract it (STORED zip) outside the checkout. */
function extractBundle(root) {
  const { buffer } = packMcpb(repoRoot);
  const dir = path.join(root, 'extracted-bundle');
  let off = 0;
  while (buffer.readUInt32LE(off) === 0x04034b50) {
    const size = buffer.readUInt32LE(off + 18);
    const nameLen = buffer.readUInt16LE(off + 26);
    const extraLen = buffer.readUInt16LE(off + 28);
    const name = buffer.subarray(off + 30, off + 30 + nameLen).toString('utf8');
    const start = off + 30 + nameLen + extraLen;
    const target = path.join(dir, ...name.split('/'));
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, buffer.subarray(start, start + size));
    off = start + size;
  }
  return path.join(dir, 'server', 'index.js');
}

let bundleEntry = null;
function bundle(root) {
  if (!bundleEntry || !fs.existsSync(bundleEntry)) bundleEntry = extractBundle(root);
  return bundleEntry;
}

/**
 * Drive the extracted bridge as an MCPB host would: initialize, then a mutating tools/call.
 * Returns the parsed stdout messages and stderr.
 */
async function bridgeSession(m, entry, envExtra = {}) {
  const input =
    `${JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'test', version: '0' } } })}\n` +
    `${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })}\n` +
    `${JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'justsearch_ingest', arguments: { paths: ['/x'] } } })}\n`;
  // The bridge exits when stdin closes; give it the messages, then close after the replies.
  return new Promise((resolve) => {
    const child = spawnNode([entry], { env: { ...m.env, ...envExtra }, cwd: m.cwd, stdio: ['pipe', 'pipe', 'pipe'] }, 'self');
    let stdout = '';
    let stderr = '';
    const messages = [];
    const timer = setTimeout(() => child.kill(), 20000);
    child.stdout.on('data', (d) => {
      stdout += d;
      let nl;
      while ((nl = stdout.indexOf('\n')) !== -1) {
        const line = stdout.slice(0, nl);
        stdout = stdout.slice(nl + 1);
        if (line.trim()) messages.push(JSON.parse(line));
        if (messages.some((x) => x.id === 2)) child.stdin.end();
      }
    });
    child.stderr.on('data', (d) => (stderr += d));
    child.on('exit', (code) => {
      clearTimeout(timer);
      resolve({ code, messages, stderr });
    });
    child.stdin.on('error', () => {}); // a bridge that finds no Engine exits before reading
    child.stdin.write(input);
  });
}

/**
 * Drive the extracted bridge step by step: initialize, then between(), then a mutating
 * tools/call. Returns the parsed stdout messages and stderr.
 */
async function bridgeInteractive(m, entry, between) {
  const child = spawnNode([entry], { env: m.env, cwd: m.cwd, stdio: ['pipe', 'pipe', 'pipe'] }, 'self');
  const messages = [];
  let stdout = '';
  let stderr = '';
  const waiters = [];
  const settle = () => {
    for (const w of [...waiters]) if (messages.some((x) => x.id === w.id)) waiters.splice(waiters.indexOf(w), 1) && w.resolve();
  };
  child.stdout.on('data', (d) => {
    stdout += d;
    let nl;
    while ((nl = stdout.indexOf('\n')) !== -1) {
      const line = stdout.slice(0, nl);
      stdout = stdout.slice(nl + 1);
      if (line.trim()) messages.push(JSON.parse(line));
    }
    settle();
  });
  child.stderr.on('data', (d) => (stderr += d));
  child.stdin.on('error', () => {});
  const exited = new Promise((resolve) => child.on('close', resolve)); // after stdout is drained
  const reply = (id) => Promise.race([new Promise((resolve) => waiters.push({ id, resolve }) && settle()), exited]);
  const timer = setTimeout(() => child.kill(), 30000);
  child.stdin.write(
    `${JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'test', version: '0' } } })}\n` +
      `${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })}\n`,
  );
  await reply(1);
  await between();
  child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'justsearch_ingest', arguments: { paths: ['/x'] } } })}\n`);
  await reply(2);
  child.stdin.end();
  const code = await exited;
  clearTimeout(timer);
  return { code, messages, stderr };
}

function assertConnectedTo(session, engine) {
  assert.equal(session.code, 0, `bridge exit ${session.code}\n${session.stderr}`);
  const init = session.messages.find((x) => x.id === 1);
  assert.equal(init?.result?.serverInfo?.name, `engine ${engine.instanceId}`, session.stderr);
  const call = session.messages.find((x) => x.id === 2);
  assert.equal(call?.result?.content?.[0]?.text, `tools/call by ${engine.instanceId}`, session.stderr);
  assert.equal(call.result.session, engine.token, 'the mutating call carries the session token');
}

// ---------------------------------------------------------------------------
// Contract: the desktop app's data folder
// ---------------------------------------------------------------------------

test('contract: desktopAppDataDir fixtures resolve as Tauri resolves app_data_dir', () => {
  const spec = JSON.parse(fs.readFileSync(path.join(repoRoot, 'contracts', 'platform-paths', 'spec.v1.json'), 'utf8'));
  const section = spec.discovery?.desktopAppDataDir;
  assert.ok(section, 'contracts/platform-paths/spec.v1.json has discovery.desktopAppDataDir');
  const { desktopAppDataDir, DESKTOP_APP_IDENTIFIER } = require(ENGINE_DISCOVERY);
  assert.equal(DESKTOP_APP_IDENTIFIER, section.identifier);
  assert.ok(section.fixtures.length >= 9);
  for (const f of section.fixtures) {
    assert.equal(desktopAppDataDir({ env: f.env, platform: f.platform, userHome: f.userHome }), f.expected, f.name);
  }
});

test('contract: the identifier is the shell bundle identifier Tauri appends', () => {
  const spec = JSON.parse(fs.readFileSync(path.join(repoRoot, 'contracts', 'platform-paths', 'spec.v1.json'), 'utf8'));
  const tauri = JSON.parse(fs.readFileSync(path.join(repoRoot, 'modules', 'shell', 'src-tauri', 'tauri.conf.json'), 'utf8'));
  assert.equal(spec.discovery.desktopAppDataDir.identifier, tauri.identifier);
});

// ---------------------------------------------------------------------------
// D1/D2/D3: no setup, desktop Engine on a random port
// ---------------------------------------------------------------------------

test('D1/D2/D3: production MCP finds the desktop Engine on a random port with no setup', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const engine = await startService(t);
  writeManifest(m.desktop, manifestFor(engine));
  const res = await prodDiscover(m);
  assert.equal(res.code, 0, res.stderr);
  assert.equal(res.result.port, engine.port);
  assert.equal(res.result.instanceId, engine.instanceId);
  assert.equal(res.result.token, engine.token);
  assert.match(res.stderr, new RegExp(`Using the Engine in .*io\\.justsearch\\.shell \\(desktop app, pid ${engine.pid}, port ${engine.port}\\)`));
});

test('D1/D2/D3: the extracted MCPB bundle connects to the desktop Engine and a mutating call carries the token', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const engine = await startService(t);
  writeManifest(m.desktop, manifestFor(engine));
  const entry = bundle(root);
  assert.ok(!entry.startsWith(repoRoot), 'the bundle runs outside the checkout');
  // Dependency-free even where the SDK is installed: from there it cannot resolve the SDK.
  assert.throws(() => createRequire(entry).resolve('@modelcontextprotocol/sdk/server/mcp.js'), { code: 'MODULE_NOT_FOUND' });
  assertConnectedTo(await bridgeSession(m, entry), engine);
});

test('D2: Linux XDG_DATA_HOME absolute is used; relative or empty falls back to ~/.local/share', { skip: !IS_LINUX && 'Linux XDG rules' }, async (t) => {
  for (const xdgKind of ['absolute', 'relative', 'empty']) {
    const root = tempRoot(t);
    const xdg = xdgKind === 'absolute' ? path.join(root, 'xdg data') : xdgKind === 'relative' ? 'rel-share' : '';
    const m = machine(root, { xdg });
    const engine = await startService(t);
    writeManifest(m.desktop, manifestFor(engine));
    if (xdgKind === 'relative') {
      // A valid decoy at the cwd-relative reading of XDG_DATA_HOME must never be selected.
      const decoy = await startService(t);
      writeManifest(path.join(m.cwd, 'rel-share', 'io.justsearch.shell'), manifestFor(decoy));
    }
    const res = await prodDiscover(m);
    assert.equal(res.code, 0, `${xdgKind}: ${res.stderr}`);
    assert.equal(res.result.instanceId, engine.instanceId, xdgKind);
    assertConnectedTo(await bridgeSession(m, bundle(root)), engine);
  }
});

// ---------------------------------------------------------------------------
// D4: bad candidates are rejected, a valid later one is still found
// ---------------------------------------------------------------------------

async function badCandidates(t, root) {
  const foreign = await startService(t, { kind: 'foreign' });
  const impostor = await startService(t); // a real-looking Engine with another identity
  const bad = {
    stale: { pid: await deadPid(), instanceId: 'stale', head: { apiPort: impostor.port } },
    malformed: '{"pid": 12, "instanceId": "trunc',
    'startup-incomplete': { pid: process.pid, instanceId: 'starting', head: {} },
    'live-pid-wrong-instance': { pid: process.pid, instanceId: 'not-the-one', head: { apiPort: impostor.port } },
    'live-pid-nothing-listening': { pid: process.pid, instanceId: 'gone', head: { apiPort: await closedPort() } },
    'foreign-on-port': { pid: process.pid, instanceId: 'foreign', head: { apiPort: foreign.port } },
  };
  const dirs = {};
  for (const [name, manifest] of Object.entries(bad)) {
    dirs[name] = path.join(root, 'bad', name);
    writeManifest(dirs[name], manifest);
  }
  return { dirs, foreign, impostor };
}

test('D4: stale, malformed, startup-incomplete, wrong-identity and foreign manifests are rejected; a later valid one wins', async (t) => {
  const root = tempRoot(t);
  const { selectEngine } = require(ENGINE_DISCOVERY);
  const { dirs, foreign } = await badCandidates(t, root);
  const engine = await startService(t);
  const good = path.join(root, 'good');
  writeManifest(good, manifestFor(engine));
  const candidates = [
    ...Object.entries(dirs).map(([name, dataDir]) => ({ source: name, dataDir })),
    { source: 'good', dataDir: good },
  ];
  const { selected, tried } = await selectEngine(candidates);
  assert.equal(selected?.instanceId, engine.instanceId);
  const status = Object.fromEntries(tried.map((x) => [x.source, x.status]));
  assert.deepEqual(status, {
    stale: 'stale',
    malformed: 'malformed',
    'startup-incomplete': 'startup-incomplete',
    'live-pid-wrong-instance': 'identity-mismatch',
    'live-pid-nothing-listening': 'not-responding',
    'foreign-on-port': 'not-responding',
    good: 'ok',
  });
  assert.ok(!foreign.requests.some((r) => r.url === '/mcp'), 'nothing is sent to the foreign service');
});

test('D4: a foreign service answering health on the desktop manifest port is rejected by both clients; a later valid candidate is used', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const foreign = await startService(t, { kind: 'foreign' });
  writeManifest(m.desktop, { pid: process.pid, instanceId: 'desktop', head: { apiPort: foreign.port } });
  const engine = await startService(t);
  // Production MCP: the user data folder comes later in its order.
  writeManifest(m.userData, manifestFor(engine));
  const res = await prodDiscover(m);
  assert.equal(res.code, 0, res.stderr);
  assert.equal(res.result.instanceId, engine.instanceId);
  // Bridge: an identity check also refuses a foreign service that answers /api/runtime/live.
  const liar = await startService(t, { instanceId: 'someone-else' });
  writeManifest(m.desktop, { pid: process.pid, instanceId: 'desktop', head: { apiPort: liar.port } });
  const session = await bridgeSession(m, bundle(root));
  assert.notEqual(session.code, 0);
  assert.match(session.stderr, /identity-mismatch/);
  assert.ok(![...foreign.requests, ...liar.requests].some((r) => r.url === '/mcp'), 'no MCP traffic reaches either impostor');
});

// ---------------------------------------------------------------------------
// D5: explicit settings win; dev + installed is deterministic and named
// ---------------------------------------------------------------------------

async function desktopAndPinned(t, root) {
  const m = machine(root);
  const desktopEngine = await startService(t);
  writeManifest(m.desktop, manifestFor(desktopEngine));
  const pinned = await startService(t);
  const pinnedDir = path.join(root, 'pinned-data');
  writeManifest(pinnedDir, manifestFor(pinned));
  return { m, pinned, pinnedDir };
}

test('D5: --port, JUSTSEARCH_API_PORT and JUSTSEARCH_DATA_DIR win over the desktop Engine (production MCP), JUSTSEARCH_API_PORT (bundle)', async (t) => {
  const root = tempRoot(t);
  const { m, pinned, pinnedDir } = await desktopAndPinned(t, root);
  assert.equal((await prodDiscover(m, { port: pinned.port })).result?.port, pinned.port, '--port');
  const byEnvPort = await prodDiscover(m, { envExtra: { JUSTSEARCH_API_PORT: String(pinned.port) } });
  assert.equal(byEnvPort.result?.port, pinned.port, 'JUSTSEARCH_API_PORT');
  const byDataDir = await prodDiscover(m, { envExtra: { JUSTSEARCH_DATA_DIR: pinnedDir } });
  assert.equal(byDataDir.result?.port, pinned.port, 'JUSTSEARCH_DATA_DIR');
  assertConnectedTo(await bridgeSession(m, bundle(root), { JUSTSEARCH_API_PORT: String(pinned.port) }), pinned);
});

test('D5: JUSTSEARCH_DATA_DIR wins over the desktop Engine in the bundle too', async (t) => {
  const root = tempRoot(t);
  const { m, pinned, pinnedDir } = await desktopAndPinned(t, root);
  assertConnectedTo(await bridgeSession(m, bundle(root), { JUSTSEARCH_DATA_DIR: pinnedDir }), pinned);
});

test('D5: with a dev Engine in the checkout and the desktop app both up, selection is the same every time and says which', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const devEngine = await startService(t);
  writeManifest(path.join(m.cwd, 'modules', 'ui-web', '.dev-data'), manifestFor(devEngine));
  const desktopEngine = await startService(t);
  writeManifest(m.desktop, manifestFor(desktopEngine));
  for (let i = 0; i < 2; i++) {
    const res = await prodDiscover(m);
    assert.equal(res.result?.instanceId, desktopEngine.instanceId, res.stderr);
    assert.match(res.stderr, /Using the Engine in .*io\.justsearch\.shell \(desktop app/);
  }
});

// ---------------------------------------------------------------------------
// D6: app not running
// ---------------------------------------------------------------------------

test('D6: app not running — production MCP names every folder tried and contacts no unrelated service', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const foreign = await startService(t, { kind: 'foreign' });
  writeManifest(m.desktop, { pid: await deadPid(), instanceId: 'old', head: { apiPort: foreign.port } });
  const res = await prodDiscover(m);
  assert.equal(res.code, 3, res.stdout);
  for (const folder of [m.desktop, path.join(m.cwd, '.dev-data'), m.userData]) {
    assert.ok(res.stderr.includes(folder), `error names ${folder}\n${res.stderr}`);
  }
  assert.match(res.stderr, /desktop app: .*\(stale: pid \d+ is not running/);
  assert.equal(foreign.requests.length, 0, 'the service on the stale port is never contacted');
});

test('D6: app not running — the extracted bundle names the desktop folder and does not fall back to a default port', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  let squatter = null;
  try {
    squatter = await startService(t, { kind: 'foreign', port: 8080 });
  } catch (e) {
    t.diagnostic(`port 8080 busy (${e.code}); the default-port half of this check did not run`);
  }
  const session = await bridgeSession(m, bundle(root));
  assert.notEqual(session.code, 0);
  assert.ok(session.stderr.includes(m.desktop), session.stderr);
  assert.match(session.stderr, /Could not reach a running JustSearch/);
  if (squatter) assert.equal(squatter.requests.length, 0, 'nothing is sent to a service on 8080');
});

// ---------------------------------------------------------------------------
// Round 2: the endpoint must be proven to be the manifest's Engine
// ---------------------------------------------------------------------------

test('D4/D9: a foreign server copying the manifest identity with an unrelated live pid is refused by both clients and contacted by neither; a later valid Engine is used', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const livePid = await unrelatedLivePid(t);
  const impostor = await startImpostor(t, { pid: livePid, instanceId: 'copied-instance' });
  const impostorDir = path.join(root, 'impostor-data');
  writeManifest(impostorDir, { schemaVersion: 2, pid: livePid, instanceId: 'copied-instance', lifecycle: 'LIFECYCLE_STATE_READY', head: { apiPort: impostor.port } });
  const engine = await startService(t);
  writeManifest(m.desktop, manifestFor(engine));

  const { selectEngine } = require(ENGINE_DISCOVERY);
  const { selected, tried } = await selectEngine([{ source: 'impostor', dataDir: impostorDir }]);
  assert.equal(selected, null, `the impostor was admitted: ${JSON.stringify(tried)}`);
  assert.equal(tried[0].status, 'owner-mismatch', tried[0].detail);

  const res = await prodDiscover(m, { envExtra: { JUSTSEARCH_DATA_DIR: impostorDir } });
  assert.equal(res.code, 0, res.stderr);
  assert.equal(res.result.instanceId, engine.instanceId, 'production MCP selects the real Engine after the impostor');

  const session = await bridgeSession(m, bundle(root), { JUSTSEARCH_DATA_DIR: impostorDir });
  assertConnectedTo(session, engine);
  assert.match(session.stderr, /using the Engine in .*io\.justsearch\.shell/);
  assert.deepEqual((await impostor.settled()).requests, [], 'nothing at all is sent to the impostor');
});

test('D4: a proven Engine still starting (/api/health 503) is waited for and admitted once it answers 200 — by both clients, no token before 200', async (t) => {
  for (const client of ['production MCP', 'bundle']) {
    const root = tempRoot(t);
    const m = machine(root);
    const engine = await startService(t, { healthStatus: 503 });
    writeManifest(m.desktop, manifestFor(engine));
    const ready = setTimeout(() => engine.setHealth(200), 1200);
    t.after(() => clearTimeout(ready));
    if (client === 'production MCP') {
      const res = await prodDiscover(m);
      assert.equal(res.result?.instanceId, engine.instanceId, `${client}: ${res.stderr}`);
    } else {
      assertConnectedTo(await bridgeSession(m, bundle(root)), engine);
    }
    assert.ok(engine.requests.some((r) => r.url === '/api/health' && r.health === 503), `${client}: it was seen starting`);
    const credentialed = engine.requests.filter((r) => r.url === '/api/mcp/token' || r.url === '/mcp');
    assert.ok(credentialed.length > 0, client);
    assert.ok(credentialed.every((r) => r.health === 200), `${client}: nothing past the probes before health 200`);
  }
});

test('D4: an Engine that stays at /api/health 503 is rejected after the bound; a later valid one is still found', async (t) => {
  const { selectEngine, STARTUP_WAIT_MS } = require(ENGINE_DISCOVERY);
  const root = tempRoot(t);
  const starting = await startService(t, { healthStatus: 503 });
  const startingDir = path.join(root, 'starting-data');
  writeManifest(startingDir, manifestFor(starting));
  const engine = await startService(t);
  const good = path.join(root, 'good');
  writeManifest(good, manifestFor(engine));
  const t0 = Date.now();
  const { selected, tried } = await selectEngine(
    [{ source: 'starting', dataDir: startingDir }, { source: 'good', dataDir: good }],
    { startupWaitMs: 1500 },
  );
  assert.ok(Date.now() - t0 >= 1000, `it was waited for (${Date.now() - t0} ms)`);
  assert.equal(tried[0].status, 'unhealthy', tried[0].detail);
  assert.equal(selected?.instanceId, engine.instanceId);
  assert.ok(STARTUP_WAIT_MS >= 10000 && STARTUP_WAIT_MS <= 60000, `the shipped bound is a constant of ~30 s (${STARTUP_WAIT_MS} ms)`);
  assert.ok(!starting.requests.some((r) => r.url === '/mcp' || r.url === '/api/mcp/token'), 'nothing past the probes reaches it');
});

test('D4: both clients give up on an Engine stuck at 503 after the startup bound and use the desktop Engine', async (t) => {
  const bound = 1500; // through the test seam; the shipped default is checked below
  const seam = { JUSTSEARCH_DISCOVERY_STARTUP_WAIT_MS: String(bound) };
  for (const client of ['production MCP', 'bundle']) {
    const root = tempRoot(t);
    const m = machine(root);
    const stuck = await startService(t, { healthStatus: 503 });
    const stuckDir = path.join(root, 'stuck-data');
    writeManifest(stuckDir, manifestFor(stuck));
    const engine = await startService(t);
    writeManifest(m.desktop, manifestFor(engine));
    const envExtra = { ...seam, JUSTSEARCH_DATA_DIR: stuckDir };
    const t0 = Date.now();
    const res = client === 'production MCP' ? await prodDiscover(m, { envExtra }) : await bridgeSession(m, bundle(root), envExtra);
    const elapsed = Date.now() - t0;
    assert.ok(elapsed >= bound - 600 && elapsed < bound + 10000, `${client}: it waited about ${bound} ms (took ${elapsed} ms)`);
    if (client === 'production MCP') assert.equal(res.result?.instanceId, engine.instanceId, res.stderr);
    else assertConnectedTo(res, engine);
    assert.ok(!stuck.requests.some((r) => r.url === '/api/mcp/token' || r.url === '/mcp'), `${client}: no token is fetched from it`);
  }
});

test('D4: the startup bound defaults to 30 000 ms for both clients; the seam is read only in engine-discovery.cjs', () => {
  const { STARTUP_WAIT_MS, startupWaitMs } = require(ENGINE_DISCOVERY);
  assert.equal(STARTUP_WAIT_MS, 30000);
  assert.equal(startupWaitMs({}), 30000);
  assert.equal(startupWaitMs({ JUSTSEARCH_DISCOVERY_STARTUP_WAIT_MS: '' }), 30000);
  assert.equal(startupWaitMs({ JUSTSEARCH_DISCOVERY_STARTUP_WAIT_MS: 'soon' }), 30000);
  assert.equal(startupWaitMs({ JUSTSEARCH_DISCOVERY_STARTUP_WAIT_MS: '1500' }), 1500);
  // Neither client overrides the shared default: both call selectEngine without a bound of their own.
  for (const rel of ['scripts/prod/justsearch-mcp/discovery.mjs', 'packaging/mcpb/server/index.js']) {
    const src = fs.readFileSync(path.join(repoRoot, rel), 'utf8');
    assert.ok(/selectEngine\(/.test(src), rel);
    assert.ok(!/startupWaitMs|STARTUP_WAIT/.test(src), `${rel} uses the shared default`);
  }
});

test('D9: after initialize the Engine goes away and a foreign server takes its port — the bridge sends it nothing, not the old token', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const engine = await startService(t);
  writeManifest(m.desktop, manifestFor(engine)); // left behind, as after a crash
  let impostor = null;
  const session = await bridgeInteractive(m, bundle(root), async () => {
    await handshakeDone(engine);
    await engine.close();
    impostor = await startImpostor(t, { port: engine.port, pid: engine.pid, instanceId: engine.instanceId });
  });
  assert.equal(session.messages.find((x) => x.id === 1)?.result?.serverInfo?.name, `engine ${engine.instanceId}`, session.stderr);
  assert.ok(impostor, 'the foreign server bound the freed port');
  assert.deepEqual(
    (await impostor.settled()).requests.filter((r) => r.url === '/mcp' || r.session),
    [],
    `no MCP traffic and no token reach the foreign server\n${session.stderr}`,
  );
  const call = session.messages.find((x) => x.id === 2);
  assert.ok(call?.error, `the call fails instead of reaching the foreign server: ${JSON.stringify(call)}\n${session.stderr}`);
  assert.match(session.stderr, /owner-mismatch/);
});

test('D9: when the Engine restarts on a new port, the bridge re-proves it and uses its new token', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const first = await startService(t, { token: 'token-first' });
  writeManifest(m.desktop, manifestFor(first));
  let second = null;
  const session = await bridgeInteractive(m, bundle(root), async () => {
    await handshakeDone(first);
    await first.close();
    second = await startService(t, { token: 'token-second' });
    writeManifest(m.desktop, manifestFor(second));
  });
  const call = session.messages.find((x) => x.id === 2);
  assert.equal(call?.result?.content?.[0]?.text, `tools/call by ${second.instanceId}`, session.stderr);
  assert.equal(call.result.session, 'token-second');
  assert.ok(!second.requests.some((r) => r.headers['x-justsearch-session'] === 'token-first'), 'the old token never reaches the new Engine');
});

/** The production MCP server over stdio, initialized; call(name, args) runs one tool. */
async function startProdMcp(t, m) {
  // The SDK is a root devDependency: without it these cases fail, they are never skipped.
  assert.doesNotThrow(
    () => require.resolve('@modelcontextprotocol/sdk/server/mcp.js', { paths: [path.join(repoRoot, 'scripts', 'prod')] }),
    'production MCP needs the MCP SDK: run npm ci at the repository root',
  );
  const child = spawnNode([PROD_MCP_ENTRY], { env: m.env, cwd: m.cwd, stdio: ['pipe', 'pipe', 'pipe'] }, 'stdin');
  child.stdin.on('error', () => {}); // a failed test may leave it exiting while we write
  let stderr = '';
  child.stderr.on('data', (d) => (stderr += d));
  const replies = new Map();
  let buf = '';
  child.stdout.on('data', (d) => {
    buf += d;
    let nl;
    while ((nl = buf.indexOf('\n')) !== -1) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (!line) continue;
      const msg = JSON.parse(line);
      replies.get(msg.id)?.(msg);
    }
  });
  let nextId = 1;
  const exited = new Promise((resolve) => child.once('exit', resolve));
  // A reply, or a failure: no reply within 30 s, or the server exited first.
  const request = (method, params) => {
    const id = nextId++;
    const reply = new Promise((resolve) => replies.set(id, resolve));
    const gone = exited.then((code) => {
      throw new Error(`production MCP exited (${code}) before answering ${method}`);
    });
    child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
    return within(Promise.race([reply, gone]), 30000, method).catch((e) => {
      throw new Error(`${e.message}\n${stderr}`);
    });
  };
  await request('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'test', version: '0' } });
  child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })}\n`);
  return {
    call: async (name, args) => JSON.stringify((await request('tools/call', { name, arguments: args })).result),
    get stderr() {
      return stderr;
    },
  };
}

test('D9: production MCP stops sending its token once the Engine no longer holds the port', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const engine = await startService(t);
  writeManifest(m.desktop, manifestFor(engine));
  const prod = await startProdMcp(t, m);
  const ingest = { paths: [path.join(root, 'doc.txt')] };
  // The Engine's last answer closes its connection, so the next call opens a new one to the port and
  // meets the proof. Without it the client may reuse its idle connection before it notices the
  // Engine closed it: that call fails with a reset (NOT_CONNECTED) and never tries the port.
  engine.lastAnswers();
  assert.match(await prod.call('justsearch_ingest', ingest), new RegExp(`ingest by ${engine.instanceId}`), prod.stderr);
  assert.equal(engine.requests.find((r) => r.url === '/api/knowledge/ingest')?.headers['x-justsearch-session'], engine.token);

  await engine.close();
  const impostor = await startImpostor(t, { port: engine.port, pid: engine.pid, instanceId: engine.instanceId });
  assert.match(await prod.call('justsearch_ingest', ingest), /ENGINE_GONE/, prod.stderr);
  const seen = await impostor.settled();
  assert.deepEqual(seen.requests, [], 'nothing, and no token, reaches the foreign server');
  assert.ok(seen.connections > 0, `the call tried the port and was refused there\n${prod.stderr}`);
});

test('D9: production MCP re-proves the Engine before each request inside one answer call (port taken over while retrieve-context is pending, old process alive)', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const engine = await startService(t, { holdUrl: '/api/knowledge/retrieve-context' });
  writeManifest(m.desktop, manifestFor(engine));
  const prod = await startProdMcp(t, m);
  const answer = prod.call('justsearch_answer', { query: 'tides', verify_citations: { answer_text: 'The keeper writes notes.' } });
  await within(engine.arrived, 10000, 'retrieve-context arriving at the Engine');
  await engine.stopListening(); // the listener goes; this process (the manifest pid) stays alive
  const impostor = await startImpostor(t, { port: engine.port, pid: engine.pid, instanceId: engine.instanceId });
  engine.release({ context: `context by ${engine.instanceId}`, total_found: 1, chunks: [], quality: {} });
  const result = await answer;
  const seen = await impostor.settled();
  assert.deepEqual(seen.requests, [], `no continuation request reaches the process that took the port\n${prod.stderr}`);
  assert.ok(!engine.requests.some((r) => r.url === '/api/knowledge/search'), 'the continuation did not slip through on the old connection');
  assert.doesNotMatch(result, /impostor/, 'nothing from the replacement ends up in the answer');
  // The observed refusal: the continuations connected to the port, were refused before writing,
  // and the answer is the proven Engine's context without the refused citation check.
  assert.ok(seen.connections > 0, `the continuation tried the port and was refused there\n${prod.stderr}`);
  const answered = JSON.parse(result).structuredContent;
  assert.equal(answered?.context, `context by ${engine.instanceId}`, result);
  assert.equal(answered.citations, undefined, 'the refused citation check adds nothing');
});

test('D9: production MCP status and operation outcome never contact a replacement on the port', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const engine = await startService(t);
  writeManifest(m.desktop, manifestFor(engine));
  const prod = await startProdMcp(t, m);
  assert.match(await prod.call('justsearch_status', {}), /"ok":true/, prod.stderr);
  assert.match(await prod.call('justsearch_operation_outcome', { operationKey: '01a151fd-a1a7-7afe-b8a8-6a7100a146f6' }), new RegExp(engine.instanceId), prod.stderr);
  engine.lastAnswers(); // the next calls need a new connection to the port (see the ingest case)
  assert.match(await prod.call('justsearch_status', {}), /"ok":true/, prod.stderr);
  assert.equal(engine.stats.connections, 1, 'discovery, the token and every call share one proven connection');
  await engine.stopListening();
  const impostor = await startImpostor(t, { port: engine.port, pid: engine.pid, instanceId: engine.instanceId });
  assert.match(await prod.call('justsearch_status', {}), /ENGINE_GONE/, prod.stderr);
  assert.match(await prod.call('justsearch_operation_outcome', { operationKey: '01a151fd-a1a7-7afe-b8a8-6a7100a146f6' }), /ENGINE_GONE/, prod.stderr);
  const seen = await impostor.settled();
  assert.deepEqual(seen.requests, [], 'neither call contacts the replacement');
  assert.ok(seen.connections > 0, `the calls tried the port and were refused there\n${prod.stderr}`);
});

/**
 * Once service has received its held request: stop its listener (the process stays alive), let a
 * different process bind the same port, then answer the held request. Resolves to that process.
 */
async function takeOverWhileHeld(t, service, answer) {
  await within(service.arrived, 10000, `the held request arriving at the Engine on port ${service.port}`);
  await service.stopListening();
  const impostor = await startImpostor(t, { port: service.port, pid: service.pid, instanceId: service.instanceId });
  service.release(answer);
  return impostor;
}

test('D9: the port is taken while /api/health is pending — neither client sends the token bootstrap (or anything) to the replacement', async (t) => {
  const reached = {};
  for (const client of ['production MCP', 'bundle']) {
    const root = tempRoot(t);
    const m = machine(root);
    const engine = await startService(t, { holdUrl: '/api/health' });
    writeManifest(m.desktop, manifestFor(engine));
    const run = client === 'production MCP' ? prodDiscover(m) : bridgeSession(m, bundle(root));
    const [res, impostor] = await Promise.all([run, takeOverWhileHeld(t, engine, { ok: true })]);
    const seen = await impostor.settled();
    reached[client] = { requests: seen.requests, answersFromIt: /impostor/.test(JSON.stringify(res.result ?? res.messages)) };
    // The observed refusal, never a timeout or a crash: it tried the port, wrote nothing, and ...
    assert.ok(seen.connections > 0, `${client}: it tried the replacement's port\n${res.stderr}`);
    if (client === 'production MCP') {
      // ... discovery returns the proven Engine with no token.
      assert.equal(res.code, 0, res.stderr);
      assert.equal(res.result.instanceId, engine.instanceId, res.stderr);
      assert.equal(res.result.token, null, 'the token bootstrap to the replacement is refused');
    } else {
      // ... the bridge exits naming the refusal.
      assert.equal(res.code, 1, res.stderr);
      assert.match(res.stderr, new RegExp(`owner-mismatch: the connection to port ${engine.port} is held by `));
    }
  }
  const none = { requests: [], answersFromIt: false };
  assert.deepEqual(reached, { 'production MCP': none, bundle: none }, 'nothing reaches the process that took the port, and nothing from it is used');
});

async function bridgeReconnectSwap(t, holdUrl, answer) {
  const root = tempRoot(t);
  const m = machine(root);
  const first = await startService(t, { token: 'token-first' });
  writeManifest(m.desktop, manifestFor(first));
  let second = null;
  let swap = null;
  const session = await bridgeInteractive(m, bundle(root), async () => {
    await handshakeDone(first, { stream: true }); // the bridge's own requests after initialize are over
    await first.close();
    second = await startService(t, { token: 'token-second', holdUrl });
    writeManifest(m.desktop, manifestFor(second));
    swap = takeOverWhileHeld(t, second, answer); // runs while the bridge reconnects for the next call
  });
  const seen = await (await swap).settled();
  assert.deepEqual(seen.requests, [], `no request, and neither token, reaches the process that took the port\n${session.stderr}`);
  const call = session.messages.find((x) => x.id === 2);
  assert.ok(call?.error, `the call fails instead of reaching the replacement: ${JSON.stringify(call)}\n${session.stderr}`);
  // The observed refusal: it tried the port and the call's error names why it wrote nothing.
  assert.ok(seen.connections > 0, `the bridge tried the replacement's port\n${session.stderr}`);
  assert.match(call.error.message, /owner-mismatch/, session.stderr);
}

test('D9: bridge reconnect — the restarted Engine\'s port is taken while its token response is pending', async (t) => {
  await bridgeReconnectSwap(t, '/api/mcp/token', { token: 'token-second' });
});

test('D9: bridge reconnect — the restarted Engine\'s port is taken while the re-initialize response is pending', async (t) => {
  await bridgeReconnectSwap(t, '/mcp', (body) => ({
    jsonrpc: '2.0',
    id: JSON.parse(body).id,
    result: { protocolVersion: '2025-06-18', capabilities: {}, serverInfo: { name: 'engine second', version: '0' } },
  }));
});

test('D9: no code path reaches the Engine except engineRequest (one http.request, behind the proving agent)', () => {
  const clients = ['packaging/mcpb/server/index.js', 'scripts/prod/justsearch-mcp/server.mjs', 'scripts/prod/justsearch-mcp/discovery.mjs'];
  const direct = /\brequire\(\s*['"](node:)?(http|https|net)['"]\s*\)|\bfrom\s+['"](node:)?(http|https|net)['"]|\bfetch\s*\(|\bhttp\.(request|get)\s*\(|\bnet\.(connect|createConnection)\s*\(/;
  for (const rel of clients) {
    const src = fs.readFileSync(path.join(repoRoot, rel), 'utf8');
    assert.doesNotMatch(src, direct, `${rel} must send through engine-discovery.cjs engineRequest`);
    assert.match(src, /engineRequest\(/, rel);
  }
  const shared = fs.readFileSync(ENGINE_DISCOVERY, 'utf8');
  assert.equal(shared.match(/\bhttp\.(request|get)\s*\(/g)?.length, 1, 'one http.request, in engineRequest');
  assert.match(shared, /function engineRequest[\s\S]*?http\.request\([\s\S]*?agent: agentFor\(engine\)/, 'it always uses the proving agent');
  assert.equal(shared.match(/\bnet\.connect\s*\(/g)?.length, 1, 'one net.connect, in provenConnection');
  assert.doesNotMatch(shared, /\bfetch\s*\(/);
});

test('D1: a Windows-style lazily resolved folder (the OS-reported Roaming folder) is tried after the home-folder guess', async (t) => {
  const root = tempRoot(t);
  const engine = await startService(t);
  const reported = path.join(root, 'redirected', 'io.justsearch.shell');
  writeManifest(reported, manifestFor(engine));
  const { selectEngine } = require(ENGINE_DISCOVERY);
  const { selected, tried } = await selectEngine([
    { source: 'desktop app', dataDir: path.join(root, 'guess', 'io.justsearch.shell') },
    { source: 'desktop app (Roaming folder the OS reports)', resolveDataDir: async () => reported },
  ]);
  assert.equal(selected?.instanceId, engine.instanceId, JSON.stringify(tried));
  assert.equal(selected.dataDir, reported);
});

test('D1: Windows reports the Roaming known folder even to a process started without APPDATA', { skip: !IS_WINDOWS && 'Windows known folder' }, async () => {
  const env = { ...process.env };
  for (const k of Object.keys(env)) if (/^APPDATA$/i.test(k)) delete env[k];
  const script = `require(${JSON.stringify(ENGINE_DISCOVERY)}).roamingAppDataFromOs().then((f) => process.stdout.write(String(f)))`;
  const res = await run(['-e', script], { env, cwd: repoRoot });
  assert.equal(res.code, 0, res.stderr);
  assert.equal(res.stdout.toLowerCase(), String(process.env.APPDATA).toLowerCase());
  const { standardCandidates } = require(ENGINE_DISCOVERY);
  const asks = (e) => standardCandidates({ env: e, userHome: 'C:\\Users\\x' }).some((c) => typeof c.resolveDataDir === 'function');
  assert.ok(asks({}), 'without APPDATA the OS is asked');
  assert.ok(!asks({ APPDATA: 'C:\\Users\\x\\AppData\\Roaming' }), 'with APPDATA it is not');
});
