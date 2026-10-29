#!/usr/bin/env node

import { execFileSync, spawnSync } from 'node:child_process';
import { accessSync, constants, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseProjectDependencies, parseSettings } from '../architecture/module-deps.mjs';

// Keep aligned with "Integration tests (module tier)" in .github/workflows/ci.yml.
const INTEGRATION_MODULES = ['app-api', 'app-services', 'indexing', 'reranker', 'ui'];

export function selectIntegrationTasks({ changedPaths, settings, buildFiles }) {
  const tasks = (modules) => modules.map((module) => `:modules:${module}:integrationTest`);
  if (changedPaths.some((file) => /^(?:(?:settings|build)\.gradle(?:\.kts)?|gradle\.(?:properties|lockfile)|gradlew(?:\.bat)?)$/.test(file)
      || file.startsWith('build-logic/') || file.startsWith('gradle/')
      // Inputs every module's tests read: shared catalogs and this run's own exclusion list.
      || file.startsWith('SSOT/') || file === 'scripts/dev/prepush-excluded-tests.tsv')) {
    return tasks(INTEGRATION_MODULES);
  }
  const modules = parseSettings(settings).filter((module) => module.startsWith(':modules:'));
  const reached = new Set(modules.filter((module) => {
    const directory = `${module.slice(1).replaceAll(':', '/')}/`;
    // Any file in the module can change what its tests see (sources, build file, lockfile,
    // properties, resources), except Markdown documentation outside its source sets.
    return changedPaths.some((file) => file.startsWith(directory)
      && (!file.endsWith('.md') || file.startsWith(`${directory}src/`)));
  }));
  if (reached.size === 0) return [];
  const dependents = new Map();
  for (const module of modules) {
    const buildPath = `${module.slice(1).replaceAll(':', '/')}/build.gradle.kts`;
    if (typeof buildFiles[buildPath] !== 'string') throw new Error(`Missing module build file: ${buildPath}`);
    const { all } = parseProjectDependencies(buildFiles[buildPath]);
    for (const dependency of all) {
      const dependencyPath = `:modules:${dependency}`;
      if (!dependents.has(dependencyPath)) dependents.set(dependencyPath, new Set());
      dependents.get(dependencyPath).add(module);
    }
  }
  const pending = [...reached];
  for (const module of pending) {
    for (const dependent of dependents.get(module) || []) {
      if (!reached.has(dependent)) {
        reached.add(dependent);
        pending.push(dependent);
      }
    }
  }
  return tasks(INTEGRATION_MODULES.filter((module) => reached.has(`:modules:${module}`)));
}

export function changedPathsSinceMain(root) {
  const git = (args) => execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  const base = git(['merge-base', 'HEAD', 'origin/main']).trim();
  // Disabling rename detection lists both the old and new paths, including cross-module moves.
  return git(['diff', '--name-only', '--no-renames', '-z', base, '--']).split('\0').filter(Boolean);
}

function main(flags) {
  const unknown = flags.find((flag) => !['--unreferenced', '--dry-run'].includes(flag));
  if (unknown !== undefined) {
    console.error('Usage: node scripts/dev/java-prepush.mjs [--unreferenced] [--dry-run]');
    process.exitCode = 1;
    return;
  }
  const root = fileURLToPath(new URL('../../', import.meta.url));
  const settings = readFileSync(path.join(root, 'settings.gradle.kts'), 'utf8');
  const buildFiles = Object.fromEntries(parseSettings(settings)
    .filter((module) => module.startsWith(':modules:'))
    .map((module) => {
      const buildPath = `${module.slice(1).replaceAll(':', '/')}/build.gradle.kts`;
      return [buildPath, readFileSync(path.join(root, buildPath), 'utf8')];
    }));
  const integrationTasks = selectIntegrationTasks({ changedPaths: changedPathsSinceMain(root), settings, buildFiles });
  const windows = process.platform === 'win32';
  const extensions = windows ? (process.env.PATHEXT || '.COM;.EXE;.BAT;.CMD').split(';') : [''];
  const slotAvailable = (process.env.PATH || '').split(path.delimiter).some((directory) =>
    extensions.some((extension) => {
      const candidate = path.resolve(root, directory, `js-gradle-slot${extension}`);
      try {
        accessSync(candidate, windows ? constants.F_OK : constants.X_OK);
        return statSync(candidate).isFile();
      } catch { return false; }
    }));
  const gradlew = windows ? 'gradlew.bat' : './gradlew';
  const command = slotAvailable ? 'js-gradle-slot' : gradlew;
  const args = [
    ...(slotAvailable ? [gradlew] : []), 'compileTestJava', 'compileIntegrationTestJava', 'pmdAll', '--continue',
    '-PskipErrorProneTests=false', '-PskipWebBuild=true',
    ...integrationTasks,
  ];
  if (flags.includes('--unreferenced')) {
    args.push(':modules:app-launcher:test', '--tests', '*UnreferencedCodeTest');
  }
  if (!slotAvailable) console.error('java-prepush: js-gradle-slot was not found on PATH; running the Gradle wrapper directly.');
  if (flags.includes('--dry-run')) {
    const quote = windows ? '"' : "'";
    const environment = windows ? 'set "JUSTSEARCH_PREPUSH=true" &&' : 'JUSTSEARCH_PREPUSH=true';
    console.log(`${environment} ${command} ${args.map((arg) => arg.includes('*') ? `${quote}${arg}${quote}` : arg).join(' ')}`);
  } else {
    const result = spawnSync(command, args, {
      cwd: root,
      stdio: 'inherit',
      shell: windows,
      env: { ...process.env, JUSTSEARCH_PREPUSH: 'true' },
    });
    if (result.error) console.error(`java-prepush: ${result.error.message}`);
    process.exitCode = result.status ?? 1;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    console.error(`java-prepush: ${error.message}`);
    process.exitCode = 1;
  }
}
