// SPDX-License-Identifier: Apache-2.0
/**
 * host.ai capability module (548 §4.2 Increment A) — extracted verbatim from
 * HostApiImpl. Builds the AI sub-interface for the given trust tier. Trust
 * attenuation is composition (521 §2.3): the tier indexes a table of
 * structural variants; UNTRUSTED gets a denied openSession. Pure module
 * extraction behind the unchanged PluginHostApi facade; no behavior change.
 */

import { authorizedFetch } from '../../api/authorizedFetch.js';
import { createStatusExecutionObserver } from '../../../api/statusInvalidation.js';
import type {
  PluginAI,
  PluginTrustTier,
  AIChunk,
  AIResponse,
  AIShapeBody,
  AISession,
  AISessionMetadata,
  AITranscriptSnapshot,
  AITranscriptMessage,
} from '../plugin-types.js';

export function createPluginAI(tier: PluginTrustTier, apiBase: string): PluginAI {
  const post = async (
    shapeId: string,
    body: AIShapeBody,
    audience: 'USER' | 'AGENT' = 'USER',
    signal?: AbortSignal,
  ): Promise<Response> => {
    // Tempdoc 521 §16.1 — host.ai.streamShape now routes through the
    // unified shape dispatcher (POST /api/chat/dispatch, AiRoutes.java).
    // Previously it pinned every plugin shape call to /api/chat/agent
    // (the AgentRunShape route), which forced every plugin-emitted
    // shapeId into the agent shape regardless of the requested id —
    // a bug for any plugin trying to invoke a non-agent shape. The
    // dispatch endpoint reads shapeId from the body via
    // ChatController.dynamicHandler and dispatches to the correct
    // ConversationShape (free, ask, summarize, extract, navigate,
    // batch-summarize, hierarchical-summarize, agent, …).
    // 804 B3: authorizedFetch, not bare fetch — this POST is token-enforced in
    // the packaged app. It keeps its own reader loop (streamShape needs the raw
    // Response plus the audience header, which consumeShapeStream cannot carry).
    return authorizedFetch(`${apiBase}/api/chat/dispatch`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-JustSearch-Audience': audience,
      },
      body: JSON.stringify({ shapeId, ...body }),
      ...(signal !== undefined ? { signal } : {}),
    });
  };

  async function* parseSseStream(res: Response): AsyncGenerator<AIChunk> {
    if (!res.body) return;
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    const observeExecution = createStatusExecutionObserver();
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      // SSE frames separated by blank lines (\n\n).
      let idx: number;
      const chunks: AIChunk[] = [];
      while ((idx = buffer.indexOf('\n\n')) !== -1) {
        const frame = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        const chunk = parseFrame(frame);
        if (chunk) chunks.push(chunk);
      }
      await Promise.all(chunks.map(chunk => observeExecution(chunk.name, chunk.payload)));
      yield* chunks;
    }
    if (buffer.trim().length > 0) {
      const chunk = parseFrame(buffer);
      if (chunk) {
        await observeExecution(chunk.name, chunk.payload);
        yield chunk;
      }
    }
  }

  function parseFrame(raw: string): AIChunk | null {
    let name = 'message';
    const dataLines: string[] = [];
    for (const line of raw.split('\n')) {
      if (line.startsWith('event:')) name = line.slice(6).trim();
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart());
      // Ignore id:, retry:, comments.
    }
    if (dataLines.length === 0) return null;
    const data = dataLines.join('\n');
    let payload: unknown = data;
    try { payload = JSON.parse(data); } catch { /* keep raw string */ }
    return { name, payload };
  }

  async function* streamShape(
    shapeId: string,
    body: AIShapeBody,
    signal?: AbortSignal,
  ): AsyncGenerator<AIChunk> {
    // §13 A6: structured error envelope with `kind` discriminant.
    // Tempdoc 521 §16.1 (phase A): optional AbortSignal is forwarded into
    // fetch so view-level cancel (Stop button, surface unmount) tears the
    // SSE stream down without leaking the underlying connection.
    let res: Response;
    try {
      res = await post(shapeId, body, 'USER', signal);
    } catch (err) {
      yield {
        name: 'error',
        payload: {
          kind: 'transport-error',
          detail: err instanceof Error ? err.message : String(err),
        },
      };
      return;
    }
    if (!res.ok) {
      yield {
        name: 'error',
        payload: {
          kind: 'http-error',
          status: res.status,
          detail: `HTTP ${res.status}`,
        },
      };
      return;
    }
    yield* parseSseStream(res);
  }

  async function invokeShape(
    shapeId: string,
    body: AIShapeBody,
    signal?: AbortSignal,
  ): Promise<AIResponse> {
    const events: AIChunk[] = [];
    let text = '';
    for await (const chunk of streamShape(shapeId, body, signal)) {
      events.push(chunk);
      // Best-effort text concatenation across common event names.
      if (chunk.name === 'token' || chunk.name === 'assistant-text' || chunk.name === 'message') {
        if (typeof chunk.payload === 'string') {
          text += chunk.payload;
        } else if (
          chunk.payload !== null &&
          typeof chunk.payload === 'object' &&
          'text' in (chunk.payload as Record<string, unknown>) &&
          typeof (chunk.payload as Record<string, unknown>)['text'] === 'string'
        ) {
          text += (chunk.payload as Record<string, string>)['text'];
        }
      }
    }
    return { text, events };
  }

  function openSession(shapeId: string, sessionId?: string): AISession {
    const id = sessionId ?? `s-${crypto.randomUUID()}`;
    let closed = false;
    return {
      id,
      send(message: AIShapeBody, signal?: AbortSignal): AsyncIterable<AIChunk> {
        if (closed) {
          // §13 A6: discriminated kind so callers distinguish
          // "session ran out" from other failures.
          return (async function* () {
            yield {
              name: 'error',
              payload: {
                kind: 'session-closed',
                detail: 'session was closed before this send()',
              },
            };
          })();
        }
        return streamShape(shapeId, { sessionId: id, ...message }, signal);
      },
      close() {
        closed = true;
      },
    };
  }

  // Tempdoc 508-followup §ε1 — transcript + metadata fetchers. Plugins
  // use these to resume / inspect existing chat sessions. The wire
  // shape is plugin-opaque; we surface `messages: ReadonlyArray<...>`
  // and the metadata fields the backend has been emitting since
  // tempdoc 491 §5.
  async function getSessionTranscript(sessionId: string): Promise<AITranscriptSnapshot> {
    const res = await authorizedFetch(
      `${apiBase}/api/chat/sessions/${encodeURIComponent(sessionId)}/transcript`,
    );
    if (!res.ok) {
      throw new Error(
        `getSessionTranscript('${sessionId}') HTTP ${res.status}`,
      );
    }
    const data = (await res.json()) as { messages?: unknown };
    const rawMessages = Array.isArray(data.messages) ? data.messages : [];
    const messages: AITranscriptMessage[] = rawMessages.flatMap((m): AITranscriptMessage[] => {
      if (m === null || typeof m !== 'object') return [];
      const obj = m as Record<string, unknown>;
      const role = obj['role'];
      const content = obj['content'];
      if (typeof content !== 'string') return [];
      const normalizedRole =
        role === 'user' || role === 'assistant' || role === 'system' || role === 'tool'
          ? role
          : 'assistant';
      const ts = obj['timestamp'];
      return [
        {
          role: normalizedRole,
          content,
          ...(typeof ts === 'string' ? { timestamp: ts } : {}),
        },
      ];
    });
    return { messages, sessionId };
  }

  async function getSessionMetadata(sessionId: string): Promise<AISessionMetadata> {
    const res = await authorizedFetch(
      `${apiBase}/api/chat/sessions/${encodeURIComponent(sessionId)}`,
    );
    if (!res.ok) {
      throw new Error(
        `getSessionMetadata('${sessionId}') HTTP ${res.status}`,
      );
    }
    const data = (await res.json()) as Record<string, unknown>;
    const pickString = (key: string): string | undefined =>
      typeof data[key] === 'string' ? (data[key] as string) : undefined;
    return {
      sessionId,
      ...(pickString('shapeId') !== undefined ? { shapeId: pickString('shapeId')! } : {}),
      ...(pickString('title') !== undefined ? { title: pickString('title')! } : {}),
      ...(pickString('createdAt') !== undefined ? { createdAt: pickString('createdAt')! } : {}),
      ...(pickString('lastUpdatedAt') !== undefined
        ? { lastUpdatedAt: pickString('lastUpdatedAt')! }
        : {}),
    };
  }

  // §13.4 attenuation: deny PERSISTENT openSession (long-lived history) for
  // UNTRUSTED; stateless invokeShape/streamShape remain available. 548 §4.2 /
  // 521 §2.3 — trust attenuation as *composition*, not a runtime `if (tier)`
  // branch: the tier indexes a table of structural variants. The UNTRUSTED
  // variant's openSession throws synchronously at the wrong-tier call site
  // (§13 A6) rather than deferring to a confusing first-send error chunk.
  const deniedOpenSession: PluginAI['openSession'] = () => {
    throw new Error(
      'host.ai.openSession() is restricted for UNTRUSTED plugins; ' +
        'use host.ai.invokeShape() or host.ai.streamShape() instead.',
    );
  };
  const openSessionByTier: Record<PluginTrustTier, PluginAI['openSession']> = {
    CORE: openSession,
    TRUSTED_PLUGIN: openSession,
    UNTRUSTED_PLUGIN: deniedOpenSession,
  };

  return {
    invokeShape,
    streamShape,
    openSession: openSessionByTier[tier],
    getSessionTranscript,
    getSessionMetadata,
  };
}
