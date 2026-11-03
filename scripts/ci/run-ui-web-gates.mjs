#!/usr/bin/env node
/**
 * Run the ui-web pre-merge gate set (tempdoc 872).
 *
 * The set's authority is the `ui-web-gates` commands list in governance/consult-register.v1.json.
 * Recipe prose explains the set; only the validated argv arrays are executed.
 * Until 872 the set was advisory only (pushed by the consult hook at edit time,
 * run in CI nowhere), which is how `gen-token-names --check` and `gen-component-vocabulary
 * --check` sat RED on main for weeks while twelve sessions each re-discovered it and wrote it
 * down. A check that main can violate silently is a memory generator; running it here makes the
 * state impossible instead of merely noted.
 *
 *   node scripts/ci/run-ui-web-gates.mjs            # run everything, non-zero on first failure set
 *   node scripts/ci/run-ui-web-gates.mjs --list     # print the commands and exit
 */
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const REGISTER = path.join(ROOT, 'governance', 'consult-register.v1.json');

/** Return the authoritative argv list, rejecting invalid data before any command runs. */
export function readUiWebGateCommands(register) {
  const entry = (register.entries ?? register.regions ?? []).find((e) => e.id === 'ui-web-gates');
  if (!entry) throw new Error('consult-register: no `ui-web-gates` entry');
  const { commands } = entry;
  if (!Array.isArray(commands) || commands.length === 0) {
    throw new Error('consult-register: ui-web-gates.commands must be a non-empty array of argv arrays');
  }
  for (const [index, command] of commands.entries()) {
    if (!Array.isArray(command) || command.length === 0
        || command.some((arg) => typeof arg !== 'string') || command[0] !== 'node') {
      throw new Error(`consult-register: ui-web-gates.commands[${index}] must be a non-empty array of strings starting with "node"`);
    }
  }
  return commands;
}

function main() {
  const register = JSON.parse(fs.readFileSync(REGISTER, 'utf8'));
  const cmds = readUiWebGateCommands(register);
  if (process.argv.includes('--list')) {
    for (const c of cmds) console.log(c.join(' '));
    return;
  }
  const failed = [];
  for (const c of cmds) {
    const res = spawnSync(c[0], c.slice(1), { cwd: ROOT, encoding: 'utf8', shell: process.platform === 'win32' });
    const label = c.slice(1).join(' ');
    if (res.status === 0) {
      console.log(`ok    ${label}`);
    } else {
      failed.push(label);
      console.log(`FAIL  ${label} (exit ${res.status})`);
      process.stdout.write((res.stdout || '') + (res.stderr || ''));
    }
  }
  console.log(`\nui-web gates: ${cmds.length - failed.length}/${cmds.length} passed`);
  if (failed.length) {
    console.log('failed: ' + failed.join(' | '));
    process.exit(1);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
