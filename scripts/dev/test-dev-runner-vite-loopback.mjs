import './lib/test-fixture-processes.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { setTimeout as pause } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

// These tests start real Vite listeners. Queue behind other live checks, including
// in CI; a lease timeout is still a failure, never a skipped/passing check.
if (process.platform === 'linux' && !process.argv.includes('--locked')) {
  fs.mkdirSync('/tmp/js-linux-port', { recursive: true });
  const result = spawnSync('flock', ['-w', '600', '/tmp/js-linux-port/devstack.lock',
    process.execPath, fileURLToPath(import.meta.url), '--locked'], { stdio: 'inherit' });
  if (result.status !== 0) console.error('Vite checks failed or the live-stack lease was unavailable');
  process.exit(result.status ?? 1);
}

const root = fileURLToPath(new URL('../../', import.meta.url));
const uiWebDir = path.join(root, 'modules/ui-web');
const runner = fs.readFileSync(path.join(root, 'scripts/dev/dev-runner.cjs'), 'utf8');
const spawnBody = runner.match(/const spawnFrontend = \(\) => \{[\s\S]+?\n  \};/)?.[0];
assert.ok(spawnBody, 'exercise the production frontend launch');
function frontend(command = null, uiPort = 5173) {
  return vm.runInNewContext(`${spawnBody}\nspawnFrontend()`, {
    harnessFrontendCommand: command, repoRoot: root, uiWebDir, path, process, uiPort,
  });
}
const launch = frontend();
const hostIndex = launch.args.indexOf('--host');
const launchHost = hostIndex < 0 ? undefined
  : launch.args[hostIndex + 1]?.startsWith('--') ? true : launch.args[hostIndex + 1];

test('V1/V4: both dev-stack launchers bind IPv4 loopback and print its literal URL', () => {
  assert.equal(launchHost, '127.0.0.1');
  const urlLine = runner.match(/const uiUrl = ([^;]+);/)?.[1];
  assert.equal(vm.runInNewContext(urlLine, { uiPort: 5173 }), 'http://127.0.0.1:5173');
  const devAll = fs.readFileSync(path.join(uiWebDir, 'scripts/dev-all.cjs'), 'utf8');
  const body = devAll.match(/function startFrontend\(port\) \{[\s\S]+?\n\}/)?.[0];
  let ownedArgs;
  vm.runInNewContext(`(${body})(12345)`, {
    path, uiWebDir, process, startOwnedChild: (_command, args) => {
      ownedArgs = args;
      return { on() {} };
    },
  });
  assert.equal(ownedArgs[ownedArgs.indexOf('--host') + 1], '127.0.0.1');
  assert.match(devAll, /Frontend: http:\/\/127\.0\.0\.1:5173/);
});

test('V5: harness frontend command remains an argv-preserving override', () => {
  const fixture = frontend(JSON.stringify(['fixture-node', 'a file.mjs', '--fixture']));
  assert.equal(fixture.command, 'fixture-node');
  assert.deepEqual(Array.from(fixture.args), ['a file.mjs', '--fixture']);
  assert.equal(fixture.shell, false);
});

let backendHits = 0;
const streams = new Set();
const backend = http.createServer((req, res) => {
  backendHits++;
  res.setHeader('x-backend-host', req.headers.host);
  if (req.url === '/api/events') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    res.write('data: loopback-stream\n\n');
    // Deliberately stay open: the client must receive data without waiting for EOF.
    streams.add(res);
    res.on('close', () => streams.delete(res));
  } else {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ path: req.url }));
  }
});
await new Promise(resolve => backend.listen(0, '127.0.0.1', resolve));

function request(port, { hostname = '127.0.0.1', host = `${hostname}:${port}`, route = '/', stream = false } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.get({ hostname, port, path: route,
      headers: { Host: host, Accept: stream ? 'text/event-stream' : '*/*' }, setHost: false, agent: false,
      autoSelectFamily: true }, res => {
      let body = '';
      res.on('data', chunk => {
        body += chunk;
        if (stream && body.includes('\n\n')) {
          resolve({ status: res.statusCode, headers: res.headers, body });
          res.destroy();
        }
      });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body }));
      res.on('error', reject);
    });
    req.setTimeout(5000, () => req.destroy(new Error('request timed out')));
    req.on('error', reject);
  });
}

