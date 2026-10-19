/**
 * Parser tests — slice 489 §7 stage 1.
 *
 * Mirrors the scorer's parser tests (the round-2 §7 finding). Each assertion
 * here corresponds to a behavior in agent-battery-url-scorer.mjs so the two
 * implementations stay in lockstep.
 */

import { describe, expect, it } from 'vitest';
import { canonicalize, extractUrls, parseUrl } from './parser.js';

describe('parseUrl — Navigation', () => {
  it('parses bare surface URL', () => {
    expect(parseUrl('justsearch://surface/core.library-surface')).toEqual({
      kind: 'navigate',
      target: 'core.library-surface',
      state: {},
    });
  });

  it('parses surface URL with single query param', () => {
    expect(parseUrl('justsearch://surface/core.search-surface?query=rust')).toEqual({
      kind: 'navigate',
      target: 'core.search-surface',
      state: { query: 'rust' },
    });
  });

  it('parses surface URL with multiple query params', () => {
    expect(
      parseUrl(
        'justsearch://surface/core.search-surface?query=rust&modifiedFromMs=12345',
      ),
    ).toEqual({
      kind: 'navigate',
      target: 'core.search-surface',
      state: { query: 'rust', modifiedFromMs: '12345' },
    });
  });

  it('repeated keys collapse to array', () => {
    expect(parseUrl('justsearch://surface/core.library?ids=a&ids=b&ids=c')).toEqual({
      kind: 'navigate',
      target: 'core.library',
      state: { ids: ['a', 'b', 'c'] },
    });
  });

  it('URL-encoded values are decoded', () => {
    expect(
      parseUrl('justsearch://surface/core.search?query=rust%20ownership'),
    ).toEqual({
      kind: 'navigate',
      target: 'core.search',
      state: { query: 'rust ownership' },
    });
  });
});

describe('parseUrl — Invocation', () => {
  it('parses bare op URL', () => {
    expect(parseUrl('justsearch://op/core.search-index')).toEqual({
      kind: 'invoke',
      target: 'core.search-index',
      args: {},
    });
  });

  it('parses op URL with args', () => {
    expect(parseUrl('justsearch://op/core.search-index?query=rust&limit=25')).toEqual({
      kind: 'invoke',
      target: 'core.search-index',
      args: { query: 'rust', limit: '25' },
    });
  });
});

describe('parseUrl — Query (548 S4-A)', () => {
  it('parses a bare query URL', () => {
    expect(parseUrl('justsearch://query?q=rust')).toEqual({
      kind: 'query',
      query: 'rust',
      state: {},
    });
  });

  it('parses a query URL with refinement state', () => {
    expect(parseUrl('justsearch://query?q=rust&lang=en')).toEqual({
      kind: 'query',
      query: 'rust',
      state: { lang: 'en' },
    });
  });

  it('rejects a query URL with no q param', () => {
    expect(parseUrl('justsearch://query')).toBeNull();
  });

  it('rejects a query URL with a blank q param', () => {
    expect(parseUrl('justsearch://query?q=')).toBeNull();
  });

  it('rejects a query URL with a non-empty path', () => {
    expect(parseUrl('justsearch://query/foo?q=rust')).toBeNull();
  });

  it('canonicalize → parseUrl round-trips a query with state', () => {
    const addr = { kind: 'query' as const, query: 'hello world', state: { lang: 'en' } };
    expect(parseUrl(canonicalize(addr))).toEqual(addr);
  });
});

describe('parseUrl — Answer (548 §4.5)', () => {
  it('parses a bare answer URL with the default shape', () => {
    expect(parseUrl('justsearch://answer?q=what%20is%20rust')).toEqual({
      kind: 'answer',
      prompt: 'what is rust',
      shape: 'core.rag-ask',
      state: {},
    });
  });

  it('parses an explicit shape + refinement state', () => {
    expect(parseUrl('justsearch://answer?q=summarize&shape=core.summarize&lang=en')).toEqual({
      kind: 'answer',
      prompt: 'summarize',
      shape: 'core.summarize',
      state: { lang: 'en' },
    });
  });

  it('rejects an answer URL with no q', () => {
    expect(parseUrl('justsearch://answer')).toBeNull();
    expect(parseUrl('justsearch://answer?q=')).toBeNull();
  });

  it('rejects an answer URL with a non-empty path', () => {
    expect(parseUrl('justsearch://answer/foo?q=x')).toBeNull();
  });

  it('canonicalize → parseUrl round-trips (default shape omitted; explicit shape kept)', () => {
    const dflt = { kind: 'answer' as const, prompt: 'hello', shape: 'core.rag-ask', state: {} };
    expect(parseUrl(canonicalize(dflt))).toEqual(dflt);
    const explicit = {
      kind: 'answer' as const,
      prompt: 'sum it',
      shape: 'core.summarize',
      state: { lang: 'en' },
    };
    expect(parseUrl(canonicalize(explicit))).toEqual(explicit);
  });
});

