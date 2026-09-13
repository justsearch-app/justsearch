// SPDX-License-Identifier: Apache-2.0
/**
 * DocumentPane (`jf-document-pane`) — Search Thread stage S6 prep (tempdoc "Reading Stage").
 *
 * A standalone, integration-ready reading surface for a single document, addressable by a passage
 * (`highlightRange`) with an optional wider containing chunk (`chunkRange`). Built ahead of the
 * serialized integration stage — see the file-level report for what a future integration pass still
 * needs to wire (consumer registration, event handling in the host surface).
 *
 * Fetch + provenance parity: mirrors `components/InspectorPane.ts`'s `loadPreview` — same
 * `/api/preview` request shape, same response fields (`content` / `textProvenance` /
 * `visualExtractionEvidence`), and the same tempdoc-671
 * zero-content diagnostic: the "Text source" provenance line is computed and rendered even when
 * `content` is empty (a scanned page OCR found no text on is still explained), not hidden behind an
 * `if (!content)` gate. `previewProvenanceLabel`/`previewEvidenceDetail` below are a deliberate,
 * small, local carryover of InspectorPane's private helpers of the same shape — this component owns
 * only new files under `components/documentPane/`, so the shared logic is duplicated rather than
 * extracted into a shared module that would require editing InspectorPane.ts.
 *
 * **Two ways to address a passage, and only one of them crosses a process boundary intact**
 * (tempdoc 849 §3 R1). {@link DocumentPane.highlightRange}/{@link DocumentPane.chunkRange} are the
 * original line-span contract, still what the shipped Shell and search-v2 consumers write.
 * {@link DocumentPane.citation} is the evidence reader's contract: the citation's own
 * document-relative CHARACTER span, from which this component derives its own 0-based lines over
 * the text it fetched. Character offsets are the producer's primary quantity; the line numbers that
 * used to travel instead were derived from them, 1-based, and read here as 0-based — an off-by-one
 * nothing downstream could recompute. When `citation` is set it is the anchor, and the line
 * properties are not read; the tier machinery below is unchanged either way.
 *
 * Two consequences the citation path carries that the line path cannot:
 *
 *  - **The window follows the citation.** The fetch is `offsetChars`-anchored around the cited span
 *    instead of the document's first 5,000 characters, and the response's `truncated` flag is read
 *    rather than dropped, so a passage at character 40,000 is reachable at all.
 *  - **The excerpt is a witness.** No content hash, document version or index timestamp exists for a
 *    citation anywhere, so the only available staleness check is whether the citation's own excerpt
 *    still appears at the offsets it named. When it does not, the pane highlights NOTHING and says
 *    so: a tinted passage is the strongest signal on this surface and a caveat beneath it is the
 *    weakest, so a hedged highlight would lend the pane's authority to a location it cannot confirm.
 *
 * Two render modes:
 *   - `rendered` — markdown source is split into top-level blocks by {@link markdownBlockMap} (each
 *     carrying its source line range) and rendered as HTML, each block wrapper carrying
 *     `data-line-start`/`data-line-end`. Default for a `.md`/`.markdown` `docPath`.
 *   - `source` — the raw text, one `<span data-line="N">` per line (InspectorPane's exact
 *     `data-line` mechanics), default for a non-markdown `docPath` (and the ONLY mode available for
 *     one — the Rendered toggle is disabled with a reason, since there is no markdown to block-map).
 *
 * `highlightRange` marks the passage the caller wants shown: the covering block(s)/lines get the
 * `hl-strong` class and the pane scrolls the first one to `{block:'center'}`. The optional
 * `chunkRange` (the wider passage's containing chunk, when the caller has one) tints the REST of
 * that chunk with the weaker `hl-weak` gutter tint, so a reader sees both the exact hit and its
 * surrounding context at a glance.
 *
 * `pane-visible-range` fires (debounced) on scroll with the first/last visible line, a hook a future
 * "reading spine" affordance can consume; `pane-close` fires on the header close action so the host
 * surface decides what closing means (this component has no opinion on layout/visibility).
 *
 * a11y: the scroll region is `tabindex="0"` + `role="region"` (the measured axe
 * `scrollable-region-focusable` fix) so a keyboard user can focus-then-arrow/Page-scroll it. The
 * ramp's own inner scroll containers — a wide `<pre>` or `<table>` in Rendered mode — get the same
 * treatment from `markScrollableRegions` (tempdoc 853 F-05); the pane region alone does not reach
 * content clipped INSIDE a block. The
 * Rendered/Source toggle is a `role="radiogroup"` of native `<button role="radio">`s — the same
 * mutually-exclusive-choice pattern `OptionButtonGroupRenderer` (`jf-option-button-group`) already
 * uses, chosen over an independent-toggle `aria-pressed` pair (seen on UnifiedChatView's "Abilities"
 * button) because Rendered/Source is a single mutually exclusive choice, not two independent flags.
 */
import { html, css, nothing, type TemplateResult, type PropertyValues } from 'lit';
import { unsafeHTML } from 'lit/directives/unsafe-html.js';
import { JfElement } from '../../primitives/JfElement.js';
import { markdownBlockMap, type MarkdownBlockDescriptor } from './markdownBlockMap.js';
// Tempdoc 849 §7 — the header's WORDS come from the registered projection authority, never from
// this view. A label minted in a renderer is the fork `governance/execution-surfaces.v1.json` exists
// to prevent, and the pane and the sources panel must describe one budget fact identically.
import { CITATION_SPAN_UNUSABLE, type CitationHeader } from '../chat/evidenceProjection.js';
import { lineSpanOfChars, locateText, locateWitness } from './charAnchor.js';
import { markdownCodeHighlight, markdownTypography } from '../markdown/markdownStyles.js';
import { highlightCodeBlocks } from '../markdown/markdownHighlight.js';
import { markScrollableRegions } from '../markdown/markdownScrollRegions.js';
import { formatDisplayPath, formatLocationBreadcrumb } from '../searchResults/resultRowPresentation.js';
import { isAdvancedMode, subscribeUiMode } from '../../state/uiModeState.js';
import { authorizedFetch } from '../../api/authorizedFetch.js';
import '../ErrorAlert.js';
import '../Button.js';
import { icon } from '../Icon.js';

/** A 0-based, inclusive source line span (a passage, or its containing chunk). */
export interface DocumentLineRange {
  readonly startLine: number;
  readonly endLine: number;
}

/**
 * Tempdoc 849 §3 — a citation addressed the way its producer computed it: by character offsets into
 * the document, with the excerpt that was quoted from them.
 */
