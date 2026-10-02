package io.justsearch.indexerworker.disambiguation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("DisambiguationService")
class DisambiguationServiceTest {

  @TempDir Path tempDir;
  private DisambiguationService service;

  @BeforeEach
  void setUp() throws Exception {
    service = new DisambiguationService(tempDir);
    service.open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (service != null) {
      service.close();
    }
  }

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {

    @Test
    @DisplayName("is available after open")
    void availableAfterOpen() {
      assertTrue(service.isAvailable());
    }

    @Test
    @DisplayName("snapshot is empty initially")
    void emptySnapshot() {
      assertTrue(service.snapshot().isEmpty());
    }

    @Test
    @DisplayName("not available after close")
    void notAvailableAfterClose() throws Exception {
      service.close();
      assertFalse(service.isAvailable());
    }

    @ParameterizedTest
    @CsvSource({"sql,false", "runtime,false", "error,false", "sql,true", "runtime,true", "error,true"})
    void snapshotFailureClosesOrRetainsStoreUntilSuccessfulRetry(String failureKind, boolean failCleanup)
        throws Exception {
      Path db = tempDir.resolve("failed-snapshot.db");
      Throwable snapshotFailure = injectedFailure(failureKind, "injected snapshot failure");
      Throwable closeFailure = injectedFailure(failureKind, "injected native close failure");
      AtomicBoolean failClose = new AtomicBoolean(failCleanup);
      AtomicBoolean snapshotQueryReached = new AtomicBoolean();
      AtomicInteger closeAttempts = new AtomicInteger();
      AtomicInteger openAttempts = new AtomicInteger();
      try (Connection acquired = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath())) {
        Connection faulting = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              if (method.getName().equals("close")) {
                closeAttempts.incrementAndGet();
                if (failClose.get()) throw closeFailure;
              }
              Object result = invoke(acquired, method, args);
              if (method.getName().equals("createStatement")) {
                Statement statement = (Statement) result;
                return Proxy.newProxyInstance(
                    Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
                    (statementProxy, statementMethod, statementArgs) -> {
                      if (statementMethod.getName().equals("executeQuery")
                          && ((String) statementArgs[0]).contains("FROM entity_clusters")) {
                        snapshotQueryReached.set(true);
                        throw snapshotFailure;
                      }
                      return invoke(statement, statementMethod, statementArgs);
                    });
              }
              return result;
            });
        EntityClusterStore candidateStore = new EntityClusterStore(db,
            jdbcUrl -> openAttempts.incrementAndGet() == 1
                ? faulting : DriverManager.getConnection(jdbcUrl));
        DisambiguationService candidate = new DisambiguationService(candidateStore);
        try {
          Throwable observed = assertThrows(snapshotFailure.getClass(), candidate::open);
          assertSame(snapshotFailure, observed, "snapshot failure must remain primary");
          assertTrue(snapshotQueryReached.get(), "schema initialization must succeed before the fault");
          assertFalse(candidate.isAvailable(), "failed candidates must never become available");
          assertSame(EntityClusterSnapshot.EMPTY, candidate.snapshot());
          assertEquals(1, openAttempts.get());
          assertEquals(1, closeAttempts.get(), "failed snapshot initialization must attempt cleanup");

          if (failCleanup) {
            assertFalse(acquired.isClosed());
            assertThrows(SQLException.class, candidateStore::loadAll,
                "retained cleanup ownership must not allow operations");
            assertEquals(1, observed.getSuppressed().length);
            Throwable suppressed = observed.getSuppressed()[0];
            assertSame(closeFailure, failureKind.equals("sql") ? suppressed.getCause() : suppressed);
            assertThrows(SQLException.class, candidate::open,
                "reopen must not replace an unconfirmed owner");
            assertEquals(1, openAttempts.get());
            Throwable shutdownFailure = assertThrows(
                failureKind.equals("sql") ? IOException.class : closeFailure.getClass(), candidate::close);
            assertSame(closeFailure,
                failureKind.equals("sql") ? shutdownFailure.getCause() : shutdownFailure);
            assertEquals(2, closeAttempts.get());
            assertFalse(candidate.isAvailable());
            assertSame(EntityClusterSnapshot.EMPTY, candidate.snapshot());

            failClose.set(false);
            candidate.close();
            assertEquals(3, closeAttempts.get());
          } else {
            assertTrue(acquired.isClosed(), "failed initialization must close before returning");
            assertEquals(0, observed.getSuppressed().length);
          }
          assertTrue(acquired.isClosed());
          assertThrows(SQLException.class, candidateStore::loadAll);
          candidate.close();
          assertEquals(failCleanup ? 3 : 1, closeAttempts.get(), "confirmed close must be idempotent");
          candidate.open();
          assertTrue(candidate.isAvailable(), "confirmed cleanup must allow a fresh open");
          assertEquals(2, openAttempts.get());
          candidate.close();
          assertFalse(candidate.isAvailable());
        } finally {
          failClose.set(false);
          candidate.close();
        }
      }
    }

    @Test
    void failedCloseRevokesAvailabilityAndSnapshotUntilRetry() throws Exception {
      Path db = tempDir.resolve("failed-close.db");
      try (EntityClusterStore seed = new EntityClusterStore(db)) {
        seed.open();
        seed.upsert("john smith", "PERSON", "c1", "john smith", 0.95);
      }
      SQLException closeFailure = new SQLException("injected native close failure");
      AtomicBoolean failClose = new AtomicBoolean(true);
      AtomicInteger closeAttempts = new AtomicInteger();
      try (Connection acquired = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath())) {
        Connection faulting = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              if (method.getName().equals("close")) {
                closeAttempts.incrementAndGet();
                if (failClose.get()) throw closeFailure;
              }
              return invoke(acquired, method, args);
            });
        EntityClusterStore candidateStore = new EntityClusterStore(db, ignored -> faulting);
        DisambiguationService candidate = new DisambiguationService(candidateStore);
        try {
          candidate.open();
          assertTrue(candidate.isAvailable());
          assertFalse(candidate.snapshot().isEmpty());
          IOException failure = assertThrows(IOException.class, candidate::close);
          assertSame(closeFailure, failure.getCause());
          assertFalse(acquired.isClosed());
          assertFalse(candidate.isAvailable(), "an unconfirmed close must revoke availability");
          assertSame(EntityClusterSnapshot.EMPTY, candidate.snapshot());
          assertThrows(SQLException.class, candidateStore::loadAll);
          assertThrows(SQLException.class, candidate::open);
          assertEquals(1, closeAttempts.get());

          failClose.set(false);
          candidate.close();
          assertTrue(acquired.isClosed());
          candidate.close();
          assertEquals(2, closeAttempts.get());
        } finally {
          failClose.set(false);
          candidate.close();
        }
      }
    }
  }

  private static Throwable injectedFailure(String kind, String message) {
    return switch (kind) {
      case "sql" -> new SQLException(message);
      case "runtime" -> new IllegalStateException(message);
      case "error" -> new AssertionError(message);
      default -> throw new IllegalArgumentException(kind);
    };
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  @Nested
  @DisplayName("processBatch")
  class ProcessBatch {

    @Test
    @DisplayName("null or empty batch returns 0")
    void emptyBatch() throws Exception {
      assertEquals(0, service.processBatch(null));
      assertEquals(0, service.processBatch(Map.of()));
      assertEquals(0, service.processBatch(Map.of("PERSON", List.of())));
    }

    @Test
    @DisplayName("single mention creates singleton cluster")
    void singleMention() throws Exception {
      int created = service.processBatch(Map.of("PERSON", List.of("John Smith")));
      assertEquals(1, created);

      EntityClusterSnapshot snap = service.snapshot();
      assertFalse(snap.isEmpty());
      // The canonical form should be the raw form (singleton)
      String canonical = snap.getCanonical("PERSON", "John Smith");
      assertNotNull(canonical);
    }

    @Test
    @DisplayName("identical mentions after normalization are not duplicated")
    void deduplication() throws Exception {
      // "JOHN SMITH" and "John Smith" normalize to the same form
      int created =
          service.processBatch(Map.of("PERSON", List.of("John Smith", "John Smith")));
      assertEquals(1, created);
    }

    @Test
    @DisplayName("different entities create separate clusters")
    void separateClusters() throws Exception {
      service.processBatch(Map.of("PERSON", List.of("John Smith", "Sarah Chen")));

      EntityClusterSnapshot snap = service.snapshot();
      String c1 = snap.getCanonical("PERSON", "John Smith");
      String c2 = snap.getCanonical("PERSON", "Sarah Chen");
      // Different people should have different canonical forms
      assertFalse(c1.equals(c2), "Different entities should not share canonical form");
    }

    @Test
    @DisplayName("within-batch mentions share correct canonical form (C1/C2 regression)")
    void withinBatchClustering() throws Exception {
      // Two similar names in one batch should cluster together and both have
      // the correct canonical form (the first mention), not some unrelated canonical.
      int created =
          service.processBatch(
              Map.of("PERSON", List.of("John Smith", "Jon Smith", "Jane Doe")));
      assertTrue(created >= 2, "Should create at least 2 entries (maybe 3)");

      EntityClusterSnapshot snap = service.snapshot();
      String c1 = snap.getCanonical("PERSON", "John Smith");
      String c2 = snap.getCanonical("PERSON", "Jon Smith");
      String c3 = snap.getCanonical("PERSON", "Jane Doe");
      assertNotNull(c1, "John Smith should have a canonical");
      assertNotNull(c3, "Jane Doe should have a canonical");

      // Jon Smith should either match John Smith's canonical (if SoftTFIDF scored high enough)
      // or be its own canonical — but should NOT have Jane Doe's canonical
      assertFalse(
          c2.equals(c3) && !c2.equals(c1),
          "Jon Smith should not be assigned an unrelated canonical (C1/C2 regression)");
    }

    @Test
    @DisplayName("similar entities cluster together (case variants)")
    void caseClustering() throws Exception {
      // First batch: create a cluster for "john smith"
      service.processBatch(Map.of("PERSON", List.of("john smith")));
      // Second batch: "Jon Smith" has typo — SoftTFIDF should match via token overlap
      service.processBatch(Map.of("PERSON", List.of("Jon Smith")));

      EntityClusterSnapshot snap = service.snapshot();
      // Both should resolve to the same canonical
      String c1 = snap.getCanonical("PERSON", "john smith");
      String c2 = snap.getCanonical("PERSON", "Jon Smith");
      assertEquals(c1, c2, "Typo variant should cluster with original");
    }

    @Test
    @DisplayName("multiple entity types processed independently")
    void multipleTypes() throws Exception {
      service.processBatch(
          Map.of(
              "PERSON", List.of("John Smith"),
              "ORGANIZATION", List.of("Acme Corp"),
              "LOCATION", List.of("New York")));

      EntityClusterSnapshot snap = service.snapshot();
      assertNotNull(snap.getCanonical("PERSON", "John Smith"));
      assertNotNull(snap.getCanonical("ORGANIZATION", "Acme Corp"));
      assertNotNull(snap.getCanonical("LOCATION", "New York"));
    }
  }

  @Nested
  @DisplayName("cluster size limits")
  class ClusterSizeLimits {

    @Test
    @DisplayName("new mention becomes singleton when cluster reaches MAX_CLUSTER_SIZE")
    void maxClusterSizeCap() throws Exception {
      // Fill a cluster to MAX_CLUSTER_SIZE with mentions that share the same tokens.
      // Use "john smith <suffix>" variants so SoftTFIDF clusters them together.
      List<String> batch = new java.util.ArrayList<>();
      for (int i = 0; i < DisambiguationService.MAX_CLUSTER_SIZE; i++) {
        batch.add("john smith " + (char) ('a' + (i % 26)) + (i / 26));
      }
      service.processBatch(Map.of("PERSON", batch));

      // Add one more similar mention — should overflow into a new singleton
      String overflow = "john smith overflow";
      service.processBatch(Map.of("PERSON", List.of(overflow)));

      EntityClusterSnapshot snap = service.snapshot();
      String overflowCanonical = snap.getCanonical("PERSON", overflow);

      // The overflow mention should be its own canonical (singleton cluster),
      // not merged into the full cluster
      assertEquals(
          overflow,
          overflowCanonical,
          "Overflow mention should be a singleton when cluster is full");
    }
  }

  @Nested
  @DisplayName("snapshot queries")
  class SnapshotQueries {

    @Test
    @DisplayName("getCanonical returns raw form when unmapped")
    void unmappedReturnsRaw() {
      assertEquals("Unknown Person", service.snapshot().getCanonical("PERSON", "Unknown Person"));
    }

    @Test
    @DisplayName("expandCanonical returns singleton for unmapped")
    void expandUnmapped() {
      Set<String> expanded = service.snapshot().expandCanonical("PERSON", "Unknown Person");
      assertEquals(Set.of("Unknown Person"), expanded);
    }

    @Test
    @DisplayName("expandCanonical returns all variants for clustered entity")
    void expandClustered() throws Exception {
      service.processBatch(Map.of("PERSON", List.of("john smith")));
      service.processBatch(Map.of("PERSON", List.of("Jon Smith")));

      EntityClusterSnapshot snap = service.snapshot();
      String canonical = snap.getCanonical("PERSON", "john smith");
      Set<String> variants = snap.expandCanonical("PERSON", canonical);
      assertTrue(variants.size() >= 2, "Should have at least 2 variants: " + variants);
    }
  }
}
