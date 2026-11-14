// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { Sv3Pane } from '../Sv3Pane.js';
import { DocumentPane } from '../../../components/documentPane/DocumentPane.js';
import { setShowDeveloperTools } from '../../../state/developerToolsState.js';
beforeEach(() => { setShowDeveloperTools(false); vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline test'))); });
afterEach(() => { document.body.replaceChildren(); vi.unstubAllGlobals(); });
it('S5-8 hides Show in folder in a browser', async () => {
  const pane = new Sv3Pane(); pane.docPath = '/notes/scan.pdf'; pane.activeTab = 'document';
  pane.tabs = [{ id: 'document', label: 'scan.pdf' }]; document.body.append(pane); await pane.updateComplete;
  expect(pane.shadowRoot!.querySelector('[data-testid="details-open-file"]')).not.toBeNull();
  expect(pane.shadowRoot!.querySelector('[data-testid="details-show-in-folder"]')).toBeNull();
});
it('S5-8 default reader omits source controls and extraction diagnostics', async () => {
  const pane = new DocumentPane(); document.body.append(pane); pane.docPath = '/notes/scan.pdf';
  await pane.updateComplete; await Promise.resolve(); await pane.updateComplete;
  expect(pane.shadowRoot!.querySelector('[role="radiogroup"]')).toBeNull();
});
it('S5-8 reader shows full folder pages size and one scan note with document text', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ content: 'raw scan text', textProvenance: 'ocr',
    visualExtractionEvidence: { pageCount: 2, textQualityScore: 0.7 } }))));
  const pane = new DocumentPane(); pane.docPath = '/notes/sub/scan.pdf'; pane.sizeBytes = 1024;
  document.body.append(pane);
  for (let n = 0; n < 3; n++) await new Promise(resolve => setTimeout(resolve, 0)); await pane.updateComplete;
  expect(pane.shadowRoot!.querySelector('.path')!.textContent!.trim()).toBe('/notes/sub · 2 pages · 1.0 KB');
  expect(pane.shadowRoot!.querySelectorAll('.scan-note')).toHaveLength(1);
  expect(pane.shadowRoot!.querySelector('.scan-note')!.textContent).toBe('Read from a scan. A few words may be wrong.');
  expect(pane.shadowRoot!.querySelector('.preview-source')).toBeNull();
  expect(pane.shadowRoot!.querySelector('.source')!.textContent!.trim()).toBe('raw scan text');
  expect(pane.content).toBe('raw scan text');
});
