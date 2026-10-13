'use strict';
// Loaded before each fixture program. A cleanup owns exactly these identities, never a PID tree.
// The preload starts no process and makes no identity query, so a fixture's own start (its port
// deadline included) is not delayed: it records its PID and a moment it certainly held that PID.
// The teardown resolves the birth from that bound and records it before any stop
// (fixture-cleanup.mjs).
const fs = require('node:fs');
const path = require('node:path');
const dir = process.env.JUSTSEARCH_CONFORMANCE_IDENTITIES;
if (dir) {
  if (process.platform !== 'win32') throw new Error('fixture identity capture requires Windows');
  // This process is running, so it holds its PID now: any process born later is not this one.
  const heldAt = (BigInt(Date.now()) * 10000n + 116444736000000000n).toString();
  fs.mkdirSync(dir, { recursive: true });
  // Written aside and renamed, so a teardown never reads a half-written identity.
  const file = path.join(dir, `${process.pid}-${heldAt}.json`);
  fs.writeFileSync(`${file}.tmp`, JSON.stringify({
    pid: process.pid, heldAtFileTimeUtc: heldAt, executable: process.execPath,
  }), { flag: 'wx' });
  fs.renameSync(`${file}.tmp`, file);
}
