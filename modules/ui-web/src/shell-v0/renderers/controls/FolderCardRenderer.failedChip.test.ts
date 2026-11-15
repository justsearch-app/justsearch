// @vitest-environment happy-dom
//
// Tempdoc 914 D3, review finding S2-2 — the "last known" qualifier must be VISIBLE.
//
// The first cut wrote `data-last-known` and put the wording in the button's `aria-label` only: no
// stylesheet consumed the attribute and no visible text changed, so a sighted user saw a chip
// asserting "2 failed" as a present fact with nothing to say the number was carried. The chip now
// says it on screen ("2 failed · last known"), renders in the muted-italic treatment StatusDeck
// gives a last-known value, and carries the sentence on hover.
//
// The `label` it sets always STARTS with the visible text (WCAG 2.5.3 "label in name" asks for
// containment, not equality). Dropping the label instead was tried and rejected: jf-control resolves
// its name from operationId/label/its own textContent, and this text is slotted through two shadow
// roots, so a label-less chip makes the primitive log "[jf-control] no accessible name".

import { describe, expect, it } from 'vitest';
import './FolderCardRenderer.js';

interface Card {
  pathHash: string;
  displayPath: string;
  status: string;
  metaText: string;
  expanded?: boolean;
  failed: number;
  failedLastKnown?: boolean;
}

async function mount(card: Card): Promise<Element> {
  const el = document.createElement('jf-folder-card');
  (el as unknown as { data: Card[] }).data = [card];
  (el as unknown as { visible: boolean }).visible = true;
  document.body.appendChild(el);
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  return el;
}

const base: Card = {
  expanded: true,
  pathHash: 'h',
  displayPath: 'reports',
  status: 'pending',
  metaText: 'default · Reading · 2 remaining',
  failed: 2,
};

describe('FolderCardRenderer — the failed chip marks a carried count visibly (914 D3 / S2-2)', () => {
  it('a LAST-KNOWN count says so in the visible text, is muted+italic, and explains on hover', async () => {
    const el = await mount({ ...base, failedLastKnown: true });
    try {
      const chip = el.shadowRoot?.querySelector('.failed-chip') as HTMLElement;
      expect(chip, 'the chip must render').toBeTruthy();
      // VISIBLE, not aria-only — this is the whole finding.
      expect(chip.textContent?.replace(/\s+/g, ' ').trim()).toContain('2 failed · last known');
      expect(chip.getAttribute('data-last-known')).toBe('true');
      // The muted-italic treatment applies (the [data-last-known] rule won).
      expect(getComputedStyle(chip).fontStyle).toBe('italic');
      expect(chip.getAttribute('title')).toContain('failed as of the last settled check');
      // WCAG 2.5.3 by construction: the accessible name CONTAINS the visible text.
      const label = chip.getAttribute('label') ?? '';
      expect(label.startsWith('2 failed · last known')).toBe(true);
    } finally {
      el.remove();
    }
  });

  it('a count this poll reported carries NO qualifier, no muting and no title', async () => {
    const el = await mount({ ...base, metaText: 'default · 200 files' });
    try {
      const chip = el.shadowRoot?.querySelector('.failed-chip') as HTMLElement;
      const text = chip.textContent?.replace(/\s+/g, ' ').trim() ?? '';
      expect(text).toContain('2 failed');
      expect(text).not.toContain('last known');
      expect(chip.getAttribute('data-last-known')).toBe('false');
      expect(getComputedStyle(chip).fontStyle).not.toBe('italic');
      expect(chip.getAttribute('title')).toBeNull();
      expect((chip.getAttribute('label') ?? '').startsWith('2 failed')).toBe(true);
    } finally {
      el.remove();
    }
  });
});
