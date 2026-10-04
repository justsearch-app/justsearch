// @vitest-environment happy-dom

/**
 * REDUCED CAPABILITY, in the Search v3 window (inventory E1/E2/E3).
 *
 * Three claims are pinned here, each in BOTH directions so an inverted condition fails rather than
 * merely renders differently:
 *
 *  - E1 — a degraded readiness state produces exactly ONE compact banner with a working one-click
 *    remedy, and a healthy one produces none. The remedy is followed to the event it raises, not
 *    merely asserted present.
 *  - E2 — causes are deduped by reason CODE. The structural proof is that the projection RETURNS the
 *    codes: a wording-keyed implementation could not, so the assertions below cannot pass for the
 *    wrong reason.
 *  - E3 — the app-wide Simple/Detailed authority gates the banner's detail AND the answer frame's
 *    model, each checked in both modes.
 *
 * The projection is exercised against the SHARED readiness authority's own output — `readinessNotice`
 * words the headline and the causes, and the test compares against that rather than against literals,
 * so a wording change in the authority moves both at once.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SearchV3View.js';
import './Sv3Composer.js';
import './Sv3Main.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Composer } from './Sv3Composer.js';
import type { Sv3Main } from './Sv3Main.js';
import {
  projectSv3Degradation,
  sv3ComposerReason,
  sv3DegradationCauses,
  sv3RemedyReference,
  type Sv3Degradation,
} from './sv3-degradation.js';
import {
  OPEN_HEALTH,
  readinessNotice,
  reasonFor,
  type NoticeRemedy,
} from '../../state/readinessNotice.js';
import type { AiState } from '../../state/aiStateStore.js';
import {
  __feedContactForTest,
  __feedForTest,
  __resetAiStateForTest,
} from '../../state/aiStateStore.js';
import type { SystemHealthVerdict } from '../../state/verdict.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { resetSearchState } from '../../state/searchState.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { __resetUiModeForTest, setUiMode } from '../../state/uiModeState.js';
import { SV3_REMEDY, type Sv3RemedyDetail } from './sv3-honesty.js';
import { SV3_DEGRADATION_HEADLINE_ID } from './fixtures.js';
import type { Sv3Turn } from './sv3-sessions.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

/* ── The one input every projection case shares ──────────────────────────────────────────────── */

/** A snapshot whose ONLY relevant field is the verdict — which is all the projection reads. */
const snapshotFor = (verdict: SystemHealthVerdict): AiState =>
  ({ verdict }) as unknown as AiState;

const degraded = (
  reasons: readonly string[],
  severity: 'info' | 'warn' | 'error' = 'warn',
): SystemHealthVerdict => ({ kind: 'degraded', severity, reasons: [...reasons] });

const HEALTHY: SystemHealthVerdict = { kind: 'operational', severity: 'ok', reasons: [] };

/* ── E1: the banner exists exactly when capability is reduced ────────────────────────────────── */

