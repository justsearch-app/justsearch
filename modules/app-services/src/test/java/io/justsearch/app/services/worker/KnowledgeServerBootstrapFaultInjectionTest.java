package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class KnowledgeServerBootstrapFaultInjectionTest {
  @Test
  void countdownExhaustsBootRetriesThenAllowsRecoveryToReachTheHost(@TempDir Path dir)
      throws Exception {
    WorkerHost host = mock(WorkerHost.class);
    when(host.start(any(), any())).thenThrow(new IOException("host reached"));
    var bootstrap = new KnowledgeServerBootstrap(new io.justsearch.core.execution.TestEngineExecutors(), config(dir, false), null, null, host);

    assertThrows(IOException.class, () -> bootstrap.startWithRetry(3, 0));
    verify(host, never()).start(any(), any());
    IOException next = assertThrows(IOException.class, bootstrap::start);
    assertEquals("host reached", next.getMessage());
    verify(host).start(any(), any());
  }

  @Test
  void productionCannotInjectAnIndexBootFailure(@TempDir Path dir) throws Exception {
    WorkerHost host = mock(WorkerHost.class);
    when(host.start(any(), any())).thenThrow(new IOException("host reached"));
    var bootstrap = new KnowledgeServerBootstrap(new io.justsearch.core.execution.TestEngineExecutors(), config(dir, true), null, null, host);

    IOException failure = assertThrows(IOException.class, bootstrap::start);
    assertEquals("host reached", failure.getMessage());
    verify(host).start(any(), any());
  }

  private static KnowledgeServerConfig config(Path dir, boolean production) {
    return new KnowledgeServerConfig(
        production, dir, dir, dir, 5000, 2000, 3, 2000, 1000, 300000, 100, 0, 3);
  }
}
