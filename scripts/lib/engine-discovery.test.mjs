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
 * kind 'foreign' answers health/status checks but is not a JustSearch Engine. Each request is
 * recorded with conn, the number of the connection it arrived on (1, 2, ... in accept order).
 * Options: requireToken, a non-GET request without this service's token is answered 401 (as the
 * packaged app's session filter does); mcpSessions, initialize assigns an Mcp-Session-Id and a
 * call (POST /mcp with an id) with another one is answered 404 (as after an Engine restart); stream, GET /mcp is a
 * long-lived event stream; keepAliveMs, idle connections are closed after that long.
 * tokenPerConnection: each connection is answered its own token (`${token}-${conn}`), and
 * requireToken checks that one; closeAfterPost: every POST answer closes its connection;
 * rejectNext(status): the next POST /mcp with an id is answered status; expireIdleMs: a request
 * that arrives on a connection idle at least that long is dropped unanswered with its connection
 * (the server's idle expiry crossing it on the wire), and no Keep-Alive hint is sent.
 */
async function startService(t, { kind = 'engine', pid = process.pid, instanceId, token = 'token-abc', port = 0, healthStatus = 200, holdUrl = null, requireToken = false, mcpSessions = false, stream = false, keepAliveMs, tokenPerConnection = false, closeAfterPost = false, expireIdleMs, protocolVersion = '2025-06-18', strictProtocol = false, noToken = false } = {}) {
  const id = instanceId || `instance-${process.pid}-${++instanceCounter}`;
  const requests = [];
  // hold(url, { rpc }): the next request to url (with rpc: a POST /mcp of that JSON-RPC method) is
  // answered only when its release(body) is called (a slow Engine call), or never: destroy() drops
  // its connection unanswered. holdUrl holds the first one from the start (arrived/release below).
  const holds = [];
  const hold = (url, { rpc } = {}) => {
    const h = { url, rpc, held: null, socket: null };
    h.arrived = new Promise((resolve) => (h.signal = resolve));
    h.release = (answer) => h.held?.(answer);
    h.destroy = () => h.socket?.destroy();
    holds.push(h);
    return h;
  };
  const first = holdUrl ? hold(holdUrl) : { arrived: new Promise(() => {}), release: () => {} };
  // lastAnswers(): from then on every answer closes its connection, as a stopping Engine's does,
  // so the client keeps no idle connection to reuse once the Engine is gone.
  let closing = false;
  let rejectStatus = null;
  const streams = new Set(); // open GET /mcp event streams
  /** Send a server->client message on every open event stream. */
  const notify = (message) => {
    for (const res of streams) res.write(`data: ${JSON.stringify(message)}\n\n`);
  };
  const server = http.createServer((req, res) => {
    const idleSince = req.socket.idleSince;
    req.socket.idleSince = undefined;
    res.once('finish', () => {
      req.socket.idleSince = Date.now();
    });
    if (expireIdleMs !== undefined && idleSince !== undefined && Date.now() - idleSince >= expireIdleMs) {
      requests.push({ method: req.method, url: req.url, headers: req.headers, body: '', health: healthStatus, conn: req.socket.conn, expired: true });
      req.socket.destroy();
      return;
    }
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      const entry = { method: req.method, url: req.url, headers: req.headers, body, health: healthStatus, conn: req.socket.conn };
      requests.push(entry);
      res.once('close', () => (entry.closed = true));
      const ownToken = tokenPerConnection ? `${token}-${req.socket.conn}` : token;
      const json = (status, obj, headers = {}) => {
        const close = closing || (closeAfterPost && req.method === 'POST');
        res.writeHead(status, { 'content-type': 'application/json', ...headers, ...(close ? { connection: 'close' } : {}) });
        res.end(JSON.stringify(obj));
      };
      const rpcOf = () => {
        try {
          return JSON.parse(body).method;
        } catch {
          return undefined;
        }
      };
      const h = holds.find((x) => x.url === req.url && !x.held && (!x.rpc || (req.method === 'POST' && rpcOf() === x.rpc)));
      if (h) {
        h.socket = req.socket;
        // Answered only on release(); the listener is gone by then, so the connection closes with
        // this answer, as a stopping Engine's does: the client's next request needs a new
        // connection to the port. release(fn) answers fn(request body).
        h.held = (answer) => {
          res.writeHead(200, { 'content-type': 'application/json', connection: 'close' });
          res.end(JSON.stringify(typeof answer === 'function' ? answer(body) : answer));
        };
        h.signal();
        return undefined;
      }
      if (req.url === '/api/health') return json(healthStatus, { ok: healthStatus === 200 });
      if (req.url === '/api/status') return json(200, { status: 'ok' });
      if (kind === 'foreign') return json(404, { error: 'not found' });
      if (req.url === '/api/runtime/live') return json(200, { alive: true, pid, instanceId: id });
      if (req.url === '/api/mcp/token') return noToken ? json(404, {}) : json(200, { token: ownToken });
      if (requireToken && req.method !== 'GET' && req.headers['x-justsearch-session'] !== ownToken) return json(401, { error: 'session token required' });
      // strictProtocol: an MCP request naming another protocol version than the one this Engine
      // answers is refused, as a server that does not support that version refuses it.
      const named = req.headers['mcp-protocol-version'];
      if (strictProtocol && req.url === '/mcp' && named !== undefined && named !== protocolVersion) return json(400, { error: `unsupported protocol version ${named}` });
      if (req.url === '/mcp' && req.method === 'GET' && stream) {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        streams.add(res);
        res.once('close', () => streams.delete(res));
        return res.write(': open\n\n');
      }
      const session = `session-${id}`;
      if (req.url === '/mcp' && req.method === 'POST') {
        const msg = JSON.parse(body);
        if (rejectStatus && msg.id !== undefined) {
          const status = rejectStatus;
          rejectStatus = null;
          return json(status, { error: 'stale session' });
        }
        if (mcpSessions && msg.id !== undefined && msg.method !== 'initialize' && req.headers['mcp-session-id'] !== session) return json(404, { error: 'unknown session' });
        if (msg.id === undefined) return res.writeHead(202).end();
        if (msg.method === 'initialize') {
          return json(200, {
            jsonrpc: '2.0',
            id: msg.id,
            result: { protocolVersion, capabilities: {}, serverInfo: { name: `engine ${id}`, version: '0' } },
          }, mcpSessions ? { 'mcp-session-id': session } : {});
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
  const stats = { connections: 0, open: 0 };
  const sockets = new Set();
  server.on('connection', (socket) => {
    socket.conn = ++stats.connections;
    stats.open++;
    sockets.add(socket);
    socket.once('close', () => {
      stats.open--;
      sockets.delete(socket);
    });
  });
  if (keepAliveMs !== undefined) server.keepAliveTimeout = keepAliveMs;
  if (expireIdleMs !== undefined) server.keepAliveTimeout = 0; // the expiry above is the only one
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
  /** Stop listening and close the connections no request is using (a long-lived stream stays). */
  const closeIdle = () => {
    const stopped = stopListening();
    server.closeIdleConnections();
    return stopped;
  };
  /** Stop listening and drop every connection but keep (a held response can still be sent on it). */
  const dropAllBut = (keep) => {
    const stopped = stopListening();
    for (const socket of sockets) if (socket !== keep) socket.destroy();
    return stopped;
  };
  /** Stop listening and reset every open connection (TCP RST), as a crashed Engine's are. */
  const reset = () => {
    const stopped = stopListening();
    for (const socket of sockets) socket.resetAndDestroy();
    return stopped;
  };
  const lastAnswers = () => {
    closing = true;
  };
  const rejectNext = (status) => {
    rejectStatus = status;
  };
  return { kind, pid, instanceId: id, port: server.address().port, requests, token, rejectNext, close, closeIdle, reset, setHealth, arrived: first.arrived, release: first.release, hold, stopListening, dropAllBut, lastAnswers, notify, stats };
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
    process.stdout.write('REQ ' + JSON.stringify({ method: req.method, url: req.url, session: req.headers['x-justsearch-session'] || null, conn: req.socket.conn }) + '\\n');
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
let conns = 0;
server.on('connection', (socket) => { socket.conn = ++conns; process.stdout.write('CONN\\n'); });
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
async function bridgeSession(m, entry, envExtra = {}, { args = [], ready } = {}) {
  const input =
    `${JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'test', version: '0' } } })}\n` +
    `${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })}\n` +
    `${JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'justsearch_ingest', arguments: { paths: ['/x'] } } })}\n`;
  // The bridge exits when stdin closes; give it the messages, then close after the replies.
  return new Promise((resolve) => {
    const child = spawnNode([entry, ...args], { env: { ...m.env, ...envExtra }, cwd: m.cwd, stdio: ['pipe', 'pipe', 'pipe'] }, 'self');
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
    Promise.resolve(ready).then(() => child.stdin.write(input)); // ready: the messages wait for it
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

test('P6 (bundle): a manifest Engine in dev mode offers an empty session token — initialize and a mutating call succeed, with no token header', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const engine = await startService(t, { token: '' });
  writeManifest(m.desktop, manifestFor(engine));
  const session = await bridgeSession(m, bundle(root));
  const init = session.messages.find((x) => x.id === 1);
  assert.equal(init?.result?.serverInfo?.name, `engine ${engine.instanceId}`, `${JSON.stringify(init)}\n${session.stderr}`);
  const call = session.messages.find((x) => x.id === 2);
  assert.equal(call?.result?.content?.[0]?.text, `tools/call by ${engine.instanceId}`, session.stderr);
  assert.ok(engine.requests.every((r) => r.headers['x-justsearch-session'] === undefined), 'no token header');
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
    await pause(300); // the idle bridge handles the close: a call written on a dropped connection is not sent again
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
    await pause(300); // the idle bridge handles the close: a call written on a dropped connection is not sent again
    second = await startService(t, { token: 'token-second' });
    writeManifest(m.desktop, manifestFor(second));
  });
  const call = session.messages.find((x) => x.id === 2);
  assert.equal(call?.result?.content?.[0]?.text, `tools/call by ${second.instanceId}`, session.stderr);
  assert.equal(call.result.session, 'token-second');
  assert.ok(!second.requests.some((r) => r.headers['x-justsearch-session'] === 'token-first'), 'the old token never reaches the new Engine');
});

/** The production MCP server over stdio, initialized; call(name, args) runs one tool. */
async function startProdMcp(t, m, { args = [], envExtra = {} } = {}) {
  // The SDK is a root devDependency: without it these cases fail, they are never skipped.
  assert.doesNotThrow(
    () => require.resolve('@modelcontextprotocol/sdk/server/mcp.js', { paths: [path.join(repoRoot, 'scripts', 'prod')] }),
    'production MCP needs the MCP SDK: run npm ci at the repository root',
  );
  const child = spawnNode([PROD_MCP_ENTRY, ...args], { env: { ...m.env, ...envExtra }, cwd: m.cwd, stdio: ['pipe', 'pipe', 'pipe'] }, 'stdin');
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
    await pause(300); // the idle bridge handles the close: a call written on a dropped connection is not sent again
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

test('D9: no code path reaches the Engine except engineRequest (one http.request, behind the proving or token-binding agent)', () => {
  const clients = ['packaging/mcpb/server/index.js', 'scripts/prod/justsearch-mcp/server.mjs', 'scripts/prod/justsearch-mcp/discovery.mjs'];
  const direct = /\brequire\(\s*['"](node:)?(http|https|net)['"]\s*\)|\bfrom\s+['"](node:)?(http|https|net)['"]|\bfetch\s*\(|\bhttp\.(request|get)\s*\(|\bnet\.(connect|createConnection)\s*\(/;
  for (const rel of clients) {
    const src = fs.readFileSync(path.join(repoRoot, rel), 'utf8');
    assert.doesNotMatch(src, direct, `${rel} must send through engine-discovery.cjs engineRequest`);
    assert.match(src, /engineRequest\(/, rel);
  }
  const shared = fs.readFileSync(ENGINE_DISCOVERY, 'utf8');
  assert.equal(shared.match(/\bhttp\.(request|get)\s*\(/g)?.length, 1, 'one http.request, in send');
  assert.match(shared, /function send\([\s\S]*?\{\s*return http\.request\(/, 'the one http.request is send()');
  // send() is called twice: by engineRequest through the target's agent (proven or token-bound
  // connections), and by bootstrapGet for the token and identity requests a token-bound
  // connection makes on itself (tokenBoundConnection) before it is handed out.
  assert.equal(shared.match(/(?<!function )\bsend\((?!\))/g)?.length, 2, 'send() called twice');
  assert.match(shared, /function engineRequest[\s\S]*?send\(agentFor\(target\)/, 'engineRequest always uses the target agent');
  assert.match(shared, /function bootstrapGet\(socket[\s\S]*?send\(\s*undefined,[\s\S]*?createConnection: \(\) => socket/, 'the bootstrap requests go only on the connection being opened');
  assert.match(shared, /function tokenBoundConnection[\s\S]*?bootstrapGet\(socket, options\.port, '\/api\/mcp\/token'/, 'the token-bound connection fetches its own token');
  assert.equal(shared.match(/\bnet\.connect\s*\(/g)?.length, 1, 'one net.connect, in connect');
  assert.equal(shared.match(/\bconnect\(options\)/g)?.length, 3, 'connect() defined once, called by provenConnection and tokenBoundConnection');
  assert.doesNotMatch(shared, /\bfetch\s*\(/);
});

// ---------------------------------------------------------------------------
// Pinned ports (--port, JUSTSEARCH_API_PORT): a credential goes only on a connection proven
// before anything was written on it (manifest proof, or the token fetched on that connection)
// ---------------------------------------------------------------------------

const PINS = {
  '--port': (port) => ({ args: ['--port', String(port)], label: `--port ${port}` }),
  JUSTSEARCH_API_PORT: (port) => ({ envExtra: { JUSTSEARCH_API_PORT: String(port) }, label: `JUSTSEARCH_API_PORT=${port}` }),
};

/** prodDiscover options for a pin. */
function pinDiscover(kind, port) {
  return kind === '--port' ? { port } : { envExtra: { JUSTSEARCH_API_PORT: String(port) } };
}

/**
 * Every request that carried a session token carried issued, and that token was answered to an
 * earlier GET /api/mcp/token on the same connection (requests in arrival order; a service's
 * records carry headers, an impostor's carry session).
 */
function assertOwnTokens(requests, issued, label) {
  for (const [i, r] of requests.entries()) {
    const session = r.headers ? r.headers['x-justsearch-session'] : r.session;
    if (!session) continue;
    assert.equal(session, issued, `${label}: ${r.method} ${r.url} carried ${session}`);
    assert.ok(
      requests.slice(0, i).some((x) => x.conn === r.conn && x.url === '/api/mcp/token'),
      `${label}: ${r.method} ${r.url} on connection ${r.conn} carried a token not fetched on that connection`,
    );
  }
}

/** The requests that reached a replacement carrying the Engine's token. */
function engineTokenAt(seen, engine) {
  return seen.requests.filter((r) => r.session === engine.token);
}

/** Resolves once cond() holds (polled), or throws after timeoutMs. */
async function until(cond, timeoutMs, what) {
  const deadline = Date.now() + timeoutMs;
  while (!cond()) {
    if (Date.now() >= deadline) throw new Error(`${what}: not within ${timeoutMs} ms`);
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

for (const kind of Object.keys(PINS)) {
  test(`P1/P2 (${kind}): a pinned port that a later candidate's manifest names is that Engine — once a replacement holds the port, production MCP sends it nothing and answers ENGINE_GONE naming the pin`, async (t) => {
    const root = tempRoot(t);
    const m = machine(root);
    const decoy = await startService(t);
    writeManifest(m.desktop, manifestFor(decoy));
    const engine = await startService(t);
    writeManifest(m.userData, manifestFor(engine)); // a later candidate than the desktop app's
    const pin = PINS[kind](engine.port);
    const prod = await startProdMcp(t, m, pin);
    const ingest = { paths: [path.join(root, 'doc.txt')] };
    engine.lastAnswers(); // the next call needs a new connection (see the D9 ingest case)
    assert.match(await prod.call('justsearch_ingest', ingest), new RegExp(`ingest by ${engine.instanceId}`), prod.stderr);

    await engine.close();
    const impostor = await startImpostor(t, { port: engine.port, pid: engine.pid, instanceId: engine.instanceId });
    const t0 = Date.now();
    const result = await prod.call('justsearch_ingest', ingest);
    const elapsed = Date.now() - t0;
    const seen = await impostor.settled();
    assert.deepEqual(seen.requests, [], `nothing, and no token, reaches the replacement\n${prod.stderr}`);
    assert.ok(seen.connections > 0, `the call tried the port and was refused there\n${prod.stderr}`);
    assert.match(result, /ENGINE_GONE/, prod.stderr);
    assert.ok(result.includes(`pinned by ${pin.label}`), `the error names the pin: ${result}`);
    assert.ok(elapsed < 10000, `refused within the call's deadline (${elapsed} ms)`);
    assert.equal(engine.requests.find((r) => r.url === '/api/knowledge/ingest')?.headers['x-justsearch-session'], engine.token);
    assert.ok(!decoy.requests.some((r) => r.url !== '/api/runtime/live' && r.url !== '/api/health'), 'the other candidate is only probed at startup');
  });

  test(`P1b/P2 (${kind}): a manifest names the pinned port but the proof fails (identity copied, another live pid) — the pin is refused, nothing is written there, no token-bound fallback`, async (t) => {
    const root = tempRoot(t);
    const m = machine(root);
    const livePid = await unrelatedLivePid(t);
    const impostor = await startImpostor(t, { pid: livePid, instanceId: 'copied-instance' });
    writeManifest(m.desktop, { schemaVersion: 2, pid: livePid, instanceId: 'copied-instance', lifecycle: 'LIFECYCLE_STATE_READY', head: { apiPort: impostor.port } });
    const res = await prodDiscover(m, pinDiscover(kind, impostor.port));
    const bridge = kind === 'JUSTSEARCH_API_PORT' ? await bridgeSession(m, bundle(root), { JUSTSEARCH_API_PORT: String(impostor.port) }) : null;
    const seen = await impostor.settled();
    assert.deepEqual(seen.requests, [], `nothing at all is written to the unproven server\n${res.stderr}\n${bridge?.stderr ?? ''}`);
    assert.ok(seen.connections > 0, 'the proof was tried there');
    const label = kind === '--port' ? `--port ${impostor.port}` : `JUSTSEARCH_API_PORT=${impostor.port}`;
    assert.equal(res.result, null, res.stdout);
    assert.ok(res.stderr.includes(`${label}: refused, the manifest naming this port is not proven`), res.stderr);
    assert.match(res.stderr, /owner-mismatch/);
    if (bridge) {
      assert.notEqual(bridge.code, 0, bridge.stderr);
      assert.ok(bridge.stderr.includes(`${label}: refused, the manifest naming this port is not proven`), bridge.stderr);
    }
  });

  test(`P1b/P2 (${kind}): the manifest accepted for the pin is deleted, or corrupted, and a replacement takes the port — production MCP answers ENGINE_GONE and never downgrades to a token-bound connection`, async (t) => {
    for (const change of ['deleted', 'corrupted']) {
      const root = tempRoot(t);
      const m = machine(root);
      const engine = await startService(t);
      writeManifest(m.desktop, manifestFor(engine));
      const prod = await startProdMcp(t, m, PINS[kind](engine.port));
      const ingest = { paths: [path.join(root, 'doc.txt')] };
      engine.lastAnswers();
      assert.match(await prod.call('justsearch_ingest', ingest), new RegExp(`ingest by ${engine.instanceId}`), prod.stderr);
      const manifest = path.join(m.desktop, 'runtime', 'manifest.json');
      if (change === 'deleted') fs.rmSync(manifest);
      else fs.writeFileSync(manifest, '{"pid": 1, "instanceId": "trunc');
      await engine.close();
      const impostor = await startImpostor(t, { port: engine.port, pid: engine.pid, instanceId: engine.instanceId });
      const result = await prod.call('justsearch_ingest', ingest);
      const seen = await impostor.settled();
      assert.deepEqual(seen.requests, [], `${change}: nothing, and no token, reaches the replacement\n${prod.stderr}`);
      assert.match(result, /ENGINE_GONE/, `${change}: ${prod.stderr}`);
      assert.match(result, change === 'deleted' ? /no-manifest/ : /malformed/, result);
    }
  });

  test(`P1c/P2 (${kind}): inside one answer call on a manifest-named pin, the port is taken after retrieval — the continuations reach nothing there and the answer holds nothing from it`, async (t) => {
    const root = tempRoot(t);
    const m = machine(root);
    const engine = await startService(t, { holdUrl: '/api/knowledge/retrieve-context' });
    writeManifest(m.desktop, manifestFor(engine));
    const prod = await startProdMcp(t, m, PINS[kind](engine.port));
    const answer = prod.call('justsearch_answer', { query: 'tides', verify_citations: { answer_text: 'The keeper writes notes.' } });
    const impostor = await takeOverWhileHeld(t, engine, { context: `context by ${engine.instanceId}`, total_found: 1, chunks: [], quality: {} });
    const result = await answer;
    const seen = await impostor.settled();
    assert.deepEqual(seen.requests, [], `no continuation request, and no token, reaches the process that took the port\n${prod.stderr}`);
    assert.ok(seen.connections > 0, `the continuation tried the port and was refused there\n${prod.stderr}`);
    assert.doesNotMatch(result, /impostor/, 'nothing from the replacement ends up in the answer');
    const answered = JSON.parse(result).structuredContent;
    assert.equal(answered?.context, `context by ${engine.instanceId}`, result);
    assert.equal(answered.citations, undefined, 'the refused citation check adds nothing');
  });
}

test('P3: a pin no manifest names — reused connections carry the token fetched on them; once a replacement answers the port it gets request content and only its own token', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const engine = await startService(t);
  const prod = await startProdMcp(t, m, PINS['--port'](engine.port));
  const ingest = { paths: [path.join(root, 'doc.txt')] };
  for (let i = 0; i < 2; i++) assert.match(await prod.call('justsearch_ingest', ingest), new RegExp(`ingest by ${engine.instanceId}`), prod.stderr);
  engine.lastAnswers();
  assert.match(await prod.call('justsearch_ingest', ingest), new RegExp(`ingest by ${engine.instanceId}`), prod.stderr);
  await engine.close();
  const impostor = await startImpostor(t, { port: engine.port, pid: engine.pid, instanceId: engine.instanceId });
  const result = await prod.call('justsearch_ingest', ingest);
  const seen = await impostor.settled();
  assert.deepEqual(engineTokenAt(seen, engine), [], `the Engine's token never reaches the replacement\n${prod.stderr}`);
  assertOwnTokens(seen.requests, 'impostor-token', 'replacement');
  assert.ok(seen.requests.some((r) => r.url === '/api/knowledge/ingest' && r.session === 'impostor-token'), `the pin is used as given: the call reached it\n${prod.stderr}`);
  assert.match(result, /impostor/, result);
  assert.equal(engine.requests.filter((r) => r.url === '/api/knowledge/ingest').length, 3);
  assert.equal(engine.stats.connections, 1, 'startup and all three calls shared one connection (its token fetched once, on it)');
  assertOwnTokens(engine.requests, engine.token, 'Engine');
});

test('P3: concurrent calls while a token fetch is held open — a connection opened to a replacement meanwhile carries only its own token; the held one is refused; the busy one is answered by the Engine', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const engine = await startService(t);
  const pin = PINS['--port'](engine.port);
  const prod = await startProdMcp(t, m, pin);
  const ingest = { paths: [path.join(root, 'doc.txt')] };
  const busy = engine.hold('/api/knowledge/ingest');
  const a = prod.call('justsearch_ingest', ingest); // on the startup connection, held
  await within(busy.arrived, 10000, 'call A arriving');
  engine.lastAnswers();
  const tokenFetch = engine.hold('/api/mcp/token');
  const b = prod.call('justsearch_ingest', ingest); // a new connection, whose token answer is held
  const bDone = b.then(() => 'done');
  await Promise.race([tokenFetch.arrived, bDone, pause(3000)]);
  await engine.stopListening(); // A's and B's connections stay
  const impostor = await startImpostor(t, { port: engine.port, pid: engine.pid, instanceId: engine.instanceId });
  const c = await prod.call('justsearch_ingest', ingest); // a third connection, to the replacement
  tokenFetch.release({ token: engine.token }); // its connection closes with this answer
  busy.release({ success: true, operationKey: `op-${engine.instanceId}`, message: `ingest by ${engine.instanceId}` });
  const [resultA, resultB] = await Promise.all([a, b]);
  const seen = await impostor.settled();
  assert.deepEqual(engineTokenAt(seen, engine), [], `the Engine's token never reaches the replacement\n${prod.stderr}`);
  assertOwnTokens(seen.requests, 'impostor-token', 'replacement');
  assert.match(c, /impostor/, 'call C reached the replacement, as a pin allows');
  assert.match(resultA, new RegExp(`ingest by ${engine.instanceId}`), resultA);
  assert.match(resultB, /ENGINE_GONE/, `call B: its connection closed before it could be used\n${resultB}`);
  assert.ok(resultB.includes(`pinned by ${pin.label}`), resultB);
  assertOwnTokens(engine.requests, engine.token, 'Engine');
});

test('P3: the port is replaced while the startup token response is held (it closes its connection) — both clients refuse the pin; the replacement never gets the Engine\'s token', async (t) => {
  for (const client of ['production MCP', 'bundle']) {
    const root = tempRoot(t);
    const m = machine(root);
    const engine = await startService(t, { holdUrl: '/api/mcp/token' });
    const run = client === 'production MCP'
      ? bridgeSession(m, PROD_MCP_ENTRY, {}, { args: ['--port', String(engine.port)] })
      : bridgeSession(m, bundle(root), { JUSTSEARCH_API_PORT: String(engine.port) });
    const impostor = await takeOverWhileHeld(t, engine, { token: engine.token });
    const res = await run;
    const seen = await impostor.settled();
    assert.deepEqual(engineTokenAt(seen, engine), [], `${client}: the Engine's token never reaches the replacement\n${res.stderr}`);
    assertOwnTokens(seen.requests, 'impostor-token', `${client}: replacement`);
    assert.notEqual(res.code, 0, `${client}: no connection to the pin fetched its own token, so it is refused\n${res.stderr}`);
    const label = client === 'production MCP' ? `--port ${engine.port}` : `JUSTSEARCH_API_PORT=${engine.port}`;
    assert.ok(res.stderr.includes(label), `${client}: the refusal names the pin\n${res.stderr}`);
  }
});

test('P3: right after its token response the Engine stops (Connection: close) and a replacement binds — both clients refuse the pin; the replacement never gets the Engine\'s token', async (t) => {
  for (const client of ['production MCP', 'bundle']) {
    const root = tempRoot(t);
    const m = machine(root);
    const engine = await startService(t);
    engine.lastAnswers();
    let bound;
    const ready = new Promise((resolve) => (bound = resolve)); // the client's messages follow the replacement
    const run = client === 'production MCP'
      ? bridgeSession(m, PROD_MCP_ENTRY, {}, { args: ['--port', String(engine.port)], ready })
      : bridgeSession(m, bundle(root), { JUSTSEARCH_API_PORT: String(engine.port) }, { ready });
    await until(() => engine.requests.some((r) => r.url === '/api/mcp/token'), 10000, 'the token request');
    await engine.close();
    const impostor = await startImpostor(t, { port: engine.port, pid: engine.pid, instanceId: engine.instanceId });
    bound();
    const res = await run;
    const seen = await impostor.settled();
    assert.deepEqual(engineTokenAt(seen, engine), [], `${client}: the Engine's token never reaches the replacement\n${res.stderr}`);
    assertOwnTokens(seen.requests, 'impostor-token', `${client}: replacement`);
    assert.notEqual(res.code, 0, `${client}: the token's connection closed with it, so the pin is refused\n${res.stderr}`);
  }
});

test('P3: the Engine evicts the idle connection, or resets it, and a replacement binds — the next call carries only the replacement\'s own token', async (t) => {
  for (const how of ['idle eviction', 'reset']) {
    const root = tempRoot(t);
    const m = machine(root);
    const engine = await startService(t, how === 'idle eviction' ? { keepAliveMs: 300 } : {});
    const prod = await startProdMcp(t, m, PINS['--port'](engine.port));
    const ingest = { paths: [path.join(root, 'doc.txt')] };
    assert.match(await prod.call('justsearch_ingest', ingest), new RegExp(`ingest by ${engine.instanceId}`), prod.stderr);
    if (how === 'reset') await engine.reset();
    await until(() => engine.stats.open === 0, 10000, `${how}: the Engine's side of the connection closing`);
    await pause(300); // the idle client handles the close (see the D9 ingest case for why it matters)
    await engine.close();
    const impostor = await startImpostor(t, { port: engine.port, pid: engine.pid, instanceId: engine.instanceId });
    const result = await prod.call('justsearch_ingest', ingest);
    const seen = await impostor.settled();
    assert.deepEqual(engineTokenAt(seen, engine), [], `${how}: the Engine's token never reaches the replacement\n${prod.stderr}`);
    assertOwnTokens(seen.requests, 'impostor-token', `${how}: replacement`);
    assert.match(result, /impostor/, `${how}: the call reached the replacement, as a pin allows\n${result}`);
  }
});

test('P3: the port is replaced between the status check and the token fetch — the replacement is asked for its own token and gets no other', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const engine = await startService(t, { holdUrl: '/api/status' });
  const run = bridgeSession(m, PROD_MCP_ENTRY, {}, { args: ['--port', String(engine.port)] });
  const impostor = await takeOverWhileHeld(t, engine, { status: 'ok' });
  const res = await run;
  const seen = await impostor.settled();
  assert.deepEqual(engineTokenAt(seen, engine), [], `the Engine's token never reaches the replacement\n${res.stderr}`);
  assertOwnTokens(seen.requests, 'impostor-token', 'replacement');
  assert.match(JSON.stringify(res.messages.find((x) => x.id === 2)), /impostor/, `the pin is used as given\n${res.stderr}`);
});

test('P7: the Engine restarts on the pinned port with a new token — the call in flight fails explicitly and is not sent again; the next call fetches the new token on a new connection and succeeds', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const first = await startService(t, { token: 'token-first', requireToken: true });
  const prod = await startProdMcp(t, m, PINS['--port'](first.port));
  const ingest = { paths: [path.join(root, 'doc.txt')] };
  assert.match(await prod.call('justsearch_ingest', ingest), new RegExp(`ingest by ${first.instanceId}`), prod.stderr);
  const lost = first.hold('/api/knowledge/ingest');
  const inFlight = prod.call('justsearch_ingest', ingest);
  await within(lost.arrived, 10000, 'the in-flight ingest arriving');
  await first.reset(); // the Engine goes; the in-flight ingest's answer is lost
  const second = await startService(t, { port: first.port, token: 'token-second', requireToken: true });
  const lostResult = await inFlight;
  const next = await prod.call('justsearch_ingest', ingest);
  assert.deepEqual(second.requests.filter((r) => r.headers['x-justsearch-session'] === 'token-first'), [], `the old token never reaches the restarted Engine\n${prod.stderr}`);
  assert.match(next, new RegExp(`ingest by ${second.instanceId}`), `${next}\n${prod.stderr}`);
  assert.equal(second.requests.filter((r) => r.url === '/api/knowledge/ingest').length, 1, 'the lost ingest is not sent again');
  assert.match(lostResult, /NOT_CONNECTED|ENGINE_GONE/, `the call in flight fails explicitly: ${lostResult}`);
  assertOwnTokens(second.requests, 'token-second', 'restarted Engine');
});

test('P5: bridge pinned to a port a manifest names — after the Engine goes and a replacement copying its identity binds, nothing reaches the replacement and the call fails naming the refusal', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const engine = await startService(t);
  writeManifest(m.desktop, manifestFor(engine));
  let impostor = null;
  const session = await bridgeInteractive({ ...m, env: { ...m.env, JUSTSEARCH_API_PORT: String(engine.port) } }, bundle(root), async () => {
    await handshakeDone(engine);
    await engine.close();
    await pause(300); // the idle bridge handles the close: a call written on a dropped connection is not sent again
    impostor = await startImpostor(t, { port: engine.port, pid: engine.pid, instanceId: engine.instanceId });
  });
  const seen = await impostor.settled();
  assert.deepEqual(seen.requests, [], `nothing, and no token, reaches the replacement\n${session.stderr}`);
  assert.equal(session.messages.find((x) => x.id === 1)?.result?.serverInfo?.name, `engine ${engine.instanceId}`, session.stderr);
  const call = session.messages.find((x) => x.id === 2);
  assert.ok(call?.error, `the call fails instead of reaching the replacement: ${JSON.stringify(call)}\n${session.stderr}`);
  assert.match(session.stderr, new RegExp(`JUSTSEARCH_API_PORT=${engine.port}: refused, the manifest naming this port is not proven \\(.*owner-mismatch`));
  assert.match(session.stderr, new RegExp(`pinned by JUSTSEARCH_API_PORT=${engine.port}`));
});

test('P5: bridge pinned to a port no manifest names — the long-lived GET stream keeps one connection; initialize, initialized and each call carry only their own connection\'s token; a replacement gets only its own', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const engine = await startService(t, { stream: true });
  let impostor = null;
  let streamOpen = null;
  const session = await bridgeInteractive({ ...m, env: { ...m.env, JUSTSEARCH_API_PORT: String(engine.port) } }, bundle(root), async () => {
    await handshakeDone(engine, { stream: true });
    await engine.closeIdle(); // the GET stream's connection stays open
    await until(() => engine.stats.open === 1, 10000, 'the idle connections closing');
    await pause(300); // the idle bridge handles the close
    streamOpen = engine.stats.open;
    impostor = await startImpostor(t, { port: engine.port, pid: engine.pid, instanceId: engine.instanceId });
  });
  const seen = await impostor.settled();
  assert.deepEqual(engineTokenAt(seen, engine), [], `the Engine's token never reaches the replacement\n${session.stderr}`);
  assertOwnTokens(seen.requests, 'impostor-token', 'replacement');
  assert.ok(seen.requests.some((r) => r.url === '/mcp' && r.method === 'POST' && r.session === 'impostor-token'), `the call reached the pin, as a pin allows\n${session.stderr}`);
  assertOwnTokens(engine.requests, engine.token, 'Engine');
  for (const step of ['initialize', 'notifications/initialized']) {
    const sent = engine.requests.find((r) => r.url === '/mcp' && r.method === 'POST' && JSON.parse(r.body).method === step);
    assert.equal(sent?.headers['x-justsearch-session'], engine.token, `${step} carries the token of its own connection`);
  }
  assert.equal(streamOpen, 1, 'the GET stream was still open on its own connection when the call was made');
});

test('P5/P7: bridge pinned to a port no manifest names — the Engine restarts there with a new token and session: the new identity leads to re-initialize, initialized and the call, sent once, each on a connection that fetched the new token', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const first = await startService(t, { token: 'token-first', requireToken: true, mcpSessions: true });
  let second = null;
  const session = await bridgeInteractive({ ...m, env: { ...m.env, JUSTSEARCH_API_PORT: String(first.port) } }, bundle(root), async () => {
    await handshakeDone(first);
    await first.close();
    await pause(300); // the idle bridge handles the close
    second = await startService(t, { port: first.port, token: 'token-second', requireToken: true, mcpSessions: true });
  });
  assert.deepEqual(second.requests.filter((r) => r.headers['x-justsearch-session'] === 'token-first'), [], `the old token never reaches the restarted Engine\n${session.stderr}`);
  const call = session.messages.find((x) => x.id === 2);
  assert.equal(call?.result?.content?.[0]?.text, `tools/call by ${second.instanceId}`, `${JSON.stringify(call)}\n${session.stderr}`);
  assert.equal(call.result.session, 'token-second');
  assertOwnTokens(second.requests, 'token-second', 'restarted Engine');
  const posts = second.requests.filter((r) => r.url === '/mcp' && r.method === 'POST').map((r) => JSON.parse(r.body).method);
  assert.deepEqual(posts, ['initialize', 'notifications/initialized', 'tools/call'], 'the old session is never used there: re-initialized, then the call, once');
  assert.match(session.stderr, /reconnected to/);
});

// ---------------------------------------------------------------------------
// Round 2: the token fetch is bounded; a manifest-accepted or stale-manifest pin is refused;
// nothing that may have been sent is sent again; an MCP session stays with its endpoint; a
// manifest-pinned Engine that restarts on its port is selected again
// ---------------------------------------------------------------------------

const INIT_LINE = `${JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'test', version: '0' } } })}\n`;
const callLine = (id) => `${JSON.stringify({ jsonrpc: '2.0', id, method: 'tools/call', params: { name: 'justsearch_ingest', arguments: { paths: ['/x'] } } })}\n`;
const INITIALIZED_LINE = `${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })}\n`;

/**
 * Start a client pinned to port (production MCP: --port; bundle: JUSTSEARCH_API_PORT) and write
 * firstLine (initialize), leaving stdin open. exited resolves to the exit code; messages and stderr fill in.
 */
function startPinnedClient(client, m, root, port, envExtra = {}, firstLine = INIT_LINE) {
  const [entry, args, env] = client === 'production MCP'
    ? [PROD_MCP_ENTRY, ['--port', String(port)], {}]
    : [bundle(root), [], { JUSTSEARCH_API_PORT: String(port) }];
  const child = spawnNode([entry, ...args], { env: { ...m.env, ...env, ...envExtra }, cwd: m.cwd, stdio: ['pipe', 'pipe', 'pipe'] }, 'stdin');
  const out = { child, messages: [], stderr: '' };
  let buf = '';
  child.stdout.on('data', (d) => {
    buf += d;
    let nl;
    while ((nl = buf.indexOf('\n')) !== -1) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (line) out.messages.push(JSON.parse(line));
    }
  });
  child.stderr.on('data', (d) => (out.stderr += d));
  child.stdin.on('error', () => {});
  out.exited = new Promise((resolve) => child.once('exit', resolve));
  child.stdin.write(firstLine);
  return out;
}

test('P3 (bound): the pin accepts the token request and never answers it — both clients refuse within the probe bound, naming it, with stdin still open', async (t) => {
  for (const client of ['production MCP', 'bundle']) {
    const root = tempRoot(t);
    const m = machine(root);
    const engine = await startService(t, { holdUrl: '/api/mcp/token' }); // never released
    const t0 = Date.now();
    const run = startPinnedClient(client, m, root, engine.port);
    const code = await within(run.exited, 7000, `${client}: a refusal while the token answer never comes\n${run.stderr}`);
    const elapsed = Date.now() - t0;
    assert.notEqual(code, 0, `${client}: refused\n${run.stderr}`);
    assert.match(run.stderr, /did not answer GET \/api\/mcp\/token within 2000 ms/, `${client}: the error says why`);
    assert.ok(elapsed < 6000, `${client}: no slower than the bound (${elapsed} ms)`);
    assert.ok(engine.requests.every((r) => r.url === '/api/mcp/token'), `${client}: nothing but the token request was written`);
  }
});

test('P3 (bound): closing stdin stops either client while discovery still waits (an Engine starting at /api/health 503)', async (t) => {
  for (const client of ['production MCP', 'bundle']) {
    const root = tempRoot(t);
    const m = machine(root);
    const engine = await startService(t, { healthStatus: 503 });
    writeManifest(m.desktop, manifestFor(engine));
    const run = startPinnedClient(client, m, root, engine.port);
    await until(() => engine.requests.some((r) => r.url === '/api/health'), 10000, `${client}: discovery reaching the starting Engine`);
    run.child.stdin.end();
    const code = await within(run.exited, 3000, `${client}: exit after stdin closed during discovery (the startup wait is 30 s)\n${run.stderr}`);
    assert.equal(code, 0, `${client}: ${run.stderr}`);
  }
});

test('P1b/P5 (bundle): the manifest accepted for the pin is deleted, or corrupted, and a replacement copying the identity binds — the bridge writes nothing there and fails the call; no token-bound downgrade', async (t) => {
  for (const change of ['deleted', 'corrupted']) {
    const root = tempRoot(t);
    const m = machine(root);
    const engine = await startService(t);
    writeManifest(m.desktop, manifestFor(engine));
    let impostor = null;
    const session = await bridgeInteractive({ ...m, env: { ...m.env, JUSTSEARCH_API_PORT: String(engine.port) } }, bundle(root), async () => {
      await handshakeDone(engine);
      const manifest = path.join(m.desktop, 'runtime', 'manifest.json');
      if (change === 'deleted') fs.rmSync(manifest);
      else fs.writeFileSync(manifest, '{"pid": 1, "instanceId": "trunc');
      await engine.close();
      await pause(300); // the idle bridge handles the close: a call written on a dropped connection is not sent again
      impostor = await startImpostor(t, { port: engine.port, pid: engine.pid, instanceId: engine.instanceId });
    });
    const seen = await impostor.settled();
    assert.deepEqual(seen.requests, [], `${change}: nothing, and no token, reaches the replacement\n${session.stderr}`);
    const call = session.messages.find((x) => x.id === 2);
    assert.ok(call?.error, `${change}: the call fails: ${JSON.stringify(call)}\n${session.stderr}`);
    assert.ok(
      session.stderr.includes(`JUSTSEARCH_API_PORT=${engine.port}: refused, a runtime manifest named this port when it was accepted and none does now`),
      `${change}: the refusal says why\n${session.stderr}`,
    );
  }
});

test('P7 (bundle): the connection drops after a mutating call was sent — it is not sent again, and the error says its outcome is unknown', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const engine = await startService(t);
  writeManifest(m.desktop, manifestFor(engine));
  const session = await bridgeInteractive(m, bundle(root), async () => {
    await handshakeDone(engine);
    const lost = engine.hold('/mcp', { rpc: 'tools/call' });
    lost.arrived.then(() => lost.destroy()); // the Engine got the call; its answer never comes
  });
  const calls = engine.requests.filter((r) => r.url === '/mcp' && r.method === 'POST' && JSON.parse(r.body).method === 'tools/call');
  assert.equal(calls.length, 1, `the call reached the Engine once and was not sent again\n${session.stderr}`);
  const call = session.messages.find((x) => x.id === 2);
  assert.match(call?.error?.message ?? '', /outcome is unknown, so it was not sent again/, `${JSON.stringify(call)}\n${session.stderr}`);
});

test('P1b (stale): a manifest whose pid is gone names the pinned port and something else answers there — both clients refuse, write nothing to it, and name the manifest', async (t) => {
  for (const client of ['production MCP', 'bundle']) {
    const root = tempRoot(t);
    const m = machine(root);
    const impostor = await startImpostor(t, { pid: 2147483647, instanceId: 'gone-instance' });
    writeManifest(m.desktop, { schemaVersion: 2, pid: await deadPid(), instanceId: 'gone-instance', lifecycle: 'LIFECYCLE_STATE_READY', head: { apiPort: impostor.port } });
    const res = client === 'production MCP'
      ? await prodDiscover(m, { port: impostor.port })
      : await bridgeSession(m, bundle(root), { JUSTSEARCH_API_PORT: String(impostor.port) });
    const seen = await impostor.settled();
    assert.deepEqual(seen.requests, [], `${client}: nothing is written to what answers the port\n${res.stderr}`);
    assert.notEqual(res.code, 0, `${client}: refused\n${res.stderr}`);
    const manifest = path.join(m.desktop, 'runtime', 'manifest.json');
    assert.ok(res.stderr.includes(`refused, the manifest naming this port is not proven`) && res.stderr.includes(`${manifest} names this port, but its pid`), `${client}: ${res.stderr}`);
  }
});

test('P5 (bundle): a pin no manifest names is restarted with a new token — the old Mcp-Session-Id is never sent to the new endpoint', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const first = await startService(t, { token: 'token-first', mcpSessions: true, stream: true });
  let second = null;
  const session = await bridgeInteractive({ ...m, env: { ...m.env, JUSTSEARCH_API_PORT: String(first.port) } }, bundle(root), async () => {
    await handshakeDone(first, { stream: true });
    await first.close();
    await pause(300); // the idle bridge handles the close
    second = await startService(t, { port: first.port, token: 'token-second', mcpSessions: true, stream: true });
  });
  const oldSession = `session-${first.instanceId}`;
  const carried = second.requests.filter((r) => r.headers['mcp-session-id'] === oldSession).map((r) => `${r.method} ${r.url}`);
  assert.deepEqual(carried, [], `the old session stays with the endpoint that opened it\n${session.stderr}`);
  const call = session.messages.find((x) => x.id === 2);
  assert.equal(call?.result?.content?.[0]?.text, `tools/call by ${second.instanceId}`, `${JSON.stringify(call)}\n${session.stderr}`);
});

// ---------------------------------------------------------------------------
// Round 4: a fresh initialize carries nothing negotiated with an earlier Engine
// ---------------------------------------------------------------------------

/** The /mcp POSTs service received whose JSON-RPC method is (or, with not, is not) method. */
function mcpPosts(service, method, { not = false } = {}) {
  return service.requests.filter((r) => r.url === '/mcp' && r.method === 'POST' && (JSON.parse(r.body).method === method) !== not);
}

/** A request's header names, with the MCP ones' values; what differs per Engine is left out. */
function initShape(request) {
  return Object.keys(request.headers)
    .filter((h) => !['host', 'content-length', 'connection', 'x-justsearch-session'].includes(h))
    .sort()
    .map((h) => (h.startsWith('mcp-') ? `${h}: ${request.headers[h]}` : h));
}

test('P5 (bundle): the Engine on a pin is replaced by one that speaks another MCP protocol version — the re-initialize is sent as a first one (no session, no version) and the version is negotiated again', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const first = await startService(t, { mcpSessions: true, stream: true, protocolVersion: '2025-03-26', strictProtocol: true });
  let second = null;
  const session = await bridgeInteractive({ ...m, env: { ...m.env, JUSTSEARCH_API_PORT: String(first.port) } }, bundle(root), async () => {
    await handshakeDone(first, { stream: true });
    await first.close();
    await pause(300); // the idle bridge handles the close
    second = await startService(t, { port: first.port, mcpSessions: true, stream: true, protocolVersion: '2025-06-18', strictProtocol: true });
  });
  const call = session.messages.find((x) => x.id === 2);
  assert.equal(call?.result?.content?.[0]?.text, `tools/call by ${second.instanceId}`, `${JSON.stringify(call)}\n${session.stderr}`);
  const [firstInit] = mcpPosts(first, 'initialize');
  const [reinit] = mcpPosts(second, 'initialize');
  assert.equal(reinit.headers['mcp-protocol-version'], undefined, 'the old version is not carried to the replacement');
  assert.equal(reinit.headers['mcp-session-id'], undefined, 'nor the old session');
  assert.deepEqual(initShape(reinit), initShape(firstInit), 'sent as the first-ever initialize was');
  assert.deepEqual(JSON.parse(reinit.body).params, JSON.parse(firstInit.body).params);
  const after = [...mcpPosts(second, 'initialize', { not: true }), ...second.requests.filter((r) => r.url === '/mcp' && r.method === 'GET')];
  assert.ok(after.length >= 2, 'initialized and the call reached the replacement');
  assert.deepEqual([...new Set(after.map((r) => r.headers['mcp-protocol-version']))], ['2025-06-18'], 'everything after it carries the version the replacement answered');
});

test('P5 (bundle): the client initializes again — that initialize carries no session and no protocol version from the first handshake', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const engine = await startService(t, { mcpSessions: true, stream: true, protocolVersion: '2025-03-26' });
  const run = startPinnedClient('bundle', m, root, engine.port);
  const replied = async (id) => {
    const deadline = Date.now() + 10000;
    while (!run.messages.some((x) => x.id === id)) {
      if (Date.now() >= deadline) throw new Error(`no answer to ${id}\n${run.stderr}`);
      await pause(10);
    }
  };
  await replied(1);
  run.child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })}\n`);
  await handshakeDone(engine, { stream: true });
  run.child.stdin.write(INIT_LINE.replace('"id":1', '"id":5'));
  await replied(5);
  run.child.stdin.end();
  await within(run.exited, 7000, 'the bridge exits once stdin closes');
  const [initial, again] = mcpPosts(engine, 'initialize');
  assert.ok(again, `the second initialize was sent\n${run.stderr}`);
  assert.equal(again.headers['mcp-protocol-version'], undefined, 'no version from the first handshake');
  assert.equal(again.headers['mcp-session-id'], undefined, 'no session from the first handshake');
  assert.deepEqual(initShape(again), initShape(initial), 'sent as the first initialize was');
});

test('P5 (bundle): the client initializes again while a call is in flight and the event stream is open — the old session ends wholesale: the call fails as outcome unknown (its late answer is dropped), and the old stream is closed', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const engine = await startService(t, { mcpSessions: true, stream: true });
  const run = startPinnedClient('bundle', m, root, engine.port);
  await untilMessage(run, (x) => x.id === 1, 'initialize');
  run.child.stdin.write(INITIALIZED_LINE);
  await handshakeDone(engine, { stream: true });
  const held = engine.hold('/mcp', { rpc: 'tools/call' });
  run.child.stdin.write(callLine(4));
  await within(held.arrived, 10000, 'the call at the Engine');
  run.child.stdin.write(INIT_LINE.replace('"id":1', '"id":5'));
  await untilMessage(run, (x) => x.id === 5, 'the second initialize');
  await untilMessage(run, (x) => x.id === 4, 'the call in flight ends');
  held.release((body) => ({ jsonrpc: '2.0', id: JSON.parse(body).id, result: { content: [{ type: 'text', text: 'late' }] } }));
  const gets = () => engine.requests.filter((r) => r.url === '/mcp' && r.method === 'GET');
  await within((async () => { while (gets().length < 2) await pause(10); })(), 5000, 'the new session\'s event stream');
  await pause(100);
  engine.notify({ jsonrpc: '2.0', method: 'notifications/message', params: { level: 'info', data: 'after' } });
  await untilMessage(run, (x) => x.params?.data === 'after', 'a notification in the new session');
  await pause(200);
  assert.equal(run.messages.filter((x) => x.params?.data === 'after').length, 1, 'only the new session\'s stream delivers');
  assert.equal(gets()[0].closed, true, 'the old stream is closed');
  const four = run.messages.filter((x) => x.id === 4);
  assert.equal(four.length, 1, 'one answer for the call');
  assert.match(four[0].error?.message ?? '', /outcome is unknown/, JSON.stringify(four));
  run.child.stdin.end();
  await within(run.exited, 7000, 'the bridge exits once stdin closes');
});

test('P5 (bundle): a client initialize superseded by the next one while unanswered fails as superseded — it is not sent again, so the newer session stays', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const engine = await startService(t, { mcpSessions: true, stream: true });
  const run = startPinnedClient('bundle', m, root, engine.port);
  await untilMessage(run, (x) => x.id === 1, 'initialize');
  run.child.stdin.write(INITIALIZED_LINE);
  await handshakeDone(engine, { stream: true });
  const held = engine.hold('/mcp', { rpc: 'initialize' });
  run.child.stdin.write(INIT_LINE.replace('"id":1', '"id":5'));
  await within(held.arrived, 10000, 'initialize 5 at the Engine');
  run.child.stdin.write(INIT_LINE.replace('"id":1', '"id":6'));
  await untilMessage(run, (x) => x.id === 6, 'initialize 6');
  await untilMessage(run, (x) => x.id === 5, 'initialize 5 ends');
  await pause(300);
  assert.match(run.messages.find((x) => x.id === 5).error?.message ?? '', /superseded/, run.stderr);
  assert.ok(run.messages.find((x) => x.id === 6).result, run.stderr);
  assert.deepEqual(mcpPosts(engine, 'initialize').map((r) => JSON.parse(r.body).id), [1, 5, 6], 'nothing is initialized again after 6');
  run.child.stdin.end();
  await within(run.exited, 7000, 'the bridge exits once stdin closes');
});

test('P5 (bundle): a client initialize the Engine turns away (404) is sent again as itself after recovery — not after a bridge re-initialize, and carrying nothing from that one', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const engine = await startService(t, { mcpSessions: true, stream: true, protocolVersion: '2025-03-26' });
  const run = startPinnedClient('bundle', m, root, engine.port);
  const replied = async (id) => {
    const deadline = Date.now() + 10000;
    while (!run.messages.some((x) => x.id === id)) {
      if (Date.now() >= deadline) throw new Error(`no answer to ${id}\n${run.stderr}`);
      await pause(10);
    }
  };
  await replied(1);
  run.child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })}\n`);
  await handshakeDone(engine, { stream: true });
  engine.rejectNext(404);
  run.child.stdin.write(INIT_LINE.replace('"id":1', '"id":5'));
  await replied(5);
  run.child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id: 6, method: 'tools/call', params: { name: 'justsearch_status', arguments: {} } })}\n`);
  await replied(6); // the session that initialize set up is ready for calls
  assert.equal(run.messages.find((x) => x.id === 6).result?.content?.[0]?.text, `tools/call by ${engine.instanceId}`, run.stderr);
  run.child.stdin.end();
  await within(run.exited, 7000, 'the bridge exits once stdin closes');
  assert.ok(run.messages.find((x) => x.id === 5)?.result, `the client's initialize is answered\n${run.stderr}`);
  const inits = mcpPosts(engine, 'initialize');
  assert.deepEqual(inits.map((r) => JSON.parse(r.body).id), [1, 5, 5], 'only the client\'s own initializes: no bridge re-initialize in between');
  for (const r of inits) assert.deepEqual(initShape(r), initShape(inits[0]), 'each is sent as a first one');
});

// Round 5: the token answer and the bridge's negotiated state follow the Engine, not a side effect.
for (const [from, to] of [['without', 'with'], ['with', 'without']]) {
  test(`P7 (--port): production MCP pinned to a manifest Engine ${from} the token endpoint — it restarts on the port ${to} one, and the first call after it, a mutating one, is decided by the Engine there now`, async (t) => {
    const root = tempRoot(t);
    const m = machine(root);
    const tokenless = { noToken: true };
    const tokened = (token) => ({ token, requireToken: true });
    const first = await startService(t, from === 'without' ? tokenless : tokened('token-first'));
    writeManifest(m.desktop, manifestFor(first));
    const prod = await startProdMcp(t, m, PINS['--port'](first.port));
    const ingest = { paths: [path.join(root, 'doc.txt')] };
    first.lastAnswers(); // no idle connection is left to the first Engine
    const before = await prod.call('justsearch_ingest', ingest);
    if (from === 'without') {
      assert.match(before, /NO_TOKEN/, `the first Engine has no token endpoint\n${prod.stderr}`);
      assert.equal(first.requests.filter((r) => r.url === '/api/knowledge/ingest').length, 0, 'nothing is sent without a token');
    } else {
      assert.match(before, new RegExp(`ingest by ${first.instanceId}`), prod.stderr);
    }
    await first.close();
    const second = await startService(t, { port: first.port, ...(to === 'with' ? tokened('token-second') : tokenless) });
    writeManifest(m.desktop, manifestFor(second));
    const next = await prod.call('justsearch_ingest', ingest); // the first call after the restart
    const ingests = second.requests.filter((r) => r.url === '/api/knowledge/ingest');
    if (to === 'with') {
      assert.match(next, new RegExp(`ingest by ${second.instanceId}`), `${next}\n${prod.stderr}`);
      assert.deepEqual(ingests.map((r) => r.headers['x-justsearch-session']), ['token-second'], 'sent once, with the new Engine\'s token');
    } else {
      assert.match(next, /NO_TOKEN/, `${next}\n${prod.stderr}`);
      assert.deepEqual(ingests, [], 'nothing is sent to an Engine without a token endpoint');
      assert.ok(second.requests.some((r) => r.url === '/api/mcp/token'), 'the Engine there now was asked');
    }
    assert.ok(second.requests.every((r) => r.headers['x-justsearch-session'] !== 'token-first'), 'the old token never reaches it');
  });
}

test('P5 (bundle): an Engine that assigns no Mcp-Session-Id is replaced on the pin — nothing negotiated with it reaches the replacement: the first request there is an initialize sent as a first one, and the version is negotiated again', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const first = await startService(t, { protocolVersion: '2025-03-26', strictProtocol: true });
  let second = null;
  const session = await bridgeInteractive({ ...m, env: { ...m.env, JUSTSEARCH_API_PORT: String(first.port) } }, bundle(root), async () => {
    await handshakeDone(first);
    await first.close();
    await pause(300); // the idle bridge handles the close
    // It would answer a request naming the old version, so only the order shows a reset.
    second = await startService(t, { port: first.port, protocolVersion: '2025-06-18' });
  });
  const call = session.messages.find((x) => x.id === 2);
  assert.equal(call?.result?.content?.[0]?.text, `tools/call by ${second.instanceId}`, `${JSON.stringify(call)}\n${session.stderr}`);
  const posts = second.requests.filter((r) => r.url === '/mcp' && r.method === 'POST');
  assert.deepEqual(posts.map((r) => JSON.parse(r.body).method), ['initialize', 'notifications/initialized', 'tools/call'], 'the replacement is initialized before the call');
  assert.deepEqual(initShape(posts[0]), initShape(mcpPosts(first, 'initialize')[0]), 'sent as the first-ever initialize was');
  assert.deepEqual(posts.slice(1).map((r) => r.headers['mcp-protocol-version']), ['2025-06-18', '2025-06-18'], 'then the version the replacement answered');
  assert.ok(second.requests.every((r) => r.headers['mcp-protocol-version'] !== '2025-03-26'), 'the old version never reaches it');
});

// Round 6: one epoch per Engine in the bridge; a call that needs a token fails closed.

/** Resolve once run (startPinnedClient) has a stdout message matching pred. */
async function untilMessage(run, pred, what) {
  const deadline = Date.now() + 10000;
  while (!run.messages.some(pred)) {
    if (Date.now() >= deadline) throw new Error(`${what}: not within 10 s\n${run.stderr}`);
    await pause(10);
  }
}

test('P5 (bundle): an initialize answer from the old Engine that arrives after the client initialized with its replacement changes nothing — the replacement\'s event stream stays open and its notifications still arrive', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const a = await startService(t, { mcpSessions: true, stream: true, protocolVersion: '2025-03-26' });
  const run = startPinnedClient('bundle', m, root, a.port);
  const initLine = (id) => INIT_LINE.replace('"id":1', `"id":${id}`);
  await untilMessage(run, (x) => x.id === 1, 'first initialize');
  run.child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })}\n`);
  await handshakeDone(a, { stream: true });
  const held = a.hold('/mcp', { rpc: 'initialize' });
  run.child.stdin.write(initLine(5)); // answered late, below
  await held.arrived;
  await a.dropAllBut(held.socket);
  const b = await startService(t, { port: a.port, mcpSessions: true, stream: true, protocolVersion: '2025-06-18' });
  run.child.stdin.write(initLine(6));
  await untilMessage(run, (x) => x.id === 6, 'initialize with the replacement');
  const gets = () => b.requests.filter((r) => r.url === '/mcp' && r.method === 'GET');
  await within((async () => { while (gets().length === 0) await pause(10); })(), 5000, 'the replacement\'s event stream');
  await pause(100);
  b.notify({ jsonrpc: '2.0', method: 'notifications/message', params: { level: 'info', data: 'before-late' } });
  await untilMessage(run, (x) => x.params?.data === 'before-late', 'a notification before the late answer');
  held.release((body) => ({ jsonrpc: '2.0', id: JSON.parse(body).id, result: { protocolVersion: '2025-03-26', capabilities: {}, serverInfo: { name: 'old', version: '0' } } }));
  await untilMessage(run, (x) => x.id === 5, 'an answer for the superseded initialize');
  await pause(200);
  b.notify({ jsonrpc: '2.0', method: 'notifications/message', params: { level: 'info', data: 'after-late' } });
  await untilMessage(run, (x) => x.params?.data === 'after-late', 'a notification after the late answer');
  assert.equal(gets().length, 1, 'the stream was not replaced');
  assert.notEqual(gets()[0].closed, true, 'nor closed');
  run.child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id: 7, method: 'tools/call', params: { name: 'justsearch_ingest', arguments: { paths: ['/x'] } } })}\n`);
  await untilMessage(run, (x) => x.id === 7, 'a call after it');
  assert.equal(run.messages.find((x) => x.id === 7).result?.content?.[0]?.text, `tools/call by ${b.instanceId}`, run.stderr);
  assert.ok(b.requests.every((r) => r.headers['mcp-protocol-version'] !== '2025-03-26'), 'the old version never reaches the replacement');
  run.child.stdin.end();
  await within(run.exited, 7000, 'the bridge exits once stdin closes');
});