describe('the window says when capability is reduced, and says nothing when it is not', () => {
  it('D1-14: projects the paused-semantic transition with its Health remedy', () => {
    const paused: SystemHealthVerdict = {
      kind: 'transitioning', severity: 'warn',
      reasons: ['rebuilding', 'source:embedding_model_change', 'index.embedding_rebuilding'],
    };
    const projection = projectSv3Degradation(snapshotFor(paused));
    expect(projection?.headline).toBe('Semantic search paused; keyword search available.');
    expect(projection?.causes.map((cause) => cause.code)).toEqual(['index.embedding_rebuilding']);
    expect(projection?.remedy).toEqual({ target: 'core.health-surface', label: 'Open Health' });
  });
  it('D1-14: projects the physical encoder reload through a generation switch', () => {
    const reloading: SystemHealthVerdict = {
      kind: 'transitioning',
      severity: 'info',
      reasons: ['generation-switch', 'encoders.reloading'],
    };
    const notice = readinessNotice(reloading)!;
    const projection = projectSv3Degradation(snapshotFor(reloading));
    expect(notice.headline).toBe('Semantic search paused; keyword search available.');
    expect(notice.body).toContain('Keyword search remains available');
    expect(notice.causes).toEqual(['Semantic search models are reloading']);
    expect(projection).toEqual({
      headline: notice.headline,
      body: notice.body,
      causes: [{ code: 'encoders.reloading', wording: 'Semantic search models are reloading' }],
      remedy: { target: 'core.health-surface', label: 'Open Health' },
      severity: 'info',
    });
  });
  it('projects a banner for a degraded verdict and NONE for a healthy one', () => {
    // The mutation probe: this pair fails if the condition inverts, in whichever direction.
    expect(projectSv3Degradation(snapshotFor(degraded(['encoders.health.embedding_not_ready'])))).not.toBeNull();
    expect(projectSv3Degradation(snapshotFor(HEALTHY))).toBeNull();
  });

  it('projects nothing at all before the store has reported', () => {
    expect(projectSv3Degradation(null)).toBeNull();
  });

  it('withholds the banner from a cosmetic info-severity gap, which Health carries calmly', () => {
    // The tier decision is the AUTHORITY's (`warrantsSearchDegradationBanner`), consumed rather than
    // re-derived — an `info` verdict still HAS a notice, so a local severity test could not tell
    // "there is nothing to say" from "this does not warrant warning-tier chrome".
    const verdict = degraded(['index.throughput_degraded'], 'info');
    expect(readinessNotice(verdict)).not.toBeNull();
    expect(projectSv3Degradation(snapshotFor(verdict))).toBeNull();
  });

  it('speaks the shared authority\'s wording, comparing against its own output rather than a literal', () => {
    const verdict = degraded(['encoders.health.embedding_not_ready']);
    const notice = readinessNotice(verdict)!;
    const projected = projectSv3Degradation(snapshotFor(verdict))!;
    expect(projected.headline).toBe(notice.headline);
    expect(projected.body).toBe(notice.body);
    expect(projected.causes.map((c) => c.wording)).toEqual(notice.causes);
    expect(projected.severity).toBe('warn');
  });

  it('carries a backend-disconnected verdict too — the loud state, never silence', () => {
    const projected = projectSv3Degradation(
      snapshotFor({ kind: 'unreachable', severity: 'error', reasons: ['binding.unreachable'] }),
    )!;
    expect(projected.severity).toBe('error');
    expect(projected.causes.map((c) => c.code)).toEqual(['binding.unreachable']);
  });
});

/* ── E1: the remedy is one click, and it points where the fix lives ──────────────────────────── */

describe('the remedy is a single navigation this window can perform', () => {
  it('passes a navigate remedy through verbatim', () => {
    const remedy: NoticeRemedy = { kind: 'navigate', target: 'core.library-surface', label: 'Open Library' };
    expect(sv3RemedyReference(remedy)).toEqual({
      target: 'core.library-surface',
      label: 'Open Library',
    });
  });

  it('points an OPERATION remedy at Health rather than firing it out from under its ceremony', () => {
    // The convention `jf-control` and `jf-capability-map` already share. The expectation is read
    // from the authority's own OPEN_HEALTH so this test cannot drift from it — and asserting the
    // real target proves the fallback is the derived one, not the empty placeholder.
    expect(OPEN_HEALTH.kind).toBe('navigate');
    const health = OPEN_HEALTH as { target: string; label: string };
    expect(sv3RemedyReference({ kind: 'operation', operationId: 'core.rebuild-index' })).toEqual({
      target: health.target,
      label: health.label,
    });
    expect(health.target).not.toBe('');
  });
});

/* ── E2: dedup keyed on the reason CODE ──────────────────────────────────────────────────────── */

