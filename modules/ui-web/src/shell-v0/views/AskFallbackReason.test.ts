// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest';
import { render, type TemplateResult } from 'lit';
import './UnifiedChatView.js';
import './search-v3/Sv3Main.js';

describe('Ask retrieval explanation', () => {
  it('Unified Chat includes the fallback cause in its retrieval preamble', () => {
    const view = document.createElement('jf-unified-chat-view') as unknown as {
      ragMeta: Record<string, unknown>; renderPreamble(): TemplateResult;
    };
    view.ragMeta = { retrieval_mode: 'BM25', chunks_used: 1,
      retrieval_mode_reason: 'SEMANTIC_BUDGET_EXCEEDED', retrieval_mode_cause: 'stuck' };
    const host = document.createElement('div');
    render(view.renderPreamble(), host);
    expect(host.textContent).toContain('semantic search did not answer in time');
    expect(host.textContent).toContain('encoder stalled');
  });

  function askNote(status: 'complete' | 'failed'): string {
    const view = document.createElement('jf-sv3-main') as unknown as {
      turnNote(turn: unknown): TemplateResult;
    };
    const turn = { kind: 'ask', status, detail: 'No matching documents found in the index',
      evidence: { sources: status === 'complete' ? [{ parentDocId: 'doc' }] : [], matches: [],
        retrievalMode: 'BM25', retrievalModeReason: 'SEMANTIC_BUDGET_EXCEEDED',
        retrievalModeCause: 'contended' } };
    const host = document.createElement('div');
    render(view.turnNote(turn), host);
    return host.textContent ?? '';
  }

  it('Search Ask shows its fallback cause without opening sources', () => {
    expect(askNote('complete')).toContain('semantic search did not answer in time');
    expect(askNote('complete')).toContain('encoder busy with other work');
  });

  it('Search Ask keeps its fallback cause on an empty terminal error', () => {
    expect(askNote('failed')).toContain('No matching documents found in the index');
    expect(askNote('failed')).toContain('semantic search did not answer in time');
    expect(askNote('failed')).toContain('encoder busy with other work');
  });
});
