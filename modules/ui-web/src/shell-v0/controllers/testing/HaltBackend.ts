// SPDX-License-Identifier: Apache-2.0
import type { AgentSessionTransport } from '../AgentSessionController.js';
import type { LiveRunRow } from '../liveRuns.js';

type Dispatch = (event: string, payload: unknown) => void;
export interface HeldResponse {
  path: string;
  owner: number;
  settled: boolean;
  init?: RequestInit;
  release(status?: number, body?: unknown): void;
  fail(): void;
}
export interface Observation {
  runId: string;
  dispatch: Dispatch;
  signal?: AbortSignal;
  lose(): void;
  close(): void;
}

/** One backend, with an independent transport for each window. Nothing is persisted in a window. */
export class HaltBackend {
  readonly responses: HeldResponse[] = [];
  readonly observations: Observation[] = [];
  readonly runs = new Map<string, { state: string; conversationId: string }>();
  readonly requests: Array<{ path: string; init?: RequestInit }> = [];
  readonly queued: Array<() => void> = [];
  readonly holdResponses = new Set<string>();
  private transportSerial = 0;
  discoveries = 0;
  private serial = 0;

  transport(): AgentSessionTransport {
    const owner = ++this.transportSerial;
    return {
      request: async (input, init) => {
        const path = new URL(String(input)).pathname;
        this.requests.push({ path, init });
        const body = path === '/api/chat/approval'
          ? { callId: 'manual', operationId: 'core.search', argsSummary: 'Search documents', riskTier: 'LOW', gateBehavior: 'INLINE_CONFIRM' }
          : path.startsWith('/api/thread/')
          ? { lifecycles: [...this.runs].map(([sessionId, run]) => ({ sessionId, state: run.state })) } : {};
        if (init?.method === 'DELETE' || this.holdResponses.has(path)) {
          return new Promise<Response>((resolve, reject) => {
            const response: HeldResponse = { path, owner, init, settled: false,
              release: (status = 200, data = body) => { response.settled = true; resolve(Response.json(data, { status })); },
              fail: () => { response.settled = true; reject(new Error('network lost')); },
            };
            this.responses.push(response);
          });
        }
        return Response.json(body);
      },
      stream: async (options) => {
        const runId = `run-${++this.serial}`;
        this.runs.set(runId, { state: 'READY_FOR_LLM', conversationId: String(options.body.conversationId ?? 'conversation') });
        await this.observe(runId, options.onEvent, options.signal);
      },
      consume: async (url, _body, dispatch, signal) => {
        const path = new URL(String(url)).pathname;
        const runId = path.endsWith('/resume') || path.endsWith('/fork') ? `run-${++this.serial}`
          : decodeURIComponent(path.split('/runs/')[1]!.split('/')[0]!);
        if (!this.runs.has(runId)) this.runs.set(runId, { state: 'READY_FOR_LLM', conversationId: 'conversation' });
        await this.observe(runId, dispatch, signal);
      },
      discover: async (_base, conversationId) => {
        this.discoveries++;
        for (const [runId, run] of [...this.runs].reverse()) {
          if (['DONE', 'CANCELLED', 'ERROR'].includes(run.state)) continue;
          if (conversationId && run.conversationId !== conversationId) continue;
          return { runId, shapeId: 'core.agent-run', conversationId: run.conversationId, state: 'running', park: null } as LiveRunRow;
        }
        return null;
      },
    };
  }

  private async observe(runId: string, dispatch: Dispatch, signal?: AbortSignal): Promise<void> {
    await new Promise<void>((resolve, reject) => {
      const observation: Observation = {
        runId, dispatch, signal,
        lose: () => reject(new Error('Stream ended without terminal event')),
        close: resolve,
      };
      this.observations.push(observation);
      signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true });
    });
  }

  identity(index = this.observations.length - 1): void {
    const observation = this.observations[index]!;
    observation.dispatch('run_started', { runId: observation.runId, conversationId: this.runs.get(observation.runId)!.conversationId });
    observation.dispatch('session_started', { sessionId: observation.runId });
  }

  emit(event: string, payload: unknown, index = this.observations.length - 1): void {
    this.observations[index]!.dispatch(event, payload);
  }

  hold(event: string, payload: unknown, index: number): void {
    this.queued.push(() => this.emit(event, payload, index));
  }

  release(index: number): void { this.queued.splice(index, 1)[0]!(); }

  terminal(runId: string, state: 'DONE' | 'CANCELLED', deliver = true): void {
    if (['DONE', 'CANCELLED', 'ERROR'].includes(this.runs.get(runId)!.state)) return;
    this.runs.get(runId)!.state = state;
    if (!deliver) return;
    for (const observation of this.observations.filter((stream) => stream.runId === runId)) {
      observation.dispatch(state === 'DONE' ? 'done' : 'error', state === 'DONE'
        ? { finalResponse: 'Completed answer', disposition: 'COMPLETED' }
        : { error: 'Session cancelled', errorCode: 'CANCELLED' });
      observation.close();
    }
  }
}
