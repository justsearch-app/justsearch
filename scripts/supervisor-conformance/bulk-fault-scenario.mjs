import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import identity from '../dev/lib/process-identity.cjs';

export const BULK_FAULT_CASES = Object.freeze({
  'bulk-partial-capture': Object.freeze({
    phase: 'bulk-partial-capture', finalIncarnation: 4, faultIncarnation: 1,
    requestedRestartIncarnations: Object.freeze([2, 3]), cutAttempts: 1, finalAttempts: 3,
  }),
  'bulk-state-before-binding': Object.freeze({
    phase: 'bulk-before-building-checkpoint', finalIncarnation: 3, faultIncarnation: 1,
    requestedRestartIncarnations: Object.freeze([2]), cutAttempts: 1, finalAttempts: 2,
  }),
  'bulk-promotion-before-terminal': Object.freeze({
    phase: 'bulk-after-promotion', finalIncarnation: 3, faultIncarnation: 2,
    requestedRestartIncarnations: Object.freeze([1]), cutAttempts: 2, finalAttempts: 2,
  }),
});

const OPERATION_KEY = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SESSION_HEADER = 'X-JustSearch-Session';
const START_ROUTE = '/api/indexing/migration/start';

/** Installed-process proof for the three recorded bulk crash boundaries. */
export async function exerciseBulkFault(c) {
  const { work, data, indexBase, first, manifest, apiPort, readJson, post,
    requireThat, requireOperationSuccess, matchingHit, scenario, operationKey } = c;
  const selected = BULK_FAULT_CASES[scenario];
  requireThat(selected, `unknown bulk fault scenario: ${scenario}`);
  requireThat(OPERATION_KEY.test(operationKey),
    `bulk fault key must be a caller-selected UUIDv7: ${operationKey}`);
  requireThat(first.pid === manifest.pid && first.instanceId === manifest.instanceId,
    'fixture may fault only an admitted Engine it launched');
  requireThat(first.incarnation === 1,
    `bulk fault fixture must begin at incarnation 1: ${JSON.stringify(first)}`);

  const deadline = Date.now() + 300000;
  const waitFor = (label, budget, probe) =>
    c.waitFor(label, Math.max(1, Math.min(budget, deadline - Date.now())), probe);
  const runtime = path.join(data, 'runtime');
  const reachedFile = path.join(runtime, 'operation-fault-reached.json');
  const releaseFile = path.join(runtime, 'operation-fault-release');
  requireThat(!fs.existsSync(reachedFile) && !fs.existsSync(releaseFile),
    'bulk fault fixture must start without a reached or release marker');

  const roots = [path.join(work, 'bulk-root-a'), path.join(work, 'bulk-root-b')];
  for (const root of roots) {
    requireThat(fs.statSync(root).isDirectory(), `root must be prebooted by the driver: ${root}`);
  }
  const registry = readJson(path.join(data, 'watched_roots.json'));
  requireThat(registry?.schemaVersion === 1 && Array.isArray(registry.roots)
    && registry.roots.length === roots.length
    && registry.roots.every((root, index) => samePath(root.path, roots[index])),
  `watched roots must be exactly A then B: ${JSON.stringify(registry)}`);

  const files = [path.join(roots[0], 'a.txt'), path.join(roots[1], 'b.txt')];
  const markers = [`bulkfault${scenario.replaceAll('-', '')}alpha`,
    `bulkfault${scenario.replaceAll('-', '')}bravo`];
  files.forEach((file, index) => fs.writeFileSync(file, `${markers[index]} durable bulk recovery quokka\n`));
  const hashes = files.map(file => sha256(fs.readFileSync(file)));
  const operationPath = path.join(data, 'operations.db');
  const jobsPath = path.join(data, 'jobs.db');
  const sourceGeneration = readJson(path.join(indexBase, 'state.json'))?.active_generation;
  requireThat(typeof sourceGeneration === 'string' && sourceGeneration.length > 0,
    'bulk fault fixture requires an authoritative pre-dispatch active generation');
  requireThat(operationRows(operationPath, operationKey).length === 0,
    'caller-selected bulk key must be unknown before dispatch');

  const headers = sessionHeaders(manifest);
  const input = { reason: 'manual', idempotencyKey: operationKey };
  const prepared = await prepareApprovedDispatch({ apiPort, input, post, requireThat });
  const dispatched = startHeldPost(apiPort, START_ROUTE, {
    ...input, confirmationToken: prepared.capsule, preparationNonce: prepared.nonce,
  }, headers);

  const reached = await waitFor(`bulk fault marker ${scenario}`, 170000,
    () => readJson(reachedFile) ?? null);
  requireThat(reached.phase === selected.phase && reached.parentKind === 'reindex'
    && reached.parentKey === operationKey && reached.operationKey === operationKey
    && Number.isSafeInteger(reached.operationRecordId) && reached.operationRecordId > 0,
  `bulk hook reached the wrong boundary: ${JSON.stringify(reached)}`);

  const faulted = await waitFor('admitted Engine that emitted the bulk marker', 10000, () => {
    const supervisor = readJson(path.join(runtime, 'supervisor.v1.json'));
    const currentManifest = readJson(path.join(runtime, 'manifest.json'));
    return supervisor?.state === 'running' && supervisor.pid === reached.pid
      && supervisor.runId === first.runId
      && supervisor.incarnation === selected.faultIncarnation
      && currentManifest?.pid === supervisor.pid
      && currentManifest.instanceId === supervisor.instanceId
      ? { supervisor, manifest: currentManifest } : null;
  });
  const processRecord = captureEngineIdentity(faulted.supervisor, data, requireThat);
  const cooldown = await killOwnedEngineAndObserveCooldown({
    record: processRecord, engine: faulted.supervisor, data, readJson, waitFor, requireThat,
  });
  await dispatched.settled;

  const cut = snapshot({ operationPath, jobsPath, indexBase, operationKey });
  fs.writeFileSync(path.join(work, 'bulk-cut.json'), JSON.stringify({
    scenario, operationKey, reached, cooldown, cut,
  }, null, 2));
  assertCommonCut({ cut, reached, prepared, operationKey, selected, roots,
    sourceGeneration, requireThat });
  assertSelectedCut({ selected, cut, files, hashes, operationKey, requireThat });

  const expectedIncarnation = first.incarnation + selected.finalIncarnation - 1;
  const recovered = await waitFor('bulk successor reaches its final physical incarnation', 180000, () => {
    const operation = operationRows(operationPath, operationKey)[0];
    if (operation?.state === 'FAILED' || operation?.state === 'CANCELLED') {
      const failed = snapshot({ operationPath, jobsPath, indexBase, operationKey });
      fs.writeFileSync(path.join(work, 'bulk-failed.json'), JSON.stringify(failed, null, 2));
      throw new Error(`bulk recovery terminated before its final incarnation: ${operation.state} ${operation.failure_reason}`);
    }
    const supervisor = readJson(path.join(runtime, 'supervisor.v1.json'));
    const currentManifest = readJson(path.join(runtime, 'manifest.json'));
    return supervisor?.state === 'running' && supervisor.runId === first.runId
      && supervisor.incarnation === expectedIncarnation
      && currentManifest?.pid === supervisor.pid
      && currentManifest.instanceId === supervisor.instanceId
      ? { supervisor, manifest: currentManifest } : null;
  });
  requireThat(recovered.supervisor.restartCount === 1,
    `bulk crash must spend exactly one supervisor restart: ${JSON.stringify(recovered.supervisor)}`);
  if (selected.requestedRestartIncarnations.at(-1) === expectedIncarnation - 1) {
    requireThat(recovered.supervisor.lastExit?.code === 4
      && recovered.supervisor.lastExit?.counted === false,
    `last boundary must be a free requested restart: ${JSON.stringify(recovered.supervisor)}`);
  } else {
    requireThat(recovered.supervisor.lastExit?.counted === true,
      `promotion crash must remain the last counted exit: ${JSON.stringify(recovered.supervisor)}`);
  }
  for (const incarnation of selected.requestedRestartIncarnations) {
    requireThat(c.output().includes(`Engine incarnation ${incarnation} exited 4 (requested_restart`),
      `incarnation ${incarnation} did not record its own requested restart`);
  }

  const final = await waitFor('bulk terminal success and exact queue acknowledgement', 90000, () => {
    const observed = snapshot({ operationPath, jobsPath, indexBase, operationKey });
    return observed.operations.length === 1
      && observed.operation?.state === 'COMPLETE'
      && observed.operation.phase === 'settled'
      && observed.walk?.sealed_at != null
      && observed.walk.acknowledged_revision === observed.walk.revision
      ? observed : null;
  });
  fs.writeFileSync(path.join(work, 'bulk-final.json'), JSON.stringify(final, null, 2));
  assertFinal({ final, cut, prepared, files, hashes, operationKey, selected, requireThat });

  const searchEvidence = [];
  for (let index = 0; index < files.length; index++) {
    const search = await waitFor(`promoted target search ${index + 1}`, 60000, async () => {
      try {
        const response = await post(recovered.manifest.head.apiPort, '/api/knowledge/search', {
          query: markers[index], limit: 10, mode: 'text',
        });
        return response.status === 200 && matchingHit(response, files[index], markers[index])
          ? response : null;
      } catch { return null; }
    });
    const matching = JSON.parse(search.text).results.filter(hit =>
      samePath(hit?.fields?.path, files[index]));
    requireThat(matching.length === 1,
      `promoted target must contain exactly one hit for ${files[index]}: ${search.text}`);
    searchEvidence.push({ path: files[index], matches: matching.length });
  }

  const stableOperation = final.operation;
  const stableQueue = queueProjection(final);
  const retry = await preparedPost({ apiPort: recovered.manifest.head.apiPort, input,
    post, requireThat });
  const retryReceipt = requireOperationSuccess(retry, 'bulk fault same-key replay', 202);
  requireThat(retryReceipt.operationKey === operationKey
    && retryReceipt.operationRecordId === stableOperation.id,
  `same-key replay changed bulk identity: ${retry.text}`);
  const afterRetry = snapshot({ operationPath, jobsPath, indexBase, operationKey });
  fs.writeFileSync(path.join(work, 'bulk-after-retry.json'), JSON.stringify(afterRetry, null, 2));
  requireThat(sameJson(afterRetry.operation, stableOperation)
    && sameJson(queueProjection(afterRetry), stableQueue),
  `same-key replay changed its operation row or queue: ${JSON.stringify({
    before: { operation: stableOperation, queue: stableQueue },
    after: { operation: afterRetry.operation, queue: queueProjection(afterRetry) },
  })}`);

  console.log('BULK_FAULT_PASS', JSON.stringify({
    scenario, operationKey, phase: selected.phase,
    faulted: compactSupervisor(faulted.supervisor), cooldown: compactSupervisor(cooldown),
    recovered: compactSupervisor(recovered.supervisor), operation: {
      id: final.operation.id, operation_key: final.operation.operation_key,
      state: final.operation.state, phase: final.operation.phase,
      attempts: final.operation.attempts, units_failed: final.operation.units_failed,
    },
    walk: final.walk, members: final.jobs.map(compactMember), search: searchEvidence,
    preparationSha256: sha256(Buffer.from(final.operation.preparation_payload, 'utf8')),
  }));
  return { reached, cooldown, recovered, operation: final.operation, walk: final.walk,
    members: final.jobs, search: searchEvidence };
}