describe('causes are deduped by reason code, not by wording', () => {
  it('collapses the SAME code reported twice into one entry, and keeps the code on it', () => {
    // Real shape, not a contrived one: a verdict's reasons are the concatenation of the `retrieval`
    // and `aiFeatures` composites' codes, so one condition reported on both axes arrives twice.
    const verdict = degraded(['inference.model_not_found', 'inference.model_not_found']);
    const causes = projectSv3Degradation(snapshotFor(verdict))!.causes;
    expect(causes).toHaveLength(1);
    // The structural half of the claim: the projection returns the CODE. Re-key this on wording and
    // this assertion cannot be satisfied at all.
    expect(causes[0]!.code).toBe('inference.model_not_found');
    expect(causes[0]!.wording).toBe(reasonFor('inference.model_not_found').wording);
  });

  it('keeps two DIFFERENT codes as two entries', () => {
    const verdict = degraded(['inference.model_not_found', 'inference.runtime_not_installed']);
    const causes = projectSv3Degradation(snapshotFor(verdict))!.causes;
    expect(causes.map((c) => c.code)).toEqual(['inference.model_not_found', 'inference.runtime_not_installed']);
  });

  it('drops a LONE cause that only restates the rebuild headline — keyed on isReindexCause', () => {
    const verdict = degraded(['index.blocked_legacy']);
    const projected = projectSv3Degradation(snapshotFor(verdict))!;
    // The headline already names the rebuild story, so the single bullet under it is chrome with no
    // information in it. The headline itself is untouched.
    expect(projected.headline).toBe(readinessNotice(verdict)!.headline);
    expect(projected.causes).toEqual([]);
  });

  it('keeps TWO rebuild causes, because then the list is telling the reader which ones', () => {
    const verdict = degraded(['index.blocked_legacy', 'index.embedding_mismatch']);
    expect(projectSv3Degradation(snapshotFor(verdict))!.causes).toHaveLength(2);
  });

  it('consumes the authority\'s cause SCOPING instead of re-deriving it', () => {
    // A rebuild headline speaks only for the causes a rebuild clears; `inference.model_not_found` is not one
    // of them and keeps its own row elsewhere, so it must not appear under this banner's remedy. Two
    // rebuild causes, so the lone-cause drop above is not what produces the answer.
    const verdict = degraded(['index.blocked_legacy', 'inference.model_not_found', 'index.embedding_mismatch']);
    const codes = projectSv3Degradation(snapshotFor(verdict))!.causes.map((c) => c.code);
    expect(codes).toEqual(['index.blocked_legacy', 'index.embedding_mismatch']);
    expect(readinessNotice(verdict)!.causes).toHaveLength(2);
  });

  it('drops even a SCOPED-DOWN lone rebuild cause, because the headline has already said it', () => {
    const verdict = degraded(['index.blocked_legacy', 'inference.model_not_found']);
    // The authority scoped two reasons down to one worded cause…
    expect(readinessNotice(verdict)!.causes).toHaveLength(1);
    // …and that one only restates the headline, so the list is empty rather than redundant.
    expect(projectSv3Degradation(snapshotFor(verdict))!.causes).toEqual([]);
  });

  it('drops a code the authority did not word into this branch, and words an unknown one honestly', () => {
    const verdict = degraded(['not.a.real.code']);
    const notice = readinessNotice(verdict)!;
    const causes = sv3DegradationCauses(verdict.reasons, notice);
    expect(causes).toEqual([{ code: 'not.a.real.code', wording: reasonFor('not.a.real.code').wording }]);
  });
});

/* ── The ONE banner slot: no status fact stands in it twice ──────────────────────────────────── */

describe('the composer slot holds one banner, never the same fact twice', () => {
  const bannerFor = (verdict: SystemHealthVerdict): Sv3Degradation =>
    projectSv3Degradation(snapshotFor(verdict))!;

  it('drops the availability reason when the banner already words its code', () => {
    const banner = bannerFor(degraded(['inference.model_not_found']));
    const reason = reasonFor('inference.model_not_found').wording;
    expect(banner.causes.map((c) => c.wording)).toContain(reason);
    expect(sv3ComposerReason(banner, reason)).toBe('');
  });

  it('KEEPS a reason the banner does not word — a refusal with no reason on screen is the worse failure', () => {
    const banner = bannerFor(degraded(['encoders.health.embedding_not_ready']));
    const reason = reasonFor('no_documents').wording;
    expect(sv3ComposerReason(banner, reason)).toBe(reason);
  });

  it('keeps the reason when there is no banner at all', () => {
    expect(sv3ComposerReason(null, 'Some reason')).toBe('Some reason');
    expect(sv3ComposerReason(bannerFor(degraded(['inference.model_not_found'])), '')).toBe('');
  });
});

/* ── E1/E3 in the DOM: one resting line, disclosure for the rest ─────────────────────────────── */

const q = (host: Mounted, testid: string): HTMLElement | null =>
  host.shadowRoot?.querySelector(`[data-testid="${testid}"]`) ?? null;

const all = (host: Mounted, testid: string): HTMLElement[] => [
  ...(host.shadowRoot?.querySelectorAll<HTMLElement>(`[data-testid="${testid}"]`) ?? []),
];

/**
 * Press a control the way a reader does — through the native button inside it. The banner's REMEDY is
 * born on the shared operability primitive (852 S4's jf-control adoption) and renders its button in
 * its own shadow root; the disclosure beside it is still a hand-rolled button (it carries
 * aria-expanded/aria-controls, which the primitive does not express) and is clicked directly.
 */