export interface DocumentCitationAnchor {
  /** Document-relative, 0-based, INCLUSIVE. */
  readonly startChar: number;
  /** Document-relative, 0-based, EXCLUSIVE — the producer's own convention. */
  readonly endChar: number;
  /**
   * The passage text the citation quoted. Used ONLY as a staleness witness, never as an anchor:
   * searching for it would silently pick the wrong occurrence in repeated text (headers, tables,
   * boilerplate), which is the confidently-wrong class this anchoring exists to remove.
   */
  readonly excerpt: string;
  /**
   * The claim-matched sentence inside the cited chunk, when a claim match exists for this source.
   * `null` ⇒ no claim match, so the pane tints the chunk and invents no sentence: which part of the
   * passage the answer used is not known, and the honest rendering is to say less, not to guess.
   */
  readonly sentenceText: string | null;
}

/** What the fetched window let the reader confirm about {@link DocumentPane.citation}. */
interface CitationAnchorState {
  /** The claim-matched sentence's line span, or `null` when there is no match to locate. */
  readonly highlight: DocumentLineRange | null;
  /** The cited chunk's line span, or `null` when the anchor could not be confirmed. */
  readonly chunk: DocumentLineRange | null;
  /**
   * Why nothing is highlighted, when nothing is. Three distinct facts, kept distinct because they
   * are three different things to tell a reader:
   *
   *  - `witness` — the excerpt is not at the anchored offsets: the document changed under them.
   *  - `shrunk` — the document ENDS before the cited offsets, and the endpoint said there is no
   *    more to fetch. Also a change, and a more specific one than `witness`.
   *  - `window` — the slice that was loaded does not reach the offsets, but more of the document
   *    exists. That is this reader's limit, not a claim about the document.
   *
   * `null` ⇒ the anchor was confirmed.
   */
  readonly unconfirmed: 'witness' | 'shrunk' | 'window' | null;
}

/** The slice of the document currently in {@link DocumentPane.content}. */
interface PreviewWindow {
  /** Where `content` starts in the document. */
  readonly offset: number;
  /** The endpoint said more of the document follows this slice. */
  readonly truncated: boolean;
}

/** Mirrors InspectorPane's local `VisualExtractionEvidence` shape (the `/api/preview` response field). */
export interface VisualExtractionEvidence {
  schemaVersion?: number;
  pageCount?: number;
  textCharCount?: number;
  textQualityScore?: number;
  charsPerPage?: number;
  alphanumericRatio?: number;
  ocrLanguage?: string;
  ocrMeanConfidence?: number;
  ocrLowConfidenceWordCount?: number;
  ocrWordCount?: number;
  pagesWithTextLayer?: number;
  pagesMissingReadableText?: number;
  mixedPdf?: boolean;
  structuredElementCounts?: {
    tables?: number;
    headings?: number;
    lists?: number;
  };
  imagePageCount?: number;
  layoutComplexity?: string;
  contentTruncated?: boolean;
  ocrFallbackRoute?: string;
  ocrSkipReason?: string;
  route?: string;
}

export type DocumentPaneMode = 'rendered' | 'source';

const SCROLL_DEBOUNCE_MS = 150;
/** The head-of-document window, kept for the line-addressed consumers that have no char anchor. */
const DEFAULT_MAX_CHARS = 5000;
/** How much text before the cited span the window carries, so a passage never opens flush at the top. */
const CITATION_LEAD_IN_CHARS = 2000;
/** …and after it, so the reader can keep reading past the evidence without a second fetch. */
const CITATION_TRAIL_CHARS = 3000;
/** The endpoint's own ceiling (`WorkerSearchService` caps a slice at 200K characters). */
const MAX_WINDOW_CHARS = 200000;

/**
 * What the pane says when it will not highlight (tempdoc 849 §3 R1.4). Both sentences state the
 * consequence — "nothing is highlighted" — because the reader's question is not "was there a
 * problem?" but "why is the passage I clicked not marked?".
 */
const ANCHOR_NOTICE = {
  witness:
    'The cited passage could not be confirmed at its recorded position — this document may have changed since it was indexed, so nothing is highlighted.',
  shrunk:
    'This document is now shorter than the cited passage’s recorded position, so it has changed since it was indexed and nothing is highlighted.',
  window:
    'The cited passage lies outside the part of this document that could be loaded, so nothing is highlighted.',
} as const;

/** What the pane says about the slice it is showing, from the response's own `truncated` flag. */
const WINDOW_NOTE = {
  around: 'Showing the part of this document around the cited passage.',
  head: 'Showing the beginning of a longer document.',
} as const;
/** Search Thread Round-2 R1b — how long the passage lands with the strong tint before decaying to
 *  the quiet translucent tint + edge marker (the card's refined-✓ decay idiom, ported here). */
const HIGHLIGHT_DECAY_MS = 1500;

function isMarkdownPath(path: string): boolean {
  return /\.(md|markdown)$/i.test(path);
}

/** a11y — honor prefers-reduced-motion (MarkdownBlock's cursor-blink-suppression precedent): an
 *  infinite/animated emphasis is a strong reduced-motion trigger, so reduced motion skips the loud
 *  phase entirely and lands quiet. */
function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}

export class DocumentPane extends JfElement {
  static properties = {
    docPath: { attribute: false },
    apiBase: { type: String, attribute: 'api-base' },
    highlightRange: { attribute: false },
    chunkRange: { attribute: false },
    // Tempdoc 849 §3 — the char-anchored citation; when set, the authority for both tiers.
    citation: { attribute: false },
    // Tempdoc 849 §7 — what the citation that opened this pane can honestly be said about.
    citationHeader: { attribute: false },
    anchorState: { state: true },
    previewWindow: { state: true },
    mode: { state: true },
    content: { state: true },
    provenance: { state: true },
    evidence: { state: true },
    loading: { state: true },
    error: { state: true },
    // Search Thread Round-2 R1b — has the current highlightRange decayed to the quiet tier?
    highlightSettled: { state: true },
    // Tempdoc 846 §2.3 — the shared ramp's prose variant, reflected so the shared sheet's
    // `:host([prose])` rules reach this pane's rendered blocks. Default ON: a document IS
    // headings, tables and rules, which is precisely what the variant exists to dress.
    prose: { type: Boolean, reflect: true },
  };

