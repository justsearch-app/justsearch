import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {
  assertFixtureFreeSpace, pruneInstallerModelTrees, pruneRegenerableModelCaches,
} from './prune-model-caches.mjs';

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-model-cache-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const work = path.join(root, 'work');
  fs.mkdirSync(work);
  return { root, work };
}

test('prunes only staged model caches and preserves hard-linked source model', t => {
  const { root, work } = fixture(t);
  const source = path.join(root, 'source.onnx');
  fs.writeFileSync(source, 'model bytes');
  const modelDir = path.join(work, 'installer-models', 'onnx', 'x');
  fs.mkdirSync(modelDir, { recursive: true });
  const model = path.join(modelDir, 'model.onnx');
  fs.linkSync(source, model);
  for (const suffix of ['.optimized', '.opt-meta']) {
    fs.writeFileSync(`${model}${suffix}`, suffix);
  }
  const outside = path.join(work, 'unrelated.optimized');
  fs.writeFileSync(outside, 'keep');
  const originalLinks = fs.statSync(source).nlink;

  const pruned = pruneRegenerableModelCaches(work);

  assert.equal(pruned.files, 2);
  assert.equal(pruned.bytes, '.optimized'.length + '.opt-meta'.length);
  assert.equal(fs.readFileSync(source, 'utf8'), 'model bytes');
  assert.equal(fs.readFileSync(model, 'utf8'), 'model bytes');
  assert.equal(fs.statSync(source).nlink, originalLinks);
  assert.equal(fs.readFileSync(outside, 'utf8'), 'keep');
  assert.equal(fs.existsSync(`${model}.optimized`), false);
  assert.equal(fs.existsSync(`${model}.opt-meta`), false);
});

test('refuses a linked model tree before deleting any candidate', t => {
  const { root, work } = fixture(t);
  const tree = path.join(work, 'installer-models');
  fs.mkdirSync(tree);
  const cache = path.join(tree, 'model.onnx.optimized');
  fs.writeFileSync(cache, 'keep on refusal');
  const outside = path.join(root, 'outside');
  fs.mkdirSync(outside);
  const outsideCache = path.join(outside, 'outside.onnx.optimized');
  fs.writeFileSync(outsideCache, 'outside');
  try {
    fs.symlinkSync(outside, path.join(tree, 'linked'), 'junction');
  } catch (error) {
    if (error.code === 'EPERM' || error.code === 'EACCES') {
      t.skip('junction creation is unavailable on this host');
      return;
    }
    throw error;
  }

  assert.throws(() => pruneRegenerableModelCaches(work), /symbolic link or junction/);
  assert.equal(fs.readFileSync(cache, 'utf8'), 'keep on refusal');
  assert.equal(fs.readFileSync(outsideCache, 'utf8'), 'outside');
});

test('removes installed model trees and only their hard links, never the shared source', t => {
  const { root, work } = fixture(t);
  const source = path.join(root, 'shared-model.onnx');
  fs.writeFileSync(source, 'shared model bytes');
  for (const tree of ['installer-models', 'installer-models-b']) {
    const modelDir = path.join(work, tree, 'onnx', 'x');
    fs.mkdirSync(modelDir, { recursive: true });
    fs.linkSync(source, path.join(modelDir, 'model.onnx'));
    fs.writeFileSync(path.join(modelDir, 'model_manifest.json'), '{}');
  }
  const diagnostics = path.join(work, 'fixture-output.txt');
  fs.writeFileSync(diagnostics, 'keep');
  assert.equal(fs.statSync(source).nlink, 3);

  const pruned = pruneInstallerModelTrees(work);

  assert.equal(pruned.trees, 2);
  assert.equal(pruned.files, 4);
  assert.equal(fs.existsSync(path.join(work, 'installer-models')), false);
  assert.equal(fs.existsSync(path.join(work, 'installer-models-b')), false);
  assert.equal(fs.readFileSync(source, 'utf8'), 'shared model bytes');
  assert.equal(fs.statSync(source).nlink, 1);
  assert.equal(fs.readFileSync(diagnostics, 'utf8'), 'keep');
  assert.deepEqual(pruneInstallerModelTrees(work), { trees: 0, files: 0, bytes: 0 });
});

test('refuses to remove a model tree that contains a junction, deleting nothing', t => {
  const { root, work } = fixture(t);
  const tree = path.join(work, 'installer-models');
  fs.mkdirSync(tree);
  const kept = path.join(tree, 'model.onnx');
  fs.writeFileSync(kept, 'keep on refusal');
  const outside = path.join(root, 'outside');
  fs.mkdirSync(outside);
  const outsideModel = path.join(outside, 'shared.onnx');
  fs.writeFileSync(outsideModel, 'shared');
  try {
    fs.symlinkSync(outside, path.join(tree, 'linked'), 'junction');
  } catch (error) {
    if (error.code === 'EPERM' || error.code === 'EACCES') {
      t.skip('junction creation is unavailable on this host');
      return;
    }
    throw error;
  }

  assert.throws(() => pruneInstallerModelTrees(work), /symbolic link or junction/);
  assert.equal(fs.readFileSync(kept, 'utf8'), 'keep on refusal');
  assert.equal(fs.readFileSync(outsideModel, 'utf8'), 'shared');
});

test('free-space preflight refuses a threshold above current free space', t => {
  const { work } = fixture(t);
  const volume = fs.statfsSync(work, { bigint: true });
  // Free space moves while parallel test files write and delete; the volume's whole capacity plus
  // one byte is a threshold no later free-space read can satisfy (hosted CI raced `free + 1`).
  const unreachable = volume.blocks * volume.bsize + 1n;
  assert.throws(() => assertFixtureFreeSpace(work, unreachable),
    /prune regenerable installer model caches/);
  assert.ok(assertFixtureFreeSpace(work, 0n) >= 0n);
});
