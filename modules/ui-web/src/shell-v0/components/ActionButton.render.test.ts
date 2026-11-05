// @vitest-environment happy-dom

/**
 * Render tests for ActionButton. Covers:
 *  - LOW risk: click immediately fires `action-invoke`.
 *  - MEDIUM risk: click → the in-place band (consequence, verb, Keep it)
 *    → the verb fires; Keep it doesn't (DE1).
 *  - HIGH risk: click → typed-confirm prompt → typing the operation
 *    id enables the verb button; mismatched typing keeps it disabled.
 *  - disabled / pending suppress invoke.
 */

import { describe, expect, it } from 'vitest';
import './ActionButton.js';
import './ConfirmBand.js';
import { deepActiveElement } from '../utils/keyboardHandler.js';
import type { ActionButton, ActionInvokeEventDetail } from './ActionButton.js';

async function mountBtn(
  props: Partial<ActionButton>,
): Promise<ActionButton> {
  const el = document.createElement('jf-action-button') as ActionButton;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

function captureInvoke(el: ActionButton): ActionInvokeEventDetail[] {
  const events: ActionInvokeEventDetail[] = [];
  el.addEventListener('action-invoke', ((e: CustomEvent) => {
    events.push(e.detail as ActionInvokeEventDetail);
  }) as EventListener);
  return events;
}

describe('ActionButton — LOW risk', () => {
  it('fires action-invoke immediately on click', async () => {
    const el = await mountBtn({
      operationId: 'cache.refresh',
      label: 'Refresh',
      risk: 'LOW',
    });
    const events = captureInvoke(el);
    const btn = el.shadowRoot?.querySelector('button.invoke') as HTMLButtonElement;
    btn.click();
    expect(events).toEqual([{ operationId: 'cache.refresh', risk: 'LOW' }]);
    el.remove();
  });
});

/** Whether focus (followed through shadow roots) is on `host` or inside its shadow tree. */
function focusWithin(host: Element): boolean {
  let node: Node | null = deepActiveElement();
  while (node) {
    if (node === host) return true;
    const root = node.getRootNode();
    node = root instanceof ShadowRoot ? root.host : null;
  }
  return false;
}

type BandButton = HTMLElement & { onActivate: (() => void) | null; disabled: boolean };
function band(el: ActionButton) {
  return el.shadowRoot?.querySelector('jf-confirm-band') ?? null;
}
function bandButton(el: ActionButton, which: 'confirm' | 'keep'): BandButton {
  return band(el)!.shadowRoot!.querySelector<BandButton>(`jf-button.${which}`)!;
}

describe('ActionButton — MEDIUM risk (DE1 in-place band)', () => {
  it('asks in place with the consequence and the verb, never "Are you sure?", and fires once on the verb', async () => {
    const el = await mountBtn({
      operationId: 'core.clear-failed-jobs',
      label: 'Clear Failed Jobs',
      risk: 'MEDIUM',
    });
    const events = captureInvoke(el);
    const btn = el.shadowRoot?.querySelector('button.invoke') as HTMLButtonElement;
    btn.click();
    await el.updateComplete;
    expect(events).toEqual([]);
    expect(el.shadowRoot?.textContent).not.toContain('Are you sure?');
    const b = band(el)!;
    await b.updateComplete;
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(b.consequence).toContain('Failed files leave the failed list');
    expect(b.consequence).toContain("can't be undone");
    expect(bandButton(el, 'confirm').textContent).toContain('Clear Failed Jobs');
    expect(focusWithin(bandButton(el, 'keep'))).toBe(true);
    bandButton(el, 'confirm').onActivate!();
    bandButton(el, 'confirm')?.onActivate?.();
    expect(events).toEqual([{ operationId: 'core.clear-failed-jobs', risk: 'MEDIUM' }]);
    await el.updateComplete;
    expect(band(el)).toBeNull();
    el.remove();
  });

  it('an operation without its own sentence still says it runs now with no undo', async () => {
    const el = await mountBtn({ operationId: 'index.flush', label: 'Flush', risk: 'MEDIUM' });
    const events = captureInvoke(el);
    (el.shadowRoot?.querySelector('button.invoke') as HTMLButtonElement).click();
    await el.updateComplete;
    expect(events).toEqual([]);
    expect(band(el)!.consequence).toBe("Flush runs now. It can't be undone from here.");
    await band(el)!.updateComplete;
    bandButton(el, 'confirm').onActivate!();
    expect(events).toEqual([{ operationId: 'index.flush', risk: 'MEDIUM' }]);
    await el.updateComplete;
    expect(band(el)).toBeNull();
    el.remove();
  });

  it('Keep it returns to idle without firing and returns focus to the button that asked', async () => {
    const el = await mountBtn({
      operationId: 'index.flush',
      label: 'Flush',
      risk: 'MEDIUM',
    });
    const events = captureInvoke(el);
    const btn = el.shadowRoot?.querySelector('button.invoke') as HTMLButtonElement;
    btn.focus();
    btn.click();
    await el.updateComplete;
    await band(el)!.updateComplete;
    bandButton(el, 'keep').onActivate!();
    await el.updateComplete;
    expect(events).toEqual([]);
    expect(band(el)).toBeNull();
    expect(el.shadowRoot?.activeElement).toBe(el.shadowRoot?.querySelector('button.invoke'));
    el.remove();
  });

  it('Escape in the band cancels it without firing', async () => {
    const el = await mountBtn({ operationId: 'index.flush', label: 'Flush', risk: 'MEDIUM' });
    const events = captureInvoke(el);
    (el.shadowRoot?.querySelector('button.invoke') as HTMLButtonElement).click();
    await el.updateComplete;
    await band(el)!.updateComplete;
    bandButton(el, 'keep').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true }));
    await el.updateComplete;
    expect(events).toEqual([]);
    expect(band(el)).toBeNull();
    el.remove();
  });
});

