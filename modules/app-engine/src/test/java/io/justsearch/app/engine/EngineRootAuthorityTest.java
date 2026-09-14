/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EngineRootAuthorityTest {
  @Test
  void clientUsesTheExactPreloadedStateWithoutReadingTheChangedFile(@TempDir Path directory) throws Exception {
    Path watched = Files.createDirectory(directory.resolve("watched")).toAbsolutePath().normalize();
    Path rootsFile = directory.resolve("watched_roots.json");
    Files.writeString(rootsFile, tools.jackson.databind.json.JsonMapper.builder().build()
        .writeValueAsString(List.of(watched.toString())));
    OperationAuthority authority = OperationAuthority.load(directory);
    assertEquals(List.of(watched), authority.roots().watchedPaths());
    Files.writeString(rootsFile, "{corrupt-after-preload");
    var server = mock(KnowledgeServer.class);
    when(server.foregroundLoad()).thenReturn(new ForegroundLoad());
    when(server.awaitClosed(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
    var root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class),
        (ignored, executors, ingestion) -> {
          EngineRootRecordedLifecycleTestSupport.bindOffline(server, ingestion);
          return server;
        }, 1_000, 100, ignored -> {}, () -> {}, authority);
    try {
      assertSame(authority, root.authority());
      var client = root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
      var field = KnowledgeClient.class.getDeclaredField("watchedRootsState");
      field.setAccessible(true);
      assertSame(authority.roots(), field.get(client));
      assertEquals(List.of(watched), client.getWatchedPaths(
          TestEngineContexts.FOREGROUND));
      assertSame(client, root.start(new GpuSchedulingGauge(), IpcTelemetry.noop()));
      assertEquals("{corrupt-after-preload", Files.readString(rootsFile));
    } finally {
      root.close();
      root.executors().close();
    }
  }
}
