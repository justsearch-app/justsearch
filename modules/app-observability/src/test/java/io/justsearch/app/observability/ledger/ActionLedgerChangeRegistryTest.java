package io.justsearch.app.observability.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.observability.navigation.NavigationHistoryEntry;
import io.justsearch.app.observability.operations.AuthorizationDisposition;
import io.justsearch.app.observability.operations.AuthorizationOutcomeEntry;
import io.justsearch.app.observability.operations.OperationHistoryEntry;
import io.justsearch.app.observability.operations.OperationHistoryStore;
import io.justsearch.app.observability.operations.OperationOutcome;
import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 550 G3/G4/G5: the unified live ledger relays each federated source as one UPDATE row
 * in the shared projection shape — so the FE renders snapshot rows and stream rows identically.
 */
@DisplayName("ActionLedgerChangeRegistry")
final class ActionLedgerChangeRegistryTest {

  @SuppressWarnings("unchecked")
  private static Map<String, Object> rowOf(SseEnvelope env) {
    return (Map<String, Object>) env.payload();
  }

  @Test
  @DisplayName("broadcastOperation publishes a kind=operation row with executionId + originator")
  void operationRow() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    registry.subscribe(seen::add);

    OperationHistoryEntry op =
        new OperationHistoryEntry(
            new OperationRef("core.reindex"),
            "head",
            Instant.parse("2026-05-26T00:00:00Z"),
            Instant.parse("2026-05-26T00:00:01Z"),
            OperationOutcome.SUCCESS,
            Optional.empty(),
            InvocationProvenance.systemInternal(Instant.parse("2026-05-26T00:00:00Z")),
            Optional.of("exec-7"));
    registry.broadcastOperation(op);