async function prepareApprovedDispatch({ apiPort, input, post, requireThat }) {
  const response = await post(apiPort, START_ROUTE, input);
  requireThat(response.status === 428,
    `bulk fault must exercise prepared approval: HTTP ${response.status} ${response.text}`);
  const pending = parseJson(response, 'bulk preparation');
  requireThat(typeof pending.pendingId === 'string' && typeof pending.preparationNonce === 'string'
    && pending.operationKey === input.idempotencyKey,
  `bulk preparation omitted exact key/nonce binding: ${response.text}`);
  const approval = await post(apiPort, '/api/authorizations/approve', { pendingId: pending.pendingId });
  const approved = parseJson(approval, 'bulk approval');
  requireThat(approval.status === 200 && typeof approved.capsule === 'string',
    `bulk approval failed: ${approval.text}`);
  return { nonce: pending.preparationNonce, capsule: approved.capsule };
}

async function preparedPost({ apiPort, input, post, requireThat }) {
  const response = await post(apiPort, START_ROUTE, input);
  if (response.status !== 428) return response;
  const pending = parseJson(response, 'bulk replay preparation');
  requireThat(pending.operationKey === input.idempotencyKey
    && typeof pending.pendingId === 'string' && typeof pending.preparationNonce === 'string',
  `bulk replay preparation lost its identity: ${response.text}`);
  const approval = await post(apiPort, '/api/authorizations/approve', { pendingId: pending.pendingId });
  const approved = parseJson(approval, 'bulk replay approval');
  requireThat(approval.status === 200 && typeof approved.capsule === 'string',
    `bulk replay approval failed: ${approval.text}`);
  return post(apiPort, START_ROUTE, { ...input, confirmationToken: approved.capsule,
    preparationNonce: pending.preparationNonce });
}

