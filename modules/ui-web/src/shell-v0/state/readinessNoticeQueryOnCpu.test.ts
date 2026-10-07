// SPDX-License-Identifier: Apache-2.0
/**
 * b6d536 contract X2 — searches the GPU turned away are answered on the CPU, and the shell says so
 * calmly. `encoders.health.query_on_cpu` (READY embedding, informational) is worded at `info`; beside
 * it, a stalled GPU (`encoders.health.semantic_stuck`) holds up background embedding only, so it no
 * longer licenses the keyword-fallback claim, yet stays listed as a cause.
 */
import { describe, it, expect } from 'vitest';
import { classifyConsequence, readinessNotice, reasonFor, severityForCodes } from './readinessNotice.js';
import type { SystemHealthVerdict } from './verdict.js';

const degraded = (
  severity: SystemHealthVerdict['severity'],
  reasons: string[],
): SystemHealthVerdict => ({ kind: 'degraded', severity, reasons });

describe('readinessNotice — searches answered on the CPU (b6d536 X2)', () => {
  it('words query_on_cpu calmly, and a stall beside it stays listed without a keyword claim', () => {
    const onCpu = 'encoders.health.query_on_cpu';
    const stuck = 'encoders.health.semantic_stuck';
    expect(reasonFor(onCpu).wording).toBe('Searches are running on the CPU while the GPU is busy');
    expect(severityForCodes([onCpu])).toBe('info');
    // The stalled GPU holds up background embedding only: no keyword-fallback claim, info severity.
    expect(classifyConsequence([stuck, onCpu])).not.toBe('retrieval-impaired');
    expect(severityForCodes([stuck, onCpu])).toBe('info');
    const notice = readinessNotice(degraded('info', [stuck, onCpu]));
    expect(notice?.headline).toBe('Reduced search capability.');
    expect(notice?.body).not.toContain('keyword');
    expect(notice?.causes).toEqual([
      'The semantic search encoder is stalled',
      'Searches are running on the CPU while the GPU is busy',
    ]);
    // Beside a genuine retrieval cause, the impairing notice names that cause, not the stall.
    const impaired = readinessNotice(degraded('warn', ['index.dense_unavailable', stuck, onCpu]));
    expect(impaired?.headline).toBe('Semantic search degraded.');
    expect(impaired?.causes).not.toContain('The semantic search encoder is stalled');
  });
});
