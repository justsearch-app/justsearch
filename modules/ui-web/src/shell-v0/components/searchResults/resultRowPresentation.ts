// SPDX-License-Identifier: Apache-2.0
/**
 * resultRowPresentation — Tempdoc 602 R3 (render single-authority for the result row).
 *
 * The ONE presentation layer shared by the dedicated Search surface
 * (`SearchSurface.renderRow`) and the unified window's retrieve tier
 * (`UnifiedChatView.renderRetrieveRow`). Before R3 each surface formatted the
 * path and highlighted the snippet independently — the Search surface used
 * middle-ellipsis + `<mark>` query highlighting, the retrieve tier showed the
 * raw path with no highlighting — so the SAME result rendered two ways. Folding
 * both into this one module (the sibling of `matchCountLabel.ts`, which 597 R-1
 * extracted for the count, citing this same 602 R3 goal) makes the divergence
 * unrepresentable: both surfaces project the same path text and the same
 * highlighted snippet from the same code.
 *
 * `highlightTerms` is registered in the run-renderers SRR authority
 * (`governance/run-renderers.v1.json` "searchResultRendering"); a third file
 * importing it must register as a consumer (anti-fork teeth).
 */

import { html, css, type TemplateResult } from 'lit';

/**
 * F-21 display helper: truncate long paths with middle-ellipsis so the row
 * doesn't overflow. Preserves the filename + first ~25 chars of the parent;
 * full path remains in the title attribute for hover. Does NOT case-correct
 * (the path comes from the backend already-canonicalized; case-restoration
 * would require a filesystem stat per row).
 */
export const PATH_DISPLAY_MAX = 72;
export function formatDisplayPath(p: string): string {
  if (!p || p.length <= PATH_DISPLAY_MAX) return p;
  const sep = p.lastIndexOf('\\') >= 0 ? '\\' : '/';
  const lastSep = p.lastIndexOf(sep);
  if (lastSep < 0) return p.slice(0, PATH_DISPLAY_MAX - 1) + '…';
  const filename = p.slice(lastSep);
  const head = p.slice(0, Math.max(25, PATH_DISPLAY_MAX - filename.length - 3));
  return `${head}…${filename}`;
}

/**
 * Tempdoc 738 (C4) — a humanized location breadcrumb for Simple mode: drop the drive letter and the
 * filename (the filename is already the row title), and render the containing folder chain (last 3
 * segments) with " › " separators instead of the raw drive-lettered, backslashed full path. Detailed
 * mode still shows {@link formatDisplayPath}. Path-derived only — the card's `CardHit` carries no
 * collection metadata, so this humanizes the folders it has rather than inventing friendly names.
 */
export function formatLocationBreadcrumb(p: string): string {
  if (!p) return '';
  const parts = p.split(/[/\\]+/).filter(Boolean);
  const first = parts[0];
  if (first && /^[a-zA-Z]:$/.test(first)) parts.shift(); // drop a drive letter ("f:")
  parts.pop(); // drop the filename (already the row title); a root file yields no folders → ''
  return parts.slice(-3).join(' › ');
}

// SsotAnalyzerRegistry.createIcuAnalyzer defines no stop filter. Presentation therefore uses
// Lucene EnglishAnalyzer.ENGLISH_STOP_WORDS_SET (the default English set), not an index change.
const ENGLISH_STOP_WORDS = new Set(
  'a an and are as at be but by for if in into is it no not of on or such that the their then there these they this to was will with'.split(' '),
);

/** Presentation only; SearchSyntax.java remains the request parser.
 * Mirrors modules/core/src/main/java/io/justsearch/core/search/SearchSyntax.java:
 * lines 35–55 (whitespace, exclusions, case-sensitive title:/author:), 56–82 (phrases and
 * the three escapes). Invalid/incomplete input simply contributes no unfinished clause.
 */
