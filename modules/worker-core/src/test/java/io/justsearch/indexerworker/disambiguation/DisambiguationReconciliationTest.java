/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.disambiguation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DisambiguationReconciliationTest {
  @TempDir Path directory;

  @Test void failedFinalPublicationRetriesRetirementWithZeroNewForms() throws Exception {
    Path database = directory.resolve("retirement-publication.db");
    var store = spy(new EntityClusterStore(database));
    try (var service = new DisambiguationService(store)) {
      service.open();
      var initial = new java.util.LinkedHashMap<String, Map<String, List<String>>>();
      initial.put("retired", Map.of("PERSON", List.of("john smith")));
      initial.put("survivor", Map.of("PERSON", List.of("JOHN SMITH")));
      assertTrue(service.prepareReconciliation(new Object(), new Object()));
      service.replaceSources(initial);
      service.acknowledgeReconciliation(7, true);
      var captured = service.snapshot();
      assertEquals(Set.of("john smith", "JOHN SMITH"), captured.expandCanonical("PERSON", "john smith"));

      Object revision = new Object();
      Object reader = new Object();
      var survivingSources = Map.of("survivor", Map.of("PERSON", List.of("JOHN SMITH")));
      assertTrue(service.prepareReconciliation(revision, reader));
      clearInvocations(store);
      var retainedSources = new java.util.ArrayList<Set<String>>();
      doAnswer(call -> {
        retainedSources.add(Set.copyOf(call.getArgument(0)));
        return call.callRealMethod();
      }).when(store).retainSources(anySet());
      service.replaceSources(survivingSources);
      doThrow(new SQLException("final snapshot read failure")).doCallRealMethod().when(store).loadAll();
      assertThrows(SQLException.class, () -> service.acknowledgeReconciliation(8, true));

      assertEquals(-1, service.reconciliationCursor(), "failed publication must not acknowledge the final cursor");
      assertTrue(service.prepareReconciliation(revision, reader), "completion must remain pending");
      assertSame(captured, service.snapshot(), "failed publication must preserve the captured snapshot");
      assertEquals(List.of("JOHN SMITH"), store.loadAll().stream().map(ClusterEntry::rawForm).toList(),
          "retirement must already have committed before the snapshot read failed");
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
          var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT source_id, raw_form, entity_type FROM entity_mentions")) {
        assertTrue(rows.next());
        assertEquals("survivor", rows.getString(1));
        assertEquals("JOHN SMITH", rows.getString(2));
        assertEquals("PERSON", rows.getString(3));
        assertFalse(rows.next(), "only surviving ownership may remain after retirement");
      }

      service.replaceSources(survivingSources);
      verify(store, never()).upsert(anyString(), anyString(), anyString(), anyString(), anyDouble());
      assertSame(captured, service.snapshot(), "zero-new-form replay must still defer publication");
      service.acknowledgeReconciliation(8, true);
      assertFalse(service.prepareReconciliation(revision, reader));
      assertEquals(8, service.reconciliationCursor());
      assertEquals("JOHN SMITH", service.snapshot().getCanonical("PERSON", "JOHN SMITH"));
      assertEquals(Set.of("JOHN SMITH"), service.snapshot().expandCanonical("PERSON", "JOHN SMITH"));
      assertEquals(Set.of("john smith"), service.snapshot().expandCanonical("PERSON", "john smith"));
      assertEquals(List.of(Set.of("survivor"), Set.of("survivor")), retainedSources);
    }
  }

  @Test void failedResetRestartsFromTheBeginningAndCanBeRetried() throws Exception {
    var store = spy(new EntityClusterStore(directory.resolve("entity-clusters.db")));
    try (var service = new DisambiguationService(store)) {
      service.open();
      Object revision = new Object();
      Object reader = new Object();
      var sources = Map.of("a", Map.of("PERSON", List.of("john smith", "JOHN SMITH")));
      assertTrue(service.prepareReconciliation(revision, reader));
      service.replaceSources(sources);
      service.acknowledgeReconciliation(7, true);
      assertFalse(service.prepareReconciliation(revision, reader));
      doThrow(new SQLException("reset cleanup failure")).doCallRealMethod().when(store).deleteAll();
      assertThrows(SQLException.class, service::reset);
      assertTrue(service.isAvailable());
      assertTrue(service.prepareReconciliation(revision, reader));
      assertEquals(-1, service.reconciliationCursor(), "failed cleanup must not reuse the finished cursor");
      service.replaceSources(sources);
      service.acknowledgeReconciliation(7, true);
      assertEquals(Set.of("john smith", "JOHN SMITH"), service.snapshot().expandCanonical("PERSON", "john smith"));
      service.reset();
      assertTrue(service.isAvailable());
      assertTrue(service.snapshot().isEmpty());
    }
  }

  @Test void establishedMultiPageLibraryThenOneAdditionHasBoundedWork() throws Exception {
    var store = spy(new EntityClusterStore(directory.resolve("scaling.db")));
    try (var service = new DisambiguationService(store)) {
      service.open();
      for (int page = 0; page < 20; page++) service.replaceSources(sources(page, false));
      service.close();
      service.open();
      clearInvocations(store);
      var normalizations = new java.util.concurrent.atomic.AtomicInteger();
      var rebuilds = new java.util.concurrent.atomic.AtomicInteger();
      try (var normalizer = mockStatic(EntityNormalizer.class, CALLS_REAL_METHODS);
          var snapshots = mockStatic(EntityClusterSnapshot.class, CALLS_REAL_METHODS)) {
        normalizer.when(() -> EntityNormalizer.normalize(anyString(), any())).thenAnswer(call -> {
          normalizations.incrementAndGet();
          return call.callRealMethod();
        });
        snapshots.when(() -> EntityClusterSnapshot.fromEntries(anyList())).thenAnswer(call -> {
          rebuilds.incrementAndGet();
          return call.callRealMethod();
        });
        for (int pass = 0; pass < 2; pass++) {
          assertTrue(service.prepareReconciliation(new Object(), new Object()));
          for (int page = 0; page < 20; page++) {
            service.replaceSources(sources(page, pass == 1));
            service.acknowledgeReconciliation(page, page == 19);
          }
        }
        long loads = mockingDetails(store).getInvocations().stream()
            .filter(call -> call.getMethod().getName().equals("loadAll")).count();
        assertAll(
            () -> assertTrue(loads <= 2, "store loads: " + loads),
            () -> assertTrue(normalizations.get() <= 201, "normalizations: " + normalizations),
            () -> assertEquals(1, rebuilds.get(), "snapshot rebuilds"));
        assertFalse(service.snapshot().isEmpty());
        assertTrue(store.loadAll().stream().anyMatch(entry -> entry.rawForm().equals("new arrival")));
      }
    }
  }

  @Test void newMultiPageLibraryPublishesOnlyAtCompletion() throws Exception {
    var store = spy(new EntityClusterStore(directory.resolve("new-library.db")));
    try (var service = new DisambiguationService(store)) {
      service.open();
      clearInvocations(store);
      assertTrue(service.prepareReconciliation(new Object(), new Object()));
      try (var snapshots = mockStatic(EntityClusterSnapshot.class, CALLS_REAL_METHODS)) {
        for (int page = 0; page < 20; page++) {
          service.replaceSources(sources(page, false));
          service.acknowledgeReconciliation(page, page == 19);
        }
        snapshots.verify(() -> EntityClusterSnapshot.fromEntries(anyList()), atMost(1));
      }
      verify(store, atMost(2)).loadAll();
      assertFalse(service.snapshot().isEmpty());
    }
  }

  static Map<String, Map<String, List<String>>> sources(int page, boolean addition) {
    var sources = new java.util.LinkedHashMap<String, Map<String, List<String>>>();
    for (int offset = 0; offset < 10; offset++) {
      String id = "source-" + (page * 10 + offset);
      String name = java.util.UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          .toString().replace("-", "");
      sources.put(id, Map.of("PERSON", addition && page == 19 && offset == 9
          ? List.of(name, "new arrival") : List.of(name)));
    }
    return sources;
  }

}
