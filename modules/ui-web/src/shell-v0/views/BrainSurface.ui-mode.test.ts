// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './BrainSurface.js';
import { __resetUiModeForTest, getUiMode, setUiMode } from '../state/uiModeState.js';

interface BrainHost extends HTMLElement { apiBase: string; updateComplete: Promise<boolean> }
beforeEach(() => {
  __resetUiModeForTest();
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ ui: { mode: 'simple' }, llm: {} }))));
});
afterEach(() => { document.body.innerHTML = ''; vi.unstubAllGlobals(); __resetUiModeForTest(); });
async function mount(): Promise<BrainHost> {
  const el = document.createElement('jf-brain-surface') as BrainHost;
  el.apiBase = ''; document.body.appendChild(el);
  await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete;
  return el;
}
describe('AI Brain independent of global detail level', () => {
  it('renders the same page under either global ui.mode', async () => {
    const el = await mount();
    const before = el.shadowRoot!.innerHTML;
    expect(el.shadowRoot!.querySelector('.brain-path')).not.toBeNull();
    expect(el.shadowRoot!.querySelector('.mode-toggle')).toBeNull();
    setUiMode('advanced'); await el.updateComplete;
    expect(el.shadowRoot!.innerHTML).toBe(before);
  });
  it.each(['simple', 'advanced'] as const)('opening Advanced never writes ui.mode (%s)', async mode => {
    setUiMode(mode);
    const el = await mount();
    const advanced = el.shadowRoot!.querySelector<HTMLButtonElement>('.brain-links button')!;
    advanced.click(); await el.updateComplete;
    expect(advanced.getAttribute('aria-expanded')).toBe('true');
    expect(el.shadowRoot!.querySelector('.brain-advanced')).not.toBeNull();
    expect(getUiMode()).toBe(mode);
    expect(vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST')).toEqual([]);
  });
  it('does not let its settings refresh overwrite a newer shared choice', async () => {
    setUiMode('advanced'); await mount(); expect(getUiMode()).toBe('advanced');
  });
});
