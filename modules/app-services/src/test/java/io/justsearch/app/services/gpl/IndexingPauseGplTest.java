/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.ListAllDocumentIdsResponse;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** S36.1/5: GPL admission and its progress publication participate in the same hold. */
final class IndexingPauseGplTest {
  private Object call(Object target, String name) {
    return assertDoesNotThrow(() -> target.getClass().getMethod(name).invoke(target));
  }

  private void pause(Object hold) {
    assertDoesNotThrow(() -> hold.getClass().getMethod("pause", Long.class, String.class)
        .invoke(hold, null, "USER_PAUSED"));
  }

  @Test
  void queuedGplWaitsAndActiveGenerationKeepsPausingUntilItsOutcomeIsPublished(@TempDir Path directory)
      throws Exception {
    Object hold = assertDoesNotThrow(() -> Class.forName("io.justsearch.core.scheduling.IndexingHold")
        .getConstructor().newInstance(), "GPL must share the acknowledged hold");
    var client = mock(KnowledgeClient.class, invocation ->
        "indexingHold".equals(invocation.getMethod().getName()) ? hold : RETURNS_DEFAULTS.answer(invocation));
    when(client.listAllDocumentIds(anyInt(), anyInt(), any())).thenReturn(
        ListAllDocumentIdsResponse.newBuilder().addDocIds("doc").setTotalCount(1).build());
    when(client.fetchDocuments(any(), any())).thenReturn(FetchDocumentsResponse.newBuilder()
        .addDocuments(DocumentContent.newBuilder().setDocId("doc").setFound(true).setContent("hello"))
        .build());
    var ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    var generated = new CompletableFuture<String>();
    var entered = new CountDownLatch(1);
    when(ai.chatCompletion(any(), anyInt(), any(), any())).thenAnswer(ignored -> {
      entered.countDown();
      return generated;
    });
    pause(hold);
    try (var executors = new TestEngineExecutors();
        var coordinator = new GplJobCoordinator(executors, () -> client, ai, false,
            new GplTrainingTripleStore(directory))) {
      try {
        assertTrue(coordinator.runAsync());
        assertFalse(entered.await(150, TimeUnit.MILLISECONDS));
        call(hold, "resume");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        pause(hold);
        assertEquals("PAUSING", call(call(hold, "snapshot"), "state").toString());
        generated.complete("");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!"PAUSED".equals(call(call(hold, "snapshot"), "state").toString())
            && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals("PAUSED", call(call(hold, "snapshot"), "state").toString());
        assertEquals(1, coordinator.getStatus().processedDocs());
        assertFalse(coordinator.awaitCompletion(150, TimeUnit.MILLISECONDS), "remaining GPL work waits for Resume");
        call(hold, "resume");
        assertTrue(coordinator.awaitCompletion(5, TimeUnit.SECONDS));
      } finally {
        generated.complete("");
        call(hold, "resume");
      }
    }
  }
}
