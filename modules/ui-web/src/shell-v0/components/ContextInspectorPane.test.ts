// @vitest-environment happy-dom
//
// Tempdoc 610 §K/§J — the details pane's Context tab: renders the whole-prompt projection (system +
// conversation + documents) the last turn saw, with per-phase token estimates + the §L.1 position marker.

import { describe, expect, it } from 'vitest';
import './ContextInspectorPane.js';
import type { ContextInspectorPane, InspectorView } from './ContextInspectorPane.js';

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

const VIEW: InspectorView = {
  systemTokens: 120,
  phases: [
    {
      name: 'Conversation',
      tokens: 450,
      segments: [
        { label: 'You', text: 'first question', tokens: null, position: 'strong' },
        { label: 'Assistant', text: 'a mid answer', tokens: null, position: 'weak' },
        { label: 'You', text: 'latest question', tokens: null, position: 'strong' },
      ],
    },
    {
      name: 'Documents',
      tokens: 454,
      segments: [{ label: 'Budget', text: 'the budget report', tokens: null, position: 'strong' }],
    },
  ],
  totalTokens: 1024,
  windowTokens: 4096,
};

describe('ContextInspectorPane (610 §K)', () => {
  it('renders the phases, the system line, the position marker, and the real total', async () => {
    const el = document.createElement('jf-context-inspector-pane') as ContextInspectorPane;
    el.view = VIEW;
    document.body.appendChild(el);
    await settle(el);

    const text = el.shadowRoot?.textContent ?? '';
    expect(text).toContain('System instructions');
    expect(text).toContain('~120 tok');
    expect(text).toContain('Conversation');
    expect(text).toContain('Documents');
    // §L.1 — the mid-context segment carries the weak-position caveat; the strong ones do not.
    expect(el.shadowRoot?.querySelectorAll('.seg-weak').length).toBe(1);
    // The real grand total (authoritative), distinct from the estimated split.
    expect(text).toContain('1024 of 4096 tokens used (real)');
    el.remove();
  });

  it('shows the empty state when no turn has completed', async () => {
    const el = document.createElement('jf-context-inspector-pane') as ContextInspectorPane;
    el.view = { systemTokens: null, phases: [], totalTokens: null, windowTokens: null };
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot?.querySelector('.empty-state')).not.toBeNull();
    el.remove();
  });
});