/**
 * The bundle's Engine on a pin restarts as B; call 2 sets off a recovery whose initialize B holds.
 * B then stops listening with that request in flight, C binds the pin, and call 3 is sent.
 */
async function recoveryHeldWhileReplaced(t) {
  const root = tempRoot(t);
  const m = machine(root);
  const a = await startService(t, { mcpSessions: true });
  const run = startPinnedClient('bundle', m, root, a.port);
  await untilMessage(run, (x) => x.id === 1, 'initialize');
  run.child.stdin.write(INITIALIZED_LINE);
  await handshakeDone(a);
  await a.close();
  await pause(300); // the idle bridge handles the close
  const b = await startService(t, { port: a.port, mcpSessions: true });
  const held = b.hold('/mcp', { rpc: 'initialize' });
  run.child.stdin.write(callLine(2)); // refused on B's connection before writing: the bridge recovers
  await within(held.arrived, 10000, 'the recovery initialize at B');
  await b.dropAllBut(held.socket);
  const c = await startService(t, { port: a.port, mcpSessions: true });
  run.child.stdin.write(callLine(3));
  const posts = (service) => service.requests.filter((r) => r.url === '/mcp' && r.method === 'POST').map((r) => JSON.parse(r.body).method);
  const assertC = () => {
    const answer = run.messages.find((x) => x.id === 3);
    assert.equal(answer?.result?.content?.[0]?.text, `tools/call by ${c.instanceId}`, `${JSON.stringify(answer)}\n${run.stderr}`);
    assert.deepEqual(posts(c), ['initialize', 'notifications/initialized', 'tools/call'], 'C is initialized before anything else reaches it');
    assert.equal(mcpPosts(c, 'tools/call')[0].headers['mcp-session-id'], `session-${c.instanceId}`);
    assert.ok(run.messages.some((x) => x.id === 2), 'call 2 is answered');
    const sent2 = [a, b, c].flatMap((s) => mcpPosts(s, 'tools/call')).filter((r) => JSON.parse(r.body).id === 2);
    assert.ok(sent2.length <= 1, 'call 2 is sent at most once');
  };
  return { run, b, c, held, posts, assertC };
}

