/**
 * IntentRouter tests — resolution-first architecture (tempdoc 499).
 *
 * The router resolves every intent against the catalog before dispatch.
 * Unresolved intents never reach handlers.
 */

import { describe, expect, it, vi, beforeEach } from 'vitest';
import { createIntentRouter } from './intentRouter.js';

import type { NavigationHandler } from './navigationHandler.js';
import type { InvocationHandler } from './invocationHandler.js';
import type { Intent } from './types.js';
import type { ResolutionResult } from './resolution.js';

const resolveAlways = (rawId: string): ResolutionResult => ({ status: 'resolved', id: rawId });
const resolveNever = (rawId: string): ResolutionResult => ({
  status: 'unresolved',
  attemptedId: rawId,
  diagnosis: { mode: 'unknown', detail: 'not found' },
  alternatives: [],
});

function makeNavHandler(impl?: NavigationHandler['handle']): {
  handler: NavigationHandler;
  spy: ReturnType<typeof vi.fn>;
} {
  const spy = vi.fn(impl ?? (async () => undefined));
  return {
    spy,
    handler: { handle: spy as unknown as NavigationHandler['handle'] },
  };
}

function makeInvHandler(impl?: InvocationHandler['handle']): {
  handler: InvocationHandler;
  spy: ReturnType<typeof vi.fn>;
} {
  const spy = vi.fn(impl ?? (async () => ({ success: true })));
  return {
    spy,
    handler: { handle: spy as unknown as InvocationHandler['handle'] },
  };
}

function makeRouter(overrides?: Partial<Parameters<typeof createIntentRouter>[0]>) {
  return createIntentRouter({
    navigationHandler: makeNavHandler().handler,
    invocationHandler: makeInvHandler().handler,
    isKnownSurface: () => true,
    resolveSurface: resolveAlways,
    resolveOperation: resolveAlways,
    ...overrides,
  });
}

describe('IntentRouter — Navigation intents', () => {
  it('delegates resolved Navigation to the NavigationHandler', async () => {
    const { handler: navHandler, spy: navSpy } = makeNavHandler();
    const { handler: invHandler, spy: invSpy } = makeInvHandler();
    const router = createIntentRouter({
      navigationHandler: navHandler,
      invocationHandler: invHandler,
      isKnownSurface: () => true,
      resolveSurface: resolveAlways,
      resolveOperation: resolveAlways,
    });

    const address = {
      kind: 'navigate' as const,
      target: 'core.library-surface',
      state: { folder: 'docs' },
    };
    await router.dispatch({ address, transport: 'RAIL' });

    expect(navSpy).toHaveBeenCalledWith(address, { push: undefined });
    expect(invSpy).not.toHaveBeenCalled();
  });

  it('forwards pushHistory option as the handler push flag', async () => {
    const { handler: navHandler, spy: navSpy } = makeNavHandler();
    const router = createIntentRouter({
      navigationHandler: navHandler,
      invocationHandler: makeInvHandler().handler,
      isKnownSurface: () => true,
      resolveSurface: resolveAlways,
      resolveOperation: resolveAlways,
    });

    await router.dispatch(
      {
        address: { kind: 'navigate', target: 'core.library-surface', state: {} },
        transport: 'URL_BAR',
      },
      { pushHistory: false },
    );

    expect(navSpy).toHaveBeenCalledWith(
      { kind: 'navigate', target: 'core.library-surface', state: {} },
      { push: false },
    );
  });

  it('state-bearing Navigation passes state through to handler', async () => {
    const { handler: navHandler, spy: navSpy } = makeNavHandler();
    const router = makeRouter({ navigationHandler: navHandler });

    await router.dispatch({
      address: {
        kind: 'navigate',
        target: 'core.search-surface',
        state: { query: 'hello' },
      },
      transport: 'LLM_EMISSION',
    });

    expect(navSpy).toHaveBeenCalledTimes(1);
    expect(navSpy.mock.calls[0]![0]).toEqual({
      kind: 'navigate',
      target: 'core.search-surface',
      state: { query: 'hello' },
    });
  });

  it('unresolved Navigation does NOT call handler', async () => {
    const { handler: navHandler, spy: navSpy } = makeNavHandler();
    const router = makeRouter({
      navigationHandler: navHandler,
      resolveSurface: resolveNever,
    });

    await router.dispatch({
      address: { kind: 'navigate', target: 'core.ghost', state: {} },
      transport: 'URL_BAR',
    });

    expect(navSpy).not.toHaveBeenCalled();
  });

  it('redirected Navigation dispatches with canonical target', async () => {
    const { handler: navHandler, spy: navSpy } = makeNavHandler();
    const redirect = (): ResolutionResult => ({
      status: 'redirected',
      id: 'core.library-surface',
      originalId: 'core.library',
      reason: 'renamed',
    });
    const router = makeRouter({
      navigationHandler: navHandler,
      resolveSurface: redirect,
    });

    await router.dispatch({
      address: { kind: 'navigate', target: 'core.library', state: {} },
      transport: 'RAIL',
    });

    expect(navSpy).toHaveBeenCalledTimes(1);
    expect(navSpy.mock.calls[0]![0].target).toBe('core.library-surface');
  });
});