describe('ActionButton — HIGH risk', () => {
  it('requires typing the operationId to enable confirm', async () => {
    const el = await mountBtn({
      operationId: 'index.reset',
      label: 'Reset',
      risk: 'HIGH',
    });
    const events = captureInvoke(el);
    const btn = el.shadowRoot?.querySelector('button.invoke') as HTMLButtonElement;
    btn.click();
    await el.updateComplete;

    const typeInput = el.shadowRoot?.querySelector(
      'input.typed-confirm',
    ) as HTMLInputElement;
    expect(typeInput).not.toBeNull();

    // The typed form's answer is the verb (the label), never "Confirm" (§8 words).
    expect(Array.from(el.shadowRoot?.querySelectorAll('button') ?? []).map((b) => b.textContent?.trim()))
      .not.toContain('Confirm');
    const findConfirm = (): HTMLButtonElement | undefined =>
      el.shadowRoot?.querySelector('.confirm-row button.invoke[data-risk]') as
        | HTMLButtonElement
        | undefined;
    expect(findConfirm()?.textContent?.trim()).toBe('Reset');

    expect(findConfirm()?.disabled).toBe(true);

    // Type the wrong text
    typeInput.value = 'wrong';
    typeInput.dispatchEvent(new Event('input'));
    await el.updateComplete;
    expect(findConfirm()?.disabled).toBe(true);

    // Type the right text
    typeInput.value = 'index.reset';
    typeInput.dispatchEvent(new Event('input'));
    await el.updateComplete;
    expect(findConfirm()?.disabled).toBe(false);

    findConfirm()?.click();
    expect(events).toEqual([{ operationId: 'index.reset', risk: 'HIGH' }]);
    el.remove();
  });

  it('respects requireConfirmText override over operationId', async () => {
    const el = await mountBtn({
      operationId: 'op.x',
      label: 'X',
      risk: 'HIGH',
      requireConfirmText: 'I CONFIRM',
    });
    const events = captureInvoke(el);
    (
      el.shadowRoot?.querySelector('button.invoke') as HTMLButtonElement
    ).click();
    await el.updateComplete;
    const typeInput = el.shadowRoot?.querySelector(
      'input.typed-confirm',
    ) as HTMLInputElement;
    typeInput.value = 'I CONFIRM';
    typeInput.dispatchEvent(new Event('input'));
    await el.updateComplete;
    const confirm = el.shadowRoot?.querySelector('.confirm-row button.invoke[data-risk]') as
      | HTMLButtonElement
      | undefined;
    confirm?.click();
    expect(events).toHaveLength(1);
    el.remove();
  });
});

describe('ActionButton — disabled / pending', () => {
  it('does not fire when disabled', async () => {
    const el = await mountBtn({
      operationId: 'op.x',
      label: 'X',
      risk: 'LOW',
      disabled: true,
    });
    const events = captureInvoke(el);
    const btn = el.shadowRoot?.querySelector('button.invoke') as HTMLButtonElement;
    btn.click();
    expect(events).toEqual([]);
    el.remove();
  });

  it('does not fire when pending', async () => {
    const el = await mountBtn({
      operationId: 'op.x',
      label: 'X',
      risk: 'LOW',
      pending: true,
    });
    const events = captureInvoke(el);
    const btn = el.shadowRoot?.querySelector('button.invoke') as HTMLButtonElement;
    btn.click();
    expect(events).toEqual([]);
    el.remove();
  });
});
