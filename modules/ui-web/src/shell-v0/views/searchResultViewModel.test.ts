/**
 * Tempdoc 577 Goal 1 Phase 7 (570 Move B / §18 D3 interim) — the typed result view.
 *
 * Pins: kind derivation (file_kind first, mime_base disambiguates), the
 * excerpt-over-preview snippet preference, and word-boundary truncation (the
 * mid-word cut is unrepresentable).
 */
import { describe, it, expect } from 'vitest';
import { collectionBadgeFor, projectResultView, truncateAtWord } from './searchResultViewModel.js';

describe('searchResultViewModel (577 Phase 7)', () => {
  it('derives kind from file_kind, with mime_base as the fallback', () => {
    expect(projectResultView({ title: 't', path: 'p', kind: 'markdown' }).kind).toBe('markdown');
    expect(projectResultView({ title: 't', path: 'p', kind: 'code' }).kind).toBe('code');
    expect(projectResultView({ title: 't', path: 'p', mimeBase: 'image/png' }).kind).toBe('image');
    expect(projectResultView({ title: 't', path: 'p', mimeBase: 'application/pdf' }).kind).toBe('pdf');
    expect(projectResultView({ title: 't', path: 'p', mimeBase: 'text/plain' }).kind).toBe('document');
    expect(projectResultView({ title: 't', path: 'p' }).kind).toBe('other');
  });

  it('maps each kind to an icon glyph', () => {
    expect(projectResultView({ title: 't', path: 'p', kind: 'code' }).icon).toBe('code');
    expect(projectResultView({ title: 't', path: 'p', kind: 'image' }).icon).toBe('image');
    expect(projectResultView({ title: 't', path: 'p', kind: 'markdown' }).icon).toBe('list');
  });

  it('prefers the worker excerpt over content_preview and carries its line anchor', () => {
    const v = projectResultView({
      title: 't',
      path: 'p',
      snippet: 'raw preview text',
      excerptRegions: [{ text: 'the best passage', approxLine: 42 }],
    });
    expect(v.snippet).toBe('the best passage');
    expect(v.snippetSource).toBe('excerpt');
    expect(v.approxLine).toBe(42);
  });

  it('falls back to content_preview when no excerpt has text', () => {
    const v = projectResultView({
      title: 't',
      path: 'p',
      snippet: 'preview',
      excerptRegions: [{ text: '  ' }],
    });
    expect(v.snippet).toBe('preview');
    expect(v.snippetSource).toBe('preview');
  });

  it('truncates at a word boundary with an ellipsis — never mid-word', () => {
    const long = `${'word '.repeat(60)}ingestion-time`; // > 240 chars
    const out = truncateAtWord(long, 240);
    expect(out.length).toBeLessThanOrEqual(241); // incl. ellipsis
    expect(out.endsWith('…')).toBe(true);
    // The cut never leaves a partial word before the ellipsis.
    const beforeEllipsis = out.slice(0, -1).trimEnd();
    expect(long.includes(beforeEllipsis)).toBe(true);
    expect(long[beforeEllipsis.length]).toBe(' ');
  });

  it('short text passes through untouched', () => {
    expect(truncateAtWord('short', 240)).toBe('short');
  });
});

/**
 * Tempdoc 811 (C-1a) — the corpus marker. JustSearch ships and auto-ingests its own help files
 * under `justsearch-help` (UIX-015), so app-internal docs intermix with the user's own documents;
 * unmarked, they are indistinguishable (809 finding 5). Pinned here: the marker is per-collection
 * (any named non-default value badges), and the user's own documents never badge.
 */
describe('searchResultViewModel — collection badge (811 C-1a)', () => {
  it('badges the built-in help corpus with the teal "Help" tone', () => {
    const badge = collectionBadgeFor('justsearch-help');
    expect(badge?.label).toBe('Help');
    expect(badge?.tone).toBe('help');
    expect(badge?.collection).toBe('justsearch-help');
  });

  it('treats the user\'s own documents as unmarked — absent, blank, and the `default` sentinel', () => {
    expect(collectionBadgeFor(undefined)).toBeUndefined();
    expect(collectionBadgeFor('')).toBeUndefined();
    expect(collectionBadgeFor('   ')).toBeUndefined();
    expect(collectionBadgeFor('default')).toBeUndefined();
  });

  it('badges agent-history neutrally (a named collection, not the help corpus)', () => {
    const badge = collectionBadgeFor('agent-history');
    expect(badge?.label).toBe('Agent history');
    expect(badge?.tone).toBe('neutral');
  });

  it('badges an UNLISTED collection by its own name — per-collection, not a justsearch-help check', () => {
    const badge = collectionBadgeFor('mcp-ingest');
    expect(badge?.label).toBe('mcp-ingest');
    expect(badge?.tone).toBe('neutral');
    expect(badge?.title).toContain('mcp-ingest');
  });

  it('carries the badge onto the projected view, and omits it for the default corpus', () => {
    expect(projectResultView({ title: 't', path: 'p', collection: 'justsearch-help' }).collection).toEqual({
      collection: 'justsearch-help',
      label: 'Help',
      tone: 'help',
      title: expect.any(String),
    });
    expect(projectResultView({ title: 't', path: 'p' }).collection).toBeUndefined();
    expect(projectResultView({ title: 't', path: 'p', collection: 'default' }).collection).toBeUndefined();
  });
});
