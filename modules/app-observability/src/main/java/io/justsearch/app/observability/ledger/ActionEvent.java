/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import java.time.Instant;
import java.util.Optional;

/**
 * Tempdoc 550 thesis I — the ONE action-event type.
 *
 * <p>A single typed, discriminated record for every entry in the unified action-event log. The
 * three backend-authoritative sources — operation invocations, navigations, trust-gate firings —
 * each project to an {@code ActionEvent} variant; the FE-local Effect Journal projects to the SAME
 * schema on the client (the federated model: two emitters, one schema, reconciled by correlation
 * id). The receipt, activity timeline, 538 trust-audit, and undo history are read-views / filters
 * over this one type — never re-joins of three separate stores.
 *
 * <p>Replaces the prior stringly-typed {@code Map<String,Object>} projection rows. Each event
 * carries an explicit, deterministic {@link #id()} (derived from kind + occurredAt + subject) that
 * is stable across snapshot re-projection and live-stream broadcast — so a row appearing in both
 * the snapshot frame and a later UPDATE frame dedups to one. The per-kind {@code outcome} is the
 * union value (dispatch outcome for operations, gate disposition for gates; absent for navigation).
 */
public sealed interface ActionEvent
    permits
      ActionEvent.Operation,
      ActionEvent.Navigation,
      ActionEvent.Gate,
      ActionEvent.Grant,
      ActionEvent.Effect,
      ActionEvent.Index,
      ActionEvent.ScanRollup {

  /** Stable, deterministic identity (kind + occurredAt + subject); the dedup / correlation key. */
  String id();

  ActionEventKind kind();

  Instant occurredAt();

  /** Coarse attribution: {@code user | agent | system}. */
  String originator();

  /** The source transport name (e.g. {@code LLM_EMISSION}, {@code BUTTON}). */
  String transport();

  /**
   * Tempdoc 561 P-A1 — the cross-domain loop/session join key (distinct from {@link #id()}, which
   * is the dedup key). When present, every row sharing a value belongs to one originating context
   * — the agent loop stamps its {@code sessionId} here (via {@code InvocationProvenance.correlationId}),
   * so the agent History view is a total projection of this one ledger filtered to a single session
   * (561 P-B1), and a cross-domain "everything that happened in this session" audit is one filter,
   * not a re-join of stores. Default {@link Optional#empty()} for kinds whose source carries no
   * correlation; variants override by declaring the component (today: {@link Operation}).
   */
  default Optional<String> correlationId() {
    return Optional.empty();
  }

  /** The discriminator for the one action-event log. */
  enum ActionEventKind {
    OPERATION,
    NAVIGATION,
    GATE,
    GRANT,
    EFFECT,
    INDEX
  }

  /** A completed operation invocation. {@code outcome} ∈ SUCCESS / FAILURE / UNDONE. */
  record Operation(
      String id,
      Instant occurredAt,
      String originator,
      String transport,
      String operationId,
      String outcome,
      Optional<String> executionId,
      Optional<String> correlationId)
      implements ActionEvent {
    @Override
    public ActionEventKind kind() {
      return ActionEventKind.OPERATION;
    }
  }

  /** A forwarded navigation. */
  record Navigation(
      String id,
      Instant occurredAt,
      String originator,
      String transport,
      String targetSurface,
      String sourceId)
      implements ActionEvent {
    @Override
    public ActionEventKind kind() {
      return ActionEventKind.NAVIGATION;
    }
  }

  /**
   * A grant lifecycle event (tempdoc 550 thesis IV): the consent capsule (and future durable
   * grants) recorded in the one log, giving one audit + one revocation trail. {@code action} ∈
   * ISSUED / CONSUMED / REVOKED; {@code subject} summarizes the grant's scope.
   */
  record Grant(
      String id,
      Instant occurredAt,
      String originator,
      String transport,
      String grantId,
      String action,
      String subject)
      implements ActionEvent {
    @Override
    public ActionEventKind kind() {
      return ActionEventKind.GRANT;
    }
  }

  /**
   * A FE-local effect (navigate / open-pane / toast / invoke-operation …) ingested into the one
   * authoritative log (tempdoc 550 thesis I, process-spanning): the FE Effect Journal posts each
   * effect here, so the receipt / timeline is ONE log — not a read-time client merge of the backend
   * ledger and the FE journal (the eliminated {@code unifiedActivity} join). {@code effectKind} is
   * the FE Effect kind; {@code subject} a short detail (e.g. the navigate target).
   */
  record Effect(
      String id,
      Instant occurredAt,
      String originator,
      String transport,
      String effectKind,
      String subject)
      implements ActionEvent {
    @Override
    public ActionEventKind kind() {
      return ActionEventKind.EFFECT;
    }
  }

  /**
   * A system/background indexing operation's TERMINAL outcome (tempdoc 550 thesis I — the
   * system-operation contributor to the one log). {@code originator} is always {@code system} and
   * {@code transport} is {@code WORKER_INDEXER}. Unlike the agent/user kinds, an indexing job has
   * only an Outcome face: it is emitted when the worker job reaches a terminal {@code state} ∈
   * {@code DONE | FAILED} (never the in-flight PENDING/PROCESSING — that live state is the rail's
   * Resource projection, the second governed projection of the same job lifecycle). The
   * {@code pathHash} is the cross-process correlation id (raw paths never cross the wire);
   * {@code collection} groups jobs; {@code attempts}/{@code errorMessage} carry the failure detail.
   */
  record Index(
      String id,
      Instant occurredAt,
      String originator,
      String transport,
      String pathHash,
      String collection,
      String state,
      int attempts,
      String errorMessage,
      String scanId)
      implements ActionEvent {
    @Override
    public ActionEventKind kind() {
      return ActionEventKind.INDEX;
    }
  }

  /**
   * Historical directory-scan rollup retained for persisted journal decoding and rendering.
   * Lane F retired its live producer; current ingestion progress belongs to the keyed operation row.
   *
   * <p>Per-document {@link Index} rows are operational telemetry (ring-only ephemera, evicted
   * first under pressure); the legacy scan was the audit fact, recorded once as an
   * {@code OPERATION}-kind event — the durable tier. {@link #kind()} deliberately returns
   * {@code OPERATION} rather than minting a seventh kind: every kind-keyed consumer (the durable
   * journal, the {@code kind} API filter, the FE's tier split, the store's index-first eviction)
   * must treat a scan rollup as the consequential record it is, and a new kind would have to be
   * added to each of them by hand. The typed variant exists so the summary's fields are a record,
   * not a stringly-typed detail blob.
   *
   * <p>The legacy producer counted {@code docsDone}/{@code docsFailed} from terminal job states
   * ({@code DONE}/{@code FAILED} rows observed on the indexing-jobs bridge), never from the
   * enqueue-time admitted count — an audit row must state what happened, not what was attempted.
   * {@code outcome} ∈ {@code STARTED} (enumeration began) / {@code COMPLETED} (every admitted
   * document reached a terminal state) / {@code PARTIAL} (the scan went quiet before all of them
   * did — cancellation, worker restart, or a re-enqueue that stole a row).
   */
  record ScanRollup(
      String id,
      Instant occurredAt,
      String originator,
      String transport,
      String scanId,
      String collection,
      String root,
      String outcome,
      int docsDone,
      int docsFailed,
      int docsAdmitted,
      long durationMs)
      implements ActionEvent {
    @Override
    public ActionEventKind kind() {
      return ActionEventKind.OPERATION;
    }

    /** The catalog-shaped operation id this rollup reports under (the FE's discriminator). */
    public static final String OPERATION_ID = "core.scan-root";
  }

  /** A trust-gate firing. {@code disposition} ∈ GATED / DENIED / APPROVED (the outcome union). */
  record Gate(
      String id,
      Instant occurredAt,
      String originator,
      String transport,
      String operationId,
      String disposition,
      String gateBehavior,
      String sourceTier)
      implements ActionEvent {
    @Override
    public ActionEventKind kind() {
      return ActionEventKind.GATE;
    }
  }
}
