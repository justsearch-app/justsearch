/** @vitest-environment happy-dom */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentSessionController } from './AgentSessionController.js';
import * as hostStream from '../plugin-api/pumpHostAiStream.js';
import * as apiStream from '../../api/streams.js';
import { setAuthorizationPresenter } from '../operations/authorizationBroker.js';

type HeldStream = {
  onEvent: (event: string, payload: unknown) => void;
  signal?: AbortSignal;
  finish: () => void;
  fail: (error: Error) => void;
};
const pending = { callId: 'stale-call', toolName: 'core-test', arguments: '{}',
  risk: 'MEDIUM', gateBehavior: 'inline_confirm' };
const done = { finalResponse: 'done', iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 1 };
let controller: AgentSessionController;
let streams: HeldStream[];
let runs: Promise<void>[];

beforeEach(() => {
  streams = []; runs = [];
  controller = new AgentSessionController('http://test', () => {});
  const hold = (onEvent: HeldStream['onEvent'], signal?: AbortSignal): Promise<void> =>
    new Promise((resolve, reject) => { streams.push({ onEvent, signal, finish: resolve, fail: reject }); });
  vi.spyOn(hostStream, 'streamViaHost').mockImplementation(options => hold(options.onEvent, options.signal));
  vi.spyOn(apiStream, 'consumeShapeStream').mockImplementation((_url, _body, onEvent, signal) => hold(onEvent, signal));
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ callId: pending.callId,
    operationId: 'core.test', argsSummary: 'Live target', riskTier: 'MEDIUM', gateBehavior: 'INLINE_CONFIRM' }))));
  setAuthorizationPresenter(async () => ({ approved: false, allowAlways: false }));
});

afterEach(async () => {
  streams.forEach(stream => stream.finish());
  await Promise.allSettled(runs);
  controller.destroy();
  setAuthorizationPresenter(null);
  vi.restoreAllMocks(); vi.unstubAllGlobals();
});