async function withVite(host, check) {
  const reservation = net.createServer();
  await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve));
  const port = reservation.address().port;
  await new Promise(resolve => reservation.close(resolve));
  const args = host === 'runner' ? frontend(null, port).args
    : [path.join(uiWebDir, 'node_modules/vite/bin/vite.js'), '--port', String(port), '--strictPort',
      ...(host === undefined ? [] : ['--host', host])];
  const child = spawn(process.execPath, [...args, '--clearScreen', 'false'], {
    cwd: uiWebDir, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true,
    env: { ...process.env, NO_COLOR: '1',
      VITE_JUSTSEARCH_API_PORT: String(backend.address().port),
      VITE_API_PORT: String(backend.address().port) },
  });
  let output = '';
  child.stdout.on('data', data => { output += data; });
  child.stderr.on('data', data => { output += data; });
  const exited = once(child, 'exit');
  const deadline = Date.now() + 30000;
  try {
    assert.ok(child.recordedIdentity, 'Vite has a spawn-recorded process identity');
    for (;;) {
      assert.equal(child.exitCode, null, `Vite exited during startup: ${output}`);
      assert.equal(child.signalCode, null, `Vite received a signal during startup: ${output}`);
      if (/Local:/.test(output)) break;
      assert.ok(Date.now() < deadline, `Vite startup timed out: ${output}`);
      await pause(25);
    }
    console.log(`Vite owned process: ${JSON.stringify(child.recordedIdentity)}; port=${port}`);
    await check(port);
  } finally {
    // End this fixture's upstream SSE responses before asking Vite to shut down.
    // A downstream disconnect need not close the proxy's upstream connection.
    for (const response of streams) response.end();
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGTERM');
    let timeout;
    try {
      await Promise.race([exited, new Promise((_, reject) => {
        timeout = setTimeout(() => reject(new Error('Vite did not exit after identity-bound stop')), 10000);
      })]);
    } finally {
      clearTimeout(timeout);
      if (child.exitCode === null && child.signalCode === null) {
        child.kill('SIGKILL');
        await exited;
      }
    }
  }
}

function listeners(port) {
  const result = process.platform === 'win32'
    ? spawnSync('netstat', ['-an', '-p', 'tcp'], { encoding: 'utf8', windowsHide: true })
    : spawnSync('ss', ['-H', '-ltn', `sport = :${port}`], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr || result.error?.message);
  return result.stdout.trim().split('\n').filter(Boolean).map(line => line.trim().split(/\s+/))
    .filter(fields => process.platform !== 'win32' || (fields[1]?.endsWith(`:${port}`) && fields[3] === 'LISTENING'))
    .map(fields => fields[process.platform === 'win32' ? 1 : 3]);
}

try {
  await test('V1: real Vite default and dev-runner listeners are loopback-only; LAN refuses', async () => {
    for (const host of ['runner', undefined]) {
      await withVite(host, async port => {
        const bound = listeners(port);
        const lan = Object.values(os.networkInterfaces()).flat().filter(address =>
          address && !address.internal && address.family === 'IPv4');
        const lanResults = [];
        for (const address of lan) {
          try { lanResults.push({ address: address.address, status: (await request(port, { hostname: address.address })).status }); }
          catch (error) { lanResults.push({ address: address.address, error: error.code }); }
        }
        console.log(`V1 listeners=${bound.join(',')}; LAN probes=${JSON.stringify(lanResults)}`);
        assert.deepEqual(bound, [`127.0.0.1:${port}`], 'no wildcard or LAN listener on the dev UI port');
        assert.deepEqual(lanResults, lan.map(address => ({ address: address.address, error: 'ECONNREFUSED' })));
        assert.equal((await request(port)).status, 200);
      });
    }
  });

  await test('V2: a wider-bound real Vite refuses non-local Host before UI and both proxies', async () => {
    await withVite('0.0.0.0', async port => {
      const hits = backendHits;
      const responses = [];
      for (const host of ['remote.example', 'localhost.remote.example', '127.0.0.1.remote.example', '192.168.1.2', '[::]', '']) {
        for (const route of ['/', '/api/check', '/.well-known/justsearch/check']) {
          const response = await request(port, { host, route });
          responses.push({ host, route, status: response.status });
        }
      }
      console.log(`V2 Host/route results=${JSON.stringify(responses)}`);
      assert.deepEqual(responses, responses.map(response => ({ ...response, status: 403 })));
      assert.equal(backendHits, hits, 'refused requests never reach the backend');
    });
  });

  await test('V3/V4: localhost and printed IPv4 URL serve UI, both proxies and streaming SSE', async () => {
    await withVite('127.0.0.1', async port => {
      for (const hostname of ['127.0.0.1', 'localhost']) {
        const ui = await request(port, { hostname });
        assert.equal(ui.status, 200);
        assert.match(ui.body, /<html/);
        for (const route of ['/api/check?query=1', '/.well-known/justsearch/check']) {
          const response = await request(port, { hostname, route });
          assert.equal(response.status, 200);
          assert.equal(JSON.parse(response.body).path, route);
          assert.equal(response.headers['x-backend-host'], `127.0.0.1:${backend.address().port}`);
        }
        const events = await request(port, { hostname, route: '/api/events', stream: true });
        assert.equal(events.status, 200);
        assert.match(events.headers['content-type'], /text\/event-stream/);
        assert.equal(events.body, 'data: loopback-stream\n\n');
        assert.ok(streams.size > 0, 'SSE data arrives while the backend stream is still open');
      }
    });
  });
} finally {
  backend.closeAllConnections();
  await new Promise(resolve => backend.close(resolve));
}
