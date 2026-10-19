#!/usr/bin/env node
// Keep failure artifacts useful without uploading indexes, models or an unbounded log tree.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createGunzip, gzipSync } from 'node:zlib';

const FILE_BYTES = 256 * 1024;
const TOTAL_BYTES = 20 * 1024 * 1024;
const MAX_FILES = 200;
const MAX_ENTRIES = 100_000;

export async function collectChildLogs(root, destination) {
  const manifest = { fileBytes: FILE_BYTES, totalBytes: TOTAL_BYTES, maxFiles: MAX_FILES,
    files: [], errors: [], omitted: 0, scanLimitReached: false };
  let remaining = TOTAL_BYTES;
  let entries = 0;
  // The installed Engine embeds the worker. Its application output is engine.log;
  // supervisor.log and backend.stdout/stderr.log live in each fixture's state/runs tree.
  const selected = (name) => /\.log(?:\.\d+)?(?:\.gz)?$/.test(name)
    || /(?:^|-)output\.txt$/.test(name) || /^crash-/.test(name)
    || /^core-summary.*\.txt$/.test(name)
    || ['stop-report.json', 'intruder-acquisitions.json'].includes(name);
  async function walk(relative, descend = true) {
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
        if (descend && !['models', 'indices', 'index', 'corpus', 'node_modules'].includes(child.name)) await walk(item);
      } else if (child.isFile() && selected(child.name)) {
        if (remaining === 0 || manifest.files.length >= MAX_FILES) { manifest.omitted++; continue; }
        let fd;
        try {
          fd = fs.openSync(path.join(root, item), 'r');
          const size = fs.fstatSync(fd).size;
          const bytes = Math.min(size, FILE_BYTES, remaining);
          let data;
          if (child.name.endsWith('.log.gz') && size > bytes) {
            // A byte slice of gzip is unreadable. Keep a recompressed text tail instead;
            // streaming decompression bounds memory even for a very compressible log.
            let tail = Buffer.alloc(0);
            const input = fs.createReadStream(path.join(root, item));
            const decoded = input.pipe(createGunzip());
            input.on('error', (error) => decoded.destroy(error));
            try {
              for await (const chunk of decoded) {
                tail = Buffer.concat([tail, chunk]).subarray(-bytes);
              }
            } finally {
              input.destroy();
            }
            data = gzipSync(tail);
            while (data.length > bytes && tail.length) {
              tail = tail.subarray(Math.min(tail.length, data.length - bytes));
              data = gzipSync(tail);
            }
            if (data.length > bytes) { manifest.omitted++; continue; }
          } else {
            data = Buffer.alloc(bytes);
            const copied = fs.readSync(fd, data, 0, bytes, size - bytes);
            data = data.subarray(0, copied);
          }
          const output = path.join(destination, item);
          fs.mkdirSync(path.dirname(output), { recursive: true });
          fs.writeFileSync(output, data);
          remaining -= data.length;
          manifest.files.push({ path: item, originalBytes: size, copiedBytes: data.length, truncated: size > data.length });
        } catch (error) {
          manifest.errors.push({ path: item, error: error.message });
        } finally {
          if (fd !== undefined) fs.closeSync(fd);
        }
      }
    }
  }
  // isolated-backend is already curated and uploaded whole by both E2E jobs.
  const fixtures = 'tmp/lane-f-takeover';
  try {
    const directory = path.join(root, fixtures);
    if (!fs.lstatSync(directory).isSymbolicLink()) {
      for (const entry of fs.readdirSync(directory, { withFileTypes: true })
        .sort((a, b) => a.name.localeCompare(b.name))) {
        if (!entry.isDirectory() || entry.isSymbolicLink()) continue;
        const fixture = path.join(fixtures, entry.name);
        // Corpus trees can contain hundreds of thousands of input files. The
        // harness owns these log/crash locations and its top-level output files.
        await walk(path.join(fixture, 'data/logs'));
        await walk(path.join(fixture, 'data/crashes'));
        await walk(path.join(fixture, 'data'), false);
        await walk(path.join(fixture, 'state/runs'));
        await walk(fixture, false);
      }
    }
  } catch (error) {
    if (error.code !== 'ENOENT') manifest.errors.push({ path: fixtures, error: error.message });
  }
  fs.mkdirSync(destination, { recursive: true });
  fs.writeFileSync(path.join(destination, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
  return manifest;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const result = await collectChildLogs(process.cwd(), path.resolve('build/ci/e2e-child-logs'));
  console.log(`Collected ${result.files.length} child logs (${result.files.reduce((n, f) => n + f.copiedBytes, 0)} bytes); ${result.omitted} omitted`);
  if (result.errors.length || result.scanLimitReached) {
    console.error(JSON.stringify({ errors: result.errors, scanLimitReached: result.scanLimitReached }));
    process.exitCode = 1;
  }
}
