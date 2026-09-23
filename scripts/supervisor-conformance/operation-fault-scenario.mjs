import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import identity from '../dev/lib/process-identity.cjs';

const CASES = Object.freeze({
  'ingest-before-accept': { parentKind: 'ingest', phase: 'before-accept', recovery: 'retry' },
  'settings-before-accept': { parentKind: 'reconfigure', phase: 'before-accept', recovery: 'retry' },
  'ingest-after-accept-before-effect': { parentKind: 'ingest', phase: 'after-accept', recovery: 'resume' },
  'settings-after-accept-before-effect': {
    parentKind: 'reconfigure', phase: 'after-accept', recovery: 'fail-before-commit',
  },
  'ingest-after-effect-before-checkpoint': {
    parentKind: 'ingest', phase: 'after-effect', recovery: 'resume',
  },
  'settings-after-effect-before-checkpoint': {
    parentKind: 'reconfigure', phase: 'after-effect', recovery: 'resume',
  },
  'ingest-client-disconnect': { parentKind: 'ingest', phase: 'after-accept', recovery: 'disconnect' },
});

const OPERATION_KEY = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SESSION_HEADER = 'X-JustSearch-Session';
const INGEST_ROUTE = '/api/knowledge/ingest';
const SETTINGS_ROUTE = '/api/settings/v2';
const RECEIPT_CURSOR = /^ingest-receipt:1:[1-9][0-9]*:[0-9a-f]{64}$/;
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

