/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.gpl.GplJobStatus;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.GenerationTracker;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.ListAllDocumentIdsResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The GPL job runner treats a refusal from the generative gate while chat drains, starts or stops
 * as it treats AI being off: it waits and retries the document instead of aborting the job or
 * dropping the document (b6d536 item 5). The gate is the production {@link GenerationTracker},
 * closed as a drain closes it and reopened when the drain ends.
 */
class GplDeferralDuringChatDrainTest {

  @TempDir Path tempDir;

  private final KnowledgeClient knowledgeClient = mock(KnowledgeClient.class);
  private final OnlineAiService onlineAiService = mock(OnlineAiService.class);
  private final GenerationTracker gate = new GenerationTracker(work -> () -> {}, Runnable::run);
  private final AtomicInteger calls = new AtomicInteger();
  private TestEngineExecutors executors;
  private GplTrainingTripleStore tripleStore;
  private GplJobCoordinator coordinator;
  private ChatActuator.DrainHold drain;

  @BeforeEach
  void setUp() {
    executors = new TestEngineExecutors();
    tripleStore = new GplTrainingTripleStore(tempDir);
    coordinator =
        new GplJobCoordinator(executors, () -> knowledgeClient, onlineAiService, false, tripleStore);
    when(knowledgeClient.listAllDocumentIds(eq(0), eq(50), any()))
        .thenReturn(ListAllDocumentIdsResponse.newBuilder()
            .addDocIds("doc-1").setTotalCount(1).build());
    when(knowledgeClient.listAllDocumentIds(eq(1), eq(50), any()))
        .thenReturn(ListAllDocumentIdsResponse.newBuilder().setTotalCount(1).build());
    when(knowledgeClient.fetchDocuments(eq(List.of("doc-1")), any()))
        .thenReturn(FetchDocumentsResponse.newBuilder()
            .addDocuments(DocumentContent.newBuilder()
                .setDocId("doc-1").setContent("solar panels turn sunlight into power")
                .setFound(true).build())
            .build());
    when(onlineAiService.isAvailable()).thenReturn(true); // the mode stays ONLINE during a drain
    drain = gate.closeNow();
  }

  @AfterEach
  void tearDown() {
    drain.close();
    coordinator.close();
    executors.close();
  }

  @Test
  void aRefusalAtTheCallDefersTheDocumentAndTheJobCompletesAfterTheDrain() throws Exception {
    when(onlineAiService.chatCompletion(any(), anyInt(), any(), any(EngineContext.class)))
        .thenAnswer(ignored -> {
          if (calls.getAndIncrement() == 0) {
            try {
              gate.requireOpen(); // refused: chat is draining
            } finally {
              drain.close(); // the drain ends while the job waits
            }
          }
          return generated();
        });

    runToCompletion();

    GplJobStatus status = coordinator.getStatus();
    assertEquals(GplJobStatus.Status.COMPLETED, status.status(),
        "the job is not aborted by a transient refusal: " + status.lastError());
    assertEquals(1L, status.processedDocs());
    assertEquals(2L, tripleStore.count(), "the deferred document still yields its triples");
    assertEquals(2, calls.get(), "the document was retried once the gate reopened");
  }

  @Test
  void aRefusalThroughTheFutureDefersTheDocumentInsteadOfDroppingIt() throws Exception {
    when(onlineAiService.chatCompletion(any(), anyInt(), any(), any(EngineContext.class)))
        .thenAnswer(ignored -> {
          if (calls.getAndIncrement() == 0) {
            try {
              gate.admit(GenerationTracker.Kind.AUX, null).close();
              return CompletableFuture.completedFuture("unreachable");
            } catch (RuntimeException refused) {
              return CompletableFuture.failedFuture(refused);
            } finally {
              drain.close();
            }
          }
          return generated();
        });

    runToCompletion();

    assertEquals(GplJobStatus.Status.COMPLETED, coordinator.getStatus().status());
    assertEquals(2L, tripleStore.count(), "the deferred document still yields its triples");
    assertEquals(2, calls.get(), "the document was retried once the gate reopened");
  }

  private CompletableFuture<String> generated() {
    gate.admit(GenerationTracker.Kind.AUX, null).close(); // admitted: the drain has ended
    return CompletableFuture.completedFuture("how do solar panels work\nphotovoltaic power");
  }

  private void runToCompletion() throws InterruptedException {
    assertTrue(coordinator.runAsync());
    assertTrue(coordinator.awaitCompletion(20, TimeUnit.SECONDS), "job did not reach terminal state");
  }
}
