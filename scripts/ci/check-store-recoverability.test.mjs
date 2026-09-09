import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  checkCorruptionPolicyVocabulary,
  checkCountRatchet,
  checkDurableStoreRegister,
  loadPolicies,
  checkParity,
  extractCatalogEntries,
  findHardcodedCipherCalls,
} from './check-store-recoverability.mjs';
import { isPersistenceWriteSource } from '../governance/lib/persistence-write-scan.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

let passed = 0;
const failures = [];

function test(label, assertion) {
  try {
    assertion();
    passed += 1;
  } catch (error) {
    failures.push(`${label}: ${error.message}`);
  }
}

const SAMPLE_CATALOG = `
  CONVERSATIONS("conversations", StoreRecoverability.AUTHORED, Framing.MIXED),
  INDEX("index", StoreRecoverability.DERIVED, Framing.OPAQUE);
`;

test('extractCatalogEntries pulls constant, directory, and class', () => {
  assert.deepEqual(extractCatalogEntries(SAMPLE_CATALOG), [
    { constant: 'CONVERSATIONS', dirName: 'conversations', recoverability: 'AUTHORED' },
    { constant: 'INDEX', dirName: 'index', recoverability: 'DERIVED' },
  ]);
});

test('findHardcodedCipherCalls flags a bare recoverability literal', () => {
  assert.deepEqual(
    findHardcodedCipherCalls('var cipher = storeCipher(StoreRecoverability.AUTHORED);'),
    ['AUTHORED'],
  );
});

test('findHardcodedCipherCalls accepts StoreCatalog-derived selection', () => {
  assert.deepEqual(
    findHardcodedCipherCalls('storeCipher(StoreCatalog.MEMORIES.recoverability())'),
    [],
  );
});

const CATALOG = [
  { constant: 'CONVERSATIONS', dirName: 'conversations', recoverability: 'AUTHORED' },
  { constant: 'INDEX', dirName: 'index', recoverability: 'DERIVED' },
];

test('checkParity passes for an exact mirror', () => {
  assert.deepEqual(
    checkParity(CATALOG, [
      { dirName: 'conversations', recoverability: 'AUTHORED' },
      { dirName: 'index', recoverability: 'DERIVED' },
    ]),
    [],
  );
});

test('checkParity reports missing, drifted, and extra rows', () => {
  const result = checkParity(CATALOG, [
    { dirName: 'conversations', recoverability: 'DERIVED' },
    { dirName: 'ghost', recoverability: 'AUTHORED' },
  ]);
  assert.equal(result.length, 3);
  assert.ok(result.some((failure) => failure.includes('conversations')));
  assert.ok(result.some((failure) => failure.includes('index')));
  assert.ok(result.some((failure) => failure.includes('ghost')));
});

function readyRow(overrides = {}) {
  return {
    id: 'conversations',
    catalogDirName: 'conversations',
    owner: 'HEAD',
    root: 'DATA_DIR',
    path: 'conversations/',
    ownedPaths: ['conversations/**'],
    recoverability: 'AUTHORED',
    format: 'JSON envelope v1',
    currentVersion: 1,
    readableLegacyVersions: [0],
    versionAuthority: 'Version.java',
    futureVersionRefusalTest: 'StoreTest.java',
    writeMode: 'FULL_REWRITE',
    atomicity: 'ATOMIC_REPLACE',
    corruptionPolicy: 'FAIL_LOUD',
    reconciliation: 'UPCAST_AND_REWRITE',
    status: 'READY',
    upgradeHandling: 'READ_IN_PLACE',
    pathVerification: 'LITERAL',
    encryption: 'SEALED_BY_STORE_CIPHER',
    implementationSources: ['Store.java'],
    tests: ['StoreTest.java'],
    fixtures: ['v0.json'],
    ...overrides,
  };
}

function check(rows, options = {}) {
  return checkDurableStoreRegister({
    root: '/repo',
    durableStores: rows,
    knownCompatibilityGaps: options.gaps ?? [],
    catalogEntries: options.catalog ?? [CATALOG[0]],
    discoveredWriteSites: options.discovered ?? ['Store.java'],
    nonDurableWriteSites: options.nonDurable ?? [],
    pendingDurableClassification: options.pending ?? null,
    pathExists: options.pathExists ?? (() => true),
    readSource: options.readSource ?? (() => 'var p = base.resolve("conversations");'),
  });
}

test('broad register accepts a complete ready row', () => {
  assert.deepEqual(check([readyRow()]), []);
});

test('jobs version projection follows its declared schema authority rather than a fixed version', () => {
  const row = readyRow({ id: 'jobs-db', upgradeHandling: 'REBUILD', currentVersion: 17,
    versionAuthority: 'SqliteSchema.java' });
  const readSource = (file) => file.endsWith('SqliteSchema.java')
    ? 'public static final int TARGET_VERSION = 17;' : 'base.resolve("conversations");';
  assert.deepEqual(check([row], { readSource }), []);
  const drift = check([{ ...row, currentVersion: 16 }], { readSource });
  assert.ok(drift.some(message => message.includes('currentVersion 16') && message.includes('TARGET_VERSION 17')));
});