/** Installed ingestion/settings fault runner using the real HTTP producers. */
export async function exerciseOperationFault(c) {
  const { work, data, first, manifest, apiPort, readJson, waitFor, request, post,
    requireThat, requireOperationSuccess, createOperationKey, matchingHit, jobStateFor,
    scenario, operationKey } = c;
  const selected = CASES[scenario];
  requireThat(selected, `unknown operation fault scenario: ${scenario}`);
  requireThat(OPERATION_KEY.test(operationKey), `scenario key must be a caller-selected UUIDv7: ${operationKey}`);
  requireThat(first.pid === manifest.pid && first.instanceId === manifest.instanceId,
    'fixture may fault only the admitted Engine it launched');
  const originalIdentity = captureEngineIdentity(first, data, requireThat);

  const runtime = path.join(data, 'runtime');
  const reachedFile = path.join(runtime, 'operation-fault-reached.json');
  const releaseFile = path.join(runtime, 'operation-fault-release');
  requireThat(!fs.existsSync(reachedFile) && !fs.existsSync(releaseFile),
    'operation fault fixture must start without a reached or release marker');

  const token = manifest.head?.sessionToken;
  const headers = { 'content-type': 'application/json' };
  if (typeof token === 'string' && token.length > 0) headers[SESSION_HEADER] = token;
  const operationPath = path.join(data, 'operations.db');
  const file = path.join(work, 'corpus', `operation-fault-${scenario}.txt`);
  const marker = `operationfault${scenario.replaceAll('-', '')}marker`;
  let settingsInput = null;
  let originalWitness = null;

  if (selected.parentKind === 'ingest') {
    requireThat(fs.existsSync(path.join(data, 'watched_roots.json')),
      'ingest recovery requires the fixture-owned watched-root precondition');
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, `${marker} durable operation recovery quokka\n`);
    requireThat(readJson(path.join(data, 'watched_roots.json'))?.roots?.some(root =>
      path.resolve(root.path).toLowerCase() === path.resolve(path.dirname(file)).toLowerCase()),
    'the fault document must be under the persisted watched corpus root');
  } else {
    const settingsResponse = await request(apiPort, SETTINGS_ROUTE);
    requireThat(settingsResponse.status === 200, `settings witness read failed: ${settingsResponse.text}`);
    const current = parseJson(settingsResponse, 'settings witness read');
    requireThat(current.settingsMode === 'read_write',
      `public settings fault needs durable settings mode: ${settingsResponse.text}`);
    originalWitness = current.witness;
    requireThat(Number.isSafeInteger(originalWitness?.acceptedRevision)
      && (originalWitness.acceptedRevision === 0
        ? originalWitness.lastCommittedOperationKey === null
        : OPERATION_KEY.test(originalWitness.lastCommittedOperationKey)),
    `settings API returned an invalid witness: ${settingsResponse.text}`);
    const persisted = settingsWitnessOnDisk(data);
    requireThat(sameJson(persisted.witness, originalWitness),
      `settings API and persisted witness differ before fault: api=${JSON.stringify(originalWitness)} `
        + `file=${JSON.stringify(persisted.witness)}`);
    settingsInput = {
      ui: { highContrast: !Boolean(current.ui?.highContrast) },
      witness: originalWitness,
      operationKey,
    };
  }

  requireThat(operationRows(operationPath, operationKey).length === 0,
    'caller-selected operation key must be unknown before dispatch');
  requireThat(jobsFor(data, file).length === 0,
    'fault document must have no preexisting queue admission');

  const endpoint = selected.parentKind === 'ingest' ? INGEST_ROUTE : SETTINGS_ROUTE;
  let admissionBaseline;
  if (selected.recovery === 'disconnect') {
    let previousCount;
    admissionBaseline = await waitFor('stable admission baseline before disconnect', 30000, async () => {
      const response = await request(apiPort, '/api/debug/effective-config');
      requireThat(response.status === 200, `admission baseline read failed: ${response.text}`);
      const admission = parseJson(response, 'admission baseline').engineAdmission;
      requireThat(Number.isSafeInteger(admission?.activeWorkCount) && admission.activeWorkCount >= 0,
        `invalid admission baseline: ${response.text}`);
      const stable = previousCount === admission.activeWorkCount;
      previousCount = admission.activeWorkCount;
      return stable ? admission : null;
    });
    console.log('OPERATION_FAULT_ADMISSION_BEFORE', JSON.stringify(admissionBaseline));
  }
  const body = selected.parentKind === 'ingest'
    ? { paths: [file], idempotencyKey: operationKey }
    : settingsInput;
  const held = startHeldPost(apiPort, endpoint, body, headers);
  let prematureOutcome = null;
  void held.settled.then(outcome => { prematureOutcome = outcome; });
  const reached = await waitFor(`operation fault marker ${scenario}`, 170000, () => {
    const value = readJson(reachedFile);
    if (value) return value;
    // Recorded ingestion returns an accepted parent response while its durable child keeps
    // running. The after-effect barrier belongs to that child, so the parent may settle first.
    if (prematureOutcome && !(selected.parentKind === 'ingest' && selected.phase === 'after-effect')) {
      throw new Error(`operation request settled before fault marker ${scenario}: `
        + JSON.stringify(prematureOutcome));
    }
    return null;
  });
  verifyReached(reached, { selected, operationKey, first, requireThat });
  let acceptedParent = null;
  if (selected.phase === 'before-accept') {
    requireThat(reached.operationRecordId === -1,
      `before-accept marker must use the no-row sentinel: ${JSON.stringify(reached)}`);
    requireThat(operationRows(operationPath, operationKey).length === 0,
      'before-accept hook must stop before a durable operation row exists');
  } else if (selected.phase === 'after-effect' && selected.parentKind === 'ingest') {
    const parentOutcome = await held.settled;
    requireThat(parentOutcome.response?.status === 200,
      `recorded parent must return accepted while its child is held: ${JSON.stringify(parentOutcome)}`);
    const acceptedResponse = parseJson(parentOutcome.response, 'recorded parent acceptance');
    requireThat(acceptedResponse.success === true
      && acceptedResponse.structuredData?.operationKey === operationKey,
    `recorded parent acceptance must retain the caller key: ${parentOutcome.response.text}`);
    acceptedParent = operationRow(operationPath, operationKey);
    const child = operationRow(operationPath, reached.operationKey);
    requireThat(acceptedParent?.kind === 'ingest' && child?.id === reached.operationRecordId
      && child.operation_key === reached.operationKey && child.kind === 'ingest'
      && reached.operationKey !== operationKey,
    `ingest receipt hook must identify its durable child and retain the caller parent: ${JSON.stringify({
      reached, acceptedParent, child,
    })}`);
  } else {
    acceptedParent = operationRow(operationPath, operationKey);
    requireThat(acceptedParent?.id === reached.operationRecordId
      && acceptedParent.operation_key === operationKey && acceptedParent.kind === selected.parentKind,
    `accepted hook must name the exact durable operation row: ${JSON.stringify({ reached, acceptedParent })}`);
  }

  if (selected.recovery === 'disconnect') {
    held.controller.abort(new Error('supervisor conformance client disconnect'));
    releaseBarrier(releaseFile);
    const abandoned = await held.settled;
    requireThat(abandoned.error, 'disconnect case must abort the held HTTP client request');
    const completed = await waitFor('durable ingest finishes after caller disconnect', 120000, () => {
      const row = operationRow(operationPath, operationKey);
      const jobs = jobsFor(data, file);
      return row?.state === 'COMPLETE' && jobs.length === 1 && jobs[0].state === 'DONE'
        ? { row, jobs } : null;
    });
    const checked = await verifyIngestEffect({ c, apiPort, file, marker, operationKey,
      operationRecordId: completed.row.id, headers, request, post, requireThat,
      requireOperationSuccess, createOperationKey, matchingHit, jobStateFor, operationPath });
    let observedAdmission;
    const admission = await waitFor('ingest admission released after caller disconnect', 30000, async () => {
      const response = await request(apiPort, '/api/debug/effective-config');
      const state = response.status === 200 ? parseJson(response, 'effective config admission read') : {};
      const current = JSON.stringify({ status: response.status, admission: state.engineAdmission, error: response.status === 200 ? undefined : response.text });
      if (current !== observedAdmission) {
        observedAdmission = current;
        console.log('OPERATION_FAULT_ADMISSION_AFTER', current);
      }
      if (response.status !== 200) return null;
      return state.engineAdmission?.activeWorkCount === admissionBaseline.activeWorkCount
        ? state.engineAdmission : null;
    });
    const releasedKey = createOperationKey();
    const admittedAgain = await postWithSession(request, apiPort, INGEST_ROUTE,
      { paths: [file], idempotencyKey: releasedKey }, headers);
    const releasedReceipt = requireOperationSuccess(admittedAgain, 'post-disconnect fresh ingest admission');
    requireThat(releasedReceipt.operationKey === releasedKey,
      `fresh post-disconnect admission changed its key: ${admittedAgain.text}`);
    const released = await c.waitFor('fresh ingest admission completes after disconnect', 120000, () => {
      const row = operationRow(operationPath, releasedKey);
      const jobs = jobsFor(data, file);
      return row?.state === 'COMPLETE' && jobs.length === 1 && jobs[0].state === 'DONE'
        ? { row, jobs } : null;
    });
    const afterReleaseStatus = await request(apiPort, '/api/knowledge/status');
    requireThat(afterReleaseStatus.status === 200
      && JSON.parse(afterReleaseStatus.text).indexedDocuments === checked.indexedDocuments,
    `fresh admission duplicated or removed the indexed document: ${afterReleaseStatus.text}`);
    const afterReleaseSearch = await postWithSession(request, apiPort, '/api/knowledge/search',
      { query: marker, limit: 10, mode: 'text' }, headers);
    const afterReleaseHits = JSON.parse(afterReleaseSearch.text).results.filter(hit =>
      String(hit?.fields?.path ?? '').toLowerCase() === path.resolve(file).toLowerCase());
    requireThat(afterReleaseSearch.status === 200 && afterReleaseHits.length === 1,
      `fresh admission must retain one searchable document: ${afterReleaseSearch.text}`);
    console.log('OPERATION_FAULT_DISCONNECT_PASS', JSON.stringify({ scenario, reached,
      operation: completed.row, jobs: checked.jobs, matchingDocuments: checked.matchingDocuments,
      indexedDocuments: checked.indexedDocuments, clientAbort: abandoned.error.name,
      admissionBaseline, admission, releasedKey, releasedOperation: released.row,
      afterReleaseMatchingDocuments: afterReleaseHits.length }));
    return { reached, operation: completed.row, ...checked, released: released.row };
  }

  let unrelatedSettings;
  let unrelatedSettingsRequest;
  if (selected.parentKind === 'ingest' && selected.phase === 'after-effect') {
    const beforeResponse = await request(apiPort, SETTINGS_ROUTE);
    requireThat(beforeResponse.status === 200, `unrelated settings read failed: ${beforeResponse.text}`);
    const before = parseJson(beforeResponse, 'unrelated settings before restart');
    const settingsKey = createOperationKey();
    const input = { ui: { highContrast: !Boolean(before.ui?.highContrast) },
      witness: before.witness, operationKey: settingsKey };
    unrelatedSettingsRequest = startHeldPost(apiPort, SETTINGS_ROUTE, input, headers);
    const persisted = await waitFor('unrelated settings commitment while ingest receipt is held', 30000, () => {
      const row = operationRow(operationPath, settingsKey);
      const disk = settingsWitnessOnDisk(data);
      return row?.state === 'COMPLETE' && disk.witness.lastCommittedOperationKey === settingsKey ? disk : null;
    });
    requireCommittedSettings(persisted, before.witness, input, settingsKey, requireThat);
    unrelatedSettings = { before: before.witness, input, persisted };
    console.log('OPERATION_FAULT_UNRELATED_SETTINGS_CHANGED', JSON.stringify(unrelatedSettings));
  }
  const afterDeath = await killOwnedEngineAndObserveCooldown({ record: originalIdentity, first, data, readJson,
    waitFor, requireThat });
  const effectKey = selected.phase === 'after-effect' && selected.parentKind === 'ingest'
    ? reached.operationKey : operationKey;
  const duringCooldown = snapshot(data, operationKey, effectKey, file);
  assertCooldownState({ selected, operationKey, reached, duringCooldown, originalWitness,
    settingsInput, requireThat });
  const afterSnapshot = readJson(path.join(runtime, 'supervisor.v1.json'));
  requireThat(afterSnapshot?.state === 'restarting' && afterSnapshot.runId === first.runId
    && afterSnapshot.incarnation === first.incarnation,
  'successor must not alter the state before the cooldown snapshot finishes');
  requireThat(afterDeath.state === 'restarting' && afterDeath.incarnation === first.incarnation,
    `cooldown observation escaped the original restart: ${JSON.stringify(afterDeath)}`);
  console.log('OPERATION_FAULT_COOLDOWN_SNAPSHOT', JSON.stringify({ scenario, reached,
    supervisor: afterDeath, ...duringCooldown }));

  held.controller.abort(new Error('original Engine died at selected operation boundary'));
  unrelatedSettingsRequest?.controller.abort(new Error('original Engine died after unrelated settings commitment'));
  releaseBarrier(releaseFile);
  await held.settled;
  if (unrelatedSettingsRequest) await unrelatedSettingsRequest.settled;

  const successor = await waitFor('identity-matched successor Engine', 150000, async () => {
    const current = readJson(path.join(runtime, 'supervisor.v1.json'));
    const currentManifest = readJson(path.join(runtime, 'manifest.json'));
    if (current?.state !== 'running' || current.runId !== first.runId
      || current.incarnation !== first.incarnation + 1 || current.instanceId === first.instanceId
      || currentManifest?.pid !== current.pid || currentManifest?.instanceId !== current.instanceId
      || currentManifest?.head?.apiPort !== current.apiPort) return null;
    try {
      const health = await request(currentManifest.head.apiPort, '/api/health');
      return health.status === 200 ? { supervisor: current, manifest: currentManifest } : null;
    } catch { return null; }
  });
  requireThat(successor.supervisor.restartCount === 1,
    'only the selected operation boundary may spend restart budget');
  const successorHeaders = sessionHeaders(successor.manifest);

  if (selected.recovery === 'fail-before-commit') {
    const failed = await waitFor('accepted settings operation fails before file commitment', 120000, () => {
      const row = operationRow(operationPath, operationKey);
      return row?.state === 'FAILED' ? row : null;
    });
    requireThat(failed.failure_reason === 'interrupted_before_settings_commit',
      `settings owner must classify the unarmed accepted row: ${JSON.stringify(failed)}`);
    requireThat(sameJson(settingsWitnessOnDisk(data).witness, originalWitness),
      'pre-effect settings recovery must leave the committed witness unchanged');
    requireThat(jobsFor(data, file).length === 0,
      'settings-only recovery must not create queue effects');
    const failedReplay = await postWithSession(request, successor.manifest.head.apiPort,
      SETTINGS_ROUTE, settingsInput, successorHeaders);
    const failureBody = parseJson(failedReplay, 'interrupted settings replay');
    requireThat(failedReplay.status >= 400 && failureBody.operationKey === operationKey
      && failureBody.operationRecordId === failed.id && failureBody.state === 'FAILED',
    `same-key settings replay must retain the recorded failure: ${failedReplay.text}`);
    const afterReplay = operationRow(operationPath, operationKey);
    requireThat(sameJson(afterReplay, failed)
      && sameJson(settingsWitnessOnDisk(data).witness, originalWitness),
    'failed settings replay must not rewrite its row or commit the patch');
    console.log('OPERATION_FAULT_SETTINGS_PRE_EFFECT_PASS', JSON.stringify({ scenario,
      reached, duringCooldown, failed, failedReplay: failureBody, settings: settingsWitnessOnDisk(data) }));
    return { reached, duringCooldown, successor, operation: failed };
  }

  if (selected.parentKind === 'ingest') {
    if (selected.recovery === 'resume') {
      await waitFor('successor resumes the accepted ingest operation', 120000, () => {
        const row = operationRow(operationPath, operationKey);
        const jobs = jobsFor(data, file);
        return row?.state === 'COMPLETE' && jobs.length === 1 && jobs[0].state === 'DONE'
          ? row : null;
      });
    }
    const checked = await verifyIngestEffect({ c, apiPort: successor.manifest.head.apiPort,
      file, marker, operationKey,
      operationRecordId: selected.recovery === 'retry' ? null : acceptedParent.id,
      headers: successorHeaders, request, post, requireThat, requireOperationSuccess,
      createOperationKey, matchingHit, jobStateFor, operationPath });
    let recoveredReceipt;
    if (selected.phase === 'after-effect') {
      recoveredReceipt = await waitFor('successor acknowledges the exact recovered child receipt', 30000, () => {
        const child = operationRow(operationPath, reached.operationKey);
        const walk = walkProgress(data, reached.operationKey);
        return child?.state === 'COMPLETE' && walk?.acknowledged_revision === duringCooldown.walk.revision
          ? { child, walk } : null;
      });
      const { child, walk } = recoveredReceipt;
      requireThat(child.id === reached.operationRecordId && child.checkpoint_cursor === reached.cursor
        && child.units_completed === reached.completed && child.units_failed === reached.failed
        && child.attempts === duringCooldown.effectOperation.attempts + 1,
      `successor must checkpoint the original child in one resumed attempt: ${JSON.stringify(recoveredReceipt)}`);
      requireThat(walk.plan_hash === duringCooldown.walk.plan_hash
        && walk.revision === duringCooldown.walk.revision
        && walk.receipt_json === duringCooldown.walk.receipt_json,
      `successor must acknowledge the same immutable sealed receipt: ${JSON.stringify(recoveredReceipt)}`);
      requireThat(sameJson(settingsWitnessOnDisk(data), unrelatedSettings.persisted),
        'ingest recovery must retain the unrelated committed settings revision and value');
      const historyResponse = await request(successor.manifest.head.apiPort, '/api/operation-history');
      requireThat(historyResponse.status === 200, `public settings history read failed: ${historyResponse.text}`);
      const settingsHistory = parseJson(historyResponse, 'public settings history').entries
        .filter(entry => entry.operationKey === unrelatedSettings.input.operationKey);
      requireThat(settingsHistory.length === 1 && settingsHistory[0].operationId === 'core.reconfigure'
        && settingsHistory[0].outcome === 'SUCCESS',
        `public settings must project one typed history entry: ${JSON.stringify(settingsHistory)}`);
      await waitFor('public settings history delivery acknowledgement', 30000, () => {
        const database = new DatabaseSync(operationPath, { readOnly: true });
        try {
          const row = database.prepare('SELECT operation_ref, history_pending FROM operations WHERE operation_key = ?')
            .get(unrelatedSettings.input.operationKey);
          requireThat(row?.operation_ref === 'core.reconfigure', 'history must retain the accepted reconfigure identity');
          return row.history_pending === 0 ? row : null;
        } finally { database.close(); }
      });
    }
    console.log('OPERATION_FAULT_INGEST_PASS', JSON.stringify({ scenario, reached,
      duringCooldown, successor: successor.supervisor, operation: checked.operation,
      jobs: checked.jobs, matchingDocuments: checked.matchingDocuments,
      indexedDocuments: checked.indexedDocuments, recoveredReceipt, unrelatedSettings }));
    return { reached, duringCooldown, successor, ...checked };
  }

  let expectedSettingsRecordId = reached.operationRecordId;
  if (selected.recovery === 'retry') {
    const firstRetry = await postWithSession(request, successor.manifest.head.apiPort,
      SETTINGS_ROUTE, settingsInput, successorHeaders);
    const firstRetryBody = parseJson(firstRetry, 'settings before-accept same-key retry');
    requireThat(firstRetry.status === 200 && firstRetryBody.state === 'COMPLETE'
      && firstRetryBody.operationKey === operationKey,
    `settings retry must accept the original key and return its new row identity: ${firstRetry.text}`);
    expectedSettingsRecordId = operationRow(operationPath, operationKey).id;
  }
  await waitFor('successor commits settings operation', 120000, () => {
    const row = operationRow(operationPath, operationKey);
    if (row?.state === 'FAILED') throw new Error(`settings recovery failed unexpectedly: ${JSON.stringify(row)}`);
    return row?.state === 'COMPLETE' && row.id === expectedSettingsRecordId ? row : null;
  });
  const committed = settingsWitnessOnDisk(data);
  requireCommittedSettings(committed, originalWitness, settingsInput, operationKey, requireThat);
  const stableSettingsRow = operationRow(operationPath, operationKey);
  const replay = await postWithSession(request, successor.manifest.head.apiPort,
    SETTINGS_ROUTE, settingsInput, successorHeaders);
  const replayBody = parseJson(replay, 'same-key settings replay');
  requireThat(replay.status === 200 && replayBody.operationKey === operationKey
    && replayBody.state === 'COMPLETE',
  `settings replay must return the original completed row: ${replay.text}`);
  requireCommittedSettings(settingsWitnessOnDisk(data), originalWitness, settingsInput,
    operationKey, requireThat);
  const finalOperation = operationRow(operationPath, operationKey);
  requireThat(finalOperation.id === expectedSettingsRecordId && finalOperation.state === 'COMPLETE'
    && sameJson(finalOperation, stableSettingsRow),
    `settings completion changed operation identity: ${JSON.stringify(finalOperation)}`);
  console.log('OPERATION_FAULT_SETTINGS_PASS', JSON.stringify({ scenario, reached,
    duringCooldown, successor: successor.supervisor, operation: finalOperation,
    settings: settingsWitnessOnDisk(data), replay: replayBody }));
  return { reached, duringCooldown, successor, operation: finalOperation, settings: committed };
}

