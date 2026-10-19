#!/usr/bin/env node
// Keep failure artifacts useful without uploading indexes, models or an unbounded log tree.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const FILE_BYTES = 256 * 1024;
const TOTAL_BYTES = 20 * 1024 * 1024;
const MAX_FILES = 200;
const MAX_ENTRIES = 100_000;

export function collectChildLogs(root, destination) {
  const manifest = { fileBytes: FILE_BYTES, totalBytes: TOTAL_BYTES, maxFiles: MAX_FILES,
    files: [], errors: [], omitted: 0, scanLimitReached: false };
  let remaining = TOTAL_BYTES;
  let entries = 0;
  const roots = ['tmp/lane-f-takeover', 'modules/system-tests/build/reports/isolated-backend'];
  // The installed Engine embeds the worker. Its application output is engine.log;
  // supervisor.log and backend.stdout/stderr.log live in each fixture's state/runs tree.
  const selected = (name) => /\.log(?:\.\d+)?$/.test(name) || /(?:^|-)output\.txt$/.test(name);
  function walk(relative) {
    const source = path.join(root, relative);
    let children;
    try {
      if (fs.lstatSync(source).isSymbolicLink()) return;
      children = fs.readdirSync(source, { withFileTypes: true });
    } catch (error) {
      if (error.code !== 'ENOENT') manifest.errors.push({ path: relative, error: error.message });
      return;
    }
    for (const child of children.sort((a, b) => a.name.localeCompare(b.name))) {
      if (++entries > MAX_ENTRIES) { manifest.scanLimitReached = true; return; }
      if (child.isSymbolicLink()) continue;
      const item = path.join(relative, child.name);
      if (child.isDirectory()) {
        if (!['models', 'indices', 'index', 'corpus', 'node_modules'].includes(child.name)) walk(item);
      } else if (child.isFile() && selected(child.name)) {
        if (remaining === 0 || manifest.files.length >= MAX_FILES) { manifest.omitted++; continue; }
        let fd;
        try {
          fd = fs.openSync(path.join(root, item), 'r');
          const size = fs.fstatSync(fd).size;
          const bytes = Math.min(size, FILE_BYTES, remaining);
          const data = Buffer.alloc(bytes);
          const copied = fs.readSync(fd, data, 0, bytes, size - bytes);
          const output = path.join(destination, item);
          fs.mkdirSync(path.dirname(output), { recursive: true });
          fs.writeFileSync(output, data.subarray(0, copied));
          remaining -= copied;
          manifest.files.push({ path: item, originalBytes: size, copiedBytes: copied, truncated: size > copied });
        } catch (error) {
          manifest.errors.push({ path: item, error: error.message });
        } finally {
          if (fd !== undefined) fs.closeSync(fd);
        }
      }
    }
  }
  for (const relative of roots) walk(relative);
  fs.mkdirSync(destination, { recursive: true });
  fs.writeFileSync(path.join(destination, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
  return manifest;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const result = collectChildLogs(process.cwd(), path.resolve('build/ci/e2e-child-logs'));
  console.log(`Collected ${result.files.length} child logs (${result.files.reduce((n, f) => n + f.copiedBytes, 0)} bytes); ${result.omitted} omitted`);
  if (result.errors.length || result.scanLimitReached) {
    console.error(JSON.stringify({ errors: result.errors, scanLimitReached: result.scanLimitReached }));
    process.exitCode = 1;
  }
}
