import fs from 'node:fs';
import path from 'node:path';

function lostConnection(error) {
  for (let cause = error; cause; cause = cause.cause) {
    if (['UND_ERR_SOCKET', 'ECONNRESET', 'ECONNREFUSED', 'EPIPE'].includes(cause.code)) return true;
  }
  return false;
}

// The former embedded EngineFileLockContentionTest workload, through a killable Engine.
// JUnit owns the real FileChannel intruder; the ordinary dev runner owns all product children.
export async function exerciseHostileLocks(c) {
  const { work, data, first, readJson, waitFor, request, post, requireThat,
    requireOperationSuccess, createOperationKey } = c;
  const atBoot = process.env.JUSTSEARCH_REAL_RECOVERY_SCENARIO === 'lock-boot';
  if (!atBoot) {
    fs.writeFileSync(path.join(work, 'intruder-start'), 'start');
    await waitFor('JUnit starts the mid-ingest intruder', 10000,
      () => fs.existsSync(path.join(work, 'intruder-started')));
  }
  const name = atBoot ? 'boot-contention' : 'ingest-contention';
  const corpus = path.join(work, 'contention-corpus', name);
  fs.mkdirSync(corpus, { recursive: true });
  const paths = Array.from({ length: 100 }, (_, i) => {
    const file = path.join(corpus, `${name}-${i}.txt`);
    fs.writeFileSync(file, `${name.replaceAll('-', ' ')} probe document ${i}\n`
      + 'the index half must survive a hostile filesystem while writing this\n');
    return file;
  });
  const currentBinding = () => {
    const supervisor = readJson(path.join(data, 'runtime', 'supervisor.v1.json'));
    const manifest = readJson(path.join(data, 'runtime', 'manifest.json'));
    return supervisor?.state === 'running' && supervisor.runId === first.runId
      && manifest?.pid === supervisor.pid && manifest?.instanceId === supervisor.instanceId
      ? { port: manifest.head.apiPort, incarnation: supervisor.incarnation } : null;
  };
  const submittedBinding = await waitFor('a readable owned binding before submitting the corpus', 10000,
    currentBinding);
  const operationKey = createOperationKey();
  let acceptanceConnectionLost = null;
  let ingested;
  try {
    // Exactly one submission: a lost connection never authorizes replay of an unknown outcome.
    ingested = await post(submittedBinding.port, '/api/knowledge/ingest', {
      paths, idempotencyKey: operationKey,
    });
  } catch (error) {
    if (!lostConnection(error)) throw error;
    acceptanceConnectionLost = error;
  }
  if (ingested) {
    const receipt = requireOperationSuccess(ingested, 'hostile-lock ingest');
    requireThat(receipt.operationKey === operationKey,
      `hostile-lock ingest changed its supplied key: ${ingested.text}`);
  }
  const deadline = Date.now() + 180000;
  let releasedAfterExit = null;
  const releaseAfterCountedExit = async () => {
    const supervisor = readJson(path.join(data, 'runtime', 'supervisor.v1.json'));
    if (!releasedAfterExit && supervisor?.runId === first.runId && supervisor.lastExit?.counted
      && supervisor.lastExit.incarnation >= submittedBinding.incarnation) {
      // A tragic Lucene write cannot be retried in that writer. After the observed fatal exit,
      // stop injecting new faults and prove recovery of the already-accepted corpus. Re-attacking
      // every successor indefinitely tests restart-budget exhaustion instead of transient recovery.
      fs.writeFileSync(path.join(work, 'intruder-stop'), 'stop');
      await waitFor('JUnit releases hostile locks after the observed Engine exit', Math.max(1, Math.min(10000, deadline - Date.now())),
        () => fs.existsSync(path.join(work, 'intruder-stopped')));
      releasedAfterExit = supervisor.lastExit;
      console.log('LOCK_FAULT_RELEASED', JSON.stringify(releasedAfterExit));
    }
    return releasedAfterExit;
  };
  if (acceptanceConnectionLost) {
    await waitFor('a counted owned exit justifies resolving the lost acceptance response',
      Math.max(1, deadline - Date.now()), releaseAfterCountedExit);
    console.log('LOCK_ACCEPTANCE_RESPONSE_LOST', JSON.stringify({ operationKey, submittedBinding,
      code: acceptanceConnectionLost.cause?.code ?? acceptanceConnectionLost.code }));
  }
  const expectedPaths = new Set(paths.map(file => path.resolve(file).toLowerCase()));
  const result = await waitFor('the original operation completes and all 100 documents become searchable',
    Math.max(1, deadline - Date.now()), async () => {
    await releaseAfterCountedExit();
    const binding = currentBinding();
    if (!binding || (releasedAfterExit && binding.incarnation <= releasedAfterExit.incarnation)) return null;
    let outcomeResponse;
    let response;
    try {
      outcomeResponse = await request(binding.port, `/api/operation-history/${operationKey}`);
      response = await post(binding.port, '/api/knowledge/search', {
        query: `${name.replaceAll('-', ' ')} probe`, limit: paths.length, mode: 'text',
      });
    } catch (error) {
      if (lostConnection(error) || ['AbortError', 'TimeoutError'].includes(error.name)) return null;
      throw error;
    }
    if (outcomeResponse.status !== 200) return null;
    const outcome = JSON.parse(outcomeResponse.text);
    requireThat(!['unknown', 'expired', 'failed'].includes(outcome.state),
      `the original hostile-lock operation must remain recoverable: ${outcomeResponse.text}`);
    if (response.status !== 200) return null;
    const matched = new Set((JSON.parse(response.text).results ?? [])
      .map(candidate => String(candidate.fields?.path ?? '').toLowerCase())
      .filter(file => expectedPaths.has(file)));
    if (outcome.state !== 'complete' || matched.size !== expectedPaths.size) return null;
    requireThat(outcome.result?.code === 'SUCCESS' && outcome.unitsFailed === 0,
      `the complete corpus must have a successful durable outcome: ${outcomeResponse.text}`);
    return { port: binding.port, incarnation: binding.incarnation, matchedCount: matched.size, outcome };
  });
  requireThat((await request(result.port, '/api/health')).status === 200,
    'the Engine must be healthy after indexing under contention');
  requireThat(Date.now() <= deadline, 'full corpus, durable completion and healthy successor must fit the original 180-second bound');
  console.log('LOCK_SURVIVAL_PASS', JSON.stringify({ scenario: name,
    operationKey, submittedBinding, matchedCount: result.matchedCount, outcome: result.outcome, releasedAfterExit,
    supervisor: readJson(path.join(data, 'runtime', 'supervisor.v1.json')) }));
}
