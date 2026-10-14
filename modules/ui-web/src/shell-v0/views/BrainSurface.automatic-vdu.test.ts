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
    inference: unknown;
    _unifiedAiState: unknown;
    renderSimplePanel(): TemplateResult;
  };
  element.busy = {};
  element.policy = { downloadsEnabled: true, onlineAiEnabled: true };
  element.installStatus = null;
  element.inference = null;
  element._unifiedAiState = {
    aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
    snapshotLive: live,
    status: { inference: {
      procedure, engineReason, chatEnabledSpec: engineReason !== 'inference.up_for_background',
    } },
  };
  const container = document.createElement('div');
  render(element.renderSimplePanel(), container);
  return container;
}

it('names picture reading while loaded chat lends its server to a VDU pass', () => {
  const view = panel('VDU_BATCH');
  expect(view.textContent).toContain('Reading pictures. Chat resumes after the current document.');
  expect(view.textContent).not.toContain('Chat and summaries ready.');
  expect(view.querySelector('[data-testid="brain-simple-action"]')).not.toBeNull();
});

it('keeps normal chat wording after the hold closes and never claims stale picture reading', () => {
  expect(panel(undefined).textContent).toContain('Chat and summaries ready.');
  expect(panel('VDU_BATCH', false).textContent).not.toContain('Reading pictures.');
});

it('keeps the original panel sentence for explicit and chat-off automatic passes', () => {
  for (const engineReason of ['healthy', 'inference.up_for_background']) {
    const view = panel('VDU_BATCH', true, engineReason);
    expect(view.textContent).toContain('Chat and summaries ready.');
    expect(view.textContent).not.toContain('Chat resumes after the current document.');
  }
});
