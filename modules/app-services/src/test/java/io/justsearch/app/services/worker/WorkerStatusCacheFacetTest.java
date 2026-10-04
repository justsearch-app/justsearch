/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.FacetCounts;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class WorkerStatusCacheFacetTest {
  private static final long REFRESH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5);

  @Test
  void emptySnapshotsAreCachedUntilIntervalExpires() {
    for (SearchResponse empty : new SearchResponse[] {
        SearchResponse.getDefaultInstance(),
        SearchResponse.newBuilder()
            .putFacets("meta_source", FacetCounts.getDefaultInstance()).build()
    }) {
      var fixture = new Fixture();
      fixture.respondWith(empty);

      fixture.refresh();
      for (int i = 0; i < 100; i++) fixture.refresh();
      fixture.clock.addAndGet(REFRESH_INTERVAL_MS - 1);
      fixture.refresh();
      fixture.verifyProbes(1);
      assertEquals("", fixture.cache.getCachedFacetSnapshot());
      assertEquals(Set.of(), fixture.cache.sourceVocabulary());

      fixture.clock.incrementAndGet();
      fixture.refresh();
      fixture.refresh();
      fixture.verifyProbes(2);
    }
  }

  @Test
  void expiredEmptyCachePicksUpNewFacets() {
    var fixture = new Fixture();
    fixture.respondWith(SearchResponse.getDefaultInstance());
    fixture.refresh();

    fixture.respondWith(sourceFacets());
    fixture.clock.addAndGet(REFRESH_INTERVAL_MS - 1);
    fixture.refresh();
    assertEquals("", fixture.cache.getCachedFacetSnapshot());
    fixture.verifyProbes(1);

    fixture.clock.incrementAndGet();
    fixture.refresh();
    assertEquals("Known index contents:\nmeta_source: Email (3)",
        fixture.cache.getCachedFacetSnapshot());
    assertEquals(Set.of("email"), fixture.cache.sourceVocabulary());
    fixture.refresh();
    fixture.verifyProbes(2);
  }

  @Test
  void expiredPopulatedCacheClearsGroundingOnEmptyRefresh() {
    var fixture = new Fixture();
    fixture.respondWith(sourceFacets());
    fixture.refresh();

    fixture.respondWith(SearchResponse.getDefaultInstance());
    fixture.clock.addAndGet(REFRESH_INTERVAL_MS);
    fixture.refresh();
    assertEquals("", fixture.cache.getCachedFacetSnapshot());
    assertEquals(Set.of(), fixture.cache.sourceVocabulary());
    fixture.refresh();
    fixture.verifyProbes(2);
  }

  @Test
  void concurrentEmptyCacheMissesClaimOnlyOneProbe() throws Exception {
    var fixture = new Fixture();
    fixture.respondWith(SearchResponse.getDefaultInstance());
    var bothMissed = new CountDownLatch(2);
    when(fixture.bootstrap.isReady()).thenAnswer(invocation -> {
      bothMissed.countDown();
      assertTrue(bothMissed.await(5, TimeUnit.SECONDS), "Both callers must observe a stale cache");
      return true;
    });

    CompletableFuture.allOf(
        CompletableFuture.runAsync(fixture::refresh),
        CompletableFuture.runAsync(fixture::refresh)).get(10, TimeUnit.SECONDS);

    fixture.verifyProbes(1);
  }

  @Test
  void failedProbeStillAllowsImmediateRetry() {
    var fixture = new Fixture();
    when(fixture.client.search(any(SearchRequest.class), any(EngineContext.class)))
        .thenThrow(new IllegalStateException("worker unavailable"))
        .thenReturn(SearchResponse.getDefaultInstance());

    fixture.refresh();
    fixture.refresh();
    fixture.refresh();

    fixture.verifyProbes(2);
  }

  @Test
  void workerNotReadyDoesNotConsumeRefreshInterval() {
    var fixture = new Fixture();
    fixture.respondWith(SearchResponse.getDefaultInstance());
    when(fixture.bootstrap.isReady()).thenReturn(false);
    fixture.refresh();
    fixture.verifyProbes(0);

    when(fixture.bootstrap.isReady()).thenReturn(true);
    fixture.refresh();
    fixture.refresh();
    fixture.verifyProbes(1);
  }

  private static SearchResponse sourceFacets() {
    return SearchResponse.newBuilder()
        .putFacets("meta_source", FacetCounts.newBuilder().putCounts("Email", 3L).build())
        .build();
  }

  private static final class Fixture {
    private final KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    private final KnowledgeClient client = mock(KnowledgeClient.class);
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final WorkerStatusCache cache = new WorkerStatusCache(bootstrap, clock::get);
    private final EngineContext caller = TestEngineContexts.ui();

    private Fixture() {
      when(bootstrap.isReady()).thenReturn(true);
    }

    private void respondWith(SearchResponse response) {
      when(client.search(any(SearchRequest.class), any(EngineContext.class))).thenReturn(response);
    }

    private void refresh() {
      cache.refreshFacetSnapshotIfStale(caller, client);
    }

    private void verifyProbes(int count) {
      verify(client, times(count)).search(any(SearchRequest.class), any(EngineContext.class));
    }
  }
}
