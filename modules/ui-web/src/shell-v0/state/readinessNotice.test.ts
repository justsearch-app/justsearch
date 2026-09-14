/**
 * Tempdoc 595 §4.2 — the verdict→notice projection (was 577 Ext III's
 * readiness→notice). Pins the contract: the search banner CONSUMES the one
 * SystemHealthVerdict (it does not re-read readiness.retrieval), renders for
 * `degraded` and (637 #1) `unreachable`, words causes from the verdict's reason codes (known specifically,
 * unknown generically — never dropped) with one remedy, and tracks the verdict's
 * SEVERITY so a cosmetic degradation is calm + accurate (§10.3).
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import {
  classifyConsequence,
  readinessNotice,
  reasonFor,
  severityForCodes,
  KEYWORD_FALLBACK_CAVEAT,
  PASSAGE_REDUCED_CAVEAT,
  OPTIONAL_CAPABILITY_CAVEAT,
  AI_UNAVAILABLE_CAVEAT,
  warrantsSearchDegradationBanner,
} from './readinessNotice.js';
import type { SystemHealthVerdict } from './verdict.js';

const degraded = (
  severity: SystemHealthVerdict['severity'],
  reasons: string[],
): SystemHealthVerdict => ({ kind: 'degraded', severity, reasons });

describe('readinessNotice (595 §4.2) — projects the ONE verdict into the search banner', () => {
  it('returns null for every non-rendering verdict (the banner does not render)', () => {
    for (const kind of ['operational', 'checking', 'connecting'] as const) {
      expect(readinessNotice({ kind, severity: 'info', reasons: [] })).toBeNull();
    }
    expect(
      readinessNotice({ kind: 'transitioning', severity: 'busy', reasons: ['rebuilding'] }),
    ).toBeNull();
  });

  it('637 #1: an UNREACHABLE verdict mints a LOUD disconnected notice (never a silent empty result)', () => {
    const n = readinessNotice({
      kind: 'unreachable',
      severity: 'error',
      reasons: ['binding.unreachable'],
    });
    expect(n).not.toBeNull();
    expect(n!.headline).toBe('Backend disconnected.');
    expect(n!.body).toContain('reconnecting automatically');
    expect(n!.causes).toEqual(['The connection to the search backend was lost']);
    // The backend is dead by definition, so a backend `operation` remedy would be pointless —
    // fall back to the always-actionable Open Health (the manual reload is in the body text).
    expect(n!.remedy).toEqual({
      kind: 'navigate',
      target: 'core.health-surface',
      label: 'Open Health',
    });
  });

  it('§10.3: a COSMETIC degradation (severity info) is worded CALMLY and accurately — no "keyword results"', () => {
    const n = readinessNotice(degraded('info', ['lambdamart.not_configured']));
    expect(n!.headline).toBe('Reduced search capability.');
    expect(n!.body).toContain('still fully semantic');
    expect(n!.body).not.toContain('keyword results');
    expect(n!.causes).toEqual(['Learned re-ranking (LambdaMART) is not configured']);
  });

  it('600 PART X: gpu.saturated words calmly (no raw code) — the secondary-cause leak is closed', () => {
    const n = readinessNotice(degraded('info', ['gpu.saturated']));
    expect(n!.causes).toContain('The GPU is busy; results may be slower');
    expect(n!.causes.join(' ')).not.toContain('Degraded: gpu.saturated'); // never the raw code
    // 804 §B5 amended the SECOND half of this case: alongside a reindex cause the banner now scopes
    // its list to causes a rebuild clears, so a secondary cause is OMITTED rather than mis-filed
    // under the Force-Rebuild remedy. PART X's guarantee — the user never sees a raw code — holds
    // either way, which is what this assertion pins.
    const alongsideReindex = readinessNotice(degraded('warn', ['index.blocked_legacy', 'gpu.saturated']));
    expect(alongsideReindex!.causes.join(' ')).not.toContain('Degraded: gpu.saturated');
  });

  it('an IMPAIRING degradation (severity warn) keeps the "Semantic search degraded / keyword results" wording', () => {
    const n = readinessNotice(degraded('warn', ['worker.health.embedding_not_ready']));
    expect(n!.headline).toBe('Semantic search degraded.');
    expect(n!.body).toContain('keyword results');
    expect(n!.causes).toEqual(['The semantic embedding index is not ready']);
    expect(n!.remedy).toEqual({
      kind: 'operation',
      operationId: 'core.trigger-offline-processing',
    });
  });

  it('compat reason code → reindex headline + rebuild remedy + the SPECIFIC cause worded (600 Design A)', () => {
    const n = readinessNotice(degraded('warn', ['index.blocked_legacy']));
    expect(n!.headline).toBe('Reindex required.');
    expect(n!.remedy).toEqual({ kind: 'operation', operationId: 'core.rebuild-index' });
    // Tempdoc 600's core fix: the causes slot now names the SPECIFIC cause (no longer empty).
    expect(n!.causes).toEqual([
      'The index was built before semantic search was available — rebuild it to enable meaning-based results.',
    ]);
  });

  // ===== Tempdoc 804 §D1 (sandbox round 10 F1) — the advisory schema state is NOT a degradation =====

  it('804 §D1: index.schema_mismatch ALONE is info + honest — never "keyword-only" / "degraded", rebuild remedy kept', () => {
    // The severity is the produce-side half of the fix: the code must rank `info`, otherwise the
    // verdict is degraded-warn and the banner takes the impairing branch no matter what it says.
    expect(severityForCodes(['index.schema_mismatch'])).toBe('info');

    const n = readinessNotice(degraded('info', ['index.schema_mismatch']));
    expect(n!.headline).toBe('Index format is out of date.');
    // Round 10's defect verbatim: dense retrieval was provably live under both these claims.
    expect(n!.body).not.toContain('keyword-only');
    expect(n!.body).not.toContain('degraded');
    expect(n!.headline).not.toContain('Reindex required');
    expect(n!.body).toContain('Search is fully working');
    expect(n!.body).toContain('semantic and keyword retrieval are active');
    // A rebuild IS what adopts the newer index format, so the remedy stays actionable.
    expect(n!.remedy).toEqual({ kind: 'operation', operationId: 'core.rebuild-index' });
    // The headline+body already ARE the cause; no bullet that restates them.
    expect(n!.causes).toEqual([]);
  });

  it('804 §D1: the advisory notice still lists OTHER info causes (fresh install: schema + LambdaMART)', () => {
    const n = readinessNotice(degraded('info', ['index.schema_mismatch', 'lambdamart.not_configured']));
    expect(n!.headline).toBe('Index format is out of date.');
    expect(n!.causes).toEqual(['Learned re-ranking (LambdaMART) is not configured']);
  });

  it('804 §D1: a DEGRADING reindex cause keeps the unchanged "Reindex required / keyword-only" wording', () => {
    const n = readinessNotice(degraded('warn', ['index.embedding_mismatch']));
    expect(n!.headline).toBe('Reindex required.');
    expect(n!.body).toBe(
      'Semantic search is degraded until the index is rebuilt — results may be keyword-only.',
    );
    expect(n!.causes).toEqual([
      'The embedding model changed — rebuild the index to restore semantic search.',
    ]);
    expect(n!.remedy).toEqual({ kind: 'operation', operationId: 'core.rebuild-index' });
  });

  it('804 §B5: the reindex branch scopes its cause list to REINDEX causes only (no rebuild-proof causes)', () => {
    const n = readinessNotice(
      degraded('warn', [
        'index.embedding_mismatch',
        'index.schema_mismatch',
        'lambdamart.not_configured',
        'inference.offline',
      ]),
    );
    expect(n!.headline).toBe('Reindex required.');
    // Only the cause a rebuild actually clears is listed under the one Force-Rebuild remedy. The
    // advisory schema code and the two rebuild-proof causes are NOT presented as reindex causes.
    expect(n!.causes).toEqual([
      'The embedding model changed — rebuild the index to restore semantic search.',
    ]);
    expect(n!.causes.join(' ')).not.toContain('LambdaMART');
    expect(n!.causes.join(' ')).not.toContain('local AI model is offline');
    expect(n!.causes.join(' ')).not.toContain('index format is out of date');
  });

  // ===== Tempdoc 804 §B5 (live round 11) — an impairing verdict is not automatically a RETRIEVAL
  // degradation: the body is selected by cause CLASS, never by severity alone. =====

  it('804 §B5: inference.offline ALONE words as AI-features-off — never "keyword results"', () => {
    const n = readinessNotice(degraded('warn', ['inference.offline']));
    expect(n!.headline).toBe('AI features unavailable.');
    // The round-11 defect verbatim: dense retrieval + the cross-encoder were live under this claim.
    // (The honest body does name keyword retrieval — as ACTIVE; what must be gone is the fallback
    // claim "Showing keyword results", i.e. keyword INSTEAD of semantic.)
    expect(n!.body).not.toContain('keyword results');
    expect(n!.body).not.toContain('Showing keyword');
    expect(n!.headline).not.toContain('Semantic search degraded');
    expect(n!.body).toContain('Search is fully working');
    expect(n!.body).toContain('semantic and keyword retrieval are active');
    expect(n!.causes).toEqual(['The local AI model is offline']);
    // Causes + remedy behave exactly as the impairing branch always did.
    expect(n!.remedy).toEqual({ kind: 'operation', operationId: 'core.reload-inference' });
  });

  it('804 §B5: the live-observed pair (inference.offline + lambdamart) words as AI-features-off', () => {
    const n = readinessNotice(degraded('warn', ['lambdamart.not_configured', 'inference.offline']));
    expect(n!.headline).toBe('AI features unavailable.');
    expect(n!.body).not.toContain('keyword results');
    // RE-PINNED, tempdoc 805 §G.2: the AI branch now SCOPES its cause list to AI codes, the same way
    // the reindex branch scopes to rebuild-clearable causes (804 §B5). Pre-805 the LambdaMART row was
    // listed here too — under the "Chat and answer features are unavailable" consequence and the
    // reload-inference remedy, neither of which it belongs to. It keeps its own calm branch when it is
    // the whole story (see the §10.3 cosmetic test above).
    expect(n!.causes).toEqual(['The local AI model is offline']);
    expect(n!.causes.join(' ')).not.toContain('LambdaMART');
    expect(n!.remedy).toEqual({ kind: 'operation', operationId: 'core.reload-inference' });
  });

  it('804 §B5: a RETRIEVAL-impairing cause alongside the AI cause keeps the "keyword results" wording', () => {
    const n = readinessNotice(
      degraded('warn', ['worker.health.embedding_not_ready', 'inference.offline']),
    );
    expect(n!.headline).toBe('Semantic search degraded.');
    expect(n!.body).toContain('Showing keyword results');
    expect(n!.body).not.toContain('Search is fully working');
  });

  it('804 §B5: an UNCLASSIFIED code never acquires the reassuring "fully working" claim', () => {
    // The calm wording is an assertion about retrieval health; a code we cannot classify is not
    // evidence for it (same doctrine as severityForCodes' unknown ⇒ warn default).
    const n = readinessNotice(degraded('warn', ['inference.offline', 'some.future.code']));
    expect(n!.headline).toBe('Semantic search degraded.');
    expect(n!.body).not.toContain('Search is fully working');
  });

  it('an unknown code words generically and falls back to Open Health — never silence', () => {
    const n = readinessNotice(degraded('warn', ['some.future.code']));
    expect(n!.causes).toEqual(['Degraded: some.future.code']);
    expect(n!.remedy).toEqual({
      kind: 'navigate',
      target: 'core.health-surface',
      label: 'Open Health',
    });
  });

  // ===== Tempdoc 805 §G.2 (live round 11) — the PASSAGE class. Round 11's banner claimed "Showing
  // keyword results" while the build's own search trace showed dense retrieval AND the cross-encoder
  // executing: the only thing actually reduced was the PASSAGE leg (chunk vectors absent). =====

  it('805 §G.2: the round-11 pair (chunk_embedding.not_ready + lambdamart) takes the PASSAGE branch', () => {
    // The exact live codes + their derived verdict severity (chunk_embedding.not_ready has no row
    // severity ⇒ warn; lambdamart is info) — so this is the impairing-severity path, and only the
    // cause CLASS keeps it off the keyword-fallback claim.
    expect(severityForCodes(['chunk_embedding.not_ready', 'lambdamart.not_configured'])).toBe('warn');

    const n = readinessNotice(degraded('warn', ['chunk_embedding.not_ready', 'lambdamart.not_configured']));
    expect(n!.headline).toBe('Semantic search partially degraded.');
    // The round-11 defect verbatim: this claim was false against the trace.
    expect(n!.body).not.toContain('keyword results');
    expect(n!.body).not.toContain('Showing keyword');
    expect(n!.headline).not.toBe('Semantic search degraded.');
    // The measured truth: the passage leg is reduced, document-level semantic ranking still serves.
    expect(n!.body).toContain('Passage-level precision is reduced');
    expect(n!.body).toContain('still ranked semantically');
    // Cause SCOPING: only the passage cause is presented under the passage consequence.
    expect(n!.causes).toEqual(['Passage embeddings are not ready']);
    expect(n!.causes.join(' ')).not.toContain('LambdaMART');
    // The remedy is the one that actually computes passage embeddings (CAUSE_ROWS' chunk rows).
    expect(n!.remedy).toEqual({ kind: 'operation', operationId: 'core.trigger-offline-processing' });
  });

  it('805 §G.2: chunk_embedding.in_progress is the passage class too (no remedy ⇒ Open Health)', () => {
    // `in_progress` is severity `info`, so it reaches this branch only alongside a warn-severity
    // cause — paired here with `ocr.disabled` (warn, recognized, non-retrieval) so the fixture's
    // severity is the one `severityForCodes` would actually derive, not an invented one.
    const codes = ['chunk_embedding.in_progress', 'ocr.disabled'];
    expect(severityForCodes(codes)).toBe('warn');

    const n = readinessNotice(degraded('warn', codes));
    expect(n!.headline).toBe('Semantic search partially degraded.');
    expect(n!.body).not.toContain('keyword results');
    // Cause SCOPING: the OCR cause is not filed under the passage consequence.
    expect(n!.causes).toEqual(['Passage embeddings are still being computed']);
    // In-progress carries no remedy row ⇒ the always-actionable Open Health reference (and the
    // scoping means it does NOT inherit some other cause's remedy either).
    expect(n!.remedy).toEqual({ kind: 'navigate', target: 'core.health-surface', label: 'Open Health' });
  });

  it('805 §G.2: a real retrieval block ALONGSIDE the passage gap outranks it (keyword wording returns)', () => {
    const n = readinessNotice(degraded('warn', ['chunk_embedding.not_ready', 'index.dense_unavailable']));
    expect(n!.headline).toBe('Semantic search degraded.');
    expect(n!.body).toContain('Showing keyword results');
    // Impairing-branch scoping: the passage cause is not filed under the retrieval-fallback claim.
    expect(n!.causes).toEqual(['Semantic search is unavailable right now — showing keyword results.']);
  });

  it('805 §G.2: an UNCLASSIFIED code alongside the passage gap stays conservative (never the calmer claim)', () => {
    const n = readinessNotice(degraded('warn', ['chunk_embedding.not_ready', 'some.future.code']));
    expect(n!.headline).toBe('Semantic search degraded.');
    expect(n!.causes).toEqual(['Degraded: some.future.code']);
  });

  it('805 §G.2: a passage gap alongside an AI cause words as PASSAGE (passage outranks ai-unavailable)', () => {
    const n = readinessNotice(degraded('warn', ['chunk_embedding.not_ready', 'inference.offline']));
    expect(n!.headline).toBe('Semantic search partially degraded.');
    // The AI branch's "Search is fully working" would over-claim while passage vectors are missing.
    expect(n!.body).not.toContain('Search is fully working');
    expect(n!.causes).toEqual(['Passage embeddings are not ready']);
  });

  it('804 §B5 + 805 §G.2: the AI branch scopes its cause list to AI codes only', () => {
    const n = readinessNotice(degraded('warn', ['ocr.disabled', 'inference.offline']));
    expect(n!.headline).toBe('AI features unavailable.');
    expect(n!.causes).toEqual(['The local AI model is offline']);
    expect(n!.causes.join(' ')).not.toContain('OCR');
  });

  it('598 reopen (B-3): index.dense_unavailable is severity warn (can never render "fully semantic") and words as capability-OFF', () => {
    // Produce→verdict severity: a dense-block a rebuild does NOT fix (no embedding model / embedder
    // down on a COMPATIBLE index) must be `warn`, so the verdict is degraded-warn and the banner can
    // NEVER take the `info` "still fully semantic" branch — the B-3 over-claim hole this code closes.
    expect(severityForCodes(['index.dense_unavailable'])).toBe('warn');

    const n = readinessNotice(degraded('warn', ['index.dense_unavailable']));
    // NOT a reindex cause (a reindex won't add a missing/unloaded embedder) → the impairing headline,
    // not "Reindex required".
    expect(n!.headline).toBe('Semantic search degraded.');
    expect(n!.body).toContain('keyword results');
    expect(n!.body).not.toContain('fully semantic');
    expect(n!.causes).toEqual(['Semantic search is unavailable right now — showing keyword results.']);
    // No false one-click rebuild remedy (the model isn't loaded) → the always-actionable Open Health ref.
    expect(n!.remedy).toEqual({ kind: 'navigate', target: 'core.health-surface', label: 'Open Health' });
  });
});

/**
 * Tempdoc 805 §G.2 — the ONE consequence classifier every degradation claim consumes (this module's
 * banner, availability.ts's affordance caveat). Round 11 found the keyword-fallback claim re-derived
 * from SEVERITY in two modules at once, both contradicting the same search trace.
 */
