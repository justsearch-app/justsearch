/**
 * Spec A step 9 — the AI state names a chat model unloaded for another GPU user (from the AI
 * readiness reason), and the refusal body and "Load anyway" request helpers read and send the
 * Engine's wire shapes.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { chatUnloadedReason, parseChatRefusal, requestLoadAnyway } from './chatResidency.js';
import { __feedForTest, __resetAiStateForTest, getAiState } from './aiStateStore.js';

function statusWithAiReasons(reasonCodes: string[], chatEnabledSpec = true): never {
  return {
    inference: { chatEnabledSpec },
    readiness: {
      composites: {
        retrieval: { state: 'READY', reasonCodes: [] },
        aiFeatures: { state: 'DEGRADED', reasonCodes },
      },
    },
  } as never;
}

describe('chatResidency — Spec A step 9', () => {
  afterEach(() => {
    __resetAiStateForTest();
  });

  it('the AI state names a GPU-pressure or game unload, and nothing else', () => {
    __feedForTest({ status: statusWithAiReasons(['inference.unloaded_gpu_pressure']) });
    expect(getAiState().chatUnloadedReason).toBe('inference.unloaded_gpu_pressure');
    __feedForTest({ status: statusWithAiReasons(['inference.unloaded_game_mode']) });
    expect(getAiState().chatUnloadedReason).toBe('inference.unloaded_game_mode');
    __feedForTest({ status: statusWithAiReasons(['inference.gpu_yielded_to_indexing']) });
    expect(getAiState().chatUnloadedReason).toBeNull();
  });

  it('a chat the user switched off is off, not unloaded', () => {
    expect(chatUnloadedReason(['inference.unloaded_gpu_pressure'], false)).toBeNull();
    expect(chatUnloadedReason(['inference.unloaded_gpu_pressure'], undefined)).toBe(
      'inference.unloaded_gpu_pressure',
    );
  });

  it('reads the refusal body and ignores other bodies', () => {
    expect(
      parseChatRefusal(
        JSON.stringify({
          error: 'Chat is paused while a game is running.',
          errorCode: 'CHAT_UNAVAILABLE',
          reason: 'GAME_MODE',
          forceAllowed: true,
          retryAfterMs: 30000,
        }),
      ),
    ).toEqual({
      errorCode: 'CHAT_UNAVAILABLE',
      reason: 'GAME_MODE',
      message: 'Chat is paused while a game is running.',
      forceAllowed: true,
      retryAfterMs: 30000,
    });
    expect(parseChatRefusal(JSON.stringify({ error: 'x', errorCode: 'INTERNAL_ERROR' }))).toBeNull();
    expect(parseChatRefusal('not json')).toBeNull();
  });

  it('Load anyway posts allowLoad to the route and reads loading or the refusal', async () => {
    const fetchImpl = vi.fn().mockResolvedValueOnce(
      new Response(JSON.stringify({ loading: true }), { status: 202 }),
    );
    expect(await requestLoadAnyway('http://h', fetchImpl)).toEqual({ ok: true, loading: true });
    expect(fetchImpl).toHaveBeenCalledWith('http://h/api/chat/load-anyway', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ allowLoad: true }),
    });

    fetchImpl.mockResolvedValueOnce(
      new Response(
        JSON.stringify({ error: 'Chat may not load for this request.', errorCode: 'AI_OFFLINE',
          reason: 'REFUSED', forceAllowed: false }),
        { status: 403 },
      ),
    );
    const refused = await requestLoadAnyway('http://h', fetchImpl);
    expect(refused.ok).toBe(false);
    expect(refused.ok === false && refused.refusal?.reason).toBe('REFUSED');
  });
});