async function verifyIngestEffect({ c, apiPort, file, marker, operationKey, operationRecordId,
  headers, request, post, requireThat, requireOperationSuccess, createOperationKey,
  matchingHit, jobStateFor, operationPath }) {
  const { data } = c;
  let row = operationRow(operationPath, operationKey);
  if (!row) {
    const accepted = await postWithSession(request, apiPort, INGEST_ROUTE,
      { paths: [file], idempotencyKey: operationKey }, headers);
    const receipt = requireOperationSuccess(accepted, 'operation fault same-key ingest retry');
    requireThat(receipt.operationKey === operationKey,
      `ingest retry changed the caller's operation key: ${accepted.text}`);
    if (operationRecordId !== null) {
      requireThat(receipt.operationRecordId === operationRecordId,
        `ingest retry changed the accepted operation record: ${accepted.text}`);
    }
  }
  await c.waitFor('indexed operation fault document', 120000, () => {
    row = operationRow(operationPath, operationKey);
    const jobs = jobsFor(data, file);
    return row?.state === 'COMPLETE' && jobs.length === 1 && jobs[0].state === 'DONE'
      ? { row, jobs } : null;
  });
  const stableOperation = operationRow(operationPath, operationKey);
  const stableJobs = jobsFor(data, file);
  requireThat(stableOperation.id === (operationRecordId ?? stableOperation.id)
    && stableOperation.operation_key === operationKey && stableOperation.kind === 'ingest'
    && stableOperation.state === 'COMPLETE',
  `ingest operation did not complete under its original identity: ${JSON.stringify(stableOperation)}`);
  requireThat(stableJobs.length === 1 && stableJobs[0].state === 'DONE',
    `ingest retry left missing or duplicate queue work: ${JSON.stringify(stableJobs)}`);
  const statusBefore = await c.waitFor('committed document is published in search and status', 30000, async () => {
    const status = await request(apiPort, '/api/knowledge/status');
    if (status.status !== 200 || JSON.parse(status.text).indexedDocuments !== 1) return null;
    const search = await postWithSession(request, apiPort, '/api/knowledge/search',
      { query: marker, limit: 10, mode: 'text' }, headers);
    return search.status === 200 && matchingHit(search, file, marker) ? status : null;
  });
  requireThat(statusBefore.status === 200, `knowledge status before retry: ${statusBefore.text}`);
  const indexedDocuments = JSON.parse(statusBefore.text).indexedDocuments;
  requireThat(Number.isInteger(indexedDocuments) && indexedDocuments === 1,
    `fixture corpus must contain exactly one indexed document: ${statusBefore.text}`);

  const retry = await postWithSession(request, apiPort, INGEST_ROUTE,
    { paths: [file], idempotencyKey: operationKey }, headers);
  const retryReceipt = requireOperationSuccess(retry, 'operation fault same-key ingest replay');
  requireThat(retryReceipt.operationKey === operationKey
    && retryReceipt.operationRecordId === stableOperation.id,
  `same-key retry did not return the original ingest row: ${retry.text}`);
  const afterRetryOperation = operationRow(operationPath, operationKey);
  const afterRetryJobs = jobsFor(data, file);
  requireThat(sameJson(afterRetryOperation, stableOperation)
    && sameJson(afterRetryJobs, stableJobs),
  `same-key retry changed durable ingest state: before=${JSON.stringify({ stableOperation, stableJobs })} `
    + `after=${JSON.stringify({ afterRetryOperation, afterRetryJobs })}`);

  const search = await postWithSession(request, apiPort, '/api/knowledge/search',
    { query: marker, limit: 10, mode: 'text' }, headers);
  requireThat(search.status === 200 && matchingHit(search, file, marker),
    `recovered document must be searchable: ${search.text}`);
  const hits = JSON.parse(search.text).results.filter(hit =>
    String(hit?.fields?.path ?? '').toLowerCase() === path.resolve(file).toLowerCase());
  requireThat(hits.length === 1, `same-key retry must not duplicate the document: ${search.text}`);
  const statusAfter = await request(apiPort, '/api/knowledge/status');
  requireThat(statusAfter.status === 200
    && JSON.parse(statusAfter.text).indexedDocuments === indexedDocuments,
  `indexed document count changed after same-key retry: ${statusAfter.text}`);

  return { operation: afterRetryOperation, jobs: afterRetryJobs, matchingDocuments: hits.length,
    indexedDocuments, retryReceipt, search };
}

