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
 * The flags BOTH spawn sites must carry, in the order the dev-runner emits them.
 *
 * `-Xmx` is deliberately absent: it is one of the documented divergences (lib.rs pins 2g because a
 * packaged JVM's 1/4-of-RAM default is wrong in both directions; the dev-runner keeps no default per
 * tempdoc 730 Increment-4 and honours JUSTSEARCH_HEAD_HEAP). See PACKAGED_ONLY_FLAGS for the rest.
 */
const SHARED_FLAGS = [
  '-XX:+UseSerialGC',
  '-XX:MetaspaceSize=128m',
  '-XX:+UseCompactObjectHeaders',
  '-XX:-UsePerfData',
  '-Dfile.encoding=UTF-8',
  '-XX:+HeapDumpOnOutOfMemoryError',
  // Lane F stage B item B1: the flag that makes an out-of-memory death distinguishable from a
  // boot failure (3 rather than 1). It has to be on BOTH spawn sites or the supervisor's exit
  // classification is right in development and wrong in production, or the reverse.
  '-XX:+ExitOnOutOfMemoryError',
];

/** The packaged heap. Sized at the A13 follow-up; the stage-E gate run re-sizes it on measurement. */
const PACKAGED_HEAP = '-Xmx2g';

/**
 * Flags lib.rs passes that the dev-runner correctly does NOT, each with the reason it is a
 * divergence rather than drift. This list exists because the lib.rs check below is now an EXACT
 * set: a flag has to be either shared or explicitly excused, and "nobody listed it" is no longer a
 * third option. `--sun-misc-unsafe-memory-access` and `--enable-native-access` were exactly that
 * third option until this change — passed by the packaged spawn, pinned by nothing.
 *
 *  - `-Xmx2g` — tempdoc 730 Increment-4, above.
 *  - `--sun-misc-unsafe-memory-access=warn` / `--enable-native-access=ALL-UNNAMED` — the dev-runner
 *    DOES get both, just not through JAVA_OPTS. It launches the Gradle start script
 *    (`modules/ui/build/install/ui/bin/ui.bat`, dev-runner.cjs:1647), whose DEFAULT_JVM_OPTS are
 *    `application { applicationDefaultJvmArgs }` in modules/ui/build.gradle.kts:1943-1946 — which
 *    is these two flags. lib.rs invokes `java -cp ... io.justsearch.ui.HeadlessApp` directly
 *    (lib.rs:826-828), bypassing the start script, so it must pass them itself. Adding them to
 *    SHARED_FLAGS would assert them on the dev-runner's JAVA_OPTS line, where they would be
 *    duplicates of what the start script already supplies.
 *  - `-Djustsearch.prod=true` — the packaged trust boundary. Setting it in dev would turn the dev
 *    stack into the production surface, which is the opposite of what it is for.
 */
const PACKAGED_ONLY_FLAGS = [
  PACKAGED_HEAP,
  '--sun-misc-unsafe-memory-access=warn',
  '--enable-native-access=ALL-UNNAMED',
  '-Djustsearch.prod=true',
];

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
    SHARED_FLAGS,
    'the dev-runner Engine flag set changed; update lib.rs and SHARED_FLAGS together, or explain '
      + 'the divergence in PACKAGED_ONLY_FLAGS the way -Xmx is explained',
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

  // 6. The packaged spawn site, as an EXACT SET. Source-level, and honest about it.
  //
  //    This used to assert only that each shared flag APPEARS in lib.rs, which is a subset check:
  //    it caught a flag going missing from the packaged spawn but not a flag being ADDED there and
  //    never reaching the dev-runner — the same one-sided drift assertion 1 exists to prevent, left
  //    open on the other side. Two flags sat in that blind spot (lib.rs:784,786) from the day they
  //    were added: passed by the shipped app, asserted by nothing, absent from every list here.
  //    They are now in PACKAGED_ONLY_FLAGS with the reason they belong there.
  const libRsPath = path.join(repoRoot, 'modules', 'shell', 'src-tauri', 'src', 'lib.rs');
  const libRs = fs.readFileSync(libRsPath, 'utf8');
  // Only `.arg("...")` calls count — a flag named in a comment is not a flag that is passed.
  const argCalls = [...libRs.matchAll(/\.arg\("([^"]+)"\)/g)].map((m) => m[1]);
  // JVM options only, and defined by EXCLUSION rather than by an allowed prefix set: `-X`/`-D`/`--`
  // would silently drop a flag spelled any other way (`-verbose:gc`, `-ea`, `-server`), which is
  // the same "not on any list, so not checked" hole this assertion exists to close. So: everything
  // starting with `-` counts, minus the two classpath spellings. `-cp` and the main class that
  // follows it are launch STRUCTURE, not tuning; the classpath VALUE and the `.arg(format!(...))`
  // sites (ErrorFile, HeapDumpPath, AOTCache) interpolate at runtime, are not string literals, and
  // never match the regex above.
  const CLASSPATH_ARGS = new Set(['-cp', '-classpath', '--class-path']);
  const libRsFlags = argCalls.filter((a) => a.startsWith('-') && !CLASSPATH_ARGS.has(a));
  assert.deepEqual(
    [...libRsFlags].sort(),
    [...SHARED_FLAGS, ...PACKAGED_ONLY_FLAGS].sort(),
    'the packaged Engine flag set in lib.rs no longer matches SHARED_FLAGS + PACKAGED_ONLY_FLAGS. '
      + 'The packaged Engine is both halves of the product: a flag only the dev-runner has is a '
      + 'flag the shipped app lacks, and a flag only lib.rs has is one no dev run ever exercises. '
      + 'Add it to SHARED_FLAGS and to dev-runner.cjs, or to PACKAGED_ONLY_FLAGS with its reason. '
      + `A changed -Xmx lands here too — lib.rs must pin exactly ${PACKAGED_HEAP}, and the stage-E `
      + 'gate run is where that number is allowed to move.',
  );
  // deepEqual on sorted arrays already rejects a duplicate (it lengthens the array), so the
  // "exactly once" property the old loop asserted per flag is subsumed rather than dropped.

  console.log('test-dev-runner-head-java-opts: OK (dev-runner set exact; lib.rs set exact)');
}

main();