  declare docPath: string | null;
  declare apiBase: string;
  declare highlightRange: DocumentLineRange | null;
  declare chunkRange: DocumentLineRange | null;
  /**
   * Tempdoc 849 §3 — the cited passage addressed by CHARACTER offsets. When set, this is the anchor:
   * both tiers are derived from it against the fetched text and {@link highlightRange}/
   * {@link chunkRange} are not read, so a line number never has to survive a process boundary.
   */
  declare citation: DocumentCitationAnchor | null;
  /**
   * Tempdoc 849 §7 — the CITATION header: which turn cited this document, where in it the passage
   * sits, whether the passage reached the model, and the two differently-measured scores. Distinct
   * from {@link provenance}, which is TEXT-EXTRACTION provenance (the OCR/text-layer route) and is
   * untouched by this: §7's first instruction is not to overload that word.
   *
   * <p>`null` means this pane was NOT opened by a citation — the three line-addressed mount sites
   * never set it. That is also what distinguishes those from a citation whose span was unusable,
   * which is a header PRESENT with {@link CitationHeader.spanUnusable} set (849 S10): before this
   * property existed the pane could not tell the two apart and so said nothing about either.
   */
  declare citationHeader: CitationHeader | null;
  /** Derived from {@link citation} + the fetched window; never written from outside. */
  declare anchorState: CitationAnchorState | null;
  /** Which slice of the document {@link content} is, and whether more of it follows. */
  declare previewWindow: PreviewWindow | null;
  declare mode: DocumentPaneMode;
  declare content: string;
  declare provenance: string | null;
  declare evidence: VisualExtractionEvidence | null;
  declare loading: boolean;
  declare error: string | null;
  /** Round-2 R1b — false while the landed highlight is in its strong phase; true once decayed
   *  (or immediately, under prefers-reduced-motion) to the quiet tint + edge marker. */
  declare highlightSettled: boolean;
  /** Tempdoc 846 §2.3 — wear the shared ramp's prose variant (headings, tables, rules, images). */
  declare prose: boolean;

  private blocksCache: { content: string; blocks: MarkdownBlockDescriptor[] } | null = null;
  private scrollDebounceTimer: ReturnType<typeof setTimeout> | null = null;
  private loadToken = 0;
  /** The window of the fetch currently in flight, or null when none is (tempdoc 849 — see {@link windowCovers}). */
  private requestedWindow: { offset: number; maxChars: number } | null = null;
  private highlightDecayTimer: ReturnType<typeof setTimeout> | null = null;
  /** Round-2 R1b — the last highlightRange (as a line-span key) the decay was armed for, so a
   *  no-op re-render (an unrelated property changing) never re-triggers the strong phase. */
  private armedHighlightKey: string | null = null;

  constructor() {
    super();
    this.docPath = null;
    this.apiBase = '';
    this.highlightRange = null;
    this.chunkRange = null;
    this.citation = null;
    this.citationHeader = null;
    this.anchorState = null;
    this.previewWindow = null;
    this.mode = 'source';
    this.content = '';
    this.provenance = null;
    this.evidence = null;
    this.loading = false;
    this.error = null;
    this.highlightSettled = false;
    this.prose = true;
  }

  static override transientState = {
    loading: false,
    error: null,
  };

  /** Tempdoc 738 — re-render the disclosure-gated path header on Simple/Detailed change. */
  private uiModeUnsubscribe: (() => void) | null = null;

  override connectedCallback(): void {
    super.connectedCallback();
    this.uiModeUnsubscribe = subscribeUiMode(() => this.requestUpdate());
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.uiModeUnsubscribe?.();
    this.uiModeUnsubscribe = null;
    if (this.scrollDebounceTimer) {
      clearTimeout(this.scrollDebounceTimer);
      this.scrollDebounceTimer = null;
    }
    if (this.highlightDecayTimer !== null) {
      clearTimeout(this.highlightDecayTimer);
      this.highlightDecayTimer = null;
    }
  }

  private base(): string {
    return this.apiBase || '';
  }

  override willUpdate(changed: PropertyValues): void {
    if (changed.has('docPath')) {
      const path = this.docPath;
      this.mode = path && isMarkdownPath(path) ? 'rendered' : 'source';
      this.content = '';
      this.provenance = null;
      this.evidence = null;
      this.error = null;
      this.blocksCache = null;
      this.anchorState = null;
      this.previewWindow = null;
      // A fresh docPath re-arms the highlight decay even for a line-range that happens to match
      // the previous document's (the passage is a genuinely new landing).
      this.armedHighlightKey = null;
      if (path) void this.loadContent(path, this.anchor());
    } else if (changed.has('citation')) {
      // Tempdoc 849 §4 — a LATE claim match arrives on an already-open pane as a new anchor over the
      // same span. Re-fetching would be a visible reload for a fact the loaded window already
      // contains, so the window is re-used whenever it covers the anchor and only re-fetched when
      // the anchor moved outside it.
      if (this.docPath && !this.windowCovers(this.anchor())) {
        void this.loadContent(this.docPath, this.anchor());
      } else {
        this.deriveAnchorState();
      }
    }
    if (changed.has('highlightRange')) {
      this.syncHighlightDecay();
    }
  }

  /**
   * The citation anchor, read through ONE normalizer. A Lit property a consumer never binds stays
   * `undefined`, not `null` — and three of this component's four mount sites are line-addressed and
   * never bind it — so an `=== null` test at each read site would be false for exactly those
   * consumers and send them down the anchored path with nothing to anchor on.
   */
  private anchor(): DocumentCitationAnchor | null {
    return this.citation ?? null;
  }

  /**
   * Is the cited span already covered — by the slice in {@link content}, or by a fetch already on
   * its way? The in-flight arm matters because the FIRST anchor typically arrives WITH the document
   * (`docPath` and `citation` set together) and a second anchor can land before that fetch returns:
   * without it, `previewWindow` is still null, the pane reads "not covered" and fires a duplicate,
   * identical request. When the in-flight window lands, the anchor is derived against whatever it
   * actually contains, so trusting the request here cannot hide a short read.
   */
  private windowCovers(citation: DocumentCitationAnchor | null): boolean {
    if (citation === null) return true; // nothing to cover
    const pending = this.requestedWindow;
    if (pending !== null) {
      return citation.startChar >= pending.offset && citation.endChar <= pending.offset + pending.maxChars;
    }
    const win = this.previewWindow;
    if (win === null) return false;
    return citation.startChar >= win.offset && citation.endChar <= win.offset + this.content.length;
  }

