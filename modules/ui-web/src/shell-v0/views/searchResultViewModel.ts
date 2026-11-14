// SPDX-License-Identifier: Apache-2.0
/**
 * searchResultViewModel — Tempdoc 577 Goal 1 Phase 7 (570 Move B, the §18 D3 interim).
 *
 * The ONE projection from a search hit's wire identity to its typed result view:
 * `kind` (from `file_kind`/`mime_base` — the data the renderer used to ignore),
 * the glyph, and the snippet (preferring the worker-computed `excerptRegions`
 * over the raw `content_preview`, with word-boundary truncation — the mid-word
 * "…ingestion-time (offline, inde" cut becomes unrepresentable here).
 *
 * Explicitly NOT the 559 element-projection engine (that stays the long-term
 * target); this is the typed-view-model + one type-switching renderer interim
 * 570 §18.4 D3 names. The renderer switches over `ResultKind`; a homogeneous
 * type-blind row has no constructor in this grammar.
 */

export type ResultKind = 'markdown' | 'code' | 'image' | 'pdf' | 'document' | 'other';

export interface SearchResultView {
  kind: ResultKind;
  /** Icon-atom glyph name for the kind. */
  icon: 'file-text' | 'code' | 'image';
  title: string;
  path: string;
  snippet: string;
  snippetSource: 'excerpt' | 'preview' | 'none';
  /** Line anchor of the best excerpt (code rows show it as :L<n>). */
  approxLine?: number;
  /** Tempdoc 811 (C-1a) — the corpus marker, absent for the user's own documents. */
  collection?: CollectionBadge;
}

/** The wire identity subset the projection reads (matches SearchHit / SearchHitSnapshot). */
export interface ResultViewInput {
  title: string;
  path: string;
  snippet?: string;
  kind?: string;
  mimeBase?: string;
  excerptRegions?: Array<{ text?: string; approxLine?: number }>;
  /** Tempdoc 811 (C-1a) — `fields.collection`; absent/`default` for the user's own documents. */
  collection?: string;
}

/**
 * Tempdoc 811 (C-1a) — the row marker for a hit that did NOT come from the user's own documents.
 * JustSearch deliberately ships and auto-ingests its own help files under the `justsearch-help`
 * collection (UIX-015), so app-internal docs intermix with user documents in the result list;
 * unmarked, the user cannot tell which is which (tempdoc 809 finding 5).
 *
 * The mechanism is per-collection, not a `justsearch-help` string check: any named non-default
 * collection (e.g. `agent-history`, reachable when that scope is explicitly enabled) is marked
 * through the same badge, so a new collection is legible the day it exists.
 */
export interface CollectionBadge {
  /** The raw wire value (`fields.collection`). */
  readonly collection: string;
  /** The pill text. */
  readonly label: string;
  /** Teal for the built-in help corpus (`search-ui-behavior.md` §Result Row Anatomy 9); else neutral. */
  readonly tone: 'help' | 'neutral';
  /** Hover text stating why the row is marked. */
  readonly title: string;
}

/**
 * Collection values that ARE the user's own documents, so they carry no marker: an absent/blank
 * field (`IndexingDocumentOps.buildDocument` only writes `collection` when the job carries one) and
 * the `default` sentinel an untagged watched root gets (`RootLifecycleOps.addWatchedRoot`).
 */
const DEFAULT_COLLECTIONS: ReadonlySet<string> = new Set(['', 'default']);

/** Per-collection presentation; an unlisted collection falls back to its own name, neutral. */
const COLLECTION_PRESENTATION: Readonly<
  Record<string, { label: string; tone: CollectionBadge['tone']; title: string }>
> = {
  'justsearch-help': {
    label: 'Help',
    tone: 'help',
    title: "Built-in JustSearch help — not one of your own documents",
  },
  'agent-history': {
    label: 'Agent history',
    tone: 'neutral',
    title: 'A saved agent conversation — not one of your own documents',
  },
};

