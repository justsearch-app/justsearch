#!/usr/bin/env node
/**
 * note-observation.mjs tests (tempdoc 872 shape): resolveSessionId stays the
 * shared session-identity seam; the CLI is a router that writes nothing.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolveSessionId, renderRouting, today } from './note-observation.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'note-obs-test-'));
function freshRoot() {
  return fs.mkdtempSync(path.join(tmp, 'root-'));
}

try {
  run('resolveSessionId reads the current-session-id pointer file', () => {
    const root = freshRoot();
    fs.mkdirSync(path.join(root, 'tmp', 'agent-telemetry'), { recursive: true });
    fs.writeFileSync(path.join(root, 'tmp', 'agent-telemetry', 'current-session-id'), 'abc-123\n');
    assert.equal(resolveSessionId({ root, env: {} }), 'abc-123');
  });
  run('resolveSessionId falls back to JUSTSEARCH_AGENT_SESSION_ID', () => {
    assert.equal(resolveSessionId({ root: freshRoot(), env: { JUSTSEARCH_AGENT_SESSION_ID: 'env-sid' } }), 'env-sid');
  });
  run('resolveSessionId prefers CLAUDE_CODE_SESSION_ID', () => {
    assert.equal(
      resolveSessionId({ root: freshRoot(), env: { CLAUDE_CODE_SESSION_ID: 'cc-sid', JUSTSEARCH_AGENT_SESSION_ID: 'x' } }),
      'cc-sid',
    );
  });
  // Env-first is deliberate (tempdoc 684): the pointer file records whatever session
  // last STARTED in this checkout — foreign in the shared main checkout.
  run('resolveSessionId: env wins over the pointer file', () => {
    const root = freshRoot();
    fs.mkdirSync(path.join(root, 'tmp', 'agent-telemetry'), { recursive: true });
    fs.writeFileSync(path.join(root, 'tmp', 'agent-telemetry', 'current-session-id'), 'file-loses');
    assert.equal(resolveSessionId({ root, env: { CLAUDE_CODE_SESSION_ID: 'env-wins' } }), 'env-wins');
  });
  run('resolveSessionId sanitizes unsafe characters', () => {
    assert.equal(resolveSessionId({ root: freshRoot(), env: { CLAUDE_CODE_SESSION_ID: 'a/b c' } }), 'a_b_c');
  });
  run('today formats YYYY-MM-DD', () => {
    assert.equal(today(new Date('2026-08-26T12:00:00Z')), '2026-08-26');
  });

  run('renderRouting names every destination and echoes the text', () => {
    const t = renderRouting('stale comment at Foo.java:12');
    assert.match(t, /RETIRED \(tempdoc 872\)/);
    assert.match(t, /quarantine the flaky test in its own runner/);
    assert.match(t, /project knowledge/);
    assert.match(t, /owning tempdoc/);
    assert.match(t, /fix it in place/);
    assert.match(t, /stale comment at Foo\.java:12/);
  });

  // The CLI contract: exit 2, routing on stderr, and NOTHING written anywhere.
  run('CLI refuses to write and routes instead', () => {
    const root = freshRoot();
    const before = fs.readdirSync(root);
    const res = spawnSync(process.execPath, [path.join(HERE, 'note-observation.mjs'), 'some finding — `x.ts:1`'], {
      cwd: root, encoding: 'utf8', env: { ...process.env, CLAUDE_CODE_SESSION_ID: 'cli-sid' },
    });
    assert.equal(res.status, 2, `exit code ${res.status}; stderr: ${res.stderr}`);
    assert.match(res.stderr, /nothing was written/);
    assert.match(res.stderr, /some finding/);
    assert.deepEqual(fs.readdirSync(root), before, 'the CLI must not create files');
    assert.equal(fs.existsSync(path.join(root, 'docs', 'observations.d')), false);
  });
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}

if (failures.length) {
  console.error(`note-observation: ${failures.length} failure(s)\n  ` + failures.join('\n  '));
  process.exit(1);
}
console.log(`note-observation: ${passed} passed`);