function assertCommonCut({ cut, reached, prepared, operationKey, selected, roots,
  sourceGeneration, requireThat }) {
  requireThat(cut.operations.length === 1 && cut.reindexOperations.length === 1,
    `fault cut must retain exactly one reindex row: ${JSON.stringify(cut.reindexOperations)}`);
  const row = cut.operation;
  requireThat(row.id === reached.operationRecordId && row.operation_key === operationKey
    && row.kind === 'reindex' && row.operation_ref === 'core.rebuild-index'
    && row.state === 'RUNNING' && row.attempts === selected.cutAttempts
    && row.preparation_nonce === prepared.nonce && row.preparation_sealed === 0,
  `fault cut lost its accepted prepared operation: ${JSON.stringify(row)}`);
  const plan = preparationPlan(row.preparation_payload);
  requireThat(plan.profile === 'RECOVERY_REBUILD'
    && plan.source === 'manual'
    && plan.scope?.generation === sourceGeneration
    && plan.scope?.roots?.length === roots.length
    && plan.scope.roots.every((root, index) => samePath(root.path, roots[index])
      && root.force === true && root.singleFile === false),
  `accepted preparation is not the original frozen rebuild plan: ${JSON.stringify(plan)}`);
}

function assertSelectedCut({ selected, cut, files, hashes, operationKey, requireThat }) {
  const target = `g-${operationKey}`;
  const row = cut.operation;
  if (selected.phase === 'bulk-partial-capture') {
    requireThat(row.phase === 'capturing' && cut.walk?.captured_plan === 1
      && cut.walk.enumeration_closed_at == null && cut.walk.enumeration_outcome == null
      && cut.walk.sealed_at == null && cut.walk.acknowledged_revision === 0,
    `partial capture cut must retain an open captured epoch: ${JSON.stringify(cut)}`);
    requireThat(cut.jobs.length === 1 && samePath(cut.jobs[0].path, files[0])
      && cut.jobs[0].planned_source_sha256 === hashes[0]
      && !cut.jobs.some(member => samePath(member.path, files[1])),
    `partial capture cut must contain exact H1 and exclude root B: ${JSON.stringify(cut.jobs)}`);
    requireThat(cut.state.active_generation !== target
      && !cut.state.building_generation && cut.state.migration_state === 'IDLE'
      && !fs.existsSync(path.join(cut.indexBase, 'indices', target)),
    `partial capture must precede every Green effect: ${JSON.stringify(cut.state)}`);
    return;
  }

  if (selected.phase === 'bulk-before-building-checkpoint') {
    requireThat(row.phase === 'capturing' && cut.walk?.captured_plan === 1
      && cut.walk.enumeration_outcome === 'COMPLETE' && cut.walk.enumeration_closed_at != null
      && cut.walk.manifest_sha256 && cut.walk.planned_units === 2 && cut.walk.sealed_at == null,
    `pre-binding cut must retain a complete closed manifest under CAPTURING: ${JSON.stringify(cut)}`);
    requireExactMembers(cut.jobs, files, hashes, false, requireThat);
    requireThat(cut.state.active_generation !== target
      && cut.state.building_generation === target && cut.state.migration_state === 'MIGRATING',
    `pre-binding cut must retain the exact MIGRATING Green: ${JSON.stringify(cut.state)}`);
    const generation = cut.generationManifest;
    requireThat(generation?.generation_id === target
      && generation.source === preparationPlan(row.preparation_payload).source
      && generation.target_index_fingerprint === preparationPlan(row.preparation_payload).target.fingerprint,
    `pre-binding Green metadata is not operation-derived: ${JSON.stringify(generation)}`);
    return;
  }

  requireThat(row.phase === 'settled' && row.building_generation_id === target
    && row.units_failed === 0 && cut.state.active_generation === target
    && !cut.state.building_generation && cut.state.migration_state === 'IDLE',
  `post-promotion cut must retain active target and durable settlement: ${JSON.stringify(cut)}`);
  requireThat(cut.walk?.sealed_at != null && cut.walk.receipt_json
    && cut.walk.acknowledged_revision < cut.walk.revision,
  `post-promotion cut must remain sealed and unacknowledged: ${JSON.stringify(cut.walk)}`);
  requireExactMembers(cut.jobs, files, hashes, true, requireThat);
}

