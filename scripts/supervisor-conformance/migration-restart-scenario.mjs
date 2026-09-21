import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';

// Installed-process regression, sharing the existing supervisor fixture's owned launch/cleanup.
export async function exerciseMigrationRestart(c) {
  const { work, data, indexBase, first, manifest, apiPort, readJson,
    post, requireThat, requireOperationSuccess, createOperationKey, matchingHit } = c;
  const deadline = Date.now() + 270000;
  const waitFor = (label, budget, probe) =>
    c.waitFor(label, Math.max(1, Math.min(budget, deadline - Date.now())), probe);
  const sources = path.join(work, 'migration-sources');
  fs.mkdirSync(sources, { recursive: true });
  const a = path.join(sources, 'a.txt');
  const b = path.join(work, 'blue-only.txt');
  fs.writeFileSync(a, 'migrationretainedmarker quokka');
  fs.writeFileSync(b, 'migrationblueonlymarker wombat');
  const operationKey = createOperationKey();
  // Exercise the same prepared approval ceremony as the UI, including out-of-root Blue setup.
  const approvedPost = async (endpoint, body, label) => {
    const firstResponse = await post(apiPort, endpoint, body);
    if (firstResponse.status !== 428) return firstResponse;
    const pending = JSON.parse(firstResponse.text);
    requireThat(typeof pending.pendingId === 'string' && typeof pending.preparationNonce === 'string'
      && pending.operationKey === body.idempotencyKey, `${label} omitted exact prepared approval binding`);
    const approval = await post(apiPort, '/api/authorizations/approve', { pendingId: pending.pendingId });
    const approved = JSON.parse(approval.text);
    requireThat(approval.status === 200 && typeof approved.capsule === 'string',
      `${label} approval failed: ${approval.text}`);
    const dispatched = post(apiPort, endpoint, { ...body, confirmationToken: approved.capsule,
      preparationNonce: pending.preparationNonce });
    return endpoint === '/api/indexing/migration/start'
      ? dispatched.catch(error => ({ deliveryUnknown: String(error) })) : dispatched;
  };
  const ingest = await approvedPost('/api/knowledge/ingest', {
    paths: [b], idempotencyKey: operationKey,
  }, 'migration ingest');
  const receipt = requireOperationSuccess(ingest, 'migration ingest');
  requireThat(receipt.operationKey === operationKey,
    `migration ingest changed its supplied key: ${ingest.text}`);
  const search = async (port, marker) => post(port, '/api/knowledge/search', {
    query: marker, limit: 5, mode: 'text',
  });
  await waitFor('both Blue documents searchable', 60000, async () => {
    try {
      return matchingHit(await search(apiPort, 'migrationretainedmarker'), a, 'migrationretainedmarker')
        && matchingHit(await search(apiPort, 'migrationblueonlymarker'), b, 'migrationblueonlymarker');
    } catch { return false; }
  });
  // Search visibility can precede the recorded setup owner's settlement. Bulk capture
  // must begin after that owner releases these paths, not race the fixture's own setup.
  await waitFor('Blue setup owners sealed and acknowledged', 60000, () => {
    const jobs = new DatabaseSync(path.join(data, 'jobs.db'), { readOnly: true });
    const operations = new DatabaseSync(path.join(data, 'operations.db'), { readOnly: true });
    try {
      const setup = operations.prepare('SELECT state, result_json FROM operations WHERE operation_key = ?')
        .get(operationKey);
      if (setup?.state !== 'COMPLETE' || JSON.parse(setup.result_json).code !== 'SUCCESS') return false;
      const paths = new Set([a, b].map(file => path.resolve(file).toLowerCase()));
      const units = jobs.prepare('SELECT path, scan_id, walk_seen_epoch FROM jobs').all()
        .filter(row => paths.has(path.resolve(row.path).toLowerCase()));
      return units.length === 2 && units.every(unit => {
        if (unit.walk_seen_epoch == null) return true;
        const walk = jobs.prepare('SELECT sealed_at, revision, acknowledged_revision '
          + 'FROM ingestion_walk_progress WHERE operation_key = ?').get(unit.scan_id);
        return walk?.sealed_at != null && walk.acknowledged_revision === walk.revision;
      });
    } finally { operations.close(); jobs.close(); }
  });
  const generationFile = path.join(indexBase, 'state.json');
  const blue = readJson(generationFile).active_generation;
  // Only A belongs to the accepted watched roots. B remains a real Blue-only document;
  // distinct generations therefore prove reader replacement without racing filesystem deletion.
  const rebuildKey = createOperationKey();
  const startResponse = await approvedPost('/api/indexing/migration/start', {
    reason: 'manual', idempotencyKey: rebuildKey,
  }, 'recorded migration');
  if (startResponse.status !== undefined) {
    const rebuildReceipt = requireOperationSuccess(startResponse, 'recorded migration', 202);
    requireThat(rebuildReceipt.operationKey === rebuildKey, 'rebuild changed its accepted operation key');
  }
  console.log('MIGRATION_START_RESPONSE', JSON.stringify(startResponse));
  const supervisorFile = path.join(data, 'runtime', 'supervisor.v1.json');
  const manifestFile = path.join(data, 'runtime', 'manifest.json');
  const promoted = await waitFor('start and promotion requested restarts', 180000, async () => {
    const s = readJson(supervisorFile);
    const m = readJson(manifestFile);
    const g = readJson(generationFile);
    return s?.state === 'running' && s.incarnation === first.incarnation + 2
      && m?.instanceId === s.instanceId && m?.pid === s.pid
      && g?.active_generation !== blue && g?.migration_state === 'IDLE'
      ? { supervisor: s, manifest: m, generation: g } : null;
  });
  requireThat(promoted.supervisor.restartCount === 0, 'voluntary restarts consumed crash budget');
  requireThat(promoted.supervisor.lastExit?.code === 4
    && promoted.supervisor.lastExit?.counted === false, 'promotion did not use clean requested restart');
  requireThat(promoted.manifest.instanceId !== manifest.instanceId, 'Engine was not replaced');
  await waitFor('promoted generation is actually served', 60000, async () => {
    try {
      const kept = await search(promoted.manifest.head.apiPort, 'migrationretainedmarker');
      const removed = await search(promoted.manifest.head.apiPort, 'migrationblueonlymarker');
      return matchingHit(kept, a, 'migrationretainedmarker') && removed.status === 200
        && !matchingHit(removed, b, 'migrationblueonlymarker');
    } catch { return false; }
  });
  const settlement = await waitFor('recorded rebuild terminal and exact queue acknowledgement', 60000, async () => {
    const operations = new DatabaseSync(path.join(data, 'operations.db'), { readOnly: true });
    const jobs = new DatabaseSync(path.join(data, 'jobs.db'), { readOnly: true });
    try {
      const row = operations.prepare('SELECT state, phase, building_generation_id, result_json, '
        + 'units_completed, units_failed FROM operations WHERE operation_key = ?').get(rebuildKey);
      const walk = jobs.prepare('SELECT planned_units, sealed_at, revision, acknowledged_revision '
        + 'FROM ingestion_walk_progress WHERE operation_key = ?').get(rebuildKey);
      return row?.state === 'COMPLETE' && row.phase === 'settled'
        && row.building_generation_id === `g-${rebuildKey}` && row.units_failed === 0
        && JSON.parse(row.result_json).code === 'SUCCESS' && walk?.planned_units === 1
        && walk.sealed_at != null && walk.acknowledged_revision === walk.revision ? { row, walk } : null;
    } finally { jobs.close(); operations.close(); }
  });
  requireThat(promoted.generation.active_generation === `g-${rebuildKey}`,
    'promotion did not use the accepted operation generation');
  // Keep the source scope empty on rollback; B exists outside it and remains distinguishable.

  fs.writeFileSync(path.join(data, 'watched_roots.json'), JSON.stringify({ roots: [] }));
  const rollbackResponse = await post(promoted.manifest.head.apiPort,
    '/api/indexing/migration/rollback', {}).catch(error => ({ deliveryUnknown: String(error) }));
  if (rollbackResponse.status !== undefined) requireThat(rollbackResponse.status === 202
    && JSON.parse(rollbackResponse.text).restartRequired === true, 'rollback requirement was not projected');
  console.log('MIGRATION_ROLLBACK_RESPONSE', JSON.stringify(rollbackResponse));
  const rolledBack = await waitFor('rollback requested restart', 90000, async () => {
    const s = readJson(supervisorFile);
    const m = readJson(manifestFile);
    return s?.state === 'running' && s.incarnation === first.incarnation + 3
      && s.instanceId === m?.instanceId && m?.pid === s.pid
      && readJson(generationFile)?.active_generation === blue ? { supervisor: s, manifest: m } : null;
  });
  requireThat(rolledBack.supervisor.restartCount === 0 && rolledBack.supervisor.lastExit?.code === 4
    && rolledBack.supervisor.lastExit?.counted === false, 'rollback did not use free requested restart');
  await waitFor('rollback really reopened Blue', 60000, async () => {
    try {
      return matchingHit(await search(rolledBack.manifest.head.apiPort, 'migrationblueonlymarker'),
        b, 'migrationblueonlymarker');
    } catch { return false; }
  });
  for (const incarnation of [first.incarnation, first.incarnation + 1, first.incarnation + 2]) {
    requireThat(c.output().includes(`Engine incarnation ${incarnation} exited 4 (requested_restart`),
      `incarnation ${incarnation} did not record its own clean requested restart`);
  }
  console.log('MIGRATION_PASS', JSON.stringify({ blue, rebuildKey, settlement, promoted, rolledBack, work }));
}
