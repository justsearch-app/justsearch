import path from 'node:path';
import { exerciseProcessingReplay } from './processing-replay-scenario.mjs';

// Batch one proves the existing durable queue's replay and path-idempotent effect.
// Operation-key and checkpoint fault points join this fixture with their owning items.
export async function exerciseOperationResume(c) {
  const { request, post, requireThat, acceptedCount, matchingHit, jobStateFor, waitFor } = c;
  const { file, marker, successor, afterDeath } = await exerciseProcessingReplay(c);
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
    proof: 'day-one-processing-replay-and-path-retry', afterDeath, before, retried,
    indexedDocuments: countBefore, matchingDocuments: hits.length,
  }));
}