function captureEngineIdentity(first, data, requireThat) {
  const initialTable = identity.readProcessTable();
  requireThat(initialTable.ok, 'initial Engine process identity must be readable');
  const original = initialTable.table.find(row => Number(row.ProcessId) === first.pid);
  requireThat(original?.CommandLine?.includes(data),
    'Engine process identity must name this fixture data directory');
  return { pid: first.pid, creationFileTimeUtc: original.CreationFileTimeUtc,
    cmdlineFingerprint: original.CommandLine };
}

async function killOwnedEngineAndObserveCooldown({ record, first, data, readJson, waitFor, requireThat }) {
  const before = readJson(path.join(data, 'runtime', 'supervisor.v1.json'));
  requireThat(before?.pid === first.pid && before.instanceId === first.instanceId
    && before.runId === first.runId && before.incarnation === first.incarnation,
  'only the original admitted Engine may receive this fault');
  const verified = identity.verifyProcessIdentity({ record, table: identity.readProcessTable() });
  requireThat(identity.isVerifiedMatch(verified), `refusing unverified operation fault: ${verified.reason}`);

  process.kill(first.pid, 'SIGKILL');
  const supervisorFile = path.join(data, 'runtime', 'supervisor.v1.json');
  return waitFor('original Engine death and counted restart cooldown', 12000, () => {
    let alive = true;
    try { process.kill(first.pid, 0); }
    catch (error) {
      if (error.code === 'ESRCH') alive = false;
      else throw error;
    }
    const state = readJson(supervisorFile);
    return !alive && state?.runId === first.runId && state.state === 'restarting'
      && state.incarnation === first.incarnation && state.restartCount === 1
      && state.lastExit?.counted === true ? state : null;
  });
}