describe('parseUrl — rejection', () => {
  it('rejects non-justsearch scheme', () => {
    expect(parseUrl('https://example.com/surface/x')).toBeNull();
    expect(parseUrl('http://justsearch/op/x')).toBeNull();
  });

  it('rejects unknown host kind', () => {
    expect(parseUrl('justsearch://other/x')).toBeNull();
  });

  it('rejects missing id', () => {
    expect(parseUrl('justsearch://surface/')).toBeNull();
  });

  it('rejects multi-segment path', () => {
    expect(parseUrl('justsearch://surface/core.x/sub')).toBeNull();
  });

  it('rejects ids with characters outside [A-Za-z0-9_.\\-]', () => {
    // Note: the parser regex permits uppercase (it mirrors the scorer's
    // `/^[A-Za-z0-9_.\\-]+$/`). Namespacing rules — lowercase + `core.` /
    // `vendor.` prefix — are enforced at catalog-resolution time, not by the
    // parser. The two layers separate syntactic validity from catalog
    // legitimacy.
    expect(parseUrl('justsearch://surface/core.x@y')).toBeNull();
    expect(parseUrl('justsearch://surface/core x')).toBeNull(); // space → URL ctor decoding rejects
  });

  it('rejects malformed URL', () => {
    expect(parseUrl('not a url')).toBeNull();
    expect(parseUrl('')).toBeNull();
  });

  it('rejects non-string input', () => {
    // @ts-expect-error testing runtime type guard
    expect(parseUrl(undefined)).toBeNull();
    // @ts-expect-error testing runtime type guard
    expect(parseUrl(123)).toBeNull();
  });
});

describe('canonicalize', () => {
  it('navigation with no state yields bare URL', () => {
    expect(
      canonicalize({
        kind: 'navigate',
        target: 'core.library-surface',
        state: {},
      }),
    ).toBe('justsearch://surface/core.library-surface');
  });

  it('navigation with state sorts keys', () => {
    expect(
      canonicalize({
        kind: 'navigate',
        target: 'core.search-surface',
        state: { z: 'last', a: 'first', m: 'middle' },
      }),
    ).toBe('justsearch://surface/core.search-surface?a=first&m=middle&z=last');
  });

  it('navigation with array values produces repeated keys', () => {
    expect(
      canonicalize({
        kind: 'navigate',
        target: 'core.library',
        state: { ids: ['a', 'b', 'c'] },
      }),
    ).toBe('justsearch://surface/core.library?ids=a&ids=b&ids=c');
  });

  it('navigation encodes special characters', () => {
    expect(
      canonicalize({
        kind: 'navigate',
        target: 'core.search',
        state: { query: 'rust ownership & lifetimes' },
      }),
    ).toBe('justsearch://surface/core.search?query=rust%20ownership%20%26%20lifetimes');
  });

  it('invocation with no args yields bare URL', () => {
    expect(
      canonicalize({ kind: 'invoke', target: 'core.search-index', args: {} }),
    ).toBe('justsearch://op/core.search-index');
  });

  it('invocation stringifies non-string args', () => {
    expect(
      canonicalize({
        kind: 'invoke',
        target: 'core.search-index',
        args: { limit: 25, ascending: true },
      }),
    ).toBe('justsearch://op/core.search-index?ascending=true&limit=25');
  });

  it('invocation skips null / undefined args', () => {
    expect(
      canonicalize({
        kind: 'invoke',
        target: 'core.search-index',
        args: { query: 'rust', maybe: undefined, also: null },
      }),
    ).toBe('justsearch://op/core.search-index?query=rust');
  });
});

describe('canonicalize round-trip with parseUrl', () => {
  it('navigation round-trips exactly', () => {
    const addr = {
      kind: 'navigate' as const,
      target: 'core.search-surface',
      state: { query: 'rust', limit: '25' },
    };
    const url = canonicalize(addr);
    expect(parseUrl(url)).toEqual(addr);
  });

  it('navigation with array round-trips exactly', () => {
    const addr = {
      kind: 'navigate' as const,
      target: 'core.library',
      state: { ids: ['a', 'b'] },
    };
    expect(parseUrl(canonicalize(addr))).toEqual(addr);
  });
});

describe('extractUrls', () => {
  it('extracts a single bare URL from prose', () => {
    const found = extractUrls('Try this: justsearch://surface/core.library now.');
    expect(found).toHaveLength(1);
    expect(found[0]?.url).toBe('justsearch://surface/core.library');
  });

  it('extracts URLs from Markdown links', () => {
    const found = extractUrls('See [the library](justsearch://surface/core.library).');
    expect(found).toHaveLength(1);
    expect(found[0]?.url).toBe('justsearch://surface/core.library');
  });

  it('strips trailing punctuation', () => {
    const found = extractUrls('Go: justsearch://surface/core.library, then…');
    expect(found[0]?.url).toBe('justsearch://surface/core.library');
  });

  it('handles multiple URLs in document order', () => {
    const found = extractUrls(
      'First justsearch://surface/a then justsearch://op/b.',
    );
    expect(found.map((m) => m.url)).toEqual([
      'justsearch://surface/a',
      'justsearch://op/b',
    ]);
  });

  it('returns empty for non-string input', () => {
    // @ts-expect-error testing runtime type guard
    expect(extractUrls(null)).toEqual([]);
    expect(extractUrls('')).toEqual([]);
  });
});
