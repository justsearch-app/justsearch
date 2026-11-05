// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// L13 — the agent in Search asks; the person denies in the status panel and the run's gate cannot take it.
import { afterEach, expect, it, vi } from 'vitest';
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }));
vi.mock('../components/advisory/ephemeralToast.js', () => ({ emitEphemeralToast: vi.fn() }));
import { AgentSessionController } from './AgentSessionController.js';
import '../components/AuthorizationHost.js';
import type { AuthorizationHost } from '../components/AuthorizationHost.js';
import { ARMING_DELAY_MS } from '../operations/authorizationBroker.js';

const detail = { callId: 'call-1', operationId: 'core.reindex', argsSummary: '/home/sam/Notes', riskTier: 'MEDIUM', gateBehavior: 'INLINE_CONFIRM' };
let host: AuthorizationHost | undefined;
let ctrl: AgentSessionController | undefined;
afterEach(() => { ctrl?.destroy(); host?.remove(); vi.useRealTimers(); vi.unstubAllGlobals(); });

it('L13 a Deny the run could not take keeps the request in Needs you, answerable, saying it could not be cancelled', async () => {
  vi.useFakeTimers({ toFake: ['performance'] });
  host = document.createElement('jf-authorization-host') as AuthorizationHost;
  document.body.append(host);
  let rejectStatus = 503;
  const rejects: unknown[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (String(url).includes('/api/chat/approval?')) return new Response(JSON.stringify(detail), { status: 200 });
    if (String(url).endsWith('/api/chat/reject')) {
      rejects.push(JSON.parse(String(init?.body)));
      return new Response('{}', { status: rejectStatus });
    }
    return new Response('{}', { status: 200 });
  }));
  ctrl = new AgentSessionController('http://test', () => {});
  ctrl.isStreaming = true; ctrl.runKind = 'agent'; ctrl.sessionId = 'run-1';
  ctrl.onToolCallPending({ callId: 'call-1', toolName: 'reindex', arguments: '{}', risk: 'MEDIUM', gateBehavior: 'inline_confirm' });
  await vi.waitFor(() => expect(host!.statusRequestState('call-1')).not.toBeNull());
  const waiting = () => host!.statusRequests().some(item => item.kind === 'authorize' && item.prompt.pendingId === 'call-1');

  vi.advanceTimersByTime(ARMING_DELAY_MS);
  await host.answerStatusRequest('call-1', false);
  expect(rejects).toEqual([{ sessionId: 'run-1', callId: 'call-1', reason: 'Denied in authorization ceremony' }]);
  // The run still waits for an answer, so the request stays in Needs you and says why.
  expect(waiting(), 'still in Needs you').toBe(true);
  expect(host.statusRequestState('call-1')?.error).toMatch(/could not be cancelled/i);
  expect(ctrl.toolCalls['call-1']?.status).toBe('pending');

  // It is still answerable: the next Deny goes through and only then leaves Needs you.
  rejectStatus = 200;
  await host.answerStatusRequest('call-1', false);
  expect(rejects).toHaveLength(2);
  expect(waiting()).toBe(false);
  expect(host.statusRequestState('call-1')).toBeNull();
});
