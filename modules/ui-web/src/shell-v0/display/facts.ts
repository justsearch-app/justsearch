// SPDX-License-Identifier: Apache-2.0
/**
 * projectFact() — the VALUE half of the Display authority (tempdoc 594 §9.2).
 *
 * `present()` (present.ts) is the Display authority's NAME half: it turns an entity-ref into a
 * human-facing *label*. It deliberately resolves names only. `projectFact()` is its symmetric
 * sibling: it turns a FACT-ref into the fact's current *value* — `{ name, value, presence,
 * provenance? }` — so a status chip that asserts a runtime/build fact (vector dimension, accelerator,
 * vector precision, capability presence) DERIVES it from one authority instead of baking a literal
 * that can ship wrong ("Embeddings 384-d") or lie per-host ("GPU cuda12" on a CPU box).
 *
 * Two source kinds, each bound to the authority the fact's reason-to-change picks (594 §9.4):
 *   - `constant`  — a build-time fact GENERATED from the SSOT catalog (the embedding dimension,
 *                   `field-constants.ts` ← `fields.v1.json`). The Generate rung.
 *   - `observed`  — a runtime fact PROJECTED from the ONE observed-state authority `aiStateStore`
 *                   (the fine-grained `status.worker.*` / `status.gpu.*` fields, incl. the 587 GPU
 *                   provenance+confidence). The Unrepresentable rung.
 *
 * Presence is TERNARY (594 §11.3 #3): `present` (show value) · `absent` (capability genuinely off →
 * the chip is omitted) · `unknown` (not polled yet / reconnecting → muted placeholder, never a
 * fabricated or stale-confident value). The chip facet (DeclaredSurface) renders this; a wrong
 * literal is unrepresentable because the factual chip carries a fact-ref, not a string.
 */

import { unknownDocumentCountLabel, type AiState } from '../state/aiStateStore.js';
import { EMBED_VECTOR_DIM } from '../../api/generated/field-constants.js';
import { whenKnown } from '../state/known.js';
import { formatBytes, formatCount } from './format.js';

/** Ternary presence — `unknown` is distinct from `absent` (594 §11.3 #3 / §14 U5). */
export type FactPresence = 'present' | 'absent' | 'unknown';

/**
 * 594 §11.3 #2 — how sure the sensing was, as a STRUCTURED level (not just tooltip prose), so the
 * chip facet can render uncertainty (587: "confidence survives to the decision"). Only host facts
 * that are merged from authority-ordered probes (the 587 GPU substrate) carry it; §14 U3.
 */
export type FactConfidence = 'high' | 'medium' | 'low' | 'unknown';

export interface ProjectedFact {
  /** The fact's display name (its own label — NOT the metric registry; 594 §14 G1). */
  readonly name: string;
  /** Formatted value to show after the name; `null` for a name-only (presence) fact. */
  readonly value: string | null;
  readonly presence: FactPresence;
  /** Optional provenance hint (source + confidence prose), rendered as the chip's `title` (§11.3 #1). */
  readonly provenance?: string;
  /**
   * 594 §11.3 #2 — the structured sensing confidence. The chip facet renders `low`/`unknown` with a
   * visible uncertainty cue (a trailing "?" + muted style) so a low-confidence reading does not assert
   * as flatly as a high-confidence one. Absent for facts that carry no confidence (the non-GPU majority).
   */
  readonly confidence?: FactConfidence;
}

/**
 * An observed read returns a tri-state: `undefined` ⇒ unknown (not polled / reconnecting),
 * `null` ⇒ absent (capability genuinely off), a string ⇒ present with that value (empty string ⇒
 * present, name-only).
 */
type ObservedRead = (s: AiState) => string | null | undefined;

type FactSource =
  | { readonly kind: 'constant'; readonly value: string }
  | {
      readonly kind: 'observed';
      readonly read: ObservedRead;
      readonly provenance?: (s: AiState) => string | undefined;
      readonly confidence?: (s: AiState) => FactConfidence | undefined;
    };

interface FactDef {
  readonly label: string;
  readonly source: FactSource;
}

// ── value formatting helpers ──

function humanizePrecision(raw: string): string {
  const v = raw.trim().toLowerCase();
  if (v === 'float32' || v === 'fp32') return 'Float32';
  if (v === 'float16' || v === 'fp16') return 'Float16';
  if (v === 'int8') return 'Int8';
  // Fallback: title-case the raw token rather than leak an upper/lower-case wire string.
  return raw.charAt(0).toUpperCase() + raw.slice(1).toLowerCase();
}

