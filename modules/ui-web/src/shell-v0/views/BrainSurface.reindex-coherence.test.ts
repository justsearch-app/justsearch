// @vitest-environment happy-dom

/**
 * Tempdoc 613 — coherence: the AI Brain compatibility callout must WORD its cause from the ONE
 * canonical reindex vocabulary (`reasonFor`/CAUSE_ROWS — the same the Chat degradation banner +
 * the 595 verdict use), not a hardcoded fork. These tests pin that the callout's lead equals the
 * canonical wording (so the two surfaces cannot drift) while the config-altitude technical detail
 * (the legacy/mismatch tag + the fingerprint hashes) is retained beneath it.
 */

import { describe, expect, it } from 'vitest';
import { render, type TemplateResult } from 'lit';
import './BrainSurface.js';

interface CalloutHarness {
  systemStatus: unknown;
  apiBase: string;
  _unifiedAiState: unknown;
  renderCompatibilityCallouts(): TemplateResult | symbol;
}

/** Build a detached BrainSurface (no connectedCallback ⇒ no poll/subscribe), drive the private
 *  callout renderer, and return its rendered text. */
function renderCallout(systemStatus: unknown, verdictReasons: string[]): HTMLDivElement {
  const el = document.createElement('jf-brain-surface') as unknown as CalloutHarness;
  el.systemStatus = systemStatus;
  el.apiBase = '';
  el._unifiedAiState = { verdict: { kind: 'degraded', reasons: verdictReasons } };
  const tpl = el.renderCompatibilityCallouts();
  const container = document.createElement('div');
  render(tpl as TemplateResult, container);
  return container;
}

function calloutText(systemStatus: unknown, verdictReasons: string[]): string {
  return renderCallout(systemStatus, verdictReasons).textContent ?? '';
}

/** The one-click reindex remedy the callout carries, or null when the callout did not render. */
function rebuildRemedy(systemStatus: unknown, verdictReasons: string[]): Element | null {
  return renderCallout(systemStatus, verdictReasons).querySelector(
    'jf-operation[operation-id="core.rebuild-index"]',
  );
}

const EMB_BLOCKED_LEGACY = {
  embedding: {
    compatState: 'BLOCKED_LEGACY',
    fingerprintStored: 'aaaaaaaaaaaa1111',
    fingerprintCurrent: 'bbbbbbbbbbbb2222',
  },
  schema: { compatState: 'COMPATIBLE' },
};

describe('BrainSurface compat callout — tempdoc 613 reindex coherence', () => {
  // Slice 7a (X7, AM9): on AI Brain the cause is said in read words, the agreed sentence (W1); the
  // canonical index wording stays the vocabulary of the other surfaces.
  it('leads with Brain\'s read words for the reindex cause', () => {
    const text = calloutText(EMB_BLOCKED_LEGACY, ['index.embedding_legacy']);
    expect(text).toContain('Read everything again to restore full search.');
    expect(text).not.toContain("Semantic search isn't available on this index yet — rebuild it to enable meaning-based results.");
  });

  it('drops the old hardcoded fork wording', () => {
    const text = calloutText(EMB_BLOCKED_LEGACY, ['index.embedding_legacy']);
    expect(text).not.toContain('before embedding fingerprinting was enabled');
  });

  it('retains the config-altitude technical detail (legacy tag + fingerprint hashes)', () => {
    const text = calloutText(EMB_BLOCKED_LEGACY, ['index.embedding_legacy']);
    expect(text).toContain('Embedding model fingerprint missing');
    expect(text).toContain('Stored:'); // fingerprint stored→current line retained
  });

  it('falls back to a generic remedy line when the verdict carries no reindex code (no per-cause fork)', () => {
    const text = calloutText(EMB_BLOCKED_LEGACY, []);
    expect(text).toContain('Read everything again to restore full search.'); // Slice 7a (AM9): read words.
  });
});

/**
 * Review 2026-08 (FE review-fix bundle, item 1) — the schema arm of this callout was gated on
 * `compatState === 'INCOMPATIBLE'`, a literal NO producer emits. The real vocabulary is
 * `COMPATIBLE | BLOCKED_LEGACY | BLOCKED_MISMATCH | REBUILDING | UNAVAILABLE`
 * (`indexing.proto` CompatibilityStatus.schema_compat_state; emitted by
 * `IndexStatusOps.safeSchemaCompatState()`), so the schema arm — and with it the 804 §D1
 * schema-mismatch remedy — was unreachable in the UI. These tests drive the REAL values through
 * the surface. They are written against the vocabulary, not the current gate: an arm re-gated on
 * a value the wire never carries fails them again.
 */
describe('BrainSurface compat callout — schema arm speaks the REAL compat vocabulary', () => {
  const schemaOnly = (compatState: string) => ({
    embedding: { compatState: 'COMPATIBLE' },
    schema: { compatState, reindexRequiredReason: 'schema_mismatch' },
  });

  it('BLOCKED_MISMATCH renders the callout with index.schema_mismatch in read words (Slice 7a X7)', () => {
    const text = calloutText(schemaOnly('BLOCKED_MISMATCH'), ['index.schema_mismatch']);
    // `StatusLifecycleHandler.compatBlockedReason` maps schema BLOCKED_MISMATCH → this code. Its meaning
    // stays (search works fully; reading again adds newer features), in read words.
    expect(text).not.toContain('The index format is out of date — search is fully working; rebuilding picks up newer index features.');
    expect(text).toMatch(/Search works fully/);
    expect(text).toMatch(/reading everything again/i);
    expect(text).toContain('Schema incompatible');
    expect(text).toContain('schema_mismatch'); // the reindexRequiredReason detail
  });

  it('BLOCKED_MISMATCH carries the core.rebuild-index remedy (the §D1 remedy is now reachable)', () => {
    expect(rebuildRemedy(schemaOnly('BLOCKED_MISMATCH'), ['index.schema_mismatch'])).not.toBeNull();
  });

  it('BLOCKED_LEGACY renders too, worded from its own code (index.blocked_legacy) in read words (Slice 7a X7)', () => {
    const text = calloutText(schemaOnly('BLOCKED_LEGACY'), ['index.blocked_legacy']);
    expect(text).toContain('Read everything again to restore full search.');
    expect(text).not.toContain('The index was built before semantic search was available — rebuild it to enable meaning-based results.');
    expect(rebuildRemedy(schemaOnly('BLOCKED_LEGACY'), ['index.blocked_legacy'])).not.toBeNull();
  });

  it('negative control: COMPATIBLE and REBUILDING render NO blocked callout and NO remedy', () => {
    for (const state of ['COMPATIBLE', 'REBUILDING', 'UNAVAILABLE']) {
      expect(calloutText(schemaOnly(state), ['index.schema_mismatch']), state).toBe('');
      expect(rebuildRemedy(schemaOnly(state), ['index.schema_mismatch']), state).toBeNull();
    }
  });

  it('negative control: the retired INCOMPATIBLE literal is not a gate any more', () => {
    // If someone re-introduces the dead literal as the gate, the two assertions above/below split:
    // the wire values would stop rendering and this one would start.
    expect(calloutText(schemaOnly('INCOMPATIBLE'), ['index.schema_mismatch'])).toBe('');
  });
});
