// @vitest-environment happy-dom
/**
 * Search mode's pure half (WP1.1): the mode list, the send hint each mode words, and the scope
 * derivations an "Ask about these results" is built from.
 *
 * No DOM. The window-level consequences (what a keystroke issues, where an ask goes, what Retry
 * keeps) live in `SearchV3View.searchMode.test.ts`.
 */
import { describe, it, expect } from 'vitest';
import {
  isSv3Tier,
  sv3PrimaryAction,
  SV3_STANDING_STOP_REASON,
  SV3_TIER_DEFAULT,
  SV3_TIER_OPTIONS,
} from './sv3-run.js';
import {
  sv3AskAboutLabel,
  sv3PruneSelection,
  sv3ResendScopeNote,
  sv3ResultsStale,
  sv3ScopeFromHits,
  sv3ScopeLabel,
} from './sv3-search.js';

const hit = (id: string, path: string): { id: string; path: string } => ({ id, path });

describe('S1.1 — the composer offers three modes, Search first and by default', () => {
  it('lists Search, Ask and Delegate, in that order', () => {
    expect(SV3_TIER_OPTIONS.map((option) => option.id)).toEqual(['search', 'ask', 'delegate']);
    expect(isSv3Tier('search')).toBe(true);
  });

  it('starts a fresh window in Search (S1.2) and badges it as the default', () => {
    expect(SV3_TIER_DEFAULT).toBe('search');
    expect(SV3_TIER_OPTIONS.filter((option) => option.isDefault).map((o) => o.id)).toEqual([
      'search',
    ]);
  });

  it('labels Search by what it does and makes no "no AI" claim', () => {
    const search = SV3_TIER_OPTIONS.find((option) => option.id === 'search');
    expect(search?.label).toBe('Search');
    expect(search?.description).toMatch(/matching documents/i);
    // The refined pass runs the backend's default pipeline, which expands the query with the chat
    // model when AI is on — so "no AI" would be a false claim.
    expect(`${search?.label} ${search?.description}`).not.toMatch(
      /\bno[\s-]+(AI|model)\b|\bwithout\s+(AI|a model)\b|\b(AI|model)[\s-]+free\b/i,
    );
  });
});

describe('S1.1 — the send hint in Search mode says what each key sends', () => {
  it('is always the search control, even while a run holds a decision or is running', () => {
    for (const pendingPrompt of [true, false]) {
      for (const running of [true, false]) {
        const slot = sv3PrimaryAction({ pendingPrompt, running, followUp: true, tier: 'search' });
        expect(slot.kind).toBe('send');
        expect(slot.reason).toMatch(/^Search · Enter searches/);
      }
    }
  });

  it('keeps the standing stop beside it while something is running — a search stops nothing', () => {
    const slot = sv3PrimaryAction({ pendingPrompt: false, running: true, followUp: true, tier: 'search' });
    expect(slot.haltReason).toBe(SV3_STANDING_STOP_REASON);
    expect(
      sv3PrimaryAction({ pendingPrompt: false, running: false, followUp: false, tier: 'search' })
        .haltReason,
    ).toBe('');
  });

  it('names the delegate chord only when the chord would actually delegate', () => {
    const idle = sv3PrimaryAction({ pendingPrompt: false, running: false, followUp: false, tier: 'search' });
    expect(idle.reason).toContain('Ctrl+Enter delegates to the agent');

    const noAgent = sv3PrimaryAction({
      pendingPrompt: false,
      running: false,
      followUp: false,
      tier: 'search',
      delegateReady: false,
    });
    expect(noAgent.reason).not.toContain('Ctrl+Enter');

    const busy = sv3PrimaryAction({ pendingPrompt: false, running: true, followUp: true, tier: 'search' });
    expect(busy.reason).not.toContain('Ctrl+Enter');

    const steerable = sv3PrimaryAction({
      pendingPrompt: false,
      running: true,
      followUp: true,
      tier: 'search',
      steerable: true,
    });
    expect(steerable.reason).toContain('Ctrl+Enter steers the running agent');

    const held = sv3PrimaryAction({
      pendingPrompt: true,
      running: true,
      followUp: true,
      tier: 'search',
      steerable: true,
    });
    expect(held.reason).not.toContain('Ctrl+Enter');
  });

  it('says a scoped Ask asks about the chosen files, and that the delegate chord leaves them behind', () => {
    const slot = sv3PrimaryAction({
      pendingPrompt: false,
      running: false,
      followUp: false,
      tier: 'ask',
      scoped: true,
    });
    expect(slot.kind).toBe('send');
    expect(slot.reason).toContain('Enter asks about the chosen files');
    expect(slot.reason).toContain('Ctrl+Enter delegates to the agent, without them');
  });
});

