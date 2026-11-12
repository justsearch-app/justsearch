import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
export const repo = path.resolve(import.meta.dirname, '../..');
export const root = () => fs.mkdtempSync(path.join(os.tmpdir(), 'owner-check-'));
export const pid = 990051;
export const caller = { harness: 'codex', owner: { key: 'override-check-caller', pid: null }, sessionId: 'check-caller' };
export const emptyOps = { byCriticality: { mustComplete: [], unsafeToInterrupt: [], interruptibleWithLoss: [] }, entries: [] };
let failed = 0;
export async function check(id, fn) {
  try { await fn(); console.log(`PASS ${id}`); }
  catch (e) { failed++; console.error(`FAIL ${id}: ${e.message}`); }
}
export function finish() { process.exitCode = failed ? 1 : 0; }
export function envFor(state) {
  const env = { ...process.env };
  for (const key of Object.keys(env)) if (key.startsWith('JUSTSEARCH_')) delete env[key];
  return { ...env, JUSTSEARCH_DEV_RUNNER_STATE_ROOT: state, JUSTSEARCH_HOME: path.join(state, 'home'),
    JUSTSEARCH_DATA_DIR: path.join(state, 'data'), JS_ADMISSION_DIR: path.join(state, 'admission'), CI: '' };
}
export function seed(state, { expired = false, runner = pid, identities = {}, holder = { key: 'override-check-holder' } } = {}) {
  const active = { kind: 'backend-shared-lease.v1', schemaVersion: 1, runId: 'run-check',
    ownershipEpoch: 9, holder: { source: 'codex', agentSessionId: 'check-holder', owner: holder },
    lease: { durationSec: 30, sequence: 1, renewedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + (expired ? -60000 : 60000)).toISOString() } };
  const run = { schemaVersion: 1, runId: active.runId, holder: active.holder, pids: { runnerPid: runner },
    identities, apiPortActual: null, uiPortActual: null };
  fs.mkdirSync(path.join(state, 'runs', active.runId), { recursive: true });
  fs.writeFileSync(path.join(state, 'active.json'), JSON.stringify(active));
  fs.writeFileSync(path.join(state, 'runs', active.runId, 'run.json'), JSON.stringify(run));
  return { active, run };
}
export function preload(state, { code = '', readCode = '', readFile = '' } = {}) {
  const file = path.join(state, 'probe.cjs');
  fs.writeFileSync(file, `const fs=require('node:fs'); const kill=process.kill, read=fs.readFileSync; let reads=0;
process.kill=function(p,s){if(p===${pid}&&s===0){if(${JSON.stringify(code)})throw Object.assign(new Error('injected lookup'),{code:${JSON.stringify(code)}});return true;}return kill.call(process,p,s);};
fs.readFileSync=function(p,...a){if(String(p)===${JSON.stringify(readFile)}&&reads++===0&&${JSON.stringify(readCode)})throw Object.assign(new Error('injected read'),{code:${JSON.stringify(readCode)}});return read.call(this,p,...a);};`);
  return file;
}
export function cli(state, args, extra = {}) {
  const r = spawnSync(process.execPath, [...(extra.preload ? ['--require', extra.preload] : []),
    path.join(repo, 'scripts/dev/dev-runner.cjs'), ...args, '--json'],
  { cwd: repo, env: { ...envFor(state), ...extra.env }, encoding: 'utf8', timeout: 20000 });
  assert.ifError(r.error);
  return { ...r, json: JSON.parse(r.stdout.trim().split('\n').at(-1) || '{}') };
}
export async function mcp(state, options = {}) {
  const child = spawn(process.execPath, ['--require', preload(state, options), path.join(repo, 'scripts/dev/justsearch-dev-mcp.mjs')],
    { cwd: repo, env: { ...envFor(state), JUSTSEARCH_AGENT_IDENTITY: 'codex:check-caller' }, stdio: ['pipe', 'pipe', 'pipe'] });
  let serial = 0, buffer = '', stderr = '';
  const pending = new Map();
  child.stderr.on('data', b => { stderr += b; });
  child.stdout.on('data', b => {
    buffer += b;
    while (buffer.includes('\n')) {
      const end = buffer.indexOf('\n'), line = buffer.slice(0, end); buffer = buffer.slice(end + 1);
      let msg; try { msg = JSON.parse(line); } catch { continue; }
      const wait = pending.get(msg.id); if (wait) { clearTimeout(wait.timer); pending.delete(msg.id); wait.resolve(msg); }
    }
  });
  child.on('error', e => { for (const w of pending.values()) { clearTimeout(w.timer); w.reject(e); } pending.clear(); });
  child.on('exit', () => { for (const w of pending.values()) { clearTimeout(w.timer); w.reject(new Error(`MCP exited: ${stderr}`)); } pending.clear(); });
  const request = (method, params) => new Promise((resolve, reject) => {
    const id = ++serial, timer = setTimeout(() => { pending.delete(id); reject(new Error(`${method} timeout: ${stderr}`)); }, 15000);
    pending.set(id, { resolve, reject, timer }); child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
  });
  const close = async () => {
    if (child.exitCode !== null || child.signalCode !== null) return;
    const ended = new Promise(resolve => child.once('exit', resolve)); child.kill(); await ended;
  };
  try {
    const response = await request('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'owner-check', version: '1' } });
    assert.equal(response.error, undefined);
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
  } catch (e) { await close(); throw e; }
  return { close, call: async (tool, args = {}) => {
    const r = await request('tools/call', { name: `justsearch.dev.${tool}`, arguments: args });
    assert.equal(r.error, undefined, JSON.stringify(r));
    assert.notEqual(r.result?.isError, true, JSON.stringify(r));
    return r.result.structuredContent ?? JSON.parse(r.result.content[0].text);
  } };
}
