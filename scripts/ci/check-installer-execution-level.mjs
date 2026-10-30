#!/usr/bin/env node
/**
 * check-installer-execution-level.mjs (D3, tempdoc 728-followup; artifact half added tempdoc 729)
 *
 * Two independent assertions, both about the same published README claim
 * ("The install is per-user; no admin rights needed" — README.md:39):
 *
 * 1. CONFIG (always-on): `bundle.windows.nsis.installMode === "currentUser"`
 *    in tauri.conf.json — per ADR-0024, this is the source setting that makes
 *    Tauri/NSIS build a per-user installer.
 * 2. ARTIFACT (when a built installer is available): the BUILT installer's
 *    embedded Windows application manifest requests `asInvoker`, not
 *    `requireAdministrator`/`highestAvailable`. This is what actually
 *    determines whether Windows shows a UAC prompt at install time — the
 *    config assertion alone cannot see a rendering/hook change that leaves
 *    tauri.conf.json correct while the built binary still requests
 *    elevation (e.g. via modules/shell/src-tauri/nsis/installer-hooks.nsh).
 *
 * This mechanical check is independent of a test machine's elevation state.
 * It proves only that the installer does not request elevation; publisher
 * warnings and SmartScreen still need observation on a clean Windows VM.
 *
 * ARTIFACT READ METHOD — byte-scan, not a PE/resource parser:
 * The requestedExecutionLevel lives in an RT_MANIFEST resource (plain XML)
 * embedded in the PE's resource section. Rather than walking the PE header
 * -> resource directory -> RT_MANIFEST structure, this scans the raw file
 * bytes for the literal `requestedExecutionLevel` marker (tried as both
 * UTF-8 and UTF-16LE, since Windows resource strings are sometimes stored
 * UTF-16LE) and reads the `level="..."` attribute that follows it. This is
 * an HONEST HEURISTIC, not a real manifest parser: it is only trustworthy
 * because it fails closed — if the marker is found zero times or more than
 * once across both encodings, that is treated as ambiguous and the check
 * FAILS rather than guessing. Verified against the real installer at
 * dist/installer-ci/windows-installer/JustSearch_0.2.0_x64-setup.exe: exactly
 * one UTF-8 match, `level="asInvoker" uiAccess="false"`.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { readTauriConfig } from './lib/tauri-config.mjs';

function repoRootFromCwd() {
  const markers = ['settings.gradle.kts', 'build.gradle.kts', '.git'];
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (markers.some((marker) => fs.existsSync(path.join(dir, marker)))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
  }
  return process.cwd();
}

const CONFIG_REL = path.join('modules', 'shell', 'src-tauri', 'tauri.conf.json');
const EXPECTED_INSTALL_MODE = 'currentUser';
const EXPECTED_EXECUTION_LEVEL = 'asInvoker';

// Conventional locations a built NSIS installer can land, in discovery-preference
// order. See scripts/ci/build-release-assets.ps1 / package-installer-win.ps1
// (default -OutDir "dist/installer"); nested paths also cover CI artifact downloads.
const CONVENTIONAL_INSTALLER_GLOBS = [
  ['dist', 'installer'],
  ['dist', 'installer', 'windows-installer'],
  ['dist', 'installer-ci', 'windows-installer'],
  ['modules', 'shell', 'src-tauri', 'target', 'x86_64-pc-windows-msvc', 'release', 'bundle', 'nsis'],
  ['modules', 'shell', 'src-tauri', 'target', 'release', 'bundle', 'nsis'],
];

export function checkInstallerExecutionLevel(repoRoot) {
  const configPath = path.join(repoRoot, CONFIG_REL);
  if (!fs.existsSync(configPath)) {
    return { ok: false, message: `${CONFIG_REL} not found at ${configPath}` };
  }

  let config;
  try {
    config = readTauriConfig(repoRoot, 'windows');
  } catch (err) {
    return { ok: false, message: err.message };
  }

  const installMode = config?.bundle?.windows?.nsis?.installMode;
  if (installMode !== EXPECTED_INSTALL_MODE) {
    return {
      ok: false,
      message:
        `${CONFIG_REL} (including the Windows overlay): bundle.windows.nsis.installMode is ${JSON.stringify(installMode)}, ` +
        `expected ${JSON.stringify(EXPECTED_INSTALL_MODE)}. A non-"currentUser" install mode ` +
        '(e.g. "perMachine") makes the NSIS installer request Windows elevation (a UAC prompt) ' +
        'on every install — per ADR-0024 the installer must be per-user.',
    };
  }

  return {
    ok: true,
    message: `${CONFIG_REL} (including the Windows overlay): bundle.windows.nsis.installMode is "currentUser" — the installer does not request elevation.`,
  };
}

/**
 * Find a single `requestedExecutionLevel` marker in the raw installer bytes
 * and return its `level="..."` value, its byte offset, and which encoding
 * matched. Fails closed (returns { ambiguous: true }) on 0 or >1 matches
 * across UTF-8 + UTF-16LE combined — ambiguity must never read as a pass.
 */