/** The ONE collection → badge decision. Returns `undefined` for the user's own documents. */
export function collectionBadgeFor(collection: string | undefined): CollectionBadge | undefined {
  const value = (collection ?? '').trim();
  if (DEFAULT_COLLECTIONS.has(value)) return undefined;
  const known = COLLECTION_PRESENTATION[value];
  if (known) return { collection: value, ...known };
  return {
    collection: value,
    label: value,
    tone: 'neutral',
    title: `From the "${value}" collection — not one of your own documents`,
  };
}

const SNIPPET_MAX = 240;

/** Display only. Stored snippets and citation offsets always keep the original text. */
function cleanSnippet(text: string, path: string): string {
  let clean = text.replace(/---\s*OCR page \d+\s*---/gi, ' ');
  if (/\.(md|markdown|mdx)$/i.test(path)) {
    clean = clean
      .replace(/!?(?:\[([^\]]*)\])\([^)]*\)/g, '$1')
      .replace(/^\s{0,3}#{1,6}\s+/gm, '')
      .replace(/^\s*(?:>\s*)+(?:[-+*]\s+)?/gm, '')
      .replace(/^\s*(?:[-+*]|\d+[.)])\s+/gm, '')
      .replace(/^\s*```[^\n]*|^\s*~~~[^\n]*/gm, '')
      .replace(/(`+)(.*?)\1/g, '$2')
      .replace(/(\*\*|__)(.*?)\1/g, '$2')
      .replace(/(\*|_)([^\n]*?)\1/g, '$2')
      .replace(/^\s*[-:| ]+\|[-:| ]*$/gm, '')
      .replace(/\|/g, ' ');
  }
  return clean.replace(/\s+/g, ' ').trim();
}

/** Closed file_kind → ResultKind mapping; mime_base disambiguates the rest. */
function deriveKind(fileKind: string | undefined, mimeBase: string | undefined): ResultKind {
  switch (fileKind) {
    case 'markdown':
      return 'markdown';
    case 'code':
      return 'code';
    case 'image':
      return 'image';
    case 'pdf':
      return 'pdf';
    default:
      break;
  }
  if (mimeBase?.startsWith('image')) return 'image';
  if (mimeBase === 'application/pdf') return 'pdf';
  if (mimeBase?.startsWith('text')) return 'document';
  return fileKind ? 'document' : 'other';
}

const KIND_ICONS: Record<ResultKind, SearchResultView['icon']> = {
  markdown: 'file-text',
  code: 'code',
  image: 'image',
  pdf: 'file-text',
  document: 'file-text',
  other: 'file-text',
};

/** Word-boundary truncation: never cuts mid-word; appends an ellipsis when cut. */
export function truncateAtWord(text: string, max: number = SNIPPET_MAX): string {
  const t = text.trim();
  if (t.length <= max) return t;
  const cut = t.slice(0, max);
  const lastSpace = cut.lastIndexOf(' ');
  // Backtrack to a word boundary unless that would eat most of the text.
  const head = lastSpace > max * 0.6 ? cut.slice(0, lastSpace) : cut;
  return `${head.trimEnd()}…`;
}

export function projectResultView(hit: ResultViewInput): SearchResultView {
  const kind = deriveKind(hit.kind, hit.mimeBase);
  const excerpt = hit.excerptRegions?.find((r) => (r.text ?? '').trim().length > 0);
  const snippetRaw = excerpt?.text ?? hit.snippet ?? '';
  const snippet = truncateAtWord(cleanSnippet(snippetRaw, hit.path));
  const collection = collectionBadgeFor(hit.collection);
  return {
    kind,
    icon: KIND_ICONS[kind],
    title: hit.title,
    path: hit.path,
    snippet,
    snippetSource: snippet ? (excerpt ? 'excerpt' : 'preview') : 'none',
    ...(excerpt?.approxLine != null ? { approxLine: excerpt.approxLine } : {}),
    ...(collection ? { collection } : {}),
  };
}