const GPU_SOURCE_LABEL: Record<string, string> = {
  nvml: 'NVML',
  'nvidia-smi': 'nvidia-smi',
  'cuda-driver-api': 'CUDA driver API',
};

function gpuProvenance(s: AiState): string | undefined {
  const gpu = s.status?.gpu;
  if (!gpu) return undefined;
  const human = gpu.source ? GPU_SOURCE_LABEL[gpu.source] : undefined;
  if (!human) return undefined;
  const conf = gpu.confidence ? gpu.confidence.toLowerCase() : undefined;
  return conf ? `via ${human} · ${conf} confidence` : `via ${human}`;
}

const GPU_CONFIDENCE: Record<string, FactConfidence> = {
  HIGH: 'high',
  MEDIUM: 'medium',
  LOW: 'low',
  UNKNOWN: 'unknown',
};

/** 594 §11.3 #2 — the 587 GPU merge's structured confidence (NVML⟹HIGH, nvidia-smi⟹LOW, …). */
function gpuConfidence(s: AiState): FactConfidence | undefined {
  const raw = s.status?.gpu?.confidence;
  return raw ? GPU_CONFIDENCE[raw] : undefined;
}

// ── the Fact catalog ──

const FACTS: Record<string, FactDef> = {
  // Build-time constant (594 Move 1a): the embedding dimension, generated from the SSOT catalog.
  'core.embed.dim': {
    label: 'Embeddings',
    source: { kind: 'constant', value: `${EMBED_VECTOR_DIM}-d` },
  },
  // Runtime host fact (587 capability substrate via the status wire): accelerator + provenance.
  'core.gpu.accel': {
    label: 'GPU',
    source: {
      kind: 'observed',
      read: (s) => {
        if (!s.status) return undefined; // not polled → unknown
        const gpu = s.status.gpu;
        if (!gpu || gpu.available !== true) return null; // no GPU on this host → absent
        return gpu.cudaFunctional ? 'CUDA' : ''; // present: "GPU CUDA" or just "GPU"
      },
      provenance: gpuProvenance,
      confidence: gpuConfidence,
    },
  },
  // Runtime config facts: capability presence (name-only chips).
  'core.splade': {
    label: 'SPLADE',
    source: {
      kind: 'observed',
      read: (s) => presence(s, s.status?.worker?.enrichment?.spladeEnabled),
    },
  },
  'core.ner': {
    label: 'NER',
    source: {
      kind: 'observed',
      read: (s) => presence(s, s.status?.worker?.enrichment?.nerEnabled),
    },
  },
  'core.reranker': {
    label: 'Reranker',
    source: {
      kind: 'observed',
      read: (s) => {
        if (!s.status) return undefined;
        const path = s.status.worker?.gpu?.rerankerModelPath;
        return path ? '' : null; // a configured reranker model ⇒ present (name-only)
      },
    },
  },
  // Runtime index fact: vector storage precision.
  'core.vector.precision': {
    label: 'Vectors',
    source: {
      kind: 'observed',
      read: (s) => {
        if (!s.status) return undefined;
        const fmt = s.status.worker?.vectorFormat?.vectorFormatActual;
        return fmt ? humanizePrecision(fmt) : undefined;
      },
    },
  },
  // 594 §17.2 — the status METRICS, migrated off the bypass shells (StatusDeck/HealthSurface) onto the
  // one value authority. Value-only facts (exact single-source measures → no confidence/provenance):
  // they render plainly. `undefined` (no data yet) ⇒ unknown ⇒ the shells' existing "—".
  'core.files': {
    label: 'Files',
    source: {
      kind: 'observed',
      // #758: an index the backend could not count reads "Checking…" while the
      // server is starting and "Unknown" after — never "0", which would say the index is empty.
      read: (s) =>
        unknownDocumentCountLabel(s.status) ??
        (s.index ? whenKnown(s.index.documentCount, (n) => formatCount(n), () => undefined) : undefined),
    },
  },
  'core.size': {
    label: 'Size',
    source: {
      kind: 'observed',
      read: (s) => (s.status ? formatBytes(s.status.worker?.core?.indexSizeBytes) : undefined),
    },
  },
  'core.memory': {
    label: 'Memory',
    source: {
      kind: 'observed',
      read: (s) => (s.status ? formatBytes(s.status.memoryUsedBytes) : undefined),
    },
  },
  // NOTE: the QUEUE metric is deliberately NOT a fact — its display is genuinely shell-divergent
  // (StatusDeck composes pending + embed counts with a "hide if 0" rule and needs the raw numbers;
  // Health shows a count + "Processing/Idle" sub). Per 559 §8 / 594 §17.2 "share the value, keep the
  // shell", there is no single shared VALUE worth centralizing — it stays shell-local in both.

  // Tempdoc 663 — AI-engine (Brain) leaf facts, sourced from `aiState.runtime`/`inference` (the
  // shared, always-on poller), NOT `aiState.status.gpu` (the Worker's own GPU — a distinct signal;
  // `core.gpu.accel` above owns that one). `s.inference === null` means "not polled yet" (unknown);
  // once polled, an absent value (no active model / no GPU) is a real `null`, not a fabricated one.
  'core.ai.model': {
    label: 'Model',
    source: {
      kind: 'observed',
      read: (s) => (s.inference ? s.runtime.modelLabel || null : undefined),
      // The raw model id (pre-friendlified) as the tooltip — matches the prior inline rendering.
      provenance: (s) => s.runtime.modelId ?? undefined,
    },
  },
  // Tempdoc 883 decision 1 / ADR-0047 — the window is a DERIVED resource, so the fact says which
  // rung was chosen and why when the engine we launched published it (`runtime.contextWindowDerived`,
  // the same record the runtime manifest carries as `ai.contextWindow`). The token count itself
  // still comes from the OBSERVATION (`/props` `n_ctx`, `runtime.contextWindow`); the derivation is
  // parenthetical provenance beside it, never a substitute for it. Nothing is appended when no
  // server this process launched is running (adopted/external engine, or nothing started).
  'core.ai.contextWindow': {
    label: 'Context',
    source: {
      kind: 'observed',
      read: (s) =>
        s.inference
          ? s.runtime.contextWindow != null
            ? `${formatCount(s.runtime.contextWindow)} tokens${derivedWindowSuffix(s)}`
            : null
          : undefined,
    },
  },
  'core.ai.gpu': {
    label: 'GPU',
    source: {
      kind: 'observed',
      read: (s) => (s.inference ? s.runtime.gpu?.description || null : undefined),
    },
  },
};

