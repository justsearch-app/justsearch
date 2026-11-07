/**
 * Protobuf Axis-6 enforcer: wraps `buf breaking`.
 *
 * Slice 3a-1-8f §A.3 (LFS-skip env), §A.16 (shallow-clone precondition).
 *
 * Empirical (§A.10 dry-run, 2026-05-07): `buf breaking` exit codes:
 *   0   — no breaks
 *   100 — breaks detected
 *   1   — usage / config error (e.g., missing baseline ref)
 *   2   — internal error
 *
 * Output format (per probe, FILE breaking-rule level):
 *   <relative-proto-path>:<line>:<col>:<message>
 */

import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, copyFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { tmpdir, platform } from 'node:os';
import { randomBytes } from 'node:crypto';

// Resolve the buf binary by platform. Windows ships an .exe; Unix uses the
// raw binary. The npm wrapper at .bin/buf is a JS launcher that's not
// directly invokable via execFileSync on Windows (would need shell:true,
// which we avoid for safety). Going to the platform-specific binary
// directly is more robust.
const BUF_BINARY_RELATIVE = (() => {
  const isWin = platform() === 'win32';
  return isWin
    ? 'scripts/wire-contract/node_modules/@bufbuild/buf-win32-x64/bin/buf.exe'
    : 'scripts/wire-contract/node_modules/@bufbuild/buf/bin/buf';
})();
const BUF_FALLBACK_RELATIVE = 'scripts/wire-contract/node_modules/.bin/buf';

/**
 * @param {{specDir: string, format: string, _selfTest?: object}} target
 * @param {{repoRoot: string, baselineRef: string, fixtureMode?: boolean}} options
 * @returns {Promise<{toolVersion: string, findings: Array<{ruleId: string, level: string, message: string, uri?: string, startLine?: number}>}>}
 */
export async function runProtobufBufBreaking(target, options) {
  const { repoRoot, baselineRef, fixtureMode } = options;
  let bufPath = resolve(repoRoot, BUF_BINARY_RELATIVE);
  if (!existsSync(bufPath)) {
    // Fallback to the npm .bin launcher (works on Unix; on Windows it's a
    // JS file that needs `node` invocation — out of scope for V1).
    bufPath = resolve(repoRoot, BUF_FALLBACK_RELATIVE);
  }

  if (!existsSync(bufPath)) {
    return {
      toolVersion: 'unknown',
      findings: [
        {
          ruleId: 'contract-governance/buf-cli-missing',
          level: 'error',
          message: `buf CLI not found at ${BUF_BINARY_RELATIVE} or ${BUF_FALLBACK_RELATIVE}; run 'npm install' in scripts/wire-contract/`,
          uri: BUF_BINARY_RELATIVE,
        },
      ],
    };
  }

  const toolVersion = `buf-${getBufVersion(bufPath)}`;

  // Fixture-mode: diff against an offline baseline directory rather than a
  // git ref (so fixtures don't need a baseline commit to exist).
  if (fixtureMode) {
    return runFixtureMode(target, options, bufPath, toolVersion, repoRoot);
  }

  // Real mode: invoke buf with --against pointing at the git ref.
  // §A.3: GIT_LFS_SKIP_SMUDGE=1 prevents LFS smudge failures during the
  // git-clone step buf performs internally.
  const env = { ...process.env, GIT_LFS_SKIP_SMUDGE: '1' };
  const subdir = target.specDir.replaceAll('\\', '/');
  const againstSpec = `.git#subdir=${subdir},ref=${baselineRef}`;

  let stdout = '';
  let stderr = '';
  let exitCode = 0;
  try {
    stdout = execFileSync(bufPath, ['breaking', target.specDir, '--against', againstSpec], {
      cwd: repoRoot,
      env,
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
    });
  } catch (err) {
    stdout = err.stdout?.toString() ?? '';
    stderr = err.stderr?.toString() ?? '';
    exitCode = err.status ?? 2;
  }

  return {
    toolVersion,
    findings: parseBufOutput(stdout, stderr, exitCode, repoRoot),
  };
}

function getBufVersion(bufPath) {
  try {
    return execFileSync(bufPath, ['--version'], { encoding: 'utf8' }).trim();
  } catch {
    return 'unknown';
  }
}

/**
 * @param {string} stdout
 * @param {string} stderr
 * @param {number} exitCode
 * @param {string} repoRoot
 */
