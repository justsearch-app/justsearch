// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
// FA2 — no Settings section shows internal design references as text, at any audience: every
// section is rendered with developer tools on, and its words (text, tooltips, accessible names,
// placeholders) are read through every nested shadow root.
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SettingsSurface.js';
import type { SettingsSurface } from './SettingsSurface.js';
import { allCategories } from './settingsRegister.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __resetForTest as resetCatalog, __seedForTest as seedCatalog } from '../../api/registry/SurfaceCatalogClient.js';
import { __resetUserStateForTest, mutateDocument } from '../state/UserStateDocument.js';
import { setViewerAudience } from '../state/viewerAudienceState.js';

/** Design-document references: "Tempdoc 543 §14.3", "§13.6 substrate", "slice 486", "ref-01", "round-15 F1", "#758". */
const INTERNAL = [/\btempdoc\b/i, /§/, /\bslice\s+\d/i, /\bref-\d+\b/i, /\bround[- ]\d+\b/i, /(?:^|[\s(])#\d{3,}\b/];

/** Every word a reader can meet in `root`: text, tooltips, accessible names and placeholders. */
function words(root: ParentNode): string[] {
  const out: string[] = [];
  const walk = (node: ParentNode) => {
    for (const el of node.querySelectorAll('*')) {
      for (const attribute of ['title', 'aria-label', 'aria-description', 'placeholder', 'label', 'alt']) {
        const value = el.getAttribute(attribute);
        if (value) out.push(value);
      }
      if (el.shadowRoot) walk(el.shadowRoot);
    }
    for (const child of node.childNodes) if (child.nodeType === 3 && child.textContent?.trim()) out.push(child.textContent.trim());
    for (const el of node.querySelectorAll('*')) {
      for (const child of el.childNodes) if (child.nodeType === 3 && child.textContent?.trim()) out.push(child.textContent.trim());
    }
  };
  walk(root);
  return out;
}

beforeEach(() => {
  __resetUserStateForTest(); resetCatalog();
  seedCatalog({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [] });
});
afterEach(() => { document.body.replaceChildren(); resetCatalog(); __resetUserStateForTest(); vi.restoreAllMocks(); });

it.each(['USER', 'OPERATOR', 'DEVELOPER'] as const)('FA2 no section shows internal design references to the %s audience', async audience => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response('{}'));
  setViewerAudience(audience);
  mutateDocument(doc => ({ ...doc, showDeveloperTools: true }));
  const el = document.createElement('jf-settings-surface') as SettingsSurface;
  el.host_ = createMockHostApi({ data: { fetch: async () => new Response('{"ui":{}}') } });
  document.body.append(el);
  const found: string[] = [];
  for (const category of allCategories()) {
    el.activeCategory = category.id;
    for (let i = 0; i < 3; i++) { await el.updateComplete; await new Promise(r => setTimeout(r, 0)); }
    for (const text of words(el.shadowRoot!)) {
      if (INTERNAL.some(pattern => pattern.test(text))) found.push(`${category.id}: ${text.replace(/\s+/g, ' ')}`);
    }
  }
  expect([...new Set(found)]).toEqual([]);
});
