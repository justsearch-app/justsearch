/**
 * Frontend helpers that only tests import — tempdoc 966 D1 scope.
 *
 * A file under a frontend module's src/ that no production file imports is test infrastructure even
 * though its name is not *.test.* (for example modules/ui-web/src/shell-v0/plugin-api/testHostApi.ts,
 * which about 15 tests import). An edit to it moves expectations as surely as an edit to a test, so
 * the gate flags it. The set is a committed list, gates/test-intent/test-support-paths.v1.json, which
 * is itself a watched baseline: an edit to the list is flagged like any other baseline change.
 *
 * The list is seeded and kept honest by a static import scan (`scanTestOnlyImports`). The gate's unit
 * test recomputes the scan on the repository and fails when a file the scan finds is missing from the
 * list, so a new test-only helper cannot stay unlisted. Extra list entries are allowed (they only flag
 * more).
 *
 * What the scan reads, per frontend module (a module with package.json and src/): every tracked or
 * untracked, non-ignored JS/TS file of the module, comments removed, for
 *   import ... from '<s>' / import '<s>' / export ... from '<s>' / import type ... from '<s>'
 *   import('<s>') / require('<s>') / vi.mock('<s>') and friends / new URL('<s>', import.meta.url)
 *   import.meta.glob('<glob>') (expanded against the module's files)
 * Relative specifiers and the `@/` alias (tsconfig paths) are resolved with TypeScript's bundler rules
 * (`./x.js` may name x.ts or x.tsx; a directory names its index). Bare package specifiers are ignored.
 *
 * A src file is test-only when some file imports it, it is not itself test-scope (scope.mjs), and no
 * production root reaches it through imports that do not pass through test-scope files. Production
 * roots are what the module's HTML pages load (src/href), every non-test file of the module outside
 * src/ (configs, scripts), and every non-test src file nothing imports (an entry point loaded some
 * other way). So src/main.jsx, which index.html loads and one test imports, stays production with
 * everything it imports; helpers that only test-only helpers import, and cycles among them, count.
 *
 * Known limit: a string that is not an import but contains import syntax in a production file adds a
 * production edge, which can only hide a helper (the list then misses it), never add a production
 * file. A reference in a form the scan does not read (a config naming a src path as a string) is not
 * an edge; such a file can appear as test-only only if tests import it too.
 */

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

import { scanBlocks } from './build-config.mjs';
import { classifyPath } from './scope.mjs';

export const TEST_SUPPORT_PATHS_FILE = 'gates/test-intent/test-support-paths.v1.json';
export const TEST_SUPPORT_SCHEMA = 'test-intent-test-support-paths.v1';

const SCANNED = /\.(js|jsx|ts|tsx|mjs|cjs|mts|cts)$/;
/** Files whose text the scan reads: JS/TS sources, plus HTML pages for their entry points. */
const READ = /\.(js|jsx|ts|tsx|mjs|cjs|mts|cts|html?)$/;

/** The listed paths (throws when the list is malformed). */
export function parseTestSupportList(text) {
  const doc = JSON.parse(text);
  if (doc?.schema !== TEST_SUPPORT_SCHEMA || !Array.isArray(doc.paths) || doc.paths.some((p) => typeof p !== 'string')) {
    throw new Error(`${TEST_SUPPORT_PATHS_FILE}: expected { "schema": "${TEST_SUPPORT_SCHEMA}", "paths": [string] }`);
  }
  return new Set(doc.paths.map((p) => p.replaceAll('\\', '/')));
}

/** The list's text for a set of paths (stable order). */
export function renderTestSupportList(paths, description) {
  return JSON.stringify({ schema: TEST_SUPPORT_SCHEMA, description, paths: [...paths].sort() }, null, 2) + '\n';
}

