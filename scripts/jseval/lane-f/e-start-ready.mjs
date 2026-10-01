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

