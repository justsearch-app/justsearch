#!/usr/bin/env node
/**
 * Lane F PR 0 (docs/design/lane-f-engine-jvm/design.md section 17.2): the Head launch-flag set
 * the dev-runner emits.
 *
 * Pins what 917 Derisk 1 measured and the design decided: the Head keeps SerialGC, no longer
 * runs C1-only (`-XX:TieredStopAtLevel=1` triggered the CodeCache-threshold full GCs and
 * conflicted with the AOT cache), and carries `-XX:MetaspaceSize=128m` so the Metaspace-threshold
 * full GCs at start stop firing. The Tauri spawn site (modules/shell/src-tauri/src/lib.rs) carries
 * the same set by hand; this test is the executable half of that pairing.
 */
import assert from 'node:assert/strict';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const require = createRequire(import.meta.url);
const { buildHeadJavaOpts } = require(path.join(__dirname, 'dev-runner.cjs')).__test;

function flags(opts) {
  return buildHeadJavaOpts(opts).split(/\s+/).filter(Boolean);
}

function main() {
  const base = { existingJavaOpts: undefined, headAotOpts: null, headDistStamp: null, logsDir: null, headHeap: null };

  // 1. The PR 0 set, without an AOT cache.
  const noAot = flags(base);
  assert.ok(noAot.includes('-XX:+UseSerialGC'), 'Head keeps SerialGC (the collector choice is the gate run, 8)');
  assert.ok(noAot.includes('-XX:MetaspaceSize=128m'), 'MetaspaceSize=128m is emitted');
  assert.ok(noAot.includes('-XX:-UsePerfData'), 'UsePerfData stays off');
  assert.ok(!noAot.some((f) => f.startsWith('-XX:TieredStopAtLevel')), 'TieredStopAtLevel is gone');

  // 2. The same set with an AOT cache: the flag set does not fork on cache presence any more.
  const withAot = flags({ ...base, headAotOpts: '-XX:AOTCache=C:/x/head.aot' });
  assert.deepEqual(
    withAot.filter((f) => !f.startsWith('-XX:AOTCache')),
    noAot,
    'the JVM flag set is identical with and without the AOT cache',
  );
  assert.ok(withAot.includes('-XX:AOTCache=C:/x/head.aot'), 'AOT cache flag is passed through');

  // 3. Existing JAVA_OPTS come first so an operator override of a later flag loses, by design.
  const withEnv = flags({ ...base, existingJavaOpts: '-Xlog:gc*,safepoint' });
  assert.equal(withEnv[0], '-Xlog:gc*,safepoint', 'operator JAVA_OPTS lead the line');

  // 4. Heap bound and dump flags are unchanged by PR 0.
  const withHeap = flags({ ...base, headHeap: '512m', logsDir: 'C:/logs' });
  assert.ok(withHeap.includes('-Xmx512m'));
  assert.ok(withHeap.includes('-XX:+HeapDumpOnOutOfMemoryError'));
  assert.ok(withHeap.includes('-XX:HeapDumpPath=C:/logs'));
  assert.ok(!flags(base).some((f) => f.startsWith('-Xmx')), 'no default -Xmx (tempdoc 730 Increment-4)');

  console.log('test-dev-runner-head-java-opts: OK');
}

main();