test('jobs schema declarations in comments or strings cannot satisfy version projection', () => {
  const row = readyRow({ id: 'jobs-db', upgradeHandling: 'REBUILD', currentVersion: 17,
    versionAuthority: 'SqliteSchema.java' });
  for (const source of [
    '// public static final int TARGET_VERSION = 17;',
    '/*\npublic static final int TARGET_VERSION = 17;\n*/',
    'String example = """\npublic static final int TARGET_VERSION = 17;\n""";',
    String.raw`String example = """
\"""
public static final int TARGET_VERSION = 17;
""";`,
    'public static final int TARGET_VERSION = 17;\npublic static final int TARGET_VERSION = 18;',
  ]) {
    const result = check([row], { readSource: file => file.endsWith('SqliteSchema.java')
      ? source : 'base.resolve("conversations");' });
    assert.ok(result.some(message => message.includes('exactly one literal TARGET_VERSION')), source);
  }
});

test('jobs version projection rejects non-decimal Java literals rather than misreading their base', () => {
  const row = readyRow({ id: 'jobs-db', upgradeHandling: 'REBUILD', currentVersion: 17,
    versionAuthority: 'SqliteSchema.java' });
  for (const literal of ['017', '0_17', '0x11', '0b10001']) {
    const result = check([row], { readSource: file => file.endsWith('SqliteSchema.java')
      ? `public static final int TARGET_VERSION = ${literal};` : 'base.resolve("conversations");' });
    assert.ok(result.some(message => message.includes('exactly one literal TARGET_VERSION')), literal);
  }
});

test('jobs schema text-block escapes cannot hide the real declaration or expose fake declarations', () => {
  const row = readyRow({ id: 'jobs-db', upgradeHandling: 'REBUILD', currentVersion: 17,
    versionAuthority: 'SqliteSchema.java' });
  const source = String.raw`String example = """
\"""
// public static final int TARGET_VERSION = 15;
public static final int TARGET_VERSION = 16;
""";
public static final int TARGET_VERSION = 17;`;
  assert.deepEqual(check([row], { readSource: file => file.endsWith('SqliteSchema.java')
    ? source : 'base.resolve("conversations");' }), []);
});

test('jobs version authority is required and unreadable sources fail visibly', () => {
  const row = readyRow({ id: 'jobs-db', upgradeHandling: 'REBUILD', currentVersion: 17,
    versionAuthority: 'SqliteSchema.java' });
  for (const versionAuthority of [undefined, '', 'Missing.java']) {
    const result = check([{ ...row, versionAuthority }], { pathExists: file => !file.endsWith('Missing.java') });
    assert.ok(result.some(message => message.includes('versionAuthority must resolve')));
  }
  const unreadable = check([row], { readSource: file => {
    if (file.endsWith('SqliteSchema.java')) throw new Error('unreadable schema');
    return 'base.resolve("conversations");';
  } });
  assert.ok(unreadable.some(message => message.includes('cannot read versionAuthority')
    && message.includes('unreadable schema')));
});

test('broad register rejects an uncovered durable Store implementation', () => {
  const result = check([readyRow()], { discovered: ['Store.java', 'NewStore.java'] });
  assert.ok(result.some((failure) => failure.includes('NewStore.java')));
});

test('broad register requires StoreCatalog coverage', () => {
  const result = check([readyRow({ catalogDirName: undefined })]);
  assert.ok(result.some((failure) => failure.includes('StoreCatalog.CONVERSATIONS')));
});

test('READY authored full rewrites must fail loud and write atomically', () => {
  const result = check([
    readyRow({ corruptionPolicy: 'SILENT_EMPTY', atomicity: 'DIRECT_REWRITE' }),
  ]);
  assert.ok(result.some((failure) => failure.includes('SILENT_EMPTY')));
  assert.ok(result.some((failure) => failure.includes('must be atomic')));
});

test('versioned READY rows require a version authority and future-version test', () => {
  const result = check([
    readyRow({ versionAuthority: undefined, futureVersionRefusalTest: undefined }),
  ]);
  assert.ok(result.some((failure) => failure.includes('versionAuthority')));
  assert.ok(result.some((failure) => failure.includes('futureVersionRefusalTest')));
});

test('HARDENING_REQUIRED is accepted only through the explicit gap ratchet', () => {
  const gap = readyRow({
    id: 'legacy',
    catalogDirName: undefined,
    currentVersion: 0,
    status: 'HARDENING_REQUIRED',
    atomicity: 'DIRECT_REWRITE',
    corruptionPolicy: 'SILENT_EMPTY',
  });
  assert.ok(
    check([gap], { gaps: [], catalog: [], discovered: ['Store.java'] }).some((failure) =>
      failure.includes('not ratcheted'),
    ),
  );
  assert.deepEqual(
    check([gap], { gaps: ['legacy'], catalog: [], discovered: ['Store.java'] }),
    [],
  );
});

