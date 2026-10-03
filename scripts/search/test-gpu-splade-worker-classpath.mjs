#!/usr/bin/env node
/**
 * The launched distribution must carry the GPU ONNX Runtime jar, and not the CPU one — SPLADE (and
 * every other ORT session) silently falls back to CPU when `onnxruntime-<ver>.jar` wins the
 * classpath instead of `onnxruntime_gpu-<ver>.jar`.
 *
 * Lane F stage A item A13: this used to read the Worker distribution's start script
 * (modules/indexer-worker/build/install/indexer-worker/bin/indexer-worker.bat) and match jar names
 * in its explicit `set CLASSPATH=` line. That distribution is gone. The surviving one is the
 * Engine's, and its start script sets `CLASSPATH=%APP_HOME%\lib\*` (a wildcard, no jar names), so
 * the classpath IS the lib directory and that is what is checked here.
 *
 * The ORT version is deliberately NOT pinned: the old assertion pinned 1.19.2 while the installed
 * jar was 1.24.3, so it had been red long before A13 — and the version belongs to the Gradle
 * version catalog, not to a second copy here. The property is which ORT build is on the classpath.
 *
 * Requires a built dist: ./gradlew.bat :modules:ui:installDist
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const repoRoot = path.resolve(import.meta.dirname, '..', '..');
const libDir = path.join(repoRoot, 'modules', 'ui', 'build', 'install', 'ui', 'lib');

assert.ok(
  fs.existsSync(libDir),
  `Engine dist lib dir not found: ${libDir} (run: ./gradlew.bat :modules:ui:installDist)`,
);

const jars = fs.readdirSync(libDir).filter((f) => f.endsWith('.jar'));
const gpuJars = jars.filter((f) => /^onnxruntime_gpu-[\d.]+\.jar$/.test(f));
const cpuJars = jars.filter((f) => /^onnxruntime-[\d.]+\.jar$/.test(f));

assert.equal(
  gpuJars.length,
  1,
  `expected exactly one onnxruntime_gpu-*.jar on the Engine classpath (${libDir}), found: ${JSON.stringify(gpuJars)}`,
);
assert.deepEqual(
  cpuJars,
  [],
  `the CPU onnxruntime jar must not be on the Engine classpath, found: ${JSON.stringify(cpuJars)}`,
);

console.log(`ok — ${gpuJars[0]}`);
