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
  settings: { chatEnabled: boolean };
  settingsRead: boolean;
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
  el.installStatus = { state: 'completed', installedFully: true };
  el.settings = { chatEnabled: kind === 'online' || kind === 'starting' };
  el.settingsRead = true; // This harness supplies the successful settings-read snapshot directly.
  el.planPreview = opts.chatUnavailable ? { components: [{ id: 'chat', state: 'unavailable', unavailableReason: opts.chatUnavailable }] } : { components: [{ id: 'embedding', state: 'installed' }, { id: 'chat', state: 'installed', declinable: true }] };
  el.inference = null;
  el._unifiedAiState = { aiEngine: verdict(kind), installObservationLive: true, runtimeObservationLive: true, snapshotLive: true };
  el.host_ = {
    data: {
      invokeOperation: (operationId: string, args: Record<string, unknown>) => {
        invocations.push({ operationId, args });
        return Promise.resolve({ structuredData: null });
      },
    },
  };
  el.fetchJson = () => Promise.resolve(null);

  // Slice 7a (D2, D5): the escape action is the Answer questions switch under its step; the old
  // Start/Stop answers button is a developer tool. Activating the switch writes the same intent.
  const container = document.createElement('div');
  render(el.renderSimplePanel(), container);
  const control = container.querySelector<HTMLButtonElement>('[data-step="chat"] [role="switch"]');
  const reason = () => container.querySelector(`[id="${control?.id}-reason"]`)?.textContent?.trim() ?? '';
  const button = control === null ? null : Object.assign(control, {
    onActivate: async () => { control.click(); for (let i = 0; i < 3; ++i) await new Promise(resolve => setTimeout(resolve, 0)); },
    get availability() { return control.getAttribute('aria-disabled') === 'true' ? unavailableBecause(reason()) : { kind: 'available' }; },
  }) as unknown as ActionButton;
  return { container, button: button as ActionButton, invocations };
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

      // No dead control: the answer switch is present with its accessible name.
      expect(button).not.toBeNull();
      expect(button.getAttribute('aria-label')).toBe('Use Answer questions');

      await button.onActivate();

      expect(invocations).toEqual([{ operationId: 'core.set-chat-enabled', args: { enabled } }]);
    });
  }

  it('draws answers as not reachable when chat is unavailable: no switch, the reason once in ordinary words', () => {
    const reason = 'Chat runtime is not supplied for linux-x86_64.';
    const { button, container } = renderPanel('offline', { chatUnavailable: reason });
    expect(button).toBeNull();
    const step = container.querySelector('[data-step="chat"]')!;
    expect(step.getAttribute('data-reach')).toBe('na');
    expect(step.querySelector('.step-reason')?.textContent?.trim()).toBeTruthy();
    expect(container.textContent).not.toContain(reason);
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