function assertFinal({ final, cut, prepared, files, hashes, operationKey, selected, requireThat }) {
  const target = `g-${operationKey}`;
  const row = final.operation;
  const receipt = parseStoredJson(row.result_json, 'bulk result');
  const queueReceipt = parseStoredJson(final.walk.receipt_json, 'bulk queue receipt');
  const operationEvidence = parseStoredJson(row.processing_history_counts_json,
    'bulk operation evidence');
  const plan = preparationPlan(row.preparation_payload);
  requireThat(final.operations.length === 1 && final.reindexOperations.length === 1
    && row.id === cut.operation.id && row.operation_key === operationKey
    && row.state === 'COMPLETE' && row.phase === 'settled'
    && row.building_generation_id === target && row.attempts === selected.finalAttempts
    && row.units_completed === 2 && row.units_failed === 0
    && receipt.code === 'SUCCESS',
  `bulk recovery did not terminate its original row exactly: ${JSON.stringify(row)}`);
  requireThat(row.preparation_nonce === prepared.nonce
    && row.preparation_payload === cut.operation.preparation_payload,
  'bulk recovery changed the accepted nonce or frozen preparation');
  requireThat(final.state.active_generation === target && !final.state.building_generation
    && final.state.migration_state === 'IDLE',
  `bulk recovery did not serve the exact target: ${JSON.stringify(final.state)}`);
  requireThat(queueReceipt.version === 2 && queueReceipt.plannedUnits === 2
    && queueReceipt.manifestSha256 === final.walk.manifest_sha256
    && queueReceipt.gapCount === 0 && queueReceipt.failedEvents === 0
    && queueReceipt.supersededEvents === 0
    && final.walk.acknowledged_revision === final.walk.revision,
  `bulk recovery did not seal and ACK its exact successful receipt: ${JSON.stringify(final.walk)}`);
  requireThat(operationEvidence.version === 2
    && operationEvidence.targetFingerprint === plan.target.fingerprint
    && operationEvidence.capture?.manifestSha256 === final.walk.manifest_sha256
    && operationEvidence.capture?.plannedUnits === 2
    && operationEvidence.sealedRevision === final.walk.revision
    && operationEvidence.settlementSha256 === queueReceipt.settlementSha256
    && operationEvidence.failedEvents === 0 && operationEvidence.supersededEvents === 0
    && operationEvidence.refusalCode == null,
  `operation checkpoint is not bound to the exact queue settlement: ${JSON.stringify(operationEvidence)}`);
  requireExactMembers(final.jobs, files, hashes, true, requireThat);
  requireThat(cut.jobs.every(before => final.jobs.some(after => samePath(after.path, before.path)
    && after.unit_revision === before.unit_revision
    && after.planned_source_sha256 === before.planned_source_sha256)),
  'recovery replaced an already captured member instead of retaining its original revision/H1');
  if (cut.walk.manifest_sha256 != null) requireThat(
    final.walk.manifest_sha256 === cut.walk.manifest_sha256,
    'recovery changed the already closed capture manifest');
  requireThat(final.ledger.length === 2
    && final.ledger.every(event => event.terminal_coverage === 'INDEXED'
      && event.content_hash === event.planned_source_sha256),
  `bulk recovery lacks exact indexed ledger coverage: ${JSON.stringify(final.ledger)}`);
  requireThat(final.recordedGenerations.length === 1 && final.recordedGenerations[0] === target,
    `bulk recovery created more than its one operation-derived target: ${JSON.stringify(final.recordedGenerations)}`);
}

