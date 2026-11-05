// @vitest-environment happy-dom

/**
 * <jf-elicit-host> tests — 543-fwd elicit-form fix.
 *
 * Regression for the live-found defect: a schema-only elicit request (no
 * uischema) rendered an EMPTY form (no inputs), because <jf-form> got an
 * undefined uischema → no renderer matched. The host now generates a default
 * VerticalLayout uischema so the registry renders a control per property.
 * This drives the REAL host + REAL renderer registry (single module instance).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './ElicitHost.js';
// Side-effect imports that register the renderers (mirrors app boot in index.ts).
import '../renderers/controls/TextControl.js';
import '../renderers/layouts/VerticalLayout.js';
import {
  elicit,
  resolveElicit,
  listPendingElicits,
  __resetElicitForTest,
} from '../substrates/elicit/index.js';

let host: HTMLElement & { open?: boolean };

beforeEach(() => {
  __resetElicitForTest();
  host = document.createElement('jf-elicit-host');
  document.body.appendChild(host);
});

afterEach(() => {
  host.remove();
  __resetElicitForTest();
});

async function settle(): Promise<void> {
  // Let the host + nested jf-form + child control renderers all render.
  for (let i = 0; i < 6; i++) {
    await Promise.resolve();
    const el = host as unknown as { updateComplete?: Promise<unknown> };
    if (el.updateComplete) await el.updateComplete;
  }
}

/**
 * 574 B (remediation) — submit/cancel are <jf-button>; the action fires from the native <button>
 * inside the composed <jf-control>, not a host click. Awaits both render passes, then clicks.
 */
