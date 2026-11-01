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
}
