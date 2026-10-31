/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.lifecycle.Capability;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.lifecycle.ReasonRetainingComponentHandle;
import io.justsearch.app.services.lifecycle.RegistryBackedCapability;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.StatusResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class WorkerStatusCacheContextTest {

  @Test
  void alternateStatusKeepsUnknownEnrichmentAndTheOriginalCountAge() {
    try (var readyCapability = ReadyCapability.create()) {
      KnowledgeClient client = mock(KnowledgeClient.class);
      var status = StatusResponse.newBuilder()
          .putCountObservations("active", io.justsearch.ipc.CountObservation.newBuilder()
              .setState("stale").setObservedAtMs(1234).build())
          .setEnrichment(io.justsearch.ipc.EnrichmentCoverage.newBuilder().setCompletenessKnown(false)
              .setEmbedding(io.justsearch.ipc.FeatureCoverage.newBuilder().setCoveragePercent(-1))
              .setSplade(io.justsearch.ipc.FeatureCoverage.newBuilder().setCoveragePercent(-1))
              .setChunk(io.justsearch.ipc.ChunkCoverage.newBuilder().setDocCount(-1)
                  .setPendingCount(-1).setCoveragePercent(-1).setSpladePendingCount(-1))
              .setPendingNerCount(-1).setCompletedNerCount(-1))
          .build();
      when(client.getStatus(org.mockito.ArgumentMatchers.any())).thenReturn(status);
      var cache = new WorkerStatusCache(readyBootstrap(client));
      cache.setWorkerCapability(readyCapability.view());
      var extras = cache.status(TestEngineContexts.mcp()).extras();
      org.junit.jupiter.api.Assertions.assertEquals(-1.0, extras.get("embeddingCoveragePercent"));
      org.junit.jupiter.api.Assertions.assertEquals(-1.0, extras.get("spladeCoveragePercent"));
      org.junit.jupiter.api.Assertions.assertEquals(-1L, ((Number) extras.get("pendingNerCount")).longValue());
      org.junit.jupiter.api.Assertions.assertEquals(-1L, extras.get("chunkEmbeddingPendingCount"));
      org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE, extras.get("completenessKnown"));
      var observations = (java.util.Map<?, ?>) extras.get("countObservations");
      var active = (io.justsearch.app.api.status.CountObservationView) observations.get("active");
      org.junit.jupiter.api.Assertions.assertEquals("stale", active.state());
      org.junit.jupiter.api.Assertions.assertEquals(1234, active.observedAtMs());
    }
  }

  @Test
  void synchronousStatusMissForwardsTheSuppliedCallerContext() {
    try (var readyCapability = ReadyCapability.create()) {
      KnowledgeClient client = mock(KnowledgeClient.class);
      KnowledgeServerBootstrap bootstrap = readyBootstrap(client);
      when(client.getStatus(org.mockito.ArgumentMatchers.any()))
          .thenReturn(StatusResponse.getDefaultInstance());
      WorkerStatusCache cache = new WorkerStatusCache(bootstrap);
      cache.setWorkerCapability(readyCapability.view());
      EngineContext caller = TestEngineContexts.mcp();

      cache.status(caller);

      ArgumentCaptor<EngineContext> context = ArgumentCaptor.forClass(EngineContext.class);
      verify(client).getStatus(context.capture());
      assertSame(caller, context.getValue());
    }
  }

  @Test
  void synchronousFacetMissForwardsTheSuppliedCallerContext() {
    try (var readyCapability = ReadyCapability.create()) {
      KnowledgeClient client = mock(KnowledgeClient.class);
      KnowledgeServerBootstrap bootstrap = readyBootstrap(client);
      when(client.search(
              org.mockito.ArgumentMatchers.any(SearchRequest.class),
              org.mockito.ArgumentMatchers.any()))
          .thenReturn(SearchResponse.getDefaultInstance());
      WorkerStatusCache cache = new WorkerStatusCache(bootstrap);
      cache.setWorkerCapability(readyCapability.view());
      EngineContext caller = TestEngineContexts.ui();

      cache.refreshFacetSnapshotIfStale(caller, client);

      ArgumentCaptor<EngineContext> context = ArgumentCaptor.forClass(EngineContext.class);
      verify(client)
          .search(org.mockito.ArgumentMatchers.any(SearchRequest.class), context.capture());
      assertSame(caller, context.getValue());
    }
  }

  private static KnowledgeServerBootstrap readyBootstrap(KnowledgeClient client) {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    KnowledgeServerBootstrap.ClientLease lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.captureClient()).thenReturn(lease);
    when(lease.client()).thenReturn(client);
    when(lease.withClient(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
        ((java.util.function.Function<KnowledgeClient, ?>) invocation.getArgument(0))
            .apply(client));
    return bootstrap;
  }

  private record ReadyCapability(TestEngineComponents components, Capability view)
      implements AutoCloseable {
    private static ReadyCapability create() {
      var components = TestEngineComponents.fourComponents();
      var producer = new ReasonRetainingComponentHandle(components.handle("index"));
      producer.transition(ComponentState.READY, null, null);
      return new ReadyCapability(
          components, new RegistryBackedCapability(components, "index", "worker"));
    }

    @Override
    public void close() {
      components.close();
    }
  }
}