test('P5 (bundle): a recovery initialize is held by the restarted Engine, which then gives way to another — a concurrent call waits for an initialized session and is sent only after the new Engine is initialized', async (t) => {
  const { run, c, held, posts, assertC } = await recoveryHeldWhileReplaced(t);
  await pause(500);
  assert.deepEqual(posts(c), [], `nothing reaches C while the initialize is unanswered\n${run.stderr}`);
  // B answers last, on its way out; the identity that answered is not the one the next connection reaches.
  held.release((body) => ({ jsonrpc: '2.0', id: JSON.parse(body).id, result: { protocolVersion: '2025-06-18', capabilities: {}, serverInfo: { name: 'B', version: '0' } } }));
  await untilMessage(run, (x) => x.id === 3, 'call 3');
  assertC();
  run.child.stdin.end();
  await within(run.exited, 7000, 'the bridge exits once stdin closes');
});

test('P5 (bundle): a recovery initialize is never answered — the session setup is bounded at 10 s, and a call waiting for it then goes to the Engine now on the pin, after initializing it', async (t) => {
  const { run, assertC } = await recoveryHeldWhileReplaced(t);
  await within((async () => { while (!run.messages.some((x) => x.id === 3)) await pause(20); })(), 20000, `call 3\n${run.stderr}`);
  assertC();
  run.child.stdin.end();
  await within(run.exited, 7000, 'the bridge exits once stdin closes');
});

