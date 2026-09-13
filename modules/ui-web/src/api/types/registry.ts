// SPDX-License-Identifier: Apache-2.0
/**
 * The FE's stable import surface for the registry primitives (Resource, Operation) and their nested
 * value types — a re-export BARREL over generated single-authority projections, NOT hand-written types.
 *
 * Tempdoc 560 §4c (both phases landed): Resource / Presentation / Provenance (Phase A) and Operation
 * (Phase B) are GENERATED from the backend record schemas (`record → JSON-Schema → {TS, Zod}` via
 * `scripts/codegen/gen-wire-schema-types.mjs`), and the enum vocabulary is aliased to the generated
 * `registry-enums.generated.ts`. The FE-facing types below `Tighten<>` / `Omit<>`-compose the
 * all-optional generated wires into the required wire shape so non-defensive readers keep compiling;
 * the generated Zod (`resourceWireSchema` / `operationWireSchema`) is the runtime parse-boundary
 * authority. This retires the `AuditPolicy 'FULL'` enum-drift defect class: the Java record is the one
 * authority, every FE representation is a derived projection, and the `wire-type-single-authority` +
 * `contract-projection` gates fail the build on a hand-authored second copy.
 *
 * Authoritative source: `modules/app-agent-api/.../registry/` (Resource.java, Operation.java, …) via
 * the `UIResourceView` / `UIOperationView` wire-view records the emitters serialize.
 */

// §4.1/§4.3 anti-drift (tempdoc 560): the enum vocabulary below is GENERATED from the Java enum
// authority (registry-enums.generated.ts) — these unions are aliases, not hand-authored mirrors.
import type {
  Audience as GenAudience,
  AuditPolicy as GenAuditPolicy,
  ExecutorTag as GenExecutorTag,
  HistoryMode as GenHistoryMode,
  OnOverflow as GenOnOverflow,
  PathPolicy as GenPathPolicy,
  RenderHint as GenRenderHint,
  RiskTier as GenRiskTier,
  SubscriptionMode as GenSubscriptionMode,
  TrustTier as GenTrustTier,
} from '../generated/registry-enums.generated.js';

// Tempdoc 560 §4c (Phase A): Resource / Presentation / Provenance are now a GENERATED
// single-authority projection (record→JSON-Schema→{TS,Zod}). This file stays the FE's stable
// import surface — a re-export barrel. The generated Zod (`resourceWireSchema`) is the runtime
// authority (consumed at the `ResourceCatalogClient` parse boundary); the FE-facing types below
// `Tighten<>` the all-optional generated wire types to the required wire shape so non-defensive
// readers keep compiling. Phase B (tempdoc 560 §4c) landed too: Operation + its nested types are now a
// GENERATED projection of `OperationWire` (the `UIOperationView` record the `UIOperationEmitter`
// serializes), with `operationWireSchema` as the runtime parse-boundary authority — no hand-authoring,
// no drift. `AvailabilityExpression` stays opaque (`unknown`) by design (the FE only forwards it).
import { z } from 'zod';
import {
  type ResourceWire,
  resourceWireSchema,
} from '../generated/schema-types/resource';
import {
  type OperationWire,
  operationWireSchema,
} from '../generated/schema-types/operation';
import type { PresentationWire } from '../generated/schema-types/presentation';
import type { ProvenanceWire } from '../generated/schema-types/provenance';


/** The generated runtime validator for a Resource — used to build the catalog parse boundary. */
export { resourceWireSchema };

// ============================================================
// Discriminator vocabularies (mirror Java enums)
// ============================================================

/**
 * Information-shape axis on a Resource. Mirrors `Category.java`.
 *
 * Slice 448 phase 6 (2026-05-07) retired LOG_TAIL per CONFLICT-LEDGER C-012 path-b.
 * Operator-trace surfaces (engine-log, etc.) are modeled as the sibling
 * DiagnosticChannel primitive — see `api/types/diagnostic.ts`.
 */
export type Category =
  | 'STATE'
  | 'EVENT_STREAM'
  | 'HISTORY'
  | 'TABULAR'
  | 'TIMESERIES';

/** Closed list useful for exhaustive switches and test fixtures. */
export const CATEGORIES: readonly Category[] = [
  'STATE',
  'EVENT_STREAM',
  'HISTORY',
  'TABULAR',
  'TIMESERIES',
] as const;

/** Subscription transport. Generated alias of the Java `SubscriptionMode` authority. */
export type SubscriptionMode = GenSubscriptionMode;