// Tempdoc 879: the register named four paths no code writes and stayed green, because nothing
// compared a declared path to its writer. These pin the comparison and its ONE stated exclusion.
test('a declared path whose segments no source writes is a failure', () => {
  const result = check([readyRow({ ownedPaths: ['settings/ui-settings.json'] })]);
  assert.ok(result.some((failure) => failure.includes('`settings`')));
  assert.ok(result.some((failure) => failure.includes('`ui-settings.json`')));
});

test('glob segments are skipped, literal segments are not', () => {
  assert.deepEqual(check([readyRow({ ownedPaths: ['conversations/*/meta.json'] })]).length, 1);
  assert.deepEqual(check([readyRow({ ownedPaths: ['conversations/**'] })]), []);
});

test('a literal component match is exact, never a substring', () => {
  const result = check([readyRow({ ownedPaths: ['ui/x.json'], catalogDirName: undefined })], {
    catalog: [],
    readSource: () => 'var p = base.resolve("build-gui");',
  });
  assert.ok(result.some((failure) => failure.includes('`ui`')));
});

test('COMPOSED is a stated exclusion that must say why', () => {
  const bare = check([readyRow({ ownedPaths: ['nowhere/at.all'], pathVerification: 'COMPOSED' })]);
  assert.ok(bare.some((failure) => failure.includes('pathVerificationNote')));
  assert.deepEqual(
    check([
      readyRow({
        ownedPaths: ['nowhere/at.all'],
        pathVerification: 'COMPOSED',
        pathVerificationNote: 'SQLite creates this sidecar itself.',
      }),
    ]),
    [],
  );
});

// Tempdoc 879 review: unioning every source's literals made LITERAL satisfiable by ADDING a file
// that happens to contain the missing word, so a row could go green with nothing writing its path.
test('LITERAL needs ONE source to hold the whole path, never a union of two', () => {
  const row = readyRow({
    id: 'plugin-allowlist',
    catalogDirName: undefined,
    ownedPaths: ['ui/plugin-allowlist.json'],
    encryption: 'UNSEALED_GAP',
    encryptionNote: 'Plaintext hashes of plugin artifacts an operator trusted.',
    implementationSources: ['Store.java', 'Neighbour.java'],
  });
  const split = check([row], {
    catalog: [],
    readSource: (absolutePath) =>
      absolutePath.replace(/\\/g, '/').endsWith('Neighbour.java')
        ? 'var settings = base.resolve("ui").resolve("settings.json");'
        : 'this.file = dir.resolve("plugin-allowlist.json");',
  });
  assert.equal(split.length, 1);
  assert.ok(split[0].includes('no SINGLE implementation source'));
  assert.ok(split[0].includes('`ui`'));
  // ... and the same row is green the moment ONE file writes the whole path.
  assert.deepEqual(
    check([row], {
      catalog: [],
      readSource: () => 'var p = base.resolve("ui").resolve("plugin-allowlist.json");',
    }),
    [],
  );
});

test('pathVerification and encryption are required on every row', () => {
  const result = check([readyRow({ pathVerification: undefined, encryption: undefined })]);
  assert.ok(result.some((failure) => failure.includes('pathVerification is required')));
  assert.ok(result.some((failure) => failure.includes('encryption is required')));
});

test('an AUTHORED StoreCatalog store must declare itself sealed', () => {
  const result = check([readyRow({ encryption: 'NOT_APPLICABLE' })]);
  assert.ok(result.some((failure) => failure.includes('SEALED_BY_STORE_CIPHER')));
});

test('a row living entirely inside an AUTHORED catalog directory must declare itself sealed', () => {
  const result = check([
    readyRow({
      id: 'run-events',
      catalogDirName: undefined,
      ownedPaths: ['conversations/*/events.ndjson'],
      encryption: 'UNSEALED_GAP',
      encryptionNote: 'plaintext events',
    }),
  ]);
  assert.ok(result.some((failure) => failure.includes('SEALED_BY_STORE_CIPHER')));
});

test('the open-gap disposition must name what the plaintext file contains', () => {
  const row = readyRow({ id: 'ui-settings', catalogDirName: undefined, encryption: 'UNSEALED_GAP' });
  assert.ok(
    check([row], { catalog: [] }).some((failure) => failure.includes('encryptionNote')),
  );
  assert.deepEqual(
    check([{ ...row, encryptionNote: 'Plaintext UI preferences incl. the local model path.' }], {
      catalog: [],
    }),
    [],
  );
});

