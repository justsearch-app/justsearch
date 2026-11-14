import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { brotliCompressSync } from 'node:zlib';
import { test } from 'vitest';
import { readWoff2Axes } from '../../e2e/woff2-axes.mjs';

for (const name of ['InterVariable.woff2', 'InterVariable-Italic.woff2']) {
  test(`${name} ships display optical size and variable weight axes`, () => {
    const axes = readWoff2Axes(readFileSync(new URL(`../../public/fonts/${name}`, import.meta.url)));
    assert.deepEqual(axes, { opsz: [14, 32], wght: [100, 900] });
  });
}

// A small WOFF2 fixture with a weight-only fvar. No external font tooling is needed.
function weightOnly() {
  const table = Buffer.alloc(36);
  table.writeUInt16BE(1, 0); table.writeUInt16BE(16, 4); table.writeUInt16BE(2, 6);
  table.writeUInt16BE(1, 8); table.writeUInt16BE(20, 10);
  table.write('wght', 16); table.writeInt32BE(100 * 65536, 20);
  table.writeInt32BE(400 * 65536, 24); table.writeInt32BE(900 * 65536, 28);
  const compressed = brotliCompressSync(table), header = Buffer.alloc(48);
  header.write('wOF2'); header.writeUInt32BE(0x00010000, 4);
  header.writeUInt32BE(50 + compressed.length, 8); header.writeUInt16BE(1, 12);
  header.writeUInt32BE(64, 16); header.writeUInt32BE(compressed.length, 20);
  return Buffer.concat([header, Buffer.from([47, table.length]), compressed]);
}

test('a WOFF2 without opsz fails the same display-range assertion', () => {
  const axes = readWoff2Axes(weightOnly());
  assert.deepEqual(axes, { wght: [100, 900] });
  assert.throws(() => assert.ok((axes.opsz?.[0] ?? NaN) <= 20 && (axes.opsz?.[1] ?? NaN) >= 28
    && (axes.wght?.[0] ?? NaN) <= 400 && (axes.wght?.[1] ?? NaN) >= 600));
});

test('truncated and corrupt fonts fail rather than silently passing', () => {
  assert.throws(() => readWoff2Axes(weightOnly().subarray(0, 49)));
  const corrupt = weightOnly(); corrupt.writeUInt8(corrupt.readUInt8(corrupt.length - 1) ^ 255, corrupt.length - 1);
  assert.throws(() => readWoff2Axes(corrupt));
});