test('P5 (bundle): an initialize answered with an error leaves no session — the next call is preceded by a new initialize', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const a = await startService(t, { mcpSessions: true, holdUrl: '/mcp' });
  const run = startPinnedClient('bundle', m, root, a.port);
  await within(a.arrived, 10000, 'the client initialize');
  a.release({ jsonrpc: '2.0', id: 1, error: { code: -32603, message: 'not now' } });
  await untilMessage(run, (x) => x.id === 1, 'the initialize answer');
  assert.equal(run.messages.find((x) => x.id === 1).error?.message, 'not now');
  run.child.stdin.write(callLine(2));
  await untilMessage(run, (x) => x.id === 2, 'call 2');
  assert.equal(run.messages.find((x) => x.id === 2).result?.content?.[0]?.text, `tools/call by ${a.instanceId}`, run.stderr);
  const methods = a.requests.filter((r) => r.url === '/mcp' && r.method === 'POST').map((r) => JSON.parse(r.body).method);
  assert.deepEqual(methods, ['initialize', 'initialize', 'notifications/initialized', 'tools/call']);
  run.child.stdin.end();
  await within(run.exited, 7000, 'the bridge exits once stdin closes');
});

test('P5 (bundle): a call before any initialize is refused and not sent', async (t) => {
  const root = tempRoot(t);
  const m = machine(root);
  const a = await startService(t, { mcpSessions: true });
  const run = startPinnedClient('bundle', m, root, a.port, {}, callLine(1));
  await untilMessage(run, (x) => x.id === 1, 'the call answer');
  assert.match(run.messages.find((x) => x.id === 1).error?.message ?? '', /has not sent initialize/, run.stderr);
  assert.deepEqual(a.requests.filter((r) => r.url === '/mcp'), [], 'nothing reaches /mcp');
  run.child.stdin.end();
  await within(run.exited, 7000, 'the bridge exits once stdin closes');
});

