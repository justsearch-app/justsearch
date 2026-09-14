package io.justsearch.app.services.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.app.observability.ledger.ActionEvent;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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

  @Test
  @DisplayName("findAllowed returns the operation entry before a matching family entry")
  void findsOperationBeforeFamily() {
    DurableGrantStore store = new DurableGrantStore();
    Optional<String> family = Optional.of("file-operations");
    store.grantFamilyAllowAlways("file-operations", SourceTier.UNTRUSTED);
    store.grantAllowAlways("core.ingest", SourceTier.UNTRUSTED);

    assertEquals(
        Optional.of(new DurableGrantStore.DurableGrant(
            DurableGrantStore.GrantKind.OPERATION, "core.ingest", SourceTier.UNTRUSTED)),
        store.findAllowed("core.ingest", family, RiskTier.MEDIUM,
            io.justsearch.app.services.TestEngineContexts.agent()));

    store.revoke("core.ingest", SourceTier.UNTRUSTED);
    assertEquals(
        Optional.of(new DurableGrantStore.DurableGrant(
            DurableGrantStore.GrantKind.FAMILY, "file-operations", SourceTier.UNTRUSTED)),
        store.findAllowed("core.ingest", family, RiskTier.MEDIUM,
            io.justsearch.app.services.TestEngineContexts.agent()));
  }

  @Test
  @DisplayName("exact revalidation keeps the selected family and refuses a revoked selected entry")
  void exactRevalidationDoesNotSubstituteAnotherGrant() {
    DurableGrantStore store = new DurableGrantStore();
    Optional<String> family = Optional.of("file-operations");
    var agent = io.justsearch.app.services.TestEngineContexts.agent();
    store.grantFamilyAllowAlways("file-operations", SourceTier.UNTRUSTED);
    DurableGrantStore.DurableGrant selectedFamily =
        store.findAllowed("core.ingest", family, RiskTier.MEDIUM, agent).orElseThrow();

    // A later, more-specific grant does not invalidate the family entry that was selected earlier.
    store.grantAllowAlways("core.ingest", SourceTier.UNTRUSTED);
    assertTrue(store.isAllowed(selectedFamily, "core.ingest", family, RiskTier.MEDIUM, agent));

    // Revoking that selected family entry fails exact recovery, even though the operation grant matches.
    store.revokeFamily("file-operations", SourceTier.UNTRUSTED);
    assertFalse(store.isAllowed(selectedFamily, "core.ingest", family, RiskTier.MEDIUM, agent));
    assertTrue(store.isAllowed("core.ingest", family, RiskTier.MEDIUM, agent),
        "ordinary selection may use the currently available operation grant");

    // The same no-substitution rule applies when the operation entry was selected first.
    store.grantFamilyAllowAlways("file-operations", SourceTier.UNTRUSTED);
    DurableGrantStore.DurableGrant selectedOperation =
        store.findAllowed("core.ingest", family, RiskTier.MEDIUM, agent).orElseThrow();
    store.revoke("core.ingest", SourceTier.UNTRUSTED);
    assertFalse(store.isAllowed(selectedOperation, "core.ingest", family, RiskTier.MEDIUM, agent));
    assertTrue(store.isAllowed("core.ingest", family, RiskTier.MEDIUM, agent),
        "ordinary selection may use the currently available family grant");
  }

  @Test
  @DisplayName("exact revalidation requires matching target, tier, declared scope, and non-HIGH risk")
  void exactRevalidationRejectsMismatches() {
    DurableGrantStore store = new DurableGrantStore();
    Optional<String> family = Optional.of("file-operations");
    var agent = io.justsearch.app.services.TestEngineContexts.agent();
    var ui = io.justsearch.app.services.TestEngineContexts.ui();
    store.grantFamilyAllowAlways("file-operations", SourceTier.UNTRUSTED);
    DurableGrantStore.DurableGrant selected =
        store.findAllowed("core.ingest", family, RiskTier.MEDIUM, agent).orElseThrow();
    assertTrue(
        store.isAllowed(selected, "core.other-in-family", family, RiskTier.MEDIUM, agent),
        "a selected family grant covers another operation declaring that family");
    store.grantAllowAlways("core.ingest", SourceTier.UNTRUSTED);
    DurableGrantStore.DurableGrant selectedOperation =
        store.findAllowed("core.ingest", family, RiskTier.MEDIUM, agent).orElseThrow();

    assertFalse(store.isAllowed(selectedOperation, "core.other", family, RiskTier.MEDIUM, agent));
    assertFalse(
        store.isAllowed(selected, "core.ingest", Optional.of("other"), RiskTier.MEDIUM, agent));
    assertFalse(
        store.isAllowed(selected, "core.ingest", Optional.empty(), RiskTier.MEDIUM, agent));
    assertFalse(store.isAllowed(selected, "core.ingest", family, RiskTier.HIGH, agent));
    assertFalse(store.isAllowed(selected, "core.ingest", family, RiskTier.MEDIUM, ui));
    assertFalse(store.isAllowed(
        new DurableGrantStore.DurableGrant(
            DurableGrantStore.GrantKind.FAMILY, "file-operations", SourceTier.TRUSTED),
        "core.ingest", family, RiskTier.MEDIUM, agent));
    assertFalse(store.isAllowed(
        new DurableGrantStore.DurableGrant(
            DurableGrantStore.GrantKind.OPERATION, "core.ungranted", SourceTier.UNTRUSTED),
        "core.ungranted", Optional.empty(), RiskTier.MEDIUM, agent));
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

  @Test
  @DisplayName("failed grant persistence publishes neither state nor audit, then retry persists")
  void failedGrantDoesNotPublishOrEmit(@TempDir Path dir) throws Exception {
    Path parent = dir.resolve("ui");
    Path file = parent.resolve("durable-grants.json");
    DurableGrantStore store = new DurableGrantStore(Clock.systemUTC(), file);
    store.grantAllowAlways("core.committed", SourceTier.UNTRUSTED);
    Set<DurableGrantStore.DurableGrant> committed = snapshotOf(store);
    List<ActionEvent> events = new ArrayList<>();
    store.setGrantEventSink(events::add);

    Path savedParent = blockParent(dir, parent);
    try {
      UncheckedIOException failure = assertThrows(
          UncheckedIOException.class,
          () -> store.grantAllowAlways("core.failed", SourceTier.UNTRUSTED));
      assertInstanceOf(IOException.class, failure.getCause());
      assertEquals(committed, snapshotOf(store), "failed grant is not visible in memory");
      assertTrue(events.isEmpty(), "failed grant emits no audit event");
      assertEquals(committed,
          snapshotOf(new DurableGrantStore(Clock.systemUTC(), savedParent.resolve(file.getFileName()))),
          "failed grant is absent after reopen");
    } finally {
      restoreParent(parent, savedParent);
    }

    store.grantAllowAlways("core.failed", SourceTier.UNTRUSTED);
    assertTrue(store.isAllowed("core.failed", RiskTier.MEDIUM,
        io.justsearch.app.services.TestEngineContexts.agent()));
    assertEquals(snapshotOf(store), snapshotOf(new DurableGrantStore(Clock.systemUTC(), file)));
    assertEquals(1, events.size(), "only the successful retry emits");
  }

  @Test
  @DisplayName("failed operation, family, and hard-stop revokes preserve the last committed state")
  void failedRevokesPreserveCommittedState(@TempDir Path dir) throws Exception {
    Path parent = dir.resolve("ui");
    Path file = parent.resolve("durable-grants.json");
    DurableGrantStore store = new DurableGrantStore(Clock.systemUTC(), file);
    store.grantAllowAlways("core.revoke", SourceTier.UNTRUSTED);
    store.grantAllowAlways("core.hard-stop", SourceTier.UNTRUSTED);
    store.grantFamilyAllowAlways("family.revoke", SourceTier.UNTRUSTED);
    store.grantAllowAlways("core.trusted", SourceTier.TRUSTED);
    Set<DurableGrantStore.DurableGrant> committed = snapshotOf(store);
    List<ActionEvent> events = new ArrayList<>();
    store.setGrantEventSink(events::add);

    Path savedParent = blockParent(dir, parent);
    try {
      UncheckedIOException operationFailure = assertThrows(
          UncheckedIOException.class,
          () -> store.revoke("core.revoke", SourceTier.UNTRUSTED));
      assertInstanceOf(IOException.class, operationFailure.getCause());
      UncheckedIOException familyFailure = assertThrows(
          UncheckedIOException.class,
          () -> store.revokeFamily("family.revoke", SourceTier.UNTRUSTED));
      assertInstanceOf(IOException.class, familyFailure.getCause());
      UncheckedIOException hardStopFailure = assertThrows(
          UncheckedIOException.class, store::revokeNonUser);
      assertInstanceOf(IOException.class, hardStopFailure.getCause());

      assertEquals(committed, snapshotOf(store), "failed revokes leave memory unchanged");
      assertTrue(events.isEmpty(), "failed revokes emit no audit events");
      assertEquals(committed,
          snapshotOf(new DurableGrantStore(Clock.systemUTC(), savedParent.resolve(file.getFileName()))),
          "failed revokes leave the saved file unchanged");
    } finally {
      restoreParent(parent, savedParent);
    }

    store.revoke("core.revoke", SourceTier.UNTRUSTED);
    store.revokeFamily("family.revoke", SourceTier.UNTRUSTED);
    store.revokeNonUser();
    assertTrue(store.isAllowed("core.trusted", RiskTier.MEDIUM,
        io.justsearch.app.services.TestEngineContexts.ui()));
    assertEquals(1, store.snapshot().size(), "retry removes all non-user entries");
    assertEquals(snapshotOf(store), snapshotOf(new DurableGrantStore(Clock.systemUTC(), file)));
  }

  @Test
  @DisplayName("concurrent writers retain every committed grant after reopen")
  void concurrentWritersDoNotLoseGrants(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("ui").resolve("durable-grants.json");
    DurableGrantStore store = new DurableGrantStore(Clock.systemUTC(), file);
    int writers = 12;
    CountDownLatch ready = new CountDownLatch(writers);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < writers; i++) {
        int writer = i;
        futures.add(pool.submit(() -> {
          ready.countDown();
          assertTrue(start.await(5, TimeUnit.SECONDS));
          if (writer % 2 == 0) {
            store.grantAllowAlways("core.concurrent." + writer, SourceTier.UNTRUSTED);
          } else {
            store.grantFamilyAllowAlways("family.concurrent." + writer, SourceTier.UNTRUSTED);
          }
          return null;
        }));
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS), "all writers reached the barrier");
      start.countDown();
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "writer pool terminated");
    }

    Set<DurableGrantStore.DurableGrant> live = snapshotOf(store);
    assertEquals(writers, live.size());
    assertEquals(live, snapshotOf(new DurableGrantStore(Clock.systemUTC(), file)),
        "reopen matches the complete committed live snapshot");
  }

  @ParameterizedTest(name = "invalid persisted grant row: {0}")
  @MethodSource("invalidPersistedGrantRows")
  void invalidPersistedGrantRowsAreRefusedWithoutOverwrite(String grantRow, @TempDir Path dir)
      throws Exception {
    Path file = dir.resolve("durable-grants.json");
    String state = "{\"schemaVersion\":1,\"grants\":[" + grantRow + "]}";
    Files.writeString(file, state);

    assertThrows(
        CorruptDurableStoreException.class,
        () -> new DurableGrantStore(Clock.systemUTC(), file));
    assertEquals(state, Files.readString(file));
  }

  static List<String> invalidPersistedGrantRows() {
    return List.of(
        "null",
        "{\"kind\":null,\"target\":\"core.x\",\"sourceTier\":\"UNTRUSTED\"}",
        "{\"kind\":\"UNKNOWN\",\"target\":\"core.x\",\"sourceTier\":\"UNTRUSTED\"}",
        "{\"kind\":\"OPERATION\",\"target\":null,\"sourceTier\":\"UNTRUSTED\"}",
        "{\"kind\":\"OPERATION\",\"target\":\"\",\"sourceTier\":\"UNTRUSTED\"}",
        "{\"kind\":\"OPERATION\",\"target\":\"core\\u0001x\",\"sourceTier\":\"UNTRUSTED\"}",
        "{\"kind\":\"OPERATION\",\"target\":\"core.x\",\"sourceTier\":null}",
        "{\"kind\":\"OPERATION\",\"target\":\"core.x\",\"sourceTier\":\"UNKNOWN\"}");
  }

  @Test
  void invalidIssuanceLeavesCommittedFileAndViewUnchanged(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("ui").resolve("durable-grants.json");
    DurableGrantStore store = new DurableGrantStore(Clock.systemUTC(), file);
    store.grantAllowAlways("core.committed", SourceTier.UNTRUSTED);
    Set<DurableGrantStore.DurableGrant> committed = snapshotOf(store);
    String saved = Files.readString(file);

    assertThrows(IllegalArgumentException.class,
        () -> store.grantAllowAlways("", SourceTier.UNTRUSTED));
    assertThrows(IllegalArgumentException.class,
        () -> store.grantAllowAlways("core" + Character.toString((char) 1) + "x",
            SourceTier.UNTRUSTED));
    assertThrows(IllegalArgumentException.class,
        () -> store.grantFamilyAllowAlways("", SourceTier.UNTRUSTED));

    assertEquals(committed, snapshotOf(store));
    assertEquals(saved, Files.readString(file));
  }

  private static Set<DurableGrantStore.DurableGrant> snapshotOf(DurableGrantStore store) {
    return Set.copyOf(store.snapshot());
  }

  private static Path blockParent(Path tempDir, Path parent) throws IOException {
    Path savedParent = tempDir.resolve("ui-saved");
    Files.move(parent, savedParent, StandardCopyOption.REPLACE_EXISTING);
    Files.writeString(parent, "blocked");
    return savedParent;
  }

  private static void restoreParent(Path parent, Path savedParent) throws IOException {
    Files.delete(parent);
    Files.move(savedParent, parent, StandardCopyOption.REPLACE_EXISTING);
  }
}
