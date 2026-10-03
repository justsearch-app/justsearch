import fs from 'node:fs';
import { createHash } from 'node:crypto';
import path from 'node:path';

function settingsFileWitness(file) {
  if (!fs.existsSync(file)) return { present: false, bytes: 0, sha256: null };
  const bytes = fs.readFileSync(file);
  return { present: true, bytes: bytes.length,
    sha256: createHash('sha256').update(bytes).digest('hex') };
}
export async function exerciseIndexLockRecovery(c) {
  const { scenario, work, data, indexBase, first, manifest, apiPort, readJson, waitFor,
    request, post, requireThat, requireOperationSuccess, createOperationKey, matchingHit } = c;
  const release = scenario === 'lock-index-release';
  const exhaustion = scenario === 'lock-index-exhaustion'
    || scenario === 'lock-index-exhaustion-no-ai';
  requireThat(release || exhaustion, `unsupported index-lock recovery scenario: ${scenario}`);
  const runtime = path.join(data, 'runtime');
  const firstPid = Number(first.pid);
  requireThat(Number.isInteger(firstPid) && firstPid > 0,
    `initial supervisor pid was not usable: ${JSON.stringify(first)}`);
  const initial = await waitFor('initial index-start barrier', 90000,
    () => readJson(path.join(runtime, 'index-start-initial-reached.json')));
  requireThat(initial.point === 'index-start' && initial.attempt === 'initial'
    && initial.recoveryAttempts === 0 && initial.indexState === 'STARTING'
    && Number.isFinite(Date.parse(initial.stateSince ?? '')),
  `invalid initial barrier marker: ${JSON.stringify(initial)}`);
  requireThat(Number(initial.pid) === firstPid && Number(manifest.pid) === firstPid,
    `initial barrier pid did not match supervisor/manifest: ${JSON.stringify({ initial, first, manifest })}`);
  requireThat(path.resolve(initial.indexBase) === path.resolve(indexBase),
    `initial barrier used an unexpected index base: ${JSON.stringify(initial)}`);
  const settingsPath = path.join(data, 'ui', 'settings.json');
  const settingsFileBefore = settingsFileWitness(settingsPath);
  const settingsBefore = await waitFor('settings v2 witness before recovery', 30000,
    async () => {
      try {
        const response = await request(apiPort, '/api/settings/v2', {}, 15000);
        if (response.status !== 200) return null;
        const value = JSON.parse(response.text);
        return value?.witness ? value : null;
      } catch { return null; }
    });
  let lastDeadlineObservation = null;
  let deadlineStatus;
  try {
    // Observation includes the 60s initial wait, monitor tick, and a bounded status request.
    // The assertion below still proves the component's own declared deadline elapsed.
    deadlineStatus = await waitFor('index start deadline while barrier is held', 100000,
    async () => {
      try {
        const response = await request(apiPort, '/api/status', {}, 15000);
        lastDeadlineObservation = { observedAt: new Date().toISOString(), response };
        if (response.status !== 200) return null;
        const status = JSON.parse(response.text);
        const index = status.readiness?.engineComponents?.index;
        const stateSinceMs = Date.parse(index?.stateSince ?? '');
        const markerStateSinceMs = Date.parse(initial.stateSince);
        const deadlineMs = Number(index?.deadlineMs);
        const waitedResource = String(index?.evidence ?? '').toLowerCase();
        const statusStateSinceObserved = Number.isFinite(stateSinceMs);
        const markerDeadlineElapsed = Number.isFinite(markerStateSinceMs)
          && Number.isFinite(deadlineMs) && deadlineMs > 0
          && Date.now() - markerStateSinceMs >= deadlineMs;
        return status.readiness?.engineComponents?.api?.state === 'READY'
          && index?.state === 'FAILED'
          && index.recoveryAttempts === 0
          && index.reasonCode === 'component.start_deadline'
          && waitedResource.includes('index root lock at')
          && statusStateSinceObserved && markerDeadlineElapsed ? status : null;
      } catch (error) {
        lastDeadlineObservation = { observedAt: new Date().toISOString(), error: String(error) };
        return null;
      }
    });
  } catch (error) {
    fs.writeFileSync(path.join(work, 'index-start-deadline-failure.json'),
      JSON.stringify({ initial, lastDeadlineObservation }, null, 2));
    throw error;
  }
  fs.writeFileSync(path.join(work, 'index-start-deadline-proved'),
    JSON.stringify({ initial, status: deadlineStatus, observedAt: new Date().toISOString() }));
  console.log('INDEX_START_DEADLINE', JSON.stringify({ initial, status: deadlineStatus }));
  await waitFor('initial barrier release after deadline proof', 30000,
    () => fs.existsSync(path.join(runtime, 'index-start-initial-release')));
  const recovery = await waitFor('index-start recovery-1 barrier', 120000,
    () => readJson(path.join(runtime, 'index-start-recovery-1-reached.json')));
  requireThat(recovery.point === 'index-start' && recovery.attempt === 'recovery-1'
    && recovery.recoveryAttempts === 1 && recovery.indexState === 'STARTING'
    && Number(recovery.pid) === firstPid,
  `invalid recovery barrier marker: ${JSON.stringify(recovery)}`);
  requireThat(path.resolve(recovery.indexBase) === path.resolve(indexBase),
    `recovery barrier used an unexpected index base: ${JSON.stringify(recovery)}`);
  const sessionToken = manifest.head?.sessionToken;
  const recoveryHeaders = { 'content-type': 'application/json' };
  if (typeof sessionToken === 'string' && sessionToken.length > 0) {
    recoveryHeaders['X-JustSearch-Session'] = sessionToken;
  }
  const busyResponse = await request(apiPort, '/api/engine/components/index/recover', {
    method: 'POST', headers: recoveryHeaders, body: '{}',
  }, 15000);
  let busyBody;
  try { busyBody = JSON.parse(busyResponse.text); } catch { busyBody = null; }
  requireThat(busyResponse.status === 429 && busyBody?.errorCode === 'ADMISSION_ENGINE_LIMIT',
    `manual recovery during the automatic attempt was not refused as busy: HTTP ${busyResponse.status} ${busyResponse.text}`);
  const busyRecovery = { status: busyResponse.status, body: busyBody };
  // The run directory receives an archive only at stop; attempts append to the live data log.
  const refusalLogPath = path.join(data, 'logs', 'engine.log');
  const refusalMarker = Buffer.from('Failed to acquire index root lock');
  const expectedIndexLockPath = path.normalize(path.resolve(`${indexBase}.index.lock`));
  const isCausalIndexLockFailure = (index, recoveryAttempts) => {
    const evidence = String(index?.evidence ?? '').toLowerCase();
    return index?.state === 'FAILED'
      && index.recoveryAttempts === recoveryAttempts
      && index.reasonCode === 'component.recovery_failed'
      && evidence.includes('index base path is already locked by another process:')
      && evidence.includes(expectedIndexLockPath.toLowerCase());
  };
  const firstRefusal = await waitFor('first root-lock refusal evidence', 30000, () => {
    if (!fs.existsSync(refusalLogPath)) return null;
    const text = fs.readFileSync(refusalLogPath, 'utf8');
    return text.includes('Failed to acquire index root lock')
      ? { log: refusalLogPath, offset: Buffer.byteLength(text), observedAt: new Date().toISOString() } : null;
  });
  const recoveryProof = path.join(work, 'index-start-recovery-proofed');
  let attemptFailures = null;
  if (exhaustion) {
    attemptFailures = (async () => {
      let attemptOneLast = null;
      let attemptOneLastMatching = null;
      let failedOne;
      try {
        failedOne = await waitFor('index FAILED after recovery attempt 1', 120000,
          async () => {
            try {
              const response = await request(apiPort, '/api/status', {}, 15000);
              const text = fs.existsSync(refusalLogPath)
                ? fs.readFileSync(refusalLogPath, 'utf8') : '';
              const suffix = Buffer.from(text).subarray(firstRefusal.offset);
              const relativeRefusalOffset = suffix.indexOf(refusalMarker);
              const status = response.status === 200 ? JSON.parse(response.text) : null;
              const index = status?.readiness?.engineComponents?.index;
              attemptOneLast = { observedAt: new Date().toISOString(), httpStatus: response.status,
                status, index, refusalLog: refusalLogPath, refusalSearchOffset: firstRefusal.offset,
                refusalOffset: relativeRefusalOffset < 0
                  ? null : firstRefusal.offset + relativeRefusalOffset };
              if (index?.state === 'FAILED' && index.recoveryAttempts === 1) {
                attemptOneLastMatching = attemptOneLast;
              }
              return isCausalIndexLockFailure(index, 1)
                && relativeRefusalOffset >= 0 ? attemptOneLast : null;
            } catch (error) {
              attemptOneLast = { observedAt: new Date().toISOString(),
                error: String(error?.stack ?? error), refusalLog: refusalLogPath,
                refusalSearchOffset: firstRefusal.offset };
              return null;
            }
          });
      } catch (error) {
        fs.writeFileSync(path.join(work, 'index-recovery-attempt-1-failure.json'),
          JSON.stringify({ lastObservation: attemptOneLast,
            lastMatchingFailedObservation: attemptOneLastMatching,
            expectedIndexLockPath,
            waitError: String(error?.stack ?? error) }, null, 2));
        throw error;
      }
      const oneOffset = failedOne.refusalOffset + refusalMarker.byteLength;
      let attemptTwoLast = null;
      let attemptTwoLastMatching = null;
      let failedTwo;
      try {
        failedTwo = await waitFor('index FAILED after recovery attempt 2 with refusal evidence', 120000,
          async () => {
            try {
              const response = await request(apiPort, '/api/status', {}, 15000);
              const text = fs.existsSync(refusalLogPath)
                ? fs.readFileSync(refusalLogPath, 'utf8') : '';
              const suffix = Buffer.from(text).subarray(oneOffset);
              const relativeRefusalOffset = suffix.indexOf(refusalMarker);
              const status = response.status === 200 ? JSON.parse(response.text) : null;
              const index = status?.readiness?.engineComponents?.index;
              attemptTwoLast = { observedAt: new Date().toISOString(), httpStatus: response.status,
                status, index, refusalLog: refusalLogPath, refusalSearchOffset: oneOffset,
                refusalOffset: relativeRefusalOffset < 0 ? null : oneOffset + relativeRefusalOffset };
              if (index?.state === 'FAILED' && index.recoveryAttempts === 2) {
                attemptTwoLastMatching = attemptTwoLast;
              }
              return isCausalIndexLockFailure(index, 2)
                && relativeRefusalOffset >= 0 ? attemptTwoLast : null;
            } catch (error) {
              attemptTwoLast = { observedAt: new Date().toISOString(),
                error: String(error?.stack ?? error), refusalLog: refusalLogPath,
                refusalSearchOffset: oneOffset };
              return null;
            }
          });
      } catch (error) {
        fs.writeFileSync(path.join(work, 'index-recovery-attempt-2-failure.json'),
          JSON.stringify({ lastObservation: attemptTwoLast,
            lastMatchingFailedObservation: attemptTwoLastMatching,
            expectedIndexLockPath,
            waitError: String(error?.stack ?? error) }, null, 2));
        throw error;
      }
      return { failedOne, failedTwo };
    })();
  }
  fs.writeFileSync(recoveryProof, JSON.stringify({
    initialRefusal: { log: refusalLogPath, offset: firstRefusal.offset,
      observedAt: firstRefusal.observedAt },
    recovery, observedAt: new Date().toISOString(),
  }));
  if (release) {
    const ready = await waitFor('same-incarnation index READY after lock release', 120000,
      async () => {
        try {
          const response = await request(apiPort, '/api/status', {}, 15000);
          if (response.status !== 200) return null;
          const status = JSON.parse(response.text);
          return status.readiness?.engineComponents?.api?.state === 'READY'
            && status.readiness?.engineComponents?.index?.state === 'READY'
            && status.readiness.engineComponents.index.recoveryAttempts === 1 ? status : null;
        } catch { return null; }
      });
    const finalSupervisor = readJson(path.join(runtime, 'supervisor.v1.json'));
    const finalManifest = readJson(path.join(runtime, 'manifest.json'));
    requireThat(finalSupervisor?.runId === first.runId
      && finalSupervisor?.incarnation === first.incarnation,
    `release arc changed supervisor identity: ${JSON.stringify(finalSupervisor)}`);
    requireThat(finalManifest?.instanceId === manifest.instanceId,
      `release arc changed Engine instance: ${JSON.stringify(finalManifest)}`);
    const settings = await waitFor('release-arc settings v2 witness after recovery', 30000,
      async () => {
        try {
          const response = await request(apiPort, '/api/settings/v2', {}, 15000);
          return response.status === 200 ? JSON.parse(response.text) : null;
        } catch { return null; }
      });
    requireThat(JSON.stringify(settings) === JSON.stringify(settingsBefore),
      'release arc changed the settings API witness while recovering the index');
    requireThat(JSON.stringify(settingsFileWitness(settingsPath)) === JSON.stringify(settingsFileBefore),
      'release arc changed settings.json presence or bytes while recovering the index');
    const vectorDoc = path.join(work, 'index-lock-release-vector.txt');
    const marker = 'index-lock-release-unique-vector-marker';
    fs.writeFileSync(vectorDoc, `${marker} standard model recovery proof\n`);
    const operationKey = createOperationKey();
    const receiptResponse = await waitFor('release-arc vector document acceptance', 90000,
      async () => {
        try {
          const response = await post(apiPort, '/api/knowledge/ingest', {
            paths: [vectorDoc], idempotencyKey: operationKey,
          });
          return response.status >= 200 && response.status < 300 ? response : null;
        } catch { return null; }
      });
    const receipt = requireOperationSuccess(receiptResponse, 'release-arc vector ingest');
    const vector = await waitFor('release-arc real standard-model VECTOR query', 120000,
      async () => {
        try {
          const response = await post(apiPort, '/api/knowledge/search',
            { query: marker, limit: 10, mode: 'vector' }, 30000);
          if (response.status !== 200) return null;
          const body = JSON.parse(response.text);
          return body.searchTrace?.effectiveMode === 'VECTOR'
            && matchingHit(response, vectorDoc, marker) ? body : null;
        } catch { return null; }
      });
    console.log('INDEX_LOCK_RELEASE_PASS', JSON.stringify({ first, manifest, initial,
      deadlineStatus, recovery, ready, finalSupervisor, finalManifest, settings, settingsBefore,
      busyRecovery, refusal: firstRefusal, receipt, vector, vectorDoc }));
    return;
  }
  const failures = await attemptFailures;
  const escalated = await waitFor('counted escalated restart after two index refusals', 180000,
    () => {
      const supervisor = readJson(path.join(runtime, 'supervisor.v1.json'));
      const lastExit = supervisor?.lastExit;
      return supervisor?.runId === first.runId && supervisor?.incarnation === first.incarnation
        && lastExit?.incarnation === first.incarnation
        && lastExit?.code === 5 && lastExit?.class === 'TRANSIENT'
        && lastExit?.counted === true && lastExit.reason === 'escalated_restart'
        ? { supervisor, lastExit } : null;
    });
  const firstIncarnationLog = await waitFor('root-lock refusal after recovery attempt 1', 30000, () => {
    const log = path.join(work, 'state', 'runs', first.runId, 'incarnations',
      String(first.incarnation), 'logs', 'engine.log');
    if (!fs.existsSync(log)) return null;
    const text = fs.readFileSync(log, 'utf8');
    const suffix = Buffer.from(text).subarray(firstRefusal.offset).toString('utf8');
    return suffix.includes('Failed to acquire index root lock')
      ? { log, offset: firstRefusal.offset, refusalEvidence: true,
        observedAt: new Date().toISOString() } : null;
  });
  requireThat(firstIncarnationLog.refusalEvidence === true,
    'exhaustion arc lacked a post-recovery root-lock refusal record');
  const successor = await waitFor('successor manifest after escalated restart', 120000, () => {
    const supervisor = readJson(path.join(runtime, 'supervisor.v1.json'));
    const next = readJson(path.join(runtime, 'manifest.json'));
    return supervisor?.runId === first.runId && supervisor?.incarnation === first.incarnation + 1
      && next?.instanceId && next.instanceId !== manifest.instanceId
      && next.pid === supervisor.pid ? { supervisor, manifest: next } : null;
  });
  const ready = await waitFor('successor index READY after escalated restart', 120000,
    async () => {
      try {
        const response = await request(successor.manifest.head.apiPort, '/api/status', {}, 15000);
        if (response.status !== 200) return null;
        const status = JSON.parse(response.text);
        return status.readiness?.engineComponents?.api?.state === 'READY'
          && status.readiness?.engineComponents?.index?.state === 'READY' ? status : null;
      } catch { return null; }
    });
  if (scenario === 'lock-index-exhaustion') {
    const vectorDoc = path.join(work, 'index-lock-exhaustion-vector.txt');
    const marker = 'index-lock-exhaustion-unique-vector-marker';
    fs.writeFileSync(vectorDoc, `${marker} standard model successor proof\n`);
    const operationKey = createOperationKey();
    const receiptResponse = await waitFor('exhaustion-arc vector document acceptance', 90000,
      async () => {
        try {
          const response = await post(successor.manifest.head.apiPort, '/api/knowledge/ingest', {
            paths: [vectorDoc], idempotencyKey: operationKey,
          });
          return response.status >= 200 && response.status < 300 ? response : null;
        } catch { return null; }
      });
    const receipt = requireOperationSuccess(receiptResponse, 'exhaustion-arc vector ingest');
    const vector = await waitFor('exhaustion-arc real standard-model VECTOR query', 120000,
      async () => {
        try {
          const response = await post(successor.manifest.head.apiPort, '/api/knowledge/search',
            { query: marker, limit: 10, mode: 'vector' }, 30000);
          if (response.status !== 200) return null;
          const body = JSON.parse(response.text);
          return body.searchTrace?.effectiveMode === 'VECTOR'
            && matchingHit(response, vectorDoc, marker) ? body : null;
        } catch { return null; }
      });
    const settings = await waitFor('exhaustion-arc settings v2 witness after successor', 30000,
      async () => {
        try {
          const response = await request(successor.manifest.head.apiPort, '/api/settings/v2', {}, 15000);
          return response.status === 200 ? JSON.parse(response.text) : null;
        } catch { return null; }
      });
    requireThat(JSON.stringify(settings) === JSON.stringify(settingsBefore),
      'exhaustion arc changed the settings API witness across escalation');
    requireThat(JSON.stringify(settingsFileWitness(settingsPath)) === JSON.stringify(settingsFileBefore),
      'exhaustion arc changed settings.json presence or bytes across escalation');
    console.log('INDEX_LOCK_EXHAUSTION_PASS', JSON.stringify({ first, manifest, initial,
      deadlineStatus, recovery, busyRecovery, escalated, failures, refusal: firstRefusal,
      postRecoveryRefusal: firstIncarnationLog, successor, ready, settings, settingsBefore,
      receipt, vector, vectorDoc }));
    return;
  }

  const textDoc = path.join(work, 'index-lock-exhaustion-text.txt');
  const marker = 'index-lock-exhaustion-unique-text-marker';
  fs.writeFileSync(textDoc, `${marker} no-model successor proof\n`);
  const operationKey = createOperationKey();
  const receiptResponse = await waitFor('no-model exhaustion text document acceptance', 90000,
    async () => {
      try {
        const response = await post(successor.manifest.head.apiPort, '/api/knowledge/ingest', {
          paths: [textDoc], idempotencyKey: operationKey,
        });
        return response.status >= 200 && response.status < 300 ? response : null;
      } catch { return null; }
    });
  const receipt = requireOperationSuccess(receiptResponse, 'no-model exhaustion text ingest');
  const text = await waitFor('no-model exhaustion TEXT query', 90000,
    async () => {
      try {
        const response = await post(successor.manifest.head.apiPort, '/api/knowledge/search',
          { query: marker, limit: 10, mode: 'text' }, 15000);
        return response.status === 200 && matchingHit(response, textDoc, marker)
          ? JSON.parse(response.text) : null;
      } catch { return null; }
    });
  const settings = await waitFor('no-model exhaustion settings v2 witness after successor', 30000,
    async () => {
      try {
        const response = await request(successor.manifest.head.apiPort, '/api/settings/v2', {}, 15000);
        return response.status === 200 ? JSON.parse(response.text) : null;
      } catch { return null; }
    });
  requireThat(JSON.stringify(settings) === JSON.stringify(settingsBefore),
    'no-model exhaustion changed the settings API witness across escalation');
  requireThat(JSON.stringify(settingsFileWitness(settingsPath)) === JSON.stringify(settingsFileBefore),
    'no-model exhaustion changed settings.json presence or bytes across escalation');
  const finalSupervisor = readJson(path.join(runtime, 'supervisor.v1.json'));
  const finalManifest = readJson(path.join(runtime, 'manifest.json'));
  const initialRestartCount = Number(first.restartCount);
  const finalExit = finalSupervisor?.lastExit;
  requireThat(Number.isInteger(initialRestartCount) && initialRestartCount >= 0,
    `initial restart count was not usable: ${JSON.stringify(first)}`);
  requireThat(finalSupervisor?.state === 'running'
      && finalSupervisor.runId === first.runId
      && finalSupervisor.incarnation === first.incarnation + 1
      && finalSupervisor.restartCount === initialRestartCount + 1
      && finalExit?.incarnation === first.incarnation
      && finalExit?.code === 5 && finalExit?.class === 'TRANSIENT'
      && finalExit?.counted === true && finalExit?.reason === 'escalated_restart',
  `no-model exhaustion did not retain exactly one counted exit-5 successor: ${JSON.stringify({
    first, finalSupervisor,
  })}`);
  requireThat(finalManifest?.pid === finalSupervisor.pid
      && finalManifest.instanceId === successor.manifest.instanceId
      && finalManifest.instanceId !== manifest.instanceId,
  `no-model exhaustion successor manifest identity changed: ${JSON.stringify({
    initial: manifest, successor: successor.manifest, finalManifest,
  })}`);
  console.log('INDEX_LOCK_EXHAUSTION_NO_AI_PASS', JSON.stringify({ first, manifest, initial,
    deadlineStatus, recovery, busyRecovery, escalated, failures, refusal: firstRefusal,
    postRecoveryRefusal: firstIncarnationLog, successor, ready, settings, settingsBefore,
    receipt, text, textDoc, finalSupervisor, finalManifest }));
}