async function press(host: Element | null | undefined): Promise<void> {
  if (!host) throw new Error('press: no control');
  if (host.localName === 'button') {
    (host as HTMLButtonElement).click();
    return;
  }
  await (host as Mounted).updateComplete;
  const button = host.shadowRoot?.querySelector('button');
  if (!button) throw new Error('press: the control rendered no button');
  button.click();
}

const text = (el: HTMLElement | null): string => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

async function mountComposer(over: {
  degradation?: Sv3Degradation | null;
  detailed?: boolean;
  unavailableReason?: string;
}): Promise<Sv3Composer & Mounted> {
  const el = document.createElement('jf-sv3-composer') as Sv3Composer & Mounted;
  el.state = 'docked';
  el.degradation = over.degradation ?? null;
  el.detailed = over.detailed ?? false;
  el.unavailableReason = over.unavailableReason ?? '';
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('the banner rests at one line and discloses the rest', () => {
  const banner = (): Sv3Degradation =>
    projectSv3Degradation(snapshotFor(degraded(['encoders.health.embedding_not_ready'])))!;

  afterEach(() => {
    for (const child of [...document.body.children]) child.remove();
  });

  it('renders EXACTLY ONE banner for a degraded state and NONE for a healthy one', async () => {
    const shown = await mountComposer({ degradation: banner() });
    expect(all(shown, 'sv3-degradation')).toHaveLength(1);
    expect(all(shown, 'sv3-degradation-line')).toHaveLength(1);
    shown.remove();
    const quiet = await mountComposer({ degradation: null });
    expect(all(quiet, 'sv3-degradation')).toHaveLength(0);
  });

  it('rests the headline and the remedy, and nothing else', async () => {
    const el = await mountComposer({ degradation: banner() });
    expect(text(q(el, 'sv3-degradation-headline'))).toBe(banner().headline);
    expect(text(q(el, 'sv3-degradation-remedy'))).toBe(banner().remedy.label);
    // The elaboration is NOT in the resting tree — structural, so residue rendered at zero height
    // would still fail rather than merely look right.
    expect(q(el, 'sv3-degradation-detail')).toBeNull();
    expect(q(el, 'sv3-degradation-causes')).toBeNull();
    expect(q(el, 'sv3-degradation-body')).toBeNull();
  });

  it('takes the reader to the fix in ONE click, through the window\'s one remedy exit', async () => {
    const el = await mountComposer({ degradation: banner() });
    const seen: string[] = [];
    el.addEventListener(SV3_REMEDY, (e) => seen.push((e as CustomEvent<Sv3RemedyDetail>).detail.target));
    await press(q(el, 'sv3-degradation-remedy'));
    expect(seen).toEqual([banner().remedy.target]);
  });

  it('opens the causes on the disclosure, which is a real button and not a hover', async () => {
    const el = await mountComposer({ degradation: banner() });
    const toggle = q(el, 'sv3-degradation-disclosure') as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.click();
    await el.updateComplete;
    expect(q(el, 'sv3-degradation-disclosure')!.getAttribute('aria-expanded')).toBe('true');
    const causes = [...(q(el, 'sv3-degradation-causes')?.querySelectorAll('li') ?? [])];
    expect(causes.map((li) => li.getAttribute('data-code'))).toEqual(
      banner().causes.map((c) => c.code),
    );
    expect(text(q(el, 'sv3-degradation-body'))).toBe(banner().body);
  });

  it('names the banner from the send when the availability notice yielded its line', async () => {
    const reason = reasonFor('inference.model_not_found').wording;
    const withBanner = projectSv3Degradation(snapshotFor(degraded(['inference.model_not_found'])))!;
    const el = await mountComposer({ degradation: withBanner, unavailableReason: reason });
    // The notice yielded — one banner in the slot, and the same sentence is not rendered twice.
    expect(q(el, 'sv3-composer-notice')).toBeNull();
    expect(all(el, 'sv3-degradation')).toHaveLength(1);
    // ...and the refusal's explanation is still reachable from the control that refused.
    const field = el.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-input"]')!;
    field.value = 'why did the renewal fail?';
    field.dispatchEvent(new Event('input'));
    await el.updateComplete;
    expect(q(el, 'sv3-composer-send')!.getAttribute('aria-describedby')).toBe(
      SV3_DEGRADATION_HEADLINE_ID,
    );
  });

  it('keeps BOTH lines when they are different facts', async () => {
    const uncovered = reasonFor('no_documents').wording;
    const el = await mountComposer({ degradation: banner(), unavailableReason: uncovered });
    expect(text(q(el, 'sv3-composer-notice'))).toBe(uncovered);
    expect(all(el, 'sv3-degradation')).toHaveLength(1);
  });
});

/* ── 830 audit D1/D2/A6: what the measured audit found, pinned ───────────────────────────────── */

describe('the banner announces nothing and hides nothing (830 audit)', () => {
  const banner = (): Sv3Degradation =>
    projectSv3Degradation(snapshotFor(degraded(['encoders.health.embedding_not_ready'])))!;

  afterEach(() => {
    for (const child of [...document.body.children]) child.remove();
  });

  it('D1 — wraps its CONTROLS in no live region, in either disclosure state', async () => {
    // The measured defect: as `role="status"` the row was created together with its content (so the
    // appearance did not reliably announce) while the two buttons inside it re-announced the whole
    // concatenated line on every toggle. Both halves are pinned: no live region anywhere in the
    // banner, and specifically none containing either button.
    const el = await mountComposer({ degradation: banner() });
    const assertQuiet = (): void => {
      const root = q(el, 'sv3-degradation')!;
      const live = [
        ...root.querySelectorAll('[role="status"],[role="alert"],[role="log"],[aria-live]'),
      ];
      expect(live).toEqual([]);
      expect(root.getAttribute('role')).toBeNull();
      expect(root.getAttribute('aria-live')).toBeNull();
      for (const control of root.querySelectorAll('button')) {
        expect(control.closest('[aria-live],[role="status"],[role="alert"]')).toBeNull();
      }
    };
    assertQuiet();
    (q(el, 'sv3-degradation-disclosure') as HTMLButtonElement).click();
    await el.updateComplete;
    // The buttons the toggle re-rendered are still outside any live region.
    expect(q(el, 'sv3-degradation-detail')).not.toBeNull();
    assertQuiet();
  });

  it('D2 — the clipped headline keeps a sighted recovery route, and a whole accessible name', async () => {
    // The CSS ellipsis clips pixels, not text — so the assertion is on both halves of the claim the
    // stylesheet now makes: the element's text is the whole sentence AND `title` carries it.
    const el = await mountComposer({ degradation: banner() });
    const headline = q(el, 'sv3-degradation-headline')!;
    expect(headline.textContent?.trim()).toBe(banner().headline);
    expect(headline.getAttribute('title')).toBe(banner().headline);
  });

  it('A6 — the severity glyph is decorative, so it is hidden from assistive tech', async () => {
    const el = await mountComposer({ degradation: banner() });
    const mark = q(el, 'sv3-degradation')!.querySelector('.degradation-mark')!;
    expect(mark.getAttribute('aria-hidden')).toBe('true');
    // ...and the tone still lands on the element the severity rules select.
    expect(mark.querySelector('svg')).not.toBeNull();
  });
});

describe('Simple and Detailed decide how much banner (E3)', () => {
  const banner = (): Sv3Degradation =>
    projectSv3Degradation(snapshotFor(degraded(['encoders.health.embedding_not_ready'])))!;

  afterEach(() => {
    for (const child of [...document.body.children]) child.remove();
  });

  it('SIMPLE keeps the causes closed and offers the disclosure', async () => {
    const el = await mountComposer({ degradation: banner(), detailed: false });
    expect(q(el, 'sv3-degradation-causes')).toBeNull();
    expect(q(el, 'sv3-degradation-disclosure')).not.toBeNull();
  });

  it('DETAILED opens the causes and drops the disclosure, which would have nothing left to do', async () => {
    const el = await mountComposer({ degradation: banner(), detailed: true });
    expect(q(el, 'sv3-degradation-causes')).not.toBeNull();
    expect(q(el, 'sv3-degradation-disclosure')).toBeNull();
  });
});

/* ── E3: the same authority gates the frame line's metadata ──────────────────────────────────── */

describe('Simple and Detailed decide whether the frame line names the model (E3)', () => {
  const turn = (): Sv3Turn =>
    ({
      id: 't1',
      kind: 'ask',
      status: 'complete',
      query: 'why did the renewal fail?',
      answer: 'The lock held.',
      reasoning: [],
      evidence: null,
      durationMs: 45_700,
      modelLabel: 'Qwen3',
    }) as unknown as Sv3Turn;

  async function mountMain(detailed: boolean): Promise<Sv3Main & Mounted> {
    const el = document.createElement('jf-sv3-main') as Sv3Main & Mounted;
    el.state = 'docked';
    el.turns = [turn()];
    // A DIFFERENT current model, so the stamp is one the tail would otherwise re-state — the mode is
    // then the only thing deciding whether it appears.
    el.currentModelLabel = 'Llama-4';
    el.detailed = detailed;
    document.body.appendChild(el);
    await el.updateComplete;
    return el;
  }

  afterEach(() => {
    for (const child of [...document.body.children]) child.remove();
  });

  it('SIMPLE states the duration and NOT the model', async () => {
    const el = await mountMain(false);
    const frame = text(q(el, 'sv3-answer-frame'));
    expect(frame).toContain('45.7 s');
    expect(frame).not.toContain('Qwen3');
  });

  it('DETAILED states both', async () => {
    const el = await mountMain(true);
    const frame = text(q(el, 'sv3-answer-frame'));
    expect(frame).toContain('45.7 s');
    expect(frame).toContain('Qwen3');
  });
});

/* ── The window, end to end: the real store feeds the real banner ────────────────────────────── */

describe('the mounted window renders the banner from the observed-state authority', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    __resetConversationListForTest();
    __resetDraftProvidersForTest();
    __resetDraftKeptForTest();
    fetchMock = vi
      .fn()
      .mockResolvedValue({ ok: true, status: 200, body: null, json: async () => ({}) });
    vi.stubGlobal('fetch', fetchMock);
    __resetAiStateForTest();
  });

  afterEach(() => {
    for (const child of [...document.body.children]) child.remove();
    resetSearchState();
    __resetAiStateForTest();
    __resetUiModeForTest();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  /** A polled status whose `retrieval` composite is DEGRADED for the given reason codes. */
  function feedStatus(composites: Record<string, unknown>): void {
    __feedForTest({
      inference: { mode: 'online', available: true, activeModelId: 'Qwen3' } as never,
      status: {
        worker: { core: { indexedDocuments: 42 } },
        readiness: { composites },
      } as unknown as StatusSnapshot,
    });
    __feedContactForTest();
  }

  async function mountWindow(): Promise<SearchV3View & Mounted> {
    const el = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
    el.setAttribute('api-base', 'http://127.0.0.1:9999');
    document.body.appendChild(el);
    await el.updateComplete;
    return el;
  }

  async function composerOf(el: Mounted): Promise<Sv3Composer & Mounted> {
    const found = el.shadowRoot?.querySelector('jf-sv3-composer') as (Sv3Composer & Mounted) | null;
    if (!found) throw new Error('no composer in the window');
    await found.updateComplete;
    return found;
  }

  it('shows the banner for a degraded readiness composite and NOT for a ready one', async () => {
    feedStatus({ retrieval: { state: 'DEGRADED', reasonCodes: ['encoders.health.embedding_not_ready'] } });
    const el = await mountWindow();
    const composer = await composerOf(el);
    expect(el.aiSnapshot?.verdict.kind).toBe('degraded');
    expect(all(composer, 'sv3-degradation')).toHaveLength(1);
    expect(text(q(composer, 'sv3-degradation-headline'))).toBe(
      readinessNotice(el.aiSnapshot!.verdict)!.headline,
    );

    // The other direction, through the same seam: the composite goes READY and the line goes away.
    feedStatus({ retrieval: { state: 'READY', reasonCodes: [] } });
    await el.updateComplete;
    const healthy = await composerOf(el);
    expect(el.aiSnapshot?.verdict.kind).toBe('operational');
    expect(all(healthy, 'sv3-degradation')).toHaveLength(0);
  });

  it('follows the app-wide mode without a re-mount', async () => {
    feedStatus({ retrieval: { state: 'DEGRADED', reasonCodes: ['encoders.health.embedding_not_ready'] } });
    const el = await mountWindow();
    expect(q(await composerOf(el), 'sv3-degradation-causes')).toBeNull();

    setUiMode('advanced');
    await el.updateComplete;
    expect(q(await composerOf(el), 'sv3-degradation-causes')).not.toBeNull();

    setUiMode('simple');
    await el.updateComplete;
    expect(q(await composerOf(el), 'sv3-degradation-causes')).toBeNull();
  });
});
