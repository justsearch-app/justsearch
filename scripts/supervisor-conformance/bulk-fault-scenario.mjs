import crypto from 'node:crypto';
import { execFile } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import identity from '../dev/lib/process-identity.cjs';
import { barrierFiles } from './barrier-files.mjs';

export const BULK_FAULT_CASES = Object.freeze({
  'bulk-live-before-green-open': Object.freeze({
    phase: 'migration-before-live-green-open', liveStart: true,
    finalIncarnation: 2, faultIncarnation: 1,
    requestedRestartIncarnations: Object.freeze([]), cutAttempts: 1, finalAttempts: 2,
  }),
  'bulk-live-after-green-open': Object.freeze({
    phase: 'migration-after-live-green-open', liveStart: true,
    finalIncarnation: 2, faultIncarnation: 1,
    requestedRestartIncarnations: Object.freeze([]), cutAttempts: 1, finalAttempts: 2,
  }),
  'bulk-live-refused-before-green-open': Object.freeze({
    phase: 'migration-before-live-green-open', liveStart: true, liveRefusal: true,
    finalIncarnation: 2, faultIncarnation: 1,
    requestedRestartIncarnations: Object.freeze([1]), cutAttempts: 1, finalAttempts: 2,
  }),
  'bulk-partial-capture': Object.freeze({
    // Recovery boots FENCED before the captured walk can finish. That physical attachment
    // cannot hand off a NATIVE producer live, so the durable BUILDING start uses one free
    // requested restart to reopen Green; the original crash remains the only counted exit.
    phase: 'bulk-partial-capture', finalIncarnation: 3, faultIncarnation: 1,
    requestedRestartIncarnations: Object.freeze([2]), cutAttempts: 1, finalAttempts: 3,
  }),
  'bulk-state-before-binding': Object.freeze({
    phase: 'bulk-before-building-checkpoint', finalIncarnation: 2, faultIncarnation: 1,
    requestedRestartIncarnations: Object.freeze([]), cutAttempts: 1, finalAttempts: 2,
  }),
  'bulk-captured-edit-before-building-checkpoint': Object.freeze({
    phase: 'bulk-before-building-checkpoint', capturedEdit: true,
    finalIncarnation: 2, faultIncarnation: 1,
    requestedRestartIncarnations: Object.freeze([]), cutAttempts: 1, finalAttempts: 2,
  }),
  'bulk-promotion-before-terminal': Object.freeze({
    phase: 'bulk-after-promotion', finalIncarnation: 2, faultIncarnation: 1,
    requestedRestartIncarnations: Object.freeze([]), cutAttempts: 1, finalAttempts: 1,
  }),
});

// The activation operation has its own durable operation identity and plan assertions below.
// The marker cut plus the five installed coordinator callbacks cover the composite crash sequence;
// the legacy bulk migration cases remain unchanged above.
export const INSTALLER_FAULT_CASES = Object.freeze({
  'installer-before-marker': Object.freeze({
    phase: 'installer-before-marker', finalIncarnation: 2, faultIncarnation: 1,
    requestedRestartIncarnations: Object.freeze([]), cutAttempts: 1, finalAttempts: 2,
  }),
  'installer-before-arm': Object.freeze({
    phase: 'installer-before-arm', finalIncarnation: 2, faultIncarnation: 1,
    requestedRestartIncarnations: Object.freeze([]), cutAttempts: 1, finalAttempts: 2,
  }),
  'installer-before-pointer': Object.freeze({
    phase: 'installer-before-pointer', finalIncarnation: 2, faultIncarnation: 1,
    requestedRestartIncarnations: Object.freeze([]), cutAttempts: 1, finalAttempts: 2,
  }),
  'installer-pointer-before-settings': Object.freeze({
    phase: 'installer-pointer-before-settings', finalIncarnation: 2, faultIncarnation: 1,
    requestedRestartIncarnations: Object.freeze([]), cutAttempts: 1, finalAttempts: 1,
  }),
  'installer-settings-before-publication': Object.freeze({
    phase: 'installer-settings-before-publication', finalIncarnation: 2, faultIncarnation: 1,
    requestedRestartIncarnations: Object.freeze([]), cutAttempts: 1, finalAttempts: 1,
  }),
  'installer-before-receipt': Object.freeze({
    phase: 'installer-before-receipt', finalIncarnation: 2, faultIncarnation: 1,
    requestedRestartIncarnations: Object.freeze([]), cutAttempts: 1, finalAttempts: 1,
  }),
});

const OPERATION_KEY = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SESSION_HEADER = 'X-JustSearch-Session';
const START_ROUTE = '/api/indexing/migration/start';
const ACCEPT_GAPS_ROUTE = '/api/indexing/migration/accept-gaps';
const CANCEL_REINDEX_ROUTE = '/api/operations/core.cancel-reindex/invoke';
const ACTIVATION_ROUTE = '/api/operations/core.activate-installed-models/invoke';

/** Installed standard-model proof that an unsuperseded gap waits on A for a distinct user decision. */
export async function exerciseBulkGapApproval(c) {
  const { work, data, indexBase, first, manifest, apiPort, readJson, waitFor, request,
    post, requireThat, matchingHit, createOperationKey, operationKey } = c;
  requireThat(OPERATION_KEY.test(operationKey) && first.incarnation === 1,
    'gap fixture needs a fresh recorded operation on its owned Engine');
  const { reachedFile, releaseFile } = barrierFiles(data);
  const operationPath = path.join(data, 'operations.db');
  const sourceGeneration = readJson(path.join(indexBase, 'state.json'))?.active_generation;
  const sourceManifest = readJson(path.join(indexBase, 'indices', sourceGeneration,
    '.justsearch-index-generation.json'));
  const servingFile = path.join(work, 'installer-root-a', 'installer-0.txt');
  requireThat(sourceManifest?.models?.embedding?.sha256 && fs.existsSync(servingFile),
    'gap fixture requires a previously installed standard-model A');
  const servingMarker = fs.readFileSync(servingFile, 'utf8').split(/\s+/)[0];
  await waitFor('installed A serves a real vector query before the gap build', 120000,
    async () => {
      try {
        const response = await post(apiPort, '/api/knowledge/search',
          { query: servingMarker, limit: 10, mode: 'vector' }, 30000);
        return response.status === 200 && JSON.parse(response.text).results?.length > 0
          ? response : null;
      } catch { return null; }
    });

  const firstFile = path.join(work, 'bulk-root-a', 'a.txt');
  const missingFile = path.join(work, 'bulk-root-b', 'b.txt');
  fs.writeFileSync(firstFile, `bulk-gap-kept-${operationKey} capybara\n`);
  fs.writeFileSync(missingFile, `bulk-gap-missing-${operationKey} capybara\n`);
  const input = { reason: 'manual', idempotencyKey: operationKey };
  const prepared = await prepareApprovedDispatch({ apiPort, input, post, requireThat });
  const dispatched = startHeldPost(apiPort, START_ROUTE, {
    ...input, confirmationToken: prepared.capsule, preparationNonce: prepared.nonce,
  }, sessionHeaders(manifest));
  let reached;
  try {
    reached = await waitFor('gap fixture closed capture before Green work', 170000,
      () => readJson(reachedFile));
    const cut = snapshot({ operationPath, jobsPath: path.join(data, 'jobs.db'),
      indexBase, operationKey });
    requireThat(reached.phase === 'bulk-before-building-checkpoint'
      && reached.operationKey === operationKey
      && cut.walk?.enumeration_outcome === 'COMPLETE'
      && cut.walk.planned_units === 2
      && cut.state.active_generation === sourceGeneration
      && cut.state.building_generation === `g-${operationKey}`,
    `gap fixture missed its captured pre-build cut: ${JSON.stringify({ reached, cut })}`);
    fs.unlinkSync(missingFile);
  } finally {
    fs.writeFileSync(releaseFile, 'release');
  }
  const dispatch = await dispatched.settled;
  requireThat(!dispatch.error,
    `gap fixture dispatch failed at transport: ${dispatch.error?.message}`);
  const waiting = await waitFor('unsuperseded gap waits without publishing B', 180000, async () => {
    const row = operationRows(operationPath, operationKey)[0];
    const state = readJson(path.join(indexBase, 'state.json'));
    if (row?.state === 'FAILED' || row?.state === 'CANCELLED') {
      throw new Error(`gap fixture terminated before user decision: ${row.failure_reason}`);
    }
    // The durable bulk phase remains settled. The outcome projector exposes the
    // nonterminal decision as wire phase awaiting_acceptance below.
    if (row?.state !== 'COMPLETE_WITH_GAPS' || row.phase !== 'settled'
      || state?.active_generation !== sourceGeneration
      || state?.building_generation !== `g-${operationKey}`
      || state?.migration_state !== 'AWAITING_ACCEPTANCE') return null;
    const response = await request(apiPort, `/api/operation-history/${operationKey}`, {}, 15000);
    if (response.status !== 200) return null;
    const outcome = parseJson(response, 'gap outcome');
    return outcome.phase === 'awaiting_acceptance'
      && outcome.state === 'running'
      && /^[0-9a-f]{64}$/.test(outcome.result?.gapListHash ?? '')
      && outcome.result?.gaps?.some(gap => gap.reason && gap.unitId)
      ? { row, state, outcome } : null;
  });
  const aText = await post(apiPort, '/api/knowledge/search',
    { query: servingMarker, limit: 10, mode: 'text' }, 30000);
  const aVector = await waitFor('serving A vector leg recovers during gap wait', 120000,
    async () => {
      try {
        const response = await post(apiPort, '/api/knowledge/search',
          { query: servingMarker, limit: 10, mode: 'vector' }, 30000);
        return response.status === 200 && JSON.parse(response.text).results?.length > 0
          ? response : null;
      } catch { return null; }
    });
  requireThat(aText.status === 200 && matchingHit(aText, servingFile, servingMarker)
    && aVector.status === 200 && JSON.parse(aVector.text).results?.length > 0,
  `serving A lost text or semantic search during gap wait: ${aText.text} ${aVector.text}`);

  const acceptanceKey = createOperationKey();
  const acceptanceInput = { reindexKey: operationKey,
    gapListHash: waiting.outcome.result.gapListHash, idempotencyKey: acceptanceKey };
  const initial = await request(apiPort, ACCEPT_GAPS_ROUTE, {
    method: 'POST', headers: sessionHeaders(manifest), body: JSON.stringify(acceptanceInput),
  }, 30000);
  const pending = parseJson(initial, 'gap acceptance preparation');
  requireThat(initial.status === 428 && pending.operationKey === acceptanceKey
    && typeof pending.pendingId === 'string' && typeof pending.preparationNonce === 'string',
  `gap acceptance lacked a distinct prepared HIGH/DURABLE decision: ${initial.text}`);
  const approved = await post(apiPort, '/api/authorizations/approve',
    { pendingId: pending.pendingId }, 30000);
  const capsule = parseJson(approved, 'gap acceptance approval').capsule;
  requireThat(approved.status === 200 && typeof capsule === 'string',
    `gap acceptance approval failed: ${approved.text}`);
  const accept = await request(apiPort, ACCEPT_GAPS_ROUTE, {
    method: 'POST', headers: sessionHeaders(manifest), body: JSON.stringify({
      ...acceptanceInput, confirmationToken: capsule,
      preparationNonce: pending.preparationNonce,
    }),
  }, 30000);
  requireThat(accept.status === 200 && parseJson(accept, 'accepted gaps').success === true,
    `authorized gap decision did not succeed: ${accept.text}`);
  const promoted = await waitFor('approved gap promotes the same candidate', 180000, () => {
    const row = operationRows(operationPath, operationKey)[0];
    const state = readJson(path.join(indexBase, 'state.json'));
    return row?.state === 'FAILED' && row.failure_reason === 'PROMOTED_WITH_GAPS'
      && state?.active_generation === `g-${operationKey}`
      && state?.migration_state === 'IDLE' ? { row, state } : null;
  });
  const duplicate = await request(apiPort, ACCEPT_GAPS_ROUTE, {
    method: 'POST', headers: sessionHeaders(manifest), body: JSON.stringify(acceptanceInput),
  }, 30000);
  requireThat(duplicate.status === 200 && operationRows(operationPath, acceptanceKey).length === 1
    && readJson(path.join(indexBase, 'state.json'))?.active_generation === `g-${operationKey}`,
  `duplicate acceptance changed the durable decision: ${duplicate.text}`);
  console.log('BULK_GAP_APPROVAL_PASS', JSON.stringify({ operationKey, acceptanceKey,
    sourceGeneration, promotedGeneration: promoted.state.active_generation,
    gapListHash: waiting.outcome.result.gapListHash,
    gaps: waiting.outcome.result.gaps, aVectorHits: JSON.parse(aVector.text).results.length,
    terminalState: promoted.row.state, terminalReason: promoted.row.failure_reason }));
}

