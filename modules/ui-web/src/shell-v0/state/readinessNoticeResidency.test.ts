/**
 * Spec A step 9 — a chat model the Engine unloaded for another GPU user is worded as a calm notice
 * that says it reloads on request, never as an outage and never in the search degradation banner.
 */
import { describe, it, expect } from 'vitest';
import {
  classifyConsequence,
  reasonFor,
  severityForCodes,
  warrantsSearchDegradationBanner,
} from './readinessNotice.js';
import type { SystemHealthVerdict } from './verdict.js';

const degraded = (
  severity: SystemHealthVerdict['severity'],
  reasons: string[],
): SystemHealthVerdict => ({ kind: 'degraded', severity, reasons });

describe('readinessNotice — Spec A step 9 residency unloads', () => {
  it('words a GPU-pressure unload as paused and reloading on request, at info', () => {
    const code = 'inference.unloaded_gpu_pressure';
    expect(reasonFor(code).wording).toBe(
      'Chat was paused to free graphics memory. It reloads when you ask.',
    );
    expect(severityForCodes([code])).toBe('info');
    expect(classifyConsequence([code])).not.toBe('ai-unavailable');
    expect(warrantsSearchDegradationBanner(degraded('info', [code]))).toBe(false);
  });

  it('words a game-mode unload as paused while a game runs, at info', () => {
    const code = 'inference.unloaded_game_mode';
    expect(reasonFor(code).wording).toBe('Chat is paused while a game is running.');
    expect(severityForCodes([code])).toBe('info');
    expect(classifyConsequence([code])).not.toBe('ai-unavailable');
    expect(warrantsSearchDegradationBanner(degraded('info', [code]))).toBe(false);
  });
});