function parseBufOutput(stdout, stderr, exitCode, repoRoot) {
  if (exitCode === 0) return [];
  if (exitCode !== 100) {
    // Runner-level error (config, baseline missing, etc.) — surface as one
    // finding with the raw stderr so debugging is direct.
    return [
      {
        ruleId: 'contract-governance/buf-runner-error',
        level: 'error',
        message: `buf breaking exited ${exitCode}: ${(stderr || stdout).trim().slice(0, 500)}`,
      },
    ];
  }
  // Exit 100: breaks detected. Each line is `<file>:<line>:<col>:<message>`.
  const findings = [];
  const lines = stdout.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
  for (const line of lines) {
    // Heuristic parse: `path:line:col:message` (where path may contain colons
    // on Windows in absolute form, but buf emits repo-relative paths).
    const m = line.match(/^([^:]+):(\d+):(\d+):(.*)$/);
    if (!m) {
      findings.push({
        ruleId: 'protobuf-buf-breaking/unparsed',
        level: 'error',
        message: line,
      });
      continue;
    }
    const [, path, lineNum, , message] = m;
    findings.push({
      ruleId: classifyBufMessage(message),
      level: 'error',
      message: message.trim(),
      uri: path.replaceAll('\\', '/'),
      startLine: Number(lineNum),
    });
  }
  // §A.10 probe 9: collapse cascading "cannot find X in scope" errors into a
  // single finding tagged as a package-rename hint.
  if (findings.every(f => f.message.includes('cannot find') && f.message.includes('in this scope'))) {
    return [
      {
        ruleId: 'protobuf-buf-breaking/package-rename-detected',
        level: 'error',
        message: `Cascade of cross-package reference errors (${findings.length}); likely a package rename. Affected: ${findings
          .map(f => f.message.match(/cannot find `([^`]+)`/)?.[1])
          .filter(Boolean)
          .join(', ')}`,
        uri: findings[0].uri,
      },
    ];
  }
  return findings;
}

/**
 * Classify a buf message string into a stable ruleId.
 * §A.10: SARIF ruleId reflects what buf observed, not what was declared.
 */
function classifyBufMessage(message) {
  const m = message.trim();
  if (/Previously present field .* was deleted/i.test(m)) {
    return 'protobuf-buf-breaking/field-deleted';
  }
  if (/changed type from/i.test(m)) {
    return 'protobuf-buf-breaking/field-type-changed';
  }
  if (/Previously present enum value .* was deleted/i.test(m)) {
    return 'protobuf-buf-breaking/enum-value-deleted';
  }
  if (/Enum value .* changed name/i.test(m)) {
    return 'protobuf-buf-breaking/enum-value-renamed';
  }
  if (/cannot find .* in this scope/i.test(m)) {
    return 'protobuf-buf-breaking/scope-reference-missing';
  }
  if (/Field .* changed name/i.test(m)) {
    return 'protobuf-buf-breaking/field-renamed';
  }
  return 'protobuf-buf-breaking/break-detected';
}

/**
 * Fixture mode: copy `<specDir>/_baseline/` into a temp dir, then run
 * `buf breaking <specDir> --against <tempBaselineDir>`. Avoids any git
 * dependency for self-test fixtures.
 */
function runFixtureMode(target, options, bufPath, toolVersion, repoRoot) {
  const baselineDir = resolve(repoRoot, target.specDir, '_baseline');
  if (!existsSync(baselineDir)) {
    return {
      toolVersion,
      findings: [
        {
          ruleId: 'contract-governance/fixture-baseline-missing',
          level: 'error',
          message: `fixture baseline missing: ${target.specDir}/_baseline`,
          uri: target.specDir,
        },
      ],
    };
  }

  // Stage baseline as a buf input directory (just the .proto files at top).
  const stagingDir = resolve(tmpdir(), `cgov-fixture-${randomBytes(6).toString('hex')}`);
  mkdirSync(stagingDir, { recursive: true });
  // Copy a buf.yaml so buf can compile the baseline standalone.
  const bufYaml = resolve(repoRoot, target.specDir, 'buf.yaml');
  if (existsSync(bufYaml)) {
    copyFileSync(bufYaml, resolve(stagingDir, 'buf.yaml'));
  }
  // Copy baseline .proto files.
  for (const file of readdirSync(baselineDir)) {
    if (file.endsWith('.proto')) {
      copyFileSync(resolve(baselineDir, file), resolve(stagingDir, file));
    }
  }

  let stdout = '';
  let stderr = '';
  let exitCode = 0;
  try {
    stdout = execFileSync(bufPath, ['breaking', target.specDir, '--against', stagingDir], {
      cwd: repoRoot,
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
    });
  } catch (err) {
    stdout = err.stdout?.toString() ?? '';
    stderr = err.stderr?.toString() ?? '';
    exitCode = err.status ?? 2;
  }

  return {
    toolVersion,
    findings: parseBufOutput(stdout, stderr, exitCode, repoRoot),
  };
}
