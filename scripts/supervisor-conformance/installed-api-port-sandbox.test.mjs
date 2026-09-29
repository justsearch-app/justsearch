/**
 * Host-side regressions for the installed API-port Windows Sandbox launcher.
 *
 * These tests never start Windows Sandbox or execute the guest script. The first test proves the
 * fixture refuses a spoofed WDAGUtilityAccount environment using os.userInfo(), before touching a
 * caller-provided work path. The second verifies the generated staging contract and parses both
 * PowerShell sources with the host parser.
 *
 * Run with: `node --test scripts/supervisor-conformance/installed-api-port-sandbox.test.mjs`
 */
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const scriptsDir = path.dirname(fileURLToPath(import.meta.url));
const fixture = path.join(scriptsDir, 'installed-api-port-restart.mjs');
const sandboxLauncher = path.join(scriptsDir, 'installed-api-port-sandbox.ps1');
const guestLauncher = path.join(scriptsDir, 'installed-api-port-sandbox-guest.ps1');
const powershellExecutable = 'powershell.exe';
// The parent Codex runtime can inject a PowerShell 7-only PSModulePath. Let the native Windows
// PowerShell child resolve its own inbox modules so this test exercises the shipped host launcher.
const powershellEnvironment = { ...process.env };
delete powershellEnvironment.PSModulePath;
const windowsOnly = process.platform === 'win32' ? {} : { skip: 'Windows-only regression' };

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

function jsonFile(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
}

function powershellAstCheck(file) {
  const escaped = file.replaceAll("'", "''");
  const command = [
    `$path = '${escaped}'`,
    '$tokens = $null',
    '$errors = $null',
    '[System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors) | Out-Null',
    'if ($errors.Count -gt 0) { $errors | ForEach-Object { Write-Output $_.Message }; exit 1 }',
  ].join('; ');
  const result = spawnSync(powershellExecutable, [
    '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', command,
  ], { encoding: 'utf8', env: powershellEnvironment, windowsHide: true });
  assert.equal(result.error, undefined, `PowerShell parser could not start: ${result.error ?? ''}`);
  assert.equal(result.status, 0,
    `${file} has PowerShell AST errors:\n${result.stdout}\n${result.stderr}`);
}

test('fixture refuses a spoofed WDAG user before creating the supplied work path', windowsOnly,
  (t) => {
    if (os.userInfo().username.toLowerCase() === 'wdagutilityaccount') {
      t.skip('actual host user is WDAGUtilityAccount; spoofed-user negative proof is unavailable');
      return;
    }
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-api-port-guard-'));
    try {
      const work = path.join(root, 'provided-work');
      const result = spawnSync(process.execPath, [fixture], {
        cwd: path.resolve(scriptsDir, '..', '..'),
        env: {
          ...process.env,
          USERNAME: 'WDAGUtilityAccount',
          JUSTSEARCH_INSTALLED_EXE: path.join(root, 'dummy-installer.exe'),
          JUSTSEARCH_INSTALLED_API_PORT_WORK: work,
          JUSTSEARCH_INSTALLED_KNOWN_APPDATA: path.join(root, 'known-appdata'),
        },
        encoding: 'utf8',
        windowsHide: true,
      });
      const output = `${result.stdout}\n${result.stderr}`;
      assert.notEqual(result.status, 0, output);
      assert.match(output, /REFUSING TO RUN: installed API-port proof requires Windows Sandbox/i);
      assert.equal(fs.existsSync(work), false,
        'the fixture must reject the spoofed user before creating JUSTSEARCH_INSTALLED_API_PORT_WORK');
    } finally {
      fs.rmSync(root, { recursive: true, force: true });
    }
  });

test('GenerateOnly stages a closed WSB contract and preserves source AST validity', windowsOnly, () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-api-port-stage-'));
  try {
    powershellAstCheck(sandboxLauncher);
    powershellAstCheck(guestLauncher);

    const installer = path.join(root, 'dummy-installer.exe');
    const work = path.join(root, 'sandbox-work');
    fs.writeFileSync(installer, Buffer.from('dummy installer bytes\n'));
    const result = spawnSync(powershellExecutable, [
      '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', sandboxLauncher,
      '-InstallerPath', installer,
      '-NodePath', process.execPath,
      '-WorkRoot', work,
      '-GenerateOnly',
    ], { encoding: 'utf8', env: powershellEnvironment, windowsHide: true });
    assert.equal(result.error, undefined, `PowerShell launcher could not start: ${result.error ?? ''}`);
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
    assert.match(result.stdout, /launched\s*:\s*False/i,
      'GenerateOnly must report launched=False and must not launch Windows Sandbox');

    const inputDir = path.join(work, 'input');
    const outputDir = path.join(work, 'output');
    const wsb = path.join(work, 'installed-api-port-sandbox.wsb');
    const stage = jsonFile(path.join(outputDir, 'host-stage.json'));
    const wsbText = fs.readFileSync(wsb, 'utf8');
    const installerHash = sha256(installer);
    const nodeHash = sha256(process.execPath);

    assert.equal(stage.installer.sha256, installerHash);
    assert.equal(stage.node.sha256, nodeHash,
      'host stage receipt must fingerprint the actual runtime passed as NodePath');
    assert.equal(fs.existsSync(path.join(outputDir, 'installed-api-port-sandbox-result.json')), false,
      'GenerateOnly must not create a guest terminal receipt');

    const mappedFolders = [...wsbText.matchAll(/<MappedFolder>([\s\S]*?)<\/MappedFolder>/gi)]
      .map((match) => match[1]);
    const inputMapping = mappedFolders.find((mapping) =>
      mapping.includes(`<HostFolder>${inputDir}</HostFolder>`));
    const outputMapping = mappedFolders.find((mapping) =>
      mapping.includes(`<HostFolder>${outputDir}</HostFolder>`));
    assert.ok(inputMapping, 'the staged input mapping must be present');
    assert.ok(outputMapping, 'the writable output mapping must be present');
    assert.match(inputMapping, /<ReadOnly>true<\/ReadOnly>/i,
      'the staged input mapping must be read-only');
    assert.match(outputMapping, /<ReadOnly>false<\/ReadOnly>/i,
      'the result mapping must be writable');
    assert.match(wsbText, /<LogonCommand>[\s\S]*<Command>/i,
      'the WSB must contain a guest LogonCommand');
    assert.ok(wsbText.includes('installed-api-port-sandbox-guest.ps1'));
    assert.ok(wsbText.includes(`-ExpectedInstallerSha256 "${installerHash}"`));
    assert.ok(wsbText.includes(`-ExpectedNodeSha256 "${nodeHash}"`));
    assert.ok(wsbText.includes('-InstallerPath'));
    assert.ok(wsbText.includes('-NodePath'));
    assert.ok(wsbText.includes('-FixturePath'));
    assert.ok(wsbText.includes('-WorkRoot'));
    assert.ok(wsbText.includes('-OutputDir'));

    // Keep the path assertions explicit so a future launcher cannot silently point the guest at a
    // different input/output root while retaining the same ReadOnly booleans.
    assert.ok(wsbText.includes('<SandboxFolder>C:\\Users\\WDAGUtilityAccount\\Desktop\\JustSearchApiPortInput</SandboxFolder>'));
    assert.ok(wsbText.includes('<SandboxFolder>C:\\Users\\WDAGUtilityAccount\\Desktop\\JustSearchApiPortOutput</SandboxFolder>'));
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
