import assert from 'node:assert/strict';
import { afterEach, beforeEach, test } from 'node:test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { assertExactJournal, assertJobsAtAction, prepareNativeMixedAfterPointerFixture,
  NATIVE_SOURCE_ID } from './native-projection-scenario.mjs';

const scratch = fileURLToPath(new URL('../../tmp/round13/', import.meta.url));
const generation = 'g-candidate';
const sourceGeneration = 'g-source';
const normalize = value => process.platform === 'win32'
  ? path.resolve(value).toLowerCase() : path.resolve(value);
const projectionKey = row => `projection:${row.source_id.length}:${row.source_id}:${row.document_id}`;
let work;
let fixture;
let rows;
let lateReceipt;
let jobsPath;

beforeEach(() => {
  fs.mkdirSync(scratch, { recursive: true });
  work = fs.mkdtempSync(path.join(scratch, 'NativeOracle-'));
  const projection = (id, revision, title, content, file, collection) => ({
    version: 1, source_id: NATIVE_SOURCE_ID, document_id: id, source_revision: revision,
    kind: 'UPSERT', fields: { title, content, path: normalize(file), collection },
  });
  const retained = projection('retained', 1, 'native-retained-projection', 'retainedbodyq7x91',
    path.join(work, 'retained-projection.md'), 'keep-collection');
  const late = projection('late', 2, 'native-late-projection', 'latebodyk9z83',
    path.join(work, 'native-prefix-root/deleted/late-projection.md'), 'deleted-files');
  fixture = prepareNativeMixedAfterPointerFixture({ work, data: path.join(work, 'data'),
    sourceInputPath: path.join(work, 'data/fixtures/source.json'),
    sourceInput: { version: 1, source_id: NATIVE_SOURCE_ID, phase: 'initial',
      initial: [retained], final: [retained, late] }, normalizePathKey: normalize,
    normalizePathPrefix: value => `${normalize(value)}${path.sep}` });
  rows = Object.entries(fixture.pathKeys).map(([name, file]) => ({
    generation, key: `path:${file}`, op: 'UPSERT', payload: JSON.stringify({
      version: 3, path: file, collection: name === 'collection' ? 'deleted-files' : 'keep-collection',
      originator: 'system', transport: 'MIGRATION_ENUMERATOR', unit_revision: `unit-${name}`,
      source_sha256: createHash('sha256').update(fs.readFileSync(file)).digest('hex'),
      ingestion_root: normalize(name === 'collection' ? fixture.collectionRoot : fixture.prefixRoot),
    }),
  }));
  rows.push(
    { key: `projection-source:${NATIVE_SOURCE_ID.length}:${NATIVE_SOURCE_ID}`,
      op: 'PROJECTION_SOURCE', payload: NATIVE_SOURCE_ID },
    { key: projectionKey(retained), op: 'PROJECTION', payload: JSON.stringify(retained) },
    { key: `prefix:${fixture.prefixKey}`, op: 'DELETE_PREFIX',
      payload: JSON.stringify({ version: 1, prefix: fixture.prefixKey, excludedSubtrees: [] }) },
    { key: 'collection:deleted-files', op: 'DELETE_COLLECTION', payload: 'deleted-files' },
    { key: projectionKey(late), op: 'PROJECTION', payload: JSON.stringify(late) });
  rows = rows.map((row, index) => ({ generation, revision: `receipt-${index}`,
    accepted_order: index + 1, ...row }));
  lateReceipt = { sourceId: NATIVE_SOURCE_ID, documentId: 'late', sourceRevision: 2,
    generationId: sourceGeneration, visibility: 'NRT' };
  jobsPath = path.join(work, 'jobs.db');
  const db = new DatabaseSync(jobsPath);
  try {
    db.exec('CREATE TABLE jobs(path TEXT PRIMARY KEY, state TEXT, content_hash TEXT, unit_revision TEXT)');
    const insert = db.prepare('INSERT INTO jobs VALUES (?, ?, ?, ?)');
    for (const name of ['retained', 'collection']) {
      insert.run(fixture.pathKeys[name], 'DONE', 'a'.repeat(64), `job-${name}`);
    }
  } finally { db.close(); }
});

