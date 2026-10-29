#!/usr/bin/env node
/**
 * Keep Search scenario bindings and retirements honest. This checks declarations, not execution:
 * a bound test or UI step is an observation to run, never evidence that it ran or passed.
 * Run from the repository root; --base-ref defaults to origin/main. Missing history fails closed.
 */
import { readFileSync, statSync } from 'node:fs';
import { resolve, relative, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

export const CATALOG = 'governance/search-window-scenarios.v1.json';
const STEP_INDEX = 'scripts/jseval/jseval/ui_step_index.json';
export const LAYERS = ['controller', 'view', 'real-ui-fixture', 'live-backend'];
const RACES = ['concurrent-or-identical-send', 'late-response', 'second-conversation-or-tab', 'reload', 'screen-switch'];
const nonempty = value => typeof value === 'string' && value.trim().length > 0;

/** Literal Vitest/Jest titles, including the declared template title of it.each. No code runs. */
export function testTitles(source) {
  // Tokenize strings before punctuation so parentheses inside a title or each row stay inert.
  const tokens = [...source.matchAll(/\/\/[^\n]*|\/\*[\s\S]*?\*\/|'(?:\\[\s\S]|[^'\\])*'|"(?:\\[\s\S]|[^"\\])*"|`(?:\\[\s\S]|[^`\\])*`|\/(?:\\.|\[(?:\\.|[^\]\\])*\]|[^/\n\\[])+\/[dgimsuvy]*|[\w$]+|[^\s]/g)]
    .map(match => match[0]).filter(token => !token.startsWith('//') && !token.startsWith('/*'));
  // Pair delimiters once: a disabled suite excludes its entire call, including nested suites.
  const closes = new Map();
  const stack = [];
  for (let i = 0; i < tokens.length; i++) {
    if (['(', '[', '{'].includes(tokens[i])) stack.push(i);
    else if ([')', ']', '}'].includes(tokens[i])) {
      const open = stack.pop();
      if (tokens[open] === ({ ')': '(', ']': '[', '}': '{' })[tokens[i]]) closes.set(open, i);
    }
  }
  const declarations = [];
  for (let i = 0; i < tokens.length; i++) {
    if (!['it', 'test', 'describe', 'xit', 'xtest', 'xdescribe'].includes(tokens[i]) || tokens[i - 1] === '.') continue;
    const suite = ['describe', 'xdescribe'].includes(tokens[i]);
    let disabled = tokens[i].startsWith('x');
    let j = i + 1;
    while (tokens[j] === '.' && ['only', 'skip', 'todo', 'concurrent', 'sequential', 'fails', 'each', 'skipIf', 'runIf'].includes(tokens[j + 1])) {
      const modifier = tokens[j + 1];
      // Conditional declarations are unbindable: do not guess their runtime environment.
      if (['skip', 'todo', 'skipIf', 'runIf'].includes(modifier)) disabled = true;
      j += 2;
      if (!['each', 'skipIf', 'runIf'].includes(modifier)) continue;
      if (modifier === 'each' && tokens[j]?.startsWith('`')) { j++; continue; }
      if (tokens[j] !== '(' || !closes.has(j)) break;
      j = closes.get(j) + 1;
    }
    if (tokens[j] === '(' && closes.has(j)) declarations.push({ start: i, call: j, end: closes.get(j), suite, disabled });
  }
  const disabledSuites = declarations.filter(declaration => declaration.suite && declaration.disabled);
  const titles = new Set();
  for (const declaration of declarations) {
    if (declaration.suite || declaration.disabled || disabledSuites.some(suite => suite.call < declaration.start && declaration.start < suite.end)) continue;
    const j = declaration.call;
    const literal = tokens[j + 1];
    // The entire first argument must be one literal, not a concatenation or other expression.
    if (!/^['"`]/.test(literal ?? '') || tokens[j + 2] !== ',') continue;
    // Decode escapes without evaluating repository code. Titles with substitutions are not static.
    if (literal[0] === '`' && literal.includes('${')) continue;
    titles.add(literal.slice(1, -1).replace(/\\(u\{[\da-fA-F]+\}|u[\da-fA-F]{4}|x[\da-fA-F]{2}|[\s\S])/g, (_, escaped) => {
      if (escaped.startsWith('u{')) return String.fromCodePoint(parseInt(escaped.slice(2, -1), 16));
      if (/^[ux]/.test(escaped)) return String.fromCharCode(parseInt(escaped.slice(1), 16));
      return ({ n: '\n', r: '\r', t: '\t', b: '\b', f: '\f', v: '\v', '0': '\0', '\n': '' })[escaped] ?? escaped;
    }));
  }
  return titles;
}

/** An absent catalog at the merge base is a first introduction, not missing history. */
export function readBaseCatalog(root, baseRef = 'origin/main') {
  const git = args => execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  const base = git(['merge-base', 'HEAD', baseRef]);
  if (!base) throw new Error(`no merge base with ${baseRef}`);
  if (!git(['ls-tree', '--name-only', base, '--', CATALOG])) return { base, catalog: null };
  return { base, catalog: JSON.parse(git(['show', `${base}:${CATALOG}`])) };
}

export function checkSearchWindowScenarios({ root, catalog, stepIndex, baseline = null }) {
  const failures = [];
  const counts = Object.fromEntries(LAYERS.map(layer => [layer, { bound: 0, unbound: 0 }]));
  if (catalog?.version !== 1 || !Array.isArray(catalog.entries) || !Array.isArray(catalog.retired)) {
    return { failures: ['catalog requires version 1, entries and retired arrays'], counts };
  }
  const ids = new Set();
  for (const row of [...catalog.entries, ...catalog.retired]) {
    if (!nonempty(row?.id)) failures.push('every scenario and retirement requires an id');
    else if (ids.has(row.id)) failures.push(`duplicate ID ${row.id}`);
    else ids.add(row.id);
  }
  for (const row of catalog.retired) {
    if (!nonempty(row.reason)) failures.push(`${row.id}: retirement requires a reason`);
    if (row.replacedBy !== undefined && (!nonempty(row.replacedBy) || !catalog.entries.some(entry => entry.id === row.replacedBy))) {
      failures.push(`${row.id}: replacedBy must name an active scenario`);
    }
  }
  const steps = new Set(Object.values(stepIndex).flat());
  const sources = new Map();
  for (const entry of catalog.entries) {
    const label = entry.id;
    if (!nonempty(entry.behavior) || !['works-today', 'known-gap'].includes(entry.kind)) failures.push(`${label}: behavior and valid kind are required`);
    if (!Array.isArray(entry.races) || entry.races.some(race => !RACES.includes(race)) || new Set(entry.races).size !== entry.races.length) failures.push(`${label}: races must be a unique subset of the race vocabulary`);
    if (!Array.isArray(entry.observations) || !entry.observations.length) {
      failures.push(`${label}: observations are required`);
      continue;
    }
    for (const observation of entry.observations) {
      const layer = observation?.layer;
      if (!LAYERS.includes(layer)) { failures.push(`${label}: unknown observation layer ${layer}`); continue; }
      if (observation.binding === null) {
        counts[layer].unbound++;
        if (!nonempty(observation.reason)) failures.push(`${label}/${layer}: unbound observation requires a reason`);
        if (observation.file !== undefined || observation.test !== undefined || observation.step !== undefined) failures.push(`${label}/${layer}: unbound observation cannot also declare a binding`);
        continue;
      }
      counts[layer].bound++;
      if (layer === 'real-ui-fixture') {
        if (!nonempty(observation.step) || !steps.has(observation.step)) failures.push(`${label}/${layer}: step ${JSON.stringify(observation.step)} is not in ${STEP_INDEX}`);
        continue;
      }
      if (!nonempty(observation.file) || !nonempty(observation.test)) {
        failures.push(`${label}/${layer}: a bound observation requires file and test`);
        continue;
      }
      const file = resolve(root, observation.file);
      const local = relative(root, file);
      if (isAbsolute(observation.file) || local === '..' || local.startsWith('../') || local.startsWith('..\\')) {
        failures.push(`${label}/${layer}: file must be inside the repository`);
        continue;
      }
      if (!sources.has(file)) {
        try { sources.set(file, statSync(file).isFile() ? testTitles(readFileSync(file, 'utf8')) : null); }
        catch { sources.set(file, null); }
      }
      const titles = sources.get(file);
      if (!titles) failures.push(`${label}/${layer}: file ${observation.file} does not exist or cannot be read`);
      else if (!titles.has(observation.test)) failures.push(`${label}/${layer}: test title ${JSON.stringify(observation.test)} is not present in ${observation.file}`);
    }
  }
  if (baseline !== null) {
    if (!Array.isArray(baseline.entries) || !Array.isArray(baseline.retired)) failures.push('base catalog requires entries and retired arrays');
    else for (const row of [...baseline.entries, ...baseline.retired]) {
      if (!ids.has(row.id)) failures.push(`${row.id}: removed without a recorded retirement`);
    }
  }
  return { failures, counts };
}

function main() {
  try {
    const args = process.argv.slice(2);
    if (args.length && (args.length !== 2 || args[0] !== '--base-ref' || !nonempty(args[1]))) throw new Error('usage: node scripts/ci/check-search-window-scenarios.mjs [--base-ref <ref>]');
    const root = process.cwd();
    const { base, catalog: baseline } = readBaseCatalog(root, args[1]);
    const { failures, counts } = checkSearchWindowScenarios({ root, baseline,
      catalog: JSON.parse(readFileSync(resolve(root, CATALOG), 'utf8')),
      stepIndex: JSON.parse(readFileSync(resolve(root, STEP_INDEX), 'utf8')) });
    for (const [layer, count] of Object.entries(counts)) console.log(`${layer}: ${count.bound} bound, ${count.unbound} unbound observations`);
    if (failures.length) throw new Error(failures.map(failure => `  - ${failure}`).join('\n'));
    console.log(`search-window-scenarios gate OK (merge base ${base.slice(0, 12)}). Bindings checked; execution and behavior are not verified here.`);
  } catch (error) {
    console.error(`search-window-scenarios gate FAILED:\n${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
