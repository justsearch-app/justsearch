/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.observability.navigation.NavigationHistoryEntry;
import io.justsearch.app.observability.operations.AuthorizationOutcomeEntry;
import io.justsearch.app.observability.operations.OperationHistoryEntry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Shared row projection for the unified action ledger (tempdoc 550 Outcome face).
 *
 * <p>Both the snapshot endpoint ({@code GET /api/action-ledger}) and the live change-stream
 * ({@code GET /api/action-ledger/stream}, via {@link ActionLedgerChangeRegistry}) MUST emit the
 * same row shape so the FE renders snapshot rows and streamed UPDATE rows through one projection.
 * Centralizing the per-kind projection here is what guarantees that — the controller can no
 * longer drift from the stream emitter.
 *
 * <p>Each {@code project*} method returns a row whose {@code occurredAt} is an {@link Instant}
 * (so the snapshot endpoint can sort chronologically before serializing); {@link #toWireRow}
 * copies a row with {@code occurredAt} rendered as an ISO-8601 string for the wire.
 *
 * <p>Attribution: every row carries a coarse {@code originator} ∈ {@code user | agent | system}
 * derived from the source transport — the axis the trust-audit and "what did the agent do this
 * session" views read.
 */
public final class ActionLedgerProjection {

  private ActionLedgerProjection() {}

  /** A completed operation invocation (tempdoc 550 thesis I — typed {@link ActionEvent}). */
  public static ActionEvent projectOperation(OperationHistoryEntry op) {
    return new ActionEvent.Operation(
        deterministicId(
            "operation",
            op.endTime(),
            op.operationId().value(),
            op.outcome().name(),
            op.executionId().orElse("")),
        op.endTime(),
        originatorOf(op.provenance()),
        op.provenance().transport().name(),
        op.operationId().value(),
        op.outcome().name(),
        // Tempdoc 550 G6: the backend execution id correlates this with the FE Effect Journal
        // entry that dispatched it (one logical record across the boundary).
        op.executionId(),
        // Tempdoc 561 P-A1: the loop/session join key (the agent loop's sessionId), so the agent
        // History view projects from this one ledger filtered to a single session (P-B1).
        op.provenance().correlationId());
  }

  /**
   * Tempdoc 561 P-A/P-B — project an agent tool completion (derived from the durable
   * {@code AgentRunStore} record) into the SAME {@link ActionEvent.Operation} row the operation path
   * produces, so the ledger's agent rows are a projection of the ONE agent record (the unified
   * thread's source) and cannot disagree with it. Built HERE (the one projection authority), exactly
   * like {@link #projectOperation}; {@code outcome} uses the same {@code SUCCESS}/{@code FAILURE}
   * vocabulary as {@code OperationOutcome.name()} so the deterministic id + wire row match the
   * operation-path row for the same logical execution.
   */
  public static ActionEvent projectAgentToolCompletion(
      Instant occurredAt, String toolName, boolean success, String executionId, String sessionId) {
    String outcome = success ? "SUCCESS" : "FAILURE";
    String exec = executionId == null ? "" : executionId;
    return new ActionEvent.Operation(
        deterministicId("operation", occurredAt, toolName, outcome, exec),
        occurredAt,
        originatorOf(TransportTag.AGENT_LOOP),
        TransportTag.AGENT_LOOP.name(),
        toolName,
        outcome,
        exec.isBlank() ? Optional.empty() : Optional.of(exec),
        Optional.of(sessionId));
  }

  /** A forwarded navigation. */
  public static ActionEvent projectNavigation(NavigationHistoryEntry nav) {
    return new ActionEvent.Navigation(
        deterministicId("navigation", nav.occurredAt(), nav.targetSurface(), nav.sourceId()),
        nav.occurredAt(),
        originatorOf(nav.provenance()),
        nav.provenance().transport().name(),
        nav.targetSurface(),
        nav.sourceId());
  }

  /**
   * A trust-gate decision — the gate firing the 538 audit reads. {@code disposition} ∈
   * GATED/DENIED/APPROVED is the outcome union for this kind.
   */
  public static ActionEvent projectGate(AuthorizationOutcomeEntry gate) {
    return new ActionEvent.Gate(
        deterministicId(
            "gate",
            gate.occurredAt(),
            gate.operationId(),
            gate.disposition().name(),
            gate.gateBehavior().name(),
            gate.sourceTier().name()),
        gate.occurredAt(),
        originatorOf(gate.transport()),
        gate.transport().name(),
        gate.operationId(),
        gate.disposition().name(),
        gate.gateBehavior().name(),
        gate.sourceTier().name());
  }

  /**
   * A system/background indexing operation's terminal outcome (tempdoc 550 thesis I — the
   * system-operation contributor). Takes primitive args (not the {@code app-api IndexingJobView})
   * so this projection stays in {@code app-observability} without an {@code app-api} dependency;
   * the Head-side translator (in {@code app-services}, which depends on both) passes the view's
   * fields. {@code occurredAt} is the job's {@code lastUpdatedMs} as an {@link Instant}, so a
   * re-delivered terminal transition produces the same deterministic id (idempotent in the store).
   */
  public static ActionEvent projectIndex(
      String pathHash,
      String collection,
      String state,
      int attempts,
      String errorMessage,
      Instant occurredAt,
      String scanId) {
    return new ActionEvent.Index(
        deterministicId("index", occurredAt, collection, pathHash, state),
        occurredAt,
        "system",
        "WORKER_INDEXER",
        pathHash,
        collection,
        state,
        attempts,
        errorMessage == null ? "" : errorMessage,
        // Tempdoc 812 D2 — the scan this document belonged to, so the Activity view groups the
        // surviving per-doc rows under their scan's rollup by KEY rather than by render adjacency.
        scanId == null ? "" : scanId);
  }

  /**
   * Tempdoc 812 D2 — a directory scan's rollup: the one durable audit record for "this scan
   * indexed N documents". Counts come from the observed terminal job states, so the row states
   * what the scan DID. The deterministic id is keyed on the scan + phase (not the counts), so a
   * re-emitted completion for the same scan dedups in the id-keyed store.
   */
  public static ActionEvent projectScanRollup(
      String scanId,
      String collection,
      String root,
      String outcome,
      int docsDone,
      int docsFailed,
      int docsAdmitted,
      long durationMs,
      Instant occurredAt) {
    String phase = "STARTED".equals(outcome) ? "STARTED" : "FINISHED";
    return new ActionEvent.ScanRollup(
        deterministicId("scan", Instant.EPOCH, scanId, phase),
        occurredAt,
        "system",
        "WORKER_INDEXER",
        scanId == null ? "" : scanId,
        collection == null ? "" : collection,
        root == null ? "" : root,
        outcome,
        docsDone,
        docsFailed,
        docsAdmitted,
        durationMs);
  }

  /**
   * Render a typed {@link ActionEvent} to its flat wire row ({@code occurredAt} as ISO-8601). The
   * field names are stable across the snapshot endpoint and the live stream because both serialize
   * through this one method. The {@code outcome} column carries the per-kind union value (operation
   * outcome, or the gate disposition) so a single column covers every kind.
   */
  public static Map<String, Object> toWireRow(ActionEvent e) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", e.id());
    m.put("kind", e.kind().name().toLowerCase(Locale.ROOT));
    m.put("occurredAt", e.occurredAt().toString());
    m.put("originator", e.originator());
    m.put("transport", e.transport());
    // Tempdoc 561 P-A1: the cross-domain session/loop join key, emitted once in the common section
    // (any kind that carries it). The agent History view filters ledger rows on this (P-B1).
    e.correlationId().ifPresent(cid -> m.put("correlationId", cid));
    switch (e) {
      case ActionEvent.Operation op -> {
        m.put("operationId", op.operationId());
        m.put("outcome", op.outcome());
        op.executionId().ifPresent(id -> m.put("executionId", id));
      }
      case ActionEvent.Navigation nav -> {
        m.put("targetSurface", nav.targetSurface());
        m.put("sourceId", nav.sourceId());
      }
      case ActionEvent.Gate gate -> {
        m.put("operationId", gate.operationId());
        m.put("disposition", gate.disposition());
        m.put("outcome", gate.disposition()); // outcome column mirrors disposition for gates
        m.put("gateBehavior", gate.gateBehavior());
        m.put("sourceTier", gate.sourceTier());
      }
      case ActionEvent.Grant grant -> {
        m.put("grantId", grant.grantId());
        m.put("action", grant.action()); // ISSUED | CONSUMED | REVOKED
        m.put("outcome", grant.action()); // outcome column mirrors the grant action
        m.put("subject", grant.subject());
      }
      case ActionEvent.Effect effect -> {
        m.put("effectKind", effect.effectKind());
        m.put("subject", effect.subject());
      }
      case ActionEvent.Index idx -> {
        m.put("pathHash", idx.pathHash());
        m.put("collection", idx.collection());
        m.put("state", idx.state());
        m.put("outcome", idx.state()); // outcome column mirrors the terminal job state
        m.put("attempts", idx.attempts());
        if (!idx.errorMessage().isEmpty()) {
          m.put("errorMessage", idx.errorMessage());
        }
        // Tempdoc 812 D2 — omitted when absent so a keyless legacy row is legibly keyless and the
        // FE falls back to the adjacency collapse rather than grouping everything under "".
        if (!idx.scanId().isEmpty()) {
          m.put("scanId", idx.scanId());
        }
      }
      case ActionEvent.ScanRollup scan -> {
        // Tempdoc 812 D2 — rendered as an OPERATION row (kind() is OPERATION); operationId is what
        // the FE discriminates the scan rollup on, the rest is the summary the row states.
        m.put("operationId", ActionEvent.ScanRollup.OPERATION_ID);
        m.put("outcome", scan.outcome());
        m.put("scanId", scan.scanId());
        m.put("collection", scan.collection());
        if (!scan.root().isEmpty()) {
          m.put("root", scan.root());
        }
        m.put("docsDone", scan.docsDone());
        m.put("docsFailed", scan.docsFailed());
        m.put("docsAdmitted", scan.docsAdmitted());
        m.put("durationMs", scan.durationMs());
      }
    }
    return m;
  }

  /**
   * The inverse of {@link #toWireRow} — tempdoc 812 D1. The durable audit journal stores one wire
   * row per line, so reading the journal back is exactly this parse; keeping the inverse HERE,
   * beside the forward projection, is what stops the journal from becoming a second schema that
   * drifts from the endpoint's. Returns empty for a row whose {@code kind} is unknown (a file
   * written by a newer build) or whose required fields are missing/malformed — the caller skips the
   * line rather than failing the whole read.
   */
  public static Optional<ActionEvent> fromWireRow(Map<String, Object> row) {
    if (row == null) {
      return Optional.empty();
    }
    try {
      String id = str(row, "id");
      String kind = str(row, "kind");
      String originator = str(row, "originator");
      String transport = str(row, "transport");
      if (id.isEmpty() || kind.isEmpty()) {
        return Optional.empty();
      }
      Instant occurredAt = Instant.parse(str(row, "occurredAt"));
      Optional<String> correlationId = optional(row, "correlationId");
      return Optional.ofNullable(
          switch (kind) {
            // Tempdoc 812 D1×D2 — a scan rollup is journaled as an OPERATION row (its kind() IS
            // operation, which is what makes it durable), so the read path must restore the ROLLUP,
            // not a bare Operation: the latter would silently drop the counts + scan key and render
            // a restored row as "Indexed 0 documents".
            case "operation" ->
                ActionEvent.ScanRollup.OPERATION_ID.equals(str(row, "operationId"))
                    ? new ActionEvent.ScanRollup(
                        id,
                        occurredAt,
                        originator,
                        transport,
                        str(row, "scanId"),
                        str(row, "collection"),
                        str(row, "root"),
                        str(row, "outcome"),
                        intOf(row, "docsDone"),
                        intOf(row, "docsFailed"),
                        intOf(row, "docsAdmitted"),
                        row.get("durationMs") instanceof Number n ? n.longValue() : 0L)
                    : new ActionEvent.Operation(
                        id,
                        occurredAt,
                        originator,
                        transport,
                        str(row, "operationId"),
                        str(row, "outcome"),
                        optional(row, "executionId"),
                        correlationId);
            case "navigation" ->
                new ActionEvent.Navigation(
                    id,
                    occurredAt,
                    originator,
                    transport,
                    str(row, "targetSurface"),
                    str(row, "sourceId"));
            case "gate" ->
                new ActionEvent.Gate(
                    id,
                    occurredAt,
                    originator,
                    transport,
                    str(row, "operationId"),
                    str(row, "disposition"),
                    str(row, "gateBehavior"),
                    str(row, "sourceTier"));
            case "grant" ->
                new ActionEvent.Grant(
                    id,
                    occurredAt,
                    originator,
                    transport,
                    str(row, "grantId"),
                    str(row, "action"),
                    str(row, "subject"));
            case "effect" ->
                new ActionEvent.Effect(
                    id,
                    occurredAt,
                    originator,
                    transport,
                    str(row, "effectKind"),
                    str(row, "subject"));
            case "index" ->
                new ActionEvent.Index(
                    id,
                    occurredAt,
                    originator,
                    transport,
                    str(row, "pathHash"),
                    str(row, "collection"),
                    str(row, "state"),
                    intOf(row, "attempts"),
                    str(row, "errorMessage"),
                    // Tempdoc 812 D2 — the scan key round-trips too (empty for keyless rows).
                    str(row, "scanId"));
            default -> null;
          });
    } catch (RuntimeException malformed) {
      return Optional.empty();
    }
  }

  private static String str(Map<String, Object> row, String key) {
    Object v = row.get(key);
    return v == null ? "" : v.toString();
  }

  /** A JSON number round-trips as Integer/Long/Double depending on the parser; 0 when absent. */
  private static int intOf(Map<String, Object> row, String key) {
    return row.get(key) instanceof Number n ? n.intValue() : 0;
  }

  private static Optional<String> optional(Map<String, Object> row, String key) {
    Object v = row.get(key);
    return v == null || v.toString().isEmpty() ? Optional.empty() : Optional.of(v.toString());
  }

  /**
   * Deterministic, stable id for an event: {@code kind:occurredAt:disc0:disc1:…}. Stable across
   * snapshot re-projection and stream broadcast (the stores hold no id), so the FE can dedup a row
   * that appears in both the snapshot and a later UPDATE, and use it as a stable render key.
   *
   * <p>All of the kind's projected discriminators are folded in (not just one subject) so two
   * distinct firings — e.g. a GATED then a DENIED gate decision for the same op, or two operations
   * with different execution ids — at an identical {@link Instant} get distinct ids rather than
   * colliding (the second silently dropped from the unified, id-keyed store). Only two events
   * identical in every projected field at the same instant share an id, which is the degenerate
   * indistinguishable case where collapsing to one unified-log row is acceptable.
   */
  private static String deterministicId(String kind, Instant occurredAt, String... discriminators) {
    StringBuilder sb = new StringBuilder(kind).append(':').append(occurredAt.toString());
    for (String d : discriminators) {
      sb.append(':').append(d == null ? "" : d);
    }
    return sb.toString();
  }

  /** Coarse originator attribution derived from the source provenance. */
  public static String originatorOf(InvocationProvenance provenance) {
    return originatorOf(provenance.transport());
  }

  /** Coarse originator attribution derived from the source transport. */
  public static String originatorOf(TransportTag transport) {
    return switch (transport) {
      case LLM_EMISSION, AGENT_LOOP, WORKFLOW, MCP -> "agent";
      case SYSTEM_INTERNAL, SCHEDULED, RULE_ENGINE -> "system";
      default -> "user";
    };
  }
}
