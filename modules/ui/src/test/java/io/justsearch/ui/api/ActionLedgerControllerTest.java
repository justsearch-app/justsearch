package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.http.Context;
import io.justsearch.app.observability.ledger.ActionEvent;
import io.justsearch.app.observability.ledger.ActionEventJournal;
import io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry;
import io.justsearch.app.observability.navigation.NavigationHistoryStore;
import io.justsearch.app.observability.operations.OperationHistoryStore;
import io.justsearch.core.execution.TestEngineExecutors;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 561 P-B1 — the {@code GET /api/action-ledger} session/originator filter that makes the
 * one ledger a governed, session-scoped projection (the agent History view's source). Without the
 * filter every consumer would re-derive its slice client-side; with it, History cannot diverge
 * from the live thread because both project the same record.
 */
@DisplayName("ActionLedgerController — P-B1 correlationId/originator projection filter")
final class ActionLedgerControllerTest {

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  @AfterEach
  void closeProcessExecutors() {
    processExecutors.close();
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static ActionEvent op(String id, String originator, String transport, String corr) {
    return new ActionEvent.Operation(
        id,
        Instant.parse("2026-05-30T00:00:00Z"),
        originator,
        transport,
        "core.search-index",
        "SUCCESS",
        Optional.empty(),
        corr == null ? Optional.empty() : Optional.of(corr));
  }

  private ActionLedgerController wiredController(ActionLedgerChangeRegistry changes) {
    return new ActionLedgerController(
            processExecutors,
              new OperationHistoryStore(), new NavigationHistoryStore(), null, changes, Clock.systemUTC());
  }

  /** Captures the byte[] the controller writes via {@code ctx.result(byte[])}. */
  private JsonNode invokeGet(
      ActionLedgerController controller, String correlationId, String originator) {
    return invokeGet(controller, correlationId, originator, List.of(), null);
  }

  private JsonNode invokeGet(
      ActionLedgerController controller,
      String correlationId,
      String originator,
      List<String> kinds,
      String limit) {
    Context ctx = mock(Context.class);
    when(ctx.queryParam("correlationId")).thenReturn(correlationId);
    when(ctx.queryParam("originator")).thenReturn(originator);
    when(ctx.queryParam("limit")).thenReturn(limit);
    when(ctx.queryParams("kind")).thenReturn(kinds);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    AtomicReference<byte[]> captured = new AtomicReference<>();
    doAnswer(
            inv -> {
              captured.set(inv.getArgument(0, byte[].class));
              return ctx;
            })
        .when(ctx)
        .result(any(byte[].class));
    controller.handleGet(ctx);
    try {
      return MAPPER.readTree(captured.get());
    } catch (Exception e) {
      throw new IllegalStateException("could not parse ledger response", e);
    }
  }

  @Test
  @DisplayName("correlationId + originator=agent yields only that session's agent rows")
  void filtersToSessionAndAgent() {
    ActionLedgerChangeRegistry changes = new ActionLedgerChangeRegistry();
    changes.broadcastActionEvent(op("op-a", "agent", "AGENT_LOOP", "sess-A"));
    changes.broadcastActionEvent(op("op-b", "agent", "AGENT_LOOP", "sess-B"));
    changes.broadcastActionEvent(op("op-u", "user", "BUTTON", "sess-A"));

    JsonNode body = invokeGet(wiredController(changes), "sess-A", "agent");
    JsonNode entries = body.get("entries");

    assertEquals(1, entries.size(), "only the sess-A agent row survives both filters");
    assertEquals("op-a", entries.get(0).get("id").asString());
    assertEquals("sess-A", entries.get(0).get("correlationId").asString());
  }

  @Test
  @DisplayName("no filter params returns the full ledger (back-compat)")
  void noFilterReturnsAll() {
    ActionLedgerChangeRegistry changes = new ActionLedgerChangeRegistry();
    changes.broadcastActionEvent(op("op-a", "agent", "AGENT_LOOP", "sess-A"));
    changes.broadcastActionEvent(op("op-b", "agent", "AGENT_LOOP", "sess-B"));

    JsonNode body = invokeGet(wiredController(changes), null, null);
    assertEquals(2, body.get("entries").size(), "unfiltered request keeps every row");
  }

  @Test
  @DisplayName("a correlationId with no matching rows yields an empty (not absent) entries array")
  void unmatchedCorrelationYieldsEmpty() {
    ActionLedgerChangeRegistry changes = new ActionLedgerChangeRegistry();
    changes.broadcastActionEvent(op("op-a", "agent", "AGENT_LOOP", "sess-A"));

    JsonNode body = invokeGet(wiredController(changes), "sess-NONE", "agent");
    JsonNode entries = body.get("entries");
    assertEquals(List.of(), MAPPER.convertValue(entries, List.class));
  }

  // ── Tempdoc 812 D1: durable journal + union read ────────────────────────────────────────────

  private static ActionEvent grant(String id, int second) {
    return new ActionEvent.Grant(
        id, atSecond(second), "user", "ALLOW_ALWAYS", "g-" + id, "GRANTED_ALWAYS", "core.x");
  }

  private static ActionEvent gate(String id, int second) {
    return new ActionEvent.Gate(
        id, atSecond(second), "agent", "AGENT_LOOP", "core.x", "GATED", "REQUIRE_APPROVAL", "UNTRUSTED");
  }

  private static ActionEvent index(String id, int second) {
    return new ActionEvent.Index(
        id, atSecond(second), "system", "WORKER_INDEXER", "h-" + id, "default", "DONE", 0, "", "");
  }

  private static ActionEvent opAt(String id, int second) {
    return new ActionEvent.Operation(
        id, atSecond(second), "agent", "AGENT_LOOP", "core.search-index", "SUCCESS",
        Optional.empty(), Optional.empty());
  }

  private static Instant atSecond(int second) {
    return Instant.parse("2026-08-06T00:00:00Z").plusSeconds(second);
  }

  private static List<String> ids(JsonNode body) {
    List<String> out = new java.util.ArrayList<>();
    for (JsonNode e : body.get("entries")) {
      out.add(e.get("id").asString());
    }
    return out;
  }

  @Test
  @DisplayName("D1: pre-restart grant/gate/operation rows are still served after a restart (ring empty)")
  void journaledActorRowsSurviveARestart(@TempDir Path dir) {
    Path audit = dir.resolve("audit");

    // Session 1: three actor kinds + an index row all flow through the one fan-in point.
    ActionLedgerChangeRegistry before = new ActionLedgerChangeRegistry(ActionEventJournal.at(audit));
    before.broadcastActionEvent(grant("g1", 1));
    before.broadcastActionEvent(gate("t1", 2));
    before.broadcastActionEvent(opAt("o1", 3));
    before.broadcastActionEvent(index("i1", 4));

    // Session 2 = a restarted Head: brand-new registry + ring + journal over the same data dir.
    ActionLedgerChangeRegistry after = new ActionLedgerChangeRegistry(ActionEventJournal.at(audit));
    assertEquals(List.of(), after.store().recent(), "the ring genuinely starts empty after restart");

    JsonNode body = invokeGet(wiredController(after), null, null);
    assertEquals(
        List.of("g1", "t1", "o1"),
        ids(body),
        "every pre-restart actor row is retrievable via the controller; the index row is not");
  }

  @Test
  @DisplayName("D1 control arm: with no journal (the pre-812 shape) a restart loses the entire trail")
  void withoutTheJournalARestartLosesEverything() {
    ActionLedgerChangeRegistry before = new ActionLedgerChangeRegistry(ActionEventJournal.disabled());
    before.broadcastActionEvent(grant("g1", 1));
    before.broadcastActionEvent(gate("t1", 2));
    before.broadcastActionEvent(opAt("o1", 3));

    ActionLedgerChangeRegistry after = new ActionLedgerChangeRegistry(ActionEventJournal.disabled());
    assertEquals(
        List.of(),
        ids(invokeGet(wiredController(after), null, null)),
        "this is exactly what the endpoint returned after every restart before tempdoc 812 D1 —"
            + " the assertion above passes because of the journal, not because of the ring");
  }

  @Test
  @DisplayName("D1: a row present in BOTH ring and journal is returned exactly once")
  void unionDoesNotDoubleCount(@TempDir Path dir) {
    ActionLedgerChangeRegistry changes =
        new ActionLedgerChangeRegistry(ActionEventJournal.at(dir.resolve("audit")));
    changes.broadcastActionEvent(grant("g1", 1));

    // The same event is now in the ring AND in the journal's tail — the union must dedup by id.
    assertEquals(1, changes.store().recent().size());
    assertEquals(1, changes.journal().tail(10).size());
    assertEquals(List.of("g1"), ids(invokeGet(wiredController(changes), null, null)));
  }

  @Test
  @DisplayName("D1: re-broadcasting an id the ring already holds does not append a second journal line")
  void journalInheritsRingIdempotency(@TempDir Path dir) {
    ActionLedgerChangeRegistry changes =
        new ActionLedgerChangeRegistry(ActionEventJournal.at(dir.resolve("audit")));
    changes.broadcastActionEvent(grant("g1", 1));
    changes.broadcastActionEvent(grant("g1", 9));

    assertEquals(1, changes.journal().tail(10).size(), "the journal is id-idempotent like the ring");
  }

  // ── Tempdoc 812 D3: kind + limit ────────────────────────────────────────────────────────────

  private ActionLedgerChangeRegistry mixedLedger() {
    ActionLedgerChangeRegistry changes = new ActionLedgerChangeRegistry();
    changes.broadcastActionEvent(grant("g1", 1));
    changes.broadcastActionEvent(gate("t1", 2));
    changes.broadcastActionEvent(opAt("o1", 3));
    changes.broadcastActionEvent(index("i1", 4));
    return changes;
  }

  @Test
  @DisplayName("D3: ?kind is repeatable and keeps only the named kinds")
  void kindFilterKeepsOnlyNamedKinds() {
    JsonNode body =
        invokeGet(wiredController(mixedLedger()), null, null, List.of("grant", "gate"), null);
    assertEquals(List.of("g1", "t1"), ids(body));
  }

  @Test
  @DisplayName("D3: an absent/blank ?kind leaves every kind in place (additive, back-compatible)")
  void absentKindFilterIsInert() {
    assertEquals(
        List.of("g1", "t1", "o1", "i1"),
        ids(invokeGet(wiredController(mixedLedger()), null, null, List.of(), null)));
    assertEquals(
        List.of("g1", "t1", "o1", "i1"),
        ids(invokeGet(wiredController(mixedLedger()), null, null, List.of("  "), null)));
  }

  @Test
  @DisplayName("D3: ?limit below the result size returns the NEWEST entries")
  void limitReturnsNewestEntries() {
    JsonNode body = invokeGet(wiredController(mixedLedger()), null, null, List.of(), "2");
    assertEquals(List.of("o1", "i1"), ids(body), "the two newest rows, still oldest-first");
  }

  @Test
  @DisplayName("D3: limit is capped, and a bad/absent limit falls back to the default")
  void limitIsCappedAndDefaulted() {
    ActionLedgerChangeRegistry changes = new ActionLedgerChangeRegistry();
    for (int i = 0; i < 12; i++) {
      changes.broadcastActionEvent(grant("g" + i, i));
    }
    ActionLedgerController controller = wiredController(changes);

    assertEquals(12, ids(invokeGet(controller, null, null, List.of(), null)).size(), "default keeps all 12");
    assertEquals(12, ids(invokeGet(controller, null, null, List.of(), "not-a-number")).size());
    assertEquals(12, ids(invokeGet(controller, null, null, List.of(), "-5")).size());
    assertEquals(
        12,
        ids(invokeGet(controller, null, null, List.of(), String.valueOf(Integer.MAX_VALUE))).size(),
        "an over-cap limit is clamped, not rejected");
    assertEquals(1, ids(invokeGet(controller, null, null, List.of(), "1")).size());
  }

  @Test
  @DisplayName("D3: kind and limit compose with the existing correlationId/originator filters")
  void filtersCompose() {
    ActionLedgerChangeRegistry changes = new ActionLedgerChangeRegistry();
    changes.broadcastActionEvent(op("op-a", "agent", "AGENT_LOOP", "sess-A"));
    changes.broadcastActionEvent(grant("g1", 5));

    JsonNode body =
        invokeGet(wiredController(changes), "sess-A", "agent", List.of("operation"), "10");
    assertEquals(List.of("op-a"), ids(body));
  }

  @Test
  @DisplayName("the contract map's kind list matches the kinds this endpoint can emit")
  void contractMapKindListIsComplete() throws Exception {
    Path map = repoRoot().resolve("docs/reference/api-contract-map.md");
    List<String> lines = java.nio.file.Files.readAllLines(map);
    int start = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).startsWith("- `GET /api/action-ledger` —")) {
        start = i;
        break;
      }
    }
    assertTrue(start >= 0, "no GET /api/action-ledger entry in " + map);
    // The entry is the bullet plus its indented continuation bullets, up to the next top-level one.
    StringBuilder entry = new StringBuilder(lines.get(start));
    for (int i = start + 1; i < lines.size() && !lines.get(i).startsWith("- "); i++) {
      entry.append('\n').append(lines.get(i));
    }
    String text = entry.toString();

    for (ActionEvent.ActionEventKind kind : ActionEvent.ActionEventKind.values()) {
      String rendered = "`" + kind.name().toLowerCase(java.util.Locale.ROOT) + "`";
      assertTrue(
          text.contains(rendered),
          "api-contract-map.md documents the " + rendered + " kind:\n" + text);
    }
    assertTrue(text.contains("?kind="), "the repeatable kind filter is documented:\n" + text);
    assertTrue(text.contains("?limit="), "the limit param is documented:\n" + text);
    assertTrue(
        text.contains(String.valueOf(ActionLedgerController.DEFAULT_LIMIT))
            && text.contains(String.valueOf(ActionLedgerController.MAX_LIMIT)),
        "the documented default/cap match the code's constants:\n" + text);
    assertTrue(
        text.contains("audit/action-ledger.jsonl"),
        "the journal's location + retention semantics are documented:\n" + text);
  }

  private static Path repoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !java.nio.file.Files.exists(dir.resolve("docs/reference/api-contract-map.md"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException("could not locate the repo root from " + Path.of("").toAbsolutePath());
    }
    return dir;
  }
}
