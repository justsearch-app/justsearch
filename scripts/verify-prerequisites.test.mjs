import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, symlinkSync, rmSync, readdirSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

// Exercise the no-GPU warning without invoking a host nvidia-smi installation.
const testEnv = { ...process.env, PATH: '' };

for (const platform of ['linux', 'win32']) {
  for (const variant of [false, true]) {
    test(`${platform} prerequisites discover ${variant ? 'variant' : 'direct'} runtime and native library`, () => {
      const root = mkdtempSync(path.join(tmpdir(), 'prerequisites-'));
      try {
        symlinkSync(fileURLToPath(new URL('../modules', import.meta.url)), path.join(root, 'modules'), 'junction');
        const runtime = path.join(root, 'native-bin/llama-server', ...(variant ? ['variants/cpu'] : []));
        mkdirSync(runtime, { recursive: true });
        const exe = platform === 'win32' ? 'llama-server.exe' : 'llama-server';
        const lib = platform === 'win32' ? 'llama.dll' : 'libllama.so';
        writeFileSync(path.join(runtime, exe), 'fixture');
        writeFileSync(path.join(root, lib), 'fixture');
        const run = (remove = '') => spawnSync(process.execPath, ['--input-type=module', '-e', `Object.defineProperty(process, 'platform', { value: ${JSON.stringify(platform)} }); ${remove} await import(${JSON.stringify(new URL('./verify-prerequisites.mjs', import.meta.url).href)});`], { cwd: root, env: { ...testEnv, JUSTSEARCH_HOME: root, JUSTSEARCH_NATIVE_PATH: root, APPDATA: '', LOCALAPPDATA: '' }, encoding: 'utf8' });
        const result = run();
        assert.ok(result.stdout, JSON.stringify(result));
        assert.match(result.stdout, new RegExp(`\\[OK\\] ${exe.replace('.', '\\.')} found at`));
        assert.match(result.stdout, /\[OK\] Native llama library found/);
        const wrongLibrary = platform === 'win32' ? 'libllama.so' : 'llama.dll';
        const missingLibrary = run(`(await import('node:fs')).unlinkSync(${JSON.stringify(path.join(root, lib))}); (await import('node:fs')).writeFileSync(${JSON.stringify(path.join(root, wrongLibrary))}, 'fixture');`);
        assert.match(missingLibrary.stdout, /\[FAIL\] Native llama library not found/);
        assert.equal(missingLibrary.status, 1);
        const wrongExe = platform === 'win32' ? 'llama-server' : 'llama-server.exe';
        const missing = run(`(await import('node:fs')).unlinkSync(${JSON.stringify(path.join(runtime, exe))}); (await import('node:fs')).writeFileSync(${JSON.stringify(path.join(runtime, wrongExe))}, 'fixture');`);
        assert.match(missing.stdout, /\[FAIL\] llama-server runtime roots found but no executable discovered/);
        assert.equal(missing.status, 1);
      } finally { rmSync(root, { recursive: true, force: true }); }
    });
  }
}

test('prerequisites verify the GPU bridge at its current source path', () => {
  const result = spawnSync(process.execPath, [fileURLToPath(new URL('./verify-prerequisites.mjs', import.meta.url))], {
    cwd: fileURLToPath(new URL('../', import.meta.url)), env: testEnv, encoding: 'utf8',
  });
  assert.equal(result.error, undefined);
  assert.match(result.stdout, /\[OK\] VramDetector.java exists/);
  assert.match(result.stdout, /\[OK\] VramDetector uses nvidia-smi/);
  assert.match(result.stdout, /\[OK\] VramDetector includes meetsVduRequirements/);
  assert.match(result.stdout, /\[OK\] VramDetector includes getRecommendedLlamaServerFlags/);
});

test('healthy prerequisites use app-api mode types and reject missing declarations', () => {
  const root = mkdtempSync(path.join(tmpdir(), 'prerequisites-mode-'));
  const modules = fileURLToPath(new URL('../modules', import.meta.url));
  try {
    mkdirSync(path.join(root, 'modules'));
    for (const name of readdirSync(modules)) {
      if (name !== 'app-api') {
        symlinkSync(path.join(modules, name), path.join(root, 'modules', name), 'junction');
      }
    }
    const api = 'app-api/src/main/java/io/justsearch/app/api';
    const fixtureApi = path.join(root, 'modules', api);
    mkdirSync(fixtureApi, { recursive: true });
    for (const name of ['DocumentService.java', 'ModeTransitionException.java', 'Mode.java']) {
      writeFileSync(path.join(fixtureApi, name), readFileSync(path.join(modules, api, name)));
    }
    const registry = JSON.parse(readFileSync(path.join(modules, 'configuration/src/main/resources/ai/model-registry.v2.json'), 'utf8'));
    const model = registry.packages.find((entry) => entry.id === 'chat').variants[0].filename;
    mkdirSync(path.join(root, 'models'));
    writeFileSync(path.join(root, 'models', model), 'fixture');
    const runtime = path.join(root, 'native-bin/llama-server');
    mkdirSync(runtime, { recursive: true });
    writeFileSync(path.join(runtime, process.platform === 'win32' ? 'llama-server.exe' : 'llama-server'), 'fixture');
    writeFileSync(path.join(root, process.platform === 'win32' ? 'llama.dll' : 'libllama.so'), 'fixture');
    const run = () => spawnSync(process.execPath, [fileURLToPath(new URL('./verify-prerequisites.mjs', import.meta.url))], {
      cwd: root, env: { ...testEnv, JUSTSEARCH_HOME: root, JUSTSEARCH_NATIVE_PATH: root, APPDATA: '', LOCALAPPDATA: '' }, encoding: 'utf8',
    });
    const healthy = run();
    assert.equal(healthy.error, undefined);
    assert.match(healthy.stdout, /\[OK\] ModeTransitionException.java exists in app-api/);
    assert.match(healthy.stdout, /\[OK\] Mode enum exists in app-api/);
    assert.equal(healthy.status, 0, healthy.stdout + healthy.stderr);

    rmSync(path.join(fixtureApi, 'ModeTransitionException.java'));
    const missingException = run();
    assert.match(missingException.stdout, /\[FAIL\] ModeTransitionException.java missing/);
    assert.equal(missingException.status, 1);
    writeFileSync(path.join(fixtureApi, 'ModeTransitionException.java'), readFileSync(path.join(modules, api, 'ModeTransitionException.java')));

    writeFileSync(path.join(fixtureApi, 'Mode.java'), 'public class Mode {}');
    const wrongMode = run();
    assert.match(wrongMode.stdout, /\[FAIL\] Mode enum missing/);
    assert.equal(wrongMode.status, 1);
    rmSync(path.join(fixtureApi, 'Mode.java'));
    const missingMode = run();
    assert.match(missingMode.stdout, /\[FAIL\] Mode enum missing/);
    assert.equal(missingMode.status, 1);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
