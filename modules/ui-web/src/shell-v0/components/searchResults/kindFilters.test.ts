// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { fileKindPresentation } from '../../display/fileKind.js';
import { kindChoices, kindFilters, kindFilterToggle, kindWord } from './kindFilters.js';

const EML = 'message/rfc822';
const kinds = (fileKind: Record<string, number>, mimeBase: Record<string, number> = {}) => ({ file_kind: fileKind, mime_base: mimeBase });

it('PF1 every engine kind reads as a word a person would use, never its raw name', () => {
  const engineKinds = ['text', 'markdown', 'pdf', 'office', 'image', 'code', 'archive', 'binary', 'unknown'];
  const words = engineKinds.map(kindWord);
  expect(words).toEqual(['Text files', 'Text files', 'Documents', 'Documents', 'Images', 'Code', 'Archives',
    'Other files', 'Other files']);
  for (const kind of engineKinds) expect(kindWord(kind)).not.toBe(kind);
});

it('PF1\' mail the Engine files as "binary" (message/rfc822) is Mail, as its row shows it; other binary files stay Other files', () => {
  expect(fileKindPresentation('binary', EML, '/home/reader/message.eml').kind, 'the row shows it as mail').toBe('mail');
  expect(kindFilters(kinds({ binary: 2, pdf: 1 }, { [EML]: 1, 'application/octet-stream': 1, 'application/pdf': 1 }), undefined, {})).toEqual([
    { label: 'Other files', field: 'file_kind', values: ['binary'], count: 1, chosen: false },
    { label: 'Documents', field: 'file_kind', values: ['pdf'], count: 1, chosen: false },
    { label: 'Mail', field: 'mime_base', values: [EML], count: 1, chosen: false },
  ]);
  // Only mail among the catch-all: no "Other files" beside it.
  expect(kindFilters(kinds({ binary: 1, office: 1, pdf: 1 }, { [EML]: 1, 'application/pdf': 1 }), undefined, {}).map(f => [f.label, f.count]))
    .toEqual([['Documents', 2], ['Mail', 1]]);
  // A non-mail binary file is never Mail.
  expect(kindFilters(kinds({ binary: 1, pdf: 1 }, { 'application/octet-stream': 1, 'application/pdf': 1 }), undefined, {}).map(f => f.label))
    .toEqual(['Other files', 'Documents']);
});

it('PF1\' choosing Mail selects the mail media types, not the catch-all kind; a chosen Mail stays and lets go again', () => {
  const [, mail] = kindFilters(kinds({ binary: 1, office: 1, pdf: 1 }, { [EML]: 1 }), undefined, {});
  expect(mail!.label).toBe('Mail');
  expect(kindFilterToggle(mail!, {})).toEqual([{ field: 'mime_base', value: EML }]);
  const chosen = kindFilters(kinds({ binary: 1 }, { [EML]: 1 }), undefined, { mime_base: [EML] });
  expect(chosen).toEqual([{ label: 'Mail', field: 'mime_base', values: [EML], count: 1, chosen: true }]);
  expect(kindFilterToggle(chosen[0]!, { mime_base: [EML] })).toEqual([{ field: 'mime_base', value: EML }]);
  // A mail type and an engine kind together would match only files that are both: choosing one lets go of the other.
  const [documents] = kindFilters(kinds({ binary: 1, office: 1, pdf: 1 }, { [EML]: 1 }), undefined, { mime_base: [EML, 'text/plain'] });
  expect(kindFilterToggle(documents!, { mime_base: [EML, 'text/plain'] })).toEqual([
    { field: 'mime_base', value: EML }, { field: 'file_kind', value: 'office' }, { field: 'file_kind', value: 'pdf' }]);
  expect(kindChoices({ mime_base: [EML, 'text/plain'], file_kind: ['pdf'], language: ['en'] }), 'a format chosen in More filters is not a kind')
    .toEqual([{ field: 'file_kind', value: 'pdf' }, { field: 'mime_base', value: EML }]);
});

it('PF1 kinds sharing a word are one filter with their summed count, most matches first', () => {
  expect(kindFilters(kinds({ text: 0, pdf: 1, office: 1, code: 1 }), undefined, {})).toEqual([
    { label: 'Documents', field: 'file_kind', values: ['pdf', 'office'], count: 2, chosen: false },
    { label: 'Code', field: 'file_kind', values: ['code'], count: 1, chosen: false },
  ]);
});

it('PF2 one kind among the results shows no kind filters; a chosen kind stays so it can be undone', () => {
  expect(kindFilters(kinds({ text: 36 }), undefined, {})).toEqual([]);
  expect(kindFilters(kinds({ text: 30, markdown: 6 }), undefined, {})).toEqual([]);
  expect(kindFilters(kinds({ binary: 2 }, { [EML]: 2 }), undefined, {}), 'only mail').toEqual([]);
  expect(kindFilters(undefined, undefined, {})).toEqual([]);
  expect(kindFilters(kinds({ pdf: 2 }), undefined, { file_kind: ['pdf'] }))
    .toEqual([{ label: 'Documents', field: 'file_kind', values: ['pdf'], count: 2, chosen: true }]);
});

it('PF1 pending counts keep the known kinds without numbers; a chosen kind absent from counts stays', () => {
  expect(kindFilters(undefined, kinds({ pdf: 2, code: 1, binary: 1 }, { [EML]: 1 }), {})).toEqual([
    { label: 'Documents', field: 'file_kind', values: ['pdf'], count: undefined, chosen: false },
    { label: 'Code', field: 'file_kind', values: ['code'], count: undefined, chosen: false },
    { label: 'Mail', field: 'mime_base', values: [EML], count: undefined, chosen: false },
  ]);
  expect(kindFilters(kinds({ text: 4, code: 1 }), undefined, { file_kind: ['pdf'] }).map(f => [f.label, f.count, f.chosen])).toEqual([
    ['Text files', 4, false], ['Code', 1, false], ['Documents', undefined, true],
  ]);
  expect(kindFilters(kinds({ text: 4, code: 1 }), undefined, { mime_base: [EML] }).map(f => [f.label, f.count, f.chosen])).toEqual([
    ['Text files', 4, false], ['Code', 1, false], ['Mail', undefined, true],
  ]);
});

it('PF1 choosing a word chooses all of its kinds; choosing it again lets go of the chosen ones', () => {
  const [documents] = kindFilters(kinds({ pdf: 1, office: 1, code: 1 }), undefined, {});
  expect(kindFilterToggle(documents!, {})).toEqual([{ field: 'file_kind', value: 'pdf' }, { field: 'file_kind', value: 'office' }]);
  const [chosen] = kindFilters(kinds({ pdf: 1, office: 1, code: 1 }), undefined, { file_kind: ['office'] });
  expect(chosen!.chosen).toBe(true);
  expect(kindFilterToggle(chosen!, { file_kind: ['office'] })).toEqual([{ field: 'file_kind', value: 'office' }]);
});