describe('classifyConsequence (805 §G.2)', () => {
  it('retrieval-impaired: a positively-known dense block', () => {
    expect(classifyConsequence(['index.dense_unavailable'])).toBe('retrieval-impaired');
    expect(classifyConsequence(['worker.health.embedding_not_ready'])).toBe('retrieval-impaired');
    expect(classifyConsequence(['worker.health.embedding_probe_missing'])).toBe('retrieval-impaired');
    // (837 S6 retired `index.rebuilding`: a generation rebuild is a transition, not a degradation,
    // so it can never reach this classifier. `index.embedding_rebuilding` — the in-place rebuild
    // that leaves stability settled — is the one that genuinely does.)
    expect(classifyConsequence(['index.embedding_rebuilding'])).toBe('retrieval-impaired');
    expect(classifyConsequence(['worker.spawn.failed'])).toBe('retrieval-impaired');
    // The reindex causes are retrieval-impairing too (the banner returns earlier for them, but the
    // classifier is consumed by other surfaces that have no reindex branch).
    expect(classifyConsequence(['index.embedding_mismatch'])).toBe('retrieval-impaired');
  });

  it('passage-reduced: the chunk-embedding codes, which are NOT a keyword fallback', () => {
    // The membership move this workstream exists for: pre-805 both codes sat in
    // RETRIEVAL_IMPAIRING_CODES, which is how a passage-vector gap licensed "Showing keyword results"
    // over a trace with dense retrieval + the cross-encoder live (round 11, tempdoc 734 R11-F1).
    expect(classifyConsequence(['chunk_embedding.not_ready'])).toBe('passage-reduced');
    expect(classifyConsequence(['chunk_embedding.in_progress'])).toBe('passage-reduced');
  });

  it('ai-unavailable: the AI-model codes, retrieval untouched', () => {
    expect(classifyConsequence(['inference.offline'])).toBe('ai-unavailable');
    expect(classifyConsequence(['inference.model_not_configured'])).toBe('ai-unavailable');
    expect(classifyConsequence(['inference.activation_failed'])).toBe('ai-unavailable');
  });

  it('cosmetic: a recognized cause that touches neither retrieval nor the AI model', () => {
    expect(classifyConsequence(['lambdamart.not_configured'])).toBe('cosmetic');
    expect(classifyConsequence(['gpu.saturated', 'index.schema_mismatch'])).toBe('cosmetic');
    expect(classifyConsequence(['ocr.disabled'])).toBe('cosmetic');
  });

  it('unknown: an unrecognized code, and an EMPTY list, are never evidence of health', () => {
    expect(classifyConsequence(['some.future.code'])).toBe('unknown');
    // Empty ⇒ unknown, mirroring severityForCodes' empty ⇒ warn: no codes is not a calm claim.
    expect(classifyConsequence([])).toBe('unknown');
  });

  it('precedence: retrieval-impaired > unknown > passage-reduced > ai-unavailable > cosmetic', () => {
    // Top: a positively-known retrieval block wins over every other class present.
    expect(
      classifyConsequence([
        'lambdamart.not_configured',
        'inference.offline',
        'chunk_embedding.not_ready',
        'some.future.code',
        'index.dense_unavailable',
      ]),
    ).toBe('retrieval-impaired');
    // An unrecognized code outranks the three CALMER classes — it cannot license a claim we can't back.
    expect(classifyConsequence(['chunk_embedding.not_ready', 'some.future.code'])).toBe('unknown');
    expect(classifyConsequence(['inference.offline', 'some.future.code'])).toBe('unknown');
    expect(classifyConsequence(['lambdamart.not_configured', 'some.future.code'])).toBe('unknown');
    // Then passage over AI, and AI over cosmetic.
    expect(classifyConsequence(['inference.offline', 'chunk_embedding.in_progress'])).toBe('passage-reduced');
    expect(classifyConsequence(['lambdamart.not_configured', 'inference.offline'])).toBe('ai-unavailable');
  });

  it('the exported caveats carry the claim wording so no second module re-authors it', () => {
    // The gate (scripts/ci/check-consequence-classification.mjs) enforces the containment; this pins
    // that the caveats say what their class licenses.
    expect(KEYWORD_FALLBACK_CAVEAT).toContain('keyword-ranked results');
    expect(PASSAGE_REDUCED_CAVEAT).not.toContain('keyword');
    expect(PASSAGE_REDUCED_CAVEAT).toContain('still ranked semantically');
    expect(OPTIONAL_CAPABILITY_CAVEAT).not.toContain('keyword');
    // Round-14 finding 8 — the calm caveat NAMES its feature (the pre-fix "an optional ranking
    // model" named none and was resolved to the wrong one), and the AI-model class keeps a
    // separate wording so it never borrows a feature name its cause is not about.
    expect(OPTIONAL_CAPABILITY_CAVEAT).toContain('Learned re-ranking (LambdaMART)');
    expect(AI_UNAVAILABLE_CAVEAT).not.toContain('LambdaMART');
    expect(AI_UNAVAILABLE_CAVEAT).not.toContain('keyword');
  });
});

