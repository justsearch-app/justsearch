// SPDX-License-Identifier: Apache-2.0
import type { IconName } from '../components/Icon.js';

export type ResultKind = 'markdown' | 'code' | 'image' | 'pdf' | 'document' | 'spreadsheet' | 'mail' | 'other';
const ICONS: Record<ResultKind, IconName> = {
  markdown: 'list', code: 'code', image: 'image', pdf: 'file-down', document: 'file-text',
  spreadsheet: 'table', mail: 'mail', other: 'file',
};
/** Whether a file is mail, by its extension or media type: the one recognition rows, Browse and the kind filters share. */
export function isMail(mimeBase?: string, path?: string): boolean {
  const extension = path?.split(/[\\/]/).pop()?.split('.').pop()?.toLowerCase() ?? '';
  return ['eml', 'msg', 'mbox'].includes(extension) || mimeBase?.startsWith('message/') === true;
}
/** The file-kind projection shared by search result rows and Browse. MIME supplies missing kind detail. */
export function fileKindPresentation(fileKind?: string, mimeBase?: string, path?: string): { kind: ResultKind; icon: IconName } {
  let kind: ResultKind;
  const extension = path?.split(/[\\/]/).pop()?.split('.').pop()?.toLowerCase() ?? '';
  if (['csv', 'tsv', 'xls', 'xlsx', 'ods'].includes(extension) || /spreadsheet|excel|csv/.test(mimeBase ?? '')) kind = 'spreadsheet';
  else if (isMail(mimeBase, path)) kind = 'mail';
  else if (fileKind === 'markdown' || fileKind === 'text') kind = 'markdown';
  else if (fileKind === 'code' || fileKind === 'image' || fileKind === 'pdf') kind = fileKind;
  else if (['spreadsheet', 'csv'].includes(fileKind ?? '') || /spreadsheet|excel|csv/.test(mimeBase ?? '')) kind = 'spreadsheet';
  else if (['mail', 'email'].includes(fileKind ?? '')) kind = 'mail';
  else if (mimeBase?.startsWith('image')) kind = 'image';
  else if (mimeBase === 'application/pdf') kind = 'pdf';
  else if (['office', 'document'].includes(fileKind ?? '') || mimeBase?.startsWith('text') || /word|opendocument.text/.test(mimeBase ?? '')) kind = 'document';
  else kind = 'other';
  return { kind, icon: ICONS[kind] };
}