for (const client of ['production MCP', 'bundle']) {
  test(`P3/P7 (${client}): a pin no manifest names — the Engine is replaced by one without a token endpoint; a call that needs a token is refused and nothing reaches it`, async (t) => {
    const root = tempRoot(t);
    const m = machine(root);
    const first = await startService(t, { token: 'token-first', mcpSessions: true });
    let second = null;
    let answer;
    if (client === 'production MCP') {
      const prod = await startProdMcp(t, m, PINS['--port'](first.port));
      await first.close();
      await pause(300); // the idle client handles the close
      second = await startService(t, { port: first.port, noToken: true, mcpSessions: true });
      answer = await prod.call('justsearch_ingest', { paths: [path.join(root, 'doc.txt')] }); // the first call
      assert.match(answer, /NO_TOKEN/, `${answer}\n${prod.stderr}`);
    } else {
      const session = await bridgeInteractive({ ...m, env: { ...m.env, JUSTSEARCH_API_PORT: String(first.port) } }, bundle(root), async () => {
        await handshakeDone(first);
        await first.close();
        await pause(300); // the idle bridge handles the close
        second = await startService(t, { port: first.port, noToken: true, mcpSessions: true });
      });
      answer = session.messages.find((x) => x.id === 2);
      assert.match(answer?.error?.message ?? '', /offers no session token/, `${JSON.stringify(answer)}\n${session.stderr}`);
    }
    const written = second.requests.filter((r) => !['/api/mcp/token', '/api/runtime/live', '/api/health', '/api/status'].includes(r.url)).map((r) => `${r.method} ${r.url}`);
    assert.deepEqual(written, [], 'nothing that needs a token reaches the Engine without one');
    assert.ok(second.requests.every((r) => r.headers['x-justsearch-session'] === undefined), 'nor any token');
  });
}

