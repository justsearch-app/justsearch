package io.justsearch.app.services.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.app.observability.ledger.ActionEvent;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tempdoc 550 thesis IV — the durable "allow-always" grant (the second Grant-model member). */
@DisplayName("DurableGrantStore")
class DurableGrantStoreTest {

  @Test
  @DisplayName("grant → allowed for that (op, tier) only; revoke → no longer allowed")
  void grantScopeAndRevoke() {
    DurableGrantStore store = new DurableGrantStore();
    assertFalse(store.isAllowed("core.x", RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.agent()));

    store.grantAllowAlways("core.x", SourceTier.UNTRUSTED);
    assertTrue(store.isAllowed("core.x", RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.agent()));
    assertFalse(store.isAllowed("core.x", RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.ui()), "scoped to the granted tier");
    assertFalse(store.isAllowed("core.other", RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.agent()), "scoped to the granted op");

    store.revoke("core.x", SourceTier.UNTRUSTED);
    assertFalse(store.isAllowed("core.x", RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.agent()));
  }

  @Test
  @DisplayName("revokeNonUser revokes only UNTRUSTED durable grants (matches the gate hard-stop)")
  void revokeNonUserScopesToUntrusted() {
    DurableGrantStore store = new DurableGrantStore();
    store.grantAllowAlways("core.agent", SourceTier.UNTRUSTED);
    store.grantAllowAlways("core.user", SourceTier.TRUSTED);

    store.revokeNonUser();

    assertFalse(store.isAllowed("core.agent", RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.agent()), "non-user durable grant revoked");
    assertTrue(store.isAllowed("core.user", RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.ui()), "user durable grant survives");
  }

  @Test
  @DisplayName("grant + revoke are recorded in the one action-event log (one audit)")
  void emitsLifecycleEvents() {
    DurableGrantStore store = new DurableGrantStore();
    List<ActionEvent> events = new ArrayList<>();
    store.setGrantEventSink(events::add);

    store.grantAllowAlways("core.x", SourceTier.UNTRUSTED);
    store.revoke("core.x", SourceTier.UNTRUSTED);

    List<String> actions =
        events.stream()
            .filter(e -> e instanceof ActionEvent.Grant)
            .map(e -> ((ActionEvent.Grant) e).action())
            .toList();
    assertTrue(actions.contains("GRANTED_ALWAYS"), "grant recorded");
    assertTrue(actions.contains("REVOKED"), "revoke recorded");
  }

  @Test
  @DisplayName("560 §28 (4d): a family grant auto-approves any op declaring that family; revoke clears it")
  void familyGrantCoversAnyOpInFamily() {
    DurableGrantStore store = new DurableGrantStore();
    Optional<String> family = Optional.of("file-operations");

    // No grant: an op in the family is not allowed by family.
    assertFalse(store.isAllowed("core.ingest", family, RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.agent()));

    store.grantFamilyAllowAlways("file-operations", SourceTier.UNTRUSTED);
    assertTrue(store.isAllowed("core.ingest", family, RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.agent()), "any op in the family");
    assertTrue(
        store.isAllowed("core.other-in-family", family, RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.agent()),
        "a different op too");
    assertFalse(
        store.isAllowed("core.ingest", Optional.empty(), RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.agent()),
        "an op WITHOUT the family is not covered");
    assertFalse(
        store.isAllowed("core.ingest", family, RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.ui()), "scoped to the granted tier");

    store.revokeFamily("file-operations", SourceTier.UNTRUSTED);
    assertFalse(store.isAllowed("core.ingest", family, RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.agent()));
  }