// Tempdoc 879 review: NOT_APPLICABLE was the ONE disposition nobody had to justify, so it was
// doing double duty for "nothing sensitive here" and "the user's own document text, unsealed".
test('the nothing-to-seal disposition must say what the file holds', () => {
  const row = readyRow({
    id: 'process-locks',
    catalogDirName: undefined,
    encryption: 'NOT_APPLICABLE',
  });
  assert.ok(
    check([row], { catalog: [] }).some((failure) =>
      failure.includes('encryption NOT_APPLICABLE requires an encryptionNote'),
    ),
  );
  assert.deepEqual(
    check([{ ...row, encryptionNote: 'PID and lock timestamp only; no user content.' }], {
      catalog: [],
    }),
    [],
  );
});

test('the derived-store disposition must name the user content it leaves in the clear', () => {
  const row = readyRow({
    id: 'index-generations',
    catalogDirName: undefined,
    encryption: 'UNSEALED_DERIVED_OS_DISK_ENCRYPTION',
  });
  assert.ok(
    check([row], { catalog: [] }).some((failure) =>
      failure.includes('encryption UNSEALED_DERIVED_OS_DISK_ENCRYPTION requires an encryptionNote'),
    ),
  );
  assert.deepEqual(
    check(
      [{ ...row, encryptionNote: 'Lucene stored fields hold the user document text verbatim.' }],
      { catalog: [] },
    ),
    [],
  );
});

test('all declared source and evidence paths must resolve', () => {
  const result = check([readyRow()], {
    pathExists: (path) => !path.endsWith('v0.json'),
  });
  assert.ok(result.some((failure) => failure.includes('v0.json')));
});

test('write-site scanner finds Java writers without Store names or Path constructors', () => {
  assert.equal(
    isPersistenceWriteSource(
      'modules/app-x/src/main/java/io/justsearch/x/DialogJournal.java',
      'class DialogJournal { void save() { Files.writeString(dataDir.resolve("x"), "x"); } }',
    ),
    true,
  );
});

test('write-site scanner finds Rust app-data writers', () => {
  assert.equal(
    isPersistenceWriteSource(
      'modules/shell/src-tauri/src/state.rs',
      'fn save(app_data_dir: &Path) { std::fs::write(app_data_dir.join("x"), b"x"); }',
    ),
    true,
  );
});

test('classified non-durable write sites satisfy coverage explicitly', () => {
  assert.deepEqual(
    check([readyRow()], {
      discovered: ['Store.java', 'Diagnostics.java'],
      nonDurable: [{ path: 'Diagnostics.java', reason: 'writes one JVM temp probe file it deletes.' }],
    }),
    [],
  );
});

test('an unknown storage root fails, so a root cannot be invented or misspelled', () => {
  const result = check([readyRow({ root: 'APP_DATA' })]);
  assert.ok(result.some((f) => f.includes('unknown root')), result.join(' | '));
});

for (const root of [
  'DATA_DIR',
  'AI_HOME',
  'PROGRAM_DATA_OR_DATA_DIR',
  'USER_INDEXED_ROOTS',
]) {
  test(`the enumerated root ${root} is accepted`, () => {
    const result = check([readyRow({ root })]);
    assert.ok(!result.some((f) => f.includes('unknown root')), result.join(' | '));
  });
}

test('the root enum does NOT catch a wrong-but-enumerated root — stated, not implied', () => {
  // ai-install-attempt-memory shipped as DATA_DIR while writing under AI_HOME (review of PR #604).
  // Both are members, so this check is silent on it. Pinned so nobody reads the enum as stronger
  // than it is and stops looking for the real answer.
  const result = check([readyRow({ root: 'AI_HOME' })]);
  assert.ok(!result.some((f) => f.includes('unknown root')));
});

test('a row without currentVersion fails — updater.rs cannot deserialise the register without it', () => {
  // The regression that broke #604's CI on two jobs. Six HARDENING_REQUIRED rows omitted
  // currentVersion; the JS gate only demanded it for READY + READ_IN_PLACE rows, but updater.rs
  // takes `current_version: u32` with NO serde default, so one such row makes the whole embedded
  // register unparseable and validate_store_compatibility rejects EVERY release descriptor.
  const row = readyRow();
  delete row.currentVersion;
  const result = check([row]);
  assert.ok(result.some((f) => f.includes('currentVersion is required on every row')), result.join(' | '));
});

test('a row without readableLegacyVersions fails', () => {
  const row = readyRow();
  delete row.readableLegacyVersions;
  const result = check([row]);
  assert.ok(result.some((f) => f.includes('readableLegacyVersions is required on every row')), result.join(' | '));
});

test('currentVersion 0 is valid — the convention for bytes with no version envelope', () => {
  const result = check([readyRow({ currentVersion: 0, status: 'HARDENING_REQUIRED', upgradeHandling: 'RESET' })]);
  assert.ok(!result.some((f) => f.includes('currentVersion')), result.join(' | '));
});

test('a non-integer currentVersion fails rather than being coerced', () => {
  const result = check([readyRow({ currentVersion: '1' })]);
  assert.ok(result.some((f) => f.includes('currentVersion is required on every row')), result.join(' | '));
});

