// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './Shell.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { __resetBootstrapForTest } from '../router/bootstrap.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';

afterEach(() => {
  document.body.replaceChildren(); __resetAiStateForTest(); __resetBootstrapForTest();
  __resetUserConfigForTest(); vi.unstubAllGlobals();
});
it('ST10 the frame has one status destination and no retired presenter mounts', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ entries: [] }), { status: 200 })));
  const shell = document.createElement('jf-shell') as HTMLElement & { updateComplete: Promise<unknown> };
  document.body.append(shell); await shell.updateComplete;
  await new Promise(resolve => setTimeout(resolve, 30)); await shell.updateComplete;
  expect(shell.shadowRoot!.querySelectorAll('jf-status-pill'), 'one always-mounted title-bar status owner').toHaveLength(1);
  expect(shell.shadowRoot!.querySelectorAll('jf-status-deck,jf-app-update-banner,jf-advisory-rail-badge,jf-advisory-toast-host,jf-advisory-inbox-drawer,jf-ai-activity-digest,jf-task-list,jf-pending-effect-queue,jf-agent-activity-panel,jf-plugin-error-overlay,jf-resolution-toast,jf-indexing-overlay-host')).toHaveLength(0);
  const pill = shell.shadowRoot!.querySelector('jf-status-pill') as HTMLElement & { updateComplete: Promise<unknown> };
  await pill.updateComplete;
  expect(pill.shadowRoot!.querySelectorAll('jf-status-panel')).toHaveLength(1);
});
it('ref-07 the shell Undo command (Ctrl+Z) reverses the undo notice showing in the pill, not while typing, after a shell remount too', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ entries: [] }), { status: 200 })));
  const { getAction } = await import('../substrates/actions/index.js');
  const { emitEphemeralToast } = await import('../components/advisory/ephemeralToast.js');
  for (const round of [1, 2]) {
    const shell = document.createElement('jf-shell') as HTMLElement & { updateComplete: Promise<unknown> };
    document.body.append(shell); await shell.updateComplete;
    await new Promise(resolve => setTimeout(resolve, 30)); await shell.updateComplete;
    const undo = vi.fn();
    emitEphemeralToast({ message: `Skip rule added ${round}`, actionLabel: 'Undo', onAction: undo });
    await new Promise(resolve => setTimeout(resolve, 0));
    // While typing, Undo keeps its text meaning and the notice is left alone (C8).
    const field = document.createElement('input'); document.body.append(field); field.focus();
    getAction('core.action.shell.undo')!.handler!({}, null);
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(undo, `round ${round}: typing`).not.toHaveBeenCalled();
    field.remove();
    // The typing fallback posted its own receipt; a fresh undo notice takes the slot again.
    emitEphemeralToast({ message: `Skip rule added again ${round}`, actionLabel: 'Undo', onAction: undo });
    await new Promise(resolve => setTimeout(resolve, 0));
    getAction('core.action.shell.undo')!.handler!({}, null);
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(undo, `round ${round}`).toHaveBeenCalledTimes(1);
    shell.remove();
  }
});