  /**
   * Tempdoc 875 C.2 — the risk ceiling. A FAMILY grant for "file-operations" must still authorize the
   * MEDIUM member (560 §28's axis is preserved) and must NOT authorize the HIGH member. Both halves
   * are asserted in one test so a regression that simply broke family grants cannot pass it.
   */
  @Test
  @DisplayName("875 C.2: a family grant covers the MEDIUM member but never the HIGH one")
  void familyGrantDoesNotCoverHighRisk() {
    DurableGrantStore store = new DurableGrantStore();
    Optional<String> family = Optional.of("file-operations");
    store.grantFamilyAllowAlways("file-operations", SourceTier.UNTRUSTED);

    assertTrue(
        store.isAllowed("core.ingest-files", family, RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.agent()),
        "560 §28's family axis is preserved: the MEDIUM member is still auto-approved");
    assertFalse(
        store.isAllowed("core.file-operations", family, RiskTier.HIGH,io.justsearch.app.services.TestEngineContexts.agent()),
        "a family grant never satisfies a HIGH-risk operation — destructive work costs a fresh gesture");
    assertTrue(
        store.isAllowed("core.ingest-files", family, RiskTier.LOW,io.justsearch.app.services.TestEngineContexts.agent()),
        "the ceiling is HIGH-only — LOW is unaffected");
  }

  /** The per-operation grant carries the same payload as the family grant, so it hits the same ceiling. */
  @Test
  @DisplayName("875 C.2: a per-operation grant on the HIGH op does not authorize it either")
  void perOperationGrantDoesNotCoverHighRisk() {
    DurableGrantStore store = new DurableGrantStore();
    store.grantAllowAlways("core.file-operations", SourceTier.UNTRUSTED);

    assertFalse(
        store.isAllowed("core.file-operations", RiskTier.HIGH,io.justsearch.app.services.TestEngineContexts.agent()),
        "'Always allow this action' cannot durably suppress a HIGH-risk gate");
    // Right-reason check: the grant IS present — it is the risk ceiling refusing, not a missing grant.
    assertTrue(
        store.isAllowed("core.file-operations", RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.agent()),
        "the same grant still answers true below the ceiling — the refusal is risk-driven");
    assertTrue(
        store.snapshot().stream()
            .anyMatch(
                g ->
                    g.kind() == DurableGrantStore.GrantKind.OPERATION
                        && "core.file-operations".equals(g.target())),
        "the grant was recorded; isAllowed refused despite it");
  }

  @Test
  @DisplayName("560 §28: durable grants (op + family) persist to disk and reload (survive a restart)")
  void persistsAndReloads(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("ui").resolve("durable-grants.json");
    DurableGrantStore store = new DurableGrantStore(Clock.systemUTC(), file);
    store.grantAllowAlways("core.x", SourceTier.UNTRUSTED);
    store.grantFamilyAllowAlways("file-operations", SourceTier.TRUSTED);

    // A fresh store over the same file reloads both grants.
    DurableGrantStore reopened = new DurableGrantStore(Clock.systemUTC(), file);
    assertTrue(reopened.isAllowed("core.x", RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.agent()), "operation grant survived");
    assertTrue(
        reopened.isAllowed("core.ingest", Optional.of("file-operations"), RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.ui()),
        "family grant survived");
    assertEquals(2, reopened.snapshot().size());
    assertTrue(Files.readString(file).contains("\"schemaVersion\":1"));
  }

  @Test
  void legacyV0StateLoads(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("durable-grants.json");
    Files.writeString(
        file,
        """
        {"grants":[{"kind":"OPERATION","target":"core.x","sourceTier":"UNTRUSTED"}]}
        """);
    DurableGrantStore store = new DurableGrantStore(Clock.systemUTC(), file);
    assertTrue(store.isAllowed("core.x", RiskTier.MEDIUM,io.justsearch.app.services.TestEngineContexts.agent()));
  }

  @Test
  void futureVersionIsRefusedWithoutOverwrite(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("durable-grants.json");
    String future = "{\"schemaVersion\":99,\"grants\":[]}";
    Files.writeString(file, future);
    assertThrows(
        UnsupportedStoreVersionException.class,
        () -> new DurableGrantStore(Clock.systemUTC(), file));
    assertEquals(future, Files.readString(file));
  }

  @Test
  void malformedStateIsRefusedWithoutOverwrite(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("durable-grants.json");
    String malformed = "{not-json";
    Files.writeString(file, malformed);
    assertThrows(
        CorruptDurableStoreException.class,
        () -> new DurableGrantStore(Clock.systemUTC(), file));
    assertEquals(malformed, Files.readString(file));
  }
}