describe('S1.6 — the chosen set is documents, by path, never an empty or invalid set', () => {
  it('counts several hits from one document once', () => {
    const hits = [hit('a#1', 'docs/a.md'), hit('b#1', 'docs/b.md'), hit('a#2', 'docs/a.md')];
    // Membership and count only: which order the documents come in is not specified.
    expect([...(sv3ScopeFromHits(hits, ['a#1', 'a#2', 'b#1'])?.paths ?? [])].sort()).toEqual([
      'docs/a.md',
      'docs/b.md',
    ]);
  });

  it('takes only chosen rows, never the rest of the list', () => {
    const hits = [hit('a', 'docs/a.md'), hit('b', 'docs/b.md'), hit('c', 'docs/c.md')];
    expect(sv3ScopeFromHits(hits, ['c'])?.paths).toEqual(['docs/c.md']);
  });

  it('refuses an empty choice, ids that are not on screen, and hits with no path', () => {
    const hits = [hit('a', 'docs/a.md'), hit('chunk:1', '')];
    expect(sv3ScopeFromHits(hits, [])).toBeNull();
    expect(sv3ScopeFromHits(hits, ['gone'])).toBeNull();
    expect(sv3ScopeFromHits(hits, ['chunk:1'])).toBeNull();
  });

  it('drops chosen rows that left the list, and keeps the same rows when nothing left', () => {
    const chosen = new Set(['a', 'b']);
    const pruned = sv3PruneSelection(chosen, [hit('a', 'docs/a.md')]);
    expect([...pruned]).toEqual(['a']);
    const unchanged = sv3PruneSelection(chosen, [hit('a', 'docs/a.md'), hit('b', 'docs/b.md')]);
    expect([...unchanged].sort()).toEqual(['a', 'b']);
  });

  it('words the act and the scope by document count', () => {
    expect(sv3AskAboutLabel(1)).toBe('Ask about this file');
    expect(sv3AskAboutLabel(3)).toBe('Ask about these 3 files');
    expect(sv3ScopeLabel({ paths: ['docs/Northfield.pdf'] })).toBe('Northfield.pdf');
    const two = sv3ScopeLabel({ paths: ['docs/a.md', 'b.md'] });
    expect(two).toMatch(/^2 files: /);
    expect(two).toContain('a.md');
    expect(two).toContain('b.md');
  });
});

describe('S1.7 — a re-send says what it will search', () => {
  it('keeps the same files when the turn carries its scope, and says all files when it does not', () => {
    expect(sv3ResendScopeNote({ paths: ['a.md', 'b.md'] })).toBe('Uses the same 2 files');
    expect(sv3ResendScopeNote({ paths: ['a.md'] })).toBe('Uses the same file');
    expect(sv3ResendScopeNote(null)).toBe('Searches all your files');
  });
});

describe('S1.9 — results of an older query are never the current ones', () => {
  it('is stale while the store holds a newer query than the one its rows answered', () => {
    expect(sv3ResultsStale({ query: 'beta' }, 'alpha')).toBe(true);
    expect(sv3ResultsStale({ query: 'alpha ' }, 'alpha')).toBe(false);
    expect(sv3ResultsStale({ query: 'alpha' }, 'alpha')).toBe(false);
  });
});
