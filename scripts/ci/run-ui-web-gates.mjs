#!/usr/bin/env node
/**
 * Run the ui-web pre-merge gate set (tempdoc 872).
 *
 * The set's authority is the `ui-web-gates` recipe in governance/consult-register.v1.json —
 * this runner PARSES that recipe rather than carrying its own list, so there is one list, not
 * two that drift. Until 872 the set was advisory only (pushed by the consult hook at edit time,
 * run in CI nowhere), which is how `gen-token-names --check` and `gen-component-vocabulary
 * --check` sat RED on main for weeks while twelve sessions each re-discovered it and wrote it
 * down. A check that main can violate silently is a memory generator; running it here makes the
 * state impossible instead of merely noted.
 *
 *   node scripts/ci/run-ui-web-gates.mjs            # run everything, non-zero on first failure set
 *   node scripts/ci/run-ui-web-gates.mjs --list     # print the parsed commands and exit
 */
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const REGISTER = path.join(ROOT, 'governance', 'consult-register.v1.json');

/** Parse the recipe prose into `node <script> [args]` commands. Pure; the test seam. */
export function parseUiWebGateCommands(register) {
  const entry = (register.entries ?? register.regions ?? []).find((e) => e.id === 'ui-web-gates');
  if (!entry) throw new Error('consult-register: no `ui-web-gates` entry');
  const cmds = [];
  for (const line of entry.recipe) {
    if (/^Plus the kernel gates:/.test(line)) {
      // One invocation per id. `--gate` is repeatable now, so a single call would work too; the
      // per-id loop is kept deliberately, because it attributes a failure to the gate that caused
      // it instead of to one combined run.
      // Accept both spellings — repeated `--gate <id>` (what run.mjs actually parses; the recipe
      // uses it since tempdoc 932) and the older comma-joined list.
      const ids = [...line.matchAll(/--gate\s+([a-z0-9,-]+)/g)].flatMap((m) => m[1].split(','));
      for (const id of ids.filter(Boolean)) {
        cmds.push(['node', 'scripts/governance/run.mjs', '--gate', id, '--mode', 'gate']);
      }
      continue;
    }
    const idx = line.indexOf(': ');
    if (idx < 0 || !/scripts\/ci\/<name>\.mjs\)|additionally:/.test(line)) continue;
    // A parenthetical naming a self-test (`(with its self-test: node scripts/ci/X.test.mjs)`)
    // is a command too — hoist it before stripping the rest of the parentheticals.
    const selfTests = [...line.matchAll(/node (scripts\/ci\/[a-z0-9-]+\.test\.mjs)/g)].map((m) => m[1]);
    const list = line.slice(idx + 2).replace(/\s*\([^)]*\)/g, '').replace(/\.\s*$/, '');
    for (const item of list.split(',').map((s) => s.trim()).filter(Boolean)) {
      const [name, ...args] = item.split(/\s+/);
      cmds.push(['node', `scripts/ci/${name}.mjs`, ...args]);
    }
    for (const t of selfTests) cmds.push(['node', t]);
  }
  return cmds;
}

/**
 * Silent-green guard. The parser reads prose; an ordinary editorial reword of the recipe
 * (e.g. dropping the `(node scripts/ci/<name>.mjs)` marker) would shrink the parse and this
 * runner would print `6/6 passed` — the exact failure it exists to prevent. The floor is the
 * count on the day it was set; raise it when the recipe grows, never lower it silently.
 */
export const EXPECTED_MIN = 27;

function main() {
  const register = JSON.parse(fs.readFileSync(REGISTER, 'utf8'));
  const cmds = parseUiWebGateCommands(register);
  if (process.argv.includes('--list')) {
    for (const c of cmds) console.log(c.join(' '));
    return;
  }
  if (cmds.length < EXPECTED_MIN) {
    console.log(`run-ui-web-gates: parsed only ${cmds.length} command(s) from the ui-web-gates recipe ` +
      `(floor ${EXPECTED_MIN}). The recipe prose in governance/consult-register.v1.json no longer parses — ` +
      'fix the recipe or the parser (parseUiWebGateCommands); do not lower the floor to make this green.');
    process.exit(1);
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
