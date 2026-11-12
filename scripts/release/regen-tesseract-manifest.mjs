#!/usr/bin/env node
/**
 * Regenerate the per-file SHA-256 / size pins in packaging/runtime/tesseract-windows.v1.json from a
 * signed Tesseract mirror archive (tempdoc 760/772 §K sign-once-per-upstream-bump).
 *
 * Why this exists: the signed-mirror override (`tesseractSourceUrlOverride` /
 * `tesseractSourceSha256Override`) is ARCHIVE-level. The manifest ALSO pins each staged file by
 * sha256 + sizeBytes in `files[]`, and signing rewrites the inner PE bytes, so those pins stop
 * matching. `verifyTesseractRuntime` deliberately does NOT skip per-file verification for a signed
 * mirror — it fails with a regeneration instruction (modules/ui/build.gradle.kts:1419-1433). This
 * script is that regeneration step.
 *
 *   node scripts/release/regen-tesseract-manifest.mjs <tesseract-...-signed.zip>
 *   node scripts/release/regen-tesseract-manifest.mjs <tesseract-...-signed.zip> --check
 *
 * `--check` verifies instead of writing: exit 0 when the manifest already matches the archive,
 * exit 1 (listing every drifted entry) when it does not.
 *
 * SCOPE — what it does NOT touch:
 *  - `sourceUrl` / `sourceSha256` (the ARCHIVE pin). The signed mirror's URL + sha256 belong in
 *    `packaging/signed-mirrors.v1.json`, which is what drives the gradle override pair; the
 *    manifest keeps pinning the genuine UPSTREAM archive so the default (no-mirror) build is
 *    unchanged.
 *  - Any `files[]` entry carrying its own `sourceUrl` (German, English and OSD tessdata). Those
 *    are downloaded separately and copied over the extraction
 *    (modules/ui/build.gradle.kts:1321-1328, 1385-1389) — they are not in the archive at all, and
 *    signing cannot have changed them.
 *
 * The rewrite is surgical: only the sha256/sizeBytes VALUES of affected entries are replaced in the
 * raw text, so key order, indentation and every unrelated byte survive untouched. Deterministic and
 * idempotent — running it twice produces the same bytes, and a second run reports no changes.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, isAbsolute, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { inflateRawSync } from 'node:zlib';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const DEFAULT_MANIFEST = 'packaging/runtime/tesseract-windows.v1.json';

/* -- minimal zip reader: central-directory scan + per-entry inflate (borrowed from
      scripts/dev/restage-ort-pack.mjs, the existing in-repo precedent) -- */

