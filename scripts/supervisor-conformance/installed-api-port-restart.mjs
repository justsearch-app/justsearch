import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

import { createOperationKey } from '../../modules/ui-web/src/api/operationKey.ts';

const guestUser = os.userInfo();
if (process.platform !== 'win32'
    || guestUser.username.toLowerCase() !== 'wdagutilityaccount') {
  throw new Error('REFUSING TO RUN: installed API-port proof requires Windows Sandbox '
    + 'WDAGUtilityAccount before any filesystem or process action');
}

const installedExeValue = process.env.JUSTSEARCH_INSTALLED_EXE?.trim();
if (!installedExeValue) {
  throw new Error('JUSTSEARCH_INSTALLED_EXE is required for the installed Tauri API-port proof');
}
const installedExe = path.resolve(installedExeValue);
if (!fs.statSync(installedExe, { throwIfNoEntry: false })?.isFile()) {
  throw new Error(`JUSTSEARCH_INSTALLED_EXE is not a file: ${installedExe}`);
}
const workValue = process.env.JUSTSEARCH_INSTALLED_API_PORT_WORK?.trim();
if (!workValue) {
  throw new Error('JUSTSEARCH_INSTALLED_API_PORT_WORK is required');
}
const work = path.resolve(workValue);
const knownAppDataValue = process.env.JUSTSEARCH_INSTALLED_KNOWN_APPDATA?.trim();
if (!knownAppDataValue) {
  throw new Error('JUSTSEARCH_INSTALLED_KNOWN_APPDATA is required');
}
const knownAppData = path.resolve(knownAppDataValue);
const data = path.join(knownAppData, 'io.justsearch.shell');
const runtime = path.join(data, 'runtime');
fs.mkdirSync(runtime, { recursive: true });

function requireThat(condition, message) {
  if (!condition) throw new Error(message);
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitFor(label, timeoutMs, probe) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const value = await probe();
      if (value) return value;
    } catch (error) {
      lastError = error;
    }
    await delay(100);
  }
  throw new Error(`timeout waiting for ${label}${lastError ? `: ${lastError.message}` : ''}`);
}

async function request(port, endpoint, token, options = {}, timeoutMs = 10_000) {
  const headers = { ...(options.headers ?? {}) };
  if (token) headers['X-JustSearch-Session'] = token;
  const response = await fetch(`http://127.0.0.1:${port}${endpoint}`, {
    ...options,
    headers,
    signal: AbortSignal.timeout(timeoutMs),
  });
  const text = await response.text();
  let body = null;
  try { body = JSON.parse(text); } catch { /* caller reports the raw body */ }
  return { status: response.status, text, body };
}

async function post(port, endpoint, token, body, timeoutMs = 10_000) {
  return request(port, endpoint, token, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  }, timeoutMs);
}

async function reserveFixedPort() {
  for (let port = 41_000; port < 42_000; port += 1) {
    const free = await new Promise((resolve) => {
      const server = net.createServer();
      server.once('error', () => resolve(false));
      server.listen(port, '127.0.0.1', () => server.close(() => resolve(true)));
    });
    if (free) return port;
  }
  throw new Error('no free fixed test port in 41000..41999');
}

function cleanChildEnvironment() {
  const env = { ...process.env };
  const forbidden = new Set([
    'justsearch_api_port',
    '_java_options',
    'java_tool_options',
    'jdk_java_options',
    'justsearch_config',
    'justsearch_home',
    'justsearch_data_dir',
    'justsearch_supervisor_harness',
    'justsearch_supervisor_stability_window_ms',
    'justsearch_supervisor_max_cooldown_ms',
    'justsearch_supervisor_cooldown_increment_ms',
    'justsearch_supervisor_start_deadline_ms',
    'justsearch_supervisor_graceful_stop_deadline_ms',
    'justsearch_supervisor_hang_poll_interval_ms',
    'justsearch_supervisor_hang_threshold',
  ]);
  for (const key of Object.keys(env)) {
    if (forbidden.has(key.toLowerCase())) delete env[key];
  }
  return env;
}

function requireProductPolicy(binding, label) {
  requireThat(binding?.supervisor?.policyProfile === 'product',
    `${label}: packaged supervisor did not use the product policy: ${JSON.stringify(binding?.supervisor)}`);
}

