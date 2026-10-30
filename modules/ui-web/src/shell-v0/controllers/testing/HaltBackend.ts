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
  lose(error?: Error): void;
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
    const transport: AgentSessionTransport = {
      request: async (input, init) => {
        const path = new URL(String(input)).pathname;
        this.requests.push({ path, init });
        const body = path === '/api/chat/approval'
          ? { callId: 'manual', operationId: 'core.search', argsSummary: 'Search documents', riskTier: 'LOW', gateBehavior: 'INLINE_CONFIRM' }
          : path.startsWith('/api/thread/')
          ? { lifecycles: [...this.runs].filter(([, run]) => path === `/api/thread/${run.conversationId}`).map(([sessionId, run]) => ({ sessionId, state: run.state })) }
          : path === '/api/chat/runs/live' ? { runs: [...this.runs].reverse().filter(([, run]) => !['DONE', 'CANCELLED', 'ERROR'].includes(run.state)).map(([runId, run]) => ({ runId, conversationId: run.conversationId, shapeId: 'core.agent-run', state: 'running', park: null })) } : {};
        if (init?.method === 'DELETE' || this.holdResponses.has(path)) {
          return new Promise<Response>((resolve, reject) => {
            const response: HeldResponse = { path, owner, init, settled: false,
              release: (status = 200, data = body) => { response.settled = true; resolve(Response.json(data, { status })); },
              fail: () => { response.settled = true; reject(new Error('network lost')); },
            };
            this.responses.push(response);
            init?.signal?.addEventListener('abort', () => { response.settled = true; reject(new DOMException('aborted', 'AbortError')); }, { once: true });
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
      discover: async (_base, conversationId, signal) => {
        this.discoveries++;
        const response = await transport.request(`${_base}/api/chat/runs/live`, { signal });
        if (!response.ok) return null;
        const { runs } = await response.json() as { runs: LiveRunRow[] };
        for (const run of runs) {
          if (conversationId && run.conversationId !== conversationId) continue;
          return run;
        }
        return null;
      },
    };
    return transport;
  }

  private async observe(runId: string, dispatch: Dispatch, signal?: AbortSignal): Promise<void> {
    await new Promise<void>((resolve, reject) => {
      const observation: Observation = {
        runId, dispatch, signal,
        lose: (error = Object.assign(new Error('Stream ended without terminal event'), { code: 'STREAM_INCOMPLETE' })) => reject(error),
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