/** Installed-process proof for the recorded bulk crash boundaries. */
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
  const { reachedFile, releaseFile } = barrierFiles(data, selected.liveStart
    ? 'migration-barrier' : 'operation-fault');
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
  let capturedReplay = null;
  const operationPath = path.join(data, 'operations.db');
  const jobsPath = path.join(data, 'jobs.db');
  const sourceGeneration = readJson(path.join(indexBase, 'state.json'))?.active_generation;
  requireThat(typeof sourceGeneration === 'string' && sourceGeneration.length > 0,
    'bulk fault fixture requires an authoritative pre-dispatch active generation');
  requireThat(operationRows(operationPath, operationKey).length === 0,
    'caller-selected bulk key must be unknown before dispatch');

  if (selected.liveStart) {
    // HTTP readiness precedes deferred model composition. Start only after A can serve a
    // real semantic request, otherwise the legitimate early-start fallback masks this cut.
    await waitFor('live-start source model ready before dispatch', 120000, async () => {
      try {
        const response = await post(apiPort, '/api/knowledge/search',
          { query: markers[0], limit: 10, mode: 'vector' }, 30000);
        return response.status === 200 ? response : null;
      } catch { return null; }
    });
  }

  const headers = sessionHeaders(manifest);
  const input = { reason: 'manual', idempotencyKey: operationKey };
  const prepared = await prepareApprovedDispatch({ apiPort, input, post, requireThat });
  const dispatched = startHeldPost(apiPort, START_ROUTE, {
    ...input, confirmationToken: prepared.capsule, preparationNonce: prepared.nonce,
  }, headers);

  const reached = await waitFor(`bulk fault marker ${scenario}`, 170000,
    () => readJson(reachedFile) ?? null);
  if (selected.liveStart) {
    requireThat(reached.point === selected.phase
      && reached.sourceGeneration === sourceGeneration
      && reached.buildingGeneration === `g-${operationKey}`,
    `live Green hook reached the wrong generation boundary: ${JSON.stringify(reached)}`);
  } else {
    requireThat(reached.phase === selected.phase && reached.parentKind === 'reindex'
      && reached.parentKey === operationKey && reached.operationKey === operationKey
      && Number.isSafeInteger(reached.operationRecordId) && reached.operationRecordId > 0,
    `bulk hook reached the wrong boundary: ${JSON.stringify(reached)}`);
  }

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
  if (selected.capturedEdit) {
    const captured = snapshot({ operationPath, jobsPath, indexBase, operationKey });
    const capturedMember = captured.jobs.find(member => samePath(member.path, files[0]));
    requireThat(captured.walk?.captured_plan === 1
        && captured.walk.enumeration_outcome === 'COMPLETE'
        && captured.walk.enumeration_closed_at != null
        && captured.walk.sealed_at == null
        && captured.walk.planned_units === files.length
        && capturedMember?.planned_source_sha256 === hashes[0]
        && capturedMember.unit_revision != null,
      `captured edit did not reach the exact closed H1 plan: ${JSON.stringify({
        walk: captured.walk, member: capturedMember, h1: hashes[0],
      })}`);
    const h2Marker = 'freshcapturedreplaycobalt';
    fs.writeFileSync(files[0], `${h2Marker} changed after capture during recovery\n`);
    const committed = sha256(fs.readFileSync(files[0]));
    requireThat(committed !== hashes[0],
      'captured edit did not change the source identity before the verified kill');
    capturedReplay = {
      file: files[0], unitRevision: capturedMember.unit_revision,
      h1: { marker: markers[0], sha256: hashes[0] },
      h2: { marker: h2Marker, sha256: committed },
    };
  }
  let cut;
  let cooldown;
  if (selected.liveRefusal) {
    cut = snapshot({ operationPath, jobsPath, indexBase, operationKey });
    fs.writeFileSync(releaseFile, 'release');
    cooldown = await waitFor('live refusal requests one free restart', 20000, () => {
      const state = readJson(path.join(runtime, 'supervisor.v1.json'));
      return state?.runId === first.runId && state.state === 'running'
        && state.incarnation === 2 && state.restartCount === 0
        && state.lastExit?.code === 4 && state.lastExit?.counted === false ? state : null;
    });
  } else {
    cooldown = await killOwnedEngineAndObserveCooldown({
      engine: faulted.supervisor, data, readJson, waitFor, requireThat,
    });
    cut = snapshot({ operationPath, jobsPath, indexBase, operationKey });
  }
  await dispatched.settled;
  const cutProof = { scenario, operationKey, reached, cooldown, cut };
  if (selected.capturedEdit) cutProof.capturedReplay = capturedReplay;
  fs.writeFileSync(path.join(work, 'bulk-cut.json'), JSON.stringify(cutProof, null, 2));
  assertCommonCut({ cut, reached, prepared, operationKey, selected, roots,
    sourceGeneration, requireThat });
  assertSelectedCut({ selected, cut, files, hashes, capturedReplay, operationKey, requireThat });

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
  requireThat(recovered.supervisor.restartCount === (selected.liveRefusal ? 0 : 1),
    `bulk boundary spent the wrong supervisor restart count: ${JSON.stringify(recovered.supervisor)}`);
  if (selected.requestedRestartIncarnations.at(-1) === expectedIncarnation - 1) {
    requireThat(recovered.supervisor.lastExit?.code === 4
      && recovered.supervisor.lastExit?.counted === false,
    `last boundary must be a free requested restart: ${JSON.stringify(recovered.supervisor)}`);
  } else {
    requireThat(recovered.supervisor.lastExit?.counted === true,
      `bulk crash must remain the last counted exit: ${JSON.stringify(recovered.supervisor)}`);
  }
  for (const incarnation of selected.requestedRestartIncarnations) {
    requireThat(c.output().includes(`Engine incarnation ${incarnation} exited 4 (requested_restart`),
      `incarnation ${incarnation} did not record its own requested restart`);
  }
  if (selected.requestedRestartIncarnations.length === 0) {
    requireThat(!c.output().includes('exited 4 (requested_restart'),
      'Flow A promotion unexpectedly requested a process restart');
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
  assertFinal({ final, cut, prepared, files, hashes, capturedReplay, operationKey, selected,
    requireThat });

  const searchEvidence = [];
  for (let index = 0; index < files.length; index++) {
    const marker = selected.capturedEdit && index === 0
      ? capturedReplay.h2.marker : markers[index];
    const search = await waitFor(`promoted target search ${index + 1}`, 60000, async () => {
      try {
        const response = await post(recovered.manifest.head.apiPort, '/api/knowledge/search', {
          query: marker, limit: 10, mode: 'text',
        });
        return response.status === 200 && matchingHit(response, files[index], marker)
          ? response : null;
      } catch { return null; }
    });
    const matching = JSON.parse(search.text).results.filter(hit =>
      samePath(hit?.fields?.path, files[index]));
    requireThat(matching.length === 1,
      `promoted target must contain exactly one hit for ${files[index]}: ${search.text}`);
    searchEvidence.push(selected.capturedEdit
      ? { path: files[index], marker, matches: matching.length }
      : { path: files[index], matches: matching.length });
  }
  if (selected.capturedEdit) {
    const stale = await post(recovered.manifest.head.apiPort, '/api/knowledge/search', {
      query: markers[0], limit: 10, mode: 'text',
    });
    requireThat(stale.status === 200 && !matchingHit(stale, files[0], markers[0]),
      `promoted target retained stale H1 content: ${stale.text}`);
    capturedReplay.staleH1Absent = true;
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
  if (selected.capturedEdit) {
    requireThat(sameJson(afterRetry.state, final.state)
        && sameJson(afterRetry.generationManifest, final.generationManifest)
        && sameJson(afterRetry.recordedGenerations, final.recordedGenerations),
      `same-key replay changed the promoted generation: ${JSON.stringify({
        before: { state: final.state, manifest: final.generationManifest,
          generations: final.recordedGenerations },
        after: { state: afterRetry.state, manifest: afterRetry.generationManifest,
          generations: afterRetry.recordedGenerations },
      })}`);
    const afterRetryLatest = await post(recovered.manifest.head.apiPort,
      '/api/knowledge/search', { query: capturedReplay.h2.marker, limit: 10, mode: 'text' });
    const afterRetryStale = await post(recovered.manifest.head.apiPort,
      '/api/knowledge/search', { query: capturedReplay.h1.marker, limit: 10, mode: 'text' });
    requireThat(afterRetryLatest.status === 200
        && matchingHit(afterRetryLatest, capturedReplay.file, capturedReplay.h2.marker)
        && afterRetryStale.status === 200
        && !matchingHit(afterRetryStale, capturedReplay.file, capturedReplay.h1.marker),
      `same-key replay changed captured H2 search: ${afterRetryLatest.text} ${afterRetryStale.text}`);
  }

  const servingAfterResult = readJson(path.join(runtime, 'supervisor.v1.json'));
  requireThat(servingAfterResult?.state === 'running'
    && servingAfterResult.incarnation === expectedIncarnation
    && servingAfterResult.instanceId === recovered.supervisor.instanceId
    && servingAfterResult.restartCount === (selected.liveRefusal ? 0 : 1)
    && !c.output().includes(`Engine incarnation ${expectedIncarnation} exited 4 (requested_restart`),
  `Flow A promoted Green through the live process without a promotion restart: ${JSON.stringify(servingAfterResult)}`);

  console.log('BULK_FAULT_PASS', JSON.stringify({
    scenario, operationKey, phase: selected.phase,
    faulted: compactSupervisor(faulted.supervisor), cooldown: compactSupervisor(cooldown),
    recovered: compactSupervisor(recovered.supervisor), operation: {
      id: final.operation.id, operation_key: final.operation.operation_key,
      state: final.operation.state, phase: final.operation.phase,
      attempts: final.operation.attempts, units_failed: final.operation.units_failed,
    },
    walk: final.walk, members: final.jobs.map(compactMember), search: searchEvidence,
    ...(selected.capturedEdit ? { capturedReplay } : {}),
    preparationSha256: sha256(Buffer.from(final.operation.preparation_payload, 'utf8')),
  }));
  return { reached, cooldown, recovered, operation: final.operation, walk: final.walk,
    members: final.jobs, search: searchEvidence };
}

/** Installed-process proof for activation of a retained installer candidate. */
export async function exerciseInstallerActivationFault(c) {
  const { work, data, indexBase, first, manifest, apiPort, readJson, post, candidate,
    requireThat, requireOperationSuccess, matchingHit, scenario, operationKey } = c;
  const selected = INSTALLER_FAULT_CASES[scenario];
  requireThat(selected, `unknown installer activation fault scenario: ${scenario}`);
  requireThat(OPERATION_KEY.test(operationKey),
    `installer activation fault key must be a caller-selected UUIDv7: ${operationKey}`);
  requireThat(first.pid === manifest.pid && first.instanceId === manifest.instanceId,
    'fixture may fault only an admitted Engine it launched');
  requireThat(first.incarnation === 1,
    `installer activation fixture must begin at incarnation 1: ${JSON.stringify(first)}`);

  // Cold CPU model composition reached this exact hook at 180s on the installed
  // standard profile. Leave time for the subsequent restart and reconciliation;
  // Stage E owns the separate runtime latency threshold.
  const deadline = Date.now() + 480000;
  const waitFor = (label, budget, probe) =>
    c.waitFor(label, Math.max(1, Math.min(budget, deadline - Date.now())), probe);
  const runtime = path.join(data, 'runtime');
  const { reachedFile, releaseFile } = barrierFiles(data);
  requireThat(!fs.existsSync(reachedFile) && !fs.existsSync(releaseFile),
    'installer activation fixture must start without a reached or release marker');

  const registry = readJson(path.join(data, 'watched_roots.json'));
  const roots = (registry?.roots ?? []).map(root => root.path);
  requireThat(registry?.schemaVersion === 1 && roots.length > 0
    && roots.every(root => typeof root === 'string' && fs.statSync(root).isDirectory()),
  `installer activation requires prebooted watched roots: ${JSON.stringify(registry)}`);
  const files = roots.slice(0, 2).map((root, index) => path.join(root, `installer-${index}.txt`));
  const markers = files.map((_, index) =>
    `installeractivation${scenario.replaceAll('-', '')}${index === 0 ? 'alpha' : 'bravo'}`);
  files.forEach((file, index) => fs.writeFileSync(file,
    `${markers[index]} durable installer activation recovery quokka\n`));
  const hashes = files.map(file => sha256(fs.readFileSync(file)));
  const operationPath = path.join(data, 'operations.db');
  const jobsPath = path.join(data, 'jobs.db');
  const sourceGeneration = readJson(path.join(indexBase, 'state.json'))?.active_generation;
  requireThat(typeof sourceGeneration === 'string' && sourceGeneration.length > 0,
    'installer activation requires an authoritative pre-dispatch active generation');
  requireThat(operationRows(operationPath, operationKey).length === 0,
    'caller-selected installer activation key must be unknown before dispatch');

  const beforeSettings = settingsWitnessOnDisk(data);
  const initialAiManifest = await waitFor('initial installer AI phase published', 30000, () => {
    const current = readJson(path.join(runtime, 'manifest.json'));
    return current?.instanceId === first.instanceId && typeof current.ai?.phase === 'string'
      ? current : null;
  });
  requireThat(beforeSettings.witness.acceptedRevision === 0
    && beforeSettings.witness.lastCommittedOperationKey == null
    && !beforeSettings.settings?.embedOnnxModelPath
    && !beforeSettings.settings?.nerModelPath
    && !beforeSettings.settings?.spladeModelPath
    && initialAiManifest.ai.phase !== 'READY',
  `installer activation must start without a prior READY model or committed model settings: ${JSON.stringify({
    witness: beforeSettings.witness, ai: initialAiManifest.ai,
  })}`);
  const headers = sessionHeaders(manifest);
  const input = { source: 'installer_model_activation' };
  const prepared = await prepareApprovedActivationDispatch({ apiPort, input, operationKey,
    post, requireThat });
  const dispatched = startHeldPost(apiPort, ACTIVATION_ROUTE, {
    args: input, idempotencyKey: operationKey,
    confirmationToken: prepared.capsule, preparationNonce: prepared.nonce,
  }, headers);

  const reached = await waitFor(`installer activation fault marker ${scenario}`, 240000,
    () => readJson(reachedFile) ?? null);
  requireThat(reached.phase === selected.phase && reached.parentKind === 'reindex'
    && reached.parentKey === operationKey && reached.operationKey === operationKey
    && Number.isSafeInteger(reached.operationRecordId) && reached.operationRecordId > 0,
  `installer activation hook reached the wrong boundary: ${JSON.stringify(reached)}`);

  // Before-receipt must crash with no terminal row. A same-key request here can
  // legitimately reconcile the committed pointer and settle that row, erasing
  // the intended fault cut. The other cuts exercise in-flight deduplication;
  // the final same-key replay below covers this cut after recovery.
  if (selected.phase !== 'installer-before-receipt') {
    const duplicate = await post(apiPort, ACTIVATION_ROUTE,
      { args: input, idempotencyKey: operationKey }, 10000);
    const duplicateReceipt = requireOperationSuccess(duplicate,
      'in-flight installer activation duplicate');
    requireThat(duplicateReceipt.operationKey === operationKey
      && duplicateReceipt.operationRecordId === reached.operationRecordId
      && duplicateReceipt.body.structuredData.state === 'RUNNING'
      && operationRows(operationPath, operationKey).length === 1,
    `in-flight duplicate changed accepted activation identity: ${duplicate.text}`);
  }

  let faulted;
  const cooldown = selected.phase === 'installer-before-receipt'
    ? await waitFor('self-halted installer Engine and counted restart cooldown', 12000, () => {
      const supervisor = readJson(path.join(runtime, 'supervisor.v1.json'));
      return supervisor?.state === 'restarting' && supervisor.pid === reached.pid
        && supervisor.runId === first.runId
        && supervisor.incarnation === selected.faultIncarnation
        && supervisor.restartCount === 1 && supervisor.lastExit?.counted === true
        ? supervisor : null;
    })
    : await (async () => {
      faulted = await waitFor('admitted Engine that emitted the installer activation marker', 10000, () => {
        const supervisor = readJson(path.join(runtime, 'supervisor.v1.json'));
        const currentManifest = readJson(path.join(runtime, 'manifest.json'));
        return supervisor?.state === 'running' && supervisor.pid === reached.pid
          && supervisor.runId === first.runId
          && supervisor.incarnation === selected.faultIncarnation
          && currentManifest?.pid === supervisor.pid
          && currentManifest.instanceId === supervisor.instanceId
          ? { supervisor, manifest: currentManifest } : null;
      });
      return killOwnedEngineAndObserveCooldown({
        engine: faulted.supervisor, data, readJson, waitFor, requireThat,
      });
    })();
  await dispatched.settled;

  const cut = snapshot({ operationPath, jobsPath, indexBase, operationKey });
  cut.settings = settingsWitnessOnDisk(data);
  fs.writeFileSync(path.join(work, 'installer-cut.json'), JSON.stringify({
    scenario, operationKey, reached, cooldown, candidate, cut,
  }, null, 2));
  assertInstallerCut({ cut, reached, prepared, operationKey, selected, sourceGeneration,
    roots, beforeSettings, candidate, requireThat });
  assertInstallerSelectedCut({ selected, cut, operationKey, requireThat });

  const expectedIncarnation = first.incarnation + selected.finalIncarnation - 1;
  const recovered = await waitFor('installer activation successor reaches its final physical incarnation', 180000, () => {
    const operation = operationRows(operationPath, operationKey)[0];
    if (operation?.state === 'FAILED' || operation?.state === 'CANCELLED') {
      throw new Error(`installer activation recovery terminated before final incarnation: ${operation.state} ${operation.failure_reason}`);
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
    `installer activation crash must spend exactly one supervisor restart: ${JSON.stringify(recovered.supervisor)}`);
  for (const incarnation of selected.requestedRestartIncarnations) {
    requireThat(c.output().includes(`Engine incarnation ${incarnation} exited 4 (requested_restart`),
      `incarnation ${incarnation} did not record its own requested restart`);
  }

  const final = await waitFor('installer activation terminal success and exact settings witness', 90000, () => {
    const observed = snapshot({ operationPath, jobsPath, indexBase, operationKey });
    observed.settings = settingsWitnessOnDisk(data);
    return observed.operations.length === 1
      && observed.operation?.state === 'COMPLETE'
      && observed.operation.phase === 'settled'
      && observed.walk?.sealed_at != null
      && observed.walk.acknowledged_revision === observed.walk.revision
      ? observed : null;
  });
  fs.writeFileSync(path.join(work, 'installer-final.json'), JSON.stringify(final, null, 2));
  assertInstallerFinal({ final, cut, prepared, files, hashes, operationKey, selected,
    sourceGeneration, beforeSettings, candidate, requireThat });

  const searchEvidence = [];
  for (let index = 0; index < files.length; index++) {
    const search = await waitFor(`installer promoted target search ${index + 1}`, 60000, async () => {
      try {
        const response = await post(recovered.manifest.head.apiPort, '/api/knowledge/search', {
          query: markers[index], limit: 10, mode: 'text',
        });
        return response.status === 200 && matchingHit(response, files[index], markers[index])
          ? response : null;
      } catch { return null; }
    });
    const matching = JSON.parse(search.text).results.filter(hit => samePath(hit?.fields?.path, files[index]));
    requireThat(matching.length === 1,
      `installer target must contain exactly one hit for ${files[index]}: ${search.text}`);
    searchEvidence.push({ path: files[index], matches: matching.length });
  }

  const stableOperation = final.operation;
  const retry = await preparedActivationPost({ apiPort: recovered.manifest.head.apiPort, input,
    operationKey, post, requireThat });
  const retryReceipt = requireOperationSuccess(retry, 'installer activation same-key replay');
  requireThat(retryReceipt.operationKey === operationKey
    && retryReceipt.operationRecordId === stableOperation.id
    && retryReceipt.body.structuredData.state === 'COMPLETE',
  `same-key replay changed installer activation identity: ${retry.text}`);
  const afterRetry = snapshot({ operationPath, jobsPath, indexBase, operationKey });
  afterRetry.settings = settingsWitnessOnDisk(data);
  requireThat(sameJson(afterRetry.operation, stableOperation)
    && sameJson(afterRetry.settings, final.settings),
  'same-key replay changed the activation row or settings witness');

  console.log('INSTALLER_ACTIVATION_FAULT_PASS', JSON.stringify({
    scenario, operationKey, phase: selected.phase,
    faulted: faulted ? compactSupervisor(faulted.supervisor)
      : { pid: reached.pid, incarnation: selected.faultIncarnation },
    cooldown: compactSupervisor(cooldown),
    recovered: compactSupervisor(recovered.supervisor), operation: {
      id: final.operation.id, operation_key: final.operation.operation_key,
      state: final.operation.state, phase: final.operation.phase,
      attempts: final.operation.attempts,
    }, pointer: final.state?.active_generation, settings: final.settings,
    search: searchEvidence,
    preparationSha256: sha256(Buffer.from(final.operation.preparation_payload, 'utf8')),
  }));
  return { reached, cooldown, recovered, operation: final.operation,
    settings: final.settings, search: searchEvidence };
}

/** Keep the installed A root intact while activating a second, separately owned model root. */
export async function exerciseLiveModelAB({ work, data, indexBase, first, manifest, apiPort,
  operationKey, readJson, waitFor, request, post, requireThat, createOperationKey, matchingHit,
  distinctModelB = false, inPlaceModelB = false, acceptedWriteDuringBuild = false,
  watcherDeleteDuringBuild = false, gapApproval = false, gapCancellation = false,
  gapRecomposeFailure = false, cancelBeforePointer = false,
  issuedSearch = false, extraBuildFiles = 0, engineLogWindow = null, crashBoundary = null,
  bootRootChanges = false }) {
  const runtime = path.join(data, 'runtime');
  const { reachedFile, releaseFile } = barrierFiles(data);
  const migrationBarrier = barrierFiles(data, 'migration-barrier');
  const issuedBarrier = barrierFiles(data, 'issued-a-search');
  const operationPath = path.join(data, 'operations.db');
  const jobsPath = path.join(data, 'jobs.db');
  const sourceGeneration = readJson(path.join(indexBase, 'state.json'))?.active_generation;
  const bGeneration = `g-${operationKey}`;
  const sourceManifest = readJson(path.join(indexBase, 'indices', sourceGeneration,
    '.justsearch-index-generation.json'));
  const oldContract = readJson(path.join(data, 'install-contract.v2.json'));
  requireThat(sourceManifest?.models?.embedding?.id && oldContract?.modelsDir
    && sourceManifest.models.embedding.id.startsWith(path.resolve(work))
    && sourceManifest.models['citation-scorer'],
  'side-by-side model fixture requires a private installed serving A');
  const installedACitation = citationIdentityFromManifest(
    sourceManifest, 'installed serving A', requireThat);
  requireThat(installedACitation.modelPath.startsWith(
    path.resolve(work, 'installer-models') + path.sep),
  `installed A citation model escaped its private root: ${installedACitation.modelPath}`);
  requireThat(operationRows(operationPath, operationKey).length === 0,
    'side-by-side activation key is already present');
  if (cancelBeforePointer) requireThat(acceptedWriteDuringBuild && distinctModelB && inPlaceModelB,
    'accepted-write cancellation requires a distinct forced in-place candidate');
  if (crashBoundary) requireThat(acceptedWriteDuringBuild && distinctModelB && inPlaceModelB
      && ['installer-before-pointer', 'installer-pointer-before-settings'].includes(crashBoundary),
    `combined maintenance requires a supported forced in-place crash boundary: ${crashBoundary}`);
  if (bootRootChanges) requireThat(crashBoundary === 'installer-pointer-before-settings',
    'committed boot root changes require the recorded post-pointer crash cut');
  const file = path.join(work, 'installer-root-a', 'installer-0.txt');
  const marker = fs.readFileSync(file, 'utf8').split(/\s+/)[0];
  const removedFile = watcherDeleteDuringBuild
    ? path.join(work, 'installer-root-b', `watcher-delete-during-b-${operationKey}.txt`) : null;
  const removedMarker = removedFile
    ? 'watcherremovalcoral' : null;
  const sourceVector = await waitFor('installed A serves its exact vector document before B',
    120000, async () => {
    try {
      const response = await post(apiPort, '/api/knowledge/search',
        { query: marker, limit: 10, mode: 'vector' }, 30000);
      return response.status === 200 && matchingHit(response, file, marker)
        ? response : null;
    } catch { return null; }
  });
  console.log('MODEL_LIVE_AB_SOURCE_VECTOR', JSON.stringify({
    sourceGeneration, path: file,
    hits: JSON.parse(sourceVector.text).results.length,
  }));
  if (gapApproval || gapRecomposeFailure) {
    await waitFor('installed A has an active CPU citation scorer', 30000,
      () => readActiveCitation(apiPort, request));
  }
  if (engineLogWindow) readEngineLogWindow(engineLogWindow, requireThat);
  const cudaABefore = gapApproval || gapRecomposeFailure
    ? await waitFor('installed A realizes CUDA before migration', 60000,
      () => readRealizedCudaEmbedding(apiPort, request))
    : null;
  // Add build load only after A has served a real vector result. These files hold
  // MIGRATING long enough for the native watcher edges and explicit pause.
  for (let i = 0; i < extraBuildFiles; i++) {
    fs.writeFileSync(path.join(work, 'installer-root-a', `build-load-${i}.txt`),
      `buildloadmarker${i} capybara\n`);
  }
  const bRoot = path.join(work, 'installer-models-b', operationKey);
  const candidateContract = structuredClone(oldContract);
  const shippedRegistry = distinctModelB ? readJson(path.join(process.cwd(),
    'modules', 'configuration', 'src', 'main', 'resources', 'ai', 'model-registry.v2.json'))
    : null;
  const retainedRoot = distinctModelB ? findRetainedModelsRoot() : null;
  for (const [packageId, installed] of Object.entries(candidateContract.models)) {
    if (installed.skipped) continue;
    const source = path.join(oldContract.modelsDir, installed.targetDir);
    const target = path.join(bRoot, installed.targetDir);
    requireThat(path.resolve(source).startsWith(path.resolve(oldContract.modelsDir))
      && path.resolve(target).startsWith(path.resolve(bRoot)),
    'installed model target escaped its owned root');
    linkRegularFiles(source, target);
    if (distinctModelB) {
      const pkg = shippedRegistry?.packages.find(item => item.id === packageId);
      const requiredTargetEP = packageId === 'citation-scorer' ? 'CPU' : 'CUDA';
      const variant = pkg?.variants.find(item => item.targetEP === requiredTargetEP);
      requireThat(variant && retainedRoot,
        `no retained ${requiredTargetEP} variant for installed package ${packageId}`);
      const alternate = path.join(retainedRoot, pkg.targetDir, variant.filename);
      const selected = path.join(target, variant.filename);
      if (!fs.existsSync(selected)) fs.linkSync(alternate, selected);
      requireThat(fs.statSync(selected).size === variant.sizeBytes
        && sha256(fs.readFileSync(selected)) === variant.sha256.toLowerCase(),
      `retained ${requiredTargetEP} bytes differ from shipped registry for ${packageId}`);
      installed.variantFilename = variant.filename;
      installed.precision = variant.precision;
      installed.targetEP = variant.targetEP;
      installed.sha256 = variant.sha256;
      installed.installedFiles = [variant.filename,
        ...pkg.supportingFiles.filter(file => file.required !== false).map(file => file.filename)];
    }
  }
  if (distinctModelB) {
    candidateContract.downloadProfile = 'GPU_FULL';
    candidateContract.hardwareProfile = {
      gpuDetected: true, cudaFunctional: true, vramBytes: 12 * 1024 * 1024 * 1024,
    };
  }
  fs.writeFileSync(path.join(data, 'install-contract.v2.json'), `${JSON.stringify({
    ...candidateContract, modelsDir: bRoot, installedAtEpochMs: Date.now(),
  }, null, 2)}\n`);
  const beforeSettings = settingsWitnessOnDisk(data);
  const input = { source: 'installer_model_activation' };
  const prepared = await prepareApprovedActivationDispatch({ apiPort, input, operationKey,
    post, requireThat, allowPreflightApproval: true });
  const dispatched = startHeldPost(apiPort, ACTIVATION_ROUTE, {
    args: input, idempotencyKey: operationKey,
    confirmationToken: prepared.capsule, preparationNonce: prepared.nonce,
  }, sessionHeaders(manifest));
  if (gapApproval || gapCancellation || gapRecomposeFailure) {
    requireThat(distinctModelB && inPlaceModelB,
      'installed model gap proof requires a distinct forced in-place candidate');
    // The deliberate A-recompose-failure case enters UNAVAILABLE; it is a different
    // acceptance path from the ordinary RELOADING refusal window.
    const semanticSampler = gapRecomposeFailure ? null
      : sampleSemanticAvailability({ apiPort, post, request, marker, file, matchingHit });
    try {
      await exerciseLiveModelGapDecision({ work, data, indexBase, manifest, apiPort,
        operationKey, sourceGeneration, sourceManifest, marker, file, bRoot, dispatched,
        readJson, waitFor, request, post, requireThat, createOperationKey, matchingHit,
        reachedFile, releaseFile, migrationBarrier, operationPath, gapCancellation,
        gapRecomposeFailure, semanticSampler, cudaABefore, engineLogWindow });
    } finally {
      if (process.env.JUSTSEARCH_RESTORED_A_NATIVE_LEASE_PROBE === '1') {
        fs.writeFileSync(path.join(data, 'runtime', 'restored-a-native-lease-release'), 'release');
      }
      await semanticSampler?.stop();
    }
    return;
  }
  const transitionSampler = distinctModelB && !crashBoundary
    ? sampleSemanticAvailability({ apiPort, post, request, marker, file, matchingHit }) : null;
  // The promotion and cancellation paths both leave through this scope.
  try {
  let cancellationKey;
  let issuedARequest;
  let issuedAReached;
  let watcherPassEvidence;
  let acceptedWriteEvidence;
  let watcherSemanticEvidence;
  try {
    const acceptedFile = path.join(work, 'installer-root-a', `accepted-during-b-${operationKey}.txt`);
    const acceptedMarker = 'lexicalbridgecobalt';
    const changedCapturedMarker = crashBoundary ? 'latestcapturedcobalt' : null;
    let capturedSourceHashes = null;
    let combinedFloor = null;
    if (acceptedWriteDuringBuild) {
      const held = await waitFor('migration monitor held before SWITCHING', 300000,
        () => readJson(migrationBarrier.reachedFile));
      const state = readJson(path.join(indexBase, 'state.json'));
      const live = readJson(path.join(runtime, 'manifest.json'));
      requireThat(held.point === 'migration-before-switching'
        && held.sourceGeneration === sourceGeneration
        && held.buildingGeneration === `g-${operationKey}`
        && held.pid === live?.pid && state?.migration_state === 'MIGRATING'
        && state?.migration_paused !== true,
      `migration barrier did not hold the expected live candidate: ${JSON.stringify({ held, state, live })}`);
      console.log('MODEL_LIVE_AB_TRANSITION_HELD', JSON.stringify(held));
    }
    if (acceptedWriteDuringBuild) {
      await waitFor('MIGRATING B with a live A producer in the same Engine', 120000, async () => {
        const state = readJson(path.join(indexBase, 'state.json'));
        const row = operationRows(operationPath, operationKey)[0];
        const successor = readJson(path.join(runtime, 'manifest.json'));
        const supervisor = readJson(path.join(runtime, 'supervisor.v1.json'));
        if (state?.migration_state !== 'MIGRATING'
          || state?.building_generation !== `g-${operationKey}`
          || row?.phase === 'settled'
          || successor?.instanceId !== manifest.instanceId
          || supervisor?.instanceId !== manifest.instanceId
          || supervisor.restartCount !== 0) return null;
        try {
          const health = await request(apiPort, '/api/health', {}, 10000);
          return health.status === 200 ? state : null;
        } catch { return null; }
      });
      if (crashBoundary) {
        const plannedHash = sha256(fs.readFileSync(file));
        const capturedBeforeEdit = snapshot({ operationPath, jobsPath, indexBase, operationKey });
        const capturedMember = capturedBeforeEdit.jobs.find(member => samePath(member.path, file));
        requireThat(capturedBeforeEdit.walk?.captured_plan === 1
            && capturedBeforeEdit.walk.enumeration_outcome === 'COMPLETE'
            && capturedMember?.planned_source_sha256 === plannedHash,
          `captured edit did not start from the exact H1 plan: ${JSON.stringify({
            walk: capturedBeforeEdit.walk, capturedMember, plannedHash,
          })}`);
        fs.writeFileSync(file, `${changedCapturedMarker} changed after capture during B\n`);
        capturedSourceHashes = { planned: plannedHash, committed: sha256(fs.readFileSync(file)) };
        requireThat(capturedSourceHashes.committed !== capturedSourceHashes.planned,
          'captured edit did not change source identity');
        await waitFor('MIGRATING watcher edit visible in serving A', 90000, async () => {
          const state = readJson(path.join(indexBase, 'state.json'));
          if (state?.active_generation !== sourceGeneration
              || state.migration_state !== 'MIGRATING') return null;
          try {
            const search = await post(apiPort, '/api/knowledge/search',
              { query: changedCapturedMarker, limit: 10, mode: 'text' }, 30000);
            return search.status === 200 && matchingHit(search, file, changedCapturedMarker)
              ? search : null;
          } catch { return null; }
        });
        const staleInA = await post(apiPort, '/api/knowledge/search',
          { query: marker, limit: 10, mode: 'text' }, 30000);
        requireThat(staleInA.status === 200 && !matchingHit(staleInA, file, marker),
          `serving A retained stale captured content after watcher edit: ${staleInA.text}`);
      }
      if (watcherDeleteDuringBuild) {
        fs.writeFileSync(removedFile, `${removedMarker} accepted by watcher during B\n`);
        // The installed migration barrier expires at 180s. Fail on the missing A
        // observation while it is still held, so the fixture cannot drift into
        // SWITCHING and mistake barrier timeout for watcher replay evidence.
        const additionInA = await waitFor('MIGRATING watcher addition visible in serving A', 120000, async () => {
          const state = readJson(path.join(indexBase, 'state.json'));
          if (state?.migration_state !== 'MIGRATING') return null;
          try {
            const search = await post(apiPort, '/api/knowledge/search',
              { query: removedMarker, limit: 10, mode: 'text' }, 30000);
            return search.status === 200 && matchingHit(search, removedFile, removedMarker)
              ? search : null;
          } catch { return null; }
        });
        const journalKey = `path:${removedFile.toLowerCase()}`;
        const journalSql = `SELECT generation, op FROM switch_buffer
          WHERE generation = 'g-${operationKey}' AND key = ?`;
        const upsertRows = readRows(jobsPath, journalSql, journalKey);
        const upsertWitness = upsertRows.find(row => row.op === 'UPSERT');
        requireThat(upsertWitness,
          'watcher addition reached A without a candidate-scoped replay obligation');
        const atDelete = readJson(path.join(indexBase, 'state.json'));
        requireThat(atDelete?.migration_state === 'MIGRATING'
          && atDelete.building_generation === `g-${operationKey}`,
        `watcher deletion missed MIGRATING admission: ${JSON.stringify(atDelete)}`);
        fs.unlinkSync(removedFile);
        console.log('MODEL_LIVE_AB_WATCHER_DELETE_SUBMITTED', JSON.stringify({
          sourceGeneration, buildingGeneration: `g-${operationKey}`,
          submittedAt: Date.now(), stateUpdatedAt: atDelete.updated_at_ms, path: removedFile,
        }));
        const deletionAbsentInA = await waitFor('MIGRATING watcher deletion absent from serving A', 120000, async () => {
          const state = readJson(path.join(indexBase, 'state.json'));
          if (state?.active_generation !== sourceGeneration
            || !['MIGRATING', 'SWITCHING'].includes(state?.migration_state)) return null;
          try {
            const search = await post(apiPort, '/api/knowledge/search',
              { query: removedMarker, limit: 10, mode: 'text' }, 30000);
            return search.status === 200 && !matchingHit(search, removedFile, removedMarker)
              ? search : null;
          } catch { return null; }
        });
        console.log('MODEL_LIVE_AB_WATCHER_DELETE_A', JSON.stringify({
          sourceGeneration, buildingGeneration: `g-${operationKey}`, path: removedFile,
        }));
        const deleteRows = readRows(jobsPath, journalSql, journalKey);
        const deleteWitness = deleteRows.find(row => row.op === 'DELETE');
        requireThat(deleteWitness,
          'watcher deletion reached A without a candidate-scoped delete obligation');
        watcherPassEvidence = {
          path: removedFile,
          marker: removedMarker,
          additionVisibleInA: true,
          additionHitsInA: JSON.parse(additionInA.text).results?.length ?? 0,
          scopedUpsert: { generation: upsertWitness.generation, key: journalKey,
            op: upsertWitness.op },
          deletionAbsentInA: true,
          deletionHitsInA: JSON.parse(deletionAbsentInA.text).results?.length ?? 0,
          scopedDelete: { generation: deleteWitness.generation, key: journalKey,
            op: deleteWitness.op },
        };
      }
      fs.writeFileSync(acceptedFile, `${acceptedMarker} capybara\n`);
      const ingestKey = createOperationKey();
      await waitFor('recorded ingest accepted while B builds', 90000, async () => {
        const state = readJson(path.join(indexBase, 'state.json'));
        if (state?.migration_state !== 'MIGRATING') return null;
        try {
          const reply = await post(apiPort, '/api/knowledge/ingest', {
            paths: [acceptedFile], idempotencyKey: ingestKey,
          }, 30000);
          const body = JSON.parse(reply.text);
          if (reply.status === 200 && body.success === true) return reply;
          if (reply.status === 200 && body.message?.includes('Serving generation authority is unavailable')) {
            return null;
          }
          if (body.retrySafe === true && body.errorCode === 'UPGRADE_PREPARING') return null;
          throw new Error(`write during B build was not accepted: ${reply.text}`);
        } catch (error) {
          if (error.message?.startsWith('write during B build was not accepted:')) throw error;
          return null;
        }
      });
      const visibleInA = await waitFor('MIGRATING-accepted write text-visible in A before B publication',
        90000, async () => {
        const state = readJson(path.join(indexBase, 'state.json'));
        const acceptedRow = operationRows(operationPath, ingestKey)[0];
        requireThat(!['FAILED', 'CANCELLED'].includes(acceptedRow?.state),
          `recorded ingest did not complete: ${acceptedRow?.state} ${acceptedRow?.failure_reason}`);
        if (state?.active_generation !== sourceGeneration
          || !['MIGRATING', 'SWITCHING'].includes(state?.migration_state)
          || acceptedRow?.state !== 'COMPLETE'
          || (state.migration_state === 'SWITCHING'
            && acceptedRow.accepted_at >= state.updated_at_ms)) return null;
        const search = await post(apiPort, '/api/knowledge/search',
          { query: acceptedMarker, limit: 10, mode: 'text' }, 30000);
        return search.status === 200 && matchingHit(search, acceptedFile, acceptedMarker)
          ? search : null;
      });
      const acceptedRow = operationRows(operationPath, ingestKey)[0];
      console.log('MODEL_LIVE_AB_ACCEPTED_WRITE', JSON.stringify({
        operationKey: ingestKey, acceptedAt: acceptedRow.accepted_at,
        completedAt: acceptedRow.completed_at, sourceGeneration,
        buildingGeneration: `g-${operationKey}`, path: acceptedFile,
        aTextHits: JSON.parse(visibleInA.text).results?.length ?? 0,
      }));
      acceptedWriteEvidence = {
        operationKey: ingestKey, acceptedAt: acceptedRow.accepted_at,
        completedAt: acceptedRow.completed_at, sourceGeneration,
        buildingGeneration: `g-${operationKey}`, path: acceptedFile,
        marker: acceptedMarker,
        duringBuild: true,
        visibleInA: true,
        aTextHits: JSON.parse(visibleInA.text).results?.length ?? 0,
      };
    }
    if (watcherDeleteDuringBuild) {
      // The watcher probe can spend long enough exercising add/delete reconciliation that B
      // reaches SWITCHING immediately after this barrier is released. Prove the semantic pause
      // against the barrier's exact MIGRATING phase instead of attributing a later transition
      // phase's readiness to MIGRATING.
      const held = readJson(migrationBarrier.reachedFile);
      const heldState = readJson(path.join(indexBase, 'state.json'));
      const live = readJson(path.join(runtime, 'manifest.json'));
      const statusReply = await request(apiPort, '/api/status', {}, 15000);
      const heldStatus = statusReply.status === 200 ? JSON.parse(statusReply.text) : null;
      const retrieval = heldStatus?.readiness?.composites?.retrieval;
      const composeMode = heldStatus?.readiness?.engineComponents?.encoders?.mode;
      requireThat(held?.point === 'migration-before-switching'
          && held.sourceGeneration === sourceGeneration
          && held.buildingGeneration === `g-${operationKey}`
          && held.pid === live?.pid
          && heldState?.migration_state === 'MIGRATING'
          && heldStatus?.worker?.migration?.migrationState === 'MIGRATING'
          && composeMode === 'IN_PLACE'
          && heldStatus?.components?.encoders?.state === 'RELOADING'
          && retrieval?.state === 'DEGRADED'
          && retrieval.reasonCodes?.includes('encoders.reloading'),
        `watcher semantic pause was absent at the held MIGRATING phase: ${JSON.stringify({
          held, heldState, live, migration: heldStatus?.worker?.migration,
          compatibility: heldStatus?.worker?.compatibility, composeMode,
          encoders: heldStatus?.components?.encoders, retrieval,
        })}`);
      const vectorSearch = await post(apiPort, '/api/knowledge/search',
        { query: marker, limit: 10, mode: 'vector' }, 30000);
      const hybridSearch = await post(apiPort, '/api/knowledge/search',
        // AUTO may legitimately choose sparse-only for this query. Request the HYBRID preset
        // to exercise its dense-leg refusal and sparse fallback at this held phase.
        { query: marker, limit: 10, mode: 'hybrid' }, 30000);
      const vectorBody = vectorSearch.status === 200 ? JSON.parse(vectorSearch.text) : null;
      const hybridBody = hybridSearch.status === 200 ? JSON.parse(hybridSearch.text) : null;
      const vectorTrace = vectorBody?.searchTrace?.degradation ?? null;
      const hybridTrace = hybridBody?.searchTrace?.degradation ?? null;
      const afterQueriesState = readJson(path.join(indexBase, 'state.json'));
      const afterQueriesLive = readJson(path.join(runtime, 'manifest.json'));
      requireThat(vectorSearch.status === 200
          && vectorBody?.results?.length === 0
          && vectorTrace?.vectorBlocked === true
          && vectorTrace.vectorBlockedReason === 'REBUILD_IN_PROGRESS'
          && hybridSearch.status === 200
          && matchingHit(hybridSearch, file, marker)
          && hybridTrace?.vectorBlocked === true
          && hybridTrace.vectorBlockedReason === 'REBUILD_IN_PROGRESS'
          && afterQueriesState?.migration_state === 'MIGRATING'
          && afterQueriesLive?.pid === held.pid,
        `watcher semantic pause lacked its held vector/hybrid trace: ${JSON.stringify({
          vector: vectorSearch.text, hybrid: hybridSearch.text,
          afterQueriesState, afterQueriesLive,
        })}`);
      watcherSemanticEvidence = {
        point: held.point, pid: held.pid, migration: heldState.migration_state,
        compatibility: heldStatus?.worker?.compatibility?.embeddingCompatState ?? null,
        composeMode, encoderState: heldStatus.components.encoders.state,
        retrievalReasons: retrieval.reasonCodes,
        vector: { hits: vectorBody.results.length, degradation: vectorTrace },
        hybrid: { hits: hybridBody.results?.length ?? 0, degradation: hybridTrace },
      };
      console.log('MODEL_LIVE_AB_WATCHER_SEMANTIC_PAUSE',
        JSON.stringify(watcherSemanticEvidence));
    }
    if (crashBoundary) {
      // The later pointer fault hook may hold publication while /api/status waits for that
      // same lock. Read the physical mode while the migration barrier still leaves A serving.
      const floorReply = await request(apiPort, '/api/status', {}, 15000);
      const floorStatus = floorReply.status === 200 ? JSON.parse(floorReply.text) : null;
      const floorState = readJson(path.join(indexBase, 'state.json'));
      const floorOperation = operationRows(operationPath, operationKey)[0];
      combinedFloor = floorStatus?.readiness?.engineComponents?.encoders;
      requireThat(floorOperation?.operation_key === operationKey
          && floorOperation.building_generation_id === `g-${operationKey}`
          && floorState?.active_generation === sourceGeneration
          && floorState.building_generation === `g-${operationKey}`
          && floorState.migration_state === 'MIGRATING'
          && floorStatus?.worker?.migration?.activeGenerationId === sourceGeneration
          && floorStatus?.worker?.migration?.buildingGenerationId === `g-${operationKey}`
          && combinedFloor?.mode === 'IN_PLACE'
          && combinedFloor.reason === 'candidate_fits_after_source_release'
          && combinedFloor.freeBytes <= 1024 * 1024
          && combinedFloor.footprintBytes > combinedFloor.freeBytes
          && floorStatus?.components?.encoders?.state === 'RELOADING',
        `combined maintenance missed the held operation's physical floor: ${JSON.stringify({
          floorOperation, floorState, migration: floorStatus?.worker?.migration,
          encoders: combinedFloor,
        })}`);
    }
    if (acceptedWriteDuringBuild) fs.writeFileSync(migrationBarrier.releaseFile, 'release');
    const reached = await waitFor('settled B before activation marker',
      acceptedWriteDuringBuild ? 300000 : 170000,
      () => readJson(reachedFile));
    requireThat(reached.phase === (crashBoundary ?? 'installer-before-marker')
      && reached.operationKey === operationKey,
    `side-by-side B stopped at the wrong marker: ${JSON.stringify(reached)}`);
    if (crashBoundary) {
      const capturedAtCut = snapshot({ operationPath, jobsPath, indexBase, operationKey });
      const capturedCutEvidence = requireCapturedH2Settlement({ observed: capturedAtCut,
        file, hashes: capturedSourceHashes, requireThat, label: 'before crash' });
      capturedSourceHashes.unitRevisionAtCut = capturedCutEvidence.unitRevision;
      const floorState = readJson(path.join(indexBase, 'state.json'));
      const floorOperation = operationRows(operationPath, operationKey)[0];
      const pointerPublished = crashBoundary === 'installer-pointer-before-settings';
      const pointerStateCorrect = pointerPublished
        ? floorState?.active_generation === `g-${operationKey}`
          && !floorState.building_generation && floorState.migration_state === 'IDLE'
        : floorState?.active_generation === sourceGeneration
          && floorState.building_generation === `g-${operationKey}`
          && floorState.migration_state === 'SWITCHING';
      requireThat(reached.operationKey === operationKey
          && floorOperation?.operation_key === operationKey
          && floorOperation.building_generation_id === `g-${operationKey}`
          && pointerStateCorrect,
        `combined maintenance missed the exact pointer boundary: ${JSON.stringify({
          reached, floorOperation, floorState, encoders: combinedFloor,
        })}`);
      await exerciseLowMemoryCrashRecovery({ crashBoundary, reached, combinedFloor, dispatched,
        changedCapturedFile: file, changedCapturedMarker, staleMarker: marker,
        capturedSourceHashes,
        acceptedFile, acceptedMarker, first, manifest, data, runtime,
        indexBase, operationPath, jobsPath, operationKey, sourceGeneration, sourceManifest,
        beforeSettings, bRoot, readJson, waitFor, request, post, requireThat, matchingHit,
        work, bootRootChanges, engineLogWindow });
      return;
    }
    const aState = readJson(path.join(indexBase, 'state.json'));
    const bManifest = readJson(path.join(indexBase, 'indices', bGeneration,
      '.justsearch-index-generation.json'));
    const inFlight = operationRows(operationPath, operationKey)[0];
    requireThat(aState.active_generation === sourceGeneration
      && bManifest?.models?.embedding?.id?.startsWith(bRoot)
      && (!distinctModelB || bManifest.models.embedding.sha256
        !== sourceManifest.models.embedding.sha256)
      && inFlight?.phase === 'settled' && inFlight?.building_generation_id === bGeneration
      && inFlight.units_completed >= 2 + extraBuildFiles && inFlight.units_failed === 0
      && settingsWitnessOnDisk(data).witness.acceptedRevision
        === beforeSettings.witness.acceptedRevision,
    `A/B cut lost serving A or settled B: ${JSON.stringify({ aState, bManifest,
      phase: inFlight?.phase })}`);
    const textSearch = await post(apiPort, '/api/knowledge/search',
      { query: marker, limit: 10, mode: 'text' }, 30000);
    const vectorSearch = await post(apiPort, '/api/knowledge/search',
      { query: marker, limit: 10, mode: 'vector' }, 30000);
    const statusReply = await request(apiPort, '/api/status', {}, 15000);
    const status = statusReply.status === 200 ? JSON.parse(statusReply.text) : null;
    if (!watcherDeleteDuringBuild) {
      console.log('MODEL_LIVE_AB_HELD_READINESS', JSON.stringify({
        migration: status?.worker?.migration?.migrationState,
        compat: status?.worker?.compatibility?.embeddingCompatState,
        encoders: status?.components?.encoders?.state,
        retrieval: status?.readiness?.composites?.retrieval,
      }));
    }
    const composeMode = status?.readiness?.engineComponents?.encoders?.mode;
    requireThat(['IN_PLACE', 'BESIDE'].includes(composeMode)
      && (inPlaceModelB ? composeMode === 'IN_PLACE'
        : !distinctModelB || composeMode === 'BESIDE'),
    `candidate composition mode is unavailable or violated the forced floor: ${JSON.stringify({
      composeMode, encoder: status?.readiness?.engineComponents?.encoders,
    })}`);
    const actualInPlace = composeMode === 'IN_PLACE';
    const retrieval = status?.readiness?.composites?.retrieval;
    const vectorBody = vectorSearch.status === 200 ? JSON.parse(vectorSearch.text) : null;
    const heldQueryTrace = watcherDeleteDuringBuild
      ? watcherSemanticEvidence?.vector?.degradation ?? null
      : actualInPlace ? vectorBody?.searchTrace?.degradation ?? null : null;
    if (!watcherDeleteDuringBuild) {
      requireThat(actualInPlace
        ? retrieval?.state === 'DEGRADED'
          && retrieval.reasonCodes?.includes('index.embedding_rebuilding')
        : !retrieval?.reasonCodes?.includes('index.embedding_rebuilding'),
      `semantic-pause readiness contradicted ${composeMode}: ${JSON.stringify(retrieval)}`);
      const vectorOutcome = actualInPlace
        ? vectorSearch.status === 200
          && vectorBody?.results?.length === 0
          && heldQueryTrace?.vectorBlocked === true
          && heldQueryTrace.vectorBlockedReason === 'REBUILD_IN_PROGRESS'
          && status?.components?.encoders?.state === 'RELOADING'
        : vectorSearch.status === 200 && matchingHit(vectorSearch, file, marker);
      requireThat(textSearch.status === 200 && matchingHit(textSearch, file, marker)
        && vectorOutcome,
      `serving A violated ${composeMode} mode while B was settled: ${JSON.stringify({
        text: textSearch.text, vector: vectorSearch.text, encoders: status?.components?.encoders,
      })}`);
    }
    if (actualInPlace && !watcherDeleteDuringBuild) {
      const hybridSearch = await post(apiPort, '/api/knowledge/search',
        { query: marker, limit: 10 }, 30000);
      requireThat(hybridSearch.status === 200
        && matchingHit(hybridSearch, file, marker),
      `held in-place hybrid query lost keyword search: ${hybridSearch.text}`);
      console.log('MODEL_LIVE_AB_HELD_QUERY_TRACE', JSON.stringify({
        operationKey, encoderState: status?.components?.encoders?.state,
        retrievalReasons: retrieval?.reasonCodes, degradation: heldQueryTrace,
      }));
    }
    if (actualInPlace && acceptedWriteDuringBuild && !watcherDeleteDuringBuild) {
      // D1-14's UI acceptance runs against this held installed Engine, not a mocked
      // status response. The installed harness intentionally has a dummy frontend;
      // jseval auto-serves this worktree's Lit UI with its proxy pinned to the
      // installed API, then the registered-helper sweep retires that owned server.
      const outputDir = path.join(work, 'ui-shot-semantic-paused');
      const jsevalDir = path.join(process.cwd(), 'scripts', 'jseval');
      const uiSession = `lane-f-semantic-${first.runId}`;
      const uiEnv = { ...process.env,
        PYTHONPATH: [jsevalDir, process.env.PYTHONPATH].filter(Boolean).join(path.delimiter),
        VITE_JUSTSEARCH_API_PORT: String(apiPort),
        CLAUDE_CODE_SESSION_ID: uiSession,
        JUSTSEARCH_AGENT_SESSION_ID: uiSession,
      };
      try {
        const uiOutput = await new Promise((resolve, reject) => {
          execFile('python', ['-m', 'jseval', 'ui-shot', 'search-semantic-paused-live',
            '--no-demo', '--output-dir', outputDir],
          { cwd: work, env: uiEnv, timeout: 90000, maxBuffer: 1024 * 1024 },
          (error, stdout, stderr) => error
            ? reject(new Error(`installed semantic-pause UI capture failed: ${stderr || stdout || error.message}`))
            : resolve(stdout));
        });
        const measure = readJson(path.join(outputDir, 'search-semantic-paused-live.measure.json'));
        requireThat(measure?.axe?.violations?.length === 0
          && measure?.console_errors?.length === 0,
        `installed semantic-pause UI measure failed: ${JSON.stringify(measure)}`);
        console.log('MODEL_LIVE_AB_UI_PASS', JSON.stringify({
          outputDir, measure: { axeViolations: measure.axe.violations.length,
            consoleErrors: measure.console_errors.length }, output: uiOutput.trim(),
        }));
      } finally {
        const sweep = await new Promise((resolve, reject) => {
          execFile('node', ['scripts/dev/agent-spawn-sweep.cjs', '--occasion',
            'session-closeout', '--session-id', uiSession, '--own-session-only'],
          { cwd: process.cwd(), timeout: 30000, maxBuffer: 1024 * 1024 },
          (error, stdout, stderr) => error
            ? reject(new Error(`semantic-pause UI helper sweep failed: ${stderr || stdout || error.message}`))
            : resolve(stdout));
        });
        console.log('MODEL_LIVE_AB_UI_SWEEP', sweep.trim());
      }
    }
    console.log('MODEL_LIVE_AB_CUT', JSON.stringify({ operationKey, sourceGeneration,
      buildingGeneration: bGeneration, aModel: sourceManifest.models.embedding,
      bModel: bManifest.models.embedding, unitsCompleted: inFlight.units_completed,
      unitsFailed: inFlight.units_failed, mode: composeMode,
      encoderState: status?.components?.encoders?.state,
      retrievalReasons: watcherDeleteDuringBuild
        ? watcherSemanticEvidence?.retrievalReasons : retrieval?.reasonCodes,
      heldQueryTrace,
      vectorHits: watcherDeleteDuringBuild ? watcherSemanticEvidence?.vector?.hits
        : actualInPlace ? 0 : JSON.parse(vectorSearch.text).results.length }));
    if (issuedSearch) {
      requireThat(composeMode === 'BESIDE' && distinctModelB,
        'issued A search requires a physical distinct-model BESIDE candidate');
      const query = `${marker} ${marker}`;
      const pending = { settled: false };
      pending.outcome = post(apiPort, '/api/knowledge/search',
        { query, limit: 10, mode: 'vector' }, 180000)
        .then(value => ({ value }), error => ({ error }))
        .then(outcome => { pending.settled = true; return outcome; });
      issuedARequest = pending;
      issuedAReached = await waitFor('A search captured before B publication', 30000,
        () => readJson(issuedBarrier.reachedFile));
      requireThat(issuedAReached.query === query
        && issuedAReached.activeGeneration === sourceGeneration
        && issuedAReached.pid === manifest.pid && !pending.settled,
      `issued search did not retain captured A: ${JSON.stringify(issuedAReached)}`);
    }
    if (cancelBeforePointer) {
      cancellationKey = await cancelReindexWithApproval({ apiPort, manifest,
        reindexKey: operationKey, createOperationKey, request, post, requireThat });
    }
  } finally {
    if (acceptedWriteDuringBuild) fs.writeFileSync(migrationBarrier.releaseFile, 'release');
    fs.writeFileSync(releaseFile, 'release');
  }
  if (issuedSearch) {
    const published = await waitFor('B serving view published while issued A search waits',
      120000, () => readJson(migrationBarrier.reachedFile));
    const state = readJson(path.join(indexBase, 'state.json'));
    requireThat(published.point === 'migration-after-live-activation'
      && published.sourceGeneration === `g-${operationKey}`
      && state?.active_generation === `g-${operationKey}`
      && !issuedARequest.settled,
    `B did not publish while A search was issued: ${JSON.stringify({ published, state })}`);
    fs.writeFileSync(migrationBarrier.releaseFile, 'release');
    const bFingerprint = readJson(path.join(indexBase, 'indices', `g-${operationKey}`,
      '.justsearch-index-generation.json'))?.models?.embedding?.sha256;
    requireThat(/^[0-9a-f]{64}$/i.test(bFingerprint ?? ''),
      'issued A search candidate lacks a full B embedding fingerprint');
    const issuedB = await waitFor('B serves VECTOR while issued A search remains held',
      120000, async () => {
        if (issuedARequest.settled) {
          throw new Error('A search completed before B served a new request');
        }
        const statusReply = await request(apiPort, '/api/status', {}, 15000);
        if (statusReply.status !== 200) return null;
        const status = JSON.parse(statusReply.text);
        if (status.worker?.migration?.activeGenerationId !== `g-${operationKey}`
          || status.worker?.compatibility?.embeddingFingerprintCurrent !== bFingerprint
          || status.components?.encoders?.state !== 'READY') return null;
        const reply = await post(apiPort, '/api/knowledge/search',
          { query: marker, limit: 10, mode: 'vector' }, 30000);
        return reply.status === 200 && JSON.parse(reply.text).results?.length > 0
          ? reply : null;
      });
    requireThat(!issuedARequest.settled,
      'A search completed before its capture barrier was released');
    fs.writeFileSync(issuedBarrier.releaseFile, 'release');
    const aOutcome = await issuedARequest.outcome;
    requireThat(!aOutcome.error && aOutcome.value.status === 200
      && matchingHit(aOutcome.value, file, marker),
    `issued A search failed after B served: ${aOutcome.error?.message ?? aOutcome.value?.text}`);
    console.log('MODEL_LIVE_AB_ISSUED_SEARCH_PASS', JSON.stringify({
      sourceGeneration, promotedGeneration: `g-${operationKey}`,
      capturedGeneration: issuedAReached.activeGeneration,
      aVectorHits: JSON.parse(aOutcome.value.text).results.length,
      bVectorHitsWhileAHeld: JSON.parse(issuedB.text).results.length,
      aSearchCompletedAfterB: true,
    }));
  }
  await dispatched.settled;
  if (cancelBeforePointer) {
    const retired = await waitFor('accepted-write B cancelled and physically retired',
      180000, () => {
        const row = operationRows(operationPath, operationKey)[0];
        const state = readJson(path.join(indexBase, 'state.json'));
        if (row?.state !== 'CANCELLED' || row.failure_reason !== 'cancelled'
          || state?.active_generation !== sourceGeneration
          || state?.migration_state !== 'IDLE'
          || state?.building_generation != null
          || fs.existsSync(path.join(indexBase, 'indices', `g-${operationKey}`))) return null;
        const durable = row.processing_history_counts_json
          ? JSON.parse(row.processing_history_counts_json) : null;
        const switchRows = readRows(jobsPath,
          'SELECT key FROM switch_buffer WHERE generation = ?', `g-${operationKey}`);
        return durable?.refusalCode === 'cancelled' && switchRows.length === 0
          ? { row, state } : null;
      });
    const acceptedFile = path.join(work, 'installer-root-a',
      `accepted-during-b-${operationKey}.txt`);
    const recoveredA = await waitFor('cancelled in-place A retains accepted write and VECTOR',
      120000, async () => {
        try {
          const live = readJson(path.join(runtime, 'manifest.json'));
          if (!live?.head?.apiPort) return null;
          const reply = await request(live.head.apiPort, '/api/status', {}, 15000);
          if (reply.status !== 200) return null;
          const status = JSON.parse(reply.text);
          if (status.worker?.compatibility?.embeddingFingerprintCurrent
              !== sourceManifest.models.embedding.sha256
            || status.components?.encoders?.state !== 'READY') return null;
          const text = await post(live.head.apiPort, '/api/knowledge/search',
            { query: 'lexicalbridgecobalt', limit: 10, mode: 'text' }, 30000);
          if (text.status !== 200
            || !matchingHit(text, acceptedFile, 'lexicalbridgecobalt')) return null;
          const vector = await post(live.head.apiPort, '/api/knowledge/search',
            { query: marker, limit: 10, mode: 'vector' }, 30000);
          return vector.status === 200 && JSON.parse(vector.text).results?.length > 0
            ? { status, text, vector } : null;
        } catch { return null; }
      });
    console.log('MODEL_LIVE_AB_ACCEPTED_CANCEL_PASS', JSON.stringify({ operationKey,
      cancellationKey, sourceGeneration, activeGeneration: retired.state.active_generation,
      acceptedFile, aVectorHits: JSON.parse(recoveredA.vector.text).results.length,
      aEmbeddingSha: recoveredA.status.worker.compatibility.embeddingFingerprintCurrent,
      terminalState: retired.row.state }));
    return;
  }
  const completed = await waitFor('side-by-side activation terminal promotion', 180000, () => {
    const row = operationRows(operationPath, operationKey)[0];
    if (row?.state === 'FAILED' || row?.state === 'CANCELLED') {
      throw new Error(`side-by-side activation terminal refusal: ${row.failure_reason}`);
    }
    const active = readJson(path.join(indexBase, 'state.json'));
    const settings = settingsWitnessOnDisk(data);
    return row?.state === 'COMPLETE' && active?.active_generation === `g-${operationKey}`
      && settings.witness.lastCommittedOperationKey === operationKey
      ? { row, active, settings } : null;
  });
  const bFingerprint = readJson(path.join(indexBase, 'indices', `g-${operationKey}`,
    '.justsearch-index-generation.json')).models.embedding.sha256;
  let lastStatus = null;
  const bStatus = await waitFor('B status after publication', 120000, async () => {
    try {
      const live = readJson(path.join(runtime, 'manifest.json'));
      if (!live?.head?.apiPort) return null;
      const result = await request(live.head.apiPort, '/api/status', {}, 15000);
      if (result.status !== 200) return null;
      const status = JSON.parse(result.text);
      lastStatus = {
        active: status.worker?.migration?.activeGenerationId,
        migration: status.worker?.migration?.migrationState,
        encoder: status.components?.encoders?.state,
        fingerprint: status.worker?.compatibility?.embeddingFingerprintCurrent,
        compat: status.worker?.compatibility?.embeddingCompatState,
      };
      return lastStatus.active === `g-${operationKey}`
        && lastStatus.encoder === 'READY'
        && (!distinctModelB || lastStatus.fingerprint === bFingerprint)
        ? { result, live } : null;
    } catch { return null; }
  }).catch(error => { throw new Error(`${error.message}; last B status=${JSON.stringify(lastStatus)}`); });
  const bVector = await post(bStatus.live.head.apiPort, '/api/knowledge/search',
    { query: marker, limit: 10, mode: 'vector' }, 30000);
  requireThat(bVector.status === 200 && JSON.parse(bVector.text).results?.length > 0,
    `promoted B could not answer a real vector query: ${bVector.text}`);
  if (acceptedWriteDuringBuild) {
    const acceptedFile = path.join(work, 'installer-root-a', `accepted-during-b-${operationKey}.txt`);
    const acceptedText = await post(bStatus.live.head.apiPort, '/api/knowledge/search',
      { query: 'lexicalbridgecobalt', limit: 10, mode: 'text' }, 30000);
    requireThat(acceptedText.status === 200
      && matchingHit(acceptedText, acceptedFile, 'lexicalbridgecobalt'),
    `promoted B lost the accepted write: ${acceptedText.text}`);
  }
  if (watcherDeleteDuringBuild) {
    const removedText = await post(bStatus.live.head.apiPort, '/api/knowledge/search',
      { query: removedMarker, limit: 10, mode: 'text' }, 30000);
    requireThat(removedText.status === 200
      && !matchingHit(removedText, removedFile, removedMarker),
      `promoted B resurrected a watcher deletion: ${removedText.text}`);
    watcherPassEvidence.bAbsence = {
      absentInB: true,
      hitsInB: JSON.parse(removedText.text).results?.length ?? 0,
    };
  }
  // The terminal B query above proves recovery at the API, but the independent sampler may be
  // between its vector and hybrid requests at that instant. Let it witness one post-refusal
  // vector success before closing the sample window.
  if (inPlaceModelB && transitionSampler) {
    await waitFor('semantic sampler observes post-refusal vector recovery', 15000,
      () => transitionSampler.recovered() ? true : null);
  }
  const semantic = await transitionSampler?.stop();
  if (semantic) {
    const violations = inPlaceModelB ? inPlaceSemanticViolations(semantic)
      : besideSemanticViolations(semantic);
    requireThat(semantic.indexStarting === 0 && semantic.transport === 0
      && semantic.apiOutageWindowMs === 0 && violations.length === 0,
    `live model transition violated D1-18: ${JSON.stringify({ violations, semantic })}`);
  }
  const promotedSupervisor = readJson(path.join(runtime, 'supervisor.v1.json'));
  const promotedManifest = readJson(path.join(runtime, 'manifest.json'));
  requireThat(promotedSupervisor?.runId === first.runId
    && promotedSupervisor.incarnation === first.incarnation
    && promotedSupervisor.restartCount === first.restartCount
    && promotedSupervisor.instanceId === first.instanceId
    && promotedManifest?.instanceId === manifest.instanceId
    && promotedManifest.pid === manifest.pid,
  `live model migration changed Engine identity: ${JSON.stringify({
    first, promotedSupervisor, manifest, promotedManifest })}`);
  if (watcherDeleteDuringBuild) {
    requireThat(watcherPassEvidence?.additionVisibleInA
      && watcherPassEvidence?.scopedUpsert?.generation === bGeneration
      && watcherPassEvidence?.scopedDelete?.generation === bGeneration
      && watcherPassEvidence?.deletionAbsentInA === true
      && watcherPassEvidence?.bAbsence?.absentInB === true
      && acceptedWriteEvidence?.visibleInA === true
      && watcherSemanticEvidence?.migration === 'MIGRATING'
      && JSON.parse(bVector.text).results.length > 0
      && promotedSupervisor.restartCount === first.restartCount,
    `watcher deletion replay evidence was incomplete: ${JSON.stringify({
      watcherPassEvidence, acceptedWriteEvidence, watcherSemanticEvidence,
      bVectorHits: JSON.parse(bVector.text).results.length,
      first, promotedSupervisor,
    })}`);
    console.log('MODEL_LIVE_AB_WATCHER_DELETE_PASS', JSON.stringify({
      scenario: 'model-live-a-b', mode: watcherSemanticEvidence.composeMode, sourceGeneration,
      buildingGeneration: bGeneration, watcher: watcherPassEvidence,
      acceptedWrite: acceptedWriteEvidence,
      semanticPause: watcherSemanticEvidence,
      bVector: { visible: true, hits: JSON.parse(bVector.text).results.length },
      engine: { instanceId: promotedManifest.instanceId, pid: promotedManifest.pid,
        restartCount: promotedSupervisor.restartCount,
        processRestarted: promotedManifest.instanceId !== manifest.instanceId
          || promotedManifest.pid !== manifest.pid
          || promotedSupervisor.restartCount !== first.restartCount },
    }));
  }
  console.log('MODEL_LIVE_AB_PASS', JSON.stringify({ operationKey,
    sourceGeneration, activeGeneration: completed.active.active_generation,
    settingsRevision: completed.settings.witness.acceptedRevision,
    bVectorHits: JSON.parse(bVector.text).results.length,
    instanceId: promotedManifest.instanceId,
    restartCount: promotedSupervisor.restartCount,
    ...(semantic ? { semantic } : {}) }));
  } finally {
    if (issuedSearch) {
      fs.writeFileSync(issuedBarrier.releaseFile, 'release');
      fs.writeFileSync(migrationBarrier.releaseFile, 'release');
    }
    await transitionSampler?.stop();
  }
}

async function exerciseLowMemoryCrashRecovery({ crashBoundary, reached, combinedFloor, dispatched,
  changedCapturedFile, changedCapturedMarker, staleMarker, acceptedFile, acceptedMarker,
  capturedSourceHashes,
  first, manifest, data, runtime, indexBase, operationPath, jobsPath, operationKey,
  sourceGeneration, sourceManifest, beforeSettings, bRoot, readJson, waitFor, request, post,
  requireThat, matchingHit, work, bootRootChanges, engineLogWindow }) {
  const targetGeneration = `g-${operationKey}`;
  const faultedSupervisor = readJson(path.join(runtime, 'supervisor.v1.json'));
  const faultedManifest = readJson(path.join(runtime, 'manifest.json'));
  requireThat(reached.operationKey === operationKey && reached.pid === faultedSupervisor?.pid
      && faultedSupervisor.runId === first.runId
      && faultedSupervisor.incarnation === first.incarnation
      && faultedSupervisor.instanceId === first.instanceId
      && faultedManifest?.pid === faultedSupervisor.pid
      && faultedManifest.instanceId === faultedSupervisor.instanceId
      && manifest.pid === faultedSupervisor.pid && manifest.instanceId === faultedSupervisor.instanceId,
    `combined maintenance fault did not target the admitted Engine: ${JSON.stringify({
      reached, first, manifest, faultedSupervisor, faultedManifest,
    })}`);

  const cut = snapshot({ operationPath, jobsPath, indexBase, operationKey });
  cut.settings = settingsWitnessOnDisk(data);
  requireThat(cut.operations.length === 1 && cut.operation?.operation_key === operationKey
      && cut.operation.operation_ref === 'core.activate-installed-models'
      && cut.operation.building_generation_id === targetGeneration,
    `combined maintenance cut changed the accepted operation identity: ${JSON.stringify(cut.operation)}`);
  const pointerPublished = crashBoundary === 'installer-pointer-before-settings';
  requireThat(pointerPublished
      ? cut.state.active_generation === targetGeneration
        && cut.settings.witness.acceptedRevision === beforeSettings.witness.acceptedRevision
        && cut.settings.witness.lastCommittedOperationKey
          === beforeSettings.witness.lastCommittedOperationKey
      : cut.state.active_generation === sourceGeneration
        && cut.settings.witness.acceptedRevision === beforeSettings.witness.acceptedRevision
        && cut.settings.witness.lastCommittedOperationKey
          === beforeSettings.witness.lastCommittedOperationKey,
    `combined maintenance cut has the wrong pointer/settings side: ${JSON.stringify({
      crashBoundary, state: cut.state, settings: cut.settings,
    })}`);

  const cooldown = await killOwnedEngineAndObserveCooldown({ engine: faultedSupervisor, data,
    readJson, waitFor, requireThat });
  const bootRootMutation = bootRootChanges
    ? mutateCommittedBootRoots({ work, data, runtime, engine: faultedSupervisor,
      operationKey, readJson, requireThat }) : null;
  await dispatched.settled;
  const successor = await waitFor('combined maintenance exact successor identity', 180000, () => {
    const supervisor = readJson(path.join(runtime, 'supervisor.v1.json'));
    const live = readJson(path.join(runtime, 'manifest.json'));
    return supervisor?.state === 'running' && supervisor.runId === first.runId
      && supervisor.incarnation === first.incarnation + 1 && supervisor.restartCount === 1
      && supervisor.pid !== first.pid && supervisor.instanceId !== first.instanceId
      && live?.pid === supervisor.pid && live.instanceId === supervisor.instanceId
      ? { supervisor, manifest: live } : null;
  });

  const terminal = await waitFor('combined maintenance terminal B convergence', 180000, () => {
    const observed = snapshot({ operationPath, jobsPath, indexBase, operationKey });
    observed.settings = settingsWitnessOnDisk(data);
    return observed.operations.length === 1 && observed.operation?.state === 'COMPLETE'
      && observed.operation.phase === 'settled'
      && observed.state?.active_generation === targetGeneration
      && observed.settings.witness.acceptedRevision
        === beforeSettings.witness.acceptedRevision + 1
      && observed.settings.witness.lastCommittedOperationKey === operationKey
      ? observed : null;
  });
  const capturedTerminalEvidence = bootRootMutation
    ? requireRetainedCapturedH2Settlement({ observed: terminal, jobsPath, operationKey,
      hashes: capturedSourceHashes, requireThat })
    : requireCapturedH2Settlement({ observed: terminal,
      file: changedCapturedFile, hashes: capturedSourceHashes, requireThat,
      label: 'after recovery', expectedUnitRevision: capturedSourceHashes.unitRevisionAtCut });
  const sealedReceipt = parseStoredJson(terminal.walk?.receipt_json,
    'combined maintenance captured receipt');
  requireThat(terminal.walk?.sealed_at != null && sealedReceipt.supersededEvents === 1
      && sealedReceipt.gapCount === 0,
    `combined maintenance did not seal exact H1→H2 supersession: ${JSON.stringify({
      walk: terminal.walk, sealedReceipt,
    })}`);
  const bManifest = readJson(path.join(indexBase, 'indices', targetGeneration,
    '.justsearch-index-generation.json'));
  const modelIdentities = assertCombinedBModelSettings({ bManifest, sourceManifest,
    sourceSettings: beforeSettings.settings, settings: terminal.settings.settings,
    bRoot, requireThat });
  const bFingerprint = modelIdentities.embedding.sha256;

  const converged = await waitFor('combined maintenance B encoder ready', 120000, async () => {
    try {
      const live = readJson(path.join(runtime, 'manifest.json'));
      if (live?.instanceId !== successor.manifest.instanceId
          || live.pid !== successor.manifest.pid) return null;
      const response = await request(live.head.apiPort, '/api/status', {}, 15000);
      if (response.status !== 200) return null;
      const status = JSON.parse(response.text);
      return status.worker?.migration?.activeGenerationId === targetGeneration
        && status.worker?.compatibility?.embeddingFingerprintCurrent === bFingerprint
        && status.components?.encoders?.state === 'READY' ? { live, status } : null;
    } catch { return null; }
  });
  const bootRoots = bootRootMutation ? await assertCommittedBootRoots({ mutation: bootRootMutation,
    data, indexBase, sourceGeneration, targetGeneration, engineLogWindow,
    apiPort: converged.live.head.apiPort, post, readJson, requireThat, matchingHit, waitFor }) : null;
  const latestText = await waitFor('combined maintenance latest text', 60000, async () => {
    try {
      const response = await post(converged.live.head.apiPort, '/api/knowledge/search',
        { query: changedCapturedMarker, limit: 10, mode: 'text' }, 30000);
      return response.status === 200
        && matchingHit(response, changedCapturedFile, changedCapturedMarker)
        ? response : null;
    } catch { return null; }
  });
  const latestVector = await waitFor('combined maintenance latest vector', 120000, async () => {
    try {
      const response = await post(converged.live.head.apiPort, '/api/knowledge/search',
        { query: changedCapturedMarker, limit: 10, mode: 'vector' }, 30000);
      return response.status === 200
        && matchingHit(response, changedCapturedFile, changedCapturedMarker)
        ? response : null;
    } catch { return null; }
  });
  const staleText = await post(converged.live.head.apiPort, '/api/knowledge/search',
    { query: staleMarker, limit: 10, mode: 'text' }, 30000);
  requireThat(staleText.status === 200
      && !matchingHit(staleText, changedCapturedFile, staleMarker),
    `combined maintenance resurrected stale captured content: ${staleText.text}`);
  const acceptedWrite = await waitFor('combined maintenance accepted write in B', 60000, async () => {
    try {
      const response = await post(converged.live.head.apiPort, '/api/knowledge/search',
        { query: acceptedMarker, limit: 10, mode: 'text' }, 30000);
      return response.status === 200 && matchingHit(response, acceptedFile, acceptedMarker)
        ? response : null;
    } catch { return null; }
  });
  const runtimeIdentities = { embedding: {
    fingerprint: converged.status.worker.compatibility.embeddingFingerprintCurrent,
    activeGeneration: converged.status.worker.migration.activeGenerationId,
    state: converged.status.components.encoders.state,
  } };

  const finalSupervisor = readJson(path.join(runtime, 'supervisor.v1.json'));
  const finalManifest = readJson(path.join(runtime, 'manifest.json'));
  requireThat(finalSupervisor?.runId === first.runId
      && finalSupervisor.incarnation === first.incarnation + 1
      && finalSupervisor.restartCount === 1
      && finalSupervisor.instanceId === successor.supervisor.instanceId
      && finalSupervisor.pid === successor.supervisor.pid
      && finalManifest?.instanceId === successor.manifest.instanceId
      && finalManifest.pid === successor.manifest.pid,
    `combined maintenance used more than one successor: ${JSON.stringify({
      successor, finalSupervisor, finalManifest,
    })}`);
  console.log('MODEL_LIVE_AB_LOW_MEMORY_CRASH_PASS', JSON.stringify({ operationKey,
    boundary: crashBoundary, floorEvidence: { mode: combinedFloor.mode,
      reason: combinedFloor.reason, freeBytes: combinedFloor.freeBytes,
      footprintBytes: combinedFloor.footprintBytes }, sourceGeneration,
    activeGeneration: terminal.state.active_generation, restartCount: finalSupervisor.restartCount,
    settingsRevision: { before: beforeSettings.witness.acceptedRevision,
      after: terminal.settings.witness.acceptedRevision },
    terminalState: terminal.operation.state, latestText: Boolean(latestText),
    latestVector: Boolean(latestVector), staleAbsent: true,
    acceptedWrite: Boolean(acceptedWrite), modelIdentities, runtimeIdentities,
    capturedReplay: { ...capturedSourceHashes,
      unitRevision: capturedTerminalEvidence.unitRevision,
      supersededEvents: sealedReceipt.supersededEvents },
    faulted: { pid: faultedSupervisor.pid, instanceId: faultedSupervisor.instanceId,
      incarnation: faultedSupervisor.incarnation },
    successor: { pid: successor.supervisor.pid, instanceId: successor.supervisor.instanceId,
      incarnation: successor.supervisor.incarnation }, countedExit: cooldown.lastExit,
    bFingerprint, bootRoots }));
}

function mutateCommittedBootRoots({ work, data, runtime, engine, operationKey,
  readJson, requireThat }) {
  const registryFile = path.join(data, 'watched_roots.json');
  const registry = readJson(registryFile);
  const roots = ['installer-root-a', 'installer-root-b'].map(name => path.join(work, name));
  requireThat(registry?.schemaVersion === 1 && registry.roots?.length === roots.length
      && registry.roots.every((root, i) => samePath(root.path, roots[i])),
    'committed boot must retain both actual watched roots');
  const deleted = path.join(roots[1], 'installer-1.txt');
  const changed = path.join(roots[0], 'build-load-0.txt');
  const added = path.join(roots[1], `boot-add-${operationKey}.txt`);
  const deletedMarker = fs.readFileSync(deleted, 'utf8').split(/\s+/)[0];
  const staleMarker = fs.readFileSync(changed, 'utf8').split(/\s+/)[0];
  const addedMarker = 'committedbootadditionquokka';
  const changedMarker = 'committedbootchangedquokka';
  const collection = 'committed-boot-roots';
  requireThat(!fs.existsSync(added), 'boot addition must be new after the Engine died');
  fs.writeFileSync(added, `${addedMarker} capybara\n`);
  fs.writeFileSync(changed, `${changedMarker} capybara\n`);
  fs.unlinkSync(deleted);
  for (const root of registry.roots) root.collection = collection;
  const staging = `${registryFile}.boot-fixture-tmp`;
  fs.writeFileSync(staging, JSON.stringify(registry));
  fs.renameSync(staging, registryFile);
  const held = readJson(path.join(runtime, 'supervisor.v1.json'));
  const heldManifest = readJson(path.join(runtime, 'manifest.json'));
  requireThat(held?.state === 'restarting' && held.runId === engine.runId
      && held.incarnation === engine.incarnation && held.restartCount === 1
      && held.pid === engine.pid && held.instanceId === engine.instanceId
      && held.lastExit?.counted === true
      && heldManifest?.pid === engine.pid && heldManifest.instanceId === engine.instanceId,
    `successor raced the dead-Engine root mutations: ${JSON.stringify({ held, heldManifest })}`);
  const survivors = roots.flatMap(root => fs.readdirSync(root)
    .filter(name => name.endsWith('.txt')).map(name => path.join(root, name)));
  return { roots, collection, added, addedMarker, deleted, deletedMarker,
    changed, changedMarker, staleMarker, survivors };
}

async function assertCommittedBootRoots({ mutation, data, indexBase, sourceGeneration,
  targetGeneration, engineLogWindow, apiPort, post, readJson, requireThat, matchingHit, waitFor }) {
  const contents = readEngineLogWindow(engineLogWindow, requireThat);
  const marker = 'Committed boot roots settled before publication: '
    + `disposition=PROMOTED, active=${targetGeneration}, roots=${mutation.roots.length}`;
  requireThat(contents.split('\n').filter(line => line.includes(marker)).length === 1,
    'installed successor did not observe recorded PROMOTED root convergence before publication');
  // These are single observations. A later watcher repair must not turn a failed
  // first available B view into a successful boot proof.
  for (const root of mutation.roots) {
    const response = await post(apiPort, '/api/knowledge/folder-files',
      { folderPath: root, limit: 1000, projection: ['path', 'collection', 'content_preview'] }, 30000);
    requireThat(response.status === 200, `committed root browse failed: ${response.text}`);
    const listing = JSON.parse(response.text);
    const expected = mutation.survivors.filter(file => samePath(path.dirname(file), root));
    requireThat(listing.totalCount === expected.length && listing.files?.length === expected.length
        && expected.every(file => listing.files.some(hit => samePath(hit?.fields?.path, file))),
      `committed boot did not enumerate the exact surviving root files: ${response.text}`);
    requireThat(listing.files.every(hit => hit?.fields?.collection === mutation.collection),
      `committed boot lost the persisted root label: ${response.text}`);
    for (const file of expected) {
      const hits = listing.files.filter(hit => samePath(hit?.fields?.path, file));
      const contentMarker = fs.readFileSync(file, 'utf8').split(/\s+/)[0];
      requireThat(hits.length === 1 && String(hits[0]?.fields?.content_preview ?? '')
        .includes(contentMarker),
      `committed boot has stale or missing survivor content: ${file}: ${response.text}`);
    }
  }
  for (const [file, marker] of [[mutation.added, mutation.addedMarker],
    [mutation.changed, mutation.changedMarker]]) {
    const response = await post(apiPort, '/api/knowledge/search',
      { query: marker, limit: 10, mode: 'text',
        projection: ['path', 'collection', 'content_preview'] }, 30000);
    requireThat(response.status === 200 && matchingHit(response, file, marker),
      `committed boot omitted a surviving root file: ${file}: ${response.text}`);
    const hit = JSON.parse(response.text).results.find(result => samePath(result?.fields?.path, file));
    requireThat(hit?.fields?.collection === mutation.collection,
      `committed boot lost the persisted root label: ${JSON.stringify(hit)}`);
  }
  for (const [file, marker] of [[mutation.deleted, mutation.deletedMarker],
    [mutation.changed, mutation.staleMarker]]) {
    const response = await post(apiPort, '/api/knowledge/search',
      { query: marker, limit: 10, mode: 'text' }, 30000);
    requireThat(response.status === 200 && !matchingHit(response, file, marker),
      `committed boot resurrected deleted or stale root content: ${response.text}`);
  }
  const registry = readJson(path.join(data, 'watched_roots.json'));
  requireThat(registry?.roots?.length === mutation.roots.length
      && registry.roots.every((root, i) => samePath(root.path, mutation.roots[i])
        && root.collection === mutation.collection),
    'successor changed the persisted root authority');
  // Recorded completion can persist after boot's first retirement attempt. The existing
  // owner retries every two minutes; content above must already match at first publication.
  await waitFor('committed boot predecessor retirement', 180000, async () => {
    const state = readJson(path.join(indexBase, 'state.json'));
    return state?.active_generation === targetGeneration && state.previous_generation == null
      && !fs.existsSync(path.join(indexBase, 'indices', sourceGeneration));
  });
  return { disposition: 'PROMOTED', persistedRoots: mutation.roots.length,
    survivorCount: mutation.survivors.length, added: true, deleted: true,
    changedBytes: true, staleAbsent: true, labelsCurrent: true, predecessorRetired: true };
}

function requireCapturedH2Settlement({ observed, file, hashes, requireThat, label,
  expectedUnitRevision = null }) {
  const member = observed.jobs.find(job => samePath(job.path, file));
  const terminal = observed.ledger.filter(event => event.unit_revision === member?.unit_revision
    && event.terminal_coverage === 'INDEXED'
    && event.planned_source_sha256 === hashes?.planned
    && event.content_hash === hashes?.committed);
  requireThat(observed.walk?.captured_plan === 1
      && observed.walk.enumeration_outcome === 'COMPLETE'
      && member?.state === 'DONE'
      && member.planned_source_sha256 === hashes?.planned
      && member.content_hash === hashes?.committed
      && (expectedUnitRevision == null || member.unit_revision === expectedUnitRevision)
      && terminal.length === 1,
    `combined maintenance lost the exact captured H1→H2 unit ${label}: ${JSON.stringify({
      member, terminal, expectedUnitRevision, hashes,
    })}`);
  return { unitRevision: member.unit_revision };
}

function requireRetainedCapturedH2Settlement({ observed, jobsPath, operationKey, hashes, requireThat }) {
  // Root maintenance can replace a sealed walk's mutable jobs row. Its original
  // accepted unit remains certified by the exact sealed-unit/ledger association.
  const selected = readRows(jobsPath, `SELECT s.unit_revision AS selected_unit,
    s.path_hash AS selected_path, s.sealed_revision, l.operation_key, l.path_hash,
    l.unit_revision, l.terminal_coverage, l.planned_source_sha256, l.content_hash
    FROM ingestion_walk_sealed_units s LEFT JOIN ingestion_ledger l ON l.id = s.ledger_id
    WHERE s.operation_key = ? ORDER BY s.path_hash`, operationKey);
  const captured = selected.filter(unit => unit.unit_revision === hashes.unitRevisionAtCut
    && unit.terminal_coverage === 'INDEXED'
    && unit.planned_source_sha256 === hashes.planned && unit.content_hash === hashes.committed);
  requireThat(observed.walk?.captured_plan === 1
      && observed.walk.enumeration_outcome === 'COMPLETE' && observed.walk.sealed_at != null
      && selected.length === observed.walk.planned_units
      && selected.every(unit => unit.operation_key === operationKey
        && unit.selected_unit === unit.unit_revision && unit.selected_path === unit.path_hash
        && unit.sealed_revision === observed.walk.revision)
      && captured.length === 1,
    `committed boot lost the original sealed H1→H2 unit: ${JSON.stringify({
      walk: observed.walk, selected, hashes,
    })}`);
  return { unitRevision: captured[0].unit_revision };
}

function assertCombinedBModelSettings({ bManifest, sourceManifest, sourceSettings, settings, bRoot,
  requireThat }) {
  const settingsByRole = {
    embedding: 'embedOnnxModelPath',
    ner: 'nerModelPath',
    splade: 'spladeModelPath',
    'citation-scorer': 'citationScorerModelPath',
  };
  const models = bManifest?.models ?? {};
  const roles = Object.keys(models).sort();
  requireThat(roles.length > 0 && roles.every(role => settingsByRole[role]),
    `combined maintenance B manifest contains an unproved model role: ${JSON.stringify(roles)}`);
  const identities = {};
  for (const role of roles) {
    const candidate = models[role];
    const source = sourceManifest?.models?.[role];
    const settingsKey = settingsByRole[role];
    const expectedSettingsPath = path.dirname(candidate?.id ?? '');
    const expectedSourceSettingsPath = path.dirname(source?.id ?? '');
    requireThat(typeof candidate?.id === 'string'
        && /^[0-9a-f]{64}$/i.test(candidate?.sha256 ?? '')
        && path.resolve(candidate.id).startsWith(path.resolve(bRoot) + path.sep)
        && typeof source?.id === 'string'
        && /^[0-9a-f]{64}$/i.test(source.sha256 ?? '')
        && (candidate.id !== source.id || candidate.sha256 !== source.sha256)
        && samePath(sourceSettings?.[settingsKey], expectedSourceSettingsPath)
        && !samePath(sourceSettings?.[settingsKey], expectedSettingsPath)
        && samePath(settings?.[settingsKey], expectedSettingsPath),
      `combined maintenance did not converge B ownership for ${role}: ${JSON.stringify({
        candidate, source, settingsKey, sourceSettingsPath: sourceSettings?.[settingsKey],
        settingsPath: settings?.[settingsKey], expectedSourceSettingsPath, expectedSettingsPath,
      })}`);
    identities[role] = { modelPath: path.resolve(candidate.id),
      settingsPath: path.resolve(settings[settingsKey]), sha256: candidate.sha256.toLowerCase(),
      sourceModelPath: path.resolve(source.id),
      sourceSettingsPath: path.resolve(sourceSettings[settingsKey]) };
  }
  requireThat(['embedding', 'ner', 'splade', 'citation-scorer']
    .every(role => identities[role] != null),
  `combined maintenance B manifest omitted a required model role: ${JSON.stringify(roles)}`);
  return identities;
}

/** The webview's HIGH-risk cancellation uses its own prepared operation and approval. */
async function cancelReindexWithApproval({ apiPort, manifest, reindexKey,
  createOperationKey, request, post, requireThat }) {
  const cancellationKey = createOperationKey();
  const cancellationInput = { args: { reindexKey }, idempotencyKey: cancellationKey };
  const initial = await request(apiPort, CANCEL_REINDEX_ROUTE, {
    method: 'POST', headers: sessionHeaders(manifest), body: JSON.stringify(cancellationInput),
  }, 30000);
  const pending = parseJson(initial, 'installer cancellation preparation');
  requireThat(initial.status === 428 && pending.operationKey === cancellationKey
    && typeof pending.pendingId === 'string',
  `installer cancellation lacked a distinct prepared decision: ${initial.text}`);
  const approved = await post(apiPort, '/api/authorizations/approve',
    { pendingId: pending.pendingId }, 30000);
  const approval = parseJson(approved, 'installer cancellation approval');
  requireThat(approved.status === 200 && typeof approval.capsule === 'string'
    && approval.operationKey === cancellationKey
    && (approval.preparationNonce === undefined
      || typeof approval.preparationNonce === 'string'),
  `installer cancellation approval failed: ${approved.text}`);
  const cancelled = await request(apiPort, CANCEL_REINDEX_ROUTE, {
    method: 'POST', headers: sessionHeaders(manifest), body: JSON.stringify({
      ...cancellationInput, confirmationToken: approval.capsule,
      ...(approval.preparationNonce
        ? { preparationNonce: approval.preparationNonce } : {}),
    }),
  }, 30000);
  requireThat(cancelled.status === 200
    && parseJson(cancelled, 'installer cancellation').success,
  `installer cancellation failed: ${cancelled.text}`);
  return cancellationKey;
}

/** Sample the real vector port through refusal and subsequent A restoration or B promotion. */
function sampleSemanticAvailability({ apiPort, post, request, marker, file, matchingHit }) {
  const started = performance.now();
  const observations = [];
  const unexpectedSamples = [];
  let running = true;
  let result;
  const classify = (reply, vector) => {
    if (reply.status === 200 && matchingHit(reply, file, marker)) return 'available';
    let body = null;
    if (vector && reply.status === 200) {
      try { body = JSON.parse(reply.text); } catch { /* classified as an unexpected response below */ }
    }
    if (vector && body?.results?.length === 0
      && body?.searchTrace?.degradation?.vectorBlockedReason === 'REBUILD_IN_PROGRESS') {
      return 'reloading';
    }
    if (reply.status === 503 && reply.text.includes('"reason":"index.starting"')) {
      return 'index-starting';
    }
    if (unexpectedSamples.length < 5) unexpectedSamples.push({
      mode: vector ? 'vector' : 'hybrid', status: reply.status, body: reply.text.slice(0, 400),
    });
    return reply.status === 200 ? 'available-unmatched' : `unexpected-${reply.status}`;
  };
  const search = async (mode) => {
    try {
      return classify(await post(apiPort, '/api/knowledge/search',
        mode ? { query: marker, limit: 10, mode } : { query: marker, limit: 10 }, 10000), !!mode);
    } catch {
      // Engine restarts are transport outages, never counted as reloading refusals.
      return 'transport';
    }
  };
  const task = (async () => {
    while (running) {
      const at = performance.now();
      const outcome = await search('vector');
      // Default (hybrid) search must keep answering from its keyword legs while vectors refuse.
      const hybrid = await search(null);
      let encoders = null;
      try {
        const health = await request(apiPort, '/api/health', {}, 10000);
        if (health.status === 200) encoders = JSON.parse(health.text)?.components?.encoders?.state ?? null;
      } catch {
        encoders = null;
      }
      observations.push({ at, outcome, hybrid, encoders });
      if (running) await new Promise(resolve => setTimeout(resolve, 250));
    }
  })();
  return { recovered() {
    const lastRefusal = observations.findLastIndex(sample => sample.outcome === 'reloading');
    return lastRefusal >= 0
      && observations.slice(lastRefusal + 1).some(sample => sample.outcome === 'available');
  }, async stop() {
    if (result) return result;
    running = false;
    await task;
    result = summarizeSemanticAvailability(observations, started, performance.now(),
      unexpectedSamples);
    return result;
  } };
}

/** Keep pre-refusal availability distinct from semantic recovery after the final refusal. */
export function summarizeSemanticAvailability(observations, started, ended, unexpectedSamples) {
  const refusals = observations.filter(sample => sample.outcome === 'reloading');
  const firstRefusal = refusals[0]?.at;
  const lastRefusal = refusals.at(-1)?.at;
  const firstRecovery = observations.find(sample => sample.outcome === 'available'
    && firstRefusal != null && sample.at > lastRefusal)?.at;
  const transitionMs = Math.round(ended - started);
  const refusalWindowMs = firstRefusal == null ? 0
    : Math.round((firstRecovery ?? ended) - firstRefusal);
  // Whole-API outage (restart): every route answers 503 index.starting or not at all.
  const outage = observations.filter(sample => sample.outcome === 'index-starting'
    || sample.outcome === 'transport');
  const apiOutageWindowMs = outage.length === 0 ? 0
    : Math.round(outage.at(-1).at - outage[0].at);
  // D1-14 structural window: a vector refusal is legitimate only while `encoders` reports
  // RELOADING (one neighbouring sample of slack for the health read racing the transition).
  const encodersAt = index => observations[index]?.encoders;
  const refusalOutsideReloading = observations.filter((sample, index) =>
    sample.outcome === 'reloading' && ![index - 1, index, index + 1]
      .some(neighbour => encodersAt(neighbour) === 'RELOADING')).length;
  const inWindow = sample => firstRefusal != null && sample.at >= firstRefusal
    && sample.at <= (firstRecovery ?? ended);
  const hybridSampled = observations.filter(sample => sample.hybrid !== undefined);
  const hybridBreaksInRefusalWindow = hybridSampled.filter(sample => inWindow(sample)
    && sample.hybrid !== 'available').length;
  const reloadingStart = observations.find(sample => sample.encoders === 'RELOADING')?.at;
  const reloadingEnd = reloadingStart == null ? undefined
    : observations.find(sample => sample.at > reloadingStart && sample.encoders
      && sample.encoders !== 'RELOADING')?.at;
  return { transitionMs, refusalWindowMs,
    refusedFraction: transitionMs === 0 ? 0 : refusalWindowMs / transitionMs,
    sampledRequests: observations.length,
    reloadingRefusals: refusals.length,
    available: observations.filter(sample => sample.outcome === 'available').length,
    recoveredAfterRefusal: firstRecovery != null,
    indexStarting: observations.filter(sample => sample.outcome === 'index-starting').length,
    transport: observations.filter(sample => sample.outcome === 'transport').length,
    unexpected: observations.filter(sample => sample.outcome.startsWith('unexpected-')
      || sample.outcome === 'available-unmatched').length,
    apiOutageWindowMs,
    apiOutageSamples: outage.length,
    refusalOutsideReloading,
    reloadingIntervalMs: reloadingStart == null ? 0
      : Math.round((reloadingEnd ?? ended) - reloadingStart),
    hybridSampled: hybridSampled.length,
    hybridAvailable: hybridSampled.filter(sample => sample.hybrid === 'available').length,
    hybridBreaksInRefusalWindow,
    unexpectedSamples };
}

/**
 * D1-14 acceptance over one sampled in-place transition (owner decision 2026-09-27): vectors refuse
 * only inside `encoders` RELOADING and recover after it; hybrid keeps answering from keyword legs
 * throughout the refusal window. The whole-API outage is reported, never folded into the window.
 */
export function inPlaceSemanticViolations(semantic) {
  const violations = [];
  if (!(semantic.reloadingRefusals > 0)) violations.push('no reloading refusal observed');
  if (!semantic.recoveredAfterRefusal) violations.push('no vector recovery after the last refusal');
  if (!(semantic.refusalWindowMs > 0 && semantic.refusalWindowMs <= semantic.transitionMs)) {
    violations.push('refusal window outside the sampled transition');
  }
  if (semantic.unexpected !== 0) violations.push('unexplained vector responses');
  if (semantic.refusalOutsideReloading !== 0) violations.push('vector refused outside RELOADING');
  if (!(semantic.hybridSampled > 0)) violations.push('hybrid search was not sampled');
  if (semantic.hybridBreaksInRefusalWindow !== 0) {
    violations.push('hybrid search failed inside the refusal window');
  }
  return violations;
}

function besideSemanticViolations(semantic) {
  const violations = [];
  if (!(semantic.sampledRequests > 0)) violations.push('vector search was not sampled');
  if (semantic.reloadingRefusals !== 0 || semantic.available !== semantic.sampledRequests) {
    violations.push('vector search was unavailable during BESIDE composition');
  }
  if (!(semantic.hybridSampled > 0) || semantic.hybridAvailable !== semantic.hybridSampled) {
    violations.push('hybrid search was unavailable during BESIDE composition');
  }
  if (semantic.unexpected !== 0) violations.push('unexplained search responses');
  return violations;
}

/** A captured installer file disappears before B builds; A must regain native service at the wait. */
async function exerciseLiveModelGapDecision(c) {
  const { work, data, indexBase, manifest, apiPort, operationKey, sourceGeneration,
    sourceManifest, marker, file, bRoot, dispatched, readJson, waitFor, request, post,
    requireThat, createOperationKey, matchingHit, reachedFile, releaseFile,
    migrationBarrier, operationPath, gapCancellation, gapRecomposeFailure, semanticSampler,
    cudaABefore, engineLogWindow } = c;
  const sourceModel = gapRecomposeFailure
    ? path.resolve(sourceManifest.models.embedding.id) : null;
  const hiddenSourceModel = sourceModel ? `${sourceModel}.recompose-held` : null;
  const removed = path.join(work, 'installer-root-b', 'installer-1.txt');
  try {
    const reached = await waitFor('installer capture closed before Green build', 180000,
      () => readJson(reachedFile));
    const cut = snapshot({ operationPath, jobsPath: path.join(data, 'jobs.db'),
      indexBase, operationKey });
    requireThat(reached.phase === 'bulk-before-building-checkpoint'
      && reached.operationKey === operationKey
      && cut.walk?.enumeration_outcome === 'COMPLETE'
      && cut.walk.planned_units >= 2
      && cut.state.active_generation === sourceGeneration
      && cut.state.building_generation === `g-${operationKey}`
      && fs.existsSync(removed),
    `installer gap did not hold its captured source set: ${JSON.stringify({ reached, cut })}`);
    fs.unlinkSync(removed);
  } finally {
    fs.writeFileSync(releaseFile, 'release');
  }
  const dispatch = await dispatched.settled;
  requireThat(!dispatch.error, `installer gap dispatch failed: ${dispatch.error?.message}`);
  let floorEvidence;
  let waiting;
  let settledBCitation;
  let failedRecompose;
  try {
    try {
      const reached = await waitFor('installer Green drained before gap decision', 180000,
        () => readJson(migrationBarrier.reachedFile));
      const state = readJson(path.join(indexBase, 'state.json'));
      const reply = await request(apiPort, '/api/status', {}, 15000);
      const status = reply.status === 200 ? JSON.parse(reply.text) : null;
      const encoder = status?.readiness?.engineComponents?.encoders;
      requireThat(reached.point === 'migration-green-drained'
        && reached.buildingGeneration === `g-${operationKey}`
        && state?.active_generation === sourceGeneration
        && state?.migration_state === 'SWITCHING'
        && encoder?.mode === 'IN_PLACE'
        // D1-14 owner decision 2026-09-27: in place only when releasing A covers the shortfall.
        && encoder?.reason === 'candidate_fits_after_source_release'
        && encoder?.freeBytes <= 1024 * 1024
        && encoder?.footprintBytes > encoder?.freeBytes
        && status?.components?.encoders?.state === 'RELOADING',
      `installer gap missed the forced device line: ${JSON.stringify({ reached, state, encoder,
        component: status?.components?.encoders })}`);
      const text = await post(apiPort, '/api/knowledge/search',
        { query: marker, limit: 10, mode: 'text' }, 30000);
      requireThat(text.status === 200 && matchingHit(text, file, marker),
        `in-place A lost lexical service during B composition: ${text.text}`);
      floorEvidence = { mode: encoder.mode, freeBytes: encoder.freeBytes,
        footprintBytes: encoder.footprintBytes };
      if (sourceModel) {
        requireThat(sourceModel.startsWith(path.resolve(work, 'installer-models') + path.sep)
          && fs.existsSync(sourceModel) && !fs.existsSync(hiddenSourceModel),
        `A recompose failure would hide a non-private or absent model: ${sourceModel}`);
        fs.renameSync(sourceModel, hiddenSourceModel);
      }
    } finally {
      fs.writeFileSync(migrationBarrier.releaseFile, 'release');
    }
    waiting = await waitFor('installer gap keeps A active and B bound', 180000,
      async () => {
        const row = operationRows(operationPath, operationKey)[0];
        const state = readJson(path.join(indexBase, 'state.json'));
        if (row?.state === 'FAILED' || row?.state === 'CANCELLED') {
          throw new Error(`installer gap terminated before approval: ${row.failure_reason}`);
        }
        if (row?.state !== 'COMPLETE_WITH_GAPS' || row.phase !== 'settled'
          || state?.active_generation !== sourceGeneration
          || state?.building_generation !== `g-${operationKey}`
          || state?.migration_state !== 'AWAITING_ACCEPTANCE') return null;
        const reply = await request(apiPort, `/api/operation-history/${operationKey}`, {}, 15000);
        if (reply.status !== 200) return null;
        const outcome = parseJson(reply, 'installer gap outcome');
        return outcome.phase === 'awaiting_acceptance' && outcome.state === 'running'
          && /^[0-9a-f]{64}$/.test(outcome.result?.gapListHash ?? '')
          && outcome.result?.gaps?.some(gap => gap.unitId && gap.reason)
          ? { row, state, outcome } : null;
      });
    const settledBManifest = readJson(path.join(indexBase, 'indices', `g-${operationKey}`,
      '.justsearch-index-generation.json'));
    settledBCitation = citationIdentityFromManifest(
      settledBManifest, 'settled B', requireThat);
    requireThat(settledBCitation.modelPath.startsWith(path.resolve(bRoot) + path.sep),
      `settled B citation model escaped its private root: ${settledBCitation.modelPath}`);
    if (sourceModel) {
      failedRecompose = await waitFor('missing A model reports both refusal reasons',
        120000, async () => {
          const reply = await request(apiPort, '/api/status', {}, 15000);
          if (reply.status !== 200) return null;
          const component = JSON.parse(reply.text)?.readiness?.engineComponents?.encoders;
          return component?.state === 'UNAVAILABLE'
            && component.recoveryAttempts > 0
            && component.evidence?.includes('B refused: Candidate awaits gap acceptance')
            && component.evidence?.includes('A recompose refused:')
            ? component : null;
        });
      const text = await post(apiPort, '/api/knowledge/search',
        { query: marker, limit: 10, mode: 'text' }, 30000);
      requireThat(text.status === 200 && matchingHit(text, file, marker),
        `failed A recompose also lost lexical service: ${text.text}`);
    }
  } finally {
    if (hiddenSourceModel && fs.existsSync(hiddenSourceModel)) {
      requireThat(!fs.existsSync(sourceModel),
        `hidden A model cannot be restored over an occupied source path: ${sourceModel}`);
      fs.renameSync(hiddenSourceModel, sourceModel);
    }
  }
  let manualEncoderRecovery;
  if (gapRecomposeFailure) {
    requireThat(failedRecompose?.state === 'UNAVAILABLE'
      && failedRecompose.recoveryAttempts === 1,
    `manual encoder recovery requires the exact failed A restoration: ${JSON.stringify(failedRecompose)}`);
    const accepted = await request(apiPort, '/api/engine/components/encoders/recover', {
      method: 'POST', headers: sessionHeaders(manifest), body: '{}',
    }, 30000);
    const receipt = parseJson(accepted, 'manual encoder recovery');
    requireThat(accepted.status === 202 && receipt.component === 'encoders'
      && receipt.recovery === 'ACCEPTED',
    `manual encoder recovery was not accepted: HTTP ${accepted.status} ${accepted.text}`);
    const ready = await waitFor('manual encoder recovery restores exact A', 120000,
      async () => {
        const reply = await request(apiPort, '/api/status', {}, 15000);
        if (reply.status !== 200) return null;
        const component = JSON.parse(reply.text)?.readiness?.engineComponents?.encoders;
        return component?.state === 'READY'
          && component.recoveryAttempts === failedRecompose.recoveryAttempts + 1
          ? component : null;
      });
    manualEncoderRecovery = { receipt, before: failedRecompose, ready };
  }
  const aVector = await waitFor('recomposed A serves VECTOR during installer gap wait',
    120000, async () => {
      try {
        const reply = await post(apiPort, '/api/knowledge/search',
          { query: marker, limit: 10, mode: 'vector' }, 30000);
        return reply.status === 200 && JSON.parse(reply.text).results?.length > 0
          ? reply : null;
      } catch { return null; }
    });
  await waitFor('restored A has an active CPU citation scorer', 30000,
    () => readActiveCitation(apiPort, request));
  const restoredAManifest = readJson(path.join(indexBase, 'indices', sourceGeneration,
    '.justsearch-index-generation.json'));
  const sourceCitation = citationIdentityFromManifest(
    sourceManifest, 'initial A', requireThat);
  const restoredCitation = citationIdentityFromManifest(
    restoredAManifest, 'restored A', requireThat);
  requireThat(sameCitationIdentity(sourceCitation, restoredCitation),
    `restored A citation identity differs from initial A: ${JSON.stringify({
      sourceCitation, restoredCitation,
    })}`);
  const cudaARestored = cudaABefore
    ? await waitFor('restored A realizes CUDA after the gap', 60000,
      () => readRealizedCudaEmbedding(apiPort, request))
    : null;
  if (cudaABefore && cudaARestored) {
    console.log('MODEL_LIVE_AB_CUDA_A', JSON.stringify({
      before: cudaABefore.currentAccelerator,
      restored: cudaARestored.currentAccelerator,
      availableBefore: cudaABefore.available,
      availableRestored: cudaARestored.available,
    }));
  }
  const nativeLeaseProbe = process.env.JUSTSEARCH_RESTORED_A_NATIVE_LEASE_PROBE === '1';
  if (nativeLeaseProbe) {
    const reached = await waitFor('restored A issued a native CPU lease', 30000,
      () => readJson(path.join(data, 'runtime', 'restored-a-native-lease-reached.json')));
    requireThat(reached?.pid > 0 && reached.inputCount > 0,
      `restored A native lease probe did not hold a readable session: ${JSON.stringify(reached)}`);
  }
  const semantic = await semanticSampler?.stop();
  if (semantic) requireThat(inPlaceSemanticViolations(semantic).length === 0,
  `floor semantic sampling violated D1-14: ${JSON.stringify({
    violations: inPlaceSemanticViolations(semantic), semantic })}`);
  if (gapCancellation) {
    const cancellationKey = await cancelReindexWithApproval({ apiPort, manifest,
      reindexKey: operationKey, createOperationKey, request, post, requireThat });
    const retired = await waitFor('cancelled installer B retired with A serving', 180000,
      () => {
        const row = operationRows(operationPath, operationKey)[0];
        const state = readJson(path.join(indexBase, 'state.json'));
        if (row?.state !== 'CANCELLED' || row.failure_reason !== 'cancelled'
          || state?.active_generation !== sourceGeneration
          || state?.migration_state !== 'IDLE'
          || state?.building_generation != null
          || fs.existsSync(path.join(indexBase, 'indices', `g-${operationKey}`))) return null;
        const durable = row?.processing_history_counts_json
          ? JSON.parse(row.processing_history_counts_json) : null;
        const switchRows = readRows(path.join(data, 'jobs.db'),
          'SELECT key FROM switch_buffer WHERE generation = ?', `g-${operationKey}`);
        return durable?.refusalCode === 'cancelled' && switchRows.length === 0
          ? { row, state } : null;
      });
    const resumedA = await waitFor('A serves VECTOR after installer gap cancellation',
      120000, async () => {
        try {
          const reply = await post(apiPort, '/api/knowledge/search',
            { query: marker, limit: 10, mode: 'vector' }, 30000);
          return reply.status === 200 && JSON.parse(reply.text).results?.length > 0
            ? reply : null;
        } catch { return null; }
      });
    console.log('MODEL_LIVE_AB_CANCEL_PASS', JSON.stringify({ operationKey,
      cancellationKey, sourceGeneration, activeGeneration: retired.state.active_generation,
      floor: 'floor simulated by device-memory cap', floorEvidence,
      aVectorHitsDuringWait: JSON.parse(aVector.text).results.length,
      aVectorHitsAfterCancel: JSON.parse(resumedA.text).results.length,
      terminalState: retired.row.state, ...(semantic ? { semantic } : {}),
      ...(failedRecompose ? { failedRecompose } : {}),
      ...(manualEncoderRecovery ? { manualEncoderRecovery } : {}) }));
    return;
  }
  const acceptanceKey = createOperationKey();
  const acceptanceInput = { reindexKey: operationKey,
    gapListHash: waiting.outcome.result.gapListHash, idempotencyKey: acceptanceKey };
  const initial = await request(apiPort, ACCEPT_GAPS_ROUTE, {
    method: 'POST', headers: sessionHeaders(manifest), body: JSON.stringify(acceptanceInput),
  }, 30000);
  const pending = parseJson(initial, 'installer gap acceptance preparation');
  requireThat(initial.status === 428 && pending.operationKey === acceptanceKey
    && typeof pending.pendingId === 'string' && typeof pending.preparationNonce === 'string',
  `installer gap acceptance lacked a distinct prepared decision: ${initial.text}`);
  const approved = await post(apiPort, '/api/authorizations/approve',
    { pendingId: pending.pendingId }, 30000);
  const capsule = parseJson(approved, 'installer gap approval').capsule;
  requireThat(approved.status === 200 && typeof capsule === 'string',
    `installer gap approval failed: ${approved.text}`);
  const acceptAttempt = request(apiPort, ACCEPT_GAPS_ROUTE, {
    method: 'POST', headers: sessionHeaders(manifest), body: JSON.stringify({
      ...acceptanceInput, confirmationToken: capsule,
      preparationNonce: pending.preparationNonce,
    }),
  }, nativeLeaseProbe ? 120000 : 30000).then(value => ({ value }), error => ({ error }));
  if (nativeLeaseProbe) {
    const blocked = await waitFor('approved B waits for restored A native lease', 60000,
      async () => {
        const state = readJson(path.join(indexBase, 'state.json'));
        const reply = await request(apiPort, '/api/status', {}, 15000);
        const encoder = reply.status === 200
          ? JSON.parse(reply.text)?.components?.encoders : null;
        return state?.active_generation === sourceGeneration
          && encoder?.state === 'RELOADING' ? { state, encoder } : null;
      });
    requireThat(blocked.state.building_generation === `g-${operationKey}`,
      `B did not retain its exact candidate while A's native call was held: ${JSON.stringify(blocked)}`);
    fs.writeFileSync(path.join(data, 'runtime', 'restored-a-native-lease-release'), 'release');
    const proof = await waitFor('restored A native lease survived B retirement', 30000,
      () => readJson(path.join(data, 'runtime', 'restored-a-native-lease-proof.json')));
    requireThat(proof?.ok === true && proof.retirementStatus === 'RETIRING'
      && proof.inputCount > 0,
    `restored A native lease proof failed: ${JSON.stringify(proof)}`);
    console.log('MODEL_LIVE_AB_RESTORED_NATIVE_LEASE', JSON.stringify(proof));
  }
  const acceptedAttempt = await acceptAttempt;
  requireThat(!acceptedAttempt.error,
    `installer gap acceptance transport failed: ${acceptedAttempt.error?.message}`);
  const accept = acceptedAttempt.value;
  requireThat(accept.status === 200 && parseJson(accept, 'installer accepted gaps').success,
    `installer gap decision failed: ${accept.text}`);
  const promoted = await waitFor('approved installer gap promotes exact B', 180000, () => {
    const row = operationRows(operationPath, operationKey)[0];
    const state = readJson(path.join(indexBase, 'state.json'));
    return row?.state === 'FAILED' && row.failure_reason === 'PROMOTED_WITH_GAPS'
      && state?.active_generation === `g-${operationKey}`
      && state?.migration_state === 'IDLE' ? { row, state } : null;
  });
  const bManifest = readJson(path.join(indexBase, 'indices', `g-${operationKey}`,
    '.justsearch-index-generation.json'));
  requireThat(bManifest?.models?.embedding?.id?.startsWith(bRoot)
    && bManifest.models.embedding.sha256 !== sourceManifest.models.embedding.sha256,
  `installed gap promoted the wrong model set: ${JSON.stringify(bManifest?.models)}`);
  const promotedBCitation = citationIdentityFromManifest(
    bManifest, 'promoted B', requireThat);
  requireThat(sameCitationIdentity(settledBCitation, promotedBCitation),
    `promoted B citation identity differs from settled B: ${JSON.stringify({
      settledBCitation, promotedBCitation,
    })}`);
  const bVector = await waitFor('approved installer gap serves B VECTOR', 120000,
    async () => {
      try {
        const reply = await post(apiPort, '/api/knowledge/search',
          { query: marker, limit: 10, mode: 'vector' }, 30000);
        return reply.status === 200 && JSON.parse(reply.text).results?.length > 0
          ? reply : null;
      } catch { return null; }
    });
  await waitFor('promoted B has an active CPU citation scorer', 30000,
    () => readActiveCitation(apiPort, request));
  requireThat(engineLogWindow,
    'installed gap citation identity proof requires a current-run Engine log window');
  const citationLog = await waitFor('current-run citation identity log records A then B then A then B',
    30000, () => verifyCitationIdentityLog({ engineLogWindow, sourceCitation,
      settledBCitation, restoredCitation, promotedBCitation, requireThat }));
  console.log('MODEL_LIVE_AB_CITATION_IDENTITY', JSON.stringify({
    aPath: sourceCitation.modelPath,
    aSha256: sourceCitation.sha256,
    bPath: settledBCitation.modelPath,
    bSha256: settledBCitation.sha256,
    restoredAPath: restoredCitation.modelPath,
    restoredASha256: restoredCitation.sha256,
    verified: citationLog.verified,
  }));
  console.log('MODEL_LIVE_AB_GAP_PASS', JSON.stringify({ operationKey, acceptanceKey,
    sourceGeneration, promotedGeneration: promoted.state.active_generation,
    floor: 'floor simulated by device-memory cap', floorEvidence,
    aVectorHits: JSON.parse(aVector.text).results.length,
    bVectorHits: JSON.parse(bVector.text).results.length,
    gapListHash: waiting.outcome.result.gapListHash,
    terminalReason: promoted.row.failure_reason, ...(semantic ? { semantic } : {}) }));
}

async function readRealizedCudaEmbedding(apiPort, request) {
  try {
    const response = await request(apiPort, '/api/inference/encoders', {}, 15000);
    if (response.status !== 200) return null;
    const embed = JSON.parse(response.text)?.encoders?.embed;
    return embed?.currentAccelerator === 'cuda' && embed.available === true
      ? { currentAccelerator: embed.currentAccelerator, available: embed.available }
      : null;
  } catch {
    return null;
  }
}

async function readActiveCitation(apiPort, request) {
  try {
    const [statusResponse, runtimeResponse] = await Promise.all([
      request(apiPort, '/api/status', {}, 15000),
      request(apiPort, '/api/inference/encoders', {}, 15000),
    ]);
    if (statusResponse.status !== 200 || runtimeResponse.status !== 200) return null;
    const status = JSON.parse(statusResponse.text);
    const runtime = JSON.parse(runtimeResponse.text);
    const citation = runtime?.encoders?.citation;
    return status?.components?.encoders?.state === 'READY'
      && runtime?.snapshotStatus === 'ok'
      && citation?.available === true
      && citation?.configuredAccelerator === 'CPU'
      && citation?.currentAccelerator === 'cpu' ? citation : null;
  } catch {
    return null;
  }
}

function citationIdentityFromManifest(manifest, label, requireThat) {
  const artifact = manifest?.models?.['citation-scorer'];
  requireThat(typeof artifact?.id === 'string' && path.isAbsolute(artifact.id)
    && /^[0-9a-f]{64}$/i.test(artifact?.sha256 ?? ''),
  `${label} lacks a full citation model identity: ${JSON.stringify(artifact)}`);
  return {
    modelPath: path.normalize(path.resolve(artifact.id)),
    sha256: artifact.sha256.toLowerCase(),
  };
}

function sameCitationIdentity(left, right) {
  return left.modelPath === right.modelPath && left.sha256 === right.sha256;
}

function engineLogIdentity(stat) {
  return { dev: stat.dev, ino: stat.ino, birthtimeMs: stat.birthtimeMs };
}

function readEngineLogWindow(window, requireThat) {
  requireThat(fs.existsSync(window.file),
    `current-run Engine log does not exist: ${window.file}`);
  const stat = fs.statSync(window.file);
  const identity = engineLogIdentity(stat);
  const expectedIdentity = window.initialIdentity ?? window.observedIdentity;
  requireThat(expectedIdentity == null || sameJson(identity, expectedIdentity),
    `current-run Engine log rolled after capture: ${JSON.stringify({
      file: window.file, expectedIdentity, identity,
    })}`);
  if (window.observedIdentity == null) window.observedIdentity = identity;
  requireThat(stat.size >= window.startOffset,
    `current-run Engine log shrank after capture: ${JSON.stringify({
      file: window.file, startOffset: window.startOffset, size: stat.size,
    })}`);
  const directory = path.dirname(window.file);
  const rolledFiles = fs.existsSync(directory)
    ? Object.fromEntries(fs.readdirSync(directory)
      .filter(name => /^engine\..+\.log\.gz$/.test(name))
      .sort()
      .map(name => {
        const rolled = fs.statSync(path.join(directory, name));
        return [name, { size: rolled.size, mtimeMs: rolled.mtimeMs }];
      }))
    : {};
  requireThat(sameJson(rolledFiles, window.rolledFiles),
    `current-run Engine log rolled after capture: ${JSON.stringify({
      before: window.rolledFiles, after: rolledFiles,
    })}`);
  const bytes = fs.readFileSync(window.file);
  requireThat(bytes.length >= window.startOffset,
    `current-run Engine log shrank while reading: ${JSON.stringify({
      file: window.file, startOffset: window.startOffset, size: bytes.length,
    })}`);
  return bytes.subarray(window.startOffset).toString('utf8');
}

function verifyCitationIdentityLog({ engineLogWindow, sourceCitation, settledBCitation,
  restoredCitation, promotedBCitation, requireThat }) {
  const contents = readEngineLogWindow(engineLogWindow, requireThat);
  const messages = contents.split(/\r?\n/).filter(Boolean).flatMap(line => {
    try {
      const message = JSON.parse(line)?.message;
      return typeof message === 'string' ? [message] : [];
    } catch {
      requireThat(!line.includes('Citation scorer generation selected:'),
        `citation identity evidence was not valid Engine log JSON: ${line}`);
      return [];
    }
  });
  const legacy = messages.filter(message =>
    /^Citation scorer wired: model=.*sha256=[0-9a-f]{16}\.\.\.$/i.test(message));
  requireThat(legacy.length === 0,
    `legacy truncated citation consumer SHA remained in the Engine log: ${JSON.stringify(legacy)}`);
  const prefix = 'Citation scorer generation selected: model=';
  const tuples = messages.filter(message => message.startsWith(prefix)).map(message => {
    const match = /^Citation scorer generation selected: model=(.+), sha256=([0-9a-f]{64})$/i
      .exec(message);
    requireThat(match,
      `citation generation identity was not an exact normalized path plus full SHA: ${message}`);
    return { modelPath: match[1], sha256: match[2].toLowerCase() };
  });
  const expected = [sourceCitation, settledBCitation, restoredCitation, promotedBCitation];
  if (tuples.length < expected.length) return null;
  requireThat(tuples.length === expected.length
    && tuples.every((tuple, index) => sameCitationIdentity(tuple, expected[index])),
    `current-run citation log differs from exact A→B→A→B composition: ${JSON.stringify({
      tuples, expected,
    })}`);
  return { verified: true };
}

function linkRegularFiles(source, target) {
  fs.mkdirSync(target, { recursive: true });
  for (const entry of fs.readdirSync(source, { withFileTypes: true })) {
    const from = path.join(source, entry.name);
    const to = path.join(target, entry.name);
    if (entry.isDirectory()) linkRegularFiles(from, to);
    else if (entry.isFile()) fs.linkSync(from, to);
    else throw new Error(`side-by-side fixture refuses non-regular model asset: ${from}`);
  }
}

async function prepareApprovedActivationDispatch({ apiPort, input, operationKey, post,
  requireThat, allowPreflightApproval = false }) {
  let response = await post(apiPort, ACTIVATION_ROUTE,
    { args: input, idempotencyKey: operationKey }, 90000);
  requireThat(response.status === 428,
    `installer activation must exercise prepared approval: HTTP ${response.status} ${response.text}`);
  let pending = parseJson(response, 'installer activation preparation');
  if (pending.preparationNonce == null && typeof pending.pendingId === 'string') {
    const preflightPendingId = pending.pendingId;
    requireThat(allowPreflightApproval,
      `fresh activation unexpectedly requested approval before preparation: ${response.text}`);
    const approval = await post(apiPort, '/api/authorizations/approve',
      { pendingId: pending.pendingId });
    const approved = parseJson(approval, 'unprepared installer approval diagnostic');
    requireThat(approval.status === 200 && typeof approved.capsule === 'string',
      `initial activation approval failed: ${approval.text}`);
    response = await post(apiPort, ACTIVATION_ROUTE, {
      args: input, idempotencyKey: operationKey, confirmationToken: approved.capsule,
    }, 90000);
    pending = parseJson(response, 'prepared activation after initial approval');
    requireThat(response.status === 428 && typeof pending.preparationNonce === 'string'
      && pending.pendingId !== preflightPendingId,
      `activation reused preflight authority instead of requiring prepared approval: ${response.text}`);
  }
  requireThat(typeof pending.pendingId === 'string' && typeof pending.preparationNonce === 'string'
    && pending.operationKey === operationKey,
  `installer activation preparation omitted exact key/nonce binding: ${response.text}`);
  const approval = await post(apiPort, '/api/authorizations/approve', { pendingId: pending.pendingId });
  const approved = parseJson(approval, 'installer activation approval');
  requireThat(approval.status === 200 && typeof approved.capsule === 'string',
    `installer activation approval failed: ${approval.text}`);
  return { nonce: pending.preparationNonce, capsule: approved.capsule };
}

async function preparedActivationPost({ apiPort, input, operationKey, post, requireThat }) {
  const response = await post(apiPort, ACTIVATION_ROUTE,
    { args: input, idempotencyKey: operationKey }, 90000);
  if (response.status !== 428) return response;
  const pending = parseJson(response, 'installer activation replay preparation');
  requireThat(pending.operationKey === operationKey
    && typeof pending.pendingId === 'string' && typeof pending.preparationNonce === 'string',
  `installer activation replay preparation lost its identity: ${response.text}`);
  const approval = await post(apiPort, '/api/authorizations/approve', { pendingId: pending.pendingId });
  const approved = parseJson(approval, 'installer activation replay approval');
  requireThat(approval.status === 200 && typeof approved.capsule === 'string',
    `installer activation replay approval failed: ${approval.text}`);
  return post(apiPort, ACTIVATION_ROUTE, { args: input, idempotencyKey: operationKey,
    confirmationToken: approved.capsule, preparationNonce: pending.preparationNonce });
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
  requireThat((selected.liveStart || row.id === reached.operationRecordId)
    && row.operation_key === operationKey
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

function assertSelectedCut({ selected, cut, files, hashes, capturedReplay, operationKey, requireThat }) {
  const target = `g-${operationKey}`;
  const row = cut.operation;
  if (selected.liveStart) {
    requireThat(row.phase === 'building' && row.building_generation_id === target
      && cut.walk?.captured_plan === 1 && cut.walk.enumeration_outcome === 'COMPLETE'
      && cut.walk.enumeration_closed_at != null && cut.walk.manifest_sha256
      && cut.walk.planned_units === 2 && cut.walk.sealed_at == null,
    `live Green cut must retain its complete BUILDING plan: ${JSON.stringify(cut)}`);
    requireExactMembers(cut.jobs, files, hashes, false, requireThat);
    requireThat(cut.state.active_generation !== target
      && cut.state.building_generation === target && cut.state.migration_state === 'MIGRATING',
    `live Green cut must retain the exact MIGRATING target: ${JSON.stringify(cut.state)}`);
    const generation = cut.generationManifest;
    requireThat(generation?.generation_id === target
      && generation.source === preparationPlan(row.preparation_payload).source
      && generation.target_index_fingerprint === preparationPlan(row.preparation_payload).target.fingerprint,
    `live Green metadata is not operation-derived: ${JSON.stringify(generation)}`);
    return;
  }
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
    if (selected.capturedEdit) {
      const capturedMember = cut.jobs.find(member => samePath(member.path, files[0]));
      requireThat(capturedReplay?.unitRevision != null
          && capturedMember?.unit_revision === capturedReplay.unitRevision
          && capturedMember.planned_source_sha256 === hashes[0]
          && capturedReplay.h1?.sha256 === hashes[0]
          && capturedReplay.h2?.sha256 !== hashes[0],
        `captured H1 cut lost its original unit revision or source witness: ${JSON.stringify({
          capturedMember, capturedReplay,
        })}`);
    }
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

function assertFinal({ final, cut, prepared, files, hashes, capturedReplay, operationKey,
  selected, requireThat }) {
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
  const expectedSupersededEvents = selected.capturedEdit ? 1 : 0;
  requireThat(queueReceipt.version === 2 && queueReceipt.plannedUnits === 2
    && queueReceipt.manifestSha256 === final.walk.manifest_sha256
    && queueReceipt.gapCount === 0 && queueReceipt.failedEvents === 0
    && queueReceipt.supersededEvents === expectedSupersededEvents
    && final.walk.acknowledged_revision === final.walk.revision,
  `bulk recovery did not seal and ACK its exact successful receipt: ${JSON.stringify(final.walk)}`);
  // D1-9's captured-gap witness widened the operation projection to v3. This
  // fresh successful operation must persist the exact empty gap set alongside
  // the unchanged queue v2 receipt and settlement hash binding.
  requireThat(operationEvidence.version === 3
    && operationEvidence.targetFingerprint === plan.target.fingerprint
    && operationEvidence.capture?.manifestSha256 === final.walk.manifest_sha256
    && operationEvidence.capture?.plannedUnits === 2
    && operationEvidence.sealedRevision === final.walk.revision
    && operationEvidence.settlementSha256 === queueReceipt.settlementSha256
    && operationEvidence.failedEvents === 0
    && operationEvidence.supersededEvents === expectedSupersededEvents
    && operationEvidence.refusalCode == null
    && Array.isArray(operationEvidence.capturedGaps) && operationEvidence.capturedGaps.length === 0,
  `operation checkpoint is not bound to the exact queue settlement: ${JSON.stringify(operationEvidence)}`);
  if (selected.capturedEdit) {
    requireCapturedH2Settlement({ observed: final, file: files[0],
      hashes: { planned: hashes[0], committed: capturedReplay.h2.sha256 }, requireThat,
      label: 'after recovery', expectedUnitRevision: capturedReplay.unitRevision });
    const unchanged = final.jobs.find(member => samePath(member.path, files[1]));
    requireThat(unchanged?.state === 'DONE'
        && unchanged.planned_source_sha256 === hashes[1]
        && unchanged.content_hash === hashes[1],
      `captured replay changed an unrelated member: ${JSON.stringify(unchanged)}`);
  } else {
    requireExactMembers(final.jobs, files, hashes, true, requireThat);
  }
  requireThat(cut.jobs.every(before => final.jobs.some(after => samePath(after.path, before.path)
    && after.unit_revision === before.unit_revision
    && after.planned_source_sha256 === before.planned_source_sha256)),
  'recovery replaced an already captured member instead of retaining its original revision/H1');
  if (cut.walk.manifest_sha256 != null) requireThat(
    final.walk.manifest_sha256 === cut.walk.manifest_sha256,
    'recovery changed the already closed capture manifest');
  const supersededLedger = selected.capturedEdit
    ? final.ledger.filter(event => event.terminal_coverage === 'INDEXED'
      && event.planned_source_sha256 === capturedReplay.h1.sha256
      && event.content_hash === capturedReplay.h2.sha256)
    : [];
  requireThat(final.ledger.length === 2
    && (!selected.capturedEdit ? final.ledger.every(event => event.terminal_coverage === 'INDEXED'
      && event.content_hash === event.planned_source_sha256)
      : supersededLedger.length === 1
        && final.ledger.every(event => event.terminal_coverage === 'INDEXED')),
  `bulk recovery lacks exact indexed ledger coverage: ${JSON.stringify(final.ledger)}`);
  requireThat(final.recordedGenerations.length === 1 && final.recordedGenerations[0] === target,
    `bulk recovery created more than its one operation-derived target: ${JSON.stringify(final.recordedGenerations)}`);
}

function assertInstallerCut({ cut, reached, prepared, operationKey, selected, sourceGeneration,
  roots, beforeSettings, candidate, requireThat }) {
  requireThat(cut.operations.length === 1 && cut.reindexOperations.length === 1,
    `installer fault cut must retain exactly one reindex row: ${JSON.stringify(cut.reindexOperations)}`);
  const row = cut.operation;
  requireThat(selected.phase === 'installer-before-marker'
      ? row.accepted_settings_revision == null
      : row.accepted_settings_revision === beforeSettings.witness.acceptedRevision,
  `activation fault cut has the wrong settings marker: ${row.accepted_settings_revision}`);
  requireThat(row.id === reached.operationRecordId && row.operation_key === operationKey
    && row.kind === 'reindex' && row.operation_ref === 'core.activate-installed-models'
    && ['RUNNING', 'COMPLETE'].includes(row.state) && row.attempts >= selected.cutAttempts
    && row.preparation_nonce === prepared.nonce,
  `installer fault cut lost its accepted prepared operation: ${JSON.stringify(row)}`);
  const plan = preparationPlan(row.preparation_payload);
  requireThat(plan.profile === 'INSTALLER_GENERATION'
    && plan.source === 'installer_model_activation'
    && plan.operationId === 'core.activate-installed-models'
    && plan.sourceGeneration === sourceGeneration
    && plan.scope?.roots?.length === roots.length
    && plan.scope.roots.every((root, index) => samePath(root.path, roots[index])),
  `accepted activation preparation is not the original frozen installer plan: ${JSON.stringify(plan)}`);
  requireThat(Array.isArray(plan.models) && plan.models.length > 0
    && Array.isArray(plan.assets) && plan.assets.length > 0
    && plan.models.some(model => samePath(model.path, candidate.modelPath)),
  `accepted activation preparation did not retain the staged model identity: ${JSON.stringify(plan)}`);
  assertInstallerChatSelection({ plan, preparationPayload: row.preparation_payload,
    cut, candidate, requireThat, label: 'cut' });
  const target = `g-${operationKey}`;
  const pointerPublished = selected.phase === 'installer-pointer-before-settings'
    || selected.phase === 'installer-settings-before-publication'
    || selected.phase === 'installer-before-receipt';
  const settingsPublished = selected.phase === 'installer-settings-before-publication'
    || selected.phase === 'installer-before-receipt';
  requireThat(pointerPublished ? cut.state.active_generation === target
      : cut.state.active_generation !== target,
  `activation fault cut has the wrong pointer witness: ${JSON.stringify(cut.state)}`);
  requireThat(settingsPublished
      ? cut.settings.witness.acceptedRevision === beforeSettings.witness.acceptedRevision + 1
        && cut.settings.witness.lastCommittedOperationKey === operationKey
      : cut.settings.witness.acceptedRevision === beforeSettings.witness.acceptedRevision
        && cut.settings.witness.lastCommittedOperationKey === beforeSettings.witness.lastCommittedOperationKey,
  `activation fault cut has the wrong settings witness: ${JSON.stringify(cut.settings)}`);
}

function assertInstallerSelectedCut({ selected, cut, operationKey, requireThat }) {
  const target = `g-${operationKey}`;
  const row = cut.operation;
  if (selected.phase === 'installer-before-marker') {
    requireThat(row.phase === 'settled' && row.building_generation_id === target
      && row.accepted_settings_revision == null && cut.state.active_generation !== target,
    `before-marker cut must retain settled B without the durable settings marker: ${row.phase}/${row.accepted_settings_revision}/${cut.state.active_generation}`);
    return;
  }
  if (selected.phase === 'installer-before-arm') {
    requireThat(row.phase === 'settled' && row.building_generation_id === target
      && row.units_failed === 0 && row.checkpoint_cursor?.startsWith('bulk-receipt:'),
    `before-arm cut must retain settled B before pointer commitment: ${row.phase}/${row.building_generation_id}/${row.checkpoint_cursor}`);
    requireThat(cut.state.active_generation !== target,
      `before-arm cut must retain source pointer: ${JSON.stringify(cut.state)}`);
    return;
  }
  if (selected.phase === 'installer-before-pointer') {
    requireThat(cut.state.active_generation !== target,
      `before-pointer cut must retain source pointer: ${JSON.stringify(cut.state)}`);
    return;
  }
  if (selected.phase === 'installer-pointer-before-settings') {
    requireThat(cut.state.active_generation === target
      && cut.settings.witness.lastCommittedOperationKey == null,
    `pointer-before-settings cut must expose pointer B with settings A: ${JSON.stringify(cut)}`);
    return;
  }
  if (selected.phase === 'installer-settings-before-publication') {
    requireThat(cut.state.active_generation === target
      && cut.settings.witness.lastCommittedOperationKey === operationKey,
    `settings-before-publication cut must expose pointer B and settings B: ${JSON.stringify(cut)}`);
    return;
  }
  requireThat(cut.state.active_generation === target
    && cut.settings.witness.lastCommittedOperationKey === operationKey
    && cut.walk?.receipt_json != null && row.result_json == null,
  `before-receipt cut must retain committed pointer/settings and the queue settlement before the operation result: ${cut.state.active_generation}/${cut.settings.witness.lastCommittedOperationKey}/${Boolean(cut.walk?.receipt_json)}/${Boolean(row.result_json)}`);
}

function assertInstallerFinal({ final, cut, prepared, files, hashes, operationKey, selected,
  sourceGeneration, beforeSettings, candidate, requireThat }) {
  const target = `g-${operationKey}`;
  const row = final.operation;
  const receipt = parseStoredJson(row.result_json, 'installer activation result');
  const plan = preparationPlan(row.preparation_payload);
  requireThat(final.operations.length === 1 && final.reindexOperations.length === 1
    && row.id === cut.operation.id && row.operation_key === operationKey
    && row.kind === 'reindex' && row.operation_ref === 'core.activate-installed-models'
    && row.state === 'COMPLETE' && row.phase === 'settled'
    && row.building_generation_id === target && row.attempts === selected.finalAttempts
    && row.units_failed === 0 && receipt.code === 'SUCCESS',
  `installer activation terminal row mismatch: ${row.state}/${row.phase}/${row.attempts}/${row.units_failed}`);
  requireThat(row.preparation_nonce === prepared.nonce
    && row.preparation_payload === cut.operation.preparation_payload,
  'installer activation changed the accepted nonce or frozen preparation');
  requireThat(final.state.active_generation === target && !final.state.building_generation
    && final.state.migration_state === 'IDLE',
  `installer activation did not serve the exact target: ${JSON.stringify(final.state)}`);
  requireThat(final.settings.witness.acceptedRevision === beforeSettings.witness.acceptedRevision + 1
    && final.settings.witness.lastCommittedOperationKey === operationKey
    && final.settings.settings?.embedOnnxModelPath === candidate.modelDir
    && (!candidate.mixedChat
      || samePath(final.settings.settings?.llmModelPath, candidate.chatModelPath)),
  `installer activation did not commit the exact settings witness: ${JSON.stringify(final.settings)}`);
  requireThat(final.walk.acknowledged_revision === final.walk.revision
    && final.walk.sealed_at != null && final.walk.receipt_json,
  `installer activation did not seal and acknowledge its queue receipt: ${JSON.stringify(final.walk)}`);
  requireThat(plan.profile === 'INSTALLER_GENERATION'
    && plan.source === 'installer_model_activation'
    && plan.sourceGeneration === sourceGeneration
    && plan.models.some(model => samePath(model.path, candidate.modelPath)),
  `installer activation final plan lost the retained candidate: ${JSON.stringify(plan)}`);
  assertInstallerChatSelection({ plan, preparationPayload: row.preparation_payload,
    final, candidate, requireThat, label: 'final' });
  requireExactMembers(final.jobs, files, hashes, true, requireThat);
  requireThat(final.recordedGenerations.length === 1 && final.recordedGenerations[0] === target,
    `installer activation created more than its one operation-derived target: ${JSON.stringify(final.recordedGenerations)}`);
}

function assertInstallerChatSelection({ plan, preparationPayload, cut, final, candidate,
  requireThat, label }) {
  if (!candidate.mixedChat) return;
  const envelope = parseStoredJson(preparationPayload, `${label} installer preparation envelope`);
  requireThat(envelope.preparation?.replaySchema === 'recorded-installer-generation-v4',
    `${label} installer activation did not retain the v4 generation plan: ${JSON.stringify(envelope)}`);
  const selection = plan.chatSelection;
  requireThat(selection?.modelAssetId === candidate.chatModelAssetId
      && Array.isArray(selection.companionAssetIds)
      && JSON.stringify(selection.companionAssetIds) === JSON.stringify(candidate.chatCompanionAssetIds),
  `${label} installer activation lost the explicit chat asset selection: ${JSON.stringify(selection)}`);
  const selectedAssets = [candidate.chatModelIdentity, ...candidate.chatCompanionIdentities];
  requireThat(selectedAssets.every(expected => plan.assets.some(asset => asset.assetId === expected.assetId
      && samePath(asset.path, expected.path)
      && asset.sha256 === expected.sha256
      && asset.sizeBytes === expected.sizeBytes)),
  `${label} installer activation did not retain the registry verified chat identities: ${JSON.stringify({
    selection, assets: plan.assets, expected: selectedAssets,
  })}`);
  const settings = (final ?? cut)?.settings?.settings;
  if (final != null) {
    requireThat(samePath(settings?.llmModelPath, candidate.chatModelPath),
      `${label} installer activation omitted the private selected chat model path: ${JSON.stringify(settings)}`);
  } else if (settings?.llmModelPath != null) {
    requireThat(samePath(settings.llmModelPath, candidate.chatModelPath),
      `${label} installer activation published the wrong chat model path: ${settings.llmModelPath}`);
  }
}

export function writeRetainedInstallerCandidate({ data, requireThat,
  mixedChat = process.env.JUSTSEARCH_WRITER_RECOVERY_MIXED_CHAT === '1',
  candidateOwned = false }) {
  const modelsRoot = findRetainedModelsRoot();
  requireThat(modelsRoot, 'installer activation requires retained real model bytes under a models/ root');
  // Stage one coherent standard-model candidate. The local embedding manifest is generated for
  // runtime use and differs from the installer asset, so restore its registry bytes only in this
  // private fixture. Hard links retain the large model bytes without touching another checkout.
  const candidateRoot = path.join(path.dirname(data), 'installer-models');
  const registry = JSON.parse(fs.readFileSync(path.join(process.cwd(),
    'modules', 'configuration', 'src', 'main', 'resources', 'ai', 'model-registry.v2.json'), 'utf8'));
  const registryManifest = [
    '{',
    '  "cpu": "model.onnx",',
    '  "gpu": "model_fp16.onnx",',
    '  "tokenizer": "tokenizer.json",',
    '  "pooling_config": "pooling_config.json"',
    '}',
    '',
  ].join('\n');
  requireThat(sha256(Buffer.from(registryManifest))
    === '9df8c6ed2d15a686eeb15e080971670919966de2812daf440b4469576b33157d',
  'embedded installer fixture manifest differs from the shipped registry');
  const installedModels = {};
  const candidateDirs = {};
  const targetEP = mixedChat ? 'CUDA' : 'CPU';
  const downloadProfile = mixedChat ? 'GPU_FULL' : 'CPU';
  for (const packageId of ['embedding', 'ner', 'splade', 'citation-scorer']) {
    const pkg = registry.packages.find(entry => entry.id === packageId);
    const packageTargetEP = packageId === 'citation-scorer' ? 'CPU' : targetEP;
    const variant = pkg?.variants.find(entry => entry.targetEP === packageTargetEP);
    requireThat(pkg && variant,
      `installer fixture lacks ${packageTargetEP} registry variant for ${packageId}`);
    const sourceDir = path.join(modelsRoot, pkg.targetDir);
    const stagedDir = path.join(candidateRoot, pkg.targetDir);
    fs.mkdirSync(stagedDir, { recursive: true });
    const required = pkg.supportingFiles.filter(file => file.required !== false);
    for (const file of [{ filename: variant.filename, sha256: variant.sha256,
      sizeBytes: variant.sizeBytes }, ...required]) {
      const staged = path.join(stagedDir, file.filename);
      if (packageId === 'embedding' && file.filename === 'model_manifest.json') {
        fs.writeFileSync(staged, registryManifest);
      } else {
        fs.linkSync(path.join(sourceDir, file.filename), staged);
      }
      requireThat(fs.statSync(staged).size === file.sizeBytes
        && sha256File(staged) === file.sha256.toLowerCase(),
      `retained installer ${packageId}/${file.filename} differs from the shipped registry`);
    }
    // Runtime manifest selection is separate from the install contract's required assets.
    const runtimeManifest = path.join(sourceDir, 'model_manifest.json');
    if (packageId !== 'embedding' && fs.existsSync(runtimeManifest)) {
      fs.linkSync(runtimeManifest, path.join(stagedDir, 'model_manifest.json'));
    }
    if (candidateOwned && (packageId === 'embedding' || packageId === 'citation-scorer')) {
      // Mirror InstallPlanner.effectiveTargetDir only for fixture placement. Its public plan
      // preview must still prove every exact registry byte is already present before install.
      const identity = (key, value) => {
        const normalized = key.endsWith('sha256') ? value.toUpperCase() : value;
        return `${key}=${normalized.length}:${normalized}\n`;
      };
      let input = identity('package', packageId)
        + identity('target', pkg.targetDir)
        + identity('model', variant.filename)
        + identity('model-sha256', variant.sha256);
      for (const file of [...pkg.supportingFiles].sort((a, b) =>
        a.filename < b.filename ? -1 : a.filename > b.filename ? 1 : 0)) {
        input += identity('supporting', file.filename)
          + identity('supporting-sha256', file.sha256);
      }
      const targetDir = `${pkg.targetDir}/candidates/${sha256(Buffer.from(input))}`;
      const candidateDir = path.join(candidateRoot, targetDir);
      fs.mkdirSync(candidateDir, { recursive: true });
      for (const file of [{ filename: variant.filename, sha256: variant.sha256,
        sizeBytes: variant.sizeBytes }, ...pkg.supportingFiles]) {
        const sourceFile = fs.existsSync(path.join(stagedDir, file.filename))
          ? path.join(stagedDir, file.filename) : path.join(sourceDir, file.filename);
        const target = path.join(candidateDir, file.filename);
        fs.linkSync(sourceFile, target);
        requireThat(fs.statSync(target).size === file.sizeBytes
          && sha256File(target) === file.sha256.toLowerCase(),
        `candidate-owned ${packageId}/${file.filename} differs from the shipped registry`);
      }
      candidateDirs[packageId] = path.resolve(candidateDir);
    }
    installedModels[packageId] = {
      packageId, variantFilename: variant.filename, precision: variant.precision,
      targetEP: variant.targetEP,
      targetDir: candidateDirs[packageId]
        ? path.relative(candidateRoot, candidateDirs[packageId]).replaceAll('\\', '/')
        : pkg.targetDir,
      sha256: variant.sha256,
      installedFiles: [variant.filename,
        ...(candidateDirs[packageId] ? pkg.supportingFiles : required)
          .map(file => file.filename)],
      skipped: false, skipReason: null, skipCause: null,
    };
  }
  const chatIdentity = mixedChat ? (() => {
    const pkg = registry.packages.find(entry => entry.id === 'chat');
    const variant = pkg?.variants.find(entry => entry.targetEP === 'LLAMA_SERVER');
    requireThat(pkg && variant, 'installer fixture lacks the registry chat variant');
    const sourceDir = path.join(modelsRoot, pkg.targetDir ?? '');
    const stagedDir = path.join(candidateRoot, pkg.targetDir ?? '');
    fs.mkdirSync(stagedDir, { recursive: true });
    const required = pkg.supportingFiles.filter(file => file.required !== false);
    const identities = [];
    for (const file of [{ filename: variant.filename, sha256: variant.sha256,
      sizeBytes: variant.sizeBytes }, ...required]) {
      const staged = path.join(stagedDir, file.filename);
      fs.linkSync(path.join(sourceDir, file.filename), staged);
      requireThat(fs.statSync(staged).size === file.sizeBytes
        && sha256File(staged) === file.sha256.toLowerCase(),
      `retained installer chat/${file.filename} differs from the shipped registry`);
      identities.push({ assetId: `chat/${file.filename}`, path: path.resolve(staged),
        sha256: file.sha256.toLowerCase(), sizeBytes: file.sizeBytes });
    }
    return { pkg, variant, identities };
  })() : null;
  const modelDir = candidateOwned ? candidateDirs.embedding
    : path.join(candidateRoot, 'onnx', 'gte-multilingual-base');
  const modelPath = path.join(modelDir, mixedChat ? 'model_fp16.onnx' : 'model.onnx');
  const installedFiles = installedModels.embedding.installedFiles;
  if (chatIdentity) {
    const [model, ...companions] = chatIdentity.identities;
    installedModels.chat = {
      packageId: 'chat', variantFilename: chatIdentity.variant.filename,
      precision: chatIdentity.variant.precision, targetEP: chatIdentity.variant.targetEP,
      targetDir: chatIdentity.pkg.targetDir ?? '', sha256: chatIdentity.variant.sha256,
      installedFiles: chatIdentity.identities.map(identity => path.basename(identity.path)),
      skipped: false, skipReason: null, skipCause: null,
    };
    chatIdentity.model = model;
    chatIdentity.companions = companions;
  }
  const contract = {
    schemaVersion: 2,
    installedAtEpochMs: Date.now(),
    hardwareProfile: mixedChat
      ? { gpuDetected: true, cudaFunctional: true, vramBytes: 12 * 1024 * 1024 * 1024 }
      : { gpuDetected: false, cudaFunctional: false, vramBytes: -1 },
    downloadProfile,
    modelsDir: path.resolve(candidateRoot),
    models: installedModels,
  };
  const contractPath = path.join(data, 'install-contract.v2.json');
  fs.writeFileSync(contractPath, `${JSON.stringify(contract, null, 2)}\n`);
  const chatModelIdentity = chatIdentity?.model ?? null;
  const chatCompanionIdentities = chatIdentity?.companions ?? [];
  return { contractPath, modelsRoot: path.resolve(candidateRoot), modelPath,
    modelDir: path.resolve(modelDir), installedFiles, mixedChat,
    candidateDirs,
    chatModelPath: chatModelIdentity?.path ?? null,
    chatModelAssetId: chatModelIdentity?.assetId ?? null,
    chatCompanionAssetIds: chatCompanionIdentities.map(identity => identity.assetId),
    chatModelIdentity, chatCompanionIdentities };
}

function findRetainedModelsRoot() {
  const candidates = [];
  let current = path.resolve(process.cwd());
  for (;;) {
    candidates.push(path.join(current, 'models'));
    const parent = path.dirname(current);
    if (parent === current) break;
    current = parent;
  }
  return candidates.find(candidate => fs.existsSync(path.join(candidate,
    'onnx', 'gte-multilingual-base', 'model.onnx')));
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
    result_json, accepted_settings_revision, building_generation_id, target_settings_json, gaps_json,
    processing_history_json, processing_history_counts_json,
    preparation_nonce, preparation_sealed, preparation_payload
    FROM operations WHERE operation_key = ?`, operationKey);
}

function readRows(dbPath, sql, parameter) {
  const database = new DatabaseSync(dbPath, { readOnly: true });
  try {
    // Recovery can hold the writer lock briefly while this observer polls.
    database.exec('PRAGMA busy_timeout = 5000');
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

async function killOwnedEngineAndObserveCooldown({ engine, data, readJson, waitFor, requireThat }) {
  const before = readJson(path.join(data, 'runtime', 'supervisor.v1.json'));
  requireThat(before?.pid === engine.pid && before.instanceId === engine.instanceId
    && before.runId === engine.runId && before.incarnation === engine.incarnation,
  'only the captured admitted Engine may receive this bulk fault');
  const table = identity.readProcessTable();
  requireThat(table.ok, `Engine process identity must be readable: ${table.reason ?? 'unknown'}`);
  const original = table.table.find(row => Number(row.ProcessId) === engine.pid);
  requireThat(original?.CommandLine?.includes(data),
    'Engine process identity must name this fixture data directory');
  const record = { pid: engine.pid, creationFileTimeUtc: original.CreationFileTimeUtc,
    cmdlineFingerprint: original.CommandLine };
  const verified = identity.verifyProcessIdentity({ record, table });
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

function settingsWitnessOnDisk(data) {
  const value = readJsonFile(path.join(data, 'ui', 'settings.json'));
  return {
    witness: {
      acceptedRevision: value?.acceptedRevision ?? 0,
      lastCommittedOperationKey: value?.lastCommittedOperationKey ?? null,
    },
    settings: value?.settings ?? null,
  };
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

function sha256File(file) {
  const digest = crypto.createHash('sha256');
  const handle = fs.openSync(file, 'r');
  const chunk = Buffer.allocUnsafe(8 * 1024 * 1024);
  try {
    for (;;) {
      const read = fs.readSync(handle, chunk, 0, chunk.length, null);
      if (read === 0) return digest.digest('hex');
      digest.update(chunk.subarray(0, read));
    }
  } finally {
    fs.closeSync(handle);
  }
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
