#!/usr/bin/env node
/**
 * Drift gate for scripts/jseval/requirements.lock.txt (tempdoc 792 §6).
 *
 * scripts/jseval/pyproject.toml declares floor-only base dependencies
 * (`dependencies = [...]` under `[project]`) with no upper bounds. The lock file pins the
 * exact version CI actually installs for each of those direct dependencies. The two can
 * silently disagree in exactly two ways this gate catches:
 *
 *   1. A new direct dependency is added to pyproject.toml's `dependencies` array but the
 *      lock file isn't regenerated -> the new package has no pin at all.
 *   2. An existing dependency's floor (`>=x.y.z`) is raised in pyproject.toml past what
 *      the lock currently pins -> the lock now pins a version pyproject.toml itself
 *      forbids, so `pip install -c` would have to violate one of the two files.
 *
 * This does NOT re-resolve the full transitive closure (that needs a real resolver
 * against the target platform/interpreter, i.e. regeneration) — it only checks the base
 * direct dependencies pyproject.toml declares against the lock's pins, which is exactly
 * the class of drift that's cheap to catch without a network resolve.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function repoRootFromCwd() {
  const markers = ['settings.gradle.kts', 'build.gradle.kts', '.git'];
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (markers.some((marker) => fs.existsSync(path.join(dir, marker)))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
  }
  return process.cwd();
}

const REPO_ROOT = repoRootFromCwd();
const PACKAGE_NAME = process.argv[2] ?? 'jseval';
if (!['jseval', 'jsharness'].includes(PACKAGE_NAME)) throw new Error('expected jseval or jsharness');
const JSEVAL_DIR = path.join(REPO_ROOT, 'scripts', PACKAGE_NAME);
const PYPROJECT_PATH = path.join(JSEVAL_DIR, 'pyproject.toml');
const LOCK_PATH = path.join(JSEVAL_DIR, 'requirements.lock.txt');

const REGEN_HINT = `
Regenerate scripts/jseval/requirements.lock.txt (see the file's own header for full
rationale) with pip's cross-platform dry-run resolver, targeting CI's interpreter and
platform (CPython 3.13 / manylinux2014_x86_64 — check .github/workflows/ci.yml's
\`public-claims\` job for the current pins):

  python -m pip install --dry-run --ignore-installed --report report.json \\
    --python-version 3.13 --implementation cp --abi cp313 \\
    --platform manylinux2014_x86_64 --only-binary=:all: \\
    -e scripts/jsharness -e scripts/jseval

Then extract name==version pairs from report.json's install[].metadata (dropping
jseval itself), and re-add the \`colorama==<version>; platform_system == "Windows"\`
marker line by hand — pip's target-platform override does not retarget
platform_system/os_name/sys_platform markers, only python_version-family ones, so that
one entry needs a manual, metadata-verified marker rather than trusting the dry-run's
flattened install list. Normalize package names (PyPI project name, case as published).
`.trim();

function fail(message) {
  console.error(`check-jseval-lock: FAIL - ${message}`);
  console.error();
  console.error(REGEN_HINT);
  process.exit(1);
}

function normalizeName(name) {
  // PEP 503 normalization: case-insensitive, '-'/'_'/'.' runs treated as equivalent.
  return name.toLowerCase().replace(/[-_.]+/g, '-');
}

function parseVersion(version) {
  return version
    .split(/[.+]/)
    .map((part) => {
      const n = Number.parseInt(part, 10);
      return Number.isNaN(n) ? 0 : n;
    });
}

// Returns negative/zero/positive like a comparator: a <=> b.
function compareVersions(a, b) {
  const pa = parseVersion(a);
  const pb = parseVersion(b);
  const len = Math.max(pa.length, pb.length);
  for (let i = 0; i < len; i++) {
    const diff = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (diff !== 0) return diff;
  }
  return 0;
}

function extractDependenciesArray(pyprojectText) {
  const marker = /^dependencies\s*=\s*\[/m.exec(pyprojectText);
  if (!marker) fail(`could not find a top-level "dependencies = [" array in ${PYPROJECT_PATH}`);
  const start = marker.index + marker[0].length;
  const end = pyprojectText.indexOf(']', start);
  if (end === -1) fail(`unterminated "dependencies" array in ${PYPROJECT_PATH}`);
  return pyprojectText.slice(start, end);
}

function parseDirectDependencies(pyprojectText) {
  const ui = PACKAGE_NAME === 'jsharness' ? (/^ui\s*=\s*\[([^\]]*)\]/m.exec(pyprojectText)?.[1] ?? '') : '';
  const arrayBody = extractDependenciesArray(pyprojectText) + ui;
  const entryPattern = /"([^"]+)"|'([^']+)'/g;
  const deps = [];
  let match;
  while ((match = entryPattern.exec(arrayBody)) !== null) {
    const raw = (match[1] ?? match[2]).trim();
    const specMatch = /^([A-Za-z0-9][A-Za-z0-9._-]*)\s*(>=|==|~=)\s*([0-9][0-9A-Za-z.+-]*)\s*$/.exec(raw);
    if (!specMatch) {
      fail(
        `unrecognized dependency spec "${raw}" in pyproject.toml — this checker only ` +
          'understands simple "name>=x.y.z" / "name==x.y.z" / "name~=x.y.z" floors ' +
          '(no extras, no environment markers, no version ranges with commas). Extend ' +
          'check-jseval-lock.mjs if a new spec shape is intentional.'
      );
    }
    const [, name, operator, version] = specMatch;
    deps.push({ name, operator, version, raw });
  }
  if (deps.length === 0) fail(`parsed zero dependencies out of pyproject.toml's "dependencies" array`);
  return deps;
}

function parseLockFile(lockText) {
  const pins = new Map();
  for (const rawLine of lockText.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    // Constraints-file line: "name==version" optionally followed by "; <marker>".
    const [specPart] = line.split(';', 1);
    const specMatch = /^([A-Za-z0-9][A-Za-z0-9._-]*)\s*==\s*([0-9][0-9A-Za-z.+-]*)\s*$/.exec(
      specPart.trim()
    );
    if (!specMatch) {
      fail(
        `unrecognized line in requirements.lock.txt: "${rawLine}" — expected ` +
          '"name==version" (optionally "; <marker>") or a "#" comment.'
      );
    }
    const [, name, version] = specMatch;
    const key = normalizeName(name);
    if (pins.has(key)) fail(`duplicate pin for "${name}" in requirements.lock.txt`);
    pins.set(key, { name, version });
  }
  return pins;
}

function main() {
  if (!fs.existsSync(PYPROJECT_PATH)) fail(`missing ${PYPROJECT_PATH}`);
  if (!fs.existsSync(LOCK_PATH)) {
    fail(`missing ${LOCK_PATH} — scripts/jseval has no dependency lock at all.`);
  }

  const pyprojectText = fs.readFileSync(PYPROJECT_PATH, 'utf8');
  const lockText = fs.readFileSync(LOCK_PATH, 'utf8');

  const directDeps = parseDirectDependencies(pyprojectText);
  const pins = parseLockFile(lockText);

  const problems = [];
  for (const dep of directDeps) {
    const key = normalizeName(dep.name);
    const pin = pins.get(key);
    if (!pin) {
      problems.push(
        `"${dep.raw}" is a direct dependency in pyproject.toml but has no pin in ` +
          'requirements.lock.txt (a dependency was added without regenerating the lock).'
      );
      continue;
    }
    if (dep.operator === '>=' || dep.operator === '~=') {
      if (compareVersions(pin.version, dep.version) < 0) {
        problems.push(
          `"${dep.name}" pyproject.toml floor is ${dep.operator}${dep.version} but ` +
            `requirements.lock.txt pins ${pin.version}, which is BELOW that floor (the ` +
            'floor was raised without regenerating the lock).'
        );
      }
    } else if (dep.operator === '==' && pin.version !== dep.version) {
      problems.push(
        `"${dep.name}" pyproject.toml pins exactly ${dep.version} but ` +
          `requirements.lock.txt pins ${pin.version} — these must match exactly.`
      );
    }
  }

  if (problems.length > 0) {
    fail(problems.join('\n'));
  }

  console.log(
    `check-jseval-lock: OK - ${directDeps.length} direct dependencies in pyproject.toml ` +
      `all satisfied by requirements.lock.txt's ${pins.size} pins.`
  );
}

main();