describe('IntentRouter — Invocation intents', () => {
  it('delegates resolved Invocation to InvocationHandler with transport', async () => {
    const { handler: invHandler, spy: invSpy } = makeInvHandler();
    const router = makeRouter({ invocationHandler: invHandler });

    const address = {
      kind: 'invoke' as const,
      target: 'core.add-watched-root',
      args: { path: '/docs' },
    };
    await router.dispatch({ address, transport: 'BUTTON' });

    expect(invSpy).toHaveBeenCalledWith(address, 'BUTTON');
  });

  it('returns the Invocation result through the dispatch promise', async () => {
    const successPayload = { success: true, message: 'done', executionId: 'exec-1' };
    const invHandler: InvocationHandler = {
      handle: vi.fn().mockResolvedValue(successPayload) as never,
    };
    const router = makeRouter({ invocationHandler: invHandler });

    const result = await router.dispatch({
      address: { kind: 'invoke', target: 'core.ping-backend', args: {} },
      transport: 'BUTTON',
    });

    expect(result).toEqual(successPayload);
  });

  it('dispatcher errors propagate to the caller', async () => {
    const invHandler: InvocationHandler = {
      handle: vi.fn().mockRejectedValue(new Error('boom')) as never,
    };
    const router = makeRouter({ invocationHandler: invHandler });

    await expect(
      router.dispatch({
        address: { kind: 'invoke', target: 'core.x', args: {} },
        transport: 'BUTTON',
      }),
    ).rejects.toThrow('boom');
  });

  it('unresolved Invocation does NOT call handler', async () => {
    const { handler: invHandler, spy: invSpy } = makeInvHandler();
    const router = makeRouter({
      invocationHandler: invHandler,
      resolveOperation: resolveNever,
    });

    const result = await router.dispatch({
      address: { kind: 'invoke', target: 'core.unknown-op', args: {} },
      transport: 'BUTTON',
    });

    expect(invSpy).not.toHaveBeenCalled();
    expect(result).toBeUndefined();
  });
});

