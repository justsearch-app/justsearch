// SPDX-License-Identifier: Apache-2.0
/**
 * VirtualToolDispatcher — Tempdoc 508 §11.5 / §13.5 Phase B —
 * FE listener that handles agent-emitted `tool_call_virtual` SSE
 * events.
 *
 * Wire flow:
 *   1. LLM emits a `vop_*`-prefixed tool call.
 *   2. AgentLoopService intercepts, emits `tool_call_virtual` SSE
 *      event, blocks on a CompletableFuture keyed by callId.
 *   3. This dispatcher receives the event via the chat SSE channel,
 *      resolves the wireName to a source command id via
 *      VirtualOperationCatalog.resolveAgentToolCall, invokes the
 *      command via invokeCommandWithResult, and POSTs the captured
 *      result to /api/chat/agent/tool-result.
 *   4. Backend completes the future; agent loop resumes with the
 *      result appended to the conversation.
 *
 * Stale tools (registry doesn't know the wireName): POST a failure
 * result immediately so the agent doesn't hang on the 30s timeout.
 */

import { authorizedFetch } from '../api/authorizedFetch.js';
import { resolveAgentToolCall } from './VirtualOperationCatalog.js';
import { invokeCommandWithResult } from './CommandRegistry.js';

export interface VirtualToolCallEvent {
  readonly sessionId: string;
  readonly callId: string;
  readonly wireName: string;
  readonly arguments: string;
}

export interface DispatcherDeps {
  readonly apiBase: string;
  /** Optional fetch override for tests. */
  readonly fetchImpl?: typeof fetch;
}

/**
 * Handle one `tool_call_virtual` event. Returns the InvocationResult
 * (the same shape POST'd to the backend) — exposed so tests can
 * assert the dispatcher's decision tree.
 */
export async function dispatchVirtualToolCall(
  event: VirtualToolCallEvent,
  deps: DispatcherDeps,
): Promise<{ ok: boolean; output?: string; error?: string }> {
  const f = deps.fetchImpl ?? authorizedFetch;
  const commandId = resolveAgentToolCall(event.wireName);
  let result: { ok: boolean; output?: string; error?: string };
  if (commandId === null) {
    result = {
      ok: false,
      error: `Virtual tool ${event.wireName} is not registered. The publishing FE session may have changed since the agent's tool list was projected.`,
    };
  } else {
    // §28.W13 — agent-originated invocation. invokeCommandWithResult
    // threads originator='agent' through invokeAndApply → applyEffect
    // → recordEffect so the resulting Journal entries attribute to
    // the AI agent. Activity's "Undo last AI action" reaches these; its
    // feed shows them under Agents.
    result = await invokeCommandWithResult(commandId, { originator: 'agent' });
  }
  // POST result back to backend regardless of outcome — the agent
  // loop is blocked waiting; never let it time out silently.
  try {
    await f(`${deps.apiBase}/api/chat/agent/tool-result`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionId: event.sessionId,
        callId: event.callId,
        success: result.ok,
        output: result.output ?? '',
        errorDetail: result.error ?? '',
      }),
    });
  } catch {
    // Best-effort: if the POST fails, the agent will time out after
    // 30s server-side. No way to surface the network error here
    // without polluting the FE error log; the dispatcher's contract
    // is fire-and-forget after best-effort delivery.
  }
  return result;
}
