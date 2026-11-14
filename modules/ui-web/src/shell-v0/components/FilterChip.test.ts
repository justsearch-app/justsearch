// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest';
import './FilterChip.js';
import type { FilterChip } from './FilterChip.js';

async function mount(setup: (el: FilterChip) => void): Promise<FilterChip> {
  const el = document.createElement('jf-filter-chip') as FilterChip;
  setup(el);
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('jf-filter-chip (574 §23.B atom)', () => {
  it('renders a keyboard-operable <button> reflecting aria-pressed from `active`', async () => {
    const off = await mount((c) => (c.active = false));
    expect(off.shadowRoot!.querySelector('button')!.getAttribute('aria-pressed')).toBe('false');
    const on = await mount((c) => (c.active = true));
    expect(on.shadowRoot!.querySelector('button')!.getAttribute('aria-pressed')).toBe('true');
  });

  it('projects the active tint from the tone (statusTone authority); defaults to info', async () => {
    const def = await mount((c) => (c.active = true));
    expect(def.shadowRoot!.querySelector('button')!.getAttribute('style')).toContain(
      'var(--text-secondary)', // info default
    );
    const err = await mount((c) => {
      c.active = true;
      c.tone = 'error';
    });
    expect(err.shadowRoot!.querySelector('button')!.getAttribute('style')).toContain(
      'var(--accent-danger)',
    );
  });

  it('slots its content (icon / label / count) into the button', async () => {
    const el = await mount(() => {});
    el.textContent = 'Errors';
    await el.updateComplete;
    expect(el.shadowRoot!.querySelector('button slot')).toBeTruthy();
    expect(el.textContent).toContain('Errors');
  });
});