  /**
   * Search Thread Round-2 R1b — arm the strong→quiet decay exactly once per distinct highlightRange
   * (the `armedHighlightKey` guard mirrors ResultsCard's `wasSettling` willUpdate-transition-detection
   * idiom): a NEW range lands strong and schedules the decay; the SAME range re-observed on an
   * unrelated re-render is a no-op (never restarts the timer). `chunkRange` never reaches this path —
   * it renders through the separate `hl-weak` tier in {@link highlightTier} and never gets the strong
   * phase, by construction (R1b: "the chunkRange tier NEVER gets the strong phase").
   */
  private syncHighlightDecay(): void {
    const hl = this.activeHighlightRange();
    const key = hl ? `${hl.startLine}:${hl.endLine}` : null;
    if (key === this.armedHighlightKey) return;
    this.armedHighlightKey = key;
    if (this.highlightDecayTimer !== null) {
      clearTimeout(this.highlightDecayTimer);
      this.highlightDecayTimer = null;
    }
    if (key === null) {
      this.highlightSettled = false;
      return;
    }
    if (prefersReducedMotion()) {
      this.highlightSettled = true; // skip the loud phase entirely — land quiet
      return;
    }
    this.highlightSettled = false;
    this.highlightDecayTimer = setTimeout(() => {
      this.highlightSettled = true;
      this.highlightDecayTimer = null;
    }, HIGHLIGHT_DECAY_MS);
  }

  /**
   * The highlight tier for a block/line span: `'strong'` while the CURRENT highlightRange overlaps
   * and hasn't decayed yet; `'weak'` once it has decayed (folds into the same quiet tint + edge
   * marker the surrounding chunkRange uses) OR the span is only in the wider chunkRange; `null`
   * otherwise. chunkRange can never yield `'strong'` — R1b's "never gets the strong phase" rule.
   */
  private highlightTier(startLine: number, endLine: number): 'strong' | 'weak' | null {
    const hl = this.activeHighlightRange();
    if (hl && this.overlaps(hl, startLine, endLine)) {
      return this.highlightSettled ? 'weak' : 'strong';
    }
    const chunk = this.activeChunkRange();
    if (chunk && this.overlaps(chunk, startLine, endLine)) return 'weak';
    return null;
  }

  override updated(changed: PropertyValues): void {
    // Tempdoc 849 §4 (review D-2) — EITHER tier is a scroll target. `rag.citations` is emitted at
    // retrieval time and `rag.citation_matches` only after the answer streams, so a citation
    // followed mid-stream has a chunk and no matched sentence: guarding this on `highlightRange`
    // alone opened the pane at the top of the document with the evidence tinted off-screen, which is
    // the common landing and not an edge case. Scrollability and emphasis are different properties —
    // the weak tier still never takes the strong PHASE (see {@link syncHighlightDecay}).
    if (
      (this.activeHighlightRange() || this.activeChunkRange()) &&
      !this.loading &&
      (changed.has('highlightRange') ||
        changed.has('chunkRange') ||
        changed.has('citation') ||
        changed.has('anchorState') ||
        changed.has('content') ||
        changed.has('loading') ||
        changed.has('mode'))
    ) {
      this.scrollToHighlight();
    }
    // Tempdoc 846 §2.4 — syntax-highlight the rendered document's fenced code. The container the
    // blocks render into is passed (not a snapshot of its children), so a highlighter that is still
    // loading writes into the live tree when it arrives. Idempotent; a no-op in Source mode.
    highlightCodeBlocks(this.renderRoot.querySelector('.blocks'));
    // Tempdoc 853 (F-05) — the ramp's `pre`/`table` scroll containers are focusable + named, so a
    // keyboard user can reach the clipped half of a wide fence or table. Re-applied per render: Lit
    // rebuilds this subtree through `unsafeHTML`, which takes the attributes with it.
    markScrollableRegions(this.renderRoot.querySelector('.blocks'));
  }

  /**
   * The window to request for an anchor: lead-in before the cited span so it does not open flush at
   * the top, the span itself however long it is, and trailing context to read on into. Without an
   * anchor this is the document's head, which is what the line-addressed consumers have always got.
   */
  private windowFor(citation: DocumentCitationAnchor | null): { offset: number; maxChars: number } {
    if (citation === null) return { offset: 0, maxChars: DEFAULT_MAX_CHARS };
    const offset = Math.max(0, Math.floor(citation.startChar) - CITATION_LEAD_IN_CHARS);
    const span = Math.max(0, Math.ceil(citation.endChar) - offset);
    return { offset, maxChars: Math.min(MAX_WINDOW_CHARS, span + CITATION_TRAIL_CHARS) };
  }

  private async loadContent(path: string, citation: DocumentCitationAnchor | null): Promise<void> {
    const token = ++this.loadToken;
    const window = this.windowFor(citation);
    this.requestedWindow = window;
    this.loading = true;
    this.error = null;
    try {
      const res = await authorizedFetch(
        this.base() +
          `/api/preview?docId=${encodeURIComponent(path)}&offsetChars=${window.offset}` +
          `&maxChars=${window.maxChars}`,
      );
      if (token !== this.loadToken) return; // a newer docPath superseded this request
      if (!res.ok) {
        this.error = `HTTP ${res.status}`;
        return;
      }
      const data = (await res.json()) as {
        content?: string;
        truncated?: boolean;
        textProvenance?: string | null;
        visualExtractionEvidence?: VisualExtractionEvidence | null;
      };
      if (token !== this.loadToken) return;
      // The anchor arithmetic uses the offset WE asked for. The response's `offsetChars` is the
      // request parameter echoed back (`PreviewController.java:167`), not the position served — the
      // worker clamps an offset past the end of the document silently, and `nextOffsetChars` is the
      // only served-position fact on the response. No clamp can go unnoticed regardless: a clamped
      // slice does not contain the cited offsets, which the coverage check below reports as a
      // changed document rather than as a highlight in the wrong place.
      let offset = window.offset;
      let content = data.content ?? '';
      if (offset > 0) {
        // A slice cut at an arbitrary character starts mid-line, which would render a half line and
        // shift nothing else; dropping that remainder makes line 0 a real line again. It is dropped
        // only when the whole remainder lies BEFORE the cited span — on text with no line breaks for
        // longer than the lead-in (extracted PDFs are routinely one long line), the first break can
        // sit past the citation, and trimming to it would cut away the evidence and then report it
        // as unreachable.
        const firstBreak = content.indexOf('\n');
        const trimmedTo = offset + firstBreak + 1;
        if (firstBreak >= 0 && (citation === null || trimmedTo <= citation.startChar)) {
          offset = trimmedTo;
          content = content.slice(firstBreak + 1);
        }
      }
      this.content = content;
      this.previewWindow = { offset, truncated: data.truncated === true };
      this.provenance = data.textProvenance ?? null;
      this.evidence = data.visualExtractionEvidence ?? null;
      this.deriveAnchorState();
    } catch (err) {
      if (token !== this.loadToken) return;
      this.error = err instanceof Error ? err.message : String(err);
    } finally {
      if (token === this.loadToken) {
        this.requestedWindow = null;
        this.loading = false;
      }
    }
  }