    assertEquals(1, seen.size());
    SseEnvelope env = seen.get(0);
    assertSame(SseFrameKind.UPDATE, env.frameKind());
    assertSame(ActionLedgerChangeRegistry.STREAM_ID, env.streamId());
    Map<String, Object> row = rowOf(env);
    assertEquals("operation", row.get("kind"));
    assertEquals("core.reindex", row.get("operationId"));
    assertEquals("exec-7", row.get("executionId"));
    assertEquals("system", row.get("originator"));
    // occurredAt is rendered as a wire string, not an Instant.
    assertEquals("2026-05-26T00:00:01Z", row.get("occurredAt"));
    // Tempdoc 550 thesis I: explicit, deterministic id (kind:occurredAt:<all discriminators>),
    // stable across snapshot re-projection and stream broadcast so the FE can dedup + use it as a
    // render key. All projected discriminators are folded in (outcome + executionId here) so two
    // distinct firings at the same Instant don't collide.
    assertEquals("operation:2026-05-26T00:00:01Z:core.reindex:SUCCESS:exec-7", row.get("id"));
  }

  @Test
  @DisplayName("broadcastGate publishes a kind=gate row carrying the disposition + gate behavior")
  void gateRow() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    registry.subscribe(seen::add);

    AuthorizationOutcomeEntry gate =
        new AuthorizationOutcomeEntry(
            "core.bulk-reindex",
            TransportTag.LLM_EMISSION,
            SourceTier.UNTRUSTED,
            RiskTier.HIGH,
            GateBehavior.TYPED_CONFIRM,
            AuthorizationDisposition.GATED,
            Instant.parse("2026-05-26T00:00:02Z"));
    registry.broadcastGate(gate);

    Map<String, Object> row = rowOf(seen.get(0));
    assertEquals("gate", row.get("kind"));
    assertEquals("GATED", row.get("disposition"));
    assertEquals("TYPED_CONFIRM", row.get("gateBehavior"));
    assertEquals("agent", row.get("originator"));
  }

  @Test
  @DisplayName(
      "deterministic id folds in all discriminators — two distinct firings at the same Instant don't collide")
  void distinctFiringsAtSameInstantGetDistinctIds() {
    Instant t = Instant.parse("2026-05-26T00:00:02Z");
    // Two gate decisions for the SAME op at the SAME Instant, differing only in disposition: a
    // GATED firing then the DENIED outcome. Under the old kind:occurredAt:operationId id these
    // collided and the second was silently dropped from the id-keyed unified store.
    ActionEvent gatedEvt =
        ActionLedgerProjection.projectGate(
            new AuthorizationOutcomeEntry(
                "core.bulk-reindex",
                TransportTag.LLM_EMISSION,
                SourceTier.UNTRUSTED,
                RiskTier.HIGH,
                GateBehavior.TYPED_CONFIRM,
                AuthorizationDisposition.GATED,
                t));
    ActionEvent deniedEvt =
        ActionLedgerProjection.projectGate(
            new AuthorizationOutcomeEntry(
                "core.bulk-reindex",
                TransportTag.LLM_EMISSION,
                SourceTier.UNTRUSTED,
                RiskTier.HIGH,
                GateBehavior.TYPED_CONFIRM,
                AuthorizationDisposition.DENIED,
                t));
    assertNotEquals(gatedEvt.id(), deniedEvt.id());

    // Two operations identical except for execution id at the same Instant also stay distinct.
    OperationHistoryEntry opBase =
        new OperationHistoryEntry(
            new OperationRef("core.reindex"),
            "head",
            t,
            t,
            OperationOutcome.SUCCESS,
            Optional.empty(),
            InvocationProvenance.systemInternal(t),
            Optional.of("exec-1"));
    OperationHistoryEntry opOther =
        new OperationHistoryEntry(
            new OperationRef("core.reindex"),
            "head",
            t,
            t,
            OperationOutcome.SUCCESS,
            Optional.empty(),
            InvocationProvenance.systemInternal(t),
            Optional.of("exec-2"));
    assertNotEquals(
        ActionLedgerProjection.projectOperation(opBase).id(),
        ActionLedgerProjection.projectOperation(opOther).id());

    // Identical content at the same Instant still shares an id (deterministic-from-content
    // contract preserved — the FE dedups a row that appears in both snapshot and stream).
    assertEquals(
        ActionLedgerProjection.projectOperation(opBase).id(),
        ActionLedgerProjection.projectOperation(opBase).id());
  }

  @Test
  @DisplayName("projectIndex publishes a kind=index row with originator=system + terminal state")
  void indexRow() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    registry.subscribe(seen::add);

    ActionEvent done =
        ActionLedgerProjection.projectIndex(
            "abc123def456", "default", "DONE", 0, "", Instant.parse("2026-05-28T00:00:00Z"), "");
    registry.broadcastActionEvent(done);

    Map<String, Object> row = rowOf(seen.get(0));
    assertEquals("index", row.get("kind"));
    assertEquals("system", row.get("originator"));
    assertEquals("WORKER_INDEXER", row.get("transport"));
    assertEquals("abc123def456", row.get("pathHash"));
    assertEquals("default", row.get("collection"));
    assertEquals("DONE", row.get("state"));
    assertEquals("DONE", row.get("outcome")); // outcome column mirrors the terminal state
    // Deterministic id folds collection + pathHash + state, so a re-delivered terminal transition
    // dedups (idempotent in the append-only store) while a later FAILED of the same job is distinct.
    assertEquals("index:2026-05-28T00:00:00Z:default:abc123def456:DONE", row.get("id"));

    ActionEvent failed =
        ActionLedgerProjection.projectIndex(
            "abc123def456", "default", "FAILED", 3, "boom", Instant.parse("2026-05-28T00:00:00Z"), "");
    assertNotEquals(done.id(), failed.id());
    // The failure detail is carried only when present.
    assertEquals("boom", ActionLedgerProjection.toWireRow(failed).get("errorMessage"));
  }

  @Test
  @DisplayName("broadcastNavigation publishes a kind=navigation row")
  void navigationRow() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    registry.subscribe(seen::add);

    NavigationHistoryEntry nav =
        new NavigationHistoryEntry(
            "ie-1",
            "core.library",
            "src-1",
            Instant.parse("2026-05-26T00:00:03Z"),
            InvocationProvenance.systemInternal(Instant.parse("2026-05-26T00:00:03Z")));
    registry.broadcastNavigation(nav);

    Map<String, Object> row = rowOf(seen.get(0));
    assertEquals("navigation", row.get("kind"));
    assertEquals("core.library", row.get("targetSurface"));
  }

  @Test
  @DisplayName("F5: a store append-listener wired to the registry fans the entry into the one log")
  void storeAppendFansIntoTheOneLog(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    try (var operations = new io.justsearch.app.observability.operations.SqliteOperationStore(directory.resolve("operations.db"))) {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    OperationHistoryStore store = new OperationHistoryStore(operations);
    // The production wiring: the store fans every append into the one log — no explicit broadcast.
    store.addAppendListener(registry::broadcastOperation);

    OperationHistoryEntry op =
        new OperationHistoryEntry(
            new OperationRef("core.reindex"),
            "head",
            Instant.parse("2026-05-26T00:00:00Z"),
            Instant.parse("2026-05-26T00:00:01Z"),
            OperationOutcome.SUCCESS,
            Optional.empty(),
            InvocationProvenance.systemInternal(Instant.parse("2026-05-26T00:00:00Z")),
            Optional.empty());
    store.append(op); // the ONLY call — the listener feeds the ledger, so they cannot diverge

    var log = registry.store().recent();
    assertEquals(1, log.size(), "the one log received the appended operation");
    assertEquals(
        "core.reindex",
        ((ActionEvent.Operation) log.get(0)).operationId());
    }
  }
}
