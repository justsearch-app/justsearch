import fs from 'node:fs';
import path from 'node:path';

// Installed-process regression, sharing the existing supervisor fixture's owned launch/cleanup.
export async function exerciseMigrationRestart(c) {
  const { work, data, indexBase, first, manifest, apiPort, readJson,
    post, requireThat, acceptedCount, matchingHit } = c;
  const deadline = Date.now() + 270000;
  const waitFor = (label, budget, probe) =>
    c.waitFor(label, Math.max(1, Math.min(budget, deadline - Date.now())), probe);
  const sources = path.join(work, 'migration-sources');
  fs.mkdirSync(sources);
  const a = path.join(sources, 'a.txt');
  const b = path.join(sources, 'b.txt');
  fs.writeFileSync(a, 'migrationretainedmarker quokka');
  fs.writeFileSync(b, 'migrationblueonlymarker wombat');
  const ingest = await post(apiPort, '/api/knowledge/ingest', { paths: [a, b] });
  requireThat(acceptedCount(ingest) === 2, `two documents must be accepted: ${ingest.text}`);
  const search = async (port, marker) => post(port, '/api/knowledge/search', {
    query: marker, limit: 5, mode: 'text',
  });
  await waitFor('both Blue documents searchable', 60000, async () => {
    try {
      return matchingHit(await search(apiPort, 'migrationretainedmarker'), a, 'migrationretainedmarker')
        && matchingHit(await search(apiPort, 'migrationblueonlymarker'), b, 'migrationblueonlymarker');
    } catch { return false; }
  });
  const generationFile = path.join(indexBase, 'state.json');
  const blue = readJson(generationFile).active_generation;
  // The running Engine has no watched roots. Seed next boot's enumerator only, so Blue keeps B
  // while Green is built from A. Equal source sets would not prove that the live reader reopened.
  fs.writeFileSync(path.join(data, 'watched_roots.json'), JSON.stringify({ roots: [{ path: sources }] }));
  fs.unlinkSync(b);
  const startResponse = await post(apiPort, '/api/indexing/migration/start', { reason: 'manual' })
    .catch(error => ({ deliveryUnknown: String(error) }));
  if (startResponse.status !== undefined) requireThat(startResponse.status === 202
    && JSON.parse(startResponse.text).restartRequired === true, 'start requirement was not projected');
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
  // Keep rollback's old Blue reader distinguishable: do not rescan away its missing source.
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
  console.log('MIGRATION_PASS', JSON.stringify({ blue, promoted, rolledBack, work }));
}