describe('agent stream ownership', () => {
  it.each(['done', 'error'] as const)('ignores a new pending frame after %s', async terminal => {
    controller.isStreaming = true; controller.runKind = 'agent';
    controller.onSessionStarted({ sessionId: 'ended-run' });
    if (terminal === 'done') controller.onDone(done);
    else controller.onError({ error: 'ended' });
    controller.onToolCallPending(pending);
    await Promise.resolve();
    expect(globalThis.fetch).not.toHaveBeenCalled();
    expect(controller.toolCalls[pending.callId]).toBeUndefined();
  });

  it.each(['send', 'workflow', 'resume', 'fork', 'attach'] as const)(
    'ignores frames and cleanup from a replaced %s stream', async entry => {
      const first = entry === 'send' ? controller.send('old')
        : entry === 'workflow' ? controller.runWorkflow('core.fixture')
        : entry === 'resume' ? controller.resumeSession('old')
        : entry === 'fork' ? controller.forkRun('old', 'question') : controller.attachToRun('old');
      runs.push(first);
      const old = streams[0]!;
      old.onEvent('session_started', { sessionId: 'old-run' });
      runs.push(controller.send('replacement'));
      const current = streams[1]!;
      current.onEvent('session_started', { sessionId: 'new-run' });
      old.onEvent('tool_call_pending', pending);
      old.onEvent('done', { ...done, finalResponse: 'stale answer' });
      await Promise.resolve();
      expect(globalThis.fetch).not.toHaveBeenCalled();
      expect(controller.toolCalls[pending.callId]).toBeUndefined();
      expect(controller.sessionId).toBe('new-run');
      expect(controller.isStreaming).toBe(true);
      expect(controller.conversation.some(entry => entry.content === 'stale answer')).toBe(false);
      old.finish(); await first;
      expect(controller.isStreaming).toBe(true);
      expect(current.signal?.aborted).toBe(false);
      current.onEvent('chunk', { text: 'current answer' });
      expect(controller.streamingText).toBe('current answer');
      current.onEvent('done', done);
    },
  );

  it('keeps pending-before-identity AUTO delivery within the current stream', async () => {
    runs.push(controller.send('start'));
    const stream = streams[0]!;
    stream.onEvent('tool_call_pending', { ...pending, gateBehavior: 'auto' });
    expect(globalThis.fetch).not.toHaveBeenCalled();
    stream.onEvent('session_started', { sessionId: 'current-run' });
    await vi.waitFor(() => expect(globalThis.fetch).toHaveBeenCalledTimes(1));
    expect(JSON.parse(vi.mocked(globalThis.fetch).mock.calls[0]![1]!.body as string))
      .toEqual({ sessionId: 'current-run', callId: pending.callId });
    stream.onEvent('done', done);
  });

  it.each(['send', 'workflow', 'resume', 'fork', 'attach'] as const)(
    'does not let a replaced %s failure end or reattach the current run', async entry => {
      const first = entry === 'send' ? controller.send('old')
        : entry === 'workflow' ? controller.runWorkflow('core.fixture')
        : entry === 'resume' ? controller.resumeSession('old')
        : entry === 'fork' ? controller.forkRun('old', 'question') : controller.attachToRun('old');
      runs.push(first);
      streams[0]!.onEvent('session_started', { sessionId: 'old-run' });
      runs.push(controller.send('replacement'));
      streams[1]!.onEvent('session_started', { sessionId: 'new-run' });
      streams[0]!.fail(new Error('old transport failed'));
      await Promise.resolve(); await Promise.resolve();
      expect(streams).toHaveLength(2);
      expect(controller.isStreaming).toBe(true);
      expect(controller.sessionId).toBe('new-run');
      expect(controller.conversation.some(entry => entry.type === 'error')).toBe(false);
    },
  );

  it('keeps a live lookup across a same-run reattach without prompting twice', async () => {
    let resolveLookup!: (response: Response) => void;
    vi.mocked(globalThis.fetch).mockImplementationOnce(() => new Promise(resolve => { resolveLookup = resolve; }));
    const presenter = vi.fn(async () => ({ approved: false, allowAlways: false }));
    setAuthorizationPresenter(presenter);
    runs.push(controller.send('start'));
    streams[0]!.onEvent('session_started', { sessionId: 'same-run' });
    streams[0]!.onEvent('tool_call_pending', pending);
    runs.push(controller.attachToRun('same-run'));
    streams[1]!.onEvent('tool_call_pending', pending);
    resolveLookup(new Response(JSON.stringify({ callId: pending.callId, operationId: 'core.test',
      argsSummary: 'Live target', riskTier: 'MEDIUM', gateBehavior: 'INLINE_CONFIRM' })));
    await vi.waitFor(() => expect(presenter).toHaveBeenCalledTimes(1));
    expect(vi.mocked(globalThis.fetch).mock.calls.filter(call => String(call[0]).includes('/approval?'))).toHaveLength(1);
    expect(streams[0]!.signal?.aborted).toBe(true);
  });

  it('does not flush an unidentified old AUTO call into a replacement run', async () => {
    runs.push(controller.send('old'));
    streams[0]!.onEvent('tool_call_pending', { ...pending, gateBehavior: 'auto' });
    runs.push(controller.send('replacement'));
    streams[1]!.onEvent('session_started', { sessionId: 'new-run' });
    await Promise.resolve();
    expect(globalThis.fetch).not.toHaveBeenCalled();
  });

  it('finishes local cancellation before a delayed DELETE can clobber a new run', async () => {
    let finishDelete!: (response: Response) => void;
    const deletion = new Promise<Response>(resolve => { finishDelete = resolve; });
    vi.mocked(globalThis.fetch).mockImplementationOnce(() => deletion);
    runs.push(controller.send('old'));
    streams[0]!.onEvent('session_started', { sessionId: 'old-run' });
    const cancellation = controller.cancelSession(); runs.push(cancellation);
    runs.push(controller.send('replacement'));
    streams[1]!.onEvent('session_started', { sessionId: 'new-run' });
    finishDelete(new Response('{}'));
    await cancellation;
    expect(controller.isStreaming).toBe(true);
    expect(controller.sessionId).toBe('new-run');
    expect(streams[1]!.signal?.aborted).toBe(false);
  });

  it('ignores a buffered pending frame after destruction', async () => {
    runs.push(controller.send('old'));
    streams[0]!.onEvent('session_started', { sessionId: 'old-run' });
    controller.destroy();
    streams[0]!.onEvent('tool_call_pending', pending);
    controller.onToolCallPending(pending);
    await Promise.resolve();
    expect(globalThis.fetch).not.toHaveBeenCalled();
    expect(controller.toolCalls[pending.callId]).toBeUndefined();
  });

  it('still renders historical pending frames without requesting approval', () => {
    controller.loadReplayFromExport({ meta: { sessionId: 'history' }, events: [
      { eventType: 'session_started', payload: { sessionId: 'history' } },
      { eventType: 'tool_call_pending', payload: pending },
    ] });
    expect(controller.toolCalls[pending.callId]?.status).toBe('pending');
    expect(globalThis.fetch).not.toHaveBeenCalled();
  });
});
