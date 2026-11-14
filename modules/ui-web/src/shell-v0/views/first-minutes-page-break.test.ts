// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { projectResultView } from './searchResultViewModel.js';

// StructuredDocument.appendPageBreak writes a "---" line between pages (a Markdown thematic break).
it.each(['pdf', 'docx', 'md'])('drops page-break rules from %s rows, on their own line or flattened', ext => {
  for (const raw of ['harbour-scan\n\n---\n\nHarbour lighthouse logbook', 'harbour-scan --- Harbour lighthouse logbook']) {
    const hit = { title: 'scan', path: `/scan.${ext}`, snippet: raw };
    expect(projectResultView(hit).snippet).toBe('harbour-scan Harbour lighthouse logbook');
    expect(hit.snippet).toBe(raw);
  }
});
it.each(['ts', 'txt', 'csv'])('keeps "---" in %s rows', ext => {
  expect(projectResultView({ title: 'raw', path: `/raw.${ext}`, snippet: 'a\n---\nb --- c' }).snippet).toBe('a --- b --- c');
});
