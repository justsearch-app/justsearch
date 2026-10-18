#!/usr/bin/env node
/**
 * Cross-platform, post-Node contributor bootstrap (tempdoc 899 D1).
 *
 * Node must already exist so this coordinator can run. On Windows,
 * bootstrap-node-win.ps1 remains the pre-Node entry point. This script validates
 * the repository's tool floors, installs every reviewed npm lock root, and makes
 * the Unix Gradle wrapper executable. It never installs host tools or edits a
 * shell profile.
 */
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const defaultRepoRoot = path.resolve(__dirname, '..', '..');
const {
  resolveJdkHome,
  javaExeIn,
  __test: { parseJavaMajor },
} = require('../dev/lib/resolve-jdk.cjs');

export const TOOL_FLOORS = Object.freeze({ node: 20, java: 24, pythonMajor: 3, pythonMinor: 13 });

export const NPM_LOCK_ROOTS = Object.freeze([
  '.',
  'modules/ui-web',
  'modules/shell',
  'packages/runtime-client',
  'scripts/wire-contract',
]);

export function parseNodeMajor(output) {
  const match = String(output ?? '').trim().match(/^v?(\d+)(?:\.\d+){0,2}$/);
  return match ? Number(match[1]) : null;
}

export function parsePythonVersion(output) {
  const match = String(output ?? '').trim().match(/^Python\s+(\d+)\.(\d+)(?:\.\d+)?$/i);
  return match ? { major: Number(match[1]), minor: Number(match[2]) } : null;
}

export function parseRustVersion(output) {
  const match = String(output ?? '').trim().match(/^rustc\s+(\d+)\.(\d+)(?:\.\d+)?/i);
  return match ? { major: Number(match[1]), minor: Number(match[2]) } : null;
}

export function validatePrerequisites({ nodeVersion, javaMajor, pythonVersion, rustVersion }) {
  const failures = [];
  const warnings = [];
  const nodeMajor = parseNodeMajor(nodeVersion);
  const python = parsePythonVersion(pythonVersion);
  const rust = rustVersion == null ? null : parseRustVersion(rustVersion);

  if (nodeMajor == null || nodeMajor < TOOL_FLOORS.node) {
    failures.push(`Node.js ${TOOL_FLOORS.node}+ is required (found ${nodeVersion || 'unreadable'}).`);
  }
  if (javaMajor == null || javaMajor < TOOL_FLOORS.java) {
    failures.push(`JDK ${TOOL_FLOORS.java}+ is required (found ${javaMajor ?? 'unreadable'}).`);
  }
  if (!python || python.major !== TOOL_FLOORS.pythonMajor || python.minor < TOOL_FLOORS.pythonMinor) {
    failures.push(`Python ${TOOL_FLOORS.pythonMajor}.${TOOL_FLOORS.pythonMinor}+ is required (found ${pythonVersion || 'unreadable'}).`);
  }
  if (rustVersion == null) {
    warnings.push('Rust is not installed; core Java/web contribution still works, but modules/shell development does not.');
  } else if (!rust) {
    warnings.push(`Rust was found but its version was unreadable (${rustVersion}).`);
  }

  return { ok: failures.length === 0, failures, warnings, nodeMajor, python, rust };
}

export function buildInstallPlan(repoRoot = defaultRepoRoot) {
  return NPM_LOCK_ROOTS.map((relativeRoot) => {
    const cwd = path.resolve(repoRoot, relativeRoot);
    for (const required of ['package.json', 'package-lock.json']) {
      if (!fs.existsSync(path.join(cwd, required))) {
        throw new Error(`Missing ${path.join(relativeRoot, required)}; refusing a non-lockfile install.`);
      }
    }
    return {
      relativeRoot,
      cwd,
      args: relativeRoot === 'packages/runtime-client' ? ['ci', '--ignore-scripts'] : ['ci'],
    };
  });
}

export function runProcess(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    env: options.env ?? process.env,
    encoding: 'utf8',
    stdio: options.stdio ?? 'pipe',
    shell: options.shell ?? false,
    windowsHide: true,
  });
  if (result.error) {
    throw new Error(`${command} could not start: ${result.error.message}`);
  }
  if (result.status !== 0) {
    const detail = String(result.stderr || result.stdout || '').trim();
    throw new Error(`${command} ${args.join(' ')} exited ${result.status}${detail ? `: ${detail}` : ''}`);
  }
  return `${result.stdout || ''}${result.stderr || ''}`.trim();
}

export const { resolveNpmInvocation } = require('../lib/tool-invoker.cjs');

export function probeSupportedPython(candidates, runner = runProcess) {
  let firstReadable = null;
  for (const [command, args] of candidates) {
    try {
      const output = runner(command, args);
      const candidate = { command, output };
      firstReadable ??= candidate;
      const version = parsePythonVersion(output);
      if (version?.major === TOOL_FLOORS.pythonMajor
          && version.minor >= TOOL_FLOORS.pythonMinor) {
        return candidate;
      }
    } catch {
      // Try the next platform spelling. The caller reports one actionable failure.
    }
  }
  return firstReadable;
}