describe('IntentRouter — Query intents (548 S4-A)', () => {
  it('lowers a query to a search-surface navigation carrying the query in state', async () => {
    const { handler: navHandler, spy: navSpy } = makeNavHandler();
    const router = makeRouter({ navigationHandler: navHandler });

    await router.dispatch({
      address: { kind: 'query', query: 'rust ownership', state: {} },
      transport: 'LLM_EMISSION',
    });

    expect(navSpy).toHaveBeenCalledTimes(1);
    // Search Thread S5b — the standalone Search rail surface is retired; the default query surface
    // is now the one window (core.search-v3-surface), which owns the retrieve tier.
    expect(navSpy.mock.calls[0]![0]).toEqual({
      kind: 'navigate',
      target: 'core.search-v3-surface',
      state: { query: 'rust ownership' },
    });
  });

  it('merges refinement state and honors custom querySurfaceId / queryStateKey', async () => {
    const { handler: navHandler, spy: navSpy } = makeNavHandler();
    const router = makeRouter({
      navigationHandler: navHandler,
      querySurfaceId: 'core.custom-search',
      queryStateKey: 'q',
    });

    await router.dispatch({
      address: { kind: 'query', query: 'lucene', state: { lang: 'en' } },
      transport: 'LLM_EMISSION',
    });

    expect(navSpy.mock.calls[0]![0]).toEqual({
      kind: 'navigate',
      target: 'core.custom-search',
      state: { lang: 'en', q: 'lucene' },
    });
  });

  it('does NOT call the handler when the query surface is unresolved', async () => {
    const { handler: navHandler, spy: navSpy } = makeNavHandler();
    const router = makeRouter({
      navigationHandler: navHandler,
      resolveSurface: resolveNever,
    });

    await router.dispatch({
      address: { kind: 'query', query: 'rust', state: {} },
      transport: 'LLM_EMISSION',
    });

    expect(navSpy).not.toHaveBeenCalled();
  });

  it("fires { status: 'dispatched' } for a resolved query", async () => {
    const seen: unknown[] = [];
    const router = makeRouter();
    router.subscribe((_, outcome) => seen.push(outcome));
    await router.dispatch({
      address: { kind: 'query', query: 'rust', state: {} },
      transport: 'LLM_EMISSION',
    });
    expect(seen).toEqual([{ status: 'dispatched' }]);
  });
});

describe('IntentRouter — Answer intents (548 §4.5)', () => {
  beforeEach(() => {
    // Drain the compose one-shots so each assertion sees only this test's writes.
  });

  it('parks the answer shape without an automatic send', async () => {
    const { handler: navHandler, spy: navSpy } = makeNavHandler();
    const router = makeRouter({ navigationHandler: navHandler });

    await router.dispatch({
      address: { kind: 'answer', prompt: 'what is rust', shape: 'core.summarize', state: {} },
      transport: 'LLM_EMISSION',
    });

    // Shape, prompt and scope travel in navigation state, without any dispatch.
    expect(navSpy).toHaveBeenCalledExactlyOnceWith({ kind: 'navigate', target: 'core.search-v3-surface',
      state: { query: 'what is rust', forceShapeId: 'core.summarize' } }, expect.anything());
  });

  it('does NOT park the auto-run flag when the chat surface is unresolved', async () => {
    const { handler: navHandler, spy: navSpy } = makeNavHandler();
    const router = makeRouter({ navigationHandler: navHandler, resolveSurface: resolveNever });
    await router.dispatch({
      address: { kind: 'answer', prompt: 'x', shape: 'core.rag-ask', state: {} },
      transport: 'LLM_EMISSION',
    });
    // No navigation fired, and crucially no stale one-shot is left parked to
    // hijack the next genuine chat activation.
    expect(navSpy).not.toHaveBeenCalled();
  });

  it('lowers an answer to a chat-surface activation carrying the prompt in state', async () => {
    const { handler: navHandler, spy: navSpy } = makeNavHandler();
    const router = makeRouter({ navigationHandler: navHandler });

    await router.dispatch({
      address: { kind: 'answer', prompt: 'what is rust', shape: 'core.rag-ask', state: {} },
      transport: 'LLM_EMISSION',
    });

    expect(navSpy).toHaveBeenCalledTimes(1);
    expect(navSpy.mock.calls[0]![0]).toEqual({
      kind: 'navigate',
      target: 'core.search-v3-surface',
      state: { query: 'what is rust', forceShapeId: 'core.rag-ask' },
    });
  });

  it('honors custom answerSurfaceId / answerStateKey and merges state', async () => {
    const { handler: navHandler, spy: navSpy } = makeNavHandler();
    const router = makeRouter({
      navigationHandler: navHandler,
      answerSurfaceId: 'core.ask-surface',
      answerStateKey: 'prompt',
    });

    await router.dispatch({
      address: { kind: 'answer', prompt: 'sum it', shape: 'core.summarize', state: { lang: 'en' } },
      transport: 'LLM_EMISSION',
    });

    expect(navSpy.mock.calls[0]![0]).toEqual({
      kind: 'navigate',
      target: 'core.ask-surface',
      state: { lang: 'en', prompt: 'sum it' },
    });
  });

  it('C2 C6 A2 keeps answer file scope while the named shape outranks conflicting state', async () => {
    const { handler: navHandler, spy: navSpy } = makeNavHandler();
    const router = makeRouter({ navigationHandler: navHandler, answerSurfaceId: 'core.search-v3-surface' });
    await router.dispatch({ transport: 'URL_DEEPLINK', address: { kind: 'answer', prompt: 'Extract chosen',
      shape: 'core.extract', state: { docIds: ['chosen.md'], forceShapeId: 'core.agent-run', query: 'stale' } } });
    expect(navSpy.mock.calls[0]![0]).toEqual({ kind: 'navigate', target: 'core.search-v3-surface',
      state: { docIds: ['chosen.md'], forceShapeId: 'core.extract', query: 'Extract chosen' } });
  });

  it('does NOT call the handler when the chat surface is unresolved', async () => {
    const { handler: navHandler, spy: navSpy } = makeNavHandler();
    const router = makeRouter({ navigationHandler: navHandler, resolveSurface: resolveNever });
    await router.dispatch({
      address: { kind: 'answer', prompt: 'x', shape: 'core.rag-ask', state: {} },
      transport: 'LLM_EMISSION',
    });
    expect(navSpy).not.toHaveBeenCalled();
  });
});

