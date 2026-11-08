/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks the real bootstrap admission seam; Worker payload preservation has separate live tests. */
final class FrozenEvalBootstrapTest {
  @TempDir Path directory;

  @Test void lateHealthyObservationsRetainSuppressionEvenWhenLaunchPropertiesChange() throws Exception {
    String eval = System.getProperty("justsearch.eval.mode"), frozen = System.getProperty("justsearch.eval.frozen");
    System.setProperty("justsearch.eval.mode", "true"); System.setProperty("justsearch.eval.frozen", "true");
    try (var fixture = KnowledgeServerBootstrapTestFixture.create(config())) {
      var bootstrap = fixture.bootstrap();
      assertTrue(bootstrap.automaticRootProducersSuppressed());
      var client = mock(KnowledgeClient.class);
      when(client.isHealthy(any())).thenReturn(true);
      var field = KnowledgeServerBootstrap.class.getDeclaredField("client");
      field.setAccessible(true); field.set(bootstrap, client);
      System.setProperty("justsearch.eval.frozen", "false");
      assertTrue(bootstrap.checkHealth()); assertTrue(bootstrap.checkHealth());
      verify(client, never()).reindexPersistedRoots(any());
      verify(client, never()).startPeriodicSync();
      verify(client, never()).submitBatch(any(), anyBoolean(), anyString(), any());
      assertTrue(bootstrap.automaticRootProducersSuppressed());
    } finally {
      if (eval == null) System.clearProperty("justsearch.eval.mode"); else System.setProperty("justsearch.eval.mode", eval);
      if (frozen == null) System.clearProperty("justsearch.eval.frozen"); else System.setProperty("justsearch.eval.frozen", frozen);
    }
  }

  @Test void frozenWithoutEvalRefusesBeforePhysicalOwnerStarts() throws Exception {
    String eval = System.getProperty("justsearch.eval.mode"), frozen = System.getProperty("justsearch.eval.frozen");
    System.setProperty("justsearch.eval.mode", "false"); System.setProperty("justsearch.eval.frozen", "true");
    var host = mock(WorkerHost.class);
    try {
      assertThrows(IllegalStateException.class, () -> KnowledgeServerBootstrapTestFixture.create(config(), host));
      verifyNoInteractions(host);
    } finally {
      if (eval == null) System.clearProperty("justsearch.eval.mode"); else System.setProperty("justsearch.eval.mode", eval);
      if (frozen == null) System.clearProperty("justsearch.eval.frozen"); else System.setProperty("justsearch.eval.frozen", frozen);
    }
  }

  private KnowledgeServerConfig config() {
    return new KnowledgeServerConfig(false, directory, directory, directory, 5_000L, 15_000L,
        3, 5_000L, 5_000L, 300_000L, 100, 0L, 0);
  }
}