async function activateJfButton(el: Element | null | undefined): Promise<void> {
  if (!el) throw new Error('activateJfButton: element not found');
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const control = el.shadowRoot!.querySelector('jf-control')!;
  await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (control.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
}

function deepFind(sel: string, root: Document | ShadowRoot | Element = document, acc: Element[] = []): Element[] {
  root.querySelectorAll('*').forEach((e) => {
    if (e.matches(sel)) acc.push(e);
    if ((e as Element & { shadowRoot?: ShadowRoot }).shadowRoot) {
      deepFind(sel, (e as Element & { shadowRoot: ShadowRoot }).shadowRoot, acc);
    }
  });
  return acc;
}

describe('<jf-elicit-host> — schema-only request renders inputs (543-fwd)', () => {
  it('generates a default uischema so a string field renders a text input', async () => {
    // No uischema — the exact shape the macro save / parameterize prompts use.
    void elicit({
      title: 'Save as macro',
      schema: {
        type: 'object',
        properties: { label: { type: 'string', title: 'Macro label' } },
        required: ['label'],
      },
    });
    await settle();
    expect(listPendingElicits()).toHaveLength(1);
    // The decisive assertion: a real input control rendered for the string field.
    const inputs = deepFind('input, textarea', host.shadowRoot ?? document).filter(
      (i) => (i as HTMLInputElement).type !== 'checkbox',
    );
    expect(inputs.length).toBeGreaterThan(0);
  });

  it('respects an explicitly-provided uischema (does not override it)', async () => {
    void elicit({
      title: 'Custom',
      schema: { type: 'object', properties: { a: { type: 'string' } } },
      uischema: { type: 'VerticalLayout', elements: [{ type: 'Control', scope: '#/properties/a' }] } as never,
    });
    await settle();
    const inputs = deepFind('input, textarea', host.shadowRoot ?? document).filter(
      (i) => (i as HTMLInputElement).type !== 'checkbox',
    );
    expect(inputs.length).toBeGreaterThan(0);
  });

  it('submitting resolves the elicit promise with the entered data', async () => {
    const p = elicit({
      title: 'Save as macro',
      schema: { type: 'object', properties: { label: { type: 'string' } } },
    });
    await settle();
    // Resolve through the substrate (the submit button calls this); proves the
    // round-trip the macro author depends on.
    resolveElicit(listPendingElicits()[0]!.id, { label: 'My macro' });
    expect(await p).toEqual({ label: 'My macro' });
  });

  it('543-fwd Fix B — a non-cancellable prompt blocks the native Escape (cancel) and stays pending', async () => {
    void elicit({
      title: 'Required',
      schema: { type: 'object', properties: { a: { type: 'string' } } },
      cancelLabel: null,
    });
    await settle();
    expect(listPendingElicits()).toHaveLength(1);
    const dlg = host.shadowRoot?.querySelector('dialog') as HTMLDialogElement;
    const cancelEvt = new Event('cancel', { cancelable: true });
    dlg.dispatchEvent(cancelEvt);
    expect(cancelEvt.defaultPrevented).toBe(true); // close blocked
    expect(listPendingElicits()).toHaveLength(1); // still required
  });

  it('543-fwd residue #5 — restores focus to the pre-open element when the queue drains', async () => {
    // Assert the wiring (pre-open element's focus() called when the queue drains)
    // rather than the happy-dom-flaky document.activeElement.
    const sentinel = document.createElement('button');
    document.body.appendChild(sentinel);
    sentinel.focus(); // active when the dialog opens → captured
    const focusSpy = vi.spyOn(sentinel, 'focus');
    const p = elicit({
      title: 'Save as macro',
      schema: { type: 'object', properties: { label: { type: 'string' } } },
    });
    await settle();
    expect(focusSpy).not.toHaveBeenCalled(); // not restored while pending
    // Submit via the dialog's own button (the production path: handleSubmit
    // resolves AND refreshes the host so it closes); a bare resolveElicit()
    // would clear the substrate without notifying the host.
    await activateJfButton(host.shadowRoot?.querySelector('jf-button.submit'));
    await p;
    await settle();
    expect(listPendingElicits()).toHaveLength(0);
    expect(focusSpy).toHaveBeenCalled(); // restored when the queue drains
    sentinel.remove();
  });

  it('543-fwd Fix B — Escape resolves a cancellable prompt (close-event-independent)', async () => {
    void elicit({
      title: 'Optional',
      schema: { type: 'object', properties: { a: { type: 'string' } } },
    });
    await settle();
    expect(listPendingElicits()).toHaveLength(1);
    const dlg = host.shadowRoot?.querySelector('dialog') as HTMLDialogElement;
    const cancelEvt = new Event('cancel', { cancelable: true });
    dlg.dispatchEvent(cancelEvt);
    expect(cancelEvt.defaultPrevented).toBe(false); // cancellable: close not blocked
    dlg.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await settle();
    expect(listPendingElicits()).toHaveLength(0); // dequeued via handleCancel
  });

  it.each(['stays mounted', 'is re-attached after navigating away', 'is replaced by a new host after navigating away'] as const)(
    'C2 two queued required prompts each answer on a deliberate Submit when the host %s; neither caller hangs', async (how) => {
      const schema = { type: 'object', properties: { a: { type: 'string' } } } as const;
      const first = elicit({ title: 'First', schema, cancelLabel: null, initialData: { a: 'one' } });
      const second = elicit({ title: 'Second', schema, cancelLabel: null, initialData: { a: 'two' } });
      await settle();
      if (how !== 'stays mounted') {
        host.remove(); await settle();
        if (how !== 'is re-attached after navigating away') host = document.createElement('jf-elicit-host');
        document.body.appendChild(host); await settle();
      }
      const title = () => host.shadowRoot?.querySelector('h2')?.textContent;
      expect(title()).toBe('First');
      await activateJfButton(host.shadowRoot?.querySelector('jf-button.submit'));
      expect(await first).toEqual({ a: 'one' });
      await settle();
      expect(title(), 'the second prompt is shown next').toBe('Second');
      await activateJfButton(host.shadowRoot?.querySelector('jf-button.submit'));
      await settle();
      const answered = await Promise.race([second, new Promise(resolve => setTimeout(() => resolve('still waiting'), 100))]);
      expect(answered, 'its Submit answers the second prompt').toEqual({ a: 'two' });
      expect(listPendingElicits()).toHaveLength(0);
      expect(title(), 'nothing is left on screen').toBeUndefined();
    });
});