function assertCooldownState({ selected, operationKey, reached, duringCooldown, originalWitness,
  settingsInput, requireThat }) {
  const { operation, effectOperation, jobs, ledger, walk, settings } = duringCooldown;
  if (selected.phase === 'before-accept') {
    requireThat(operation === null && jobs.length === 0 && ledger.length === 0 && walk === null,
      `before-accept death must leave no operation or effect: ${JSON.stringify(duringCooldown)}`);
    if (selected.parentKind === 'reconfigure') {
      requireThat(sameJson(settings.witness, originalWitness),
        'settings before acceptance must leave the committed witness unchanged');
    }
    return;
  }

  const childReceipt = selected.phase === 'after-effect' && selected.parentKind === 'ingest';
  requireThat(operation && (childReceipt
    ? Number.isSafeInteger(operation.id) && operation.id > 0
    : operation.id === reached.operationRecordId)
    && operation.operation_key === operationKey && operation.kind === selected.parentKind,
  `cooldown snapshot must retain the exact accepted parent row: ${JSON.stringify(duringCooldown)}`);
  if (selected.phase === 'after-accept') {
    requireThat(operation.state === 'ACCEPTED' && operation.started_at === null,
      `after-accept boundary must precede start/body: ${JSON.stringify(operation)}`);
    requireThat(jobs.length === 0 && ledger.length === 0 && walk === null,
      `after-accept boundary must precede ingest queue effects: ${JSON.stringify(duringCooldown)}`);
    if (selected.parentKind === 'reconfigure') {
      requireThat(sameJson(settings.witness, originalWitness),
        'after-accept/before-effect settings must not arm or commit a revision');
    }
    return;
  }

  requireThat(operation.state === 'RUNNING' && operation.started_at !== null,
    `after-effect boundary must retain the open running parent: ${JSON.stringify(operation)}`);
  if (selected.parentKind === 'ingest') {
    requireThat(effectOperation?.id === reached.operationRecordId
      && effectOperation.operation_key === reached.operationKey
      && effectOperation.kind === 'ingest' && effectOperation.state === 'RUNNING',
    `after-effect boundary must retain the exact running child row: ${JSON.stringify({ reached, effectOperation })}`);
    requireThat(typeof reached.cursor === 'string' && RECEIPT_CURSOR.test(reached.cursor)
      && reached.completed > 0 && effectOperation.checkpoint_cursor !== reached.cursor,
    `child row must remain before the positive-unit receipt checkpoint: ${JSON.stringify({ reached, effectOperation })}`);
    requireThat(jobs.length === 1 && jobs[0].state === 'DONE'
      && walk?.enumeration_outcome === 'COMPLETE' && walk.sealed_at !== null
      && walk.receipt_json !== null
      && ledger.some(row => row.terminal_coverage === 'INDEXED'),
    `ingest effect must already be sealed and indexed before its row checkpoint: ${JSON.stringify(duringCooldown)}`);
  } else {
    requireThat(settings.witness.acceptedRevision === originalWitness.acceptedRevision + 1
      && settings.witness.lastCommittedOperationKey === operationKey
      && settings.settings?.highContrast === settingsInput.ui.highContrast,
    `settings side effect must match its accepted witness before terminal write: ${JSON.stringify(settings)}`);
    requireThat(operation.accepted_settings_revision === originalWitness.acceptedRevision,
      `accepted settings row must retain the original expected revision: ${JSON.stringify(operation)}`);
  }
}