test('the REAL register satisfies what updater.rs deserialises', () => {
  // Cargo cannot build in this worktree (the Tauri build script needs staged headless resources),
  // so the Rust contract is asserted here instead — and the field list is PARSED OUT of updater.rs
  // rather than restated, so it cannot drift from the struct it mirrors. serde has no defaults on
  // LocalDurableStore, so one row missing one field makes include_str! unparseable and
  // validate_store_compatibility rejects every release descriptor.
  const rust = fs.readFileSync(
    path.resolve(REPO_ROOT, 'modules/shell/src-tauri/src/updater.rs'),
    'utf8',
  );
  const block = /struct LocalDurableStore \{([\s\S]*?)\n\}/.exec(rust);
  assert.ok(block, 'LocalDurableStore struct not found — updater.rs moved; re-point this test');
  const fields = [...block[1].matchAll(/^\s*(\w+):\s*([\w<>]+),/gm)].map(([, name, type]) => ({
    // #[serde(rename_all = "camelCase")] on the struct.
    json: name.replace(/_([a-z])/g, (_m, c) => c.toUpperCase()),
    numeric: /^u\d+$/.test(type),
  }));
  assert.ok(fields.length >= 5, `expected the struct to declare fields, parsed ${fields.length}`);

  const real = JSON.parse(
    fs.readFileSync(path.resolve(REPO_ROOT, 'governance/store-recoverability.v1.json'), 'utf8'),
  );
  for (const row of real.durableStores) {
    for (const field of fields) {
      const value = row[field.json];
      const ok = field.numeric
        ? Number.isInteger(value) && value >= 0
        : typeof value === 'string' && value.length > 0;
      assert.ok(ok, `${row.id}: ${field.json} is required by updater.rs LocalDurableStore`);
    }
    assert.ok(Array.isArray(row.readableLegacyVersions), `${row.id}: readableLegacyVersions`);
  }
});

test('a non-durable classification without a reason fails', () => {
  // The entry is a CLAIM that nothing here survives. Every other row in this register justifies its
  // claims; a bare path grows the allowlist by assertion, which is how the register acquires
  // entries nobody can re-check.
  const result = check([readyRow()], {
    discovered: ['Store.java', 'Diagnostics.java'],
    nonDurable: [{ path: 'Diagnostics.java' }],
  });
  assert.ok(result.some((f) => f.includes('reason is required')), result.join(' | '));
});

test('a non-durable entry with a blank reason fails the same way', () => {
  const result = check([readyRow()], {
    discovered: ['Store.java', 'Diagnostics.java'],
    nonDurable: [{ path: 'Diagnostics.java', reason: '   ' }],
  });
  assert.ok(result.some((f) => f.includes('reason is required')), result.join(' | '));
});

// The gate only inspects sites the scanner discovers (check-store-recoverability.mjs:194), so an
// idiom the detector misses is an unregistered writer nothing can notice. These pin both edges.
const JAVA_SRC = 'modules/x/src/main/java/io/justsearch/X.java';

for (const idiom of [
  'Files.newBufferedWriter(p, UTF_8)',
  'Files.createFile(p)',
  'new FileOutputStream(f)',
  'new FileWriter(f)',
  'new PrintWriter(f)',
  'new ObjectOutputStream(out)',
]) {
  test(`detects durable write idiom: ${idiom}`, () => {
    assert.equal(isPersistenceWriteSource(JAVA_SRC, `var p = dataDir.resolve("s"); ${idiom};`), true);
  });
}

test('read-only mode-dependent APIs are not write sites', () => {
  // Both appear in production against durable paths and are strictly reads; flagging them would
  // force read-only files into nonDurableWriteSites, which registers false authority.
  assert.equal(
    isPersistenceWriteSource(JAVA_SRC, 'FileChannel.open(dbPath, StandardOpenOption.READ);'),
    false,
  );
  assert.equal(
    isPersistenceWriteSource(JAVA_SRC, 'new RandomAccessFile(dataDir.resolve("t").toFile(), "r");'),
    false,
  );
});

// -------------------- discovery is by CALL, not by vocabulary --------------------
//
// The retired rule ANDed the mutation idiom with a "durable anchor" word (`dataDir`, `telemetry`,
// `StoreCatalog`, …) matched against the raw file TEXT — comments included. That produced both
// error directions at once: `ExtractionSandboxFactory` was discovered because a javadoc sentence
// happened to contain an anchor word, while `ExtractionSandboxCommand`, which writes a real
// argfile, was invisible because its prose did not. Discovery decided by reading English.
//
// The two checks below are the exact shapes of that defect, in both directions.

test('DISCOVERED BY CALL: a write with no anchor vocabulary anywhere is a write site', () => {
  // The ExtractionSandboxCommand shape: a real argfile write, and not one anchor word in the file.
  assert.equal(
    isPersistenceWriteSource(
      'modules/worker-services/src/main/java/io/justsearch/x/SandboxCommand.java',
      'class SandboxCommand { void argfile(Path file, StringBuilder body) {\n' +
        '  Files.writeString(file, body.toString(), StandardCharsets.UTF_8);\n} }',
    ),
    true,
  );
});