describe('warrantsSearchDegradationBanner (round-14 finding 9)', () => {
  it('an info-severity-only verdict warrants NO banner-tier warning', () => {
    expect(warrantsSearchDegradationBanner(degraded('info', ['lambdamart.not_configured']))).toBe(false);
  });

  it('…while its notice still exists, so Health keeps carrying the cause', () => {
    const n = readinessNotice(degraded('info', ['lambdamart.not_configured']));
    expect(n).not.toBeNull();
    expect(n!.causes).toEqual(['Learned re-ranking (LambdaMART) is not configured']);
  });

  it('warn / error / unreachable keep the banner (the gate is severity, not "no banner ever")', () => {
    expect(warrantsSearchDegradationBanner(degraded('warn', ['worker.health.embedding_not_ready']))).toBe(true);
    expect(warrantsSearchDegradationBanner(degraded('error', ['worker.spawn_recovery_exhausted']))).toBe(true);
    expect(
      warrantsSearchDegradationBanner({ kind: 'unreachable', severity: 'error', reasons: ['binding.unreachable'] }),
    ).toBe(true);
  });

  it('an operational verdict warrants nothing (no notice to show)', () => {
    expect(warrantsSearchDegradationBanner({ kind: 'operational', severity: 'ok', reasons: [] })).toBe(false);
  });
});