export function findRequestedExecutionLevel(buf) {
  const markerUtf8 = Buffer.from('requestedExecutionLevel', 'utf8');
  const markerUtf16 = Buffer.from('requestedExecutionLevel', 'utf16le');

  const matches = [];
  for (const [marker, encoding] of [
    [markerUtf8, 'utf8'],
    [markerUtf16, 'utf16le'],
  ]) {
    let idx = buf.indexOf(marker);
    while (idx !== -1) {
      matches.push({ offset: idx, encoding, markerLength: marker.length });
      idx = buf.indexOf(marker, idx + 1);
    }
  }

  if (matches.length !== 1) {
    return { ambiguous: true, matchCount: matches.length, matches };
  }

  const { offset, encoding, markerLength } = matches[0];
  // Look at a bounded window right after the marker for `level="..."`.
  const windowEnd = Math.min(buf.length, offset + markerLength + 400);
  const windowBuf = buf.subarray(offset, windowEnd);
  const windowText = windowBuf.toString(encoding === 'utf16le' ? 'utf16le' : 'latin1');

  const levelMatch = windowText.match(/level\s*=\s*"([^"]*)"/);
  if (!levelMatch) {
    return { ambiguous: true, matchCount: 1, reason: 'marker found but no level="..." followed it' };
  }

  return { ambiguous: false, level: levelMatch[1], offset, encoding };
}

function findConventionalInstaller(repoRoot) {
  let best = null; // { path, mtimeMs }
  for (const parts of CONVENTIONAL_INSTALLER_GLOBS) {
    const dir = path.join(repoRoot, ...parts);
    if (!fs.existsSync(dir)) continue;
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      if (!entry.isFile() || !entry.name.toLowerCase().endsWith('-setup.exe')) continue;
      const fullPath = path.join(dir, entry.name);
      const stat = fs.statSync(fullPath);
      if (!best || stat.mtimeMs > best.mtimeMs) {
        best = { path: fullPath, mtimeMs: stat.mtimeMs };
      }
    }
  }
  return best?.path ?? null;
}

export function checkInstallerArtifact(installerPath) {
  if (!fs.existsSync(installerPath)) {
    return { ok: false, message: `Installer not found at ${installerPath}` };
  }

  const buf = fs.readFileSync(installerPath);
  const found = findRequestedExecutionLevel(buf);

  if (found.ambiguous) {
    return {
      ok: false,
      message:
        `${installerPath}: byte-scan for "requestedExecutionLevel" found ${found.matchCount} match(es) ` +
        '(expected exactly 1) — refusing to guess. This is an honest heuristic, not a real PE/resource ' +
        'parser, so ambiguity fails closed rather than reading as a pass.',
    };
  }

  if (found.level !== EXPECTED_EXECUTION_LEVEL) {
    return {
      ok: false,
      message:
        `${installerPath}: built installer's embedded manifest requests ` +
        `requestedExecutionLevel="${found.level}" (encoding=${found.encoding}, byte offset=${found.offset}), ` +
        `expected "${EXPECTED_EXECUTION_LEVEL}". This means the BUILT installer requests Windows elevation — ` +
        'a UAC prompt WILL appear on install, contradicting the README\'s "no admin rights needed" claim. ' +
        'This is a release blocker, not a check to tune.',
    };
  }

  return {
    ok: true,
    message:
      `${installerPath}: built installer's embedded manifest requests requestedExecutionLevel="asInvoker" ` +
      `(encoding=${found.encoding}, byte offset=${found.offset}, exactly 1 match) — the artifact itself does ` +
      'not request elevation.',
  };
}

const SCOPE_NOTE =
  'SCOPE: this proves only that the installer does not request elevation. It says nothing ' +
  'about unsigned-publisher warnings or SmartScreen, which depend on code signing and remain ' +
  "the Sandbox round's job to observe whenever elevation posture allows it.";

function parseArgs(argv) {
  const args = { installer: null };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--installer') {
      args.installer = argv[i + 1] ?? null;
      i++;
    }
  }
  return args;
}

function main() {
  const repoRoot = repoRootFromCwd();
  const { installer: explicitInstaller } = parseArgs(process.argv.slice(2));

  const configResult = checkInstallerExecutionLevel(repoRoot);
  let overallOk = configResult.ok;

  if (configResult.ok) {
    console.log(`check-installer-execution-level: OK (config) — ${configResult.message}`);
  } else {
    console.error('check-installer-execution-level: FAIL (config)');
    console.error(`- ${configResult.message}`);
  }

  let installerPath = explicitInstaller ? path.resolve(explicitInstaller) : findConventionalInstaller(repoRoot);
  if (explicitInstaller && !fs.existsSync(installerPath)) {
    console.error(`check-installer-execution-level: FAIL (artifact) — --installer path not found: ${installerPath}`);
    overallOk = false;
    installerPath = null;
  }

  if (installerPath) {
    const artifactResult = checkInstallerArtifact(installerPath);
    overallOk = overallOk && artifactResult.ok;
    if (artifactResult.ok) {
      console.log(`check-installer-execution-level: OK (artifact) — ${artifactResult.message}`);
    } else {
      console.error('check-installer-execution-level: FAIL (artifact)');
      console.error(`- ${artifactResult.message}`);
    }
  } else if (!explicitInstaller) {
    console.log(
      'check-installer-execution-level: config only — no built installer found at the conventional ' +
        'locations and no --installer given; this run verified ONLY the source config, NOT the built ' +
        'artifact. This is expected on a normal PR run (no installer has been built).'
    );
  }

  console.log(SCOPE_NOTE);
  if (!overallOk) process.exitCode = 1;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