function probeFirst(candidates, runner = runProcess) {
  for (const [command, args] of candidates) {
    try {
      return { command, output: runner(command, args) };
    } catch {
      // Try the next platform spelling. The caller reports one actionable failure.
    }
  }
  return null;
}

export function validateGradleJavaHome({ ambientJavaHome, resolvedJdkHome, inspect }) {
  if (!ambientJavaHome) return;
  let ambientMajor = null;
  try {
    ambientMajor = parseJavaMajor(inspect(javaExeIn(ambientJavaHome)));
  } catch {
    // Treat an unusable JAVA_HOME the same as an obsolete one: Gradle will prefer it and fail.
  }
  if (ambientMajor == null || ambientMajor < TOOL_FLOORS.java) {
    throw new Error(
      `JAVA_HOME points at ${ambientJavaHome}, which is not a usable JDK ${TOOL_FLOORS.java}+. `
      + `Gradle will prefer it over the JDK ${TOOL_FLOORS.java}+ found at ${resolvedJdkHome}. `
      + 'Point JAVA_HOME at that resolved JDK, then rerun bootstrap.',
    );
  }
}

export function applySetup({
  check,
  platform = process.platform,
  gradlePath,
  installPlan,
  npmInvocation = resolveNpmInvocation({ platform }),
  install = (entry) => runProcess(npmInvocation.command, [...npmInvocation.prefixArgs, ...entry.args], {
    cwd: entry.cwd,
    stdio: 'inherit',
  }),
  chmod = fs.chmodSync,
  access = fs.accessSync,
  log = console.error,
}) {
  if (!fs.existsSync(gradlePath)) throw new Error(`Gradle wrapper is missing: ${gradlePath}`);

  if (platform !== 'win32') {
    if (check) {
      try {
        access(gradlePath, fs.constants.X_OK);
      } catch {
        throw new Error(`Gradle wrapper is not executable: ${gradlePath}. Run chmod +x gradlew.`);
      }
    } else {
      chmod(gradlePath, 0o755);
    }
  }

  if (check) {
    log(`[bootstrap] check-only: ${installPlan.length} lockfile roots validated; no files changed`);
    return;
  }
  for (const entry of installPlan) {
    log(`[bootstrap] npm ${entry.args.join(' ')} (${entry.relativeRoot})`);
    install(entry);
  }
}

export function parseArgs(argv) {
  const known = new Set(['--check', '--help']);
  const unknown = argv.filter((arg) => !known.has(arg));
  if (unknown.length) throw new Error(`Unknown option: ${unknown[0]}`);
  return { check: argv.includes('--check'), help: argv.includes('--help') };
}

export async function main(argv = process.argv.slice(2)) {
  const args = parseArgs(argv);
  if (args.help) {
    console.log('Usage: node scripts/setup/bootstrap.mjs [--check]');
    console.log('  --check  validate tools, lockfiles, and wrapper state without changing files');
    return;
  }

  let jdkHome;
  try {
    jdkHome = resolveJdkHome();
  } catch (error) {
    throw new Error(error.message);
  }
  validateGradleJavaHome({
    ambientJavaHome: process.env.JAVA_HOME,
    resolvedJdkHome: jdkHome,
    inspect: (javaExe) => runProcess(javaExe, ['-version']),
  });
  const javaVersionOutput = runProcess(javaExeIn(jdkHome), ['-version']);
  const javaMajor = parseJavaMajor(javaVersionOutput);
  const pythonProbe = probeSupportedPython(process.platform === 'win32'
    ? [['python', ['--version']], ['py', ['-3.13', '--version']]]
    : [['python3', ['--version']], ['python', ['--version']]]);
  const rustProbe = probeFirst([['rustc', ['--version']]]);

  const report = validatePrerequisites({
    nodeVersion: process.version,
    javaMajor,
    pythonVersion: pythonProbe?.output,
    rustVersion: rustProbe?.output ?? null,
  });
  for (const warning of report.warnings) console.error(`[bootstrap] NOTE: ${warning}`);
  if (!report.ok) throw new Error(report.failures[0]);

  // Confirm npm exists in check mode too; a Node installation without npm cannot bootstrap this repo.
  const npmInvocation = resolveNpmInvocation();
  const npmVersion = runProcess(npmInvocation.command, [...npmInvocation.prefixArgs, '--version']);
  const installPlan = buildInstallPlan(defaultRepoRoot);
  const gradlePath = path.join(defaultRepoRoot, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
  applySetup({ check: args.check, gradlePath, installPlan, npmInvocation });

  console.log(`[bootstrap] ready — Node ${process.version}, JDK ${javaMajor}, ${pythonProbe.output}, npm ${npmVersion}`);
  if (rustProbe) console.log(`[bootstrap] ${rustProbe.output}`);
  if (!args.check) console.log('[bootstrap] dependencies installed. Next: node scripts/dev/doctor.mjs');
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  main().catch((error) => {
    console.error(`[bootstrap] ERROR: ${error.message}`);
    process.exitCode = 1;
  });
}
