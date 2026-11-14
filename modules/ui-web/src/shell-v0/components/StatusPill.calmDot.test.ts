// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import { StatusPill } from './StatusPill.js';

afterEach(() => { document.body.replaceChildren(); vi.unstubAllGlobals(); });
async function mount() {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
  const pill = new StatusPill(); document.body.append(pill); await pill.updateComplete;
  return pill;
}
it.each(['Up to date', 'Keyword search ready', 'Getting started · 1 of 3', 'Getting started · 2 of 3', 'Getting started · 3 of 3'])('calm %s is only a dot, preserving its name and title', async label => {
  const pill = await mount();
  pill.status.model = { ...pill.status.model, pill: { kind: 'calm', label, stale: false } };
  pill.requestUpdate(); await pill.updateComplete;
  const trigger = pill.shadowRoot!.querySelector<HTMLButtonElement>('.trigger')!;
  expect(trigger.getAttribute('aria-label')).toBe(label);
  expect(trigger.title).toBe(label);
  expect(trigger.querySelector('.label')).toBeNull();
  expect(trigger.querySelector('.symbol')!.textContent).toBe('');
  expect(trigger.querySelector('[aria-label="Plugin status items"]')).toBeNull();
  trigger.click(); await pill.updateComplete;
  expect(trigger.getAttribute('aria-expanded')).toBe('true');
});
it.each([0, 50, 100])('working %i percent is visible alongside its ring', async percent => {
  const pill = await mount();
  pill.narrow = false;
  pill.status.model = { ...pill.status.model, pill: { kind: 'working', label: 'Installing AI', percent, stale: false } };
  pill.requestUpdate(); await pill.updateComplete;
  const trigger = pill.shadowRoot!.querySelector<HTMLButtonElement>('.trigger')!;
  expect(trigger.querySelector('.label')!.textContent).toBe(`Installing AI · ${percent}%`);
  expect(trigger.querySelector('.ring')!.getAttribute('data-percent')).toBe(String(percent));
});