function requireExactMembers(members, files, hashes, terminal, requireThat) {
  requireThat(members.length === files.length
    && members.every((member, index) => samePath(member.path, files[index])
      && member.planned_source_sha256 === hashes[index]
      && member.walk_seen_epoch != null
      && (!terminal || member.state === 'DONE' && member.content_hash === hashes[index])),
  `captured members differ from the exact ordered source set: ${JSON.stringify(members)}`);
}

function snapshot({ operationPath, jobsPath, indexBase, operationKey }) {
  const operations = operationRows(operationPath, operationKey);
  const operation = operations[0] ?? null;
  const jobs = readRows(jobsPath, `SELECT path, state, attempts, content_hash,
    planned_source_sha256, scan_id, unit_revision, walk_seen_epoch FROM jobs
    WHERE scan_id = ? AND walk_seen_epoch IS NOT NULL ORDER BY lower(path)`, operationKey);
  const walk = readOne(jobsPath, 'SELECT * FROM ingestion_walk_progress WHERE operation_key = ?', operationKey);
  const ledger = readRows(jobsPath, `SELECT path_hash, unit_revision, outcome_class, reason_code,
    retry_policy, terminal_coverage, content_hash, planned_source_sha256
    FROM ingestion_ledger WHERE operation_key = ? AND terminal_coverage IS NOT NULL ORDER BY path_hash, id`, operationKey);
  const state = readJsonFile(path.join(indexBase, 'state.json'));
  const target = `g-${operationKey}`;
  const generationManifest = readJsonFile(path.join(indexBase, 'indices', target,
    '.justsearch-index-generation.json'));
  const recordedGenerations = fs.existsSync(path.join(indexBase, 'indices'))
    ? fs.readdirSync(path.join(indexBase, 'indices'), { withFileTypes: true })
      .filter(entry => entry.isDirectory() && /^g-[0-9a-f-]{36}$/.test(entry.name))
      .map(entry => entry.name).sort()
    : [];
  return { indexBase, operations, operation,
    reindexOperations: readRows(operationPath, "SELECT id, operation_key FROM operations WHERE kind = 'reindex' ORDER BY id"),
    jobs, walk, ledger, state, generationManifest, recordedGenerations };
}

