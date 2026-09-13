/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.RunEventStore;
import io.justsearch.app.engine.DefaultEngineExecutorRegistry;
import io.justsearch.app.services.agenthistory.AgentHistoryIndexer;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class AgentHistoryExecutorRecoveryTest {
  @TempDir Path dataDir;

  @Test
  @Timeout(20)
  void refusedTerminalEventIsReconciledFromTheDurableStoreWithoutRestart() throws Exception {
    Path eventsDir = Files.createDirectories(dataDir.resolve("runs"));
    Path historyDir = dataDir.resolve("history");
    var store = new RunEventStore(eventsDir);
    var firstScan = new CountDownLatch(1);
    var blocked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var recovered = new CountDownLatch(1);
    KnowledgeClient client = mock(KnowledgeClient.class);
    doAnswer(invocation -> {
      List<Path> documents = invocation.getArgument(0);
      String name = documents.getFirst().getFileName().toString();
      if (name.equals("blocker.md")) { blocked.countDown(); release.await(); }
      if (name.equals("overflow.md")) recovered.countDown();
      return null;
    }).when(client).submitBatch(anyList(), eq(true), eq(AgentHistoryIndexer.COLLECTION), any());

    try (var registry = new DefaultEngineExecutorRegistry();
        var indexer = AgentHistoryIndexer.register(registry, store::addEventListener, historyDir, () -> client)) {
      try {
        indexer.reconcile(() -> {
          try (var paths = Files.list(eventsDir)) {
            List<String> ids = paths.map(path -> path.getFileName().toString()).sorted().toList();
            firstScan.countDown();
            return ids;
          } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }, store::readEvents);
        assertTrue(firstScan.await(3, TimeUnit.SECONDS));
        assertNotNull(store.appendEvent("blocker", "core.agent-run", "done", Map.of("finalResponse", "blocked")));
        assertTrue(blocked.await(3, TimeUnit.SECONDS));
        int capacity = registry.limits(EngineExecutorSpec.Kind.BACKGROUND).maxQueue();
        for (int i = 0; i < capacity; i++) {
          assertNotNull(store.appendEvent("queued-" + i, "core.agent-run", "done", Map.of("finalResponse", "queued")));
        }
        var row = registry.snapshot().registrations().stream()
            .filter(item -> item.spec().name().equals("head.agent-history-indexer")).findFirst().orElseThrow();
        assertEquals(capacity, row.queuedTasks(), "the real executor must be full before the refusal");
        assertNotNull(store.appendEvent("overflow", "core.agent-run", "done", Map.of("finalResponse", "durable recovery marker")));
        assertEquals(1, store.readEvents("overflow").size(), "the refused event is already durable");
        assertFalse(Files.exists(historyDir.resolve("overflow.md")), "the listener must not do disk work inline");
        release.countDown();
        assertTrue(recovered.await(8, TimeUnit.SECONDS), "the owned retry timer must recover without another event or restart");
        assertTrue(Files.readString(historyDir.resolve("overflow.md")).contains("durable recovery marker"));
      } finally { release.countDown(); }
    }
  }
}
