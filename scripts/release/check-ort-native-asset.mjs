#!/usr/bin/env node
/**
 * ORT native pack asset content check (tempdoc 805 G.3 W-TRUTH, "asset guarantee").
 *
 * Round 11 (tempdoc 734 R11-F3): PR #276 trimmed the onnxruntime jar and moved the ORT natives into
 * a NEW `ort-native-cuda12-v<ver>.zip` supporting file. The registry sha-pins that asset, so the
 * bytes are verified — but nothing verified the bytes CONTAIN the four DLLs `OrtCudaHelper`
 * requires. A pack missing one of them does not fail loudly: ORT silently falls back to CPU and
 * every ONNX encoder runs at CPU speed while the status surfaces report a GPU variant active.
 *
 * This is the check at the only place the contents change — asset (re)publish, BEFORE the sha is
 * pinned into `model-registry.v2.json`. Once pinned, the verified bytes are the only accepted bytes.
 *
 *   node scripts/release/check-ort-native-asset.mjs <path-to-ort-native-cuda12-v1.24.3.zip>
 *
 * Exit 0 = every required DLL present. Exit 1 = missing DLLs listed (or the file is unreadable /
 * not a zip). The required library IDs and version marker are READ FROM `OrtCudaHelper.java` — the
 * authority the runtime checks against, with names resolved through the native-target contract —
 * never a second hardcoded copy that could drift (same discipline as `scripts/dev/restage-ort-pack.mjs`, whose minimal central-directory
 * reader this borrows).
 *
 * Layout tolerance: entries may sit at the archive root OR under a single top-level directory
 * (`cuda12/onnxruntime.dll`), which is how zip tools commonly wrap a folder.
 */

import { readFileSync } from 'node:fs';
import { nativeTarget } from '../lib/native-targets.cjs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const ORT_HELPER = resolve(
  REPO_ROOT,
  'modules/ort-common/src/main/java/io/justsearch/ort/OrtCudaHelper.java',
);

/** Read Java library IDs and resolve filenames with the same native-target contract as the runtime. */
export function readRequiredDlls(helperPath = ORT_HELPER, target = nativeTarget()) {
  const java = readFileSync(helperPath, 'utf8');
  const setBlock = java.match(/ortNativeDllSet\(\)\s*\{\s*return\s+nativeLibraryNames\(([^)]*)\)\s*;/);
  const ids = setBlock ? [...setBlock[1].matchAll(/"([^"]+)"/g)].map(m => m[1]) : [];
  const dlls = ids.map(id => {
    const name = target.libraries[id];
    if (!name) throw new Error(`unknown ORT native library id: ${id}`);
    return name;
  });
  if (dlls.length === 0) {
    throw new Error(`could not parse ortNativeDllSet() from ${helperPath}`);
  }
  const marker = java.match(/ORT_NATIVE_VERSION_MARKER\s*=\s*"([^"]*)"/);
  const version = java.match(/EXPECTED_ORT_NATIVE_VERSION\s*=\s*"([^"]*)"/);
  return { dlls, marker: marker ? marker[1] : null, version: version ? version[1] : null };
}

/**
 * Lists entry names from a zip's central directory. Central-directory-only (no inflate): the
 * question is which names exist, and a 160 MB pack should not be decompressed to answer it.
 */
export function listZipEntryNames(buf) {
  const min = Math.max(0, buf.length - 22 - 65535);
  let p = buf.length - 22;
  for (; p >= min; p--) {
    if (buf.readUInt32LE(p) === 0x06054b50) break; // EOCD signature
  }
  if (p < min) throw new Error('end-of-central-directory record not found (not a zip?)');
  const cdOffset = buf.readUInt32LE(p + 16);
  const total = buf.readUInt16LE(p + 10);
  const names = [];
  let o = cdOffset;
  for (let i = 0; i < total; i++) {
    if (buf.readUInt32LE(o) !== 0x02014b50) throw new Error('malformed central-directory header');
    const nameLen = buf.readUInt16LE(o + 28);
    const extraLen = buf.readUInt16LE(o + 30);
    const commentLen = buf.readUInt16LE(o + 32);
    names.push(buf.toString('utf8', o + 46, o + 46 + nameLen));
    o += 46 + nameLen + extraLen + commentLen;
  }
  return names;
}

/**
 * Which required files the archive does NOT carry, tolerating a single wrapping directory.
 *
 * @param names zip entry names
 * @param required file names that must be present
 * @returns the missing names (empty = complete)
 */
export function missingRequiredFiles(names, required) {
  const flattened = new Set();
  for (const name of names) {
    if (name.endsWith('/')) continue; // directory entry
    const parts = name.replace(/\\/g, '/').split('/');
    if (parts.length === 1) {
      flattened.add(parts[0].toLowerCase());
    } else if (parts.length === 2) {
      // Single wrapping directory — the common "zipped a folder" shape.
      flattened.add(parts[1].toLowerCase());
    }
    // Deeper nesting is NOT flattened: OrtCudaHelper resolves the DLLs directly under the pack
    // dir, so a file three levels down would not be found at runtime either.
  }
  return required.filter((f) => !flattened.has(f.toLowerCase()));
}

/** Returns {missing, names} for an on-disk archive. */
export function checkAsset(zipPath, helperPath = ORT_HELPER, target = nativeTarget()) {
  const { dlls, marker } = readRequiredDlls(helperPath, target);
  const names = listZipEntryNames(readFileSync(zipPath));
  return {
    required: dlls,
    marker,
    names,
    missing: missingRequiredFiles(names, dlls),
    markerMissing: marker ? missingRequiredFiles(names, [marker]).length > 0 : false,
  };
}

function main(argv) {
  const zipPath = argv[2];
  if (!zipPath) {
    console.error('usage: node scripts/release/check-ort-native-asset.mjs <ort-native-pack.zip>');
    return 1;
  }
  let result;
  try {
    result = checkAsset(zipPath);
  } catch (e) {
    console.error(`[check-ort-native-asset] ERROR — ${zipPath}: ${e.message}`);
    return 1;
  }
  if (result.missing.length > 0) {
    console.error(`[check-ort-native-asset] FAIL — ${zipPath} is missing required ORT natives:`);
    for (const dll of result.missing) console.error(`  - ${dll}`);
    console.error(
      '  A pack missing any of these does not fail loudly: ORT falls back to CPU silently.',
    );
    return 1;
  }
  console.log(
    `[check-ort-native-asset] OK — ${zipPath} carries all ${result.required.length} required ORT natives.`,
  );
  if (result.markerMissing) {
    // Not fatal here: the marker is checked by the release coupling checklist and enforced at
    // runtime by OrtCudaHelper (VERSION_MISMATCH). Reported so a missing marker is not a surprise.
    console.log(
      `[check-ort-native-asset] NOTE — version marker ${result.marker} not found in the archive.`,
    );
  }
  return 0;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  process.exit(main(process.argv));
}