function operationRows(dbPath, operationKey) {
  return readRows(dbPath, `SELECT id, operation_key, kind, state, phase, operation_ref,
    identity_json, checkpoint_cursor, units_completed, units_failed, attempts,
    accepted_at, started_at, updated_at, completed_at, failure_reason, failure_detail,
    result_json, building_generation_id, target_settings_json, gaps_json,
    processing_history_json, processing_history_counts_json,
    preparation_nonce, preparation_sealed, preparation_payload
    FROM operations WHERE operation_key = ?`, operationKey);
}

function readRows(dbPath, sql, parameter) {
  const database = new DatabaseSync(dbPath, { readOnly: true });
  try {
    const statement = database.prepare(sql);
    return parameter === undefined ? statement.all() : statement.all(parameter);
  } finally { database.close(); }
}

function readOne(dbPath, sql, parameter) {
  const rows = readRows(dbPath, sql, parameter);
  if (rows.length > 1) throw new Error(`expected one SQLite row, received ${rows.length}`);
  return rows[0] ?? null;
}

function queueProjection(value) {
  return { jobs: value.jobs, walk: value.walk, ledger: value.ledger };
}

function preparationPlan(payload) {
  const envelope = parseStoredJson(payload, 'accepted preparation envelope');
  return parseStoredJson(envelope.preparation?.replayPayloadJson, 'accepted bulk replay plan');
}

