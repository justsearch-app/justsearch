// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { kindFilters, kindFilterToggle, kindWord } from './kindFilters.js';

it('PF1 every engine kind reads as a word a person would use, never its raw name', () => {
  const engineKinds = ['text', 'markdown', 'pdf', 'office', 'image', 'code', 'archive', 'binary', 'unknown', 'email'];
  const words = engineKinds.map(kindWord);
  expect(words).toEqual(['Text files', 'Text files', 'Documents', 'Documents', 'Images', 'Code', 'Archives',
    'Other files', 'Other files', 'Mail']);
  for (const kind of engineKinds) expect(kindWord(kind)).not.toBe(kind);
});

it('PF1 kinds sharing a word are one filter with their summed count, most matches first', () => {
  expect(kindFilters({ eml: 0, email: 1, pdf: 1, office: 1 }, undefined, [])).toEqual([
    { label: 'Documents', values: ['pdf', 'office'], count: 2, chosen: false },
    { label: 'Mail', values: ['email'], count: 1, chosen: false },
  ]);
});

it('PF2 one kind among the results shows no kind filters; a chosen kind stays so it can be undone', () => {
  expect(kindFilters({ text: 36 }, undefined, [])).toEqual([]);
  expect(kindFilters({ text: 30, markdown: 6 }, undefined, [])).toEqual([]);
  expect(kindFilters(undefined, undefined, [])).toEqual([]);
  expect(kindFilters({ pdf: 2 }, undefined, ['pdf'])).toEqual([{ label: 'Documents', values: ['pdf'], count: 2, chosen: true }]);
});

it('PF1 pending counts keep the known kinds without numbers; a chosen kind absent from counts stays', () => {
  expect(kindFilters(undefined, { pdf: 2, code: 1 }, [])).toEqual([
    { label: 'Documents', values: ['pdf'], count: undefined, chosen: false },
    { label: 'Code', values: ['code'], count: undefined, chosen: false },
  ]);
  expect(kindFilters({ text: 4, code: 1 }, undefined, ['pdf']).map(f => [f.label, f.count, f.chosen])).toEqual([
    ['Text files', 4, false], ['Code', 1, false], ['Documents', undefined, true],
  ]);
});

it('PF1 choosing a word chooses all of its kinds; choosing it again lets go of the chosen ones', () => {
  const [documents] = kindFilters({ pdf: 1, office: 1, email: 1 }, undefined, []);
  expect(kindFilterToggle(documents!, [])).toEqual(['pdf', 'office']);
  const [chosen] = kindFilters({ pdf: 1, office: 1, email: 1 }, undefined, ['office']);
  expect(chosen!.chosen).toBe(true);
  expect(kindFilterToggle(chosen!, ['office'])).toEqual(['office']);
});