/** Privacy axis path policy. Generated alias of the Java `PathPolicy` authority. */
export type PathPolicy = GenPathPolicy;

/** History retention mode. Generated alias of the Java `HistoryPolicy.Mode` authority. */
export type HistoryMode = GenHistoryMode;

/** History overflow behavior. Generated alias of the Java `OnOverflow` authority. */
export type OnOverflow = GenOnOverflow;

/** Provenance contributor tier. Generated alias of the Java `TrustTier` authority. */
export type ProvenanceTier = GenTrustTier;

/** Operation risk tier. Generated alias of the Java `RiskTier` authority. */
export type RiskTier = GenRiskTier;

/** Operation audit policy. Generated alias of the Java `AuditPolicy`
 * authority (`NONE | METADATA_ONLY`). Drift history: the hand-authored FE type
 * once declared a third variant `'FULL'` against the wire's `FULL_PAYLOAD` —
 * fixed in 511-followup-2, and now structurally impossible because the union is
 * the generated `registry-enums.generated.ts` projection, not a hand mirror.
 * Tempdoc 879 then deleted `FULL_PAYLOAD` outright: no Operation declared it and
 * the redaction machinery it named never existed. */
export type AuditPolicy = GenAuditPolicy;

/**
 * Audience axis. Mirrors `Audience.java`. Tempdoc 511 §"Aggregate
 * Surfacing Substrate" Phase 0: previously absent from the FE
 * Operation type (round-tripped via the index-signature escape
 * hatch). Now first-class so canonical strategies can consume it.
 */
export type Audience = GenAudience;

/**
 * Executor channel a callable is reachable from. Generated alias of the Java
 * `ExecutorTag` authority (tempdoc 511 Phase 0; 560 anti-drift).
 */
export type ExecutorTag = GenExecutorTag;

// ============================================================
// Common value types
// ============================================================

/**
 * Operation id. Serialized as a bare string (Java record uses @JsonValue).
 * Pattern: `core.<name>` or `vendor.<plugin-id>.<name>`.
 */
export type OperationRef = string;

/**
 * Resource id. Per slice 447-impl-A: typed distinct from OperationRef so cross-
 * primitive references stay unambiguous. Wire format identical (bare string).
 */
export type ResourceRef = string;

/**
 * Prompt id. Per slice 447-impl-A: typed distinct from OperationRef. Wire format
 * identical (bare string).
 */
export type PromptRef = string;

/**
 * Argument-bearing reference to an Operation invocation. Per slice 447-impl-B:
 * recovery slots widen from a bare OperationRef to this object so static defaults
 * (e.g., `force=true` for `core.reindex`) ride alongside the target.
 */
export interface OperationInvocation {
  target: OperationRef;
  /** JSON object source text encoding default arguments. `"{}"` for no defaults. */
  defaultArgsJson: string;
}

/** i18n key. Serialized as a bare string. */
export type I18nKey = string;

/**
 * Provenance for catalog entries. The required 3-field shape
 * (`tier` / `contributorId` / `version`) mirrors `Provenance.java`.
 *
 * Per Tempdoc 543 §13.2.3.1 (multi-axis Provenance), the kernel side
 * additionally tracks `identity` / `review` / `capability` /
 * `installedAt` as TS-only optional fields. These do NOT cross the
 * wire — the Java side serializes only the 3-field shape; the
 * extension fields are populated locally by the manifest installer
 * + plugin trust handshake and consumed by chrome chip rendering.
 *
 * `tier` remains the load-bearing display field today; future chrome
 * passes derive richer chips from the extension fields.
 */
export type Provenance = Omit<ProvenanceWire, 'identity'> & {
  // ── Tempdoc 543 §13.2.3.1 multi-axis extension (TS-side only) ──
  // The 3-field wire shape (tier/contributorId/version) comes from the generated `ProvenanceWire`.
  // `identity` is present-as-null on the Resource wire but ABSENT on the Operation wire (3-field
  // ProvenanceView), and is also stamped locally by the installer — so it is optional here, with a
  // signature-optional inner shape for the local stamp. The Resource Zod still validates it present.
  readonly identity?: { readonly verified: boolean; readonly signature?: string | null } | null;
  /** Review signal: was the contributor's code human-reviewed, and when? */
  readonly review?: {
    readonly lastReviewedAt: string | null;
    readonly reviewer?: string;
  };
  /** Capability signal: declared capabilities (from the ContributionManifest). */
  readonly capability?: readonly string[];
  /** Install timestamp (ISO-8601); populated by the manifest installer. */
  readonly installedAt?: string;
};