function verifyReached(reached, { selected, operationKey, first, requireThat }) {
  const childReceipt = selected.phase === 'after-effect' && selected.parentKind === 'ingest';
  requireThat(reached.phase === selected.phase && reached.parentKind === selected.parentKind
    && reached.parentKey === operationKey && typeof reached.operationKey === 'string'
    && reached.operationKey.length > 0 && (childReceipt || reached.operationKey === operationKey),
  `hook reached the wrong operation boundary: ${JSON.stringify(reached)}`);
  requireThat(reached.pid === first.pid,
    `hook marker belongs to a different Engine process: ${JSON.stringify(reached)}`);
  requireThat(Number.isSafeInteger(reached.operationRecordId)
    && Object.hasOwn(reached, 'cursor')
    && Number.isSafeInteger(reached.completed) && Number.isSafeInteger(reached.failed),
  `hook marker omitted its durable boundary snapshot: ${JSON.stringify(reached)}`);
}

function snapshot(data, operationKey, effectKey, file) {
  return {
    operation: operationRow(path.join(data, 'operations.db'), operationKey),
    effectOperation: operationRow(path.join(data, 'operations.db'), effectKey),
    jobs: jobsFor(data, file),
    ledger: ledgerRows(data, effectKey),
    walk: walkProgress(data, effectKey),
    settings: settingsWitnessOnDisk(data),
  };
}

