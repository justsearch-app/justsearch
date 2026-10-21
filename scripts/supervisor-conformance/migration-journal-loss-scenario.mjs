import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';

// The existing native driver calls the real worker API for file, prefix, collection and
// projection deletion. Its held source prevents cutover until this fixture kills the Engine.
export async function exerciseMigrationJournalLoss(c) {
  const { work, data, indexBase, first, apiPort, fixture, damage,
    readJson, waitFor, request, post, requireThat: check } = c;
  const runtime = path.join(data, 'runtime');
  const database = path.join(data, 'jobs.db');
  const beforeMigration = path.join(runtime, 'journal-loss-before-migration-reached.json');
  const held = await waitFor('native driver before migration', 180000, () => readJson(beforeMigration));
  check(held.pid === first.pid, 'pre-migration barrier belongs to another Engine');
  const stale = path.join(work, 'stale-jobs.db');
  snapshotDatabase(database, stale);
  fs.writeFileSync(path.join(runtime, 'journal-loss-before-migration-release'), 'release', { flag: 'wx' });
  const actions = await waitFor('four accepted migration deletions', 180000,
    () => readJson(path.join(runtime, 'native-projection-actions-reached.json')));
  check(actions.pid === first.pid && actions.sourceGeneration !== actions.buildingGeneration,
    'deletion barrier belongs to another migration owner');
  const state = readJson(path.join(indexBase, 'state.json'));
  check(state.active_generation === actions.sourceGeneration
    && state.building_generation === actions.buildingGeneration && state.migration_state === 'MIGRATING',
  'migration must still serve A while its source is held');
  const queue = new DatabaseSync(database, { readOnly: true });
  try {
    const rows = queue.prepare('SELECT op, payload FROM switch_buffer').all();
    for (const op of ['DELETE', 'DELETE_PREFIX', 'DELETE_COLLECTION']) {
      check(rows.some(row => row.op === op), `the real API did not journal ${op}`);
    }
    check(rows.some(row => row.op === 'PROJECTION' && JSON.parse(row.payload).kind === 'DELETE'),
      'the real API did not journal the projection deletion');
  } finally { queue.close(); }
  const projection = fixture.sourceInput.initial[0];
  const victims = [
    { marker: 'native-retained-file', id: fixture.pathKeys.retained },
    { marker: 'native-prefix-victim', id: fixture.pathKeys.prefix },
    { marker: 'native-collection-victim', id: fixture.pathKeys.collection },
    { marker: fixture.projectionMarkers.retained,
      id: `projection:${projection.source_id.length}:${projection.source_id}:${projection.document_id}` },
  ];
  const markers = victims.map(victim => victim.marker);
  for (const { marker, id } of victims) {
    const response = await post(apiPort, '/api/knowledge/search', { query: marker, mode: 'text', limit: 20 });
    check(response.status === 200 && !JSON.parse(response.text).results.some(hit => hit.id === id),
      `acknowledged deletion was still searchable in A: ${marker}: ${response.text}`);
  }
  // First startup skipped this barrier using our sentinel. Remove it while A is held so its
  // successor must stop before opening the queue; no cooldown or sleep is a synchronization point.
  fs.rmSync(path.join(runtime, 'index-start-initial-reached.json'));
  for (const file of Object.values(fixture.files)) fs.rmSync(file);
  fs.writeFileSync(fixture.sourceInputPath, JSON.stringify({ ...fixture.sourceInput, phase: 'final' }));
  fs.writeFileSync(path.join(runtime, 'native-projection-actions-release'), 'kill', { flag: 'wx' });
  const successor = await waitFor('successor held before queue open', 90000, () => {
    const barrier = readJson(path.join(runtime, 'index-start-initial-reached.json'));
    const supervisor = readJson(path.join(runtime, 'supervisor.v1.json'));
    const manifest = readJson(path.join(runtime, 'manifest.json'));
    return barrier?.pid !== first.pid && barrier?.pid === manifest?.pid
      && supervisor?.runId === first.runId && supervisor.incarnation > first.incarnation
      && supervisor.pid === manifest.pid && supervisor.instanceId === manifest.instanceId
      ? { barrier, supervisor, manifest } : null;
  });
  if (damage === 'corrupt') {
    const consistent = path.join(work, 'corrupt-input.db');
    snapshotDatabase(database, consistent);
    replaceDatabase(database, consistent);
    const fd = fs.openSync(database, 'r+');
    try { fs.writeSync(fd, Buffer.alloc(512, 0xab), 0, 512, 100); }
    finally { fs.closeSync(fd); }
  } else if (damage === 'stale') replaceDatabase(database, stale);
  else if (damage === 'missing') fs.unlinkSync(database);
  else if (damage === 'empty') fs.truncateSync(database, 0);
  else throw new Error(`Unknown journal fault ${damage}`);
  const intact = queueFiles(data);
  const port = successor.manifest.head.apiPort;
  const beforeReady = await unavailableQueries(port, markers, post, check);
  fs.writeFileSync(path.join(runtime, 'index-start-initial-release'), 'release', { flag: 'wx' });
  const refusal = await waitFor('named migration journal refusal', 60000, async () => {
    const response = await request(port, '/api/status');
    if (response.status !== 200) return null;
    const status = JSON.parse(response.text);
    const index = status.readiness?.engineComponents?.index;
    check(index?.state !== 'READY', 'the worker accepted a lost migration journal and became ready');
    return index?.reasonCode === 'index.migration_journal_unavailable'
      ? status : null;
  });
  const afterRestart = await unavailableQueries(port, markers, post, check);
  check(JSON.stringify(queueFiles(data)) === JSON.stringify(intact), 'refusal changed queue evidence');
  const result = { damage, first, successor, beforeReady, afterRestart,
    refusal: refusal.readiness.engineComponents.index, intact };
  fs.writeFileSync(path.join(work, 'migration-journal-loss-proof.json'), JSON.stringify(result, null, 2));
  console.log('MIGRATION_JOURNAL_LOSS_PASS', JSON.stringify(result));
}

function snapshotDatabase(database, target) {
  const connection = new DatabaseSync(database);
  try { connection.exec(`VACUUM INTO '${target.replaceAll("'", "''")}'`); }
  finally { connection.close(); }
}

function replaceDatabase(database, source) {
  fs.copyFileSync(source, database);
  for (const suffix of ['-wal', '-shm']) fs.rmSync(`${database}${suffix}`, { force: true });
}

function queueFiles(data) {
  // SQLite's coordination memory is not database/WAL/backup/quarantine evidence.
  return Object.fromEntries(fs.readdirSync(data)
    .filter(name => name.startsWith('jobs.db') && name !== 'jobs.db-shm').sort()
    .map(name => [name, createHash('sha256').update(fs.readFileSync(path.join(data, name))).digest('hex')]));
}

async function unavailableQueries(port, markers, post, check) {
  const results = [];
  for (const marker of markers) {
    const response = await post(port, '/api/knowledge/search', { query: marker, mode: 'text', limit: 20 });
    check(response.status === 503, `unready migration served a deleted ${marker}: ${response.status} ${response.text}`);
    results.push({ marker, status: response.status });
  }
  return results;
}
