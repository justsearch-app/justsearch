/*
 * Installed native mixed-after-pointer proof using the actual Head-owned Engine.
 */
import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { createHash } from 'node:crypto';

export const NATIVE_MIXED_AFTER_POINTER = 'native-mixed-after-pointer';
export const NATIVE_SOURCE_ID = 'native-supervised-fixture';
export const NATIVE_SOURCE_VERSION = 1;
export const NATIVE_ACTION_REACHED = 'native-projection-actions-reached.json';
export const NATIVE_ACTION_RELEASE = 'native-projection-actions-release';
export const NATIVE_MIGRATION_POINT = 'migration-after-pointer-commit';

const deletedFilesCollection = 'deleted-files';
const retainedFilesCollection = 'keep-collection';
const retainedProjectionTitle = 'native-retained-projection';
const lateProjectionTitle = 'native-late-projection';
const retainedProjectionMarker = 'retainedbodyq7x91';
const lateProjectionMarker = 'latebodyk9z83';

function requireThat(condition, message) {
  if (!condition) throw new Error(message);
}

function samePath(left, right) {
  return typeof left === 'string' && typeof right === 'string'
    && path.resolve(left).toLowerCase() === path.resolve(right).toLowerCase();
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

function canonicalJson(value) {
  if (Array.isArray(value)) return value.map(canonicalJson);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort()
      .map(key => [key, canonicalJson(value[key])]));
  }
  return value;
}

function projectionJournalKey(projection) {
  return `projection:${projection.source_id.length}:${projection.source_id}:${projection.document_id}`;
}

function requirePathNormalizer(normalizePathKey) {
  requireThat(typeof normalizePathKey === 'function',
    'the Engine-owned PathNormalizer.normalizeKey callback is required');
  return value => normalizePathKey(path.resolve(value));
}

function atomicWriteJson(file, value) {
  const temporary = `${file}.native-supervised-tmp`;
  fs.writeFileSync(temporary, `${JSON.stringify(value)}\n`, { flag: 'w' });
  try {
    fs.renameSync(temporary, file);
  } catch (failure) {
    fs.rmSync(temporary, { force: true });
    throw failure;
  }
}

function writeFresh(file, value) {
  fs.writeFileSync(file, value, { flag: 'wx' });
}

function validateProjection(projection, label, normalizePathKey) {
  requireThat(projection?.version === NATIVE_SOURCE_VERSION,
    `${label} projection version is not ${NATIVE_SOURCE_VERSION}`);
  requireThat(projection.source_id === NATIVE_SOURCE_ID,
    `${label} projection source id is not ${NATIVE_SOURCE_ID}`);
  requireThat(typeof projection.document_id === 'string' && projection.document_id.length > 0,
    `${label} projection document id is missing`);
  requireThat(projection.kind === 'UPSERT' && Number.isInteger(projection.source_revision),
    `${label} projection must be an UPSERT with an integer source revision`);
  const fields = projection.fields;
  requireThat(fields && typeof fields === 'object' && !Array.isArray(fields),
    `${label} projection fields are not an object`);
  for (const name of ['title', 'content', 'path', 'collection']) {
    requireThat(typeof fields[name] === 'string' && fields[name].length > 0,
      `${label} projection field ${name} is missing`);
  }
  requireThat(fields.path === normalizePathKey(fields.path),
    `${label} projection path is not PathNormalizer.normalizeKey output`);
  return projection;
}

