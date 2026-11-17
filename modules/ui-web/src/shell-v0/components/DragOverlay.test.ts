// @vitest-environment happy-dom

import { describe, expect, it, beforeEach } from 'vitest';
import './DragOverlay.js';
import type { DragOverlay } from './DragOverlay.js';

function make(): DragOverlay {
  const el = document.createElement('jf-drag-overlay') as DragOverlay;
  document.body.appendChild(el);
  return el;
}

describe('DragOverlay (slice 459)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('renders nothing when inactive', async () => {
    const el = make();
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.overlay')).toBeNull();
  });

  it('renders overlay when active', async () => {
    const el = make();
    el.active = true;
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.overlay')).not.toBeNull();
  });

  it('drag-kind=folder shows "Drop folder to index"', async () => {
    const el = make();
    el.active = true;
    el.dragKind = 'folder';
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('h3')?.textContent).toBe('Drop folder to add it to your library');
  });

  it('drag-kind=file shows "Drop files here"', async () => {
    const el = make();
    el.active = true;
    el.dragKind = 'file';
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('h3')?.textContent).toBe('Drop files here');
  });

  it('drag-kind=unknown shows generic copy', async () => {
    const el = make();
    el.active = true;
    el.dragKind = 'unknown';
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('h3')?.textContent).toBe('Drop to add to your library');
  });
});
