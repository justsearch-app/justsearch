# Wire Contract Changelog

Per `docs/decisions/0039-contract-substrate.md`
§"Why governance must be mechanical" + slice 3a-1-8 Phase 5b: every change to
`contracts/wire/spec/*` requires an entry here. Reviewer applies the
evolution-rule classification (additive optional = patch; additive required =
minor; rename/remove = major). Mechanical structural-diff (`buf breaking`) is
the V1.5 follow-up per slice 3a-1-8f.

## [Unreleased]

### Changed

- Wire3.0.0 replaces schema1 status component messages with schema2 API, index,
  encoders and generative component states. The old StatusResponse field4/name are
  reserved; the replacement uses field35 and distinct messages. This is a breaking
  migration coordinated with the first-party producers and consumers (Lane F D1).

### Added

- Operation history includes OPERATION_OUTCOME_UNDONE for successful undo (Lane F C2).

## [0.2.0] - 2026-05-07

### Added — contract events for Resource-layer runtime-continuous negotiation (slice 3a-1-8e)

- `contract_events.proto` — `ContractEvent` message + `ReactionOutcome`
  enum. Single-message-with-discriminator pattern per ADR-09a (precedent:
  `HealthEventBody`). Four `kind` variants:
  - `capability-registered` — LSP-style capability registration with
    stable `capability_id` + `capability_type` discriminator + typed
    payload via `attributes`.
  - `capability-unregistered` — symmetric removal.
  - `catalog-membership-changed` — Envoy delta-stream shape for
    first-party catalog mutations.
  - `reaction-outcome` — xDS-narrow one-way observability emit
    (consumer → substrate) reporting `REACTION_OUTCOME_APPLIED` /
    `_REJECTED` / `_DEGRADED`.

  Carried as the `SseEnvelope.payload` JSON-Struct on UPDATE frames of
  `/infra/capabilities/stream`. No new endpoint; reuses the existing
  multi-frame Resource. Per-`kind` required-field invariants enforced
  via CEL.

  Classification: **minor** (additive new message type; no existing
  field renamed or removed; consumers that don't subscribe to
  `/infra/capabilities/stream` UPDATE frames are unaffected).

  Reference: slices/3a-1-8e-runtime-continuous-negotiation.md (ship-
  option a, 2026-05-07).

## [0.1.0] - 2026-05-05

### Added — initial wire-Category contract substrate

- `health.proto` — HealthEvent + body family (lifecycle / condition /
  threshold / unknown), with the single-message-with-discriminator pattern
  per ADR-09a §"Decision". Discriminator field carries no `(buf.validate.field).string.in`
  constraint per ADR-09a's "Future Agents Must Not" rule (forward-compat).
- `status.proto` — StatusResponse envelope with mixed snake_case / camelCase
  field naming (per-field `[json_name]` overrides preserve the existing wire
  format).
- `knowledge.proto` — KnowledgeStatusView with dual-name aliases (canonical
  + deprecated) and CEL consistency invariant.
- `capabilities.proto` — CapabilitiesView + nested types per slice 443.
- `runtime.proto` — RuntimeContext per slice 440.
- `operation_history.proto` — OperationHistoryEntry per slice 444b.
- `metrics.proto` — TimeseriesSnapshot + MetricRef + RenderHint per slice
  3a-1-4.
- `stream.proto` — SseEnvelope + SseFrameKind + StreamId per slice 436.