function operationRows(dbPath, operationKey) {
  const database = new DatabaseSync(dbPath, { readOnly: true });
  try {
    return database.prepare(`SELECT id, operation_key, kind, state, phase, operation_ref,
      identity_json, checkpoint_cursor, units_completed, units_failed, attempts,
      accepted_at, started_at, updated_at, completed_at, failure_reason, failure_detail,
      result_json, accepted_settings_revision FROM operations WHERE operation_key = ?`)
      .all(operationKey);
  } finally { database.close(); }
}

function operationRow(dbPath, operationKey) {
  const rows = operationRows(dbPath, operationKey);
  if (rows.length > 1) throw new Error(`operation key is duplicated in SQLite: ${operationKey}`);
  return rows[0] ?? null;
}

function jobsFor(data, file) {
  const database = new DatabaseSync(path.join(data, 'jobs.db'), { readOnly: true });
  try {
    return database.prepare(`SELECT path, state, attempts, content_hash, last_outcome_class,
      last_reason_code, last_retry_policy, scan_id, walk_seen_epoch FROM jobs
      WHERE lower(path) LIKE ? ORDER BY path`).all(`%${path.basename(file).toLowerCase()}`);
  } finally { database.close(); }
}

function ledgerRows(data, operationKey) {
  const database = new DatabaseSync(path.join(data, 'jobs.db'), { readOnly: true });
  try {
    return database.prepare(`SELECT path_hash, unit_revision, outcome_class, reason_code,
      retry_policy, terminal_coverage, content_hash FROM ingestion_ledger
      WHERE operation_key = ? ORDER BY id`).all(operationKey);
  } finally { database.close(); }
}

