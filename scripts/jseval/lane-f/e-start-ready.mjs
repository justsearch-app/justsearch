/** Owned startup/readiness evidence collection; e-run supplies lifecycle execution. */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const write = (file, value) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
};
export function verifySharedModels(config, sharedModels) {
  const models = config.keys?.find(entry => entry.key === 'justsearch.models.dir')?.value;
  if (typeof models !== 'string' || path.resolve(models).toLowerCase() !== sharedModels.toLowerCase()) {
    throw new Error(`Shared models required: effective justsearch.models.dir=${models}, expected ${sharedModels}`);
  }
  return models;
}
// Same realized-identity predicate used by fixture-cycle.sh (not merely a past activation result).
export function chatReady(status, profile) {
  const active = status?.active ?? {};
  const online = active.modelPath != null || status?.activation?.state === 'completed' && active.activeVariantId;
  return Boolean(online && (active.chatProfile ?? status?.chatProfile) === profile);
}
export async function activateChat(command, context, runtime = {}) {
  const now = runtime.now ?? Date.now;
  const request = runtime.fetch ?? fetch;
  const sleep = runtime.sleep ?? (ms => new Promise(resolve => setTimeout(resolve, ms)));
  if (!(command.timeoutSeconds > 0 && command.timeoutSeconds <= 300)) throw new Error('AI activation timeout must be <= 300 s');
  const deadline = Math.min(context.deadline ?? Infinity, now() + command.timeoutSeconds * 1000);
  const timeout = () => new Error(`AI activation timed out: ${command.profile} profile did not become ready within ${command.timeoutSeconds} s; see ${context.raw}`);
  const call = async (endpoint, body) => {
    if (now() >= deadline) throw timeout();
    const url = `${command.baseUrl}${endpoint}`;
    const headers = { Host: new URL(command.baseUrl).host, ...(body ? { 'Content-Type': 'application/json' } : {}) };
    const prefix = path.join(context.raw, `${context.sequence++}-${command.label}`);
    const requestFile = `${prefix}-request.json`, responseFile = `${prefix}-response.json`;
    const receipt = { label: command.label, method: body ? 'POST' : 'GET', url, headers: { ...headers },
      requestFile, rawFile: responseFile, observedAt: new Date(now()).toISOString(), tokenPresent: Boolean(context.token) };
    write(requestFile, { method: receipt.method, url, headers: receipt.headers, body });
    context.record.commands.push(receipt);
    if (context.token) headers['X-JustSearch-Session'] = context.token;
    try {
      const response = await request(url, { method: receipt.method, headers,
        ...(body ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(Math.min(body ? 30000 : 10000, deadline - now())) });
      receipt.status = response.status;
      const raw = await response.text();
      let value; try { value = JSON.parse(raw); } catch { value = raw; }
      write(responseFile, { status: response.status, body: value });
      if (!response.ok) throw new Error(`AI activation ${endpoint}: HTTP ${response.status}; see ${responseFile}`);
      if (now() >= deadline) throw timeout();
      return value;
    } catch (error) {
      receipt.error = String(error.message);
      if (now() >= deadline) throw timeout();
      throw new Error(`AI activation request failed: ${endpoint}: ${error.message}; see ${prefix}`, { cause: error });
    }
  };
  await call(command.endpoint, command.body);
  while (now() < deadline) {
    const status = await call(command.readyEndpoint);
    if (chatReady(status, command.profile)) return;
    if (status?.activation?.state === 'failed') throw new Error(`AI activation failed for ${command.profile}; see ${context.raw}`);
    await sleep(Math.min(command.pollIntervalMs, deadline - now()));
  }
  throw timeout();
}
async function api(context, endpoint) {
  const url = `http://127.0.0.1:33221${endpoint}`;
  const response = await fetch(url, { headers: { Host: '127.0.0.1:33221' }, signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw new Error(`${endpoint}: HTTP ${response.status}`);
  const body = await response.json();
  const rawFile = endpoint === '/api/mcp/token' ? undefined
    : path.join(context.raw, `${context.sequence++}-${endpoint.replaceAll('/', '_')}.json`);
  context.record.commands.push({ label: endpoint, method: 'GET', url, headers: { Host: '127.0.0.1:33221' },
    status: response.status, observedAt: new Date().toISOString(), rawFile, tokenResponseRetained: false });
  if (rawFile) write(rawFile, body);
  return body;
}
export async function startOwned(command, context, bindings, execute, sharedModels) {
  const proc = execute(command, context);
  const receipt = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Start receipt deadline')), 180000);
    proc.child.stdout.on('data', () => {
      for (const line of proc.stdout().split(/\r?\n/)) {
        let value; try { value = JSON.parse(line); } catch { continue; }
        if (value.runId && value.ok !== false) { clearTimeout(timer); resolve(value); }
        else if (value.ok === false) { clearTimeout(timer); reject(new Error(JSON.stringify(value.error))); }
      }
    });
    proc.complete.then(({ code }) => { clearTimeout(timer); reject(new Error(`Start exited ${code} before receipt`)); });
  });
  bindings.runId = receipt.runId;
  context.owned = { command, proc, runId: receipt.runId };
  context.dataDir = command.args[command.args.indexOf('--data-dir') + 1];
  context.tree = command.cwd;
  context.record.runIds.push(receipt.runId);
  write(path.join(context.raw, `${command.label}-receipt.json`), receipt);
  // A dev-mode stack (main's split arm) does not enforce the per-boot token and hands out none;
  // proceed without it there. Any mutation the server does guard still fails loudly on the wire.
  context.token = (await api(context, '/api/mcp/token')).token ?? null;
  context.record.tokenEnforced = Boolean(context.token);
  // /api/health answers 503 until the index is ready; wait for readiness, bounded.
  const readyBy = Date.now() + 300000;
  for (;;) {
    try { await api(context, '/api/health'); break; } catch (error) {
      if (!/HTTP 503/.test(String(error.message)) || Date.now() > readyBy) throw error;
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
  }
  await api(context, '/api/debug/state');
  context.manifest = await api(context, '/api/runtime/manifest');
  const config = await api(context, '/api/debug/effective-config');
  context.record.sharedModels = verifySharedModels(config, sharedModels);
  const ready = await execute({ label: `capability-ready-${command.label}`, executable: 'python',
    args: [path.join(ROOT, 'scripts/jseval/lane-f/capability-ready.py'),
      '--base-url', 'http://127.0.0.1:33221', '--timeout', '300',
      '--output', path.join(context.raw, `${command.label}-capability-ready.json`)],
    cwd: command.cwd, env: command.env }, context).complete;
  if (ready.code !== 0) throw new Error(`Capability readiness failed for ${command.label}; see ${ready.receipt.stderrFile}`);
}