export function readCentralDirectory(buf) {
  const min = Math.max(0, buf.length - 22 - 65535);
  let p = buf.length - 22;
  for (; p >= min; p--) {
    if (buf.readUInt32LE(p) === 0x06054b50) break; // EOCD signature
  }
  if (p < min) throw new Error('end-of-central-directory record not found (not a zip?)');
  const cdOffset = buf.readUInt32LE(p + 16);
  const total = buf.readUInt16LE(p + 10);
  const entries = new Map();
  let o = cdOffset;
  for (let i = 0; i < total; i++) {
    if (buf.readUInt32LE(o) !== 0x02014b50) throw new Error('malformed central-directory header');
    const method = buf.readUInt16LE(o + 10);
    const compSize = buf.readUInt32LE(o + 20);
    const nameLen = buf.readUInt16LE(o + 28);
    const extraLen = buf.readUInt16LE(o + 30);
    const commentLen = buf.readUInt16LE(o + 32);
    const localOff = buf.readUInt32LE(o + 42);
    const name = buf.toString('utf8', o + 46, o + 46 + nameLen).replace(/\\/g, '/');
    entries.set(name, { method, compSize, localOff });
    o += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

export function extractEntry(buf, entry) {
  const lo = entry.localOff;
  if (buf.readUInt32LE(lo) !== 0x04034b50) throw new Error('malformed local file header');
  const nameLen = buf.readUInt16LE(lo + 26);
  const extraLen = buf.readUInt16LE(lo + 28);
  const dataStart = lo + 30 + nameLen + extraLen;
  const comp = buf.subarray(dataStart, dataStart + entry.compSize);
  if (entry.method === 0) return Buffer.from(comp); // stored
  if (entry.method === 8) return inflateRawSync(comp); // deflate
  throw new Error(`unsupported zip compression method ${entry.method}`);
}

/* ------------------------------------------------------------------------- */

/**
 * Which manifest entries the archive must re-pin, and their recomputed values.
 *
 * @returns {{updates: Array<{path: string, sha256: string, sizeBytes: number, changed: boolean}>,
 *            skipped: string[]}}
 */
export function computeUpdates(manifest, zipBuf) {
  const entries = readCentralDirectory(zipBuf);
  const files = Array.isArray(manifest.files) ? manifest.files : null;
  if (!files || files.length === 0) throw new Error('manifest has no files[] array');

  const updates = [];
  const skipped = [];
  for (const file of files) {
    const path = file.path;
    if (typeof path !== 'string' || !path) throw new Error('a files[] entry has no "path"');
    if (typeof file.sourceUrl === 'string' && file.sourceUrl) {
      // Separately downloaded and copied over the extraction — never sourced from the archive.
      skipped.push(path);
      continue;
    }
    const entry = entries.get(path);
    if (!entry) {
      throw new Error(
        `manifest pins "${path}" but the archive has no such entry. ` +
          'Either the archive is not the Tesseract runtime mirror, or the upstream layout changed ' +
          '(the manifest files[] paths resolve against the extraction root — ' +
          'modules/ui/build.gradle.kts:1442).',
      );
    }
    const bytes = extractEntry(zipBuf, entry);
    const sha256 = createHash('sha256').update(bytes).digest('hex');
    const sizeBytes = bytes.length;
    updates.push({
      path,
      sha256,
      sizeBytes,
      changed: sha256 !== String(file.sha256).toLowerCase() || sizeBytes !== file.sizeBytes,
    });
  }
  return { updates, skipped };
}

/**
 * Replaces one entry's sha256 + sizeBytes VALUES in the raw manifest text, leaving every other
 * byte (key order, spacing, line endings, comments-by-convention) untouched.
 */
export function rewriteEntry(raw, path, sha256, sizeBytes) {
  const filesAt = raw.indexOf('"files"');
  if (filesAt < 0) throw new Error('manifest text has no "files" key');
  const pathRe = new RegExp(`"path"\\s*:\\s*${escapeRegExp(JSON.stringify(path))}`);
  const inFiles = raw.slice(filesAt);
  const rel = inFiles.search(pathRe);
  if (rel < 0) throw new Error(`could not locate the "${path}" entry in the manifest text`);
  const start = filesAt + rel;
  const nextRel = inFiles.slice(rel + 1).search(/"path"\s*:/);
  const end = nextRel < 0 ? raw.length : start + 1 + nextRel;

  let window = raw.slice(start, end);
  const shaRe = /("sha256"\s*:\s*")[0-9a-fA-F]*(")/;
  const sizeRe = /("sizeBytes"\s*:\s*)\d+/;
  if (!shaRe.test(window)) throw new Error(`entry "${path}" has no "sha256" field to rewrite`);
  if (!sizeRe.test(window)) throw new Error(`entry "${path}" has no "sizeBytes" field to rewrite`);
  window = window.replace(shaRe, `$1${sha256}$2`).replace(sizeRe, `$1${sizeBytes}`);
  return raw.slice(0, start) + window + raw.slice(end);
}

function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Full text transform: raw manifest + archive bytes -> new raw manifest. */
export function regenerate(raw, zipBuf) {
  const manifest = JSON.parse(raw);
  const { updates, skipped } = computeUpdates(manifest, zipBuf);
  let out = raw;
  for (const u of updates) {
    if (!u.changed) continue;
    out = rewriteEntry(out, u.path, u.sha256, u.sizeBytes);
  }
  return { text: out, updates, skipped };
}

function main(argv) {
  const args = argv.slice(2);
  const check = args.includes('--check');
  let manifestArg = null;
  const positional = [];
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--check') continue;
    if (args[i] === '--manifest') {
      manifestArg = args[++i];
      continue;
    }
    if (args[i].startsWith('-')) {
      console.error(`[regen-tesseract-manifest] unknown option: ${args[i]}`);
      return 1;
    }
    positional.push(args[i]);
  }
  const archiveArg = positional[0];
  if (!archiveArg || positional.length > 1) {
    console.error(
      'usage: node scripts/release/regen-tesseract-manifest.mjs <tesseract-signed.zip> [--check] [--manifest <path>]',
    );
    return 1;
  }
  const archivePath = isAbsolute(archiveArg) ? archiveArg : resolve(process.cwd(), archiveArg);
  const manifestPath = manifestArg
    ? isAbsolute(manifestArg)
      ? manifestArg
      : resolve(process.cwd(), manifestArg)
    : resolve(REPO_ROOT, DEFAULT_MANIFEST);

  let result;
  try {
    result = regenerate(readFileSync(manifestPath, 'utf8'), readFileSync(archivePath));
  } catch (e) {
    console.error(`[regen-tesseract-manifest] ERROR — ${e.message}`);
    return 1;
  }
  const { text, updates, skipped } = result;
  const changed = updates.filter((u) => u.changed);

  for (const p of skipped) {
    console.log(`[regen-tesseract-manifest] skip (own sourceUrl, not in the archive): ${p}`);
  }
  for (const u of updates) {
    console.log(
      `[regen-tesseract-manifest] ${u.changed ? 'DRIFT' : 'match'} ${u.path} ` +
        `sha256=${u.sha256} sizeBytes=${u.sizeBytes}`,
    );
  }

  if (check) {
    if (changed.length === 0) {
      console.log(`[regen-tesseract-manifest] OK — ${manifestPath} matches ${archivePath}.`);
      return 0;
    }
    const noun = changed.length === 1 ? 'entry does' : 'entries do';
    console.error(
      `[regen-tesseract-manifest] FAIL — ${changed.length} ${noun} not match ${archivePath} in ` +
        `${manifestPath}. Re-run without --check to rewrite.`,
    );
    return 1;
  }

  if (changed.length === 0) {
    console.log(`[regen-tesseract-manifest] no changes — ${manifestPath} already matches.`);
    return 0;
  }
  writeFileSync(manifestPath, text, 'utf8');
  console.log(
    `[regen-tesseract-manifest] rewrote ${changed.length} entr${changed.length === 1 ? 'y' : 'ies'} in ${manifestPath}.`,
  );
  return 0;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  process.exit(main(process.argv));
}
