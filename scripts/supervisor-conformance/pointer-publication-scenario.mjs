import fs from 'node:fs';
import path from 'node:path';

export function preparePointerRecovery(indexBase) {
  const generation = 'g-pointer-recovery-fixture';
  const directory = path.join(indexBase, 'indices', generation);
  fs.mkdirSync(directory, { recursive: true });
  const now = Date.now();
  fs.writeFileSync(path.join(directory, '.justsearch-generation.sentinel'),
    `justsearch_generation_sentinel_v1\ngeneration_id=${generation}\ncreated_at_ms=${now}\n`);
  fs.writeFileSync(path.join(directory, '.justsearch-index-generation.json'), JSON.stringify({
    format_version: 1, generation_id: generation, source: 'new', created_at_ms: now,
  }));
  const state = JSON.stringify({
    format_version: 2, active_generation: generation, migration_state: 'IDLE',
    migration_paused: false, updated_at_ms: now,
  });
  fs.writeFileSync(path.join(indexBase, 'state.json.prev'), state);
  fs.writeFileSync(path.join(indexBase, 'state.json'), state);
}

export async function exercisePointerPublication(c) {
  const { work, data, indexBase, mode, point, first, apiPort, readJson, waitFor,
    request, post, requireThat: check, createOperationKey, initialExit } = c;
  let operationKey = null;
  if (mode === 'replacement') {
    await waitFor('pointer fixture corpus searchable', 60000, async () => {
      const response = await post(apiPort, '/api/knowledge/search',
        { query: 'pointerdurabilitymarker', mode: 'text', limit: 5 });
      return response.status === 200 && JSON.parse(response.text).results.length > 0;
    });
    const body = { reason: 'manual', idempotencyKey: createOperationKey() };
    operationKey = body.idempotencyKey;
    // One dispatch only; a disconnect after the crash cut never authorizes a duplicate attempt.
    try {
      let response = await post(apiPort, '/api/indexing/migration/start', body);
      if (response.status === 428) {
        const pending = JSON.parse(response.text);
        check(pending.operationKey === body.idempotencyKey, 'approval changed the operation key');
        const approval = await post(apiPort, '/api/authorizations/approve', { pendingId: pending.pendingId });
        const accepted = JSON.parse(approval.text);
        check(approval.status === 200 && typeof accepted.capsule === 'string', 'migration approval failed');
        response = await post(apiPort, '/api/indexing/migration/start', { ...body,
          confirmationToken: accepted.capsule, preparationNonce: pending.preparationNonce });
      }
      check(response.status === 202, `migration dispatch failed: ${response.status} ${response.text}`);
    } catch (error) {
      if (!['TypeError', 'AbortError', 'TimeoutError'].includes(error.name)
          || !readJson(path.join(indexBase, 'pointer-publication-cut'))) throw error;
    }
  }
  const cut = await waitFor('forced pointer publication kill marker', 60000,
    () => readJson(path.join(indexBase, 'pointer-publication-cut')));
  check(cut.point === point && Number.isSafeInteger(cut.pid), 'pointer cut did not identify its writer');
  if (mode === 'replacement') {
    check(cut.pid === first.pid && cut.state.building_generation === `g-${operationKey}`,
      'pointer cut belongs to another writer or operation');
  }
  const recovered = await waitFor('healthy successor with an authoritative pointer', 90000, async () => {
    const supervisor = readJson(path.join(data, 'runtime', 'supervisor.v1.json'));
    const manifest = readJson(path.join(data, 'runtime', 'manifest.json'));
    const generation = readJson(path.join(indexBase, 'state.json'));
    if (supervisor?.state !== 'running' || manifest?.pid !== supervisor.pid
        || manifest?.instanceId !== supervisor.instanceId || manifest.pid === cut.pid || !generation) return null;
    try {
      const response = await request(manifest.head.apiPort, '/api/status');
      if (response.status !== 200
          || JSON.parse(response.text).readiness?.engineComponents?.index?.state !== 'READY') return null;
    } catch { return null; }
    return { supervisor, manifest, generation };
  });
  if (operationKey) {
    await waitFor('recovered migration releases its recorded owner', 120000, async () => {
      const response = await request(recovered.manifest.head.apiPort, `/api/operation-history/${operationKey}`);
      if (response.status !== 200) return false;
      const outcome = JSON.parse(response.text);
      check(!['failed', 'cancelled', 'expired', 'unknown'].includes(outcome.state),
        `recorded migration did not recover: ${response.text}`);
      return outcome.state === 'complete';
    });
    recovered.generation = readJson(path.join(indexBase, 'state.json'));
  }
  const permitted = [cut.state.active_generation, cut.state.building_generation].filter(Boolean);
  check(permitted.includes(recovered.generation.active_generation), 'restart guessed an unrelated generation');
  const exit = initialExit ?? recovered.supervisor.lastExit;
  check(exit?.exitCode === 73 || exit?.code === 73, 'the recorded writer did not die at the requested cut');
  const proof = { mode, point, cut, first, recovered, exit, operationKey };
  fs.writeFileSync(path.join(work, 'pointer-publication-proof.json'), JSON.stringify(proof, null, 2));
  console.log('POINTER_PUBLICATION_PASS', JSON.stringify(proof));
}
