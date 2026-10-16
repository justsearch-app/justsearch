import './lib/test-fixture-processes.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

// A junction exercises the same alias-to-canonical-root comparison as hosted Windows 8.3 TEMP.
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'fixture-temp-alias-'));
const physical = path.join(root, 'physical');
const alias = path.join(root, 'alias');
fs.mkdirSync(physical);
fs.symlinkSync(physical, alias, process.platform === 'win32' ? 'junction' : 'dir');
let child;
try {
  const fixture = path.resolve(import.meta.dirname, 'lib/test-fixture-processes.cjs');
  child = spawn(process.execPath, ['-e', `
    require(${JSON.stringify(fixture)});
    const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
    const directory = fs.realpathSync.native(fs.mkdtempSync(path.join(os.tmpdir(), 'canonical-')));
    fs.writeFileSync(path.join(directory, 'receipt.txt'), 'fixture');
    fs.rmSync(directory, {recursive:true, force:true});
    if (fs.existsSync(directory)) throw new Error('canonical fixture directory survived');
  `], { env: { ...process.env, TEMP: alias, TMP: alias, TMPDIR: alias },
    stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  let output = '';
  child.stdout.on('data', data => { output += data; });
  child.stderr.on('data', data => { output += data; });
  const code = await new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('exit', resolve);
  });
  assert.equal(code, 0, 'fixture cleanup accepts the canonical form of its declared TEMP root: ' + output);
  console.log('PASS canonical temporary-root alias cleanup');
} finally {
  if (child && child.exitCode === null && child.signalCode === null) child.kill();
  fs.unlinkSync(alias); // unlink the junction non-recursively before removing its containing tree
  fs.rmSync(root, { recursive: true, force: true });
}
