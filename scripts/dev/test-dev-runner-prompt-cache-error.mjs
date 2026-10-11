#!/usr/bin/env node
// An invalid --prompt-cache-mib is the caller's input error. The runner refuses it before any start
// work with the documented code INVALID_PROMPT_CACHE_MIB and a one-line message, never as UNHANDLED
// with a stack trace. Each case runs the real CLI entry point; parsing fails before anything starts.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const RUNNER = path.join(HERE, 'dev-runner.cjs');
const CODE = 'INVALID_PROMPT_CACHE_MIB';

function runCli(args) {
  const res = spawnSync(process.execPath, [RUNNER, ...args], {
    encoding: 'utf8',
    timeout: 30_000,
    windowsHide: true,
  });
  assert.equal(res.error, undefined, `the runner must finish on its own: ${res.error}`);
  return res;
}

for (const value of ['abc', '-5', '1.5', '2147483648', '']) {
  for (const form of ['separate', 'inline']) {
    const flag = form === 'inline' ? [`--prompt-cache-mib=${value}`] : ['--prompt-cache-mib', value];
    if (form === 'inline' && value === '') continue; // `--x=` is an empty inline value, same as separate ''
    const label = `${form} ${JSON.stringify(value)}`;

    const json = runCli(['start', '--json', ...flag]);
    assert.equal(json.status, 1, `${label}: a refusal exits 1`);
    const lines = json.stdout.trim().split(/\r?\n/);
    assert.equal(lines.length, 1, `${label}: one JSON line: ${json.stdout}`);
    const report = JSON.parse(lines[0]);
    assert.equal(report.ok, false, label);
    assert.equal(report.error.code, CODE, `${label}: a typed validation code, not UNHANDLED: ${lines[0]}`);
    assert.equal(report.error.stack, undefined, `${label}: no stack trace for an input error`);
    assert.doesNotMatch(report.error.message, /[\r\n]/, `${label}: a one-line message`);
    assert.match(report.error.message, /--prompt-cache-mib/, `${label}: names the option`);
    assert.match(report.error.message, /non-negative integer MiB/, `${label}: names the expected type`);
    assert.ok(report.error.message.includes(JSON.stringify(value)), `${label}: names the value given`);

    const human = runCli(['start', ...flag]);
    assert.equal(human.status, 1, `${label}: a refusal exits 1 without --json too`);
    const errLines = human.stderr.trim().split(/\r?\n/);
    assert.equal(errLines.length, 1, `${label}: one stderr line, no stack: ${human.stderr}`);
    assert.match(errLines[0], new RegExp(CODE), `${label}: the human report names the code`);
  }
}

const require = createRequire(import.meta.url);
const { parseArgs, buildHeadJavaOpts } = require('./dev-runner.cjs').__test;
assert.throws(() => parseArgs(['start', '--prompt-cache-mib', 'abc']),
  (err) => err.code === CODE && !/\n/.test(err.message));
assert.throws(() => buildHeadJavaOpts({ promptCacheMiB: 'abc' }),
  (err) => err.code === CODE, 'the property value the runner writes is validated with the same code');

console.log('test-dev-runner-prompt-cache-error: OK');