describe('IntentRouter — observability', () => {
  it("listeners receive { status: 'dispatched' } for resolved surfaces", async () => {
    const seen: Array<{ intent: Intent; outcome: unknown }> = [];
    const router = makeRouter();
    router.subscribe((intent, outcome) => seen.push({ intent, outcome }));

    const intent: Intent = {
      address: { kind: 'navigate', target: 'core.library-surface', state: {} },
      transport: 'RAIL',
    };
    await router.dispatch(intent);
    expect(seen).toEqual([{ intent, outcome: { status: 'dispatched' } }]);
  });

  it("listeners receive { status: 'unresolved' } for unknown surfaces", async () => {
    const seen: Array<{ outcome: unknown }> = [];
    const router = makeRouter({ resolveSurface: resolveNever });
    router.subscribe((_, outcome) => seen.push({ outcome }));
    await router.dispatch({
      address: { kind: 'navigate', target: 'core.ghost', state: {} },
      transport: 'URL_BAR',
    });
    expect(seen[0]!.outcome).toEqual(expect.objectContaining({ status: 'unresolved', attemptedId: 'core.ghost' }));
  });

  it('subscribe fires for both Navigation and Invocation intents', async () => {
    const seen: Intent[] = [];
    const router = makeRouter();
    const unsub = router.subscribe((i) => seen.push(i));

    const navIntent: Intent = {
      address: { kind: 'navigate', target: 'core.library-surface', state: {} },
      transport: 'RAIL',
    };
    const invIntent: Intent = {
      address: { kind: 'invoke', target: 'core.ping-backend', args: {} },
      transport: 'PALETTE',
    };

    await router.dispatch(navIntent);
    await router.dispatch(invIntent);
    expect(seen).toEqual([navIntent, invIntent]);

    unsub();
    await router.dispatch(navIntent);
    expect(seen).toHaveLength(2);
  });

  it('listener errors do not block dispatch', async () => {
    const { handler: navHandler, spy: navSpy } = makeNavHandler();
    const router = makeRouter({ navigationHandler: navHandler });
    router.subscribe(() => {
      throw new Error('listener boom');
    });

    await expect(
      router.dispatch({
        address: { kind: 'navigate', target: 'core.library-surface', state: {} },
        transport: 'RAIL',
      }),
    ).resolves.toBeUndefined();
    expect(navSpy).toHaveBeenCalled();
  });
});
