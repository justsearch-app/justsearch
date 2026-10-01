import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { barrierFiles } from './barrier-files.mjs';

/** Installed ordinary-settings proof for the query-only composition owner. */
export async function exerciseQueryReconfigure(c) {
  const { mode, work, data, indexBase, modelsRoot, apiPort, manifest, first,
    readJson, waitFor, request, post, requireThat, createOperationKey,
    operationKey: forcedOperationKey } = c;
  const source = path.join(modelsRoot, 'onnx', 'reranker');
  const stage = (name) => {
    const destination = path.join(work, name);
    fs.mkdirSync(destination, { recursive: true });
    for (const entry of fs.readdirSync(source, { withFileTypes: true })) {
      if (!entry.isFile()) continue;
      const from = path.join(source, entry.name);
      const to = path.join(destination, entry.name);
      if (fs.existsSync(to)) continue;
      if (/\.onnx$/i.test(entry.name)) fs.linkSync(from, to);
      else fs.copyFileSync(from, to);
    }
    requireThat(fs.existsSync(path.join(destination, 'model_fp16.onnx'))
      && fs.existsSync(path.join(destination, 'tokenizer.json')),
    `query reconfigure ${name} lacks CUDA reranker bytes`);
    return destination;
  };
  const hash = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
  // A is the retained source reranker the Engine boots with. The launcher sets
  // JUSTSEARCH_QUERY_RECONFIGURE_A on the Engine's environment, not on this process.
  const a = path.resolve(source);
  const b = stage(`query-reranker-${mode.toLowerCase()}-b`);
  const sourceFp16 = path.join(source, 'model_fp16.onnx');
  const sourceFp16Hash = hash(sourceFp16);
  const bFp16 = path.join(b, 'model_fp16.onnx');
  fs.unlinkSync(bFp16);
  // ONNX ModelProto field 14 is repeated metadata_props; its entry is StringStringEntryProto
  // with key field 1 and value field 2. Appending this length-delimited entry changes only model
  // metadata, leaving the graph intact. Primary schema:
  // https://raw.githubusercontent.com/onnx/onnx/main/onnx/onnx.proto
  const metadataKey = Buffer.from('justsearch.reconfigure.test', 'utf8');
  const metadataValue = Buffer.from('B', 'utf8');
  const metadataEntry = Buffer.concat([
    Buffer.from([0x0a, metadataKey.length]), metadataKey,
    Buffer.from([0x12, metadataValue.length]), metadataValue,
  ]);
  requireThat(metadataKey.length < 128 && metadataValue.length < 128
      && metadataEntry.length < 128, 'ONNX test metadata exceeded single-byte protobuf lengths');
  fs.writeFileSync(bFp16, Buffer.concat([
    fs.readFileSync(sourceFp16),
    Buffer.from([0x72, metadataEntry.length]), metadataEntry,
  ]));
  requireThat(hash(sourceFp16) === sourceFp16Hash && hash(bFp16) !== sourceFp16Hash,
    'distinct B metadata write changed retained A or failed to create a distinct B');
  const witnessPath = value => value?.startsWith('file:') ? fileURLToPath(value) : value;
  const selectedRole = (expectedDir, label) => {
    const persisted = readJson(path.join(data, 'ui', 'settings.json'));
    const role = persisted?.queryRoles?.reranker;
    const expected = path.join(expectedDir, 'model_fp16.onnx');
    requireThat(role?.state === 'SELECTED'
        && role.targetEp === 'CUDA'
        && path.resolve(witnessPath(role.model?.path) ?? '').toLowerCase()
          === path.resolve(expected).toLowerCase()
        && role.model?.sha256 === hash(expected),
    `${label} did not persist the exact CUDA reranker identity: ${JSON.stringify(role)}`);
    return { path: path.resolve(witnessPath(role.model.path)), sha256: role.model.sha256 };
  };
  const read = (response, label) => {
    try { return JSON.parse(response.text); }
    catch { throw new Error(`${label} returned invalid JSON: ${response.text}`); }
  };
  const settings = async () => {
    const response = await request(apiPort, '/api/settings/v2', {}, 15000);
    requireThat(response.status === 200, `settings read failed: ${response.text}`);
    return read(response, 'settings');
  };
  const status = async () => {
    const response = await request(apiPort, '/api/status', {}, 15000);
    requireThat(response.status === 200, `status read failed: ${response.text}`);
    return read(response, 'status');
  };
  const runtime = async (label) => {
    const response = await request(apiPort, '/api/inference/encoders', {}, 30000);
    const value = read(response, label);
    const reranker = value.encoders?.reranker;
    requireThat(response.status === 200 && value.snapshotStatus === 'ok'
      && reranker?.available === true && reranker.currentAccelerator === 'cuda'
      && reranker.configuredAccelerator === 'CUDA',
    `${label} was not a realized CUDA reranker: ${response.text}`);
    return reranker;
  };
  const query = async (label) => {
    return waitFor(label, 90000, async () => {
      const response = await post(apiPort, '/api/knowledge/search',
        { query: 'query reconfigure availability', limit: 10, mode: 'text' }, 60000);
      const value = read(response, label);
      const crossEncoder = value.searchTrace?.stages?.find(stage => stage.id === 'cross-encoder');
      return response.status === 200 && value.results?.length >= 3
        && crossEncoder?.status === 'executed'
        ? { status: response.status, results: value.results.length, crossEncoder } : null;
    });
  };
  const headers = { 'content-type': 'application/json' };
  if (typeof manifest.head?.sessionToken === 'string' && manifest.head.sessionToken.length > 0) {
    headers['X-JustSearch-Session'] = manifest.head.sessionToken;
  }
  let apiOutages = 0;
  let samples = 0;
  const expectedReason = mode === 'BESIDE' ? 'candidate_fits_free_device_memory'
    : 'candidate_fits_after_source_release';
  const assertComposition = (composition, label) => requireThat(
    composition?.mode === mode && composition.reason === expectedReason
      && Number.isSafeInteger(composition.freeBytes) && composition.freeBytes >= 0
      && Number.isSafeInteger(composition.footprintBytes) && composition.footprintBytes > 0,
    `${label} omitted measured composition evidence: ${JSON.stringify(composition)}`);
  const availabilityRounds = [];
  let barrierOperationKey = forcedOperationKey;
  let heldA = null;
  const observe = (label, issue) => {
    let settled = false;
    const round = { label, samples: 0, outages: 0, samplingStartedAt: Date.now(),
      postIssuedAt: null, settledAt: null };
    const sampling = (async () => {
      while (!settled) {
        try {
          const response = await request(apiPort, '/api/status', {}, 5000);
          round.samples++;
          if (response.status !== 200) round.outages++;
        } catch { round.samples++; round.outages++; }
        await new Promise(resolve => setTimeout(resolve, 25));
      }
    })();
    // Sampling has issued its first status request before this mutation request is issued.
    round.postIssuedAt = Date.now();
    const pending = issue();
    pending.then(() => { settled = true; round.settledAt = Date.now(); }, () => {
      settled = true;
      round.settledAt = Date.now();
    });
    return {
      pending,
      complete: async () => {
        try { return await pending; }
        finally {
          settled = true;
          await sampling;
          apiOutages += round.outages;
          samples += round.samples;
          availabilityRounds.push(round);
        }
      },
    };
  };
  const apply = async (modelPath, label) => {
    const before = await settings();
    const operationKey = barrierOperationKey ?? createOperationKey();
    const observed = observe(label, () => request(apiPort, '/api/settings/v2', {
      method: 'POST', headers,
      body: JSON.stringify({ rerankerModelPath: modelPath,
        witness: before.witness, operationKey }),
    }, 240000));
    let degradation = null;
    if (heldA != null) {
      if (mode === 'IN_PLACE') {
        const during = await waitFor('in-place RELOADING while issued A remains held', 120000,
          async () => {
            const value = await status();
            return value.readiness?.engineComponents?.encoders?.state === 'RELOADING'
              ? value : null;
          });
        const healthResponse = await request(apiPort, '/api/health', {}, 15000);
        const health = read(healthResponse, 'in-place degraded health sample');
        const lexicalResponse = await post(apiPort, '/api/knowledge/search',
          { query: 'query reconfigure availability', limit: 10, mode: 'text' }, 30000);
        const lexical = read(lexicalResponse, 'in-place degraded lexical query');
        requireThat(!heldA.settled && health != null && [200, 503].includes(healthResponse.status)
            && lexicalResponse.status === 200 && lexical.results?.length > 0,
        `in-place issued A did not hold across degraded serving: ${JSON.stringify({
          heldSettled: heldA.settled, healthStatus: healthResponse.status,
          lexicalStatus: lexicalResponse.status })}`);
        degradation = { observedAt: Date.now(), healthStatus: healthResponse.status,
          lexicalResults: lexical.results.length,
          encoders: during.readiness.engineComponents.encoders };
      } else {
        const published = await waitFor('B publication while issued A remains held', 120000,
          async () => {
            const value = await status();
            return path.resolve(value.worker?.gpu?.rerankerModelPath ?? '').toLowerCase()
                === path.resolve(modelPath).toLowerCase()
              && value.readiness?.engineComponents?.encoders?.state === 'READY'
              ? value : null;
          });
        requireThat(!heldA.settled,
          `issued A completed before BESIDE B publication: ${JSON.stringify(published)}`);
        degradation = { observedAt: Date.now(), issuedAAcrossPublication: true,
          encoders: published.readiness.engineComponents.encoders };
      }
      const issuedAReleasedAt = Date.now();
      fs.writeFileSync(heldA.releaseFile, `${issuedAReleasedAt}\n`);
      const issuedOutcome = await heldA.outcome;
      requireThat(!issuedOutcome.error && issuedOutcome.value.status === 200
          && read(issuedOutcome.value, 'held A completion').results?.length > 0,
      `issued A failed after release: ${issuedOutcome.error?.message ?? issuedOutcome.value?.text}`);
      degradation.issuedA = { reached: heldA.reached, issuedAReleasedAt,
        completedAt: Date.now(), completedAfterRelease: true };
      heldA = null;
    }
    if (barrierOperationKey != null) {
      const { reachedFile, releaseFile } = barrierFiles(data);
      const reached = await waitFor('in-place query preparation barrier', 120000,
        () => readJson(reachedFile));
      requireThat(reached?.phase === 'settings-mid-compose'
          && reached.operationKey === operationKey && reached.cursor === 'encoders'
          && reached.pid === first.pid,
      `in-place query barrier did not identify the encoder owner: ${JSON.stringify(reached)}`);
      const during = await status();
      requireThat(during.readiness?.engineComponents?.encoders?.state === 'RELOADING',
        `composition barrier lost RELOADING: ${JSON.stringify(during.readiness)}`);
      degradation.compositionBarrier = { reached,
        encoders: during.readiness.engineComponents.encoders };
      fs.writeFileSync(releaseFile, `${Date.now()}\n`);
      barrierOperationKey = null;
    }
    const response = await observed.complete();
    const result = read(response, label);
    requireThat(response.status === 200 && result.state === 'COMPLETE'
      && result.operationKey === operationKey
      && result.witness?.acceptedRevision === before.witness.acceptedRevision + 1,
    `${label} failed: HTTP ${response.status} ${response.text}`);
    const composition = result.composition;
    assertComposition(composition, label);
    return { operationKey, before: before.witness, after: result.witness,
      composition, degradation };
  };

  const generation = readJson(path.join(indexBase, 'state.json'))?.active_generation;
  requireThat(typeof generation === 'string' && generation.length > 0,
    'query reconfigure lacks an active index generation');
  const queryA = await query('model query A');
  const initialStatus = await status();
  const appliedA = initialStatus.readiness?.engineComponents?.encoders?.appliedVersion;
  const initialModel = path.join(a, 'model_fp16.onnx');
  const initialIdentity = { path: initialModel, sha256: hash(initialModel) };
  requireThat(typeof appliedA === 'string' && appliedA.length > 0
      && path.resolve(initialStatus.worker?.gpu?.rerankerModelPath ?? '').toLowerCase()
        === a.toLowerCase()
      && initialIdentity.sha256.length === 64,
    `initial A identity is not the retained CUDA reranker: ${JSON.stringify({
      readiness: initialStatus.readiness, gpu: initialStatus.worker?.gpu, initialIdentity })}`);
  const initial = await waitFor('realized CUDA reranker A', 90000, async () => {
    try { return await runtime('runtime A'); } catch { return null; }
  });
  const issuedBarrier = barrierFiles(data, 'issued-a-search');
  heldA = { settled: false, reached: null, releaseFile: issuedBarrier.releaseFile };
  heldA.outcome = post(apiPort, '/api/knowledge/search',
    { query: 'query reconfigure held lease', limit: 10, mode: 'text' }, 180000)
    .then(value => ({ value }), error => ({ error }))
    .then(outcome => { heldA.settled = true; return outcome; });
  const heldReached = await waitFor('A query captured before query reconfigure', 30000,
    () => readJson(issuedBarrier.reachedFile));
  requireThat(heldReached?.query === 'query reconfigure held lease'
      && heldReached.pid === first.pid && !heldA.settled,
  `issued query did not retain A: ${JSON.stringify(heldReached)}`);
  heldA.reached = heldReached;
  const toB = await apply(b, `${mode} A to B`);
  const statusB = await waitFor(`${mode} B publication`, 90000, async () => {
    try {
      const value = await status();
      const encoders = value.readiness?.engineComponents?.encoders;
      const compose = encoders == null ? null : {
        mode: encoders.mode, reason: encoders.reason,
        freeBytes: encoders.freeBytes, footprintBytes: encoders.footprintBytes,
      };
      return compose?.mode === mode ? { value, compose } : null;
    } catch { return null; }
  });
  const selectedB = selectedRole(b, `${mode} B`);
  const appliedB = statusB.value.readiness.engineComponents.encoders.appliedVersion;
  requireThat(typeof appliedB === 'string' && appliedB !== appliedA
      && path.resolve(statusB.value.worker?.gpu?.rerankerModelPath ?? '').toLowerCase()
        === b.toLowerCase(),
    `${mode} B did not publish its distinct encoder path/version`);
  const queryB = await query('model query B');
  const realizedB = await runtime('runtime B');
  const toA = await apply(a, `${mode} B to restored A`);
  const statusA = await waitFor(`${mode} restored A publication`, 90000, async () => {
    try {
      const value = await status();
      return value.readiness?.engineComponents?.encoders?.mode === mode ? value : null;
    } catch { return null; }
  });
  const selectedA = selectedRole(a, `${mode} restored A`);
  const appliedRestoredA = statusA.readiness.engineComponents.encoders.appliedVersion;
  requireThat(appliedRestoredA === appliedA && initialIdentity.sha256 !== selectedB.sha256
      && selectedA.sha256 === initialIdentity.sha256
      && path.resolve(statusA.worker?.gpu?.rerankerModelPath ?? '').toLowerCase()
        === a.toLowerCase(),
    `${mode} did not restore A version or byte identity: ${JSON.stringify({
      appliedA, appliedB, appliedRestoredA, selectedA, selectedB })}`);
  const queryRestoredA = await query('model query restored A');
  const restoredA = await runtime('runtime restored A');

  const beforeRefusal = await settings();
  const recoveryAttemptsBefore = statusA.readiness.engineComponents.encoders.recoveryAttempts;
  requireThat(Number.isSafeInteger(recoveryAttemptsBefore),
    `${mode} lacks recovery-attempt evidence before refusal`);
  const witnessBytes = fs.readFileSync(path.join(data, 'ui', 'settings.json'), 'utf8');
  const invalid = stage(`query-reranker-${mode.toLowerCase()}-invalid`);
  const onnxFiles = fs.readdirSync(source).filter(name => /\.onnx$/i.test(name));
  const retainedHashes = Object.fromEntries(onnxFiles.map(name =>
    [name, hash(path.join(source, name))]));
  for (const name of onnxFiles) {
    const candidate = path.join(invalid, name);
    fs.unlinkSync(candidate);
    fs.writeFileSync(candidate, `invalid private ONNX candidate ${name}`);
  }
  requireThat(onnxFiles.length > 0 && onnxFiles.every(name =>
    hash(path.join(source, name)) === retainedHashes[name]
      && hash(path.join(invalid, name)) !== retainedHashes[name]),
  'malformed candidate mutation escaped its private unlinked files');
  const refusalKey = createOperationKey();
  const refusalRequest = { rerankerModelPath: invalid,
    witness: beforeRefusal.witness, operationKey: refusalKey };
  const refusalObserved = observe(`${mode} refusal`, () => request(apiPort, '/api/settings/v2', {
    method: 'POST', headers,
    body: JSON.stringify(refusalRequest),
  }, 120000));
  const refused = await refusalObserved.complete();
  const refusedBody = read(refused, `${mode} refusal`);
  requireThat(refused.status >= 400 && refusedBody.state === 'FAILED',
    `${mode} invalid candidate was not refused: HTTP ${refused.status} ${refused.text}`);
  assertComposition(refusedBody.composition, `${mode} refusal`);
  const afterRefusal = await settings();
  requireThat(JSON.stringify(afterRefusal.witness) === JSON.stringify(beforeRefusal.witness)
      && fs.readFileSync(path.join(data, 'ui', 'settings.json'), 'utf8') === witnessBytes,
    `${mode} refusal changed the committed settings witness`);
  const replayObserved = observe(`${mode} refusal replay`, () => request(
    apiPort, '/api/settings/v2', {
      method: 'POST', headers, body: JSON.stringify(refusalRequest),
    }, 120000));
  const replay = await replayObserved.complete();
  const replayBody = read(replay, `${mode} refusal replay`);
  assertComposition(replayBody.composition, `${mode} refusal replay`);
  const afterReplay = await settings();
  requireThat(replayBody.state === refusedBody.state && replayBody.operationKey === refusalKey
      && JSON.stringify(replayBody.composition) === JSON.stringify(refusedBody.composition)
      && JSON.stringify(afterReplay.witness) === JSON.stringify(beforeRefusal.witness)
      && fs.readFileSync(path.join(data, 'ui', 'settings.json'), 'utf8') === witnessBytes,
  `${mode} same-key refusal replay changed evidence or durable settings: ${replay.text}`);
  const afterRefusalStatus = await status();
  const afterRefusalEncoders = afterRefusalStatus.readiness?.engineComponents?.encoders;
  const expectedRecoveryAttempts = recoveryAttemptsBefore + (mode === 'IN_PLACE' ? 1 : 0);
  requireThat(path.resolve(afterRefusalStatus.worker?.gpu?.rerankerModelPath ?? '').toLowerCase()
      === a.toLowerCase()
      && afterRefusalEncoders?.mode === mode
      && typeof afterRefusalEncoders.reason === 'string'
      && Number.isSafeInteger(afterRefusalEncoders.footprintBytes)
      && afterRefusalEncoders.recoveryAttempts === expectedRecoveryAttempts,
    `${mode} refusal did not restore exact reranker A path/evidence: ${JSON.stringify({
      gpu: afterRefusalStatus.worker?.gpu, encoders: afterRefusalEncoders })}`);
  await query('model query A after refusal');
  const afterRefusalRuntime = await runtime('runtime A after refusal');
  const finalSupervisor = readJson(path.join(data, 'runtime', 'supervisor.v1.json'));
  requireThat(apiOutages === 0 && samples > 0
      && availabilityRounds.length === 4
      && availabilityRounds.every(round => round.samples > 0 && round.outages === 0
        && round.samplingStartedAt <= round.postIssuedAt
        && round.postIssuedAt <= round.settledAt)
      && finalSupervisor?.pid === first.pid && finalSupervisor?.restartCount === first.restartCount
      && readJson(path.join(indexBase, 'state.json'))?.active_generation === generation,
  `${mode} changed API/process/generation continuity: ${JSON.stringify({
    apiOutages, samples, first, finalSupervisor, generation })}`);
  const evidence = toB.composition;
  requireThat(evidence.reason === expectedReason
      && Number.isSafeInteger(evidence.freeBytes) && evidence.freeBytes >= 0
      && Number.isSafeInteger(evidence.footprintBytes) && evidence.footprintBytes > 0
      && (mode !== 'IN_PLACE' || evidence.freeBytes <= 1024 * 1024),
  `${mode} omitted bounded compose evidence: ${JSON.stringify(evidence)}`);
  console.log('QUERY_RECONFIGURE_ROUND_PASS', JSON.stringify({ mode, generation,
    pid: first.pid, restartCount: finalSupervisor.restartCount, apiOutages, samples,
    paths: { a, b }, evidence, versions: { appliedA, appliedB, appliedRestoredA },
    selections: { initialA: initialIdentity, b: selectedB, restoredA: selectedA },
    recoveryAttempts: { beforeRefusal: recoveryAttemptsBefore,
      afterRefusal: afterRefusalEncoders.recoveryAttempts },
    availabilityRounds,
    witnesses: { toB, toA, refusal: beforeRefusal.witness },
    realized: { a: initial, b: realizedB, restoredA, afterRefusal: afterRefusalRuntime },
    queries: { a: queryA, b: queryB, restoredA: queryRestoredA },
    restoredStatus: statusA.readiness.engineComponents.encoders }));
}
