/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentsResponse;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Verifies document calls stay on the urgency-specific executors supplied by the composition root. */
final class RemoteDocumentServiceExecutorTest {

  @Test
  void dispatchesForegroundAndBackgroundCallsWithoutUsingTheCommonPool() throws Exception {
    ExecutorService foreground =
        Executors.newSingleThreadExecutor(namedDaemonFactory("document-foreground"));
    ExecutorService background =
        Executors.newSingleThreadExecutor(namedDaemonFactory("document-background"));
    try {
      KnowledgeClient client = mock(KnowledgeClient.class);
      AtomicReference<String> threadName = new AtomicReference<>();
      AtomicReference<EngineContext> receivedContext = new AtomicReference<>();
      when(client.fetchDocuments(anyList(), any()))
          .thenAnswer(
              invocation -> {
                threadName.set(Thread.currentThread().getName());
                receivedContext.set(invocation.getArgument(1));
                String id = ((List<String>) invocation.getArgument(0)).get(0);
                return FetchDocumentsResponse.newBuilder()
                    .addDocuments(DocumentContent.newBuilder().setDocId(id).setFound(true).build())
                    .build();
              });

      RemoteDocumentService service =
          new RemoteDocumentService(foreground, background, () -> client);
      EngineContext foregroundContext = TestEngineContexts.internal();
      service.fetchBatch(List.of("fg"), foregroundContext).toCompletableFuture().join();
      assertEquals("document-foreground", threadName.get());
      assertEquals(foregroundContext, receivedContext.get());

      EngineContext backgroundContext =
          foregroundContext.withUrgency(EngineContext.Urgency.BACKGROUND);
      service.fetchBatch(List.of("bg"), backgroundContext).toCompletableFuture().join();
      assertEquals("document-background", threadName.get());
      assertEquals(backgroundContext, receivedContext.get());
      assertNotNull(threadName.get());
    } finally {
      foreground.shutdownNow();
      background.shutdownNow();
    }
  }

  @Test
  void cancellingQueuedRequestCompletesStageWithoutInvokingClient() {
    AtomicReference<Runnable> queued = new AtomicReference<>();
    KnowledgeClient client = mock(KnowledgeClient.class);
    RemoteDocumentService service =
        new RemoteDocumentService(queued::set, Runnable::run, () -> client);

    var stage =
        service
            .fetchBatch(List.of("queued"), TestEngineContexts.internal())
            .toCompletableFuture();

    assertFalse(stage.isDone(), "a request held by the executor must remain pending");
    Runnable task = queued.get();
    assertNotNull(task, "EngineFutures must submit a cancellable queued task");
    assertTrue(task instanceof Future<?>, "queued task must expose cancellation to registry draining");
    assertTrue(((Future<?>) task).cancel(false));
    assertTrue(stage.isDone(), "cancelling the queued task must complete the returned stage");
    verify(client, never()).fetchDocuments(anyList(), any());
  }

  private static java.util.concurrent.ThreadFactory namedDaemonFactory(String name) {
    return runnable -> {
      Thread thread = new Thread(runnable, name);
      thread.setDaemon(true);
      return thread;
    };
  }
}