/**
 * Display copy + iconography hooks — the FE view of the generated `PresentationWire`. `iconHint` /
 * `category` are optional here because the Operation wire OMITS them (field-level `@JsonInclude`),
 * while the Resource wire sends them present-as-null; the always-present copy keys stay required. The
 * generated per-surface Zod still validates each wire's actual shape.
 */
export type Presentation = Omit<PresentationWire, 'iconHint' | 'category'> & {
  iconHint?: string | null;
  category?: string | null;
};

/**
 * History retention semantics — DERIVED from the generated Resource wire (tempdoc 560 §4c). The
 * precise schema (mode/onOverflow/resumeWindow required non-null; capacity/retention present-as-null)
 * replaces the former hand-mirror, removing the nullability type-lies it carried.
 */
export type HistoryPolicy = NonNullable<ResourceWire['history']>;

/** Privacy axis — DERIVED from the generated Resource wire (tempdoc 560 §4c). */
export type Privacy = ResourceWire['privacy'];

/**
 * Slice 490 §4.C — renderer-selection hint for advisory-shaped Resource events.
 * Mirrors `RenderHint.java`. Drives the FE chrome's toast / inbox / ack-required
 * dispatch when the upstream Resource has `kind = KIND_ADVISORY`.
 * Generated alias of the Java `RenderHint` authority.
 */
export type RenderHint = GenRenderHint;

/**
 * Slice 490 §4.C — sibling axis to OperationPolicy. Declared on advisory-shaped
 * Resources to govern unprompted system → user emission. v1 carries `renderHint`
 * (renderer-selection) + `dedupeWindow` (per-class repeat-suppression at the
 * backend broadcast site). Mirrors `EmissionPolicy.java`.
 *
 * - `dedupeWindow` is the wire serialization of the Java `Optional<Duration>` —
 *   the ISO-8601 duration string (e.g. "PT1M") when present, `null` when absent.
 *   The backend already enforces the dedupe at the change-registry boundary; the
 *   FE doesn't re-enforce, but the field is here so v2 generic discovery (when
 *   the FE store walks the catalog for advisory Resources) can read the policy.
 */
export type EmissionPolicy = NonNullable<ResourceWire['emissionPolicy']>;

/**
 * Slice 490 §4.A — reserved `Resource.kind` value for advisory-shaped Resources.
 * A generic FE renderer filters the Resource Catalog by this value to discover
 * advisory streams across all classes. Mirrors `Resource.KIND_ADVISORY` on Java.
 */
export const KIND_ADVISORY = 'advisory-event-stream';

// ============================================================
// Resource primitive
// ============================================================

/**
 * Wire shape of a Resource catalog entry. Mirrors
 * `modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/Resource.java`.
 *
 * Slice 3a.1.9 additions: `primaryKey` field (required non-blank for TABULAR;
 * empty string for non-TABULAR Resources where the concept doesn't apply).
 */
/**
 * Wire shape of a Resource catalog entry — a GENERATED single-authority projection (tempdoc 560
 * §4c). The scalar/enum/array keys are tightened to the required wire shape; the nested object
 * fields are overridden with the FE-facing named types (themselves generated-backed or stable
 * hand types). The discriminator `type:"resource"` is omitted from the FE type (the generated Zod
 * still validates it on the wire). `schema` is the doc URL; `primaryKey` is required for TABULAR.
 */
export type Resource = Omit<
  ResourceWire,
  'type' | 'presentation' | 'provenance' | 'history' | 'privacy' | 'consumers' | 'recovery' | 'emissionPolicy'
> & {
  presentation: Presentation;
  provenance: Provenance;
  history: HistoryPolicy | null;
  privacy: Privacy;
  consumers: ConsumerHook[];
  recovery: OperationRef | null;
  // Present-as-null on the wire (Optional<EmissionPolicy> → null for non-advisory Resources); optional
  // in the ergonomic FE view for partial construction, consistent with identity/iconHint. The
  // generated resourceWireSchema still requires it at the parse boundary.
  emissionPolicy?: EmissionPolicy | null;
};

/**
 * Catalog envelope returned by `GET /api/registry/resources`.
 * Wire shape mirrors `RegistryController.handleResources`.
 */
export interface ResourceCatalog {
  $schema?: string;
  schemaVersion: string;
  catalogVersion: number;
  namespace: string;
  primitive: 'Resource';
  entries: Resource[];
}