test('NOT DISCOVERED BY PROSE: a javadoc naming a write and an anchor is not a write site', () => {
  // The ExtractionSandboxFactory shape, inverted: everything the old rule keyed on is present, and
  // all of it is prose. A file that only TALKS about writing must not be classified as writing.
  assert.equal(
    isPersistenceWriteSource(
      JAVA_SRC,
      '/**\n' +
        ' * Reads the snapshot. The writer uses Files.writeString under dataDir; see StoreCatalog\n' +
        ' * and the telemetry runtimeDir notes. This class never writes.\n' +
        ' */\n' +
        'class Reader { String read(Path p) throws IOException { return Files.readString(p); } }',
    ),
    false,
  );
});

test('NOT DISCOVERED BY PROSE: a commented-out write does not count', () => {
  assert.equal(
    isPersistenceWriteSource(JAVA_SRC, 'class X { void f() { // Files.write(dataDir, b);\n } }'),
    false,
  );
});

test('a write to a caller-supplied destination is STILL a write site (fail-closed)', () => {
  // The retired anchor let this through on the theory that user-chosen export targets are governed
  // elsewhere. Deciding that by vocabulary made the exemption invisible; it is now one explicit
  // nonDurableWriteSites line with a reason, which a reader can disagree with.
  assert.equal(isPersistenceWriteSource(JAVA_SRC, 'new FileWriter(userChosenExportTarget);'), true);
});

test('mode-bearing write forms ARE discovered when the mode says write', () => {
  // These were dropped entirely because matching them by name flagged their read-only twins. The
  // mode literal distinguishes them, so the argfile/marker writes that used them stop being a gap.
  assert.equal(
    isPersistenceWriteSource(JAVA_SRC, 'var raf = new RandomAccessFile(signalPath.toFile(), "rw");'),
    true,
  );
  assert.equal(
    isPersistenceWriteSource(JAVA_SRC, 'FileChannel.open(lib, StandardOpenOption.WRITE);'),
    true,
  );
});

test('a string literal is not a comment: `"// Files.write(x)"` still reads as prose-free code', () => {
  // The comment stripper must not mistake `//` inside a literal for a comment start, or it would
  // swallow the rest of a line that may contain the real call.
  assert.equal(
    isPersistenceWriteSource(JAVA_SRC, 'class X { void f() { log("// nope"); Files.write(p, b); } }'),
    true,
  );
});

// --- tempdoc 910 item 2: closed corruptionPolicy vocabulary + count ratchet ---

const POLICY_VOCAB = { FAIL_LOUD: 'Reading raises a visible error.' };

test('a corruptionPolicy outside the closed vocabulary fails, naming the file to extend', () => {
  const result = checkCorruptionPolicyVocabulary({
    durableStores: [readyRow({ id: 'invented', corruptionPolicy: 'SILENT_EMPTY' })],
    policies: POLICY_VOCAB,
  });
  assert.ok(result.some((f) => f.includes('unknown corruptionPolicy `SILENT_EMPTY`')), result.join(' | '));
  // The message must BE the remedy: a lane coining a value has to know where to put it.
  assert.ok(
    result.some((f) => f.includes('governance/store-corruption-policies.v1.json')),
    result.join(' | '),
  );
});

test('a corruptionPolicy inside the vocabulary passes', () => {
  assert.deepEqual(
    checkCorruptionPolicyVocabulary({
      durableStores: [readyRow({ corruptionPolicy: 'FAIL_LOUD' })],
      policies: POLICY_VOCAB,
    }),
    [],
  );
});

test('a declared policy no row uses fails, so the vocabulary cannot outlive its rows', () => {
  const result = checkCorruptionPolicyVocabulary({
    durableStores: [readyRow({ corruptionPolicy: 'FAIL_LOUD' })],
    policies: { ...POLICY_VOCAB, RETIRED_SPELLING: 'Nothing uses this any more.' },
  });
  assert.ok(result.some((f) => f.includes('`RETIRED_SPELLING` is declared but no durableStores row uses it')),
    result.join(' | '));
});

test('a vocabulary entry with no stated meaning fails', () => {
  const result = checkCorruptionPolicyVocabulary({
    durableStores: [readyRow({ corruptionPolicy: 'FAIL_LOUD' })],
    policies: { FAIL_LOUD: '   ' },
  });
  assert.ok(result.some((f) => f.includes('has no meaning')), result.join(' | '));
});

const MARKER = { reason: 'row lands in PR #613', until: '2026-09-30' };

test('an awaitingRow marker with no `until` fails, so an abandoned PR cannot make it permanent', () => {
  const result = checkCorruptionPolicyVocabulary({
    durableStores: [readyRow({ corruptionPolicy: 'FAIL_LOUD' })],
    policies: { ...POLICY_VOCAB, LANDING_IN_ANOTHER_PR: 'Regenerated or preserved.' },
    awaitingRow: { LANDING_IN_ANOTHER_PR: { reason: 'row lands in PR #613' } },
  });
  assert.ok(result.some((f) => f.includes('must carry an ISO `until` date')), result.join(' | '));
});

