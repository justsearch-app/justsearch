// @vitest-environment happy-dom

/**
 * Tempdoc 737 §3b+§3d + §12b/§12d — the model-agnostic escape-action regression that replaces the
 * retired `BrainSurface.indexing-escape.test.ts` fossil.
 *
 * The headline this pins: "from every reachable runtime state the store models, the Simple-panel
 * primary action EXISTS (never a dead button) and, for the chat-lifecycle states, writes the user's
 * chat-enabled INTENT via `core.set-chat-enabled` with the right arg." The old test pinned the same
 * escape hatch against `core.switch-inference-mode` — a mode toggle that could be capability-denied
 * (the §3b circular class); the intent write has no preconditions, so the escape action is always
 * legal (§12b). Labels are asserted against `aiEngineHeadline`/the panel, not re-forked literals.
 */

import { describe, expect, it } from 'vitest';
import { render, type TemplateResult } from 'lit';
import './BrainSurface.js';
import { type AiEngineVerdict } from '../state/aiVerdict.js';
import { unavailableBecause } from '../state/availability.js';

interface PanelHarness {
  apiBase: string;
  policy: unknown;
  busy: Record<string, boolean>;
  installStatus: unknown;
  planPreview: unknown;
  inference: unknown;
  _unifiedAiState: unknown;
  host_: unknown;
  fetchJson(path: string): Promise<unknown>;
  renderSimplePanel(): TemplateResult;
}

interface ActionButton extends Element {
  onActivate: () => unknown;
  availability: unknown;
}

const verdict = (kind: AiEngineVerdict['kind']): AiEngineVerdict =>
  ({ kind, stability: { kind: 'settled' }, installFailure: null }) as AiEngineVerdict;

function renderPanel(kind: AiEngineVerdict['kind'], opts: { onlineAiEnabled?: boolean; chatUnavailable?: string } = {}) {
  const invocations: Array<{ operationId: string; args: Record<string, unknown> }> = [];
  const el = document.createElement('jf-brain-surface') as unknown as PanelHarness;
  el.apiBase = '';
  el.busy = {};
  el.policy = { downloadsEnabled: true, onlineAiEnabled: opts.onlineAiEnabled ?? true };
  el.installStatus = null;
  el.planPreview = opts.chatUnavailable ? { components: [{ id: 'chat', state: 'unavailable', unavailableReason: opts.chatUnavailable }] } : null;
  el.inference = null;
  el._unifiedAiState = { aiEngine: verdict(kind) };
  el.host_ = {
    data: {
      invokeOperation: (operationId: string, args: Record<string, unknown>) => {
        invocations.push({ operationId, args });
        return Promise.resolve({ structuredData: null });
      },
    },
  };
  el.fetchJson = () => Promise.resolve(null);

  const container = document.createElement('div');
  render(el.renderSimplePanel(), container);
  const button = container.querySelector('[data-testid="brain-simple-action"]') as ActionButton;
  return { container, button, invocations };
}

// The chat-lifecycle states + the enabled arg each one's primary action must write. These are the
// §3d escape actions: from ANY of them the user can reach the state they want with ONE legal intent
// write, and none re-POSTs the state it is already in.
const CHAT_INTENT_STATES: ReadonlyArray<[AiEngineVerdict['kind'], boolean]> = [
  ['offline', true], // Start AI
  ['indexing', true], // Resume Chat AI
  ['background', true], // Start Chat AI (soft-off — never a dead button, §15 decision 1)
  ['starting', false], // Cancel
  ['online', false], // Shut Down AI
];

describe('BrainSurface Simple panel — chat-enabled intent write (tempdoc 737 §3b/§3d)', () => {
  for (const [kind, enabled] of CHAT_INTENT_STATES) {
    it(`${kind}: the primary action exists and invokes core.set-chat-enabled {enabled:${enabled}}`, async () => {
      const { button, invocations } = renderPanel(kind);

      // No dead button: the primary action is present and carries a non-empty label.
      expect(button).not.toBeNull();
      expect(button.getAttribute('label')?.length ?? 0).toBeGreaterThan(0);

      await button.onActivate();

      expect(invocations).toEqual([{ operationId: 'core.set-chat-enabled', args: { enabled } }]);
    });
  }

  it('disables Start AI with the plan reason when chat is unavailable', () => {
    const reason = 'Chat runtime is not supplied for linux-x86_64.';
    const { button, container } = renderPanel('offline', { chatUnavailable: reason });
    expect(button.availability).toEqual(unavailableBecause(reason));
    expect(container.textContent).toContain(reason);
  });

  it('never posts the superseded core.switch-inference-mode from any chat-lifecycle state', async () => {
    for (const [kind] of CHAT_INTENT_STATES) {
      const { button, invocations } = renderPanel(kind);
      await button.onActivate();
      expect(invocations.map((i) => i.operationId)).not.toContain('core.switch-inference-mode');
    }
  });

  it('gates the enable action behind the online-AI policy with a showable reason (offline)', () => {
    const { button } = renderPanel('offline', { onlineAiEnabled: false });
    expect(button.availability).toEqual(
      unavailableBecause('Online AI is disabled by administrator policy.'),
    );
  });

  it('gates the soft-off "Start Chat AI" action behind the same policy, with a reason', () => {
    const { button } = renderPanel('background', { onlineAiEnabled: false });
    expect(button.availability).toEqual(
      unavailableBecause('Online AI is disabled by administrator policy.'),
    );
  });
});