  /**
   * Tempdoc 849 §3 — turn the citation's character offsets into this reader's own 0-based lines,
   * over the text it actually holds, and refuse to do so when it cannot confirm the offsets.
   *
   * Order matters: the witness is checked BEFORE any line is derived, so a stale anchor produces no
   * range at all rather than a range that is then explained away.
   */
  private deriveAnchorState(): void {
    this.anchorState = this.resolveAnchor();
    // Armed here, where the range is decided, rather than from a `changed.has('anchorState')` test
    // in `willUpdate`. Both work — probed: Lit records a property changed DURING `willUpdate` in the
    // same `changedProperties`, so the observer would fire in the same cycle — and the claim that
    // arming from `willUpdate` was a defect would have been false. This is the shorter path, not a
    // repair: deriving the range and arming its emphasis are one operation.
    this.syncHighlightDecay();
  }

  private resolveAnchor(): CitationAnchorState | null {
    const citation = this.anchor();
    const win = this.previewWindow;
    if (citation === null || win === null) return null;
    const start = citation.startChar - win.offset;
    const end = citation.endChar - win.offset;
    // A span that is empty or inverted is not an anchor at all. It says nothing about the document,
    // so the pane says nothing either — the same silence as opening with no citation, rather than a
    // notice blaming the document for a span the producer never filled in.
    if (end <= start) return null;
    if (start < 0 || end > this.content.length) {
      // The `truncated` flag is what separates "the document ends here" from "we only loaded this
      // much". A slice that ran to EOF and still does not reach the cited offsets means the document
      // SHRANK under them — reporting that as an under-loaded window would blame this reader for a
      // change in the document.
      const ranToEnd = !win.truncated && end > this.content.length && start >= 0;
      return { highlight: null, chunk: null, unconfirmed: ranToEnd ? 'shrunk' : 'window' };
    }
    if (!locateWitness(this.content, { start, end }, citation.excerpt)) {
      return { highlight: null, chunk: null, unconfirmed: 'witness' };
    }
    const chunk = lineSpanOfChars(this.content, { start, end });
    // The strong tier is the claim-matched sentence LOCATED INSIDE the confirmed chunk — a bounded
    // search, not a second anchor. No match, or a match whose text is not in the passage, leaves the
    // chunk tinted and nothing emphasised.
    let highlight: DocumentLineRange | null = null;
    if (citation.sentenceText) {
      const sentence = locateText(this.content, citation.sentenceText, start, end);
      if (sentence !== null) highlight = lineSpanOfChars(this.content, sentence);
    }
    return { highlight, chunk, unconfirmed: null };
  }

  /** The line span the strong tier renders from — the anchor's when there is one. */
  private activeHighlightRange(): DocumentLineRange | null {
    if (this.anchor() !== null) return this.anchorState?.highlight ?? null;
    return this.highlightRange;
  }

  /** The line span the weak tier renders from — the anchor's when there is one. */
  private activeChunkRange(): DocumentLineRange | null {
    if (this.anchor() !== null) return this.anchorState?.chunk ?? null;
    return this.chunkRange;
  }

  private computedBlocks(): MarkdownBlockDescriptor[] {
    if (this.blocksCache?.content !== this.content) {
      this.blocksCache = { content: this.content, blocks: markdownBlockMap(this.content) };
    }
    return this.blocksCache.blocks;
  }

  private overlaps(range: DocumentLineRange, startLine: number, endLine: number): boolean {
    return endLine >= range.startLine && startLine <= range.endLine;
  }

  private scrollToHighlight(): void {
    void this.updateComplete.then(() => {
      // The exact hit when there is one, the chunk when there is not — asked in that ORDER rather
      // than as one `.hl-strong, .hl-weak` selector. A single selector returns the first match in
      // DOCUMENT order, and the chunk's opening line precedes the matched sentence whenever the
      // sentence sits anywhere but the head of the passage, so it would scroll past the emphasis it
      // was meant to land on.
      const root = this.shadowRoot;
      const el = root?.querySelector('.hl-strong') ?? root?.querySelector('.hl-weak');
      el?.scrollIntoView({ block: 'center' });
    });
  }

  private handleClose = (): void => {
    this.dispatchEvent(new CustomEvent('pane-close', { bubbles: true, composed: true }));
  };

  private handleScroll = (): void => {
    if (this.scrollDebounceTimer) clearTimeout(this.scrollDebounceTimer);
    this.scrollDebounceTimer = setTimeout(() => {
      this.scrollDebounceTimer = null;
      this.emitVisibleRange();
    }, SCROLL_DEBOUNCE_MS);
  };

  private emitVisibleRange(): void {
    const container = this.shadowRoot?.querySelector('.scroll-region') as HTMLElement | null;
    if (!container) return;
    const containerRect = container.getBoundingClientRect();
    const items = container.querySelectorAll('[data-line-start], [data-line]');
    let first: number | null = null;
    let last: number | null = null;
    for (const el of items) {
      const rect = el.getBoundingClientRect();
      const visible = rect.bottom > containerRect.top && rect.top < containerRect.bottom;
      if (!visible) continue;
      const startAttr = el.getAttribute('data-line-start') ?? el.getAttribute('data-line');
      const endAttr = el.getAttribute('data-line-end') ?? startAttr;
      const start = Number(startAttr);
      const end = Number(endAttr);
      if (!Number.isFinite(start) || !Number.isFinite(end)) continue;
      if (first === null || start < first) first = start;
      if (last === null || end > last) last = end;
    }
    if (first === null || last === null) return;
    this.dispatchEvent(
      new CustomEvent('pane-visible-range', {
        detail: { firstLine: first, lastLine: last },
        bubbles: true,
        composed: true,
      }),
    );
  }

