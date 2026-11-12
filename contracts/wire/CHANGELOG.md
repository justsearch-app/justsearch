# Wire Contract Changelog

Per `docs/decisions/0039-contract-substrate.md`
§"Why governance must be mechanical" + slice 3a-1-8 Phase 5b: every change to
`contracts/wire/spec/*` requires an entry here. Reviewer applies the
evolution-rule classification (additive optional = patch; additive required =
minor; rename/remove = major). Mechanical structural-diff (`buf breaking`) is
the V1.5 follow-up per slice 3a-1-8f.

## [Unreleased]

### Changed

- `/api/status` JSON (`worker.core`, outside the protobuf spec): while the Engine has no Worker
  reading (not connected yet, first answer pending, status RPC failed), `indexedDocumentsUnknown`
  is true. It used to publish the placeholder zero `indexedDocuments` and `searchableDocuments` as
  known counts. Readers of the field already show such counts as unknown; a genuinely empty index stays a
  known zero. The flag's meaning is stated in full (health-readiness contract): while it is true, every
  count the Worker supplies in that status (documents, pending jobs, index size, job-queue, failed-job
  and enrichment counts) is unknown, here and on `/api/knowledge/status`; first-party readers no longer
  read any of them as 0. No field, type or value changes, no `.proto` change, so no VERSION bump; no
  stored state.

- Indexed-root JSON view (`SSOT/schemas/indexed-root.v1.json`, outside the protobuf spec): job
  counts and their enrichment coverage are nullable. A failed count query reports null (unknown)
  instead of a fabricated zero; an observed empty queue stays zero. First-party readers treat null
  as unconfirmed. No `.proto` change, so no VERSION bump; no stored state.

- Wire3.0.0 replaces schema1 status component messages with schema2 API, index,
  encoders and generative component states. The old StatusResponse field4/name are
  reserved; the replacement uses field35 and distinct messages. This is a breaking
  migration coordinated with the first-party producers and consumers (Lane F D1).

### Added

- Wire 3.0.5: CoreIndexView field 20 `ownSearchableDocuments` is the searchable population
  without the app's internal collections (bundled Help, agent history), from the same snapshot
  as `searchableDocuments`; absent when the index counts are unknown. Additive optional; no
  stored state.

- Wire 3.0.3: SearchFilters field 17 `sizeBytes` echoes inclusive source-file byte bounds.
  ByteRange uses optional integer endpoints so zero remains a real bound. Additive optional;
  no new index fields or saved state (WP2.3a).

- Wire 3.0.2: OperationHistoryEntry field 12 `message` mirrors the existing optional
  read-time refusal explanation, including its support code. Additive optional; no stored state.
- Operation history includes OPERATION_OUTCOME_UNDONE for successful undo (Lane F C2).
- Wire 3.0.1: StatusResponse field 36 `operating` (OperatingStateView) reports chat, indexing
  and GPU operating state (ADR-0050), including the external-use reading
  `operating.gpu.externalUsedBytes`. Additive optional.

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
