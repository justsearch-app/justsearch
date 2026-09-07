#!/usr/bin/env node
/**
 * Lane F (docs/design/lane-f-engine-jvm/design.md section 17.2, plus the item A13 follow-up):
 * the Engine launch-flag set, pinned from both spawn sites.
 *
 * There are two of them — `scripts/dev/dev-runner.cjs` (`buildHeadJavaOpts`) for the dev stack and
 * `modules/shell/src-tauri/src/lib.rs` for the packaged app — and they have drifted before. Item
 * A13 collapsed the Head and the Worker into one JVM and updated neither: the packaged Engine went
 * on running a 512 MiB heap sized for "REST API + SSE + static files" while Lucene, the job queue
 * and the ONNX session cache moved into it, and `-Dfile.encoding=UTF-8` was lost entirely when
 * `WorkerSpawner` was deleted. Nothing failed, because a JVM starts fine with the wrong heap and
 * decodes text fine until the text is not ASCII.
 *
 * So this file pins the set two ways:
 *
 *  1. **Exactly**, for the dev-runner: `deepEqual` on the whole flag list, so ADDING a flag fails
 *     the test as loudly as removing one. A subset assertion would let a flag be introduced on one
 *     side and never reach the other, which is the drift this exists to catch.
 *  2. **By reading `lib.rs`'s source**, for the packaged spawn: this test is JavaScript and cannot
 *     execute Rust, so it asserts that each shared flag appears exactly once in the spawn block.
 *     A source-level cross-check is weaker than an execution one and is not pretending otherwise —
 *     it is the same technique the fixture-gate glob guard uses, and it catches the failure that
 *     actually happened (one side edited, the other forgotten).
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const require = createRequire(import.meta.url);
const repoRoot = path.resolve(__dirname, '..', '..');
const { buildHeadJavaOpts } = require(path.join(__dirname, 'dev-runner.cjs')).__test;

/**
 * The flags BOTH spawn sites must carry. `-Xmx` is deliberately absent: it is the one documented
 * divergence (lib.rs pins 2g because a packaged JVM's 1/4-of-RAM default is wrong in both
 * directions; the dev-runner keeps no default per tempdoc 730 Increment-4 and honours
 * JUSTSEARCH_HEAD_HEAP).
 */
const SHARED_FLAGS = [
  '-XX:+UseSerialGC',
  '-XX:MetaspaceSize=128m',
  '-XX:+UseCompactObjectHeaders',
  '-XX:-UsePerfData',
  '-Dfile.encoding=UTF-8',
];

/** The packaged heap. Sized at the A13 follow-up; the stage-E gate run re-sizes it on measurement. */
const PACKAGED_HEAP = '-Xmx2g';

function flags(opts) {
  return buildHeadJavaOpts(opts).split(/\s+/).filter(Boolean);
}

function main() {
  const base = {
    existingJavaOpts: undefined,
    headAotOpts: null,
    headDistStamp: null,
    logsDir: null,
    headHeap: null,
  };

  // 1. THE EXACT dev-runner set. deepEqual, not "includes": a new flag here that never reaches
  //    lib.rs is exactly the drift item A13 shipped, so adding one must fail until both sides move.
  assert.deepEqual(
    flags(base),
    [...SHARED_FLAGS, '-XX:+HeapDumpOnOutOfMemoryError'],
    'the dev-runner Engine flag set changed; update lib.rs and SHARED_FLAGS together, or explain '
      + 'the divergence here the way -Xmx is explained',
  );

  // 2. The AOT cache does not fork the set.
  const withAot = flags({ ...base, headAotOpts: '-XX:AOTCache=C:/x/head.aot' });
  assert.deepEqual(
    withAot.filter((f) => !f.startsWith('-XX:AOTCache')),
    flags(base),
    'the JVM flag set must be identical with and without the AOT cache',
  );
  assert.ok(withAot.includes('-XX:AOTCache=C:/x/head.aot'), 'AOT cache flag is passed through');

  // 3. Operator JAVA_OPTS lead the line so a later flag of ours wins, by design.
  assert.equal(
    flags({ ...base, existingJavaOpts: '-Xlog:gc*,safepoint' })[0],
    '-Xlog:gc*,safepoint',
    'operator JAVA_OPTS lead the line',
  );

  // 4. Heap and dump path are caller-supplied; no default -Xmx in dev (tempdoc 730 Increment-4).
  const withHeap = flags({ ...base, headHeap: '512m', logsDir: 'C:/logs' });
  assert.ok(withHeap.includes('-Xmx512m'));
  assert.ok(withHeap.includes('-XX:HeapDumpPath=C:/logs'));
  assert.ok(
    !flags(base).some((f) => f.startsWith('-Xmx')),
    'the dev-runner must not invent a default heap — that is lib.rs\'s job, not the dev stack\'s',
  );

  // 5. The JDWP listener (lane F item A11 follow-up): absent unless hot reload asked for one.
  assert.ok(
    !flags(base).some((f) => f.startsWith('-agentlib:jdwp')),
    'no JDWP listener unless hot reload asked for one: an open debug port is remote code execution',
  );
  const withJdwp = flags({ ...base, debugPort: 5011 });
  assert.ok(
    withJdwp.includes('-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5011'),
    'JDWP is emitted on the Engine line, non-suspending and bound to loopback',
  );
  assert.deepEqual(
    withJdwp.filter((f) => !f.startsWith('-agentlib:jdwp')),
    flags(base),
    'enabling hot reload adds the listener and changes nothing else',
  );

  // 6. The packaged spawn site carries the same shared set. Source-level, and honest about it.
  const libRsPath = path.join(repoRoot, 'modules', 'shell', 'src-tauri', 'src', 'lib.rs');
  const libRs = fs.readFileSync(libRsPath, 'utf8');
  // Only `.arg("...")` calls count — a flag named in a comment is not a flag that is passed.
  const argCalls = [...libRs.matchAll(/\.arg\("([^"]+)"\)/g)].map((m) => m[1]);
  for (const flag of [...SHARED_FLAGS, PACKAGED_HEAP]) {
    const n = argCalls.filter((a) => a === flag).length;
    assert.equal(
      n,
      1,
      `lib.rs must pass ${flag} exactly once (found ${n}). The packaged Engine is both halves of `
        + 'the product; a flag that lives only in the dev-runner is a flag the shipped app does not '
        + 'have.',
    );
  }
  assert.ok(
    !argCalls.some((a) => a.startsWith('-Xmx') && a !== PACKAGED_HEAP),
    `lib.rs must pin exactly ${PACKAGED_HEAP}; a different -Xmx means the heap was re-sized without `
      + 'updating this pin (the stage-E gate run is where that number is allowed to move)',
  );

  console.log('test-dev-runner-head-java-opts: OK (dev-runner set exact; lib.rs cross-checked)');
}

main();