for (const kind of Object.keys(PINS)) {
  test(`P7 (${kind}): production MCP pinned to a manifest Engine — the Engine restarts on the port with a new manifest and token; the next call is proven there and succeeds, sent once, with only the new token`, async (t) => {
    const root = tempRoot(t);
    const m = machine(root);
    const first = await startService(t, { token: 'token-first', requireToken: true });
    writeManifest(m.desktop, manifestFor(first));
    const prod = await startProdMcp(t, m, PINS[kind](first.port));
    const ingest = { paths: [path.join(root, 'doc.txt')] };
    first.lastAnswers(); // no idle connection is left to the first Engine
    assert.match(await prod.call('justsearch_ingest', ingest), new RegExp(`ingest by ${first.instanceId}`), prod.stderr);
    await first.close();
    const second = await startService(t, { port: first.port, token: 'token-second', requireToken: true });
    writeManifest(m.desktop, manifestFor(second));
    const next = await prod.call('justsearch_ingest', ingest);
    assert.match(next, new RegExp(`ingest by ${second.instanceId}`), `${next}\n${prod.stderr}`);
    const tokens = second.requests.map((r) => r.headers['x-justsearch-session']).filter(Boolean);
    assert.ok(tokens.length > 0 && tokens.every((x) => x === 'token-second'), `only the new token reaches the restarted Engine: ${tokens}`);
    assert.equal(second.requests.filter((r) => r.url === '/api/knowledge/ingest').length, 1, 'the call is sent once');
  });
}

