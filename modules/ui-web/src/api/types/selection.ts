// SPDX-License-Identifier: Apache-2.0
/**
 * Hand-written TypeScript mirror of the Java sealed sums under
 * `modules/app-api/src/main/java/io/justsearch/app/api/selection/`:
 *
 *  - SelectionPayload (tempdoc 526 §4.1)
 *  - DocumentAddress (tempdoc 526 §4.2)
 *  - SourceCitation (tempdoc 526 §11.2 + R2.3 closure + §16 retraction)
 *  - ComposeIntent (tempdoc 526 §4.5)
 *
 * Discriminator pattern matches the Java @JsonTypeInfo / @JsonSubTypes wire shape
 * (ConfirmStrategy is the in-repo precedent). Adding a variant in Java requires updating
 * this file and the FE consumers that match on it.
 *
 * Tempdoc 526 §16 retraction notes:
 *  - SelectionPayload variants without v1 producers (ConversationTurn, HealthCondition)
 *    have been retracted from this file. They land with their consumer slices.
 *  - SourceCitation collapsed from a sealed sum to a flat record — only CharLocation ever
 *    had a producer.
 *  - DocumentAddress.Opaque retracted — no consumer.
 *  - ComposeOperation narrowed to {summarize, ask} — explain/compare shapes don't exist.
 */

// ============================================================
// DocumentAddress — coordinate-system primitive (tempdoc 526 §4.2)
// ============================================================

export type ViewFormat =
  | 'preview-5k'
  | 'preview-20k'
  | 'preview-200k'
  | 'preview-full'
  | 'snippet-rag'
  | 'markdown-rendered'
  | 'paginated-page';

export interface DocumentAddressCanonical {
  readonly coords: 'canonical';
  readonly docId: string;
  readonly startChar: number;
  readonly endChar: number;
}

export interface DocumentAddressDisplay {
  readonly coords: 'display';
  readonly docId: string;
  readonly viewId: ViewFormat;
  readonly displayStart: number;
  readonly displayEnd: number;
  readonly canonicalHint?: { readonly startChar: number; readonly endChar: number };
}

export interface DocumentAddressLines {
  readonly coords: 'lines';
  readonly docId: string;
  readonly startLine: number;
  readonly endLine: number;
}

export type DocumentAddress =
  | DocumentAddressCanonical
  | DocumentAddressDisplay
  | DocumentAddressLines;

// ============================================================
// SourceCitation — flat citation locator (tempdoc 526 §16 collapse)
// ============================================================

export interface SourceCitation {
  readonly parentDocId: string;
  readonly startChar: number;
  readonly endChar: number;
  readonly excerpt: string;
}

// ============================================================
// SelectionPayload — typed wire shape for the user's pick
// ============================================================

export interface HostEntity {
  readonly kind: 'doc' | 'snippet' | 'message' | 'log-entry';
  readonly id: string;
}

export interface ResultRef {
  readonly id: string;
  readonly kind: 'doc' | 'hit';
}

export interface SelectionPayloadItem {
  readonly kind: 'item';
  readonly itemKind: 'search-hit' | 'browse-node' | 'plugin-item' | string;
  readonly itemId: string;
  readonly surfaceId?: string;
  readonly label?: string;
}

export interface SelectionPayloadTextRange {
  readonly kind: 'text-range';
  readonly address: DocumentAddress;
  readonly selectionText: string;
  readonly hostEntity: HostEntity;
}

export interface SelectionPayloadCitation {
  readonly kind: 'citation';
  readonly citation: SourceCitation;
  readonly promotedFrom?: 'rag-stream' | 'manual' | string;
}

export interface SelectionPayloadResultSet {
  readonly kind: 'result-set';
  readonly items: ReadonlyArray<ResultRef>;
  readonly query?: string;
}

export interface SelectionPayloadHealthCondition {
  readonly kind: 'health-condition';
  readonly conditionId: string;
  readonly severity?: string;
  readonly summary?: string;
}

/**
 * Tempdoc 549 Slice 4 (G33/G111 LLM narration). A search-pipeline trace the user asked the
 * LLM to explain in words. `scope`: 'query' (whole-query trace) or 'hit' (one result's
 * ranking provenance); `summary` is the human-readable trace text fed to the LLM as context.
 */
export interface SelectionPayloadSearchTrace {
  readonly kind: 'search-trace';
  readonly scope: 'query' | 'hit';
  readonly summary: string;
  /** The result being explained, for source navigation; no document text is injected. */
  readonly docId?: string;
}

export type SelectionPayload =
  | SelectionPayloadItem
  | SelectionPayloadTextRange
  | SelectionPayloadCitation
  | SelectionPayloadResultSet
  | SelectionPayloadHealthCondition
  | SelectionPayloadSearchTrace;

// ============================================================
// ComposeIntent — universal dispatch envelope (tempdoc 526 §4.5)
// ============================================================

/** Operations the FE compose() helper recognizes. Narrowed in §16 to {summarize, ask}. */
export type ComposeOperation = 'core.summarize' | 'core.ask';

export interface ComposeIntent {
  readonly operation: ComposeOperation;
  readonly source: string;
  readonly selection?: SelectionPayload;
  readonly userPrompt?: string;
  /** Optional explicit shape override; defaults to the (operation, kind) table lookup. */
  readonly targetShape?: string;
}