test('an expired awaitingRow marker fails', () => {
  // The whole point of the expiry: the row-landed branch only fires on SUCCESS, so without this a
  // marker for an abandoned PR would sit in the vocabulary forever.
  const result = checkCorruptionPolicyVocabulary({
    durableStores: [readyRow({ corruptionPolicy: 'FAIL_LOUD' })],
    policies: { ...POLICY_VOCAB, LANDING_IN_ANOTHER_PR: 'Regenerated or preserved.' },
    awaitingRow: { LANDING_IN_ANOTHER_PR: { reason: 'row lands in PR #613', until: '2026-09-01' } },
    now: new Date('2026-09-02T00:00:00Z'),
  });
  assert.ok(result.some((f) => f.includes('expired on 2026-09-01 (today is 2026-09-02)')), result.join(' | '));
});

test('an unexpired awaitingRow marker passes', () => {
  assert.deepEqual(
    checkCorruptionPolicyVocabulary({
      durableStores: [readyRow({ corruptionPolicy: 'FAIL_LOUD' })],
      policies: { ...POLICY_VOCAB, LANDING_IN_ANOTHER_PR: 'Regenerated or preserved.' },
      awaitingRow: { LANDING_IN_ANOTHER_PR: MARKER },
      now: new Date('2026-09-02T00:00:00Z'),
    }),
    [],
  );
});

test('an awaitingRow reason naming no PR or branch fails', () => {
  const result = checkCorruptionPolicyVocabulary({
    durableStores: [readyRow({ corruptionPolicy: 'FAIL_LOUD' })],
    policies: { ...POLICY_VOCAB, LANDING_IN_ANOTHER_PR: 'Regenerated or preserved.' },
    awaitingRow: { LANDING_IN_ANOTHER_PR: { reason: 'landing soon', until: '2026-09-30' } },
    now: new Date('2026-09-02T00:00:00Z'),
  });
  assert.ok(result.some((f) => f.includes('does not name a PR or branch')), result.join(' | '));
});

test('a branch name satisfies the reference check as well as a PR number', () => {
  assert.deepEqual(
    checkCorruptionPolicyVocabulary({
      durableStores: [readyRow({ corruptionPolicy: 'FAIL_LOUD' })],
      policies: { ...POLICY_VOCAB, LANDING_IN_ANOTHER_PR: 'Regenerated or preserved.' },
      awaitingRow: {
        LANDING_IN_ANOTHER_PR: { reason: 'row lands from worktree-resid2-stores', until: '2026-09-30' },
      },
      now: new Date('2026-09-02T00:00:00Z'),
    }),
    [],
  );
});

test('a value declared ahead of its row passes only while awaitingRow names the PR', () => {
  // Cross-lane reality: a sibling branch coins a value, and this vocabulary must accept it BEFORE
  // that row exists or the sibling reds on merge. The marker is what keeps that from becoming a
  // silent hole.
  const policies = { ...POLICY_VOCAB, LANDING_IN_ANOTHER_PR: 'Regenerated or preserved.' };
  const durableStores = [readyRow({ corruptionPolicy: 'FAIL_LOUD' })];
  assert.deepEqual(
    checkCorruptionPolicyVocabulary({
      durableStores,
      policies,
      awaitingRow: { LANDING_IN_ANOTHER_PR: MARKER },
      now: new Date('2026-09-02T00:00:00Z'),
    }),
    [],
  );
  // Without the marker it is just an unused spelling.
  assert.ok(
    checkCorruptionPolicyVocabulary({ durableStores, policies })
      .some((f) => f.includes('`LANDING_IN_ANOTHER_PR` is declared but no durableStores row uses it')),
  );
});

test('an awaitingRow marker whose row has landed fails, so scaffolding cannot outlive its reason', () => {
  const result = checkCorruptionPolicyVocabulary({
    durableStores: [readyRow({ corruptionPolicy: 'FAIL_LOUD' })],
    policies: POLICY_VOCAB,
    awaitingRow: { FAIL_LOUD: MARKER },
  });
  assert.ok(result.some((f) => f.includes('is in `awaitingRow` but a durableStores row now uses it')),
    result.join(' | '));
});

test('an awaitingRow value with no policy meaning fails', () => {
  const result = checkCorruptionPolicyVocabulary({
    durableStores: [readyRow({ corruptionPolicy: 'FAIL_LOUD' })],
    policies: POLICY_VOCAB,
    awaitingRow: { NEVER_DECLARED: MARKER },
  });
  assert.ok(result.some((f) => f.includes('has no entry in `policies`')), result.join(' | '));
});

