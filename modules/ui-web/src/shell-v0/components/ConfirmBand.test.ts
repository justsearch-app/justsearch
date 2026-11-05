// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * DE1 — the in-place confirmation band (design system §7, ref-06): one consequence sentence, a
 * danger button named with the verb and object, "Keep it"; focus to "Keep it"; Escape cancels and
 * returns focus to the opener; one answer runs at most once (double-click, key repeat).
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import './ConfirmBand.js';
import type { ConfirmBand } from './ConfirmBand.js';

type Btn = HTMLElement & { onActivate: (() => void) | null; disabled: boolean };

async function open(opener: HTMLElement | null = null): Promise<{ band: ConfirmBand; confirm: ReturnType<typeof vi.fn>; cancel: ReturnType<typeof vi.fn> }> {
  const band = document.createElement('jf-confirm-band');
  band.heading = 'Remove Notes from your library?';
  band.consequence = 'Its 1,420 files leave search. Your files on disk stay where they are, and adding Notes again reads it from scratch.';
  band.actionLabel = 'Remove Notes';
  const confirm = vi.fn();
  const cancel = vi.fn(() => band.remove());
  band.addEventListener('confirm-band-confirm', confirm);
  band.addEventListener('confirm-band-cancel', cancel);
  opener?.focus();
  document.body.append(band);
  await band.updateComplete;
  await band.focusKeep();
  return { band, confirm, cancel };
}
const part = (band: ConfirmBand, selector: string): Btn => band.shadowRoot!.querySelector<Btn>(selector)!;

afterEach(() => { document.body.replaceChildren(); });

describe('ConfirmBand (DE1)', () => {
  it('says the consequence, names the verb and object, offers Keep it, and focuses Keep it', async () => {
    const { band } = await open();
    const text = band.shadowRoot!.textContent!.replace(/\s+/g, ' ');
    expect(text).toContain('Remove Notes from your library?');
    expect(text).toContain('Your files on disk stay where they are');
    expect(part(band, 'jf-button.confirm').textContent).toContain('Remove Notes');
    expect(part(band, 'jf-button.keep').textContent!.trim()).toBe('Keep it');
    expect(text).not.toMatch(/Are you sure|\bOK\b|\bConfirm\b/);
    expect(band.shadowRoot!.querySelector('[role="group"]')!.getAttribute('aria-describedby')).toBe('consequence');
    expect(band.shadowRoot!.activeElement).toBe(part(band, 'jf-button.keep'));
  });

  it('Escape cancels and returns focus to the control that opened it; it does not reach the window behind', async () => {
    const opener = document.createElement('button');
    opener.textContent = 'Remove from library';
    document.body.append(opener);
    const outer = vi.fn();
    document.addEventListener('keydown', outer);
    const { band, confirm, cancel } = await open(opener);
    expect(document.activeElement).not.toBe(opener);
    part(band, 'jf-button.keep').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true }));
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(confirm).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(opener);
    expect(outer).not.toHaveBeenCalled();
    document.removeEventListener('keydown', outer);
  });

  it('Keep it cancels once and returns focus to the opener', async () => {
    const opener = document.createElement('button');
    document.body.append(opener);
    const { band, confirm, cancel } = await open(opener);
    part(band, 'jf-button.keep').onActivate!();
    part(band, 'jf-button.keep').onActivate!();
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(confirm).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(opener);
  });

  it('a double-click on the danger button answers once, and no later answer runs', async () => {
    const { band, confirm, cancel } = await open();
    part(band, 'jf-button.confirm').onActivate!();
    part(band, 'jf-button.confirm').onActivate!();
    part(band, 'jf-button.keep').onActivate!();
    band.shadowRoot!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true }));
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(cancel).not.toHaveBeenCalled();
  });

  it('an auto-repeating Enter or Space (a key held since the opener) answers nothing', async () => {
    const { band } = await open();
    for (const key of ['Enter', ' ']) {
      const repeat = new KeyboardEvent('keydown', { key, repeat: true, bubbles: true, composed: true, cancelable: true });
      part(band, 'jf-button.keep').dispatchEvent(repeat);
      expect(repeat.defaultPrevented).toBe(true);
      const fresh = new KeyboardEvent('keydown', { key, bubbles: true, composed: true, cancelable: true });
      part(band, 'jf-button.keep').dispatchEvent(fresh);
      expect(fresh.defaultPrevented).toBe(false);
    }
  });

  it('while the host runs the action, neither answer is offered', async () => {
    const { band, confirm, cancel } = await open();
    band.busy = true;
    await band.updateComplete;
    expect(part(band, 'jf-button.confirm').disabled).toBe(true);
    expect(part(band, 'jf-button.keep').disabled).toBe(true);
    band.shadowRoot!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true }));
    expect(confirm).not.toHaveBeenCalled();
    expect(cancel).not.toHaveBeenCalled();
  });
});
