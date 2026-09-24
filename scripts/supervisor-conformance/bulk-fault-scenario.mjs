import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import identity from '../dev/lib/process-identity.cjs';

export const BULK_FAULT_CASES = Object.freeze({
  'bulk-partial-capture': Object.freeze({
    phase: 'bulk-partial-capture', finalIncarnation: 3, faultIncarnation: 1,
    requestedRestartIncarnations: Object.freeze([2]), cutAttempts: 1, finalAttempts: 3,
  }),
  'bulk-state-before-binding': Object.freeze({
    phase: 'bulk-before-building-checkpoint', finalIncarnation: 2, faultIncarnation: 1,
    requestedRestartIncarnations: Object.freeze([]), cutAttempts: 1, finalAttempts: 2,
  }),
  'bulk-promotion-before-terminal': Object.freeze({
    phase: 'bulk-after-promotion', finalIncarnation: 3, faultIncarnation: 2,
    requestedRestartIncarnations: Object.freeze([1]), cutAttempts: 2, finalAttempts: 2,
  }),
});

// The activation operation has its own durable operation identity and plan assertions below.
// The marker cut plus the five installed coordinator callbacks cover the composite crash sequence;
// the legacy bulk migration cases remain unchanged above.
export const INSTALLER_FAULT_CASES = Object.freeze({
  'installer-before-marker': Object.freeze({
    phase: 'installer-before-marker', finalIncarnation: 3, faultIncarnation: 2,
    requestedRestartIncarnations: Object.freeze([1]), cutAttempts: 2, finalAttempts: 3,
  }),
  'installer-before-arm': Object.freeze({
    phase: 'installer-before-arm', finalIncarnation: 3, faultIncarnation: 2,
    requestedRestartIncarnations: Object.freeze([1]), cutAttempts: 2, finalAttempts: 3,
  }),
  'installer-before-pointer': Object.freeze({
    phase: 'installer-before-pointer', finalIncarnation: 3, faultIncarnation: 2,
    requestedRestartIncarnations: Object.freeze([1]), cutAttempts: 2, finalAttempts: 3,
  }),
  'installer-pointer-before-settings': Object.freeze({
    phase: 'installer-pointer-before-settings', finalIncarnation: 3, faultIncarnation: 2,
    requestedRestartIncarnations: Object.freeze([1]), cutAttempts: 2, finalAttempts: 2,
  }),
  'installer-settings-before-publication': Object.freeze({
    phase: 'installer-settings-before-publication', finalIncarnation: 3, faultIncarnation: 2,
    requestedRestartIncarnations: Object.freeze([1]), cutAttempts: 2, finalAttempts: 2,
  }),
  'installer-before-receipt': Object.freeze({
    phase: 'installer-before-receipt', finalIncarnation: 3, faultIncarnation: 2,
    requestedRestartIncarnations: Object.freeze([1]), cutAttempts: 2, finalAttempts: 2,
  }),
});

