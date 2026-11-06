import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';

export const ROOT_VOLUME_INITIALIZATION_RETRY = 'root-volume-initialization-retry';

export function prepareRootVolumeInitialization({ work, data }) {
  const corpus = path.join(work, 'root-volume-corpus');
  fs.mkdirSync(corpus, { recursive: true });
  fs.writeFileSync(path.join(data, 'watched_roots.json'), JSON.stringify({
    schemaVersion: 1, roots: [{ path: corpus }],
  }));
}

// JUnit holds the exact pending-ledger temporary file before launching the installed Engine.
// This scenario neither restarts the Engine nor repeats its one accepted ingest request.
export async function exerciseRootVolumeInitialization(c) {
  const { work, data, indexBase, first, apiPort, readJson, waitFor, request, post,
    requireThat, requireOperationSuccess, createOperationKey } = c;
  const locked = path.join(indexBase, 'root-volumes-pending.properties.tmp');
  requireThat(fs.existsSync(path.join(work, 'root-volume-lock-held')),
    'JUnit must acquire the real pending-ledger lock before Engine boot');
  const deadline = Date.now() + 180000;
  const engineLog = path.join(data, 'logs', 'engine.log');
  const failure = 'Upgrade seeding of root volumes could not start; roots stay unconfirmed';
  const initialFailure = await waitFor('the actual boot initialization write failure', 10000, () => {
    const log = fs.existsSync(engineLog) ? fs.readFileSync(engineLog, 'utf8') : '';
    return log.split(/\r?\n/).find(line => line.includes(failure));
  });
  requireThat(!fs.existsSync(path.join(indexBase, 'root-volumes.properties')),
    'the injected failure must precede ledger creation');
  requireThat(!fs.existsSync(path.join(indexBase, 'root-volumes-pending.properties')),
    'the injected failure must precede pending-root publication');
  const corpus = path.join(work, 'root-volume-corpus');
  const paths = [0, 1].map(i => {
    const file = path.join(corpus, `seed-retry-${i}.txt`);
    fs.writeFileSync(file, `rootvolumeretrymarker original accepted content ${i}\n`);
    return file;
  });
  const operationKey = createOperationKey();
  const receiptResponse = await post(apiPort, '/api/knowledge/ingest', { paths, idempotencyKey: operationKey });
  const receipt = requireOperationSuccess(receiptResponse, 'root-volume initialization retry ingest');
  requireThat(receipt.operationKey === operationKey, 'ingest must retain its original operation key');
  const refused = await waitFor('the accepted original job really waits on ROOT_UNAVAILABLE', 30000, () => {
    const database = new DatabaseSync(path.join(data, 'jobs.db'), { readOnly: true });
    try {
      const rows = database.prepare('SELECT path, state, last_reason_code FROM jobs WHERE path IN (?, ?)')
        .all(...paths).filter(row => row.state === 'PENDING'
          && row.last_reason_code === 'ROOT_UNAVAILABLE');
      // The coordinator drives roots serially; a deferred first child can precede the second job.
      return rows.length > 0 ? rows : null;
    } finally { database.close(); }
  });
  console.log('ROOT_VOLUME_INITIALIZATION_REFUSED', JSON.stringify({ operationKey, locked, initialFailure, refused, first }));
  fs.writeFileSync(path.join(work, 'root-volume-lock-release'), 'release after actual initialization and job refusal');
  await waitFor('JUnit releases the exact lock without restarting', 10000,
    () => fs.existsSync(path.join(work, 'root-volume-lock-released')));
  const identity = () => {
    const supervisor = readJson(path.join(data, 'runtime', 'supervisor.v1.json'));
    const manifest = readJson(path.join(data, 'runtime', 'manifest.json'));
    requireThat(supervisor?.runId === first.runId && supervisor?.pid === first.pid
      && supervisor?.instanceId === first.instanceId && supervisor?.incarnation === first.incarnation,
    'initialization retry must retain the same supervised Engine incarnation');
    requireThat(manifest?.pid === first.pid && manifest?.instanceId === first.instanceId,
      'initialization retry must retain the original manifest process');
    return supervisor;
  };
  const outcomeEndpoint = `/api/operation-history/${operationKey}`;
  const result = await waitFor('original operation completes and every document searches',
    Math.max(1, deadline - Date.now()), async () => {
      identity();
      const response = await request(apiPort, outcomeEndpoint);
      if (response.status !== 200) return null;
      const outcome = JSON.parse(response.text);
      requireThat(!['failed', 'unknown', 'expired'].includes(outcome.state), response.text);
      if (outcome.state !== 'complete') return null;
      requireThat(outcome.result?.code === 'SUCCESS' && outcome.unitsFailed === 0, response.text);
      const search = await post(apiPort, '/api/knowledge/search', {
        query: 'rootvolumeretrymarker', limit: paths.length, mode: 'text',
      });
      if (search.status !== 200) return null;
      const expected = new Set(paths.map(file => path.resolve(file).toLowerCase()));
      const matched = new Set((JSON.parse(search.text).results ?? [])
        .map(hit => String(hit.fields?.path ?? '').toLowerCase()).filter(file => expected.has(file)));
      return matched.size === expected.size ? { outcome, matchedCount: matched.size } : null;
    });
  requireThat(Date.now() <= deadline, 'same-Engine recovery must fit the existing 180-second bound');
  requireThat((await request(apiPort, '/api/health')).status === 200, 'recovered Engine must be healthy');
  console.log('ROOT_VOLUME_INITIALIZATION_RETRY_PASS', JSON.stringify({ operationKey,
    submitted: first, recovered: identity(), outcomeEndpoint, ...result, initialFailure, refused, locked }));
}
