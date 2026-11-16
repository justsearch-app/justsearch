/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.core.scheduling.IndexingHold;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.ListAllDocumentIdsResponse;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** S6c-6 (C1'): GPL stays held while "read waiting files now" releases file reading. */
final class ReadNowGplIsolationTest {
  private static Object call(Object target, String name, Object... args) {
    return assertDoesNotThrow(() -> {
      for (var method : target.getClass().getMethods()) {
        if (method.getName().equals(name) && method.getParameterCount() == args.length) {
          return method.invoke(target, args);
        }
      }
      throw new NoSuchMethodException(name);
    }, "IndexingHold." + name);
  }

  @Test
  void gplWaitsWhileFileReadingIsReleased(@TempDir Path directory) throws Exception {
    var hold = new IndexingHold();
    hold.setOnBattery(true);
    hold.setPolicy(false, null, null, null, true);
    assertEquals("STARTED", call(call(hold, "readWaitingNow", true), "outcome").toString());
    var client = mock(KnowledgeClient.class, invocation ->
        "indexingHold".equals(invocation.getMethod().getName()) ? hold : RETURNS_DEFAULTS.answer(invocation));
    when(client.listAllDocumentIds(anyInt(), anyInt(), any())).thenReturn(
        ListAllDocumentIdsResponse.newBuilder().addDocIds("doc").setTotalCount(1).build());
    when(client.fetchDocuments(any(), any())).thenReturn(FetchDocumentsResponse.newBuilder()
        .addDocuments(DocumentContent.newBuilder().setDocId("doc").setFound(true).setContent("hello"))
        .build());
    var ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    var entered = new CountDownLatch(1);
    when(ai.chatCompletion(any(), anyInt(), any(), any())).thenAnswer(ignored -> {
      entered.countDown();
      return CompletableFuture.completedFuture("");
    });
    try (var executors = new TestEngineExecutors();
        var coordinator = new GplJobCoordinator(executors, () -> client, ai, false,
            new GplTrainingTripleStore(directory))) {
      try {
        assertTrue(coordinator.runAsync());
        assertFalse(entered.await(300, TimeUnit.MILLISECONDS), "GPL generation stays held");
        hold.setOnBattery(false);
        assertTrue(entered.await(5, TimeUnit.SECONDS), "it runs once the rule clears");
        assertTrue(coordinator.awaitCompletion(5, TimeUnit.SECONDS));
      } finally {
        hold.setPolicy(false, null, null, null, false);
      }
    }
  }
}
