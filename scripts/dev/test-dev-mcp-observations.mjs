#!/usr/bin/env node
/** Pure/loopback tests for tempdoc 925's typed filesystem and socket observations. */

import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';

import {
  FILE_OBSERVATION,
  PROBE_OBSERVATION,
  classifyInferenceOrphan,
  classifyOptionalFileError,
  observeOptionalJsonFile,
  observeRecordedInference,
  probeLoopbackHttpStatus,
  probeStatusCodeOrThrow,
} from './justsearch-dev-mcp/observations.mjs';
import { createRequire } from 'node:module';

const { recordedLlamaServers, probeForeignRuns, httpGetStatusCode } =
  createRequire(import.meta.url)('./lib/process-record.cjs');

process.exitCode = 1;
let passed = 0;

async function check(label, fn) {
  await fn();
  passed += 1;
  console.log(`  PASS  ${label}`);
}

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve(server.address().port));
  });
}

function close(server) {
  return new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
}

const root = await fsp.mkdtemp(path.join(os.tmpdir(), 'justsearch-dev-observations-'));
try {
  await fsp.mkdir(path.join(root, 'tmp'), { recursive: true });
  await fsp.writeFile(path.join(root, 'tmp', 'valid.json'), '{"runId":"run-1"}\n', 'utf8');
  await fsp.writeFile(path.join(root, 'tmp', 'invalid.json'), '{not-json\n', 'utf8');
  await fsp.writeFile(path.join(root, 'tmp', 'scalar.json'), '42\n', 'utf8');
  await fsp.writeFile(path.join(root, 'tmp', 'large.json'), '{"payload":"123456"}\n', 'utf8');

  await check('valid JSON object → PRESENT', async () => {
    const result = await observeOptionalJsonFile({ repoRoot: root, relPosix: 'tmp/valid.json' });
    assert.equal(result.state, FILE_OBSERVATION.PRESENT);
    assert.equal(result.value.runId, 'run-1');
  });
  await check('missing file → ABSENT', async () => {
    const result = await observeOptionalJsonFile({ repoRoot: root, relPosix: 'tmp/missing.json' });
    assert.equal(result.state, FILE_OBSERVATION.ABSENT);
  });
  await check('malformed JSON → INVALID', async () => {
    const result = await observeOptionalJsonFile({ repoRoot: root, relPosix: 'tmp/invalid.json' });
    assert.equal(result.state, FILE_OBSERVATION.INVALID);
  });
  await check('valid non-object JSON → INVALID', async () => {
    const result = await observeOptionalJsonFile({ repoRoot: root, relPosix: 'tmp/scalar.json' });
    assert.equal(result.state, FILE_OBSERVATION.INVALID);
    assert.equal(result.error.code, 'DEV_MCP_FILE_INVALID_JSON_SHAPE');
  });
  await check('oversized JSON → INVALID', async () => {
    const result = await observeOptionalJsonFile({ repoRoot: root, relPosix: 'tmp/large.json', maxBytes: 4 });
    assert.equal(result.state, FILE_OBSERVATION.INVALID);
    assert.equal(result.error.code, 'DEV_MCP_FILE_TOO_LARGE');
  });
  await check('symlink refusal → INVALID (injected for Windows portability)', async () => {
    const error = Object.assign(new Error('symlink'), { code: 'DEV_MCP_FILE_SYMLINK' });
    const result = await observeOptionalJsonFile({ read: async () => { throw error; } });
    assert.equal(result.state, FILE_OBSERVATION.INVALID);
  });
  await check('permission denial → UNREADABLE', async () => {
    const error = Object.assign(new Error('denied'), { code: 'EACCES' });
    const result = await observeOptionalJsonFile({ read: async () => { throw error; } });
    assert.equal(result.state, FILE_OBSERVATION.UNREADABLE);
  });
  await check('ENOTDIR is INVALID, never invented absence', async () => {
    const result = classifyOptionalFileError(Object.assign(new Error('parent is not a directory'), { code: 'ENOTDIR' }));
    assert.equal(result.state, FILE_OBSERVATION.INVALID);
  });

  const reachableServer = http.createServer((_req, res) => {
    res.writeHead(503);
    res.end('not ready');
  });
  const reachablePort = await listen(reachableServer);
  try {
    await check('reachable non-200 listener → REACHABLE with status', async () => {
      const result = await probeLoopbackHttpStatus(`http://127.0.0.1:${reachablePort}/health`, { timeoutMs: 500 });
      assert.deepEqual(result, { state: PROBE_OBSERVATION.REACHABLE, statusCode: 503 });
    });
  } finally {
    await close(reachableServer);
  }

  const closedCandidate = net.createServer();
  const refusedPort = await listen(closedCandidate);
  await close(closedCandidate);
  await check('closed local port → REFUSED', async () => {
    const result = await probeLoopbackHttpStatus(`http://127.0.0.1:${refusedPort}/health`, { timeoutMs: 500 });
    assert.equal(result.state, PROBE_OBSERVATION.REFUSED);
  });
  await check('legacy foreign-run adapter maps only REFUSED to null', async () => {
    assert.equal(await probeStatusCodeOrThrow(`http://127.0.0.1:${refusedPort}/health`, 500), null);
  });

  const sockets = new Set();
  const hangingServer = net.createServer((socket) => {
    sockets.add(socket);
    socket.once('close', () => sockets.delete(socket));
  });
  const hangingPort = await listen(hangingServer);
  try {
    await check('accepted socket with no response → TIMED_OUT', async () => {
      const result = await probeLoopbackHttpStatus(`http://127.0.0.1:${hangingPort}/health`, { timeoutMs: 50 });
      assert.equal(result.state, PROBE_OBSERVATION.TIMED_OUT);
    });
    await check('legacy foreign-run adapter throws on timeout so caller returns unknown', async () => {
      await assert.rejects(
        () => probeStatusCodeOrThrow(`http://127.0.0.1:${hangingPort}/health`, 50),
        /timeout/,
      );
    });
  } finally {
    for (const socket of sockets) socket.destroy();
    await close(hangingServer);
  }

  await check('invalid/non-loopback URL → ERROR', async () => {
    const result = await probeLoopbackHttpStatus('https://example.com/health');
    assert.equal(result.state, PROBE_OBSERVATION.ERROR);
  });
  await check('unexpected request failure → ERROR', async () => {
    const result = await probeLoopbackHttpStatus('http://127.0.0.1:1/health', {
      request: () => { throw Object.assign(new Error('synthetic failure'), { code: 'EIO' }); },
    });
    assert.equal(result.state, PROBE_OBSERVATION.ERROR);
    assert.equal(result.error.code, 'EIO');
  });
  await check('loopback HTTP without an explicit port uses port 80', async () => {
    let requestedPort;
    const result = await probeLoopbackHttpStatus('http://localhost/health', {
      request: (options, onResponse) => {
        requestedPort = options.port;
        const request = {
          on() { return request; },
          end() { onResponse({ statusCode: 204, resume() {} }); },
        };
        return request;
      },
    });
    assert.equal(requestedPort, 80);
    assert.equal(result.state, PROBE_OBSERVATION.REACHABLE);
  });

  await check('reachable inference with no run has unknown process identity', async () => {
    assert.equal(classifyInferenceOrphan({
      runState: 'ABSENT',
      inferenceObservation: { state: PROBE_OBSERVATION.REACHABLE },
    }), null);
  });
  await check('reachable inference beside an unhealthy reachable API has unknown ownership', async () => {
    assert.equal(classifyInferenceOrphan({
      runState: 'ACTIVE',
      pidsAlive: false,
      apiObservation: { state: PROBE_OBSERVATION.REACHABLE, statusCode: 503 },
      inferenceObservation: { state: PROBE_OBSERVATION.REACHABLE },
    }), null);
  });
  await check('reachable inference after a dead active run still has unknown process identity', async () => {
    assert.equal(classifyInferenceOrphan({
      runState: 'ACTIVE',
      pidsAlive: false,
      apiObservation: { state: PROBE_OBSERVATION.REFUSED },
      inferenceObservation: { state: PROBE_OBSERVATION.REACHABLE },
    }), null);
  });
  await check('reachable inference with an unreadable run record has unknown ownership', async () => {
    assert.equal(classifyInferenceOrphan({
      runState: 'UNKNOWN',
      inferenceObservation: { state: PROBE_OBSERVATION.REACHABLE },
    }), null);
  });
  await check('refused inference port proves there is no orphan despite unknown run state', async () => {
    assert.equal(classifyInferenceOrphan({
      runState: 'UNKNOWN',
      inferenceObservation: { state: PROBE_OBSERVATION.REFUSED },
    }), false);
  });

  // ADR-0051 decision 2: llama-server binds a fresh port per launch, so the dev tools find a
  // left-over one through the port recorded in the runtime manifest, never a fixed port.
  await check('a recorded llama-server on its own fresh port is found and reported until it stops', async () => {
    // Connection: close, so no pooled keep-alive socket outlives the server and turns the
    // after-stop probe into a reset instead of a refusal.
    const llama = http.createServer((_req, res) => {
      res.writeHead(200, { Connection: 'close' });
      res.end('{"status":"ok"}');
    });
    const port = await listen(llama);
    const dataDir = path.join(root, 'recorded-llama');
    await fsp.mkdir(path.join(dataDir, 'runtime'), { recursive: true });
    await fsp.writeFile(path.join(dataDir, 'runtime', 'manifest.json'), JSON.stringify({
      children: [
        { id: 'llama', kind: 'LLAMA_SERVER', pid: 4242, endpoint: `http://127.0.0.1:${port}` },
        { id: 'extraction', kind: 'EXTRACTION', pid: 4343, endpoint: 'http://127.0.0.1:1' },
        { id: 'remote', kind: 'LLAMA_SERVER', pid: 4444, endpoint: 'http://192.0.2.1:9000' },
      ],
    }));
    const missing = path.join(root, 'no-manifest-here');
    let recorded;
    try {
      recorded = await recordedLlamaServers([dataDir, missing, dataDir]);
      assert.deepEqual(recorded, [{ port, pid: 4242, dataDir }],
        'only the loopback LLAMA_SERVER record counts; a missing manifest contributes nothing');

      const live = await observeRecordedInference({ recorded });
      assert.equal(live.state, PROBE_OBSERVATION.REACHABLE);
      assert.equal(live.port, port);
      assert.equal(live.pid, 4242);

      const foreign = await probeForeignRuns({
        enabled: true, hasActiveRun: false, ports: [],
        inferencePorts: recorded.map((r) => r.port), probe: httpGetStatusCode,
      });
      assert.deepEqual(foreign, [{
        port, kind: 'inference', probePath: '/health', attribution: 'unowned', source: 'observed',
      }]);
    } finally {
      await close(llama);
    }
    const gone = await observeRecordedInference({ recorded });
    assert.equal(gone.state, PROBE_OBSERVATION.REFUSED, JSON.stringify(gone));
    assert.deepEqual(gone.recordedPorts, [port]);
  });
  await check('nothing recorded is a looked-and-found-nothing REFUSED, probing no fixed port', async () => {
    const probed = [];
    const observation = await observeRecordedInference({
      recorded: [],
      probe: async (url) => { probed.push(url); return { state: PROBE_OBSERVATION.REACHABLE }; },
    });
    assert.deepEqual(probed, []);
    assert.equal(observation.state, PROBE_OBSERVATION.REFUSED);
    assert.deepEqual(observation.recordedPorts, []);
    const seen = [];
    await probeForeignRuns({
      enabled: true, hasActiveRun: false, ports: [],
      probe: async (url) => { seen.push(url); return null; },
    });
    assert.deepEqual(seen, [], 'no inference port is probed unless one is recorded');
  });
  await check('an unknown probe of a recorded port stays unknown, naming the port', async () => {
    const observation = await observeRecordedInference({
      recorded: [{ port: 41001, pid: 1 }, { port: 41002, pid: 2 }],
      probe: async (url) => (new URL(url).port === '41001'
        ? { state: PROBE_OBSERVATION.TIMED_OUT }
        : { state: PROBE_OBSERVATION.REFUSED }),
    });
    assert.equal(observation.state, PROBE_OBSERVATION.TIMED_OUT);
    assert.equal(observation.port, 41001);
  });

  console.log(`test-dev-mcp-observations: ${passed} passed`);
  process.exitCode = 0;
} finally {
  await fsp.rm(root, { recursive: true, force: true });
}