/** Module-relative specifiers a JS/TS source imports, comments removed. */
export function importSpecifiers(text) {
  const code = scanBlocks(text, { js: true }).lines.map((l) => l.code).join('\n');
  const out = [];
  const add = (re, glob = false) => {
    for (const m of code.matchAll(re)) out.push({ spec: m[1], glob });
  };
  add(/(?:^|[^\w$.])(?:import|export)\s+(?:type\s+)?(?:[\w*{}\s,$]+?\s+from\s*)?['"]([^'"\n]+)['"]/g);
  add(/(?:^|[^\w$.])import\s*\(\s*['"]([^'"\n]+)['"]\s*[,)]/g);
  add(/(?:^|[^\w$.])require\s*\(\s*['"]([^'"\n]+)['"]\s*\)/g);
  add(/\bvi\s*\.\s*(?:mock|doMock|unmock|doUnmock|importActual|importMock)\s*(?:<[^>]*>)?\s*\(\s*['"]([^'"\n]+)['"]/g);
  add(/\bnew\s+URL\s*\(\s*['"]([^'"\n]+)['"]\s*,\s*import\.meta\.url/g);
  add(/\bimport\.meta\.glob\s*(?:<[^>]*>)?\s*\(\s*['"]([^'"\n]+)['"]/g, true);
  return out;
}

/** A path glob (`*`, `**`, `?`, `{a,b}`) as an anchored RegExp. */
export function globToRegExp(glob) {
  let re = '';
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === '*' && glob[i + 1] === '*') {
      re += glob[i + 2] === '/' ? '(?:.*/)?' : '.*';
      i += glob[i + 2] === '/' ? 2 : 1;
    } else if (c === '*') re += '[^/]*';
    else if (c === '?') re += '[^/]';
    else if (c === '{') {
      const end = glob.indexOf('}', i);
      re += `(?:${glob.slice(i + 1, end).split(',').map((s) => s.replace(/[.+^$()|[\]\\]/g, '\\$&')).join('|')})`;
      i = end;
    } else re += c.replace(/[.+^$()|[\]\\]/g, '\\$&');
  }
  return new RegExp(`^${re}$`);
}

/** Resolve one specifier of `from` against the module's files; returns repo-relative paths. */
export function resolveSpecifier(from, { spec, glob }, files, srcRoot) {
  let target;
  if (spec.startsWith('./') || spec.startsWith('../')) target = path.posix.join(path.posix.dirname(from), spec);
  else if (spec.startsWith('@/')) target = `${srcRoot}/${spec.slice(2)}`;
  else if (spec.startsWith('/src/')) target = `${srcRoot}${spec.slice(4)}`;
  else return [];
  target = target.split(/[?#]/)[0];
  if (glob) {
    const re = globToRegExp(target);
    return [...files].filter((f) => re.test(f));
  }
  const cands = [target];
  const ext = /\.(js|jsx|mjs|cjs)$/.exec(target);
  if (ext) {
    const stem = target.slice(0, -ext[0].length);
    if (ext[1] === 'js') cands.push(`${stem}.ts`, `${stem}.tsx`);
    if (ext[1] === 'jsx') cands.push(`${stem}.tsx`);
    if (ext[1] === 'mjs') cands.push(`${stem}.mts`);
    if (ext[1] === 'cjs') cands.push(`${stem}.cts`);
  }
  for (const e of ['.ts', '.tsx', '.js', '.jsx', '.mjs', '.mts', '.cjs', '.cts', '.json']) cands.push(target + e);
  for (const e of ['ts', 'tsx', 'js', 'jsx', 'mjs']) cands.push(`${target}/index.${e}`);
  const hit = cands.find((c) => files.has(c));
  return hit ? [hit] : [];
}

/** Frontend modules: modules/<m> with a package.json and a src/ directory. */
export function frontendModules(repoRoot) {
  const dir = path.join(repoRoot, 'modules');
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true })
    .filter((d) => d.isDirectory()
      && fs.existsSync(path.join(dir, d.name, 'package.json'))
      && fs.existsSync(path.join(dir, d.name, 'src')))
    .map((d) => `modules/${d.name}`)
    .sort();
}

/**
 * Test-only files from an in-memory module: `files` maps repo-relative path -> text (null for files
 * that are not scanned as importers, such as JSON and CSS).
 */
export function testOnlyFiles(files, moduleRoot) {
  const srcRoot = `${moduleRoot}/src`;
  const all = new Set(files.keys());
  const importers = new Map();
  for (const [from, text] of files) {
    if (text === null || !SCANNED.test(from)) continue;
    for (const s of importSpecifiers(text)) {
      for (const to of resolveSpecifier(from, s, all, srcRoot)) {
        if (to === from) continue;
        if (!importers.has(to)) importers.set(to, new Set());
        importers.get(to).add(from);
      }
    }
  }
  const isTestScope = (p) => classifyPath(p) !== null;
  // Production roots: what HTML pages load, every non-test file of the module outside src/ (configs,
  // scripts), and every non-test src file nothing imports (an entry point loaded some other way).
  const roots = new Set();
  for (const [rel, text] of files) {
    if (isTestScope(rel)) continue;
    if (/\.html?$/.test(rel) && text !== null) {
      for (const m of text.matchAll(/\b(?:src|href)\s*=\s*["']([^"']+)["']/g)) {
        for (const hit of resolveSpecifier(rel, { spec: m[1].startsWith('/') ? m[1] : `./${m[1].replace(/^\.\//, '')}`, glob: false }, all, srcRoot)) roots.add(hit);
      }
    }
    if (!rel.startsWith(`${srcRoot}/`) || !importers.has(rel)) roots.add(rel);
  }
  const imports = new Map();
  for (const [to, froms] of importers) for (const from of froms) {
    if (!imports.has(from)) imports.set(from, new Set());
    imports.get(from).add(to);
  }
  const reachable = new Set();
  const queue = [...roots];
  while (queue.length) {
    const f = queue.pop();
    if (reachable.has(f) || isTestScope(f)) continue;
    reachable.add(f);
    for (const t of imports.get(f) ?? []) queue.push(t);
  }
  return [...importers.keys()]
    .filter((p) => p.startsWith(`${srcRoot}/`) && !isTestScope(p) && !reachable.has(p))
    .sort();
}

/** Scan the repository's frontend modules (working tree: tracked plus untracked, non-ignored). */
export function scanTestOnlyImports(repoRoot) {
  const result = [];
  for (const mod of frontendModules(repoRoot)) {
    const listed = execFileSync('git', ['ls-files', '-co', '--exclude-standard', '-z', '--', mod], {
      cwd: repoRoot, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024,
    }).split('\0').filter((p) => p && !p.split('/').includes('node_modules') && !p.startsWith(`${mod}/dist/`));
    const files = new Map();
    for (const rel of listed) {
      const abs = path.join(repoRoot, rel);
      if (!fs.existsSync(abs)) continue;
      files.set(rel, READ.test(rel) ? fs.readFileSync(abs, 'utf8') : null);
    }
    result.push(...testOnlyFiles(files, mod));
  }
  return result.sort();
}
