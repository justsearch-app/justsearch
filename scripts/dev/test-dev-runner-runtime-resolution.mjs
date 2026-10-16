#!/usr/bin/env node
import './lib/test-fixture-processes.cjs';
/**
 * Tempdoc 656 Move 1 + Move 2 regression test for the dev llama-server resolution.
 *
 * Pins the load-bearing guarantee: dev inference is GPU-only — `resolveCuda12ServerExe` resolves the
 * shared/worktree cuda12 runtime and NEVER a CPU baseline. If neither cuda12 exists, it returns null
 * (→ JUSTSEARCH_SERVER_EXE stays unset → inference fails closed), even when a CPU-baseline-shaped
 * `native-bin/llama-server/llama-server.exe` is present. This is the anti-regression that a future
 * change must not silently undo (re-introducing the 9B-on-CPU cross-worktree DOS).
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const require = createRequire(import.meta.url);
const { resolveCuda12ServerExe, stageSharedCuda12 } = require(path.join(__dirname, 'dev-runner.cjs')).__test;

const EXE = 'llama-server.exe';

function touch(p) {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, 'stub');
}
function cuda12Exe(root) {
  return path.join(root, 'modules', 'ui', 'native-bin', 'llama-server', 'variants', 'cuda12', EXE);
}
function cpuBaselineExe(root) {
  return path.join(root, 'modules', 'ui', 'native-bin', 'llama-server', EXE);
}

function main() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'js-656-resolve-'));
  try {
    const worktree = path.join(tmp, 'worktree');
    const shared = path.join(tmp, 'main');

    // (1) shared cuda12 present, worktree empty → resolves the shared cuda12.
    touch(cuda12Exe(shared));
    assert.equal(
      resolveCuda12ServerExe(worktree, shared, EXE),
      cuda12Exe(shared),
      '(1) should resolve the shared main-checkout cuda12',
    );

    // (2) worktree's own cuda12 present → it wins over the shared one.
    touch(cuda12Exe(worktree));
    assert.equal(
      resolveCuda12ServerExe(worktree, shared, EXE),
      cuda12Exe(worktree),
      "(2) a worktree's own cuda12 should take precedence over the shared one",
    );

    // (3) THE CORE ANTI-REGRESSION: no cuda12 anywhere, but a CPU baseline exe present in BOTH
    // worktree and shared native-bin → resolution returns null (never the CPU baseline).
    const noCuda = path.join(tmp, 'nocuda-worktree');
    const noCudaShared = path.join(tmp, 'nocuda-main');
    touch(cpuBaselineExe(noCuda));
    touch(cpuBaselineExe(noCudaShared));
    assert.equal(
      resolveCuda12ServerExe(noCuda, noCudaShared, EXE),
      null,
      '(3) a CPU baseline must NEVER be resolved — GPU-only dev fails closed instead',
    );

    // ---- stageSharedCuda12: the populate guard must be cuda12-specific ----
    const sharedNb = (root) => path.join(root, 'modules', 'ui', 'native-bin', 'llama-server');
    const stageSrc = (root) => path.join(root, 'modules', 'ui', 'build', 'llama-server', 'stage', 'variants', 'cuda12');
    function withStageSource(root) {
      // a minimal but complete-looking cuda12 stage (exe + a DLL, to prove the whole dir copies).
      touch(path.join(stageSrc(root), EXE));
      touch(path.join(stageSrc(root), 'ggml-cuda.dll'));
      return [stageSrc(root)];
    }

    // (4) THE REGRESSION: a stale flat CPU baseline present in the shared native-bin, but NO cuda12,
    // and a valid cuda12 stage source → stageSharedCuda12 STILL provisions cuda12 (the old
    // hasAnyLlamaRuntime guard would have wrongly skipped, silently breaking GPU dev).
    const m4 = path.join(tmp, 'm4');
    touch(path.join(sharedNb(m4), EXE)); // stray flat CPU baseline (must NOT block)
    const staged4 = stageSharedCuda12(sharedNb(m4), withStageSource(m4), EXE);
    assert.equal(
      staged4,
      path.join(sharedNb(m4), 'variants', 'cuda12', EXE),
      '(4) a stray flat CPU baseline must NOT block cuda12 provisioning',
    );
    assert.ok(fs.existsSync(path.join(sharedNb(m4), 'variants', 'cuda12', 'ggml-cuda.dll')),
      '(4) the full cuda12 dir (incl. DLLs) should be copied');

    // (5) idempotent/protect: cuda12 already present → returns null, does not re-copy.
    const m5 = path.join(tmp, 'm5');
    touch(path.join(sharedNb(m5), 'variants', 'cuda12', EXE));
    assert.equal(
      stageSharedCuda12(sharedNb(m5), withStageSource(m5), EXE),
      null,
      '(5) an existing cuda12 must be protected (no re-copy)',
    );

    // (6) no stage source → returns null (nothing to do).
    const m6 = path.join(tmp, 'm6');
    assert.equal(
      stageSharedCuda12(sharedNb(m6), [stageSrc(m6)], EXE),
      null,
      '(6) no cuda12 stage source → returns null',
    );

    console.log('OK  test-dev-runner-runtime-resolution: cuda12-only resolution + cuda12-specific populate guard (656)');
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

main();