function currentBinding() {
  const supervisor = readJson(path.join(runtime, 'supervisor.v1.json'));
  const manifest = readJson(path.join(runtime, 'manifest.json'));
  if (supervisor?.state !== 'running' || !manifest?.head?.apiPort
      || manifest.pid !== supervisor.pid || manifest.instanceId !== supervisor.instanceId
      || manifest.head.apiPort !== supervisor.apiPort || !manifest.head.sessionToken) {
    return null;
  }
  return { supervisor, manifest };
}

async function assertOldListenerClosed(port) {
  let lastResult;
  await waitFor(`old listener ${port} to refuse TCP`, 20_000, async () => {
    lastResult = await new Promise((resolve) => {
      const socket = net.connect({ host: '127.0.0.1', port });
      socket.setTimeout(500);
      socket.once('connect', () => {
        socket.destroy();
        resolve({ connected: true });
      });
      socket.once('timeout', () => {
        socket.destroy();
        resolve({ connected: false, code: 'ETIMEDOUT' });
      });
      socket.once('error', (error) => {
        socket.destroy();
        resolve({ connected: false, code: error.code });
      });
    });
    return lastResult.code === 'ECONNREFUSED';
  });
  requireThat(lastResult?.code === 'ECONNREFUSED',
    `old listener ${port} did not produce ECONNREFUSED: ${JSON.stringify(lastResult)}`);
}

function effectiveKey(snapshot, key) {
  return snapshot?.keys?.find((entry) => entry.key === key);
}

async function observePredecessorExit(supervisorFile, predecessor) {
  return waitFor(`exit of incarnation ${predecessor.incarnation}`, 90_000, () => {
    const current = readJson(supervisorFile);
    if (current?.runId !== predecessor.runId
        || current?.lastExit?.incarnation !== predecessor.incarnation) return null;
    return {
      observedAt: Date.now(),
      supervisorUpdatedAt: current.updatedAt,
      lastExit: current.lastExit,
    };
  });
}

