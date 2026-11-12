// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * jf-button runs the action it was last given. A caller that re-renders the same button (same label,
 * same look) with a new action — Activity's details moving from one record to another — must have a
 * click run the new action, not the one the button was first drawn with.
 */
import { afterEach, expect, it, vi } from 'vitest';
import { html, render } from 'lit';
import './Button.js';
import type { JfButton } from './Button.js';
import type { Control } from './Control.js';

afterEach(() => { document.body.innerHTML = ''; });

async function click(button: JfButton): Promise<void> {
  await button.updateComplete;
  const control = button.shadowRoot!.querySelector('jf-control') as Control;
  await control.updateComplete;
  control.shadowRoot!.querySelector('button')!.click();
}

it('a click runs the action the button was last rendered with, also when nothing else about it changed', async () => {
  const host = document.createElement('div');
  document.body.append(host);
  const first = vi.fn();
  const second = vi.fn();
  const draw = (action: () => void) => render(html`<jf-button size="sm" label="Restore to this point"
    .onActivate=${action}>Restore to this point</jf-button>`, host);
  draw(first);
  const button = host.querySelector('jf-button') as JfButton;
  await button.updateComplete;
  draw(second);
  await click(button);
  expect(second, 'the action given last runs').toHaveBeenCalledTimes(1);
  expect(first, 'the earlier action does not run').not.toHaveBeenCalled();
});

it('an action set on the element directly also reaches the click', async () => {
  const button = document.createElement('jf-button') as JfButton;
  button.label = 'Ask';
  const first = vi.fn();
  button.onActivate = first;
  document.body.append(button);
  await click(button);
  const second = vi.fn();
  button.onActivate = second;
  await click(button);
  expect(first).toHaveBeenCalledTimes(1);
  expect(second).toHaveBeenCalledTimes(1);
});
