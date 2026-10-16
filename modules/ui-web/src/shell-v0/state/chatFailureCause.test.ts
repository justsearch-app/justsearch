// @vitest-environment happy-dom

/**
 * Spec A step 7 (F1) — when chat failed, the AI state names the classified cause and the
 * affordance controls ("What you can do right now") say it in the readiness notice's words instead
 * of a bare "offline".
 */
import { describe, it, expect, afterEach } from 'vitest';
import { __feedForTest, __resetAiStateForTest, getAiState } from './aiStateStore.js';
import { projectAvailability } from './availability.js';

function offlineStatusWithAiReasons(reasonCodes: string[], chatEnabledSpec = true): never {
  return {
    inference: { chatEnabledSpec },
    readiness: {
      composites: {
        retrieval: { state: 'READY', reasonCodes: [] },
        aiFeatures: { state: 'NOT_READY', reasonCodes },
      },
    },
  } as never;
}

function feed(reasonCodes: string[], chatEnabledSpec = true): void {
  __feedForTest({
    status: offlineStatusWithAiReasons(reasonCodes, chatEnabledSpec),
    inference: { mode: 'offline', starting: false, available: false } as never,
  });
}

function controlReason(): string {
  const a = projectAvailability('agent', getAiState());
  expect(a.kind).toBe('unavailable');
  return a.kind === 'unavailable' ? a.reason : '';
}

describe('chat failure cause — Spec A step 7 (F1)', () => {
  afterEach(() => {
    __resetAiStateForTest();
  });

  it('the AI state names the classified failure, and nothing else', () => {
    feed(['inference.model_load_failed']);
    expect(getAiState().chatFailureReason).toBe('inference.model_load_failed');
    feed(['inference.out_of_memory']);
    expect(getAiState().chatFailureReason).toBe('inference.out_of_memory');
    feed(['inference.offline']);
    expect(getAiState().chatFailureReason).toBeNull();
    feed(['inference.model_load_failed'], false);
    expect(getAiState().chatFailureReason, 'a chat the user switched off is off').toBeNull();
  });

  it.each([
    ['inference.out_of_memory', 'The local AI model ran out of graphics memory'],
    ['inference.startup_timeout', 'The local AI model took too long to start'],
    ['inference.start_failed', 'The local AI model failed to start'],
    ['inference.model_load_failed', 'The local AI model file could not be loaded'],
    ['inference.crashed', 'The local AI model stopped unexpectedly'],
  ])('a control unavailable because chat failed with %s says why', (code, words) => {
    feed([code]);
    expect(controlReason()).toBe(words);
  });

  it('a chat model that is merely offline still says offline', () => {
    feed(['inference.offline']);
    expect(controlReason()).toBe('The local AI model is offline');
  });
});