  private previewProvenanceLabel(): string | null {
    switch ((this.provenance ?? '').toLowerCase()) {
      case 'ocr':
        return 'OCR';
      case 'tika':
        return 'Tika';
      case 'vdu':
        return 'VDU';
      case 'vdu_pending':
        return 'VDU pending';
      case 'vdu_processing':
        return 'VDU processing';
      case 'vdu_failed':
        return 'VDU failed';
      // Tempdoc 677: VDU ran and found no text on the page(s) — previously fell through
      // silently to the base extraction label, hiding that VDU ran at all.
      case 'vdu_empty':
        return 'VDU: no text found';
      // Tempdoc 677 abstention gate: VDU output was judged untrustworthy (or the call was
      // skipped on an illegible input) — baseline extraction is shown instead.
      case 'vdu_rejected':
        return 'VDU: unreliable, not used';
      default:
        return null;
    }
  }

  /** Tempdoc 677: an explanatory tooltip for provenance values a bare label doesn't fully convey. */
  private previewProvenanceTooltip(): string | null {
    switch ((this.provenance ?? '').toLowerCase()) {
      case 'vdu_rejected':
        return 'The automatic reader could not produce trustworthy text for this document, so search uses the original extraction.';
      default:
        return null;
    }
  }

  private previewEvidenceDetail(): string | null {
    const evidence = this.evidence;
    if (!evidence) return null;
    const parts: string[] = [];
    const route = evidence.route?.replace(/_/g, ' ');
    if (route) parts.push(route);
    if (evidence.ocrLanguage) parts.push(evidence.ocrLanguage);
    if (typeof evidence.textQualityScore === 'number') {
      const score = Math.round(Math.max(0, Math.min(1, evidence.textQualityScore)) * 100);
      parts.push(`${score}% quality`);
    }
    if (typeof evidence.ocrMeanConfidence === 'number') {
      const confidence = Math.round(Math.max(0, Math.min(1, evidence.ocrMeanConfidence)) * 100);
      parts.push(`${confidence}% OCR confidence`);
    }
    if (evidence.ocrFallbackRoute) {
      parts.push(`${evidence.ocrFallbackRoute.replace(/_/g, ' ')} fallback`);
    }
    if (evidence.contentTruncated) {
      parts.push('truncated');
    }
    if (evidence.ocrSkipReason) {
      parts.push(`OCR skipped: ${evidence.ocrSkipReason.replace(/_/g, ' ')}`);
    }
    if ((evidence.pagesMissingReadableText ?? 0) > 0) {
      parts.push(`${evidence.pagesMissingReadableText} pages still visual`);
    }
    if (evidence.layoutComplexity && evidence.layoutComplexity !== 'none') {
      parts.push(`${evidence.layoutComplexity} layout`);
    }
    return parts.length > 0 ? parts.join(' · ') : null;
  }

