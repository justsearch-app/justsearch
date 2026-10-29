#!/usr/bin/env node
/**
 * Check bindings and retirements; bound tests need a complete literal title and function body,
 * without disabling modifiers, disabling options (skip, fails and the to-do marker), non-literal options or conditional forms
 * on the test or any enclosing suite.
 * This does not establish execution: surrounding control flow (e.g. `if (false)`), runtime skips,
 * config include/exclude and name filters are out of scope.
 * Run from the repository root; --base-ref defaults to origin/main. Missing history fails closed.
 */
import { readFileSync, statSync } from 'node:fs';
import { resolve, relative, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import ts from 'typescript';

export const CATALOG = 'governance/search-window-scenarios.v1.json';
const STEP_INDEX = 'scripts/jseval/jseval/ui_step_index.json';
export const LAYERS = ['controller', 'view', 'real-ui-fixture', 'live-backend'];
const RACES = ['concurrent-or-identical-send', 'late-response', 'second-conversation-or-tab', 'reload', 'screen-switch'];
const nonempty = value => typeof value === 'string' && value.trim().length > 0;

/** Literal test declarations only. Parse syntax without executing repository code. */
export function testTitles(source, filename = 'bindings.test.ts') {
  const tree = ts.createSourceFile(filename, source, ts.ScriptTarget.Latest, true);
  if (tree.parseDiagnostics.length) return new Set();
  const suites = new Set(['describe', 'suite', 'xdescribe', 'xsuite']);
  const tests = new Set(['it', 'test', 'xit', 'xtest']);
  const factories = new Set(['each', 'for', 'skipIf', 'runIf']);
  const allowed = new Set(['only', 'concurrent', 'sequential', 'each']);

  function unwrap(expression) {
    while (expression && (ts.isParenthesizedExpression(expression) || ts.isAsExpression(expression)
      || ts.isTypeAssertionExpression(expression) || ts.isNonNullExpression(expression)
      || ts.isSatisfiesExpression(expression))) expression = expression.expression;
    return expression;
  }

  function enabledOptions(expression) {
    if (!expression || !ts.isObjectLiteralExpression(expression)) return false;
    return expression.properties.every(property => {
      // Spreads, accessors and dynamic keys can hide disabling options.
      if (!ts.isPropertyAssignment(property)) return false;
      const name = ts.isComputedPropertyName(property.name) ? unwrap(property.name.expression) : property.name;
      if (!ts.isIdentifier(name) && !ts.isStringLiteral(name) && !ts.isNoSubstitutionTemplateLiteral(name)) return false;
      if (ts.isComputedPropertyName(property.name) && ts.isIdentifier(name)) return false;
      if (name.text === '__proto__') return false;
      return !['skip', 'todo', 'fails'].includes(name.text)
        || unwrap(property.initializer).kind === ts.SyntaxKind.FalseKeyword;
    });
  }

  function hasBody(expression) {
    return expression && (ts.isArrowFunction(expression) || ts.isFunctionExpression(expression)) && !!expression.body;
  }

  function bindableArguments(args) {
    const [, second, third] = args.map(unwrap);
    if (args.length > 3) return false;
    // Vitest 4 accepts (title, body, timeout) or (title, options, body).
    if (hasBody(second)) return !third || ts.isNumericLiteral(third);
    return enabledOptions(second) && hasBody(third);
  }

  // Read the callee chain, including factory calls such as test.skip.each(rows).
  function declaration(expression) {
    expression = unwrap(expression);
    if (ts.isIdentifier(expression)) return { name: expression.text, modifiers: [] };
    if (ts.isPropertyAccessExpression(expression) || ts.isElementAccessExpression(expression)) {
      const parent = declaration(expression.expression);
      if (!parent) return null;
      const property = ts.isPropertyAccessExpression(expression) ? expression.name.text
        : ts.isStringLiteral(expression.argumentExpression) || ts.isNoSubstitutionTemplateLiteral(expression.argumentExpression)
          ? expression.argumentExpression.text : null;
      return { ...parent, modifiers: [...parent.modifiers, { name: property, called: false }] };
    }
    if (ts.isCallExpression(expression) || ts.isTaggedTemplateExpression(expression)) {
      const parent = declaration(ts.isCallExpression(expression) ? expression.expression : expression.tag);
      const modifier = parent?.modifiers.at(-1);
      if (!modifier || modifier.called) return null;
      if (ts.isTaggedTemplateExpression(expression) && modifier.name !== 'each') return null;
      modifier.called = true;
      return parent;
    }
    return null;
  }

  const titles = new Set();
  function visit(node, blocked = false) {
    if (ts.isCallExpression(node)) {
      const declared = declaration(node.expression);
      if (declared && (suites.has(declared.name) || tests.has(declared.name))) {
        const disabled = !bindableArguments(node.arguments) || declared.name.startsWith('x') || declared.modifiers.some(modifier =>
          !allowed.has(modifier.name) || factories.has(modifier.name) !== modifier.called);
        if (suites.has(declared.name)) blocked ||= disabled;
        else if (!blocked && !disabled) {
          const title = node.arguments[0];
          if (title && (ts.isStringLiteral(title) || ts.isNoSubstitutionTemplateLiteral(title))) titles.add(title.text);
        }
      }
    }
    ts.forEachChild(node, child => visit(child, blocked));
  }
  visit(tree);
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
        if (observation.file !== undefined || observation.test !== undefined) failures.push(`${label}/${layer}: a step binding cannot declare file or test`);
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
        try { sources.set(file, statSync(file).isFile() ? testTitles(readFileSync(file, 'utf8'), file) : null); }
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
