// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
import { expect, it } from 'vitest';
import { render, type TemplateResult } from 'lit';
import './BrainSurface.js';

function panel(procedure: string | undefined, live = true, engineReason = 'automatic-vdu') {
  const element = document.createElement('jf-brain-surface') as unknown as {
    busy: Record<string, boolean>;
    policy: unknown;
    installStatus: unknown;
    runtimeStatus: unknown;
    inference: unknown;
    _unifiedAiState: unknown;
    renderSimplePanel(): TemplateResult;
  };
  element.busy = {};
  element.policy = { downloadsEnabled: true, onlineAiEnabled: true };
  element.installStatus = { state: 'completed', installedFully: true };
  element.inference = null;
  element.runtimeStatus = { onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }] };
  element._unifiedAiState = {
    aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null }, capabilities: { chat: true },
    installObservationLive: live, runtimeObservationLive: live, snapshotLive: live,
    status: { inference: {
      procedure, engineReason, chatEnabledSpec: engineReason !== 'inference.up_for_background',
    } },
  };
  const container = document.createElement('div');
  render(element.renderSimplePanel(), container);
  return container;
}

// Slice 7a (AM9): picture reading and answers in answers words; the answer switch stays under its step.
it('names picture reading while loaded chat lends its server to a VDU pass', () => {
  const view = panel('VDU_BATCH');
  expect(view.querySelector('.brain-headline')?.textContent).toBe('Reading text in scans and images');
  expect(view.querySelector('.brain-sentence')?.textContent).toBe('Answers resume after the current document.');
  expect(view.textContent).not.toContain('Everything below runs on this computer.');
  expect(view.querySelector('[data-step="chat"]')).not.toBeNull();
});

it('keeps normal answer wording after the hold closes and never claims stale picture reading', () => {
  expect(panel(undefined).querySelector('.brain-sentence')?.textContent).toBe('Everything below runs on this computer.');
  expect(panel('VDU_BATCH', false).textContent).not.toContain('Reading text in scans and images');
});

it('keeps the original panel sentence for explicit and chat-off automatic passes', () => {
  const normal = panel(undefined).querySelector('.brain-sentence')?.textContent;
  for (const engineReason of ['healthy', 'inference.up_for_background']) {
    const view = panel('VDU_BATCH', true, engineReason);
    expect(view.querySelector('.brain-headline')?.textContent).toBe('Reading text in scans and images');
    expect(view.querySelector('.brain-sentence')?.textContent).toBe(normal);
    expect(view.textContent).not.toContain('Answers resume after the current document.');
  }
});