const OPERATION_KEY = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SESSION_HEADER = 'X-JustSearch-Session';
const START_ROUTE = '/api/indexing/migration/start';
const ACTIVATION_ROUTE = '/api/operations/core.activate-installed-models/invoke';

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
  const cooldown = await killOwnedEngineAndObserveCooldown({
    engine: faulted.supervisor, data, readJson, waitFor, requireThat,
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

  const servingAfterResult = readJson(path.join(runtime, 'supervisor.v1.json'));
  requireThat(servingAfterResult?.state === 'running'
    && servingAfterResult.incarnation === expectedIncarnation
    && servingAfterResult.instanceId === recovered.supervisor.instanceId
    && servingAfterResult.restartCount === 1
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
  const reachedFile = path.join(runtime, 'operation-fault-reached.json');
  const releaseFile = path.join(runtime, 'operation-fault-release');
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
export async function exerciseLiveModelAB({ work, data, indexBase, manifest, apiPort,
  operationKey, readJson, waitFor, request, post, requireThat, createOperationKey, matchingHit,
  distinctModelB = false, inPlaceModelB = false, acceptedWriteDuringBuild = false,
  watcherDeleteDuringBuild = false,
  extraBuildFiles = 0 }) {
  const runtime = path.join(data, 'runtime');
  const reachedFile = path.join(runtime, 'operation-fault-reached.json');
  const releaseFile = path.join(runtime, 'operation-fault-release');
  const operationPath = path.join(data, 'operations.db');
  const jobsPath = path.join(data, 'jobs.db');
  const sourceGeneration = readJson(path.join(indexBase, 'state.json'))?.active_generation;
  const sourceManifest = readJson(path.join(indexBase, 'indices', sourceGeneration,
    '.justsearch-index-generation.json'));
  const oldContract = readJson(path.join(data, 'install-contract.v2.json'));
  requireThat(sourceManifest?.models?.embedding?.id && oldContract?.modelsDir
    && sourceManifest.models.embedding.id.startsWith(path.resolve(work)),
  'side-by-side model fixture requires a private installed serving A');
  requireThat(operationRows(operationPath, operationKey).length === 0,
    'side-by-side activation key is already present');
  const file = path.join(work, 'installer-root-a', 'installer-0.txt');
  const marker = fs.readFileSync(file, 'utf8').split(/\s+/)[0];
  const removedFile = watcherDeleteDuringBuild
    ? path.join(work, 'installer-root-b', `watcher-delete-during-b-${operationKey}.txt`) : null;
  const removedMarker = removedFile
    ? 'watcherremovalcoral' : null;
  await waitFor('installed A serves a real vector query before B', 120000, async () => {
    try {
      const response = await post(apiPort, '/api/knowledge/search',
        { query: marker, limit: 10, mode: 'vector' }, 30000);
      return response.status === 200 && JSON.parse(response.text).results?.length > 0
        ? response : null;
    } catch { return null; }
  });
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
      const variant = pkg?.variants.find(item => item.targetEP === 'CUDA');
      requireThat(variant && retainedRoot,
        `no retained CUDA variant for installed package ${packageId}`);
      const alternate = path.join(retainedRoot, pkg.targetDir, variant.filename);
      const selected = path.join(target, variant.filename);
      fs.linkSync(alternate, selected);
      requireThat(fs.statSync(selected).size === variant.sizeBytes
        && sha256(fs.readFileSync(selected)) === variant.sha256.toLowerCase(),
      `retained CUDA bytes differ from shipped registry for ${packageId}`);
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
  try {
    const acceptedFile = path.join(work, 'installer-root-a', `accepted-during-b-${operationKey}.txt`);
    const acceptedMarker = 'lexicalbridgecobalt';
    if (watcherDeleteDuringBuild) {
      // The native watcher polls on its own cadence. Hold MIGRATING while it observes both
      // filesystem edges; an unpaused candidate can enter SWITCHING between them.
      let lastPauseObservation = 'candidate has not entered MIGRATING';
      await waitFor('pause MIGRATING candidate for watcher mutations', 120000, async () => {
        const state = readJson(path.join(indexBase, 'state.json'));
        const live = readJson(path.join(runtime, 'manifest.json'));
        if (state?.migration_state === 'SWITCHING') {
          throw new Error(`candidate reached SWITCHING before watcher pause: ${lastPauseObservation}`);
        }
        lastPauseObservation = `state=${state?.migration_state} generation=${state?.building_generation}`
          + ` livePort=${live?.head?.apiPort}`;
        if (state?.migration_state !== 'MIGRATING'
          || state.building_generation !== `g-${operationKey}`
          || !live?.head?.apiPort) return null;
        try {
          const response = await request(live.head.apiPort, '/api/indexing/migration/pause', {
            method: 'POST', headers: sessionHeaders(live),
            body: JSON.stringify({ reason: 'installed watcher A/B verification' }),
          }, 10000);
          const paused = readJson(path.join(indexBase, 'state.json'));
          lastPauseObservation = `http=${response.status} ${response.text}`
            + ` paused=${paused?.migration_paused} state=${paused?.migration_state}`;
          return response.status === 202 && paused?.migration_state === 'MIGRATING'
            && paused.migration_paused === true ? paused : null;
        } catch (error) {
          lastPauseObservation = `request error=${error.message}`;
          return null;
        }
      });
      console.log('MODEL_LIVE_AB_PAUSED', lastPauseObservation);
    }
    if (acceptedWriteDuringBuild) {
      await waitFor('MIGRATING B with a live A producer after restart', 120000, async () => {
        const state = readJson(path.join(indexBase, 'state.json'));
        const row = operationRows(operationPath, operationKey)[0];
        const successor = readJson(path.join(runtime, 'manifest.json'));
        if (state?.migration_state !== 'MIGRATING'
          || state?.building_generation !== `g-${operationKey}`
          || row?.phase === 'settled'
          || !successor?.instanceId || successor.instanceId === manifest.instanceId) return null;
        try {
          const health = await request(apiPort, '/api/health', {}, 10000);
          return health.status === 200 ? state : null;
        } catch { return null; }
      });
      if (watcherDeleteDuringBuild) {
        fs.writeFileSync(removedFile, `${removedMarker} accepted by watcher during B\n`);
        await waitFor('MIGRATING watcher addition visible in serving A', 240000, async () => {
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
        requireThat(readRows(jobsPath, journalSql, journalKey).some(row => row.op === 'UPSERT'),
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
        await waitFor('MIGRATING watcher deletion absent from serving A', 90000, async () => {
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
        requireThat(readRows(jobsPath, journalSql, journalKey).some(row => row.op === 'DELETE'),
          'watcher deletion reached A without a candidate-scoped delete obligation');
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
    }
    if (watcherDeleteDuringBuild) {
      const live = readJson(path.join(runtime, 'manifest.json'));
      requireThat(live?.head?.apiPort, 'paused watcher fixture lost its live Head');
      const resumed = await request(live.head.apiPort, '/api/indexing/migration/resume', {
        method: 'POST', headers: sessionHeaders(live), body: '{}',
      }, 10000);
      requireThat(resumed.status === 202,
        `watcher fixture could not resume candidate: ${resumed.status} ${resumed.text}`);
    }
    const reached = await waitFor('settled B before activation marker',
      acceptedWriteDuringBuild ? 300000 : 170000,
      () => readJson(reachedFile));
    requireThat(reached.phase === 'installer-before-marker'
      && reached.operationKey === operationKey,
    `side-by-side B stopped at the wrong marker: ${JSON.stringify(reached)}`);
    const aState = readJson(path.join(indexBase, 'state.json'));
    const bGeneration = `g-${operationKey}`;
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
    const composeMode = status?.readiness?.engineComponents?.encoders?.mode;
    requireThat(['IN_PLACE', 'BESIDE'].includes(composeMode)
      && (!inPlaceModelB || composeMode === 'IN_PLACE'),
    `candidate composition mode is unavailable or violated the forced floor: ${JSON.stringify({
      composeMode, encoder: status?.readiness?.engineComponents?.encoders,
    })}`);
    const actualInPlace = composeMode === 'IN_PLACE';
    const vectorOutcome = actualInPlace
      ? vectorSearch.status === 400
        && JSON.parse(vectorSearch.text).errorCode === 'INVALID_REQUEST'
        && vectorSearch.text.includes('NO_EMBEDDING_SERVICE')
        && status?.components?.encoders?.state === 'RELOADING'
      : vectorSearch.status === 200
        && JSON.parse(vectorSearch.text).results?.length > 0;
    requireThat(textSearch.status === 200 && matchingHit(textSearch, file, marker)
      && vectorOutcome,
    `serving A violated ${composeMode} mode while B was settled: ${JSON.stringify({
      text: textSearch.text, vector: vectorSearch.text, encoders: status?.components?.encoders,
    })}`);
    console.log('MODEL_LIVE_AB_CUT', JSON.stringify({ operationKey, sourceGeneration,
      buildingGeneration: bGeneration, aModel: sourceManifest.models.embedding,
      bModel: bManifest.models.embedding, unitsCompleted: inFlight.units_completed,
      unitsFailed: inFlight.units_failed, mode: composeMode,
      encoderState: status?.components?.encoders?.state,
      vectorHits: actualInPlace ? 0 : JSON.parse(vectorSearch.text).results.length }));
  } finally {
    fs.writeFileSync(releaseFile, 'release');
  }
  await dispatched.settled;
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
  }
  console.log('MODEL_LIVE_AB_PASS', JSON.stringify({ operationKey,
    sourceGeneration, activeGeneration: completed.active.active_generation,
    settingsRevision: completed.settings.witness.acceptedRevision,
    bVectorHits: JSON.parse(bVector.text).results.length }));
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
  requireThat(envelope.preparation?.replaySchema === 'recorded-installer-generation-v3',
    `${label} installer activation did not retain the v3 generation plan: ${JSON.stringify(envelope)}`);
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
  mixedChat = process.env.JUSTSEARCH_WRITER_RECOVERY_MIXED_CHAT === '1' }) {
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
  const targetEP = mixedChat ? 'CUDA' : 'CPU';
  const downloadProfile = mixedChat ? 'GPU_FULL' : 'CPU';
  for (const packageId of ['embedding', 'ner', 'splade']) {
    const pkg = registry.packages.find(entry => entry.id === packageId);
    const variant = pkg?.variants.find(entry => entry.targetEP === targetEP);
    requireThat(pkg && variant, `installer fixture lacks ${targetEP} registry variant for ${packageId}`);
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
    installedModels[packageId] = {
      packageId, variantFilename: variant.filename, precision: variant.precision,
      targetEP: variant.targetEP, targetDir: pkg.targetDir, sha256: variant.sha256,
      installedFiles: [variant.filename, ...required.map(file => file.filename)],
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
  const modelDir = path.join(candidateRoot, 'onnx', 'gte-multilingual-base');
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
