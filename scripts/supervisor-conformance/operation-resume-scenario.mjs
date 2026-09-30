import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { exerciseProcessingReplay } from './processing-replay-scenario.mjs';

function recordedPing(data, key) {
  const database = new DatabaseSync(path.join(data, 'operations.db'), { readOnly: true });
  try {
    const rows = database.prepare(`SELECT id, operation_key, kind, state, operation_ref,
      identity_json, attempts, accepted_at, started_at, completed_at, result_json
      FROM operations WHERE operation_ref = 'core.ping-backend'
      ${key ? 'AND operation_key = ?' : ''}`).all(...(key ? [key] : []));
    if (rows.length !== 1 || rows[0].state !== 'COMPLETE' || rows[0].kind !== 'operation'
      || rows[0].attempts !== 1 || rows[0].started_at < rows[0].accepted_at
      || rows[0].completed_at < rows[0].started_at
      || JSON.parse(rows[0].result_json)?.code !== 'SUCCESS') {
      throw new Error(`installed dispatcher did not persist exactly one completed ping: ${JSON.stringify(rows)}`);
    }
    return rows[0];
  } finally {
    database.close();
  }
}

// Proves the installed dispatcher/store plus existing queue replay. The six keyed
// checkpoint/owner fault points still join this fixture with their owning items.
export async function exerciseOperationResume(c) {
  const { request, post, requireThat, acceptedCount, matchingHit, jobStateFor, waitFor } = c;
  const ping = await post(c.apiPort, '/api/operations/core.ping-backend/invoke', { args: {} });
  requireThat(ping.status === 200 && JSON.parse(ping.text).success === true,
    `installed ping dispatch failed: ${ping.text}`);
  const operation = recordedPing(c.data);
  console.log('OPERATION_ROW_BEFORE_DEATH', JSON.stringify(operation));
  const { file, marker, successor, afterDeath } = await exerciseProcessingReplay({ ...c,
    observeAfterDeath: () => {
      const row = recordedPing(c.data, operation.operation_key);
      requireThat(JSON.stringify(row) === JSON.stringify(operation),
        `recorded outcome changed after Engine death: ${JSON.stringify(row)}`);
      console.log('OPERATION_ROW_AFTER_DEATH', JSON.stringify(row));
    },
  });
  const recoveredOperation = recordedPing(c.data, operation.operation_key);
  requireThat(JSON.stringify(recoveredOperation) === JSON.stringify(operation),
    `successor altered a completed operation: ${JSON.stringify(recoveredOperation)}`);
  console.log('OPERATION_ROW_AFTER_RESTART', JSON.stringify(recoveredOperation));
  const apiPort = successor.manifest.head.apiPort;
  const before = jobStateFor(path.basename(file));
  const statusBefore = await request(apiPort, '/api/knowledge/status');
  requireThat(statusBefore.status === 200, `status before retry: ${statusBefore.text}`);
  const countBefore = JSON.parse(statusBefore.text).indexedDocuments;
  requireThat(Number.isInteger(countBefore) && countBefore === 1,
    `the eval-mode fixture must contain exactly one indexed document: ${statusBefore.text}`);
  const retry = await post(apiPort, '/api/knowledge/ingest', { paths: [file] });
  requireThat(retry.status === 200 && acceptedCount(retry) === 1,
    `retry must accept exactly one job: ${JSON.stringify(retry)}`);
  const retried = await waitFor('retry completes a new queue attempt', 60000, () => {
    const row = jobStateFor(path.basename(file));
    return row?.state === 'DONE' && row.last_updated > before.last_updated ? row : null;
  });
  const search = await post(apiPort, '/api/knowledge/search', { query: marker, limit: 10, mode: 'text' });
  requireThat(search.status === 200 && matchingHit(search, file, marker),
    `retried document must remain searchable: ${search.text}`);
  const hits = JSON.parse(search.text).results.filter(hit =>
    String(hit?.fields?.path ?? '').toLowerCase() === path.resolve(file).toLowerCase());
  requireThat(hits.length === 1, `retry must not duplicate the document: ${search.text}`);
  const statusAfter = await request(apiPort, '/api/knowledge/status');
  requireThat(statusAfter.status === 200
    && JSON.parse(statusAfter.text).indexedDocuments === countBefore,
  `indexed document count changed after retry: ${statusAfter.text}`);
  console.log('OPERATION_RETRY_NO_DUPLICATES_PASS', JSON.stringify({
    proof: 'recorded-operation-survival-and-processing-path-retry', operation, afterDeath, before, retried,
    indexedDocuments: countBefore, matchingDocuments: hits.length,
  }));
}
