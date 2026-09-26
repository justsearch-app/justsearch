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

import io.justsearch.app.api.DocumentService;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentsResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

  @Test
  void fetchBatchRetainsOneCaptureAcrossPagesAndReleasesAfterStageCompletion() {
    AtomicReference<Runnable> queued = new AtomicReference<>();
    AtomicReference<CompletableFuture<Map<String, DocumentService.DocumentRecord>>> stageRef =
        new AtomicReference<>();
    AtomicInteger captures = new AtomicInteger();
    AtomicInteger closes = new AtomicInteger();
    AtomicBoolean closedAfterCompletion = new AtomicBoolean();
    List<List<String>> pages = Collections.synchronizedList(new ArrayList<>());
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.fetchDocuments(anyList(), any()))
        .thenAnswer(
            invocation -> {
              List<String> ids = List.copyOf((List<String>) invocation.getArgument(0));
              pages.add(ids);
              FetchDocumentsResponse.Builder response = FetchDocumentsResponse.newBuilder();
              ids.forEach(id -> response.addDocuments(
                  DocumentContent.newBuilder().setDocId(id).setFound(true).build()));
              return response.build();
            });

    RemoteDocumentService service =
        new RemoteDocumentService(
            queued::set,
            Runnable::run,
            () -> client,
            RagMetricCatalog.noop(),
            () -> {
              captures.incrementAndGet();
              return new RemoteDocumentService.ClientCapture() {
                @Override
                public KnowledgeClient client() {
                  return client;
                }

                @Override
                public void close() {
                  closes.incrementAndGet();
                  closedAfterCompletion.set(stageRef.get().isDone());
                }
              };
            });

    int pageSize = BoundedDocumentFetch.maxDocsPerRequest(BoundedDocumentFetch.DEFAULT_BYTE_BUDGET);
    List<String> ids = java.util.stream.IntStream.range(0, pageSize + 1)
        .mapToObj(i -> "doc-" + i)
        .toList();
    var stage = service.fetchBatch(ids, TestEngineContexts.internal()).toCompletableFuture();
    stageRef.set(stage);

    assertEquals(1, captures.get(), "one physical capture must cover the whole queued operation");
    assertEquals(0, closes.get(), "the capture must remain held until work completes");
    queued.get().run();

    assertEquals(ids.size(), stage.join().size());
    assertEquals(2, pages.size(), "the test must exercise more than one bounded fetch page");
    assertEquals(1, closes.get(), "the physical capture must be released exactly once");
    assertTrue(closedAfterCompletion.get(), "release must happen after the returned stage completes");
  }

  @Test
  void cancellingQueuedCapturedRequestReleasesCaptureWithoutInvokingClient() {
    AtomicReference<Runnable> queued = new AtomicReference<>();
    AtomicInteger closes = new AtomicInteger();
    KnowledgeClient client = mock(KnowledgeClient.class);
    RemoteDocumentService service =
        new RemoteDocumentService(
            queued::set,
            Runnable::run,
            () -> client,
            RagMetricCatalog.noop(),
            () -> new RemoteDocumentService.ClientCapture() {
              @Override
              public KnowledgeClient client() {
                return client;
              }

              @Override
              public void close() {
                closes.incrementAndGet();
              }
            });

    var stage =
        service.fetchBatch(List.of("queued-captured"), TestEngineContexts.internal())
            .toCompletableFuture();
    assertTrue(((Future<?>) queued.get()).cancel(false));
    assertTrue(stage.isDone());
    assertEquals(1, closes.get(), "queued cancellation must release the captured lease");
    verify(client, never()).fetchDocuments(anyList(), any());
  }

  @Test
  void boundDocumentOperationUsesEnclosingAClientAfterCurrentCaptureWouldSelectB() {
    AtomicReference<Runnable> queued = new AtomicReference<>();
    AtomicInteger ordinaryCaptures = new AtomicInteger();
    AtomicBoolean enclosingClosed = new AtomicBoolean();
    KnowledgeClient clientA = mock(KnowledgeClient.class);
    KnowledgeClient clientB = mock(KnowledgeClient.class);
    when(clientA.fetchDocuments(anyList(), any())).thenAnswer(invocation ->
        FetchDocumentsResponse.newBuilder().addDocuments(DocumentContent.newBuilder()
            .setDocId("held-a").setFound(true).build()).build());
    RemoteDocumentService service = new RemoteDocumentService(queued::set, Runnable::run,
        () -> clientB, RagMetricCatalog.noop(), () -> {
          ordinaryCaptures.incrementAndGet();
          throw new AssertionError("bound operation reacquired the current B client");
        });
    RemoteDocumentService.ClientCapture enclosing = new RemoteDocumentService.ClientCapture() {
      @Override public KnowledgeClient client() { return clientA; }
      @Override public <T> T withClient(java.util.function.Function<KnowledgeClient, T> action) {
        if (enclosingClosed.get()) throw new IllegalStateException("A capture already closed");
        return action.apply(clientA);
      }
      @Override public void close() { enclosingClosed.set(true); }
    };

    var stage = service.boundTo(enclosing).fetchBatch(List.of("held-a"),
        TestEngineContexts.internal()).toCompletableFuture();
    assertFalse(stage.isDone());
    queued.get().run();

    assertTrue(stage.join().containsKey("held-a"));
    assertEquals(0, ordinaryCaptures.get());
    verify(clientA).fetchDocuments(anyList(), any());
    verify(clientB, never()).fetchDocuments(anyList(), any());
    assertFalse(enclosingClosed.get(), "nested document stage cannot close its parent capture");
    enclosing.close();
  }

  private static java.util.concurrent.ThreadFactory namedDaemonFactory(String name) {
    return runnable -> {
      Thread thread = new Thread(runnable, name);
      thread.setDaemon(true);
      return thread;
    };
  }
}