  /**
   * Tempdoc 846 §2.3 — the shared markdown ramp comes FIRST, so every rule below still has the last
   * word. Before this, Rendered mode dressed a whole `.md` file in user-agent defaults (a 32px
   * `h1`, browser list indents, an unstyled `<table>`, a `<pre>` with no surface) — the one surface
   * whose entire job is reading a document was the one that did not dress documents.
   *
   * The shared sheet also carries `:host` typography (`font-size`, `line-height`, `word-wrap`); the
   * pane's own `:host` rule below re-declares what it means to keep (`display`, `color`,
   * `font-family`), and every chrome row — header, toggle, provenance — sets its own `font-size`,
   * so what the ramp actually reaches is the document body it was moved here for.
   */
  static styles = [markdownTypography, markdownCodeHighlight, css`
    :host([overlay]) {
      /* 687 R5b — sized for the OverlayHost right-drawer slot (narrow viewports). */
      width: min(28rem, 92vw);
      height: calc(100vh - 7.5rem);
      box-shadow: var(--shadow-float);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      background: var(--surface-1);
    }
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      box-sizing: border-box;
      background: var(--surface-1);
      color: var(--text-primary);
      font-family: system-ui, sans-serif;
    }
    .header {
      flex-shrink: 0;
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.625rem 0.875rem;
      border-bottom: 1px solid var(--border-subtle);
    }
    /* Round-2 R5c — truncation is the shared formatDisplayPath authority (filename-preserving
       middle-ellipsis), not CSS end-truncation; overflow stays hidden as a defensive clamp only. */
    .path {
      flex: 1;
      min-width: 0;
      font-size: var(--font-size-sm);
      font-weight: 600;
      font-family: monospace;
      overflow: hidden;
      white-space: nowrap;
    }
    .toggle-group {
      flex-shrink: 0;
      display: flex;
      gap: 0;
      padding: 0.5rem 0.875rem 0;
    }
    .toggle-btn {
      padding: 0.3rem 0.75rem;
      border: 1px solid var(--border-subtle);
      background: var(--surface-2);
      color: var(--text-secondary);
      font: inherit;
      font-size: var(--font-size-xs);
      cursor: pointer;
      transition: background var(--duration-fast), color var(--duration-fast),
        border-color var(--duration-fast);
    }
    .toggle-btn:first-child {
      border-radius: 0.375rem 0 0 0.375rem;
    }
    .toggle-btn:last-child {
      border-radius: 0 0.375rem 0.375rem 0;
      margin-left: -1px;
    }
    .toggle-btn:hover:not([aria-disabled='true']) {
      background: var(--surface-hover);
      color: var(--text-primary);
    }
    .toggle-btn:focus-visible {
      outline: 2px solid var(--accent-tint);
      outline-offset: 1px;
    }
    .toggle-btn.selected {
      background: var(--accent-tint-16);
      color: var(--text-tint);
      border-color: var(--accent-tint);
    }
    /* Tempdoc 596 face 1.1 — this is a SOFT block (aria-disabled, not native disabled): the button
       stays focusable and its title reason stays reachable via hover/focus. A native disabled
       button suppresses its own title tooltip (596 §1.1) — the exact defect the retired
       controls-a11y gate's title-on-disabled check used to flag. */
    .toggle-btn[aria-disabled='true'] {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .empty {
      padding: 2rem 1rem;
      text-align: center;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
    .preview-source {
      display: inline-flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.35rem;
      max-width: 100%;
      margin: 0.75rem 0.875rem 0;
      padding: 0.2rem 0.45rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      background: var(--surface-2);
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      line-height: 1.2;
    }
    .preview-source strong {
      color: var(--text-primary);
      font-weight: 600;
    }
    .preview-source-detail {
      color: var(--text-tertiary);
    }
    /* Tempdoc 849 §7 — the CITATION header. Above the extraction-provenance line and visually its
       sibling, not its replacement: one says where this TEXT came from, the other says why this
       document is open. Wraps, because the fact count varies with what the producer recorded and a
       fixed row would either clip the honest cases or reserve space for absent ones. */
    .citation-header {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: 0.25rem 0.6rem;
      margin: 0.75rem 0.875rem 0;
      padding: 0.3rem 0.5rem;
      border-inline-start: 2px solid var(--border-subtle);
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      line-height: 1.35;
    }
    .citation-turn {
      flex-basis: 100%;
      color: var(--text-tertiary);
    }
    .citation-turn q {
      color: var(--text-primary);
    }
    /* Each score names its own metric. §7 rule 1: the two are NEVER adjacent as bare numbers, which
       is enforced by there being no number here at all — the band word is the whole value. */
    .citation-metric {
      color: var(--text-tertiary);
    }
    .citation-band {
      color: var(--text-secondary);
      font-weight: 500;
    }
    .citation-inclusion {
      font-weight: 500;
      white-space: nowrap;
    }
    .citation-inclusion.dropped {
      color: var(--text-warning);
    }
    /* Tempdoc 849 §3 — the reader's own notices (why nothing is highlighted; what slice this is).
       Deliberately the quiet chrome voice the provenance line already speaks in: these say the pane
       is claiming LESS than usual, which is not an alert. */
    .reader-notice {
      margin: 0.75rem 0.875rem 0;
      padding: 0.35rem 0.5rem;
      border-inline-start: 2px solid var(--border-subtle);
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      line-height: 1.35;
    }
    .scroll-region {
      flex: 1;
      overflow-y: auto;
      padding: 0.875rem;
    }
    .scroll-region:focus-visible {
      outline: 2px solid var(--accent-tint);
      outline-offset: -2px;
    }
    /* Tempdoc 846 §2.3 — the wrapper's rhythm now READS the shared vocabulary instead of restating
       its literal (it was a private '0.25em'). The wrapper is load-bearing here and the ramp cannot
       replace it: this pane renders one block per wrapper, so the ramp's own 'p:first-child' /
       'p:last-child' zeroing fires on EVERY paragraph (each is both), which is exactly right — the
       gap between blocks is the wrapper's, and nothing double-counts it. */
    .blocks .block {
      margin: var(--md-block-gap) 0;
    }
    /* Round-2 R1b — the strong→quiet decay: both tiers share the same transitioned properties so
       swapping hl-strong for hl-weak (the JS class-flip in highlightTier) animates smoothly rather
       than jumping, via the existing --duration tokens. */
    .blocks .block.hl-weak,
    .blocks .block.hl-strong {
      transition: background var(--duration-slow) var(--ease-standard),
        color var(--duration-slow) var(--ease-standard),
        border-color var(--duration-slow) var(--ease-standard);
    }
    .blocks .block.hl-weak {
      background: var(--accent-tint-08);
      border-left: 2px solid var(--accent-tint-30);
      padding-left: 0.5rem;
      margin-left: -0.5rem;
    }
    .blocks .block.hl-strong {
      background: var(--accent-tint);
      color: var(--accent-on-tint);
      border-radius: 0.25rem;
      padding: 0.1rem 0.4rem;
      margin-left: -0.4rem;
    }
    pre.source {
      margin: 0;
      font-family: ui-monospace, 'SF Mono', monospace;
      font-size: var(--font-size-xs);
      line-height: 1.5;
      color: var(--text-secondary);
      white-space: pre-wrap;
      word-break: break-word;
    }
    pre.source span.hl-weak,
    pre.source span.hl-strong {
      transition: background var(--duration-slow) var(--ease-standard),
        color var(--duration-slow) var(--ease-standard),
        border-color var(--duration-slow) var(--ease-standard);
    }
    pre.source span.hl-weak {
      background: var(--accent-tint-08);
      border-left: 2px solid var(--accent-tint-30);
    }
    pre.source span.hl-strong {
      background: var(--accent-tint);
      color: var(--accent-on-tint);
      border-radius: 2px;
    }
    /* a11y — honor prefers-reduced-motion (mirrors MarkdownBlock's cursor-blink suppression): the
       JS side already skips the loud phase (prefersReducedMotion() in syncHighlightDecay), this is
       the defensive CSS-only backstop against the decay transition itself. */
    @media (prefers-reduced-motion: reduce) {
      .blocks .block.hl-weak,
      .blocks .block.hl-strong,
      pre.source span.hl-weak,
      pre.source span.hl-strong {
        transition: none;
      }
    }
  `];

  private renderToggle(): TemplateResult {
    const isMd = this.docPath ? isMarkdownPath(this.docPath) : false;
    return html`
      <div class="toggle-group" role="radiogroup" aria-label="View mode">
        <button
          type="button"
          role="radio"
          aria-checked=${this.mode === 'rendered' ? 'true' : 'false'}
          aria-disabled=${isMd ? nothing : 'true'}
          class="toggle-btn ${this.mode === 'rendered' ? 'selected' : ''}"
          title=${isMd ? nothing : 'Rendered view is only available for Markdown documents'}
          @click=${() => {
            if (isMd) this.mode = 'rendered';
          }}
        >
          Rendered
        </button>
        <button
          type="button"
          role="radio"
          aria-checked=${this.mode === 'source' ? 'true' : 'false'}
          class="toggle-btn ${this.mode === 'source' ? 'selected' : ''}"
          @click=${() => {
            this.mode = 'source';
          }}
        >
          Source
        </button>
      </div>
    `;
  }

  private renderProvenanceLine(): TemplateResult | typeof nothing {
    const label = this.previewProvenanceLabel();
    if (!label) return nothing;
    const detail = this.previewEvidenceDetail();
    const tooltip = this.previewProvenanceTooltip();
    return html`
      <div class="preview-source">
        <span title=${tooltip ?? nothing}>Text source <strong>${label}</strong></span>
        ${detail ? html`<span class="preview-source-detail">${detail}</span>` : nothing}
      </div>
    `;
  }

  /**
   * Tempdoc 849 §7 — the citation header. Every element is projected by `evidenceProjection.ts` and
   * every one of them is suppressed when its producer said nothing, so a pre-849 conversation, a
   * fallback-mode retrieval and an uncited source each render a SHORTER header rather than a
   * padded one. There is no "unknown" placeholder: a caveat on every historical citation would be a
   * claim of its own.
   */
  private renderCitationHeader(): TemplateResult | typeof nothing {
    const h = this.citationHeader;
    if (h === null) return nothing;
    const parts = [
      h.passage === null ? nothing : html`<span class="citation-passage">${h.passage}</span>`,
      this.renderInclusion(h),
      h.grounding === null ? nothing : html`<span class="citation-grounding">${h.grounding}</span>`,
      this.renderBand(h.claim),
    ].filter((part) => part !== nothing);
    if (h.turnLabel === null && parts.length === 0) return nothing;
    return html`<div class="citation-header" data-testid="citation-header">
      ${h.turnLabel === null
        ? nothing
        : html`<span class="citation-turn">Cited in the answer to <q>${h.turnLabel}</q></span>`}
      ${parts}
    </div>`;
  }

