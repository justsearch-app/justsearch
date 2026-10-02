/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.feedback.FeatureSnapshot;
import io.justsearch.app.services.feedback.FeedbackObserver;
import io.justsearch.app.services.feedback.NdjsonAppendStore;
import io.justsearch.app.services.feedback.ResultDisposition;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeSearchControllerFeedbackObserverTest {
  @Test
  void searchRespondsWhileSnapshotPersistenceIsBlocked(@TempDir Path dir) throws Exception {
    try (var registry = TestEngineExecutors.awaitingTermination();
        var observer = new FeedbackObserver(registry)) {
      var snapshots = new NdjsonAppendStore<>(dir.resolve("snapshots.ndjson"), FeatureSnapshot.class);
      var controller = controller(observer);
      inject(controller, "featureSnapshots", snapshots);
      var ctx = context(Map.of("query", "needle", "pipeline", Map.of()));
      synchronized (snapshots) {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> controller.handleSearch(ctx));
        verify(ctx).json(any());
        verify(ctx, never()).status(500);
      }
      drain(observer);
      assertEquals(1, snapshots.readAll().size(), "capture still persists after releasing the writer");
    }
  }

  @Test
  void dispositionResolvesOneIdentityEvenWhenTheArchiveCannotBeParsed(@TempDir Path dir)
      throws Exception {
    try (var registry = TestEngineExecutors.awaitingTermination();
        var observer = new FeedbackObserver(registry)) {
      Path archive = dir.resolve("snapshots.ndjson");
      var snapshots = new NdjsonAppendStore<>(archive, FeatureSnapshot.class);
      snapshots.append(new FeatureSnapshot("iid", "q", 1L, List.of(
          new FeatureSnapshot.HitFeatures("uid", "path", 1, 1f, 0f, 0f, 1f, null))));
      Files.writeString(archive, "invalid historical JSON\n");
      var dispositions = new NdjsonAppendStore<>(dir.resolve("dispositions.ndjson"), ResultDisposition.class);
      var controller = controller(observer);
      inject(controller, "featureSnapshots", snapshots);
      inject(controller, "dispositions", dispositions);
      var ctx = context(Map.of("interactionId", "iid", "docId", "path", "kind", "OPENED"));
      controller.handleDisposition(ctx);
      verify(ctx).status(204);
      drain(observer);
      assertEquals(1, dispositions.readAll().size());
      assertEquals("uid", dispositions.readAll().getFirst().docId());
    }
  }

  private static KnowledgeSearchController controller(FeedbackObserver observer) {
    var config = new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.qu.enabled", "false", "justsearch.filter_norm.enabled", "false")));
    var client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any(EngineContext.class)))
        .thenReturn(SearchResponse.getDefaultInstance());
    var bootstrap = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(config.publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(lease.client()).thenReturn(client);
    when(lease.withClient(any())).thenAnswer(invocation ->
        ((java.util.function.Function<KnowledgeClient, ?>) invocation.getArgument(0)).apply(client));
    var controller = new KnowledgeSearchController(bootstrap, mock(SearchPerSourceExecutor.class),
        null, OnlineAiService.unavailable(), null, null, config);
    controller.setFeedbackObserver(observer);
    return controller;
  }

  private static Context context(Map<String, Object> body) {
    var ctx = mock(Context.class);
    when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
    when(ctx.path()).thenReturn("/api/knowledge/search");
    when(ctx.bodyAsClass(Map.class)).thenReturn(body);
    when(ctx.status(anyInt())).thenReturn(ctx);
    return ctx;
  }

  private static void inject(Object owner, String name, Object value) throws Exception {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private static void drain(FeedbackObserver observer) throws Exception {
    var drained = new CountDownLatch(1);
    assertTrue(observer.observe(drained::countDown));
    assertTrue(drained.await(2, TimeUnit.SECONDS));
  }
}
