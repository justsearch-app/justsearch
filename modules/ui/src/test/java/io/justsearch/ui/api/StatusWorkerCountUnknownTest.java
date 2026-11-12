/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.StatusResponse;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.services.lifecycle.RegistryBackedCapability;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * When the Engine has no reading from the Worker, the status it publishes says the document counts
 * are unknown. Its placeholder zeros never read as a known empty index; a Worker that answers, with
 * any count including zero, is read as known.
 */
final class StatusWorkerCountUnknownTest {
  @TempDir Path indexBase;

  @Test
  @Timeout(60)
  void beforeTheWorkerConnectsTheCountsAreUnknown() {
    var server = mock(KnowledgeServerBootstrap.class);
    when(server.hasClient()).thenReturn(false);
    var handler = handler(server);

    assertCountsUnknown(handler.buildStatusSnapshot(), "the initial status");
    assertCountsUnknown(handler.sampleAndBuildStatusSnapshot(), "a sample with no Worker client");
  }

  @Test
  @Timeout(60)
  void whileTheWorkersFirstAnswerIsSlowTheCountsAreUnknown() throws Exception {
    var answering = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var handler = handler(connected(() -> {
      answering.countDown();
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return counted(5);
    }));
    try {
      var waiting = Thread.ofPlatform().daemon().start(handler::sampleAndBuildStatusSnapshot);
      assertTrue(answering.await(10, TimeUnit.SECONDS), "the sampler asked the Worker");
      assertCountsUnknown(handler.buildStatusSnapshot(), "status while the first answer is pending");
      release.countDown();
      waiting.join(TimeUnit.SECONDS.toMillis(20));
    } finally {
      release.countDown();
    }
    assertCountsKnown(handler.sampleAndBuildStatusSnapshot(), 5, "status after the first answer");
  }

  @Test
  @Timeout(60)
  void aWorkerRpcFailureMidSessionMakesTheCountsUnknownUntilTheWorkerAnswersAgain() {
    var answer = new AtomicReference<Supplier<WorkerOperationalView>>(() -> counted(5));
    var handler = handler(connected(() -> answer.get().get()));
    assertCountsKnown(handler.sampleAndBuildStatusSnapshot(), 5, "status while the Worker answers");

    answer.set(() -> {
      throw new IllegalStateException("worker connection lost");
    });
    StatusResponse outage = handler.sampleAndBuildStatusSnapshot();
    assertTrue(outage.meta().workerRpcStale(), "the failed RPC is reported as lost contact");
    assertCountsUnknown(outage, "status after the Worker RPC failed");
    assertNotEquals("READY", chunkEmbedding(outage),
        "passage embeddings are not ready because of an empty index nobody counted");

    answer.set(() -> counted(5));
    assertCountsKnown(handler.sampleAndBuildStatusSnapshot(), 5, "status once the Worker is back");
  }

  @Test
  @Timeout(60)
  void aConnectedWorkerWithNoDocumentsReportsAKnownEmptyIndex() {
    var handler = handler(connected(() -> counted(0)));
    StatusResponse empty = handler.sampleAndBuildStatusSnapshot();

    assertCountsKnown(empty, 0, "status of a counted empty index");
    assertEquals("READY", chunkEmbedding(empty), "an empty corpus has no passages left to embed");
  }

  private static void assertCountsUnknown(StatusResponse status, String when) {
    CoreIndexView core = status.worker().core();
    assertTrue(core.indexedDocumentsUnknown(),
        when + " must say the document counts are unknown, not a known " + core.indexedDocuments());
    assertNull(core.ownSearchableDocuments(), when + " has no own-document count");
  }

  private static void assertCountsKnown(StatusResponse status, long count, String when) {
    CoreIndexView core = status.worker().core();
    assertFalse(core.indexedDocumentsUnknown(), when + " carries the Worker's counts");
    assertEquals(count, core.indexedDocuments(), when);
    assertEquals(count, core.searchableDocuments(), when);
  }

  private static String chunkEmbedding(StatusResponse status) {
    return status.readiness().components().get("chunkEmbedding").state();
  }

  private static WorkerOperationalView counted(long documents) {
    return WorkerOperationalViewBuilder.from(WorkerOperationalView.fallback("SERVING"))
        .withCore(new CoreIndexView(true, documents, 0, "IDLE", 0, 0));
  }

  private static KnowledgeServerBootstrap connected(Supplier<WorkerOperationalView> answer) {
    var client = mock(KnowledgeClient.class);
    when(client.getWorkerOperationalView(any())).thenAnswer(ignored -> answer.get());
    var server = mock(KnowledgeServerBootstrap.class);
    when(server.hasClient()).thenReturn(true);
    BootstrapLeaseFixtures.bind(server, client);
    return server;
  }

  private StatusLifecycleHandler handler(KnowledgeServerBootstrap server) {
    var components = TestEngineComponents.fourComponents();
    components.handle("api").transition(ComponentState.READY, null, null);
    components.handle("index").transition(ComponentState.STARTING, "index.starting", null);
    var handler = new StatusLifecycleHandler(
        mock(OnlineAiService.class), mock(io.justsearch.agent.api.AgentService.class), () -> null,
        server, null, indexBase, Instant.now(), () -> "OK", null, null, null,
        new RegistryBackedCapability(components, "index", "worker"),
        new RegistryBackedCapability(components, "generative", "inference"));
    handler.setIndexComponent(components, components.handle("index"));
    return handler;
  }
}
