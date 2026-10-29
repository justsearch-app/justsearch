// SPDX-License-Identifier: Apache-2.0
import { localizeResourceKey as message } from '../../../i18n/resourceCatalog.js';
import type { SearchFilterSpec, SizeUnit } from '../../state/searchFiltersState.js';

export const SIZE_UNITS: Record<SizeUnit, number> = { KB: 1024, MB: 1024 ** 2, GB: 1024 ** 3 };
const DATE_MAX = 253402300799999;

export function localDay(ms: number): string {
  const d = new Date(ms);
  return `${String(d.getFullYear()).padStart(4, '0')}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** Next local midnight, rather than adding 24h: calendar days can have 23 or 25 hours. */
export function dateBound(text: string, end: boolean): number | undefined {
  if (text === '') return undefined;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) return NaN;
  const [year = 0, month = 0, day = 0] = text.split('-').map(Number);
  const date = new Date(0);
  date.setFullYear(year, month - 1, day);
  date.setHours(0, 0, 0, 0);
  if (localDay(date.getTime()) !== text) return NaN;
  if (end) date.setDate(date.getDate() + 1);
  const ms = date.getTime() - (end ? 1 : 0);
  return ms >= 1 && ms <= DATE_MAX ? ms : NaN;
}

export function sizeBound(text: string, unit: SizeUnit): number | undefined {
  if (text === '') return undefined;
  if (!/^(?:\d+(?:\.\d*)?|\.\d+)$/.test(text)) return NaN;
  const bytes = Math.round(Number(text) * SIZE_UNITS[unit]);
  return Number.isSafeInteger(bytes) && bytes >= 0 ? bytes : NaN;
}

export type RestrictionKind = 'folder' | 'date' | 'size';
export function restrictionLabels(spec: SearchFilterSpec): Array<{ kind: RestrictionKind; label: string }> {
  const labels: Array<{ kind: RestrictionKind; label: string }> = [];
  if (spec.pathPrefix !== undefined) labels.push({ kind: 'folder', label: `${message('search.filters.folder')}: ${spec.pathPrefix}` });
  const range = (from: string | undefined, to: string | undefined): string =>
    from !== undefined && to !== undefined ? `${from} – ${to}` : from !== undefined ? `≥ ${from}` : `≤ ${to ?? ''}`;
  const from = spec.modifiedFromMs === 0 ? undefined : spec.modifiedFromMs;
  const to = spec.modifiedToMs === 0 ? undefined : spec.modifiedToMs;
  if (from !== undefined || to !== undefined) labels.push({ kind: 'date',
    label: `${message('search.filters.modified')}: ${range(from === undefined ? undefined : localDay(from), to === undefined ? undefined : localDay(to))}` });
  const size = (bytes: number | undefined, input: SearchFilterSpec['sizeFromInput']): string | undefined =>
    bytes === undefined ? undefined : input ? `${input.text} ${input.unit}` : `${bytes / 1024} KB`;
  if (spec.sizeFromBytes !== undefined || spec.sizeToBytes !== undefined) labels.push({ kind: 'size',
    label: `${message('search.filters.size')}: ${range(size(spec.sizeFromBytes, spec.sizeFromInput), size(spec.sizeToBytes, spec.sizeToInput))}` });
  return labels;
}

export function withoutRestriction(spec: SearchFilterSpec, kind: RestrictionKind): SearchFilterSpec {
  const next = { ...spec };
  if (kind === 'folder') delete next.pathPrefix;
  if (kind === 'date') { delete next.modifiedFromMs; delete next.modifiedToMs; }
  if (kind === 'size') { delete next.sizeFromBytes; delete next.sizeToBytes; delete next.sizeFromInput; delete next.sizeToInput; }
  return next;
}