function highlightQueryTerms(query: string): string[] {
  const terms = new Set<string>();
  // Character.isWhitespace: unlike JS \s, excludes non-breaking spaces/BOM and includes U+001C–1F.
  const isWhitespace = (c: string): boolean =>
    (/\s/u.test(c) && !/[\u00a0\u2007\u202f\ufeff]/u.test(c)) || (c >= '\u001c' && c <= '\u001f');
  let offset = 0;
  while (offset < query.length) {
    if (isWhitespace(query[offset]!)) { offset++; continue; }
    const excluded = query[offset] === '-'
      && /[\p{L}"]/u.test(String.fromCodePoint(query.codePointAt(offset + 1) ?? 0));
    if (excluded) offset++;
    if (!excluded) {
      for (const field of ['title:', 'author:']) {
        if (query.startsWith(field, offset)) {
          offset += field.length;
          break;
        }
      }
    }
    const phrase = query[offset] === '"';
    if (phrase) offset++;
    let value = '';
    let closed = !phrase;
    while (offset < query.length) {
      const c = query[offset]!;
      const next = query[offset + 1];
      if (c === '\\' && next !== undefined && ['"', '-', '\\'].includes(next)) {
        value += next;
        offset += 2;
      } else if (c === '"') {
        if (phrase) { closed = true; offset++; }
        break;
      } else if (!phrase && isWhitespace(c)) {
        break;
      } else {
        value += c;
        offset++;
      }
    }
    if (excluded || !closed) continue;
    for (const word of value.toLowerCase().match(/[\p{L}\p{N}]+/gu) ?? []) {
      if (word.length >= 2 && !ENGLISH_STOP_WORDS.has(word)) terms.add(word);
    }
  }
  return [...terms].sort((a, b) => b.length - a.length);
}

/** A run of text, and whether it is a found word. */
export interface HighlightSegment {
  readonly text: string;
  readonly mark: boolean;
}

/**
 * The query's found words in `text`, as runs: word prefixes matching the query's terms are marked.
 * The ONE matching shared by the result snippet ({@link highlightTerms}) and the reader, which marks
 * the same words in the document a search result opened. An empty / sub-2-char query marks nothing.
 */
export function highlightSegments(text: string, query: string): HighlightSegment[] {
  const terms = highlightQueryTerms(query);
  if (terms.length === 0) return text === '' ? [] : [{ text, mark: false }];
  // Terms contain only Unicode letters/digits. Split words first rather than using ASCII \b.
  const prefix = new RegExp(`^(${terms.join('|')})`, 'iu');
  const segments: HighlightSegment[] = [];
  text.split(/([\p{L}\p{N}]+)/u).forEach((part, index) => {
    const match = index % 2 === 1 ? prefix.exec(part) : null;
    if (match === null) {
      if (part !== '') segments.push({ text: part, mark: false });
      return;
    }
    segments.push({ text: match[0], mark: true });
    if (part.length > match[0].length) segments.push({ text: part.slice(match[0].length), mark: false });
  });
  return segments;
}

/**
 * Q11: wrap word prefixes matching the query's terms in `<mark>` so the snippet shows
 * WHY a result matched. Lit auto-escapes both the plain segments and the marked
 * text, so this is XSS-safe (no unsafeHTML). An empty / sub-2-char query yields
 * no marks (returns the text verbatim).
 */
export function highlightTerms(text: string, query: string): TemplateResult | string {
  if (highlightQueryTerms(query).length === 0) return text;
  return html`${highlightSegments(text, query).map((segment) =>
    segment.mark ? html`<mark class="hl">${segment.text}</mark>` : segment.text)}`;
}

/**
 * The found-word mark (first-minutes fm-04/fm-05): a faint warm wash behind the word, which keeps the
 * colour of the text around it — the found words are read as text, not as a row of filled blocks. The
 * wash is the theme's `--highlight-wash`, translucent, so every theme's own ink stays readable on it.
 * Unscoped `mark.hl` so it applies in either container that draws it (the result snippet, the reader);
 * the reader adds its own ring and padding. Forced colours take the system Mark pair.
 */
export const highlightStyles = css`
  mark.hl {
    background: var(--highlight-wash);
    color: var(--text-primary);
    border-radius: 4px;
    padding: 0 2px;
  }
  @media (forced-colors: active) {
    mark.hl {
      forced-color-adjust: none;
      background: Mark;
      color: MarkText;
      box-shadow: none;
    }
  }
`;

/**
 * The folder of a path, split for a middle ellipsis: everything up to the last folder, and the last
 * folder with its separator. The first part may be cut at its end while the last folder stays whole,
 * so a long folder reads "/home/reader/.ca…/Documents" on one line. Both parts stay in the text, so
 * the whole folder is still there for a screen reader and for copying.
 */
export function splitFolderTail(folder: string): { readonly head: string; readonly tail: string } {
  const at = Math.max(folder.lastIndexOf('/'), folder.lastIndexOf('\\'));
  if (at <= 0 || at === folder.length - 1) return { head: '', tail: folder };
  return { head: folder.slice(0, at), tail: folder.slice(at) };
}
