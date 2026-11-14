// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './HelpSurface.js';
import * as presentation from '../state/presentationRuntime.js';
import { CORE_DECLARED, HELP_REFERENCE_REGION } from '../themes/builtinPresentations.js';
import { __resetUserStateForTest, getWalkthroughProgress, markWalkthroughStepComplete, setWalkthroughActiveStep } from '../state/UserStateDocument.js';
import { __resetWalkthroughsForTest, registerWalkthrough } from '../commands/WalkthroughRegistry.js';
import { makeCoreProvenance } from '../primitives/provenance.js';
import type { WalkthroughCard } from '../components/WalkthroughCard.js';
import type { HelpSurface } from './HelpSurface.js';

type Updated = HTMLElement & { updateComplete: Promise<unknown> };
beforeEach(() => {
  localStorage.clear(); __resetUserStateForTest(); __resetWalkthroughsForTest();
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}')));
  registerWalkthrough({ id: 'plugin.tour', title: 'Plugin tour', source: 'plugin', priority: 0,
    provenance: makeCoreProvenance(), steps: [{ id: 'one', title: 'One', body: 'First step' }, { id: 'two', title: 'Two', body: 'Second step' }] });
});
afterEach(() => { document.body.replaceChildren(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
async function mount(declared: boolean): Promise<WalkthroughCard> {
  vi.spyOn(presentation, 'activeBodyFor').mockReturnValue(declared ? CORE_DECLARED.body![HELP_REFERENCE_REGION] : undefined);
  const help = document.createElement('jf-help-surface') as HelpSurface;
  document.body.append(help); await help.updateComplete;
  expect(help.shadowRoot!.querySelector('jf-declared-surface') !== null).toBe(declared);
  const tour = help.shadowRoot!.querySelector<WalkthroughCard>('jf-walkthrough-card');
  expect(tour, 'Help exposes the saved plugin tour').not.toBeNull();
  await tour!.updateComplete; return tour!;
}
async function activate(tour: WalkthroughCard, label: string): Promise<void> {
  const action = tour.shadowRoot!.querySelector<Updated>(`jf-button[label="${label}"]`)!;
  expect(action).not.toBeNull(); await action.updateComplete;
  const control = action.shadowRoot!.querySelector<Updated>('jf-control')!; await control.updateComplete;
  control.shadowRoot!.querySelector<HTMLButtonElement>('button')!.click(); await tour.updateComplete;
}
for (const declared of [false, true]) {
  it(`S4-21 resumes the saved plugin step and completes from Help (declared=${declared})`, async () => {
    markWalkthroughStepComplete('plugin.tour', 'one'); setWalkthroughActiveStep('plugin.tour', 1);
    const tour = await mount(declared);
    expect(tour.shadowRoot!.textContent).toContain('Two');
    await activate(tour, 'Done');
    expect(getWalkthroughProgress('plugin.tour')).toMatchObject({ activeStepIndex: 2, completedStepIds: ['one', 'two'] });
  });
  it(`S4-21 Next and Dismiss keep their saved progress in Help (declared=${declared})`, async () => {
    const tour = await mount(declared); await activate(tour, 'Next');
    expect(getWalkthroughProgress('plugin.tour')).toMatchObject({ activeStepIndex: 1, completedStepIds: ['one'] });
    await activate(tour, 'Dismiss');
    expect(getWalkthroughProgress('plugin.tour')).toMatchObject({ activeStepIndex: 1, completedStepIds: ['one'], dismissed: true });
  });
}