/**
 * Sandbox round 7 — the FIRST test in this suite (and in `shell-v0`) that asserts a remedy's
 * `navigate` target against the surface that actually OWNS the capability, rather than against a
 * hardcoded expected id. That distinction is the whole point: `conversations.locked` pointed at
 * `core.settings-surface` for as long as it existed, because tempdoc 629 moved `unlockEncryption()`
 * out of Settings into the Security surface and nothing anywhere re-checked the remedy against it.
 * An id-vs-id assertion would have been updated to match the wrong id and stayed green forever.
 *
 * So the expectation is DERIVED from source: find the module that defines the unlock control, read
 * the custom-element tag it registers, and resolve that tag back to its surface id through
 * CorePlugin's registration table. The remedy must land on that id.
 */
describe('remedy targets resolve to the surface that owns the capability', () => {
  const here = dirname(fileURLToPath(import.meta.url));
  const read = (rel: string) => readFileSync(resolve(here, rel), 'utf8');

  /** The surface id whose `mountTag` is `tag`, per CorePlugin's registration table. */
  function surfaceIdForMountTag(corePluginSrc: string, tag: string): string | null {
    // Registration blocks are `{ id: 'core.x-surface', mountTag: 'jf-x-surface', ... }`; scan for
    // the id that precedes the given mountTag within the same block.
    const re = /id:\s*'(core\.[^']+)'(?:(?!id:\s*')[\s\S])*?mountTag:\s*'([^']+)'/g;
    for (const m of corePluginSrc.matchAll(re)) {
      if (m[2] === tag) return m[1]!;
    }
    return null;
  }

  it('conversations.locked → the surface that defines unlockEncryption(), not Settings', () => {
    const securitySrc = read('../views/SecuritySurface.ts');
    const settingsSrc = read('../views/SettingsSurface.ts');
    const corePluginSrc = read('../plugin-api/CorePlugin.ts');

    // 1. Establish the capability owner from source, not from memory.
    expect(securitySrc).toMatch(/unlockEncryption\s*\(/);
    expect(settingsSrc).not.toMatch(/unlockEncryption\s*\(/);

    // 2. Resolve that module's registered element tag to its surface id.
    const tagMatch = /customElements\.define\('([^']+)',\s*SecuritySurface\)/.exec(securitySrc);
    expect(tagMatch, 'SecuritySurface must register a custom element').not.toBeNull();
    const ownerSurfaceId = surfaceIdForMountTag(corePluginSrc, tagMatch![1]!);
    expect(ownerSurfaceId, `no CorePlugin surface mounts <${tagMatch![1]}>`).not.toBeNull();

    // 3. The remedy the locked-chat affordance offers must navigate THERE.
    expect(reasonFor('conversations.locked').remedy).toEqual({
      kind: 'navigate',
      target: ownerSurfaceId,
      label: 'Unlock in Security',
    });
  });
});

