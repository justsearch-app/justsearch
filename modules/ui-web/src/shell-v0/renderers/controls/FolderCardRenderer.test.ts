// @vitest-environment happy-dom

/**
 * 569 §14 — the Library folder-card renderer projects the indexed-folder cards at bespoke quality
 * through the engine, and emits a Remove INTENT the surface handles (the gated op stays surface-owned).
 */
import { describe, it, expect, beforeEach } from 'vitest';
import './FolderCardRenderer.js';
import type { FolderCardRenderer } from './FolderCardRenderer.js';
import { getXUiRendererTag } from './XUiRendererControl.js';

beforeEach(() => {
  document.body.innerHTML = '';
});

function mount(data: unknown): FolderCardRenderer {
  const el = document.createElement('jf-folder-card') as FolderCardRenderer;
  document.body.appendChild(el);
  el.data = data as FolderCardRenderer['data'];
  el.visible = true;
  el.enabled = true;
  el.uischema = { type: 'Control' };
  el.onChange = () => {};
  return el;
}

describe('<jf-folder-card>', () => {
  it('registers the folder-card hint and renders one card per folder', async () => {
    expect(getXUiRendererTag('folder-card')).toBe('jf-folder-card');
    const el = mount([
      { pathHash: 'h1', displayPath: '/home/docs', status: 'indexed', metaText: 'Docs · 12 files' },
      { pathHash: 'h2', displayPath: '/home/code', status: 'pending', metaText: 'Code · count pending' },
    ]);
    await el.updateComplete;
    const cards = el.shadowRoot?.querySelectorAll('.card');
    expect(cards?.length).toBe(2);
    // The displayPath is the title-bearing span (the first span is the status icon).
    expect(el.shadowRoot?.querySelector('.card-path span[title]')?.textContent).toContain('/home/docs');
    expect(el.shadowRoot?.querySelector('.card-meta')?.textContent).toContain('Docs · 12 files');
    el.remove();
  });

  it('shows an empty state for no folders', async () => {
    const el = mount([]);
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.empty')?.textContent).toMatch(/No folders/);
    el.remove();
  });

  it('emits jf-folder-card-remove with the pathHash when Remove is activated (intent, not the op)', async () => {
    const el = mount([{ pathHash: 'h1', displayPath: '/x', status: 'indexed', metaText: 'm' }]);
    await el.updateComplete;
    el.shadowRoot!.querySelector<HTMLButtonElement>('.folder-toggle')!.click();
    await el.updateComplete;
    const events: string[] = [];
    document.addEventListener('jf-folder-card-remove', (e) => {
      events.push((e as CustomEvent<{ pathHash: string }>).detail.pathHash);
    });
    const btn = el.shadowRoot?.querySelector('.remove-action') as HTMLElement & {
      onActivate?: () => void;
    };
    btn?.onActivate?.();
    expect(events).toEqual(['h1']);
    el.remove();
  });
});
