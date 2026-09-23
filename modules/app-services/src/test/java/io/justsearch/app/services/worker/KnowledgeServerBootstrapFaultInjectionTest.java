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
    try (var fixture = KnowledgeServerBootstrapTestFixture.create(config(dir, false), host)) {
      var bootstrap = fixture.bootstrap();
      assertThrows(IOException.class, () -> bootstrap.startWithRetry(3, 0));
      verify(host, never()).start(any(), any());
      IOException next = assertThrows(IOException.class, bootstrap::start);
      assertEquals("host reached", next.getMessage());
      verify(host).start(any(), any());
    }
  }

  @Test
  void productionCannotInjectAnIndexBootFailure(@TempDir Path dir) throws Exception {
    WorkerHost host = mock(WorkerHost.class);
    when(host.start(any(), any())).thenThrow(new IOException("host reached"));
    try (var fixture = KnowledgeServerBootstrapTestFixture.create(config(dir, true), host)) {
      IOException failure = assertThrows(IOException.class, fixture.bootstrap()::start);
      assertEquals("host reached", failure.getMessage());
      verify(host).start(any(), any());
    }
  }

  @Test
  void failedInProcessCloseRetainsAppLockUntilHostRetry(@TempDir Path dir) throws Exception {
    WorkerHost host = mock(WorkerHost.class);
    var lock = mock(io.justsearch.app.util.AppInstanceLock.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("native lease held"))
        .doNothing().when(host).close();
    try (var fixture = KnowledgeServerBootstrapTestFixture.create(config(dir, true), host)) {
      var bootstrap = fixture.bootstrap();
      var lockField = KnowledgeServerBootstrap.class.getDeclaredField("appLock");
      lockField.setAccessible(true);
      lockField.set(bootstrap, lock);

      assertEquals(ShutdownOutcome.FAILED, bootstrap.closeForUpgrade());
      verify(lock, never()).close();
      org.junit.jupiter.api.Assertions.assertSame(lock, lockField.get(bootstrap));

      assertEquals(ShutdownOutcome.GRACEFUL, bootstrap.closeForUpgrade());
      verify(lock).close();
      org.junit.jupiter.api.Assertions.assertNull(lockField.get(bootstrap));
    }
  }

  private static KnowledgeServerConfig config(Path dir, boolean production) {
    return new KnowledgeServerConfig(
        production, dir, dir, dir, 5000, 2000, 3, 2000, 1000, 300000, 100, 0, 3);
  }
}