function validateSourceSnapshot(snapshot, phase, normalizePathKey) {
  requireThat(snapshot?.version === NATIVE_SOURCE_VERSION,
    `${phase} source version is not ${NATIVE_SOURCE_VERSION}`);
  requireThat(snapshot.source_id === NATIVE_SOURCE_ID,
    `${phase} source id is not ${NATIVE_SOURCE_ID}`);
  requireThat(Array.isArray(snapshot.initial) && Array.isArray(snapshot.final),
    `${phase} source must carry initial and final projection arrays`);
  requireThat(snapshot.initial.length === 1 && snapshot.final.length === 2,
    `${phase} source must be [retained] then [retained, late]`);
  const retained = validateProjection(snapshot.initial[0], `${phase} initial retained`, normalizePathKey);
  const finalRetained = validateProjection(snapshot.final[0], `${phase} final retained`, normalizePathKey);
  const late = validateProjection(snapshot.final[1], `${phase} final late`, normalizePathKey);
  requireThat(retained.source_revision === 1 && late.source_revision === 2,
    `${phase} source revisions must be retained=1 and late=2`);
  requireThat(retained.document_id !== late.document_id,
    `${phase} source rows must have distinct document identities`);
  requireThat(retained.fields.content === retainedProjectionMarker
      && late.fields.content === lateProjectionMarker,
    `${phase} source rows have the wrong projection markers`);
  requireThat(retained.fields.title === retainedProjectionTitle
      && late.fields.title === lateProjectionTitle,
    `${phase} source rows have the wrong projection titles`);
  requireThat(retained.fields.collection === retainedFilesCollection
      && late.fields.collection === deletedFilesCollection,
    `${phase} source rows have the wrong collections`);
  requireThat(JSON.stringify(canonicalJson(retained))
      === JSON.stringify(canonicalJson(finalRetained)),
    `${phase} final snapshot changed the retained projection payload`);
  return snapshot;
}

/**
 * Prepare the one source input object and the persisted watched-root authority.  The source
 * object is raw AcceptedProjection.encode()-shape: no initial/final wrapper is added around
 * individual rows.  The caller supplies the actual Engine PathNormalizer.normalizeKey callback.
 */
export function prepareNativeMixedAfterPointerFixture({ work, data, sourceInputPath,
  sourceInput, normalizePathKey, normalizePathPrefix, requireThat: check = requireThat }) {
  const normalize = requirePathNormalizer(normalizePathKey);
  requireThat(typeof normalizePathPrefix === 'function',
    'the Engine-owned PathNormalizer.normalizePathPrefix callback is required');
  const prefixRoot = path.resolve(work, 'native-prefix-root');
  const prefixDeleted = path.join(prefixRoot, 'deleted');
  const collectionRoot = path.resolve(work, 'native-collection-root');
  const files = {
    prefix: path.join(prefixDeleted, 'prefix.txt'),
    dual: path.join(prefixDeleted, 'dual.txt'),
    retained: path.join(prefixRoot, 'retained.txt'),
    collection: path.join(collectionRoot, 'collection.txt'),
  };
  const projections = {
    retained: path.resolve(work, 'retained-projection.md'),
    late: path.join(prefixDeleted, 'late-projection.md'),
  };
  for (const directory of [prefixDeleted, collectionRoot, path.dirname(sourceInputPath),
    path.join(data, 'runtime')]) fs.mkdirSync(directory, { recursive: true });
  for (const [file, marker] of Object.entries({
    [files.prefix]: 'native-prefix-victim',
    [files.dual]: 'native-prefix-and-collection-victim',
    [files.retained]: 'native-retained-file',
    [files.collection]: 'native-collection-victim',
  })) writeFresh(file, `${marker} native supervised fixture\n`);

  const input = validateSourceSnapshot(sourceInput, 'initial', normalize);
  check(input.phase === 'initial', 'source input must begin in initial phase');
  check(input.initial[0].fields.path === normalize(projections.retained),
    'retained projection path does not match the private fixture path');
  check(input.final[1].fields.path === normalize(projections.late),
    'late projection path does not match the private fixture path');
  writeFresh(sourceInputPath, `${JSON.stringify(input)}\n`);

  const watchedRoots = {
    schemaVersion: 1,
    roots: [
      { path: prefixRoot, collection: retainedFilesCollection },
      { path: collectionRoot, collection: deletedFilesCollection },
    ],
  };
  const registry = path.join(data, 'watched_roots.json');
  writeFresh(registry, `${JSON.stringify(watchedRoots)}\n`);
  const prefixKey = normalizePathPrefix(prefixDeleted);
  check(typeof prefixKey === 'string' && prefixKey.length > 0
      && (prefixKey.endsWith('/') || prefixKey.endsWith('\\')),
    'prefix delete key must be normalized with a trailing separator');
  const pathKeys = Object.fromEntries(Object.entries(files)
    .map(([name, file]) => [name, normalize(file)]));
  return { sourceInputPath, prefixRoot, prefixDeleted, collectionRoot, files, projections,
    pathKeys, prefixKey, sourceInput: input, watchedRoots,
    projectionMarkers: { retained: retainedProjectionMarker, late: lateProjectionMarker } };
}

