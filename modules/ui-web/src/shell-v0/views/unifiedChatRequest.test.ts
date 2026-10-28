import { describe, it, expect } from 'vitest';
import { buildRequestBody, } from './unifiedChatRequest.js';
import type { SelectionPayload } from '../../api/types/selection.js';

describe('buildRequestBody (tempdoc 621 Phase 2 — extracted request shaping)', () => {
  it('rag-ask carries `question` + `docIds` (scoped retrieval), not `prompt`', () => {
    const body = buildRequestBody('core.rag-ask', 'what is X', 's1', '', ['d1', 'd2']);
    expect(body).toMatchObject({ shapeId: 'core.rag-ask', question: 'what is X', docIds: ['d1', 'd2'] });
    expect(body.prompt).toBeUndefined();
    expect(body.sessionId).toBeUndefined();
  });

  it('extract carries `prompt` + `schema`', () => {
    const body = buildRequestBody('core.extract', 'pull fields', 's1', '{"a":1}', []);
    expect(body).toMatchObject({ shapeId: 'core.extract', prompt: 'pull fields', schema: '{"a":1}' });
  });

  it('extract carries a document reference, so the schema is not applied to thin air', () => {
    const sel: SelectionPayload = {
      kind: 'item',
      itemKind: 'search-hit',
      itemId: '/docs/invoice.md',
      label: 'invoice.md',
    };
    const body = buildRequestBody('core.extract', 'pull fields', 's1', '{"a":1}', [], sel);
    // body.selection is the document channel: ExtractShape declares SelectionContextInjector,
    // which resolves this reference to real document content.
    expect(body.selection).toEqual(sel);
  });

  it('free-chat / agent-run carry `prompt` + `sessionId`', () => {
    for (const shape of ['core.free-chat', 'core.agent-run'] as const) {
      const body = buildRequestBody(shape, 'hi', 'sess-9', '', []);
      expect(body).toMatchObject({ shapeId: shape, prompt: 'hi', sessionId: 'sess-9' });
      expect(body.question).toBeUndefined();
    }
  });

  it('forwards a typed selection verbatim when present, omits it otherwise', () => {
    const sel = { kind: 'text-range', text: 'hello' } as unknown as SelectionPayload;
    expect(buildRequestBody('core.free-chat', 't', 's', '', [], sel).selection).toBe(sel);
    expect(buildRequestBody('core.free-chat', 't', 's', '', [], null).selection).toBeUndefined();
    expect('selection' in buildRequestBody('core.free-chat', 't', 's', '', [])).toBe(false);
  });


});