async function applyPort(binding, requestedPort, label) {
  const oldSupervisor = binding.supervisor;
  const oldManifest = binding.manifest;
  const oldPort = oldManifest.head.apiPort;
  const token = oldManifest.head.sessionToken;
  const settingsResponse = await request(oldPort, '/api/settings/v2', null);
  requireThat(settingsResponse.status === 200 && settingsResponse.body?.witness,
    `${label}: settings read failed: HTTP ${settingsResponse.status} ${settingsResponse.text}`);

  const operationKey = createOperationKey();
  // Observe independently before the mutation. `observedAt` below is the first filesystem view of
  // lastExit, not a kernel timestamp. The durable timestamp is stronger causally: this predecessor
  // is the only process that could write this operation COMPLETE before its recorded exit.
  const exitObservationPromise = observePredecessorExit(
    path.join(runtime, 'supervisor.v1.json'), oldSupervisor);
  const receipt = await post(oldPort, '/api/settings/v2', token, {
    witness: settingsResponse.body.witness,
    operationKey,
    apiPort: requestedPort,
  }, 30_000);
  const receiptObservedAt = Date.now();
  requireThat(receipt.status === 200 && receipt.body?.operationKey === operationKey
      && receipt.body?.state === 'COMPLETE' && receipt.body?.restartScheduled === true
      && receipt.body?.witness,
  `${label}: settings write did not return a terminal restart receipt: HTTP ${receipt.status} ${receipt.text}`);

  const successor = await waitFor(`${label} successor`, 90_000, async () => {
    const candidate = currentBinding();
    return candidate?.supervisor?.runId === oldSupervisor.runId
      && candidate.supervisor.incarnation > oldSupervisor.incarnation
      && candidate.manifest.instanceId !== oldManifest.instanceId ? candidate : null;
  });
  const successorObservedAt = Date.now();
  const exitObservation = await exitObservationPromise;
  const nextSupervisor = successor.supervisor;
  const nextManifest = successor.manifest;
  const nextPort = nextManifest.head.apiPort;
  requireProductPolicy(successor, `${label} successor`);

  requireThat(receiptObservedAt <= exitObservation.observedAt,
    `${label}: predecessor exit was observed before its terminal receipt reached the client`);
  requireThat(nextManifest.pid !== oldManifest.pid,
    `${label}: successor retained the old Engine pid`);
  requireThat(nextManifest.head.sessionToken !== oldManifest.head.sessionToken,
    `${label}: successor retained the old mutation token`);
  requireThat(nextPort !== oldPort,
    `${label}: successor reused ${oldPort}, so old-listener closure cannot be proven`);
  requireThat(nextSupervisor.lastExit?.code === 4
      && nextSupervisor.lastExit?.counted === false
      && nextSupervisor.lastExit?.reason === 'requested_restart'
      && nextSupervisor.lastExit?.class === 'REQUESTED'
      && nextSupervisor.lastExit?.incarnation === oldSupervisor.incarnation,
  `${label}: exit 4 was not recorded as the uncounted predecessor: ${JSON.stringify(nextSupervisor.lastExit)}`);
  requireThat(nextSupervisor.restartCount === oldSupervisor.restartCount,
    `${label}: requested restart changed restartCount (${oldSupervisor.restartCount} -> ${nextSupervisor.restartCount})`);
  if (requestedPort === 0) {
    requireThat(Number.isInteger(nextPort) && nextPort > 0,
      `${label}: port 0 did not resolve to a positive listener: ${nextPort}`);
  } else {
    requireThat(nextPort === requestedPort,
      `${label}: successor bound ${nextPort}, expected fixed port ${requestedPort}`);
  }
  await assertOldListenerClosed(oldPort);

  const durable = await request(nextPort, `/api/operation-history/${operationKey}`, null);
  requireThat(durable.status === 200 && durable.body?.state === 'complete'
      && Number.isInteger(durable.body?.completedAt),
    `${label}: successor did not retain the COMPLETE operation row: ${durable.text}`);
  requireThat(durable.body.completedAt <= exitObservation.observedAt,
    `${label}: durable completion ${durable.body.completedAt} followed exit observation ${exitObservation.observedAt}`);
  const supervisorExitPublishedAt = Date.parse(exitObservation.supervisorUpdatedAt ?? '');
  requireThat(Number.isFinite(supervisorExitPublishedAt)
      && durable.body.completedAt <= supervisorExitPublishedAt,
  `${label}: durable completion did not precede the supervisor's exit publication: ${JSON.stringify(exitObservation)}`);
  const desired = await request(nextPort, '/api/settings/v2', null);
  requireThat(desired.status === 200 && desired.body?.apiPort === requestedPort,
    `${label}: desired API port disagrees after restart: ${desired.text}`);
  const status = await request(nextPort, '/api/status', null, {}, 20_000);
  const apiComponent = status.body?.readiness?.engineComponents?.api;
  requireThat(status.status === 200 && apiComponent?.desiredVersion
      && apiComponent.desiredVersion === apiComponent.appliedVersion,
  `${label}: API desired/applied versions disagree: ${status.text}`);
  const effective = await request(nextPort, '/api/debug/effective-config', null);
  const desiredKey = effectiveKey(effective.body, 'justsearch.api.port');
  const processKey = effectiveKey(effective.body, 'process.apiPort');
  requireThat(effective.status === 200 && desiredKey?.value === requestedPort
      && desiredKey?.source === 'settings.json',
  `${label}: desired port provenance was not settings.json: ${effective.text}`);
  requireThat(processKey?.value === nextPort && processKey?.source === 'runtime',
    `${label}: applied process port disagrees with the manifest: ${effective.text}`);
  requireThat(desiredKey?.details?.envValue == null && desiredKey?.details?.syspropValue == null,
    `${label}: an API-port environment or JVM override survived: ${JSON.stringify(desiredKey)}`);

  return {
    binding: successor,
    proof: {
      label,
      operationKey,
      requestedPort,
      oldPort,
      appliedPort: nextPort,
      receiptObservedAt,
      successorObservedAt,
      exitObservation,
      orderingLimit: 'exit observed from supervisor file; completedAt is the predecessor-written durable timestamp',
      runId: nextSupervisor.runId,
      incarnation: nextSupervisor.incarnation,
      policyProfile: nextSupervisor.policyProfile,
      lastExit: nextSupervisor.lastExit,
      predecessor: {
        pid: oldManifest.pid,
        instanceId: oldManifest.instanceId,
        listenerClosed: true,
      },
      successor: {
        pid: nextManifest.pid,
        instanceId: nextManifest.instanceId,
        tokenChanged: true,
      },
      durableOperationState: durable.body.state,
      durableCompletedAt: durable.body.completedAt,
      desiredVersion: apiComponent.desiredVersion,
      appliedVersion: apiComponent.appliedVersion,
      desiredSource: desiredKey.source,
    },
  };
}