for (const tokens of ['empty', 'repeated']) {
  test(`P5 (bundle, ${tokens} token): a pin no manifest names — the Engine stops and another one binds the port with the ${tokens === 'empty' ? 'same empty (dev mode)' : 'same'} token; the old Mcp-Session-Id never reaches it, and the bridge re-initializes there before the call`, async (t) => {
    const root = tempRoot(t);
    const m = machine(root);
    const token = tokens === 'empty' ? '' : 'token-same';
    const first = await startService(t, { token, mcpSessions: true, stream: true });
    let second = null;
    const session = await bridgeInteractive({ ...m, env: { ...m.env, JUSTSEARCH_API_PORT: String(first.port) } }, bundle(root), async () => {
      await handshakeDone(first, { stream: true });
      await first.close();
      await until(() => first.stats.open === 0, 10000, 'the first Engine\'s connections closing');
      await pause(300); // the idle bridge handles the close: a call written on a dropped connection is not sent again
      second = await startService(t, { port: first.port, token, mcpSessions: true, stream: true });
    });
    const oldSession = `session-${first.instanceId}`;
    const carried = second.requests.filter((r) => r.headers['mcp-session-id'] === oldSession).map((r) => `${r.method} ${r.url}`);
    assert.deepEqual(carried, [], `the old session stays with the Engine that opened it\n${session.stderr}`);
    const posts = second.requests.filter((r) => r.url === '/mcp' && r.method === 'POST').map((r) => JSON.parse(r.body).method);
    assert.deepEqual(posts, ['initialize', 'notifications/initialized', 'tools/call'], 're-initialized there, then the call, once');
    const call = session.messages.find((x) => x.id === 2);
    assert.equal(call?.result?.content?.[0]?.text, `tools/call by ${second.instanceId}`, `${JSON.stringify(call)}\n${session.stderr}`);
  });
}

