// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StatusPill } from './StatusPill.js';
import type { StatusPanel } from './StatusPanel.js';
import { AuthorizationHost } from './AuthorizationHost.js';
import type { JfButton } from './Button.js';
import { requestAuthorization, withdrawAuthorization } from '../operations/authorizationBroker.js';
import { proposeEffect, listPending, __resetPendingForTest } from '../substrates/pending-effects/index.js';
import { listJournal, __resetJournalForTest } from '../substrates/effects/index.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { __seedForTest as seedOps, __resetForTest as resetOps } from '../../api/registry/OperationCatalogClient.js';
import type { OperationCatalog } from '../../api/types/registry.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { requestCapability, listPendingConsentRequests, resolveConsentRequest } from '../substrates/consent/index.js';
let pill: StatusPill;
let host: AuthorizationHost;
const panel = () => pill.shadowRoot!.querySelector<StatusPanel>('jf-status-panel')!;
async function flush() { await Promise.resolve(); await host.updateComplete; pill.status.refresh(); await pill.updateComplete; await panel().updateComplete; await Promise.resolve(); }
async function activate(el: Element | null) {
  if (!el) throw new Error('Missing decision button');
  const button = el as JfButton; await button.updateComplete;
  const control = button.shadowRoot!.querySelector('jf-control') as HTMLElement & { updateComplete: Promise<unknown> };
  // A newly presented prompt ignores activation until its arming delay has passed.
  await control.updateComplete; vi.advanceTimersByTime(400); control.shadowRoot!.querySelector<HTMLButtonElement>('button')!.click(); await flush();
}
/** C12 — a listed answer arms after its row has stood still for the arming delay. */
function arm() { vi.advanceTimersByTime(400); }
function action(label: string) { return [...panel().shadowRoot!.querySelectorAll<HTMLButtonElement>('article button')].find(b => b.textContent!.trim() === label)!; }
function seed(risk: string) {
  seedOps({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Operation', entries: [{
    id: 'core.reindex', presentation: { labelKey: 'core.reindex', descriptionKey: 'core.reindex', iconHint: null, category: null },
    intf: { errors: [], inputs: {}, result: {}, uiHints: {} }, policy: { risk, confirm: { kind: 'NONE' }, audit: 'METADATA_ONLY', undoSupported: false },
    availability: {}, lineage: { affects: [], supersedes: [] }, provenance: CORE_PROVENANCE, executors: ['UI'], audience: 'USER', consumers: [],
  }] } as OperationCatalog);
}
beforeEach(async () => {
  vi.useFakeTimers({ toFake: ['performance'] });
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
  __resetAiStateForTest(); __resetPendingForTest(); __resetJournalForTest(); resetOps();
  host = new AuthorizationHost(); document.body.append(host);
  pill = new StatusPill(); pill.authorizationHost = () => host; document.body.append(pill); await flush();
});
afterEach(() => { pill.remove(); host.remove(); __resetAiStateForTest(); __resetPendingForTest(); __resetJournalForTest(); resetOps();
  for (const r of listPendingConsentRequests()) resolveConsentRequest(r.id, 'deny'); vi.unstubAllGlobals(); vi.useRealTimers(); });
