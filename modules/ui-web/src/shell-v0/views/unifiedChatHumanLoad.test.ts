/**
 * Spec A step 9 (reconciliation F8/CH1) — the chat window's own send marks its dispatch as a
 * person's (`allowLoad: true`), so the Engine may load an unloaded chat model for it; the shared
 * body builder, which the search window's ask reuses, never adds the flag.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import * as request from './unifiedChatRequest.js';

const here = dirname(fileURLToPath(import.meta.url));

type Body = Record<string, unknown>;

describe('unifiedChatRequest — Spec A step 9 allowLoad', () => {
  it("withHumanLoad marks the body as a person's chat and keeps the rest", () => {
    const mark = (request as Record<string, unknown>)['withHumanLoad'];
    expect(typeof mark).toBe('function');
    const body = request.buildRequestBody('core.free-chat', 'hello', 's1', '', []);
    const marked = (mark as (b: Body) => Body)(body);
    expect(marked['allowLoad']).toBe(true);
    expect(marked['prompt']).toBe('hello');
    expect(marked['sessionId']).toBe('s1');
  });

  it('the shared body builder itself never sends allowLoad', () => {
    for (const shape of ['core.free-chat', 'core.rag-ask', 'core.extract', 'core.agent-run'] as const) {
      expect('allowLoad' in request.buildRequestBody(shape, 't', 's', '', [])).toBe(false);
    }
  });

  it('the chat window marks its /api/chat/dispatch body before it sends it', () => {
    const source = readFileSync(resolve(here, 'search-v3/sv3-ask.ts'), 'utf8');
    const marked = source.indexOf('withHumanLoad(body);');
    const dispatch = source.indexOf('await consumeShapeStream(');
    expect(marked).toBeGreaterThan(-1);
    expect(dispatch).toBeGreaterThan(marked);
    expect(source.split('withHumanLoad(body)').length - 1).toBe(1);
  });
});
