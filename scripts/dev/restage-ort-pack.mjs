#!/usr/bin/env node
/**
 * Dev ORT native-pack re-stage helper (tempdoc 787 item 2).
 *
 * `OrtCudaHelper.applyOrtNativePackProperty` (tempdoc 772 §J) refuses an INCOMPLETE
 * `tmp/ort-variant-test/<variant>` dir: setting `onnxruntime.native.path` reroutes the ENTIRE ORT
 * native set to that dir, so a pre-772 layout carrying only the provider/cuDNN DLLs (missing the
 * core `onnxruntime.dll` + `onnxruntime4j_jni.dll` and the version marker) fails detection and ORT
 * silently falls back to CPU — killing GPU inference for every ONNX eval run.
 *
 * This helper completes such a pack: it validates the dir against
 * `OrtCudaHelper.ortNativeDllSet()` (the four required native libraries) + the version marker,
 * extracts missing libraries from the gradle-cache `onnxruntime_gpu-<version>.jar` (entries in
 * the current platform’s native resource directory), writes the version marker, and prints a summary. It NEVER deletes anything. Exits nonzero if it cannot complete the pack.
 *
 * The required version, DLL set, and marker filename are read from OrtCudaHelper.java at runtime —
 * no second hardcoded copy that could drift from the Java authority.
 *
 *   node scripts/dev/restage-ort-pack.mjs                 # default pack dir
 *   node scripts/dev/restage-ort-pack.mjs <packDir>        # explicit pack dir (abs or repo-relative)
 */

