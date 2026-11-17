// SPDX-License-Identifier: Apache-2.0
/**
 * The results row's kind filters (fm-04): what the files are, in the words a person would use, one
 * filter per word. Kinds that share a word ("pdf" and "office" are both Documents) are one filter
 * whose count is their sum, and choosing it chooses all of them.
 *
 * Mail is what the rows and Browse call mail (display/fileKind.ts, by media type): the Engine files
 * it under its catch-all kind "binary" (message/rfc822), so the Mail filter counts and selects the
 * mail media types (the file-type filter More filters offers) and "Other files" counts the rest.
 */
import { isMail } from '../../display/fileKind.js';

/** The engine's `file_kind` values as words. Mail is recognised by media type, not by a kind name. */
const WORDS: Record<string, string> = {
  text: 'Text files', markdown: 'Text files', html: 'Text files',
  pdf: 'Documents', office: 'Documents', document: 'Documents', doc: 'Documents', docx: 'Documents',
  image: 'Images',
  spreadsheet: 'Spreadsheets', csv: 'Spreadsheets',
  presentation: 'Presentations',
  code: 'Code',
  archive: 'Archives',
};
const MAIL = 'Mail';
/** The Engine's kind for media it does not otherwise recognise, mail among them. */
const CATCH_ALL = 'binary';

/** The word for one engine kind; a kind without one is "Other files", never its raw name. */
export function kindWord(fileKind: string): string {
  return WORDS[fileKind.toLowerCase()] ?? 'Other files';
}

export type KindField = 'file_kind' | 'mime_base';
type KindCounts = { readonly file_kind?: Readonly<Record<string, number>>; readonly mime_base?: Readonly<Record<string, number>> };
/** One selection flip, as the card's `card-facet-toggle` carries it. */
export interface FacetToggle { readonly field: string; readonly value: string }

export interface KindFilter {
  readonly label: string;
  /** The filter field its values select on: Mail selects mail media types, the rest engine kinds. */
  readonly field: KindField;
  readonly values: readonly string[];
  /** Matches across the values, or undefined while counts are pending. */
  readonly count: number | undefined;
  readonly chosen: boolean;
}

/** Whether a selection is a kind filter's: every engine kind, and the mail media types. */
export function isKindChoice(field: string, value: string): boolean {
  return field === 'file_kind' || (field === 'mime_base' && isMail(value));
}

/** Every kind filter choice held now ("All" lets go of these). */
export function kindChoices(selections: Readonly<Record<string, readonly string[]>>): FacetToggle[] {
  return (['file_kind', 'mime_base'] as const).flatMap(field =>
    (selections[field] ?? []).filter(value => isKindChoice(field, value)).map(value => ({ field, value })));
}

/**
 * The kind filters to show, most matches first. Empty when there is fewer than two kinds and none
 * is chosen: one kind leaves nothing to choose between, but a chosen kind stays so it can be undone.
 */
export function kindFilters(
  counts: KindCounts | null | undefined,
  pending: KindCounts | null | undefined,
  selections: Readonly<Record<string, readonly string[]>>,
): KindFilter[] {
  const source = counts ?? pending ?? {};
  const known = counts !== null && counts !== undefined;
  const choices = kindChoices(selections);
  const mailTotal = Object.entries(source.mime_base ?? {}).reduce((n, [mime, count]) => n + (isMail(mime) ? count : 0), 0);
  // What a kind holds once mail is told apart: the catch-all less the mail filed under it.
  const held = (field: KindField, value: string, count: number): number =>
    field === 'file_kind' && value === CATCH_ALL ? Math.max(0, count - mailTotal) : count;
  const groups = new Map<string, { field: KindField; values: string[]; count: number | undefined; order: number }>();
  const add = (field: KindField, value: string, count: number | undefined): void => {
    const label = field === 'mime_base' ? MAIL : kindWord(value);
    const group = groups.get(label) ?? { field, values: [], count: undefined, order: 0 };
    if (!group.values.includes(value)) group.values.push(value);
    if (count !== undefined) group.count = (group.count ?? 0) + count;
    group.order += count ?? held(field, value, source[field]?.[value] ?? 0);
    groups.set(label, group);
  };
  for (const [value, count] of Object.entries(source.file_kind ?? {})) {
    const own = held('file_kind', value, count);
    if (own > 0) add('file_kind', value, known ? own : undefined);
  }
  for (const [value, count] of Object.entries(source.mime_base ?? {})) if (count > 0 && isMail(value)) add('mime_base', value, known ? count : undefined);
  for (const { field, value } of choices) {
    const kind = field as KindField;
    if (groups.get(kind === 'mime_base' ? MAIL : kindWord(value))?.values.includes(value)) continue;
    const count = counts?.[kind]?.[value];
    add(kind, value, count === undefined ? undefined : held(kind, value, count));
  }
  if (groups.size < 2 && choices.length === 0) return [];
  return [...groups.entries()]
    .sort((a, b) => b[1].order - a[1].order)
    .map(([label, group]) => ({ label, field: group.field, values: group.values, count: group.count,
      chosen: group.values.some(value => selections[group.field]?.includes(value) === true) }));
}

/**
 * The flips a press on `filter` makes, in one search: a chosen filter lets go of its values; an
 * unchosen one takes all of them, and lets go of kinds chosen on the other field, since a mail type
 * and an engine kind together would match only files that are both.
 */
export function kindFilterToggle(filter: KindFilter, selections: Readonly<Record<string, readonly string[]>>): FacetToggle[] {
  const selected = selections[filter.field] ?? [];
  if (filter.chosen) return filter.values.filter(value => selected.includes(value)).map(value => ({ field: filter.field, value }));
  return [...kindChoices(selections).filter(choice => choice.field !== filter.field),
    ...filter.values.map(value => ({ field: filter.field, value }))];
}