function readRows(dbPath, sql, parameters = []) {
  const database = new DatabaseSync(dbPath, { readOnly: true });
  try {
    database.exec('PRAGMA busy_timeout = 5000');
    return database.prepare(sql).all(...parameters);
  } finally {
    database.close();
  }
}

function switchRows(dbPath, generation) {
  return readRows(dbPath, `SELECT generation, key, op, payload, revision, accepted_order
      FROM switch_buffer WHERE generation = ? ORDER BY accepted_order`, [generation]);
}

function jobRows(dbPath, normalizedPaths) {
  const placeholders = normalizedPaths.map(() => '?').join(', ');
  return readRows(dbPath, `SELECT path, state, content_hash, unit_revision
      FROM jobs WHERE path IN (${placeholders}) ORDER BY lower(path)`, normalizedPaths);
}

function parseBody(response) {
  return typeof response.body === 'object' ? response.body : JSON.parse(response.text);
}

function assertSearchHit(body, expectedPath, marker, label) {
  const result = body?.results?.find(hit => {
    const fields = hit?.fields ?? {};
    return samePath(fields.path, expectedPath)
      && String(fields.content_preview ?? '').includes(marker);
  });
  requireThat(result != null, `${label} did not return ${expectedPath} with ${marker}`);
  return result;
}

function assertSearchAbsent(body, expectedPath, label) {
  const result = body?.results?.find(hit => samePath(hit?.fields?.path, expectedPath));
  requireThat(result == null, `${label} still returned deleted path ${expectedPath}`);
}

function assertProjectionHit(body, projection, label) {
  const result = body?.results?.find(hit => hit.id === projectionJournalKey(projection));
  requireThat(result && samePath(result.fields?.path, projection.fields.path)
      && result.fields.collection === projection.fields.collection
      && result.fields.projection_source_id === projection.source_id
      && result.fields.projection_source_revision === String(projection.source_revision)
      && result.fields.projection_digest === createHash('sha256')
        .update(JSON.stringify(canonicalJson(projection.fields))).digest('hex')
      && result.fields.title === projection.fields.title,
    `${label} did not return its exact accepted fields: ${JSON.stringify(body)}`);
}

function assertExactJournal({ rows, generation, sourceGeneration, fixture, lateReceipt, check = requireThat }) {
  check(rows.length === 9,
    `native mixed journal expected 9 rows, got ${rows.length}: ${JSON.stringify(rows)}`);
  check(rows.every(row => row.generation === generation), 'journal crossed candidate generations');
  check(new Set(rows.map(row => row.key)).size === rows.length,
    'native mixed journal replaced a prior receipt under the same key');

  const expectedUpserts = new Set(Object.values(fixture.pathKeys).map(key => `path:${key}`));
  const actualUpserts = rows.filter(row => row.op === 'UPSERT').map(row => row.key);
  check(actualUpserts.length === expectedUpserts.size
      && new Set(actualUpserts).size === actualUpserts.length
      && actualUpserts.every(key => expectedUpserts.has(key)),
    `native mixed UPSERT identity set changed: ${JSON.stringify(actualUpserts)}`);

  const retained = fixture.sourceInput.initial[0];
  const late = fixture.sourceInput.final[1];
  check(lateReceipt?.sourceId === NATIVE_SOURCE_ID
      && lateReceipt.documentId === late.document_id
      && lateReceipt.sourceRevision === late.source_revision
      && lateReceipt.generationId === sourceGeneration
      && lateReceipt.visibility === 'NRT',
    `late receipt is not the normal source/generation record: ${JSON.stringify(lateReceipt)}`);
  const ordered = [
    ['PROJECTION_SOURCE', `projection-source:${NATIVE_SOURCE_ID.length}:${NATIVE_SOURCE_ID}`],
    ['PROJECTION', projectionJournalKey(retained)],
    ['DELETE_PREFIX', `prefix:${fixture.prefixKey}`],
    ['DELETE_COLLECTION', `collection:${deletedFilesCollection}`],
    ['PROJECTION', projectionJournalKey(late)],
  ];
  check(rows.slice(0, 4).every(row => row.op === 'UPSERT'),
    'native enumeration did not precede the complete source snapshot');
  for (const row of rows.slice(0, 4)) {
    const payload = JSON.parse(row.payload);
    check(payload.version === 2 && row.key === `path:${payload.path}`
        && Object.values(fixture.pathKeys).includes(payload.path)
        && payload.collection === (payload.path === fixture.pathKeys.collection
          ? deletedFilesCollection : retainedFilesCollection)
        && payload.originator === 'system' && payload.transport === 'MIGRATION_ENUMERATOR'
        && typeof payload.unit_revision === 'string' && payload.unit_revision.length > 0
        && payload.source_sha256 === createHash('sha256').update(fs.readFileSync(payload.path)).digest('hex'),
      `candidate file receipt lost its exact source witness: ${JSON.stringify(row)}`);
  }
  check(rows.every((row, index) => Number.isSafeInteger(row.accepted_order)
      && row.accepted_order > 0 && (index === 0 || row.accepted_order > rows[index - 1].accepted_order)),
    'candidate receipts lost their durable accepted order');
  for (const [offset, [op, key]] of ordered.entries()) {
    const row = rows[4 + offset];
    check(row.op === op && row.key === key, `native mixed ordered suffix missing ${op}:${key}`);
    const expectedPayload = op === 'PROJECTION_SOURCE' ? NATIVE_SOURCE_ID
      : op === 'DELETE_PREFIX' ? fixture.prefixKey
        : op === 'DELETE_COLLECTION' ? deletedFilesCollection : null;
    if (expectedPayload !== null) check(row.payload === expectedPayload, `wrong ${op} scope`);
    else check(JSON.stringify(canonicalJson(JSON.parse(row.payload)))
        === JSON.stringify(canonicalJson(offset === 1 ? retained : late)),
      `wrong accepted ${op} payload`);
  }
  check(rows.every(row => typeof row.revision === 'string' && row.revision.length > 0),
    'native mixed journal contains an unversioned receipt');
}