async function terminateOwnedShellTree() {
  if (shell.exitCode !== null || shell.signalCode !== null) return;
  const killer = spawn('taskkill.exe', ['/PID', String(shell.pid), '/T', '/F'], {
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });
  let output = '';
  killer.stdout?.on('data', (bytes) => { output += bytes.toString(); });
  killer.stderr?.on('data', (bytes) => { output += bytes.toString(); });
  const result = await Promise.race([
    new Promise((resolve) => killer.once('exit', (code, signal) => resolve({ code, signal }))),
    delay(20_000).then(() => ({ timeout: true })),
  ]);
  if (result.timeout) {
    killer.kill();
    throw new Error(`taskkill timed out for owned packaged shell pid ${shell.pid}`);
  }
  requireThat(result.code === 0 && result.signal == null,
    `taskkill failed for owned packaged shell pid ${shell.pid}: ${JSON.stringify(result)} ${output}`);
  await Promise.race([
    shell.exitCode !== null || shell.signalCode !== null
      ? Promise.resolve() : new Promise((resolve) => shell.once('exit', resolve)),
    delay(10_000),
  ]);
  requireThat(shell.exitCode !== null || shell.signalCode !== null,
    `owned packaged shell tree remained alive after taskkill pid ${shell.pid}: ${JSON.stringify(result)} ${output}`);
}

const fixedPort = await reserveFixedPort();
const shell = spawn(installedExe, [], {
  cwd: path.dirname(installedExe),
  env: cleanChildEnvironment(),
  stdio: ['ignore', 'ignore', 'pipe'],
  windowsHide: true,
});
let shellError = '';
shell.stderr?.on('data', (bytes) => { shellError += bytes.toString(); });
let primaryFailure;
try {
  const initial = await waitFor('initial installed Tauri binding', 120_000, () => {
    if (shell.exitCode !== null || shell.signalCode !== null) {
      throw new Error(`installed shell exited during startup: code=${shell.exitCode} signal=${shell.signalCode} ${shellError}`);
    }
    return currentBinding();
  });
  requireProductPolicy(initial, 'initial binding');
  const fixed = await applyPort(initial, fixedPort, 'fixed');
  const ephemeral = await applyPort(fixed.binding, 0, 'ephemeral');
  console.log('INSTALLED_API_PORT_RESTART_PASS', JSON.stringify({
    executable: installedExe,
    data,
    initial: {
      runId: initial.supervisor.runId,
      incarnation: initial.supervisor.incarnation,
      port: initial.manifest.head.apiPort,
      policyProfile: initial.supervisor.policyProfile,
    },
    fixed: fixed.proof,
    ephemeral: ephemeral.proof,
  }));
} catch (error) {
  primaryFailure = error;
} finally {
  let cleanupFailure;
  try {
    const binding = currentBinding();
    if (binding) {
      const stopped = await post(binding.manifest.head.apiPort, '/api/lifecycle/shutdown',
        binding.manifest.head.sessionToken, {});
      requireThat(stopped.status >= 200 && stopped.status < 300,
        `installed Engine cleanup was refused: HTTP ${stopped.status} ${stopped.text}`);
      await waitFor('installed Engine cleanup', 20_000,
        () => !fs.existsSync(path.join(runtime, 'manifest.json')));
      await assertOldListenerClosed(binding.manifest.head.apiPort);
    }
  } catch (error) {
    cleanupFailure = error;
  }
  try {
    await terminateOwnedShellTree();
  } catch (error) {
    if (cleanupFailure) {
      cleanupFailure = new AggregateError(
        [cleanupFailure, error], 'installed Engine and packaged-shell cleanup failed');
    } else {
      cleanupFailure = error;
    }
  }
  if (primaryFailure && cleanupFailure) {
    throw new AggregateError([primaryFailure, cleanupFailure], 'installed API-port proof and cleanup failed');
  }
  if (primaryFailure) throw primaryFailure;
  if (cleanupFailure) throw cleanupFailure;
}