/**
 * Tempdoc 883 decision 1 — the parenthetical that explains a derived window: `" (top-rung, 2 slots,
 * q8_0)"`, or the empty string when the engine published no derivation. Reason first because it is
 * the part that answers "why this number" (`stepped-from:32768` is the case that matters — the
 * launch did NOT get the rung it planned); slots and KV type follow only when present, so a partial
 * record degrades to the fields it actually has instead of rendering `null`.
 */
function derivedWindowSuffix(s: AiState): string {
  const d = s.runtime.contextWindowDerived;
  if (!d) return '';
  const parts: string[] = [];
  if (d.reason) parts.push(d.reason);
  if (d.slots != null) parts.push(`${d.slots} slot${d.slots === 1 ? '' : 's'}`);
  if (d.kvType) parts.push(d.kvType);
  return parts.length > 0 ? ` (${parts.join(', ')})` : '';
}

/** Map a boolean capability flag to the tri-state ObservedRead contract (name-only when present). */
function presence(s: AiState, flag: boolean | null | undefined): string | null | undefined {
  if (!s.status || flag === undefined || flag === null) return undefined; // unknown
  return flag ? '' : null; // present (name-only) | absent
}

/** Is `id` a known fact? (used by the chip facet to decide factual vs decorative). */
export function isFact(id: string): boolean {
  return Object.prototype.hasOwnProperty.call(FACTS, id);
}

/**
 * Project a fact-ref into its current displayed value. Pure over `(id, aiState)`. An unknown id or
 * a null `aiState` yields a `unknown`-presence result so the caller never renders a fabricated value.
 */
export function projectFact(id: string, aiState: AiState | null): ProjectedFact {
  const def = FACTS[id];
  if (!def) return { name: id, value: null, presence: 'unknown' };

  if (def.source.kind === 'constant') {
    return { name: def.label, value: def.source.value, presence: 'present' };
  }

  if (!aiState) return { name: def.label, value: null, presence: 'unknown' };

  const raw = def.source.read(aiState);
  if (raw === undefined) return { name: def.label, value: null, presence: 'unknown' };
  if (raw === null) return { name: def.label, value: null, presence: 'absent' };
  const provenance = def.source.provenance?.(aiState);
  const confidence = def.source.confidence?.(aiState);
  return {
    name: def.label,
    value: raw === '' ? null : raw,
    presence: 'present',
    ...(provenance ? { provenance } : {}),
    ...(confidence ? { confidence } : {}),
  };
}
