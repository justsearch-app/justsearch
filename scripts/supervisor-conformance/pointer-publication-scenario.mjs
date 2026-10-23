import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';

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
    const ingest = await post(apiPort, '/api/knowledge/ingest', {
      paths: [path.join(work, 'pointer-corpus', 'probe.txt')],
      idempotencyKey: createOperationKey(),
    });
    check(ingest.status >= 200 && ingest.status < 300,
      `pointer fixture ingest failed: ${ingest.status} ${ingest.text}`);
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
  // Every call after the cut goes to the Engine incarnation running now: recovery may request
  // another restart, which supersedes the port of the incarnation first seen healthy.
  const currentRequest = async (endpoint, body) => {
    const supervisor = readJson(path.join(data, 'runtime', 'supervisor.v1.json'));
    const manifest = readJson(path.join(data, 'runtime', 'manifest.json'));
    if (supervisor?.state !== 'running' || manifest?.pid !== supervisor.pid
        || manifest?.instanceId !== supervisor.instanceId || manifest.pid === cut.pid) return null;
    try {
      return body === undefined ? await request(manifest.head.apiPort, endpoint)
        : await post(manifest.head.apiPort, endpoint, body);
    } catch { return null; }
  };
  let outcome = null;
  if (operationKey) {
    const candidate = `g-${operationKey}`;
    const source = cut.state.active_generation;
    check(cut.state.migration_state === 'MIGRATING' && cut.state.building_generation === candidate
        && source && source !== candidate,
      'the replacement cut must identify A serving and B building');
    outcome = await settleReplacement({ indexBase, data, work, operationKey, source, candidate,
      currentRequest, readJson });
    recovered.generation = readJson(path.join(indexBase, 'state.json'));
  }
  const permitted = [cut.state.active_generation, cut.state.building_generation].filter(Boolean);
  check(permitted.includes(recovered.generation.active_generation), 'restart guessed an unrelated generation');
  // Recovery can request another restart after the cut. Its lastExit is then exit 4,
  // so bind the crash evidence to the original writer's recorded incarnation.
  const writerReport = mode === 'replacement' ? readJson(path.join(work, 'state', 'runs',
    first.runId, 'incarnations', String(first.incarnation), 'stop-report.json')) : null;
  if (mode === 'replacement') {
    check(writerReport?.runId === first.runId && writerReport.incarnation === first.incarnation,
      'pointer writer exit report belongs to another incarnation');
  }
  const exit = initialExit ?? (writerReport
    ? { ...writerReport, exitCode: writerReport.backendExitCode } : recovered.supervisor.lastExit);
  check(exit?.exitCode === 73 || exit?.code === 73, 'the recorded writer did not die at the requested cut');
  const proof = { mode, point, cut, first, recovered, exit, operationKey, outcome };
  fs.writeFileSync(path.join(work, 'pointer-publication-proof.json'), JSON.stringify(proof, null, 2));
  console.log('POINTER_PUBLICATION_PASS', JSON.stringify(proof));
}

/**
 * Judges a replacement cut by the state after the restart, not by the pointer the cut recorded:
 * resuming forward before promotion is correct (as bulk-state-before-binding requires). Exactly
 * one clean terminal outcome must hold with every one of its conditions: (a) B promoted and the
 * operation COMPLETE, or (b) the clean refusal with A kept. Anything else fails, naming the
 * conditions that did not hold.
 */
async function settleReplacement({ indexBase, data, work, operationKey, source, candidate,
  currentRequest, readJson }) {
  const probe = path.resolve(work, 'pointer-corpus', 'probe.txt').toLowerCase();
  const deadline = Date.now() + 180000;
  let last;
  while (true) {
    last = await observeReplacement();
    if (last.failed.length === 0) return last;
    if (last.terminalMismatch || Date.now() >= deadline) break;
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error(`replacement cut reached neither clean outcome (${last.branch}); failed: `
    + `${last.failed.join('; ')}; observed: ${JSON.stringify(last.observed)}`);

  async function observeReplacement() {
    const generation = readJson(path.join(indexBase, 'state.json'));
    const operations = new DatabaseSync(path.join(data, 'operations.db'), { readOnly: true });
    const jobs = new DatabaseSync(path.join(data, 'jobs.db'), { readOnly: true });
    let rows;
    let journal;
    try {
      rows = operations.prepare('SELECT operation_key, state, failure_reason FROM operations').all()
        .map(row => ({ ...row }));
      journal = jobs.prepare('SELECT COUNT(*) AS n FROM switch_buffer').get().n;
    } finally { jobs.close(); operations.close(); }
    const operation = rows.find(row => row.operation_key === operationKey);
    const history = await currentRequest(`/api/operation-history/${operationKey}`);
    const historyState = history?.status === 200 ? JSON.parse(history.text).state : null;
    const search = await currentRequest('/api/knowledge/search',
      { query: 'pointerdurabilitymarker', mode: 'text', limit: 5 });
    const found = search?.status === 200 && JSON.parse(search.text).results?.some(hit =>
      path.resolve(String(hit?.fields?.path ?? '')).toLowerCase() === probe);
    const exists = generationId => fs.existsSync(path.join(indexBase, 'indices', generationId));
    const promoted = generation?.active_generation === candidate;
    const kept = generation?.active_generation === source;
    const branch = promoted ? '(a) B promoted' : kept ? '(b) clean refusal' : 'pointer on neither A nor B';
    const conditions = [
      ['pointer names A or B', promoted || kept],
      ['pointer IDLE', generation?.migration_state === 'IDLE'],
      ['no building generation', !generation?.building_generation],
      ['no previous generation', !generation?.previous_generation],
      ['nothing RUNNING', !rows.some(row => row.state === 'RUNNING')],
      ['journal empty', journal === 0],
      ...(promoted ? [
        ['operation COMPLETE', operation?.state === 'COMPLETE'],
        ['current Engine reports complete', historyState === 'complete'],
        ["A's directory retired", !exists(source)],
        ['file findable through B', found],
      ] : [
        ['operation FAILED with BULK_GENERATION_REFUSED', operation?.state === 'FAILED'
          && operation.failure_reason === 'BULK_GENERATION_REFUSED'],
        ['current Engine reports failed', historyState === 'failed'],
        ["B's directory gone", !exists(candidate)],
        ['A still finds the file', found],
      ]),
    ];
    const failed = conditions.filter(([, holds]) => !holds).map(([name]) => name);
    // A terminal operation never changes its outcome: once the pointer settled IDLE, an operation
    // state the branch forbids cannot become right by waiting.
    const terminalMismatch = generation?.migration_state === 'IDLE' && (promoted || kept)
      && operation != null && operation.state !== 'RUNNING'
      && failed.some(name => name.startsWith('operation '));
    return { branch, failed, terminalMismatch, outcome: promoted ? 'promoted' : 'refused',
      observed: { generation, operation, running: rows.filter(row => row.state === 'RUNNING'),
        journal, historyState, found, sourceDirectory: exists(source),
        candidateDirectory: exists(candidate) } };
  }
}
