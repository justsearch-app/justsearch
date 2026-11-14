// SPDX-License-Identifier: Apache-2.0
import type { SearchTrace } from '../../../api/generated/index.js';

/** Execution evidence, not the requested/effective mode: TEXT can include SPLADE. */
export function searchExecution(trace: unknown): 'words' | 'meaning' | 'unknown' {
  const raw = (trace as SearchTrace | null)?.stages;
  const stages = Array.isArray(raw) ? raw : [];
  const status = (id: string) => stages.find(stage => stage?.id === id)?.status;
  const learned = [status('dense-retrieval'), status('splade-retrieval')];
  if (learned.includes('executed')) return 'meaning';
  if (status('sparse-retrieval') === 'executed' && learned.every(value => value === 'skipped' || value === 'disabled')) return 'words';
  return 'unknown';
}
