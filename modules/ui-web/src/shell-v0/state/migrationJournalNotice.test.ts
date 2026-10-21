import { expect, it } from 'vitest';
import { classifyConsequence, readinessNotice, severityForCodes } from './readinessNotice.js';

it('journal refusal names the lost migration journal and offers Health while search is unavailable', () => {
  const reasons = ['index.migration_journal_unavailable'];
  expect(classifyConsequence(reasons)).toBe('search-unavailable');
  expect(severityForCodes(reasons)).toBe('error');
  const notice = readinessNotice({ kind: 'degraded', severity: 'error', reasons });
  expect(notice?.headline).toBe('Search index unavailable.');
  expect(notice?.causes.join(' ')).toContain('job journal');
  expect(notice?.causes.join(' ')).toContain('kept for repair');
  expect(notice?.remedy).toEqual({ kind: 'navigate', target: 'core.health-surface', label: 'Open Health' });
});
