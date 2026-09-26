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
  const { request, post, requireThat, requireOperationSuccess, createOperationKey,
    matchingHit, jobStateFor } = c;
  const pingKey = createOperationKey();
  const ping = await post(c.apiPort, '/api/operations/core.ping-backend/invoke', {
    args: {}, idempotencyKey: pingKey,
  });
  const pingReceipt = requireOperationSuccess(ping, 'installed ping dispatch');
  requireThat(pingReceipt.operationKey === pingKey,
    `installed ping changed its supplied operation key: ${ping.text}`);
  const operation = recordedPing(c.data, pingKey);
  console.log('OPERATION_ROW_BEFORE_DEATH', JSON.stringify(operation));
  const { file, marker, successor, afterDeath, ingestReceipt } = await exerciseProcessingReplay({ ...c,
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
  const pingRetry = await post(apiPort, '/api/operations/core.ping-backend/invoke', {
    args: {}, idempotencyKey: pingKey,
  });
  const pingRetryReceipt = requireOperationSuccess(pingRetry, 'installed ping retry');
  requireThat(pingRetryReceipt.operationKey === pingReceipt.operationKey
    && pingRetryReceipt.operationRecordId === pingReceipt.operationRecordId,
  `ping retry did not return the original receipt: ${pingRetry.text}`);
  requireThat(JSON.stringify(recordedPing(c.data, pingKey)) === JSON.stringify(operation),
    'ping retry altered the completed operation row');
  const retry = await post(apiPort, '/api/knowledge/ingest', {
    paths: [file], idempotencyKey: ingestReceipt.operationKey,
  });
  const retryReceipt = requireOperationSuccess(retry, 'ingest retry');
  requireThat(retryReceipt.operationKey === ingestReceipt.operationKey
    && retryReceipt.operationRecordId === ingestReceipt.operationRecordId,
  `ingest retry did not return the original receipt: ${retry.text}`);
  const retried = jobStateFor(path.basename(file));
  requireThat(JSON.stringify(retried) === JSON.stringify(before),
    `same-key retry created or changed queue work: before=${JSON.stringify(before)} after=${JSON.stringify(retried)}`);
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
    proof: 'recorded-operation-survival-and-same-key-retry', operation, afterDeath, before, retried,
    pingReceipt, pingRetryReceipt, ingestReceipt, retryReceipt,
    indexedDocuments: countBefore, matchingDocuments: hits.length,
  }));
}