function captureEngineIdentity(engine, data, requireThat) {
  const table = identity.readProcessTable();
  requireThat(table.ok, `Engine process identity must be readable: ${table.reason ?? 'unknown'}`);
  const original = table.table.find(row => Number(row.ProcessId) === engine.pid);
  requireThat(original?.CommandLine?.includes(data),
    'Engine process identity must name this fixture data directory');
  return { pid: engine.pid, creationFileTimeUtc: original.CreationFileTimeUtc,
    cmdlineFingerprint: original.CommandLine };
}

async function killOwnedEngineAndObserveCooldown({ record, engine, data, readJson, waitFor, requireThat }) {
  const before = readJson(path.join(data, 'runtime', 'supervisor.v1.json'));
  requireThat(before?.pid === engine.pid && before.instanceId === engine.instanceId
    && before.runId === engine.runId && before.incarnation === engine.incarnation,
  'only the captured admitted Engine may receive this bulk fault');
  const verified = identity.verifyProcessIdentity({ record, table: identity.readProcessTable() });
  requireThat(identity.isVerifiedMatch(verified),
    `refusing unverified bulk fault: ${verified.reason}`);

  process.kill(engine.pid, 'SIGKILL');
  const supervisorFile = path.join(data, 'runtime', 'supervisor.v1.json');
  return waitFor('bulk Engine death and counted restart cooldown', 12000, () => {
    let alive = true;
    try { process.kill(engine.pid, 0); }
    catch (error) {
      if (error.code === 'ESRCH') alive = false;
      else throw error;
    }
    const state = readJson(supervisorFile);
    return !alive && state?.runId === engine.runId && state.state === 'restarting'
      && state.incarnation === engine.incarnation && state.restartCount === 1
      && state.lastExit?.counted === true ? state : null;
  });
}

function startHeldPost(apiPort, endpoint, body, headers) {
  const settled = fetch(`http://127.0.0.1:${apiPort}${endpoint}`, {
    method: 'POST', headers, body: JSON.stringify(body),
  }).then(async response => ({ response: { status: response.status, text: await response.text() } }),
    error => ({ error }));
  return { settled };
}

function sessionHeaders(manifest) {
  const headers = { 'content-type': 'application/json' };
  const token = manifest.head?.sessionToken;
  if (typeof token === 'string' && token.length > 0) headers[SESSION_HEADER] = token;
  return headers;
}

function readJsonFile(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); }
  catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
}

function parseJson(response, label) {
  try { return JSON.parse(response.text); }
  catch { throw new Error(`${label} returned invalid JSON: ${response.text}`); }
}

function parseStoredJson(value, label) {
  try { return JSON.parse(value); }
  catch { throw new Error(`${label} is invalid JSON`); }
}

function samePath(left, right) {
  return typeof left === 'string' && typeof right === 'string'
    && path.resolve(left).toLowerCase() === path.resolve(right).toLowerCase();
}

function sameJson(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function sha256(bytes) {
  return crypto.createHash('sha256').update(bytes).digest('hex');
}

function compactSupervisor(value) {
  return { runId: value.runId, instanceId: value.instanceId, pid: value.pid,
    incarnation: value.incarnation, restartCount: value.restartCount,
    state: value.state, lastExit: value.lastExit };
}

function compactMember(value) {
  return { path: value.path, state: value.state, contentHash: value.content_hash,
    plannedSourceSha256: value.planned_source_sha256, unitRevision: value.unit_revision,
    walkSeenEpoch: value.walk_seen_epoch };
}