function assertJobsAtAction({ jobsPath, fixture, check = requireThat }) {
  const rows = jobRows(jobsPath, [fixture.pathKeys.retained, fixture.pathKeys.prefix,
    fixture.pathKeys.dual, fixture.pathKeys.collection]);
  check(rows.length === 2 && rows.every(row => row.state === 'DONE'
      && typeof row.unit_revision === 'string' && row.unit_revision.length > 0
      && /^[a-f0-9]{64}$/.test(row.content_hash))
      && rows.some(row => samePath(row.path, fixture.files.retained))
      && rows.some(row => samePath(row.path, fixture.files.collection)),
    `native mixed action lost its terminal retained/collection job witnesses: ${JSON.stringify(rows)}`);
}

function removeVictimsBeforeRelease({ fixture, check = requireThat }) {
  for (const file of [fixture.files.prefix, fixture.files.dual, fixture.files.collection]) {
    check(fs.existsSync(file), `expected held-source victim backing file ${file}`);
    fs.unlinkSync(file);
  }
}

/**
 * Exercise the scenario after the existing runner has admitted one real supervised Engine.
 * The Head-owned actor disables automatic producers only for the first incarnation, holds the
 * registered source, performs the native mutations, and writes the action marker.  Victim files
 * are removed while that source is still held, before the final source flip and release marker.
 */
