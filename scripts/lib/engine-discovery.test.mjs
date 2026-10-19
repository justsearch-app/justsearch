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
const ENGINE_DISCOVERY = path.join(repoRoot, 'packaging', 'mcpb', 'server', 'engine-discovery.cjs');
const IS_WINDOWS = process.platform === 'win32';
const IS_LINUX = process.platform === 'linux';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

function tempRoot(t) {
  const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'js-engine-discovery-')));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
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
async function startService(t, { kind = 'engine', pid = process.pid, instanceId, token = 'token-abc', port = 0 } = {}) {
  const id = instanceId || `instance-${process.pid}-${++instanceCounter}`;
  const requests = [];
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      requests.push({ method: req.method, url: req.url, headers: req.headers, body });
      const json = (status, obj) => {
        res.writeHead(status, { 'content-type': 'application/json' });
        res.end(JSON.stringify(obj));
      };
      if (req.url === '/api/health') return json(200, { ok: true });
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
      return json(404, {});
    });
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', resolve);
  });
  t.after(() => new Promise((resolve) => server.close(resolve)));
  return { kind, pid, instanceId: id, port: server.address().port, requests, token };
}

/** A pid that existed and has exited. */
async function deadPid() {
  const child = spawn(process.execPath, ['-e', ''], { stdio: 'ignore' });
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
    const child = spawn(process.execPath, args, { env, cwd, stdio: ['pipe', 'pipe', 'pipe'] });
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
async function prodDiscover(m, { port, envExtra = {} } = {}) {
  const script =
    `import(${JSON.stringify(pathToFileURL(DISCOVERY_MJS).href)})` +
    `.then((d) => d.discover(${port ? `{ port: ${port} }` : ''}))` +
    '.then((r) => { process.stdout.write(JSON.stringify(r)); })' +
    '.catch((e) => { process.stderr.write(e.message); process.exit(3); });';
  const res = await run(['--input-type=module', '-e', script], { env: { ...m.env, ...envExtra }, cwd: m.cwd });
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
    const child = spawn(process.execPath, [entry], { env: { ...m.env, ...envExtra }, cwd: m.cwd, stdio: ['pipe', 'pipe', 'pipe'] });
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
