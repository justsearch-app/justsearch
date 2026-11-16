// SPDX-License-Identifier: Apache-2.0
/**
 * The results row's kind filters (fm-04): the engine's file kinds in the words a person would use,
 * one filter per word. Kinds that share a word ("pdf" and "office" are both Documents) are one
 * filter whose count is their sum, and choosing it chooses all of them.
 */

/** The engine's `file_kind` values (and the mail/sheet/slide names a facet may carry) as words. */
const WORDS: Record<string, string> = {
  text: 'Text files', markdown: 'Text files', html: 'Text files',
  pdf: 'Documents', office: 'Documents', document: 'Documents', doc: 'Documents', docx: 'Documents',
  email: 'Mail', mail: 'Mail', message: 'Mail',
  image: 'Images',
  spreadsheet: 'Spreadsheets', csv: 'Spreadsheets',
  presentation: 'Presentations',
  code: 'Code',
  archive: 'Archives',
};

/** The word for one engine kind; a kind without one is "Other files", never its raw name. */
export function kindWord(fileKind: string): string {
  return WORDS[fileKind.toLowerCase()] ?? 'Other files';
}

export interface KindFilter {
  readonly label: string;
  /** The engine kinds this filter stands for. */
  readonly values: readonly string[];
  /** Matches across the kinds, or undefined while counts are pending. */
  readonly count: number | undefined;
  readonly chosen: boolean;
}

/**
 * The kind filters to show, most matches first. Empty when there is fewer than two kinds and none
 * is chosen: one kind leaves nothing to choose between, but a chosen kind stays so it can be undone.
 */
export function kindFilters(
  counts: Readonly<Record<string, number>> | undefined,
  pending: Readonly<Record<string, number>> | undefined,
  selected: readonly string[],
): KindFilter[] {
  const byWord = new Map<string, { values: string[]; count: number | undefined; order: number }>();
  const add = (value: string, count: number | undefined): void => {
    const label = kindWord(value);
    const group = byWord.get(label) ?? { values: [], count: undefined, order: 0 };
    if (!group.values.includes(value)) group.values.push(value);
    if (count !== undefined) group.count = (group.count ?? 0) + count;
    group.order += count ?? pending?.[value] ?? 0;
    byWord.set(label, group);
  };
  for (const [value, count] of Object.entries(counts ?? pending ?? {})) if (count > 0) add(value, counts ? count : undefined);
  for (const value of selected) if (!(byWord.get(kindWord(value))?.values.includes(value))) add(value, counts?.[value]);
  if (byWord.size < 2 && selected.length === 0) return [];
  return [...byWord.entries()]
    .sort((a, b) => b[1].order - a[1].order)
    .map(([label, group]) => ({ label, values: group.values, count: group.count,
      chosen: group.values.some(value => selected.includes(value)) }));
}

/** The engine kinds a press on `filter` flips: a chosen filter lets go of all of its kinds. */
export function kindFilterToggle(filter: KindFilter, selected: readonly string[]): string[] {
  return filter.chosen ? filter.values.filter(value => selected.includes(value)) : [...filter.values];
}