describe('Needs you (moved from proposed-effect cards and agent activity)', () => {
  it('accept executes once and records acceptance; reject records rejection without applying', async () => {
    proposeEffect({ kind: 'open-modal', modalId: 'm1' }, CORE_PROVENANCE, 'agent'); await flush(); arm();
    action('Allow suggestion').click(); action('Allow suggestion').click(); await flush();
    expect(listPending()).toHaveLength(0); expect(listJournal()).toHaveLength(2); expect(listJournal()[1]!.pendingOutcome).toBe('accepted');
    proposeEffect({ kind: 'navigate', to: '#elsewhere' }, CORE_PROVENANCE, 'agent'); await flush(); arm(); action('Reject').click(); await flush();
    expect(listPending()).toHaveLength(0); expect(listJournal()).toHaveLength(3); expect(listJournal()[2]!.pendingOutcome).toBe('rejected');
  });
  it('multiple proposals preserve rationale and rank the least-confident first without inventing confidence', async () => {
    proposeEffect({ kind: 'navigate', to: '#high' }, CORE_PROVENANCE, 'agent', { rationale: 'High', confidence: .9 });
    proposeEffect({ kind: 'navigate', to: '#low' }, CORE_PROVENANCE, 'agent', { rationale: 'Low', confidence: .2 });
    proposeEffect({ kind: 'navigate', to: '#unknown' }, CORE_PROVENANCE, 'system'); await flush();
    const facts = panel().model!.sections.needs;
    expect(facts.map(r => r.confidence)).toEqual([.2, .9, undefined]); expect(facts[0]!.detail).toContain('Low');
    const badges = panel().shadowRoot!.querySelectorAll('jf-status-badge'); expect(badges).toHaveLength(2);
    expect((badges[0] as HTMLElement & { tone: string }).tone).toBe('warning');
  });
  it.each([['HIGH', 'agent'], ['HIGH', 'user'], ['MEDIUM', 'agent']] as const)('%s/%s requires the exact typed operation and preserves consent provenance', async (risk, originator) => {
    seed(risk); const invoke = vi.fn(); document.addEventListener('jf-invoke-operation', invoke);
    try {
      proposeEffect({ kind: 'invoke-operation', operationId: 'core.reindex', args: { exact: 'target' } }, CORE_PROVENANCE, originator);
      await flush(); arm(); action('Allow suggestion').click(); await flush();
      expect(invoke).not.toHaveBeenCalled(); expect(host.active?.kind).toBe('authorize');
      const input = host.shadowRoot!.querySelector<HTMLInputElement>('input[type="text"]')!;
      const approve = () => host.shadowRoot!.querySelector('[data-testid="authorization-approve"]');
      expect(approve()!.hasAttribute('disabled')).toBe(true);
      input.value = 'wrong'; input.dispatchEvent(new Event('input')); await flush(); expect(approve()!.hasAttribute('disabled')).toBe(true);
      input.value = 'core.reindex'; input.dispatchEvent(new Event('input')); await flush(); expect(approve()!.hasAttribute('disabled')).toBe(false);
      await activate(approve()); await flush(); expect(listPending()).toHaveLength(0); expect(invoke).toHaveBeenCalledTimes(1);
      const detail = (invoke.mock.calls[0]![0] as CustomEvent).detail;
      expect(detail.args).toEqual({ exact: 'target' }); expect(detail.consented).toBe(true); expect(detail.confirmationToken).toBeUndefined();
    } finally { document.removeEventListener('jf-invoke-operation', invoke); }
  });
  it('user MEDIUM proposal dispatches without a ceremony or client-minted confirmation token', async () => {
    seed('MEDIUM'); const invoke = vi.fn(); document.addEventListener('jf-invoke-operation', invoke);
    try {
      proposeEffect({ kind: 'invoke-operation', operationId: 'core.reindex', args: {} }, CORE_PROVENANCE, 'user'); await flush();
      arm(); action('Allow suggestion').click(); await flush(); expect(host.active).toBeNull(); expect(invoke).toHaveBeenCalledTimes(1);
      expect((invoke.mock.calls[0]![0] as CustomEvent).detail.confirmationToken).toBeUndefined();
    } finally { document.removeEventListener('jf-invoke-operation', invoke); }
  });
  it('plugin capability requests remain reachable through the protected host', async () => {
    const request = requestCapability({ contributorId: 'test.plugin', capability: 'external-fetch' }); await flush();
    action('Review permissions').click(); await flush(); expect(host.active?.kind).toBe('capability');
    await activate(host.shadowRoot!.querySelector('[data-testid="capability-deny"]')); await request; await flush();
    expect(panel().model!.sections.needs.filter(row => row.id.startsWith('consent:'))).toHaveLength(0);
  });
  it('C12 announcement and panel representations share one decision; stale clicks cannot approve the next request', async () => {
    const prompt = { pendingId: 'one', operationId: 'core.reindex', gateBehavior: 'INLINE_CONFIRM', expiresAt: new Date(Date.now() + 50000).toISOString() };
    const decision = requestAuthorization(prompt); const duplicate = requestAuthorization(prompt); await flush();
    expect(panel().model!.sections.needs.filter(r => r.id === 'authorization:one')).toHaveLength(1);
    // ref-09: the deadline reads as its consequence, relative to now, never as a raw timestamp.
    const deadline = panel().shadowRoot!.querySelector('[data-fact="authorization:one"] [data-deadline]')!;
    expect(deadline.textContent!.trim()).toBe('◷ Denied automatically in less than a minute');
    expect(panel().shadowRoot!.textContent).not.toContain(prompt.expiresAt);
    await expect(duplicate).resolves.toMatchObject({ approved: false, superseded: true });
    action('Review request').click(); await flush();
    const staleButton = host.shadowRoot!.querySelector<JfButton>('[data-testid="authorization-approve"]')!;
    const staleActivate = staleButton.onActivate!;
    const next = requestAuthorization({ ...prompt, pendingId: 'two' }); await flush();
    await activate(staleButton); await expect(decision).resolves.toMatchObject({ approved: true });
    staleActivate(); await flush(); expect(host.active?.kind === 'authorize' && host.active.prompt.pendingId).toBe('two');
    withdrawAuthorization('two'); await expect(next).resolves.toMatchObject({ approved: false, superseded: true }); await flush();
    expect(panel().model!.sections.needs.filter(r => r.id.startsWith('authorization:'))).toHaveLength(0);
  });
});
