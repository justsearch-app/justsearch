#!/usr/bin/env node
/**
 * Print the calling agent session's identity (see lib/agent-identity.cjs).
 *
 *   node scripts/dev/agent-identity.mjs --json [--session-id <id>]
 *
 * The Python registers (`scripts/jseval/jseval/agent_spawn_register.py`, `run_register.py`) call
 * this so both languages resolve identity by one rule. Output (one JSON line):
 *   { "harness": "claude"|"codex"|"ci"|"unknown", "owner": {...}|null, "sessionId": "..."|null,
 *     "source": "...", "elapsedMs": <n> }
 * The process walk starts at this process, so a caller's harness is found through its parents.
 */

import process from 'node:process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { resolveAgentIdentity } = require('./lib/agent-identity.cjs');

const argv = process.argv.slice(2);
let explicit = null;
for (let i = 0; i < argv.length; i += 1) {
  const a = argv[i];
  if (a === '--session-id') explicit = argv[++i] ?? null;
  else if (a.startsWith('--session-id=')) explicit = a.slice('--session-id='.length);
}

const t0 = process.hrtime.bigint();
const identity = resolveAgentIdentity({ explicit });
const elapsedMs = Number((process.hrtime.bigint() - t0) / 1_000_000n);
const out = { ...identity, elapsedMs };

if (argv.includes('--json')) {
  process.stdout.write(`${JSON.stringify(out)}\n`);
} else {
  const who = identity.sessionId || identity.owner?.key || '(none)';
  process.stdout.write(`${identity.harness} ${who} via ${identity.source} in ${elapsedMs} ms\n`);
}