test('a malformed vocabulary file fails with a remedy instead of a JSON.parse stack', () => {
  // The gate's other failures all name what to do; the one an author is most likely to hit while
  // EXTENDING the vocabulary should not be the one that surfaces as an unhandled exception.
  const calls = [];
  const originalError = console.error;
  const originalExit = process.exit;
  console.error = (msg) => calls.push(String(msg));
  process.exit = (code) => { throw new Error(`exit:${code}`); };
  try {
    assert.throws(
      () => loadPolicies(REPO_ROOT, () => '{ "policies": { "A": "x", } }'),
      /exit:1/,
    );
    assert.ok(calls.some((m) => m.includes('is not valid JSON')), calls.join(' | '));
    assert.ok(calls.some((m) => m.includes('trailing comma')), calls.join(' | '));

    calls.length = 0;
    assert.throws(
      () => loadPolicies(REPO_ROOT, () => { const e = new Error('nope'); e.code = 'ENOENT'; throw e; }),
      /exit:1/,
    );
    assert.ok(calls.some((m) => m.includes('could not be read')), calls.join(' | '));
    assert.ok(calls.some((m) => m.includes('Restore the file from git')), calls.join(' | '));
  } finally {
    console.error = originalError;
    process.exit = originalExit;
  }
});

const RATCHET = { durableStoreRows: 2, pendingDurableClassificationCap: 3 };

test('losing a durableStores row fails against the pinned floor', () => {
  const result = checkCountRatchet({
    durableStores: [readyRow()],
    pendingDurableClassification: null,
    ratchet: RATCHET,
  });
  assert.ok(result.some((f) => f.includes('1 rows against a pinned floor of 2')), result.join(' | '));
  assert.ok(result.some((f) => f.includes('SAME commit that removes the row')), result.join(' | '));
});

test('gaining a durableStores row is allowed — the pin is a floor, not an equality', () => {
  // A lane adding rows in parallel must not red this gate; only disappearance is the event.
  assert.deepEqual(
    checkCountRatchet({
      durableStores: [readyRow({ id: 'a' }), readyRow({ id: 'b' }), readyRow({ id: 'c' })],
      pendingDurableClassification: null,
      ratchet: RATCHET,
    }),
    [],
  );
});

test('pending entries over the pinned ceiling fail', () => {
  const entry = { path: 'X.java', state: 's', blocker: 'b' };
  const result = checkCountRatchet({
    durableStores: [readyRow({ id: 'a' }), readyRow({ id: 'b' })],
    pendingDurableClassification: { cap: 3, entries: [entry, entry, entry, entry] },
    ratchet: RATCHET,
  });
  assert.ok(result.some((f) => f.includes('4 entries against the pinned ceiling of 3')), result.join(' | '));
});

test('raising the register-side cap without raising the external pin fails', () => {
  // This is the hole the external pin exists to close: before it, one commit could add a pending
  // entry and raise the very cap that forbade it, because both lived in the same file.
  const result = checkCountRatchet({
    durableStores: [readyRow({ id: 'a' }), readyRow({ id: 'b' })],
    pendingDurableClassification: { cap: 80, entries: [] },
    ratchet: RATCHET,
  });
  assert.ok(result.some((f) => f.includes('cap is 80 against a pinned ceiling of 3')), result.join(' | '));
});

test('the REAL register passes both the vocabulary and the ratchet', () => {
  const real = JSON.parse(
    fs.readFileSync(path.resolve(REPO_ROOT, 'governance/store-recoverability.v1.json'), 'utf8'),
  );
  const vocab = JSON.parse(
    fs.readFileSync(path.resolve(REPO_ROOT, 'governance/store-corruption-policies.v1.json'), 'utf8'),
  );
  assert.deepEqual(
    checkCorruptionPolicyVocabulary({
      durableStores: real.durableStores,
      policies: vocab.policies,
      awaitingRow: vocab.awaitingRow,
    }),
    [],
  );
  assert.deepEqual(
    checkCountRatchet({
      durableStores: real.durableStores,
      pendingDurableClassification: real.pendingDurableClassification,
      ratchet: vocab.ratchet,
    }),
    [],
  );
  // The pin must describe the register it pins, not a number someone typed once — but only as a
  // floor/ceiling, never as an equality. A lane adding rows in parallel raises
  // `real.durableStores.length` above the pin, and that is the ratchet working, not drifting.
  assert.ok(
    vocab.ratchet.durableStoreRows <= real.durableStores.length,
    `pinned floor ${vocab.ratchet.durableStoreRows} exceeds the register's ${real.durableStores.length} rows`,
  );
  assert.ok(
    real.pendingDurableClassification.cap <= vocab.ratchet.pendingDurableClassificationCap,
    'register cap must not exceed the external pin',
  );
});

if (failures.length > 0) {
  console.error(`check-store-recoverability.test FAILED (${failures.length}):`);
  for (const failure of failures) console.error(`  - ${failure}`);
  process.exit(1);
}

console.log(`check-store-recoverability.test OK - ${passed} assertions passed.`);
