// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 3a-1-8e (ship-option a, 2026-05-07) — FE consumer for contract events
 * on `/infra/capabilities/stream`.
 *
 * The substrate's runtime-continuous negotiation surface lives at the
 * Resource layer: contract events ride the existing capabilities stream as
 * typed `ContractEvent` payloads (single-message-with-discriminator pattern
 * per `contracts/wire/contract_events.proto`).
 *
 * This module:
 *  - Decodes incoming JSON-Struct payloads into a typed event view.
 *  - Lets consumers subscribe by event kind (with optional filter).
 *  - Provides one-way `emitReactionOutcome(...)` for consumers to report
 *    APPLIED / REJECTED / DEGRADED outcomes back to the substrate's
 *    observability layer (xDS-narrow pattern).
 *
 * Design decisions (settled by the parent slice's investigation):
 *  - `kind` is the primary discriminator (CloudEvents pattern).
 *  - `capability-registered` / `capability-unregistered` use stable per-
 *    capability `id` + `type` discriminator (LSP pattern).
 *  - `catalog-membership-changed` is a delta shape (Envoy delta xDS
 *    pattern) for first-party catalog mutations.
 *  - Wire-version evolution (`contract-version-changed`) is intentionally
 *    absent — additive Protobuf contract evolution makes it structurally
 *    non-breaking mid-session.
 *  - Static + dynamic registration non-collision: V1 default is last-
 *    write-wins reconciliation with WARN log on duplicate `id`; LSP-
 *    strict enforcement is escalation when a real consumer surfaces.
 *
 * Producer status (2026-05-07): backend producers other than the test
 * `__emitForTest` hook are not yet wired in production. The FE module
 * ships ready-to-consume; runtime emit-site wiring follows in slice
 * `3a-1-8e-runtime` once 3a-1-8b lands real producers.
 */

export type ContractEventKind =
  | 'capability-registered'
  | 'capability-unregistered'
  | 'catalog-membership-changed'
  | 'reaction-outcome';

export type ReactionOutcomeKind = 'APPLIED' | 'REJECTED' | 'DEGRADED';

/**
 * Decoded contract event view. Fields are populated per-`kind` per the
 * proto's CEL invariants; consumers dispatch by `kind` and read the
 * relevant fields.
 */
export interface ContractEvent {
  kind: ContractEventKind | string;
  // capability-registered / capability-unregistered / reaction-outcome
  capabilityId?: string;
  capabilityType?: string;
  // catalog-membership-changed
  category?: string;
  added?: readonly string[];
  removed?: readonly string[];
  modified?: readonly string[];
  // reaction-outcome
  consumerId?: string;
  outcome?: ReactionOutcomeKind | string;
  reason?: string;
  // Per-kind opaque payload (JSON-shaped) — capability-registered carries
  // the contributed capability's typed manifest here.
  attributes?: Readonly<Record<string, unknown>>;
}

export type ContractEventListener = (event: ContractEvent) => void;

/**
 * Subscription filter. Each function checks one axis; all-or-nothing
 * conjunction at dispatch.
 */
export interface ContractEventFilter {
  /** Match `capability-registered` / `capability-unregistered` by `capabilityType`. */
  capabilityType?: string;
  /** Match `catalog-membership-changed` by `category`. */
  category?: string;
}

interface Subscription {
  kind: ContractEventKind;
  filter: ContractEventFilter;
  listener: ContractEventListener;
}

let subscriptions: Set<Subscription> = new Set();
let reactionOutcomeSink: ((event: ContractEvent) => void) | undefined;

/**
 * Subscribe to contract events of a given kind, optionally narrowed by
 * filter. Returns an unsubscribe function.
 */
export function subscribe(
  kind: ContractEventKind,
  listener: ContractEventListener,
  filter: ContractEventFilter = {},
): () => void {
  const sub: Subscription = { kind, filter, listener };
  subscriptions.add(sub);
  return () => {
    subscriptions.delete(sub);
  };
}

/**
 * Emit a reaction-outcome event one-way back to the substrate's
 * observability sink. xDS-narrow pattern: consumers report their
 * reaction status; substrate gains visibility into "which capability
 * changes consumers couldn't apply" without the bidirectional ACK loop.
 *
 * In V1 the sink is wired into the dev-stack telemetry path or buffered
 * locally; production wiring follows slice 3a-1-8e-runtime once
 * `CapabilitiesChangeRegistry` exposes a consumer-side emit API.
 */
export function emitReactionOutcome(
  capabilityId: string,
  consumerId: string,
  outcome: ReactionOutcomeKind,
  reason?: string,
): void {
  const event: ContractEvent = {
    kind: 'reaction-outcome',
    capabilityId,
    consumerId,
    outcome,
    reason,
  };
  if (reactionOutcomeSink) {
    reactionOutcomeSink(event);
  } else if (typeof console !== 'undefined') {
    // Default sink: log so the outcome is visible in dev-stack stdout.
    // Production wiring replaces this via `__setReactionOutcomeSink`.
    const detail =
      reason !== undefined ? `${outcome} (${reason})` : outcome;
    console.debug(
      `[contractEvents] reaction-outcome: ${consumerId} → ${capabilityId} = ${detail}`,
    );
  }
}

/**
 * Dispatch an inbound contract event to matching subscribers.
 *
 * Used internally by the SSE consumer that decodes
 * `/infra/capabilities/stream` UPDATE frames; exported for tests.
 */
export function dispatchContractEvent(event: ContractEvent): void {
  for (const sub of subscriptions) {
    if (sub.kind !== event.kind) continue;
    if (
      sub.filter.capabilityType !== undefined &&
      event.capabilityType !== sub.filter.capabilityType
    ) {
      continue;
    }
    if (
      sub.filter.category !== undefined &&
      event.category !== sub.filter.category
    ) {
      continue;
    }
    try {
      sub.listener(event);
    } catch {
      // swallow listener errors — one bad subscriber shouldn't break others
    }
  }
}

/**
 * Discriminate a decoded event as a contract event vs a legacy
 * `CapabilityChangeEvent` payload (both ride
 * `/infra/capabilities/stream` UPDATE frames). Legacy
 * `CapabilityChangeEvent` carries only `kind` ("snapshot" / "added" /
 * "modified" / "removed" / "heartbeat") + `detail`; contract events
 * always carry at least one of {capabilityId, category, consumerId}.
 *
 * Structural rather than closed-set so the discriminator is forward-
 * compatible with future contract-event variants per ADR-09a.
 */
export function isContractEvent(event: ContractEvent): boolean {
  return (
    event.capabilityId !== undefined ||
    event.category !== undefined ||
    event.consumerId !== undefined
  );
}

/**
 * Decode a wire payload (the `payload` field of an SseEnvelope on the
 * capabilities stream) into a typed `ContractEvent`. Returns `undefined`
 * if the payload doesn't look like a contract event (missing or
 * non-string `kind`).
 */
export function decodeContractEvent(payload: unknown): ContractEvent | undefined {
  if (typeof payload !== 'object' || payload === null) return undefined;
  const obj = payload as Record<string, unknown>;
  const kind = obj.kind;
  if (typeof kind !== 'string' || kind.length === 0) return undefined;
  return {
    kind,
    capabilityId: typeof obj.capabilityId === 'string' ? obj.capabilityId : undefined,
    capabilityType: typeof obj.capabilityType === 'string' ? obj.capabilityType : undefined,
    category: typeof obj.category === 'string' ? obj.category : undefined,
    added: Array.isArray(obj.added) ? (obj.added.filter((s) => typeof s === 'string') as readonly string[]) : undefined,
    removed: Array.isArray(obj.removed) ? (obj.removed.filter((s) => typeof s === 'string') as readonly string[]) : undefined,
    modified: Array.isArray(obj.modified) ? (obj.modified.filter((s) => typeof s === 'string') as readonly string[]) : undefined,
    consumerId: typeof obj.consumerId === 'string' ? obj.consumerId : undefined,
    outcome: typeof obj.outcome === 'string' ? obj.outcome : undefined,
    reason: typeof obj.reason === 'string' ? obj.reason : undefined,
    attributes: typeof obj.attributes === 'object' && obj.attributes !== null
      ? (obj.attributes as Readonly<Record<string, unknown>>)
      : undefined,
  };
}

/**
 * Test-only: inject a synthetic contract event into the dispatch pipeline.
 *
 * Used by integration tests that exercise the consumer-side reaction
 * policies without standing up a backend producer. Production callers
 * should not use this; the runtime follow-up slice
 * (`slices/3a-1-8e-runtime.md`) wires real producers via
 * `CapabilitiesChangeRegistry`.
 */
export function __emitForTest(event: ContractEvent): void {
  dispatchContractEvent(event);
}

/**
 * Test-only: redirect reaction-outcome emissions to a custom sink. Used
 * by integration tests that capture emitted outcomes for assertion.
 */
export function __setReactionOutcomeSinkForTest(
  sink: ((event: ContractEvent) => void) | undefined,
): void {
  reactionOutcomeSink = sink;
}

/**
 * Test-only: clear all subscriptions and reset module state.
 */
export function __resetForTest(): void {
  subscriptions = new Set();
  reactionOutcomeSink = undefined;
}