function walkProgress(data, operationKey) {
  const database = new DatabaseSync(path.join(data, 'jobs.db'), { readOnly: true });
  try {
    return database.prepare(`SELECT plan_hash, enumeration_epoch, enumeration_closed_at,
      enumeration_outcome, completed_units, failed_units, revision, sealed_at,
      receipt_json, acknowledged_revision FROM ingestion_walk_progress WHERE operation_key = ?`)
      .get(operationKey) ?? null;
  } finally { database.close(); }
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

function readJsonFile(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); }
  catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
}

function requireCommittedSettings(persisted, originalWitness, settingsInput, operationKey, requireThat) {
  requireThat(persisted.witness.acceptedRevision === originalWitness.acceptedRevision + 1
    && persisted.witness.lastCommittedOperationKey === operationKey
    && persisted.settings?.highContrast === settingsInput.ui.highContrast,
  `settings replay advanced unexpectedly or did not commit the exact patch: ${JSON.stringify(persisted)}`);
}

function sessionHeaders(manifest) {
  const headers = { 'content-type': 'application/json' };
  const token = manifest.head?.sessionToken;
  if (typeof token === 'string' && token.length > 0) headers[SESSION_HEADER] = token;
  return headers;
}

function postWithSession(request, apiPort, endpoint, body, headers) {
  return request(apiPort, endpoint, {
    method: 'POST', headers, body: JSON.stringify(body),
  });
}

function startHeldPost(apiPort, endpoint, body, headers) {
  const controller = new AbortController();
  const settled = fetch(`http://127.0.0.1:${apiPort}${endpoint}`, {
    method: 'POST', headers, body: JSON.stringify(body), signal: controller.signal,
  }).then(async response => ({ status: response.status, text: await response.text() }))
    .then(response => ({ response }), error => ({ error }));
  return { controller, settled };
}

function releaseBarrier(releaseFile) {
  fs.writeFileSync(releaseFile, `${Date.now()}\n`);
}

function parseJson(response, label) {
  try { return JSON.parse(response.text); }
  catch { throw new Error(`${label} returned invalid JSON: ${response.text}`); }
}

function sameJson(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}
