import assert from 'node:assert/strict';
import { brotliDecompressSync } from 'node:zlib';

// WOFF2 directory: https://www.w3.org/TR/WOFF2/#table_dir_format
const tags = ('cmap head hhea hmtx maxp name OS/2 post cvt_ fpgm glyf loca prep CFF_ VORG EBDT '
  + 'EBLC gasp hdmx kern LTSH PCLT VDMX vhea vmtx BASE GDEF GPOS GSUB EBSC JSTF MATH '
  + 'CBDT CBLC COLR CPAL SVG_ sbix acnt avar bdat bloc bsln cvar fdsc feat fmtx fvar '
  + 'gvar hsty just lcar mort morx opbd prop trak Zapf Silf Glat Gloc Feat Sill').split(' ').map(tag => tag.replace('_', ' '));

// Only fvar is decoded; transformed glyph tables need no reconstruction to locate it.
export function readWoff2Axes(file) {
  assert.ok(file.length >= 48 && file.toString('ascii', 0, 4) === 'wOF2', 'not a WOFF2 file');
  assert.notEqual(file.toString('ascii', 4, 8), 'ttcf', 'font collections are unsupported');
  assert.equal(file.readUInt32BE(8), file.length, 'invalid WOFF2 file length');
  let cursor = 48, dataLength = 0, fvar;
  const byte = () => { assert.ok(cursor < file.length, 'truncated WOFF2 directory'); return file[cursor++]; };
  const base128 = () => {
    let value = 0;
    for (let i = 0; i < 5; i++) {
      const b = byte();
      assert.ok(!(i === 0 && b === 0x80), 'leading zero in UIntBase128');
      value = value * 128 + (b & 0x7f);
      assert.ok(value <= 0xffffffff, 'UIntBase128 overflow');
      if (!(b & 0x80)) return value;
    }
    throw new Error('UIntBase128 exceeds five bytes');
  };
  for (let i = 0; i < file.readUInt16BE(12); i++) {
    const flags = byte(), index = flags & 63, version = flags >> 6;
    let tag = tags[index];
    if (index === 63) { tag = Buffer.from([byte(), byte(), byte(), byte()]).toString('ascii'); }
    const originalLength = base128();
    const transformed = tag === 'glyf' || tag === 'loca' ? version !== 3 : version !== 0;
    const length = transformed ? base128() : originalLength;
    if (tag === 'fvar') {
      assert.ok(!transformed && !fvar, 'invalid or duplicate fvar table');
      fvar = { offset: dataLength, length };
    }
    dataLength += length;
  }
  const compressedLength = file.readUInt32BE(20);
  assert.ok(cursor + compressedLength <= file.length, 'truncated WOFF2 compressed data');
  const data = brotliDecompressSync(file.subarray(cursor, cursor + compressedLength), { maxOutputLength: 32 * 1024 * 1024 });
  assert.equal(data.length, dataLength, 'WOFF2 table lengths do not match decoded data');
  if (!fvar) return {};
  const table = data.subarray(fvar.offset, fvar.offset + fvar.length);
  assert.ok(table.length >= 16 && table.readUInt16BE(0) === 1, 'invalid fvar header');
  const start = table.readUInt16BE(4), count = table.readUInt16BE(8), size = table.readUInt16BE(10);
  assert.ok(start >= 16 && size >= 20 && start + count * size <= table.length, 'truncated fvar axes');
  const axes = {};
  for (let i = 0; i < count; i++) {
    const offset = start + i * size, tag = table.toString('ascii', offset, offset + 4);
    // OpenType Fixed is signed 16.16, not an integer or a float32.
    axes[tag] = [table.readInt32BE(offset + 4) / 65536, table.readInt32BE(offset + 12) / 65536];
  }
  return axes;
}
