import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { check, finish, root, seed, mcp, cli, preload, caller } from './owner-liveness-check-support.mjs';
const require = createRequire(import.meta.url), presence = require('./lib/owner-presence.cjs');
const verdict = require('./lib/ownership-verdict.cjs');
const state = root(), file = path.join(state, 'owners', `${caller.owner.key}.json`);
const before = '{ "ownedEpoch": 3, "unrelated": {"keep":true}, "sessionId":"old" }\n';
const reset = text => { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, text); };
function stub(name, fn, action) {
  const original = fs[name]; fs[name] = (...args) => fn(original, ...args);
  try { return action(); } finally { fs[name] = original; }
}
try {
  for (const code of ['EACCES', 'EPERM', 'EBUSY', 'EIO']) for (const op of ['touch', 'merge']) {
    await check(`S1 ${op} ${code}`, () => {
      reset(before);
      const result = stub('readFileSync', (read, p, ...args) => {
        if (String(p) === file) throw Object.assign(new Error(code), { code }); return read(p, ...args);
      }, () => op === 'touch' ? presence.touchOwner(state, caller) : presence.mergeOwnerRecord(state, caller.owner.key, { added: true }));
      const bytes = fs.readFileSync(file, 'utf8');
      assert.ok(result === false && bytes === before, `write=${result}; unchanged=${bytes === before}`);
    });
  }
  await check('S2 missing creates stamped touch', () => {
    fs.rmSync(file, { force: true }); assert.equal(presence.touchOwner(state, caller, { now: 1000 }), true);
    const doc = JSON.parse(fs.readFileSync(file));
    assert.equal(doc.key, caller.owner.key); assert.equal(doc.schema, presence.TOUCH_SCHEMA);
    assert.equal(doc.lastDevStackTouchAt, new Date(1000).toISOString());
  });
  for (const op of ['touch', 'merge']) await check(`S3 ${op} preserves fields and patch wins`, () => {
    reset(before);
    assert.equal(op === 'touch' ? presence.touchOwner(state, caller) : presence.mergeOwnerRecord(state, caller.owner.key,
      { sessionId: 'patched', schema: 'wrong', key: 'wrong' }), true);
    const doc = JSON.parse(fs.readFileSync(file));
    assert.equal(doc.ownedEpoch, 3); assert.deepEqual(doc.unrelated, { keep: true });
    assert.equal(doc.sessionId, op === 'touch' ? caller.sessionId : 'patched');
    assert.equal(doc.schema, presence.TOUCH_SCHEMA); assert.equal(doc.key, caller.owner.key);
  });
  for (const text of ['{bad', 'null', '["poison"]', '42', '"poison"']) await check(`S4 clean ${text}`, () => {
    reset(text); assert.equal(presence.touchOwner(state, caller, { now: 1000 }), true);
    const actual = fs.readFileSync(file, 'utf8'); fs.rmSync(file);
    assert.equal(presence.touchOwner(state, caller, { now: 1000 }), true);
    assert.deepEqual(JSON.parse(actual), JSON.parse(fs.readFileSync(file)), 'invalid record must equal a fresh touch');
  });
  for (const kind of ['write', 'rename', 'partial-write']) await check(`S6 ${kind} preserves old record and removes temp`, () => {
    reset(before);
    const method = kind === 'rename' ? 'renameSync' : 'writeFileSync';
    const result = stub(method, (original, p, ...args) => {
      if (String(p).startsWith(`${file}.`)) {
        if (kind === 'partial-write') original(p, 'partial');
        throw Object.assign(new Error('injected write failure'), { code: 'EIO' });
      }
      return original(p, ...args);
    }, () => presence.touchOwner(state, caller));
    assert.equal(result, false); assert.equal(fs.readFileSync(file, 'utf8'), before);
    assert.deepEqual(fs.readdirSync(path.dirname(file)), [path.basename(file)]);
  });
  // Real MCP and CLI calls, including exactly one failed read during the MCP touch.
  for (const code of ['', 'EIO']) await check(`S5 MCP next call notice ${code || 'readable'}`, async () => {
    seed(state); reset(before);
    const client = await mcp(state, { readFile: file, readCode: code });
    try {
      const result = await client.call('quick_health', { probe: false });
      assert.match(result.ownership?.displacedNotice ?? '', /your (?:dev )?stack.*was taken over/i);
    } finally { await client.close(); }
  });
  await check('S15 reader and verdict preserved', () => {
    const now = Date.now(); fs.writeFileSync(path.join(state, 'session.json'), JSON.stringify({
      lastActivityAt: new Date(now - 1000).toISOString(), lastDevStackTouchAt: new Date(now - 1200000).toISOString(), ownedEpoch: 3 }));
    const activity = verdict.readSessionActivity(state, 'session'); assert.equal(activity.ownedEpoch, 3);
    const result = verdict.computeOwnershipVerdict({ active: { runId: 'reader-check', holder: { agentSessionId: 'other' } },
      callerSessionId: 'self', supervisorAlive: true, leaseExpired: false, ownerActivity: activity, now });
    assert.equal(result.verdict, 'IDLE_HOLD'); assert.equal(verdict.readSessionActivity(state, 'absent'), null);
  });
  await check('S15 obsolete writer removed', () => { assert.equal(verdict.mergeSessionActivity, undefined); });
} finally { fs.rmSync(state, { recursive: true, force: true }); }
finish();