/**
 * Runtime validator for the Resource catalog envelope (tempdoc 560 §4c parse boundary). The thin
 * envelope is hand-composed (the Java `ResourceCatalog` is an interface, not a wire record); each
 * entry is validated by the GENERATED `resourceWireSchema` — the single runtime authority for the
 * element shape. Consumed by `ResourceCatalogClient` via `parseWireContract`.
 */
export const resourceCatalogSchema = z.object({
  $schema: z.string().optional(),
  schemaVersion: z.string(),
  catalogVersion: z.number(),
  namespace: z.string(),
  primitive: z.literal('Resource'),
  entries: z.array(resourceWireSchema),
});

// ============================================================
// Operation primitive (subset needed by item-Operation rendering)
// ============================================================

// Tempdoc 560 §4c Phase B: the Operation nested types are DERIVED from the generated `OperationWire`
// (the UIOperationView record's projection) — no hand-authoring, no drift. Tightened where consumers
// read fields non-defensively.

/**
 * Operation interface escape hatch. Wire shape: `inputs`/`result` are arbitrary JSON-Schema source
 * (opaque `unknown`), `errors` a string list, `uiHints` a typed map. The FE only forwards these.
 */
export type OperationInterface = NonNullable<OperationWire['intf']>;

/** Confirmation strategy (discriminated by `kind`). */
export type ConfirmStrategy = NonNullable<NonNullable<OperationWire['policy']>['confirm']>;

/**
 * Operation policy bundle (risk / confirm / audit / undoSupported always present). `inverseOperationId`
 * is present-as-null on the wire (null when no inverse); optional in the ergonomic FE view for partial
 * construction, consistent with identity/iconHint. The generated operationWireSchema requires it.
 */
export type OperationPolicy = Omit<
  NonNullable<OperationWire['policy']>,
  'inverseOperationId'
> & {
  inverseOperationId?: string | null;
};

/** Discovery-time availability gate. */
export type OperationAvailability = NonNullable<OperationWire['availability']>;

/** Cross-primitive lineage (affects / supersedes). */
export type OperationLineage = NonNullable<OperationWire['lineage']>;

/**
 * Per-Operation consumer registration. Mirrors `ConsumerHook.java`.
 * Declared by surfaces (or the catalog) to record who consumes the
 * Operation at what audience floor. Tempdoc 511 Phase 0 first-class
 * addition.
 */
export type ConsumerHook = ResourceWire['consumers'][number];

/**
 * Operation catalog entry. Tempdoc 511 Phase 0 (2026-05-18): the
 * previous shape had `[key: string]: unknown` as a pass-through
 * escape hatch; all aggregate-substrate work depends on the type
 * being closed over its wire fields so canonical strategies can
 * exhaustiveness-check field consumption. The fields below mirror
 * the JSON shape emitted by `UIOperationEmitter.java` exactly,
 * with casing normalized to uppercase by the catalog client (see
 * `normalizeOperationFromWire` in `OperationCatalogClient.ts`).
 */
/**
 * Wire shape of an Operation catalog entry — a GENERATED single-authority projection (tempdoc 560
 * §4c Phase B) of the `UIOperationView` record the emitter serializes. Scalar/enum/array keys are
 * tightened; nested objects use the FE-facing named types; the discriminator `type:"operation"` is
 * omitted from the FE type (the generated Zod still validates it). The interface field is `intf`
 * (the wire name; the historical `interface` alias was a never-read type-lie, dropped here).
 */
export type Operation = Omit<
  OperationWire,
  'type' | 'presentation' | 'provenance' | 'consumers' | 'policy' | 'lineage' | 'availability' | 'intf'
> & {
  presentation: Presentation;
  provenance: Provenance;
  consumers: ConsumerHook[];
  policy: OperationPolicy;
  lineage: OperationLineage;
  availability: OperationAvailability;
  intf: OperationInterface;
};

/** Catalog envelope returned by `GET /api/registry/operations`. */
export interface OperationCatalog {
  $schema?: string;
  schemaVersion: string;
  catalogVersion: number;
  namespace: string;
  primitive: 'Operation';
  entries: Operation[];
}

/**
 * Runtime validator for the Operation catalog envelope (tempdoc 560 §4c Phase B parse boundary).
 * Each entry is validated by the GENERATED `operationWireSchema` — the single runtime authority for
 * the Operation wire shape (now faithful: the emitter serializes the `UIOperationView` record).
 */
export const operationCatalogSchema = z.object({
  $schema: z.string().optional(),
  schemaVersion: z.string(),
  catalogVersion: z.number(),
  namespace: z.string(),
  primitive: z.literal('Operation'),
  entries: z.array(operationWireSchema),
});
