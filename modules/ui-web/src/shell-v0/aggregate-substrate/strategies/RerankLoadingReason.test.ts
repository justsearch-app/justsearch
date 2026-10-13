import { expect, it } from 'vitest';
import { searchTraceSchema } from '../../../api/generated/schema-types/search-trace.js';
import { userSummaryParts } from './searchTraceExplain.js';

it('a loading reranker is explained without a raw reason code', () => {
  const trace = searchTraceSchema.parse({ version: 1, effectiveMode: 'HYBRID',
    stages: [{ id: 'cross-encoder', status: 'skipped', reason: 'MODEL_LOADING' }] });
  const text = userSummaryParts(trace).join(' · ');
  expect(text).toContain('Relevance re-ranking model is still loading');
  expect(text).not.toContain('MODEL_LOADING');
});