for (const status of [400, 404]) {
  test(`P5 (bundle): an Engine that answers each connection its own token and closes after each POST rejects a call with ${status} — the bridge re-initializes and the call succeeds in the new session on the same Engine`, async (t) => {
    const root = tempRoot(t);
    const m = machine(root);
    const engine = await startService(t, { token: 'token-conn', tokenPerConnection: true, requireToken: true, mcpSessions: true, closeAfterPost: true });
    const session = await bridgeInteractive({ ...m, env: { ...m.env, JUSTSEARCH_API_PORT: String(engine.port) } }, bundle(root), async () => {
      await handshakeDone(engine);
      engine.rejectNext(status);
    });
    const call = session.messages.find((x) => x.id === 2);
    assert.equal(call?.result?.content?.[0]?.text, `tools/call by ${engine.instanceId}`, `${JSON.stringify(call)}\n${session.stderr}`);
    const posts = engine.requests.filter((r) => r.url === '/mcp' && r.method === 'POST').map((r) => JSON.parse(r.body).method);
    assert.deepEqual(posts, ['initialize', 'notifications/initialized', 'tools/call', 'initialize', 'notifications/initialized', 'tools/call'], `rejected (${status}), re-initialized, sent again once`);
    for (const r of engine.requests.filter((x) => x.headers['x-justsearch-session'])) {
      assert.equal(r.headers['x-justsearch-session'], `token-conn-${r.conn}`, `${r.method} ${r.url} carries its own connection's token`);
    }
  });
}

test('P3/P7: a connection idle past the agent\'s retirement is not reused — the Engine\'s idle expiry crossing a request never costs a call (production MCP and bundle; the 25 s / 30 s boundary scaled down)', async (t) => {
  // Scaled from production (retire at 25 s, before the Engine's expiry; the call after a longer
  // gap): the agents retire idle connections at 250 ms, the fixture drops (unanswered) any
  // request arriving on a connection idle 300 ms or more, and the second call comes 400 ms
  // after the first.
  const retireMs = 250;
  const expireIdleMs = 300;
  const idleMs = 400;
  const root = tempRoot(t);
  const m = machine(root);
  m.env.JUSTSEARCH_DISCOVERY_IDLE_RETIRE_MS = String(retireMs);
  const prodEngine = await startService(t, { expireIdleMs });
  const bridgeEngine = await startService(t, { expireIdleMs });
  const ingest = { paths: [path.join(root, 'doc.txt')] };
  const prodRun = (async () => {
    const prod = await startProdMcp(t, m, PINS['--port'](prodEngine.port));
    assert.match(await prod.call('justsearch_ingest', ingest), new RegExp(`ingest by ${prodEngine.instanceId}`), prod.stderr);
    await pause(idleMs);
    return { result: await prod.call('justsearch_ingest', ingest), stderr: prod.stderr };
  })();
  const bridgeRun = bridgeInteractive({ ...m, env: { ...m.env, JUSTSEARCH_API_PORT: String(bridgeEngine.port) } }, bundle(root), async () => {
    await handshakeDone(bridgeEngine);
    await pause(idleMs);
  });
  const [prod, session] = await Promise.all([prodRun, bridgeRun]);
  const call = session.messages.find((x) => x.id === 2);
  const outcomes = {
    'production MCP': prod.result.includes(`ingest by ${prodEngine.instanceId}`) ? 'answered' : prod.result,
    bundle: call?.result?.content?.[0]?.text === `tools/call by ${bridgeEngine.instanceId}` ? 'answered' : JSON.stringify(call),
  };
  assert.deepEqual(outcomes, { 'production MCP': 'answered', bundle: 'answered' }, `the call after the idle gap\n${prod.stderr}\n${session.stderr}`);
  for (const [client, engine] of [['production MCP', prodEngine], ['bundle', bridgeEngine]]) {
    assert.deepEqual(engine.requests.filter((r) => r.expired).map((r) => `${r.method} ${r.url}`), [], `${client}: nothing was written on a connection past its idle retirement`);
  }
});

test('P3/P7: production retires idle connections at 25 000 ms, below the Engine\'s 30 s idle expiry; the seam is read only in engine-discovery.cjs', () => {
  const { IDLE_RETIRE_MS, idleRetireMs } = require(ENGINE_DISCOVERY);
  assert.equal(IDLE_RETIRE_MS, 25000);
  assert.ok(IDLE_RETIRE_MS < 30000, 'retired before the Engine (Jetty) closes an idle connection at 30 s');
  assert.equal(idleRetireMs({}), 25000);
  assert.equal(idleRetireMs({ JUSTSEARCH_DISCOVERY_IDLE_RETIRE_MS: '' }), 25000);
  assert.equal(idleRetireMs({ JUSTSEARCH_DISCOVERY_IDLE_RETIRE_MS: 'soon' }), 25000);
  assert.equal(idleRetireMs({ JUSTSEARCH_DISCOVERY_IDLE_RETIRE_MS: '250' }), 250);
  const shared = fs.readFileSync(ENGINE_DISCOVERY, 'utf8');
  assert.match(shared, /new http\.Agent\(\{ keepAlive: true, timeout: idleRetireMs\(\) \}\)/, 'every agent is created with the retirement');
  for (const rel of ['scripts/prod/justsearch-mcp/server.mjs', 'scripts/prod/justsearch-mcp/discovery.mjs', 'packaging/mcpb/server/index.js']) {
    const src = fs.readFileSync(path.join(repoRoot, rel), 'utf8');
    assert.ok(!/idleRetireMs|IDLE_RETIRE/.test(src), `${rel} uses the shared retirement`);
  }
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

// A stand-in for the PowerShell start: a child that answers folder after delayMs.
function slowLookup(folder, delayMs, timeoutMs) {
  return { file: process.execPath, args: ['-e', `setTimeout(() => process.stdout.write(${JSON.stringify(folder)}), ${delayMs})`], timeoutMs };
}

test('D1: a Roaming folder lookup that outlasts its bound is a named error among the folders tried, not a silent skip', async () => {
  const { selectEngine, describeTried, roamingAppDataFromOs } = require(ENGINE_DISCOVERY);
  const started = Date.now();
  const { selected, tried } = await selectEngine([
    {
      source: 'desktop app (Roaming folder the OS reports)',
      resolveDataDir: async () => {
        const roaming = await roamingAppDataFromOs(slowLookup('C:\\Users\\x\\AppData\\Roaming', 5000, 200));
        return roaming ? path.win32.join(roaming, 'io.justsearch.shell') : null;
      },
    },
  ]);
  assert.ok(Date.now() - started < 4000, 'the bound, not the slow lookup, ends the wait');
  assert.equal(selected, null);
  assert.equal(tried.length, 1, 'the folder that could not be determined is listed');
  assert.equal(tried[0].status, 'ROAMING_LOOKUP_TIMEOUT');
  assert.match(describeTried(tried)[0], /^desktop app \(Roaming folder the OS reports\): folder unknown \(ROAMING_LOOKUP_TIMEOUT: the Roaming folder lookup \(.+\) did not answer within 200 ms\)$/);
});

test('D1: a Roaming folder lookup slower than the old 10 s bound but inside its own still finds the Engine', async (t) => {
  const root = tempRoot(t);
  const engine = await startService(t);
  const reported = path.join(root, 'Roaming');
  writeManifest(path.join(reported, 'io.justsearch.shell'), manifestFor(engine));
  const { selectEngine, roamingAppDataFromOs, ROAMING_QUERY_TIMEOUT_MS } = require(ENGINE_DISCOVERY);
  // Scaled by a tenth: a 1.1 s lookup (the 10.8 s cold start) against 3 s (the 30 s bound); the
  // old bound would have been 1 s.
  const { selected, tried } = await selectEngine([
    {
      source: 'desktop app (Roaming folder the OS reports)',
      resolveDataDir: async () => path.join(await roamingAppDataFromOs(slowLookup(reported, 1100, ROAMING_QUERY_TIMEOUT_MS / 10)), 'io.justsearch.shell'),
    },
  ]);
  assert.equal(selected?.instanceId, engine.instanceId, JSON.stringify(tried));
});

test('D1: production bounds the Roaming folder lookup at 30 s, three times the PowerShell cold start seen on hosted Windows', () => {
  const { ROAMING_QUERY_TIMEOUT_MS } = require(ENGINE_DISCOVERY);
  assert.equal(ROAMING_QUERY_TIMEOUT_MS, 30000);
  const src = fs.readFileSync(ENGINE_DISCOVERY, 'utf8');
  assert.match(src, /function roamingQuery\(\) \{\n  return \{\n    file: windowsTool\('WindowsPowerShell', 'v1\.0', 'powershell\.exe'\),\n[^}]*?\n    timeoutMs: ROAMING_QUERY_TIMEOUT_MS,\n  \};\n\}/, 'the PowerShell lookup uses that bound');
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