import { readFileSync, writeFileSync, existsSync, mkdirSync, readdirSync, statSync } from 'node:fs';
import { resolve, dirname, join, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';
import { inflateRawSync } from 'node:zlib';
import { nativeTarget } from '../lib/native-targets.cjs';
import { readRequiredDlls } from '../release/check-ort-native-asset.mjs';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const DEFAULT_PACK = 'tmp/ort-variant-test/cuda-12.4-v1.24.3';
const target = nativeTarget();

function die(msg) {
  console.error(`[restage-ort-pack] ERROR — ${msg}`);
  process.exit(1);
}

/** Read the single ORT authority (OrtCudaHelper.java) so nothing is hardcoded twice. */
function readOrtAuthority() {
  let authority;
  try {
    authority = readRequiredDlls();
  } catch (error) {
    die(error.message);
  }
  const { version, marker, dlls } = authority;
  if (!version) die('could not parse EXPECTED_ORT_NATIVE_VERSION from OrtCudaHelper.java');
  if (!marker) die('could not parse ORT_NATIVE_VERSION_MARKER from OrtCudaHelper.java');
  return { version, marker, dlls };
}

/** Locate the resolved onnxruntime_gpu jar in the gradle cache the build already populated. */
function findGpuJar(version) {
  const roots = [];
  if (process.env.GRADLE_USER_HOME) roots.push(process.env.GRADLE_USER_HOME);
  roots.push(join(homedir(), '.gradle'));
  const rel = join(
    'caches',
    'modules-2',
    'files-2.1',
    'com.microsoft.onnxruntime',
    'onnxruntime_gpu',
    version,
  );
  const searched = [];
  for (const root of roots) {
    const base = join(root, rel);
    searched.push(base);
    if (!existsSync(base)) continue;
    for (const sub of readdirSync(base)) {
      const jar = join(base, sub, `onnxruntime_gpu-${version}.jar`);
      if (existsSync(jar)) return { jar, searched };
    }
  }
  return { jar: null, searched };
}

/* -- minimal zip reader (a jar is a zip): central-directory scan + per-entry inflate -- */

function readCentralDirectory(buf) {
  const min = Math.max(0, buf.length - 22 - 65535);
  let p = buf.length - 22;
  for (; p >= min; p--) {
    if (buf.readUInt32LE(p) === 0x06054b50) break; // EOCD signature
  }
  if (p < min) throw new Error('end-of-central-directory record not found (not a zip/jar?)');
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
    const name = buf.toString('utf8', o + 46, o + 46 + nameLen);
    entries.set(name, { method, compSize, localOff });
    o += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

function extractEntry(buf, entry) {
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

const { version, marker, dlls } = readOrtAuthority();

const arg = process.argv.slice(2).find((a) => !a.startsWith('-'));
const packDir = arg ? (isAbsolute(arg) ? arg : resolve(REPO_ROOT, arg)) : resolve(REPO_ROOT, DEFAULT_PACK);

console.log(`[restage-ort-pack] pack dir: ${packDir}`);
console.log(`[restage-ort-pack] authority: OrtCudaHelper.java — version ${version}, ${dlls.length} DLL(s), marker '${marker}'`);

if (!existsSync(packDir)) {
  mkdirSync(packDir, { recursive: true });
  console.log(`[restage-ort-pack] created pack dir (did not exist).`);
} else if (!statSync(packDir).isDirectory()) {
  die(`pack path exists but is not a directory: ${packDir}`);
}

const present = dlls.filter((d) => existsSync(join(packDir, d)));
const missing = dlls.filter((d) => !existsSync(join(packDir, d)));

console.log('');
console.log(`[restage-ort-pack] present DLLs (${present.length}/${dlls.length}): ${present.join(', ') || '(none)'}`);
console.log(`[restage-ort-pack] missing DLLs (${missing.length}/${dlls.length}): ${missing.join(', ') || '(none)'}`);

let jarBuf = null;
let jarEntries = null;
let jarPath = null;

if (missing.length > 0) {
  const { jar, searched } = findGpuJar(version);
  if (!jar) {
    die(
      `${missing.length} DLL(s) missing but no onnxruntime_gpu-${version}.jar found in the gradle ` +
        `cache. Searched:\n  ${searched.join('\n  ')}\n` +
        `Run './gradlew :modules:ort-common:dependencies' (gradlew.bat on Windows) (or any build touching onnxruntime_gpu) ` +
        `to populate the cache, then re-run.`,
    );
  }
  jarPath = jar;
  console.log(`[restage-ort-pack] source jar: ${jar}`);
  try {
    jarBuf = readFileSync(jar);
    jarEntries = readCentralDirectory(jarBuf);
  } catch (e) {
    die(`failed to read/parse ${jar}: ${e.message}`);
  }
}

const completed = [];
const failures = [];
for (const dll of missing) {
  const entryName = Object.values(target.ortResources).find(name => name.endsWith(`/${dll}`));
  const entry = jarEntries.get(entryName);
  if (!entry) {
    failures.push(`${dll} — no entry '${entryName}' in ${jarPath}`);
    continue;
  }
  try {
    const data = extractEntry(jarBuf, entry);
    writeFileSync(join(packDir, dll), data);
    completed.push(`${dll} (${data.length.toLocaleString()} bytes)`);
  } catch (e) {
    failures.push(`${dll} — extraction failed: ${e.message}`);
  }
}

// Write / refresh the version marker (required for detection; never a delete).
const markerPath = join(packDir, marker);
let markerAction = 'wrote';
if (existsSync(markerPath)) {
  const cur = readFileSync(markerPath, 'utf8').split(/\r?\n/)[0]?.trim();
  markerAction = cur === version ? 'confirmed' : 'corrected';
}
writeFileSync(markerPath, `${version}\n`);

console.log('');
if (completed.length > 0) console.log(`[restage-ort-pack] extracted: ${completed.join(', ')}`);
console.log(`[restage-ort-pack] marker '${marker}': ${markerAction} → ${version}`);

if (failures.length > 0) {
  console.error('');
  console.error(`[restage-ort-pack] FAILED to complete the pack — ${failures.length} DLL(s) could not be staged:`);
  for (const f of failures) console.error(`  ${f}`);
  process.exit(1);
}

const finalPresent = dlls.filter((d) => existsSync(join(packDir, d)));
console.log('');
if (finalPresent.length === dlls.length) {
  console.log(`[restage-ort-pack] OK — pack complete: ${dlls.length}/${dlls.length} DLLs + marker '${marker}' (${version}).`);
  console.log('OrtCudaHelper.applyOrtNativePackProperty will now accept this dir (GPU EP path).');
  process.exit(0);
}
die(`pack still incomplete after staging: ${dlls.filter((d) => !finalPresent.includes(d)).join(', ')}`);