  /** §5 — the flagship. Absent ⇒ nothing, never "included" (`inclusionBadge` already refuses). */
  private renderInclusion(h: CitationHeader): TemplateResult | typeof nothing {
    const badge = h.inclusion;
    if (badge === null) return nothing;
    return html`<span
      class="citation-inclusion ${badge.state}"
      data-inclusion=${badge.state}
      title=${badge.detail}
      >${badge.label}</span
    >`;
  }

  /**
   * §7 — a score as METRIC + BAND, never a bare number. Only the CLAIM similarity is banded here;
   * the retrieval score is not rendered at all, because it is the raw Lucene hit score and the
   * tier thresholds are anchored to the cross-encoder scale (see `claimMatch`).
   */
  private renderBand(score: CitationHeader['claim']): TemplateResult | typeof nothing {
    if (score === null) return nothing;
    return html`<span class="citation-score"
      ><span class="citation-metric">${score.metric}</span>
      <span class="citation-band">${score.band}</span></span
    >`;
  }

  /**
   * Tempdoc 849 slice 2 S10 — the citation carried a span this reader cannot use (`endChar <=
   * startChar`, or a non-finite offset). The pane used to open in silence, indistinguishable from a
   * document opened with no citation at all; the header is what finally lets it tell the difference,
   * so this is where the message belongs.
   */
  private renderSpanNotice(): TemplateResult | typeof nothing {
    if (this.citationHeader?.spanUnusable !== true) return nothing;
    return html`<p class="reader-notice span-notice" role="note">${CITATION_SPAN_UNUSABLE}</p>`;
  }

  /**
   * Tempdoc 849 §3 R1.4 — why nothing is highlighted, when the anchor could not be confirmed. It
   * renders INSTEAD of a highlight, never beside one: a tinted passage plus a hedge is read as a
   * tinted passage.
   */
  private renderAnchorNotice(): TemplateResult | typeof nothing {
    const unconfirmed = this.anchorState?.unconfirmed ?? null;
    if (unconfirmed === null) return nothing;
    return html`<p class="reader-notice anchor-notice" role="note">${ANCHOR_NOTICE[unconfirmed]}</p>`;
  }

  /**
   * Tempdoc 849 §10 — the endpoint has always returned `truncated` and this reader has always
   * dropped it, so a reader looking at a 5,000-character slice believed they were looking at the
   * document. The flag is now said out loud, and no character count is quoted with it: the honest
   * fact is that this is a window, and a number would invite precision about a boundary the reader
   * cannot see.
   */
  private renderWindowNote(): TemplateResult | typeof nothing {
    const win = this.previewWindow;
    if (win === null || (!win.truncated && win.offset === 0)) return nothing;
    // Not beside a suppression notice: "showing the part around the cited passage" next to "the
    // cited passage could not be confirmed" are two sentences that contradict each other, and the
    // suppression is the one the reader needs. When there is no confirmed anchor, this window is not
    // "around" anything the pane is willing to claim.
    if ((this.anchorState?.unconfirmed ?? null) !== null) return nothing;
    return html`<p class="reader-notice window-note">
      ${win.offset > 0 ? WINDOW_NOTE.around : WINDOW_NOTE.head}
    </p>`;
  }

  private renderRenderedMode(): TemplateResult {
    const blocks = this.computedBlocks();
    if (blocks.length === 0) {
      return html`<div class="empty">No renderable content.</div>`;
    }
    return html`
      <div class="blocks md-content">
        ${blocks.map((b) => {
          const tier = this.highlightTier(b.startLine, b.endLine);
          return html`<div
            class="block ${tier === 'strong' ? 'hl-strong' : ''} ${tier === 'weak' ? 'hl-weak' : ''}"
            data-line-start=${b.startLine}
            data-line-end=${b.endLine}
          >
            ${unsafeHTML(b.html)}
          </div>`;
        })}
      </div>
    `;
  }

  private renderSourceMode(): TemplateResult {
    const lines = this.content.split('\n');
    return html`
      <pre class="source">${lines.map((line, i) => {
        const tier = this.highlightTier(i, i);
        return html`<span
          data-line="${i}"
          class=${tier === 'strong' ? 'hl-strong' : tier === 'weak' ? 'hl-weak' : ''}
          >${line}\n</span
        >`;
      })}</pre>
    `;
  }

  private renderBody(): TemplateResult {
    if (this.loading) {
      return html`<div class="empty">Loading…</div>`;
    }
    if (this.error) {
      return html`<jf-error-alert tone="error">${this.error}</jf-error-alert>`;
    }
    return html`
      ${this.renderCitationHeader()}${this.renderProvenanceLine()}${this.renderSpanNotice()}${this.renderAnchorNotice()}${this.renderWindowNote()}
      <div
        class="scroll-region"
        tabindex="0"
        role="region"
        aria-label="Document content"
        @scroll=${this.handleScroll}
      >
        ${this.content
          ? this.mode === 'rendered'
            ? this.renderRenderedMode()
            : this.renderSourceMode()
          : html`<div class="empty">No preview available.</div>`}
      </div>
    `;
  }

  override render(): TemplateResult {
    if (!this.docPath) {
      return html`<div class="empty">No document selected.</div>`;
    }
    return html`
      <div class="header">
        ${/* Search Thread Round-2 R5c — the shared formatDisplayPath authority (filename-preserving
              middle truncation), not CSS end-truncation; the full path stays reachable via title. */ ''}
        <div class="path" title=${this.docPath}>
          ${/* Tempdoc 738 (C4) — Simple shows the humanized folder breadcrumb; Detailed the full path
                (the full path stays reachable via the title tooltip in both). */ ''}
          ${isAdvancedMode() ? formatDisplayPath(this.docPath) : formatLocationBreadcrumb(this.docPath)}
        </div>
        <jf-button class="icon" variant="ghost" size="icon" label="Close" .onActivate=${this.handleClose}
          >${icon({ name: 'x', size: 14 })}</jf-button
        >
      </div>
      ${this.renderToggle()} ${this.renderBody()}
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-document-pane')) {
  customElements.define('jf-document-pane', DocumentPane);
}
