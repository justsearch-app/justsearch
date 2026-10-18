import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { evalLaunch } = require('./lib/eval-launch.cjs');
const { resolveDevRuntime } = require('./lib/dev-runtime.cjs');
const { samePath } = require('../lib/path-identity.cjs');

test('eval launch has immutable eval settings and preserves explicit spaced paths as single argv', () => {
  const root = path.resolve('tmp/eval root ü');
  const data = path.join(root, 'data space');
  const input = { JUSTSEARCH_DATA_DIR: data, JUSTSEARCH_MODELS_DIR: path.join(root, 'models'),
    JUSTSEARCH_API_PORT: '43210', JUSTSEARCH_SERVER_EXE: path.join(root, 'cuda server'),
    JUSTSEARCH_GPU_ENABLED: 'true', JUSTSEARCH_LLM_MODEL_PATH: 'a b.gguf', JUSTSEARCH_UI_SETTINGS_MODE: 'PERSISTENT' };
  const launch = evalLaunch(root, input);
  assert.equal(launch.environment.JUSTSEARCH_UI_SETTINGS_MODE, 'IN_MEMORY');
  assert.ok(launch.jvmArgs.includes('-Djustsearch.eval.mode=true'));
  assert.ok(launch.jvmArgs.includes(`-Djustsearch.data.dir=${data}`));
  assert.ok(launch.jvmArgs.includes(`-Djustsearch.server.exe=${input.JUSTSEARCH_SERVER_EXE}`));
  assert.ok(launch.jvmArgs.includes('-Djustsearch.llm.model_path=a b.gguf'));
  assert.equal(launch.environment.JUSTSEARCH_GPU_ENABLED, 'true');
  assert.equal(launch.environment.JUSTSEARCH_API_PORT, '43210');
  assert.equal(input.JUSTSEARCH_UI_SETTINGS_MODE, 'PERSISTENT');
});

test('an invalid runtime override never falls through to a default or CPU runtime', () => {
  const runtime = resolveDevRuntime('/absent', '/absent', { JUSTSEARCH_SERVER_EXE: '/missing/cuda-server' }, 'linux');
  assert.deepEqual(runtime, { path: '/missing/cuda-server', valid: false, source: 'override' });
  assert.equal(resolveDevRuntime('/absent', '/absent', {}, 'linux').path, null);
});

test('POSIX path identity keeps case and literal backslashes; Windows folds case', () => {
  assert.equal(samePath('/data/Case', '/data/case', 'linux'), false);
  assert.equal(samePath('/data/Case', '/data/Case', 'linux'), true);
  assert.equal(samePath('/data/a\\b', '/data/a/b', 'linux'), false);
  assert.equal(samePath('C:\\Data\\Case', 'c:\\data\\case', 'win32'), true);
});

test('runtime override validates executable regular files and never accepts a directory', { skip: process.platform !== 'linux' }, () => {
  const fs = require('node:fs'), os = require('node:os');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'runtime-override-'));
  const file = path.join(dir, 'CUDA server');
  try {
    fs.writeFileSync(file, '#!/bin/sh\nexit 0\n', { mode: 0o644 });
    const resolve = target => resolveDevRuntime(dir, dir, { JUSTSEARCH_SERVER_EXE: target }, 'linux');
    assert.equal(resolve(file).valid, false);
    assert.equal(resolve(dir).valid, false);
    fs.chmodSync(file, 0o755);
    assert.deepEqual(resolve(file), { path: file, valid: true, source: 'override' });
    assert.equal(resolveDevRuntime(dir, dir, {}, 'linux').path, null);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test('Linux eval never selects a Windows ORT DLL directory from a shared checkout', { skip: process.platform !== 'linux' }, () => {
  const fs = require('node:fs'), os = require('node:os');
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'eval-ort-platform-'));
  const native = path.join(root, 'tmp/ort-variant-test/cuda-12.4-v1.24.3');
  try {
    fs.mkdirSync(native, { recursive: true });
    fs.writeFileSync(path.join(native, 'onnxruntime_providers_cuda.dll'), 'fixture');
    const launch = evalLaunch(root, {});
    assert.equal(launch.environment.JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH, undefined);
    assert.ok(!launch.jvmArgs.some(arg => arg.startsWith('-Djustsearch.onnxruntime.native_path=')));
    const explicit = evalLaunch(root, { JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH: '/explicit/linux/ort' });
    assert.equal(explicit.environment.JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH, '/explicit/linux/ort');
    assert.ok(explicit.jvmArgs.includes('-Djustsearch.onnxruntime.native_path=/explicit/linux/ort'));
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
