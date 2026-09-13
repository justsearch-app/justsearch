import fs from 'node:fs';
import path from 'node:path';

// The former embedded EngineFileLockContentionTest workload, through a killable Engine.
// JUnit owns the real FileChannel intruder; the ordinary dev runner owns all product children.
export async function exerciseHostileLocks(c) {
  const { work, data, first, readJson, waitFor, request, post, requireThat, acceptedCount } = c;
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
  const ingested = await post(submittedBinding.port, '/api/knowledge/ingest', { paths });
  requireThat(ingested.status === 200 && acceptedCount(ingested) === paths.length,
    `documents under lock contention must be accepted: ${JSON.stringify(ingested)}`);
  let releasedAfterExit = null;
  const result = await waitFor('documents under hostile locks become searchable', 180000, async () => {
    const supervisor = readJson(path.join(data, 'runtime', 'supervisor.v1.json'));
    if (!releasedAfterExit && supervisor?.runId === first.runId && supervisor.lastExit?.counted
      && supervisor.lastExit.incarnation >= submittedBinding.incarnation) {
      // A tragic Lucene write cannot be retried in that writer. After the observed fatal exit,
      // stop injecting new faults and prove recovery of the already-accepted corpus. Re-attacking
      // every successor indefinitely tests restart-budget exhaustion instead of transient recovery.
      fs.writeFileSync(path.join(work, 'intruder-stop'), 'stop');
      await waitFor('JUnit releases hostile locks after the observed Engine exit', 10000,
        () => fs.existsSync(path.join(work, 'intruder-stopped')));
      releasedAfterExit = supervisor.lastExit;
      console.log('LOCK_FAULT_RELEASED', JSON.stringify(releasedAfterExit));
    }
    try {
      const livePort = currentBinding()?.port;
      if (!livePort) return null;
      const response = await post(livePort, '/api/knowledge/search', {
        query: `${name.replaceAll('-', ' ')} probe`, limit: 10, mode: 'text',
      });
      const hit = JSON.parse(response.text).results?.find(candidate =>
        paths.some(file => path.resolve(file).toLowerCase()
          === String(candidate.fields?.path ?? '').toLowerCase()));
      return response.status === 200 && hit ? { port: livePort, hit } : null;
    } catch { return null; }
  });
  requireThat((await request(result.port, '/api/health')).status === 200,
    'the Engine must be healthy after indexing under contention');
  console.log('LOCK_SURVIVAL_PASS', JSON.stringify({ scenario: name,
    accepted: acceptedCount(ingested), submittedBinding, hit: result.hit, releasedAfterExit,
    supervisor: readJson(path.join(data, 'runtime', 'supervisor.v1.json')) }));
}