/**
 * Tempdoc 837 S3/S4 — the codes that used to be prose, or used to be collapsed onto a sibling.
 * Each row is asserted on the bar the tempdoc set for itself: the wording must be TRUE in the state
 * that emits it, and the consequence-set membership must be a decision, not an omission (omission
 * silently selects `cosmetic` for an AI code and `impairing` for everything else).
 */
describe('readinessNotice — tempdoc 837 worker + inference rows', () => {
  it('worker.lost words the state it is actually emitted in (it was serving, then stopped)', () => {
    expect(reasonFor('worker.lost').wording).toBe(
      'The knowledge server stopped responding and does not restart itself — restart JustSearch to recover it',
    );
    // The collapsed code claimed a start failure for a worker that had started fine.
    expect(reasonFor('worker.spawn.failed').wording).toContain('failed to start');
    // 837 S3's bar, restated at its own intent rather than by substring. This assertion used to be
    // `not.toContain('start')`, which lane F stage A item A11 turned into a false negative: the row
    // now has to say "restart JustSearch" (A11 deleted the supervisor that used to restart it), and
    // "restart" contains "start". The PROPERTY 837 set — never claim a START FAILURE for a server
    // that started fine and then died — is unchanged and is what is asserted here. Tightened, not
    // weakened: a bare substring also passed on "the server did not start", which this rejects.
    expect(reasonFor('worker.lost').wording).not.toMatch(
      /failed to start|could not start|did not start|never started/i,
    );
  });

  it('worker.lost tells the user to restart, because nothing restarts it for them (A11)', () => {
    // The load-bearing half of the row after lane F stage A item A11 deleted crash detection and
    // the restart budget: a stopped Engine stays stopped. If this ever reverts to a bare "stopped
    // responding", the notice silently reads as "wait, it is coming back" — which it is not, until
    // stage B restores supervision. Then this assertion should be deleted WITH that restoration,
    // not before it.
    expect(reasonFor('worker.lost').wording).toMatch(/restart JustSearch/);
    const n = readinessNotice(degraded('error', ['worker.lost']));
    expect(n!.causes).toContain(reasonFor('worker.lost').wording);
  });

  it('worker.index_corrupt carries no fake one-click remedy — Open Health, like its precedent', () => {
    const r = reasonFor('worker.index_corrupt');
    expect(r.wording).toBe('The search index is corrupt and could not be repaired automatically');
    // No operation exists for index.recovery.policy=BACKUP_REBUILD, so the row must not invent one:
    // it declares NO remedy (the vdu.missing_mmproj precedent) and the banner supplies Open Health.
    expect(r.remedy).toBeUndefined();
    const n = readinessNotice(degraded('error', ['worker.index_corrupt']));
    expect(n!.remedy).toEqual({ kind: 'navigate', target: 'core.health-surface', label: 'Open Health' });
  });

  it('the non-serving worker codes are all retrieval-impairing (omission would over-claim)', () => {
    for (const code of [
      'worker.lost',
      'worker.index_corrupt',
      'worker.shut_down',
      'worker.not_connected',
    ]) {
      expect(classifyConsequence([code]), code).toBe('retrieval-impaired');
    }
  });

  it('825: the boot-recovery terminal code is worded apart from the pin it succeeds', () => {
    const r = reasonFor('worker.spawn_recovery_exhausted');
    expect(r.wording).toBe('The knowledge server failed to start and could not be recovered');
    // `worker.spawn.failed` now means "failed, recovery pending or in flight" — its wording must not
    // read as a dead end while the Head is still re-attempting, and this one must.
    expect(reasonFor('worker.spawn.failed').wording).not.toContain('could not be recovered');
    expect(severityForCodes(['worker.spawn_recovery_exhausted'])).toBe('error');
    expect(classifyConsequence(['worker.spawn_recovery_exhausted'])).toBe('retrieval-impaired');
    // No one-click remedy: a respawn is exactly what just failed its whole budget.
    expect(r.remedy).toBeUndefined();
    expect(warrantsSearchDegradationBanner(degraded('error', ['worker.spawn_recovery_exhausted']))).toBe(
      true,
    );
  });

  it('severity: a lost/corrupt server is error; a shutdown or a not-yet-connected one is calm', () => {
    expect(severityForCodes(['worker.lost'])).toBe('error');
    expect(severityForCodes(['worker.index_corrupt'])).toBe('error');
    expect(severityForCodes(['worker.shut_down'])).toBe('info');
    expect(severityForCodes(['worker.not_connected'])).toBe('info');
    expect(warrantsSearchDegradationBanner(degraded('error', ['worker.lost']))).toBe(true);
  });

  it('S4: the two new inference codes are calm and stay OUT of the banner', () => {
    expect(reasonFor('inference.gpu_yielded_to_indexing').wording).toBe(
      'The GPU is indexing your files — chat resumes when it finishes',
    );
    expect(reasonFor('inference.up_for_background').wording).toBe(
      'Chat is turned off; the AI engine is running background document processing',
    );
    expect(severityForCodes(['inference.gpu_yielded_to_indexing'])).toBe('info');
    expect(severityForCodes(['inference.up_for_background'])).toBe('info');
    expect(
      warrantsSearchDegradationBanner(degraded('info', ['inference.gpu_yielded_to_indexing'])),
    ).toBe(false);
  });

  it('S4: neither new inference code joins AI_MODEL_UNAVAILABLE_CODES (not "any inference.*")', () => {
    // up_for_background describes an engine that is UP; gpu_yielded is a scheduled, self-clearing
    // hand-off. Classifying either as "the AI model is unavailable" would repeat, in the other
    // direction, the exact over-claim this tempdoc exists to remove.
    expect(classifyConsequence(['inference.gpu_yielded_to_indexing'])).not.toBe('ai-unavailable');
    expect(classifyConsequence(['inference.up_for_background'])).not.toBe('ai-unavailable');
    // …while the genuinely-unavailable sibling still classifies that way.
    expect(classifyConsequence(['inference.offline'])).toBe('ai-unavailable');
  });

  it('S5: a crash and a deactivation word DIFFERENT truths (the collapse this slice removes)', () => {
    expect(reasonFor('inference.crashed').wording).toBe('The local AI model stopped unexpectedly');
    expect(reasonFor('inference.deactivated').wording).toBe('The local AI model is turned off');
    // Nobody chose the crash: it is a warn with a reload remedy. The deactivation IS the choice.
    expect(severityForCodes(['inference.crashed'])).toBe('warn');
    expect(severityForCodes(['inference.deactivated'])).toBe('info');
    expect(reasonFor('inference.crashed').remedy).toEqual({
      kind: 'operation',
      operationId: 'core.reload-inference',
    });
  });

  it('S5: inference.crashed MUST join AI_MODEL_UNAVAILABLE_CODES — the `cosmetic` trap', () => {
    // A recognized `warn` row in NONE of the consequence sets falls through to `cosmetic`, and the
    // banner then says "An optional capability is unavailable; results are still fully semantic"
    // about a crashed AI model. Membership is the mandatory companion edit, not a nicety.
    expect(classifyConsequence(['inference.crashed'])).toBe('ai-unavailable');
    const n = readinessNotice(degraded('warn', ['inference.crashed']));
    expect(n!.headline).toBe('AI features unavailable.');
    expect(n!.body).toContain('Search is fully working');
    expect(n!.body).toContain('Chat and answer features are unavailable');
  });

  it('S5: inference.deactivated does NOT join it — it is the policy/user-choice case', () => {
    // The set's own doctrine excludes inference.policy_* ("policy-disabled never comes online").
    // A deliberate switch-off is that case, and it ships at `info` where the set is never consulted.
    expect(classifyConsequence(['inference.deactivated'])).not.toBe('ai-unavailable');
    expect(warrantsSearchDegradationBanner(degraded('info', ['inference.deactivated']))).toBe(false);
  });
});
