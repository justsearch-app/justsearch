import { fileURLToPath } from 'node:url';
import path from 'node:path';

export function ingestAccepted(response) {
  if (!response || typeof response !== 'object') return false;
  // An operation envelope never falls back to legacy fields, including on refusal.
  if (Object.hasOwn(response, 'success') || Object.hasOwn(response, 'structuredData')) {
    return response.success === true && Boolean(response.structuredData?.operationKey);
  }
  return Number.isInteger(response.accepted) && response.accepted > 0
    && response.error === '' && !response.errorCode
    && typeof response.scanId === 'string' && response.scanId.trim().length > 0;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  let raw = '';
  for await (const chunk of process.stdin) raw += chunk;
  const response = JSON.parse(raw);
  if (!ingestAccepted(response)) {
    console.error('Ingest operation refused:', response);
    process.exitCode = 2;
  }
}
