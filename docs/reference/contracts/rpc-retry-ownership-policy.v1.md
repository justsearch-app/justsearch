---
title: RPC Retry Ownership Policy v1
type: contract
status: stable
updated: 2026-02-19
description: Method-level idempotency and retry ownership policy for runtime resilience.
---

# RPC Retry Ownership Policy v1

## Purpose

Define the canonical method-level policy for:
1. Idempotency classification.
2. Retry ownership (transport vs caller).
3. Circuit-breaker ownership.

Machine-readable companion artifacts:
1. `scripts/resilience/contracts/rpc-retry-ownership-matrix.v1.json`
2. `scripts/resilience/contracts/rpc-retry-ownership-matrix.v1.schema.json`
3. `scripts/resilience/contracts/grpc-retry-policy-profiles.v1.json`
4. `scripts/resilience/contracts/grpc-retry-policy-profiles.v1.schema.json`

## Scope

This contract covers the current runtime resilience surfaces:
1. `io.justsearch.ipc.SearchService` (8 methods)
2. `io.justsearch.ipc.IngestService` (19 methods)
3. `io.justsearch.ipc.HealthService` (1 method)
4. `io.justsearch.ipc.v1.AiService` (3 methods)
5. `io.justsearch.ipc.v1.HealthService` (3 methods)

Baseline method count: 35.

## Idempotency Classes

1. `STRICT`: replay is semantically stable and side-effect safe.
2. `EFFECTIVE_RETRY_SAFE`: not strictly identical response/state, but replay is operationally safe.
3. `CONDITIONAL`: replay safety depends on idempotency-key or dedupe contract.
4. `NON_IDEMPOTENT`: replay can cause invalid or duplicated side effects.

## Retry Ownership Model

1. `transportRetryOwner=grpc_service_config`
Rule: only allowed for `STRICT` and `EFFECTIVE_RETRY_SAFE`.
2. `callerRetryOwner`
Rule: default `none`; only one retry owner by default.
3. Dual ownership is forbidden unless explicit allowlist (`allowDualRetryOwner=true`) exists with bounded rationale.
4. `retryPolicyId` is required when `transportRetryOwner=grpc_service_config`, and must be `null` otherwise.
5. `retryPolicyId` must resolve to a known entry in
   `grpc-retry-policy-profiles.v1.json`.

## Operation Class Semantics

1. `READ`: no state mutation.
2. `WRITE`: mutates operational or queue state.
3. `CONTROL`: lifecycle or orchestration control transition.
4. `DESTRUCTIVE`: delete/prune semantics.

Operation class informs review strictness but does not by itself grant retry ownership.

## Deadline Category Semantics

1. `STANDARD`
2. `CONTENT_FETCH`
3. `VDU_OPERATION`
4. `INDEX_GC`
5. `LONG_RUNNING`

These categories reflect runtime call budgets and are tracked per method in the matrix.

## Enforcement

The contract's machine-readable companions (the matrix + retry-policy-profile JSON and their schemas) declare the following invariants:
1. JSON schema validity.
2. Retry policy profile schema validity (`grpc-retry-policy-profiles.v1.schema.json`).
3. Descriptor completeness and uniqueness.
4. Retry-ownership conflict checks.
5. Safety rule (`grpc_service_config` only on safe idempotency classes).
6. Matrix policy IDs map to known retry policy profiles.
7. Value-level profile constraints (backoff bounds, retryability floor, required transient status coverage).
8. Evidence-reference existence checks.

Runtime wiring parity is validated by executable module tests:
1. `GrpcAiTranslatorServiceTest`

> **Head-to-Worker rows are historical as of lane F stage A (2026-09).** Items A9 and A10 deleted
> that channel — server, client, retry service config and circuit breaker — so the two tests that
> pinned its runtime wiring (`RemoteKnowledgeClientRetryConfigTest`, `GrpcRetryServiceConfigTest`)
> went with it. The rows below are kept as the record of what the wire promised; the surviving
> enforced surface is the Brain's translator channel. Nothing re-validates the Head-to-Worker
> rows, and they must not be read as describing live behaviour.

## Change Rules

1. Adding an RPC method requires matrix update in the same change.
2. Retry ownership changes require matrix update and parity validation.
3. Promoting `NON_IDEMPOTENT` to retryable class requires explicit decision record and evidence update.
4. Breaking policy field changes require schema version bump.

## Known Residuals (v1)

1. Some methods classified `EFFECTIVE_RETRY_SAFE` remain intentionally non-retied today; this is explicit policy, not drift.
2. `CONDITIONAL` class remains reserved until idempotency-key contracts are introduced.
