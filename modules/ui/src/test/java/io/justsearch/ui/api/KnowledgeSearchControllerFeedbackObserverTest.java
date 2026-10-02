/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.feedback.FeatureSnapshot;
import io.justsearch.app.services.feedback.FeedbackObserver;
import io.justsearch.app.services.feedback.FeedbackLookupMaintenance;
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
  void lockedBootResumesOnUnlockAndCapturesAHistoricalCitationWithoutAnotherSearch(@TempDir Path dir)
      throws Exception {
    var keys = new io.justsearch.app.services.encryption.DataKeyManager(
        new io.justsearch.app.services.encryption.EncryptionKeystore(dir));
    keys.setup("passphrase".toCharArray());
    var cipher = spy(new io.justsearch.agent.api.encryption.StoreCipher(keys));
    Path archive = dir.resolve("snapshots.ndjson");
    String legacy = cipher.seal(new tools.jackson.databind.ObjectMapper().writeValueAsString(
        new FeatureSnapshot("historical", "q", 1L, List.of(
            new FeatureSnapshot.HitFeatures("uid", "path", 1, 1f, 0f, 0f, 1f, null))))) + "\n";
    Files.writeString(archive, legacy);
    keys.lock();
    var bootCheckedLocked = new CountDownLatch(1);
    doAnswer(invocation -> {
      boolean locked = (Boolean) invocation.callRealMethod();
      if (locked) bootCheckedLocked.countDown();
      return locked;
    }).when(cipher).locked();
    doAnswer(invocation -> {
      assertFalse(Thread.holdsLock(keys), "backfill must run outside the synchronized unlock listener");
      return invocation.callRealMethod();
    }).when(cipher).open(anyString());
    try (var registry = TestEngineExecutors.awaitingTermination();
        var observer = new FeedbackObserver(registry);
        var maintenance = new FeedbackLookupMaintenance(registry, archive, cipher, keys)) {
      assertTrue(bootCheckedLocked.await(2, TimeUnit.SECONDS));
      assertFalse(maintenance.ready().isDone());
      keys.unlock("passphrase".toCharArray());
      maintenance.ready().get(5, TimeUnit.SECONDS);

      var dispositions = new NdjsonAppendStore<>(dir.resolve("dispositions.ndjson"), ResultDisposition.class, cipher);
      var controller = controller(observer);
      inject(controller, "featureSnapshots", new NdjsonAppendStore<>(archive, FeatureSnapshot.class, cipher));
      inject(controller, "dispositions", dispositions);
      var ctx = context(Map.of("interactionId", "historical", "docId", "path", "kind", "OPENED",
          "contributor", "chat-citation"));
      controller.handleDisposition(ctx);
      verify(ctx).status(204);
      drain(observer);
      var rows = dispositions.readAll();
      assertEquals(1, rows.size());
      assertEquals("uid", rows.getFirst().docId());
      assertEquals(ResultDisposition.Contributor.USER_CITATION_CLICK, rows.getFirst().contributor());
      assertEquals(legacy, Files.readString(archive), "unlock recovery must not require a new capture");
    } finally {
      keys.lock();
    }
  }

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