export async function exerciseNativeMixedAfterPointer({ work, data, indexBase, jobsPath,
  sourceInputPath, fixture, first, manifest, readJson: read = readJson, waitFor, post, request,
  requireThat: check = requireThat }) {
  check(first?.incarnation === 1 && first.pid === manifest?.pid,
    `native mixed fixture was not attached to first Engine: ${JSON.stringify({ first, manifest })}`);
  check(fixture?.sourceInputPath === sourceInputPath,
    'native mixed source path was not the runner-owned private input');
  validateSourceSnapshot(read(sourceInputPath), 'runner initial', value => value);

  const runtime = path.join(data, 'runtime');
  const actionReached = path.join(runtime, NATIVE_ACTION_REACHED);
  const actionRelease = path.join(runtime, NATIVE_ACTION_RELEASE);
  const migrationReached = path.join(runtime, 'migration-barrier-reached.json');
  check(!fs.existsSync(actionReached) && !fs.existsSync(actionRelease)
      && !fs.existsSync(migrationReached), 'native mixed barrier files were not fresh');
  const jobsDatabase = jobsPath ?? path.join(data, 'jobs.db');
  const sourceGeneration = read(path.join(indexBase, 'state.json'))?.active_generation;
  check(typeof sourceGeneration === 'string' && sourceGeneration.length > 0,
    'native mixed fixture has no source generation');

  const initialApi = manifest.head.apiPort;
  const reached = await waitFor('native projection action marker', 180000,
    () => read(actionReached));
  check(reached?.scenario === NATIVE_MIXED_AFTER_POINTER
      && reached.pid === first.pid
      && reached.sourceGeneration === sourceGeneration
      && typeof reached.buildingGeneration === 'string'
      && reached.lateReceipt != null,
    `native action marker targeted the wrong lifecycle point: ${JSON.stringify(reached)}`);
  const buildingGeneration = reached.buildingGeneration;
  // The actor has already proved initial TEXT and VECTOR readiness. Observe A while its
  // completed mutations and complete-source snapshot are held, without racing model startup.
  const initialRetained = await post(initialApi, '/api/knowledge/search', {
    query: 'native-retained-file', limit: 10, mode: 'text',
  }, 30000);
  check(initialRetained.status === 200, 'A retained text proof did not return HTTP 200');
  assertSearchHit(parseBody(initialRetained), fixture.files.retained, 'native-retained-file', 'A text');
  const initialStatus = await request(initialApi, '/api/status', {}, 30000);
  check(initialStatus.status === 200, 'A model status query failed');
  const componentNames = ['api', 'index', 'encoders', 'generative'];
  const recoveryTrace = [];
  const recordReadiness = (phase, value) => {
    const components = value.readiness?.engineComponents;
    check(value.readiness?.schemaVersion === 2, `${phase} omitted schema-2 readiness`);
    for (const name of componentNames) {
      const row = components?.[name];
      check(row && Number.isFinite(Date.parse(row.stateSince))
        && ['ABSENT', 'STARTING', 'READY', 'RELOADING', 'UNAVAILABLE', 'FAILED'].includes(row.state)
        && row.state === value.components?.[name]?.state
        && row.stateSince === value.components?.[name]?.state_since,
      `${phase} lost ${name} state/epoch projection: ${JSON.stringify(row)}`);
    }
    const observation = { phase, observedAtMs: Date.now(), components };
    recoveryTrace.push(observation);
    return observation;
  };
  recordReadiness('before-death', parseBody(initialStatus));
  const sourceEmbedding = parseBody(initialStatus).worker?.compatibility?.embeddingFingerprintCurrent;
  check(typeof sourceEmbedding === 'string' && /^[a-f0-9]{64}$/.test(sourceEmbedding),
    `A did not publish a real embedding identity: ${sourceEmbedding}`);
  const rowsBeforeRelease = switchRows(jobsDatabase, buildingGeneration);
  assertExactJournal({ rows: rowsBeforeRelease, generation: buildingGeneration,
    sourceGeneration, fixture, lateReceipt: reached.lateReceipt, check });
  assertJobsAtAction({ jobsPath: jobsDatabase, fixture, check });

  // The source and actor remain held.  This is deliberately before the atomic source flip and
  // release marker, so boot reconciliation cannot recreate the broad-delete victims.
  removeVictimsBeforeRelease({ fixture, check });
  const finalSource = { ...fixture.sourceInput, phase: 'final' };
  atomicWriteJson(sourceInputPath, finalSource);
  writeFresh(actionRelease, JSON.stringify({
    scenario: NATIVE_MIXED_AFTER_POINTER, sourceGeneration, buildingGeneration,
    releasedByPid: process.pid,
  }));

  const firstExit = await waitFor('first Engine after-pointer self-exit', 180000, () => {
    const supervisor = read(path.join(runtime, 'supervisor.v1.json'));
    let alive = true;
    try { process.kill(first.pid, 0); }
    catch (failure) { if (failure.code === 'ESRCH') alive = false; else throw failure; }
    return !alive && supervisor?.runId === first.runId
        && supervisor.incarnation === first.incarnation
        && supervisor.state === 'restarting'
        && supervisor.lastExit?.counted === true
        && supervisor.lastExit?.incarnation === first.incarnation
        && supervisor.lastExit?.code === 1
        && supervisor.lastExit?.reason === 'fatal_or_uncaught'
        && supervisor.lastExit?.requestedReason == null
      ? supervisor : null;
  });
  const deathObservedAtMs = Date.now();
  let deathRequest;
  try { deathRequest = await request(initialApi, '/api/status', {}, 1000); }
  catch (failure) { deathRequest = { transportError: failure.message }; }
  check(deathRequest.status !== 200, 'dead Engine still served a readiness envelope');
  recoveryTrace.push({ phase: 'dead', observedAtMs: deathObservedAtMs,
    components: null, lastReachable: recoveryTrace[0], deathRequest, supervisor: firstExit });
  // The first reachable successor samples may precede model readiness; retain every envelope.
  const successor = await waitFor('one supervised successor incarnation', 180000, async () => {
    const supervisor = read(path.join(runtime, 'supervisor.v1.json'));
    const live = read(path.join(runtime, 'manifest.json'));
    if (live?.pid === supervisor?.pid && live?.instanceId === supervisor?.instanceId
      && supervisor?.incarnation === first.incarnation + 1) {
      let reply;
      try { reply = await request(live.head.apiPort, '/api/status', {}, 5000); }
      catch { reply = null; }
      if (reply?.status === 200) recordReadiness('successor-starting', parseBody(reply));
    }
    return supervisor?.runId === first.runId
        && supervisor.incarnation === first.incarnation + 1
        && supervisor.restartCount === 1
        && supervisor.state === 'running'
        && supervisor.pid !== first.pid
        && supervisor.instanceId !== first.instanceId
        && live?.pid === supervisor.pid && live.instanceId === supervisor.instanceId
      ? { supervisor, manifest: live } : null;
  });

  const publishedB = await waitFor('successor B published before A retirement', 180000, () => {
    const state = read(path.join(indexBase, 'state.json'));
    return state?.active_generation === buildingGeneration && state.building_generation == null
      ? state : null;
  });
  check(publishedB.active_generation === buildingGeneration, 'B did not become active');

  const bPort = successor.manifest.head.apiPort;
  const status = await waitFor('successor B verified client and real model ready', 180000, async () => {
    const response = await request(bPort, '/api/status', {}, 30000);
    if (response.status !== 200) return null;
    const value = parseBody(response);
    recordReadiness('successor-recovering', value);
    return value.components?.index?.state === 'READY'
        && value.components?.encoders?.state === 'READY'
      && value.worker?.migration?.activeGenerationId === buildingGeneration ? value : null;
  });
  const recovered = recordReadiness('recovered', status);
  const beforeDeath = recoveryTrace[0];
  const timeToReadyMs = {};
  for (const name of componentNames) {
    const row = recovered.components[name];
    const epoch = Date.parse(row.stateSince);
    check(epoch >= deathObservedAtMs && epoch <= recovered.observedAtMs
      && row.stateSince !== beforeDeath.components[name].stateSince,
    `successor reused ${name} stateSince from dead Engine`);
    // ABSENT is an intentional optional state, not a fabricated time-to-READY.
    timeToReadyMs[name] = { state: row.state, stateSince: row.stateSince,
      readyMs: row.state === 'READY' ? epoch - deathObservedAtMs : null,
      observedReadyMs: row.state === 'READY' ? recovered.observedAtMs - deathObservedAtMs : null };
  }
  check(['api', 'index', 'encoders'].every(name => timeToReadyMs[name].state === 'READY'),
    'recovery did not restore all required components');
  const retainedText = await post(bPort, '/api/knowledge/search', {
    query: retainedProjectionMarker, limit: 10, mode: 'text',
  }, 30000);
  const lateText = await post(bPort, '/api/knowledge/search', {
    query: lateProjectionMarker, limit: 10, mode: 'text',
  }, 30000);
  const retainedVector = await post(bPort, '/api/knowledge/search', {
    query: 'native-retained-file', limit: 10, mode: 'vector',
  }, 30000);
  check(retainedText.status === 200 && lateText.status === 200 && retainedVector.status === 200,
    `successor search proof failed: ${JSON.stringify({ retainedText, lateText, retainedVector })}`);
  console.log('NATIVE_SUCCESSOR_QUERY_OBSERVATION', JSON.stringify({
    retained: parseBody(retainedText), late: parseBody(lateText), vector: parseBody(retainedVector),
  }));
  assertProjectionHit(parseBody(retainedText), fixture.sourceInput.initial[0],
    'B retained projection text');
  assertProjectionHit(parseBody(lateText), fixture.sourceInput.final[1],
    'B late projection text');
  assertSearchHit(parseBody(retainedVector), fixture.files.retained,
    'native-retained-file', 'B retained file vector');
  const vectorTrace = parseBody(retainedVector).searchTrace;
  check(vectorTrace?.effectiveMode === 'VECTOR'
      && vectorTrace.degradation?.vectorBlocked !== true
      && vectorTrace.stages?.some(stage => stage.id === 'dense-retrieval' && stage.status === 'executed'),
    `B query did not execute real vector retrieval: ${JSON.stringify(vectorTrace)}`);
  const recoveryProof = { deathObservedAtMs, recoveryTrace, timeToReadyMs,
    text: { status: retainedText.status, results: parseBody(retainedText).results.length },
    semantic: { status: retainedVector.status, results: parseBody(retainedVector).results.length,
      vectorTrace } };
  fs.writeFileSync(path.join(work, 'component-recovery-proof.json'),
    JSON.stringify(recoveryProof, null, 2));
  console.log('COMPONENT_DEATH_RECOVERY_PASS', JSON.stringify(recoveryProof));
  const generationManifest = read(path.join(indexBase, 'indices', buildingGeneration,
    '.justsearch-index-generation.json'));
  const embedding = status.worker?.compatibility;
  // Native migration retains the selected model; its generation manifest contains source
  // ownership, while the Worker projects current/stored Lucene model metadata through status.
  check(generationManifest?.generation_id === buildingGeneration
      && generationManifest.projection_source_ids?.includes(NATIVE_SOURCE_ID)
      && embedding?.embeddingFingerprintCurrent === sourceEmbedding
      && embedding.embeddingFingerprintStored === sourceEmbedding
      && status.worker.compatibility.embeddingCompatState === 'COMPATIBLE'
      && status.components?.encoders?.state === 'READY',
    `B did not serve its declared real model: ${JSON.stringify({ embedding, status })}`);

  for (const [label, marker, file] of [
    ['prefix victim', 'native-prefix-victim', fixture.files.prefix],
    ['dual-scope victim', 'native-prefix-and-collection-victim', fixture.files.dual],
    ['collection victim', 'native-collection-victim', fixture.files.collection],
  ]) {
    const response = await post(bPort, '/api/knowledge/search',
      { query: marker, limit: 10, mode: 'text' }, 30000);
    check(response.status === 200, `B ${label} absence query failed: ${response.text}`);
    assertSearchAbsent(parseBody(response), file, `B ${label}`);
  }

  // The scoped journal must be empty at B publication before the separate A-retirement wait.
  const retainedRows = switchRows(jobsDatabase, buildingGeneration);
  check(retainedRows.length === 0,
    `successor left scoped journal rows after exact B certificate: ${JSON.stringify(retainedRows)}`);
  const finalJobs = jobRows(jobsDatabase, Object.values(fixture.pathKeys));
  check(finalJobs.every(row => row.state === 'DONE')
      && finalJobs.some(row => samePath(row.path, fixture.files.retained))
      && finalJobs.some(row => samePath(row.path, fixture.files.collection))
      && finalJobs.every(row => samePath(row.path, fixture.files.retained)
        || samePath(row.path, fixture.files.collection)),
    `successor left nonterminal or prefix-victim jobs after B certificate: ${JSON.stringify(finalJobs)}`);

  const retiredA = await waitFor('A physical retirement after B proof', 180000, () => {
    const state = read(path.join(indexBase, 'state.json'));
    return state?.active_generation === buildingGeneration && state.previous_generation == null
      && !fs.existsSync(path.join(indexBase, 'indices', sourceGeneration)) ? state : null;
  });
  return { scenario: NATIVE_MIXED_AFTER_POINTER, first, firstExit, successor,
    sourceGeneration, buildingGeneration, publishedB, retiredA,
    rowsBeforeRelease, finalJournalRows: retainedRows, finalJobs, embedding, vectorTrace };
}
