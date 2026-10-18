import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import runtime from './lib/dev-runtime.cjs';
// Exercise the real environment-producing function without starting either dev child.
const source = fs.readFileSync(new URL('../../modules/ui-web/scripts/dev-all.cjs', import.meta.url), 'utf8');
const body = source.match(/function resolveAiDevEnv\(\) \{[\s\S]+?\n\}/)?.[0];
assert.ok(body);
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dev-all-runtime-'));
const touch = file => { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, 'fixture'); };
try {
  const main = path.join(dir, 'main'), rootDir = path.join(dir, 'worktree');
  fs.mkdirSync(rootDir, { recursive: true });
  fs.writeFileSync(path.join(rootDir, '.git'), `gitdir: ${path.join(main, '.git/worktrees/fixture')}\n`);
  for (const platform of ['linux', 'win32']) {
    const env = {};
    const resolve = () => vm.runInNewContext(`(${body})`, { rootDir, path, fs, process: { env },
      devRuntime: { ...runtime, resolveDevRuntime: (root, shared) => runtime.resolveDevRuntime(root, shared, env, platform) } })();
    const flat = path.join(rootDir, 'native-bin/llama-server', platform === 'win32' ? 'llama-server.exe' : 'llama-server');
    touch(flat);
    assert.equal(resolve().JUSTSEARCH_SERVER_EXE, undefined, `${platform}: no CPU baseline fallback`);
    env.JUSTSEARCH_SERVER_EXE = path.join(dir, 'missing');
    assert.throws(resolve, /regular executable file/, `${platform}: invalid override is visible`);
    delete env.JUSTSEARCH_SERVER_EXE;
    if (platform === 'win32') {
      const cuda = path.join(main, 'modules/ui/native-bin/llama-server/variants/cuda12/llama-server.exe');
      touch(cuda);
      assert.equal(resolve().JUSTSEARCH_SERVER_EXE, cuda, 'worktree resolves shared Windows GPU runtime');
    }
  }
  console.log('PASS dev:all uses the shared GPU-only runtime resolver for both selected targets');
} finally { fs.rmSync(dir, { recursive: true, force: true }); }