afterEach(() => { fs.rmSync(work, { recursive: true, force: true }); });
const journal = () => assertExactJournal({ rows, generation, sourceGeneration, fixture, lateReceipt });
function payload(index, mutate) {
  const value = JSON.parse(rows[index].payload);
  mutate(value);
  rows[index].payload = JSON.stringify(value);
}
function jobs(sql, ...parameters) {
  const db = new DatabaseSync(jobsPath);
  try { db.prepare(sql).run(...parameters); } finally { db.close(); }
}

test('current v3 roots and encoded prefix scope pass the actual nine-row oracle', () => {
  assert.doesNotThrow(journal);
  assert.doesNotThrow(() => assertJobsAtAction({ jobsPath, fixture }));
});
test('declared root lookup is independent of declaration order', () => {
  fixture.watchedRoots.roots.reverse();
  assert.doesNotThrow(journal);
});
test('scope comparison is structural and preserves exact projection payload comparison', () => {
  rows[6].payload = JSON.stringify({ excludedSubtrees: [], prefix: fixture.prefixKey, version: 1 });
  payload(5, value => { value.fields = Object.fromEntries(Object.entries(value.fields).reverse()); });
  assert.doesNotThrow(journal);
});
for (const version of [1, 2, 4, null, undefined]) {
  test(`fresh UPSERT version ${version} is rejected`, () => {
    payload(0, value => { value.version = version; });
    assert.throws(journal, /exact source witness/);
  });
}
for (const [label, value] of [
  ['missing', undefined], ['null', null], ['wrong', 'wrong-root'],
  ['foreign', () => normalize(path.join(work, 'foreign'))],
  ['swapped prefix', () => normalize(fixture.collectionRoot)],
  ['child directory', () => normalize(fixture.prefixDeleted)],
  ['trailing separator', () => `${normalize(fixture.prefixRoot)}${path.sep}`],
  ['dot segment alias', () => `${normalize(fixture.prefixRoot)}${path.sep}.${path.sep}`],
  ['wrong case', () => {
    const root = normalize(fixture.prefixRoot);
    return process.platform === 'win32' ? root.toUpperCase() : root.toLowerCase();
  }],
]) {
  test(`exact ingestion root rejects ${label}`, () => {
    payload(0, row => { row.ingestion_root = typeof value === 'function' ? value() : value; });
    assert.throws(journal, /exact source witness/);
  });
}
test('collection file cannot borrow the prefix root', () => {
  payload(3, row => { row.ingestion_root = normalize(fixture.prefixRoot); });
  assert.throws(journal, /exact source witness/);
});
test('a file root must remain a fixture declaration', () => {
  fixture.watchedRoots.roots = fixture.watchedRoots.roots.filter(row => row.path !== fixture.prefixRoot);
  assert.throws(journal, /exact source witness/);
});
for (const [label, mutate] of [
  ['wrong version', scope => { scope.version = 2; }],
  ['wrong prefix', scope => { scope.prefix += 'other/'; }],
  ['missing exclusions', scope => { delete scope.excludedSubtrees; }],
  ['null exclusions', scope => { scope.excludedSubtrees = null; }],
  ['nonempty exclusions', scope => { scope.excludedSubtrees = [fixture.prefixKey + 'nested/']; }],
  ['nonarray exclusions', scope => { scope.excludedSubtrees = {}; }],
  ['extra field', scope => { scope.extra = true; }],
]) {
  test(`fresh encoded prefix scope rejects ${label}`, () => {
    payload(6, mutate);
    assert.throws(journal, /wrong DELETE_PREFIX scope/);
  });
}
for (const [label, value] of [['raw legacy prefix', () => fixture.prefixKey],
  ['JSON string', () => JSON.stringify(fixture.prefixKey)], ['null', () => 'null'],
  ['array', () => '[]'], ['malformed', () => '{']]) {
  test(`fresh prefix rejects ${label}`, () => {
    rows[6].payload = value();
    assert.throws(journal);
  });
}
for (const [label, mutate] of [
  ['label', row => { row.collection = 'foreign'; }],
  ['originator', row => { row.originator = 'user'; }],
  ['transport', row => { row.transport = 'other'; }],
  ['hash', row => { row.source_sha256 = '0'.repeat(64); }],
  ['missing unit revision', row => { delete row.unit_revision; }],
  ['empty unit revision', row => { row.unit_revision = ''; }],
  ['path', row => { row.path = fixture.pathKeys.collection; }],
]) {
  test(`file source witness retains rejection of wrong ${label}`, () => {
    payload(0, mutate);
    assert.throws(journal, /exact source witness/);
  });
}
test('same-file hash is checked against fresh bytes', () => {
  fs.appendFileSync(fixture.files.prefix, 'changed bytes');
  assert.throws(journal, /exact source witness/);
});
for (const [label, mutate] of [
  ['generation', () => { rows[0].generation = 'g-foreign'; }],
  ['key', () => { rows[0].key += '-wrong'; }],
  ['missing row', () => { rows.pop(); }],
  ['extra row', () => { rows.push({ ...rows[0], key: 'extra' }); }],
  ['duplicate row', () => { rows[1] = { ...rows[0] }; }],
  ['missing journal revision', () => { delete rows[0].revision; }],
  ['empty journal revision', () => { rows[0].revision = ''; }],
  ['zero acceptance order', () => { rows[0].accepted_order = 0; }],
  ['duplicate acceptance order', () => { rows[1].accepted_order = rows[0].accepted_order; }],
  ['fractional acceptance order', () => { rows[0].accepted_order = 0.5; }],
  ['unsafe acceptance order', () => { rows[8].accepted_order = Number.MAX_SAFE_INTEGER + 1; }],
  ['suffix order', () => { [rows[7], rows[8]] = [rows[8], rows[7]];
    rows.forEach((row, index) => { row.accepted_order = index + 1; }); }],
  ['source payload', () => { rows[4].payload = 'wrong-source'; }],
  ['collection scope', () => { rows[7].payload = 'other'; }],
  ['projection source revision', () => { payload(8, row => { row.source_revision = 99; }); }],
  ['projection extra field', () => { payload(5, row => { row.extra = true; }); }],
  ['projection fields', () => { payload(5, row => { row.fields.content = 'wrong'; }); }],
  ['projection version', () => { payload(5, row => { row.version = 2; }); }],
]) {
  test(`journal retains rejection of ${label}`, () => { mutate(); assert.throws(journal); });
}
for (const [field, value] of Object.entries({ sourceId: 'other', documentId: 'other',
  sourceRevision: 1, generationId: 'g-foreign', visibility: 'DURABLE' })) {
  test(`late receipt retains exact ${field}`, () => {
    lateReceipt[field] = value;
    assert.throws(journal, /late receipt/);
  });
}
test('missing late receipt is rejected', () => {
  lateReceipt = null;
  assert.throws(journal, /late receipt/);
});
for (const [label, mutate] of [
  ['missing retained job', () => jobs('DELETE FROM jobs WHERE path=?', fixture.pathKeys.retained)],
  ['extra fixture job', () => jobs('INSERT INTO jobs VALUES (?, ?, ?, ?)',
    fixture.pathKeys.prefix, 'DONE', 'a'.repeat(64), 'job-prefix')],
  ['wrong fixture job set', () => jobs('UPDATE jobs SET path=? WHERE path=?',
    fixture.pathKeys.prefix, fixture.pathKeys.retained)],
  ['non-DONE state', () => jobs('UPDATE jobs SET state=?', 'PENDING')],
  ['missing revision', () => jobs('UPDATE jobs SET unit_revision=NULL')],
  ['empty revision', () => jobs('UPDATE jobs SET unit_revision=?', '')],
  ['malformed hash', () => jobs('UPDATE jobs SET content_hash=?', 'bad')],
  ['missing hash', () => jobs('UPDATE jobs SET content_hash=NULL')],
  ['uppercase hash', () => jobs('UPDATE jobs SET content_hash=?', 'A'.repeat(64))],
]) {
  test(`jobs oracle retains rejection of ${label}`, () => {
    mutate(); assert.throws(() => assertJobsAtAction({ jobsPath, fixture }), /terminal/);
  });
}
test('existing unit and journal revisions are nonempty checks, not arbitrary exact-value comparisons', () => {
  payload(0, row => { row.unit_revision = 'another-nonempty-revision'; });
  rows[0].revision = 'another-receipt';
  assert.doesNotThrow(journal);
});
test('jobs hashes retain format checking rather than fresh-byte comparison', () => {
  jobs('UPDATE jobs SET content_hash=?, unit_revision=?', 'b'.repeat(64), 'another-nonempty-job-revision');
  assert.doesNotThrow(() => assertJobsAtAction({ jobsPath, fixture }));
});
